package tech.illusion.spacehop.content

import androidx.compose.runtime.withFrameNanos
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.LoadType
import com.pico.spatial.core.ecs.ObjectAudioComponent
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.audio.AudioPlayerController
import com.pico.spatial.core.ecs.resource.AudioResource
import com.pico.spatial.core.math.Vector3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.illusion.spacehop.game.PlatformSpec
import tech.illusion.spacehop.game.StageBoardState

/** Shared with `HomeStage.kt` so the character's recentring animation runs over the same
 * duration as the promoted platform's slide, instead of snapping the moment this returns. */
internal const val ADVANCE_PHASE_MS = 240L

private const val PLATE_ASSET_PATH = "asset://model/Plate.usdz"
private const val FALL_AUDIO_NAME = "plateFall"
private const val FALL_AUDIO_ASSET_PATH = "asset://audio/fall.wav"

/** How much further out than its final spot the freshly-spawned "next" platform starts its
 * approach from, so it visibly slides in from ahead rather than materializing where it stands
 * (design still avoids sliding it in from *behind* the character — see [StageBoardAnimator] doc). */
private const val NEXT_APPROACH_EXTRA_DISTANCE_M = 0.15f

private data class PlacedTransform(val position: Vector3, val scale: Vector3) {
    companion object {
        fun of(spec: PlatformSpec) = PlacedTransform(
            position = Vector3(spec.lateralOffsetMeters, 0f, -spec.distanceFromPreviousMeters),
            // Uniform on all 3 axes — the old procedural cylinder mesh fixed its own height and
            // only scaled radius, but the loaded Plate model has real authored proportions (its
            // child scale in `loadPlateEntity` already normalizes its bounding-sphere radius to
            // 1m); scaling Y differently from X/Z here distorts a flat plate into a deep bowl.
            scale = Vector3(spec.footprintRadiusMeters, spec.footprintRadiusMeters, spec.footprintRadiusMeters),
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
 * - The entity that was visually "current" — now stale — fades out, jumps instantly (while
 *   fully transparent) to a point [NEXT_APPROACH_EXTRA_DISTANCE_M] beyond the freshly-spawned
 *   "next" spec's position, then slides the rest of the way in while fading back in (answers
 *   "how does a brand-new platform appear": it approaches from further ahead, it does not
 *   slide in from behind the character, which would look like it darted out from under their
 *   feet).
 *
 * Own transform bookkeeping ([currentTransform]/[nextTransform]) rather than reading transforms
 * back off the entities — this SDK's `TransformComponent` only has verified setters
 * (`setPosition`/`setScaleVector`/...), no confirmed getters.
 *
 * Each plate entity also carries its own prepared fall-sound `AudioPlayerController`
 * ([currentFallAudio]/[nextFallAudio]), swapped alongside the entity/opacity refs in
 * [animateAdvance] for the same reason: the "you didn't make it" cue plays from the *plate* the
 * frog missed (design ask), not the frog itself, and which physical entity is currently playing
 * the "next" plate's role changes every successful landing.
 */
class StageBoardAnimator {

    private lateinit var currentEntity: Entity
    private lateinit var nextEntity: Entity
    private lateinit var currentOpacity: OpacityControllerComponent
    private lateinit var nextOpacity: OpacityControllerComponent
    private lateinit var currentFallAudio: AudioPlayerController
    private lateinit var nextFallAudio: AudioPlayerController
    private lateinit var currentTransform: PlacedTransform
    private lateinit var nextTransform: PlacedTransform

    suspend fun build(anchor: Entity) {
        val (currentPlate, currentAudio) = loadPlateEntity()
        val (nextPlate, nextAudio) = loadPlateEntity()
        currentEntity = currentPlate
        nextEntity = nextPlate
        currentFallAudio = currentAudio
        nextFallAudio = nextAudio
        anchor.addChild(currentEntity)
        anchor.addChild(nextEntity)
        currentOpacity = currentEntity.components[OpacityControllerComponent::class.java]!!
        nextOpacity = nextEntity.components[OpacityControllerComponent::class.java]!!
    }

    /** A plain wrapper `Entity` (carries position/footprint-scale/opacity) with the loaded
     * `Plate.usdz` model as a child, scaled once so its own bounding-sphere radius reads as
     * 1m — [PlacedTransform.of]'s `footprintRadiusMeters` scale on the wrapper then multiplies
     * through to give the plate its actual on-screen size, exactly as the old unit-radius
     * cylinder mesh did. Also carries a prepared fall-sound `AudioPlayerController`, bound to
     * the same wrapper so it plays from wherever that particular plate ends up sitting. */
    private suspend fun loadPlateEntity(): Pair<Entity, AudioPlayerController> {
        val wrapper = Entity()
        wrapper.components.set(OpacityControllerComponent(1f))
        wrapper.components.set(ObjectAudioComponent())
        val plate = withContext(Dispatchers.IO) { Entity.load(PLATE_ASSET_PATH) }
        wrapper.addChild(plate)
        val nativeRadius = plate.getVisualBounds(null, recursive = true).boundingSphereRadius
        val normalizeScale = if (nativeRadius > 0f) 1f / nativeRadius else 1f
        plate.components[TransformComponent::class.java]
            ?.setScaleVector(Vector3(normalizeScale, normalizeScale, normalizeScale))
        val fallAudioResource = withContext(Dispatchers.IO) {
            AudioResource.load(name = FALL_AUDIO_NAME, path = FALL_AUDIO_ASSET_PATH, loadType = LoadType.FROM_ASSETS)
        }
        val fallAudioController = wrapper.prepareAudio(fallAudioResource)
        return wrapper to fallAudioController
    }

    /** Plays the fall stinger from whichever plate is currently the jump target — the one the
     * frog just missed, whether by falling short, overshooting, or the app's own forced-miss
     * catch. Restarts from the top every call, matching CharacterController's jump-stinger
     * convention. Call this alongside `CharacterController.fall()`, not from inside it. */
    fun playFallSound() {
        nextFallAudio.stop()
        nextFallAudio.play()
    }

    /** No animation — used once when a fresh run starts (there is no "materialize" moment at
     * run start, only between successful jumps). */
    fun placeInstant(state: StageBoardState) {
        currentTransform = PlacedTransform.of(state.platforms[0])
        nextTransform = PlacedTransform.of(state.platforms[1])
        applyTransform(currentEntity, currentTransform)
        applyTransform(nextEntity, nextTransform)
        currentOpacity.opacity = 1f
        nextOpacity.opacity = 1f
    }

    /** The slide + materialize transition, run once per successful landing. */
    suspend fun animateAdvance(newState: StageBoardState) {
        val staleEntity = currentEntity
        val staleOpacity = currentOpacity
        val staleFallAudio = currentFallAudio
        val promotedEntity = nextEntity
        val promotedOpacity = nextOpacity
        val promotedFallAudio = nextFallAudio
        val promotedFrom = nextTransform

        // Swap the references now: everything below — and every call after this one returns —
        // treats the promoted platform as "current" and the stale one as "next".
        currentEntity = promotedEntity; currentOpacity = promotedOpacity; currentFallAudio = promotedFallAudio
        nextEntity = staleEntity; nextOpacity = staleOpacity; nextFallAudio = staleFallAudio

        // The promoted platform becomes the anchor origin itself (the character lands and
        // resets to Vector3(0f, 0.05f, 0f) — see HomeStage.kt's `controller.setIdlePosition`
        // call right after this), not `-distanceFromPreviousMeters` away from it: that field
        // is the distance it was jumped to *before* recentring and no longer describes its
        // position relative to the new anchor. Only its footprint scale still applies.
        val promotedTo = PlacedTransform.of(newState.platforms[0]).copy(position = Vector3.ZERO)
        val staleTo = PlacedTransform.of(newState.platforms[1])
        val staleFrom = staleTo.copy(
            position = Vector3(staleTo.position.x, staleTo.position.y, staleTo.position.z - NEXT_APPROACH_EXTRA_DISTANCE_M),
        )

        val slideStartNanos = withFrameNanos { it }
        while (true) {
            val t = (((withFrameNanos { it }) - slideStartNanos) / 1_000_000f / ADVANCE_PHASE_MS)
                .coerceIn(0f, 1f)
            applyTransform(promotedEntity, lerpTransform(promotedFrom, promotedTo, t))
            staleOpacity.opacity = 1f - t // fully transparent exactly when the slide finishes
            if (t >= 1f) break
        }
        currentTransform = promotedTo

        applyTransform(staleEntity, staleFrom) // instant — it is fully transparent right now
        val approachStartNanos = withFrameNanos { it }
        while (true) {
            val t = (((withFrameNanos { it }) - approachStartNanos) / 1_000_000f / ADVANCE_PHASE_MS)
                .coerceIn(0f, 1f)
            applyTransform(staleEntity, lerpTransform(staleFrom, staleTo, t))
            staleOpacity.opacity = t
            if (t >= 1f) break
        }
        nextTransform = staleTo
    }

    private fun applyTransform(entity: Entity, transform: PlacedTransform) {
        entity.components[TransformComponent::class.java]?.apply {
            setPosition(transform.position)
            setScaleVector(transform.scale)
        }
    }
}
