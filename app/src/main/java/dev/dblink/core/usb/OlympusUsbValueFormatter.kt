package dev.dblink.core.usb

import dev.dblink.core.model.CameraExposureMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun olympusExposureModeFromRaw(rawValue: Long): CameraExposureMode? {
    return when (rawValue.toInt()) {
        // OM-1 / OM System ExposureMode (0xd01d) — enum[4]=4,1,2,3
        1 -> CameraExposureMode.M
        2 -> CameraExposureMode.P
        3 -> CameraExposureMode.A
        4 -> CameraExposureMode.S
        5, 6, 7 -> CameraExposureMode.B
        8 -> CameraExposureMode.VIDEO
        else -> null
    }
}

internal fun formatOlympusExposureMode(rawValue: Long): String {
    return when (rawValue.toInt()) {
        // OM-1 / OM System ExposureMode (0xd01d) — enum[4]=4,1,2,3
        1 -> "M"
        2 -> "P"
        3 -> "A"
        4 -> "S"
        5 -> "Bulb"
        6 -> "Time"
        7 -> "Composite"
        8 -> "Movie"
        else -> "Mode 0x${rawValue.toString(16)}"
    }
}

internal fun formatOlympusAperture(rawValue: Long): String {
    if (rawValue <= 0L) return "--"
    val fNumber = rawValue / 10.0
    return if (abs(fNumber - fNumber.toLong()) < 0.001) {
        "F${fNumber.toLong()}"
    } else {
        "F${String.format(Locale.ENGLISH, "%.1f", fNumber)}"
    }
}

internal fun olympusApertureFNumber(rawValue: Long): Float? {
    if (rawValue <= 0L) return null
    return (rawValue / 10.0).toFloat()
}

internal fun formatOlympusApertureValueOnly(rawValue: Long): String {
    if (rawValue <= 0L) return ""
    val fNumber = rawValue / 10.0
    return if (abs(fNumber - fNumber.toLong()) < 0.001) {
        fNumber.toLong().toString()
    } else {
        String.format(Locale.ENGLISH, "%.1f", fNumber)
    }
}

internal fun formatOlympusShutterSpeed(rawValue: Long): String {
    if (rawValue < 0L) return "--"
    return when (rawValue) {
        0xFFFF_FFFCL -> "Bulb"
        0xFFFF_FFFBL -> "Time"
        0xFFFF_FFFAL -> "Composite"
        else -> {
            var x = ((rawValue ushr 16) and 0xFFFF).toInt()
            var y = (rawValue and 0xFFFF).toInt()
            if (x <= 0 || y <= 0) return "0x${rawValue.toString(16)}"
            if (x % 10 == 0 && y % 10 == 0) {
                x /= 10
                y /= 10
            }
            when {
                y == 1 -> "${x}\""
                y == 10 -> {
                    // Decimal-seconds value: 0.3", 0.5", 0.8", 1.3", 1.6", 2.5", 3.2" …
                    val seconds = x / 10.0
                    if (seconds == seconds.toLong().toDouble()) {
                        "${seconds.toLong()}\""
                    } else {
                        String.format(Locale.ENGLISH, "%.1f", seconds) + "\""
                    }
                }
                else -> "$x/$y"
            }
        }
    }
}

internal fun formatOlympusShutterSpeedValueOnly(rawValue: Long): String {
    if (rawValue < 0L) return ""
    return when (rawValue) {
        0xFFFF_FFFCL -> "Bulb"
        0xFFFF_FFFBL -> "Time"
        0xFFFF_FFFAL -> "Composite"
        else -> {
            var x = ((rawValue ushr 16) and 0xFFFF).toInt()
            var y = (rawValue and 0xFFFF).toInt()
            if (x <= 0 || y <= 0) return ""
            if (x % 10 == 0 && y % 10 == 0) {
                x /= 10
                y /= 10
            }
            when {
                y == 1 -> x.toString()
                y == 10 -> {
                    val seconds = x / 10.0
                    if (seconds == seconds.toLong().toDouble()) {
                        seconds.toLong().toString()
                    } else {
                        String.format(Locale.ENGLISH, "%.1f", seconds)
                    }
                }
                else -> "$x/$y"
            }
        }
    }
}

