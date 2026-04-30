package dev.dblink.feature.omcapture

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dblink.core.model.ActivePropertyPicker
import dev.dblink.core.model.CameraExposureMode
import dev.dblink.core.model.DriveMode
import dev.dblink.core.model.RemoteRuntimeState
import dev.dblink.core.model.TetherPhoneImportFormat
import dev.dblink.core.model.TetherSaveTarget
import dev.dblink.core.model.TimerMode
import dev.dblink.core.omcapture.OmCaptureAction
import dev.dblink.core.omcapture.OmCaptureFeatureDescriptor
import dev.dblink.core.omcapture.OmCaptureImplementationStatus
import dev.dblink.core.omcapture.OmCaptureSection
import dev.dblink.core.omcapture.OmCaptureStudioUiState
import dev.dblink.core.protocol.PropertyFormatter
import dev.dblink.core.ui.GlassCard
import dev.dblink.core.ui.KeyValueRow
import dev.dblink.core.usb.OmCaptureUsbManager
import dev.dblink.core.usb.OmCaptureUsbOperationState
import dev.dblink.core.usb.PtpConstants
import dev.dblink.core.usb.formatOlympusExposureMode
import dev.dblink.ui.OmCaptureUsbUiState
import dev.dblink.ui.theme.AppleBlue
import dev.dblink.ui.theme.AppleGreen
import dev.dblink.ui.theme.AppleOrange
import dev.dblink.ui.theme.AppleRed
import dev.dblink.ui.theme.Chalk
import dev.dblink.ui.theme.Iron
import dev.dblink.ui.theme.LeicaBorder
import dev.dblink.ui.theme.LeicaSteel

@Composable
fun OmCaptureScreen(
    studioState: OmCaptureStudioUiState,
    omCaptureUsb: OmCaptureUsbUiState,
    remoteRuntime: RemoteRuntimeState,
    liveViewFrame: Bitmap?,
    cameraProperties: OmCaptureUsbManager.CameraPropertiesSnapshot = OmCaptureUsbManager.CameraPropertiesSnapshot(),
    onDispatchAction: (OmCaptureAction) -> Unit,
) {
    val isUsbConnected = omCaptureUsb.summary != null
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
    ) {
        // Live View + Capture
        if (liveViewFrame != null) {
            item {
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Image(
                            bitmap = liveViewFrame.asImageBitmap(),
                            contentDescription = "Live view",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.FillWidth,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // MF Drive buttons
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CompactButton("MF-") { onDispatchAction(OmCaptureAction.ManualFocusDrive(-100)) }
                                CompactButton("MF--") { onDispatchAction(OmCaptureAction.ManualFocusDrive(-500)) }
                            }
                            // Shutter button
                            Button(
                                onClick = { onDispatchAction(OmCaptureAction.CaptureWhileLiveView) },
                                colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                                shape = CircleShape,
                                modifier = Modifier.size(64.dp),
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .border(3.dp, Chalk, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {}
                            }
                            // MF Drive buttons
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CompactButton("MF++") { onDispatchAction(OmCaptureAction.ManualFocusDrive(500)) }
                                CompactButton("MF+") { onDispatchAction(OmCaptureAction.ManualFocusDrive(100)) }
                            }
                        }
                    }
                }
            }
        }

        // USB Camera Properties
        if (isUsbConnected) {
            item {
                UsbCameraControlPanel(
                    cameraProperties = cameraProperties,
                    omCaptureUsb = omCaptureUsb,
                    liveViewActive = studioState.liveViewActive,
                    onDispatchAction = onDispatchAction,
                )
            }
        }

        // Connection & Actions
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = studioState.connectionLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Chalk,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Button(
                            onClick = { onDispatchAction(OmCaptureAction.RefreshUsbConnection) },
                            colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                            shape = RoundedCornerShape(18.dp),
                        ) { Text("Connect") }
                        Button(
                            onClick = { onDispatchAction(OmCaptureAction.ToggleUsbLiveView) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (studioState.liveViewActive) AppleRed else AppleBlue,
                            ),
                            shape = RoundedCornerShape(18.dp),
                        ) { Text(if (studioState.liveViewActive) "Stop LV" else "Live View") }
                        OutlinedButton(
                            onClick = { onDispatchAction(OmCaptureAction.CaptureImport) },
                            enabled = !omCaptureUsb.isBusy,
                            border = androidx.compose.foundation.BorderStroke(1.dp, LeicaBorder),
                            shape = RoundedCornerShape(18.dp),
                        ) { Text("Capture and Import", color = Chalk) }
                    }
                    StatusStrip(
                        usbState = omCaptureUsb.operationState,
                        usbLabel = omCaptureUsb.statusLabel,
                        liveViewActive = remoteRuntime.liveViewActive,
                    )
                }
            }
        }

        // File Save Settings
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ControlGroupLabel("Save Target")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SaveTargetChip("SD", studioState.tetherSaveTarget == TetherSaveTarget.SdCard) {
                            onDispatchAction(OmCaptureAction.SetSaveTarget(TetherSaveTarget.SdCard))
                        }
                        SaveTargetChip("SD + Phone", studioState.tetherSaveTarget == TetherSaveTarget.SdAndPhone) {
                            onDispatchAction(OmCaptureAction.SetSaveTarget(TetherSaveTarget.SdAndPhone))
                        }
                        SaveTargetChip("Phone", studioState.tetherSaveTarget == TetherSaveTarget.Phone) {
                            onDispatchAction(OmCaptureAction.SetSaveTarget(TetherSaveTarget.Phone))
                        }
                    }
                    ControlGroupLabel("Import Format")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SaveTargetChip("JPEG", studioState.phoneImportFormat == TetherPhoneImportFormat.JpegOnly) {
                            onDispatchAction(OmCaptureAction.SetPhoneImportFormat(TetherPhoneImportFormat.JpegOnly))
                        }
                        SaveTargetChip("RAW", studioState.phoneImportFormat == TetherPhoneImportFormat.RawOnly) {
                            onDispatchAction(OmCaptureAction.SetPhoneImportFormat(TetherPhoneImportFormat.RawOnly))
                        }
                    }
                }
            }
        }

        // Section Matrix
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        studioState.sectionSummaries.forEach { summary ->
                            SectionChip(
                                title = summary.section.title,
                                detail = "${summary.implementedCount + summary.adaptedCount}/${summary.featureCount}",
                                selected = studioState.selectedSection == summary.section,
                                onClick = { onDispatchAction(OmCaptureAction.SelectSection(summary.section)) },
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionActionPanel(
                studioState = studioState,
                omCaptureUsb = omCaptureUsb,
                remoteRuntime = remoteRuntime,
                liveViewFrame = liveViewFrame,
                onDispatchAction = onDispatchAction,
            )
        }
    }
}

