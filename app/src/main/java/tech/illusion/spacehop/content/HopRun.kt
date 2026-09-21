package tech.illusion.spacehop.content

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.platform.HandControllerHapticType
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import tech.illusion.spacehop.game.ChargeCurve
import tech.illusion.spacehop.game.DifficultyCurve
import tech.illusion.spacehop.game.HighScoreStore
import tech.illusion.spacehop.game.HopPhase
import tech.illusion.spacehop.game.HopSession
import tech.illusion.spacehop.game.LandingJudge
import tech.illusion.spacehop.game.LandingResult
import tech.illusion.spacehop.game.PlatformKind
import tech.illusion.spacehop.game.PlatformSpawner
import tech.illusion.spacehop.game.ScoreRules
import tech.illusion.spacehop.game.StageBoard

// Pressing "开始" is itself a pinch — the SDK's own button-select gesture, not our charge/jump
// input. PinchChargeController watches raw hand distance independently of the button, so if the
// player's fingers are still closing/separating right as `phase` flips to Playing, that same
// physical release can be read as a completed charge and fire an instant jump. Swallow any
// release within this window of a run starting so only the player's next, deliberate pinch counts.
internal const val JUMP_INPUT_GRACE_MS = 400L
// Design ask: the frog must jump within this long each round or it gets caught. A hand-tuned
// game design knob, independent of countdown.mp3's own ~12.7s runtime — a real player jump cuts
// the cue short (see onRelease below), but the app's own forced-miss catch lets it keep playing.
internal const val ROUND_TIME_LIMIT_MS = 10_200L
// Forced-miss distance multiplier for the timeout catch below: PlatformSpawner never spawns a
// target farther than DifficultyCurve.maxDistanceMeters(consecutiveJumps) (it interpolates up to,
// but never reaches, that ceiling), so launching at 1.5x that ceiling always overshoots the
// target by at least half of it — e.g. at minimum, 0.5 * 0.32m = 0.16m — which is always well
// past the widest possible footprint radius (0.09m). Provably safe, not tuned by feel, and reads
// as the frog leaping too hard and overshooting the plate rather than falling meekly short.
internal const val AUTO_FAIL_LAUNCH_DISTANCE_MULTIPLIER = 1.5f
// V2 alt path into the same window-crossing beat as StageBoard.WINDOW_TRIGGER_JUMPS_COMPLETED,
// for a player going for speed instead of raw combo length: land this many plates with no more
// than this much real time between the oldest and newest of them. Tracked here (not in
// StageBoard, which stays wall-clock-free and purely testable) via `recentLandingTimestampsMs`
// below, then handed to StageBoard.advance as a plain bool each landing.
internal const val FAST_STREAK_JUMPS_REQUIRED = 10
internal const val FAST_STREAK_WINDOW_MS = 10_000L
// The round-timeout watchdog below polls in this granularity instead of one straight
// `delay(ROUND_TIME_LIMIT_MS)`, so the exit-confirm freeze (`paused`) can hold the catch off
// indefinitely — a real, already-suspended delay() can't be un-suspended once started, so only
// time actually spent unpaused may count toward the limit.
internal const val ROUND_TIMEOUT_POLL_MS = 100L
// The platform plane itself: StageBoardAnimator puts each platform's landable top surface at
// exactly this height (verified on-device — both the plate and the lotus leaf measure top.y ≈ 0
// relative to their wrapper), and CharacterController.load puts the frog's feet on its own
// wrapper origin, so the two meet with nothing to add here.
//
// This was `0.05` from the original procedural-sphere character (git history: the placeholder
// used `characterMesh(radiusMeters = 0.05f)`, so half its height was the right centre height).
// After the frog model replaced the sphere it survived as an accidental counterweight to a
// container-space measurement bug in CharacterController.load — removing it exposed that bug
// rather than causing one. Fixing the measurement is what makes 0 correct here; don't reintroduce
// an offset to compensate for grounding, tune FROG_EXTRA_GROUND_OFFSET_M instead.
internal const val CHARACTER_GROUND_Y = 0f

