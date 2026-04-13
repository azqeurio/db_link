package dev.dblink.core.deepsky

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.PowerManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val THERMAL_STATUS_NONE_FALLBACK = 0

data class DeepSkyPerformanceSnapshot(
    val thermalStatus: Int,
    val isLowMemory: Boolean,
    val availableMemBytes: Long,
)

interface DeepSkyPerformanceProbe {
    fun snapshot(): DeepSkyPerformanceSnapshot
}

class DefaultDeepSkyPerformanceProbe(
    private val appContext: Context,
) : DeepSkyPerformanceProbe {
    override fun snapshot(): DeepSkyPerformanceSnapshot {
        val activityManager = appContext.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        val powerManager = appContext.getSystemService(PowerManager::class.java)
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager?.currentThermalStatus ?: THERMAL_STATUS_NONE_FALLBACK
        } else {
            THERMAL_STATUS_NONE_FALLBACK
        }
        val available = if (memoryInfo.availMem > 0L) {
            memoryInfo.availMem
        } else {
            Runtime.getRuntime().maxMemory()
        }
        return DeepSkyPerformanceSnapshot(
            thermalStatus = thermalStatus,
            isLowMemory = memoryInfo.lowMemory,
            availableMemBytes = available,
        )
    }
}

data class DeepSkyMemoryBudgetPlan(
    val success: Boolean,
    val enableWinsorizedPreview: Boolean,
    val enableWinsorizedFullRes: Boolean,
    val initialWorkflowState: DeepSkyWorkflowState,
    val initialPerformanceMode: DeepSkyPerformanceMode,
    val estimatedBytes: Long,
    val safeBudgetBytes: Long,
    val explanation: String,
)

open class DeepSkyMemoryPolicy(
    private val appContext: Context,
) {
    open fun evaluate(
        decoded: DecodedFrame,
        preset: DeepSkyPresetProfile,
    ): DeepSkyMemoryBudgetPlan {
        val activityManager = appContext.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        val memoryClassMb = (activityManager?.memoryClass ?: 256).toLong()
        val largeMemoryClassMb = (activityManager?.largeMemoryClass ?: memoryClassMb.toInt()).toLong()
        val isLowRam = activityManager?.isLowRamDevice ?: false
        val largeHeapRequested =
            (appContext.applicationInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP) != 0
        val available = if (memoryInfo.availMem > 0L) memoryInfo.availMem else Runtime.getRuntime().maxMemory()
        val manifestHeapBudgetBytes =
            (if (largeHeapRequested) largeMemoryClassMb else memoryClassMb) * 1024L * 1024L
        val runtimeBudget = min(
            max(Runtime.getRuntime().maxMemory(), manifestHeapBudgetBytes),
            available,
        )
        val reservedHeadroomBytes = when {
            isLowRam -> 64L * 1024L * 1024L
            runtimeBudget >= 1024L * 1024L * 1024L -> 160L * 1024L * 1024L
            runtimeBudget >= 768L * 1024L * 1024L -> 128L * 1024L * 1024L
            largeMemoryClassMb >= 512L -> 96L * 1024L * 1024L
            else -> 96L * 1024L * 1024L
        }
        val safeBudget = (runtimeBudget - reservedHeadroomBytes).coerceAtLeast(32L * 1024L * 1024L)

        val fullPixels = decoded.fullResWidth.toLong() * decoded.fullResHeight.toLong()
        val previewPixels = decoded.previewWidth.toLong() * decoded.previewHeight.toLong()
        val alignmentPixels = decoded.alignmentWidth.toLong() * decoded.alignmentHeight.toLong()

        val baselinePersistentBytes =
            fullPixels * 4L * java.lang.Float.BYTES +
                previewPixels * 4L * java.lang.Float.BYTES +
                previewPixels * java.lang.Float.BYTES * 2L
        val decodeScratchBytes =
            fullPixels * 3L * java.lang.Short.BYTES +
                alignmentPixels * java.lang.Float.BYTES +
                previewPixels * Int.SIZE_BYTES * 2L +
                fullPixels * Int.SIZE_BYTES
        val fullRobustBytes = fullPixels * java.lang.Float.BYTES * 2L
        val previewRobustBytes = previewPixels * java.lang.Float.BYTES * 2L
        val baselineTotal = baselinePersistentBytes + decodeScratchBytes + previewRobustBytes
        val robustTotal = baselineTotal + fullRobustBytes

        if (baselineTotal > safeBudget) {
            return DeepSkyMemoryBudgetPlan(
                success = false,
                enableWinsorizedPreview = false,
                enableWinsorizedFullRes = false,
                initialWorkflowState = DeepSkyWorkflowState.MemoryLimited,
                initialPerformanceMode = DeepSkyPerformanceMode.ProtectionPaused,
                estimatedBytes = baselineTotal,
                safeBudgetBytes = safeBudget,
                explanation = "Deep Sky stacking needs ${baselineTotal.toMbString()} but only ${safeBudget.toMbString()} is safely available on this device.",
            )
        }

        val fullResRobustAllowed = robustTotal <= safeBudget
        val degraded = !fullResRobustAllowed || memoryInfo.lowMemory
        return DeepSkyMemoryBudgetPlan(
            success = true,
            enableWinsorizedPreview = preset.accumulation.previewMode == StackAccumulationMode.WinsorizedMean,
            enableWinsorizedFullRes = fullResRobustAllowed &&
                preset.accumulation.fullResPreferredMode == StackAccumulationMode.WinsorizedMean,
            initialWorkflowState = if (degraded) DeepSkyWorkflowState.Degraded else DeepSkyWorkflowState.WaitingForFrames,
            initialPerformanceMode = if (degraded) DeepSkyPerformanceMode.DegradedPreview else DeepSkyPerformanceMode.Normal,
            estimatedBytes = if (fullResRobustAllowed) robustTotal else baselineTotal,
            safeBudgetBytes = safeBudget,
            explanation = if (fullResRobustAllowed) {
                "Deep Sky stacking has enough memory headroom for full preview and full-resolution robust accumulation."
            } else {
                "Deep Sky stacking will use robust preview accumulation but keep full-resolution accumulation in weighted-average mode to stay within the device budget."
            },
        )
    }
}

