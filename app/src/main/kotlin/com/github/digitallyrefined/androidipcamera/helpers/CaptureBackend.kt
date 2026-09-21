package com.github.digitallyrefined.androidipcamera.helpers

/**
 * A live camera backend feeding the H.264 encoder, plus a full-resolution still and basic controls.
 *  - CameraXCapture  — the global/modern default (works across phones; ImageAnalysis + ImageCapture).
 *  - Camera1Capture  — legacy fallback reaching 1920×1080 on old HALs where CameraX caps at 720p.
 * The service auto-picks one by hardware level (or a manual override) and talks to it through here.
 */
interface CaptureBackend {
    val width: Int
    val height: Int
    val ready: Boolean get() = true        // CameraX overrides (async bind); Camera1 is ready after start()
    /** Full-resolution JPEG; concurrent with the live stream where the hardware allows. */
    fun captureStill(onJpeg: (ByteArray?) -> Unit)
    /**
     * True only when THIS camera can drive the flash unit. Auxiliary lenses (ultra-wide, depth)
     * report no flash unit on many multi-lens phones — the caller must then route the torch
     * through a flash-capable camera instead of assuming setTorch() did anything.
     */
    val hasFlashUnit: Boolean get() = true
    fun getTorch(): Boolean
    fun setTorch(on: Boolean)
    /**
     * Enable/disable night mode (low light boost). When enabled the sensor accumulates more light at
     * the cost of a lower frame rate and more motion blur. Best-effort: backends that report no
     * support simply stay off.
     */
    fun setNightMode(on: Boolean)
    /** True when the active camera can actually drive night mode / low light boost. */
    fun isNightModeSupported(): Boolean = false
    /**
     * Opt-in alternative to [setNightMode]: use the OEM CameraX NIGHT extension (the vendor's tuned
     * night algorithm) instead of the raw low-light-boost control. Mutually exclusive with
     * [setNightMode] — enabling one turns the other off. No-op on backends without extension support.
     */
    fun setNightExtension(on: Boolean) {}
    /** True when the active camera exposes an OEM NIGHT extension that can feed the streamed
     *  ImageAnalysis frames (not just the on-screen preview / stills). */
    fun isNightExtensionSupported(): Boolean = false
    fun setExposure(ev: Int)
    fun setZoom(ratio: Float)
    fun triggerAutoFocus()
    /**
     * Manual focus. [distance] in 0f..1f is a normalized fixed focus distance
     * (0f = far/infinity, 1f = nearest). A negative value returns the camera to
     * continuous autofocus. Backends apply this on a best-effort basis.
     */
    fun setManualFocus(distance: Float)
    fun stop()
}
