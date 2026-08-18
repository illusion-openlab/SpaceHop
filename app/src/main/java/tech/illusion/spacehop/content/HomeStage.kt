package tech.illusion.spacehop.content

import android.util.Log
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
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.content.SpatialView
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import tech.illusion.spacehop.game.ChargeCurve
import tech.illusion.spacehop.game.DifficultyCurve
import tech.illusion.spacehop.game.HighScoreStore
import tech.illusion.spacehop.game.LandingJudge
import tech.illusion.spacehop.game.LandingResult
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
// Design ask: the frog must jump within this long each round or it gets caught. Kept independent
// of countdown.mp3's own ~12.7s runtime (StageBoardAnimator-style constants are hand-tuned game
// design knobs here, not derived from an asset's incidental length).
private const val ROUND_TIME_LIMIT_MS = 13_000L
// Forced-miss distance for the timeout catch below: short enough that LandingJudge.judge always
// reads it as MISS no matter the current difficulty stage. Provably safe, not tuned by feel —
// DifficultyCurve's footprint radius never exceeds 0.09m while its target distance never drops
// below 0.18m, so any offset from a jump this short is guaranteed to clear the widest footprint.
private const val AUTO_FAIL_LAUNCH_DISTANCE_M = 0.05f

/** Mirrors the design doc's "起始态" as this run's idle/ready state, not literally re-showing
 * a start page every retry — "再来一局" restarts play directly (design-contract.md decision,
 * see plan Task 10 Step 6). Only cold app launch ever shows [NotStarted]. */
private sealed class HopPhase {
    data object NotStarted : HopPhase()
    data object Playing : HopPhase()
    data class Result(val isNewRecord: Boolean) : HopPhase()
}

/** Task 10 gate: the full loop with all three SpatialUI panels. `primary` (front, blocking)
 * shows start_page or result_panel; `side` (beside, non-blocking) shows hud only while
 * playing — mirrors `SpaceLianliankan/content/BoardStage.kt`'s two-attachment-slot pattern. */
