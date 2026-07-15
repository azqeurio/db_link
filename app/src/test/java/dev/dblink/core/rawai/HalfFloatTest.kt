package dev.dblink.core.rawai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HalfFloatTest {
    @Test
    fun knownBinary16EncodingsAreCorrect() {
        assertBits(0x0000, 0.0f)
        assertBits(0x8000, -0.0f)
        assertBits(0x3c00, 1.0f)
        assertBits(0xbc00, -1.0f)
        assertBits(0x3800, 0.5f)
        assertBits(0x7bff, 65504.0f)
        assertBits(0x0400, 6.103515625e-5f)
        assertBits(0x0001, 5.9604645e-8f)
        assertBits(0x7c00, Float.POSITIVE_INFINITY)
        assertBits(0xfc00, Float.NEGATIVE_INFINITY)
        assertEquals(0.0f.toRawBits(), HalfFloat.toFloat(0x0000.toShort()).toRawBits())
        assertEquals((-0.0f).toRawBits(), HalfFloat.toFloat(0x8000.toShort()).toRawBits())
    }

    @Test
    fun nanInfinitySubnormalAndRoundTripSemanticsArePreserved() {
        assertTrue(HalfFloat.toFloat(HalfFloat.fromFloat(Float.NaN)).isNaN())
        assertEquals(Float.POSITIVE_INFINITY, HalfFloat.toFloat(HalfFloat.fromFloat(Float.POSITIVE_INFINITY)))
        assertEquals(Float.NEGATIVE_INFINITY, HalfFloat.toFloat(HalfFloat.fromFloat(Float.NEGATIVE_INFINITY)))
        for (value in listOf(0f, -0f, 1f, -1f, 0.5f, 65504f, 6.103515625e-5f, 5.9604645e-8f)) {
            assertEquals(value, HalfFloat.toFloat(HalfFloat.fromFloat(value)), 0f)
        }
    }

    private fun assertBits(expected: Int, value: Float) {
        assertEquals(expected, HalfFloat.fromFloat(value).toInt() and 0xffff)
        if (!value.isNaN()) assertEquals(value, HalfFloat.toFloat(expected.toShort()), 0f)
    }
}
