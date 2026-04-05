package dev.pl36.cameralink.core.deepsky

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.ArrayDeque

@RunWith(RobolectricTestRunner::class)
class DeepSkyCoordinatorTest {
    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val presetEngine = DeepSkyPresetEngine()

    @Test
    fun `coordinator elects the strongest early reference without changing stack anchor`() {
        val frames = listOf(
            capturedFrame("f1"),
            capturedFrame("f2"),
            capturedFrame("f3"),
            capturedFrame("f4"),
            capturedFrame("f5"),
        )
        val decoder = MapFrameDecoder(frames.associate { it.id to SyntheticSkyFrameFactory.decodedFrame(width = 8, height = 8) })
        val starSets = ArrayDeque(
            listOf(
                SyntheticSkyFrameFactory.detectedStars(count = 10),
                SyntheticSkyFrameFactory.detectedStars(count = 12),
                SyntheticSkyFrameFactory.detectedStars(count = 11),
                SyntheticSkyFrameFactory.detectedStars(count = 10),
                SyntheticSkyFrameFactory.detectedStars(count = 9),
            ),
        )
        val qualityMetrics = ArrayDeque(
            listOf(
                frameQuality(qualityScore = 0.60f, referenceScore = 0.60f, starCount = 10),
                frameQuality(qualityScore = 0.88f, referenceScore = 0.88f, starCount = 12),
                frameQuality(qualityScore = 0.72f, referenceScore = 0.72f, starCount = 11),
                frameQuality(qualityScore = 0.66f, referenceScore = 0.66f, starCount = 10),
                frameQuality(qualityScore = 0.61f, referenceScore = 0.61f, starCount = 9),
            ),
        )
        val decisions = ArrayDeque(List(6) { acceptDecision() })
        val registrationResults = ArrayDeque(
            List(6) { registrationResult(referenceFrameId = "f1") },
        )

        val coordinator = DeepSkyLiveStackCoordinator(
            appContext = appContext,
            presetEngine = presetEngine,
            frameDecoder = decoder,
            starDetector = SequenceStarDetector(starSets),
            qualityAnalyzer = SequenceQualityAnalyzer(qualityMetrics, decisions),
            memoryPolicy = AcceptingMemoryPolicy(appContext),
            performanceProbe = StaticPerformanceProbe(),
            frameRegistrar = SequenceFrameRegistrar(registrationResults),
            stackRenderer = TestStackRenderer(),
        )

        coordinator.startSession(DeepSkyPresetId.WideFieldBalanced)
        frames.forEach(coordinator::submitCapturedFrame)

        val state = waitForState(coordinator) { it.frameCount >= 3 && it.referenceStatus.activeReferenceFrameId == "f2" }
        coordinator.stopSession()

        assertTrue(state.frameCount >= 3)
        assertEquals("f2", state.referenceStatus.activeReferenceFrameId)
        assertTrue(
            state.referenceStatus.status == DeepSkyReferenceStatus.WarmingUp ||
                state.referenceStatus.status == DeepSkyReferenceStatus.Elected,
        )
        assertTrue(
            state.workflowState == DeepSkyWorkflowState.ElectingReference ||
                state.workflowState == DeepSkyWorkflowState.StackingHealthy,
        )
        assertNotNull(state.previewBitmap)
    }

