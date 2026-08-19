package tech.illusion.spacehop.content

import android.util.Log
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
import tech.illusion.spacehop.game.PlatformKind
import tech.illusion.spacehop.game.PlatformSpec
import tech.illusion.spacehop.game.StageBoardState

/** Shared with `HomeStage.kt` so the character's recentring animation runs over the same
 * duration as the promoted platform's slide, instead of snapping the moment this returns. */
internal const val ADVANCE_PHASE_MS = 240L

/** Shared with `CharacterController` so one logcat filter shows both sides of the
 * frog-standing-on-platform grounding math at once. */
internal const val GROUND_TAG = "SpaceHopGround"

private const val PLATE_ASSET_PATH = "asset://model/Plate.usdz"
private const val WINDOW_ASSET_PATH = "asset://model/window.usdz"
private const val LILY_PADS_ASSET_PATH = "asset://model/lily_pads.usdz"
private const val LOTUS_ASSET_PATH = "asset://model/lotus_leaf.usdz"
private const val FALL_AUDIO_NAME = "plateFall"
private const val FALL_AUDIO_ASSET_PATH = "asset://audio/fall.wav"
// V2: the lotus-pond stage's version of the fall stinger — a splash instead of a clatter.
private const val SPLASH_AUDIO_NAME = "lotusSplash"
private const val SPLASH_AUDIO_ASSET_PATH = "asset://audio/splash.wav"

/** How much further out than its final spot the freshly-spawned "next" platform starts its
 * approach from, so it visibly slides in from ahead rather than materializing where it stands
 * (design still avoids sliding it in from *behind* the character — see [StageBoardAnimator] doc). */
private const val NEXT_APPROACH_EXTRA_DISTANCE_M = 0.15f

// V2 window/lotus theme: LilyPads is backdrop glimpsed through the window, not a jump target, so
// it's normalized to a larger relative diameter than the 1m the jump-target models share — reads
// as "a whole pond beyond the window" rather than matching the window's own footprint-scaled
// size. Both values are rough starting guesses, unverified on-device.
private const val LILY_PADS_RELATIVE_DIAMETER_M = 3f
private const val LILY_PADS_BACKGROUND_OFFSET_M = 1.2f
// The window is a piece of kitchen architecture the frog flies through mid-arc, not a frog-sized
// landing spot — it must not be squashed down to DifficultyCurve's plate/leaf footprint scale (a
// few centimetres). Sized independently at real-world scale: 1x its own
// normalized-to-1m-radius baseline, i.e. left unscaled. Unverified on-device; this is the knob
// to retune if it still doesn't read as a real window.
private const val WINDOW_REAL_SCALE = 1f

private data class PlacedTransform(val position: Vector3, val scale: Vector3) {
    companion object {
        fun of(spec: PlatformSpec) = PlacedTransform(
            position = Vector3(spec.lateralOffsetMeters, 0f, -spec.distanceFromPreviousMeters),
            // Uniform on all 3 axes — the old procedural cylinder mesh fixed its own height and
            // only scaled radius, but the loaded models have real authored proportions (each
            // jump-target child's scale in `loadPlatformVisual` already normalizes its own
            // bounding-sphere radius to 1m); scaling Y differently from X/Z here would distort a
            // flat plate/leaf into a deep bowl.
            scale = Vector3(spec.footprintRadiusMeters, spec.footprintRadiusMeters, spec.footprintRadiusMeters),
        )
    }
}

private fun lerpVector3(a: Vector3, b: Vector3, t: Float) =
    Vector3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)

private fun lerpTransform(a: PlacedTransform, b: PlacedTransform, t: Float) =
    PlacedTransform(lerpVector3(a.position, b.position, t), lerpVector3(a.scale, b.scale, t))

/** Everything one platform slot's wrapper entity carries: the two mutually-exclusive visual
 * children it can show ([PlatformKind]), its fall-sound controller, and its own overall
 * fade/slide opacity. Bundled into one object so [StageBoardAnimator.animateAdvance] can swap
 * "current" and "next" with a single reference swap instead of one line per field — a lesson
 * from the fall-audio addition, where each new per-slot piece of state needed its own swap line
 * added by hand. */
