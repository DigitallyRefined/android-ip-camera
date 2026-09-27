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
    /**
     * Stored low-light level to start from. It has to be known *before* the bind, because the AE fps
     * range that unlocks the long exposure is baked into each use case at build time — seeding it
     * here is what makes the very first camera start of a session already correct, instead of
     * relying on a camera-level option applied after the session is already configured.
     */
    private val initialLowLightLevel: Int = 0,
    /**
     * Stored OEM NIGHT extension opt-in, likewise seeded before the bind so the camera does not
     * have to be rebound once just to turn it on.
     */
    private val initialNightExtension: Boolean = false,
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
    // Cached low-light level, applied to every use case at bind time and re-applied after a rebind.
    // Seeded from [initialLowLightLevel] so the first bind already carries the right AE fps range.
    @Volatile private var lowLightLevel = initialLowLightLevel.coerceIn(0, LowLight.MAX_LEVEL)
    // Opt-in OEM NIGHT extension. Applied through the bind selector (not interop), so toggling it
    // forces a rebind; mutually exclusive with lowLightLevel.
    @Volatile private var nightExtensionRequested = initialNightExtension && lowLightLevel == 0
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

    /**
     * Static characteristics of the camera this backend will bind, resolved once. Both the AE fps
     * range choice and the "is low light even worth offering" question are answered from these,
     * and they never change for a given lens, so there is no reason to re-read them per frame.
     */
    private val characteristics: CameraCharacteristics? by lazy {
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val id = logicalCameraId?.takeIf { it in cm.cameraIdList }
                ?: cm.cameraIdList.firstOrNull {
                    runCatching { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }.getOrDefault(false)
                }
                ?: cm.cameraIdList.firstOrNull()
            id?.let { cm.getCameraCharacteristics(it) }
        } catch (e: Exception) {
            Log.w(TAG, "characteristics: ${e.message}")
            null
        }
    }

    /** Real low-light limits of this camera, so the UI never offers an unreachable level. */
    override val lowLightCaps: LowLight.Caps by lazy {
        characteristics?.let { LowLight.capsOf(it) } ?: LowLight.Caps.UNKNOWN
    }

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
                // target FPS range is set. In low light the *slowest* advertised range is used instead —
                // that is what lets auto-exposure hold a long shutter and actually collect light.
                val profile = LowLight.profile(lowLightLevel)
                val fpsRange = if (profile.level > 0) {
                    LowLight.fpsRangeFor(characteristics, profile.targetFps)
                } else {
                    aeFpsRange()
                }
                fpsRange?.let {
                    analysisInterop.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                    Log.i(TAG, "AE fps range $it (${if (profile.level > 0) "low light L${profile.level}" else "auto within range"})")
                }
                applyLowLightToUseCase(analysisInterop, profile)

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
                    // In surface mode this Preview *is* the video stream, so the low-light AE fps
                    // range has to reach it too — tuning only ImageAnalysis would improve the
                    // software paths and leave /video/h264 exactly as dark as before.
                    Camera2Interop.Extender(previewBuilder).also {
                        physicalCameraId?.let { pid -> it.setPhysicalCameraId(pid) }
                        applyLowLightToUseCase(it, profile)
                    }
                    previewUseCase = previewBuilder.build().also { it.setSurfaceProvider(pv) }
                }
                encoderSurfaceProvider?.let { pv ->
                    val previewBuilder = Preview.Builder().setResolutionSelector(sel)
                    Camera2Interop.Extender(previewBuilder).also {
                        physicalCameraId?.let { pid -> it.setPhysicalCameraId(pid) }
                        applyLowLightToUseCase(it, profile)
                    }
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

        // When the OEM NIGHT extension was requested, try its selector first: it is the only way to
        // reach the vendor's tuned night algorithm, and it is applied here because the extension is
        // part of the bind, not a per-request option. Some HALs advertise it and then refuse to
        // configure a session that also carries ImageAnalysis, so a failure falls back to the normal
        // selector rather than losing the camera.
        if (nightExtensionRequested && NightExtensionSupport.isReady()) {
            val extSel = NightExtensionSupport.extensionSelector(baseSel)
            if (extSel == null) {
                // Manager ready but this camera cannot do NIGHT with analysis — stop asking.
                Log.i(TAG, "Night extension requested but unavailable for this camera; staying off")
                nightExtensionRequested = false
            } else {
                var extensionBound = false
                for (attempt in 1..maxRetries) {
                    try {
                        camera = p.bindToLifecycle(owner, extSel, *cases.toTypedArray())
                        encoderSurfaceBound = encoderUseCase != null && cases.any { it === encoderUseCase }
                        extensionBound = true
                        Log.i(TAG, "bound with OEM NIGHT extension")
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
                // Do not keep retrying a selector this HAL cannot configure. Unbind first: the failed
                // extension attempt may have left a half-configured session, and dropping straight
                // into the standard bind below would be building on top of it.
                nightExtensionRequested = false
                try { p.unbindAll() } catch (_: Exception) {}
            }
        }

        val sel = baseSel
        // The extension was requested but the ExtensionsManager has not finished loading, so the
        // branch above could not run. Bind normally now so the stream works in the meantime, and
        // rebind once the manager arrives — otherwise a stored "on" would be silently ignored on a
        // cold start, because the state is already set and setNightExtension() would see no change.
        if (nightExtensionRequested && !NightExtensionSupport.isReady()) {
            Log.i(TAG, "Night extension requested; waiting for ExtensionsManager before rebinding")
            NightExtensionSupport.prime(ctx)
            NightExtensionSupport.whenReady { if (!released) rebind() }
        }
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
        // Re-apply cached controls, since rebinding replaces the camera control.
        reapplyControlsAfterRebind()
    }

    /**
     * Re-apply the cached controls after a rebind replaced the camera control: torch, zoom,
     * exposure, and the single merged Camera2 interop option set (manual focus + low light).
     */
    private fun reapplyControlsAfterRebind() {
        val cc = camera?.cameraControl
        if (torchEnabled && hasFlashUnit) try { cc?.enableTorch(true) } catch (_: Exception) {}
        zoomRatio?.let { applyZoomWithRetry(it) }
        exposureIndex?.let { try { cc?.setExposureCompensationIndex(it) } catch (_: Exception) {} }
        // Manual focus and low light share one CaptureRequestOptions slot on Camera2CameraControl,
        // so they are always applied together (see applyCaptureRequestOptions).
        applyCaptureRequestOptions()
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
            // defeats the AF scan below. Low-light options (if any) are kept.
            manualFocus = null
            applyCaptureRequestOptions()
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
    override fun setManualFocus(distance: Float) {
        // Cached and re-applied through the merged option set, so enabling low light never wipes an
        // active focus override and vice versa (they share one CaptureRequestOptions slot).
        manualFocus = distance.takeIf { it >= 0f }?.coerceIn(0f, 1f)
        applyCaptureRequestOptions()
    }

    override fun setLowLight(level: Int) {
        val clamped = level.coerceIn(0, LowLight.MAX_LEVEL)
        if (clamped == lowLightLevel) return
        lowLightLevel = clamped
        // The extension and the app's own levels are alternatives; enabling the levels drops the
        // extension so the two never double-process the same frame.
        if (clamped > 0 && nightExtensionRequested) {
            nightExtensionRequested = false
            mainHandler.post { if (!released) rebind() }
        }
        // The AE fps range is also baked into every use case at bind time (that is the only way to
        // reach the surface-mode Preview stream), so the service restarts the camera after a level
        // change. Applying here as well makes the change visible immediately on the paths that honour
        // camera-level options, and covers a restart that has not happened yet.
        applyCaptureRequestOptions()
        Log.i(TAG, "low light L$clamped (caps=${lowLightCaps})")
    }

    override fun setNightExtension(on: Boolean) {
        // Mutually exclusive with the app's own low-light levels.
        if (on && lowLightLevel > 0) { lowLightLevel = 0; applyCaptureRequestOptions() }
        val changed = nightExtensionRequested != on
        nightExtensionRequested = on
        // No rebind needed when the state is unchanged (e.g. the stored "false" default at startup).
        if (!changed) return
        if (!on) {
            mainHandler.post { if (!released) rebind() }
            return
        }
        // The extension is applied through the bind selector, so this needs a rebind once the async
        // ExtensionsManager is ready. If it isn't yet, wait for it instead of binding without it.
        NightExtensionSupport.prime(ctx)
        val requestRebind: () -> Unit = { mainHandler.post { if (!released) rebind() } }
        if (NightExtensionSupport.isReady()) requestRebind() else NightExtensionSupport.whenReady(requestRebind)
    }

    override fun isNightExtensionSupported(): Boolean {
        val base = boundSelector ?: return false
        return NightExtensionSupport.isExtensionAvailable(base) &&
            NightExtensionSupport.isImageAnalysisSupported(base)
    }

    /**
     * Merge the cached manual-focus and low-light overrides into ONE repeating CaptureRequest via
     * Camera2 interop. Both features share a single [CaptureRequestOptions] slot on
     * Camera2CameraControl, so applying one independently would silently wipe the other.
     * Falls back to a reduced option set if the HAL rejects the full one.
     */
    private fun applyCaptureRequestOptions() {
        val cam = camera ?: return
        val focus = manualFocus
        val profile = LowLight.profile(lowLightLevel)
        if (focus == null && profile.level == 0) {
            // Nothing to express. A fresh bind already starts from a clean CaptureRequestOptions, so
            // there is nothing to clear here — clearing unconditionally just pokes the running session
            // on every rebind for no gain. Clearing is reserved for the explicit "back to auto focus"
            // path in setManualFocus, which is the only case that can have stale options to drop.
            return
        }
        try {
            val c2 = Camera2CameraControl.from(cam.cameraControl)
            val minFocusDistance = if (focus != null) {
                Camera2CameraInfo.from(cam.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            } else 0f

            fun build(withFocus: Boolean, withNight: Boolean): CaptureRequestOptions? = try {
                val b = CaptureRequestOptions.Builder()
                if (withFocus && focus != null) {
                    b.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    // Nearest focus in diopters; 0f (fixed-focus lens) leaves us at infinity, fine.
                    b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focus * minFocusDistance)
                }
                if (withNight && profile.level > 0) applyLowLightRequestOptions(b)
                b.build()
            } catch (_: Exception) { null }

            // Richest option set first; if CameraX/the HAL rejects it, drop pieces until one sticks so
            // a single unsupported night key never takes manual focus down with it.
            val attempts = listOfNotNull(
                build(withFocus = true, withNight = true),
                build(withFocus = true, withNight = false),
                build(withFocus = false, withNight = true),
            ).distinct()
            for (options in attempts) {
                try {
                    c2.captureRequestOptions = options
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "captureOptions rejected: ${e.message}")
                }
            }
            // Everything was rejected — clear so we at least fall back to CameraX defaults.
            try { c2.clearCaptureRequestOptions() } catch (_: Exception) {}
        } catch (e: Exception) { Log.e(TAG, "captureOptions: ${e.message}") }
    }

    /**
     * Low-light keys for the repeating request:
     *  - the slowest advertised AE fps range, which is what actually lets auto-exposure hold a long
     *    shutter (auto-exposure cannot expose longer than one frame period, whatever the ISO);
     *  - max `CONTROL_POST_RAW_SENSITIVITY_BOOST` for HALs that allow sensitivity past the ISO range;
     *  - FAST noise reduction — in a dark frame HIGH_QUALITY averages away exactly the faint
     *    shadow signal the longer exposure just collected, and at low fps its latency is very visible;
     *  - the vendor NIGHT scene mode, kept only as a bonus since most HALs ignore it.
     * Every key is checked against the camera's advertised modes first.
     */
    private fun applyLowLightRequestOptions(b: CaptureRequestOptions.Builder) {
        val profile = LowLight.profile(lowLightLevel)
        val caps = lowLightCaps
        val range = LowLight.fpsRangeFor(characteristics, profile.targetFps)
        if (range != null) {
            try { b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range) } catch (_: Exception) {}
        } else {
            Log.w(TAG, "low light L${profile.level}: no usable AE fps range (min ${caps.minFpsUpper}fps)")
        }
        if (profile.useBoost && caps.hasBoost && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { b.setCaptureRequestOption(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, caps.boostUpper) }
            catch (_: Exception) {}
        }
        try { b.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST) }
        catch (_: Exception) {}
        val scenes = characteristics?.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
        if (profile.sceneModeNight && scenes != null && CameraMetadata.CONTROL_SCENE_MODE_NIGHT in scenes) {
            try { b.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_NIGHT) }
            catch (_: Exception) {}
        }
    }

    /**
     * Same low-light keys, applied to a *use case* builder instead of the camera control. This is the
     * path that reaches the surface-mode Preview use case, which is what /video/h264 actually renders
     * from — a camera-level option alone would leave the main video stream untouched. Use-case options
     * are fixed at build time, so a level change is picked up on the next camera start.
     */
    private fun applyLowLightToUseCase(extender: Camera2Interop.Extender<*>, profile: LowLight.Profile) {
        if (profile.level == 0) return
        val caps = lowLightCaps
        LowLight.fpsRangeFor(characteristics, profile.targetFps)?.let {
            try { extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) } catch (_: Exception) {}
        }
        if (profile.useBoost && caps.hasBoost && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { extender.setCaptureRequestOption(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, caps.boostUpper) }
            catch (_: Exception) {}
        }
        try { extender.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST) }
        catch (_: Exception) {}
        val scenes = characteristics?.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
        if (profile.sceneModeNight && scenes != null && CameraMetadata.CONTROL_SCENE_MODE_NIGHT in scenes) {
            try { extender.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_NIGHT) }
            catch (_: Exception) {}
        }
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
            // Widest advertised range, converted from thousandths to fps — the request key wants fps.
            ?.maxWithOrNull(compareBy({ it.upper - it.lower }, { it.upper }))
            ?.let { Range(it.lower / 1000, it.upper / 1000) }
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