    @Test
    fun `coordinator tracks soft accepts and hard rejects in diagnostics`() {
        val frames = listOf(capturedFrame("f1"), capturedFrame("f2"), capturedFrame("f3"))
        val decoder = MapFrameDecoder(frames.associate { it.id to SyntheticSkyFrameFactory.decodedFrame(width = 8, height = 8) })
        val starSets = ArrayDeque(
            listOf(
                SyntheticSkyFrameFactory.detectedStars(count = 10),
                SyntheticSkyFrameFactory.detectedStars(count = 9),
                SyntheticSkyFrameFactory.detectedStars(count = 9),
            ),
        )
        val qualityMetrics = ArrayDeque(
            listOf(
                frameQuality(qualityScore = 0.70f, referenceScore = 0.70f, starCount = 10),
                frameQuality(qualityScore = 0.62f, referenceScore = 0.62f, starCount = 9),
                frameQuality(qualityScore = 0.30f, referenceScore = 0.30f, starCount = 9),
            ),
        )
        val decisions = ArrayDeque(
            listOf(
                acceptDecision(),
                softDecision(),
                hardRejectDecision(DeepSkyRejectionReason.BackgroundTooBright),
                hardRejectDecision(DeepSkyRejectionReason.BackgroundTooBright),
            ),
        )
        val registrationResults = ArrayDeque(
            listOf(
                registrationResult(referenceFrameId = "f1", confidenceScore = 0.58f, usedFallbackPath = true),
                registrationResult(referenceFrameId = "f1", confidenceScore = 0.42f),
                registrationResult(referenceFrameId = "f1", confidenceScore = 0.42f),
            ),
        )

        val coordinator = DeepSkyLiveStackCoordinator(
            appContext = appContext,
            presetEngine = presetEngine,
            frameDecoder = decoder,
            starDetector = SequenceStarDetector(starSets),
            qualityAnalyzer = SequenceQualityAnalyzer(qualityMetrics, decisions),
            memoryPolicy = AcceptingMemoryPolicy(appContext),
            performanceProbe = StaticPerformanceProbe(),
            frameRegistrar = SequenceFrameRegistrar(registrationResults),
            stackRenderer = TestStackRenderer(),
        )

        coordinator.startSession(DeepSkyPresetId.MidTeleBalanced)
        frames.forEach(coordinator::submitCapturedFrame)

        val state = waitForState(coordinator) {
            it.softAcceptedCount == 1 && it.lastRejectionKind == DeepSkyRejectionReason.BackgroundTooBright
        }
        coordinator.stopSession()

        assertEquals(2, state.frameCount)
        assertEquals(1, state.softAcceptedCount)
        assertEquals(1, state.rejectedCount)
        assertEquals(DeepSkyWorkflowState.StackingUnstable, state.workflowState)
        assertEquals(DeepSkyRejectionReason.BackgroundTooBright, state.lastRejectionKind)
        assertTrue((state.activeDiagnosticMessage ?: "").contains("bright", ignoreCase = true))
    }

    @Test
    fun `coordinator surfaces memory-limited startup failures safely`() {
        val frame = capturedFrame("f1")
        val decoder = MapFrameDecoder(mapOf(frame.id to SyntheticSkyFrameFactory.decodedFrame(width = 8, height = 8)))
        val coordinator = DeepSkyLiveStackCoordinator(
            appContext = appContext,
            presetEngine = presetEngine,
            frameDecoder = decoder,
            starDetector = SequenceStarDetector(ArrayDeque(listOf(SyntheticSkyFrameFactory.detectedStars(count = 10)))),
            qualityAnalyzer = SequenceQualityAnalyzer(ArrayDeque(listOf(frameQuality())), ArrayDeque(listOf(acceptDecision()))),
            memoryPolicy = RejectingMemoryPolicy(appContext),
            performanceProbe = StaticPerformanceProbe(),
            frameRegistrar = SequenceFrameRegistrar(ArrayDeque()),
            stackRenderer = TestStackRenderer(),
        )

        coordinator.startSession(DeepSkyPresetId.WideFieldBalanced)
        coordinator.submitCapturedFrame(frame)

        val state = waitForState(coordinator) { it.workflowState == DeepSkyWorkflowState.MemoryLimited }

        assertEquals(DeepSkySessionStatus.Error, state.sessionStatus)
        assertEquals(DeepSkyWorkflowState.MemoryLimited, state.workflowState)
        assertFalse(state.isSessionActive)
        assertEquals(DeepSkyRejectionReason.MemoryBudgetExceeded, state.lastRejectionKind)
    }