private class PlatformVisual(
    val wrapper: Entity,
    val opacity: OpacityControllerComponent,
    val plate: Entity,
    val plateOpacity: OpacityControllerComponent,
    val plateNormalizeScale: Float,
    val lotus: Entity,
    val lotusOpacity: OpacityControllerComponent,
    val lotusNormalizeScale: Float,
    val fallAudio: AudioPlayerController,
    val splashAudio: AudioPlayerController,
)

/** [entity] with the uniform scale [loadNormalizedJumpTarget] normalized it to — kept alongside
 * the entity (rather than read back later; this SDK's `TransformComponent` has no confirmed scale
 * getter) so [StageBoardAnimator.applyKind] can restore the exact right size when un-hiding a
 * child, not just any non-zero one. */
private data class NormalizedTarget(val entity: Entity, val normalizeScale: Float)

/**
 * Owns exactly two platform-slot wrapper entities for the whole game, built once, never
 * recreated — this workspace has no verified "remove a child entity" API (only
 * `SpatialViewContent.removeEntity` for top-level entities). [animateAdvance] swaps which
 * wrapper is "current" vs "next" rather than swapping their data, so the promoted platform
 * genuinely slides and the recycled one genuinely fades:
 *
 * - The wrapper that was visually "next" slides to the current anchor over [ADVANCE_PHASE_MS]
 *   (answers "how does the promoted platform move").
 * - The wrapper that was visually "current" — now stale — fades out, jumps instantly (while
 *   fully transparent) to a point [NEXT_APPROACH_EXTRA_DISTANCE_M] beyond the freshly-spawned
 *   "next" spec's position, then slides the rest of the way in while fading back in (answers
 *   "how does a brand-new platform appear": it approaches from further ahead, it does not
 *   slide in from behind the character, which would look like it darted out from under their
 *   feet).
 *
 * V2: each wrapper is built once with both [PlatformKind] visuals as children (plate, single
 * lotus leaf), and only one is ever opaque at a time — picked by [applyKind] from the
 * corresponding [PlatformSpec.kind]. This avoids ever needing to load or remove a child at
 * runtime (the same "no verified remove" constraint above). The one-time window crossing is
 * deliberately *not* a third visual here — see [windowCrossingWrapper].
 *
 * Own transform bookkeeping ([currentTransform]/[nextTransform]) rather than reading transforms
 * back off the entities — this SDK's `TransformComponent` only has verified setters
 * (`setPosition`/`setScaleVector`/...), no confirmed getters.
 */
class StageBoardAnimator {

    private lateinit var currentVisual: PlatformVisual
    private lateinit var nextVisual: PlatformVisual
    private lateinit var currentTransform: PlacedTransform
    private lateinit var nextTransform: PlacedTransform

    // A dedicated, one-time prop for the window crossing — deliberately *not* one of the two
    // reusable current/next slots above. The frog flies over this in one continuous arc without
    // ever landing on it (content/HomeStage.kt's compound jump), so it never needs to take part
    // in the current/next promotion dance; it only ever appears once (PlatformSpec.
    // windowCrossingDistanceMeters is set on exactly one jump per run) and can stay retired for
    // good afterwards.
    private lateinit var windowCrossingWrapper: Entity

    suspend fun build(anchor: Entity) {
        currentVisual = loadPlatformVisual()
        nextVisual = loadPlatformVisual()
        anchor.addChild(currentVisual.wrapper)
        anchor.addChild(nextVisual.wrapper)
        windowCrossingWrapper = loadWindowCrossingVisual()
        anchor.addChild(windowCrossingWrapper)
    }