internal fun olympusShutterSpeedSeconds(rawValue: Long): Double? {
    if (rawValue < 0L) return null
    return when (rawValue) {
        0xFFFF_FFFCL, 0xFFFF_FFFBL, 0xFFFF_FFFAL -> null
        else -> {
            var numerator = ((rawValue ushr 16) and 0xFFFF).toInt()
            var denominator = (rawValue and 0xFFFF).toInt()
            if (numerator <= 0 || denominator <= 0) return null
            if (numerator % 10 == 0 && denominator % 10 == 0) {
                numerator /= 10
                denominator /= 10
            }
            numerator.toDouble() / denominator.toDouble()
        }
    }
}

internal fun olympusFocalLengthMillimeters(rawValue: Long): Float? {
    if (rawValue <= 0L) return null
    return (rawValue / 100.0).toFloat()
}

internal fun formatOlympusFocalLength(rawValue: Long): String {
    val focalLengthMm = olympusFocalLengthMillimeters(rawValue) ?: return "--"
    return if (abs(focalLengthMm - focalLengthMm.roundToInt()) < 0.05f) {
        "${focalLengthMm.roundToInt()} mm"
    } else {
        String.format(Locale.ENGLISH, "%.1f mm", focalLengthMm)
    }
}

internal fun formatOlympusIso(rawValue: Long): String {
    val value = rawValue.toInt()
    return when {
        // Explicit Auto markers
        value == 0 || value == 0xFFFF || value == 0xFFFE -> "Auto"
        // OM-1 / OM System: 0xd005 uses small index values (1–4) for ISO sensitivity
        // mode rather than actual ISO numbers.  The camera does not expose numeric
        // ISO over PTP, so we show the mode.
        value == 1 -> "Low"
        value == 2 -> "Auto"
        value == 3 -> "Auto"
        // 0x8012 = 32786 observed on OM-1 in M mode — additional auto variant
        value and 0x8000 != 0 -> "Auto"
        // Actual ISO values from older cameras (≥ 50)
        value >= 50 -> value.toString()
        // Unknown small values — show as index
        else -> "ISO($value)"
    }
}

/**
 * Detect EV raw value scale factor.
 * OM-1 / OM System uses ×1000 (e.g., 300 = 0.3 EV, range ±5000).
 * Older OM-D cameras use ×10 (e.g., 3 = 0.3 EV, range ±50).
 * Threshold: if abs(signed) > 60, it must be ×1000 because ×10 max is ±50.
 */
private fun olympusEvDivisor(signedValue: Int): Double =
    if (signedValue == 0 || abs(signedValue) > 60) 1000.0 else 10.0

internal fun formatOlympusExposureComp(rawValue: Long): String {
    val signed = rawValue.toShort().toInt()
    val divisor = olympusEvDivisor(signed)
    val ev = signed / divisor
    val snapped = if (abs(ev) < 0.05) 0.0 else ev
    return when {
        snapped > 0 -> String.format(Locale.ENGLISH, "+%.1f", snapped)
        snapped < 0 -> String.format(Locale.ENGLISH, "%.1f", snapped)
        else -> "0.0"
    }
}

/**
 * Parse an EV display string ("+0.3", "-1.0", "0.0") back to a raw value.
 * Returns ×10 encoded value (legacy format). Used for float extraction
 * (3 → 3/10f = 0.3f) and as fallback in resolveUsbRawValue.
 * For ×1000 cameras, resolveUsbRawValue prefers candidate matching which
 * returns the camera's actual raw value directly.
 */
internal fun parseOlympusExposureCompRaw(displayValue: String): Long? {
    val numeric = displayValue
        .trim()
        .replace("EV", "", ignoreCase = true)
        .trim()
        .toDoubleOrNull()
        ?: return null
    return (numeric * 10.0).roundToInt().toLong()
}

internal fun formatOlympusWhiteBalance(rawValue: Long): String {
    return when (rawValue.toInt()) {
        1 -> "AWB"
        2 -> "Daylight"
        3 -> "Shade"
        4 -> "Cloudy"
        5 -> "Tungsten"
        6 -> "Fluorescent"
        7 -> "Underwater"
        8 -> "Flash"
        9 -> "Preset 1"
        10 -> "Preset 2"
        11 -> "Preset 3"
        12 -> "Preset 4"
        13 -> "Custom"
        else -> "WB 0x${rawValue.toString(16)}"
    }
}

