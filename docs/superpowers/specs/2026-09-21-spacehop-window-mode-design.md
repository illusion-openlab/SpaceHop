# SpaceHop 3D 窗口模式设计 — 一局游戏，两个容器

> 需求原话：「SpaceHop 应用需要额外增加空间感，想要的效果是：创建一个 3D 窗口可以直接进行游戏，也可以通过点击游戏上的『进入沉浸』来开始当前的模式」

## 0. 元信息

- 应用 / applicationId：SpaceHop / `tech.illusion.spacehop`
- Spatial SDK 版本：6.0.0（`tracking` / `core` / `ui` / `ui.foundation` / `foundation` 均为 6.0.0）
- 设计源类型：一句话意图，经 brainstorming 逐题澄清
- 用户确认时间：2026-09-21，§1–§5 逐段确认通过
- 本设计的实现分支：`feat/window-mode`

### 用户在本次设计中做出的五个决定

| # | 问题 | 决定 |
|---|---|---|
| 1 | 窗口模式怎么蓄力起跳 | 按住 3D 视图蓄力，松手起跳 |
| 2 | 点「进入沉浸」时当前这一局怎么办 | 无缝带过去：分数、连击、当前平台、厨房/荷塘，全部继续 |
| 3 | 是否支持反向切回窗口 | 支持且对称，局面同样无缝带回 |
| 4 | 启动默认落哪个容器 | 启动进 3D 窗口，沉浸由玩家主动进 |
| 5 | 窗口默认尺寸 | 0.5 × 0.4 × 0.45 m，场景缩到 ~0.55× |

## 1. 现状与约束

### 1.1 现状

SpaceHop 目前是纯 Stage 应用。`Main.kt` 只有 `DefaultStage { PicoTheme { HomeStage() } }`，`AndroidManifest.xml` 在 `LaunchActivity` 上声明 `pico.spatial.stage.style=1`（Mixed）、`immersion` 锁 100。启动即沉浸。

玩法输入全部来自 `PinchChargeController`：手部捏合蓄力为主，手柄扳机为第二路径。板面高度由 `HMDTrackingProvider` 做头高校准（`StageCalibration`）。整个玩法编排（575 行）集中在 `content/HomeStage.kt` 的单个 `@Composable fun HomeStage()` 内。

### 1.2 决定性约束：tracking 在 WindowContainer 里全部不可用

`tracking-6.0.0-sources.jar` 实测：`HandTrackingProvider`、`ControllerTrackingProvider`、`HMDTrackingProvider`、以及它们共同的父接口 `DataProvider<T>`，**全部**带 `@RequiredFullSpace` 注解。该注解在 `foundation-6.0.0-sources.jar` 中的文档原文：

> Indicates that the annotated class or function must be used within the "FullSpace" state. Using it outside the "FullSpace" state may result in errors or invalid data.

同工作区的 `SpaceFireworks/content/HomeVolume.kt` 已在代码注释中记录过真机行为：*"HandTrackingProvider carries @RequiredFullSpace and stays PENDING forever under a WindowContainer."*

**结论**：把现有玩法原样搬进 3D 窗口，捏合蓄力、手柄扳机、头高校准三条路同时失效。窗口模式必须有一套独立的输入实现。这是本设计存在的根本原因。

### 1.3 次级约束：帧钟

`CharacterController`（3 处）与 `StageBoardAnimator`（2 处）的全部动画由 `withFrameNanos` 驱动。实测记录（Overwinter，2026-08-18，`DefaultWindowContainer`）：`withFrameNanos` 只触发一次就不再恢复，用它驱动的游戏循环一帧都不跑。症状具欺骗性——首次组合已画出一帧，画面有内容只是永不变化，容易误判为状态机 bug。同一写法在 SpaceVinyl 中又工作正常，说明行为依上下文而变，不可依赖。

### 1.4 可用的替代输入（已验证签名）

`com.pico.spatial.ui.foundation.gesture.ComposeTapGestureDetectorExt`：