open class DeepSkyQualityAnalyzer {
    open fun analyzeFrame(
        decoded: DecodedFrame,
        stars: List<DetectedStar>,
        preset: DeepSkyPresetProfile,
        recentAcceptedMetrics: List<FrameQualityMetrics>,
        registrationConfidenceHint: Float?,
    ): FrameQualityMetrics {
        val usableStars = stars.filter { it.usableForMatching }
        val starCount = stars.size
        val usableCount = usableStars.size
        val medianFwhm = median(stars.map { it.fwhmPx })
        val medianElongation = median(stars.map { it.elongation })
        val backgroundLevel = percentile(sampleFloatArray(decoded.alignmentLuma), 0.35f)
        val backgroundFraction = (backgroundLevel / 65535f).coerceIn(0f, 1f)
        val backgroundNoiseSigma = robustSigma(sampleFloatArray(decoded.alignmentLuma), backgroundLevel)
        val backgroundNoiseFraction = (backgroundNoiseSigma / 65535f).coerceIn(0f, 1f)
        val saturationFraction = estimateSaturationFraction(decoded.fullResRgb48)
        val hotPixels = estimateHotPixelCandidates(decoded.alignmentLuma, decoded.alignmentWidth, decoded.alignmentHeight)

        val starScore = (usableCount.toFloat() / (preset.quality.minUsableStars * 1.5f)).coerceIn(0f, 1f)
        val sharpnessScore = normalizeInverse(medianFwhm, 1.1f, preset.quality.maxMedianFwhmPx)
        val roundnessScore = normalizeInverse(medianElongation, 1.0f, preset.quality.maxMedianElongation)
        val backgroundScore = normalizeInverse(backgroundFraction, 0f, preset.quality.maxBackgroundFraction)
        val noiseScore = normalizeInverse(backgroundNoiseFraction, 0f, preset.quality.maxNoiseFraction)
        val confidenceScore = registrationConfidenceHint?.coerceIn(0f, 1f) ?: 1f
        val qualityScore =
            (starScore * 0.25f) +
                (sharpnessScore * 0.25f) +
                (roundnessScore * 0.20f) +
                (((backgroundScore + noiseScore) / 2f) * 0.15f) +
                (confidenceScore * 0.15f)

        return FrameQualityMetrics(
            detectedStarCount = starCount,
            usableStarCount = usableCount,
            medianFwhmPx = medianFwhm,
            medianElongation = medianElongation,
            backgroundLevel = backgroundLevel,
            backgroundFraction = backgroundFraction,
            backgroundNoiseSigma = backgroundNoiseSigma,
            backgroundNoiseFraction = backgroundNoiseFraction,
            saturationFraction = saturationFraction,
            hotPixelCandidateCount = hotPixels,
            qualityScore = qualityScore.coerceIn(0f, 1f),
            referenceCandidateScore = computeReferenceCandidateScore(
                starScore = starScore,
                sharpnessScore = sharpnessScore,
                roundnessScore = roundnessScore,
                backgroundScore = (backgroundScore + noiseScore) / 2f,
                registrationConfidence = confidenceScore,
                recentAcceptedMetrics = recentAcceptedMetrics,
            ),
        )
    }

