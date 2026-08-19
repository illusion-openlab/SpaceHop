package tech.illusion.spacehop.content

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.controller.ControllerActionData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import com.pico.spatial.tracking.hand.HandJoint
import com.pico.spatial.tracking.hand.HandPose
import com.pico.spatial.tracking.hand.HandTrackingProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import tech.illusion.spacehop.game.ChargeCurve
import tech.illusion.spacehop.game.PinchEvent
import tech.illusion.spacehop.game.PinchGeometry
import tech.illusion.spacehop.game.PinchStateMachine

data class IndicatorPositions(
    val leftThumb: Vector3 = Vector3.ZERO,
    val leftIndex: Vector3 = Vector3.ZERO,
    val rightThumb: Vector3 = Vector3.ZERO,
    val rightIndex: Vector3 = Vector3.ZERO,
    val activeHandIsLeft: Boolean? = null, // null = neither hand is charging
    // False whenever that hand's HandPose was null this frame — [leftThumb]/[leftIndex] (or the
    // right pair) are meaningless Vector3.ZERO placeholders in that case, not a real position, so
    // IndicatorBallsRenderer hides that pair instead of drawing a ball at the placeholder (which
    // converts to a fixed, very-visible spot that happened to land on a platform — confirmed by
    // report). Defaults false: hidden until the first real frame of tracking data arrives.
    val leftTracked: Boolean = false,
    val rightTracked: Boolean = false,
)

data class ChargeUiState(
    val isCharging: Boolean = false,
    val chargeFraction: Float = 0f,
    val positions: IndicatorPositions = IndicatorPositions(),
)

/**
 * Polls both hands, feeds each hand's raw thumb/index distance into its own
 * [PinchStateMachine], and drives the charge timer from whichever hand starts pinching
 * first. The other hand's indicator balls stay idle-grey (design doc §二.1 update: reuse
 * `SpaceSnake`'s four-ball convention, only the active hand's pair recolors).
 *
 * V2: the controller trigger on either hand drives the exact same charge timer, as a second
 * input path for players holding controllers instead of bare-handed (design ask). Whichever
 * source — pinch or trigger — starts a charge is also the only one whose *matching* release
 * can end it (see the `chargingIsLeftHand` checks in both [processHandFrame] and
 * [onControllerAction]); the rest of the game only ever sees `chargingIsLeftHand`/
 * `chargeStartNanos`, so it never needs to know which input method was actually used.
 */
object PinchChargeController {

    private val handProvider = HandTrackingProvider()
    private val controllerProvider = ControllerTrackingProvider()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var job: Job? = null

    private val leftStateMachine = PinchStateMachine()
    private val rightStateMachine = PinchStateMachine()
    // Edge-triggered diagnostics only (not per-frame — this collects at hand-tracking data rate)
    // for the stray-indicator-ball investigation: logs the raw joint-lookup nullity whenever it
    // flips, so a still-reproducing report gives us the actual left/right/joint state at the
    // moment it happens instead of another blind guess.
    private var lastLoggedLeftTracked: Boolean? = null
    private var lastLoggedRightTracked: Boolean? = null
    private var chargingIsLeftHand: Boolean? = null
    private var chargeStartNanos: Long = 0L
    private var wasLeftTriggerPressed = false
    private var wasRightTriggerPressed = false
    // The four indicator balls only mean anything for hand-pinch input, so the controller path
    // reuses whichever positions hand tracking last reported (both pairs stay hidden via
    // leftTracked/rightTracked if it never ran this session — see IndicatorPositions) rather than
    // tracking its own — only chargeFraction/isCharging need to update while charging by trigger.
    private var lastHandPositions = IndicatorPositions()

    private val _chargeState = MutableStateFlow(ChargeUiState())
    val chargeState: StateFlow<ChargeUiState> = _chargeState

    private var onChargeStart: ((isLeftHand: Boolean) -> Unit)? = null
    private var onRelease: ((cappedHeldMillis: Long, isLeftHand: Boolean) -> Unit)? = null

    private val controllerActionListener =
        ControllerTrackingProvider.ControllerActionListener { action -> onControllerAction(action) }

