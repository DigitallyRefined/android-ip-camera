package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide, lazily-initialised view of the OEM CameraX NIGHT extension.
 *
 * The CameraX extension API is the only supported way for a third-party app to reach the vendor's
 * tuned night algorithm (the raw `CONTROL_POST_RAW_SENSITIVITY_BOOST` control is absent on many
 * HALs). Availability is device- and lens-specific and must be queried from an [ExtensionsManager],
 * which is loaded asynchronously from a [ProcessCameraProvider].
 *
 * Because both the `/info.json` UI and the capture backend need the same answer, this object owns
 * the single manager instance and caches per-camera results. Lookups never block: before the
 * manager is ready they report "unsupported", and callers can register via [whenReady] to be told
 * when a late result arrives.
 */
object NightExtensionSupport {
    private const val TAG = "NightExtensionSupport"

    @Volatile private var manager: ExtensionsManager? = null
    @Volatile private var started = false
    @Volatile private var ready = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val readyCallbacks = CopyOnWriteArrayList<() -> Unit>()
    private val byCameraId = ConcurrentHashMap<String, Boolean>()

    /** Kick off the one-time async [ExtensionsManager] load. Safe to call from any thread. */
    fun prime(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        val appCtx = context.applicationContext
        val main = ContextCompat.getMainExecutor(appCtx)
        mainHandler.post {
            try {
                val providerFuture = ProcessCameraProvider.getInstance(appCtx)
                providerFuture.addListener({
                    try {
                        val provider = providerFuture.get()
                        val managerFuture = ExtensionsManager.getInstanceAsync(appCtx, provider)
                        managerFuture.addListener({
                            try {
                                manager = managerFuture.get()
                                ready = true
                                Log.i(TAG, "ExtensionsManager ready")
                                readyCallbacks.forEach { cb -> try { cb() } catch (e: Exception) { Log.w(TAG, "ready cb: ${e.message}") } }
                            } catch (e: Exception) {
                                Log.w(TAG, "ExtensionsManager init failed: ${e.message}")
                            } finally {
                                readyCallbacks.clear()
                            }
                        }, main)
                    } catch (e: Exception) {
                        Log.w(TAG, "ProcessCameraProvider unavailable: ${e.message}")
                    }
                }, main)
            } catch (e: Exception) {
                Log.w(TAG, "prime: ${e.message}")
            }
        }
    }

    fun isReady(): Boolean = ready

    /** Run [cb] once the manager is ready (immediately, on the main thread, if it already is). */
    fun whenReady(cb: () -> Unit) {
        if (ready) { mainHandler.post(cb); return }
        readyCallbacks.add(cb)
    }

    /** True when the OEM NIGHT extension exists for [base]'s camera. */
    fun isExtensionAvailable(base: CameraSelector): Boolean = try {
        manager?.isExtensionAvailable(base, ExtensionMode.NIGHT) == true
    } catch (e: Exception) {
        Log.w(TAG, "isExtensionAvailable: ${e.message}")
        false
    }

    /** True when the extension can feed a YUV [androidx.camera.core.ImageAnalysis] stream, i.e. it
     *  can enhance the streamed video and not merely the on-screen preview / stills. */
    fun isImageAnalysisSupported(base: CameraSelector): Boolean = try {
        manager?.isImageAnalysisSupported(base, ExtensionMode.NIGHT) == true
    } catch (e: Exception) {
        Log.w(TAG, "isImageAnalysisSupported: ${e.message}")
        false
    }

    /** The extension-enabled selector to bind with, or null when NIGHT (with analysis) is
     *  unsupported for [base]. Must be called after [isReady]. */
    fun extensionSelector(base: CameraSelector): CameraSelector? {
        val mgr = manager ?: return null
        return try {
            if (!mgr.isExtensionAvailable(base, ExtensionMode.NIGHT) ||
                !mgr.isImageAnalysisSupported(base, ExtensionMode.NIGHT)) return null
            mgr.getExtensionEnabledCameraSelector(base, ExtensionMode.NIGHT)
        } catch (e: Exception) {
            Log.w(TAG, "extensionSelector: ${e.message}")
            null
        }
    }

    /**
     * Convenience for `/info.json`: builds a selector for a stored camera id (logical id, with any
     * ":physical" suffix ignored, matching how the backend binds) and caches the answer per id.
     */
    fun isSupported(context: Context, cameraId: String?): Boolean {
        prime(context)
        val logicalId = cameraId?.substringBefore(':') ?: return false
        byCameraId[logicalId]?.let { return it }
        val mgr = manager ?: return false
        val supported = try {
            val selector = CameraSelector.Builder()
                .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == logicalId } }
                .build()
            mgr.isExtensionAvailable(selector, ExtensionMode.NIGHT) &&
                mgr.isImageAnalysisSupported(selector, ExtensionMode.NIGHT)
        } catch (e: Exception) {
            Log.w(TAG, "isSupported($logicalId): ${e.message}")
            false
        }
        byCameraId[logicalId] = supported
        return supported
    }
}
