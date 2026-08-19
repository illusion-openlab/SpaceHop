package tech.illusion.spacehop.game

import kotlin.math.abs

/** V2: what a platform slot renders as. [PLATE] is the default kitchen theme; [StageBoard]
 * switches to [LOTUS] for good once the frog has flown through the one-time window crossing
 * (see [PlatformSpec.windowCrossingDistanceMeters]) — the frog has escaped into the lotus pond
 * for the rest of the run. */
enum class PlatformKind { PLATE, LOTUS }

/**
 * One platform's judging geometry, in straight-line distance from the previous platform's
 * centre (design doc §2.2/§2.3). [lateralOffsetMeters] is render-only path variety — the
 * character always auto-aims at the platform centre, so it never affects judging.
 *
 * [windowCrossingDistanceMeters] is non-null on exactly one jump per run: the compound "fly
 * through the window into the lotus pond" hop. It marks the distance, mid-[distanceFromPreviousMeters],
 * at which a one-time decorative window prop sits — the frog's single flight arc passes it
 * without landing there, continuing on to a real [PlatformKind.LOTUS] landing spot at the full
 * distance. That whole jump is a guaranteed success (content/HomeStage.kt), not judged by
 * distance like everything else here — see [StageBoard.advance].
 */
data class PlatformSpec(
    val index: Int,
    val distanceFromPreviousMeters: Float,
    val footprintRadiusMeters: Float,
    val perfectRadiusMeters: Float,
    val lateralOffsetMeters: Float,
    val kind: PlatformKind = PlatformKind.PLATE,
    val windowCrossingDistanceMeters: Float? = null,
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
