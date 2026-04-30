package dev.dblink.feature.remote

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.withRotation
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import dev.dblink.R
import dev.dblink.core.deepsky.DeepSkyLiveStackUiState
import dev.dblink.core.deepsky.DeepSkyPresetId
import dev.dblink.core.logging.D
import dev.dblink.core.model.ActivePropertyPicker
import dev.dblink.core.model.CameraExposureMode
import dev.dblink.core.model.CameraPropertyValues
import dev.dblink.core.model.DriveMode
import dev.dblink.core.model.GeoTagLocationSample
import dev.dblink.core.model.ModePickerSurface
import dev.dblink.core.model.RemoteRuntimeState
import dev.dblink.core.model.RemoteShootingMode
import dev.dblink.core.model.TetherPhoneImportFormat
import dev.dblink.core.model.TetherSaveTarget
import dev.dblink.core.model.TimerMode
import dev.dblink.core.model.TouchFocusRequest
import dev.dblink.core.model.TouchFocusPoint
import dev.dblink.core.protocol.PropertyFormatter
import dev.dblink.core.ui.StatusBadge
import dev.dblink.core.usb.OmCaptureUsbManager
import dev.dblink.core.usb.OmCaptureUsbOperationState
import dev.dblink.core.usb.PtpConstants
import dev.dblink.core.usb.enumerateOlympusUsbPropertyValues
import dev.dblink.core.usb.formatOlympusUsbPropertyValue
import dev.dblink.core.usb.formatOlympusAperture
import dev.dblink.core.usb.formatOlympusDriveMode
import dev.dblink.core.usb.formatOlympusExposureComp
import dev.dblink.core.usb.formatOlympusExposureMode
import dev.dblink.core.usb.formatOlympusFlashMode
import dev.dblink.core.usb.formatOlympusFocusMode
import dev.dblink.core.usb.formatOlympusImageQuality
import dev.dblink.core.usb.formatOlympusIso
import dev.dblink.core.usb.formatOlympusMetering
import dev.dblink.core.usb.formatOlympusShutterSpeed
import dev.dblink.core.usb.formatOlympusWhiteBalance
import dev.dblink.core.usb.isLegacyOlympusDriveLayout
import dev.dblink.core.usb.olympusExposureModeFromRaw
import dev.dblink.core.usb.olympusUsbPropertyLabel
import dev.dblink.core.usb.statusChipLabel
import dev.dblink.ui.OmCaptureUsbUiState
import dev.dblink.ui.theme.AppleBlue
import dev.dblink.ui.theme.AppleGreen
import dev.dblink.ui.theme.AppleOrange
import dev.dblink.ui.theme.AppleRed
import dev.dblink.ui.theme.Chalk
import dev.dblink.ui.theme.Graphite
import dev.dblink.ui.theme.LeicaBorder
import dev.dblink.ui.theme.Obsidian
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private data class RemoteRotationState(
    val readableRotation: Int,
    val animatedReadableRotation: Float,
    val quarterTurn: Boolean,
)

private data class RemoteQuickAccessItem(
    val picker: ActivePropertyPicker,
    val accent: Color,
)

private data class RemoteScpLabels(
    val mode: String,
    val afMode: String,
    val pictureMode: String,
    val drive: String,
    val flash: String,
    val metering: String,
    val quality: String,
    val faceEye: String,
    val aspect: String,
    val stabilizer: String,
    val highRes: String,
    val subject: String,
    val subjectType: String,
    val colorSpace: String,
)

private val LocalRemoteRotationState = compositionLocalOf<RemoteRotationState?> { null }
private const val TETHER_MODE_DIAL_VALUE = "Tether"
private const val TETHER_RETRY_MODE_DIAL_VALUE = "Tether Retry"
private const val DEEP_SKY_MODE_DIAL_VALUE = "Deep Sky"
private const val DEEP_SKY_FOCAL_LENGTH_PROP = "deepsky_focal_length"
private const val DEFAULT_CAPTURE_REVIEW_DURATION_SECONDS = 2
private val Om1SilentBurstDriveRawValues = setOf(
    41L, // Pro Capture SH2 on OM-1 tether reports
    67L, // Pro Capture Low
    72L, // Pro Capture SH1
    73L, // Pro Capture SH2
)

// USB SCP dual-property bindings (display state vs. writable control)
private const val USB_SCP_PICTURE_MODE_STATE_PROP = 0xD00C
private const val USB_SCP_PICTURE_MODE_CONTROL_PROP = 0xD010
private const val USB_SCP_FACE_EYE_PROP = 0xD01A
private const val USB_SCP_STABILIZER_CONTROL_PROP = 0xD08E
private const val USB_SCP_HIGH_RES_STATE_PROP = 0xD065
private const val USB_SCP_HIGH_RES_CONTROL_PROP = 0xD0C7
private const val USB_SCP_SUBJECT_DETECT_PROP = 0xD100
private const val USB_SCP_SUBJECT_TYPE_PROP = 0xD101
private const val USB_SCP_ASPECT_PROP = 0xD11A
private const val USB_SCP_STABILIZER_STATE_PROP = 0xD11B
private const val USB_SCP_COLOR_SPACE_PROP = 0xD11C

private data class ScpExtraPropBinding(
    val displayPropCode: Int,
    val controlPropCode: Int = displayPropCode,
)

private val UsbScpPictureModeBinding = ScpExtraPropBinding(
    displayPropCode = USB_SCP_PICTURE_MODE_STATE_PROP,
    controlPropCode = USB_SCP_PICTURE_MODE_CONTROL_PROP,
)
private val UsbScpHighResBinding = ScpExtraPropBinding(
    displayPropCode = USB_SCP_HIGH_RES_STATE_PROP,
    controlPropCode = USB_SCP_HIGH_RES_CONTROL_PROP,
)
private val UsbScpFaceEyeBinding = ScpExtraPropBinding(
    displayPropCode = USB_SCP_FACE_EYE_PROP,
)
private val UsbScpStabilizerBinding = ScpExtraPropBinding(
    displayPropCode = USB_SCP_STABILIZER_STATE_PROP,
    controlPropCode = USB_SCP_STABILIZER_CONTROL_PROP,
)

private fun OmCaptureUsbManager.CameraPropertiesSnapshot.findScpDisplayProperty(
    binding: ScpExtraPropBinding,
): OmCaptureUsbManager.CameraPropertyState? {
    return findProperty(binding.displayPropCode) ?: findProperty(binding.controlPropCode)
}

private fun OmCaptureUsbManager.CameraPropertiesSnapshot.findScpControlProperty(
    binding: ScpExtraPropBinding,
): OmCaptureUsbManager.CameraPropertyState? {
    return findProperty(binding.controlPropCode) ?: findProperty(binding.displayPropCode)
}

@Composable
fun RemoteScreen(
    remoteRuntime: RemoteRuntimeState,
    remoteReady: Boolean = true,
    liveViewFrame: Bitmap?,
    lastCaptureThumbnail: Bitmap? = null,
    captureReviewAfterShotEnabled: Boolean = false,
    captureReviewDurationSeconds: Int = DEFAULT_CAPTURE_REVIEW_DURATION_SECONDS,
    tetheredCaptureAvailable: Boolean = true,
    omCaptureUsb: OmCaptureUsbUiState = OmCaptureUsbUiState(),
    latestTransferThumbnail: Bitmap? = null,
    latestTransferFileName: String? = null,
    libraryBusy: Boolean = false,
    libraryStatus: String = "",
    tetherSaveTarget: TetherSaveTarget = TetherSaveTarget.SdAndPhone,
    tetherPhoneImportFormat: TetherPhoneImportFormat = TetherPhoneImportFormat.JpegOnly,
    onSetPhoneImportFormat: (TetherPhoneImportFormat) -> Unit = {},
    onToggleLiveView: () -> Unit,
    onCapturePhoto: () -> Unit,
    onExposureChanged: (String) -> Unit,
    onSetShootingMode: (RemoteShootingMode) -> Unit = {},
    onSetIntervalSeconds: (Int) -> Unit = {},
    onSetIntervalCount: (Int) -> Unit = {},
    onStartInterval: () -> Unit = {},
    onStopInterval: () -> Unit = {},
    onSetActivePicker: (ActivePropertyPicker) -> Unit = {},
    onSetModePickerSurface: (ModePickerSurface) -> Unit = {},
    onSetPropertyValue: (ActivePropertyPicker, String, Boolean) -> Unit = { _, _, _ -> },
    onTouchFocus: (TouchFocusRequest) -> Unit = {},
    onSetCameraExposureMode: (CameraExposureMode) -> Unit = {},
    onSetDriveMode: (DriveMode) -> Unit = {},
    onSetTimerMode: (TimerMode) -> Unit = {},
    onSetTimerDelay: (Int) -> Unit = {},
    onRefreshOmCaptureUsb: () -> Unit = {},
    onCaptureOmCaptureUsb: () -> Unit = {},
    onImportLatestOmCaptureUsb: () -> Unit = {},
    onClearOmCaptureUsb: () -> Unit = {},
    onOpenOmCapture: () -> Unit = {},
    deepSkyState: DeepSkyLiveStackUiState = DeepSkyLiveStackUiState(),
    onOpenDeepSkyLiveStack: () -> Unit = {},
    onStartDeepSkySession: () -> Unit = {},
    onStopDeepSkySession: () -> Unit = {},
    onResetDeepSkySession: () -> Unit = {},
    onSelectDeepSkyPreset: (DeepSkyPresetId?) -> Unit = {},
    onSetDeepSkyManualFocalLength: (Float?) -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onSetUsbProperty: (Int, Long) -> Unit = { _, _ -> },
    onManualFocusDrive: (Int) -> Unit = {},
    onRefreshUsbProperties: () -> Unit = {},
    usbCameraProperties: OmCaptureUsbManager.CameraPropertiesSnapshot = OmCaptureUsbManager.CameraPropertiesSnapshot(),
    latestGeoTagSample: GeoTagLocationSample? = null,
    reverseDialMap: Map<String, Boolean> = emptyMap(),
) {
    val sensorReadableRotation = rememberReadableCameraRotation()
    val actualLandscapeLayout = rememberLandscapeControlLayout()
    val readableRotation = if (actualLandscapeLayout) 0 else sensorReadableRotation
    val previewReadableRotation = remember(sensorReadableRotation, actualLandscapeLayout, liveViewFrame?.width, liveViewFrame?.height) {
        val previewLooksPortrait = (liveViewFrame?.width ?: 0) in 1 until (liveViewFrame?.height ?: 0)
        when {
            !actualLandscapeLayout -> sensorReadableRotation
            previewLooksPortrait -> if (isQuarterTurnRotation(sensorReadableRotation)) sensorReadableRotation else 90
            else -> 0
        }
    }
    val rotationState = rememberRemoteRotationState(readableRotation)
    val landscapeChrome = rotationState.quarterTurn || actualLandscapeLayout
    val sharedAnimatedReadableRotation = rotationState.animatedReadableRotation
    val usbTetherSurface =
        remoteRuntime.modePickerSurface == ModePickerSurface.Tether ||
            remoteRuntime.modePickerSurface == ModePickerSurface.TetherRetry ||
            remoteRuntime.modePickerSurface == ModePickerSurface.DeepSky
    val reverseEv = reverseDialMap["expcomp"] == true
    val evValues = remember(remoteRuntime.exposureCompensationValues.availableValues, reverseEv, usbTetherSurface) {
        val source = if (usbTetherSurface) {
            remoteRuntime.exposureCompensationValues.availableValues.ifEmpty {
                buildUsbExposureScaleValues()
            }
        } else {
            remoteRuntime.exposureCompensationValues.availableValues.ifEmpty {
                listOf("-3.0", "-2.0", "-1.0", "0.0", "+1.0", "+2.0", "+3.0")
            }
        }
        val normalized = source.map(::normalizeExposureDialValue).distinct()
        if (reverseEv) normalized.asReversed() else normalized
    }
    val evCurrentValue = remember(remoteRuntime.exposureCompensationValues.currentValue, usbTetherSurface) {
        normalizeExposureDialValue(remoteRuntime.exposureCompensationValues.currentValue)
    }
    val previewAspectRatio = remember(liveViewFrame?.width, liveViewFrame?.height) {
        liveViewFrame
            ?.takeIf { it.width > 0 && it.height > 0 }
            ?.let { it.width.toFloat() / it.height.toFloat() }
            ?: (4f / 3f)
    }
    val liveViewVisible = liveViewFrame != null
    val reviewBitmap = lastCaptureThumbnail ?: latestTransferThumbnail
    val reviewAspectRatio = remember(reviewBitmap?.width, reviewBitmap?.height) {
        reviewBitmap
            ?.takeIf { it.width > 0 && it.height > 0 }
            ?.let { it.width.toFloat() / it.height.toFloat() }
            ?: previewAspectRatio
    }
    val previewLayerAlpha by animateFloatAsState(
        targetValue = if (liveViewVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "liveViewPreviewAlpha",
    )
    val previewLayerScale by animateFloatAsState(
        targetValue = if (liveViewVisible) 1f else 1.02f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "liveViewPreviewScale",
    )
    val overlayPanelAlpha by animateFloatAsState(
        targetValue = if (liveViewVisible) 0f else 1f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "liveViewOverlayAlpha",
    )
    val overlayPanelScale by animateFloatAsState(
        targetValue = if (liveViewVisible) 0.98f else 1f,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "liveViewOverlayScale",
    )
    var captureReviewVisible by rememberSaveable { mutableStateOf(false) }
    val captureReviewAlpha by animateFloatAsState(
        targetValue = if (captureReviewVisible && reviewBitmap != null) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "captureReviewAlpha",
    )
    val captureReviewScale by animateFloatAsState(
        targetValue = if (captureReviewVisible && reviewBitmap != null) 1f else 1.015f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "captureReviewScale",
    )
    val liveViewAmbient = rememberInfiniteTransition(label = "liveViewAmbient")
    val gridPulseAlpha by liveViewAmbient.animateFloat(
        initialValue = 0.84f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "gridPulseAlpha",
    )
    val statusPulseScale by liveViewAmbient.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "statusPulseScale",
    )
    val statusPulseAlpha by liveViewAmbient.animateFloat(
        initialValue = 0.26f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "statusPulseAlpha",
    )

    // Stable state refs for pointerInput — liveViewFrame changes every frame (~30fps),
    // so it must NOT be a pointerInput key (which would restart the gesture handler each frame,
    // causing taps to be lost). Instead, capture the latest value via rememberUpdatedState.
    val currentFrame by rememberUpdatedState(liveViewFrame)
    val currentAspectRatio by rememberUpdatedState(previewAspectRatio)
    val currentLiveViewActive by rememberUpdatedState(remoteRuntime.liveViewActive)
    val currentRemoteReady by rememberUpdatedState(remoteReady)
    val currentModePickerSurface by rememberUpdatedState(remoteRuntime.modePickerSurface)
    val currentOnToggleLiveView by rememberUpdatedState(onToggleLiveView)
    val currentOnTouchFocus by rememberUpdatedState(onTouchFocus)
    val currentReadableRotation by rememberUpdatedState(readableRotation)
    val currentPreviewReadableRotation by rememberUpdatedState(previewReadableRotation)
    val currentPreviewQuarterTurn by rememberUpdatedState(isQuarterTurnRotation(previewReadableRotation))
    val touchView = LocalView.current
    val usbQuickAccessEnabled = tetheredCaptureAvailable &&
        (remoteRuntime.modePickerSurface == ModePickerSurface.Tether ||
            remoteRuntime.modePickerSurface == ModePickerSurface.TetherRetry ||
            remoteRuntime.modePickerSurface == ModePickerSurface.DeepSky) &&
        remoteRuntime.liveViewActive
    var quickAccessOpen by remember { mutableStateOf(false) }
    var activeUsbExtraPropCode by remember { mutableIntStateOf(-1) }
    var activeUsbExtraPropLabel by remember { mutableStateOf("") }
    var showSkyOverlay by rememberSaveable { mutableStateOf(true) }
    var showUsbTetherGuide by rememberSaveable { mutableStateOf(false) }
    val currentUsbQuickAccessEnabled by rememberUpdatedState(usbQuickAccessEnabled)
    val currentQuickAccessOpen by rememberUpdatedState(quickAccessOpen)
    val currentSetQuickAccessOpen by rememberUpdatedState<(Boolean) -> Unit>({ quickAccessOpen = it })
    val gestureDensity = LocalDensity.current
    val scpDragThresholdPx = with(gestureDensity) { 14.dp.toPx() }
    val tapSlopPx = with(gestureDensity) { 10.dp.toPx() }
    val liveClockMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(30_000L)
            value = System.currentTimeMillis()
        }
    }
    val skyOverlayInfo = remember(
        latestGeoTagSample,
        liveClockMs,
        deepSkyState.activeFocalLengthMm,
        deepSkyState.fieldOfViewLabel,
    ) {
        buildRemoteSkyOverlayInfo(
            sample = latestGeoTagSample,
            nowMs = liveClockMs,
            activeFocalLengthMm = deepSkyState.activeFocalLengthMm,
            fieldOfViewLabel = deepSkyState.fieldOfViewLabel,
            azimuthDeg = null,
        )
    }

    fun clearUsbExtraPicker() {
        activeUsbExtraPropCode = -1
        activeUsbExtraPropLabel = ""
    }

    fun openUsbTetherGuide() {
        showUsbTetherGuide = true
        if (remoteRuntime.modePickerSurface != ModePickerSurface.Tether &&
            remoteRuntime.modePickerSurface != ModePickerSurface.TetherRetry
        ) {
            onSetModePickerSurface(ModePickerSurface.Tether)
        }
    }

    val routedSetActivePicker: (ActivePropertyPicker) -> Unit = { picker ->
        clearUsbExtraPicker()
        onSetActivePicker(picker)
    }
    val openUsbExtraPicker: (Int, String) -> Unit = { propCode, label ->
        activeUsbExtraPropCode = propCode
        activeUsbExtraPropLabel = usbCameraProperties.findProperty(propCode)?.label
            ?: label.ifBlank { olympusUsbPropertyLabel(propCode) }
        D.usb("SCP extra property opened code=0x${propCode.toString(16)} label=$activeUsbExtraPropLabel")
        onSetActivePicker(ActivePropertyPicker.None)
    }

    LaunchedEffect(usbQuickAccessEnabled) {
        if (!usbQuickAccessEnabled) {
            quickAccessOpen = false
            clearUsbExtraPicker()
        }
    }

    LaunchedEffect(remoteRuntime.modePickerSurface) {
        quickAccessOpen = false
        clearUsbExtraPicker()
    }

    LaunchedEffect(remoteRuntime.liveViewActive) {
        if (!remoteRuntime.liveViewActive) {
            quickAccessOpen = false
            clearUsbExtraPicker()
        }
    }

    LaunchedEffect(captureReviewAfterShotEnabled, captureReviewDurationSeconds, remoteRuntime.captureCount) {
        if (!captureReviewAfterShotEnabled || remoteRuntime.captureCount <= 0) {
            captureReviewVisible = false
            return@LaunchedEffect
        }
        captureReviewVisible = true
        delay(captureReviewDurationSeconds.coerceAtLeast(1) * 1000L)
        captureReviewVisible = false
    }

    // Always refresh the expanded SCP property set when the overlay opens so
    // vendor props do not stay stale across live-view restarts/re-entry.
    LaunchedEffect(quickAccessOpen) {
        if (quickAccessOpen) {
            clearUsbExtraPicker()
            onRefreshUsbProperties()
        }
    }

    LaunchedEffect(remoteRuntime.activePicker, remoteRuntime.modePickerSurface) {
        if (remoteRuntime.activePicker != ActivePropertyPicker.None ||
            remoteRuntime.modePickerSurface == ModePickerSurface.DeepSky
        ) {
            clearUsbExtraPicker()
        }
    }

    LaunchedEffect(activeUsbExtraPropCode, usbCameraProperties) {
        if (activeUsbExtraPropCode >= 0 &&
            usbCameraProperties.findProperty(activeUsbExtraPropCode) == null
        ) {
            clearUsbExtraPicker()
        }
    }

    LaunchedEffect(rotationState.quarterTurn, remoteRuntime.activePicker) {
        D.layout("REMOTE_LAYOUT: orientation=${if (landscapeChrome) "LANDSCAPE" else "PORTRAIT"} rotation=$readableRotation previewRotation=$previewReadableRotation " +
            "activePicker=${remoteRuntime.activePicker} liveViewActive=${remoteRuntime.liveViewActive} " +
            "propertiesLoaded=${remoteRuntime.propertiesLoaded}")
    }

    // Shared live view box content — used in both portrait and landscape layouts
    val liveViewContent: @Composable BoxScope.() -> Unit = {
        if (liveViewFrame != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = previewLayerAlpha
                        scaleX = previewLayerScale
                        scaleY = previewLayerScale
                    },
            ) {
                LiveViewPreviewLayer(
                    liveViewFrame = liveViewFrame,
                    previewAspectRatio = previewAspectRatio,
                    readableRotation = previewReadableRotation,
                )
            }
        } else if (
            !remoteRuntime.liveViewActive &&
            (remoteRuntime.modePickerSurface == ModePickerSurface.Tether ||
                remoteRuntime.modePickerSurface == ModePickerSurface.TetherRetry ||
                remoteRuntime.modePickerSurface == ModePickerSurface.DeepSky)
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = overlayPanelAlpha
                        scaleX = overlayPanelScale
                        scaleY = overlayPanelScale
                    },
            ) {
                TetherEntryPanel(
                    omCaptureUsb = omCaptureUsb,
                    onRefreshOmCaptureUsb = onRefreshOmCaptureUsb,
                    onStartLiveView = onToggleLiveView,
                    onShowUsbTetherGuide = ::openUsbTetherGuide,
                    retryMode = remoteRuntime.modePickerSurface == ModePickerSurface.TetherRetry,
                    readableRotation = readableRotation,
                    tetherPhoneImportFormat = tetherPhoneImportFormat,
                    onSetPhoneImportFormat = onSetPhoneImportFormat,
                )
            }
        } else if (!remoteRuntime.liveViewActive) {
            val placeholderText = when {
                remoteReady -> stringResource(R.string.remote_live_view_prompt)
                else -> stringResource(R.string.remote_connect_prompt)
            }
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer {
                        rotationZ = sharedAnimatedReadableRotation
                        alpha = overlayPanelAlpha
                        scaleX = overlayPanelScale
                        scaleY = overlayPanelScale
                    }
                    .padding(horizontal = if (landscapeChrome) 28.dp else 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = placeholderText,
                    color = Chalk.copy(alpha = 0.78f),
                    style = if (landscapeChrome) {
                        MaterialTheme.typography.headlineSmall
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                )
                if (!remoteReady) {
                    Text(
                        text = stringResource(R.string.remote_usb_tether_guide_link),
                        modifier = Modifier.clickable(onClick = ::openUsbTetherGuide),
                        color = AppleBlue,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        PreviewGridOverlay(
            previewAspectRatio = previewAspectRatio,
            readableRotation = previewReadableRotation,
            strength = if (remoteRuntime.liveViewActive) gridPulseAlpha else 0.78f,
        )
        if (
            remoteRuntime.modePickerSurface == ModePickerSurface.DeepSky &&
            remoteRuntime.liveViewActive &&
            deepSkyState.activeFocalLengthMm != null
        ) {
            DeepSkyStackFrameOverlay(
                state = deepSkyState,
                previewAspectRatio = previewAspectRatio,
                readableRotation = previewReadableRotation,
            )
        }
        if (
            remoteRuntime.modePickerSurface == ModePickerSurface.DeepSky &&
            remoteRuntime.liveViewActive &&
            showSkyOverlay
        ) {
            DeepSkyEnvironmentOverlay(
                skyInfo = skyOverlayInfo,
                readableRotation = readableRotation,
            )
        }
        FocusBoxOverlay(
            focusPoint = remoteRuntime.touchFocusPoint,
            focusHeld = remoteRuntime.focusHeld,
            isFocusing = remoteRuntime.isFocusing,
            previewAspectRatio = previewAspectRatio,
            readableRotation = previewReadableRotation,
        )
        if (captureReviewVisible && reviewBitmap != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = captureReviewAlpha
                        scaleX = captureReviewScale
                        scaleY = captureReviewScale
                    }
                    .background(Color.Black.copy(alpha = 0.56f)),
            ) {
                CaptureReviewOverlay(
                    bitmap = reviewBitmap,
                    previewAspectRatio = reviewAspectRatio,
                    readableRotation = previewReadableRotation,
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Graphite.copy(alpha = 0.82f))
                        .border(1.dp, Chalk.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_capture_review_after_shot_title),
                        color = Chalk,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        if (remoteRuntime.liveViewActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(18.dp)
                    .size(18.dp)
                    .graphicsLayer {
                        alpha = statusPulseAlpha
                        scaleX = statusPulseScale
                        scaleY = statusPulseScale
                    }
                    .clip(CircleShape)
                    .background(AppleRed.copy(alpha = 0.28f)),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(23.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(AppleRed),
            )
        }
        if (usbQuickAccessEnabled) {
            AdaptiveSuperControlPanelOverlay(
                open = quickAccessOpen,
                usbCameraProperties = usbCameraProperties,
                onOpenChange = { quickAccessOpen = it },
                onOpenUsbExtraProperty = openUsbExtraPicker,
                readableRotation = readableRotation,
            )
        }
        if (showUsbTetherGuide) {
            UsbTetherGuideOverlay(
                onClose = { showUsbTetherGuide = false },
            )
        }
    }

    val liveViewModifier = Modifier
        .background(Color.Black)
        .pointerInput(
            usbQuickAccessEnabled,
            quickAccessOpen,
            remoteRuntime.modePickerSurface,
            readableRotation,
            scpDragThresholdPx,
            tapSlopPx,
        ) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var totalX = 0f
                var totalY = 0f
                var handledDrag = false
                val swipeAxis = scpSwipeAxis(currentReadableRotation)
                val crossAxis = Offset(-swipeAxis.y, swipeAxis.x)
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val delta = change.position - change.previousPosition
                    totalX += delta.x
                    totalY += delta.y
                    val primaryMotion = (totalX * swipeAxis.x) + (totalY * swipeAxis.y)
                    val crossMotion = (totalX * crossAxis.x) + (totalY * crossAxis.y)

                    if (
                        !handledDrag &&
                        currentUsbQuickAccessEnabled &&
                        abs(primaryMotion) > scpDragThresholdPx &&
                        abs(primaryMotion) > abs(crossMotion) * 0.8f
                    ) {
                        if (!currentQuickAccessOpen && primaryMotion > 0f) {
                            currentSetQuickAccessOpen(true)
                        } else if (currentQuickAccessOpen && primaryMotion < 0f) {
                            currentSetQuickAccessOpen(false)
                        }
                        handledDrag = true
                        change.consume()
                    }

                    if (!change.pressed) {
                        if (
                            !handledDrag &&
                            abs(change.position.x - down.position.x) <= tapSlopPx &&
                            abs(change.position.y - down.position.y) <= tapSlopPx &&
                            !currentQuickAccessOpen
                        ) {
                            val offset = change.position
                            if (!currentLiveViewActive) {
                                if (
                                    currentModePickerSurface == ModePickerSurface.Tether ||
                                    currentModePickerSurface == ModePickerSurface.TetherRetry ||
                                    currentModePickerSurface == ModePickerSurface.DeepSky
                                ) {
                                    break
                                }
                                if (!currentRemoteReady) {
                                    break
                                }
                                currentOnToggleLiveView()
                                break
                            }
                            if (currentFrame == null) break
                            val effectiveAspect = if (currentPreviewQuarterTurn) 1f / currentAspectRatio else currentAspectRatio
                            val previewRect = resolvePreviewRect(
                                containerWidth = size.width.toFloat(),
                                containerHeight = size.height.toFloat(),
                                imageAspectRatio = effectiveAspect,
                            )
                            if (!previewRect.contains(offset)) {
                                break
                            }
                            val normalizedX = ((offset.x - previewRect.left) / previewRect.width).coerceIn(0f, 1f)
                            val normalizedY = ((offset.y - previewRect.top) / previewRect.height).coerceIn(0f, 1f)
                            val sensorCoord = mapDisplayFocusPointToSensor(
                                normalizedX = normalizedX,
                                normalizedY = normalizedY,
                                rotationDegrees = currentReadableRotation,
                            )
                            ViewCompat.performHapticFeedback(touchView, HapticFeedbackConstantsCompat.CLOCK_TICK)
                            currentOnTouchFocus(
                                TouchFocusRequest(
                                    displayX = normalizedX,
                                    displayY = normalizedY,
                                    focusPlaneX = sensorCoord.x,
                                    focusPlaneY = sensorCoord.y,
                                    rotationDegrees = currentPreviewReadableRotation,
                                ),
                            )
                        }
                        break
                    }
                }
            }
        }

    // Shared controls content — selector row, picker/dial, capture bar
    val selectorRowContent: @Composable () -> Unit = {
        RemoteSelectorRow(
            remoteRuntime = remoteRuntime,
            onSetActivePicker = routedSetActivePicker,
            readableRotation = readableRotation,
            usbTetherConnected = omCaptureUsb.summary != null,
            usbCameraProperties = usbCameraProperties,
        )
    }
    val panelContent: @Composable () -> Unit = {
        RemoteControlPanel(
            runtime = remoteRuntime,
            tetheredCaptureAvailable = tetheredCaptureAvailable,
            omCaptureUsb = omCaptureUsb,
            evValues = evValues,
            evCurrentValue = evCurrentValue,
            latestTransferThumbnail = latestTransferThumbnail ?: lastCaptureThumbnail,
            latestTransferFileName = latestTransferFileName,
            libraryBusy = libraryBusy,
            libraryStatus = libraryStatus,
            tetherSaveTarget = tetherSaveTarget,
            onToggleLiveView = onToggleLiveView,
            onExposureChanged = onExposureChanged,
            onSetCameraExposureMode = onSetCameraExposureMode,
            onSetModePickerSurface = onSetModePickerSurface,
            onSetPropertyValue = onSetPropertyValue,
            onSetShootingMode = onSetShootingMode,
            onSetDriveMode = onSetDriveMode,
            onSetTimerMode = onSetTimerMode,
            onSetTimerDelay = onSetTimerDelay,
            onSetIntervalSeconds = onSetIntervalSeconds,
            onSetIntervalCount = onSetIntervalCount,
            onRefreshOmCaptureUsb = onRefreshOmCaptureUsb,
            onCaptureOmCaptureUsb = onCaptureOmCaptureUsb,
            onImportLatestOmCaptureUsb = onImportLatestOmCaptureUsb,
            onClearOmCaptureUsb = onClearOmCaptureUsb,
            onOpenOmCapture = onOpenOmCapture,
            deepSkyState = deepSkyState,
            onOpenDeepSkyLiveStack = onOpenDeepSkyLiveStack,
            onStartDeepSkySession = onStartDeepSkySession,
            onStopDeepSkySession = onStopDeepSkySession,
            onResetDeepSkySession = onResetDeepSkySession,
            onSelectDeepSkyPreset = onSelectDeepSkyPreset,
            onSetDeepSkyManualFocalLength = onSetDeepSkyManualFocalLength,
            showSkyOverlay = showSkyOverlay,
            onSetShowSkyOverlay = { showSkyOverlay = it },
            skyOverlayInfo = skyOverlayInfo,
            onOpenLibrary = onOpenLibrary,
            onSetUsbProperty = onSetUsbProperty,
            usbCameraProperties = usbCameraProperties,
            usbTetherConnected = omCaptureUsb.summary != null,
            activeUsbExtraPropCode = activeUsbExtraPropCode.takeIf { it >= 0 },
            activeUsbExtraPropLabel = activeUsbExtraPropLabel,
            reverseDialMap = reverseDialMap,
            readableRotation = readableRotation,
        )
    }

    // Both portrait and landscape use the SAME Column layout structure.
    // In landscape, the individual icons/text/controls are rotated 90° via
    // readableRotation (graphicsLayer { rotationZ }) — the overall anchor
    // positions remain identical to portrait. This matches the user's
    // requirement: "same overall anchor/position logic as portrait, then
    // rotate the icon/text/control composition intelligently by 90°."
    CompositionLocalProvider(
        LocalRemoteRotationState provides rotationState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Obsidian)
                .padding(top = if (landscapeChrome) 8.dp else 0.dp),
            verticalArrangement = if (landscapeChrome) Arrangement.spacedBy(10.dp) else Arrangement.Top,
        ) {
            Box(
                modifier = liveViewModifier
                    .weight(1f)
                    .fillMaxWidth(),
                content = liveViewContent,
            )
            if (landscapeChrome) {
                LandscapeRemoteControlDeck(
                    runtime = remoteRuntime,
                    lastCaptureThumbnail = lastCaptureThumbnail,
                    onCapturePhoto = onCapturePhoto,
                    onStartInterval = onStartInterval,
                    onStopInterval = onStopInterval,
                    onSetActivePicker = routedSetActivePicker,
                    onOpenLibrary = onOpenLibrary,
                    readableRotation = readableRotation,
                    selectorContent = selectorRowContent,
                    panelContent = panelContent,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.92f))
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = 6.dp,
                            bottom = 22.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    selectorRowContent()
                    panelContent()
                    BottomControlBar(
                        runtime = remoteRuntime,
                        lastCaptureThumbnail = lastCaptureThumbnail,
                        onCapturePhoto = onCapturePhoto,
                        onStartInterval = onStartInterval,
                        onStopInterval = onStopInterval,
                        onSetActivePicker = routedSetActivePicker,
                        onOpenLibrary = onOpenLibrary,
                        readableRotation = readableRotation,
                    )
                }
            }
        }
    }
}

