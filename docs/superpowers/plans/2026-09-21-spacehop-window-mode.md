# SpaceHop 3D 窗口模式 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 SpaceHop 启动进一个 0.5×0.4×0.45m 的 3D 体积窗口并可直接游玩，点「进入沉浸」无缝把当前这一局带进 Stage，反向亦然。

**Architecture:** 把跨容器的局面状态提升到应用级单例 `HopSession`（Compose snapshot state 支撑），把玩法编排从 `HomeStage.kt` 抽成两容器共用的 `rememberHopRun`，并开三个注入缝——`ChargeInput`（沉浸=捏合/扳机，窗口=press-hold）、`HopClock`（沉浸=`withFrameNanos`，窗口=`delay`）、根摆位与缩放（沉浸 1.0×，窗口 0.55×）。玩法规则（`DifficultyCurve`/`PlatformSpawner`/`LandingJudge`/`ScoreRules`/`StageBoard`/`ChargeCurve`）一行不改。

**Tech Stack:** Kotlin + PICO Spatial SDK 6.0.0（`DefaultWindowContainer` / `Stage` DSL、`SpatialView`、`Augment`、`SpatialNavigator`）、Jetpack Compose runtime、JUnit 4。

**Spec:** `docs/superpowers/specs/2026-09-21-spacehop-window-mode-design.md`

**Branch:** `feat/window-mode`（已创建，spec 已提交于 `630d2f1`）

## Global Constraints

- **不新增任何依赖。** `app/build.gradle.kts` 的 `dependencies {}` 块不得改动。已实测确认：`androidx.compose.runtime` 的 `mutableStateOf` 与 `kotlinx.coroutines.runBlocking` 在单测 JVM classpath 上可用，无需 `kotlinx-coroutines-test`。
- **测试用 JUnit 4 + `runBlocking`**，不要用 `runTest` / `TestScope` / `advanceTimeBy`（依赖不存在）。
- **不改动玩法规则文件**：`game/DifficultyCurve.kt`、`game/PlatformSpawner.kt`、`game/LandingJudge.kt`、`game/ScoreRules.kt`、`game/StageBoard.kt`、`game/ChargeCurve.kt`、`game/PinchStateMachine.kt`、`game/PinchGeometry.kt`、`game/ChargeIndicatorColor.kt`、`game/HighScoreStore.kt`、`game/RunState.kt`（`PlatformSpec` / `PlatformKind` / `LandingResult` 都定义在 `game/LandingJudge.kt` 里，没有单独的 `PlatformSpec.kt`）。现有 13 个测试文件（含模板 `ExampleUnitTest`）必须保持全绿且不被修改。
- **UI 一律 SpatialUI + `PicoTheme`**（`com.pico.spatial.ui.design.*`）。禁止 `androidx.compose.material` / `material3` / `MaterialTheme`。
- **构建与测试命令**（每次都要先 export，环境变量不跨 Bash 调用持续）：
  ```bash
  export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
  cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceHop
  ./gradlew :app:testDebugUnitTest --tests "<pattern>"
  ./gradlew :app:assembleDebug
  ```
- **验证单测真的跑了**：`./gradlew` 加 `-q` 成功时零输出。不要把零输出当成"通过"，要看
  `app/build/test-results/testDebugUnitTest/TEST-<类全名>.xml` 里的 `tests="N" failures="0"`。
- **沉浸态用 `StageStyle.Mixed`，绝不用 `StageStyle.Full`**（见 spec §3.3）。
- **窗口态不得启动任何 tracking provider**（`HandTrackingProvider` / `ControllerTrackingProvider` / `HMDTrackingProvider` 全带 `@RequiredFullSpace`，在 WindowContainer 下 `start()` 永远返回 `PENDING`，`dataFlow` 永不发射，且不报错）。
- **Kotlin 不会对未使用的 import 报警**。每次删代码后手工核对 import 块，编译通过不等于 import 干净。

---

### Task 1: HopClock —— 可注入的帧钟

**Files:**
- Create: `app/src/main/java/tech/illusion/spacehop/content/HopClock.kt`
- Test: `app/src/test/java/tech/illusion/spacehop/content/HopClockTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `fun interface HopClock { suspend fun awaitTick(): Long }`（返回纳秒）
  - `object HopClocks { val frameAligned: HopClock; fun delayDriven(intervalMs: Long = 11L): HopClock }`

为什么需要：`CharacterController`（3 处）和 `StageBoardAnimator`（2 处）的动画都由 `withFrameNanos` 驱动，而 `withFrameNanos` 在 `DefaultWindowContainer` 里实测只触发一次，用它驱动的循环一帧都不跑。症状具欺骗性——首帧已画出，画面有内容但永不变化。

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/tech/illusion/spacehop/content/HopClockTest.kt`：

```kotlin
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
```

- [ ] **Step 2: 跑测试确认失败**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceHop
./gradlew :app:testDebugUnitTest --tests "tech.illusion.spacehop.content.HopClockTest"
```

预期：编译失败，`Unresolved reference: HopClocks`。

- [ ] **Step 3: 写实现**

创建 `app/src/main/java/tech/illusion/spacehop/content/HopClock.kt`：

```kotlin
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
```

- [ ] **Step 4: 跑测试确认通过**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceHop
./gradlew :app:testDebugUnitTest --tests "tech.illusion.spacehop.content.HopClockTest"
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-tech.illusion.spacehop.content.HopClockTest.xml
```

预期：`tests="2" skipped="0" failures="0" errors="0"`。

- [ ] **Step 5: 把 CharacterController 的三处 withFrameNanos 换成注入的 clock**

修改 `app/src/main/java/tech/illusion/spacehop/content/CharacterController.kt`：

1. 删除 `import androidx.compose.runtime.withFrameNanos`（第 4 行）。
2. 给主构造函数加一个 `private val clock: HopClock` 参数（`CharacterController private constructor(...)`，第 59 行），放在 `jumpAudioController` 之后。
3. `companion object` 的 `load` 签名（第 77 行）改为：

```kotlin
suspend fun load(anchor: Entity, characterRadiusMeters: Float, clock: HopClock): CharacterController {
```

并在函数末尾构造 `CharacterController(...)` 处把 `clock` 传下去。

4. `flyTo`（第 262、265 行）、`fall`（第 289、293 行）、`recenterTo`（第 325、327 行）里全部 6 处 `withFrameNanos { it }` 替换为 `clock.awaitTick()`。例如：

```kotlin
        val startNanos = clock.awaitTick()
        var endPosition = basePosition
        while (true) {
            val nowNanos = clock.awaitTick()
```

- [ ] **Step 6: 把 StageBoardAnimator 的两处 withFrameNanos 换成注入的 clock**

修改 `app/src/main/java/tech/illusion/spacehop/content/StageBoardAnimator.kt`：

1. 删除 `import androidx.compose.runtime.withFrameNanos`（第 4 行）。
2. `class StageBoardAnimator {`（第 126 行）改为 `class StageBoardAnimator(private val clock: HopClock) {`。
3. `animateAdvance` 里的 4 处调用（第 377、379、391、393 行）替换：

```kotlin
        val slideStartNanos = clock.awaitTick()
        while (true) {
            val t = ((clock.awaitTick() - slideStartNanos) / 1_000_000f / ADVANCE_PHASE_MS)
                .coerceIn(0f, 1f)
```

```kotlin
        val approachStartNanos = clock.awaitTick()
        while (true) {
            val t = ((clock.awaitTick() - approachStartNanos) / 1_000_000f / ADVANCE_PHASE_MS)
                .coerceIn(0f, 1f)
```

- [ ] **Step 7: 更新 HomeStage 的两个构造点，保持沉浸态行为不变**

修改 `app/src/main/java/tech/illusion/spacehop/content/HomeStage.kt`：

- `val boardAnimator = remember { StageBoardAnimator() }` → `val boardAnimator = remember { StageBoardAnimator(HopClocks.frameAligned) }`
- `CharacterController.load(anchor, characterRadiusMeters = 0.05f)` → `CharacterController.load(anchor, characterRadiusMeters = 0.05f, clock = HopClocks.frameAligned)`

