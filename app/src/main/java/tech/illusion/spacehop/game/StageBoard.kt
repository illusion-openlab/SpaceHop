package tech.illusion.spacehop.game

/** [platforms] is always exactly `[current, next]` — the "current+next only" visible window
 * from design doc §2.4, which is what keeps this app's FOV footprint far smaller than
 * `SpaceSnake`'s 6×6×6 cage. */
data class StageBoardState(
    val platforms: List<PlatformSpec>,
    val current: PlatformSpec,
)

object StageBoard {

    // V2 design ask: once the player's combo passes 20 consecutive successful jumps (any result,
    // not just a perfect one — the milestone should be reachable by most players, not gated
    // behind precision play), the very next jump becomes the compound window-crossing hop into
    // the lotus pond. [forceWindowCrossing] below is the second, skill-based path to the exact
    // same beat (content/HomeStage.kt tracks "10 landings within 10 seconds" and passes the
    // result in here) — either path fires the identical branch, so a fast player never has to
    // also grind out the full combo count.
    private const val WINDOW_TRIGGER_JUMPS_COMPLETED = 20

    /** Promotes the landed-on platform to `current`, drops the old `current`, and spawns a
     * fresh `next`. Rendering re-centres so the new `current` sits at the anchor — that
     * translation is a rendering concern (`content/HomeStage.kt`), not modeled here.
     *
     * Once the frog has landed on a [PlatformKind.LOTUS] slot, every later spawn stays
     * [PlatformKind.LOTUS] too — derived from `newCurrent.kind` itself, not a separate
     * world-theme flag, so the transition is one-way for the whole run with no extra state.
     * The one exception is the trigger jump itself: instead of spawning a normal single-leg
     * platform, it fuses two spawns (window leg + lotus leg) into one [PlatformSpec] whose
     * `distanceFromPreviousMeters` is their sum and whose [PlatformSpec.windowCrossingDistanceMeters]
     * marks where the window leg ends — content/CharacterController.flyTo covers that whole
     * distance in one continuous arc, so the frog passes the window mid-flight rather than
     * landing on it (V2 design ask: "flies through in one motion, lands on the lotus leaf").
     *
     * [forceWindowCrossing] is [StageBoard]'s only awareness of the speed-based alt path — it
     * carries no wall-clock knowledge itself (that lives in content/HomeStage.kt, alongside
     * `System`/`Compose` access), it just OR's into the same branch [WINDOW_TRIGGER_JUMPS_COMPLETED]
     * already uses. Ignored once already in the lotus world (first branch below wins regardless),
     * so callers never need to suppress it themselves after the crossing has happened. */
    fun advance(
        state: StageBoardState,
        spawner: PlatformSpawner,
        consecutiveJumps: Int,
        forceWindowCrossing: Boolean = false,
    ): StageBoardState {
        val newCurrent = state.platforms[1]
        val newNext = when {
            newCurrent.kind == PlatformKind.LOTUS ->
                spawner.spawnNext(index = newCurrent.index + 1, consecutiveJumps = consecutiveJumps)
                    .copy(kind = PlatformKind.LOTUS)
            consecutiveJumps > WINDOW_TRIGGER_JUMPS_COMPLETED || forceWindowCrossing -> {
                val windowLeg = spawner.spawnNext(index = newCurrent.index + 1, consecutiveJumps = consecutiveJumps)
                val lotusLeg = spawner.spawnNext(index = newCurrent.index + 2, consecutiveJumps = consecutiveJumps)
                lotusLeg.copy(
                    kind = PlatformKind.LOTUS,
                    distanceFromPreviousMeters = windowLeg.distanceFromPreviousMeters + lotusLeg.distanceFromPreviousMeters,
                    windowCrossingDistanceMeters = windowLeg.distanceFromPreviousMeters,
                )
            }
            else -> spawner.spawnNext(index = newCurrent.index + 1, consecutiveJumps = consecutiveJumps)
        }
        return StageBoardState(platforms = listOf(newCurrent, newNext), current = newCurrent)
    }
}