@Composable
private fun animateReadableRotationValue(
    readableRotation: Int,
    label: String,
): Float {
    val animated by animateFloatAsState(
        targetValue = readableRotation.toFloat(),
        animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
        label = label,
    )
    return animated
}

@Composable
private fun rememberRemoteRotationState(
    readableRotation: Int,
): RemoteRotationState {
    val animatedReadableRotation = animateReadableRotationValue(
        readableRotation = readableRotation,
        label = "remoteSharedRotation",
    )
    return remember(readableRotation, animatedReadableRotation) {
        RemoteRotationState(
            readableRotation = readableRotation,
            animatedReadableRotation = animatedReadableRotation,
            quarterTurn = isQuarterTurnRotation(readableRotation),
        )
    }
}

@Composable
private fun currentRemoteRotationState(readableRotation: Int): RemoteRotationState {
    return LocalRemoteRotationState.current ?: rememberRemoteRotationState(readableRotation)
}

@Composable
private fun rememberAnimatedReadableRotation(
    readableRotation: Int,
    label: String,
): Float {
    return currentRemoteRotationState(readableRotation).animatedReadableRotation
}

@Composable
private fun rememberRemoteQuarterTurn(readableRotation: Int): Boolean {
    return currentRemoteRotationState(readableRotation).quarterTurn
}

@Composable
private fun rememberLandscapeControlLayout(): Boolean {
    val containerSize = LocalWindowInfo.current.containerSize
    return containerSize.width > containerSize.height
}

private fun normalizedReadableRotation(readableRotation: Int): Int {
    val normalized = readableRotation % 360
    return if (normalized < 0) normalized + 360 else normalized
}

private fun scpSwipeAxis(readableRotation: Int): Offset = when (normalizedReadableRotation(readableRotation)) {
    90 -> Offset(0f, 1f)
    180 -> Offset(-1f, 0f)
    270 -> Offset(0f, -1f)
    else -> Offset(1f, 0f)
}

private fun isSubjectDetectionEnabled(
    property: OmCaptureUsbManager.CameraPropertyState?,
): Boolean {
    return when (property?.currentValue?.toInt()) {
        null, 0, 1 -> false
        else -> true
    }
}

@Composable
private fun BoxScope.TetherEntryPanel(
    omCaptureUsb: OmCaptureUsbUiState,
    onRefreshOmCaptureUsb: () -> Unit,
    onStartLiveView: () -> Unit,
    onShowUsbTetherGuide: () -> Unit,
    retryMode: Boolean,
    readableRotation: Int,
    tetherPhoneImportFormat: TetherPhoneImportFormat = TetherPhoneImportFormat.JpegOnly,
    onSetPhoneImportFormat: (TetherPhoneImportFormat) -> Unit = {},
) {
    val animatedRotation = rememberAnimatedReadableRotation(readableRotation, label = "tetherEntryRotation")
    val view = LocalView.current
    val summary = omCaptureUsb.summary
    val accent = when {
        omCaptureUsb.isBusy -> AppleOrange
        summary != null -> AppleGreen
        else -> AppleBlue
    }
    val readyFallbackLabel = stringResource(R.string.remote_usb_tether_ready)
    val liveViewFeatureLabel = stringResource(R.string.remote_usb_tether_feature_live_view)
    val controlFeatureLabel = stringResource(R.string.remote_usb_tether_feature_control)
    Column(
        modifier = Modifier
            .align(Alignment.Center)
            .fillMaxWidth(0.72f)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.82f))
            .border(1.dp, Chalk.copy(alpha = 0.18f), RoundedCornerShape(24.dp))
            .padding(horizontal = 20.dp, vertical = 18.dp)
            .graphicsLayer { rotationZ = animatedRotation },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusBadge(
            label = omCaptureUsb.operationState.statusChipLabel,
            color = accent,
        )
        Text(
            text = if (summary == null) {
                stringResource(R.string.remote_usb_tether_connect_camera)
            } else {
                summary.deviceLabel.ifBlank { readyFallbackLabel }
            },
            color = Chalk,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = omCaptureUsb.statusLabel,
            color = Chalk.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (summary != null) {
            Text(
                text = buildString {
                    append(summary.model.ifBlank { summary.manufacturer })
                    if (summary.supportsLiveView) append("  •  $liveViewFeatureLabel")
                    if (summary.supportsPropertyControl) append("  •  $controlFeatureLabel")
                },
                color = Chalk.copy(alpha = 0.56f),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.remote_usb_tether_import_format),
                color = Chalk.copy(alpha = 0.46f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TetherActionButton(
                    modifier = Modifier.weight(1f),
                    label = "JPEG",
                    accent = if (tetherPhoneImportFormat == TetherPhoneImportFormat.JpegOnly) AppleBlue else Chalk.copy(alpha = 0.35f),
                ) { onSetPhoneImportFormat(TetherPhoneImportFormat.JpegOnly) }
                TetherActionButton(
                    modifier = Modifier.weight(1f),
                    label = "RAW",
                    accent = if (tetherPhoneImportFormat == TetherPhoneImportFormat.RawOnly) AppleBlue else Chalk.copy(alpha = 0.35f),
                ) { onSetPhoneImportFormat(TetherPhoneImportFormat.RawOnly) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TetherActionButton(
                modifier = Modifier.weight(1f),
                label = if (retryMode || summary == null || omCaptureUsb.canRetry) {
                    stringResource(R.string.remote_usb_tether_connect)
                } else {
                    stringResource(R.string.remote_usb_tether_refresh)
                },
                accent = AppleBlue,
            ) {
                ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.CLOCK_TICK)
                onRefreshOmCaptureUsb()
            }
            TetherActionButton(
                modifier = Modifier.weight(1f),
                label = if (summary?.supportsLiveView == true) {
                    if (retryMode) {
                        stringResource(R.string.remote_usb_tether_return_live_view)
                    } else {
                        stringResource(R.string.remote_usb_tether_start_live_view)
                    }
                } else {
                    stringResource(R.string.remote_usb_tether_preparing)
                },
                accent = if (summary?.supportsLiveView == true) AppleRed else Chalk.copy(alpha = 0.28f),
                enabled = summary?.supportsLiveView == true && !omCaptureUsb.isBusy,
            ) {
                ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.CLOCK_TICK)
                onStartLiveView()
            }
        }
        Text(
            text = stringResource(R.string.remote_usb_tether_guide_link),
            modifier = Modifier.clickable(onClick = onShowUsbTetherGuide),
            color = AppleBlue,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BoxScope.UsbTetherGuideOverlay(
    onClose: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = 0.94f))
            .border(1.dp, Chalk.copy(alpha = 0.14f)),
    ) {
        Column(
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.remote_usb_tether_guide_title),
                    color = Chalk,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.common_close),
                        tint = Chalk,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                UsbTetherGuideStep(
                    number = 1,
                    text = stringResource(R.string.remote_usb_tether_guide_step_1),
                )
                UsbTetherGuideStep(
                    number = 2,
                    text = stringResource(R.string.remote_usb_tether_guide_step_2),
                )
                UsbTetherGuideStep(
                    number = 3,
                    text = stringResource(R.string.remote_usb_tether_guide_step_3),
                )
                UsbTetherGuideStep(
                    number = 4,
                    text = stringResource(R.string.remote_usb_tether_guide_step_4),
                )
                Text(
                    text = stringResource(R.string.remote_usb_tether_guide_note_primary),
                    color = Chalk.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 22.sp,
                )
                Text(
                    text = stringResource(R.string.remote_usb_tether_guide_note_secondary),
                    color = Chalk.copy(alpha = 0.68f),
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 20.sp,
                )
            }
        }
    }
}

@Composable
private fun UsbTetherGuideStep(
    number: Int,
    text: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(AppleBlue.copy(alpha = 0.18f))
                .border(1.dp, AppleBlue.copy(alpha = 0.42f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                color = Chalk,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            color = Chalk.copy(alpha = 0.84f),
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun rememberRemoteScpLabels(): RemoteScpLabels = RemoteScpLabels(
    mode = stringResource(R.string.remote_scp_label_mode),
    afMode = stringResource(R.string.remote_scp_label_af_mode),
    pictureMode = stringResource(R.string.remote_scp_label_picture_mode),
    drive = stringResource(R.string.remote_scp_label_drive),
    flash = stringResource(R.string.remote_scp_label_flash),
    metering = stringResource(R.string.remote_scp_label_metering),
    quality = stringResource(R.string.remote_scp_label_quality),
    faceEye = stringResource(R.string.remote_scp_label_face_eye),
    aspect = stringResource(R.string.remote_scp_label_aspect),
    stabilizer = stringResource(R.string.remote_scp_label_stabilizer),
    highRes = stringResource(R.string.remote_scp_label_high_res),
    subject = stringResource(R.string.remote_scp_label_subject),
    subjectType = stringResource(R.string.remote_scp_label_subject_type),
    colorSpace = stringResource(R.string.remote_scp_label_color_space),
)

@Composable
private fun TetherActionButton(
    modifier: Modifier = Modifier,
    label: String,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = if (enabled) 0.16f else 0.06f))
            .border(1.dp, accent.copy(alpha = if (enabled) 0.55f else 0.16f), RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) Chalk else Chalk.copy(alpha = 0.35f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun LiveViewPreviewLayer(
    liveViewFrame: Bitmap,
    previewAspectRatio: Float,
    readableRotation: Int = 0,
) {
    val rotated = rememberRemoteQuarterTurn(readableRotation)
    Canvas(modifier = Modifier.fillMaxSize()) {
        // When phone is rotated, invert the aspect ratio so the image fills the container
        val effectiveAspect = if (rotated) 1f / previewAspectRatio else previewAspectRatio
        val previewRect = resolvePreviewRect(
            containerWidth = size.width,
            containerHeight = size.height,
            imageAspectRatio = effectiveAspect,
        )

        if (rotated) {
            val cx = previewRect.left + previewRect.width / 2f
            val cy = previewRect.top + previewRect.height / 2f
            val nativeCanvas = drawContext.canvas.nativeCanvas
            // After rotating 90°, width↔height swap:
            // draw into a rect whose pre-rotation size matches the post-rotation previewRect
            val drawW = previewRect.height
            val drawH = previewRect.width
            nativeCanvas.withRotation(readableRotation.toFloat(), cx, cy) {
                drawBitmap(
                    liveViewFrame,
                    null,
                    android.graphics.RectF(
                        cx - drawW / 2f,
                        cy - drawH / 2f,
                        cx + drawW / 2f,
                        cy + drawH / 2f,
                    ),
                    null,
                )
            }
        } else {
            val image = liveViewFrame.asImageBitmap()
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(previewRect.left.roundToInt(), previewRect.top.roundToInt()),
                dstSize = IntSize(
                    previewRect.width.roundToInt().coerceAtLeast(1),
                    previewRect.height.roundToInt().coerceAtLeast(1),
                ),
            )
        }
    }
}

@Composable
private fun CaptureReviewOverlay(
    bitmap: Bitmap,
    previewAspectRatio: Float,
    readableRotation: Int = 0,
) {
    val rotated = rememberRemoteQuarterTurn(readableRotation)
    Canvas(modifier = Modifier.fillMaxSize()) {
        val effectiveAspect = if (rotated) 1f / previewAspectRatio else previewAspectRatio
        val previewRect = resolvePreviewRect(
            containerWidth = size.width,
            containerHeight = size.height,
            imageAspectRatio = effectiveAspect,
        )

        if (rotated) {
            val cx = previewRect.left + previewRect.width / 2f
            val cy = previewRect.top + previewRect.height / 2f
            val nativeCanvas = drawContext.canvas.nativeCanvas
            val drawW = previewRect.height
            val drawH = previewRect.width
            nativeCanvas.withRotation(readableRotation.toFloat(), cx, cy) {
                drawBitmap(
                    bitmap,
                    null,
                    android.graphics.RectF(
                        cx - drawW / 2f,
                        cy - drawH / 2f,
                        cx + drawW / 2f,
                        cy + drawH / 2f,
                    ),
                    null,
                )
            }
        } else {
            val image = bitmap.asImageBitmap()
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(previewRect.left.roundToInt(), previewRect.top.roundToInt()),
                dstSize = IntSize(
                    previewRect.width.roundToInt().coerceAtLeast(1),
                    previewRect.height.roundToInt().coerceAtLeast(1),
                ),
            )
        }
    }
}

@Composable
private fun PreviewGridOverlay(
    previewAspectRatio: Float,
    readableRotation: Int = 0,
    strength: Float = 1f,
) {
    val rotated = rememberRemoteQuarterTurn(readableRotation)
    Canvas(modifier = Modifier.fillMaxSize()) {
        val effectiveAspect = if (rotated) 1f / previewAspectRatio else previewAspectRatio
        val previewRect = resolvePreviewRect(
            containerWidth = size.width,
            containerHeight = size.height,
            imageAspectRatio = effectiveAspect,
        )
        val gridColor = Chalk.copy(alpha = 0.07f * strength)
        drawLine(
            gridColor,
            Offset(previewRect.left + (previewRect.width / 3f), previewRect.top),
            Offset(previewRect.left + (previewRect.width / 3f), previewRect.bottom),
            1f,
        )
        drawLine(
            gridColor,
            Offset(previewRect.left + (previewRect.width * 2f / 3f), previewRect.top),
            Offset(previewRect.left + (previewRect.width * 2f / 3f), previewRect.bottom),
            1f,
        )
        drawLine(
            gridColor,
            Offset(previewRect.left, previewRect.top + (previewRect.height / 3f)),
            Offset(previewRect.right, previewRect.top + (previewRect.height / 3f)),
            1f,
        )
        drawLine(
            gridColor,
            Offset(previewRect.left, previewRect.top + (previewRect.height * 2f / 3f)),
            Offset(previewRect.right, previewRect.top + (previewRect.height * 2f / 3f)),
            1f,
        )
    }
}

@Composable
private fun FocusBoxOverlay(
    focusPoint: TouchFocusPoint?,
    focusHeld: Boolean,
    isFocusing: Boolean,
    previewAspectRatio: Float,
    readableRotation: Int = 0,
) {
    if (focusPoint == null) return
    val rotated = rememberRemoteQuarterTurn(readableRotation)

    // Animate box scale on appearance for visual pop
    val animatedScale by animateFloatAsState(
        targetValue = if (isFocusing) 1.15f else 1f,
        animationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing),
        label = "focusScale",
    )
    // Animate opacity for visibility
    val animatedAlpha by animateFloatAsState(
        targetValue = if (focusHeld) 1f else 0.95f,
        animationSpec = tween(durationMillis = 120),
        label = "focusAlpha",
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val effectiveAspect = if (rotated) 1f / previewAspectRatio else previewAspectRatio
        val previewRect = resolvePreviewRect(
            containerWidth = size.width,
            containerHeight = size.height,
            imageAspectRatio = effectiveAspect,
        )
        val baseSize = minOf(previewRect.width, previewRect.height) * 0.14f
        val frameSize = baseSize * animatedScale
        val left = previewRect.left + (focusPoint.x * previewRect.width) - (frameSize / 2f)
        val top = previewRect.top + (focusPoint.y * previewRect.height) - (frameSize / 2f)
        val strokeColor = when {
            focusHeld -> Color(0xFF43D86B)     // Green — focus locked
            isFocusing -> Color(0xFFFFFFFF)     // Bright white while focusing
            else -> Color(0xFFFF4444)           // Red — focus failed
        }
        val strokeWidth = if (focusHeld) 3.5f else 2.5f

        // Draw outer glow for visibility against any background
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.4f * animatedAlpha),
            topLeft = Offset(left - 1f, top - 1f),
            size = Size(frameSize + 2f, frameSize + 2f),
            cornerRadius = CornerRadius(13f, 13f),
            style = Stroke(width = strokeWidth + 2f),
        )
        // Draw main AF frame
        drawRoundRect(
            color = strokeColor.copy(alpha = animatedAlpha),
            topLeft = Offset(left, top),
            size = Size(frameSize, frameSize),
            cornerRadius = CornerRadius(12f, 12f),
            style = Stroke(width = strokeWidth),
        )
    }
}

