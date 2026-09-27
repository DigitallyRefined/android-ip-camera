package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.Range

/**
 * Low-light ("accumulate the light") tuning, shared by both capture backends and every pixel path.
 *
 * A camera image is black in a dark room for one reason: not enough photons reached the sensor
 * during the exposure. Post-processing cannot fix that — it can only remap values that were never
 * collected. So the settings here are split into the two halves that actually matter, and the
 * existing "night mode" branches in this project only ever touched the second half:
 *
 *  1. COLLECT (capture side, [profile] -> `CaptureRequestOptions` / `Camera.Parameters`)
 *     - [Profile.targetFps]: the real lever. Auto-exposure may only hold a shutter as long as the
 *       frame period the AE fps range allows, so a 1/30s range caps the exposure at 33ms no matter
 *       what the sensitivity is. Dropping the ceiling is what actually buys photons: at 1/30s ->
 *       1/4s the sensor collects ~4 stops more light.
 *     - [Profile.useBoost]: `CONTROL_POST_RAW_SENSITIVITY_BOOST` (API 28+) multiplies sensitivity
 *       past the ISO range's ceiling. Worth up to ~1-2 stops, but only on HALs that expose a range
 *       above 100 — [Caps.boostUpper] reports the real ceiling, and the UI caps the level to what
 *       the hardware can reach instead of pretending.
 *     - [Profile.sceneModeNight]: `CONTROL_SCENE_MODE_NIGHT`. Cheap and harmless when the HAL
 *       honours it, ignored when it does not — deliberately kept as a bonus, never relied upon.
 *
 *  2. DEVELOP (pixel side, [LumaTone] -> luma LUT / GL shader)
 *     Even a 500ms exposure of a dim room lands most pixels inside the bottom ~10% of the luma
 *     range, so a straight encode is still near-black. [LumaTone] lifts that shadow floor, adds
 *     gain and applies a gamma curve, which is what turns "noisy dark rectangle" into visible
 *     outlines. It is applied on the phone, on the actual stream — not in the browser, where it
 *     would improve nothing for a recording or a second viewer.
 *
 * Frame rate is the price: every profile level is also an fps ceiling, so a low-light stream is
 * automatically a low-bandwidth one (fewer frames per second over Wi-Fi and a smaller bitrate),
 * which is the right trade for a surveillance camera in a dark room.
 */
object LowLight {
    private const val TAG = "LowLight"

    /** Highest level offered by the UI. Levels are 1..[MAX_LEVEL]; 0 means "off". */
    const val MAX_LEVEL = 4

    /**
     * One selectable low-light level. [targetFps] is the AE frame-rate ceiling (and therefore the
     * longest exposure the HAL will hold); [tone] develops the resulting dark image.
     */
    data class Profile(
        val level: Int,
        val label: String,
        /** AE frame-rate ceiling. The longest possible exposure is ~1/targetFps seconds. */
        val targetFps: Int,
        /** Ask for max `CONTROL_POST_RAW_SENSITIVITY_BOOST` (ignored when the HAL has no range >100). */
        val useBoost: Boolean,
        /** Request the vendor NIGHT scene mode when advertised (best-effort). */
        val sceneModeNight: Boolean,
        /** Shadow development applied to the luma plane. */
        val tone: LumaTone,
    ) {
        /** Longest shutter this level unlocks, in ms, given the frame-rate ceiling. */
        val maxShutterMs: Int get() = (1000f / targetFps).toInt().coerceAtLeast(1)
    }

    /**
     * Luma-only tonal mapping: subtract [blackLevel], scale by [gain], then raise to [gamma].
     *
     * Applied to luma only (not per RGB channel) so colour and white balance survive untouched —
     * a per-channel curve on a noisy dark frame shifts hue frame to frame. `gamma < 1` lifts
     * midtones, which is the whole point here; `gain` recovers what the subtract took away and
     * more. [enabled] false means "pass through", so the LUT degenerates to the identity and costs
     * one table lookup per pixel.
     */
    data class LumaTone(
        val enabled: Boolean,
        val blackLevel: Int = 0,
        val gain: Float = 1f,
        val gamma: Float = 1f,
    ) {
        companion object {
            /** No development at all — the identity curve, so callers can skip the pass entirely. */
            val OFF = LumaTone(enabled = false)
        }

        /** True when the curve would leave every input value unchanged. */
        val isIdentity: Boolean
            get() = !enabled || (blackLevel == 0 && gain == 1f && gamma == 1f)

        /**
         * Build the 256-entry luma LUT. Index is the raw 8-bit luma byte; the value is the mapped
         * luma. Callers apply it in place to a dense 8-bit luma plane (Y, or the Y of an NV21/NV12).
         *
         * This is the reference definition of the curve; the GL shader in [CameraGlPipe] mirrors it
         * step for step and must be changed together with it. The order matters:
         *
         *  1. subtract [blackLevel] (0..255 luma units) and scale by [gain],
         *  2. normalise to 0..1,
         *  3. raise to [gamma] — which is < 1 for every level, and that is what *lifts*: out of
         *     0.08, gamma 0.5 gives 0.28. Using 1/gamma here inverts the whole point of the control
         *     and, combined with skipping step 2, drives the result past 255 and clips the frame
         *     to solid white for any input above the black level.
         */
        fun toLut(): ByteArray {
            val lut = ByteArray(256)
            if (isIdentity) {
                for (i in 0..255) lut[i] = i.toByte()
                return lut
            }
            val g = gamma.toDouble().coerceIn(0.05, 20.0)
            val lutFloat = FloatArray(256)
            for (i in 0..255) {
                val x = (i - blackLevel).toFloat() * gain / 255f
                if (x <= 0f) {
                    lutFloat[i] = 0f
                    continue
                }
                val v = Math.pow(x.coerceAtMost(1f).toDouble(), g).toFloat() * 255f
                lutFloat[i] = if (v.isNaN()) 0f else v.coerceIn(0f, 255f)
            }
            // Round once so a grey ramp stays monotone and free of dithering steps.
            for (i in 0..255) {
                val v = (lutFloat[i] + 0.5f).toInt()
                lut[i] = (if (v < 0) 0 else if (v > 255) 255 else v).toByte()
            }
            return lut
        }
    }

