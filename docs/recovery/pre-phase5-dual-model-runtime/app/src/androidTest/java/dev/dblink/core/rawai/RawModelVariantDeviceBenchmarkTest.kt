package dev.dblink.core.rawai

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class RawModelVariantDeviceBenchmarkTest {
    @Test
    fun benchmarkExternalRawForgeTile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val input = File(requireNotNull(arguments.getString("inputPath")))
        val outputDir = File(requireNotNull(arguments.getString("outputDir"))).apply { mkdirs() }
        val sample = requireNotNull(arguments.getString("sample"))
        val iso = requireNotNull(arguments.getString("iso")).toFloat()
        val repeats = arguments.getString("repeats")?.toInt() ?: 5
        val threads = arguments.getString("threads")?.toInt() ?: 4
        val precisionName = arguments.getString("precision") ?: "fp32"
        val precision = when (precisionName) {
            "fp32" -> ModelPrecision.FP32
            "fp16" -> ModelPrecision.FP16
            else -> error("Unsupported precision: $precisionName")
        }
        val modelName = if (precision == ModelPrecision.FP32) "model_fp32.tflite" else "model_fp16.tflite"
        val modelSha = if (precision == ModelPrecision.FP32) FP32_SHA else FP16_SHA
        val tensor = readFloat32(input, ELEMENTS)
        val output = FloatArray(ELEMENTS)
        val condition = minOf(iso, 65535f) / 6400f
        val timings = ArrayList<Double>(repeats)

        RawAiTestSession(context, modelName, modelSha, precision, threads).use { session ->
            repeat(3) { session.run(tensor, condition, output) }
            repeat(repeats) {
                val start = SystemClock.elapsedRealtimeNanos()
                session.run(tensor, condition, output)
                timings += (SystemClock.elapsedRealtimeNanos() - start) / 1e6
            }
        }
        assertTrue(output.all(Float::isFinite))
        val outputFile = File(outputDir, "$sample.mobile_$precisionName.nchw.f32le.bin")
        writeFloat32(outputFile, output)
        val sorted = timings.sorted()
        val report = JSONObject()
            .put("sample", sample)
            .put("iso", iso.toDouble())
            .put("condition", condition.toDouble())
            .put("precision", precisionName)
            .put("threads", threads)
            .put("repeats", repeats)
            .put("median_ms", sorted[sorted.size / 2])
            .put("mean_ms", timings.average())
            .put("minimum_ms", timings.min())
            .put("maximum_ms", timings.max())
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android_api", Build.VERSION.SDK_INT)
            .put("model", modelName)
            .put("model_sha256", modelSha)
            .put("output", outputFile.name)
            .put("output_sha256", ModelAssetStore.sha256(outputFile))
        File(outputDir, "$sample.mobile_$precisionName.json").writeText(report.toString(2))
        println("RAW_AI_MOBILE_BENCHMARK $report")
    }

    private fun readFloat32(file: File, expected: Int): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size == expected * Float.SIZE_BYTES) {
            "Input byte count mismatch: expected=${expected * Float.SIZE_BYTES} actual=${bytes.size}"
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(expected) { buffer.float }
    }

    private fun writeFloat32(file: File, values: FloatArray) {
        val buffer = ByteBuffer.allocate(values.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach(buffer::putFloat)
        file.writeBytes(buffer.array())
    }

    companion object {
        private const val ELEMENTS = 256 * 256 * 3
        private const val FP32_SHA = "0efe3fd811cb8691e6347021fbb147fd81282952145274460d1238da58715806"
        private const val FP16_SHA = "03842593e1295f94e44d3cab6bc3f7fae2022941f0659d54233114398d1376b3"
    }
}
