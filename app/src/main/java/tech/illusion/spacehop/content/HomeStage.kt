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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.platform.HandController
import com.pico.spatial.ui.platform.LocalHandControllerHaptic
import kotlinx.coroutines.delay
import tech.illusion.spacehop.game.HopPhase
import tech.illusion.spacehop.game.HopSession

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

/** Task 10 gate: the full loop with all three SpatialUI panels. `primary` (front, blocking)
 * shows start_page or result_panel; `side` (beside, non-blocking) shows hud only while
 * playing — mirrors `SpaceLianliankan/content/BoardStage.kt`'s two-attachment-slot pattern.
 *
 * 沉浸态这一个容器的壳：输入源用捏合/扳机，帧钟用 vsync 对齐的 [HopClocks.frameAligned]，
 * 加上指尖球、HMD 身高标定、退出确认框和面板摆位。玩法本身一行都不在这里——全部在
 * [rememberHopRun] 里，与窗口壳共用同一份。 */
@Composable
fun HomeStage() {
    val context = LocalContext.current
    // Used only to reach `onBackPressedDispatcher` below — null on a host that somehow never
    // wraps a ComponentActivity, in which case the back interception below simply can't attach
    // (same fallback behavior as before this change existed).
    val activity = remember(context) { context.findComponentActivity() }
    // Safe at this scope even without a lexical PicoTheme wrapper here: Main.kt calls
    // `DefaultStage { PicoTheme { HomeStage() } }`, so this whole function body already runs
    // inside a PicoTheme composition. Feeds controller-trigger charge/jump input below — a no-op
    // when the player is bare-handed with no controller connected.
    val controllerHaptic = LocalHandControllerHaptic.current
    val hmd = remember { HMDTrackingProvider() }
    val anchor = remember { Entity() }
    val chargeInput = remember { PinchChargeController() }
    val indicatorBalls = remember { IndicatorBallsRenderer() }
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
    var heightAdjustM by remember { mutableFloatStateOf(0f) }
    // Immersive-only: the window shell has no back-press exit dialog of its own, so this stays a
    // local `remember` rather than moving into HopSession alongside `paused`.
    var showExitConfirm by remember { mutableStateOf(false) }

    // Root-caused against a PICO app store review report ("遇到崩溃"): DefaultStage has no
    // window chrome or close button, and several distinct physical controller inputs all get
    // remapped by PICO OS into a plain KEYCODE_BACK KeyEvent (confirmed on real hardware) — left
    // un-intercepted, ComponentActivity's default handling calls Activity.finish() immediately
    // and silently kills the whole run mid-play with no crash at all. Registered once per
    // `activity` (stable for the composition's lifetime), but reads `phase`/`runState` live on
    // every fire — off HopSession now rather than off captured composition locals, which is the
    // same read-it-at-fire-time pattern rememberHopRun's `onRelease` callback also relies on.
    DisposableEffect(activity) {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (HopSession.phase is HopPhase.Playing && HopSession.runState.isAlive) {
                    // Freeze first, then ask — same shape as `sfx`'s own ON_PAUSE/ON_RESUME
                    // handling (now in rememberHopRun), just triggered by back instead of a
                    // lifecycle event.
                    HopSession.paused = true
                    run.sfx.pause()
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

    // Immersive-only half of what used to be one charge-visual tick: the fingertip balls follow
    // hand-tracking data, which a WindowContainer never receives, so they stay with this shell
    // while the squash/charge-sound half lives in rememberHopRun. Same 16ms cadence and the same
    // `paused` freeze as before — don't let a real hand pinch still visibly advance the charge
    // cue behind the exit-confirm dialog while the run is held.
    LaunchedEffect(Unit) {
        while (true) {
            delay(16)
            val state = chargeInput.chargeState.value
            if (HopSession.paused) continue
            indicatorBalls.update(state)
        }
    }

    SpatialView(
        initial = { content, attachments ->
            anchor.components[TransformComponent::class.java]
                ?.setPosition(StageCalibration.boardPosition(heightAdjustM))
            content.addEntity(anchor)
            indicatorBalls.build(content)

            run.buildScene(anchor)

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
                    AnimatedContent(
                        targetState = HopSession.phase,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                    ) { p ->
                        when (p) {
                            is HopPhase.NotStarted -> StartPageContent(
                                bestScore = run.bestScore(),
                                onStart = { run.startNewRun() },
                            )
                            is HopPhase.Result -> ResultPanelContent(
                                score = HopSession.runState.score,
                                bestCombo = HopSession.runState.bestComboStreak,
                                bestScore = run.bestScore() ?: 0,
                                isNewRecord = p.isNewRecord,
                                diedInLotusPond = p.diedInLotusPond,
                                onRetry = { run.startNewRun() },
                            )
                            is HopPhase.Playing -> AnimatedVisibility(
                                visible = showExitConfirm,
                                enter = fadeIn(),
                                exit = fadeOut(),
                            ) {
                                ExitConfirmContent(
                                    onCancel = {
                                        showExitConfirm = false
                                        HopSession.paused = false
                                        run.sfx.resume()
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
                    AnimatedVisibility(
                        visible = HopSession.phase is HopPhase.Playing,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        HudContent(score = HopSession.runState.score, comboStreak = HopSession.runState.comboStreak)
                    }
                }
            }
        },
    )
}
