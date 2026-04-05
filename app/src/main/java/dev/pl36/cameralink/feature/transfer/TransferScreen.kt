package dev.pl36.cameralink.feature.transfer

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.pl36.cameralink.R
import dev.pl36.cameralink.core.protocol.CameraImage
import dev.pl36.cameralink.core.ui.GlassCard
import dev.pl36.cameralink.core.ui.SectionHeader
import dev.pl36.cameralink.ui.theme.AppleBlue
import dev.pl36.cameralink.ui.theme.AppleGreen
import dev.pl36.cameralink.ui.theme.Graphite
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale
import kotlin.math.roundToInt

enum class ImageTypeFilter(val label: String) {
    ALL("All"),
    JPG("JPG"),
    RAW("RAW"),
    VIDEO("Video"),
}

enum class TransferSourceKind {
    WifiCamera,
    OmCaptureUsb,
}

data class TransferUiState(
    val images: List<CameraImage> = emptyList(),
    val thumbnails: Map<String, Bitmap> = emptyMap(),
    val isLoading: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: String = "",
    val errorMessage: String? = null,
    val selectedImage: CameraImage? = null,
    val downloadedCount: Int = 0,
    val selectedImages: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val batchDownloadTotal: Int = 0,
    val batchDownloadCurrent: Int = 0,
    val typeFilter: ImageTypeFilter = ImageTypeFilter.ALL,
    val downloadedFileNames: Set<String> = emptySet(),
    val matchedGeoTags: Map<String, ImageGeoTagInfo> = emptyMap(),
    val previewUnavailable: Set<String> = emptySet(),
    val sourceCameraSsid: String? = null,
    val sourceKind: TransferSourceKind = TransferSourceKind.WifiCamera,
    val sourceLabel: String = "Wi-Fi",
    val supportsDelete: Boolean = true,
    val usbObjectHandlesByPath: Map<String, Int> = emptyMap(),
    val usbAvailableStorageIds: List<Int> = emptyList(),
    val selectedUsbStorageIds: Set<Int>? = null,
)

data class ImageGeoTagInfo(
    val latitude: Double,
    val longitude: Double,
    val matchedSampleAtMillis: Long,
    val photoCapturedAtMillis: Long?,
    val sourceLabel: String,
    val placeName: String? = null,
    val altitudeMeters: Double? = null,
)

