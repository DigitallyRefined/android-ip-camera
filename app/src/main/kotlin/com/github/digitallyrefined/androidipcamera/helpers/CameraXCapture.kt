package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The single camera backend: CameraX. The live stream is fed from **ImageAnalysis** (YUV frames →
 * the H.264 encoder) — unlike Preview, ImageAnalysis honours the requested resolution and is NOT
 * capped to the device's screen size, so it streams the real 720p/1080p the user picks and works
 * headless. **ImageCapture** takes full-resolution stills concurrently. CameraX handles per-device
 * quirks, rotation, lifecycle, focus and resolution uniformly across phones.
 *
 * [onFrame] is called for every analysis frame and MUST close the ImageProxy.
 */
class CameraXCapture(
    private val ctx: Context,
    private val owner: LifecycleOwner,
    private val front: Boolean,
    private val desired: Size,
    private val onFrame: (ImageProxy) -> Unit
) : CaptureBackend {
    @Volatile override var width = desired.width; private set
    @Volatile override var height = desired.height; private set
    @Volatile override var ready = false; private set

    private var provider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null
    private var imageCapture: ImageCapture? = null
    private val main = ContextCompat.getMainExecutor(ctx)
    private val analysisExec = Executors.newSingleThreadExecutor()

    fun start() {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            try {
                val p = future.get(); provider = p
                // ImageAnalysis defaults to 640x480; a ResolutionStrategy alone is overridden unless a
                // MATCHING AspectRatioStrategy is set too. PREFER_HIGHER_RESOLUTION lifts the analysis cap.
                val ratio = if (desired.width.toDouble() / desired.height >= 1.5) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
                val selExec = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy(ratio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                    .setResolutionStrategy(ResolutionStrategy(desired, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(selExec)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also { a -> a.setAnalyzer(analysisExec) { img -> width = img.width; height = img.height; onFrame(img) } }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)   // full-res stills
                    .build()
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                p.unbindAll()
                camera = p.bindToLifecycle(owner, selector, analysis, imageCapture)
                ready = true
                Log.i(TAG, "bound desired ${desired.width}x${desired.height} front=$front")
            } catch (e: Exception) { Log.e(TAG, "start: ${e.message}") }
        }, main)
    }

    override fun captureStill(onJpeg: (ByteArray?) -> Unit) {
        val ic = imageCapture ?: return onJpeg(null)
        ic.takePicture(main, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val buf = image.planes[0].buffer
                    val b = ByteArray(buf.remaining()); buf.get(b); onJpeg(b)
                } catch (e: Exception) { Log.e(TAG, "read: ${e.message}"); onJpeg(null) }
                finally { image.close() }
            }
            override fun onError(e: ImageCaptureException) { Log.e(TAG, "capture: ${e.message}"); onJpeg(null) }
        })
    }

    override fun setTorch(on: Boolean) { try { camera?.cameraControl?.enableTorch(on) } catch (_: Exception) {} }
    override fun setExposure(ev: Int) { try { camera?.cameraControl?.setExposureCompensationIndex(ev) } catch (_: Exception) {} }
    override fun setZoom(ratio: Float) { try { camera?.cameraControl?.setZoomRatio(ratio) } catch (_: Exception) {} }
    override fun triggerAutoFocus() {
        try {
            val cam = camera ?: return
            val pt = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
            cam.cameraControl.startFocusAndMetering(
                FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS).build())
        } catch (e: Exception) { Log.e(TAG, "AF: ${e.message}") }
    }

    override fun stop() {
        ready = false
        try { provider?.unbindAll() } catch (_: Exception) {}
        try { analysisExec.shutdown() } catch (_: Exception) {}
        camera = null; imageCapture = null
    }

    companion object { private const val TAG = "CameraXCapture" }
}
