package dev.dblink.feature.deepsky

import dev.dblink.core.deepsky.DeepSkyFrameDecision
import dev.dblink.core.deepsky.DeepSkyFrameDecisionKind
import dev.dblink.core.deepsky.DeepSkyLiveStackUiState
import dev.dblink.core.deepsky.DeepSkyPerformanceMode
import dev.dblink.core.deepsky.DeepSkyPresetEngine
import dev.dblink.core.deepsky.DeepSkyReferenceStatus
import dev.dblink.core.deepsky.DeepSkyReferenceStatusSummary
import dev.dblink.core.deepsky.FrameRegistrationMetrics
import dev.dblink.core.deepsky.DeepSkySessionStatus
import dev.dblink.core.deepsky.DeepSkyWorkflowState
import dev.dblink.core.model.TetherPhoneImportFormat
import dev.dblink.core.model.TetherSaveTarget
import dev.dblink.core.usb.OmCaptureUsbOperationState
import dev.dblink.core.usb.OmCaptureUsbSessionSummary
import dev.dblink.ui.OmCaptureUsbUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSkyWorkflowPresenterTest {
    private val presetEngine = DeepSkyPresetEngine()

    @Test
    fun `shows connection-first state when USB tether is unavailable`() {
        val presentation = DeepSkyWorkflowPresenter.present(
            uiState = DeepSkyLiveStackUiState(),
            omCaptureUsb = OmCaptureUsbUiState(),
            tetherSaveTarget = TetherSaveTarget.SdAndPhone,
            tetherPhoneImportFormat = TetherPhoneImportFormat.JpegAndRaw,
        )

        assertEquals("Connect Camera", presentation.headline)
        assertEquals("Capture First Frame", presentation.captureLabel)
        assertEquals(DeepSkyChipTone.Error, presentation.chips.first { it.label == "USB" }.tone)
    }

    @Test
    fun `surfaces phone import as the primary blocker when sd only is selected`() {
        val presentation = DeepSkyWorkflowPresenter.present(
            uiState = DeepSkyLiveStackUiState(),
            omCaptureUsb = connectedUsbState(),
            tetherSaveTarget = TetherSaveTarget.SdCard,
            tetherPhoneImportFormat = TetherPhoneImportFormat.JpegAndRaw,
        )

        assertEquals("Enable Phone Import", presentation.headline)
        assertEquals("SD ONLY", presentation.chips.first { it.label == "IMPORT" }.value)
        assertEquals(DeepSkyChipTone.Error, presentation.chips.first { it.label == "IMPORT" }.tone)
    }

    @Test
    fun `shows reference election state during warmup`() {
        val presentation = DeepSkyWorkflowPresenter.present(
            uiState = DeepSkyLiveStackUiState(
                frameCount = 2,
                sessionStatus = DeepSkySessionStatus.Running,
                workflowState = DeepSkyWorkflowState.ElectingReference,
                referenceStatus = DeepSkyReferenceStatusSummary(
                    status = DeepSkyReferenceStatus.WarmingUp,
                    warmupAcceptedFrames = 2,
                    targetWarmupFrames = 5,
                ),
                selectedPreset = presetEngine.profile(dev.dblink.core.deepsky.DeepSkyPresetId.WideFieldBalanced),
            ),
            omCaptureUsb = connectedUsbState(),
            tetherSaveTarget = TetherSaveTarget.SdAndPhone,
            tetherPhoneImportFormat = TetherPhoneImportFormat.JpegAndRaw,
        )

        assertEquals("Electing Reference", presentation.headline)
        assertEquals("2/5", presentation.chips.first { it.label == "REF" }.value)
        assertEquals("Capture Next Frame", presentation.captureLabel)
        assertTrue(presentation.recipeLines.first().contains("15s"))
    }

    @Test
    fun `surfaces unstable stacking and diagnostics without tutor copy`() {
        val presentation = DeepSkyWorkflowPresenter.present(
            uiState = DeepSkyLiveStackUiState(
                frameCount = 3,
                sessionStatus = DeepSkySessionStatus.Running,
                workflowState = DeepSkyWorkflowState.StackingUnstable,
                performanceMode = DeepSkyPerformanceMode.Normal,
                activeDiagnosticMessage = "Alignment unstable. Check tripod stability or reduce focal length.",
                lastDecision = DeepSkyFrameDecision(
                    kind = DeepSkyFrameDecisionKind.SoftAccept,
                    explanation = "Frame downweighted",
                    weightMultiplier = 0.55f,
                ),
                selectedPreset = presetEngine.profile(dev.dblink.core.deepsky.DeepSkyPresetId.MidTeleBalanced),
            ),
            omCaptureUsb = connectedUsbState(),
            tetherSaveTarget = TetherSaveTarget.SdAndPhone,
            tetherPhoneImportFormat = TetherPhoneImportFormat.JpegAndRaw,
        )

        assertEquals("Alignment Unstable", presentation.headline)
        assertEquals("WATCH", presentation.chips.first { it.label == "ALIGN" }.value)
        assertEquals(DeepSkyChipTone.Warning, presentation.chips.first { it.label == "ALIGN" }.tone)
    }

    @Test
    fun `shows degraded performance mode when preview is throttled`() {
        val presentation = DeepSkyWorkflowPresenter.present(
            uiState = DeepSkyLiveStackUiState(
                frameCount = 7,
                sessionStatus = DeepSkySessionStatus.Running,
                workflowState = DeepSkyWorkflowState.Degraded,
                performanceMode = DeepSkyPerformanceMode.Throttled,
                activeDiagnosticMessage = "Preview throttled to protect device temperature.",
                selectedPreset = presetEngine.profile(dev.dblink.core.deepsky.DeepSkyPresetId.LongTeleSafe),
            ),
            omCaptureUsb = connectedUsbState(),
            tetherSaveTarget = TetherSaveTarget.SdAndPhone,
            tetherPhoneImportFormat = TetherPhoneImportFormat.JpegAndRaw,
        )

        assertEquals("Preview Throttled", presentation.headline)
        assertEquals("SLOW", presentation.chips.first { it.label == "PERF" }.value)
        assertEquals(DeepSkyChipTone.Warning, presentation.chips.first { it.label == "PERF" }.tone)
    }

    @Test
    fun `shows stable running state for healthy sessions`() {
        val presentation = DeepSkyWorkflowPresenter.present(
            uiState = DeepSkyLiveStackUiState(
                frameCount = 8,
                sessionStatus = DeepSkySessionStatus.Running,
                workflowState = DeepSkyWorkflowState.StackingHealthy,
                selectedPreset = presetEngine.profile(dev.dblink.core.deepsky.DeepSkyPresetId.LongTeleSafe),
                referenceStatus = DeepSkyReferenceStatusSummary(status = DeepSkyReferenceStatus.Elected),
                lastRegistrationMetrics = FrameRegistrationMetrics(
                    matchedStars = 8,
                    inlierRatio = 0.66f,
                    confidenceScore = 0.82f,
                ),
            ),
            omCaptureUsb = connectedUsbState(),
            tetherSaveTarget = TetherSaveTarget.SdAndPhone,
            tetherPhoneImportFormat = TetherPhoneImportFormat.JpegAndRaw,
        )

        assertEquals("Stack Running", presentation.headline)
        assertEquals("STABLE", presentation.chips.first { it.label == "ALIGN" }.value)
        assertEquals("Capture Next Frame", presentation.captureLabel)
        assertTrue(presentation.recipeLines.last().contains("model", ignoreCase = true))
    }

    private fun connectedUsbState(): OmCaptureUsbUiState {
        return OmCaptureUsbUiState(
            operationState = OmCaptureUsbOperationState.Idle,
            statusLabel = "OM camera ready over USB/PTP",
            isBusy = false,
            summary = OmCaptureUsbSessionSummary(
                deviceLabel = "OM-1",
                manufacturer = "OM SYSTEM",
                model = "OM-1",
                firmwareVersion = "1.0",
                serialNumber = "123",
                storageIds = listOf(1),
                supportsCapture = true,
                supportsLiveView = true,
                supportsPropertyControl = true,
                supportedOperationCount = 120,
            ),
        )
    }
}
