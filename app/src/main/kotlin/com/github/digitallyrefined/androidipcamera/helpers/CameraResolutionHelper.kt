package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.Size

class CameraResolutionHelper(private val context: Context) {
    var highResolution: Size? = null
    var mediumResolution: Size? = null
    var lowResolution: Size? = null

    fun initializeResolutions(cameraId: String) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map != null) {
                val supportedSizes = map.getOutputSizes(ImageFormat.YUV_420_888)
                Log.i(TAG, "Camera $cameraId supports ${supportedSizes.size} resolutions")
                if (supportedSizes.isNotEmpty()) {
                    val sortedSizes = supportedSizes.sortedByDescending { it.width * it.height }
                    Log.i(TAG, "Available resolutions for camera $cameraId:")
                    sortedSizes.forEachIndexed { index, size ->
                        Log.i(TAG, "  ${index + 1}. ${size.width}x${size.height} (${size.width * size.height} pixels)")
                    }
                    when {
                        sortedSizes.size >= 3 -> {
                            // High: use a resolution around 1280x720 or smaller
                            highResolution = sortedSizes.find { it.width <= 1280 && it.height <= 720 }
                                ?: sortedSizes.find { it.width <= 960 && it.height <= 720 }
                                ?: sortedSizes[sortedSizes.size / 3]
                            // Medium: use a resolution around 960x720 or smaller, but smaller than high
                            val highRes = highResolution
                            mediumResolution = sortedSizes.find {
                                it.width <= 960 && it.height <= 720 &&
                                (highRes == null || (it.width * it.height) < (highRes.width * highRes.height))
                            } ?: sortedSizes.find {
                                it.width <= 800 && it.height <= 600 &&
                                (highRes == null || (it.width * it.height) < (highRes.width * highRes.height))
                            } ?: sortedSizes[sortedSizes.size * 2 / 3]
                            // Low: use a resolution smaller than medium, more conservative
                            val mediumRes = mediumResolution
                            lowResolution = sortedSizes.find {
                                it.width <= 640 && it.height <= 480 &&
                                (mediumRes == null || (it.width * it.height) < (mediumRes.width * mediumRes.height))
                            } ?: sortedSizes.find {
                                it.width <= 800 && it.height <= 600 &&
                                (mediumRes == null || (it.width * it.height) < (mediumRes.width * mediumRes.height))
                            } ?: sortedSizes[sortedSizes.size - 2].takeIf { sortedSizes.size > 1 }
                                ?: sortedSizes.last()
                        }
                        sortedSizes.size == 2 -> {
                            // For 2 resolutions, use the larger for high, smaller for medium, and use a conservative low
                            highResolution = sortedSizes[0]
                            mediumResolution = sortedSizes[1]
                            lowResolution = Size(640, 480)
                        }
                        else -> {
                            // Only one resolution available
                            highResolution = sortedSizes[0]
                            mediumResolution = sortedSizes[0]
                            lowResolution = sortedSizes[0]
                        }
                    }
                    Log.i(TAG, "Selected resolutions for camera $cameraId:")
                    Log.i(TAG, "  High: ${highResolution?.width}x${highResolution?.height}")
                    Log.i(TAG, "  Medium: ${mediumResolution?.width}x${mediumResolution?.height}")
                    Log.i(TAG, "  Low: ${lowResolution?.width}x${lowResolution?.height}")
                } else {
                    Log.w(TAG, "No supported resolutions found for camera $cameraId")
                }
            } else {
                Log.w(TAG, "No stream configuration map found for camera $cameraId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing resolutions for camera $cameraId: ${e.message}")
        }
    }

    fun getResolutionForQuality(quality: String): Size? {
        return when (quality) {
            "high" -> highResolution
            "medium" -> mediumResolution
            "low" -> lowResolution
            else -> lowResolution
        }
    }

    companion object {
        private const val TAG = "CameraResolutionHelper"
    }
}