internal fun formatOlympusFocusMode(rawValue: Long): String {
    return when (rawValue.toInt()) {
        0x0001 -> "MF"
        0x0002 -> "S-AF"
        0x8001 -> "S-AF+MF"
        0x8003 -> "C-AF+MF"
        0x8004 -> "Pre MF"
        0x8005 -> "S-AF"
        0x8006 -> "C-AF+TR"
        0x8002 -> "C-AF"
        0x8007 -> "Starry Sky AF"
        0x8008 -> "Starry Sky AF + MF"
        else -> "AF 0x${rawValue.toString(16)}"
    }
}

internal fun isOlympusManualFocusMode(rawValue: Long): Boolean {
    return rawValue == 0x0001L || rawValue == 0x8001L || rawValue == 0x8004L
}

internal fun formatOlympusMetering(rawValue: Long): String {
    return when (rawValue.toInt()) {
        0x8001 -> "ESP"
        0x0002 -> "Center"
        0x0004 -> "Spot"
        0x8011 -> "Spot Hi"
        0x8012 -> "Spot Sh"
        else -> "0x${rawValue.toString(16)}"
    }
}

internal fun formatOlympusDriveMode(
    rawValue: Long,
    options: List<Long> = emptyList(),
): String {
    return if (isLegacyOlympusDriveLayout(options)) {
        when (rawValue.toInt()) {
            0, 1 -> "Single"
            17 -> "Sequential L"
            33 -> "Sequential H"
            3 -> "Anti-Shock Single"
            35 -> "Anti-Shock Sequential L"
            2 -> "Anti-Shock Sequential H"
            18 -> "Silent Single"
            34 -> "Silent Sequential L"
            4 -> "Silent Sequential H"
            20 -> "Self-timer 2 sec"
            37 -> "Anti-Shock Self-timer 2 sec"
            5 -> "Silent Self-timer 2 sec"
            21 -> "Self-timer 12 sec"
            36 -> "Anti-Shock Self-timer 12 sec"
            6 -> "Silent Self-timer 12 sec"
            22 -> "Custom Self-timer"
            38 -> "Anti-Shock Custom Self-timer"
            48 -> "Silent Custom Self-timer"
            else -> "0x${rawValue.toString(16)}"
        }
    } else {
        when (rawValue.toInt()) {
            0, 1 -> "Single-frame shooting"
            33 -> "Sequential L"
            7 -> "Sequential H"
            39 -> "Silent : Single-frame shooting"
            40 -> "Silent : Sequential H"
            41 -> "Pro Capture SH2"
            67 -> "Pro Capture Low"
            72 -> "Pro Capture SH1"
            73 -> "Pro Capture SH2"
            4 -> "Self-timer"
            5 -> "Self-timer Burst"
            36 -> "Silent : Self-timer"
            6 -> "Timer C"
            else -> "0x${rawValue.toString(16)}"
        }
    }
}

internal fun formatOlympusImageQuality(rawValue: Long): String {
    return when (rawValue.toInt()) {
        0x020 -> "RAW"
        0x101 -> "L-F"
        0x102 -> "L-N"
        0x103 -> "M-N"
        0x104 -> "S-N"
        0x121 -> "L-F+RAW"
        0x122 -> "L-N+RAW"
        0x123 -> "M-N+RAW"
        0x124 -> "S-N+RAW"
        else -> "0x${rawValue.toString(16)}"
    }
}

internal fun formatOlympusFlashMode(rawValue: Long): String {
    return when (rawValue.toInt()) {
        0x0000 -> "Off"
        0x0001 -> "Auto"
        0x0002 -> "Off"
        0x0003 -> "Fill"
        0x0004 -> "Red-eye"
        0x0005 -> "Red-eye Fill"
        0x0006 -> "External"
        else -> "0x${rawValue.toString(16)}"
    }
}

internal fun encodeOlympusAfArea(xNorm: Float, yNorm: Float): Long {
    val col = (xNorm.coerceIn(0f, 1f) * 12f).roundToInt().coerceIn(0, 12)
    val row = (yNorm.coerceIn(0f, 1f) * 8f).roundToInt().coerceIn(0, 8)
    return (41 + (row * 117) + (col * 3)).toLong()
}

