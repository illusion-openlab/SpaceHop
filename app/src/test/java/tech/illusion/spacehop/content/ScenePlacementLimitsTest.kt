package tech.illusion.spacehop.content

import org.junit.Assert.assertEquals
import org.junit.Test

class ScenePlacementLimitsTest {
    @Test
    fun clampScale_clampsToMinMaxRange() {
        assertEquals(0.6f, ScenePlacementLimits.clampScale(0.1f), 0.0001f)
        assertEquals(1.6f, ScenePlacementLimits.clampScale(5f), 0.0001f)
        assertEquals(1.0f, ScenePlacementLimits.clampScale(1.0f), 0.0001f)
    }
}
