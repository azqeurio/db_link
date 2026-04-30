package dev.dblink.ui

import android.app.Application
import android.content.ContentUris
import android.content.Intent
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dblink.BuildConfig
import dev.dblink.DbLinkApplication
import dev.dblink.R
import dev.dblink.core.config.AppConfig
import dev.dblink.core.config.AppEnvironment
import dev.dblink.core.deepsky.CapturedFrame
import dev.dblink.core.deepsky.DeepSkyLiveStackUiState
import dev.dblink.core.deepsky.DeepSkyPerformanceMode
import dev.dblink.core.deepsky.DeepSkyPresetId
import dev.dblink.core.deepsky.RawDecoder
import dev.dblink.core.geotag.GeoTagStatusNotifier
import dev.dblink.core.geotag.GeoTagMatcher
import dev.dblink.core.geotag.GeoTagTrackingState
import dev.dblink.core.geotag.GeoTagTrackingService
import dev.dblink.core.localization.AppLanguageManager
import dev.dblink.core.logging.D
import dev.dblink.core.geotag.GeoTagLocationManager
import dev.dblink.core.model.CameraWorkspace
import dev.dblink.core.model.CameraNameNormalizer
import dev.dblink.core.model.GeoTagLocationSample
import dev.dblink.core.model.GeoTaggingSnapshot
import dev.dblink.core.model.ActivePropertyPicker
import dev.dblink.core.model.CameraExposureMode
import dev.dblink.core.model.CameraPropertyValues
import dev.dblink.core.model.DriveMode
import dev.dblink.core.model.ModePickerSurface
import dev.dblink.core.model.RemoteRuntimeState
import dev.dblink.core.model.RemoteShootingMode
import dev.dblink.core.model.SavedCameraProfile
import dev.dblink.core.model.SettingItem
import dev.dblink.core.model.TetherSaveTarget
import dev.dblink.core.model.TetherPhoneImportFormat
import dev.dblink.core.model.TimerMode
import dev.dblink.core.model.ConnectMode
import dev.dblink.core.omcapture.OmCaptureAction
import dev.dblink.core.omcapture.OmCaptureCapabilityRegistry
import dev.dblink.core.omcapture.OmCaptureSection
import dev.dblink.core.omcapture.OmCaptureStudioUiState
import dev.dblink.core.permissions.PermissionPlan
import dev.dblink.core.permissions.PermissionPlanner
import dev.dblink.core.preferences.AppPreferencesRepository
import dev.dblink.core.protocol.CameraImage
import dev.dblink.core.protocol.CameraPropertyDesc
import dev.dblink.core.protocol.CameraRepository
import dev.dblink.core.protocol.DefaultCameraRepository
import dev.dblink.core.protocol.PropertyFormatter
import dev.dblink.core.session.CameraSessionEvent
import dev.dblink.core.session.CameraSessionState
import dev.dblink.core.session.CameraSessionStateMachine
import dev.dblink.core.stream.LiveViewMetadata
import dev.dblink.core.stream.LiveViewReceiver
import dev.dblink.core.stream.StreamHealth
import dev.dblink.core.update.AppReleaseInfo
import dev.dblink.core.update.GitHubAppUpdateManager
import dev.dblink.core.usb.OmCaptureUsbCameraEvent
import dev.dblink.core.usb.OmCaptureUsbImportResult
import dev.dblink.core.usb.OmCaptureUsbLibraryItem
import dev.dblink.core.usb.OmCaptureUsbManager
import dev.dblink.core.usb.OmCaptureUsbOperationState
import dev.dblink.core.usb.OmCaptureUsbRuntimeState
import dev.dblink.core.usb.OmCaptureUsbSavedMedia
import dev.dblink.core.usb.PtpConstants
import dev.dblink.core.usb.UsbTetheringProfile
import dev.dblink.core.usb.UsbSessionDomain
import dev.dblink.core.usb.encodeOlympusAfArea
import dev.dblink.core.usb.enumerateOlympusUsbPropertyValues
import dev.dblink.core.usb.formatOlympusApertureValueOnly
import dev.dblink.core.usb.formatOlympusDriveMode
import dev.dblink.core.usb.formatOlympusExposureComp
import dev.dblink.core.usb.formatOlympusExposureMode
import dev.dblink.core.usb.formatOlympusIso
import dev.dblink.core.usb.formatOlympusShutterSpeed
import dev.dblink.core.usb.formatOlympusShutterSpeedValueOnly
import dev.dblink.core.usb.formatOlympusWhiteBalance
import dev.dblink.core.usb.isLegacyOlympusDriveLayout
import dev.dblink.core.usb.olympusApertureFNumber
import dev.dblink.core.usb.olympusExposureModeFromRaw
import dev.dblink.core.usb.olympusFocalLengthMillimeters
import dev.dblink.core.usb.olympusUsbPropertyLabel
import dev.dblink.core.usb.olympusShutterSpeedSeconds
import dev.dblink.core.usb.parseOlympusExposureCompRaw
import dev.dblink.core.usb.usbStorageSelectionLabel
import dev.dblink.core.wifi.CameraWifiManager
import dev.dblink.core.wifi.WifiConnectionState
import dev.dblink.feature.qr.WifiCredentials
import dev.dblink.feature.remote.mapSensorFocusPointToDisplay
import dev.dblink.feature.remote.SYNTHETIC_APERTURE_AUTO
import dev.dblink.feature.remote.SYNTHETIC_SHUTTER_AUTO
import dev.dblink.feature.remote.isSyntheticDialAutoValue
import dev.dblink.feature.transfer.ImageGeoTagInfo
import dev.dblink.feature.transfer.TransferSourceKind
import dev.dblink.feature.transfer.TransferUiState
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.text.SimpleDateFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MEDIA_STORE_PRIMARY_VOLUME = "external_primary"
private const val DEFAULT_CAPTURE_REVIEW_DURATION_SECONDS = 2
private const val MIN_CAPTURE_REVIEW_DURATION_SECONDS = 1
private const val MAX_CAPTURE_REVIEW_DURATION_SECONDS = 5
private const val TOUCH_FOCUS_CONFIRMATION_MOVE_THRESHOLD = 0.035f

data class AutoImportConfig(
    val saveLocation: String = "Pictures/db link",
    val fileFormat: String = "jpeg",       // "jpeg", "jpeg_raw", "raw"
    val importTiming: String = "manual", // "on_connect", "since_launch", "manual"
)

data class GeotagConfig(
    val matchWindowMinutes: Int = 2,       // ±N minutes
    val matchMethod: String = "interpolation", // "interpolation", "nearest"
    val locationSource: String = "device_gps", // "device_gps"
    val writeAltitude: Boolean = true,
)

enum class LibraryCompatibilityMode(val preferenceValue: String) {
    HighSpeed("high_speed"),
    Slow("slow"),
    ;

    companion object {
        fun fromPreferenceValue(value: String?): LibraryCompatibilityMode {
            return entries.firstOrNull { it.preferenceValue == value } ?: HighSpeed
        }
    }
}

enum class UsbTetheringMode(val preferenceValue: String) {
    Om("om"),
    Olympus("olympus"),
    ;

    companion object {
        fun fromPreferenceValue(value: String?): UsbTetheringMode {
            return entries.firstOrNull { it.preferenceValue == value } ?: Om
        }
    }

    fun toUsbTetheringProfile(): UsbTetheringProfile = when (this) {
        Om -> UsbTetheringProfile.Om
        Olympus -> UsbTetheringProfile.Olympus
    }
}

data class MainUiState(
    val workspace: CameraWorkspace,
    val rawProtocolInput: String,
    val appConfig: AppConfig,
    val permissionPlans: List<PermissionPlan>,
    val settings: List<SettingItem>,
    val geoTagging: GeoTaggingSnapshot,
    val remoteRuntime: RemoteRuntimeState,
    val sessionState: CameraSessionState,
    val wifiState: WifiConnectionState = WifiConnectionState.Disconnected,
    val protocolError: String? = null,
    val isRefreshing: Boolean = false,
    val refreshStatus: String = "Ready",
    val showProtocolWorkbench: Boolean = appConfig.showProtocolWorkbench,
    val pendingPermissions: List<String> = emptyList(),
    val permissionsGranted: Map<String, Boolean> = emptyMap(),
    val transferState: TransferUiState = TransferUiState(),
    val hasSavedCamera: Boolean = false,
    val savedCameras: List<SavedCameraProfile> = emptyList(),
    val selectedCameraSsid: String? = null,
    val selectedCardSlotSource: Int? = null,
    val lastCaptureThumbnail: Bitmap? = null,
    val selectedLanguageTag: String = AppLanguageManager.LANGUAGE_SYSTEM,
    val autoImportConfig: AutoImportConfig = AutoImportConfig(),
    val geotagConfig: GeotagConfig = GeotagConfig(),
    val libraryCompatibilityMode: LibraryCompatibilityMode = LibraryCompatibilityMode.HighSpeed,
    val usbTetheringMode: UsbTetheringMode = UsbTetheringMode.Om,
    val tetherSaveTarget: TetherSaveTarget = TetherSaveTarget.SdAndPhone,
    val tetherPhoneImportFormat: TetherPhoneImportFormat = TetherPhoneImportFormat.JpegOnly,
    val captureReviewDurationSeconds: Int = DEFAULT_CAPTURE_REVIEW_DURATION_SECONDS,
    val appUpdate: AppUpdateUiState = AppUpdateUiState(),
    val pendingReconnectHandoffToken: Long? = null,
    val omCaptureUsb: OmCaptureUsbUiState = OmCaptureUsbUiState(),
    val omCaptureStudio: OmCaptureStudioUiState = OmCaptureCapabilityRegistry.defaultState(),
    val deepSkyState: DeepSkyLiveStackUiState = DeepSkyLiveStackUiState(),
)

data class AppUpdateUiState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val updateAvailable: Boolean = false,
    val latestVersion: String? = null,
    val statusLabel: String = "",
)

class MainViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val appConfig: AppConfig = AppEnvironment.current()
    private val repository: CameraRepository = DefaultCameraRepository(appConfig)
    private val preferencesRepository = AppPreferencesRepository(application)
    private val geoTagLocationManager = GeoTagLocationManager(application)
    private val geoTagStatusNotifier = GeoTagStatusNotifier(application)
    private val sessionStateMachine = CameraSessionStateMachine()
    private val cameraWifiManager = CameraWifiManager(application)
    private val cameraBleManager = dev.dblink.core.bluetooth.CameraBleManager(application)
    private val appContainer = (application as DbLinkApplication).appContainer
    private val omCaptureUsbManager = appContainer.omCaptureUsbManager
    private val deepSkyLiveStackCoordinator = appContainer.deepSkyLiveStackCoordinator
    private val liveViewReceiver = LiveViewReceiver(appConfig.liveViewPort)
    private val appUpdateManager = GitHubAppUpdateManager(application)
    private val initialWorkspace = repository.initialWorkspace()
    private val normalizedAppVersionName = BuildConfig.VERSION_NAME.removeSuffix("-debug")

    private var liveViewReceiverJob: Job? = null
    private var liveViewFrameJob: Job? = null
    private var liveViewHealthJob: Job? = null
    private var liveViewMetadataJob: Job? = null
    private var usbLiveViewFrameJob: Job? = null
    private var usbLiveViewRecoveryJob: Job? = null
    private var imageListJob: Job? = null
    private var thumbnailJob: Job? = null
    private var detailPreviewJob: Job? = null
    private var wifiMediaRequestCooldownUntilMs: Long = 0L
    private var downloadProgressJob: Job? = null
    private var propertyApplyJob: Job? = null
    private var propertyLoadJob: Job? = null
    private var propertyRefreshJob: Job? = null
    private var emptyPropertyLoadRetries = 0
    private var reconnectJob: Job? = null
    private var handshakeJob: Job? = null
    private var sessionKeepAliveJob: Job? = null
    private var sessionKeepAliveSuspendedForTransfer = false
    private var touchFocusOverlayJob: Job? = null
    @Volatile private var isCapturing = false
    @Volatile private var isTogglingLiveView = false
    @Volatile private var isAutoConnecting = false
    @Volatile private var isFirstConnectActive = false
    @Volatile private var liveViewRetryCount = 0
    private var intervalJob: Job? = null
    private val propertyLoadGeneration = AtomicLong(0)
    private val transferLoadGeneration = AtomicLong(0)
    private val reconnectGeneration = AtomicLong(0)
    private val reconnectHandoffGeneration = AtomicLong(0)
    private val pendingPropertyTargets = linkedMapOf<String, String>()
    private val pendingPropertyTimestamps = linkedMapOf<String, Long>()
    private companion object {
        /** Maximum age (ms) for a pending property target before it's considered stale.
         *  Reduced from 8s to 5s to prevent prolonged dial mismatch during rapid scrolling
         *  when cascading SocketTimeoutExceptions delay camera confirmation. */
        const val PENDING_PROPERTY_MAX_AGE_MS = 5000L
        const val RECONNECT_BLE_FOUND_STATUS = "OM Digital Solutions camera with built-in Wi-Fi/Bluetooth found."
        const val RECONNECT_WIFI_STATUS = "Connecting Wi-Fi..."
        const val AUTO_IMPORT_MARKER_PREFIX = "auto_import_last_"
        const val TRANSFER_PREVIEW_PREFETCH_LIMIT = 12
        const val WIFI_THUMBNAIL_PREFETCH_LIMIT = 96
        const val WIFI_THUMBNAIL_REQUEST_DELAY_MS = 140L
        const val WIFI_THUMBNAIL_FAILURE_BACKOFF_MS = 650L
        const val WIFI_THUMBNAIL_FAILURE_LIMIT = 5
        const val WIFI_MEDIA_REQUEST_COOLDOWN_MS = 3_000L
        const val WIFI_RESIZED_THUMBNAIL_COOLDOWN_MS = 12_000L
        const val CAPTURE_FOLLOWUP_MAX_ATTEMPTS = 6
        const val CAPTURE_FOLLOWUP_POLL_DELAY_MS = 700L
        const val USB_AUTO_IMPORT_RETRY_MS = 250L
        const val USB_AUTO_IMPORT_COMPLETED_HANDLE_LIMIT = 24
        const val USB_LIBRARY_CACHE_TTL_MS = 15_000L
        const val DEEP_SKY_PROTECTION_PROBE_BACKOFF_MS = 15_000L
        const val USB_AF_TARGET_EVENT_PROP = PtpConstants.OlympusProp.AFTargetArea
        const val USB_MODE_DIAL_EVENT_PROP = 0xD062
        const val USB_MODE_DIAL_REFRESH_SUPPRESS_MS = 800L
        const val USB_CAPTURE_PROPERTY_SETTLE_MS = 6_000L
        const val USB_AUTO_IMPORT_BATCH_WINDOW_MS = 450L
        const val USB_TRANSFER_THUMBNAIL_PREFETCH_LIMIT = 4
        const val USB_TRANSFER_THUMBNAIL_PREFETCH_DELAY_MS = 60L
        const val EMPTY_PROPERTY_LOAD_RETRY_LIMIT = 5
        const val USB_OM1_ISO_PROP = 0xD005
        const val USB_INTERVAL_COUNT_PROP = 0xD0CC
        const val USB_INTERVAL_SECONDS_PROP = 0xD0CD
        const val USB_TIMER_DELAY_PROP = 0xD0EC
    }
    private var queuedRefreshAfterHandshake = false
    private var suppressWifiAutoRefresh = false
    @Volatile private var recoverLiveViewAfterReconnect = false
    @Volatile private var usbLiveViewDesired = false
    /** True while reconnect restoration (property reload + live view recovery) is in progress.
     *  Auto-import is blocked until this is cleared so reconnect can finish safely. */
    @Volatile private var reconnectRestorationActive = false
    @Volatile private var autoImportArmed = false
    private var pendingLastCapturePreviewOpen = false
    private var pendingLastCapturePreviewFileName: String? = null
    private var pendingAutoImportMarker: String? = null
    private var pendingAutoImportMarkerSsid: String? = null
    private val sessionLaunchImportBaselineBySsid = linkedMapOf<String, String>()
    private var usbLibraryCache: List<OmCaptureUsbLibraryItem> = emptyList()
    private var usbLibraryCacheUpdatedAtMs: Long = 0L
    private val previewThumbnailUpgrades = linkedSetOf<String>()
    private val wifiThumbnail404Paths = linkedSetOf<String>()
    private val wifiPreview404Paths = linkedSetOf<String>()
    private val wifiResizedThumbnail404Paths = linkedSetOf<String>()
    private var wifiResizedThumbnailCooldownUntilMs: Long = 0L
    private var activePlayTargetSlot: Int? = null
    private var latestLiveViewMetadata: LiveViewMetadata? = null
    private var lastRawApertureOptions: List<String> = emptyList()
    private var latestAppRelease: AppReleaseInfo? = null
    private var downloadedUpdateApk: File? = null
    private var pendingReconnectWorkspace: CameraWorkspace? = null
    private var pendingReconnectRestoreLiveView = false
    private var pendingReconnectCompletion: CompletableDeferred<Unit>? = null
    private var lastAutoImportedUsbHandle: Int? = null
    private val pendingUsbAutoImportHandles = linkedSetOf<Int>()
    private val completedUsbAutoImportHandles = linkedSetOf<Int>()
    private val usbSavedMediaByFileName = linkedMapOf<String, OmCaptureUsbSavedMedia>()
    private val usbSavedMediaHistory = linkedMapOf<String, UsbSavedMediaRecord>()
    private val usbSavedMediaByTransferPath = linkedMapOf<String, OmCaptureUsbSavedMedia>()
    private var usbCaptureEventMonitoringEnabled = false
    private var usbAutoImportJob: Job? = null
    private var deepSkyAutoCaptureJob: Job? = null
    private var usbPropertySyncJob: Job? = null
    private var usbModeDialRefreshJob: Job? = null
    private var usbLiveViewStartupRefreshJob: Job? = null
    @Volatile private var usbTransferThumbnailPrefetchActive = false
    private val pendingUsbExtraPropertyJobs = linkedMapOf<Int, Job>()
    private var usbCaptureSettleUntilMs = 0L
    private var nextDeepSkyProtectionProbeAtMs = 0L
    private val pendingUsbPropertyRefreshCodes = linkedSetOf<Int>()
    private val _uiState = MutableStateFlow(
        MainUiState(
            workspace = initialWorkspace,
            rawProtocolInput = repository.sampleCommandListXml(),
            appConfig = appConfig,
            permissionPlans = PermissionPlanner.plans,
            settings = initialWorkspace.settings,
            geoTagging = GeoTaggingSnapshot(),
            remoteRuntime = RemoteRuntimeState(),
            sessionState = CameraSessionState.Idle,
            selectedLanguageTag = AppLanguageManager.selectedLanguageTag(application),
        ),
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // Separate flow for live view frames — avoids recomposing the entire UI at 30fps.
    private val _liveViewFrame = MutableStateFlow<Bitmap?>(null)
    val liveViewFrame: StateFlow<Bitmap?> = _liveViewFrame.asStateFlow()
    val usbCameraProperties = omCaptureUsbManager.cameraProperties

    private inline fun updateUiState(transform: (MainUiState) -> MainUiState) {
        _uiState.update(transform)
    }

    private inline fun updateRemoteRuntime(transform: (RemoteRuntimeState) -> RemoteRuntimeState) {
        updateUiState { current ->
            current.copy(remoteRuntime = transform(current.remoteRuntime))
        }
    }

    private fun usbPropHex(propCode: Int): String = "0x${propCode.toString(16).uppercase(Locale.US)}"

    private fun recordQaUiAction(title: String, detail: String = "") {
        val detailSuffix = if (detail.isNotBlank()) " | $detail" else ""
        D.action("[UI] $title$detailSuffix")
    }

    private fun recordQaCameraEvent(title: String, detail: String = "") {
        val detailSuffix = if (detail.isNotBlank()) " | $detail" else ""
        D.action("[Camera] $title$detailSuffix")
    }

    private fun clearUsbAutoImportTracking(reason: String, clearCompleted: Boolean = true) {
        if (
            pendingUsbAutoImportHandles.isEmpty() &&
            completedUsbAutoImportHandles.isEmpty() &&
            lastAutoImportedUsbHandle == null
        ) {
            return
        }
        D.usb(
            "Clearing OM USB auto-import tracking ($reason) " +
                "pending=${pendingUsbAutoImportHandles.size} completed=${completedUsbAutoImportHandles.size}",
        )
        lastAutoImportedUsbHandle = null
        pendingUsbAutoImportHandles.clear()
        if (clearCompleted) {
            completedUsbAutoImportHandles.clear()
        }
    }

    init {
        D.lifecycle("MainViewModel init, config=$appConfig")
        omCaptureUsbManager.setUsbTetheringProfile(_uiState.value.usbTetheringMode.toUsbTetheringProfile())
        viewModelScope.launch {
            loadPersistedUiState()
        }
        viewModelScope.launch {
            delay(2_500L)
            checkForAppUpdate(silent = true)
        }
        omCaptureUsbManager.runtimeState
            .onEach { runtimeState ->
                val currentState = _uiState.value
                val usbSessionJustBecameAvailable =
                    currentState.omCaptureUsb.summary == null &&
                        runtimeState.summary != null
                // Drop live view from UI when the manager says it's no longer active
                // — regardless of whether it ended in error or just stopped.
                val shouldDropUsbLiveView =
                    isUsbModeSurface(currentState.remoteRuntime.modePickerSurface) &&
                        currentState.remoteRuntime.liveViewActive &&
                        !omCaptureUsbManager.isLiveViewActive
                if (shouldDropUsbLiveView) {
                    _liveViewFrame.value = null
                }
                refreshOmCaptureStudioState(
                    currentState.copy(
                        remoteRuntime = if (shouldDropUsbLiveView) {
                            currentState.remoteRuntime.copy(
                                liveViewActive = false,
                                statusLabel = runtimeState.statusLabel,
                            )
                        } else {
                            currentState.remoteRuntime
                        },
                        omCaptureUsb = runtimeState.toUiState(),
                    ),
                )
                if (usbSessionJustBecameAvailable) {
                    clearUsbLibraryCache()
                }
                if (shouldDropUsbLiveView && canAttemptUsbLiveViewRecovery(currentState)) {
                    scheduleUsbLiveViewRecovery(
                        reason = "runtime-${runtimeState.operationState.name.lowercase()}",
                    )
                }
                if (
                    runtimeState.summary == null &&
                    (
                        runtimeState.operationState == OmCaptureUsbOperationState.Idle ||
                            runtimeState.operationState == OmCaptureUsbOperationState.Error
                        )
                ) {
                    if (runtimeState.operationState == OmCaptureUsbOperationState.Error) {
                        usbAutoImportJob?.cancel()
                    }
                    clearUsbAutoImportTracking(
                        reason = "usb-runtime-${runtimeState.operationState.name.lowercase()}",
                    )
                    clearUsbLibraryCache()
                    resetTransferSourceFromUsbToWifi()
                }
            }
            .launchIn(viewModelScope)
        omCaptureUsbManager.cameraProperties
            .onEach { snapshot ->
                syncRemoteRuntimeFromUsbProperties(snapshot)
            }
            .launchIn(viewModelScope)
        omCaptureUsbManager.cameraEvents
            .onEach { event -> handleOmCaptureUsbCameraEvent(event) }
            .launchIn(viewModelScope)
        GeoTagTrackingService.state
            .onEach(::syncGeoTagTrackingState)
            .launchIn(viewModelScope)
        deepSkyLiveStackCoordinator.state
            .onEach { deepSkyState ->
                refreshOmCaptureStudioState(
                    _uiState.value.copy(deepSkyState = deepSkyState),
                )
            }
            .launchIn(viewModelScope)
        observeDownloadProgress()
        cameraWifiManager.observeWifiState()
            .onEach { wifiState ->
                val selectedCameraSsid = _uiState.value.selectedCameraSsid
                val effectiveWifiState = when {
                    wifiState is WifiConnectionState.CameraWifi &&
                        !selectedCameraSsid.isNullOrBlank() &&
                        !wifiState.ssid.isNullOrBlank() &&
                        !wifiState.ssid.equals(selectedCameraSsid, ignoreCase = true) -> {
                        D.wifi("Camera WiFi belongs to non-selected camera (${wifiState.ssid}); treating it as OtherWifi")
                        WifiConnectionState.OtherWifi(wifiState.ssid)
                    }
                    else -> wifiState
                }
                D.wifi("WiFi state changed: $effectiveWifiState, currentSession=${_uiState.value.sessionState}")
                val ignoreTransientFirstConnectHandoff =
                    isFirstConnectActive &&
                        (effectiveWifiState is WifiConnectionState.OtherWifi || effectiveWifiState == WifiConnectionState.Disconnected) &&
                        isWifiHandoffInProgress()
                if (ignoreTransientFirstConnectHandoff) {
                    D.wifi(
                        "Ignoring transient first-connect WiFi handoff state: $effectiveWifiState " +
                            "(requestNetwork still in progress)",
                    )
                    return@onEach
                }
                updateUiState { it.copy(wifiState = effectiveWifiState) }
                when (effectiveWifiState) {
                    is WifiConnectionState.CameraWifi -> {
                        D.wifi("Camera WiFi detected, session=${_uiState.value.sessionState}")
                        if (suppressWifiAutoRefresh || reconnectJob?.isActive == true || isFirstConnectActive) {
                            D.wifi("Skipping WiFi observer auto-refresh while reconnect/first-connect flow is active")
                            return@onEach
                        }
                        // Only auto-connect if this is a direct WiFi association (not a saved-camera reconnect).
                        // For reconnect, the user must press the reconnect button manually.
                        if (_uiState.value.sessionState is CameraSessionState.Idle ||
                            _uiState.value.sessionState is CameraSessionState.Error
                        ) {
                            // Check if we're already on camera WiFi (user manually connected via system settings)
                            // In that case, proceed with handshake only — no BLE wake, no reconnect flow.
                            D.wifi("Camera WiFi detected while idle — starting handshake (not reconnect)")
                            startCameraHandshake(refreshStatus = "Connecting to camera...")
                        }
                    }
                    is WifiConnectionState.OtherWifi,
                    WifiConnectionState.Disconnected -> {
                        if (isWifiHandoffInProgress()) {
                            D.wifi("Ignoring transient WiFi loss while camera handoff is still in flight")
                            return@onEach
                        }
                        val wasLiveViewActive = _uiState.value.remoteRuntime.liveViewActive
                        val hadActiveSession = _uiState.value.sessionState !is CameraSessionState.Idle
                        pendingPropertyTargets.clear(); pendingPropertyTimestamps.clear()
                        thumbnailJob?.cancel()
                        thumbnailJob = null
                        detailPreviewJob?.cancel()
                        detailPreviewJob = null
                        autoImportArmed = false
                        if (hadActiveSession) {
                            D.wifi("Camera WiFi lost, resetting session from ${_uiState.value.sessionState}")
                            stopSessionKeepAlive()
                            stopLiveViewInternal()
                            updateUiState { current -> current.copy(
                                sessionState = CameraSessionState.Idle,
                                refreshStatus = "Camera WiFi disconnected",
                                protocolError = null,
                                remoteRuntime = current.remoteRuntime.copy(
                                    liveViewActive = false,
                                    focusHeld = false,
                                    propertiesLoaded = false,
                                    statusLabel = "Disconnected — press Reconnect to restore session",
                                ),
                                transferState = current.transferState.copy(
                                    isLoading = false,
                                    isDownloading = false,
                                ),
                            ) }
                            // Leave reconnect as an explicit user action.
                            val savedSsid = _uiState.value.selectedCameraSsid
                            D.reconnect("RECONNECT_AUTO suppressed on wifi disconnect (savedSsid=$savedSsid, liveView=$wasLiveViewActive)")
                            // Remember live-view state so manual reconnect can restore it
                            recoverLiveViewAfterReconnect = wasLiveViewActive
                        }
                    }
                    WifiConnectionState.WifiOff -> {
                        pendingPropertyTargets.clear(); pendingPropertyTimestamps.clear()
                        thumbnailJob?.cancel()
                        thumbnailJob = null
                        detailPreviewJob?.cancel()
                        detailPreviewJob = null
                        autoImportArmed = false
                        if (_uiState.value.sessionState !is CameraSessionState.Idle) {
                            D.wifi("WiFi off, resetting session from ${_uiState.value.sessionState}")
                            stopSessionKeepAlive()
                            stopLiveViewInternal()
                            updateUiState { current -> current.copy(
                                sessionState = CameraSessionState.Idle,
                                refreshStatus = "WiFi is off",
                                protocolError = null,
                                remoteRuntime = current.remoteRuntime.copy(
                                    liveViewActive = false,
                                    focusHeld = false,
                                    propertiesLoaded = false,
                                    statusLabel = "WiFi is off",
                                ),
                                transferState = current.transferState.copy(
                                    isLoading = false,
                                    isDownloading = false,
                                ),
                            ) }
                        }
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    override fun onCleared() {
        D.lifecycle("MainViewModel onCleared")
        stopSessionKeepAlive()
        stopLiveViewInternal()
        geoTagStatusNotifier.cancel()
        cameraBleManager.disconnect()
        imageListJob?.cancel()
        thumbnailJob?.cancel()
        detailPreviewJob?.cancel()
        downloadProgressJob?.cancel()
        propertyApplyJob?.cancel()
        propertyLoadJob?.cancel()
        propertyRefreshJob?.cancel()
        reconnectJob?.cancel()
        handshakeJob?.cancel()
        touchFocusOverlayJob?.cancel()
        intervalJob?.cancel()
        deepSkyAutoCaptureJob?.cancel()
        usbAutoImportJob?.cancel()
        usbPropertySyncJob?.cancel()
        usbModeDialRefreshJob?.cancel()
        usbLiveViewStartupRefreshJob?.cancel()
        pendingUsbAutoImportHandles.clear()
        completedUsbAutoImportHandles.clear()
        clearPendingReconnectHandoffState(cancelCompletion = true)
        super.onCleared()
    }

    fun onNavigateAwayFromRemote() {
        val leavingUsbRemote = isUsbRemoteTransportActive() && _uiState.value.omCaptureUsb.summary != null
        if (!leavingUsbRemote) {
            suppressWifiAutoRefresh = false
            propertyApplyJob?.cancel()
            propertyApplyJob = null
            propertyLoadJob?.cancel()
            propertyLoadJob = null
            propertyRefreshJob?.cancel()
            propertyRefreshJob = null
            updateUiState { current -> current.copy(
                remoteRuntime = current.remoteRuntime.copy(
                    activePicker = ActivePropertyPicker.None,
                    modePickerSurface = ModePickerSurface.CameraModes,
                ),
            ) }
        }
        if (_uiState.value.remoteRuntime.liveViewActive) {
            D.ui("Navigating away from Remote — stopping live view")
            stopLiveViewInternal()
            if (!leavingUsbRemote) {
                viewModelScope.launch {
                    repository.stopLiveView()
                }
            }
            if (leavingUsbRemote) {
                refreshOmCaptureStudioState(
                    base = _uiState.value.copy(
                        remoteRuntime = _uiState.value.remoteRuntime.copy(
                            liveViewActive = false,
                            focusHeld = false,
                            statusLabel = "",
                        ),
                    ),
                )
            } else {
                updateUiState { current -> current.copy(
                    remoteRuntime = current.remoteRuntime.copy(
                        liveViewActive = false,
                        focusHeld = false,
                        statusLabel = "",
                    ),
                    sessionState = sessionStateMachine.reduce(
                        current.sessionState,
                        CameraSessionEvent.Connected(
                            method = current.workspace.camera.connectionMethod,
                            mode = current.workspace.camera.connectMode,
                        ),
                    ),
                ) }
            }
        }
    }

    fun getRequiredPermissions(): Array<String> {
        return getStartupForegroundPermissions()
    }

    fun getStartupForegroundPermissions(): Array<String> {
        return PermissionPlanner.plans
            .flatMap { it.permissions }
            .distinct()
            .filterNot { it == "android.permission.ACCESS_BACKGROUND_LOCATION" }
            .filter(::isRuntimePermissionSupported)
            .filterNot(::isPermissionGranted)
            .toTypedArray()
    }

    fun getStartupBackgroundLocationPermissions(): Array<String> {
        val permission = "android.permission.ACCESS_BACKGROUND_LOCATION"
        if (!isRuntimePermissionSupported(permission) || isPermissionGranted(permission)) {
            return emptyArray()
        }
        val foregroundLocationGranted =
            isPermissionGranted("android.permission.ACCESS_FINE_LOCATION") ||
                isPermissionGranted("android.permission.ACCESS_COARSE_LOCATION")
        return if (foregroundLocationGranted) arrayOf(permission) else emptyArray()
    }

    private fun isRuntimePermissionSupported(permission: String): Boolean {
        return when (permission) {
            "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT" ->
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            "android.permission.NEARBY_WIFI_DEVICES",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO" ->
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED" ->
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            "android.permission.READ_EXTERNAL_STORAGE" ->
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2
            "android.permission.ACCESS_BACKGROUND_LOCATION" ->
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            else -> true
        }
    }

    private fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            getApplication(),
            permission,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun onPermissionsResult(results: Map<String, Boolean>) {
        D.perm("Permission results: granted=${results.filter { it.value }.keys}, denied=${results.filter { !it.value }.keys}")
        updateUiState { current -> current.copy(
            permissionsGranted = current.permissionsGranted + results,
            pendingPermissions = emptyList(),
        ) }
        val anyGranted = results.values.any { it }
        if (anyGranted) {
            D.perm("Some permissions granted, triggering refresh")
            refresh()
        }
    }

    fun refreshOmCaptureUsb() {
        recordQaUiAction(
            title = "Refresh USB tether",
            detail = "surface=${_uiState.value.remoteRuntime.modePickerSurface}",
        )
        viewModelScope.launch {
            val summary = omCaptureUsbManager.inspectConnectedCamera().getOrElse { throwable ->
                D.err("USB", "Failed to inspect OM USB tether", throwable)
                return@launch
            }
            refreshUsbProperties(includeExtras = false)
            val runtime = _uiState.value.remoteRuntime
            if (
                summary.supportsLiveView &&
                usbLiveViewDesired &&
                runtime.modePickerSurface != ModePickerSurface.TetherRetry &&
                isUsbModeSurface(runtime.modePickerSurface) &&
                !omCaptureUsbManager.isLiveViewActive
            ) {
                startUsbLiveViewSession(waitingStatus = "Waiting for USB live view frame...")
            }
        }
    }

    private fun setUsbLiveViewDesired(enabled: Boolean, reason: String) {
        usbLiveViewDesired = enabled
        if (!enabled) {
            usbLiveViewRecoveryJob?.cancel()
            usbLiveViewRecoveryJob = null
        }
        D.usb("USB live view desired=$enabled reason=$reason")
    }

    private fun canAttemptUsbLiveViewRecovery(
        state: MainUiState = _uiState.value,
    ): Boolean {
        return usbLiveViewDesired &&
            isUsbModeSurface(state.remoteRuntime.modePickerSurface) &&
            state.remoteRuntime.modePickerSurface != ModePickerSurface.TetherRetry
    }

    private fun scheduleUsbLiveViewRecovery(
        reason: String,
        delayMs: Long = 350L,
        waitingStatus: String = "Reconnecting live view...",
    ) {
        if (!canAttemptUsbLiveViewRecovery()) {
            return
        }
        usbLiveViewRecoveryJob?.cancel()
        usbLiveViewRecoveryJob = viewModelScope.launch {
            delay(delayMs)
            repeat(8) { attempt ->
                val currentState = _uiState.value
                if (!canAttemptUsbLiveViewRecovery(currentState)) {
                    return@launch
                }
                if (omCaptureUsbManager.isLiveViewActive) {
                    return@launch
                }
                if (currentState.omCaptureUsb.isBusy) {
                    D.usb(
                        "USB live view recovery waiting for idle " +
                            "reason=$reason attempt=${attempt + 1}/8",
                    )
                    delay(250L)
                    return@repeat
                }
                val summary = currentState.omCaptureUsb.summary
                val shouldRefreshSession =
                    summary == null ||
                        currentState.omCaptureUsb.operationState == OmCaptureUsbOperationState.Error
                if (!shouldRefreshSession && summary.supportsLiveView) {
                    D.usb(
                        "USB live view recovery restarting active session " +
                            "reason=$reason attempt=${attempt + 1}/8",
                    )
                    startUsbLiveViewSession(waitingStatus = waitingStatus)
                } else {
                    D.usb(
                        "USB live view recovery refreshing USB session " +
                            "reason=$reason attempt=${attempt + 1}/8",
                    )
                    refreshOmCaptureUsb()
                }
                return@launch
            }
            D.usb("USB live view recovery timed out waiting for an idle USB session reason=$reason")
        }
    }

    fun captureOmCaptureUsbPhoto() {
        recordQaUiAction(
            title = "Capture OM USB photo",
            detail = "saveTarget=${_uiState.value.tetherSaveTarget.preferenceValue}",
        )
        if (_uiState.value.tetherSaveTarget == TetherSaveTarget.SdCard) {
            viewModelScope.launch {
                omCaptureUsbManager.captureToCameraStorage()
            }
            return
        }
        val currentState = _uiState.value
        refreshOmCaptureStudioState(
            currentState.copy(
                remoteRuntime = currentState.remoteRuntime.copy(
                    statusLabel = "Importing new capture in background...",
                ),
            ),
        )
        dev.dblink.core.download.ImageDownloadService.startUsbCaptureAndImport(
            context = getApplication(),
            importFormat = currentState.tetherPhoneImportFormat,
            saveLocation = currentState.autoImportConfig.saveLocation,
        )
    }

    private fun refreshUsbProperties(includeExtras: Boolean = true) {
        recordQaUiAction(
            title = "Refresh USB properties",
            detail = "includeExtras=$includeExtras",
        )
        viewModelScope.launch {
            omCaptureUsbManager.refreshCameraProperties(includeExtras = includeExtras).onFailure { throwable ->
                D.err("USB", "Failed to refresh USB camera properties", throwable)
            }
        }
    }

    private fun setUsbProperty(propCode: Int, value: Long) {
        recordQaUiAction(
            title = "Set USB property",
            detail = "${olympusUsbPropertyLabel(propCode)}=${value} (${usbPropHex(propCode)})",
        )
        viewModelScope.launch {
            omCaptureUsbManager.setCameraProperty(propCode, value).onFailure { throwable ->
                D.err("USB", "Failed to set USB property 0x${propCode.toString(16)}=$value", throwable)
            }
        }
    }

    private fun currentUsbIsoPropCode(
        snapshot: OmCaptureUsbManager.CameraPropertiesSnapshot = usbCameraProperties.value,
    ): Int {
        return snapshot.iso?.propCode ?: PtpConstants.OlympusProp.ISOSpeed
    }

    private fun scheduleUsbExtraPropertyUpdate(
        propCode: Int,
        value: Long,
        statusLabel: String,
    ) {
        recordQaUiAction(
            title = "Queue USB property update",
            detail = "$statusLabel -> ${olympusUsbPropertyLabel(propCode)}=$value (${usbPropHex(propCode)})",
        )
        if (!isUsbRemoteTransportActive() || _uiState.value.omCaptureUsb.summary == null) {
            return
        }
        pendingUsbExtraPropertyJobs.remove(propCode)?.cancel()
        val job = viewModelScope.launch {
            delay(140L)
            try {
                omCaptureUsbManager.setCameraProperty(propCode, value)
                    .onFailure { error ->
                        D.err("USB", "Failed to set USB property 0x${propCode.toString(16)}=$value", error)
                        updateUiState { current -> current.copy(
                            remoteRuntime = current.remoteRuntime.copy(
                                statusLabel = "$statusLabel failed: ${error.message}",
                            ),
                        ) }
                    }
            } finally {
                if (pendingUsbExtraPropertyJobs[propCode] === this.coroutineContext[Job]) {
                    pendingUsbExtraPropertyJobs.remove(propCode)
                }
            }
        }
        pendingUsbExtraPropertyJobs[propCode] = job
    }

    private fun manualFocusDrive(steps: Int) {
        recordQaUiAction(title = "Manual focus drive", detail = "steps=$steps")
        viewModelScope.launch {
            omCaptureUsbManager.manualFocusDrive(steps).onFailure { throwable ->
                D.err("USB", "Failed to drive MF steps=$steps", throwable)
            }
        }
    }

    private fun captureWhileLiveView() {
        recordQaUiAction(title = "Capture while live view", detail = "usb=${isUsbRemoteTransportActive()}")
        viewModelScope.launch {
            omCaptureUsbManager.captureWhileLiveView().onFailure { throwable ->
                D.err("USB", "Capture while live view failed", throwable)
            }
        }
    }

    private fun isUsbModeSurface(surface: ModePickerSurface): Boolean {
        return surface == ModePickerSurface.Tether ||
            surface == ModePickerSurface.TetherRetry ||
            surface == ModePickerSurface.DeepSky
    }

    private fun isUsbRemoteTransportActive(): Boolean {
        return isUsbModeSurface(_uiState.value.remoteRuntime.modePickerSurface)
    }

    private fun preserveModePickerSurface(
        currentSurface: ModePickerSurface,
        closePicker: Boolean,
    ): ModePickerSurface {
        return if (isUsbModeSurface(currentSurface)) {
            currentSurface
        } else if (closePicker) {
            ModePickerSurface.CameraModes
        } else {
            currentSurface
        }
    }

    private fun syncRemoteRuntimeFromUsbProperties(
        snapshot: OmCaptureUsbManager.CameraPropertiesSnapshot,
    ) {
        val baseState = _uiState.value
        if (snapshot.allProperties().isEmpty()) {
            return
        }
        if (baseState.omCaptureUsb.summary == null) {
            return
        }

        val runtime = baseState.remoteRuntime
        val reportedExposureMode = snapshot.exposureMode?.let(::usbExposureModeFromProperty)
        val resolvedExposureMode = resolveUsbExposureMode(
            reportedMode = reportedExposureMode,
            reportedModePropCode = snapshot.exposureMode?.propCode,
            apertureProperty = snapshot.aperture,
            shutterProperty = snapshot.shutterSpeed,
        )
        val nextActivePicker = coerceActivePickerForMode(runtime.activePicker, resolvedExposureMode)
        val apertureValues = snapshot.aperture?.let {
            usbAperturePropertyValues(property = it)
        }
            ?: runtime.aperture
        val shutterValues = snapshot.shutterSpeed?.let { usbRemotePropertyValues("shutspeedvalue", it) }
            ?: runtime.shutterSpeed
        val isoValues = snapshot.iso?.let { usbRemotePropertyValues("isospeedvalue", it) }
            ?: runtime.iso
        val whiteBalanceValues = snapshot.whiteBalance?.let { usbRemotePropertyValues("wbvalue", it) }
            ?: runtime.whiteBalance
        val exposureValues = snapshot.exposureComp?.let { usbRemotePropertyValues("expcomp", it) }
            ?: runtime.exposureCompensationValues
        val takeModeValues = snapshot.exposureMode?.let(::usbTakeModeValues)
            ?.let { normalizeUsbTakeModeValues(it, resolvedExposureMode) }
            ?: runtime.takeMode.copy(currentValue = resolvedExposureMode.label)
        val driveModeValues = snapshot.driveMode?.let(::usbDriveModeValues)
            ?: runtime.driveModeValues
        val (driveMode, timerMode) = snapshot.driveMode
            ?.let { property ->
                driveAndTimerFromUsbRaw(
                    rawValue = property.currentValue,
                    options = enumerateUsbPropertyValues(property),
                )
            }
            ?: driveAndTimerFromRaw(driveModeValues.currentValue)
        val focalLengthMm = snapshot.findProperty(PtpConstants.Prop.FocalLength)
            ?.currentValue
            ?.let(::olympusFocalLengthMillimeters)
        val apertureFNumber = snapshot.aperture
            ?.currentValue
            ?.let(::olympusApertureFNumber)
        val exposureSeconds = snapshot.shutterSpeed
            ?.currentValue
            ?.let(::olympusShutterSpeedSeconds)
        val intervalCount = snapshot.findProperty(USB_INTERVAL_COUNT_PROP)
            ?.currentValue
            ?.toInt()
            ?.coerceAtLeast(1)
            ?: runtime.intervalCount
        val intervalSeconds = snapshot.findProperty(USB_INTERVAL_SECONDS_PROP)
            ?.currentValue
            ?.toInt()
            ?.coerceAtLeast(1)
            ?: runtime.intervalSeconds
        val timerDelay = snapshot.findProperty(USB_TIMER_DELAY_PROP)
            ?.currentValue
            ?.toInt()
            ?.coerceAtLeast(1)
            ?: runtime.timerDelay
        val apertureEnabled = (snapshot.aperture?.isReadOnly != true) &&
            isApertureControlEnabled(resolvedExposureMode)
        val shutterEnabled = (snapshot.shutterSpeed?.isReadOnly != true) &&
            isShutterControlEnabled(resolvedExposureMode)
        val nextRuntime = runtime.copy(
            aperture = apertureValues.copy(
                enabled = apertureEnabled,
            ),
            shutterSpeed = shutterValues.copy(
                enabled = shutterEnabled,
            ),
            iso = isoValues,
            whiteBalance = whiteBalanceValues,
            exposureCompensation = parseOlympusExposureCompRaw(exposureValues.currentValue)
                ?.toInt()
                ?.toShort()
                ?.toInt()
                ?.div(10f)
                ?: runtime.exposureCompensation,
            exposureCompensationValues = exposureValues,
            driveModeValues = driveModeValues,
            driveMode = driveMode,
            timerMode = timerMode,
            intervalCount = intervalCount,
            intervalSeconds = intervalSeconds,
            timerDelay = timerDelay,
            exposureMode = resolvedExposureMode,
            takeMode = takeModeValues,
            activePicker = nextActivePicker,
            modePickerSurface = preserveModePickerSurface(runtime.modePickerSurface, closePicker = false),
            propertiesLoaded = true,
            statusLabel = if (runtime.liveViewActive && runtime.statusLabel.isBlank()) {
                "USB live view"
            } else {
                runtime.statusLabel
            },
        )
        deepSkyLiveStackCoordinator.updateLiveViewCaptureContext(
            focalLengthMm = focalLengthMm,
            aperture = apertureFNumber,
            exposureSec = exposureSeconds,
        )
        refreshOmCaptureStudioState(base = baseState.copy(remoteRuntime = nextRuntime))
    }

    private fun usbRemotePropertyValues(
        propName: String,
        property: OmCaptureUsbManager.CameraPropertyState,
    ): CameraPropertyValues {
        val rawOptions = enumerateUsbPropertyValues(property)
        val currentValue = formatUsbRemoteValue(propName, property.currentValue, rawOptions)
        val allowedValues = rawOptions
            .map { formatUsbRemoteValue(propName, it, rawOptions) }
            .filter { it.isNotBlank() }
        val mergedValues = buildList {
            addAll(allowedValues)
            if (currentValue.isNotBlank() && currentValue !in allowedValues) {
                add(currentValue)
            }
        }.distinct()
        return CameraPropertyValues(
            currentValue = currentValue,
            availableValues = mergedValues,
            autoActive = propName == "isospeedvalue" &&
                PropertyFormatter.isAutoValue(propName, currentValue),
            enabled = !property.isReadOnly,
        )
    }

    private fun usbAperturePropertyValues(
        property: OmCaptureUsbManager.CameraPropertyState,
    ): CameraPropertyValues {
        val rawOptions = enumerateUsbPropertyValues(property)
        val currentValue = formatUsbRemoteValue("focalvalue", property.currentValue, rawOptions)
        val allowedValues = rawOptions
            .map { formatUsbRemoteValue("focalvalue", it, rawOptions) }
            .filter { it.isNotBlank() }
            .distinct()
        lastRawApertureOptions = allowedValues
        // libgphoto2's Olympus aperture path uses the descriptor enumeration
        // directly and does not synthesize a min/max range from raw bounds.
        // If the camera does not provide enum choices, keep only the current
        // value instead of preserving an older bad 1-90 style list.
        val filteredValues = allowedValues.ifEmpty {
            listOfNotNull(currentValue.takeIf { it.isNotBlank() })
        }
        return CameraPropertyValues(
            currentValue = currentValue,
            availableValues = filteredValues,
            enabled = !property.isReadOnly,
        )
    }

    private fun usbTakeModeValues(
        property: OmCaptureUsbManager.CameraPropertyState,
    ): CameraPropertyValues {
        val currentValue = usbExposureModeLabel(property)
        val availableValues = enumerateUsbPropertyValues(property)
            .mapNotNull { raw ->
                usbExposureModeLabel(property.propCode, raw).takeIf { it.isNotBlank() }
            }
            .ifEmpty { listOf(currentValue) }
            .distinct()
        return CameraPropertyValues(
            currentValue = currentValue,
            availableValues = availableValues,
            enabled = !property.isReadOnly,
        )
    }

    private fun usbDriveModeValues(
        property: OmCaptureUsbManager.CameraPropertyState,
    ): CameraPropertyValues {
        val rawOptions = enumerateUsbPropertyValues(property)
        val currentValue = usbDriveModeLabel(property.currentValue, rawOptions)
        val availableValues = rawOptions
            .map { raw -> usbDriveModeLabel(raw, rawOptions) }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(currentValue) }
            .distinct()
        return CameraPropertyValues(
            currentValue = currentValue,
            availableValues = availableValues,
            enabled = !property.isReadOnly,
        )
    }

    private fun formatUsbRemoteValue(
        propName: String,
        rawValue: Long,
        options: List<Long> = emptyList(),
    ): String {
        return when (propName) {
            "focalvalue" -> formatOlympusApertureValueOnly(rawValue)
            "shutspeedvalue" -> formatOlympusShutterSpeed(rawValue)
            "isospeedvalue" -> formatOlympusIso(rawValue)
            "expcomp" -> formatOlympusExposureComp(rawValue)
            "wbvalue" -> formatOlympusWhiteBalance(rawValue)
            "drivemode" -> formatOlympusDriveMode(rawValue, options)
            "takemode" -> formatOlympusExposureMode(rawValue)
            else -> rawValue.toString()
        }
    }

    private fun enumerateUsbPropertyValues(
        property: OmCaptureUsbManager.CameraPropertyState,
    ): List<Long> {
        return enumerateOlympusUsbPropertyValues(property)
    }

    private fun usbExposureModeFromRaw(
        propCode: Int,
        rawValue: Long,
    ): CameraExposureMode? = olympusExposureModeFromRaw(propCode, rawValue)

    private fun usbExposureModeFromProperty(
        property: OmCaptureUsbManager.CameraPropertyState,
    ): CameraExposureMode? = usbExposureModeFromRaw(property.propCode, property.currentValue)

    private fun usbExposureModeLabel(
        property: OmCaptureUsbManager.CameraPropertyState,
    ): String = usbExposureModeLabel(property.propCode, property.currentValue)

    private fun usbExposureModeLabel(
        propCode: Int,
        rawValue: Long,
    ): String = formatOlympusExposureMode(propCode, rawValue)

    private fun usbWhiteBalanceLabel(rawValue: Long): String = formatOlympusWhiteBalance(rawValue)

    private fun usbDriveModeLabel(
        rawValue: Long,
        options: List<Long> = emptyList(),
    ): String = formatOlympusDriveMode(rawValue, options)

    private fun formatUsbApertureValue(rawValue: Long): String = formatOlympusApertureValueOnly(rawValue)

    private fun formatUsbShutterSpeedValue(rawValue: Long): String = formatOlympusShutterSpeedValueOnly(rawValue)

    private fun formatUsbIsoValue(rawValue: Long): String = formatOlympusIso(rawValue)

    private fun formatUsbExposureCompValue(rawValue: Long): String = formatOlympusExposureComp(rawValue)

    private fun resolveUsbRawValue(
        propCode: Int,
        selectedValue: String,
    ): Long? {
        val snapshot = usbCameraProperties.value
        val isoPropCode = currentUsbIsoPropCode(snapshot)
        val property = snapshot.findProperty(propCode)
            ?: if (
                propCode == isoPropCode ||
                propCode == PtpConstants.OlympusProp.ISOSpeed ||
                propCode == USB_OM1_ISO_PROP
            ) {
                snapshot.iso
            } else {
                null
            }
        val propName = when {
            propCode == PtpConstants.OlympusProp.Aperture -> "focalvalue"
            propCode == PtpConstants.OlympusProp.ShutterSpeed -> "shutspeedvalue"
            propCode == isoPropCode ||
                propCode == PtpConstants.OlympusProp.ISOSpeed ||
                propCode == USB_OM1_ISO_PROP -> "isospeedvalue"
            propCode == PtpConstants.OlympusProp.ExposureCompensation -> "expcomp"
            propCode == PtpConstants.OlympusProp.WhiteBalance -> "wbvalue"
            propCode == PtpConstants.OlympusProp.DriveMode -> "drivemode"
            propCode == PtpConstants.Prop.ExposureProgramMode ||
                propCode == PtpConstants.OlympusProp.ExposureMode -> "takemode"
            else -> ""
        }
        if (property != null) {
            val candidates = enumerateUsbPropertyValues(property)
            candidates.firstOrNull { raw ->
                val candidateValue = if (propName == "takemode") {
                    usbExposureModeLabel(property.propCode, raw)
                } else {
                    formatUsbRemoteValue(propName, raw, candidates)
                }
                propertyValueMatches(propName, candidateValue, selectedValue) ||
                    candidateValue.equals(selectedValue, ignoreCase = true) ||
                    PropertyFormatter.formatForDisplay(propName, candidateValue)
                        .equals(selectedValue, ignoreCase = true)
            }?.let { return it }
        }
        return when (propCode) {
            PtpConstants.OlympusProp.ExposureCompensation -> parseOlympusExposureCompRaw(selectedValue)
            PtpConstants.Prop.ExposureProgramMode -> when (selectedValue.trim().uppercase()) {
                "M" -> 1L
                "P", "PS" -> 2L
                "A" -> 3L
                "S" -> 4L
                else -> null
            }
            PtpConstants.OlympusProp.ExposureMode -> when (selectedValue.trim().uppercase()) {
                "M" -> 1L
                "P", "PS" -> 2L
                "A" -> 3L
                "S" -> 4L
                "B", "BULB" -> 5L
                "TIME" -> 6L
                "COMPOSITE" -> 7L
                "VIDEO", "MOVIE" -> property?.allowedValues?.firstOrNull { it == 0L || it == 8L } ?: 0L
                else -> null
            }
            else -> null
        }
    }

    private suspend fun applyUsbPropertyValue(
        propCode: Int,
        propName: String,
        selectedValue: String,
        closePicker: Boolean,
    ): Boolean {
        val rawValue = resolveUsbRawValue(propCode, selectedValue)
        if (rawValue == null) {
            updateUiState { current -> current.copy(
                remoteRuntime = current.remoteRuntime.copy(
                    statusLabel = "Requested setting is not available over USB",
                ),
            ) }
            return false
        }
        val currentSurface = _uiState.value.remoteRuntime.modePickerSurface
        updateUiState { current -> current.copy(
            remoteRuntime = current.remoteRuntime.copy(
                statusLabel = "Applying...",
            ),
        ) }
        val result = omCaptureUsbManager.setCameraProperty(propCode, rawValue)
        result.onSuccess {
            val currentRuntime = _uiState.value.remoteRuntime
            refreshOmCaptureStudioState(
                base = _uiState.value.copy(
                    remoteRuntime = currentRuntime.copy(
                        activePicker = if (closePicker) ActivePropertyPicker.None else currentRuntime.activePicker,
                        modePickerSurface = preserveModePickerSurface(currentSurface, closePicker),
                        statusLabel = if (currentRuntime.liveViewActive) {
                            "USB live view"
                        } else {
                            "Applied"
                        },
                    ),
                ),
            )
        }.onFailure { error ->
            D.err("USB", "Failed to set USB property $propName=$selectedValue", error)
            updateUiState { current -> current.copy(
                remoteRuntime = current.remoteRuntime.copy(
                    statusLabel = "${propName.replaceFirstChar { it.uppercase() }} change failed: ${error.message}",
                ),
            ) }
        }
        return result.isSuccess
    }

    private suspend fun applyUsbExposureModeChange(
        mode: CameraExposureMode,
        closePicker: Boolean,
    ): Boolean {
        val exposureModePropCode = omCaptureUsbManager.cameraProperties.value.exposureMode?.propCode
            ?: PtpConstants.OlympusProp.ExposureMode
        val rawValue = resolveUsbRawValue(
            propCode = exposureModePropCode,
            selectedValue = mode.label,
        )
        if (rawValue == null) {
            updateUiState { current -> current.copy(
                remoteRuntime = current.remoteRuntime.copy(
                    statusLabel = "Mode switch unavailable over USB",
                ),
            ) }
            return false
        }
        val currentSurface = _uiState.value.remoteRuntime.modePickerSurface
        updateUiState { current -> current.copy(
            remoteRuntime = current.remoteRuntime.copy(
                statusLabel = "Switching to ${mode.label}...",
            ),
        ) }
        val result = omCaptureUsbManager.setCameraProperty(exposureModePropCode, rawValue)
        result.onSuccess {
            val currentRuntime = _uiState.value.remoteRuntime
            refreshOmCaptureStudioState(
                base = _uiState.value.copy(
                    remoteRuntime = currentRuntime.copy(
                        exposureMode = mode,
                        activePicker = if (closePicker) ActivePropertyPicker.None else currentRuntime.activePicker,
                        modePickerSurface = preserveModePickerSurface(currentSurface, closePicker),
                        statusLabel = if (currentRuntime.liveViewActive) {
                            "USB live view"
                        } else {
                            "${mode.label} mode"
                        },
                    ),
                ),
            )
        }.onFailure { error ->
            D.err("USB", "Failed to change USB exposure mode to ${mode.label}", error)
            updateUiState { current -> current.copy(
                remoteRuntime = current.remoteRuntime.copy(
                    statusLabel = "Mode switch failed: ${error.message}",
                ),
            ) }
        }
        return result.isSuccess
    }

    private suspend fun applyUsbRemotePropertySelection(
        picker: ActivePropertyPicker,
        value: String,
        closePicker: Boolean,
    ) {
        val (propName, propCode) = when (picker) {
            ActivePropertyPicker.Aperture -> "focalvalue" to PtpConstants.OlympusProp.Aperture
            ActivePropertyPicker.ShutterSpeed -> "shutspeedvalue" to PtpConstants.OlympusProp.ShutterSpeed
            ActivePropertyPicker.Iso -> "isospeedvalue" to currentUsbIsoPropCode()
            ActivePropertyPicker.WhiteBalance -> "wbvalue" to PtpConstants.OlympusProp.WhiteBalance
            else -> return
        }
        applyUsbPropertyValue(
            propCode = propCode,
            propName = propName,
            selectedValue = value,
            closePicker = closePicker,
        )
    }

    private fun applyUsbDriveModeSelection(
        driveMode: DriveMode,
        timerMode: TimerMode,
    ) {
        propertyApplyJob?.cancel()
        propertyApplyJob = viewModelScope.launch {
            val property = usbCameraProperties.value.driveMode
            if (property == null) {
                updateUiState { current -> current.copy(
                    remoteRuntime = current.remoteRuntime.copy(
                        statusLabel = "Drive mode change is unavailable over USB",
                    ),
                ) }
                return@launch
            }
            val rawOptions = enumerateUsbPropertyValues(property)
            val targetRaw = resolveUsbDriveModeRaw(
                driveMode = driveMode,
                timerMode = timerMode,
                options = rawOptions,
                currentRaw = property.currentValue,
            ) ?: run {
                updateUiState { current -> current.copy(
                    remoteRuntime = current.remoteRuntime.copy(
                        statusLabel = "Drive mode change is unavailable over USB",
                    ),
                ) }
                return@launch
            }
            if (targetRaw !in rawOptions) {
                updateUiState { current -> current.copy(
                    remoteRuntime = current.remoteRuntime.copy(
                        statusLabel = "Requested drive mode is not available over USB",
                    ),
                ) }
                return@launch
            }
            updateUiState { current -> current.copy(
                remoteRuntime = current.remoteRuntime.copy(
                    statusLabel = "Changing drive mode...",
                ),
            ) }
            omCaptureUsbManager.setCameraProperty(PtpConstants.OlympusProp.DriveMode, targetRaw)
                .onFailure { error ->
                    D.err("USB", "Failed to set USB drive mode=${formatOlympusDriveMode(targetRaw)}", error)
                    updateUiState { current -> current.copy(
                        remoteRuntime = current.remoteRuntime.copy(
                            statusLabel = "Drive mode change failed: ${error.message}",
                        ),
                    ) }
                }
        }
    }

    private suspend fun performRemoteCaptureCommand(
        liveViewActive: Boolean,
    ): Result<Unit> {
        return if (isUsbRemoteTransportActive()) {
            if (_uiState.value.tetherSaveTarget == TetherSaveTarget.SdCard) {
                if (liveViewActive) {
                    omCaptureUsbManager.captureWhileLiveView()
                } else {
                    omCaptureUsbManager.captureToCameraStorage()
                }
            } else {
                omCaptureUsbManager.captureAndImportLatestImage(
                    saveMedia = { fileName, data ->
                        withContext(Dispatchers.IO) {
                            saveToGalleryDetailed(fileName, data, currentUsbImportDateFolder())
                                ?: error("Failed to save $fileName to the phone gallery.")
                        }
                    },
                    importFormat = _uiState.value.tetherPhoneImportFormat,
                ).map { result ->
                    applyOmCaptureUsbImportSuccess(result)
                }
            }
        } else {
            if (liveViewActive) {
                repository.captureWhileLiveView().map { Unit }
            } else {
                repository.triggerCapture().map { Unit }
            }
        }
    }

    fun importLatestOmCaptureUsbImage() {
        recordQaUiAction(
            title = "Import latest OM USB image",
            detail = "format=${_uiState.value.tetherPhoneImportFormat.preferenceValue}",
        )
        val currentState = _uiState.value
        refreshOmCaptureStudioState(
            currentState.copy(
                remoteRuntime = currentState.remoteRuntime.copy(
                    statusLabel = "Importing latest photo in background...",
                ),
            ),
        )
        dev.dblink.core.download.ImageDownloadService.startUsbImportLatest(
            context = getApplication(),
            importFormat = currentState.tetherPhoneImportFormat,
            saveLocation = currentState.autoImportConfig.saveLocation,
        )
    }

    private fun applyOmCaptureUsbImportSuccess(image: OmCaptureUsbImportResult) {
        rememberImportedUsbResult(image)
        handleOmCaptureUsbImportResult(image, scanFilesNow = true)
    }

    private suspend fun saveOmCaptureUsbMedia(
        fileName: String,
        data: ByteArray,
    ): OmCaptureUsbSavedMedia = withContext(Dispatchers.IO) {
        saveToGalleryDetailed(fileName, data, currentUsbImportDateFolder())
            ?: error("Failed to save $fileName to the phone gallery.")
    }

    private fun handleOmCaptureUsbImportResult(
        image: OmCaptureUsbImportResult,
        scanFilesNow: Boolean,
    ) {
        invalidateUsbLibraryCache(reloadIfVisible = true)
        val preview = decodeTransferPreviewBitmap(image.objectBytes)
            ?: decodeSampledPreviewBitmap(image.objectBytes)
            ?: image.thumbnailBytes?.let(::decodeTransferPreviewBitmap)
            ?: image.thumbnailBytes?.let(::decodeSampledPreviewBitmap)
        updateUiState { current -> current.copy(
            lastCaptureThumbnail = preview ?: current.lastCaptureThumbnail,
        ) }
        val initialPreviewPairs = buildList {
            preview?.let { add(image.fileName to it) }
            image.companionImports.forEach { companion ->
                preview?.let { add(companion.fileName to it) }
            }
        }
        if (initialPreviewPairs.isNotEmpty()) {
            updateTransferThumbnails(initialPreviewPairs, overwriteExisting = true)
        }
        if (image.savedMedia.isRaw || preview == null) {
            viewModelScope.launch {
                val savedPreview = decodeSavedMediaPreview(image.savedMedia) ?: return@launch
                updateLastCaptureThumbnail(savedPreview)
                updateTransferThumbnails(
                    buildList {
                        add(image.fileName to savedPreview)
                        image.companionImports.forEach { companion ->
                            if (companion.isRaw || companion.isJpeg) {
                                add(companion.fileName to savedPreview)
                            }
                        }
                    },
                    overwriteExisting = true,
                )
                updateUiState { current ->
                    if (current.transferState.selectedSavedMedia?.uriString == image.savedMedia.uriString) {
                        val updatedSavedMediaBitmaps = current.transferState.selectedSavedMediaBitmaps.toMutableMap()
                        updatedSavedMediaBitmaps[image.savedMedia.uriString] = savedPreview
                        current.copy(
                            transferState = current.transferState.copy(
                                selectedSavedMediaBitmap = savedPreview,
                                selectedSavedMediaLoading = false,
                                selectedSavedMediaBitmaps = updatedSavedMediaBitmaps,
                            ),
                        )
                    } else {
                        current
                    }
                }
            }
        }
        if (_uiState.value.transferState.sourceKind == TransferSourceKind.OmCaptureUsb) {
            applyUsbTransferItems(usbLibraryCache)
        }
        submitDeepSkyCapturedFrame(image)
        if (scanFilesNow) {
            scanDownloadedFiles()
        } else {
            refreshDownloadedFilesAfterUsbAutoImport()
        }
    }

    private fun handleOmCaptureUsbCameraEvent(event: OmCaptureUsbCameraEvent) {
        when (event) {
            is OmCaptureUsbCameraEvent.ObjectAdded -> {
                recordQaCameraEvent(
                    title = "Object added",
                    detail = "handle=${usbPropHex(event.handle)}",
                )
                D.usb("Received OM USB auto-import trigger for handle=${event.handle.toString(16)}")
                invalidateUsbLibraryCache(
                    reloadIfVisible = _uiState.value.transferState.sourceKind == TransferSourceKind.OmCaptureUsb,
                )
                maybeAutoImportUsbHandle(event.handle)
            }
            is OmCaptureUsbCameraEvent.PropertyChanged -> {
                val propLabel = olympusUsbPropertyLabel(event.propCode)
                val detail = if (event.rawValue != null) {
                    "$propLabel (${usbPropHex(event.propCode)}) raw=${event.rawValue}"
                } else {
                    "$propLabel (${usbPropHex(event.propCode)})"
                }
                recordQaCameraEvent(
                    title = if (event.propCode == USB_MODE_DIAL_EVENT_PROP) {
                        "Camera mode/state hint"
                    } else {
                        "Camera property event"
                    },
                    detail = detail,
                )
                queueUsbPropertyRefresh(event.propCode, event.rawValue)
            }
            is OmCaptureUsbCameraEvent.SessionResetRequired -> {
                recordQaCameraEvent(
                    title = "Session reset required",
                    detail = event.reason,
                )
                usbPropertySyncJob?.cancel()
                usbModeDialRefreshJob?.cancel()
                pendingUsbPropertyRefreshCodes.clear()
                D.usb("USB session reset requested (${event.reason}); dropping pending property refresh batch")
                if (isUsbModeSurface(_uiState.value.remoteRuntime.modePickerSurface)) {
                    viewModelScope.launch {
                        delay(350L)
                        val currentState = _uiState.value
                        if (
                            isUsbModeSurface(currentState.remoteRuntime.modePickerSurface) &&
                            !currentState.omCaptureUsb.isBusy
                        ) {
                            D.usb("USB session reset requested (${event.reason}); re-inspecting camera to rebind tether session")
                            refreshOmCaptureUsb()
                        }
                    }
                }
            }
        }
    }

    private fun queueUsbPropertyRefresh(propCode: Int, rawValue: Long?) {
        val currentState = _uiState.value
        if (currentState.omCaptureUsb.summary == null) {
            return
        }
        val allowModeHintRefreshWhileTransfer =
            propCode == USB_MODE_DIAL_EVENT_PROP &&
                (usbLiveViewDesired ||
                    omCaptureUsbManager.isLiveViewActive ||
                    isUsbModeSurface(currentState.remoteRuntime.modePickerSurface))
        if (
            omCaptureUsbManager.preferredSessionDomain() == UsbSessionDomain.Transfer &&
            !allowModeHintRefreshWhileTransfer
        ) {
            D.usb(
                "Skipping USB property refresh for 0x${propCode.toString(16)} " +
                    "because the session is currently in transfer mode",
            )
            return
        }
        val liveViewBusy = currentState.omCaptureUsb.isBusy && omCaptureUsbManager.isLiveViewActive
        if (propCode == USB_MODE_DIAL_EVENT_PROP) {
            usbModeDialRefreshJob?.cancel()
            usbModeDialRefreshJob = viewModelScope.launch {
                val settleDelayMs = (usbCaptureSettleUntilMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                val extraDelayMs = if (settleDelayMs > 0L) settleDelayMs + 120L else USB_MODE_DIAL_REFRESH_SUPPRESS_MS
                if (extraDelayMs > 0L) {
                    D.usb(
                        "Scheduling USB mode/state refresh for 0x${propCode.toString(16)} " +
                            "after ${extraDelayMs}ms so we can re-read D01D/500E from the camera",
                    )
                    delay(extraDelayMs)
                }
                var remainingWaitMs = 3_500L
                while (
                    remainingWaitMs > 0L &&
                    (_uiState.value.omCaptureUsb.isBusy || propertyApplyJob?.isActive == true)
                ) {
                    delay(100L)
                    remainingWaitMs -= 100L
                }
                D.usb(
                    "Refreshing USB exposure-mode properties from D062 hint " +
                        "using actual camera props (D01D/500E)",
                )
                omCaptureUsbManager.refreshCameraPropertiesForCodes(setOf(propCode))
                    .onFailure { throwable ->
                        D.err("USB", "Failed to refresh USB camera mode after D062 hint", throwable)
                    }
            }
            return
        }
        if (propCode == USB_AF_TARGET_EVENT_PROP) {
            D.usb(
                "Skipping USB AF target readback for 0x${propCode.toString(16)} " +
                    "because the event already carried the latest target value",
            )
            return
        }
        val modeRelatedRefreshCodes = usbModeRelatedRefreshCodes(propCode)
        val refreshCodesForEvent = if (modeRelatedRefreshCodes.isNotEmpty()) {
            modeRelatedRefreshCodes
        } else {
            linkedSetOf(propCode)
        }
        if (omCaptureUsbManager.isLiveViewActive) {
            pendingUsbPropertyRefreshCodes.addAll(refreshCodesForEvent)
            usbPropertySyncJob?.cancel()
            usbPropertySyncJob = viewModelScope.launch {
                if (modeRelatedRefreshCodes.isNotEmpty()) {
                    D.usb(
                        "Refreshing USB mode-related props for 0x${propCode.toString(16)} " +
                            "while live view stays active to match libgphoto2's immediate " +
                            "Olympus DevicePropChanged descriptor read",
                    )
                    delay(320L)
                    var remainingWaitMs = 1_200L
                    while (
                        remainingWaitMs > 0L &&
                        (_uiState.value.omCaptureUsb.isBusy || propertyApplyJob?.isActive == true)
                    ) {
                        delay(80L)
                        remainingWaitMs -= 80L
                    }
                } else {
                    D.usb(
                        "Deferring USB property refresh for 0x${propCode.toString(16)} " +
                            "while live view stays active; using event value first to match libgphoto2's " +
                            "Olympus DevicePropChanged flow",
                    )
                    while (
                        omCaptureUsbManager.isLiveViewActive ||
                        _uiState.value.omCaptureUsb.isBusy ||
                        propertyApplyJob?.isActive == true
                    ) {
                        delay(120L)
                    }
                }
                val refreshCodes = pendingUsbPropertyRefreshCodes.toSet()
                pendingUsbPropertyRefreshCodes.clear()
                if (refreshCodes.isEmpty()) {
                    return@launch
                }
                omCaptureUsbManager.refreshCameraPropertiesForCodes(refreshCodes)
                    .onFailure { throwable ->
                        D.err("USB", "Failed deferred USB property refresh after live view settled", throwable)
                    }
            }
            return
        }
        if (usbTransferThumbnailPrefetchActive) {
            pendingUsbPropertyRefreshCodes.addAll(refreshCodesForEvent)
            usbPropertySyncJob?.cancel()
            usbPropertySyncJob = viewModelScope.launch {
                D.usb(
                    "Deferring USB property refresh for 0x${propCode.toString(16)} " +
                        "until USB thumbnail prefetch settles",
                )
                var remainingWaitMs = 3_000L
                while (usbTransferThumbnailPrefetchActive && remainingWaitMs > 0L) {
                    delay(100L)
                    remainingWaitMs -= 100L
                }
                val refreshCodes = pendingUsbPropertyRefreshCodes.toSet()
                pendingUsbPropertyRefreshCodes.clear()
                if (refreshCodes.isEmpty()) {
                    return@launch
                }
                omCaptureUsbManager.refreshCameraPropertiesForCodes(refreshCodes)
                    .onFailure { throwable ->
                        D.err("USB", "Failed deferred USB property refresh after thumbnail prefetch", throwable)
                }
            }
            return
        }
        if (liveViewBusy) {
            pendingUsbPropertyRefreshCodes.addAll(refreshCodesForEvent)
            usbPropertySyncJob?.cancel()
            usbPropertySyncJob = viewModelScope.launch {
                D.usb(
                    "Deferring USB property refresh for 0x${propCode.toString(16)} " +
                        "until the current tether transfer settles",
                )
                var remainingWaitMs = 3_500L
                while (
                    remainingWaitMs > 0L &&
                    (_uiState.value.omCaptureUsb.isBusy || propertyApplyJob?.isActive == true)
                ) {
                    delay(100L)
                    remainingWaitMs -= 100L
                }
                val refreshCodes = pendingUsbPropertyRefreshCodes.toSet()
                pendingUsbPropertyRefreshCodes.clear()
                if (refreshCodes.isEmpty()) {
                    return@launch
                }
                omCaptureUsbManager.refreshCameraPropertiesForCodes(refreshCodes)
                    .onFailure { throwable ->
                        D.err("USB", "Failed deferred USB property refresh after tether transfer", throwable)
                }
            }
            return
        }
        pendingUsbPropertyRefreshCodes.addAll(refreshCodesForEvent)
        usbPropertySyncJob?.cancel()
        usbPropertySyncJob = viewModelScope.launch {
            // Debounce: batch rapid-fire camera events into a single refresh
            // to reduce the frequency of live-view transport pauses.
            // Optimistic updates (applyOptimisticPropertyChanged) already
            // update the UI immediately; this is a confirmation readback.
            delay(350)
            var remainingWaitMs = 280L
            while (propertyApplyJob?.isActive == true && remainingWaitMs > 0L) {
                delay(70)
                remainingWaitMs -= 70L
            }
            val refreshCodes = pendingUsbPropertyRefreshCodes.toSet()
            pendingUsbPropertyRefreshCodes.clear()
            if (refreshCodes.isEmpty()) {
                return@launch
            }
            val codesLabel = refreshCodes.joinToString { "0x${it.toString(16)}" }
            D.usb(
                "Refreshing USB properties from camera event " +
                    "prop=0x${propCode.toString(16)} raw=${rawValue?.toString(16) ?: "n/a"} " +
                    "codes=$codesLabel",
            )
            omCaptureUsbManager.refreshCameraPropertiesForCodes(refreshCodes)
                .onFailure { throwable ->
                    D.err("USB", "Failed to refresh USB camera properties from device events", throwable)
                }
        }
    }

    private fun maybeAutoImportUsbHandle(handle: Int) {
        val currentState = _uiState.value
        if (!currentState.tetherSaveTarget.savesToPhone) {
            D.usb(
                "Ignoring OM USB auto-import trigger for handle=${handle.toString(16)} " +
                    "because the save target is ${currentState.tetherSaveTarget}",
            )
            return
        }
        if (isCapturing && currentState.omCaptureUsb.isBusy) {
            D.usb(
                "Ignoring OM USB auto-import trigger for handle=${handle.toString(16)} " +
                    "because an app-initiated USB capture/import is already resolving this shot",
            )
            return
        }
        if (currentState.omCaptureUsb.isBusy || reconnectRestorationActive) {
            D.usb(
                "Queueing OM USB auto-import for handle=${handle.toString(16)} " +
                    "because USB is busy or reconnect restoration is active",
            )
        }
        if (handle in completedUsbAutoImportHandles) {
            D.usb("Ignoring already-imported OM USB auto-import handle=${handle.toString(16)}")
            return
        }
        if (lastAutoImportedUsbHandle == handle || handle in pendingUsbAutoImportHandles) {
            D.usb("Ignoring duplicate OM USB auto-import event for handle=${handle.toString(16)}")
            return
        }
        pendingUsbAutoImportHandles += handle
        usbCaptureSettleUntilMs = maxOf(
            usbCaptureSettleUntilMs,
            SystemClock.elapsedRealtime() + USB_CAPTURE_PROPERTY_SETTLE_MS,
        )
        if (isUsbRemoteTransportActive()) {
            val previewFrame = _liveViewFrame.value ?: currentState.lastCaptureThumbnail
            updateUiState { state -> state.copy(
                remoteRuntime = state.remoteRuntime.copy(
                    statusLabel = "Importing latest shot...",
                    isBusy = true,
                ),
                lastCaptureThumbnail = previewFrame ?: state.lastCaptureThumbnail,
            ) }
        }
        D.usb(
            "Queued OM USB auto-import handle=${handle.toString(16)} " +
                "pending=${pendingUsbAutoImportHandles.joinToString { it.toString(16) }}",
        )
        if (usbAutoImportJob?.isActive == true) {
            return
        }
        usbAutoImportJob = viewModelScope.launch {
            drainPendingUsbAutoImports()
        }
    }

    private suspend fun drainPendingUsbAutoImports() {
        try {
            while (pendingUsbAutoImportHandles.isNotEmpty()) {
                while (true) {
                    val currentState = _uiState.value
                    if (!currentState.tetherSaveTarget.savesToPhone) {
                        pendingUsbAutoImportHandles.clear()
                        return
                    }
                    if (reconnectRestorationActive || currentState.omCaptureUsb.isBusy) {
                        D.usb("Delaying queued OM USB auto-import because USB is busy")
                        delay(USB_AUTO_IMPORT_RETRY_MS)
                        continue
                    }
                    break
                }

                val handle = pendingUsbAutoImportHandles.firstOrNull() ?: continue
                pendingUsbAutoImportHandles.remove(handle)
                delay(USB_AUTO_IMPORT_BATCH_WINDOW_MS)
                val relatedHandlesHint = linkedSetOf<Int>()
                val iterator = pendingUsbAutoImportHandles.iterator()
                while (iterator.hasNext()) {
                    val pendingHandle = iterator.next()
                    if (pendingHandle in completedUsbAutoImportHandles) {
                        iterator.remove()
                        continue
                    }
                    relatedHandlesHint += pendingHandle
                    iterator.remove()
                }
                if (lastAutoImportedUsbHandle == handle) {
                    D.usb("Skipping already-imported OM USB handle=${handle.toString(16)}")
                    continue
                }
                if (handle in completedUsbAutoImportHandles) {
                    D.usb("Skipping completed OM USB auto-import handle=${handle.toString(16)}")
                    continue
                }

                lastAutoImportedUsbHandle = handle
                D.usb("Importing OM camera capture handle=${handle.toString(16)} to the phone...")
                val keepLiveViewVisible = omCaptureUsbManager.isLiveViewActive
                omCaptureUsbManager.importAutoAddedHandle(
                    handle = handle,
                    saveMedia = { fileName, data ->
                        withContext(Dispatchers.IO) {
                            saveToGalleryDetailed(fileName, data, currentUsbImportDateFolder())
                                ?: error("Failed to save $fileName to the phone gallery.")
                        }
                    },
                    importFormat = _uiState.value.tetherPhoneImportFormat,
                    keepLiveViewVisible = keepLiveViewVisible,
                    relatedHandlesHint = relatedHandlesHint,
                ).onSuccess { result ->
                    if (result == null) {
                        D.usb(
                            "OM USB auto-import handle=${handle.toString(16)} was intentionally skipped " +
                                "for format=${_uiState.value.tetherPhoneImportFormat.preferenceValue}",
                        )
                        return@onSuccess
                    }
                    recordCompletedUsbAutoImport(handle, result)
                    handleOmCaptureUsbImportResult(result, scanFilesNow = false)
                    if (isUsbModeSurface(_uiState.value.remoteRuntime.modePickerSurface)) {
                        val current = _uiState.value
                        refreshOmCaptureStudioState(
                            current.copy(
                                remoteRuntime = current.remoteRuntime.copy(
                                    liveViewActive = omCaptureUsbManager.isLiveViewActive,
                                    captureCount = current.remoteRuntime.captureCount + 1,
                                    statusLabel = "Saved latest OM camera shot to phone",
                                    isBusy = false,
                                ),
                            ),
                        )
                    }
                }.onFailure { throwable ->
                    lastAutoImportedUsbHandle = null
                    D.err(
                        "USB",
                        "Automatic OM camera phone save failed for handle=${handle.toString(16)}",
                        throwable,
                    )
                    if (isUsbModeSurface(_uiState.value.remoteRuntime.modePickerSurface)) {
                        val current = _uiState.value
                        refreshOmCaptureStudioState(
                            current.copy(
                                remoteRuntime = current.remoteRuntime.copy(
                                    liveViewActive = omCaptureUsbManager.isLiveViewActive,
                                    statusLabel = throwable.message ?: "Latest-shot import failed",
                                    isBusy = false,
                                ),
                            ),
                        )
                    }
                }
            }
        } finally {
            usbAutoImportJob = null
        }
    }

    private fun recordCompletedUsbAutoImport(
        requestedHandle: Int,
        result: OmCaptureUsbImportResult,
    ) {
        rememberImportedUsbHandle(requestedHandle)
        rememberImportedUsbResult(result)
    }

    private fun rememberImportedUsbResult(result: OmCaptureUsbImportResult) {
        rememberImportedUsbHandle(result.objectHandle)
        rememberSavedMediaLookup(result.savedMedia)
        rememberSavedMediaHistory(
            savedMedia = result.savedMedia,
            width = result.width,
            height = result.height,
            captureTimestampMillis = result.captureTimestampMillis,
        )
        result.relatedObjectHandles.forEach(::rememberImportedUsbHandle)
        result.companionImports.forEach { companion ->
            rememberImportedUsbHandle(companion.objectHandle)
            rememberSavedMediaLookup(companion.savedMedia)
            rememberSavedMediaHistory(
                savedMedia = companion.savedMedia,
                width = companion.width,
                height = companion.height,
                captureTimestampMillis = companion.captureTimestampMillis,
            )
        }
    }

    private fun rememberImportedUsbHandle(handle: Int) {
        lastAutoImportedUsbHandle = handle
        completedUsbAutoImportHandles += handle
        while (completedUsbAutoImportHandles.size > USB_AUTO_IMPORT_COMPLETED_HANDLE_LIMIT) {
            val oldest = completedUsbAutoImportHandles.firstOrNull() ?: break
            completedUsbAutoImportHandles.remove(oldest)
        }
    }

    private fun refreshDownloadedFilesAfterUsbAutoImport() {
        if (omCaptureUsbManager.isLiveViewActive) {
            D.usb("Deferring local downloaded-file scan while USB live view remains active")
            return
        }
        scanDownloadedFiles()
    }

    private data class DeepSkyImportedMedia(
        val fileName: String,
        val captureDate: String,
        val captureTimestampMillis: Long?,
        val width: Int,
        val height: Int,
        val isJpeg: Boolean,
        val isRaw: Boolean,
        val savedMedia: OmCaptureUsbSavedMedia,
    )

    private data class UsbSavedMediaRecord(
        val savedMedia: OmCaptureUsbSavedMedia,
        val width: Int,
        val height: Int,
        val captureTimestampMillis: Long?,
    )

    private data class DeepSkyExifMetadata(
        val exposureSec: Double?,
        val iso: Int?,
        val focalLengthMm: Float?,
        val aperture: Float?,
        val width: Int?,
        val height: Int?,
    )

    private fun submitDeepSkyCapturedFrame(image: OmCaptureUsbImportResult) {
        deepSkyLiveStackCoordinator.updateSkyHintContext(_uiState.value.geoTagging.latestSample, null)
        viewModelScope.launch(Dispatchers.IO) {
            val capturedFrame = buildDeepSkyCapturedFrame(image) ?: return@launch
            D.astro(
                "Submitting Deep Sky frame=${capturedFrame.id} " +
                    "preview=${capturedFrame.previewPath} raw=${capturedFrame.rawPath}",
            )
            deepSkyLiveStackCoordinator.updateLiveViewCaptureContext(
                focalLengthMm = capturedFrame.focalLengthMm,
                aperture = capturedFrame.aperture,
                exposureSec = capturedFrame.exposureSec,
            )
            deepSkyLiveStackCoordinator.submitCapturedFrame(capturedFrame)
        }
    }

    private fun buildDeepSkyCapturedFrame(image: OmCaptureUsbImportResult): CapturedFrame? {
        val primary = DeepSkyImportedMedia(
            fileName = image.fileName,
            captureDate = image.captureDate,
            captureTimestampMillis = image.captureTimestampMillis,
            width = image.width,
            height = image.height,
            isJpeg = image.isJpeg,
            isRaw = image.isRaw,
            savedMedia = image.savedMedia,
        )
        val rawMedia = when {
            primary.isRaw -> primary
            else -> image.companionImports.firstOrNull { it.isRaw }?.let {
                DeepSkyImportedMedia(
                    fileName = it.fileName,
                    captureDate = it.captureDate,
                    captureTimestampMillis = it.captureTimestampMillis,
                    width = it.width,
                    height = it.height,
                    isJpeg = it.isJpeg,
                    isRaw = it.isRaw,
                    savedMedia = it.savedMedia,
                )
            }
        }
        val previewMedia = when {
            primary.isJpeg -> primary
            else -> image.companionImports.firstOrNull { it.isJpeg }?.let {
                DeepSkyImportedMedia(
                    fileName = it.fileName,
                    captureDate = it.captureDate,
                    captureTimestampMillis = it.captureTimestampMillis,
                    width = it.width,
                    height = it.height,
                    isJpeg = it.isJpeg,
                    isRaw = it.isRaw,
                    savedMedia = it.savedMedia,
                )
            } ?: primary
        }

        val previewPath = previewMedia.savedMedia.absolutePath
        val rawPath = rawMedia?.savedMedia?.absolutePath ?: previewPath
        if (!File(previewPath).exists() && !File(rawPath).exists()) {
            D.astro("Deep Sky frame skipped because imported files are not on disk: preview=$previewPath raw=$rawPath")
            return null
        }

        val exif = readDeepSkyExifMetadata(previewPath) ?: readDeepSkyExifMetadata(rawPath)
        val bounds = readImageBounds(previewPath).takeIf { it.first > 0 && it.second > 0 } ?: readImageBounds(rawPath)
        val width = listOf(rawMedia?.width, previewMedia.width, exif?.width, bounds.first)
            .firstOrNull { (it ?: 0) > 0 } ?: 0
        val height = listOf(rawMedia?.height, previewMedia.height, exif?.height, bounds.second)
            .firstOrNull { (it ?: 0) > 0 } ?: 0
        val captureTimeMs =
            image.captureTimestampMillis ?: rawMedia?.captureTimestampMillis ?: previewMedia.captureTimestampMillis ?: System.currentTimeMillis()

        return CapturedFrame(
            id = "${image.objectHandle}-${captureTimeMs}",
            captureTimeMs = captureTimeMs,
            rawPath = rawPath,
            previewPath = previewPath,
            width = width,
            height = height,
            exposureSec = exif?.exposureSec,
            iso = exif?.iso,
            focalLengthMm = exif?.focalLengthMm,
            aperture = exif?.aperture,
            cameraMetadata = buildMap {
                put("displayName", rawMedia?.fileName ?: previewMedia.fileName)
                put("captureDate", image.captureDate)
                put("previewPath", previewPath)
                put("rawPath", rawPath)
            },
        )
    }

    private fun readDeepSkyExifMetadata(path: String): DeepSkyExifMetadata? {
        if (path.isBlank() || !File(path).isFile) return null
        return runCatching {
            val exif = ExifInterface(path)
            val iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
                .takeIf { it > 0 }
            val exposureSec = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                .takeIf { it > 0.0 }
            val focalLengthMm = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
                .takeIf { it > 0.0 }
                ?.toFloat()
            val aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
                .takeIf { it > 0.0 }
                ?.toFloat()
            val width = exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0).takeIf { it > 0 }
            val height = exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0).takeIf { it > 0 }
            DeepSkyExifMetadata(
                exposureSec = exposureSec,
                iso = iso,
                focalLengthMm = focalLengthMm,
                aperture = aperture,
                width = width,
                height = height,
            )
        }.getOrNull()
    }

    private fun readImageBounds(path: String): Pair<Int, Int> {
        if (path.isBlank() || !File(path).isFile) return 0 to 0
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        return options.outWidth to options.outHeight
    }

    fun clearOmCaptureUsbState() {
        clearUsbLibraryCache()
        omCaptureUsbManager.clearRuntimeState()
    }

    private fun clearUsbLibraryCache() {
        usbLibraryCache = emptyList()
        usbLibraryCacheUpdatedAtMs = 0L
    }

    private fun invalidateUsbLibraryCache(reloadIfVisible: Boolean) {
        clearUsbLibraryCache()
        if (!reloadIfVisible) {
            return
        }
        val transferState = _uiState.value.transferState
        if (
            transferState.sourceKind != TransferSourceKind.OmCaptureUsb ||
            transferState.isLoading ||
            omCaptureUsbManager.isLiveViewActive
        ) {
            return
        }
        loadCameraImages()
    }

    private fun wifiTransferSourceLabel(slot: Int?, ssid: String?): String {
        return when (slot) {
            1 -> "Slot 1"
            2 -> "Slot 2"
            else -> ssid ?: "Wi-Fi"
        }
    }

    private fun resetTransferSourceFromUsbToWifi() {
        val selectedSsid = _uiState.value.selectedCameraSsid
        updateUiState { current ->
            if (current.transferState.sourceKind != TransferSourceKind.OmCaptureUsb) {
                current
            } else {
                current.copy(
                    transferState = current.transferState.copy(
                        images = emptyList(),
                        thumbnails = emptyMap(),
                        isLoading = false,
                        isDownloading = false,
                        downloadProgress = "",
                        errorMessage = null,
                        selectedImage = null,
                        selectedImages = emptySet(),
                        isSelectionMode = false,
                        matchedGeoTags = emptyMap(),
                        previewUnavailable = emptySet(),
                        sourceCameraSsid = selectedSsid,
                        sourceKind = TransferSourceKind.WifiCamera,
                        sourceLabel = wifiTransferSourceLabel(current.selectedCardSlotSource, selectedSsid),
                        supportsDelete = true,
                        usbObjectHandlesByPath = emptyMap(),
                        usbAvailableStorageIds = emptyList(),
                        selectedUsbStorageIds = null,
                    ),
                )
            }
        }
        resetWifiThumbnailFallbackState()
    }

    /**
     * Toggle USB PTP live view.
     * Flow: ChangeRunMode(RECORDING) → LiveViewEnabled=1 → poll GetLiveViewImage.
     */
    fun toggleUsbLiveView() {
        recordQaUiAction(
            title = if (omCaptureUsbManager.isLiveViewActive) "Stop USB live view" else "Start USB live view",
            detail = "surface=${_uiState.value.remoteRuntime.modePickerSurface}",
        )
        viewModelScope.launch {
            val starting = !omCaptureUsbManager.isLiveViewActive
            D.usb("toggleUsbLiveView: starting=$starting")
            if (starting) {
                prepareOmCaptureUsbForControlMode()
            }

            if (!starting) {
                setUsbLiveViewDesired(false, "toggle-stop")
                omCaptureUsbManager.stopUsbLiveView()
                usbLiveViewFrameJob?.cancel()
                usbLiveViewFrameJob = null
                _liveViewFrame.value = null
                refreshOmCaptureStudioState(
                    _uiState.value.copy(
                        remoteRuntime = _uiState.value.remoteRuntime.copy(
                            liveViewActive = false,
                            activePicker = ActivePropertyPicker.None,
                            statusLabel = "Ready",
                        ),
                    ),
                )
                return@launch
            }
            setUsbLiveViewDesired(true, "toggle-start")

            var usbSummary = _uiState.value.omCaptureUsb.summary
            if (usbSummary == null) {
                D.usb("toggleUsbLiveView: no active USB session, attempting explicit tether connect")
                refreshOmCaptureStudioState(
                    _uiState.value.copy(
                        remoteRuntime = _uiState.value.remoteRuntime.copy(
                            statusLabel = "Connecting USB tether...",
                        ),
                    ),
                )
                // Wait briefly if a session reconnect is in progress
                var waitAttempts = 0
                while (usbSummary == null && waitAttempts < 8) {
                    usbSummary = omCaptureUsbManager.inspectConnectedCamera().getOrNull()
                    if (usbSummary == null) {
                        waitAttempts++
                        D.usb("toggleUsbLiveView: connect attempt $waitAttempts/8, waiting...")
                        delay(500)
                    }
                }
                if (usbSummary == null) {
                    D.err("USB", "USB live view connect failed after $waitAttempts attempts")
                    refreshOmCaptureStudioState(
                        _uiState.value.copy(
                            remoteRuntime = _uiState.value.remoteRuntime.copy(
                                liveViewActive = false,
                                statusLabel = "USB tether connection failed",
                            ),
                        ),
                    )
                    return@launch
                }
            }

            if (!usbSummary.supportsLiveView) {
                // Distinguish between "basic MTP mode" (few ops) vs "truly unsupported camera"
                val isBasicMtpMode = usbSummary.supportedOperationCount < 50
                val statusMsg = if (isBasicMtpMode) {
                    "Camera is in basic MTP mode — change USB mode to PTP/Tether in camera settings, then reconnect"
                } else {
                    "USB live view is not supported by this camera"
                }
                D.usb(
                    "toggleUsbLiveView: active USB session does not support live view " +
                        "(ops=${usbSummary.supportedOperationCount} basicMtp=$isBasicMtpMode)",
                )
                refreshOmCaptureStudioState(
                    _uiState.value.copy(
                        remoteRuntime = _uiState.value.remoteRuntime.copy(
                            liveViewActive = false,
                            statusLabel = statusMsg,
                        ),
                    ),
                )
                return@launch
            }

            startUsbLiveViewSession(waitingStatus = "Waiting for USB live view frame...")
        }
    }

    /**
     * Open the last USB-captured image using its content URI.
     */
    fun openUsbCapturedImage(context: android.content.Context) {
        _uiState.value.omCaptureUsb.lastSavedMedia?.let(::openSavedMediaPreview)
    }

    private suspend fun resolveSavedMediaForFileName(fileName: String): OmCaptureUsbSavedMedia? {
        usbSavedMediaByFileName[normalizeSavedMediaKey(fileName)]?.let { return it }
        return findSavedMediaByFileName(fileName)?.also { rememberSavedMediaLookup(it) }
    }

    private suspend fun findSavedMediaByFileName(fileName: String): OmCaptureUsbSavedMedia? = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        val basePath = buildConfiguredSaveBasePath().trimEnd('/') + "/"
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val selection = buildString {
            append("${MediaStore.MediaColumns.DISPLAY_NAME}=?")
            append(" AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?")
        }
        val selectionArgs = arrayOf(fileName, "$basePath%")
        val collections = listOf(
            MediaStore.Images.Media.getContentUri(MEDIA_STORE_PRIMARY_VOLUME),
            MediaStore.Files.getContentUri(MEDIA_STORE_PRIMARY_VOLUME),
            MediaStore.Video.Media.getContentUri(MEDIA_STORE_PRIMARY_VOLUME),
        )
        collections.firstNotNullOfOrNull { collection ->
            resolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use null
                }
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val relativePathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val mimeTypeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val displayName = cursor.getString(nameCol) ?: fileName
                val relativePath = cursor.getString(relativePathCol).orEmpty()
                val mimeType = cursor.getString(mimeTypeCol).orEmpty().ifBlank { resolveSavedMediaMimeType(displayName) }
                val dateFolder = relativePath
                    .removePrefix(basePath)
                    .trim('/')
                OmCaptureUsbSavedMedia(
                    uriString = ContentUris.withAppendedId(collection, cursor.getLong(idCol)).toString(),
                    relativePath = relativePath,
                    absolutePath = "/storage/emulated/0/$relativePath$displayName",
                    displayName = displayName,
                    mimeType = mimeType,
                    dateFolder = dateFolder,
                    isRaw = isRawFileName(displayName),
                )
            }
        }
    }

    private suspend fun decodeSavedMediaPreview(savedMedia: OmCaptureUsbSavedMedia): Bitmap? {
        if (savedMedia.isRaw) {
            decodeSavedRawPreview(savedMedia)?.let { return it }
        }
        decodeSavedMediaPlatformPreview(savedMedia)?.let { return it }
        val bytes = readSavedMediaBytes(savedMedia) ?: return null
        return withContext(Dispatchers.Default) {
            decodeTransferPreviewBitmap(bytes) ?: decodeSampledPreviewBitmap(bytes)
        }
    }

    private suspend fun decodeSavedRawPreview(savedMedia: OmCaptureUsbSavedMedia): Bitmap? {
        val rawPath = ensureSavedMediaRawPath(savedMedia) ?: return null
        val frameId = "saved-${savedMedia.displayName}"
        val frame = CapturedFrame(
            id = frameId,
            captureTimeMs = System.currentTimeMillis(),
            rawPath = rawPath,
            previewPath = "",
            width = 0,
            height = 0,
            exposureSec = null,
            iso = null,
            focalLengthMm = null,
            aperture = null,
        )
        val rawDecoder = RawDecoder()
        val decoded = rawDecoder.decodeOrNull(frame) ?: return null
        return try {
            Bitmap.createBitmap(decoded.previewWidth, decoded.previewHeight, Bitmap.Config.ARGB_8888).apply {
                setPixels(
                    decoded.previewArgb,
                    0,
                    decoded.previewWidth,
                    0,
                    0,
                    decoded.previewWidth,
                    decoded.previewHeight,
                )
            }
        } finally {
            rawDecoder.recycle(frameId, decoded)
        }
    }

    private suspend fun decodeSavedMediaPlatformPreview(savedMedia: OmCaptureUsbSavedMedia): Bitmap? = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        val uri = savedMedia.uriString.toUri()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(resolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val longestSide = maxOf(info.size.width, info.size.height)
                    if (longestSide > 2048) {
                        val scale = 2048f / longestSide.toFloat()
                        decoder.setTargetSize(
                            (info.size.width * scale).toInt().coerceAtLeast(1),
                            (info.size.height * scale).toInt().coerceAtLeast(1),
                        )
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                resolver.openInputStream(uri)?.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream)
                }
            }
        }.getOrNull()
    }

    private suspend fun ensureSavedMediaRawPath(savedMedia: OmCaptureUsbSavedMedia): String? = withContext(Dispatchers.IO) {
        val absolutePath = savedMedia.absolutePath
        if (absolutePath.isNotBlank() && File(absolutePath).isFile) {
            return@withContext absolutePath
        }
        val bytes = readSavedMediaBytes(savedMedia) ?: return@withContext null
        val cacheFile = File(getApplication<Application>().cacheDir, "saved-preview-${savedMedia.displayName}")
        runCatching {
            if (!cacheFile.exists() || cacheFile.length() != bytes.size.toLong()) {
                cacheFile.writeBytes(bytes)
            }
            cacheFile.absolutePath
        }.getOrNull()
    }

    private suspend fun readSavedMediaBytes(savedMedia: OmCaptureUsbSavedMedia): ByteArray? = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        resolver.openInputStream(savedMedia.uriString.toUri())?.use { inputStream ->
            inputStream.readBytes()
        }
    }

    private fun rememberSavedMediaLookup(savedMedia: OmCaptureUsbSavedMedia) {
        usbSavedMediaByFileName[normalizeSavedMediaKey(savedMedia.displayName)] = savedMedia
        while (usbSavedMediaByFileName.size > 64) {
            val oldestKey = usbSavedMediaByFileName.keys.firstOrNull() ?: break
            usbSavedMediaByFileName.remove(oldestKey)
        }
    }

    private fun rememberSavedMediaHistory(
        savedMedia: OmCaptureUsbSavedMedia,
        width: Int,
        height: Int,
        captureTimestampMillis: Long?,
    ) {
        val key = savedMedia.uriString.ifBlank { savedMedia.absolutePath.ifBlank { savedMedia.displayName } }
        usbSavedMediaHistory.remove(key)
        usbSavedMediaHistory[key] = UsbSavedMediaRecord(
            savedMedia = savedMedia,
            width = width,
            height = height,
            captureTimestampMillis = captureTimestampMillis,
        )
        while (usbSavedMediaHistory.size > 128) {
            val oldestKey = usbSavedMediaHistory.keys.firstOrNull() ?: break
            usbSavedMediaHistory.remove(oldestKey)
        }
    }

    private fun normalizeSavedMediaKey(fileName: String): String = fileName.lowercase(Locale.US)

    private fun resolveSavedMediaForKnownFileName(fileName: String): OmCaptureUsbSavedMedia? {
        return usbSavedMediaByFileName[normalizeSavedMediaKey(fileName)]
    }

    private fun buildConfiguredSaveBasePath(): String {
        val saveLocation = _uiState.value.autoImportConfig.saveLocation.ifBlank { "Pictures/db link" }
        return if (saveLocation.startsWith(Environment.DIRECTORY_PICTURES)) {
            saveLocation
        } else {
            "${Environment.DIRECTORY_PICTURES}/${saveLocation.removePrefix("Pictures/")}"
        }
    }

    private fun resolveSavedMediaMimeType(fileName: String): String = when {
        fileName.uppercase(Locale.US).endsWith(".JPG") || fileName.uppercase(Locale.US).endsWith(".JPEG") -> "image/jpeg"
        isRawFileName(fileName) -> "image/x-olympus-orf"
        fileName.uppercase(Locale.US).endsWith(".MOV") -> "video/quicktime"
        fileName.uppercase(Locale.US).endsWith(".MP4") -> "video/mp4"
        else -> "application/octet-stream"
    }

    private fun isRawFileName(fileName: String): Boolean {
        return fileName.uppercase(Locale.US).endsWith(".ORF") || fileName.uppercase(Locale.US).endsWith(".RAW")
    }

    private fun currentUsbImportDateFolder(): String {
        return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    }

    private fun OmCaptureUsbRuntimeState.toUiState(): OmCaptureUsbUiState {
        return OmCaptureUsbUiState(
            operationState = operationState,
            statusLabel = statusLabel,
            isBusy = isBusy,
            summary = summary,
            lastActionLabel = lastActionLabel,
            lastImportedFileName = lastImportedFileName,
            importedCount = importedCount,
            lastSavedMedia = lastSavedMedia,
            canRetry = canRetry,
        )
    }

    private fun refreshOmCaptureStudioState(
        base: MainUiState = _uiState.value,
        selectedSection: OmCaptureSection = base.omCaptureStudio.selectedSection,
    ) {
        // Merge caller-provided field overrides into the current state atomically.
        // The caller's `base` may carry updated remoteRuntime, omCaptureUsb, deepSkyState,
        // or tetherSaveTarget that are not yet in _uiState. We extract those overrides and
        // apply them inside the CAS loop so concurrent updates to unrelated fields survive.
        val overrideRemoteRuntime = base.remoteRuntime
        val overrideOmCaptureUsb = base.omCaptureUsb
        val overrideDeepSkyState = base.deepSkyState
        val overrideTetherSaveTarget = base.tetherSaveTarget
        val overrideTetherPhoneImportFormat = base.tetherPhoneImportFormat
        _uiState.update { current ->
            current.copy(
                remoteRuntime = overrideRemoteRuntime,
                omCaptureUsb = overrideOmCaptureUsb,
                deepSkyState = overrideDeepSkyState,
                tetherSaveTarget = overrideTetherSaveTarget,
                tetherPhoneImportFormat = overrideTetherPhoneImportFormat,
                omCaptureStudio = OmCaptureCapabilityRegistry.buildUiState(
                    selectedSection = selectedSection,
                    connectionLabel = overrideOmCaptureUsb.statusLabel,
                    deviceLabel = overrideOmCaptureUsb.summary?.deviceLabel,
                    lastActionLabel = overrideOmCaptureUsb.lastActionLabel,
                    liveViewActive = overrideRemoteRuntime.liveViewActive,
                    tetherSaveTarget = overrideTetherSaveTarget,
                    phoneImportFormat = overrideTetherPhoneImportFormat,
                    importedCount = overrideOmCaptureUsb.importedCount,
                    lastImportedFileName = overrideOmCaptureUsb.lastImportedFileName,
                    remoteRuntime = overrideRemoteRuntime,
                ),
            )
        }
        val committedState = _uiState.value
        syncOmCaptureUsbCaptureMonitoring(committedState)
        syncDeepSkyAutoCapture(committedState)
    }

    private fun syncOmCaptureUsbCaptureMonitoring(state: MainUiState = _uiState.value) {
        val enabled = state.tetherSaveTarget.savesToPhone && !shouldAutoRunDeepSkyCapture(state)
        if (usbCaptureEventMonitoringEnabled == enabled) return
        usbCaptureEventMonitoringEnabled = enabled
        omCaptureUsbManager.setCaptureEventMonitoringEnabled(enabled)
    }

    private fun shouldAutoRunDeepSkyCapture(state: MainUiState = _uiState.value): Boolean {
        return state.deepSkyState.isSessionActive &&
            state.remoteRuntime.modePickerSurface == ModePickerSurface.DeepSky &&
            state.omCaptureUsb.summary != null
    }

    private fun syncDeepSkyAutoCapture(state: MainUiState = _uiState.value) {
        val shouldRun = shouldAutoRunDeepSkyCapture(state)
        if (!shouldRun) {
            deepSkyAutoCaptureJob?.cancel()
            deepSkyAutoCaptureJob = null
            nextDeepSkyProtectionProbeAtMs = 0L
            return
        }
        if (deepSkyAutoCaptureJob?.isActive == true) {
            return
        }
        val previousJob = deepSkyAutoCaptureJob
        deepSkyAutoCaptureJob = viewModelScope.launch {
            // Wait for previous loop iteration to fully terminate before starting a new one
            previousJob?.join()
            runDeepSkyAutoCaptureLoop()
        }
    }

    private suspend fun runDeepSkyAutoCaptureLoop() {
        while (shouldAutoRunDeepSkyCapture()) {
            val state = _uiState.value
            if (state.deepSkyState.performanceMode == DeepSkyPerformanceMode.ProtectionPaused) {
                val nowMs = SystemClock.elapsedRealtime()
                if (nextDeepSkyProtectionProbeAtMs == 0L) {
                    nextDeepSkyProtectionProbeAtMs = nowMs + DEEP_SKY_PROTECTION_PROBE_BACKOFF_MS
                }
                val remainingBackoffMs = (nextDeepSkyProtectionProbeAtMs - nowMs).coerceAtLeast(0L)
                if (remainingBackoffMs > 0L) {
                    if (isUsbModeSurface(state.remoteRuntime.modePickerSurface)) {
                        refreshOmCaptureStudioState(
                            state.copy(
                                remoteRuntime = state.remoteRuntime.copy(
                                    statusLabel = buildDeepSkyProtectionBackoffStatus(remainingBackoffMs),
                                ),
                            ),
                        )
                    }
                    delay(remainingBackoffMs)
                    continue
                }
                nextDeepSkyProtectionProbeAtMs = nowMs + DEEP_SKY_PROTECTION_PROBE_BACKOFF_MS
                D.astro(
                    "Deep Sky auto capture paused by protection mode; " +
                        "allowing a recovery probe capture every ${DEEP_SKY_PROTECTION_PROBE_BACKOFF_MS}ms",
                )
            } else {
                nextDeepSkyProtectionProbeAtMs = 0L
            }
            if (state.omCaptureUsb.isBusy || reconnectRestorationActive) {
                delay(240L)
                continue
            }

            val cycleStartMs = SystemClock.elapsedRealtime()
            val result = omCaptureUsbManager.captureAndImportLatestImage(
                saveMedia = ::saveOmCaptureUsbMedia,
                importFormat = state.tetherPhoneImportFormat,
            )
            if (!shouldAutoRunDeepSkyCapture()) {
                break
            }

            val succeeded = result.fold(
                onSuccess = { image ->
                    rememberImportedUsbResult(image)
                    handleOmCaptureUsbImportResult(
                        image = image,
                        scanFilesNow = false,
                    )
                    if (isUsbModeSurface(_uiState.value.remoteRuntime.modePickerSurface)) {
                        refreshOmCaptureStudioState(
                            _uiState.value.copy(
                                remoteRuntime = _uiState.value.remoteRuntime.copy(
                                    liveViewActive = omCaptureUsbManager.isLiveViewActive,
                                    statusLabel = buildDeepSkyAutoCaptureStatus(_uiState.value.deepSkyState),
                                ),
                            ),
                        )
                    }
                    true
                },
                onFailure = { throwable ->
                    D.err("ASTRO", "Deep Sky auto capture/import failed", throwable)
                    refreshOmCaptureStudioState(
                        _uiState.value.copy(
                            remoteRuntime = _uiState.value.remoteRuntime.copy(
                                statusLabel = throwable.message ?: "Deep Sky auto capture failed",
                            ),
                        ),
                    )
                    false
                },
            )
            if (!succeeded) {
                delay(1_000L)
                continue
            }

            val cycleElapsedMs = SystemClock.elapsedRealtime() - cycleStartMs
            val cadenceMs = expectedDeepSkyCadenceMs(_uiState.value.deepSkyState)
            val remainingMs = (cadenceMs - cycleElapsedMs).coerceAtLeast(350L)
            delay(remainingMs)
        }
    }

    private fun expectedDeepSkyCadenceMs(
        state: DeepSkyLiveStackUiState,
    ): Long {
        val shutterSeconds = usbCameraProperties.value.shutterSpeed
            ?.currentValue
            ?.let(::olympusShutterSpeedSeconds)
        val presetCadenceSeconds = state.selectedPreset?.stackCadenceExpectationSec
            ?: state.selectedPreset?.recommendedShutterSec
            ?: 12.0
        val targetSeconds = maxOf(
            presetCadenceSeconds,
            (shutterSeconds ?: 0.0) + 1.0,
        )
        return (targetSeconds * 1000.0).toLong().coerceAtLeast(1_500L)
    }

    private fun buildDeepSkyAutoCaptureStatus(
        state: DeepSkyLiveStackUiState,
    ): String {
        val frameLabel = if (state.frameCount > 0) "${state.frameCount} frames" else "arming"
        val totalSeconds = state.totalExposureSec.roundToInt().coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val timeLabel = if (minutes > 0) {
            "${minutes}m ${seconds}s"
        } else {
            "${seconds}s"
        }
        return "Deep Sky auto capture • $frameLabel • $timeLabel"
    }

    private fun buildDeepSkyProtectionBackoffStatus(remainingBackoffMs: Long): String {
        val seconds = (remainingBackoffMs / 1000L).coerceAtLeast(1L)
        return "Cooling device before next Deep Sky capture (${seconds}s)"
    }

    fun selectOmCaptureSection(section: OmCaptureSection) {
        recordQaUiAction(title = "Select OM Capture section", detail = section.title)
        refreshOmCaptureStudioState(selectedSection = section)
    }

    fun refreshOmCaptureCapabilities() {
        refreshOmCaptureStudioState()
    }

    fun dispatchOmCaptureAction(action: OmCaptureAction) {
        when (action) {
            is OmCaptureAction.SelectSection -> selectOmCaptureSection(action.section)
            is OmCaptureAction.SetSaveTarget -> updateTetherSaveTarget(action.target)
            is OmCaptureAction.SetPhoneImportFormat -> updateTetherPhoneImportFormat(action.format)
            is OmCaptureAction.SetExposureCompensation -> setExposureCompensationValue(action.value)
            is OmCaptureAction.SetCameraExposureMode -> setCameraExposureMode(action.mode)
            is OmCaptureAction.SetPropertyValue -> setPropertyValue(action.picker, action.value, action.closePicker)
            is OmCaptureAction.SetDriveMode -> setDriveMode(action.mode)
            is OmCaptureAction.SetTimerMode -> setTimerMode(action.mode)
            is OmCaptureAction.SetTimerDelay -> setTimerDelay(action.seconds)
            is OmCaptureAction.SetIntervalSeconds -> setIntervalSeconds(action.seconds)
            is OmCaptureAction.SetIntervalCount -> setIntervalCount(action.count)
            OmCaptureAction.RefreshCapabilities -> refreshOmCaptureCapabilities()
            OmCaptureAction.RefreshUsbConnection -> refreshOmCaptureUsb()
            OmCaptureAction.ToggleUsbLiveView -> toggleUsbLiveView()
            OmCaptureAction.CaptureRemotePhoto -> captureRemotePhoto()
            OmCaptureAction.CaptureImport -> captureOmCaptureUsbPhoto()
            OmCaptureAction.ImportLatest -> importLatestOmCaptureUsbImage()
            OmCaptureAction.ClearUsbStatus -> clearOmCaptureUsbState()
            OmCaptureAction.StartInterval -> startInterval()
            OmCaptureAction.StopInterval -> stopInterval()
            OmCaptureAction.RefreshUsbProperties -> refreshUsbProperties()
            is OmCaptureAction.SetUsbProperty -> setUsbProperty(action.propCode, action.value)
            is OmCaptureAction.ManualFocusDrive -> manualFocusDrive(action.steps)
            OmCaptureAction.CaptureWhileLiveView -> captureWhileLiveView()
        }
    }

    fun refresh() {
        recordQaUiAction(
            title = "Refresh session",
            detail = "wifi=${_uiState.value.wifiState}",
        )
        val currentWifi = _uiState.value.wifiState
        D.ui("refresh() wifiState=$currentWifi, sessionState=${_uiState.value.sessionState}")

        if (isFirstConnectActive) {
            D.ui("refresh(): ignored because first-connect via QR is active")
            return
        }

        if (handshakeJob?.isActive == true || reconnectJob?.isActive == true || _uiState.value.isRefreshing) {
            queuedRefreshAfterHandshake = true
            D.ui("refresh(): queued because a handshake/reconnect is already running")
            return
        }

        if (currentWifi is WifiConnectionState.WifiOff) {
            updateUiState { it.copy(
                isRefreshing = false,
                refreshStatus = "WiFi is off",
                protocolError = "Turn on WiFi and connect to your camera's WiFi network",
                sessionState = CameraSessionState.Idle,
            ) }
            return
        }
        if (currentWifi is WifiConnectionState.Disconnected) {
            val savedSsid = _uiState.value.selectedCameraSsid
            D.reconnect("RECONNECT_AUTO suppressed on refresh (wifi=Disconnected, savedSsid=$savedSsid)")
            updateUiState { current -> current.copy(
                isRefreshing = false,
                refreshStatus = if (current.hasSavedCamera) "Press Reconnect to connect" else "Not connected",
                protocolError = if (current.hasSavedCamera)
                    "Camera WiFi not found. Use the Reconnect button on the dashboard."
                else
                    "Connect to your camera's WiFi network to get started",
                sessionState = CameraSessionState.Idle,
            ) }
            return
        }
        if (currentWifi is WifiConnectionState.OtherWifi &&
            _uiState.value.hasSavedCamera &&
            _uiState.value.selectedCameraSsid != null
        ) {
            D.reconnect("RECONNECT_AUTO suppressed on refresh (wifi=OtherWifi, savedSsid=${_uiState.value.selectedCameraSsid})")
            updateUiState { it.copy(
                isRefreshing = false,
                refreshStatus = "Wrong network — press Reconnect",
                protocolError = "Connected to \"${currentWifi.ssid ?: "unknown"}\". Use the Reconnect button on the dashboard.",
                sessionState = CameraSessionState.Idle,
            ) }
            return
        }
        if (currentWifi is WifiConnectionState.OtherWifi) {
            updateUiState { it.copy(
                isRefreshing = false,
                refreshStatus = "Wrong network",
                protocolError = "Connected to \"${currentWifi.ssid ?: "unknown"}\". Switch to your camera's WiFi network.",
                sessionState = CameraSessionState.Idle,
            ) }
            return
        }

        startCameraHandshake()
    }

    private fun startCameraHandshake(refreshStatus: String = "Connecting...") {
        if (handshakeJob?.isActive == true) {
            queuedRefreshAfterHandshake = true
            D.ui("startCameraHandshake: queued another refresh while handshake is active")
            return
        }
        handshakeJob = viewModelScope.launch {
            try {
                performCameraHandshake(refreshStatus)
            } finally {
                handshakeJob = null
                if (_uiState.value.isRefreshing && reconnectJob?.isActive != true) {
                    updateUiState { current ->
                        if (!current.isRefreshing) {
                            current
                        } else {
                            current.copy(isRefreshing = false)
                        }
                    }
                }
                val shouldRetry = queuedRefreshAfterHandshake &&
                    reconnectJob?.isActive != true &&
                    _uiState.value.wifiState is WifiConnectionState.CameraWifi
                queuedRefreshAfterHandshake = false
                if (shouldRetry) {
                    D.ui("startCameraHandshake: draining queued refresh")
                    startCameraHandshake(refreshStatus = "Refreshing camera...")
                }
            }
        }
    }

    private suspend fun performCameraHandshake(refreshStatus: String) {
        val workspace = runCameraHandshakePipeline(refreshStatus) ?: return
        commitConnectedWorkspace(workspace)
    }

    private suspend fun runCameraHandshakePipeline(refreshStatus: String): CameraWorkspace? {
        updateUiState { current ->
            val discoveryState = sessionStateMachine.reduce(
                current.sessionState,
                CameraSessionEvent.StartDiscovery,
            )
            val connectingState = sessionStateMachine.reduce(
                discoveryState,
                CameraSessionEvent.BeginConnect(current.workspace.camera.connectionMethod),
            )
            current.copy(
                isRefreshing = true,
                refreshStatus = refreshStatus,
                protocolError = null,
                sessionState = connectingState,
            )
        }

        val readinessResult = repository.probeConnectionReady()
        if (readinessResult.isFailure) {
            val message = readinessResult.exceptionOrNull()?.message ?: "Camera is not ready yet"
            updateUiState { current ->
                current.copy(
                    isRefreshing = false,
                    refreshStatus = "Camera not ready",
                    protocolError = message,
                    sessionState = sessionStateMachine.reduce(
                        current.sessionState,
                        CameraSessionEvent.Fail(message),
                    ),
                )
            }
            return null
        }
        D.proto("READINESS: connection probe succeeded, proceeding with workspace load")

        // Use explicit try/catch instead of runCatching+onSuccess to guarantee
        // suspend calls (activateRecMode) are properly awaited.
        val handshakeStartMs = System.currentTimeMillis()
        D.timeStart("full_handshake")
        val workspace: CameraWorkspace
        try {
            D.timeStart("loadWorkspace")
            workspace = repository.loadWorkspace()
            D.timeEnd("PROTO", "loadWorkspace", "Handshake complete")
        } catch (throwable: Exception) {
            D.err("PROTO", "Failed to load workspace", throwable)
            val message = throwable.message ?: "Connection failed"
            updateUiState { current ->
                current.copy(
                    isRefreshing = false,
                    refreshStatus = "Unable to connect",
                    protocolError = message,
                    sessionState = sessionStateMachine.reduce(
                        current.sessionState,
                        CameraSessionEvent.Fail(message),
                    ),
                )
            }
            return null
        }

        D.proto("Workspace loaded: mode=${workspace.camera.connectMode}, caps=${workspace.capabilities.size}, commands=${workspace.protocol.commandList.commands.size}")

        // Proceed after a single rec-mode activation once the camera reports ready.
        D.proto("Activating rec mode (single request) post-handshake")
        D.timeStart("rec_mode_activation")
        val recModeResult = repository.activateRecMode()
        if (recModeResult.isFailure) {
            D.err(
                "PROTO",
                "Post-handshake rec mode activation failed",
                recModeResult.exceptionOrNull(),
            )
        } else {
            D.proto("Rec mode activated successfully post-handshake")
        }
        D.proto("READINESS: proceeding after rec mode activation without an extra blocking gate")
        D.timeEnd("PROTO", "rec_mode_activation", "Rec mode request complete")

        // Note: set_utctimediff is always sent inside loadWorkspace().
        // geotag_sync_clock controls whether the app trusts the clock sync for geotag matching:
        // when enabled, clockOffsetMinutes is reset to 0 on connect (clocks are aligned);
        // when disabled, any user-set clock offset is preserved.
        var geoTaggingToPersist: GeoTaggingSnapshot? = null
        updateUiState { current ->
            val syncClockEnabled = current.settings
                .firstOrNull { it.id == "geotag_sync_clock" }?.enabled != false
            if (!syncClockEnabled || current.geoTagging.clockOffsetMinutes == 0) {
                current
            } else {
                D.transfer("geotag_sync_clock enabled: resetting clockOffsetMinutes to 0 (trusting set_utctimediff)")
                val updatedGeoTagging = current.geoTagging.copy(clockOffsetMinutes = 0)
                geoTaggingToPersist = updatedGeoTagging
                current.copy(geoTagging = updatedGeoTagging)
            }
        }
        geoTaggingToPersist?.let { snapshot ->
            viewModelScope.launch { preferencesRepository.saveGeoTagging(snapshot) }
        }
        val totalHandshakeMs = System.currentTimeMillis() - handshakeStartMs
        D.timeEnd("PROTO", "full_handshake", "Total handshake + pipeline activation: ${totalHandshakeMs}ms")
        return workspace
    }

    private fun commitConnectedWorkspace(workspace: CameraWorkspace) {
        activePlayTargetSlot = null
        emptyPropertyLoadRetries = 0
        updateUiState { current ->
            val connectedSessionState = sessionStateMachine.reduce(
                current.sessionState,
                CameraSessionEvent.Connected(
                    method = workspace.camera.connectionMethod,
                    mode = workspace.camera.connectMode,
                ),
            )
            current.copy(
                workspace = workspace,
                isRefreshing = false,
                refreshStatus = workspace.generatedAtLabel,
                protocolError = null,
                sessionState = connectedSessionState,
                remoteRuntime = current.remoteRuntime.copy(
                    focusHeld = false,
                    isFocusing = false,
                    touchFocusPoint = null,
                    activePicker = ActivePropertyPicker.None,
                    modePickerSurface = ModePickerSurface.CameraModes,
                ),
            )
        }
        // Start background session keepalive after the connect flow is complete.
        startSessionKeepAlive()

        // Keep the rec/live-view pipeline intact here.
        // Auto-import is armed after property restoration and only starts on the next
        // library load, where play-mode image browsing is already expected.
        D.transfer("triggerAutoImport: deferred until library load")

        val activeSsid = cameraWifiManager.getCurrentSsid() ?: _uiState.value.selectedCameraSsid
        if (!activeSsid.isNullOrBlank()) {
            viewModelScope.launch {
                preferencesRepository.updateCameraDisplayName(activeSsid, workspace.camera.name)
                refreshSavedCamerasState()
                syncSelectedCardSlotSourceFromCamera()
            }
        }
    }

    private fun stageReconnectRemoteHandoff(
        workspace: CameraWorkspace,
        restoreLiveView: Boolean,
    ): CompletableDeferred<Unit> {
        clearPendingReconnectHandoffState(cancelCompletion = true)
        val completion = CompletableDeferred<Unit>()
        val token = reconnectHandoffGeneration.incrementAndGet()
        pendingReconnectWorkspace = workspace
        pendingReconnectRestoreLiveView = restoreLiveView
        pendingReconnectCompletion = completion
        updateUiState { current -> current.copy(
            pendingReconnectHandoffToken = token,
            remoteRuntime = current.remoteRuntime.copy(
                focusHeld = false,
                propertiesLoaded = false,
                statusLabel = "Opening remote control...",
            ),
            refreshStatus = "Opening remote control...",
            protocolError = null,
        ) }
        D.reconnect(
            "RECONNECT HANDOFF staged: token=$token, restoreLiveView=$restoreLiveView, " +
                "destination=Remote",
        )
        return completion
    }

    private fun clearPendingReconnectHandoffState(cancelCompletion: Boolean) {
        if (cancelCompletion) {
            pendingReconnectCompletion?.cancel()
        }
        pendingReconnectCompletion = null
        pendingReconnectWorkspace = null
        pendingReconnectRestoreLiveView = false
        if (_uiState.value.pendingReconnectHandoffToken != null) {
            updateUiState { it.copy(pendingReconnectHandoffToken = null) }
        }
    }

    suspend fun completePendingReconnectHandoff(token: Long) {
        if (_uiState.value.pendingReconnectHandoffToken != token) {
            return
        }
        val workspace = pendingReconnectWorkspace ?: run {
            clearPendingReconnectHandoffState(cancelCompletion = true)
            return
        }
        val completion = pendingReconnectCompletion
        try {
            D.reconnect("RECONNECT HANDOFF completing: token=$token")
            commitConnectedWorkspace(workspace)

            D.reconnect("RECONNECT LIFECYCLE: loading camera properties")
            D.timeStart("reconnect_prop_load")
            loadCameraPropertiesAndAwait()
            D.timeEnd("RECONNECT", "reconnect_prop_load", "Property reload complete")
            D.reconnect(
                "RECONNECT LIFECYCLE: properties loaded, mode=${_uiState.value.remoteRuntime.exposureMode}, " +
                    "F=${_uiState.value.remoteRuntime.aperture.currentValue}, " +
                    "SS=${_uiState.value.remoteRuntime.shutterSpeed.currentValue}, " +
                    "ISO=${_uiState.value.remoteRuntime.iso.currentValue}",
            )

            val shouldRecoverLiveView = pendingReconnectRestoreLiveView
            pendingReconnectRestoreLiveView = false
            if (shouldRecoverLiveView) {
                D.stream("Recovering live view after reconnection")
                D.timeStart("reconnect_lv_restore")
                delay(500)
                startLiveViewInternal()
                D.timeEnd("RECONNECT", "reconnect_lv_restore", "Live view restored")
            }

            reconnectRestorationActive = false
            autoImportArmed = true
            D.reconnect("Reconnect restoration complete — auto-import unblocked")
            completion?.complete(Unit)
        } catch (throwable: Exception) {
            D.err("RECONNECT", "Reconnect handoff completion failed", throwable)
            stopSessionKeepAlive()
            updateUiState { current -> current.copy(
                isRefreshing = false,
                refreshStatus = "Reconnect failed",
                protocolError = throwable.message ?: "Could not open remote control.",
                sessionState = sessionStateMachine.reduce(
                    current.sessionState,
                    CameraSessionEvent.Fail(throwable.message ?: "Reconnect failed"),
                ),
            ) }
            completion?.completeExceptionally(throwable)
            throw throwable
        } finally {
            clearPendingReconnectHandoffState(cancelCompletion = false)
        }
    }

    private fun startSavedCameraReconnect(
        ssidOverride: String? = null,
        initialStatus: String = "Reconnecting to saved camera...",
    ) {
        D.reconnect("AUTO_PATH: RECONNECT entry — startSavedCameraReconnect: ssid=$ssidOverride, " +
            "isFirstConnectActive=$isFirstConnectActive, reconnectJob.active=${reconnectJob?.isActive}")
        if (isFirstConnectActive) {
            D.reconnect("startSavedCameraReconnect: ignoring invocation because first-connect via QR is active")
            return
        }
        if (reconnectJob?.isActive == true) {
            D.reconnect("cancelling stale reconnect before starting a new one")
            reconnectJob?.cancel()
        }
        handshakeJob?.cancel()
        val reconnectId = reconnectGeneration.incrementAndGet()
        reconnectJob = viewModelScope.launch {
            isAutoConnecting = true
            suppressWifiAutoRefresh = true
            val reconnectStartMs = System.currentTimeMillis()
            D.timeStart("full_reconnect")
            try {
                val savedProfile = preferencesRepository.loadSavedCameraProfile(ssidOverride)
                val credentials = savedProfile?.let {
                    WifiCredentials(
                        ssid = it.ssid,
                        password = it.password,
                        bleName = it.bleName,
                        blePass = it.blePass,
                    )
                } ?: preferencesRepository.loadCameraCredentials(ssidOverride)
                if (credentials == null) {
                    D.ble("autoConnect: no saved credentials")
                    updateUiState { it.copy(
                        isRefreshing = false,
                        refreshStatus = "No saved camera",
                        protocolError = "Scan a camera QR code once before using saved reconnect.",
                        sessionState = CameraSessionState.Idle,
                    ) }
                    return@launch
                }

                if (!cameraWifiManager.isWifiEnabled()) {
                    D.wifi("autoConnect: WiFi is disabled")
                    updateUiState { it.copy(
                        isRefreshing = false,
                        refreshStatus = "WiFi is off",
                        protocolError = "Turn on WiFi to reconnect to ${credentials.ssid}.",
                        sessionState = CameraSessionState.Idle,
                    ) }
                    return@launch
                }

                preferencesRepository.selectSavedCamera(credentials.ssid)
                refreshSavedCamerasState()
                val reconnectBleIdentity = preferencesRepository.loadReconnectBleIdentity(credentials.ssid)
                val wakeTargetBleName = reconnectBleIdentity?.bleName?.takeIf { it.isNotBlank() }
                    ?: savedProfile?.bleName?.takeIf { it.isNotBlank() }
                    ?: credentials.bleName?.takeIf { it.isNotBlank() }
                    ?: resolveWakeTargetBleName(savedProfile, credentials.ssid)
                val wakeTargetBlePass = reconnectBleIdentity?.blePass?.takeIf { it.isNotBlank() }
                    ?: savedProfile?.blePass?.takeIf { it.isNotBlank() }
                    ?: credentials.blePass?.takeIf { it.isNotBlank() }
                val wakeTargetBleAddress = reconnectBleIdentity?.bleAddress
                    ?: savedProfile?.bleAddress

                if (cameraWifiManager.isConnectedToCameraSsid(credentials.ssid)) {
                    D.wifi("autoConnect: already on ${credentials.ssid}, starting handshake directly")
                    pendingPropertyTargets.clear(); pendingPropertyTimestamps.clear()
                    stopLiveViewInternal()
                    thumbnailJob?.cancel()
                    thumbnailJob = null
                    detailPreviewJob?.cancel()
                    detailPreviewJob = null
                    propertyApplyJob?.cancel()
                    propertyLoadJob?.cancel()
                    propertyRefreshJob?.cancel()
                    updateUiState { current -> current.copy(
                        isRefreshing = true,
                        refreshStatus = "Opening remote control...",
                        protocolError = null,
                        sessionState = CameraSessionState.Idle,
                        remoteRuntime = current.remoteRuntime.copy(
                            liveViewActive = false,
                            focusHeld = false,
                            propertiesLoaded = false,
                            statusLabel = "Opening remote control...",
                        ),
                    ) }
                    _liveViewFrame.value = null
                    reconnectRestorationActive = true
                    D.reconnect("Reconnect restoration active — auto-import blocked until restoration complete")
                    D.timeStart("reconnect_handshake")
                    val workspace = runCameraHandshakePipeline(refreshStatus = "Opening remote control...")
                    if (workspace == null) {
                        D.reconnect("Handshake did not reach remote handoff stage, skipping recovery")
                        recoverLiveViewAfterReconnect = false
                        reconnectRestorationActive = false
                        return@launch
                    }
                    D.timeEnd("RECONNECT", "reconnect_handshake", "Handshake phase complete")
                    val shouldRecoverLiveView = recoverLiveViewAfterReconnect
                    recoverLiveViewAfterReconnect = false
                    stageReconnectRemoteHandoff(
                        workspace = workspace,
                        restoreLiveView = shouldRecoverLiveView,
                    ).await()
                    val totalReconnectMs = System.currentTimeMillis() - reconnectStartMs
                    D.timeEnd("RECONNECT", "full_reconnect", "Total reconnect: ${totalReconnectMs}ms (lv_restored=$shouldRecoverLiveView)")
                    return@launch
                }

                pendingPropertyTargets.clear(); pendingPropertyTimestamps.clear()
                stopLiveViewInternal()
                thumbnailJob?.cancel()
                thumbnailJob = null
                detailPreviewJob?.cancel()
                detailPreviewJob = null
                propertyApplyJob?.cancel()
                propertyLoadJob?.cancel()
                propertyRefreshJob?.cancel()
                updateUiState { current -> current.copy(
                    isRefreshing = true,
                    refreshStatus = initialStatus,
                    protocolError = null,
                    sessionState = CameraSessionState.Idle,
                    remoteRuntime = current.remoteRuntime.copy(
                        liveViewActive = false,
                        focusHeld = false,
                        statusLabel = initialStatus,
                    ),
                ) }
                _liveViewFrame.value = null

                if (cameraBleManager.isBleConnected) {
                    D.ble("startSavedCameraReconnect: closing stale BLE session before wake-up")
                    cameraBleManager.disconnect()
                    delay(350L)
                }
                D.reconnect("RECONNECT LIFECYCLE: clearing stale WiFi binding, starting BLE wake")
                D.timeStart("ble_wake")
                cameraWifiManager.clearRequestedCameraConnection(clearBinding = true)

                updateUiState { it.copy(
                    isRefreshing = true,
                    refreshStatus = RECONNECT_BLE_FOUND_STATUS,
                    protocolError = null,
                ) }
                D.ble(
                    "saved reconnect wake target resolved: ssid=${credentials.ssid}, " +
                        "bleName=$wakeTargetBleName, bleAddress=$wakeTargetBleAddress, " +
                        "blePassSet=${!wakeTargetBlePass.isNullOrBlank()}, blePassLen=${wakeTargetBlePass?.length ?: 0}",
                )
                D.ble("isReconnect=true (resolvedBleName=$wakeTargetBleName, resolvedBleAddress=$wakeTargetBleAddress, " +
                    "savedBleName=${savedProfile?.bleName}, savedBleAddress=${savedProfile?.bleAddress}, " +
                    "savedBlePassSet=${!savedProfile?.blePass.isNullOrBlank()}, " +
                    "prefsBlePassSet=${!reconnectBleIdentity?.blePass.isNullOrBlank()})")
                val wakeResult = cameraBleManager.wakeUpCamera(
                    targetSsid = credentials.ssid,
                    targetBleName = wakeTargetBleName,
                    targetBlePass = wakeTargetBlePass,
                    targetBleAddress = wakeTargetBleAddress,
                    isReconnect = true,
                )
                if (wakeResult.isFailure) {
                    val error = wakeResult.exceptionOrNull()
                    updateUiState { it.copy(
                        isRefreshing = false,
                        refreshStatus = "Bluetooth wake failed",
                        protocolError = error?.message ?: "Could not wake the camera over Bluetooth.",
                        sessionState = CameraSessionState.Idle,
                    ) }
                    return@launch
                }
                cameraBleManager.latestWakeIdentity?.let { identity ->
                    preferencesRepository.updateCameraBleIdentity(
                        ssid = credentials.ssid,
                        bleName = identity.name,
                        blePass = wakeTargetBlePass,
                        bleAddress = identity.address,
                    )
                }
                D.timeEnd("RECONNECT", "ble_wake", "BLE wake phase complete")
                D.timeStart("wifi_join")
                updateUiState { it.copy(
                    isRefreshing = true,
                    refreshStatus = RECONNECT_WIFI_STATUS,
                ) }
                val wifiFound = reconnectCameraWifi(credentials.ssid, credentials.password)
                if (!wifiFound) {
                    recoverLiveViewAfterReconnect = false
                    updateUiState { it.copy(
                        isRefreshing = false,
                        refreshStatus = "Camera WiFi unavailable",
                        protocolError = "Could not switch to ${credentials.ssid}. Check the camera WiFi and try again.",
                        sessionState = CameraSessionState.Idle,
                    ) }
                    return@launch
                }

                D.timeEnd("RECONNECT", "wifi_join", "WiFi join complete")
                reconnectRestorationActive = true
                D.reconnect("Reconnect restoration active — auto-import blocked until restoration complete")

                D.timeStart("reconnect_handshake")
                val workspace = runCameraHandshakePipeline(refreshStatus = "Opening remote control...")
                if (workspace == null) {
                    D.reconnect("Handshake did not reach remote handoff stage, skipping recovery")
                    recoverLiveViewAfterReconnect = false
                    reconnectRestorationActive = false
                    return@launch
                }
                D.timeEnd("RECONNECT", "reconnect_handshake", "Handshake phase complete")
                val shouldRecoverLiveView = recoverLiveViewAfterReconnect
                recoverLiveViewAfterReconnect = false
                stageReconnectRemoteHandoff(
                    workspace = workspace,
                    restoreLiveView = shouldRecoverLiveView,
                ).await()
                val totalReconnectMs = System.currentTimeMillis() - reconnectStartMs
                D.timeEnd("RECONNECT", "full_reconnect", "Total reconnect: ${totalReconnectMs}ms (lv_restored=$shouldRecoverLiveView)")
            } finally {
                if (reconnectGeneration.get() == reconnectId) {
                    isAutoConnecting = false
                    suppressWifiAutoRefresh = false
                    reconnectRestorationActive = false
                    reconnectJob = null
                    clearPendingReconnectHandoffState(cancelCompletion = true)
                    // Always clear isRefreshing so refresh() is never permanently blocked
                    if (_uiState.value.isRefreshing) {
                        updateUiState { it.copy(isRefreshing = false) }
                    }
                    val shouldRetry = queuedRefreshAfterHandshake &&
                        _uiState.value.wifiState is WifiConnectionState.CameraWifi
                    queuedRefreshAfterHandshake = false
                    if (shouldRetry) {
                        startCameraHandshake(refreshStatus = "Refreshing camera...")
                    }
                }
            }
        }
    }

    /** Disconnect only the camera Wi-Fi session, keeping the phone's WiFi on. */
    fun disconnectCamera() {
        D.session("disconnectCamera: user-initiated disconnect")
        D.session("AUTO_PATH: disconnectCamera — resetting isFirstConnectActive=${isFirstConnectActive}, isAutoConnecting=${isAutoConnecting}")
        isFirstConnectActive = false
        isAutoConnecting = false
        autoImportArmed = false
        activePlayTargetSlot = null
        emptyPropertyLoadRetries = 0
        stopSessionKeepAlive()
        stopLiveViewInternal()
        reconnectJob?.cancel()
        handshakeJob?.cancel()
        propertyApplyJob?.cancel()
        propertyLoadJob?.cancel()
        propertyRefreshJob?.cancel()
        cameraBleManager.disconnect()
        cameraWifiManager.clearRequestedCameraConnection(clearBinding = true)
        clearPendingReconnectHandoffState(cancelCompletion = true)
        updateUiState { current -> current.copy(
            isRefreshing = false,
            refreshStatus = "Disconnected",
            protocolError = null,
            sessionState = CameraSessionState.Idle,
            remoteRuntime = current.remoteRuntime.copy(
                liveViewActive = false,
                focusHeld = false,
                propertiesLoaded = false,
                statusLabel = "Disconnected by user",
            ),
        ) }
        _liveViewFrame.value = null
        D.session("disconnectCamera: session cleared, state=Idle")
    }

    /**
     * FIRST-CONNECT FLOW: QR onboarding for a brand-new camera.
     *
     * This is a completely separate path from [startSavedCameraReconnect].
     * It does NOT do BLE wake (camera WiFi is already active after QR scan),
     * does NOT enter the reconnect lifecycle, and uses its own log prefix.
     */
    fun connectToCameraViaQr(credentials: dev.dblink.feature.qr.WifiCredentials) {
        D.qr("AUTO_PATH: FIRST_CONNECT entry — connectToCameraViaQr: ssid=${credentials.ssid}, " +
            "isFirstConnectActive=$isFirstConnectActive, reconnectJob.active=${reconnectJob?.isActive}")
        D.qr("FIRST_CONNECT: connectToCameraViaQr: ssid=${credentials.ssid}")
        if (reconnectJob?.isActive == true) {
            D.qr("FIRST_CONNECT: cancelling stale reconnect before first-connect")
            reconnectJob?.cancel()
        }
        handshakeJob?.cancel()

        handshakeJob = viewModelScope.launch {
            isFirstConnectActive = true
            isAutoConnecting = true
            suppressWifiAutoRefresh = true
            val firstConnectStartMs = System.currentTimeMillis()
            D.timeStart("first_connect")
            try {
                // 1. Save credentials first
                preferencesRepository.saveCameraCredentials(
                    ssid = credentials.ssid,
                    password = credentials.password,
                    bleName = credentials.bleName,
                    blePass = credentials.blePass,
                )
                refreshSavedCamerasState()
                D.qr("FIRST_CONNECT: credentials saved for ssid=${credentials.ssid}")
                preferencesRepository.selectSavedCamera(credentials.ssid)
                val savedProfile = preferencesRepository.loadSavedCameraProfile(credentials.ssid)
                val previousSsid = _uiState.value.selectedCameraSsid
                val selectionChanged = !previousSsid.equals(credentials.ssid, ignoreCase = true)
                val transferState = _uiState.value.transferState
                if (selectionChanged) {
                    invalidateTransferLoads()
                    resetWifiThumbnailFallbackState()
                    activePlayTargetSlot = null
                    pendingLastCapturePreviewOpen = false
                    _liveViewFrame.value = null
                }

                updateUiState { current -> current.copy(
                    isRefreshing = true,
                    refreshStatus = "Joining ${credentials.ssid}...",
                    protocolError = null,
                    sessionState = CameraSessionState.Idle,
                    remoteRuntime = current.remoteRuntime.copy(
                        liveViewActive = false,
                        focusHeld = false,
                        statusLabel = "Connecting...",
                    ),
                    selectedCameraSsid = credentials.ssid,
                    selectedCardSlotSource = savedProfile?.playTargetSlot,
                    transferState = if (selectionChanged) {
                        transferState.copy(
                            images = emptyList(),
                            thumbnails = emptyMap(),
                            isLoading = false,
                            isDownloading = false,
                            downloadProgress = "",
                            errorMessage = null,
                            selectedImage = null,
                            selectedImages = emptySet(),
                            isSelectionMode = false,
                            matchedGeoTags = emptyMap(),
                            previewUnavailable = emptySet(),
                            sourceCameraSsid = null,
                        )
                    } else {
                        transferState.copy(
                            isLoading = false,
                            errorMessage = null,
                        )
                    },
                    lastCaptureThumbnail = if (selectionChanged) null else current.lastCaptureThumbnail,
                ) }

                // 2. Join WiFi directly — no BLE wake needed for first connect.
                //    The camera WiFi is already broadcasting because the user just
                //    turned on the camera and scanned the QR code.
                D.qr("FIRST_CONNECT: joining WiFi directly (no BLE wake)")
                D.timeStart("first_connect_wifi")
                val wifiJoined = firstConnectJoinWifi(credentials.ssid, credentials.password)
                if (!wifiJoined) {
                    D.qr("FIRST_CONNECT: WiFi join failed for ${credentials.ssid}")
                    updateUiState { it.copy(
                        isRefreshing = false,
                        refreshStatus = "WiFi connection failed",
                        protocolError = "Could not connect to ${credentials.ssid}. Make sure the camera is on and WiFi is active.",
                        sessionState = CameraSessionState.Idle,
                    ) }
                    return@launch
                }
                D.timeEnd("FIRST_CONNECT", "first_connect_wifi", "WiFi join complete")

                // 3. Perform handshake (shared 6-step protocol)
                D.qr("FIRST_CONNECT: starting handshake")
                D.timeStart("first_connect_handshake")
                performCameraHandshake(refreshStatus = "Opening remote control...")
                D.timeEnd("FIRST_CONNECT", "first_connect_handshake", "Handshake phase complete")

                if (_uiState.value.sessionState !is CameraSessionState.Connected) {
                    D.qr("FIRST_CONNECT: handshake did not reach Connected, aborting")
                    return@launch
                }

                // 4. Load camera properties after the handshake.
                D.qr("FIRST_CONNECT: loading camera properties")
                D.timeStart("first_connect_props")
                loadCameraPropertiesAndAwait()
                D.timeEnd("FIRST_CONNECT", "first_connect_props", "Property load complete")

                // 5. Save BLE identity if available from WiFi SSID
                val bleName = credentials.bleName?.takeIf { it.isNotBlank() }
                    ?: CameraNameNormalizer.extractPairingToken(credentials.ssid)
                if (!bleName.isNullOrBlank() || !credentials.blePass.isNullOrBlank()) {
                    preferencesRepository.updateCameraBleIdentity(
                        ssid = credentials.ssid,
                        bleName = bleName,
                        blePass = credentials.blePass,
                        bleAddress = null,
                    )
                    D.qr(
                        "FIRST_CONNECT: saved BLE identity bleName=$bleName, " +
                            "blePassSet=${!credentials.blePass.isNullOrBlank()}",
                    )
                }

                val totalMs = System.currentTimeMillis() - firstConnectStartMs
                D.timeEnd("FIRST_CONNECT", "first_connect", "Total first-connect: ${totalMs}ms")
                D.qr("FIRST_CONNECT: complete, mode=${_uiState.value.remoteRuntime.exposureMode}, " +
                    "F=${_uiState.value.remoteRuntime.aperture.currentValue}, " +
                    "SS=${_uiState.value.remoteRuntime.shutterSpeed.currentValue}, " +
                    "ISO=${_uiState.value.remoteRuntime.iso.currentValue}")
                autoImportArmed = true
                D.transfer("FIRST_CONNECT: auto-import armed for next library load")
            } finally {
                isFirstConnectActive = false
                isAutoConnecting = false
                suppressWifiAutoRefresh = false
                handshakeJob = null
                if (_uiState.value.isRefreshing) {
                    updateUiState { it.copy(isRefreshing = false) }
                }
            }
        }
    }

    /**
     * WiFi join for first-connect: simpler than reconnect because camera WiFi
     * is already broadcasting. Tries to join directly without BLE wake.
     */
    private suspend fun firstConnectJoinWifi(ssid: String, password: String): Boolean {
        if (cameraWifiManager.isConnectedToCameraSsid(ssid)) {
            D.qr("FIRST_CONNECT: already on $ssid")
            return true
        }

        // Camera WiFi should already be visible — try joining immediately
        val maxAttempts = 3
        repeat(maxAttempts) { attempt ->
            if (cameraWifiManager.isConnectedToCameraSsid(ssid)) return true

            updateUiState { it.copy(
                refreshStatus = if (attempt == 0) "Connecting to $ssid..." else "Retrying WiFi (${attempt + 1}/$maxAttempts)...",
            ) }

            D.qr("FIRST_CONNECT: requestNetwork for $ssid (attempt ${attempt + 1}/$maxAttempts)")
            val result = cameraWifiManager.connectToCameraWifiAndAwait(
                ssid = ssid,
                password = password,
                timeoutMillis = 15000L,
            )
            if (result) {
                D.qr("FIRST_CONNECT: WiFi connected to $ssid")
                return true
            }

            if (attempt < maxAttempts - 1) {
                D.qr("FIRST_CONNECT: WiFi attempt ${attempt + 1} failed, retrying after cooldown")
                delay(2000L)
            }
        }
        return cameraWifiManager.isConnectedToCameraSsid(ssid)
    }

    /**
     * Manual reconnect triggered by user pressing the Reconnect button.
     * This is the ONLY entry point for saved-camera reconnect.
     */
    fun reconnectManual() {
        val ssid = _uiState.value.selectedCameraSsid
        D.reconnect("RECONNECT_MANUAL requested by user, RECONNECT_TARGET savedCamera=$ssid")
        if (ssid == null) {
            D.reconnect("RECONNECT_MANUAL: no saved camera selected")
            updateUiState { it.copy(
                refreshStatus = "No saved camera",
                protocolError = "Scan a camera QR code first to save a camera.",
            ) }
            return
        }
        startSavedCameraReconnect(
            ssidOverride = ssid,
            initialStatus = "Reconnecting to saved camera...",
        )
    }

    /**
     * BLE WiFi prep — uses Bluetooth to enable/prepare the camera's WiFi functionality.
     * Does NOT power on the camera, does NOT start reconnect.
     * After this, the user explicitly presses Reconnect to connect.
     */
    fun bleEnableWifi() {
        val ssid = _uiState.value.selectedCameraSsid
        D.ble("BLE_WIFI_PREP requested by user, target=$ssid")
        if (ssid == null) {
            D.ble("BLE_WIFI_PREP: no saved camera selected")
            updateUiState { it.copy(
                refreshStatus = "No saved camera",
                protocolError = "Scan a camera QR code first to save a camera.",
            ) }
            return
        }
        viewModelScope.launch {
            updateUiState { it.copy(
                isRefreshing = true,
                refreshStatus = "Enabling camera WiFi via Bluetooth...",
                protocolError = null,
            ) }
            try {
                val savedProfile = preferencesRepository.loadSavedCameraProfile(ssid)
                val reconnectBleIdentity = preferencesRepository.loadReconnectBleIdentity(ssid)
                val wakeTargetBleName = reconnectBleIdentity?.bleName?.takeIf { it.isNotBlank() }
                    ?: resolveWakeTargetBleName(savedProfile, ssid)
                val wakeTargetBlePass = reconnectBleIdentity?.blePass?.takeIf { it.isNotBlank() }
                    ?: savedProfile?.blePass?.takeIf { it.isNotBlank() }
                val wakeTargetBleAddress = reconnectBleIdentity?.bleAddress
                    ?: savedProfile?.bleAddress
                D.ble(
                    "BLE_WIFI_PREP: bleName=$wakeTargetBleName, bleAddress=$wakeTargetBleAddress, " +
                        "blePassSet=${!wakeTargetBlePass.isNullOrBlank()}, blePassLen=${wakeTargetBlePass?.length ?: 0}",
                )

                if (cameraBleManager.isBleConnected) {
                    cameraBleManager.disconnect()
                    delay(350L)
                }

                val wakeResult = cameraBleManager.wakeUpCamera(
                    targetSsid = ssid,
                    targetBleName = wakeTargetBleName,
                    targetBlePass = wakeTargetBlePass,
                    targetBleAddress = wakeTargetBleAddress,
                    isReconnect = true,
                )
                if (wakeResult.isSuccess) {
                    cameraBleManager.latestWakeIdentity?.let { identity ->
                        preferencesRepository.updateCameraBleIdentity(
                            ssid = ssid,
                            bleName = identity.name,
                            blePass = wakeTargetBlePass,
                            bleAddress = identity.address,
                        )
                    }
                    D.ble("BLE_WIFI_PREP: BLE protocol complete — now polling for camera WiFi SSID visibility")
                    updateUiState { it.copy(
                        isRefreshing = true,
                        refreshStatus = "BLE wake sent — waiting for camera WiFi...",
                    ) }
                    // Camera needs time to start WiFi after BLE wake.
                    // Poll for SSID visibility while the camera Wi-Fi comes online.
                    val wifiVisible = cameraWifiManager.waitForCameraSsidVisibility(
                        ssid = ssid,
                        timeoutMillis = 30000L,
                        scanDelayMillis = 2000L,
                    )
                    if (wifiVisible) {
                        D.ble("BLE_WIFI_PREP: SUCCESS — camera WiFi SSID $ssid is now visible")
                        updateUiState { it.copy(
                            isRefreshing = false,
                            refreshStatus = "Camera WiFi detected — press Reconnect",
                            protocolError = null,
                        ) }
                    } else {
                        D.ble("BLE_WIFI_PREP: camera WiFi SSID $ssid NOT visible after 30s polling")
                        updateUiState { it.copy(
                            isRefreshing = false,
                            refreshStatus = "BLE wake sent — WiFi not detected yet",
                            protocolError = "Camera WiFi did not appear within 30 seconds. " +
                                "The camera may need more time, or may need to be powered on manually. " +
                                "Try pressing Reconnect anyway.",
                        ) }
                    }
                } else {
                    val error = wakeResult.exceptionOrNull()
                    D.ble("BLE_WIFI_PREP failed — ${error?.message}")
                    updateUiState { it.copy(
                        isRefreshing = false,
                        refreshStatus = "Bluetooth WiFi prep failed",
                        protocolError = error?.message ?: "Could not enable camera WiFi via Bluetooth.",
                    ) }
                }
            } catch (e: Exception) {
                D.err("BLE", "BLE_WIFI_PREP failed", e)
                updateUiState { it.copy(
                    isRefreshing = false,
                    refreshStatus = "Bluetooth WiFi prep failed",
                    protocolError = e.message ?: "Bluetooth error",
                ) }
            }
        }
    }

    fun autoConnectWithSavedCredentials() {
        reconnectManual()
    }

    fun forgetCamera() {
        D.pref("forgetCamera: clearing saved credentials")
        viewModelScope.launch {
            preferencesRepository.forgetCamera(_uiState.value.selectedCameraSsid)
            refreshSavedCamerasState()
        }
    }

    fun selectSavedCamera(ssid: String) {
        D.pref("selectSavedCamera: ssid=$ssid")
        viewModelScope.launch {
            switchSavedCameraContext(ssid, reconnectImmediately = false)
        }
    }

    fun toggleSavedCameraPower(
        ssid: String,
        enabled: Boolean,
    ) {
        D.session("toggleSavedCameraPower: ssid=$ssid, enabled=$enabled")
        viewModelScope.launch {
            if (!enabled) {
                powerOffSavedCamera(ssid)
                return@launch
            }

            if (!_uiState.value.selectedCameraSsid.equals(ssid, ignoreCase = true)) {
                switchSavedCameraContext(ssid, reconnectImmediately = false)
            }
            bleEnableWifi()
        }
    }

    fun connectToSavedCamera(ssid: String) {
        D.pref("connectToSavedCamera: ssid=$ssid")
        viewModelScope.launch {
            switchSavedCameraContext(ssid, reconnectImmediately = true)
        }
    }

    fun forgetSavedCamera(ssid: String) {
        D.pref("forgetSavedCamera: ssid=$ssid")
        viewModelScope.launch {
            preferencesRepository.forgetCamera(ssid)
            refreshSavedCamerasState()
        }
    }

    fun updateRawProtocolInput(value: String) {
        updateUiState { it.copy(rawProtocolInput = value) }
    }

    private suspend fun switchSavedCameraContext(
        ssid: String,
        reconnectImmediately: Boolean,
    ) {
        val previousSsid = _uiState.value.selectedCameraSsid
        val savedProfile = preferencesRepository.loadSavedCameraProfile(ssid)
        val selectionChanged = !previousSsid.equals(ssid, ignoreCase = true)
        val switchingCamera = !previousSsid.isNullOrBlank() && !previousSsid.equals(ssid, ignoreCase = true)
        if (switchingCamera) {
            disconnectCamera()
            resetWifiThumbnailFallbackState()
            activePlayTargetSlot = null
            pendingLastCapturePreviewOpen = false
        }
        if (selectionChanged) {
            invalidateTransferLoads()
            val currentTransfer = _uiState.value.transferState
            val currentWifiSsid = cameraWifiManager.getCurrentSsid()
            val nextWifiState = when {
                !cameraWifiManager.isWifiEnabled() -> WifiConnectionState.WifiOff
                !currentWifiSsid.isNullOrBlank() && currentWifiSsid.equals(ssid, ignoreCase = true) ->
                    WifiConnectionState.CameraWifi(currentWifiSsid)
                !currentWifiSsid.isNullOrBlank() -> WifiConnectionState.OtherWifi(currentWifiSsid)
                else -> WifiConnectionState.Disconnected
            }
            updateUiState { it.copy(
                workspace = initialWorkspace,
                selectedCameraSsid = ssid,
                selectedCardSlotSource = savedProfile?.playTargetSlot,
                sessionState = CameraSessionState.Idle,
                wifiState = nextWifiState,
                protocolError = null,
                refreshStatus = "Ready",
                remoteRuntime = RemoteRuntimeState(statusLabel = "Ready"),
                transferState = currentTransfer.copy(
                    images = emptyList(),
                    thumbnails = emptyMap(),
                    isLoading = false,
                    isDownloading = false,
                    downloadProgress = "",
                    errorMessage = null,
                    selectedImage = null,
                    selectedImages = emptySet(),
                    isSelectionMode = false,
                    matchedGeoTags = emptyMap(),
                    previewUnavailable = emptySet(),
                    sourceCameraSsid = null,
                ),
                lastCaptureThumbnail = null,
            ) }
            _liveViewFrame.value = null
        }
        preferencesRepository.selectSavedCamera(ssid)
        autoImportArmed = false
        updateUiState { it.copy(
            selectedCameraSsid = ssid,
            selectedCardSlotSource = savedProfile?.playTargetSlot,
        ) }
        refreshSavedCamerasState()
        if (reconnectImmediately) {
            startSavedCameraReconnect(
                ssidOverride = ssid,
                initialStatus = "Reconnecting to $ssid...",
            )
        }
    }

    private suspend fun powerOffSavedCamera(ssid: String) {
        if (!_uiState.value.selectedCameraSsid.equals(ssid, ignoreCase = true)) {
            updateUiState { it.copy(
                refreshStatus = "Select the target camera before powering it off",
                protocolError = "Camera power off is only available for the currently selected camera.",
            ) }
            return
        }

        val uiState = _uiState.value
        val cameraWifiReachable =
            uiState.wifiState is WifiConnectionState.CameraWifi &&
                uiState.wifiState.ssid?.equals(ssid, ignoreCase = true) == true
        val activeSession =
            cameraWifiReachable ||
                uiState.sessionState is CameraSessionState.Connected ||
                uiState.sessionState is CameraSessionState.LiveView ||
                uiState.sessionState is CameraSessionState.Transferring
        if (!activeSession) {
            _uiState.update { uiState.copy(
                refreshStatus = "Camera is not reachable",
                protocolError = "Camera power off is only available while connected to this camera.",
            ) }
            return
        }

        val powerOffCommand = uiState.workspace.protocol.commandList.commands.firstOrNull {
            it.supported && it.name.equals("exec_pwoff", ignoreCase = true)
        }
        if (powerOffCommand == null) {
            _uiState.update { uiState.copy(
                refreshStatus = "Camera power control unavailable",
                protocolError = "This camera does not advertise exec_pwoff support in its command list.",
            ) }
            return
        }

        val supportsWithBleMode = listOf(powerOffCommand.param1, powerOffCommand.param2)
            .any { it.equals("withble", ignoreCase = true) }
        val useWithBleMode =
            supportsWithBleMode &&
                uiState.workspace.camera.connectMode == ConnectMode.Play

        _uiState.update { uiState.copy(
            isRefreshing = true,
            refreshStatus = "Powering off camera...",
            protocolError = null,
        ) }

        repository.powerOffCamera(useWithBleMode = useWithBleMode)
            .onSuccess {
                disconnectCamera()
                updateUiState { it.copy(
                    refreshStatus = "Camera power off sent",
                    protocolError = null,
                ) }
            }
            .onFailure { error ->
                updateUiState { it.copy(
                    isRefreshing = false,
                    refreshStatus = "Camera power off failed",
                    protocolError = error.message ?: "Could not send camera power off command.",
                ) }
            }
    }

    fun applyProtocolPreview() {
        updateUiState { it.copy(
            isRefreshing = true,
            refreshStatus = "Rebuilding...",
            protocolError = null,
        ) }
        viewModelScope.launch {
            repository.refreshFromCommandList(_uiState.value.rawProtocolInput)
                .onSuccess { workspace ->
                    updateUiState { current -> current.copy(
                        workspace = workspace,
                        protocolError = null,
                        isRefreshing = false,
                        refreshStatus = workspace.generatedAtLabel,
                        sessionState = sessionStateMachine.reduce(
                            current.sessionState,
                            CameraSessionEvent.Connected(
                                method = workspace.camera.connectionMethod,
                                mode = workspace.camera.connectMode,
                            ),
                        ),
                    ) }
                }
                .onFailure { throwable ->
                    updateUiState { current -> current.copy(
                        protocolError = throwable.message ?: "Parse failed",
                        isRefreshing = false,
                        refreshStatus = "Rebuild failed",
                        sessionState = sessionStateMachine.reduce(
                            current.sessionState,
                            CameraSessionEvent.Fail(throwable.message ?: "Parse failed"),
                        ),
                    ) }
                }
        }
    }

    fun resetProtocolSample() {
        updateUiState { it.copy(
            rawProtocolInput = repository.sampleCommandListXml(),
            protocolError = null,
            refreshStatus = "Sample restored",
        ) }
    }

    fun updateSetting(settingId: String, enabled: Boolean) {
        D.pref("updateSetting: $settingId=$enabled")
        val updatedSettings = _uiState.value.settings.map { setting ->
            if (setting.id == settingId) setting.copy(enabled = enabled) else setting
        }
        updateUiState { it.copy(
            settings = updatedSettings,
            showProtocolWorkbench = resolveWorkbenchVisibility(updatedSettings),
        ) }
        viewModelScope.launch {
            preferencesRepository.saveSetting(settingId, enabled)
        }
        if (settingId == "time_match_geotags") {
            refreshMatchedGeoTags()
        }
        // Keep geotag config in sync with the include_altitude toggle
        if (settingId == "geotag_include_altitude") {
            val updatedConfig = _uiState.value.geotagConfig.copy(writeAltitude = enabled)
            updateUiState { it.copy(geotagConfig = updatedConfig) }
            viewModelScope.launch {
                preferencesRepository.saveStringPref("geotag_write_altitude", enabled.toString())
            }
        }
    }

    fun updateAutoImportConfig(config: AutoImportConfig) {
        updateUiState { it.copy(autoImportConfig = config) }
        viewModelScope.launch {
            preferencesRepository.saveStringPref("import_save_location", config.saveLocation)
            preferencesRepository.saveStringPref("import_file_format", config.fileFormat)
            preferencesRepository.saveStringPref("import_timing", config.importTiming)
        }
        if (config.importTiming == "since_launch") {
            val selectedSsid = _uiState.value.selectedCameraSsid
            if (!selectedSsid.isNullOrBlank()) {
                sessionLaunchImportBaselineBySsid.remove(selectedSsid)
            }
        }
    }

    fun onRemoteScreenVisible() {
        val state = _uiState.value
        val surface = state.remoteRuntime.modePickerSurface
        if (!isUsbModeSurface(surface)) {
            return
        }
        if (surface == ModePickerSurface.TetherRetry) {
            if (!state.omCaptureUsb.isBusy) {
                refreshOmCaptureUsb()
            }
            return
        }
        prepareOmCaptureUsbForControlMode()
        setUsbLiveViewDesired(true, "remote-visible")
        if (omCaptureUsbManager.isLiveViewActive) {
            if (!state.remoteRuntime.liveViewActive) {
                refreshOmCaptureStudioState(
                    state.copy(
                        remoteRuntime = state.remoteRuntime.copy(
                            liveViewActive = true,
                            activePicker = ActivePropertyPicker.None,
                            statusLabel = "USB live view",
                        ),
                    ),
                )
            }
            return
        }
        if (state.omCaptureUsb.isBusy) {
            scheduleUsbLiveViewRecovery(
                reason = "remote-visible",
                delayMs = 150L,
                waitingStatus = "Resuming USB live view...",
            )
            return
        }
        if (state.omCaptureUsb.summary?.supportsLiveView == true) {
            startUsbLiveViewSession(waitingStatus = "Waiting for USB live view frame...")
        } else {
            refreshOmCaptureUsb()
        }
    }

    fun updateLibraryCompatibilityMode(mode: LibraryCompatibilityMode) {
        if (_uiState.value.libraryCompatibilityMode == mode) return
        wifiMediaRequestCooldownUntilMs = 0L
        updateUiState { it.copy(libraryCompatibilityMode = mode) }
        viewModelScope.launch {
            preferencesRepository.saveStringPref("library_compatibility_mode", mode.preferenceValue)
        }
        val transfer = _uiState.value.transferState
        if (transfer.sourceKind == TransferSourceKind.WifiCamera && transfer.images.isNotEmpty()) {
            thumbnailJob?.cancel()
            loadThumbnails(transfer.images)
        }
    }

    fun updateUsbTetheringMode(mode: UsbTetheringMode) {
        if (_uiState.value.usbTetheringMode == mode) return
        omCaptureUsbManager.setUsbTetheringProfile(mode.toUsbTetheringProfile())
        updateUiState { it.copy(usbTetheringMode = mode) }
        viewModelScope.launch {
            preferencesRepository.saveStringPref("usb_tethering_mode", mode.preferenceValue)
        }
    }

    fun updateSelectedCardSlotSource(slot: Int) {
        if (slot !in 1..2) return
        val selectedSsid = _uiState.value.selectedCameraSsid ?: return
        val currentTransfer = _uiState.value.transferState
        val switchingWifiLibrary = currentTransfer.sourceKind == TransferSourceKind.WifiCamera
        updateUiState { it.copy(
            selectedCardSlotSource = slot,
            transferState = currentTransfer.copy(
                images = emptyList(),
                thumbnails = emptyMap(),
                selectedImage = null,
                selectedImages = emptySet(),
                isSelectionMode = false,
                matchedGeoTags = emptyMap(),
                previewUnavailable = emptySet(),
                isLoading = false,
                errorMessage = null,
                sourceCameraSsid = null,
                sourceLabel = if (switchingWifiLibrary) {
                    wifiTransferSourceLabel(slot, selectedSsid)
                } else {
                    currentTransfer.sourceLabel
                },
            ),
        ) }
        resetWifiThumbnailFallbackState()
        activePlayTargetSlot = null
        viewModelScope.launch {
            preferencesRepository.updateCameraPlayTargetSlot(selectedSsid, slot)
            if (canSendRemoteCommand()) {
                ensureSelectedPlayTargetSlotApplied()
            }
            refreshSavedCamerasState()
        }
    }

    fun selectWifiLibrarySourceSlot(slot: Int) {
        updateSelectedCardSlotSource(slot)
        loadCameraImages()
    }

    fun updateGeotagConfig(config: GeotagConfig) {
        updateUiState { it.copy(geotagConfig = config) }
        viewModelScope.launch {
            preferencesRepository.saveStringPref("geotag_match_window", config.matchWindowMinutes.toString())
            preferencesRepository.saveStringPref("geotag_match_method", config.matchMethod)
            preferencesRepository.saveStringPref("geotag_location_source", config.locationSource)
            preferencesRepository.saveStringPref("geotag_write_altitude", config.writeAltitude.toString())
        }
        if (config.matchWindowMinutes != _uiState.value.geotagConfig.matchWindowMinutes) {
            refreshMatchedGeoTags()
        }
    }

    fun updateTetherSaveTarget(target: TetherSaveTarget) {
        recordQaUiAction(title = "Change tether save target", detail = target.preferenceValue)
        refreshOmCaptureStudioState(_uiState.value.copy(tetherSaveTarget = target))
        if (!target.savesToPhone) {
            lastAutoImportedUsbHandle = null
            pendingUsbAutoImportHandles.clear()
            completedUsbAutoImportHandles.clear()
        }
        viewModelScope.launch {
            preferencesRepository.saveStringPref("tether_save_target", target.preferenceValue)
        }
    }

    fun updateTetherPhoneImportFormat(format: TetherPhoneImportFormat) {
        recordQaUiAction(title = "Change phone import format", detail = format.preferenceValue)
        refreshOmCaptureStudioState(_uiState.value.copy(tetherPhoneImportFormat = format))
        viewModelScope.launch {
            preferencesRepository.saveStringPref("tether_phone_import_format", format.preferenceValue)
        }
    }

    fun updateCaptureReviewDurationSeconds(seconds: Int) {
        val normalized = normalizeCaptureReviewDurationSeconds(seconds)
        if (_uiState.value.captureReviewDurationSeconds == normalized) return
        updateUiState { it.copy(captureReviewDurationSeconds = normalized) }
        viewModelScope.launch {
            preferencesRepository.saveStringPref("capture_review_duration_seconds", normalized.toString())
        }
    }

    fun checkForAppUpdate(silent: Boolean = false) {
        val current = _uiState.value.appUpdate
        if (current.checking || current.downloading) return
        updateUiState { state ->
            state.copy(
                appUpdate = state.appUpdate.copy(
                    checking = true,
                    statusLabel = if (silent) state.appUpdate.statusLabel else appString(R.string.settings_update_checking),
                ),
            )
        }
        viewModelScope.launch {
            runCatching { appUpdateManager.fetchLatestRelease() }
                .onSuccess { release ->
                    latestAppRelease = release
                    downloadedUpdateApk = downloadedUpdateApk?.takeIf {
                        it.exists() && it.name.equals(release.assetFileName, ignoreCase = true)
                    }
                    val updateAvailable = compareVersionNames(release.versionName, normalizedAppVersionName) > 0
                    updateUiState { state ->
                        state.copy(
                            appUpdate = state.appUpdate.copy(
                                checking = false,
                                downloading = false,
                                updateAvailable = updateAvailable,
                                latestVersion = release.versionName,
                                statusLabel = when {
                                    updateAvailable && downloadedUpdateApk != null ->
                                        appString(R.string.settings_update_downloaded, release.versionName)
                                    updateAvailable ->
                                        appString(R.string.settings_update_available, release.versionName)
                                    silent -> ""
                                    else -> appString(R.string.settings_update_current)
                                },
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    D.http("GitHub update check failed: ${error.message}")
                    if (!silent) {
                        updateUiState { state ->
                            state.copy(
                                appUpdate = state.appUpdate.copy(
                                    checking = false,
                                    downloading = false,
                                    statusLabel = appString(R.string.settings_update_failed),
                                ),
                            )
                        }
                    } else {
                        updateUiState { state ->
                            state.copy(appUpdate = state.appUpdate.copy(checking = false))
                        }
                    }
                }
        }
    }

    fun installLatestAppUpdate() {
        val current = _uiState.value.appUpdate
        if (current.checking || current.downloading) return
        val release = latestAppRelease
        if (release == null) {
            checkForAppUpdate(silent = false)
            return
        }
        if (!appUpdateManager.canRequestPackageInstalls()) {
            updateUiState { state ->
                state.copy(
                    appUpdate = state.appUpdate.copy(
                        statusLabel = appString(R.string.settings_update_install_permission),
                    ),
                )
            }
            val permissionIntent = appUpdateManager.buildUnknownSourcesIntent().apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { getApplication<Application>().startActivity(permissionIntent) }
            return
        }
        viewModelScope.launch {
            updateUiState { state ->
                state.copy(
                    appUpdate = state.appUpdate.copy(
                        downloading = downloadedUpdateApk?.exists() != true,
                        statusLabel = if (downloadedUpdateApk?.exists() == true) {
                            appString(R.string.settings_update_downloaded, release.versionName)
                        } else {
                            appString(R.string.settings_update_downloading)
                        },
                    ),
                )
            }
            runCatching {
                val apkFile = downloadedUpdateApk?.takeIf { it.exists() }
                    ?: appUpdateManager.downloadReleaseApk(release).also { downloadedUpdateApk = it }
                updateUiState { state ->
                    state.copy(
                        appUpdate = state.appUpdate.copy(
                            downloading = false,
                            updateAvailable = true,
                            latestVersion = release.versionName,
                            statusLabel = appString(R.string.settings_update_downloaded, release.versionName),
                        ),
                    )
                }
                val installIntent = appUpdateManager.buildInstallIntent(apkFile).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                getApplication<Application>().startActivity(installIntent)
            }.onFailure { error ->
                D.http("GitHub update install failed: ${error.message}")
                updateUiState { state ->
                    state.copy(
                        appUpdate = state.appUpdate.copy(
                            downloading = false,
                            statusLabel = appString(R.string.settings_update_failed),
                        ),
                    )
                }
            }
        }
    }

    private fun compareVersionNames(left: String, right: String): Int {
        val leftParts = left.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '_')
            .mapNotNull { part -> part.toIntOrNull() }
        val rightParts = right.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '_')
            .mapNotNull { part -> part.toIntOrNull() }
        val maxSize = maxOf(leftParts.size, rightParts.size)
        repeat(maxSize) { index ->
            val leftValue = leftParts.getOrElse(index) { 0 }
            val rightValue = rightParts.getOrElse(index) { 0 }
            if (leftValue != rightValue) {
                return leftValue.compareTo(rightValue)
            }
        }
        return 0
    }

    private fun appString(
        @StringRes resId: Int,
        vararg args: Any,
    ): String = getApplication<Application>().getString(resId, *args)

    private fun startSessionKeepAlive() {
        sessionKeepAliveSuspendedForTransfer = false
        sessionKeepAliveJob?.cancel()
        sessionKeepAliveJob = viewModelScope.launch {
            // Refresh the session timeout on a stable 150-second cadence.
            // (field c=600, delay = (c/4)*1000 = 150000ms), timeout value = 1800s.
            while (true) {
                delay(150_000L) // 150 seconds
                if (shouldRunSessionKeepAlive()) {
                    D.session("Sending session keepalive (set_timeout 1800s)")
                    runCatching { repository.setSessionTimeout(1800) }
                        .onFailure { D.err("SESSION", "Keepalive failed, will retry next cycle", it) }
                } else {
                    D.session("Session no longer active, stopping keepalive")
                    break
                }
            }
        }
    }

    private fun stopSessionKeepAlive() {
        sessionKeepAliveJob?.cancel()
        sessionKeepAliveJob = null
    }

    private fun shouldRunSessionKeepAlive(): Boolean {
        if (sessionKeepAliveSuspendedForTransfer) {
            return false
        }
        if (isUsbRemoteTransportActive()) {
            return false
        }
        return _uiState.value.sessionState is CameraSessionState.Connected ||
            _uiState.value.remoteRuntime.liveViewActive
    }

    private fun pauseSessionKeepAliveForWifiTransfer() {
        if (_uiState.value.transferState.sourceKind != TransferSourceKind.WifiCamera) {
            return
        }
        if (sessionKeepAliveSuspendedForTransfer) {
            return
        }
        sessionKeepAliveSuspendedForTransfer = true
        D.session("Pausing session keepalive for Wi-Fi background transfer")
        stopSessionKeepAlive()
    }

    private fun resumeSessionKeepAliveAfterWifiTransfer() {
        if (!sessionKeepAliveSuspendedForTransfer) {
            return
        }
        sessionKeepAliveSuspendedForTransfer = false
        if (_uiState.value.transferState.sourceKind == TransferSourceKind.WifiCamera &&
            !isUsbRemoteTransportActive() &&
            (_uiState.value.sessionState is CameraSessionState.Connected ||
                _uiState.value.remoteRuntime.liveViewActive)
        ) {
            D.session("Resuming session keepalive after Wi-Fi background transfer")
            startSessionKeepAlive()
        }
    }

    fun setAppLanguage(languageTag: String) {
        if (languageTag != AppLanguageManager.LANGUAGE_SYSTEM &&
            languageTag != AppLanguageManager.LANGUAGE_ENGLISH &&
            languageTag != AppLanguageManager.LANGUAGE_KOREAN
        ) {
            return
        }
        AppLanguageManager.persistLanguage(getApplication(), languageTag)
        updateUiState { it.copy(selectedLanguageTag = languageTag) }
    }

    private fun canSendRemoteCommand(): Boolean {
        if (isUsbRemoteTransportActive()) {
            val usbConnected = _uiState.value.omCaptureUsb.summary != null
            if (!usbConnected) {
                D.ui("canSendRemoteCommand=false: usbConnected=false")
            }
            return usbConnected
        }
        val reconnectActive = reconnectJob?.isActive == true
        val handshakeActive = handshakeJob?.isActive == true
        val hasNetwork = cameraWifiManager.getCameraNetwork() != null
        val sessionConnected = _uiState.value.sessionState is CameraSessionState.Connected ||
            _uiState.value.sessionState is CameraSessionState.LiveView
        val canSend = !reconnectActive && !handshakeActive && hasNetwork && sessionConnected
        if (!canSend) {
            D.ui(
                "canSendRemoteCommand=false: reconnect=$reconnectActive, handshake=$handshakeActive, " +
                    "sessionConnected=$sessionConnected, network=$hasNetwork",
            )
        }
        return canSend
    }

    private fun showRemoteStatus(message: String) {
        updateUiState { current -> current.copy(
            remoteRuntime = current.remoteRuntime.copy(statusLabel = message),
        ) }
    }

    private fun isRemoteSessionReady(): Boolean {
        if (isUsbRemoteTransportActive()) {
            return _uiState.value.omCaptureUsb.summary != null
        }
        val sessionConnected = _uiState.value.sessionState is CameraSessionState.Connected ||
            _uiState.value.sessionState is CameraSessionState.LiveView
        val onCameraWifi = _uiState.value.wifiState is WifiConnectionState.CameraWifi
        return sessionConnected && onCameraWifi
    }

    private fun moveRemoteToSafeIdle(statusLabel: String) {
        touchFocusOverlayJob?.cancel()
        updateUiState { current -> current.copy(
            remoteRuntime = current.remoteRuntime.copy(
                liveViewActive = false,
                focusHeld = false,
                isFocusing = false,
                touchFocusPoint = null,
                statusLabel = statusLabel,
            ),
        ) }
        _liveViewFrame.value = null
    }

    private fun updateLastCaptureThumbnail(bitmap: Bitmap?) {
        if (bitmap == null) return
        updateUiState { it.copy(lastCaptureThumbnail = bitmap) }
    }

    fun updateGeoTagPermissions(granted: Boolean, preciseGranted: Boolean) {
        updateUiState { current -> current.copy(
            geoTagging = current.geoTagging.copy(
                permissionGranted = granted,
                precisePermissionGranted = preciseGranted,
                statusLabel = when {
                    granted && preciseGranted -> "Precise location ready"
                    granted -> "Approximate location ready"
                    else -> "Location permission needed"
                },
            ),
        ) }
    }

    fun setExposurePreview(value: Float) {
        updateUiState { current -> current.copy(
            remoteRuntime = current.remoteRuntime.copy(
                exposureCompensation = value,
                statusLabel = "EV adjusted",
            ),
        ) }
    }

    fun setExposureCompensationValue(value: String) {
        val normalizedDisplayValue = PropertyFormatter.exposureCompensationDisplayValue(value).ifBlank { value }
        recordQaUiAction(title = "Set exposure compensation", detail = normalizedDisplayValue)
        D.cmd("SET_EV REQUEST: display=$normalizedDisplayValue")
        if (!canSendRemoteCommand()) {
            showRemoteStatus("Reconnect to camera before changing EV")
            return
        }
        if (isUsbRemoteTransportActive()) {
            val runtime = _uiState.value.remoteRuntime
            val previewEv = PropertyFormatter.exposureCompensationNumericValue(normalizedDisplayValue)
                ?.toFloat()
                ?: runtime.exposureCompensation
            updateUiState { it.copy(
                remoteRuntime = runtime.copy(
                    exposureCompensation = previewEv,
                    exposureCompensationValues = runtime.exposureCompensationValues.copy(currentValue = normalizedDisplayValue),
                    statusLabel = "Applying EV...",
                ),
            ) }
            propertyApplyJob?.cancel()
            propertyApplyJob = viewModelScope.launch {
                applyUsbPropertyValue(
                    propCode = PtpConstants.OlympusProp.ExposureCompensation,
                    propName = "expcomp",
                    selectedValue = normalizedDisplayValue,
                    closePicker = false,
                )
            }
            return
        }
        val runtime = _uiState.value.remoteRuntime
        val requestedValue = resolveRequestedExposureCompValue(
            selectedValue = normalizedDisplayValue,
            currentValues = runtime.exposureCompensationValues.availableValues,
            currentValue = runtime.exposureCompensationValues.currentValue,
        )
        D.cmd("SET_EV REQUEST: display=$normalizedDisplayValue raw=$requestedValue")
        pendingPropertyTargets["expcomp"] = requestedValue
        pendingPropertyTimestamps["expcomp"] = System.currentTimeMillis()
        propertyRefreshJob?.cancel()
        val confirmedValue = runtime.exposureCompensationValues.currentValue
        val numericValue = PropertyFormatter.exposureCompensationNumericValue(requestedValue)
            ?.toFloat()
            ?: runtime.exposureCompensation
        updateUiState { it.copy(
            remoteRuntime = runtime.copy(
                exposureCompensation = numericValue,
                exposureCompensationValues = runtime.exposureCompensationValues.copy(currentValue = requestedValue),
                statusLabel = "Applying EV...",
            ),
        ) }

        propertyApplyJob?.cancel()
        propertyApplyJob = viewModelScope.launch {
            delay(120)
            if (propertyValueMatches("expcomp", confirmedValue, requestedValue)) {
                clearPendingProperty("expcomp")
                schedulePropertyRefresh(delayMillis = 60L)
                return@launch
            }
            repository.setProperty("expcomp", requestedValue)
                .onSuccess {
                    if (waitForPropertyTarget("expcomp", requestedValue) == null) {
                        clearPendingProperty("expcomp")
                    }
                    schedulePropertyRefresh(delayMillis = 60L)
                }
                .onFailure { error ->
                    clearPendingProperty("expcomp")
                    D.err("PROTO", "Failed to set expcomp=$value", error)
                    updateUiState { current -> current.copy(
                        remoteRuntime = current.remoteRuntime.copy(
                            statusLabel = "EV change failed: ${error.message}",
                        ),
                    ) }
                }
        }
    }

    // Live View
    fun toggleLiveView() {
        val starting = !_uiState.value.remoteRuntime.liveViewActive
        recordQaUiAction(
            title = if (starting) "Start live view" else "Stop live view",
            detail = "usbSurface=${isUsbModeSurface(_uiState.value.remoteRuntime.modePickerSurface)}",
        )
        val prefersUsbTether = isUsbModeSurface(_uiState.value.remoteRuntime.modePickerSurface)
        if (prefersUsbTether) {
            toggleUsbLiveView()
            return
        }
        if (starting && !isRemoteSessionReady()) {
            moveRemoteToSafeIdle("Connect to camera before starting live view")
            return
        }
        if (isTogglingLiveView) {
            D.ui("toggleLiveView: ignored (already toggling)")
            return
        }
        isTogglingLiveView = true
        D.ui("toggleLiveView: starting=$starting, port=${appConfig.liveViewPort}")
        updateUiState { current -> current.copy(
            remoteRuntime = current.remoteRuntime.copy(
                statusLabel = if (starting) "Starting live view..." else "Stopping...",
            ),
        ) }
        viewModelScope.launch {
            try {
                if (starting) {
                    startLiveViewInternal()
                } else {
                    stopLiveViewInternal()
                    repository.stopLiveView()
                    _liveViewFrame.value = null
                    updateUiState { current -> current.copy(
                        remoteRuntime = current.remoteRuntime.copy(
                            liveViewActive = false,
                            focusHeld = false,
                            isFocusing = false,
                            touchFocusPoint = null,
                            statusLabel = "Ready",
                        ),
                        sessionState = sessionStateMachine.reduce(
                            current.sessionState,
                            CameraSessionEvent.Connected(
                                method = current.workspace.camera.connectionMethod,
                                mode = current.workspace.camera.connectMode,
                            ),
                        ),
                    ) }
                }
            } finally {
                isTogglingLiveView = false
            }
        }
    }

    private suspend fun startLiveViewInternal() {
        thumbnailJob?.cancel()
        thumbnailJob = null
        stopLiveViewInternal()
        if (!cameraWifiManager.isWifiEnabled()) {
            updateUiState { current -> current.copy(
                remoteRuntime = current.remoteRuntime.copy(
                    statusLabel = "Turn on WiFi to start live view",
                ),
                protocolError = "Turn on WiFi to reconnect to your camera.",
            ) }
            return
        }

        val selectedPassword = _uiState.value.savedCameras.firstOrNull { it.ssid == _uiState.value.selectedCameraSsid }?.password
            ?: preferencesRepository.loadCameraCredentials(_uiState.value.selectedCameraSsid)?.password
        val cameraNetwork = resolveLiveViewCameraNetwork(
            ssid = _uiState.value.selectedCameraSsid,
            password = selectedPassword,
        )
        if (cameraNetwork == null) {
            updateUiState { current -> current.copy(
                remoteRuntime = current.remoteRuntime.copy(
                    statusLabel = "Reconnect to camera WiFi first",
                ),
            ) }
            return
        }
        liveViewMetadataJob?.cancel()
        liveViewMetadataJob = liveViewReceiver.metadata
            .onEach { metadata ->
                latestLiveViewMetadata = metadata
                metadata?.let(::applyLiveViewApertureMetadata)
            }
            .launchIn(viewModelScope)
        liveViewFrameJob = liveViewReceiver.frames
            .onEach { bitmap ->
                _liveViewFrame.value = bitmap
            }
            .launchIn(viewModelScope)
        val connectivityManager = getApplication<Application>()
            .getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        D.stream("Starting LiveViewReceiver with cameraNetwork=$cameraNetwork")
        val receiverReady = CompletableDeferred<Unit>()
        liveViewReceiverJob = viewModelScope.launch {
            liveViewReceiver.start(cameraNetwork, connectivityManager) {
                if (!receiverReady.isCompleted) {
                    receiverReady.complete(Unit)
                }
            }
        }
        val receiverReadyInTime = withTimeoutOrNull(1500L) {
            receiverReady.await()
            true
        } ?: false
        if (!receiverReadyInTime || !liveViewReceiver.isSocketReady) {
            D.stream("LiveViewReceiver did not become ready before startliveview")
            stopLiveViewInternal()
            updateUiState { current -> current.copy(
                remoteRuntime = current.remoteRuntime.copy(
                    statusLabel = "Live view receiver not ready",
                ),
            ) }
            return
        }

        val result = repository.startLiveView(appConfig.liveViewPort)
        result.onSuccess { message ->
            D.stream("Live view started: $message")
            updateUiState { current -> current.copy(
                remoteRuntime = current.remoteRuntime.copy(
                    liveViewActive = true,
                    focusHeld = false,
                    statusLabel = "Live view active",
                ),
                sessionState = sessionStateMachine.reduce(
                    current.sessionState,
                    CameraSessionEvent.StartLiveView(appConfig.liveViewPort),
                ),
            ) }
            if (!_uiState.value.remoteRuntime.propertiesLoaded) {
                schedulePropertyRefresh(delayMillis = 180L)
            }
            liveViewRetryCount = 0
            liveViewHealthJob?.cancel()
            liveViewHealthJob = viewModelScope.launch {
                liveViewReceiver.health.collect { health ->
                    val currentState = _uiState.value
                    val canRetry = health == StreamHealth.Lost &&
                        currentState.remoteRuntime.liveViewActive &&
                        currentState.wifiState is WifiConnectionState.CameraWifi &&
                        currentState.sessionState !is CameraSessionState.Idle &&
                        liveViewRetryCount < 3
                    if (canRetry) {
                        liveViewRetryCount++
                        D.stream("Auto-reconnecting live view (attempt $liveViewRetryCount/3)")
                        stopLiveViewInternal()
                        delay(500)
                        // Re-check after delay — WiFi may have dropped during the wait
                        if (_uiState.value.wifiState is WifiConnectionState.CameraWifi &&
                            _uiState.value.sessionState !is CameraSessionState.Idle
                        ) {
                            startLiveViewInternal()
                        } else {
                            D.stream("Aborting live view retry — WiFi or session lost during wait")
                        }
                    }
                }
            }
        }.onFailure { throwable ->
            D.err("STREAM", "Failed to start live view", throwable)
            stopLiveViewInternal()
            updateUiState { current -> current.copy(
                remoteRuntime = current.remoteRuntime.copy(
                    statusLabel = throwable.message ?: "Failed to start live view",
                ),
            ) }
        }
    }

    private fun stopLiveViewInternal() {
        liveViewReceiver.stop()
        liveViewReceiverJob?.cancel()
        liveViewReceiverJob = null
        liveViewFrameJob?.cancel()
        liveViewFrameJob = null
        liveViewHealthJob?.cancel()
        liveViewHealthJob = null
        liveViewMetadataJob?.cancel()
        liveViewMetadataJob = null
        latestLiveViewMetadata = null
        // Also stop USB live view if active
        if (omCaptureUsbManager.isLiveViewActive) {
            omCaptureUsbManager.stopUsbLiveView()
        }
        usbLiveViewFrameJob?.cancel()
        usbLiveViewFrameJob = null
    }

    // Remote Capture
    fun captureRemotePhoto() {
        recordQaUiAction(
            title = "Capture photo",
            detail = "usb=${isUsbRemoteTransportActive()} liveView=${_uiState.value.remoteRuntime.liveViewActive}",
        )
        if (isCapturing) {
            D.ui("captureRemotePhoto: ignored (already capturing)")
            return
        }
        if (!canSendRemoteCommand()) {
            showRemoteStatus("Reconnect to camera before capturing")
            return
        }
        isCapturing = true
        val liveViewActive = _uiState.value.remoteRuntime.liveViewActive
        if (isUsbRemoteTransportActive()) {
            D.ui("captureRemotePhoto[USB]: liveView=$liveViewActive")
            prepareOmCaptureUsbForControlMode()
            usbCaptureSettleUntilMs = maxOf(
                usbCaptureSettleUntilMs,
                SystemClock.elapsedRealtime() + USB_CAPTURE_PROPERTY_SETTLE_MS,
            )
            val previewFrame = _liveViewFrame.value ?: _uiState.value.lastCaptureThumbnail
            updateUiState { current -> current.copy(
                remoteRuntime = current.remoteRuntime.copy(
                    statusLabel = "Capturing...",
                    isBusy = true,
                ),
                lastCaptureThumbnail = previewFrame ?: current.lastCaptureThumbnail,
            ) }
            viewModelScope.launch {
                try {
                    performRemoteCaptureCommand(liveViewActive)
                        .onSuccess {
                            val completedRuntime = _uiState.value.remoteRuntime
                            updateUiState { it.copy(
                                remoteRuntime = completedRuntime.copy(
                                    captureCount = completedRuntime.captureCount + 1,
                                    statusLabel = if (completedRuntime.liveViewActive) {
                                        "USB live view"
                                    } else {
                                        "Captured"
                                    },
                                    isBusy = false,
                                ),
                                lastCaptureThumbnail = it.lastCaptureThumbnail ?: previewFrame,
                            ) }
                        }
                        .onFailure { throwable ->
                            D.err("USB", "USB remote capture failed", throwable)
                            updateUiState { current -> current.copy(
                                remoteRuntime = current.remoteRuntime.copy(
                                    statusLabel = throwable.message ?: "Capture failed",
                                    isBusy = false,
                                ),
                            ) }
                            if (liveViewActive) {
                                scheduleUsbLiveViewRecovery(
                                    reason = "capture-failed",
                                    waitingStatus = "Reconnecting USB live view...",
                                )
                            }
                        }
                } finally {
                    isCapturing = false
                }
            }
            return
        }
        val previousTopMarker = _uiState.value.transferState.images.firstOrNull()?.let(::autoImportSortKey)
        D.ui("captureRemotePhoto: count=${_uiState.value.remoteRuntime.captureCount}, liveView=$liveViewActive")
        thumbnailJob?.cancel()
        thumbnailJob = null
        updateUiState { current -> current.copy(
            remoteRuntime = current.remoteRuntime.copy(
                statusLabel = "Capturing...",
                isBusy = true,
            ),
        ) }
        viewModelScope.launch {
            try {
                D.cmd("CAPTURE REQUEST: liveView=$liveViewActive")
                D.timeStart("capture")
                val result = if (liveViewActive) {
                    repository.captureWhileLiveView()
                } else {
                    repository.triggerCapture()
                }
                result
                    .onSuccess { message ->
                        D.timeEnd("CMD", "capture", "CAPTURE SUCCESS")
                        val previewFrame = _liveViewFrame.value ?: _uiState.value.lastCaptureThumbnail
                        val followupRequested = isCaptureReviewAfterShotEnabled() || shouldSaveCapturedImageToPhone()
                        updateUiState { current -> current.copy(
                            remoteRuntime = current.remoteRuntime.copy(
                                captureCount = current.remoteRuntime.captureCount + 1,
                                statusLabel = if (followupRequested) {
                                    "Captured. Syncing latest shot..."
                                } else {
                                    "Captured"
                                },
                                isBusy = followupRequested,
                            ),
                            lastCaptureThumbnail = previewFrame,
                        ) }
                        if (followupRequested) {
                            val followupStatus = runCatching {
                                runLatestCaptureFollowup(previousTopMarker)
                            }.getOrElse {
                                D.err("TRANSFER", "Latest-shot follow-up failed", it)
                                "Captured, but latest-shot sync failed"
                            }
                            updateUiState { current -> current.copy(
                                remoteRuntime = current.remoteRuntime.copy(
                                    statusLabel = followupStatus,
                                    isBusy = false,
                                ),
                            ) }
                        }
                    }
                    .onFailure { throwable ->
                        D.timeEnd("CMD", "capture", "CAPTURE FAIL: ${throwable.message}")
                        updateUiState { current -> current.copy(
                            remoteRuntime = current.remoteRuntime.copy(
                                statusLabel = throwable.message ?: "Capture failed",
                                isBusy = false,
                            ),
                        ) }
                    }
            } finally {
                isCapturing = false
            }
        }
    }

    // Shooting Mode
    private fun isCaptureReviewAfterShotEnabled(): Boolean {
        return _uiState.value.settings.firstOrNull { it.id == "capture_review_after_shot" }?.enabled == true
    }

    private fun shouldSaveCapturedImageToPhone(): Boolean {
        return _uiState.value.tetherSaveTarget.savesToPhone
    }

    private fun normalizeCaptureReviewDurationSeconds(seconds: Int): Int {
        return seconds.coerceIn(
            MIN_CAPTURE_REVIEW_DURATION_SECONDS,
            MAX_CAPTURE_REVIEW_DURATION_SECONDS,
        )
    }

    private suspend fun runLatestCaptureFollowup(previousTopMarker: String?): String {
        val shouldReview = isCaptureReviewAfterShotEnabled()
        val shouldSave = shouldSaveCapturedImageToPhone()
        if (!shouldReview && !shouldSave) {
            return "Captured"
        }

        updateUiState { current -> current.copy(
            transferState = current.transferState.copy(
                isLoading = true,
                errorMessage = null,
                downloadProgress = "",
            ),
        ) }

        val latestImages = waitForLatestCapturedImages(previousTopMarker)
        if (latestImages.isNullOrEmpty()) {
            updateUiState { current -> current.copy(
                transferState = current.transferState.copy(
                    isLoading = false,
                ),
            ) }
            return "Captured, but latest-shot sync timed out"
        }

        applyTransferImageList(
            images = latestImages,
            isLoading = false,
            errorMessage = null,
            sourceCameraSsid = _uiState.value.selectedCameraSsid,
            sourceKind = TransferSourceKind.WifiCamera,
            sourceLabel = wifiTransferSourceLabel(
                _uiState.value.selectedCardSlotSource,
                _uiState.value.selectedCameraSsid,
            ),
            supportsDelete = true,
            usbObjectHandlesByPath = emptyMap(),
        )
        if (shouldReview) {
            applyPendingLastCapturePreview(latestImages, force = true)
        }
        loadThumbnails(latestImages)
        scanDownloadedFiles()

        if (!shouldSave) {
            return if (shouldReview) {
                "Captured. Latest shot ready"
            } else {
                "Captured"
            }
        }

        return saveCapturedImageToDevice(latestImages.first()).getOrElse {
            D.err("TRANSFER", "Failed to save latest captured image", it)
            "Captured, but latest-shot save failed"
        }
    }

    private suspend fun waitForLatestCapturedImages(previousTopMarker: String?): List<CameraImage>? {
        repeat(CAPTURE_FOLLOWUP_MAX_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                delay(CAPTURE_FOLLOWUP_POLL_DELAY_MS)
            }
            if (ensureSelectedPlayTargetSlotApplied().isFailure) {
                return null
            }
            val imageResult = repository.getImageList("/DCIM")
            if (imageResult.isFailure) {
                D.err("TRANSFER", "Latest capture refresh failed", imageResult.exceptionOrNull())
                return@repeat
            }
            val newestFirst = imageResult.getOrThrow()
                .sortedByDescending { "${it.captureDateKey}_${it.fileName}" }
            val topMarker = newestFirst.firstOrNull()?.let(::autoImportSortKey)
            if (topMarker != null && (previousTopMarker.isNullOrBlank() || topMarker != previousTopMarker)) {
                return newestFirst
            }
        }
        return null
    }

    private suspend fun saveCapturedImageToDevice(image: CameraImage): Result<String> {
        if (_uiState.value.transferState.downloadedFileNames.contains(image.fileName)) {
            updateUiState { current -> current.copy(
                transferState = current.transferState.copy(
                    isDownloading = false,
                    downloadProgress = "Latest shot already saved",
                ),
            ) }
            return Result.success("Captured. Latest shot already saved")
        }

        updateUiState { current -> current.copy(
            transferState = current.transferState.copy(
                isDownloading = true,
                downloadProgress = "Saving latest shot...",
            ),
        ) }

        val saved = withContext(Dispatchers.IO) {
            runCatching {
                saveStreamToGallery(
                    fileName = image.fileName,
                    dateFolder = image.dateFolderName,
                ) { outputStream ->
                    repository.downloadFullImageTo(image, outputStream).getOrThrow()
                }
            }.getOrElse { throwable ->
                updateUiState { current -> current.copy(
                    transferState = current.transferState.copy(
                        isDownloading = false,
                        downloadProgress = throwable.message ?: "Latest shot download failed",
                    ),
                ) }
                return@withContext false
            }
        }
        updateUiState { current -> current.copy(
            transferState = current.transferState.copy(
                isDownloading = false,
                downloadProgress = if (saved) "Latest shot saved" else "Latest shot save failed",
                downloadedCount = current.transferState.downloadedCount + if (saved) 1 else 0,
            ),
        ) }
        if (!saved) {
            return Result.failure(IllegalStateException("Failed to save latest captured image"))
        }

        scanDownloadedFiles()
        return Result.success("Captured and saved")
    }

    fun setShootingMode(mode: RemoteShootingMode) {
        D.ui("setShootingMode: $mode")
        stopInterval()
        updateUiState { current -> current.copy(
            remoteRuntime = current.remoteRuntime.copy(shootingMode = mode),
        ) }
    }

    // Drive & Timer
    fun setDriveMode(mode: DriveMode) {
        recordQaUiAction(title = "Set drive mode", detail = mode.label)
        D.ui("setDriveMode: $mode")
        updateUiState { current -> current.copy(
            remoteRuntime = current.remoteRuntime.copy(
                driveMode = mode,
                statusLabel = "Changing drive mode...",
            ),
        ) }
        applyDriveModeSelection(mode, _uiState.value.remoteRuntime.timerMode)
    }

    fun setTimerMode(mode: TimerMode) {
        recordQaUiAction(title = "Set timer mode", detail = mode.label)
        D.ui("setTimerMode: $mode")
        updateUiState { current -> current.copy(
            remoteRuntime = current.remoteRuntime.copy(
                timerMode = mode,
                statusLabel = "Changing timer mode...",
            ),
        ) }
        applyDriveModeSelection(_uiState.value.remoteRuntime.driveMode, mode)
    }

    fun setTimerDelay(seconds: Int) {
        recordQaUiAction(title = "Set timer delay", detail = "${seconds}s")
        D.ui("setTimerDelay: $seconds")
        updateUiState { current -> current.copy(
            remoteRuntime = current.remoteRuntime.copy(timerDelay = seconds),
        ) }
        scheduleUsbExtraPropertyUpdate(
            propCode = USB_TIMER_DELAY_PROP,
            value = seconds.toLong(),
            statusLabel = "Timer delay update",
        )
    }

    // Camera Mode (P/A/S/M...)
    fun setCameraExposureMode(mode: CameraExposureMode) {
        recordQaUiAction(title = "Set exposure mode", detail = mode.label)
        val current = _uiState.value.remoteRuntime.exposureMode
        D.mode("MODE_CHANGE requested=$mode (current=$current)")
        if (!canSendRemoteCommand()) {
            showRemoteStatus("Reconnect to camera before switching mode")
            return
        }
        if (isUsbRemoteTransportActive()) {
            showRemoteStatus("Change camera mode on the body while tethered")
            return
        }
        propertyRefreshJob?.cancel()
        propertyApplyJob?.cancel()
        propertyApplyJob = viewModelScope.launch {
            if (!applyExposureModeChange(mode = mode, closePicker = true)) {
                D.mode("setCameraExposureMode: takemode change failed")
                return@launch
            }
            D.mode("MODE_CHANGE: complete, PICKER_STATE -> None, exposureMode=${_uiState.value.remoteRuntime.exposureMode}, " +
                "F=${_uiState.value.remoteRuntime.aperture.currentValue}, SS=${_uiState.value.remoteRuntime.shutterSpeed.currentValue}, " +
                "ISO=${_uiState.value.remoteRuntime.iso.currentValue}")
        }
    }

    fun setActivePicker(picker: ActivePropertyPicker) {
        recordQaUiAction(title = "Open picker", detail = picker.name)
        val runtime = _uiState.value.remoteRuntime
        val prev = runtime.activePicker
        val nextSurface = if (isUsbModeSurface(runtime.modePickerSurface)) {
            runtime.modePickerSurface
        } else if (
            picker == ActivePropertyPicker.ExposureMode &&
            runtime.activePicker == ActivePropertyPicker.ExposureMode
        ) {
            runtime.modePickerSurface
        } else {
            ModePickerSurface.CameraModes
        }
        D.ui(
            "PICKER_STATE: $prev -> $picker " +
                "(mode=${runtime.exposureMode}, surface=${runtime.modePickerSurface} -> $nextSurface)",
        )
        updateUiState { it.copy(
            remoteRuntime = runtime.copy(
                activePicker = picker,
                modePickerSurface = nextSurface,
            ),
        ) }
        if (
            isUsbModeSurface(nextSurface) &&
            picker == ActivePropertyPicker.DriveSettings &&
            _uiState.value.omCaptureUsb.summary != null
        ) {
            refreshUsbProperties(includeExtras = true)
        }
    }

    fun setModePickerSurface(surface: ModePickerSurface) {
        recordQaUiAction(title = "Change mode surface", detail = surface.name)
        val runtime = _uiState.value.remoteRuntime
        val previousSurface = runtime.modePickerSurface
        val enteringUsbSurface = !isUsbModeSurface(previousSurface) && isUsbModeSurface(surface)
        if (enteringUsbSurface) {
            suppressWifiAutoRefresh = true
        } else if (!isUsbModeSurface(surface)) {
            suppressWifiAutoRefresh = false
        }
        D.ui(
            "MODE_PICKER_SURFACE: ${runtime.modePickerSurface} -> $surface " +
                "(activePicker=${runtime.activePicker})",
        )
        if (surface == ModePickerSurface.TetherRetry) {
            usbLiveViewFrameJob?.cancel()
            usbLiveViewFrameJob = null
            _liveViewFrame.value = null
            if (omCaptureUsbManager.isLiveViewActive) {
                viewModelScope.launch {
                    omCaptureUsbManager.stopUsbLiveView()
                }
            }
        }
        updateUiState { it.copy(
            remoteRuntime = runtime.copy(
                activePicker = if (isUsbModeSurface(surface)) {
                    ActivePropertyPicker.None
                } else {
                    ActivePropertyPicker.ExposureMode
                },
                modePickerSurface = surface,
                liveViewActive = if (surface == ModePickerSurface.TetherRetry) {
                    false
                } else {
                    runtime.liveViewActive && !enteringUsbSurface
                },
                statusLabel = if (surface == ModePickerSurface.TetherRetry) {
                    "Reconnect USB tether..."
                } else if (enteringUsbSurface) {
                    "Preparing USB tether..."
                } else {
                    runtime.statusLabel
                },
            ),
        ) }
        if (previousSurface == ModePickerSurface.DeepSky && surface != ModePickerSurface.DeepSky) {
            stopDeepSkySession()
        }
        if (surface == ModePickerSurface.DeepSky) {
            startDeepSkySession()
        }
        if (isUsbModeSurface(surface)) {
            if (surface != ModePickerSurface.TetherRetry) {
                setUsbLiveViewDesired(true, "enter-usb-surface")
            }
            viewModelScope.launch {
                if (enteringUsbSurface) {
                    prepareWifiRemoteForUsbTether(previousLiveViewActive = runtime.liveViewActive)
                }
                if (_uiState.value.remoteRuntime.modePickerSurface != surface) {
                    return@launch
                }
                if (surface == ModePickerSurface.TetherRetry) {
                    refreshOmCaptureUsb()
                } else if (
                    shouldAutoConnectUsbTether() ||
                    (usbLiveViewDesired && _uiState.value.omCaptureUsb.summary == null)
                ) {
                    refreshOmCaptureUsb()
                } else if (_uiState.value.omCaptureUsb.summary != null) {
                    refreshUsbProperties(includeExtras = surface == ModePickerSurface.DeepSky)
                    if (
                        surface != ModePickerSurface.TetherRetry &&
                        usbLiveViewDesired &&
                        _uiState.value.omCaptureUsb.summary?.supportsLiveView == true &&
                        !omCaptureUsbManager.isLiveViewActive
                    ) {
                        startUsbLiveViewSession(waitingStatus = "Waiting for USB live view frame...")
                    }
                }
            }
        }
    }

    private suspend fun prepareWifiRemoteForUsbTether(previousLiveViewActive: Boolean) {
        val state = _uiState.value
        val hadWifiLiveView = previousLiveViewActive ||
            state.sessionState is CameraSessionState.LiveView ||
            (state.remoteRuntime.liveViewActive && !isUsbRemoteTransportActive())
        val hadWifiSession = state.sessionState is CameraSessionState.Connected ||
            state.sessionState is CameraSessionState.LiveView

        if (!hadWifiLiveView && !hadWifiSession) {
            cameraWifiManager.clearRequestedCameraConnection(clearBinding = true)
            return
        }

        D.ui(
            "Preparing Wi-Fi remote session for USB tether " +
                "(liveView=$hadWifiLiveView, session=${state.sessionState})",
        )
        recoverLiveViewAfterReconnect = false
        queuedRefreshAfterHandshake = false
        reconnectJob?.cancel()
        handshakeJob?.cancel()
        propertyApplyJob?.cancel()
        propertyLoadJob?.cancel()
        propertyRefreshJob?.cancel()
        stopSessionKeepAlive()
        _liveViewFrame.value = null

        if (hadWifiLiveView) {
            stopLiveViewInternal()
            val stopResult = withTimeoutOrNull(2_200L) { repository.stopLiveView() }
            if (stopResult == null) {
                D.proto("Timed out stopping Wi-Fi live view before USB tether; continuing with USB scan")
            } else {
                stopResult.onFailure { throwable ->
                    D.err("PROTO", "Failed to stop Wi-Fi live view before USB tether", throwable)
                }
            }
        } else if (hadWifiSession) {
            val parkResult = withTimeoutOrNull(2_200L) { repository.switchCameraMode(ConnectMode.Play) }
            if (parkResult == null) {
                D.proto("Timed out parking Wi-Fi camera session before USB tether; continuing with USB scan")
            } else {
                parkResult.onFailure { throwable ->
                    D.err("PROTO", "Failed to park Wi-Fi camera session before USB tether", throwable)
                }
            }
        }

        cameraWifiManager.clearRequestedCameraConnection(clearBinding = true)
        updateUiState { current -> current.copy(
            isRefreshing = false,
            refreshStatus = "USB tether mode",
            protocolError = null,
            sessionState = CameraSessionState.Idle,
            remoteRuntime = current.remoteRuntime.copy(
                liveViewActive = false,
                focusHeld = false,
                isFocusing = false,
                touchFocusPoint = null,
                propertiesLoaded = false,
                statusLabel = "Connect the camera over USB",
            ),
        ) }
        delay(650L)
    }

    fun startDeepSkySession() {
        deepSkyLiveStackCoordinator.startSession(_uiState.value.deepSkyState.manualPresetOverride)
    }

    fun stopDeepSkySession() {
        deepSkyLiveStackCoordinator.stopSession()
    }

    fun resetDeepSkySession() {
        deepSkyLiveStackCoordinator.resetSession()
    }

    fun selectDeepSkyPreset(presetId: DeepSkyPresetId?) {
        deepSkyLiveStackCoordinator.startSession(presetId)
    }

    fun setDeepSkyManualFocalLength(focalLengthMm: Float?) {
        deepSkyLiveStackCoordinator.setManualFocalLengthOverride(focalLengthMm)
    }

    fun setPropertyValue(
        picker: ActivePropertyPicker,
        value: String,
        closePicker: Boolean = false,
    ) {
        recordQaUiAction(
            title = "Set property value",
            detail = "${picker.name}=$value close=$closePicker",
        )
        val runtime = _uiState.value.remoteRuntime
        D.ui("setPropertyValue: picker=$picker, value=$value, closePicker=$closePicker, currentMode=${runtime.exposureMode}, " +
            "F=${runtime.aperture.currentValue}, SS=${runtime.shutterSpeed.currentValue}, " +
            "ISO=${runtime.iso.currentValue}, WB=${runtime.whiteBalance.currentValue}")
        if (!canSendRemoteCommand()) {
            showRemoteStatus("Reconnect to camera before changing settings")
            return
        }
        if (isUsbRemoteTransportActive()) {
            propertyApplyJob?.cancel()
            propertyApplyJob = viewModelScope.launch {
                applyUsbRemotePropertySelection(
                    picker = picker,
                    value = value,
                    closePicker = closePicker,
                )
            }
            return
        }
        propertyRefreshJob?.cancel()
        val propName = when (picker) {
            ActivePropertyPicker.Aperture -> "focalvalue"
            ActivePropertyPicker.ShutterSpeed -> "shutspeedvalue"
            ActivePropertyPicker.Iso -> "isospeedvalue"
            ActivePropertyPicker.WhiteBalance -> "wbvalue"
            else -> return
        }
        if (picker == ActivePropertyPicker.Iso || picker == ActivePropertyPicker.WhiteBalance) {
            val requestedValue = resolveRequestedPropertyValue(
                propName = propName,
                selectedValue = value,
                currentValues = if (picker == ActivePropertyPicker.Iso) {
                    runtime.iso.availableValues
                } else {
                    runtime.whiteBalance.availableValues
                },
            )
            propertyApplyJob?.cancel()
            propertyApplyJob = viewModelScope.launch {
                applyOriginalLiveViewPropertySelection(
                    picker = picker,
                    propName = propName,
                    selectedValue = value,
                    requestedValue = requestedValue,
                    closePicker = closePicker,
                )
            }
            return
        }

        val requiresModeTransition = picker == ActivePropertyPicker.Aperture ||
            picker == ActivePropertyPicker.ShutterSpeed
        if (requiresModeTransition) {
            val targetMode = targetExposureModeForSelection(runtime, picker, value)
            if (isSyntheticDialAutoValue(propName, value) || targetMode != runtime.exposureMode) {
                propertyApplyJob?.cancel()
                propertyApplyJob = viewModelScope.launch {
                    val transitioned = applyExposureModeChange(
                        mode = targetMode,
                        closePicker = false,
                    )
                    if (!transitioned) {
                        return@launch
                    }
                    if (isSyntheticDialAutoValue(propName, value)) {
                        if (closePicker) {
                            updateUiState { current -> current.copy(
                                remoteRuntime = current.remoteRuntime.copy(
                                    activePicker = ActivePropertyPicker.None,
                                    modePickerSurface = ModePickerSurface.CameraModes,
                                ),
                            ) }
                        }
                        return@launch
                    }
                    val refreshedRuntime = _uiState.value.remoteRuntime
                    val transitionedRequestedValue = resolveRequestedPropertyValue(
                        propName = propName,
                        selectedValue = value,
                        currentValues = if (picker == ActivePropertyPicker.Aperture) {
                            refreshedRuntime.aperture.availableValues
                        } else {
                            refreshedRuntime.shutterSpeed.availableValues
                        },
                    )
                    val transitionedConfirmedValue = if (picker == ActivePropertyPicker.Aperture) {
                        refreshedRuntime.aperture.currentValue
                    } else {
                        refreshedRuntime.shutterSpeed.currentValue
                    }
                    val now = System.currentTimeMillis()
                    if (picker == ActivePropertyPicker.Aperture) {
                        pendingPropertyTargets["focalvalue"] = transitionedRequestedValue
                        pendingPropertyTimestamps["focalvalue"] = now
                    } else {
                        pendingPropertyTargets["shutspeedvalue"] = transitionedRequestedValue
                        pendingPropertyTimestamps["shutspeedvalue"] = now
                    }
                    val optimisticRuntime = if (picker == ActivePropertyPicker.Aperture) {
                        refreshedRuntime.copy(
                            aperture = refreshedRuntime.aperture.copy(currentValue = transitionedRequestedValue),
                        )
                    } else {
                        refreshedRuntime.copy(
                            shutterSpeed = refreshedRuntime.shutterSpeed.copy(currentValue = transitionedRequestedValue),
                        )
                    }.copy(statusLabel = "Applying...")
                    updateUiState { it.copy(remoteRuntime = optimisticRuntime) }
                    applyDirectPropertySelection(
                        picker = picker,
                        requestedValue = transitionedRequestedValue,
                        confirmedValue = transitionedConfirmedValue,
                    )
                }
                return
            }
        }

        val confirmedValue = if (picker == ActivePropertyPicker.Aperture) {
            runtime.aperture.currentValue
        } else {
            runtime.shutterSpeed.currentValue
        }
        val requestedValue = resolveRequestedPropertyValue(
            propName = propName,
            selectedValue = value,
            currentValues = if (picker == ActivePropertyPicker.Aperture) {
                runtime.aperture.availableValues
            } else {
                runtime.shutterSpeed.availableValues
            },
        )
        val now = System.currentTimeMillis()
        if (picker == ActivePropertyPicker.Aperture) {
            pendingPropertyTargets["focalvalue"] = requestedValue
            pendingPropertyTimestamps["focalvalue"] = now
        } else {
            pendingPropertyTargets["shutspeedvalue"] = requestedValue
            pendingPropertyTimestamps["shutspeedvalue"] = now
        }
        val optimisticRuntime = if (picker == ActivePropertyPicker.Aperture) {
            runtime.copy(aperture = runtime.aperture.copy(currentValue = requestedValue))
        } else {
            runtime.copy(shutterSpeed = runtime.shutterSpeed.copy(currentValue = requestedValue))
        }.copy(statusLabel = "Applying...")

        updateUiState { it.copy(remoteRuntime = optimisticRuntime) }

        propertyApplyJob?.cancel()
        propertyApplyJob = viewModelScope.launch {
            applyDirectPropertySelection(picker, requestedValue, confirmedValue)
        }
    }

    fun loadCameraProperties() {
        val generation = propertyLoadGeneration.incrementAndGet()
        D.ui("loadCameraProperties generation=$generation")
        propertyLoadJob?.cancel()
        propertyLoadJob = viewModelScope.launch {
            loadCameraPropertiesInternal(generation)
        }
    }

    private suspend fun applyOriginalLiveViewPropertySelection(
        picker: ActivePropertyPicker,
        propName: String,
        selectedValue: String,
        requestedValue: String,
        closePicker: Boolean,
    ) {
        val runtime = _uiState.value.remoteRuntime
        val currentRawValue = when (picker) {
            ActivePropertyPicker.Iso -> runtime.iso.currentValue
            ActivePropertyPicker.WhiteBalance -> runtime.whiteBalance.currentValue
            else -> ""
        }
        val skipSend = when (picker) {
            ActivePropertyPicker.Iso -> {
                val currentIsoAuto = runtime.iso.autoActive || currentRawValue.equals("AUTO", ignoreCase = true)
                (!currentIsoAuto && requestedValue.equals(currentRawValue, ignoreCase = true)) ||
                    (currentIsoAuto && requestedValue.equals("AUTO", ignoreCase = true))
            }
            ActivePropertyPicker.WhiteBalance -> requestedValue.equals(currentRawValue, ignoreCase = true)
            else -> false
        }
        if (skipSend) {
            D.proto("AUTO_PATH[$propName] no-op: requested=$requestedValue current=$currentRawValue closePicker=$closePicker")
            if (closePicker) {
                updateRemoteRuntime { current ->
                    current.copy(
                        activePicker = ActivePropertyPicker.None,
                        modePickerSurface = ModePickerSurface.CameraModes,
                    )
                }
            }
            return
        }

        updateRemoteRuntime { current -> current.copy(statusLabel = "Applying...") }
        repository.setProperty(propName, requestedValue)
            .onSuccess {
                updateUiState { current ->
                    val currentRuntime = current.remoteRuntime
                    val updatedRuntime = when (picker) {
                        ActivePropertyPicker.Iso -> currentRuntime.copy(
                            iso = currentRuntime.iso.copy(
                                currentValue = requestedValue,
                                autoActive = requestedValue.equals("AUTO", ignoreCase = true),
                            ),
                        )
                        ActivePropertyPicker.WhiteBalance -> currentRuntime.copy(
                            whiteBalance = currentRuntime.whiteBalance.copy(currentValue = requestedValue),
                        )
                        else -> currentRuntime
                    }
                    current.copy(
                        remoteRuntime = updatedRuntime.copy(
                            activePicker = if (closePicker) ActivePropertyPicker.None else updatedRuntime.activePicker,
                            modePickerSurface = if (closePicker) {
                                ModePickerSurface.CameraModes
                            } else {
                                updatedRuntime.modePickerSurface
                            },
                            statusLabel = if (updatedRuntime.liveViewActive) "Live view active" else updatedRuntime.statusLabel,
                        ),
                    )
                }
                D.proto("AUTO_PATH[$propName] success: selected=$selectedValue sent=$requestedValue closePicker=$closePicker")
                schedulePropertyRefresh(delayMillis = 70L)
            }
            .onFailure { error ->
                D.err("PROTO", "Failed to set $propName=$requestedValue", error)
                updateUiState { current ->
                    val currentRuntime = current.remoteRuntime
                    current.copy(
                        remoteRuntime = currentRuntime.copy(
                            activePicker = if (closePicker) ActivePropertyPicker.None else currentRuntime.activePicker,
                            modePickerSurface = if (closePicker) {
                                ModePickerSurface.CameraModes
                            } else {
                                currentRuntime.modePickerSurface
                            },
                            statusLabel = "${if (picker == ActivePropertyPicker.Iso) "ISO" else "WB"} change failed: ${error.message}",
                        ),
                    )
                }
                schedulePropertyRefresh(delayMillis = 90L)
            }
    }

    private fun applyPropertyResults(
        takeModeResult: CameraPropertyDesc?,
        apertureResult: CameraPropertyDesc?,
        shutterResult: CameraPropertyDesc?,
        isoResult: CameraPropertyDesc?,
        wbResult: CameraPropertyDesc?,
        expCompResult: CameraPropertyDesc?,
        driveResult: CameraPropertyDesc?,
    ) {
        D.prop("applyPropertyResults: takemode=${takeModeResult?.currentValue}, " +
            "F=${apertureResult?.currentValue}, SS=${shutterResult?.currentValue}, " +
            "ISO=${isoResult?.currentValue}, WB=${wbResult?.currentValue}, " +
            "EV=${expCompResult?.currentValue}, drive=${driveResult?.currentValue}")
        val runtime = _uiState.value.remoteRuntime

        val takeModeValues = takeModeResult?.let {
            CameraPropertyValues(
                currentValue = mergePendingPropertyCurrent("takemode", it.currentValue),
                availableValues = sanitizePropertyOptions("takemode", it.enumValues),
            )
        } ?: runtime.takeMode

        val actualAperture = apertureResult?.let {
            val rawOptions = sanitizePropertyOptions("focalvalue", it.enumValues)
            lastRawApertureOptions = rawOptions
            val liveViewRange = currentLiveViewApertureRange()
            val currentVal = mergePendingPropertyCurrent(
                "focalvalue",
                liveViewRange?.currentValue ?: it.currentValue,
            )
            val filtered = buildOriginalApertureRange(
                options = rawOptions,
                minValue = liveViewRange?.minValue ?: it.minValue,
                maxValue = liveViewRange?.maxValue ?: it.maxValue,
                currentValue = currentVal,
                previousValues = runtime.aperture.availableValues,
            )
            D.prop("PROP_RANGE[focalvalue] rawOptions=${rawOptions.size} descMin=${it.minValue} descMax=${it.maxValue} " +
                "lvMin=${liveViewRange?.minValue} lvMax=${liveViewRange?.maxValue} lvCurrent=${liveViewRange?.currentValue} " +
                "current=$currentVal filteredOptions=${filtered.size}: $filtered")
            CameraPropertyValues(
                currentValue = currentVal,
                availableValues = filtered,
                enabled = runtime.aperture.enabled,
            )
        } ?: runtime.aperture

        val actualShutter = shutterResult?.let {
            val sanitized = sanitizePropertyOptions("shutspeedvalue", it.enumValues)
            val currentSS = mergePendingPropertyCurrent("shutspeedvalue", it.currentValue)
            D.prop("PROP_RANGE[shutspeedvalue] rawOptions=${it.enumValues.size} descMin=${it.minValue} descMax=${it.maxValue} " +
                "filteredOptions=${sanitized.size} current=$currentSS")
            CameraPropertyValues(
                currentValue = currentSS,
                availableValues = sanitized,
                enabled = runtime.shutterSpeed.enabled,
            )
        } ?: runtime.shutterSpeed

        val updatedIso = isoResult?.let {
            val options = sanitizePropertyOptions("isospeedvalue", it.enumValues).distinct()
            CameraPropertyValues(
                currentValue = it.currentValue,
                availableValues = options,
                autoActive = resolveIsoAutoActive(
                    currentValue = it.currentValue,
                    options = options,
                    previous = runtime.iso.autoActive,
                ),
            )
        } ?: runtime.iso

        val updatedWb = wbResult?.let {
            val options = sanitizePropertyOptions("wbvalue", it.enumValues).distinct()
            CameraPropertyValues(
                currentValue = it.currentValue,
                availableValues = options,
            )
        } ?: runtime.whiteBalance

        val updatedExposureCompensation = expCompResult?.let {
            val sorted = sanitizePropertyOptions("expcomp", it.enumValues).distinct()
            CameraPropertyValues(
                currentValue = mergePendingPropertyCurrent("expcomp", it.currentValue),
                availableValues = sorted,
            )
        } ?: runtime.exposureCompensationValues

        val newMode = if (takeModeValues.currentValue.isBlank()) {
            runtime.exposureMode
        } else {
            val derived = resolveExposureMode(
                takeModeRaw = takeModeValues.currentValue,
                fallbackMode = runtime.exposureMode,
            )
            if (derived != runtime.exposureMode) {
                D.mode("loadCameraProperties: mode changed ${runtime.exposureMode} -> $derived (takemode=${takeModeValues.currentValue})")
            }
            derived
        }

        val updatedAperture = actualAperture.copy(
            enabled = isApertureControlEnabled(newMode),
        )
        val updatedShutter = actualShutter.copy(
            enabled = isShutterControlEnabled(newMode),
        )
        val nextActivePicker = coerceActivePickerForMode(
            activePicker = runtime.activePicker,
            mode = newMode,
        )

        val driveModeValues = driveResult?.let {
            CameraPropertyValues(
                currentValue = mergePendingPropertyCurrent("drivemode", it.currentValue),
                availableValues = sanitizePropertyOptions("drivemode", it.enumValues),
            )
        } ?: runtime.driveModeValues
        val (driveMode, timerMode) = driveAndTimerFromRaw(driveModeValues.currentValue)

        updateUiState { current -> current.copy(
            remoteRuntime = current.remoteRuntime.copy(
                aperture = updatedAperture,
                shutterSpeed = updatedShutter,
                iso = updatedIso,
                whiteBalance = updatedWb,
                exposureCompensation = PropertyFormatter
                    .exposureCompensationNumericValue(updatedExposureCompensation.currentValue)
                    ?.toFloat()
                    ?: runtime.exposureCompensation,
                exposureCompensationValues = updatedExposureCompensation,
                driveModeValues = driveModeValues,
                driveMode = driveMode,
                timerMode = timerMode,
                exposureMode = newMode,
                takeMode = takeModeValues,
                activePicker = nextActivePicker,
                modePickerSurface = if (nextActivePicker == ActivePropertyPicker.ExposureMode) {
                    runtime.modePickerSurface
                } else {
                    ModePickerSurface.CameraModes
                },
                propertiesLoaded = true,
                statusLabel = if (current.remoteRuntime.liveViewActive) "Live view ready" else current.remoteRuntime.statusLabel,
            ),
        ) }
    }

    /**
     * Suspend variant of [loadCameraProperties] that awaits completion.
     * Used by the reconnect flow to ensure properties are loaded before
     * proceeding with live view recovery or other state restoration.
     */
    private suspend fun loadCameraPropertiesAndAwait() {
        val generation = propertyLoadGeneration.incrementAndGet()
        D.ui("loadCameraPropertiesAndAwait generation=$generation")
        propertyLoadJob?.cancel()
        // Run the property load inline (not in a separate launch) so we can await it
        loadCameraPropertiesInternal(generation)
    }

    private suspend fun loadCameraPropertiesInternal(generation: Long) {
        // Try desclist batch first; retry once on HTTP 520 (pipeline not ready)
        var descList = repository.getPropertyDescList()
            .onSuccess { D.proto("Loaded desclist with ${it.size} properties") }
            .onFailure { D.proto("desclist unavailable: ${it.message}") }
            .getOrDefault(emptyMap())

        if (descList.isEmpty()) {
            // Pipeline might not be ready — wait and retry
            delay(400)
            descList = repository.getPropertyDescList()
                .onSuccess { D.proto("Loaded desclist on retry with ${it.size} properties") }
                .onFailure { D.proto("desclist retry also failed, falling back to per-property reads") }
                .getOrDefault(emptyMap())
        }

        suspend fun fetchProperty(propName: String): CameraPropertyDesc? {
            val descFromList = descList[propName]
            if (isUsablePropertyDesc(descFromList)) {
                if (_uiState.value.remoteRuntime.liveViewActive) delay(40)
                return descFromList
            }

            val directRead = repository.getPropertyDesc(propName)
            if (directRead.isFailure && isTransientCameraPropFailure(directRead.exceptionOrNull())) {
                delay(300)
                return mergePropertyDesc(
                    descFromList,
                    repository.getPropertyDesc(propName).getOrNull(),
                ).also {
                    if (_uiState.value.remoteRuntime.liveViewActive) delay(40)
                }
            }
            return mergePropertyDesc(descFromList, directRead.getOrNull()).also {
                if (_uiState.value.remoteRuntime.liveViewActive) delay(40)
            }
        }

        val takeModeResult = fetchProperty("takemode")
        val apertureResult = fetchProperty("focalvalue")
        val shutterResult = fetchProperty("shutspeedvalue")
        val isoResult = fetchProperty("isospeedvalue")
        val wbResult = fetchProperty("wbvalue")
        val expCompResult = fetchProperty("expcomp")
        val driveResult = fetchProperty("drivemode")

        if (generation != propertyLoadGeneration.get()) {
            D.ui("loadCameraPropertiesInternal: ignoring stale generation=$generation")
            return
        }
        val loadedAnyProperty = listOf(
            takeModeResult,
            apertureResult,
            shutterResult,
            isoResult,
            wbResult,
            expCompResult,
            driveResult,
        ).any { it != null }
        if (!loadedAnyProperty) {
            emptyPropertyLoadRetries += 1
            val retryDelay = (900L + emptyPropertyLoadRetries * 450L).coerceAtMost(3_000L)
            D.proto(
                "Camera property load returned no values " +
                    "(attempt=$emptyPropertyLoadRetries/$EMPTY_PROPERTY_LOAD_RETRY_LIMIT)",
            )
            updateUiState { current -> current.copy(
                remoteRuntime = current.remoteRuntime.copy(
                    propertiesLoaded = false,
                    statusLabel = if (current.remoteRuntime.liveViewActive) {
                        "Live view ready"
                    } else {
                        "Loading camera controls..."
                    },
                ),
            ) }
            if (emptyPropertyLoadRetries <= EMPTY_PROPERTY_LOAD_RETRY_LIMIT && isRemoteSessionReady()) {
                schedulePropertyRefresh(delayMillis = retryDelay)
            }
            return
        }
        emptyPropertyLoadRetries = 0

        applyPropertyResults(takeModeResult, apertureResult, shutterResult, isoResult, wbResult, expCompResult, driveResult)
    }

    fun touchFocus(request: dev.dblink.core.model.TouchFocusRequest) {
        recordQaUiAction(
            title = "Touch focus",
            detail =
                "display=(${request.displayX}, ${request.displayY}) " +
                    "focus=(${request.focusPlaneX}, ${request.focusPlaneY})",
        )
        D.ui(
            "touchFocus ENTRY: display=(${request.displayX}, ${request.displayY}), " +
                "focusPlane=(${request.focusPlaneX}, ${request.focusPlaneY}) " +
                "rotation=${request.rotationDegrees}",
        )
        touchFocusOverlayJob?.cancel()

        // Show AF indicator immediately
        updateUiState { current -> current.copy(
            remoteRuntime = current.remoteRuntime.copy(
                touchFocusPoint = dev.dblink.core.model.TouchFocusPoint(
                    request.displayX,
                    request.displayY,
                ),
                isFocusing = true,
            ),
        ) }

        if (isUsbRemoteTransportActive()) {
            D.ui("touchFocus USB: setting AF area from normalized coordinates")
            viewModelScope.launch {
                val encodedArea = encodeOlympusAfArea(request.focusPlaneX, request.focusPlaneY)
                omCaptureUsbManager.touchFocus(encodedArea)
                    .onSuccess { result ->
                        val statusLabel = when {
                            result.focusLocked -> "AF-ON complete"
                            result.afFrameNotified -> "AF target moved"
                            else -> "AF target updated"
                        }
                        updateUiState { current -> current.copy(
                            remoteRuntime = current.remoteRuntime.copy(
                                isFocusing = false,
                                focusHeld = result.focusLocked,
                                statusLabel = statusLabel,
                            ),
                        ) }
                        touchFocusOverlayJob = viewModelScope.launch {
                            delay(1800)
                            updateUiState { current -> current.copy(
                                remoteRuntime = current.remoteRuntime.copy(
                                    touchFocusPoint = null,
                                    focusHeld = false,
                                ),
                            ) }
                        }
                    }
                    .onFailure { error ->
                        D.err("USB", "Failed to set AF target area", error)
                        updateUiState { current -> current.copy(
                            remoteRuntime = current.remoteRuntime.copy(
                                isFocusing = false,
                                focusHeld = false,
                                touchFocusPoint = null,
                                statusLabel = error.message ?: "AF point selection failed",
                            ),
                        ) }
                    }
            }
            return
        }

        if (!canSendRemoteCommand()) {
            D.ui("touchFocus BLOCKED by canSendRemoteCommand()")
            showRemoteStatus("Reconnect to camera before focusing")
            updateUiState { current -> current.copy(
                remoteRuntime = current.remoteRuntime.copy(
                    isFocusing = false,
                    touchFocusPoint = null,
                ),
            ) }
            return
        }

        viewModelScope.launch {
            val (focusPlaneWidth, focusPlaneHeight) = liveViewFocusPlaneSize()
            val requestedPointX = (request.focusPlaneX * (focusPlaneWidth - 1))
                .roundToInt()
                .coerceIn(0, focusPlaneWidth - 1)
            val requestedPointY = (request.focusPlaneY * (focusPlaneHeight - 1))
                .roundToInt()
                .coerceIn(0, focusPlaneHeight - 1)
            D.proto("touchFocus: sending assignAfFrame($requestedPointX, $requestedPointY)")
            val assignmentResult = repository.assignAfFrame(requestedPointX, requestedPointY)
            val assignment = assignmentResult.getOrElse { error ->
                D.err("PROTO", "Touch AF FAILED at ($requestedPointX, $requestedPointY): ${error.message}", error)
                updateUiState { current -> current.copy(
                    remoteRuntime = current.remoteRuntime.copy(
                        isFocusing = false,
                        focusHeld = false,
                        touchFocusPoint = null,
                        statusLabel = error.message ?: "AF point selection failed",
                    ),
                ) }
                return@launch
            }
            val confirmedDisplayPoint = confirmedTouchFocusPoint(
                confirmedX = assignment.confirmedX,
                confirmedY = assignment.confirmedY,
                focusPlaneWidth = focusPlaneWidth,
                focusPlaneHeight = focusPlaneHeight,
                rotationDegrees = request.rotationDegrees,
                requestedDisplayX = request.displayX,
                requestedDisplayY = request.displayY,
            )
            D.proto(
                "Touch AF SUCCESS at ($requestedPointX, $requestedPointY) " +
                    "confirmed=(${assignment.confirmedX}, ${assignment.confirmedY})",
            )
            updateUiState { current -> current.copy(
                remoteRuntime = current.remoteRuntime.copy(
                    touchFocusPoint = confirmedDisplayPoint,
                    isFocusing = false,
                    focusHeld = true,
                    statusLabel = "AF point locked",
                ),
            ) }
            touchFocusOverlayJob = viewModelScope.launch {
                delay(1800)
                updateUiState { current -> current.copy(
                    remoteRuntime = current.remoteRuntime.copy(
                        touchFocusPoint = null,
                        focusHeld = false,
                    ),
                ) }
            }
        }
    }

    private fun liveViewFocusPlaneSize(): Pair<Int, Int> {
        return if (appConfig.useHighQualityScreennails) {
            800 to 600
        } else {
            640 to 480
        }
    }

    private fun confirmedTouchFocusPoint(
        confirmedX: Int,
        confirmedY: Int,
        focusPlaneWidth: Int,
        focusPlaneHeight: Int,
        rotationDegrees: Int,
        requestedDisplayX: Float,
        requestedDisplayY: Float,
    ): dev.dblink.core.model.TouchFocusPoint {
        val normalizedX = confirmedX
            .toFloat()
            .div((focusPlaneWidth - 1).coerceAtLeast(1))
            .coerceIn(0f, 1f)
        val normalizedY = confirmedY
            .toFloat()
            .div((focusPlaneHeight - 1).coerceAtLeast(1))
            .coerceIn(0f, 1f)
        val displayPoint = mapSensorFocusPointToDisplay(
            normalizedX = normalizedX,
            normalizedY = normalizedY,
            rotationDegrees = rotationDegrees,
        )
        val useRequestedDisplayPoint =
            abs(displayPoint.x - requestedDisplayX) <= TOUCH_FOCUS_CONFIRMATION_MOVE_THRESHOLD &&
                abs(displayPoint.y - requestedDisplayY) <= TOUCH_FOCUS_CONFIRMATION_MOVE_THRESHOLD
        return dev.dblink.core.model.TouchFocusPoint(
            x = if (useRequestedDisplayPoint) requestedDisplayX else displayPoint.x,
            y = if (useRequestedDisplayPoint) requestedDisplayY else displayPoint.y,
        )
    }

    fun setIntervalSeconds(seconds: Int) {
        recordQaUiAction(title = "Set interval seconds", detail = "${seconds}s")
        updateUiState { current -> current.copy(
            remoteRuntime = current.remoteRuntime.copy(intervalSeconds = seconds),
        ) }
        scheduleUsbExtraPropertyUpdate(
            propCode = USB_INTERVAL_SECONDS_PROP,
            value = seconds.toLong(),
            statusLabel = "Interval update",
        )
    }

    fun setIntervalCount(count: Int) {
        recordQaUiAction(title = "Set interval count", detail = count.toString())
        updateUiState { current -> current.copy(
            remoteRuntime = current.remoteRuntime.copy(intervalCount = count),
        ) }
        scheduleUsbExtraPropertyUpdate(
            propCode = USB_INTERVAL_COUNT_PROP,
            value = count.toLong(),
            statusLabel = "Interval count update",
        )
    }

    fun startInterval() {
        if (_uiState.value.remoteRuntime.intervalRunning) return
        if (!canSendRemoteCommand()) {
            showRemoteStatus("Reconnect to camera before starting interval")
            return
        }
        val total = _uiState.value.remoteRuntime.intervalCount
        val intervalMs = _uiState.value.remoteRuntime.intervalSeconds * 1000L
        D.ui("startInterval: $total shots, ${intervalMs}ms interval")
        updateUiState { current -> current.copy(
            remoteRuntime = current.remoteRuntime.copy(
                intervalRunning = true,
                intervalCurrent = 0,
                statusLabel = "Starting interval...",
            ),
        ) }
        intervalJob = viewModelScope.launch {
            var successCount = 0
            var failCount = 0
            var consecutiveFailures = 0
            val maxConsecutiveFailures = 3

            for (i in 1..total) {
                ensureActive()

                // Check connection before each shot
                if (!canSendRemoteCommand()) {
                    D.err("REMOTE", "Interval $i/$total: connection lost, stopping")
                    updateUiState { current -> current.copy(
                        remoteRuntime = current.remoteRuntime.copy(
                            intervalRunning = false,
                            statusLabel = "Interval stopped: connection lost ($successCount/$total captured)",
                        ),
                    ) }
                    return@launch
                }

                // Prevent duplicate trigger if a single capture is in progress
                if (isCapturing) {
                    D.ui("Interval $i/$total: waiting for in-progress capture...")
                    // Wait up to 15s for the existing capture to finish
                    var waited = 0L
                    while (isCapturing && waited < 15000L) {
                        delay(200)
                        waited += 200
                    }
                    if (isCapturing) {
                        D.err("REMOTE", "Interval $i/$total: capture still in progress after 15s, skipping")
                        failCount++
                        consecutiveFailures++
                        if (consecutiveFailures >= maxConsecutiveFailures) {
                            D.err("REMOTE", "Interval: $maxConsecutiveFailures consecutive failures, aborting")
                            break
                        }
                        continue
                    }
                }

                updateUiState { current -> current.copy(
                    remoteRuntime = current.remoteRuntime.copy(
                        intervalCurrent = i,
                        statusLabel = "Interval $i/$total capturing...",
                    ),
                ) }

                val captureStartMs = System.currentTimeMillis()
                val captureResult = try {
                    performRemoteCaptureCommand(_uiState.value.remoteRuntime.liveViewActive)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }

                captureResult
                    .onSuccess {
                        successCount++
                        consecutiveFailures = 0
                        updateUiState { current -> current.copy(
                            remoteRuntime = current.remoteRuntime.copy(
                                captureCount = current.remoteRuntime.captureCount + 1,
                            ),
                        ) }
                    }
                    .onFailure { throwable ->
                        failCount++
                        consecutiveFailures++
                        D.err("REMOTE", "Interval capture $i/$total failed (consecutive=$consecutiveFailures)", throwable)
                    }

                // Abort after too many consecutive failures
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    D.err("REMOTE", "Interval: $maxConsecutiveFailures consecutive failures, aborting")
                    updateUiState { current -> current.copy(
                        remoteRuntime = current.remoteRuntime.copy(
                            intervalRunning = false,
                            statusLabel = "Interval aborted: repeated failures ($successCount captured)",
                        ),
                    ) }
                    return@launch
                }

                // Wait for remaining interval time (subtract capture duration)
                if (i < total) {
                    ensureActive()
                    val captureElapsed = System.currentTimeMillis() - captureStartMs
                    val remainingDelay = (intervalMs - captureElapsed).coerceAtLeast(500L)
                    updateUiState { current -> current.copy(
                        remoteRuntime = current.remoteRuntime.copy(
                            statusLabel = "Next shot in ${(remainingDelay / 1000)}s... ($successCount/$total)",
                        ),
                    ) }
                    delay(remainingDelay)
                }
            }

            val statusMsg = if (failCount > 0) {
                "Interval done: $successCount captured, $failCount failed"
            } else {
                "Interval complete ($successCount shots)"
            }
            updateUiState { current -> current.copy(
                remoteRuntime = current.remoteRuntime.copy(
                    intervalRunning = false,
                    statusLabel = statusMsg,
                ),
            ) }
        }
    }

    fun stopInterval() {
        if (!_uiState.value.remoteRuntime.intervalRunning) return
        val shotsTaken = _uiState.value.remoteRuntime.intervalCurrent
        D.ui("stopInterval at $shotsTaken")
        intervalJob?.cancel()
        intervalJob = null
        updateUiState { state -> state.copy(
            remoteRuntime = state.remoteRuntime.copy(
                intervalRunning = false,
                statusLabel = "Interval stopped ($shotsTaken shots taken)",
            ),
        ) }
    }

    private fun shouldUseOmCaptureUsbLibrary(): Boolean {
        return _uiState.value.omCaptureUsb.summary != null
    }

    private fun shouldAttemptOmCaptureUsbLibraryConnection(): Boolean {
        val usbState = _uiState.value.omCaptureUsb
        val status = usbState.statusLabel
        return usbState.summary != null ||
            usbState.lastActionLabel == "USB camera attached" ||
            status.contains("Select Tether to connect", ignoreCase = true) ||
            status.contains("ready over USB/PTP", ignoreCase = true) ||
            status.contains("basic MTP mode", ignoreCase = true)
    }

    fun isOmCaptureUsbLibraryAvailable(): Boolean = shouldAttemptOmCaptureUsbLibraryConnection()

    private fun buildUsbTransferCameraImage(item: OmCaptureUsbLibraryItem): CameraImage {
        return CameraImage(
            directory = "/USB/PTP/${item.storageId.toString(16)}/${item.handle.toString(16)}",
            fileName = item.fileName,
            fileSize = item.fileSize,
            attribute = 0,
            width = item.width,
            height = item.height,
        )
    }

    private fun buildSavedMediaTransferCameraImage(record: UsbSavedMediaRecord): CameraImage {
        val directory = "/PHONE/USB/${record.savedMedia.dateFolder.ifBlank { "Unsorted" }}"
        val fileSize = File(record.savedMedia.absolutePath).takeIf(File::exists)?.length() ?: 0L
        return CameraImage(
            directory = directory,
            fileName = record.savedMedia.displayName,
            fileSize = fileSize,
            attribute = 0,
            width = record.width,
            height = record.height,
        )
    }

    private fun savedMediaTransferPath(record: UsbSavedMediaRecord): String =
        buildSavedMediaTransferCameraImage(record).fullPath

    private fun currentUsbSavedMediaPreviewItems(): List<OmCaptureUsbSavedMedia> {
        val mappedFromVisibleImages = _uiState.value.transferState.images
            .mapNotNull { image -> usbSavedMediaByTransferPath[image.fullPath] }
            .distinctBy { it.uriString }
        if (mappedFromVisibleImages.isNotEmpty()) {
            return mappedFromVisibleImages
        }
        return usbSavedMediaHistory.values
            .toList()
            .asReversed()
            .map(UsbSavedMediaRecord::savedMedia)
    }

    private fun resolveUsbTransferHandle(image: CameraImage): Int? {
        return _uiState.value.transferState.usbObjectHandlesByPath[image.fullPath]
    }

    fun setUsbLibrarySourceSelection(selectedStorageIds: Set<Int>?) {
        val storageSelection = selectedStorageIds?.takeIf { it.isNotEmpty() }
        val current = _uiState.value.transferState
        updateUiState { it.copy(
            transferState = current.copy(
                selectedUsbStorageIds = storageSelection,
                sourceLabel = if (current.sourceKind == TransferSourceKind.OmCaptureUsb) {
                    usbStorageSelectionLabel(storageSelection, current.usbAvailableStorageIds)
                } else {
                    current.sourceLabel
                },
            ),
        ) }
        if (_uiState.value.transferState.sourceKind == TransferSourceKind.OmCaptureUsb && usbLibraryCache.isNotEmpty()) {
            applyUsbTransferItems(usbLibraryCache)
        }
    }

    private fun applyUsbTransferItems(
        items: List<OmCaptureUsbLibraryItem>,
        isLoading: Boolean = false,
        errorMessage: String? = null,
    ) {
        usbLibraryCache = items
        usbLibraryCacheUpdatedAtMs = if (items.isEmpty() || isLoading || errorMessage != null) {
            0L
        } else {
            SystemClock.elapsedRealtime()
        }
        val storageIds = items.map { it.storageId }.distinct().sorted()
        val selectedStorageIds = _uiState.value.transferState.selectedUsbStorageIds
            ?.filter { it in storageIds }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
        val visibleItems = if (selectedStorageIds.isNullOrEmpty()) {
            items
        } else {
            items.filter { it.storageId in selectedStorageIds }
        }
        val cameraImages = visibleItems.map(::buildUsbTransferCameraImage)
        val cameraNames = cameraImages.mapTo(linkedSetOf()) { normalizeSavedMediaKey(it.fileName) }
        val localOnlySavedMedia = usbSavedMediaHistory.values
            .toList()
            .asReversed()
            .filter { record -> normalizeSavedMediaKey(record.savedMedia.displayName) !in cameraNames }
        val localImages = localOnlySavedMedia.map(::buildSavedMediaTransferCameraImage)
        val images = (cameraImages + localImages)
            .sortedByDescending { "${it.captureDateKey}_${it.fileName}" }
        val handleMap = visibleItems.associate { item ->
            buildUsbTransferCameraImage(item).fullPath to item.handle
        }
        usbSavedMediaByTransferPath.clear()
        visibleItems.forEachIndexed { index, item ->
            resolveSavedMediaForKnownFileName(item.fileName)?.let { savedMedia ->
                usbSavedMediaByTransferPath[cameraImages[index].fullPath] = savedMedia
            }
        }
        localOnlySavedMedia.forEach { record ->
            usbSavedMediaByTransferPath[savedMediaTransferPath(record)] = record.savedMedia
        }
        applyTransferImageList(
            images = images,
            isLoading = isLoading,
            errorMessage = errorMessage,
            sourceCameraSsid = null,
            sourceKind = TransferSourceKind.OmCaptureUsb,
            sourceLabel = usbStorageSelectionLabel(selectedStorageIds, storageIds),
            supportsDelete = false,
            usbObjectHandlesByPath = handleMap,
            usbAvailableStorageIds = storageIds,
            selectedUsbStorageIds = selectedStorageIds,
        )
    }

    fun loadCameraImages() {
        D.transfer("loadCameraImages")
        imageListJob?.cancel()
        val hasExistingImages = _uiState.value.transferState.images.isNotEmpty()
        val requestGeneration = transferLoadGeneration.incrementAndGet()
        val requestSsid = _uiState.value.selectedCameraSsid
        val usbLibraryRequested = shouldAttemptOmCaptureUsbLibraryConnection()
        if (usbLibraryRequested) {
            prepareOmCaptureUsbForTransferMode()
        }
        fun isCurrentTransferRequest(): Boolean {
            if (requestGeneration != transferLoadGeneration.get()) {
                return false
            }
            if (usbLibraryRequested || shouldAttemptOmCaptureUsbLibraryConnection()) {
                return true
            }
            val currentSelectedSsid = _uiState.value.selectedCameraSsid
            return !requestSsid.isNullOrBlank() &&
                requestSsid.equals(currentSelectedSsid, ignoreCase = true)
        }
        imageListJob = viewModelScope.launch {
            var useUsbLibrary = shouldUseOmCaptureUsbLibrary()
            if (!useUsbLibrary && usbLibraryRequested) {
                updateUiState { current -> current.copy(
                    transferState = current.transferState.copy(
                        isLoading = true,
                        errorMessage = null,
                        sourceKind = TransferSourceKind.OmCaptureUsb,
                        sourceLabel = current.omCaptureUsb.summary?.deviceLabel ?: "USB/PTP",
                        supportsDelete = false,
                    ),
                ) }
                val inspectResult = omCaptureUsbManager.inspectConnectedCamera()
                if (!isCurrentTransferRequest()) {
                    return@launch
                }
                if (inspectResult.isFailure) {
                    val throwable = inspectResult.exceptionOrNull()
                    D.err("TRANSFER", "Failed to prepare OM USB library session", throwable)
                    updateUiState { current -> current.copy(
                        transferState = current.transferState.copy(
                            isLoading = false,
                            errorMessage = throwable?.message ?: if (hasExistingImages) {
                                "USB library refresh failed"
                            } else {
                                "Failed to load OM camera photos over USB"
                            },
                            sourceKind = TransferSourceKind.OmCaptureUsb,
                            sourceLabel = current.omCaptureUsb.summary?.deviceLabel ?: "USB/PTP",
                            supportsDelete = false,
                        ),
                    ) }
                    return@launch
                }
                useUsbLibrary = true
            }

            if (useUsbLibrary) {
                val hasFreshUsbLibraryCache =
                    usbLibraryCache.isNotEmpty() &&
                        (SystemClock.elapsedRealtime() - usbLibraryCacheUpdatedAtMs) <= USB_LIBRARY_CACHE_TTL_MS
                if (hasFreshUsbLibraryCache) {
                    applyUsbTransferItems(
                        items = usbLibraryCache,
                        isLoading = false,
                        errorMessage = null,
                    )
                    val images = _uiState.value.transferState.images
                    loadThumbnails(images)
                    scanDownloadedFiles()
                    return@launch
                }
                updateUiState { current -> current.copy(
                    transferState = current.transferState.copy(
                        isLoading = true,
                        errorMessage = null,
                        sourceKind = TransferSourceKind.OmCaptureUsb,
                        sourceLabel = current.omCaptureUsb.summary?.deviceLabel ?: "USB/PTP",
                        supportsDelete = false,
                    ),
                ) }
                if (usbLibraryCache.isNotEmpty()) {
                    applyUsbTransferItems(
                        items = usbLibraryCache,
                        isLoading = true,
                        errorMessage = null,
                    )
                }
                val imageResult = omCaptureUsbManager.loadLibraryItems()
                if (!isCurrentTransferRequest()) {
                    return@launch
                }
                if (imageResult.isSuccess) {
                    val items = imageResult.getOrThrow()
                        .sortedWith(
                            compareByDescending<OmCaptureUsbLibraryItem> { it.captureTimestampMillis ?: Long.MIN_VALUE }
                                .thenByDescending { it.fileName },
                        )
                    applyUsbTransferItems(items = items, isLoading = false, errorMessage = null)
                    val images = _uiState.value.transferState.images
                    loadThumbnails(images)
                    scanDownloadedFiles()
                } else {
                    val throwable = imageResult.exceptionOrNull()
                    D.err("TRANSFER", "Failed to load OM USB library images", throwable)
                    updateUiState { current -> current.copy(
                        transferState = current.transferState.copy(
                            isLoading = false,
                            errorMessage = throwable?.message ?: if (hasExistingImages) {
                                "USB library refresh failed"
                            } else {
                                "Failed to load OM camera photos over USB"
                            },
                            sourceKind = TransferSourceKind.OmCaptureUsb,
                            sourceLabel = current.omCaptureUsb.summary?.deviceLabel ?: "USB/PTP",
                            supportsDelete = false,
                        ),
                    ) }
                }
                return@launch
            }

            clearUsbLibraryCache()
            if (!canSendRemoteCommand()) {
                updateUiState { current -> current.copy(
                    transferState = current.transferState.copy(
                        isLoading = true,
                        errorMessage = null,
                    ),
                ) }
                val credentials = preferencesRepository.loadCameraCredentials(requestSsid)
                val networkReady = ensureCameraWifiReady(
                    ssid = credentials?.ssid,
                    password = credentials?.password,
                    retries = 10,
                    retryDelayMillis = 500L,
                )
                if (!isCurrentTransferRequest()) {
                    return@launch
                }
                if (networkReady) {
                    loadCameraImages()
                } else {
                    updateUiState { current -> current.copy(
                        transferState = current.transferState.copy(
                            isLoading = false,
                            errorMessage = "Reconnect to the saved camera before opening the library",
                        ),
                    ) }
                }
                return@launch
            }

            updateUiState { current -> current.copy(
                transferState = current.transferState.copy(
                    isLoading = true,
                    errorMessage = null,
                ),
            ) }
            val slotResult = ensureSelectedPlayTargetSlotApplied()
            if (!isCurrentTransferRequest()) {
                return@launch
            }
            if (slotResult.isFailure) {
                val throwable = slotResult.exceptionOrNull()
                updateUiState { current -> current.copy(
                    transferState = current.transferState.copy(
                        isLoading = false,
                        errorMessage = throwable?.message ?: "Failed to switch photo source",
                    ),
                ) }
                return@launch
            }
            val imageResult = repository.getImageList("/DCIM")
            if (imageResult.isSuccess) {
                if (!isCurrentTransferRequest()) {
                    return@launch
                }
                val images = imageResult.getOrThrow()
                D.transfer("Got ${images.size} images")
                val newestFirst = images.sortedByDescending { "${it.captureDateKey}_${it.fileName}" }
                applyTransferImageList(
                    images = newestFirst,
                    isLoading = false,
                    errorMessage = null,
                    sourceCameraSsid = requestSsid,
                    sourceKind = TransferSourceKind.WifiCamera,
                    sourceLabel = wifiTransferSourceLabel(_uiState.value.selectedCardSlotSource, requestSsid),
                    supportsDelete = true,
                    usbObjectHandlesByPath = emptyMap(),
                    usbAvailableStorageIds = emptyList(),
                    selectedUsbStorageIds = null,
                )
                if (!isCurrentTransferRequest()) {
                    return@launch
                }
                applyPendingLastCapturePreview(newestFirst)
                maybeTriggerArmedAutoImport(newestFirst)
                loadThumbnails(newestFirst)
                scanDownloadedFiles()
            } else {
                if (!isCurrentTransferRequest()) {
                    return@launch
                }
                val throwable = imageResult.exceptionOrNull()
                D.err("TRANSFER", "Failed to load images", throwable)
                updateUiState { current -> current.copy(
                    transferState = current.transferState.copy(
                        isLoading = false,
                        errorMessage = throwable?.message ?: if (hasExistingImages) {
                            "Library refresh failed"
                        } else {
                            "Failed to load photos"
                        },
                    ),
                ) }
            }
        }
    }

    private fun loadThumbnails(images: List<CameraImage>) {
        thumbnailJob?.cancel()
        thumbnailJob = viewModelScope.launch {
            if (shouldPauseWifiLibraryMediaRequests()) {
                return@launch
            }
            val transferState = _uiState.value.transferState
            if (transferState.sourceKind == TransferSourceKind.OmCaptureUsb) {
                val handleMap = transferState.usbObjectHandlesByPath
                val toLoad = images
                    .filter { it.fileName !in transferState.thumbnails }
                    .take(USB_TRANSFER_THUMBNAIL_PREFETCH_LIMIT)
                // Batch-pause live view ONCE for all thumbnails instead of
                // per-thumbnail pause/resume which causes visible stutter.
                val paused = omCaptureUsbManager.pauseLiveViewBatch("thumbnail-batch")
                usbTransferThumbnailPrefetchActive = true
                try {
                    for ((index, image) in toLoad.withIndex()) {
                        ensureActive()
                        if (index > 0) {
                            delay(USB_TRANSFER_THUMBNAIL_PREFETCH_DELAY_MS)
                        }
                        val handle = handleMap[image.fullPath] ?: continue
                        val bytes = omCaptureUsbManager.loadLibraryThumbnail(handle).getOrNull()
                        if (bytes == null) {
                            D.usb(
                                "Stopping USB thumbnail prefetch after a failed thumbnail " +
                                    "handle=${handle.toString(16)} file=${image.fileName}",
                            )
                            break
                        }
                        val bitmap = decodeTransferPreviewBitmap(bytes) ?: continue
                        updateTransferThumbnails(
                            listOf(image.fileName to bitmap),
                            overwriteExisting = false,
                        )
                    }
                } finally {
                    usbTransferThumbnailPrefetchActive = false
                    if (paused) omCaptureUsbManager.resumeLiveViewBatch("thumbnail-batch")
                }
                return@launch
            }
            if (ensureSelectedPlayTargetSlotApplied().isFailure) {
                return@launch
            }
            if (_uiState.value.libraryCompatibilityMode == LibraryCompatibilityMode.HighSpeed) {
                loadWifiThumbnailsFast(images)
                return@launch
            }
            loadWifiThumbnailsSlow(images)
        }
    }

    private suspend fun loadWifiThumbnailsFast(images: List<CameraImage>) = coroutineScope {
        val toLoad = images.filter { it.fileName !in _uiState.value.transferState.thumbnails }
        toLoad.chunked(6).forEach { batch ->
            ensureActive()
            if (shouldPauseWifiLibraryMediaRequests()) return@coroutineScope
            if (!canSendRemoteCommand()) return@coroutineScope
            val deferred = batch.filter { it.isJpeg }.map { image ->
                async(Dispatchers.IO) {
                    loadWifiThumbnailResult(image)
                }
            }
            val results = mutableListOf<WifiThumbnailLoadResult>()
            results += deferred.awaitAll().filterNotNull()
            batch.filterNot { it.isJpeg }.forEach { image ->
                ensureActive()
                if (shouldPauseWifiLibraryMediaRequests()) return@coroutineScope
                if (!canSendRemoteCommand()) return@coroutineScope
                loadWifiThumbnailResult(image)?.let(results::add)
            }
            updateTransferThumbnails(results.map { it.fileName to it.bitmap }, overwriteExisting = false)
            results.filter { it.usedPreview }.forEach { result ->
                previewThumbnailUpgrades += result.fileName
                clearPreviewUnavailable(result.fileName)
            }
        }

        buildPreviewThumbnailTargets(images)
            .filterNot { it.isRaw || it.isMovie }
            .filterNot { it.fileName in previewThumbnailUpgrades }
            .forEach { image ->
                ensureActive()
                if (shouldPauseWifiLibraryMediaRequests()) return@coroutineScope
                if (!canSendRemoteCommand()) return@coroutineScope
                val preview = loadWifiPreviewUpgradeBitmap(image) ?: return@forEach
                previewThumbnailUpgrades += image.fileName
                clearPreviewUnavailable(image.fileName)
                updateTransferThumbnails(listOf(image.fileName to preview), overwriteExisting = true)
            }
    }

    private suspend fun loadWifiThumbnailsSlow(images: List<CameraImage>) {
        waitForWifiMediaRequestCooldown()
        val toLoad = images
            .filter { it.fileName !in _uiState.value.transferState.thumbnails }
            .take(WIFI_THUMBNAIL_PREFETCH_LIMIT)
        var consecutiveFailures = 0
        var shouldLoadPreviewUpgrades = true
        for (image in toLoad) {
            currentCoroutineContext().ensureActive()
            if (shouldPauseWifiLibraryMediaRequests()) return
            if (!canSendRemoteCommand()) return
            val result = loadWifiThumbnailResult(image)
            if (result != null) {
                consecutiveFailures = 0
                updateTransferThumbnails(listOf(result.fileName to result.bitmap), overwriteExisting = false)
                if (result.usedPreview) {
                    previewThumbnailUpgrades += result.fileName
                    clearPreviewUnavailable(result.fileName)
                }
                delay(WIFI_THUMBNAIL_REQUEST_DELAY_MS)
            } else {
                consecutiveFailures += 1
                if (consecutiveFailures >= WIFI_THUMBNAIL_FAILURE_LIMIT) {
                    shouldLoadPreviewUpgrades = false
                    startWifiMediaRequestCooldown("thumbnail failures")
                    break
                }
                delay(WIFI_THUMBNAIL_FAILURE_BACKOFF_MS * consecutiveFailures)
            }
        }

        if (!shouldLoadPreviewUpgrades) {
            return
        }
        consecutiveFailures = 0
        val previewTargets = buildPreviewThumbnailTargets(images)
            .filterNot { it.isRaw || it.isMovie }
            .filterNot { it.fileName in previewThumbnailUpgrades }
        for (image in previewTargets) {
            currentCoroutineContext().ensureActive()
            if (shouldPauseWifiLibraryMediaRequests()) return
            if (!canSendRemoteCommand()) return
            waitForWifiMediaRequestCooldown()
            val preview = loadWifiPreviewUpgradeBitmap(image)
            if (preview == null) {
                consecutiveFailures += 1
                if (consecutiveFailures >= WIFI_THUMBNAIL_FAILURE_LIMIT) {
                    startWifiMediaRequestCooldown("preview thumbnail failures")
                    return
                }
                delay(WIFI_THUMBNAIL_FAILURE_BACKOFF_MS * consecutiveFailures)
                continue
            }
            consecutiveFailures = 0
            previewThumbnailUpgrades += image.fileName
            clearPreviewUnavailable(image.fileName)
            updateTransferThumbnails(listOf(image.fileName to preview), overwriteExisting = true)
            delay(WIFI_THUMBNAIL_REQUEST_DELAY_MS)
        }
    }

    private suspend fun loadWifiThumbnailResult(image: CameraImage): WifiThumbnailLoadResult? {
        val payload = loadWifiThumbnailPayload(image) ?: return null
        val bytes = payload.bytes
        val bitmap = withContext(Dispatchers.Default) {
            decodeTransferPreviewBitmap(bytes)
        } ?: return null
        return WifiThumbnailLoadResult(
            fileName = image.fileName,
            bitmap = bitmap,
            usedPreview = payload.usedPreview,
        )
    }

    private suspend fun loadWifiThumbnailPayload(image: CameraImage): WifiThumbnailPayload? {
        val payload = withContext(Dispatchers.IO) {
            when {
                image.isRaw -> {
                    loadWifiPreviewThumbnailBytes(image)?.let { WifiThumbnailPayload(it, true) }
                        ?: loadWifiBasicThumbnailBytes(image)?.let { WifiThumbnailPayload(it, false) }
                }
                image.isMovie -> {
                    loadWifiPreviewThumbnailBytes(image)?.let { WifiThumbnailPayload(it, true) }
                        ?: loadWifiBasicThumbnailBytes(image)?.let { WifiThumbnailPayload(it, false) }
                        ?: loadWifiResizedThumbnailBytes(image)?.let { WifiThumbnailPayload(it, true) }
                }
                else -> {
                    loadWifiBasicThumbnailBytes(image)?.let { WifiThumbnailPayload(it, false) }
                        ?: loadWifiPreviewThumbnailBytes(image)?.let { WifiThumbnailPayload(it, true) }
                        ?: loadWifiResizedThumbnailBytes(image)?.let { WifiThumbnailPayload(it, true) }
                }
            }
        } ?: return null
        updatePreviewDetails(image, payload.bytes)
        return payload
    }

    private suspend fun loadWifiPreviewUpgradeBitmap(image: CameraImage): Bitmap? {
        val bytes = withContext(Dispatchers.IO) {
            loadWifiPreviewThumbnailBytes(image) ?: loadWifiResizedThumbnailBytes(image)
        } ?: return null
        updatePreviewDetails(image, bytes)
        return withContext(Dispatchers.Default) {
            decodeTransferPreviewBitmap(bytes)
        }
    }

    private suspend fun loadWifiBasicThumbnailBytes(image: CameraImage): ByteArray? {
        if (image.fullPath in wifiThumbnail404Paths) {
            return null
        }
        val result = withTimeoutOrNull(1_400L) {
            repository.getThumbnail(image)
        } ?: return null
        val bytes = result.getOrNull()
        if (bytes == null && isHttp404(result.exceptionOrNull())) {
            wifiThumbnail404Paths += image.fullPath
        }
        return bytes
    }

    private suspend fun loadWifiPreviewThumbnailBytes(image: CameraImage): ByteArray? {
        if (image.fullPath in wifiPreview404Paths) {
            return null
        }
        val result = withTimeoutOrNull(1_800L) {
            repository.getPreviewThumbnail(image)
        } ?: return null
        val bytes = result.getOrNull()
        if (bytes == null && isHttp404(result.exceptionOrNull())) {
            wifiPreview404Paths += image.fullPath
        }
        return bytes
    }

    private suspend fun loadWifiResizedThumbnailBytes(image: CameraImage): ByteArray? {
        if (image.isRaw) {
            return null
        }
        if (image.fullPath in wifiResizedThumbnail404Paths) {
            return null
        }
        if (SystemClock.elapsedRealtime() < wifiResizedThumbnailCooldownUntilMs) {
            return null
        }
        val targetWidth = when {
            image.isRaw -> 640
            image.isMovie -> 960
            else -> 720
        }
        val result = withTimeoutOrNull(2_400L) {
            repository.getResizedImageWithErr(image, width = targetWidth)
        } ?: return null
        val bytes = result.getOrNull()
        if (bytes != null) {
            return bytes
        }
        val failure = result.exceptionOrNull()
        if (isHttp404(failure)) {
            wifiResizedThumbnail404Paths += image.fullPath
        } else if (isHttp520(failure)) {
            startWifiResizedThumbnailCooldown("resizeimg_witherr 520")
            return null
        }
        val fallbackResult = withTimeoutOrNull(2_400L) {
            repository.getResizedImage(image, width = targetWidth)
        } ?: return null
        val fallbackBytes = fallbackResult.getOrNull()
        if (fallbackBytes == null) {
            val fallbackFailure = fallbackResult.exceptionOrNull()
            if (isHttp404(fallbackFailure)) {
                wifiResizedThumbnail404Paths += image.fullPath
            } else if (isHttp520(fallbackFailure)) {
                startWifiResizedThumbnailCooldown("resizeimg 520")
            }
        }
        return fallbackBytes
    }

    private fun isHttp404(throwable: Throwable?): Boolean {
        return throwable?.message?.contains("HTTP 404") == true
    }

    private fun isHttp520(throwable: Throwable?): Boolean {
        return throwable?.message?.contains("HTTP 520") == true
    }

    private fun startWifiResizedThumbnailCooldown(reason: String) {
        val nextUntil = SystemClock.elapsedRealtime() + WIFI_RESIZED_THUMBNAIL_COOLDOWN_MS
        if (nextUntil <= wifiResizedThumbnailCooldownUntilMs) {
            return
        }
        wifiResizedThumbnailCooldownUntilMs = nextUntil
        D.transfer("Pausing Wi-Fi resized thumbnail requests after $reason")
    }

    private data class WifiThumbnailLoadResult(
        val fileName: String,
        val bitmap: Bitmap,
        val usedPreview: Boolean,
    )

    private data class WifiThumbnailPayload(
        val bytes: ByteArray,
        val usedPreview: Boolean,
    )

    private suspend fun waitForWifiMediaRequestCooldown() {
        val remaining = wifiMediaRequestCooldownUntilMs - SystemClock.elapsedRealtime()
        if (remaining > 0L) {
            delay(remaining)
        }
    }

    private fun startWifiMediaRequestCooldown(reason: String) {
        wifiMediaRequestCooldownUntilMs = SystemClock.elapsedRealtime() + WIFI_MEDIA_REQUEST_COOLDOWN_MS
        D.transfer("Pausing Wi-Fi library media requests after $reason")
    }

    private fun scanDownloadedFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val downloaded = loadDownloadedFileNames()
                val currentTransfer = _uiState.value.transferState
                if (
                    currentTransfer.downloadedFileNames == downloaded &&
                    currentTransfer.downloadedCount == downloaded.size
                ) {
                    return@launch
                }
                updateUiState { it.copy(
                    transferState = currentTransfer.copy(
                        downloadedFileNames = downloaded,
                        downloadedCount = downloaded.size,
                    ),
                ) }
            } catch (e: Exception) {
                D.err("TRANSFER", "Failed to scan downloaded files", e)
            }
        }
    }

    private fun buildPreviewThumbnailTargets(images: List<CameraImage>): List<CameraImage> {
        if (images.isEmpty()) {
            return emptyList()
        }
        val prioritized = linkedMapOf<String, CameraImage>()
        val selectedPath = _uiState.value.transferState.selectedImage?.fullPath
        selectedPath?.let { path ->
            images.firstOrNull { it.fullPath == path }?.let { prioritized[path] = it }
        }
        images.take(TRANSFER_PREVIEW_PREFETCH_LIMIT).forEach { image ->
            prioritized[image.fullPath] = image
        }
        return prioritized.values.toList()
    }

    private fun applyTransferImageList(
        images: List<CameraImage>,
        isLoading: Boolean,
        errorMessage: String?,
        sourceCameraSsid: String?,
        sourceKind: TransferSourceKind = _uiState.value.transferState.sourceKind,
        sourceLabel: String = _uiState.value.transferState.sourceLabel,
        supportsDelete: Boolean = _uiState.value.transferState.supportsDelete,
        usbObjectHandlesByPath: Map<String, Int> = _uiState.value.transferState.usbObjectHandlesByPath,
        usbAvailableStorageIds: List<Int> = _uiState.value.transferState.usbAvailableStorageIds,
        selectedUsbStorageIds: Set<Int>? = _uiState.value.transferState.selectedUsbStorageIds,
    ) {
        val transfer = _uiState.value.transferState
        val validPaths = images.mapTo(linkedSetOf()) { it.fullPath }
        val validFileNames = images.mapTo(linkedSetOf()) { it.fileName }
        previewThumbnailUpgrades.retainAll(validFileNames)
        wifiThumbnail404Paths.retainAll(validPaths)
        wifiPreview404Paths.retainAll(validPaths)
        wifiResizedThumbnail404Paths.retainAll(validPaths)
        val retainedThumbnails = transfer.thumbnails.filterKeys { it in validFileNames }
        val retainedGeoTags = transfer.matchedGeoTags.filterKeys { it in validFileNames }
        val retainedSelection = transfer.selectedImages.filterTo(linkedSetOf()) { it in validPaths }
        val retainedUnavailable = transfer.previewUnavailable.filterTo(linkedSetOf()) { it in validFileNames }
        val selectedImage = transfer.selectedImage?.let { selected ->
            images.firstOrNull { it.fullPath == selected.fullPath }
        }
        val newestExistingThumbnail = images.firstOrNull()?.let { image ->
            retainedThumbnails[image.fileName]
        }
        val retainedIsSelectionMode = transfer.isSelectionMode && retainedSelection.isNotEmpty()
        updateUiState { state -> state.copy(
            transferState = state.transferState.copy(
                images = images,
                thumbnails = retainedThumbnails,
                matchedGeoTags = retainedGeoTags,
                previewUnavailable = retainedUnavailable,
                selectedImage = selectedImage,
                selectedImages = retainedSelection,
                isSelectionMode = retainedIsSelectionMode,
                isLoading = isLoading,
                errorMessage = errorMessage,
                sourceCameraSsid = sourceCameraSsid,
                sourceKind = sourceKind,
                sourceLabel = sourceLabel,
                supportsDelete = supportsDelete,
                usbObjectHandlesByPath = usbObjectHandlesByPath,
                usbAvailableStorageIds = usbAvailableStorageIds,
                selectedUsbStorageIds = selectedUsbStorageIds,
            ),
            lastCaptureThumbnail = newestExistingThumbnail ?: state.lastCaptureThumbnail,
        ) }
    }

    private fun invalidateTransferLoads() {
        transferLoadGeneration.incrementAndGet()
        imageListJob?.cancel()
        imageListJob = null
        thumbnailJob?.cancel()
        thumbnailJob = null
        detailPreviewJob?.cancel()
        detailPreviewJob = null
    }

    private fun prepareOmCaptureUsbForControlMode() {
        invalidateTransferLoads()
        usbPropertySyncJob?.cancel()
        usbModeDialRefreshJob?.cancel()
        usbLiveViewStartupRefreshJob?.cancel()
        pendingUsbPropertyRefreshCodes.clear()
        omCaptureUsbManager.requestSessionDomain(UsbSessionDomain.Control, "viewmodel-control-mode")
    }

    private fun prepareOmCaptureUsbForTransferMode() {
        thumbnailJob?.cancel()
        thumbnailJob = null
        detailPreviewJob?.cancel()
        detailPreviewJob = null
        usbPropertySyncJob?.cancel()
        usbModeDialRefreshJob?.cancel()
        usbLiveViewStartupRefreshJob?.cancel()
        pendingUsbPropertyRefreshCodes.clear()
        if (omCaptureUsbManager.isLiveViewActive || _uiState.value.remoteRuntime.liveViewActive) {
            setUsbLiveViewDesired(false, "usb-transfer-mode")
            stopLiveViewInternal()
            updateUiState { current -> current.copy(
                remoteRuntime = current.remoteRuntime.copy(
                    liveViewActive = false,
                    focusHeld = false,
                    activePicker = ActivePropertyPicker.None,
                ),
            ) }
        }
        omCaptureUsbManager.requestSessionDomain(UsbSessionDomain.Transfer, "viewmodel-transfer-mode")
    }

    private suspend fun refreshTransferImagesAfterDelete(
        deletedPaths: Set<String>,
        errorMessage: String? = null,
    ) {
        if (deletedPaths.isEmpty() || !canSendRemoteCommand()) {
            return
        }
        val slotResult = ensureSelectedPlayTargetSlotApplied()
        if (slotResult.isFailure) {
            updateUiState { current -> current.copy(
                transferState = current.transferState.copy(
                    isLoading = false,
                    errorMessage = errorMessage ?: (slotResult.exceptionOrNull()?.message ?: "Failed to switch photo source"),
                ),
            ) }
            return
        }
        val imageResult = repository.getImageList("/DCIM")
        if (imageResult.isSuccess) {
            val images = imageResult.getOrThrow()
                .filterNot { it.fullPath in deletedPaths }
                .sortedByDescending { "${it.captureDateKey}_${it.fileName}" }
            applyTransferImageList(
                images = images,
                isLoading = false,
                errorMessage = errorMessage,
                sourceCameraSsid = _uiState.value.selectedCameraSsid,
                sourceKind = TransferSourceKind.WifiCamera,
                sourceLabel = wifiTransferSourceLabel(
                    _uiState.value.selectedCardSlotSource,
                    _uiState.value.selectedCameraSsid,
                ),
                supportsDelete = true,
                usbObjectHandlesByPath = emptyMap(),
                usbAvailableStorageIds = emptyList(),
                selectedUsbStorageIds = null,
            )
            loadThumbnails(images)
            return
        }
        D.err("TRANSFER", "Delete refresh failed", imageResult.exceptionOrNull())
        updateUiState { current -> current.copy(
            transferState = current.transferState.copy(
                isLoading = false,
                errorMessage = errorMessage,
            ),
        ) }
    }

    private suspend fun loadDownloadedFileNames(): Set<String> = withContext(Dispatchers.IO) {
        val downloaded = linkedSetOf<String>()
        val resolver = getApplication<Application>().contentResolver
        val projection = arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val saveLocation = _uiState.value.autoImportConfig.saveLocation.ifBlank { "Pictures/db link" }
        val basePath = if (saveLocation.startsWith(Environment.DIRECTORY_PICTURES)) {
            saveLocation
        } else {
            "${Environment.DIRECTORY_PICTURES}/${saveLocation.removePrefix("Pictures/")}"
        }
        val selectionArgs = arrayOf("$basePath%")
        listOf(
            android.provider.MediaStore.Images.Media.getContentUri(
                MEDIA_STORE_PRIMARY_VOLUME,
            ),
            android.provider.MediaStore.Video.Media.getContentUri(
                MEDIA_STORE_PRIMARY_VOLUME,
            ),
            android.provider.MediaStore.Files.getContentUri(
                MEDIA_STORE_PRIMARY_VOLUME,
            ),
        ).forEach { collection ->
            resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    downloaded.add(cursor.getString(nameCol))
                }
            }
        }
        downloaded
    }

    fun selectImage(image: CameraImage?) {
        val currentTransfer = _uiState.value.transferState
        if (image != null && currentTransfer.sourceKind == TransferSourceKind.OmCaptureUsb) {
            usbSavedMediaByTransferPath[image.fullPath]?.let { savedMedia ->
                val items = currentUsbSavedMediaPreviewItems()
                val initialIndex = items.indexOfFirst { it.uriString == savedMedia.uriString }
                    .takeIf { it >= 0 } ?: 0
                openSavedMediaPreview(savedMedia, items = items, initialIndex = initialIndex)
                return
            }
        }
        val currentSelectionPath = currentTransfer.selectedImage?.fullPath
        val nextSelectionPath = image?.fullPath
        if (
            currentSelectionPath == nextSelectionPath &&
            !currentTransfer.isDownloading &&
            currentTransfer.downloadProgress.isEmpty()
        ) {
            return
        }
        updateUiState { it.copy(
            transferState = currentTransfer.copy(
                selectedImage = image,
                selectedSavedMedia = null,
                selectedSavedMediaBitmap = null,
                selectedSavedMediaLoading = false,
                selectedSavedMediaItems = emptyList(),
                selectedSavedMediaIndex = 0,
                selectedSavedMediaBitmaps = emptyMap(),
            ),
        ) }
        detailPreviewJob?.cancel()
        if (image != null) {
            preloadSelectedImagePreview(image)
        }
    }

    fun closeSavedMediaPreview() {
        detailPreviewJob?.cancel()
        updateUiState { current -> current.copy(
            transferState = current.transferState.copy(
                selectedSavedMedia = null,
                selectedSavedMediaBitmap = null,
                selectedSavedMediaLoading = false,
                selectedSavedMediaItems = emptyList(),
                selectedSavedMediaIndex = 0,
                selectedSavedMediaBitmaps = emptyMap(),
            ),
        ) }
    }

    fun openLastCapturedPreview() {
        val savedMedia = _uiState.value.omCaptureUsb.lastSavedMedia
            ?: _uiState.value.omCaptureUsb.lastImportedFileName?.let { fileName ->
                usbSavedMediaByFileName[normalizeSavedMediaKey(fileName)]
            }
        if (savedMedia != null) {
            applyUsbTransferItems(usbLibraryCache)
            val items = currentUsbSavedMediaPreviewItems()
            val initialIndex = items.indexOfFirst { it.uriString == savedMedia.uriString }
                .takeIf { it >= 0 } ?: 0
            openSavedMediaPreview(savedMedia, items = items, initialIndex = initialIndex)
            return
        }
        val targetFileName = _uiState.value.omCaptureUsb.lastImportedFileName
        val images = _uiState.value.transferState.images
        if (images.isNotEmpty()) {
            applyPendingLastCapturePreview(
                images.sortedByDescending { "${it.captureDateKey}_${it.fileName}" },
                force = true,
                targetFileName = targetFileName,
            )
            return
        }
        pendingLastCapturePreviewOpen = true
        pendingLastCapturePreviewFileName = targetFileName
        loadCameraImages()
    }

    private fun openSavedMediaPreview(
        savedMedia: OmCaptureUsbSavedMedia,
        items: List<OmCaptureUsbSavedMedia> = currentUsbSavedMediaPreviewItems(),
        initialIndex: Int = items.indexOfFirst { it.uriString == savedMedia.uriString }.takeIf { it >= 0 } ?: 0,
        placeholder: Bitmap? = _uiState.value.lastCaptureThumbnail,
    ) {
        detailPreviewJob?.cancel()
        val previewItems = items.ifEmpty { listOf(savedMedia) }
        val currentTransfer = _uiState.value.transferState
        val scopedCache = currentTransfer.selectedSavedMediaBitmaps
            .filterKeys { key -> previewItems.any { it.uriString == key } }
            .toMutableMap()
        val cachedBitmap = scopedCache[savedMedia.uriString]
        val previewBitmap = cachedBitmap ?: placeholder
        if (previewBitmap != null) {
            scopedCache[savedMedia.uriString] = previewBitmap
        }
        updateUiState { current -> current.copy(
            transferState = current.transferState.copy(
                selectedImage = null,
                selectedSavedMedia = savedMedia,
                selectedSavedMediaBitmap = previewBitmap,
                selectedSavedMediaLoading = cachedBitmap == null,
                selectedSavedMediaItems = previewItems,
                selectedSavedMediaIndex = initialIndex.coerceIn(0, previewItems.lastIndex),
                selectedSavedMediaBitmaps = scopedCache,
                isSelectionMode = false,
                typeFilter = dev.dblink.feature.transfer.ImageTypeFilter.ALL,
            ),
        ) }
        detailPreviewJob = viewModelScope.launch {
            val bitmap = decodeSavedMediaPreview(savedMedia)
            updateUiState { current ->
                if (current.transferState.selectedSavedMedia?.uriString != savedMedia.uriString) {
                    current
                } else {
                    val updatedSavedMediaBitmaps = current.transferState.selectedSavedMediaBitmaps.toMutableMap()
                    if (bitmap != null) {
                        updatedSavedMediaBitmaps[savedMedia.uriString] = bitmap
                    }
                    current.copy(
                        transferState = current.transferState.copy(
                            selectedSavedMediaBitmap = bitmap ?: current.transferState.selectedSavedMediaBitmap,
                            selectedSavedMediaLoading = false,
                            selectedSavedMediaBitmaps = updatedSavedMediaBitmaps,
                        ),
                    )
                }
            }
        }
    }

    fun selectSavedMediaPreviewPage(savedMedia: OmCaptureUsbSavedMedia, index: Int) {
        val current = _uiState.value.transferState
        val currentItems = current.selectedSavedMediaItems.ifEmpty { listOf(savedMedia) }
        val normalizedIndex = index.coerceIn(0, currentItems.lastIndex)
        if (
            current.selectedSavedMedia?.uriString == savedMedia.uriString &&
            current.selectedSavedMediaIndex == normalizedIndex
        ) {
            return
        }
        openSavedMediaPreview(
            savedMedia = savedMedia,
            items = currentItems,
            initialIndex = normalizedIndex,
            placeholder = current.selectedSavedMediaBitmaps[savedMedia.uriString]
                ?: current.selectedSavedMediaBitmap?.takeIf {
                    current.selectedSavedMedia?.uriString == savedMedia.uriString
                },
        )
    }

    private fun applyPendingLastCapturePreview(
        images: List<CameraImage>,
        force: Boolean = false,
        targetFileName: String? = pendingLastCapturePreviewFileName,
    ) {
        if (!force && !pendingLastCapturePreviewOpen) {
            return
        }
        val target = targetFileName?.let { fileName ->
            images.firstOrNull { it.fileName.equals(fileName, ignoreCase = true) }
        } ?: images.firstOrNull() ?: return
        pendingLastCapturePreviewOpen = false
        pendingLastCapturePreviewFileName = null
        updateUiState { current -> current.copy(
            transferState = current.transferState.copy(
                selectedImage = target,
                selectedSavedMedia = null,
                selectedSavedMediaBitmap = null,
                selectedSavedMediaLoading = false,
                selectedSavedMediaItems = emptyList(),
                selectedSavedMediaIndex = 0,
                selectedSavedMediaBitmaps = emptyMap(),
                isSelectionMode = false,
                typeFilter = dev.dblink.feature.transfer.ImageTypeFilter.ALL,
            ),
        ) }
        preloadSelectedImagePreview(target)
    }

    private fun preloadSelectedImagePreview(image: CameraImage) {
        detailPreviewJob = viewModelScope.launch {
            if (shouldPauseWifiLibraryMediaRequests()) {
                return@launch
            }
            if (_uiState.value.transferState.sourceKind == TransferSourceKind.OmCaptureUsb) {
                val localSavedMedia = resolveSavedMediaForFileName(image.fileName)
                if (localSavedMedia != null) {
                    val localBitmap = decodeSavedMediaPreview(localSavedMedia)
                    if (localBitmap != null) {
                        previewThumbnailUpgrades += image.fileName
                        clearPreviewUnavailable(image.fileName)
                        updateTransferThumbnails(listOf(image.fileName to localBitmap), overwriteExisting = true)
                        return@launch
                    }
                }
                val handle = resolveUsbTransferHandle(image)
                if (handle == null) {
                    markPreviewUnavailable(image.fileName)
                    return@launch
                }
                val previewBytes = omCaptureUsbManager.loadLibraryPreview(handle).getOrNull()
                if (previewBytes == null) {
                    markPreviewUnavailable(image.fileName)
                    return@launch
                }
                val bitmap = withContext(Dispatchers.Default) {
                    decodeTransferPreviewBitmap(previewBytes)
                }
                if (bitmap == null) {
                    markPreviewUnavailable(image.fileName)
                    return@launch
                }
                previewThumbnailUpgrades += image.fileName
                clearPreviewUnavailable(image.fileName)
                updateTransferThumbnails(listOf(image.fileName to bitmap), overwriteExisting = true)
                return@launch
            }
            if (!canSendRemoteCommand()) {
                return@launch
            }
            if (ensureSelectedPlayTargetSlotApplied().isFailure) {
                markPreviewUnavailable(image.fileName)
                return@launch
            }
            if (_uiState.value.libraryCompatibilityMode == LibraryCompatibilityMode.Slow) {
                waitForWifiMediaRequestCooldown()
            }
            var bitmap = decodeSelectedPreviewCandidate(
                image = image,
                label = "resizeimg_witherr(1920)",
            ) {
                withTimeoutOrNull(2200L) {
                    repository.getResizedImageWithErr(image, width = 1920).getOrNull()
                }
            }
            if (bitmap == null) {
                bitmap = decodeSelectedPreviewCandidate(
                    image = image,
                    label = "resizeimg(1920)",
                ) {
                    withTimeoutOrNull(2200L) {
                        repository.getResizedImage(image, width = 1920).getOrNull()
                    }
                }
            }
            if (bitmap == null) {
                bitmap = decodeSelectedPreviewCandidate(
                    image = image,
                    label = "screennail",
                ) {
                    withTimeoutOrNull(1800L) {
                        loadWifiPreviewThumbnailBytes(image)
                    }
                }
            }
            if (bitmap == null) {
                bitmap = decodeSelectedPreviewCandidate(
                    image = image,
                    label = "thumbnail",
                ) {
                    withTimeoutOrNull(1800L) {
                        loadWifiBasicThumbnailBytes(image)
                    }
                }
            }
            if (bitmap == null && image.isRaw) {
                val fullImageBytes = withContext(Dispatchers.IO) {
                    repository.getFullImage(image).getOrNull()
                }
                if (fullImageBytes != null) {
                    updatePreviewDetails(image, fullImageBytes)
                    bitmap = withContext(Dispatchers.Default) {
                        decodeTransferPreviewBitmap(fullImageBytes)
                    }
                    if (bitmap == null) {
                        D.transfer("Selected preview step full-raw was not decodable for ${image.fileName}")
                    }
                }
            }
            if (bitmap == null) {
                markPreviewUnavailable(image.fileName)
                return@launch
            }

            previewThumbnailUpgrades += image.fileName
            clearPreviewUnavailable(image.fileName)
            updateTransferThumbnails(listOf(image.fileName to bitmap), overwriteExisting = true)
        }
    }

    private suspend fun decodeSelectedPreviewCandidate(
        image: CameraImage,
        label: String,
        loadBytes: suspend () -> ByteArray?,
    ): Bitmap? {
        val previewBytes = loadBytes()
        if (previewBytes == null) {
            D.transfer("Selected preview step $label returned no bytes for ${image.fileName}")
            return null
        }
        updatePreviewDetails(image, previewBytes)
        val decoded = withContext(Dispatchers.Default) {
            decodeTransferPreviewBitmap(previewBytes)
        }
        if (decoded == null) {
            D.transfer("Selected preview step $label was not decodable for ${image.fileName}; trying next fallback")
        }
        return decoded
    }

    /**
     * Auto-import: loads image list from camera, filters by configured format,
     * skips already-downloaded files, and starts background download.
     * Called automatically on connect when importTiming == "on_connect".
     */
    private suspend fun triggerAutoImport() {
        val config = _uiState.value.autoImportConfig
        D.transfer("triggerAutoImport: format=${config.fileFormat}, timing=${config.importTiming}")

        if (ensureSelectedPlayTargetSlotApplied().isFailure) {
            D.transfer("triggerAutoImport: skipped because play target slot could not be applied")
            return
        }
        val images = repository.getImageList("/DCIM").getOrNull()
        if (images.isNullOrEmpty()) {
            D.transfer("triggerAutoImport: no images found on camera")
            return
        }
        startAutoImportFromImages(images)
    }

    fun downloadImage(image: CameraImage) {
        D.transfer("downloadImage: ${image.fileName}")
        if (_uiState.value.transferState.sourceKind == TransferSourceKind.OmCaptureUsb) {
            downloadOmCaptureUsbLibraryImage(image)
            return
        }
        prepareWifiTransferPipeline()
        updateUiState { current -> current.copy(
            transferState = current.transferState.copy(
                isDownloading = true,
                downloadProgress = "Preparing background download...",
                backgroundTransferRunning = true,
                backgroundTransferProgress = "Preparing background download...",
                backgroundTransferTotal = 1,
                backgroundTransferCurrent = 0,
            ),
        ) }
        dev.dblink.core.download.ImageDownloadService.startDownload(
            context = getApplication(),
            images = listOf(image),
            saveLocation = _uiState.value.autoImportConfig.saveLocation,
            playTargetSlot = _uiState.value.selectedCardSlotSource,
        )
    }

    private fun downloadOmCaptureUsbLibraryImage(image: CameraImage) {
        val handle = resolveUsbTransferHandle(image)
        if (handle == null) {
            updateUiState { current -> current.copy(
                transferState = current.transferState.copy(
                    isDownloading = false,
                    downloadProgress = "Import failed",
                    errorMessage = "The selected OM camera file could not be resolved.",
                ),
            ) }
            return
        }
        updateUiState { current -> current.copy(
            transferState = current.transferState.copy(
                isDownloading = true,
                downloadProgress = "Preparing USB import...",
                backgroundTransferRunning = true,
                backgroundTransferProgress = "Preparing USB import...",
                backgroundTransferTotal = 1,
                backgroundTransferCurrent = 0,
                errorMessage = null,
            ),
        ) }
        dev.dblink.core.download.ImageDownloadService.startUsbImport(
            context = getApplication(),
            items = listOf(
                dev.dblink.core.download.UsbImportRequest(
                    handle = handle,
                    fileName = image.fileName,
                ),
            ),
            saveLocation = _uiState.value.autoImportConfig.saveLocation,
        )
    }

    fun toggleImageSelection(image: CameraImage) {
        val selected = _uiState.value.transferState.selectedImages
        val updated = if (image.fullPath in selected) {
            selected - image.fullPath
        } else {
            selected + image.fullPath
        }
        updateUiState { state -> state.copy(
            transferState = state.transferState.copy(
                selectedImages = updated,
                isSelectionMode = updated.isNotEmpty(),
            ),
        ) }
    }

    fun toggleDateSelection(dateKey: String) {
        val images = _uiState.value.transferState.images
        val filtered = applyTypeFilter(images)
        val dateImages = filtered.filter { it.captureDateKey == dateKey }
        val datePaths = dateImages.map { it.fullPath }.toSet()

        val selected = _uiState.value.transferState.selectedImages
        val allSelected = datePaths.all { it in selected }
        val updated = if (allSelected) {
            selected - datePaths
        } else {
            selected + datePaths
        }
        updateUiState { state -> state.copy(
            transferState = state.transferState.copy(
                selectedImages = updated,
                isSelectionMode = updated.isNotEmpty(),
            ),
        ) }
    }

    fun selectAllImages() {
        val allPaths = _uiState.value.transferState.images.map { it.fullPath }.toSet()
        updateUiState { current -> current.copy(
            transferState = current.transferState.copy(
                selectedImages = allPaths,
                isSelectionMode = true,
            ),
        ) }
    }

    fun clearImageSelection() {
        updateUiState { current -> current.copy(
            transferState = current.transferState.copy(
                selectedImages = emptySet(),
                isSelectionMode = false,
            ),
        ) }
    }

    fun setTypeFilter(filter: dev.dblink.feature.transfer.ImageTypeFilter) {
        updateUiState { current -> current.copy(
            transferState = current.transferState.copy(
                typeFilter = filter,
                selectedImages = emptySet(),
                isSelectionMode = false,
            ),
        ) }
    }

    private fun applyTypeFilter(images: List<CameraImage>): List<CameraImage> {
        return when (_uiState.value.transferState.typeFilter) {
            dev.dblink.feature.transfer.ImageTypeFilter.ALL -> images
            dev.dblink.feature.transfer.ImageTypeFilter.JPG -> images.filter { it.isJpeg }
            dev.dblink.feature.transfer.ImageTypeFilter.RAW -> images.filter { it.isRaw }
            dev.dblink.feature.transfer.ImageTypeFilter.VIDEO -> images.filter { it.isMovie }
        }
    }

    fun downloadSelectedImages() {
        val selected = _uiState.value.transferState.selectedImages
        val images = _uiState.value.transferState.images.filter { it.fullPath in selected }
        if (images.isEmpty()) return
        if (_uiState.value.transferState.sourceKind == TransferSourceKind.OmCaptureUsb) {
            downloadSelectedOmCaptureUsbImages(images)
            return
        }
        prepareWifiTransferPipeline()
        updateUiState { current -> current.copy(
            transferState = current.transferState.copy(
                isDownloading = true,
                batchDownloadTotal = images.size,
                batchDownloadCurrent = 0,
                backgroundTransferRunning = true,
                backgroundTransferProgress = "Preparing background download...",
                backgroundTransferTotal = images.size,
                backgroundTransferCurrent = 0,
                selectedImages = emptySet(),
                isSelectionMode = false,
                downloadProgress = "Preparing background download...",
            ),
        ) }
        val context = getApplication<Application>()
        dev.dblink.core.download.ImageDownloadService.startDownload(
            context = context,
            images = images,
            saveLocation = _uiState.value.autoImportConfig.saveLocation,
            playTargetSlot = _uiState.value.selectedCardSlotSource,
        )
    }

    private fun downloadSelectedOmCaptureUsbImages(images: List<CameraImage>) {
        val requests = images.mapNotNull { image ->
            resolveUsbTransferHandle(image)?.let { handle ->
                dev.dblink.core.download.UsbImportRequest(
                    handle = handle,
                    fileName = image.fileName,
                )
            }
        }
        if (requests.isEmpty()) {
            updateUiState { current -> current.copy(
                transferState = current.transferState.copy(
                    isDownloading = false,
                    downloadProgress = "Import failed",
                    errorMessage = "The selected OM camera files could not be resolved.",
                ),
            ) }
            return
        }
        updateUiState { current -> current.copy(
            transferState = current.transferState.copy(
                isDownloading = true,
                batchDownloadTotal = requests.size,
                batchDownloadCurrent = 0,
                backgroundTransferRunning = true,
                backgroundTransferProgress = "Preparing USB import...",
                backgroundTransferTotal = requests.size,
                backgroundTransferCurrent = 0,
                selectedImages = emptySet(),
                isSelectionMode = false,
                downloadProgress = "Preparing USB import...",
                errorMessage = null,
            ),
        ) }
        dev.dblink.core.download.ImageDownloadService.startUsbImport(
            context = getApplication(),
            items = requests,
            saveLocation = _uiState.value.autoImportConfig.saveLocation,
        )
    }

    fun cancelBackgroundDownload() {
        val currentTransfer = _uiState.value.transferState
        if (!currentTransfer.backgroundTransferRunning) {
            return
        }
        updateUiState { current -> current.copy(
            transferState = current.transferState.copy(
                downloadProgress = "Canceling...",
                backgroundTransferProgress = "Canceling...",
            ),
        ) }
        dev.dblink.core.download.ImageDownloadService.cancelTransfer(getApplication())
    }

    private fun shouldPauseWifiLibraryMediaRequests(): Boolean {
        val transfer = _uiState.value.transferState
        return transfer.sourceKind == TransferSourceKind.WifiCamera && transfer.backgroundTransferRunning
    }

    private fun resetWifiThumbnailFallbackState() {
        previewThumbnailUpgrades.clear()
        wifiThumbnail404Paths.clear()
        wifiPreview404Paths.clear()
        wifiResizedThumbnail404Paths.clear()
        wifiResizedThumbnailCooldownUntilMs = 0L
    }

    private fun prepareWifiTransferPipeline() {
        if (_uiState.value.transferState.sourceKind != TransferSourceKind.WifiCamera) {
            return
        }
        pauseSessionKeepAliveForWifiTransfer()
        thumbnailJob?.cancel()
        thumbnailJob = null
        detailPreviewJob?.cancel()
        detailPreviewJob = null
        propertyApplyJob?.cancel()
        propertyApplyJob = null
        propertyLoadJob?.cancel()
        propertyLoadJob = null
        propertyRefreshJob?.cancel()
        propertyRefreshJob = null
    }

    fun deleteImage(image: CameraImage) {
        if (_uiState.value.transferState.sourceKind == TransferSourceKind.OmCaptureUsb) {
            updateUiState { current -> current.copy(
                transferState = current.transferState.copy(
                    errorMessage = "Delete is unavailable in USB import mode.",
                ),
            ) }
            return
        }
        if (!canSendRemoteCommand()) {
            updateUiState { current -> current.copy(
                transferState = current.transferState.copy(
                    errorMessage = "Reconnect to the camera before deleting photos",
                ),
            ) }
            return
        }
        updateUiState { current -> current.copy(
            transferState = current.transferState.copy(
                isDownloading = true,
                downloadProgress = "Deleting...",
                errorMessage = null,
            ),
        ) }
        viewModelScope.launch {
            val slotResult = ensureSelectedPlayTargetSlotApplied()
            if (slotResult.isFailure) {
                updateUiState { current -> current.copy(
                    transferState = current.transferState.copy(
                        isDownloading = false,
                        downloadProgress = "Delete failed",
                        errorMessage = slotResult.exceptionOrNull()?.message ?: "Failed to switch photo source",
                    ),
                ) }
                return@launch
            }
            repository.deleteImage(image)
                .onSuccess {
                    val deletedPaths = setOf(image.fullPath)
                    removeImagesFromTransferState(deletedPaths)
                    updateUiState { current -> current.copy(
                        transferState = current.transferState.copy(
                            isDownloading = false,
                            downloadProgress = "Deleted",
                            errorMessage = null,
                        ),
                    ) }
                    refreshTransferImagesAfterDelete(deletedPaths)
                }
                .onFailure { throwable ->
                    updateUiState { current -> current.copy(
                        transferState = current.transferState.copy(
                            isDownloading = false,
                            downloadProgress = "Delete failed",
                            errorMessage = throwable.message?.let { "Delete failed: $it" } ?: "Delete failed",
                        ),
                    ) }
                }
        }
    }

    fun deleteSelectedImages() {
        val selected = _uiState.value.transferState.selectedImages
        val images = _uiState.value.transferState.images.filter { it.fullPath in selected }
        if (images.isEmpty()) return
        if (_uiState.value.transferState.sourceKind == TransferSourceKind.OmCaptureUsb) {
            updateUiState { current -> current.copy(
                transferState = current.transferState.copy(
                    errorMessage = "Delete is unavailable in USB import mode.",
                ),
            ) }
            return
        }
        if (!canSendRemoteCommand()) {
            updateUiState { current -> current.copy(
                transferState = current.transferState.copy(
                    errorMessage = "Reconnect to the camera before deleting photos",
                ),
            ) }
            return
        }
        updateUiState { current -> current.copy(
            transferState = current.transferState.copy(
                isDownloading = true,
                batchDownloadTotal = images.size,
                batchDownloadCurrent = 0,
                downloadProgress = "Deleting ${images.size} items...",
                errorMessage = null,
            ),
        ) }
        viewModelScope.launch {
            val slotResult = ensureSelectedPlayTargetSlotApplied()
            if (slotResult.isFailure) {
                updateUiState { current -> current.copy(
                    transferState = current.transferState.copy(
                        isDownloading = false,
                        downloadProgress = "Delete failed",
                        errorMessage = slotResult.exceptionOrNull()?.message ?: "Failed to switch photo source",
                    ),
                ) }
                return@launch
            }
            val deletedPaths = linkedSetOf<String>()
            var failures = 0
            images.forEachIndexed { index, image ->
                updateUiState { current -> current.copy(
                    transferState = current.transferState.copy(
                        batchDownloadCurrent = index,
                        downloadProgress = "Deleting ${index + 1}/${images.size}...",
                    ),
                ) }
                val result = repository.deleteImage(image)
                if (result.isSuccess) {
                    deletedPaths += image.fullPath
                } else {
                    failures++
                }
            }
            if (deletedPaths.isNotEmpty()) {
                removeImagesFromTransferState(deletedPaths)
            }
            updateUiState { current -> current.copy(
                transferState = current.transferState.copy(
                    isDownloading = false,
                    batchDownloadCurrent = images.size,
                    downloadProgress = if (failures == 0) {
                        "Deleted ${deletedPaths.size} items"
                    } else {
                        "Deleted ${deletedPaths.size}, failed ${failures}"
                    },
                    errorMessage = if (failures == 0) null else "Delete failed for $failures item(s)",
                ),
            ) }
            if (deletedPaths.isNotEmpty()) {
                refreshTransferImagesAfterDelete(
                    deletedPaths = deletedPaths,
                    errorMessage = if (failures == 0) null else "Delete failed for $failures item(s)",
                )
            }
        }
    }

    private fun removeImagesFromTransferState(deletedPaths: Set<String>) {
        if (deletedPaths.isEmpty()) return
        val current = _uiState.value.transferState
        val remainingImages = current.images.filterNot { it.fullPath in deletedPaths }
        val remainingSelected = current.selectedImages - deletedPaths
        val remainingFileNames = remainingImages.mapTo(linkedSetOf()) { it.fileName }
        previewThumbnailUpgrades.retainAll(remainingFileNames)
        val remainingThumbnails = current.thumbnails.filterKeys { it in remainingFileNames }
        val remainingGeoTags = current.matchedGeoTags.filterKeys { it in remainingFileNames }
        val remainingPreviewUnavailable = current.previewUnavailable.filterTo(linkedSetOf()) { it in remainingFileNames }
        val currentSelectedPath = current.selectedImage?.fullPath
        val nextSelectedImage = when {
            currentSelectedPath == null -> null
            currentSelectedPath !in deletedPaths -> remainingImages.firstOrNull { it.fullPath == currentSelectedPath }
            else -> remainingImages.firstOrNull()
        }
        updateUiState { it.copy(
            transferState = current.copy(
                images = remainingImages,
                thumbnails = remainingThumbnails,
                matchedGeoTags = remainingGeoTags,
                previewUnavailable = remainingPreviewUnavailable,
                selectedImage = nextSelectedImage,
                selectedImages = remainingSelected,
                isSelectionMode = remainingSelected.isNotEmpty(),
            ),
        ) }
    }

    private fun saveToGallery(fileName: String, data: ByteArray, dateFolder: String = ""): Boolean {
        return saveToGalleryDetailed(fileName, data, dateFolder) != null
    }

    private suspend fun saveStreamToGallery(
        fileName: String,
        dateFolder: String = "",
        writeData: suspend (java.io.OutputStream) -> Unit,
    ): Boolean {
        return saveStreamToGalleryDetailed(fileName, dateFolder, writeData) != null
    }

    private fun saveToGalleryDetailed(
        fileName: String,
        data: ByteArray,
        dateFolder: String = "",
    ): OmCaptureUsbSavedMedia? {
        return kotlinx.coroutines.runBlocking {
            saveStreamToGalleryDetailed(fileName, dateFolder) { outputStream ->
                outputStream.write(data)
                outputStream.flush()
            }
        }
    }

    private suspend fun saveStreamToGalleryDetailed(
        fileName: String,
        dateFolder: String = "",
        writeData: suspend (java.io.OutputStream) -> Unit,
    ): OmCaptureUsbSavedMedia? {
        return try {
            val context = getApplication<Application>()
            val resolver = context.contentResolver
            val upperName = fileName.uppercase(Locale.US)
            val isJpeg = upperName.endsWith(".JPG") || upperName.endsWith(".JPEG")
            val isRawImage = upperName.endsWith(".ORF")
            val isVideo = upperName.endsWith(".MOV") || upperName.endsWith(".MP4")
            val mimeType = when {
                isJpeg -> "image/jpeg"
                isRawImage -> "image/x-olympus-orf"
                upperName.endsWith(".MOV") -> "video/quicktime"
                upperName.endsWith(".MP4") -> "video/mp4"
                else -> "application/octet-stream"
            }

            val saveLocation = _uiState.value.autoImportConfig.saveLocation.ifBlank { "Pictures/db link" }
            val basePath = if (saveLocation.startsWith(Environment.DIRECTORY_PICTURES)) {
                saveLocation
            } else {
                "${Environment.DIRECTORY_PICTURES}/${saveLocation.removePrefix("Pictures/")}"
            }
            val relativePath = if (dateFolder.isNotEmpty()) "$basePath/$dateFolder" else basePath
            val normalizedRelativePath = relativePath.trimEnd('/') + "/"
            val absolutePath = "/storage/emulated/0/$normalizedRelativePath$fileName"

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, normalizedRelativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                if (isVideo) {
                    put(MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
                }
            }

            val collection = if (isVideo) {
                MediaStore.Video.Media.getContentUri(MEDIA_STORE_PRIMARY_VOLUME)
            } else {
                MediaStore.Images.Media.getContentUri(MEDIA_STORE_PRIMARY_VOLUME)
            }

            D.transfer(
                "saveToGalleryDetailed: file=$fileName mime=$mimeType " +
                    "relativePath=$normalizedRelativePath collection=$collection",
            )
            val uri = resolver.insert(collection, contentValues) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    writeData(outputStream)
                    outputStream.flush()
                } ?: error("Content resolver returned a null output stream for $fileName")

                if (isJpeg) {
                    writeGeoTagExif(resolver, uri, fileName)
                }

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            } catch (writeFailure: Exception) {
                resolver.delete(uri, null, null)
                throw writeFailure
            }

            runCatching {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(absolutePath),
                    arrayOf(mimeType),
                    null,
                )
            }.onFailure { throwable ->
                D.err("TRANSFER", "MediaScanner scan failed for $fileName", throwable)
            }

            D.transfer("saveToGalleryDetailed: saved file=$fileName uri=$uri path=$absolutePath")

            OmCaptureUsbSavedMedia(
                uriString = uri.toString(),
                relativePath = normalizedRelativePath,
                absolutePath = absolutePath,
                displayName = fileName,
                mimeType = mimeType,
                dateFolder = dateFolder,
                isRaw = isRawImage,
            )
        } catch (e: Exception) {
            D.err("TRANSFER", "saveToGallery failed for $fileName", e)
            null
        }
    }

    /**
     * Writes GPS EXIF tags to a saved image if a matched geotag exists.
     * Respects the geotag_include_altitude setting for altitude data.
     */
    private fun writeGeoTagExif(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        fileName: String,
    ) {
        val geoTag = _uiState.value.transferState.matchedGeoTags[fileName] ?: return
        try {
            val fd = resolver.openFileDescriptor(uri, "rw") ?: return
            fd.use {
                val exif = ExifInterface(it.fileDescriptor)

                // Check if image already has embedded GPS — don't overwrite
                val existingLatLong = exif.latLong
                if (existingLatLong != null && existingLatLong[0] != 0.0) {
                    D.transfer("$fileName already has GPS EXIF, skipping geotag write")
                    return
                }

                exif.setLatLong(geoTag.latitude, geoTag.longitude)
                D.transfer("Writing GPS EXIF to $fileName: lat=${geoTag.latitude}, lon=${geoTag.longitude}")

                // Write altitude only if geotag_include_altitude is enabled
                val writeAltitude = _uiState.value.geotagConfig.writeAltitude
                if (writeAltitude) {
                    // Find altitude from the matched sample
                    val matchedSample = _uiState.value.geoTagging.samples.minByOrNull {
                        abs(it.capturedAtMillis - geoTag.matchedSampleAtMillis)
                    }
                    matchedSample?.altitude?.let { altitude ->
                        exif.setAltitude(altitude)
                        D.transfer("Writing altitude to $fileName: ${altitude}m")
                    }
                } else {
                    D.transfer("geotag_include_altitude disabled — skipping altitude for $fileName")
                }

                exif.saveAttributes()
            }
        } catch (e: Exception) {
            D.err("TRANSFER", "Failed to write GPS EXIF to $fileName", e)
        }
    }

    /**
     * Post-process batch-downloaded JPEGs to write geotag EXIF data.
     * Called after ImageDownloadService completes, since the service itself
     * has no access to ViewModel geotag state.
     */
    private suspend fun postProcessBatchGeoTags() {
        val matchedGeoTags = _uiState.value.transferState.matchedGeoTags
        if (matchedGeoTags.isEmpty()) return

        val context = getApplication<Application>()
        val resolver = context.contentResolver

        // Query recently saved JPEGs in the configured save location
        val saveLocation = _uiState.value.autoImportConfig.saveLocation.ifBlank { "Pictures/db link" }
        val basePath = if (saveLocation.startsWith(Environment.DIRECTORY_PICTURES)) {
            saveLocation
        } else {
            "${Environment.DIRECTORY_PICTURES}/${saveLocation.removePrefix("Pictures/")}"
        }

        withContext(Dispatchers.IO) {
            for ((fileName, _) in matchedGeoTags) {
                if (!fileName.uppercase().let { it.endsWith(".JPG") || it.endsWith(".JPEG") }) continue

                // Find the file in MediaStore by display name and relative path
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf(fileName, "$basePath%")
                val cursor = resolver.query(
                    MediaStore.Images.Media.getContentUri(MEDIA_STORE_PRIMARY_VOLUME),
                    arrayOf(MediaStore.MediaColumns._ID),
                    selection,
                    selectionArgs,
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC",
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        val id = it.getLong(0)
                        val uri = android.content.ContentUris.withAppendedId(
                            MediaStore.Images.Media.getContentUri(MEDIA_STORE_PRIMARY_VOLUME),
                            id,
                        )
                        writeGeoTagExif(resolver, uri, fileName)
                    }
                }
            }
            D.transfer("postProcessBatchGeoTags: processed ${matchedGeoTags.size} matched files")
        }
    }

    fun syncGeoTagNow() {
        updateUiState { current -> current.copy(
            geoTagging = current.geoTagging.copy(statusLabel = "Syncing location..."),
        ) }
        viewModelScope.launch {
            runCatching { geoTagLocationManager.captureCurrentLocation() }
                .onSuccess { sample ->
                    pushGeoTagSample(
                        sample = sample,
                        statusLabel = sample.placeName ?: "Location synced",
                    )
                }
                .onFailure { throwable ->
                    updateUiState { current -> current.copy(
                        geoTagging = current.geoTagging.copy(
                            statusLabel = throwable.message ?: "Location sync failed",
                        ),
                    ) }
                }
        }
    }

    fun captureGeoTagPin() {
        updateUiState { current -> current.copy(
            geoTagging = current.geoTagging.copy(statusLabel = "Saving location..."),
        ) }
        viewModelScope.launch {
            runCatching { geoTagLocationManager.captureCurrentLocation() }
                .onSuccess { sample ->
                    pushGeoTagSample(
                        sample = sample,
                        statusLabel = sample.placeName ?: if (_uiState.value.geoTagging.precisePermissionGranted) "Pin saved" else "Approximate location saved",
                    )
                }
                .onFailure { throwable ->
                    updateUiState { current -> current.copy(
                        geoTagging = current.geoTagging.copy(
                            statusLabel = throwable.message ?: "Could not get location",
                        ),
                    ) }
                }
        }
    }

    fun startGeoTagSession() {
        if (_uiState.value.geoTagging.sessionActive) return
        updateUiState { current -> current.copy(
            geoTagging = current.geoTagging.copy(
                sessionActive = true,
                statusLabel = "Starting background tracking...",
            ),
        ) }
        GeoTagTrackingService.start(getApplication())
    }

    fun stopGeoTagSession() {
        val updatedGeoTagging = _uiState.value.geoTagging.copy(
            sessionActive = false,
            statusLabel = if (_uiState.value.geoTagging.latestSample != null) "Tracking stopped" else "Ready",
        )
        updateUiState { it.copy(geoTagging = updatedGeoTagging) }
        persistGeoTagging(updatedGeoTagging)
        refreshMatchedGeoTags()
        GeoTagTrackingService.stop(getApplication())
        syncGeoTagStatusNotification(updatedGeoTagging)
    }

    fun adjustGeoTagClockOffset(deltaMinutes: Int) {
        val updatedGeoTagging = _uiState.value.geoTagging.copy(
            clockOffsetMinutes = (_uiState.value.geoTagging.clockOffsetMinutes + deltaMinutes).coerceIn(-720, 720),
            statusLabel = "Offset updated",
        )
        updateUiState { it.copy(geoTagging = updatedGeoTagging) }
        persistGeoTagging(updatedGeoTagging)
        refreshMatchedGeoTags()
    }

    private fun observeDownloadProgress() {
        downloadProgressJob?.cancel()
        downloadProgressJob = dev.dblink.core.download.ImageDownloadService.progress
            .onEach { progress ->
                val currentTransfer = _uiState.value.transferState
                if (progress.isRunning && currentTransfer.sourceKind == TransferSourceKind.WifiCamera) {
                    pauseSessionKeepAliveForWifiTransfer()
                }
                val processed = progress.completed + progress.failed
                val etaLabel = formatEtaLabel(progress.etaMillis)
                val nextProgressLabel = when {
                    progress.total == 0 && progress.skipped > 0 -> "${progress.skipped} already saved"
                    progress.total == 0 -> currentTransfer.downloadProgress
                    progress.isRunning -> buildString {
                        append("${processed}/${progress.total}")
                        if (etaLabel.isNotBlank()) {
                            append(" • ")
                            append(etaLabel)
                        }
                    }
                    progress.isCancelled -> if (progress.total > 0) {
                        "Canceled (${processed}/${progress.total})"
                    } else {
                        "Canceled"
                    }
                    progress.failed > 0 && progress.completed == 0 -> buildString {
                        append("${progress.failed}/${progress.total} failed")
                        if (progress.skipped > 0) {
                            append(", ${progress.skipped} already saved")
                        }
                    }
                    progress.failed > 0 -> buildString {
                        append("${progress.completed}/${progress.total} saved, ${progress.failed} failed")
                        if (progress.skipped > 0) {
                            append(", ${progress.skipped} already saved")
                        }
                    }
                    else -> buildString {
                        append("${progress.completed}/${progress.total} saved")
                        if (progress.skipped > 0) {
                            append(", ${progress.skipped} already saved")
                        }
                    }
                }
                if (
                    currentTransfer.isDownloading == progress.isRunning &&
                    currentTransfer.batchDownloadTotal == progress.total &&
                    currentTransfer.batchDownloadCurrent == processed &&
                    currentTransfer.downloadProgress == nextProgressLabel &&
                    currentTransfer.backgroundTransferRunning == progress.isRunning &&
                    currentTransfer.backgroundTransferTotal == progress.total &&
                    currentTransfer.backgroundTransferCurrent == processed &&
                    currentTransfer.backgroundTransferProgress == nextProgressLabel
                ) {
                    return@onEach
                }
                updateUiState { it.copy(
                    transferState = currentTransfer.copy(
                        isDownloading = progress.isRunning,
                        batchDownloadTotal = progress.total,
                        batchDownloadCurrent = processed,
                        downloadProgress = nextProgressLabel,
                        backgroundTransferRunning = progress.isRunning,
                        backgroundTransferTotal = progress.total,
                        backgroundTransferCurrent = processed,
                        backgroundTransferProgress = nextProgressLabel,
                    ),
                ) }
                if (!progress.isRunning) {
                    resumeSessionKeepAliveAfterWifiTransfer()
                }
                if (!progress.isRunning && progress.total > 0 && (currentTransfer.isDownloading || currentTransfer.batchDownloadTotal > 0)) {
                    scanDownloadedFiles()
                    // Post-process: write geotag EXIF to batch-downloaded JPEGs
                    postProcessBatchGeoTags()
                    if (pendingAutoImportMarker != null) {
                        if (progress.completed > 0 && progress.failed == 0) {
                            val markerKey = autoImportMarkerKey(pendingAutoImportMarkerSsid)
                            preferencesRepository.saveStringPref(markerKey, pendingAutoImportMarker.orEmpty())
                            D.transfer("observeDownloadProgress: saved auto-import marker ${pendingAutoImportMarker.orEmpty()} to $markerKey")
                        } else {
                            D.transfer("observeDownloadProgress: keeping auto-import marker unchanged (completed=${progress.completed}, failed=${progress.failed})")
                        }
                        pendingAutoImportMarker = null
                        pendingAutoImportMarkerSsid = null
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    private fun formatEtaLabel(etaMillis: Long): String {
        if (etaMillis <= 0L) return ""
        val totalSeconds = (etaMillis / 1000L).coerceAtLeast(1L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes > 0L) {
            "about ${minutes}m ${seconds}s left"
        } else {
            "about ${seconds}s left"
        }
    }

    private suspend fun refreshSavedCamerasState() {
        val savedCameras = preferencesRepository.loadSavedCameraProfiles()
        val selectedCameraSsid = preferencesRepository.loadSelectedCameraSsid()
        val selectedCardSlotSource = savedCameras.firstOrNull { it.ssid == selectedCameraSsid }?.playTargetSlot
        updateUiState { current ->
            current.copy(
                hasSavedCamera = savedCameras.isNotEmpty(),
                savedCameras = savedCameras,
                selectedCameraSsid = selectedCameraSsid,
                selectedCardSlotSource = selectedCardSlotSource,
            )
        }
    }

    private fun supportsPlayTargetSlotSelection(): Boolean {
        val commands = _uiState.value.workspace.protocol.commandList.commands
        val supportsGet = commands.any {
            it.supported && it.name.equals("get_playtargetslot", ignoreCase = true)
        }
        val supportsSet = commands.any {
            it.supported && it.name.equals("set_playtargetslot", ignoreCase = true)
        }
        return supportsGet && supportsSet
    }

    private suspend fun syncSelectedCardSlotSourceFromCamera() {
        if (!supportsPlayTargetSlotSelection()) {
            activePlayTargetSlot = null
            return
        }
        val slot = repository.getPlayTargetSlot().getOrNull() ?: return
        activePlayTargetSlot = slot
        val selectedSsid = _uiState.value.selectedCameraSsid
        if (_uiState.value.selectedCardSlotSource != slot) {
            updateUiState { current ->
                current.copy(selectedCardSlotSource = slot)
            }
        }
        if (!selectedSsid.isNullOrBlank()) {
            preferencesRepository.updateCameraPlayTargetSlot(selectedSsid, slot)
            refreshSavedCamerasState()
        }
    }

    private suspend fun ensureSelectedPlayTargetSlotApplied(): Result<Unit> {
        if (!supportsPlayTargetSlotSelection()) {
            activePlayTargetSlot = null
            return Result.success(Unit)
        }
        val desiredSlot = _uiState.value.selectedCardSlotSource?.takeIf { it in 1..2 }
            ?: return Result.success(Unit)
        val currentSlot = activePlayTargetSlot ?: repository.getPlayTargetSlot().getOrElse { error ->
            return Result.failure(error)
        }
        activePlayTargetSlot = currentSlot
        if (currentSlot == desiredSlot) {
            return Result.success(Unit)
        }
        val setResult = repository.setPlayTargetSlot(desiredSlot)
        if (setResult.isFailure) {
            return Result.failure(setResult.exceptionOrNull() ?: IllegalStateException("Failed to set play target slot"))
        }
        val appliedSlot = setResult.getOrThrow()
        activePlayTargetSlot = appliedSlot
        val selectedSsid = _uiState.value.selectedCameraSsid
        if (!selectedSsid.isNullOrBlank()) {
            preferencesRepository.updateCameraPlayTargetSlot(selectedSsid, appliedSlot)
        }
        if (_uiState.value.selectedCardSlotSource != appliedSlot) {
            updateUiState { it.copy(selectedCardSlotSource = appliedSlot) }
        }
        return Result.success(Unit)
    }

    private suspend fun ensureCameraWifiReady(
        ssid: String?,
        password: String?,
        retries: Int = 18,
        retryDelayMillis: Long = 1000L,
    ): Boolean {
        if (!cameraWifiManager.isWifiEnabled()) {
            return false
        }
        if (cameraWifiManager.isConnectedToCameraSsid(ssid) && cameraWifiManager.getCameraNetwork() != null) {
            return true
        }
        if (ssid.isNullOrBlank() || password.isNullOrBlank()) {
            return false
        }

        repeat(retries) { attempt ->
            if (cameraWifiManager.isConnectedToCameraSsid(ssid) && cameraWifiManager.getCameraNetwork() != null) {
                D.wifi("ensureCameraWifiReady: connected to $ssid after ${attempt + 1} checks")
                return true
            }

            if (attempt == 0 || attempt == 8 || attempt == 14) {
                updateUiState { it.copy(
                    refreshStatus = "Waiting for ${ssid} Wi-Fi...",
                ) }
                val wifiVisible = cameraWifiManager.waitForCameraSsidVisibility(
                    ssid = ssid,
                    timeoutMillis = if (attempt == 0) 21000L else 6000L,
                    scanDelayMillis = 3000L,
                )
                if (!wifiVisible) {
                    D.wifi("ensureCameraWifiReady: SSID $ssid did not appear before requestNetwork (attempt ${attempt + 1})")
                    delay(retryDelayMillis)
                    return@repeat
                }

                updateUiState { it.copy(
                    refreshStatus = "Joining ${ssid}...",
                ) }
                D.wifi("ensureCameraWifiReady: requesting camera WiFi for $ssid (attempt ${attempt + 1})")
                val connectIssued = cameraWifiManager.connectToCameraWifiAndAwait(
                    ssid = ssid,
                    password = password,
                    timeoutMillis = 15000L,
                    openSettingsOnFailure = false,
                )
                repeat(if (connectIssued) 5 else 3) { settleAttempt ->
                    if (cameraWifiManager.isConnectedToCameraSsid(ssid) &&
                        cameraWifiManager.getCameraNetwork() != null
                    ) {
                        D.wifi("ensureCameraWifiReady: connected to $ssid after request settle #${settleAttempt + 1}")
                        return true
                    }
                    delay(350L)
                }
            }
            delay(retryDelayMillis)
        }
        cameraWifiManager.clearRequestedCameraConnection()
        return false
    }

    private suspend fun resolveLiveViewCameraNetwork(
        ssid: String?,
        password: String?,
    ): android.net.Network? {
        if (!cameraWifiManager.isWifiEnabled()) {
            return null
        }

        fun readyNetwork(): android.net.Network? {
            val network = cameraWifiManager.getCameraNetwork()
            return if (cameraWifiManager.isConnectedToCameraSsid(ssid) && network != null) {
                network
            } else {
                null
            }
        }

        for (attempt in 0 until 6) {
            readyNetwork()?.let { network ->
                return network
            }
            if (!cameraWifiManager.isCameraRequestInFlight(ssid)) {
                break
            }
            delay(100L)
        }

        readyNetwork()?.let { network ->
            return network
        }

        if (ssid.isNullOrBlank() || password.isNullOrBlank()) {
            return null
        }

        updateUiState { it.copy(refreshStatus = "Joining $ssid...") }
        D.wifi("resolveLiveViewCameraNetwork: requesting camera WiFi for $ssid")
        val requested = cameraWifiManager.connectToCameraWifiAndAwait(
            ssid = ssid,
            password = password,
            timeoutMillis = 8000L,
            openSettingsOnFailure = false,
        )
        if (!requested) {
            return null
        }

        repeat(8) { settleAttempt ->
            readyNetwork()?.let { network ->
                D.wifi("resolveLiveViewCameraNetwork: network ready after settle #${settleAttempt + 1}")
                return network
            }
            delay(125L)
        }

        return readyNetwork()
    }

    private suspend fun reconnectCameraWifi(ssid: String, password: String): Boolean {
        if (!cameraWifiManager.isWifiEnabled()) return false

        fun isReady() = cameraWifiManager.isConnectedToCameraSsid(ssid) &&
            cameraWifiManager.getCameraNetwork() != null

        if (isReady()) return true

        updateUiState { it.copy(refreshStatus = RECONNECT_WIFI_STATUS) }
        val wifiVisible = cameraWifiManager.waitForCameraSsidVisibility(
            ssid = ssid,
            timeoutMillis = 4500L,
            scanDelayMillis = 650L,
        )
        if (!wifiVisible) {
            D.wifi("reconnectCameraWifi: camera SSID $ssid not visible during quick scan, attempting direct requestNetwork")
        }

        D.wifi("reconnectCameraWifi: fast requestNetwork attempt for $ssid")
        val fastConnected = cameraWifiManager.connectToCameraWifiAndAwait(
            ssid = ssid,
            password = password,
            timeoutMillis = if (wifiVisible) 12000L else 16000L,
            openSettingsOnFailure = false,
        )
        if (fastConnected && isReady()) {
            D.wifi("reconnectCameraWifi: connected on fast path")
            return true
        }

        cameraWifiManager.clearRequestedCameraConnection()
        if (!cameraWifiManager.isConnectedToCameraSsid(ssid)) {
            val retryVisible = cameraWifiManager.waitForCameraSsidVisibility(
                ssid = ssid,
                timeoutMillis = 6000L,
                scanDelayMillis = 1000L,
            )
            if (!retryVisible && !cameraWifiManager.isConnectedToCameraSsid(ssid)) {
                D.wifi("reconnectCameraWifi: camera SSID $ssid not visible after retry scan")
                cameraWifiManager.clearRequestedCameraConnection()
                return false
            }
        }

        D.wifi("reconnectCameraWifi: retry requestNetwork for $ssid")
        val connected = cameraWifiManager.connectToCameraWifiAndAwait(
            ssid = ssid,
            password = password,
            timeoutMillis = 22000L,
            openSettingsOnFailure = false,
        )
        if (connected && isReady()) {
            D.wifi("reconnectCameraWifi: connected on retry path")
            return true
        }

        D.wifi("reconnectCameraWifi: failed for $ssid")
        cameraWifiManager.clearRequestedCameraConnection()
        return false
    }

    private fun isWifiHandoffInProgress(): Boolean {
        val targetSsid = _uiState.value.selectedCameraSsid
        return reconnectJob?.isActive == true ||
            handshakeJob?.isActive == true ||
            cameraWifiManager.isCameraRequestInFlight(targetSsid)
    }

    private fun schedulePropertyRefresh(
        delayMillis: Long = if (_uiState.value.remoteRuntime.liveViewActive) 700L else 280L,
    ) {
        propertyRefreshJob?.cancel()
        propertyRefreshJob = viewModelScope.launch {
            delay(delayMillis)
            if (propertyApplyJob?.isActive == true) {
                return@launch
            }
            loadCameraProperties()
        }
    }

    private suspend fun applyExposureModeChange(
        mode: CameraExposureMode,
        closePicker: Boolean,
    ): Boolean {
        val runtime = _uiState.value.remoteRuntime
        val rawTakeMode = resolveTakeModeRaw(mode, runtime.takeMode.availableValues)
        if (rawTakeMode == null) {
            updateUiState { current -> current.copy(
                remoteRuntime = current.remoteRuntime.copy(
                    statusLabel = "Mode switch unavailable",
                ),
            ) }
            return false
        }
        if (!rawTakeMode.equals(runtime.takeMode.currentValue, ignoreCase = true)) {
            updateUiState { current -> current.copy(
                remoteRuntime = current.remoteRuntime.copy(
                    statusLabel = "Switching to ${mode.label}...",
                ),
            ) }
            D.mode("setCameraExposureMode: sending takemode=$rawTakeMode for $mode")
            if (!applyTakeModeSelection(rawTakeMode)) {
                return false
            }
        } else {
            D.mode("setCameraExposureMode: takemode already matches $rawTakeMode")
        }
        clearPendingProperty("focalvalue")
        clearPendingProperty("shutspeedvalue")
        val currentRuntime = _uiState.value.remoteRuntime
        val updatedRuntime = currentRuntime.copy(
            exposureMode = mode,
            takeMode = currentRuntime.takeMode.copy(currentValue = rawTakeMode),
            aperture = currentRuntime.aperture.copy(
                enabled = isApertureControlEnabled(mode),
            ),
            shutterSpeed = currentRuntime.shutterSpeed.copy(
                enabled = isShutterControlEnabled(mode),
            ),
            activePicker = if (closePicker) ActivePropertyPicker.None else currentRuntime.activePicker,
            modePickerSurface = if (closePicker) ModePickerSurface.CameraModes else currentRuntime.modePickerSurface,
        )
        updateUiState { it.copy(
            remoteRuntime = updatedRuntime.copy(
                statusLabel = if (updatedRuntime.liveViewActive) {
                    "Live view active"
                } else {
                    "${mode.label} mode"
                },
            ),
        ) }
        schedulePropertyRefresh(delayMillis = 80L)
        return true
    }

    private fun shouldAutoConnectUsbTether(): Boolean {
        val usbState = _uiState.value.omCaptureUsb
        return !usbState.isBusy && usbState.summary == null
    }

    private fun startUsbLiveViewSession(
        waitingStatus: String,
    ) {
        prepareOmCaptureUsbForControlMode()
        setUsbLiveViewDesired(true, "start-session")
        usbLiveViewRecoveryJob?.cancel()
        usbLiveViewRecoveryJob = null
        // If the previous live view loop ended but the flag wasn't cleaned up, force-stop first
        // so startUsbLiveView() doesn't bail out with "already active".
        if (omCaptureUsbManager.isLiveViewActive) {
            D.usb("startUsbLiveViewSession: force-stopping stale live view before restart")
            omCaptureUsbManager.stopUsbLiveView()
        }
        usbLiveViewFrameJob?.cancel()
        usbLiveViewFrameJob = null
        omCaptureUsbManager.startUsbLiveView()
        usbLiveViewStartupRefreshJob?.cancel()
        usbLiveViewStartupRefreshJob = viewModelScope.launch {
            delay(350L)
            if (
                omCaptureUsbManager.isLiveViewActive &&
                isUsbModeSurface(_uiState.value.remoteRuntime.modePickerSurface)
            ) {
                refreshUsbProperties(includeExtras = false)
            }
        }
        usbLiveViewFrameJob?.cancel()
        usbLiveViewFrameJob = viewModelScope.launch {
            omCaptureUsbManager.usbLiveViewFrame.collect { bitmap ->
                _liveViewFrame.value = bitmap
                val currentState = _uiState.value
                val surface = if (isUsbModeSurface(currentState.remoteRuntime.modePickerSurface)) {
                    currentState.remoteRuntime.modePickerSurface
                } else {
                    ModePickerSurface.Tether
                }
                if (!currentState.remoteRuntime.liveViewActive ||
                    currentState.remoteRuntime.modePickerSurface != surface
                ) {
                    refreshOmCaptureStudioState(
                        currentState.copy(
                            remoteRuntime = currentState.remoteRuntime.copy(
                                liveViewActive = true,
                                activePicker = ActivePropertyPicker.None,
                                modePickerSurface = surface,
                                statusLabel = "USB live view",
                            ),
                        ),
                    )
                }
            }
        }
        val surface = if (isUsbModeSurface(_uiState.value.remoteRuntime.modePickerSurface)) {
            _uiState.value.remoteRuntime.modePickerSurface
        } else {
            ModePickerSurface.Tether
        }
        refreshOmCaptureStudioState(
            _uiState.value.copy(
                remoteRuntime = _uiState.value.remoteRuntime.copy(
                    liveViewActive = true,
                    activePicker = ActivePropertyPicker.None,
                    modePickerSurface = surface,
                    statusLabel = waitingStatus,
                ),
            ),
        )
    }

    private suspend fun applyTakeModeSelection(
        rawTakeMode: String,
    ): Boolean {
        if (_uiState.value.remoteRuntime.takeMode.currentValue.equals(rawTakeMode, ignoreCase = true)) {
            return true
        }

        D.mode("MODE_CHANGE: sending set_takemode=$rawTakeMode")
        val requestedResult = repository.setTakeMode(rawTakeMode)
        if (requestedResult.isFailure) {
            val error = requestedResult.exceptionOrNull()
            D.mode("MODE_CHANGE: set_takemode FAILED: ${error?.message}")
            D.err("PROTO", "Failed to set takemode=$rawTakeMode", error)
            updateUiState { current -> current.copy(
                remoteRuntime = current.remoteRuntime.copy(
                    statusLabel = "Mode switch failed: ${error?.message}",
                ),
            ) }
            return false
        }
        D.mode("MODE_CHANGE: set_takemode sent OK")
        return true
    }

    private fun applyDriveModeSelection(driveMode: DriveMode, timerMode: TimerMode) {
        if (isUsbRemoteTransportActive()) {
            applyUsbDriveModeSelection(driveMode, timerMode)
            return
        }
        propertyApplyJob?.cancel()
        propertyApplyJob = viewModelScope.launch {
            delay(140)
            val runtime = _uiState.value.remoteRuntime
            val rawDriveMode = resolveDriveModeRaw(
                driveMode = driveMode,
                timerMode = timerMode,
                options = runtime.driveModeValues.availableValues,
                currentRaw = runtime.driveModeValues.currentValue,
            )
            if (rawDriveMode == null) {
                updateUiState { it.copy(
                    remoteRuntime = runtime.copy(
                        statusLabel = "Camera does not support drive mode change",
                    ),
                ) }
                return@launch
            }
            if (rawDriveMode.equals(runtime.driveModeValues.currentValue, ignoreCase = true)) {
                updateUiState { it.copy(
                    remoteRuntime = runtime.copy(
                        statusLabel = if (runtime.liveViewActive) "Live view active" else runtime.statusLabel,
                    ),
                ) }
                return@launch
            }

            repository.setProperty("drivemode", rawDriveMode)
                .onSuccess {
                    D.proto("Drive mode updated -> $rawDriveMode")
                    schedulePropertyRefresh()
                }
                .onFailure { error ->
                    D.err("PROTO", "Failed to set drivemode=$rawDriveMode", error)
                    updateUiState { it.copy(
                        remoteRuntime = runtime.copy(
                            statusLabel = "Drive mode change failed: ${error.message}",
                        ),
                    ) }
                }
        }
    }

    private suspend fun applyDirectPropertySelection(
        picker: ActivePropertyPicker,
        requestedValue: String,
        confirmedValue: String,
    ) {
        propertyRefreshJob?.cancel()
        val propName = when (picker) {
            ActivePropertyPicker.Aperture -> "focalvalue"
            ActivePropertyPicker.ShutterSpeed -> "shutspeedvalue"
            ActivePropertyPicker.Iso -> "isospeedvalue"
            ActivePropertyPicker.WhiteBalance -> "wbvalue"
            else -> return
        }
        D.proto("applyPropertySelection: picker=$picker, requestedValue=$requestedValue, confirmedValue=$confirmedValue")

        if (propertyValueMatches(propName, confirmedValue, requestedValue)) {
            D.proto("Property $propName already at $requestedValue, skipping redundant set")
            clearPendingProperty(propName)
            schedulePropertyRefresh(delayMillis = 60L)
            return
        }

        D.cmd("SET_PROPERTY REQUEST: $propName=$requestedValue")
        D.timeStart("set_$propName")
        repository.setProperty(propName, requestedValue)
            .onSuccess {
                D.timeEnd("CMD", "set_$propName", "SET_PROPERTY SUCCESS: $propName=$requestedValue")
                updateRemoteRuntime { current ->
                    current.copy(
                        statusLabel = if (current.liveViewActive) "Live view active" else "Applied",
                    )
                }
                schedulePropertyRefresh(delayMillis = 60L)
            }
            .onFailure { error ->
                D.timeEnd("CMD", "set_$propName", "SET_PROPERTY FAIL: $propName=$requestedValue err=${error.message}")
                clearPendingProperty(propName)
                val errMsg = error.message.orEmpty()
                val isTimeout = "timeout" in errMsg.lowercase() || "Timeout" in errMsg
                D.err("PROTO", "DIAL_MISMATCH: Failed to set $propName=$requestedValue (isTimeout=$isTimeout)", error)
                schedulePropertyRefresh(delayMillis = 90L)
            }
    }

    private suspend fun waitForPropertyTarget(
        propName: String,
        expectedValue: String,
        attempts: Int = 6,
    ): CameraPropertyDesc? {
        for (attempt in 0 until attempts) {
            if (attempt > 0) {
                delay(110L + (attempt * 120L))
            }
            val desc = repository.getPropertyDesc(propName).getOrNull()
            if (desc != null && (
                    propertyValueMatches(propName, desc.currentValue, expectedValue) ||
                        (propName == "isospeedvalue" &&
                            (isUiAutoValue(expectedValue) ||
                                PropertyFormatter.isAutoValue(propName, expectedValue)) &&
                            desc.enumValues.any { PropertyFormatter.isAutoValue(propName, it) })
                    )
            ) {
                // Keep the pending target until the next property merge so a readback
                // cannot briefly replace a user-selected value with stale camera data.
                D.proto("waitForPropertyTarget: $propName confirmed at $expectedValue (pending kept for load)")
                return desc
            }
        }
        return null
    }

    private fun mergePendingPropertyCurrent(propName: String, actualValue: String): String {
        val pendingValue = pendingPropertyTargets[propName] ?: return actualValue
        if (propertyValueMatches(propName, actualValue, pendingValue)) {
            // Camera confirmed the pending value — safe to clear
            D.proto("pending[$propName] CONFIRMED: camera=$actualValue matches pending=$pendingValue → cleared")
            clearPendingProperty(propName)
            return actualValue
        }
        // Check if the pending target is stale (e.g., camera rounded to a slightly different value
        // and will never exactly match). After 8 seconds, accept the camera's value.
        val timestamp = pendingPropertyTimestamps[propName]
        val ageMs = if (timestamp != null) System.currentTimeMillis() - timestamp else -1L
        if (timestamp != null && ageMs > PENDING_PROPERTY_MAX_AGE_MS) {
            D.proto("pending[$propName] STALE: pending=$pendingValue aged out (${ageMs}ms), accepting camera=$actualValue")
            clearPendingProperty(propName)
            return actualValue
        }
        D.proto("pending[$propName] ACTIVE: rejecting camera=$actualValue, keeping pending=$pendingValue (age=${ageMs}ms)")
        return pendingValue
    }

    /** Clear a pending property target and its timestamp. */
    private fun clearPendingProperty(propName: String) {
        pendingPropertyTargets.remove(propName)
        pendingPropertyTimestamps.remove(propName)
    }

    private data class LiveViewApertureRange(
        val currentValue: String?,
        val minValue: String?,
        val maxValue: String?,
    )

    private fun applyLiveViewApertureMetadata(metadata: LiveViewMetadata) {
        if (_uiState.value.omCaptureUsb.summary != null) {
            // Match the original USB tools: for tethered Olympus bodies, aperture
            // options come from the property descriptor enumeration rather than
            // live-view min/max metadata.
            return
        }
        val liveViewRange = currentLiveViewApertureRange(metadata) ?: return
        val runtime = _uiState.value.remoteRuntime
        val rawOptions = lastRawApertureOptions.ifEmpty { runtime.aperture.availableValues }
        if (rawOptions.isEmpty()) {
            return
        }
        val currentValue = mergePendingPropertyCurrent(
            "focalvalue",
            liveViewRange.currentValue ?: runtime.aperture.currentValue,
        )
        val filtered = buildOriginalApertureRange(
            options = rawOptions,
            minValue = liveViewRange.minValue,
            maxValue = liveViewRange.maxValue,
            currentValue = currentValue,
            previousValues = runtime.aperture.availableValues,
        )
        if (currentValue == runtime.aperture.currentValue &&
            filtered == runtime.aperture.availableValues
        ) {
            return
        }
        D.prop(
            "LIVEVIEW_APERTURE: current=${liveViewRange.currentValue} min=${liveViewRange.minValue} max=${liveViewRange.maxValue} filtered=${filtered.size}",
        )
        updateUiState { it.copy(
            remoteRuntime = runtime.copy(
                aperture = runtime.aperture.copy(
                    currentValue = currentValue,
                    availableValues = filtered,
                ),
            ),
        ) }
    }

    private fun currentLiveViewApertureRange(
        metadata: LiveViewMetadata? = latestLiveViewMetadata,
    ): LiveViewApertureRange? {
        metadata ?: return null
        return LiveViewApertureRange(
            currentValue = formatOriginalApertureTenths(metadata.apertureCurrentTenths),
            minValue = formatOriginalApertureTenths(metadata.apertureMinTenths),
            maxValue = formatOriginalApertureTenths(metadata.apertureMaxTenths),
        )
    }

    private fun formatOriginalApertureTenths(rawTenths: Int?): String? {
        val tenths = rawTenths ?: return null
        if (tenths < 0) {
            return null
        }
        val numeric = tenths / 10f
        var formatted = String.format(Locale.ENGLISH, "%.1f", numeric)
        val parts = formatted.split(".")
        if (parts.firstOrNull()?.length ?: 0 >= 2) {
            formatted = parts.first()
        }
        return if (formatted == "+0.0") "0.0" else formatted
    }

    private fun buildOriginalApertureRange(
        options: List<String>,
        minValue: String?,
        maxValue: String?,
        currentValue: String?,
        previousValues: List<String>,
    ): List<String> {
        val manualOptions = options.distinct()
        if (manualOptions.isEmpty()) {
            return previousValues.ifEmpty {
                listOfNotNull(currentValue?.takeIf { it.isNotBlank() })
            }
        }

        val minNumeric = canonicalNumericValue(minValue)?.takeIf { it > 0.0 }
        val maxNumeric = canonicalNumericValue(maxValue)?.takeIf { it > 0.0 }
        if (minNumeric == null && maxNumeric == null) {
            val preserved = previousValues
                .filter { previous ->
                    manualOptions.any { option -> matchesNumericValue(option, previous) || option.equals(previous, ignoreCase = true) }
                }
                .distinct()
            if (preserved.isNotEmpty()) {
                return preserved
            }
            val currentOnly = manualOptions.firstOrNull { option ->
                matchesNumericValue(option, currentValue) || option.equals(currentValue, ignoreCase = true)
            } ?: currentValue?.takeIf { it.isNotBlank() }
            return listOfNotNull(currentOnly).distinct()
        }

        val filtered = mutableListOf<String>()
        if (minNumeric != null && !minValue.isNullOrBlank() && manualOptions.none { matchesNumericValue(it, minValue) }) {
            filtered += minValue
        }

        var withinLowerBound = minNumeric == null
        manualOptions.forEach { option ->
            val numeric = canonicalNumericValue(option) ?: return@forEach
            if (!withinLowerBound && minNumeric != null && numeric >= (minNumeric - 0.05)) {
                withinLowerBound = true
            }
            if (!withinLowerBound) {
                return@forEach
            }
            if (maxNumeric != null && numeric > (maxNumeric + 0.05)) {
                return filtered.distinct().ifEmpty {
                    listOfNotNull(currentValue?.takeIf { it.isNotBlank() })
                }
            }
            filtered += option
        }
        return filtered.distinct().ifEmpty {
            listOfNotNull(currentValue?.takeIf { it.isNotBlank() })
        }
    }

    private fun resolveIsoAutoActive(
        currentValue: String,
        options: List<String>,
        previous: Boolean,
    ): Boolean {
        return currentValue.equals("AUTO", ignoreCase = true)
    }

    private fun mergePropertyDesc(
        primary: CameraPropertyDesc?,
        secondary: CameraPropertyDesc?,
    ): CameraPropertyDesc? {
        return when {
            primary == null -> secondary
            secondary == null -> primary
            else -> primary.copy(
                attribute = secondary.attribute.ifBlank { primary.attribute },
                currentValue = secondary.currentValue.ifBlank { primary.currentValue },
                enumValues = if (secondary.enumValues.isNotEmpty()) secondary.enumValues else primary.enumValues,
                minValue = secondary.minValue ?: primary.minValue,
                maxValue = secondary.maxValue ?: primary.maxValue,
            )
        }
    }

    private fun isUsablePropertyDesc(desc: CameraPropertyDesc?): Boolean {
        return desc != null && (desc.currentValue.isNotBlank() || desc.enumValues.isNotEmpty())
    }

    private fun isTransientCameraPropFailure(error: Throwable?): Boolean {
        val message = error?.message.orEmpty()
        return "520" in message || "503" in message
    }

    private fun updateTransferThumbnails(
        results: List<Pair<String, Bitmap>>,
        overwriteExisting: Boolean,
    ) {
        if (results.isEmpty()) return
        val updatedThumbnails = _uiState.value.transferState.thumbnails.toMutableMap()
        var changed = false
        results.forEach { (fileName, bitmap) ->
            val existing = updatedThumbnails[fileName]
            if (overwriteExisting) {
                if (existing !== bitmap) {
                    updatedThumbnails[fileName] = bitmap
                    changed = true
                }
            } else if (fileName !in updatedThumbnails) {
                updatedThumbnails[fileName] = bitmap
                changed = true
            }
        }
        if (!changed) return
        val latestFileName = _uiState.value.transferState.images.firstOrNull()?.fileName
        updateUiState { state -> state.copy(
            transferState = state.transferState.copy(
                thumbnails = updatedThumbnails,
            ),
            lastCaptureThumbnail = latestFileName?.let(updatedThumbnails::get) ?: state.lastCaptureThumbnail,
        ) }
    }

    private fun sanitizePropertyOptions(
        propName: String,
        options: List<String>,
    ): List<String> {
        return options
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { candidate ->
                when (propName) {
                    "focalvalue",
                    "shutspeedvalue" -> PropertyFormatter.isAutoValue(propName, candidate) ||
                        canonicalNumericValue(candidate) != null
                    else -> true
                }
            }
            .distinct()
    }

    private fun resolveRequestedPropertyValue(
        propName: String,
        selectedValue: String,
        currentValues: List<String>,
    ): String {
        if (propName.isBlank()) return selectedValue
        if (isUiAutoValue(selectedValue) || PropertyFormatter.isAutoValue(propName, selectedValue)) {
            val rawAutoValue = when (propName) {
                "isospeedvalue" -> currentValues.firstOrNull { it.equals("AUTO", ignoreCase = true) }
                    ?: PropertyFormatter.normalizeSetValue(propName, selectedValue)
                "wbvalue" -> currentValues.firstOrNull { PropertyFormatter.isAutoValue(propName, it) }
                    ?: PropertyFormatter.normalizeSetValue(propName, selectedValue)
                else -> PropertyFormatter.normalizeSetValue(propName, selectedValue)
            }
            D.proto("resolveRequestedPropertyValue: $propName UI auto '$selectedValue' → raw '$rawAutoValue'")
            return rawAutoValue
        }
        val directMatch = currentValues.firstOrNull { candidate ->
            propertyValueMatches(propName, candidate, selectedValue) ||
                PropertyFormatter.formatForDisplay(propName, candidate).equals(selectedValue, ignoreCase = true)
        }
        return PropertyFormatter.normalizeSetValue(propName, directMatch ?: selectedValue)
    }

    private fun resolveRequestedExposureCompValue(
        selectedValue: String,
        currentValues: List<String>,
        currentValue: String,
    ): String {
        val candidates = buildList {
            addAll(currentValues)
            if (currentValue.isNotBlank()) {
                add(currentValue)
            }
        }.distinct()
        val directMatch = candidates.firstOrNull { candidate ->
            propertyValueMatches("expcomp", candidate, selectedValue) ||
                PropertyFormatter.formatForDisplay("expcomp", candidate).equals(selectedValue, ignoreCase = true)
        }
        if (directMatch != null) {
            return directMatch
        }
        val numeric = PropertyFormatter.exposureCompensationNumericValue(selectedValue)
            ?: return PropertyFormatter.normalizeSetValue("expcomp", selectedValue)
        val rawScale = inferExposureCompRawScale(candidates)
        return when (rawScale) {
            100 -> (numeric * 100.0).roundToInt().toString()
            else -> PropertyFormatter.normalizeSetValue("expcomp", selectedValue)
        }
    }

    private fun propertyValueMatches(
        propName: String,
        actualValue: String?,
        expectedValue: String?,
    ): Boolean {
        if (actualValue.isNullOrBlank() || expectedValue.isNullOrBlank()) return false
        return when (propName) {
            "takemode" -> {
                actualValue.equals(expectedValue, ignoreCase = true) ||
                    normalizeUsbModeValue(actualValue) == normalizeUsbModeValue(expectedValue)
            }
            "wbvalue" -> {
                // WB "0" ↔ "AWB" equivalence
                actualValue.equals(expectedValue, ignoreCase = true) ||
                    (PropertyFormatter.isAutoValue(propName, actualValue) &&
                        PropertyFormatter.isAutoValue(propName, expectedValue))
            }
            "isospeedvalue" -> {
                matchesNumericValue(actualValue, expectedValue) ||
                    actualValue.equals(expectedValue, ignoreCase = true) ||
                    (PropertyFormatter.isAutoValue(propName, actualValue) &&
                        PropertyFormatter.isAutoValue(propName, expectedValue))
            }
            "focalvalue",
            "shutspeedvalue",
            -> matchesNumericValue(actualValue, expectedValue) ||
                actualValue.equals(expectedValue, ignoreCase = true)
            "expcomp" -> matchesExposureCompValue(actualValue, expectedValue) ||
                actualValue.equals(expectedValue, ignoreCase = true)
            else -> actualValue.equals(expectedValue, ignoreCase = true)
        }
    }

    private fun matchesExposureCompValue(candidate: String?, target: String?): Boolean {
        if (candidate.isNullOrBlank() || target.isNullOrBlank()) return false
        val candidateNumeric = PropertyFormatter.exposureCompensationNumericValue(candidate)
        val targetNumeric = PropertyFormatter.exposureCompensationNumericValue(target)
        return when {
            candidateNumeric != null && targetNumeric != null -> abs(candidateNumeric - targetNumeric) < 0.051
            else -> candidate.equals(target, ignoreCase = true)
        }
    }

    private fun inferExposureCompRawScale(values: List<String>): Int {
        val maxIntegerMagnitude = values
            .mapNotNull { raw ->
                raw.trim()
                    .takeIf { it.isNotBlank() && '.' !in it && 'e' !in it.lowercase(Locale.ENGLISH) }
                    ?.toIntOrNull()
                    ?.let(::abs)
            }
            .maxOrNull()
            ?: return 1
        return if (maxIntegerMagnitude > 7) 100 else 1
    }

    private fun normalizeUsbModeValue(value: String?): String? {
        val normalized = value?.trim()?.uppercase(Locale.ENGLISH) ?: return null
        return when {
            normalized == "P" || normalized.contains("PROGRAM") -> "P"
            normalized == "A" || normalized.contains("APERTURE") -> "A"
            normalized == "S" || normalized.contains("SHUTTER") -> "S"
            normalized == "M" || normalized.contains("MANUAL") -> "M"
            normalized == "B" || normalized.contains("BULB") || normalized.contains("TIME") || normalized.contains("COMPOSITE") -> "B"
            normalized.contains("VIDEO") || normalized.contains("MOVIE") -> "VIDEO"
            else -> normalized
        }
    }

    private fun matchesNumericValue(candidate: String?, target: String?): Boolean {
        if (candidate.isNullOrBlank() || target.isNullOrBlank()) return false
        val candidateNumeric = canonicalNumericValue(candidate)
        val targetNumeric = canonicalNumericValue(target)
        return when {
            candidateNumeric != null && targetNumeric != null -> abs(candidateNumeric - targetNumeric) < 0.051
            else -> candidate.equals(target, ignoreCase = true)
        }
    }

    private fun canonicalNumericValue(value: String?): Double? {
        val normalized = value
            ?.trim()
            ?.removePrefix("F")
            ?.removePrefix("f")
            ?.removeSuffix(" sec")
            ?.removeSuffix(" SEC")
            ?.removeSuffix("s")
            ?.removeSuffix("S")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        // Shutter speed in seconds notation (e.g., 60", 2") uses negative values
        // so range comparisons order correctly: seconds < fractions.
        // 60" (60 seconds, slow) → -60.0, while 60 (1/60s, fast) → 60.0.
        if (normalized.endsWith("\"") || normalized.endsWith("\u201D")) {
            val raw = normalized.removeSuffix("\"").removeSuffix("\u201D")
            return raw.toDoubleOrNull()?.let { -it }
        }

        return when {
            normalized.startsWith("1/") -> normalized.substringAfter("1/").toDoubleOrNull()
            else -> normalized.toDoubleOrNull()
        }
    }

    private fun targetExposureModeForSelection(
        runtime: RemoteRuntimeState,
        picker: ActivePropertyPicker,
        value: String,
    ): CameraExposureMode {
        val result = targetExposureModeForSelectionInternal(runtime, picker, value)
        if (result != runtime.exposureMode) {
            D.mode("mode derivation: $picker=$value, F=${runtime.aperture.currentValue}, SS=${runtime.shutterSpeed.currentValue} → ${runtime.exposureMode} → $result")
        }
        return result
    }

    private fun targetExposureModeForSelectionInternal(
        runtime: RemoteRuntimeState,
        picker: ActivePropertyPicker,
        value: String,
    ): CameraExposureMode {
        val wantsAuto = isUiAutoValue(value) || when (picker) {
            ActivePropertyPicker.Aperture -> value.equals(SYNTHETIC_APERTURE_AUTO, ignoreCase = true)
            ActivePropertyPicker.ShutterSpeed -> value.equals(SYNTHETIC_SHUTTER_AUTO, ignoreCase = true)
            else -> false
        }
        val shutterAutoManaged = !runtime.shutterSpeed.enabled
        val apertureAutoManaged = !runtime.aperture.enabled
        return when (picker) {
            ActivePropertyPicker.Aperture -> {
                if (wantsAuto) {
                    if (shutterAutoManaged) CameraExposureMode.P else CameraExposureMode.S
                } else {
                    if (shutterAutoManaged) CameraExposureMode.A else CameraExposureMode.M
                }
            }
            ActivePropertyPicker.ShutterSpeed -> {
                if (wantsAuto) {
                    if (apertureAutoManaged) CameraExposureMode.P else CameraExposureMode.A
                } else {
                    if (apertureAutoManaged) CameraExposureMode.S else CameraExposureMode.M
                }
            }
            ActivePropertyPicker.Iso -> {
                if (isUiAutoValue(value) && runtime.exposureMode in setOf(CameraExposureMode.M, CameraExposureMode.B)) {
                    when {
                        !isUiAutoValue(runtime.aperture.currentValue) -> CameraExposureMode.A
                        !isUiAutoValue(runtime.shutterSpeed.currentValue) -> CameraExposureMode.S
                        else -> CameraExposureMode.P
                    }
                } else {
                    runtime.exposureMode
                }
            }
            else -> runtime.exposureMode
        }
    }

    private fun resolveExposureMode(
        takeModeRaw: String?,
        fallbackMode: CameraExposureMode,
    ): CameraExposureMode {
        val normalized = takeModeRaw.orEmpty().uppercase()
        val result = when {
            normalized == "P" || normalized == "PS" || "PROGRAM" in normalized -> CameraExposureMode.P
            normalized == "A" || "APERTURE" in normalized -> CameraExposureMode.A
            normalized == "S" || "SHUTTER" in normalized -> CameraExposureMode.S
            normalized == "M" || "MANUAL" in normalized -> CameraExposureMode.M
            normalized == "B" || "BULB" in normalized || "TIME" in normalized || "COMP" in normalized -> CameraExposureMode.B
            "MOVIE" in normalized || "VIDEO" in normalized -> CameraExposureMode.VIDEO
            else -> fallbackMode
        }
        D.exposure("resolveExposureMode: takemode=$takeModeRaw fallback=${fallbackMode.label} → ${result.label}")
        return result
    }

    private fun resolveUsbExposureMode(
        reportedMode: CameraExposureMode?,
        reportedModePropCode: Int?,
        apertureProperty: OmCaptureUsbManager.CameraPropertyState?,
        shutterProperty: OmCaptureUsbManager.CameraPropertyState?,
    ): CameraExposureMode {
        val shutterDisplay = shutterProperty?.currentValue?.let(::formatUsbShutterSpeedValue)
        if (
            reportedMode == CameraExposureMode.B ||
            shutterDisplay.equals("Bulb", ignoreCase = true) ||
            shutterDisplay.equals("Time", ignoreCase = true) ||
            shutterDisplay.equals("Composite", ignoreCase = true)
        ) {
            return CameraExposureMode.B
        }
        if (reportedMode == CameraExposureMode.VIDEO) {
            return CameraExposureMode.VIDEO
        }
        if (reportedModePropCode == PtpConstants.Prop.ExposureProgramMode && reportedMode != null) {
            return reportedMode
        }
        val apertureWritable = apertureProperty?.isReadOnly?.not()
        val shutterWritable = shutterProperty?.isReadOnly?.not()
        return when {
            apertureProperty == null && shutterProperty == null -> reportedMode ?: CameraExposureMode.P
            apertureWritable == true && shutterWritable == true -> CameraExposureMode.M
            apertureWritable == true && shutterWritable == false -> CameraExposureMode.A
            apertureWritable == false && shutterWritable == true -> CameraExposureMode.S
            apertureWritable == false && shutterWritable == false -> CameraExposureMode.P
            else -> reportedMode ?: CameraExposureMode.P
        }
    }

    private fun usbModeRelatedRefreshCodes(propCode: Int): Set<Int> {
        if (
            propCode != PtpConstants.OlympusProp.Aperture &&
            propCode != PtpConstants.OlympusProp.ShutterSpeed &&
            propCode != PtpConstants.Prop.ExposureProgramMode &&
            propCode != PtpConstants.OlympusProp.ExposureMode
        ) {
            return emptySet()
        }
        return linkedSetOf(
            propCode,
            PtpConstants.Prop.ExposureProgramMode,
            PtpConstants.OlympusProp.ExposureMode,
            PtpConstants.OlympusProp.Aperture,
            PtpConstants.OlympusProp.ShutterSpeed,
            PtpConstants.OlympusProp.ExposureCompensation,
        )
    }

    private fun normalizeUsbTakeModeValues(
        values: CameraPropertyValues,
        resolvedMode: CameraExposureMode,
    ): CameraPropertyValues {
        val currentMode = resolveExposureMode(values.currentValue, resolvedMode)
        if (currentMode == resolvedMode) {
            return values
        }
        val normalizedCurrent = resolveTakeModeRaw(resolvedMode, values.availableValues) ?: resolvedMode.label
        return values.copy(currentValue = normalizedCurrent)
    }

    private fun isApertureControlEnabled(mode: CameraExposureMode): Boolean {
        return when (mode) {
            CameraExposureMode.P -> false
            CameraExposureMode.A -> true
            CameraExposureMode.S -> false
            CameraExposureMode.M -> true
            else -> true
        }
    }

    private fun isShutterControlEnabled(mode: CameraExposureMode): Boolean {
        return when (mode) {
            CameraExposureMode.P -> false
            CameraExposureMode.A -> false
            CameraExposureMode.S -> true
            CameraExposureMode.M -> true
            else -> true
        }
    }

    private fun coerceActivePickerForMode(
        activePicker: ActivePropertyPicker,
        mode: CameraExposureMode,
    ): ActivePropertyPicker {
        return when {
            activePicker == ActivePropertyPicker.Aperture && !isApertureControlEnabled(mode) -> ActivePropertyPicker.None
            activePicker == ActivePropertyPicker.ShutterSpeed && !isShutterControlEnabled(mode) -> ActivePropertyPicker.None
            else -> activePicker
        }
    }

    private fun resolveTakeModeRaw(
        mode: CameraExposureMode,
        options: List<String>,
    ): String? {
        val modeKey = mode.label.uppercase()
        val normalizedOptions = options.associateBy { it.uppercase() }
        if (modeKey in normalizedOptions) {
            return normalizedOptions[modeKey]
        }
        return when (mode) {
            CameraExposureMode.P -> options.firstOrNull { it.equals("P", true) || it.contains("program", true) } ?: mode.label
            CameraExposureMode.A -> options.firstOrNull { it.equals("A", true) || it.contains("aperture", true) } ?: mode.label
            CameraExposureMode.S -> options.firstOrNull { it.equals("S", true) || it.contains("shutter", true) } ?: mode.label
            CameraExposureMode.M -> options.firstOrNull { it.equals("M", true) || it.contains("manual", true) } ?: mode.label
            CameraExposureMode.B -> options.firstOrNull {
                it.equals("B", true) ||
                    it.contains("bulb", true) ||
                    it.contains("time", true) ||
                    it.contains("comp", true)
            } ?: mode.label
            CameraExposureMode.VIDEO -> options.firstOrNull { it.contains("movie", true) || it.contains("video", true) } ?: mode.label
        }
    }

    private fun driveAndTimerFromRaw(rawValue: String): Pair<DriveMode, TimerMode> {
        val lower = rawValue.lowercase()
        val timerMode = when {
            "customselftimer" in lower || ("timer" in lower && "burst" in lower) -> TimerMode.BurstTimer
            "selftimer" in lower || "timer" in lower -> TimerMode.Timer
            else -> TimerMode.Off
        }
        val driveMode = when {
            "procap" in lower || "pro capture" in lower -> DriveMode.Burst
            ("seq" in lower || "burst" in lower || "continuous" in lower) && "silent" in lower -> DriveMode.SilentBurst
            "silent" in lower -> DriveMode.Silent
            "seq" in lower || "burst" in lower || "continuous" in lower -> DriveMode.Burst
            else -> DriveMode.Single
        }
        return driveMode to timerMode
    }

    private fun driveAndTimerFromUsbRaw(
        rawValue: Long,
        options: List<Long> = emptyList(),
    ): Pair<DriveMode, TimerMode> {
        return if (isLegacyOlympusDriveLayout(options)) {
            when (rawValue.toInt()) {
                1, 3 -> DriveMode.Single to TimerMode.Off
                17, 33, 35, 2 -> DriveMode.Burst to TimerMode.Off
                18 -> DriveMode.Silent to TimerMode.Off
                34, 4 -> DriveMode.SilentBurst to TimerMode.Off
                20, 21, 37, 36 -> DriveMode.Single to TimerMode.Timer
                5, 6 -> DriveMode.Silent to TimerMode.Timer
                22, 38, 48 -> DriveMode.Burst to TimerMode.BurstTimer
                else -> driveAndTimerFromRaw(formatOlympusDriveMode(rawValue, options))
            }
        } else {
            when (rawValue.toInt()) {
                1 -> DriveMode.Single to TimerMode.Off
                33, 7 -> DriveMode.Burst to TimerMode.Off
                39 -> DriveMode.Silent to TimerMode.Off
                40, 41, 67, 72, 73 -> DriveMode.SilentBurst to TimerMode.Off
                4 -> DriveMode.Single to TimerMode.Timer
                5 -> DriveMode.Burst to TimerMode.BurstTimer
                36 -> DriveMode.Silent to TimerMode.Timer
                6 -> DriveMode.Single to TimerMode.Timer
                else -> driveAndTimerFromRaw(formatOlympusDriveMode(rawValue, options))
            }
        }
    }

    private fun resolveUsbDriveModeRaw(
        driveMode: DriveMode,
        timerMode: TimerMode,
        options: List<Long>,
        currentRaw: Long,
    ): Long? {
        if (options.isEmpty()) {
            return currentRaw.takeIf { it > 0L }
        }

        fun firstAvailable(vararg preferred: Long): Long? {
            return preferred.firstOrNull { candidate -> candidate in options } ?: options.firstOrNull()
        }

        if (isLegacyOlympusDriveLayout(options)) {
            return when (timerMode) {
                TimerMode.BurstTimer -> when (driveMode) {
                    DriveMode.Silent,
                    DriveMode.SilentBurst -> firstAvailable(48L, 38L, 22L)
                    else -> firstAvailable(22L, 38L, 48L)
                }
                TimerMode.Timer -> when (driveMode) {
                    DriveMode.Silent,
                    DriveMode.SilentBurst -> firstAvailable(5L, 6L, 48L)
                    else -> firstAvailable(20L, 21L, 37L, 36L, 22L)
                }
                TimerMode.Off -> when (driveMode) {
                    DriveMode.Single -> firstAvailable(1L, 3L)
                    DriveMode.Silent -> firstAvailable(18L)
                    DriveMode.Burst -> firstAvailable(17L, 33L, 35L, 2L)
                    DriveMode.SilentBurst -> firstAvailable(34L, 4L)
                }
            } ?: currentRaw.takeIf { it in options }
        }

        val preferred = when (timerMode) {
            TimerMode.BurstTimer -> firstAvailable(5L)
            TimerMode.Timer -> when (driveMode) {
                DriveMode.Silent,
                DriveMode.SilentBurst -> firstAvailable(36L, 4L)
                else -> firstAvailable(4L, 36L)
            }
            TimerMode.Off -> when (driveMode) {
                DriveMode.Single -> firstAvailable(1L)
                DriveMode.Silent -> firstAvailable(39L, 36L)
                DriveMode.Burst -> firstAvailable(33L, 7L)
                DriveMode.SilentBurst -> firstAvailable(40L, 67L, 72L, 73L, 41L, 36L)
            }
        }
        return preferred ?: currentRaw.takeIf { it in options }
    }

    private fun resolveDriveModeRaw(
        driveMode: DriveMode,
        timerMode: TimerMode,
        options: List<String>,
        currentRaw: String,
    ): String? {
        if (options.isEmpty()) {
            return currentRaw.ifBlank { null }
        }
        val timerCandidates = when (timerMode) {
            TimerMode.Off -> options.filterNot {
                it.contains("selftimer", ignoreCase = true) || it.contains("timer", ignoreCase = true)
            }
            TimerMode.Timer -> options.filter {
                (it.contains("selftimer", ignoreCase = true) || it.contains("timer", ignoreCase = true)) &&
                    !it.contains("customselftimer", ignoreCase = true) &&
                    !it.contains("burst", ignoreCase = true)
            }
            TimerMode.BurstTimer -> options.filter {
                it.contains("customselftimer", ignoreCase = true) ||
                    (it.contains("timer", ignoreCase = true) && it.contains("burst", ignoreCase = true))
            }
        }.ifEmpty { options }

        fun String.isBurstLike(): Boolean {
            val lower = lowercase()
            return "seq" in lower || "burst" in lower || "continuous" in lower || "procap" in lower || "pro capture" in lower
        }

        val preferred = when (driveMode) {
            DriveMode.SilentBurst -> timerCandidates.firstOrNull { it.contains("silent", true) && it.isBurstLike() }
            DriveMode.Silent -> timerCandidates.firstOrNull { it.contains("silent", true) && !it.isBurstLike() }
            DriveMode.Burst -> timerCandidates.firstOrNull { !it.contains("silent", true) && it.isBurstLike() }
            DriveMode.Single -> timerCandidates.firstOrNull { !it.contains("silent", true) && !it.isBurstLike() }
        }
        return preferred ?: timerCandidates.firstOrNull() ?: currentRaw.ifBlank { null }
    }

    private fun isUiAutoValue(value: String): Boolean {
        return value.equals("Auto", ignoreCase = true) ||
            value.equals("AUTO", ignoreCase = true) ||
            value.equals("AWB", ignoreCase = true) ||
            value.equals(SYNTHETIC_APERTURE_AUTO, ignoreCase = true) ||
            value.equals(SYNTHETIC_SHUTTER_AUTO, ignoreCase = true)
    }

    private fun autoImportMarkerKey(ssid: String?): String {
        val normalizedSsid = ssid?.trim()?.ifBlank { null } ?: "default"
        return "$AUTO_IMPORT_MARKER_PREFIX$normalizedSsid"
    }

    private fun autoImportSortKey(image: CameraImage): String = "${image.captureDateKey}_${image.fileName}"

    private suspend fun maybeTriggerArmedAutoImport(images: List<CameraImage>) {
        if (!autoImportArmed) {
            return
        }
        autoImportArmed = false
        val autoImportEnabled = _uiState.value.settings.firstOrNull { it.id == "auto_import" }?.enabled == true
        if (!autoImportEnabled) {
            D.transfer("maybeTriggerArmedAutoImport: auto-import disabled, skipping")
            return
        }
        if (_uiState.value.autoImportConfig.importTiming == "manual") {
            D.transfer("maybeTriggerArmedAutoImport: import timing is manual, skipping")
            return
        }
        startAutoImportFromImages(images)
    }

    private suspend fun startAutoImportFromImages(images: List<CameraImage>) {
        if (images.isEmpty()) {
            D.transfer("startAutoImportFromImages: no images available")
            return
        }

        val config = _uiState.value.autoImportConfig
        val newestFirst = images.sortedByDescending(::autoImportSortKey)
        val skipDuplicates = _uiState.value.settings.firstOrNull { it.id == "import_skip_duplicates" }?.enabled != false
        val importNewOnly = _uiState.value.settings.firstOrNull { it.id == "import_new_only" }?.enabled != false

        val alreadyDownloaded = loadDownloadedFileNames()
        updateUiState { current -> current.copy(
            transferState = current.transferState.copy(
                downloadedFileNames = alreadyDownloaded,
                downloadedCount = alreadyDownloaded.size,
            ),
        ) }
        val lastImportedMarker = preferencesRepository.loadStringPref(
            autoImportMarkerKey(_uiState.value.selectedCameraSsid),
            "",
        )
        val selectedSsid = _uiState.value.selectedCameraSsid
        val sinceLaunchBaseline = if (config.importTiming == "since_launch") {
            resolveSinceLaunchImportBaseline(newestFirst, selectedSsid)
        } else {
            null
        }

        val formatFiltered = newestFirst.filter { image ->
            when (config.fileFormat) {
                "jpeg" -> image.isJpeg
                "raw" -> image.isRaw
                "jpeg_raw" -> image.isJpeg || image.isRaw
                else -> image.isJpeg
            }
        }
        val newOnlyFiltered = if (importNewOnly && lastImportedMarker.isNotBlank()) {
            formatFiltered.filter { autoImportSortKey(it) > lastImportedMarker }
        } else {
            formatFiltered
        }
        val launchFiltered = if (config.importTiming == "since_launch" && !sinceLaunchBaseline.isNullOrBlank()) {
            newOnlyFiltered.filter { autoImportSortKey(it) > sinceLaunchBaseline }
        } else {
            newOnlyFiltered
        }
        val toDownload = if (skipDuplicates) {
            launchFiltered.filter { it.fileName !in alreadyDownloaded }
        } else {
            launchFiltered
        }

        if (toDownload.isEmpty()) {
            D.transfer(
                "startAutoImportFromImages: no new images " +
                    "(format=${formatFiltered.size}, newOnly=${newOnlyFiltered.size}, sinceLaunch=${launchFiltered.size}, downloaded=${alreadyDownloaded.size})",
            )
            return
        }

        pendingAutoImportMarker = toDownload.maxByOrNull(::autoImportSortKey)?.let(::autoImportSortKey)
        pendingAutoImportMarkerSsid = _uiState.value.selectedCameraSsid

        D.transfer("startAutoImportFromImages: starting background download of ${toDownload.size} images")
        prepareWifiTransferPipeline()
        dev.dblink.core.download.ImageDownloadService.startDownload(
            getApplication<Application>(),
            toDownload,
            config.saveLocation,
            _uiState.value.selectedCardSlotSource,
        )
        updateUiState { current -> current.copy(
            transferState = current.transferState.copy(
                images = newestFirst,
                isDownloading = true,
                batchDownloadTotal = toDownload.size,
                batchDownloadCurrent = 0,
                backgroundTransferRunning = true,
                backgroundTransferProgress = "Auto-importing ${toDownload.size} images...",
                backgroundTransferTotal = toDownload.size,
                backgroundTransferCurrent = 0,
                downloadProgress = "Auto-importing ${toDownload.size} images...",
            ),
        ) }
    }

    private fun resolveWakeTargetBleName(
        savedProfile: SavedCameraProfile?,
        ssid: String,
    ): String? {
        return savedProfile?.bleName?.takeIf { it.isNotBlank() }
            ?: CameraNameNormalizer.extractPairingToken(ssid)
    }

    private fun resolveSinceLaunchImportBaseline(
        newestFirst: List<CameraImage>,
        ssid: String?,
    ): String? {
        val normalizedSsid = ssid?.takeIf { it.isNotBlank() } ?: return null
        sessionLaunchImportBaselineBySsid[normalizedSsid]?.let { return it }
        val baseline = newestFirst.maxByOrNull(::autoImportSortKey)?.let(::autoImportSortKey)
        if (!baseline.isNullOrBlank()) {
            sessionLaunchImportBaselineBySsid[normalizedSsid] = baseline
            D.transfer("resolveSinceLaunchImportBaseline: captured session baseline $baseline for $normalizedSsid")
        }
        return baseline
    }

    private suspend fun loadPersistedUiState() {
        preferencesRepository.migrateLegacyCameraPrefsIfNeeded()
        val persistedSettings = preferencesRepository.loadSettings(initialWorkspace.settings)
        val trackingState = GeoTagTrackingService.state.value
        val persistedGeoTagging = preferencesRepository.loadGeoTagging().copy(
            sessionActive = trackingState.isRunning,
            statusLabel = when {
                trackingState.isRunning -> trackingState.statusLabel
                else -> "Ready"
            },
        )
        val savedCameras = preferencesRepository.loadSavedCameraProfiles()
        val selectedCameraSsid = preferencesRepository.loadSelectedCameraSsid()
        val selectedCardSlotSource = savedCameras.firstOrNull { it.ssid == selectedCameraSsid }?.playTargetSlot
        val selectedLanguageTag = AppLanguageManager.selectedLanguageTag(getApplication())
        val importConfig = AutoImportConfig(
            saveLocation = preferencesRepository.loadStringPref("import_save_location", "Pictures/db link"),
            fileFormat = preferencesRepository.loadStringPref("import_file_format", "jpeg"),
            importTiming = preferencesRepository.loadStringPref("import_timing", "manual"),
        )
        val libraryCompatibilityMode = LibraryCompatibilityMode.fromPreferenceValue(
            preferencesRepository.loadStringPref(
                "library_compatibility_mode",
                LibraryCompatibilityMode.HighSpeed.preferenceValue,
            ),
        )
        val usbTetheringMode = UsbTetheringMode.fromPreferenceValue(
            preferencesRepository.loadStringPref(
                "usb_tethering_mode",
                UsbTetheringMode.Om.preferenceValue,
            ),
        )
        val tetherSaveTarget = TetherSaveTarget.fromPreferenceValue(
            preferencesRepository.loadStringPref(
                "tether_save_target",
                TetherSaveTarget.SdAndPhone.preferenceValue,
            ),
        )
        val savedTetherPhoneImportFormat = preferencesRepository.loadStringPref(
            "tether_phone_import_format",
            TetherPhoneImportFormat.JpegOnly.preferenceValue,
        )
        val tetherPhoneImportFormat = TetherPhoneImportFormat.fromPreferenceValue(savedTetherPhoneImportFormat)
        val captureReviewDurationSeconds = normalizeCaptureReviewDurationSeconds(
            preferencesRepository.loadStringPref(
                "capture_review_duration_seconds",
                DEFAULT_CAPTURE_REVIEW_DURATION_SECONDS.toString(),
            ).toIntOrNull() ?: DEFAULT_CAPTURE_REVIEW_DURATION_SECONDS,
        )
        val geoConfig = GeotagConfig(
            matchWindowMinutes = preferencesRepository.loadStringPref("geotag_match_window", "2").toIntOrNull() ?: 2,
            matchMethod = preferencesRepository.loadStringPref("geotag_match_method", "interpolation"),
            locationSource = preferencesRepository.loadStringPref("geotag_location_source", "device_gps"),
            writeAltitude = preferencesRepository.loadStringPref("geotag_write_altitude", "true") == "true",
        )
        D.pref(
            "loadPersistedUiState: savedCameras=${savedCameras.size}, " +
                "selectedCameraSsid=${selectedCameraSsid ?: "<none>"}",
        )
        omCaptureUsbManager.setUsbTetheringProfile(usbTetheringMode.toUsbTetheringProfile())
        updateUiState { current ->
            current.copy(
                settings = persistedSettings,
                geoTagging = persistedGeoTagging,
                showProtocolWorkbench = resolveWorkbenchVisibility(persistedSettings),
                hasSavedCamera = savedCameras.isNotEmpty(),
                savedCameras = savedCameras,
                selectedCameraSsid = selectedCameraSsid,
                selectedCardSlotSource = selectedCardSlotSource,
                selectedLanguageTag = selectedLanguageTag,
                autoImportConfig = importConfig,
                libraryCompatibilityMode = libraryCompatibilityMode,
                usbTetheringMode = usbTetheringMode,
                geotagConfig = geoConfig,
                tetherSaveTarget = tetherSaveTarget,
                tetherPhoneImportFormat = tetherPhoneImportFormat,
                captureReviewDurationSeconds = captureReviewDurationSeconds,
            )
        }
        if (savedTetherPhoneImportFormat != tetherPhoneImportFormat.preferenceValue) {
            viewModelScope.launch {
                preferencesRepository.saveStringPref(
                    "tether_phone_import_format",
                    tetherPhoneImportFormat.preferenceValue,
                )
            }
        }
        refreshOmCaptureStudioState()
        deepSkyLiveStackCoordinator.updateSkyHintContext(persistedGeoTagging.latestSample, null)
        // Startup connection stays user-driven. If the phone is already on camera
        // Wi-Fi, the Wi-Fi observer handles the session without a competing launch path.
    }

    private fun pushGeoTagSample(
        sample: GeoTagLocationSample,
        statusLabel: String,
    ) {
        val updatedGeoTagging = _uiState.value.geoTagging.copy(
            latestSample = sample,
            samples = (_uiState.value.geoTagging.samples + sample)
                .sortedByDescending { it.capturedAtMillis }
                .distinctBy { it.capturedAtMillis to it.latitude to it.longitude }
                .take(64),
            statusLabel = statusLabel,
            totalPinsCaptured = _uiState.value.geoTagging.totalPinsCaptured + 1,
        )
        updateUiState { it.copy(geoTagging = updatedGeoTagging) }
        persistGeoTagging(updatedGeoTagging)
        refreshMatchedGeoTags()
        syncGeoTagStatusNotification(updatedGeoTagging)
        deepSkyLiveStackCoordinator.updateSkyHintContext(sample, null)
    }

    private fun syncGeoTagTrackingState(state: GeoTagTrackingState) {
        val current = _uiState.value.geoTagging
        val latestChanged = current.latestSample?.capturedAtMillis != state.latestSample?.capturedAtMillis
        val nextSnapshot = current.copy(
            sessionActive = state.isRunning,
            statusLabel = state.statusLabel,
            latestSample = state.latestSample ?: current.latestSample,
            samples = if (state.samples.isNotEmpty()) state.samples else current.samples,
            totalPinsCaptured = maxOf(current.totalPinsCaptured, state.totalPinsCaptured),
        )
        if (nextSnapshot == current) {
            return
        }
        updateUiState { it.copy(geoTagging = nextSnapshot) }
        if (latestChanged) {
            nextSnapshot.latestSample?.let { sample ->
                refreshMatchedGeoTags()
                deepSkyLiveStackCoordinator.updateSkyHintContext(sample, null)
            }
        }
        if (!state.isRunning) {
            persistGeoTagging(nextSnapshot)
        }
        syncGeoTagStatusNotification(nextSnapshot)
    }

    private fun syncGeoTagStatusNotification(snapshot: GeoTaggingSnapshot = _uiState.value.geoTagging) {
        if (snapshot.sessionActive) {
            geoTagStatusNotifier.cancel()
            return
        }
        geoTagStatusNotifier.update(snapshot)
    }

    private fun persistGeoTagging(snapshot: GeoTaggingSnapshot) {
        viewModelScope.launch {
            preferencesRepository.saveGeoTagging(snapshot)
        }
    }

    private fun updatePreviewDetails(image: CameraImage, imageBytes: ByteArray) {
        val geoTagInfo = extractPreviewGeoTag(imageBytes)
        if (geoTagInfo == null) {
            return
        }
        val transferState = _uiState.value.transferState
        if (transferState.matchedGeoTags[image.fileName] == geoTagInfo) {
            return
        }
        updateUiState { it.copy(
            transferState = transferState.copy(
                matchedGeoTags = transferState.matchedGeoTags + (image.fileName to geoTagInfo),
            ),
        ) }
    }

    private fun extractPreviewGeoTag(imageBytes: ByteArray): ImageGeoTagInfo? {
        return runCatching {
            val exif = ByteArrayInputStream(imageBytes).use { inputStream ->
                ExifInterface(inputStream)
            }
            val photoCapturedAtMillis = readExifCapturedAtMillis(exif)
            val latLong = exif.latLong
            if (latLong != null) {
                return@runCatching ImageGeoTagInfo(
                    latitude = latLong[0],
                    longitude = latLong[1],
                    matchedSampleAtMillis = photoCapturedAtMillis ?: 0L,
                    photoCapturedAtMillis = photoCapturedAtMillis,
                    sourceLabel = "Embedded GPS metadata",
                    altitudeMeters = exif.getAltitude(Double.NaN).takeUnless { it.isNaN() },
                )
            }

            if (!isAutoGeoMatchEnabled()) {
                return@runCatching null
            }

            val match = photoCapturedAtMillis?.let { capturedAt ->
                GeoTagMatcher.matchNearestSample(
                    photoCapturedAtMillis = capturedAt,
                    samples = _uiState.value.geoTagging.samples,
                    clockOffsetMinutes = _uiState.value.geoTagging.clockOffsetMinutes,
                    maxDistanceMinutes = _uiState.value.geotagConfig.matchWindowMinutes,
                )
            } ?: return@runCatching null

            ImageGeoTagInfo(
                latitude = match.latitude,
                longitude = match.longitude,
                matchedSampleAtMillis = match.capturedAtMillis,
                photoCapturedAtMillis = photoCapturedAtMillis,
                sourceLabel = "Matched from phone geotag log",
                placeName = match.placeName,
                altitudeMeters = match.altitude,
            )
        }.getOrNull()
    }

    private fun decodeTransferPreviewBitmap(imageBytes: ByteArray): Bitmap? {
        decodeSampledPreviewBitmap(imageBytes)?.let { return it }
        decodePlatformPreviewBitmap(imageBytes)?.let { return it }
        extractTiffPreviewBytes(imageBytes)?.let { previewBytes ->
            decodeSampledPreviewBitmap(previewBytes)?.let { return it }
            decodePlatformPreviewBitmap(previewBytes)?.let { return it }
        }
        extractExifThumbnailBytes(imageBytes)?.let { thumbnailBytes ->
            decodeSampledPreviewBitmap(thumbnailBytes)?.let { return it }
            decodePlatformPreviewBitmap(thumbnailBytes)?.let { return it }
        }
        extractEmbeddedJpegPreview(imageBytes)?.let { embeddedPreview ->
            decodeSampledPreviewBitmap(embeddedPreview)?.let { return it }
            decodePlatformPreviewBitmap(embeddedPreview)?.let { return it }
        }
        return null
    }

    private fun decodePlatformPreviewBitmap(imageBytes: ByteArray): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null
        }
        return runCatching {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(imageBytes))
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val longestSide = maxOf(info.size.width, info.size.height)
                if (longestSide > 2048) {
                    val scale = 2048f / longestSide.toFloat()
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
    }

    private fun extractTiffPreviewBytes(imageBytes: ByteArray): ByteArray? {
        if (imageBytes.size < 8) {
            return null
        }
        val byteOrder = when {
            imageBytes[0] == 'I'.code.toByte() && imageBytes[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            imageBytes[0] == 'M'.code.toByte() && imageBytes[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> return null
        }
        val firstIfdOffset = readTiffUInt(imageBytes, 4, byteOrder)?.toInt() ?: return null
        val pendingOffsets = ArrayDeque<Int>().apply {
            add(firstIfdOffset)
        }
        val visitedOffsets = linkedSetOf<Int>()
        val previewCandidates = linkedSetOf<Pair<Int, Int>>()

        while (pendingOffsets.isNotEmpty()) {
            val ifdOffset = pendingOffsets.removeFirst()
            if (!visitedOffsets.add(ifdOffset) || ifdOffset <= 0 || ifdOffset + 2 > imageBytes.size) {
                continue
            }
            val entryCount = readTiffUShort(imageBytes, ifdOffset, byteOrder) ?: continue
            var jpegOffset: Int? = null
            var jpegLength: Int? = null
            var stripOffsets: LongArray? = null
            var stripByteCounts: LongArray? = null
            var tileOffsets: LongArray? = null
            var tileByteCounts: LongArray? = null
            for (entryIndex in 0 until entryCount) {
                val entryOffset = ifdOffset + 2 + entryIndex * 12
                if (entryOffset + 12 > imageBytes.size) {
                    break
                }
                val tag = readTiffUShort(imageBytes, entryOffset, byteOrder) ?: continue
                val type = readTiffUShort(imageBytes, entryOffset + 2, byteOrder) ?: continue
                val count = readTiffUInt(imageBytes, entryOffset + 4, byteOrder)?.toInt() ?: continue
                val values = readTiffValues(imageBytes, entryOffset + 8, type, count, byteOrder)
                when (tag) {
                    0x0201 -> jpegOffset = values.firstOrNull()?.toInt()
                    0x0202 -> jpegLength = values.firstOrNull()?.toInt()
                    0x0111 -> stripOffsets = values
                    0x0117 -> stripByteCounts = values
                    0x0144 -> tileOffsets = values
                    0x0145 -> tileByteCounts = values
                    0x014A, 0x8769, 0x8825, 0xA005 -> {
                        values.mapTo(pendingOffsets) { it.toInt() }
                    }
                }
            }

            if (jpegOffset != null && jpegLength != null) {
                previewCandidates += jpegOffset to jpegLength
            }
            if (stripOffsets != null && stripByteCounts != null && stripOffsets.size == stripByteCounts.size) {
                stripOffsets.indices.forEach { index ->
                    previewCandidates += stripOffsets[index].toInt() to stripByteCounts[index].toInt()
                }
            }
            if (tileOffsets != null && tileByteCounts != null && tileOffsets.size == tileByteCounts.size) {
                tileOffsets.indices.forEach { index ->
                    previewCandidates += tileOffsets[index].toInt() to tileByteCounts[index].toInt()
                }
            }

            val nextIfdOffset = readTiffUInt(imageBytes, ifdOffset + 2 + entryCount * 12, byteOrder)?.toInt()
            if (nextIfdOffset != null && nextIfdOffset > 0) {
                pendingOffsets += nextIfdOffset
            }
        }

        return previewCandidates
            .asSequence()
            .filter { (offset, length) ->
                offset >= 0 &&
                    length > 32 &&
                    offset + length <= imageBytes.size
            }
            .sortedByDescending { (_, length) -> length }
            .mapNotNull { (offset, length) ->
                imageBytes.copyOfRange(offset, offset + length).let { candidate ->
                    if (candidate.size >= 2 &&
                        candidate[0] == 0xFF.toByte() &&
                        candidate[1] == 0xD8.toByte()
                    ) {
                        candidate
                    } else {
                        extractTiffPreviewBytes(candidate) ?: extractEmbeddedJpegPreview(candidate)
                    }
                }
            }
            .firstOrNull()
    }

    private fun decodeSampledPreviewBitmap(
        imageBytes: ByteArray,
        maxDimension: Int = 2048,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        val options = BitmapFactory.Options()
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            var sampleSize = 1
            val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
            while (longestSide / sampleSize > maxDimension) {
                sampleSize *= 2
            }
            options.inSampleSize = sampleSize.coerceAtLeast(1)
        }
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
    }

    private fun extractExifThumbnailBytes(imageBytes: ByteArray): ByteArray? {
        return runCatching {
            ByteArrayInputStream(imageBytes).use { inputStream ->
                val exif = ExifInterface(inputStream)
                if (exif.hasThumbnail()) {
                    exif.thumbnailBytes
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun extractEmbeddedJpegPreview(imageBytes: ByteArray): ByteArray? {
        var bestStart = -1
        var bestEnd = -1
        var index = 0
        while (index < imageBytes.size - 1) {
            if (imageBytes[index] == 0xFF.toByte() && imageBytes[index + 1] == 0xD8.toByte()) {
                var end = index + 2
                while (end < imageBytes.size - 1) {
                    if (imageBytes[end] == 0xFF.toByte() && imageBytes[end + 1] == 0xD9.toByte()) {
                        val candidateEnd = end + 2
                        if (candidateEnd - index > bestEnd - bestStart) {
                            bestStart = index
                            bestEnd = candidateEnd
                        }
                        break
                    }
                    end++
                }
                index = end
            } else {
                index++
            }
        }
        return if (bestStart >= 0 && bestEnd > bestStart) {
            imageBytes.copyOfRange(bestStart, bestEnd)
        } else {
            null
        }
    }

    private fun readTiffUShort(
        data: ByteArray,
        offset: Int,
        byteOrder: ByteOrder,
    ): Int? {
        if (offset < 0 || offset + 2 > data.size) {
            return null
        }
        return ByteBuffer.wrap(data, offset, 2).order(byteOrder).short.toInt() and 0xFFFF
    }

    private fun readTiffUInt(
        data: ByteArray,
        offset: Int,
        byteOrder: ByteOrder,
    ): Long? {
        if (offset < 0 || offset + 4 > data.size) {
            return null
        }
        return ByteBuffer.wrap(data, offset, 4).order(byteOrder).int.toLong() and 0xFFFFFFFFL
    }

    private fun readTiffValues(
        data: ByteArray,
        valueFieldOffset: Int,
        type: Int,
        count: Int,
        byteOrder: ByteOrder,
    ): LongArray {
        val typeSize = when (type) {
            1, 2, 6, 7 -> 1
            3, 8 -> 2
            4, 9, 13 -> 4
            5, 10, 12 -> 8
            16, 17, 18 -> 8
            else -> return longArrayOf()
        }
        val totalBytes = typeSize * count
        val dataOffset = if (totalBytes <= 4) {
            valueFieldOffset
        } else {
            readTiffUInt(data, valueFieldOffset, byteOrder)?.toInt() ?: return longArrayOf()
        }
        if (dataOffset < 0 || dataOffset + totalBytes > data.size) {
            return longArrayOf()
        }
        return LongArray(count) { index ->
            val elementOffset = dataOffset + index * typeSize
            when (type) {
                1, 2, 6, 7 -> data[elementOffset].toLong() and 0xFFL
                3, 8 -> readTiffUShort(data, elementOffset, byteOrder)?.toLong() ?: 0L
                4, 9, 13 -> readTiffUInt(data, elementOffset, byteOrder) ?: 0L
                16, 17, 18 -> {
                    if (elementOffset + 8 > data.size) {
                        0L
                    } else {
                        ByteBuffer.wrap(data, elementOffset, 8).order(byteOrder).long
                    }
                }
                else -> 0L
            }
        }
    }

    private fun readExifCapturedAtMillis(exif: ExifInterface): Long? {
        val candidateValues = listOf(
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED),
            exif.getAttribute(ExifInterface.TAG_DATETIME),
        )
        val formatter = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        formatter.timeZone = TimeZone.getDefault()
        return candidateValues.firstNotNullOfOrNull { value ->
            value?.let { formatter.parse(it)?.time }
        }
    }

    private fun markPreviewUnavailable(fileName: String) {
        val current = _uiState.value.transferState
        if (fileName in current.previewUnavailable) return
        updateUiState { it.copy(
            transferState = current.copy(
                previewUnavailable = current.previewUnavailable + fileName,
            ),
        ) }
    }

    private fun clearPreviewUnavailable(fileName: String) {
        val current = _uiState.value.transferState
        if (fileName !in current.previewUnavailable) return
        updateUiState { it.copy(
            transferState = current.copy(
                previewUnavailable = current.previewUnavailable - fileName,
            ),
        ) }
    }

    private fun refreshMatchedGeoTags() {
        val currentTransfer = _uiState.value.transferState
        if (currentTransfer.matchedGeoTags.isEmpty()) return

        val refreshed = currentTransfer.matchedGeoTags.mapNotNull { (fileName, geoTagInfo) ->
            if (geoTagInfo.sourceLabel.startsWith("Embedded", ignoreCase = true)) {
                fileName to geoTagInfo
            } else if (!isAutoGeoMatchEnabled()) {
                null
            } else {
                val photoTime = geoTagInfo.photoCapturedAtMillis ?: return@mapNotNull null
                GeoTagMatcher.matchNearestSample(
                    photoCapturedAtMillis = photoTime,
                    samples = _uiState.value.geoTagging.samples,
                    clockOffsetMinutes = _uiState.value.geoTagging.clockOffsetMinutes,
                    maxDistanceMinutes = _uiState.value.geotagConfig.matchWindowMinutes,
                )?.let { sample ->
                    fileName to geoTagInfo.copy(
                        latitude = sample.latitude,
                        longitude = sample.longitude,
                        matchedSampleAtMillis = sample.capturedAtMillis,
                        sourceLabel = "Matched from phone geotag log",
                    )
                }
            }
        }.toMap()

        updateUiState { it.copy(
            transferState = currentTransfer.copy(matchedGeoTags = refreshed),
        ) }
    }

    private fun isAutoGeoMatchEnabled(): Boolean {
        return _uiState.value.settings.firstOrNull { it.id == "time_match_geotags" }?.enabled == true
    }

    private fun resolveWorkbenchVisibility(settings: List<SettingItem>): Boolean {
        val developerToggle = settings.firstOrNull { it.id == "debug_workbench" }?.enabled ?: true
        return appConfig.showProtocolWorkbench && developerToggle
    }
}