/** A date section: header label + images for that date */
data class DateSection(
    val dateKey: String,
    val label: String,
    val images: List<CameraImage>,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransferScreen(
    transferState: TransferUiState,
    onLoadImages: () -> Unit,
    onDownloadImage: (CameraImage) -> Unit,
    onDeleteImage: (CameraImage) -> Unit = {},
    onSelectImage: (CameraImage?) -> Unit,
    onToggleSelection: (CameraImage) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onDownloadSelected: () -> Unit = {},
    onDeleteSelected: () -> Unit = {},
    onSetTypeFilter: (ImageTypeFilter) -> Unit = {},
    onToggleDateSelection: (String) -> Unit = {},
    onSelectUsbSource: (Set<Int>?) -> Unit = {},
) {
    var showUsbSourceDialog by remember(transferState.sourceKind, transferState.usbAvailableStorageIds) {
        mutableStateOf(false)
    }
    val primaryActionLabel = if (transferState.sourceKind == TransferSourceKind.OmCaptureUsb) {
        "Import"
    } else {
        "Save"
    }

    // Apply type filter (needed for both detail view pager and grid)
    val filteredImages = remember(transferState.images, transferState.typeFilter) {
        when (transferState.typeFilter) {
            ImageTypeFilter.ALL -> transferState.images
            ImageTypeFilter.JPG -> transferState.images.filter { it.isJpeg }
            ImageTypeFilter.RAW -> transferState.images.filter { it.isRaw }
            ImageTypeFilter.VIDEO -> transferState.images.filter { it.isMovie }
        }
    }

    // Sort for detail pager (newest first across all dates)
    val sortedFilteredImages = remember(filteredImages) {
        filteredImages.sortedByDescending { it.captureDateKey + it.fileName }
    }

    // Detail view (single image with pager for swipe)
    if (transferState.selectedImage != null && !transferState.isSelectionMode) {
        val selectedIndex = remember(sortedFilteredImages, transferState.selectedImage) {
            sortedFilteredImages.indexOfFirst { it.fullPath == transferState.selectedImage.fullPath }
                .coerceAtLeast(0)
        }
        ImageDetailPager(
            images = sortedFilteredImages,
            initialIndex = selectedIndex,
            thumbnails = transferState.thumbnails,
            matchedGeoTags = transferState.matchedGeoTags,
            previewUnavailable = transferState.previewUnavailable,
            isDownloading = transferState.isDownloading,
            downloadProgress = transferState.downloadProgress,
            downloadedFileNames = transferState.downloadedFileNames,
            supportsDelete = transferState.supportsDelete,
            primaryActionLabel = primaryActionLabel,
            onDownload = onDownloadImage,
            onDelete = onDeleteImage,
            onSelectImage = onSelectImage,
            onClose = { onSelectImage(null) },
        )
        return
    }

    // Group by date (newest first)
    val dateSections = remember(filteredImages) {
        filteredImages
            .groupBy { it.captureDateKey }
            .entries
            .sortedByDescending { it.key }
            .map { (dateKey, imgs) ->
                DateSection(
                    dateKey = dateKey,
                    label = imgs.first().captureDateLabel,
                    images = imgs.sortedByDescending { it.fileName },
                )
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Header ─────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (transferState.isSelectionMode) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = onClearSelection) {
                        Icon(Icons.Rounded.Close, "Cancel", tint = Color.White)
                    }
                    Text(
                        text = "${transferState.selectedImages.size} selected",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
                IconButton(onClick = onSelectAll) {
                    Icon(Icons.Rounded.SelectAll, "Select All", tint = Color.White)
                }
            } else {
                SectionHeader(stringResource(R.string.dest_library))
                Spacer(modifier = Modifier.width(1.dp))
            }
        }
        if (!transferState.isSelectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransferSummaryCard(
                    label = "Source",
                    value = transferState.sourceLabel,
                    clickable = transferState.sourceKind == TransferSourceKind.OmCaptureUsb &&
                        transferState.usbAvailableStorageIds.isNotEmpty(),
                    onClick = { showUsbSourceDialog = true },
                )
                if (transferState.downloadedCount > 0) {
                    TransferSummaryCard(
                        label = "Saved",
                        value = transferState.downloadedCount.toString(),
                    )
                }
                IconButton(onClick = onLoadImages) {
                    Icon(Icons.Rounded.Refresh, "Reload", tint = Color.White.copy(alpha = 0.7f))
                }
            }
        }

        // ── Filter chips ─────────────────────
        if (transferState.images.isNotEmpty()) {
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
            ) {
                items(ImageTypeFilter.entries.size) { index ->
                    val filter = ImageTypeFilter.entries[index]
                    val count = when (filter) {
                        ImageTypeFilter.ALL -> transferState.images.size
                        ImageTypeFilter.JPG -> transferState.images.count { it.isJpeg }
                        ImageTypeFilter.RAW -> transferState.images.count { it.isRaw }
                        ImageTypeFilter.VIDEO -> transferState.images.count { it.isMovie }
                    }
                    FilterChip(
                        selected = transferState.typeFilter == filter,
                        onClick = { onSetTypeFilter(filter) },
                        label = {
                            Text(
                                "${filter.label} ($count)",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                softWrap = false,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppleBlue,
                            selectedLabelColor = Color.White,
                            containerColor = Graphite,
                            labelColor = Color.White.copy(alpha = 0.7f),
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = Color.Transparent,
                            selectedBorderColor = Color.Transparent,
                            enabled = true,
                            selected = transferState.typeFilter == filter,
                        ),
                    )
                }
            }
        }

        // ── Content ──────────────────────────
        when {
            transferState.isLoading && transferState.images.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator(color = AppleBlue)
                        Text(
                            stringResource(R.string.transfer_loading),
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                }
            }
            transferState.errorMessage != null && transferState.images.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Icon(Icons.Rounded.BrokenImage, null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(48.dp))
                        Text(transferState.errorMessage, color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center)
                        Button(onClick = onLoadImages, colors = ButtonDefaults.buttonColors(containerColor = AppleBlue)) {
                            Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
                            Text("  ${stringResource(R.string.transfer_retry)}", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            transferState.images.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Icon(Icons.Rounded.PhotoLibrary, null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(64.dp))
                        Text(
                            stringResource(R.string.transfer_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                        Text(
                            stringResource(R.string.transfer_empty_message),
                            color = Color.White.copy(alpha = 0.4f),
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = onLoadImages, colors = ButtonDefaults.buttonColors(containerColor = AppleBlue)) {
                            Icon(Icons.Rounded.CloudDownload, null, modifier = Modifier.size(18.dp))
                            Text(
                                "  ${if (transferState.sourceKind == TransferSourceKind.OmCaptureUsb) "Import from Camera" else stringResource(R.string.transfer_load_button)}",
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
            else -> {
                val hasInlineStatus = transferState.isLoading || transferState.errorMessage != null
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp,
                            top = if (hasInlineStatus) 58.dp else 4.dp,
                            bottom = if (transferState.isSelectionMode && transferState.selectedImages.isNotEmpty()) 80.dp else 8.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        dateSections.forEach { section ->
                            // ── Date section header (full width) ──
                            item(
                                key = "header_${section.dateKey}",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                val allInSection = section.images.map { it.fullPath }.toSet()
                                val allSelected = allInSection.all { it in transferState.selectedImages }

                                DateSectionHeader(
                                    label = section.label,
                                    imageCount = section.images.size,
                                    isSelectionMode = transferState.isSelectionMode,
                                    allSelected = allSelected,
                                    onToggleDateSelect = { onToggleDateSelection(section.dateKey) },
                                )
                            }

                            // ── Thumbnails ──
                            items(section.images, key = { it.fullPath }) { image ->
                                val thumbnail = transferState.thumbnails[image.fileName]
                                val isSelected = image.fullPath in transferState.selectedImages
                                val isDownloaded = image.fileName in transferState.downloadedFileNames
                                ImageThumbnailCell(
                                    image = image,
                                    thumbnail = thumbnail,
                                    isSelected = isSelected,
                                    isSelectionMode = transferState.isSelectionMode,
                                    isDownloaded = isDownloaded,
                                    onClick = {
                                        if (transferState.isSelectionMode) {
                                            onToggleSelection(image)
                                        } else {
                                            onSelectImage(image)
                                        }
                                    },
                                    onLongClick = {
                                        if (!transferState.isSelectionMode) {
                                            onToggleSelection(image)
                                        }
                                    },
                                )
                            }
                        }
                    }

                    // ── Batch download bar ──
                    if (hasInlineStatus) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (transferState.errorMessage != null) {
                                GlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                Icons.Rounded.BrokenImage,
                                                contentDescription = null,
                                                tint = Color.White.copy(alpha = 0.55f),
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Text(
                                                transferState.errorMessage,
                                                color = Color.White.copy(alpha = 0.78f),
                                                style = MaterialTheme.typography.labelMedium,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        TextButton(onClick = onLoadImages) {
                                            Text("Retry", color = AppleBlue, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                            if (transferState.isLoading) {
                                GlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CircularProgressIndicator(
                                            color = AppleBlue,
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Text(
                                            "Refreshing photos...",
                                            color = Color.White.copy(alpha = 0.72f),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (transferState.isSelectionMode && transferState.selectedImages.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .background(Graphite.copy(alpha = 0.95f)),
                        ) {
                            if (transferState.isDownloading && transferState.batchDownloadTotal > 0) {
                                LinearProgressIndicator(
                                    progress = { transferState.batchDownloadCurrent.toFloat() / transferState.batchDownloadTotal },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = AppleBlue,
                                    trackColor = Color.White.copy(alpha = 0.1f),
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (transferState.supportsDelete) {
                                    Button(
                                        onClick = onDeleteSelected,
                                        enabled = !transferState.isDownloading,
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD14B5A)),
                                        shape = RoundedCornerShape(16.dp),
                                    ) {
                                        Icon(Icons.Rounded.Delete, null, modifier = Modifier.size(20.dp))
                                        Text("  Delete", fontWeight = FontWeight.Bold)
                                    }
                                }
                                Button(
                                    onClick = onDownloadSelected,
                                    enabled = !transferState.isDownloading,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                                    shape = RoundedCornerShape(16.dp),
                                ) {
                                    if (transferState.isDownloading) {
                                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                        Text("  ${transferState.batchDownloadCurrent}/${transferState.batchDownloadTotal}", fontWeight = FontWeight.Bold)
                                    } else {
                                        Icon(Icons.Rounded.Download, null, modifier = Modifier.size(20.dp))
                                        Text("  $primaryActionLabel ${transferState.selectedImages.size}", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showUsbSourceDialog && transferState.sourceKind == TransferSourceKind.OmCaptureUsb) {
        val storageIds = transferState.usbAvailableStorageIds.distinct()
        AlertDialog(
            onDismissRequest = { showUsbSourceDialog = false },
            confirmButton = {},
            title = { Text("Source", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UsbSourceOption(
                        label = if (storageIds.size > 1) "Both" else "Slot 1",
                        selected = transferState.selectedUsbStorageIds.isNullOrEmpty() ||
                            transferState.selectedUsbStorageIds!!.size >= storageIds.size,
                    ) {
                        onSelectUsbSource(null)
                        showUsbSourceDialog = false
                    }
                    storageIds.forEachIndexed { index, storageId ->
                        UsbSourceOption(
                            label = "Slot ${index + 1}",
                            selected = transferState.selectedUsbStorageIds == setOf(storageId),
                        ) {
                            onSelectUsbSource(setOf(storageId))
                            showUsbSourceDialog = false
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showUsbSourceDialog = false }) {
                    Text("Close")
                }
            },
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Date section header
// ──────────────────────────────────────────────────────────────────────────
@Composable
private fun TransferSummaryCard(
    label: String,
    value: String,
    clickable: Boolean = false,
    onClick: () -> Unit = {},
) {
    GlassCard(
        modifier = Modifier.then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.56f),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = value,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun UsbSourceOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (selected) "Selected" else "",
                color = AppleBlue,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun DateSectionHeader(
    label: String,
    imageCount: Int,
    isSelectionMode: Boolean,
    allSelected: Boolean,
    onToggleDateSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Rounded.DateRange,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${imageCount} items",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.4f),
            )
        }
        TextButton(
            onClick = onToggleDateSelect,
        ) {
            if (isSelectionMode && allSelected) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = AppleBlue,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("Deselect", color = AppleBlue, style = MaterialTheme.typography.labelMedium)
            } else {
                Icon(
                    Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("Select All", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Image thumbnail cell
// ──────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageThumbnailCell(
    image: CameraImage,
    thumbnail: Bitmap?,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    isDownloaded: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Graphite)
            .then(
                if (isSelected) Modifier.border(3.dp, AppleBlue, RoundedCornerShape(8.dp))
                else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = image.fileName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = if (isSelected) 0.7f else 1f,
            )
        } else {
            Icon(
                imageVector = if (image.isMovie) Icons.Rounded.Movie else Icons.Rounded.Image,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.2f),
                modifier = Modifier.size(32.dp),
            )
        }

        // Selection indicator
        if (isSelectionMode) {
            Icon(
                imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isSelected) AppleBlue else Color.White.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(24.dp)
                    .then(
                        if (!isSelected) Modifier.background(Color.Black.copy(alpha = 0.3f), CircleShape)
                        else Modifier
                    ),
            )
        }

        // Downloaded indicator
        if (isDownloaded && !isSelectionMode) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = "Downloaded",
                tint = AppleGreen,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .size(18.dp),
            )
        }

        // File type badge for RAW/MOV
        if (image.isRaw || image.isMovie) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (image.isRaw) AppleGreen.copy(alpha = 0.8f) else AppleBlue.copy(alpha = 0.8f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (image.isMovie) {
                    Icon(Icons.Rounded.Movie, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                }
                Text(
                    text = image.fileName.substringAfterLast('.').uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Image detail view with horizontal pager (swipe between photos)
// ──────────────────────────────────────────────────────────────────────────
@Composable
private fun ImageDetailPager(
    images: List<CameraImage>,
    initialIndex: Int,
    thumbnails: Map<String, Bitmap>,
    matchedGeoTags: Map<String, ImageGeoTagInfo>,
    previewUnavailable: Set<String>,
    isDownloading: Boolean,
    downloadProgress: String,
    downloadedFileNames: Set<String>,
    supportsDelete: Boolean,
    primaryActionLabel: String,
    onDownload: (CameraImage) -> Unit,
    onDelete: (CameraImage) -> Unit,
    onSelectImage: (CameraImage?) -> Unit,
    onClose: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialIndex) { images.size }
    val zoomByPage = remember { mutableStateMapOf<Int, Float>() }
    val context = LocalContext.current
    val currentPage by remember(pagerState, images.size) {
        derivedStateOf {
            if (images.isEmpty()) 0 else pagerState.currentPage.coerceIn(0, images.lastIndex)
        }
    }
    val currentImage by remember(images, pagerState) {
        derivedStateOf { images.getOrNull(currentPage) }
    }
    val currentGeoTag by remember(currentImage, matchedGeoTags) {
        derivedStateOf { currentImage?.let { matchedGeoTags[it.fileName] } }
    }
    val currentPreviewUnavailable by remember(currentImage, previewUnavailable) {
        derivedStateOf { currentImage?.fileName in previewUnavailable }
    }
    val currentZoom by remember(currentPage, zoomByPage) {
        derivedStateOf { zoomByPage[currentPage] ?: 1f }
    }
    BackHandler(onBack = onClose)

    LaunchedEffect(initialIndex, images.size) {
        if (images.isEmpty()) return@LaunchedEffect
        val targetPage = initialIndex.coerceIn(0, images.lastIndex)
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
    }

    // Update selected image when page changes
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { page ->
            if (page in images.indices) {
                onSelectImage(images[page])
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Image pager ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = currentZoom <= 1.05f,
            ) { page ->
                val image = images[page]
                val thumbnail = thumbnails[image.fileName]
                var scale by remember(page) { mutableFloatStateOf(1f) }
                var offset by remember(page) { mutableStateOf(Offset.Zero) }

                LaunchedEffect(page) {
                    snapshotFlow {
                        if (scale <= 1.02f) 1f else (scale * 100f).roundToInt() / 100f
                    }
                        .distinctUntilChanged()
                        .collect { normalizedScale ->
                            zoomByPage[page] = normalizedScale
                        }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(page) {
                            awaitEachGesture {
                                var transformActive = scale > 1.02f
                                var keepTracking = true
                                while (keepTracking) {
                                    val event = awaitPointerEvent()
                                    val pressedPointers = event.changes.count { it.pressed }
                                    if (pressedPointers > 1 || transformActive) {
                                        val nextScale = (scale * event.calculateZoom()).coerceIn(1f, 4f)
                                        val nextOffset = if (nextScale <= 1.02f) {
                                            Offset.Zero
                                        } else {
                                            offset + event.calculatePan()
                                        }
                                        val shouldConsume = pressedPointers > 1 ||
                                            nextScale > 1.02f ||
                                            nextOffset != offset
                                        scale = nextScale
                                        offset = nextOffset
                                        transformActive = nextScale > 1.02f
                                        if (shouldConsume) {
                                            event.changes.forEach { change ->
                                                if (change.positionChanged()) {
                                                    change.consume()
                                                }
                                            }
                                        }
                                    }
                                    keepTracking = event.changes.any { it.pressed }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (thumbnail != null) {
                        Image(
                            bitmap = thumbnail.asImageBitmap(),
                            contentDescription = image.fileName,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                },
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Icon(Icons.Rounded.Image, null, tint = Color.White.copy(alpha = 0.15f), modifier = Modifier.size(64.dp))
                    }
                }
            }

            // Close button — top left
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            ) {
                Icon(Icons.Rounded.Close, "Close", tint = Color.White)
            }

            // File type badge — top right
            val visibleImage = currentImage
            if (visibleImage != null) {
                val ext = visibleImage.fileName.substringAfterLast('.').uppercase()
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            when {
                                visibleImage.isRaw -> AppleGreen.copy(alpha = 0.85f)
                                visibleImage.isMovie -> AppleBlue.copy(alpha = 0.85f)
                                else -> Color.Black.copy(alpha = 0.6f)
                            }
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (visibleImage.isMovie) {
                        Icon(Icons.Rounded.Movie, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                    }
                    Text(ext, style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            // Page indicator
            if (images.size > 1) {
                Text(
                    text = "${currentPage + 1} / ${images.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

        }

        // ── Bottom info bar ──
        val selectedImage = currentImage
        if (selectedImage != null) {
            DetailBottomBar(
                currentImage = selectedImage,
                currentGeoTag = currentGeoTag,
                currentPreviewUnavailable = currentPreviewUnavailable,
                isDownloading = isDownloading,
                downloadProgress = downloadProgress,
                isAlreadyDownloaded = selectedImage.fileName in downloadedFileNames,
                supportsDelete = supportsDelete,
                primaryActionLabel = primaryActionLabel,
                onOpenMap = { geoTag -> openMapForGeoTag(context, geoTag) },
                onDelete = onDelete,
                onDownload = onDownload,
            )
        }
    }
}

@Composable
private fun DetailBottomBar(
    currentImage: CameraImage,
    currentGeoTag: ImageGeoTagInfo?,
    currentPreviewUnavailable: Boolean,
    isDownloading: Boolean,
    downloadProgress: String,
    isAlreadyDownloaded: Boolean,
    supportsDelete: Boolean,
    primaryActionLabel: String,
    onOpenMap: (ImageGeoTagInfo) -> Unit,
    onDelete: (CameraImage) -> Unit,
    onDownload: (CameraImage) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0A0A))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = currentImage.fileName,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = formatFileSize(currentImage.fileSize),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f),
            )
        }

        GeoTagInfoBlock(
            geoTag = currentGeoTag,
            onOpenMap = onOpenMap,
        )

        AnimatedVisibility(
            visible = currentPreviewUnavailable,
            enter = fadeIn(animationSpec = tween(durationMillis = 90)),
            exit = fadeOut(animationSpec = tween(durationMillis = 70)),
        ) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (currentImage.isRaw) {
                        "Preview unavailable for this RAW file. This ORF variant does not expose a decodable preview in the current app stack."
                    } else {
                        "Preview unavailable for this photo."
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.76f),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (supportsDelete) {
                Button(
                    onClick = { onDelete(currentImage) },
                    enabled = !isDownloading,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD14B5A)),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Rounded.Delete, null, modifier = Modifier.size(18.dp))
                    Text("  Delete", fontWeight = FontWeight.Bold)
                }
            }
            Button(
                onClick = { onDownload(currentImage) },
                enabled = !isDownloading,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isAlreadyDownloaded) AppleGreen else AppleBlue,
                ),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (isDownloading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  $downloadProgress", fontWeight = FontWeight.Bold)
                } else if (isAlreadyDownloaded) {
                    Icon(Icons.Rounded.CheckCircle, null, modifier = Modifier.size(18.dp))
                    Text("  Saved", fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Rounded.Download, null, modifier = Modifier.size(18.dp))
                    Text("  $primaryActionLabel", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun GeoTagInfoBlock(
    geoTag: ImageGeoTagInfo?,
    onOpenMap: (ImageGeoTagInfo) -> Unit,
) {
    AnimatedVisibility(
        visible = geoTag != null,
        enter = fadeIn(animationSpec = tween(durationMillis = 90)),
        exit = fadeOut(animationSpec = tween(durationMillis = 70)),
    ) {
        if (geoTag == null) return@AnimatedVisibility
        Text(
            text = "${"%.5f".format(geoTag.latitude)}, ${"%.5f".format(geoTag.longitude)}",
            modifier = Modifier.clickable { onOpenMap(geoTag) },
            style = MaterialTheme.typography.labelSmall,
            color = AppleBlue.copy(alpha = 0.92f),
        )
    }
}

private fun openMapForGeoTag(
    context: android.content.Context,
    geoTag: ImageGeoTagInfo,
) {
    val mapsUri = "https://www.google.com/maps/search/?api=1&query=${geoTag.latitude},${geoTag.longitude}".toUri()
    val packageManager = context.packageManager
    val googleMapsIntent = Intent(Intent.ACTION_VIEW, mapsUri)
        .setPackage("com.google.android.apps.maps")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallbackIntent = Intent(Intent.ACTION_VIEW, mapsUri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val intent = if (googleMapsIntent.resolveActivity(packageManager) != null) {
        googleMapsIntent
    } else {
        fallbackIntent
    }
    runCatching {
        context.startActivity(intent)
    }.onFailure {
        if (it !is ActivityNotFoundException) {
            throw it
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "Unknown"
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
