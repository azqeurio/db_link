package dev.dblink.feature.qr

import dev.dblink.core.logging.D

data class WifiCredentials(
    val ssid: String,
    val password: String,
    val bleName: String? = null,
    val blePass: String? = null,
)

object WifiQrParser {
    /**
     * Parse Wi-Fi QR code generic and Olympus format.
     * Example: WIFI:S:MySSID;T:WPA;P:MyPass;;
     */
    fun parse(qrContent: String): WifiCredentials? {
        D.qr("parsing qrContent startsWith(OIS)=${qrContent.startsWith("OIS")}, startsWith(WIFI)=${qrContent.startsWith("WIFI:")}")
        D.qr("raw length=${qrContent.length}, content='$qrContent'")
        if (qrContent.startsWith("WIFI:")) {
            return parseStandardWifi(qrContent)
        }
        if (qrContent.startsWith("OIS")) {
            val result = parseOlympusWifi(qrContent)
            if (result == null) {
                D.err("QR", "Failed to parse Olympus format string.", null)
            } else {
                D.qr("Successfully parsed Olympus QR.")
            }
            return result
        }
        return null
    }

    private fun parseStandardWifi(qrContent: String): WifiCredentials? {
        var ssid: String? = null
        var password = ""

        val segments = qrContent.removePrefix("WIFI:").split(";")
        for (segment in segments) {
            when {
                segment.startsWith("S:") -> ssid = segment.removePrefix("S:")
                segment.startsWith("P:") -> password = segment.removePrefix("P:")
            }
        }

        return ssid?.let { WifiCredentials(it, password) }
    }

    private val olympusCharMap: Map<Char, Char> = buildMap {
        val syms = listOf('0' to '/', '1' to '-', '2' to '+', '3' to '*', '4' to '%', '5' to '$')
        syms.forEach { (a, b) ->
            put(a, b)
            put(b, a)
        }
        for (c in 'A'..'V') put(c, 'V' - (c - 'A'))
        put('W', '9')
        put('9', 'W')
        put('X', '8')
        put('8', 'X')
        put('Y', '7')
        put('7', 'Y')
        put('Z', '6')
        put('6', 'Z')
        for (c in 'a'..'z') put(c, 'z' - (c - 'a'))
        put(',', ',')
        put(' ', ' ')
    }

    private fun decodeOlympusString(str: String): String {
        return str.map { olympusCharMap[it] ?: it }.joinToString("")
    }

    private fun parseOlympusWifi(qrContent: String): WifiCredentials? {
        val parts = qrContent.split(",")
        if (parts.isEmpty()) return null
        
        when (parts[0]) {
            "OIS1" -> {
                if (parts.size >= 3) {
                    val ssid = decodeOlympusString(parts[1])
                    val password = decodeOlympusString(parts[2])
                    return WifiCredentials(ssid, password)
                }
            }
            "OIS2" -> {
                if (parts.size >= 4) {
                    val version = parts.getOrNull(1)?.toIntOrNull() ?: return null
                    val ssid = decodeOlympusString(parts[2])
                    val password = decodeOlympusString(parts[3])
                    val bleName = if (version == 3 && parts.size >= 6) decodeOlympusString(parts[4]) else null
                    val blePass = if (version == 3 && parts.size >= 6) decodeOlympusString(parts[5]) else null
                    D.qr(
                        "Parsed OIS2 QR: version=$version, ssid=$ssid, bleName=$bleName, " +
                            "blePassSet=${!blePass.isNullOrBlank()}, blePassLen=${blePass?.length ?: 0}",
                    )
                    return WifiCredentials(
                        ssid = ssid,
                        password = password,
                        bleName = bleName,
                        blePass = blePass,
                    )
                }
            }
            "OIS3" -> {
                if (parts.size >= 5) {
                    val version = parts.getOrNull(1)?.toIntOrNull() ?: return null
                    val ssid = decodeOlympusString(parts[3])
                    val password = decodeOlympusString(parts[4])
                    val bleName = if (version == 3 && parts.size >= 7) decodeOlympusString(parts[5]) else null
                    val blePass = if (version == 3 && parts.size >= 7) decodeOlympusString(parts[6]) else null
                    D.qr(
                        "Parsed OIS3 QR: version=$version, ssid=$ssid, bleName=$bleName, " +
                            "blePassSet=${!blePass.isNullOrBlank()}, blePassLen=${blePass?.length ?: 0}",
                    )
                    return WifiCredentials(
                        ssid = ssid,
                        password = password,
                        bleName = bleName,
                        blePass = blePass,
                    )
                }
            }
        }
        return null
    }
}
