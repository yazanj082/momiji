// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "video-decoder.h"

#include <jni.h>

#include <android/native_window_jni.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#include <chiaki/time.h>

#include <string.h>

#define INPUT_BUFFER_TIMEOUT_MS 10
// Decode latency is logged once per this many frames
#define LATENCY_LOG_FRAMES 600
// About half a second at 60 fps, for the statistics overlay
#define LATENCY_STATS_FRAMES 30

static void *android_chiaki_video_decoder_output_thread_func(void *user);

ChiakiErrorCode
android_chiaki_video_decoder_init(AndroidChiakiVideoDecoder *decoder,
                                  ChiakiLog *log, int32_t target_width,
                                  int32_t target_height, ChiakiCodec codec) {
  decoder->log = log;
  decoder->codec = NULL;
  decoder->timestamp_cur = 0;
  decoder->target_width = target_width;
  decoder->target_height = target_height;
  decoder->target_codec = codec;
  decoder->shutdown_output = false;
  memset(decoder->queued_us, 0, sizeof(decoder->queued_us));
  decoder->latency_sum_us = 0;
  decoder->latency_max_us = 0;
  decoder->stats_sum_us = 0;
  decoder->stats_max_us = 0;
  decoder->stats_count = 0;
  decoder->frames_rendered = 0;
  decoder->stats_latency_avg_us = 0;
  decoder->stats_latency_max_us = 0;
  decoder->latency_count = 0;
  return chiaki_mutex_init(&decoder->codec_mutex, false);
}

static void kill_decoder(AndroidChiakiVideoDecoder *decoder) {
  chiaki_mutex_lock(&decoder->codec_mutex);
  decoder->shutdown_output = true;
  ssize_t codec_buf_index =
      AMediaCodec_dequeueInputBuffer(decoder->codec, 1000);
  if (codec_buf_index >= 0) {
    CHIAKI_LOGI(decoder->log, "Video Decoder sending EOS buffer");
    AMediaCodec_queueInputBuffer(decoder->codec, (size_t)codec_buf_index, 0, 0,
                                 decoder->timestamp_cur++,
                                 AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
    AMediaCodec_stop(decoder->codec);
    chiaki_mutex_unlock(&decoder->codec_mutex);
    chiaki_thread_join(&decoder->output_thread, NULL);
  } else {
    CHIAKI_LOGE(decoder->log,
                "Failed to get input buffer for shutting down Video Decoder!");
    AMediaCodec_stop(decoder->codec);
    chiaki_mutex_unlock(&decoder->codec_mutex);
  }
  // Under the lock so video_sample() never sees a deleted codec
  chiaki_mutex_lock(&decoder->codec_mutex);
  AMediaCodec_delete(decoder->codec);
  decoder->codec = NULL;
  decoder->shutdown_output = false;
  chiaki_mutex_unlock(&decoder->codec_mutex);
}

void android_chiaki_video_decoder_fini(AndroidChiakiVideoDecoder *decoder) {
  if (decoder->codec)
    kill_decoder(decoder);
  chiaki_mutex_fini(&decoder->codec_mutex);
}

static AMediaFormat *create_format(AndroidChiakiVideoDecoder *decoder,
                                   const char *mime, bool low_latency) {
  AMediaFormat *format = AMediaFormat_new();
  AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, mime);
  AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, decoder->target_width);
  AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT,
                        decoder->target_height);
  if (!low_latency)
    return format;

  // Big enough for a whole keyframe, so a frame is never split across
  // input buffers, which some decoders can't handle
  AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_MAX_INPUT_SIZE,
                        decoder->target_width * decoder->target_height);
  // Output each frame as soon as it is decoded instead of holding it until
  // the next one arrives (Android 11+, KEY_LOW_LATENCY). On Rockchip this
  // saves a whole frame, 19 -> 5 ms at 1080p60.
  AMediaFormat_setInt32(format, "low-latency", 1);
  // Realtime priority (KEY_PRIORITY)
  AMediaFormat_setInt32(format, "priority", 0);
  // Vendor specific low latency switches for decoders that predate
  // KEY_LOW_LATENCY; decoders ignore the ones they don't know
  AMediaFormat_setInt32(format, "vendor.qti-ext-dec-picture-order.enable", 1);
  AMediaFormat_setInt32(format, "vendor.qti-ext-dec-low-latency.enable", 1);
  AMediaFormat_setInt32(format, "vendor.rtc-ext-dec-low-latency.enable", 1);
  AMediaFormat_setInt32(format, "vendor.low-latency.enable", 1);
  return format;
}

