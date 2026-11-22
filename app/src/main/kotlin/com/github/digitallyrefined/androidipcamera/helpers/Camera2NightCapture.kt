package com.github.digitallyrefined.androidipcamera.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size

/**
 * A dedicated Camera2 still session for manual long-exposure night photos.
 *
 * CameraX applies ImageCapture's Camera2 interop options to the metering repeating request too.
 * Some HALs (notably the Pixel 3) reject an exposure longer than a video frame on that request, so
 * the still never completes. This class keeps a normal auto-exposure repeating request for 3A and
 * sends manual exposure only on the single still capture request. It therefore bypasses CameraX for
 * the manual path while leaving the existing CameraX extension/scene fallbacks in place.
 */
class Camera2NightCapture(
    private val context: Context,
    private val front: Boolean,
    private val cameraId: String?,
    private val desired: Size,
    private val exposureMs: Long,
    private val iso: Int,
    private val onLog: (String) -> Unit = {},
) : NightStillCapture {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("NightModeCamera2").apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var previewReader: ImageReader? = null
    private var pending: ((ByteArray?) -> Unit)? = null
    private var pendingTimeout: Runnable? = null
    @Volatile private var released = false
    @Volatile override var isReady = false
        private set

    override fun start(onReady: (Boolean) -> Unit) {
        handler.post {
            if (released) return@post notifyReady(onReady, false)
            try {
                openCamera(onReady)
            } catch (e: Exception) {
                Log.e(TAG, "open: ${e.message}")
                notifyReady(onReady, false)
            }
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: android.hardware.camera2.TotalCaptureResult,
        ) {
            try {
                val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY)
                val frame = result.get(CaptureResult.SENSOR_FRAME_DURATION)
                Log.i(TAG, "capture result exposure=${exposure}ns iso=$sensitivity frame=$frame")
            } catch (_: Exception) {
            }
        }
    }

    override fun capture(onJpeg: (ByteArray?) -> Unit) {
        handler.post {
            val s = session
            val d = device
            val reader = jpegReader
            if (released || !isReady || s == null || d == null || reader == null || pending != null) {
                onJpeg(null)
                return@post
            }
            pending = onJpeg
            val timeout = Runnable {
                Log.w(TAG, "capture timed out")
                finish(null)
            }
            pendingTimeout = timeout
            handler.postDelayed(timeout, CAPTURE_TIMEOUT_MS)
            try {
                val exposureNs = (exposureMs.coerceAtLeast(1) * 1_000_000L)
                val request = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                    set(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceAtLeast(1))
                    set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs(exposureNs))
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                    set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                    set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
                }.build()
                s.capture(request, captureCallback, handler)
            } catch (e: Exception) {
                Log.e(TAG, "capture request: ${e.message}")
                finish(null)
            }
        }
    }

    override fun stop() {
        released = true
        isReady = false
        handler.post {
            finish(null)
            try { session?.close() } catch (_: Exception) {}
            session = null
            try { device?.close() } catch (_: Exception) {}
            device = null
            try { jpegReader?.close() } catch (_: Exception) {}
            jpegReader = null
            try { previewReader?.close() } catch (_: Exception) {}
            previewReader = null
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun openCamera(onReady: (Boolean) -> Unit, attempt: Int = 0) {
        val id = resolveCameraId() ?: return notifyReady(onReady, false)
        val characteristics = manager.getCameraCharacteristics(id)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return notifyReady(onReady, false)
        val jpegSize = chooseJpegSize(map, desired)
        val previewSize = choosePreviewSize(map)
        val jpeg = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
        val preview = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2)
        jpeg.setOnImageAvailableListener({ reader -> onJpegAvailable(reader) }, handler)
        preview.setOnImageAvailableListener({ reader ->
            try { reader.acquireLatestImage()?.close() } catch (_: Exception) {}
        }, handler)
        jpegReader = jpeg
        previewReader = preview

        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (released) {
                        camera.close()
                        return
                    }
                    device = camera
                    createSession(camera, onReady)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    device = null
                    notifyReady(onReady, false)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "camera error $error")
                    camera.close()
                    device = null
                    notifyReady(onReady, false)
                }
            }, handler)
        } catch (e: android.hardware.camera2.CameraAccessException) {
            // The previous CameraX session can still be closing when night mode is enabled.
            if (e.reason == android.hardware.camera2.CameraAccessException.CAMERA_IN_USE && attempt < 8) {
                Log.w(TAG, "camera busy, retrying (${attempt + 1}/8)")
                handler.postDelayed({ if (!released) openCamera(onReady, attempt + 1) }, 300)
            } else {
                Log.e(TAG, "open: ${e.message}")
                notifyReady(onReady, false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "open: ${e.message}")
            notifyReady(onReady, false)
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession(camera: CameraDevice, onReady: (Boolean) -> Unit) {
        val jpeg = jpegReader ?: return notifyReady(onReady, false)
        val preview = previewReader ?: return notifyReady(onReady, false)
        try {
            camera.createCaptureSession(
                listOf(jpeg.surface, preview.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (released) {
                            try { s.close() } catch (_: Exception) {}
                            return
                        }
                        session = s
                        try {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(preview.surface)
                                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(15, 30))
                            }.build()
                            s.setRepeatingRequest(request, null, handler)
                            isReady = true
                            Log.i(TAG, "night still session ready camera=$cameraId jpeg=${jpeg.width}x${jpeg.height} exposure=${exposureMs}ms iso=$iso")
                            notifyReady(onReady, true)
                        } catch (e: Exception) {
                            Log.e(TAG, "repeating request: ${e.message}")
                            notifyReady(onReady, false)
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        Log.e(TAG, "session configure failed")
                        notifyReady(onReady, false)
                    }
                },
                handler
            )
        } catch (e: Exception) {
            Log.e(TAG, "create session: ${e.message}")
            notifyReady(onReady, false)
        }
    }

    private fun onJpegAvailable(reader: ImageReader) {
        val image = try { reader.acquireNextImage() } catch (_: Exception) { null } ?: return
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            finish(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "read jpeg: ${e.message}")
            finish(null)
        } finally {
            try { image.close() } catch (_: Exception) {}
        }
    }

    private fun finish(result: ByteArray?) {
        val cb = pending ?: return
        pending = null
        pendingTimeout?.let { handler.removeCallbacks(it) }
        pendingTimeout = null
        cb(result)
    }

    private fun notifyReady(onReady: (Boolean) -> Unit, ready: Boolean) {
        mainHandler.post { onReady(ready) }
    }

    private fun resolveCameraId(): String? {
        NightMode.resolveLogicalId(manager, cameraId)?.let { return it }
        val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        return try {
            manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want
            } ?: manager.cameraIdList.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun chooseJpegSize(map: android.hardware.camera2.params.StreamConfigurationMap, target: Size): Size {
        val sizes = try {
            map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        if (sizes.isEmpty()) return target
        return sizes
            .filter { it.width >= target.width && it.height >= target.height }
            .minByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull {
                kotlin.math.abs(it.width.toLong() * it.height - target.width.toLong() * target.height)
            }
            ?: target
    }

    private fun choosePreviewSize(map: android.hardware.camera2.params.StreamConfigurationMap): Size {
        val target = Size(640, 480)
        val sizes = try {
            map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        if (sizes.isEmpty()) return target
        return sizes
            .filter { it.width >= 320 && it.height >= 240 }
            .minByOrNull { kotlin.math.abs(it.width.toLong() * it.height - target.width.toLong() * target.height) }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
            ?: target
    }

    private fun frameDurationNs(exposureNs: Long): Long = maxOf(exposureNs, 33_333_333L)

    private companion object {
        private const val TAG = "Camera2NightCapture"

        /** Long night exposures plus OEM processing can take a while. */
        private const val CAPTURE_TIMEOUT_MS = 20_000L
    }
}
