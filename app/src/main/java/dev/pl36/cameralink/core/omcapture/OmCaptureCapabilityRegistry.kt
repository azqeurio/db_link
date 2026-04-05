package dev.pl36.cameralink.core.omcapture

import dev.pl36.cameralink.core.model.RemoteRuntimeState
import dev.pl36.cameralink.core.model.TetherPhoneImportFormat
import dev.pl36.cameralink.core.model.TetherSaveTarget
import dev.pl36.cameralink.core.protocol.PropertyFormatter

object OmCaptureCapabilityRegistry {

    private val features: List<OmCaptureFeatureDescriptor> = listOf(
        feature("usb_connection", "USB Connection", "Connection", "Observed USB/PTP session behavior", listOf("GetDeviceInfo", "OpenSession"), OmCaptureSupportState.Supported, OmCaptureAndroidPolicy.Native, OmCaptureSection.Connection, OmCaptureImplementationStatus.Implemented, "USB/PTP session startup is available."),
        feature("wifi_connection", "Wi-Fi Connection", "Connection", "Observed Wi-Fi control behavior", listOf("existing Wi-Fi remote"), OmCaptureSupportState.Supported, OmCaptureAndroidPolicy.Adapted, OmCaptureSection.Connection, OmCaptureImplementationStatus.Adapted, "Uses the existing app Wi-Fi flow."),
        feature("create_new_link", "Create New Link", "Connection", "Onboarding and saved-camera workflow", listOf("saved camera identity"), OmCaptureSupportState.Supported, OmCaptureAndroidPolicy.Adapted, OmCaptureSection.Connection, OmCaptureImplementationStatus.Adapted, "Mapped to QR onboarding."),
        feature("edit_existing_links", "Edit Existing Links", "Connection", "Saved-camera workflow", listOf("saved camera profiles"), OmCaptureSupportState.Supported, OmCaptureAndroidPolicy.Adapted, OmCaptureSection.Connection, OmCaptureImplementationStatus.Adapted, "Mapped to saved cameras."),

        feature("show_live_view", "Live Preview", "Live Preview", "Observed USB live-preview startup", listOf("ChangeRunMode", "LiveViewEnabled", "GetChangedProperties", "CreateRecView", "GetLiveViewImage"), OmCaptureSupportState.Supported, OmCaptureAndroidPolicy.Native, OmCaptureSection.LiveView, OmCaptureImplementationStatus.Implemented, "USB live preview follows the tested startup flow."),
        feature("rec_view", "Preview Pane", "Live Preview", "Observed frame delivery behavior", listOf("CreateRecView", "GetLiveViewImage"), OmCaptureSupportState.Supported, OmCaptureAndroidPolicy.Adapted, OmCaptureSection.LiveView, OmCaptureImplementationStatus.Adapted, "Preview frames are shown inline on Android."),
        feature("live_view_size", "Preview Size", "Live Preview", "Observed live-preview mode switching", listOf("LiveViewModeOm (0xD06D)"), OmCaptureSupportState.Supported, OmCaptureAndroidPolicy.Adapted, OmCaptureSection.LiveView, OmCaptureImplementationStatus.Adapted, "The app cycles supported preview sizes automatically."),
        feature("live_view_boost", "Live View Boost", "Live Preview", "Additional device validation required", listOf("validation pending"), OmCaptureSupportState.NotTraced, OmCaptureAndroidPolicy.Diagnostics, OmCaptureSection.LiveView, OmCaptureImplementationStatus.BlockedByMissingTrace, "Additional device validation is needed."),
        feature("lv_mode", "LV Mode / S-OVF", "Live Preview", "Additional device validation required", listOf("validation pending"), OmCaptureSupportState.NotTraced, OmCaptureAndroidPolicy.Diagnostics, OmCaptureSection.LiveView, OmCaptureImplementationStatus.BlockedByMissingTrace, "Additional device validation is needed."),

        feature("full_press_shutter", "Full Shutter Press", "AF / MF / Target", "Observed remote capture behavior", listOf("existing remote capture pipeline"), OmCaptureSupportState.Supported, OmCaptureAndroidPolicy.Adapted, OmCaptureSection.AfMf, OmCaptureImplementationStatus.Adapted, "Mapped to the existing remote shutter flow."),
        feature("half_press_shutter", "Half Shutter Press", "AF / MF / Target", "Additional device validation required", listOf("touch focus / hold focus"), OmCaptureSupportState.NotTraced, OmCaptureAndroidPolicy.Diagnostics, OmCaptureSection.AfMf, OmCaptureImplementationStatus.BlockedByMissingTrace, "Further validation is needed before enabling hold-focus controls."),
        feature("one_touch_wb", "One Touch WB", "AF / MF / Target", "Additional device validation required", listOf("SetOneTouchWBGain"), OmCaptureSupportState.NotTraced, OmCaptureAndroidPolicy.Diagnostics, OmCaptureSection.AfMf, OmCaptureImplementationStatus.BlockedByMissingTrace, "Further validation is needed before enabling this control."),
        feature("manual_focus", "Manual Focus", "AF / MF / Target", "Additional device validation required", listOf("FocusDistance", "focus drive validation"), OmCaptureSupportState.NotTraced, OmCaptureAndroidPolicy.Diagnostics, OmCaptureSection.AfMf, OmCaptureImplementationStatus.BlockedByMissingTrace, "Further validation is needed before enabling this control."),
        feature("mf_focus_adj", "MF Focus Adj.", "AF / MF / Target", "Additional device validation required", listOf("FocusDistance", "step validation"), OmCaptureSupportState.NotTraced, OmCaptureAndroidPolicy.Diagnostics, OmCaptureSection.AfMf, OmCaptureImplementationStatus.BlockedByMissingTrace, "Further validation is needed before enabling this control."),
        feature("wb_comp", "WB Comp.", "AF / MF / Target", "Additional device validation required", listOf("validation pending"), OmCaptureSupportState.NotTraced, OmCaptureAndroidPolicy.Diagnostics, OmCaptureSection.AfMf, OmCaptureImplementationStatus.BlockedByMissingTrace, "Further validation is needed before enabling this control."),
        feature("subject_detection", "Subject Detection", "AF / MF / Target", "Additional device validation required", listOf("validation pending"), OmCaptureSupportState.NotTraced, OmCaptureAndroidPolicy.Diagnostics, OmCaptureSection.AfMf, OmCaptureImplementationStatus.BlockedByMissingTrace, "Further validation is needed before enabling this control."),
        feature("starry_sky_af", "Starry Sky AF", "AF / MF / Target", "Additional device validation required", listOf("validation pending"), OmCaptureSupportState.NotTraced, OmCaptureAndroidPolicy.Diagnostics, OmCaptureSection.AfMf, OmCaptureImplementationStatus.BlockedByMissingTrace, "Further validation is needed before enabling this control."),
        feature("af_target", "AF Target / Custom Target", "AF / MF / Target", "Additional device validation required", listOf("NotifyAfTargetFrame", "target validation"), OmCaptureSupportState.NotTraced, OmCaptureAndroidPolicy.Diagnostics, OmCaptureSection.AfMf, OmCaptureImplementationStatus.BlockedByMissingTrace, "Further validation is needed before enabling this control."),

        feature("exposure_mode", "Exposure Mode", "Exposure", "Observed property handling", listOf("ExposureMode"), OmCaptureSupportState.Supported, OmCaptureAndroidPolicy.Adapted, OmCaptureSection.Exposure, OmCaptureImplementationStatus.Adapted, "Existing remote state is mirrored here."),
        feature("shutter_speed", "Shutter Speed", "Exposure", "Observed property handling", listOf("ShutterSpeed"), OmCaptureSupportState.Supported, OmCaptureAndroidPolicy.Adapted, OmCaptureSection.Exposure, OmCaptureImplementationStatus.Adapted, "Existing remote state is mirrored here."),
        feature("aperture", "Aperture", "Exposure", "Observed property handling", listOf("Aperture"), OmCaptureSupportState.Supported, OmCaptureAndroidPolicy.Adapted, OmCaptureSection.Exposure, OmCaptureImplementationStatus.Adapted, "Existing remote state is mirrored here."),
        feature("iso", "ISO", "Exposure", "Observed property handling", listOf("ISOSpeed"), OmCaptureSupportState.Supported, OmCaptureAndroidPolicy.Adapted, OmCaptureSection.Exposure, OmCaptureImplementationStatus.Adapted, "Existing remote state is mirrored here."),
        feature("white_balance", "White Balance", "Exposure", "Observed property handling", listOf("WhiteBalance"), OmCaptureSupportState.Supported, OmCaptureAndroidPolicy.Adapted, OmCaptureSection.Exposure, OmCaptureImplementationStatus.Adapted, "Existing remote state is mirrored here."),
        feature("exposure_compensation", "Exposure Compensation", "Exposure", "Observed property handling", listOf("expcomp"), OmCaptureSupportState.Supported, OmCaptureAndroidPolicy.Adapted, OmCaptureSection.Exposure, OmCaptureImplementationStatus.Adapted, "Existing EV control is mirrored here."),
        feature("raw_recording_bit", "RAW Recording Bit", "Exposure", "Additional device validation required", listOf("validation pending"), OmCaptureSupportState.NotTraced, OmCaptureAndroidPolicy.Diagnostics, OmCaptureSection.Exposure, OmCaptureImplementationStatus.BlockedByMissingTrace, "Further validation is needed before enabling this control."),

        feature("drive_mode", "Drive Mode", "Drive / Computational", "Observed property handling", listOf("DriveMode"), OmCaptureSupportState.Supported, OmCaptureAndroidPolicy.Adapted, OmCaptureSection.DriveComputational, OmCaptureImplementationStatus.Adapted, "Existing drive state is mirrored here."),
        feature("timer_mode", "Self-timer / Anti-Shock", "Drive / Computational", "Observed timer and drive handling", listOf("DriveMode", "TimerMode"), OmCaptureSupportState.Supported, OmCaptureAndroidPolicy.Adapted, OmCaptureSection.DriveComputational, OmCaptureImplementationStatus.Adapted, "Mapped to the existing timer and drive-selection logic."),
        feature("hdr", "HDR", "Drive / Computational", "Additional device validation required", listOf("validation pending"), OmCaptureSupportState.NotTraced, OmCaptureAndroidPolicy.Diagnostics, OmCaptureSection.DriveComputational, OmCaptureImplementationStatus.BlockedByMissingTrace, "Additional device validation is needed."),
        feature("bracketing", "Bracketing", "Drive / Computational", "Additional device validation required", listOf("SetProperties batch", "validation pending"), OmCaptureSupportState.NotTraced, OmCaptureAndroidPolicy.Diagnostics, OmCaptureSection.DriveComputational, OmCaptureImplementationStatus.BlockedByMissingTrace, "Additional device validation is needed."),
        feature("focus_stacking", "Focus Stacking", "Drive / Computational", "Additional device validation required", listOf("validation pending"), OmCaptureSupportState.NotTraced, OmCaptureAndroidPolicy.Diagnostics, OmCaptureSection.DriveComputational, OmCaptureImplementationStatus.BlockedByMissingTrace, "Additional device validation is needed."),
        feature("high_res_shot", "High Res Shot", "Drive / Computational", "Additional device validation required", listOf("validation pending"), OmCaptureSupportState.NotTraced, OmCaptureAndroidPolicy.Diagnostics, OmCaptureSection.DriveComputational, OmCaptureImplementationStatus.BlockedByMissingTrace, "Additional device validation is needed."),
        feature("pro_capture", "Pro Capture", "Drive / Computational", "Additional device validation required", listOf("validation pending"), OmCaptureSupportState.NotTraced, OmCaptureAndroidPolicy.Diagnostics, OmCaptureSection.DriveComputational, OmCaptureImplementationStatus.BlockedByMissingTrace, "Additional device validation is needed."),
        feature("interval_shooting", "Interval Shooting", "Drive / Computational", "Observed interval workflow", listOf("existing interval engine", "validation pending"), OmCaptureSupportState.Supported, OmCaptureAndroidPolicy.Adapted, OmCaptureSection.DriveComputational, OmCaptureImplementationStatus.Adapted, "The current interval flow is preserved."),
        feature("time_lapse", "Time Lapse", "Drive / Computational", "Observed interval workflow", listOf("existing interval engine"), OmCaptureSupportState.Supported, OmCaptureAndroidPolicy.Adapted, OmCaptureSection.DriveComputational, OmCaptureImplementationStatus.Adapted, "The current interval flow is preserved."),
        feature("live_bulb_time_composite", "Live Bulb / Live Time / Composite", "Drive / Computational", "Additional device validation required", listOf("OMD.Capture bulb params", "validation pending"), OmCaptureSupportState.NotTraced, OmCaptureAndroidPolicy.Diagnostics, OmCaptureSection.DriveComputational, OmCaptureImplementationStatus.BlockedByMissingTrace, "Additional device validation is needed."),

        feature("movie_settings", "Movie Settings", "Movie", "Additional device validation required", listOf("validation pending"), OmCaptureSupportState.NotTraced, OmCaptureAndroidPolicy.Diagnostics, OmCaptureSection.Movie, OmCaptureImplementationStatus.BlockedByMissingTrace, "Additional device validation is needed."),
        feature("movie_af_mode", "Movie AF Mode", "Movie", "Additional device validation required", listOf("validation pending"), OmCaptureSupportState.NotTraced, OmCaptureAndroidPolicy.Diagnostics, OmCaptureSection.Movie, OmCaptureImplementationStatus.BlockedByMissingTrace, "Additional device validation is needed."),
        feature("movie_resolution", "Movie Resolution", "Movie", "Additional device validation required", listOf("validation pending"), OmCaptureSupportState.NotTraced, OmCaptureAndroidPolicy.Diagnostics, OmCaptureSection.Movie, OmCaptureImplementationStatus.BlockedByMissingTrace, "Additional device validation is needed."),
        feature("movie_save_slot", "Movie Save Slot", "Movie", "Additional device validation required", listOf("validation pending"), OmCaptureSupportState.NotTraced, OmCaptureAndroidPolicy.Diagnostics, OmCaptureSection.Movie, OmCaptureImplementationStatus.BlockedByMissingTrace, "Additional device validation is needed."),
        feature("movie_is", "Movie IS", "Movie", "Additional device validation required", listOf("validation pending"), OmCaptureSupportState.NotTraced, OmCaptureAndroidPolicy.Diagnostics, OmCaptureSection.Movie, OmCaptureImplementationStatus.BlockedByMissingTrace, "Additional device validation is needed."),
        feature("recording_volume", "Recording Volume", "Movie", "Additional device validation required", listOf("validation pending"), OmCaptureSupportState.NotTraced, OmCaptureAndroidPolicy.Diagnostics, OmCaptureSection.Movie, OmCaptureImplementationStatus.BlockedByMissingTrace, "Additional device validation is needed."),

        feature("file_save_settings", "Save Settings", "Save / Transfer", "Observed Android save behavior", listOf("Android save target policy"), OmCaptureSupportState.Supported, OmCaptureAndroidPolicy.Adapted, OmCaptureSection.FileSave, OmCaptureImplementationStatus.Implemented, "Mapped to Android save policy."),
        feature("file_save_priority", "Save Priority", "Save / Transfer", "Observed save-target workflow", listOf("SD / SD + Phone / Phone"), OmCaptureSupportState.Supported, OmCaptureAndroidPolicy.Adapted, OmCaptureSection.FileSave, OmCaptureImplementationStatus.Implemented, "Save-target priority is configurable."),
        feature("file_save_quick_setup", "Quick Setup", "Save / Transfer", "Observed Android control surface", listOf("Android control surface"), OmCaptureSupportState.Supported, OmCaptureAndroidPolicy.Adapted, OmCaptureSection.FileSave, OmCaptureImplementationStatus.Adapted, "Shown in the Studio screen."),
        feature("capture_import", "Capture and Import", "Transfer", "Observed USB capture/import workflow", listOf("InitiateCapture/OMD.Capture", "ObjectAdded", "GetThumb", "GetObject"), OmCaptureSupportState.Supported, OmCaptureAndroidPolicy.Native, OmCaptureSection.TransferLink, OmCaptureImplementationStatus.Implemented, "Event-driven USB capture/import is available."),
        feature("import_latest", "Import Latest Existing Image", "Transfer", "Observed USB object enumeration", listOf("GetObjectHandles", "GetObjectInfo", "GetThumb", "GetObject"), OmCaptureSupportState.Supported, OmCaptureAndroidPolicy.Native, OmCaptureSection.TransferLink, OmCaptureImplementationStatus.Implemented, "Imports the most recent image based on available metadata."),
        feature("export_now_later", "Export Now / Export Later", "Transfer", "Observed Android share workflow", listOf("gallery/share handoff"), OmCaptureSupportState.Supported, OmCaptureAndroidPolicy.Adapted, OmCaptureSection.TransferLink, OmCaptureImplementationStatus.Adapted, "Adapted to Android gallery handoff."),

        feature("windows_firewall", "Windows Firewall Settings", "Support", "Platform-specific desktop utility", listOf("None"), OmCaptureSupportState.NotApplicable, OmCaptureAndroidPolicy.DesktopOnly, OmCaptureSection.Support, OmCaptureImplementationStatus.DesktopOnly, "Desktop-only."),
        feature("multi_monitor", "Multi-monitor Settings", "Support", "Platform-specific desktop utility", listOf("None"), OmCaptureSupportState.NotApplicable, OmCaptureAndroidPolicy.DesktopOnly, OmCaptureSection.Support, OmCaptureImplementationStatus.DesktopOnly, "Desktop-only."),
        feature("update_camera_software", "Update Camera / Update Software", "Support", "Platform-specific desktop utility", listOf("None"), OmCaptureSupportState.NotApplicable, OmCaptureAndroidPolicy.DesktopOnly, OmCaptureSection.Support, OmCaptureImplementationStatus.DesktopOnly, "Desktop-only."),
        feature("help", "Studio Help", "Support", "Inline guidance and validation notes", listOf("None"), OmCaptureSupportState.Supported, OmCaptureAndroidPolicy.Adapted, OmCaptureSection.Support, OmCaptureImplementationStatus.Adapted, "Guidance is shown inline.")
    )

