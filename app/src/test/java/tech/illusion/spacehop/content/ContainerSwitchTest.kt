package tech.illusion.spacehop.content

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.illusion.spacehop.game.HopPhase
import tech.illusion.spacehop.game.HopSession
import tech.illusion.spacehop.game.PlatformSpawner
import kotlin.random.Random

class ContainerSwitchTest {

    @Before
    fun reset() {
        HopSession.startNewRun(PlatformSpawner(Random(3)))
        HopSession.phase = HopPhase.Playing
    }

    /** 先开后关：任何时刻都必须至少有一个活容器，否则应用会直接消失。 */
    @Test
    fun enterImmersiveOpensStageBeforeClosingWindow() = runBlocking {
        val order = mutableListOf<String>()
        val outcome = enterImmersive(
            openStage = { order += "open"; true },
            closeWindow = { order += "close" },
        )
        assertEquals(SwitchOutcome.SWITCHED, outcome)
        assertEquals(listOf("open", "close"), order)
    }

    /** openStage 被系统拒绝（别的应用占着 Full Space）时，绝不能关掉窗口——
     * 关了就两个容器都没有了。 */
    @Test
    fun blockedOpenStageLeavesTheWindowAlone() = runBlocking {
        var windowClosed = false
        val outcome = enterImmersive(
            openStage = { false },
            closeWindow = { windowClosed = true },
        )
        assertEquals(SwitchOutcome.BLOCKED_BY_SYSTEM, outcome)
        assertFalse("被拒时窗口必须留着", windowClosed)
    }

    /** 被拒之后局面必须完好：解冻、切换标志清掉、分数一分不少。 */
    @Test
    fun blockedOpenStageRollsBackTheSession() = runBlocking {
        val runBefore = HopSession.runState
        enterImmersive(openStage = { false }, closeWindow = { })

        assertFalse("被拒后必须解冻，否则玩家卡在暂停里", HopSession.paused)
        assertFalse(HopSession.switching)
        assertEquals(runBefore, HopSession.runState)
        assertEquals(HopPhase.Playing, HopSession.phase)
    }

    /** 飞行中拒绝切换——飞行动画状态不在 session 里，带不过去。 */
    @Test
    fun switchIsRefusedWhileFlying() = runBlocking {
        HopSession.isFlying = true
        var opened = false
        val outcome = enterImmersive(openStage = { opened = true; true }, closeWindow = { })

        assertEquals(SwitchOutcome.REFUSED_BUSY, outcome)
        assertFalse("飞行中不该碰容器", opened)
    }

    /** 重复点击不能开出两个 Stage。 */
    @Test
    fun secondSwitchWhileSwitchingIsRefused() = runBlocking {
        HopSession.onContainerSwitchStart()
        var opened = false
        val outcome = enterImmersive(openStage = { opened = true; true }, closeWindow = { })

        assertEquals(SwitchOutcome.REFUSED_BUSY, outcome)
        assertFalse(opened)
    }

    /** 反方向同样先开后关。 */
    @Test
    fun exitImmersiveOpensWindowBeforeClosingStage() = runBlocking {
        val order = mutableListOf<String>()
        val outcome = exitImmersive(
            openWindow = { order += "open" },
            closeStage = { order += "close" },
        )
        assertEquals(SwitchOutcome.SWITCHED, outcome)
        assertEquals(listOf("open", "close"), order)
    }

    /** 切换开始时必须断掉 fast streak：这个窗口判的是 10 秒内连落 10 块，
     * 而切换本身就要花掉数秒真实时间。 */
    @Test
    fun switchClearsFastStreakTimestamps() = runBlocking {
        HopSession.recentLandingTimestampsMs.addLast(1L)
        HopSession.recentLandingTimestampsMs.addLast(2L)

        enterImmersive(openStage = { true }, closeWindow = { })

        assertTrue(HopSession.recentLandingTimestampsMs.isEmpty())
    }
}