@Composable
private fun UsbCameraControlPanel(
    cameraProperties: OmCaptureUsbManager.CameraPropertiesSnapshot,
    omCaptureUsb: OmCaptureUsbUiState,
    liveViewActive: Boolean,
    onDispatchAction: (OmCaptureAction) -> Unit,
) {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Camera Controls",
                    style = MaterialTheme.typography.titleMedium,
                    color = Chalk,
                    fontWeight = FontWeight.Bold,
                )
                CompactButton("Refresh") {
                    onDispatchAction(OmCaptureAction.RefreshUsbProperties)
                }
            }

            val props = cameraProperties.allProperties()
            if (props.isEmpty()) {
                Text(
                    text = "Tap Refresh to load camera properties via USB/PTP.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Chalk.copy(alpha = 0.6f),
                )
            } else {
                props.forEach { prop ->
                    UsbPropertyRow(
                        prop = prop,
                        onSelect = { value ->
                            onDispatchAction(OmCaptureAction.SetUsbProperty(prop.propCode, value))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun UsbPropertyRow(
    prop: OmCaptureUsbManager.CameraPropertyState,
    onSelect: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = prop.label,
                style = MaterialTheme.typography.labelLarge,
                color = Chalk,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = formatPtpValue(prop.propCode, prop.currentValue),
                style = MaterialTheme.typography.labelLarge,
                color = AppleBlue,
                fontWeight = FontWeight.Bold,
            )
        }
        if (!prop.isReadOnly && prop.allowedValues.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                prop.allowedValues.forEach { value ->
                    val label = formatPtpValue(prop.propCode, value)
                    val selected = value == prop.currentValue
                    Surface(
                        modifier = Modifier.clickable { onSelect(value) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) AppleBlue else Iron,
                        border = if (selected) null else androidx.compose.foundation.BorderStroke(
                            1.dp,
                            LeicaBorder,
                        ),
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selected) Color.White else Chalk.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } else if (prop.isReadOnly) {
            Text(
                text = "Read-only",
                style = MaterialTheme.typography.bodySmall,
                color = LeicaSteel,
            )
        }
    }
}

@Composable
private fun CompactButton(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = Iron,
        border = androidx.compose.foundation.BorderStroke(1.dp, LeicaBorder),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Chalk,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun formatPtpValue(propCode: Int, value: Long): String = when (propCode) {
    PtpConstants.OlympusProp.ShutterSpeed -> formatOlympusShutterSpeed(value)
    PtpConstants.OlympusProp.Aperture -> formatOlympusAperture(value)
    PtpConstants.OlympusProp.ISOSpeed -> formatOlympusIso(value)
    PtpConstants.OlympusProp.ExposureCompensation -> formatOlympusExpComp(value)
    PtpConstants.OlympusProp.WhiteBalance -> formatOlympusWb(value)
    PtpConstants.OlympusProp.FocusMode -> when (value.toInt()) {
        0 -> "S-AF"; 1 -> "C-AF"; 2 -> "MF"; 3 -> "S-AF+MF"; 4 -> "C-AF+TR"; else -> "0x${value.toString(16)}"
    }
    PtpConstants.OlympusProp.MeteringMode -> when (value.toInt()) {
        2 -> "Center"; 3 -> "Spot"; 4 -> "ESP"; 5 -> "Highlight"; else -> "0x${value.toString(16)}"
    }
    PtpConstants.OlympusProp.FlashMode -> when (value.toInt()) {
        0 -> "Off"; 1 -> "Auto"; 2 -> "On"; 3 -> "Red-eye"; 4 -> "Slow"; 5 -> "Fill"; else -> "0x${value.toString(16)}"
    }
    PtpConstants.OlympusProp.DriveMode -> when (value.toInt()) {
        0 -> "Single"; 1 -> "Sequential"; 5 -> "SH"; 6 -> "Silent"; else -> "0x${value.toString(16)}"
    }
    PtpConstants.Prop.ExposureProgramMode,
    PtpConstants.OlympusProp.ExposureMode -> formatOlympusExposureMode(propCode, value)
    PtpConstants.OlympusProp.ImageQuality -> when (value.toInt()) {
        1 -> "RAW"; 2 -> "JPEG"; 3 -> "RAW+JPEG"; else -> "0x${value.toString(16)}"
    }
    else -> "0x${value.toString(16)}"
}

private fun formatOlympusShutterSpeed(value: Long): String {
    val v = value.toInt()
    if (v == 0) return "Auto"
    // Olympus encodes shutter speed * 10 in some models, or as APEX values
    // Common pattern: value is denominator (e.g., 250 = 1/250)
    return if (v >= 10) "1/$v" else "${v}s"
}

private fun formatOlympusAperture(value: Long): String {
    val v = value.toInt()
    if (v == 0) return "Auto"
    // Olympus typically stores aperture * 10 (e.g., 28 = f/2.8)
    val fNum = v / 10.0
    return if (fNum == fNum.toLong().toDouble()) "F${fNum.toLong()}" else "F${"%.1f".format(fNum)}"
}

private fun formatOlympusIso(value: Long): String {
    val v = value.toInt()
    return when {
        v == 0 || v == 0xFFFF -> "Auto"
        v == 0xFFFE -> "Auto"
        else -> "ISO $v"
    }
}

private fun formatOlympusExpComp(value: Long): String {
    val v = value.toInt().toShort().toInt() // treat as signed 16-bit
    if (v == 0) return "0.0"
    // Olympus stores EV * 10 (e.g., -7 = -0.7 EV, 10 = +1.0 EV)
    val ev = v / 10.0
    return if (ev > 0) "+${"%.1f".format(ev)}" else "${"%.1f".format(ev)}"
}

private fun formatOlympusWb(value: Long): String = when (value.toInt()) {
    0 -> "AWB"; 16 -> "Sunny"; 17 -> "Shade"; 18 -> "Cloudy"
    20 -> "Tungsten"; 35 -> "Fluorescent"; 64 -> "Flash"
    23 -> "Underwater"; 256 -> "CWB1"; 257 -> "CWB2"
    258 -> "CWB3"; 259 -> "CWB4"; 512 -> "Kelvin"
    else -> "WB ${value.toInt()}"
}

@Composable
private fun SectionActionPanel(
    studioState: OmCaptureStudioUiState,
    omCaptureUsb: OmCaptureUsbUiState,
    remoteRuntime: RemoteRuntimeState,
    liveViewFrame: Bitmap?,
    onDispatchAction: (OmCaptureAction) -> Unit,
) {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = "${studioState.selectedSection.title} Controls",
                style = MaterialTheme.typography.labelLarge,
                color = Chalk,
                fontWeight = FontWeight.Bold,
            )

            when (studioState.selectedSection) {
                OmCaptureSection.Connection -> {
                    studioState.controlValues.forEach { value ->
                        KeyValueRow(value.label, value.value)
                        value.detail?.let { detail ->
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = Chalk.copy(alpha = 0.56f),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onDispatchAction(OmCaptureAction.RefreshUsbConnection) },
                            colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text("USB Connection")
                        }
                        OutlinedButton(
                            onClick = { onDispatchAction(OmCaptureAction.ClearUsbStatus) },
                            border = androidx.compose.foundation.BorderStroke(1.dp, LeicaBorder),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text("Clear Status", color = Chalk)
                        }
                    }
                    Text(
                        text = if (studioState.remoteControlReady) {
                            "USB camera control is ready in this screen."
                        } else {
                            "USB tether is ready, but property control still needs the existing remote session before exposure and drive settings can be changed here."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Chalk.copy(alpha = 0.62f),
                    )
                }

                OmCaptureSection.LiveView -> {
                    if (liveViewFrame != null) {
                        Image(
                            bitmap = liveViewFrame.asImageBitmap(),
                            contentDescription = "USB live preview",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(18.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    KeyValueRow("Live Preview", if (studioState.liveViewActive) "Running" else "Ready")
                    KeyValueRow("Preview Pane", if (liveViewFrame != null) "Streaming to Android" else "Waiting for usable JPEG frame")
                    KeyValueRow("Preview Size", "Automatic D06D candidate recovery")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onDispatchAction(OmCaptureAction.ToggleUsbLiveView) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (studioState.liveViewActive) AppleRed else AppleBlue,
                            ),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text(if (studioState.liveViewActive) "Stop Live Preview" else "Start Live Preview")
                        }
                        OutlinedButton(
                            onClick = { onDispatchAction(OmCaptureAction.CaptureImport) },
                            enabled = !omCaptureUsb.isBusy,
                            border = androidx.compose.foundation.BorderStroke(1.dp, LeicaBorder),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text("Capture and Import", color = Chalk)
                        }
                    }
                    Text(
                        text = "Live preview now follows the event-driven USB handoff and keeps streaming with device-specific recovery if blank frames cluster.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Chalk.copy(alpha = 0.62f),
                    )
                }

                OmCaptureSection.AfMf -> {
                    KeyValueRow("Focus State", focusStateLabel(remoteRuntime))
                    KeyValueRow("Remote Status", studioState.remoteStatusLabel)
                    KeyValueRow("AF Target", "Use the existing remote live view tap-to-focus surface")
                    KeyValueRow("One Touch WB", "Validation pending")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onDispatchAction(OmCaptureAction.CaptureRemotePhoto) },
                            colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text("Remote Shutter")
                        }
                        OutlinedButton(
                            onClick = { onDispatchAction(OmCaptureAction.SelectSection(OmCaptureSection.LiveView)) },
                            border = androidx.compose.foundation.BorderStroke(1.dp, LeicaBorder),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text("Open Live View", color = Chalk)
                        }
                    }
                    Text(
                        text = "Half-press hold, MF drive amount, One Touch WB payloads, and AF target writes stay disabled until additional device validation is complete.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Chalk.copy(alpha = 0.62f),
                    )
                }

                OmCaptureSection.Exposure -> {
                    KeyValueRow("Mode", remoteRuntime.exposureMode.label)
                    KeyValueRow("Aperture", formatPropertyValue("focalvalue", remoteRuntime.aperture.currentValue))
                    KeyValueRow("Shutter", formatPropertyValue("shutspeedvalue", remoteRuntime.shutterSpeed.currentValue))
                    KeyValueRow("ISO", formatPropertyValue("isospeedvalue", remoteRuntime.iso.currentValue, remoteRuntime.iso.autoActive))
                    KeyValueRow("White Balance", formatPropertyValue("wbvalue", remoteRuntime.whiteBalance.currentValue))
                    KeyValueRow("Exposure Compensation", formatPropertyValue("expcomp", remoteRuntime.exposureCompensationValues.currentValue))

                    ControlGroupLabel("Exposure Mode")
                    OptionChipRow(
                        options = CameraExposureMode.entries.map { option ->
                            Triple(option.label, option == remoteRuntime.exposureMode, true)
                        },
                        onSelect = { selected ->
                            CameraExposureMode.entries.firstOrNull { it.label == selected }?.let { mode ->
                                onDispatchAction(OmCaptureAction.SetCameraExposureMode(mode))
                            }
                        },
                    )

                    PropertySelector(
                        title = "Aperture",
                        currentValue = formatPropertyValue("focalvalue", remoteRuntime.aperture.currentValue),
                        values = remoteRuntime.aperture.availableValues,
                        formatter = { formatPropertyValue("focalvalue", it) },
                        onSelect = { onDispatchAction(OmCaptureAction.SetPropertyValue(ActivePropertyPicker.Aperture, it)) },
                    )
                    PropertySelector(
                        title = "Shutter Speed",
                        currentValue = formatPropertyValue("shutspeedvalue", remoteRuntime.shutterSpeed.currentValue),
                        values = remoteRuntime.shutterSpeed.availableValues,
                        formatter = { formatPropertyValue("shutspeedvalue", it) },
                        onSelect = { onDispatchAction(OmCaptureAction.SetPropertyValue(ActivePropertyPicker.ShutterSpeed, it)) },
                    )
                    PropertySelector(
                        title = "ISO Sensitivity",
                        currentValue = formatPropertyValue("isospeedvalue", remoteRuntime.iso.currentValue, remoteRuntime.iso.autoActive),
                        values = remoteRuntime.iso.availableValues,
                        formatter = { formatPropertyValue("isospeedvalue", it) },
                        onSelect = { onDispatchAction(OmCaptureAction.SetPropertyValue(ActivePropertyPicker.Iso, it)) },
                    )
                    PropertySelector(
                        title = "White Balance",
                        currentValue = formatPropertyValue("wbvalue", remoteRuntime.whiteBalance.currentValue),
                        values = remoteRuntime.whiteBalance.availableValues,
                        formatter = { formatPropertyValue("wbvalue", it) },
                        onSelect = { onDispatchAction(OmCaptureAction.SetPropertyValue(ActivePropertyPicker.WhiteBalance, it)) },
                    )
                    PropertySelector(
                        title = "Exposure Compensation",
                        currentValue = formatPropertyValue("expcomp", remoteRuntime.exposureCompensationValues.currentValue),
                        values = remoteRuntime.exposureCompensationValues.availableValues,
                        formatter = { formatPropertyValue("expcomp", it) },
                        onSelect = { onDispatchAction(OmCaptureAction.SetExposureCompensation(it)) },
                    )
                }

                OmCaptureSection.DriveComputational -> {
                    KeyValueRow("Drive", remoteRuntime.driveMode.label)
                    KeyValueRow("Timer", remoteRuntime.timerMode.label)
                    KeyValueRow("Timer Delay", "${remoteRuntime.timerDelay}s")
                    KeyValueRow(
                        "Interval Shooting",
                        if (remoteRuntime.intervalRunning) {
                            "${remoteRuntime.intervalCurrent}/${remoteRuntime.intervalCount} running"
                        } else {
                            "${remoteRuntime.intervalSeconds}s x ${remoteRuntime.intervalCount}"
                        },
                    )

                    ControlGroupLabel("Drive Mode")
                    OptionChipRow(
                        options = DriveMode.entries.map { option ->
                            Triple(option.label, option == remoteRuntime.driveMode, true)
                        },
                        onSelect = { selected ->
                            DriveMode.entries.firstOrNull { it.label == selected }?.let { mode ->
                                onDispatchAction(OmCaptureAction.SetDriveMode(mode))
                            }
                        },
                    )

                    ControlGroupLabel("Timer")
                    OptionChipRow(
                        options = TimerMode.entries.map { option ->
                            Triple(option.label, option == remoteRuntime.timerMode, true)
                        },
                        onSelect = { selected ->
                            TimerMode.entries.firstOrNull { it.label == selected }?.let { mode ->
                                onDispatchAction(OmCaptureAction.SetTimerMode(mode))
                            }
                        },
                    )

                    ControlGroupLabel("Timer Delay")
                    OptionChipRow(
                        options = timerDelayChoices(remoteRuntime.timerDelay).map { delaySeconds ->
                            Triple("${delaySeconds}s", delaySeconds == remoteRuntime.timerDelay, true)
                        },
                        onSelect = { selected ->
                            selected.removeSuffix("s").toIntOrNull()?.let { seconds ->
                                onDispatchAction(OmCaptureAction.SetTimerDelay(seconds))
                            }
                        },
                    )

                    AdjustValueRow(
                        title = "Shooting Interval",
                        value = "${remoteRuntime.intervalSeconds}s",
                        onDecrease = {
                            onDispatchAction(
                                OmCaptureAction.SetIntervalSeconds(
                                    (remoteRuntime.intervalSeconds - 1).coerceAtLeast(1),
                                ),
                            )
                        },
                        onIncrease = {
                            onDispatchAction(
                                OmCaptureAction.SetIntervalSeconds(
                                    (remoteRuntime.intervalSeconds + 1).coerceAtMost(3600),
                                ),
                            )
                        },
                    )
                    AdjustValueRow(
                        title = "Frame Count",
                        value = remoteRuntime.intervalCount.toString(),
                        onDecrease = {
                            onDispatchAction(
                                OmCaptureAction.SetIntervalCount(
                                    (remoteRuntime.intervalCount - 1).coerceAtLeast(1),
                                ),
                            )
                        },
                        onIncrease = {
                            onDispatchAction(
                                OmCaptureAction.SetIntervalCount(
                                    (remoteRuntime.intervalCount + 1).coerceAtMost(999),
                                ),
                            )
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                onDispatchAction(
                                    if (remoteRuntime.intervalRunning) OmCaptureAction.StopInterval
                                    else OmCaptureAction.StartInterval,
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (remoteRuntime.intervalRunning) AppleRed else AppleBlue,
                            ),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text(if (remoteRuntime.intervalRunning) "Stop Interval" else "Start Interval")
                        }
                        OutlinedButton(
                            onClick = { onDispatchAction(OmCaptureAction.CaptureRemotePhoto) },
                            border = androidx.compose.foundation.BorderStroke(1.dp, LeicaBorder),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text("Remote Shutter", color = Chalk)
                        }
                    }
                    Text(
                        text = "HDR, High Res Shot, Pro Capture, Live Bulb, and Focus Stacking remain visible in the matrix but stay disabled until additional device validation is complete.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Chalk.copy(alpha = 0.62f),
                    )
                }

                OmCaptureSection.Movie -> {
                    KeyValueRow("Movie Mode", if (remoteRuntime.exposureMode == CameraExposureMode.VIDEO) "Video active" else "Switch required")
                    KeyValueRow("Movie Settings", "Validation pending")
                    KeyValueRow("Movie AF Mode", "Validation pending")
                    KeyValueRow("Movie Save Slot", "Validation pending")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onDispatchAction(OmCaptureAction.SetCameraExposureMode(CameraExposureMode.VIDEO)) },
                            colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text("Switch To Video")
                        }
                        OutlinedButton(
                            onClick = { onDispatchAction(OmCaptureAction.CaptureRemotePhoto) },
                            border = androidx.compose.foundation.BorderStroke(1.dp, LeicaBorder),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text("Validate Movie Flow", color = Chalk)
                        }
                    }
                }

                OmCaptureSection.FileSave -> {
                    studioState.controlValues.forEach { value ->
                        KeyValueRow(value.label, value.value)
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SaveTargetChip("SD", studioState.tetherSaveTarget == TetherSaveTarget.SdCard) {
                            onDispatchAction(OmCaptureAction.SetSaveTarget(TetherSaveTarget.SdCard))
                        }
                        SaveTargetChip("SD + Phone", studioState.tetherSaveTarget == TetherSaveTarget.SdAndPhone) {
                            onDispatchAction(OmCaptureAction.SetSaveTarget(TetherSaveTarget.SdAndPhone))
                        }
                        SaveTargetChip("Phone", studioState.tetherSaveTarget == TetherSaveTarget.Phone) {
                            onDispatchAction(OmCaptureAction.SetSaveTarget(TetherSaveTarget.Phone))
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    ControlGroupLabel("Phone Import Format")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SaveTargetChip("JPEG", studioState.phoneImportFormat == TetherPhoneImportFormat.JpegOnly) {
                            onDispatchAction(OmCaptureAction.SetPhoneImportFormat(TetherPhoneImportFormat.JpegOnly))
                        }
                        SaveTargetChip("RAW", studioState.phoneImportFormat == TetherPhoneImportFormat.RawOnly) {
                            onDispatchAction(OmCaptureAction.SetPhoneImportFormat(TetherPhoneImportFormat.RawOnly))
                        }
                    }
                }

                OmCaptureSection.TransferLink -> {
                    studioState.controlValues.forEach { value ->
                        KeyValueRow(value.label, value.value)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onDispatchAction(OmCaptureAction.CaptureImport) },
                            enabled = !omCaptureUsb.isBusy,
                            colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text(captureLabel(studioState.tetherSaveTarget))
                        }
                        OutlinedButton(
                            onClick = { onDispatchAction(OmCaptureAction.ImportLatest) },
                            enabled = !omCaptureUsb.isBusy,
                            border = androidx.compose.foundation.BorderStroke(1.dp, LeicaBorder),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text("Import Latest", color = Chalk)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { onDispatchAction(OmCaptureAction.CaptureRemotePhoto) },
                            border = androidx.compose.foundation.BorderStroke(1.dp, LeicaBorder),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text("Remote Capture", color = Chalk)
                        }
                        OutlinedButton(
                            onClick = { onDispatchAction(OmCaptureAction.RefreshUsbConnection) },
                            border = androidx.compose.foundation.BorderStroke(1.dp, LeicaBorder),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text("Refresh USB", color = Chalk)
                        }
                    }
                }

                OmCaptureSection.Support -> {
                    studioState.controlValues.forEach { value ->
                        KeyValueRow(value.label, value.value)
                        value.detail?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = Chalk.copy(alpha = 0.55f),
                            )
                        }
                    }
                    Text(
                        text = "Only validated features are enabled here. Anything still marked validation pending remains disabled until additional device testing is complete.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Chalk.copy(alpha = 0.62f),
                    )
                }
            }

            StatusStrip(
                usbState = omCaptureUsb.operationState,
                usbLabel = omCaptureUsb.statusLabel,
                liveViewActive = remoteRuntime.liveViewActive,
            )
        }
    }
}

