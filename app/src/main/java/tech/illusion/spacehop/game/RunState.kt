package tech.illusion.spacehop.game

data class RunState(
    val score: Int = 0,
    val comboStreak: Int = 0,
    val jumpsCompleted: Int = 0,
    val isAlive: Boolean = true,
)