```kotlin
suspend fun PointerInputScope.detectTapGestures(
    context: Context,
    targetedToEntity: TargetEntity?,   // null = 2D 与 3D 交互事件都接收
    onDoubleTap: ((Offset) -> Unit)? = null,
    onLongPress: ((Offset) -> Unit)? = null,
    onPress: suspend PressGestureScope.(Offset) -> Unit = NoPressGesture,
    onTap: ((Offset) -> Unit)? = null,
)
```

`onPress` 是带 `PressGestureScope` 接收者的挂起 lambda，可在其中 `tryAwaitRelease()`。**按住时长即蓄力时长**，与现有 `onRelease(cappedHeldMillis, isLeftHand)` 回调形状同构。

### 1.5 容器切换 API（已验证签名）

`com.pico.spatial.ui.platform.containers.SpatialNavigator`：

```kotlin
fun openWindowContainer(id: String, tag: String? = null, bundle: Bundle? = null)
fun closeWindowContainer(id: String, tag: String? = null)
suspend fun openStage(id: String, style: StageStyle? = null, bundle: Bundle? = null,
                      upperLimbRenderMode: UpperLimbRenderMode = UpperLimbRenderMode.Default): OpenStageResult
suspend fun closeStage()
```

`OpenStageResult` 为 sealed class，三种取值：`Allowed` / `NotAllowed` / `Error(code, reason)`。

## 2. 架构：方案 A —— 会话上提 + 三个注入缝

已评估并否决的两个备选：

- **方案 B（状态上提，编排各写一份）**：改动面小、零回归风险，但回合计时、强制失手、fast streak 这些微妙逻辑会有两份拷贝。逻辑漂移不是风险而是必然，且这类 bug 在游戏中最难查。**否决。**
- **方案 C（给 `HomeStage()` 加 `mode` 参数）**：代码搬动最少、单一真相源，但会把一个已经过大的函数变成塞满 `if (mode == ...)` 的怪物，可读性与可测性双降。**否决。**

**采用方案 A。** 一次性重构成本真实存在，但拆完的结构恰好就是"两个容器 + 一份规则"这件事本身的形状；而 575 行的 `HomeStage.kt` 本来就到了该拆的体量。

### 2.1 三个注入缝

| 缝 | 沉浸态实现 | 窗口态实现 |
|---|---|---|
| `ChargeInput` | `TrackingChargeInput`（现有捏合/扳机逻辑） | `PointerChargeInput`（`detectTapGestures` press-hold） |
| `HopClock` | `withFrameNanos`（现状零改动） | `delay(11ms)` + `System.nanoTime()` |
| 根摆位与缩放 | `StageCalibration.boardPosition()`，1.0× | 窗口体积中心偏后下，0.55× |

### 2.2 文件落点

| 文件 | 内容 |
|---|---|
| `game/HopSession.kt` | 跨容器会话单例 |
| `content/HopRun.kt` | `rememberHopRun(session, input, clock)`：回合计时、强制失手、fast streak、落点判定 —— 两容器共用 |
| `content/ImmersiveHome.kt` | 沉浸壳：tracking 输入、HMD 校准、`StageCalibration` 摆位、三块 `AttachmentPanel` |
| `content/WindowHome.kt` | 窗口壳：pointer 输入、0.55× 缩放、`Augment` 工具条 |
| `content/ContainerSwitch.kt` | 双向切换 + `OpenStageResult` 失败回滚 |
| `content/ChargeInput.kt` | `ChargeInput` 接口 + 两个实现 |
| `content/HopClock.kt` | `HopClock` 接口 + 两个实现 |

`HomeStage.kt` 在拆分后消失，其内容分流到 `HopRun.kt` 与 `ImmersiveHome.kt`。

## 3. 容器架构与启动

### 3.1 Manifest 换主

`LaunchActivity` 上现有的 `pico.spatial.stage.*` 四条 meta-data 全部移除，换成：