    /** A plain wrapper `Entity` carrying position/footprint-scale/opacity, with both
     * [PlatformKind] visuals loaded as children up front — [applyKind] picks which one is
     * visible later, since nothing here has a verified way to load or remove a child at runtime.
     * Each jump-target child (plate, lotus leaf) is scaled once so its own bounding-sphere
     * radius reads as 1m — [PlacedTransform.of]'s `footprintRadiusMeters` scale on the wrapper
     * then multiplies through to give whichever one is showing its actual on-screen size,
     * exactly as the old unit-radius cylinder mesh did. Also carries a prepared fall-sound
     * `AudioPlayerController`, bound to the same wrapper so it plays from wherever that
     * particular slot ends up sitting. */
    private suspend fun loadPlatformVisual(): PlatformVisual {
        val wrapper = Entity()
        wrapper.components.set(OpacityControllerComponent(1f))
        wrapper.components.set(ObjectAudioComponent())

        val (plate, plateNormalizeScale) = loadNormalizedJumpTarget(PLATE_ASSET_PATH, alignTopToOrigin = true)
        val plateOpacity = OpacityControllerComponent(1f)
        plate.components.set(plateOpacity)
        wrapper.addChild(plate)

        val (lotus, lotusNormalizeScale) = loadNormalizedJumpTarget(LOTUS_ASSET_PATH, alignTopToOrigin = true)
        val lotusOpacity = OpacityControllerComponent(0f)
        lotus.components.set(lotusOpacity)
        wrapper.addChild(lotus)

        // The other half of the grounding contract, verified rather than assumed: each platform's
        // landable top surface must sit at its wrapper's own origin (`max.y` ≈ 0), because that is
        // where CharacterController lands the frog's feet. A point at the origin is unaffected by
        // the wrapper's own footprint scale, so this holds at every difficulty size.
        Log.e(
            GROUND_TAG,
            "plate top vs wrapper=${plate.getVisualBounds(wrapper, recursive = true).max.y} " +
                "lotus top vs wrapper=${lotus.getVisualBounds(wrapper, recursive = true).max.y}",
        )

        val fallAudioResource = withContext(Dispatchers.IO) {
            AudioResource.load(name = FALL_AUDIO_NAME, path = FALL_AUDIO_ASSET_PATH, loadType = LoadType.FROM_ASSETS)
        }
        val fallAudioController = wrapper.prepareAudio(fallAudioResource)
        val splashAudioResource = withContext(Dispatchers.IO) {
            AudioResource.load(name = SPLASH_AUDIO_NAME, path = SPLASH_AUDIO_ASSET_PATH, loadType = LoadType.FROM_ASSETS)
        }
        val splashAudioController = wrapper.prepareAudio(splashAudioResource)

        return PlatformVisual(
            wrapper = wrapper,
            opacity = wrapper.components[OpacityControllerComponent::class.java]!!,
            plate = plate,
            plateOpacity = plateOpacity,
            plateNormalizeScale = plateNormalizeScale,
            lotus = lotus,
            lotusOpacity = lotusOpacity,
            lotusNormalizeScale = lotusNormalizeScale,
            fallAudio = fallAudioController,
            splashAudio = splashAudioController,
        )
    }

    /** The window + its backdrop lily pads, built once and reused for the one crossing this run
     * ever has. Starts invisible; [prepareWindowCrossing] positions and reveals it right before
     * the frog's single fly-through arc, [retireWindowCrossing] hides it again once that arc
     * completes — it is never needed again after that (the world stays in the lotus pond for the
     * rest of the run). Not scaled by any [PlatformSpec]'s footprint radius — see
     * [WINDOW_REAL_SCALE]. */
    private suspend fun loadWindowCrossingVisual(): Entity {
        val wrapper = Entity()
        wrapper.components.set(OpacityControllerComponent(0f))
        wrapper.components[TransformComponent::class.java]
            ?.setScaleVector(Vector3(WINDOW_REAL_SCALE, WINDOW_REAL_SCALE, WINDOW_REAL_SCALE))

        val window = loadNormalizedJumpTarget(WINDOW_ASSET_PATH).entity
        wrapper.addChild(window)

        val lilyPadsBackdrop = withContext(Dispatchers.IO) { Entity.load(LILY_PADS_ASSET_PATH) }
        wrapper.addChild(lilyPadsBackdrop)
        val backdropNativeRadius = lilyPadsBackdrop.getVisualBounds(null, recursive = true).boundingSphereRadius
        val backdropScale = if (backdropNativeRadius > 0f) {
            (LILY_PADS_RELATIVE_DIAMETER_M / 2f) / backdropNativeRadius
        } else {
            1f
        }
        lilyPadsBackdrop.components[TransformComponent::class.java]?.apply {
            setScaleVector(Vector3(backdropScale, backdropScale, backdropScale))
            setPosition(Vector3(0f, 0f, -LILY_PADS_BACKGROUND_OFFSET_M))
        }

        return wrapper
    }

