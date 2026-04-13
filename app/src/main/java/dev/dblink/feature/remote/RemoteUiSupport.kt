package dev.dblink.feature.remote

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.OrientationEventListener
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import dev.dblink.core.model.CameraPropertyValues
import dev.dblink.core.model.GeoTagLocationSample
import dev.dblink.core.protocol.PropertyFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal const val SYNTHETIC_APERTURE_AUTO = "__synthetic_aperture_auto__"
internal const val SYNTHETIC_SHUTTER_AUTO = "__synthetic_shutter_auto__"

internal data class RemoteAttitudeState(
    val azimuthDeg: Float? = null,
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val levelOffsetX: Float = 0f,
    val levelOffsetY: Float = 0f,
    val ready: Boolean = false,
)

internal enum class RemoteSkyPhase {
    Daylight,
    GoldenHour,
    CivilTwilight,
    NauticalTwilight,
    AstronomicalTwilight,
    DarkSky,
}

internal data class RemoteSkyOverlayInfo(
    val phase: RemoteSkyPhase,
    val phaseLabel: String,
    val detailLabel: String,
    val timeLabel: String,
    val locationLabel: String,
    val directionLabel: String? = null,
    val fieldLabel: String? = null,
    val gpsLabel: String? = null,
)

@Composable
internal fun rememberReadableCameraRotation(): Int {
    val context = LocalContext.current
    var rotation by remember { mutableIntStateOf(0) }
    var pendingTarget by remember { mutableIntStateOf(Int.MIN_VALUE) }
    var pendingSinceMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context.applicationContext) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val currentTarget = canonicalTargetFromReadableRotation(rotation)
                val candidateTarget = listOf(0, 90, 180, 270)
                    .minByOrNull { target -> circularDistance(orientation, target) }
                    ?: 0
                if (candidateTarget == currentTarget) {
                    pendingTarget = Int.MIN_VALUE
                    pendingSinceMs = 0L
                    return
                }

                val candidateDistance = circularDistance(orientation, candidateTarget)
                val currentDistance = circularDistance(orientation, currentTarget)
                val nowMs = SystemClock.elapsedRealtime()
                val eligibleForSwitch = candidateDistance <= 20 && currentDistance >= 62

                if (!eligibleForSwitch) {
                    pendingTarget = Int.MIN_VALUE
                    pendingSinceMs = 0L
                    return
                }

                if (pendingTarget != candidateTarget) {
                    pendingTarget = candidateTarget
                    pendingSinceMs = nowMs
                    return
                }

                if (nowMs - pendingSinceMs < 150L) {
                    return
                }

                rotation = readableRotationFromCanonicalTarget(candidateTarget)
                pendingTarget = Int.MIN_VALUE
                pendingSinceMs = 0L
            }
        }
        if (listener.canDetectOrientation()) {
            listener.enable()
        }
        onDispose {
            listener.disable()
        }
    }

    return rotation
}