    open fun decideFrame(
        qualityMetrics: FrameQualityMetrics,
        registrationMetrics: FrameRegistrationMetrics?,
        preset: DeepSkyPresetProfile,
        recentAcceptedMetrics: List<FrameQualityMetrics>,
        performanceMode: DeepSkyPerformanceMode,
        transformPlausible: Boolean,
    ): DeepSkyFrameDecision {
        if (performanceMode == DeepSkyPerformanceMode.ProtectionPaused) {
            return DeepSkyFrameDecision(
                kind = DeepSkyFrameDecisionKind.HardReject,
                reason = DeepSkyRejectionReason.ThermalOrPerformanceBackoff,
                explanation = "Preview processing is paused to protect device temperature or memory stability.",
                weightMultiplier = 0f,
            )
        }
        if (qualityMetrics.usableStarCount < preset.quality.minUsableStars) {
            return hardReject(
                DeepSkyRejectionReason.TooFewStars,
                "Too few usable stars were detected for reliable alignment.",
            )
        }
        if (qualityMetrics.medianElongation > preset.quality.maxMedianElongation) {
            return hardReject(
                DeepSkyRejectionReason.TooManyElongatedStars,
                "Stars are too elongated for stable tripod-based registration.",
            )
        }
        if (qualityMetrics.backgroundFraction > preset.quality.maxBackgroundFraction) {
            return hardReject(
                DeepSkyRejectionReason.BackgroundTooBright,
                "Sky background is too bright for effective stacking with this preset.",
            )
        }
        if (qualityMetrics.saturationFraction > preset.quality.maxSaturationFraction) {
            return hardReject(
                DeepSkyRejectionReason.SaturatedFrame,
                "The frame contains too many saturated highlights to stack safely.",
            )
        }
        if (!transformPlausible) {
            return hardReject(
                DeepSkyRejectionReason.ImplausibleTransform,
                "Alignment transform exceeds the safe limits for this fixed-tripod preset.",
            )
        }

        val registrationConfidence = registrationMetrics?.confidenceScore ?: 1f
        if (registrationConfidence < preset.quality.hardConfidenceFloor) {
            return hardReject(
                DeepSkyRejectionReason.RegistrationLowConfidence,
                "Registration confidence is too low for this frame to contribute safely.",
            )
        }

        val isOutlier = isOutlierRelativeToSession(
            qualityMetrics = qualityMetrics,
            recentAcceptedMetrics = recentAcceptedMetrics,
            policy = preset.quality,
        )
        if (qualityMetrics.backgroundNoiseFraction > preset.quality.maxNoiseFraction * 1.12f) {
            return hardReject(
                DeepSkyRejectionReason.ExcessiveNoise,
                "Background noise is too high for this frame to improve the stack.",
            )
        }

        val softReasons = mutableListOf<DeepSkyRejectionReason>()
        if (qualityMetrics.backgroundNoiseFraction > preset.quality.maxNoiseFraction) {
            softReasons += DeepSkyRejectionReason.ExcessiveNoise
        }
        if (registrationConfidence < preset.quality.softConfidenceFloor) {
            softReasons += DeepSkyRejectionReason.RegistrationLowConfidence
        }
        if (isOutlier) {
            softReasons += DeepSkyRejectionReason.OutlierFrameRelativeToSession
        }
        if (registrationMetrics?.usedFallbackPath == true) {
            softReasons += DeepSkyRejectionReason.RegistrationLowConfidence
        }

        return if (softReasons.isNotEmpty()) {
            val qualityWeight = max(qualityMetrics.qualityScore, preset.quality.softWeightFloor)
            val confidenceWeight = registrationConfidence.coerceIn(0f, 1f)
            val weight = lerp(
                preset.accumulation.softAcceptWeightMin,
                preset.accumulation.softAcceptWeightMax,
                ((qualityWeight + confidenceWeight) / 2f).coerceIn(0f, 1f),
            )
            DeepSkyFrameDecision(
                kind = DeepSkyFrameDecisionKind.SoftAccept,
                reason = softReasons.first(),
                explanation = "Frame is usable but below the ideal quality target, so it will be stacked with reduced weight.",
                weightMultiplier = weight,
            )
        } else {
            val weight = lerp(
                preset.accumulation.fullAcceptWeightMin,
                preset.accumulation.fullAcceptWeightMax,
                (((registrationConfidence + qualityMetrics.qualityScore) / 2f).coerceIn(0f, 1f)),
            )
            DeepSkyFrameDecision(
                kind = DeepSkyFrameDecisionKind.Accept,
                reason = null,
                explanation = "Frame passed the current quality and registration thresholds.",
                weightMultiplier = weight,
            )
        }
    }

