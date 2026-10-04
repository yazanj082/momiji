// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import com.metallic.chiaki.R
import com.metallic.chiaki.lib.Codec
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.lib.VideoFPSPreset
import com.metallic.chiaki.lib.VideoResolutionPreset
import io.reactivex.Observable
import io.reactivex.subjects.BehaviorSubject
import kotlin.math.max
import kotlin.math.min

class Preferences(context: Context)
{
	enum class Resolution(val value: String, @StringRes val title: Int, val preset: VideoResolutionPreset)
	{
		RES_360P("360p", R.string.preferences_resolution_title_360p, VideoResolutionPreset.RES_360P),
		RES_540P("540p", R.string.preferences_resolution_title_540p, VideoResolutionPreset.RES_540P),
		RES_720P("720p", R.string.preferences_resolution_title_720p, VideoResolutionPreset.RES_720P),
		RES_1080P("1080p", R.string.preferences_resolution_title_1080p, VideoResolutionPreset.RES_1080P),
	}

	enum class FPS(val value: String, @StringRes val title: Int, val preset: VideoFPSPreset)
	{
		FPS_30("30", R.string.preferences_fps_title_30, VideoFPSPreset.FPS_30),
		FPS_60("60", R.string.preferences_fps_title_60, VideoFPSPreset.FPS_60)
	}

	enum class Codec(val value: String, @StringRes val title: Int, val codec: com.metallic.chiaki.lib.Codec)
	{
		CODEC_H264("h264", R.string.preferences_codec_title_h264, com.metallic.chiaki.lib.Codec.CODEC_H264),
		CODEC_H265("h265", R.string.preferences_codec_title_h265, com.metallic.chiaki.lib.Codec.CODEC_H265)
	}

	/**
	 * Resolution, frame rate and bitrate together. Custom uses the separate settings for these.
	 */
	enum class Quality(val value: String, @StringRes val title: Int, @StringRes val summary: Int)
	{
		SMOOTH("smooth", R.string.quality_smooth, R.string.quality_smooth_summary),
		BALANCED("balanced", R.string.quality_balanced, R.string.quality_balanced_summary),
		SHARP("sharp", R.string.quality_sharp, R.string.quality_sharp_summary),
		CUSTOM("custom", R.string.quality_custom, R.string.quality_custom_summary);

		companion object
		{
			fun fromValue(value: String?) = values().firstOrNull { it.value == value }
		}
	}

	companion object
	{
		val resolutionAll = Resolution.values()
		/** Little data and delay, for a weak network and away from home */
		private const val SMOOTH_BITRATE = 5000
		/** A PS4 Pro can't stream much more */
		private const val SHARP_BITRATE_PS4 = 15000
		private const val SHARP_BITRATE_PS5 = 25000
		val qualityAwayDefault = Quality.SMOOTH
		val fpsDefault = FPS.FPS_60
		val fpsAll = FPS.values()
		val codecDefault = Codec.CODEC_H265
		val codecAll = Codec.values()
	}