    /** Loads a model and normalizes its own bounding-sphere radius to 1m — shared by every
     * jump-target model (plate/lotus leaf) and the one-time window prop, so a single further
     * scale (footprint radius, or [WINDOW_REAL_SCALE]) gives each its actual real-world size.
     *
     * [alignTopToOrigin] additionally snaps the model's own *highest* bounding-box point — the
     * upward-facing surface the frog actually lands on, for a plate or a leaf lying flat — to
     * local Y=0, needed because neither `Plate.usdz` nor `lotus_leaf.usdz` has its authored
     * pivot sitting exactly at that surface (confirmed by report: the frog floated above both,
     * worse on the lotus leaf than the plate). Per the SDK's own "why does the entity appear
     * visually misaligned" FAQ, that's a model-origin issue, so this corrects it by measuring
     * each model's real bounds rather than guessing a per-asset constant.
     *
     * This was first written against `bounds.min` (the *lowest* point) — wrong: that's the
     * underside a plate/leaf itself would rest on if something were under *it*, not the surface
     * facing up. Aligning the bottom instead pushed both surfaces up by their own mesh
     * thickness, which combined with zeroing the frog's own legacy `+0.05` offset
     * (`CHARACTER_GROUND_Y`, content/HomeStage.kt) in the same pass landed the frog below both
     * platforms — confirmed by report. `bounds.max` is the corrected reference.
     *
     * Applied to both jump-target models below; left off for the window prop, which the frog
     * only flies through mid-arc and never stands on. */
    private suspend fun loadNormalizedJumpTarget(assetPath: String, alignTopToOrigin: Boolean = false): NormalizedTarget {
        val model = withContext(Dispatchers.IO) { Entity.load(assetPath) }
        val bounds = model.getVisualBounds(null, recursive = true)
        val normalizeScale = if (bounds.boundingSphereRadius > 0f) 1f / bounds.boundingSphereRadius else 1f
        val appliedY = if (alignTopToOrigin) -bounds.max.y * normalizeScale else 0f
        model.components[TransformComponent::class.java]?.apply {
            setScaleVector(Vector3(normalizeScale, normalizeScale, normalizeScale))
            if (alignTopToOrigin) setPosition(Vector3(0f, appliedY, 0f))
        }
        // Grounding diagnostics: two rounds of reasoning about where these models' origins sit
        // relative to their own geometry produced two wrong fixes, so measure instead. Logs the
        // raw bounds this measurement actually returns (note this runs while the model is still
        // unparented — the wrapper adopts it after this returns — whereas the frog is measured
        // already parented, so the two may not even be in the same space).
        Log.e(
            GROUND_TAG,
            "platform $assetPath: min=${bounds.min} max=${bounds.max} center=${bounds.center} " +
                "size=${bounds.size} sphereR=${bounds.boundingSphereRadius} " +
                "normalizeScale=$normalizeScale alignTop=$alignTopToOrigin appliedY=$appliedY",
        )
        return NormalizedTarget(model, normalizeScale)
    }

    /** Shows exactly the child matching [kind] on [visual], hiding the other. Only ever called
     * while [visual]'s own overall opacity is 0 (a fresh [build] before anything is shown, or
     * mid-[animateAdvance] once the stale wrapper has fully faded out), so the switch itself is
     * never visible.
     *
     * Also zero-scales the hidden child, on top of zeroing its opacity — belt and suspenders
     * (the same hide convention this codebase already uses for the trail dots and window prop)
     * for an as-yet-unconfirmed report of a small stray dark mark on a plate: scale-to-zero
     * collapses the hidden mesh to no geometry at all, which can't render regardless of whether
     * the opacity path has some edge case this hasn't found yet. Restores [PlatformVisual.
     * plateNormalizeScale]/[PlatformVisual.lotusNormalizeScale] — not `Vector3(1f, 1f, 1f)` —
     * when showing a child, since that normalization scale (not 1) is each model's own
     * real-to-1m-bounding-sphere baseline (see [loadNormalizedJumpTarget]). */
    private fun applyKind(visual: PlatformVisual, kind: PlatformKind) {
        visual.plateOpacity.opacity = if (kind == PlatformKind.PLATE) 1f else 0f
        visual.lotusOpacity.opacity = if (kind == PlatformKind.LOTUS) 1f else 0f
        val plateScale = if (kind == PlatformKind.PLATE) visual.plateNormalizeScale else 0f
        val lotusScale = if (kind == PlatformKind.LOTUS) visual.lotusNormalizeScale else 0f
        visual.plate.components[TransformComponent::class.java]?.setScaleVector(Vector3(plateScale, plateScale, plateScale))
        visual.lotus.components[TransformComponent::class.java]?.setScaleVector(Vector3(lotusScale, lotusScale, lotusScale))
    }

