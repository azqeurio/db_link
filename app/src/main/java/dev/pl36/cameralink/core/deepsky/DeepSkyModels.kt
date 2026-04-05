package dev.pl36.cameralink.core.deepsky

import android.graphics.Bitmap
import dev.pl36.cameralink.core.model.GeoTagLocationSample
import kotlin.math.abs
import kotlin.math.hypot

data class CapturedFrame(
    val id: String,
    val captureTimeMs: Long,
    val rawPath: String,
    val previewPath: String,
    val width: Int,
    val height: Int,
    val exposureSec: Double?,
    val iso: Int?,
    val focalLengthMm: Float?,
    val aperture: Float?,
    val cameraMetadata: Map<String, String> = emptyMap(),
)

data class DecodedFrame(
    val fullResRgb48: ShortArray,
    val fullResWidth: Int,
    val fullResHeight: Int,
    val alignmentLuma: FloatArray,
    val alignmentWidth: Int,
    val alignmentHeight: Int,
    val previewArgb: IntArray,
    val previewWidth: Int,
    val previewHeight: Int,
)

data class DeepSkySkyHintContext(
    val locationSample: GeoTagLocationSample? = null,
    val rotationHintDeg: Float? = null,
)

data class DeepSkySkyHint(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val captureTimeMs: Long,
    val fovHorizontalDeg: Float? = null,
    val fovVerticalDeg: Float? = null,
    val raEstimateHours: Double? = null,
    val decEstimateDeg: Double? = null,
    val rotationHintDeg: Float? = null,
)

enum class DeepSkyTransformModel {
    Rigid,
    Affine,
}

data class DetectedStar(
    val x: Float,
    val y: Float,
    val flux: Float,
    val peak: Float = flux,
    val localContrast: Float = 0f,
    val fwhmPx: Float = 0f,
    val elongation: Float = 1f,
    val isolationScore: Float = 0f,
    val usableForMatching: Boolean = true,
) {
    val matchingRank: Float
        get() {
            val shapeScore = (1.8f - elongation).coerceAtLeast(0f)
            return (flux * 0.45f) +
                (peak * 0.20f) +
                (localContrast * 0.20f) +
                (isolationScore * 0.15f) +
                (shapeScore * flux * 0.05f)
        }
}

data class RegistrationTransform(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 1f,
    val rotationDeg: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val shear: Float = 0f,
    val model: DeepSkyTransformModel = DeepSkyTransformModel.Rigid,
) {
    fun translationMagnitude(): Float = hypot(dx.toDouble(), dy.toDouble()).toFloat()

    fun scaled(scaleX: Float, scaleY: Float): RegistrationTransform {
        return copy(
            dx = dx * scaleX,
            dy = dy * scaleY,
        )
    }

    fun applyRelative(x: Float, y: Float): Pair<Float, Float> {
        return Pair(
            (a * x) + (b * y),
            (c * x) + (d * y),
        )
    }

    companion object {
        val Identity = RegistrationTransform()
    }
}

enum class DeepSkyRejectionReason {
    DecodeFailure,
    TooFewStars,
    TooManyElongatedStars,
    BackgroundTooBright,
    ExcessiveNoise,
    RegistrationLowConfidence,
    ImplausibleTransform,
    SaturatedFrame,
    MemoryBudgetExceeded,
    ThermalOrPerformanceBackoff,
    OutlierFrameRelativeToSession,
}

enum class DeepSkyFrameDecisionKind {
    Accept,
    SoftAccept,
    HardReject,
}

data class FrameQualityMetrics(
    val detectedStarCount: Int = 0,
    val usableStarCount: Int = 0,
    val medianFwhmPx: Float = 0f,
    val medianElongation: Float = 1f,
    val backgroundLevel: Float = 0f,
    val backgroundFraction: Float = 0f,
    val backgroundNoiseSigma: Float = 0f,
    val backgroundNoiseFraction: Float = 0f,
    val saturationFraction: Float = 0f,
    val hotPixelCandidateCount: Int = 0,
    val qualityScore: Float = 0f,
    val referenceCandidateScore: Float = 0f,
)