    open fun summarizeSession(
        qualityMetrics: List<FrameQualityMetrics>,
        registrationMetrics: List<FrameRegistrationMetrics>,
    ): DeepSkySessionQualitySummary {
        if (qualityMetrics.isEmpty() || registrationMetrics.isEmpty()) {
            return DeepSkySessionQualitySummary()
        }
        val averageConfidence = registrationMetrics.map { it.confidenceScore }.average().toFloat()
        val averageResidual = registrationMetrics.map { it.residualPx }.average().toFloat()
        val averageSharpness = qualityMetrics.map { it.medianFwhmPx }.average().toFloat()
        val averageBackground = qualityMetrics.map { it.backgroundFraction }.average().toFloat()
        val healthLabel = when {
            averageConfidence >= 0.78f && averageResidual <= 1.8f && averageBackground <= 0.16f -> "Healthy"
            averageConfidence >= 0.60f && averageResidual <= 2.6f -> "Usable"
            else -> "Unstable"
        }
        return DeepSkySessionQualitySummary(
            healthLabel = healthLabel,
            averageConfidence = averageConfidence,
            averageResidualPx = averageResidual,
            averageSharpnessPx = averageSharpness,
            averageBackgroundFraction = averageBackground,
        )
    }

    private fun computeReferenceCandidateScore(
        starScore: Float,
        sharpnessScore: Float,
        roundnessScore: Float,
        backgroundScore: Float,
        registrationConfidence: Float,
        recentAcceptedMetrics: List<FrameQualityMetrics>,
    ): Float {
        val sessionStabilityBoost = if (recentAcceptedMetrics.isEmpty()) {
            1f
        } else {
            recentAcceptedMetrics.map { it.qualityScore }.average().toFloat().coerceIn(0.7f, 1f)
        }
        return (
            (starScore * 0.25f) +
                (sharpnessScore * 0.25f) +
                (roundnessScore * 0.20f) +
                (backgroundScore * 0.15f) +
                (registrationConfidence * 0.15f)
            ) * sessionStabilityBoost
    }