    private fun waitForState(
        coordinator: DeepSkyLiveStackCoordinator,
        predicate: (DeepSkyLiveStackUiState) -> Boolean,
    ): DeepSkyLiveStackUiState {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val state = coordinator.state.value
            if (predicate(state)) {
                return state
            }
            Thread.sleep(20L)
        }
        throw AssertionError("Timed out waiting for coordinator state. Last state=${coordinator.state.value}")
    }

    private fun capturedFrame(id: String): CapturedFrame {
        return CapturedFrame(
            id = id,
            captureTimeMs = 1_000L,
            rawPath = id,
            previewPath = id,
            width = 8,
            height = 8,
            exposureSec = 10.0,
            iso = 1600,
            focalLengthMm = 24f,
            aperture = 2.8f,
        )
    }

    private fun frameQuality(
        qualityScore: Float = 0.72f,
        referenceScore: Float = 0.72f,
        starCount: Int = 10,
    ): FrameQualityMetrics {
        return FrameQualityMetrics(
            detectedStarCount = starCount,
            usableStarCount = starCount,
            medianFwhmPx = 2.3f,
            medianElongation = 1.12f,
            backgroundFraction = 0.10f,
            backgroundNoiseFraction = 0.03f,
            saturationFraction = 0.01f,
            qualityScore = qualityScore,
            referenceCandidateScore = referenceScore,
        )
    }

    private fun acceptDecision(): DeepSkyFrameDecision {
        return DeepSkyFrameDecision(
            kind = DeepSkyFrameDecisionKind.Accept,
            explanation = "Accepted",
            weightMultiplier = 1f,
        )
    }

    private fun softDecision(): DeepSkyFrameDecision {
        return DeepSkyFrameDecision(
            kind = DeepSkyFrameDecisionKind.SoftAccept,
            reason = DeepSkyRejectionReason.RegistrationLowConfidence,
            explanation = "Downweighted",
            weightMultiplier = 0.55f,
        )
    }

    private fun hardRejectDecision(reason: DeepSkyRejectionReason): DeepSkyFrameDecision {
        return DeepSkyFrameDecision(
            kind = DeepSkyFrameDecisionKind.HardReject,
            reason = reason,
            explanation = "Rejected",
            weightMultiplier = 0f,
        )
    }

    private fun registrationResult(
        referenceFrameId: String,
        confidenceScore: Float = 0.78f,
        usedFallbackPath: Boolean = false,
    ): FrameRegistrationResult {
        return FrameRegistrationResult(
            success = true,
            transform = RegistrationTransform.Identity,
            metrics = FrameRegistrationMetrics(
                matchedStars = 8,
                inlierRatio = 0.65f,
                residualPx = 1.2f,
                confidenceScore = confidenceScore,
                usedFallbackPath = usedFallbackPath,
                referenceFrameId = referenceFrameId,
                transformModel = DeepSkyTransformModel.Rigid,
            ),
        )
    }
}

private class MapFrameDecoder(
    private val frames: Map<String, DecodedFrame>,
) : DeepSkyFrameDecoder {
    override suspend fun decode(frame: CapturedFrame): DecodedFrame {
        return frames.getValue(frame.id)
    }
}

private class SequenceStarDetector(
    private val starsByCall: ArrayDeque<List<DetectedStar>>,
) : StarDetector() {
    private var lastReturned: List<DetectedStar> = emptyList()

    override fun detect(
        luma: FloatArray,
        width: Int,
        height: Int,
    ): List<DetectedStar> {
        if (starsByCall.isNotEmpty()) {
            lastReturned = starsByCall.removeFirst()
        }
        return lastReturned
    }
}

private class SequenceFrameRegistrar(
    private val resultsByCall: ArrayDeque<FrameRegistrationResult>,
) : FrameRegistrar() {
    private var lastReturned = FrameRegistrationResult(
        success = true,
        transform = RegistrationTransform.Identity,
        metrics = FrameRegistrationMetrics(
            matchedStars = 8,
            inlierRatio = 0.65f,
            residualPx = 1.2f,
            confidenceScore = 0.78f,
            transformModel = DeepSkyTransformModel.Rigid,
        ),
    )

    override fun register(
        referenceStars: List<DetectedStar>,
        currentStars: List<DetectedStar>,
        registrationPolicy: DeepSkyRegistrationPolicy,
        alignmentWidth: Int,
        alignmentHeight: Int,
        rotationHintDeg: Float?,
        referenceFrameId: String?,
        usedFallbackPath: Boolean,
    ): FrameRegistrationResult {
        if (resultsByCall.isNotEmpty()) {
            lastReturned = resultsByCall.removeFirst()
        }
        return lastReturned
    }
}

