package dev.dblink.core.deepsky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSkyPresetEngineTest {
    private val engine = DeepSkyPresetEngine()

    @Test
    fun `profiles expose the planned fixed thresholds`() {
        val profiles = engine.profiles.associateBy { it.id }

        assertEquals(6, profiles.size)

        assertProfile(
            profile = profiles.getValue(DeepSkyPresetId.WideFieldBalanced),
            shutterSec = 15.0,
            isoMin = 800,
            isoMax = 1600,
            maxRotationDeg = 2.0f,
            maxShiftPxAt1024 = 110,
            minStars = 10,
            minMatches = 6,
            minScore = 0.52f,
        )
        assertProfile(
            profile = profiles.getValue(DeepSkyPresetId.WideFieldAggressive),
            shutterSec = 20.0,
            isoMin = 1600,
            isoMax = 3200,
            maxRotationDeg = 3.0f,
            maxShiftPxAt1024 = 140,
            minStars = 8,
            minMatches = 5,
            minScore = 0.46f,
        )
        assertProfile(
            profile = profiles.getValue(DeepSkyPresetId.MidTeleConservative),
            shutterSec = 8.0,
            isoMin = 800,
            isoMax = 1600,
            maxRotationDeg = 1.2f,
            maxShiftPxAt1024 = 70,
            minStars = 9,
            minMatches = 6,
            minScore = 0.58f,
        )
        assertProfile(
            profile = profiles.getValue(DeepSkyPresetId.MidTeleBalanced),
            shutterSec = 12.0,
            isoMin = 1600,
            isoMax = 3200,
            maxRotationDeg = 1.8f,
            maxShiftPxAt1024 = 95,
            minStars = 8,
            minMatches = 5,
            minScore = 0.52f,
        )
        assertProfile(
            profile = profiles.getValue(DeepSkyPresetId.LongTeleSafe),
            shutterSec = 5.0,
            isoMin = 800,
            isoMax = 1600,
            maxRotationDeg = 0.8f,
            maxShiftPxAt1024 = 48,
            minStars = 6,
            minMatches = 4,
            minScore = 0.62f,
        )
        assertProfile(
            profile = profiles.getValue(DeepSkyPresetId.LongTeleAggressive),
            shutterSec = 8.0,
            isoMin = 1600,
            isoMax = 3200,
            maxRotationDeg = 1.1f,
            maxShiftPxAt1024 = 64,
            minStars = 5,
            minMatches = 4,
            minScore = 0.54f,
        )
    }

    @Test
    fun `recommends wide aggressive for fast short wide-field capture`() {
        val recommendation = engine.recommend(
            DeepSkyCaptureContext(
                focalLengthMm = 24f,
                aperture = 2.0f,
                exposureSec = 6.0,
                tripodFixed = true,
            ),
        )

        assertEquals(DeepSkyPresetId.WideFieldAggressive, recommendation.profile.id)
        assertTrue(recommendation.reason.contains("24mm"))
    }

    @Test
    fun `recommends conservative mid tele preset for slower longer mid tele capture`() {
        val recommendation = engine.recommend(
            DeepSkyCaptureContext(
                focalLengthMm = 150f,
                aperture = 4.0f,
                exposureSec = 15.0,
                tripodFixed = true,
            ),
        )

        assertEquals(DeepSkyPresetId.MidTeleConservative, recommendation.profile.id)
        assertTrue(recommendation.reason.contains("150mm"))
    }

    @Test
    fun `recommends long tele aggressive only when the setup is fast enough`() {
        val aggressive = engine.recommend(
            DeepSkyCaptureContext(
                focalLengthMm = 300f,
                aperture = 2.8f,
                exposureSec = 6.0,
                tripodFixed = true,
                targetStyle = DeepSkyTargetStackStyle.Aggressive,
            ),
        )
        val safe = engine.recommend(
            DeepSkyCaptureContext(
                focalLengthMm = 300f,
                aperture = 5.6f,
                exposureSec = 8.0,
                tripodFixed = true,
                targetStyle = DeepSkyTargetStackStyle.Aggressive,
            ),
        )

        assertEquals(DeepSkyPresetId.LongTeleAggressive, aggressive.profile.id)
        assertEquals(DeepSkyPresetId.LongTeleSafe, safe.profile.id)
    }

    @Test
    fun `falls back to wide balanced until capture metadata arrives`() {
        val recommendation = engine.recommend(
            DeepSkyCaptureContext(
                focalLengthMm = null,
                aperture = null,
                exposureSec = null,
                tripodFixed = true,
            ),
        )

        assertEquals(DeepSkyPresetId.WideFieldBalanced, recommendation.profile.id)
        assertTrue(recommendation.reason.contains("first frame", ignoreCase = true))
    }

    private fun assertProfile(
        profile: DeepSkyPresetProfile,
        shutterSec: Double,
        isoMin: Int,
        isoMax: Int,
        maxRotationDeg: Float,
        maxShiftPxAt1024: Int,
        minStars: Int,
        minMatches: Int,
        minScore: Float,
    ) {
        assertEquals(shutterSec, profile.recommendedShutterSec, 0.0)
        assertEquals(isoMin, profile.isoRange.min)
        assertEquals(isoMax, profile.isoRange.max)
        assertEquals(maxRotationDeg, profile.registration.maxRotationDeg, 0.0f)
        assertEquals(maxShiftPxAt1024, profile.registration.maxShiftPxAt1024)
        assertEquals(minStars, profile.registration.minStars)
        assertEquals(minMatches, profile.registration.minMatches)
        assertEquals(minScore, profile.registration.minScore, 0.0f)
    }
}
