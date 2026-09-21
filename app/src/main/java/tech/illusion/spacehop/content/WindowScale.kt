package tech.illusion.spacehop.content

import tech.illusion.spacehop.game.DifficultyCurve
import tech.illusion.spacehop.game.PlatformSpawner

/**
 * 把玩法几何（以米为单位、固定不变）装进窗口体积里的缩放计算。
 *
 * **缩放只作用于视觉根实体，玩法的米数一律不变。** `DifficultyCurve` / `PlatformSpawner` /
 * `LandingJudge` / `ScoreRules` 读到的永远是同样的数字，所以窗口里和沉浸里的同一局判定完全
 * 一致、分数可比，现有的玩法单测也继续有效。
 *
 * **已知例外：窗口穿越那一跳装不下。** 下面的尺寸覆盖的是*常规*回合——最远一跳受
 * [DifficultyCurve.MAX_DISTANCE_CEILING_M] 封顶。而 `StageBoard.advance` 生成的窗口穿越腿是
 * 把「跳到窗口」和「跳进荷塘」融成一跳的复合距离，最坏情况可以逼近
 * `2 * MAX_DISTANCE_CEILING_M`，明显超出 [SCENE_DEPTH_M] 的预算。
 *
 * 这里**有意不去覆盖它**：把 [SCENE_DEPTH_M] 翻倍会把沙盘缩放砍掉近一半，而 ~0.55× 的
 * 「桌面摆件」观感是用户在设计阶段从三个方案里明确选定的——精度与观感之间的取舍不该在实现
 * 里被悄悄改掉。实际后果也不是崩：实体只是被裁剪到 SpatialView 的包围盒，所以症状是
 * **每局那一次穿越跳时青蛙可能短暂穿帮出框**，不是异常、不是黑屏。真要改，先回到设计层。
 */
object WindowScale {

    /** 最大的平台半径。跟着 `DifficultyCurve` 的起始值走——它只会随难度变小，不会变大。
     *
     * 公开是因为它同时是场景原点在深度轴上的**内缩量**：见 [originOffsetZ]。 */
    const val MAX_FOOTPRINT_RADIUS_M = 0.09f

    /** 场景前后要占的米数：最远的**常规**一跳，加上两端平台各自的半径。
     *
     * 窗口穿越那一跳不在预算内，见本类的类级注释。 */
    const val SCENE_DEPTH_M = DifficultyCurve.MAX_DISTANCE_CEILING_M + 2f * MAX_FOOTPRINT_RADIUS_M

    /** 场景左右要占的米数：最大横向偏移加平台半径，左右各一份。 */
    const val SCENE_WIDTH_M = 2f * (PlatformSpawner.MAX_LATERAL_OFFSET_M + MAX_FOOTPRINT_RADIUS_M)

    /** 场景上下要占的米数：跳跃弧高加青蛙自身高度，留一点余量。
     *
     * 这一段**整个在原点之上**（地面是场景 y=0，跳跃弧往上走），所以摆位时不能把它当成
     * 以原点为中心的区间——见 [originOffsetY]。 */
    const val SCENE_HEIGHT_M = 0.25f

    /** 原点平面之下仍要留出的米数。落地判定读的是平台**顶面**（`HopRun.CHARACTER_GROUND_Y`
     * = 0，`StageBoardAnimator` 把每块平台的顶面对齐到自己 wrapper 的原点），平台本体是往下
     * 长的。按一个平台半径预算，和深度轴两端各留一份半径是同一套口径。 */
    const val SCENE_BELOW_GROUND_M = MAX_FOOTPRINT_RADIUS_M

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

    /**
     * 场景根实体在深度轴上的位置（盒心为 0，+Z 朝向观察者）。
     *
     * 场景原点是**当前**那块平台的圆心，不是场景的前表面：[SCENE_DEPTH_M] 在原点前面还预算了
     * 一整个 [MAX_FOOTPRINT_RADIUS_M]（当前平台自己的半径）。按 `SCENE_DEPTH_M / 2` 摆就等于
     * 把原点当成前表面用，整个场景会往观察者方向多推一个半径——默认窗口下是 51mm，足够让当前
     * 平台的前沿（+0.258m）捅出 +0.225m 的盒面，同时后面白白空掉 69mm。
     *
     * 实体自身的 position 在**父空间**里，不受自己的 scale 影响，所以这里要乘 [scale]。
     */
    fun originOffsetZ(scale: Float): Float = scale * (SCENE_DEPTH_M / 2f - MAX_FOOTPRINT_RADIUS_M)

    /**
     * 场景根实体在高度轴上的位置（盒心为 0，+Y 朝上）。
     *
     * 地面坐到盒子的底面附近，而不是把 [SCENE_HEIGHT_M] 以盒心居中：manifest 里
     * `volumebasepanel=0`（底板可见）就是冲着「底板即沙盘的桌面、青蛙站在上面」去的，居中会让
     * 整个沙盘悬在底板上方十几厘米。高度轴离约束边界很远（默认窗口下 `byHeight` = 1.6，远大于
     * 起作用的 `byDepth` = 0.616），把地面压到底完全有余量。
     *
     * 留出的那点余量是 [SCENE_BELOW_GROUND_M]：平台本体从顶面往下长，贴死底面会把它们切掉。
     *
     * @param halfHeightM 实测的盒子半高（米）。还没测量到（<= 0）时退回盒心，不猜。
     */
    fun originOffsetY(halfHeightM: Float, scale: Float): Float {
        if (halfHeightM <= 0f) return 0f
        return -halfHeightM + SCENE_BELOW_GROUND_M * scale
    }
}
