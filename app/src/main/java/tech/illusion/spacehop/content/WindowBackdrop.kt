package tech.illusion.spacehop.content

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Vector3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ASSET_PATH = "kitchen_backdrop.png"

/** Source image is 1254x1254 (1:1) — see [ASPECT_RATIO] on why the plane's own height is derived
 * from this rather than a second hand-picked constant that could drift out of sync with the
 * actual file and silently stretch it. */
private const val SOURCE_WIDTH_PX = 1254f
private const val SOURCE_HEIGHT_PX = 1254f

/**
 * The window-mode-only kitchen backdrop: a plane textured with a single static image, standing in
 * for a real 3D environment behind the desk-scale sandbox. Not part of [rememberHopRun]/
 * [HopRun.buildScene] — the immersive Stage keeps its passthrough view of the player's real room
 * instead, so this only gets built from [WindowHome].
 *
 * Pattern lifted from `SpaceStack`'s `DesertBackdrop` (confirmed working on the same class of
 * volumetric window on real hardware): decode with `Dispatchers.IO`, `MeshResource.createPlane`,
 * `UnlitMaterial` + `TextureResource`, one static entity created once and never touched again.
 *
 * **Facing, per that same prior confirmation:** `MeshResource.createPlane`'s own KDoc states only
 * that the plane lies on the local X-Y plane, perpendicular to Z — not which side is the textured
 * face. The default winding faces +Z (this SDK's "toward the player" axis), so a backdrop sitting
 * at a *more negative* local Z than the sandbox's own content needs no extra rotation to face back
 * toward the viewer. [WindowScale.originOffsetZ] at the default window scale comes out positive
 * (~+0.16m — the sandbox sits toward the front), so a fixed, more-negative Z placed directly under
 * `content` (unscaled, NOT nested under the scaled `sceneRoot`) satisfies this without extra work.
 * If this ever renders blank/black instead, that assumption — not the texture — is the first
 * thing to re-check, on-device.
 */
object WindowBackdrop {

    /** Width ÷ height. Deriving the plane's own aspect from the source pixel dimensions, rather
     *  than hand-picking a height to go with a hand-picked width, means the two can never silently
     *  drift apart into a stretched image — if the asset file is ever replaced, this constant is
     *  the one thing that must be updated to match it. */
    const val ASPECT_RATIO: Float = SOURCE_WIDTH_PX / SOURCE_HEIGHT_PX

    /**
     * Decodes [ASSET_PATH] and builds the plane, sized to [widthM] wide (height follows from
     * [ASPECT_RATIO]) at [localOffset]. Returns the entity un-parented — the caller decides where
     * to attach it (in [WindowHome] this is `content.addEntity(...)`, deliberately NOT
     * `sceneRoot.addChild(...)`, so the backdrop stays at real-world scale instead of shrinking
     * along with the desk-scale sandbox).
     *
     * Runs the bitmap decode on [Dispatchers.IO] per `TextureResource.create`'s own KDoc
     * recommendation for non-trivial images, then returns to the caller's thread (main, since
     * `SpatialView.initial` is itself a suspend callback already on it) to touch ECS entities —
     * entity/component work must stay on the main thread, decoding does not have to.
     */
    suspend fun build(context: Context, localOffset: Vector3, widthM: Float): Entity {
        val bitmap = withContext(Dispatchers.IO) {
            context.assets.open(ASSET_PATH).use { stream ->
                val options = BitmapFactory.Options().apply {
                    // TextureResource.create only accepts ARGB_8888 or RGBA_F16 and throws
                    // IllegalArgumentException otherwise; decodeStream's default config is not
                    // guaranteed to be either (a HARDWARE bitmap on newer Android, for one), so
                    // this is requested explicitly rather than hoped for.
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeStream(stream, null, options)
                    ?: error("window backdrop: BitmapFactory could not decode $ASSET_PATH")
            }
        }

        val heightM = widthM / ASPECT_RATIO
        val mesh = MeshResource.createPlane(widthM, heightM)
        val material = UnlitMaterial.create(BlendingMode.OPAQUE).apply {
            setBaseColorTexture(TextureResource.create(bitmap))
        }
        val entity = ModelEntity(mesh, material)
        entity.components[TransformComponent::class.java]?.setPosition(localOffset)
        return entity
    }
}
