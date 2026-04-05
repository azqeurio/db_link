package dev.pl36.cameralink.core.deepsky

/**
 * The preset logic stays explicit and deterministic.
 *
 * Commercial tuning only works if every threshold lives in visible policy
 * structures rather than drifting into scattered heuristics.
 */
class DeepSkyPresetEngine {
    val profiles: List<DeepSkyPresetProfile> = listOf(
        wideFieldBalanced(),
        wideFieldAggressive(),
        midTeleConservative(),
        midTeleBalanced(),
        longTeleSafe(),
        longTeleAggressive(),
    )

    private val profileById = profiles.associateBy { it.id }

    fun profile(id: DeepSkyPresetId): DeepSkyPresetProfile = requireNotNull(profileById[id]) {
        "Unknown Deep Sky preset: $id"
    }

    fun recommend(context: DeepSkyCaptureContext): DeepSkyPresetRecommendation {
        if (!context.tripodFixed) {
            val fallback = profile(DeepSkyPresetId.WideFieldBalanced)
            return DeepSkyPresetRecommendation(
                profile = fallback,
                reason = "Deep Sky Live Stack is tuned for fixed-tripod framing, so ${fallback.displayName} is the safe fallback.",
            )
        }

        val focalLength = context.focalLengthMm
        if (focalLength == null) {
            val fallback = profile(DeepSkyPresetId.WideFieldBalanced)
            return DeepSkyPresetRecommendation(
                profile = fallback,
                reason = "Recommended ${fallback.displayName} until the first frame provides focal length and aperture metadata.",
            )
        }

        val aperture = context.aperture
        val exposureSec = context.exposureSec
        val fastLens = aperture != null && aperture <= 2.8f
        val alreadyShortSubs = exposureSec != null && exposureSec <= 8.0
        val alreadyLongSubs = exposureSec != null && exposureSec >= 15.0
        val preferAggressive = when (context.targetStyle) {
            DeepSkyTargetStackStyle.Aggressive -> true
            DeepSkyTargetStackStyle.Balanced -> fastLens && alreadyShortSubs
        }

        val chosen = when {
            focalLength <= 35f -> {
                if (preferAggressive && !alreadyLongSubs) {
                    profile(DeepSkyPresetId.WideFieldAggressive)
                } else {
                    profile(DeepSkyPresetId.WideFieldBalanced)
                }
            }
            focalLength <= 150f -> {
                if (preferAggressive && !alreadyLongSubs) {
                    profile(DeepSkyPresetId.MidTeleBalanced)
                } else {
                    profile(DeepSkyPresetId.MidTeleConservative)
                }
            }
            else -> {
                if (preferAggressive && fastLens && !alreadyLongSubs) {
                    profile(DeepSkyPresetId.LongTeleAggressive)
                } else {
                    profile(DeepSkyPresetId.LongTeleSafe)
                }
            }
        }

        val focalText = focalLength.toInt().toString()
        val apertureText = aperture?.let { "f/${trimFloat(it)}" } ?: "unknown aperture"
        val exposureText = exposureSec?.let { "${trimDouble(it)}s subs" } ?: "unknown sub-exposure length"
        val reason = when (chosen.id) {
            DeepSkyPresetId.WideFieldBalanced ->
                "Recommended ${chosen.displayName} because focal length is ${focalText}mm and $apertureText favors forgiving wide-field registration on a fixed tripod."
            DeepSkyPresetId.WideFieldAggressive ->
                "Recommended ${chosen.displayName} because focal length is ${focalText}mm, $apertureText is fast, and $exposureText leaves room for brighter wide-field subs."
            DeepSkyPresetId.MidTeleConservative ->
                "Recommended ${chosen.displayName} because focal length is ${focalText}mm and shorter sub-exposures improve mid-tele registration reliability on a fixed tripod."
            DeepSkyPresetId.MidTeleBalanced ->
                "Recommended ${chosen.displayName} because focal length is ${focalText}mm, $apertureText is fast enough, and $exposureText supports a moderate mid-tele cadence."
            DeepSkyPresetId.LongTeleSafe ->
                "Recommended ${chosen.displayName} because focal length is ${focalText}mm, where shorter subs reduce drift and keep long-tele registration stable."
            DeepSkyPresetId.LongTeleAggressive ->
                "Recommended ${chosen.displayName} because focal length is ${focalText}mm and $apertureText is fast enough to push a more ambitious long-tele stack."
        }
        return DeepSkyPresetRecommendation(chosen, reason)
    }

