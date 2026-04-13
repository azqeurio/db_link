package dev.pl36.cameralink.feature.geotag

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLocation
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.GoogleMap as GoogleMapSdk
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import dev.pl36.cameralink.BuildConfig
import dev.pl36.cameralink.R
import dev.pl36.cameralink.feature.transfer.ImageGeoTagInfo
import dev.pl36.cameralink.core.model.GeoTaggingSnapshot
import dev.pl36.cameralink.core.presentation.accuracyLabel
import dev.pl36.cameralink.core.presentation.altitudeLabel
import dev.pl36.cameralink.core.presentation.coordinateLabel
import dev.pl36.cameralink.core.presentation.displayName
import dev.pl36.cameralink.core.presentation.speedLabel
import dev.pl36.cameralink.core.presentation.timeLabel
import dev.pl36.cameralink.core.ui.GlassCard
import dev.pl36.cameralink.core.ui.KeyValueRow
import dev.pl36.cameralink.core.ui.MetricPill
import dev.pl36.cameralink.core.ui.SectionHeader
import dev.pl36.cameralink.core.ui.SettingToggleRow
import dev.pl36.cameralink.core.ui.StatusBadge
import dev.pl36.cameralink.ui.theme.AppleBlue
import dev.pl36.cameralink.ui.theme.AppleOrange
import dev.pl36.cameralink.ui.theme.Graphite
import dev.pl36.cameralink.ui.theme.Iron

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GeoTagScreen(
    geoTagging: GeoTaggingSnapshot,
    photoGeoTags: Map<String, ImageGeoTagInfo>,
    autoMatchEnabled: Boolean,
    onAutoMatchChanged: (Boolean) -> Unit,
    onPermissionsResolved: (granted: Boolean, preciseGranted: Boolean) -> Unit,
    onCapturePin: () -> Unit,
    onSyncNow: () -> Unit,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
    onAdjustClockOffset: (Int) -> Unit,
) {
    val context = LocalContext.current
    var pendingAction by rememberSaveable { mutableStateOf(GeoPendingAction.None) }
    val geoPermissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }.toTypedArray()
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val preciseGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = preciseGranted || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        onPermissionsResolved(coarseGranted, preciseGranted)
        if (coarseGranted) {
            when (pendingAction) {
                GeoPendingAction.CapturePin -> onCapturePin()
                GeoPendingAction.SyncNow -> onSyncNow()
                GeoPendingAction.StartSession -> onStartSession()
                GeoPendingAction.None -> Unit
            }
        }
        pendingAction = GeoPendingAction.None
    }

    LaunchedEffect(Unit) {
        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = fineGranted || ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        onPermissionsResolved(coarseGranted, fineGranted)
    }

    val latestSample = geoTagging.latestSample
    val routePoints = remember(geoTagging.samples) {
        geoTagging.samples.map { LatLng(it.latitude, it.longitude) }
    }
    val photoGroups = remember(photoGeoTags) {
        photoGeoTags
            .entries
            .groupBy { entry -> entry.value.latitude to entry.value.longitude }
            .map { (key, entries) ->
                PhotoPinGroup(
                    latitude = key.first,
                    longitude = key.second,
                    count = entries.size,
                    fileNames = entries.map { it.key }.sorted(),
                )
            }
            .sortedByDescending { it.count }
    }
    var historyExpanded by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
    ) {
        item {
            SectionHeader(stringResource(R.string.geo_title))
        }

        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusBadge(
                            label = geoTagging.statusLabel,
                            color = if (geoTagging.sessionActive) AppleOrange else Color.Gray,
                        )
                        Icon(
                            imageVector = Icons.Rounded.LocationOn,
                            contentDescription = null,
                            tint = if (geoTagging.sessionActive) AppleOrange else Color.White.copy(alpha = 0.2f),
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        MetricPill(stringResource(R.string.geo_active_pins), geoTagging.samples.size.toString())
                        MetricPill(stringResource(R.string.geo_time_offset), "${geoTagging.clockOffsetMinutes}m")
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(R.string.geo_actions),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.5f),
                            fontWeight = FontWeight.Bold,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            GeoActionButton(
                                label = stringResource(R.string.geo_drop_pin),
                                icon = Icons.Rounded.AddLocation,
                                containerColor = AppleBlue,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (geoTagging.permissionGranted) onCapturePin()
                                else {
                                    pendingAction = GeoPendingAction.CapturePin
                                    permissionLauncher.launch(geoPermissions)
                                }
                            }
                            GeoActionButton(
                                label = stringResource(R.string.geo_sync_now),
                                icon = Icons.Rounded.Schedule,
                                containerColor = Graphite,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (geoTagging.permissionGranted) onSyncNow()
                                else {
                                    pendingAction = GeoPendingAction.SyncNow
                                    permissionLauncher.launch(geoPermissions)
                                }
                            }
                        }

                        GeoActionButton(
                            label = stringResource(
                                if (geoTagging.sessionActive) R.string.geo_stop_tracking
                                else R.string.geo_start_tracking,
                            ),
                            icon = Icons.Rounded.LocationOn,
                            containerColor = if (geoTagging.sessionActive) Color.Red else AppleOrange,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (geoTagging.sessionActive) onStopSession()
                            else {
                                if (geoTagging.permissionGranted) onStartSession()
                                else {
                                    pendingAction = GeoPendingAction.StartSession
                                    permissionLauncher.launch(geoPermissions)
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = stringResource(R.string.geo_sync_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.5f),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SettingToggleRow(
                            title = stringResource(R.string.geo_auto_match),
                            summary = stringResource(R.string.geo_auto_match_summary),
                            checked = autoMatchEnabled,
                            onCheckedChange = onAutoMatchChanged,
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Rounded.Schedule, null, tint = AppleBlue, modifier = Modifier.size(20.dp))
                            Text(stringResource(R.string.geo_adjust_clock), style = MaterialTheme.typography.titleMedium, color = Color.White)
                            Spacer(Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                arrayOf(-1, 1, 5).forEach { delta ->
                                    OutlinedButton(
                                        onClick = { onAdjustClockOffset(delta) },
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.height(32.dp),
                                    ) {
                                        Text(if (delta > 0) "+${delta}m" else "${delta}m", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = stringResource(R.string.geo_route_map),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.5f),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                GlassCard {
                    GeoRouteMap(
                        routePoints = routePoints,
                        photoGroups = photoGroups,
                    )
                }
            }
        }

        if (latestSample != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = stringResource(R.string.geo_current_position),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.5f),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    GlassCard {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            latestSample.placeName?.let { name ->
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = AppleBlue,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            KeyValueRow(stringResource(R.string.geo_coordinates), latestSample.coordinateLabel())
                            KeyValueRow(stringResource(R.string.geo_accuracy), latestSample.accuracyLabel())
                            KeyValueRow(stringResource(R.string.geo_altitude), latestSample.altitudeLabel())
                            KeyValueRow(stringResource(R.string.geo_speed), latestSample.speedLabel())
                            KeyValueRow(stringResource(R.string.geo_timestamp), latestSample.timeLabel())
                        }
                    }
                }
            }
        }

        if (geoTagging.samples.size > 1) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 4.dp),
                        ) {
                            Icon(Icons.Rounded.History, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                            Text(
                                text = stringResource(R.string.geo_location_history),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.5f),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        TextButton(onClick = { historyExpanded = !historyExpanded }) {
                            Icon(
                                imageVector = if (historyExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                tint = AppleBlue,
                            )
                            Spacer(Modifier.size(4.dp))
                            Text(
                                text = stringResource(
                                    if (historyExpanded) R.string.geo_history_collapse
                                    else R.string.geo_history_expand,
                                ),
                                color = AppleBlue,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    if (historyExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            geoTagging.samples.take(10).forEach { sample ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Graphite)
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column {
                                        Text(sample.displayName(), style = MaterialTheme.typography.bodyLarge, color = Color.White, fontWeight = FontWeight.Medium)
                                        Text(sample.timeLabel(), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.4f))
                                    }
                                    Icon(Icons.Rounded.LocationOn, null, tint = Iron, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class GeoPendingAction {
    None,
    CapturePin,
    SyncNow,
    StartSession,
}

data class PhotoPinGroup(
    val latitude: Double,
    val longitude: Double,
    val count: Int,
    val fileNames: List<String>,
)

@Composable
@OptIn(MapsComposeExperimentalApi::class)
private fun GeoRouteMap(
    routePoints: List<LatLng>,
    photoGroups: List<PhotoPinGroup>,
) {
    val hasMapsKey = BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank()
    if (!hasMapsKey) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 220.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Rounded.Map,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.36f),
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.geo_map_unavailable_no_key),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    if (routePoints.isEmpty() && photoGroups.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 220.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Rounded.Map,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.36f),
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.geo_map_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val cameraPositionState = rememberCameraPositionState()
    val context = LocalContext.current
    val mapUiSettings = remember {
        MapUiSettings(
            compassEnabled = false,
            mapToolbarEnabled = false,
            zoomControlsEnabled = false,
            myLocationButtonEnabled = false,
            rotationGesturesEnabled = false,
            tiltGesturesEnabled = false,
        )
    }
    val mapProperties = remember { MapProperties() }
    val mapLoadedState = remember { mutableStateOf(false) }
    var mapsInitialized by remember { mutableStateOf(false) }
    var googleMap by remember { mutableStateOf<GoogleMapSdk?>(null) }
    val mapLoaded = mapLoadedState.value
    val mapInstanceReady = mapsInitialized && googleMap != null && mapLoaded

    LaunchedEffect(context) {
        MapsInitializer.initialize(context.applicationContext)
        mapsInitialized = true
    }

    DisposableEffect(Unit) {
        onDispose {
            mapLoadedState.value = false
            googleMap = null
        }
    }

    if (!mapsInitialized) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 220.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Rounded.Map,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.36f),
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.geo_map_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    LaunchedEffect(mapInstanceReady, routePoints, photoGroups) {
        if (!mapInstanceReady) return@LaunchedEffect
        val allPoints = buildList {
            addAll(routePoints)
            addAll(photoGroups.map { LatLng(it.latitude, it.longitude) })
        }
        if (allPoints.isEmpty()) return@LaunchedEffect
        if (allPoints.size == 1) {
            cameraPositionState.move(CameraUpdateFactory.newLatLngZoom(allPoints.first(), 14f))
            return@LaunchedEffect
        }
        val bounds = LatLngBounds.Builder().apply {
            allPoints.forEach(::include)
        }.build()
        cameraPositionState.move(CameraUpdateFactory.newLatLngBounds(bounds, 96))
    }

    GoogleMap(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(RoundedCornerShape(18.dp)),
        cameraPositionState = cameraPositionState,
        properties = mapProperties,
        uiSettings = mapUiSettings,
    ) {
        MapEffect(Unit) { map ->
            if (googleMap !== map) {
                googleMap = map
                mapLoadedState.value = false
                map.setOnMapLoadedCallback {
                    if (googleMap === map) {
                        mapLoadedState.value = true
                    }
                }
            }
        }
        if (mapInstanceReady) {
            if (routePoints.size >= 2) {
                Polyline(
                    points = routePoints,
                    color = AppleBlue,
                    width = 8f,
                )
            }
            photoGroups.forEach { group ->
                MarkerComposable(
                    state = MarkerState(position = LatLng(group.latitude, group.longitude)),
                    title = if (group.count > 1) {
                        "${group.count} photos"
                    } else {
                        group.fileNames.firstOrNull() ?: "Photo"
                    },
                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 1f),
                ) {
                    PhotoPinMarker(manyPhotos = group.count > 1)
                }
            }
        }
    }
}

@Composable
private fun PhotoPinMarker(
    manyPhotos: Boolean,
) {
    val fill = if (manyPhotos) Color.White else Color.Black
    val stroke = if (manyPhotos) Color.Black else Color.White
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(fill)
                .border(2.dp, stroke, CircleShape),
        )
        Box(
            modifier = Modifier
                .width(6.dp)
                .height(10.dp)
                .clip(RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp))
                .background(fill)
                .border(1.dp, stroke, RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp)),
        )
    }
}

@Composable
private fun GeoActionButton(
    label: String,
    icon: ImageVector,
    containerColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(
                text = label,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
