package tech.illusion.spacehop.content

import android.util.Log
import androidx.compose.runtime.withFrameNanos
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.LoadType
import com.pico.spatial.core.ecs.ObjectAudioComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.animation.AnimationPlaybackController
import com.pico.spatial.core.ecs.audio.AudioPlayerController
import com.pico.spatial.core.ecs.resource.AnimationResource
import com.pico.spatial.core.ecs.resource.AudioResource
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import kotlin.math.atan2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class CharacterAnim { IDLE, CHARGING, FLYING, LANDED_PERFECT, LANDED_SUCCESS, FALLING }

private const val FLIGHT_DURATION_MS = 420L
private const val ARC_HEIGHT_M = 0.12f
private const val FALL_DURATION_MS = 500L
private const val FALL_DROP_M = 0.6f
private const val FROG_ASSET_PATH = "asset://model/frog.usdz"
private const val JUMP_AUDIO_NAME = "frogJump"
// "asset://..." — the audio-resource doc's own load-from-assets *sample code* shows a bare
// relative path, but on-device both a bare path and a clean, valid .mp3/.wav file threw
// ResourceLoadingException("Illegal file type", url: <the bare path>) identically regardless of
// the file's actual codec — the failure tracks the path string, not the file content — so the
// LoadType.FROM_ASSETS enum's own doc comment ("specified by a URI using the asset:// scheme")
// is what this build's runtime actually honours; the sample text is stale for this SDK version.
// .wav, not .mp3: every real ObjectAudioComponent/AmbientAudioComponent sample in the SDK docs
// (spatial-sdk_audio_example-create-immersive-spatial-audio.md) ships .wav assets, never .mp3.
private const val JUMP_AUDIO_ASSET_PATH = "asset://audio/jump.wav"
// Extra downward nudge past the measured feet-on-ground position — the bounds-based grounding
// still read slightly high on-device; tune this by eye on real-device screenshots.
private const val FROG_EXTRA_GROUND_OFFSET_M = 0.03f
private const val ANIM_TAG = "SpaceHopAnim"

/** Owns the character entity's transform across its animation states. All timing is
 * simplified easing, not real projectile physics (design doc §2.4: "物理感 vs 节奏感"
 * risk — favours a snappy feel over ballistic accuracy).
 *
 * [entity] is a plain wrapper — position/squash-scale target, exactly as before — with the
 * loaded `Frog_Jump.usdz` model as its child ([modelEntity]), so the model's own normalization
 * scale (baked in once by [load]) is never overwritten by the squash/idle scale calls below. */
