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
}
