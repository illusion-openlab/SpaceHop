package tech.illusion.spacehop.game

import org.junit.Assert.assertEquals
import org.junit.Test

class ChargeIndicatorColorTest {

    @Test
    fun forFraction_atZero_isGreen() {
        val rgb = ChargeIndicatorColor.forFraction(0f)
        assertEquals(0f, rgb[0], 0.001f)
        assertEquals(1f, rgb[1], 0.001f)
        assertEquals(0f, rgb[2], 0.001f)
    }

    @Test
    fun forFraction_atOne_isRed() {
        val rgb = ChargeIndicatorColor.forFraction(1f)
        assertEquals(1f, rgb[0], 0.001f)
        assertEquals(0f, rgb[1], 0.001f)
        assertEquals(0f, rgb[2], 0.001f)
    }

    @Test
    fun forFraction_atHalf_isBetweenGreenAndRed() {
        val rgb = ChargeIndicatorColor.forFraction(0.5f)
        assertEquals(0.5f, rgb[0], 0.001f)
        assertEquals(0.5f, rgb[1], 0.001f)
        assertEquals(0f, rgb[2], 0.001f)
    }

    @Test
    fun forFraction_clampsOutOfRangeInput() {
        assertEquals(0f, ChargeIndicatorColor.forFraction(-1f)[0], 0.001f)
        assertEquals(1f, ChargeIndicatorColor.forFraction(2f)[0], 0.001f)
    }
}
