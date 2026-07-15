package dev.dblink.core.rawai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RawCameraColorMatrixPolicyTest {
    @Test
    fun om5MarkIiUsesExplicitOm5FallbackForRankZeroDecoderMatrix() {
        val result = RawCameraColorMatrixPolicy.resolve("OM-5MarkII      ", DoubleArray(12))
        assertTrue(result.usedFallback)
        assertEquals("OM-5", result.effectiveModel)
        assertEquals("LibRaw_0.22.0_colordata_OM-5_project_alias", result.source)
        assertArrayEquals(
            doubleArrayOf(1.1896, -.5110, -.1076, -.3181, 1.1378, .2048, -.0519, .1224, .5166),
            result.matrix3x3,
            0.0,
        )
    }

    @Test
    fun validDecoderMatrixWinsForNonAliasedCamera() {
        val matrix = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val result = RawCameraColorMatrixPolicy.resolve("OM-1", matrix)
        assertFalse(result.usedFallback)
        assertArrayEquals(matrix, result.matrix3x3, 0.0)
    }

    @Test
    fun unknownRankZeroCameraIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            RawCameraColorMatrixPolicy.resolve("Unknown Camera", DoubleArray(12))
        }
    }
}
