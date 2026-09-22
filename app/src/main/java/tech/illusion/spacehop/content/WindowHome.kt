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
import androidx.compose.ui.platform.LocalContext
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

/** 厨房背景板（3D 平面）的真实宽度。 */
private const val WINDOW_BACKDROP_WIDTH_M = 0.5f

/** 窗帘贴图本身实测的透明缺口宽度占比：`window_curtain_backdrop.png` 中间透明区只占整张图
 *  宽度的 ~65.6%（两侧各 ~17.2% 是不透明窗帘布），且几乎贯穿整个高度（≈99.9%，顶部没有横向
 *  帘头）。下面两个常量都是从这个实测比例反推出来的，不是各自独立手调的数字。 */
private const val WINDOW_CURTAIN_TRANSPARENT_WIDTH_FRACTION = 0.656f

/** 窗帘层（3D 平面）的真实宽度——用户明确要求四层深度顺序「厨房背景层-游戏主体-窗帘层-UI层」，
 *  窗帘不再是嵌在底部 Augment 里的 2D 装饰图，而是独立的 3D 平面，摆在沙盘前面、Augment 弹层
 *  后面。
 *
 *  **两轮反复调出来的数字，别再改大。** 第一版 0.6f：透明缺口 0.6*0.656≈0.39m 比蓄力响应层
 *  `Augment` 自己的宽度（`halfW*2*0.9`，默认窗口下≈0.45m）还窄，窗帘布压住了蓄力区外沿——
 *  「蓄力范围变小了」。改到 0.7f 想拉宽透明缺口，代价是窗帘布的物理宽度（0.7m）超过了默认
 *  窗口本身的横截面（0.5m），超出的部分被 SpatialView 自己的包围盒硬裁掉——「窗帘两侧被截断
 *  了」。0.46f = 默认窗口宽度 0.5m * `WindowScale.MARGIN` 同款的 0.92 留白系数：保证窗帘布
 *  完整留在盒子内部、不会被裁边（下面 [WINDOW_CURTAIN_VISIBLE_GAP_WIDTH_M] 换算出来的透明
 *  缺口比蓄力区窄，所以蓄力区改成不超过这个缺口宽度，而不是继续加宽窗帘——宁可蓄力区比最初
 *  版本窄一点，也不能让窗帘露出裁切硬边。 */
private const val WINDOW_CURTAIN_WIDTH_M = 0.46f

/** 窗帘中间透明缺口的实际可见宽度（米）——蓄力响应层 `Augment` 的可点击宽度必须封顶在这个
 *  数以内：如果继续按 `halfW*2*0.9` 算下去，蓄力区会比玩家透过窗帘实际看到的「开着的洞」更
 *  宽，摸得到但玩家看不出那块能摸。见下面蓄力 `Augment` 里的 `minOf(...)` 用法。 */
private const val WINDOW_CURTAIN_VISIBLE_GAP_WIDTH_M =
    WINDOW_CURTAIN_WIDTH_M * WINDOW_CURTAIN_TRANSPARENT_WIDTH_FRACTION

/** 窗帘层在深度轴上的位置（盒心为 0，+Z 朝向观察者）——必须比沙盘自己的前沿更靠前，才谈得上
 *  「盖在游戏前面」。默认窗口（0.5x0.4x0.45m）下，沙盘缩放约 0.567x，前沿约在
 *  `0.567 * SCENE_DEPTH_M / 2` ≈ 0.207m 处（见 [WindowScale.originOffsetZ] 的推导）；窗口盒子
 *  本身的前表面在 halfD ≈ 0.225m。0.21f 落在这两者之间，留了几毫米余量，不贴死前表面。
 *
 *  这是个固定值，不随窗口被用户等比缩放而重新计算——和厨房背景板的 `-0.20f` 一样，是个已知的
 *  简化：如果用户缩放窗口后窗帘和沙盘的前后关系看着不对，这是第一个要重新核实的数字。 */
