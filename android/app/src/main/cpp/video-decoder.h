// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_JNI_VIDEO_DECODER_H
#define CHIAKI_JNI_VIDEO_DECODER_H

#include <jni.h>

#include <chiaki/log.h>
#include <chiaki/thread.h>

typedef struct AMediaCodec AMediaCodec;
typedef struct ANativeWindow ANativeWindow;

// Frames in flight in the decoder that the latency statistics can keep track of
#define ANDROID_CHIAKI_VIDEO_DECODER_LATENCY_SLOTS 64

typedef struct android_chiaki_video_decoder_t {
  ChiakiLog *log;
  ChiakiMutex codec_mutex;
  AMediaCodec *codec;
  ANativeWindow *window;
  uint64_t timestamp_cur;
  ChiakiThread output_thread;
  bool shutdown_output;
  int32_t target_width;
  int32_t target_height;
  ChiakiCodec target_codec;

  // When each input was queued, by timestamp, for measuring how long the
  // decoder takes
  uint64_t queued_us[ANDROID_CHIAKI_VIDEO_DECODER_LATENCY_SLOTS];
  uint64_t latency_sum_us;
  uint64_t latency_max_us;
  uint32_t latency_count;

  // For the stream statistics overlay, over a shorter window than the log's
  uint64_t stats_sum_us;
  uint64_t stats_max_us;
  uint32_t stats_count;
  // Read from other threads with __atomic_load_n
  uint32_t frames_rendered;
  uint32_t stats_latency_avg_us;
  uint32_t stats_latency_max_us;
} AndroidChiakiVideoDecoder;

ChiakiErrorCode
android_chiaki_video_decoder_init(AndroidChiakiVideoDecoder *decoder,
                                  ChiakiLog *log, int32_t target_width,
                                  int32_t target_height, ChiakiCodec codec);
void android_chiaki_video_decoder_fini(AndroidChiakiVideoDecoder *decoder);
void android_chiaki_video_decoder_set_surface(
    AndroidChiakiVideoDecoder *decoder, JNIEnv *env, jobject surface);
bool android_chiaki_video_decoder_video_sample(uint8_t *buf, size_t buf_size,
                                               int32_t frames_lost,
                                               bool frame_recovered,
                                               void *user);

#endif