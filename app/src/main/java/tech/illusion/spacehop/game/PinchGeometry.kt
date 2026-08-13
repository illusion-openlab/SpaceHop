package tech.illusion.spacehop.game

import com.pico.spatial.core.math.Vector3

/** Pure pinch geometry — independent reimplementation of the same shape as
 * `PortalCanToss/core/GraspDetector.kt` (this workspace's convention: borrow the
 * conclusion, not the code, across independent projects). */
object PinchGeometry {

    fun distanceMeters(thumbTip: Vector3, indexTip: Vector3): Float =
        Vector3.distance(thumbTip, indexTip)

    fun midpoint(thumbTip: Vector3, indexTip: Vector3): Vector3 =
        (thumbTip + indexTip) * 0.5f
}
