package tech.illusion.spacehop.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 一局游戏的生命周期阶段。
 *
 * 原先私有在 `content/HomeStage.kt` 里。搬到这里并放开可见性，是因为它现在要被两个容器的壳
 * 同时读取，而它本身不含任何 SDK 或容器概念。
 *
 * [NotStarted] 只在冷启动时出现——「再来一局」直接重开游戏，不回起始页。 */
sealed class HopPhase {
    data object NotStarted : HopPhase()
    data object Playing : HopPhase()
    data class Result(val isNewRecord: Boolean, val diedInLotusPond: Boolean) : HopPhase()
}

/** 一局新游戏的初始板面：脚下一块 + 前方一块。 */
fun freshBoardState(spawner: PlatformSpawner): StageBoardState {
    val origin = PlatformSpec(0, 0f, 0.09f, 0.09f * 0.35f, 0f)
    return StageBoardState(platforms = listOf(origin, spawner.spawnNext(1, 0)), current = origin)
}

/**
 * 跨容器的局面会话。
 *
 * 应用级单例，**不属于任何容器**：窗口的 composition 在 `closeWindowContainer` 时整个 dispose，
 * 所以任何需要跨越切换活下来的状态都必须住在 composition 之外。这是整个窗口模式架构的地基。
 *
 * 字段全部由 `mutableStateOf` 支撑而不是普通 `var`——普通 `var` 在组合里读只订阅绘制阶段，
 * `when (HopSession.phase)` 会卡在首次求值，点了按钮画面不切，而编译、单测、代码审查全都发现
 * 不了。纯逻辑仍然住在 [RunState] / [StageBoardState] 这些不含 Compose 类型的数据类里，它们继续
 * 可以脱离 Compose 单测。
 *
 * 最高分不在这里——`HighScoreStore` 已经做了持久化。
 */
object HopSession {

    var runState: RunState by mutableStateOf(RunState())
    var boardState: StageBoardState by mutableStateOf(freshBoardState(PlatformSpawner()))
    var phase: HopPhase by mutableStateOf(HopPhase.NotStarted)

    /** 冻结回合超时看门狗与蓄力视觉。退出确认框和容器切换共用这一个概念。 */
    var paused: Boolean by mutableStateOf(false)

    /** 本回合是否已经提交过跳跃（真人松手或看门狗的强制跳都算）。 */
    var jumpCommittedThisRound: Boolean by mutableStateOf(false)

    /** 青蛙是否正在飞行。飞行动画状态住在 `CharacterController` 里而不在本单例里，
     * 所以飞行期间禁止切换容器——与其序列化飞行状态，不如把按钮置灰。 */
    var isFlying: Boolean by mutableStateOf(false)

    /** 容器切换进行中。防止重复触发开出两个 Stage。 */
    var switching: Boolean by mutableStateOf(false)

    /** 本局最近若干次落地的墙钟时间戳，供 fast-streak 判定用。
     *
     * 不是 snapshot state：只在玩法编排内部读写，不需要触发重组。
     *
     * 容器切换时清空（见 [onContainerSwitchStart]）：这个窗口判的是「10 秒内连落 10 块」，
     * 而切换要花掉数秒真实时间，带过去只会让判定被切换耗时污染。切换期间玩家确实没在连跳。 */
    val recentLandingTimestampsMs: ArrayDeque<Long> = ArrayDeque()

    fun startNewRun(spawner: PlatformSpawner) {
        runState = RunState()
        boardState = freshBoardState(spawner)
        paused = false
        jumpCommittedThisRound = false
        isFlying = false
        switching = false
        recentLandingTimestampsMs.clear()
        phase = HopPhase.Playing
    }

    /** 切换是否被允许。飞行中或已在切换途中都拒绝。 */
    fun canSwitchContainer(): Boolean = !isFlying && !switching

    /** 按下「进入沉浸 / 退出沉浸」的瞬间调用：冻结回合，断掉 fast streak。 */
    fun onContainerSwitchStart() {
        switching = true
        paused = true
        recentLandingTimestampsMs.clear()
    }

    /** 新容器的场景建完之后调用：解冻。 */
    fun onContainerSwitchSettled() {
        paused = false
        switching = false
    }

    /** 切换失败（`openStage` 返回 NotAllowed/Error）时解冻、清掉切换标志。
     *
     * 这就是完整的回滚：切换开始到失败之间，[onContainerSwitchStart] 只动了 [paused]、
     * [switching] 和 [recentLandingTimestampsMs]，容器本身还没换（失败发生在 openStage
     * 之后、closeWindow 之前），所以没有别的状态需要还原。清空的 fast-streak 时间戳不必
     * 恢复——切换期间玩家本来就没在连跳，判定不受影响。 */
    fun onContainerSwitchFailed() {
        paused = false
        switching = false
    }
}
