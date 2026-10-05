// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include <jni.h>

#include <android/log.h>

#include <chiaki/common.h>
#include <chiaki/log.h>
#include <chiaki/session.h>
#include <chiaki/discoveryservice.h>
#include <chiaki/regist.h>
#include <chiaki/orientation.h>
#include <chiaki/time.h>

#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <linux/in.h>
#include <linux/in6.h>
#include <arpa/inet.h>

#include "video-decoder.h"
#include "audio-decoder.h"
#include "audio-output.h"
#include "haptics-output.h"
#include "log.h"
#include "chiaki-jni.h"

static char *strdup_jni(const char *str)
{
	if(!str)
		return NULL;
	char *r = strdup(str);
	if(!r)
		return NULL;
	for(char *c=r; *c; c++)
	{
		if(*c & (1 << 7))
			*c = '?';
	}
	return r;
}

jobject jnistr_from_ascii(JNIEnv *env, const char *str)
{
	if(!str)
		return NULL;
	char *s = strdup_jni(str);
	if(!s)
		return NULL;
	jobject r = E->NewStringUTF(env, s);
	free(s);
	return r;
}

static jbyteArray jnibytearray_create(JNIEnv *env, const uint8_t *buf, size_t buf_size)
{
	jbyteArray r = E->NewByteArray(env, buf_size);
	E->SetByteArrayRegion(env, r, 0, buf_size, (const jbyte *)buf);
	return r;
}

static jobject get_kotlin_global_object(JNIEnv *env, const char *id)
{
	size_t idlen = strlen(id);
	char *sig = malloc(idlen + 3);
	if(!sig)
		return NULL;
	sig[0] = 'L';
	memcpy(sig + 1, id, idlen);
	sig[1 + idlen] = ';';
	sig[1 + idlen + 1] = '\0';
	jclass cls = E->FindClass(env, id);
	jfieldID field_id = E->GetStaticFieldID(env, cls, "INSTANCE", sig);
	jobject r = E->GetStaticObjectField(env, cls, field_id);
	free(sig);
	return r;
}

static ChiakiLog global_log;
JavaVM *global_vm;

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved)
{
	global_vm = vm;

	android_chiaki_file_log_init(&global_log, CHIAKI_LOG_ALL & ~CHIAKI_LOG_VERBOSE, NULL);
	CHIAKI_LOGI(&global_log, "Loading Chiaki Library");
	ChiakiErrorCode err = chiaki_lib_init();
	CHIAKI_LOGI(&global_log, "Chiaki Library Init Result: %s\n", chiaki_error_string(err));
	return JNI_VERSION;
}

JNIEnv *attach_thread_jni()
{
	JNIEnv *env;
	int r = (*global_vm)->GetEnv(global_vm, (void **)&env, JNI_VERSION);
	if(r == JNI_OK)
		return env;

	if((*global_vm)->AttachCurrentThread(global_vm, &env, NULL) == 0)
		return env;

	CHIAKI_LOGE(&global_log, "Failed to get JNIEnv from JavaVM or attach");
	return NULL;
}

JNIEXPORT jstring JNICALL JNI_FCN(errorCodeToString)(JNIEnv *env, jobject obj, jint value)
{
	return E->NewStringUTF(env, chiaki_error_string((ChiakiErrorCode)value));
}

JNIEXPORT jstring JNICALL JNI_FCN(quitReasonToString)(JNIEnv *env, jobject obj, jint value)
{
	return E->NewStringUTF(env, chiaki_quit_reason_string((ChiakiQuitReason)value));
}

JNIEXPORT jboolean JNICALL JNI_FCN(quitReasonIsError)(JNIEnv *env, jobject obj, jint value)
{
	return chiaki_quit_reason_is_error(value);
}

JNIEXPORT jobject JNICALL JNI_FCN(videoProfilePreset)(JNIEnv *env, jobject obj, jint resolution_preset, jint fps_preset, jobject codec)
{
	ChiakiConnectVideoProfile profile = { 0 };
	chiaki_connect_video_profile_preset(&profile, (ChiakiVideoResolutionPreset)resolution_preset, (ChiakiVideoFPSPreset)fps_preset);
	jclass profile_class = E->FindClass(env, BASE_PACKAGE"/ConnectVideoProfile");
	jmethodID profile_ctor = E->GetMethodID(env, profile_class, "<init>", "(IIIIL"BASE_PACKAGE"/Codec;)V");
	return E->NewObject(env, profile_class, profile_ctor, profile.width, profile.height, profile.max_fps, profile.bitrate, codec);
}

typedef struct android_chiaki_session_t
{
	ChiakiSession session;
	ChiakiLog *log;
	jobject java_session;
	jclass java_session_class;
	jmethodID java_session_event_connected_meth;
	jmethodID java_session_event_login_pin_request_meth;
	jmethodID java_session_event_quit_meth;
	jmethodID java_session_event_rumble_meth;
	jmethodID java_session_event_haptics_meth;
	jmethodID java_session_event_trigger_effects_meth;
	jmethodID java_session_event_led_color_meth;
	jmethodID java_session_event_haptic_intensity_meth;
	jmethodID java_session_event_trigger_intensity_meth;
	jmethodID java_session_event_cant_display_meth;
	jfieldID java_controller_state_buttons;
	jfieldID java_controller_state_l2_state;
	jfieldID java_controller_state_r2_state;
	jfieldID java_controller_state_left_x;
	jfieldID java_controller_state_left_y;
	jfieldID java_controller_state_right_x;
	jfieldID java_controller_state_right_y;
	jfieldID java_controller_state_touches;
	jfieldID java_controller_state_gyro_x;
	jfieldID java_controller_state_gyro_y;
	jfieldID java_controller_state_gyro_z;
	jfieldID java_controller_state_accel_x;
	jfieldID java_controller_state_accel_y;
	jfieldID java_controller_state_accel_z;
	jfieldID java_controller_state_orient_x;
	jfieldID java_controller_state_orient_y;
	jfieldID java_controller_state_orient_z;
	jfieldID java_controller_state_orient_w;
	jfieldID java_controller_touch_x;
	jfieldID java_controller_touch_y;
	jfieldID java_controller_touch_id;

	AndroidChiakiVideoDecoder video_decoder;
	AndroidChiakiAudioDecoder audio_decoder;
	void *audio_output;
	void *haptics_output;

	// Motion of the physical controller, see sessionSetMotion()
	ChiakiOrientationTracker orient_tracker;
	ChiakiAccelNewZero accel_zero;
	bool orient_tracker_active;
	atomic_bool motion_reset;

	// Haptics audio turned into vibration, see android_chiaki_haptics_frame_cb()
	uint8_t haptics_peak_left, haptics_peak_right;
	uint8_t haptics_sent_left, haptics_sent_right;
	uint64_t haptics_sent_us;
	bool haptics_logged, rumble_logged;

	// Packet counts at the last sessionGetStats(), for the loss since then
	uint64_t stats_received, stats_lost;
} AndroidChiakiSession;

/**
 * The console shows something it doesn't stream (protected content), the video
 * stays black until it's gone.
 */
static void android_chiaki_cant_display_cb(void *user, bool cant_display)
{
	AndroidChiakiSession *session = user;
	JNIEnv *env = attach_thread_jni();
	if(!env)
		return;
	E->CallVoidMethod(env, session->java_session, session->java_session_event_cant_display_meth, (jboolean)cant_display);
	(*global_vm)->DetachCurrentThread(global_vm);
}

// Vibration is updated at most this often from the haptics, which arrive every few ms
#define HAPTICS_RUMBLE_INTERVAL_US 16000

/**
 * The console streams DualSense haptics as 3 kHz stereo PCM. With the DualSense on USB,
 * they are played on its voice coil actuators, see haptics-output.h.
 * Otherwise Android can't do that, so they drive the rumble motors instead,
 * following the loudness of each channel.
 */