| key | 值 | 理由 |
|---|---|---|
| `pico.spatial.windowcontainer.id` | `SpaceHopVolume` | 反向切换需按 id 重开 |
| `pico.spatial.windowcontainer.style` | `2`（Volumetric） | 体积窗口，非平面窗口 |
| `pico.spatial.windowcontainer.defaultsize` | `0.5x0.4x0.45` | 用户选定的桌面摆件尺度 |
| `pico.spatial.windowcontainer.defaultsize.unit` | `meters` | — |
| `pico.spatial.windowcontainer.worldscaletype` | `2`（Fixed） | 沙盘必须遵守真实透视；Dynamic 的"恒定视觉大小"会毁掉摆件感 |
| `pico.spatial.windowcontainer.resizerestriction` | `1`（Uniform） | 玩法几何锁死纵横比，非等比拉伸会把沙盘拉变形 |
| `pico.spatial.windowcontainer.volumebasepanel` | `0`（可见） | 底板即沙盘桌面，青蛙站在上面 |
| `pico.spatial.windowcontainer.materialbackground` | `0`（关闭） | 沙盘要看穿到真实房间。主窗口的毛玻璃**只能**在 manifest 关闭，DSL 的 `enableMaterialBackground` 参数只作用于子窗口 |

保留不变：`pico.spatial.ui.Logger=1`、`icon.3d.list`、`icon.sdf.list`、`android:localeConfig`、`HAND_TRACKING` 权限（沉浸态仍需要）。

### 3.2 Main.kt

```kotlin
fun mainApp(scope: SpatialAppScope) = with(scope) {
    DefaultWindowContainer { PicoTheme { WindowHome() } }
    Stage(id = IMMERSIVE_STAGE_ID) { PicoTheme { ImmersiveHome() } }
}
```

两个容器都在启动时注册，但 Stage 直到「进入沉浸」被点击才真正打开。

### 3.3 沉浸态使用 StageStyle.Mixed，不是 Full

`SpaceFireworks` 使用 `StageStyle.Full` 并在注释中称这是"手部追踪能工作的原因"。该说法把 **FullSpace 状态**与 **`StageStyle.Full`** 混为一谈：`StageStyle` 的四个取值（`Automatic` / `Mixed` / `Progressive` / `Full`）描述的是虚实混合比例，而 Stage 容器本身无论何种 style 都处于 FullSpace 状态。

证据：SpaceHop 现行 manifest 写的是 `stage.style=1`（Mixed），而捏合追踪在已上架版本中工作正常。

因此本设计使用 `StageStyle.Mixed`，保持现有观感。使用 `Full` 会关闭视频透视、把青蛙从用户家中桌面搬进黑盒，是对现有体验的无谓改变。

### 3.4 切换时序

```
窗口 → 沉浸：openStage(IMMERSIVE_STAGE_ID, StageStyle.Mixed)
             → 结果为 Allowed 才 closeWindowContainer(SpaceHopVolume)
沉浸 → 窗口：openWindowContainer(SpaceHopVolume) → closeStage()
```

**先开后关**，保证任何时刻至少有一个活容器。

**必须检查 `OpenStageResult`。** 当别的应用正占用 Full Space 时 `openStage` 会返回 `NotAllowed`（本工作区已实测遇到过）。此时绝不能关闭窗口，否则应用将没有任何活容器而直接消失。失败处理：留在窗口态，在工具条上给出一行提示，session 不做任何变更。

**必须关闭旧容器而非最小化。** Stage 打开只会隐藏*其他应用*的窗口，自己的窗口会继续飘在沉浸场景里（`SpaceFireworks` 模拟器实测）。关闭同时也释放该容器的全部场景资源。

## 4. 会话与局面交接

### 4.1 HopSession