/**
 * Super Control Panel overlay — transparent OM-1 style SCP that appears
 * on horizontal swipe over the live view in tether mode.
 * Matches the OM-1 camera's SCP grid layout.
 * Completely transparent background — only text/borders are visible.
 */
@Composable
private fun BoxScope.SuperControlPanelOverlay(
    open: Boolean,
    usbCameraProperties: OmCaptureUsbManager.CameraPropertiesSnapshot,
    onOpenChange: (Boolean) -> Unit,
    onOpenUsbExtraProperty: (Int, String) -> Unit,
    onManualFocusDrive: (Int) -> Unit,
    readableRotation: Int = 0,
) {
    val density = LocalDensity.current
    val fullScreenOverlay = rememberRemoteQuarterTurn(readableRotation)
    val dragThreshold = 14.dp
    val dragThresholdPx = with(density) { dragThreshold.toPx() }

    // Slide-up animation: 0 = fully hidden below, 1 = fully visible
    val slideProgress by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "scpSlide",
    )
    val animatedRotation = rememberAnimatedReadableRotation(readableRotation, label = "scpRotation")

    // Pull-up gesture detector — covers entire live view area.
    // Detects PHYSICAL upward drag (negative screen-Y) to open, downward to close.
    // Direction is NOT transformed by rotation so it always feels the same.
    // Uses PointerEventPass.Final so touch-AF taps on Main pass aren't blocked.
    Box(
        modifier = Modifier
            .matchParentSize()
            .pointerInput(open, dragThresholdPx) {
                awaitEachGesture {
                    // Always accept events (requireUnconsumed=false) because the
                    // live-view tap-to-focus gesture on Main pass already consumed
                    // the down. We run on Final pass and only act on clear vertical
                    // drags, so tap-to-focus still works for taps.
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Final,
                    )
                    var totalY = 0f
                    var totalX = 0f
                    while (true) {
                        val event = awaitPointerEvent(
                            pass = PointerEventPass.Final,
                        )
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val delta = change.position - change.previousPosition
                        totalX += delta.x
                        totalY += delta.y
                        // Require predominantly vertical drag (physical screen Y)
                        if (abs(totalY) > dragThresholdPx && abs(totalY) > abs(totalX) * 0.8f) {
                            if (!open && totalY < 0f) {
                                D.action("SCP gesture: drag UP detected (totalY=${"%.0f".format(totalY)}, threshold=${"%.0f".format(dragThresholdPx)}) → opening")
                                onOpenChange(true)
                            } else if (open && totalY > 0f) {
                                D.action("SCP gesture: drag DOWN detected (totalY=${"%.0f".format(totalY)}) → closing")
                                onOpenChange(false)
                            } else {
                                D.action("SCP gesture: drag ignored (open=$open, totalY=${"%.0f".format(totalY)})")
                            }
                            change.consume()
                            break
                        }
                        if (!change.pressed) break
                    }
                }
            },
    ) {
        if (slideProgress > 0f) {
            // Tap-to-dismiss scrim
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = slideProgress * 0.28f / 0.28f }
                    .background(Color.Black.copy(alpha = 0.28f * slideProgress))
                    .clickable { onOpenChange(false) },
            )

            // SCP content — slides up from bottom with device-rotation aware content
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .then(
                        if (fullScreenOverlay) {
                            Modifier.fillMaxWidth()
                        } else {
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 10.dp)
                        },
                    )
                    .graphicsLayer {
                        // Slide up from below: offset Y based on slide progress
                        translationY = (1f - slideProgress) * 400f
                        alpha = slideProgress
                        rotationZ = animatedRotation
                    }
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .background(Color.Black.copy(alpha = 0.94f))
                    .border(
                        width = 1.dp,
                        color = Chalk.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                    )
                    .padding(
                        horizontal = if (fullScreenOverlay) 16.dp else 10.dp,
                        vertical = if (fullScreenOverlay) 18.dp else 10.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val props = usbCameraProperties
                val hasProps = props.allProperties().isNotEmpty()

                // Page indicator
                if (!hasProps) {
                    Text(
                        text = stringResource(R.string.remote_loading_camera_properties),
                        color = Chalk.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        textAlign = TextAlign.Center,
                    )
                } else {
                    ScpSinglePage(
                        props = props,
                        onOpenChange = onOpenChange,
                        onOpenUsbExtraProperty = onOpenUsbExtraProperty,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScpSinglePage(
    props: OmCaptureUsbManager.CameraPropertiesSnapshot,
    onOpenChange: (Boolean) -> Unit,
    onOpenUsbExtraProperty: (Int, String) -> Unit,
) {
    val pictureModeDisplayProp = props.findScpDisplayProperty(UsbScpPictureModeBinding)
    val pictureModeControlProp = props.findScpControlProperty(UsbScpPictureModeBinding)
    val highResDisplayProp = props.findScpDisplayProperty(UsbScpHighResBinding)
    val highResControlProp = props.findScpControlProperty(UsbScpHighResBinding)
    val faceEyeProp = props.findScpControlProperty(UsbScpFaceEyeBinding)
    val subjectDetectProp = props.findProperty(USB_SCP_SUBJECT_DETECT_PROP)
    val subjectTypeProp = props.findProperty(USB_SCP_SUBJECT_TYPE_PROP)
    val stabilizerDisplayProp = props.findScpDisplayProperty(UsbScpStabilizerBinding)
    val stabilizerControlProp = props.findScpControlProperty(UsbScpStabilizerBinding)
    val aspectProp = props.findProperty(USB_SCP_ASPECT_PROP)
    val colorSpaceProp = props.findProperty(USB_SCP_COLOR_SPACE_PROP)
    val labels = rememberRemoteScpLabels()
    val faceEyeDisplay = formatScpPtpValue(faceEyeProp)
    val subjectDetectDisplay = formatScpPtpValue(subjectDetectProp)
    val subjectTypeDisplay = if (subjectDetectDisplay.equals("Off", ignoreCase = true)) {
        "Off"
    } else {
        formatScpPtpValue(subjectTypeProp)
    }

    fun openExtraProperty(property: OmCaptureUsbManager.CameraPropertyState?) {
        property ?: return
        onOpenChange(false)
        onOpenUsbExtraProperty(property.propCode, property.label)
    }

    // Row 1: Exposure Mode + Exposure Triangle (matches camera SCP top row)
    ScpRow {
        ScpCell(
            label = labels.mode,
            value = formatUsbScpExposureMode(props),
            propCode = props.exposureMode?.propCode ?: PtpConstants.OlympusProp.ExposureMode,
            modifier = Modifier.weight(1f),
            highlight = true,
            onClick = null, // Read-only — PASM/B mode is controlled on the camera body
        )
        ScpCell(
            label = "SS",
            value = formatUsbScpShutterSpeed(props.shutterSpeed?.currentValue ?: -1L),
            propCode = PtpConstants.OlympusProp.ShutterSpeed,
            modifier = Modifier.weight(1f),
            onClick = { openExtraProperty(props.shutterSpeed) },
        )
        ScpCell(
            label = "F",
            value = formatUsbScpAperture(props.aperture?.currentValue ?: -1L),
            propCode = PtpConstants.OlympusProp.Aperture,
            modifier = Modifier.weight(1f),
            onClick = { openExtraProperty(props.aperture) },
        )
        ScpCell(
            label = "ISO",
            value = formatUsbScpIso(props.iso?.currentValue ?: -1L),
            propCode = props.iso?.propCode ?: PtpConstants.OlympusProp.ISOSpeed,
            modifier = Modifier.weight(1f),
            onClick = { openExtraProperty(props.iso) },
        )
    }

    // Row 2: AF + EV + WB + Picture Mode
    ScpRow {
        ScpCell(
            label = labels.afMode,
            value = formatUsbScpFocusMode(props.focusMode?.currentValue ?: -1L),
            propCode = PtpConstants.OlympusProp.FocusMode,
            modifier = Modifier.weight(1f),
            onClick = { openExtraProperty(props.focusMode) },
        )
        ScpCell(
            label = "EV",
            value = formatUsbScpExpComp(props.exposureComp?.currentValue ?: -1L),
            propCode = PtpConstants.OlympusProp.ExposureCompensation,
            modifier = Modifier.weight(1f),
            onClick = props.exposureComp?.let { { openExtraProperty(it) } },
        )
        ScpCell(
            label = "WB",
            value = formatUsbScpWbValue(props.whiteBalance?.currentValue ?: -1L),
            propCode = PtpConstants.OlympusProp.WhiteBalance,
            modifier = Modifier.weight(1f),
            onClick = { openExtraProperty(props.whiteBalance) },
        )
        ScpCell(
            label = labels.pictureMode,
            value = formatScpPtpValue(pictureModeDisplayProp),
            propCode = pictureModeDisplayProp?.propCode ?: pictureModeControlProp?.propCode,
            modifier = Modifier.weight(1f),
            onClick = pictureModeControlProp?.let { { openExtraProperty(it) } },
        )
    }

    // Row 3: Drive + Flash + Metering + Quality
    ScpRow {
        ScpCell(
            label = labels.drive,
            value = formatUsbScpDrive(props.driveMode),
            propCode = PtpConstants.OlympusProp.DriveMode,
            modifier = Modifier.weight(1f),
            onClick = { openExtraProperty(props.driveMode) },
        )
        ScpCell(
            label = labels.flash,
            value = formatUsbScpFlash(props.flashMode?.currentValue ?: -1L),
            propCode = PtpConstants.OlympusProp.FlashMode,
            modifier = Modifier.weight(1f),
            onClick = { openExtraProperty(props.flashMode) },
        )
        ScpCell(
            label = labels.metering,
            value = formatUsbScpMetering(props.meteringMode?.currentValue ?: -1L),
            propCode = PtpConstants.OlympusProp.MeteringMode,
            modifier = Modifier.weight(1f),
            onClick = { openExtraProperty(props.meteringMode) },
        )
        ScpCell(
            label = labels.quality,
            value = formatUsbScpImageQuality(props.imageQuality?.currentValue ?: -1L),
            propCode = PtpConstants.OlympusProp.ImageQuality,
            modifier = Modifier.weight(1f),
            onClick = { openExtraProperty(props.imageQuality) },
        )
    }

    // Row 4: Face/Eye + Aspect + Stabilizer + High Res
    ScpRow {
        ScpCell(
            label = labels.faceEye,
            value = faceEyeDisplay,
            propCode = faceEyeProp?.propCode,
            modifier = Modifier.weight(1f),
            onClick = faceEyeProp?.let { { openExtraProperty(it) } },
        )
        ScpCell(
            label = labels.aspect,
            value = formatScpPtpValue(aspectProp),
            propCode = aspectProp?.propCode,
            modifier = Modifier.weight(1f),
            onClick = aspectProp?.let { { openExtraProperty(it) } },
        )
        ScpCell(
            label = labels.stabilizer,
            value = formatScpPtpValue(stabilizerDisplayProp),
            propCode = stabilizerDisplayProp?.propCode ?: stabilizerControlProp?.propCode,
            modifier = Modifier.weight(1f),
            onClick = stabilizerControlProp?.let { { openExtraProperty(it) } },
        )
        ScpCell(
            label = labels.highRes,
            value = formatScpPtpValue(highResDisplayProp),
            propCode = highResDisplayProp?.propCode ?: highResControlProp?.propCode,
            modifier = Modifier.weight(1f),
            onClick = highResControlProp?.let { { openExtraProperty(it) } },
        )
    }

    // Row 5: Subject + Color Space
    ScpRow {
        ScpCell(
            label = labels.subject,
            value = subjectDetectDisplay,
            propCode = subjectDetectProp?.propCode,
            modifier = Modifier.weight(1f),
            onClick = subjectDetectProp?.let { { openExtraProperty(it) } },
        )
        ScpCell(
            label = labels.subjectType,
            value = subjectTypeDisplay,
            propCode = subjectTypeProp?.propCode,
            modifier = Modifier.weight(1f),
            onClick = subjectTypeProp?.let { { openExtraProperty(it) } },
        )
        ScpCell(
            label = labels.colorSpace,
            value = formatScpPtpValue(colorSpaceProp),
            propCode = colorSpaceProp?.propCode,
            modifier = Modifier.weight(1f),
            onClick = colorSpaceProp?.let { { openExtraProperty(it) } },
        )
        Spacer(modifier = Modifier.weight(1f))
    }

    val surfacedPropCodes = setOf(
        UsbScpPictureModeBinding.displayPropCode,
        UsbScpPictureModeBinding.controlPropCode,
        UsbScpHighResBinding.displayPropCode,
        UsbScpHighResBinding.controlPropCode,
        UsbScpFaceEyeBinding.displayPropCode,
        USB_SCP_SUBJECT_DETECT_PROP,
        USB_SCP_SUBJECT_TYPE_PROP,
        UsbScpStabilizerBinding.displayPropCode,
        UsbScpStabilizerBinding.controlPropCode,
        USB_SCP_ASPECT_PROP,
        USB_SCP_COLOR_SPACE_PROP,
        PtpConstants.OlympusProp.DriveMode,
    )
    val extraProps = props.extraProperties
        .filter { !it.isReadOnly && it.propCode !in surfacedPropCodes }
        .take(12)

    extraProps.chunked(4).forEach { chunk ->
        ScpRow {
            repeat(4) { index ->
                val prop = chunk.getOrNull(index)
                if (prop == null) {
                    Spacer(modifier = Modifier.weight(1f))
                } else {
                    ScpCell(
                        label = olympusUsbPropertyLabel(prop.propCode),
                        value = formatScpPtpValue(prop),
                        propCode = prop.propCode,
                        modifier = Modifier.weight(1f),
                        onClick = { openExtraProperty(prop) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxScope.AdaptiveSuperControlPanelOverlay(
    open: Boolean,
    usbCameraProperties: OmCaptureUsbManager.CameraPropertiesSnapshot,
    onOpenChange: (Boolean) -> Unit,
    onOpenUsbExtraProperty: (Int, String) -> Unit,
    readableRotation: Int = 0,
) {
    val quarterTurn = rememberRemoteQuarterTurn(readableRotation)
    val actualLandscapeLayout = rememberLandscapeControlLayout()
    val landscapeLayout = actualLandscapeLayout || quarterTurn
    val rotateWholePanel = !actualLandscapeLayout && quarterTurn
    val columns = if (landscapeLayout) 6 else 4
    val contentScrollState = rememberScrollState()
    val slideProgress by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "adaptiveScpSlide",
    )
    val animatedRotation = rememberAnimatedReadableRotation(readableRotation, label = "adaptiveScpRotation")

    if (slideProgress <= 0f) return

    Box(
        modifier = Modifier
            .matchParentSize()
            .graphicsLayer { alpha = slideProgress }
            .background(Color.Black.copy(alpha = 0.28f * slideProgress))
            .clickable { onOpenChange(false) },
    )

    Column(
        modifier = Modifier
            .then(
                if (landscapeLayout) {
                    Modifier
                        .matchParentSize()
                        .padding(top = 10.dp)
                } else {
                    Modifier.align(Alignment.BottomCenter)
                }
            )
            .then(
                if (landscapeLayout) {
                    Modifier
                        .fillMaxSize()
                } else {
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                }
            )
            .graphicsLayer {
                translationX = -(1f - slideProgress) * 260f
                alpha = slideProgress
                rotationZ = if (rotateWholePanel) animatedRotation else 0f
            }
            .clip(if (landscapeLayout) RoundedCornerShape(0.dp) else RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .background(Color.Black.copy(alpha = if (landscapeLayout) 0.96f else 0.94f))
            .then(
                if (landscapeLayout) {
                    Modifier
                } else {
                    Modifier.border(
                        width = 1.dp,
                        color = Chalk.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                    )
                }
            )
            .verticalScroll(contentScrollState)
            .padding(
                horizontal = if (landscapeLayout) 8.dp else 10.dp,
                vertical = if (landscapeLayout) 8.dp else 10.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(if (landscapeLayout) 6.dp else 8.dp),
    ) {
        val props = usbCameraProperties
        if (props.allProperties().isEmpty()) {
            Text(
                text = stringResource(R.string.remote_loading_camera_properties),
                color = Chalk.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                textAlign = TextAlign.Center,
            )
        } else {
            AdaptiveScpSinglePage(
                props = props,
                columns = columns,
                compactCells = landscapeLayout,
                onOpenChange = onOpenChange,
                onOpenUsbExtraProperty = onOpenUsbExtraProperty,
            )
        }
    }
}

private data class AdaptiveScpGridItem(
    val label: String,
    val value: String,
    val propCode: Int? = null,
    val highlight: Boolean = false,
    val onClick: (() -> Unit)? = null,
)

@Composable
private fun AdaptiveScpSinglePage(
    props: OmCaptureUsbManager.CameraPropertiesSnapshot,
    columns: Int,
    compactCells: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onOpenUsbExtraProperty: (Int, String) -> Unit,
) {
    val pictureModeDisplayProp = props.findScpDisplayProperty(UsbScpPictureModeBinding)
    val pictureModeControlProp = props.findScpControlProperty(UsbScpPictureModeBinding)
    val highResDisplayProp = props.findScpDisplayProperty(UsbScpHighResBinding)
    val highResControlProp = props.findScpControlProperty(UsbScpHighResBinding)
    val faceEyeProp = props.findScpControlProperty(UsbScpFaceEyeBinding)
    val subjectDetectProp = props.findProperty(USB_SCP_SUBJECT_DETECT_PROP)
    val subjectTypeProp = props.findProperty(USB_SCP_SUBJECT_TYPE_PROP)
    val stabilizerDisplayProp = props.findScpDisplayProperty(UsbScpStabilizerBinding)
    val stabilizerControlProp = props.findScpControlProperty(UsbScpStabilizerBinding)
    val aspectProp = props.findProperty(USB_SCP_ASPECT_PROP)
    val colorSpaceProp = props.findProperty(USB_SCP_COLOR_SPACE_PROP)
    val isoPropCode = props.iso?.propCode ?: PtpConstants.OlympusProp.ISOSpeed
    val labels = rememberRemoteScpLabels()

    val faceEyeDisplay = formatScpPtpValue(faceEyeProp)
    val subjectDetectDisplay = formatScpPtpValue(subjectDetectProp)
    val subjectDetectionEnabled = isSubjectDetectionEnabled(subjectDetectProp)
    val subjectTypeDisplay = formatScpPtpValue(subjectTypeProp)

    fun openExtraProperty(property: OmCaptureUsbManager.CameraPropertyState?) {
        property ?: return
        onOpenChange(false)
        onOpenUsbExtraProperty(property.propCode, property.label)
    }

    val primaryItems = buildList {
        add(
        AdaptiveScpGridItem(
            label = labels.mode,
            value = formatUsbScpExposureMode(props),
            propCode = props.exposureMode?.propCode ?: PtpConstants.OlympusProp.ExposureMode,
            highlight = true,
        ),
        )
        add(
        AdaptiveScpGridItem(
            label = "SS",
            value = formatUsbScpShutterSpeed(props.shutterSpeed?.currentValue ?: -1L),
            propCode = PtpConstants.OlympusProp.ShutterSpeed,
            onClick = { openExtraProperty(props.shutterSpeed) },
        ),
        )
        add(
        AdaptiveScpGridItem(
            label = "F",
            value = formatUsbScpAperture(props.aperture?.currentValue ?: -1L),
            propCode = PtpConstants.OlympusProp.Aperture,
            onClick = { openExtraProperty(props.aperture) },
        ),
        )
        add(
        AdaptiveScpGridItem(
            label = "ISO",
            value = formatUsbScpIso(props.iso?.currentValue ?: -1L),
            propCode = isoPropCode,
            onClick = { openExtraProperty(props.iso) },
        ),
        )
        add(
        AdaptiveScpGridItem(
            label = labels.afMode,
            value = formatUsbScpFocusMode(props.focusMode?.currentValue ?: -1L),
            propCode = PtpConstants.OlympusProp.FocusMode,
            onClick = { openExtraProperty(props.focusMode) },
        ),
        )
        add(
        AdaptiveScpGridItem(
            label = "EV",
            value = formatUsbScpExpComp(props.exposureComp?.currentValue ?: -1L),
            propCode = PtpConstants.OlympusProp.ExposureCompensation,
            onClick = props.exposureComp?.let { { openExtraProperty(it) } },
        ),
        )
        add(
        AdaptiveScpGridItem(
            label = "WB",
            value = formatUsbScpWbValue(props.whiteBalance?.currentValue ?: -1L),
            propCode = PtpConstants.OlympusProp.WhiteBalance,
            onClick = { openExtraProperty(props.whiteBalance) },
        ),
        )
        add(
        AdaptiveScpGridItem(
            label = labels.pictureMode,
            value = formatScpPtpValue(pictureModeDisplayProp),
            propCode = pictureModeDisplayProp?.propCode ?: pictureModeControlProp?.propCode,
            onClick = pictureModeControlProp?.let { { openExtraProperty(it) } },
        ),
        )
        add(
        AdaptiveScpGridItem(
            label = labels.drive,
            value = formatUsbScpDrive(props.driveMode),
            propCode = PtpConstants.OlympusProp.DriveMode,
            onClick = { openExtraProperty(props.driveMode) },
        ),
        )
        add(
        AdaptiveScpGridItem(
            label = labels.flash,
            value = formatUsbScpFlash(props.flashMode?.currentValue ?: -1L),
            propCode = PtpConstants.OlympusProp.FlashMode,
            onClick = { openExtraProperty(props.flashMode) },
        ),
        )
        add(
        AdaptiveScpGridItem(
            label = labels.metering,
            value = formatUsbScpMetering(props.meteringMode?.currentValue ?: -1L),
            propCode = PtpConstants.OlympusProp.MeteringMode,
            onClick = { openExtraProperty(props.meteringMode) },
        ),
        )
        add(
        AdaptiveScpGridItem(
            label = labels.quality,
            value = formatUsbScpImageQuality(props.imageQuality?.currentValue ?: -1L),
            propCode = PtpConstants.OlympusProp.ImageQuality,
            onClick = { openExtraProperty(props.imageQuality) },
        ),
        )
        add(
        AdaptiveScpGridItem(
            label = labels.faceEye,
            value = faceEyeDisplay,
            propCode = faceEyeProp?.propCode,
            onClick = faceEyeProp?.let { { openExtraProperty(it) } },
        ),
        )
        add(
        AdaptiveScpGridItem(
            label = labels.aspect,
            value = formatScpPtpValue(aspectProp),
            propCode = aspectProp?.propCode,
            onClick = aspectProp?.let { { openExtraProperty(it) } },
        ),
        )
        add(
        AdaptiveScpGridItem(
            label = labels.stabilizer,
            value = formatScpPtpValue(stabilizerDisplayProp),
            propCode = stabilizerDisplayProp?.propCode ?: stabilizerControlProp?.propCode,
            onClick = stabilizerControlProp?.let { { openExtraProperty(it) } },
        ),
        )
        add(
        AdaptiveScpGridItem(
            label = labels.highRes,
            value = formatScpPtpValue(highResDisplayProp),
            propCode = highResDisplayProp?.propCode ?: highResControlProp?.propCode,
            onClick = highResControlProp?.let { { openExtraProperty(it) } },
        ),
        )
        add(
        AdaptiveScpGridItem(
            label = labels.subject,
            value = subjectDetectDisplay,
            propCode = subjectDetectProp?.propCode,
            onClick = subjectDetectProp?.let { { openExtraProperty(it) } },
        ),
        )
        if (subjectDetectionEnabled) {
            add(
        AdaptiveScpGridItem(
            label = labels.subjectType,
            value = subjectTypeDisplay,
            propCode = subjectTypeProp?.propCode,
            onClick = subjectTypeProp?.let { { openExtraProperty(it) } },
        ),
            )
        }
        add(
        AdaptiveScpGridItem(
            label = labels.colorSpace,
            value = formatScpPtpValue(colorSpaceProp),
            propCode = colorSpaceProp?.propCode,
            onClick = colorSpaceProp?.let { { openExtraProperty(it) } },
        ),
        )
    }

    AdaptiveScpGrid(
        items = primaryItems,
        columns = columns,
        compactCells = compactCells,
    )

    val surfacedPropCodes = setOf(
        UsbScpPictureModeBinding.displayPropCode,
        UsbScpPictureModeBinding.controlPropCode,
        UsbScpHighResBinding.displayPropCode,
        UsbScpHighResBinding.controlPropCode,
        UsbScpFaceEyeBinding.displayPropCode,
        USB_SCP_SUBJECT_DETECT_PROP,
        USB_SCP_SUBJECT_TYPE_PROP,
        UsbScpStabilizerBinding.displayPropCode,
        UsbScpStabilizerBinding.controlPropCode,
        USB_SCP_ASPECT_PROP,
        USB_SCP_COLOR_SPACE_PROP,
        PtpConstants.OlympusProp.DriveMode,
    )
    val extraProps = props.extraProperties
        .filter { !it.isReadOnly && it.propCode !in surfacedPropCodes }
        .take(12)

    if (extraProps.isNotEmpty()) {
        AdaptiveScpGrid(
            items = extraProps.map { prop ->
                AdaptiveScpGridItem(
                    label = olympusUsbPropertyLabel(prop.propCode),
                    value = formatScpPtpValue(prop),
                    propCode = prop.propCode,
                    onClick = { openExtraProperty(prop) },
                )
            },
            columns = columns,
            compactCells = compactCells,
        )
    }
}

@Composable
private fun AdaptiveScpGrid(
    items: List<AdaptiveScpGridItem>,
    columns: Int,
    compactCells: Boolean,
) {
    val safeColumns = columns.coerceAtLeast(1)
    items.chunked(safeColumns).forEach { rowItems ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.04f))
                .border(0.5.dp, Chalk.copy(alpha = 0.25f)),
        ) {
            repeat(safeColumns) { index ->
                val item = rowItems.getOrNull(index)
                if (item == null) {
                    Spacer(modifier = Modifier.weight(1f))
                } else {
                    key(item.propCode ?: "${item.label}:${item.value}:$index") {
                        AdaptiveScpCell(
                            label = item.label,
                            value = item.value,
                            modifier = Modifier.weight(1f),
                            propCode = item.propCode,
                            highlight = item.highlight,
                            compact = compactCells,
                            onClick = item.onClick,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AdaptiveScpCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    propCode: Int? = null,
    highlight: Boolean = false,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val view = LocalView.current
    val visual = remember(propCode, label, value, highlight) {
        resolveScpCellVisual(
            propCode = propCode,
            label = label,
            value = value,
            highlighted = highlight,
        )
    }
    val minHeight = if (compact) 72.dp else 88.dp
    val cellPadding = if (compact) 7.dp else 8.dp
    val verticalGap = if (compact) 4.dp else 6.dp
    val labelFontSize = if (compact) 8.sp else 9.sp
    val badgeFontSize = if (compact) 7.sp else 8.sp
    val isExposureMode = propCode == PtpConstants.OlympusProp.ExposureMode ||
        propCode == PtpConstants.Prop.ExposureProgramMode
    val valueFontSize = when {
        isExposureMode && compact -> 24.sp
        isExposureMode -> 28.sp
        compact -> 12.sp
        else -> 14.sp
    }
    val valueLineHeight = when {
        isExposureMode && compact -> 26.sp
        isExposureMode -> 30.sp
        compact -> 13.sp
        else -> 15.sp
    }

    Column(
        modifier = modifier
            .heightIn(min = minHeight)
            .background(visual.surfaceColor)
            .border(0.5.dp, visual.borderColor)
            .let { base ->
                if (onClick != null) {
                    base.clickable {
                        ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.CLOCK_TICK)
                        onClick()
                    }
                } else {
                    base
                }
            }
            .padding(horizontal = cellPadding, vertical = cellPadding),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(verticalGap),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(visual.accent.copy(alpha = 0.86f)),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = Chalk.copy(alpha = 0.68f),
                style = MaterialTheme.typography.labelSmall,
                fontSize = labelFontSize,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            visual.badge?.let { badge ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(visual.accent.copy(alpha = 0.14f))
                        .border(0.5.dp, visual.accent.copy(alpha = 0.36f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = badge,
                        color = visual.accent,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = badgeFontSize,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }
        Text(
            text = value,
            color = visual.valueColor,
            style = MaterialTheme.typography.labelLarge,
            fontSize = valueFontSize,
            fontWeight = FontWeight.Bold,
            maxLines = if (isExposureMode) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = valueLineHeight,
        )
    }
}

/** Page indicator tabs at the top of SCP */
@Composable
private fun ScpPageIndicator(
    currentPage: Int,
    pageCount: Int,
    pageLabels: List<String>,
    onPageSelected: (Int) -> Unit,
) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val selected = index == currentPage
            Text(
                text = pageLabels.getOrElse(index) { "$index" },
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.CLOCK_TICK)
                        onPageSelected(index)
                    }
                    .background(if (selected) Chalk.copy(alpha = 0.12f) else Color.Transparent)
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                color = if (selected) Chalk else Chalk.copy(alpha = 0.38f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
            if (index < pageCount - 1) {
                Spacer(modifier = Modifier.width(4.dp))
            }
        }
    }
}

// ScpSettingsPage and ScpInfoAndMfPage removed — all SCP properties
// are now handled via ScpSinglePage with USB PTP property dials.

/** Manual focus drive button for SCP */
@Composable
private fun ScpMfButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .graphicsLayer { alpha = if (enabled) 1f else 0.36f }
            .background(Color.White.copy(alpha = if (enabled) 0.12f else 0.06f))
            .border(0.5.dp, Chalk.copy(alpha = if (enabled) 0.28f else 0.1f), RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) Chalk else Chalk.copy(alpha = 0.38f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** SCP grid row — transparent, with thin border lines */
@Composable
private fun ScpRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.04f))
            .border(0.5.dp, Chalk.copy(alpha = 0.25f)),
        content = content,
    )
}

/** Single SCP cell — completely transparent background, tappable */
@Composable
private fun ScpCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    propCode: Int? = null,
    highlight: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val view = LocalView.current
    val visual = remember(propCode, label, value, highlight) {
        resolveScpCellVisual(
            propCode = propCode,
            label = label,
            value = value,
            highlighted = highlight,
        )
    }
    Column(
        modifier = modifier
            .heightIn(min = 88.dp)
            .background(visual.surfaceColor)
            .border(0.5.dp, visual.borderColor)
            .let { m ->
                if (onClick != null) {
                    m.clickable {
                        ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.CLOCK_TICK)
                        onClick()
                    }
                } else m
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(visual.accent.copy(alpha = 0.86f)),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = Chalk.copy(alpha = 0.68f),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            visual.badge?.let { badge ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(visual.accent.copy(alpha = 0.14f))
                        .border(0.5.dp, visual.accent.copy(alpha = 0.36f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = badge,
                        color = visual.accent,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }
    val isExposureMode = propCode == PtpConstants.OlympusProp.ExposureMode ||
        propCode == PtpConstants.Prop.ExposureProgramMode
        Text(
            text = value,
            color = visual.valueColor,
            style = MaterialTheme.typography.labelLarge,
            fontSize = if (isExposureMode) 28.sp else 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = if (isExposureMode) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = if (isExposureMode) 30.sp else 15.sp,
        )
    }
}

private data class ScpCellVisual(
    val accent: Color,
    val badge: String? = null,
    val helper: String? = null,
    val surfaceColor: Color,
    val borderColor: Color,
    val valueColor: Color,
)

private fun resolveScpCellVisual(
    propCode: Int?,
    label: String,
    value: String,
    highlighted: Boolean,
): ScpCellVisual {
    val normalized = value.trim().uppercase(Locale.US)
    if (normalized == "--") {
        return ScpCellVisual(
            accent = Chalk.copy(alpha = 0.36f),
            badge = "N/A",
            helper = "Waiting for camera value",
            surfaceColor = Color.White.copy(alpha = 0.05f),
            borderColor = Chalk.copy(alpha = 0.14f),
            valueColor = Chalk.copy(alpha = 0.64f),
        )
    }
    if (normalized.startsWith("0X")) {
        return ScpCellVisual(
            accent = AppleOrange,
            badge = "RAW",
            helper = "Vendor value",
            surfaceColor = AppleOrange.copy(alpha = 0.06f),
            borderColor = AppleOrange.copy(alpha = 0.18f),
            valueColor = Chalk,
        )
    }

    val (accent, badge, helper) = when (propCode) {
        PtpConstants.OlympusProp.ExposureMode,
        PtpConstants.Prop.ExposureProgramMode -> when {
            normalized in setOf("M") -> Triple(Color(0xFFCCFF00), null, "Manual exposure")
            normalized in setOf("A") -> Triple(AppleGreen, null, "Aperture priority")
            normalized in setOf("S") -> Triple(AppleBlue, null, "Shutter priority")
            normalized in setOf("P") -> Triple(Chalk, null, "Program auto")
            normalized.contains("BULB") || normalized.contains("TIME") || normalized.contains("COMP") ->
                Triple(AppleOrange, "B", "Long exposure")
            normalized.contains("MOVIE") -> Triple(Color(0xFFFF453A), "REC", "Movie mode")
            else -> Triple(Chalk, null, "Exposure mode")
        }

        PtpConstants.OlympusProp.WhiteBalance -> when {
            normalized.contains("AWB") -> Triple(AppleBlue, "AUTO", "Adaptive white balance")
            normalized.contains("DAY") -> Triple(Color(0xFFFFC857), "SUN", "Daylight balance")
            normalized.contains("CLOUD") || normalized.contains("SHADE") -> Triple(AppleOrange, "OUT", "Outdoor balance")
            normalized.contains("PRESET") || normalized.contains("CUSTOM") -> Triple(AppleGreen, "MEM", "Stored balance")
            else -> Triple(AppleBlue, null, "White balance preset")
        }

        PtpConstants.OlympusProp.FocusMode -> when {
            normalized.contains("STAR") -> Triple(AppleBlue, "STAR", "Night-sky focus assist")
            normalized.contains("MF") && normalized.contains("S-AF") -> Triple(AppleGreen, "HYBRID", "AF with manual override")
            normalized == "MF" -> Triple(AppleOrange, "MF", "Manual focus")
            normalized.contains("C-AF") -> Triple(AppleGreen, "CONT", "Continuous autofocus")
            normalized.contains("S-AF") -> Triple(AppleBlue, "AF", "Single autofocus")
            else -> Triple(AppleBlue, null, "Focus mode")
        }

        PtpConstants.OlympusProp.DriveMode -> when {
            normalized.contains("PRO CAPTURE") -> Triple(AppleOrange, "PRO", "Pre-buffer burst")
            normalized.contains("SILENT") -> Triple(AppleBlue, "SILENT", "Electronic shutter burst")
            normalized.contains("SEQ") || normalized.contains("H2") -> Triple(AppleOrange, "BURST", "Continuous shooting")
            normalized.contains("CUSTOM SELF") -> Triple(AppleGreen, "TIMER", "Custom self-timer")
            normalized.contains("SELF TIMER") || normalized.contains("SELF-TIMER") -> Triple(AppleGreen, "TIMER", "Delayed capture")
            normalized.contains("SINGLE") -> Triple(Chalk, "1X", "Single frame")
            else -> Triple(AppleOrange, null, "Drive behavior")
        }

        PtpConstants.OlympusProp.FlashMode -> when {
            normalized.contains("OFF") -> Triple(Chalk, "OFF", "Flash disabled")
            normalized.contains("AUTO") -> Triple(AppleBlue, "AUTO", "Automatic flash")
            normalized.contains("FILL") -> Triple(AppleOrange, "FILL", "Forced flash")
            normalized.contains("EXTERNAL") -> Triple(AppleGreen, "EXT", "External flash")
            else -> Triple(AppleOrange, null, "Flash mode")
        }

        PtpConstants.OlympusProp.MeteringMode -> when {
            normalized.contains("ESP") -> Triple(AppleBlue, "MULTI", "Evaluative metering")
            normalized.contains("CENTER") -> Triple(AppleGreen, "CENTER", "Center-weighted")
            normalized.contains("SPOT") -> Triple(AppleOrange, "SPOT", "Spot meter")
            else -> Triple(AppleBlue, null, "Metering mode")
        }

        PtpConstants.OlympusProp.ImageQuality -> when {
            normalized.contains("RAW") && normalized.contains("+") -> Triple(AppleOrange, "RAW+", "JPEG plus RAW")
            normalized.contains("RAW") -> Triple(AppleOrange, "RAW", "Sensor data only")
            normalized.contains("F") -> Triple(AppleBlue, "FINE", "Highest JPEG quality")
            normalized.contains("N") -> Triple(AppleGreen, "STD", "Compressed JPEG")
            else -> Triple(AppleBlue, null, "Capture format")
        }

        0xD00C -> Triple(AppleBlue, "LOOK", "Picture profile")
        0xD065 -> when {
            normalized.contains("TRIPOD") -> Triple(AppleBlue, "TRI", "Tripod composite")
            normalized.contains("HANDHELD") -> Triple(AppleGreen, "HHHR", "Handheld composite")
            else -> Triple(AppleBlue, null, "High-resolution mode")
        }

        0xD0C4, 0xD0C5 -> when {
            normalized.contains("OFF") -> Triple(Chalk, "OFF", "Face and eye detect off")
            normalized.contains("LEFT") || normalized.contains("RIGHT") -> Triple(AppleGreen, "EYE", "Eye priority autofocus")
            else -> Triple(AppleBlue, "FACE", "Face detection")
        }

        0xD100, 0xD101 -> when {
            normalized.contains("OFF") -> Triple(Chalk, "OFF", "Subject detection disabled")
            else -> Triple(AppleGreen, "AI", "Subject recognition")
        }

        0xD11A -> Triple(AppleBlue, null, "Aspect ratio")

        0xD11B -> when {
            normalized.contains("OFF") -> Triple(Chalk, "OFF", "Stabilizer disabled")
            normalized.contains("S-IS") || normalized.contains("M-IS") -> Triple(AppleGreen, "IBIS", "Image stabilization")
            else -> Triple(AppleGreen, null, "Stabilizer setting")
        }

        0xD11C -> when {
            normalized.contains("ADOBE") -> Triple(AppleOrange, "WIDE", "Adobe RGB gamut")
            else -> Triple(AppleBlue, null, "Color space")
        }

        else -> when {
            normalized.contains("AUTO") -> Triple(AppleBlue, "AUTO", "Camera managed")
            normalized.contains("OFF") -> Triple(Chalk, "OFF", "Disabled")
            normalized.contains("ON") -> Triple(AppleGreen, "ON", "Enabled")
            label.equals("Pic Mode", ignoreCase = true) -> Triple(AppleBlue, "LOOK", "Picture profile")
            else -> Triple(if (highlighted) AppleGreen else AppleBlue, null, null)
        }
    }

    val accentColor = if (highlighted) AppleGreen else accent
    return ScpCellVisual(
        accent = accentColor,
        badge = badge,
        helper = helper,
        surfaceColor = accentColor.copy(alpha = if (highlighted) 0.12f else 0.06f),
        borderColor = accentColor.copy(alpha = if (highlighted) 0.34f else 0.16f),
        valueColor = if (highlighted) Color(0xFFCCFF00) else Chalk,
    )
}

private fun formatLevelDegrees(value: Float): String {
    return if (value >= 0f) {
        String.format(Locale.ENGLISH, "+%.1f°", value)
    } else {
        String.format(Locale.ENGLISH, "%.1f°", value)
    }
}

private fun cardinalFromAzimuth(azimuth: Float): String {
    val directions = listOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )
    val index = ((((azimuth % 360f) + 360f) % 360f + 11.25f) / 22.5f).toInt() % directions.size
    return directions[index]
}

/** Status bar badge at the bottom of SCP */
@Composable
private fun ScpStatusBadge(
    text: String,
    highlight: Boolean = false,
    onClick: (() -> Unit)?,
) {
    val view = LocalView.current
    Text(
        text = text,
        color = if (highlight) Color(0xFFCCFF00) else Chalk,
        style = MaterialTheme.typography.titleMedium,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .let { m ->
                if (onClick != null) {
                    m.clickable {
                        ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.CLOCK_TICK)
                        onClick()
                    }
                } else m
            }
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/**
 * Inline USB property value picker — shown when an SCP cell is tapped.
 * Displays the allowed values for the expanded property as horizontal chips.
 */
@Composable
private fun ScpInlineUsbPicker(
    expandedPropCode: Int,
    propCodes: List<Int>,
    usbCameraProperties: OmCaptureUsbManager.CameraPropertiesSnapshot,
    onSetUsbProperty: (Int, Long) -> Unit,
) {
    if (expandedPropCode !in propCodes) return
    val prop = usbCameraProperties.allProperties().firstOrNull { it.propCode == expandedPropCode }
        ?: return
    if (prop.allowedValues.isEmpty() && prop.isReadOnly) return
    val view = LocalView.current
    val values = enumerateOlympusUsbPropertyValues(prop)
    val listState = rememberLazyListState()
    // Scroll to current value on first appearance
    LaunchedEffect(expandedPropCode) {
        val idx = values.indexOf(prop.currentValue)
        if (idx >= 0) listState.scrollToItem(idx.coerceAtLeast(0))
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        itemsIndexed(values) { _, value ->
            val selected = value == prop.currentValue
            val label = formatScpPtpValue(prop, value)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) AppleBlue.copy(alpha = 0.22f)
                        else Chalk.copy(alpha = 0.06f),
                    )
                    .border(
                        0.5.dp,
                        if (selected) AppleBlue.copy(alpha = 0.5f) else Chalk.copy(alpha = 0.18f),
                        RoundedCornerShape(10.dp),
                    )
                    .clickable(enabled = !prop.isReadOnly) {
                        ViewCompat.performHapticFeedback(
                            view,
                            HapticFeedbackConstantsCompat.CLOCK_TICK,
                        )
                        onSetUsbProperty(expandedPropCode, value)
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (selected) AppleBlue else Chalk.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Format a raw PTP property value for display in the SCP picker. */
private fun formatScpPtpValue(
    propCode: Int,
    value: Long,
    options: List<Long> = emptyList(),
): String = formatOlympusUsbPropertyValue(propCode, value, options)

private fun formatScpPtpValue(
    property: OmCaptureUsbManager.CameraPropertyState?,
    value: Long = property?.currentValue ?: 0L,
): String {
    if (property == null) return "--"
    return formatScpPtpValue(
        propCode = property.propCode,
        value = value,
        options = enumerateOlympusUsbPropertyValues(property),
    )
}

// SCP value formatters
private fun formatUsbScpIso(value: Long): String = if (value < 0) "--" else formatOlympusIso(value)

private fun formatUsbScpWbValue(value: Long): String = if (value < 0) "--" else formatOlympusWhiteBalance(value)

private fun formatUsbScpExpComp(value: Long): String = if (value < 0) "--" else formatOlympusExposureComp(value)

private fun formatUsbScpFlash(value: Long): String = if (value < 0) "--" else formatOlympusFlashMode(value)

private fun formatUsbScpFocusMode(value: Long): String = if (value < 0) "--" else formatOlympusFocusMode(value)

private fun formatUsbScpMetering(value: Long): String = if (value < 0) "--" else formatOlympusMetering(value)

private fun formatUsbScpDrive(
    property: OmCaptureUsbManager.CameraPropertyState?,
): String = property?.let {
    formatOlympusDriveMode(it.currentValue, enumerateOlympusUsbPropertyValues(it))
} ?: "--"

private fun formatUsbScpImageQuality(value: Long): String = if (value < 0) "--" else formatOlympusImageQuality(value)

private fun formatUsbScpExposureMode(
    props: OmCaptureUsbManager.CameraPropertiesSnapshot,
): String {
    val modeProperty = props.exposureMode
    val reportedMode = modeProperty?.let { olympusExposureModeFromRaw(it.propCode, it.currentValue) }
    val shutterDisplay = formatOlympusShutterSpeed(props.shutterSpeed?.currentValue ?: -1L)
    return when (shutterDisplay) {
        "Bulb" -> "Bulb"
        "Time" -> "Time"
        "Composite" -> "Composite"
        else -> {
            if (modeProperty != null) {
                return formatOlympusExposureMode(modeProperty.propCode, modeProperty.currentValue)
            }
            if (props.aperture != null || props.shutterSpeed != null || reportedMode != null) {
                return "P"
            }
            "--"
        }
    }
}

private fun formatUsbScpShutterSpeed(value: Long): String = if (value < 0) "--" else formatOlympusShutterSpeed(value)

private fun formatUsbScpAperture(value: Long): String = if (value < 0) "--" else formatOlympusAperture(value)

private fun formatScpIso(value: Long): String {
    return if (value < 0) "--" else formatOlympusIso(value)
}

private fun formatScpWbLabel(value: Long): String = if (value < 0) "WB" else when (value.toInt()) {
    0 -> "AWB"; 16 -> "☀ WB"; 17 -> "⛅ WB"; 18 -> "☁ WB"
    20 -> "💡 WB"; 35 -> "FL WB"; 64 -> "⚡ WB"
    256, 257, 258, 259 -> "CWB"; 512 -> "K WB"
    else -> "WB"
}

private fun formatScpWbValue(value: Long): String = if (value < 0) "--" else formatOlympusWhiteBalance(value)

private fun formatScpExpComp(value: Long): String {
    if (value < 0) return "--"
    val v = value.toInt().toShort().toInt()
    if (v == 0) return "±0"
    val ev = v / 10.0
    return if (ev > 0) "+${"%.1f".format(ev)}" else "${"%.1f".format(ev)}"
}

private fun formatScpFlash(value: Long): String = if (value < 0) "--" else when (value.toInt()) {
    0 -> "Off"; 1 -> "Auto"; 2 -> "On"; 3 -> "Red-eye"
    4 -> "Slow"; 5 -> "Fill"; else -> "0x${value.toString(16)}"
}

private fun formatScpFocusMode(value: Long): String = if (value < 0) "--" else when (value.toInt()) {
    0 -> "S-AF"; 1 -> "C-AF"; 2 -> "MF"; 3 -> "S-AF+MF"
    4 -> "C-AF+TR"; else -> "0x${value.toString(16)}"
}

private fun formatScpMetering(value: Long): String = if (value < 0) "--" else when (value.toInt()) {
    2 -> "Center"; 3 -> "Spot"; 4 -> "ESP"; 5 -> "Highlight"
    else -> "0x${value.toString(16)}"
}

private fun formatScpDrive(value: Long): String = if (value < 0) "--" else when (value.toInt()) {
    0 -> "Single"; 1 -> "Burst"; 2 -> "Timer"; 3 -> "T.Burst"
    4 -> "ProCap"; 5 -> "Silent C"; 6 -> "Silent"; 7 -> "S.Timer"
    else -> "0x${value.toString(16)}"
}

private fun formatScpImageQuality(value: Long): String = if (value < 0) "--" else when (value.toInt()) {
    1 -> "RAW"; 2 -> "JPEG"; 3 -> "RAW+J"
    else -> "0x${value.toString(16)}"
}

private fun formatScpExposureMode(value: Long): String = if (value < 0) "--" else when (value.toInt()) {
    1 -> "P"; 2 -> "A"; 3 -> "S"; 4 -> "M"; 5 -> "B"; 6 -> "Video"
    else -> "0x${value.toString(16)}"
}

private fun formatScpShutterSpeed(value: Long): String {
    if (value < 0) return "--"
    val v = value.toInt()
    if (v == 0) return "Auto"
    return if (v >= 10) "1/$v" else "${v}\""
}

private fun formatScpAperture(value: Long): String {
    if (value < 0) return "--"
    val v = value.toInt()
    if (v == 0) return "Auto"
    val fNum = v / 10.0
    return if (fNum == fNum.toLong().toDouble()) "F${fNum.toLong()}" else "F${"%.1f".format(fNum)}"
}

private fun enumerateUsbDialRawValues(
    prop: OmCaptureUsbManager.CameraPropertyState,
): List<Long> {
    return dev.dblink.core.usb.enumerateOlympusUsbPropertyValues(prop)
}

private fun resolvePreviewRect(
    containerWidth: Float,
    containerHeight: Float,
    imageAspectRatio: Float,
): Rect {
    val safeContainerWidth = containerWidth.coerceAtLeast(1f)
    val safeContainerHeight = containerHeight.coerceAtLeast(1f)
    val safeAspectRatio = imageAspectRatio.coerceAtLeast(0.1f)
    val containerAspectRatio = safeContainerWidth / safeContainerHeight
    return if (containerAspectRatio > safeAspectRatio) {
        val previewHeight = safeContainerHeight
        val previewWidth = previewHeight * safeAspectRatio
        val left = (safeContainerWidth - previewWidth) / 2f
        Rect(left, 0f, left + previewWidth, previewHeight)
    } else {
        val previewWidth = safeContainerWidth
        val previewHeight = previewWidth / safeAspectRatio
        // Keep the preview anchored the same way in portrait and landscape.
        // Rotation should change readability only, not move the screen layout.
        val top = ((safeContainerHeight - previewHeight) * 0.45f).coerceAtLeast(0f)
        Rect(0f, top, previewWidth, top + previewHeight)
    }
}

@Composable
private fun RemoteSelectorRow(
    remoteRuntime: RemoteRuntimeState,
    onSetActivePicker: (ActivePropertyPicker) -> Unit,
    readableRotation: Int,
    usbTetherConnected: Boolean = false,
    usbCameraProperties: OmCaptureUsbManager.CameraPropertiesSnapshot = OmCaptureUsbManager.CameraPropertiesSnapshot(),
) {
    val shutterEnabled = remoteRuntime.shutterSpeed.enabled
    val apertureEnabled = remoteRuntime.aperture.enabled
    val shutterAutoDisplayed = !shutterEnabled || isCameraReportedAutoValue("shutspeedvalue", remoteRuntime.shutterSpeed)
    val apertureAutoDisplayed = !apertureEnabled || isCameraReportedAutoValue("focalvalue", remoteRuntime.aperture)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectorButton(
                modifier = Modifier.weight(1f),
                label = "WB",
                value = PropertyFormatter.formatForDisplay("wbvalue", remoteRuntime.whiteBalance.currentValue).ifBlank { "AWB" },
                selected = remoteRuntime.activePicker == ActivePropertyPicker.WhiteBalance,
                readableRotation = readableRotation,
                onClick = {
                    onSetActivePicker(
                        if (remoteRuntime.activePicker == ActivePropertyPicker.WhiteBalance) ActivePropertyPicker.None
                        else ActivePropertyPicker.WhiteBalance,
                    )
                },
            )
            SelectorButton(
                modifier = Modifier.weight(1f),
                label = "ISO",
                value = PropertyFormatter.formatIsoDisplay(
                    remoteRuntime.iso.currentValue,
                    remoteRuntime.iso.autoActive,
                ).ifBlank { "Auto" },
                highlighted = remoteRuntime.iso.autoActive ||
                    PropertyFormatter.formatIsoDisplay(
                        remoteRuntime.iso.currentValue,
                        remoteRuntime.iso.autoActive,
                    ).equals("Auto", ignoreCase = true),
                selected = remoteRuntime.activePicker == ActivePropertyPicker.Iso,
                readableRotation = readableRotation,
                onClick = {
                    onSetActivePicker(
                        if (remoteRuntime.activePicker == ActivePropertyPicker.Iso) ActivePropertyPicker.None
                        else ActivePropertyPicker.Iso,
                    )
                },
            )
            SelectorButton(
                modifier = Modifier.weight(1f),
                label = "SS",
                value = if (shutterAutoDisplayed) {
                    "Auto"
                } else {
                    PropertyFormatter.formatForDisplay("shutspeedvalue", remoteRuntime.shutterSpeed.currentValue)
                        .ifBlank { remoteRuntime.shutterSpeed.currentValue }
                },
                highlighted = shutterAutoDisplayed,
                selected = remoteRuntime.activePicker == ActivePropertyPicker.ShutterSpeed,
                enabled = shutterEnabled,
                allowWhenDisabled = true,
                readableRotation = readableRotation,
                onClick = {
                    onSetActivePicker(
                        if (remoteRuntime.activePicker == ActivePropertyPicker.ShutterSpeed) ActivePropertyPicker.None
                        else ActivePropertyPicker.ShutterSpeed,
                    )
                },
            )
            SelectorButton(
                modifier = Modifier.weight(1f),
                label = "F",
                value = if (apertureAutoDisplayed) {
                    "Auto"
                } else {
                    PropertyFormatter.formatForDisplay("focalvalue", remoteRuntime.aperture.currentValue)
                        .ifBlank { remoteRuntime.aperture.currentValue }
                },
                highlighted = apertureAutoDisplayed,
                selected = remoteRuntime.activePicker == ActivePropertyPicker.Aperture,
                enabled = apertureEnabled,
                allowWhenDisabled = true,
                readableRotation = readableRotation,
                onClick = {
                    onSetActivePicker(
                        if (remoteRuntime.activePicker == ActivePropertyPicker.Aperture) ActivePropertyPicker.None
                        else ActivePropertyPicker.Aperture,
                    )
                },
            )
            DriveSelectorButton(
                modifier = Modifier.weight(1f),
                summary = if (usbTetherConnected) {
                    formatUsbScpDrive(usbCameraProperties.driveMode)
                } else {
                    currentDriveSummary(remoteRuntime)
                },
                shootingMode = remoteRuntime.shootingMode,
                driveMode = remoteRuntime.driveMode,
                selected = remoteRuntime.activePicker == ActivePropertyPicker.DriveSettings,
                readableRotation = readableRotation,
                onClick = {
                    onSetActivePicker(
                        if (remoteRuntime.activePicker == ActivePropertyPicker.DriveSettings) ActivePropertyPicker.None
                        else ActivePropertyPicker.DriveSettings,
                    )
                },
            )
        }
        // AF, PIC, HIRES, METER, QUAL removed — controlled via SCP instead
    }
}

@Composable
private fun RemoteControlPanel(
    runtime: RemoteRuntimeState,
    tetheredCaptureAvailable: Boolean,
    omCaptureUsb: OmCaptureUsbUiState,
    evValues: List<String>,
    evCurrentValue: String,
    latestTransferThumbnail: Bitmap?,
    latestTransferFileName: String?,
    libraryBusy: Boolean,
    libraryStatus: String,
    tetherSaveTarget: TetherSaveTarget,
    onToggleLiveView: () -> Unit,
    onExposureChanged: (String) -> Unit,
    onSetCameraExposureMode: (CameraExposureMode) -> Unit,
    onSetModePickerSurface: (ModePickerSurface) -> Unit,
    onSetPropertyValue: (ActivePropertyPicker, String, Boolean) -> Unit,
    onSetShootingMode: (RemoteShootingMode) -> Unit,
    onSetDriveMode: (DriveMode) -> Unit,
    onSetTimerMode: (TimerMode) -> Unit,
    onSetTimerDelay: (Int) -> Unit,
    onSetIntervalSeconds: (Int) -> Unit,
    onSetIntervalCount: (Int) -> Unit,
    onRefreshOmCaptureUsb: () -> Unit,
    onCaptureOmCaptureUsb: () -> Unit,
    onImportLatestOmCaptureUsb: () -> Unit,
    onClearOmCaptureUsb: () -> Unit,
    onOpenOmCapture: () -> Unit,
    deepSkyState: DeepSkyLiveStackUiState,
    onOpenDeepSkyLiveStack: () -> Unit,
    onStartDeepSkySession: () -> Unit,
    onStopDeepSkySession: () -> Unit,
    onResetDeepSkySession: () -> Unit,
    onSelectDeepSkyPreset: (DeepSkyPresetId?) -> Unit,
    onSetDeepSkyManualFocalLength: (Float?) -> Unit,
    showSkyOverlay: Boolean,
    onSetShowSkyOverlay: (Boolean) -> Unit,
    skyOverlayInfo: RemoteSkyOverlayInfo?,
    onOpenLibrary: () -> Unit,
    onSetUsbProperty: (Int, Long) -> Unit,
    usbCameraProperties: OmCaptureUsbManager.CameraPropertiesSnapshot,
    usbTetherConnected: Boolean,
    activeUsbExtraPropCode: Int?,
    activeUsbExtraPropLabel: String,
    reverseDialMap: Map<String, Boolean>,
    readableRotation: Int,
) {
    val quarterTurn = rememberRemoteQuarterTurn(readableRotation)
    val landscapeControlLayout = rememberLandscapeControlLayout()
    val usbTetherSurface =
        runtime.modePickerSurface == ModePickerSurface.Tether ||
            runtime.modePickerSurface == ModePickerSurface.TetherRetry ||
            runtime.modePickerSurface == ModePickerSurface.DeepSky
    val compactDialLayout = quarterTurn || landscapeControlLayout
    val panelHeight = if (compactDialLayout) 132.dp else 100.dp
    // Content-driven height: no space is reserved for absent content.
    // Each picker type determines its own height requirement.
    when (runtime.activePicker) {
        ActivePropertyPicker.DriveSettings -> {
            if (usbTetherConnected) {
                // USB mode: chip buttons for all camera-reported drive modes
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(),
                ) {
                    UsbDriveSettingsPanel(
                        driveProp = usbCameraProperties.driveMode,
                        remoteRuntime = runtime,
                        onSetShootingMode = onSetShootingMode,
                        onSetDriveMode = onSetDriveMode,
                        onSetTimerMode = onSetTimerMode,
                        onSetTimerDelay = onSetTimerDelay,
                        onSetIntervalSeconds = onSetIntervalSeconds,
                        onSetIntervalCount = onSetIntervalCount,
                        onSetUsbProperty = onSetUsbProperty,
                    )
                }
            } else {
                // WiFi mode: simplified drive settings panel
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(),
                ) {
                    DriveSettingsPanel(
                        remoteRuntime = runtime,
                        onSetShootingMode = onSetShootingMode,
                        onSetDriveMode = onSetDriveMode,
                        onSetTimerMode = onSetTimerMode,
                        onSetTimerDelay = onSetTimerDelay,
                        onSetIntervalSeconds = onSetIntervalSeconds,
                        onSetIntervalCount = onSetIntervalCount,
                    )
                }
            }
        }
        ActivePropertyPicker.ExposureMode -> {
            val modeDialValues = buildModeDialValues(
                takeMode = runtime.takeMode,
                fallbackMode = runtime.exposureMode,
                includeTetherOption = tetheredCaptureAvailable,
                includeTetherRetryOption = usbTetherSurface && (usbTetherConnected || omCaptureUsb.canRetry),
                includeDeepSkyOption = usbTetherConnected && runtime.modePickerSurface == ModePickerSurface.DeepSky,
                preferFullModeFallback = usbTetherSurface,
                usbTetherActionOnly = usbTetherSurface,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(panelHeight),
            ) {
                DialSection(
                    label = "MODE",
                    values = modeDialValues,
                    propName = "takemode",
                    reverseDial = false,
                    readableRotation = readableRotation,
                ) { selectedMode, _ ->
                    when {
                        selectedMode.equals(TETHER_MODE_DIAL_VALUE, ignoreCase = true) -> {
                            onSetModePickerSurface(ModePickerSurface.Tether)
                        }
                        selectedMode.equals(TETHER_RETRY_MODE_DIAL_VALUE, ignoreCase = true) -> {
                            onSetModePickerSurface(ModePickerSurface.TetherRetry)
                        }
                        selectedMode.equals(DEEP_SKY_MODE_DIAL_VALUE, ignoreCase = true) -> {
                            onSetModePickerSurface(ModePickerSurface.DeepSky)
                        }
                        usbTetherSurface &&
                            selectedMode.equals(modeDialValues.currentValue, ignoreCase = true) -> Unit
                        else -> {
                            CameraExposureMode.entries
                                .firstOrNull { it.label.equals(selectedMode, ignoreCase = true) }
                                ?.let(onSetCameraExposureMode)
                        }
                    }
                }
            }
        }
        ActivePropertyPicker.None -> {
            if (runtime.modePickerSurface == ModePickerSurface.DeepSky) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (compactDialLayout) 286.dp else 238.dp),
                ) {
                    DeepSkyInlinePanel(
                        state = deepSkyState,
                        readableRotation = readableRotation,
                        onStartSession = onStartDeepSkySession,
                        onStopSession = onStopDeepSkySession,
                        onResetSession = onResetDeepSkySession,
                        onSelectPreset = onSelectDeepSkyPreset,
                        onSetManualFocalLength = onSetDeepSkyManualFocalLength,
                        showSkyOverlay = showSkyOverlay,
                        onSetShowSkyOverlay = onSetShowSkyOverlay,
                        skyInfo = skyOverlayInfo,
                    )
                }
                return
            }
            if (activeUsbExtraPropCode != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(panelHeight),
                ) {
                    UsbPropertyDialSection(
                        label = activeUsbExtraPropLabel.ifBlank {
                            olympusUsbPropertyLabel(activeUsbExtraPropCode)
                        },
                        prop = usbCameraProperties.findProperty(activeUsbExtraPropCode),
                        propCode = activeUsbExtraPropCode,
                        readableRotation = readableRotation,
                        onSetUsbProperty = onSetUsbProperty,
                    )
                }
                return
            }
            // Show EV dial in P/A/S modes. Hide in M/B modes unless ISO is Auto
            // and the camera specifically reports EV values.
            // When properties aren't loaded yet, show nothing (avoid flicker).
            val isIsoAuto = runtime.iso.autoActive ||
                PropertyFormatter.isAutoValue("isospeedvalue", runtime.iso.currentValue)
            // Only count camera-reported EV values, not the hardcoded defaults
            val hasCameraEvValues = runtime.exposureCompensationValues.availableValues.isNotEmpty()
            val showEvDial = when {
                !runtime.propertiesLoaded -> false // Don't show before properties load
                runtime.exposureMode in setOf(CameraExposureMode.M, CameraExposureMode.B) -> {
                    // M/B: only show EV if ISO is Auto AND camera reports EV values
                    isIsoAuto && hasCameraEvValues
                }
                else -> true // P, A, S: always show
            }
            D.layout("EV_VISIBILITY: showEvDial=$showEvDial mode=${runtime.exposureMode} " +
                "activePicker=${runtime.activePicker} propertiesLoaded=${runtime.propertiesLoaded} " +
                "hasCameraEvValues=$hasCameraEvValues isIsoAuto=$isIsoAuto iso=${runtime.iso.currentValue}")
            if (showEvDial) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(panelHeight),
                ) {
                    ExposureScalePanel(
                        values = evValues,
                        currentValue = evCurrentValue,
                        onExposureChanged = onExposureChanged,
                        readableRotation = readableRotation,
                    )
                }
            }
            // No placeholder when EV is hidden — 0 height
        }
        ActivePropertyPicker.Aperture -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(panelHeight),
            ) {
                DialSection(
                    label = stringResource(R.string.remote_dial_aperture),
                    values = runtime.aperture,
                    propName = "focalvalue",
                    reverseDial = reverseDialMap["focalvalue"] == true,
                    readableRotation = readableRotation,
                ) { value, closePicker -> onSetPropertyValue(ActivePropertyPicker.Aperture, value, closePicker) }
            }
        }
        ActivePropertyPicker.ShutterSpeed -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(panelHeight),
            ) {
                DialSection(
                    label = stringResource(R.string.remote_dial_shutter_speed),
                    values = runtime.shutterSpeed,
                    propName = "shutspeedvalue",
                    reverseDial = reverseDialMap["shutspeedvalue"] == true,
                    readableRotation = readableRotation,
                ) { value, closePicker -> onSetPropertyValue(ActivePropertyPicker.ShutterSpeed, value, closePicker) }
            }
        }
        ActivePropertyPicker.Iso -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(panelHeight),
            ) {
                DialSection(
                    label = stringResource(R.string.remote_dial_iso),
                    values = runtime.iso,
                    propName = "isospeedvalue",
                    reverseDial = reverseDialMap["isospeedvalue"] == true,
                    readableRotation = readableRotation,
                ) { value, closePicker -> onSetPropertyValue(ActivePropertyPicker.Iso, value, closePicker) }
            }
        }
        ActivePropertyPicker.WhiteBalance -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(panelHeight),
            ) {
                DialSection(
                    label = stringResource(R.string.remote_dial_white_balance),
                    values = runtime.whiteBalance,
                    propName = "wbvalue",
                    reverseDial = reverseDialMap["wbvalue"] == true,
                    readableRotation = readableRotation,
                ) { value, closePicker -> onSetPropertyValue(ActivePropertyPicker.WhiteBalance, value, closePicker) }
            }
        }
        ActivePropertyPicker.FocusMode -> {
            Box(modifier = Modifier.fillMaxWidth().height(panelHeight)) {
                UsbPropertyDialSection(
                    label = stringResource(R.string.remote_scp_label_af_mode),
                    prop = usbCameraProperties.focusMode,
                    propCode = PtpConstants.OlympusProp.FocusMode,
                    readableRotation = readableRotation,
                    onSetUsbProperty = onSetUsbProperty,
                )
            }
        }
        ActivePropertyPicker.Metering -> {
            Box(modifier = Modifier.fillMaxWidth().height(panelHeight)) {
                UsbPropertyDialSection(
                    label = stringResource(R.string.remote_scp_label_metering),
                    prop = usbCameraProperties.meteringMode,
                    propCode = PtpConstants.OlympusProp.MeteringMode,
                    readableRotation = readableRotation,
                    onSetUsbProperty = onSetUsbProperty,
                )
            }
        }
        ActivePropertyPicker.Flash -> {
            Box(modifier = Modifier.fillMaxWidth().height(panelHeight)) {
                UsbPropertyDialSection(
                    label = stringResource(R.string.remote_scp_label_flash),
                    prop = usbCameraProperties.flashMode,
                    propCode = PtpConstants.OlympusProp.FlashMode,
                    readableRotation = readableRotation,
                    onSetUsbProperty = onSetUsbProperty,
                )
            }
        }
        ActivePropertyPicker.ImageQuality -> {
            Box(modifier = Modifier.fillMaxWidth().height(panelHeight)) {
                UsbPropertyDialSection(
                    label = stringResource(R.string.remote_scp_label_image_quality),
                    prop = usbCameraProperties.imageQuality,
                    propCode = PtpConstants.OlympusProp.ImageQuality,
                    readableRotation = readableRotation,
                    onSetUsbProperty = onSetUsbProperty,
                )
            }
        }
        ActivePropertyPicker.UsbDriveMode -> Unit // handled via DriveSettings
        ActivePropertyPicker.PictureMode -> {
            Box(modifier = Modifier.fillMaxWidth().height(panelHeight)) {
                UsbPropertyDialSection(
                    label = stringResource(R.string.remote_scp_label_picture_mode),
                    prop = usbCameraProperties.findProperty(0xD00C),
                    propCode = 0xD00C,
                    readableRotation = readableRotation,
                    onSetUsbProperty = onSetUsbProperty,
                )
            }
        }
        ActivePropertyPicker.HighRes -> {
            Box(modifier = Modifier.fillMaxWidth().height(panelHeight)) {
                UsbPropertyDialSection(
                    label = stringResource(R.string.remote_scp_label_high_res_shot),
                    prop = usbCameraProperties.findProperty(0xD065),
                    propCode = 0xD065,
                    readableRotation = readableRotation,
                    onSetUsbProperty = onSetUsbProperty,
                )
            }
        }
    }
}

