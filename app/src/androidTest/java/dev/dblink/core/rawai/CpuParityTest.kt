package dev.dblink.core.rawai

import android.content.res.AssetManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class CpuParityTest {
    @Test
    fun fp32SingleTileMatchesGoldenReference() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val modelManifest = JSONObject(context.assets.open(MODEL_MANIFEST).bufferedReader().use { it.readText() })
        val referenceManifest = JSONObject(
            instrumentation.context.assets.open(REFERENCE_MANIFEST).bufferedReader().use { it.readText() },
        )
        check(referenceManifest.getString("endianness") == "little") {
            "Unsupported reference byte order: ${referenceManifest.getString("endianness")}"
        }

        val modelInfo = modelManifest.getJSONObject("model_files").getJSONObject("superlight")
        val modelName = modelInfo.getString("path")
        val expectedModelSha = modelInfo.getString("sha256").lowercase()
        val modelFile = ModelAssetStore.stage(context, modelName, expectedModelSha)
        val actualModelSha = ModelAssetStore.sha256(modelFile)
        check(actualModelSha == expectedModelSha) {
            "Model SHA-256 mismatch before interpreter creation: expected=$expectedModelSha " +
                "actual=$actualModelSha path=${modelFile.absolutePath}"
        }

        val referenceCase = findReferenceCase(referenceManifest.getJSONArray("cases"), "tile_01")
        val inputSpec = BinarySpec.from(referenceCase.getJSONObject("input"))
        val conditionSpec = BinarySpec.from(referenceCase.getJSONObject("condition"))
        val outputSpec = BinarySpec.from(referenceCase.getJSONObject("output"))
        validateManifestContract(modelManifest, inputSpec, conditionSpec, outputSpec)

        val referenceAssets = instrumentation.context.assets
        val inputBytes = readValidatedAsset(referenceAssets, inputSpec)
        val conditionBytes = readValidatedAsset(referenceAssets, conditionSpec)
        val goldenBytes = readValidatedAsset(referenceAssets, outputSpec)
        val condition = littleEndianFloats(conditionBytes, conditionSpec)[0]
        val golden = littleEndianFloats(goldenBytes, outputSpec)

        val options = Interpreter.Options()
            .setNumThreads(CPU_THREADS)
            .setUseNNAPI(false)
            .setUseXNNPACK(false)
        var inferenceMs = 0L
        lateinit var actual: FloatArray
        lateinit var repeat: FloatArray

        Interpreter(modelFile, options).use { interpreter ->
            validateRuntimeContract(interpreter, modelManifest, modelFile.absolutePath, actualModelSha)

            // The warm-up is deliberately separate from the two measured/reference runs.
            runInference(interpreter, inputBytes, conditionBytes, outputSpec.elementCount)
            val start = SystemClock.elapsedRealtimeNanos()
            actual = runInference(interpreter, inputBytes, conditionBytes, outputSpec.elementCount)
            inferenceMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000L
            repeat = runInference(interpreter, inputBytes, conditionBytes, outputSpec.elementCount)
        }

        val parity = metrics(golden, actual, outputSpec.shape)
        val determinism = differenceMetrics(actual, repeat)
        printReport(
            modelName, modelFile.absolutePath, actualModelSha, modelFile.length(), condition,
            inputSpec, conditionSpec, outputSpec, inferenceMs, parity, determinism,
        )

        val failures = mutableListOf<String>()
        if (parity.actualNanCount != 0) failures += "actual NaN count=${parity.actualNanCount}, allowed=0"
        if (parity.actualPositiveInfinityCount != 0) {
            failures += "actual +Inf count=${parity.actualPositiveInfinityCount}, allowed=0"
        }
        if (parity.actualNegativeInfinityCount != 0) {
            failures += "actual -Inf count=${parity.actualNegativeInfinityCount}, allowed=0"
        }
        if (parity.referenceNonFiniteCount != 0) {
            failures += "reference non-finite count=${parity.referenceNonFiniteCount}, allowed=0"
        }
        if (parity.maxAbsoluteError > MAX_ABSOLUTE_ERROR) {
            failures += "max absolute error=${parity.maxAbsoluteError}, allowed=$MAX_ABSOLUTE_ERROR"
        }
        if (parity.meanAbsoluteError > MEAN_ABSOLUTE_ERROR) {
            failures += "mean absolute error=${parity.meanAbsoluteError}, allowed=$MEAN_ABSOLUTE_ERROR"
        }
        if (parity.rmse > RMSE) failures += "RMSE=${parity.rmse}, allowed=$RMSE"
        if (determinism.max > DETERMINISM_MAX_ERROR) {
            failures += "run-to-run max=${determinism.max}, allowed=$DETERMINISM_MAX_ERROR"
        }
        if (determinism.mean > DETERMINISM_MEAN_ERROR) {
            failures += "run-to-run mean=${determinism.mean}, allowed=$DETERMINISM_MEAN_ERROR"
        }
        if (failures.isNotEmpty()) fail("CPU parity FAIL:\n${failures.joinToString("\n")}")
        println("RAW_AI_CPU_PARITY status=PASS")
    }

    private fun runInference(
        interpreter: Interpreter,
        inputBytes: ByteArray,
        conditionBytes: ByteArray,
        outputElements: Int,
    ): FloatArray {
        val input = directBuffer(inputBytes)
        val condition = directBuffer(conditionBytes)
        val output = ByteBuffer.allocateDirect(outputElements * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        interpreter.runForMultipleInputsOutputs(arrayOf(input, condition), mutableMapOf<Int, Any>(0 to output))
        output.rewind()
        return FloatArray(outputElements) { output.float }
    }

    private fun validateRuntimeContract(
        interpreter: Interpreter,
        manifest: JSONObject,
        modelPath: String,
        modelSha: String,
    ) {
        check(interpreter.inputTensorCount == 2 && interpreter.outputTensorCount == 1) {
            "Tensor count mismatch: inputs=${interpreter.inputTensorCount} outputs=${interpreter.outputTensorCount} " +
                "model=$modelPath sha256=$modelSha runtime=$LITERT_ARTIFACT"
        }
        validateTensor(0, interpreter.getInputTensor(0), manifest.getJSONObject("image_input"), modelPath, modelSha)
        validateTensor(1, interpreter.getInputTensor(1), manifest.getJSONObject("condition_input"), modelPath, modelSha)
        validateTensor(0, interpreter.getOutputTensor(0), manifest.getJSONObject("image_output"), modelPath, modelSha)
    }

    private fun validateTensor(
        ioIndex: Int,
        actual: Tensor,
        expected: JSONObject,
        modelPath: String,
        modelSha: String,
    ) {
        val expectedName = expected.getString("tensor_name")
        val expectedShape = expected.getJSONArray("shape").toIntArray()
        val expectedType = expected.getString("dtype")
        check(actual.name() == expectedName && actual.shape().contentEquals(expectedShape) && actual.dataType() == DataType.FLOAT32) {
            "Tensor contract mismatch at I/O index $ioIndex: expected name=$expectedName shape=${expectedShape.contentToString()} " +
                "dtype=$expectedType; actual name=${actual.name()} shape=${actual.shape().contentToString()} " +
                "dtype=${actual.dataType()}; model=$modelPath sha256=$modelSha runtime=$LITERT_ARTIFACT"
        }
        println(
            "RAW_AI_TENSOR ioIndex=$ioIndex name=${actual.name()} shape=${actual.shape().contentToString()} " +
                "dtype=${actual.dataType()}",
        )
    }

    private fun validateManifestContract(
        manifest: JSONObject,
        input: BinarySpec,
        condition: BinarySpec,
        output: BinarySpec,
    ) {
        fun validate(sectionName: String, spec: BinarySpec, expectedLayout: String?) {
            val section = manifest.getJSONObject(sectionName)
            check(section.getString("dtype") == spec.dtype && section.getJSONArray("shape").toIntArray().contentEquals(spec.shape)) {
                "Reference/model manifest mismatch for ${spec.name}: model dtype=${section.getString("dtype")} " +
                    "shape=${section.getJSONArray("shape")}; reference dtype=${spec.dtype} shape=${spec.shape.contentToString()}"
            }
            if (expectedLayout != null) check(section.getString("layout") == expectedLayout)
        }
        validate("image_input", input, "NHWC")
        validate("condition_input", condition, null)
        validate("image_output", output, "NCHW")
        val conditionContract = manifest.getJSONObject("condition_input")
        check(conditionContract.getString("formula") == "min(iso,65535)/6400")
        check(conditionContract.getDouble("oracle_value") == 0.0) {
            "PR2 golden reference must remain the explicit cond=0 numerical oracle"
        }
    }

    private fun readValidatedAsset(assets: AssetManager, spec: BinarySpec): ByteArray {
        check(spec.dtype == "float32") { "Unsupported dtype for ${spec.name}: ${spec.dtype}" }
        check(spec.sizeBytes == spec.elementCount * Float.SIZE_BYTES) {
            "Manifest byte count mismatch for ${spec.name}: size=${spec.sizeBytes} elements=${spec.elementCount} " +
                "dtype=${spec.dtype} shape=${spec.shape.contentToString()}"
        }
        val bytes = assets.open("raw_ai/reference/${spec.path}").use { it.readBytes() }
        check(bytes.size == spec.sizeBytes) {
            "Binary size mismatch for ${spec.name}: expected=${spec.sizeBytes} actual=${bytes.size} " +
                "dtype=${spec.dtype} shape=${spec.shape.contentToString()}"
        }
        val actualSha = ModelAssetStore.sha256(bytes)
        check(actualSha == spec.sha256) {
            "Reference SHA-256 mismatch for ${spec.name}: expected=${spec.sha256} actual=$actualSha"
        }
        return bytes
    }

    private fun littleEndianFloats(bytes: ByteArray, spec: BinarySpec): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val result = FloatArray(spec.elementCount) { buffer.float }
        check(!buffer.hasRemaining()) { "Trailing bytes in ${spec.name}: ${buffer.remaining()}" }
        return result
    }

    private fun metrics(reference: FloatArray, actual: FloatArray, shape: IntArray): ParityMetrics {
        check(reference.size == actual.size && shape.contentEquals(intArrayOf(1, 3, 256, 256)))
        var finite = 0
        var nan = 0
        var positiveInfinity = 0
        var negativeInfinity = 0
        var referenceNonFinite = 0
        var sumAbs = 0.0
        var sumSquares = 0.0
        var maxError = -1.0
        var maxIndex = 0
        var referenceMin = Double.POSITIVE_INFINITY
        var referenceMax = Double.NEGATIVE_INFINITY
        var referenceSum = 0.0
        var actualMin = Double.POSITIVE_INFINITY
        var actualMax = Double.NEGATIVE_INFINITY
        var actualSum = 0.0
        val channelAbs = DoubleArray(3)
        val channelSquares = DoubleArray(3)
        val channelMax = DoubleArray(3)
        val channelElements = 256 * 256

        for (index in reference.indices) {
            val ref = reference[index].toDouble()
            val value = actual[index].toDouble()
            if (!ref.isFinite()) referenceNonFinite++
            when {
                value.isNaN() -> nan++
                value == Double.POSITIVE_INFINITY -> positiveInfinity++
                value == Double.NEGATIVE_INFINITY -> negativeInfinity++
                else -> {
                    finite++
                    actualMin = minOf(actualMin, value)
                    actualMax = maxOf(actualMax, value)
                    actualSum += value
                }
            }
            if (ref.isFinite()) {
                referenceMin = minOf(referenceMin, ref)
                referenceMax = maxOf(referenceMax, ref)
                referenceSum += ref
            }
            val error = if (ref.isFinite() && value.isFinite()) abs(value - ref) else Double.POSITIVE_INFINITY
            if (error > maxError) {
                maxError = error
                maxIndex = index
            }
            sumAbs += error
            sumSquares += error * error
            val channel = index / channelElements
            channelAbs[channel] += error
            channelSquares[channel] += error * error
            channelMax[channel] = maxOf(channelMax[channel], error)
        }
        val count = reference.size.toDouble()
        return ParityMetrics(
            reference.size, finite, nan, positiveInfinity, negativeInfinity, referenceNonFinite,
            maxError, sumAbs / count, sqrt(sumSquares / count), referenceMin, referenceMax,
            referenceSum / count, actualMin, actualMax, actualSum / count, maxIndex,
            reference[maxIndex], actual[maxIndex],
            List(3) { channel ->
                ChannelMetrics(channelMax[channel], channelAbs[channel] / channelElements, sqrt(channelSquares[channel] / channelElements))
            },
        )
    }

    private fun differenceMetrics(first: FloatArray, second: FloatArray): DifferenceMetrics {
        check(first.size == second.size)
        var max = 0.0
        var sum = 0.0
        for (index in first.indices) {
            val difference = abs(first[index].toDouble() - second[index].toDouble())
            max = maxOf(max, difference)
            sum += difference
        }
        return DifferenceMetrics(max, sum / first.size)
    }

    private fun printReport(
        modelName: String,
        modelPath: String,
        modelSha: String,
        modelBytes: Long,
        condition: Float,
        input: BinarySpec,
        conditionSpec: BinarySpec,
        output: BinarySpec,
        inferenceMs: Long,
        metrics: ParityMetrics,
        determinism: DifferenceMetrics,
    ) {
        val coordinate = nchwCoordinate(metrics.largestErrorIndex)
        println("RAW_AI_CPU_PARITY model=$modelName path=$modelPath sha256=$modelSha bytes=$modelBytes")
        println("RAW_AI_CPU_PARITY runtime=$LITERT_ARTIFACT cpuThreads=$CPU_THREADS delegates=none xnnpack=false")
        println("RAW_AI_CPU_PARITY inputBytes=${input.sizeBytes} conditionBytes=${conditionSpec.sizeBytes} outputBytes=${output.sizeBytes} condition=$condition")
        // cond=0 is contractually reproduced here; this parity test does not prove its semantic meaning.
        println("RAW_AI_CPU_PARITY inferenceMs=$inferenceMs elements=${metrics.elementCount} finite=${metrics.actualFiniteCount} " +
            "nan=${metrics.actualNanCount} positiveInf=${metrics.actualPositiveInfinityCount} negativeInf=${metrics.actualNegativeInfinityCount}")
        println("RAW_AI_CPU_PARITY maxAbs=${metrics.maxAbsoluteError} mae=${metrics.meanAbsoluteError} rmse=${metrics.rmse}")
        println("RAW_AI_CPU_PARITY referenceMin=${metrics.referenceMin} referenceMax=${metrics.referenceMax} referenceMean=${metrics.referenceMean}")
        println("RAW_AI_CPU_PARITY actualMin=${metrics.actualMin} actualMax=${metrics.actualMax} actualMean=${metrics.actualMean}")
        println("RAW_AI_CPU_PARITY largestIndex=${metrics.largestErrorIndex} coordinate=$coordinate " +
            "reference=${metrics.referenceAtLargestError} actual=${metrics.actualAtLargestError}")
        metrics.channels.forEachIndexed { channel, value ->
            println("RAW_AI_CPU_PARITY channel=$channel maxAbs=${value.maxAbsoluteError} mae=${value.meanAbsoluteError} rmse=${value.rmse}")
        }
        println("RAW_AI_CPU_PARITY determinismMax=${determinism.max} determinismMean=${determinism.mean}")
    }

    private fun nchwCoordinate(index: Int): String {
        var remainder = index
        val x = remainder % 256
        remainder /= 256
        val y = remainder % 256
        remainder /= 256
        val c = remainder % 3
        val n = remainder / 3
        return "[$n,$c,$y,$x]"
    }

    private fun findReferenceCase(cases: JSONArray, id: String): JSONObject =
        (0 until cases.length()).map { cases.getJSONObject(it) }.single { it.getString("id") == id }

    private fun JSONArray.toIntArray(): IntArray = IntArray(length()) { getInt(it) }

    private fun directBuffer(bytes: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN).apply { put(bytes); rewind() }

    private data class BinarySpec(
        val name: String,
        val path: String,
        val dtype: String,
        val shape: IntArray,
        val sha256: String,
        val sizeBytes: Int,
    ) {
        val elementCount: Int = shape.fold(1, Int::times)

        companion object {
            fun from(json: JSONObject) = BinarySpec(
                json.getString("name"), json.getString("path"), json.getString("dtype"),
                json.getJSONArray("shape").let { array -> IntArray(array.length()) { array.getInt(it) } },
                json.getString("sha256").lowercase(), json.getInt("size_bytes"),
            )
        }
    }

    private data class ChannelMetrics(val maxAbsoluteError: Double, val meanAbsoluteError: Double, val rmse: Double)
    private data class DifferenceMetrics(val max: Double, val mean: Double)
    private data class ParityMetrics(
        val elementCount: Int,
        val actualFiniteCount: Int,
        val actualNanCount: Int,
        val actualPositiveInfinityCount: Int,
        val actualNegativeInfinityCount: Int,
        val referenceNonFiniteCount: Int,
        val maxAbsoluteError: Double,
        val meanAbsoluteError: Double,
        val rmse: Double,
        val referenceMin: Double,
        val referenceMax: Double,
        val referenceMean: Double,
        val actualMin: Double,
        val actualMax: Double,
        val actualMean: Double,
        val largestErrorIndex: Int,
        val referenceAtLargestError: Float,
        val actualAtLargestError: Float,
        val channels: List<ChannelMetrics>,
    )

    companion object {
        private const val MODEL_MANIFEST = "raw_ai/model_manifest.json"
        private const val REFERENCE_MANIFEST = "raw_ai/reference/reference_manifest.json"
        private const val LITERT_ARTIFACT = "org.tensorflow:tensorflow-lite:2.16.1"
        private const val CPU_THREADS = 1
        private const val MAX_ABSOLUTE_ERROR = 1.0e-4
        private const val MEAN_ABSOLUTE_ERROR = 1.0e-5
        private const val RMSE = 1.0e-5
        private const val DETERMINISM_MAX_ERROR = 1.0e-7
        private const val DETERMINISM_MEAN_ERROR = 1.0e-8
    }
}
