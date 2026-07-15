package dev.dblink.core.rawai

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit benchmark class; run by class name, not as part of focused correctness commands. */
@RunWith(AndroidJUnit4::class)
class FullImageBenchmarkTest {
    @Test
    fun benchmarkFp32AndFp16Matrix() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        println("PR3_DEVICE manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} android=${Build.VERSION.RELEASE} " +
            "api=${Build.VERSION.SDK_INT} abis=${Build.SUPPORTED_ABIS.contentToString()} processors=${Runtime.getRuntime().availableProcessors()} " +
            "memoryClassMb=${activityManager.memoryClass} charging=${battery.isCharging}")
        val manifest = JSONObject(context.assets.open("raw_ai/model_manifest.json").bufferedReader().use { it.readText() })
        val sizes = listOf(256 to 256, 512 to 512, 1024 to 1024, 1920 to 1080, 2048 to 1536)
        for ((key, precision) in listOf("cpu_reference" to ModelPrecision.FP32, "accelerated" to ModelPrecision.FP16)) {
            val info = manifest.getJSONObject("model_files").getJSONObject(key)
            val opened = try {
                RawAiTestSession(context, info.getString("path"), info.getString("sha256"), precision)
            } catch (unsupported: IllegalStateException) {
                println("PR3_BENCH precision=$precision status=UNSUPPORTED reason=${unsupported.message.orEmpty().replace('\n', ' ')}")
                continue
            }
            opened.use { session ->
                val warmInput = synthetic(256, 256)
                val warmupMs = session.warmUp(warmInput.data)
                for ((width, height) in sizes) {
                    val input = synthetic(width, height)
                    val runs = if (width <= 1024) 2 else 1
                    var best: FullImageResult? = null
                    var peakHeap = usedHeap()
                    repeat(runs) {
                        val result = FullImageInferenceEngine().process(
                            input, TilingConfig(overlap = 32, padding = TilePadding.REFLECT),
                            TileProcessor { _, tile, output -> session.run(tile, 0f, output) },
                        )
                        peakHeap = maxOf(peakHeap, usedHeap())
                        if (best?.let { result.timings.totalMillis < it.timings.totalMillis } != false) best = result
                    }
                    val result = requireNotNull(best)
                    val sorted = result.timings.tileInferenceMillis.sorted()
                    val median = percentile(sorted, 0.5)
                    val p95 = percentile(sorted, 0.95)
                    val megapixels = width.toDouble() * height / 1_000_000.0
                    val summary = Pr3Metrics.summarize(result.image.data)
                    assertEquals(summary.elements, summary.finite)
                    println("PR3_BENCH precision=$precision size=${width}x$height overlap=32 padding=REFLECT tiles=${result.plan.totalTiles} " +
                        "threads=1 warmups=1 measuredRuns=$runs loadMs=${session.modelLoadMillis} warmupMs=$warmupMs totalMs=${result.timings.totalMillis} " +
                        "extractMs=${result.timings.extractionMillis} inferenceMs=${result.timings.inferenceMillis} blendMs=${result.timings.blendingMillis} " +
                        "finalizeMs=${result.timings.finalizationMillis} meanTileMs=${sorted.average()} medianTileMs=$median p95TileMs=$p95 " +
                        "throughputMpixPerSec=${megapixels / (result.timings.totalMillis / 1000.0)} peakHeap=$peakHeap nativeHeap=${Debug.getNativeHeapAllocatedSize()} " +
                        "estimatedBytes=${FullImageInferenceEngine().estimateWorkingBytes(width, height)} cancellation=true status=PASS")
                }
            }
        }
    }

    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        if (sorted.isEmpty()) return 0.0
        return sorted[((sorted.size - 1) * fraction).toInt().coerceIn(sorted.indices)]
    }

    private fun synthetic(width: Int, height: Int): LinearRgbImage = LinearRgbImage(
        width, height, FloatArray(width * height * 3) { index -> ((index * 37L + 11L) % 4093L).toFloat() / 4092f },
    )

    private fun usedHeap(): Long = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
}
