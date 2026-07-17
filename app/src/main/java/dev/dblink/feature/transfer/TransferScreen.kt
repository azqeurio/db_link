package dev.dblink.feature.transfer

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Wifi
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.dblink.R
import dev.dblink.core.protocol.CameraImage
import dev.dblink.core.ui.GlassCard
import dev.dblink.core.ui.SectionHeader
import dev.dblink.core.usb.OmCaptureUsbSavedMedia
import dev.dblink.ui.theme.AppleBlue
import dev.dblink.ui.theme.AppleGreen
import dev.dblink.ui.theme.Graphite
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale
import kotlin.math.roundToInt

enum class ImageTypeFilter(val label: String) {
    ALL("All"),
    JPG("JPG"),
    RAW("RAW"),
    VIDEO("Video"),
    FIVE_STAR("5-star"),
    SELECTED("Share Order"),
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
    val backgroundTransferRunning: Boolean = false,
    val backgroundTransferProgress: String = "",
    val backgroundTransferTotal: Int = 0,
    val backgroundTransferCurrent: Int = 0,
    val errorMessage: String? = null,
    val selectedImage: CameraImage? = null,
    val downloadedCount: Int = 0,
    val selectedImages: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val batchDownloadTotal: Int = 0,
    val batchDownloadCurrent: Int = 0,
    /** Per-file download fraction (0f..1f) for files currently transferring over Wi-Fi/MTP. */
    val perImageDownloadProgress: Map<String, Float> = emptyMap(),
    /** File names queued in the active batch but not yet downloading (shown dimmed). */
    val pendingDownloadFileNames: Set<String> = emptySet(),
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
    val selectedSavedMedia: OmCaptureUsbSavedMedia? = null,
    val selectedSavedMediaBitmap: Bitmap? = null,
    val selectedSavedMediaLoading: Boolean = false,
    val selectedSavedMediaItems: List<OmCaptureUsbSavedMedia> = emptyList(),
    val selectedSavedMediaIndex: Int = 0,
    val selectedSavedMediaBitmaps: Map<String, Bitmap> = emptyMap(),
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

private const val BACKGROUND_STATUS_SCROLL_ITEM_WEIGHT = 1_000_000
private const val BACKGROUND_STATUS_SCROLL_DELTA_THRESHOLD = 16
private const val BACKGROUND_STATUS_EXPAND_AT_TOP_THRESHOLD = 24

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransferScreen(
    transferState: TransferUiState,
    autoLoadImages: Boolean = true,
    onLoadImages: () -> Unit,
    onStartHighSpeedWifiTransfer: () -> Unit = {},
    onDownloadImage: (CameraImage) -> Unit,
    onCancelDownload: () -> Unit = {},
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
    onCloseSavedMediaPreview: () -> Unit = {},
    onSelectSavedMediaPreviewPage: (OmCaptureUsbSavedMedia, Int) -> Unit = { _, _ -> },
    selectedCardSlotSource: Int? = null,
    wifiSourceSelectionAvailable: Boolean = false,
    onSelectWifiSource: (Int) -> Unit = {},
) {
    val showSourceDialogState = remember(transferState.sourceKind, transferState.usbAvailableStorageIds) {
        mutableStateOf(false)
    }
    val showSourceDialog = showSourceDialogState.value

    // Auto-load only after the host confirms the normal camera-AP / USB library
    // path is ready. Same-Wi-Fi high-speed transfer stays manual.
    LaunchedEffect(autoLoadImages) {
        if (autoLoadImages && transferState.images.isEmpty() && !transferState.isLoading) {
            onLoadImages()
        }
    }
    val primaryActionLabel = if (transferState.sourceKind == TransferSourceKind.OmCaptureUsb) {
        "Import"
    } else {
        "Save"
    }
    val isHighSpeedWifiSource =
        transferState.sourceKind == TransferSourceKind.WifiCamera &&
            transferState.sourceLabel.startsWith("고속전달")
    val sourceValue = if (
        transferState.sourceKind == TransferSourceKind.WifiCamera &&
        selectedCardSlotSource in 1..2 &&
        !isHighSpeedWifiSource
    ) {
        "Slot $selectedCardSlotSource"
    } else {
        transferState.sourceLabel
    }
    val sourceClickable = when (transferState.sourceKind) {
        TransferSourceKind.OmCaptureUsb -> transferState.usbAvailableStorageIds.isNotEmpty()
        TransferSourceKind.WifiCamera -> wifiSourceSelectionAvailable && !isHighSpeedWifiSource
    }

    // Apply type filter (needed for both detail view pager and grid)
    val filteredImages = remember(transferState.images, transferState.typeFilter) {
        when (transferState.typeFilter) {
            ImageTypeFilter.ALL -> transferState.images
            ImageTypeFilter.JPG -> transferState.images.filter { it.isJpeg }
            ImageTypeFilter.RAW -> transferState.images.filter { it.isRaw }
            ImageTypeFilter.VIDEO -> transferState.images.filter { it.isMovie }
            ImageTypeFilter.FIVE_STAR -> transferState.images.filter { it.isFiveStar }
            ImageTypeFilter.SELECTED -> transferState.images.filter { it.isCameraSelected }
        }
    }

    // Sort for detail pager (newest first across all dates)
    val sortedFilteredImages = remember(filteredImages) {
        filteredImages.sortedByDescending { it.captureDateKey + it.fileName }
    }

    if (transferState.selectedSavedMedia != null && !transferState.isSelectionMode) {
        SavedMediaDetailView(
            savedMediaItems = transferState.selectedSavedMediaItems.ifEmpty {
                listOf(transferState.selectedSavedMedia)
            },
            selectedIndex = transferState.selectedSavedMediaIndex,
            selectedSavedMedia = transferState.selectedSavedMedia,
            bitmap = transferState.selectedSavedMediaBitmap,
            bitmapCache = transferState.selectedSavedMediaBitmaps,
            isLoading = transferState.selectedSavedMediaLoading,
            onSelectSavedMediaPage = onSelectSavedMediaPreviewPage,
            onClose = onCloseSavedMediaPreview,
        )
        return
    }

    // Detail view (single image with pager for swipe). Also available in selection
    // mode via the enlarge button, where it shows a selection toggle instead of the
    // download/delete bar so the user can review large and pick while swiping.
    if (transferState.selectedImage != null) {
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
            backgroundTransferRunning = transferState.backgroundTransferRunning,
            backgroundTransferProgress = transferState.backgroundTransferProgress,
            downloadedFileNames = transferState.downloadedFileNames,
            supportsDelete = transferState.supportsDelete,
            primaryActionLabel = primaryActionLabel,
            selectionMode = transferState.isSelectionMode,
            selectedFullPaths = transferState.selectedImages,
            onToggleSelection = onToggleSelection,
            onDownload = onDownloadImage,
            onCancelDownload = onCancelDownload,
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
    val gridState = rememberLazyGridState()
    val backgroundDownloadVisible = !transferState.isSelectionMode && transferState.backgroundTransferRunning
    var backgroundDownloadCollapsed by remember { mutableStateOf(false) }

    LaunchedEffect(backgroundDownloadVisible, gridState) {
        if (!backgroundDownloadVisible) {
            backgroundDownloadCollapsed = false
        } else {
            var previousScroll = gridState.firstVisibleItemIndex * BACKGROUND_STATUS_SCROLL_ITEM_WEIGHT +
                gridState.firstVisibleItemScrollOffset
            backgroundDownloadCollapsed = previousScroll > BACKGROUND_STATUS_EXPAND_AT_TOP_THRESHOLD
            snapshotFlow {
                gridState.firstVisibleItemIndex * BACKGROUND_STATUS_SCROLL_ITEM_WEIGHT +
                    gridState.firstVisibleItemScrollOffset
            }
                .distinctUntilChanged()
                .collect { currentScroll ->
                    val delta = currentScroll - previousScroll
                    when {
                        currentScroll <= BACKGROUND_STATUS_EXPAND_AT_TOP_THRESHOLD -> {
                            backgroundDownloadCollapsed = false
                        }
                        delta > BACKGROUND_STATUS_SCROLL_DELTA_THRESHOLD -> {
                            backgroundDownloadCollapsed = true
                        }
                        delta < -BACKGROUND_STATUS_SCROLL_DELTA_THRESHOLD -> {
                            backgroundDownloadCollapsed = false
                        }
                    }
                    previousScroll = currentScroll
                }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
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
                if (transferState.sourceKind == TransferSourceKind.WifiCamera) {
                    HighSpeedTransferChip(
                        enabled = !transferState.isLoading,
                        onClick = onStartHighSpeedWifiTransfer,
                    )
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }
            }
        }
        if (!transferState.isSelectionMode) {
            if (transferState.images.isNotEmpty()) {
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
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
                            ImageTypeFilter.FIVE_STAR -> transferState.images.count { it.isFiveStar }
                            ImageTypeFilter.SELECTED -> transferState.images.count { it.isCameraSelected }
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LibraryToolbarChip(
                    label = "Source",
                    value = sourceValue,
                    clickable = sourceClickable,
                    modifier = Modifier.weight(1f),
                    onClick = { showSourceDialogState.value = true },
                )
                LibraryRefreshChip(
                    isLoading = transferState.isLoading,
                    onClick = onLoadImages,
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
        // Content
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
                val backgroundDownloadTopInset by animateDpAsState(
                    targetValue = when {
                        !backgroundDownloadVisible -> 0.dp
                        backgroundDownloadCollapsed -> 14.dp
                        else -> 78.dp
                    },
                    animationSpec = tween(durationMillis = 220),
                    label = "backgroundDownloadTopInset",
                )
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = gridState,
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp,
                            top = (if (hasInlineStatus) 58.dp else 4.dp) + backgroundDownloadTopInset,
                            bottom = if (transferState.isSelectionMode && transferState.selectedImages.isNotEmpty()) 80.dp else 8.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        dateSections.forEach { section ->
                            // Date section header (full width)
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

                            // Thumbnails
                            items(section.images, key = { it.fullPath }) { image ->
                                val thumbnail = transferState.thumbnails[image.fileName]
                                val isSelected = image.fullPath in transferState.selectedImages
                                val isDownloaded = image.fileName in transferState.downloadedFileNames
                                val downloadProgress = transferState.perImageDownloadProgress[image.fileName]
                                val isPendingDownload = downloadProgress == null &&
                                    image.fileName in transferState.pendingDownloadFileNames
                                ImageThumbnailCell(
                                    image = image,
                                    thumbnail = thumbnail,
                                    isSelected = isSelected,
                                    isSelectionMode = transferState.isSelectionMode,
                                    isDownloaded = isDownloaded,
                                    downloadProgress = downloadProgress,
                                    isPendingDownload = isPendingDownload,
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
                                    onEnlarge = { onSelectImage(image) },
                                )
                            }
                        }
                    }

                    // Batch download bar
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

                    if (backgroundDownloadVisible) {
                        BackgroundTransferStatusOverlay(
                            progress = transferState.backgroundTransferProgress,
                            current = transferState.backgroundTransferCurrent,
                            total = transferState.backgroundTransferTotal,
                            collapsed = backgroundDownloadCollapsed,
                            onCancel = onCancelDownload,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = if (hasInlineStatus) 52.dp else 0.dp),
                        )
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
    if (showSourceDialog && transferState.sourceKind == TransferSourceKind.OmCaptureUsb) {
        val storageIds = transferState.usbAvailableStorageIds.distinct()
        AlertDialog(
            onDismissRequest = { showSourceDialogState.value = false },
            confirmButton = {},
            title = { Text("Source", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UsbSourceOption(
                        label = if (storageIds.size > 1) "Both" else "Slot 1",
                        selected = transferState.selectedUsbStorageIds.isNullOrEmpty() ||
                            transferState.selectedUsbStorageIds.size >= storageIds.size,
                    ) {
                        onSelectUsbSource(null)
                        showSourceDialogState.value = false
                    }
                    storageIds.forEachIndexed { index, storageId ->
                        UsbSourceOption(
                            label = "Slot ${index + 1}",
                            selected = transferState.selectedUsbStorageIds == setOf(storageId),
                        ) {
                            onSelectUsbSource(setOf(storageId))
                            showSourceDialogState.value = false
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showSourceDialogState.value = false }) {
                    Text("Close")
                }
            },
        )
    }
    if (showSourceDialog && transferState.sourceKind == TransferSourceKind.WifiCamera && wifiSourceSelectionAvailable) {
        AlertDialog(
            onDismissRequest = { showSourceDialogState.value = false },
            confirmButton = {},
            title = { Text("Source", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..2).forEach { slot ->
                        UsbSourceOption(
                            label = "Slot $slot",
                            selected = selectedCardSlotSource == slot,
                        ) {
                            onSelectWifiSource(slot)
                            showSourceDialogState.value = false
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showSourceDialogState.value = false }) {
                    Text("Close")
                }
            },
        )
    }
}

}

/**
 * Compact "고속전달" (high-speed same-Wi-Fi import) trigger shown on the Library
 * header row. Replaces the older full-width card — just a small Wi-Fi icon + label.
 */
@Composable
private fun HighSpeedTransferChip(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Graphite.copy(alpha = 0.78f))
            .border(1.dp, AppleBlue.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Wifi,
            contentDescription = null,
            tint = if (enabled) AppleBlue else AppleBlue.copy(alpha = 0.4f),
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = "고속전달",
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun BackgroundTransferStatusOverlay(
    progress: String,
    current: Int,
    total: Int,
    collapsed: Boolean,
    modifier: Modifier = Modifier,
    onCancel: () -> Unit,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = if (total > 0) (current.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "backgroundTransferProgress",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        AnimatedVisibility(
            visible = !collapsed,
            enter = slideInVertically(
                animationSpec = tween(durationMillis = 220),
                initialOffsetY = { -it },
            ) + fadeIn(animationSpec = tween(durationMillis = 180)),
            exit = slideOutVertically(
                animationSpec = tween(durationMillis = 180),
                targetOffsetY = { -it },
            ) + fadeOut(animationSpec = tween(durationMillis = 120)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Graphite.copy(alpha = 0.94f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        style = MaterialTheme.typography.labelSmall,
                        text = progress.ifBlank {
                            if (total > 0) "$current / $total" else "Downloading..."
                        },
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(
                        onClick = onCancel,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.common_cancel),
                            color = Color(0xFFFF8A80),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color = AppleBlue,
                        trackColor = Color.White.copy(alpha = 0.1f),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color = AppleBlue,
                        trackColor = Color.White.copy(alpha = 0.1f),
                    )
                }
                if (total > 0) {
                    Text(
                        text = "$current / $total",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.56f),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = collapsed,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically(
                animationSpec = tween(durationMillis = 180),
                initialOffsetY = { -it },
            ) + fadeIn(animationSpec = tween(durationMillis = 140)),
            exit = slideOutVertically(
                animationSpec = tween(durationMillis = 140),
                targetOffsetY = { -it },
            ) + fadeOut(animationSpec = tween(durationMillis = 90)),
        ) {
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = AppleBlue,
                    trackColor = Color.White.copy(alpha = 0.1f),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = AppleBlue,
                    trackColor = Color.White.copy(alpha = 0.1f),
                )
            }
        }
    }
}

@Composable
private fun LibraryToolbarChip(
    label: String,
    value: String,
    clickable: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Graphite.copy(alpha = 0.78f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f, fill = false),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LibraryRefreshChip(
    isLoading: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Graphite.copy(alpha = 0.78f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = AppleBlue,
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                Icons.Rounded.Refresh,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.72f),
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = "Refresh",
            color = Color.White.copy(alpha = 0.82f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
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

// Image thumbnail cell
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageThumbnailCell(
    image: CameraImage,
    thumbnail: Bitmap?,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    isDownloaded: Boolean = false,
    downloadProgress: Float? = null,
    isPendingDownload: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEnlarge: (() -> Unit)? = null,
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

        // Per-image download progress ring (shown while this photo is transferring)
        if (downloadProgress != null) {
            val animatedProgress by animateFloatAsState(
                targetValue = downloadProgress.coerceIn(0f, 1f),
                label = "thumbDownloadProgress",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                if (downloadProgress > 0f) {
                    CircularProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.size(40.dp),
                        color = AppleBlue,
                        trackColor = Color.White.copy(alpha = 0.30f),
                        strokeWidth = 3.dp,
                    )
                    Text(
                        text = "${(animatedProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        color = AppleBlue,
                        trackColor = Color.White.copy(alpha = 0.30f),
                        strokeWidth = 3.dp,
                    )
                }
            }
        }

        // Queued-for-download overlay: dim photos waiting their turn in the batch
        // so the user can see "this one will download later".
        if (isPendingDownload) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Schedule,
                    contentDescription = "Queued for download",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        // Enlarge button (selection mode): view the photo large and swipe while still selecting.
        if (isSelectionMode && onEnlarge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(onClick = onEnlarge),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.ZoomIn,
                    contentDescription = "Enlarge",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
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

        if ((image.rating ?: 0) > 0 || image.isCameraSelected) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                image.rating?.takeIf { it > 0 }?.let { rating ->
                    Icon(
                        Icons.Rounded.Star,
                        contentDescription = null,
                        tint = Color(0xFFFFC857),
                        modifier = Modifier.size(10.dp),
                    )
                    Text(
                        text = rating.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (image.isCameraSelected) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = "Share Order",
                        tint = AppleBlue,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedMediaDetailView(
    savedMediaItems: List<OmCaptureUsbSavedMedia>,
    selectedIndex: Int,
    selectedSavedMedia: OmCaptureUsbSavedMedia,
    bitmap: Bitmap?,
    bitmapCache: Map<String, Bitmap>,
    isLoading: Boolean,
    onSelectSavedMediaPage: (OmCaptureUsbSavedMedia, Int) -> Unit,
    onClose: () -> Unit,
) {
    val items = remember(savedMediaItems, selectedSavedMedia) {
        savedMediaItems.ifEmpty { listOf(selectedSavedMedia) }
    }
    val pagerState = rememberPagerState(initialPage = selectedIndex.coerceIn(0, items.lastIndex)) { items.size }
    val currentPage by remember(pagerState, items.size) {
        derivedStateOf { pagerState.currentPage.coerceIn(0, items.lastIndex) }
    }
    val savedMedia = items.getOrElse(currentPage) { selectedSavedMedia }
    val extension = remember(savedMedia.displayName) {
        savedMedia.displayName.substringAfterLast('.', "").uppercase(Locale.US)
    }
    BackHandler(onBack = onClose)

    LaunchedEffect(selectedIndex, items.size) {
        val targetPage = selectedIndex.coerceIn(0, items.lastIndex)
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState, items) {
        snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { page ->
            items.getOrNull(page)?.let { onSelectSavedMediaPage(it, page) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val pageMedia = items[page]
                val pageBitmap = bitmapCache[pageMedia.uriString]
                    ?: if (
                        page == currentPage &&
                        selectedSavedMedia.uriString == pageMedia.uriString
                    ) {
                        bitmap
                    } else {
                        null
                    }
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        pageBitmap != null -> {
                            Image(
                                bitmap = pageBitmap.asImageBitmap(),
                                contentDescription = pageMedia.displayName,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        }

                        page == currentPage && isLoading -> {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                        }

                        else -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.BrokenImage,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.48f),
                                    modifier = Modifier.size(68.dp),
                                )
                                Text(
                                    text = stringResource(R.string.transfer_saved_media_preview_unavailable),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.72f),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 24.dp),
                                )
                            }
                        }
                    }
                }
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.common_close),
                    tint = Color.White,
                )
            }

            if (extension.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (savedMedia.isRaw) AppleGreen.copy(alpha = 0.85f)
                            else Color.Black.copy(alpha = 0.6f),
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = extension,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            if (items.size > 1) {
                Text(
                    text = "${currentPage + 1} / ${items.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 14.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0A0A0A))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = savedMedia.displayName,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = savedMedia.relativePath,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.56f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// Image detail view with horizontal pager (swipe between photos)
@Composable
private fun ImageDetailPager(
    images: List<CameraImage>,
    initialIndex: Int,
    thumbnails: Map<String, Bitmap>,
    matchedGeoTags: Map<String, ImageGeoTagInfo>,
    previewUnavailable: Set<String>,
    isDownloading: Boolean,
    downloadProgress: String,
    backgroundTransferRunning: Boolean,
    backgroundTransferProgress: String,
    downloadedFileNames: Set<String>,
    supportsDelete: Boolean,
    primaryActionLabel: String,
    selectionMode: Boolean = false,
    selectedFullPaths: Set<String> = emptySet(),
    onToggleSelection: (CameraImage) -> Unit = {},
    onDownload: (CameraImage) -> Unit,
    onCancelDownload: () -> Unit,
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
        // Image pager
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

        // Bottom info bar
        val selectedImage = currentImage
        if (selectedImage != null) {
            if (selectionMode) {
                SelectionPreviewBar(
                    currentImage = selectedImage,
                    isSelected = selectedImage.fullPath in selectedFullPaths,
                    onToggleSelection = { onToggleSelection(selectedImage) },
                )
            } else {
                DetailBottomBar(
                    currentImage = selectedImage,
                    currentGeoTag = currentGeoTag,
                    currentPreviewUnavailable = currentPreviewUnavailable,
                    isDownloading = isDownloading,
                    downloadProgress = downloadProgress,
                    backgroundTransferRunning = backgroundTransferRunning,
                    backgroundTransferProgress = backgroundTransferProgress,
                    isAlreadyDownloaded = selectedImage.fileName in downloadedFileNames,
                    supportsDelete = supportsDelete,
                    primaryActionLabel = primaryActionLabel,
                    onOpenMap = { geoTag -> openMapForGeoTag(context, geoTag) },
                    onDelete = onDelete,
                    onDownload = onDownload,
                    onCancelDownload = onCancelDownload,
                )
            }
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
    backgroundTransferRunning: Boolean,
    backgroundTransferProgress: String,
    isAlreadyDownloaded: Boolean,
    supportsDelete: Boolean,
    primaryActionLabel: String,
    onOpenMap: (ImageGeoTagInfo) -> Unit,
    onDelete: (CameraImage) -> Unit,
    onDownload: (CameraImage) -> Unit,
    onCancelDownload: () -> Unit,
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
                onClick = {
                    if (backgroundTransferRunning) {
                        onCancelDownload()
                    } else {
                        onDownload(currentImage)
                    }
                },
                enabled = !isDownloading || backgroundTransferRunning,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (backgroundTransferRunning) {
                        Color(0xFFD14B5A)
                    } else if (isAlreadyDownloaded) {
                        AppleGreen
                    } else {
                        AppleBlue
                    },
                ),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (backgroundTransferRunning) {
                    Icon(Icons.Rounded.Close, null, modifier = Modifier.size(18.dp))
                    Text("  ${stringResource(R.string.common_cancel)}", fontWeight = FontWeight.Bold)
                } else if (isDownloading) {
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
        if (backgroundTransferRunning && backgroundTransferProgress.isNotBlank()) {
            Text(
                text = backgroundTransferProgress,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.68f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SelectionPreviewBar(
    currentImage: CameraImage,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0A0A))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = currentImage.fileName,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Button(
            onClick = onToggleSelection,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isSelected) AppleBlue else Graphite,
            ),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(
                imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Color.White,
            )
            Text(
                text = if (isSelected) "  선택됨" else "  선택",
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
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
