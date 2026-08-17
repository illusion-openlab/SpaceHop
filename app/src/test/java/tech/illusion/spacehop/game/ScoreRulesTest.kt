package tech.illusion.spacehop.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreRulesTest {

    @Test
    fun applyLanding_perfect_incrementsStreakAndScoresBaseNPlusBonus() {
        val state = ScoreRules.applyLanding(RunState(), LandingResult.PERFECT)
        assertEquals(1, state.comboStreak)
        assertEquals(2, state.score) // base 1 + comboBonus(streak=1) = 1
        assertTrue(state.isAlive)
        assertEquals(1, state.jumpsCompleted)
    }

    @Test
    fun applyLanding_consecutivePerfects_growComboBonus() {
        var state = RunState()
        state = ScoreRules.applyLanding(state, LandingResult.PERFECT) // streak 1, score 2
        state = ScoreRules.applyLanding(state, LandingResult.PERFECT) // streak 2, score 2+3=5
        state = ScoreRules.applyLanding(state, LandingResult.PERFECT) // streak 3, score 5+4=9
        assertEquals(3, state.comboStreak)
        assertEquals(9, state.score)
    }

    @Test
    fun applyLanding_success_scoresBaseOnly_resetsStreak() {
        var state = ScoreRules.applyLanding(RunState(), LandingResult.PERFECT)
        assertEquals(1, state.comboStreak)
        state = ScoreRules.applyLanding(state, LandingResult.SUCCESS)
        assertEquals(0, state.comboStreak)
        assertEquals(2 + ScoreRules.BASE_LANDING_SCORE, state.score)
        assertTrue(state.isAlive)
    }

    @Test
    fun applyLanding_miss_killsRun_withoutChangingScore() {
        val before = ScoreRules.applyLanding(RunState(), LandingResult.PERFECT)
        val after = ScoreRules.applyLanding(before, LandingResult.MISS)
        assertFalse(after.isAlive)
        assertEquals(before.score, after.score)
        assertEquals(before.jumpsCompleted, after.jumpsCompleted)
    }

    @Test
    fun applyLanding_streakBrokenByLaterSuccess_bestComboStreakKeepsThePeak() {
        // Result panel shows "最高连击" (this run's best streak), which must survive a streak
        // reset — reading the live comboStreak instead would report whatever streak the run
        // happened to end on, not the actual peak reached earlier in the run.
        var state = RunState()
        state = ScoreRules.applyLanding(state, LandingResult.PERFECT) // streak 1
        state = ScoreRules.applyLanding(state, LandingResult.PERFECT) // streak 2
        state = ScoreRules.applyLanding(state, LandingResult.PERFECT) // streak 3 (peak)
        state = ScoreRules.applyLanding(state, LandingResult.SUCCESS) // streak resets to 0
        state = ScoreRules.applyLanding(state, LandingResult.PERFECT) // streak 1 again
        assertEquals(1, state.comboStreak)
        assertEquals(3, state.bestComboStreak)
    }
}
