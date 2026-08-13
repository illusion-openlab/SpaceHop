package tech.illusion.spacehop.game

import com.pico.spatial.core.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Test

class PinchGeometryTest {

    @Test
    fun distanceMeters_returnsEuclideanDistance() {
        val thumb = Vector3(0f, 0f, 0f)
        val index = Vector3(0.03f, 0.04f, 0f)
        assertEquals(0.05f, PinchGeometry.distanceMeters(thumb, index), 0.0001f)
    }

    @Test
    fun midpoint_returnsAveragePosition() {
        val thumb = Vector3(0f, 0f, 0f)
        val index = Vector3(0.02f, 0.04f, 0.06f)
        val mid = PinchGeometry.midpoint(thumb, index)
        assertEquals(0.01f, mid.x, 0.0001f)
        assertEquals(0.02f, mid.y, 0.0001f)
        assertEquals(0.03f, mid.z, 0.0001f)
    }
}
