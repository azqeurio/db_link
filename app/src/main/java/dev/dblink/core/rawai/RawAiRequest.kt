package dev.dblink.core.rawai

data class RawAiRequest(
    val inputPath: String,
    val outputPath: String,
    val width: Int,
    val height: Int,
    val iso: Float,
    val preprocessProfile: PreprocessProfile,
    val preferredBackend: BackendPreference = BackendPreference.AUTO,
)

enum class PreprocessProfile {
    TREENET_STANDARD,
    RAWPY_AHD
}

enum class BackendPreference {
    AUTO,
    NPU,
    GPU,
    CPU,
    DISABLED,
}
