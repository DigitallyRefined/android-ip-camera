package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionDetectorCoreTest {
    private val w = MotionDetectorCore.GRID_W
    private val h = MotionDetectorCore.GRID_H

    private fun flat(v: Double) = DoubleArray(w * h) { v }
    private fun withBlob(base: Double, x0: Int, y0: Int, size: Int, v: Double) = flat(base).also { g ->
        for (y in y0 until y0 + size) for (x in x0 until x0 + size) g[y * w + x] = v
    }

    @Test fun staticSceneNeverTriggers() {
        val events = mutableListOf<Boolean>()
        val d = MotionDetectorCore(onMotion = { events += it })
        repeat(50) { d.processGrid(flat(100.0), it * 200L) }
        assertTrue(events.isEmpty())
    }

    @Test fun movingBlobTriggersThenClearsAfterHold() {
        val events = mutableListOf<Boolean>()
        val d = MotionDetectorCore(holdMs = 1_000, onMotion = { events += it })
        d.processGrid(flat(100.0), 0)
        // A person-sized blob moving across the frame for a few frames.
        for (i in 1..5) d.processGrid(withBlob(100.0, i * 4, 8, 6, 200.0), i * 200L)
        assertEquals(listOf(true), events)
        assertTrue(d.active)
        for (i in 6..20) d.processGrid(flat(100.0), i * 200L)
        assertEquals(listOf(true, false), events)
        assertFalse(d.active)
    }

    @Test fun globalBrightnessShiftIsIgnored() {
        val events = mutableListOf<Boolean>()
        val d = MotionDetectorCore(onMotion = { events += it })
        d.processGrid(flat(100.0), 0)
        for (i in 1..10) d.processGrid(flat(100.0 + i * 8), i * 200L)   // auto-exposure ramp
        assertTrue(events.isEmpty())
    }

    @Test fun lightSwitchReseedsInsteadOfTriggering() {
        val events = mutableListOf<Boolean>()
        val d = MotionDetectorCore(onMotion = { events += it })
        d.processGrid(flat(40.0), 0)
        // Lamp on: most of the frame brightens unevenly (left much more than right).
        val lit = DoubleArray(w * h) { i -> if (i % w < w * 3 / 4) 200.0 else 60.0 }
        for (i in 1..5) d.processGrid(lit, i * 200L)
        assertTrue(events.isEmpty())
    }

    @Test fun offFrameTvScenesAreIgnored() {
        val events = mutableListOf<Boolean>()
        val d = MotionDetectorCore(onMotion = { events += it })
        d.processGrid(flat(40.0), 0)
        // TV light flickering across the room: big, uneven brightness jumps frame to frame.
        val levels = listOf(40.0, 15.0, 15.0, 95.0, 90.0, 25.0, 40.0, 110.0, 30.0, 40.0)
        levels.forEachIndexed { i, v ->
            val g = DoubleArray(w * h) { c -> v * (0.6 + 0.8 * (c % w) / w) }   // falls off across the room
            d.processGrid(g, (i + 1) * 200L)
        }
        assertTrue(events.isEmpty())
    }

    @Test fun motionStillDetectedAfterLightingChange() {
        val events = mutableListOf<Boolean>()
        val d = MotionDetectorCore(onMotion = { events += it })
        d.processGrid(flat(40.0), 0)
        d.processGrid(flat(90.0), 200)                       // lamp on
        for (i in 2..8) d.processGrid(flat(90.0), i * 200L)  // settle past the hold-off
        for (i in 9..12) d.processGrid(withBlob(90.0, i * 3, 8, 6, 200.0), i * 200L)
        assertEquals(listOf(true), events)
    }

    @Test fun singleNoisyFrameIsNotMotion() {
        val events = mutableListOf<Boolean>()
        val d = MotionDetectorCore(onMotion = { events += it })
        d.processGrid(flat(100.0), 0)
        d.processGrid(withBlob(100.0, 10, 10, 6, 220.0), 200)
        d.processGrid(flat(100.0), 400)
        assertTrue(events.isEmpty())
    }
}
