package dev.pl36.cameralink.feature.deepsky

import dev.pl36.cameralink.core.deepsky.DeepSkyDiagnosticCatalog
import dev.pl36.cameralink.core.deepsky.DeepSkyLiveStackUiState
import dev.pl36.cameralink.core.deepsky.DeepSkyPerformanceMode
import dev.pl36.cameralink.core.deepsky.DeepSkyReferenceStatus
import dev.pl36.cameralink.core.deepsky.DeepSkyWorkflowState
import dev.pl36.cameralink.core.model.TetherPhoneImportFormat
import dev.pl36.cameralink.core.model.TetherSaveTarget
import dev.pl36.cameralink.ui.OmCaptureUsbUiState

enum class DeepSkyChipTone {
    Neutral,
    Active,
    Good,
    Warning,
    Error,
}

data class DeepSkyStatusChipModel(
    val label: String,
    val value: String,
    val tone: DeepSkyChipTone,
)

data class DeepSkyWorkflowPresentation(
    val headline: String,
    val subheadline: String,
    val chips: List<DeepSkyStatusChipModel>,
    val captureLabel: String,
    val importLabel: String,
    val resetLabel: String,
    val recipeTitle: String?,
    val recipeLines: List<String>,
)

object DeepSkyWorkflowPresenter {
    fun present(
        uiState: DeepSkyLiveStackUiState,
        omCaptureUsb: OmCaptureUsbUiState,
        tetherSaveTarget: TetherSaveTarget,
        tetherPhoneImportFormat: TetherPhoneImportFormat,
    ): DeepSkyWorkflowPresentation {
        val headline = when {
            omCaptureUsb.summary == null -> "Connect Camera"
            !tetherSaveTarget.savesToPhone -> "Enable Phone Import"
            uiState.workflowState == DeepSkyWorkflowState.MemoryLimited -> "Memory Limited"
            uiState.workflowState == DeepSkyWorkflowState.ActionRequired -> "Action Required"
            uiState.workflowState == DeepSkyWorkflowState.ElectingReference -> "Electing Reference"
            uiState.workflowState == DeepSkyWorkflowState.StackingHealthy -> "Stack Running"
            uiState.workflowState == DeepSkyWorkflowState.StackingUnstable -> "Alignment Unstable"
            uiState.workflowState == DeepSkyWorkflowState.Degraded -> degradedHeadline(uiState.performanceMode)
            uiState.workflowState == DeepSkyWorkflowState.WaitingForFrames && uiState.frameCount == 0 -> "Capture First Frames"
            uiState.workflowState == DeepSkyWorkflowState.WaitingForFrames -> "Waiting For Next Frame"
            uiState.workflowState == DeepSkyWorkflowState.Stopped -> "Session Stopped"
            else -> "Deep Sky Live Stack"
        }

        val subheadline = when {
            omCaptureUsb.summary == null ->
                "USB/PTP tethering must be active before Deep Sky stacking can ingest imported captures."
            !tetherSaveTarget.savesToPhone ->
                "Deep Sky stacking only consumes files imported to the phone through the existing tether workflow."
            uiState.activeDiagnosticMessage != null ->
                uiState.activeDiagnosticMessage
            uiState.workflowState == DeepSkyWorkflowState.ElectingReference ->
                "The stack anchor is fixed, while the app scores the early accepted frames to elect the strongest long-lived registration reference."
            uiState.workflowState == DeepSkyWorkflowState.StackingHealthy ->
                "Registration, accumulation, and preview tone mapping are stable. Keep the field fixed and continue capturing."
            uiState.workflowState == DeepSkyWorkflowState.StackingUnstable ->
                "Frames are still arriving, but the last quality or alignment check fell below the healthy target."
            uiState.workflowState == DeepSkyWorkflowState.Degraded ->
                DeepSkyDiagnosticCatalog.describePerformanceMode(uiState.performanceMode)
                    ?: "The session is in a protected performance mode."
            uiState.workflowState == DeepSkyWorkflowState.WaitingForFrames && uiState.frameCount == 0 ->
                "Capture a few clean sub-exposures so the app can lock the stack anchor and elect the best registration reference."
            uiState.workflowState == DeepSkyWorkflowState.WaitingForFrames ->
                "The current stack is ready for the next imported frame."
            uiState.workflowState == DeepSkyWorkflowState.MemoryLimited ->
                "The device cannot safely hold the required working buffers for the current stack configuration."
            else -> uiState.statusLabel
        }

        val chips = buildList {
            add(
                DeepSkyStatusChipModel(
                    label = "USB",
                    value = omCaptureUsb.summary?.model ?: "OFF",
                    tone = if (omCaptureUsb.summary != null) DeepSkyChipTone.Good else DeepSkyChipTone.Error,
                ),
            )
            add(
                DeepSkyStatusChipModel(
                    label = "IMPORT",
                    value = if (tetherSaveTarget.savesToPhone) "PHONE" else "SD ONLY",
                    tone = if (tetherSaveTarget.savesToPhone) DeepSkyChipTone.Good else DeepSkyChipTone.Error,
                ),
            )
            add(
                DeepSkyStatusChipModel(
                    label = "FORMAT",
                    value = when (tetherPhoneImportFormat) {
                        TetherPhoneImportFormat.JpegAndRaw -> "JPEG+RAW"
                        TetherPhoneImportFormat.RawOnly -> "RAW"
                        TetherPhoneImportFormat.JpegOnly -> "JPEG"
                    },
                    tone = if (tetherPhoneImportFormat == TetherPhoneImportFormat.JpegAndRaw) {
                        DeepSkyChipTone.Good
                    } else {
                        DeepSkyChipTone.Warning
                    },
                ),
            )
            add(
                DeepSkyStatusChipModel(
                    label = "REF",
                    value = referenceValue(uiState),
                    tone = referenceTone(uiState),
                ),
            )
            add(
                DeepSkyStatusChipModel(
                    label = "ALIGN",
                    value = alignmentValue(uiState),
                    tone = alignmentTone(uiState),
                ),
            )
            add(
                DeepSkyStatusChipModel(
                    label = "PERF",
                    value = performanceValue(uiState.performanceMode),
                    tone = performanceTone(uiState.performanceMode),
                ),
            )
            add(
                DeepSkyStatusChipModel(
                    label = "PRESET",
                    value = uiState.selectedPreset?.displayName ?: "AUTO",
                    tone = DeepSkyChipTone.Neutral,
                ),
            )
        }

        val recipeLines = uiState.selectedPreset?.let { preset ->
            listOf(
                "${preset.recommendedShutterSec.toInt()}s | ${preset.isoRange.label} | ${preset.apertureSuggestion}",
                "Cadence ${preset.stackCadenceExpectationSec.toInt()}s | ${preset.registrationStrictness} registration | ${preset.rejectionSensitivity} rejection",
                "Model ${preset.registration.preferredTransformModel.name.lowercase()} | preview ${preset.accumulation.previewMode.name.lowercase()} | full ${preset.accumulation.fullResPreferredMode.name.lowercase()}",
            )
        }.orEmpty()

        return DeepSkyWorkflowPresentation(
            headline = headline,
            subheadline = subheadline,
            chips = chips,
            captureLabel = if (uiState.frameCount == 0) "Capture First Frame" else "Capture Next Frame",
            importLabel = if (uiState.frameCount == 0) "Import First Frame" else "Import Latest",
            resetLabel = if (uiState.frameCount > 0 || uiState.lastDecision != null) "Reset Stack" else "Reset",
            recipeTitle = uiState.selectedPreset?.displayName,
            recipeLines = recipeLines,
        )
    }