    fun defaultState(): OmCaptureStudioUiState = buildUiState(
        selectedSection = OmCaptureSection.Connection,
        connectionLabel = "No compatible camera detected over USB",
        deviceLabel = null,
        lastActionLabel = null,
        liveViewActive = false,
        tetherSaveTarget = TetherSaveTarget.SdAndPhone,
        phoneImportFormat = TetherPhoneImportFormat.JpegOnly,
        importedCount = 0,
        lastImportedFileName = null,
        remoteRuntime = RemoteRuntimeState(),
    )

    fun buildUiState(
        selectedSection: OmCaptureSection,
        connectionLabel: String,
        deviceLabel: String?,
        lastActionLabel: String?,
        liveViewActive: Boolean,
        tetherSaveTarget: TetherSaveTarget,
        phoneImportFormat: TetherPhoneImportFormat,
        importedCount: Int,
        lastImportedFileName: String?,
        remoteRuntime: RemoteRuntimeState,
    ): OmCaptureStudioUiState {
        val summaries = OmCaptureSection.entries.map { section ->
            val sectionFeatures = features.filter { it.uiSection == section }
            OmCaptureSectionSummary(
                section = section,
                featureCount = sectionFeatures.size,
                implementedCount = sectionFeatures.count { it.implementationStatus == OmCaptureImplementationStatus.Implemented },
                adaptedCount = sectionFeatures.count { it.implementationStatus == OmCaptureImplementationStatus.Adapted },
                blockedCount = sectionFeatures.count { it.implementationStatus == OmCaptureImplementationStatus.BlockedByMissingTrace },
            )
        }
        return OmCaptureStudioUiState(
            selectedSection = selectedSection,
            sectionSummaries = summaries,
            features = features,
            connectionLabel = connectionLabel,
            deviceLabel = deviceLabel,
            lastActionLabel = lastActionLabel,
            controlValues = buildControlValues(
                section = selectedSection,
                connectionLabel = connectionLabel,
                deviceLabel = deviceLabel,
                lastActionLabel = lastActionLabel,
                liveViewActive = liveViewActive,
                tetherSaveTarget = tetherSaveTarget,
                phoneImportFormat = phoneImportFormat,
                importedCount = importedCount,
                lastImportedFileName = lastImportedFileName,
                remoteRuntime = remoteRuntime,
            ),
            liveViewActive = liveViewActive,
            tetherSaveTarget = tetherSaveTarget,
            phoneImportFormat = phoneImportFormat,
            remoteControlReady = remoteRuntime.propertiesLoaded,
            remoteStatusLabel = remoteRuntime.statusLabel,
            totalFeatureCount = features.size,
            implementedCount = features.count { it.implementationStatus == OmCaptureImplementationStatus.Implemented },
            adaptedCount = features.count { it.implementationStatus == OmCaptureImplementationStatus.Adapted },
            blockedCount = features.count { it.implementationStatus == OmCaptureImplementationStatus.BlockedByMissingTrace },
        )
    }