- [ ] **Step 8: 全量编译 + 全量单测**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceHop
./gradlew :app:assembleDebug :app:testDebugUnitTest
ls app/build/test-results/testDebugUnitTest/*.xml | wc -l
grep -L 'failures="0" errors="0"' app/build/test-results/testDebugUnitTest/*.xml
```

预期：BUILD SUCCESSFUL；xml 文件数 14（原有 13 + `HopClockTest`）；`grep -L` 无输出（即没有任何一个文件缺少 `failures="0" errors="0"`）。

- [ ] **Step 9: 提交**

```bash
git add app/src/main/java/tech/illusion/spacehop/content/HopClock.kt \
        app/src/test/java/tech/illusion/spacehop/content/HopClockTest.kt \
        app/src/main/java/tech/illusion/spacehop/content/CharacterController.kt \
        app/src/main/java/tech/illusion/spacehop/content/StageBoardAnimator.kt \
        app/src/main/java/tech/illusion/spacehop/content/HomeStage.kt
git commit -m "refactor: inject the animation frame clock instead of calling withFrameNanos directly

withFrameNanos has been measured firing exactly once under a
DefaultWindowContainer, which would leave every animation in the coming
window mode frozen on its first frame. Stage keeps the vsync-aligned
clock it already had; the seam is what lets the window pass a
delay-driven one.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: HopSession —— 跨容器的局面会话

**Files:**
- Create: `app/src/main/java/tech/illusion/spacehop/game/HopSession.kt`
- Test: `app/src/test/java/tech/illusion/spacehop/game/HopSessionTest.kt`

**Interfaces:**
- Consumes: `RunState`、`StageBoardState`、`PlatformSpawner`（均已存在）
- Produces:
  - `sealed class HopPhase { NotStarted; Playing; data class Result(isNewRecord, diedInLotusPond) }`（从 `HomeStage.kt` 迁移到本文件并改为 public）
  - `object HopSession`，字段：`runState`、`boardState`、`phase`、`paused`、`jumpCommittedThisRound`、`isFlying`、`switching`
  - `fun HopSession.startNewRun(spawner: PlatformSpawner)`
  - `fun HopSession.onContainerSwitchStart()` / `fun HopSession.onContainerSwitchSettled()`
  - `fun freshBoardState(spawner: PlatformSpawner): StageBoardState`（从 `HomeStage.kt` 迁移，改为 public）

**注意：字段必须由 `mutableStateOf` 支撑。** 普通 `var` 在组合里读只订阅绘制阶段，`when (HopSession.phase)` 会卡在首次求值——点了按钮画面不切，而编译、单测、代码审查全都发现不了。

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/tech/illusion/spacehop/game/HopSessionTest.kt`：

```kotlin
package tech.illusion.spacehop.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

class HopSessionTest {

    private val spawner = PlatformSpawner(Random(7))

    @Before
    fun reset() {
        HopSession.startNewRun(spawner)
        HopSession.phase = HopPhase.NotStarted
    }

    /** 交接的核心保证：容器切换不碰任何一项局面数据。这条测的就是"无缝带过去"。 */
    @Test
    fun containerSwitchPreservesEveryRunField() {
        HopSession.runState = RunState(
            score = 15,
            comboStreak = 3,
            bestComboStreak = 7,
            jumpsCompleted = 21,
            isAlive = true,
        )
        HopSession.boardState = StageBoardState(
            platforms = listOf(
                PlatformSpec(30, 0f, 0.06f, 0.021f, 0f, kind = PlatformKind.LOTUS),
                PlatformSpec(31, 0.02f, 0.06f, 0.021f, 0.3f, kind = PlatformKind.LOTUS),
            ),
            current = PlatformSpec(30, 0f, 0.06f, 0.021f, 0f, kind = PlatformKind.LOTUS),
        )
        HopSession.phase = HopPhase.Playing

        val runBefore = HopSession.runState
        val boardBefore = HopSession.boardState

        HopSession.onContainerSwitchStart()
        HopSession.onContainerSwitchSettled()

        assertEquals(runBefore, HopSession.runState)
        assertEquals(boardBefore, HopSession.boardState)
        assertEquals(HopPhase.Playing, HopSession.phase)
        assertEquals(
            "荷塘关卡必须跟着走，否则玩家会被切回厨房",
            PlatformKind.LOTUS,
            HopSession.boardState.current.kind,
        )
    }

    /** 切换全程必须冻结，否则 10.2 秒的回合超时会在切换的空窗期里把玩家"抓住"。 */
    @Test
    fun containerSwitchFreezesTheRoundThenThaws() {
        HopSession.phase = HopPhase.Playing
        assertFalse(HopSession.paused)

        HopSession.onContainerSwitchStart()
        assertTrue("切换开始后必须冻结", HopSession.paused)
        assertTrue("切换中标志必须置起", HopSession.switching)

        HopSession.onContainerSwitchSettled()
        assertFalse("新容器建完场景后必须解冻", HopSession.paused)
        assertFalse(HopSession.switching)
    }

    /** 青蛙飞行中（动画状态在 CharacterController 里、不在 session 里）不能切换。 */
    @Test
    fun switchIsRefusedWhileFlying() {
        HopSession.phase = HopPhase.Playing
        HopSession.isFlying = true
        assertFalse(HopSession.canSwitchContainer())

        HopSession.isFlying = false
        assertTrue(HopSession.canSwitchContainer())
    }

    /** 已经在切换途中时不能再次触发，否则会开出两个 Stage。 */
    @Test
    fun switchIsRefusedWhileAlreadySwitching() {
        HopSession.phase = HopPhase.Playing
        HopSession.onContainerSwitchStart()
        assertFalse(HopSession.canSwitchContainer())
    }

    /** 开新局必须把上一局的残留全部清干净——包括切换用的那几个标志。 */
    @Test
    fun startNewRunClearsEverything() {
        HopSession.runState = RunState(score = 99, jumpsCompleted = 40)
        HopSession.paused = true
        HopSession.jumpCommittedThisRound = true
        HopSession.isFlying = true

        HopSession.startNewRun(spawner)

        assertEquals(0, HopSession.runState.score)
        assertEquals(0, HopSession.runState.jumpsCompleted)
        assertTrue(HopSession.runState.isAlive)
        assertFalse(HopSession.paused)
        assertFalse(HopSession.jumpCommittedThisRound)
        assertFalse(HopSession.isFlying)
        assertEquals(HopPhase.Playing, HopSession.phase)
        assertEquals("新局必须是厨房关卡", PlatformKind.PLATE, HopSession.boardState.current.kind)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceHop
./gradlew :app:testDebugUnitTest --tests "tech.illusion.spacehop.game.HopSessionTest"
```

预期：编译失败，`Unresolved reference: HopSession`。

**注意**：测试里用到了 `PlatformKind.PLATE` 和 `PlatformSpec` 的 `kind` 参数名。动手前先 `cat app/src/main/java/tech/illusion/spacehop/game/PlatformSpec.kt` 确认枚举常量与构造参数的真实拼写，与之不符就按真实拼写改测试（不要改 `PlatformSpec.kt` 本身）。

- [ ] **Step 3: 写实现**

创建 `app/src/main/java/tech/illusion/spacehop/game/HopSession.kt`：

```kotlin
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

    /** 切换失败（`openStage` 返回 NotAllowed/Error）时回滚到切换前的状态。 */
    fun onContainerSwitchFailed() {
        paused = false
        switching = false
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceHop
./gradlew :app:testDebugUnitTest --tests "tech.illusion.spacehop.game.HopSessionTest"
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-tech.illusion.spacehop.game.HopSessionTest.xml
```

预期：`tests="5" skipped="0" failures="0" errors="0"`。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/tech/illusion/spacehop/game/HopSession.kt \
        app/src/test/java/tech/illusion/spacehop/game/HopSessionTest.kt
git commit -m "feat: add HopSession, the run state that outlives either container

A window container's whole composition disposes on close, so anything
that must survive crossing into the Stage cannot live inside it. Backed
by snapshot state rather than plain vars: a plain var read from a
composition only subscribes the draw phase, so a phase switch would
never recompose and nothing -- not the compiler, not the tests, not a
reviewer -- would catch it.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: ChargeInput 抽象 + PinchChargeController 转实例

**Files:**
- Create: `app/src/main/java/tech/illusion/spacehop/content/ChargeInput.kt`
- Modify: `app/src/main/java/tech/illusion/spacehop/content/PinchChargeController.kt`（`object` → `class`，实现 `ChargeInput`）
- Modify: `app/src/main/java/tech/illusion/spacehop/content/HomeStage.kt`（改用实例）
- Modify: `app/src/main/java/tech/illusion/spacehop/content/IndicatorBallsRenderer.kt`（如引用了 `ChargeUiState` 的 `activeHandIsLeft`，保持不变即可；只在编译报错时才动）
- Test: `app/src/test/java/tech/illusion/spacehop/content/ChargeHandTest.kt`

**Interfaces:**
- Consumes: `ChargeUiState`、`IndicatorPositions`（已存在于 `PinchChargeController.kt`）
- Produces:
  - `enum class ChargeHand { LEFT, RIGHT }`
  - `interface ChargeInput { val chargeState: StateFlow<ChargeUiState>; fun start(onChargeStart: (ChargeHand?) -> Unit, onRelease: (Long, ChargeHand?) -> Unit); fun stop() }`
  - `class PinchChargeController : ChargeInput`

**为什么不直接用 SDK 的 `HandController`**：那是 `com.pico.spatial.ui.platform` 的类型，把它放进 `ChargeInput` 会让这个接口带上 SDK 依赖，也让本任务的单测跨进 Android 侧。用自己的 `ChargeHand`，只在沉浸壳里映射成 `HandController`。

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/tech/illusion/spacehop/content/ChargeHandTest.kt`：

```kotlin
package tech.illusion.spacehop.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargeHandTest {

    /** 沉浸态的捏合输入知道是哪只手；窗口态的 pointer 输入不知道（SpatialPointerInfo 给不出
     * 左右手），所以这个映射必须显式接受 null。 */
    @Test
    fun booleanHandMapsToChargeHand() {
        assertEquals(ChargeHand.LEFT, ChargeHand.fromIsLeft(true))
        assertEquals(ChargeHand.RIGHT, ChargeHand.fromIsLeft(false))
        assertNull(ChargeHand.fromIsLeft(null))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceHop
./gradlew :app:testDebugUnitTest --tests "tech.illusion.spacehop.content.ChargeHandTest"
```

预期：编译失败，`Unresolved reference: ChargeHand`。

- [ ] **Step 3: 写 ChargeInput 接口**

创建 `app/src/main/java/tech/illusion/spacehop/content/ChargeInput.kt`：

```kotlin
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
```

- [ ] **Step 4: 跑测试确认通过**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceHop
./gradlew :app:testDebugUnitTest --tests "tech.illusion.spacehop.content.ChargeHandTest"
grep -o 'tests="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-tech.illusion.spacehop.content.ChargeHandTest.xml
```

预期：`tests="1" failures="0" errors="0"`。

- [ ] **Step 5: 把 PinchChargeController 从 object 改成 class 并实现 ChargeInput**

修改 `app/src/main/java/tech/illusion/spacehop/content/PinchChargeController.kt`：

1. `object PinchChargeController {` → `class PinchChargeController : ChargeInput {`。
2. KDoc 末尾追加一段，说明它为什么不再是单例：

```kotlin
 *
 * 不再是 `object`：现在有两个容器会交替 [start]/[stop] 输入源，共享一个全局可变单例迟早会出
 * 现一个容器的 stop 清掉另一个容器刚建好的回调。由容器各自 `remember` 一个实例。
```

3. `private val onChargeStart: ((isLeftHand: Boolean) -> Unit)?` →
   `private var onChargeStartCallback: ((ChargeHand?) -> Unit)? = null`；
   `onRelease` 同理改名为 `onReleaseCallback`，类型 `((Long, ChargeHand?) -> Unit)?`。
4. `start` 签名改为实现接口，并在函数体内把参数存进上面两个字段：

```kotlin
    override val chargeState: StateFlow<ChargeUiState> = _chargeState

    override fun start(
        onChargeStart: (ChargeHand?) -> Unit,
        onRelease: (cappedHeldMillis: Long, hand: ChargeHand?) -> Unit,
    ) {
        this.onChargeStartCallback = onChargeStart
        this.onReleaseCallback = onRelease
        handProvider.start()
        controllerProvider.addControllerActionListener(controllerActionListener)
        controllerProvider.start()
        job = CoroutineScope(Dispatchers.Main).launch {
            handProvider.dataFlow.collect { data -> processHandFrame(data.left, data.right) }
        }
    }

    override fun stop() { /* 原 stop 函数体不变，仅加 override */ }
```

5. 文件内所有 `onChargeStart?.invoke(isLeft)` 形式的调用改为
   `onChargeStartCallback?.invoke(ChargeHand.fromIsLeft(isLeft))`，
   `onRelease?.invoke(millis, isLeft)` 改为
   `onReleaseCallback?.invoke(millis, ChargeHand.fromIsLeft(isLeft))`。
   用 `grep -n "onChargeStart\|onRelease" app/src/main/java/tech/illusion/spacehop/content/PinchChargeController.kt` 找全所有调用点。
6. `val chargeState: StateFlow<ChargeUiState> = _chargeState` 加 `override`（见上）。

- [ ] **Step 6: 更新 HomeStage 改用实例**

修改 `app/src/main/java/tech/illusion/spacehop/content/HomeStage.kt`：

1. 在其它 `remember` 旁边加：`val chargeInput = remember { PinchChargeController() }`。
2. `PinchChargeController.start(` → `chargeInput.start(`；`PinchChargeController.stop()` → `chargeInput.stop()`；
   `PinchChargeController.chargeState.value` → `chargeInput.chargeState.value`。
3. 两处回调的参数从 `isLeftHand: Boolean` 改为 `hand: ChargeHand?`，内部的
   `if (isLeftHand) HandController.Left else HandController.Right` 改为：

```kotlin
                val target = when (hand) {
                    ChargeHand.LEFT -> HandController.Left
                    ChargeHand.RIGHT -> HandController.Right
                    null -> HandController.Unknown
                }
                controllerHaptic.feedback(HandControllerHapticType.Hover, target)
```

（`onRelease` 里同样的 `when`，配 `HandControllerHapticType.Press`。）
注意 `onRelease` 里原本已有一个名为 `target` 的局部变量（`boardState.platforms[1]`），
把震动用的这个命名为 `hapticTarget` 以免遮蔽。

- [ ] **Step 7: 全量编译 + 全量单测**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceHop
./gradlew :app:assembleDebug :app:testDebugUnitTest
grep -L 'failures="0" errors="0"' app/build/test-results/testDebugUnitTest/*.xml
```

预期：BUILD SUCCESSFUL，`grep -L` 无输出。

- [ ] **Step 8: 手工核对 import**

Kotlin 不会对未使用的 import 报警，编译通过证明不了 import 干净。逐个检查本任务改过的三个文件的 import 块，删掉已经不再引用的项。

- [ ] **Step 9: 提交**

```bash
git add app/src/main/java/tech/illusion/spacehop/content/ChargeInput.kt \
        app/src/test/java/tech/illusion/spacehop/content/ChargeHandTest.kt \
        app/src/main/java/tech/illusion/spacehop/content/PinchChargeController.kt \
        app/src/main/java/tech/illusion/spacehop/content/HomeStage.kt
git commit -m "refactor: put charge input behind an interface, drop the singleton

The window container gets a different input implementation entirely --
every tracking provider is @RequiredFullSpace and delivers nothing
there -- so the two containers need to swap input sources. A global
mutable object would let one container's stop() tear down the callbacks
the other just installed.

ChargeHand is ours rather than the SDK's HandController so the interface
and its tests stay off the Android side.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: PointerChargeInput —— 窗口态的按压蓄力

**Files:**
- Create: `app/src/main/java/tech/illusion/spacehop/content/PointerChargeInput.kt`
- Test: `app/src/test/java/tech/illusion/spacehop/content/PointerChargeInputTest.kt`

**Interfaces:**
- Consumes: `ChargeInput`、`ChargeHand`、`ChargeUiState`（Task 3）；`ChargeCurve`（已存在）
- Produces:
  - `class PointerChargeInput(private val nowNanos: () -> Long = System::nanoTime) : ChargeInput`
  - `fun PointerChargeInput.onPressDown()`
  - `fun PointerChargeInput.onPressUp()`
  - `fun PointerChargeInput.tickChargeFraction()`（供 UI 每帧刷新蓄力条）

**设计要点**：把「时间」做成构造参数注入，这个类才能在 JVM 上被精确单测——否则只能靠 `Thread.sleep` 碰运气。Compose 手势层只负责在 `onPress` / `tryAwaitRelease` 时调 `onPressDown()` / `onPressUp()`。

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/tech/illusion/spacehop/content/PointerChargeInputTest.kt`：

```kotlin
package tech.illusion.spacehop.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.illusion.spacehop.game.ChargeCurve

class PointerChargeInputTest {

    private class FakeClock(var nanos: Long = 0L) : () -> Long {
        override fun invoke(): Long = nanos
        fun advanceMs(ms: Long) { nanos += ms * 1_000_000L }
    }

    @Test
    fun heldDurationBecomesCappedChargeMillis() {
        val clock = FakeClock()
        val input = PointerChargeInput(nowNanos = clock)
        var released: Long? = null
        input.start(onChargeStart = {}, onRelease = { ms, _ -> released = ms })

        input.onPressDown()
        clock.advanceMs(500L)
        input.onPressUp()

        assertEquals(500L, released)
    }

    /** 按太久必须封顶，和捏合输入用的是同一个 ChargeCurve 上限。 */
    @Test
    fun overlongHoldIsCappedAtMaxChargeMillis() {
        val clock = FakeClock()
        val input = PointerChargeInput(nowNanos = clock)
        var released: Long? = null
        input.start(onChargeStart = {}, onRelease = { ms, _ -> released = ms })

        input.onPressDown()
        clock.advanceMs(ChargeCurve.MAX_CHARGE_MILLIS + 5_000L)
        input.onPressUp()

        assertEquals(ChargeCurve.MAX_CHARGE_MILLIS, released)
    }

    /** 窗口态的 pointer 事件给不出左右手，必须如实报 null 而不是瞎猜一只。 */
    @Test
    fun handIsAlwaysNullBecausePointerEventsCannotTellLeftFromRight() {
        val input = PointerChargeInput(nowNanos = FakeClock())
        var startHand: ChargeHand? = ChargeHand.LEFT
        var releaseHand: ChargeHand? = ChargeHand.LEFT
        input.start(
            onChargeStart = { startHand = it },
            onRelease = { _, hand -> releaseHand = hand },
        )

        input.onPressDown()
        input.onPressUp()

        assertEquals(null, startHand)
        assertEquals(null, releaseHand)
    }

    /** 没按下就松手不能产生一次跳跃——射线扫过场景不该让青蛙起跳。 */
    @Test
    fun releaseWithoutPressIsIgnored() {
        val input = PointerChargeInput(nowNanos = FakeClock())
        var releaseCount = 0
        input.start(onChargeStart = {}, onRelease = { _, _ -> releaseCount++ })

        input.onPressUp()

        assertEquals(0, releaseCount)
    }

    /** 蓄力条要有东西可画：按住期间 chargeState 必须反映进度并在 1.0 封顶。 */
    @Test
    fun chargeFractionRampsToOneAndStops() {
        val clock = FakeClock()
        val input = PointerChargeInput(nowNanos = clock)
        input.start(onChargeStart = {}, onRelease = { _, _ -> })

        assertFalse(input.chargeState.value.isCharging)

        input.onPressDown()
        clock.advanceMs(ChargeCurve.MAX_CHARGE_MILLIS / 2)
        input.tickChargeFraction()
        assertTrue(input.chargeState.value.isCharging)
        assertEquals(0.5f, input.chargeState.value.chargeFraction, 0.02f)

        clock.advanceMs(ChargeCurve.MAX_CHARGE_MILLIS * 3)
        input.tickChargeFraction()
        assertEquals(1f, input.chargeState.value.chargeFraction, 0.001f)

        input.onPressUp()
        assertFalse(input.chargeState.value.isCharging)
        assertEquals(0f, input.chargeState.value.chargeFraction, 0.001f)
    }

    /** stop() 之后不该再有回调——容器切走时输入源必须真的哑掉。 */
    @Test
    fun stopSilencesCallbacks() {
        val clock = FakeClock()
        val input = PointerChargeInput(nowNanos = clock)
        var releaseCount = 0
        input.start(onChargeStart = {}, onRelease = { _, _ -> releaseCount++ })

        input.stop()
        input.onPressDown()
        clock.advanceMs(300L)
        input.onPressUp()

        assertEquals(0, releaseCount)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceHop
./gradlew :app:testDebugUnitTest --tests "tech.illusion.spacehop.content.PointerChargeInputTest"
```

预期：编译失败，`Unresolved reference: PointerChargeInput`。

- [ ] **Step 3: 写实现**

创建 `app/src/main/java/tech/illusion/spacehop/content/PointerChargeInput.kt`：

```kotlin
package tech.illusion.spacehop.content

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tech.illusion.spacehop.game.ChargeCurve

/**
 * 窗口态（WindowContainer）的蓄力输入：按住 3D 视图蓄力，松手起跳。
 *
 * 存在的理由：`HandTrackingProvider` / `ControllerTrackingProvider` / `HMDTrackingProvider` 全部
 * 带 `@RequiredFullSpace`，在 WindowContainer 下 `start()` 只返回 `PENDING` 且 `dataFlow` 永不
 * 发射。窗口里拿不到任何追踪数据，所以蓄力必须改由 Compose 的指针事件驱动。
 *
 * 本类不接触 Compose 也不接触 SDK——手势层只在 `onPress` / `tryAwaitRelease` 时调
 * [onPressDown] / [onPressUp]。[nowNanos] 注入时间源，是为了让按住时长的计算能在 JVM 上被精确
 * 单测，而不是靠 `Thread.sleep` 碰运气。
 *
 * 蓄力封顶用的是和捏合输入完全相同的 [ChargeCurve.MAX_CHARGE_MILLIS]，两个容器的手感因此一致、
 * 分数可比——这是「同一局跨容器」能够成立的前提。
 */
class PointerChargeInput(
    private val nowNanos: () -> Long = System::nanoTime,
) : ChargeInput {

    private val _chargeState = MutableStateFlow(ChargeUiState())
    override val chargeState: StateFlow<ChargeUiState> = _chargeState

    private var onChargeStartCallback: ((ChargeHand?) -> Unit)? = null
    private var onReleaseCallback: ((Long, ChargeHand?) -> Unit)? = null

    /** 按下时刻；null 表示当前没有按住。 */
    private var pressStartNanos: Long? = null

    override fun start(
        onChargeStart: (ChargeHand?) -> Unit,
        onRelease: (cappedHeldMillis: Long, hand: ChargeHand?) -> Unit,
    ) {
        onChargeStartCallback = onChargeStart
        onReleaseCallback = onRelease
    }

    override fun stop() {
        onChargeStartCallback = null
        onReleaseCallback = null
        pressStartNanos = null
        _chargeState.value = ChargeUiState()
    }

    /** Compose 手势层的 `onPress` 调这里。 */
    fun onPressDown() {
        if (onReleaseCallback == null) return // 已 stop，输入源应当是哑的
        pressStartNanos = nowNanos()
        _chargeState.value = ChargeUiState(isCharging = true, chargeFraction = 0f)
        // 窗口态的 pointer 事件给不出左右手：SpatialPointerInfo 有 kind 和 inputDevicePose，
        // 但没有左右手信息，而 HandController 只有 Left/Right/Unknown。如实报 null。
        onChargeStartCallback?.invoke(null)
    }

    /** Compose 手势层的 `tryAwaitRelease()` 返回后调这里。 */
    fun onPressUp() {
        val startNanos = pressStartNanos ?: return // 没按下过就松手（射线扫过场景）——忽略
        pressStartNanos = null
        val heldMillis = (nowNanos() - startNanos) / 1_000_000L
        _chargeState.value = ChargeUiState()
        onReleaseCallback?.invoke(ChargeCurve.cappedChargeMillis(heldMillis), null)
    }

    /** 每帧调一次，把按住进度推进到 [ChargeUiState.chargeFraction] 上供蓄力条读取。
     * 没按住时是空操作。 */
    fun tickChargeFraction() {
        val startNanos = pressStartNanos ?: return
        val heldMillis = (nowNanos() - startNanos) / 1_000_000L
        val fraction = (heldMillis.toFloat() / ChargeCurve.MAX_CHARGE_MILLIS).coerceIn(0f, 1f)
        _chargeState.value = ChargeUiState(isCharging = true, chargeFraction = fraction)
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceHop
./gradlew :app:testDebugUnitTest --tests "tech.illusion.spacehop.content.PointerChargeInputTest"
grep -o 'tests="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-tech.illusion.spacehop.content.PointerChargeInputTest.xml
```

预期：`tests="6" failures="0" errors="0"`。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/tech/illusion/spacehop/content/PointerChargeInput.kt \
        app/src/test/java/tech/illusion/spacehop/content/PointerChargeInputTest.kt
git commit -m "feat: add press-and-hold charge input for the window container

Holds the same ChargeCurve cap as the pinch path, so a run scored in the
window is comparable to one scored in the Stage -- which is what makes
carrying a single run across the two containers meaningful.

Reports a null hand rather than guessing one: SpatialPointerInfo exposes
the interaction kind and the device pose but nothing that distinguishes
left from right.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: ContainerSwitch —— 双向切换与失败回滚

**Files:**
- Create: `app/src/main/java/tech/illusion/spacehop/content/ContainerSwitch.kt`
- Test: `app/src/test/java/tech/illusion/spacehop/content/ContainerSwitchTest.kt`

**Interfaces:**
- Consumes: `HopSession`（Task 2）
- Produces:
  - `const val IMMERSIVE_STAGE_ID = "SpaceHopImmersive"`
  - `const val MAIN_WINDOW_ID = "SpaceHopVolume"`（必须与 manifest 的 `pico.spatial.windowcontainer.id` 一致）
  - `enum class SwitchOutcome { SWITCHED, REFUSED_BUSY, BLOCKED_BY_SYSTEM }`
  - `suspend fun enterImmersive(openStage: suspend () -> Boolean, closeWindow: () -> Unit): SwitchOutcome`
  - `suspend fun exitImmersive(openWindow: () -> Unit, closeStage: suspend () -> Unit): SwitchOutcome`

**为什么把 SDK 调用做成函数参数**：`LocalSpatialNavigator` 只能在组合里拿到，直接调用会让切换的决策逻辑（先开后关、失败不关窗、忙时拒绝）完全无法在 JVM 上测。生产代码传 navigator 的方法引用，测试传假实现。SDK 调用本身不测。

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/tech/illusion/spacehop/content/ContainerSwitchTest.kt`：

```kotlin
package tech.illusion.spacehop.content

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.illusion.spacehop.game.HopPhase
import tech.illusion.spacehop.game.HopSession
import tech.illusion.spacehop.game.PlatformSpawner
import kotlin.random.Random

class ContainerSwitchTest {

    @Before
    fun reset() {
        HopSession.startNewRun(PlatformSpawner(Random(3)))
        HopSession.phase = HopPhase.Playing
    }

    /** 先开后关：任何时刻都必须至少有一个活容器，否则应用会直接消失。 */
    @Test
    fun enterImmersiveOpensStageBeforeClosingWindow() = runBlocking {
        val order = mutableListOf<String>()
        val outcome = enterImmersive(
            openStage = { order += "open"; true },
            closeWindow = { order += "close" },
        )
        assertEquals(SwitchOutcome.SWITCHED, outcome)
        assertEquals(listOf("open", "close"), order)
    }

    /** openStage 被系统拒绝（别的应用占着 Full Space）时，绝不能关掉窗口——
     * 关了就两个容器都没有了。 */
    @Test
    fun blockedOpenStageLeavesTheWindowAlone() = runBlocking {
        var windowClosed = false
        val outcome = enterImmersive(
            openStage = { false },
            closeWindow = { windowClosed = true },
        )
        assertEquals(SwitchOutcome.BLOCKED_BY_SYSTEM, outcome)
        assertFalse("被拒时窗口必须留着", windowClosed)
    }

    /** 被拒之后局面必须完好：解冻、切换标志清掉、分数一分不少。 */
    @Test
    fun blockedOpenStageRollsBackTheSession() = runBlocking {
        val runBefore = HopSession.runState
        enterImmersive(openStage = { false }, closeWindow = { })

        assertFalse("被拒后必须解冻，否则玩家卡在暂停里", HopSession.paused)
        assertFalse(HopSession.switching)
        assertEquals(runBefore, HopSession.runState)
        assertEquals(HopPhase.Playing, HopSession.phase)
    }

    /** 飞行中拒绝切换——飞行动画状态不在 session 里，带不过去。 */
    @Test
    fun switchIsRefusedWhileFlying() = runBlocking {
        HopSession.isFlying = true
        var opened = false
        val outcome = enterImmersive(openStage = { opened = true; true }, closeWindow = { })

        assertEquals(SwitchOutcome.REFUSED_BUSY, outcome)
        assertFalse("飞行中不该碰容器", opened)
    }

    /** 重复点击不能开出两个 Stage。 */
    @Test
    fun secondSwitchWhileSwitchingIsRefused() = runBlocking {
        HopSession.onContainerSwitchStart()
        var opened = false
        val outcome = enterImmersive(openStage = { opened = true; true }, closeWindow = { })

        assertEquals(SwitchOutcome.REFUSED_BUSY, outcome)
        assertFalse(opened)
    }

    /** 反方向同样先开后关。 */
    @Test
    fun exitImmersiveOpensWindowBeforeClosingStage() = runBlocking {
        val order = mutableListOf<String>()
        val outcome = exitImmersive(
            openWindow = { order += "open" },
            closeStage = { order += "close" },
        )
        assertEquals(SwitchOutcome.SWITCHED, outcome)
        assertEquals(listOf("open", "close"), order)
    }

    /** 切换开始时必须断掉 fast streak：这个窗口判的是 10 秒内连落 10 块，
     * 而切换本身就要花掉数秒真实时间。 */
    @Test
    fun switchClearsFastStreakTimestamps() = runBlocking {
        HopSession.recentLandingTimestampsMs.addLast(1L)
        HopSession.recentLandingTimestampsMs.addLast(2L)

        enterImmersive(openStage = { true }, closeWindow = { })

        assertTrue(HopSession.recentLandingTimestampsMs.isEmpty())
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceHop
./gradlew :app:testDebugUnitTest --tests "tech.illusion.spacehop.content.ContainerSwitchTest"
```

预期：编译失败，`Unresolved reference: enterImmersive`。

- [ ] **Step 3: 写实现**

创建 `app/src/main/java/tech/illusion/spacehop/content/ContainerSwitch.kt`：

```kotlin
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
```

- [ ] **Step 4: 跑测试确认通过**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceHop
./gradlew :app:testDebugUnitTest --tests "tech.illusion.spacehop.content.ContainerSwitchTest"
grep -o 'tests="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-tech.illusion.spacehop.content.ContainerSwitchTest.xml
```

预期：`tests="7" failures="0" errors="0"`。

注意：`enterImmersive` 成功路径后 `HopSession.switching` 仍为 true，由新容器建完场景后调
`HopSession.onContainerSwitchSettled()` 解除（Task 7 / Task 8）。测试里不要断言它在这里被清掉。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/tech/illusion/spacehop/content/ContainerSwitch.kt \
        app/src/test/java/tech/illusion/spacehop/content/ContainerSwitchTest.kt
git commit -m "feat: add bidirectional container switching with rollback

openStage can come back NotAllowed when another app holds Full Space.
Closing the window before checking that would leave the app with no live
container at all, so the order is open-then-close and a refusal rolls the
session back instead.

The navigator calls are function parameters so the decision logic --
ordering, refusal while flying, rollback -- is testable on the JVM.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: HopRun —— 把玩法编排抽出来给两个容器共用

**Files:**
- Create: `app/src/main/java/tech/illusion/spacehop/content/HopRun.kt`
- Modify: `app/src/main/java/tech/illusion/spacehop/content/HomeStage.kt`（改为消费 `rememberHopRun`）
- Test: 无新增单测（本任务是纯搬运重构，行为不变；由现有 13 个测试文件 + 全量编译把关）

**Interfaces:**
- Consumes: `HopSession`、`HopPhase`、`freshBoardState`（Task 2）；`ChargeInput`、`ChargeHand`（Task 3）；`HopClock`（Task 1）
- Produces:
  - `class HopRun(context: Context, clock: HopClock)` —— 持有 `spawner` / `boardAnimator` / `sfx` / `highScoreStore` / `characterController`。公开成员（后续任务全部依赖它们，签名不可改名）：
    - `suspend fun buildScene(root: Entity)` —— 建板面动画器 + 加载青蛙，两个容器壳的 `SpatialView.initial` 共用
    - `fun startNewRun()`
    - `suspend fun performJump(flightDistance: Float, trigger: String)`
    - `fun endRun(diedInLotusPond: Boolean)`
    - `fun bestScore(): Int?` —— 转发 `highScoreStore.bestScore()`，供两个壳的起始页/结算页读取
    - `var runStartedAtNanos: Long` —— 开局宽限期的基准，Task 9 会读它
    - `val jumpGrace: JumpGrace` —— Task 9 创建，本任务先不存在；Task 9 再加字段
  - `@Composable fun rememberHopRun(chargeInput: ChargeInput, clock: HopClock, onHaptic: (HandControllerHapticType, ChargeHand?) -> Unit): HopRun`
  - 从 `HomeStage.kt` 迁来的常量一律用 `internal const val`（不是 `private`）—— `JUMP_INPUT_GRACE_MS` 会被 Task 9 的单测直接引用，`private` 会让那个测试编译不过。同文件里已有的 `ADVANCE_PHASE_MS` 就是 `internal`，跟着它走。

**本任务是纯搬运。** 从 `HomeStage.kt` 迁出的内容必须逐字保留其逻辑与注释，只改三件事：
(a) 局部 `var` 改为读写 `HopSession.*`；(b) `PinchChargeController` 改为参数传入的 `chargeInput`；
(c) 手柄震动改为通过 `onHaptic` 回调上抛给容器壳（`HandControllerHapticType` 是 SDK 类型，但
`HopRun` 本来就在 `content` 包里且已依赖 SDK，直接用即可；上抛是为了让窗口壳能传一个空实现）。

- [ ] **Step 1: 建立 HopRun 骨架并迁入常量**

创建 `app/src/main/java/tech/illusion/spacehop/content/HopRun.kt`，把 `HomeStage.kt` 中以下
`private const val` **整段连注释**搬过来（搬走后从 `HomeStage.kt` 删除），并把可见性从
`private` 改为 `internal`：

`JUMP_INPUT_GRACE_MS`、`ROUND_TIME_LIMIT_MS`、`AUTO_FAIL_LAUNCH_DISTANCE_MULTIPLIER`、
`FAST_STREAK_JUMPS_REQUIRED`、`FAST_STREAK_WINDOW_MS`、`ROUND_TIMEOUT_POLL_MS`、`CHARACTER_GROUND_Y`。

改 `internal` 的理由：`JUMP_INPUT_GRACE_MS` 会被 Task 9 的 `JumpGraceTest` 直接引用，`private`
会让那个测试编译不过。同文件将要引入的 `ADVANCE_PHASE_MS` 在 `StageBoardAnimator.kt` 里本来就是
`internal`，可见性跟它保持一致。

`PRIMARY_PANEL` / `SIDE_PANEL` / `SIDE_OFFSET_M` / `PRIMARY_PANEL_USER_DISTANCE_M` /
`PRIMARY_PANEL_TILT_PITCH_DEGREES` **留在** `HomeStage.kt`（它们是沉浸壳的摆位常量，Task 7 会
随文件改名一起带走）。

- [ ] **Step 2: 迁入 HopRun 类本体**

在同一文件里写 `class HopRun`，把 `HomeStage()` 里的 `startNewRun` / `endRun` / `performJump`
三个局部函数原样搬进来作为成员函数，并做前述三项替换。例如 `startNewRun`：

```kotlin
    fun startNewRun() {
        HopSession.startNewRun(spawner)
        boardAnimator.placeInstant(HopSession.boardState) // 冷重置，不是落地——不做滑入/淡入
        characterController?.apply {
            setIdlePosition(Vector3(0f, CHARACTER_GROUND_Y, 0f))
            // 快照，不是转向：冷启动没有落地过渡可以把转向折叠进去。
            val next = HopSession.boardState.platforms[1]
            faceTarget(next.lateralOffsetMeters, next.distanceFromPreviousMeters)
        }
        runStartedAtNanos = System.nanoTime()
    }
```

同时新增两个成员，供两个容器壳共用（Task 8 依赖它们）：

```kotlin
    /** 建立板面动画器与青蛙。两个容器壳的 `SpatialView.initial` 都调这个，所以场景构建只有
     * 一份。指尖球（`IndicatorBallsRenderer`）**不**在这里——它是沉浸壳独有的，窗口态没有手部
     * 数据可跟随。 */
    suspend fun buildScene(root: Entity) {
        boardAnimator.build(root)
        boardAnimator.placeInstant(HopSession.boardState)
        characterController = CharacterController.load(
            root,
            characterRadiusMeters = 0.05f,
            clock = clock,
        ).also {
            it.setIdlePosition(Vector3(0f, CHARACTER_GROUND_Y, 0f))
            val next = HopSession.boardState.platforms[1]
            it.faceTarget(next.lateralOffsetMeters, next.distanceFromPreviousMeters)
        }
    }

    fun bestScore(): Int? = highScoreStore.bestScore()
```

`ImmersiveHome.kt` 的 `SpatialView.initial` 里原本那三段（`boardAnimator.build` /
`placeInstant` / `CharacterController.load`）随之替换为一句 `run.buildScene(anchor)`，
`indicatorBalls.build(content)` 留在原地。

`performJump` 里：

- `boardState` → `HopSession.boardState`，`runState` → `HopSession.runState`
- `recentLandingTimestampsMs` → `HopSession.recentLandingTimestampsMs`
- `jumpCommittedThisRound` → `HopSession.jumpCommittedThisRound`
- 函数最开头加 `HopSession.isFlying = true`，`try { ... } finally { HopSession.isFlying = false }`
  包住整个函数体——飞行中禁止切换容器靠的就是这个标志，而 MISS 分支里有 `return`，用 `finally`
  才能保证任何出口都会清掉它。

- [ ] **Step 3: 迁入三个 LaunchedEffect / DisposableEffect 到 rememberHopRun**

在同一文件里写：

```kotlin
/**
 * 两个容器共用的玩法编排。
 *
 * 容器壳负责的是它们各自不同的部分——输入源、帧钟、根实体摆位与缩放、面板布局；本函数负责
 * 两边完全相同的部分——回合超时看门狗、强制失手、fast-streak 判定、落点判定与推进。规则只有
 * 一份，所以两个容器的玩法不可能漂移。
 *
 * @param onHaptic 手柄震动。沉浸壳传真实的 `LocalHandControllerHaptic`；窗口壳传空实现——
 *   窗口态的 pointer 事件给不出左右手，没有可以精准震动的目标（见 [PointerChargeInput]）。
 */
@Composable
fun rememberHopRun(
    chargeInput: ChargeInput,
    clock: HopClock,
    onHaptic: (HandControllerHapticType, ChargeHand?) -> Unit,
): HopRun {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val run = remember(clock) { HopRun(context, clock) }
    // ... 以下三块由 HomeStage.kt 原样搬入 ...
}
```

要搬入的三块（全部保留原注释）：

1. **生命周期暂停/恢复音效**的 `DisposableEffect(lifecycleOwner, sfx)`。
2. **输入订阅**的 `DisposableEffect(Unit) { chargeInput.start(...); onDispose { chargeInput.stop(); sfx.release() } }`——
   其中 `onChargeStart` 改为 `onHaptic(HandControllerHapticType.Hover, hand)`，
   `onRelease` 里的震动改为 `onHaptic(HandControllerHapticType.Press, hand)`。
3. **蓄力视觉 tick** 的 `LaunchedEffect(Unit) { while (true) { delay(16) ... } }`——
   其中 `indicatorBalls.update(state)` 移出到沉浸壳（窗口态没有指尖球），本函数只保留
   `characterController?.applyChargeSquash/resetSquash` 和 `sfx.startCharge/stopCharge`。
   循环开头加一行 `(chargeInput as? PointerChargeInput)?.tickChargeFraction()`，让窗口的蓄力条有
   东西可读；捏合输入自己会更新 `chargeState`，这行对它是空操作。
4. **回合超时看门狗**的 `LaunchedEffect(boardState, phase)` → `LaunchedEffect(HopSession.boardState, HopSession.phase)`，
   函数体内的局部状态引用同样全部改为 `HopSession.*`。

- [ ] **Step 4: 让 HomeStage 改为消费 rememberHopRun**

修改 `HomeStage.kt`：删掉已迁出的常量、局部状态 `var`、三个局部函数和四个 effect，改为：

```kotlin
    val chargeInput = remember { PinchChargeController() }
    val controllerHaptic = LocalHandControllerHaptic.current
    val run = rememberHopRun(
        chargeInput = chargeInput,
        clock = HopClocks.frameAligned,
        onHaptic = { type, hand ->
            controllerHaptic.feedback(
                type,
                when (hand) {
                    ChargeHand.LEFT -> HandController.Left
                    ChargeHand.RIGHT -> HandController.Right
                    null -> HandController.Unknown
                },
            )
        },
    )
```

并把余下所有 `phase` / `runState` / `boardState` / `paused` / `showExitConfirm` 的读取改为
`HopSession.*`（`showExitConfirm` 留作 `HomeStage` 的本地 `remember` 状态——它是沉浸壳独有的
退出确认框，不跨容器）。指尖球的 `indicatorBalls.update(...)` tick 循环留在 `HomeStage.kt`。

- [ ] **Step 5: 全量编译 + 全量单测**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceHop
./gradlew :app:assembleDebug :app:testDebugUnitTest
grep -L 'failures="0" errors="0"' app/build/test-results/testDebugUnitTest/*.xml
wc -l app/src/main/java/tech/illusion/spacehop/content/HomeStage.kt \
      app/src/main/java/tech/illusion/spacehop/content/HopRun.kt
```

预期：BUILD SUCCESSFUL；`grep -L` 无输出；`HomeStage.kt` 显著缩短（目标 250 行以内）。

- [ ] **Step 6: 手工核对 import**

`HomeStage.kt` 删掉了大量代码，会留下一批未使用的 import，而 Kotlin 不会报警。逐行核对
`HomeStage.kt` 和 `HopRun.kt` 的 import 块。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/tech/illusion/spacehop/content/HopRun.kt \
        app/src/main/java/tech/illusion/spacehop/content/HomeStage.kt
git commit -m "refactor: extract the run orchestration so both containers share one copy

The round timeout, the forced-miss catch, the fast-streak detection and
the landing judgement are subtle enough that a second copy for the
window container would drift from this one -- not might, would. Moving
them out of the Stage composable is what keeps the two containers
playing the same game.

Pure move: the logic and its comments are unchanged, local vars now read
HopSession, and the input source and frame clock arrive as parameters.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Manifest 换主 + 双容器声明 + ImmersiveHome

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/tech/illusion/spacehop/Main.kt`
- Rename + Modify: `content/HomeStage.kt` → `content/ImmersiveHome.kt`（`HomeStage()` → `ImmersiveHome()`）
- Create: `app/src/main/java/tech/illusion/spacehop/content/WindowHome.kt`（本任务只放一个占位实现，Task 8 填充）
- Test: 无新增单测（配置改动；由编译 + Task 10 的设备验证把关）

**Interfaces:**
- Consumes: `IMMERSIVE_STAGE_ID`、`MAIN_WINDOW_ID`（Task 5）；`rememberHopRun`（Task 6）
- Produces: `@Composable fun ImmersiveHome()`、`@Composable fun WindowHome()`

- [ ] **Step 1: 改 manifest**

修改 `app/src/main/AndroidManifest.xml`，把 `<activity>` 内四条 `pico.spatial.stage.*` meta-data
（`stage.id`、`stage.style`、`stage.immersion`、`stage.immersion_min`、`stage.immersion_max`）
**整段连注释删除**，替换为：

```xml
            <!-- Volumetric DefaultWindowContainer 配置。应用启动落在这个窗口里；沉浸态的
                 Stage 不在 manifest 声明，改由 Main.kt 的 DSL 按 id 声明并在玩家点「进入沉浸」
                 时才打开。 -->

            <!-- 窗口 id。必须与 content/ContainerSwitch.kt 的 MAIN_WINDOW_ID 一致，否则从
                 沉浸态返回时 openWindowContainer 找不到它。 -->
            <meta-data
                android:name="pico.spatial.windowcontainer.id"
                android:value="SpaceHopVolume" />

            <!-- "2": Form.Volumetric —— 体积窗口，允许自定义深度。沙盘需要深度，平面窗口装不下。 -->
            <meta-data
                android:name="pico.spatial.windowcontainer.style"
                android:value="2" />

            <!-- 桌面摆件尺度。玩法几何固定（最远跳 0.55m、平台半径 0.09m、横向 ±0.08m，
                 合计约 0.73m 深 × 0.34m 宽），所以窗口里的场景必然是缩小的沙盘——
                 见 content/WindowHome.kt 的 SANDBOX_SCALE。 -->
            <meta-data
                android:name="pico.spatial.windowcontainer.defaultsize"
                android:value="0.5x0.4x0.45" />

            <meta-data
                android:name="pico.spatial.windowcontainer.defaultsize.unit"
                android:value="meters" />

            <!-- "2": WorldScale.Fixed —— 沙盘必须遵守真实透视，走近变大才对。
                 Dynamic 的「距离再远看上去也一样大」会毁掉摆件感。 -->
            <meta-data
                android:name="pico.spatial.windowcontainer.worldscaletype"
                android:value="2" />

            <!-- "1": UniformResizable —— 玩法几何锁死纵横比，非等比拉伸会把沙盘拉变形。 -->
            <meta-data
                android:name="pico.spatial.windowcontainer.resizerestriction"
                android:value="1" />

            <!-- "0": 底板可见 —— 它就是沙盘的桌面，青蛙站在上面。 -->
            <meta-data
                android:name="pico.spatial.windowcontainer.volumebasepanel"
                android:value="0" />

            <!-- "0": 关闭毛玻璃 —— 沙盘要看穿到真实房间。主窗口的毛玻璃只能在 manifest 关，
                 DSL 的 enableMaterialBackground 参数只作用于子窗口。 -->
            <meta-data
                android:name="pico.spatial.windowcontainer.materialbackground"
                android:value="0" />
```

保留不动：`pico.spatial.ui.Logger`、`icon.3d.list`、`icon.sdf.list`、`android:localeConfig`、
`<uses-permission android:name="com.picovr.permission.HAND_TRACKING" />`（沉浸态仍需要）。

- [ ] **Step 2: 改 Main.kt**

```kotlin
package tech.illusion.spacehop

import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.Stage
import tech.illusion.spacehop.content.IMMERSIVE_STAGE_ID
import tech.illusion.spacehop.content.ImmersiveHome
import tech.illusion.spacehop.content.WindowHome

/**
 * 两个容器都在启动时注册。
 *
 * 体积窗口是入口（manifest 里配置的就是它）；Stage 在这里按 id 声明，但直到玩家点「进入沉浸」
 * 才真正打开。只有*默认*容器在 manifest 配置，次级 Stage 必须在 DSL 里声明并按 id 寻址。
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer { PicoTheme { WindowHome() } }
        Stage(id = IMMERSIVE_STAGE_ID) { PicoTheme { ImmersiveHome() } }
    }
```

- [ ] **Step 3: 改名 HomeStage → ImmersiveHome**

```bash
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceHop
git mv app/src/main/java/tech/illusion/spacehop/content/HomeStage.kt \
       app/src/main/java/tech/illusion/spacehop/content/ImmersiveHome.kt
```

把文件里的 `fun HomeStage()` 改为 `fun ImmersiveHome()`，并更新它的 KDoc 首句为：
「沉浸态（Stage）的壳：捏合/扳机输入、HMD 头高校准、`StageCalibration` 摆位、三块 AttachmentPanel。玩法编排在 [rememberHopRun]。」

- [ ] **Step 4: 沉浸壳建完场景后解冻会话**

在 `ImmersiveHome.kt` 的 `SpatialView(initial = { content, attachments -> ... })` **末尾**加一行：

```kotlin
            // 从窗口切过来时会话是冻结的（ContainerSwitch.enterImmersive 设的），场景建好了才解冻。
            // 冷启动时它本来就是 false，这行是幂等的。
            HopSession.onContainerSwitchSettled()
```

- [ ] **Step 5: 写 WindowHome 占位**

创建 `app/src/main/java/tech/illusion/spacehop/content/WindowHome.kt`：

```kotlin
package tech.illusion.spacehop.content

import androidx.compose.runtime.Composable
import com.pico.spatial.ui.design.Text

/** 窗口态（WindowContainer）的壳。Task 8 填充。 */
@Composable
fun WindowHome() {
    Text(text = "SpaceHop")
}
```

- [ ] **Step 6: 编译 + 全量单测**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceHop
./gradlew :app:assembleDebug :app:testDebugUnitTest
grep -L 'failures="0" errors="0"' app/build/test-results/testDebugUnitTest/*.xml
```

预期：BUILD SUCCESSFUL，`grep -L` 无输出。

- [ ] **Step 7: 确认 manifest 里真的没有 stage 配置残留**

```bash
grep -c "pico.spatial.stage" app/src/main/AndroidManifest.xml
grep -c "pico.spatial.windowcontainer" app/src/main/AndroidManifest.xml
```

预期：第一个输出 `0`，第二个输出 `8`。

- [ ] **Step 8: 提交**

```bash
git add app/src/main/AndroidManifest.xml \
        app/src/main/java/tech/illusion/spacehop/Main.kt \
        app/src/main/java/tech/illusion/spacehop/content/ImmersiveHome.kt \
        app/src/main/java/tech/illusion/spacehop/content/WindowHome.kt
git commit -m "feat: launch into a volumetric window, declare the Stage in the DSL

The app now opens as a 0.5x0.4x0.45m object in the room rather than
taking over the whole space, and the Stage is opened on request instead
of at launch.

The Stage keeps StageStyle.Mixed. SpaceFireworks uses Full and its
comment credits that for hand tracking working, but that conflates
StageStyle.Full with the FullSpace state -- a Mixed Stage is FullSpace
too, which the shipped SpaceHop proves. Full would switch passthrough
off and move the frog off the user's table into a black box.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: WindowHome —— 窗口里的沙盘与工具条

**Files:**
- Modify: `app/src/main/java/tech/illusion/spacehop/content/WindowHome.kt`
- Create: `app/src/main/java/tech/illusion/spacehop/content/WindowScale.kt`
- Test: `app/src/test/java/tech/illusion/spacehop/content/WindowScaleTest.kt`

**Interfaces:**
- Consumes: `rememberHopRun`（Task 6）、`PointerChargeInput`（Task 4）、`HopClocks.delayDriven`（Task 1）、`enterImmersive` / `MAIN_WINDOW_ID` / `IMMERSIVE_STAGE_ID`（Task 5）、`HopSession`（Task 2）
- Produces:
  - `object WindowScale { const val SCENE_DEPTH_M; const val SCENE_WIDTH_M; fun fitScale(halfWidthM, halfHeightM, halfDepthM): Float }`
  - `@Composable fun WindowHome()`

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/tech/illusion/spacehop/content/WindowScaleTest.kt`：

```kotlin
package tech.illusion.spacehop.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.illusion.spacehop.game.DifficultyCurve
import tech.illusion.spacehop.game.PlatformSpawner

class WindowScaleTest {

    /** 场景占用的尺寸必须从玩法常量推导，不能是手填的数字——难度曲线一调，
     * 沙盘就会悄悄超出窗口被裁掉。 */
    @Test
    fun sceneFootprintIsDerivedFromGameplayConstants() {
        val expectedDepth = DifficultyCurve.MAX_DISTANCE_CEILING_M + 2f * 0.09f
        val expectedWidth = 2f * (PlatformSpawner.MAX_LATERAL_OFFSET_M + 0.09f)
        assertEquals(expectedDepth, WindowScale.SCENE_DEPTH_M, 0.0001f)
        assertEquals(expectedWidth, WindowScale.SCENE_WIDTH_M, 0.0001f)
    }

    /** 默认窗口（0.5 × 0.4 × 0.45m）下缩放应落在 0.55 附近——这是用户定的沙盘观感。 */
    @Test
    fun defaultWindowYieldsTheAgreedSandboxScale() {
        val scale = WindowScale.fitScale(
            halfWidthM = 0.5f / 2f,
            halfHeightM = 0.4f / 2f,
            halfDepthM = 0.45f / 2f,
        )
        assertEquals(0.55f, scale, 0.06f)
    }

    /** 缩放后的场景必须真的装得进盒子，否则最远的平台会被 SpatialView 的包围盒裁掉。 */
    @Test
    fun scaledSceneFitsInsideTheViewBox() {
        val halfDepth = 0.45f / 2f
        val halfWidth = 0.5f / 2f
        val scale = WindowScale.fitScale(halfWidth, 0.4f / 2f, halfDepth)
        assertTrue(
            "缩放后深度 ${WindowScale.SCENE_DEPTH_M * scale} 必须 <= 盒深 ${halfDepth * 2f}",
            WindowScale.SCENE_DEPTH_M * scale <= halfDepth * 2f,
        )
        assertTrue(
            "缩放后宽度 ${WindowScale.SCENE_WIDTH_M * scale} 必须 <= 盒宽 ${halfWidth * 2f}",
            WindowScale.SCENE_WIDTH_M * scale <= halfWidth * 2f,
        )
    }

    /** 窗口被用户缩小时缩放必须跟着变小，而不是让沙盘穿帮出界。 */
    @Test
    fun shrinkingTheWindowShrinksTheSandbox() {
        val big = WindowScale.fitScale(0.25f, 0.20f, 0.225f)
        val small = WindowScale.fitScale(0.125f, 0.10f, 0.1125f)
        assertTrue("窗口减半，缩放必须跟着减小：big=$big small=$small", small < big)
    }

    /** 还没测量到尺寸时（首帧 halfExtent 为 0）不能返回 0 或负数缩放——
     * 缩放 0 会让整个沙盘不可见，看上去像模型加载失败。 */
    @Test
    fun unmeasuredViewFallsBackToAPositiveScale() {
        val scale = WindowScale.fitScale(0f, 0f, 0f)
        assertTrue("首帧未测量时必须给一个正的兜底缩放，实际 $scale", scale > 0f)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceHop
./gradlew :app:testDebugUnitTest --tests "tech.illusion.spacehop.content.WindowScaleTest"
```

预期：编译失败，`Unresolved reference: WindowScale`。

- [ ] **Step 3: 写 WindowScale**

创建 `app/src/main/java/tech/illusion/spacehop/content/WindowScale.kt`：

```kotlin
package tech.illusion.spacehop.content

import tech.illusion.spacehop.game.DifficultyCurve
import tech.illusion.spacehop.game.PlatformSpawner

/**
 * 把玩法几何（以米为单位、固定不变）装进窗口体积里的缩放计算。
 *
 * **缩放只作用于视觉根实体，玩法的米数一律不变。** `DifficultyCurve` / `PlatformSpawner` /
 * `LandingJudge` / `ScoreRules` 读到的永远是同样的数字，所以窗口里和沉浸里的同一局判定完全
 * 一致、分数可比，现有的玩法单测也继续有效。
 */
object WindowScale {

    /** 最大的平台半径。跟着 `DifficultyCurve` 的起始值走——它只会随难度变小，不会变大。 */
    private const val MAX_FOOTPRINT_RADIUS_M = 0.09f

    /** 场景前后要占的米数：最远的一跳，加上两端平台各自的半径。 */
    const val SCENE_DEPTH_M = DifficultyCurve.MAX_DISTANCE_CEILING_M + 2f * MAX_FOOTPRINT_RADIUS_M

    /** 场景左右要占的米数：最大横向偏移加平台半径，左右各一份。 */
    const val SCENE_WIDTH_M = 2f * (PlatformSpawner.MAX_LATERAL_OFFSET_M + MAX_FOOTPRINT_RADIUS_M)

    /** 场景上下要占的米数：跳跃弧高加青蛙自身高度，留一点余量。 */
    const val SCENE_HEIGHT_M = 0.25f

    /** 视图还没测量出来（首帧半径为 0）时的兜底缩放。返回 0 会让整个沙盘不可见，
     * 看上去像模型加载失败而不是还没测量。 */
    private const val UNMEASURED_FALLBACK_SCALE = 0.55f

    /** 留给包围盒边缘的余量比例——实体是被裁剪到 **SpatialView 的** 包围盒（不是
     * WindowContainer 的），贴边的平台会被切掉一半。 */
    private const val MARGIN = 0.92f

    /**
     * 把场景等比缩放到刚好装进给定的半径尺寸里。三个轴取最小值，哪个轴最紧哪个轴说了算。
     *
     * 窗口是用户可等比缩放的，所以这个值要在 `onGloballyPositioned` 重新测量后重算——但玩法
     * 的米数不跟着变。
     */
    fun fitScale(halfWidthM: Float, halfHeightM: Float, halfDepthM: Float): Float {
        if (halfWidthM <= 0f || halfHeightM <= 0f || halfDepthM <= 0f) {
            return UNMEASURED_FALLBACK_SCALE
        }
        val byWidth = (halfWidthM * 2f) / SCENE_WIDTH_M
        val byHeight = (halfHeightM * 2f) / SCENE_HEIGHT_M
        val byDepth = (halfDepthM * 2f) / SCENE_DEPTH_M
        return minOf(byWidth, byHeight, byDepth) * MARGIN
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceHop
./gradlew :app:testDebugUnitTest --tests "tech.illusion.spacehop.content.WindowScaleTest"
grep -o 'tests="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-tech.illusion.spacehop.content.WindowScaleTest.xml
```

预期：`tests="5" failures="0" errors="0"`。

**如果 `defaultWindowYieldsTheAgreedSandboxScale` 失败**：说明 `MARGIN` 或 `SCENE_HEIGHT_M` 需要
调整以命中用户认可的 0.55×。调常量，不要放宽测试的容差——0.55 是用户在设计阶段明确选定的沙盘
观感，容差 0.06 已经是合理余量。

- [ ] **Step 5: 写 WindowHome 本体**

重写 `app/src/main/java/tech/illusion/spacehop/content/WindowHome.kt`。结构如下（参照
`ImmersiveHome.kt` 的 `SpatialView` 用法，以及 `SpaceFireworks/content/HomeVolume.kt` 的
`Augment` + `onGloballyPositioned` 再测量模式）：

```kotlin
package tech.illusion.spacehop.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.detectTapGestures
import com.pico.spatial.ui.foundation.layout.depth
import com.pico.spatial.ui.foundation.window.Augment
import com.pico.spatial.ui.foundation.window.AugmentContentAlignment
import com.pico.spatial.ui.foundation.window.NormalizedPoint3D
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.StageStyle
import kotlinx.coroutines.launch
import tech.illusion.spacehop.game.HopPhase
import tech.illusion.spacehop.game.HopSession

/**
 * 窗口态（WindowContainer）的壳。
 *
 * 和沉浸壳的差别只有四处：输入源（按压而非捏合——窗口里拿不到任何追踪数据）、帧钟（delay 而非
 * withFrameNanos）、根实体缩放（沙盘而非 1:1）、面板布局（Augment 工具条而非 AttachmentPanel）。
 * 玩法编排两边共用 [rememberHopRun]。
 */
@Composable
fun WindowHome() {
    val context = LocalContext.current
    val converter = LocalPhysicalLengthConverter.current
    val density = LocalDensity.current
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()

    val chargeInput = remember { PointerChargeInput() }
    val run = rememberHopRun(
        chargeInput = chargeInput,
        clock = HopClocks.delayDriven(),
        // 窗口态没有可以精准震动的目标：pointer 事件给不出左右手。
        onHaptic = { _, _ -> },
    )

    val sceneRoot = remember { Entity() }
    var halfW by remember { mutableFloatStateOf(0f) }
    var halfH by remember { mutableFloatStateOf(0f) }
    var blockedMessage by remember { mutableStateOf<String?>(null) }

    val halfDepth = WindowScale.SCENE_DEPTH_M / 2f
    val scale = WindowScale.fitScale(halfW, halfH, halfDepth)

    // 窗口可被用户等比缩放，所以每次重新测量都要重算缩放——但玩法的米数不跟着变。
    LaunchedEffect(scale) {
        sceneRoot.components[TransformComponent::class.java]
            ?.setScaleVector(Vector3(scale, scale, scale))
    }

    val chargeEnabled = HopSession.phase is HopPhase.Playing &&
        HopSession.runState.isAlive &&
        !HopSession.paused

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .depth(converter.lengthToDp(WindowScale.SCENE_DEPTH_M, LengthUnit.Meters))
            .onGloballyPositioned { coords ->
                with(density) {
                    halfW = converter.dpToLength(coords.size.width.toDp(), LengthUnit.Meters) / 2f
                    halfH = converter.dpToLength(coords.size.height.toDp(), LengthUnit.Meters) / 2f
                }
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
            sceneRoot.components[TransformComponent::class.java]?.apply {
                // 锚点往后下方偏：实体被裁剪到 SpatialView 自己的包围盒，最远的平台要留在盒内。
                setPosition(Vector3(0f, -WindowScale.SCENE_HEIGHT_M / 2f, WindowScale.SCENE_DEPTH_M / 2f))
                setScaleVector(Vector3(scale, scale, scale))
            }
            run.buildScene(sceneRoot)
            // 从沉浸切回来时会话是冻结的，场景建好了才解冻。冷启动时本来就是 false，幂等。
            HopSession.onContainerSwitchSettled()
        },
        attachments = {},
    )

    Augment(
        anchor = NormalizedPoint3D.BottomFront,
        alignment = AugmentContentAlignment.BottomCenter,
        enableMaterialBackground = false,
    ) {
        PicoTheme {
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
                    modifier = Modifier.padding(12.dp),
                ) {
                    HudContent(
                        score = HopSession.runState.score,
                        comboStreak = HopSession.runState.comboStreak,
                    )
                    ChargeBar(state = chargeInput.chargeState.collectAsState().value)
                    Button(
                        onClick = {
                            scope.launch {
                                val outcome = enterImmersive(
                                    openStage = {
                                        navigator.openStage(IMMERSIVE_STAGE_ID, StageStyle.Mixed) ==
                                            com.pico.spatial.ui.platform.containers.OpenStageResult.Allowed
                                    },
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
                    ) { Text("进入沉浸") }
                }
            }
            blockedMessage?.let { Text(it, color = PicoTheme.colorScheme.labelSecondary) }
        }
    }
}
```

**实现时必须逐项核对的四件事**（签名可能与上面的草稿不完全一致，以 SDK 源码为准）：

1. `SpatialView` 的 `initial` lambda 签名与 `ImmersiveHome.kt` 里保持一致。
2. `run.buildScene(sceneRoot)` 与 `run.bestScore()` 由 Task 6 提供，本任务直接用。
3. `Augment` 的 `offset` 参数有 `IntOffset3D` 与 `DpOffset3D` 两个重载，用 `DpOffset3D` 那个
   （默认值 `DpOffset3D.Zero`，不传即可）。
4. `navigator.openStage(...)` 的返回值是 `OpenStageResult`（sealed class，取值
   `Allowed` / `NotAllowed` / `Error(code, reason)`）。草稿里用 `==` 与 `Allowed` 比较可行，
   但更稳妥的写法是 `is OpenStageResult.Allowed`；失败时把 `Error` 的 `reason` 打进日志，
   否则被拒的原因在现场无从查起。

- [ ] **Step 6: 写 ChargeBar**

在 `WindowHome.kt` 底部加一个私有 composable。蓄力条是窗口态唯一的蓄力反馈（没有指尖球），
复用 `ChargeIndicatorColor` 的配色规则让两个容器的蓄力语义一致：

```kotlin
/** 窗口态的蓄力反馈。沉浸态用四颗指尖球，窗口态没有手部数据，只能靠这条 + 青蛙自身的下蹲压缩。
 *
 * 颜色沿用 [tech.illusion.spacehop.game.ChargeIndicatorColor] 的绿→红插值，和指尖球同一套语义。 */
@Composable
private fun ChargeBar(state: ChargeUiState) {
    val rgb = ChargeIndicatorColor.forFraction(state.chargeFraction)
    Box(
        modifier = Modifier
            .width(120.dp)
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(PicoTheme.colorScheme.fillTertiary),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(if (state.isCharging) state.chargeFraction else 0f)
                .background(Color(rgb[0], rgb[1], rgb[2], 1f)),
        )
    }
}
```

`PicoTheme.colorScheme.fillTertiary` 与 `labelSecondary` 已核对为真实角色名
（`com/pico/spatial/ui/design/ColorScheme.kt` 第 52 / 56 行）。**不要引入 Material 的颜色。**

本段还需要补的 import（草稿里没列全，编译会直接报出来）：
`androidx.compose.foundation.background`、`androidx.compose.foundation.layout.Box`、
`androidx.compose.foundation.layout.fillMaxHeight`、`androidx.compose.foundation.layout.fillMaxWidth`、
`androidx.compose.foundation.layout.height`、`androidx.compose.foundation.layout.width`、
`androidx.compose.foundation.shape.RoundedCornerShape`、`androidx.compose.ui.draw.clip`、
`androidx.compose.ui.graphics.Color`、`androidx.compose.runtime.collectAsState`（Step 5 的
`chargeInput.chargeState.collectAsState()` 要用），以及
`tech.illusion.spacehop.game.ChargeIndicatorColor`。

- [ ] **Step 7: 编译 + 全量单测**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceHop
./gradlew :app:assembleDebug :app:testDebugUnitTest
grep -L 'failures="0" errors="0"' app/build/test-results/testDebugUnitTest/*.xml
```

预期：BUILD SUCCESSFUL，`grep -L` 无输出。

- [ ] **Step 8: 确认没有 Material 混进来**

```bash
grep -rn "^import androidx.compose.material" app/src/main/java/ || echo "clean"
```

预期：输出 `clean`。

- [ ] **Step 9: 提交**

```bash
git add app/src/main/java/tech/illusion/spacehop/content/WindowHome.kt \
        app/src/main/java/tech/illusion/spacehop/content/WindowScale.kt \
        app/src/main/java/tech/illusion/spacehop/content/HopRun.kt \
        app/src/main/java/tech/illusion/spacehop/content/ImmersiveHome.kt \
        app/src/test/java/tech/illusion/spacehop/content/WindowScaleTest.kt
git commit -m "feat: make the game playable inside the 3D window

The sandbox scale is derived from the gameplay constants rather than
hand-tuned, so a difficulty-curve change cannot silently push the far
platform outside the view's bounding box and get it clipped. Gameplay
metres are untouched by the scale -- the same run judges identically in
either container, which is what makes carrying it across meaningful.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: 沉浸态的「退出沉浸」

**Files:**
- Modify: `app/src/main/java/tech/illusion/spacehop/content/HudContent.kt`
- Modify: `app/src/main/java/tech/illusion/spacehop/content/ImmersiveHome.kt`
- Modify: `app/src/main/java/tech/illusion/spacehop/content/HopRun.kt`（新增跳跃吞没窗口）
- Test: `app/src/test/java/tech/illusion/spacehop/content/JumpGraceTest.kt`

**Interfaces:**
- Consumes: `exitImmersive`（Task 5）、`HopSession`（Task 2）
- Produces:
  - `HudContent(score, comboStreak, onExitImmersive: (() -> Unit)? = null)`——默认 null 使窗口壳的调用不变
  - `HopRun.swallowNextReleaseUntil(nanos: Long)` 与 `HopRun.shouldSwallowRelease(nowNanos: Long): Boolean`

**已知陷阱**：按「退出沉浸」这个动作本身就是一次捏合，而 `PinchChargeController` 独立于按钮监听
裸手距离，会把同一次物理松手读成一次完整蓄力并立即起跳。现有代码已经为「开始」按钮解决过这个
问题（`JUMP_INPUT_GRACE_MS = 400ms` 吞掉开局瞬间的松手），这里复用同一机制。

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/tech/illusion/spacehop/content/JumpGraceTest.kt`：

```kotlin
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
```

- [ ] **Step 2: 跑测试确认失败**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceHop
./gradlew :app:testDebugUnitTest --tests "tech.illusion.spacehop.content.JumpGraceTest"
```

预期：编译失败，`Unresolved reference: JumpGrace`。

- [ ] **Step 3: 写 JumpGrace 并接进 HopRun**

在 `app/src/main/java/tech/illusion/spacehop/content/HopRun.kt` 顶部加：

```kotlin
/**
 * 吞掉紧跟在一次 SpatialUI 按钮按下之后的那一次松手。
 *
 * 为什么需要：在沉浸态里按任何按钮本身就是一次捏合，而 [PinchChargeController] 独立于按钮
 * 监听裸手距离——同一次物理松手会同时被按钮和蓄力状态机看到，后者会把它读成一次完整蓄力并
 * 立即起跳。这个机制原本是为「开始」按钮写的（[JUMP_INPUT_GRACE_MS]），「退出沉浸」按钮
 * 复用同一套。
 */
class JumpGrace {
    private var armedUntilNanos: Long = Long.MIN_VALUE

    /** 在按钮按下的瞬间调用。 */
    fun armAt(pressNanos: Long) {
        armedUntilNanos = pressNanos + JUMP_INPUT_GRACE_MS * 1_000_000L
    }

    fun shouldSwallow(nowNanos: Long): Boolean = nowNanos < armedUntilNanos
}
```

在 `HopRun` 里持有 `val jumpGrace = JumpGrace()`，并把 `rememberHopRun` 的 `onRelease` 回调里
现有的那句

```kotlin
                val sinceRunStartMs = (System.nanoTime() - runStartedAtNanos) / 1_000_000L
                if (sinceRunStartMs < JUMP_INPUT_GRACE_MS) return@start
```

改为（保留原有的开局宽限，再叠加按钮宽限）：

```kotlin
                val nowNanos = System.nanoTime()
                val sinceRunStartMs = (nowNanos - run.runStartedAtNanos) / 1_000_000L
                if (sinceRunStartMs < JUMP_INPUT_GRACE_MS) return@start
                // 按钮按下带来的那次松手不是玩家想跳——见 JumpGrace。
                if (run.jumpGrace.shouldSwallow(nowNanos)) return@start
```

- [ ] **Step 4: 跑测试确认通过**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceHop
./gradlew :app:testDebugUnitTest --tests "tech.illusion.spacehop.content.JumpGraceTest"
grep -o 'tests="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-tech.illusion.spacehop.content.JumpGraceTest.xml
```

预期：`tests="3" failures="0" errors="0"`。

- [ ] **Step 5: 给 HudContent 加可选的退出入口**

修改 `app/src/main/java/tech/illusion/spacehop/content/HudContent.kt`：签名改为

```kotlin
@Composable
fun HudContent(score: Int, comboStreak: Int, onExitImmersive: (() -> Unit)? = null) {
```

在现有 `Row` 的连击徽章之后追加（`onExitImmersive` 为 null 时整块不渲染，窗口壳的调用因此完全
不受影响）：

```kotlin
        if (onExitImmersive != null) {
            Button(
                onClick = onExitImmersive,
                // 飞行中置灰：飞行动画状态在 CharacterController 里、不在 HopSession 里。
                enabled = HopSession.canSwitchContainer(),
                modifier = Modifier.padding(start = 10.dp),
            ) { Text("退出沉浸") }
        }
```

需要新增的 import：`com.pico.spatial.ui.design.Button`、`tech.illusion.spacehop.game.HopSession`。

- [ ] **Step 6: 在 ImmersiveHome 里接上**

修改 `ImmersiveHome.kt` 的 `AttachmentPanel(SIDE_PANEL)` 里那次 `HudContent(...)` 调用：

```kotlin
                        HudContent(
                            score = HopSession.runState.score,
                            comboStreak = HopSession.runState.comboStreak,
                            onExitImmersive = {
                                run.jumpGrace.armAt(System.nanoTime())
                                scope.launch {
                                    exitImmersive(
                                        openWindow = { navigator.openWindowContainer(MAIN_WINDOW_ID) },
                                        closeStage = { navigator.closeStage() },
                                    )
                                }
                            },
                        )
```

`navigator` 由 `val navigator = LocalSpatialNavigator.current` 取得（文件顶部新增）。

- [ ] **Step 7: 编译 + 全量单测**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceHop
./gradlew :app:assembleDebug :app:testDebugUnitTest
grep -L 'failures="0" errors="0"' app/build/test-results/testDebugUnitTest/*.xml
ls app/build/test-results/testDebugUnitTest/*.xml | wc -l
```

预期：BUILD SUCCESSFUL；`grep -L` 无输出；xml 文件数 **20**
（原有 13 个 + 本次新增 7 个：`HopClockTest`、`HopSessionTest`、`ChargeHandTest`、
`PointerChargeInputTest`、`ContainerSwitchTest`、`WindowScaleTest`、`JumpGraceTest`）。

数量对不上说明有测试类没被执行——去查，不要忽略。`./gradlew -q` 成功时是零输出，零输出不等于
通过；判据永远是 xml 里的 `failures="0" errors="0"` 和文件数。

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/tech/illusion/spacehop/content/HudContent.kt \
        app/src/main/java/tech/illusion/spacehop/content/ImmersiveHome.kt \
        app/src/main/java/tech/illusion/spacehop/content/HopRun.kt \
        app/src/test/java/tech/illusion/spacehop/content/JumpGraceTest.kt
git commit -m "feat: add the way back out of the Stage

Pressing any button in the Stage is itself a pinch, and the charge state
machine watches raw finger distance independently of the button -- so
the same physical release would read as a completed charge and fire a
wasted jump the instant the player leaves. Reuses the grace window the
start button already needed.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: 设备验证（门禁 B）

**Files:**
- Create: `app/src/androidTest/java/tech/illusion/spacehop/WindowChargeProbe.kt`（验完删除）
- Create: `.spatialsdk/ui-verify/window-mode-*.png`（证据）

**前置**：本任务会执行 `pico-cli` 与模拟器操作。按项目约定先跑 `pico-env-doctor` 预检，再按
`spatial-emulator-usage` 走模拟器流程；两者都属于环境依赖型任务的必经步骤。

**四个已知会骗人的地方**（照做，不要绕）：

1. **沉浸态在模拟器上帧钟只有 1Hz**，而窗口/平面侧的 `delay` 循环能跑满 60–80fps。任何
   "等 N 秒后截图"的脚本在沉浸侧必然踩空。改成**轮询日志锚点直到出现**。
2. **截图可能是陈旧帧**（实测差距达 ≥6 秒）。每张截图必须配一条日志锚点交叉核对，否则短命 UI
   （蓄力条、飞行中）会得到假阴性。
3. **`adb screencap` 在模拟器上能拍到空间内容**，`pico-cli capture` 不行——视觉取证优先用
   `screencap`。读 logcat 一律加 `-a`，否则一个控制字节就会让 grep 把整个日志当二进制静默跳过、
   零输出，看上去像"应用根本没打日志"。
4. **模拟器的 `Build.TYPE` 是 `user`**。调试钩子绝不能写 `if (Build.TYPE != "user")` 守卫。

**设备锁**：全程持锁，owner 字符串跨 Bash 调用必须稳定（**不要**用 `$$` 之类每次调用都变的值）。
截图完成后立即 stop 应用并释放锁。

- [ ] **Step 1: 环境预检 + 装机**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceHop
./gradlew :app:assembleDebug
```

然后按 `spatial-emulator-usage` 启动模拟器并安装 `app/build/outputs/apk/debug/app-debug.apk`。

- [ ] **Step 2: 验证档 A —— 启动落在窗口里**

启动应用后轮询日志锚点（不要固定 sleep）直到出现窗口容器创建的记录，再 `adb screencap`。

判定：截图里应看到一个悬浮的体积窗口，里面有缩小的沙盘（盘子 + 青蛙），底部一条工具条。
存为 `.spatialsdk/ui-verify/window-mode-01-launch.png`。

**这一档只验静态构图，验不出帧循环和状态机。** 下一档才是关键。

- [ ] **Step 3: 验证档 B —— 走真实路径的动态验证**

**不能**用"首次组合前就把状态设好"的 DEBUG 截图——那样 `when` 第一次求值就选对分支，截图完全
正常，正好绕过帧循环和状态机的 bug。

创建 `app/src/androidTest/java/tech/illusion/spacehop/WindowChargeProbe.kt`：用 androidTest 探针
绕过 UI 层直接驱动 `PointerChargeInput.onPressDown()` → 等待 → `onPressUp()`，并在每一步打
`Log.i("SpaceHopProbe", ...)` 锚点。之所以走探针而不是 `adb tap`：模拟器上手势不可靠，点不准
按钮，还可能把沉浸态关掉。

跑探针，然后：

1. 轮询 logcat（`adb logcat -a -d | grep -a SpaceHopProbe`）确认按下/松手锚点都出现。
2. 轮询 `SpaceHopJump` 锚点（`HopRun.performJump` 已有的 `Log.e`）确认真的起跳了，
   并核对 `flightDistance` 与按住时长相符。
3. 起跳后立刻 `adb screencap`，存为 `.spatialsdk/ui-verify/window-mode-02-jump.png`。

**判定帧循环是否活着**：`StageBoardAnimator.animateAdvance` 跑完需要 2×240ms。如果 `delay` 帧钟
是死的，这个协程会卡住、日志里不会出现落地之后的推进锚点。在 `HopRun.performJump` 的
`boardAnimator.animateAdvance(advanced)` 之后加一行临时
`Log.i("SpaceHopProbe", "advance-done")`，确认它出现——这是窗口帧钟活着的决定性证据。

- [ ] **Step 4: 验证档 C —— 容器切换**

用探针（或在窗口工具条按钮上用 `adb tap`，失败则回落到探针）触发 `enterImmersive`，然后：

1. 轮询日志确认 `openStage` 的结果。若为 `NotAllowed`，说明别的会话正占着 Full Space——
   **这不是 bug**，等待或稍后重试，不要去 force-stop 别人的会话。
2. 确认沉浸态起来后 `HopSession.runState.score` 与切换前一致（在 `ImmersiveHome` 的
   `SpatialView.initial` 里临时加 `Log.i("SpaceHopProbe", "immersive score=${HopSession.runState.score}")`）。
3. 截图存为 `.spatialsdk/ui-verify/window-mode-03-immersive.png`。
4. 触发 `exitImmersive`，确认回到窗口且分数仍然一致，截图存为
   `.spatialsdk/ui-verify/window-mode-04-back.png`。

- [ ] **Step 5: 清理**

```bash
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceHop
rm app/src/androidTest/java/tech/illusion/spacehop/WindowChargeProbe.kt
```

删掉 Step 3 加的临时 `Log.i("SpaceHopProbe", ...)` 行。停掉应用，释放设备锁。

- [ ] **Step 6: 最终全量验证**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceHop
./gradlew :app:assembleDebug :app:testDebugUnitTest
grep -L 'failures="0" errors="0"' app/build/test-results/testDebugUnitTest/*.xml
grep -rn "^import androidx.compose.material" app/src/main/java/ || echo "no material"
grep -rn "SpaceHopProbe" app/src/ || echo "no probe leftovers"
```

预期：BUILD SUCCESSFUL；`grep -L` 无输出；输出 `no material`；输出 `no probe leftovers`。

- [ ] **Step 7: 提交证据**

```bash
git add .spatialsdk/ui-verify/window-mode-*.png
git commit -m "test: on-device evidence for the window mode round trip

Captured against log anchors rather than fixed sleeps: the Stage's frame
clock runs at about 1Hz on the emulator and screenshots have been
measured lagging by six seconds or more, so a timed capture proves
nothing on its own.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 8: 交还给用户做真机试玩**

模拟器给不出结论的三件事需要用户上头显：

1. 0.55× 沙盘在真机上够不够看清——平台半径缩到 5cm 量级，落点判定的"完美 / 普通 / 失手"
   视觉差别很细。
2. 按住 3D 视图蓄力的手感，以及射线指向下按住会不会漂。
3. 0.5 × 0.4 × 0.45m 摆在房间里是不是合适的存在感。

提醒：真机上 `adb screencap` 是坏的（退出码为 0、产物却是 50 字节错误文本，按 `$?` 判断必被骗），
真机侧只能靠日志判活，视觉结论依赖用户肉眼。