```kotlin
object HopSession {          // 应用级单例，不属于任何容器
    var runState: RunState by mutableStateOf(RunState())       // 分数、连击、bestComboStreak、jumpsCompleted、isAlive
    var boardState: StageBoardState by mutableStateOf(...)     // platforms、current（含 kind：KITCHEN / LOTUS）
    var phase: HopPhase by mutableStateOf(HopPhase.NotStarted) // NotStarted / Playing / Result
    var paused: Boolean by mutableStateOf(false)
    var jumpCommittedThisRound: Boolean by mutableStateOf(false)
    var isFlying: Boolean by mutableStateOf(false)             // 见 §4.2 规则 2：飞行中禁止切换
}
```

窗口的 composition 在 `closeWindowContainer` 时整个 dispose，因此这些状态**必须**活在 composition 之外。这是选择方案 A 的根本原因。

**字段必须由 `mutableStateOf` 支撑，不能是普通 `var`。** 已知实测教训：纯逻辑类的普通 `var` 在组合里读只订阅绘制阶段，`when (session.phase)` 会卡在首次求值——点了按钮画面不切，而编译、单测、代码审查全都发现不了。单例本身不含 Compose 类型这件事在这里要让位于可观测性；`HopSession` 是视图状态的持有者，不是被测的纯逻辑（纯逻辑仍在 `RunState` / `StageBoardState` 等数据类里，它们保持不含 Compose 类型、继续可测）。

最高分已由 `HighScoreStore` 持久化，无需进入 session。

**不使用 `openStage(bundle = ...)` 传递状态**：Bundle 要求全部字段可序列化，而 `StageBoardState` 内嵌 `PlatformSpec` 列表；单例既不需要序列化，也天然覆盖"切换失败需回滚"的情况。

### 4.2 三条交接规则

1. **fast streak 时间戳在切换时清空。** `recentLandingTimestampsMs` 判定的是"10 秒内连落 10 块"，而容器切换耗时数秒真实时间。带过去会让该窗口被切换耗时污染——要么白送一次触发，要么无故作废。清空的理由站得住：切换期间玩家确实没有在连跳，streak 确实已断。

2. **飞行中禁止切换。** 青蛙起跳到落地约 420ms，这段的动画状态在 `CharacterController` 内而不在 session 内。与其序列化飞行状态，不如在飞行期间将「进入沉浸 / 退出沉浸」按钮置灰。代价近乎为零，消除一整类竞态。

3. **切换全程 `paused = true`。** 复用现有暂停标志（退出确认框已在使用），由新容器的 `SpatialView.initial` 建完场景后解冻。否则玩家会在切换的空窗期被回合超时"抓住"——`ROUND_TIME_LIMIT_MS` 为 10.2 秒，而它的轮询式实现（`ROUND_TIMEOUT_POLL_MS` 每 100ms 检查 `paused`）本来就是为了能被冻结才写成轮询的。

## 5. 输入抽象与帧钟

### 5.1 ChargeInput

```kotlin
interface ChargeInput {
    val chargeState: StateFlow<ChargeUiState>
    fun start(
        onChargeStart: (HandController?) -> Unit,
        onRelease: (cappedHeldMillis: Long, hand: HandController?) -> Unit,
    )
    fun stop()
}
```

**`TrackingChargeInput`（沉浸态）**：现有 `PinchChargeController` 逻辑原封不动，仅将 `isLeftHand: Boolean` 映射为 `HandController.Left/Right`。

需要的唯一结构改动：`PinchChargeController` 当前是 `object` 单例。改为 class，由容器 `remember` 持有。接口需要实例；且两个容器交替 `start`/`stop` 一个全局可变单例迟早出事。这属于"修好挡路的东西"，不是无关重构。

**`PointerChargeInput`（窗口态）**：不是 provider，是由 Compose 手势驱动的普通对象。

```kotlin
Modifier.pointerInput(enabled) {
    detectTapGestures(context, targetedToEntity = null, onPress = {
        val downNanos = System.nanoTime()
        onChargeStart(null)
        tryAwaitRelease()
        onRelease(cappedHeldMillis(downNanos), null)
    })
}
```

### 5.2 输入的三条裁决

