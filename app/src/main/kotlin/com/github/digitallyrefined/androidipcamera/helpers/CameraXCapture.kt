package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
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
 * legacy HALs, and Preview is screen-capped anyway). Full-res stills come from ImageCapture
 * (co-bound so no per-shot rebind); if the HAL never completes a takePicture, the freshest analysis
 * frame is encoded as a guaranteed fallback. Some HALs fail to configure any session containing a
 * still surface ("Unable to configure camera ... TimeoutException") — that wedge is detected by the
 * service via [hasProducedFrame] and it falls back to the Camera1 backend.
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
    private val requestedFps: Int,
    private val previewSurfaceProvider: Preview.SurfaceProvider? = null,
    private val encoderSurfaceProvider: Preview.SurfaceProvider? = null,
    private val onEncoderSurfaceFallback: (() -> Unit)? = null,
    private val onFrame: (ImageProxy) -> Unit
) : CaptureBackend {
    @Volatile override var width = desired.width; private set
    @Volatile override var height = desired.height; private set
    @Volatile override var ready = false; private set
    @Volatile var encoderSurfaceBound = false
        private set

    private var provider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null
    private var imageCapture: ImageCapture? = null
    // Always co-bound with the stream so a still snaps straight from the bound use case (no rebind).
    private var boundSelector: CameraSelector? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var previewUseCase: Preview? = null
    private var encoderUseCase: Preview? = null
    @Volatile private var released = false
    /** True once the analysis stream has delivered at least one frame. The service uses this to spot
     *  a session that bound but never configured (no frames ever arrive) and fall back to Camera1. */
    @Volatile var hasProducedFrame = false
        private set
    /** Elapsed realtime of the last analysis frame, so the service can tell a stalled session from a live one. */
    @Volatile var lastFrameAtMs = 0L
        private set
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var torchEnabled = false
    /** Whether the bound camera can drive the flash at all — auxiliary lenses (ultra-wide, depth)
     *  report FLASH_INFO_AVAILABLE=false on many multi-lens phones and enableTorch() then fails
     *  asynchronously, which a synchronous try/catch never sees. Lazy because it reads
     *  [logicalCameraId], declared below. */
    override val hasFlashUnit: Boolean by lazy {
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val id = logicalCameraId?.takeIf { it in cm.cameraIdList }
                ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
            id != null && cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } catch (_: Exception) { true }
    }
    // Cached camera controls, re-applied after a rebind (which replaces the camera control).
    @Volatile private var zoomRatio: Float? = null
    @Volatile private var exposureIndex: Int? = null
    // Cached manual focus (0f..1f) so it can be re-applied after an async (re)bind; null = autofocus.
    @Volatile private var manualFocus: Float? = null
    private val main = ContextCompat.getMainExecutor(ctx)
    private val analysisExec = Executors.newSingleThreadExecutor()
    @Volatile private var sensorIsoMax: Int? = null

    // Latest analysis frame in NV21, so a still can fall back to it when the HAL's takePicture()
    // stalls or the camera is mid-restart — a guaranteed JPEG beats a 503.
    private val nv21Lock = Any()
    private var nv21Buffer: ByteArray? = null
    private var nv21RowBuffer: ByteArray? = null
    @Volatile private var latestNv21: ByteArray? = null
    @Volatile private var latestNv21W = 0
    @Volatile private var latestNv21H = 0
    @Volatile private var latestNv21AtMs = 0L

    private val logicalCameraId: String? = cameraId?.substringBefore(':')
    private val physicalCameraId: String? = cameraId
        ?.substringAfter(':', "")
        ?.takeIf { it.isNotBlank() }

    /** Upper ISO sensitivity the bound sensor reports, used by the Camera AE night trigger. */
    private fun readSensorIsoMax(): Int? = try {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = logicalCameraId?.takeIf { it in cm.cameraIdList }
            ?: cm.cameraIdList.firstOrNull { candidate ->
                cm.getCameraCharacteristics(candidate).get(CameraCharacteristics.LENS_FACING) ==
                    if (front) CameraCharacteristics.LENS_FACING_FRONT
                    else CameraCharacteristics.LENS_FACING_BACK
            }
        id?.let { cm.getCameraCharacteristics(it).get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.upper }
    } catch (_: Exception) {
        null
    }

    @AndroidXOptIn(ExperimentalCamera2Interop::class)
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
                // Pin every use case to a fixed rotation. Stream rotation comes only from the
                // per-camera rotate= control: the encoders ignore imageInfo.rotationDegrees and the
                // GL pipe ignores the surface's sensor transform, so this value must never follow
                // the display rotation.
                val aBuilder = ImageAnalysis.Builder()
                    .setResolutionSelector(sel)
                    .setTargetRotation(Surface.ROTATION_0)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                val analysisInterop = Camera2Interop.Extender(aBuilder)
                analysisInterop.setPhysicalCameraIdIfSupported(physicalCameraId)
                sensorIsoMax = readSensorIsoMax()
                analysisInterop.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        CameraLightMeter.reportAe(
                            result.get(CaptureResult.SENSOR_SENSITIVITY),
                            sensorIsoMax,
                            result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                            result.get(CaptureResult.SENSOR_FRAME_DURATION),
                        )
                    }
                })
                // Request the tightest advertised range around the configured FPS. The previous
                // "widest range" choice let auto-exposure fall to the lower bound (often 7–15 FPS)
                // in low light, so a 30 FPS recording could run far below its target. The range is
                // still driven by auto-exposure; this is not a fixed high-speed session.
                val fpsRange = targetFpsRange()
                fpsRange?.let {
                    analysisInterop.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                    Log.i(TAG, "AE fps range $it (requested $requestedFps)")
                }
                analysisUseCase = aBuilder.build()
                    .also { a -> a.setAnalyzer(analysisExec) { img ->
                        width = img.width; height = img.height
                        hasProducedFrame = true
                        lastFrameAtMs = SystemClock.elapsedRealtime()
                        CameraLightMeter.reportYuvLuma(img)
                        try {
                            val crop = img.cropRect
                            val nv21 = synchronized(nv21Lock) {
                                convertYUV420toNV21(img, nv21Buffer, nv21RowBuffer).also {
                                    nv21Buffer = it
                                    if (nv21RowBuffer == null || nv21RowBuffer!!.size < img.planes[0].rowStride) {
                                        nv21RowBuffer = ByteArray(img.planes[0].rowStride)
                                    }
                                }
                            }
                            latestNv21 = nv21
                            latestNv21W = crop.width(); latestNv21H = crop.height()
                            latestNv21AtMs = System.currentTimeMillis()
                        } catch (e: Exception) { Log.e(TAG, "frame cache: ${e.message}") }
                        onFrame(img)
                    } }
                // ImageCapture stays co-bound from start(): a still then snaps straight from the bound
                // use case, avoiding the per-shot unbind/rebind that hangs the session configuration on
                // some HALs. MINIMIZE_LATENCY captures from the repeating stream instead of asking the
                // HAL to reconfigure the session for a full-sensor still surface.
                val imageCaptureBuilder = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(Surface.ROTATION_0)
                Camera2Interop.Extender(imageCaptureBuilder).setPhysicalCameraIdIfSupported(physicalCameraId)
                imageCapture = imageCaptureBuilder.build()
                previewSurfaceProvider?.let { pv ->
                    val previewBuilder = Preview.Builder().setResolutionSelector(sel)
                        .setTargetRotation(Surface.ROTATION_0)
                    fpsRange?.let { previewBuilder.setTargetFrameRate(it) }
                    Camera2Interop.Extender(previewBuilder).setPhysicalCameraIdIfSupported(physicalCameraId)
                    previewUseCase = previewBuilder.build().also { it.setSurfaceProvider(pv) }
                }
                encoderSurfaceProvider?.let { pv ->
                    val previewBuilder = Preview.Builder().setResolutionSelector(sel)
                        .setTargetRotation(Surface.ROTATION_0)
                    fpsRange?.let { previewBuilder.setTargetFrameRate(it) }
                    Camera2Interop.Extender(previewBuilder).setPhysicalCameraIdIfSupported(physicalCameraId)
                    encoderUseCase = previewBuilder.build().also { it.setSurfaceProvider(pv) }
                }
                boundSelector = logicalCameraId?.let { requestedId ->
                    CameraSelector.Builder()
                        .addCameraFilter { cameraInfos ->
                            cameraInfos.filter { Camera2CameraInfo.from(it).cameraId == requestedId }
                        }
                        .build()
                } ?: if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                rebind()
                ready = true
                Log.i(TAG, "bound desired ${desired.width}x${desired.height} front=$front cameraId=${cameraId ?: "default"}")
            } catch (e: Exception) { Log.e(TAG, "start: ${e.message}") }
        }, main)
    }

    /** (Re)bind the streaming use cases on the main thread. When the device cannot fit every
     *  requested use case, shed the optional ones in [CameraXBindingPolicy] order. The encoder
     *  surface is the last to go: the CPU-fed YUV fallback cannot sustain full-HD frame rates. */
    @AndroidXOptIn(ExperimentalCamera2Interop::class)
    private fun rebind() {
        if (released) return
        val p = provider ?: return
        val sel = boundSelector ?: return
        val analysis = analysisUseCase ?: return
        val candidates = CameraXBindingPolicy.candidates(
            display = previewUseCase != null,
            encoder = encoderUseCase != null,
            still = imageCapture != null,
        )
        var bound: CameraXBinding? = null
        var lastException: Exception? = null
        for ((index, candidate) in candidates.withIndex()) {
            val cases = mutableListOf<androidx.camera.core.UseCase>()
            if (candidate.display) previewUseCase?.let { cases.add(it) }
            if (candidate.encoder) encoderUseCase?.let { cases.add(it) }
            cases.add(analysis)
            if (candidate.still) imageCapture?.let { cases.add(it) }
            // On slow HALs (e.g. Exynos 7420) the previous camera close() can take 400ms–6s.
            // bindToLifecycle() may fail if the HAL hasn't finished tearing down, so retry the
            // requested combination; reduced combinations are attempted once each.
            val attempts = if (index == 0) 3 else 1
            for (attempt in 1..attempts) {
                try {
                    p.unbindAll()
                    camera = p.bindToLifecycle(owner, sel, *cases.toTypedArray())
                    bound = candidate
                    lastException = null
                    break
                } catch (e: Exception) {
                    lastException = e
                    // A rejected surface combination will not become valid on a retry; only slow
                    // HAL teardown benefits from waiting, so move on to the next combination.
                    val unsupported = e.message?.contains("No supported surface combination") == true
                    if (attempt < attempts && !unsupported) {
                        Log.w(TAG, "bindToLifecycle failed (attempt $attempt/$attempts): ${e.message}; retrying in 500ms...")
                        Thread.sleep(500)
                    } else break
                }
            }
            if (bound != null) break
            if (candidates.size > 1) Log.w(TAG, "bind $candidate failed: ${lastException?.message}")
        }
        if (bound == null) {
            Log.e(TAG, "bindToLifecycle failed after ${candidates.size} combinations: ${lastException?.message}")
            throw lastException ?: IllegalStateException("Could not bind the camera")
        }
        encoderSurfaceBound = bound.encoder && encoderUseCase != null
        if (encoderUseCase != null && !bound.encoder) {
            Log.w(TAG, "Could not keep the H.264 encoder surface; using software YUV encoding")
            encoderUseCase = null
            onEncoderSurfaceFallback?.invoke()
        }
        if (previewUseCase != null && !bound.display) {
            Log.w(TAG, "Could not keep the on-phone preview in this camera session")
            previewUseCase = null
        }
        if (imageCapture != null && !bound.still) {
            Log.w(TAG, "Could not keep full-resolution stills; snapshots use the analysis frame")
            imageCapture = null
        }
        Log.i(TAG, "bound use cases: display=${bound.display} encoder=${bound.encoder} still=${bound.still}; analysis=true")
        // Re-apply cached controls, since rebinding replaces the camera control.
        val cc = camera?.cameraControl
        if (torchEnabled && hasFlashUnit) setTorch(true)
        zoomRatio?.let { applyZoomWithRetry(it) }
        exposureIndex?.let { try { cc?.setExposureCompensationIndex(it) } catch (_: Exception) {} }
        manualFocus?.let { setManualFocus(it) }
    }

    /**
     * Apply a zoom ratio, retrying briefly on failure. Right after a (re)bind, CameraX's reported
     * zoom range (from CONTROL_ZOOM_RATIO_RANGE) can momentarily reject an otherwise-valid ratio —
     * this mostly bites sub-1.0x (ultra-wide) ratios, since >=1.0x is almost always within the
     * camera's default range and succeeds on the first try. Without this retry, a saved sub-1.0x
     * zoom would silently fail to restore on app startup.
     */
    private fun applyZoomWithRetry(ratio: Float, attemptsLeft: Int = 10) {
        val cc = camera?.cameraControl ?: return
        try {
            val future = cc.setZoomRatio(ratio)
            future.addListener({
                try {
                    future.get()
                } catch (e: Exception) {
                    if (attemptsLeft > 0) {
                        mainHandler.postDelayed({ applyZoomWithRetry(ratio, attemptsLeft - 1) }, 150)
                    } else {
                        Log.e(TAG, "zoom $ratio failed after retries: ${e.message}")
                    }
                }
            }, main)
        } catch (e: Exception) {
            if (attemptsLeft > 0) {
                mainHandler.postDelayed({ applyZoomWithRetry(ratio, attemptsLeft - 1) }, 150)
            } else {
                Log.e(TAG, "zoom $ratio rejected after retries: ${e.message}")
            }
        }
    }

    override fun captureStill(onJpeg: (ByteArray?) -> Unit) {
        if (provider == null || released) return onJpeg(null)
        val ic = imageCapture ?: return onJpeg(frameFallbackJpeg())
        // ImageCapture stays bound from start(), so a still needs no rebind — per-shot unbind/rebind
        // cycles hang the session configuration on some HALs. Just snap from the bound use case.
        main.execute {
            if (released) return@execute onJpeg(null)
            var finished = false
            fun done(result: ByteArray?) {
                if (finished) return
                finished = true
                mainHandler.removeCallbacksAndMessages(null)
                onJpeg(result)
            }
            // Watchdog: if the HAL never calls back, fall back to the live analysis frame so the
            // snapshot still succeeds.
            mainHandler.postDelayed({
                if (!finished) {
                    Log.w(TAG, "capture timeout — falling back to analysis frame")
                    done(frameFallbackJpeg())
                }
            }, CAPTURE_TIMEOUT_MS)
            val shoot = Runnable {
                if (finished) return@Runnable
                ic.takePicture(main, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val b = try {
                            val buf = image.planes[0].buffer
                            ByteArray(buf.remaining()).also { buf.get(it) }
                        } catch (e: Exception) { Log.e(TAG, "read: ${e.message}"); null } finally { image.close() }
                        done(b)
                    }
                    override fun onError(e: ImageCaptureException) {
                        Log.e(TAG, "capture: ${e.message}")
                        done(frameFallbackJpeg())
                    }
                })
            }
            // Wait for the re-applied zoom to actually take effect, else the still would be captured
            // at the reset (1.0x) zoom.
            val zoomFuture = zoomRatio?.let { r -> try { camera?.cameraControl?.setZoomRatio(r) } catch (_: Exception) { applyZoomWithRetry(r); null } }
            if (zoomFuture != null) zoomFuture.addListener(shoot, main) else shoot.run()
        }
    }

    /** JPEG encoded from the latest analysis frame (if still fresh), or null. Guarantees a snapshot
     *  whenever the camera is producing frames. */
    private fun frameFallbackJpeg(): ByteArray? {
        val nv: ByteArray; val w: Int; val h: Int
        synchronized(nv21Lock) {
            val cached = latestNv21 ?: run { Log.w(TAG, "fallback: no cached frame"); return null }
            val age = System.currentTimeMillis() - latestNv21AtMs
            if (age > FRAME_FALLBACK_MAX_AGE_MS) {
                Log.w(TAG, "fallback: cached frame stale (${age}ms)")
                return null
            }
            nv = cached.copyOf()   // copy under the lock so the analyzer can't overwrite mid-encode
            w = latestNv21W; h = latestNv21H
        }
        if (w == 0 || h == 0) return null
        return try {
            val quality = DeviceMemoryHelper.mjpegJpegQuality(ctx)
            convertNV21toJPEG(nv, w, h, quality)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "frame fallback OOM")
            try { System.gc() } catch (_: Exception) {}
            null
        } catch (e: Exception) {
            Log.e(TAG, "frame fallback: ${e.message}")
            null
        }
    }

    override fun getTorch(): Boolean = torchEnabled
    override fun setTorch(on: Boolean) {
        // Lenses without a flash unit must report OFF even when asked for ON, so the service knows
        // to route the torch through a flash-capable camera instead.
        if (on && !hasFlashUnit) {
            torchEnabled = false
            return
        }
        try {
            val future = camera?.cameraControl?.enableTorch(on) ?: return
            // enableTorch reports failure through the future, not by throwing — observe it or the
            // failure is completely silent and internal state drifts from the real LED. Do not set
            // [torchEnabled] optimistically: the service's verifier uses getTorch() to decide
            // whether the LED stuck, so an enable that lost the race against a settling camera
            // must read as off and be retried instead of looking successful.
            future.addListener({
                try {
                    future.get()
                    torchEnabled = on
                } catch (e: Exception) {
                    Log.w(TAG, "enableTorch($on): ${e.message}")
                    if (on) torchEnabled = false
                }
            }, main)
        } catch (e: Exception) {
            Log.w(TAG, "enableTorch($on): ${e.message}")
            if (on) torchEnabled = false
        }
    }
    override fun setExposure(ev: Int) { exposureIndex = ev; try { camera?.cameraControl?.setExposureCompensationIndex(ev) } catch (_: Exception) {} }
    override fun setZoom(ratio: Float) { zoomRatio = ratio; applyZoomWithRetry(ratio) }
    @AndroidXOptIn(ExperimentalCamera2Interop::class)
    override fun triggerAutoFocus() {
        try {
            val cam = camera ?: return
            // Drop any manual-focus override, else the lingering CONTROL_AF_MODE_OFF
            // defeats the AF scan below.
            manualFocus = null
            try { Camera2CameraControl.from(cam.cameraControl).clearCaptureRequestOptions() } catch (_: Exception) {}
            val pt = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
            cam.cameraControl.startFocusAndMetering(
                FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS).build())
        } catch (e: Exception) { Log.e(TAG, "AF: ${e.message}") }
    }

    /**
     * Fixed manual focus via Camera2 interop. [distance] 0f..1f maps to LENS_FOCUS_DISTANCE
     * in diopters (0f = infinity, 1f = the lens' minimum focus distance / nearest). A negative
     * value clears the override and restores continuous autofocus.
     */
    @AndroidXOptIn(ExperimentalCamera2Interop::class)
    override fun setManualFocus(distance: Float) {
        val cam = camera ?: run { manualFocus = distance.takeIf { it >= 0f }; return }
        try {
            val c2 = Camera2CameraControl.from(cam.cameraControl)
            if (distance < 0f) {
                // Clear the override so CameraX resumes its own continuous AF.
                manualFocus = null
                c2.clearCaptureRequestOptions()
                return
            }
            val norm = distance.coerceIn(0f, 1f)
            manualFocus = norm
            // Nearest focus in diopters; 0f (fixed-focus lens) leaves us at infinity, which is fine.
            val minDist = Camera2CameraInfo.from(cam.cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            c2.captureRequestOptions = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(
                    CaptureRequest.LENS_FOCUS_DISTANCE, norm * minDist)
                .build()
        } catch (e: Exception) { Log.e(TAG, "manualFocus: ${e.message}") }
    }

    /** The advertised AE range that best matches the configured stream FPS. Preferring the tightest
     *  containing range keeps auto-exposure from dropping to single-digit frame rates in low light. */
    private fun targetFpsRange(): Range<Int>? = try {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = logicalCameraId?.takeIf { it in cm.cameraIdList }
            ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
            ?: cm.cameraIdList.first()
        val advertised = cm.getCameraCharacteristics(id)
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { FpsRange(it.lower, it.upper) }
            .orEmpty()
        CaptureFps.select(advertised, requestedFps)?.let { Range(it.lower, it.upper) }
    } catch (e: Exception) { Log.e(TAG, "fpsRange: ${e.message}"); null }

    override fun stop() {
        ready = false
        released = true               // block any in-flight capture callback from touching the camera
        mainHandler.removeCallbacksAndMessages(null)
        try { provider?.unbindAll() } catch (_: Exception) {}
        try { analysisExec.shutdown() } catch (_: Exception) {}
        provider = null; camera = null; imageCapture = null
    }

    companion object {
        private const val TAG = "CameraXCapture"

        /**
         * Physical camera IDs (logical:physical tokens) only exist from API 28, where
         * [Camera2Interop.Extender.setPhysicalCameraId] becomes available too. Older devices
         * never provide one, so skipping the call keeps the legacy path unchanged.
         */
        @AndroidXOptIn(ExperimentalCamera2Interop::class)
        private fun Camera2Interop.Extender<*>.setPhysicalCameraIdIfSupported(id: String?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                id?.let { setPhysicalCameraId(it) }
            }
        }

        /** Give a full-res takePicture this long before falling back to the analysis frame. */
        private const val CAPTURE_TIMEOUT_MS = 4_000L
        /** Analysis-frame fallback is only served if the cached frame is at most this old. */
        private const val FRAME_FALLBACK_MAX_AGE_MS = 6_000L
    }
}
