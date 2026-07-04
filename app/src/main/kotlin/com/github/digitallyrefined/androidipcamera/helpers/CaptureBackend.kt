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
    fun getTorch(): Boolean
    fun setTorch(on: Boolean)
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
