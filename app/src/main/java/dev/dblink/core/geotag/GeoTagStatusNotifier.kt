package dev.dblink.core.geotag

import android.annotation.SuppressLint
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.dblink.R
import dev.dblink.core.model.GeoTaggingSnapshot

class GeoTagStatusNotifier(
    private val context: Context,
) {
    companion object {
        private const val CHANNEL_ID = "geotag_status_channel"
        private const val NOTIFICATION_ID = 1402
    }

    @SuppressLint("MissingPermission")
    fun update(snapshot: GeoTaggingSnapshot) {
        if (!snapshot.sessionActive) {
            cancel()
            return
        }
        if (!canPostNotifications()) {
            return
        }
        createChannelIfNeeded()
        NotificationManagerCompat.from(context).notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.notification_geotag_title))
                .setContentText(
                    snapshot.latestSample?.placeName
                        ?: context.getString(R.string.notification_geotag_text),
                )
                .setContentIntent(buildLaunchIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build(),
        )
    }

    fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun createChannelIfNeeded() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_geotag_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_geotag_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildLaunchIntent(): PendingIntent? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, NOTIFICATION_ID, launchIntent, flags)
    }
}
