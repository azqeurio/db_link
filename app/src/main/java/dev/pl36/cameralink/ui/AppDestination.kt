package dev.pl36.cameralink.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.ui.graphics.vector.ImageVector
import dev.pl36.cameralink.R

enum class AppDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    Dashboard("dashboard", R.string.dest_dashboard, Icons.Rounded.Dashboard),
    Remote("remote", R.string.dest_remote, Icons.Rounded.CameraAlt),
    Transfer("transfer", R.string.dest_library, Icons.Rounded.Collections),
    GeoTag("geotag", R.string.dest_geotag, Icons.Rounded.LocationOn),
    Settings("settings", R.string.dest_settings, Icons.Rounded.Settings);

    companion object {
        fun fromRoute(route: String?): AppDestination =
            entries.find { it.route == route } ?: Dashboard
    }
}
