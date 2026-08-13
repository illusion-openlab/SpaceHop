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
import tech.illusion.spacehop.game.ChargeCurve
import tech.illusion.spacehop.game.DifficultyCurve
import tech.illusion.spacehop.game.LandingJudge
import tech.illusion.spacehop.game.LandingResult
import tech.illusion.spacehop.game.PlatformSpawner
import tech.illusion.spacehop.game.RunState
import tech.illusion.spacehop.game.ScoreRules

private const val TAG = "SpaceHop"

/**
 * Task 7 gate: a single charge → fly → land/fall loop, no infinite scroll yet (that's
 * Task 8) and no panels yet (Task 10). Landing on the (fixed) next platform just leaves the
 * character there; a miss falls and stays fallen — there is no restart until Task 10's
 * result panel exists.
 */
@Composable
fun HomeStage() {
    val hmd = remember { HMDTrackingProvider() }
    val mats = remember { StageMaterials() }
    val anchor = remember { Entity() }
    val indicatorBalls = remember { IndicatorBallsRenderer() }
    var heightAdjustM by remember { mutableFloatStateOf(0f) }
    var runState by remember { mutableStateOf(RunState()) }
    var characterController by remember { mutableStateOf<CharacterController?>(null) }
    var currentPlatform by remember {
        mutableStateOf(PlatformSpawner().spawnNext(index = 1, consecutiveJumps = 0))
    }
    // Built once at a nominal 1m radius and resized via setScaleVector — never recreated.
    // This is deliberate: there is no verified "remove a child entity" API in this workspace
    // (only `SpatialViewContent.removeEntity` for top-level entities), so Task 8's infinite
    // scroll reuses these two entities forever instead of destroying/recreating them.
    var currentPlatformEntity by remember { mutableStateOf<Entity?>(null) }
    var nextPlatformEntity by remember { mutableStateOf<Entity?>(null) }

    DisposableEffect(hmd) {
        val result = hmd.start()
        Log.e(TAG, "HMDTrackingProvider.start() -> $result")
        onDispose { hmd.stop() }
    }

    LaunchedEffect(hmd) {
        repeat(StageCalibration.CALIBRATION_POLLS) {
            val head = hmd.latestData.hmdPose.position
            // Not just "!= ZERO" — see Task 6 Step 2's StageCalibration.isPlausibleHeadHeight:
            // a non-zero-but-implausible reading must not be trusted either, or the board ends
            // up too high or too low.
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
            val launchDistance = ChargeCurve.launchDistanceMeters(
                cappedHeldMillis,
                minDistance = DifficultyCurve.minDistanceMeters(runState.jumpsCompleted),
                maxDistance = DifficultyCurve.maxDistanceMeters(runState.jumpsCompleted),
            )
            CoroutineScope(Dispatchers.Main).launch {
                controller.flyTo(launchDistance) { }
                val result = LandingJudge.judge(launchDistance, currentPlatform)
                runState = ScoreRules.applyLanding(runState, result)
                when (result) {
                    LandingResult.MISS -> controller.fall()
                    else -> Unit
                }
            }
        })
        onDispose { PinchChargeController.stop() }
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

            // Nominal 1m-radius mesh, shared by both platform entities — actual footprint size
            // comes entirely from TransformComponent.setScaleVector (X/Z only, Y stays 1 so
            // platform height never changes). Task 8 relies on being able to resize these two
            // entities in place; building them at a fixed nominal radius here is what makes
            // that possible without ever creating a second mesh.
            val nominalPlatformMesh = StageMeshes.platformMesh(radiusMeters = 1f)

            val current = ModelEntity(nominalPlatformMesh, mats.newPlatformMaterial())
            current.components[TransformComponent::class.java]?.apply {
                setPosition(Vector3(0f, 0f, 0f))
                setScaleVector(Vector3(0.09f, 1f, 0.09f))
            }
            anchor.addChild(current)
            currentPlatformEntity = current

            val next = ModelEntity(nominalPlatformMesh, mats.newPlatformMaterial())
            next.components[TransformComponent::class.java]?.apply {
                setPosition(Vector3(currentPlatform.lateralOffsetMeters, 0f, -currentPlatform.distanceFromPreviousMeters))
                setScaleVector(Vector3(currentPlatform.footprintRadiusMeters, 1f, currentPlatform.footprintRadiusMeters))
            }
            anchor.addChild(next)
            nextPlatformEntity = next

            val charMesh = StageMeshes.characterMesh(radiusMeters = 0.05f)
            val character = ModelEntity(charMesh, mats.character)
            anchor.addChild(character)
            characterController = CharacterController(character).also {
                it.setIdlePosition(Vector3(0f, 0.05f, 0f))
            }
        },
    )
}