    private fun buildControlValues(
        section: OmCaptureSection,
        connectionLabel: String,
        deviceLabel: String?,
        lastActionLabel: String?,
        liveViewActive: Boolean,
        tetherSaveTarget: TetherSaveTarget,
        phoneImportFormat: TetherPhoneImportFormat,
        importedCount: Int,
        lastImportedFileName: String?,
        remoteRuntime: RemoteRuntimeState,
    ): List<OmCaptureControlValue> = when (section) {
        OmCaptureSection.Connection -> listOf(
            OmCaptureControlValue("Camera", deviceLabel ?: "Waiting for a compatible USB/PTP camera"),
            OmCaptureControlValue("Status", connectionLabel),
            OmCaptureControlValue("Remote Control", if (remoteRuntime.propertiesLoaded) "Connected" else "Reconnect required", remoteRuntime.statusLabel),
            OmCaptureControlValue("Live Preview", if (liveViewActive) "Active" else "Stopped"),
            OmCaptureControlValue("Save Target", tetherSaveTarget.toOmCaptureLabel()),
        )
        OmCaptureSection.LiveView -> listOf(
            OmCaptureControlValue("Live Preview", if (liveViewActive) "Running" else "Ready"),
            OmCaptureControlValue("Preview Pane", "Inline Android preview", "Desktop preview adapted for Android"),
            OmCaptureControlValue("Live View Boost", "Validation pending"),
            OmCaptureControlValue("LV Mode / S-OVF", "Validation pending"),
        )
        OmCaptureSection.AfMf -> listOf(
            OmCaptureControlValue("Focus State", focusStateLabel(remoteRuntime)),
            OmCaptureControlValue("White Balance", PropertyFormatter.formatForDisplay("wbvalue", remoteRuntime.whiteBalance.currentValue).ifBlank { remoteRuntime.whiteBalance.currentValue.ifBlank { "--" } }),
            OmCaptureControlValue("One Touch WB", "Validation pending"),
            OmCaptureControlValue("Manual Focus", "Validation pending"),
        )
        OmCaptureSection.Exposure -> listOf(
            OmCaptureControlValue("Mode", remoteRuntime.exposureMode.label),
            OmCaptureControlValue("Aperture", PropertyFormatter.formatForDisplay("focalvalue", remoteRuntime.aperture.currentValue).ifBlank { remoteRuntime.aperture.currentValue.ifBlank { "--" } }),
            OmCaptureControlValue("Shutter", PropertyFormatter.formatForDisplay("shutspeedvalue", remoteRuntime.shutterSpeed.currentValue).ifBlank { remoteRuntime.shutterSpeed.currentValue.ifBlank { "--" } }),
            OmCaptureControlValue("ISO", PropertyFormatter.formatIsoDisplay(remoteRuntime.iso.currentValue, remoteRuntime.iso.autoActive).ifBlank { remoteRuntime.iso.currentValue.ifBlank { "--" } }),
            OmCaptureControlValue("White Balance", PropertyFormatter.formatForDisplay("wbvalue", remoteRuntime.whiteBalance.currentValue).ifBlank { remoteRuntime.whiteBalance.currentValue.ifBlank { "--" } }),
            OmCaptureControlValue("Exposure Compensation", PropertyFormatter.formatForDisplay("expcomp", remoteRuntime.exposureCompensationValues.currentValue).ifBlank { remoteRuntime.exposureCompensationValues.currentValue.ifBlank { "--" } }),
        )
        OmCaptureSection.DriveComputational -> listOf(
            OmCaptureControlValue("Drive", remoteRuntime.driveMode.label),
            OmCaptureControlValue("Timer", remoteRuntime.timerMode.label),
            OmCaptureControlValue("Interval", "${remoteRuntime.intervalSeconds}s x ${remoteRuntime.intervalCount}", if (remoteRuntime.intervalRunning) "Running ${remoteRuntime.intervalCurrent}/${remoteRuntime.intervalCount}" else "Ready"),
            OmCaptureControlValue("High Res / Pro Capture", "Validation pending"),
        )
        OmCaptureSection.Movie -> listOf(
            OmCaptureControlValue("Mode", if (remoteRuntime.exposureMode.label == "Video") "Video" else remoteRuntime.exposureMode.label),
            OmCaptureControlValue("Movie Settings", "Validation pending"),
            OmCaptureControlValue("Movie AF Mode", "Validation pending"),
            OmCaptureControlValue("Movie Save Slot", "Validation pending"),
        )
        OmCaptureSection.FileSave -> listOf(
            OmCaptureControlValue("Save Settings", tetherSaveTarget.toOmCaptureLabel()),
            OmCaptureControlValue("Save Priority", tetherSaveTarget.toPriorityDescription()),
            OmCaptureControlValue("Phone Import Format", phoneImportFormat.toLabel()),
            OmCaptureControlValue("Quick Setup", "Section-local on Android"),
            OmCaptureControlValue("Storage Source", "MediaStore content URI"),
        )
        OmCaptureSection.TransferLink -> listOf(
            OmCaptureControlValue("Last Action", lastActionLabel ?: "No USB import run yet"),
            OmCaptureControlValue("Imported Count", importedCount.toString()),
            OmCaptureControlValue("Last Imported File", lastImportedFileName ?: "--"),
            OmCaptureControlValue("Save Target", tetherSaveTarget.toOmCaptureLabel()),
            OmCaptureControlValue("Phone Import Format", phoneImportFormat.toLabel()),
        )
        OmCaptureSection.Support -> listOf(
            OmCaptureControlValue("Validation Scope", "Observed device behavior and protocol checks"),
            OmCaptureControlValue("Desktop-only Items", "Firewall / multi-monitor / updater"),
            OmCaptureControlValue("Availability Policy", "Only validated features are enabled"),
            OmCaptureControlValue("Target Body", "OM-1"),
        )
    }