private const val WINDOW_CURTAIN_OFFSET_Z_M = 0.21f

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

    // 兜底松手挪到顶层、只写一份：蓄力触发面现在有两处（顶部工具条 + 底部蓄力区），各自持有
    // 自己的 MutableInteractionSource，但 `chargeInput` 内部的按下状态是单一的（同一时刻只可能
    // 有一根手指真的按在其中一处，两个弹层是两个独立窗口，物理上不可能同时收到同一次触摸）。
    // 回合结束/切换容器时如果手还按着，Compose 是否会在 `clickable` 的 `enabled` 翻转时自动吐出
    // `PressInteraction.Cancel` 未经过这个项目独立验证，所以镜像一份显式兜底松手——和 Task 8
    // 修过的「手势卸载时蓄力泄漏」同一个道理：宁可判两次，不可漏判一次。
    LaunchedEffect(chargeEnabled) {
        if (!chargeEnabled) chargeInput.onPressUp()
    }

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
                // 厨房背景板：直接加到 content 下面，不挂在 sceneRoot 下——sceneRoot 会被沙盘自己
                // 的缩放（~0.567x）带着一起缩小，背景板要保持真实房间尺度，不跟着缩。摆在比沙盘
                // 更靠后的位置（z 更负），不需要额外旋转就能正面朝向玩家（见 WindowBackdrop 的
                // 说明）。放在 sceneBuilt=true 之后：就算背景板加载失败，核心玩法场景已经建好、
                // 不受影响。
                val backdrop = WindowBackdrop.build(
                    context = context,
                    localOffset = Vector3(0f, 0f, -0.20f),
                    widthM = WINDOW_BACKDROP_WIDTH_M,
                )
                content.addEntity(backdrop)
                // 窗帘层：厨房背景板和游戏主体之间已经处理好了（都在后面），这一层要盖在游戏
                // 前面。同样直接挂在 content 下（不挂 sceneRoot），保持真实尺度不跟着沙盘缩放；
                // 同样放在 sceneBuilt=true 之后，加载失败不影响核心玩法。
                val curtain = WindowCurtain.build(
                    context = context,
                    localOffset = Vector3(0f, 0f, WINDOW_CURTAIN_OFFSET_Z_M),
                    widthM = WINDOW_CURTAIN_WIDTH_M,
                )
                content.addEntity(curtain)
            } finally {
                // 从沉浸切回来时会话是冻结的，场景建好了才解冻；就算 buildScene 抛异常（例如资源
                // 加载失败）也必须解冻，否则回合超时看门狗永远冻结、蓄力输入永远被拒，玩家除了
                // 重启 App 没有别的恢复路径（M3 修复）。冷启动时本来就是 false，幂等。
                HopSession.onContainerSwitchSettled()
            }
        },
        attachments = {},
    )

    // 用户明确要求：蓄力响应区要覆盖主体画面（红框标出的范围），不只是顶部/底部工具条周围一圈。
    // 这块 Augment 本身完全透明（没有背景、没有可见内容）——沙盘本身收不到任何 Compose 手势
    // （见下面 ChargeBar 的大注释：不管是 PICO 的手势 API、vanilla AndroidX，还是挂在一个完全
    // 独立不经过 AndroidView 的裸 Box 上，`Modifier.pointerInput` 在这个体积窗口的根组合上一律
    // 收不到事件），只能靠再叠一层 Augment 弹层去"借用"它能收到触摸这件事，盖在场景正上方。
    //
    // 尺寸跟着实测的窗口宽高走（halfW/halfH，来自 SpatialView 自己的 onGloballyPositioned），
    // 不写死像素——窗口允许被用户等比缩放（manifest 的 resizerestriction=1），写死尺寸的话缩放
    // 后这块透明层要么盖不住场景、要么反过来盖住上下的工具条。宽高都留了一点余量（不到窗口的
    // 满宽满高），刻意不去顶格盖住顶部/底部工具条各自的可点击区域。
    Augment(
        anchor = NormalizedPoint3D.Center,
        alignment = AugmentContentAlignment.Center,
        enableMaterialBackground = false,
    ) {
        val sceneInteractionSource = remember { MutableInteractionSource() }
        LaunchedEffect(sceneInteractionSource) {
            sceneInteractionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> chargeInput.onPressDown()
                    is PressInteraction.Release, is PressInteraction.Cancel ->
                        chargeInput.onPressUp()
                    else -> {}
                }
            }
        }
        // 宽度封顶在窗帘实际露出的透明缺口宽度以内（见 [WINDOW_CURTAIN_VISIBLE_GAP_WIDTH_M]）——
        // 否则蓄力区会比玩家透过窗帘能看到的「开着的洞」更宽，看不出来的地方其实也能点，体验
        // 上就是「点得到但不知道哪能点」，跟本来想修的「蓄力范围变小」是同一类体验问题的反面。
        // 窗口比默认更小时 halfW 更紧，min 会退回窗口本身的口径，不会比窗帘缺口还宽出去。
        Box(
            modifier = Modifier
                .width(
                    converter.lengthToDp(
                        minOf(halfW * 2f * 0.9f, WINDOW_CURTAIN_VISIBLE_GAP_WIDTH_M),
                        LengthUnit.Meters,
                    ),
                )
                .height(converter.lengthToDp(halfH * 2f * 0.55f, LengthUnit.Meters))
                .clickable(
                    interactionSource = sceneInteractionSource,
                    indication = null,
                    enabled = chargeEnabled,
                    onClick = {}, // 真正的逻辑在上面的 interactionSource 收流里
                ),
        )
    }

    // 「进入沉浸」单独一个弹层，锚在窗口顶部——用户实测反馈：和蓄力条/HUD 挤在底部工具条同一排时
    // 位置太靠近主体画面、容易在按蓄力条时误触到它。挪到顶部之后两者物理上分得足够开，且不挡
    // 视线（沙盘的可视重心在窗口中下部，顶部本来就是留白）。
    //
    // HudContent（分数 + 连击）也放在这里，不留在底部工具条：用户反馈连击出现时分数面板会变宽，
    // 一旦连击变宽/居中的那组内容就会往主体画面方向挤，把青蛙挡住。顶部是已经验证过的"不挡视线"
    // 安全区，分数面板宽度不管怎么变都不会影响底部的蓄力交互区。
    //
    // 用户反馈蓄力响应区太小，要求延展到分数和「进入沉浸」下面。这里的做法是把 `clickable`
    // 直接挂在整个 Row 上（分数 + 按钮的外层容器），而不是只挂在某个子项上：Compose 对嵌套
    // `clickable` 的消费顺序是内层先拿到事件——精确点在「进入沉浸」按钮上时按钮自己的点击优先
    // 触发（切容器），点在按钮范围之外、包括分数数字上方或按钮周围的留白，才会落到这层外层
    // `clickable` 上触发蓄力。两者是父子嵌套关系，不是同级抢事件，不会重演蓄力/按钮互相吞事件
    // 的老问题。
    Augment(
        anchor = NormalizedPoint3D.TopFront,
        alignment = AugmentContentAlignment.TopCenter,
        enableMaterialBackground = false,
    ) {
        PicoTheme {
            val topInteractionSource = remember { MutableInteractionSource() }
            LaunchedEffect(topInteractionSource) {
                topInteractionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> chargeInput.onPressDown()
                        is PressInteraction.Release, is PressInteraction.Cancel ->
                            chargeInput.onPressUp()
                        else -> {}
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier
                    .clickable(
                        interactionSource = topInteractionSource,
                        indication = null,
                        enabled = chargeEnabled,
                        onClick = {}, // 真正的逻辑在上面的 interactionSource 收流里
                    )
                    .padding(12.dp),
            ) {
                if (HopSession.phase is HopPhase.Playing) {
                    HudContent(
                        score = HopSession.runState.score,
                        comboStreak = HopSession.runState.comboStreak,
                    )
                }
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
                        // 手感太窄。HudContent 已经挪去顶部弹层了（见上面那个 Augment 的注释），
                        // 这块面板在 Playing 阶段只剩蓄力标签 + 蓄力条，没有任何按钮，挂在整个
                        // Column 上不会和任何子控件的点击抢事件。
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
                        // 兜底松手已经提到 WindowHome() 顶层，两个蓄力触发面（这里 + 顶部工具条）
                        // 共用同一份，不在这里重复。

                        // 窗帘不再是这块面板自己的背景图——已经改成独立的 3D 平面（见
                        // [WindowCurtain]），挂在沙盘前面、Augment 弹层后面，不再和这里的文字
                        // 抢同一块 2D 画布。这里只剩纯 UI：蓄力标签 + 蓄力条。
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = null,
                                    enabled = chargeEnabled,
                                    onClick = {}, // 真正的逻辑在上面的 interactionSource 收流里
                                )
                                .padding(horizontal = 24.dp, vertical = 8.dp),
                        ) {
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
