package dev.pl36.cameralink.feature.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pl36.cameralink.R
import dev.pl36.cameralink.core.config.AppConfig
import dev.pl36.cameralink.core.model.CameraCapability
import dev.pl36.cameralink.core.model.ConnectionMethod
import dev.pl36.cameralink.core.model.CameraWorkspace
import dev.pl36.cameralink.core.model.GeoTaggingSnapshot
import dev.pl36.cameralink.core.model.SavedCameraProfile
import dev.pl36.cameralink.core.model.SettingItem
import dev.pl36.cameralink.core.usb.OmCaptureUsbOperationState
import dev.pl36.cameralink.core.usb.statusChipLabel
import dev.pl36.cameralink.core.presentation.displayLabel
import dev.pl36.cameralink.core.presentation.displayName
import dev.pl36.cameralink.core.presentation.toPresentation
import dev.pl36.cameralink.core.session.CameraSessionState
import dev.pl36.cameralink.core.ui.ActionCard
import dev.pl36.cameralink.core.ui.GlassCard
import dev.pl36.cameralink.core.ui.KeyValueRow
import dev.pl36.cameralink.core.ui.StatusBadge
import dev.pl36.cameralink.core.wifi.WifiConnectionState
import dev.pl36.cameralink.ui.OmCaptureUsbUiState
import dev.pl36.cameralink.ui.theme.AppleBlue
import dev.pl36.cameralink.ui.theme.AppleGreen
import dev.pl36.cameralink.ui.theme.AppleOrange
import dev.pl36.cameralink.ui.theme.AppleRed
import dev.pl36.cameralink.ui.theme.Chalk
import dev.pl36.cameralink.ui.theme.LeicaBorder
import java.util.Locale

