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
import kotlinx.coroutines.delay
import tech.illusion.spacehop.game.PlatformSpawner

private const val TAG = "SpaceHop"

/**
 * First-week static gate: current + next platform + a placeholder character, no gameplay
 * yet. This exists purely so Gate B round 1 can judge whether the stage placement (height,
 * distance) keeps both platforms inside the headset's FOV — see design doc §5/§6.
 */
@Composable
fun HomeStage() {
    val hmd = remember { HMDTrackingProvider() }
    val mats = remember { StageMaterials() }
    val anchor = remember { Entity() }
    var heightAdjustM by remember { mutableFloatStateOf(0f) }

    val spawner = remember { PlatformSpawner() }
    val nextPlatform = remember { spawner.spawnNext(index = 1, consecutiveJumps = 0) }

    var currentPlatformEntity by remember { mutableStateOf<Entity?>(null) }
    var nextPlatformEntity by remember { mutableStateOf<Entity?>(null) }
    var characterEntity by remember { mutableStateOf<Entity?>(null) }

    DisposableEffect(hmd) {
        val result = hmd.start()
        Log.e(TAG, "HMDTrackingProvider.start() -> $result")
        onDispose { hmd.stop() }
    }

    LaunchedEffect(hmd) {
        repeat(StageCalibration.CALIBRATION_POLLS) {
            val head = hmd.latestData.hmdPose.position
            // Not just "!= ZERO": a non-zero-but-implausible reading (unconverged tracking, a
            // room/floor calibration error) must not be trusted either, or the board ends up
            // too high or too low (user feedback, 2026-08-13) — see StageCalibration.kt Step 2.
            if (head != Vector3.ZERO && StageCalibration.isPlausibleHeadHeight(head.y)) {
                heightAdjustM = head.y - StageCalibration.NOMINAL_EYE_HEIGHT_M
                Log.e(TAG, "head height ${head.y}, board heightAdjust $heightAdjustM")
                return@LaunchedEffect
            }
            delay(StageCalibration.CALIBRATION_INTERVAL_MS)
        }
        Log.e(TAG, "head pose never reported; keeping default board position")
    }

    LaunchedEffect(heightAdjustM) {
        anchor.components[TransformComponent::class.java]
            ?.setPosition(StageCalibration.boardPosition(heightAdjustM))
    }

    SpatialView(
        initial = { content, _ ->
            anchor.components[TransformComponent::class.java]
                ?.setPosition(StageCalibration.boardPosition(heightAdjustM))
            content.addEntity(anchor)

            val currentMesh = StageMeshes.platformMesh(radiusMeters = 0.09f)
            val current = ModelEntity(currentMesh, mats.newPlatformMaterial())
            current.components[TransformComponent::class.java]?.setPosition(Vector3(0f, 0f, 0f))
            anchor.addChild(current)
            currentPlatformEntity = current

            val nextMesh = StageMeshes.platformMesh(radiusMeters = nextPlatform.footprintRadiusMeters)
            val next = ModelEntity(nextMesh, mats.newPlatformMaterial())
            next.components[TransformComponent::class.java]?.setPosition(
                Vector3(nextPlatform.lateralOffsetMeters, 0f, -nextPlatform.distanceFromPreviousMeters),
            )
            anchor.addChild(next)
            nextPlatformEntity = next

            val charMesh = StageMeshes.characterMesh(radiusMeters = 0.05f)
            val character = ModelEntity(charMesh, mats.character)
            character.components[TransformComponent::class.java]?.setPosition(Vector3(0f, 0.05f, 0f))
            anchor.addChild(character)
            characterEntity = character
        },
    )
}