- **手势挂在整个 SpatialView 上，不挂青蛙实体**（`targetedToEntity = null`，2D 与 3D 事件都接收）。原玩法的跳跃方向由下一块平台决定，本来就不需要瞄准；强迫玩家先指准青蛙只会增加操作负担。

- **窗口态没有显式手柄震动。** `SpatialPointerInfo` 能给出 `kind`（`DirectPinch` / `GazePinch` / `RayBasedPinch` / `Poke` / `Pointer`）、`pointerId`、`inputDevicePose`，但**给不出左右手**；而 `HandController` 枚举只有 `Left` / `Right` / `Unknown`。因此窗口态不调用 `LocalHandControllerHaptic.feedback()`，依赖 SpatialUI 交互元素自带的按压反馈。这是 SDK 能力缺口，不是设计选择。

- **蓄力手势与工具条按钮互斥挂载。** 已知实测教训：拖拽检测器会条件性消费事件、点击检测器一见消费就取消，带位移的点击会间歇性失效。本设计的做法是蓄力节点只在 `phase is Playing && !isFlying` 时挂载，而按钮区位于 SpatialView 之外，物理上不重叠。

- **`ChargeCurve` 两端共用**，同一 `heldMillis → distance` 映射。这是"同一局跨容器"能够成立的前提：手感一致，分数可比。

> **本节第一条设计已被真机证伪，实际实现与此不同（2026-09-22 追记）。**
>
> "手势挂在整个 `SpatialView` 上"在真机上完全收不到任何事件——不是瞄不准，是协程连启动都不会
> 启动。三处独立验证：挂在 `SpatialView` 自己的 modifier 上、挂在一个完全独立不经过
> `AndroidView` 包装的裸 Compose `Box` 上、挂在 `Augment` 弹层内部，`Modifier.pointerInput`
> （不管是这里写的 `com.pico.spatial.ui.foundation.gesture.detectTapGestures` 还是尝试直接引用
> 的 vanilla AndroidX 版本）在体积 `WindowContainer` 的根组合上一律无法启动其协程。运行时自己的
> 空间手势识别在更底层把触摸事件截去做 3D 实体命中测试，Compose 自己的指针事件管线在这层根本
> 收不到东西。
>
> 唯一确认能收到事件的路径是 SpatialUI 内建组件用的 `androidx.compose.foundation.clickable`
> （`Button` 内部机制），且必须渲染在 `Augment`/`AttachmentPanel` 这类独立弹层里——已验证连续
> 多次点击稳定命中。
>
> **实际实现**：蓄力手势从"按住 3D 视图任意位置"改为"按住工具条上一个专用条形区域"，用
> `Modifier.clickable(interactionSource, indication=null)` + `MutableInteractionSource`
> 收 `PressInteraction.Press`/`Release`/`Cancel` 拿按压时长，而不是本节的 `detectTapGestures` +
> `onPress`/`tryAwaitRelease()`。`PointerChargeInput` 类本身（`onPressDown`/`onPressUp`/
> `tickChargeFraction`/`ChargeCurve` 封顶逻辑）未受影响，只是调用它的手势入口换了。
>
> 详见 `docs/superpowers/plans/2026-09-21-spacehop-window-mode.md` 结尾的真机修复记录，以及
> commit `b337773`。工作区记忆：`pointerinput-dead-in-windowcontainer-root.md`。

### 5.3 HopClock

```kotlin
fun interface HopClock { suspend fun awaitTick(): Long }   // 返回纳秒
// 沉浸：HopClock { withFrameNanos { it } }
// 窗口：HopClock { delay(11); System.nanoTime() }
```

`CharacterController` 三处、`StageBoardAnimator` 两处 `withFrameNanos` 调用改为 `clock.awaitTick()`。

**为什么不两端统一用 `delay`**：沉浸态当前是 vsync 对齐的，已上架且工作正常，改动它是无谓风险（最小可逆变更原则）。

