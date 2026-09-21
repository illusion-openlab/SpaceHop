package tech.illusion.spacehop.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.illusion.spacehop.game.ChargeCurve

class PointerChargeInputTest {

    private class FakeClock(var nanos: Long = 0L) : () -> Long {
        override fun invoke(): Long = nanos
        fun advanceMs(ms: Long) { nanos += ms * 1_000_000L }
    }

    @Test
    fun heldDurationBecomesCappedChargeMillis() {
        val clock = FakeClock()
        val input = PointerChargeInput(nowNanos = clock)
        var released: Long? = null
        input.start(onChargeStart = {}, onRelease = { ms, _ -> released = ms })

        input.onPressDown()
        clock.advanceMs(500L)
        input.onPressUp()

        assertEquals(500L, released)
    }

    /** 按太久必须封顶，和捏合输入用的是同一个 ChargeCurve 上限。 */
    @Test
    fun overlongHoldIsCappedAtMaxChargeMillis() {
        val clock = FakeClock()
        val input = PointerChargeInput(nowNanos = clock)
        var released: Long? = null
        input.start(onChargeStart = {}, onRelease = { ms, _ -> released = ms })

        input.onPressDown()
        clock.advanceMs(ChargeCurve.MAX_CHARGE_MILLIS + 5_000L)
        input.onPressUp()

        assertEquals(ChargeCurve.MAX_CHARGE_MILLIS, released)
    }

    /** 窗口态的 pointer 事件给不出左右手，必须如实报 null 而不是瞎猜一只。 */
    @Test
    fun handIsAlwaysNullBecausePointerEventsCannotTellLeftFromRight() {
        val input = PointerChargeInput(nowNanos = FakeClock())
        var startHand: ChargeHand? = ChargeHand.LEFT
        var releaseHand: ChargeHand? = ChargeHand.LEFT
        input.start(
            onChargeStart = { startHand = it },
            onRelease = { _, hand -> releaseHand = hand },
        )

        input.onPressDown()
        input.onPressUp()

        assertEquals(null, startHand)
        assertEquals(null, releaseHand)
    }

    /** 没按下就松手不能产生一次跳跃——射线扫过场景不该让青蛙起跳。 */
    @Test
    fun releaseWithoutPressIsIgnored() {
        val input = PointerChargeInput(nowNanos = FakeClock())
        var releaseCount = 0
        input.start(onChargeStart = {}, onRelease = { _, _ -> releaseCount++ })

        input.onPressUp()

        assertEquals(0, releaseCount)
    }

    /** 蓄力条要有东西可画：按住期间 chargeState 必须反映进度并在 1.0 封顶。 */
    @Test
    fun chargeFractionRampsToOneAndStops() {
        val clock = FakeClock()
        val input = PointerChargeInput(nowNanos = clock)
        input.start(onChargeStart = {}, onRelease = { _, _ -> })

        assertFalse(input.chargeState.value.isCharging)

        input.onPressDown()
        clock.advanceMs(ChargeCurve.MAX_CHARGE_MILLIS / 2)
        input.tickChargeFraction()
        assertTrue(input.chargeState.value.isCharging)
        assertEquals(0.5f, input.chargeState.value.chargeFraction, 0.02f)

        clock.advanceMs(ChargeCurve.MAX_CHARGE_MILLIS * 3)
        input.tickChargeFraction()
        assertEquals(1f, input.chargeState.value.chargeFraction, 0.001f)

        input.onPressUp()
        assertFalse(input.chargeState.value.isCharging)
        assertEquals(0f, input.chargeState.value.chargeFraction, 0.001f)
    }

    /** stop() 之后不该再有回调——容器切走时输入源必须真的哑掉。 */
    @Test
    fun stopSilencesCallbacks() {
        val clock = FakeClock()
        val input = PointerChargeInput(nowNanos = clock)
        var releaseCount = 0
        input.start(onChargeStart = {}, onRelease = { _, _ -> releaseCount++ })

        input.stop()
        input.onPressDown()
        assertFalse(input.chargeState.value.isCharging)
        clock.advanceMs(300L)
        input.onPressUp()

        assertEquals(0, releaseCount)
    }
}
