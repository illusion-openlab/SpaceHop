package tech.illusion.spacehop.content

import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay

/**
 * 动画循环的时间源，按容器注入。
 *
 * 存在的理由是一条实测结论：`withFrameNanos` 在 `DefaultWindowContainer` 下只触发一次就不再恢复
 * （Overwinter，2026-08-18，加 Log 读 logcat 确认），用它驱动的游戏循环一帧都不跑。而同一写法在
 * Stage 里工作正常且是 vsync 对齐的。所以两个容器各用各的时钟，而不是统一换成 delay——沉浸态是
 * 已上架且工作正常的代码路径，改它是无谓风险。
 */
fun interface HopClock {
    /** 挂起到下一帧，返回该帧的时间戳（纳秒）。 */
    suspend fun awaitTick(): Long
}

object HopClocks {

    /** 沉浸态（Stage）用：vsync 对齐，与改动前的行为完全一致。 */
    val frameAligned: HopClock = HopClock { withFrameNanos { it } }

    /**
     * 窗口态（WindowContainer）用。11ms 约等于 90Hz，比设备刷新率略密，
     * 保证不会成为动画流畅度的瓶颈；实测平面侧的 delay 循环能跑到 60–80fps。
     */
    fun delayDriven(intervalMs: Long = 11L): HopClock = HopClock {
        delay(intervalMs)
        System.nanoTime()
    }
}
