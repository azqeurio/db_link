package dev.pl36.cameralink.core.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.pl36.cameralink.BuildConfig
import dev.pl36.cameralink.core.logging.D
import dev.pl36.cameralink.core.model.TetherPhoneImportFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

data class OmCaptureUsbLibraryItem(
    val handle: Int,
    val fileName: String,
    val fileSize: Long,
    val width: Int,
    val height: Int,
    val storageId: Int,
    val captureTimestampMillis: Long?,
    val captureDate: String,
    val modificationDate: String,
    val isJpeg: Boolean,
    val isRaw: Boolean,
    val hasThumbnail: Boolean,
)

class OmCaptureUsbManager(
    context: Context,
) : AutoCloseable {
    private companion object {
        const val CAPTURE_OBJECT_TIMEOUT_MS = 15_000L
        const val CAPTURE_COLLECTION_TIMEOUT_MS = 4_000L
        const val CAPTURE_COMPLETE_GRACE_MS = 800L
        const val CAPTURE_FALLBACK_RECENT_WINDOW_MS = 15_000L
        const val CAPTURE_FALLBACK_HANDLE_SCAN_LIMIT = 96
        const val SPECIFIC_HANDLE_PAIR_SCAN_LIMIT = 32
        const val EVENT_POLL_TIMEOUT_MS = 500
        const val EXTENDED_EVENT_POLL_TIMEOUT_MS = 180
        const val EXTENDED_EVENT_FALLBACK_MISS_THRESHOLD = 3
        const val EXTENDED_EVENT_PROBE_INTERVAL_MS = 500L
        const val GET_DEVICE_INFO_MAX_ATTEMPTS = 3
        const val GET_DEVICE_INFO_RESPONSE_TIMEOUT_MS = 1_500
        const val SESSION_BOOTSTRAP_MAX_ATTEMPTS = 2
        const val SESSION_BOOTSTRAP_RETRY_DELAY_MS = 350L
        const val SESSION_PC_MODE_READY_TIMEOUT_MS = 12_000L
        const val SESSION_POST_PC_MODE_SETTLE_MS = 220L
        const val SESSION_POST_PC_MODE_REOPEN_DELAY_MS = 700L
        const val SESSION_STORAGE_RETRY_DELAY_MS = 180L
        // Some bodies confirm preview readiness via an event callback, while
        // others also succeed with fixed-size frame polling during startup.
        const val LIVE_VIEW_READY_EVENT_TIMEOUT_MS = 4_000L
        const val LIVE_VIEW_READY_STATE_PROBE_INTERVAL_MS = 1_000L
        const val LIVE_VIEW_MODE_SETTLE_MS = 150L
        const val LIVE_VIEW_STREAMING_MODE_SETTLE_MS = 200L
        // Give each advertised preview-size candidate enough budget for the
        // callback wait plus the fixed-size empty-frame polling window.
        // camera_link_session (6).log showed the
        // fixed global timeout was expiring before the final 640x480 mode could
        // ever be attempted.
        const val LIVE_VIEW_BOOTSTRAP_MIN_TIMEOUT_MS = 14_000L
        const val LIVE_VIEW_BOOTSTRAP_TIMEOUT_PER_MODE_MS = 6_000L
        const val LIVE_VIEW_BOOTSTRAP_REQUEST_TIMEOUT_MS = 2_000
        const val LIVE_VIEW_BOOTSTRAP_RETRY_DELAY_MS = 140L
        const val LIVE_VIEW_BOOTSTRAP_POLL_DELAY_MS = 40L
        const val LIVE_VIEW_BOOTSTRAP_EMPTY_THRESHOLD_PER_MODE = 25
        const val LIVE_VIEW_FRAME_INTERVAL_MS = 34L
        const val LIVE_VIEW_EMPTY_FRAME_BACKOFF_MS = 80L
        const val LIVE_VIEW_EMPTY_FRAME_LOG_INTERVAL = 12
        const val LIVE_VIEW_EMPTY_FRAME_REARM_THRESHOLD = 18
        const val LIVE_VIEW_EMPTY_FRAME_STOP_THRESHOLD = 90
        const val LIVE_VIEW_FALLBACK_STATE_SAMPLE_INTERVAL = 24
        const val LIVE_VIEW_SOFT_RECOVERY_TIMEOUT_MS = 2_500L
        const val LIVE_VIEW_SOFT_RECOVERY_COOLDOWN_MS = 1_500L
        const val EVENT_LOOP_SILENT_BACKOFF_MS = 24L
        // Derived from observed device behavior and OM-1 logs:
        // D084 is the RecView/live-view state property. It transitions to 7
        // when RecView becomes usable, and is not a general UI-facing mode-dial
        // property even though body-dial changes also make it churn.
        const val LIVE_VIEW_REC_VIEW_STATE_PROP = 0xD084
        const val LIVE_VIEW_REC_VIEW_READY_VALUE = 7
        const val LIVE_VIEW_REC_VIEW_OM1_PENDING_VALUE = 8
        const val LIVE_VIEW_REC_VIEW_PROGRESS_PROP = 0xD121
        const val MODE_DIAL_EVENT_PROP = 0xD062
        const val ISO_SPEED_OM1_FALLBACK_PROP = 0xD005
        const val PERMISSION_TIMEOUT_MS = 15_000L
        const val RAW_PAIR_WINDOW_MS = 1_500L
    }

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(UsbManager::class.java)
    private val permissionAction = "${appContext.packageName}.USB_PERMISSION.OM_CAPTURE"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private val sessionLock = Any()
    private val _runtimeState = MutableStateFlow(OmCaptureUsbRuntimeState())
    private val operationIdCounter = AtomicLong(0)
    private val attachSeenAtMs = ConcurrentHashMap<Int, Long>()

    @Volatile
    private var isClosed = false
    @Volatile
    private var activeOperationId: Long = 0L
    @Volatile
    private var eventEndpointUnreliable = false
    @Volatile
    private var pureFallbackLiveViewPolling = false
    @Volatile
    private var cameraControlOffRecoveryJob: Job? = null
    private var lifecycleReceiverRegistered = false
    private var activeSession: ActiveSession? = null
    private var cachedInitDeviceInfo: CachedInitDeviceInfo? = null
    private var cachedMtpWarmupSnapshot: CachedMtpWarmupSnapshot? = null
    private val transferCandidateCache = ConcurrentHashMap<Int, TransferCandidate>()

    // USB Live View
    private val _usbLiveViewFrame = MutableSharedFlow<Bitmap>(
        replay = 1,
        extraBufferCapacity = 2,
    )
    private var liveViewJob: Job? = null
    private val liveViewPauseLock = Any()
    @Volatile
    private var liveViewPauseDepth = 0

    @Volatile
    var isLiveViewActive: Boolean = false
        private set

    val usbLiveViewFrame: SharedFlow<Bitmap> = _usbLiveViewFrame.asSharedFlow()
    private val _cameraEvents = MutableSharedFlow<OmCaptureUsbCameraEvent>(extraBufferCapacity = 8)
    val cameraEvents: SharedFlow<OmCaptureUsbCameraEvent> = _cameraEvents.asSharedFlow()

    val runtimeState: StateFlow<OmCaptureUsbRuntimeState> = _runtimeState.asStateFlow()
    @Volatile
    private var monitorCaptureEventsDuringLiveView = false

    private data class LiveViewModeCandidate(
        val value: Int,
        val dataType: Int,
        val label: String,
    )

    private data class LiveViewBootstrapResult(
        val frame: ByteArray,
        val modeCandidate: LiveViewModeCandidate?,
    )

    private val usbLifecycleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = extractUsbDevice(intent) ?: return
                    attachSeenAtMs.remove(device.deviceId)
                    clearCachedInitDeviceInfo(device)
                    clearCachedMtpWarmupSnapshot(device)
                    transferCandidateCache.clear()
                    if (!matchesActiveDevice(device)) return
                    usbLog("USB detach detected for ${device.deviceName}")
                    closeActiveSession("usb-detached:${device.deviceName}")
                    updateRuntimeState(
                        nextState = OmCaptureUsbOperationState.Error,
                        statusLabel = "USB camera disconnected during transfer",
                    ) { state ->
                        state.copy(
                            summary = null,
                            canRetry = true,
                            lastActionLabel = "USB device detached",
                        )
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = extractUsbDevice(intent) ?: return
                    if (!isCandidateUsbDevice(device)) return
                    attachSeenAtMs[device.deviceId] = SystemClock.elapsedRealtime()
                    usbLog("USB attach detected for ${device.deviceName}; awaiting explicit tether selection")
                    if (currentActiveSession() == null) {
                        updateRuntimeState(
                            nextState = OmCaptureUsbOperationState.Idle,
                            statusLabel = "OM camera detected. Select Tether to connect.",
                        ) { state ->
                            state.copy(
                                summary = null,
                                canRetry = false,
                                lastActionLabel = "USB camera attached",
                            )
                        }
                    }
                }
            }
        }
    }

    init {
        registerUsbLifecycleReceiver()
    }

    suspend fun inspectConnectedCamera(): Result<OmCaptureUsbSessionSummary> = withContext(Dispatchers.IO) {
        runCatching {
            withOptionalLiveViewTransportPause(reason = "inspect") {
                operationMutex.withLock {
                ensureManagerOpen()
                logUsbInventory()
                updateRuntimeState(
                    nextState = OmCaptureUsbOperationState.Inspecting,
                    statusLabel = "Scanning OM camera over USB...",
                ) { it.copy(canRetry = false) }

                try {
                    val active = connectOrReuseSession("inspect")
                    val isBasicMtp = active.summary.supportedOperationCount < 50
                    val statusMsg = if (isBasicMtp) {
                        "OM camera connected in basic MTP mode — switch USB mode to PTP/Tether on camera"
                    } else {
                        "OM camera ready over USB/PTP"
                    }
                    updateRuntimeState(
                        nextState = OmCaptureUsbOperationState.Idle,
                        statusLabel = statusMsg,
                    ) { state ->
                        state.copy(summary = active.summary, canRetry = false)
                    }
                    active.summary
                } catch (throwable: Throwable) {
                    handleOperationFailure(throwable)
                    throw throwable
                }
                }
            }
        }
    }

    suspend fun captureAndImportLatestImage(
        saveMedia: suspend (fileName: String, data: ByteArray) -> OmCaptureUsbSavedMedia,
        importFormat: TetherPhoneImportFormat,
    ): Result<OmCaptureUsbImportResult> = runSerializedImport(
        startState = OmCaptureUsbOperationState.Capturing,
        startStatus = "Triggering OM camera shutter over USB...",
        operationKind = OperationKind.Capture,
        saveMedia = saveMedia,
        selectionBlock = { active -> captureSelection(active, importFormat) },
    )

    suspend fun importLatestImage(
        saveMedia: suspend (fileName: String, data: ByteArray) -> OmCaptureUsbSavedMedia,
        importFormat: TetherPhoneImportFormat,
    ): Result<OmCaptureUsbImportResult> = runSerializedImport(
        startState = OmCaptureUsbOperationState.WaitObject,
        startStatus = "Resolving latest OM camera image over USB...",
        operationKind = OperationKind.ImportLatest,
        saveMedia = saveMedia,
        selectionBlock = { active -> latestSelection(active, importFormat) },
    )

    suspend fun importObjectHandle(
        handle: Int,
        saveMedia: suspend (fileName: String, data: ByteArray) -> OmCaptureUsbSavedMedia,
        importFormat: TetherPhoneImportFormat,
        resumeLiveViewAfter: Boolean = false,
    ): Result<OmCaptureUsbImportResult> = runSerializedImport(
        startState = OmCaptureUsbOperationState.WaitObject,
        startStatus = "Importing OM camera capture ${handle.toUsbHex()} to the phone...",
        operationKind = OperationKind.ImportHandle,
        saveMedia = saveMedia,
        selectionBlock = { active -> specificHandleSelection(active, handle, importFormat) },
        resumeLiveViewAfter = resumeLiveViewAfter,
    )

    suspend fun importAutoAddedHandle(
        handle: Int,
        saveMedia: suspend (fileName: String, data: ByteArray) -> OmCaptureUsbSavedMedia,
        importFormat: TetherPhoneImportFormat,
        keepLiveViewVisible: Boolean = false,
    ): Result<OmCaptureUsbImportResult?> = withContext(Dispatchers.IO) {
        runCatching {
            val keepPresentation = keepLiveViewVisible && isLiveViewActive
            val pausedLiveView = keepPresentation &&
                pauseLiveViewTransport("auto-import handle ${handle.toUsbHex()}")

            try {
                operationMutex.withLock {
                    ensureManagerOpen()
                    activeOperationId = operationIdCounter.incrementAndGet()
                    val active = connectOrReuseSession(OperationKind.ImportHandle.name)
                    updateRuntimeState(
                        nextState = OmCaptureUsbOperationState.WaitObject,
                        statusLabel = "Importing OM camera capture ${handle.toUsbHex()} to the phone...",
                    ) { state ->
                        state.copy(summary = active.summary, canRetry = false)
                    }

                    try {
                        val requested = loadTransferCandidate(active, handle)
                            ?: throw IllegalStateException(
                                "The OM camera reported object ${handle.toUsbHex()}, but its metadata could not be read.",
                            )
                        val selection = selectAutoImportTransferForExactHandle(active, requested, importFormat)
                        if (selection == null) {
                            usbLog(
                                "Skipping OM camera auto-import handle=${handle.toUsbHex()} " +
                                    "file=${requested.fileName} formatPref=${importFormat.preferenceValue}",
                            )
                            return@withLock null
                        }
                        val primaryDownload = downloadCandidate(
                            active = active,
                            candidate = selection.primary,
                            fetchThumbnail = false,
                        )
                        val companionDownload = selection.companionRaw?.let { raw ->
                            runCatching { downloadCandidate(active, raw, fetchThumbnail = false) }
                                .onFailure { throwable ->
                                    D.err("USB", "Companion RAW download failed for ${raw.fileName}", throwable)
                                }
                                .getOrNull()
                        }
                        saveImportResult(
                            active = active,
                            operationKind = OperationKind.ImportHandle,
                            primaryDownload = primaryDownload,
                            companionDownload = companionDownload,
                            relatedHandles = selection.relatedHandles,
                            saveMedia = saveMedia,
                        )
                    } catch (throwable: Throwable) {
                        handleOperationFailure(throwable)
                        throw throwable
                    }
                }
            } finally {
                if (pausedLiveView) {
                    resumeLiveViewTransport("auto-import handle ${handle.toUsbHex()}")
                }
            }
        }
    }

    suspend fun loadLibraryItems(): Result<List<OmCaptureUsbLibraryItem>> = withContext(Dispatchers.IO) {
        runCatching {
            withOptionalLiveViewTransportPause("usb library load") {
                operationMutex.withLock {
                    ensureManagerOpen()
                    val active = connectOrReuseSession("library-list")
                    val handles = enumerateObjectHandles(active)
                    usbLog("USB library enumerated ${handles.size} object handles")
                    val activeHandleSet = handles.toSet()
                    transferCandidateCache.keys
                        .filterNot { it in activeHandleSet }
                        .forEach(transferCandidateCache::remove)
                    handles
                        .mapNotNull { handle -> loadTransferCandidate(active, handle) }
                        .sortedWith(transferCandidateComparator)
                        .map { candidate -> candidate.toLibraryItem() }
                }
            }
        }
    }

    suspend fun loadLibraryThumbnail(handle: Int): Result<ByteArray?> = withContext(Dispatchers.IO) {
        runCatching {
            withOptionalLiveViewTransportPause("usb library thumbnail ${handle.toUsbHex()}") {
                operationMutex.withLock {
                    ensureManagerOpen()
                    val active = connectOrReuseSession("library-thumb")
                    active.session.getThumb(handle).getOrNull()?.takeIf { it.isNotEmpty() }?.also { bytes ->
                        usbLog(
                            "USB library thumbnail end handle=${handle.toUsbHex()} bytes=${bytes.size}",
                        )
                        return@withLock bytes
                    }
                    val candidate = loadTransferCandidate(active, handle)
                        ?: throw IllegalStateException(
                            "The OM camera object ${handle.toUsbHex()} is no longer available.",
                        )
                    if (!candidate.info.hasThumbnail) {
                        return@withLock null
                    }
                    usbLog(
                        "USB library thumbnail retry file=${candidate.fileName} " +
                            "handle=${candidate.handle.toUsbHex()}",
                    )
                    active.session.getThumb(candidate.handle).getOrElse { throwable ->
                        throw IllegalStateException(
                            "Failed to load thumbnail for ${candidate.fileName}: " +
                                "${throwable.message ?: "thumbnail transfer failed"}",
                            throwable,
                        )
                    }.also { bytes ->
                        usbLog(
                            "USB library thumbnail end file=${candidate.fileName} " +
                                "handle=${candidate.handle.toUsbHex()} bytes=${bytes.size}",
                        )
                    }
                }
            }
        }
    }

    suspend fun loadLibraryPreview(handle: Int): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            withOptionalLiveViewTransportPause("usb library preview ${handle.toUsbHex()}") {
                operationMutex.withLock {
                    ensureManagerOpen()
                    val active = connectOrReuseSession("library-preview")
                    val candidate = loadTransferCandidate(active, handle)
                        ?: throw IllegalStateException(
                            "The OM camera object ${handle.toUsbHex()} is no longer available.",
                        )
                    if (candidate.info.hasThumbnail) {
                        active.session.getThumb(candidate.handle).getOrElse { throwable ->
                            throw IllegalStateException(
                                "Failed to load preview for ${candidate.fileName}: " +
                                    "${throwable.message ?: "preview transfer failed"}",
                                throwable,
                            )
                        }
                    } else {
                        active.session.getObject(candidate.handle).getOrElse { throwable ->
                            throw IllegalStateException(
                                "Failed to load preview for ${candidate.fileName}: " +
                                    "${throwable.message ?: "preview transfer failed"}",
                                throwable,
                            )
                        }
                    }
                }
            }
        }
    }

    suspend fun importLibraryHandle(
        handle: Int,
        saveMedia: suspend (fileName: String, data: ByteArray) -> OmCaptureUsbSavedMedia,
    ): Result<OmCaptureUsbImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            withOptionalLiveViewTransportPause("usb library import ${handle.toUsbHex()}") {
                operationMutex.withLock {
                    ensureManagerOpen()
                    activeOperationId = operationIdCounter.incrementAndGet()
                    val active = connectOrReuseSession(OperationKind.ImportHandle.name)
                    updateRuntimeState(
                        nextState = OmCaptureUsbOperationState.WaitObject,
                        statusLabel = "Importing ${handle.toUsbHex()} from OM camera...",
                    ) { state ->
                        state.copy(summary = active.summary, canRetry = false)
                    }
                    try {
                        val requested = loadTransferCandidate(active, handle)
                            ?: throw IllegalStateException(
                                "The OM camera object ${handle.toUsbHex()} is no longer available.",
                            )
                        val primaryDownload = downloadCandidate(
                            active = active,
                            candidate = requested,
                            fetchThumbnail = false,
                        )
                        saveImportResult(
                            active = active,
                            operationKind = OperationKind.ImportHandle,
                            primaryDownload = primaryDownload,
                            companionDownload = null,
                            relatedHandles = setOf(requested.handle),
                            saveMedia = saveMedia,
                        )
                    } catch (throwable: Throwable) {
                        handleOperationFailure(throwable)
                        throw throwable
                    }
                }
            }
        }
    }

    suspend fun captureToCameraStorage(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (isLiveViewActive) {
                usbLog("Pausing USB live view for capture-only")
                stopUsbLiveView()
            }
            operationMutex.withLock {
                ensureManagerOpen()
                activeOperationId = operationIdCounter.incrementAndGet()
                val active = connectOrReuseSession(OperationKind.CaptureOnly.name)
                updateRuntimeState(
                    nextState = OmCaptureUsbOperationState.Capturing,
                    statusLabel = "Triggering OM camera shutter over USB...",
                ) { state ->
                    state.copy(summary = active.summary, canRetry = false)
                }
                try {
                    val selection = captureSelection(active, TetherPhoneImportFormat.JpegAndRaw)
                    updateRuntimeState(
                        nextState = OmCaptureUsbOperationState.Complete,
                        statusLabel = "Captured ${selection.primary.fileName} to camera storage",
                    ) { state ->
                        state.copy(
                            summary = active.summary,
                            lastActionLabel = "Saved to SD card: ${selection.primary.fileName}",
                            canRetry = false,
                        )
                    }
                } catch (throwable: Throwable) {
                    handleOperationFailure(throwable)
                    throw throwable
                }
            }
        }
    }

    suspend fun runDebugStressCaptureLoop(
        iterations: Int = 10,
        saveMedia: suspend (fileName: String, data: ByteArray) -> OmCaptureUsbSavedMedia,
    ): Result<Unit> = runDebugStressLoop(
        label = "capture+import",
        iterations = iterations,
    ) {
        captureAndImportLatestImage(saveMedia, TetherPhoneImportFormat.JpegAndRaw).getOrThrow()
    }

    suspend fun runDebugStressImportLoop(
        iterations: Int = 10,
        saveMedia: suspend (fileName: String, data: ByteArray) -> OmCaptureUsbSavedMedia,
    ): Result<Unit> = runDebugStressLoop(
        label = "import-latest",
        iterations = iterations,
    ) {
        importLatestImage(saveMedia, TetherPhoneImportFormat.JpegAndRaw).getOrThrow()
    }

    fun clearRuntimeState() {
        updateRuntimeState(
            nextState = OmCaptureUsbOperationState.Idle,
            statusLabel = "USB tether status cleared",
        ) { OmCaptureUsbRuntimeState(statusLabel = "USB tether status cleared") }
    }

    fun setCaptureEventMonitoringEnabled(enabled: Boolean) {
        if (monitorCaptureEventsDuringLiveView == enabled) return
        monitorCaptureEventsDuringLiveView = enabled
        usbLog(
            "USB tether camera-event monitoring " +
                if (enabled) "enabled for auto phone save" else "disabled",
        )
    }

    // ── Camera Property Control ──────────────────────────────────

    /**
     * Descriptor for a single camera property exposed to the UI.
     */
    data class CameraPropertyState(
        val propCode: Int,
        val label: String,
        val currentValue: Long,
        val allowedValues: List<Long>,
        val dataType: Int,
        val isReadOnly: Boolean,
        val formFlag: Int,
        val rangeMin: Long = 0,
        val rangeMax: Long = 0,
        val rangeStep: Long = 0,
    )

    data class TouchFocusResult(
        val focusLocked: Boolean,
        val afFrameNotified: Boolean,
        val focusState: Long?,
        val afResult: Long?,
    )

    /** All controllable properties and their current state. */
    data class CameraPropertiesSnapshot(
        val shutterSpeed: CameraPropertyState? = null,
        val aperture: CameraPropertyState? = null,
        val iso: CameraPropertyState? = null,
        val exposureComp: CameraPropertyState? = null,
        val whiteBalance: CameraPropertyState? = null,
        val focusMode: CameraPropertyState? = null,
        val meteringMode: CameraPropertyState? = null,
        val flashMode: CameraPropertyState? = null,
        val driveMode: CameraPropertyState? = null,
        val imageQuality: CameraPropertyState? = null,
        val exposureMode: CameraPropertyState? = null,
        val extraProperties: List<CameraPropertyState> = emptyList(),
    ) {
        fun allProperties(): List<CameraPropertyState> = listOfNotNull(
            exposureMode, shutterSpeed, aperture, iso, exposureComp,
            whiteBalance, focusMode, meteringMode, flashMode, driveMode, imageQuality,
        ) + extraProperties

        fun findProperty(propCode: Int): CameraPropertyState? {
            return allProperties().firstOrNull { it.propCode == propCode }
        }
    }

    private val _cameraProperties = MutableStateFlow(CameraPropertiesSnapshot())
    val cameraProperties: StateFlow<CameraPropertiesSnapshot> = _cameraProperties.asStateFlow()

    /**
     * Read all supported camera properties from the connected camera.
     * Safe to call while live view is running — pauses transport briefly.
     */
    suspend fun refreshCameraProperties(includeExtras: Boolean = true): Result<CameraPropertiesSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val paused = pauseLiveViewTransport("property-refresh")
            try {
                operationMutex.withLock {
                    ensureManagerOpen()
                    val active = connectOrReuseSession("property-refresh")
                    val snapshot = readAllProperties(active, includeExtras = includeExtras)
                    _cameraProperties.value = snapshot
                    snapshot
                }
            } finally {
                if (paused) resumeLiveViewTransport("property-refresh")
            }
        }
    }

    suspend fun refreshCameraPropertiesForCodes(
        propCodes: Set<Int>,
    ): Result<CameraPropertiesSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            if (propCodes.isEmpty()) {
                return@runCatching _cameraProperties.value
            }
            val expandedCodes = expandDependentCameraPropertyCodes(propCodes)
            val paused = pauseLiveViewTransport("property-refresh-subset")
            try {
                operationMutex.withLock {
                    ensureManagerOpen()
                    val active = connectOrReuseSession("property-refresh-subset")
                    val snapshot = refreshCameraPropertiesSubset(
                        active = active,
                        existing = _cameraProperties.value,
                        propCodes = expandedCodes,
                    )
                    _cameraProperties.value = snapshot
                    snapshot
                }
            } finally {
                if (paused) resumeLiveViewTransport("property-refresh-subset")
            }
        }
    }

    /**
     * Set a single camera property value.
     * Pauses live view transport briefly, sets value, then refreshes affected property.
     */
    suspend fun setCameraProperty(propCode: Int, value: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val paused = pauseLiveViewTransport("property-set")
            try {
                operationMutex.withLock {
                    ensureManagerOpen()
                    val active = connectOrReuseSession("property-set")
                    val desc = active.session.getDevicePropDesc(propCode).getOrNull()
                    val dataType = desc?.dataType ?: PtpPropertyDesc.TYPE_UINT16
                    when (dataType) {
                        PtpPropertyDesc.TYPE_INT8, PtpPropertyDesc.TYPE_UINT8 -> {
                            val buf = ByteBuffer.allocate(1).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            buf.put(value.toByte())
                            active.session.setDevicePropValue(propCode, buf.array()).getOrThrow()
                        }
                        PtpPropertyDesc.TYPE_INT16, PtpPropertyDesc.TYPE_UINT16 -> {
                            active.session.setDevicePropValueInt16(propCode, value.toInt()).getOrThrow()
                        }
                        PtpPropertyDesc.TYPE_INT32, PtpPropertyDesc.TYPE_UINT32 -> {
                            val buf = ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            buf.putInt(value.toInt())
                            active.session.setDevicePropValue(propCode, buf.array()).getOrThrow()
                        }
                        else -> {
                            active.session.setDevicePropValueInt16(propCode, value.toInt()).getOrThrow()
                        }
                    }
                    usbLog("Set property 0x${propCode.toString(16)}=$value OK")
                    val snapshot = refreshCameraPropertiesSubset(
                        active = active,
                        existing = _cameraProperties.value,
                        propCodes = expandDependentCameraPropertyCodes(setOf(propCode)),
                    )
                    _cameraProperties.value = snapshot
                }
            } finally {
                if (paused) resumeLiveViewTransport("property-set")
            }
        }
    }

    /**
     * Execute manual focus drive.
     * @param steps positive = near, negative = far
     */
    suspend fun manualFocusDrive(steps: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val paused = pauseLiveViewTransport("mf-drive")
            try {
                operationMutex.withLock {
                    ensureManagerOpen()
                    val active = connectOrReuseSession("mf-drive")
                    active.session.omdMfDrive(steps).getOrThrow()
                }
            } finally {
                if (paused) resumeLiveViewTransport("mf-drive")
            }
        }
    }

    /**
     * Trigger capture while keeping live view active.
     * Pauses live view transport during capture, then resumes.
     */
    suspend fun captureWhileLiveView(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val paused = pauseLiveViewTransport("capture")
            try {
                operationMutex.withLock {
                    ensureManagerOpen()
                    activeOperationId = operationIdCounter.incrementAndGet()
                    val active = connectOrReuseSession("capture-lv")
                    updateRuntimeState(
                        nextState = OmCaptureUsbOperationState.Capturing,
                        statusLabel = "Triggering OM camera shutter over USB...",
                    ) { state ->
                        state.copy(summary = active.summary, canRetry = false)
                    }
                    val selection = captureSelection(active, TetherPhoneImportFormat.JpegAndRaw)
                    updateRuntimeState(
                        nextState = OmCaptureUsbOperationState.Complete,
                        statusLabel = "Captured ${selection.primary.fileName}",
                    ) { state ->
                        state.copy(
                            summary = active.summary,
                            lastActionLabel = "Captured ${selection.primary.fileName}",
                            canRetry = false,
                        )
                    }
                    usbLog("Capture during live view completed with ${selection.primary.fileName}")
                }
            } finally {
                if (paused) resumeLiveViewTransport("capture")
            }
        }
    }

    suspend fun touchFocus(encodedArea: Long): Result<TouchFocusResult> = withContext(Dispatchers.IO) {
        runCatching {
            val activeBefore = currentActiveSession()
                ?: throw IllegalStateException("USB camera is not connected")
            drainPendingEvents(activeBefore)

            // Step 1: Set AF target area (moves the AF point on screen)
            setCameraProperty(PtpConstants.OlympusProp.AFTargetArea, encodedArea).getOrThrow()

            // Step 2: Trigger AF-only half-press (param=0x1 without param=0x2).
            // On OM-1, the full AF_START+AF_END (param=1+2) sequence triggers a
            // shutter release / capture.  We send ONLY AF_START to initiate
            // autofocus at the new target area without firing the shutter.
            val afActive = currentActiveSession()
                ?: throw IllegalStateException("USB camera disconnected during touch focus")
            afActive.session.omdAfStart().onFailure { throwable ->
                D.err("USB", "AF start failed (non-fatal, AF target was set)", throwable)
            }

            // Step 3: Wait for AF result events
            val activeAfter = currentActiveSession()
                ?: throw IllegalStateException("USB camera disconnected during touch focus")
            val matchedEvent = awaitEventMatchingOrNull(
                active = activeAfter,
                timeoutMs = 2_000L,
                waitLabel = "USB touch-focus feedback",
            ) { event ->
                when (event.code) {
                    PtpConstants.OlympusEvt.AfFrame,
                    PtpConstants.OlympusEvt.NotifyAfTargetFrame,
                    -> true
                    PtpConstants.OlympusEvt.DevicePropChanged,
                    PtpConstants.Evt.DevicePropChanged,
                    -> event.param(0) in setOf(
                        PtpConstants.OlympusProp.AFTargetArea,
                        PtpConstants.OlympusProp.FocusDistance,
                        PtpConstants.OlympusProp.AFResult,
                    )
                    else -> false
                }
            }

            // Step 4: Read back AF state
            val readPaused = pauseLiveViewTransport("touch-focus-readback")
            val snapshot = try {
                operationMutex.withLock {
                    ensureManagerOpen()
                    val active = connectOrReuseSession("touch-focus-readback")
                    val nextSnapshot = refreshCameraPropertiesSubset(
                        active = active,
                        existing = _cameraProperties.value,
                        propCodes = setOf(
                            PtpConstants.OlympusProp.AFTargetArea,
                            PtpConstants.OlympusProp.FocusDistance,
                            PtpConstants.OlympusProp.AFResult,
                        ),
                    )
                    _cameraProperties.value = nextSnapshot
                    nextSnapshot
                }
            } finally {
                if (readPaused) resumeLiveViewTransport("touch-focus-readback")
            }
            val focusState = snapshot.findProperty(PtpConstants.OlympusProp.FocusDistance)?.currentValue
            val afResult = snapshot.findProperty(PtpConstants.OlympusProp.AFResult)?.currentValue
            val focusLocked =
                matchedEvent?.code == PtpConstants.OlympusEvt.AfFrame ||
                    focusState == 2L
            TouchFocusResult(
                focusLocked = focusLocked,
                afFrameNotified = matchedEvent != null,
                focusState = focusState,
                afResult = afResult,
            )
        }
    }

    private fun readAllProperties(active: ActiveSession, includeExtras: Boolean): CameraPropertiesSnapshot {
        val supported = active.info.devicePropertiesSupported
        val extraProperties = if (includeExtras) {
            supported.asSequence()
                .filter(::shouldExposeUsbScpExtraProperty)
                .mapNotNull { propCode ->
                    readPropertyIfSupported(
                        active = active,
                        propCode = propCode,
                        label = olympusUsbPropertyLabel(propCode),
                        supported = supported,
                    )
                }
                .filterNot { it.isReadOnly && it.allowedValues.isEmpty() }
                .sortedWith(compareBy<CameraPropertyState>({ olympusUsbPropertyPriority(it.propCode) }, { it.propCode }))
                .toList()
        } else {
            emptyList()
        }
        return CameraPropertiesSnapshot(
            shutterSpeed = readPropertyIfSupported(active, PtpConstants.OlympusProp.ShutterSpeed, "Shutter Speed", supported),
            aperture = readPropertyIfSupported(active, PtpConstants.OlympusProp.Aperture, "Aperture", supported),
            iso = readIsoPropertyIfSupported(active, supported),
            exposureComp = readPropertyIfSupported(active, PtpConstants.OlympusProp.ExposureCompensation, "Exposure Comp", supported),
            whiteBalance = readPropertyIfSupported(active, PtpConstants.OlympusProp.WhiteBalance, "White Balance", supported),
            focusMode = readPropertyIfSupported(active, PtpConstants.OlympusProp.FocusMode, "Focus Mode", supported),
            meteringMode = readPropertyIfSupported(active, PtpConstants.OlympusProp.MeteringMode, "Metering", supported),
            flashMode = readPropertyIfSupported(active, PtpConstants.OlympusProp.FlashMode, "Flash", supported),
            driveMode = readPropertyIfSupported(active, PtpConstants.OlympusProp.DriveMode, "Drive Mode", supported),
            imageQuality = readPropertyIfSupported(active, PtpConstants.OlympusProp.ImageQuality, "Image Quality", supported),
            exposureMode = readPropertyIfSupported(active, PtpConstants.OlympusProp.ExposureMode, "Exposure Mode", supported),
            extraProperties = extraProperties,
        )
    }

    private fun currentIsoCandidatePropCodes(supported: Set<Int>): List<Int> {
        // OM-1 session logs repeatedly churn 0xD005 while 0xD007 never appears.
        // Prefer the already-bound ISO prop, then the legacy constant, then the
        // OM-1 fallback code when the camera actually reports support for it.
        return buildList {
            _cameraProperties.value.iso?.propCode?.let(::add)
            add(PtpConstants.OlympusProp.ISOSpeed)
            add(ISO_SPEED_OM1_FALLBACK_PROP)
        }.distinct().filter { it in supported }
    }

    private fun currentIsoRefreshCodes(): List<Int> {
        return buildList {
            _cameraProperties.value.iso?.propCode?.let(::add)
            add(PtpConstants.OlympusProp.ISOSpeed)
            add(ISO_SPEED_OM1_FALLBACK_PROP)
        }.distinct()
    }

    private fun isIsoPropertyCode(propCode: Int): Boolean = propCode in currentIsoRefreshCodes()

    private fun readIsoPropertyIfSupported(
        active: ActiveSession,
        supported: Set<Int>,
    ): CameraPropertyState? {
        currentIsoCandidatePropCodes(supported).forEach { candidate ->
            readPropertyIfSupported(
                active = active,
                propCode = candidate,
                label = "ISO",
                supported = supported,
            )?.let { return it }
        }
        return null
    }

    private fun refreshCameraPropertiesSubset(
        active: ActiveSession,
        existing: CameraPropertiesSnapshot,
        propCodes: Set<Int>,
    ): CameraPropertiesSnapshot {
        var nextSnapshot = existing
        val supported = active.info.devicePropertiesSupported
        propCodes.forEach { propCode ->
            val refreshed = readPropertyIfSupported(
                active = active,
                propCode = propCode,
                label = olympusUsbPropertyLabel(propCode),
                supported = supported,
            )
            nextSnapshot = nextSnapshot.withUpdatedProperty(propCode, refreshed)
        }
        return nextSnapshot
    }

    private fun applyOptimisticPropertyChanged(
        propCode: Int,
        rawValue: Long?,
    ) {
        val nextRawValue = rawValue ?: return
        val existing = _cameraProperties.value.findProperty(propCode) ?: return
        if (existing.currentValue == nextRawValue) {
            return
        }
        val nextAllowedValues = if (
            existing.allowedValues.isNotEmpty() &&
            nextRawValue !in existing.allowedValues
        ) {
            (existing.allowedValues + nextRawValue).distinct()
        } else {
            existing.allowedValues
        }
        _cameraProperties.value = _cameraProperties.value.withUpdatedProperty(
            propCode = propCode,
            property = existing.copy(
                currentValue = nextRawValue,
                allowedValues = nextAllowedValues,
            ),
        )
    }

    private fun expandDependentCameraPropertyCodes(propCodes: Set<Int>): Set<Int> {
        val expanded = linkedSetOf<Int>()
        propCodes.forEach { propCode ->
            expanded += propCode
            when (propCode) {
                PtpConstants.OlympusProp.ExposureMode -> expanded += listOf(
                    PtpConstants.OlympusProp.ExposureMode,
                    PtpConstants.OlympusProp.ShutterSpeed,
                    PtpConstants.OlympusProp.Aperture,
                    PtpConstants.OlympusProp.ExposureCompensation,
                )
                    .plus(currentIsoRefreshCodes())
                PtpConstants.OlympusProp.FocusMode -> expanded += listOf(
                    PtpConstants.OlympusProp.FocusMode,
                    PtpConstants.OlympusProp.AFTargetArea,
                    PtpConstants.OlympusProp.FocusDistance,
                    PtpConstants.OlympusProp.AFResult,
                )
                PtpConstants.OlympusProp.AFTargetArea -> expanded += listOf(
                    PtpConstants.OlympusProp.AFTargetArea,
                    PtpConstants.OlympusProp.FocusDistance,
                    PtpConstants.OlympusProp.AFResult,
                )
                // Body mode changes emit 0xD062 before the session often falls out
                // of PC control (0xC105 CameraControlOff). Refresh the exposure set
                // from the next safe session rather than treating D084 as a UI prop;
                // D084 is RecView/live-view state and is handled separately.
                MODE_DIAL_EVENT_PROP -> expanded += listOf(
                    PtpConstants.OlympusProp.ExposureMode,
                    PtpConstants.OlympusProp.ShutterSpeed,
                    PtpConstants.OlympusProp.Aperture,
                    PtpConstants.OlympusProp.ExposureCompensation,
                    PtpConstants.OlympusProp.DriveMode,
                )
                    .plus(currentIsoRefreshCodes())
            }
        }
        return expanded
    }

    private fun shouldEmitUsbPropertyChanged(propCode: Int): Boolean {
        if (isIsoPropertyCode(propCode)) {
            return true
        }
        return when (propCode) {
            PtpConstants.OlympusProp.ShutterSpeed,
            PtpConstants.OlympusProp.Aperture,
            PtpConstants.OlympusProp.FocusMode,
            PtpConstants.OlympusProp.MeteringMode,
            PtpConstants.OlympusProp.ExposureCompensation,
            PtpConstants.OlympusProp.DriveMode,
            PtpConstants.OlympusProp.ImageQuality,
            PtpConstants.OlympusProp.WhiteBalance,
            PtpConstants.OlympusProp.FlashMode,
            PtpConstants.OlympusProp.ExposureMode,
            PtpConstants.OlympusProp.AFTargetArea,
            PtpConstants.OlympusProp.FocusDistance,
            PtpConstants.OlympusProp.AFResult,
            // OM-1 body mode changes surface on 0xD062. D084 is RecView/live-view
            // state and should stay on the manager's internal live-view path.
            MODE_DIAL_EVENT_PROP,
            -> true
            else -> shouldExposeUsbScpExtraProperty(propCode)
        }
    }

    private fun CameraPropertiesSnapshot.withUpdatedProperty(
        propCode: Int,
        property: CameraPropertyState?,
    ): CameraPropertiesSnapshot {
        val cleanedExtras = extraProperties.filterNot { it.propCode == propCode }
        if (isIsoPropertyCode(propCode)) {
            return copy(iso = property, extraProperties = cleanedExtras)
        }
        return when (propCode) {
            PtpConstants.OlympusProp.ShutterSpeed -> copy(shutterSpeed = property, extraProperties = cleanedExtras)
            PtpConstants.OlympusProp.Aperture -> copy(aperture = property, extraProperties = cleanedExtras)
            PtpConstants.OlympusProp.ExposureCompensation -> copy(exposureComp = property, extraProperties = cleanedExtras)
            PtpConstants.OlympusProp.WhiteBalance -> copy(whiteBalance = property, extraProperties = cleanedExtras)
            PtpConstants.OlympusProp.FocusMode -> copy(focusMode = property, extraProperties = cleanedExtras)
            PtpConstants.OlympusProp.MeteringMode -> copy(meteringMode = property, extraProperties = cleanedExtras)
            PtpConstants.OlympusProp.FlashMode -> copy(flashMode = property, extraProperties = cleanedExtras)
            PtpConstants.OlympusProp.DriveMode -> copy(driveMode = property, extraProperties = cleanedExtras)
            PtpConstants.OlympusProp.ImageQuality -> copy(imageQuality = property, extraProperties = cleanedExtras)
            PtpConstants.OlympusProp.ExposureMode -> copy(exposureMode = property, extraProperties = cleanedExtras)
            else -> copy(
                extraProperties = if (property == null) {
                    cleanedExtras
                } else {
                    (cleanedExtras + property).sortedWith(
                        compareBy<CameraPropertyState>({ olympusUsbPropertyPriority(it.propCode) }, { it.propCode }),
                    )
                },
            )
        }
    }

    private fun readPropertyIfSupported(
        active: ActiveSession,
        propCode: Int,
        label: String,
        supported: Set<Int>,
    ): CameraPropertyState? {
        if (propCode !in supported) return null
        return try {
            val desc = active.session.getDevicePropDesc(propCode).getOrNull()
            if (desc != null) {
                val state = CameraPropertyState(
                    propCode = propCode,
                    label = label,
                    currentValue = desc.currentValue,
                    allowedValues = desc.enumValues,
                    dataType = desc.dataType,
                    isReadOnly = desc.isReadOnly,
                    formFlag = desc.formFlag,
                    rangeMin = desc.rangeMin,
                    rangeMax = desc.rangeMax,
                    rangeStep = desc.rangeStep,
                )
                val formatted = formatOlympusUsbPropertyValue(propCode, desc.currentValue)
                val formFlagStr = when (desc.formFlag) {
                    0 -> "none"; 1 -> "range"; 2 -> "enum"; else -> "0x${desc.formFlag.toString(16)}"
                }
                val valuesInfo = if (desc.enumValues.isNotEmpty()) {
                    "enum[${desc.enumValues.size}]=${desc.enumValues.take(20).joinToString(",")}"
                } else if (desc.rangeMin != desc.rangeMax) {
                    "range=${desc.rangeMin}..${desc.rangeMax} step=${desc.rangeStep}"
                } else {
                    "noValues"
                }
                D.usb(
                    "PROP 0x${propCode.toString(16)} [$label]: raw=${desc.currentValue} " +
                        "(0x${desc.currentValue.toString(16)}) → \"$formatted\" | " +
                        "type=${desc.dataType} $formFlagStr $valuesInfo " +
                        "readOnly=${desc.isReadOnly}",
                )
                state
            } else {
                null
            }
        } catch (e: Exception) {
            D.err("USB", "Failed to read property 0x${propCode.toString(16)} ($label)", e)
            null
        }
    }

    // ── USB Live View ────────────────────────────────────────────

    /**
     * Start USB live view.
     *
     * USB/PTP live-view startup flow:
     *  1. If supported, switch/control run mode
     *  2. Enable live view property
     *  3. Wait for OM live-view ready event
     *  4. Poll GetLiveViewImage (0x9484) in a loop → each response is a JPEG frame
     */
    fun startUsbLiveView() {
        if (isLiveViewActive) {
            usbLog("USB live view already active")
            return
        }
        val active = currentActiveSession() ?: run {
            usbLog("Cannot start USB live view: no active session")
            updateRuntimeState(
                nextState = OmCaptureUsbOperationState.Error,
                statusLabel = "USB tether is not connected",
            ) { state -> state.copy(canRetry = true) }
            return
        }
        if (!active.info.supportsOmdLiveView()) {
            usbLog("Camera does not support OMD.GetLiveViewImage")
            updateRuntimeState(
                nextState = OmCaptureUsbOperationState.Error,
                statusLabel = "This OM camera does not expose USB live view",
            ) { state -> state.copy(summary = active.summary, canRetry = false) }
            return
        }

        isLiveViewActive = true
        liveViewJob = scope.launch(Dispatchers.IO) {
            usbLog("USB live view starting")
            pureFallbackLiveViewPolling = false
            var endedWithError = false
            try {
                updateRuntimeState(
                    nextState = OmCaptureUsbOperationState.Idle,
                    statusLabel = "Preparing OM camera live view...",
                ) { state -> state.copy(summary = active.summary, canRetry = false) }
                val liveViewModeCandidates = resolveLiveViewModeCandidates(active)
                updateRuntimeState(
                    nextState = OmCaptureUsbOperationState.Idle,
                    statusLabel = "Waiting for camera preview readiness...",
                ) { state -> state.copy(summary = active.summary, canRetry = false) }
                val skipRecViewCallbackWait = eventEndpointUnreliable
                if (skipRecViewCallbackWait) {
                    pureFallbackLiveViewPolling = true
                    usbLog(
                        "USB live view: this OM-1 transport already proved its preview callback " +
                            "path unreliable, so startup is skipping callback wait " +
                            "and entering fixed-size fallback polling immediately",
                    )
                }
                val preparedRecView = prepareUsbLiveView(
                    active = active,
                    liveViewModeCandidate = liveViewModeCandidates.firstOrNull(),
                    waitForRecView = !skipRecViewCallbackWait,
                )
                val recViewReady = !skipRecViewCallbackWait && preparedRecView
                if (!recViewReady) {
                    pureFallbackLiveViewPolling = true
                    usbLog(
                        "Preview readiness was not confirmed during startup; " +
                            "falling back to fixed-size live-view polling",
                    )
                }
                val bootstrap = bootstrapUsbLiveViewFrame(
                    active = active,
                    liveViewModeCandidates = liveViewModeCandidates,
                    recViewReadyInitially = recViewReady,
                )
                val activeLiveViewMode = bootstrap.modeCandidate
                emitLiveViewFrame(bootstrap.frame)
                updateRuntimeState(
                    nextState = OmCaptureUsbOperationState.Idle,
                    statusLabel = "USB live view active",
                ) { state -> state.copy(summary = active.summary, canRetry = false) }

                var consecutiveErrors = 0
                var consecutiveEmptyFrames = 0
                var rearmedAfterEmptyFrames = false
                var lastSoftRecoveryAtMs = 0L
                var frameCount = 1L
                while (isActive && isLiveViewActive && !isClosed) {
                    if (currentActiveSession() !== active || active.transport.isClosed || !active.session.isOpen) {
                        usbLog("USB live view: session lost, stopping")
                        break
                    }
                    if (isLiveViewTransportPaused()) {
                        delay(LIVE_VIEW_FRAME_INTERVAL_MS)
                        continue
                    }

                    val frameResult = active.session.getLiveViewFrame()
                    if (frameResult.isSuccess) {
                        val rawData = frameResult.getOrThrow()
                        consecutiveErrors = 0
                        if (hasLiveViewJpegPayload(rawData) && emitLiveViewFrame(rawData)) {
                            consecutiveEmptyFrames = 0
                            rearmedAfterEmptyFrames = false
                            frameCount++
                            delay(LIVE_VIEW_FRAME_INTERVAL_MS)
                        } else {
                            consecutiveEmptyFrames++
                            if (consecutiveEmptyFrames == 1 ||
                                consecutiveEmptyFrames % LIVE_VIEW_EMPTY_FRAME_LOG_INTERVAL == 0
                            ) {
                                usbLog(
                                    "USB live view: waiting for usable JPEG frame " +
                                        "emptyCount=$consecutiveEmptyFrames bytes=${rawData.size}",
                                )
                            }
                            if (!rearmedAfterEmptyFrames &&
                                consecutiveEmptyFrames >= LIVE_VIEW_EMPTY_FRAME_REARM_THRESHOLD
                            ) {
                                usbLog(
                                    "USB live view: re-arming live view after repeated empty frames " +
                                        "count=$consecutiveEmptyFrames",
                                )
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastSoftRecoveryAtMs >= LIVE_VIEW_SOFT_RECOVERY_COOLDOWN_MS) {
                                    lastSoftRecoveryAtMs = now
                                    val recoveredFrame = attemptSoftLiveViewRecovery(
                                        active = active,
                                        liveViewModeCandidate = activeLiveViewMode,
                                        reason = "blank-frame streak count=$consecutiveEmptyFrames",
                                    )
                                    if (recoveredFrame != null) {
                                        consecutiveErrors = 0
                                        consecutiveEmptyFrames = 0
                                        rearmedAfterEmptyFrames = false
                                        frameCount++
                                        delay(LIVE_VIEW_FRAME_INTERVAL_MS)
                                        continue
                                    }
                                } else {
                                    usbLog(
                                        "USB live view: recovery cooldown active, delaying another " +
                                            "blank-frame re-arm",
                                    )
                                }
                                rearmedAfterEmptyFrames = true
                            }
                            if (consecutiveEmptyFrames >= LIVE_VIEW_EMPTY_FRAME_STOP_THRESHOLD) {
                                throw IllegalStateException(
                                    "Live preview stayed blank. Check the camera Display Time / preview settings and try again.",
                                )
                            }
                            delay(LIVE_VIEW_EMPTY_FRAME_BACKOFF_MS)
                        }
                        if (frameCount % 300 == 0L) {
                            usbLog("USB live view: $frameCount frames delivered")
                        }
                    } else {
                        consecutiveErrors++
                        val e = frameResult.exceptionOrNull()
                        if (consecutiveErrors <= 3) {
                            D.err("USB", "GetLiveViewImage failed ($consecutiveErrors)", e)
                        }
                        if (consecutiveErrors == 5) {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastSoftRecoveryAtMs >= LIVE_VIEW_SOFT_RECOVERY_COOLDOWN_MS) {
                                lastSoftRecoveryAtMs = now
                                val recoveredFrame = attemptSoftLiveViewRecovery(
                                    active = active,
                                    liveViewModeCandidate = activeLiveViewMode,
                                    reason = "frame-request failures count=$consecutiveErrors",
                                )
                                if (recoveredFrame != null) {
                                    consecutiveErrors = 0
                                    consecutiveEmptyFrames = 0
                                    rearmedAfterEmptyFrames = false
                                    frameCount++
                                    delay(LIVE_VIEW_FRAME_INTERVAL_MS)
                                    continue
                                }
                            }
                        }
                        if (consecutiveErrors > 30) {
                            usbLog("USB live view: too many errors, stopping")
                            break
                        }
                        delay(LIVE_VIEW_BOOTSTRAP_RETRY_DELAY_MS)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                endedWithError = true
                D.err("USB", "USB live view loop error", e)
                updateRuntimeState(
                    nextState = OmCaptureUsbOperationState.Error,
                    statusLabel = e.message ?: "USB live view failed",
                ) { state ->
                    state.copy(summary = active.summary, canRetry = true)
                }
            } finally {
                isLiveViewActive = false
                pureFallbackLiveViewPolling = false
                liveViewJob = null
                usbLog("USB live view stopped")
                usbLog(
                    "USB live view: leaving Olympus Camera Control " +
                        "${PtpConstants.OlympusProp.LiveViewEnabled.toUsbHex()} unchanged; " +
                        "keep the property unchanged until session exit",
                )
                if (!endedWithError && currentActiveSession() === active && !isClosed) {
                    updateRuntimeState(
                        nextState = OmCaptureUsbOperationState.Idle,
                        statusLabel = "OM camera ready over USB/PTP",
                    ) { state ->
                        state.copy(summary = active.summary, canRetry = false)
                    }
                }
            }
        }
    }

    fun stopUsbLiveView() {
        if (!isLiveViewActive) return
        usbLog("Stopping USB live view")
        isLiveViewActive = false
        liveViewJob?.cancel()
        liveViewJob = null
        synchronized(liveViewPauseLock) {
            liveViewPauseDepth = 0
        }
    }

    /**
     * Pause live view transport for a batch of PTP operations.
     * Call [resumeLiveViewBatch] when the batch is done.
     * This prevents the per-operation pause/resume cycle that causes
     * visible live view stutter during library thumbnail loading.
     */
    fun pauseLiveViewBatch(reason: String): Boolean = pauseLiveViewTransport(reason)

    fun resumeLiveViewBatch(reason: String) = resumeLiveViewTransport(reason)

    private fun pauseLiveViewTransport(reason: String): Boolean {
        synchronized(liveViewPauseLock) {
            if (!isLiveViewActive) return false
            liveViewPauseDepth += 1
            if (liveViewPauseDepth == 1) {
                usbLog("USB live view transport paused reason=$reason")
            }
            return true
        }
    }

    private fun resumeLiveViewTransport(reason: String) {
        synchronized(liveViewPauseLock) {
            if (liveViewPauseDepth <= 0) return
            liveViewPauseDepth -= 1
            if (liveViewPauseDepth == 0) {
                usbLog("USB live view transport resumed reason=$reason")
            }
        }
    }

    private fun isLiveViewTransportPaused(): Boolean {
        synchronized(liveViewPauseLock) {
            return liveViewPauseDepth > 0
        }
    }

    private suspend fun <T> withOptionalLiveViewTransportPause(
        reason: String,
        block: suspend () -> T,
    ): T {
        // If already batch-paused, skip the per-operation pause to avoid
        // rapid depth increment/decrement that produces visible stutter.
        if (isLiveViewTransportPaused()) {
            return block()
        }
        val paused = pauseLiveViewTransport(reason)
        return try {
            block()
        } finally {
            if (paused) {
                resumeLiveViewTransport(reason)
            }
        }
    }

    /**
     * Prepare OM-D live view using the tested setup sequence:
     * 1. Ensure PC mode (0xD052=1) — already done at session init, re-confirm here
     * 2. Set LiveViewModeOm (0xD06D) to the desired streaming resolution
     * 3. Poll GetLiveViewImage (0x9484) with no params
     *
     * OM-1 does NOT support GetRunMode/ChangeRunMode (0x910A/0x910B) —
     * those are legacy E-series operations. Skipped when not advertised.
     */
    private suspend fun prepareUsbLiveView(
        active: ActiveSession,
        liveViewModeCandidate: LiveViewModeCandidate?,
        waitForRecView: Boolean,
    ): Boolean {
        ensureSessionAlive(active)
        drainPendingEvents(active)

        // Step 1: session bootstrap already handled the Olympus Camera Control
        // handoff. Do not re-send 0xD052 here; camera_link_session (2).log
        // showed that bulk OUT stays wedged after the handoff until we reopen
        // the transport, so the live-view path must reuse that refreshed session.
        if (PtpConstants.OlympusProp.LiveViewEnabled in active.info.devicePropertiesSupported) {
            usbLog("USB live view: reusing session already primed for Olympus Camera Control")
        }

        // Step 2: Set LiveViewModeOm (0xD06D)
        if (PtpConstants.OlympusProp.LiveViewModeOm in active.info.devicePropertiesSupported) {
            val selectedMode = liveViewModeCandidate
            if (selectedMode != null) {
                usbLog(
                    "USB live view: setting LiveViewModeOm " +
                        "${PtpConstants.OlympusProp.LiveViewModeOm.toUsbHex()} " +
                        "to ${selectedMode.label} (${selectedMode.value.toUsbHex()})",
                )
                active.session.setOmLiveViewMode(
                    target = selectedMode.value,
                    dataTypeHint = selectedMode.dataType,
                ).getOrElse { throwable ->
                    D.err(
                        "USB",
                        "Failed to set LiveViewModeOm for ${selectedMode.label}",
                        throwable,
                    )
                    usbLog("USB live view: LiveViewModeOm set failed, continuing with current mode")
                }
            } else {
                active.session.configureOmLiveViewStreamingMode().getOrElse { throwable ->
                    D.err(
                        "USB",
                        "Failed to configure LiveViewModeOm",
                        throwable,
                    )
                }
            }
            delay(LIVE_VIEW_STREAMING_MODE_SETTLE_MS)
        }

        // Step 3 (optional): Wait for RecView readiness event
        return !waitForRecView || awaitLiveViewReadyEventOrLog(active)
    }

    private suspend fun bootstrapUsbLiveViewFrame(
        active: ActiveSession,
        liveViewModeCandidates: List<LiveViewModeCandidate>,
        recViewReadyInitially: Boolean,
    ): LiveViewBootstrapResult {
        var rearmedAfterFrameFailure = false
        var lastError: Throwable? = null
        var attempt = 0
        var emptyResponses = 0
        var emptyResponsesForCurrentMode = 0
        var currentModeIndex = 0
        var currentMode = liveViewModeCandidates.getOrNull(currentModeIndex)
        var recViewReadyObserved = recViewReadyInitially
        var recViewFallbackLogged = false
        val deadline = SystemClock.elapsedRealtime() + computeLiveViewBootstrapTimeout(liveViewModeCandidates)
        while (SystemClock.elapsedRealtime() < deadline) {
            attempt++
            ensureSessionAlive(active)
            val remainingMs = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(250L)
            val requestTimeoutMs = minOf(LIVE_VIEW_BOOTSTRAP_REQUEST_TIMEOUT_MS, remainingMs.toInt())
            val frameResult = active.session.getLiveViewFrame(timeoutMs = requestTimeoutMs)
            if (frameResult.isSuccess) {
                val frame = frameResult.getOrThrow()
                if (hasLiveViewJpegPayload(frame)) {
                    usbLog(
                        "USB live view bootstrap succeeded attempt=$attempt " +
                            "bytes=${frame.size} emptyResponsesBeforeSuccess=$emptyResponses " +
                            "recViewReady=$recViewReadyObserved",
                    )
                    return LiveViewBootstrapResult(frame = frame, modeCandidate = currentMode)
                }
                emptyResponses++
                emptyResponsesForCurrentMode++
                val modeLabel = currentMode?.let { "${it.label} ${it.value.toUsbHex()}" }
                    ?: "${PtpConstants.OlympusProp.LiveViewModeOm.toUsbHex()}:" +
                        PtpConstants.OlympusLiveViewMode.Streaming.toUsbHex()
                if (!recViewReadyObserved) {
                    if (!recViewFallbackLogged) {
                        usbLog(
                            "USB live view bootstrap: preview readiness is still pending; " +
                                "switching to fixed-size fallback polling " +
                                "at $modeLabel without interleaving extra live-view setup",
                        )
                        recViewFallbackLogged = true
                    }
                    if (emptyResponses == 1 ||
                        emptyResponses % LIVE_VIEW_EMPTY_FRAME_LOG_INTERVAL == 0
                    ) {
                        usbLog(
                            "USB live view bootstrap fallback polling attempt=$attempt " +
                                "emptyResponses=$emptyResponses bytes=${frame.size} mode=$modeLabel",
                        )
                    }
                    if (emptyResponses % LIVE_VIEW_FALLBACK_STATE_SAMPLE_INTERVAL == 0) {
                        logRecViewStateSample(
                            active = active,
                            reason = "fallback polling attempt=$attempt",
                        )
                    }
                    if (emptyResponsesForCurrentMode >= LIVE_VIEW_BOOTSTRAP_EMPTY_THRESHOLD_PER_MODE &&
                        currentModeIndex + 1 < liveViewModeCandidates.size
                    ) {
                        val nextMode = liveViewModeCandidates[currentModeIndex + 1]
                        usbLog(
                            "USB live view bootstrap switching preview size " +
                                "from $modeLabel to ${nextMode.label} ${nextMode.value.toUsbHex()} " +
                                "after $emptyResponsesForCurrentMode empty responses",
                        )
                        currentModeIndex += 1
                        currentMode = nextMode
                        emptyResponsesForCurrentMode = 0
                        if (pureFallbackLiveViewPolling) {
                            applyLiveViewModeForPurePolling(
                                active = active,
                                liveViewModeCandidate = currentMode,
                                reason = "mode retry after empty frames",
                            )
                            recViewReadyObserved = false
                        } else {
                            recViewReadyObserved = prepareUsbLiveView(
                                active = active,
                                liveViewModeCandidate = currentMode,
                                waitForRecView = !eventEndpointUnreliable,
                            )
                            if (!recViewReadyObserved) {
                                pureFallbackLiveViewPolling = true
                            }
                        }
                        recViewFallbackLogged = false
                        delay(LIVE_VIEW_BOOTSTRAP_POLL_DELAY_MS)
                        continue
                    }
                    delay(LIVE_VIEW_BOOTSTRAP_POLL_DELAY_MS)
                    continue
                }
                if (emptyResponses == 1 ||
                    emptyResponses % LIVE_VIEW_EMPTY_FRAME_LOG_INTERVAL == 0
                ) {
                    usbLog(
                        "USB live view bootstrap waiting for JPEG frame attempt=$attempt " +
                            "emptyResponses=$emptyResponses bytes=${frame.size} " +
                        "mode=$modeLabel",
                    )
                }
                if (emptyResponsesForCurrentMode >= LIVE_VIEW_BOOTSTRAP_EMPTY_THRESHOLD_PER_MODE &&
                    currentModeIndex + 1 < liveViewModeCandidates.size
                ) {
                    val nextMode = liveViewModeCandidates[currentModeIndex + 1]
                    usbLog(
                        "USB live view bootstrap switching preview size " +
                            "from $modeLabel to ${nextMode.label} ${nextMode.value.toUsbHex()} " +
                            "after $emptyResponsesForCurrentMode empty responses",
                    )
                    currentModeIndex += 1
                    currentMode = nextMode
                    emptyResponsesForCurrentMode = 0
                    recViewReadyObserved = prepareUsbLiveView(
                        active = active,
                        liveViewModeCandidate = currentMode,
                        waitForRecView = !eventEndpointUnreliable,
                    )
                    if (!recViewReadyObserved) {
                        pureFallbackLiveViewPolling = true
                    }
                    recViewFallbackLogged = false
                    delay(LIVE_VIEW_BOOTSTRAP_POLL_DELAY_MS)
                    continue
                }
                delay(LIVE_VIEW_BOOTSTRAP_POLL_DELAY_MS)
                continue
            }

            lastError = frameResult.exceptionOrNull()
            D.err("USB", "USB live view bootstrap GetLiveViewImage failed (attempt=$attempt)", lastError)
            if (!recViewReadyObserved) {
                if (!recViewFallbackLogged) {
                    usbLog(
                        "USB live view bootstrap: frame request failed before preview became ready; " +
                            "continuing fixed-size fallback polling",
                    )
                    recViewFallbackLogged = true
                }
                if (attempt % LIVE_VIEW_FALLBACK_STATE_SAMPLE_INTERVAL == 0) {
                    logRecViewStateSample(
                        active = active,
                        reason = "frame-request failure attempt=$attempt",
                    )
                }
                delay(LIVE_VIEW_BOOTSTRAP_POLL_DELAY_MS)
                continue
            }
            if (!rearmedAfterFrameFailure) {
                usbLog("USB live view bootstrap re-arming OM live view sequence after frame-request failure")
                if (pureFallbackLiveViewPolling) {
                    applyLiveViewModeForPurePolling(
                        active = active,
                        liveViewModeCandidate = currentMode,
                        reason = "frame-request failure",
                    )
                    recViewReadyObserved = false
                } else {
                    recViewReadyObserved = prepareUsbLiveView(
                        active = active,
                        liveViewModeCandidate = currentMode,
                        waitForRecView = !eventEndpointUnreliable,
                    )
                    if (!recViewReadyObserved) {
                        pureFallbackLiveViewPolling = true
                    }
                }
                emptyResponsesForCurrentMode = 0
                recViewFallbackLogged = false
                rearmedAfterFrameFailure = true
            }
            delay(LIVE_VIEW_BOOTSTRAP_POLL_DELAY_MS)
        }
        val finalRecViewState = if (LIVE_VIEW_REC_VIEW_STATE_PROP in active.info.devicePropertiesSupported) {
            active.session.getDevicePropValueInt16(LIVE_VIEW_REC_VIEW_STATE_PROP).getOrNull()
        } else {
            null
        }
        throw IllegalStateException(
            buildString {
                append("OM live view did not deliver a usable JPEG frame across the available Live View Size modes.")
                if (finalRecViewState != null) {
                    append(" Last preview state was ")
                    append(finalRecViewState.toUsbHex())
                    append(".")
                }
                append(" Check the camera Display Time / preview settings and try again.")
            },
            lastError,
        )
    }

    private suspend fun applyLiveViewModeForPurePolling(
        active: ActiveSession,
        liveViewModeCandidate: LiveViewModeCandidate?,
        reason: String,
    ) {
        ensureSessionAlive(active)
        val selectedMode = liveViewModeCandidate ?: return
        usbLog(
            "USB live view: fixed-size fallback polling mode apply " +
                "reason=$reason target=${selectedMode.label} ${selectedMode.value.toUsbHex()}",
        )
        active.session.setOmLiveViewMode(
            target = selectedMode.value,
            dataTypeHint = selectedMode.dataType,
        ).getOrElse { throwable ->
            D.err(
                "USB",
                "Failed to set LiveViewModeOm for fallback polling (${selectedMode.label})",
                throwable,
            )
        }
        delay(LIVE_VIEW_BOOTSTRAP_POLL_DELAY_MS)
    }

    private suspend fun attemptSoftLiveViewRecovery(
        active: ActiveSession,
        liveViewModeCandidate: LiveViewModeCandidate?,
        reason: String,
    ): ByteArray? {
        ensureSessionAlive(active)
        updateRuntimeState(
            nextState = OmCaptureUsbOperationState.Idle,
            statusLabel = "Recovering OM camera live view...",
        ) { state ->
            state.copy(summary = active.summary, canRetry = false)
        }
        usbLog(
            "USB live view: soft recovery starting reason=$reason " +
                "mode=${liveViewModeCandidate?.let { "${it.label} ${it.value.toUsbHex()}" } ?: "current"}",
        )
        if (pureFallbackLiveViewPolling) {
            applyLiveViewModeForPurePolling(
                active = active,
                liveViewModeCandidate = liveViewModeCandidate,
                reason = reason,
            )
        } else {
            val rearmedReady = prepareUsbLiveView(
                active = active,
                liveViewModeCandidate = liveViewModeCandidate,
                waitForRecView = !eventEndpointUnreliable,
            )
            if (!rearmedReady) {
                pureFallbackLiveViewPolling = true
                usbLog(
                    "USB live view: soft recovery did not confirm preview readiness; " +
                        "continuing on fixed-size fallback polling",
                )
            }
        }

        val deadline = SystemClock.elapsedRealtime() + LIVE_VIEW_SOFT_RECOVERY_TIMEOUT_MS
        var emptyResponses = 0
        var requestFailures = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            ensureSessionAlive(active)
            val remainingMs = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(250L)
            val frameResult = active.session.getLiveViewFrame(
                timeoutMs = minOf(LIVE_VIEW_BOOTSTRAP_REQUEST_TIMEOUT_MS, remainingMs.toInt()),
            )
            if (frameResult.isSuccess) {
                val frame = frameResult.getOrThrow()
                if (hasLiveViewJpegPayload(frame) && emitLiveViewFrame(frame)) {
                    usbLog(
                        "USB live view: soft recovery succeeded bytes=${frame.size} " +
                            "emptyResponses=$emptyResponses requestFailures=$requestFailures",
                    )
                    updateRuntimeState(
                        nextState = OmCaptureUsbOperationState.Idle,
                        statusLabel = "USB live view active",
                    ) { state ->
                        state.copy(summary = active.summary, canRetry = false)
                    }
                    return frame
                }
                emptyResponses += 1
            } else {
                requestFailures += 1
                if (requestFailures <= 2) {
                    D.err(
                        "USB",
                        "USB live view soft recovery frame request failed ($requestFailures)",
                        frameResult.exceptionOrNull(),
                    )
                }
            }
            delay(LIVE_VIEW_BOOTSTRAP_POLL_DELAY_MS)
        }
        usbLog(
            "USB live view: soft recovery timed out reason=$reason " +
                "emptyResponses=$emptyResponses requestFailures=$requestFailures",
        )
        updateRuntimeState(
            nextState = OmCaptureUsbOperationState.Idle,
            statusLabel = "USB live view active",
        ) { state ->
            state.copy(summary = active.summary, canRetry = false)
        }
        return null
    }

    private suspend fun probeRecViewReadyDuringBootstrap(
        active: ActiveSession,
        reason: String,
    ): Boolean {
        while (true) {
            val event = try {
                active.eventChannel.tryReceive().getOrNull()
            } catch (_: ClosedReceiveChannelException) {
                throw UsbDisconnectedException("USB camera disconnected while waiting for live view readiness.")
            } ?: break
            val params = event.params()
            val paramLabel = params.joinToString { it.toUsbHex() }
            val propCode = event.param(0)
            val propValue = event.param(1)
            when {
                event.code == PtpConstants.OlympusEvt.CreateRecView ||
                    event.code == PtpConstants.OlympusEvt.LegacyCreateRecView -> {
                    usbLog(
                        "USB live view bootstrap ready via CreateRecView code=${event.code.toUsbHex()} " +
                            "reason=$reason params=[$paramLabel]",
                    )
                    return true
                }

                event.code == PtpConstants.OlympusEvt.DevicePropChanged ||
                    event.code == PtpConstants.Evt.DevicePropChanged -> {
                    when (propCode) {
                        LIVE_VIEW_REC_VIEW_STATE_PROP -> {
                            usbLog(
                                "USB live view bootstrap RecView state event " +
                                    "value=${propValue?.toUsbHex() ?: "n/a"} reason=$reason params=[$paramLabel]",
                            )
                            if (propValue == LIVE_VIEW_REC_VIEW_READY_VALUE) {
                                return true
                            }
                        }

                        LIVE_VIEW_REC_VIEW_PROGRESS_PROP -> {
                            usbLog(
                                "USB live view bootstrap RecView progress " +
                                    "value=${propValue?.toUsbHex() ?: "n/a"} reason=$reason params=[$paramLabel]",
                            )
                        }
                    }
                }
            }
        }
        val currentRecViewState = if (LIVE_VIEW_REC_VIEW_STATE_PROP in active.info.devicePropertiesSupported) {
            active.session.getDevicePropValueInt16(LIVE_VIEW_REC_VIEW_STATE_PROP).getOrNull()
        } else {
            null
        }
        if (currentRecViewState != null) {
            usbLog(
                "USB live view bootstrap state probe ${LIVE_VIEW_REC_VIEW_STATE_PROP.toUsbHex()}=" +
                    currentRecViewState.toUsbHex() +
                    " reason=$reason",
            )
        }
        return currentRecViewState == LIVE_VIEW_REC_VIEW_READY_VALUE
    }

    private suspend fun logRecViewStateSample(
        active: ActiveSession,
        reason: String,
    ) {
        if (LIVE_VIEW_REC_VIEW_STATE_PROP !in active.info.devicePropertiesSupported) return
        val currentRecViewState = active.session.getDevicePropValueInt16(
            LIVE_VIEW_REC_VIEW_STATE_PROP,
        ).getOrNull() ?: return
        val suffix = when (currentRecViewState) {
            LIVE_VIEW_REC_VIEW_READY_VALUE -> "ready"
            LIVE_VIEW_REC_VIEW_OM1_PENDING_VALUE ->
                "Observed as a pending value on tested OM-1 bodies"
            else -> "pending"
        }
        usbLog(
            "USB live view diagnostic state sample " +
                "${LIVE_VIEW_REC_VIEW_STATE_PROP.toUsbHex()}=${currentRecViewState.toUsbHex()} " +
                "reason=$reason note=$suffix",
        )
    }

    private suspend fun awaitLiveViewReadyEventOrLog(
        active: ActiveSession,
    ): Boolean {
        val initialRecViewState = if (LIVE_VIEW_REC_VIEW_STATE_PROP in active.info.devicePropertiesSupported) {
            active.session.getDevicePropValueInt16(LIVE_VIEW_REC_VIEW_STATE_PROP).getOrNull()
        } else {
            null
        }
        if (initialRecViewState == LIVE_VIEW_REC_VIEW_READY_VALUE) {
            usbLog(
                "USB live view ready inferred from current ${LIVE_VIEW_REC_VIEW_STATE_PROP.toUsbHex()}=" +
                    initialRecViewState.toUsbHex() +
                    " before waiting for preview readiness events",
            )
            return true
        }
        if (initialRecViewState != null) {
            usbLog(
                "USB live view waiting: initial ${LIVE_VIEW_REC_VIEW_STATE_PROP.toUsbHex()}=" +
                    initialRecViewState.toUsbHex(),
            )
            if (initialRecViewState == LIVE_VIEW_REC_VIEW_OM1_PENDING_VALUE) {
                usbLog(
                    "USB live view waiting: ${LIVE_VIEW_REC_VIEW_STATE_PROP.toUsbHex()}=" +
                        initialRecViewState.toUsbHex() +
                        " matches an observed OM-1 pending state, so fallback polling " +
                        "will not treat it as fatal by itself",
                )
            }
        }

        usbLog(
            "Waiting for preview readiness " +
                "(CreateRecView or ${LIVE_VIEW_REC_VIEW_STATE_PROP.toUsbHex()}=" +
                LIVE_VIEW_REC_VIEW_READY_VALUE.toUsbHex() +
                ") timeout=${LIVE_VIEW_READY_EVENT_TIMEOUT_MS}ms",
        )
        var sawProgressOnly = false
        var sawAnyRecViewEvent = false
        val deadline = SystemClock.elapsedRealtime() + LIVE_VIEW_READY_EVENT_TIMEOUT_MS
        var lastLoggedRecViewState = initialRecViewState
        var nextStateProbeAtMs = SystemClock.elapsedRealtime() + LIVE_VIEW_READY_STATE_PROBE_INTERVAL_MS

        while (SystemClock.elapsedRealtime() < deadline) {
            ensureSessionAlive(active)
            val remainingMs = deadline - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) break
            val waitWindowMs = minOf(EVENT_POLL_TIMEOUT_MS.toLong(), remainingMs)
            val event = try {
                withTimeoutOrNull(waitWindowMs) { active.eventChannel.receive() }
            } catch (_: ClosedReceiveChannelException) {
                throw UsbDisconnectedException("USB camera disconnected while waiting for a RecView event.")
            }
            if (event != null) {
                sawAnyRecViewEvent = true
                val params = event.params()
                val paramLabel = params.joinToString { it.toUsbHex() }
                val propCode = event.param(0)
                val propValue = event.param(1)
                when {
                    event.code == PtpConstants.OlympusEvt.CreateRecView ||
                        event.code == PtpConstants.OlympusEvt.LegacyCreateRecView -> {
                        usbLog(
                            "USB live view ready via CreateRecView code=${event.code.toUsbHex()} " +
                                "params=[$paramLabel]",
                        )
                        return true
                    }

                    event.code == PtpConstants.OlympusEvt.DevicePropChanged ||
                        event.code == PtpConstants.Evt.DevicePropChanged -> {
                        when (propCode) {
                            LIVE_VIEW_REC_VIEW_STATE_PROP -> {
                                usbLog(
                                    "USB live view RecView state event " +
                                        "prop=${propCode.toUsbHex()} value=${propValue?.toUsbHex() ?: "n/a"} " +
                                        "params=[$paramLabel]",
                                )
                                lastLoggedRecViewState = propValue
                                if (propValue == LIVE_VIEW_REC_VIEW_READY_VALUE) {
                                    usbLog(
                                        "USB live view ready via ${LIVE_VIEW_REC_VIEW_STATE_PROP.toUsbHex()}=" +
                                            LIVE_VIEW_REC_VIEW_READY_VALUE.toUsbHex(),
                                    )
                                    return true
                                }
                            }

                            LIVE_VIEW_REC_VIEW_PROGRESS_PROP -> {
                                sawProgressOnly = true
                                usbLog(
                                    "USB live view RecView progress " +
                                        "prop=${propCode.toUsbHex()} value=${propValue?.toUsbHex() ?: "n/a"} " +
                                        "params=[$paramLabel]",
                                )
                            }

                            else -> {
                                usbLog(
                                    "USB live view pre-frame property change " +
                                        "prop=${propCode?.toUsbHex() ?: "n/a"} " +
                                        "value=${propValue?.toUsbHex() ?: "n/a"} " +
                                        "params=[$paramLabel]",
                                )
                            }
                        }
                    }

                    else -> {
                        usbLog(
                            "Ignoring event code=${event.code.toUsbHex()} while waiting for RecView " +
                                "params=[$paramLabel]",
                        )
                    }
                }
                continue
            }

            if (LIVE_VIEW_REC_VIEW_STATE_PROP in active.info.devicePropertiesSupported &&
                SystemClock.elapsedRealtime() >= nextStateProbeAtMs
            ) {
                val currentRecViewState = active.session.getDevicePropValueInt16(
                    LIVE_VIEW_REC_VIEW_STATE_PROP,
                ).getOrNull()
                if (currentRecViewState != null && currentRecViewState != lastLoggedRecViewState) {
                    usbLog(
                        "USB live view waiting: current ${LIVE_VIEW_REC_VIEW_STATE_PROP.toUsbHex()}=" +
                            currentRecViewState.toUsbHex(),
                    )
                    lastLoggedRecViewState = currentRecViewState
                }
                if (currentRecViewState == LIVE_VIEW_REC_VIEW_READY_VALUE) {
                    usbLog(
                        "USB live view ready inferred from current ${LIVE_VIEW_REC_VIEW_STATE_PROP.toUsbHex()}=" +
                            currentRecViewState.toUsbHex(),
                    )
                    return true
                }
                nextStateProbeAtMs = SystemClock.elapsedRealtime() + LIVE_VIEW_READY_STATE_PROBE_INTERVAL_MS
            }
        }

        val currentRecViewState = if (LIVE_VIEW_REC_VIEW_STATE_PROP in active.info.devicePropertiesSupported) {
            active.session.getDevicePropValueInt16(LIVE_VIEW_REC_VIEW_STATE_PROP).getOrNull()
        } else {
            null
        }
        if (currentRecViewState == LIVE_VIEW_REC_VIEW_READY_VALUE) {
            usbLog(
                "USB live view ready inferred from current ${LIVE_VIEW_REC_VIEW_STATE_PROP.toUsbHex()}=" +
                    currentRecViewState.toUsbHex(),
            )
            return true
        }
        if (currentRecViewState != null) {
            usbLog(
                "USB live view wait ended with ${LIVE_VIEW_REC_VIEW_STATE_PROP.toUsbHex()}=" +
                    currentRecViewState.toUsbHex(),
            )
        }
        if (!sawAnyRecViewEvent) {
            eventEndpointUnreliable = true
            usbLog(
                "USB live view: no interrupt/extended RecView events arrived during startup; " +
                    "subsequent mode retries will use fallback polling without waiting " +
                    "for preview callbacks",
            )
        }
        if (sawProgressOnly) {
            usbLog(
                "USB live view: saw RecView progress but never observed CreateRecView/" +
                    "${LIVE_VIEW_REC_VIEW_STATE_PROP.toUsbHex()}=" +
                    LIVE_VIEW_REC_VIEW_READY_VALUE.toUsbHex() +
                    " before first frame polling",
            )
        } else {
            usbLog("USB live view: no preview-ready event arrived before first frame polling")
        }
        usbLog(
            "USB live view: Display Time Off/Auto or 3s+ can delay preview creation on some bodies",
        )
        return false
    }

    private fun computeLiveViewBootstrapTimeout(
        liveViewModeCandidates: List<LiveViewModeCandidate>,
    ): Long {
        val candidateCount = liveViewModeCandidates.size.coerceAtLeast(1)
        return maxOf(
            LIVE_VIEW_BOOTSTRAP_MIN_TIMEOUT_MS,
            candidateCount * LIVE_VIEW_BOOTSTRAP_TIMEOUT_PER_MODE_MS,
        )
    }

    private fun hasLiveViewJpegPayload(rawData: ByteArray): Boolean {
        return rawData.isNotEmpty() && findJpegStart(rawData) >= 0
    }

    private fun emitLiveViewFrame(rawData: ByteArray): Boolean {
        val jpegOffset = findJpegStart(rawData)
        if (jpegOffset < 0) {
            return false
        }
        val bitmap = if (jpegOffset >= 0) {
            BitmapFactory.decodeByteArray(rawData, jpegOffset, rawData.size - jpegOffset)
        } else {
            BitmapFactory.decodeByteArray(rawData, 0, rawData.size)
        }
        if (bitmap != null) {
            _usbLiveViewFrame.tryEmit(bitmap)
            return true
        }
        usbLog("USB live view: failed to decode JPEG payload bytes=${rawData.size} jpegOffset=$jpegOffset")
        return false
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        stopUsbLiveView()
        runCatching {
            if (lifecycleReceiverRegistered) {
                appContext.unregisterReceiver(usbLifecycleReceiver)
                lifecycleReceiverRegistered = false
            }
        }
        closeActiveSession("manager-close")
        scope.cancel()
    }

    private suspend fun runSerializedImport(
        startState: OmCaptureUsbOperationState,
        startStatus: String,
        operationKind: OperationKind,
        saveMedia: suspend (fileName: String, data: ByteArray) -> OmCaptureUsbSavedMedia,
        selectionBlock: suspend (ActiveSession) -> TransferSelection,
        resumeLiveViewAfter: Boolean = false,
    ): Result<OmCaptureUsbImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val pausedLiveView = isLiveViewActive &&
                pauseLiveViewTransport(operationKind.name)
            try {
                operationMutex.withLock {
                    ensureManagerOpen()
                    activeOperationId = operationIdCounter.incrementAndGet()
                    val active = connectOrReuseSession(operationKind.name)
                    updateRuntimeState(nextState = startState, statusLabel = startStatus) { state ->
                        state.copy(summary = active.summary, canRetry = false)
                    }

                    try {
                        val selection = selectionBlock(active)
                        val primaryDownload = downloadCandidate(active, selection.primary, fetchThumbnail = true)
                        val companionDownload = selection.companionRaw?.let { raw ->
                            runCatching { downloadCandidate(active, raw, fetchThumbnail = false) }
                                .onFailure { throwable ->
                                    D.err("USB", "Companion RAW download failed for ${raw.fileName}", throwable)
                                }
                                .getOrNull()
                        }
                        saveImportResult(
                            active = active,
                            operationKind = operationKind,
                            primaryDownload = primaryDownload,
                            companionDownload = companionDownload,
                            relatedHandles = selection.relatedHandles,
                            saveMedia = saveMedia,
                        )
                    } catch (throwable: Throwable) {
                        handleOperationFailure(throwable)
                        throw throwable
                    }
                }
            } finally {
                if (pausedLiveView) {
                    resumeLiveViewTransport(operationKind.name)
                }
            }
        }
    }

    private suspend fun runDebugStressLoop(
        label: String,
        iterations: Int,
        operation: suspend () -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(BuildConfig.DEBUG) { "Stress loops are debug-only." }
            require(iterations > 0) { "Stress loop iteration count must be positive." }
            usbLog("Starting debug stress loop label=$label iterations=$iterations")
            repeat(iterations) { index ->
                val iteration = index + 1
                runCatching {
                    usbLog("Stress loop $label iteration $iteration/$iterations begin")
                    operation()
                    usbLog("Stress loop $label iteration $iteration/$iterations success")
                }.onFailure { throwable ->
                    D.err("USB", "Stress loop $label iteration $iteration/$iterations failed", throwable)
                }
            }
            usbLog("Completed debug stress loop label=$label iterations=$iterations")
        }
    }

    private suspend fun captureSelection(
        active: ActiveSession,
        importFormat: TetherPhoneImportFormat,
    ): TransferSelection {
        ensureSessionAlive(active)
        drainPendingEvents(active)
        val handlesBefore = runCatching {
            enumerateObjectHandles(active).toSet()
        }.onFailure { throwable ->
            D.err("USB", "Failed to snapshot OM camera handles before capture", throwable)
        }.getOrDefault(emptySet())

        // ── Capture workflow: ChangeRunMode → Capture → event → download ──

        // Step 1: Ensure the camera is in RECORDING mode
        if (active.info.supportsChangeRunMode()) {
            usbLog("ChangeRunMode -> RUN_MODE_RECORDING")
            active.session.changeRunMode(PtpConstants.RunMode.RECORDING).getOrElse { throwable ->
                usbLog("ChangeRunMode failed (non-fatal): ${throwable.message}")
                // Non-fatal: camera might already be in recording mode
            }
        }

        // Step 2: Trigger capture
        usbLog(
            "Capture start device=${active.summary.model} " +
                "op=${if (active.info.supportsOmdCapture()) "OMD.Capture" else "InitiateCapture"}",
        )
        active.session.captureStill().getOrElse { throwable ->
            throw IllegalStateException(
                "Failed to start OM camera capture: ${throwable.message ?: "camera rejected capture request"}",
                throwable,
            )
        }

        val captureStartMillis = System.currentTimeMillis()

        updateRuntimeState(
            nextState = OmCaptureUsbOperationState.WaitObject,
            statusLabel = "Waiting for OM camera to report ObjectAdded...",
        )

        val handles = linkedSetOf<Int>()
        val firstObjectAdded = try {
            awaitFreshObjectAdded(
                active = active,
                knownHandles = handlesBefore,
                timeoutMs = CAPTURE_OBJECT_TIMEOUT_MS,
            )
        } catch (timeout: ObjectAddedTimeoutException) {
            // Fallback for when event endpoint doesn't deliver ObjectAdded
            return fallbackSelectionAfterCaptureTimeout(active, handlesBefore, captureStartMillis, timeout, importFormat)
        }
        eventEndpointUnreliable = false
        handles += firstObjectAdded.param(0)
            ?: throw IllegalStateException("Camera reported ObjectAdded without an object handle.")

        val deadline = System.currentTimeMillis() + CAPTURE_COLLECTION_TIMEOUT_MS
        var captureCompleteSeen = false
        while (System.currentTimeMillis() < deadline) {
            val remainingMs = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
            val waitMs = if (captureCompleteSeen) {
                CAPTURE_COMPLETE_GRACE_MS
            } else {
                minOf(CAPTURE_COMPLETE_GRACE_MS, remainingMs)
            }
            val event = awaitEventOrNull(
                active = active,
                expectedCodes = setOf(
                    PtpConstants.Evt.ObjectAdded,
                    PtpConstants.Evt.CaptureComplete,
                    PtpConstants.Evt.RequestObjectTransfer,
                    PtpConstants.OlympusEvt.ObjectAdded,
                    PtpConstants.OlympusEvt.ImageTransferFinish,
                    PtpConstants.OlympusEvt.ImageRecordFinish,
                ),
                timeoutMs = waitMs,
            ) ?: break

            when (event.code) {
                PtpConstants.Evt.ObjectAdded -> {
                    val handle = event.param(0)
                        ?: throw IllegalStateException("Camera reported ObjectAdded without an object handle.")
                    handles += handle
                }

                PtpConstants.OlympusEvt.ObjectAdded -> {
                    val handle = event.param(0)
                        ?: throw IllegalStateException("Camera reported Olympus ObjectAdded without an object handle.")
                    handles += handle
                }

                PtpConstants.Evt.CaptureComplete,
                PtpConstants.OlympusEvt.ImageTransferFinish,
                PtpConstants.OlympusEvt.ImageRecordFinish,
                -> captureCompleteSeen = true
                PtpConstants.Evt.RequestObjectTransfer -> {
                    val handle = event.param(0)
                    if (handle != null && handle != 0) {
                        handles += handle
                    }
                    usbLog(
                        "RequestObjectTransfer received while waiting for ObjectAdded " +
                            "handle=${handle?.toUsbHex() ?: "n/a"}",
                    )
                }
            }
        }

        usbLog("Capture reported handles=${handles.map { it.toUsbHex() }}")
        val candidates = handles.mapNotNull { handle -> loadTransferCandidate(active, handle) }
        if (candidates.isEmpty()) {
            throw IllegalStateException(
                "Capture completed, but the OM camera did not report a valid JPEG or RAW image handle.",
            )
        }
        return chooseTransferSelection(candidates, importFormat)
    }

    private suspend fun fallbackSelectionAfterCaptureTimeout(
        active: ActiveSession,
        handlesBefore: Set<Int>,
        captureStartMillis: Long,
        timeout: ObjectAddedTimeoutException,
        importFormat: TetherPhoneImportFormat,
    ): TransferSelection {
        eventEndpointUnreliable = true
        usbLog("ObjectAdded timeout hit; entering controlled storage fallback")
        D.err("USB", "Interrupt endpoint marked unreliable after ObjectAdded timeout", timeout)
        updateRuntimeState(
            nextState = OmCaptureUsbOperationState.WaitObject,
            statusLabel = "ObjectAdded timed out; checking camera storage for a new capture...",
        )
        val handlesAfter = enumerateObjectHandles(active)
        val newHandles = handlesAfter.filterNot { it in handlesBefore }
        usbLog(
            "Fallback handle scan before=${handlesBefore.size} after=${handlesAfter.size} " +
                "newHandles=${newHandles.map { it.toUsbHex() }}",
        )

        val newCandidates = newHandles.mapNotNull { handle ->
            loadTransferCandidate(active, handle)
        }
        if (newCandidates.isNotEmpty()) {
            usbLog("Fallback selected from newly discovered handles only")
            return logFallbackSelection(
                selection = chooseTransferSelection(newCandidates, importFormat),
                previousLatestTimestamp = latestKnownTimestamp(active, handlesBefore),
            )
        }

        val fallbackHandleWindow = linkedSetOf<Int>().apply {
            addAll(newHandles)
            addAll(handlesAfter.takeLast(CAPTURE_FALLBACK_HANDLE_SCAN_LIMIT))
        }.toList()
        val recentCandidates = fallbackHandleWindow
            .mapNotNull { handle -> loadTransferCandidate(active, handle) }
            .filter { candidate ->
                val timestamp = candidate.info.preferredTimestampMillis ?: return@filter false
                timestamp >= captureStartMillis - CAPTURE_FALLBACK_RECENT_WINDOW_MS
            }
        if (recentCandidates.isNotEmpty()) {
            usbLog(
                "Fallback selected from recent timestamps within ${CAPTURE_FALLBACK_RECENT_WINDOW_MS}ms " +
                    "of capture start using ${fallbackHandleWindow.size} recent handles",
            )
            return logFallbackSelection(
                selection = chooseTransferSelection(recentCandidates, importFormat),
                previousLatestTimestamp = latestKnownTimestamp(active, handlesBefore),
            )
        }

        throw timeout
    }

    private fun latestKnownTimestamp(
        active: ActiveSession,
        handles: Set<Int>,
    ): Long? {
        return handles.toList().takeLast(CAPTURE_FALLBACK_HANDLE_SCAN_LIMIT).mapNotNull { handle ->
            loadTransferCandidate(active, handle)?.info?.preferredTimestampMillis
        }.maxOrNull()
    }

    private fun logFallbackSelection(
        selection: TransferSelection,
        previousLatestTimestamp: Long?,
    ): TransferSelection {
        val chosenTimestamp = selection.primary.info.preferredTimestampMillis
        usbLog(
            "Fallback chose handle=${selection.primary.handle.toUsbHex()} " +
                "file=${selection.primary.fileName} timestamp=${chosenTimestamp ?: -1} " +
                "newerThanPrevious=${previousLatestTimestamp?.let { chosenTimestamp != null && chosenTimestamp > it } ?: "unknown"}",
        )
        return selection
    }

    private suspend fun awaitFreshObjectAdded(
        active: ActiveSession,
        knownHandles: Set<Int>,
        timeoutMs: Long,
    ): PtpContainer {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val remainingMs = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
            val event = awaitEventOrNull(
                active = active,
                expectedCodes = setOf(
                    PtpConstants.Evt.ObjectAdded,
                    PtpConstants.Evt.RequestObjectTransfer,
                    PtpConstants.OlympusEvt.ObjectAdded,
                ),
                timeoutMs = remainingMs,
            ) ?: break
            val handle = event.param(0)
                ?: throw IllegalStateException("Camera reported ObjectAdded without an object handle.")
            if (handle !in knownHandles) {
                usbLog(
                    "Accepted capture handle=${handle.toUsbHex()} code=${event.code.toUsbHex()} for op=$activeOperationId " +
                        "after ${System.currentTimeMillis() - (deadline - timeoutMs)}ms",
                )
                return event
            }
            usbLog("Discarded stale capture handle=${handle.toUsbHex()} code=${event.code.toUsbHex()} for op=$activeOperationId")
        }
        throw ObjectAddedTimeoutException("Timed out waiting for ObjectAdded after USB capture.")
    }

    private suspend fun latestSelection(
        active: ActiveSession,
        importFormat: TetherPhoneImportFormat,
    ): TransferSelection {
        ensureSessionAlive(active)
        updateRuntimeState(
            nextState = OmCaptureUsbOperationState.WaitObject,
            statusLabel = "Reading OM camera object list and sorting by capture time...",
        )

        val handles = enumerateObjectHandles(active)
        usbLog("Import-latest enumerated ${handles.size} object handles")
        if (handles.isEmpty()) {
            throw IllegalStateException("The connected OM camera reported an empty object list.")
        }

        val candidates = handles.mapNotNull { handle -> loadTransferCandidate(active, handle) }
        if (candidates.isEmpty()) {
            throw IllegalStateException("No JPEG or ORF images were found on the connected OM camera.")
        }
        return chooseTransferSelection(candidates, importFormat)
    }

    private suspend fun specificHandleSelection(
        active: ActiveSession,
        handle: Int,
        importFormat: TetherPhoneImportFormat,
    ): TransferSelection {
        ensureSessionAlive(active)
        updateRuntimeState(
            nextState = OmCaptureUsbOperationState.WaitObject,
            statusLabel = "Resolving OM camera capture ${handle.toUsbHex()} for phone import...",
        ) { state -> state.copy(summary = active.summary, canRetry = false) }

        val requested = loadTransferCandidate(active, handle)
            ?: throw IllegalStateException(
                "The OM camera reported object ${handle.toUsbHex()}, but its metadata could not be read.",
            )

        val nearbyCandidates = runCatching {
            enumerateObjectHandles(active)
                .takeLast(SPECIFIC_HANDLE_PAIR_SCAN_LIMIT)
                .asReversed()
                .filter { it != handle }
                .mapNotNull { nearbyHandle -> loadTransferCandidate(active, nearbyHandle) }
        }.onFailure { throwable ->
            D.err(
                "USB",
                "Failed to inspect neighboring OM camera objects for ${handle.toUsbHex()} pairing",
                throwable,
            )
        }.getOrDefault(emptyList())

        val selection = chooseTransferSelection(listOf(requested) + nearbyCandidates, importFormat)
        usbLog(
            "Selected OM camera object from explicit handle request requested=${handle.toUsbHex()} " +
                "primary=${selection.primary.fileName} companionRaw=${selection.companionRaw?.fileName ?: "none"} " +
                "formatPref=${importFormat.preferenceValue}",
        )
        return selection
    }

    private fun enumerateObjectHandles(active: ActiveSession): List<Int> {
        // OM-1 quirk: GetObjectHandles with a specific storageId returns
        // InvalidStorageId (0x2008) even though GetStorageIDs lists it.
        // Use storageId=0xFFFFFFFF (all storages) as primary approach,
        // fall back to per-storage enumeration only if needed.
        val allHandles = active.session.getObjectHandles(
            storageId = -1, // 0xFFFFFFFF = all storages
        )
        if (allHandles.isSuccess) {
            return allHandles.getOrThrow()
        }

        // Fallback: try per-storage enumeration
        usbLog("GetObjectHandles(all) failed, trying per-storage fallback")
        val storageIds = active.summary.storageIds.ifEmpty {
            active.session.getStorageIDs().getOrDefault(emptyList())
        }
        val handles = linkedSetOf<Int>()
        if (storageIds.isEmpty()) {
            handles += active.session.getObjectHandles().getOrElse { throwable ->
                throw IllegalStateException(
                    "Failed to read OM camera object handles: ${throwable.message ?: "PTP GetObjectHandles failed"}",
                    throwable,
                )
            }
        } else {
            storageIds.forEach { storageId ->
                handles += active.session.getObjectHandles(storageId = storageId).getOrElse { throwable ->
                    throw IllegalStateException(
                        "Failed to read OM camera objects for storage ${storageId.toUsbHex()}: " +
                            "${throwable.message ?: "PTP GetObjectHandles failed"}",
                        throwable,
                    )
                }
            }
        }
        return handles.toList()
    }

    private fun chooseTransferSelection(
        candidates: List<TransferCandidate>,
        importFormat: TetherPhoneImportFormat,
    ): TransferSelection {
        val sorted = candidates.sortedWith(transferCandidateComparator)
        val newest = sorted.first()
        val pairedCandidates = sorted.filter { candidate ->
            candidate.handle == newest.handle || isSameCapture(newest, candidate)
        }
        val preferredJpeg = pairedCandidates.firstOrNull { it.info.isJpeg }
        val preferredRaw = pairedCandidates.firstOrNull { it.info.isRaw }
        val primary = when (importFormat) {
            TetherPhoneImportFormat.JpegOnly -> preferredJpeg ?: preferredRaw ?: newest
            TetherPhoneImportFormat.RawOnly -> preferredRaw ?: preferredJpeg ?: newest
            TetherPhoneImportFormat.JpegAndRaw -> preferredJpeg ?: preferredRaw ?: newest
        }
        val companionRaw = when (importFormat) {
            TetherPhoneImportFormat.JpegAndRaw ->
                if (primary.info.isJpeg) {
                    pairedCandidates.firstOrNull { candidate ->
                        candidate.handle != primary.handle && candidate.info.isRaw && isSameCapture(primary, candidate)
                    }
                } else {
                    null
                }
            else -> null
        }
        usbLog(
            "Selected OM camera object primary=${primary.fileName} handle=${primary.handle.toUsbHex()} " +
                "timestamp=${primary.info.preferredTimestampMillis ?: -1} " +
                "companionRaw=${companionRaw?.fileName ?: "none"} formatPref=${importFormat.preferenceValue}",
        )
        return TransferSelection(
            primary = primary,
            companionRaw = companionRaw,
            relatedHandles = pairedCandidates.mapTo(linkedSetOf()) { it.handle },
        )
    }

    private fun selectAutoImportTransferForExactHandle(
        active: ActiveSession,
        requested: TransferCandidate,
        importFormat: TetherPhoneImportFormat,
    ): TransferSelection? {
        val nearbyCandidates = runCatching {
            enumerateObjectHandles(active)
                .takeLast(SPECIFIC_HANDLE_PAIR_SCAN_LIMIT)
                .asReversed()
                .filter { it != requested.handle }
                .mapNotNull { nearbyHandle -> loadTransferCandidate(active, nearbyHandle) }
        }.onFailure { throwable ->
            D.err(
                "USB",
                "Failed to inspect neighboring OM camera objects for auto-import ${requested.handle.toUsbHex()} pairing",
                throwable,
            )
        }.getOrDefault(emptyList())

        val pairedCandidates = buildList {
            add(requested)
            addAll(nearbyCandidates.filter { candidate -> isSameCapture(requested, candidate) })
        }
        val preferredJpeg = pairedCandidates.firstOrNull { it.info.isJpeg }
        val preferredRaw = pairedCandidates.firstOrNull { it.info.isRaw }
        val primary = when (importFormat) {
            TetherPhoneImportFormat.JpegOnly -> preferredJpeg ?: return null
            TetherPhoneImportFormat.RawOnly -> preferredRaw ?: return null
            TetherPhoneImportFormat.JpegAndRaw -> preferredJpeg ?: preferredRaw ?: requested
        }
        val companionRaw = when (importFormat) {
            TetherPhoneImportFormat.JpegAndRaw ->
                if (primary.info.isJpeg) {
                    pairedCandidates.firstOrNull { candidate ->
                        candidate.handle != primary.handle && candidate.info.isRaw && isSameCapture(primary, candidate)
                    }
                } else {
                    null
                }
            else -> null
        }
        usbLog(
            "Selected OM camera auto-import object requested=${requested.handle.toUsbHex()} " +
                "primary=${primary.fileName} handle=${primary.handle.toUsbHex()} " +
                "companionRaw=${companionRaw?.fileName ?: "none"} formatPref=${importFormat.preferenceValue}",
        )
        return TransferSelection(
            primary = primary,
            companionRaw = companionRaw,
            relatedHandles = pairedCandidates.mapTo(linkedSetOf()) { it.handle },
        )
    }

    private fun isSameCapture(primary: TransferCandidate, companion: TransferCandidate): Boolean {
        if (primary.handle == companion.handle) return false
        val sameBaseName = primary.info.normalizedBaseName.isNotBlank() &&
            primary.info.normalizedBaseName == companion.info.normalizedBaseName
        val sameSequence = primary.info.sequenceNumber != 0 &&
            primary.info.sequenceNumber == companion.info.sequenceNumber
        val sameStorage = primary.info.storageId == companion.info.storageId
        val primaryTimestamp = primary.info.preferredTimestampMillis
        val companionTimestamp = companion.info.preferredTimestampMillis
        val withinPairWindow = primaryTimestamp != null &&
            companionTimestamp != null &&
            abs(primaryTimestamp - companionTimestamp) <= RAW_PAIR_WINDOW_MS
        return sameBaseName || (withinPairWindow && sameSequence && sameStorage)
    }

    private fun loadTransferCandidate(active: ActiveSession, handle: Int): TransferCandidate? {
        transferCandidateCache[handle]?.let { cached ->
            return cached
        }
        ensureSessionAlive(active)
        val info = active.session.getObjectInfo(handle).getOrElse { throwable ->
            D.err("USB", "GetObjectInfo failed for handle=${handle.toUsbHex()}", throwable)
            return null
        }
        if (!info.isImportableImage) {
            usbLog(
                "Skipping non-importable object handle=${handle.toUsbHex()} " +
                    "format=${info.objectFormat.toUsbHex()} file=${info.filename}",
            )
            return null
        }
        usbLog(
            "ObjectInfo handle=${handle.toUsbHex()} file=${info.filename.ifBlank { "unnamed" }} " +
                "captureDate=${info.captureDate.ifBlank { "n/a" }} parsedCapture=${info.captureTimestampMillis ?: -1} " +
                "modDate=${info.modificationDate.ifBlank { "n/a" }} parsedMod=${info.modificationTimestampMillis ?: -1} " +
                "sequence=${info.sequenceNumber} storage=${info.storageId.toUsbHex()} format=${info.objectFormat.toUsbHex()}",
        )
        return TransferCandidate(
            handle = handle,
            info = info,
            fileName = info.filename.ifBlank { buildFallbackFileName(info, handle) },
        ).also { candidate ->
            transferCandidateCache[handle] = candidate
        }
    }

    private fun TransferCandidate.toLibraryItem(): OmCaptureUsbLibraryItem {
        return OmCaptureUsbLibraryItem(
            handle = handle,
            fileName = fileName,
            fileSize = info.compressedSize.toLong() and 0xFFFFFFFFL,
            width = info.imagePixWidth,
            height = info.imagePixHeight,
            storageId = info.storageId,
            captureTimestampMillis = info.preferredTimestampMillis,
            captureDate = info.captureDate,
            modificationDate = info.modificationDate,
            isJpeg = info.isJpeg,
            isRaw = info.isRaw,
            hasThumbnail = info.hasThumbnail,
        )
    }

    private fun downloadCandidate(
        active: ActiveSession,
        candidate: TransferCandidate,
        fetchThumbnail: Boolean,
    ): DownloadedCandidate {
        ensureSessionAlive(active)
        updateRuntimeState(
            nextState = OmCaptureUsbOperationState.Downloading,
            statusLabel = "Downloading ${candidate.fileName} from OM camera...",
        ) { state -> state.copy(summary = active.summary) }

        val thumbnailBytes = if (fetchThumbnail && candidate.info.hasThumbnail) {
            usbLog("Download start thumbnail handle=${candidate.handle.toUsbHex()} file=${candidate.fileName}")
            active.session.getThumb(candidate.handle)
                .onSuccess { bytes ->
                    usbLog("Download end thumbnail handle=${candidate.handle.toUsbHex()} bytes=${bytes.size}")
                }
                .onFailure { throwable ->
                    D.err("USB", "Thumbnail download failed for ${candidate.fileName}", throwable)
                }
                .getOrNull()
        } else {
            null
        }

        usbLog("Download start file=${candidate.fileName} handle=${candidate.handle.toUsbHex()}")
        val objectBytes = active.session.getObject(candidate.handle).getOrElse { throwable ->
            throw IllegalStateException(
                "Failed to download ${candidate.fileName} from the OM camera: " +
                    "${throwable.message ?: "transfer failure"}",
                throwable,
            )
        }
        usbLog(
            "Download end file=${candidate.fileName} handle=${candidate.handle.toUsbHex()} bytes=${objectBytes.size}",
        )
        return DownloadedCandidate(candidate = candidate, thumbnailBytes = thumbnailBytes, objectBytes = objectBytes)
    }

    private suspend fun saveImportResult(
        active: ActiveSession,
        operationKind: OperationKind,
        primaryDownload: DownloadedCandidate,
        companionDownload: DownloadedCandidate?,
        relatedHandles: Set<Int>,
        saveMedia: suspend (fileName: String, data: ByteArray) -> OmCaptureUsbSavedMedia,
    ): OmCaptureUsbImportResult {
        ensureSessionAlive(active)
        updateRuntimeState(
            nextState = OmCaptureUsbOperationState.Saving,
            statusLabel = "Saving ${primaryDownload.candidate.fileName} to phone gallery...",
        ) { state -> state.copy(summary = active.summary) }

        usbLog("Save start file=${primaryDownload.candidate.fileName} bytes=${primaryDownload.objectBytes.size}")
        val primarySaved = saveMedia(primaryDownload.candidate.fileName, primaryDownload.objectBytes)
        usbLog(
            "Save result file=${primaryDownload.candidate.fileName} " +
                "uri=${primarySaved.uriString} path=${primarySaved.absolutePath}",
        )

        val companionImports = buildList {
            if (companionDownload != null) {
                runCatching {
                    usbLog(
                        "Save start companion RAW file=${companionDownload.candidate.fileName} " +
                            "bytes=${companionDownload.objectBytes.size}",
                    )
                    val savedMedia = saveMedia(companionDownload.candidate.fileName, companionDownload.objectBytes)
                    usbLog(
                        "Save result companion RAW file=${companionDownload.candidate.fileName} " +
                            "uri=${savedMedia.uriString} path=${savedMedia.absolutePath}",
                    )
                    add(
                        OmCaptureUsbCompanionImport(
                            objectHandle = companionDownload.candidate.handle,
                            fileName = companionDownload.candidate.fileName,
                            captureDate = companionDownload.candidate.info.captureDate,
                            captureTimestampMillis = companionDownload.candidate.info.captureTimestampMillis,
                            width = companionDownload.candidate.info.imagePixWidth,
                            height = companionDownload.candidate.info.imagePixHeight,
                            isJpeg = companionDownload.candidate.info.isJpeg,
                            isRaw = companionDownload.candidate.info.isRaw,
                            savedMedia = savedMedia,
                        ),
                    )
                }.onFailure { throwable ->
                    D.err("USB", "Companion RAW save failed for ${companionDownload.candidate.fileName}", throwable)
                }
            }
        }

        val result = OmCaptureUsbImportResult(
            objectHandle = primaryDownload.candidate.handle,
            fileName = primaryDownload.candidate.fileName,
            captureDate = primaryDownload.candidate.info.captureDate,
            captureTimestampMillis = primaryDownload.candidate.info.captureTimestampMillis,
            width = primaryDownload.candidate.info.imagePixWidth,
            height = primaryDownload.candidate.info.imagePixHeight,
            isJpeg = primaryDownload.candidate.info.isJpeg,
            isRaw = primaryDownload.candidate.info.isRaw,
            thumbnailBytes = primaryDownload.thumbnailBytes,
            objectBytes = primaryDownload.objectBytes,
            savedMedia = primarySaved,
            relatedObjectHandles = relatedHandles.toList(),
            companionImports = companionImports,
        )

        updateRuntimeState(
            nextState = OmCaptureUsbOperationState.Complete,
            statusLabel = buildCompleteStatus(operationKind, result.savedCount),
        ) { state ->
            state.copy(
                summary = active.summary,
                lastActionLabel = buildLastActionLabel(result, companionDownload != null),
                lastImportedFileName = result.fileName,
                importedCount = state.importedCount + result.savedCount,
                lastSavedMedia = result.savedMedia,
                canRetry = false,
            )
        }
        return result
    }

    private fun buildCompleteStatus(operationKind: OperationKind, savedCount: Int): String {
        return when (operationKind) {
            OperationKind.Capture -> {
                if (savedCount > 1) "USB tethered capture imported (JPEG + RAW)" else "USB tethered capture imported"
            }
            OperationKind.CaptureOnly -> "USB tethered capture saved to camera storage"
            OperationKind.ImportLatest -> {
                if (savedCount > 1) "Latest USB image imported (JPEG + RAW)" else "Latest USB image imported"
            }
            OperationKind.ImportHandle -> {
                if (savedCount > 1) "Camera capture imported to phone (JPEG + RAW)" else "Camera capture imported to phone"
            }
        }
    }

    private fun buildLastActionLabel(
        result: OmCaptureUsbImportResult,
        companionRawAttempted: Boolean,
    ): String {
        val handleLabel = result.objectHandle.toUsbHex()
        return when {
            result.companionImports.isNotEmpty() -> {
                "Imported ${result.fileName} from USB ($handleLabel) + " +
                    result.companionImports.joinToString { it.fileName }
            }
            companionRawAttempted -> {
                "Imported ${result.fileName} from USB ($handleLabel); RAW companion was unavailable"
            }
            else -> "Imported ${result.fileName} from USB ($handleLabel)"
        }
    }

    private suspend fun connectOrReuseSession(reason: String): ActiveSession {
        currentActiveSession()?.let { active ->
            if (isSessionReusable(active)) {
                usbLog("Reusing OM camera USB/PTP session for $reason")
                if (_runtimeState.value.summary == null) {
                    updateRuntimeState(
                        nextState = _runtimeState.value.operationState,
                        statusLabel = _runtimeState.value.statusLabel,
                    ) { state -> state.copy(summary = active.summary) }
                }
                return active
            }
            closeActiveSession("stale-session:$reason")
        }

        val device = findCandidateDevice()
        ensurePermission(device)
        val bundle = findPtpInterfaceBundle(device)
        var lastFailure: Throwable? = null
        var reusableDeviceInfo = findCachedInitDeviceInfo(device)
        var reusableMtpWarmupSnapshot = findCachedMtpWarmupSnapshot(device)
        for (attempt in 1..SESSION_BOOTSTRAP_MAX_ATTEMPTS) {
            usbLog(
                "USB/PTP session bootstrap attempt $attempt/$SESSION_BOOTSTRAP_MAX_ATTEMPTS " +
                    "reason=$reason init=${if (reusableDeviceInfo != null) "cached_device_info" else "fresh_get_device_info"}",
            )
            val initSession = openFreshTransportForInit(
                device = device,
                bundle = bundle,
                cachedInfo = reusableDeviceInfo,
                cachedMtpWarmupSnapshot = reusableMtpWarmupSnapshot,
            )
            var transport = initSession.transport
            var session = initSession.session
            var info = initSession.info
            reusableDeviceInfo = info
            reusableMtpWarmupSnapshot = initSession.mtpWarmupSnapshot ?: reusableMtpWarmupSnapshot
            rememberCachedInitDeviceInfo(device, info)
            reusableMtpWarmupSnapshot?.let { rememberCachedMtpWarmupSnapshot(device, it) }
            var invalidateCachedInitMetadataOnFailure = false
            try {
                check(info.isOlympusOrOmSystem()) {
                    "Attached USB camera is not an Olympus / OM System model."
                }
                session.openSession().getOrElse { throwable ->
                    throw SessionBootstrapException(
                        phase = SessionBootstrapPhase.OpenSession,
                        message = "Failed to open a standard PTP session after GetDeviceInfo.",
                        cause = throwable,
                    )
                }
                warmupFilesystemBeforeOlympusPcMode(
                    session = session,
                    mtpWarmupSnapshot = reusableMtpWarmupSnapshot,
                )
                var pcModeWriteSent = false
                if (PtpConstants.OlympusProp.LiveViewEnabled in info.devicePropertiesSupported) {
                    usbLog(
                        "USB/PTP session init: ensuring Olympus PC mode before storage refresh",
                    )
                    val pcModeResult = session.initializeOlympusPcMode().getOrElse { throwable ->
                        throw SessionBootstrapException(
                            phase = SessionBootstrapPhase.PcModeInit,
                            message = "Olympus PC mode init did not complete cleanly on this transport.",
                            cause = throwable,
                        )
                    }
                    pcModeWriteSent = pcModeResult.writeSent
                    invalidateCachedInitMetadataOnFailure = pcModeWriteSent
                    usbLog(
                        when {
                            pcModeResult.writeSent && pcModeResult.normalizedToPcMode ->
                                "USB/PTP session init: Olympus PC mode normalized to 0x1 " +
                                    "(before=${pcModeResult.valueBefore.toUsbHex()} " +
                                    "after=${pcModeResult.valueAfter?.toUsbHex() ?: "unknown"} " +
                                    "ack=${pcModeResult.responseCode?.let(PtpConstants.Resp::name) ?: "none"})"
                            pcModeResult.writeSent && pcModeResult.controlModeActiveAfter ->
                                "USB/PTP session init: Olympus control mode stayed active after " +
                                    "0xd052 compatibility write (before=${pcModeResult.valueBefore.toUsbHex()} " +
                                    "after=${pcModeResult.valueAfter?.toUsbHex() ?: "unknown"} " +
                                    "ack=${pcModeResult.responseCode?.let(PtpConstants.Resp::name) ?: "none"})"
                            else ->
                                "USB/PTP session init: Olympus PC mode already normalized at " +
                                    pcModeResult.valueBefore.toUsbHex()
                        },
                    )
                    if (pcModeWriteSent) {
                        awaitBootstrapReadyAfterPcModeSwitch(session, info)
                        delay(SESSION_POST_PC_MODE_SETTLE_MS)
                        val refreshed = reopenTransportAfterOlympusPcModeSwitch(
                            device = device,
                            bundle = bundle,
                            cachedMtpWarmupSnapshot = reusableMtpWarmupSnapshot,
                            staleTransport = transport,
                        )
                        transport = refreshed.transport
                        session = refreshed.session
                        info = refreshed.info
                        reusableDeviceInfo = refreshed.info
                        reusableMtpWarmupSnapshot = refreshed.mtpWarmupSnapshot ?: reusableMtpWarmupSnapshot
                        rememberCachedInitDeviceInfo(device, refreshed.info)
                        reusableMtpWarmupSnapshot?.let { rememberCachedMtpWarmupSnapshot(device, it) }
                        usbLog(
                            "USB/PTP session init: using fresh transport after Olympus Camera Control handoff",
                        )
                    }
                }
                val storageIds = probeStorageIdsAfterBootstrap(
                    session = session,
                    pcModeChanged = pcModeWriteSent,
                    mtpWarmupSnapshot = reusableMtpWarmupSnapshot,
                ).getOrElse { throwable ->
                    throw SessionBootstrapException(
                        phase = SessionBootstrapPhase.StorageProbe,
                        message = "Storage probe failed after session initialization.",
                        cause = throwable,
                    )
                }
                val summary = buildSummary(device, info, storageIds)
                val eventChannel = Channel<PtpContainer>(Channel.BUFFERED)
                val eventJob = startEventLoop(device, transport, eventChannel)
                val active = ActiveSession(
                    device = device,
                    transport = transport,
                    session = session,
                    info = info,
                    summary = summary,
                    eventChannel = eventChannel,
                    eventJob = eventJob,
                )
                synchronized(sessionLock) {
                    activeSession = active
                }
                usbLog(
                    "USB/PTP session ready device=${summary.model} " +
                        "supportsCapture=${summary.supportsCapture} " +
                        "supportsLiveView=${summary.supportsLiveView} " +
                        "ops=${summary.supportedOperationCount} " +
                        "storages=${summary.storageIds.size} " +
                        "pid=0x${device.productId.toString(16)}",
                )
                return active
            } catch (throwable: Throwable) {
                lastFailure = throwable
                val phase = (throwable as? SessionBootstrapException)?.phase
                if (
                    invalidateCachedInitMetadataOnFailure ||
                    phase == SessionBootstrapPhase.PostHandoffReconnect ||
                    throwable.message?.contains("post-handoff reinit", ignoreCase = true) == true
                ) {
                    reusableDeviceInfo = null
                    reusableMtpWarmupSnapshot = null
                    clearCachedInitDeviceInfo(device)
                    clearCachedMtpWarmupSnapshot(device)
                    usbLog(
                        "USB/PTP session bootstrap: cleared cached init metadata after Olympus " +
                            "Camera Control handoff failure so the next outer retry starts fully fresh",
                    )
                }
                usbLog(
                    "USB/PTP session bootstrap attempt $attempt/$SESSION_BOOTSTRAP_MAX_ATTEMPTS failed " +
                        "phase=${phase?.logLabel ?: "session_bootstrap"} error=${throwable.message}",
                )
                runCatching { transport.close() }
                if (attempt < SESSION_BOOTSTRAP_MAX_ATTEMPTS) {
                    usbLog(
                        "USB/PTP session bootstrap attempt $attempt/$SESSION_BOOTSTRAP_MAX_ATTEMPTS " +
                            "closing transport and retrying fresh after ${SESSION_BOOTSTRAP_RETRY_DELAY_MS}ms",
                    )
                    delay(SESSION_BOOTSTRAP_RETRY_DELAY_MS)
                }
            }
        }
        val failure = IllegalStateException("USB/PTP session bootstrap failed after fresh reconnect retries")
        lastFailure?.let(failure::initCause)
        throw failure
    }

    private suspend fun warmupFilesystemBeforeOlympusPcMode(
        session: PtpSession,
        mtpWarmupSnapshot: MtpWarmupSnapshot?,
    ) {
        val snapshotStorageIds = mtpWarmupSnapshot?.storageIds
            ?.distinct()
            ?.filter { it != 0 }
            .orEmpty()
        if (snapshotStorageIds.isNotEmpty()) {
            usbLog(
                "USB/PTP session init: pre-PC-mode filesystem warmup already satisfied by " +
                    "framework MTP snapshot ids=$snapshotStorageIds; skipping duplicate raw storage probe",
            )
            return
        }
        usbLog(
            "USB/PTP session init: pre-PC-mode filesystem warmup before Olympus PC mode",
        )
        val warmupStorageIds = session.getStorageIDs().getOrElse { throwable ->
            val fallbackIds = mtpWarmupSnapshot?.storageIds
                ?.distinct()
                ?.filter { it != 0 }
                .orEmpty()
            if (fallbackIds.isNotEmpty()) {
                usbLog(
                    "USB/PTP session init: pre-PC-mode GetStorageIDs failed " +
                        "error=${throwable.message}; using MTP storage snapshot for warmup ids=$fallbackIds",
                )
                fallbackIds
            } else {
                usbLog(
                    "USB/PTP session init: pre-PC-mode GetStorageIDs failed " +
                        "error=${throwable.message}; skipping filesystem warmup",
                )
                return
            }
        }
        if (warmupStorageIds.isEmpty()) {
            usbLog("USB/PTP session init: pre-PC-mode filesystem warmup found no storage ids")
            return
        }
        val firstStorageId = warmupStorageIds.first()
        session.getStorageInfoRaw(firstStorageId).onSuccess { data ->
            usbLog(
                "USB/PTP session init: pre-PC-mode GetStorageInfo(${firstStorageId.toUsbHex()}) " +
                    "returned ${data.size} bytes",
            )
        }.onFailure { throwable ->
            usbLog(
                "USB/PTP session init: pre-PC-mode GetStorageInfo(${firstStorageId.toUsbHex()}) failed " +
                    "error=${throwable.message}",
            )
        }
    }

    private suspend fun probeStorageIdsAfterBootstrap(
        session: PtpSession,
        pcModeChanged: Boolean,
        mtpWarmupSnapshot: MtpWarmupSnapshot?,
    ): Result<List<Int>> {
        val attemptCount = if (pcModeChanged) 2 else 1
        var lastFailure: Throwable? = null
        repeat(attemptCount) { index ->
            val attempt = index + 1
            usbLog(
                "USB/PTP session init: GetStorageIDs attempt $attempt/$attemptCount " +
                    if (pcModeChanged) "after Olympus PC mode transition" else "during bootstrap",
            )
            val result = session.getStorageIDs()
            if (result.isSuccess) {
                return result
            }
            lastFailure = result.exceptionOrNull()
            usbLog(
                "USB/PTP session init: GetStorageIDs attempt $attempt/$attemptCount failed " +
                    "error=${lastFailure?.message}",
            )
            if (attempt < attemptCount) {
                delay(SESSION_STORAGE_RETRY_DELAY_MS)
            }
        }
        val mtpStorageIds = mtpWarmupSnapshot?.storageIds
            ?.distinct()
            ?.filter { it != 0 }
            .orEmpty()
        if (mtpStorageIds.isNotEmpty()) {
            usbLog(
                "USB/PTP session init: raw GetStorageIDs unavailable; falling back to " +
                    "MTP/PortableDevice storage snapshot ids=$mtpStorageIds",
            )
            return Result.success(mtpStorageIds)
        }
        return Result.failure(
            lastFailure ?: IllegalStateException("PTP GetStorageIDs failed after bootstrap."),
        )
    }

    private suspend fun reopenTransportAfterOlympusPcModeSwitch(
        device: UsbDevice,
        bundle: PtpInterfaceBundle,
        cachedMtpWarmupSnapshot: MtpWarmupSnapshot?,
        staleTransport: PtpUsbConnection,
    ): FreshInitSession {
        usbLog(
            "USB/PTP session init: reopening transport after Olympus Camera Control handoff " +
                "because OM-1 events continued while bulk commands stopped; " +
                "using a full fresh GetDeviceInfo reinit instead of cached-info resume",
        )
        runCatching { staleTransport.close() }
        usbLog(
            "USB/PTP session init: waiting ${SESSION_POST_PC_MODE_REOPEN_DELAY_MS}ms for the OM camera " +
                "to return to shooting standby before raw reconnect",
        )
        delay(SESSION_POST_PC_MODE_REOPEN_DELAY_MS)
        val refreshed = try {
            openFreshTransportForInit(
                device = device,
                bundle = bundle,
                cachedInfo = null,
                cachedMtpWarmupSnapshot = cachedMtpWarmupSnapshot,
                allowMtpWarmup = false,
                claimMode = PtpUsbConnection.ClaimMode.AggressiveDetach,
                initializeTransport = true,
            )
        } catch (throwable: Throwable) {
            throw SessionBootstrapException(
                phase = SessionBootstrapPhase.PostHandoffReconnect,
                message = "Failed to reinitialize raw PTP transport after Olympus Camera Control handoff.",
                cause = throwable,
            )
        }
        refreshed.session.openSession().getOrElse { throwable ->
            runCatching { refreshed.transport.close() }
            throw SessionBootstrapException(
                phase = SessionBootstrapPhase.PostHandoffReconnect,
                message = "Failed to reopen a standard PTP session after Olympus Camera Control handoff.",
                cause = throwable,
            )
        }
        return refreshed
    }

    private suspend fun awaitBootstrapReadyAfterPcModeSwitch(
        session: PtpSession,
        info: PtpDeviceInfo,
    ) {
        if (LIVE_VIEW_REC_VIEW_STATE_PROP !in info.devicePropertiesSupported) {
            usbLog(
                "USB/PTP session init: ${LIVE_VIEW_REC_VIEW_STATE_PROP.toUsbHex()} not advertised; " +
                    "skipping Olympus post-PC-mode ready wait",
            )
            return
        }

        usbLog(
            "USB/PTP session init: waiting for Olympus post-PC-mode ready events " +
                "(event callback handoff)",
        )

        var sawProgress = false
        val ready = withTimeoutOrNull(SESSION_PC_MODE_READY_TIMEOUT_MS) {
            while (true) {
                val event = session.pollEvent(250) ?: continue
                val params = event.params()
                val paramLabel = params.joinToString { it.toUsbHex() }
                val propCode = event.param(0)
                val propValue = event.param(1)
                when {
                    event.code == PtpConstants.OlympusEvt.DevicePropChanged ||
                        event.code == PtpConstants.Evt.DevicePropChanged -> {
                        when (propCode) {
                            LIVE_VIEW_REC_VIEW_STATE_PROP -> {
                                usbLog(
                                    "USB/PTP session init post-PC-mode state " +
                                        "prop=${propCode.toUsbHex()} value=${propValue?.toUsbHex() ?: "n/a"} " +
                                        "params=[$paramLabel]",
                                )
                                if (propValue == LIVE_VIEW_REC_VIEW_READY_VALUE) {
                                    return@withTimeoutOrNull true
                                }
                            }

                            LIVE_VIEW_REC_VIEW_PROGRESS_PROP -> {
                                sawProgress = true
                                usbLog(
                                    "USB/PTP session init post-PC-mode progress " +
                                        "prop=${propCode.toUsbHex()} value=${propValue?.toUsbHex() ?: "n/a"} " +
                                        "params=[$paramLabel]",
                                )
                            }

                            else -> {
                                usbLog(
                                    "USB/PTP session init post-PC-mode property change " +
                                        "prop=${propCode?.toUsbHex() ?: "n/a"} " +
                                        "value=${propValue?.toUsbHex() ?: "n/a"} " +
                                        "params=[$paramLabel]",
                                )
                            }
                        }
                    }

                    event.code == PtpConstants.OlympusEvt.AfFrame -> {
                        usbLog(
                            "USB/PTP session init ready via ${PtpConstants.OlympusEvt.AfFrame.toUsbHex()} " +
                                "params=[$paramLabel]",
                        )
                        return@withTimeoutOrNull true
                    }

                    else -> {
                        usbLog(
                            "USB/PTP session init ignoring event code=${event.code.toUsbHex()} " +
                                "params=[$paramLabel]",
                        )
                    }
                }
            }
            error("Unreachable post-PC-mode ready wait exit")
        } ?: false

        if (ready) {
            usbLog("USB/PTP session init: Olympus post-PC-mode ready observed")
            return
        }

        val currentState = session.getDevicePropValueInt16(LIVE_VIEW_REC_VIEW_STATE_PROP).getOrNull()
        if (currentState == LIVE_VIEW_REC_VIEW_READY_VALUE) {
            usbLog(
                "USB/PTP session init: post-PC-mode ready inferred from current " +
                    "${LIVE_VIEW_REC_VIEW_STATE_PROP.toUsbHex()}=${currentState.toUsbHex()}",
            )
            return
        }

        if (sawProgress) {
            usbLog(
                    "USB/PTP session init: saw post-PC-mode progress but not ready state before settle",
            )
        } else {
            usbLog(
                "USB/PTP session init: no Olympus ready event before settle window elapsed",
            )
        }
    }

    private fun resolveLiveViewModeCandidates(active: ActiveSession): List<LiveViewModeCandidate> {
        if (PtpConstants.OlympusProp.LiveViewModeOm !in active.info.devicePropertiesSupported) {
            return emptyList()
        }

        val descriptor = active.session.getOlympusLiveViewModeDescriptor().getOrElse { throwable ->
            D.err("USB", "Failed to read Olympus Live View Size descriptor", throwable)
            val fallbackValue = PtpConstants.OlympusLiveViewMode.Streaming
            return listOf(
                LiveViewModeCandidate(
                    value = fallbackValue,
                    dataType = PtpPropertyDesc.TYPE_UINT32,
                    label = formatOmLiveViewModeLabel(fallbackValue, PtpPropertyDesc.TYPE_UINT32),
                ),
            )
        }

        val dataType = descriptor.dataType
        val candidateValues = mutableListOf<Int>()
        val preferredValue = when (dataType) {
            PtpPropertyDesc.TYPE_UINT16, PtpPropertyDesc.TYPE_INT16 ->
                PtpConstants.OlympusLiveViewMode.Streaming16
            else ->
                PtpConstants.OlympusLiveViewMode.Streaming
        }

        if (preferredValue != 0) {
            candidateValues += preferredValue
        }
        descriptor.supportedValues
            .filter { it != 0 }
            .forEach { value ->
                if (value !in candidateValues) {
                    candidateValues += value
                }
            }
        if (descriptor.currentValue != 0 && descriptor.currentValue !in candidateValues) {
            candidateValues += descriptor.currentValue
        }

        val candidates = candidateValues.distinct().map { value ->
            LiveViewModeCandidate(
                value = value,
                dataType = dataType,
                label = formatOmLiveViewModeLabel(value, dataType),
            )
        }

        if (candidates.isNotEmpty()) {
            usbLog(
                "USB live view: preview size candidates=" +
                    candidates.joinToString { "${it.label}:${it.value.toUsbHex()}" },
            )
        }
        return candidates
    }

    private fun formatOmLiveViewModeLabel(value: Int, dataType: Int): String {
        if (value == 0) return "Off"
        val is16Bit = dataType == PtpPropertyDesc.TYPE_UINT16 || dataType == PtpPropertyDesc.TYPE_INT16
        if (!is16Bit) {
            val width = (value ushr 16) and 0xFFFF
            val height = value and 0xFFFF
            if (width in 160..4096 && height in 120..4096) {
                return "${width}x${height}"
            }
        }
        return when (value) {
            PtpConstants.OlympusLiveViewMode.Streaming16 -> "Streaming"
            else -> value.toUsbHex()
        }
    }

    /**
     * Warm up Android's framework MTP stack before taking over raw PTP.
     */
    private fun runMtpWarmupProbe(
        device: UsbDevice,
        attempt: Int,
    ): MtpWarmupSnapshot? {
        usbLog("MTP warmup attempt $attempt/$GET_DEVICE_INFO_MAX_ATTEMPTS opening framework probe")
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            usbLog("MTP warmup attempt $attempt/$GET_DEVICE_INFO_MAX_ATTEMPTS failed: openDevice returned null")
            return null
        }

        val mtpDevice = android.mtp.MtpDevice(device)
        try {
            val opened = mtpDevice.open(connection)
            usbLog("MTP warmup attempt $attempt/$GET_DEVICE_INFO_MAX_ATTEMPTS MtpDevice.open()=$opened")
            if (!opened) return null

            val info = mtpDevice.deviceInfo
            val storageIds = mtpDevice.storageIds ?: IntArray(0)
            val snapshot = MtpWarmupSnapshot(
                manufacturer = info?.manufacturer.orEmpty(),
                model = info?.model.orEmpty(),
                serialNumber = info?.serialNumber.orEmpty(),
                version = info?.version.orEmpty(),
                storageIds = storageIds,
            )
            usbLog(
                "MTP warmup attempt $attempt/$GET_DEVICE_INFO_MAX_ATTEMPTS ready " +
                    "manufacturer=${snapshot.manufacturer.ifBlank { "unknown" }} " +
                    "model=${snapshot.model.ifBlank { "unknown" }} " +
                    "serial=${snapshot.serialNumber.ifBlank { "unknown" }} " +
                    "version=${snapshot.version.ifBlank { "unknown" }} " +
                    "storageIds=${snapshot.storageIds.contentToString()}",
            )
            return snapshot
        } catch (e: Exception) {
            usbLog(
                "MTP warmup attempt $attempt/$GET_DEVICE_INFO_MAX_ATTEMPTS exception " +
                    "${e.javaClass.simpleName}: ${e.message}",
            )
            return null
        } finally {
            runCatching { mtpDevice.close() }
            connection.close()
            usbLog("MTP warmup attempt $attempt/$GET_DEVICE_INFO_MAX_ATTEMPTS closed")
        }
    }

    private suspend fun openFreshTransportForInit(
        device: UsbDevice,
        bundle: PtpInterfaceBundle,
        cachedInfo: PtpDeviceInfo? = null,
        cachedMtpWarmupSnapshot: MtpWarmupSnapshot? = null,
        allowMtpWarmup: Boolean = true,
        claimMode: PtpUsbConnection.ClaimMode = PtpUsbConnection.ClaimMode.PortableDeviceCompatible,
        initializeTransport: Boolean = false,
    ): FreshInitSession {
        var lastFailure: Throwable? = null

        for (attempt in 1..GET_DEVICE_INFO_MAX_ATTEMPTS) {
            val warmupMs = warmupDelayForAttempt(attempt, cachedInfo != null)
            var mtpWarmupSnapshot = cachedMtpWarmupSnapshot
            if (cachedInfo == null) {
                if (allowMtpWarmup) {
                    mtpWarmupSnapshot = runMtpWarmupProbe(device, attempt)
                } else {
                    usbLog(
                        "PTP init fresh attempt $attempt/$GET_DEVICE_INFO_MAX_ATTEMPTS " +
                            "skipping framework MTP warmup for post-handoff raw reconnect",
                    )
                }
                usbLog(
                    "PTP init fresh attempt $attempt/$GET_DEVICE_INFO_MAX_ATTEMPTS opening transport " +
                        "warmup=${warmupMs}ms timeout=${GET_DEVICE_INFO_RESPONSE_TIMEOUT_MS}ms " +
                        "frameworkReady=${mtpWarmupSnapshot != null}",
                )
            } else {
                usbLog(
                    "PTP init cached-info attempt $attempt/$GET_DEVICE_INFO_MAX_ATTEMPTS opening transport " +
                        "warmup=${warmupMs}ms model=${cachedInfo.model} fw=${cachedInfo.firmwareVersion} " +
                        "ops=${cachedInfo.operationsSupported.size}",
                )
            }
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                val failure = PtpInitException(
                    phase = PtpInitFailurePhase.ReconnectReclaim,
                    message = "Unable to open the OM camera over USB on fresh attempt $attempt.",
                )
                lastFailure = failure
                usbLog(
                    "PTP init fresh attempt $attempt/$GET_DEVICE_INFO_MAX_ATTEMPTS failed " +
                        "phase=${failure.phase.logLabel} error=${failure.message}",
                )
                continue
            }

            val transport = PtpUsbConnection(
                connection = connection,
                usbInterface = bundle.usbInterface,
                bulkOut = bundle.bulkOut,
                bulkIn = bundle.bulkIn,
                interruptIn = bundle.interruptIn,
            )
            var keepTransport = false

            try {
                usbLog(
                    "Opening USB/PTP session for ${device.deviceName} " +
                        "interface=${bundle.usbInterface.id} class=${bundle.usbInterface.interfaceClass} " +
                        "bulkIn=${bundle.bulkIn.address.toUsbHex()} bulkOut=${bundle.bulkOut.address.toUsbHex()} " +
                        "interruptIn=${bundle.interruptIn.address.toUsbHex()} " +
                        "attempt=$attempt/$GET_DEVICE_INFO_MAX_ATTEMPTS",
                )
                if (!transport.claim(claimMode)) {
                    throw PtpInitException(
                        phase = PtpInitFailurePhase.ReconnectReclaim,
                        message = "Unable to claim the OM camera USB interface on fresh attempt $attempt.",
                    )
                }
                val claimCompletedAtMs = SystemClock.elapsedRealtime()
                usbLog(
                    "${if (cachedInfo == null) "PTP init fresh" else "PTP init cached-info"} " +
                        "attempt $attempt/$GET_DEVICE_INFO_MAX_ATTEMPTS reclaim succeeded",
                )
                if (initializeTransport) {
                    usbLog(
                        "${if (cachedInfo == null) "PTP init fresh" else "PTP init cached-info"} " +
                            "attempt $attempt/$GET_DEVICE_INFO_MAX_ATTEMPTS running standard PTP transport reset " +
                            "before GetDeviceInfo",
                    )
                    if (!transport.initializePtpTransport()) {
                        throw PtpInitException(
                            phase = PtpInitFailurePhase.ReconnectReclaim,
                            message = "PTP transport reset/init did not reach a ready state on fresh attempt $attempt.",
                        )
                    }
                }
                if (cachedInfo == null) {
                    usbLog(
                        "PTP init fresh attempt $attempt/$GET_DEVICE_INFO_MAX_ATTEMPTS " +
                            "using MTP-warmed minimal raw startup before GetDeviceInfo",
                    )
                } else {
                    usbLog(
                        "PTP init cached-info attempt $attempt/$GET_DEVICE_INFO_MAX_ATTEMPTS " +
                            "skipping MTP warmup and GetDeviceInfo; reusing cached init metadata",
                    )
                }

                usbLog(
                    "${if (cachedInfo == null) "PTP init fresh" else "PTP init cached-info"} " +
                        "attempt $attempt/$GET_DEVICE_INFO_MAX_ATTEMPTS warmup ${warmupMs}ms " +
                        "before first PTP command",
                )
                delay(warmupMs)
                val session = PtpSession(transport)
                val firstCommandAtMs = SystemClock.elapsedRealtime()
                val attachToFirstCommandLabel = formatAttachToFirstCommand(device, firstCommandAtMs)
                if (cachedInfo != null) {
                    session.primeDeviceInfo(cachedInfo)
                    usbLog(
                        "PTP init cached-info attempt $attempt/$GET_DEVICE_INFO_MAX_ATTEMPTS ready " +
                            "attachToBootstrapResume=$attachToFirstCommandLabel " +
                            "claimToBootstrapResume=${firstCommandAtMs - claimCompletedAtMs}ms",
                    )
                    keepTransport = true
                    return FreshInitSession(
                        transport = transport,
                        session = session,
                        info = cachedInfo,
                        mtpWarmupSnapshot = mtpWarmupSnapshot,
                    )
                }

                usbLog(
                    "GetDeviceInfo fresh attempt $attempt/$GET_DEVICE_INFO_MAX_ATTEMPTS begin " +
                        "timeout=${GET_DEVICE_INFO_RESPONSE_TIMEOUT_MS}ms",
                )
                val commandStartedAtMs = SystemClock.elapsedRealtime()
                val result = session.getDeviceInfo(
                    responseTimeoutMs = GET_DEVICE_INFO_RESPONSE_TIMEOUT_MS,
                )
                val commandElapsedMs = SystemClock.elapsedRealtime() - commandStartedAtMs
                if (result.isSuccess) {
                    usbLog(
                        "GetDeviceInfo fresh attempt $attempt/$GET_DEVICE_INFO_MAX_ATTEMPTS succeeded " +
                            "commandToResponse=${commandElapsedMs}ms",
                    )
                    keepTransport = true
                    return FreshInitSession(
                        transport = transport,
                        session = session,
                        info = result.getOrThrow(),
                        mtpWarmupSnapshot = mtpWarmupSnapshot,
                    )
                }

                val failure = result.exceptionOrNull()
                    ?: PtpInitException(
                        phase = PtpInitFailurePhase.ResponseReceive,
                        message = "GetDeviceInfo failed for an unknown reason.",
                    )
                lastFailure = failure
                val phase = (failure as? PtpInitException)?.phase ?: PtpInitFailurePhase.ResponseReceive
                usbLog(
                    "GetDeviceInfo fresh attempt $attempt/$GET_DEVICE_INFO_MAX_ATTEMPTS failed " +
                        "phase=${phase.logLabel} commandToResponse=${commandElapsedMs}ms " +
                        "error=${failure.message}",
                )
            } catch (throwable: Throwable) {
                lastFailure = throwable
                val phase = (throwable as? PtpInitException)?.phase ?: PtpInitFailurePhase.ReconnectReclaim
                usbLog(
                    "${if (cachedInfo == null) "PTP init fresh" else "PTP init cached-info"} " +
                        "attempt $attempt/$GET_DEVICE_INFO_MAX_ATTEMPTS failed " +
                        "phase=${phase.logLabel} error=${throwable.message}",
                )
            } finally {
                if (!keepTransport) {
                    usbLog(
                        "${if (cachedInfo == null) "PTP init fresh" else "PTP init cached-info"} " +
                            "attempt $attempt/$GET_DEVICE_INFO_MAX_ATTEMPTS closing failed transport",
                    )
                    runCatching { transport.close() }
                }
            }
        }

        val failure = IllegalStateException("PTP init failed after fresh reconnect retries")
        lastFailure?.let(failure::initCause)
        throw failure
    }

    private fun isSessionReusable(active: ActiveSession): Boolean {
        if (isClosed || active.transport.isClosed || !active.session.isOpen) return false
        if (!usbManager.hasPermission(active.device)) return false
        return usbManager.deviceList.values.any { device -> device.deviceId == active.device.deviceId }
    }

    private fun formatAttachToFirstCommand(device: UsbDevice, firstCommandAtMs: Long): String {
        val attachAtMs = attachSeenAtMs[device.deviceId] ?: return "unknown"
        return "${(firstCommandAtMs - attachAtMs).coerceAtLeast(0L)}ms"
    }

    private fun warmupDelayForAttempt(attempt: Int, usingCachedInfo: Boolean): Long {
        if (usingCachedInfo) {
            return when (attempt) {
                1 -> 100L
                2 -> 180L
                else -> 260L
            }
        }
        return when (attempt) {
            1 -> 240L
            2 -> 420L
            else -> 680L
        }
    }

    private fun currentActiveSession(): ActiveSession? = synchronized(sessionLock) { activeSession }

    private fun findCachedInitDeviceInfo(device: UsbDevice): PtpDeviceInfo? = synchronized(sessionLock) {
        cachedInitDeviceInfo
            ?.takeIf { it.matches(device) }
            ?.info
    }

    private fun findCachedMtpWarmupSnapshot(device: UsbDevice): MtpWarmupSnapshot? = synchronized(sessionLock) {
        cachedMtpWarmupSnapshot
            ?.takeIf { it.matches(device) }
            ?.snapshot
    }

    private fun rememberCachedInitDeviceInfo(device: UsbDevice, info: PtpDeviceInfo) {
        synchronized(sessionLock) {
            cachedInitDeviceInfo = CachedInitDeviceInfo(
                deviceId = device.deviceId,
                vendorId = device.vendorId,
                productId = device.productId,
                info = info,
            )
        }
    }

    private fun rememberCachedMtpWarmupSnapshot(device: UsbDevice, snapshot: MtpWarmupSnapshot) {
        synchronized(sessionLock) {
            cachedMtpWarmupSnapshot = CachedMtpWarmupSnapshot(
                deviceId = device.deviceId,
                vendorId = device.vendorId,
                productId = device.productId,
                snapshot = snapshot,
            )
        }
    }

    private fun clearCachedInitDeviceInfo(device: UsbDevice? = null) {
        synchronized(sessionLock) {
            val cached = cachedInitDeviceInfo ?: return
            if (device == null || cached.matches(device)) {
                cachedInitDeviceInfo = null
            }
        }
    }

    private fun clearCachedMtpWarmupSnapshot(device: UsbDevice? = null) {
        synchronized(sessionLock) {
            val cached = cachedMtpWarmupSnapshot ?: return
            if (device == null || cached.matches(device)) {
                cachedMtpWarmupSnapshot = null
            }
        }
    }

    private fun closeActiveSession(reason: String) {
        val closing = synchronized(sessionLock) {
            val current = activeSession
            activeSession = null
            current
        } ?: return

        usbLog("Closing OM camera USB/PTP session: $reason")
        runCatching { closing.eventChannel.close() }
        runCatching { closing.eventJob.cancel() }
        val skipProtocolClose =
            reason.startsWith("usb-detached") ||
                reason.contains("UsbDisconnectedException", ignoreCase = true)
        if (skipProtocolClose) {
            usbLog("Skipping CloseSession because the USB transport is already gone ($reason)")
        } else {
            runCatching { closing.session.closeSession() }
        }
        runCatching { closing.transport.close() }
    }

    private fun handleCameraControlOffEvent() {
        val existingJob = cameraControlOffRecoveryJob
        if (existingJob?.isActive == true) {
            return
        }
        cameraControlOffRecoveryJob = scope.launch {
            usbLog(
                "OM camera reported ${PtpConstants.OlympusEvt.CameraControlOff.toUsbHex()} " +
                    "(CameraControlOff); forcing a clean USB/PTP session reset",
            )
            stopUsbLiveView()
            closeActiveSession("camera-control-off")
            updateRuntimeState(
                nextState = OmCaptureUsbOperationState.Idle,
                statusLabel = "Camera body mode changed. Reconnecting USB session...",
            ) { state ->
                state.copy(canRetry = false)
            }
            _cameraEvents.tryEmit(
                OmCaptureUsbCameraEvent.SessionResetRequired("camera-control-off"),
            )
        }
    }

    private fun startEventLoop(
        device: UsbDevice,
        transport: PtpUsbConnection,
        eventChannel: Channel<PtpContainer>,
    ): Job = scope.launch {
        usbLog("PTP event loop started for ${device.deviceName}")
        var consecutiveInterruptMisses = 0
        var lastExtendedProbeAtMs = 0L
        var extendedFallbackAnnounced = false
        var purePollingPauseAnnounced = false
        while (isActive && !transport.isClosed) {
            // Never fully pause the event loop — property change events (e.g. mode
            // dial turned on camera body) must always flow, even during pure-polling
            // live view.  Only pause when capture-event monitoring is off AND the
            // transport is truly in pure-fallback mode with GetExtendedEventData
            // already proved unreliable (eventEndpointUnreliable).
            val shouldPauseForPurePolling = false
            if (shouldPauseForPurePolling) {
                if (!purePollingPauseAnnounced) {
                    purePollingPauseAnnounced = true
                    usbLog(
                        "PTP event loop paused during fixed-size fallback live-view polling; " +
                            "the interrupt/extended event path stayed silent on this OM-1 transport",
                    )
                }
                delay(EVENT_POLL_TIMEOUT_MS.toLong())
                continue
            }
            purePollingPauseAnnounced = false
            var resolvedFromExtendedFallback = false
            val event = runCatching { transport.pollEvent(EVENT_POLL_TIMEOUT_MS) }
                .onFailure { throwable ->
                    D.err("USB", "PTP event loop failed for ${device.deviceName}", throwable)
                }
                .getOrNull()

            val resolvedEvent = if (event != null) {
                consecutiveInterruptMisses = 0
                event
            } else {
                consecutiveInterruptMisses += 1
                val shouldProbeExtendedEvents =
                    isLiveViewActive || _runtimeState.value.operationState == OmCaptureUsbOperationState.WaitObject
                val now = SystemClock.elapsedRealtime()
                if (
                    shouldProbeExtendedEvents &&
                    consecutiveInterruptMisses >= EXTENDED_EVENT_FALLBACK_MISS_THRESHOLD &&
                    now - lastExtendedProbeAtMs >= EXTENDED_EVENT_PROBE_INTERVAL_MS
                ) {
                    lastExtendedProbeAtMs = now
                    if (!extendedFallbackAnnounced) {
                        extendedFallbackAnnounced = true
                        usbLog(
                            "PTP event loop: interrupt endpoint is silent; probing standard " +
                            "GetExtendedEventData fallback (standard request 0x65)",
                        )
                    }
                    val extendedEvent = runCatching {
                        transport.pollExtendedEvent(EXTENDED_EVENT_POLL_TIMEOUT_MS)
                    }.onFailure { throwable ->
                        D.err("USB", "PTP extended event probe failed for ${device.deviceName}", throwable)
                    }.getOrNull()
                    if (extendedEvent != null) {
                        eventEndpointUnreliable = true
                        resolvedFromExtendedFallback = true
                        usbLog(
                            "PTP event loop: received event via GetExtendedEventData " +
                                "after $consecutiveInterruptMisses silent interrupt polls",
                        )
                        consecutiveInterruptMisses = 0
                    }
                    extendedEvent
                } else {
                    null
                }
            }
            if (resolvedEvent == null) {
                if (consecutiveInterruptMisses > 0) {
                    delay(EVENT_LOOP_SILENT_BACKOFF_MS)
                }
                continue
            }
            if (!resolvedFromExtendedFallback && eventEndpointUnreliable) {
                eventEndpointUnreliable = false
                usbLog(
                    "PTP event loop: interrupt endpoint recovered with a real event; " +
                        "callback waits are enabled again",
                )
            }
            val params = resolvedEvent.params()
            usbLog(
                "Event received code=${resolvedEvent.code.toUsbHex()} type=${resolvedEvent.type.toUsbHex()} " +
                    "txn=${resolvedEvent.transactionId.toUsbHex()} " +
                    "param0=${resolvedEvent.param(0)?.toUsbHex() ?: "n/a"} " +
                    "param1=${resolvedEvent.param(1)?.toUsbHex() ?: "n/a"} " +
                    "param2=${resolvedEvent.param(2)?.toUsbHex() ?: "n/a"} " +
                    "params=[${params.joinToString { it.toUsbHex() }}]",
            )
            when (resolvedEvent.code) {
                PtpConstants.Evt.ObjectAdded,
                PtpConstants.Evt.RequestObjectTransfer,
                PtpConstants.OlympusEvt.ObjectAdded,
                -> {
                    val handle = resolvedEvent.param(0)
                    if (handle != null && handle != 0) {
                        val sourceLabel = when (resolvedEvent.code) {
                            PtpConstants.Evt.ObjectAdded -> "standard_object_added"
                            PtpConstants.Evt.RequestObjectTransfer -> "request_object_transfer"
                            PtpConstants.OlympusEvt.ObjectAdded -> "olympus_object_added"
                            else -> "unknown"
                        }
                        usbLog(
                            "OM camera capture event mapped to auto-import handle=${handle.toUsbHex()} " +
                                "source=$sourceLabel",
                        )
                        _cameraEvents.tryEmit(OmCaptureUsbCameraEvent.ObjectAdded(handle))
                    } else {
                        usbLog(
                            "Ignoring capture-related event code=${resolvedEvent.code.toUsbHex()} " +
                                "because it did not carry a usable object handle",
                        )
                    }
                }
                PtpConstants.Evt.DevicePropChanged,
                PtpConstants.OlympusEvt.DevicePropChanged,
                -> {
                    val propCode = resolvedEvent.param(0)
                    if (propCode != null && shouldEmitUsbPropertyChanged(propCode)) {
                        val rawValue = resolvedEvent.param(1)?.toLong()?.and(0xFFFF_FFFFL)
                        applyOptimisticPropertyChanged(propCode, rawValue)
                        _cameraEvents.tryEmit(
                            OmCaptureUsbCameraEvent.PropertyChanged(
                                propCode = propCode,
                                rawValue = rawValue,
                            ),
                        )
                    }
                }
                PtpConstants.OlympusEvt.CameraControlOff -> {
                    handleCameraControlOffEvent()
                }
            }
            eventChannel.trySend(resolvedEvent)
        }
        usbLog("PTP event loop stopped for ${device.deviceName}")
    }

    private fun drainPendingEvents(active: ActiveSession) {
        var drainedCount = 0
        while (true) {
            val staleEvent = active.eventChannel.tryReceive().getOrNull() ?: break
            drainedCount += 1
            usbLog(
                "Drained stale event code=${staleEvent.code.toUsbHex()} " +
                    "handle=${staleEvent.param(0)?.toUsbHex() ?: "n/a"}",
            )
        }
        if (drainedCount > 0) {
            usbLog("Discarded $drainedCount stale PTP events before starting a new operation")
        }
    }

    private suspend fun awaitEvent(
        active: ActiveSession,
        expectedCodes: Set<Int>,
        timeoutMs: Long,
        timeoutMessage: String,
    ): PtpContainer {
        return awaitEventOrNull(active, expectedCodes, timeoutMs)
            ?: throw ObjectAddedTimeoutException(timeoutMessage)
    }

    private suspend fun awaitEventOrNull(
        active: ActiveSession,
        expectedCodes: Set<Int>,
        timeoutMs: Long,
    ): PtpContainer? {
        if (timeoutMs <= 0L) return null
        usbLog(
            "Waiting for PTP events ${expectedCodes.joinToString { it.toUsbHex() }} " +
                "timeout=${timeoutMs}ms",
        )
        val matchedEvent = withTimeoutOrNull(timeoutMs) {
            while (true) {
                ensureSessionAlive(active)
                try {
                    val event = active.eventChannel.receive()
                    if (event.code in expectedCodes) {
                        usbLog(
                            "Matched expected event code=${event.code.toUsbHex()} " +
                                "handle=${event.param(0)?.toUsbHex() ?: "n/a"}",
                        )
                        return@withTimeoutOrNull event
                    }
                    usbLog(
                        "Ignoring event code=${event.code.toUsbHex()} while waiting for " +
                            expectedCodes.joinToString { it.toUsbHex() },
                    )
                } catch (_: ClosedReceiveChannelException) {
                    throw UsbDisconnectedException("USB camera disconnected while waiting for a PTP event.")
                }
            }
            error("Unreachable event wait loop exit")
        }
        if (matchedEvent == null) {
            usbLog(
                "Timed out waiting for PTP events ${expectedCodes.joinToString { it.toUsbHex() }} " +
                    "after ${timeoutMs}ms",
            )
        }
        return matchedEvent
    }

    private suspend fun awaitEventMatchingOrNull(
        active: ActiveSession,
        timeoutMs: Long,
        waitLabel: String,
        predicate: (PtpContainer) -> Boolean,
    ): PtpContainer? {
        if (timeoutMs <= 0L) return null
        usbLog("Waiting for $waitLabel timeout=${timeoutMs}ms")
        val matchedEvent = withTimeoutOrNull(timeoutMs) {
            while (true) {
                ensureSessionAlive(active)
                try {
                    val event = active.eventChannel.receive()
                    if (predicate(event)) {
                        usbLog(
                            "Matched $waitLabel code=${event.code.toUsbHex()} " +
                                "handle=${event.param(0)?.toUsbHex() ?: "n/a"}",
                        )
                        return@withTimeoutOrNull event
                    }
                    usbLog(
                        "Ignoring event code=${event.code.toUsbHex()} while waiting for $waitLabel",
                    )
                } catch (_: ClosedReceiveChannelException) {
                    throw UsbDisconnectedException("USB camera disconnected while waiting for $waitLabel.")
                }
            }
            error("Unreachable event wait loop exit")
        }
        if (matchedEvent == null) {
            usbLog("Timed out waiting for $waitLabel after ${timeoutMs}ms")
        }
        return matchedEvent
    }

    private fun ensureSessionAlive(active: ActiveSession) {
        ensureManagerOpen()
        if (currentActiveSession() !== active || active.transport.isClosed || !active.session.isOpen) {
            throw UsbDisconnectedException("USB camera disconnected during transfer.")
        }
    }

    private fun buildSummary(
        device: UsbDevice,
        info: PtpDeviceInfo,
        storageIds: List<Int>,
    ): OmCaptureUsbSessionSummary {
        return OmCaptureUsbSessionSummary(
            deviceLabel = device.productName ?: device.deviceName,
            manufacturer = info.manufacturer.ifBlank { "OM Digital Solutions" },
            model = info.model.ifBlank { device.productName ?: "Unknown camera" },
            firmwareVersion = info.firmwareVersion,
            serialNumber = info.serialNumber,
            storageIds = storageIds,
            supportsCapture = info.supportsOmdCapture() || PtpConstants.Op.InitiateCapture in info.operationsSupported,
            supportsLiveView = info.supportsOmdLiveView(),
            supportsPropertyControl = info.supportsOmdSetProperties() ||
                PtpConstants.Op.SetDevicePropValue in info.operationsSupported,
            supportedOperationCount = info.operationsSupported.size,
        )
    }

    private fun registerUsbLifecycleReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            appContext,
            usbLifecycleReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        lifecycleReceiverRegistered = true
    }

    private fun matchesActiveDevice(device: UsbDevice): Boolean {
        return currentActiveSession()?.device?.deviceId == device.deviceId
    }

    private fun extractUsbDevice(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private fun isCandidateUsbDevice(device: UsbDevice): Boolean {
        return device.interfaceCount > 0 &&
            (device.vendorId == PtpConstants.VENDOR_ID_OLYMPUS ||
                device.vendorId == PtpConstants.VENDOR_ID_OM_DIGITAL ||
                findPtpInterfaceBundleOrNull(device) != null) &&
            findPtpInterfaceBundleOrNull(device) != null
    }

    private fun findCandidateDevice(): UsbDevice {
        val allDevices = usbManager.deviceList.values.toList()
        val candidates = allDevices.filter { isCandidateUsbDevice(it) }
        val storageModeDevices = allDevices.filter { isLikelyStorageModeDevice(it, allDevices.size) }
        return when {
            candidates.isEmpty() -> error(
                if (storageModeDevices.isNotEmpty()) {
                    "Camera is in Storage mode. Switch to PTP/Tether mode."
                } else {
                    "No OM camera detected over USB/PTP. If the camera is connected in storage mode, switch it to PTP/MTP and reconnect."
                },
            )
            candidates.size > 1 -> error("Multiple USB cameras are connected. Disconnect extras and try again.")
            else -> candidates.first()
        }
    }

    private fun logUsbInventory() {
        val devices = usbManager.deviceList.values.toList()
        if (devices.isEmpty()) {
            usbLog("USB inventory is empty")
            return
        }
        devices.forEach { device ->
            usbLog("USB descriptor ${describeUsbDevice(device)}")
        }
    }

    private fun describeUsbDevice(device: UsbDevice): String {
        val interfaceDescriptions = buildString {
            repeat(device.interfaceCount) { index ->
                val usbInterface = device.getInterface(index)
                append(" iface#")
                append(index)
                append("(id=")
                append(usbInterface.id)
                append(",class=")
                append(usbInterface.interfaceClass)
                append(",subclass=")
                append(usbInterface.interfaceSubclass)
                append(",protocol=")
                append(usbInterface.interfaceProtocol)
                append(")")
                repeat(usbInterface.endpointCount) { endpointIndex ->
                    val endpoint = usbInterface.getEndpoint(endpointIndex)
                    append(" ep#")
                    append(endpointIndex)
                    append("[addr=")
                    append(endpoint.address.toUsbHex())
                    append(",dir=")
                    append(if (endpoint.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT")
                    append(",type=")
                    append(endpointTypeLabel(endpoint.type))
                    append(",max=")
                    append(endpoint.maxPacketSize)
                    append("]")
                }
            }
        }
        return "vid=${device.vendorId.toUsbHex()} pid=${device.productId.toUsbHex()} " +
            "class=${device.deviceClass} subclass=${device.deviceSubclass} protocol=${device.deviceProtocol} " +
            "name=${device.deviceName} product=${device.productName ?: "n/a"} manufacturer=${device.manufacturerName ?: "n/a"}" +
            interfaceDescriptions
    }

    private fun isLikelyStorageModeDevice(device: UsbDevice, totalDevices: Int): Boolean {
        val hasMassStorage = hasMassStorageInterface(device)
        val hasStillImage = findPtpInterfaceBundleOrNull(device) != null
        if (!hasMassStorage || hasStillImage) {
            return false
        }
        return isLikelyOmBrand(device) ||
            device.vendorId == PtpConstants.VENDOR_ID_OLYMPUS ||
            device.vendorId == PtpConstants.VENDOR_ID_OM_DIGITAL ||
            totalDevices == 1
    }

    private fun hasMassStorageInterface(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
            return true
        }
        return (0 until device.interfaceCount).any { index ->
            device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE
        }
    }

    private fun isLikelyOmBrand(device: UsbDevice): Boolean {
        val labels = listOfNotNull(device.productName, device.manufacturerName, device.deviceName)
        return labels.any { label ->
            val normalized = label.uppercase()
            "OLYMPUS" in normalized || "OM SYSTEM" in normalized || "OM-" in normalized
        }
    }

    private fun endpointTypeLabel(type: Int): String = when (type) {
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISO"
        else -> type.toString()
    }

    private suspend fun ensurePermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            usbLog("USB permission already granted for ${device.deviceName}")
            return
        }
        usbLog("Requesting USB permission for ${device.deviceName}")
        val granted = requestPermission(device)
        usbLog("USB permission result for ${device.deviceName}: granted=$granted")
        check(granted) { "USB permission was not granted for the OM camera." }
    }

    private suspend fun requestPermission(device: UsbDevice): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != permissionAction) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val resultDevice = extractUsbDevice(intent)
                if (resultDevice?.deviceId == device.deviceId && !deferred.isCompleted) {
                    deferred.complete(granted)
                }
            }
        }

        val filter = IntentFilter(permissionAction)
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        try {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val permissionIntent = PendingIntent.getBroadcast(
                appContext,
                device.deviceId,
                Intent(permissionAction).setPackage(appContext.packageName),
                flags,
            )
            usbManager.requestPermission(device, permissionIntent)
            return withTimeoutOrNull(PERMISSION_TIMEOUT_MS) { deferred.await() } == true
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    private fun findPtpInterfaceBundle(device: UsbDevice): PtpInterfaceBundle =
        findPtpInterfaceBundleOrNull(device)
            ?: error("Attached USB device does not expose a PTP still-image interface.")

    private fun findPtpInterfaceBundleOrNull(device: UsbDevice): PtpInterfaceBundle? {
        repeat(device.interfaceCount) { index ->
            val usbInterface = device.getInterface(index)
            if (
                usbInterface.interfaceClass != PtpConstants.USB_CLASS_STILL_IMAGE ||
                usbInterface.interfaceSubclass != PtpConstants.USB_SUBCLASS_STILL_IMAGE
            ) {
                return@repeat
            }

            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            var interruptIn: UsbEndpoint? = null

            repeat(usbInterface.endpointCount) { endpointIndex ->
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                when {
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        endpoint.direction == UsbConstants.USB_DIR_IN -> bulkIn = endpoint
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        endpoint.direction == UsbConstants.USB_DIR_OUT -> bulkOut = endpoint
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                        endpoint.direction == UsbConstants.USB_DIR_IN -> interruptIn = endpoint
                }
            }

            if (bulkIn != null && bulkOut != null && interruptIn != null) {
                return PtpInterfaceBundle(
                    usbInterface = usbInterface,
                    bulkIn = bulkIn,
                    bulkOut = bulkOut,
                    interruptIn = interruptIn,
                )
            }
        }
        return null
    }

    private fun handleOperationFailure(throwable: Throwable) {
        if (throwable is CancellationException && isClosed) return
        val message = when (throwable) {
            is ObjectAddedTimeoutException -> throwable.message ?: "Timed out waiting for ObjectAdded."
            is UsbDisconnectedException -> throwable.message ?: "USB camera disconnected during transfer."
            else -> throwable.message ?: "USB operation failed."
        }
        val preservedSummary = currentActiveSession()?.summary ?: _runtimeState.value.summary
        closeActiveSession("operation-failure:${throwable::class.simpleName ?: "Unknown"}")
        D.err("USB", message, throwable)
        updateRuntimeState(
            nextState = OmCaptureUsbOperationState.Error,
            statusLabel = message,
        ) { state ->
            state.copy(
                summary = preservedSummary ?: state.summary,
                canRetry = true,
            )
        }
    }

    private fun updateRuntimeState(
        nextState: OmCaptureUsbOperationState,
        statusLabel: String,
        transform: (OmCaptureUsbRuntimeState) -> OmCaptureUsbRuntimeState = { it },
    ) {
        _runtimeState.update { current ->
            if (!isAllowedTransition(current.operationState, nextState)) {
                D.err("USB", "Unexpected USB state transition ${current.operationState} -> $nextState")
            }
            D.usb(
                "[${current.operationState.statusChipLabel}] -> [${nextState.statusChipLabel}] " +
                    "status=\"$statusLabel\"",
            )
            transform(current).copy(operationState = nextState, statusLabel = statusLabel)
        }
    }

    private fun isAllowedTransition(
        current: OmCaptureUsbOperationState,
        next: OmCaptureUsbOperationState,
    ): Boolean = when {
        current == next -> true
        next == OmCaptureUsbOperationState.Error -> true
        next == OmCaptureUsbOperationState.Idle -> true
        current == OmCaptureUsbOperationState.Idle && next in setOf(
            OmCaptureUsbOperationState.Inspecting,
            OmCaptureUsbOperationState.Capturing,
            OmCaptureUsbOperationState.WaitObject,
        ) -> true
        current == OmCaptureUsbOperationState.Inspecting && next == OmCaptureUsbOperationState.Idle -> true
        current == OmCaptureUsbOperationState.Capturing && next == OmCaptureUsbOperationState.WaitObject -> true
        current == OmCaptureUsbOperationState.WaitObject && next == OmCaptureUsbOperationState.Downloading -> true
        current == OmCaptureUsbOperationState.Downloading && next == OmCaptureUsbOperationState.Saving -> true
        current == OmCaptureUsbOperationState.Saving && next == OmCaptureUsbOperationState.Complete -> true
        current == OmCaptureUsbOperationState.Complete && next in setOf(
            OmCaptureUsbOperationState.Inspecting,
            OmCaptureUsbOperationState.Capturing,
            OmCaptureUsbOperationState.WaitObject,
        ) -> true
        current == OmCaptureUsbOperationState.Error && next in setOf(
            OmCaptureUsbOperationState.Inspecting,
            OmCaptureUsbOperationState.Capturing,
            OmCaptureUsbOperationState.WaitObject,
        ) -> true
        else -> false
    }

    private fun ensureManagerOpen() {
        check(!isClosed) { "USB manager is closed." }
    }

    private fun usbLog(message: String) {
        D.usb("[op=$activeOperationId] $message")
    }

    private fun buildFallbackFileName(info: PtpObjectInfo, handle: Int): String {
        val extension = when {
            info.isJpeg -> "JPG"
            info.isRaw -> "ORF"
            else -> "BIN"
        }
        return "OM_USB_$handle.$extension"
    }

    /**
     * Find the offset of the JPEG SOI marker (0xFF 0xD8) in raw data.
     * OM-D GetLiveViewImage responses may include metadata before the JPEG.
     */
    private fun findJpegStart(data: ByteArray): Int {
        for (i in 0 until data.size - 1) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte()) {
                return i
            }
        }
        return -1
    }

    private fun Int.toUsbHex(): String = "0x${toUInt().toString(16)}"

    private data class PtpInterfaceBundle(
        val usbInterface: UsbInterface,
        val bulkIn: UsbEndpoint,
        val bulkOut: UsbEndpoint,
        val interruptIn: UsbEndpoint,
    )

    private data class ActiveSession(
        val device: UsbDevice,
        val transport: PtpUsbConnection,
        val session: PtpSession,
        val info: PtpDeviceInfo,
        val summary: OmCaptureUsbSessionSummary,
        val eventChannel: Channel<PtpContainer>,
        val eventJob: Job,
    )

    private data class MtpWarmupSnapshot(
        val manufacturer: String,
        val model: String,
        val serialNumber: String,
        val version: String,
        val storageIds: IntArray,
    )

    private data class FreshInitSession(
        val transport: PtpUsbConnection,
        val session: PtpSession,
        val info: PtpDeviceInfo,
        val mtpWarmupSnapshot: MtpWarmupSnapshot?,
    )

    private data class CachedInitDeviceInfo(
        val deviceId: Int,
        val vendorId: Int,
        val productId: Int,
        val info: PtpDeviceInfo,
    ) {
        fun matches(device: UsbDevice): Boolean {
            return device.deviceId == deviceId &&
                device.vendorId == vendorId &&
                device.productId == productId
        }
    }

    private data class CachedMtpWarmupSnapshot(
        val deviceId: Int,
        val vendorId: Int,
        val productId: Int,
        val snapshot: MtpWarmupSnapshot,
    ) {
        fun matches(device: UsbDevice): Boolean {
            return device.deviceId == deviceId &&
                device.vendorId == vendorId &&
                device.productId == productId
        }
    }

    private enum class SessionBootstrapPhase(val logLabel: String) {
        OpenSession("open_session"),
        PcModeInit("pc_mode_init"),
        PostHandoffReconnect("post_handoff_reconnect"),
        StorageProbe("storage_probe"),
    }

    private class SessionBootstrapException(
        val phase: SessionBootstrapPhase,
        message: String,
        cause: Throwable? = null,
    ) : IllegalStateException(message, cause)

    private data class TransferCandidate(
        val handle: Int,
        val info: PtpObjectInfo,
        val fileName: String,
    )

    private data class TransferSelection(
        val primary: TransferCandidate,
        val companionRaw: TransferCandidate?,
        val relatedHandles: Set<Int>,
    )

    private data class DownloadedCandidate(
        val candidate: TransferCandidate,
        val thumbnailBytes: ByteArray?,
        val objectBytes: ByteArray,
    )

    private enum class OperationKind {
        Capture,
        CaptureOnly,
        ImportLatest,
        ImportHandle,
    }

    private class ObjectAddedTimeoutException(message: String) : IllegalStateException(message)

    private class UsbDisconnectedException(message: String) : IllegalStateException(message)

    private val transferCandidateComparator = compareByDescending<TransferCandidate> {
        it.info.preferredTimestampMillis ?: Long.MIN_VALUE
    }
        .thenBy { if (it.info.isJpeg) 0 else 1 }
        .thenByDescending { it.info.sequenceNumber }
        .thenByDescending { it.handle.toUInt().toLong() }
}
