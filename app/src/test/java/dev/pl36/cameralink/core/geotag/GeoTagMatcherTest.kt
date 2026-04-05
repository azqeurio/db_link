package dev.pl36.cameralink.core.geotag

import dev.pl36.cameralink.core.model.GeoTagLocationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoTagMatcherTest {
    @Test
    fun `matches nearest sample within allowed window`() {
        val samples = listOf(
            sample(time = 1_000L),
            sample(time = 10_000L),
            sample(time = 20_000L),
        )

        val match = GeoTagMatcher.matchNearestSample(
            photoCapturedAtMillis = 11_000L,
            samples = samples,
            clockOffsetMinutes = 0,
            maxDistanceMinutes = 1,
        )

        assertEquals(10_000L, match?.capturedAtMillis)
    }

    @Test
    fun `returns null when no sample is within the allowed window`() {
        val samples = listOf(sample(time = 1_000L))

        val match = GeoTagMatcher.matchNearestSample(
            photoCapturedAtMillis = 5_000_000L,
            samples = samples,
            clockOffsetMinutes = 0,
            maxDistanceMinutes = 1,
        )

        assertNull(match)
    }

    @Test
    fun `applies camera clock offset before matching`() {
        val samples = listOf(
            sample(time = 60_000L),
            sample(time = 180_000L),
        )

        val match = GeoTagMatcher.matchNearestSample(
            photoCapturedAtMillis = 240_000L,
            samples = samples,
            clockOffsetMinutes = 1,
            maxDistanceMinutes = 2,
        )

        assertEquals(180_000L, match?.capturedAtMillis)
    }

    private fun sample(time: Long): GeoTagLocationSample {
        return GeoTagLocationSample(
            capturedAtMillis = time,
            latitude = 43.0,
            longitude = -79.0,
            accuracyMeters = 8f,
            source = "test",
        )
    }
}