static void android_chiaki_haptics_frame_cb(uint8_t *buf, size_t buf_size, void *user)
{
	AndroidChiakiSession *session = user;
	if(!session->haptics_logged)
	{
		session->haptics_logged = true;
		CHIAKI_LOGI(session->log, "Receiving DualSense haptics, %zu bytes per frame", buf_size);
	}
	if(android_chiaki_haptics_output_active(session->haptics_output))
	{
		android_chiaki_haptics_output_frame(buf, buf_size, session->haptics_output);
		return;
	}
	size_t samples = buf_size / (2 * sizeof(int16_t));
	if(!samples)
		return;
	uint32_t sum_left = 0, sum_right = 0;
	for(size_t i = 0; i < samples; i++)
	{
		int16_t sample[2];
		memcpy(sample, buf + i * sizeof(sample), sizeof(sample));
		sum_left += abs(sample[0]);
		sum_right += abs(sample[1]);
	}
	// Average amplitude of 8192 and above is full strength
	uint32_t left = (sum_left / samples) >> 5;
	uint32_t right = (sum_right / samples) >> 5;
	if(left > session->haptics_peak_left)
		session->haptics_peak_left = left > 0xff ? 0xff : left;
	if(right > session->haptics_peak_right)
		session->haptics_peak_right = right > 0xff ? 0xff : right;

	uint64_t now = chiaki_time_now_monotonic_us();
	if(now - session->haptics_sent_us < HAPTICS_RUMBLE_INTERVAL_US)
		return;
	if(session->haptics_peak_left != session->haptics_sent_left
		|| session->haptics_peak_right != session->haptics_sent_right)
	{
		JNIEnv *env = attach_thread_jni();
		if(!env)
			return;
		E->CallVoidMethod(env, session->java_session, session->java_session_event_haptics_meth,
				(jint)session->haptics_peak_left, (jint)session->haptics_peak_right);
		(*global_vm)->DetachCurrentThread(global_vm);
		session->haptics_sent_left = session->haptics_peak_left;
		session->haptics_sent_right = session->haptics_peak_right;
	}
	session->haptics_sent_us = now;
	session->haptics_peak_left = 0;
	session->haptics_peak_right = 0;
}

static void android_chiaki_event_cb(ChiakiEvent *event, void *user)
{
	AndroidChiakiSession *session = user;

	JNIEnv *env = attach_thread_jni();
	if(!env)
		return;

	switch(event->type)
	{
		case CHIAKI_EVENT_CONNECTED:
			E->CallVoidMethod(env, session->java_session,
							  session->java_session_event_connected_meth);
			break;
		case CHIAKI_EVENT_LOGIN_PIN_REQUEST:
			E->CallVoidMethod(env, session->java_session,
							  session->java_session_event_login_pin_request_meth,
							  (jboolean)event->login_pin_request.pin_incorrect);
			break;
		case CHIAKI_EVENT_QUIT:
		{
			char *reason_str = strdup_jni(event->quit.reason_str);
			jstring reason_str_java = reason_str ? E->NewStringUTF(env, reason_str) : NULL;
			E->CallVoidMethod(env, session->java_session,
							  session->java_session_event_quit_meth,
							  (jint)event->quit.reason,
							  reason_str_java);
			if(reason_str_java)
				E->DeleteLocalRef(env, reason_str_java);
			free(reason_str);
			break;
		}
		case CHIAKI_EVENT_RUMBLE:
			if(!session->rumble_logged)
			{
				session->rumble_logged = true;
				CHIAKI_LOGI(session->log, "Receiving rumble");
			}
			android_chiaki_haptics_output_rumble(event->rumble.left, event->rumble.right, session->haptics_output);
			if(android_chiaki_haptics_output_active(session->haptics_output))
				break;
			E->CallVoidMethod(env, session->java_session,
							  session->java_session_event_rumble_meth,
							  (jint)event->rumble.left,
							  (jint)event->rumble.right);
			break;
		case CHIAKI_EVENT_TRIGGER_EFFECTS:
		{
			jbyteArray left = jnibytearray_create(env, event->trigger_effects.left, sizeof(event->trigger_effects.left));
			jbyteArray right = jnibytearray_create(env, event->trigger_effects.right, sizeof(event->trigger_effects.right));
			E->CallVoidMethod(env, session->java_session,
							  session->java_session_event_trigger_effects_meth,
							  (jint)event->trigger_effects.type_left,
							  (jint)event->trigger_effects.type_right,
							  left, right);
			E->DeleteLocalRef(env, left);
			E->DeleteLocalRef(env, right);
			break;
		}
		case CHIAKI_EVENT_LED_COLOR:
			E->CallVoidMethod(env, session->java_session,
							  session->java_session_event_led_color_meth,
							  (jint)event->led_state[0],
							  (jint)event->led_state[1],
							  (jint)event->led_state[2]);
			break;
		case CHIAKI_EVENT_HAPTIC_INTENSITY:
			android_chiaki_haptics_output_intensity((int)event->intensity, session->haptics_output);
			E->CallVoidMethod(env, session->java_session,
							  session->java_session_event_haptic_intensity_meth,
							  (jint)event->intensity);
			break;
		case CHIAKI_EVENT_TRIGGER_INTENSITY:
			E->CallVoidMethod(env, session->java_session,
							  session->java_session_event_trigger_intensity_meth,
							  (jint)event->intensity);
			break;
		case CHIAKI_EVENT_MOTION_RESET:
			// Applied on the thread that feeds the motion
			atomic_store(&session->motion_reset, true);
			break;
		default:
			break;
	}

	(*global_vm)->DetachCurrentThread(global_vm);
}

/** A connection through PSN, until a session takes it over with its log */
typedef struct android_chiaki_holepunch_t
{
	ChiakiLog *log;
	ChiakiHolepunchSession session;
} AndroidChiakiHolepunch;

static ChiakiLog *log_create(JNIEnv *env, jstring log_file_str, jboolean log_verbose)
{
	ChiakiLog *log = malloc(sizeof(ChiakiLog));
	if(!log)
		return NULL;
	const char *log_file = log_file_str ? E->GetStringUTFChars(env, log_file_str, NULL) : NULL;
	android_chiaki_file_log_init(log, log_verbose ? CHIAKI_LOG_ALL : (CHIAKI_LOG_ALL & ~CHIAKI_LOG_VERBOSE), log_file);
	if(log_file)
		E->ReleaseStringUTFChars(env, log_file_str, log_file);
	return log;
}

/**
 * @param holepunch_ptr a connection through PSN from holepunchCreate, or 0. The session takes it
 * over, also when creating the session fails.
 * @param psn_account_id_array the account id of the PSN sign-in, for a connection through PSN
 */
