package tech.illusion.spacehop.content

import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Vector3

private const val TRAIL_POINT_COUNT = 6
private const val TRAIL_BALL_RADIUS_M = 0.015f

/** A fixed pool of [TRAIL_POINT_COUNT] fading dots along the character's flight path —
 * design doc §三: pure post-hoc flight feedback, not a landing predictor (the dots only
 * ever show where the character *has been* this jump, never where it will land). */
class TrailRenderer {

    private val points = mutableListOf<ModelEntity>()
    private val history = mutableListOf<Vector3>()

    fun build(content: SpatialViewContent) {
        val mesh = com.pico.spatial.core.ecs.resource.MeshResource.createSphere(TRAIL_BALL_RADIUS_M)
        repeat(TRAIL_POINT_COUNT) { i ->
            val opacity = 1f - i.toFloat() / TRAIL_POINT_COUNT
            val material = UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(StagePalette.color(StagePalette.TRAIL, opacity))
                setDepthWrite(false)
            }
            val entity = ModelEntity(mesh, material)
            entity.components[TransformComponent::class.java]?.setScaleVector(Vector3.ZERO) // hidden until sampled
            points += entity
            content.addEntity(entity)
        }
    }

    /** Call once per animation frame during flight with the character's current local position. */
    fun sample(position: Vector3) {
        history.add(0, position)
        if (history.size > TRAIL_POINT_COUNT) history.removeAt(history.lastIndex)
        history.forEachIndexed { i, pos ->
            points[i].components[TransformComponent::class.java]?.apply {
                setPosition(pos)
                setScaleVector(Vector3(1f, 1f, 1f))
            }
        }
    }

    /** Hides every dot — call at the start of each new jump so last jump's trail doesn't linger. */
    fun clear() {
        history.clear()
        points.forEach { it.components[TransformComponent::class.java]?.setScaleVector(Vector3.ZERO) }
    }

    fun dispose(content: SpatialViewContent) {
        points.forEach { content.removeEntity(it) }
        points.clear()
    }
}