    private fun degradedHeadline(mode: DeepSkyPerformanceMode): String {
        return when (mode) {
            DeepSkyPerformanceMode.Normal -> "Degraded"
            DeepSkyPerformanceMode.DegradedPreview -> "Preview Degraded"
            DeepSkyPerformanceMode.Throttled -> "Preview Throttled"
            DeepSkyPerformanceMode.ProtectionPaused -> "Protection Pause"
        }
    }

    private fun referenceValue(uiState: DeepSkyLiveStackUiState): String {
        return when (uiState.referenceStatus.status) {
            DeepSkyReferenceStatus.None -> "OPEN"
            DeepSkyReferenceStatus.WarmingUp -> "${uiState.referenceStatus.warmupAcceptedFrames}/${uiState.referenceStatus.targetWarmupFrames}"
            DeepSkyReferenceStatus.Elected -> "ELECTED"
            DeepSkyReferenceStatus.Rebased -> "REBASED"
            DeepSkyReferenceStatus.Drifting -> "DRIFT"
        }
    }

    private fun referenceTone(uiState: DeepSkyLiveStackUiState): DeepSkyChipTone {
        return when (uiState.referenceStatus.status) {
            DeepSkyReferenceStatus.None -> DeepSkyChipTone.Active
            DeepSkyReferenceStatus.WarmingUp -> DeepSkyChipTone.Active
            DeepSkyReferenceStatus.Elected -> DeepSkyChipTone.Good
            DeepSkyReferenceStatus.Rebased -> DeepSkyChipTone.Warning
            DeepSkyReferenceStatus.Drifting -> DeepSkyChipTone.Error
        }
    }