    /**
     * The five levels. Exposure time roughly doubles per level, so each step is worth ~1 stop:
     * L1 1/15s (~67ms) → L4 1/1s (~1000ms) is about 4 stops of light over the 30fps default.
     * `gain`/`gamma` grow alongside so the extra light is actually visible rather than clipped low.
     */
    val PROFILES: List<Profile> = listOf(
        Profile(
            level = 0, label = "Off", targetFps = 30,
            useBoost = false, sceneModeNight = false, tone = LumaTone.OFF,
        ),
        Profile(
            level = 1, label = "Low", targetFps = 15,
            useBoost = true, sceneModeNight = true,
            // Barely touches the image: 67ms is enough to stop motion smearing, and the small
            // lift just cleans up the deepest shadows.
            tone = LumaTone(enabled = true, blackLevel = 2, gain = 1.35f, gamma = 0.95f),
        ),
        Profile(
            level = 2, label = "Medium", targetFps = 8,
            useBoost = true, sceneModeNight = true,
            tone = LumaTone(enabled = true, blackLevel = 6, gain = 1.9f, gamma = 0.80f),
        ),
        Profile(
            level = 3, label = "High", targetFps = 4,
            useBoost = true, sceneModeNight = true,
            tone = LumaTone(enabled = true, blackLevel = 10, gain = 2.6f, gamma = 0.65f),
        ),
        Profile(
            level = 4, label = "Maximum", targetFps = 1,
            useBoost = true, sceneModeNight = true,
            // 1s exposures: still subjects hold, anything moving is a smear. The strong lift and
            // gamma are what makes a room lit only by a standby LED legible as outlines.
            tone = LumaTone(enabled = true, blackLevel = 14, gain = 3.4f, gamma = 0.50f),
        ),
    )

    /** [level] clamped into a valid index of [PROFILES]. */
    fun profile(level: Int): Profile = PROFILES[level.coerceIn(0, MAX_LEVEL)]

    /**
     * What this camera can actually reach, so the UI never offers a level the hardware cannot
     * deliver. Read once per camera from `CameraCharacteristics`.
     */
    data class Caps(
        /** Longest exposure the sensor supports, in ms. */
        val maxExposureMs: Int,
        /** Highest ISO the sensor supports. */
        val maxIso: Int,
        /** `CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE.upper`; 100 means "no boost available". */
        val boostUpper: Int,
        /** Lowest advertised AE frame-rate upper bound, in fps — the slowest the HAL will go. */
        val minFpsUpper: Int,
        /** True when the HAL advertises `REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR`. */
        val manualSensor: Boolean,
    ) {
        companion object {
            /** Conservative fallback for when the characteristics cannot be read. */
            val UNKNOWN = Caps(maxExposureMs = 33, maxIso = 100, boostUpper = 100, minFpsUpper = 30, manualSensor = false)
        }

        val hasBoost: Boolean get() = boostUpper > 100

        /**
         * Highest level this camera can genuinely deliver: a level is capped by the frame-rate
         * ceiling (needs the HAL to go that slow) and by the exposure range (a shutter longer than
         * the sensor supports is not a real setting).
         */
        fun maxUsableLevel(): Int = PROFILES
            .filterIndexed { index, p -> index > 0 && p.targetFps >= minFpsUpper && p.maxShutterMs <= maxExposureMs }
            .maxOfOrNull { it.level }
            ?: 0
    }

    /**
     * Read [Caps] for a camera id, or the first camera of the requested facing when [cameraId] is
     * null. Best-effort: any failure returns [Caps.UNKNOWN] so callers can degrade rather than fail.
     */
    fun probe(context: Context, cameraId: String?, front: Boolean): Caps = try {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        // The app's camera ids are "<logical>[:<physical>]", and only the logical part is a real
        // Camera2 id, so match on that rather than letting a physical id fall through to the
        // first lens of the requested facing — which would report the wrong camera's limits.
        val logical = cameraId?.substringBefore(':')
        val id = logical?.takeIf { it in cm.cameraIdList }
            ?: cm.cameraIdList.firstOrNull {
                runCatching { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }.getOrDefault(false)
            }
            ?: cm.cameraIdList.firstOrNull()
        if (id == null) Caps.UNKNOWN else capsOf(cm.getCameraCharacteristics(id))
    } catch (e: Exception) {
        Log.w(TAG, "probe: ${e.message}")
        Caps.UNKNOWN
    }

