package tech.illusion.spacehop.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargeHandTest {

    /** 沉浸态的捏合输入知道是哪只手；窗口态的 pointer 输入不知道（SpatialPointerInfo 给不出
     * 左右手），所以这个映射必须显式接受 null。 */
    @Test
    fun booleanHandMapsToChargeHand() {
        assertEquals(ChargeHand.LEFT, ChargeHand.fromIsLeft(true))
        assertEquals(ChargeHand.RIGHT, ChargeHand.fromIsLeft(false))
        assertNull(ChargeHand.fromIsLeft(null))
    }
}
