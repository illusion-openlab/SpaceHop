package tech.illusion.spacehop.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

class HopSessionTest {

    private val spawner = PlatformSpawner(Random(7))

    @Before
    fun reset() {
        HopSession.startNewRun(spawner)
        HopSession.phase = HopPhase.NotStarted
    }

    /** 交接的核心保证：容器切换不碰任何一项局面数据。这条测的就是"无缝带过去"。 */
    @Test
    fun containerSwitchPreservesEveryRunField() {
        HopSession.runState = RunState(
            score = 15,
            comboStreak = 3,
            bestComboStreak = 7,
            jumpsCompleted = 21,
            isAlive = true,
        )
        HopSession.boardState = StageBoardState(
            platforms = listOf(
                PlatformSpec(30, 0f, 0.06f, 0.021f, 0f, kind = PlatformKind.LOTUS),
                PlatformSpec(31, 0.02f, 0.06f, 0.021f, 0.3f, kind = PlatformKind.LOTUS),
            ),
            current = PlatformSpec(30, 0f, 0.06f, 0.021f, 0f, kind = PlatformKind.LOTUS),
        )
        HopSession.phase = HopPhase.Playing

        val runBefore = HopSession.runState
        val boardBefore = HopSession.boardState

        HopSession.onContainerSwitchStart()
        HopSession.onContainerSwitchSettled()

        assertEquals(runBefore, HopSession.runState)
        assertEquals(boardBefore, HopSession.boardState)
        assertEquals(HopPhase.Playing, HopSession.phase)
        assertEquals(
            "荷塘关卡必须跟着走，否则玩家会被切回厨房",
            PlatformKind.LOTUS,
            HopSession.boardState.current.kind,
        )
    }

    /** 切换全程必须冻结，否则 10.2 秒的回合超时会在切换的空窗期里把玩家"抓住"。 */
    @Test
    fun containerSwitchFreezesTheRoundThenThaws() {
        HopSession.phase = HopPhase.Playing
        assertFalse(HopSession.paused)

        HopSession.onContainerSwitchStart()
        assertTrue("切换开始后必须冻结", HopSession.paused)
        assertTrue("切换中标志必须置起", HopSession.switching)

        HopSession.onContainerSwitchSettled()
        assertFalse("新容器建完场景后必须解冻", HopSession.paused)
        assertFalse(HopSession.switching)
    }

    /** 青蛙飞行中（动画状态在 CharacterController 里、不在 session 里）不能切换。 */
    @Test
    fun switchIsRefusedWhileFlying() {
        HopSession.phase = HopPhase.Playing
        HopSession.isFlying = true
        assertFalse(HopSession.canSwitchContainer())

        HopSession.isFlying = false
        assertTrue(HopSession.canSwitchContainer())
    }

    /** 已经在切换途中时不能再次触发，否则会开出两个 Stage。 */
    @Test
    fun switchIsRefusedWhileAlreadySwitching() {
        HopSession.phase = HopPhase.Playing
        HopSession.onContainerSwitchStart()
        assertFalse(HopSession.canSwitchContainer())
    }

    /** 开新局必须把上一局的残留全部清干净——包括切换用的那几个标志。 */
    @Test
    fun startNewRunClearsEverything() {
        HopSession.runState = RunState(score = 99, jumpsCompleted = 40)
        HopSession.paused = true
        HopSession.jumpCommittedThisRound = true
        HopSession.isFlying = true

        HopSession.startNewRun(spawner)

        assertEquals(0, HopSession.runState.score)
        assertEquals(0, HopSession.runState.jumpsCompleted)
        assertTrue(HopSession.runState.isAlive)
        assertFalse(HopSession.paused)
        assertFalse(HopSession.jumpCommittedThisRound)
        assertFalse(HopSession.isFlying)
        assertEquals(HopPhase.Playing, HopSession.phase)
        assertEquals("新局必须是厨房关卡", PlatformKind.PLATE, HopSession.boardState.current.kind)
    }
}