/**
 * 一局游戏的编排：场景构建、开局、起跳判定、结算。
 *
 * 这里只放两个容器**完全相同**的部分。容器各自不同的东西——输入源、帧钟、根实体的摆位与缩放、
 * 面板布局、指尖球——都留在各自的壳里，通过构造参数或回调进来。规则只有这一份，所以沉浸态与
 * 窗口态的玩法不可能漂移。
 *
 * 局面状态不在本类里，而在 [HopSession]：窗口的 composition 在切换容器时整个 dispose，本类
 * 会随之重建，只有住在 composition 之外的东西才能跨过切换活下来。本类持有的是**不跨容器**的
 * 那部分——板面动画器、青蛙控制器、音效、随机源，它们全都绑在当前容器的场景图上。
 *
 * @param clock 动画时间源。沉浸态传 [HopClocks.frameAligned]，窗口态传 [HopClocks.delayDriven]
 *   ——`withFrameNanos` 在 `DefaultWindowContainer` 下只触发一次，见 [HopClock]。
 */
class HopRun(context: Context, private val clock: HopClock) {

    private val spawner = PlatformSpawner(Random.Default)
    private val boardAnimator = StageBoardAnimator(clock)
    private val highScoreStore = HighScoreStore(context)

    /** 容器壳也要用：退出确认框冻结回合时 [Sfx.pause]，取消时 [Sfx.resume]。生命周期的
     * 暂停/恢复与最终 `release` 由 [rememberHopRun] 负责，壳不必操心。 */
    val sfx = Sfx(context)

    /** 由 [buildScene] 赋值。普通 `var` 而不是 `mutableStateOf`：只在编排内部和两个壳的
     * effect 里读写，从来不需要触发重组（与 [HopSession.recentLandingTimestampsMs] 同理）。 */
    internal var characterController: CharacterController? = null

    /** 开局宽限期的基准，见 [JUMP_INPUT_GRACE_MS]。 */
    var runStartedAtNanos: Long = 0L

