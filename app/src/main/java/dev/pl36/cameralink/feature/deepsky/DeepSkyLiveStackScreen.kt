package dev.pl36.cameralink.feature.deepsky

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pl36.cameralink.core.deepsky.DeepSkyLiveStackUiState
import dev.pl36.cameralink.core.deepsky.DeepSkyPerformanceMode
import dev.pl36.cameralink.core.deepsky.DeepSkyPresetId
import dev.pl36.cameralink.core.deepsky.DeepSkyPresetProfile
import dev.pl36.cameralink.core.deepsky.DeepSkyReferenceStatus
import dev.pl36.cameralink.core.deepsky.DeepSkySessionStatus
import dev.pl36.cameralink.core.deepsky.DeepSkyWorkflowState
import dev.pl36.cameralink.core.model.TetherPhoneImportFormat
import dev.pl36.cameralink.core.model.TetherSaveTarget
import dev.pl36.cameralink.ui.OmCaptureUsbUiState
import dev.pl36.cameralink.ui.theme.AppleBlue
import dev.pl36.cameralink.ui.theme.AppleGreen
import dev.pl36.cameralink.ui.theme.AppleOrange
import dev.pl36.cameralink.ui.theme.AppleRed
import dev.pl36.cameralink.ui.theme.Chalk
import dev.pl36.cameralink.ui.theme.Graphite
import dev.pl36.cameralink.ui.theme.LeicaBorder
import dev.pl36.cameralink.ui.theme.Obsidian

@Composable
fun DeepSkyLiveStackScreen(
    uiState: DeepSkyLiveStackUiState,
    omCaptureUsb: OmCaptureUsbUiState,
    tetherSaveTarget: TetherSaveTarget,
    tetherPhoneImportFormat: TetherPhoneImportFormat,
    onBack: () -> Unit,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
    onResetSession: () -> Unit,
    onSelectPreset: (DeepSkyPresetId?) -> Unit,
    onRefreshUsb: () -> Unit,
    onCaptureAndImport: () -> Unit,
    onImportLatest: () -> Unit,
) {
    DisposableEffect(Unit) {
        onStartSession()
        onDispose { onStopSession() }
    }

    val scrollState = rememberScrollState()
    val workflow = remember(uiState, omCaptureUsb, tetherSaveTarget, tetherPhoneImportFormat) {
        DeepSkyWorkflowPresenter.present(
            uiState = uiState,
            omCaptureUsb = omCaptureUsb,
            tetherSaveTarget = tetherSaveTarget,
            tetherPhoneImportFormat = tetherPhoneImportFormat,
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Obsidian,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = Chalk,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Deep Sky Live Stack",
                        color = Chalk,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = uiState.statusLabel,
                        color = Chalk.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                SessionBadge(status = uiState.sessionStatus)
            }

            WorkflowCard(workflow = workflow)

            PreviewCard(
                uiState = uiState,
                statusLabel = workflow.headline,
            )

            ControlCard(
                workflow = workflow,
                omCaptureUsb = omCaptureUsb,
                onRefreshUsb = onRefreshUsb,
                onCaptureAndImport = onCaptureAndImport,
                onImportLatest = onImportLatest,
                onResetSession = onResetSession,
            )

            StatsCard(uiState = uiState)

            PresetCard(
                uiState = uiState,
                workflow = workflow,
                onSelectPreset = onSelectPreset,
            )
        }
    }
}

@Composable
private fun WorkflowCard(
    workflow: DeepSkyWorkflowPresentation,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Graphite.copy(alpha = 0.96f),
        border = androidx.compose.foundation.BorderStroke(1.dp, LeicaBorder),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = workflow.headline,
                color = Chalk,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = workflow.subheadline,
                color = Chalk.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall,
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(workflow.chips, key = { "${it.label}-${it.value}" }) { chip ->
                    WorkflowChip(chip = chip)
                }
            }
        }
    }
}

