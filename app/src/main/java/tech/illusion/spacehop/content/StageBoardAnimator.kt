package tech.illusion.spacehop.content

import androidx.compose.runtime.withFrameNanos
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.math.Vector3
import tech.illusion.spacehop.game.PlatformSpec
import tech.illusion.spacehop.game.StageBoardState

private const val ADVANCE_PHASE_MS = 240L

private data class PlacedTransform(val position: Vector3, val scale: Vector3) {
    companion object {
        fun of(spec: PlatformSpec) = PlacedTransform(
            position = Vector3(spec.lateralOffsetMeters, 0f, -spec.distanceFromPreviousMeters),
            scale = Vector3(spec.footprintRadiusMeters, 1f, spec.footprintRadiusMeters),
        )
    }
}

private fun lerpVector3(a: Vector3, b: Vector3, t: Float) =
    Vector3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)

private fun lerpTransform(a: PlacedTransform, b: PlacedTransform, t: Float) =
    PlacedTransform(lerpVector3(a.position, b.position, t), lerpVector3(a.scale, b.scale, t))

/**
 * Owns exactly two platform entities for the whole game, built once, never recreated — this
 * workspace has no verified "remove a child entity" API (only `SpatialViewContent.removeEntity`
 * for top-level entities). [animateAdvance] swaps which entity reference is "current" vs "next"
 * rather than swapping their data, so the promoted platform genuinely slides and the recycled
 * one genuinely fades:
 *
 * - The entity that was visually "next" slides to the current anchor over [ADVANCE_PHASE_MS]
 *   (answers "how does the promoted platform move").
 * - The entity that was visually "current" — now stale — fades out, jumps instantly to the
 *   freshly-spawned "next" spec while fully transparent, then fades back in (answers "how
 *   does a brand-new platform appear": it materializes, it does not slide in from anywhere,
 *   because sliding a platform in from behind the character would look like it darted out
 *   from under their feet).
 *
 * Own transform bookkeeping ([currentTransform]/[nextTransform]) rather than reading transforms
 * back off the entities — this SDK's `TransformComponent` only has verified setters
 * (`setPosition`/`setScaleVector`/...), no confirmed getters.
 */
class StageBoardAnimator(private val mats: StageMaterials) {

    private lateinit var currentEntity: Entity
    private lateinit var nextEntity: Entity
    private lateinit var currentMaterial: PhysicallyBasedMaterial
    private lateinit var nextMaterial: PhysicallyBasedMaterial
    private lateinit var currentTransform: PlacedTransform
    private lateinit var nextTransform: PlacedTransform

    fun build(anchor: Entity, nominalMesh: MeshResource) {
        currentMaterial = mats.newPlatformMaterial()
        nextMaterial = mats.newPlatformMaterial()
        currentEntity = ModelEntity(nominalMesh, currentMaterial)
        nextEntity = ModelEntity(nominalMesh, nextMaterial)
        anchor.addChild(currentEntity)
        anchor.addChild(nextEntity)
    }

    /** No animation — used once when a fresh run starts (there is no "materialize" moment at
     * run start, only between successful jumps). */
    fun placeInstant(state: StageBoardState) {
        currentTransform = PlacedTransform.of(state.platforms[0])
        nextTransform = PlacedTransform.of(state.platforms[1])
        applyTransform(currentEntity, currentTransform)
        applyTransform(nextEntity, nextTransform)
        currentMaterial.setOpacity(1f)
        nextMaterial.setOpacity(1f)
    }

    /** The slide + materialize transition, run once per successful landing. */
    suspend fun animateAdvance(newState: StageBoardState) {
        val staleEntity = currentEntity
        val staleMaterial = currentMaterial
        val promotedEntity = nextEntity
        val promotedMaterial = nextMaterial
        val promotedFrom = nextTransform

        // Swap the references now: everything below — and every call after this one returns —
        // treats the promoted platform as "current" and the stale one as "next".
        currentEntity = promotedEntity; currentMaterial = promotedMaterial
        nextEntity = staleEntity; nextMaterial = staleMaterial

        val promotedTo = PlacedTransform.of(newState.platforms[0])
        val staleTo = PlacedTransform.of(newState.platforms[1])

        val slideStartNanos = withFrameNanos { it }
        while (true) {
            val t = (((withFrameNanos { it }) - slideStartNanos) / 1_000_000f / ADVANCE_PHASE_MS)
                .coerceIn(0f, 1f)
            applyTransform(promotedEntity, lerpTransform(promotedFrom, promotedTo, t))
            staleMaterial.setOpacity(1f - t) // fully transparent exactly when the slide finishes
            if (t >= 1f) break
        }
        currentTransform = promotedTo

        applyTransform(staleEntity, staleTo) // instant — it is fully transparent right now
        nextTransform = staleTo
        val fadeInStartNanos = withFrameNanos { it }
        while (true) {
            val t = (((withFrameNanos { it }) - fadeInStartNanos) / 1_000_000f / ADVANCE_PHASE_MS)
                .coerceIn(0f, 1f)
            staleMaterial.setOpacity(t)
            if (t >= 1f) break
        }
    }

    private fun applyTransform(entity: Entity, transform: PlacedTransform) {
        entity.components[TransformComponent::class.java]?.apply {
            setPosition(transform.position)
            setScaleVector(transform.scale)
        }
    }
}