JNIEXPORT void JNICALL JNI_FCN(sessionCreate)(JNIEnv *env, jobject obj, jobject result, jobject connect_info_obj, jstring log_file_str, jboolean log_verbose, jobject java_session,
		jlong holepunch_ptr, jbyteArray psn_account_id_array)
{
	AndroidChiakiSession *session = NULL;
	AndroidChiakiHolepunch *holepunch = (AndroidChiakiHolepunch *)holepunch_ptr;
	// Until chiaki_session_init() takes it over
	ChiakiHolepunchSession holepunch_session = NULL;
	ChiakiLog *log;
	if(holepunch)
	{
		log = holepunch->log;
		holepunch_session = holepunch->session;
		free(holepunch);
	}
	else
		log = log_create(env, log_file_str, log_verbose);

	ChiakiErrorCode err = CHIAKI_ERR_SUCCESS;
	char *host_str = NULL;

	jclass result_class = E->GetObjectClass(env, result);

	jclass connect_info_class = E->GetObjectClass(env, connect_info_obj);
	jboolean ps5 = E->GetBooleanField(env, connect_info_obj, E->GetFieldID(env, connect_info_class, "ps5", "Z"));
	jstring host_string = E->GetObjectField(env, connect_info_obj, E->GetFieldID(env, connect_info_class, "host", "Ljava/lang/String;"));
	jbyteArray regist_key_array = E->GetObjectField(env, connect_info_obj, E->GetFieldID(env, connect_info_class, "registKey", "[B"));
	jbyteArray morning_array = E->GetObjectField(env, connect_info_obj, E->GetFieldID(env, connect_info_class, "morning", "[B"));
	jboolean enable_dualsense = E->GetBooleanField(env, connect_info_obj, E->GetFieldID(env, connect_info_class, "enableDualSense", "Z"));
	jobject connect_video_profile_obj = E->GetObjectField(env, connect_info_obj, E->GetFieldID(env, connect_info_class, "videoProfile", "L"BASE_PACKAGE"/ConnectVideoProfile;"));
	jclass connect_video_profile_class = E->GetObjectClass(env, connect_video_profile_obj);

	ChiakiConnectInfo connect_info = { 0 };
	connect_info.ps5 = ps5;
	connect_info.enable_dualsense = enable_dualsense;

	if(!log)
	{
		err = CHIAKI_ERR_MEMORY;
		goto beach;
	}

	if(holepunch_session)
	{
		if(!psn_account_id_array || E->GetArrayLength(env, psn_account_id_array) != sizeof(connect_info.psn_account_id))
		{
			CHIAKI_LOGE(log, "PSN Account ID passed from Java has invalid length");
			err = CHIAKI_ERR_INVALID_DATA;
			goto beach;
		}
		E->GetByteArrayRegion(env, psn_account_id_array, 0, sizeof(connect_info.psn_account_id), (jbyte *)connect_info.psn_account_id);
	}

	const char *str_borrow = E->GetStringUTFChars(env, host_string, NULL);
	connect_info.host = host_str = strdup(str_borrow);
	E->ReleaseStringUTFChars(env, host_string, str_borrow);
	if(!connect_info.host)
	{
		err = CHIAKI_ERR_MEMORY;
		goto beach;
	}

	if(E->GetArrayLength(env, regist_key_array) != sizeof(connect_info.regist_key))
	{
		CHIAKI_LOGE(log, "Regist Key passed from Java has invalid length");
		err = CHIAKI_ERR_INVALID_DATA;
		goto beach;
	}
	jbyte *bytes = E->GetByteArrayElements(env, regist_key_array, NULL);
	memcpy(connect_info.regist_key, bytes, sizeof(connect_info.regist_key));
	E->ReleaseByteArrayElements(env, regist_key_array, bytes, JNI_ABORT);

	if(E->GetArrayLength(env, morning_array) != sizeof(connect_info.morning))
	{
		CHIAKI_LOGE(log, "Morning passed from Java has invalid length");
		err = CHIAKI_ERR_INVALID_DATA;
		goto beach;
	}
	bytes = E->GetByteArrayElements(env, morning_array, NULL);
	memcpy(connect_info.morning, bytes, sizeof(connect_info.morning));
	E->ReleaseByteArrayElements(env, morning_array, bytes, JNI_ABORT);

	connect_info.video_profile.width = (unsigned int)E->GetIntField(env, connect_video_profile_obj, E->GetFieldID(env, connect_video_profile_class, "width", "I"));
	connect_info.video_profile.height = (unsigned int)E->GetIntField(env, connect_video_profile_obj, E->GetFieldID(env, connect_video_profile_class, "height", "I"));
	connect_info.video_profile.max_fps = (unsigned int)E->GetIntField(env, connect_video_profile_obj, E->GetFieldID(env, connect_video_profile_class, "maxFPS", "I"));
	connect_info.video_profile.bitrate = (unsigned int)E->GetIntField(env, connect_video_profile_obj, E->GetFieldID(env, connect_video_profile_class, "bitrate", "I"));

	jobject codec_obj = E->GetObjectField(env, connect_video_profile_obj, E->GetFieldID(env, connect_video_profile_class, "codec", "L"BASE_PACKAGE"/Codec;"));
	jclass codec_class = E->GetObjectClass(env, codec_obj);
	jint target_value = E->GetIntField(env, codec_obj, E->GetFieldID(env, codec_class, "value", "I"));
	connect_info.video_profile.codec = (ChiakiCodec)target_value;

	connect_info.video_profile_auto_downgrade = true;
	// Like chiaki-ng: the console hears of up to 5 % loss and lowers the bitrate on a weak network.
	// At 0, it was told there was never any loss and kept sending at full rate.
	connect_info.packet_loss_max = 0.05;

	session = CHIAKI_NEW(AndroidChiakiSession);
	if(!session)
	{
		err = CHIAKI_ERR_MEMORY;
		goto beach;
	}
	memset(session, 0, sizeof(AndroidChiakiSession));
	session->log = log;
	err = android_chiaki_video_decoder_init(&session->video_decoder, log, connect_info.video_profile.width, connect_info.video_profile.height,
			connect_info.ps5 ? connect_info.video_profile.codec : CHIAKI_CODEC_H264);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		free(session);
		session = NULL;
		goto beach;
	}

	err = android_chiaki_audio_decoder_init(&session->audio_decoder, log);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		android_chiaki_video_decoder_fini(&session->video_decoder);
		free(session);
		session = NULL;
		goto beach;
	}

	session->audio_output = android_chiaki_audio_output_new(log);
	session->haptics_output = android_chiaki_haptics_output_new(log);

	android_chiaki_audio_decoder_set_cb(&session->audio_decoder, android_chiaki_audio_output_settings, android_chiaki_audio_output_frame, session->audio_output);

	connect_info.holepunch_session = holepunch_session;
	// It finishes the connection through PSN when it fails, too
	holepunch_session = NULL;
	err = chiaki_session_init(&session->session, &connect_info, log);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		CHIAKI_LOGE(log, "JNI ChiakiSession failed to init");
		android_chiaki_video_decoder_fini(&session->video_decoder);
		android_chiaki_audio_decoder_fini(&session->audio_decoder);
		android_chiaki_audio_output_free(session->audio_output);
		android_chiaki_haptics_output_free(session->haptics_output);
		free(session);
		session = NULL;
		goto beach;
	}

	session->java_session = E->NewGlobalRef(env, java_session);
	session->java_session_class = E->NewGlobalRef(env, E->GetObjectClass(env, session->java_session));
	session->java_session_event_connected_meth = E->GetMethodID(env, session->java_session_class, "eventConnected", "()V");
	session->java_session_event_login_pin_request_meth = E->GetMethodID(env, session->java_session_class, "eventLoginPinRequest", "(Z)V");
	session->java_session_event_quit_meth = E->GetMethodID(env, session->java_session_class, "eventQuit", "(ILjava/lang/String;)V");
	session->java_session_event_rumble_meth = E->GetMethodID(env, session->java_session_class, "eventRumble", "(II)V");
	session->java_session_event_haptics_meth = E->GetMethodID(env, session->java_session_class, "eventHaptics", "(II)V");
	session->java_session_event_trigger_effects_meth = E->GetMethodID(env, session->java_session_class, "eventTriggerEffects", "(II[B[B)V");
	session->java_session_event_led_color_meth = E->GetMethodID(env, session->java_session_class, "eventLedColor", "(III)V");
	session->java_session_event_haptic_intensity_meth = E->GetMethodID(env, session->java_session_class, "eventHapticIntensity", "(I)V");
	session->java_session_event_trigger_intensity_meth = E->GetMethodID(env, session->java_session_class, "eventTriggerIntensity", "(I)V");
	session->java_session_event_cant_display_meth = E->GetMethodID(env, session->java_session_class, "eventCantDisplay", "(Z)V");

	chiaki_orientation_tracker_init(&session->orient_tracker);
	chiaki_accel_new_zero_set_inactive(&session->accel_zero, false);
	session->orient_tracker_active = false;
	atomic_init(&session->motion_reset, false);

	jclass controller_state_class = E->FindClass(env, BASE_PACKAGE"/ControllerState");
	session->java_controller_state_buttons = E->GetFieldID(env, controller_state_class, "buttons", "I");
	session->java_controller_state_l2_state = E->GetFieldID(env, controller_state_class, "l2State", "B");
	session->java_controller_state_r2_state = E->GetFieldID(env, controller_state_class, "r2State", "B");
	session->java_controller_state_left_x = E->GetFieldID(env, controller_state_class, "leftX", "S");
	session->java_controller_state_left_y = E->GetFieldID(env, controller_state_class, "leftY", "S");
	session->java_controller_state_right_x = E->GetFieldID(env, controller_state_class, "rightX", "S");
	session->java_controller_state_right_y = E->GetFieldID(env, controller_state_class, "rightY", "S");
	session->java_controller_state_touches = E->GetFieldID(env, controller_state_class, "touches", "[L"BASE_PACKAGE"/ControllerTouch;");
	session->java_controller_state_gyro_x = E->GetFieldID(env, controller_state_class, "gyroX", "F");
	session->java_controller_state_gyro_y = E->GetFieldID(env, controller_state_class, "gyroY", "F");
	session->java_controller_state_gyro_z = E->GetFieldID(env, controller_state_class, "gyroZ", "F");
	session->java_controller_state_accel_x = E->GetFieldID(env, controller_state_class, "accelX", "F");
	session->java_controller_state_accel_y = E->GetFieldID(env, controller_state_class, "accelY", "F");
	session->java_controller_state_accel_z = E->GetFieldID(env, controller_state_class, "accelZ", "F");
	session->java_controller_state_orient_x = E->GetFieldID(env, controller_state_class, "orientX", "F");
	session->java_controller_state_orient_y = E->GetFieldID(env, controller_state_class, "orientY", "F");
	session->java_controller_state_orient_z = E->GetFieldID(env, controller_state_class, "orientZ", "F");
	session->java_controller_state_orient_w = E->GetFieldID(env, controller_state_class, "orientW", "F");

	jclass controller_touch_class = E->FindClass(env, BASE_PACKAGE"/ControllerTouch");
	session->java_controller_touch_x = E->GetFieldID(env, controller_touch_class, "x", "S");
	session->java_controller_touch_y = E->GetFieldID(env, controller_touch_class, "y", "S");
	session->java_controller_touch_id = E->GetFieldID(env, controller_touch_class, "id", "B");

	chiaki_session_set_event_cb(&session->session, android_chiaki_event_cb, session);
	chiaki_session_set_video_sample_cb(&session->session, android_chiaki_video_decoder_video_sample, &session->video_decoder);

	ChiakiAudioSink audio_sink;
	android_chiaki_audio_decoder_get_sink(&session->audio_decoder, &audio_sink);
	chiaki_session_set_audio_sink(&session->session, &audio_sink);

	ChiakiCtrlDisplaySink display_sink = { 0 };
	display_sink.user = session;
	display_sink.cantdisplay_cb = android_chiaki_cant_display_cb;
	chiaki_session_ctrl_set_display_sink(&session->session, &display_sink);

	if(connect_info.enable_dualsense)
	{
		ChiakiAudioSink haptics_sink = { 0 };
		haptics_sink.user = session;
		haptics_sink.frame_cb = android_chiaki_haptics_frame_cb;
		chiaki_session_set_haptics_sink(&session->session, &haptics_sink);
	}

