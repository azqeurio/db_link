package dev.dblink.core.rawai

import android.os.Build
import android.util.JsonWriter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class RawDomainDeviceTest {
    @Test
    fun extractActualLibRawStageAndCompareWithIndependentReferences() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val raw = File(requireArgument(arguments.getString("rawPath"), "rawPath"))
        val rawForgeReference = File(requireArgument(arguments.getString("rawForgeReference"), "rawForgeReference"))
        val dcrawReference = File(requireArgument(arguments.getString("dcrawReference"), "dcrawReference"))
        val cropX = requireArgument(arguments.getString("cropX"), "cropX").toInt()
        val cropY = requireArgument(arguments.getString("cropY"), "cropY").toInt()
        val width = arguments.getString("cropWidth")?.toInt() ?: 256
        val height = arguments.getString("cropHeight")?.toInt() ?: 256
        val iso = arguments.getString("iso")?.toFloat() ?: 0f
        check(raw.isFile && rawForgeReference.isFile && dcrawReference.isFile) {
            "Missing PR4 input: raw=${raw.isFile} rawforge=${rawForgeReference.isFile} dcraw=${dcrawReference.isFile}"
        }

        val extracted = RawDomainDebugNative.extract(raw.absolutePath, cropX, cropY, width, height)
        val rawForge = readFloat32(rawForgeReference, width * height * 3)
        val dcraw = readFloat32(dcrawReference, width * height * 3)
        val vsRawForge = compare(rawForge, extracted.tensorHwc, width, height, true)
        val vsDcraw = compare(dcraw, extracted.tensorHwc, width, height, true)
        val offset = bestOffset(dcraw, extracted.tensorHwc, width, height, 4)

        val rawForgeOutput = FloatArray(width * height * 3)
        val androidOutput = FloatArray(width * height * 3)
        val rawForgeOutputIso = FloatArray(width * height * 3)
        val androidOutputIso = FloatArray(width * height * 3)
        RawAiTestSession(
            context,
            "rawforge_superlight_fp32.tflite",
            "0efe3fd811cb8691e6347021fbb147fd81282952145274460d1238da58715806",
            ModelPrecision.FP32,
        ).use { session ->
            session.run(rawForge, 0f, rawForgeOutput)
            session.run(extracted.tensorHwc, 0f, androidOutput)
            val evidencedCondition = minOf(iso, 65535f) / 6400f
            session.run(rawForge, evidencedCondition, rawForgeOutputIso)
            session.run(extracted.tensorHwc, evidencedCondition, androidOutputIso)
        }
        val outputCond0 = compare(rawForgeOutput, androidOutput, width, height, false)
        val outputIsoCondition = compare(rawForgeOutputIso, androidOutputIso, width, height, false)

        val artifactDir = File(context.cacheDir, "raw_ai_pr4/${raw.nameWithoutExtension}").apply { mkdirs() }
        val tensorFile = File(artifactDir, "android-${extracted.stage}.f32le.bin")
        writeFloat32(tensorFile, extracted.tensorHwc)
        val manifestFile = File(artifactDir, "android-manifest.json")
        writeManifest(
            manifestFile,
            raw,
            extracted,
            tensorFile,
            vsRawForge,
            vsDcraw,
            offset,
            outputCond0,
            outputIsoCondition,
            iso,
        )

        println("PR4_ANDROID_ARTIFACT manifest=${manifestFile.absolutePath} tensor=${tensorFile.absolutePath}")
        println("PR4_INPUT_VS_RAWFORGE sample=${raw.name} $vsRawForge")
        println("PR4_INPUT_VS_DCRAW sample=${raw.name} $vsDcraw bestOffset=$offset")
        println("PR4_OUTPUT_COND0 sample=${raw.name} $outputCond0")
        println("PR4_OUTPUT_ISO_CONDITION sample=${raw.name} condition=${minOf(iso, 65535f) / 6400f} $outputIsoCondition")

        assertEquals(width * height * 3, extracted.tensorHwc.size)
        assertEquals("RGGB", extracted.cfaPattern)
        assertTrue(extracted.tensorHwc.all(Float::isFinite))
        assertEquals(0, offset.dx)
        assertEquals(0, offset.dy)
        // This checks that the bridge observes the existing path reproducibly. It deliberately does
        // not assert RawForge compatibility, which is the measured question.
        // The independent Python environment is rawpy/LibRaw 0.21.4 while the app bundles
        // LibRaw 0.22.0. These bounds detect coordinate/channel/stage mistakes without pretending
        // that two LibRaw releases are bit-identical.
        assertTrue("Android/rawpy dcraw correlation indicates a wrong stage or geometry: $vsDcraw", vsDcraw.correlation >= 0.97)
        assertTrue("Android/rawpy dcraw RMSE indicates a wrong stage or normalization: $vsDcraw", vsDcraw.rmse <= 0.10)
    }

    private fun requireArgument(value: String?, name: String): String =
        value ?: error("Instrumentation argument '$name' is required")
}

