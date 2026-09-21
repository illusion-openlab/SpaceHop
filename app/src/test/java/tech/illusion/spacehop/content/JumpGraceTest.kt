package tech.illusion.spacehop.content

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpGraceTest {

    /** 按「退出沉浸」本身就是一次捏合。PinchChargeController 独立于按钮监听裸手距离，
     * 所以同一次物理松手会被读成一次完整蓄力——不吞掉的话青蛙会在玩家退出的瞬间白跳一次。 */
    @Test
    fun releaseRightAfterAButtonPressIsSwallowed() {
        val grace = JumpGrace()
        val pressNanos = 1_000_000_000L
        grace.armAt(pressNanos)

        assertTrue(
            "按钮按下后 100ms 的松手必须吞掉",
            grace.shouldSwallow(pressNanos + 100L * 1_000_000L),
        )
    }

    /** 吞没窗口过后玩家的下一次故意捏合必须照常起跳。 */
    @Test
    fun releaseAfterTheGraceWindowCounts() {
        val grace = JumpGrace()
        val pressNanos = 1_000_000_000L
        grace.armAt(pressNanos)

        assertFalse(
            "超过 ${JUMP_INPUT_GRACE_MS}ms 之后的松手是玩家真实意图",
            grace.shouldSwallow(pressNanos + (JUMP_INPUT_GRACE_MS + 50L) * 1_000_000L),
        )
    }

    /** 没有按过按钮时不该吞任何东西。 */
    @Test
    fun unarmedGraceSwallowsNothing() {
        assertFalse(JumpGrace().shouldSwallow(1_000_000_000L))
    }
}
