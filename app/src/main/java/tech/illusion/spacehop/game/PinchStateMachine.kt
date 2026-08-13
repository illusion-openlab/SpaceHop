package tech.illusion.spacehop.game

enum class PinchEvent { STARTED, ENDED }

/**
 * Hysteresis (separate enter/exit thresholds) + N-consistent-frame debounce over raw
 * thumb-index distance. Neither this workspace's `SpaceSnake` (spec'd but never built) nor
 * `PortalCanToss` (single fixed threshold, no debounce) has shipped this combination yet —
 * SpaceHop needs it because charge duration is timed off the pinch start/end edges, and a
 * single noisy frame flipping state mid-charge would corrupt the timer.
 *
 * Not thread-safe; call [update] from a single collector coroutine (see
 * `content/PinchChargeController.kt`).
 */
class PinchStateMachine(
    private val enterThresholdMeters: Float = 0.025f,
    private val exitThresholdMeters: Float = 0.045f,
    private val requiredConsistentFrames: Int = 3,
) {
    private var pinching = false
    private var candidateState = false
    private var candidateFrameCount = 0

    fun isPinching(): Boolean = pinching

    /** Feed one frame's thumb-index distance. Returns the event that fired this frame, if any. */
    fun update(distanceMeters: Float): PinchEvent? {
        val instantState = when {
            distanceMeters <= enterThresholdMeters -> true
            distanceMeters >= exitThresholdMeters -> false
            else -> pinching // inside the hysteresis band: hold the current committed state
        }

        if (instantState == pinching) {
            candidateFrameCount = 0
            candidateState = pinching
            return null
        }

        if (instantState == candidateState) {
            candidateFrameCount++
        } else {
            candidateState = instantState
            candidateFrameCount = 1
        }

        if (candidateFrameCount >= requiredConsistentFrames) {
            pinching = instantState
            candidateFrameCount = 0
            return if (pinching) PinchEvent.STARTED else PinchEvent.ENDED
        }
        return null
    }
}
