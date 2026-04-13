package dev.dblink.core.geotag

import dev.dblink.core.model.GeoTagLocationSample
import kotlin.math.absoluteValue

object GeoTagMatcher {
    fun matchNearestSample(
        photoCapturedAtMillis: Long,
        samples: List<GeoTagLocationSample>,
        clockOffsetMinutes: Int,
        maxDistanceMinutes: Int = 15,
    ): GeoTagLocationSample? {
        if (samples.isEmpty()) return null

        val adjustedPhotoTime = photoCapturedAtMillis - (clockOffsetMinutes * MILLIS_PER_MINUTE)
        return samples
            .minByOrNull { sample ->
                (sample.capturedAtMillis - adjustedPhotoTime).absoluteValue
            }
            ?.takeIf { sample ->
                (sample.capturedAtMillis - adjustedPhotoTime).absoluteValue <= maxDistanceMinutes * MILLIS_PER_MINUTE
            }
    }

    private const val MILLIS_PER_MINUTE = 60_000L
}
