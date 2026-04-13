package dev.dblink.core.protocol

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object PropertyFormatter {

    private val wbNames = mapOf(
        "0" to "AWB",
        "16" to "Sunny",
        "17" to "Shade",
        "18" to "Cloudy",
        "20" to "Tungsten",
        "35" to "Fluorescent",
        "64" to "Flash",
        "23" to "Underwater",
        "256" to "CWB1",
        "257" to "CWB2",
        "258" to "CWB3",
        "259" to "CWB4",
        "512" to "Kelvin",
    )

    fun formatForDisplay(propName: String, rawValue: String): String {
        if (rawValue.isBlank()) return rawValue
        return when (propName) {
            "focalvalue" -> formatAperture(rawValue)
            "shutspeedvalue" -> formatShutterSpeed(rawValue)
            "isospeedvalue" -> formatIsoDisplay(rawValue)
            "wbvalue" -> wbNames[rawValue] ?: rawValue
            "expcomp" -> formatExposureCompensation(rawValue)
            else -> rawValue
        }
    }

    private fun formatAperture(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.toDoubleOrNull() != null) {
            "F$trimmed"
        } else {
            trimmed
        }
    }

    private fun formatShutterSpeed(raw: String): String {
        if (raw.contains("\"") || raw.contains("\u201D")) return raw
        val numeric = raw.toDoubleOrNull() ?: return raw
        return if (numeric >= 4) "1/${raw}" else raw
    }

    fun formatIsoDisplay(raw: String, autoActive: Boolean = false): String {
        return when {
            autoActive -> "Auto"
            raw.equals("auto", ignoreCase = true) -> "Auto"
            raw.equals("AUTO", ignoreCase = true) -> "Auto"
            else -> raw
        }
    }

    fun exposureCompensationNumericValue(rawValue: String?): Double? {
        val normalized = rawValue
            ?.trim()
            ?.replace("EV", "", ignoreCase = true)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val numeric = normalized.toDoubleOrNull() ?: return null
        val interpreted = when {
            normalized.contains(".") -> numeric
            abs(numeric) <= 7.0 -> numeric
            else -> numeric / 100.0
        }
        return snapExposureCompensation(interpreted)
    }

    fun exposureCompensationDisplayValue(rawValue: String?): String {
        val numeric = exposureCompensationNumericValue(rawValue) ?: return rawValue?.trim().orEmpty()
        return when {
            numeric > 0.0 -> String.format(Locale.US, "+%.1f", numeric)
            numeric < 0.0 -> String.format(Locale.US, "%.1f", numeric)
            else -> "0.0"
        }
    }

    private fun formatExposureCompensation(raw: String): String {
        return exposureCompensationDisplayValue(raw)
    }

    fun isAutoValue(propName: String, rawValue: String): Boolean {
        return when (propName) {
            "isospeedvalue" -> rawValue.equals("Auto", ignoreCase = true) ||
                rawValue.equals("AUTO", ignoreCase = true)
            "wbvalue" -> rawValue == "0" || rawValue.equals("AWB", ignoreCase = true)
            else -> false
        }
    }

    fun normalizeSetValue(propName: String, rawValue: String): String {
        val normalized = rawValue.trim()
        return when (propName) {
            "isospeedvalue" -> if (normalized.equals("Auto", ignoreCase = true)) "AUTO" else normalized
            "wbvalue" -> if (
                normalized.equals("Auto", ignoreCase = true) ||
                normalized.equals("AWB", ignoreCase = true)
            ) {
                "0"
            } else {
                normalized
            }
            "expcomp" -> exposureCompensationDisplayValue(normalized).ifBlank { normalized }
            else -> normalized
        }
    }

    fun propNameForPicker(pickerName: String): String = when (pickerName) {
        "Aperture" -> "focalvalue"
        "ShutterSpeed" -> "shutspeedvalue"
        "Iso" -> "isospeedvalue"
        "WhiteBalance" -> "wbvalue"
        else -> ""
    }

    private fun snapExposureCompensation(value: Double): Double {
        if (abs(value) < 0.05) return 0.0
        val snappedThirds = (value * 3.0).roundToInt() / 3.0
        return if (abs(value - snappedThirds) <= 0.06) {
            snappedThirds
        } else {
            (value * 10.0).roundToInt() / 10.0
        }
    }
}
