package tech.illusion.spacehop.game

import org.junit.Assert.assertEquals
import org.junit.Test

class ChargeCurveTest {

    @Test
    fun cappedChargeMillis_clampsToRangeZeroToMax() {
        assertEquals(0L, ChargeCurve.cappedChargeMillis(-100L))
        assertEquals(600L, ChargeCurve.cappedChargeMillis(600L))
        assertEquals(ChargeCurve.MAX_CHARGE_MILLIS, ChargeCurve.cappedChargeMillis(5_000L))
    }

    @Test
    fun launchDistanceMeters_atZeroHeld_returnsMinDistance() {
        val d = ChargeCurve.launchDistanceMeters(0L, minDistance = 0.2f, maxDistance = 0.5f)
        assertEquals(0.2f, d, 0.001f)
    }

    @Test
    fun launchDistanceMeters_atMaxHeld_returnsMaxDistance() {
        val d = ChargeCurve.launchDistanceMeters(
            ChargeCurve.MAX_CHARGE_MILLIS,
            minDistance = 0.2f,
            maxDistance = 0.5f,
        )
        assertEquals(0.5f, d, 0.001f)
    }

    @Test
    fun launchDistanceMeters_easesOut_growsFasterEarlyThanLate() {
        val quarter = ChargeCurve.launchDistanceMeters(ChargeCurve.MAX_CHARGE_MILLIS / 4, 0f, 1f)
        val half = ChargeCurve.launchDistanceMeters(ChargeCurve.MAX_CHARGE_MILLIS / 2, 0f, 1f)
        val threeQuarters = ChargeCurve.launchDistanceMeters(ChargeCurve.MAX_CHARGE_MILLIS * 3 / 4, 0f, 1f)
        // ease-out quadratic: the first quarter of hold time should cover more distance
        // than the last quarter.
        val firstQuarterGain = quarter - 0f
        val lastQuarterGain = 1f - threeQuarters
        org.junit.Assert.assertTrue(firstQuarterGain > lastQuarterGain)
        org.junit.Assert.assertTrue(quarter < half)
        org.junit.Assert.assertTrue(half < threeQuarters)
    }
}
