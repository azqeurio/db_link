package dev.dblink.core.rawai

enum class RawAiStatus {
    IDLE,
    INITIALIZING,
    PREPROCESSING,
    INFERENCE,
    BLENDING,
    COMPLETED,
    FAILED,
    CANCELLED
}