private val olympusUsbScpPriority = listOf(
    PtpConstants.Prop.FocalLength,
    0xD00C,
    0xD010,
    0xD065,
    0xD0C7,
    PtpConstants.OlympusProp.AFTargetArea,
    0xD01A,
    0xD0C4,
    0xD0C5,
    0xD100,
    0xD101,
    0xD11A,
    0xD08E,
    0xD11B,
    0xD11C,
)

private val olympusUsbBasePropCodes = setOf(
    PtpConstants.OlympusProp.ShutterSpeed,
    PtpConstants.OlympusProp.Aperture,
    PtpConstants.OlympusProp.FocusMode,
    PtpConstants.OlympusProp.MeteringMode,
    PtpConstants.OlympusProp.ISOSpeed,
    0xD005,
    PtpConstants.OlympusProp.ExposureCompensation,
    PtpConstants.OlympusProp.DriveMode,
    PtpConstants.OlympusProp.ImageQuality,
    PtpConstants.OlympusProp.WhiteBalance,
    PtpConstants.OlympusProp.FlashMode,
    PtpConstants.OlympusProp.ExposureMode,
)

private val olympusUsbHiddenPropCodes = setOf(
    PtpConstants.OlympusProp.LiveViewModeOm,
    PtpConstants.OlympusProp.LiveViewEnabled,
    PtpConstants.OlympusProp.FocusDistance,
    PtpConstants.OlympusProp.AFResult,
)

internal fun shouldExposeUsbScpExtraProperty(propCode: Int): Boolean {
    if (propCode in olympusUsbBasePropCodes || propCode in olympusUsbHiddenPropCodes) {
        return false
    }
    return propCode in olympusUsbScpPriority
}

internal fun olympusUsbPropertyPriority(propCode: Int): Int {
    val index = olympusUsbScpPriority.indexOf(propCode)
    return if (index >= 0) index else 10_000 + propCode
}

internal fun olympusUsbPropertyLabel(propCode: Int): String = when (propCode) {
    PtpConstants.Prop.FocalLength -> "Focal Length"
    PtpConstants.OlympusProp.ShutterSpeed -> "Shutter Speed"
    PtpConstants.OlympusProp.Aperture -> "Aperture"
    PtpConstants.OlympusProp.ISOSpeed -> "ISO"
    0xD005 -> "ISO"
    PtpConstants.OlympusProp.ExposureCompensation -> "EV"
    PtpConstants.OlympusProp.WhiteBalance -> "White Balance"
    PtpConstants.OlympusProp.FocusMode -> "AF Mode"
    PtpConstants.OlympusProp.MeteringMode -> "Metering"
    PtpConstants.OlympusProp.FlashMode -> "Flash"
    PtpConstants.OlympusProp.DriveMode -> "Drive"
    PtpConstants.OlympusProp.ImageQuality -> "Image Quality"
    PtpConstants.OlympusProp.ExposureMode -> "Mode"
    PtpConstants.OlympusProp.AFTargetArea -> "AF Target"
    0xD00C -> "Picture Mode State"
    0xD010 -> "Picture Mode"
    0xD065 -> "High Res State"
    0xD0C7 -> "High Res Shot"
    0xD01A -> "Face / Eye"
    0xD0C4 -> "Face Priority AF"
    0xD0C5 -> "Face / Eye"
    0xD100 -> "Subject Detection"
    0xD101 -> "Subject Type"
    0xD11A -> "Aspect Ratio"
    0xD08E -> "Image Stabilizer"
    0xD11B -> "Image Stabilizer State"
    0xD11C -> "Color Space"
    0xD0CB -> "Save Slot"
    0xD0CC -> "Count"
    0xD0CD -> "Interval"
    0xD0EC -> "Timer Delay"
    else -> "0x${propCode.toString(16).uppercase(Locale.US)}"
}