@Composable
private fun DeepSkyInlinePanel(
    state: DeepSkyLiveStackUiState,
    readableRotation: Int,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
    onResetSession: () -> Unit,
    onSelectPreset: (DeepSkyPresetId?) -> Unit,
    onSetManualFocalLength: (Float?) -> Unit,
    showSkyOverlay: Boolean,
    onSetShowSkyOverlay: (Boolean) -> Unit,
    skyInfo: RemoteSkyOverlayInfo?,
) {
    val animatedRotation = rememberAnimatedReadableRotation(readableRotation, label = "deepSkyInlineRotation")
    val manualDialCurrent = state.manualFocalLengthMm ?: state.autoFocalLengthMm ?: state.activeFocalLengthMm ?: 50f
    val showManualDial = state.usingManualFocalLength || state.autoFocalLengthMm == null
    val manualFocalLengthLabel = stringResource(R.string.remote_deep_sky_manual)
    val manualDialValues = remember(
        state.manualFocalLengthMm,
        state.autoFocalLengthMm,
        state.activeFocalLengthMm,
    ) {
        buildDeepSkyFocalLengthPropertyValues(manualDialCurrent)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .border(1.dp, Chalk.copy(alpha = 0.16f), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .graphicsLayer { rotationZ = animatedRotation },
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.remote_deep_sky_title),
                    color = Chalk,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = state.statusLabel,
                    color = Chalk.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusBadge(
                label = if (state.isSessionActive) {
                    stringResource(R.string.remote_deep_sky_stacking)
                } else {
                    stringResource(R.string.remote_deep_sky_ready)
                },
                color = if (state.isSessionActive) AppleGreen else AppleBlue,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        )
        {
            state.autoFocalLengthMm?.let { autoFocalLength ->
                TetheredActionChip(
                    label = stringResource(
                        R.string.remote_deep_sky_camera_focal_length,
                        formatDeepSkyFocalLengthMm(autoFocalLength),
                    ),
                    accent = AppleBlue,
                    selected = !state.usingManualFocalLength,
                    modifier = Modifier.weight(1f),
                    onClick = { onSetManualFocalLength(null) },
                )
            }
            TetheredActionChip(
                label = buildString {
                    append(manualFocalLengthLabel)
                    if (state.manualFocalLengthMm != null) {
                        append(" ")
                        append(formatDeepSkyFocalLengthMm(state.manualFocalLengthMm))
                    }
                },
                accent = AppleOrange,
                selected = state.usingManualFocalLength,
                modifier = Modifier.weight(1f),
                onClick = {
                    onSetManualFocalLength(
                        state.manualFocalLengthMm ?: state.autoFocalLengthMm ?: 50f,
                    )
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DeepSkyInfoTile(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.remote_deep_sky_preset),
                value = state.selectedPreset?.displayName ?: "--",
            )
            DeepSkyInfoTile(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.remote_deep_sky_focal),
                value = state.activeFocalLengthMm?.let(::formatDeepSkyFocalLengthMm) ?: "--",
            )
            DeepSkyInfoTile(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.remote_deep_sky_guide),
                value = state.stackFrameLabel,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TetheredActionChip(
                label = if (showSkyOverlay) {
                    stringResource(R.string.remote_deep_sky_overlay_on)
                } else {
                    stringResource(R.string.remote_deep_sky_overlay_off)
                },
                accent = AppleBlue,
                selected = showSkyOverlay,
                modifier = Modifier.weight(1f),
                onClick = { onSetShowSkyOverlay(!showSkyOverlay) },
            )
        }
        Text(
            text = "${state.focalLengthSourceLabel} · ${state.fieldOfViewLabel}",
            color = Chalk.copy(alpha = 0.64f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        skyInfo?.let {
            Text(
                text = "${it.phaseLabel} · ${it.timeLabel} · ${it.locationLabel}",
                color = Chalk.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showManualDial) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (state.autoFocalLengthMm == null) {
                        stringResource(R.string.remote_deep_sky_manual_hint_primary)
                    } else {
                        stringResource(R.string.remote_deep_sky_manual_hint_secondary)
                    },
                    color = Chalk.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.labelSmall,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(76.dp),
                ) {
                    PropertyDial(
                        prop = manualDialValues,
                        propName = DEEP_SKY_FOCAL_LENGTH_PROP,
                        reverseDial = false,
                        readableRotation = readableRotation,
                    ) { value, _ ->
                        onSetManualFocalLength(parseDeepSkyFocalLengthMm(value))
                    }
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(state.availablePresets) { _, preset ->
                val selected = state.manualPresetOverride == preset.id || state.selectedPreset?.id == preset.id
                TetheredActionChip(
                    label = preset.displayName,
                    accent = AppleBlue,
                    selected = selected,
                    onClick = { onSelectPreset(preset.id) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TetheredActionChip(
                label = if (state.isSessionActive) {
                    stringResource(R.string.remote_deep_sky_running)
                } else {
                    stringResource(R.string.remote_deep_sky_start)
                },
                accent = AppleGreen,
                selected = state.isSessionActive,
                onClick = onStartSession,
            )
            TetheredActionChip(
                label = stringResource(R.string.remote_deep_sky_stop),
                accent = AppleOrange,
                selected = false,
                enabled = state.isSessionActive,
                onClick = onStopSession,
            )
            TetheredActionChip(
                label = stringResource(R.string.remote_deep_sky_reset),
                accent = AppleBlue,
                selected = false,
                onClick = onResetSession,
            )
        }
        Text(
            text = state.recommendationReason,
            color = Chalk.copy(alpha = 0.56f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DeepSkyInfoTile(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Graphite.copy(alpha = 0.72f))
            .border(1.dp, Chalk.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = title.uppercase(),
            color = Chalk.copy(alpha = 0.44f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        Text(
            text = value,
            color = Chalk,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DeepSkyStackFrameOverlay(
    state: DeepSkyLiveStackUiState,
    previewAspectRatio: Float,
    readableRotation: Int = 0,
) {
    val rotated = rememberRemoteQuarterTurn(readableRotation)
    Canvas(modifier = Modifier.fillMaxSize()) {
        val effectiveAspect = if (rotated) 1f / previewAspectRatio else previewAspectRatio
        val previewRect = resolvePreviewRect(
            containerWidth = size.width,
            containerHeight = size.height,
            imageAspectRatio = effectiveAspect,
        )
        val frameWidth = previewRect.width * state.stackFrameWidthFraction.coerceIn(0.16f, 0.9f)
        val frameHeight = previewRect.height * state.stackFrameHeightFraction.coerceIn(0.16f, 0.9f)
        val frameLeft = previewRect.left + ((previewRect.width - frameWidth) / 2f)
        val frameTop = previewRect.top + ((previewRect.height - frameHeight) / 2f)
        val overlayColor = AppleBlue.copy(alpha = 0.74f)
        val centerX = previewRect.center.x
        val centerY = previewRect.center.y
        val crosshair = minOf(frameWidth, frameHeight) * 0.08f

        drawRoundRect(
            color = Color.Black.copy(alpha = 0.24f),
            topLeft = Offset(frameLeft - 2f, frameTop - 2f),
            size = Size(frameWidth + 4f, frameHeight + 4f),
            cornerRadius = CornerRadius(24f, 24f),
            style = Stroke(width = 5f),
        )
        drawRoundRect(
            color = overlayColor,
            topLeft = Offset(frameLeft, frameTop),
            size = Size(frameWidth, frameHeight),
            cornerRadius = CornerRadius(22f, 22f),
            style = Stroke(width = 2.2f),
        )
        drawLine(
            color = overlayColor,
            start = Offset(centerX - crosshair, centerY),
            end = Offset(centerX + crosshair, centerY),
            strokeWidth = 2f,
        )
        drawLine(
            color = overlayColor,
            start = Offset(centerX, centerY - crosshair),
            end = Offset(centerX, centerY + crosshair),
            strokeWidth = 2f,
        )
        drawCircle(
            color = overlayColor,
            radius = 3.5f,
            center = Offset(centerX, centerY),
        )
    }
}

@Composable
private fun BoxScope.DeepSkyEnvironmentOverlay(
    skyInfo: RemoteSkyOverlayInfo?,
    readableRotation: Int,
) {
    val animatedRotation = rememberAnimatedReadableRotation(readableRotation, label = "deepSkyEnvironmentRotation")

    skyInfo?.let { info ->
        val accent = when (info.phase) {
            RemoteSkyPhase.DarkSky -> AppleBlue
            RemoteSkyPhase.AstronomicalTwilight -> AppleGreen
            RemoteSkyPhase.NauticalTwilight -> AppleOrange
            RemoteSkyPhase.CivilTwilight -> AppleOrange
            RemoteSkyPhase.GoldenHour -> Color(0xFFFFC857)
            RemoteSkyPhase.Daylight -> Chalk
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black.copy(alpha = 0.64f))
                .border(1.dp, accent.copy(alpha = 0.32f), RoundedCornerShape(18.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .graphicsLayer { rotationZ = animatedRotation },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBadge(label = info.phaseLabel, color = accent)
                info.gpsLabel?.let { gpsLabel ->
                    Text(
                        text = gpsLabel,
                        color = Chalk.copy(alpha = 0.58f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(
                text = "${info.timeLabel} · ${info.locationLabel}",
                color = Chalk,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = listOfNotNull(info.detailLabel, info.directionLabel, info.fieldLabel).joinToString(" · "),
                color = Chalk.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SelectorButton(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    highlighted: Boolean = false,
    selected: Boolean,
    enabled: Boolean = true,
    allowWhenDisabled: Boolean = false,
    readableRotation: Int,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    val animatedRotation = rememberAnimatedReadableRotation(readableRotation, label = "selectorRotation")
    val compactLandscape = rememberLandscapeControlLayout() || rememberRemoteQuarterTurn(readableRotation)
    val valueFontSize = if (compactLandscape) 9.sp else 14.sp
    val valueLineHeight = if (compactLandscape) 10.sp else 18.sp
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled || allowWhenDisabled) {
                ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.CLOCK_TICK)
                onClick()
            }
            .graphicsLayer { alpha = if (enabled || highlighted || selected) 1f else 0.76f }
            .padding(horizontal = 1.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minWidth = if (compactLandscape) 54.dp else 48.dp)
                .height(if (compactLandscape) 64.dp else 56.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .graphicsLayer { rotationZ = animatedRotation }
                    .padding(horizontal = if (compactLandscape) 8.dp else 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compactLandscape) 2.dp else 3.dp),
            ) {
                Text(
                    text = value,
                    modifier = Modifier.widthIn(min = if (compactLandscape) 54.dp else 0.dp),
                    color = when {
                        selected -> AppleRed
                        !enabled -> Chalk.copy(alpha = 0.56f)
                        highlighted -> AppleRed
                        else -> Chalk
                    },
                    style = if (compactLandscape) {
                        MaterialTheme.typography.titleSmall.copy(
                            fontSize = valueFontSize,
                            lineHeight = valueLineHeight,
                        )
                    } else {
                        MaterialTheme.typography.labelLarge
                    },
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    softWrap = false,
                )
                Text(
                    text = label,
                    modifier = Modifier.widthIn(min = if (compactLandscape) 42.dp else 0.dp),
                    color = Chalk.copy(alpha = if (enabled) 0.48f else 0.28f),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = if (compactLandscape) 8.sp else 11.sp,
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        Box(
            modifier = Modifier
                .width(18.dp)
                .height(2.dp)
                .background(if (selected) AppleRed else Color.Transparent),
        )
    }
}

@Composable
private fun DriveSelectorButton(
    modifier: Modifier = Modifier,
    summary: String,
    shootingMode: RemoteShootingMode,
    driveMode: DriveMode,
    selected: Boolean,
    readableRotation: Int,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    val animatedRotation = rememberAnimatedReadableRotation(readableRotation, label = "driveSelectorRotation")
    val compactLandscape = rememberLandscapeControlLayout() || rememberRemoteQuarterTurn(readableRotation)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable {
                ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.CLOCK_TICK)
                onClick()
            }
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compactLandscape) 52.dp else 56.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.graphicsLayer { rotationZ = animatedRotation },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compactLandscape) 4.dp else 5.dp),
            ) {
                DriveGlyph(
                    shootingMode = shootingMode,
                    driveMode = driveMode,
                    tint = if (selected) AppleRed else Chalk,
                )
                Text(
                    text = summary,
                    color = if (selected) AppleRed else Chalk,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = if (compactLandscape) 8.sp else 11.sp,
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        Box(
            modifier = Modifier
                .width(18.dp)
                .height(2.dp)
                .background(if (selected) AppleRed else Color.Transparent),
        )
    }
}

@Composable
private fun ExposureScalePanel(
    values: List<String>,
    currentValue: String,
    onExposureChanged: (String) -> Unit,
    readableRotation: Int,
) {
    val view = LocalView.current
    val animatedRotation = rememberAnimatedReadableRotation(readableRotation, label = "evRotation")
    val compactLandscape = rememberLandscapeControlLayout() || rememberRemoteQuarterTurn(readableRotation)
    val normalizedValues = values
        .ifEmpty { listOf("0.0") }
        .map(::normalizeExposureDialValue)
        .distinct()
    val normalizedCurrentValue = normalizeExposureDialValue(currentValue)
    val resolvedIndex = findExposureScaleIndex(
        values = normalizedValues,
        currentValue = normalizedCurrentValue,
    )
    var sliderPosition by remember(normalizedValues, normalizedCurrentValue) { mutableFloatStateOf(resolvedIndex.toFloat()) }
    var isEditing by remember { mutableStateOf(false) }
    // Track last haptic step to fire once per step change during drag
    var lastHapticStep by remember { mutableIntStateOf(resolvedIndex) }

    LaunchedEffect(normalizedValues) {
        isEditing = false
        sliderPosition = resolvedIndex.toFloat()
        lastHapticStep = resolvedIndex
    }

    LaunchedEffect(resolvedIndex, normalizedValues) {
        if (!isEditing) {
            sliderPosition = resolvedIndex.toFloat()
            lastHapticStep = resolvedIndex
        }
    }

    // Step-change haptic during EV slider drag — fires on each discrete step
    LaunchedEffect(sliderPosition) {
        if (!isEditing) return@LaunchedEffect
        val currentStep = sliderPosition.roundToInt().coerceIn(0, normalizedValues.lastIndex)
        if (currentStep != lastHapticStep) {
            lastHapticStep = currentStep
            val stepValue = normalizedValues[currentStep]
            // Use CONFIRM for full-stop EV values (0.0, ±1.0, ±2.0, ±3.0) for stronger feel
            val isMajorStep = stepValue.endsWith(".0") || stepValue == "0.0"
            if (isMajorStep) {
                ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.CONFIRM)
            } else {
                performDialHaptic(view, "expcomp", stepValue)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (compactLandscape) 14.dp else 0.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "EV",
                modifier = Modifier.graphicsLayer { rotationZ = animatedRotation },
                color = Chalk.copy(alpha = 0.52f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            Box(
                modifier = Modifier
                    .widthIn(min = if (compactLandscape) 54.dp else 0.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = PropertyFormatter.formatForDisplay(
                        "expcomp",
                        normalizedValues[sliderPosition.roundToInt().coerceIn(0, normalizedValues.lastIndex)],
                    ),
                    modifier = Modifier
                        .graphicsLayer { rotationZ = animatedRotation }
                        .padding(horizontal = if (compactLandscape) 8.dp else 4.dp),
                    color = Color.White,
                    style = if (compactLandscape) {
                        MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp, lineHeight = 26.sp)
                    } else {
                        MaterialTheme.typography.titleLarge
                    },
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val y = size.height * 0.38f
                val startX = 24.dp.toPx()
                val endX = size.width - startX
                drawLine(Chalk.copy(alpha = 0.18f), Offset(startX, y), Offset(endX, y), 2f)

                val stepCount = (normalizedValues.size - 1).coerceAtLeast(1)
                normalizedValues.forEachIndexed { index, rawVal ->
                    val fraction = index.toFloat() / stepCount.toFloat()
                    val x = startX + ((endX - startX) * fraction)
                    val isZero = rawVal == "0.0"
                    val isCurrent = index == sliderPosition.roundToInt().coerceIn(0, normalizedValues.lastIndex)
                    val isWholeStop = rawVal.toDoubleOrNull()?.let { v ->
                        val abs = kotlin.math.abs(v)
                        abs == 0.0 || abs == 1.0 || abs == 2.0 || abs == 3.0 || abs == 4.0 || abs == 5.0
                    } ?: false
                    val tickHeight = when {
                        isZero -> 28.dp.toPx()
                        isWholeStop -> 18.dp.toPx()
                        else -> 10.dp.toPx()
                    }
                    drawLine(
                        color = when {
                            isCurrent -> AppleRed
                            isZero -> Chalk.copy(alpha = 0.82f)
                            isWholeStop -> Chalk.copy(alpha = 0.52f)
                            else -> Chalk.copy(alpha = 0.28f)
                        },
                        start = Offset(x, y - (tickHeight / 2f)),
                        end = Offset(x, y + (tickHeight / 2f)),
                        strokeWidth = when {
                            isCurrent -> 3f
                            isZero -> 2.5f
                            isWholeStop -> 2f
                            else -> 1.5f
                        },
                    )
                }
            }

            Slider(
                value = sliderPosition,
                onValueChange = {
                    isEditing = true
                    sliderPosition = it
                },
                onValueChangeFinished = {
                    val targetIndex = sliderPosition.roundToInt().coerceIn(0, normalizedValues.lastIndex)
                    val targetValue = normalizedValues[targetIndex]
                    sliderPosition = targetIndex.toFloat()
                    isEditing = false
                    if (!targetValue.equals(normalizedCurrentValue, ignoreCase = true)) {
                        performDialHaptic(view, "expcomp", targetValue)
                        onExposureChanged(targetValue)
                    }
                },
                valueRange = 0f..normalizedValues.lastIndex.toFloat().coerceAtLeast(0f),
                steps = (normalizedValues.size - 2).coerceAtLeast(0),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color.Transparent,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (compactLandscape) 14.dp else 0.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val compactLabelWidth = if (compactLandscape) 44.dp else 0.dp
            Box(modifier = Modifier.widthIn(min = compactLabelWidth), contentAlignment = Alignment.Center) {
                Text(
                    text = PropertyFormatter.formatForDisplay("expcomp", normalizedValues.first()),
                    modifier = Modifier.graphicsLayer { rotationZ = animatedRotation },
                    color = Chalk.copy(alpha = 0.42f),
                    style = if (compactLandscape) {
                        MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 9.sp)
                    } else {
                        MaterialTheme.typography.labelSmall
                    },
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
            Box(modifier = Modifier.widthIn(min = if (compactLandscape) 40.dp else 0.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = "0.0",
                    modifier = Modifier.graphicsLayer { rotationZ = animatedRotation },
                    color = Chalk.copy(alpha = 0.42f),
                    style = if (compactLandscape) {
                        MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 9.sp)
                    } else {
                        MaterialTheme.typography.labelSmall
                    },
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
            Box(modifier = Modifier.widthIn(min = compactLabelWidth), contentAlignment = Alignment.Center) {
                Text(
                    text = PropertyFormatter.formatForDisplay("expcomp", normalizedValues.last()),
                    modifier = Modifier.graphicsLayer { rotationZ = animatedRotation },
                    color = Chalk.copy(alpha = 0.42f),
                    style = if (compactLandscape) {
                        MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 9.sp)
                    } else {
                        MaterialTheme.typography.labelSmall
                    },
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DialSection(
    label: String,
    values: CameraPropertyValues,
    propName: String,
    reverseDial: Boolean,
    readableRotation: Int,
    onSelect: (String, Boolean) -> Unit,
) {
    val compactLandscape = rememberLandscapeControlLayout() || rememberRemoteQuarterTurn(readableRotation)
    val animatedRotation = rememberAnimatedReadableRotation(readableRotation, label = "dialSectionRotation")
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(if (compactLandscape) 0.dp else 8.dp),
    ) {
        if (!compactLandscape) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.graphicsLayer { rotationZ = animatedRotation },
                    color = Chalk.copy(alpha = 0.52f),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                    ),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = rangeLabel(propName, values),
                    modifier = Modifier.graphicsLayer { rotationZ = animatedRotation },
                    color = Chalk.copy(alpha = 0.36f),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                    ),
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            PropertyDial(
                prop = values,
                propName = propName,
                reverseDial = reverseDial,
                readableRotation = readableRotation,
                onSelect = onSelect,
            )
        }
    }
}

/**
 * Dial section for USB PTP properties — converts CameraPropertyState (Long values)
 * into CameraPropertyValues (String) so the existing PropertyDial can render them.
 * Uses SCP PTP formatters for display.
 */
@Composable
private fun UsbPropertyDialSection(
    label: String,
    prop: OmCaptureUsbManager.CameraPropertyState?,
    propCode: Int,
    readableRotation: Int,
    onSetUsbProperty: (Int, Long) -> Unit,
) {
    if (prop == null) {
        Text(
            text = "$label: not available",
            color = Chalk.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelMedium,
        )
        return
    }
    // Build a string-keyed propName so PropertyDial uses our custom formatter
    val usbPropName = "usb_0x${propCode.toString(16)}"
    val rawValues = remember(
        prop.allowedValues,
        prop.currentValue,
        prop.rangeMin,
        prop.rangeMax,
        prop.rangeStep,
    ) {
        enumerateUsbDialRawValues(prop)
    }
    // Convert Long values to String for the dial
    val dialValues = remember(rawValues, prop.currentValue) {
        CameraPropertyValues(
            currentValue = prop.currentValue.toString(),
            availableValues = rawValues.map { it.toString() },
        )
    }
    // Reverse mapping: formatted string dial value → Long for USB command
    val valueLookup = remember(rawValues) {
        rawValues.associateBy { it.toString() }
    }
    val compactLandscape = rememberLandscapeControlLayout() || rememberRemoteQuarterTurn(readableRotation)
    val animatedRotation = rememberAnimatedReadableRotation(readableRotation, label = "usbDialRotation")
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(if (compactLandscape) 0.dp else 8.dp),
    ) {
        if (!compactLandscape) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.graphicsLayer { rotationZ = animatedRotation },
                    color = Chalk.copy(alpha = 0.52f),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = formatScpPtpValue(prop, prop.currentValue),
                    modifier = Modifier.graphicsLayer { rotationZ = animatedRotation },
                    color = AppleBlue,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            PropertyDial(
                prop = dialValues,
                propName = usbPropName,
                reverseDial = false,
                readableRotation = readableRotation,
                onSelect = { selectedValue, _ ->
                    val longVal = valueLookup[selectedValue]
                    if (longVal != null) {
                        onSetUsbProperty(propCode, longVal)
                    }
                },
            )
        }
    }
}

@Composable
private fun BottomControlBar(
    runtime: RemoteRuntimeState,
    lastCaptureThumbnail: Bitmap?,
    onCapturePhoto: () -> Unit,
    onStartInterval: () -> Unit,
    onStopInterval: () -> Unit,
    onSetActivePicker: (ActivePropertyPicker) -> Unit,
    onOpenLibrary: () -> Unit,
    readableRotation: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CaptureThumbnail(
            bitmap = lastCaptureThumbnail,
            compact = false,
            modifier = Modifier.padding(bottom = 4.dp),
            onClick = onOpenLibrary,
        )
        ShutterButton(
            isBusy = runtime.isBusy,
            intervalRunning = runtime.intervalRunning,
            compact = false,
            onClick = {
                when {
                    runtime.intervalRunning -> onStopInterval()
                    runtime.shootingMode == RemoteShootingMode.Interval -> onStartInterval()
                    else -> onCapturePhoto()
                }
            },
        )
        ModeSelector(
            mode = runtime.exposureMode,
            tetherActive = runtime.modePickerSurface == ModePickerSurface.Tether ||
                runtime.modePickerSurface == ModePickerSurface.TetherRetry ||
                runtime.modePickerSurface == ModePickerSurface.DeepSky,
            selected = runtime.activePicker == ActivePropertyPicker.ExposureMode,
            onToggle = {
                onSetActivePicker(
                    if (runtime.activePicker == ActivePropertyPicker.ExposureMode) ActivePropertyPicker.None
                    else ActivePropertyPicker.ExposureMode,
                )
            },
            readableRotation = readableRotation,
            compact = false,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

@Composable
private fun TetheredLiveViewWorkspace(
    omCaptureUsb: OmCaptureUsbUiState,
    liveViewActive: Boolean,
    tetherSaveTarget: TetherSaveTarget,
    onToggleLiveView: () -> Unit,
    onRefreshOmCaptureUsb: () -> Unit,
    onClearOmCaptureUsb: () -> Unit,
    onOpenOmCapture: () -> Unit,
    onOpenDeepSkyLiveStack: () -> Unit,
    onReturnToCamera: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.remote_tether_live_view_title),
                    color = Chalk,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.remote_tether_live_view_subtitle),
                    color = Chalk.copy(alpha = 0.56f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TetheredActionChip(
                label = stringResource(R.string.remote_camera_controls),
                accent = AppleBlue,
                selected = false,
                onClick = onOpenOmCapture,
            )
        }
        TetheredCapturePanel(
            omCaptureUsb = omCaptureUsb,
            liveViewActive = liveViewActive,
            tetherSaveTarget = tetherSaveTarget,
            onToggleLiveView = onToggleLiveView,
            onRefreshOmCaptureUsb = onRefreshOmCaptureUsb,
            onClearOmCaptureUsb = onClearOmCaptureUsb,
            onOpenOmCapture = onOpenOmCapture,
            onOpenDeepSkyLiveStack = onOpenDeepSkyLiveStack,
        )
        TetheredActionChip(
            label = stringResource(R.string.remote_back_to_camera),
            accent = AppleOrange,
            selected = false,
            onClick = onReturnToCamera,
        )
    }
}

@Composable
private fun TetheredCapturePanel(
    omCaptureUsb: OmCaptureUsbUiState,
    liveViewActive: Boolean,
    tetherSaveTarget: TetherSaveTarget,
    onToggleLiveView: () -> Unit,
    onRefreshOmCaptureUsb: () -> Unit,
    onClearOmCaptureUsb: () -> Unit,
    onOpenOmCapture: () -> Unit,
    onOpenDeepSkyLiveStack: () -> Unit,
) {
    val statusColor = when (omCaptureUsb.operationState) {
        OmCaptureUsbOperationState.Complete -> AppleGreen
        OmCaptureUsbOperationState.Error -> AppleRed
        OmCaptureUsbOperationState.Idle -> {
            if (omCaptureUsb.summary != null) AppleBlue else Chalk.copy(alpha = 0.4f)
        }
        else -> AppleOrange
    }
    val statusText = when {
        omCaptureUsb.lastActionLabel != null -> omCaptureUsb.lastActionLabel
        else -> omCaptureUsb.statusLabel
    }
    val connectLabel = when {
        omCaptureUsb.isBusy -> stringResource(R.string.remote_connecting_usb)
        omCaptureUsb.canRetry -> stringResource(R.string.remote_retry_usb)
        omCaptureUsb.summary != null -> stringResource(R.string.remote_reconnect_usb)
        else -> stringResource(R.string.remote_connect_usb)
    }
    val liveViewLabel = if (liveViewActive) {
        stringResource(R.string.remote_tethered_live_view_stop)
    } else {
        stringResource(R.string.remote_tethered_live_view_start)
    }
    val saveTargetLabel = when (tetherSaveTarget) {
        TetherSaveTarget.SdCard -> stringResource(R.string.settings_tether_save_target_sd)
        TetherSaveTarget.SdAndPhone -> stringResource(R.string.settings_tether_save_target_sd_phone)
        TetherSaveTarget.Phone -> stringResource(R.string.settings_tether_save_target_phone)
    }
    val canClearStatus =
        omCaptureUsb.summary != null ||
            omCaptureUsb.lastActionLabel != null ||
            omCaptureUsb.lastSavedMedia != null ||
            omCaptureUsb.importedCount > 0 ||
            omCaptureUsb.canRetry
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Graphite.copy(alpha = 0.96f))
            .border(1.dp, LeicaBorder, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.remote_tethered_title),
                color = Chalk,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            StatusBadge(
                label = omCaptureUsb.operationState.statusChipLabel,
                color = statusColor,
            )
        }
        Text(
            text = omCaptureUsb.statusLabel,
            color = Chalk.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = stringResource(R.string.remote_tethered_save_target, saveTargetLabel),
            color = Chalk.copy(alpha = 0.58f),
            style = MaterialTheme.typography.labelSmall,
        )
        omCaptureUsb.summary?.let { summary ->
            Text(
                text = summary.model.ifBlank { summary.deviceLabel },
                color = Chalk.copy(alpha = 0.56f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = statusText,
            color = Chalk.copy(alpha = 0.62f),
            style = MaterialTheme.typography.labelSmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TetheredActionChip(
                label = connectLabel,
                accent = AppleBlue,
                selected = omCaptureUsb.summary != null,
                enabled = !omCaptureUsb.isBusy,
                modifier = Modifier.weight(1f),
                onClick = onRefreshOmCaptureUsb,
            )
            TetheredActionChip(
                label = stringResource(R.string.remote_clear_status),
                accent = AppleOrange,
                selected = false,
                enabled = !omCaptureUsb.isBusy && canClearStatus,
                modifier = Modifier.weight(1f),
                onClick = onClearOmCaptureUsb,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TetheredActionChip(
                label = liveViewLabel,
                accent = AppleOrange,
                selected = liveViewActive,
                enabled = !omCaptureUsb.isBusy,
                modifier = Modifier.weight(1f),
                onClick = onToggleLiveView,
            )
            TetheredActionChip(
                label = stringResource(R.string.remote_camera_controls),
                accent = AppleBlue,
                selected = false,
                enabled = omCaptureUsb.summary != null,
                modifier = Modifier.weight(1f),
                onClick = onOpenOmCapture,
            )
        }
        TetheredActionChip(
            label = stringResource(R.string.remote_deep_sky_live_stack),
            accent = AppleBlue,
            selected = false,
            enabled = omCaptureUsb.summary != null,
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenDeepSkyLiveStack,
        )
    }
}

@Composable
private fun TetheredActionChip(
    label: String,
    accent: Color,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .graphicsLayer { alpha = if (enabled) 1f else 0.46f }
            .background(if (selected) accent.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.28f))
            .border(1.dp, if (selected) accent.copy(alpha = 0.48f) else LeicaBorder, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when {
                !enabled -> Chalk.copy(alpha = 0.46f)
                selected -> accent
                else -> Chalk
            },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LandscapeRemoteControlDeck(
    runtime: RemoteRuntimeState,
    lastCaptureThumbnail: Bitmap?,
    onCapturePhoto: () -> Unit,
    onStartInterval: () -> Unit,
    onStopInterval: () -> Unit,
    onSetActivePicker: (ActivePropertyPicker) -> Unit,
    onOpenLibrary: () -> Unit,
    readableRotation: Int,
    selectorContent: @Composable () -> Unit,
    panelContent: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.94f))
            .padding(
                start = 8.dp,
                end = 8.dp,
                top = 12.dp,
                bottom = 10.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        selectorContent()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp, max = 200.dp)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            panelContent()
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp),
        ) {
            CaptureThumbnail(
                bitmap = lastCaptureThumbnail,
                compact = true,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 6.dp, bottom = 8.dp),
                onClick = onOpenLibrary,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                ShutterButton(
                    isBusy = runtime.isBusy,
                    intervalRunning = runtime.intervalRunning,
                    compact = false,
                    onClick = {
                        when {
                            runtime.intervalRunning -> onStopInterval()
                            runtime.shootingMode == RemoteShootingMode.Interval -> onStartInterval()
                            else -> onCapturePhoto()
                        }
                    },
                )
            }
            ModeSelector(
                mode = runtime.exposureMode,
                tetherActive = runtime.modePickerSurface == ModePickerSurface.Tether ||
                    runtime.modePickerSurface == ModePickerSurface.TetherRetry ||
                    runtime.modePickerSurface == ModePickerSurface.DeepSky,
                selected = runtime.activePicker == ActivePropertyPicker.ExposureMode,
                onToggle = {
                    onSetActivePicker(
                        if (runtime.activePicker == ActivePropertyPicker.ExposureMode) ActivePropertyPicker.None
                        else ActivePropertyPicker.ExposureMode,
                    )
                },
                readableRotation = readableRotation,
                compact = true,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 2.dp, bottom = 3.dp),
            )
        }
    }
}

@Composable
private fun LandscapeActionRail(
    runtime: RemoteRuntimeState,
    lastCaptureThumbnail: Bitmap?,
    onCapturePhoto: () -> Unit,
    onStartInterval: () -> Unit,
    onStopInterval: () -> Unit,
    onSetActivePicker: (ActivePropertyPicker) -> Unit,
    onOpenLibrary: () -> Unit,
    readableRotation: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.92f)),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ModeSelector(
                mode = runtime.exposureMode,
                tetherActive = runtime.modePickerSurface == ModePickerSurface.Tether ||
                    runtime.modePickerSurface == ModePickerSurface.TetherRetry ||
                    runtime.modePickerSurface == ModePickerSurface.DeepSky,
                selected = runtime.activePicker == ActivePropertyPicker.ExposureMode,
                onToggle = {
                    onSetActivePicker(
                        if (runtime.activePicker == ActivePropertyPicker.ExposureMode) ActivePropertyPicker.None
                        else ActivePropertyPicker.ExposureMode,
                    )
                },
                readableRotation = readableRotation,
                compact = true,
            )
        }
        Box(
            modifier = Modifier.align(Alignment.Center),
            contentAlignment = Alignment.Center,
        ) {
            ShutterButton(
                isBusy = runtime.isBusy,
                intervalRunning = runtime.intervalRunning,
                compact = true,
                onClick = {
                    when {
                        runtime.intervalRunning -> onStopInterval()
                        runtime.shootingMode == RemoteShootingMode.Interval -> onStartInterval()
                        else -> onCapturePhoto()
                    }
                },
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CaptureThumbnail(
                bitmap = lastCaptureThumbnail,
                compact = true,
                onClick = onOpenLibrary,
            )
        }
    }
}

@Composable
private fun CaptureThumbnail(
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: () -> Unit = {},
) {
    val view = LocalView.current
    val thumbSize = if (compact) 44.dp else 46.dp
    Box(
        modifier = modifier
            .size(thumbSize)
            .clip(RoundedCornerShape(if (compact) 10.dp else 14.dp))
            .background(if (bitmap != null) Graphite else Color.Transparent)
            .clickable(enabled = bitmap != null) {
                ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.CLOCK_TICK)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun ShutterButton(
    isBusy: Boolean,
    intervalRunning: Boolean,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    val outerSize = if (compact) 68.dp else 78.dp
    val innerSize = if (compact) 64.dp else 74.dp
    val ringSize = if (compact) 38.dp else 44.dp
    Box(
        modifier = Modifier.size(outerSize),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(innerSize)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (isBusy && !intervalRunning) 0.52f else 0.96f))
                .clickable(enabled = !isBusy || intervalRunning) {
                    ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.CONFIRM)
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            if (intervalRunning) {
                Icon(Icons.Rounded.Stop, contentDescription = null, tint = Color.Black, modifier = Modifier.size(if (compact) 16.dp else 22.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(ringSize)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.14f)),
                )
            }
        }
    }
}

@Composable
private fun ModeSelector(
    mode: CameraExposureMode,
    tetherActive: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    readableRotation: Int,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val view = LocalView.current
    val animatedRotation = rememberAnimatedReadableRotation(readableRotation, label = "modeRotation")
    val containerShape = if (compact) CircleShape else RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .then(if (compact) Modifier.size(58.dp) else Modifier)
            .clip(containerShape)
            .background(Graphite)
            .clickable {
                ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.CLOCK_TICK)
                onToggle()
            }
            .padding(
                horizontal = if (compact) 0.dp else 14.dp,
                vertical = if (compact) 0.dp else 10.dp,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Text(
                text = mode.label,
                modifier = Modifier.graphicsLayer { rotationZ = animatedRotation },
                color = if (selected) AppleRed else Chalk,
                style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            if (tetherActive) {
                Box(
                    modifier = Modifier
                        .padding(start = if (compact) 12.dp else 18.dp, bottom = if (compact) 10.dp else 14.dp)
                        .size(if (compact) 4.dp else 6.dp)
                        .clip(CircleShape)
                        .background(AppleBlue),
                )
            }
        }
    }
}
@Composable
private fun DriveSettingsPanel(
    remoteRuntime: RemoteRuntimeState,
    onSetShootingMode: (RemoteShootingMode) -> Unit,
    onSetDriveMode: (DriveMode) -> Unit,
    onSetTimerMode: (TimerMode) -> Unit,
    onSetTimerDelay: (Int) -> Unit,
    onSetIntervalSeconds: (Int) -> Unit,
    onSetIntervalCount: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DriveChip("Single", remoteRuntime.shootingMode == RemoteShootingMode.Single, Modifier.weight(1f)) {
                onSetShootingMode(RemoteShootingMode.Single)
            }
            DriveChip("Interval", remoteRuntime.shootingMode == RemoteShootingMode.Interval, Modifier.weight(1f)) {
                onSetShootingMode(RemoteShootingMode.Interval)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DriveMode.entries.forEach { mode ->
                DriveChip(driveLabel(mode), remoteRuntime.driveMode == mode, Modifier.weight(1f)) {
                    onSetDriveMode(mode)
                }
            }
        }
        if (remoteRuntime.shootingMode == RemoteShootingMode.Interval) {
            ValueSlider("Interval", "${remoteRuntime.intervalSeconds}s", remoteRuntime.intervalSeconds.toFloat(), 2f..60f, 57) {
                onSetIntervalSeconds(it.roundToInt())
            }
            ValueSlider("Count", remoteRuntime.intervalCount.toString(), remoteRuntime.intervalCount.toFloat(), 2f..99f, 96) {
                onSetIntervalCount(it.roundToInt())
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TimerMode.entries.forEach { mode ->
                    DriveChip(timerLabel(mode, remoteRuntime.timerDelay), remoteRuntime.timerMode == mode, Modifier.weight(1f)) {
                        onSetTimerMode(mode)
                    }
                }
            }
            if (remoteRuntime.timerMode != TimerMode.Off) {
                ValueSlider("Delay", "${remoteRuntime.timerDelay}s", remoteRuntime.timerDelay.toFloat(), 1f..30f, 28) {
                    onSetTimerDelay(it.roundToInt())
                }
            }
        }
    }
}

private fun usesElectronicShutterDriveMode(rawValue: Long, allRawValues: List<Long>): Boolean {
    val label = formatOlympusDriveMode(rawValue, allRawValues)
    return label.contains("Silent", ignoreCase = true) ||
        (!isLegacyOlympusDriveLayout(allRawValues) && rawValue in Om1SilentBurstDriveRawValues)
}

private fun sortUsbDriveModesForDisplay(rawValues: List<Long>, allRawValues: List<Long>): List<Long> {
    fun rank(rawValue: Long): Int {
        val label = formatOlympusDriveMode(rawValue, allRawValues)
        return when {
            label.contains("Single", ignoreCase = true) -> 0
            label.contains("Sequential L", ignoreCase = true) -> 10
            label.contains("Sequential H2", ignoreCase = true) -> 20
            label.contains("Sequential H", ignoreCase = true) -> 21
            label.contains("Pro Capture Low", ignoreCase = true) -> 30
            label.contains("Pro Capture SH1", ignoreCase = true) -> 31
            label.contains("Pro Capture SH2", ignoreCase = true) -> 32
            label.contains("SH2", ignoreCase = true) -> 32
            label.contains("Self-timer Burst", ignoreCase = true) -> 40
            label.contains("Custom Self-timer", ignoreCase = true) -> 41
            label.contains("Timer C", ignoreCase = true) -> 41
            label.contains("Self-timer", ignoreCase = true) -> 42
            label.contains("Anti-Shock", ignoreCase = true) -> 50
            else -> 90
        }
    }

    return rawValues.sortedWith(
        compareBy<Long> { rank(it) }
            .thenBy { allRawValues.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE },
    )
}

/**
 * Shorten a drive-mode label for display inside a category tab.
 * When the mode is already inside the "Silent" tab, strip the "Silent : " prefix
 * so users see just "Single", "Seq L", etc.
 */
private fun usbDriveCategoryChipLabel(fullLabel: String, insideSilentTab: Boolean): String {
    val stripped = if (insideSilentTab) {
        fullLabel.removePrefix("Silent : ").removePrefix("Silent: ").removePrefix("Silent ")
    } else {
        fullLabel
    }
    return when {
        stripped.contains("Single", ignoreCase = true) -> "Single"
        stripped.contains("Sequential L", ignoreCase = true) -> "Seq L"
        stripped.contains("Sequential H2", ignoreCase = true) -> "Seq H2"
        stripped.contains("Sequential H", ignoreCase = true) -> "Seq H"
        stripped.contains("Pro Capture Low", ignoreCase = true) -> "ProCap L"
        stripped.contains("Pro Capture SH1", ignoreCase = true) -> "ProCap SH1"
        stripped.contains("Pro Capture SH2", ignoreCase = true) -> "ProCap SH2"
        stripped.contains("Custom Self-timer", ignoreCase = true) -> "Custom Timer"
        stripped.contains("Self-timer Burst", ignoreCase = true) -> "Timer Burst"
        stripped.contains("Self-timer", ignoreCase = true) -> "Timer"
        else -> stripped
    }
}

/**
 * Returns true if the given raw value is a timer-type drive mode
 * (self-timer, custom self-timer, silent self-timer, etc.)
 */
private fun isDriveModeTimer(rawValue: Long, allRawValues: List<Long>): Boolean {
    val label = formatOlympusDriveMode(rawValue, allRawValues)
    return label.contains("Self-timer", ignoreCase = true) ||
        label.contains("Timer", ignoreCase = true)
}

private fun isUsbDriveBurstRaw(rawValue: Long, allRawValues: List<Long>): Boolean {
    val label = formatOlympusDriveMode(rawValue, allRawValues)
    return !isDriveModeTimer(rawValue, allRawValues) &&
        !label.contains("Single", ignoreCase = true) &&
        (
            label.contains("Sequential", ignoreCase = true) ||
                label.contains("Burst", ignoreCase = true) ||
                label.contains("Pro Capture", ignoreCase = true) ||
                label.contains("SH1", ignoreCase = true) ||
                label.contains("SH2", ignoreCase = true)
            )
}

private fun usbBurstDriveOptions(driveMode: DriveMode, rawValues: List<Long>): List<Long> {
    val electronic = driveMode == DriveMode.SilentBurst
    val candidates = rawValues.filter { raw ->
        isUsbDriveBurstRaw(raw, rawValues) &&
            usesElectronicShutterDriveMode(raw, rawValues) == electronic
    }
    return sortUsbDriveModesForDisplay(candidates, rawValues)
}

@Composable
private fun UsbDriveSettingsPanel(
    driveProp: OmCaptureUsbManager.CameraPropertyState?,
    remoteRuntime: RemoteRuntimeState,
    onSetShootingMode: (RemoteShootingMode) -> Unit,
    onSetDriveMode: (DriveMode) -> Unit,
    onSetTimerMode: (TimerMode) -> Unit,
    onSetTimerDelay: (Int) -> Unit,
    onSetIntervalSeconds: (Int) -> Unit,
    onSetIntervalCount: (Int) -> Unit,
    onSetUsbProperty: (Int, Long) -> Unit,
) {
    if (driveProp == null) {
        Text(
            text = stringResource(R.string.remote_drive_mode_unavailable),
            color = Chalk.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelMedium,
        )
        return
    }
    val rawValues = remember(
        driveProp.allowedValues,
        driveProp.currentValue,
        driveProp.rangeMin,
        driveProp.rangeMax,
        driveProp.rangeStep,
    ) {
        enumerateUsbDialRawValues(driveProp)
    }

    var expandedBurstMode by remember { mutableStateOf<DriveMode?>(null) }
    val selectedBurstModes = remember(expandedBurstMode, rawValues) {
        expandedBurstMode?.let { mode -> usbBurstDriveOptions(mode, rawValues) }.orEmpty()
    }
    val visibleBurstModes = selectedBurstModes.take(4)
    val showBurstModes = remoteRuntime.shootingMode != RemoteShootingMode.Interval &&
        expandedBurstMode in setOf(DriveMode.Burst, DriveMode.SilentBurst) &&
        visibleBurstModes.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DriveChip("Single", remoteRuntime.shootingMode == RemoteShootingMode.Single, Modifier.weight(1f)) {
                expandedBurstMode = null
                onSetShootingMode(RemoteShootingMode.Single)
            }
            DriveChip("Interval", remoteRuntime.shootingMode == RemoteShootingMode.Interval, Modifier.weight(1f)) {
                expandedBurstMode = null
                onSetShootingMode(RemoteShootingMode.Interval)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DriveMode.entries.forEach { mode ->
                DriveChip(driveLabel(mode), remoteRuntime.driveMode == mode, Modifier.weight(1f)) {
                    expandedBurstMode = mode.takeIf { it == DriveMode.Burst || it == DriveMode.SilentBurst }
                    onSetDriveMode(mode)
                }
            }
        }
        if (remoteRuntime.shootingMode == RemoteShootingMode.Interval) {
            ValueSlider("Interval", "${remoteRuntime.intervalSeconds}s", remoteRuntime.intervalSeconds.toFloat(), 2f..60f, 57) {
                onSetIntervalSeconds(it.roundToInt())
            }
            ValueSlider("Count", remoteRuntime.intervalCount.toString(), remoteRuntime.intervalCount.toFloat(), 2f..99f, 96) {
                onSetIntervalCount(it.roundToInt())
            }
        } else if (showBurstModes) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                visibleBurstModes.forEach { rawValue ->
                    val fullLabel = formatOlympusDriveMode(rawValue, rawValues)
                    val chipLabel = usbDriveCategoryChipLabel(
                        fullLabel,
                        expandedBurstMode == DriveMode.SilentBurst,
                    )
                    DriveChip(
                        label = chipLabel,
                        selected = rawValue == driveProp.currentValue,
                        modifier = Modifier.weight(1f),
                    ) {
                        expandedBurstMode = null
                        onSetUsbProperty(PtpConstants.OlympusProp.DriveMode, rawValue)
                    }
                }
                repeat(4 - visibleBurstModes.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TimerMode.entries.forEach { mode ->
                    DriveChip(timerLabel(mode, remoteRuntime.timerDelay), remoteRuntime.timerMode == mode, Modifier.weight(1f)) {
                        expandedBurstMode = null
                        onSetTimerMode(mode)
                    }
                }
            }
            if (remoteRuntime.timerMode != TimerMode.Off) {
                ValueSlider("Delay", "${remoteRuntime.timerDelay}s", remoteRuntime.timerDelay.toFloat(), 1f..30f, 28) {
                    onSetTimerDelay(it.roundToInt())
                }
            }
        }
    }
}

