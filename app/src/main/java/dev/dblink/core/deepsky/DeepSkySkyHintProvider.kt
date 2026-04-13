package dev.dblink.core.deepsky

import dev.dblink.core.model.GeoTagLocationSample
import kotlin.math.atan

/**
 * Sky hints stay intentionally coarse.
 *
 * GPS, time, and orientation can help seed the search space, but they never
 * replace star-based registration.
 */
class SkyHintProvider {
    @Volatile
    private var latestLocationSample: GeoTagLocationSample? = null

    @Volatile
    private var latestRotationHintDeg: Float? = null

    fun updateContext(locationSample: GeoTagLocationSample?, rotationHintDeg: Float?) {
        if (locationSample != null) {
            latestLocationSample = locationSample
        }
        if (rotationHintDeg != null) {
            latestRotationHintDeg = rotationHintDeg
        }
    }

    fun buildHint(frame: CapturedFrame): DeepSkySkyHint {
        val location = latestLocationSample
        val focalLength = frame.focalLengthMm
        val fovHorizontalDeg = focalLength?.let { estimateFieldOfViewDeg(sensorSizeMm = 17.3f, focalLengthMm = it) }
        val fovVerticalDeg = focalLength?.let { estimateFieldOfViewDeg(sensorSizeMm = 13.0f, focalLengthMm = it) }
        return DeepSkySkyHint(
            latitude = location?.latitude,
            longitude = location?.longitude,
            captureTimeMs = frame.captureTimeMs,
            fovHorizontalDeg = fovHorizontalDeg,
            fovVerticalDeg = fovVerticalDeg,
            raEstimateHours = null,
            decEstimateDeg = null,
            rotationHintDeg = latestRotationHintDeg,
        )
    }

    private fun estimateFieldOfViewDeg(sensorSizeMm: Float, focalLengthMm: Float): Float {
        return Math.toDegrees(
            2.0 * atan((sensorSizeMm / (2.0f * focalLengthMm)).toDouble()),
        ).toFloat()
    }
}
