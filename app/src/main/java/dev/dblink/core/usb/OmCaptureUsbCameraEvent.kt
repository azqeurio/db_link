package dev.dblink.core.usb

sealed interface OmCaptureUsbCameraEvent {
    data class ObjectAdded(
        val handle: Int,
    ) : OmCaptureUsbCameraEvent

    data class PropertyChanged(
        val propCode: Int,
        val rawValue: Long?,
    ) : OmCaptureUsbCameraEvent

    data class SessionResetRequired(
        val reason: String,
    ) : OmCaptureUsbCameraEvent
}
