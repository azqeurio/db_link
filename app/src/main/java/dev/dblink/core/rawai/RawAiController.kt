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
    private var initializedModel: RawAiModelId? = null

    /**
     * Initializes the RawAiEngine by copying models, validating SHA-256 hashes,
     * and setting up the native LiteRT CompiledModel handle.
     */
    @Synchronized
    fun initialize(modelId: RawAiModelId): Boolean {
        if (isInitialized) return initializedModel == modelId

        try {
            val manifestJson = context.assets.open("raw_ai/model_manifest.json").bufferedReader().use { it.readText() }
            val manifestObj = JSONObject(manifestJson)
            val filesObj = manifestObj.getJSONObject("model_files")

            val modelInfo = filesObj.getJSONObject(modelId.manifestKey)
            check(modelInfo.getString("id") == modelId.stableId) {
                "Manifest model ID mismatch for ${modelId.stableId}"
            }
            val modelFile = ModelAssetStore.stage(
                context,
                modelInfo.getString("path"),
                modelInfo.getString("sha256"),
            )

            // Selection is explicit: initialize exactly the requested model, with no fallback.
            enginePointer = createNativeEngine(modelFile.absolutePath, manifestJson)
            if (enginePointer == 0L) {
                return false
            }

            isInitialized = true
            initializedModel = modelId
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

        if (request.modelId != initializedModel) {
            return RawAiResult(
                success = false,
                selectedBackend = "DISABLED",
                fallbackReason = null,
                errorCode = "EXPLICIT_MODEL_SELECTION_REQUIRED",
                errorMessage = "Requested ${request.modelId.stableId}, but ${initializedModel?.stableId} is initialized. " +
                    "Release and explicitly initialize the requested model; no model fallback was attempted.",
                initializationMs = 0.0,
                processingMs = 0.0,
                peakMemoryBytes = 0,
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
        initializedModel = null
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
