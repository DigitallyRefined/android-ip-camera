package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Common interface for a still-only night session. Two implementations exist: CameraX for OEM
 * extension/scene modes, and a direct Camera2 session for manual long exposure (CameraX applies
 * capture-request options to its metering repeating request, which some HALs reject for long
 * exposures).
 */
interface NightStillCapture {
    val isReady: Boolean
    fun start(onReady: (Boolean) -> Unit)
    fun capture(onJpeg: (ByteArray?) -> Unit)
    fun stop()
}

/**
 * A still-only camera session used while the web UI's Night mode checkbox is on.
 *
 * Video capture has no night mode: the camera pipelines that brighten dark scenes need to merge
 * several long exposures into one photo. This class therefore binds an [ImageCapture] use case (and
 * no analysis/preview use cases), asks the OEM Camera Extension for [ExtensionMode.NIGHT] when the
 * device has it, and falls back to the Camera2 night scene mode or the low-light AE boost. The
 * service repeatedly calls [capture] and pushes the JPEGs into the normal streaming encoders.
 */
class NightModeCapture(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val front: Boolean,
    private val cameraId: String?,
    private val desired: Size,
    private val initialExposureMs: Long? = null,
    private val initialIso: Int? = null,
    private val onLog: (String) -> Unit = {},
) : NightStillCapture {
    private val main = ContextCompat.getMainExecutor(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    // Shared across NightModeCapture instances: CameraX may deliver a still-capture result after
    // the session is stopped, and shutting this executor down then crashes the app with a
    // RejectedExecutionException from TakePictureRequest.onResult().
    private val captureExec = CAPTURE_EXECUTOR

    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    @Volatile private var released = false
    @Volatile override var isReady = false
        private set
    @Volatile private var manualExposureMs = 0L
    @Volatile private var manualIso = 0

    /** Binds the still session. [onReady] is called on the main thread once it can capture. */
    override fun start(onReady: (Boolean) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (released) return@addListener onReady(false)
            try {
                val p = future.get()
                provider = p
                val selector = cameraSelector()
                val mode = NightMode.captureMode(cameraManager(), cameraId)
                if (mode == NightMode.CaptureMode.EXTENSION) {
                    val extensionsFuture = ExtensionsManager.getInstanceAsync(context, p)
                    extensionsFuture.addListener({
                        if (released) return@addListener onReady(false)
                        val extensions = try {
                            extensionsFuture.get()
                        } catch (e: Exception) {
                            Log.w(TAG, "ExtensionsManager failed: ${e.message}")
                            null
                        }
                        if (extensions != null && extensions.isExtensionAvailable(selector, ExtensionMode.NIGHT)) {
                            try {
                                val nightSelector = extensions.getExtensionEnabledCameraSelector(selector, ExtensionMode.NIGHT)
                                bind(p, nightSelector, NightMode.CaptureMode.EXTENSION, onReady)
                                return@addListener
                            } catch (e: Exception) {
                                Log.w(TAG, "Night extension selector failed: ${e.message}")
                            }
                        }
                        bindFallback(p, selector, onReady)
                    }, main)
                } else {
                    bindWithMode(p, selector, mode, onReady)
                }
            } catch (e: Exception) {
                Log.e(TAG, "start: ${e.message}")
                onReady(false)
            }
        }, main)
    }

    /*
     * Requests one still. [onJpeg] is called exactly once, possibly with null.
     */
    override fun capture(onJpeg: (ByteArray?) -> Unit) {
        val ic = imageCapture
        if (ic == null || released || !isReady) {
            onJpeg(null)
            return
        }
        val done = AtomicBoolean(false)
        fun finish(result: ByteArray?) {
            if (!done.compareAndSet(false, true)) return
            onJpeg(result)
        }
        mainHandler.postDelayed({
            Log.w(TAG, "capture timed out")
            finish(null)
        }, CAPTURE_TIMEOUT_MS)
        try {
            ic.takePicture(captureExec, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bytes = try {
                        val buffer = image.planes[0].buffer
                        ByteArray(buffer.remaining()).also { buffer.get(it) }
                    } catch (e: Exception) {
                        Log.e(TAG, "read jpeg: ${e.message}")
                        null
                    } finally {
                        image.close()
                    }
                    finish(bytes)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.w(TAG, "capture failed: ${exception.message}")
                    finish(null)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "takePicture: ${e.message}")
            finish(null)
        }
    }

    override fun stop() {
        released = true
        isReady = false
        imageCapture = null
        try { mainHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
        try { provider?.unbindAll() } catch (_: Exception) {}
        provider = null
    }

    private fun cameraManager(): CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    @AndroidXOptIn(ExperimentalCamera2Interop::class)
    private fun cameraSelector(): CameraSelector {
        val logicalId = NightMode.resolveLogicalId(cameraManager(), cameraId)
        return logicalId?.let { id ->
            CameraSelector.Builder()
                .addCameraFilter { infos ->
                    infos.filter { Camera2CameraInfo.from(it).cameraId == id }
                }
                .build()
        } ?: if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    }

    private fun bindFallback(
        p: ProcessCameraProvider,
        selector: CameraSelector,
        onReady: (Boolean) -> Unit,
    ) {
        val cm = cameraManager()
        val logicalId = NightMode.resolveLogicalId(cm, cameraId)
        val ch = NightMode.characteristics(cm, cameraId, logicalId)
        val logicalCh = try {
            logicalId?.let { cm.getCameraCharacteristics(it) }
        } catch (_: Exception) {
            null
        }
        val fallback = when {
            NightMode.manualExposure(cm, cameraId) != null -> NightMode.CaptureMode.MANUAL
            NightMode.sceneModeSupported(ch) || NightMode.sceneModeSupported(logicalCh) ->
                NightMode.CaptureMode.SCENE
            NightMode.lowLightBoostSupported(ch) || NightMode.lowLightBoostSupported(logicalCh) ->
                NightMode.CaptureMode.LOW_LIGHT_BOOST
            else -> NightMode.CaptureMode.NONE
        }
        bindWithMode(p, selector, fallback, onReady)
    }

    private fun bindWithMode(
        p: ProcessCameraProvider,
        selector: CameraSelector,
        mode: NightMode.CaptureMode,
        onReady: (Boolean) -> Unit,
    ) {
        if (mode == NightMode.CaptureMode.NONE) {
            onLog("Night mode is not available on this camera")
            onReady(false)
            return
        }
        if (mode == NightMode.CaptureMode.MANUAL) {
            val manual = NightMode.manualExposure(cameraManager(), cameraId)
            if (manual == null) {
                bindFallback(p, selector, onReady)
                return
            }
            manualExposureMs = if (manualExposureMs > 0) {
                manualExposureMs.coerceIn(manual.minExposureMs, manual.maxExposureMs)
            } else {
                (initialExposureMs ?: manual.defaultExposureMs)
                    .coerceIn(manual.minExposureMs, manual.maxExposureMs)
            }
            manualIso = if (manualIso > 0) {
                manualIso.coerceIn(manual.minIso, manual.maxIso)
            } else {
                (initialIso ?: manual.defaultIso)
                    .coerceIn(manual.minIso, manual.maxIso)
            }
        }
        bind(p, selector, mode, onReady)
    }

    @Suppress("InlinedApi")
    @AndroidXOptIn(ExperimentalCamera2Interop::class)
    private fun bind(
        p: ProcessCameraProvider,
        selector: CameraSelector,
        mode: NightMode.CaptureMode,
        onReady: (Boolean) -> Unit,
    ) {
        if (released) return onReady(false)
        try {
            val builder = ImageCapture.Builder()
                // Night mode benefits from the highest-quality still pipeline rather than the
                // low-latency video snapshot path used by the regular streaming session.
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                // CameraX defaults to AUTO flash, which adds an AE pre-capture task and can
                // override manual exposure. Night mode must never fire the flash.
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .setTargetRotation(Surface.ROTATION_0)
                .setResolutionSelector(resolutionSelector())
            when (mode) {
                NightMode.CaptureMode.EXTENSION -> Unit // the extension selector enables the pipeline
                NightMode.CaptureMode.MANUAL -> {
                    // Manual long exposure: the HAL must be told auto-exposure is off, otherwise it
                    // ignores SENSOR_EXPOSURE_TIME/SENSOR_SENSITIVITY. SENSOR_FRAME_DURATION has to
                    // cover the exposure too or frame-rate limits clip it back to video speed.
                    val exposureNs = manualExposureMs * 1_000_000L
                    Camera2Interop.Extender(builder)
                        .setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_MODE,
                            CameraMetadata.CONTROL_AE_MODE_OFF,
                        )
                        .setCaptureRequestOption(
                            CaptureRequest.SENSOR_EXPOSURE_TIME,
                            exposureNs,
                        )
                        .setCaptureRequestOption(
                            CaptureRequest.SENSOR_SENSITIVITY,
                            manualIso,
                        )
                        .setCaptureRequestOption(
                            CaptureRequest.SENSOR_FRAME_DURATION,
                            frameDurationNs(exposureNs),
                        )
                        .setCaptureRequestOption(
                            CaptureRequest.FLASH_MODE,
                            CameraMetadata.FLASH_MODE_OFF,
                        )
                }
                NightMode.CaptureMode.SCENE -> {
                    Camera2Interop.Extender(builder)
                        .setCaptureRequestOption(
                            CaptureRequest.CONTROL_MODE,
                            CameraMetadata.CONTROL_MODE_USE_SCENE_MODE,
                        )
                        .setCaptureRequestOption(
                            CaptureRequest.CONTROL_SCENE_MODE,
                            CameraMetadata.CONTROL_SCENE_MODE_NIGHT,
                        )
                        .setCaptureRequestOption(
                            CaptureRequest.FLASH_MODE,
                            CameraMetadata.FLASH_MODE_OFF,
                        )
                }
                NightMode.CaptureMode.LOW_LIGHT_BOOST -> {
                    Camera2Interop.Extender(builder)
                        .setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_MODE,
                            CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY,
                        )
                }
                NightMode.CaptureMode.NONE -> {
                    onReady(false)
                    return
                }
            }
            val ic = builder.build()
            p.unbindAll()
            p.bindToLifecycle(owner, selector, ic)
            imageCapture = ic
            isReady = true
            Log.i(TAG, "night still session ready mode=$mode ${desired.width}x${desired.height} exposure=${manualExposureMs}ms iso=$manualIso")
            onReady(true)
        } catch (e: Exception) {
            Log.e(TAG, "bind $mode failed: ${e.message}")
            onLog("Night mode could not start: ${e.message}")
            imageCapture = null
            isReady = false
            onReady(false)
        }
    }

    private fun frameDurationNs(exposureNs: Long): Long =
        maxOf(exposureNs, 33_333_333L)

    private fun resolutionSelector(): ResolutionSelector {
        val ratio = if (desired.width.toDouble() / desired.height >= 1.5) {
            AspectRatio.RATIO_16_9
        } else {
            AspectRatio.RATIO_4_3
        }
        return ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy(ratio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
            .setResolutionStrategy(
                ResolutionStrategy(desired, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
            .build()
    }

    private companion object {
        private const val TAG = "NightModeCapture"

        /** A night exposure merges several frames, so give it much longer than a video snapshot. */
        private const val CAPTURE_TIMEOUT_MS = 15_000L

        /** Never shut down: CameraX can deliver a pending result after the session is released. */
        private val CAPTURE_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "NightModeCapture").apply { isDaemon = true }
        }
    }
}
