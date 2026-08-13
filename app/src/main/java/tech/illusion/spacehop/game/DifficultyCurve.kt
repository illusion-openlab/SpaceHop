package tech.illusion.spacehop.game

/**
 * Difficulty knob for the endless run, keyed by consecutive successful jumps (not score).
 * Starter values per design doc §2.3 — explicitly tunable on real-device playtesting, not
 * final. Every curve has a clamp so the run never becomes literally impossible or literally
 * static.
 */
object DifficultyCurve {

    const val MIN_DISTANCE_FLOOR_M = 0.18f

    private const val BASE_MAX_DISTANCE_M = 0.32f
    private const val DISTANCE_GROWTH_PER_JUMP_M = 0.01f
    const val MAX_DISTANCE_CEILING_M = 0.55f

    private const val BASE_FOOTPRINT_RADIUS_M = 0.09f
    private const val FOOTPRINT_SHRINK_PER_JUMP_M = 0.0015f
    const val FOOTPRINT_RADIUS_FLOOR_M = 0.045f

    private const val PERFECT_RADIUS_FRACTION = 0.35f

    fun minDistanceMeters(consecutiveJumps: Int): Float = MIN_DISTANCE_FLOOR_M

    fun maxDistanceMeters(consecutiveJumps: Int): Float =
        (BASE_MAX_DISTANCE_M + DISTANCE_GROWTH_PER_JUMP_M * consecutiveJumps).coerceAtMost(MAX_DISTANCE_CEILING_M)

    fun footprintRadiusMeters(consecutiveJumps: Int): Float =
        (BASE_FOOTPRINT_RADIUS_M - FOOTPRINT_SHRINK_PER_JUMP_M * consecutiveJumps)
            .coerceAtLeast(FOOTPRINT_RADIUS_FLOOR_M)

    fun perfectRadiusMeters(consecutiveJumps: Int): Float =
        footprintRadiusMeters(consecutiveJumps) * PERFECT_RADIUS_FRACTION
}
