package tech.illusion.spacehop.game

/** [platforms] is always exactly `[current, next]` — the "current+next only" visible window
 * from design doc §2.4, which is what keeps this app's FOV footprint far smaller than
 * `SpaceSnake`'s 6×6×6 cage. */
data class StageBoardState(
    val platforms: List<PlatformSpec>,
    val current: PlatformSpec,
)

object StageBoard {

    /** Promotes the landed-on platform to `current`, drops the old `current`, and spawns a
     * fresh `next`. Rendering re-centres so the new `current` sits at the anchor — that
     * translation is a rendering concern (`content/HomeStage.kt`), not modeled here. */
    fun advance(state: StageBoardState, spawner: PlatformSpawner, consecutiveJumps: Int): StageBoardState {
        val newCurrent = state.platforms[1]
        val newNext = spawner.spawnNext(index = newCurrent.index + 1, consecutiveJumps = consecutiveJumps)
        return StageBoardState(platforms = listOf(newCurrent, newNext), current = newCurrent)
    }
}
