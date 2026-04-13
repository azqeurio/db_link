package dev.dblink.core.deepsky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class DeepSkyProcessingTest {
    private val presetEngine = DeepSkyPresetEngine()

    @Test
    fun `star detector finds stable gaussian stars`() {
        val luma = SyntheticSkyFrameFactory.starFieldLuma(
            width = 96,
            height = 96,
            background = 32f,
            stars = listOf(
                SyntheticStarSpec(x = 22f, y = 24f, peak = 360f),
                SyntheticStarSpec(x = 68f, y = 61f, peak = 340f),
            ),
        )

        val detected = StarDetector(
            tileSize = 16,
            sigmaThreshold = 2.0f,
            minSeparationPx = 6f,
            maxStars = 8,
        ).detect(luma, 96, 96)

        assertEquals(2, detected.size)
        assertTrue(detected.any { approx(it.x, 22f, 1.2f) && approx(it.y, 24f, 1.2f) })
        assertTrue(detected.any { approx(it.x, 68f, 1.2f) && approx(it.y, 61f, 1.2f) })
    }

    @Test
    fun `star detector rejects hot pixels and excludes elongated stars from matching pool`() {
        val luma = SyntheticSkyFrameFactory.starFieldLuma(
            width = 96,
            height = 96,
            background = 32f,
            stars = listOf(
                SyntheticStarSpec(x = 24f, y = 20f, peak = 340f),
                SyntheticStarSpec(x = 70f, y = 70f, peak = 360f, sigmaX = 2.6f, sigmaY = 0.7f, angleDeg = 35f),
            ),
            hotPixels = listOf(10 to 10),
        )

        val detected = StarDetector(
            tileSize = 16,
            sigmaThreshold = 2.0f,
            minSupportPixels = 3,
            minFootprintRadiusPx = 0.45f,
            maxStars = 8,
        ).detect(luma, 96, 96)

        assertEquals(2, detected.size)
        assertFalse(detected.any { approx(it.x, 10f, 0.5f) && approx(it.y, 10f, 0.5f) })
        val elongated = detected.first { it.x > 60f }
        assertTrue(elongated.elongation > 1.6f)
        assertFalse(elongated.usableForMatching)
    }

    @Test
    fun `frame registrar solves rigid translation and rotation`() {
        val policy = rigidPolicy(maxRotationDeg = 2.0f, maxShiftPxAt1024 = 120, minScore = 0.5f)
        val expected = RegistrationTransform(dx = 5.5f, dy = -3.0f, rotationDeg = 0.8f)
        val reference = referenceStars()
        val current = SyntheticSkyFrameFactory.inverseRigidTransform(reference, expected, 1024, 768)

        val result = FrameRegistrar().register(
            referenceStars = reference,
            currentStars = current,
            registrationPolicy = policy,
            alignmentWidth = 1024,
            alignmentHeight = 768,
        )

        assertTrue(result.success)
        assertNull(result.reason)
        assertEquals(DeepSkyTransformModel.Rigid, result.metrics.transformModel)
        assertTrue(result.metrics.residualPx <= policy.maxResidualPx)
        assertTrue(result.metrics.confidenceScore >= policy.minScore)
        assertTrue(result.metrics.inlierRatio >= policy.minInlierRatio)
    }

    @Test
    fun `frame registrar handles mild wide field distortions under affine-capable policy`() {
        val policy = presetEngine.profile(DeepSkyPresetId.WideFieldBalanced).registration.copy(
            minScore = 0.30f,
            maxResidualPx = 3.0f,
            minStars = 3,
            minMatches = 3,
            maxScaleDelta = 0.08f,
            maxShear = 0.06f,
        )
        val reference = referenceStars()
        val expected = RegistrationTransform(
            dx = 6f,
            dy = -4f,
            a = 1.008f,
            b = 0.006f,
            c = -0.005f,
            d = 0.996f,
            rotationDeg = -0.35f,
            scaleX = 1.008f,
            scaleY = 0.996f,
            shear = 0.002f,
            model = DeepSkyTransformModel.Affine,
        )
        val current = SyntheticSkyFrameFactory.inverseAffineTransform(reference, expected)

        val rigidOnly = FrameRegistrar().register(
            referenceStars = reference,
            currentStars = current,
            registrationPolicy = policy.copy(
                preferredTransformModel = DeepSkyTransformModel.Rigid,
                allowAffineFallback = false,
            ),
            alignmentWidth = 1024,
            alignmentHeight = 768,
        )
        val result = FrameRegistrar().register(
            referenceStars = reference,
            currentStars = current,
            registrationPolicy = policy,
            alignmentWidth = 1024,
            alignmentHeight = 768,
        )

        assertTrue("result=$result rigidOnly=$rigidOnly", result.success)
        assertTrue(result.metrics.residualPx <= policy.maxResidualPx)
        assertTrue(result.metrics.matchedStars >= policy.minMatches)
        assertTrue(result.metrics.residualPx <= rigidOnly.metrics.residualPx + 0.25f)
    }

    @Test
    fun `frame registrar rejects mismatched constellations with low confidence`() {
        val result = FrameRegistrar().register(
            referenceStars = referenceStars(),
            currentStars = listOf(
                DetectedStar(720f, 640f, 30f),
                DetectedStar(760f, 580f, 29f),
                DetectedStar(810f, 730f, 28f),
                DetectedStar(910f, 690f, 27f),
                DetectedStar(690f, 760f, 26f),
                DetectedStar(845f, 620f, 25f),
                DetectedStar(960f, 740f, 24f),
                DetectedStar(640f, 700f, 23f),
            ),
            registrationPolicy = rigidPolicy(maxRotationDeg = 2.0f, maxShiftPxAt1024 = 80, minScore = 0.55f),
            alignmentWidth = 1024,
            alignmentHeight = 768,
        )

        assertFalse(result.success)
        assertEquals(DeepSkyRejectionReason.RegistrationLowConfidence, result.reason)
    }

    @Test
    fun `compose transforms rotates intermediate translation into reference space`() {
        val currentToPrevious = rigidTransform(dx = 4f, dy = 2f, rotationDeg = 10f)
        val previousToReference = rigidTransform(dx = 8f, dy = -3f, rotationDeg = 15f)

        val composed = composeTransforms(currentToPrevious, previousToReference)

        val radians = previousToReference.rotationDeg / 180f * PI.toFloat()
        val expectedDx = cos(radians) * currentToPrevious.dx - sin(radians) * currentToPrevious.dy + previousToReference.dx
        val expectedDy = sin(radians) * currentToPrevious.dx + cos(radians) * currentToPrevious.dy + previousToReference.dy
        assertEquals(expectedDx, composed.dx, 0.001f)
        assertEquals(expectedDy, composed.dy, 0.001f)
        assertEquals(25f, composed.rotationDeg, 0.001f)
    }

    @Test
    fun `quality analyzer distinguishes hard reject and soft accept`() {
        val preset = presetEngine.profile(DeepSkyPresetId.MidTeleBalanced)
        val analyzer = DeepSkyQualityAnalyzer()
        val hardDecision = analyzer.decideFrame(
            qualityMetrics = FrameQualityMetrics(
                detectedStarCount = 12,
                usableStarCount = 4,
                medianFwhmPx = 2.4f,
                medianElongation = 1.2f,
                backgroundFraction = 0.10f,
                backgroundNoiseFraction = 0.03f,
                saturationFraction = 0.01f,
                qualityScore = 0.52f,
            ),
            registrationMetrics = null,
            preset = preset,
            recentAcceptedMetrics = emptyList(),
            performanceMode = DeepSkyPerformanceMode.Normal,
            transformPlausible = true,
        )
        val softDecision = analyzer.decideFrame(
            qualityMetrics = FrameQualityMetrics(
                detectedStarCount = 16,
                usableStarCount = 10,
                medianFwhmPx = 2.8f,
                medianElongation = 1.18f,
                backgroundFraction = 0.11f,
                backgroundNoiseFraction = preset.quality.maxNoiseFraction * 1.03f,
                saturationFraction = 0.01f,
                qualityScore = 0.68f,
            ),
            registrationMetrics = FrameRegistrationMetrics(
                matchedStars = 8,
                inlierRatio = 0.60f,
                residualPx = 1.8f,
                confidenceScore = preset.quality.softConfidenceFloor - 0.02f,
                usedFallbackPath = false,
            ),
            preset = preset,
            recentAcceptedMetrics = List(6) {
                FrameQualityMetrics(
                    detectedStarCount = 18,
                    usableStarCount = 11,
                    medianFwhmPx = 2.4f,
                    medianElongation = 1.12f,
                    backgroundFraction = 0.09f,
                    backgroundNoiseFraction = 0.03f,
                    qualityScore = 0.76f,
                )
            },
            performanceMode = DeepSkyPerformanceMode.Normal,
            transformPlausible = true,
        )

        assertEquals(DeepSkyFrameDecisionKind.HardReject, hardDecision.kind)
        assertEquals(DeepSkyRejectionReason.TooFewStars, hardDecision.reason)
        assertEquals(DeepSkyFrameDecisionKind.SoftAccept, softDecision.kind)
        assertTrue(softDecision.weightMultiplier in preset.accumulation.softAcceptWeightMin..preset.accumulation.softAcceptWeightMax)
    }

    @Test
    fun `stack engine winsorized preview suppresses singular outliers`() {
        val baseFrame = SyntheticSkyFrameFactory.decodedFrame(width = 2, height = 1, baseValue = 1024)
        val outlierFrame = SyntheticSkyFrameFactory.decodedFrame(width = 2, height = 1, baseValue = 1024, brightPixel = 1 to 0, previewOutlierBoost = 22000)
        val engine = StackEngine()
        val preset = presetEngine.profile(DeepSkyPresetId.WideFieldBalanced)
        engine.initialize(
            decodedFrame = baseFrame,
            config = StackEngineConfig(
                previewMode = StackAccumulationMode.WinsorizedMean,
                fullResMode = StackAccumulationMode.WeightedAverage,
                previewWinsorStartFrame = 2,
                winsorSigmaMultiplier = preset.accumulation.winsorSigmaMultiplier,
                enableWinsorizedPreview = true,
                enableWinsorizedFullRes = false,
            ),
        )
        repeat(4) {
            engine.accumulate(baseFrame, RegistrationTransform.Identity)
        }
        engine.accumulate(outlierFrame, RegistrationTransform.Identity)
        val snapshot = engine.snapshot()

        val protectedAverage = previewAverage(snapshot.previewRedSum[1], snapshot.previewWeightSum[1])
        val naiveAverage = (4f + ((1024f + 22000f) / 257f)) / 5f * 257f / 257f

        assertTrue(protectedAverage < 30f)
        assertTrue(protectedAverage < naiveAverage)
    }

    @Test
    fun `preview tone parameters ignore singular highlights and apply hysteresis`() {
        val preset = presetEngine.profile(DeepSkyPresetId.WideFieldBalanced)
        val lowSnapshot = StackSnapshot(
            previewRedSum = FloatArray(512) { 100f }.also { it[511] = 14000f },
            previewGreenSum = FloatArray(512) { 110f }.also { it[511] = 14500f },
            previewBlueSum = FloatArray(512) { 90f }.also { it[511] = 14200f },
            previewWeightSum = FloatArray(512) { 1f },
            previewWidth = 512,
            previewHeight = 1,
            frameCount = 4,
            totalWeight = 4f,
        )
        val higherSnapshot = StackSnapshot(
            previewRedSum = FloatArray(512) { 180f },
            previewGreenSum = FloatArray(512) { 185f },
            previewBlueSum = FloatArray(512) { 175f },
            previewWeightSum = FloatArray(512) { 1f },
            previewWidth = 512,
            previewHeight = 1,
            frameCount = 5,
            totalWeight = 5f,
        )

        val tone1 = computePreviewToneParameters(lowSnapshot, preset)
        val tone2 = computePreviewToneParameters(higherSnapshot, preset)
        val smoothed = smoothToneState(tone1, tone2, preset)

        assertTrue(tone1.whitePoint < 500f)
        assertTrue(smoothed.blackPoint in minOf(tone1.blackPoint, tone2.blackPoint)..maxOf(tone1.blackPoint, tone2.blackPoint))
        assertTrue(smoothed.redGain in 0.85f..1.15f)
    }

    private fun rigidPolicy(
        maxRotationDeg: Float = 2.0f,
        maxShiftPxAt1024: Int = 120,
        minStars: Int = 8,
        minMatches: Int = 5,
        minScore: Float = 0.5f,
    ): DeepSkyRegistrationPolicy {
        return presetEngine.profile(DeepSkyPresetId.MidTeleBalanced).registration.copy(
            preferredTransformModel = DeepSkyTransformModel.Rigid,
            allowAffineFallback = false,
            maxRotationDeg = maxRotationDeg,
            maxShiftPxAt1024 = maxShiftPxAt1024,
            minStars = minStars,
            minMatches = minMatches,
            minScore = minScore,
            matchRadiusPx = 4f,
        )
    }

    private fun rigidTransform(
        dx: Float,
        dy: Float,
        rotationDeg: Float,
    ): RegistrationTransform {
        val radians = rotationDeg / 180f * PI.toFloat()
        val cosTheta = cos(radians)
        val sinTheta = sin(radians)
        return RegistrationTransform(
            dx = dx,
            dy = dy,
            a = cosTheta,
            b = -sinTheta,
            c = sinTheta,
            d = cosTheta,
            rotationDeg = rotationDeg,
            scaleX = 1f,
            scaleY = 1f,
            shear = 0f,
            model = DeepSkyTransformModel.Rigid,
        )
    }

    private fun approx(actual: Float, expected: Float, tolerance: Float): Boolean {
        return actual in (expected - tolerance)..(expected + tolerance)
    }

    private fun referenceStars(): List<DetectedStar> {
        return listOf(
            DetectedStar(120f, 120f, 120f, localContrast = 8f, fwhmPx = 2.3f, elongation = 1.05f, isolationScore = 0.9f),
            DetectedStar(220f, 150f, 110f, localContrast = 7.8f, fwhmPx = 2.4f, elongation = 1.08f, isolationScore = 0.88f),
            DetectedStar(340f, 100f, 100f, localContrast = 7.6f, fwhmPx = 2.5f, elongation = 1.10f, isolationScore = 0.86f),
            DetectedStar(470f, 180f, 90f, localContrast = 7.4f, fwhmPx = 2.5f, elongation = 1.11f, isolationScore = 0.84f),
            DetectedStar(150f, 320f, 85f, localContrast = 7.2f, fwhmPx = 2.4f, elongation = 1.12f, isolationScore = 0.82f),
            DetectedStar(280f, 280f, 80f, localContrast = 7.0f, fwhmPx = 2.3f, elongation = 1.09f, isolationScore = 0.8f),
            DetectedStar(390f, 340f, 75f, localContrast = 6.8f, fwhmPx = 2.4f, elongation = 1.07f, isolationScore = 0.78f),
            DetectedStar(520f, 300f, 70f, localContrast = 6.6f, fwhmPx = 2.5f, elongation = 1.08f, isolationScore = 0.76f),
            DetectedStar(610f, 190f, 65f, localContrast = 6.4f, fwhmPx = 2.4f, elongation = 1.06f, isolationScore = 0.74f),
            DetectedStar(560f, 420f, 60f, localContrast = 6.2f, fwhmPx = 2.5f, elongation = 1.10f, isolationScore = 0.72f),
            DetectedStar(680f, 260f, 55f, localContrast = 6.0f, fwhmPx = 2.6f, elongation = 1.12f, isolationScore = 0.70f),
            DetectedStar(730f, 410f, 50f, localContrast = 5.8f, fwhmPx = 2.6f, elongation = 1.14f, isolationScore = 0.68f),
        )
    }
}
