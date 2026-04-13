package dev.pl36.cameralink

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.pl36.cameralink.core.ui.LensBackdrop
import dev.pl36.cameralink.feature.dashboard.DashboardScreen
import dev.pl36.cameralink.feature.deepsky.DeepSkyLiveStackScreen
import dev.pl36.cameralink.feature.deepsky.DeepSkyLiveStackViewModel
import dev.pl36.cameralink.feature.geotag.GeoTagScreen
import dev.pl36.cameralink.feature.omcapture.OmCaptureScreen
import dev.pl36.cameralink.feature.remote.RemoteScreen
import dev.pl36.cameralink.feature.settings.SettingsScreen
import dev.pl36.cameralink.feature.transfer.TransferSourceKind
import dev.pl36.cameralink.feature.transfer.TransferScreen
import dev.pl36.cameralink.core.logging.D
import dev.pl36.cameralink.core.model.ModePickerSurface
import dev.pl36.cameralink.ui.AppDestination
import dev.pl36.cameralink.ui.MainViewModel
import dev.pl36.cameralink.ui.theme.AppleBlue
import dev.pl36.cameralink.ui.theme.CameraLinkTheme
import dev.pl36.cameralink.ui.theme.Chalk
import dev.pl36.cameralink.ui.theme.Graphite
import dev.pl36.cameralink.ui.theme.LeicaBorder
import dev.pl36.cameralink.ui.theme.Obsidian
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

