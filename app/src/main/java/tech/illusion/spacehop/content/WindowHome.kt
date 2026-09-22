package tech.illusion.spacehop.content

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.geometry.NormalizedPoint3D
import com.pico.spatial.ui.foundation.layout.depth
import com.pico.spatial.ui.foundation.layout.sizeIn3D
import com.pico.spatial.ui.foundation.window.Augment
import com.pico.spatial.ui.foundation.window.AugmentContentAlignment
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.OpenStageResult
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import com.pico.spatial.ui.platform.containers.StageStyle
import kotlinx.coroutines.launch
import tech.illusion.spacehop.game.ChargeIndicatorColor
import tech.illusion.spacehop.game.HopPhase
import tech.illusion.spacehop.game.HopSession

private const val TAG = "SpaceHopWindow"

/**
 * 窗口态（WindowContainer）的壳。
 *
 * 和沉浸壳的差别只有四处：输入源（按压而非捏合——窗口里拿不到任何追踪数据）、帧钟（delay 而非
 * withFrameNanos）、根实体缩放（沙盘而非 1:1）、面板布局（Augment 工具条而非 AttachmentPanel）。
 * 玩法编排两边共用 [rememberHopRun]。
 *
 * 声明成块体（`fun WindowHome() { ... }`，返回 Unit）而不是表达式体：[rememberHopRun] 内部的
 * 回合超时看门狗靠 `LaunchedEffect(HopSession.boardState, HopSession.phase)` 的快照读重新起爆，
 * 表达式体会让这些读丢掉自己的重启作用域。与 [ImmersiveHome] 同形。
 */
