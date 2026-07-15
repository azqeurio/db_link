package dev.dblink.core.rawai

import android.content.Context
import org.json.JSONObject

class RawAiController(private val context: Context) {

    companion object {
        init {
            // Load native library that implements the raw_ai JNI interfaces
            System.loadLibrary("deepskynative")
        }
    }

    private var enginePointer: Long = 0
    private var isInitialized = false

    /**
     * Initializes the RawAiEngine by copying models, validating SHA-256 hashes,
     * and setting up the native LiteRT CompiledModel handle.
     */
    @Synchronized
    fun initialize(): Boolean {
        if (isInitialized) return true

        try {
            val manifestJson = context.assets.open("raw_ai/model_manifest.json").bufferedReader().use { it.readText() }
            val manifestObj = JSONObject(manifestJson)
            val filesObj = manifestObj.getJSONObject("model_files")

            val fp32Info = filesObj.getJSONObject("cpu_reference")
            val fp32Name = fp32Info.getString("path")
            val fp32Sha = fp32Info.getString("sha256")

            val fp16Info = filesObj.getJSONObject("accelerated")
            val fp16Name = fp16Info.getString("path")
            val fp16Sha = fp16Info.getString("sha256")

            // 1. Copy and validate FP32 model
            val fp32LocalFile = ModelAssetStore.stage(context, fp32Name, fp32Sha)

            // 2. Copy and validate FP16 model
            ModelAssetStore.stage(context, fp16Name, fp16Sha)

            // 3. Create Native Engine passing the manifest JSON and the validated model path
            // We pass the parent models directory or specific model path.
            // Under PR 2 CPU Single-Tile, we pass the validated FP32 model path to the engine.
            enginePointer = createNativeEngine(fp32LocalFile.absolutePath, manifestJson)
            if (enginePointer == 0L) {
                return false
            }

            isInitialized = true
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * Executes the raw denoising on a target RAW file.
     */
    @Synchronized
    fun processRaw(request: RawAiRequest): RawAiResult {
        if (!isInitialized || enginePointer == 0L) {
            return RawAiResult(
                success = false,
                selectedBackend = "DISABLED",
                fallbackReason = "Engine not initialized",
                errorCode = "ENGINE_NOT_INITIALIZED",
                errorMessage = "Engine was not successfully initialized.",
                initializationMs = 0.0,
                processingMs = 0.0,
                peakMemoryBytes = 0
            )
        }

        // Map enum configurations to int constants for JNI crossing
        val profileVal = request.preprocessProfile.ordinal
        val backendVal = request.preferredBackend.ordinal

        return process(
            enginePointer,
            request.inputPath,
            request.outputPath,
            request.width,
            request.height,
            request.iso,
            profileVal,
            backendVal
        )
    }

    /**
     * Cancels the active inference processing.
     */
    fun cancelProcessing() {
        if (enginePointer != 0L) {
            cancel(enginePointer)
        }
    }

    /**
     * Releases the native engine allocations.
     */
    @Synchronized
    fun release() {
        if (enginePointer != 0L) {
            destroyEngine(enginePointer)
            enginePointer = 0L
        }
        isInitialized = false
    }

    // ==========================================
    // Native JNI Declarations
    // ==========================================
    private external fun createNativeEngine(modelPath: String, manifestJson: String): Long
    private external fun process(
        enginePtr: Long,
        inputPath: String,
        outputPath: String,
        width: Int,
        height: Int,
        iso: Float,
        preprocessProfile: Int,
        preferredBackend: Int
    ): RawAiResult
    private external fun cancel(enginePtr: Long)
    private external fun destroyEngine(enginePtr: Long)
}
