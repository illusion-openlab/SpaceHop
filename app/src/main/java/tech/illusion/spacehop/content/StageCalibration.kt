package tech.illusion.spacehop.content

import com.pico.spatial.core.math.Vector3

/**
 * Stage anchor position, in front of and below the calibrated head height — "microscopically
 * downward gaze at a chest-height table" per design doc §二.4/三. No rotation is applied:
 * this SDK's `EulerAngles` component order is unverified in this workspace (see
 * `SpaceSnake/AGENTS.md`), so height is lowered instead of the board being tilted.
 */
object StageCalibration {

    const val NOMINAL_EYE_HEIGHT_M = 1.15f
    const val CHEST_HEIGHT_OFFSET_M = -0.35f
    const val BOARD_DISTANCE_M = 0.9f

    const val CALIBRATION_POLLS = 60
    const val CALIBRATION_INTERVAL_MS = 50L

    /** A seated adult's eye height on the low end, a tall standing adult on the high end.
     * Starter bound, not measured — tighten or loosen after real-device calibration data
     * comes in, same tuning status as the difficulty-curve constants. */
    const val PLAUSIBLE_HEAD_HEIGHT_MIN_M = 0.9f
    const val PLAUSIBLE_HEAD_HEIGHT_MAX_M = 2.0f

    /** Guards against trusting a headset pose that is merely non-zero but still not a
     * believable eye height (unconverged tracking, a room/floor calibration error). Returns
     * false for exactly [Vector3.ZERO] too, since 0f is outside the plausible range. */
    fun isPlausibleHeadHeight(headYMeters: Float): Boolean =
        headYMeters in PLAUSIBLE_HEAD_HEIGHT_MIN_M..PLAUSIBLE_HEAD_HEIGHT_MAX_M

    fun boardPosition(headHeightAdjustM: Float): Vector3 = Vector3(
        0f,
        NOMINAL_EYE_HEIGHT_M + headHeightAdjustM + CHEST_HEIGHT_OFFSET_M,
        -BOARD_DISTANCE_M,
    )
}