void android_chiaki_video_decoder_set_surface(
    AndroidChiakiVideoDecoder *decoder, JNIEnv *env, jobject surface) {
  chiaki_mutex_lock(&decoder->codec_mutex);

  if (!surface) {
    if (decoder->codec) {
      // kill_decoder() locks codec_mutex itself and the mutex is not recursive
      chiaki_mutex_unlock(&decoder->codec_mutex);
      kill_decoder(decoder);
      chiaki_mutex_lock(&decoder->codec_mutex);
      if (decoder->window) {
        ANativeWindow_release(decoder->window);
        decoder->window = NULL;
      }
      CHIAKI_LOGI(decoder->log, "Decoder shut down after surface was removed");
    }
    goto beach;
  }

  if (decoder->codec) {
#if __ANDROID_API__ >= 23
    CHIAKI_LOGI(decoder->log,
                "Video decoder already initialized, swapping surface");
    ANativeWindow *new_window =
        surface ? ANativeWindow_fromSurface(env, surface) : NULL;
    AMediaCodec_setOutputSurface(decoder->codec, new_window);
    ANativeWindow_release(decoder->window);
    decoder->window = new_window;
#else
    CHIAKI_LOGE(decoder->log, "Video Decoder already initialized");
#endif
    goto beach;
  }

  decoder->window = ANativeWindow_fromSurface(env, surface);

  const char *mime =
      chiaki_codec_is_h265(decoder->target_codec) ? "video/hevc" : "video/avc";
  CHIAKI_LOGI(decoder->log, "Initializing decoder with mime %s", mime);

  // Try the low latency configuration first and fall back to the plain one
  // for decoders that reject any of its keys.
  media_status_t r = AMEDIA_ERROR_UNKNOWN;
  for (int low_latency = 1; low_latency >= 0; low_latency--) {
    decoder->codec = AMediaCodec_createDecoderByType(mime);
    if (!decoder->codec) {
      CHIAKI_LOGE(decoder->log, "Failed to create AMediaCodec for mime type %s",
                  mime);
      goto error_surface;
    }

    AMediaFormat *format = create_format(decoder, mime, low_latency);
    r = AMediaCodec_configure(decoder->codec, format, decoder->window, NULL, 0);
    if (r == AMEDIA_OK)
      r = AMediaCodec_start(decoder->codec);
    AMediaFormat_delete(format);
    if (r == AMEDIA_OK) {
      CHIAKI_LOGI(decoder->log, "Video decoder started%s",
                  low_latency ? " in low latency mode" : "");
      break;
    }

    CHIAKI_LOGE(decoder->log,
                "Configuring the video decoder%s failed: %d",
                low_latency ? " in low latency mode" : "", (int)r);
    AMediaCodec_delete(decoder->codec);
    decoder->codec = NULL;
  }
  if (r != AMEDIA_OK)
    goto error_surface;

  ChiakiErrorCode err = chiaki_thread_create(
      &decoder->output_thread, android_chiaki_video_decoder_output_thread_func,
      decoder);
  if (err != CHIAKI_ERR_SUCCESS) {
    CHIAKI_LOGE(decoder->log, "Failed to create output thread for AMediaCodec");
    goto error_codec;
  }

  goto beach;

error_codec:
  AMediaCodec_stop(decoder->codec);
  AMediaCodec_delete(decoder->codec);
  decoder->codec = NULL;

error_surface:
  ANativeWindow_release(decoder->window);
  decoder->window = NULL;

beach:
  chiaki_mutex_unlock(&decoder->codec_mutex);
}

/**
 * Whether the sample only holds parameter sets (VPS/SPS/PPS) and no picture, like
 * the stream header that is sent before the first frame.
 */
static bool is_codec_config(const uint8_t *buf, size_t buf_size, bool h265) {
  bool any = false;
  for (size_t i = 0; i + 3 < buf_size; i++) {
    // Annex B start code, 00 00 01 (also the end of 00 00 00 01)
    if (buf[i] != 0 || buf[i + 1] != 0 || buf[i + 2] != 1)
      continue;
    uint8_t header = buf[i + 3];
    bool parameter_set;
    if (h265) {
      uint8_t type = (header >> 1) & 0x3f;
      parameter_set = type >= 32 && type <= 34;
    } else {
      uint8_t type = header & 0x1f;
      parameter_set = type == 7 || type == 8;
    }
    if (!parameter_set)
      return false;
    any = true;
    i += 3;
  }
  return any;
}