    /** [Caps] from already-fetched characteristics (also used from the CameraX camera info). */
    fun capsOf(c: CameraCharacteristics): Caps = Caps(
        maxExposureMs = ((c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.upper ?: 33_000_000L) / 1_000_000L)
            .toInt().coerceAtLeast(1),
        maxIso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.upper ?: 100,
        boostUpper = c.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)?.upper ?: 100,
        minFpsUpper = slowFpsUpper(c) ?: 30,
        manualSensor = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true,
    )

    /**
     * Lowest AE frame-rate upper bound this camera advertises, in real fps.
     *
     * Unit note: `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` reports frames in **thousandths** of a
     * frame per second, while `CONTROL_AE_TARGET_FPS_RANGE` on a request is in whole fps. The two
     * are the same value in different units, so the characteristics value is divided by 1000 here
     * rather than at each use site — a range like [0, 7500] has to reach the HAL as [0, 7], and
     * passing 7500 straight through is not a valid request at all.
     */
    fun slowFpsUpper(c: CameraCharacteristics?): Int? = try {
        c?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.minOfOrNull { it.upper / 1000 }
            ?.coerceAtLeast(1)
    } catch (e: Exception) {
        Log.w(TAG, "slowFpsUpper: ${e.message}")
        null
    }

    /** An AE frame-rate range in whole fps. Plain type so the selection is unit-testable without
     *  Android, which is only involved in reading the characteristics and building the request. */
    data class FpsRange(val lower: Int, val upper: Int)

    /**
     * Pick the AE range to request for a low-light level, from ranges already in whole fps.
     *
     * The slowest range always wins, because the range's *upper* bound is the frame-rate ceiling and
     * that ceiling is what caps the exposure. A range like [0, 30] spans any target yet permits 30
     * fps, so it would leave the HAL free to ignore the request entirely and the level would do
     * nothing — while [7, 7], which cannot even reach 8 fps, is the one that actually holds a long
     * shutter. Deliberately accepting fewer frames is the entire trade this feature makes, so being
     * able to reach the requested rate is not a requirement.
     *
     * Ties on the ceiling go to the lower bound, since a wider range lets auto-exposure still use a
     * short shutter when the scene is bright instead of being pinned to the slow rate.
     *
     * The request is always one the camera itself advertised, so it cannot be unsupported. What the
     * caller must not do is *promise* a rate the camera cannot reach, which is what
     * [Caps.maxUsableLevel] decides.
     */
    fun selectFpsRange(available: List<FpsRange>, targetFps: Int): FpsRange? {
        if (available.isEmpty()) return null
        // An exact ceiling is the ideal request, so prefer it over a slower one.
        available.firstOrNull { it.upper == targetFps }?.let { return it }
        return available.minWithOrNull(compareBy({ it.upper }, { it.lower }))
    }

    /**
     * The advertised AE fps range to use for a low-light level, in whole fps, ready to be set on
     * `CONTROL_AE_TARGET_FPS_RANGE`.
     */
    fun fpsRangeFor(caps: CameraCharacteristics?, targetFps: Int): Range<Int>? = try {
        val advertised = caps?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            // Convert thousandths -> fps here, at the boundary, so nothing downstream has to know
            // about the unit mismatch.
            ?.map { FpsRange(it.lower / 1000, it.upper / 1000) }
            ?: return null
        selectFpsRange(advertised, targetFps)?.let { Range(it.lower, it.upper) }
    } catch (e: Exception) {
        Log.w(TAG, "fpsRangeFor: ${e.message}")
        null
    }

    /**
     * Apply [lut] to [w]×[h] luma bytes of [plane] starting at [offset], in place.
     *
     * Written as an index-based loop over a byte array rather than a per-pixel abstraction: this
     * runs on every frame of every stream, and a 1080p frame is ~2M lookups, so the difference
     * between a tight loop and a buffer walk is worth keeping tight.
     */
    fun applyLut(plane: ByteArray, offset: Int, w: Int, h: Int, lut: ByteArray) {
        applyLut(plane, offset, w * h, lut)
    }

    /**
     * Apply [lut] to a flat run of [count] luma bytes of [plane] starting at [offset], in place.
     * Used for a whole plane and for a single row buffer alike; the bounds check makes it safe to
     * call with a row that extends past the valid pixel count.
     */
    fun applyLut(plane: ByteArray, offset: Int, count: Int, lut: ByteArray) {
        if (count <= 0) return
        val end = offset + count
        if (offset < 0 || end > plane.size) return
        for (i in offset until end) {
            plane[i] = lut[plane[i].toInt() and 0xFF]
        }
    }
}