data class FrameRegistrationMetrics(
    val matchedStars: Int = 0,
    val inlierRatio: Float = 0f,
    val residualPx: Float = 0f,
    val rotationDeg: Float = 0f,
    val translationMagnitudePx: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val shear: Float = 0f,
    val confidenceScore: Float = 0f,
    val usedFallbackPath: Boolean = false,
    val referenceFrameId: String? = null,
    val transformModel: DeepSkyTransformModel = DeepSkyTransformModel.Rigid,
)

data class DeepSkyFrameDecision(
    val kind: DeepSkyFrameDecisionKind,
    val reason: DeepSkyRejectionReason? = null,
    val explanation: String,
    val weightMultiplier: Float,
) {
    val isAccepted: Boolean
        get() = kind != DeepSkyFrameDecisionKind.HardReject
}

data class FrameRegistrationResult(
    val success: Boolean,
    val transform: RegistrationTransform,
    val metrics: FrameRegistrationMetrics,
    val reason: DeepSkyRejectionReason? = null,
    val debugMessage: String? = null,
) {
    val score: Float
        get() = metrics.confidenceScore

    val starCount: Int
        get() = metrics.matchedStars

    val matchedStarCount: Int
        get() = metrics.matchedStars
}

data class DeepSkySessionStats(
    val acceptedFrameCount: Int = 0,
    val softAcceptedCount: Int = 0,
    val hardRejectedCount: Int = 0,
    val totalExposureSec: Double = 0.0,
    val effectiveIntegrationSec: Double = 0.0,
    val rollingAcceptanceRate: Float = 0f,
)

data class DeepSkySessionQualitySummary(
    val healthLabel: String = "Waiting",
    val averageConfidence: Float? = null,
    val averageResidualPx: Float? = null,
    val averageSharpnessPx: Float? = null,
    val averageBackgroundFraction: Float? = null,
)

data class DeepSkyStageTimings(
    val decodeMs: Long = 0L,
    val detectMs: Long = 0L,
    val qualityMs: Long = 0L,
    val registerMs: Long = 0L,
    val accumulateMs: Long = 0L,
    val renderMs: Long = 0L,
    val totalMs: Long = 0L,
)

enum class DeepSkySessionStatus {
    Idle,
    Waiting,
    Processing,
    Running,
    Error,
    Stopped,
}

enum class DeepSkyWorkflowState {
    Idle,
    WaitingForFrames,
    ElectingReference,
    StackingHealthy,
    StackingUnstable,
    Degraded,
    MemoryLimited,
    ActionRequired,
    Stopped,
}

enum class DeepSkyPerformanceMode {
    Normal,
    DegradedPreview,
    Throttled,
    ProtectionPaused,
}

enum class DeepSkyReferenceStatus {
    None,
    WarmingUp,
    Elected,
    Rebased,
    Drifting,
}

enum class DeepSkyTargetStackStyle {
    Balanced,
    Aggressive,
}

enum class DeepSkyStretchMode {
    Arcsinh,
    Log,
}

enum class DeepSkyPresetId {
    WideFieldBalanced,
    WideFieldAggressive,
    MidTeleConservative,
    MidTeleBalanced,
    LongTeleSafe,
    LongTeleAggressive,
}

data class DeepSkyIsoRange(
    val min: Int,
    val max: Int,
) {
    val label: String
        get() = "ISO $min-$max"
}

