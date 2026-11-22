package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/**
 * Night mode is a still-photo feature: the camera takes a long / multi-frame exposure when a photo
 * is requested. Android exposes it through two independent public APIs, so support has to be checked
 * on both:
 *
 *  - Camera Extensions (API 31+): [CameraExtensionCharacteristics.EXTENSION_NIGHT]. This is the same
 *    OEM "Night Sight"-style pipeline the system camera app uses, and is the best path.
 *  - Camera2 scene modes / low-light AE: [CameraMetadata.CONTROL_SCENE_MODE_NIGHT] and, on newer
 *    devices, [CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY]. These work
 *    on devices whose OEM only implements a basic night scene mode.
 *
 * Video capture cannot use either mode, which is why [StreamingService] switches to a still-capture
 * loop and feeds the resulting photos into the normal MJPEG/H.264 stream.
 */
object NightMode {
    const val PREF = "night_mode"
    private const val PREF_EXPOSURE_PREFIX = "night_exposure_"
    private const val PREF_ISO_PREFIX = "night_iso_"

    /** How a photo should be requested from this camera. */
    enum class CaptureMode { MANUAL, EXTENSION, SCENE, LOW_LIGHT_BOOST, NONE }

    /**
     * Manual sensor exposure limits for night stills. Exposure is in milliseconds because that is
     * what the web slider shows; Camera2 expects nanoseconds. Manual exposure is the only reliable
     * way to brighten a scene when the OEM night mode is absent or is ignored by the HAL.
     */
    data class ManualExposure(
        val minExposureMs: Long,
        val maxExposureMs: Long,
        val minIso: Int,
        val maxIso: Int,
    ) {
        /** Start at the brightest exposure; the slider lets the user trade brightness for motion. */
        val defaultExposureMs: Long
            get() = maxExposureMs.coerceAtLeast(minExposureMs)

        /** High enough to expose a dark room, but not necessarily the noisiest setting. */
        val defaultIso: Int
            get() = 3200.coerceIn(minIso, maxIso)
    }