@Composable
internal fun rememberRemoteAttitudeState(readableRotation: Int): RemoteAttitudeState {
    val context = LocalContext.current
    var attitudeState by remember { mutableStateOf(RemoteAttitudeState()) }

    DisposableEffect(context, readableRotation) {
        val sensorManager =
            context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (sensorManager == null) {
            attitudeState = RemoteAttitudeState()
            onDispose {}
        } else {
            val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val rotationMatrix = FloatArray(9)
            val remappedMatrix = FloatArray(9)
            val orientation = FloatArray(3)

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    when (event.sensor.type) {
                        Sensor.TYPE_ROTATION_VECTOR -> {
                            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                            val (axisX, axisY) = remapAxesForReadableRotation(readableRotation)
                            if (!SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)) {
                                return
                            }
                            SensorManager.getOrientation(remappedMatrix, orientation)
                            val azimuthDeg = normalizeDegrees(Math.toDegrees(orientation[0].toDouble()).toFloat())
                            val pitchDeg = Math.toDegrees(orientation[1].toDouble()).toFloat()
                            val rollDeg = Math.toDegrees(orientation[2].toDouble()).toFloat()
                            attitudeState = RemoteAttitudeState(
                                azimuthDeg = azimuthDeg,
                                pitchDeg = pitchDeg,
                                rollDeg = rollDeg,
                                levelOffsetX = (rollDeg / 18f).coerceIn(-1f, 1f),
                                levelOffsetY = (-pitchDeg / 18f).coerceIn(-1f, 1f),
                                ready = true,
                            )
                        }

                        Sensor.TYPE_GRAVITY,
                        Sensor.TYPE_ACCELEROMETER,
                        -> {
                            val (screenX, screenY) = screenRelativeGravity(
                                rawX = event.values[0],
                                rawY = event.values[1],
                                readableRotation = readableRotation,
                            )
                            val z = event.values.getOrElse(2) { 9.81f }
                            val pitchDeg =
                                Math.toDegrees(atan2((-screenY).toDouble(), max2(1.0, sqrt((screenX * screenX + z * z).toDouble())))).toFloat()
                            val rollDeg =
                                Math.toDegrees(atan2(screenX.toDouble(), max2(1.0, z.toDouble()))).toFloat()
                            attitudeState = RemoteAttitudeState(
                                azimuthDeg = null,
                                pitchDeg = pitchDeg,
                                rollDeg = rollDeg,
                                levelOffsetX = (screenX / 4.2f).coerceIn(-1f, 1f),
                                levelOffsetY = (-screenY / 4.2f).coerceIn(-1f, 1f),
                                ready = true,
                            )
                        }
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }

            val registered = when {
                rotationVectorSensor != null ->
                    sensorManager.registerListener(
                        listener,
                        rotationVectorSensor,
                        SensorManager.SENSOR_DELAY_GAME,
                    )

                gravitySensor != null ->
                    sensorManager.registerListener(
                        listener,
                        gravitySensor,
                        SensorManager.SENSOR_DELAY_UI,
                    )

                accelSensor != null ->
                    sensorManager.registerListener(
                        listener,
                        accelSensor,
                        SensorManager.SENSOR_DELAY_UI,
                    )

                else -> false
            }

            if (!registered) {
                attitudeState = RemoteAttitudeState()
            }

            onDispose {
                sensorManager.unregisterListener(listener)
            }
        }
    }

    return attitudeState
}

internal fun buildRemoteSkyOverlayInfo(
    sample: GeoTagLocationSample?,
    nowMs: Long,
    activeFocalLengthMm: Float?,
    fieldOfViewLabel: String,
    azimuthDeg: Float?,
): RemoteSkyOverlayInfo? {
    sample ?: return null
    val solarElevationDeg = estimateSolarElevationDeg(
        timeMs = nowMs,
        latitudeDeg = sample.latitude,
        longitudeDeg = sample.longitude,
    )
    val phase = when {
        solarElevationDeg <= -18.0 -> RemoteSkyPhase.DarkSky
        solarElevationDeg <= -12.0 -> RemoteSkyPhase.AstronomicalTwilight
        solarElevationDeg <= -6.0 -> RemoteSkyPhase.NauticalTwilight
        solarElevationDeg < 0.0 -> RemoteSkyPhase.CivilTwilight
        solarElevationDeg < 8.0 -> RemoteSkyPhase.GoldenHour
        else -> RemoteSkyPhase.Daylight
    }
    val timeLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nowMs))
    val gpsAgeMinutes = ((nowMs - sample.capturedAtMillis).coerceAtLeast(0L) / 60_000L).toInt()
    return RemoteSkyOverlayInfo(
        phase = phase,
        phaseLabel = when (phase) {
            RemoteSkyPhase.DarkSky -> "Dark sky"
            RemoteSkyPhase.AstronomicalTwilight -> "Astronomical twilight"
            RemoteSkyPhase.NauticalTwilight -> "Nautical twilight"
            RemoteSkyPhase.CivilTwilight -> "Civil twilight"
            RemoteSkyPhase.GoldenHour -> "Golden hour"
            RemoteSkyPhase.Daylight -> "Daylight"
        },
        detailLabel = "Sun ${formatSignedDegrees(solarElevationDeg)}",
        timeLabel = timeLabel,
        locationLabel = sample.placeName?.takeIf { it.isNotBlank() }
            ?: formatLatLonShort(sample.latitude, sample.longitude),
        directionLabel = azimuthDeg?.let { "Facing ${cardinalDirectionLabel(it)}" },
        fieldLabel = activeFocalLengthMm?.let {
            "${formatFocalLengthShort(it)} · ${fieldOfViewLabel.replace(" field", "")}"
        },
        gpsLabel = if (gpsAgeMinutes <= 0) "GPS live" else "GPS ${gpsAgeMinutes}m ago",
    )
}

