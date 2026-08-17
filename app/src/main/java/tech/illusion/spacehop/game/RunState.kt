package tech.illusion.spacehop.game

data class RunState(
    val score: Int = 0,
    val comboStreak: Int = 0,
    // The peak comboStreak ever reached this run — comboStreak itself resets to 0 on a
    // non-perfect landing, so it can't answer "what was this run's best combo?" once broken.
    val bestComboStreak: Int = 0,
    val jumpsCompleted: Int = 0,
    val isAlive: Boolean = true,
)
