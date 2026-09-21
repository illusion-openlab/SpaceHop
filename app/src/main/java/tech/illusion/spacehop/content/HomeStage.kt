package tech.illusion.spacehop.content

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.platform.HandController
import com.pico.spatial.ui.platform.HandControllerHapticType
import com.pico.spatial.ui.platform.LocalHandControllerHaptic
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import tech.illusion.spacehop.game.ChargeCurve
import tech.illusion.spacehop.game.DifficultyCurve
import tech.illusion.spacehop.game.HighScoreStore
import tech.illusion.spacehop.game.LandingJudge
import tech.illusion.spacehop.game.LandingResult
import tech.illusion.spacehop.game.PlatformKind
import tech.illusion.spacehop.game.PlatformSpawner
import tech.illusion.spacehop.game.RunState
import tech.illusion.spacehop.game.ScoreRules
import tech.illusion.spacehop.game.StageBoard

private const val PRIMARY_PANEL = "primary"
private const val SIDE_PANEL = "side"
private const val SIDE_OFFSET_M = 0.32f
/** How far in front of the player the primary panel floats. Expressed as a distance from the
 * player rather than as a raw local offset, because the panel is parented to the stage anchor (so
 * it follows the stage) and the anchor already sits [StageCalibration.BOARD_DISTANCE_M] out — the
 * local offset below is derived, so this number is the only one to tune.
 *
 * Note the panel is world-placed, not head-following: this distance is measured from the Stage
 * origin captured at launch, so leaning in or moving after launch changes how close it actually
 * feels without changing this value. */
private const val PRIMARY_PANEL_USER_DISTANCE_M = 0.75f
// EulerAngles(pitch, yaw, roll) applies pitch as an extrinsic rotation around the world X-axis
// (com.pico.spatial.core.math.EulerAngles doc). Negative pitch tilts the panel's face upward —
// unverified sign convention (SpaceSnake/AGENTS.md flags EulerAngles component order/sign as
// never confirmed on-device in this workspace); check the on-device screenshot before trusting.
private const val PRIMARY_PANEL_TILT_PITCH_DEGREES = -20f
// Pressing "开始" is itself a pinch — the SDK's own button-select gesture, not our charge/jump
// input. PinchChargeController watches raw hand distance independently of the button, so if the
// player's fingers are still closing/separating right as `phase` flips to Playing, that same
// physical release can be read as a completed charge and fire an instant jump. Swallow any
// release within this window of a run starting so only the player's next, deliberate pinch counts.
private const val JUMP_INPUT_GRACE_MS = 400L
// Design ask: the frog must jump within this long each round or it gets caught. A hand-tuned
// game design knob, independent of countdown.mp3's own ~12.7s runtime — a real player jump cuts
// the cue short (see onRelease below), but the app's own forced-miss catch lets it keep playing.
private const val ROUND_TIME_LIMIT_MS = 10_200L
// Forced-miss distance multiplier for the timeout catch below: PlatformSpawner never spawns a
// target farther than DifficultyCurve.maxDistanceMeters(consecutiveJumps) (it interpolates up to,
// but never reaches, that ceiling), so launching at 1.5x that ceiling always overshoots the
// target by at least half of it — e.g. at minimum, 0.5 * 0.32m = 0.16m — which is always well
// past the widest possible footprint radius (0.09m). Provably safe, not tuned by feel, and reads
// as the frog leaping too hard and overshooting the plate rather than falling meekly short.
private const val AUTO_FAIL_LAUNCH_DISTANCE_MULTIPLIER = 1.5f
// V2 alt path into the same window-crossing beat as StageBoard.WINDOW_TRIGGER_JUMPS_COMPLETED,
// for a player going for speed instead of raw combo length: land this many plates with no more
// than this much real time between the oldest and newest of them. Tracked here (not in
// StageBoard, which stays wall-clock-free and purely testable) via `recentLandingTimestampsMs`
// below, then handed to StageBoard.advance as a plain bool each landing.
private const val FAST_STREAK_JUMPS_REQUIRED = 10
private const val FAST_STREAK_WINDOW_MS = 10_000L
// The round-timeout watchdog below polls in this granularity instead of one straight
// `delay(ROUND_TIME_LIMIT_MS)`, so the exit-confirm freeze (`paused`) can hold the catch off
// indefinitely — a real, already-suspended delay() can't be un-suspended once started, so only
// time actually spent unpaused may count toward the limit.
private const val ROUND_TIMEOUT_POLL_MS = 100L
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
private const val CHARACTER_GROUND_Y = 0f

