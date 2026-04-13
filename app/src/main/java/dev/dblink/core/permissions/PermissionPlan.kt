package dev.dblink.core.permissions

data class PermissionPlan(
    val feature: String,
    val title: String,
    val permissions: List<String>,
    val rationale: String,
)

object PermissionPlanner {
    val plans: List<PermissionPlan> = listOf(
        PermissionPlan(
            feature = "devices",
            title = "Discovery and pairing",
            permissions = listOf(
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.NEARBY_WIFI_DEVICES",
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.ACCESS_FINE_LOCATION",
            ),
            rationale = "Request discovery-related permissions only when the user starts camera setup or reconnection.",
        ),
        PermissionPlan(
            feature = "remote",
            title = "Remote shooting",
            permissions = listOf(
                "android.permission.CAMERA",
                "android.permission.RECORD_AUDIO",
            ),
            rationale = "Camera and microphone access support QR setup and voice-triggered timer flows.",
        ),
        PermissionPlan(
            feature = "transfer",
            title = "Import and save media",
            permissions = listOf(
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VIDEO",
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
                "android.permission.READ_EXTERNAL_STORAGE",
            ),
            rationale = "The modern app reads and saves media through scoped APIs and can handle Android 14 partial photo access.",
        ),
        PermissionPlan(
            feature = "notifications",
            title = "Background status",
            permissions = listOf(
                "android.permission.POST_NOTIFICATIONS",
            ),
            rationale = "Show camera download progress and ongoing geotag status while the app is in the background.",
        ),
        PermissionPlan(
            feature = "geotag",
            title = "Geotagging and place matching",
            permissions = listOf(
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_BACKGROUND_LOCATION",
            ),
            rationale = "Use a foreground tracking service so geotag sessions can continue while the screen is off or the app is in the background.",
        ),
    )
}
