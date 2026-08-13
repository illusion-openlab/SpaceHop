package tech.illusion.spacehop.game

import org.junit.Assert.assertEquals
import org.junit.Test

class LandingJudgeTest {

    private fun platform(distance: Float, footprint: Float, perfect: Float) =
        PlatformSpec(
            index = 1,
            distanceFromPreviousMeters = distance,
            footprintRadiusMeters = footprint,
            perfectRadiusMeters = perfect,
            lateralOffsetMeters = 0f,
        )

    @Test
    fun judge_exactDistance_isPerfect() {
        val p = platform(distance = 0.3f, footprint = 0.08f, perfect = 0.03f)
        assertEquals(LandingResult.PERFECT, LandingJudge.judge(launchDistanceMeters = 0.3f, platform = p))
    }

    @Test
    fun judge_withinFootprintButOutsidePerfect_isSuccess() {
        val p = platform(distance = 0.3f, footprint = 0.08f, perfect = 0.03f)
        assertEquals(LandingResult.SUCCESS, LandingJudge.judge(launchDistanceMeters = 0.36f, platform = p))
    }

    @Test
    fun judge_beyondFootprint_isMiss() {
        val p = platform(distance = 0.3f, footprint = 0.08f, perfect = 0.03f)
        assertEquals(LandingResult.MISS, LandingJudge.judge(launchDistanceMeters = 0.41f, platform = p))
        assertEquals(LandingResult.MISS, LandingJudge.judge(launchDistanceMeters = 0.2f, platform = p))
    }

    @Test
    fun judge_isSymmetric_shortAndLongMissEqually() {
        val p = platform(distance = 0.3f, footprint = 0.08f, perfect = 0.03f)
        assertEquals(
            LandingJudge.judge(launchDistanceMeters = 0.3f + 0.09f, platform = p),
            LandingJudge.judge(launchDistanceMeters = 0.3f - 0.09f, platform = p),
        )
    }
}