    private fun isOutlierRelativeToSession(
        qualityMetrics: FrameQualityMetrics,
        recentAcceptedMetrics: List<FrameQualityMetrics>,
        policy: DeepSkyQualityPolicy,
    ): Boolean {
        if (recentAcceptedMetrics.size < 4) return false
        val qualities = recentAcceptedMetrics.map { it.qualityScore }
        val backgrounds = recentAcceptedMetrics.map { it.backgroundFraction }
        val elongations = recentAcceptedMetrics.map { it.medianElongation }
        val qualityMedian = median(qualities)
        val backgroundMedian = median(backgrounds)
        val elongationMedian = median(elongations)
        val qualityMad = mad(qualities, qualityMedian).coerceAtLeast(0.01f)
        val backgroundMad = mad(backgrounds, backgroundMedian).coerceAtLeast(0.002f)
        val elongationMad = mad(elongations, elongationMedian).coerceAtLeast(0.02f)

        return qualityMetrics.qualityScore < qualityMedian - (qualityMad * policy.sessionOutlierMadMultiplier) ||
            qualityMetrics.backgroundFraction > backgroundMedian + (backgroundMad * policy.sessionOutlierMadMultiplier) ||
            qualityMetrics.medianElongation > elongationMedian + (elongationMad * policy.sessionOutlierMadMultiplier)
    }

    private fun estimateSaturationFraction(fullResRgb48: ShortArray): Float {
        if (fullResRgb48.isEmpty()) return 0f
        val pixelCount = fullResRgb48.size / 3
        val step = max(1, pixelCount / 8192)
        var saturated = 0
        var samples = 0
        var pixelIndex = 0
        while (pixelIndex < pixelCount) {
            val srcIndex = pixelIndex * 3
            val r = fullResRgb48[srcIndex].toInt() and 0xffff
            val g = fullResRgb48[srcIndex + 1].toInt() and 0xffff
            val b = fullResRgb48[srcIndex + 2].toInt() and 0xffff
            if (r >= 65000 || g >= 65000 || b >= 65000) {
                saturated += 1
            }
            samples += 1
            pixelIndex += step
        }
        return if (samples == 0) 0f else saturated.toFloat() / samples.toFloat()
    }

    private fun estimateHotPixelCandidates(
        luma: FloatArray,
        width: Int,
        height: Int,
    ): Int {
        if (width < 3 || height < 3) return 0
        val background = percentile(sampleFloatArray(luma), 0.35f)
        val threshold = background + max(1024f, background * 0.25f)
        var count = 0
        for (y in 1 until height - 1) {
            val rowOffset = y * width
            for (x in 1 until width - 1) {
                val center = luma[rowOffset + x]
                if (center <= threshold) continue
                val left = luma[rowOffset + x - 1]
                val right = luma[rowOffset + x + 1]
                val up = luma[(y - 1) * width + x]
                val down = luma[(y + 1) * width + x]
                if (center > left * 1.8f && center > right * 1.8f && center > up * 1.8f && center > down * 1.8f) {
                    count += 1
                }
            }
        }
        return count
    }

    private fun hardReject(
        reason: DeepSkyRejectionReason,
        explanation: String,
    ): DeepSkyFrameDecision {
        return DeepSkyFrameDecision(
            kind = DeepSkyFrameDecisionKind.HardReject,
            reason = reason,
            explanation = explanation,
            weightMultiplier = 0f,
        )
    }
}

object DeepSkyDiagnosticCatalog {
    fun describeDecision(decision: DeepSkyFrameDecision?): String? {
        return when (decision?.reason) {
            DeepSkyRejectionReason.TooFewStars ->
                "Too few stars detected. Try a wider field, higher ISO, or darker sky."
            DeepSkyRejectionReason.TooManyElongatedStars ->
                "Star shapes look stretched. Check tripod stability or reduce focal length."
            DeepSkyRejectionReason.BackgroundTooBright ->
                "Sky background is too bright for effective stacking. Wait for darker conditions or shorten the exposure."
            DeepSkyRejectionReason.ExcessiveNoise ->
                "Background noise is high. Lower ISO or improve sky conditions if possible."
            DeepSkyRejectionReason.RegistrationLowConfidence ->
                "Alignment confidence is low. Check framing stability, focus, or shorten the sub-exposure."
            DeepSkyRejectionReason.ImplausibleTransform ->
                "Alignment unstable. Check tripod stability or reduce focal length."
            DeepSkyRejectionReason.SaturatedFrame ->
                "Highlights are clipping. Reduce exposure or ISO."
            DeepSkyRejectionReason.DecodeFailure ->
                "The imported frame could not be decoded."
            DeepSkyRejectionReason.MemoryBudgetExceeded ->
                "This device does not have enough safe memory headroom for the current stack size."
            DeepSkyRejectionReason.ThermalOrPerformanceBackoff ->
                "Preview throttled to protect device temperature or memory stability."
            DeepSkyRejectionReason.OutlierFrameRelativeToSession ->
                "This frame is unusually weak compared with the rest of the session, so it is being downweighted."
            null -> decision?.explanation
        }
    }

