package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
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
 * Global/modern backend (CameraX). The live stream is fed from ImageAnalysis (YUV → encoder) — it
 * reliably honours the requested resolution (Preview-to-a-custom-Surface picks tiny sizes on some
 * legacy HALs, and Preview is screen-capped anyway). ImageCapture gives full-resolution stills.
 * On a strong CPU this runs at the camera's frame rate; on weak/legacy chips the YUV→NV12 copy is
 * the bottleneck (~12fps) — which is why the service auto-prefers Camera1 on LEGACY hardware.
 *
 * [onFrame] is called for every analysis frame and MUST close the ImageProxy.
 */
class CameraXCapture(
    private val ctx: Context,
    private val owner: LifecycleOwner,
    private val front: Boolean,
    private val cameraId: String?,
    private val desired: Size,
    private val previewSurfaceProvider: Preview.SurfaceProvider? = null,
    private val onFrame: (ImageProxy) -> Unit
) : CaptureBackend {
    @Volatile override var width = desired.width; private set
    @Volatile override var height = desired.height; private set
    @Volatile override var ready = false; private set

    private var provider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null
    private var imageCapture: ImageCapture? = null
    // Kept so a still can briefly rebind ImageCapture on demand (see captureStill).
    private var boundSelector: CameraSelector? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var previewUseCase: Preview? = null
    private var capturing = false
    @Volatile private var torchEnabled = false
    private val main = ContextCompat.getMainExecutor(ctx)
    private val analysisExec = Executors.newSingleThreadExecutor()

    private val logicalCameraId: String? = cameraId?.substringBefore(':')
    private val physicalCameraId: String? = cameraId
        ?.substringAfter(':', "")
        ?.takeIf { it.isNotBlank() }

    @OptIn(ExperimentalCamera2Interop::class)
    fun start() {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            try {
                val p = future.get(); provider = p
                // ImageAnalysis ignores a lone ResolutionStrategy unless a matching AspectRatioStrategy
                // is set too; PREFER_HIGHER lifts the analysis cap above the 640x480 default.
                val ratio = if (desired.width.toDouble() / desired.height >= 1.5) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
                val sel = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy(ratio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                    .setResolutionStrategy(ResolutionStrategy(desired, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                    .build()
                val aBuilder = ImageAnalysis.Builder()
                    .setResolutionSelector(sel)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                val analysisInterop = Camera2Interop.Extender(aBuilder)
                physicalCameraId?.let { analysisInterop.setPhysicalCameraId(it) }
                // Some legacy HALs (e.g. this phone's rear cam) stall the analysis stream unless an AE
                // target FPS range is set. Use the WIDEST advertised range, not a fixed lock — AE stays
                // fully auto (drops low for light in the dark, up to 30 in good light).
                aeFpsRange()?.let {
                    analysisInterop.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                    Log.i(TAG, "AE fps range $it (auto within range)")
                }
                analysisUseCase = aBuilder.build()
                    .also { a -> a.setAnalyzer(analysisExec) { img -> width = img.width; height = img.height; onFrame(img) } }
                // ImageCapture is NOT bound during streaming: co-binding a MAXIMUM still stream forces the
                // HAL to cap ImageAnalysis at preview size. It is bound on demand in captureStill() instead,
                // so the live analysis stream can use the full sensor resolution.
                val imageCaptureBuilder = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)   // full-res stills
                physicalCameraId?.let { Camera2Interop.Extender(imageCaptureBuilder).setPhysicalCameraId(it) }
                imageCapture = imageCaptureBuilder.build()
                previewSurfaceProvider?.let { pv ->
                    val previewBuilder = Preview.Builder()
                    physicalCameraId?.let { Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(it) }
                    previewUseCase = previewBuilder.build().also { it.setSurfaceProvider(pv) }
                }
                boundSelector = logicalCameraId?.let { requestedId ->
                    CameraSelector.Builder()
                        .addCameraFilter { cameraInfos ->
                            cameraInfos.filter { Camera2CameraInfo.from(it).cameraId == requestedId }
                        }
                        .build()
                } ?: if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                rebind(withCapture = false)
                ready = true
                Log.i(TAG, "bound desired ${desired.width}x${desired.height} front=$front cameraId=${cameraId ?: "default"}")
            } catch (e: Exception) { Log.e(TAG, "start: ${e.message}") }
        }, main)
    }

    /** (Re)bind the streaming use cases on the main thread; [withCapture] adds ImageCapture for a still. */
    private fun rebind(withCapture: Boolean) {
        val p = provider ?: return
        val sel = boundSelector ?: return
        val analysis = analysisUseCase ?: return
        val cases = mutableListOf<androidx.camera.core.UseCase>()
        previewUseCase?.let { cases.add(it) }
        cases.add(analysis)
        if (withCapture) imageCapture?.let { cases.add(it) }
        p.unbindAll()
        camera = p.bindToLifecycle(owner, sel, *cases.toTypedArray())
        // Re-apply cached torch, since rebinding replaces the camera control.
        if (torchEnabled) try { camera?.cameraControl?.enableTorch(true) } catch (_: Exception) {}
    }

    override fun captureStill(onJpeg: (ByteArray?) -> Unit) {
        val ic = imageCapture ?: return onJpeg(null)
        if (provider == null) return onJpeg(null)
        // Briefly add ImageCapture (drops the live stream to preview size for the shot), then restore
        // analysis-only so streaming returns to full resolution.
        main.execute {
            if (capturing) return@execute onJpeg(null)
            capturing = true
            try { rebind(withCapture = true) }
            catch (e: Exception) { Log.e(TAG, "rebind capture: ${e.message}"); capturing = false; return@execute onJpeg(null) }
            ic.takePicture(main, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val buf = image.planes[0].buffer
                        val b = ByteArray(buf.remaining()); buf.get(b); onJpeg(b)
                    } catch (e: Exception) { Log.e(TAG, "read: ${e.message}"); onJpeg(null) }
                    finally { image.close(); restoreStreamBind() }
                }
                override fun onError(e: ImageCaptureException) {
                    Log.e(TAG, "capture: ${e.message}"); onJpeg(null); restoreStreamBind()
                }
            })
        }
    }

    /** Restore the analysis-only (full-resolution) bind after a still. */
    private fun restoreStreamBind() {
        main.execute {
            try { rebind(withCapture = false) } catch (e: Exception) { Log.e(TAG, "rebind stream: ${e.message}") }
            finally { capturing = false }
        }
    }

    override fun getTorch(): Boolean = torchEnabled
    override fun setTorch(on: Boolean) { torchEnabled = on; try { camera?.cameraControl?.enableTorch(on) } catch (_: Exception) {} }
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

    /** The camera's WIDEST advertised AE FPS range — leaves auto-exposure fully free while giving legacy
     *  HALs the explicit range they need to not stall the analysis stream. Not a fixed-fps lock. */
    private fun aeFpsRange(): Range<Int>? = try {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = logicalCameraId?.takeIf { it in cm.cameraIdList }
            ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
            ?: cm.cameraIdList.first()
        cm.getCameraCharacteristics(id).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.maxWithOrNull(compareBy({ it.upper - it.lower }, { it.upper }))
    } catch (e: Exception) { Log.e(TAG, "aeRange: ${e.message}"); null }

    override fun stop() {
        ready = false
        try { provider?.unbindAll() } catch (_: Exception) {}
        try { analysisExec.shutdown() } catch (_: Exception) {}
        camera = null; imageCapture = null
    }

    companion object { private const val TAG = "CameraXCapture" }
}
