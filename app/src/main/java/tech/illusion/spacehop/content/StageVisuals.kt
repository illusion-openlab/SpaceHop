package tech.illusion.spacehop.content

import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4

/**
 * Original palette — deliberately not matching WeChat "跳一跳"'s character/platform art
 * (design doc copyright boundary, header + §三). 3D entity materials are PBR/Unlit and do
 * not go through `PicoTheme` — hardcoded hex here is correct, mirrors
 * `SpaceSnake/content/SnakeVisuals.kt`'s `SnakePalette`.
 */
object StagePalette {
    const val PERFECT_ZONE = 0xFFD966
    const val TRAIL = 0xFFE9B0
    const val INDICATOR_IDLE = 0x8492AB

    fun color(hex: Int, opacity: Float = 1f): Color4 = Color4(
        ((hex shr 16) and 0xFF) / 255f,
        ((hex shr 8) and 0xFF) / 255f,
        (hex and 0xFF) / 255f,
        opacity,
    )
}

/**
 * `perfectZone` is a single shared instance (created lazily) — `SpaceSnake` ANR'd once from
 * building ~26 near-duplicate materials synchronously inside `SpatialView`'s `initial`, so
 * nothing here is built per-frame or per-jump. Platform and character visuals are now loaded
 * models (see `StageBoardAnimator`/`CharacterController`), not procedural meshes/materials.
 */
class StageMaterials {

    val perfectZone: Material by lazy {
        UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
            setBaseColor(StagePalette.color(StagePalette.PERFECT_ZONE, opacity = 0.85f))
            setDepthWrite(false)
        }
    }
}

/** Mesh factories for content still procedurally generated. */
object StageMeshes {

    private const val PLATFORM_HEIGHT_M = 0.03f

    fun perfectZoneMesh(radiusMeters: Float): MeshResource =
        MeshResource.createCylinder(radius = radiusMeters, height = PLATFORM_HEIGHT_M * 1.2f)
}
