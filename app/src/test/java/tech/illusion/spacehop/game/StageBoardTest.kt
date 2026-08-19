package tech.illusion.spacehop.game

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StageBoardTest {

    private fun initialState(spawner: PlatformSpawner) = StageBoardState(
        platforms = emptyList(),
        current = PlatformSpec(0, 0f, 0.09f, 0.03f, 0f),
    ).let { it.copy(platforms = listOf(it.current, spawner.spawnNext(1, 0))) }

    @Test
    fun advance_promotesNextPlatformToCurrent() {
        val spawner = PlatformSpawner(Random(1))
        var state = initialState(spawner)
        val previousNext = state.platforms[1]
        state = StageBoard.advance(state, spawner, consecutiveJumps = 1)
        assertEquals(previousNext.index, state.current.index)
    }

    @Test
    fun advance_alwaysKeepsExactlyTwoVisiblePlatforms() {
        val spawner = PlatformSpawner(Random(2))
        var state = initialState(spawner)
        repeat(20) { i ->
            state = StageBoard.advance(state, spawner, consecutiveJumps = i)
            assertEquals(2, state.platforms.size)
        }
    }

    @Test
    fun advance_newNextPlatform_hasIncrementingIndex() {
        val spawner = PlatformSpawner(Random(3))
        var state = initialState(spawner)
        state = StageBoard.advance(state, spawner, consecutiveJumps = 1)
        val firstNextIndex = state.platforms[1].index
        state = StageBoard.advance(state, spawner, consecutiveJumps = 2)
        val secondNextIndex = state.platforms[1].index
        assertTrue(secondNextIndex > firstNextIndex)
    }

    @Test
    fun advance_atOrBeforeTwentyConsecutiveJumps_nextStaysPlate() {
        val spawner = PlatformSpawner(Random(4))
        var state = initialState(spawner)
        state = StageBoard.advance(state, spawner, consecutiveJumps = 20)
        assertEquals(PlatformKind.PLATE, state.platforms[1].kind)
    }

    @Test
    fun advance_afterTwentyConsecutiveJumps_nextIsCompoundWindowCrossingIntoLotus() {
        val spawner = PlatformSpawner(Random(5))
        var state = initialState(spawner)
        state = StageBoard.advance(state, spawner, consecutiveJumps = 21)
        val next = state.platforms[1]
        assertEquals(PlatformKind.LOTUS, next.kind)
        assertTrue("windowCrossingDistanceMeters should be set on the trigger jump", next.windowCrossingDistanceMeters != null)
        assertTrue(
            "total distance should be longer than just the window leg",
            next.distanceFromPreviousMeters > next.windowCrossingDistanceMeters!!,
        )
    }

    @Test
    fun advance_forceWindowCrossing_triggersCrossingWellBelowComboThreshold() {
        val spawner = PlatformSpawner(Random(8))
        var state = initialState(spawner)
        // Low combo count that would never trigger the crossing on its own.
        state = StageBoard.advance(state, spawner, consecutiveJumps = 3, forceWindowCrossing = true)
        val next = state.platforms[1]
        assertEquals(PlatformKind.LOTUS, next.kind)
        assertTrue("windowCrossingDistanceMeters should be set on the forced crossing jump", next.windowCrossingDistanceMeters != null)
    }

    @Test
    fun advance_forceWindowCrossingFalse_belowComboThreshold_nextStaysPlate() {
        val spawner = PlatformSpawner(Random(9))
        var state = initialState(spawner)
        state = StageBoard.advance(state, spawner, consecutiveJumps = 3, forceWindowCrossing = false)
        assertEquals(PlatformKind.PLATE, state.platforms[1].kind)
    }

    @Test
    fun advance_onceInLotusWorld_neverReturnsToPlateAndNeverCrossesAgain() {
        val spawner = PlatformSpawner(Random(7))
        var state = initialState(spawner)
        state = StageBoard.advance(state, spawner, consecutiveJumps = 21) // the compound crossing jump
        repeat(10) { i ->
            state = StageBoard.advance(state, spawner, consecutiveJumps = 22 + i)
            assertEquals(PlatformKind.LOTUS, state.platforms[1].kind)
            assertEquals(null, state.platforms[1].windowCrossingDistanceMeters)
        }
    }
}