bool android_chiaki_video_decoder_video_sample(uint8_t *buf, size_t buf_size,
                                               int32_t frames_lost,
                                               bool frame_recovered,
                                               void *user) {
  (void)frames_lost;     // unused
  (void)frame_recovered; // unused
  bool r = true;
  AndroidChiakiVideoDecoder *decoder = user;
  chiaki_mutex_lock(&decoder->codec_mutex);

  if (!decoder->codec) {
    CHIAKI_LOGE(decoder->log,
                "Received video data, but decoder is not initialized!");
    goto beach;
  }

  // Decoders in low latency mode may wait forever for the picture of a sample
  // that has none, unless it is marked as configuration
  uint32_t flags = is_codec_config(buf, buf_size,
                                   chiaki_codec_is_h265(decoder->target_codec))
                       ? AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG
                       : 0;

  while (buf_size > 0) {
    ssize_t codec_buf_index = AMediaCodec_dequeueInputBuffer(
        decoder->codec, INPUT_BUFFER_TIMEOUT_MS * 1000);
    if (codec_buf_index < 0) {
      CHIAKI_LOGE(decoder->log, "Failed to get input buffer");
      r = false;
      goto beach;
    }

    size_t codec_buf_size;
    uint8_t *codec_buf = AMediaCodec_getInputBuffer(
        decoder->codec, (size_t)codec_buf_index, &codec_buf_size);
    size_t codec_sample_size = buf_size;
    if (codec_sample_size > codec_buf_size) {
      // CHIAKI_LOGD(decoder->log, "Sample is bigger than buffer, splitting");
      codec_sample_size = codec_buf_size;
    }
    memcpy(codec_buf, buf, codec_sample_size);
    decoder->queued_us[decoder->timestamp_cur %
                       ANDROID_CHIAKI_VIDEO_DECODER_LATENCY_SLOTS] =
        chiaki_time_now_monotonic_us();
    media_status_t r = AMediaCodec_queueInputBuffer(
        decoder->codec, (size_t)codec_buf_index, 0, codec_sample_size,
        decoder->timestamp_cur++,
        flags); // timestamp just raised by 1 for maximum realtime
    if (r != AMEDIA_OK) {
      CHIAKI_LOGE(decoder->log, "AMediaCodec_queueInputBuffer() failed: %d",
                  (int)r);
    }
    buf += codec_sample_size;
    buf_size -= codec_sample_size;
  }

beach:
  chiaki_mutex_unlock(&decoder->codec_mutex);
  return r;
}

static void track_latency(AndroidChiakiVideoDecoder *decoder,
                          uint64_t timestamp) {
  uint64_t queued =
      decoder->queued_us[timestamp % ANDROID_CHIAKI_VIDEO_DECODER_LATENCY_SLOTS];
  if (!queued)
    return;
  uint64_t latency = chiaki_time_now_monotonic_us() - queued;

  decoder->stats_sum_us += latency;
  if (latency > decoder->stats_max_us)
    decoder->stats_max_us = latency;
  if (++decoder->stats_count >= LATENCY_STATS_FRAMES) {
    __atomic_store_n(&decoder->stats_latency_avg_us,
                     (uint32_t)(decoder->stats_sum_us / decoder->stats_count),
                     __ATOMIC_RELAXED);
    __atomic_store_n(&decoder->stats_latency_max_us,
                     (uint32_t)decoder->stats_max_us, __ATOMIC_RELAXED);
    decoder->stats_sum_us = 0;
    decoder->stats_max_us = 0;
    decoder->stats_count = 0;
  }

  decoder->latency_sum_us += latency;
  if (latency > decoder->latency_max_us)
    decoder->latency_max_us = latency;
  if (++decoder->latency_count < LATENCY_LOG_FRAMES)
    return;
  CHIAKI_LOGI(decoder->log, "Decode latency: average %.1f ms, max %.1f ms",
              decoder->latency_sum_us / 1000.0 / decoder->latency_count,
              decoder->latency_max_us / 1000.0);
  decoder->latency_sum_us = 0;
  decoder->latency_max_us = 0;
  decoder->latency_count = 0;
}

static void *android_chiaki_video_decoder_output_thread_func(void *user) {
  AndroidChiakiVideoDecoder *decoder = user;

  // Puts the decoded frames on the screen
  if (chiaki_thread_set_current_nice(-8) != CHIAKI_ERR_SUCCESS)
    CHIAKI_LOGW(decoder->log, "Video decoder failed to raise its output thread's priority");

  while (1) {
    AMediaCodecBufferInfo info;
    ssize_t status = AMediaCodec_dequeueOutputBuffer(decoder->codec, &info, -1);
    if (status >= 0) {
      AMediaCodec_releaseOutputBuffer(decoder->codec, (size_t)status,
                                      info.size != 0);
      if (info.size != 0) {
        __atomic_add_fetch(&decoder->frames_rendered, 1, __ATOMIC_RELAXED);
        track_latency(decoder, (uint64_t)info.presentationTimeUs);
      }
      if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
        CHIAKI_LOGI(decoder->log, "AMediaCodec reported EOS");
        break;
      }
    } else {
      chiaki_mutex_lock(&decoder->codec_mutex);
      bool shutdown = decoder->shutdown_output;
      chiaki_mutex_unlock(&decoder->codec_mutex);
      if (shutdown) {
        CHIAKI_LOGI(decoder->log, "Video Decoder Output Thread detected "
                                  "shutdown after reported error");
        break;
      }
    }
  }

  CHIAKI_LOGI(decoder->log, "Video Decoder Output Thread exiting");

  return NULL;
}