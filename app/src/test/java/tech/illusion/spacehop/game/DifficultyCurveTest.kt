package tech.illusion.spacehop.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DifficultyCurveTest {

    @Test
    fun minDistanceMeters_staysConstant_neverBelowFloor() {
        assertEquals(DifficultyCurve.MIN_DISTANCE_FLOOR_M, DifficultyCurve.minDistanceMeters(0), 0.0001f)
        assertEquals(DifficultyCurve.MIN_DISTANCE_FLOOR_M, DifficultyCurve.minDistanceMeters(200), 0.0001f)
    }

    @Test
    fun maxDistanceMeters_growsWithConsecutiveJumps_thenClampsAtCeiling() {
        val early = DifficultyCurve.maxDistanceMeters(0)
        val later = DifficultyCurve.maxDistanceMeters(20)
        assertTrue(later > early)
        assertEquals(DifficultyCurve.MAX_DISTANCE_CEILING_M, DifficultyCurve.maxDistanceMeters(10_000), 0.0001f)
    }

    @Test
    fun footprintRadiusMeters_shrinksWithConsecutiveJumps_thenClampsAtFloor() {
        val early = DifficultyCurve.footprintRadiusMeters(0)
        val later = DifficultyCurve.footprintRadiusMeters(20)
        assertTrue(later < early)
        assertEquals(DifficultyCurve.FOOTPRINT_RADIUS_FLOOR_M, DifficultyCurve.footprintRadiusMeters(10_000), 0.0001f)
    }

    @Test
    fun perfectRadiusMeters_isAlwaysSmallerThanFootprint() {
        for (jumps in listOf(0, 5, 20, 100)) {
            assertTrue(DifficultyCurve.perfectRadiusMeters(jumps) < DifficultyCurve.footprintRadiusMeters(jumps))
        }
    }

    @Test
    fun minDistance_isAlwaysLessThanMaxDistance() {
        for (jumps in listOf(0, 5, 20, 100, 10_000)) {
            assertTrue(DifficultyCurve.minDistanceMeters(jumps) < DifficultyCurve.maxDistanceMeters(jumps))
        }
    }
}