data class DeepSkyRegistrationPolicy(
    val preferredTransformModel: DeepSkyTransformModel,
    val allowAffineFallback: Boolean,
    val maxRotationDeg: Float,
    val maxShiftPxAt1024: Int,
    val minStars: Int,
    val minMatches: Int,
    val minScore: Float,
    val minInlierRatio: Float,
    val maxResidualPx: Float,
    val maxScaleDelta: Float,
    val maxShear: Float,
    val maxFallbackChainFrames: Int,
    val maxReferenceDeviationPxAt1024: Int,
    val maxFallbackRotationDeg: Float,
    val maxFallbackTranslationPxAt1024: Int,
    val coarsePairDistanceMinPx: Float = 24f,
    val candidateBinSizePx: Float = 2f,
    val topStarsForVoting: Int = 20,
    val maxRotationHypotheses: Int = 24,
    val matchRadiusPx: Float = 6f,
) {
    fun maxShiftPxFor(alignmentWidth: Int): Float {
        return (maxShiftPxAt1024.toFloat() * (alignmentWidth.toFloat() / 1024f)).coerceAtLeast(16f)
    }

    fun maxReferenceDeviationPxFor(alignmentWidth: Int): Float {
        return (maxReferenceDeviationPxAt1024.toFloat() * (alignmentWidth.toFloat() / 1024f)).coerceAtLeast(20f)
    }

    fun maxFallbackTranslationPxFor(alignmentWidth: Int): Float {
        return (maxFallbackTranslationPxAt1024.toFloat() * (alignmentWidth.toFloat() / 1024f)).coerceAtLeast(20f)
    }

    fun isTransformPlausible(
        transform: RegistrationTransform,
        alignmentWidth: Int,
        fallbackPath: Boolean = false,
    ): Boolean {
        val rotationLimit = if (fallbackPath) maxFallbackRotationDeg else maxRotationDeg
        val translationLimit = if (fallbackPath) {
            maxFallbackTranslationPxFor(alignmentWidth)
        } else {
            maxShiftPxFor(alignmentWidth)
        }
        if (abs(transform.rotationDeg) > rotationLimit) return false
        if (transform.translationMagnitude() > translationLimit) return false
        if (transform.model == DeepSkyTransformModel.Affine) {
            if (abs(transform.scaleX - 1f) > maxScaleDelta) return false
            if (abs(transform.scaleY - 1f) > maxScaleDelta) return false
            if (abs(transform.shear) > maxShear) return false
        }
        return true
    }
}

data class DeepSkyQualityPolicy(
    val minUsableStars: Int,
    val maxMedianFwhmPx: Float,
    val maxMedianElongation: Float,
    val maxBackgroundFraction: Float,
    val maxNoiseFraction: Float,
    val maxSaturationFraction: Float,
    val hardConfidenceFloor: Float,
    val softConfidenceFloor: Float,
    val softWeightFloor: Float,
    val sessionOutlierMadMultiplier: Float,
)

data class DeepSkyReferencePolicy(
    val warmupAcceptedFrames: Int,
    val earlyRebaseAcceptedFrames: Int,
    val earlyRebaseImprovementFraction: Float,
    val driftWindowFrames: Int,
    val driftSuccessFloor: Float,
    val driftRebaseImprovementFraction: Float,
)

enum class StackAccumulationMode {
    WeightedAverage,
    WinsorizedMean,
}

data class DeepSkyAccumulationPolicy(
    val previewMode: StackAccumulationMode,
    val fullResPreferredMode: StackAccumulationMode,
    val previewWinsorStartFrame: Int,
    val winsorSigmaMultiplier: Float,
    val softAcceptWeightMin: Float,
    val softAcceptWeightMax: Float,
    val fullAcceptWeightMin: Float,
    val fullAcceptWeightMax: Float,
)

data class DeepSkyPreviewStretchProfile(
    val mode: DeepSkyStretchMode,
    val aggressiveness: Float,
    val blackPointLift: Float,
    val midtone: Float,
)

data class DeepSkyPreviewPolicy(
    val stretch: DeepSkyPreviewStretchProfile,
    val maxColorBalanceShift: Float,
    val blackRiseAlpha: Float,
    val blackFallAlpha: Float,
    val whiteRiseAlpha: Float,
    val whiteFallAlpha: Float,
    val colorRiseAlpha: Float,
    val colorFallAlpha: Float,
)

data class DeepSkyPerformancePolicy(
    val previewMaxEdgeNormal: Int,
    val previewMaxEdgeDegraded: Int,
    val renderEveryAcceptedFramesNormal: Int,
    val renderEveryAcceptedFramesThrottled: Int,
    val degradeLoadRatio: Float,
    val throttleLoadRatio: Float,
    val protectionLoadRatio: Float,
    val skipQualityEveryOtherWhenThrottled: Boolean,
)

