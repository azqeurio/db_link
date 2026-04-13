package dev.dblink.ui

import dev.dblink.core.usb.OmCaptureUsbOperationState
import dev.dblink.core.usb.OmCaptureUsbSavedMedia
import dev.dblink.core.usb.OmCaptureUsbSessionSummary

typealias OmCaptureUsbSummary = OmCaptureUsbSessionSummary

data class OmCaptureUsbUiState(
    val operationState: OmCaptureUsbOperationState = OmCaptureUsbOperationState.Idle,
    val statusLabel: String = "No OM camera detected over USB",
    val isBusy: Boolean = false,
    val summary: OmCaptureUsbSummary? = null,
    val lastActionLabel: String? = null,
    val lastImportedFileName: String? = null,
    val importedCount: Int = 0,
    val lastSavedMedia: OmCaptureUsbSavedMedia? = null,
    val canRetry: Boolean = false,
)