    private fun TetherSaveTarget.toOmCaptureLabel(): String = when (this) {
        TetherSaveTarget.SdCard -> "SD"
        TetherSaveTarget.SdAndPhone -> "SD + Phone"
        TetherSaveTarget.Phone -> "Phone"
    }

    private fun TetherSaveTarget.toPriorityDescription(): String = when (this) {
        TetherSaveTarget.SdCard -> "Camera card only"
        TetherSaveTarget.SdAndPhone -> "Camera card plus MediaStore import"
        TetherSaveTarget.Phone -> "MediaStore import first"
    }

    private fun TetherPhoneImportFormat.toLabel(): String = when (this) {
        TetherPhoneImportFormat.JpegOnly -> "JPEG"
        TetherPhoneImportFormat.RawOnly -> "RAW"
        TetherPhoneImportFormat.JpegAndRaw -> "JPEG + RAW"
    }

    private fun focusStateLabel(runtime: RemoteRuntimeState): String = when {
        runtime.isFocusing -> "Focusing"
        runtime.focusHeld -> "Locked"
        else -> "Idle"
    }

    private fun feature(
        id: String,
        label: String,
        group: String,
        evidence: String,
        ptp: List<String>,
        support: OmCaptureSupportState,
        policy: OmCaptureAndroidPolicy,
        section: OmCaptureSection,
        status: OmCaptureImplementationStatus,
        summary: String,
    ) = OmCaptureFeatureDescriptor(
        id = id,
        originalLabel = label,
        originalGroup = group,
        evidenceSource = evidence,
        ptpReferences = ptp,
        om1SupportState = support,
        androidPolicy = policy,
        uiSection = section,
        implementationStatus = status,
        summary = summary,
    )
}