@Composable
private fun WorkflowChip(
    chip: DeepSkyStatusChipModel,
) {
    val accent = when (chip.tone) {
        DeepSkyChipTone.Neutral -> Chalk.copy(alpha = 0.48f)
        DeepSkyChipTone.Active -> AppleBlue
        DeepSkyChipTone.Good -> AppleGreen
        DeepSkyChipTone.Warning -> AppleOrange
        DeepSkyChipTone.Error -> AppleRed
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.18f),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.65f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = chip.label,
                color = accent,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = chip.value,
                color = Chalk,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun PreviewCard(
    uiState: DeepSkyLiveStackUiState,
    statusLabel: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Graphite.copy(alpha = 0.96f),
        border = androidx.compose.foundation.BorderStroke(1.dp, LeicaBorder),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (uiState.previewBitmap != null) {
                Image(
                    bitmap = uiState.previewBitmap.asImageBitmap(),
                    contentDescription = "Deep Sky stack preview",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = AppleBlue,
                        modifier = Modifier.size(42.dp),
                    )
                    Text(
                        text = statusLabel,
                        color = Chalk.copy(alpha = 0.76f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PreviewIndicatorRow(uiState)
                uiState.activeDiagnosticMessage?.takeIf { it.isNotBlank() }?.let { message ->
                    Surface(
                        color = Color.Black.copy(alpha = 0.50f),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, LeicaBorder),
                    ) {
                        Text(
                            text = message,
                            color = Chalk,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlCard(
    workflow: DeepSkyWorkflowPresentation,
    omCaptureUsb: OmCaptureUsbUiState,
    onRefreshUsb: () -> Unit,
    onCaptureAndImport: () -> Unit,
    onImportLatest: () -> Unit,
    onResetSession: () -> Unit,
) {
    val usbReady = omCaptureUsb.summary != null
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Graphite.copy(alpha = 0.94f),
        border = androidx.compose.foundation.BorderStroke(1.dp, LeicaBorder),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Actions",
                color = Chalk,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ActionButton(
                    label = if (usbReady) "Reconnect USB" else "Connect USB",
                    icon = Icons.Rounded.Usb,
                    enabled = !omCaptureUsb.isBusy,
                    modifier = Modifier.weight(1f),
                    onClick = onRefreshUsb,
                )
                ActionButton(
                    label = workflow.resetLabel,
                    icon = Icons.Rounded.Refresh,
                    enabled = true,
                    modifier = Modifier.weight(1f),
                    onClick = onResetSession,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ActionButton(
                    label = workflow.captureLabel,
                    icon = Icons.Rounded.AutoAwesome,
                    enabled = usbReady && !omCaptureUsb.isBusy,
                    modifier = Modifier.weight(1f),
                    onClick = onCaptureAndImport,
                )
                ActionButton(
                    label = workflow.importLabel,
                    icon = Icons.Rounded.Downloading,
                    enabled = usbReady && !omCaptureUsb.isBusy,
                    modifier = Modifier.weight(1f),
                    onClick = onImportLatest,
                )
            }
        }
    }
}

@Composable
private fun StatsCard(
    uiState: DeepSkyLiveStackUiState,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Graphite.copy(alpha = 0.94f),
        border = androidx.compose.foundation.BorderStroke(1.dp, LeicaBorder),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Session",
                color = Chalk,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatCell("Accepted", uiState.frameCount.toString(), Modifier.weight(1f))
                StatCell("Soft", uiState.softAcceptedCount.toString(), Modifier.weight(1f))
                StatCell("Rejected", uiState.rejectedCount.toString(), Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatCell("Exposure", formatExposure(uiState.totalExposureSec), Modifier.weight(1f))
                StatCell("Effective", formatExposure(uiState.effectiveIntegrationSec), Modifier.weight(1f))
                StatCell("Accept %", formatPercent(uiState.rollingAcceptanceRate), Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatCell("Score", uiState.lastRegistrationScore?.let { "%.3f".format(it) } ?: "--", Modifier.weight(1f))
                StatCell("Stars", uiState.detectedStarCount.toString(), Modifier.weight(1f))
                StatCell("Matches", uiState.matchedStarCount.toString(), Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatCell("Residual", uiState.lastRegistrationMetrics?.residualPx?.let { "%.2f px".format(it) } ?: "--", Modifier.weight(1f))
                StatCell("Inliers", uiState.lastRegistrationMetrics?.inlierRatio?.let { formatPercent(it) } ?: "--", Modifier.weight(1f))
                StatCell("Total Ms", uiState.lastStageTimings?.totalMs?.toString() ?: "--", Modifier.weight(1f))
            }
            uiState.activeDiagnosticMessage?.let { reason ->
                Text(
                    text = reason,
                    color = if (uiState.workflowState == DeepSkyWorkflowState.StackingHealthy) AppleGreen else AppleOrange,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PresetCard(
    uiState: DeepSkyLiveStackUiState,
    workflow: DeepSkyWorkflowPresentation,
    onSelectPreset: (DeepSkyPresetId?) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Graphite.copy(alpha = 0.94f),
        border = androidx.compose.foundation.BorderStroke(1.dp, LeicaBorder),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Preset",
                color = Chalk,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = uiState.selectedPreset?.displayName ?: "Waiting for metadata",
                color = AppleBlue,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    PresetChip(
                        label = "Auto",
                        selected = uiState.manualPresetOverride == null,
                        onClick = { onSelectPreset(null) },
                    )
                }
                items(uiState.availablePresets, key = { it.id.name }) { preset ->
                    PresetChip(
                        label = preset.displayName,
                        selected = uiState.manualPresetOverride == preset.id,
                        onClick = { onSelectPreset(preset.id) },
                    )
                }
            }
            uiState.selectedPreset?.let { selectedPreset ->
                PresetDetail(selectedPreset)
            }
            workflow.recipeTitle?.let {
                Text(
                    text = it,
                    color = AppleGreen,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            workflow.recipeLines.forEach { line ->
                Text(
                    text = line,
                    color = Chalk.copy(alpha = 0.74f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PresetDetail(
    preset: DeepSkyPresetProfile,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "${preset.isoRange.label} | ${preset.recommendedShutterSec.toInt()}s | cadence ${preset.stackCadenceExpectationSec.toInt()}s",
            color = Chalk,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "${preset.registrationStrictness} registration | ${preset.rejectionSensitivity} rejection",
            color = Chalk.copy(alpha = 0.72f),
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            text = "Reference ${preset.reference.warmupAcceptedFrames} frames | ${preset.registration.preferredTransformModel.name.lowercase()} model | render every ${preset.performance.renderEveryAcceptedFramesNormal}",
            color = Chalk.copy(alpha = 0.60f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun PreviewIndicatorRow(
    uiState: DeepSkyLiveStackUiState,
) {
    val indicators = buildList {
        if (uiState.referenceStatus.status == DeepSkyReferenceStatus.WarmingUp) {
            add("Reference Election" to AppleBlue)
        }
        if (uiState.workflowState == DeepSkyWorkflowState.StackingUnstable) {
            add("Alignment Unstable" to AppleOrange)
        }
        if (uiState.lastRejectionKind == dev.pl36.cameralink.core.deepsky.DeepSkyRejectionReason.BackgroundTooBright) {
            add("Bright Sky" to AppleOrange)
        }
        if (uiState.performanceMode == DeepSkyPerformanceMode.DegradedPreview ||
            uiState.performanceMode == DeepSkyPerformanceMode.Throttled
        ) {
            add("Preview Degraded" to AppleOrange)
        }
        if (uiState.workflowState == DeepSkyWorkflowState.MemoryLimited) {
            add("Memory Limited" to AppleRed)
        }
    }
    if (indicators.isEmpty()) {
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        indicators.forEach { (label, color) ->
            Surface(
                color = Color.Black.copy(alpha = 0.45f),
                shape = RoundedCornerShape(999.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.75f)),
            ) {
                Text(
                    text = label,
                    color = color,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.18f))
            .border(1.dp, LeicaBorder, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            color = Chalk.copy(alpha = 0.58f),
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            text = value,
            color = Chalk,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SessionBadge(
    status: DeepSkySessionStatus,
) {
    val color = when (status) {
        DeepSkySessionStatus.Running -> AppleGreen
        DeepSkySessionStatus.Processing -> AppleOrange
        DeepSkySessionStatus.Error -> AppleRed
        DeepSkySessionStatus.Waiting -> AppleBlue
        DeepSkySessionStatus.Stopped,
        DeepSkySessionStatus.Idle,
        -> Chalk.copy(alpha = 0.5f)
    }
    Surface(
        color = color.copy(alpha = 0.16f),
        shape = RoundedCornerShape(999.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.55f)),
    ) {
        Text(
            text = status.name.uppercase(),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text = label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = if (selected) AppleBlue else Chalk.copy(alpha = 0.42f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) AppleBlue.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.18f))
            .border(1.dp, accent, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (selected) Icons.Rounded.AutoAwesome else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                color = accent,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private fun formatExposure(totalExposureSec: Double): String {
    return when {
        totalExposureSec >= 60.0 -> "%.1f min".format(totalExposureSec / 60.0)
        totalExposureSec > 0.0 -> "%.0f s".format(totalExposureSec)
        else -> "--"
    }
}

private fun formatPercent(value: Float): String {
    if (value <= 0f) return "--"
    return "%.0f%%".format(value * 100f)
}