@Composable
private fun ControlGroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = Chalk,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun OptionChipRow(
    options: List<Triple<String, Boolean, Boolean>>,
    onSelect: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        options.forEach { (label, selected, enabled) ->
            SelectableActionChip(
                title = label,
                selected = selected,
                enabled = enabled,
                onClick = { onSelect(label) },
            )
        }
    }
}

@Composable
private fun PropertySelector(
    title: String,
    currentValue: String,
    values: List<String>,
    formatter: (String) -> String,
    onSelect: (String) -> Unit,
) {
    val sanitized = values.distinct()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KeyValueRow(title, currentValue.ifBlank { "--" })
        if (sanitized.isEmpty()) {
            Text(
                text = "Reconnect the existing remote control session to load available $title options.",
                style = MaterialTheme.typography.bodySmall,
                color = Chalk.copy(alpha = 0.56f),
            )
        } else {
            OptionChipRow(
                options = sanitized.map { value ->
                    Triple(formatter(value), value == currentValue || formatter(value) == currentValue, true)
                },
                onSelect = { selectedLabel ->
                    sanitized.firstOrNull { formatter(it) == selectedLabel || it == selectedLabel }?.let(onSelect)
                },
            )
        }
    }
}

@Composable
private fun AdjustValueRow(
    title: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KeyValueRow(title, value)
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onDecrease,
                border = androidx.compose.foundation.BorderStroke(1.dp, LeicaBorder),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("-", color = Chalk)
            }
            Text(
                text = value,
                color = Chalk,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = onIncrease,
                border = androidx.compose.foundation.BorderStroke(1.dp, LeicaBorder),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("+", color = Chalk)
            }
        }
    }
}

