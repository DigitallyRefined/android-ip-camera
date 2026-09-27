package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowLightTest {
    private fun luma(b: Byte): Int = b.toInt() and 0xFF

    @Test
    fun `off profile is the identity curve`() {
        val tone = LowLight.profile(0).tone
        assertTrue(tone.isIdentity)

        val lut = tone.toLut()
        for (i in 0..255) assertEquals(i, luma(lut[i]))
    }

    @Test
    fun `tone curve lifts the shadows it is meant to develop`() {
        val tone = LowLight.profile(4).tone
        val lut = tone.toLut()

        // The point of the feature: a near-black value has to come out well clear of the floor.
        assertTrue("luma 20 was ${luma(lut[20])}", luma(lut[20]) > 20 * 3)
        // Highlights stay put instead of being blown out.
        assertTrue(luma(lut[255]) >= 254)
    }

    @Test
    fun `tone curve does not blow the midtones out to white`() {
        // Regression guard: an earlier version applied pow() to the un-normalised 0..255 value with
        // an inverted (1/gamma) exponent, so anything above the black level ran past 255 and every
        // level saturated the whole frame to solid white — L4 clipped 237 of 256 inputs. The curve
        // must be a graded lift, not a hard clip.
        //
        // The shadow/midtone range is what matters: saturating the top of the range is intended (a
        // dim room is supposed to end up bright), but a scene that is genuinely dark must stay a
        // readable gradient rather than turning into a white rectangle.
        for (level in 1..LowLight.MAX_LEVEL) {
            val lut = LowLight.profile(level).tone.toLut()
            for (input in 20..80) {
                assertTrue(
                    "L$level clipped luma $input to white",
                    luma(lut[input]) < 255,
                )
                assertTrue(
                    "L$level darkened luma $input (${luma(lut[input])})",
                    luma(lut[input]) > input,
                )
            }
        }
    }

    @Test
    fun `tone curve maps the 0 to 255 scale with a soft shoulder`() {
        val lut = LowLight.profile(4).tone.toLut()
        // black level and below are the floor; the top of the range is the ceiling; the interesting
        // behaviour is in between, where the curve should climb smoothly rather than saturate.
        assertEquals(0, luma(lut[14]))
        assertEquals(255, luma(lut[255]))
        assertTrue(luma(lut[30]) in 31..255)
        assertTrue(luma(lut[60]) in 61..255)
    }

    @Test
    fun `tone curve is monotone so gradients do not band or invert`() {
        val lut = LowLight.profile(3).tone.toLut()
        for (i in 1..255) {
            assertTrue(
                "lut not monotone at $i: ${luma(lut[i - 1])} -> ${luma(lut[i])}",
                luma(lut[i]) >= luma(lut[i - 1]),
            )
        }
    }

    @Test
    fun `tone curve does not annihilate pixels above the black level`() {
        // Regression guard for the scale mismatch that blacked out the whole GL stream: the GL
        // shader samples a 0..1 colour while blackLevel is a 0..255 luma value, so applying the
        // curve to the un-scaled luma drove (luma - blackLevel) negative and clamped every pixel to
        // zero. Whatever curve the GPU mirrors, it must lift — not erase — every input above the
        // black level, on the same 0..255 scale the LUT works in.
        for (level in 1..LowLight.MAX_LEVEL) {
            val tone = LowLight.profile(level).tone
            val lut = tone.toLut()
            for (input in (tone.blackLevel + 1)..255) {
                assertTrue(
                    "L$level erased luma $input (blackLevel=${tone.blackLevel})",
                    luma(lut[input]) > 0,
                )
            }
            assertTrue("L$level did not lift luma 20", luma(lut[20]) > 20)
        }
    }

    @Test
    fun `tone curve is defined on the 0 to 255 luma scale`() {
        // A black level is meaningless outside 0..255; if a profile ever started working in 0..1 the
        // GL shader and the CPU LUT would silently diverge again.
        LowLight.PROFILES.filter { it.level > 0 }.forEach {
            assertTrue("blackLevel ${it.tone.blackLevel} out of range", it.tone.blackLevel in 0..255)
            assertTrue("gain ${it.tone.gain} not a gain", it.tone.gain >= 1f)
            assertTrue("gamma ${it.tone.gamma} not a lift", it.tone.gamma in 0.05f..1f)
        }
    }

    @Test
    fun `each level roughly doubles the exposure`() {
        val shutters = (1..LowLight.MAX_LEVEL).map { LowLight.profile(it).maxShutterMs }
        assertEquals(listOf(66, 125, 250, 1000), shutters)
    }

    @Test
    fun `out of range levels clamp instead of throwing`() {
        assertEquals(0, LowLight.profile(-5).level)
        assertEquals(LowLight.MAX_LEVEL, LowLight.profile(99).level)
    }

    @Test
    fun `applyLut only touches the requested run`() {
        val lut = LowLight.profile(4).tone.toLut()
        val plane = ByteArray(16) { 20 }

        LowLight.applyLut(plane, 4, 4, lut)

        // Untouched bytes keep their original value, the mapped run does not.
        for (i in 0 until 4) assertEquals(20, luma(plane[i]))
        for (i in 4 until 8) assertTrue(luma(plane[i]) > 20)
        for (i in 8 until 16) assertEquals(20, luma(plane[i]))
    }

    @Test
    fun `applyLut ignores runs that fall outside the plane`() {
        val lut = LowLight.profile(4).tone.toLut()
        val plane = ByteArray(8) { 20 }

        LowLight.applyLut(plane, 6, 10, lut)
        LowLight.applyLut(plane, -1, 4, lut)

        for (i in 0..7) assertEquals(20, luma(plane[i]))
    }

    @Test
    fun `maxUsableLevel stops where the sensor cannot go`() {
        // Slowest advertised ceiling is 7 fps, so 8 fps is reachable but 4 fps and 1 fps are not.
        val caps = LowLight.Caps(maxExposureMs = 1000, maxIso = 1600, boostUpper = 400, minFpsUpper = 7, manualSensor = false)
        assertEquals(2, caps.maxUsableLevel())

        // A short exposure range caps the ladder even when the fps ceiling would allow more.
        val shortShutter = LowLight.Caps(maxExposureMs = 100, maxIso = 1600, boostUpper = 400, minFpsUpper = 1, manualSensor = false)
        assertEquals(1, shortShutter.maxUsableLevel())

        // A camera that cannot go below 30 fps supports no level at all.
        val fullSpeedOnly = LowLight.Caps(maxExposureMs = 1000, maxIso = 1600, boostUpper = 100, minFpsUpper = 30, manualSensor = false)
        assertEquals(0, fullSpeedOnly.maxUsableLevel())
    }

    @Test
    fun `boost is only claimed when the HAL exposes a range above 100`() {
        assertFalse(LowLight.Caps.UNKNOWN.hasBoost)
        assertTrue(LowLight.Caps(1000, 1600, boostUpper = 400, minFpsUpper = 7, manualSensor = false).hasBoost)
    }

    @Test
    fun `fps selection prefers a range whose ceiling actually caps the frame rate`() {
        // A typical phone: [0,30], [7.5,7.5], [15,30] in whole fps. Asking for 8 fps must not settle
        // on [0, 30], which would leave the HAL free to ignore the request and stream at 30 fps.
        val advertised = listOf(
            LowLight.FpsRange(0, 30),
            LowLight.FpsRange(7, 7),
            LowLight.FpsRange(15, 30),
        )
        val picked = LowLight.selectFpsRange(advertised, 8)

        assertEquals(7, picked!!.upper)
    }

    @Test
    fun `fps selection takes an exact ceiling when the camera offers one`() {
        val advertised = listOf(
            LowLight.FpsRange(0, 30),
            LowLight.FpsRange(4, 4),
            LowLight.FpsRange(8, 8),
        )
        assertEquals(8, LowLight.selectFpsRange(advertised, 8)!!.upper)
        assertEquals(4, LowLight.selectFpsRange(advertised, 4)!!.upper)
    }

    @Test
    fun `fps selection falls back to the slowest range on a slow camera`() {
        // Only 15 fps and 30 fps exist, so a 4 fps request cannot be honoured; the level should
        // still ask for the slowest thing available rather than nothing.
        val advertised = listOf(LowLight.FpsRange(15, 15), LowLight.FpsRange(30, 30))
        val picked = LowLight.selectFpsRange(advertised, 4)

        assertEquals(15, picked!!.upper)
    }
    @Test
    fun `fps selection returns null when the camera advertises nothing`() {
        assertEquals(null, LowLight.selectFpsRange(emptyList(), 8))
    }

    @Test
    fun `level and the ceiling it reports agree on what the camera can do`() {
        // 7 fps is the slowest ceiling, so levels 1 and 2 (15 and 8 fps) are reachable and 3 and 4
        // are not. Reporting a level whose range would be silently ignored is the bug this guards.
        val caps = LowLight.Caps(1000, 1600, 400, minFpsUpper = 7, manualSensor = false)
        val advertised = listOf(LowLight.FpsRange(0, 30), LowLight.FpsRange(7, 7), LowLight.FpsRange(15, 30))

        for (level in 1..caps.maxUsableLevel()) {
            val target = LowLight.profile(level).targetFps
            assertTrue(
                "level $level claims $target fps but the camera cannot get below ${caps.minFpsUpper}",
                caps.minFpsUpper <= target,
            )
            assertTrue(LowLight.selectFpsRange(advertised, target) != null)
        }
    }

    @Test
    fun `every profile maps to a real level`() {
        LowLight.PROFILES.forEachIndexed { index, profile ->
            assertEquals(index, profile.level)
        }
        assertEquals(LowLight.MAX_LEVEL, LowLight.PROFILES.size - 1)
    }
}
