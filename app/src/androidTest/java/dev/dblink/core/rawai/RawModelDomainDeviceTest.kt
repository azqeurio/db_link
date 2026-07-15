package dev.dblink.core.rawai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class RawModelDomainDeviceTest {
    @Test
    fun visibleBayerPipelineMatchesPythonReference() {
        val arguments = InstrumentationRegistry.getArguments()
        val raw = File(requireNotNull(arguments.getString("rawPath")))
        val reference = File(requireNotNull(arguments.getString("rawForgeReference")))
        val cropX = requireNotNull(arguments.getString("cropX")).toInt()
        val cropY = requireNotNull(arguments.getString("cropY")).toInt()
        val width = arguments.getString("cropWidth")?.toInt() ?: 256
        val height = arguments.getString("cropHeight")?.toInt() ?: 256
        check(raw.isFile && reference.isFile)

        val extracted = RawDomainDebugNative.extractBayer(raw.absolutePath, cropX, cropY, width, height)
        val matrix = RawCameraColorMatrixPolicy.resolve(extracted.cameraModel, extracted.rgbXyzMatrix)
        val actual = RawModelDomainPreprocessor.preprocess(
            extracted.mosaic,
            RawModelDomainMetadata(
                width = extracted.width,
                height = extracted.height,
                cfaPattern = extracted.cfaPattern,
                blackLevels = extracted.blackLevels,
                whiteLevel = extracted.whiteLevel,
                rgbXyzMatrix = matrix.matrix3x3,
            ),
        )
        val expected = readFloat32(reference, actual.values.size)
        var maximum = 0.0
        var sum = 0.0
        var squares = 0.0
        expected.indices.forEach { index ->
            val error = abs(expected[index] - actual.values[index]).toDouble()
            maximum = maxOf(maximum, error)
            sum += error
            squares += error * error
        }
        val mean = sum / expected.size
        val rmse = sqrt(squares / expected.size)
        println(
            "PR5_BAYER_PARITY sample=${raw.name} sourceModel=${extracted.cameraModel} " +
                "effectiveModel=${matrix.effectiveModel} fallback=${matrix.usedFallback} " +
                "max=$maximum mean=$mean rmse=$rmse libraw=${extracted.libRawVersion} " +
                "black=${extracted.blackLevels.contentToString()} white=${extracted.whiteLevel} " +
                "mosaicRange=${extracted.mosaic.min()}..${extracted.mosaic.max()} " +
                "expectedMean=${expected.average()} actualMean=${actual.values.average()}",
        )

        assertEquals("RGGB", extracted.cfaPattern)
        assertEquals(width, actual.width)
        assertEquals(height, actual.height)
        assertTrue(actual.values.all(Float::isFinite))
        // The two environments use different LibRaw releases; raw mosaic decoding should still
        // be stable, while this bound catches CFA, matrix, normalization, and Malvar mismatches.
        assertTrue("Python/Android model-domain RMSE too high: $rmse", rmse <= 1e-4)
        assertTrue("Python/Android model-domain max error too high: $maximum", maximum <= 2e-3)
    }

    private fun readFloat32(file: File, count: Int): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size == count * Float.SIZE_BYTES)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(count) { buffer.float }
    }
}