@Composable
private fun SelectableActionChip(
    title: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(enabled = enabled, onClick = onClick),
        color = when {
            selected -> AppleBlue.copy(alpha = 0.18f)
            else -> Color.White.copy(alpha = 0.03f)
        },
        shape = RoundedCornerShape(999.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            when {
                !enabled -> Chalk.copy(alpha = 0.14f)
                selected -> AppleBlue.copy(alpha = 0.5f)
                else -> LeicaBorder
            },
        ),
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            color = if (enabled) Chalk else Chalk.copy(alpha = 0.42f),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun FeatureRow(feature: OmCaptureFeatureDescriptor) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = feature.originalLabel,
            style = MaterialTheme.typography.titleSmall,
            color = Chalk,
            fontWeight = FontWeight.SemiBold,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MatrixBadge(feature.om1SupportState.label, AppleBlue.copy(alpha = 0.22f))
            MatrixBadge(feature.androidPolicy.label, AppleOrange.copy(alpha = 0.22f))
            MatrixBadge(feature.implementationStatus.label, implementationColor(feature.implementationStatus))
        }
        Text(
            text = feature.summary,
            style = MaterialTheme.typography.bodySmall,
            color = Chalk.copy(alpha = 0.72f),
        )
        Text(
            text = "Protocol: ${feature.ptpReferences.joinToString()}",
            style = MaterialTheme.typography.bodySmall,
            color = Chalk.copy(alpha = 0.56f),
        )
        Text(
            text = "Notes: ${feature.evidenceSource}",
            style = MaterialTheme.typography.bodySmall,
            color = Chalk.copy(alpha = 0.52f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.06f)),
        )
    }
}

