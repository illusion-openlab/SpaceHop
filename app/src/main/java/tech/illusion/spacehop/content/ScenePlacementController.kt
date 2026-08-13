package tech.illusion.spacehop.content

/** Pure clamp — the only genuinely testable slice of scene placement. Scale drag itself
 * needs live two-hand tracking data, unverifiable off-device (same limitation as pinch
 * charging, Task 7). Live move/rotate/scale gesture glue is not implemented in this pass —
 * deliberately deferred as a fast-follow after Gate B, since it can't be verified without a
 * real device anyway and every other feature already competes for that same real-device time. */
object ScenePlacementLimits {
    const val MIN_SCALE = 0.6f
    const val MAX_SCALE = 1.6f

    fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)
}
