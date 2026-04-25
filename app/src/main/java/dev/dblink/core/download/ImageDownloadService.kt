package dev.dblink.core.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.dblink.DbLinkApplication
import dev.dblink.R
import dev.dblink.core.config.AppEnvironment
import dev.dblink.core.logging.D
import dev.dblink.core.model.TetherPhoneImportFormat
import dev.dblink.core.preferences.AppPreferencesRepository
import dev.dblink.core.protocol.CameraImage
import dev.dblink.core.protocol.DefaultCameraRepository
import dev.dblink.core.usb.OmCaptureUsbSavedMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException

data class DownloadProgress(
    val total: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val currentFileName: String = "",
    val isRunning: Boolean = false,
    val isCancelled: Boolean = false,
    val etaMillis: Long = 0L,
    val title: String = "Downloading from camera",
)

data class UsbImportRequest(
    val handle: Int,
    val fileName: String,
)

private data class DownloadRequestItem(
    val source: String,
    val fileName: String,
    val directory: String = "",
    val fileSize: Long = 0L,
    val attribute: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val usbHandle: Int? = null,
    val usbMode: String = "",
    val usbImportFormat: String = "",
)

private data class DownloadTotals(
    val completed: Int,
    val failed: Int,
)

class ImageDownloadService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var downloadJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val preferencesRepository by lazy { AppPreferencesRepository(applicationContext) }
    private val repository by lazy { DefaultCameraRepository(AppEnvironment.current()) }
    private val omCaptureUsbManager by lazy {
        (application as DbLinkApplication).appContainer.omCaptureUsbManager
    }

    companion object {
        private const val MEDIA_STORE_PRIMARY_VOLUME = "external_primary"
        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 1001
        private const val SOURCE_WIFI = "wifi"
        private const val SOURCE_USB = "usb"
        private const val USB_MODE_LIBRARY = "library"
        private const val USB_MODE_IMPORT_LATEST = "import_latest"
        private const val USB_MODE_CAPTURE_LATEST = "capture_latest"
        private const val ACTION_CANCEL = "dev.dblink.action.DOWNLOAD_CANCEL"
        private const val EXTRA_REQUESTS_JSON = "requests_json"
        private const val EXTRA_SAVE_LOCATION = "save_location"
        private const val EXTRA_PLAY_TARGET_SLOT = "play_target_slot"
        private const val LIBRARY_COMPATIBILITY_MODE_PREF = "library_compatibility_mode"
        private const val LIBRARY_COMPATIBILITY_HIGH_SPEED = "high_speed"
        private const val WIFI_JPEG_PARALLELISM = 4
        private const val WIFI_RAW_PARALLELISM = 3
        private const val WIFI_LARGE_STILL_PARALLELISM = 2
        private const val WIFI_TYPICAL_JPEG_BYTES = 15L * 1024L * 1024L
        private const val WIFI_TYPICAL_RAW_BYTES = 20L * 1024L * 1024L
        private const val WIFI_TARGET_IN_FLIGHT_BYTES = 80L * 1024L * 1024L
        private const val WIFI_LARGE_STILL_THRESHOLD_BYTES = 32L * 1024L * 1024L
        private const val WIFI_TRANSFER_START_SETTLE_DELAY_MS = 300L
        private const val WIFI_TRANSFER_MAX_ATTEMPTS = 3
        private const val WIFI_RAW_START_STAGGER_MS = 260L
        private const val STREAM_WRITE_BUFFER_BYTES = 256 * 1024

        private val _progress = MutableStateFlow(DownloadProgress())
        val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

        fun startDownload(
            context: Context,
            images: List<CameraImage>,
            saveLocation: String = "",
            playTargetSlot: Int? = null,
        ) {
            startRequests(
                context = context,
                requests = images.map { image ->
                    DownloadRequestItem(
                        source = SOURCE_WIFI,
                        fileName = image.fileName,
                        directory = image.directory,
                        fileSize = image.fileSize,
                        attribute = image.attribute,
                        width = image.width,
                        height = image.height,
                    )
                },
                saveLocation = saveLocation,
                playTargetSlot = playTargetSlot,
            )
        }

        fun startUsbImport(
            context: Context,
            items: List<UsbImportRequest>,
            saveLocation: String = "",
        ) {
            startRequests(
                context = context,
                requests = items.map { item ->
                    DownloadRequestItem(
                        source = SOURCE_USB,
                        fileName = item.fileName,
                        usbHandle = item.handle,
                        usbMode = USB_MODE_LIBRARY,
                    )
                },
                saveLocation = saveLocation,
                playTargetSlot = null,
            )
        }

        fun startUsbImportLatest(
            context: Context,
            importFormat: TetherPhoneImportFormat,
            saveLocation: String = "",
        ) {
            startRequests(
                context = context,
                requests = listOf(
                    DownloadRequestItem(
                        source = SOURCE_USB,
                        fileName = "Latest OM image",
                        usbMode = USB_MODE_IMPORT_LATEST,
                        usbImportFormat = importFormat.preferenceValue,
                    ),
                ),
                saveLocation = saveLocation,
                playTargetSlot = null,
            )
        }

        fun startUsbCaptureAndImport(
            context: Context,
            importFormat: TetherPhoneImportFormat,
            saveLocation: String = "",
        ) {
            startRequests(
                context = context,
                requests = listOf(
                    DownloadRequestItem(
                        source = SOURCE_USB,
                        fileName = "New OM capture",
                        usbMode = USB_MODE_CAPTURE_LATEST,
                        usbImportFormat = importFormat.preferenceValue,
                    ),
                ),
                saveLocation = saveLocation,
                playTargetSlot = null,
            )
        }

        fun cancelTransfer(context: Context) {
            val intent = Intent(context, ImageDownloadService::class.java).setAction(ACTION_CANCEL)
            context.startService(intent)
        }

        private fun startRequests(
            context: Context,
            requests: List<DownloadRequestItem>,
            saveLocation: String,
            playTargetSlot: Int?,
        ) {
            if (requests.isEmpty()) return
            val title = notificationTitleFor(requests)
            _progress.value = DownloadProgress(
                total = requests.size,
                isRunning = true,
                isCancelled = false,
                title = title,
            )
            val intent = Intent(context, ImageDownloadService::class.java).apply {
                putExtra(EXTRA_REQUESTS_JSON, serializeRequests(requests))
                putExtra(EXTRA_SAVE_LOCATION, saveLocation)
                putExtra(EXTRA_PLAY_TARGET_SLOT, playTargetSlot ?: 0)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        private fun serializeRequests(requests: List<DownloadRequestItem>): String {
            return JSONArray().apply {
                requests.forEach { request ->
                    put(
                        JSONObject().apply {
                            put("source", request.source)
                            put("directory", request.directory)
                            put("fileName", request.fileName)
                            put("fileSize", request.fileSize)
                            put("attribute", request.attribute)
                            put("width", request.width)
                            put("height", request.height)
                            put("usbMode", request.usbMode)
                            put("usbImportFormat", request.usbImportFormat)
                            request.usbHandle?.let { put("usbHandle", it) }
                        },
                    )
                }
            }.toString()
        }

        private fun deserializeRequests(serialized: String?): List<DownloadRequestItem> {
            if (serialized.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(serialized)
                buildList(array.length()) {
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        add(
                            DownloadRequestItem(
                                source = item.optString("source", SOURCE_WIFI),
                                fileName = item.getString("fileName"),
                                directory = item.optString("directory"),
                                fileSize = item.optLong("fileSize", 0L),
                                attribute = item.optInt("attribute", 0),
                                width = item.optInt("width", 0),
                                height = item.optInt("height", 0),
                                usbHandle = item.optInt("usbHandle").takeIf { item.has("usbHandle") },
                                usbMode = item.optString("usbMode"),
                                usbImportFormat = item.optString("usbImportFormat"),
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                D.err("DOWNLOAD", "Failed to deserialize pending requests", e)
                emptyList()
            }
        }

        private fun notificationTitleFor(requests: List<DownloadRequestItem>): String {
            return when {
                requests.any { it.usbMode == USB_MODE_CAPTURE_LATEST } -> "Importing new capture from OM camera"
                requests.any { it.usbMode == USB_MODE_IMPORT_LATEST } -> "Importing latest photo from OM camera"
                requests.all { it.source == SOURCE_USB } -> "Importing from OM camera"
                requests.all { it.source == SOURCE_WIFI } -> "Downloading from camera"
                else -> "Importing from camera"
            }
        }
    }

    private var saveLocation: String = ""
    private var playTargetSlot: Int? = null
    private val existingMediaCache = linkedSetOf<String>()
    private var existingMediaCachePrimed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelActiveTransfer()
            return START_NOT_STICKY
        }
        val requests = deserializeRequests(intent?.getStringExtra(EXTRA_REQUESTS_JSON))
        if (requests.isEmpty()) {
            D.transfer("No transfer requests in intent, stopping download service")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        saveLocation = intent?.getStringExtra(EXTRA_SAVE_LOCATION)?.ifBlank { null } ?: ""
        playTargetSlot = intent?.getIntExtra(EXTRA_PLAY_TARGET_SLOT, 0)?.takeIf { it in 1..2 }
        val title = notificationTitleFor(requests)
        startForeground(
            NOTIFICATION_ID,
            buildNotification(_progress.value.copy(total = requests.size, title = title)),
        )
        acquireRuntimeLocks(hasWifiTransfers = requests.any { it.source == SOURCE_WIFI })
        startDownloading(requests, title)
        return START_REDELIVER_INTENT
    }

    private fun startDownloading(
        requests: List<DownloadRequestItem>,
        title: String,
    ) {
        downloadJob?.cancel()
        downloadJob = scope.launch {
            resetExistingMediaCache()
            try {
                coroutineScope {
                    val mediaCacheJob = async {
                        primeExistingMediaCache(requests)
                    }
                    val slotJob = async {
                        val requestedSlot = playTargetSlot
                        if (requestedSlot != null && requests.any { it.source == SOURCE_WIFI }) {
                            repository.setPlayTargetSlot(requestedSlot).getOrThrow()
                            kotlinx.coroutines.delay(WIFI_TRANSFER_START_SETTLE_DELAY_MS)
                        }
                    }
                    mediaCacheJob.await()
                    slotJob.await()
                }
            } catch (e: Exception) {
                D.err("DOWNLOAD", "Failed to prepare background transfer pipeline", e)
                _progress.value = DownloadProgress(
                    total = requests.size,
                    completed = 0,
                    failed = requests.size,
                    isRunning = false,
                    title = title,
                )
                updateNotification(_progress.value)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            val pendingRequests = requests.filterNot(::isRequestAlreadySaved)
            val skipped = requests.size - pendingRequests.size
            val startedAt = System.currentTimeMillis()

            if (pendingRequests.isEmpty()) {
                D.transfer("Background transfer complete: 0 saved, 0 failed, $skipped already saved")
                _progress.value = DownloadProgress(
                    total = 0,
                    completed = 0,
                    failed = 0,
                    skipped = skipped,
                    isRunning = false,
                    isCancelled = false,
                    title = title,
                )
                updateNotification(_progress.value)
                val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(
                    NOTIFICATION_ID + 1,
                    NotificationCompat.Builder(this@ImageDownloadService, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_launcher_foreground)
                        .setContentTitle("Download complete")
                        .setContentText(buildCompletionSummary(0, 0, skipped))
                        .setContentIntent(buildLaunchIntent())
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true)
                        .build(),
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            _progress.value = DownloadProgress(
                total = pendingRequests.size,
                completed = 0,
                failed = 0,
                skipped = skipped,
                isRunning = true,
                isCancelled = false,
                title = title,
            )
            updateNotification(_progress.value)

            val totals = if (shouldUseHighSpeedWifiDownloads(pendingRequests)) {
                processHighSpeedWifiRequests(
                    requests = pendingRequests,
                    title = title,
                    startedAt = startedAt,
                    skipped = skipped,
                )
            } else {
                processRequestsSequentially(
                    requests = pendingRequests,
                    title = title,
                    startedAt = startedAt,
                    overallTotal = pendingRequests.size,
                    skipped = skipped,
                )
            }
            val completed = totals.completed
            val failed = totals.failed

            D.transfer(
                "Background transfer complete: $completed/${pendingRequests.size} saved, " +
                    "$failed failed, $skipped already saved",
            )
            _progress.value = DownloadProgress(
                total = pendingRequests.size,
                completed = completed,
                failed = failed,
                skipped = skipped,
                isRunning = false,
                isCancelled = false,
                title = title,
            )

            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(
                NOTIFICATION_ID + 1,
                NotificationCompat.Builder(this@ImageDownloadService, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(
                        when {
                            requests.all { it.source == SOURCE_USB } -> "USB import complete"
                            else -> "Download complete"
                        },
                    )
                    .setContentText(buildCompletionSummary(completed, failed, skipped))
                    .setContentIntent(buildLaunchIntent())
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .build(),
            )

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun shouldUseHighSpeedWifiDownloads(requests: List<DownloadRequestItem>): Boolean {
        if (requests.isEmpty() || requests.any { it.source != SOURCE_WIFI }) {
            return false
        }
        return preferencesRepository.loadStringPref(
            LIBRARY_COMPATIBILITY_MODE_PREF,
            LIBRARY_COMPATIBILITY_HIGH_SPEED,
        ) == LIBRARY_COMPATIBILITY_HIGH_SPEED
    }

    private suspend fun processHighSpeedWifiRequests(
        requests: List<DownloadRequestItem>,
        title: String,
        startedAt: Long,
        skipped: Int,
    ): DownloadTotals {
        val stillRequests = requests.filterNot(::isVideoTransfer)
        val videoRequests = requests.filter(::isVideoTransfer)
        val jpegRequests = stillRequests.filter(::isJpegTransfer)
        val rawRequests = stillRequests.filter(::isRawTransfer)
        val otherStillRequests = stillRequests.filterNot { request ->
            isJpegTransfer(request) || isRawTransfer(request)
        }
        var totals = DownloadTotals(completed = 0, failed = 0)

        if (jpegRequests.isNotEmpty()) {
            totals = processWifiStillPhase(
                requests = jpegRequests,
                phaseLabel = "JPEG",
                title = title,
                startedAt = startedAt,
                overallTotal = requests.size,
                skipped = skipped,
                completedSoFar = totals.completed,
                failedSoFar = totals.failed,
            )
        }

        if (rawRequests.isNotEmpty()) {
            totals = processWifiStillPhase(
                requests = rawRequests,
                phaseLabel = "RAW",
                title = title,
                startedAt = startedAt,
                overallTotal = requests.size,
                skipped = skipped,
                completedSoFar = totals.completed,
                failedSoFar = totals.failed,
                launchStaggerMs = WIFI_RAW_START_STAGGER_MS,
            )
        }

        if (otherStillRequests.isNotEmpty()) {
            totals = processWifiStillPhase(
                requests = otherStillRequests,
                phaseLabel = "still",
                title = title,
                startedAt = startedAt,
                overallTotal = requests.size,
                skipped = skipped,
                completedSoFar = totals.completed,
                failedSoFar = totals.failed,
            )
        }

        if (videoRequests.isNotEmpty()) {
            D.transfer("Running video downloads in dedicated sequential phase (${videoRequests.size} items)")
            totals = processRequestsSequentially(
                requests = videoRequests,
                title = title,
                startedAt = startedAt,
                overallTotal = requests.size,
                completedSoFar = totals.completed,
                failedSoFar = totals.failed,
                skipped = skipped,
            )
        }

        return totals
    }

    private suspend fun processWifiStillPhase(
        requests: List<DownloadRequestItem>,
        phaseLabel: String,
        title: String,
        startedAt: Long,
        overallTotal: Int,
        skipped: Int,
        completedSoFar: Int = 0,
        failedSoFar: Int = 0,
        launchStaggerMs: Long = 0L,
    ): DownloadTotals {
        val parallelism = wifiStillImageParallelism(requests)
        D.transfer(
            "Running high-speed Wi-Fi $phaseLabel downloads with concurrency=$parallelism " +
                "(count=${requests.size})",
        )
        return if (parallelism > 1) {
            processRequestsInParallel(
                requests = requests,
                title = title,
                startedAt = startedAt,
                parallelism = parallelism,
                overallTotal = overallTotal,
                skipped = skipped,
                completedSoFar = completedSoFar,
                failedSoFar = failedSoFar,
                launchStaggerMs = launchStaggerMs,
            )
        } else {
            processRequestsSequentially(
                requests = requests,
                title = title,
                startedAt = startedAt,
                overallTotal = overallTotal,
                skipped = skipped,
                completedSoFar = completedSoFar,
                failedSoFar = failedSoFar,
            )
        }
    }

    private fun wifiStillImageParallelism(requests: List<DownloadRequestItem>): Int {
        if (requests.isEmpty()) {
            return 1
        }
        val estimatedSizes = requests.map(::estimatedStillTransferBytes)
        val maxSize = estimatedSizes.maxOrNull() ?: WIFI_TYPICAL_RAW_BYTES
        val averageSize = estimatedSizes.average().takeIf { it > 0.0 }?.toLong() ?: WIFI_TYPICAL_RAW_BYTES
        val targetParallelism = (WIFI_TARGET_IN_FLIGHT_BYTES / averageSize)
            .toInt()
            .coerceAtLeast(1)
        val concurrencyCap = when {
            maxSize >= WIFI_LARGE_STILL_THRESHOLD_BYTES -> WIFI_LARGE_STILL_PARALLELISM
            requests.all(::isJpegTransfer) -> WIFI_JPEG_PARALLELISM
            requests.any(::isRawTransfer) -> WIFI_RAW_PARALLELISM
            else -> WIFI_RAW_PARALLELISM
        }
        return targetParallelism.coerceAtMost(concurrencyCap)
    }

    private fun estimatedStillTransferBytes(request: DownloadRequestItem): Long {
        if (request.fileSize > 0L) {
            return request.fileSize
        }
        return when {
            isJpegTransfer(request) -> WIFI_TYPICAL_JPEG_BYTES
            isRawTransfer(request) -> WIFI_TYPICAL_RAW_BYTES
            else -> WIFI_TYPICAL_RAW_BYTES
        }
    }

    private fun isVideoTransfer(request: DownloadRequestItem): Boolean {
        val upperName = request.fileName.uppercase()
        return upperName.endsWith(".MOV") ||
            upperName.endsWith(".MP4") ||
            upperName.endsWith(".AVI")
    }

    private fun isRawTransfer(request: DownloadRequestItem): Boolean {
        val upperName = request.fileName.uppercase()
        return upperName.endsWith(".ORF") || upperName.endsWith(".RAW")
    }

    private fun isJpegTransfer(request: DownloadRequestItem): Boolean {
        val upperName = request.fileName.uppercase()
        return upperName.endsWith(".JPG") || upperName.endsWith(".JPEG")
    }

    private suspend fun processRequestsSequentially(
        requests: List<DownloadRequestItem>,
        title: String,
        startedAt: Long,
        overallTotal: Int,
        skipped: Int,
        completedSoFar: Int = 0,
        failedSoFar: Int = 0,
    ): DownloadTotals {
        var completed = completedSoFar
        var failed = failedSoFar
        requests.forEach { request ->
            publishRunningProgress(
                total = overallTotal,
                completed = completed,
                failed = failed,
                currentFileName = request.fileName,
                startedAt = startedAt,
                title = title,
                isRunning = true,
                skipped = skipped,
            )

            val saved = executeTransferRequest(request)

            if (saved) completed++ else failed++

            publishRunningProgress(
                total = overallTotal,
                completed = completed,
                failed = failed,
                currentFileName = request.fileName,
                startedAt = startedAt,
                title = title,
                isRunning = completed + failed < overallTotal,
                skipped = skipped,
            )
        }
        return DownloadTotals(completed = completed, failed = failed)
    }

    private suspend fun processRequestsInParallel(
        requests: List<DownloadRequestItem>,
        title: String,
        startedAt: Long,
        parallelism: Int,
        overallTotal: Int,
        skipped: Int,
        completedSoFar: Int = 0,
        failedSoFar: Int = 0,
        launchStaggerMs: Long = 0L,
    ): DownloadTotals {
        val semaphore = Semaphore(parallelism.coerceAtLeast(1))
        val progressLock = Any()
        var completed = completedSoFar
        var failed = failedSoFar

        coroutineScope {
            requests.mapIndexed { index, request ->
                launch {
                    val staggerDelayMs = if (launchStaggerMs > 0L && parallelism > 1) {
                        launchStaggerMs * (index % parallelism)
                    } else {
                        0L
                    }
                    if (staggerDelayMs > 0L) {
                        delay(staggerDelayMs)
                    }
                    semaphore.withPermit {
                        synchronized(progressLock) {
                            publishRunningProgress(
                                total = overallTotal,
                                completed = completed,
                                failed = failed,
                                currentFileName = request.fileName,
                                startedAt = startedAt,
                                title = title,
                                isRunning = true,
                                skipped = skipped,
                            )
                        }

                        val saved = executeTransferRequest(request)

                        synchronized(progressLock) {
                            if (saved) completed++ else failed++
                            publishRunningProgress(
                                total = overallTotal,
                                completed = completed,
                                failed = failed,
                                currentFileName = request.fileName,
                                startedAt = startedAt,
                                title = title,
                                isRunning = completed + failed < overallTotal,
                                skipped = skipped,
                            )
                        }
                    }
                }
            }.joinAll()
        }

        return DownloadTotals(completed = completed, failed = failed)
    }

    private fun publishRunningProgress(
        total: Int,
        completed: Int,
        failed: Int,
        currentFileName: String,
        startedAt: Long,
        title: String,
        isRunning: Boolean,
        skipped: Int,
    ) {
        _progress.value = _progress.value.copy(
            total = total,
            completed = completed,
            failed = failed,
            skipped = skipped,
            currentFileName = currentFileName,
            isRunning = isRunning,
            isCancelled = false,
            etaMillis = estimateRemainingMillis(startedAt, completed + failed, total),
            title = title,
        )
        updateNotification(_progress.value)
    }

    private suspend fun executeTransferRequest(request: DownloadRequestItem): Boolean {
        return try {
            processRequest(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            D.err("DOWNLOAD", "Failed transfer for ${request.fileName}", throwable)
            false
        }
    }

    private suspend fun processRequest(request: DownloadRequestItem): Boolean {
        return when (request.source) {
            SOURCE_USB -> processUsbRequest(request)
            else -> processWifiRequest(request)
        }
    }

    private suspend fun processWifiRequest(request: DownloadRequestItem): Boolean {
        val image = CameraImage(
            directory = request.directory,
            fileName = request.fileName,
            fileSize = request.fileSize,
            attribute = request.attribute,
            width = request.width,
            height = request.height,
        )
        if (isAlreadySaved(image.fileName, image.dateFolderName)) {
            D.transfer("Skipping already-downloaded Wi-Fi media ${image.fileName}")
            return true
        }
        var lastFailure: Throwable? = null
        repeat(WIFI_TRANSFER_MAX_ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            val attemptNumber = attempt + 1
            if (attempt > 0) {
                val retryDelayMs = when (attempt) {
                    1 -> 450L
                    else -> 1_000L
                }
                D.transfer(
                    "Retrying Wi-Fi media ${image.fileName} " +
                        "attempt=$attemptNumber/$WIFI_TRANSFER_MAX_ATTEMPTS delay=${retryDelayMs}ms",
                )
                kotlinx.coroutines.delay(retryDelayMs)
            }
            try {
                return saveStreamToGallery(
                    fileName = image.fileName,
                    dateFolder = image.dateFolderName,
                    rethrowWriteFailures = true,
                ) { outputStream ->
                    repository.downloadFullImageTo(image, outputStream).getOrThrow()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                lastFailure = throwable
                if (!isTransientWifiTransferFailure(throwable) || attempt == WIFI_TRANSFER_MAX_ATTEMPTS - 1) {
                    throw throwable
                }
                D.err(
                    "DOWNLOAD",
                    "Transient Wi-Fi transfer failure for ${image.fileName}; will retry",
                    throwable,
                )
            }
        }
        throw lastFailure ?: IllegalStateException("Unknown Wi-Fi transfer failure for ${image.fileName}")
    }

    private fun isRequestAlreadySaved(request: DownloadRequestItem): Boolean {
        if (request.usbMode == USB_MODE_IMPORT_LATEST || request.usbMode == USB_MODE_CAPTURE_LATEST) {
            return false
        }
        return isAlreadySaved(request.fileName, expectedDateFolderFor(request))
    }

    private suspend fun processUsbRequest(request: DownloadRequestItem): Boolean {
        val saveMedia: suspend (String, ByteArray) -> OmCaptureUsbSavedMedia = { fileName, data ->
            saveToGalleryDetailed(fileName, data)
                ?: error("Failed to save $fileName to the phone gallery.")
        }
        return when (request.usbMode) {
            USB_MODE_CAPTURE_LATEST -> {
                val importFormat = TetherPhoneImportFormat.fromPreferenceValue(request.usbImportFormat)
                omCaptureUsbManager.captureAndImportLatestImage(
                    saveMedia = saveMedia,
                    importFormat = importFormat,
                ).getOrThrow()
                true
            }

            USB_MODE_IMPORT_LATEST -> {
                val importFormat = TetherPhoneImportFormat.fromPreferenceValue(request.usbImportFormat)
                omCaptureUsbManager.importLatestImage(
                    saveMedia = saveMedia,
                    importFormat = importFormat,
                ).getOrThrow()
                true
            }

            else -> {
                val handle = request.usbHandle
                    ?: error("USB transfer request missing object handle for ${request.fileName}")
                if (isAlreadySaved(request.fileName, "")) {
                    D.transfer("Skipping already-imported USB media ${request.fileName}")
                    return true
                }
                omCaptureUsbManager.importLibraryHandle(
                    handle = handle,
                    saveMedia = saveMedia,
                ).getOrThrow()
                true
            }
        }
    }

    private fun mediaCollectionForFileName(fileName: String): Uri {
        return if (fileName.uppercase().endsWith(".MOV") || fileName.uppercase().endsWith(".MP4")) {
            MediaStore.Video.Media.getContentUri(MEDIA_STORE_PRIMARY_VOLUME)
        } else {
            MediaStore.Images.Media.getContentUri(MEDIA_STORE_PRIMARY_VOLUME)
        }
    }

    private fun resolveMimeType(fileName: String): String {
        return when {
            fileName.uppercase().endsWith(".JPG") || fileName.uppercase().endsWith(".JPEG") -> "image/jpeg"
            fileName.uppercase().endsWith(".ORF") -> "image/x-olympus-orf"
            fileName.uppercase().endsWith(".MOV") -> "video/quicktime"
            fileName.uppercase().endsWith(".MP4") -> "video/mp4"
            else -> "application/octet-stream"
        }
    }

    private fun buildRelativePath(dateFolder: String): String {
        val configuredLocation = saveLocation.ifBlank { "Pictures/db link" }
        val basePath = if (configuredLocation.startsWith(Environment.DIRECTORY_PICTURES)) {
            configuredLocation
        } else {
            "${Environment.DIRECTORY_PICTURES}/${configuredLocation.removePrefix("Pictures/")}"
        }
        val relativePath = if (dateFolder.isNotEmpty()) "$basePath/$dateFolder" else basePath
        return relativePath.trimEnd('/') + "/"
    }

    private fun isAlreadySaved(fileName: String, dateFolder: String): Boolean {
        val cacheKey = buildMediaCacheKey(fileName, dateFolder)
        if (existingMediaCachePrimed) {
            return cacheKey in existingMediaCache
        }
        val resolver = contentResolver
        val collection = mediaCollectionForFileName(fileName)
        val relativePath = buildRelativePath(dateFolder)
        val selection = buildString {
            append("${MediaStore.MediaColumns.DISPLAY_NAME}=?")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                append(" AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?")
            }
        }
        val selectionArgs = buildList {
            add(fileName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(relativePath)
            }
        }.toTypedArray()
        return resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            selection,
            selectionArgs,
            null,
        )?.use { cursor -> cursor.moveToFirst() } == true
    }

    private fun saveToGallery(fileName: String, data: ByteArray, dateFolder: String): Boolean {
        return saveToGalleryDetailed(fileName, data, dateFolder) != null
    }

    private suspend fun saveStreamToGallery(
        fileName: String,
        dateFolder: String = "",
        rethrowWriteFailures: Boolean = false,
        writeData: suspend (java.io.OutputStream) -> Unit,
    ): Boolean {
        return saveStreamToGalleryDetailed(
            fileName = fileName,
            dateFolder = dateFolder,
            rethrowWriteFailures = rethrowWriteFailures,
            writeData = writeData,
        ) != null
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
        rethrowWriteFailures: Boolean = false,
        writeData: suspend (java.io.OutputStream) -> Unit,
    ): OmCaptureUsbSavedMedia? {
        val resolver = contentResolver
        val mimeType = resolveMimeType(fileName)
        val relativePath = buildRelativePath(dateFolder)
        val absolutePath = "/storage/emulated/0/$relativePath$fileName"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = mediaCollectionForFileName(fileName)
        val uri = try {
            resolver.insert(collection, contentValues)
        } catch (e: Exception) {
            D.err("DOWNLOAD", "MediaStore insert failed: $fileName", e)
            return null
        } ?: return null
        try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.buffered(STREAM_WRITE_BUFFER_BYTES).use { bufferedOutput ->
                    writeData(bufferedOutput)
                    bufferedOutput.flush()
                }
            }
                ?: throw IllegalStateException("ContentResolver returned null OutputStream for $uri")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            rememberSavedMedia(fileName, dateFolder)
            return OmCaptureUsbSavedMedia(
                uriString = uri.toString(),
                relativePath = relativePath,
                absolutePath = absolutePath,
                displayName = fileName,
            )
        } catch (e: Exception) {
            if (e is CancellationException) {
                try {
                    resolver.delete(uri, null, null)
                } catch (deleteErr: Exception) {
                    D.err("DOWNLOAD", "Failed to delete canceled MediaStore entry: $uri", deleteErr)
                }
                throw e
            }
            D.err("DOWNLOAD", "Save failed, removing orphaned MediaStore entry: $fileName", e)
            // Clean up the IS_PENDING entry so it doesn't stay invisible in the gallery
            try {
                resolver.delete(uri, null, null)
            } catch (deleteErr: Exception) {
                D.err("DOWNLOAD", "Failed to delete orphaned MediaStore entry: $uri", deleteErr)
            }
            if (rethrowWriteFailures) {
                throw e
            }
            return null
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Image download",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while importing photos from the camera"
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(progress: DownloadProgress): Notification {
        val processed = progress.completed + progress.failed
        val etaLabel = formatEta(progress.etaMillis)
        val contentText = buildString {
            when {
                progress.isRunning && progress.total > 0 -> {
                    append("${processed}/${progress.total}")
                    if (etaLabel.isNotBlank()) {
                        append("  ")
                        append(etaLabel)
                    }
                }
                progress.total == 0 && progress.skipped > 0 -> {
                    append("${progress.skipped} already saved")
                }
                else -> {
                    append(buildCompletionSummary(progress.completed, progress.failed, progress.skipped))
                }
            }
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(progress.title)
            .setContentText(contentText)
            .setContentIntent(buildLaunchIntent())
            .setProgress(progress.total.coerceAtLeast(1), processed.coerceAtMost(progress.total), false)
            .setOngoing(progress.isRunning)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (progress.isRunning) {
            builder.addAction(
                0,
                getString(R.string.common_cancel),
                buildCancelIntent(),
            )
        }
        return builder.build()
    }

    private fun updateNotification(progress: DownloadProgress) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(progress))
    }

    private fun buildCompletionSummary(completed: Int, failed: Int, skipped: Int): String {
        return buildString {
            when {
                completed > 0 || failed > 0 -> {
                    append("$completed saved")
                    if (failed > 0) {
                        append(", $failed failed")
                    }
                }
                skipped > 0 -> append("$skipped already saved")
                else -> append("Nothing to download")
            }
            if (skipped > 0 && (completed > 0 || failed > 0)) {
                append(", $skipped already saved")
            }
        }
    }

    private fun estimateRemainingMillis(startedAt: Long, processed: Int, total: Int): Long {
        if (processed <= 0 || total <= processed) return 0L
        val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
        val averagePerItem = elapsed / processed
        return averagePerItem * (total - processed)
    }

    private fun formatEta(etaMillis: Long): String {
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

    private fun buildLaunchIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, NOTIFICATION_ID, launchIntent, flags)
    }

    private fun buildCancelIntent(): PendingIntent {
        val intent = Intent(this, ImageDownloadService::class.java).setAction(ACTION_CANCEL)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, NOTIFICATION_ID + 2, intent, flags)
    }

    private fun cancelActiveTransfer() {
        val current = _progress.value
        D.transfer("User requested transfer cancellation")
        repository.cancelPendingTransfers()
        downloadJob?.cancel(CancellationException("Canceled by user"))
        _progress.value = current.copy(
            isRunning = false,
            isCancelled = current.total > 0,
            etaMillis = 0L,
            currentFileName = current.currentFileName.ifBlank { "Canceled" },
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
        manager.cancel(NOTIFICATION_ID + 1)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun resetExistingMediaCache() {
        existingMediaCache.clear()
        existingMediaCachePrimed = false
    }

    private fun buildMediaCacheKey(fileName: String, dateFolder: String): String {
        return buildRelativePath(dateFolder) + fileName
    }

    private fun rememberSavedMedia(fileName: String, dateFolder: String) {
        existingMediaCache += buildMediaCacheKey(fileName, dateFolder)
    }

    private fun primeExistingMediaCache(requests: List<DownloadRequestItem>) {
        if (existingMediaCachePrimed) {
            return
        }
        val requestedTargets = requests
            .filter { it.source == SOURCE_WIFI || it.usbMode == USB_MODE_LIBRARY }
            .map { request ->
                CachedMediaLookupTarget(
                    collection = mediaCollectionForFileName(request.fileName),
                    relativePath = buildRelativePath(expectedDateFolderFor(request)),
                    fileName = request.fileName,
                )
            }
            .distinct()
        if (requestedTargets.isEmpty()) {
            existingMediaCachePrimed = true
            return
        }
        val resolver = contentResolver
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        requestedTargets
            .groupBy { it.collection to it.relativePath }
            .forEach { (groupKey, groupTargets) ->
                val (collection, relativePath) = groupKey
                groupTargets
                    .map { it.fileName }
                    .distinct()
                    .chunked(40)
                    .forEach { fileNames ->
                        val placeholders = fileNames.joinToString(",") { "?" }
                        val selection = buildString {
                            append("${MediaStore.MediaColumns.DISPLAY_NAME} IN ($placeholders)")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                append(" AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?")
                            }
                        }
                        val selectionArgs = buildList {
                            addAll(fileNames)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                add(relativePath)
                            }
                        }.toTypedArray()
                        resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                            val relativePathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                            while (cursor.moveToNext()) {
                                val fileName = cursor.getString(nameCol) ?: continue
                                val storedRelativePath = cursor.getString(relativePathCol) ?: relativePath
                                existingMediaCache += storedRelativePath + fileName
                            }
                        }
                    }
            }
        existingMediaCachePrimed = true
    }

    private fun expectedDateFolderFor(request: DownloadRequestItem): String {
        if (request.source != SOURCE_WIFI || request.directory.isBlank()) {
            return ""
        }
        return CameraImage(
            directory = request.directory,
            fileName = request.fileName,
            fileSize = request.fileSize,
            attribute = request.attribute,
            width = request.width,
            height = request.height,
        ).dateFolderName
    }

    private data class CachedMediaLookupTarget(
        val collection: Uri,
        val relativePath: String,
        val fileName: String,
    )

    private fun isTransientWifiTransferFailure(throwable: Throwable): Boolean {
        return when (throwable) {
            is SocketTimeoutException,
            is SocketException,
            is ConnectException,
            is EOFException,
            -> true
            else -> {
                val message = throwable.message.orEmpty()
                "HTTP 500" in message ||
                    "HTTP 503" in message ||
                    "HTTP 520" in message ||
                    "timeout" in message.lowercase() ||
                    "socket closed" in message.lowercase() ||
                    "unexpected end of stream" in message.lowercase()
            }
        }
    }

    private fun acquireRuntimeLocks(hasWifiTransfers: Boolean) {
        if (wakeLock?.isHeld != true) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:download").apply {
                setReferenceCounted(false)
                acquire(2 * 60 * 60 * 1000L)
            }
        }

        if (!hasWifiTransfers) {
            return
        }

        if (wifiLock?.isHeld != true) {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:camera-download").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseRuntimeLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        } finally {
            wakeLock = null
        }

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Exception) {
        } finally {
            wifiLock = null
        }
    }

    override fun onDestroy() {
        downloadJob?.cancel()
        releaseRuntimeLocks()
        scope.cancel()
        super.onDestroy()
    }
}