/** Walks the `ContextWrapper` chain from a Compose [LocalContext] to find the hosting
 * `ComponentActivity` — `SpatialLaunchActivity` still resolves down to `ComponentActivity` (per
 * the SDK's own Activity-customization doc: "its superclass should be ComponentActivity or a
 * subclass of it"), so `onBackPressedDispatcher` is reachable this way even though HomeStage
 * never sees the Activity directly. */
private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

/** Mirrors the design doc's "起始态" as this run's idle/ready state, not literally re-showing
 * a start page every retry — "再来一局" restarts play directly (design-contract.md decision,
 * see plan Task 10 Step 6). Only cold app launch ever shows [NotStarted]. */
private sealed class HopPhase {
    data object NotStarted : HopPhase()
    data object Playing : HopPhase()
    data class Result(val isNewRecord: Boolean, val diedInLotusPond: Boolean) : HopPhase()
}

/** Task 10 gate: the full loop with all three SpatialUI panels. `primary` (front, blocking)
 * shows start_page or result_panel; `side` (beside, non-blocking) shows hud only while
 * playing — mirrors `SpaceLianliankan/content/BoardStage.kt`'s two-attachment-slot pattern. */
@Composable
fun HomeStage() {
    val context = LocalContext.current
    // Used only to reach `onBackPressedDispatcher` below — null on a host that somehow never
    // wraps a ComponentActivity, in which case the back interception below simply can't attach
    // (same fallback behavior as before this change existed).
    val activity = remember(context) { context.findComponentActivity() }
    val scope = rememberCoroutineScope()
    // Safe at this scope even without a lexical PicoTheme wrapper here: Main.kt calls
    // `DefaultStage { PicoTheme { HomeStage() } }`, so this whole function body already runs
    // inside a PicoTheme composition. Feeds controller-trigger charge/jump input below — a no-op
    // when the player is bare-handed with no controller connected.
    val controllerHaptic = LocalHandControllerHaptic.current
    val hmd = remember { HMDTrackingProvider() }
    val anchor = remember { Entity() }
    val chargeInput = remember { PinchChargeController() }
    val indicatorBalls = remember { IndicatorBallsRenderer() }
    val sfx = remember { Sfx(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, sfx) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> sfx.pause()
                Lifecycle.Event.ON_RESUME -> sfx.resume()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val spawner = remember { PlatformSpawner(Random.Default) }
    val boardAnimator = remember { StageBoardAnimator(HopClocks.frameAligned) }
    val highScoreStore = remember { HighScoreStore(context) }
    var heightAdjustM by remember { mutableFloatStateOf(0f) }
    var runState by remember { mutableStateOf(RunState()) }
    var boardState by remember { mutableStateOf(freshBoardState(spawner)) }
    var characterController by remember { mutableStateOf<CharacterController?>(null) }
    var phase by remember { mutableStateOf<HopPhase>(HopPhase.NotStarted) }
    // Freezes the round-timeout watchdog and the charge-visual tick (both read this below) while
    // the exit-confirm panel is up — the run's only other "keep advancing while true" state, so
    // this rides alongside them rather than introducing a second, redundant pause concept; there
    // is no other pause/settings-open state anywhere else in this project to compose with instead.
    var paused by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var runStartedAtNanos by remember { mutableLongStateOf(0L) }
    // Flips true the instant a jump is committed (release, past the grace period) and back to
    // false once the next round begins — the single signal the round-timeout watchdog below
    // reads after its delay to tell "player jumped in time" from "ran out the clock".
    var jumpCommittedThisRound by remember { mutableStateOf(false) }
    // Plain mutable object, not Compose state — read/written only from within performJump, never
    // needs to trigger recomposition on its own. Holds at most FAST_STREAK_JUMPS_REQUIRED entries
    // (oldest evicted first), so `last - first` is the span of the most recent landings once full —
    // see performJump's isFastStreak check below.
    val recentLandingTimestampsMs = remember { ArrayDeque<Long>() }

    // Root-caused against a PICO app store review report ("遇到崩溃"): DefaultStage has no
    // window chrome or close button, and several distinct physical controller inputs all get
    // remapped by PICO OS into a plain KEYCODE_BACK KeyEvent (confirmed on real hardware) — left
    // un-intercepted, ComponentActivity's default handling calls Activity.finish() immediately
    // and silently kills the whole run mid-play with no crash at all. Registered once per
    // `activity` (stable for the composition's lifetime), but reads `phase`/`runState` live on
    // every fire, the same closure-over-local-state pattern PinchChargeController's `onRelease`
    // callback below already relies on.
    DisposableEffect(activity) {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (phase is HopPhase.Playing && runState.isAlive) {
                    // Freeze first, then ask — same shape as `sfx`'s own ON_PAUSE/ON_RESUME
                    // handling above, just triggered by back instead of a lifecycle event.
                    paused = true
                    sfx.pause()
                    showExitConfirm = true
                } else {
                    // Start/result screens have no live run to protect — exit like the system
                    // default would have.
                    activity?.finish()
                }
            }
        }
        activity?.onBackPressedDispatcher?.addCallback(callback)
        onDispose { callback.remove() }
    }

    fun startNewRun() {
        runState = RunState()
        val fresh = freshBoardState(spawner)
        boardState = fresh
        boardAnimator.placeInstant(fresh) // cold reset, not a landing — no slide/fade
        characterController?.apply {
            setIdlePosition(Vector3(0f, CHARACTER_GROUND_Y, 0f))
            // Snap, not turn: a cold run start has no landing transition to fold the turn into.
            faceTarget(fresh.platforms[1].lateralOffsetMeters, fresh.platforms[1].distanceFromPreviousMeters)
        }
        runStartedAtNanos = System.nanoTime()
        jumpCommittedThisRound = false
        recentLandingTimestampsMs.clear()
        phase = HopPhase.Playing
    }

    // V2: a miss in the lotus pond reads as "swam home", not "caught" — see ResultPanelContent.
    fun endRun(diedInLotusPond: Boolean) {
        val isNewRecord = highScoreStore.submitScore(runState.score)
        if (isNewRecord) sfx.play(HopSfxEvent.NEW_RECORD)
        phase = HopPhase.Result(isNewRecord, diedInLotusPond)
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
     * a clatter) — see [Sfx.playLotusLanding] / [StageBoardAnimator.playFallSound]. */
    suspend fun performJump(controller: CharacterController, flightDistance: Float, trigger: String) {
        val target = boardState.platforms[1]
        val isWindowCrossing = target.windowCrossingDistanceMeters != null
        controller.flyTo(flightDistance, target.lateralOffsetMeters, target.distanceFromPreviousMeters)
        if (isWindowCrossing) boardAnimator.retireWindowCrossing()
        val result = if (isWindowCrossing) LandingResult.SUCCESS else LandingJudge.judge(flightDistance, target)
        Log.e(
            "SpaceHopJump",
            "trigger=$trigger flightDistance=$flightDistance targetDistance=${target.distanceFromPreviousMeters} " +
                "footprintRadius=${target.footprintRadiusMeters} isWindowCrossing=$isWindowCrossing result=$result",
        )
        runState = ScoreRules.applyLanding(runState, result)
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
        recentLandingTimestampsMs.addLast(System.currentTimeMillis())
        while (recentLandingTimestampsMs.size > FAST_STREAK_JUMPS_REQUIRED) recentLandingTimestampsMs.removeFirst()
        val isFastStreak = recentLandingTimestampsMs.size == FAST_STREAK_JUMPS_REQUIRED &&
            recentLandingTimestampsMs.last() - recentLandingTimestampsMs.first() <= FAST_STREAK_WINDOW_MS
        val advanced = StageBoard.advance(
            boardState,
            spawner,
            consecutiveJumps = runState.jumpsCompleted,
            forceWindowCrossing = isFastStreak,
        )
        boardState = advanced
        jumpCommittedThisRound = false // the new round's own countdown/stream starts fresh below
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
    }

    DisposableEffect(hmd) {
        hmd.start()
        onDispose { hmd.stop() }
    }

    LaunchedEffect(hmd) {
        repeat(StageCalibration.CALIBRATION_POLLS) {
            val head = hmd.latestData.hmdPose.position
            if (head != Vector3.ZERO && StageCalibration.isPlausibleHeadHeight(head.y)) {
                heightAdjustM = head.y - StageCalibration.NOMINAL_EYE_HEIGHT_M
                return@LaunchedEffect
            }
            delay(StageCalibration.CALIBRATION_INTERVAL_MS)
        }
    }

    LaunchedEffect(heightAdjustM) {
        anchor.components[TransformComponent::class.java]
            ?.setPosition(StageCalibration.boardPosition(heightAdjustM))
    }

    DisposableEffect(Unit) {
        chargeInput.start(
            // Controller-trigger input only (bare-hand pinch has no "press start" moment worth
            // a buzz for) — a light tap so charging registers without masking the stronger jump
            // pulse below.
            onChargeStart = { hand ->
                val hapticTarget = when (hand) {
                    ChargeHand.LEFT -> HandController.Left
                    ChargeHand.RIGHT -> HandController.Right
                    null -> HandController.Unknown
                }
                controllerHaptic.feedback(HandControllerHapticType.Hover, hapticTarget)
            },
            onRelease = { cappedHeldMillis, hand ->
                val controller = characterController ?: return@start
                // `paused` gates a live exit-confirm panel the same way `isAlive` already gates a
                // finished run — a real hand release shouldn't commit a jump behind the dialog.
                if (phase !is HopPhase.Playing || !runState.isAlive || paused) return@start
                // A jump is already in flight (either the player's own, or the watchdog's forced one)
                // — don't let a second release also grab the entity mid-animation.
                if (jumpCommittedThisRound) return@start
                val sinceRunStartMs = (System.nanoTime() - runStartedAtNanos) / 1_000_000L
                if (sinceRunStartMs < JUMP_INPUT_GRACE_MS) return@start
                // Committed — the round-timeout watchdog below reads this to know a real jump beat
                // the clock. Only a genuine player jump cuts the countdown cue short; the app's own
                // forced jumps (same LaunchedEffect) deliberately leave it playing instead.
                jumpCommittedThisRound = true
                val hapticTarget = when (hand) {
                    ChargeHand.LEFT -> HandController.Left
                    ChargeHand.RIGHT -> HandController.Right
                    null -> HandController.Unknown
                }
                controllerHaptic.feedback(HandControllerHapticType.Press, hapticTarget)
                sfx.stopCountdown()
                // V2: a window-crossing target is a guaranteed-success beat, flown at its own exact
                // (compound) distance rather than whatever the player charged — see performJump.
                val target = boardState.platforms[1]
                val flightDistance = if (target.windowCrossingDistanceMeters != null) {
                    target.distanceFromPreviousMeters
                } else {
                    ChargeCurve.launchDistanceMeters(
                        cappedHeldMillis,
                        minDistance = DifficultyCurve.minDistanceMeters(runState.jumpsCompleted),
                        maxDistance = DifficultyCurve.maxDistanceMeters(runState.jumpsCompleted),
                    )
                }
                scope.launch { performJump(controller, flightDistance, trigger = "player") }
            },
        )
        onDispose { chargeInput.stop(); sfx.release() }
    }

    LaunchedEffect(Unit) {
        var wasCharging = false
        while (true) {
            delay(16)
            val state = chargeInput.chargeState.value
            // Frozen for the exit-confirm panel: don't let a real hand pinch still visibly
            // squash the frog or advance the charge cue behind the dialog while the run is held.
            if (paused) continue
            indicatorBalls.update(state)
            characterController?.let { c ->
                if (state.isCharging) c.applyChargeSquash(state.chargeFraction) else c.resetSquash()
            }
            // Edge-triggered, not level-triggered — mirrors the indicator-ball/squash feedback
            // above by staying ungated on `phase` (charging still visually reacts on the start
            // /result screens today; the sound follows the same existing behaviour).
            if (state.isCharging && !wasCharging) sfx.startCharge()
            else if (!state.isCharging && wasCharging) sfx.stopCharge()
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
    LaunchedEffect(boardState, phase) {
        if (phase !is HopPhase.Playing) return@LaunchedEffect
        // V2 design ask: the lotus-pond stage has no time limit at all — once the frog has
        // actually landed on a lotus leaf (not just while the window-crossing jump, still judged
        // from the kitchen side, is upcoming as `next`), this is just an ambient loop, not a
        // countdown-and-catch mechanism. Never returns to the kitchen stage, so once this branch
        // is taken it's taken for the rest of the run (StageBoard.advance's one-way transition).
        if (boardState.current.kind == PlatformKind.LOTUS) {
            sfx.startStream()
            return@LaunchedEffect
        }
        sfx.startCountdown()
        // Polls instead of one straight delay(ROUND_TIME_LIMIT_MS) so `paused` (the exit-confirm
        // freeze) can hold this catch off indefinitely — only time actually spent unpaused counts
        // toward the limit, so a player who takes 15 real seconds to decide on the exit dialog
        // doesn't come back to an auto-fail that ran out the clock behind it.
        var elapsedMs = 0L
        while (elapsedMs < ROUND_TIME_LIMIT_MS) {
            delay(ROUND_TIMEOUT_POLL_MS)
            if (!paused) elapsedMs += ROUND_TIMEOUT_POLL_MS
        }
        if (jumpCommittedThisRound) return@LaunchedEffect
        val controller = characterController ?: return@LaunchedEffect
        // Committed, same as a real release — blocks both a last-instant player pinch (below)
        // and this same watchdog re-firing. No sfx.stopCountdown() here, unlike onRelease — this
        // is the app's own doing, not the player's, so the countdown cue keeps playing through it.
        jumpCommittedThisRound = true
        val target = boardState.platforms[1]
        val isWindowCrossing = target.windowCrossingDistanceMeters != null
        if (!isWindowCrossing) {
            // This one *is* a guaranteed miss — set isAlive=false (mirrors ScoreRules.applyLanding's
            // MISS branch) before it starts animating, not after: flyTo below takes ~420ms, and
            // it's this flag (via onRelease's `!runState.isAlive` guard) that stops a last-instant
            // real release from also fighting this coroutine over the entity's transform. A window
            // crossing never sets this — the run is meant to keep going.
            runState = runState.copy(isAlive = false)
        }
        // The app plays out a real jump attempt instead of just despawning the frog in place —
        // same fly/judge/advance-or-fall path performJump gives a player release, just with a
        // distance engineered to always overshoot (AUTO_FAIL_LAUNCH_DISTANCE_MULTIPLIER), unless
        // the target is the V2 window crossing, which is flown at its own exact (compound)
        // distance and always succeeds.
        val flightDistance = if (isWindowCrossing) {
            target.distanceFromPreviousMeters
        } else {
            DifficultyCurve.maxDistanceMeters(runState.jumpsCompleted) * AUTO_FAIL_LAUNCH_DISTANCE_MULTIPLIER
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
            performJump(controller, flightDistance, trigger = if (isWindowCrossing) "auto-window" else "auto-fail")
        }
    }

    SpatialView(
        initial = { content, attachments ->
            anchor.components[TransformComponent::class.java]
                ?.setPosition(StageCalibration.boardPosition(heightAdjustM))
            content.addEntity(anchor)
            indicatorBalls.build(content)

            boardAnimator.build(anchor)
            boardAnimator.placeInstant(boardState)

            characterController = CharacterController.load(anchor, characterRadiusMeters = 0.05f, clock = HopClocks.frameAligned).also {
                it.setIdlePosition(Vector3(0f, CHARACTER_GROUND_Y, 0f))
                it.faceTarget(
                    boardState.platforms[1].lateralOffsetMeters,
                    boardState.platforms[1].distanceFromPreviousMeters,
                )
            }

            attachments.entity(PRIMARY_PANEL)?.let {
                // Floats in front of the stage, between the player and the platforms — NOT
                // at the anchor origin, which is where the current platform sits (+Z here is
                // toward the viewer, since -Z is established elsewhere as "away from player").
                // Coincident with the platform was a confirmed Gate B round 3 Blocker: the
                // panel and the platform/character z-fought and the panel's text never
                // rendered as a result.
                // +Z is toward the viewer here, so pulling the panel this far back off the anchor
                // leaves it PRIMARY_PANEL_USER_DISTANCE_M from the player.
                val panelLocalZ = StageCalibration.BOARD_DISTANCE_M - PRIMARY_PANEL_USER_DISTANCE_M
                Log.e(
                    "SpaceHopPanel",
                    "primary panel: userDistance=$PRIMARY_PANEL_USER_DISTANCE_M " +
                        "boardDistance=${StageCalibration.BOARD_DISTANCE_M} localZ=$panelLocalZ",
                )
                it.components[TransformComponent::class.java]?.apply {
                    setPosition(Vector3(0f, 0f, panelLocalZ))
                    setEulerAngles(EulerAngles(pitch = PRIMARY_PANEL_TILT_PITCH_DEGREES, yaw = 0f, roll = 0f))
                }
                anchor.addChild(it)
            }
            attachments.entity(SIDE_PANEL)?.let {
                it.components[TransformComponent::class.java]?.setPosition(Vector3(SIDE_OFFSET_M, 0f, 0f))
                anchor.addChild(it)
            }
        },
        attachments = {
            AttachmentPanel(PRIMARY_PANEL) {
                PicoTheme {
                    // Crossfades between start/result/playing instead of a hard cut — keyed on
                    // the phase itself, so NotStarted -> Result -> Playing each get their own
                    // transition rather than only firing once.
                    AnimatedContent(targetState = phase, transitionSpec = { fadeIn() togetherWith fadeOut() }) { p ->
                        when (p) {
                            is HopPhase.NotStarted -> StartPageContent(
                                bestScore = highScoreStore.bestScore(),
                                onStart = { startNewRun() },
                            )
                            is HopPhase.Result -> ResultPanelContent(
                                score = runState.score,
                                bestCombo = runState.bestComboStreak,
                                bestScore = highScoreStore.bestScore() ?: 0,
                                isNewRecord = p.isNewRecord,
                                diedInLotusPond = p.diedInLotusPond,
                                onRetry = { startNewRun() },
                            )
                            is HopPhase.Playing -> AnimatedVisibility(
                                visible = showExitConfirm,
                                enter = fadeIn(),
                                exit = fadeOut(),
                            ) {
                                ExitConfirmContent(
                                    onCancel = {
                                        showExitConfirm = false
                                        paused = false
                                        sfx.resume()
                                    },
                                    onExit = { activity?.finish() },
                                )
                            }
                        }
                    }
                }
            }
            AttachmentPanel(SIDE_PANEL) {
                PicoTheme {
                    AnimatedVisibility(visible = phase is HopPhase.Playing, enter = fadeIn(), exit = fadeOut()) {
                        HudContent(score = runState.score, comboStreak = runState.comboStreak)
                    }
                }
            }
        },
    )
}

private fun freshBoardState(spawner: PlatformSpawner) =
    tech.illusion.spacehop.game.StageBoardState(
        platforms = listOf(
            tech.illusion.spacehop.game.PlatformSpec(0, 0f, 0.09f, 0.09f * 0.35f, 0f),
            spawner.spawnNext(1, 0),
        ),
        current = tech.illusion.spacehop.game.PlatformSpec(0, 0f, 0.09f, 0.09f * 0.35f, 0f),
    )
