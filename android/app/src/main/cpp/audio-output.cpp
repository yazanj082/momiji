// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "audio-output.h"

#include "circular-buf.hpp"

#include <chiaki/log.h>
#include <chiaki/thread.h>

#include <oboe/Oboe.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <vector>

#define BUFFER_CHUNK_SIZE 1024
#define BUFFER_CHUNKS_COUNT 32

// The audio comes at the console's pace but plays at the device's, which can be a little slower,
// e.g. over HDMI. Then the buffer slowly fills up during a session, and the sound falls behind the
// picture, up to the buffer's whole size (about 170 ms), where the newest audio gets dropped.
// What is still queued when the buffer is at its lowest over CATCH_UP_WINDOW_MS is never needed:
// once that is more than CATCH_UP_KEEP_MS + CATCH_UP_SLACK_MS, it's skipped down to CATCH_UP_KEEP_MS.
#define CATCH_UP_WINDOW_MS 4000
#define CATCH_UP_KEEP_MS 20
#define CATCH_UP_SLACK_MS 20

using AudioBuffer = CircularBuffer<BUFFER_CHUNKS_COUNT, BUFFER_CHUNK_SIZE>;

class AudioOutput;

class AudioOutputCallback: public oboe::AudioStreamCallback
{
private:
	AudioOutput *audio_output;

public:
	AudioOutputCallback(AudioOutput *audio_output) : audio_output(audio_output) {}
	oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;
	void onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error) override;
	void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;
};

struct AudioOutput
{
	ChiakiLog *log;
	oboe::ManagedStream stream;
	AudioOutputCallback stream_callback;
	AudioBuffer buf;
	uint32_t channels = 0;
	uint32_t rate = 0;
	// AAudio streams get disconnected over and over on some devices (e.g. Rockchip TV boxes
	// that play on HDMI and the speaker at once), OpenSL ES goes through AudioTrack, which copes
	bool use_opensl = false;
	// Picked by the app, e.g. HDMI instead of a controller's headphone jack, see android_chiaki_audio_output_set_device()
	int32_t device_id = oboe::kUnspecified;
	std::mutex stream_mutex;
	// Oboe's error thread might still use a replaced stream, so they are only deleted with the AudioOutput
	std::vector<oboe::ManagedStream> old_streams;

	// Bytes in buf. Signed, as the stream's callback can count a pop before the frame's push is counted.
	std::atomic<int64_t> queued{0};
	// The lowest the buffer got in the current window, and how long the window is so far.
	// Only the stream's callback uses these.
	int64_t window_lowest = INT64_MAX;
	int64_t window_frames = 0;
	// The lowest over the last window, for the statistics
	std::atomic<int32_t> lowest_ms{0};
	// Skipped by the callback, logged from the decoder's thread, as the callback shouldn't write files
	std::atomic<int32_t> skipped_ms{0};
	std::atomic<int32_t> skipped_from_ms{0};

	AudioOutput() : stream_callback(this) {}
};

// ao->stream_mutex must be locked
static void open_stream(AudioOutput *ao)
{
	if(ao->stream)
	{
		ao->stream->close();
		ao->old_streams.push_back(std::move(ao->stream));
	}

	oboe::AudioStreamBuilder builder;
	// Shared: exclusive streams are refused or dropped by some HDMI outputs, e.g. on TV boxes
	builder.setPerformanceMode(oboe::PerformanceMode::LowLatency)
		->setSharingMode(oboe::SharingMode::Shared)
		->setUsage(oboe::Usage::Game)
		->setFormat(oboe::AudioFormat::I16)
		->setChannelCount(ao->channels)
		->setSampleRate(ao->rate)
		->setCallback(&ao->stream_callback);
	if(ao->device_id != oboe::kUnspecified)
		builder.setDeviceId(ao->device_id); // OpenSL ES can't pick the device
	else if(ao->use_opensl)
		builder.setAudioApi(oboe::AudioApi::OpenSLES);

	auto result = builder.openManagedStream(ao->stream);
	if(result == oboe::Result::OK)
		CHIAKI_LOGI(ao->log, "Audio Output opened Oboe stream on device %d", (int)ao->stream->getDeviceId());
	else
	{
		CHIAKI_LOGE(ao->log, "Audio Output failed to open Oboe stream: %s", oboe::convertToText(result));
		return;
	}

	result = ao->stream->start();
	if(result == oboe::Result::OK)
		CHIAKI_LOGI(ao->log, "Audio Output started Oboe stream");
	else
		CHIAKI_LOGE(ao->log, "Audio Output failed to start Oboe stream: %s", oboe::convertToText(result));
}

extern "C" void *android_chiaki_audio_output_new(ChiakiLog *log)
{
	auto r = new AudioOutput();
	r->log = log;
	return r;
}

extern "C" void android_chiaki_audio_output_free(void *audio_output)
{
	if(!audio_output)
		return;
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);
	ao->stream = nullptr;
	ao->old_streams.clear();
	delete ao;
}

extern "C" void android_chiaki_audio_output_settings(uint32_t channels, uint32_t rate, void *audio_output)
{
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);
	std::lock_guard<std::mutex> lock(ao->stream_mutex);
	ao->channels = channels;
	ao->rate = rate;
	open_stream(ao);
}