beach:
	if(holepunch_session)
		chiaki_holepunch_session_fini(holepunch_session);
	if(!session && log)
	{
		android_chiaki_file_log_fini(log);
		free(log);
	}

	free(host_str);
	E->SetIntField(env, result, E->GetFieldID(env, result_class, "errorCode", "I"), (jint)err);
	E->SetLongField(env, result, E->GetFieldID(env, result_class, "ptr", "J"), (jlong)session);
}

JNIEXPORT void JNICALL JNI_FCN(sessionFree)(JNIEnv *env, jobject obj, jlong ptr)
{
	AndroidChiakiSession *session = (AndroidChiakiSession *)ptr;
	if(!session)
		return;
	CHIAKI_LOGI(session->log, "Shutting down JNI Session");
	chiaki_session_fini(&session->session);
	android_chiaki_video_decoder_fini(&session->video_decoder);
	android_chiaki_audio_decoder_fini(&session->audio_decoder);
	android_chiaki_audio_output_free(session->audio_output);
	android_chiaki_haptics_output_free(session->haptics_output);
	E->DeleteGlobalRef(env, session->java_session);
	E->DeleteGlobalRef(env, session->java_session_class);
	CHIAKI_LOGI(session->log, "JNI Session has quit");
	android_chiaki_file_log_fini(session->log);
	free(session->log);
	free(session);
}

JNIEXPORT jint JNICALL JNI_FCN(sessionStart)(JNIEnv *env, jobject obj, jlong ptr)
{
	AndroidChiakiSession *session = (AndroidChiakiSession *)ptr;
	CHIAKI_LOGI(session->log, "Start JNI Session");
	return chiaki_session_start(&session->session);
}

JNIEXPORT jint JNICALL JNI_FCN(sessionStop)(JNIEnv *env, jobject obj, jlong ptr)
{
	AndroidChiakiSession *session = (AndroidChiakiSession *)ptr;
	CHIAKI_LOGI(session->log, "Stop JNI Session");
	return chiaki_session_stop(&session->session);
}

JNIEXPORT jint JNICALL JNI_FCN(sessionJoin)(JNIEnv *env, jobject obj, jlong ptr)
{
	AndroidChiakiSession *session = (AndroidChiakiSession *)ptr;
	CHIAKI_LOGI(session->log, "Join JNI Session");
	return chiaki_session_join(&session->session);
}

JNIEXPORT void JNICALL JNI_FCN(sessionSetSurface)(JNIEnv *env, jobject obj, jlong ptr, jobject surface)
{
	AndroidChiakiSession *session = (AndroidChiakiSession *)ptr;
	android_chiaki_video_decoder_set_surface(&session->video_decoder, env, surface);
}

JNIEXPORT void JNICALL JNI_FCN(sessionSetControllerState)(JNIEnv *env, jobject obj, jlong ptr, jobject controller_state_java)
{
	AndroidChiakiSession *session = (AndroidChiakiSession *)ptr;
	ChiakiControllerState controller_state;
	chiaki_controller_state_set_idle(&controller_state);
	controller_state.buttons = (uint32_t)E->GetIntField(env, controller_state_java, session->java_controller_state_buttons);
	controller_state.l2_state = (uint8_t)E->GetByteField(env, controller_state_java, session->java_controller_state_l2_state);
	controller_state.r2_state = (uint8_t)E->GetByteField(env, controller_state_java, session->java_controller_state_r2_state);
	controller_state.left_x = (int16_t)E->GetShortField(env, controller_state_java, session->java_controller_state_left_x);
	controller_state.left_y = (int16_t)E->GetShortField(env, controller_state_java, session->java_controller_state_left_y);
	controller_state.right_x = (int16_t)E->GetShortField(env, controller_state_java, session->java_controller_state_right_x);
	controller_state.right_y = (int16_t)E->GetShortField(env, controller_state_java, session->java_controller_state_right_y);
	jobjectArray touch_array = E->GetObjectField(env, controller_state_java, session->java_controller_state_touches);
	size_t touch_array_len = (size_t)E->GetArrayLength(env, touch_array);
	for(size_t i = 0; i < CHIAKI_CONTROLLER_TOUCHES_MAX; i++)
	{
		if(i < touch_array_len)
		{
			jobject touch = E->GetObjectArrayElement(env, touch_array, i);
			controller_state.touches[i].x = (uint16_t)E->GetShortField(env, touch, session->java_controller_touch_x);
			controller_state.touches[i].y = (uint16_t)E->GetShortField(env, touch, session->java_controller_touch_y);
			controller_state.touches[i].id = (int8_t)E->GetByteField(env, touch, session->java_controller_touch_id);
		}
		else
		{
			controller_state.touches[i].x = 0;
			controller_state.touches[i].y = 0;
			controller_state.touches[i].id = -1;
		}
	}
	controller_state.gyro_x = E->GetFloatField(env, controller_state_java, session->java_controller_state_gyro_x);
	controller_state.gyro_y = E->GetFloatField(env, controller_state_java, session->java_controller_state_gyro_y);
	controller_state.gyro_z = E->GetFloatField(env, controller_state_java, session->java_controller_state_gyro_z);
	controller_state.accel_x = E->GetFloatField(env, controller_state_java, session->java_controller_state_accel_x);
	controller_state.accel_y = E->GetFloatField(env, controller_state_java, session->java_controller_state_accel_y);
	controller_state.accel_z = E->GetFloatField(env, controller_state_java, session->java_controller_state_accel_z);
	controller_state.orient_x = E->GetFloatField(env, controller_state_java, session->java_controller_state_orient_x);
	controller_state.orient_y = E->GetFloatField(env, controller_state_java, session->java_controller_state_orient_y);
	controller_state.orient_z = E->GetFloatField(env, controller_state_java, session->java_controller_state_orient_z);
	controller_state.orient_w = E->GetFloatField(env, controller_state_java, session->java_controller_state_orient_w);
	if(session->orient_tracker_active)
		chiaki_orientation_tracker_apply_to_controller_state(&session->orient_tracker, &controller_state);
	chiaki_session_set_controller_state(&session->session, &controller_state);
}