**为什么窗口态不敢用 `withFrameNanos`**：见 §1.3。

## 6. 窗口场景构成

### 6.1 缩放

玩法几何：最远跳跃 `MAX_DISTANCE_CEILING_M = 0.55`，平台半径 `BASE_FOOTPRINT_RADIUS_M = 0.09`，横向偏移 `MAX_LATERAL_OFFSET_M = ±0.08`。场景实际占用约 0.73m 深 × 0.34m 宽。

窗口体积 0.5 × 0.4 × 0.45 m。根视觉实体统一缩放 **0.55×** → 0.40m 深 × 0.19m 宽，深度方向留 0.05m 余量。

实体被裁剪到 **SpatialView 的** bounding box（不是 WindowContainer 的），因此根锚点需向后下方偏移，保证最远平台仍在盒内。

**缩放只作用于视觉根实体，玩法几何的米数一律不变。** `DifficultyCurve`、`PlatformSpawner`、`LandingJudge`、`ScoreRules` 不改一行，现有 13 个测试文件全部继续有效。窗口被用户 uniform 缩放后重新测量（`onGloballyPositioned`）只重算缩放因子，判定米数纹丝不动。

### 6.2 窗口内的 UI

不使用 `AttachmentPanel`（那是 Stage 的摆法）。

- 体积底部一条 `Augment` 工具条：左侧为蓄力条（复用 `ChargeIndicatorColor` 的配色规则）加分数/连击（`HudContent` 常驻，窗口内空间足够，不需要独立 side panel），右侧为「进入沉浸」按钮。
- 开始页 / 结算页：`StartPageContent` / `ResultPanelContent` 原样复用，作为 2D 覆盖层贴在窗口正面。
- `IndicatorBallsRenderer` 不在窗口内 build —— 没有手部数据，四颗指尖球没有任何东西可跟随。
- 窗口态的蓄力可见性由两处承担：工具条蓄力条，以及青蛙自身的下蹲压缩（`CharacterController` 已有的蓄力形变）。

### 6.3 沉浸态新增「退出沉浸」

放入现有 `SIDE_PANEL` 的 `HudContent`，不新增面板（该面板本就只在 `Playing` 时显示）。

**已知陷阱**：按这个按钮本身就是一次捏合，而 `PinchChargeController` 独立于按钮监听裸手距离，会把同一次物理松手读成一次完整蓄力并立即起跳。现有代码已为「开始」按钮解决过该问题（`JUMP_INPUT_GRACE_MS = 400ms` 吞掉开局瞬间的松手）。退出按钮复用同一机制：按下即 `paused = true` 并打开吞没窗口。

## 7. 验证策略

### 7.1 JVM 单测

- `HopSession` 往返：窗口态一局 → 写入 session → 沉浸态读出，分数 / 连击 / `bestComboStreak` / 当前平台 / `current.kind` 逐项相等。
- 切换失败回滚：`openStage` 返回 `NotAllowed` / `Error` 时窗口仍在、session 未被破坏。**为使这条可测，`ContainerSwitch` 不直接调用 `LocalSpatialNavigator`，而是接受一个函数缝**（`suspend (String, StageStyle) -> OpenStageResult` 与 `(String) -> Unit`），生产代码传入 navigator 的方法引用，测试传入假实现。切换的决策逻辑因此是纯 JVM 可测的，SDK 调用本身不测。
- 蓄力封顶一致：`cappedHeldMillis` 的计算在 `TrackingChargeInput` 与 `PointerChargeInput` 两个实现中给出相同结果（两者都经由同一 `ChargeCurve`，这条测的是两个实现各自的计时与封顶代码没有写歪，而不是 `ChargeCurve` 本身）。
- 缩放不污染玩法：`PlatformSpawner` / `LandingJudge` 输出与窗口缩放因子无关。

现有 13 个测试文件不需修改 —— 这是 §6.1"缩放只动视觉根实体"挣回来的。

### 7.2 模拟器验证（四个已知会骗人的地方）