private fun canonicalTargetFromReadableRotation(currentRotation: Int): Int {
    return when (currentRotation) {
        -90 -> 90
        90 -> 270
        180, -180 -> 180
        else -> 0
    }
}

private fun readableRotationFromCanonicalTarget(target: Int): Int {
    return when (target) {
        90 -> -90
        180 -> 180
        270 -> 90
        else -> 0
    }
}

private fun circularDistance(value: Int, target: Int): Int {
    val delta = kotlin.math.abs(value - target)
    return minOf(delta, 360 - delta)
}

private fun remapAxesForReadableRotation(readableRotation: Int): Pair<Int, Int> = when (readableRotation) {
    -90 -> SensorManager.AXIS_Z to SensorManager.AXIS_MINUS_X
    90 -> SensorManager.AXIS_MINUS_Z to SensorManager.AXIS_X
    180, -180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Z
    else -> SensorManager.AXIS_X to SensorManager.AXIS_Z
}

private fun screenRelativeGravity(
    rawX: Float,
    rawY: Float,
    readableRotation: Int,
): Pair<Float, Float> = when (readableRotation) {
    -90 -> -rawY to rawX
    90 -> rawY to -rawX
    180, -180 -> -rawX to -rawY
    else -> rawX to rawY
}

private fun normalizeDegrees(value: Float): Float {
    val normalized = value % 360f
    return if (normalized < 0f) normalized + 360f else normalized
}

private fun formatSignedDegrees(value: Double): String {
    return if (value >= 0.0) {
        String.format(Locale.ENGLISH, "+%.1f°", value)
    } else {
        String.format(Locale.ENGLISH, "%.1f°", value)
    }
}

private fun formatLatLonShort(latitude: Double, longitude: Double): String {
    return String.format(Locale.ENGLISH, "%.3f°, %.3f°", latitude, longitude)
}

private fun formatFocalLengthShort(focalLengthMm: Float): String {
    return if (kotlin.math.abs(focalLengthMm - focalLengthMm.toInt()) < 0.05f) {
        "${focalLengthMm.toInt()} mm"
    } else {
        String.format(Locale.ENGLISH, "%.1f mm", focalLengthMm)
    }
}

private fun cardinalDirectionLabel(azimuthDeg: Float): String {
    val directions = listOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )
    val index = ((normalizeDegrees(azimuthDeg) + 11.25f) / 22.5f).toInt() % directions.size
    return directions[index]
}

private fun estimateSolarElevationDeg(
    timeMs: Long,
    latitudeDeg: Double,
    longitudeDeg: Double,
): Double {
    val julianDay = (timeMs / 86_400_000.0) + 2440587.5
    val n = julianDay - 2451545.0
    val meanLongitude = normalizeDoubleDegrees(280.460 + (0.9856474 * n))
    val meanAnomaly = normalizeDoubleDegrees(357.528 + (0.9856003 * n))
    val eclipticLongitude =
        normalizeDoubleDegrees(
            meanLongitude +
                (1.915 * sin(meanAnomaly.toRadians())) +
                (0.020 * sin((2.0 * meanAnomaly).toRadians())),
        )
    val obliquityDeg = 23.439 - (0.0000004 * n)
    val rightAscensionDeg = normalizeDoubleDegrees(
        Math.toDegrees(
            atan2(
                cos(obliquityDeg.toRadians()) * sin(eclipticLongitude.toRadians()),
                cos(eclipticLongitude.toRadians()),
            ),
        ),
    )
    val declinationDeg = Math.toDegrees(
        asin(sin(obliquityDeg.toRadians()) * sin(eclipticLongitude.toRadians())),
    )
    val gmstHours = normalizeHours(18.697374558 + (24.06570982441908 * n))
    val localSiderealDeg = normalizeDoubleDegrees((gmstHours * 15.0) + longitudeDeg)
    val hourAngleDeg = normalizeSignedDegrees(localSiderealDeg - rightAscensionDeg)
    return Math.toDegrees(
        asin(
            (sin(latitudeDeg.toRadians()) * sin(declinationDeg.toRadians())) +
                (cos(latitudeDeg.toRadians()) * cos(declinationDeg.toRadians()) * cos(hourAngleDeg.toRadians())),
        ),
    )
}

private fun normalizeDoubleDegrees(value: Double): Double {
    val normalized = value % 360.0
    return if (normalized < 0.0) normalized + 360.0 else normalized
}

