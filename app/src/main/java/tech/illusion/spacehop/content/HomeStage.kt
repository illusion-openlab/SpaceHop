package tech.illusion.spacehop.content

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import com.pico.spatial.ui.foundation.content.SpatialView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import tech.illusion.spacehop.game.ChargeCurve
import tech.illusion.spacehop.game.DifficultyCurve
import tech.illusion.spacehop.game.LandingJudge
import tech.illusion.spacehop.game.LandingResult
import tech.illusion.spacehop.game.PlatformSpawner
import tech.illusion.spacehop.game.PlatformSpec
import tech.illusion.spacehop.game.RunState
import tech.illusion.spacehop.game.ScoreRules
import tech.illusion.spacehop.game.StageBoard
import tech.illusion.spacehop.game.StageBoardState

private const val TAG = "SpaceHop"
private const val START_PLATFORM_RADIUS_M = 0.09f

private fun freshBoardState(spawner: PlatformSpawner): StageBoardState {
    val start = PlatformSpec(0, 0f, START_PLATFORM_RADIUS_M, START_PLATFORM_RADIUS_M * 0.35f, 0f)
    return StageBoardState(platforms = listOf(start, spawner.spawnNext(1, 0)), current = start)
}

/** Task 8 gate: the infinite loop. No panels yet (Task 10) — a miss just falls and the run
 * stays dead until the process restarts, since there is no result panel to retry from. */
@Composable
fun HomeStage() {
    val hmd = remember { HMDTrackingProvider() }
    val mats = remember { StageMaterials() }
    val anchor = remember { Entity() }
    val indicatorBalls = remember { IndicatorBallsRenderer() }
    val trail = remember { TrailRenderer() }
    val sfx = remember { Sfx() }
    val spawner = remember { PlatformSpawner(Random.Default) }
    val boardAnimator = remember { StageBoardAnimator(mats) }
    var heightAdjustM by remember { mutableFloatStateOf(0f) }
    var runState by remember { mutableStateOf(RunState()) }
    var boardState by remember { mutableStateOf(freshBoardState(spawner)) }
    var characterController by remember { mutableStateOf<CharacterController?>(null) }

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
            if (!runState.isAlive) return@start
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
        initial = { content, _ ->
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
        },
    )
}
