package dev.pl36.cameralink.core.geotag

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.pl36.cameralink.R
import dev.pl36.cameralink.core.model.GeoTagLocationSample
import dev.pl36.cameralink.core.model.GeoTaggingSnapshot
import dev.pl36.cameralink.core.preferences.AppPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GeoTagTrackingState(
    val isRunning: Boolean = false,
    val statusLabel: String = "Ready",
    val latestSample: GeoTagLocationSample? = null,
    val samples: List<GeoTagLocationSample> = emptyList(),
    val totalPinsCaptured: Int = 0,
)

class GeoTagTrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferencesRepository by lazy { AppPreferencesRepository(applicationContext) }
    private val locationManager by lazy { GeoTagLocationManager(applicationContext) }
    private var bootstrapJob: Job? = null
    @Volatile
    private var trackingActive = false
    private var currentSnapshot = GeoTaggingSnapshot()

    companion object {
        private const val MAX_STORED_SAMPLES = 64
        private const val CHANNEL_ID = "geotag_tracking_service"
        private const val NOTIFICATION_ID = 1403
        private const val ACTION_START = "dev.pl36.cameralink.action.GEOTAG_START"
        private const val ACTION_STOP = "dev.pl36.cameralink.action.GEOTAG_STOP"

        private val _state = MutableStateFlow(GeoTagTrackingState())
        val state: StateFlow<GeoTagTrackingState> = _state.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, GeoTagTrackingService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, GeoTagTrackingService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking(reason = "Tracking stopped")
                return START_NOT_STICKY
            }

            else -> {
                if (trackingActive) {
                    updateNotification(buildNotification(currentSnapshot))
                    return START_STICKY
                }
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(
                        currentSnapshot.copy(
                            sessionActive = true,
                            statusLabel = "Starting background tracking...",
                        ),
                    ),
                )
                startTracking()
                return START_STICKY
            }
        }
    }

    private fun startTracking() {
        bootstrapJob?.cancel()
        bootstrapJob = scope.launch {
            currentSnapshot = preferencesRepository.loadGeoTagging().copy(
                sessionActive = true,
                statusLabel = "Tracking",
            )
            trackingActive = true
            emitState(currentSnapshot)
            preferencesRepository.saveGeoTagging(currentSnapshot)
            updateNotification(buildNotification(currentSnapshot))
            locationManager.startSession(
                onSample = { sample ->
                    scope.launch {
                        val updated = currentSnapshot.copy(
                            sessionActive = true,
                            statusLabel = "Tracking",
                            latestSample = sample,
                            samples = (currentSnapshot.samples + sample)
                                .sortedByDescending { it.capturedAtMillis }
                                .distinctBy { it.capturedAtMillis to it.latitude to it.longitude }
                                .take(MAX_STORED_SAMPLES),
                            totalPinsCaptured = currentSnapshot.totalPinsCaptured + 1,
                        )
                        currentSnapshot = updated
                        emitState(updated)
                        preferencesRepository.saveGeoTagging(updated)
                        updateNotification(buildNotification(updated))
                    }
                },
                onFailure = { throwable ->
                    scope.launch {
                        val failedSnapshot = currentSnapshot.copy(
                            sessionActive = false,
                            statusLabel = throwable.message ?: "Tracking failed",
                        )
                        currentSnapshot = failedSnapshot
                        emitState(failedSnapshot)
                        preferencesRepository.saveGeoTagging(failedSnapshot)
                        trackingActive = false
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                },
            )
        }
    }

    private fun stopTracking(reason: String) {
        bootstrapJob?.cancel()
        locationManager.stopSession()
        trackingActive = false
        val stoppedSnapshot = currentSnapshot.copy(
            sessionActive = false,
            statusLabel = if (currentSnapshot.latestSample != null) reason else "Ready",
        )
        currentSnapshot = stoppedSnapshot
        scope.launch {
            emitState(stoppedSnapshot)
            preferencesRepository.saveGeoTagging(stoppedSnapshot)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun emitState(snapshot: GeoTaggingSnapshot) {
        _state.value = GeoTagTrackingState(
            isRunning = snapshot.sessionActive,
            statusLabel = snapshot.statusLabel,
            latestSample = snapshot.latestSample,
            samples = snapshot.samples,
            totalPinsCaptured = snapshot.totalPinsCaptured,
        )
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Location tracking",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while geotag tracking continues in the background"
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(snapshot: GeoTaggingSnapshot): Notification {
        val contentText = snapshot.latestSample?.let { sample ->
            sample.placeName ?: "${"%.5f".format(sample.latitude)}, ${"%.5f".format(sample.longitude)}"
        } ?: snapshot.statusLabel
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Location tracking active")
            .setContentText(contentText)
            .setSubText("${snapshot.samples.size} samples")
            .setContentIntent(buildLaunchIntent())
            .addAction(
                0,
                "Stop",
                buildStopIntent(),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(notification: Notification) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildLaunchIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, NOTIFICATION_ID, launchIntent, flags)
    }

    private fun buildStopIntent(): PendingIntent {
        val intent = Intent(this, GeoTagTrackingService::class.java).setAction(ACTION_STOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, NOTIFICATION_ID + 1, intent, flags)
    }

    override fun onDestroy() {
        locationManager.stopSession()
        scope.cancel()
        super.onDestroy()
    }
}