class CharacterController private constructor(
    private val entity: Entity,
    private val modelEntity: Entity,
    private val jumpAudioController: AudioPlayerController,
) {

    private var basePosition = Vector3.ZERO
    // Tracked by hand for the same reason as [basePosition]: this SDK's `TransformComponent`
    // exposes only verified setters, no confirmed getters to read the current yaw back.
    private var baseYawDegrees = 0f
    private var skinnedMeshEntity: Entity? = null
    private var jumpAnimation: AnimationResource? = null
    private var jumpPlayback: AnimationPlaybackController? = null

    companion object {
        /** Loads the frog model as a child of a new wrapper entity under [anchor], scaling it so
         * its bounding-sphere radius matches [characterRadiusMeters] (the old sphere character's
         * radius), then returns a ready [CharacterController]. */
        suspend fun load(anchor: Entity, characterRadiusMeters: Float): CharacterController {
            val entity = Entity()
            anchor.addChild(entity)
            val model = withContext(Dispatchers.IO) { Entity.load(FROG_ASSET_PATH) }
            entity.addChild(model)
            val modelTransform = model.components[TransformComponent::class.java]
            // Measure raw bounds before any transform is touched (this is the path that already
            // proved correct for sizing) — center/min here are in the model's own untouched local
            // space. A 180° yaw only negates X and Z (Y/up is unaffected), so the corresponding
            // flip is applied by hand below instead of re-measuring after rotating: a first
            // attempt that measured via getVisualBounds(entity, ...) *after* rotating landed the
            // model well clear of the platform horizontally, so it's not trusted here.
            val bounds = model.getVisualBounds(null, recursive = true)
            val scale = if (bounds.boundingSphereRadius > 0f) characterRadiusMeters / bounds.boundingSphereRadius else 1f
            modelTransform?.apply {
                setScaleVector(Vector3(scale, scale, scale))
                // Authored facing +Z (toward the player); the character travels -Z per `flyTo`'s
                // auto-aim, so flip it 180° around Y — no sign ambiguity at 180°, unlike the
                // primary panel's still-unverified pitch tilt (HomeStage.kt).
                setEulerAngles(EulerAngles(pitch = 0f, yaw = 180f, roll = 0f))
                // The model's authored origin is neither centred over its footprint nor at its
                // feet (it rendered floating above the plate, off-centre) — shift it so the
                // horizontal centre and the lowest point (feet) land exactly on this entity's
                // own origin, which `entity`'s position then places on the ground/platform.
                // X/Z use +center (not -center) because the 180° flip above already negates them.
                setPosition(
                    Vector3(
                        bounds.center.x * scale,
                        -bounds.min.y * scale - FROG_EXTRA_GROUND_OFFSET_M,
                        bounds.center.z * scale + 0.03f,
                    ),
                )
            }
            // Bound to `entity` (the wrapper), not `model` — `entity` is what `flyTo` actually
            // moves every frame, so the jump cue's position keeps tracking the frog mid-arc
            // instead of freezing wherever it happened to be when this loaded. The fall stinger
            // lives on the *plate* the frog missed instead (StageBoardAnimator), not here.
            entity.components.set(ObjectAudioComponent())
            val jumpAudioResource = withContext(Dispatchers.IO) {
                AudioResource.load(name = JUMP_AUDIO_NAME, path = JUMP_AUDIO_ASSET_PATH, loadType = LoadType.FROM_ASSETS)
            }
            val jumpAudioController = entity.prepareAudio(jumpAudioResource)

            return CharacterController(entity, model, jumpAudioController)
        }
    }

    /** Finds the frog model's skinned mesh and its baked jump clip once, then plays it — USD
     * only ever stores a single `SkelAnimation` per file (see the SDK's skeletal-animation
     * doc), so `frog.usdz` exposes exactly one animation resource: the jump.
     *
     * The clip is time-scaled so its whole length plays out across [FLIGHT_DURATION_MS]. Left at
     * 1x it was still early in the leap when the arc ended, and cutting playback off there is why
     * the frog kept freezing with its legs splayed: stopping playback does not re-pose the
     * skeleton, it just abandons it on whatever frame was last evaluated. Playing the clip out in
     * full instead means it settles on its own authored final frame. */
    private fun playJumpAnimation() {
        val skinned = skinnedMeshEntity ?: modelEntity.findSkinnedMeshEntity().firstOrNull()?.also {
            skinnedMeshEntity = it
            jumpAnimation = it.getAnimationResources().firstOrNull()
        } ?: return
        val clip = jumpAnimation ?: return
        jumpPlayback?.close()
        val playback = skinned.playAnimation(clip)
        val clipSeconds = playback.getDuration()
        val flightSeconds = FLIGHT_DURATION_MS / 1000f
        if (clipSeconds > 0f) playback.setSpeed(clipSeconds / flightSeconds)
        Log.e(ANIM_TAG, "play: clipSeconds=$clipSeconds flightSeconds=$flightSeconds speed=${playback.getSpeed()}")
        jumpPlayback = playback
    }

    /** Releases the jump clip's hold on the skeleton once the flight ends, whether it lands or
     * falls. `stopAllAnimations()` and `stop()` + `setTime(0f)` were both tried and both left the
     * frog frozen mid-leap — neither re-poses the skeleton — so this closes the playback
     * controller outright to drop the animation binding and let the bind pose (sitting, as the
     * model rendered before any animation had ever played) take over again. */
    private fun resetJumpAnimation() {
        val playback = jumpPlayback ?: return
        Log.e(
            ANIM_TAG,
            "end: time=${playback.getTime()} duration=${playback.getDuration()} complete=${playback.isComplete()}",
        )
        playback.stop()
        playback.close()
        jumpPlayback = null
    }

    /** Restarts the jump stinger from the top every call — a rapid next jump can otherwise
     * overlap the tail of the previous one still finishing. */
    private fun playJumpSound() {
        jumpAudioController.stop()
        jumpAudioController.play()
    }

    fun setIdlePosition(position: Vector3) {
        basePosition = position
        entity.components[TransformComponent::class.java]?.setPosition(position)
        entity.components[TransformComponent::class.java]?.setScaleVector(Vector3(1f, 1f, 1f))
    }

    /**
     * Yaw that turns the character from its resting forward (-Z, away from the player — the
     * model's own 180° flip in [load] already establishes that) toward a platform sitting
     * [lateralOffsetMeters] to the side and [forwardDistanceMeters] ahead of it, matching how
     * `StageBoardAnimator` places platforms at `Vector3(lateralOffset, 0, -distance)`.
     *
     * Derived from the documented convention — right-handed axes, yaw applied about world Y
     * (`com.pico.spatial.core.math.EulerAngles`) — where rotating -Z by yaw φ gives
     * `(-sin φ, 0, -cos φ)`; matching that to `(lateral, 0, -distance)` yields this. Zero lateral
     * offset correctly returns 0 (no turn). The *sign* still rests on positive yaw turning the
     * documented way, which is unverified in this workspace (`SpaceSnake/AGENTS.md`) and is why
     * the 180° flip was chosen earlier — if the frog turns away from the plate on device, negate
     * this one expression.
     */
    private fun aimYawDegrees(lateralOffsetMeters: Float, forwardDistanceMeters: Float): Float =
        (-atan2(lateralOffsetMeters.toDouble(), forwardDistanceMeters.toDouble()) * 180.0 / Math.PI).toFloat()

    /** Snaps the character to face the given platform — for run start, where there is no landing
     * transition to fold the turn into. */
    fun faceTarget(lateralOffsetMeters: Float, forwardDistanceMeters: Float) {
        baseYawDegrees = aimYawDegrees(lateralOffsetMeters, forwardDistanceMeters)
        entity.components[TransformComponent::class.java]
            ?.setEulerAngles(EulerAngles(pitch = 0f, yaw = baseYawDegrees, roll = 0f))
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
        playJumpAnimation()
        playJumpSound()
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
        resetJumpAnimation()
        basePosition = endPosition
        return endPosition
    }

    /** Drops the character straight down and fades scale toward zero — miss feedback. Callers
     * pair this with `StageBoardAnimator.playFallSound()` for the audio cue. */
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

    /** Smoothly moves from the landed spot back to [target] (the recentred idle position) while
     * turning to face the next platform ([nextLateralOffsetMeters] / [nextForwardDistanceMeters]),
     * over [durationMs] — call concurrently with `StageBoardAnimator.animateAdvance`'s platform
     * slide (same [tech.illusion.spacehop.content.ADVANCE_PHASE_MS] duration) so the character and
     * the platform it's standing on arrive at the anchor together instead of the platform sliding
     * out from under a character that only snaps back afterwards.
     *
     * The yaw lerps plainly, with no shortest-arc handling: both endpoints come from
     * [aimYawDegrees], whose range is bounded by the spawner's lateral offset over its minimum
     * distance (`atan2(0.08, 0.18)` ≈ 24°), so the turn can never approach the ±180° wrap. */
    suspend fun recenterTo(
        target: Vector3,
        nextLateralOffsetMeters: Float,
        nextForwardDistanceMeters: Float,
        durationMs: Long,
    ) {
        val start = basePosition
        val startYaw = baseYawDegrees
        val targetYaw = aimYawDegrees(nextLateralOffsetMeters, nextForwardDistanceMeters)
        val startNanos = withFrameNanos { it }
        while (true) {
            val nowNanos = withFrameNanos { it }
            val elapsedMs = (nowNanos - startNanos) / 1_000_000f
            val t = (elapsedMs / durationMs).coerceIn(0f, 1f)
            val position = Vector3(
                start.x + (target.x - start.x) * t,
                start.y + (target.y - start.y) * t,
                start.z + (target.z - start.z) * t,
            )
            entity.components[TransformComponent::class.java]?.apply {
                setPosition(position)
                setEulerAngles(EulerAngles(pitch = 0f, yaw = startYaw + (targetYaw - startYaw) * t, roll = 0f))
            }
            if (t >= 1f) break
        }
        basePosition = target
        baseYawDegrees = targetYaw
    }
}