private fun normalizeSignedDegrees(value: Double): Double {
    val normalized = normalizeDoubleDegrees(value)
    return if (normalized > 180.0) normalized - 360.0 else normalized
}

private fun normalizeHours(value: Double): Double {
    val normalized = value % 24.0
    return if (normalized < 0.0) normalized + 24.0 else normalized
}

private fun Double.toRadians(): Double = this * PI / 180.0

private fun max2(first: Double, second: Double): Double = if (first > second) first else second

internal fun mapDisplayFocusPointToSensor(
    normalizedX: Float,
    normalizedY: Float,
    rotationDegrees: Int,
): Offset = when (rotationDegrees) {
    -90 -> Offset((1f - normalizedY).coerceIn(0f, 1f), normalizedX.coerceIn(0f, 1f))
    90 -> Offset(normalizedY.coerceIn(0f, 1f), (1f - normalizedX).coerceIn(0f, 1f))
    180, -180 -> Offset((1f - normalizedX).coerceIn(0f, 1f), (1f - normalizedY).coerceIn(0f, 1f))
    else -> Offset(normalizedX.coerceIn(0f, 1f), normalizedY.coerceIn(0f, 1f))
}

internal fun mapSensorFocusPointToDisplay(
    normalizedX: Float,
    normalizedY: Float,
    rotationDegrees: Int,
): Offset = when (rotationDegrees) {
    -90 -> Offset(normalizedY.coerceIn(0f, 1f), (1f - normalizedX).coerceIn(0f, 1f))
    90 -> Offset((1f - normalizedY).coerceIn(0f, 1f), normalizedX.coerceIn(0f, 1f))
    180, -180 -> Offset((1f - normalizedX).coerceIn(0f, 1f), (1f - normalizedY).coerceIn(0f, 1f))
    else -> Offset(normalizedX.coerceIn(0f, 1f), normalizedY.coerceIn(0f, 1f))
}

internal fun performDialHaptic(
    view: View,
    propName: String,
    value: String,
) {
    val feedback = if (isMajorDialValue(propName, value)) {
        // Full-stop boundary: heavier click for tactile "detent" feel
        HapticFeedbackConstantsCompat.SEGMENT_TICK
    } else {
        // 1/3-stop step: light ratcheting tick for mechanical feel
        HapticFeedbackConstantsCompat.SEGMENT_FREQUENT_TICK
    }
    ViewCompat.performHapticFeedback(view, feedback)
}

internal fun syntheticDialAutoValue(propName: String): String? = when (propName) {
    "focalvalue" -> SYNTHETIC_APERTURE_AUTO
    "shutspeedvalue" -> SYNTHETIC_SHUTTER_AUTO
    else -> null
}

internal fun isSyntheticDialAutoValue(propName: String, value: String): Boolean {
    return value.equals(syntheticDialAutoValue(propName), ignoreCase = true)
}

internal fun isCameraReportedAutoValue(
    propName: String,
    values: CameraPropertyValues,
): Boolean {
    if (values.autoActive) {
        return true
    }
    val currentValue = values.currentValue.trim()
    return when (propName) {
        "isospeedvalue", "wbvalue" -> PropertyFormatter.isAutoValue(propName, currentValue)
        "focalvalue", "shutspeedvalue" -> {
            currentValue.equals("AUTO", ignoreCase = true) ||
                currentValue.equals("Auto", ignoreCase = true) ||
                (!values.enabled && currentValue.isBlank())
        }
        else -> false
    }
}

private fun isMajorDialValue(propName: String, value: String): Boolean {
    val normalized = value.trim().uppercase()
    return when (propName) {
        "focalvalue" -> normalized in setOf("2.0", "2.8", "4.0", "5.6", "8.0", "11", "16", "22")
        "isospeedvalue" -> normalized in setOf("100", "200", "400", "800", "1600", "3200", "6400")
        "shutspeedvalue" -> normalized in setOf(
            "60\"",
            "30\"",
            "15\"",
            "8\"",
            "4\"",
            "2\"",
            "1\"",
            "1",
            "2",
            "4",
            "8",
            "15",
            "30",
            "60",
            "125",
            "250",
            "500",
            "1000",
            "2000",
            "4000",
        )
        "expcomp" -> {
            val numeric = normalized.replace("+", "").replace("EV", "").trim()
            numeric == "0.0" || numeric == "0" || numeric.endsWith(".0") || !numeric.contains(".")
        }
        else -> false
    }
}
