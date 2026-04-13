package dev.dblink.core.usb

enum class OmCaptureUsbOperationState {
    Idle,
    Inspecting,
    Capturing,
    WaitObject,
    Downloading,
    Saving,
    Complete,
    Error,
}

val OmCaptureUsbOperationState.statusChipLabel: String
    get() = when (this) {
        OmCaptureUsbOperationState.Idle -> "IDLE"
        OmCaptureUsbOperationState.Inspecting -> "SCAN"
        OmCaptureUsbOperationState.Capturing -> "CAPTURE"
        OmCaptureUsbOperationState.WaitObject -> "WAIT_OBJECT"
        OmCaptureUsbOperationState.Downloading -> "DOWNLOADING"
        OmCaptureUsbOperationState.Saving -> "SAVING"
        OmCaptureUsbOperationState.Complete -> "COMPLETE"
        OmCaptureUsbOperationState.Error -> "ERROR"
    }

data class OmCaptureUsbSessionSummary(
    val deviceLabel: String,
    val manufacturer: String,
    val model: String,
    val firmwareVersion: String,
    val serialNumber: String,
    val storageIds: List<Int>,
    val supportsCapture: Boolean,
    val supportsLiveView: Boolean,
    val supportsPropertyControl: Boolean,
    val supportedOperationCount: Int,
)

data class OmCaptureUsbSavedMedia(
    val uriString: String,
    val relativePath: String,
    val absolutePath: String,
    val displayName: String,
)

data class OmCaptureUsbCompanionImport(
    val objectHandle: Int,
    val fileName: String,
    val captureDate: String,
    val captureTimestampMillis: Long?,
    val width: Int,
    val height: Int,
    val isJpeg: Boolean,
    val isRaw: Boolean,
    val savedMedia: OmCaptureUsbSavedMedia,
)

data class OmCaptureUsbRuntimeState(
    val operationState: OmCaptureUsbOperationState = OmCaptureUsbOperationState.Idle,
    val statusLabel: String = "No OM camera detected over USB",
    val summary: OmCaptureUsbSessionSummary? = null,
    val lastActionLabel: String? = null,
    val lastImportedFileName: String? = null,
    val importedCount: Int = 0,
    val lastSavedMedia: OmCaptureUsbSavedMedia? = null,
    val canRetry: Boolean = false,
) {
    val isBusy: Boolean
        get() = operationState in setOf(
            OmCaptureUsbOperationState.Inspecting,
            OmCaptureUsbOperationState.Capturing,
            OmCaptureUsbOperationState.WaitObject,
            OmCaptureUsbOperationState.Downloading,
            OmCaptureUsbOperationState.Saving,
        )
}

data class OmCaptureUsbImportResult(
    val objectHandle: Int,
    val fileName: String,
    val captureDate: String,
    val captureTimestampMillis: Long?,
    val width: Int,
    val height: Int,
    val isJpeg: Boolean,
    val isRaw: Boolean,
    val thumbnailBytes: ByteArray?,
    val objectBytes: ByteArray,
    val savedMedia: OmCaptureUsbSavedMedia,
    val relatedObjectHandles: List<Int> = emptyList(),
    val companionImports: List<OmCaptureUsbCompanionImport> = emptyList(),
) {
    val savedCount: Int
        get() = 1 + companionImports.size
}