/** Shorten OM-1 drive mode label to fit chip buttons (used for summary display). */
private fun usbDriveChipLabel(fullLabel: String): String = when {
    fullLabel.contains("Single", ignoreCase = true) && fullLabel.contains("Silent", ignoreCase = true) -> "Silent"
    fullLabel.contains("Single", ignoreCase = true) -> "Single"
    fullLabel.contains("Sequential L", ignoreCase = true) && fullLabel.contains("Silent", ignoreCase = true) -> "S. Seq L"
    fullLabel.contains("Sequential H2", ignoreCase = true) -> "Seq H2"
    fullLabel.contains("Sequential H", ignoreCase = true) && fullLabel.contains("Silent", ignoreCase = true) -> "S. Seq H"
    fullLabel.contains("Sequential L", ignoreCase = true) -> "Seq L"
    fullLabel.contains("Sequential H", ignoreCase = true) -> "Seq H"
    fullLabel.contains("Pro Capture Low", ignoreCase = true) -> "ProCap L"
    fullLabel.contains("Pro Capture SH1", ignoreCase = true) -> "ProCap SH1"
    fullLabel.contains("Pro Capture SH2", ignoreCase = true) -> "ProCap SH2"
    fullLabel.contains("Custom Self-timer", ignoreCase = true) -> "Custom Timer"
    fullLabel.contains("Self-timer Burst", ignoreCase = true) -> "Timer Burst"
    fullLabel.contains("Silent") && fullLabel.contains("Self-timer", ignoreCase = true) -> "S. Timer"
    fullLabel.contains("Self-timer", ignoreCase = true) -> "Timer"
    else -> fullLabel
}

