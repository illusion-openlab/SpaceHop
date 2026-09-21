package tech.illusion.spacehop.content

import tech.illusion.spacehop.game.HopSession

/** 沉浸态 Stage 的 id。只在 `Main.kt` 的 DSL 里声明，manifest 不再有 stage 配置。 */
const val IMMERSIVE_STAGE_ID = "SpaceHopImmersive"

/** 主窗口的 id。**必须**与 AndroidManifest.xml 的 `pico.spatial.windowcontainer.id` 一致，
 * 否则从沉浸态返回时 `openWindowContainer` 会找不到它。 */
const val MAIN_WINDOW_ID = "SpaceHopVolume"

enum class SwitchOutcome {
    /** 切换完成。 */
    SWITCHED,

    /** 当前不能切：青蛙在飞行中，或已有一次切换在途。 */
    REFUSED_BUSY,

    /** 系统拒绝打开 Stage——通常是别的应用正占用 Full Space。 */
    BLOCKED_BY_SYSTEM,
}

/**
 * 窗口 → 沉浸。
 *
 * [openStage] 与 [closeWindow] 是函数缝而不是直接的 navigator 调用：`LocalSpatialNavigator`
 * 只能在组合里拿到，直接调用会让下面这些决策（先开后关、被拒不关窗、忙时拒绝、失败回滚）
 * 完全无法在 JVM 上被测到。生产代码传 navigator 的方法引用，测试传假实现。
 *
 * @param openStage 打开 Stage，返回是否被允许（`OpenStageResult.Allowed`）。
 */
suspend fun enterImmersive(
    openStage: suspend () -> Boolean,
    closeWindow: () -> Unit,
): SwitchOutcome {
    if (!HopSession.canSwitchContainer()) return SwitchOutcome.REFUSED_BUSY
    HopSession.onContainerSwitchStart()
    // 先开后关：保证任何时刻至少有一个活容器。反过来做的话，只要 openStage 被拒，
    // 应用就一个容器都不剩、直接消失。
    val allowed = openStage()
    if (!allowed) {
        HopSession.onContainerSwitchFailed()
        return SwitchOutcome.BLOCKED_BY_SYSTEM
    }
    // 必须关闭而不是最小化：打开 Stage 只会隐藏*其他应用*的窗口，自己的窗口会继续飘在沉浸
    // 场景里。关闭同时也释放这个容器的整个场景。
    closeWindow()
    return SwitchOutcome.SWITCHED
}

/** 沉浸 → 窗口。同样先开后关。 */
suspend fun exitImmersive(
    openWindow: () -> Unit,
    closeStage: suspend () -> Unit,
): SwitchOutcome {
    if (!HopSession.canSwitchContainer()) return SwitchOutcome.REFUSED_BUSY
    HopSession.onContainerSwitchStart()
    openWindow()
    closeStage()
    return SwitchOutcome.SWITCHED
}
