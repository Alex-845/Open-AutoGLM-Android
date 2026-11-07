package com.example.open_autoglm_android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateSpaceTest {

    @Test
    fun center_mapsOnto1080x2640Screenshot() {
        val mapped = CoordinateSpace.mapToScreen(500f, 500f, 1080, 2640)
        val point = (mapped as CoordinateSpace.Mapping.OnScreen).point
        assertEquals(540f, point.x, 0.01f)
        assertEquals(1320f, point.y, 0.01f)
    }

    @Test
    fun fullScale_staysOnTheLastPixel() {
        val mapped = CoordinateSpace.mapToScreen(1000f, 1000f, 1080, 2640)
        val point = (mapped as CoordinateSpace.Mapping.OnScreen).point
        assertEquals(1079f, point.x, 0.01f)
        assertEquals(2639f, point.y, 0.01f)
    }

    @Test
    fun observedPointAbove1000_isRejectedRatherThanScaledOffScreen() {
        val mapped = CoordinateSpace.mapToScreen(147f, 1015f, 1080, 2640)
        assertTrue(mapped is CoordinateSpace.Mapping.OutOfRange)
        val message = CoordinateSpace.outOfRangeMessage(147f, 1015f)
        assertTrue(message.startsWith(CoordinateSpace.OUT_OF_RANGE_PREFIX))
        assertTrue(message.contains("1015"))
        assertFalse(message.contains("156"))
    }

    @Test
    fun negativeCoordinate_isRejected() {
        assertTrue(CoordinateSpace.mapToScreen(-1f, 10f, 1080, 2640) is CoordinateSpace.Mapping.OutOfRange)
    }

    @Test
    fun twoSimilarTapsAreAllowedAndTheThirdIsBlocked() {
        val first = emptyList<Pair<Int, Int>>()
        assertFalse(CoordinateSpace.isRepeatedTap(first, 147, 800))
        val once = listOf(147 to 800)
        assertFalse(CoordinateSpace.isRepeatedTap(once, 136, 810))
        val twice = listOf(147 to 800, 136 to 810)
        assertTrue(CoordinateSpace.isRepeatedTap(twice, 140, 805))
        assertFalse(CoordinateSpace.isRepeatedTap(twice, 800, 200))
    }
}