    private fun wideFieldBalanced(): DeepSkyPresetProfile {
        return DeepSkyPresetProfile(
            id = DeepSkyPresetId.WideFieldBalanced,
            displayName = "Wide Field Balanced",
            recommendedShutterSec = 15.0,
            isoRange = DeepSkyIsoRange(800, 1600),
            apertureSuggestion = "Use the lens close to wide open if the corners stay acceptable.",
            stackCadenceExpectationSec = 18.0,
            registrationStrictness = "Balanced",
            rejectionSensitivity = "Medium",
            registration = DeepSkyRegistrationPolicy(
                preferredTransformModel = DeepSkyTransformModel.Affine,
                allowAffineFallback = true,
                maxRotationDeg = 2.0f,
                maxShiftPxAt1024 = 110,
                minStars = 10,
                minMatches = 6,
                minScore = 0.52f,
                minInlierRatio = 0.44f,
                maxResidualPx = 2.6f,
                maxScaleDelta = 0.035f,
                maxShear = 0.020f,
                maxFallbackChainFrames = 3,
                maxReferenceDeviationPxAt1024 = 180,
                maxFallbackRotationDeg = 2.6f,
                maxFallbackTranslationPxAt1024 = 132,
            ),
            quality = DeepSkyQualityPolicy(
                minUsableStars = 8,
                maxMedianFwhmPx = 2.9f,
                maxMedianElongation = 1.55f,
                maxBackgroundFraction = 0.24f,
                maxNoiseFraction = 0.060f,
                maxSaturationFraction = 0.018f,
                hardConfidenceFloor = 0.42f,
                softConfidenceFloor = 0.55f,
                softWeightFloor = 0.52f,
                sessionOutlierMadMultiplier = 2.7f,
            ),
            reference = defaultReferencePolicy(),
            accumulation = balancedAccumulationPolicy(),
            preview = balancedPreviewPolicy(aggressiveness = 3.0f, blackPointLift = 0.030f, midtone = 1.15f),
            performance = defaultPerformancePolicy(previewMaxEdgeNormal = 1440),
        )
    }

    private fun wideFieldAggressive(): DeepSkyPresetProfile {
        return DeepSkyPresetProfile(
            id = DeepSkyPresetId.WideFieldAggressive,
            displayName = "Wide Field Aggressive",
            recommendedShutterSec = 20.0,
            isoRange = DeepSkyIsoRange(1600, 3200),
            apertureSuggestion = "Open the lens up and accept a more aggressive stretch for faster feedback.",
            stackCadenceExpectationSec = 22.0,
            registrationStrictness = "Relaxed",
            rejectionSensitivity = "Lower",
            registration = DeepSkyRegistrationPolicy(
                preferredTransformModel = DeepSkyTransformModel.Affine,
                allowAffineFallback = true,
                maxRotationDeg = 3.0f,
                maxShiftPxAt1024 = 140,
                minStars = 8,
                minMatches = 5,
                minScore = 0.46f,
                minInlierRatio = 0.38f,
                maxResidualPx = 3.1f,
                maxScaleDelta = 0.045f,
                maxShear = 0.026f,
                maxFallbackChainFrames = 3,
                maxReferenceDeviationPxAt1024 = 210,
                maxFallbackRotationDeg = 3.4f,
                maxFallbackTranslationPxAt1024 = 162,
            ),
            quality = DeepSkyQualityPolicy(
                minUsableStars = 6,
                maxMedianFwhmPx = 3.3f,
                maxMedianElongation = 1.70f,
                maxBackgroundFraction = 0.28f,
                maxNoiseFraction = 0.075f,
                maxSaturationFraction = 0.028f,
                hardConfidenceFloor = 0.36f,
                softConfidenceFloor = 0.48f,
                softWeightFloor = 0.42f,
                sessionOutlierMadMultiplier = 3.0f,
            ),
            reference = defaultReferencePolicy(),
            accumulation = aggressiveAccumulationPolicy(),
            preview = aggressivePreviewPolicy(aggressiveness = 4.1f, blackPointLift = 0.020f, midtone = 1.30f),
            performance = defaultPerformancePolicy(previewMaxEdgeNormal = 1440),
        )
    }

