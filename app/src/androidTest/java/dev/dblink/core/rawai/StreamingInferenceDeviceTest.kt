package dev.dblink.core.rawai

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class StreamingInferenceDeviceTest {
    @Test
    fun fullResolutionExternalRawForgeTensor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext
        val inputPath = File(requireNotNull(arguments.getString("inputPath")))
        val modelKey = requireNotNull(arguments.getString("model"))
        require(modelKey == "standard" || modelKey == "superlight")
        val width = arguments.getString("width")?.toInt() ?: 5240
        val height = arguments.getString("height")?.toInt() ?: 3912
        val iso = arguments.getString("iso")?.toFloat() ?: 6400f
        val expectedBytes = width.toLong() * height * 3L * Float.SIZE_BYTES
        require(inputPath.length() == expectedBytes) {
            "Full-resolution input mismatch: expected=$expectedBytes actual=${inputPath.length()}"
        }
        val manifest = JSONObject(context.assets.open("raw_ai/model_manifest.json").bufferedReader().use { it.readText() })
        val modelInfo = manifest.getJSONObject("model_files").getJSONObject(modelKey)
        val heapBefore = usedHeap()
        val input = readLinearRgb(inputPath, width, height)
        val heapAfterInput = usedHeap()
        val output = File(context.filesDir, "raw_ai/phase5_full/${modelKey}_${width}x$height.f32le.bin")
        output.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        val condition = RawAiCondition.fromIso(iso)
        val config = TilingConfig(overlap = 16, padding = TilePadding.REPLICATE)
        val rowBytes = ByteBuffer.allocate(width * 3 * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val started = System.nanoTime()
        val result = RawAiTestSession(
            context, modelInfo.getString("path"), modelInfo.getString("sha256"), ModelPrecision.FP32, 4,
        ).use { session ->
            output.outputStream().buffered(1024 * 1024).use { sink ->
                StreamingFullImageInferenceEngine().process(
                    input,
                    config,
                    TileProcessor { _, tile, modelOutput -> session.run(tile, condition, modelOutput) },
                    StreamingRowSink { _, row ->
                        rowBytes.clear()
                        row.forEach(rowBytes::putFloat)
                        val array = rowBytes.array()
                        sink.write(array)
                        digest.update(array)
                    },
                    progress = { progress ->
                        if (progress.completedTiles % 25 == 0) {
                            println("PHASE5_FULL_PROGRESS model=$modelKey tiles=${progress.completedTiles}/${progress.totalTiles}")
                        }
                    },
                )
            }
        }
        val elapsedMs = (System.nanoTime() - started) / 1e6
        val outputSha = digest.digest().joinToString("") { "%02x".format(it) }
        println(
            "PHASE5_FULL_RESULT model=$modelKey width=$width height=$height iso=$iso condition=$condition " +
                "tiles=${result.plan.totalTiles} rows=${result.rowsEmitted} outputBytes=${output.length()} " +
                "outputSha256=$outputSha elapsedMs=$elapsedMs inferenceMs=${result.timings.inferenceMillis} " +
                "peakRows=${result.peakRetainedRows} estimatedReconstruction=${result.estimatedReconstructionBytes} " +
                "heapBefore=$heapBefore heapAfterInput=$heapAfterInput heapAfter=${usedHeap()} nativeHeap=${Debug.getNativeHeapAllocatedSize()}",
        )
        assertEquals(expectedBytes, output.length())
        assertEquals(height, result.rowsEmitted)
        assertEquals(374, result.plan.totalTiles)
        assertTrue(result.peakRetainedRows <= config.tileSize)
    }

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
            "rawforge_superlight_fp32.tflite",
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

    private fun readLinearRgb(file: File, width: Int, height: Int): LinearRgbImage {
        val values = FloatArray(LinearRgbImage.checkedElements(width, height))
        val chunk = ByteArray(1024 * 1024)
        var offset = 0
        file.inputStream().buffered(chunk.size).use { input ->
            while (offset < values.size) {
                val requested = minOf(chunk.size, (values.size - offset) * Float.SIZE_BYTES)
                var count = 0
                while (count < requested) {
                    val read = input.read(chunk, count, requested - count)
                    check(read > 0) { "Unexpected EOF at float $offset" }
                    count += read
                }
                val buffer = ByteBuffer.wrap(chunk, 0, count).order(ByteOrder.LITTLE_ENDIAN)
                while (buffer.remaining() >= Float.SIZE_BYTES) values[offset++] = buffer.float
            }
            check(input.read() == -1) { "Trailing bytes in ${file.absolutePath}" }
        }
        return LinearRgbImage(width, height, values)
    }
}