JNIEXPORT void JNICALL JNI_FCN(sessionSetMotion)(JNIEnv *env, jobject obj, jlong ptr,
		jfloat gyro_x, jfloat gyro_y, jfloat gyro_z, jfloat accel_x, jfloat accel_y, jfloat accel_z, jint timestamp_us)
{
	AndroidChiakiSession *session = (AndroidChiakiSession *)ptr;
	// The console asks for this to make the current orientation the neutral one
	if(atomic_exchange(&session->motion_reset, false))
		chiaki_orientation_tracker_init(&session->orient_tracker);
	chiaki_orientation_tracker_update(&session->orient_tracker, gyro_x, gyro_y, gyro_z, accel_x, accel_y, accel_z,
			&session->accel_zero, false, (uint32_t)timestamp_us);
	session->orient_tracker_active = true;
}

JNIEXPORT void JNICALL JNI_FCN(sessionSetAudioDevice)(JNIEnv *env, jobject obj, jlong ptr, jint device_id)
{
	AndroidChiakiSession *session = (AndroidChiakiSession *)ptr;
	android_chiaki_audio_output_set_device((int32_t)device_id, session->audio_output);
}

JNIEXPORT void JNICALL JNI_FCN(sessionSetHapticsDevice)(JNIEnv *env, jobject obj, jlong ptr, jint device_id)
{
	AndroidChiakiSession *session = (AndroidChiakiSession *)ptr;
	android_chiaki_haptics_output_set_device((int32_t)device_id, session->haptics_output);
}

JNIEXPORT void JNICALL JNI_FCN(sessionSetLoginPin)(JNIEnv *env, jobject obj, jlong ptr, jstring pin_java)
{
	AndroidChiakiSession *session = (AndroidChiakiSession *)ptr;
	const char *pin = E->GetStringUTFChars(env, pin_java, NULL);
	chiaki_session_set_login_pin(&session->session, (const uint8_t *)pin, strlen(pin));
	E->ReleaseStringUTFChars(env, pin_java, pin);
}

JNIEXPORT jint JNICALL JNI_FCN(sessionGotoBed)(JNIEnv *env, jobject obj, jlong ptr)
{
	AndroidChiakiSession *session = (AndroidChiakiSession *)ptr;
	CHIAKI_LOGI(session->log, "Putting the console in rest mode");
	return chiaki_session_goto_bed(&session->session);
}

/**
 * Fills stats with the stream's bitrate in Mbit/s, packet loss (0 to 1), ping in ms measured when
 * connecting, the number of frames rendered so far, and the decode time's average and maximum in ms
 */
JNIEXPORT void JNICALL JNI_FCN(sessionGetStats)(JNIEnv *env, jobject obj, jlong ptr, jfloatArray stats_array)
{
	AndroidChiakiSession *session = (AndroidChiakiSession *)ptr;
	AndroidChiakiVideoDecoder *decoder = &session->video_decoder;
	// The loss since the last call: the congestion control's own figure covers only its last 200 ms,
	// so looking at it once a second missed most losses, as they come in bursts
	ChiakiCongestionControl *congestion = &session->session.stream_connection.congestion_control;
	uint64_t received = __atomic_load_n(&congestion->received_total, __ATOMIC_RELAXED);
	uint64_t lost = __atomic_load_n(&congestion->lost_total, __ATOMIC_RELAXED);
	if(received < session->stats_received || lost < session->stats_lost)
		session->stats_received = session->stats_lost = 0;
	uint64_t received_new = received - session->stats_received;
	uint64_t lost_new = lost - session->stats_lost;
	session->stats_received = received;
	session->stats_lost = lost;
	jfloat stats[6] = {
		(jfloat)session->session.stream_connection.measured_bitrate,
		received_new + lost_new > 0 ? (jfloat)lost_new / (jfloat)(received_new + lost_new) : 0.0f,
		(jfloat)(session->session.rtt_us / 1000.0),
		(jfloat)__atomic_load_n(&decoder->frames_rendered, __ATOMIC_RELAXED),
		(jfloat)(__atomic_load_n(&decoder->stats_latency_avg_us, __ATOMIC_RELAXED) / 1000.0),
		(jfloat)(__atomic_load_n(&decoder->stats_latency_max_us, __ATOMIC_RELAXED) / 1000.0)
	};
	E->SetFloatArrayRegion(env, stats_array, 0, 6, stats);
}

/**
 * Lists the consoles of a PSN account. Blocks for the network requests.
 * @return an array of HolepunchDevice, or null if it failed, with result.errorCode set
 */
JNIEXPORT jobjectArray JNICALL JNI_FCN(holepunchListDevices)(JNIEnv *env, jobject obj, jobject result, jstring token_string, jboolean ps5)
{
	const char *token = E->GetStringUTFChars(env, token_string, NULL);
	ChiakiHolepunchDeviceInfo *devices = NULL;
	size_t devices_count = 0;
	ChiakiErrorCode err = chiaki_holepunch_list_devices(token,
			ps5 ? CHIAKI_HOLEPUNCH_CONSOLE_TYPE_PS5 : CHIAKI_HOLEPUNCH_CONSOLE_TYPE_PS4,
			&devices, &devices_count, &global_log);
	E->ReleaseStringUTFChars(env, token_string, token);
	jclass result_class = E->GetObjectClass(env, result);
	E->SetIntField(env, result, E->GetFieldID(env, result_class, "errorCode", "I"), (jint)err);
	if(err != CHIAKI_ERR_SUCCESS)
		return NULL;

	jclass device_class = E->FindClass(env, BASE_PACKAGE"/HolepunchDevice");
	jmethodID device_ctor = E->GetMethodID(env, device_class, "<init>", "([B[BZZ)V");
	jobjectArray r = E->NewObjectArray(env, devices_count, device_class, NULL);
	for(size_t i=0; i<devices_count; i++)
	{
		ChiakiHolepunchDeviceInfo *device = &devices[i];
		// The name is UTF-8, which isn't always valid modified UTF-8 for NewStringUTF()
		size_t name_size = strnlen(device->device_name, sizeof(device->device_name));
		jobject java_device = E->NewObject(env, device_class, device_ctor,
				jnibytearray_create(env, (const uint8_t *)device->device_name, name_size),
				jnibytearray_create(env, device->device_uid, sizeof(device->device_uid)),
				(jboolean)(device->type == CHIAKI_HOLEPUNCH_CONSOLE_TYPE_PS5),
				(jboolean)device->remoteplay_enabled);
		E->SetObjectArrayElement(env, r, i, java_device);
		E->DeleteLocalRef(env, java_device);
	}
	if(devices)
		chiaki_holepunch_free_device_list(&devices);
	return r;
}

/**
 * Starts a connection through PSN, which the steps below continue, in order
 * @return 0 if it failed
 */
