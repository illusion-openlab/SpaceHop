package tech.illusion.spacehop.content

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

private const val TAG = "SpaceHop"
private const val PRIMARY_PANEL = "primary"
private const val SIDE_PANEL = "side"
private const val SIDE_OFFSET_M = 0.32f
private const val PRIMARY_PANEL_FORWARD_OFFSET_M = 0.3f

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
    val hmd = remember { HMDTrackingProvider() }
    val mats = remember { StageMaterials() }
    val anchor = remember { Entity() }
    val indicatorBalls = remember { IndicatorBallsRenderer() }
    val trail = remember { TrailRenderer() }
    val sfx = remember { Sfx() }
    val spawner = remember { PlatformSpawner(Random.Default) }
    val boardAnimator = remember { StageBoardAnimator(mats) }
    val highScoreStore = remember { HighScoreStore(context) }
    var heightAdjustM by remember { mutableFloatStateOf(0f) }
    var runState by remember { mutableStateOf(RunState()) }
    var boardState by remember { mutableStateOf(freshBoardState(spawner)) }
    var characterController by remember { mutableStateOf<CharacterController?>(null) }
    var phase by remember { mutableStateOf<HopPhase>(HopPhase.NotStarted) }

    fun startNewRun() {
        runState = RunState()
        boardState = freshBoardState(spawner)
        boardAnimator.placeInstant(boardState) // cold reset, not a landing — no slide/fade
        characterController?.setIdlePosition(Vector3(0f, 0.05f, 0f))
        phase = HopPhase.Playing
    }

    fun endRun() {
        val isNewRecord = highScoreStore.submitScore(runState.score)
        if (isNewRecord) sfx.play(HopSfxEvent.NEW_RECORD)
        phase = HopPhase.Result(isNewRecord)
    }

    DisposableEffect(hmd) {
        val result = hmd.start()
        Log.e(TAG, "HMDTrackingProvider.start() -> $result")
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
            trail.clear()
            sfx.play(HopSfxEvent.LAUNCH)
            val launchDistance = ChargeCurve.launchDistanceMeters(
                cappedHeldMillis,
                minDistance = DifficultyCurve.minDistanceMeters(runState.jumpsCompleted),
                maxDistance = DifficultyCurve.maxDistanceMeters(runState.jumpsCompleted),
            )
            CoroutineScope(Dispatchers.Main).launch {
                controller.flyTo(launchDistance) { local -> trail.sample(local) }
                val result = LandingJudge.judge(launchDistance, boardState.current)
                runState = ScoreRules.applyLanding(runState, result)
                if (result == LandingResult.MISS) {
                    sfx.play(HopSfxEvent.FELL)
                    controller.fall()
                    endRun()
                    return@launch
                }
                sfx.play(if (result == LandingResult.PERFECT) HopSfxEvent.LANDED_PERFECT else HopSfxEvent.LANDED_SUCCESS)
                boardState = StageBoard.advance(boardState, spawner, consecutiveJumps = runState.jumpsCompleted)
                boardAnimator.animateAdvance(boardState)
                controller.setIdlePosition(Vector3(0f, 0.05f, 0f))
            }
        })
        onDispose { PinchChargeController.stop(); sfx.release() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(16)
            val state = PinchChargeController.chargeState.value
            indicatorBalls.update(state)
            characterController?.let { c ->
                if (state.isCharging) c.applyChargeSquash(state.chargeFraction) else c.resetSquash()
            }
        }
    }

    SpatialView(
        initial = { content, attachments ->
            anchor.components[TransformComponent::class.java]
                ?.setPosition(StageCalibration.boardPosition(heightAdjustM))
            content.addEntity(anchor)
            indicatorBalls.build(content)
            trail.build(content)

            val nominalPlatformMesh = StageMeshes.platformMesh(radiusMeters = 1f)
            boardAnimator.build(anchor, nominalPlatformMesh)
            boardAnimator.placeInstant(boardState)

            val charMesh = StageMeshes.characterMesh(radiusMeters = 0.05f)
            val character = ModelEntity(charMesh, mats.character)
            anchor.addChild(character)
            characterController = CharacterController(character).also {
                it.setIdlePosition(Vector3(0f, 0.05f, 0f))
            }

            attachments.entity(PRIMARY_PANEL)?.let {
                // Floats in front of the stage, between the player and the platforms — NOT
                // at the anchor origin, which is where the current platform sits (+Z here is
                // toward the viewer, since -Z is established elsewhere as "away from player").
                // Coincident with the platform was a confirmed Gate B round 3 Blocker: the
                // panel and the platform/character z-fought and the panel's text never
                // rendered as a result.
                it.components[TransformComponent::class.java]?.setPosition(Vector3(0f, 0f, PRIMARY_PANEL_FORWARD_OFFSET_M))
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
                    when (val p = phase) {
                        is HopPhase.NotStarted -> StartPageContent(
                            bestScore = highScoreStore.bestScore(),
                            onStart = { startNewRun() },
                        )
                        is HopPhase.Result -> ResultPanelContent(
                            score = runState.score,
                            bestCombo = runState.comboStreak,
                            bestScore = highScoreStore.bestScore() ?: 0,
                            isNewRecord = p.isNewRecord,
                            onRetry = { startNewRun() },
                        )
                        is HopPhase.Playing -> Unit
                    }
                }
            }
            AttachmentPanel(SIDE_PANEL) {
                PicoTheme {
                    if (phase is HopPhase.Playing) {
                        Column {
                            HudContent(score = runState.score, comboStreak = runState.comboStreak)
                            Button(
                                onClick = { endRun() },
                                colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.error),
                                modifier = Modifier.padding(top = 10.dp),
                            ) {
                                Text(text = "DEBUG 强制落空", style = PicoTheme.typography.labelMedium)
                            }
                        }
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
