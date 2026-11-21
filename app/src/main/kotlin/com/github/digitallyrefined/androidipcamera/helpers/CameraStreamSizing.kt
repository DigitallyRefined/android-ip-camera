package com.github.digitallyrefined.androidipcamera.helpers

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaCodec

/** The camera output path that determines which frame sizes a lens can actually deliver. */
enum class CameraOutputStream {
    /** CameraX Preview into the GPU pipe / encoder surface (rear FULL/3 cameras). */
    SURFACE,

    /** CameraX ImageAnalysis YUV frames (front cameras and LIMITED devices). */
    YUV,

    /** Legacy Camera1 preview into the GPU pipe. */
    CAMERA1,
}

/**
 * Keeps the size list shown in `/info.json` and the size requested from the capture backend in
 * sync with the path that the selected lens will actually use.
 *
 * The three paths do not advertise the same sizes: a front camera may list 1920x1080 for its
 * preview surface while only exposing YUV sizes up to 1280x720 for ImageAnalysis. Reporting the
 * union of every map (and then requesting 1920x1080 anyway) made the UI and ONVIF status claim a
 * resolution the encoder never produced.
 */
object CameraStreamSizing {
    /**
     * The path [StreamingService] uses for this lens. Mirrors `chooseApi()`/`supportsSurfaceEncoder()`:
     * Camera1 on LEGACY HALs (or when explicitly selected), the GPU surface path for rear FULL/3
     * cameras, and ImageAnalysis YUV everywhere else.
     */
    fun outputPath(front: Boolean, hardwareLevel: Int?, captureApi: String?): CameraOutputStream {
        val api = captureApi?.trim()?.lowercase()
        if (api == "camera1") return CameraOutputStream.CAMERA1
        val legacy = hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
        if (api != "camerax" && legacy) return CameraOutputStream.CAMERA1
        val surface = !front && (
            hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL ||
                hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3
        )
        return if (surface) CameraOutputStream.SURFACE else CameraOutputStream.YUV
    }

    /**
     * Sizes the output path can deliver for [characteristics], or null when the path has no
     * enumerable list here (Camera1 sizes come from the legacy API instead).
     */
    fun outputSizes(
        characteristics: CameraCharacteristics,
        path: CameraOutputStream,
    ): List<Pair<Int, Int>>? {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return null
        val sizes = when (path) {
            CameraOutputStream.SURFACE -> buildList {
                try {
                    map.getOutputSizes(SurfaceTexture::class.java)?.forEach { add(it.width to it.height) }
                } catch (_: Exception) {}
                try {
                    map.getOutputSizes(MediaCodec::class.java)?.forEach { add(it.width to it.height) }
                } catch (_: Exception) {}
            }
            CameraOutputStream.YUV -> try {
                map.getOutputSizes(ImageFormat.YUV_420_888)?.map { it.width to it.height }.orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
            CameraOutputStream.CAMERA1 -> return null
        }
        return sizes.distinct()
    }
}