    fun describePerformanceMode(mode: DeepSkyPerformanceMode): String? {
        return when (mode) {
            DeepSkyPerformanceMode.Normal -> null
            DeepSkyPerformanceMode.DegradedPreview ->
                "Preview detail is reduced to preserve stability during a long session."
            DeepSkyPerformanceMode.Throttled ->
                "Preview refresh is throttled because processing load is approaching the capture cadence."
            DeepSkyPerformanceMode.ProtectionPaused ->
                "Stacking is temporarily paused to protect the device."
        }
    }
}

internal fun selectPerformanceMode(
    currentMode: DeepSkyPerformanceMode,
    snapshot: DeepSkyPerformanceSnapshot,
    rollingProcessingMs: Long,
    cadenceMs: Long,
    policy: DeepSkyPerformancePolicy,
): DeepSkyPerformanceMode {
    if (snapshot.isLowMemory) {
        return DeepSkyPerformanceMode.ProtectionPaused
    }
    if (snapshot.thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL) {
        return DeepSkyPerformanceMode.ProtectionPaused
    }
    val loadRatio = if (cadenceMs <= 0L) 0f else rollingProcessingMs.toFloat() / cadenceMs.toFloat()
    return when {
        loadRatio >= policy.protectionLoadRatio -> DeepSkyPerformanceMode.ProtectionPaused
        snapshot.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ||
            loadRatio >= policy.throttleLoadRatio -> DeepSkyPerformanceMode.Throttled
        snapshot.thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE ||
            loadRatio >= policy.degradeLoadRatio -> DeepSkyPerformanceMode.DegradedPreview
        currentMode == DeepSkyPerformanceMode.ProtectionPaused &&
            loadRatio >= policy.degradeLoadRatio -> DeepSkyPerformanceMode.Throttled
        else -> DeepSkyPerformanceMode.Normal
    }
}

internal fun List<Float>.medianOrZero(): Float = median(this)

internal fun median(values: List<Float>): Float {
    if (values.isEmpty()) return 0f
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[middle - 1] + sorted[middle]) / 2f
    } else {
        sorted[middle]
    }
}

internal fun percentile(values: FloatArray, percentile: Float): Float {
    if (values.isEmpty()) return 0f
    val sorted = values.copyOf()
    sorted.sort()
    val index = ((sorted.lastIndex) * percentile.coerceIn(0f, 1f)).roundToInt().coerceIn(0, sorted.lastIndex)
    return sorted[index]
}

internal fun robustSigma(values: FloatArray, center: Float): Float {
    if (values.isEmpty()) return 0f
    val deviations = FloatArray(values.size) { index -> abs(values[index] - center) }
    return percentile(deviations, 0.50f) * 1.4826f
}

internal fun mad(values: List<Float>, center: Float): Float {
    if (values.isEmpty()) return 0f
    val deviations = values.map { abs(it - center) }
    return median(deviations)
}

internal fun sampleFloatArray(values: FloatArray, maxSamples: Int = 65536): FloatArray {
    if (values.size <= maxSamples) return values.copyOf()
    val step = max(1, values.size / maxSamples)
    val sampled = FloatArray((values.size + step - 1) / step)
    var dstIndex = 0
    var srcIndex = 0
    while (srcIndex < values.size && dstIndex < sampled.size) {
        sampled[dstIndex++] = values[srcIndex]
        srcIndex += step
    }
    return if (dstIndex == sampled.size) sampled else sampled.copyOf(dstIndex)
}

internal fun normalizeInverse(value: Float, best: Float, worst: Float): Float {
    if (worst <= best) return 1f
    return (1f - ((value - best) / (worst - best))).coerceIn(0f, 1f)
}

internal fun lerp(start: Float, end: Float, t: Float): Float {
    return start + (end - start) * t.coerceIn(0f, 1f)
}

private fun Long.toMbString(): String {
    return "${this / (1024L * 1024L)}MB"
}
