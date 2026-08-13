package tech.illusion.spacehop.game

import kotlin.random.Random

/**
 * Generates the next platform's judging geometry from [DifficultyCurve]. Injectable
 * [random] makes this deterministic under test; production code uses the default source.
 */
class PlatformSpawner(private val random: Random = Random.Default) {

    companion object {
        const val MAX_LATERAL_OFFSET_M = 0.08f
    }

    fun spawnNext(index: Int, consecutiveJumps: Int): PlatformSpec {
        val minDistance = DifficultyCurve.minDistanceMeters(consecutiveJumps)
        val maxDistance = DifficultyCurve.maxDistanceMeters(consecutiveJumps)
        val distance = minDistance + random.nextFloat() * (maxDistance - minDistance)
        val footprint = DifficultyCurve.footprintRadiusMeters(consecutiveJumps)
        val perfect = DifficultyCurve.perfectRadiusMeters(consecutiveJumps)
        val lateral = (random.nextFloat() * 2f - 1f) * MAX_LATERAL_OFFSET_M
        return PlatformSpec(
            index = index,
            distanceFromPreviousMeters = distance,
            footprintRadiusMeters = footprint,
            perfectRadiusMeters = perfect,
            lateralOffsetMeters = lateral,
        )
    }
}