@Composable
private fun DriveChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) AppleRed.copy(alpha = 0.18f) else Graphite)
            .clickable {
                // Stronger haptic for Drive/Timer mode changes — must be noticeable
                ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.SEGMENT_TICK)
                onClick()
            }
            .defaultMinSize(minHeight = 40.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) AppleRed else Chalk,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun ValueSlider(
    label: String,
    valueLabel: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    val view = LocalView.current
    var lastStep by remember { mutableIntStateOf(current.roundToInt()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.width(72.dp)) {
            Text(label.uppercase(), color = Chalk.copy(alpha = 0.48f), style = MaterialTheme.typography.labelSmall)
            Text(valueLabel, color = Chalk, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = current,
            onValueChange = { newValue ->
                val newStep = newValue.roundToInt()
                if (newStep != lastStep) {
                    val wasMajorBoundary = lastStep % 10 == 0 || newStep % 10 == 0
                    lastStep = newStep
                    if (wasMajorBoundary) {
                        ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.SEGMENT_TICK)
                    } else {
                        ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.SEGMENT_FREQUENT_TICK)
                    }
                }
                onValueChange(newValue)
            },
            modifier = Modifier.weight(1f),
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Chalk,
                activeTrackColor = AppleRed,
                inactiveTrackColor = Chalk.copy(alpha = 0.1f),
            ),
        )
    }
}