internal fun formatOlympusUsbPropertyValue(
    propCode: Int,
    rawValue: Long,
    options: List<Long> = emptyList(),
): String = when (propCode) {
    PtpConstants.Prop.FocalLength -> formatOlympusFocalLength(rawValue)
    PtpConstants.OlympusProp.ShutterSpeed -> formatOlympusShutterSpeed(rawValue)
    PtpConstants.OlympusProp.Aperture -> formatOlympusAperture(rawValue)
    PtpConstants.OlympusProp.ISOSpeed -> formatOlympusIso(rawValue)
    0xD005 -> formatOlympusIso(rawValue)
    PtpConstants.OlympusProp.ExposureCompensation -> formatOlympusExposureComp(rawValue)
    PtpConstants.OlympusProp.WhiteBalance -> formatOlympusWhiteBalance(rawValue)
    PtpConstants.OlympusProp.FocusMode -> formatOlympusFocusMode(rawValue)
    PtpConstants.OlympusProp.MeteringMode -> formatOlympusMetering(rawValue)
    PtpConstants.OlympusProp.FlashMode -> formatOlympusFlashMode(rawValue)
    PtpConstants.OlympusProp.DriveMode -> formatOlympusDriveMode(rawValue, options)
    PtpConstants.OlympusProp.ImageQuality -> formatOlympusImageQuality(rawValue)
    PtpConstants.OlympusProp.ExposureMode -> formatOlympusExposureMode(rawValue)
    PtpConstants.OlympusProp.AFTargetArea -> formatOlympusAfTargetArea(rawValue)
    0xD00C -> formatOlympusPictureModeState(rawValue)
    0xD010 -> formatOlympusPictureModeControl(rawValue)
    0xD065 -> formatOlympusHighResState(rawValue)
    0xD0C7 -> formatOlympusHighResControl(rawValue)
    0xD01A -> formatOlympusFaceEyeMode(rawValue)
    0xD0C4 -> formatOlympusFacePriority(rawValue)
    0xD0C5 -> formatOlympusFaceEyeMode(rawValue)
    0xD100 -> formatOlympusSubjectDetection(rawValue)
    0xD101 -> formatOlympusSubjectType(rawValue)
    0xD0CB -> if (rawValue == 2L) "Slot 2" else "Slot 1"
    0xD0CC -> "$rawValue shots"
    0xD0CD -> "$rawValue s"
    0xD0EC -> "$rawValue s"
    0xD0F0, 0xD0F1, 0xD0F2 -> formatOlympusSignedAdjustment(rawValue)
    0xD11A -> formatOlympusAspectRatio(rawValue)
    0xD08E -> formatOlympusImageStabilizerControl(rawValue)
    0xD11B -> formatOlympusImageStabilizer(rawValue)
    0xD11C -> formatOlympusColorSpace(rawValue)
    else -> rawValue.toString()
}

internal fun enumerateOlympusUsbPropertyValues(
    property: OmCaptureUsbManager.CameraPropertyState,
): List<Long> {
    val descriptorValues = when {
        property.allowedValues.isNotEmpty() -> property.allowedValues
        property.propCode == PtpConstants.OlympusProp.ExposureMode -> listOf(2L, 3L, 4L, 1L, 5L, 6L, 7L, 8L)
        property.propCode == PtpConstants.OlympusProp.ExposureCompensation -> defaultOlympusExposureCompRawValues(
            rangeMin = property.rangeMin,
            rangeMax = property.rangeMax,
        )
        else -> enumerateOlympusRangeValues(property)
    }
    return buildList {
        if (property.propCode == 0xD065) {
            add(0L)
        }
        addAll(descriptorValues)
        if (descriptorValues.isEmpty()) {
            addAll(syntheticOlympusUsbPropertyValues(property.propCode))
        }
        add(property.currentValue)
    }.distinct()
}

internal fun isLegacyOlympusDriveLayout(options: List<Long>): Boolean {
    return options.any { raw ->
        raw in setOf(
            17L, 18L, 20L, 21L, 22L,
            34L, 35L, 37L, 38L, 48L,
        )
    }
}

private fun defaultOlympusExposureCompRawValues(
    rangeMin: Long = -50L,
    rangeMax: Long = 50L,
): List<Long> {
    // Use camera-reported range if available, otherwise default to ±5.0 EV
    val effectiveMin = if (rangeMin != 0L || rangeMax != 0L) rangeMin else -50L
    val effectiveMax = if (rangeMin != 0L || rangeMax != 0L) rangeMax else 50L
    // Auto-detect scale: OM-1 uses ×1000 (range ±5000), older cameras ×10 (range ±50)
    val isHighRes = abs(effectiveMin.toShort().toInt()) > 60 || abs(effectiveMax.toShort().toInt()) > 60
    val multiplier = if (isHighRes) 100.0 else 10.0
    // Generate standard 1/3-stop EV values within the camera's range
    return (-15..15)
        .map { step -> ((step / 3.0) * multiplier).roundToInt().toLong() }
        .filter { it in effectiveMin..effectiveMax }
        .distinct()
}

