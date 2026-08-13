package tech.illusion.spacehop.game

/**
 * Converts pinch-hold duration into launch distance. Ease-out quadratic: early hold time
 * buys more distance than late hold time, which is what gives players fine control near
 * the short end and forgiving control near the long end (design doc §2.1).
 */
object ChargeCurve {

    const val MAX_CHARGE_MILLIS = 1_200L

    fun cappedChargeMillis(rawHeldMillis: Long): Long =
        rawHeldMillis.coerceIn(0L, MAX_CHARGE_MILLIS)

    fun launchDistanceMeters(cappedHeldMillis: Long, minDistance: Float, maxDistance: Float): Float {
        val t = cappedHeldMillis.toFloat() / MAX_CHARGE_MILLIS
        val eased = 1f - (1f - t) * (1f - t)
        return minDistance + eased * (maxDistance - minDistance)
    }
}