    private fun alignmentValue(uiState: DeepSkyLiveStackUiState): String {
        val metrics = uiState.lastRegistrationMetrics
        return when {
            uiState.frameCount == 0 -> "WAIT"
            uiState.workflowState == DeepSkyWorkflowState.StackingUnstable -> "WATCH"
            uiState.workflowState == DeepSkyWorkflowState.ActionRequired -> "CHECK"
            metrics == null -> "READY"
            metrics.confidenceScore >= 0.78f && metrics.inlierRatio >= 0.55f -> "STABLE"
            metrics.confidenceScore >= 0.60f -> "ACTIVE"
            else -> "LOW"
        }
    }

    private fun alignmentTone(uiState: DeepSkyLiveStackUiState): DeepSkyChipTone {
        val metrics = uiState.lastRegistrationMetrics
        return when {
            uiState.frameCount == 0 -> DeepSkyChipTone.Active
            uiState.workflowState == DeepSkyWorkflowState.ActionRequired -> DeepSkyChipTone.Error
            uiState.workflowState == DeepSkyWorkflowState.StackingUnstable -> DeepSkyChipTone.Warning
            metrics == null -> DeepSkyChipTone.Active
            metrics.confidenceScore >= 0.78f && metrics.inlierRatio >= 0.55f -> DeepSkyChipTone.Good
            metrics.confidenceScore >= 0.60f -> DeepSkyChipTone.Active
            else -> DeepSkyChipTone.Warning
        }
    }

    private fun performanceValue(mode: DeepSkyPerformanceMode): String {
        return when (mode) {
            DeepSkyPerformanceMode.Normal -> "NORMAL"
            DeepSkyPerformanceMode.DegradedPreview -> "LOWER"
            DeepSkyPerformanceMode.Throttled -> "SLOW"
            DeepSkyPerformanceMode.ProtectionPaused -> "PAUSED"
        }
    }

    private fun performanceTone(mode: DeepSkyPerformanceMode): DeepSkyChipTone {
        return when (mode) {
            DeepSkyPerformanceMode.Normal -> DeepSkyChipTone.Good
            DeepSkyPerformanceMode.DegradedPreview -> DeepSkyChipTone.Warning
            DeepSkyPerformanceMode.Throttled -> DeepSkyChipTone.Warning
            DeepSkyPerformanceMode.ProtectionPaused -> DeepSkyChipTone.Error
        }
    }
}
