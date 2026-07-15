package dev.dblink.core.rawai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class FullImageInferenceTest {
    @Test
    fun plannerCoversRequiredDimensionsWithoutDuplicateOrigins() {
        val dimensions = listOf(
            1 to 1, 64 to 64, 255 to 255, 256 to 256, 257 to 256, 256 to 257,
            257 to 257, 511 to 513, 512 to 512, 513 to 513, 1024 to 768, 4032 to 3024,
        )
        for ((width, height) in dimensions) {
            for (overlap in listOf(0, 16, 32, 64)) {
                val plan = TilePlanner.create(width, height, TilingConfig(overlap = overlap))
                plan.validate()
                assertEquals(width, plan.imageWidth)
                assertEquals(height, plan.imageHeight)
                assertEquals(plan.totalTiles, plan.tiles.map { it.originX to it.originY }.toSet().size)
            }
        }
    }

    @Test
    fun identityReconstructionPreservesArbitraryHwcImages() {
        val engine = FullImageInferenceEngine()
        for ((width, height) in listOf(1 to 1, 64 to 31, 255 to 257, 256 to 256, 511 to 513)) {
            val input = synthetic(width, height)
            for (padding in listOf(TilePadding.REFLECT, TilePadding.REPLICATE, TilePadding.ZERO)) {
                for (overlap in listOf(0, 16, 32)) {
                    val result = engine.process(input, TilingConfig(overlap = overlap, padding = padding), SyntheticTileProcessors.identity)
                    assertEquals(input.width, result.image.width)
                    assertEquals(input.height, result.image.height)
                    val tolerance = if (overlap == 0) 0f else 2e-7f
                    assertArrayEquals("${width}x$height overlap=$overlap padding=$padding", input.data, result.image.data, tolerance)
                }
            }
        }
    }

    @Test
    fun paddingModesHaveExpectedEdgeValues() {
        val image = LinearRgbImage(2, 1, floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f))
        val tile = TilePlanner.create(2, 1, TilingConfig()).tiles.single()
        val reflect = FloatArray(256 * 256 * 3)
        val replicate = FloatArray(reflect.size)
        val zero = FloatArray(reflect.size)
        TileExtractor.extract(image, tile, TilingConfig(padding = TilePadding.REFLECT), reflect)
        TileExtractor.extract(image, tile, TilingConfig(padding = TilePadding.REPLICATE), replicate)
        TileExtractor.extract(image, tile, TilingConfig(padding = TilePadding.ZERO), zero)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), reflect.copyOfRange(6, 9), 0f)
        assertArrayEquals(floatArrayOf(4f, 5f, 6f), replicate.copyOfRange(6, 9), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), zero.copyOfRange(6, 9), 0f)
    }

    @Test
    fun blendWindowIsPositiveAndConstantTilesNormalizeExactly() {
        for (overlap in listOf(16, 32, 64)) {
            for (local in 0 until 256) {
                assertTrue(BlendWindow.weight(local, 256, 1, 3, overlap) > 0f)
            }
        }
        val result = FullImageInferenceEngine().process(
            synthetic(513, 511), TilingConfig(overlap = 32), SyntheticTileProcessors.constant(0.375f),
        )
        result.image.data.forEach { assertEquals(0.375f, it, 1e-7f) }
    }

    @Test
    fun localCoordinateProcessorExposesCorrectTilePlacement() {
        val output = FullImageInferenceEngine().process(
            synthetic(257, 257), TilingConfig(overlap = 0), SyntheticTileProcessors.localCoordinates,
        ).image
        assertEquals(255f, output[255, 0, 0], 0f)
        assertEquals(0f, output[256, 0, 0], 0f)
        assertEquals(255f, output[0, 255, 1], 0f)
        assertEquals(0f, output[0, 256, 1], 0f)
    }

    @Test
    fun progressIsMonotonicAndCompletionFollowsFinalization() {
        val events = mutableListOf<InferenceProgress>()
        FullImageInferenceEngine().process(
            synthetic(513, 257), TilingConfig(overlap = 32), SyntheticTileProcessors.identity,
            progress = events::add,
        )
        assertTrue(events.isNotEmpty())
        events.zipWithNext().forEach { (a, b) -> assertTrue("$a -> $b", b.fraction >= a.fraction) }
        assertTrue(events.all { it.fraction in 0.0..1.0 })
        assertEquals(InferencePhase.FINALIZING, events[events.lastIndex - 1].phase)
        assertEquals(InferencePhase.COMPLETED, events.last().phase)
        assertEquals(1.0, events.last().fraction, 0.0)
    }

    @Test
    fun cancellationStopsAtKnownTileAndNeverCompletes() {
        val processed = AtomicInteger()
        val cancelled = AtomicBoolean(false)
        val phases = mutableListOf<InferencePhase>()
        val processor = TileProcessor { _, input, output ->
            SyntheticTileProcessors.identity.process(TileRegion(0, 0, 0, 0, 0, 256, 256, 0, 0), input, output)
            if (processed.incrementAndGet() == 2) cancelled.set(true)
        }
        assertThrows(FullImageCancellationException::class.java) {
            FullImageInferenceEngine().process(
                synthetic(768, 256), TilingConfig(overlap = 0), processor,
                CancellationSignal(cancelled::get),
            ) { phases += it.phase }
        }
        assertEquals(2, processed.get())
        assertTrue(InferencePhase.CANCELLED in phases)
        assertFalse(InferencePhase.COMPLETED in phases)
    }

    @Test
    fun memoryLimitRejectsOversizedWorkBeforeAllocation() {
        val input = synthetic(64, 64)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            FullImageInferenceEngine(maximumWorkingBytes = 1).process(input, TilingConfig(), SyntheticTileProcessors.identity)
        }
        assertTrue(failure.message.orEmpty().contains("working set"))
    }

    @Test
    fun seamAnalyzerReportsConfiguredBoundaryCounts() {
        val input = synthetic(513, 513)
        val result = FullImageInferenceEngine().process(input, TilingConfig(overlap = 32), SyntheticTileProcessors.identity)
        val metrics = SeamAnalyzer.analyze(result.image, result.plan)
        assertEquals(result.plan.tileCountX - 1, metrics.verticalSeamCount)
        assertEquals(result.plan.tileCountY - 1, metrics.horizontalSeamCount)
        assertTrue(metrics.maximumBoundaryJump.isFinite())
        assertTrue(metrics.seamToInteriorRatio.isFinite())
    }

    @Test
    fun edgeSensitiveProcessorQuantifiesOverlapEffect() {
        val input = synthetic(513, 513)
        val engine = FullImageInferenceEngine()
        val baseline = engine.process(input, TilingConfig(overlap = 0), SyntheticTileProcessors.edgeSensitive)
        val blended = engine.process(input, TilingConfig(overlap = 32), SyntheticTileProcessors.edgeSensitive)
        val baselineSeams = SeamAnalyzer.analyze(baseline.image, baseline.plan)
        val blendedSeams = SeamAnalyzer.analyze(blended.image, blended.plan)
        assertTrue(baselineSeams.maximumBoundaryJump.isFinite())
        assertTrue(blendedSeams.maximumBoundaryJump.isFinite())
        assertTrue(blendedSeams.boundaryRmse <= baselineSeams.boundaryRmse)
    }

    private fun synthetic(width: Int, height: Int): LinearRgbImage {
        val data = FloatArray(width * height * 3)
        for (y in 0 until height) for (x in 0 until width) for (channel in 0..2) {
            data[(y * width + x) * 3 + channel] = ((x * 17 + y * 31 + channel * 13) % 997) / 996f
        }
        return LinearRgbImage(width, height, data)
    }
}