private fun enumerateOlympusRangeValues(
    property: OmCaptureUsbManager.CameraPropertyState,
): List<Long> {
    val rangeStep = property.rangeStep.takeIf { it > 0L } ?: 0L
    if (rangeStep == 0L || property.rangeMin == property.rangeMax) {
        return emptyList()
    }
    val maxEntries = 256
    val values = mutableListOf<Long>()
    val ascending = property.rangeMax >= property.rangeMin
    var nextValue = property.rangeMin
    while (
        values.size < maxEntries &&
        if (ascending) nextValue <= property.rangeMax else nextValue >= property.rangeMax
    ) {
        values += nextValue
        nextValue = if (ascending) nextValue + rangeStep else nextValue - rangeStep
    }
    if (values.isNotEmpty() && values.last() != property.rangeMax && values.size < maxEntries) {
        values += property.rangeMax
    }
    return values
}

private fun syntheticOlympusUsbPropertyValues(propCode: Int): List<Long> = when (propCode) {
    PtpConstants.OlympusProp.ExposureMode -> listOf(2L, 3L, 4L, 1L, 5L, 6L, 7L, 8L)
    PtpConstants.OlympusProp.ExposureCompensation -> defaultOlympusExposureCompRawValues()
    PtpConstants.OlympusProp.FocusMode -> listOf(0x0002L, 0x8002L, 0x0001L, 0x8004L, 0x8007L)
    PtpConstants.OlympusProp.MeteringMode -> listOf(0x8001L, 0x0002L, 0x0004L, 0x8011L, 0x8012L)
    PtpConstants.OlympusProp.FlashMode -> listOf(0x0000L, 0x0001L, 0x0003L, 0x0004L, 0x0005L, 0x0006L, 0x0002L)
    PtpConstants.OlympusProp.ImageQuality -> listOf(0x020L, 0x101L, 0x102L, 0x103L, 0x104L, 0x121L, 0x122L, 0x123L, 0x124L)
    // The modern OM-1 descriptors for these props are enum-backed, but their
    // exact raw layouts vary. Keep fallbacks conservative and prefer the
    // camera-reported allowedValues whenever available.
    0xD00C -> emptyList()
    0xD010 -> listOf(0x8301L, 0x2L, 0x1L, 0x8003L, 0x8001L, 0x8610L)
    0xD065 -> listOf(0L, 1L, 2L)
    0xD0C7 -> listOf(0x128L, 0x126L, 0x127L)
    0xD01A -> listOf(0L, 0x8001L, 0x8003L, 0x8004L)
    0xD0C4 -> listOf(1L, 2L)
    0xD0C5 -> listOf(2L, 32770L, 32769L, 32771L, 32772L)
    0xD100 -> listOf(1L, 2L)
    0xD101 -> listOf(1L, 2L, 3L, 4L, 5L)
    0xD11A -> listOf(1L, 2L, 3L, 4L)
    0xD08E -> listOf(1L, 4L)
    0xD11B -> listOf(0L, 1L, 2L, 3L, 4L, 5L, 6L)
    0xD11C -> listOf(1L, 2L)
    else -> emptyList()
}

private fun formatOlympusPictureModeState(rawValue: Long): String = when (rawValue.toInt()) {
    33024 -> "iAUTO"
    33025 -> "Vivid"
    33027 -> "Natural"
    33026 -> "Muted"
    34817 -> "Vivid"
    34818 -> "Natural"
    34819 -> "Muted"
    34820 -> "Portrait"
    34821 -> "Monotone"
    35073 -> "Monotone"
    else -> "Mode ${rawValue.toString(16)}"
}

