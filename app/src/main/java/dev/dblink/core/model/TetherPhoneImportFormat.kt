package dev.dblink.core.model

enum class TetherPhoneImportFormat(
    val preferenceValue: String,
) {
    JpegOnly("jpeg_only"),
    RawOnly("raw_only");

    companion object {
        fun fromPreferenceValue(value: String): TetherPhoneImportFormat = when (value) {
            "jpeg_and_raw" -> RawOnly
            else -> entries.firstOrNull { it.preferenceValue == value } ?: JpegOnly
        }
    }
}