@Composable
private fun StatusStrip(
    usbState: OmCaptureUsbOperationState,
    usbLabel: String,
    liveViewActive: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    when {
                        liveViewActive -> AppleGreen
                        usbState != OmCaptureUsbOperationState.Idle -> AppleOrange
                        else -> Chalk.copy(alpha = 0.4f)
                    },
                ),
        )
        Text(
            text = usbLabel,
            style = MaterialTheme.typography.bodySmall,
            color = Chalk.copy(alpha = 0.62f),
        )
    }
}

@Composable
private fun SectionChip(
    title: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        color = if (selected) AppleBlue.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) AppleBlue.copy(alpha = 0.5f) else LeicaBorder,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(text = title, color = Chalk, fontWeight = FontWeight.SemiBold)
            Text(text = detail, color = Chalk.copy(alpha = 0.56f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SaveTargetChip(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick),
        color = if (selected) AppleGreen.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.03f),
        shape = RoundedCornerShape(999.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) AppleGreen.copy(alpha = 0.5f) else LeicaBorder),
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            color = Chalk,
        )
    }
}

@Composable
private fun SummaryChip(text: String, color: Color) {
    MatrixBadge(text, color.copy(alpha = 0.22f))
}

@Composable
private fun MatrixBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color)
            .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            color = Chalk,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun implementationColor(status: OmCaptureImplementationStatus): Color = when (status) {
    OmCaptureImplementationStatus.Implemented -> AppleGreen.copy(alpha = 0.22f)
    OmCaptureImplementationStatus.Adapted -> AppleOrange.copy(alpha = 0.22f)
    OmCaptureImplementationStatus.DesktopOnly -> AppleRed.copy(alpha = 0.16f)
    OmCaptureImplementationStatus.NotApplicable -> Chalk.copy(alpha = 0.18f)
    OmCaptureImplementationStatus.BlockedByMissingTrace -> AppleRed.copy(alpha = 0.22f)
}

private fun captureLabel(target: TetherSaveTarget): String = when (target) {
    TetherSaveTarget.SdCard -> "Capture to SD"
    TetherSaveTarget.SdAndPhone -> "Capture to SD + Phone"
    TetherSaveTarget.Phone -> "Capture to Phone"
}

private fun formatPropertyValue(
    propertyName: String,
    rawValue: String,
    autoActive: Boolean = false,
): String {
    val formatted = when (propertyName) {
        "isospeedvalue" -> PropertyFormatter.formatIsoDisplay(rawValue, autoActive)
        else -> PropertyFormatter.formatForDisplay(propertyName, rawValue)
    }
    return formatted.ifBlank { rawValue.ifBlank { "--" } }
}

private fun focusStateLabel(runtime: RemoteRuntimeState): String = when {
    runtime.isFocusing -> "Focusing"
    runtime.focusHeld -> "Locked"
    else -> "Idle"
}

private fun timerDelayChoices(current: Int): List<Int> = listOf(2, 12, 20, current)
    .filter { it > 0 }
    .distinct()
    .sorted()