JNIEXPORT jlong JNICALL JNI_FCN(holepunchCreate)(JNIEnv *env, jobject obj, jstring token_string, jstring log_file_str, jboolean log_verbose)
{
	AndroidChiakiHolepunch *holepunch = CHIAKI_NEW(AndroidChiakiHolepunch);
	if(!holepunch)
		return 0;
	holepunch->log = log_create(env, log_file_str, log_verbose);
	if(!holepunch->log)
	{
		free(holepunch);
		return 0;
	}
	const char *token = E->GetStringUTFChars(env, token_string, NULL);
	holepunch->session = chiaki_holepunch_session_init(token, holepunch->log);
	E->ReleaseStringUTFChars(env, token_string, token);
	if(!holepunch->session)
	{
		CHIAKI_LOGE(holepunch->log, "Failed to start the connection through PSN");
		android_chiaki_file_log_fini(holepunch->log);
		free(holepunch->log);
		free(holepunch);
		return 0;
	}
	CHIAKI_LOGI(holepunch->log, "Connecting through PSN");
	return (jlong)holepunch;
}

/** Creates the session on PSN, which the console joins. Blocks. */
JNIEXPORT jint JNICALL JNI_FCN(holepunchSessionCreate)(JNIEnv *env, jobject obj, jlong ptr)
{
	AndroidChiakiHolepunch *holepunch = (AndroidChiakiHolepunch *)ptr;
	ChiakiErrorCode err = chiaki_holepunch_upnp_discover(holepunch->session);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		CHIAKI_LOGE(holepunch->log, "UPnP discovery failed: %s", chiaki_error_string(err));
		return err;
	}
	err = chiaki_holepunch_session_create(holepunch->session);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		CHIAKI_LOGE(holepunch->log, "Creating the PSN session failed: %s", chiaki_error_string(err));
		return err;
	}
	err = holepunch_session_create_offer(holepunch->session);
	if(err != CHIAKI_ERR_SUCCESS)
		CHIAKI_LOGE(holepunch->log, "Creating the offer for the control connection failed: %s", chiaki_error_string(err));
	return err;
}

/** Asks the console, also in rest mode, to join the session. Blocks. */
JNIEXPORT jint JNICALL JNI_FCN(holepunchSessionStart)(JNIEnv *env, jobject obj, jlong ptr, jbyteArray console_uid_array, jboolean ps5)
{
	AndroidChiakiHolepunch *holepunch = (AndroidChiakiHolepunch *)ptr;
	uint8_t console_uid[32];
	if(E->GetArrayLength(env, console_uid_array) != sizeof(console_uid))
	{
		CHIAKI_LOGE(holepunch->log, "Console id passed from Java has invalid length");
		return CHIAKI_ERR_INVALID_DATA;
	}
	E->GetByteArrayRegion(env, console_uid_array, 0, sizeof(console_uid), (jbyte *)console_uid);
	ChiakiErrorCode err = chiaki_holepunch_session_start(holepunch->session, console_uid,
			ps5 ? CHIAKI_HOLEPUNCH_CONSOLE_TYPE_PS5 : CHIAKI_HOLEPUNCH_CONSOLE_TYPE_PS4);
	if(err != CHIAKI_ERR_SUCCESS)
		CHIAKI_LOGE(holepunch->log, "Starting the PSN session on the console failed: %s", chiaki_error_string(err));
	return err;
}

/** Opens the control connection to the console through the routers. Blocks. */
JNIEXPORT jint JNICALL JNI_FCN(holepunchPunchHole)(JNIEnv *env, jobject obj, jlong ptr)
{
	AndroidChiakiHolepunch *holepunch = (AndroidChiakiHolepunch *)ptr;
	ChiakiErrorCode err = chiaki_holepunch_session_punch_hole(holepunch->session, CHIAKI_HOLEPUNCH_PORT_TYPE_CTRL);
	if(err != CHIAKI_ERR_SUCCESS)
		CHIAKI_LOGE(holepunch->log, "Opening the control connection failed: %s", chiaki_error_string(err));
	else
		CHIAKI_LOGI(holepunch->log, "Opened the control connection through PSN");
	return err;
}

/** Makes the step that is running return soon, from another thread */
JNIEXPORT void JNICALL JNI_FCN(holepunchCancel)(JNIEnv *env, jobject obj, jlong ptr)
{
	AndroidChiakiHolepunch *holepunch = (AndroidChiakiHolepunch *)ptr;
	chiaki_holepunch_main_thread_cancel(holepunch->session, true);
}

/** For a connection no session took over. Blocks while it removes the session from PSN. */
JNIEXPORT void JNICALL JNI_FCN(holepunchFree)(JNIEnv *env, jobject obj, jlong ptr)
{
	AndroidChiakiHolepunch *holepunch = (AndroidChiakiHolepunch *)ptr;
	if(!holepunch)
		return;
	chiaki_holepunch_session_fini(holepunch->session);
	android_chiaki_file_log_fini(holepunch->log);
	free(holepunch->log);
	free(holepunch);
}

typedef struct android_discovery_service_t
{
	ChiakiDiscoveryService service;
	jobject java_service;
	jclass java_service_class;
	jmethodID java_service_hosts_updated_meth;

	jclass host_class;
	jmethodID host_ctor;
	jobject host_state_unknown;
	jobject host_state_ready;
	jobject host_state_standby;
} AndroidDiscoveryService;

static void android_discovery_service_cb(ChiakiDiscoveryHost *hosts, size_t hosts_count, void *user)
{
	AndroidDiscoveryService *service = user;

	CHIAKI_LOGI(&global_log, "JNI Discovery Callback got %llu hosts", (unsigned long long)hosts_count);

	JNIEnv *env = attach_thread_jni();
	if(!env)
		return;

	jobjectArray r = E->NewObjectArray(env, hosts_count, service->host_class, NULL);

	for(size_t i=0; i<hosts_count; i++)
	{
		jobject state;
		ChiakiDiscoveryHost *host = hosts + i;
		switch(host->state)
		{
			case CHIAKI_DISCOVERY_HOST_STATE_STANDBY:
				state = service->host_state_standby;
				break;
			case CHIAKI_DISCOVERY_HOST_STATE_READY:
				state = service->host_state_ready;
				break;
			default:
				state = service->host_state_unknown;
				break;
		}

		jobject o = E->NewObject(env, service->host_class, service->host_ctor,
				state,
				host->host_request_port,
				jnistr_from_ascii(env, host->host_addr),
				jnistr_from_ascii(env, host->system_version),
				jnistr_from_ascii(env, host->device_discovery_protocol_version),
				jnistr_from_ascii(env, host->host_name),
				jnistr_from_ascii(env, host->host_type),
				jnistr_from_ascii(env, host->host_id),
				jnistr_from_ascii(env, host->running_app_titleid),
				jnistr_from_ascii(env, host->running_app_name));

		E->SetObjectArrayElement(env, r, i, o);
	}

	E->CallVoidMethod(env, service->java_service, service->java_service_hosts_updated_meth, r);

	(*global_vm)->DetachCurrentThread(global_vm);
}