    /** 建立板面动画器与青蛙。两个容器壳的 `SpatialView.initial` 都调这个，所以场景构建只有
     * 一份。指尖球（[IndicatorBallsRenderer]）**不**在这里——它是沉浸壳独有的，窗口态没有手部
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

    fun startNewRun() {
        HopSession.startNewRun(spawner)
        val fresh = HopSession.boardState
        boardAnimator.placeInstant(fresh) // cold reset, not a landing — no slide/fade
        characterController?.apply {
            setIdlePosition(Vector3(0f, CHARACTER_GROUND_Y, 0f))
            // Snap, not turn: a cold run start has no landing transition to fold the turn into.
            faceTarget(fresh.platforms[1].lateralOffsetMeters, fresh.platforms[1].distanceFromPreviousMeters)
        }
        runStartedAtNanos = System.nanoTime()
    }

    // V2: a miss in the lotus pond reads as "swam home", not "caught" — see ResultPanelContent.
    fun endRun(diedInLotusPond: Boolean) {
        val isNewRecord = highScoreStore.submitScore(HopSession.runState.score)
        if (isNewRecord) sfx.play(HopSfxEvent.NEW_RECORD)
        HopSession.phase = HopPhase.Result(isNewRecord, diedInLotusPond)
    }

    /** The one place a jump actually flies and its outcome is applied — shared by a real
     * player release and the round-timeout watchdog's own forced jumps (both the
     * guaranteed-miss catch and the guaranteed-success window crossing), so all three keep the
     * exact same fly/judge/advance sequence instead of three near-duplicate copies drifting
     * apart. A window-crossing target ([PlatformSpec.windowCrossingDistanceMeters] non-null)
     * forces [LandingResult.SUCCESS] instead of judging [flightDistance] by distance (V2 design
     * ask: this beat must never fail the player out) — the window prop itself is already visible
     * by the time this runs (revealed as soon as the compound jump was spawned as `next`, see
     * the tail of this function), not just for the flight.
     *
     * V2: once the target is [PlatformKind.LOTUS], landing/falling swaps to the lotus-pond
     * stage's own cues (a single landing chime instead of perfect/success, a splash instead of
     * a clatter) — see [Sfx.playLotusLanding] / [StageBoardAnimator.playFallSound].
     *
     * 整个函数体被 `try { ... } finally { HopSession.isFlying = false }` 包住：飞行中禁止切换
     * 容器靠的就是这个标志（飞行状态住在 [CharacterController] 里，过不了容器），而 MISS 分支
     * 里有 `return`，只有 `finally` 能保证任何出口——包括抛异常——都会把它清掉。 */
    suspend fun performJump(flightDistance: Float, trigger: String) {
        HopSession.isFlying = true
        try {
            val controller = characterController ?: return
            val target = HopSession.boardState.platforms[1]
            val isWindowCrossing = target.windowCrossingDistanceMeters != null
            controller.flyTo(flightDistance, target.lateralOffsetMeters, target.distanceFromPreviousMeters)
            if (isWindowCrossing) boardAnimator.retireWindowCrossing()
            val result = if (isWindowCrossing) LandingResult.SUCCESS else LandingJudge.judge(flightDistance, target)
            Log.e(
                "SpaceHopJump",
                "trigger=$trigger flightDistance=$flightDistance targetDistance=${target.distanceFromPreviousMeters} " +
                    "footprintRadius=${target.footprintRadiusMeters} isWindowCrossing=$isWindowCrossing result=$result",
            )
            HopSession.runState = ScoreRules.applyLanding(HopSession.runState, result)
            if (result == LandingResult.MISS) {
                boardAnimator.playFallSound(target.kind) // from the plate/leaf that stayed empty, not the frog
                if (target.kind == PlatformKind.LOTUS) sfx.stopStream() // a loop never ends on its own
                controller.fall(tippedForward = flightDistance > target.distanceFromPreviousMeters)
                endRun(diedInLotusPond = target.kind == PlatformKind.LOTUS)
                return
            }
            if (target.kind == PlatformKind.LOTUS) {
                sfx.playLotusLanding()
            } else {
                sfx.play(if (result == LandingResult.PERFECT) HopSfxEvent.LANDED_PERFECT else HopSfxEvent.LANDED_SUCCESS)
            }
            // Speed-based alt path to the same window-crossing beat as the combo-count one: once the
            // last FAST_STREAK_JUMPS_REQUIRED landings (this one included) all fit inside
            // FAST_STREAK_WINDOW_MS of real time, force the crossing regardless of combo length. Fed
            // from every landing here — whichever trigger, `player`/`auto-fail`/`auto-window` — since
            // an auto-fail never reaches this line (it's a MISS, judged above) and an already-crossed
            // run just ignores this flag (StageBoard.advance's LOTUS branch takes priority).
            HopSession.recentLandingTimestampsMs.addLast(System.currentTimeMillis())
            while (HopSession.recentLandingTimestampsMs.size > FAST_STREAK_JUMPS_REQUIRED) {
                HopSession.recentLandingTimestampsMs.removeFirst()
            }
            val isFastStreak = HopSession.recentLandingTimestampsMs.size == FAST_STREAK_JUMPS_REQUIRED &&
                HopSession.recentLandingTimestampsMs.last() - HopSession.recentLandingTimestampsMs.first() <=
                FAST_STREAK_WINDOW_MS
            val advanced = StageBoard.advance(
                HopSession.boardState,
                spawner,
                consecutiveJumps = HopSession.runState.jumpsCompleted,
                forceWindowCrossing = isFastStreak,
            )
            HopSession.boardState = advanced
            HopSession.jumpCommittedThisRound = false // the new round's own countdown/stream starts fresh below
            // Recentre the character over the same window as the promoted platform's slide
            // (StageBoardAnimator.animateAdvance) so they arrive at the anchor together, instead of
            // the character snapping there only after the platform finishes. The turn toward the
            // newly-spawned next platform rides along in that same window.
            coroutineScope {
                launch {
                    controller.recenterTo(
                        target = Vector3(0f, CHARACTER_GROUND_Y, 0f),
                        nextLateralOffsetMeters = advanced.platforms[1].lateralOffsetMeters,
                        nextForwardDistanceMeters = advanced.platforms[1].distanceFromPreviousMeters,
                        durationMs = ADVANCE_PHASE_MS,
                    )
                }
                boardAnimator.animateAdvance(advanced)
            }
            // V2: reveal the window (and the lotus backdrop glimpsed through it) the moment the
            // compound crossing jump is spawned, not right before the frog flies through it — the
            // whole round leading up to that jump gets to see it coming.
            val upcoming = advanced.platforms[1]
            if (upcoming.windowCrossingDistanceMeters != null) {
                boardAnimator.prepareWindowCrossing(upcoming)
            }
        } finally {
            HopSession.isFlying = false
        }
    }
}

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

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, run.sfx) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> run.sfx.pause()
                Lifecycle.Event.ON_RESUME -> run.sfx.resume()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        chargeInput.start(
            // Controller-trigger input only (bare-hand pinch has no "press start" moment worth
            // a buzz for) — a light tap so charging registers without masking the stronger jump
            // pulse below.
            onChargeStart = { hand ->
                onHaptic(HandControllerHapticType.Hover, hand)
            },
            onRelease = { cappedHeldMillis, hand ->
                // The frog has to exist before anything else is decided — this guard stays ahead
                // of the ones below so a release that arrives before `buildScene` finishes leaves
                // `jumpCommittedThisRound` untouched. `performJump` re-reads the field itself.
                if (run.characterController == null) return@start
                // `paused` gates a live exit-confirm panel the same way `isAlive` already gates a
                // finished run — a real hand release shouldn't commit a jump behind the dialog.
                if (HopSession.phase !is HopPhase.Playing || !HopSession.runState.isAlive || HopSession.paused) {
                    return@start
                }
                // A jump is already in flight (either the player's own, or the watchdog's forced one)
                // — don't let a second release also grab the entity mid-animation.
                if (HopSession.jumpCommittedThisRound) return@start
                val sinceRunStartMs = (System.nanoTime() - run.runStartedAtNanos) / 1_000_000L
                if (sinceRunStartMs < JUMP_INPUT_GRACE_MS) return@start
                // Committed — the round-timeout watchdog below reads this to know a real jump beat
                // the clock. Only a genuine player jump cuts the countdown cue short; the app's own
                // forced jumps (same LaunchedEffect) deliberately leave it playing instead.
                HopSession.jumpCommittedThisRound = true
                onHaptic(HandControllerHapticType.Press, hand)
                run.sfx.stopCountdown()
                // V2: a window-crossing target is a guaranteed-success beat, flown at its own exact
                // (compound) distance rather than whatever the player charged — see performJump.
                val target = HopSession.boardState.platforms[1]
                val flightDistance = if (target.windowCrossingDistanceMeters != null) {
                    target.distanceFromPreviousMeters
                } else {
                    ChargeCurve.launchDistanceMeters(
                        cappedHeldMillis,
                        minDistance = DifficultyCurve.minDistanceMeters(HopSession.runState.jumpsCompleted),
                        maxDistance = DifficultyCurve.maxDistanceMeters(HopSession.runState.jumpsCompleted),
                    )
                }
                scope.launch { run.performJump(flightDistance, trigger = "player") }
            },
        )
        onDispose { chargeInput.stop(); run.sfx.release() }
    }

    LaunchedEffect(Unit) {
        var wasCharging = false
        while (true) {
            delay(16)
            // 窗口态的蓄力进度没有别的驱动源——pointer 输入自己不跑循环，得有人按帧推它，
            // 蓄力条才有东西可读。捏合输入自己会更新 `chargeState`，这行对它是空操作。
            (chargeInput as? PointerChargeInput)?.tickChargeFraction()
            val state = chargeInput.chargeState.value
            // Frozen for the exit-confirm panel: don't let a real hand pinch still visibly
            // squash the frog or advance the charge cue behind the dialog while the run is held.
            if (HopSession.paused) continue
            run.characterController?.let { c ->
                if (state.isCharging) c.applyChargeSquash(state.chargeFraction) else c.resetSquash()
            }
            // Edge-triggered, not level-triggered — mirrors the indicator-ball/squash feedback
            // above by staying ungated on `phase` (charging still visually reacts on the start
            // /result screens today; the sound follows the same existing behaviour).
            if (state.isCharging && !wasCharging) run.sfx.startCharge()
            else if (!state.isCharging && wasCharging) run.sfx.stopCharge()
            wasCharging = state.isCharging
        }
    }

    // Per-round catch timer: restarts on every new `boardState` (a fresh run start or a
    // successful advance both assign a new one), and Compose cancels the previous instance's
    // `delay` outright when that happens — so reaching the code after `delay` here already
    // proves this exact round is still the one in play. `jumpCommittedThisRound` is the only
    // thing that still needs an explicit check: a jump commits well before `boardState` changes
    // (the flight animation runs in between), so without it a last-instant jump would race this
    // watchdog into firing a false catch.
    LaunchedEffect(HopSession.boardState, HopSession.phase) {
        if (HopSession.phase !is HopPhase.Playing) return@LaunchedEffect
        // V2 design ask: the lotus-pond stage has no time limit at all — once the frog has
        // actually landed on a lotus leaf (not just while the window-crossing jump, still judged
        // from the kitchen side, is upcoming as `next`), this is just an ambient loop, not a
        // countdown-and-catch mechanism. Never returns to the kitchen stage, so once this branch
        // is taken it's taken for the rest of the run (StageBoard.advance's one-way transition).
        if (HopSession.boardState.current.kind == PlatformKind.LOTUS) {
            run.sfx.startStream()
            return@LaunchedEffect
        }
        run.sfx.startCountdown()
        // Polls instead of one straight delay(ROUND_TIME_LIMIT_MS) so `paused` (the exit-confirm
        // freeze) can hold this catch off indefinitely — only time actually spent unpaused counts
        // toward the limit, so a player who takes 15 real seconds to decide on the exit dialog
        // doesn't come back to an auto-fail that ran out the clock behind it.
        var elapsedMs = 0L
        while (elapsedMs < ROUND_TIME_LIMIT_MS) {
            delay(ROUND_TIMEOUT_POLL_MS)
            if (!HopSession.paused) elapsedMs += ROUND_TIMEOUT_POLL_MS
        }
        if (HopSession.jumpCommittedThisRound) return@LaunchedEffect
        // Same ordering as onRelease: no frog, no commit. `performJump` re-reads the field.
        if (run.characterController == null) return@LaunchedEffect
        // Committed, same as a real release — blocks both a last-instant player pinch (below)
        // and this same watchdog re-firing. No sfx.stopCountdown() here, unlike onRelease — this
        // is the app's own doing, not the player's, so the countdown cue keeps playing through it.
        HopSession.jumpCommittedThisRound = true
        val target = HopSession.boardState.platforms[1]
        val isWindowCrossing = target.windowCrossingDistanceMeters != null
        if (!isWindowCrossing) {
            // This one *is* a guaranteed miss — set isAlive=false (mirrors ScoreRules.applyLanding's
            // MISS branch) before it starts animating, not after: flyTo below takes ~420ms, and
            // it's this flag (via onRelease's `!runState.isAlive` guard) that stops a last-instant
            // real release from also fighting this coroutine over the entity's transform. A window
            // crossing never sets this — the run is meant to keep going.
            HopSession.runState = HopSession.runState.copy(isAlive = false)
        }
        // The app plays out a real jump attempt instead of just despawning the frog in place —
        // same fly/judge/advance-or-fall path performJump gives a player release, just with a
        // distance engineered to always overshoot (AUTO_FAIL_LAUNCH_DISTANCE_MULTIPLIER), unless
        // the target is the V2 window crossing, which is flown at its own exact (compound)
        // distance and always succeeds.
        val flightDistance = if (isWindowCrossing) {
            target.distanceFromPreviousMeters
        } else {
            DifficultyCurve.maxDistanceMeters(HopSession.runState.jumpsCompleted) *
                AUTO_FAIL_LAUNCH_DISTANCE_MULTIPLIER
        }
        // Launched in `scope`, not awaited directly in this coroutine: performJump mutates
        // `boardState` partway through (see its `boardState = advanced` line), and `boardState`
        // is one of this very LaunchedEffect's own keys. Awaiting it here would let that write
        // schedule Compose to cancel-and-relaunch this exact coroutine, truncating
        // StageBoardAnimator.animateAdvance mid-flight — after it swaps which wrapper is
        // "current" vs "next" but before it recycles the stale one's visual/position — leaving a
        // stranded plate/leaf floating where the old "current" used to sit. onRelease avoids this
        // the same way, for the same reason: it isn't `boardState`-keyed either.
        scope.launch {
            run.performJump(flightDistance, trigger = if (isWindowCrossing) "auto-window" else "auto-fail")
        }
    }

    return run
}