private const val OmCaptureRoute = "om_capture"
private const val DeepSkyLiveStackRoute = "deep_sky_live_stack"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraLinkApp(viewModel: MainViewModel = viewModel(), onExportLogs: () -> Unit) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val liveViewFrame by viewModel.liveViewFrame.collectAsStateWithLifecycle()
    val usbCameraProperties by viewModel.usbCameraProperties.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val currentDestination = AppDestination.entries.find { it.route == currentRoute }
    val navigateTo: (AppDestination) -> Unit = { destination ->
        D.nav("Navigate: ${currentDestination?.route ?: currentRoute ?: "unknown"} -> ${destination.route}")
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val autoTimeMatchEnabled = uiState.settings.firstOrNull { it.id == "time_match_geotags" }?.enabled ?: true
    val wifiRemoteReady =
        (uiState.sessionState is dev.pl36.cameralink.core.session.CameraSessionState.Connected ||
            uiState.sessionState is dev.pl36.cameralink.core.session.CameraSessionState.LiveView) &&
        uiState.wifiState is dev.pl36.cameralink.core.wifi.WifiConnectionState.CameraWifi
    val usbRemoteReady = uiState.omCaptureUsb.summary?.supportsLiveView == true
    val remoteSessionReady = wifiRemoteReady || usbRemoteReady
    var previousDestination by remember { mutableStateOf(currentDestination) }
    // Request device discovery permissions at startup.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        D.perm("Permission results: ${results.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" }}")
        viewModel.onPermissionsResult(results)
    }

    LaunchedEffect(Unit) {
        val perms = viewModel.getRequiredPermissions()
        D.perm("Requesting ${perms.size} permissions: ${perms.map { it.substringAfterLast('.') }}")
        permissionLauncher.launch(perms)
    }

    LaunchedEffect(currentDestination) {
        if (previousDestination == AppDestination.Remote && currentDestination != AppDestination.Remote) {
            viewModel.onNavigateAwayFromRemote()
        }
        previousDestination = currentDestination
    }

    LaunchedEffect(uiState.pendingReconnectHandoffToken, currentDestination) {
        val handoffToken = uiState.pendingReconnectHandoffToken ?: return@LaunchedEffect
        if (currentDestination != AppDestination.Remote) {
            D.reconnect("RECONNECT HANDOFF: navigating to remote for token=$handoffToken")
            navigateTo(AppDestination.Remote)
            return@LaunchedEffect
        }
        D.reconnect("RECONNECT HANDOFF: remote reached, completing token=$handoffToken")
        viewModel.completePendingReconnectHandoff(handoffToken)
    }

    CameraLinkTheme(darkTheme = true) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Obsidian,
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isExpanded = maxWidth >= 840.dp

                Box(modifier = Modifier.fillMaxSize()) {
                    LensBackdrop()

                    val isRemoteScreen = currentDestination == AppDestination.Remote

                    DisposableEffect(activity, isRemoteScreen) {
                        val window = activity?.window
                        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
                        if (isRemoteScreen) {
                            window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
                            controller?.hide(WindowInsetsCompat.Type.systemBars())
                            controller?.systemBarsBehavior =
                                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        } else {
                            window?.let { WindowCompat.setDecorFitsSystemWindows(it, true) }
                            controller?.show(WindowInsetsCompat.Type.systemBars())
                        }
                        onDispose {
                            if (!isRemoteScreen) {
                                controller?.show(WindowInsetsCompat.Type.systemBars())
                            }
                        }
                    }

                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = Color.Transparent,
                        contentWindowInsets = if (isRemoteScreen) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
                        topBar = {
                            if (!isRemoteScreen) {
                                AnimatedVisibility(
                                    visible = uiState.isRefreshing,
                                    enter = fadeIn() + scaleIn(initialScale = 0.9f),
                                    exit = fadeOut() + scaleOut(targetScale = 0.9f),
                                ) {
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp, vertical = 12.dp)
                                            .clip(RoundedCornerShape(999.dp)),
                                        color = AppleBlue,
                                        trackColor = Chalk.copy(alpha = 0.12f),
                                    )
                                }
                            }
                        },
                        bottomBar = {
                            if (!isExpanded && !isRemoteScreen) {
                                Surface(
                                    modifier = Modifier
                                        .navigationBarsPadding()
                                        .padding(horizontal = 20.dp, vertical = 8.dp),
                                    color = Graphite.copy(alpha = 0.98f),
                                    shape = RoundedCornerShape(30.dp),
                                    border = BorderStroke(1.dp, LeicaBorder),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 6.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        AppDestination.entries.forEach { destination ->
                                            val selected = currentDestination == destination
                                            val iconTint = if (selected) Chalk else Chalk.copy(alpha = 0.38f)
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(22.dp))
                                                    .background(
                                                        if (selected) AppleBlue.copy(alpha = 0.16f)
                                                            else Color.Transparent,
                                                    )
                                                    .clickable { navigateTo(destination) }
                                                    .padding(horizontal = 2.dp, vertical = 8.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                                ) {
                                                    Icon(
                                                        destination.icon,
                                                        contentDescription = stringResource(destination.labelRes),
                                                        modifier = Modifier.size(22.dp),
                                                        tint = iconTint,
                                                    )
                                                    Text(
                                                        text = stringResource(destination.labelRes),
                                                        modifier = Modifier.fillMaxWidth(),
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontSize = 10.sp,
                                                        ),
                                                        color = iconTint,
                                                        textAlign = TextAlign.Center,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        softWrap = false,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } 
                            }
                        },
                    ) { innerPadding ->
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                        ) {
                            if (isExpanded) {
                                NavigationRail(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .padding(start = 12.dp, top = 16.dp, bottom = 16.dp)
                                        .clip(RoundedCornerShape(26.dp))
                                        .border(1.dp, LeicaBorder, RoundedCornerShape(26.dp)),
                                    containerColor = Graphite.copy(alpha = 0.92f),
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxHeight(),
                                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                                    ) {
                                        AppDestination.entries.forEach { destination ->
                                            val selected = currentDestination == destination
                                            NavigationRailItem(
                                                selected = selected,
                                                onClick = { navigateTo(destination) },
                                                icon = { Icon(destination.icon, contentDescription = stringResource(destination.labelRes)) },
                                                label = { Text(stringResource(destination.labelRes)) },
                                                colors = NavigationRailItemDefaults.colors(
                                                    indicatorColor = AppleBlue.copy(alpha = 0.18f),
                                                    selectedIconColor = Chalk,
                                                    unselectedIconColor = Chalk.copy(alpha = 0.4f),
                                                ),
                                            )
                                        }
                                    }
                                }
                            }

                            NavHost(
                                navController = navController,
                                startDestination = AppDestination.Dashboard.route,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                enterTransition = { fadeIn(animationSpec = spring()) + scaleIn(initialScale = 0.95f) },
                                exitTransition = { fadeOut(animationSpec = spring()) + scaleOut(targetScale = 0.95f) },
                            ) {
                                composable(AppDestination.Dashboard.route) {
                                    DashboardScreen(
                                        workspace = uiState.workspace,
                                        rawProtocolInput = uiState.rawProtocolInput,
                                        appConfig = uiState.appConfig,
                                        settings = uiState.settings,
                                        geoTagging = uiState.geoTagging,
                                        sessionState = uiState.sessionState,
                                        wifiState = uiState.wifiState,
                                        protocolError = uiState.protocolError,
                                        isRefreshing = uiState.isRefreshing,
                                        refreshStatus = uiState.refreshStatus,
                                        showProtocolWorkbench = uiState.showProtocolWorkbench,
                                        onRefresh = viewModel::refresh,
                                        onOpenRemote = { navigateTo(AppDestination.Remote) },
                                        onOpenTransfer = { navigateTo(AppDestination.Transfer) },
                                        onOpenGeoTag = { navigateTo(AppDestination.GeoTag) },
                                        onOpenOmCapture = {
                                            viewModel.setModePickerSurface(ModePickerSurface.Tether)
                                            navigateTo(AppDestination.Remote)
                                        },
                                        onOpenQrScanner = { navController.navigate("qr_scanner") },
                                        onAutoConnect = viewModel::autoConnectWithSavedCredentials,
                                        onDisconnectCamera = viewModel::disconnectCamera,
                                        onReconnectManual = viewModel::reconnectManual,
                                        hasSavedCamera = uiState.hasSavedCamera,
                                        savedCameras = uiState.savedCameras,
                                        selectedCameraSsid = uiState.selectedCameraSsid,
                                        onSelectSavedCamera = viewModel::selectSavedCamera,
                                        onToggleSavedCameraPower = viewModel::toggleSavedCameraPower,
                                        onConnectSavedCamera = viewModel::connectToSavedCamera,
                                        onProtocolInputChanged = viewModel::updateRawProtocolInput,
                                        onApplyProtocol = viewModel::applyProtocolPreview,
                                        onResetProtocol = viewModel::resetProtocolSample,
                                    )
                                }
                                composable(AppDestination.Remote.route) {
                                    val latestTransfer = uiState.transferState.images.firstOrNull()
                                    RemoteScreen(
                                        remoteRuntime = uiState.remoteRuntime,
                                        remoteReady = remoteSessionReady,
                                        liveViewFrame = liveViewFrame,
                                        lastCaptureThumbnail = uiState.lastCaptureThumbnail,
                                        tetheredCaptureAvailable = true,
                                        omCaptureUsb = uiState.omCaptureUsb,
                                        latestTransferThumbnail = latestTransfer?.let { uiState.transferState.thumbnails[it.fileName] },
                                        latestTransferFileName = latestTransfer?.fileName,
                                        libraryBusy = uiState.transferState.isLoading || uiState.transferState.isDownloading,
                                        libraryStatus = uiState.transferState.downloadProgress,
                                        tetherSaveTarget = uiState.tetherSaveTarget,
                                        tetherPhoneImportFormat = uiState.tetherPhoneImportFormat,
                                        onSetPhoneImportFormat = viewModel::updateTetherPhoneImportFormat,
                                        onToggleLiveView = viewModel::toggleLiveView,
                                        onCapturePhoto = viewModel::captureRemotePhoto,
                                        onExposureChanged = viewModel::setExposureCompensationValue,
                                        onSetShootingMode = viewModel::setShootingMode,
                                        onSetIntervalSeconds = viewModel::setIntervalSeconds,
                                        onSetIntervalCount = viewModel::setIntervalCount,
                                        onStartInterval = viewModel::startInterval,
                                        onStopInterval = viewModel::stopInterval,
                                        onSetActivePicker = viewModel::setActivePicker,
                                        onSetModePickerSurface = viewModel::setModePickerSurface,
                                        onSetPropertyValue = { picker, value, closePicker ->
                                            viewModel.setPropertyValue(picker, value, closePicker)
                                        },
                                        onTouchFocus = viewModel::touchFocus,
                                        onSetCameraExposureMode = viewModel::setCameraExposureMode,
                                        onSetDriveMode = viewModel::setDriveMode,
                                        onSetTimerMode = viewModel::setTimerMode,
                                        onSetTimerDelay = viewModel::setTimerDelay,
                                        onRefreshOmCaptureUsb = viewModel::refreshOmCaptureUsb,
                                        onCaptureOmCaptureUsb = viewModel::captureOmCaptureUsbPhoto,
                                        onImportLatestOmCaptureUsb = viewModel::importLatestOmCaptureUsbImage,
                                        onClearOmCaptureUsb = viewModel::clearOmCaptureUsbState,
                                        onOpenOmCapture = {
                                            viewModel.setModePickerSurface(ModePickerSurface.Tether)
                                            navigateTo(AppDestination.Remote)
                                        },
                                        deepSkyState = uiState.deepSkyState,
                                        onOpenDeepSkyLiveStack = {
                                            navController.navigate(DeepSkyLiveStackRoute)
                                        },
                                        onStartDeepSkySession = viewModel::startDeepSkySession,
                                        onStopDeepSkySession = viewModel::stopDeepSkySession,
                                        onResetDeepSkySession = viewModel::resetDeepSkySession,
                                        onSelectDeepSkyPreset = viewModel::selectDeepSkyPreset,
                                        onSetDeepSkyManualFocalLength = viewModel::setDeepSkyManualFocalLength,
                                        onOpenLibrary = {
                                            val usbSaved = uiState.omCaptureUsb.lastSavedMedia
                                            if (usbSaved != null && activity != null) {
                                                // Open the image saved by the USB capture flow.
                                                viewModel.openUsbCapturedImage(activity)
                                            } else {
                                                viewModel.openLastCapturedPreview()
                                                navigateTo(AppDestination.Transfer)
                                            }
                                        },
                                        onSetUsbProperty = { propCode, value ->
                                            viewModel.dispatchOmCaptureAction(
                                                dev.pl36.cameralink.core.omcapture.OmCaptureAction.SetUsbProperty(propCode, value)
                                            )
                                        },
                                        onManualFocusDrive = { steps ->
                                            viewModel.dispatchOmCaptureAction(
                                                dev.pl36.cameralink.core.omcapture.OmCaptureAction.ManualFocusDrive(steps)
                                            )
                                        },
                                        onRefreshUsbProperties = {
                                            viewModel.dispatchOmCaptureAction(
                                                dev.pl36.cameralink.core.omcapture.OmCaptureAction.RefreshUsbProperties
                                            )
                                        },
                                        usbCameraProperties = usbCameraProperties,
                                        latestGeoTagSample = uiState.geoTagging.latestSample,
                                        reverseDialMap = mapOf(
                                            "focalvalue" to (uiState.settings.firstOrNull { it.id == "reverse_dial_aperture" }?.enabled == true),
                                            "shutspeedvalue" to (uiState.settings.firstOrNull { it.id == "reverse_dial_shutter" }?.enabled == true),
                                            "isospeedvalue" to (uiState.settings.firstOrNull { it.id == "reverse_dial_iso" }?.enabled == true),
                                            "wbvalue" to (uiState.settings.firstOrNull { it.id == "reverse_dial_wb" }?.enabled == true),
                                            "expcomp" to (uiState.settings.firstOrNull { it.id == "reverse_dial_ev" }?.enabled == true),
                                        ),
                                    )
                                }
                                composable(OmCaptureRoute) {
                                    OmCaptureScreen(
                                        studioState = uiState.omCaptureStudio,
                                        omCaptureUsb = uiState.omCaptureUsb,
                                        remoteRuntime = uiState.remoteRuntime,
                                        liveViewFrame = liveViewFrame,
                                        cameraProperties = usbCameraProperties,
                                        onDispatchAction = viewModel::dispatchOmCaptureAction,
                                    )
                                }
                                composable(DeepSkyLiveStackRoute) {
                                    val deepSkyViewModel: DeepSkyLiveStackViewModel =
                                        viewModel(factory = DeepSkyLiveStackViewModel.factory())
                                    val deepSkyState by deepSkyViewModel.uiState.collectAsStateWithLifecycle()
                                    LaunchedEffect(uiState.geoTagging.latestSample?.capturedAtMillis) {
                                        deepSkyViewModel.updateSkyHint(uiState.geoTagging.latestSample)
                                    }
                                    DeepSkyLiveStackScreen(
                                        uiState = deepSkyState,
                                        omCaptureUsb = uiState.omCaptureUsb,
                                        tetherSaveTarget = uiState.tetherSaveTarget,
                                        tetherPhoneImportFormat = uiState.tetherPhoneImportFormat,
                                        onBack = { navController.popBackStack() },
                                        onStartSession = deepSkyViewModel::onScreenEntered,
                                        onStopSession = deepSkyViewModel::onScreenExited,
                                        onResetSession = deepSkyViewModel::onResetSession,
                                        onSelectPreset = deepSkyViewModel::onSelectPreset,
                                        onRefreshUsb = viewModel::refreshOmCaptureUsb,
                                        onCaptureAndImport = viewModel::captureOmCaptureUsbPhoto,
                                        onImportLatest = viewModel::importLatestOmCaptureUsbImage,
                                    )
                                }
                                composable(AppDestination.Transfer.route) {
                                    LaunchedEffect(
                                        uiState.sessionState,
                                        uiState.selectedCameraSsid,
                                        uiState.transferState.sourceCameraSsid,
                                        uiState.transferState.images.size,
                                        uiState.transferState.isLoading,
                                    ) {
                                        val selectedCameraSsid = uiState.selectedCameraSsid
                                        val shouldRefreshForCamera =
                                            !selectedCameraSsid.isNullOrBlank() &&
                                                !uiState.transferState.sourceCameraSsid.equals(selectedCameraSsid, ignoreCase = true)
                                        val wifiLibraryReady =
                                            uiState.sessionState is dev.pl36.cameralink.core.session.CameraSessionState.Connected
                                        val usbLibraryReady = viewModel.isOmCaptureUsbLibraryAvailable()
                                        val shouldRefreshForWifiSource =
                                            wifiLibraryReady &&
                                                (
                                                    uiState.transferState.sourceKind != TransferSourceKind.WifiCamera ||
                                                        shouldRefreshForCamera
                                                    )
                                        val shouldRefreshForUsbSource =
                                            usbLibraryReady &&
                                                uiState.transferState.sourceKind != TransferSourceKind.OmCaptureUsb
                                        if (
                                            (wifiLibraryReady || usbLibraryReady) &&
                                            !uiState.transferState.isLoading &&
                                            (
                                                uiState.transferState.images.isEmpty() ||
                                                    shouldRefreshForWifiSource ||
                                                    shouldRefreshForUsbSource
                                                )
                                        ) {
                                            viewModel.loadCameraImages()
                                        }
                                    }
                                    TransferScreen(
                                        transferState = uiState.transferState,
                                        onLoadImages = viewModel::loadCameraImages,
                                        onDownloadImage = viewModel::downloadImage,
                                        onDeleteImage = viewModel::deleteImage,
                                        onSelectImage = viewModel::selectImage,
                                        onToggleSelection = viewModel::toggleImageSelection,
                                        onSelectAll = viewModel::selectAllImages,
                                        onClearSelection = viewModel::clearImageSelection,
                                        onDownloadSelected = viewModel::downloadSelectedImages,
                                        onDeleteSelected = viewModel::deleteSelectedImages,
                                        onSetTypeFilter = viewModel::setTypeFilter,
                                        onToggleDateSelection = viewModel::toggleDateSelection,
                                        onSelectUsbSource = viewModel::setUsbLibrarySourceSelection,
                                    )
                                }
                                composable(AppDestination.GeoTag.route) {
                                    GeoTagScreen(
                                        geoTagging = uiState.geoTagging,
                                        photoGeoTags = uiState.transferState.matchedGeoTags,
                                        autoMatchEnabled = autoTimeMatchEnabled,
                                        onAutoMatchChanged = { enabled ->
                                            viewModel.updateSetting("time_match_geotags", enabled)
                                        },
                                        onPermissionsResolved = viewModel::updateGeoTagPermissions,
                                        onCapturePin = viewModel::captureGeoTagPin,
                                        onSyncNow = viewModel::syncGeoTagNow,
                                        onStartSession = viewModel::startGeoTagSession,
                                        onStopSession = viewModel::stopGeoTagSession,
                                        onAdjustClockOffset = viewModel::adjustGeoTagClockOffset,
                                    )
                                }
                                composable(AppDestination.Settings.route) {
                                    SettingsScreen(
                                        workspace = uiState.workspace,
                                        settings = uiState.settings,
                                        appConfig = uiState.appConfig,
                                        permissionPlans = uiState.permissionPlans,
                                        hasSavedCamera = uiState.hasSavedCamera,
                                        savedCameras = uiState.savedCameras,
                                        selectedCameraSsid = uiState.selectedCameraSsid,
                                        selectedCardSlotSource = uiState.selectedCardSlotSource,
                                        selectedLanguageTag = uiState.selectedLanguageTag,
                                        tetherSaveTarget = uiState.tetherSaveTarget,
                                        onSelectSavedCamera = viewModel::selectSavedCamera,
                                        onSelectLanguage = { languageTag ->
                                            if (languageTag != uiState.selectedLanguageTag) {
                                                viewModel.setAppLanguage(languageTag)
                                                activity?.recreate()
                                            }
                                        },
                                        autoImportConfig = uiState.autoImportConfig,
                                        geotagConfig = uiState.geotagConfig,
                                        onSettingChanged = viewModel::updateSetting,
                                        onAutoImportConfigChanged = viewModel::updateAutoImportConfig,
                                        onSelectCardSlotSource = viewModel::updateSelectedCardSlotSource,
                                        onGeotagConfigChanged = viewModel::updateGeotagConfig,
                                        onTetherSaveTargetChanged = viewModel::updateTetherSaveTarget,
                                        onExportLogs = onExportLogs,
                                        onForgetCamera = viewModel::forgetCamera,
                                        onForgetSavedCamera = viewModel::forgetSavedCamera,
                                    )
                                }
                                composable("qr_scanner") {
                                    D.nav("QR scanner screen opened")
                                    dev.pl36.cameralink.feature.qr.QrScannerScreen(
                                        onCredentialsFound = { credentials ->
                                            D.qr("QR credentials found, SSID=${credentials.ssid}, navigating back")
                                            viewModel.connectToCameraViaQr(credentials)
                                            navController.popBackStack()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