static ChiakiErrorCode sockaddr_from_java(JNIEnv *env, jobject /*InetSocketAddress*/ sockaddr_obj, struct sockaddr **addr, size_t *addr_size)
{
	jclass sockaddr_class = E->GetObjectClass(env, sockaddr_obj);
	uint16_t port = (uint16_t)E->CallIntMethod(env, sockaddr_obj, E->GetMethodID(env, sockaddr_class, "getPort", "()I"));
	jobject addr_obj = E->CallObjectMethod(env, sockaddr_obj, E->GetMethodID(env, sockaddr_class, "getAddress", "()Ljava/net/InetAddress;"));
	jclass addr_class = E->GetObjectClass(env, addr_obj);
	jbyteArray addr_byte_array = E->CallObjectMethod(env, addr_obj, E->GetMethodID(env, addr_class, "getAddress", "()[B"));
	jsize addr_byte_array_len = E->GetArrayLength(env, addr_byte_array);

	if(addr_byte_array_len == 4)
	{
		struct sockaddr_in *inaddr = CHIAKI_NEW(struct sockaddr_in);
		if(!inaddr)
			return CHIAKI_ERR_MEMORY;
		memset(inaddr, 0, sizeof(*inaddr));
		inaddr->sin_family = AF_INET;
		jbyte *bytes = E->GetByteArrayElements(env, addr_byte_array, NULL);
		memcpy(&inaddr->sin_addr.s_addr, bytes, sizeof(inaddr->sin_addr.s_addr));
		E->ReleaseByteArrayElements(env, addr_byte_array, bytes, JNI_ABORT);
		inaddr->sin_port = htons(port);

		*addr = (struct sockaddr *)inaddr;
		*addr_size = sizeof(*inaddr);
	}
	else if(addr_byte_array_len == 0x10)
	{
		struct sockaddr_in6 *inaddr6 = CHIAKI_NEW(struct sockaddr_in6);
		if(!inaddr6)
			return CHIAKI_ERR_MEMORY;
		memset(inaddr6, 0, sizeof(*inaddr6));
		inaddr6->sin6_family = AF_INET6;
		jbyte *bytes = E->GetByteArrayElements(env, addr_byte_array, NULL);
		memcpy(&inaddr6->sin6_addr.in6_u, bytes, sizeof(inaddr6->sin6_addr.in6_u));
		E->ReleaseByteArrayElements(env, addr_byte_array, bytes, JNI_ABORT);
		inaddr6->sin6_port = htons(port);

		*addr = (struct sockaddr *)inaddr6;
		*addr_size = sizeof(*inaddr6);
	}
	else
		return CHIAKI_ERR_INVALID_DATA;

	return CHIAKI_ERR_SUCCESS;
}

JNIEXPORT void JNICALL JNI_FCN(discoveryServiceCreate)(JNIEnv *env, jobject obj, jobject result, jobject options_obj, jobject java_service)
{
	jclass result_class = E->GetObjectClass(env, result);
	ChiakiErrorCode err = CHIAKI_ERR_SUCCESS;
	ChiakiDiscoveryServiceOptions options = { 0 };

	AndroidDiscoveryService *service = CHIAKI_NEW(AndroidDiscoveryService);
	if(!service)
	{
		err = CHIAKI_ERR_MEMORY;
		goto beach;
	}

	jclass options_class = E->GetObjectClass(env, options_obj);

	options.hosts_max = (size_t)E->GetLongField(env, options_obj, E->GetFieldID(env, options_class, "hostsMax", "J"));
	options.host_drop_pings = (uint64_t)E->GetLongField(env, options_obj, E->GetFieldID(env, options_class, "hostDropPings", "J"));
	options.ping_ms = (uint64_t)E->GetLongField(env, options_obj, E->GetFieldID(env, options_class, "pingMs", "J"));
	options.cb = android_discovery_service_cb;
	options.cb_user = service;

	err = sockaddr_from_java(env, E->GetObjectField(env, options_obj, E->GetFieldID(env, options_class, "sendAddr", "Ljava/net/InetSocketAddress;")), &options.send_addr, &options.send_addr_size);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		CHIAKI_LOGE(&global_log, "Failed to get sockaddr from InetSocketAddress");
		goto beach;
	}

	service->java_service = E->NewGlobalRef(env, java_service);
	service->java_service_class = E->GetObjectClass(env, service->java_service);
	service->java_service_hosts_updated_meth = E->GetMethodID(env, service->java_service_class, "hostsUpdated", "([L"BASE_PACKAGE"/DiscoveryHost;)V");

	service->host_class = E->NewGlobalRef(env, E->FindClass(env, BASE_PACKAGE"/DiscoveryHost"));
	service->host_ctor = E->GetMethodID(env, service->host_class, "<init>", "("
		"L"BASE_PACKAGE"/DiscoveryHost$State;"
		"S" // hostRequestPort: UShort
		"Ljava/lang/String;" // hostAddr: String?,
		"Ljava/lang/String;" // systemVersion: String?,
		"Ljava/lang/String;" // deviceDiscoveryProtocolVersion: String?,
		"Ljava/lang/String;" // hostName: String?,
		"Ljava/lang/String;" // hostType: String?,
		"Ljava/lang/String;" // hostId: String?,
		"Ljava/lang/String;" // runningAppTitleid: String?,
		"Ljava/lang/String;" // runningAppName: String?
		")V");

	jclass host_state_class = E->FindClass(env, BASE_PACKAGE"/DiscoveryHost$State");
	service->host_state_unknown = E->NewGlobalRef(env, E->GetStaticObjectField(env, host_state_class, E->GetStaticFieldID(env, host_state_class, "UNKNOWN", "L"BASE_PACKAGE"/DiscoveryHost$State;")));
	service->host_state_standby = E->NewGlobalRef(env, E->GetStaticObjectField(env, host_state_class, E->GetStaticFieldID(env, host_state_class, "STANDBY", "L"BASE_PACKAGE"/DiscoveryHost$State;")));
	service->host_state_ready = E->NewGlobalRef(env, E->GetStaticObjectField(env, host_state_class, E->GetStaticFieldID(env, host_state_class, "READY", "L"BASE_PACKAGE"/DiscoveryHost$State;")));


	err = chiaki_discovery_service_init(&service->service, &options, &global_log);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		CHIAKI_LOGE(&global_log, "Failed to create discovery service (JNI)");
		E->DeleteGlobalRef(env, service->java_service);
		E->DeleteGlobalRef(env, service->host_state_unknown);
		E->DeleteGlobalRef(env, service->host_state_standby);
		E->DeleteGlobalRef(env, service->host_state_ready);
		E->DeleteGlobalRef(env, service->host_class);
		free(service);
		goto beach;
	}

beach:
	free(options.send_addr);
	E->SetIntField(env, result, E->GetFieldID(env, result_class, "errorCode", "I"), (jint)err);
	E->SetLongField(env, result, E->GetFieldID(env, result_class, "ptr", "J"), (jlong)service);
}

JNIEXPORT void JNICALL JNI_FCN(discoveryServiceFree)(JNIEnv *env, jobject obj, jlong ptr)
{
	AndroidDiscoveryService *service = (AndroidDiscoveryService *)ptr;
	if(!service)
		return;
	chiaki_discovery_service_fini(&service->service);
	E->DeleteGlobalRef(env, service->java_service);
	E->DeleteGlobalRef(env, service->host_state_unknown);
	E->DeleteGlobalRef(env, service->host_state_standby);
	E->DeleteGlobalRef(env, service->host_state_ready);
	E->DeleteGlobalRef(env, service->host_class);
	free(service);
}

JNIEXPORT jint JNICALL JNI_FCN(discoveryServiceWakeup)(JNIEnv *env, jobject obj, jlong ptr, jstring host_string, jlong user_credential, jboolean ps5)
{
	AndroidDiscoveryService *service = (AndroidDiscoveryService *)ptr;
	const char *host = E->GetStringUTFChars(env, host_string, NULL);
	ChiakiErrorCode r = chiaki_discovery_wakeup(&global_log, service ? &service->service.discovery : NULL, host, (uint64_t)user_credential, ps5);
	E->ReleaseStringUTFChars(env, host_string, host);
	return r;
}


typedef struct android_chiaki_regist_t
{
	AndroidChiakiJNILog log;
	ChiakiRegist regist;

	jobject java_regist;
	jmethodID java_regist_event_meth;

	jclass java_target_class;

	jobject java_regist_event_canceled;
	jobject java_regist_event_failed;
	jclass java_regist_event_success_class;
	jmethodID java_regist_event_success_ctor;

	jclass java_regist_host_class;
	jmethodID java_regist_host_ctor;
} AndroidChiakiRegist;

static jobject create_jni_target(JNIEnv *env, jclass target_class, ChiakiTarget target)
{
	jmethodID meth = E->GetStaticMethodID(env, target_class, "fromValue", "(I)L"BASE_PACKAGE"/Target;");
	return E->CallStaticObjectMethod(env, target_class, meth, (jint)target);
}

