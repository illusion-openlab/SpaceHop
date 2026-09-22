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
import tech.illusion.spacehop.R

/** Source image is 1254x1254 (1:1), same as [WindowBackdrop]'s — see that object's own constant
 *  for why this is derived from pixels rather than hand-picked again here. */
private const val SOURCE_WIDTH_PX = 1254f
private const val SOURCE_HEIGHT_PX = 1254f

/**
 * The window-mode-only curtain frame: a plane with a transparent middle and opaque curtains down
 * each side, sitting *between* the sandbox game and the Augment UI popups — from back to front the
 * intended stack is now kitchen backdrop ([WindowBackdrop]) → game (`sceneRoot`) → this curtain →
 * UI (per user's explicit "厨房背景层-游戏主体-窗帘层-UI层" ordering, replacing the earlier attempt
 * that baked the curtain into the bottom Augment's own background — that put it at the *same*
 * depth as the UI text instead of framing the game behind it, which read poorly on-device).
 *
 * Unlike [WindowBackdrop], the source PNG lives in `res/drawable` (already used as a 2D
 * `Image` before this rework), not `assets` — decoded with [BitmapFactory.decodeResource] instead
 * of an `AssetManager` stream, everything else follows the same proven pattern.
 *
 * **Alpha, not opaque:** the transparent middle must let the game show through, so this uses
 * [BlendingMode.TRANSPARENT] (mirrors `StageMaterials.perfectZone` in `StageVisuals.kt`) with
 * `setDepthWrite(false)` — same reasoning as that material: a transparent plane that still *writes*
 * depth would occupy the depth buffer across its fully-transparent centre too, incorrectly
 * occluding the game behind it despite nothing visible being drawn there. Depth *testing* stays on,
 * so the opaque game content in front of this plane's Z still correctly draws over it.
 */
object WindowCurtain {

    /** Width ÷ height — 1:1, see the source pixel constants above. */
    const val ASPECT_RATIO: Float = SOURCE_WIDTH_PX / SOURCE_HEIGHT_PX

    /**
     * Decodes the curtain drawable and builds the plane, sized to [widthM] wide (height follows
     * [ASPECT_RATIO]) at [localOffset]. Returns the entity un-parented — same contract as
     * [WindowBackdrop.build]: the caller attaches it to `content` directly, NOT under the scaled
     * `sceneRoot`, so it stays at real-world scale.
     */
    suspend fun build(context: Context, localOffset: Vector3, widthM: Float): Entity {
        val bitmap = withContext(Dispatchers.IO) {
            val options = BitmapFactory.Options().apply {
                // Same TextureResource.create constraint as WindowBackdrop: must be ARGB_8888 or
                // RGBA_F16, and this source's alpha channel (the transparent middle) requires one
                // of those anyway — RGB_565 would silently drop it.
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeResource(
                context.resources,
                R.drawable.window_curtain_backdrop,
                options,
            ) ?: error("window curtain: BitmapFactory could not decode window_curtain_backdrop")
        }

        val heightM = widthM / ASPECT_RATIO
        val mesh = MeshResource.createPlane(widthM, heightM)
        val material = UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
            setBaseColorTexture(TextureResource.create(bitmap))
            setDepthWrite(false)
        }
        val entity = ModelEntity(mesh, material)
        entity.components[TransformComponent::class.java]?.setPosition(localOffset)
        return entity
    }
}