    private fun midTeleConservative(): DeepSkyPresetProfile {
        return DeepSkyPresetProfile(
            id = DeepSkyPresetId.MidTeleConservative,
            displayName = "Mid Tele Conservative",
            recommendedShutterSec = 8.0,
            isoRange = DeepSkyIsoRange(800, 1600),
            apertureSuggestion = "Keep subs shorter to reduce drift and make star matching easier.",
            stackCadenceExpectationSec = 11.0,
            registrationStrictness = "Strict",
            rejectionSensitivity = "High",
            registration = DeepSkyRegistrationPolicy(
                preferredTransformModel = DeepSkyTransformModel.Rigid,
                allowAffineFallback = true,
                maxRotationDeg = 1.2f,
                maxShiftPxAt1024 = 70,
                minStars = 9,
                minMatches = 6,
                minScore = 0.58f,
                minInlierRatio = 0.52f,
                maxResidualPx = 2.1f,
                maxScaleDelta = 0.018f,
                maxShear = 0.012f,
                maxFallbackChainFrames = 2,
                maxReferenceDeviationPxAt1024 = 108,
                maxFallbackRotationDeg = 1.6f,
                maxFallbackTranslationPxAt1024 = 84,
            ),
            quality = DeepSkyQualityPolicy(
                minUsableStars = 7,
                maxMedianFwhmPx = 2.6f,
                maxMedianElongation = 1.35f,
                maxBackgroundFraction = 0.18f,
                maxNoiseFraction = 0.048f,
                maxSaturationFraction = 0.012f,
                hardConfidenceFloor = 0.48f,
                softConfidenceFloor = 0.60f,
                softWeightFloor = 0.55f,
                sessionOutlierMadMultiplier = 2.5f,
            ),
            reference = defaultReferencePolicy(),
            accumulation = balancedAccumulationPolicy(),
            preview = balancedPreviewPolicy(aggressiveness = 2.7f, blackPointLift = 0.035f, midtone = 1.08f),
            performance = defaultPerformancePolicy(previewMaxEdgeNormal = 1320),
        )
    }

    private fun midTeleBalanced(): DeepSkyPresetProfile {
        return DeepSkyPresetProfile(
            id = DeepSkyPresetId.MidTeleBalanced,
            displayName = "Mid Tele Balanced",
            recommendedShutterSec = 12.0,
            isoRange = DeepSkyIsoRange(1600, 3200),
            apertureSuggestion = "Use moderate subs when the lens is fast enough and the field is still easy to register.",
            stackCadenceExpectationSec = 15.0,
            registrationStrictness = "Balanced",
            rejectionSensitivity = "Medium",
            registration = DeepSkyRegistrationPolicy(
                preferredTransformModel = DeepSkyTransformModel.Rigid,
                allowAffineFallback = true,
                maxRotationDeg = 1.8f,
                maxShiftPxAt1024 = 95,
                minStars = 8,
                minMatches = 5,
                minScore = 0.52f,
                minInlierRatio = 0.46f,
                maxResidualPx = 2.4f,
                maxScaleDelta = 0.024f,
                maxShear = 0.016f,
                maxFallbackChainFrames = 3,
                maxReferenceDeviationPxAt1024 = 132,
                maxFallbackRotationDeg = 2.2f,
                maxFallbackTranslationPxAt1024 = 116,
            ),
            quality = DeepSkyQualityPolicy(
                minUsableStars = 6,
                maxMedianFwhmPx = 2.9f,
                maxMedianElongation = 1.45f,
                maxBackgroundFraction = 0.22f,
                maxNoiseFraction = 0.058f,
                maxSaturationFraction = 0.017f,
                hardConfidenceFloor = 0.42f,
                softConfidenceFloor = 0.55f,
                softWeightFloor = 0.50f,
                sessionOutlierMadMultiplier = 2.7f,
            ),
            reference = defaultReferencePolicy(),
            accumulation = balancedAccumulationPolicy(),
            preview = balancedPreviewPolicy(aggressiveness = 3.2f, blackPointLift = 0.028f, midtone = 1.18f),
            performance = defaultPerformancePolicy(previewMaxEdgeNormal = 1320),
        )
    }

