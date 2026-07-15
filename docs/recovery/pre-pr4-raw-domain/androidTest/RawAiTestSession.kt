package dev.dblink.core.rawai

import android.content.Context
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class ModelPrecision { FP32, FP16 }

/** Android-test-only CPU session. One interpreter and reusable buffers are retained for all tiles. */
class RawAiTestSession(
    context: Context,
    modelName: String,
    expectedSha256: String,
    val precision: ModelPrecision,
    private val cpuThreads: Int = 1,
) : Closeable {
    private val bytesPerElement = if (precision == ModelPrecision.FP32) 4 else 2
    private val elements = 256 * 256 * 3
    private val inputBuffer = ByteBuffer.allocateDirect(elements * bytesPerElement).order(ByteOrder.nativeOrder())
    private val conditionBuffer = ByteBuffer.allocateDirect(bytesPerElement).order(ByteOrder.nativeOrder())
    private val outputBuffer = ByteBuffer.allocateDirect(elements * bytesPerElement).order(ByteOrder.nativeOrder())
    private val outputMap = mutableMapOf<Int, Any>(0 to outputBuffer)
    private val interpreter: Interpreter
    val modelPath: String
    val modelSha256: String
    val modelLoadMillis: Double
    @Volatile var isClosed: Boolean = false
        private set

    init {
        check(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) { "PR 3 buffers require little-endian Android ABI" }
        val started = System.nanoTime()
        val modelFile = ModelAssetStore.stage(context, modelName, expectedSha256)
        modelPath = modelFile.absolutePath
        modelSha256 = ModelAssetStore.sha256(modelFile)
        check(modelSha256 == expectedSha256.lowercase()) {
            "Model hash mismatch: expected=$expectedSha256 actual=$modelSha256 path=$modelPath"
        }
        interpreter = Interpreter(
            modelFile,
            Interpreter.Options().setNumThreads(cpuThreads).setUseNNAPI(false).setUseXNNPACK(false),
        )
        modelLoadMillis = (System.nanoTime() - started) / 1e6
        validateTensorContract()
    }

    @Synchronized
    fun run(inputHwc: FloatArray, condition: Float, outputNchw: FloatArray) {
        check(!isClosed) { "RawAiTestSession is closed" }
        require(inputHwc.size == elements) { "Input element count mismatch: expected=$elements actual=${inputHwc.size}" }
        require(outputNchw.size == elements) { "Output element count mismatch: expected=$elements actual=${outputNchw.size}" }
        inputBuffer.clear()
        conditionBuffer.clear()
        outputBuffer.clear()
        when (precision) {
            ModelPrecision.FP32 -> {
                inputHwc.forEach(inputBuffer::putFloat)
                conditionBuffer.putFloat(condition)
            }
            ModelPrecision.FP16 -> {
                inputHwc.forEach { inputBuffer.putShort(HalfFloat.fromFloat(it)) }
                conditionBuffer.putShort(HalfFloat.fromFloat(condition))
            }
        }
        inputBuffer.rewind()
        conditionBuffer.rewind()
        outputBuffer.rewind()
        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer, conditionBuffer), outputMap)
        outputBuffer.rewind()
        when (precision) {
            ModelPrecision.FP32 -> for (index in outputNchw.indices) outputNchw[index] = outputBuffer.float
            ModelPrecision.FP16 -> for (index in outputNchw.indices) outputNchw[index] = HalfFloat.toFloat(outputBuffer.short)
        }
        check(!outputBuffer.hasRemaining()) { "Output tensor contained trailing bytes: ${outputBuffer.remaining()}" }
    }

    fun warmUp(inputHwc: FloatArray, condition: Float = 0f): Double {
        val output = FloatArray(elements)
        val started = System.nanoTime()
        run(inputHwc, condition, output)
        return (System.nanoTime() - started) / 1e6
    }

    private fun validateTensorContract() {
        check(interpreter.inputTensorCount == 2 && interpreter.outputTensorCount == 1) {
            "Tensor count mismatch for $precision: inputs=${interpreter.inputTensorCount} outputs=${interpreter.outputTensorCount}"
        }
        val input = interpreter.getInputTensor(0)
        val condition = interpreter.getInputTensor(1)
        val output = interpreter.getOutputTensor(0)
        val inputType = runCatching { input.dataType() }.getOrNull()
        val conditionType = runCatching { condition.dataType() }.getOrNull()
        val outputType = runCatching { output.dataType() }.getOrNull()
        val expectedType = if (precision == ModelPrecision.FP32) DataType.FLOAT32 else null
        check(input.name() == "input" && input.shape().contentEquals(intArrayOf(1, 256, 256, 3)) &&
            input.numBytes() == elements * bytesPerElement && (expectedType == null || inputType == expectedType)) {
            "FP tensor mismatch: expected=input [1,256,256,3] precision=$precision bytes=${elements * bytesPerElement} " +
                "actual=${input.name()} ${input.shape().contentToString()} type=$inputType bytes=${input.numBytes()} model=$modelPath"
        }
        check(condition.name() == "cond" && condition.shape().contentEquals(intArrayOf(1, 1)) &&
            condition.numBytes() == bytesPerElement && (expectedType == null || conditionType == expectedType)) {
            "Condition tensor mismatch: expected=cond [1,1] precision=$precision bytes=$bytesPerElement " +
                "actual=${condition.name()} ${condition.shape().contentToString()} type=$conditionType bytes=${condition.numBytes()} model=$modelPath"
        }
        check(output.name() == "output" && output.shape().contentEquals(intArrayOf(1, 3, 256, 256)) &&
            output.numBytes() == elements * bytesPerElement && (expectedType == null || outputType == expectedType)) {
            "Output tensor mismatch: expected=output [1,3,256,256] precision=$precision bytes=${elements * bytesPerElement} " +
                "actual=${output.name()} ${output.shape().contentToString()} type=$outputType bytes=${output.numBytes()} model=$modelPath"
        }
        println("PR3_CONTRACT precision=$precision input=${input.name()}:${input.shape().contentToString()}:type=$inputType:bytes=${input.numBytes()} " +
            "condition=${condition.name()}:${condition.shape().contentToString()}:type=$conditionType:bytes=${condition.numBytes()} " +
            "output=${output.name()}:${output.shape().contentToString()}:type=$outputType:bytes=${output.numBytes()}")
    }

    @Synchronized
    override fun close() {
        if (!isClosed) {
            interpreter.close()
            isClosed = true
        }
    }
}