    /** Positions the one-time window prop at [target]'s window leg (relative to the current
     * anchor — see [PlatformSpec.windowCrossingDistanceMeters]) and reveals it. Called as soon
     * as the compound window-crossing jump is spawned as the new `next` — not right before the
     * frog's fly-through arc — so the window (and the lotus backdrop glimpsed through it) is
     * visible for the whole round leading up to that jump, not just a flash mid-flight (V2
     * design ask). No-ops if [target] isn't a window-crossing jump. */
    fun prepareWindowCrossing(target: PlatformSpec) {
        val crossingDistance = target.windowCrossingDistanceMeters ?: return
        applyTransform(
            windowCrossingWrapper,
            PlacedTransform(
                // Lines up with target's own lateral offset, not a separately-generated one, so
                // the prop sits on the straight line the frog's single arc actually flies.
                position = Vector3(target.lateralOffsetMeters, 0f, -crossingDistance),
                scale = Vector3(WINDOW_REAL_SCALE, WINDOW_REAL_SCALE, WINDOW_REAL_SCALE),
            ),
        )
        windowCrossingWrapper.components[OpacityControllerComponent::class.java]?.opacity = 1f
    }

    /** Hides the window prop once the frog's fly-through arc completes — it's never shown again
     * for the rest of the run. */
    fun retireWindowCrossing() {
        windowCrossingWrapper.components[OpacityControllerComponent::class.java]?.opacity = 0f
    }

    /** Plays the fall/splash stinger from whichever plate or lotus leaf is currently the jump
     * target — the one the frog just missed, whether by falling short, overshooting, or the
     * app's own forced-miss catch. [kind] picks the sound (a clatter on the kitchen stage, a
     * splash on the lotus-pond stage, V2). Restarts from the top every call, matching
     * CharacterController's jump-stinger convention. Call this alongside
     * `CharacterController.fall()`, not from inside it. */
    fun playFallSound(kind: PlatformKind) {
        val audio = if (kind == PlatformKind.LOTUS) nextVisual.splashAudio else nextVisual.fallAudio
        audio.stop()
        audio.play()
    }

    /** No animation — used once when a fresh run starts (there is no "materialize" moment at
     * run start, only between successful jumps). */
    fun placeInstant(state: StageBoardState) {
        currentTransform = PlacedTransform.of(state.platforms[0])
        nextTransform = PlacedTransform.of(state.platforms[1])
        applyTransform(currentVisual.wrapper, currentTransform)
        applyTransform(nextVisual.wrapper, nextTransform)
        currentVisual.opacity.opacity = 1f
        nextVisual.opacity.opacity = 1f
        applyKind(currentVisual, state.platforms[0].kind)
        applyKind(nextVisual, state.platforms[1].kind)
    }

    /** The slide + materialize transition, run once per successful landing. */
    suspend fun animateAdvance(newState: StageBoardState) {
        val staleVisual = currentVisual
        val promotedVisual = nextVisual
        val promotedFrom = nextTransform

        // Swap the references now: everything below — and every call after this one returns —
        // treats the promoted platform as "current" and the stale one as "next".
        currentVisual = promotedVisual
        nextVisual = staleVisual

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
            applyTransform(promotedVisual.wrapper, lerpTransform(promotedFrom, promotedTo, t))
            staleVisual.opacity.opacity = 1f - t // fully transparent exactly when the slide finishes
            if (t >= 1f) break
        }
        currentTransform = promotedTo

        // The stale wrapper is fully transparent now — safe to swap which child it shows before
        // it starts sliding/fading back in as the freshly-spawned "next" platform.
        applyKind(staleVisual, newState.platforms[1].kind)
        applyTransform(staleVisual.wrapper, staleFrom) // instant — it is fully transparent right now
        val approachStartNanos = withFrameNanos { it }
        while (true) {
            val t = (((withFrameNanos { it }) - approachStartNanos) / 1_000_000f / ADVANCE_PHASE_MS)
                .coerceIn(0f, 1f)
            applyTransform(staleVisual.wrapper, lerpTransform(staleFrom, staleTo, t))
            staleVisual.opacity.opacity = t
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
