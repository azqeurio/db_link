package dev.dblink.ui

import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.viewModelScope
import dev.dblink.core.geotag.GeoTagMatcher
import dev.dblink.core.geotag.GeoTagTrackingState
import dev.dblink.core.logging.D
import dev.dblink.core.model.GeoTagLocationSample
import dev.dblink.core.model.GeoTaggingSnapshot
import dev.dblink.feature.transfer.ImageGeoTagInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import kotlin.math.abs

/**
 * Geotagging logic for [MainViewModel], extracted into its own file so the
 * EXIF-writing, sample tracking, and match-refresh helpers live together.
 *
 * These are `internal` extension functions on [MainViewModel]: behavior is
 * identical to the original member functions, and the public geotag entry
 * points (`syncGeoTagNow`, `startGeoTagSession`, …) remain on the ViewModel and
 * delegate here. Keeping them in the same `dev.dblink.ui` package lets the
 * ViewModel call them without extra imports.
 */

/**
 * Writes GPS EXIF tags to a saved image if a matched geotag exists.
 * Respects the geotag_include_altitude setting for altitude data.
 */
internal fun MainViewModel.writeGeoTagExif(
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
internal suspend fun MainViewModel.postProcessBatchGeoTags() {
    val matchedGeoTags = _uiState.value.transferState.matchedGeoTags
    if (matchedGeoTags.isEmpty()) return

    val context = appContext()
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

internal fun MainViewModel.pushGeoTagSample(
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

internal fun MainViewModel.syncGeoTagTrackingState(state: GeoTagTrackingState) {
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

internal fun MainViewModel.syncGeoTagStatusNotification(snapshot: GeoTaggingSnapshot = _uiState.value.geoTagging) {
    if (snapshot.sessionActive) {
        geoTagStatusNotifier.cancel()
        return
    }
    geoTagStatusNotifier.update(snapshot)
}

internal fun MainViewModel.persistGeoTagging(snapshot: GeoTaggingSnapshot) {
    viewModelScope.launch {
        preferencesRepository.saveGeoTagging(snapshot)
    }
}

internal fun MainViewModel.extractPreviewGeoTag(imageBytes: ByteArray): ImageGeoTagInfo? {
    return runCatching {
        val exif = ByteArrayInputStream(imageBytes).use { inputStream ->
            ExifInterface(inputStream)
        }
        val photoCapturedAtMillis = ImagePreviewDecoder.readExifCapturedAtMillis(exif)
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

internal fun MainViewModel.refreshMatchedGeoTags() {
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

internal fun MainViewModel.isAutoGeoMatchEnabled(): Boolean {
    return _uiState.value.settings.firstOrNull { it.id == "time_match_geotags" }?.enabled == true
}
