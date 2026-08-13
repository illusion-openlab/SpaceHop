package tech.illusion.spacehop.content

import androidx.compose.runtime.withFrameNanos
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Vector3

enum class CharacterAnim { IDLE, CHARGING, FLYING, LANDED_PERFECT, LANDED_SUCCESS, FALLING }

private const val FLIGHT_DURATION_MS = 420L
private const val ARC_HEIGHT_M = 0.12f
private const val FALL_DURATION_MS = 500L
private const val FALL_DROP_M = 0.6f

/** Owns the character entity's transform across its animation states. All timing is
 * simplified easing, not real projectile physics (design doc §2.4: "物理感 vs 节奏感"
 * risk — favours a snappy feel over ballistic accuracy). */
class CharacterController(private val entity: ModelEntity) {

    private var basePosition = Vector3.ZERO

    fun setIdlePosition(position: Vector3) {
        basePosition = position
        entity.components[TransformComponent::class.java]?.setPosition(position)
        entity.components[TransformComponent::class.java]?.setScaleVector(Vector3(1f, 1f, 1f))
    }

    /** [fraction] 0f..1f charge progress — squashes Y down, bulges X/Z up slightly. */
    fun applyChargeSquash(fraction: Float) {
        val squashY = 1f - 0.35f * fraction
        val bulgeXZ = 1f + 0.15f * fraction
        entity.components[TransformComponent::class.java]?.setScaleVector(Vector3(bulgeXZ, squashY, bulgeXZ))
    }

    fun resetSquash() {
        entity.components[TransformComponent::class.java]?.setScaleVector(Vector3(1f, 1f, 1f))
    }

    /** Animates a parabolic arc from [basePosition] to the point [launchDistanceMeters] ahead
     * (auto-aimed straight down -Z, per design doc §2.1), calling [onFrame] with the visited
     * local offset each frame so callers (e.g. `TrailRenderer`) can sample it, then resolves
     * with the actual landing local-Z offset once the flight completes. */
    suspend fun flyTo(launchDistanceMeters: Float, onFrame: (Vector3) -> Unit): Vector3 {
        val startNanos = withFrameNanos { it }
        var endPosition = basePosition
        while (true) {
            val nowNanos = withFrameNanos { it }
            val elapsedMs = (nowNanos - startNanos) / 1_000_000f
            val t = (elapsedMs / FLIGHT_DURATION_MS).coerceIn(0f, 1f)
            val forwardOffset = -launchDistanceMeters * t
            val heightOffset = ARC_HEIGHT_M * 4f * t * (1f - t) // parabola peaking at t=0.5
            val local = Vector3(basePosition.x, basePosition.y + heightOffset, basePosition.z + forwardOffset)
            entity.components[TransformComponent::class.java]?.setPosition(local)
            onFrame(local)
            if (t >= 1f) {
                endPosition = Vector3(basePosition.x, basePosition.y, basePosition.z + forwardOffset)
                break
            }
        }
        basePosition = endPosition
        return endPosition
    }

    /** Drops the character straight down and fades scale toward zero — miss feedback. */
    suspend fun fall() {
        val startNanos = withFrameNanos { it }
        val start = basePosition
        while (true) {
            val nowNanos = withFrameNanos { it }
            val elapsedMs = (nowNanos - startNanos) / 1_000_000f
            val t = (elapsedMs / FALL_DURATION_MS).coerceIn(0f, 1f)
            entity.components[TransformComponent::class.java]?.setPosition(
                Vector3(start.x, start.y - FALL_DROP_M * t, start.z),
            )
            entity.components[TransformComponent::class.java]?.setScaleVector(
                Vector3(1f - t, 1f - t, 1f - t),
            )
            if (t >= 1f) break
        }
    }
}
