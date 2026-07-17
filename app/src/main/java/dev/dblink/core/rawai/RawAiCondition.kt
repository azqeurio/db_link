package dev.dblink.core.rawai

/** Shared ISO conditioning contract used by both Phase 5 model variants. */
object RawAiCondition {
    const val MAX_ISO = 65_535f
    const val SCALE_ISO = 6_400f

    fun fromIso(iso: Float): Float {
        require(iso.isFinite() && iso >= 0f) { "ISO must be finite and non-negative: $iso" }
        return iso.coerceAtMost(MAX_ISO) / SCALE_ISO
    }
}