private data class DomainDifference(
    val max: Double,
    val mean: Double,
    val rmse: Double,
    val meanRelative: Double,
    val correlation: Double,
    val largestIndex: Int,
    val referenceAtLargest: Double,
    val actualAtLargest: Double,
    val above: Map<String, Double>,
    val channelRmse: List<Double>,
)

private data class OffsetResult(val dx: Int, val dy: Int, val rmse: Double, val correlation: Double)

private fun compare(reference: FloatArray, actual: FloatArray, width: Int, height: Int, hwc: Boolean): DomainDifference {
    require(reference.size == actual.size && reference.size == width * height * 3)
    var max = -1.0
    var maxIndex = 0
    var sum = 0.0
    var squares = 0.0
    var relative = 0.0
    var sumReference = 0.0
    var sumActual = 0.0
    val thresholds = doubleArrayOf(1e-6, 1e-5, 1e-4, 1e-3, 1e-2)
    val above = IntArray(thresholds.size)
    val channelSquares = DoubleArray(3)
    val channelCount = IntArray(3)
    for (index in reference.indices) {
        val ref = reference[index].toDouble()
        val value = actual[index].toDouble()
        val error = abs(ref - value)
        if (error > max) { max = error; maxIndex = index }
        sum += error
        squares += error * error
        relative += error / maxOf(abs(ref), 1e-6)
        sumReference += ref
        sumActual += value
        thresholds.indices.forEach { if (error > thresholds[it]) above[it]++ }
        val channel = if (hwc) index % 3 else index / (width * height)
        channelSquares[channel] += error * error
        channelCount[channel]++
    }
    val meanReference = sumReference / reference.size
    val meanActual = sumActual / actual.size
    var covariance = 0.0
    var varianceReference = 0.0
    var varianceActual = 0.0
    for (index in reference.indices) {
        val r = reference[index] - meanReference
        val a = actual[index] - meanActual
        covariance += r * a
        varianceReference += r * r
        varianceActual += a * a
    }
    return DomainDifference(
        max,
        sum / reference.size,
        sqrt(squares / reference.size),
        relative / reference.size,
        covariance / sqrt(varianceReference * varianceActual).coerceAtLeast(1e-30),
        maxIndex,
        reference[maxIndex].toDouble(),
        actual[maxIndex].toDouble(),
        thresholds.indices.associate { thresholds[it].toString() to above[it] * 100.0 / reference.size },
        List(3) { sqrt(channelSquares[it] / channelCount[it].coerceAtLeast(1)) },
    )
}

private fun bestOffset(reference: FloatArray, actual: FloatArray, width: Int, height: Int, radius: Int): OffsetResult {
    var best = OffsetResult(0, 0, Double.POSITIVE_INFINITY, Double.NaN)
    for (dy in -radius..radius) for (dx in -radius..radius) {
        var squares = 0.0
        var count = 0
        var sr = 0.0
        var sa = 0.0
        for (y in maxOf(0, -dy) until minOf(height, height - dy)) {
            for (x in maxOf(0, -dx) until minOf(width, width - dx)) {
                for (channel in 0..2) {
                    val r = reference[(y * width + x) * 3 + channel].toDouble()
                    val a = actual[((y + dy) * width + x + dx) * 3 + channel].toDouble()
                    val delta = r - a
                    squares += delta * delta
                    sr += r
                    sa += a
                    count++
                }
            }
        }
        val rmse = sqrt(squares / count)
        if (rmse < best.rmse) {
            val mr = sr / count
            val ma = sa / count
            var covariance = 0.0
            var vr = 0.0
            var va = 0.0
            for (y in maxOf(0, -dy) until minOf(height, height - dy)) for (x in maxOf(0, -dx) until minOf(width, width - dx)) for (channel in 0..2) {
                val r = reference[(y * width + x) * 3 + channel] - mr
                val a = actual[((y + dy) * width + x + dx) * 3 + channel] - ma
                covariance += r * a; vr += r * r; va += a * a
            }
            best = OffsetResult(dx, dy, rmse, covariance / sqrt(vr * va).coerceAtLeast(1e-30))
        }
    }
    return best
}