    private fun longTeleSafe(): DeepSkyPresetProfile {
        return DeepSkyPresetProfile(
            id = DeepSkyPresetId.LongTeleSafe,
            displayName = "Long Tele Safe",
            recommendedShutterSec = 5.0,
            isoRange = DeepSkyIsoRange(800, 1600),
            apertureSuggestion = "Favor shorter exposures because fixed-tripod drift becomes obvious quickly at long focal lengths.",
            stackCadenceExpectationSec = 8.0,
            registrationStrictness = "Very Strict",
            rejectionSensitivity = "High",
            registration = DeepSkyRegistrationPolicy(
                preferredTransformModel = DeepSkyTransformModel.Rigid,
                allowAffineFallback = false,
                maxRotationDeg = 0.8f,
                maxShiftPxAt1024 = 48,
                minStars = 6,
                minMatches = 4,
                minScore = 0.62f,
                minInlierRatio = 0.58f,
                maxResidualPx = 1.8f,
                maxScaleDelta = 0.010f,
                maxShear = 0.008f,
                maxFallbackChainFrames = 2,
                maxReferenceDeviationPxAt1024 = 72,
                maxFallbackRotationDeg = 1.0f,
                maxFallbackTranslationPxAt1024 = 56,
            ),
            quality = DeepSkyQualityPolicy(
                minUsableStars = 5,
                maxMedianFwhmPx = 2.4f,
                maxMedianElongation = 1.24f,
                maxBackgroundFraction = 0.16f,
                maxNoiseFraction = 0.042f,
                maxSaturationFraction = 0.010f,
                hardConfidenceFloor = 0.54f,
                softConfidenceFloor = 0.66f,
                softWeightFloor = 0.58f,
                sessionOutlierMadMultiplier = 2.4f,
            ),
            reference = defaultReferencePolicy(),
            accumulation = balancedAccumulationPolicy(),
            preview = balancedPreviewPolicy(aggressiveness = 2.4f, blackPointLift = 0.040f, midtone = 1.05f),
            performance = defaultPerformancePolicy(previewMaxEdgeNormal = 1180),
        )
    }

    private fun longTeleAggressive(): DeepSkyPresetProfile {
        return DeepSkyPresetProfile(
            id = DeepSkyPresetId.LongTeleAggressive,
            displayName = "Long Tele Aggressive",
            recommendedShutterSec = 8.0,
            isoRange = DeepSkyIsoRange(1600, 3200),
            apertureSuggestion = "Use only when the lens is fast and framing is stable enough to tolerate looser registration.",
            stackCadenceExpectationSec = 11.0,
            registrationStrictness = "Balanced",
            rejectionSensitivity = "Medium",
            registration = DeepSkyRegistrationPolicy(
                preferredTransformModel = DeepSkyTransformModel.Rigid,
                allowAffineFallback = false,
                maxRotationDeg = 1.1f,
                maxShiftPxAt1024 = 64,
                minStars = 5,
                minMatches = 4,
                minScore = 0.54f,
                minInlierRatio = 0.50f,
                maxResidualPx = 2.0f,
                maxScaleDelta = 0.012f,
                maxShear = 0.010f,
                maxFallbackChainFrames = 2,
                maxReferenceDeviationPxAt1024 = 88,
                maxFallbackRotationDeg = 1.4f,
                maxFallbackTranslationPxAt1024 = 76,
            ),
            quality = DeepSkyQualityPolicy(
                minUsableStars = 4,
                maxMedianFwhmPx = 2.7f,
                maxMedianElongation = 1.30f,
                maxBackgroundFraction = 0.19f,
                maxNoiseFraction = 0.050f,
                maxSaturationFraction = 0.014f,
                hardConfidenceFloor = 0.46f,
                softConfidenceFloor = 0.58f,
                softWeightFloor = 0.52f,
                sessionOutlierMadMultiplier = 2.6f,
            ),
            reference = defaultReferencePolicy(),
            accumulation = aggressiveAccumulationPolicy(),
            preview = aggressivePreviewPolicy(aggressiveness = 3.3f, blackPointLift = 0.030f, midtone = 1.16f),
            performance = defaultPerformancePolicy(previewMaxEdgeNormal = 1180),
        )
    }

