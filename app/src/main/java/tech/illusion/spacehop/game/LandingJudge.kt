package tech.illusion.spacehop.game

import kotlin.math.abs

/**
 * One platform's judging geometry, in straight-line distance from the previous platform's
 * centre (design doc §2.2/§2.3). [lateralOffsetMeters] is render-only path variety — the
 * character always auto-aims at the platform centre, so it never affects judging.
 */
data class PlatformSpec(
    val index: Int,
    val distanceFromPreviousMeters: Float,
    val footprintRadiusMeters: Float,
    val perfectRadiusMeters: Float,
    val lateralOffsetMeters: Float,
)

enum class LandingResult { PERFECT, SUCCESS, MISS }

object LandingJudge {

    fun judge(launchDistanceMeters: Float, platform: PlatformSpec): LandingResult {
        val offset = abs(launchDistanceMeters - platform.distanceFromPreviousMeters)
        return when {
            offset <= platform.perfectRadiusMeters -> LandingResult.PERFECT
            offset <= platform.footprintRadiusMeters -> LandingResult.SUCCESS
            else -> LandingResult.MISS
        }
    }
}