1. **沉浸态在模拟器上帧钟只有 1Hz**（实测），而同一会话的平面/窗口侧 `delay` 循环能跑满 60–80fps。任何"等 N 秒后截图"的验证脚本在沉浸侧必然踩空，必须改为**按条件重试**（轮询日志锚点出现为止）。反之，窗口侧的 `delay` 帧钟在模拟器上是能真验的 —— 这恰是本次最需要验证的新代码。
2. **截图可能是陈旧帧**，实测差距曾达 ≥6 秒。短命 UI（蓄力条、飞行中）用截图取证会得到假阴性。每张截图必须配一条日志锚点交叉核对。
3. **手势在模拟器上验不了。** 窗口蓄力是 press-hold，`adb tap` 摸得到管线但点不准按钮，还可能把沉浸态关掉。用 androidTest 探针绕过 UI 层直接驱动 `PointerChargeInput`，验完删除。
4. **模拟器的 `Build.TYPE` 是 `user`。** 调试钩子绝不能写 `if (Build.TYPE != "user")` 守卫 —— 会静默不跑，看着像逻辑写错。

**验证档必须有一档走真实路径**：不能用"首次组合前就把状态设好"的 DEBUG 截图 —— 那样 `when` 第一次求值就选对分支，截图完全正常，正好绕过帧循环与状态机的 bug。正确做法：启动停在窗口初始态 → 延时后由代码触发开局与一次蓄力起跳 → 再截图。

**环境风险**：别的会话占用 Full Space 时 `openStage` 返回 `NotAllowed`。这既是要测的代码路径，也是验证时的干扰源。全程走设备锁，owner 字符串跨 Bash 调用保持稳定（不可使用 `$$` 之类每次调用都变的值），截图完成后立即 stop 应用并释放锁。

### 7.3 真机验证（需用户试玩）

模拟器给不出结论的三件事：

- 0.55× 沙盘在真机上是否够看清 —— 平台半径缩至 5cm 量级，落点判定的"完美 / 普通 / 失手"视觉差别很细。
- 按住 3D 视图蓄力的手感，以及射线指向下按住是否会漂移。
- 0.5 × 0.4 × 0.45 m 摆在房间里是否是合适的存在感。

真机上 `adb screencap` 是坏的（退出码为 0、产物却是 50 字节错误文本，按 `$?` 判断必被骗），因此真机侧的判活只能看日志，视觉结论依赖用户肉眼。

### 7.4 门禁

实现阶段走 `spatial-design-first-build` 的两道门：设计契约（本文件）与限时 90 秒的设备截图验证。

## 8. 兼容性与风险

| 项 | 影响 |
|---|---|
| 老用户行为改变 | 启动不再直接进沉浸，改为先进 3D 窗口。用户已明确接受（决定 #4） |
| 沉浸态玩法 | 除新增「退出沉浸」按钮与 `HopClock` 注入外，行为不变；`StageStyle.Mixed` 保持现有观感 |
| 现有单测 | 全部继续有效，不需修改 |
| `HAND_TRACKING` 权限 | 保留（沉浸态仍需），窗口态不启动任何 provider |
| 风险：窗口帧钟 | `delay(11ms)` 已在同工作区验证可行，但需在本项目实测确认 |
| 风险：`openStage` 被拒 | 已设计回滚路径，需实测覆盖 |
| 风险：0.55× 可读性 | 只能真机判断，可能需要调整窗口默认尺寸或缩放因子 |

## 9. 明确不做的事

- 不改动 `DifficultyCurve` / `PlatformSpawner` / `LandingJudge` / `ScoreRules` / `StageBoard` 任何玩法规则。
- 不改动 V2 的窗口穿越与荷塘关卡逻辑。
- 不为窗口态设计不同的难度曲线或计分规则。
- 不引入 ViewModel、DI 框架或任何新依赖。
- 不做与本功能无关的重构；`HomeStage.kt` 的拆分是本功能的直接需要。
