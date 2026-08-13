package tech.illusion.spacehop.content

import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.math.Color4
import tech.illusion.spacehop.game.ChargeIndicatorColor

private const val BALL_RADIUS_M = 0.01f

/** Four small spheres — left/right × thumb/index — reusing `SpaceSnake`'s indicator-ball
 * convention. Only the currently-charging hand's pair recolors green→red; the other pair
 * stays idle grey. */
class IndicatorBallsRenderer {

    private lateinit var leftThumbBall: ModelEntity
    private lateinit var leftIndexBall: ModelEntity
    private lateinit var rightThumbBall: ModelEntity
    private lateinit var rightIndexBall: ModelEntity
    private lateinit var leftThumbMat: PhysicallyBasedMaterial
    private lateinit var leftIndexMat: PhysicallyBasedMaterial
    private lateinit var rightThumbMat: PhysicallyBasedMaterial
    private lateinit var rightIndexMat: PhysicallyBasedMaterial

    fun build(content: SpatialViewContent) {
        val mesh: MeshResource = MeshResource.createSphere(BALL_RADIUS_M)
        leftThumbMat = idleMaterial(); leftIndexMat = idleMaterial()
        rightThumbMat = idleMaterial(); rightIndexMat = idleMaterial()
        leftThumbBall = ModelEntity(mesh, leftThumbMat)
        leftIndexBall = ModelEntity(mesh, leftIndexMat)
        rightThumbBall = ModelEntity(mesh, rightThumbMat)
        rightIndexBall = ModelEntity(mesh, rightIndexMat)
        listOf(leftThumbBall, leftIndexBall, rightThumbBall, rightIndexBall).forEach { content.addEntity(it) }
    }

    private fun idleMaterial(): PhysicallyBasedMaterial = PhysicallyBasedMaterial.create().apply {
        setBaseColor(StagePalette.color(StagePalette.INDICATOR_IDLE))
        setMetallic(0f)
        setRoughness(0.5f)
    }

    /** Call every frame with the latest [ChargeUiState]. World positions, not stage-anchored —
     * hand tracking data is already in world space. */
    fun update(state: ChargeUiState) {
        leftThumbBall.components[TransformComponent::class.java]?.setPosition(state.positions.leftThumb)
        leftIndexBall.components[TransformComponent::class.java]?.setPosition(state.positions.leftIndex)
        rightThumbBall.components[TransformComponent::class.java]?.setPosition(state.positions.rightThumb)
        rightIndexBall.components[TransformComponent::class.java]?.setPosition(state.positions.rightIndex)

        val activeIsLeft = state.positions.activeHandIsLeft
        val rgb = ChargeIndicatorColor.forFraction(state.chargeFraction)
        val chargingColor = Color4(rgb[0], rgb[1], rgb[2], 1f)
        val idleColor = StagePalette.color(StagePalette.INDICATOR_IDLE)

        leftThumbMat.setBaseColor(if (activeIsLeft == true) chargingColor else idleColor)
        leftIndexMat.setBaseColor(if (activeIsLeft == true) chargingColor else idleColor)
        rightThumbMat.setBaseColor(if (activeIsLeft == false) chargingColor else idleColor)
        rightIndexMat.setBaseColor(if (activeIsLeft == false) chargingColor else idleColor)
    }

    fun dispose(content: SpatialViewContent) {
        listOf(leftThumbBall, leftIndexBall, rightThumbBall, rightIndexBall).forEach { content.removeEntity(it) }
    }
}
