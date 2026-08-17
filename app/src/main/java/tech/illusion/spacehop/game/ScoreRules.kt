package tech.illusion.spacehop.game

/**
 * Scoring formula per design doc §2.2: base point per successful landing, plus a combo
 * bonus that grows with consecutive perfect landings. Exact bonus growth/cap is a tuning
 * knob, not locked by the design — [comboBonus] is the single place to retune it.
 */
object ScoreRules {

    const val BASE_LANDING_SCORE = 1

    fun comboBonus(streakAfterThisLanding: Int): Int = streakAfterThisLanding

    fun applyLanding(state: RunState, result: LandingResult): RunState = when (result) {
        LandingResult.PERFECT -> {
            val newStreak = state.comboStreak + 1
            val gained = BASE_LANDING_SCORE + comboBonus(newStreak)
            state.copy(
                score = state.score + gained,
                comboStreak = newStreak,
                bestComboStreak = maxOf(state.bestComboStreak, newStreak),
                jumpsCompleted = state.jumpsCompleted + 1,
            )
        }
        LandingResult.SUCCESS -> state.copy(
            score = state.score + BASE_LANDING_SCORE,
            comboStreak = 0,
            jumpsCompleted = state.jumpsCompleted + 1,
        )
        LandingResult.MISS -> state.copy(isAlive = false)
    }
}