static void android_chiaki_regist_cb(ChiakiRegistEvent *event, void *user)
{
	AndroidChiakiRegist *regist = user;

	JNIEnv *env = attach_thread_jni();
	if(!env)
		return;

	jobject java_event = NULL;
	switch(event->type)
	{
		case CHIAKI_REGIST_EVENT_TYPE_FINISHED_CANCELED:
			java_event = regist->java_regist_event_canceled;
			break;
		case CHIAKI_REGIST_EVENT_TYPE_FINISHED_FAILED:
			java_event = regist->java_regist_event_failed;
			break;
		case CHIAKI_REGIST_EVENT_TYPE_FINISHED_SUCCESS:
		{
			ChiakiRegisteredHost *host = event->registered_host;
			jobject java_host = E->NewObject(env, regist->java_regist_host_class, regist->java_regist_host_ctor,
					create_jni_target(env, regist->java_target_class, host->target),
					jnistr_from_ascii(env, host->ap_ssid),
					jnistr_from_ascii(env, host->ap_bssid),
					jnistr_from_ascii(env, host->ap_key),
					jnistr_from_ascii(env, host->ap_name),
					jnibytearray_create(env, host->server_mac, sizeof(host->server_mac)),
					jnistr_from_ascii(env, host->server_nickname),
					jnibytearray_create(env, (const uint8_t *)host->rp_regist_key, sizeof(host->rp_regist_key)),
					(jint)host->rp_key_type,
					jnibytearray_create(env, host->rp_key, sizeof(host->rp_key)));
			java_event = E->NewObject(env, regist->java_regist_event_success_class, regist->java_regist_event_success_ctor, java_host);
			break;
		}
	}

	if(java_event)
		E->CallVoidMethod(env, regist->java_regist, regist->java_regist_event_meth, java_event);

	(*global_vm)->DetachCurrentThread(global_vm);
}

static void android_chiaki_regist_fini_partial(JNIEnv *env, AndroidChiakiRegist *regist)
{
	android_chiaki_jni_log_fini(&regist->log, env);
	E->DeleteGlobalRef(env, regist->java_regist);
	E->DeleteGlobalRef(env, regist->java_target_class);
	E->DeleteGlobalRef(env, regist->java_regist_event_canceled);
	E->DeleteGlobalRef(env, regist->java_regist_event_failed);
	E->DeleteGlobalRef(env, regist->java_regist_event_success_class);
	E->DeleteGlobalRef(env, regist->java_regist_host_class);
}

JNIEXPORT void JNICALL JNI_FCN(registStart)(JNIEnv *env, jobject obj, jobject result, jobject regist_info_obj, jobject log_obj, jobject java_regist)
{
	jclass result_class = E->GetObjectClass(env, result);
	ChiakiErrorCode err = CHIAKI_ERR_SUCCESS;
	AndroidChiakiRegist *regist = CHIAKI_NEW(AndroidChiakiRegist);
	if(!regist)
	{
		err = CHIAKI_ERR_MEMORY;
		goto beach;
	}

	android_chiaki_jni_log_init(&regist->log, env, log_obj);

	regist->java_regist = E->NewGlobalRef(env, java_regist);
	regist->java_regist_event_meth = E->GetMethodID(env, E->GetObjectClass(env, regist->java_regist), "event", "(L"BASE_PACKAGE"/RegistEvent;)V");

	regist->java_target_class = E->NewGlobalRef(env, E->FindClass(env, BASE_PACKAGE"/Target"));

	regist->java_regist_event_canceled = E->NewGlobalRef(env, get_kotlin_global_object(env, BASE_PACKAGE"/RegistEventCanceled"));
	regist->java_regist_event_failed = E->NewGlobalRef(env, get_kotlin_global_object(env, BASE_PACKAGE"/RegistEventFailed"));
	regist->java_regist_event_success_class = E->NewGlobalRef(env, E->FindClass(env, BASE_PACKAGE"/RegistEventSuccess"));
	regist->java_regist_event_success_ctor = E->GetMethodID(env, regist->java_regist_event_success_class, "<init>", "(L"BASE_PACKAGE"/RegistHost;)V");

	regist->java_regist_host_class = E->NewGlobalRef(env, E->FindClass(env, BASE_PACKAGE"/RegistHost"));
	regist->java_regist_host_ctor = E->GetMethodID(env, regist->java_regist_host_class, "<init>", "("
			  "L"BASE_PACKAGE"/Target;" // target: Target
			  "Ljava/lang/String;" // apSsid: String
			  "Ljava/lang/String;" // apBssid: String
			  "Ljava/lang/String;" // apKey: String
			  "Ljava/lang/String;" // apName: String
			  "[B" // serverMac: ByteArray
			  "Ljava/lang/String;" // serverNickname: String
			  "[B" // rpRegistKey: ByteArray
			  "I" // rpKeyType: UInt
			  "[B" // rpKey: ByteArray
			  ")V");

	jclass regist_info_class = E->GetObjectClass(env, regist_info_obj);

	jobject target_obj = E->GetObjectField(env, regist_info_obj, E->GetFieldID(env, regist_info_class, "target", "L"BASE_PACKAGE"/Target;"));
	jclass target_class = E->GetObjectClass(env, target_obj);
	jint target_value = E->GetIntField(env, target_obj, E->GetFieldID(env, target_class, "value", "I"));

	jstring host_string = E->GetObjectField(env, regist_info_obj, E->GetFieldID(env, regist_info_class, "host", "Ljava/lang/String;"));
	jboolean broadcast = E->GetBooleanField(env, regist_info_obj, E->GetFieldID(env, regist_info_class, "broadcast", "Z"));
	jstring psn_online_id_string = E->GetObjectField(env, regist_info_obj, E->GetFieldID(env, regist_info_class, "psnOnlineId", "Ljava/lang/String;"));
	jbyteArray psn_account_id_array = E->GetObjectField(env, regist_info_obj, E->GetFieldID(env, regist_info_class, "psnAccountId", "[B"));
	jint pin = E->GetIntField(env, regist_info_obj, E->GetFieldID(env, regist_info_class, "pin", "I"));

	ChiakiRegistInfo regist_info = { 0 };
	regist_info.target = (ChiakiTarget)target_value;
	regist_info.host = E->GetStringUTFChars(env, host_string, NULL);
	regist_info.broadcast = broadcast;
	if(psn_online_id_string)
		regist_info.psn_online_id = E->GetStringUTFChars(env, psn_online_id_string, NULL);
	if(psn_account_id_array && E->GetArrayLength(env, psn_account_id_array) == sizeof(regist_info.psn_account_id))
		E->GetByteArrayRegion(env, psn_account_id_array, 0, sizeof(regist_info.psn_account_id), (jbyte *)regist_info.psn_account_id);
	regist_info.pin = (uint32_t)pin;

	err = chiaki_regist_start(&regist->regist, &regist->log.log, &regist_info, android_chiaki_regist_cb, regist);

	E->ReleaseStringUTFChars(env, host_string, regist_info.host);
	if(regist_info.psn_online_id)
		E->ReleaseStringUTFChars(env, psn_online_id_string, regist_info.psn_online_id);

	if(err != CHIAKI_ERR_SUCCESS)
	{
		android_chiaki_regist_fini_partial(env, regist);
		free(regist);
		regist = NULL;
	}

beach:
	E->SetIntField(env, result, E->GetFieldID(env, result_class, "errorCode", "I"), (jint)err);
	E->SetLongField(env, result, E->GetFieldID(env, result_class, "ptr", "J"), (jlong)regist);
}

JNIEXPORT void JNICALL JNI_FCN(registStop)(JNIEnv *env, jobject obj, jlong ptr)
{
	AndroidChiakiRegist *regist = (AndroidChiakiRegist *)ptr;
	chiaki_regist_stop(&regist->regist);
}

JNIEXPORT void JNICALL JNI_FCN(registFree)(JNIEnv *env, jobject obj, jlong ptr)
{
	AndroidChiakiRegist *regist = (AndroidChiakiRegist *)ptr;
	chiaki_regist_fini(&regist->regist);
	android_chiaki_regist_fini_partial(env, regist);
	free(regist);
}
