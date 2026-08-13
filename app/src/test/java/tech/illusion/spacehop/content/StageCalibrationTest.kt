package tech.illusion.spacehop.content

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageCalibrationTest {

    @Test
    fun isPlausibleHeadHeight_withinAdultEyeHeightRange_isTrue() {
        assertTrue(StageCalibration.isPlausibleHeadHeight(1.15f))
        assertTrue(StageCalibration.isPlausibleHeadHeight(StageCalibration.PLAUSIBLE_HEAD_HEIGHT_MIN_M))
        assertTrue(StageCalibration.isPlausibleHeadHeight(StageCalibration.PLAUSIBLE_HEAD_HEIGHT_MAX_M))
    }

    @Test
    fun isPlausibleHeadHeight_zeroSentinel_isFalse() {
        assertFalse(StageCalibration.isPlausibleHeadHeight(0f))
    }

    @Test
    fun isPlausibleHeadHeight_belowOrAboveRange_isFalse() {
        assertFalse(StageCalibration.isPlausibleHeadHeight(0.3f))
        assertFalse(StageCalibration.isPlausibleHeadHeight(2.6f))
    }
}