@Composable
fun WindowHome() {
    // PhysicalLengthConverter 自己就是 Density（platform/PhysicalLengthConverter.kt:187
    // `interface PhysicalLengthConverter : Density`），所以下面 `with(converter)` 里可以直接
    // 用 `toDp()`，不必再取一份 LocalDensity。
    val converter = LocalPhysicalLengthConverter.current
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()

    val chargeInput = remember { PointerChargeInput() }
    // MUST be remembered, not called inline. `rememberHopRun` does `remember(clock) { HopRun(...) }`,
    // so a clock whose identity changes every recomposition rebuilds HopRun -- and with it a second
    // Sfx -- while the first one's `release()` sits in a DisposableEffect(Unit) that never re-runs.
    // That leaks a SoundPool per recomposition. `HopClocks.frameAligned` is a stable singleton and
    // has no such problem; `delayDriven()` returns a fresh instance on every call.
    val clock = remember { HopClocks.delayDriven() }
    val run = rememberHopRun(
        chargeInput = chargeInput,
        clock = clock,
        // 窗口态没有可以精准震动的目标：pointer 事件给不出左右手。
        onHaptic = { _, _ -> },
    )

    val sceneRoot = remember { Entity() }
    var halfW by remember { mutableFloatStateOf(0f) }
    var halfH by remember { mutableFloatStateOf(0f) }
    var halfD by remember { mutableFloatStateOf(0f) }
    var sceneBuilt by remember { mutableStateOf(false) }
    var blockedMessage by remember { mutableStateOf<String?>(null) }

    val scale = WindowScale.fitScale(halfW, halfH, halfD)

    // 摆位与缩放**只有这一个写入点**。草稿把它们同时写在 `SpatialView.initial` 和这个
    // LaunchedEffect 里：`initial` 是个只执行一次的挂起 lambda，捕获的是首帧（尚未测量、
    // 走兜底值）的 scale，而它实际跑完的时机晚于 effect——两处写同一属性时，后跑的那个会把
    // 测量后的正确值覆盖回兜底值。所以这里一次写全，`initial` 不碰 transform。
    //
    // 摆位的两个偏移都算在 [WindowScale] 里、各自带单测：它们是这个壳里唯一能被单测钉住的
    // 几何，写在这里只会变成一行没人验证的表达式。`SpatialView` 的内容原点是视图的中心
    // （SDK `SpatialCoordinateSpaceConverter.kt:59,79`），两个偏移都以盒心为 0。
    LaunchedEffect(scale, halfH, sceneBuilt) {
        sceneRoot.components[TransformComponent::class.java]?.apply {
            setScaleVector(Vector3(scale, scale, scale))
            setPosition(
                Vector3(
                    0f,
                    WindowScale.originOffsetY(halfH, scale),
                    WindowScale.originOffsetZ(scale),
                ),
            )
        }
    }

    val chargeEnabled = HopSession.phase is HopPhase.Playing &&
        HopSession.runState.isAlive &&
        !HopSession.paused

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            // 请求整个场景深度（0.73m）。这是个**常量**请求，不是 scale 的函数，所以下面读回
            // 来的深度不会和 scale 形成反馈环。`Modifier.depth` 走 enforceIncoming 分支
            // （foundation/layout/SpatialDepth.kt:36 -> :161 `maxDepthPx.coerceIn(
            // constraints.minDepth, constraints.maxDepth)`），超出容器的部分会被夹回容器自己的
            // 深度，所以读回来的就是「这个窗口实际给得起多少深度」。
            .depth(converter.lengthToDp(WindowScale.SCENE_DEPTH_M, LengthUnit.Meters))
            .onGloballyPositioned { coords ->
                with(converter) {
                    val size3D = coords.sizeIn3D
                    halfW = dpToLength(size3D.width.toDp(), LengthUnit.Meters) / 2f
                    halfH = dpToLength(size3D.height.toDp(), LengthUnit.Meters) / 2f
                    halfD = dpToLength(size3D.depth.toDp(), LengthUnit.Meters) / 2f
                }
                // Task 10 的真机取证锚点（已在门禁 B 完成，见 ledger）：沙盘摆位对不对只能靠实测
                // 三围反推，截图给不出可信数值。判据：originZ + MAX_FOOTPRINT_RADIUS_M*scale <=
                // halfD（当前平台不捅出前表面），且 originY + SCENE_HEIGHT_M*scale <= halfH（跳跃弧
                // 不顶出天花板）。取证已完成，M1 修复：移除每帧都在 ERROR 级别触发的调试 Log.e。
            },
        // 蓄力手势**不再**挂在这里。实测确认：`Modifier.pointerInput`（不论是 PICO 的
        // `detectTapGestures`/`detectSpatialTapGesture` 还是尝试直接引用的 vanilla AndroidX 版本）
        // 在这个体积主窗口的根组合上——不管是挂在 SpatialView 自己的 modifier 上，还是挂在一个
        // 完全独立、不经过 AndroidView 的裸 Compose Box 上——协程连启动都不会启动，poll 不到任何
        // 触摸事件。运行时自己的空间手势识别在更底层把事件送去做 3D 实体命中测试，Compose 自己的
        // 指针事件管线在这层根本收不到。唯一确认能用的是 `Augment` 弹层（独立的 Android 窗口）里
        // 的标准 Compose 手势——`Button` 用的 `clickable` 正是这条路径，所以蓄力交互挪到了
        // [ChargeBar] 内部，用同一套 `clickable` + `MutableInteractionSource` 机制。
        initial = { content, _ ->
            content.addEntity(sceneRoot)
            try {
                run.buildScene(sceneRoot)
                // 场景建完，让上面那个 LaunchedEffect 带着当前（可能已经测量到的）scale 再落一次
                // transform，而不是让这里去写一份可能已经过期的值。
                sceneBuilt = true
            } finally {
                // 从沉浸切回来时会话是冻结的，场景建好了才解冻；就算 buildScene 抛异常（例如资源
                // 加载失败）也必须解冻，否则回合超时看门狗永远冻结、蓄力输入永远被拒，玩家除了
                // 重启 App 没有别的恢复路径（M3 修复）。冷启动时本来就是 false，幂等。
                HopSession.onContainerSwitchSettled()
            }
        },
        attachments = {},
    )

    // 「进入沉浸」单独一个弹层，锚在窗口顶部——用户实测反馈：和蓄力条/HUD 挤在底部工具条同一排时
    // 位置太靠近主体画面、容易在按蓄力条时误触到它。挪到顶部之后两者物理上分得足够开，且不挡
    // 视线（沙盘的可视重心在窗口中下部，顶部本来就是留白）。
    Augment(
        anchor = NormalizedPoint3D.TopFront,
        alignment = AugmentContentAlignment.TopCenter,
        enableMaterialBackground = false,
    ) {
        PicoTheme {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(12.dp),
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            val outcome = enterImmersive(
                                openStage = { openImmersiveStage(navigator) },
                                closeWindow = { navigator.closeWindowContainer(MAIN_WINDOW_ID) },
                            )
                            blockedMessage = when (outcome) {
                                SwitchOutcome.BLOCKED_BY_SYSTEM -> "另一个应用正占用沉浸空间，稍后再试"
                                else -> null
                            }
                        }
                    },
                    // 飞行中置灰：飞行动画状态住在 CharacterController 里而不在 HopSession 里，
                    // 带不过去。与其序列化它，不如这半秒不让点。
                    enabled = HopSession.canSwitchContainer(),
                ) { Text(text = "进入沉浸") }
                blockedMessage?.let {
                    Text(text = it, color = PicoTheme.colorScheme.labelSecondary)
                }
            }
        }
    }

    Augment(
        anchor = NormalizedPoint3D.BottomFront,
        alignment = AugmentContentAlignment.BottomCenter,
        // 面板自己带毛玻璃（HudContent / StartPageContent 的 backgroundMaterial），Augment 再来
        // 一层会叠成两个共面半透明层。
        enableMaterialBackground = false,
    ) {
        // Augment 的内容跑在一个独立的弹层 composition 里，外层 Main.kt 的 PicoTheme 不保证
        // 透进来，所以这里自己再包一层。
        PicoTheme {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(12.dp),
            ) {
                when (val phase = HopSession.phase) {
                    is HopPhase.NotStarted -> StartPageContent(
                        bestScore = run.bestScore(),
                        onStart = { run.startNewRun() },
                    )
                    is HopPhase.Result -> ResultPanelContent(
                        score = HopSession.runState.score,
                        bestCombo = HopSession.runState.bestComboStreak,
                        bestScore = run.bestScore() ?: 0,
                        isNewRecord = phase.isNewRecord,
                        diedInLotusPond = phase.diedInLotusPond,
                        onRetry = { run.startNewRun() },
                    )
                    is HopPhase.Playing -> {
                        // 蓄力手势挂在整块面板上，不只是那条细条——用户反馈「只有那一小块能按」
                        // 手感太窄。这块面板在 Playing 阶段没有任何按钮（HudContent 纯展示、
                        // ChargeBar 现在也是纯展示），挂在整个 Row 上不会和任何子控件的点击抢事件。
                        val interactionSource = remember { MutableInteractionSource() }
                        LaunchedEffect(interactionSource) {
                            interactionSource.interactions.collect { interaction ->
                                when (interaction) {
                                    is PressInteraction.Press -> chargeInput.onPressDown()
                                    is PressInteraction.Release, is PressInteraction.Cancel ->
                                        chargeInput.onPressUp()
                                    else -> {}
                                }
                            }
                        }
                        // 回合结束/切换容器时如果玩家的手还按着，Compose 是否会在 `clickable` 的
                        // `enabled` 翻转时自动吐出 `PressInteraction.Cancel` 未经过这个项目独立
                        // 验证，所以额外镜像一份显式兜底松手——和 Task 8 修过的「手势卸载时蓄力
                        // 泄漏」同一个道理：宁可判两次，不可漏判一次。
                        LaunchedEffect(chargeEnabled) {
                            if (!chargeEnabled) chargeInput.onPressUp()
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            // 分数面板和蓄力条之间的间距故意拉大：用户反馈分数离主体画面太近，
                            // 拉开间距能让分数（整组居中）自然往左挪、蓄力区往右挪，不用假设
                            // 窗口的具体像素宽度去做绝对定位。
                            horizontalArrangement = Arrangement.spacedBy(40.dp),
                            modifier = Modifier
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = null,
                                    enabled = chargeEnabled,
                                    onClick = {}, // 真正的逻辑在上面的 interactionSource 收流里
                                )
                                .padding(vertical = 8.dp),
                        ) {
                            HudContent(
                                score = HopSession.runState.score,
                                comboStreak = HopSession.runState.comboStreak,
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "按住蓄力",
                                    style = PicoTheme.typography.labelLarge,
                                    color = PicoTheme.colorScheme.labelSecondary,
                                )
                                // 蓄力条本身再往下挪一点，和上面的文字标签拉开——用户反馈的第三条。
                                Spacer(modifier = Modifier.height(10.dp))
                                ChargeBar(chargeInput = chargeInput)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 打开沉浸 Stage，把 `OpenStageResult` 收敛成 [enterImmersive] 要的布尔值。
 *
 * 用 `is OpenStageResult.Allowed` 而不是 `==`：`Allowed` / `NotAllowed` 是 object，但 `Error`
 * 是带 `code`/`reason` 的普通 class（platform/containers/StageParams.kt:43-77），`is` 让 when
 * 对 sealed 层级穷尽，SDK 以后加一个分支时编译期就会报出来。
 *
 * 失败一定要打日志：被系统拒掉的切换在现场看就是「按钮点了没反应」，logcat 里什么都没有的话
 * 根本无从查起。
 */
private suspend fun openImmersiveStage(
    navigator: SpatialNavigator,
): Boolean = when (val result = navigator.openStage(IMMERSIVE_STAGE_ID, StageStyle.Mixed)) {
    is OpenStageResult.Allowed -> true
    is OpenStageResult.NotAllowed -> {
        Log.e(TAG, "openStage($IMMERSIVE_STAGE_ID) refused: NotAllowed")
        false
    }
    is OpenStageResult.Error -> {
        Log.e(
            TAG,
            "openStage($IMMERSIVE_STAGE_ID) failed: code=${result.code} reason=${result.reason}",
        )
        false
    }
}

/**
 * 窗口态的蓄力视觉反馈——纯展示，不处理手势。
 *
 * 蓄力手势本身挂在调用侧（[WindowHome] 的 Playing 分支）包住整个 HUD+蓄力区的 `Row` 上，
 * 而不是只挂在这条窄窄的进度条上：用户实测反馈「只有那一小块区域能按」手感太窄，希望整块面板
 * 任意位置都能发起蓄力。这里不再持有 `MutableInteractionSource`。
 *
 * 手势之所以只能挂在 `Augment` 弹层这类独立 2D 面板里、挂不到 3D 场景上，是被真机实测逼出来
 * 的：`Modifier.pointerInput`（不管是 PICO 的 `detectTapGestures`/`detectSpatialTapGesture`
 * 还是 vanilla AndroidX 版本）挂在体积主窗口的根组合上——不管是 `SpatialView` 自己的 modifier
 * 还是一个完全独立、不经过 `AndroidView` 包装的裸 Compose `Box`——协程连启动都不会启动。运行时
 * 自己的空间手势识别在更底层把触摸事件送去做 3D 实体命中测试，Compose 自己的指针事件管线在这层
 * 根本收不到事件；唯一确认收得到事件的是 `Augment` 弹层（独立的 Android 窗口）里的标准 Compose
 * 手势——`Button` 内部用的正是 `androidx.compose.foundation.clickable`，调用侧复用的就是这套。
 *
 * 沉浸态用四颗指尖球做蓄力反馈，窗口态没有手部数据，只能靠这条 + 青蛙自身的下蹲压缩。
 * 颜色沿用 [tech.illusion.spacehop.game.ChargeIndicatorColor] 的绿→红插值，和指尖球同一套语义。
 *
 * 收流在这个 composable 内部而不是调用侧：蓄力进度每 16ms 更新一次，在外面 collect 会把整个
 * Augment 的内容（起始页/结算页/工具条）一起拖进 60Hz 重组。
 */
@Composable
private fun ChargeBar(chargeInput: PointerChargeInput) {
    val state by chargeInput.chargeState.collectAsState()
    val rgb = ChargeIndicatorColor.forFraction(state.chargeFraction)

    Box(
        modifier = Modifier
            .width(160.dp)
            .height(20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(PicoTheme.colorScheme.fillTertiary),
    ) {
        // 0f 宽度的 fillMaxWidth 没必要也没意义，索性整条不画——顺带避开 fraction 为 0 时
        // 各版本 Compose 对 fillMaxWidth 取值范围的不同处理。
        if (state.isCharging && state.chargeFraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(state.chargeFraction.coerceIn(0.01f, 1f))
                    .background(Color(rgb[0], rgb[1], rgb[2], 1f)),
            )
        }
    }
}