private fun readFloat32(file: File, expected: Int): FloatArray {
    val bytes = file.readBytes()
    require(bytes.size == expected * 4) { "Reference byte count mismatch: expected=${expected * 4} actual=${bytes.size} file=$file" }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(expected) { buffer.float }
}

private fun writeFloat32(file: File, values: FloatArray) {
    val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    values.forEach(buffer::putFloat)
    buffer.flip()
    FileOutputStream(file).channel.use { it.write(buffer) }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun writeManifest(
    file: File,
    raw: File,
    tensor: RawDomainDebugTensor,
    tensorFile: File,
    vsRawForge: DomainDifference,
    vsDcraw: DomainDifference,
    offset: OffsetResult,
    outputCond0: DomainDifference,
    outputIso: DomainDifference,
    iso: Float,
) {
    JsonWriter(OutputStreamWriter(file.outputStream(), Charsets.UTF_8)).use { json ->
        json.setIndent("  ")
        json.beginObject()
        json.name("schema_version").value(1)
        json.name("app_version").value(dev.dblink.BuildConfig.VERSION_NAME)
        json.name("git_commit").value("d907fb2")
        json.name("dirty_worktree").value(true)
        json.name("abi").value(Build.SUPPORTED_ABIS.joinToString(","))
        json.name("device").value("${Build.MANUFACTURER} ${Build.MODEL}")
        json.name("android_api").value(Build.VERSION.SDK_INT)
        json.name("libraw_version").value(tensor.libRawVersion)
        json.name("source_file_name").value(raw.name)
        json.name("source_sha256").value(sha256(raw))
        json.name("raw_width").value(tensor.rawWidth)
        json.name("raw_height").value(tensor.rawHeight)
        json.name("processed_width").value(tensor.processedWidth)
        json.name("processed_height").value(tensor.processedHeight)
        json.name("active_area_left").value(tensor.leftMargin)
        json.name("active_area_top").value(tensor.topMargin)
        json.name("cfa_pattern").value(tensor.cfaPattern)
        json.name("orientation_flip").value(tensor.orientationFlip)
        json.name("stage").value(tensor.stage)
        json.name("crop_x").value(tensor.cropX)
        json.name("crop_y").value(tensor.cropY)
        json.name("width").value(tensor.width)
        json.name("height").value(tensor.height)
        json.name("channels").value(3)
        json.name("layout").value("HWC")
        json.name("dtype").value("float32")
        json.name("endianness").value("little")
        json.name("channel_order").value("RGB")
        json.name("tensor_file").value(tensorFile.name)
        json.name("tensor_sha256").value(sha256(tensorFile))
        json.name("black_levels").beginArray(); tensor.blackLevels.forEach { json.value(it.toDouble()) }; json.endArray()
        json.name("white_level").value(tensor.whiteLevel)
        json.name("camera_white_balance").beginArray(); tensor.cameraWhiteBalance.forEach { json.value(it.toDouble()) }; json.endArray()
        json.name("iso").value(iso.toDouble())
        json.name("condition_oracle").value(0.0)
        json.name("condition_upstream_formula").value(minOf(iso, 65535f) / 6400f)
        fun difference(name: String, value: DomainDifference) {
            json.name(name).beginObject()
            json.name("maximum_absolute_error").value(value.max)
            json.name("mean_absolute_error").value(value.mean)
            json.name("rmse").value(value.rmse)
            json.name("mean_relative_error_epsilon_1e-6").value(value.meanRelative)
            json.name("correlation").value(value.correlation)
            json.name("largest_error_index").value(value.largestIndex)
            json.name("reference_at_largest").value(value.referenceAtLargest)
            json.name("android_at_largest").value(value.actualAtLargest)
            json.name("channel_rmse").beginArray(); value.channelRmse.forEach(json::value); json.endArray()
            json.name("percent_above_threshold").beginObject(); value.above.forEach { (key, number) -> json.name(key).value(number) }; json.endObject()
            json.endObject()
        }
        difference("input_vs_rawforge", vsRawForge)
        difference("input_vs_rawpy_dcraw", vsDcraw)
        difference("model_output_cond0", outputCond0)
        difference("model_output_iso_condition", outputIso)
        json.name("best_dcraw_offset").beginObject().name("dx").value(offset.dx).name("dy").value(offset.dy).name("rmse").value(offset.rmse).name("correlation").value(offset.correlation).endObject()
        json.endObject()
    }
}