private fun formatOlympusPictureModeControl(rawValue: Long): String = when (rawValue.toInt()) {
    0x8301 -> "Vivid"
    0x0002 -> "Natural"
    0x0001 -> "Muted"
    0x8003 -> "Portrait"
    0x8001 -> "Monotone"
    0x8201, 0x8610 -> "iAUTO"
    else -> formatOlympusPictureModeState(rawValue)
}

private fun formatOlympusHighResState(rawValue: Long): String = when (rawValue.toInt()) {
    0 -> "Off"
    1 -> "High Res Shot Tripod"
    2 -> "High Res Shot Handheld"
    else -> rawValue.toString()
}

private fun formatOlympusHighResControl(rawValue: Long): String = when (rawValue.toInt()) {
    0x128 -> "Off"
    0x127 -> "High Res Shot Tripod"
    0x126 -> "High Res Shot Handheld"
    else -> formatOlympusHighResState(rawValue)
}

private fun formatOlympusImageStabilizerControl(rawValue: Long): String = when (rawValue.toInt()) {
    1 -> "On"
    4 -> "Off"
    else -> rawValue.toString()
}

private fun formatOlympusAfTargetArea(rawValue: Long): String {
    val value = rawValue.toInt()
    val normalized = value - 41
    if (normalized < 0 || normalized % 3 != 0) {
        return rawValue.toString()
    }
    val row = normalized / 117
    val col = (normalized % 117) / 3
    if (row !in 0..8 || col !in 0..12) {
        return rawValue.toString()
    }
    return if (row == 4 && col == 6) {
        "Center"
    } else {
        "R${row + 1} C${col + 1}"
    }
}

private fun formatOlympusFaceEyeMode(rawValue: Long): String = when (rawValue.toInt()) {
    2 -> "Eye Priority Off"
    32770, 1, 32772 -> "Face & Eye Priority On"
    32769 -> "Face & R. Eye Priority On"
    32771 -> "Face & L. Eye Priority On"
    else -> rawValue.toString()
}

private fun formatOlympusSubjectType(rawValue: Long): String = when (rawValue.toInt()) {
    1 -> "Human"
    2 -> "Motorsports"
    3 -> "Airplanes"
    4 -> "Trains"
    5 -> "Birds"
    0 -> "Auto"
    else -> rawValue.toString()
}

private fun formatOlympusSubjectDetection(rawValue: Long): String = when (rawValue.toInt()) {
    0, 1 -> "Off"
    2 -> "On"
    else -> rawValue.toString()
}

private fun formatOlympusFacePriority(rawValue: Long): String = when (rawValue.toInt()) {
    0, 1 -> "Off"
    2 -> "On"
    else -> rawValue.toString()
}

private fun formatOlympusAspectRatio(rawValue: Long): String = when (rawValue.toInt()) {
    1 -> "4:3"
    2 -> "3:2"
    3 -> "16:9"
    4 -> "1:1"
    else -> rawValue.toString()
}

private fun formatOlympusImageStabilizer(rawValue: Long): String = when (rawValue.toInt()) {
    0, 1 -> "S-IS Off"
    2 -> "S-IS 1"
    3 -> "S-IS 2"
    4 -> "S-IS 3"
    5 -> "S-IS Auto"
    6 -> "M-IS 1"
    else -> rawValue.toString()
}

private fun formatOlympusColorSpace(rawValue: Long): String = when (rawValue.toInt()) {
    1 -> "sRGB"
    2 -> "Adobe RGB"
    else -> rawValue.toString()
}

private fun formatOlympusSignedAdjustment(rawValue: Long): String {
    val signed = rawValue.toShort().toInt()
    return when {
        signed > 0 -> "+$signed"
        signed < 0 -> signed.toString()
        else -> "0"
    }
}

internal fun usbStorageSelectionLabel(
    selectedStorageIds: Set<Int>?,
    availableStorageIds: List<Int>,
): String {
    val cleaned = availableStorageIds.distinct()
    if (cleaned.isEmpty()) return "USB/PTP"
    val selected = selectedStorageIds?.toSet().orEmpty()
    if (selected.isEmpty() || selected.size >= cleaned.size) {
        return if (cleaned.size > 1) "Both" else "Slot 1"
    }
    return cleaned.filter { it in selected }
        .mapIndexed { index, storageId ->
            val slotIndex = cleaned.indexOf(storageId) + 1
            "Slot $slotIndex"
        }
        .joinToString(" + ")
}
