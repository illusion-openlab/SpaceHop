package tech.illusion.spacehop.content

import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4

/**
 * Original palette — deliberately not matching WeChat "跳一跳"'s character/platform art
 * (design doc copyright boundary, header + §三). 3D entity materials are PBR/Unlit and do
 * not go through `PicoTheme` — hardcoded hex here is correct, mirrors
 * `SpaceSnake/content/SnakeVisuals.kt`'s `SnakePalette`.
 */
object StagePalette {
    const val PLATFORM_TOP = 0x8ED1FF
    const val PERFECT_ZONE = 0xFFD966
    const val CHARACTER_BODY = 0xFF9F6B
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
 * `perfectZone`/`character` are single shared instances (created lazily) — `SpaceSnake` ANR'd
 * once from building ~26 near-duplicate materials synchronously inside `SpatialView`'s
 * `initial`, so nothing here is built per-frame or per-jump.
 *
 * Platforms are the one exception: [newPlatformMaterial] is a factory, not a shared instance,
 * because Task 8's advance transition needs each of the two platform entities to fade
 * independently (shared material = both platforms would fade together, which is wrong — only
 * one of them fades at a time). This still only ever creates exactly two instances for the
 * whole game (both up front, in `StageBoardAnimator.build`), so it does not reintroduce the
 * ANR risk. There is no separate "side" material — a single `PhysicallyBasedMaterial` on the
 * cylinder already reads as two-tone because PBR shading responds to each face's normal
 * direction, so the top cap and the side wall naturally render at different brightness without
 * needing a second material.
 */
class StageMaterials {

    fun newPlatformMaterial(): PhysicallyBasedMaterial =
        PhysicallyBasedMaterial.create().apply {
            setBaseColor(StagePalette.color(StagePalette.PLATFORM_TOP))
            setMetallic(0f)
            setRoughness(0.5f)
            // FADE (not TRANSPARENT) so `setOpacity` can animate 0..1 for the materialize
            // transition — exact FADE-vs-TRANSPARENT rendering difference is unverified in
            // this workspace beyond the enum existing; confirm the look on-device in Task 8's
            // Gate B round and swap to TRANSPARENT there if FADE doesn't read as expected.
            setBlendingMode(BlendingMode.FADE)
        }

    val perfectZone: Material by lazy {
        UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
            setBaseColor(StagePalette.color(StagePalette.PERFECT_ZONE, opacity = 0.85f))
            setDepthWrite(false)
        }
    }
    val character: Material by lazy {
        PhysicallyBasedMaterial.create().apply {
            setBaseColor(StagePalette.color(StagePalette.CHARACTER_BODY))
            setMetallic(0f)
            setRoughness(0.45f)
        }
    }
}

/** Mesh factories — platforms are short cylinders, the character is a plain sphere (squash
 * handled by non-uniform scale in `CharacterController`, not by a separate mesh). */
object StageMeshes {

    private const val PLATFORM_HEIGHT_M = 0.03f

    fun platformMesh(radiusMeters: Float): MeshResource =
        MeshResource.createCylinder(radius = radiusMeters, height = PLATFORM_HEIGHT_M)

    fun perfectZoneMesh(radiusMeters: Float): MeshResource =
        MeshResource.createCylinder(radius = radiusMeters, height = PLATFORM_HEIGHT_M * 1.2f)

    fun characterMesh(radiusMeters: Float): MeshResource = MeshResource.createSphere(radiusMeters)
}
