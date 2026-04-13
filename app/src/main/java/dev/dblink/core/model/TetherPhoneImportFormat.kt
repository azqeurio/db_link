package dev.dblink.core.model

enum class TetherPhoneImportFormat(
    val preferenceValue: String,
) {
    JpegOnly("jpeg_only"),
    RawOnly("raw_only"),
    JpegAndRaw("jpeg_and_raw");

    companion object {
        fun fromPreferenceValue(value: String): TetherPhoneImportFormat =
            entries.firstOrNull { it.preferenceValue == value } ?: JpegOnly
    }
}
