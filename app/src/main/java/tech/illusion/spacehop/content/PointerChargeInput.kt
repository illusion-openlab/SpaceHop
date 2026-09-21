package tech.illusion.spacehop.content

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tech.illusion.spacehop.game.ChargeCurve

/**
 * 窗口态（WindowContainer）的蓄力输入：按住 3D 视图蓄力，松手起跳。
 *
 * 存在的理由：`HandTrackingProvider` / `ControllerTrackingProvider` / `HMDTrackingProvider` 全部
 * 带 `@RequiredFullSpace`，在 WindowContainer 下 `start()` 只返回 `PENDING` 且 `dataFlow` 永不
 * 发射。窗口里拿不到任何追踪数据，所以蓄力必须改由 Compose 的指针事件驱动。
 *
 * 本类不接触 Compose 也不接触 SDK——手势层只在 `onPress` / `tryAwaitRelease` 时调
 * [onPressDown] / [onPressUp]。[nowNanos] 注入时间源，是为了让按住时长的计算能在 JVM 上被精确
 * 单测，而不是靠 `Thread.sleep` 碰运气。
 *
 * 蓄力封顶用的是和捏合输入完全相同的 [ChargeCurve.MAX_CHARGE_MILLIS]，两个容器的手感因此一致、
 * 分数可比——这是「同一局跨容器」能够成立的前提。
 */
class PointerChargeInput(
    private val nowNanos: () -> Long = System::nanoTime,
) : ChargeInput {

    private val _chargeState = MutableStateFlow(ChargeUiState())
    override val chargeState: StateFlow<ChargeUiState> = _chargeState

    private var onChargeStartCallback: ((ChargeHand?) -> Unit)? = null
    private var onReleaseCallback: ((Long, ChargeHand?) -> Unit)? = null

    /** 按下时刻；null 表示当前没有按住。 */
    private var pressStartNanos: Long? = null

    override fun start(
        onChargeStart: (ChargeHand?) -> Unit,
        onRelease: (cappedHeldMillis: Long, hand: ChargeHand?) -> Unit,
    ) {
        onChargeStartCallback = onChargeStart
        onReleaseCallback = onRelease
    }

    override fun stop() {
        onChargeStartCallback = null
        onReleaseCallback = null
        pressStartNanos = null
        _chargeState.value = ChargeUiState()
    }

    /** Compose 手势层的 `onPress` 调这里。 */
    fun onPressDown() {
        if (onReleaseCallback == null) return // 已 stop，输入源应当是哑的
        pressStartNanos = nowNanos()
        _chargeState.value = ChargeUiState(isCharging = true, chargeFraction = 0f)
        // 窗口态的 pointer 事件给不出左右手：SpatialPointerInfo 有 kind 和 inputDevicePose，
        // 但没有左右手信息，而 HandController 只有 Left/Right/Unknown。如实报 null。
        onChargeStartCallback?.invoke(null)
    }

    /** Compose 手势层的 `tryAwaitRelease()` 返回后调这里。 */
    fun onPressUp() {
        val startNanos = pressStartNanos ?: return // 没按下过就松手（射线扫过场景）——忽略
        pressStartNanos = null
        val heldMillis = (nowNanos() - startNanos) / 1_000_000L
        _chargeState.value = ChargeUiState()
        onReleaseCallback?.invoke(ChargeCurve.cappedChargeMillis(heldMillis), null)
    }

    /** 每帧调一次，把按住进度推进到 [ChargeUiState.chargeFraction] 上供蓄力条读取。
     * 没按住时是空操作。 */
    fun tickChargeFraction() {
        val startNanos = pressStartNanos ?: return
        val heldMillis = (nowNanos() - startNanos) / 1_000_000L
        val fraction = (heldMillis.toFloat() / ChargeCurve.MAX_CHARGE_MILLIS).coerceIn(0f, 1f)
        _chargeState.value = ChargeUiState(isCharging = true, chargeFraction = fraction)
    }
}
