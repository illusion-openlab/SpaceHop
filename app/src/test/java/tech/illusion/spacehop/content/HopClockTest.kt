package tech.illusion.spacehop.content

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class HopClockTest {

    /** delay 驱动的时钟必须真的推进——窗口态的全部动画都指望它。 */
    @Test
    fun delayDrivenClockAdvancesMonotonically() = runBlocking {
        val clock = HopClocks.delayDriven(intervalMs = 5L)
        val first = clock.awaitTick()
        val second = clock.awaitTick()
        val third = clock.awaitTick()
        assertTrue("第二次 tick 必须晚于第一次", second > first)
        assertTrue("第三次 tick 必须晚于第二次", third > second)
    }

    /** 连续 tick 之间至少要有大致一个 interval 的间隔，否则它就是个忙等循环而不是帧钟。
     * 下界取 interval 的一半，给调度抖动留足余量——这条测的是"确实在等"，不是精确计时。 */
    @Test
    fun delayDrivenClockRespectsInterval() = runBlocking {
        val intervalMs = 20L
        val clock = HopClocks.delayDriven(intervalMs = intervalMs)
        val start = clock.awaitTick()
        repeat(4) { clock.awaitTick() }
        val end = clock.awaitTick()
        val elapsedMs = (end - start) / 1_000_000L
        assertTrue(
            "5 次 tick 至少该花掉 ${intervalMs * 5 / 2}ms，实际 ${elapsedMs}ms",
            elapsedMs >= intervalMs * 5 / 2,
        )
    }
}
