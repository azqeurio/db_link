package dev.pl36.cameralink.core.download

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
import dev.pl36.cameralink.CameraLinkApplication
import dev.pl36.cameralink.R
import dev.pl36.cameralink.core.config.AppEnvironment
import dev.pl36.cameralink.core.logging.D
import dev.pl36.cameralink.core.model.TetherPhoneImportFormat
import dev.pl36.cameralink.core.protocol.CameraImage
import dev.pl36.cameralink.core.protocol.DefaultCameraRepository
import dev.pl36.cameralink.core.usb.OmCaptureUsbSavedMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class DownloadProgress(
    val total: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val currentFileName: String = "",
    val isRunning: Boolean = false,
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

class ImageDownloadService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var downloadJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val repository by lazy { DefaultCameraRepository(AppEnvironment.current()) }
    private val omCaptureUsbManager by lazy {
        (application as CameraLinkApplication).appContainer.omCaptureUsbManager
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
        private const val EXTRA_REQUESTS_JSON = "requests_json"
        private const val EXTRA_SAVE_LOCATION = "save_location"
        private const val EXTRA_PLAY_TARGET_SLOT = "play_target_slot"

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
            var completed = 0
            var failed = 0
            val startedAt = System.currentTimeMillis()

            val requestedSlot = playTargetSlot
            if (requestedSlot != null && requests.any { it.source == SOURCE_WIFI }) {
                try {
                    val currentSlot = repository.getPlayTargetSlot().getOrThrow()
                    if (currentSlot != requestedSlot) {
                        repository.setPlayTargetSlot(requestedSlot).getOrThrow()
                    }
                } catch (e: Exception) {
                    D.err("DOWNLOAD", "Failed to apply play target slot $requestedSlot before background transfer", e)
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
            }

            requests.forEach { request ->
                _progress.value = _progress.value.copy(
                    total = requests.size,
                    completed = completed,
                    failed = failed,
                    currentFileName = request.fileName,
                    isRunning = true,
                    etaMillis = estimateRemainingMillis(startedAt, completed + failed, requests.size),
                    title = title,
                )
                updateNotification(_progress.value)

                val saved = runCatching { processRequest(request) }
                    .onFailure { throwable ->
                        D.err("DOWNLOAD", "Failed transfer for ${request.fileName}", throwable)
                    }
                    .getOrDefault(false)

                if (saved) completed++ else failed++

                _progress.value = _progress.value.copy(
                    total = requests.size,
                    completed = completed,
                    failed = failed,
                    currentFileName = request.fileName,
                    isRunning = completed + failed < requests.size,
                    etaMillis = estimateRemainingMillis(startedAt, completed + failed, requests.size),
                    title = title,
                )
                updateNotification(_progress.value)
            }

            D.transfer("Background transfer complete: $completed/${requests.size} saved, $failed failed")
            _progress.value = DownloadProgress(
                total = requests.size,
                completed = completed,
                failed = failed,
                isRunning = false,
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
                    .setContentText("${completed} saved" + if (failed > 0) ", ${failed} failed" else "")
                    .setContentIntent(buildLaunchIntent())
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .build(),
            )

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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
        val bytes = repository.getFullImage(image).getOrThrow()
        return saveToGallery(image.fileName, bytes, image.dateFolderName)
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

    private fun saveToGalleryDetailed(
        fileName: String,
        data: ByteArray,
        dateFolder: String = "",
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
            resolver.openOutputStream(uri)?.use { it.write(data) }
                ?: throw IllegalStateException("ContentResolver returned null OutputStream for $uri")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            return OmCaptureUsbSavedMedia(
                uriString = uri.toString(),
                relativePath = relativePath,
                absolutePath = absolutePath,
                displayName = fileName,
            )
        } catch (e: Exception) {
            D.err("DOWNLOAD", "Save failed, removing orphaned MediaStore entry: $fileName", e)
            // Clean up the IS_PENDING entry so it doesn't stay invisible in the gallery
            try {
                resolver.delete(uri, null, null)
            } catch (deleteErr: Exception) {
                D.err("DOWNLOAD", "Failed to delete orphaned MediaStore entry: $uri", deleteErr)
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
        val currentLabel = progress.currentFileName.ifBlank { "Preparing transfer" }
        val etaLabel = formatEta(progress.etaMillis)
        val contentText = buildString {
            append(currentLabel)
            append("  ")
            append("${processed}/${progress.total}")
            if (etaLabel.isNotBlank()) {
                append("  ")
                append(etaLabel)
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(progress.title)
            .setContentText(contentText)
            .setContentIntent(buildLaunchIntent())
            .setProgress(progress.total.coerceAtLeast(1), processed.coerceAtMost(progress.total), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(progress: DownloadProgress) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(progress))
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
