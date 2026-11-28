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

    @Test
    fun `auto grayscale follows the trigger independently of night sight`() {
        // Dark trigger + Auto grayscale: grayscale on even if Night-sight is not selected.
        assertTrue(
            NightMode.effectiveGrayscale(
                follow = true,
                autoModeEnabled = true,
                autoDark = true,
                autoOverride = null,
                manual = false,
                pressedByFlash = false,
            )
        )
        // Light trigger turns grayscale off even when the manual checkbox is on.
        assertFalse(
            NightMode.effectiveGrayscale(
                follow = true,
                autoModeEnabled = true,
                autoDark = false,
                autoOverride = null,
                manual = true,
                pressedByFlash = false,
            )
        )
    }

    @Test
    fun `auto grayscale falls back to the manual setting without a trigger or decision`() {
        assertTrue(
            NightMode.effectiveGrayscale(
                follow = true,
                autoModeEnabled = false,
                autoDark = true,
                autoOverride = null,
                manual = true,
                pressedByFlash = false,
            )
        )
        assertFalse(
            NightMode.effectiveGrayscale(
                follow = true,
                autoModeEnabled = true,
                autoDark = null,
                autoOverride = null,
                manual = false,
                pressedByFlash = false,
            )
        )
        assertTrue(
            NightMode.effectiveGrayscale(
                follow = false,
                autoModeEnabled = true,
                autoDark = false,
                autoOverride = null,
                manual = true,
                pressedByFlash = false,
            )
        )
    }

    @Test
    fun `temporary override wins over the trigger while automation is active`() {
        // Night trigger but the user temporarily turns grayscale off.
        assertFalse(
            NightMode.effectiveGrayscale(
                follow = true,
                autoModeEnabled = true,
                autoDark = true,
                autoOverride = false,
                manual = true,
                pressedByFlash = false,
            )
        )
        // Day trigger but the user temporarily turns grayscale on.
        assertTrue(
            NightMode.effectiveGrayscale(
                follow = true,
                autoModeEnabled = true,
                autoDark = false,
                autoOverride = true,
                manual = false,
                pressedByFlash = false,
            )
        )
    }

    @Test
    fun `temporary night-sight override behaves like the automatic trigger`() {
        // Day trigger, but the user temporarily selects Night-sight.
        assertTrue(
            NightMode.nightSightActive(
                selected = false,
                override = true,
                autoSwitching = true,
                autoModeEnabled = true,
                autoDark = false,
                flashSuppressed = false,
            )
        )
        // Night trigger, but the user temporarily turns Night-sight off.
        assertFalse(
            NightMode.nightSightActive(
                selected = true,
                override = false,
                autoSwitching = true,
                autoModeEnabled = true,
                autoDark = true,
                flashSuppressed = false,
            )
        )
        // Without an override, a stale day selection waits for auto switching to clear it.
        assertFalse(
            NightMode.nightSightActive(
                selected = true,
                override = null,
                autoSwitching = true,
                autoModeEnabled = true,
                autoDark = false,
                flashSuppressed = false,
            )
        )
        // With automatic switching off, the manual selection remains authoritative.
        assertTrue(
            NightMode.nightSightActive(
                selected = true,
                override = null,
                autoSwitching = false,
                autoModeEnabled = true,
                autoDark = false,
                flashSuppressed = false,
            )
        )
        // A lit torch always pauses night-sight.
        assertFalse(
            NightMode.nightSightActive(
                selected = true,
                override = true,
                autoSwitching = true,
                autoModeEnabled = true,
                autoDark = true,
                flashSuppressed = true,
            )
        )
    }

    @Test
    fun `a lit torch suppresses grayscale regardless of its source`() {
        assertFalse(
            NightMode.effectiveGrayscale(
                follow = true,
                autoModeEnabled = true,
                autoDark = true,
                autoOverride = true,
                manual = true,
                pressedByFlash = true,
            )
        )
        assertFalse(
            NightMode.effectiveGrayscale(
                follow = false,
                autoModeEnabled = false,
                autoDark = null,
                autoOverride = null,
                manual = true,
                pressedByFlash = true,
            )
        )
    }
}