    fun isEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit { putBoolean(PREF, enabled) }
    }

    /** True when the selected camera can take a night-mode still. */
    fun supports(context: Context, cameraId: String?): Boolean = try {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        captureMode(cm, cameraId) != CaptureMode.NONE
    } catch (_: Exception) {
        false
    }

    fun captureMode(cm: CameraManager, cameraId: String?): CaptureMode {
        val logicalId = resolveLogicalId(cm, cameraId)
        val characteristics = characteristics(cm, cameraId, logicalId)
        val logical = try {
            logicalId?.let { cm.getCameraCharacteristics(it) }
        } catch (_: Exception) {
            null
        }
        // Manual long exposure is the most predictable night path, so prefer it when the device
        // exposes the manual-sensor capability. OEM night extensions are used when it cannot.
        if (manualExposure(characteristics) != null || manualExposure(logical) != null) {
            return CaptureMode.MANUAL
        }
        if (hasNightExtension(cm, logicalId)) return CaptureMode.EXTENSION
        if (sceneModeSupported(characteristics) || sceneModeSupported(logical)) return CaptureMode.SCENE
        if (lowLightBoostSupported(characteristics) || lowLightBoostSupported(logical)) return CaptureMode.LOW_LIGHT_BOOST
        return CaptureMode.NONE
    }

    /**
     * Manual exposure limits, or null when the camera does not advertise the manual-sensor
     * capability or one of the exposure/ISO ranges. [NightModeCapture] uses these to drive
     * `SENSOR_EXPOSURE_TIME` and `SENSOR_SENSITIVITY` with auto-exposure off.
     */
    fun manualExposure(context: Context, cameraId: String?): ManualExposure? = try {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        manualExposure(cm, cameraId)
    } catch (_: Exception) {
        null
    }

    fun manualExposure(cm: CameraManager, cameraId: String?): ManualExposure? {
        val logicalId = resolveLogicalId(cm, cameraId)
        val physical = characteristics(cm, cameraId, logicalId)
        val logical = try {
            logicalId?.let { cm.getCameraCharacteristics(it) }
        } catch (_: Exception) {
            null
        }
        return manualExposure(physical) ?: manualExposure(logical)
    }

    fun manualExposure(characteristics: CameraCharacteristics?): ManualExposure? = try {
        val ch = characteristics ?: return null
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        if (caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) != true) {
            return null
        }
        val exposure = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: return null
        val iso = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return null
        val minMs = (exposure.lower / 1_000_000L).coerceAtLeast(1L)
        // Some sensors report an enormous maximum. Night stills gain little beyond a couple of
        // seconds, and a multi-minute slider is unusable, so cap the exposed range at 2 s.
        val maxMs = minOf(exposure.upper / 1_000_000L, 2_000L)
        if (maxMs < minMs || iso.upper < iso.lower || iso.lower <= 0) return null
        ManualExposure(minMs, maxMs, iso.lower, iso.upper)
    } catch (_: Exception) {
        null
    }

    /** Stored night exposure in ms, clamped to the device's range. */
    fun currentExposureMs(context: Context, cameraId: String?, manual: ManualExposure): Long {
        val stored = readLongPref(context, PREF_EXPOSURE_PREFIX, cameraId)
        return (stored ?: manual.defaultExposureMs).coerceIn(manual.minExposureMs, manual.maxExposureMs)
    }

    /** Stored night ISO, clamped to the device's range. */
    fun currentIso(context: Context, cameraId: String?, manual: ManualExposure): Int {
        val stored = readLongPref(context, PREF_ISO_PREFIX, cameraId)?.toInt()
        return (stored ?: manual.defaultIso).coerceIn(manual.minIso, manual.maxIso)
    }

    fun setExposureMs(context: Context, cameraId: String?, value: Long) {
        writePerCameraPref(context, PREF_EXPOSURE_PREFIX, cameraId, value.toString())
    }

    fun setIso(context: Context, cameraId: String?, value: Int) {
        writePerCameraPref(context, PREF_ISO_PREFIX, cameraId, value.toString())
    }

    private fun readLongPref(context: Context, prefix: String, cameraId: String?): Long? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val keys = buildList {
            addAll(perCameraKeys(prefix, cameraId))
            addAll(perCameraKeys(prefix, prefs.getString("camera_id", null)))
        }.distinct()
        for (key in keys) {
            if (!prefs.contains(key)) continue
            prefs.getString(key, null)?.toLongOrNull()?.let { return it }
        }
        return null
    }

    private fun writePerCameraPref(context: Context, prefix: String, cameraId: String?, value: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit {
            perCameraKeys(prefix, cameraId).forEach { putString(it, value) }
        }
    }

    private fun perCameraKeys(prefix: String, cameraId: String?): List<String> {
        if (cameraId.isNullOrBlank()) return emptyList()
        val physical = cameraId.substringAfter(':', "")
        return buildList {
            add(prefix + cameraId)
            if (physical.isNotBlank() && physical != cameraId) add(prefix + physical)
        }
    }

    /**
     * Pure capability test, kept separate from Camera2 lookups so the decision can be unit tested.
     * [sceneModes] / [aeModes] are the arrays from [CameraCharacteristics]; [nightExtension] comes
     * from [CameraExtensionCharacteristics.supportedExtensions].
     */
    fun supports(
        sceneModes: IntArray?,
        aeModes: IntArray?,
        nightExtension: Boolean,
        lowLightBoost: Boolean,
    ): Boolean {
        if (nightExtension) return true
        if (lowLightBoost) return true
        if (aeModes?.contains(CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY) == true) {
            return true
        }
        return sceneModes?.any {
            it == CameraMetadata.CONTROL_SCENE_MODE_NIGHT ||
                it == CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT
        } == true
    }

    fun sceneModeSupported(characteristics: CameraCharacteristics?): Boolean {
        val modes = try {
            characteristics?.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
        } catch (_: Exception) {
            null
        }
        return modes?.any {
            it == CameraMetadata.CONTROL_SCENE_MODE_NIGHT ||
                it == CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT
        } == true
    }

    fun lowLightBoostSupported(characteristics: CameraCharacteristics?): Boolean = try {
        characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.contains(
            CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY
        ) == true
    } catch (_: Exception) {
        false
    }

    /** True when the camera has the OEM night Camera Extension (API 31+). */
    fun hasNightExtension(cm: CameraManager, cameraId: String?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val ids = if (cameraId != null && cm.cameraIdList.contains(cameraId)) {
            listOf(cameraId)
        } else {
            cm.cameraIdList.toList()
        }
        return ids.any { id ->
            try {
                cm.getCameraExtensionCharacteristics(id)
                    .supportedExtensions
                    .contains(CameraExtensionCharacteristics.EXTENSION_NIGHT)
            } catch (_: Exception) {
                false
            }
        }
    }

    /** The logical camera id for a stored id/token such as "0", "1:3", "back" or "front". */
    fun resolveLogicalId(cm: CameraManager, cameraId: String?): String? {
        if (cameraId.isNullOrBlank()) return null
        if (!cameraId.contains(':')) {
            val facing = when (cameraId.lowercase()) {
                "back" -> CameraCharacteristics.LENS_FACING_BACK
                "front" -> CameraCharacteristics.LENS_FACING_FRONT
                else -> null
            }
            if (facing != null) {
                return try {
                    cm.cameraIdList.firstOrNull { id ->
                        cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }
        val token = cameraId.substringBefore(':')
        if (cm.cameraIdList.contains(token)) return token
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).physicalCameraIds.contains(token)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** The most specific characteristics available for a camera id/token. */
    fun characteristics(
        cm: CameraManager,
        cameraId: String?,
        logicalId: String? = resolveLogicalId(cm, cameraId),
    ): CameraCharacteristics? {
        val candidates = buildList {
            cameraId?.substringAfter(':', "")?.takeIf { it.isNotBlank() }?.let { add(it) }
            cameraId?.substringBefore(':')?.let { if (it.isNotBlank()) add(it) }
            logicalId?.let { add(it) }
        }
        for (id in candidates.distinct()) {
            try {
                if (cm.cameraIdList.contains(id)) return cm.getCameraCharacteristics(id)
            } catch (_: Exception) {
                // Try the next id.
            }
        }
        return null
    }
}