@Composable
fun DashboardScreen(
    workspace: CameraWorkspace,
    rawProtocolInput: String,
    appConfig: AppConfig,
    settings: List<SettingItem>,
    geoTagging: GeoTaggingSnapshot,
    sessionState: CameraSessionState,
    wifiState: WifiConnectionState,
    protocolError: String?,
    isRefreshing: Boolean,
    refreshStatus: String,
    showProtocolWorkbench: Boolean,
    onRefresh: () -> Unit,
    onOpenRemote: () -> Unit,
    onOpenTransfer: () -> Unit,
    onOpenGeoTag: () -> Unit,
    onOpenOmCapture: () -> Unit,
    onOpenQaRecorder: (() -> Unit)? = null,
    onOpenQrScanner: () -> Unit,
    onAutoConnect: () -> Unit = {},
    onDisconnectCamera: () -> Unit = {},
    onReconnectManual: () -> Unit = {},
    hasSavedCamera: Boolean = false,
    savedCameras: List<SavedCameraProfile> = emptyList(),
    selectedCameraSsid: String? = null,
    onSelectSavedCamera: (String) -> Unit = {},
    onToggleSavedCameraPower: (String, Boolean) -> Unit = { _, _ -> },
    onConnectSavedCamera: (String) -> Unit = {},
    onProtocolInputChanged: (String) -> Unit,
    onApplyProtocol: () -> Unit,
    onResetProtocol: () -> Unit,
) {
    val sessionPresentation = sessionState.toPresentation(appConfig.liveViewPort)
    val sessionColor = sessionState.toAccentColor()
    val isConnected = sessionState is CameraSessionState.Connected ||
        sessionState is CameraSessionState.LiveView ||
        sessionState is CameraSessionState.Transferring
    val statusTitle = when (sessionState) {
        CameraSessionState.Idle -> dashboardLocalized("Ready", "\uC5F0\uACB0 \uB300\uAE30")
        is CameraSessionState.Connected,
        is CameraSessionState.LiveView,
        is CameraSessionState.Transferring -> dashboardLocalized("Connected", "\uC5F0\uACB0\uB428")
        else -> sessionPresentation.title
    }
    val suppressWifiRedirectPrompt = wifiState is WifiConnectionState.OtherWifi &&
        protocolError?.let { message ->
            message.contains("camera's WiFi", ignoreCase = true) ||
                message.contains("Reconnect button", ignoreCase = true)
        } == true

    val connectionHint = when (wifiState) {
        WifiConnectionState.WifiOff -> stringResource(R.string.dashboard_wait_camera_wifi)
        WifiConnectionState.Disconnected -> stringResource(R.string.dashboard_join_camera_wifi)
        is WifiConnectionState.OtherWifi -> ""
        is WifiConnectionState.CameraWifi -> stringResource(R.string.dashboard_camera_wifi_detected)
    }

    val primaryActionLabel = when {
        isConnected -> stringResource(R.string.dest_remote)
        hasSavedCamera -> stringResource(R.string.dashboard_reconnect)
        else -> stringResource(R.string.dashboard_scan_qr)
    }
    val statusDetail = when {
        protocolError != null && !suppressWifiRedirectPrompt -> protocolError
        isConnected -> when (sessionState) {
            is CameraSessionState.LiveView ->
                dashboardLocalized("Live view is active", "\uB77C\uC774\uBE0C\uBDF0\uAC00 \uC2E4\uD589 \uC911\uC785\uB2C8\uB2E4")
            is CameraSessionState.Transferring ->
                dashboardLocalized("Browsing photos from the camera", "\uCE74\uBA54\uB77C \uC0AC\uC9C4\uC744 \uD655\uC778 \uC911\uC785\uB2C8\uB2E4")
            else -> dashboardLocalized("Camera connected", "\uCE74\uBA54\uB77C\uC640 \uC5F0\uACB0\uB418\uC5C8\uC2B5\uB2C8\uB2E4")
        }
        sessionState is CameraSessionState.Connecting ->
            dashboardLocalized("Connecting to the camera", "\uCE74\uBA54\uB77C\uC5D0 \uC5F0\uACB0 \uC911\uC785\uB2C8\uB2E4")
        refreshStatus.isNotBlank() && isRefreshing -> refreshStatus
        else -> connectionHint
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
    ) {
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(sessionColor),
                                )
                                Text(
                                    text = workspace.camera.name.ifBlank {
                                        stringResource(R.string.dashboard_no_camera_selected)
                                    },
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = Chalk,
                                )
                            }
                            StatusBadge(label = statusTitle, color = sessionColor)
                            if (statusDetail.isNotBlank()) {
                                Text(
                                    text = statusDetail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (protocolError != null && !suppressWifiRedirectPrompt) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        Chalk.copy(alpha = 0.58f)
                                    },
                                )
                            }
                        }

                        Button(
                            onClick = onRefresh,
                            enabled = !isRefreshing,
                            colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                if (isRefreshing) {
                                    stringResource(R.string.dash_syncing)
                                } else {
                                    stringResource(R.string.dash_sync)
                                },
                            )
                        }
                    }

                    if (hasSavedCamera && savedCameras.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.dashboard_saved_cameras),
                            style = MaterialTheme.typography.labelMedium,
                            color = Chalk.copy(alpha = 0.46f),
                            fontWeight = FontWeight.Bold,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            savedCameras.forEach { profile ->
                                val selected = selectedCameraSsid == profile.ssid
                                val powerOn = selected && (
                                    isConnected ||
                                        (wifiState is WifiConnectionState.CameraWifi &&
                                            wifiState.ssid?.equals(profile.ssid, ignoreCase = true) == true)
                                    )
                                val canPowerOn = !profile.blePass.isNullOrBlank()
                                val canPowerOff = powerOn
                                val powerToggleEnabled = !isRefreshing && (canPowerOn || canPowerOff)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(Color.White.copy(alpha = 0.03f))
                                        .clickable { onSelectSavedCamera(profile.ssid) }
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(if (selected) AppleBlue else Chalk.copy(alpha = 0.18f)),
                                    )
                                    Spacer(modifier = Modifier.size(10.dp))
                                    Column(
                                        modifier = Modifier
                                            .weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = profile.displayName,
                                            color = Chalk,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = if (selected) {
                                                stringResource(R.string.dashboard_current_selected)
                                            } else {
                                                stringResource(R.string.dashboard_saved_camera)
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (selected) AppleBlue else Chalk.copy(alpha = 0.42f),
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Rounded.PowerSettingsNew,
                                        contentDescription = null,
                                        tint = if (powerToggleEnabled) {
                                            if (powerOn) AppleBlue else Chalk.copy(alpha = 0.54f)
                                        } else {
                                            Chalk.copy(alpha = 0.22f)
                                        },
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.size(8.dp))
                                    Switch(
                                        checked = powerOn,
                                        onCheckedChange = { enabled ->
                                            onToggleSavedCameraPower(profile.ssid, enabled)
                                        },
                                        enabled = powerToggleEnabled,
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Chalk,
                                            checkedTrackColor = AppleBlue,
                                            uncheckedThumbColor = Chalk.copy(alpha = 0.86f),
                                            uncheckedTrackColor = Chalk.copy(alpha = 0.18f),
                                            disabledCheckedThumbColor = Chalk.copy(alpha = 0.5f),
                                            disabledCheckedTrackColor = AppleBlue.copy(alpha = 0.3f),
                                            disabledUncheckedThumbColor = Chalk.copy(alpha = 0.32f),
                                            disabledUncheckedTrackColor = Chalk.copy(alpha = 0.12f),
                                        ),
                                    )
                                }
                            }
                        }
                    } else if (connectionHint.isNotBlank()) {
                        Text(
                            text = connectionHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = Chalk.copy(alpha = 0.52f),
                        )
                    }

                    if (hasSavedCamera) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = if (isConnected) onOpenRemote else onReconnectManual,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                                shape = RoundedCornerShape(18.dp),
                            ) {
                                Icon(
                                    if (isConnected) Icons.Rounded.Videocam else Icons.Rounded.Sync,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.size(6.dp))
                                Text(primaryActionLabel)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = onOpenQrScanner,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Chalk),
                                border = BorderStroke(1.dp, LeicaBorder),
                                shape = RoundedCornerShape(18.dp),
                            ) {
                                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.size(6.dp))
                                Text(stringResource(R.string.dashboard_add_camera))
                            }
                        }
                    } else {
                        // No saved camera: show QR scan only
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = onOpenQrScanner,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                                shape = RoundedCornerShape(18.dp),
                            ) {
                                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(stringResource(R.string.dashboard_scan_qr))
                            }
                        }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = stringResource(R.string.dashboard_quick_actions),
                    style = MaterialTheme.typography.labelMedium,
                    color = Chalk.copy(alpha = 0.46f),
                    fontWeight = FontWeight.Bold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    maxItemsInEachRow = 2,
                ) {
                    ActionCard(
                        title = stringResource(R.string.dest_remote),
                        icon = Icons.Rounded.Videocam,
                        accent = AppleBlue,
                        enabled = workspace.capabilities.contains(CameraCapability.RemoteCapture),
                        onClick = onOpenRemote,
                        modifier = Modifier.weight(1f),
                    )
                    ActionCard(
                        title = stringResource(R.string.dest_library),
                        icon = Icons.Rounded.Collections,
                        accent = AppleGreen,
                        enabled = workspace.capabilities.contains(CameraCapability.ImageBrowser),
                        onClick = onOpenTransfer,
                        modifier = Modifier.weight(1f),
                    )
                    ActionCard(
                        title = stringResource(R.string.dest_geotag),
                        icon = Icons.Rounded.LocationOn,
                        accent = AppleOrange,
                        onClick = onOpenGeoTag,
                        modifier = Modifier.weight(1f),
                    )
                    onOpenQaRecorder?.let { openQaRecorder ->
                        ActionCard(
                            title = dashboardLocalized("QA Recorder", "QA 기록"),
                            icon = Icons.Rounded.CameraAlt,
                            accent = AppleRed,
                            onClick = openQaRecorder,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.dashboard_camera_status),
                        style = MaterialTheme.typography.labelMedium,
                        color = Chalk.copy(alpha = 0.46f),
                        fontWeight = FontWeight.Bold,
                    )
                    ConnectionStateRow(
                        label = stringResource(R.string.dash_interface),
                        sessionState = sessionState,
                        wifiState = wifiState,
                        workspace = workspace,
                    )
                    KeyValueRow(
                        stringResource(R.string.dash_operating_mode),
                        workspace.camera.connectMode.displayLabel(),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.03f))
                            .clickable { onOpenGeoTag() }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.dash_location_sync),
                                color = Chalk,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = geoTagging.latestSample?.displayName()
                                    ?: stringResource(R.string.dashboard_location_inactive),
                                color = Chalk.copy(alpha = 0.52f),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = stringResource(R.string.dashboard_open_location_settings),
                            color = AppleBlue,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (isConnected) {
                        OutlinedButton(
                            onClick = onDisconnectCamera,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Chalk),
                            border = BorderStroke(1.dp, AppleRed),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Icon(Icons.Rounded.WifiOff, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(stringResource(R.string.dashboard_disconnect))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraSessionState.toAccentColor() = when (this) {
    CameraSessionState.Idle -> Chalk.copy(alpha = 0.42f)
    CameraSessionState.Discovering -> Chalk.copy(alpha = 0.72f)
    is CameraSessionState.Connecting -> AppleRed
    is CameraSessionState.Connected -> AppleRed
    is CameraSessionState.LiveView -> AppleRed
    is CameraSessionState.Transferring -> AppleRed
    is CameraSessionState.Error -> AppleRed
}

@Composable
private fun ConnectionStateRow(
    label: String,
    sessionState: CameraSessionState,
    wifiState: WifiConnectionState,
    workspace: CameraWorkspace,
) {
    val isConnected = sessionState is CameraSessionState.Connected ||
        sessionState is CameraSessionState.LiveView ||
        sessionState is CameraSessionState.Transferring
    val isConnecting = sessionState is CameraSessionState.Connecting
    val bluetoothActive = when (sessionState) {
        is CameraSessionState.Connecting ->
            sessionState.method == ConnectionMethod.BleOnly ||
                sessionState.method == ConnectionMethod.WifiBleAssist
        is CameraSessionState.Connected ->
            sessionState.method == ConnectionMethod.BleOnly ||
                sessionState.method == ConnectionMethod.WifiBleAssist
        else -> isConnected && (
            workspace.camera.connectionMethod == ConnectionMethod.BleOnly ||
                workspace.camera.connectionMethod == ConnectionMethod.WifiBleAssist
            )
    }
    val wifiActive = when (sessionState) {
        is CameraSessionState.Connecting -> sessionState.method != ConnectionMethod.BleOnly
        is CameraSessionState.Connected -> sessionState.method != ConnectionMethod.BleOnly
        else -> wifiState is WifiConnectionState.CameraWifi ||
            (isConnected && workspace.camera.connectionMethod != ConnectionMethod.BleOnly)
    }
    val summary = when {
        isConnected -> dashboardLocalized("Camera connected", "\uCE74\uBA54\uB77C \uC5F0\uACB0\uB428")
        isConnecting -> dashboardLocalized("Connecting", "\uC5F0\uACB0 \uC911")
        bluetoothActive -> dashboardLocalized("Bluetooth ready", "\uBE14\uB8E8\uD22C\uC2A4 \uC900\uBE44 \uC644\uB8CC")
        wifiActive -> dashboardLocalized("Camera Wi-Fi ready", "\uCE74\uBA54\uB77C Wi-Fi \uC900\uBE44 \uC644\uB8CC")
        else -> dashboardLocalized("Not connected", "\uC5F0\uACB0 \uC548 \uB428")
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = Chalk.copy(alpha = 0.62f),
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isConnected) AppleBlue else Chalk,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ConnectionSignalChip(
                label = "Wi-Fi",
                active = wifiActive,
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Wifi,
                        contentDescription = null,
                        tint = if (wifiActive) AppleBlue else Chalk.copy(alpha = 0.28f),
                        modifier = Modifier.size(16.dp),
                    )
                },
                modifier = Modifier.weight(1f),
            )
            ConnectionSignalChip(
                label = dashboardLocalized("Bluetooth", "\uBE14\uB8E8\uD22C\uC2A4"),
                active = bluetoothActive,
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Bluetooth,
                        contentDescription = null,
                        tint = if (bluetoothActive) AppleBlue else Chalk.copy(alpha = 0.28f),
                        modifier = Modifier.size(16.dp),
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ConnectionSignalChip(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) AppleBlue.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.03f))
            .border(
                1.dp,
                if (active) AppleBlue.copy(alpha = 0.34f) else LeicaBorder,
                RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (active) AppleBlue else Chalk.copy(alpha = 0.18f)),
        )
        icon()
        Text(
            text = label,
            color = if (active) Chalk else Chalk.copy(alpha = 0.58f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun dashboardLocalized(english: String, korean: String): String {
    return if (Locale.getDefault().language.equals("ko", ignoreCase = true)) {
        korean
    } else {
        english
    }
}
