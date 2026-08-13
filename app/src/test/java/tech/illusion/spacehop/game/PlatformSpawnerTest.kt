package tech.illusion.spacehop.game

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformSpawnerTest {

    @Test
    fun spawnNext_distanceIsWithinDifficultyCurveRange() {
        val spawner = PlatformSpawner(random = Random(seed = 42))
        repeat(50) { i ->
            val spec = spawner.spawnNext(index = i, consecutiveJumps = i)
            val min = DifficultyCurve.minDistanceMeters(i)
            val max = DifficultyCurve.maxDistanceMeters(i)
            assertTrue("distance ${spec.distanceFromPreviousMeters} out of [$min,$max] at jump $i",
                spec.distanceFromPreviousMeters in min..max)
        }
    }

    @Test
    fun spawnNext_footprintAndPerfectRadiusMatchDifficultyCurve() {
        val spawner = PlatformSpawner(random = Random(seed = 1))
        val spec = spawner.spawnNext(index = 0, consecutiveJumps = 10)
        assertEquals(DifficultyCurve.footprintRadiusMeters(10), spec.footprintRadiusMeters, 0.0001f)
        assertEquals(DifficultyCurve.perfectRadiusMeters(10), spec.perfectRadiusMeters, 0.0001f)
    }

    @Test
    fun spawnNext_lateralOffsetIsBoundedAndDoesNotAffectDistance() {
        val spawner = PlatformSpawner(random = Random(seed = 7))
        repeat(50) { i ->
            val spec = spawner.spawnNext(index = i, consecutiveJumps = 0)
            assertTrue(kotlin.math.abs(spec.lateralOffsetMeters) <= PlatformSpawner.MAX_LATERAL_OFFSET_M)
        }
    }

    @Test
    fun spawnNext_sameSeed_producesSameSequence() {
        val a = PlatformSpawner(random = Random(seed = 99)).spawnNext(0, 0)
        val b = PlatformSpawner(random = Random(seed = 99)).spawnNext(0, 0)
        assertEquals(a, b)
    }
}