    fun start(
        onChargeStart: (isLeftHand: Boolean) -> Unit,
        onRelease: (cappedHeldMillis: Long, isLeftHand: Boolean) -> Unit,
    ) {
        this.onChargeStart = onChargeStart
        this.onRelease = onRelease
        handProvider.start()
        controllerProvider.addControllerActionListener(controllerActionListener)
        controllerProvider.start()
        job = CoroutineScope(Dispatchers.Main).launch {
            handProvider.dataFlow.collect { data -> processHandFrame(data.left, data.right) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        handProvider.stop()
        controllerProvider.removeControllerActionListener(controllerActionListener)
        controllerProvider.stop()
        chargingIsLeftHand = null
        wasLeftTriggerPressed = false
        wasRightTriggerPressed = false
        _chargeState.value = ChargeUiState()
    }

    private fun processHandFrame(left: HandPose?, right: HandPose?) {
        val leftThumbJoint = left?.joint(HandJoint.Index.THUMB_TIP)?.position
        val leftIndexJoint = left?.joint(HandJoint.Index.INDEX_TIP)?.position
        val rightThumbJoint = right?.joint(HandJoint.Index.THUMB_TIP)?.position
        val rightIndexJoint = right?.joint(HandJoint.Index.INDEX_TIP)?.position
        val leftThumb = leftThumbJoint ?: Vector3.ZERO
        val leftIndex = leftIndexJoint ?: Vector3.ZERO
        val rightThumb = rightThumbJoint ?: Vector3.ZERO
        val rightIndex = rightIndexJoint ?: Vector3.ZERO

        val leftEvent = if (left != null) leftStateMachine.update(PinchGeometry.distanceMeters(leftThumb, leftIndex)) else null
        val rightEvent = if (right != null) rightStateMachine.update(PinchGeometry.distanceMeters(rightThumb, rightIndex)) else null

        when (chargingIsLeftHand) {
            null -> {
                if (leftEvent == PinchEvent.STARTED) beginCharge(isLeftHand = true)
                else if (rightEvent == PinchEvent.STARTED) beginCharge(isLeftHand = false)
            }
            true -> if (leftEvent == PinchEvent.ENDED) endCharge()
            false -> if (rightEvent == PinchEvent.ENDED) endCharge()
        }

        // Gated on the *joint* lookups, not just `left`/`right` being non-null: a HandPose can
        // exist while a specific joint still fails to resolve, which silently falls back to
        // Vector3.ZERO above without `left`/`right` ever being null — a prior version of this
        // check used only hand-level nullity and missed that fallback path (a stray indicator
        // ball still reproduced on-device after that fix shipped; unconfirmed whether this
        // joint-level check is the full fix or only a partial one — see the diagnostics below).
        val leftTracked = leftThumbJoint != null && leftIndexJoint != null
        val rightTracked = rightThumbJoint != null && rightIndexJoint != null
        if (leftTracked != lastLoggedLeftTracked || rightTracked != lastLoggedRightTracked) {
            Log.w(
                "SpaceHopHandTrack",
                "tracked changed: leftTracked=$leftTracked rightTracked=$rightTracked " +
                    "left=$left right=$right leftThumbJoint=$leftThumbJoint leftIndexJoint=$leftIndexJoint " +
                    "rightThumbJoint=$rightThumbJoint rightIndexJoint=$rightIndexJoint",
            )
            lastLoggedLeftTracked = leftTracked
            lastLoggedRightTracked = rightTracked
        }
        lastHandPositions = IndicatorPositions(
            leftThumb,
            leftIndex,
            rightThumb,
            rightIndex,
            leftTracked = leftTracked,
            rightTracked = rightTracked,
        )
        publishState()
    }

    /** Runs on the SDK's own controller data-source thread, not Main, and fires continuously
     * "at device output rate" (controller-tracking doc), not just on press/release — so edge
     * detection stays here (cheap, and this thread is the callback's only caller, so the two
     * `was*TriggerPressed` fields never need a lock), but every actual charge-state change is
     * posted back to Main so it can never race [processHandFrame]'s reads/writes of the same
     * `chargingIsLeftHand`/`chargeStartNanos` from the hand-tracking Flow's collector. Posting
     * unconditionally (not just on an edge) mirrors the hand-pinch path's own per-frame publish,
     * which is what keeps the charge-fraction squash animation live for the whole hold, not just
     * its start/end instants. */
    private fun onControllerAction(action: ControllerActionData) {
        val leftPressed = action.left.triggerPressed
        val rightPressed = action.right.triggerPressed
        val leftDownEdge = leftPressed && !wasLeftTriggerPressed
        val leftUpEdge = !leftPressed && wasLeftTriggerPressed
        val rightDownEdge = rightPressed && !wasRightTriggerPressed
        val rightUpEdge = !rightPressed && wasRightTriggerPressed
        wasLeftTriggerPressed = leftPressed
        wasRightTriggerPressed = rightPressed
        mainHandler.post {
            when (chargingIsLeftHand) {
                null -> {
                    if (leftDownEdge) beginCharge(isLeftHand = true)
                    else if (rightDownEdge) beginCharge(isLeftHand = false)
                }
                true -> if (leftUpEdge) endCharge()
                false -> if (rightUpEdge) endCharge()
            }
            publishState()
        }
    }

    private fun beginCharge(isLeftHand: Boolean) {
        chargingIsLeftHand = isLeftHand
        chargeStartNanos = System.nanoTime()
        onChargeStart?.invoke(isLeftHand)
    }

    private fun endCharge() {
        val heldMillis = (System.nanoTime() - chargeStartNanos) / 1_000_000L
        val isLeftHand = chargingIsLeftHand ?: return
        chargingIsLeftHand = null
        onRelease?.invoke(ChargeCurve.cappedChargeMillis(heldMillis), isLeftHand)
    }

    private fun publishState() {
        val activeHand = chargingIsLeftHand
        val fraction = if (activeHand == null) 0f else {
            val heldMillis = (System.nanoTime() - chargeStartNanos) / 1_000_000L
            ChargeCurve.cappedChargeMillis(heldMillis).toFloat() / ChargeCurve.MAX_CHARGE_MILLIS
        }
        _chargeState.value = ChargeUiState(
            isCharging = activeHand != null,
            chargeFraction = fraction,
            positions = lastHandPositions.copy(activeHandIsLeft = activeHand),
        )
    }
}