    private fun defaultReferencePolicy(): DeepSkyReferencePolicy {
        return DeepSkyReferencePolicy(
            warmupAcceptedFrames = 5,
            earlyRebaseAcceptedFrames = 12,
            earlyRebaseImprovementFraction = 0.12f,
            driftWindowFrames = 6,
            driftSuccessFloor = 0.50f,
            driftRebaseImprovementFraction = 0.15f,
        )
    }

    private fun balancedAccumulationPolicy(): DeepSkyAccumulationPolicy {
        return DeepSkyAccumulationPolicy(
            previewMode = StackAccumulationMode.WinsorizedMean,
            fullResPreferredMode = StackAccumulationMode.WinsorizedMean,
            previewWinsorStartFrame = 4,
            winsorSigmaMultiplier = 2.6f,
            softAcceptWeightMin = 0.35f,
            softAcceptWeightMax = 0.80f,
            fullAcceptWeightMin = 0.85f,
            fullAcceptWeightMax = 1.15f,
        )
    }

    private fun aggressiveAccumulationPolicy(): DeepSkyAccumulationPolicy {
        return DeepSkyAccumulationPolicy(
            previewMode = StackAccumulationMode.WinsorizedMean,
            fullResPreferredMode = StackAccumulationMode.WinsorizedMean,
            previewWinsorStartFrame = 4,
            winsorSigmaMultiplier = 2.9f,
            softAcceptWeightMin = 0.35f,
            softAcceptWeightMax = 0.75f,
            fullAcceptWeightMin = 0.82f,
            fullAcceptWeightMax = 1.12f,
        )
    }

    private fun balancedPreviewPolicy(
        aggressiveness: Float,
        blackPointLift: Float,
        midtone: Float,
    ): DeepSkyPreviewPolicy {
        return DeepSkyPreviewPolicy(
            stretch = DeepSkyPreviewStretchProfile(
                mode = DeepSkyStretchMode.Arcsinh,
                aggressiveness = aggressiveness,
                blackPointLift = blackPointLift,
                midtone = midtone,
            ),
            maxColorBalanceShift = 0.15f,
            blackRiseAlpha = 0.22f,
            blackFallAlpha = 0.10f,
            whiteRiseAlpha = 0.28f,
            whiteFallAlpha = 0.08f,
            colorRiseAlpha = 0.18f,
            colorFallAlpha = 0.10f,
        )
    }

    private fun aggressivePreviewPolicy(
        aggressiveness: Float,
        blackPointLift: Float,
        midtone: Float,
    ): DeepSkyPreviewPolicy {
        return DeepSkyPreviewPolicy(
            stretch = DeepSkyPreviewStretchProfile(
                mode = DeepSkyStretchMode.Arcsinh,
                aggressiveness = aggressiveness,
                blackPointLift = blackPointLift,
                midtone = midtone,
            ),
            maxColorBalanceShift = 0.15f,
            blackRiseAlpha = 0.26f,
            blackFallAlpha = 0.10f,
            whiteRiseAlpha = 0.32f,
            whiteFallAlpha = 0.08f,
            colorRiseAlpha = 0.20f,
            colorFallAlpha = 0.10f,
        )
    }

    private fun defaultPerformancePolicy(previewMaxEdgeNormal: Int): DeepSkyPerformancePolicy {
        return DeepSkyPerformancePolicy(
            previewMaxEdgeNormal = previewMaxEdgeNormal,
            previewMaxEdgeDegraded = (previewMaxEdgeNormal * 0.72f).toInt(),
            renderEveryAcceptedFramesNormal = 1,
            renderEveryAcceptedFramesThrottled = 2,
            degradeLoadRatio = 0.60f,
            throttleLoadRatio = 0.85f,
            protectionLoadRatio = 0.95f,
            skipQualityEveryOtherWhenThrottled = true,
        )
    }

    private fun trimFloat(value: Float): String = trimDouble(value.toDouble())

    private fun trimDouble(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toInt().toString()
        } else {
            "%.1f".format(value)
        }
    }
}
