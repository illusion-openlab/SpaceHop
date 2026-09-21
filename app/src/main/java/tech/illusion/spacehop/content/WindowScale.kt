package tech.illusion.spacehop.content

import tech.illusion.spacehop.game.DifficultyCurve
import tech.illusion.spacehop.game.PlatformSpawner

/**
 * 把玩法几何（以米为单位、固定不变）装进窗口体积里的缩放计算。
 *
 * **缩放只作用于视觉根实体，玩法的米数一律不变。** `DifficultyCurve` / `PlatformSpawner` /
 * `LandingJudge` / `ScoreRules` 读到的永远是同样的数字，所以窗口里和沉浸里的同一局判定完全
 * 一致、分数可比，现有的玩法单测也继续有效。
 */
object WindowScale {

    /** 最大的平台半径。跟着 `DifficultyCurve` 的起始值走——它只会随难度变小，不会变大。 */
    private const val MAX_FOOTPRINT_RADIUS_M = 0.09f

    /** 场景前后要占的米数：最远的一跳，加上两端平台各自的半径。 */
    const val SCENE_DEPTH_M = DifficultyCurve.MAX_DISTANCE_CEILING_M + 2f * MAX_FOOTPRINT_RADIUS_M

    /** 场景左右要占的米数：最大横向偏移加平台半径，左右各一份。 */
    const val SCENE_WIDTH_M = 2f * (PlatformSpawner.MAX_LATERAL_OFFSET_M + MAX_FOOTPRINT_RADIUS_M)

    /** 场景上下要占的米数：跳跃弧高加青蛙自身高度，留一点余量。 */
    const val SCENE_HEIGHT_M = 0.25f

    /** 视图还没测量出来（首帧半径为 0）时的兜底缩放。返回 0 会让整个沙盘不可见，
     * 看上去像模型加载失败而不是还没测量。取值与默认窗口下算出来的结果同量级，
     * 所以测量到达之前的那一两帧不会出现肉眼可见的跳变。 */
    private const val UNMEASURED_FALLBACK_SCALE = 0.55f

    /** 留给包围盒边缘的余量比例——实体是被裁剪到 **SpatialView 的** 包围盒（不是
     * WindowContainer 的），贴边的平台会被切掉一半。 */
    private const val MARGIN = 0.92f

    /**
     * 把场景等比缩放到刚好装进给定的半径尺寸里。三个轴取最小值，哪个轴最紧哪个轴说了算。
     *
     * 窗口是用户可等比缩放的（manifest 里是 `resizerestriction=1`，UniformResizable），所以这个
     * 值要在 `onGloballyPositioned` 重新测量后重算——但玩法的米数不跟着变。
     *
     * 默认窗口 0.5 × 0.4 × 0.45m 下，深度是最紧的一轴（场景要占 [SCENE_DEPTH_M] = 0.73m，
     * 盒子只有 0.45m），算出来约 0.567×——即用户在设计阶段选定的 ~0.55× 沙盘观感。
     */
    fun fitScale(halfWidthM: Float, halfHeightM: Float, halfDepthM: Float): Float {
        if (halfWidthM <= 0f || halfHeightM <= 0f || halfDepthM <= 0f) {
            return UNMEASURED_FALLBACK_SCALE
        }
        val byWidth = (halfWidthM * 2f) / SCENE_WIDTH_M
        val byHeight = (halfHeightM * 2f) / SCENE_HEIGHT_M
        val byDepth = (halfDepthM * 2f) / SCENE_DEPTH_M
        return minOf(byWidth, byHeight, byDepth) * MARGIN
    }
}
