package dev.dblink.core.protocol

import dev.dblink.core.model.ConnectMode

object CameraHttpProbeValidator {
    private val cameraIdentityPattern = Regex(
        pattern = """(?i)\b(olympus|om\s*system|om\s*digital|om-\d|e-m\d|e-p\d|pen[-\s]|tg-\d)""",
    )
    private val camInfoFieldPattern = Regex(
        pattern = """(?i)<\s*(model|modelname|bodyname|cameraname|firmwareversion|fwversion|batterypercent|batterylevel)\b""",
    )

    fun isCameraConnectModeResponse(raw: String): Boolean {
        val normalized = raw.trim()
        if (normalized.isBlank() || !normalized.contains("connectmode", ignoreCase = true)) {
            return false
        }
        return ConnectModeParser.parse(normalized) != ConnectMode.Unknown
    }

    fun isCameraInfoResponse(raw: String): Boolean {
        val normalized = raw.trim()
        if (normalized.isBlank()) {
            return false
        }
        if (!camInfoFieldPattern.containsMatchIn(normalized)) {
            return false
        }
        return cameraIdentityPattern.containsMatchIn(normalized)
    }
}
