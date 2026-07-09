package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateLimiterTest {
    @Test
    fun `paces a 30 fps source to 10 fps`() {
        val limiter = FrameRateLimiter(10)
        val emitted = (0 until 90).count { frame ->
            limiter.shouldEmit(frame * 1_000_000_000L / 30)
        }

        assertTrue(emitted in 30..31)
    }

    @Test
    fun `keeps accurate average with a 25 fps source`() {
        val limiter = FrameRateLimiter(10)
        val emitted = (0 until 250).count { frame ->
            limiter.shouldEmit(frame * 1_000_000_000L / 25)
        }

        assertTrue(emitted in 100..101)
    }

    @Test
    fun `resets when camera timestamps move backwards`() {
        val limiter = FrameRateLimiter(10)
        assertTrue(limiter.shouldEmit(1_000_000_000L))
        limiter.shouldEmit(1_050_000_000L)

        assertTrue(limiter.shouldEmit(10L))
    }

    @Test
    fun `clamps invalid target to one frame per second`() {
        val limiter = FrameRateLimiter(0)
        val emitted = (0..20).count { limiter.shouldEmit(it * 100_000_000L) }

        assertEquals(3, emitted)
    }
}
