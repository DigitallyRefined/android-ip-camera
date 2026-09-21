package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
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
import java.io.ByteArrayOutputStream
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
    // Cached night mode (low light boost) flag so it can be re-applied after an async (re)bind.
    @Volatile private var nightMode = false
    // Opt-in OEM NIGHT extension. Applied at bind time (not via interop), so toggling it forces a
    // rebind; mutually exclusive with the low-light-boost [nightMode] above.
    @Volatile private var nightExtensionRequested = false

    /**
     * Low light boost (API 28+): `CONTROL_POST_RAW_SENSITIVITY_BOOST` multiplies the sensor
     * sensitivity beyond the auto range — the official Camera2 "low light boost" / night-mode hook
     * that lets the exposure accumulate more light in dark scenes, at the cost of a lower frame rate
     * and motion blur. When the range's upper bound is 100 (the neutral "1x" factor) the device
     * offers no boost, and the toggle is reported as unsupported instead of pretending to work.
     */
    private class LowLightBoost(val supported: Boolean, val maxBoost: Int)

    private val lowLightBoost: LowLightBoost by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@lazy LowLightBoost(false, 0)
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val id = logicalCameraId?.takeIf { it in cm.cameraIdList }
                ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
                ?: cm.cameraIdList.firstOrNull()
            val range = id?.let {
                try {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)
                } catch (_: Exception) { null }
            }
            if (range != null && range.upper > 100) LowLightBoost(true, range.upper)
            else LowLightBoost(false, 0)
        } catch (_: Exception) { LowLightBoost(false, 0) }
    }

    private val supportsLowLightBoost: Boolean get() = lowLightBoost.supported

    /**
     * Re-apply the overlapping Camera2 interop capture-request overrides (manual focus + night mode)
     * as one coherent CaptureRequestOptions set, so enabling night mode doesn't clobber an active
     * focus override and vice versa. Passing no overrides clears them all (back to CameraX defaults).
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyCamera2Overrides() {
        val cam = camera ?: return
        try {
            val c2 = Camera2CameraControl.from(cam.cameraControl)
            val b = CaptureRequestOptions.Builder()
            var any = false
            manualFocus?.let { norm ->
                val minDist = Camera2CameraInfo.from(cam.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, norm * minDist)
                any = true
            }
            if (nightMode) {
                // Max boost on the live repeating request; absence of the key is the neutral state.
                b.setCaptureRequestOption(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, lowLightBoost.maxBoost)
                any = true
            }
            if (any) c2.captureRequestOptions = b.build()
            else c2.clearCaptureRequestOptions()
        } catch (e: Exception) { Log.e(TAG, "camera2 overrides: ${e.message}") }
    }
    private val main = ContextCompat.getMainExecutor(ctx)
    private val analysisExec = Executors.newSingleThreadExecutor()

    // Latest analysis frame in NV21, so a still can fall back to it when the HAL's takePicture()
    // stalls or the camera is mid-restart — a guaranteed JPEG beats a 503.
    private val nv21Lock = Any()
    private var nv21Buffer: ByteArray? = null
    private var nv21RowBuffer: ByteArray? = null
    @Volatile private var latestNv21: ByteArray? = null
    @Volatile private var latestNv21W = 0
    @Volatile private var latestNv21H = 0
    @Volatile private var latestNv21Rotation = 0
    @Volatile private var latestNv21AtMs = 0L

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
                    .also { a -> a.setAnalyzer(analysisExec) { img ->
                        width = img.width; height = img.height
                        hasProducedFrame = true
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
                            latestNv21Rotation = img.imageInfo.rotationDegrees
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
                physicalCameraId?.let { Camera2Interop.Extender(imageCaptureBuilder).setPhysicalCameraId(it) }
                imageCapture = imageCaptureBuilder.build()
                previewSurfaceProvider?.let { pv ->
                    val previewBuilder = Preview.Builder().setResolutionSelector(sel)
                    physicalCameraId?.let { Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(it) }
                    previewUseCase = previewBuilder.build().also { it.setSurfaceProvider(pv) }
                }
                encoderSurfaceProvider?.let { pv ->
                    val previewBuilder = Preview.Builder().setResolutionSelector(sel)
                    physicalCameraId?.let { Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(it) }
                    encoderUseCase = previewBuilder.build().also { it.setSurfaceProvider(pv) }
                }
                boundSelector = logicalCameraId?.let { requestedId ->
                    CameraSelector.Builder()
                        .addCameraFilter { cameraInfos ->
                            cameraInfos.filter { Camera2CameraInfo.from(it).cameraId == requestedId }
                        }
                        .build()
                } ?: if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                // Load the OEM extension manager in the background so a stored/opt-in Night
                // Extension preference can be honoured on the next rebind.
                NightExtensionSupport.prime(ctx)
                rebind()
                ready = true
                Log.i(TAG, "bound desired ${desired.width}x${desired.height} front=$front cameraId=${cameraId ?: "default"}")
            } catch (e: Exception) { Log.e(TAG, "start: ${e.message}") }
        }, main)
    }

    /** (Re)bind the streaming use cases on the main thread. ImageCapture is always co-bound so a still
     *  can snap directly from the already-bound use case — never a per-shot unbind/rebind cycle (which
     *  hangs the session configuration on some HALs). */
    private fun rebind() {
        if (released) return
        val p = provider ?: return
        val baseSel = boundSelector ?: return
        val analysis = analysisUseCase ?: return
        val cases = mutableListOf<androidx.camera.core.UseCase>()
        previewUseCase?.let { cases.add(it) }
        encoderUseCase?.let { cases.add(it) }
        cases.add(analysis)
        imageCapture?.let { cases.add(it) }
        p.unbindAll()
        // On slow HALs (e.g. Exynos 7420) the previous camera close() can take 400ms–6s.
        // bindToLifecycle() may fail if the HAL hasn't finished tearing down. Retry a few
        // times before falling back to degraded surface combinations.
        val maxRetries = 3

        // When the user opted into the OEM NIGHT extension, try that selector first. Some HALs
        // advertise the extension but then refuse to configure a session that also carries
        // ImageAnalysis, so fall back to the standard selector rather than losing the camera.
        // If the async ExtensionsManager isn't ready yet, keep the request and bind normally —
        // setNightExtension() has registered a whenReady() callback that rebinds once it loads.
        if (nightExtensionRequested && NightExtensionSupport.isReady()) {
            val extSel = NightExtensionSupport.extensionSelector(baseSel)
            if (extSel == null) {
                // Manager ready but this camera can't do NIGHT with analysis — stop retrying.
                Log.i(TAG, "Night extension requested but unavailable for this camera; staying on boost/off")
                nightExtensionRequested = false
            } else {
                var extensionBound = false
                for (attempt in 1..maxRetries) {
                    try {
                        camera = p.bindToLifecycle(owner, extSel, *cases.toTypedArray())
                        encoderSurfaceBound = encoderUseCase != null && cases.any { it === encoderUseCase }
                        extensionBound = true
                        break
                    } catch (e: Exception) {
                        if (attempt < maxRetries) Thread.sleep(500)
                        else Log.w(TAG, "Night extension bind failed (${e.message}); using standard selector")
                    }
                }
                if (extensionBound) {
                    reapplyControlsAfterRebind()
                    return
                }
                // Don't keep retrying a selector this HAL cannot configure.
                nightExtensionRequested = false
            }
        }

        val sel = baseSel
        var lastException: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                camera = p.bindToLifecycle(owner, sel, *cases.toTypedArray())
                encoderSurfaceBound = encoderUseCase != null && cases.any { it === encoderUseCase }
                lastException = null
                break
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries) {
                    Log.w(TAG, "bindToLifecycle failed (attempt $attempt/$maxRetries): ${e.message}; retrying in 500ms...")
                    Thread.sleep(500)
                } else {
                    Log.e(TAG, "bindToLifecycle failed after $maxRetries attempts: ${e.message}")
                }
            }
        }
        if (lastException != null) {
            // Co-binding the encoder surface with a still stream can exceed some HALs' concurrent-stream
            // limit. Rather than lose the camera, shed the encoder surface / screen preview and keep
            // ImageAnalysis + ImageCapture (software YUV stream, full-res stills intact).
            if (encoderUseCase != null || previewUseCase != null) {
                Log.w(TAG, "Attempting fallback: dropping encoder surface / preview...")
                try {
                    encoderUseCase = null
                    previewUseCase = null
                    encoderSurfaceBound = false
                    p.unbindAll()
                    camera = p.bindToLifecycle(owner, sel, analysis, imageCapture ?: throw RuntimeException("ImageCapture not initialized"))
                    onEncoderSurfaceFallback?.invoke()
                } catch (e: Exception) {
                    Log.e(TAG, "fallback rebind failed: ${e.message}")
                    throw e
                }
            } else {
                // Last resort: a lone ImageCapture should always be bindable.
                Log.w(TAG, "Attempting fallback: binding ImageCapture alone...")
                try {
                    p.unbindAll()
                    camera = p.bindToLifecycle(owner, sel, imageCapture ?: throw RuntimeException("ImageCapture not initialized"))
                    encoderSurfaceBound = false
                } catch (e: Exception) {
                    Log.e(TAG, "ImageCapture-alone rebind failed: ${e.message}")
                    throw e
                }
            }
        }
        reapplyControlsAfterRebind()
    }

    /** Re-apply the cached controls after a rebind replaced the camera control (torch, zoom,
     *  exposure, and the Camera2 manual-focus / low-light-boost interop overrides). */
    private fun reapplyControlsAfterRebind() {
        val cc = camera?.cameraControl
        if (torchEnabled && hasFlashUnit) try { cc?.enableTorch(true) } catch (_: Exception) {}
        zoomRatio?.let { applyZoomWithRetry(it) }
        exposureIndex?.let { try { cc?.setExposureCompensationIndex(it) } catch (_: Exception) {} }
        // Manual focus and/or night mode overrides persist as cached fields and are re-applied
        // atomically after the rebind replaced the camera control. (The NIGHT extension is applied
        // at bind time via the extension-enabled selector, not through these interop options.)
        if (manualFocus != null || nightMode) applyCamera2Overrides()
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
        val ic = imageCapture ?: return onJpeg(null)
        if (provider == null || released) return onJpeg(null)
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
        val nv: ByteArray; val w: Int; val h: Int; val rot: Int
        synchronized(nv21Lock) {
            val cached = latestNv21 ?: run { Log.w(TAG, "fallback: no cached frame"); return null }
            val age = System.currentTimeMillis() - latestNv21AtMs
            if (age > FRAME_FALLBACK_MAX_AGE_MS) {
                Log.w(TAG, "fallback: cached frame stale (${age}ms)")
                return null
            }
            nv = cached.copyOf()   // copy under the lock so the analyzer can't overwrite mid-encode
            w = latestNv21W; h = latestNv21H; rot = latestNv21Rotation
        }
        if (w == 0 || h == 0) return null
        return try {
            val quality = DeviceMemoryHelper.mjpegJpegQuality(ctx)
            var jpeg = convertNV21toJPEG(nv, w, h, quality)
            if (rot != 0) jpeg = rotateJpeg(jpeg, rot, quality)
            jpeg
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "frame fallback OOM")
            try { System.gc() } catch (_: Exception) {}
            null
        } catch (e: Exception) {
            Log.e(TAG, "frame fallback: ${e.message}")
            null
        }
    }

    private fun rotateJpeg(jpeg: ByteArray, degrees: Int, quality: Int): ByteArray {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        try {
            val m = Matrix().apply { postRotate(degrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            try {
                if (rotated == bmp) return jpeg
                val out = ByteArrayOutputStream()
                try {
                    rotated.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    return out.toByteArray()
                } finally { out.close() }
            } finally { if (rotated != bmp) rotated.recycle() }
        } finally { bmp.recycle() }
    }

    override fun getTorch(): Boolean = torchEnabled
    override fun setTorch(on: Boolean) {
        // Lenses without a flash unit must report OFF even when asked for ON, so the service knows
        // to route the torch through a flash-capable camera instead.
        torchEnabled = on && hasFlashUnit
        if (on && !hasFlashUnit) return
        try {
            val future = camera?.cameraControl?.enableTorch(on) ?: return
            // enableTorch reports failure through the future, not by throwing — observe it or the
            // failure is completely silent and internal state drifts from the real LED.
            future.addListener({
                try {
                    future.get()
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
    @OptIn(ExperimentalCamera2Interop::class)
    override fun triggerAutoFocus() {
        try {
            val cam = camera ?: return
            // Drop any manual-focus override, else the lingering CONTROL_AF_MODE_OFF
            // defeats the AF scan below.
            manualFocus = null
            try { Camera2CameraControl.from(cam.cameraControl).clearCaptureRequestOptions() } catch (_: Exception) {}
            // Clearing the options also drops the AE override above — reinstate night mode if active.
            if (nightMode) applyCamera2Overrides()
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
    @OptIn(ExperimentalCamera2Interop::class)
    override fun setManualFocus(distance: Float) {
        // Normalized fixed focus distance in 0f..1f (0f = infinity, 1f = nearest); a negative value
        // clears the override and restores continuous autofocus. Applied together with night mode via
        // applyCamera2Overrides() so the two interop overrides never fight each other.
        manualFocus = distance.takeIf { it >= 0f }?.coerceIn(0f, 1f)
        applyCamera2Overrides()
    }

    override fun isNightModeSupported(): Boolean = supportsLowLightBoost

    override fun setNightMode(on: Boolean) {
        // The boost and the OEM extension are alternatives — turning the boost on drops the
        // extension so the two never double-process.
        if (on) {
            if (nightExtensionRequested) {
                nightExtensionRequested = false
                mainHandler.post { if (!released) rebind() }
            }
        }
        // Never lie to the underlying feature: if the HAL has no low-light-boost range, stay off.
        nightMode = on && supportsLowLightBoost
        applyCamera2Overrides()
    }

    override fun isNightExtensionSupported(): Boolean {
        val base = boundSelector ?: return false
        return NightExtensionSupport.isExtensionAvailable(base) &&
            NightExtensionSupport.isImageAnalysisSupported(base)
    }

    override fun setNightExtension(on: Boolean) {
        // Mutually exclusive with the low-light-boost override.
        if (on) { nightMode = false; applyCamera2Overrides() }
        val changed = nightExtensionRequested != on
        nightExtensionRequested = on
        // No rebind needed when the state is unchanged (e.g. the stored "false" default at startup).
        if (!changed) return
        if (!on) {
            mainHandler.post { if (!released) rebind() }
            return
        }
        // The extension is applied through the bind selector, so this needs a rebind once the
        // async ExtensionsManager is ready. If it isn't yet, wait for it instead of silently
        // binding without the extension.
        NightExtensionSupport.prime(ctx)
        val requestRebind: () -> Unit = { mainHandler.post { if (!released) rebind() }; Unit }
        if (NightExtensionSupport.isReady()) requestRebind() else NightExtensionSupport.whenReady(requestRebind)
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
        released = true               // block any in-flight capture callback from touching the camera
        mainHandler.removeCallbacksAndMessages(null)
        try { provider?.unbindAll() } catch (_: Exception) {}
        try { analysisExec.shutdown() } catch (_: Exception) {}
        provider = null; camera = null; imageCapture = null
    }

    companion object {
        private const val TAG = "CameraXCapture"
        /** Give a full-res takePicture this long before falling back to the analysis frame. */
        private const val CAPTURE_TIMEOUT_MS = 4_000L
        /** Analysis-frame fallback is only served if the cached frame is at most this old. */
        private const val FRAME_FALLBACK_MAX_AGE_MS = 6_000L
    }
}