@Composable
private fun PropertyDial(
    prop: CameraPropertyValues,
    propName: String,
    reverseDial: Boolean,
    readableRotation: Int,
    onSelect: (String, Boolean) -> Unit,
) {
    val compactLandscape = rememberLandscapeControlLayout() || rememberRemoteQuarterTurn(readableRotation)
    val values = remember(
        prop.availableValues,
        if (prop.availableValues.isEmpty()) prop.currentValue else "",
        propName,
        reverseDial,
    ) {
        buildDialValues(prop, propName, reverseDial)
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val animatedRotation = rememberAnimatedReadableRotation(readableRotation, label = "dialRotation_$propName")
    val commitMode = remember(propName) {
        when (propName) {
            "isospeedvalue", "wbvalue" -> DialCommitMode.DelayedClose
            "focalvalue", "shutspeedvalue" -> DialCommitMode.ImmediateWithFollowup
            else -> DialCommitMode.ImmediateOnSettle
        }
    }
    var selectedValue by remember(propName, prop.currentValue, prop.autoActive, values) {
        mutableStateOf(
            when {
                propName == "isospeedvalue" && prop.autoActive ->
                    values.firstOrNull { PropertyFormatter.isAutoValue(propName, it) }
                        ?: prop.currentValue
                (propName == "focalvalue" || propName == "shutspeedvalue") &&
                    (!prop.enabled || isCameraReportedAutoValue(propName, prop)) ->
                    values.firstOrNull { isSyntheticDialAutoValue(propName, it) }
                        ?: prop.currentValue
                else -> prop.currentValue
            },
        )
    }
    var delayedCommitJob by remember(propName) { mutableStateOf<Job?>(null) }
    var awaitingScrollSettle by remember(propName) { mutableStateOf(false) }
    var lastCommittedValue by remember(propName) { mutableStateOf<String?>(null) }

    fun cameraValue(): String {
        return when {
            propName == "isospeedvalue" && prop.autoActive ->
                values.firstOrNull { PropertyFormatter.isAutoValue(propName, it) }
                    ?: prop.currentValue.takeIf { it.isNotBlank() }
                    ?: values.firstOrNull().orEmpty()
            (propName == "focalvalue" || propName == "shutspeedvalue") &&
                (!prop.enabled || isCameraReportedAutoValue(propName, prop)) ->
                values.firstOrNull { isSyntheticDialAutoValue(propName, it) }
                    ?: prop.currentValue.takeIf { it.isNotBlank() }
                    ?: values.firstOrNull().orEmpty()
            else -> prop.currentValue.takeIf { it.isNotBlank() } ?: values.firstOrNull().orEmpty()
        }
    }

    fun cancelDelayedCommit() {
        delayedCommitJob?.cancel()
        delayedCommitJob = null
    }

    fun commitSelectedValue(closePicker: Boolean, force: Boolean = false) {
        val target = selectedValue.ifBlank { cameraValue() }
        if (target.isBlank()) {
            return
        }
        if (!force && !closePicker && target.equals(lastCommittedValue, ignoreCase = true)) {
            return
        }
        lastCommittedValue = target
        onSelect(target, closePicker)
    }

    fun scheduleDelayedCommit() {
        if (commitMode == DialCommitMode.ImmediateOnSettle) {
            return
        }
        cancelDelayedCommit()
        delayedCommitJob = scope.launch {
            delay(3000L)
            when (commitMode) {
                DialCommitMode.DelayedClose -> commitSelectedValue(true)
                DialCommitMode.ImmediateWithFollowup -> commitSelectedValue(false, force = true)
                DialCommitMode.ImmediateOnSettle -> Unit
            }
        }
    }

    fun centeredIndex(): Int? {
        val layoutInfo = listState.layoutInfo
        val visibleItems = layoutInfo.visibleItemsInfo
        if (visibleItems.isEmpty()) {
            return null
        }
        val center = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
        return visibleItems
            .minByOrNull { item -> abs((item.offset + item.size / 2f) - center) }
            ?.index
    }

    LaunchedEffect(prop.currentValue, prop.autoActive, values) {
        if (values.isEmpty()) {
            return@LaunchedEffect
        }
        val cameraValue = cameraValue()
        val preserveDelayedCommit = cameraValue.equals(selectedValue, ignoreCase = true)
        if (!preserveDelayedCommit) {
            cancelDelayedCommit()
            awaitingScrollSettle = false
        }
        selectedValue = cameraValue
        lastCommittedValue = cameraValue
        val index = values.indexOf(cameraValue).takeIf { it >= 0 } ?: 0
        D.dial("DIAL[$propName] camera-sync: scrollToItem index=$index, value=$cameraValue")
        listState.scrollToItem(index)
    }

    LaunchedEffect(listState, values, propName) {
        snapshotFlow { centeredIndex() to listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { (index, scrolling) ->
                if (index == null || index !in values.indices) {
                    return@collect
                }
                val centeredValue = values[index]
                if (!centeredValue.equals(selectedValue, ignoreCase = true)) {
                    selectedValue = centeredValue
                    D.dial("DIAL[$propName] center-select index=$index value=$centeredValue scrolling=$scrolling")
                }
                if (!scrolling && awaitingScrollSettle) {
                    awaitingScrollSettle = false
                    when (commitMode) {
                        DialCommitMode.ImmediateOnSettle -> commitSelectedValue(false)
                        DialCommitMode.ImmediateWithFollowup -> {
                            commitSelectedValue(false)
                            scheduleDelayedCommit()
                        }
                        DialCommitMode.DelayedClose -> scheduleDelayedCommit()
                    }
                }
            }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val itemWidth = when {
            compactLandscape && propName == DEEP_SKY_FOCAL_LENGTH_PROP -> 126.dp
            compactLandscape && propName == "takemode" -> 132.dp
            compactLandscape && propName == "expcomp" -> 126.dp
            compactLandscape && (propName == "wbvalue" || propName == "isospeedvalue") -> 144.dp
            compactLandscape -> 128.dp
            propName == DEEP_SKY_FOCAL_LENGTH_PROP -> 94.dp
            propName == "wbvalue" || propName == "isospeedvalue" -> 96.dp
            else -> 86.dp
        }
        val indicatorHeight = if (compactLandscape) 74.dp else 46.dp
        val indicatorWidth = itemWidth - if (compactLandscape) 6.dp else 10.dp
        val padding = ((maxWidth - itemWidth) / 2).coerceAtLeast(0.dp)
        Box(
            modifier = Modifier
                .width(indicatorWidth)
                .height(indicatorHeight)
                .clip(RoundedCornerShape(18.dp))
                .border(1.5.dp, AppleRed, RoundedCornerShape(18.dp))
                .background(AppleRed.copy(alpha = 0.08f)),
        )
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = padding),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(propName) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        cancelDelayedCommit()
                        awaitingScrollSettle = false
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.any { it.pressed }) {
                                cancelDelayedCommit()
                            }
                        } while (event.changes.any { it.pressed })
                        awaitingScrollSettle = true
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(values) { index, value ->
                Box(
                    modifier = Modifier
                        .width(itemWidth)
                        .clickable {
                            selectedValue = value
                            D.dial("DIAL[$propName] click-select index=$index value=$value mode=$commitMode")
                            scope.launch { listState.animateScrollToItem(index) }
                            when (commitMode) {
                                DialCommitMode.ImmediateOnSettle,
                                DialCommitMode.ImmediateWithFollowup -> {
                                    cancelDelayedCommit()
                                    awaitingScrollSettle = true
                                }
                                DialCommitMode.DelayedClose -> {
                                    lastCommittedValue = null
                                    commitSelectedValue(false)
                                    awaitingScrollSettle = false
                                    scheduleDelayedCommit()
                                }
                            }
                        }
                        .graphicsLayer {
                            val center = listState.layoutInfo.viewportSize.width / 2f
                            val item = listState.layoutInfo.visibleItemsInfo.find { it.index == index }
                            if (item != null) {
                                val normalized = (abs((item.offset + item.size / 2f) - center) / (itemWidth.toPx() * 1.8f)).coerceIn(0f, 1f)
                                alpha = 1f - (normalized * 0.72f)
                                val scale = 1.04f - (normalized * 0.16f)
                                scaleX = scale
                                scaleY = scale
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val displayValue = when {
                        propName == DEEP_SKY_FOCAL_LENGTH_PROP -> {
                            parseDeepSkyFocalLengthMm(value)?.let(::formatDeepSkyFocalLengthMm) ?: value
                        }
                        propName.startsWith("usb_") -> {
                            val code = propName.removePrefix("usb_0x").toIntOrNull(16) ?: 0
                            formatScpPtpValue(
                                propCode = code,
                                value = value.toLongOrNull() ?: 0L,
                                options = prop.availableValues.mapNotNull { it.toLongOrNull() },
                            )
                        }
                        propName == "isospeedvalue" &&
                            prop.autoActive &&
                            (value.equals(prop.currentValue, ignoreCase = true) ||
                                PropertyFormatter.isAutoValue(propName, value)) -> "Auto"
                        isSyntheticDialAutoValue(propName, value) -> "Auto"
                        else -> PropertyFormatter.formatForDisplay(propName, value)
                    }.let { formatted ->
                        if (compactLandscape && propName == "takemode") {
                            when (formatted) {
                                TETHER_RETRY_MODE_DIAL_VALUE -> "Retry"
                                TETHER_MODE_DIAL_VALUE -> "USB"
                                DEEP_SKY_MODE_DIAL_VALUE -> "Sky"
                                else -> formatted
                            }
                        } else {
                            formatted
                        }
                    }
                    val isSelected = value.equals(selectedValue, ignoreCase = true)
                    Text(
                        text = displayValue,
                        modifier = Modifier
                            .graphicsLayer { rotationZ = animatedRotation }
                            .widthIn(min = if (compactLandscape) itemWidth - 8.dp else 0.dp)
                            .padding(horizontal = if (compactLandscape) 10.dp else 0.dp),
                        color = when {
                            isSelected -> AppleRed
                            displayValue.equals("Auto", ignoreCase = true) -> AppleRed
                            else -> Chalk
                        },
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        style = if (compactLandscape) {
                            MaterialTheme.typography.titleMedium.copy(
                                fontSize = if (propName == "takemode") 10.sp else 9.sp,
                                lineHeight = if (propName == "takemode") 11.sp else 10.sp,
                            )
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private enum class DialCommitMode {
    ImmediateOnSettle,
    ImmediateWithFollowup,
    DelayedClose,
}

private fun buildDialValues(
    prop: CameraPropertyValues,
    propName: String,
    reverseDial: Boolean,
): List<String> {
    val source = prop.availableValues.ifEmpty { listOf(prop.currentValue.ifBlank { "0.0" }) }
        .map { it.trim() }
        .filter { it.isNotBlank() }
    if (source.isEmpty()) {
        return emptyList()
    }
    val baseValues = if (reverseDial) source.distinct().asReversed() else source.distinct()
    val syntheticAuto = syntheticDialAutoValue(propName) ?: return baseValues
    val numericCandidates = baseValues.mapIndexedNotNull { index, value ->
        canonicalDialNumericValue(propName, value)?.let { numeric -> index to numeric }
    }
    if (numericCandidates.isEmpty()) {
        return baseValues
    }
    val anchorIndex = when (propName) {
        "focalvalue" -> numericCandidates.minByOrNull { it.second }?.first ?: 0
        "shutspeedvalue" -> numericCandidates.maxByOrNull { it.second }?.first ?: baseValues.lastIndex
        else -> return baseValues
    }
    return when {
        anchorIndex <= 0 -> listOf(syntheticAuto) + baseValues
        anchorIndex >= baseValues.lastIndex -> baseValues + syntheticAuto
        anchorIndex < baseValues.size / 2 -> buildList {
            addAll(baseValues.take(anchorIndex))
            add(syntheticAuto)
            addAll(baseValues.drop(anchorIndex))
        }
        else -> buildList {
            addAll(baseValues.take(anchorIndex + 1))
            add(syntheticAuto)
            addAll(baseValues.drop(anchorIndex + 1))
        }
    }
}

private fun isQuarterTurnRotation(readableRotation: Int): Boolean {
    return readableRotation == 90 || readableRotation == -90
}

private fun buildModeDialValues(
    takeMode: CameraPropertyValues,
    fallbackMode: CameraExposureMode,
    includeTetherOption: Boolean = false,
    includeTetherRetryOption: Boolean = false,
    includeDeepSkyOption: Boolean = false,
    preferFullModeFallback: Boolean = false,
    usbTetherActionOnly: Boolean = false,
): CameraPropertyValues {
    val availableModes = takeMode.availableValues
        .mapNotNull(::modeFromTakeModeRaw)
        .distinct()
        .map(CameraExposureMode::label)
        .ifEmpty { CameraExposureMode.entries.map(CameraExposureMode::label) }
        .let { resolved ->
            if (preferFullModeFallback && resolved.size <= 1) {
                CameraExposureMode.entries.map(CameraExposureMode::label)
            } else {
                resolved
            }
        }
    val currentMode = modeFromTakeModeRaw(takeMode.currentValue)?.label ?: fallbackMode.label
    if (usbTetherActionOnly) {
        return CameraPropertyValues(
            currentValue = currentMode,
            availableValues = buildList {
                add(currentMode)
                if (includeTetherOption) {
                    add(TETHER_MODE_DIAL_VALUE)
                }
                if (includeTetherRetryOption) {
                    add(TETHER_RETRY_MODE_DIAL_VALUE)
                }
                if (includeDeepSkyOption) {
                    add(DEEP_SKY_MODE_DIAL_VALUE)
                }
            }.distinct(),
        )
    }
    val surfacedModes = buildList {
        addAll(availableModes)
        if (includeTetherOption) {
            add(TETHER_MODE_DIAL_VALUE)
        }
        if (includeTetherRetryOption) {
            add(TETHER_RETRY_MODE_DIAL_VALUE)
        }
        // Deep Sky is only available when USB tether is connected
        if (includeDeepSkyOption) {
            add(DEEP_SKY_MODE_DIAL_VALUE)
        }
    }.distinct()
    return CameraPropertyValues(
        currentValue = currentMode,
        availableValues = surfacedModes,
    )
}

private fun buildDeepSkyFocalLengthPropertyValues(
    currentFocalLengthMm: Float,
): CameraPropertyValues {
    val normalizedCurrent = currentFocalLengthMm
        .coerceIn(7f, 600f)
        .roundToInt()
    val focalLengths = buildSet {
        addAll(7..24)
        addAll(25..100 step 5)
        addAll(110..300 step 10)
        addAll(350..600 step 50)
        add(normalizedCurrent)
    }.sorted()
    return CameraPropertyValues(
        currentValue = formatDeepSkyFocalLengthMm(normalizedCurrent.toFloat()),
        availableValues = focalLengths.map { formatDeepSkyFocalLengthMm(it.toFloat()) },
        enabled = true,
    )
}

private fun parseDeepSkyFocalLengthMm(value: String): Float? {
    return value
        .trim()
        .removeSuffix("mm")
        .removeSuffix(" mm")
        .trim()
        .toFloatOrNull()
        ?.takeIf { it > 0f }
}

private fun formatDeepSkyFocalLengthMm(focalLengthMm: Float): String {
    val rounded = focalLengthMm.roundToInt()
    return "$rounded mm"
}

private fun modeFromTakeModeRaw(rawMode: String): CameraExposureMode? {
    val normalized = rawMode.trim().uppercase()
    return when {
        normalized == "P" || normalized == "PS" || "PROGRAM" in normalized -> CameraExposureMode.P
        normalized == "A" || "APERTURE" in normalized -> CameraExposureMode.A
        normalized == "S" || "SHUTTER" in normalized -> CameraExposureMode.S
        normalized == "M" || "MANUAL" in normalized -> CameraExposureMode.M
        normalized == "B" || "BULB" in normalized || "TIME" in normalized || "COMP" in normalized -> CameraExposureMode.B
        "MOVIE" in normalized || "VIDEO" in normalized -> CameraExposureMode.VIDEO
        else -> null
    }
}

private fun buildUsbExposureScaleValues(): List<String> {
    return (-15..15)
        .map { step -> formatOlympusExposureComp(((step / 3.0) * 10.0).roundToInt().toLong()) }
        .map(::normalizeExposureDialValue)
        .distinct()
}

private fun normalizeExposureDialValue(value: String): String {
    return PropertyFormatter.exposureCompensationDisplayValue(value).ifBlank { value.ifBlank { "0.0" } }
}

private fun findExposureScaleIndex(
    values: List<String>,
    currentValue: String,
): Int {
    val exactIndex = values.indexOfFirst { it.equals(currentValue, ignoreCase = true) }
    if (exactIndex >= 0) {
        return exactIndex
    }
    val currentNumeric = currentValue.toDoubleOrNull()
    if (currentNumeric != null) {
        val nearestIndex = values.withIndex()
            .mapNotNull { indexed ->
                indexed.value.toDoubleOrNull()?.let { indexed.index to kotlin.math.abs(it - currentNumeric) }
            }
            .minByOrNull { it.second }
            ?.first
        if (nearestIndex != null) {
            return nearestIndex
        }
    }
    return values.indexOf("0.0").takeIf { it >= 0 } ?: 0
}

@Composable
private fun DriveGlyph(
    shootingMode: RemoteShootingMode,
    driveMode: DriveMode,
    tint: Color,
) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val stroke = 2.dp.toPx()
        if (shootingMode == RemoteShootingMode.Interval) {
            drawCircle(tint, radius = size.minDimension * 0.33f, style = Stroke(width = stroke))
            drawLine(tint, Offset(size.width / 2f, size.height / 2f), Offset(size.width / 2f, size.height * 0.28f), stroke)
            drawLine(tint, Offset(size.width / 2f, size.height / 2f), Offset(size.width * 0.67f, size.height / 2f), stroke)
            return@Canvas
        }
        when (driveMode) {
            DriveMode.Single -> drawCircle(tint, radius = size.minDimension * 0.16f)
            DriveMode.Silent -> {
                drawCircle(tint, radius = size.minDimension * 0.16f)
                drawLine(tint, Offset(size.width * 0.22f, size.height * 0.78f), Offset(size.width * 0.78f, size.height * 0.22f), stroke)
            }
            DriveMode.Burst -> repeat(3) { index ->
                drawCircle(tint, radius = size.minDimension * 0.11f, center = Offset(size.width * (0.28f + (index * 0.22f)), size.height / 2f))
            }
            DriveMode.SilentBurst -> {
                repeat(3) { index ->
                    drawCircle(tint, radius = size.minDimension * 0.11f, center = Offset(size.width * (0.28f + (index * 0.22f)), size.height / 2f))
                }
                drawLine(tint, Offset(size.width * 0.18f, size.height * 0.8f), Offset(size.width * 0.82f, size.height * 0.2f), stroke)
            }
        }
    }
}

private fun rangeLabel(propName: String, values: CameraPropertyValues): String {
    val actual = values.availableValues.filterNot {
        PropertyFormatter.isAutoValue(propName, it) || isSyntheticDialAutoValue(propName, it)
    }
    if (actual.isEmpty()) return ""
    if (propName == "takemode") return ""
    if (propName == DEEP_SKY_FOCAL_LENGTH_PROP) return "7 mm - 600 mm"
    if (propName == "wbvalue") return "${actual.size} presets"
    if (propName.startsWith("usb_0x")) {
        val propCode = propName.removePrefix("usb_0x").toIntOrNull(16) ?: return ""
        val rawValues = actual.mapNotNull(String::toLongOrNull)
        if (rawValues.isEmpty()) return ""
        return "${formatScpPtpValue(propCode, rawValues.first(), rawValues)} - " +
            formatScpPtpValue(propCode, rawValues.last(), rawValues)
    }
    return "${PropertyFormatter.formatForDisplay(propName, actual.first())} - ${PropertyFormatter.formatForDisplay(propName, actual.last())}"
}

private fun canonicalDialNumericValue(propName: String, value: String): Double? {
    if (isSyntheticDialAutoValue(propName, value)) {
        return null
    }
    if (propName == "expcomp") {
        return PropertyFormatter.exposureCompensationNumericValue(value)
    }
    val normalized = value
        .trim()
        .removePrefix("F")
        .removePrefix("f")
        .removeSuffix(" sec")
        .removeSuffix(" SEC")
        .removeSuffix("s")
        .removeSuffix("S")
        .takeIf { it.isNotBlank() }
        ?: return null
    if (normalized.endsWith("\"") || normalized.endsWith("\u201D")) {
        val raw = normalized.removeSuffix("\"").removeSuffix("\u201D")
        return raw.toDoubleOrNull()?.let { -it }
    }
    return when {
        normalized.startsWith("1/") -> normalized.substringAfter("1/").toDoubleOrNull()
        else -> normalized.toDoubleOrNull()
    }
}

private fun currentDriveSummary(runtime: RemoteRuntimeState): String {
    return if (runtime.shootingMode == RemoteShootingMode.Interval) "Interval" else driveLabel(runtime.driveMode)
}

private fun driveLabel(mode: DriveMode): String = when (mode) {
    DriveMode.Single -> "Single"
    DriveMode.Silent -> "Silent"
    DriveMode.Burst -> "Burst"
    DriveMode.SilentBurst -> "S.Burst"
}

private fun timerLabel(mode: TimerMode, delay: Int): String = when (mode) {
    TimerMode.Off -> "Timer Off"
    TimerMode.Timer -> "${delay}s"
    TimerMode.BurstTimer -> "Burst Timer"
}
