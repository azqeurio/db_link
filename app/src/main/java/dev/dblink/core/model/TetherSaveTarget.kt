package dev.dblink.core.model

enum class TetherSaveTarget(
    val preferenceValue: String,
) {
    SdCard("sd_card"),
    SdAndPhone("sd_and_phone"),
    Phone("phone");

    val savesToPhone: Boolean
        get() = this != SdCard

    companion object {
        fun fromPreferenceValue(value: String): TetherSaveTarget =
            entries.firstOrNull { it.preferenceValue == value } ?: SdAndPhone
    }
}
