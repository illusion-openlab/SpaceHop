package tech.illusion.spacehop.content

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.detectTapGestures
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
    val context = LocalContext.current
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
    // 位置用的是**缩放后**的米数：实体自身的 position 在父空间里，不受自己的 scale 影响。
    // 青蛙站在场景原点、平台朝 -Z 展开，所以原点要贴到盒子的前（+Z）面，场景才整个落在盒内；
    // 同理 y 往下压半个场景高度，让跳跃弧留出上方余量。
    LaunchedEffect(scale, sceneBuilt) {
        sceneRoot.components[TransformComponent::class.java]?.apply {
            setScaleVector(Vector3(scale, scale, scale))
            setPosition(
                Vector3(
                    0f,
                    -(WindowScale.SCENE_HEIGHT_M * scale) / 2f,
                    (WindowScale.SCENE_DEPTH_M * scale) / 2f,
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
                // Task 10 的真机取证锚点：沙盘摆位对不对只能靠实测三围反推，截图给不出可信数值。
                Log.e(
                    TAG,
                    "view box measured: halfW=$halfW halfH=$halfH halfD=$halfD " +
                        "scale=${WindowScale.fitScale(halfW, halfH, halfD)}",
                )
            }
            // 蓄力手势只在真正能跳的时候挂载。工具条上的按钮位于 SpatialView 之外，物理上
            // 不重叠——已知教训：拖拽检测器条件性消费事件、点击检测器一见消费就取消，两个
            // 手势节点重叠时带位移的点击会间歇性失效。
            .pointerInput(chargeEnabled) {
                if (!chargeEnabled) return@pointerInput
                detectTapGestures(
                    context = context,
                    targetedToEntity = null, // 2D 与 3D 事件都收：按住窗口里任何地方都能蓄力
                    onPress = {
                        chargeInput.onPressDown()
                        tryAwaitRelease()
                        chargeInput.onPressUp()
                    },
                )
            },
        initial = { content, _ ->
            content.addEntity(sceneRoot)
            run.buildScene(sceneRoot)
            // 场景建完，让上面那个 LaunchedEffect 带着当前（可能已经测量到的）scale 再落一次
            // transform，而不是让这里去写一份可能已经过期的值。
            sceneBuilt = true
            // 从沉浸切回来时会话是冻结的，场景建好了才解冻。冷启动时本来就是 false，幂等。
            HopSession.onContainerSwitchSettled()
        },
        attachments = {},
    )

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
                    is HopPhase.Playing -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        HudContent(
                            score = HopSession.runState.score,
                            comboStreak = HopSession.runState.comboStreak,
                        )
                        ChargeBar(chargeInput = chargeInput)
                    }
                }
                // 工具条常驻，不挂在某个 phase 下：起始页和结算页也要能进沉浸，否则玩家必须先
                // 开一局才能换容器。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
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

/** 窗口态的蓄力反馈。沉浸态用四颗指尖球，窗口态没有手部数据，只能靠这条 + 青蛙自身的下蹲压缩。
 *
 * 颜色沿用 [tech.illusion.spacehop.game.ChargeIndicatorColor] 的绿→红插值，和指尖球同一套语义。
 *
 * 收流在这个 composable 内部而不是调用侧：蓄力进度每 16ms 更新一次，在外面 collect 会把整个
 * Augment 的内容（起始页/结算页/工具条）一起拖进 60Hz 重组。 */
@Composable
private fun ChargeBar(chargeInput: PointerChargeInput) {
    val state by chargeInput.chargeState.collectAsState()
    val rgb = ChargeIndicatorColor.forFraction(state.chargeFraction)
    Box(
        modifier = Modifier
            .width(120.dp)
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
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
