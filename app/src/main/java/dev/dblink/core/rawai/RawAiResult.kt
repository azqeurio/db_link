package dev.dblink.core.rawai

data class RawAiResult(
    val success: Boolean,
    val selectedBackend: String,
    val fallbackReason: String?,
    val errorCode: String?,
    val errorMessage: String?,
    val initializationMs: Double,
    val processingMs: Double,
    val peakMemoryBytes: Long,
)