data class DeepSkyPresetProfile(
    val id: DeepSkyPresetId,
    val displayName: String,
    val recommendedShutterSec: Double,
    val isoRange: DeepSkyIsoRange,
    val apertureSuggestion: String,
    val stackCadenceExpectationSec: Double,
    val registrationStrictness: String,
    val rejectionSensitivity: String,
    val registration: DeepSkyRegistrationPolicy,
    val quality: DeepSkyQualityPolicy,
    val reference: DeepSkyReferencePolicy,
    val accumulation: DeepSkyAccumulationPolicy,
    val preview: DeepSkyPreviewPolicy,
    val performance: DeepSkyPerformancePolicy,
)

data class DeepSkyCaptureContext(
    val focalLengthMm: Float? = null,
    val aperture: Float? = null,
    val exposureSec: Double? = null,
    val tripodFixed: Boolean = true,
    val targetStyle: DeepSkyTargetStackStyle = DeepSkyTargetStackStyle.Balanced,
)

data class DeepSkyPresetRecommendation(
    val profile: DeepSkyPresetProfile,
    val reason: String,
)

data class DeepSkyReferenceStatusSummary(
    val status: DeepSkyReferenceStatus = DeepSkyReferenceStatus.None,
    val activeReferenceFrameId: String? = null,
    val candidateFrameId: String? = null,
    val warmupAcceptedFrames: Int = 0,
    val targetWarmupFrames: Int = 0,
)

data class DeepSkyLiveStackUiState(
    val sessionStatus: DeepSkySessionStatus = DeepSkySessionStatus.Idle,
    val workflowState: DeepSkyWorkflowState = DeepSkyWorkflowState.Idle,
    val performanceMode: DeepSkyPerformanceMode = DeepSkyPerformanceMode.Normal,
    val statusLabel: String = "Deep Sky Live Stack idle",
    val previewBitmap: Bitmap? = null,
    val frameCount: Int = 0,
    val softAcceptedCount: Int = 0,
    val rejectedCount: Int = 0,
    val hardRejectedCount: Int = 0,
    val totalExposureSec: Double = 0.0,
    val effectiveIntegrationSec: Double = 0.0,
    val rollingAcceptanceRate: Float = 0f,
    val lastRegistrationScore: Float? = null,
    val detectedStarCount: Int = 0,
    val matchedStarCount: Int = 0,
    val lastRegistrationMetrics: FrameRegistrationMetrics? = null,
    val lastFrameQualityMetrics: FrameQualityMetrics? = null,
    val lastDecision: DeepSkyFrameDecision? = null,
    val lastStageTimings: DeepSkyStageTimings? = null,
    val sessionQualitySummary: DeepSkySessionQualitySummary = DeepSkySessionQualitySummary(),
    val selectedPreset: DeepSkyPresetProfile? = null,
    val recommendedPreset: DeepSkyPresetProfile? = null,
    val recommendationReason: String =
        "Waiting for camera metadata or a manual focal length so the Deep Sky guide can size the stack frame.",
    val autoFocalLengthMm: Float? = null,
    val manualFocalLengthMm: Float? = null,
    val activeFocalLengthMm: Float? = null,
    val usingManualFocalLength: Boolean = true,
    val focalLengthSourceLabel: String = "Manual input",
    val fieldOfViewLabel: String = "Set focal length to size the center stack guide.",
    val stackFrameLabel: String = "Center stack guide",
    val stackFrameWidthFraction: Float = 0.42f,
    val stackFrameHeightFraction: Float = 0.42f,
    val lastRejectionReason: String? = null,
    val lastRejectionKind: DeepSkyRejectionReason? = null,
    val consecutiveRejectedFrames: Int = 0,
    val manualPresetOverride: DeepSkyPresetId? = null,
    val isSessionActive: Boolean = false,
    val queueDepth: Int = 0,
    val lastAcceptedFrameId: String? = null,
    val referenceStatus: DeepSkyReferenceStatusSummary = DeepSkyReferenceStatusSummary(),
    val activeDiagnosticMessage: String? = null,
    val availablePresets: List<DeepSkyPresetProfile> = emptyList(),
)
