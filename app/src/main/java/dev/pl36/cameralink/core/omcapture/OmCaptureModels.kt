package dev.pl36.cameralink.core.omcapture

import dev.pl36.cameralink.core.model.ActivePropertyPicker
import dev.pl36.cameralink.core.model.CameraExposureMode
import dev.pl36.cameralink.core.model.DriveMode
import dev.pl36.cameralink.core.model.TetherPhoneImportFormat
import dev.pl36.cameralink.core.model.TetherSaveTarget
import dev.pl36.cameralink.core.model.TimerMode

enum class OmCaptureSection(
    val title: String,
    val subtitle: String,
) {
    Connection(
        title = "Connection",
        subtitle = "USB/PTP bring-up, link handoff, and camera session readiness",
    ),
    LiveView(
        title = "Live View",
        subtitle = "USB/PTP live preview and session flow",
    ),
    AfMf(
        title = "AF / MF",
        subtitle = "Focus, one-touch WB, AF target, and subject-detection controls",
    ),
    Exposure(
        title = "Exposure",
        subtitle = "Exposure mode, shutter, aperture, ISO, white balance, and metering",
    ),
    DriveComputational(
        title = "Drive / Computational",
        subtitle = "Drive modes, bracketing, high-res, interval, bulb, and composite flows",
    ),
    Movie(
        title = "Movie",
        subtitle = "Movie-specific settings, save slot, stabilization, and record control",
    ),
    FileSave(
        title = "File Save",
        subtitle = "Save targets and import behavior for Android storage",
    ),
    TransferLink(
        title = "Transfer / Link",
        subtitle = "USB import, Wi-Fi link management, export, and library handoff",
    ),
    Support(
        title = "Support",
        subtitle = "Desktop-only utilities, diagnostics, and update/help surfaces",
    ),
}

enum class OmCaptureSupportState(
    val label: String,
) {
    Supported("Supported"),
    Unsupported("Unsupported"),
    NotTraced("Validation pending"),
    NotApplicable("Not applicable"),
}

enum class OmCaptureAndroidPolicy(
    val label: String,
) {
    Native("Native Android"),
    Adapted("Android adapted"),
    DesktopOnly("Desktop-only"),
    Diagnostics("Diagnostics"),
}

enum class OmCaptureImplementationStatus(
    val label: String,
) {
    Implemented("Implemented"),
    Adapted("Adapted"),
    DesktopOnly("Desktop-only"),
    NotApplicable("Not applicable"),
    BlockedByMissingTrace("Validation pending"),
}

data class OmCaptureFeatureDescriptor(
    val id: String,
    val originalLabel: String,
    val originalGroup: String,
    val evidenceSource: String,
    val ptpReferences: List<String>,
    val om1SupportState: OmCaptureSupportState,
    val androidPolicy: OmCaptureAndroidPolicy,
    val uiSection: OmCaptureSection,
    val implementationStatus: OmCaptureImplementationStatus,
    val summary: String,
)

data class OmCaptureSectionSummary(
    val section: OmCaptureSection,
    val featureCount: Int,
    val implementedCount: Int,
    val adaptedCount: Int,
    val blockedCount: Int,
)

data class OmCaptureControlValue(
    val label: String,
    val value: String,
    val detail: String? = null,
)

data class OmCaptureStudioUiState(
    val selectedSection: OmCaptureSection = OmCaptureSection.Connection,
    val sectionSummaries: List<OmCaptureSectionSummary> = emptyList(),
    val features: List<OmCaptureFeatureDescriptor> = emptyList(),
    val connectionLabel: String = "No compatible camera detected over USB",
    val deviceLabel: String? = null,
    val lastActionLabel: String? = null,
    val controlValues: List<OmCaptureControlValue> = emptyList(),
    val liveViewActive: Boolean = false,
    val tetherSaveTarget: TetherSaveTarget = TetherSaveTarget.SdAndPhone,
    val phoneImportFormat: TetherPhoneImportFormat = TetherPhoneImportFormat.JpegOnly,
    val remoteControlReady: Boolean = false,
    val remoteStatusLabel: String = "Reconnect to the camera before changing settings",
    val totalFeatureCount: Int = 0,
    val implementedCount: Int = 0,
    val adaptedCount: Int = 0,
    val blockedCount: Int = 0,
) {
    val visibleFeatures: List<OmCaptureFeatureDescriptor>
        get() = features.filter { it.uiSection == selectedSection }
}

sealed interface OmCaptureAction {
    data class SelectSection(val section: OmCaptureSection) : OmCaptureAction

    data class SetSaveTarget(val target: TetherSaveTarget) : OmCaptureAction

    data class SetPhoneImportFormat(val format: TetherPhoneImportFormat) : OmCaptureAction

    object RefreshCapabilities : OmCaptureAction

    object RefreshUsbConnection : OmCaptureAction

    object ToggleUsbLiveView : OmCaptureAction

    object CaptureRemotePhoto : OmCaptureAction

    object CaptureImport : OmCaptureAction

    object ImportLatest : OmCaptureAction

    object ClearUsbStatus : OmCaptureAction

    data class SetExposureCompensation(val value: String) : OmCaptureAction

    data class SetCameraExposureMode(val mode: CameraExposureMode) : OmCaptureAction

    data class SetPropertyValue(
        val picker: ActivePropertyPicker,
        val value: String,
        val closePicker: Boolean = false,
    ) : OmCaptureAction

    data class SetDriveMode(val mode: DriveMode) : OmCaptureAction

    data class SetTimerMode(val mode: TimerMode) : OmCaptureAction

    data class SetTimerDelay(val seconds: Int) : OmCaptureAction

    data class SetIntervalSeconds(val seconds: Int) : OmCaptureAction

    data class SetIntervalCount(val count: Int) : OmCaptureAction

    object StartInterval : OmCaptureAction

    object StopInterval : OmCaptureAction

    /** USB: read all camera properties via PTP */
    object RefreshUsbProperties : OmCaptureAction

    /** USB: set a camera property via PTP */
    data class SetUsbProperty(val propCode: Int, val value: Long) : OmCaptureAction

    /** USB: manual focus drive (positive = near, negative = far) */
    data class ManualFocusDrive(val steps: Int) : OmCaptureAction

    /** USB: capture while live view is active */
    object CaptureWhileLiveView : OmCaptureAction
}