extern "C" void android_chiaki_audio_output_set_device(int32_t device_id, void *audio_output)
{
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);
	std::lock_guard<std::mutex> lock(ao->stream_mutex);
	if(device_id == ao->device_id)
		return;
	ao->device_id = device_id;
	if(!ao->channels)
		return; // the stream isn't open yet
	CHIAKI_LOGI(ao->log, "Audio Output switching to device %d", (int)device_id);
	open_stream(ao);
}

extern "C" void android_chiaki_audio_output_frame(int16_t *buf, size_t samples_count, void *audio_output)
{
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);

	size_t buf_size = samples_count * sizeof(int16_t);
	size_t pushed = ao->buf.Push(reinterpret_cast<uint8_t *>(buf), buf_size);
	ao->queued.fetch_add(static_cast<int64_t>(pushed), std::memory_order_relaxed);
	if(pushed < buf_size)
		CHIAKI_LOGW(ao->log, "Audio Output Buffer Overflow!");

	int32_t skipped_ms = ao->skipped_ms.exchange(0, std::memory_order_relaxed);
	if(skipped_ms)
		CHIAKI_LOGI(ao->log, "Audio Output was %d ms behind, skipped %d ms to catch up",
				(int)ao->skipped_from_ms.load(std::memory_order_relaxed), (int)skipped_ms);
}

extern "C" int32_t android_chiaki_audio_output_buffered_ms(void *audio_output)
{
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);
	return ao->lowest_ms.load(std::memory_order_relaxed);
}

// Called by the stream's callback after it took its audio
static void catch_up(AudioOutput *ao, oboe::AudioStream *stream, int32_t num_frames)
{
	int64_t bytes_per_frame = stream->getBytesPerFrame();
	int64_t bytes_per_ms = static_cast<int64_t>(stream->getSampleRate()) * bytes_per_frame / 1000;
	if(bytes_per_frame <= 0 || bytes_per_ms <= 0)
		return;
	int64_t queued = std::max<int64_t>(0, ao->queued.load(std::memory_order_relaxed));
	ao->window_lowest = std::min(ao->window_lowest, queued);
	ao->window_frames += num_frames;
	if(ao->window_frames * 1000 < static_cast<int64_t>(stream->getSampleRate()) * CATCH_UP_WINDOW_MS)
		return;

	int64_t lowest = ao->window_lowest;
	ao->window_lowest = INT64_MAX;
	ao->window_frames = 0;
	ao->lowest_ms.store(static_cast<int32_t>(lowest / bytes_per_ms), std::memory_order_relaxed);
	if(lowest <= bytes_per_ms * (CATCH_UP_KEEP_MS + CATCH_UP_SLACK_MS))
		return;

	int64_t skip = (lowest - bytes_per_ms * CATCH_UP_KEEP_MS) / bytes_per_frame * bytes_per_frame;
	uint8_t scratch[BUFFER_CHUNK_SIZE];
	int64_t skipped = 0;
	while(skipped < skip)
	{
		size_t popped = ao->buf.Pop(scratch, static_cast<size_t>(std::min<int64_t>(sizeof(scratch), skip - skipped)));
		if(!popped)
			break;
		skipped += static_cast<int64_t>(popped);
	}
	ao->queued.fetch_sub(skipped, std::memory_order_relaxed);
	ao->skipped_from_ms.store(static_cast<int32_t>(lowest / bytes_per_ms), std::memory_order_relaxed);
	ao->skipped_ms.fetch_add(static_cast<int32_t>(skipped / bytes_per_ms), std::memory_order_relaxed);
}

oboe::DataCallbackResult AudioOutputCallback::onAudioReady(oboe::AudioStream *stream, void *audio_data, int32_t num_frames)
{
	if(stream->getFormat() != oboe::AudioFormat::I16)
	{
		CHIAKI_LOGE(audio_output->log, "Oboe stream has invalid format in callback");
		return oboe::DataCallbackResult::Stop;
	}

	int32_t bytes_per_frame = stream->getBytesPerFrame();
	size_t buf_size_requested = static_cast<size_t>(bytes_per_frame * num_frames);
	auto buf = reinterpret_cast<uint8_t *>(audio_data);

	size_t buf_size_delivered = audio_output->buf.Pop(buf, buf_size_requested);
	audio_output->queued.fetch_sub(static_cast<int64_t>(buf_size_delivered), std::memory_order_relaxed);
	catch_up(audio_output, stream, num_frames);

	if(buf_size_delivered < buf_size_requested)
	{
		CHIAKI_LOGV(audio_output->log, "Audio Output Buffer Underflow!");
		memset(buf + buf_size_delivered, 0, buf_size_requested - buf_size_delivered);
	}

	return oboe::DataCallbackResult::Continue;
}

void AudioOutputCallback::onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error)
{
	CHIAKI_LOGE(audio_output->log, "Oboe reported error before close: %s", oboe::convertToText(error));
}

void AudioOutputCallback::onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error)
{
	CHIAKI_LOGE(audio_output->log, "Oboe reported error after close: %s", oboe::convertToText(error));
	std::lock_guard<std::mutex> lock(audio_output->stream_mutex);
	if(stream != audio_output->stream.get())
		return; // already replaced by android_chiaki_audio_output_set_device()
	// The output device changed (e.g. HDMI audio was rerouted), which closes the stream.
	// Oboe calls this on its own thread, where opening a new stream is allowed.
	if(error == oboe::Result::ErrorDisconnected)
	{
		CHIAKI_LOGI(audio_output->log, "Audio Output reopening Oboe stream with OpenSL ES after disconnect");
		audio_output->use_opensl = true;
		open_stream(audio_output);
	}
}
