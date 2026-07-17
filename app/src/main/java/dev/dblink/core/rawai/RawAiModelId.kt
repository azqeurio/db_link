package dev.dblink.core.rawai

/** The only RAW AI models that Phase 5 permits the Android runtime to select. */
enum class RawAiModelId(val stableId: String, val manifestKey: String) {
    RAWFORGE_STANDARD_FP32("RAWFORGE_STANDARD_FP32", "standard"),
    RAWFORGE_SUPERLIGHT_FP32("RAWFORGE_SUPERLIGHT_FP32", "superlight"),
}
