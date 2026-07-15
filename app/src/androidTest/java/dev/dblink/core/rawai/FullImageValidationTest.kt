package dev.dblink.core.rawai

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class FullImageValidationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext get() = instrumentation.targetContext

    @Test
    fun singleTileEquivalenceAndConditionSensitivity() {
        val input = referenceInput()
        fp32Session().use { session ->
            val direct = FloatArray(TILE_ELEMENTS)
            session.run(input.data, 0f, direct)
            val result = FullImageInferenceEngine().process(
                input, TilingConfig(overlap = 0), TileProcessor { _, tile, output -> session.run(tile, 0f, output) },
            )
            val fullNchw = hwcToNchw(result.image.data)
            val equivalence = Pr3Metrics.difference(direct, fullNchw)
            println("PR3_SINGLE_TILE max=${equivalence.maximum} mean=${equivalence.mean} rmse=${equivalence.rmse} index=${equivalence.largestIndex}")
            assertEquals(0.0, equivalence.maximum, 0.0)

            val baseline = direct
            for (condition in listOf(-1f, 0f, 0.25f, 0.5f, 1f)) {
                val output = FloatArray(TILE_ELEMENTS)
                session.run(input.data, condition, output)
                val image = Pr3Metrics.summarize(nchwToHwc(output))
                val difference = Pr3Metrics.difference(baseline, output)
                println("PR3_CONDITION value=$condition min=${image.minimum} max=${image.maximum} mean=${image.mean} " +
                    "diffMax=${difference.maximum} diffMean=${difference.mean} rmse=${difference.rmse} changedPercent=${difference.changedPercent}")
                assertEquals(TILE_ELEMENTS, image.finite)
            }
        }
    }

    @Test
    fun fp16ExecutesDeterministicallyAndIsComparedWithFp32() {
        val input = referenceInput().data
        fp32Session().use { fp32 ->
            val fp16 = try {
                fp16Session()
            } catch (unsupported: IllegalStateException) {
                val reason = unsupported.message.orEmpty()
                println("PR3_FP16_UNSUPPORTED runtime=org.tensorflow:tensorflow-lite:2.16.1 reason=${reason.replace('\n', ' ')}")
                assertTrue(reason.contains("input_type == kTfLiteFloat32"))
                assertTrue(reason.contains("CONV_2D"))
                return
            }
            fp16.use {
                val fp32Output = FloatArray(TILE_ELEMENTS)
                val fp16Output = FloatArray(TILE_ELEMENTS)
                val repeat = FloatArray(TILE_ELEMENTS)
                fp32.run(input, 0f, fp32Output)
                it.run(input, 0f, fp16Output)
                it.run(input, 0f, repeat)
                val summary = Pr3Metrics.summarize(nchwToHwc(fp16Output))
                val difference = Pr3Metrics.difference(fp32Output, fp16Output)
                val determinism = Pr3Metrics.difference(fp16Output, repeat)
                println("PR3_FP16 modelPath=${it.modelPath} loadMs=${it.modelLoadMillis} finite=${summary.finite} nan=${summary.nan} " +
                    "posInf=${summary.positiveInfinity} negInf=${summary.negativeInfinity} min=${summary.minimum} max=${summary.maximum} mean=${summary.mean}")
                println("PR3_FP16_VS_FP32 max=${difference.maximum} mean=${difference.mean} rmse=${difference.rmse} " +
                    "psnrPeak1=${difference.psnrPeakOne} changedPercent=${difference.changedPercent} index=${difference.largestIndex} channels=${difference.channels}")
                println("PR3_FP16_DETERMINISM max=${determinism.maximum} mean=${determinism.mean}")
                assertEquals(TILE_ELEMENTS, summary.finite)
                assertEquals(0, summary.nan + summary.positiveInfinity + summary.negativeInfinity)
                assertEquals(0.0, determinism.maximum, 0.0)
                assertTrue("FP16 max difference=${difference.maximum}", difference.maximum <= 0.01)
                assertTrue("FP16 mean difference=${difference.mean}", difference.mean <= 0.001)
            }
        }
    }

    @Test
    fun seamPaddingProgressCancellationAndMemoryAreValidated() {
        val input = synthetic(513, 513)
        val engine = FullImageInferenceEngine()
        val heapBefore = usedHeap()
        var peakHeap = heapBefore
        val session = fp32Session()
        session.use {
            it.warmUp(input.data.copyOf(TILE_ELEMENTS))
            for (padding in listOf(TilePadding.REFLECT, TilePadding.REPLICATE)) {
                for (overlap in listOf(0, 16, 32)) {
                    val progress = mutableListOf<InferenceProgress>()
                    val result = engine.process(
                        input, TilingConfig(overlap = overlap, padding = padding),
                        TileProcessor { _, tile, output -> it.run(tile, 0f, output) },
                        progress = progress::add,
                    )
                    peakHeap = maxOf(peakHeap, usedHeap())
                    val summary = Pr3Metrics.summarize(result.image.data)
                    val seam = SeamAnalyzer.analyze(result.image, result.plan)
                    println("PR3_SEAM padding=$padding overlap=$overlap tiles=${result.plan.totalTiles} " +
                        "vertical=${seam.verticalSeamCount} horizontal=${seam.horizontalSeamCount} mean=${seam.meanBoundaryJump} " +
                        "max=${seam.maximumBoundaryJump} rmse=${seam.boundaryRmse} interior=${seam.interiorMeanJump} ratio=${seam.seamToInteriorRatio} channels=${seam.channels} " +
                        "totalMs=${result.timings.totalMillis} inferenceMs=${result.timings.inferenceMillis}")
                    println("PR3_OUTPUT padding=$padding overlap=$overlap elements=${summary.elements} finite=${summary.finite} nan=${summary.nan} " +
                        "posInf=${summary.positiveInfinity} negInf=${summary.negativeInfinity} min=${summary.minimum} max=${summary.maximum} mean=${summary.mean} std=${summary.standardDeviation} " +
                        "below0=${summary.belowZeroPercent} above1=${summary.aboveOnePercent} equal0=${summary.equalZeroPercent} equal1=${summary.equalOnePercent} channels=${summary.channels}")
                    assertEquals(summary.elements, summary.finite)
                    assertTrue(progress.zipWithNext().all { (a, b) -> b.fraction >= a.fraction })
                    if (padding == TilePadding.REFLECT && overlap == 32) writePpm(result.image, "pr3_fp32_reflect_overlap32.ppm")
                }
            }

            val cancelled = AtomicBoolean(false)
            val processed = AtomicInteger()
            val phases = mutableListOf<InferencePhase>()
            var cancellationObserved = false
            try {
                engine.process(
                    synthetic(768, 256), TilingConfig(overlap = 0),
                    TileProcessor { _, tile, output ->
                        it.run(tile, 0f, output)
                        if (processed.incrementAndGet() == 2) cancelled.set(true)
                    },
                    CancellationSignal(cancelled::get),
                ) { phases += it.phase }
            } catch (_: FullImageCancellationException) {
                cancellationObserved = true
            }
            assertTrue(cancellationObserved)
            assertEquals(2, processed.get())
            assertFalse(InferencePhase.COMPLETED in phases)
            assertTrue(InferencePhase.CANCELLED in phases)
        }
        assertTrue(session.isClosed)
        val heapAfter = usedHeap()
        println("PR3_MEMORY width=513 height=513 estimated=${engine.estimateWorkingBytes(513, 513)} " +
            "heapBefore=$heapBefore peakObserved=$peakHeap heapAfter=$heapAfter nativeHeap=${Debug.getNativeHeapAllocatedSize()}")
    }

    private fun fp32Session(): RawAiTestSession = session("cpu_reference", ModelPrecision.FP32)
    private fun fp16Session(): RawAiTestSession = session("accelerated", ModelPrecision.FP16)

    private fun session(key: String, precision: ModelPrecision): RawAiTestSession {
        val manifest = JSONObject(targetContext.assets.open("raw_ai/model_manifest.json").bufferedReader().use { it.readText() })
        val info = manifest.getJSONObject("model_files").getJSONObject(key)
        return RawAiTestSession(targetContext, info.getString("path"), info.getString("sha256"), precision)
    }

    private fun referenceInput(): LinearRgbImage {
        val bytes = instrumentation.context.assets.open("raw_ai/reference/input_tile_01.bin").use { it.readBytes() }
        assertEquals(TILE_ELEMENTS * 4, bytes.size)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return LinearRgbImage(256, 256, FloatArray(TILE_ELEMENTS) { buffer.float })
    }

    private fun synthetic(width: Int, height: Int): LinearRgbImage = LinearRgbImage(
        width, height, FloatArray(width * height * 3) { index ->
            val pixel = index / 3
            val x = pixel % width
            val y = pixel / width
            val channel = index % 3
            ((x * 17 + y * 31 + channel * 47) % 1021) / 1020f
        },
    )

    private fun hwcToNchw(hwc: FloatArray): FloatArray {
        val pixels = hwc.size / 3
        return FloatArray(hwc.size) { index -> hwc[(index % pixels) * 3 + index / pixels] }
    }

    private fun nchwToHwc(nchw: FloatArray): FloatArray {
        val pixels = nchw.size / 3
        return FloatArray(nchw.size) { index -> nchw[(index % 3) * pixels + index / 3] }
    }

    private fun usedHeap(): Long = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

    private fun writePpm(image: LinearRgbImage, name: String) {
        val directory = File(targetContext.cacheDir, "raw_ai_pr3").apply { mkdirs() }
        val file = File(directory, name)
        file.outputStream().buffered().use { output ->
            output.write("P6\n${image.width} ${image.height}\n255\n".toByteArray())
            for (pixel in 0 until image.width * image.height) for (channel in 0..2) {
                output.write((image.data[pixel * 3 + channel].coerceIn(0f, 1f) * 255f + 0.5f).toInt())
            }
        }
        println("PR3_DEBUG_OUTPUT path=${file.absolutePath} bytes=${file.length()}")
    }

    companion object { private const val TILE_ELEMENTS = 256 * 256 * 3 }
}
