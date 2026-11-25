package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

class NightStillTransformTest {
    @Test
    fun `camera rotation is inverted back to sensor space`() {
        assertEquals(0, NightStillTransform.correctionDegrees(0))
        assertEquals(270, NightStillTransform.correctionDegrees(90))
        assertEquals(180, NightStillTransform.correctionDegrees(180))
        assertEquals(90, NightStillTransform.correctionDegrees(270))
        assertEquals(90, NightStillTransform.correctionDegrees(-90))
        assertEquals(0, NightStillTransform.correctionDegrees(360))
    }
}
