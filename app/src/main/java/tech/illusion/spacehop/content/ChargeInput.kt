package tech.illusion.spacehop.content

import kotlinx.coroutines.flow.StateFlow

/** 哪只手在蓄力。
 *
 * 刻意不用 SDK 的 `com.pico.spatial.ui.platform.HandController`：那会让 [ChargeInput] 这个
 * 接口带上 SDK 依赖，也会让它的单测跨进 Android 侧。沉浸壳负责把它映射成 `HandController`。 */
enum class ChargeHand {
    LEFT,
    RIGHT;

    companion object {
        /** [isLeft] 为 null 表示输入源不知道是哪只手——窗口态的 pointer 事件就是这种情况：
         * `SpatialPointerInfo` 能给出 `kind`（DirectPinch / GazePinch / RayBasedPinch / Poke /
         * Pointer）和 `inputDevicePose`，但给不出左右手。 */
        fun fromIsLeft(isLeft: Boolean?): ChargeHand? = when (isLeft) {
            true -> LEFT
            false -> RIGHT
            null -> null
        }
    }
}

/**
 * 蓄力输入源。两个容器各有一套实现，因为它们的可用输入完全不同。
 *
 * 沉浸态（Stage）用 [PinchChargeController]：手部捏合 + 手柄扳机。
 * 窗口态（WindowContainer）用 [PointerChargeInput]：按住 3D 视图。
 *
 * 为什么不能共用：`HandTrackingProvider` / `ControllerTrackingProvider` / `HMDTrackingProvider`
 * 连同它们的父接口 `DataProvider<T>` 全部带 `@RequiredFullSpace` 注解（SDK 6.0.0 源码实测）。
 * 在 WindowContainer 下 `start()` 只返回 `StartResult.PENDING`——不抛异常、不打日志，`dataFlow`
 * 永远不发射。整套追踪输入在窗口里不是降级而是归零。
 */
interface ChargeInput {

    /** 蓄力的可视化状态。窗口态不产生指尖球位置，`positions` 保持默认值。 */
    val chargeState: StateFlow<ChargeUiState>

    /**
     * @param onChargeStart 开始蓄力。[ChargeHand] 为 null 表示输入源不知道是哪只手。
     * @param onRelease 松手。第一个参数是已按 [tech.illusion.spacehop.game.ChargeCurve.MAX_CHARGE_MILLIS]
     *   封顶的按住时长。
     */
    fun start(
        onChargeStart: (ChargeHand?) -> Unit,
        onRelease: (cappedHeldMillis: Long, hand: ChargeHand?) -> Unit,
    )

    fun stop()
}
