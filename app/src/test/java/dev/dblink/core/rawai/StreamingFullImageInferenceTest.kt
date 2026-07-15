package dev.dblink.core.rawai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingFullImageInferenceTest {
    private val config = TilingConfig(tileSize = 256, overlap = 16, padding = TilePadding.REPLICATE)

    @Test
    fun streamingMatchesFullBufferOracleForRequiredDimensions() {
        val sizes = listOf(256 to 256, 257 to 257, 512 to 512, 513 to 513, 1024 to 768, 1920 to 1080)
        sizes.forEach { (width, height) ->
            val input = synthetic(width, height)
            val oracle = FullImageInferenceEngine().process(input, config, SyntheticTileProcessors.identity).image
            val streamed = FloatArray(input.data.size)
            val result = StreamingFullImageInferenceEngine().process(
                input,
                config,
                SyntheticTileProcessors.identity,
                StreamingRowSink { row, values -> values.copyInto(streamed, row * width * 3) },
            )
            assertArrayEquals("${width}x$height", oracle.data, streamed, 0f)
            assertEquals(height, result.rowsEmitted)
            assertTrue(result.peakRetainedRows <= config.tileSize)
        }
    }

    @Test
    fun edgeSensitiveProcessorPreservesExactAccumulationOrder() {
        val input = synthetic(513, 513)
        val oracle = FullImageInferenceEngine().process(input, config, SyntheticTileProcessors.edgeSensitive).image
        val streamed = FloatArray(input.data.size)
        StreamingFullImageInferenceEngine().process(
            input,
            config,
            SyntheticTileProcessors.edgeSensitive,
            StreamingRowSink { row, values -> values.copyInto(streamed, row * input.width * 3) },
        )
        assertArrayEquals(oracle.data, streamed, 0f)
    }

    @Test
    fun cancellationStopsBeforeCompletionAndDoesNotEmitAllRows() {
        val input = synthetic(1024, 768)
        var calls = 0
        var emitted = 0
        val phases = mutableListOf<InferencePhase>()
        val processor = TileProcessor { tile, tileInput, output ->
            calls++
            SyntheticTileProcessors.identity.process(tile, tileInput, output)
        }
        val failure = runCatching {
            StreamingFullImageInferenceEngine().process(
                input,
                config,
                processor,
                StreamingRowSink { _, _ -> emitted++ },
                CancellationSignal { calls >= 2 },
            ) { phases += it.phase }
        }.exceptionOrNull()
        assertTrue(failure is FullImageCancellationException)
        assertEquals(2, calls)
        assertTrue(emitted < input.height)
        assertTrue(InferencePhase.CANCELLED in phases)
        assertFalse(InferencePhase.COMPLETED in phases)
    }

    @Test
    fun reconstructionMemoryIsIndependentOfImageHeight() {
        val engine = StreamingFullImageInferenceEngine()
        assertEquals(engine.estimateReconstructionBytes(4032), engine.estimateReconstructionBytes(4032))
        assertEquals(18_087_936L, engine.estimateReconstructionBytes(4032))
        assertEquals(22_806_528L, engine.estimateReconstructionBytes(5184))
    }

    private fun synthetic(width: Int, height: Int): LinearRgbImage {
        val data = FloatArray(LinearRgbImage.checkedElements(width, height))
        for (y in 0 until height) for (x in 0 until width) for (channel in 0..2) {
            data[(y * width + x) * 3 + channel] = ((x * 17L + y * 31L + channel * 7L) % 1021L).toFloat() / 1020f
        }
        return LinearRgbImage(width, height, data)
    }
}
