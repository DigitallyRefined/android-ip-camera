package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraXBindingPolicyTest {
    @Test
    fun `the full request is tried first`() {
        val candidates = CameraXBindingPolicy.candidates(display = true, encoder = true, still = true)
        assertEquals(CameraXBinding(true, true, true), candidates.first())
    }

    @Test
    fun `the encoder surface is kept before any optional use case is dropped`() {
        val candidates = CameraXBindingPolicy.candidates(display = true, encoder = true, still = true)
        val firstWithoutEncoder = candidates.indexOfFirst { !it.encoder }
        assertTrue(firstWithoutEncoder > 0)
        assertTrue(candidates.take(firstWithoutEncoder).all { it.encoder })
    }

    @Test
    fun `the on-phone preview is kept before full-resolution stills are dropped`() {
        val candidates = CameraXBindingPolicy.candidates(display = true, encoder = true, still = true)
        val keepEncoder = candidates.filter { it.encoder }
        val dropStill = keepEncoder.first { !it.still }
        assertTrue(dropStill.display)
        assertTrue(keepEncoder.indexOf(dropStill) < keepEncoder.indexOfFirst { !it.display })
    }

    @Test
    fun `every requested subset is represented and no duplicate attempts are made`() {
        val candidates = CameraXBindingPolicy.candidates(display = true, encoder = true, still = true)
        assertEquals(candidates.distinct(), candidates)
        assertEquals(8, candidates.size)
        assertEquals(
            setOf(
                CameraXBinding(true, true, true),
                CameraXBinding(true, true, false),
                CameraXBinding(false, true, true),
                CameraXBinding(false, true, false),
                CameraXBinding(true, false, true),
                CameraXBinding(true, false, false),
                CameraXBinding(false, false, true),
                CameraXBinding(false, false, false),
            ),
            candidates.toSet(),
        )
    }

    @Test
    fun `use cases that were not requested are never bound`() {
        val candidates = CameraXBindingPolicy.candidates(display = false, encoder = true, still = false)
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.none { it.display })
        assertTrue(candidates.none { it.still })
        assertTrue(candidates.any { it.encoder })
        assertFalse(candidates.first().display)
    }

    @Test
    fun `a request with no optional use cases has one attempt`() {
        assertEquals(
            listOf(CameraXBinding(false, false, false)),
            CameraXBindingPolicy.candidates(display = false, encoder = false, still = false),
        )
    }
}
