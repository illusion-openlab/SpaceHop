package tech.illusion.spacehop.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.illusion.spacehop.game.DifficultyCurve
import tech.illusion.spacehop.game.PlatformSpawner

class WindowScaleTest {

    /** 场景占用的尺寸必须从玩法常量推导，不能是手填的数字——难度曲线一调，
     * 沙盘就会悄悄超出窗口被裁掉。 */
    @Test
    fun sceneFootprintIsDerivedFromGameplayConstants() {
        val expectedDepth = DifficultyCurve.MAX_DISTANCE_CEILING_M + 2f * 0.09f
        val expectedWidth = 2f * (PlatformSpawner.MAX_LATERAL_OFFSET_M + 0.09f)
        assertEquals(expectedDepth, WindowScale.SCENE_DEPTH_M, 0.0001f)
        assertEquals(expectedWidth, WindowScale.SCENE_WIDTH_M, 0.0001f)
    }

    /** 默认窗口（0.5 × 0.4 × 0.45m）下缩放应落在 0.55 附近——这是用户定的沙盘观感。 */
    @Test
    fun defaultWindowYieldsTheAgreedSandboxScale() {
        val scale = WindowScale.fitScale(
            halfWidthM = 0.5f / 2f,
            halfHeightM = 0.4f / 2f,
            halfDepthM = 0.45f / 2f,
        )
        assertEquals(0.55f, scale, 0.06f)
    }

    /** 缩放后的场景必须真的装得进盒子，否则最远的平台会被 SpatialView 的包围盒裁掉。 */
    @Test
    fun scaledSceneFitsInsideTheViewBox() {
        val halfDepth = 0.45f / 2f
        val halfWidth = 0.5f / 2f
        val scale = WindowScale.fitScale(halfWidth, 0.4f / 2f, halfDepth)
        assertTrue(
            "缩放后深度 ${WindowScale.SCENE_DEPTH_M * scale} 必须 <= 盒深 ${halfDepth * 2f}",
            WindowScale.SCENE_DEPTH_M * scale <= halfDepth * 2f,
        )
        assertTrue(
            "缩放后宽度 ${WindowScale.SCENE_WIDTH_M * scale} 必须 <= 盒宽 ${halfWidth * 2f}",
            WindowScale.SCENE_WIDTH_M * scale <= halfWidth * 2f,
        )
    }

    /** 窗口被用户缩小时缩放必须跟着变小，而不是让沙盘穿帮出界。 */
    @Test
    fun shrinkingTheWindowShrinksTheSandbox() {
        val big = WindowScale.fitScale(0.25f, 0.20f, 0.225f)
        val small = WindowScale.fitScale(0.125f, 0.10f, 0.1125f)
        assertTrue("窗口减半，缩放必须跟着减小：big=$big small=$small", small < big)
    }

    /** 还没测量到尺寸时（首帧 halfExtent 为 0）不能返回 0 或负数缩放——
     * 缩放 0 会让整个沙盘不可见，看上去像模型加载失败。 */
    @Test
    fun unmeasuredViewFallsBackToAPositiveScale() {
        val scale = WindowScale.fitScale(0f, 0f, 0f)
        assertTrue("首帧未测量时必须给一个正的兜底缩放，实际 $scale", scale > 0f)
    }

    /** 场景原点是当前那块平台的**圆心**，不是场景的前表面——`SCENE_DEPTH_M` 在原点前面
     * 还预算了一整个平台半径。这条把公式钉死，防止退回 `SCENE_DEPTH_M / 2` 的写法。 */
    @Test
    fun originOffsetZBacksOffByOneFootprintRadius() {
        assertEquals(
            WindowScale.SCENE_DEPTH_M / 2f - WindowScale.MAX_FOOTPRINT_RADIUS_M,
            WindowScale.originOffsetZ(1f),
            0.0001f,
        )
    }

    /** 任何正缩放下原点都必须比场景半深更靠里。按前表面摆（`SCENE_DEPTH_M / 2`）会让这条挂掉。 */
    @Test
    fun originOffsetZNeverSitsOnTheSceneFrontFace() {
        for (scale in floatArrayOf(0.1f, 0.283f, 0.567f, 1f)) {
            val z = WindowScale.originOffsetZ(scale)
            assertTrue(
                "scale=$scale 时原点 $z 必须 < 场景半深 ${WindowScale.SCENE_DEPTH_M / 2f}",
                z < WindowScale.SCENE_DEPTH_M / 2f,
            )
        }
    }

    /** 真正要成立的不变式：摆好之后，当前平台的前沿不能捅出盒子的前表面。
     * 这正是修掉的那个 Critical——原来的公式在默认窗口下会超出 33mm。 */
    @Test
    fun currentPlatformStaysInsideTheBoxFrontFace() {
        val halfDepth = 0.45f / 2f
        val scale = WindowScale.fitScale(0.5f / 2f, 0.4f / 2f, halfDepth)
        val frontEdge = WindowScale.originOffsetZ(scale) + WindowScale.MAX_FOOTPRINT_RADIUS_M * scale
        assertTrue(
            "当前平台前沿 $frontEdge 必须 <= 盒子前表面 $halfDepth",
            frontEdge <= halfDepth,
        )
    }

    /** 地面要坐到底板附近，而不是悬在盒子的垂直中心——manifest 的 `volumebasepanel=0` 就是
     * 冲着「底板即桌面」去的。留出的余量正好是平台本体往下长的那一段。 */
    @Test
    fun groundSeatsNearTheBoxFloorInsteadOfItsCentre() {
        val halfHeight = 0.4f / 2f
        val scale = WindowScale.fitScale(0.5f / 2f, halfHeight, 0.45f / 2f)
        val groundY = WindowScale.originOffsetY(halfHeight, scale)
        assertTrue("地面 $groundY 必须在盒底 ${-halfHeight} 之上", groundY > -halfHeight)
        assertEquals(
            -halfHeight + WindowScale.SCENE_BELOW_GROUND_M * scale,
            groundY,
            0.0001f,
        )
        // 居中摆法（-SCENE_HEIGHT_M * scale / 2）会把地面留在约 -0.071m，离盒底 0.13m。
        assertTrue(
            "地面 $groundY 必须明显低于居中摆法的结果",
            groundY < -(WindowScale.SCENE_HEIGHT_M * scale) / 2f,
        )
    }

    /** 还没测量到半高时不能拿 0 当盒底用——那会把地面钉在盒心而不是退回一个中性值。 */
    @Test
    fun unmeasuredHeightLeavesTheSceneAtTheBoxCentre() {
        assertEquals(0f, WindowScale.originOffsetY(0f, 0.55f), 0.0001f)
    }
}
