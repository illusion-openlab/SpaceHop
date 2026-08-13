package tech.illusion.spacehop.content

import com.pico.spatial.core.math.Vector3
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
 */
object PinchChargeController {

    private val handProvider = HandTrackingProvider()
    private var job: Job? = null

    private val leftStateMachine = PinchStateMachine()
    private val rightStateMachine = PinchStateMachine()
    private var chargingIsLeftHand: Boolean? = null
    private var chargeStartNanos: Long = 0L

    private val _chargeState = MutableStateFlow(ChargeUiState())
    val chargeState: StateFlow<ChargeUiState> = _chargeState

    private var onRelease: ((cappedHeldMillis: Long) -> Unit)? = null

    fun start(onRelease: (cappedHeldMillis: Long) -> Unit) {
        this.onRelease = onRelease
        handProvider.start()
        job = CoroutineScope(Dispatchers.Main).launch {
            handProvider.dataFlow.collect { data -> processFrame(data.left, data.right) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        handProvider.stop()
        chargingIsLeftHand = null
        _chargeState.value = ChargeUiState()
    }

    private fun processFrame(left: HandPose?, right: HandPose?) {
        val leftThumb = left?.joint(HandJoint.Index.THUMB_TIP)?.position ?: Vector3.ZERO
        val leftIndex = left?.joint(HandJoint.Index.INDEX_TIP)?.position ?: Vector3.ZERO
        val rightThumb = right?.joint(HandJoint.Index.THUMB_TIP)?.position ?: Vector3.ZERO
        val rightIndex = right?.joint(HandJoint.Index.INDEX_TIP)?.position ?: Vector3.ZERO

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

        val activeHand = chargingIsLeftHand
        val fraction = if (activeHand == null) 0f else {
            val heldMillis = (System.nanoTime() - chargeStartNanos) / 1_000_000L
            ChargeCurve.cappedChargeMillis(heldMillis).toFloat() / ChargeCurve.MAX_CHARGE_MILLIS
        }

        _chargeState.value = ChargeUiState(
            isCharging = activeHand != null,
            chargeFraction = fraction,
            positions = IndicatorPositions(leftThumb, leftIndex, rightThumb, rightIndex, activeHand),
        )
    }

    private fun beginCharge(isLeftHand: Boolean) {
        chargingIsLeftHand = isLeftHand
        chargeStartNanos = System.nanoTime()
    }

    private fun endCharge() {
        val heldMillis = (System.nanoTime() - chargeStartNanos) / 1_000_000L
        chargingIsLeftHand = null
        onRelease?.invoke(ChargeCurve.cappedChargeMillis(heldMillis))
    }
}
