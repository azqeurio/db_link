package dev.dblink.core.model

object CameraNameNormalizer {
    private val pairingSuffixPatterns = listOf(
        Regex("""^(.*?)-P-[A-Z0-9]{4,}$""", RegexOption.IGNORE_CASE),
        Regex("""^(.*?)_P_[A-Z0-9]{4,}$""", RegexOption.IGNORE_CASE),
    )

    fun normalizeModelName(raw: String?): String? {
        val trimmed = raw
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotBlank() }
            ?: return null

        pairingSuffixPatterns.forEach { pattern ->
            val match = pattern.matchEntire(trimmed) ?: return@forEach
            val candidate = match.groupValues[1].trim()
            if (candidate.isNotBlank()) {
                return candidate
            }
        }

        return trimmed
    }

    fun normalizeSsid(raw: String?): String? {
        val trimmed = raw
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotBlank() }
            ?: return null

        pairingSuffixPatterns.forEach { pattern ->
            val match = pattern.matchEntire(trimmed) ?: return@forEach
            val candidate = match.groupValues[1].trim()
            if (looksLikeOlympusModel(candidate)) {
                return candidate
            }
        }

        val separators = listOf('_', '-')
        separators.forEach { separator ->
            val cutIndex = trimmed.lastIndexOf(separator)
            if (cutIndex <= 0 || cutIndex >= trimmed.lastIndex) return@forEach
            val prefix = trimmed.substring(0, cutIndex).trim()
            val suffix = trimmed.substring(cutIndex + 1)
            if (looksLikeOlympusModel(prefix) && suffix.length >= 5 && suffix.all { it.isLetterOrDigit() }) {
                return prefix
            }
        }

        return null
    }

    fun savedCameraDisplayName(
        preferredName: String? = null,
        ssid: String,
    ): String {
        return normalizeModelName(preferredName)
            ?: normalizeSsid(ssid)
            ?: ssid.trim()
    }

    fun extractPairingToken(raw: String?): String? {
        val trimmed = raw
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val normalized = trimmed.replace('_', '-')
        val segments = normalized.split('-').filter { it.isNotBlank() }
        val candidate = segments.lastOrNull()
            ?.takeIf { it.length >= 6 && it.all { char -> char.isLetterOrDigit() } }
            ?: return null
        return candidate.uppercase()
    }

    private fun looksLikeOlympusModel(value: String): Boolean {
        val normalized = value.uppercase()
        return normalized.startsWith("OM-") ||
            normalized.startsWith("E-") ||
            normalized.startsWith("TG-") ||
            normalized.startsWith("SH-") ||
            normalized.startsWith("XZ-") ||
            normalized.startsWith("SP-") ||
            normalized.startsWith("AIR-") ||
            normalized.startsWith("PEN") ||
            normalized.startsWith("STYLUS")
    }
}
