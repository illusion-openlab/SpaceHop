package tech.illusion.spacehop.game

/** Linear green→red interpolation for the pinch indicator balls, keyed by charge fraction
 * (0f..1f). Pure so it is unit-testable without touching the material APIs that consume it
 * (`content/IndicatorBallsRenderer.kt`). */
object ChargeIndicatorColor {

    fun forFraction(fraction: Float): FloatArray {
        val t = fraction.coerceIn(0f, 1f)
        return floatArrayOf(t, 1f - t, 0f)
    }
}