@Composable
fun HomeStage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hmd = remember { HMDTrackingProvider() }
    val anchor = remember { Entity() }
    val indicatorBalls = remember { IndicatorBallsRenderer() }
    val trail = remember { TrailRenderer() }
    val sfx = remember { Sfx(context) }
    val spawner = remember { PlatformSpawner(Random.Default) }
    val boardAnimator = remember { StageBoardAnimator() }
    val highScoreStore = remember { HighScoreStore(context) }
    var heightAdjustM by remember { mutableFloatStateOf(0f) }
    var runState by remember { mutableStateOf(RunState()) }
    var boardState by remember { mutableStateOf(freshBoardState(spawner)) }
    var characterController by remember { mutableStateOf<CharacterController?>(null) }
    var phase by remember { mutableStateOf<HopPhase>(HopPhase.NotStarted) }
    var runStartedAtNanos by remember { mutableLongStateOf(0L) }
    // Flips true the instant a jump is committed (release, past the grace period) and back to
    // false once the next round begins — the single signal the round-timeout watchdog below
    // reads after its delay to tell "player jumped in time" from "ran out the clock".
    var jumpCommittedThisRound by remember { mutableStateOf(false) }

    fun startNewRun() {
        runState = RunState()
        val fresh = freshBoardState(spawner)
        boardState = fresh
        boardAnimator.placeInstant(fresh) // cold reset, not a landing — no slide/fade
        characterController?.apply {
            setIdlePosition(Vector3(0f, 0.05f, 0f))
            // Snap, not turn: a cold run start has no landing transition to fold the turn into.
            faceTarget(fresh.platforms[1].lateralOffsetMeters, fresh.platforms[1].distanceFromPreviousMeters)
        }
        runStartedAtNanos = System.nanoTime()
        jumpCommittedThisRound = false
        phase = HopPhase.Playing
    }

    fun endRun() {
        val isNewRecord = highScoreStore.submitScore(runState.score)
        if (isNewRecord) sfx.play(HopSfxEvent.NEW_RECORD)
        phase = HopPhase.Result(isNewRecord)
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
        PinchChargeController.start(onRelease = { cappedHeldMillis ->
            val controller = characterController ?: return@start
            if (phase !is HopPhase.Playing || !runState.isAlive) return@start
            val sinceRunStartMs = (System.nanoTime() - runStartedAtNanos) / 1_000_000L
            if (sinceRunStartMs < JUMP_INPUT_GRACE_MS) return@start
            // Committed: stop this round's countdown tick right away — the jump stinger itself
            // plays from the frog (CharacterController.flyTo), not here.
            jumpCommittedThisRound = true
            sfx.stopCountdown()
            trail.clear()
            val launchDistance = ChargeCurve.launchDistanceMeters(
                cappedHeldMillis,
                minDistance = DifficultyCurve.minDistanceMeters(runState.jumpsCompleted),
                maxDistance = DifficultyCurve.maxDistanceMeters(runState.jumpsCompleted),
            )
            scope.launch {
                controller.flyTo(launchDistance) { local -> trail.sample(local) }
                // Judge against the platform ahead (`platforms[1]`), not `current` — `current`
                // is the platform the character is standing on (distance 0 from itself), which
                // is what StageBoardAnimator renders at the anchor; the actual jump target is
                // rendered ahead at `platforms[1]`'s distance (StageBoardAnimator.kt PlacedTransform).
                val target = boardState.platforms[1]
                val result = LandingJudge.judge(launchDistance, target)
                Log.e(
                    "SpaceHopJump",
                    "heldMs=$cappedHeldMillis launchDistance=$launchDistance " +
                        "targetDistance=${target.distanceFromPreviousMeters} " +
                        "footprintRadius=${target.footprintRadiusMeters} result=$result",
                )
                runState = ScoreRules.applyLanding(runState, result)
                if (result == LandingResult.MISS) {
                    controller.fall() // plays its own fall stinger from the frog
                    endRun()
                    return@launch
                }
                sfx.play(if (result == LandingResult.PERFECT) HopSfxEvent.LANDED_PERFECT else HopSfxEvent.LANDED_SUCCESS)
                val advanced = StageBoard.advance(boardState, spawner, consecutiveJumps = runState.jumpsCompleted)
                boardState = advanced
                jumpCommittedThisRound = false // the new round's own countdown starts fresh below
                // Recentre the character over the same window as the promoted platform's slide
                // (StageBoardAnimator.animateAdvance) so they arrive at the anchor together,
                // instead of the character snapping there only after the platform finishes. The
                // turn toward the newly-spawned next platform rides along in that same window.
                coroutineScope {
                    launch {
                        controller.recenterTo(
                            target = Vector3(0f, 0.05f, 0f),
                            nextLateralOffsetMeters = advanced.platforms[1].lateralOffsetMeters,
                            nextForwardDistanceMeters = advanced.platforms[1].distanceFromPreviousMeters,
                            durationMs = ADVANCE_PHASE_MS,
                        )
                    }
                    boardAnimator.animateAdvance(advanced)
                }
            }
        })
        onDispose { PinchChargeController.stop(); sfx.release() }
    }

    LaunchedEffect(Unit) {
        var wasCharging = false
        while (true) {
            delay(16)
            val state = PinchChargeController.chargeState.value
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
        sfx.startCountdown()
        delay(ROUND_TIME_LIMIT_MS)
        if (jumpCommittedThisRound) return@LaunchedEffect
        val controller = characterController ?: return@LaunchedEffect
        sfx.stopCountdown()
        // Set isAlive=false (mirrors ScoreRules.applyLanding's MISS branch) before the app's own
        // forced-miss jump starts animating, not after — flyTo below takes ~420ms, and it's this
        // flag (via onRelease's `!runState.isAlive` guard) that stops a last-instant real release
        // from also fighting this coroutine over the entity's transform.
        runState = runState.copy(isAlive = false)
        trail.clear()
        // The app plays out a real (too-short) jump attempt instead of just despawning the frog
        // in place — same fly/fall path a player-triggered miss takes, just with a distance
        // engineered to always fall short (see AUTO_FAIL_LAUNCH_DISTANCE_M).
        controller.flyTo(AUTO_FAIL_LAUNCH_DISTANCE_M) { local -> trail.sample(local) }
        controller.fall() // plays its own fall stinger from the frog
        endRun()
    }

    SpatialView(
        initial = { content, attachments ->
            anchor.components[TransformComponent::class.java]
                ?.setPosition(StageCalibration.boardPosition(heightAdjustM))
            content.addEntity(anchor)
            indicatorBalls.build(content)
            trail.build(content)

            boardAnimator.build(anchor)
            boardAnimator.placeInstant(boardState)

            characterController = CharacterController.load(anchor, characterRadiusMeters = 0.05f).also {
                it.setIdlePosition(Vector3(0f, 0.05f, 0f))
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
                                onRetry = { startNewRun() },
                            )
                            is HopPhase.Playing -> Unit
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