	internal val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
	private val sharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		when(key)
		{
			resolutionKey -> bitrateAutoSubject.onNext(bitrateAuto)
		}
	}.also { sharedPreferences.registerOnSharedPreferenceChangeListener(it) }

	private val resources = context.resources
	private val packageManager = context.packageManager
	private val homeActivity = ComponentName(context, "com.metallic.chiaki.main.HomeActivity")

	/**
	 * TV boxes and other devices without a touchscreen, played with a controller on a big screen
	 */
	val isTv = !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
			|| packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

	private val resolutionDefault = if(isTv) Resolution.RES_1080P else Resolution.RES_720P

	val discoveryEnabledKey get() = resources.getString(R.string.preferences_discovery_enabled_key)
	var discoveryEnabled
		get() = sharedPreferences.getBoolean(discoveryEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(discoveryEnabledKey, value).apply() }

	val onScreenControlsEnabledKey get() = resources.getString(R.string.preferences_on_screen_controls_enabled_key)
	var onScreenControlsEnabled
		get() = sharedPreferences.getBoolean(onScreenControlsEnabledKey, !isTv)
		set(value) { sharedPreferences.edit().putBoolean(onScreenControlsEnabledKey, value).apply() }


	/**
	 * Whether Chiaki is offered as the home screen, which makes a TV box start into it
	 */
	val homeScreenKey get() = resources.getString(R.string.preferences_home_screen_key)
	var homeScreen
		get() = packageManager.getComponentEnabledSetting(homeActivity) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
		set(value) = packageManager.setComponentEnabledSetting(homeActivity,
			if(value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
			PackageManager.DONT_KILL_APP)

	val rumbleEnabledKey get() = resources.getString(R.string.preferences_rumble_enabled_key)
	var rumbleEnabled
		get() = sharedPreferences.getBoolean(rumbleEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(rumbleEnabledKey, value).apply() }

	val dualSenseEnabledKey get() = resources.getString(R.string.preferences_dualsense_enabled_key)
	var dualSenseEnabled
		get() = sharedPreferences.getBoolean(dualSenseEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(dualSenseEnabledKey, value).apply() }

	val controllerHeadphonesKey get() = resources.getString(R.string.preferences_controller_headphones_key)
	var controllerHeadphones
		get() = sharedPreferences.getBoolean(controllerHeadphonesKey, false)
		set(value) { sharedPreferences.edit().putBoolean(controllerHeadphonesKey, value).apply() }

	val motionEnabledKey get() = resources.getString(R.string.preferences_motion_enabled_key)
	var motionEnabled
		get() = sharedPreferences.getBoolean(motionEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(motionEnabledKey, value).apply() }

	val buttonHapticEnabledKey get() = resources.getString(R.string.preferences_button_haptic_enabled_key)
	var buttonHapticEnabled
		get() = sharedPreferences.getBoolean(buttonHapticEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(buttonHapticEnabledKey, value).apply() }

	val logVerboseKey get() = resources.getString(R.string.preferences_log_verbose_key)
	var logVerbose
		get() = sharedPreferences.getBoolean(logVerboseKey, false)
		set(value) { sharedPreferences.edit().putBoolean(logVerboseKey, value).apply() }


	val debandingEnabledKey get() = resources.getString(R.string.preferences_debanding_key)
	var debandingEnabled
		// Off by default, like the switch in the settings: the shader's extra rendering pass adds delay
		get() = sharedPreferences.getBoolean(debandingEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(debandingEnabledKey, value).apply() }

	val upscalingEnabledKey get() = resources.getString(R.string.preferences_upscaling_key)
	var upscalingEnabled
		// Off by default, like the switch in the settings: drawing through shaders adds a little delay,
		// and it only helps on screens with more pixels than the stream
		get() = sharedPreferences.getBoolean(upscalingEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(upscalingEnabledKey, value).apply() }

	val touchscreenTouchpadEnabledKey get() = "preferences_touchscreen_touchpad_enabled"
	var touchscreenTouchpadEnabled
		get() = sharedPreferences.getBoolean(touchscreenTouchpadEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(touchscreenTouchpadEnabledKey, value).apply() }

	/** Whether leaving the app keeps the stream in a small window. TV launchers handle it poorly. */
	val pictureInPictureKey get() = "stream_picture_in_picture"
	var pictureInPicture
		get() = sharedPreferences.getBoolean(pictureInPictureKey, !isTv)
		set(value) { sharedPreferences.edit().putBoolean(pictureInPictureKey, value).apply() }

	val streamStatsEnabledKey get() = "stream_stats_enabled"
	var streamStatsEnabled
		get() = sharedPreferences.getBoolean(streamStatsEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(streamStatsEnabledKey, value).apply() }

	// Last used Fit/Zoom/Stretch mode of the stream, stored as the TransformMode name
	val streamDisplayModeKey get() = "stream_display_mode"
	var streamDisplayMode: String?
		get() = sharedPreferences.getString(streamDisplayModeKey, null)
		set(value) { sharedPreferences.edit().putString(streamDisplayModeKey, value).apply() }

	val sharpnessIntensityKey get() = "preferences_sharpness_intensity"
	var sharpnessIntensity: Float
		get() = sharedPreferences.getInt(sharpnessIntensityKey, 0).toFloat() / 100f
		set(value) { sharedPreferences.edit().putInt(sharpnessIntensityKey, (value * 100f).toInt()).apply() }


	val resolutionKey get() = resources.getString(R.string.preferences_resolution_key)
	var resolution
		get() = sharedPreferences.getString(resolutionKey, resolutionDefault.value)?.let { value ->
			Resolution.values().firstOrNull { it.value == value }
		} ?: resolutionDefault
		set(value) { sharedPreferences.edit().putString(resolutionKey, value.value).apply() }

	val fpsKey get() = resources.getString(R.string.preferences_fps_key)
	var fps
		get() = sharedPreferences.getString(fpsKey, fpsDefault.value)?.let { value ->
			FPS.values().firstOrNull { it.value == value }
		}  ?: fpsDefault
		set(value) { sharedPreferences.edit().putString(fpsKey, value.value).apply() }

	fun validateBitrate(bitrate: Int) = max(2000, min(100000, bitrate))
	val bitrateKey get() = resources.getString(R.string.preferences_bitrate_key)
	var bitrate
		get() = sharedPreferences.getInt(bitrateKey, 0).let { if(it == 0) null else validateBitrate(it) }
		set(value) { sharedPreferences.edit().putInt(bitrateKey, if(value != null) validateBitrate(value) else 0).apply() }
	val bitrateAuto get() = videoProfileDefaultBitrate.bitrate
	private val bitrateAutoSubject by lazy { BehaviorSubject.createDefault(bitrateAuto) }
	val bitrateAutoObservable: Observable<Int> get() = bitrateAutoSubject

	val codecKey get() = resources.getString(R.string.preferences_codec_key)
	var codec
		get() = sharedPreferences.getString(codecKey, codecDefault.value)?.let { value ->
			Codec.values().firstOrNull { it.value == value }
		}  ?: codecDefault
		set(value) { sharedPreferences.edit().putString(codecKey, value.value).apply() }

	private val videoProfileDefaultBitrate get() = ConnectVideoProfile.preset(resolution.preset, fps.preset, codec.codec)
	val videoProfile get() = videoProfile(quality, true)

	val qualityKey get() = "stream_quality"
	var quality: Quality
		get() = Quality.fromValue(sharedPreferences.getString(qualityKey, null))
			// The details someone set before there were presets stay as they are
			?: if(listOf(resolutionKey, fpsKey, bitrateKey).any { sharedPreferences.contains(it) }) Quality.CUSTOM else Quality.BALANCED
		set(value) { sharedPreferences.edit().putString(qualityKey, value.value).apply() }

	/** For playing away from home, where the network is usually slower */
	val qualityAwayKey get() = "stream_quality_away"
	var qualityAway: Quality
		get() = Quality.fromValue(sharedPreferences.getString(qualityAwayKey, null))?.takeIf { it != Quality.CUSTOM } ?: qualityAwayDefault
		set(value) { sharedPreferences.edit().putString(qualityAwayKey, value.value).apply() }

	private fun consoleQualityKey(mac: MacAddress) = "console/${mac.value}/quality"

	/** The quality of a console, or null for the one in the settings */
	fun consoleQuality(mac: MacAddress) = Quality.fromValue(sharedPreferences.getString(consoleQualityKey(mac), null))

	fun setConsoleQuality(mac: MacAddress, quality: Quality?) = sharedPreferences.edit().also {
		if(quality == null)
			it.remove(consoleQualityKey(mac))
		else
			it.putString(consoleQualityKey(mac), quality.value)
	}.apply()

	/** @param away whether connecting from away from home, through PSN */
	fun videoProfile(mac: MacAddress?, ps5: Boolean, away: Boolean) =
		videoProfile(if(away) qualityAway else mac?.let { consoleQuality(it) } ?: quality, ps5)

	fun videoProfile(quality: Quality, ps5: Boolean): ConnectVideoProfile = when(quality)
	{
		Quality.SMOOTH -> ConnectVideoProfile.preset(Resolution.RES_720P.preset, FPS.FPS_60.preset, codec.codec)
			.copy(bitrate = SMOOTH_BITRATE)
		Quality.BALANCED -> ConnectVideoProfile.preset(resolutionDefault.preset, FPS.FPS_60.preset, codec.codec)
		Quality.SHARP -> ConnectVideoProfile.preset(Resolution.RES_1080P.preset, FPS.FPS_60.preset, codec.codec)
			.copy(bitrate = if(ps5) SHARP_BITRATE_PS5 else SHARP_BITRATE_PS4)
		Quality.CUSTOM -> videoProfileDefaultBitrate.let {
			val bitrate = bitrate
			if(bitrate == null)
				it
			else
				it.copy(bitrate = bitrate)
		}
	}
}