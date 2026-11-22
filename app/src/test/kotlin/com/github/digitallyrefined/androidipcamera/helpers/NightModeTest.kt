package com.github.digitallyrefined.androidipcamera.helpers

import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightModeTest {
    @Test
    fun `night scene mode is supported`() {
        assertTrue(
            NightMode.supports(
                sceneModes = intArrayOf(CameraMetadata.CONTROL_SCENE_MODE_NIGHT),
                aeModes = null,
                nightExtension = false,
                lowLightBoost = false,
            )
        )
    }

    @Test
    fun `night portrait scene mode is supported`() {
        assertTrue(
            NightMode.supports(
                sceneModes = intArrayOf(CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT),
                aeModes = null,
                nightExtension = false,
                lowLightBoost = false,
            )
        )
    }

    @Test
    fun `ordinary scene modes alone are not night mode`() {
        assertFalse(
            NightMode.supports(
                sceneModes = intArrayOf(
                    CameraMetadata.CONTROL_SCENE_MODE_DISABLED,
                    CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY,
                ),
                aeModes = intArrayOf(CameraMetadata.CONTROL_AE_MODE_ON),
                nightExtension = false,
                lowLightBoost = false,
            )
        )
        assertFalse(
            NightMode.supports(
                sceneModes = null,
                aeModes = null,
                nightExtension = false,
                lowLightBoost = false,
            )
        )
    }

    @Test
    fun `camera extension or low light ae boost counts as night mode`() {
        assertTrue(
            NightMode.supports(
                sceneModes = null,
                aeModes = null,
                nightExtension = true,
                lowLightBoost = false,
            )
        )
        assertTrue(
            NightMode.supports(
                sceneModes = null,
                aeModes = null,
                nightExtension = false,
                lowLightBoost = true,
            )
        )
        assertTrue(
            NightMode.supports(
                sceneModes = null,
                aeModes = intArrayOf(
                    CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY,
                ),
                nightExtension = false,
                lowLightBoost = false,
            )
        )
    }

    @Test
    fun `manual night defaults are bright but inside the sensor range`() {
        val wide = NightMode.ManualExposure(minExposureMs = 1, maxExposureMs = 1000, minIso = 50, maxIso = 6400)
        assertEquals(1000, wide.defaultExposureMs)
        assertEquals(3200, wide.defaultIso)

        val narrow = NightMode.ManualExposure(minExposureMs = 100, maxExposureMs = 200, minIso = 4000, maxIso = 8000)
        assertEquals(200, narrow.defaultExposureMs)
        assertEquals(4000, narrow.defaultIso)
    }
}
