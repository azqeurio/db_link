package dev.dblink.core.protocol

import dev.dblink.core.model.CameraWorkspace
import dev.dblink.core.model.ConnectMode
import java.io.OutputStream

/** Describes a camera property with its current value and list of possible values. */
data class CameraPropertyDesc(
    val propName: String,
    val attribute: String = "",
    val currentValue: String,
    val enumValues: List<String>,
    val minValue: String? = null,
    val maxValue: String? = null,
)

data class AssignedAfFrame(
    val requestedX: Int,
    val requestedY: Int,
    val confirmedX: Int = requestedX,
    val confirmedY: Int = requestedY,
)

interface CameraRepository {
    fun initialWorkspace(): CameraWorkspace
    suspend fun probeConnectionReady(): Result<String>
    suspend fun loadWorkspace(): CameraWorkspace
    suspend fun refreshFromCommandList(rawCommandList: String): Result<CameraWorkspace>
    suspend fun switchCameraMode(mode: ConnectMode): Result<String>
    /** Switch to rec mode WITH lvqty to activate the imaging pipeline (required before get_camprop). */
    suspend fun activateRecMode(): Result<String>
    suspend fun startLiveView(port: Int): Result<String>
    suspend fun stopLiveView(): Result<String>
    suspend fun halfPressShutter(): Result<String>
    suspend fun releaseShutterFocus(): Result<String>
    suspend fun triggerCapture(): Result<String>
    /** Capture while live view is already active (skip mode switch, faster). */
    suspend fun captureWhileLiveView(): Result<String>
    suspend fun setTakeMode(modeValue: String): Result<String>
    suspend fun setProperty(propName: String, propValue: String): Result<String>
    suspend fun getPropertyCheck(propName: String): Result<Boolean>
    suspend fun getPropertyDesc(propName: String): Result<CameraPropertyDesc>
    suspend fun getPropertyDescList(): Result<Map<String, CameraPropertyDesc>>
    suspend fun assignAfFrame(pointX: Int, pointY: Int): Result<AssignedAfFrame>
    suspend fun releaseAfFrame(): Result<String>
    /** Send set_timeout keepalive to prevent camera from disconnecting (default 1800s). */
    suspend fun setSessionTimeout(timeoutSeconds: Int = 1800): Result<String>
    /** Send set_utctimediff.cgi to sync camera clock. Always sent in loadWorkspace(); this is for standalone use. */
    suspend fun syncCameraTime(): Result<String>
    fun sampleCommandListXml(): String

    // Image transfer
    suspend fun getImageList(directory: String = "/DCIM"): Result<List<CameraImage>>
    suspend fun getThumbnail(image: CameraImage): Result<ByteArray>
    suspend fun getPreviewThumbnail(image: CameraImage): Result<ByteArray>
    suspend fun getResizedImageWithErr(image: CameraImage, width: Int = 1024): Result<ByteArray>
    suspend fun getResizedImage(image: CameraImage, width: Int = 1024): Result<ByteArray>
    suspend fun getFullImage(image: CameraImage): Result<ByteArray>
    suspend fun downloadFullImageTo(image: CameraImage, output: OutputStream): Result<Long>
    suspend fun getPlayTargetSlot(): Result<Int>
    suspend fun setPlayTargetSlot(slot: Int): Result<Int>
    suspend fun deleteImage(image: CameraImage): Result<String>
    suspend fun powerOffCamera(useWithBleMode: Boolean = false): Result<String>
}