private class SequenceQualityAnalyzer(
    private val metricsByCall: ArrayDeque<FrameQualityMetrics>,
    private val decisionsByCall: ArrayDeque<DeepSkyFrameDecision>,
) : DeepSkyQualityAnalyzer() {
    private var lastMetrics = FrameQualityMetrics(
        detectedStarCount = 10,
        usableStarCount = 10,
        medianFwhmPx = 2.3f,
        medianElongation = 1.12f,
        backgroundFraction = 0.10f,
        backgroundNoiseFraction = 0.03f,
        saturationFraction = 0.01f,
        qualityScore = 0.7f,
        referenceCandidateScore = 0.7f,
    )
    private var lastDecision = DeepSkyFrameDecision(
        kind = DeepSkyFrameDecisionKind.Accept,
        explanation = "Accepted",
        weightMultiplier = 1f,
    )

    override fun analyzeFrame(
        decoded: DecodedFrame,
        stars: List<DetectedStar>,
        preset: DeepSkyPresetProfile,
        recentAcceptedMetrics: List<FrameQualityMetrics>,
        registrationConfidenceHint: Float?,
    ): FrameQualityMetrics {
        if (metricsByCall.isNotEmpty()) {
            lastMetrics = metricsByCall.removeFirst()
        }
        return lastMetrics
    }

    override fun decideFrame(
        qualityMetrics: FrameQualityMetrics,
        registrationMetrics: FrameRegistrationMetrics?,
        preset: DeepSkyPresetProfile,
        recentAcceptedMetrics: List<FrameQualityMetrics>,
        performanceMode: DeepSkyPerformanceMode,
        transformPlausible: Boolean,
    ): DeepSkyFrameDecision {
        if (decisionsByCall.isNotEmpty()) {
            lastDecision = decisionsByCall.removeFirst()
        }
        return lastDecision
    }

    override fun summarizeSession(
        qualityMetrics: List<FrameQualityMetrics>,
        registrationMetrics: List<FrameRegistrationMetrics>,
    ): DeepSkySessionQualitySummary {
        return DeepSkySessionQualitySummary(
            healthLabel = if (registrationMetrics.any { it.confidenceScore < 0.6f }) "Unstable" else "Healthy",
            averageConfidence = registrationMetrics.map { it.confidenceScore }.average().toFloat(),
        )
    }
}

private class AcceptingMemoryPolicy(
    context: Context,
) : DeepSkyMemoryPolicy(context) {
    override fun evaluate(
        decoded: DecodedFrame,
        preset: DeepSkyPresetProfile,
    ): DeepSkyMemoryBudgetPlan {
        return DeepSkyMemoryBudgetPlan(
            success = true,
            enableWinsorizedPreview = true,
            enableWinsorizedFullRes = false,
            initialWorkflowState = DeepSkyWorkflowState.WaitingForFrames,
            initialPerformanceMode = DeepSkyPerformanceMode.Normal,
            estimatedBytes = 1024L,
            safeBudgetBytes = 1024L * 1024L,
            explanation = "Budget accepted",
        )
    }
}

private class RejectingMemoryPolicy(
    context: Context,
) : DeepSkyMemoryPolicy(context) {
    override fun evaluate(
        decoded: DecodedFrame,
        preset: DeepSkyPresetProfile,
    ): DeepSkyMemoryBudgetPlan {
        return DeepSkyMemoryBudgetPlan(
            success = false,
            enableWinsorizedPreview = false,
            enableWinsorizedFullRes = false,
            initialWorkflowState = DeepSkyWorkflowState.MemoryLimited,
            initialPerformanceMode = DeepSkyPerformanceMode.ProtectionPaused,
            estimatedBytes = 9_999_999L,
            safeBudgetBytes = 1024L,
            explanation = "Not enough memory",
        )
    }
}

private class StaticPerformanceProbe : DeepSkyPerformanceProbe {
    override fun snapshot(): DeepSkyPerformanceSnapshot {
        return DeepSkyPerformanceSnapshot(
            thermalStatus = 0,
            isLowMemory = false,
            availableMemBytes = 256L * 1024L * 1024L,
        )
    }
}

private class TestStackRenderer : StackRenderer() {
    override fun render(
        snapshot: StackSnapshot,
        preset: DeepSkyPresetProfile,
        performanceMode: DeepSkyPerformanceMode,
    ): Bitmap {
        return Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    }
}
