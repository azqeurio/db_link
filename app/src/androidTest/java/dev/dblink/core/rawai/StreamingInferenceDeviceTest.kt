package dev.dblink.core.rawai

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamingInferenceDeviceTest {
    @Test
    fun realFp32ModelMatchesFullBufferWithBoundedReconstruction() {
        val width = 513
        val height = 513
        val input = LinearRgbImage(width, height, FloatArray(width * height * 3) { index ->
            val pixel = index / 3
            val x = pixel % width
            val y = pixel / width
            val channel = index % 3
            ((x * 17 + y * 31 + channel * 47) % 1021) / 1020f
        })
        val config = TilingConfig(overlap = 32, padding = TilePadding.REFLECT)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val heapBefore = usedHeap()
        var heapAfterFull = heapBefore
        var heapAfterStreaming = heapBefore

        RawAiTestSession(
            context,
            "model_fp32.tflite",
            "0efe3fd811cb8691e6347021fbb147fd81282952145274460d1238da58715806",
            ModelPrecision.FP32,
        ).use { session ->
            val processor = TileProcessor { _, tile, output -> session.run(tile, 0f, output) }
            val full = FullImageInferenceEngine().process(input, config, processor)
            heapAfterFull = usedHeap()
            val streamed = FloatArray(input.data.size)
            val streaming = StreamingFullImageInferenceEngine().process(
                input,
                config,
                processor,
                StreamingRowSink { rowIndex, row ->
                    row.copyInto(streamed, rowIndex * width * 3)
                },
            )
            heapAfterStreaming = usedHeap()
            val difference = Pr3Metrics.difference(full.image.data, streamed)
            println(
                "PR4_STREAMING_DEVICE width=$width height=$height tiles=${streaming.plan.totalTiles} " +
                    "max=${difference.maximum} mean=${difference.mean} rmse=${difference.rmse} " +
                    "rows=${streaming.rowsEmitted} peakRows=${streaming.peakRetainedRows} " +
                    "estimatedStreaming=${streaming.estimatedReconstructionBytes} " +
                    "estimatedFull=${FullImageInferenceEngine().estimateWorkingBytes(width, height)} " +
                    "heapBefore=$heapBefore heapAfterFull=$heapAfterFull heapAfterStreaming=$heapAfterStreaming " +
                    "nativeHeap=${Debug.getNativeHeapAllocatedSize()} totalMs=${streaming.timings.totalMillis}",
            )
            assertEquals(0.0, difference.maximum, 0.0)
            assertEquals(height, streaming.rowsEmitted)
            assertTrue(streaming.peakRetainedRows <= config.tileSize)
            assertTrue(
                streaming.estimatedReconstructionBytes <
                    FullImageInferenceEngine().estimateWorkingBytes(width, height),
            )
        }
    }

    private fun usedHeap(): Long = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
}
