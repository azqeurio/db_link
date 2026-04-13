package dev.dblink.core.protocol

import dev.dblink.core.config.AppConfig
import dev.dblink.core.config.AppEnvironment
import dev.dblink.core.model.CameraWorkspace
import dev.dblink.core.model.ConnectMode
import dev.dblink.core.model.ConnectionMethod

class FakeCameraRepository(
    private val config: AppConfig = AppEnvironment.current(),
) : CameraRepository {
    override fun initialWorkspace(): CameraWorkspace = WorkspaceAssembler.build(
        commandListXml = sampleCommandListXml(),
        baseUrl = config.cameraBaseUrl,
        sourceLabel = "Sample data",
        connectMode = ConnectMode.Record,
        connectionMethod = ConnectionMethod.WifiBleAssist,
        showProtocolWorkbench = config.showProtocolWorkbench,
        debugProtocolLogs = config.debugProtocolLogs,
    )

    override suspend fun probeConnectionReady(): Result<String> =
        Result.success("Camera ready (simulated)")

    override suspend fun loadWorkspace(): CameraWorkspace = initialWorkspace()

    override suspend fun refreshFromCommandList(rawCommandList: String): Result<CameraWorkspace> = runCatching {
        WorkspaceAssembler.build(
            commandListXml = rawCommandList,
            baseUrl = config.cameraBaseUrl,
            sourceLabel = "Custom preview",
            connectMode = ConnectMode.Record,
            connectionMethod = ConnectionMethod.WifiBleAssist,
            showProtocolWorkbench = config.showProtocolWorkbench,
            debugProtocolLogs = config.debugProtocolLogs,
        )
    }

    override suspend fun switchCameraMode(mode: ConnectMode): Result<String> =
        Result.success("Switched to ${mode.name} (simulated)")

    override suspend fun activateRecMode(): Result<String> =
        Result.success("Rec mode activated (simulated)")

    override suspend fun startLiveView(port: Int): Result<String> =
        Result.success("Live View on port $port (simulated)")

    override suspend fun stopLiveView(): Result<String> =
        Result.success("Live View stopped")

    override suspend fun halfPressShutter(): Result<String> =
        Result.success("Focus locked (simulated)")

    override suspend fun releaseShutterFocus(): Result<String> =
        Result.success("Focus released")

    override suspend fun triggerCapture(): Result<String> =
        Result.success("Captured (simulated)")

    override suspend fun captureWhileLiveView(): Result<String> =
        Result.success("Captured while LV (simulated)")

    override suspend fun setTakeMode(modeValue: String): Result<String> =
        Result.success("takemode = $modeValue (simulated)")

    override suspend fun setProperty(propName: String, propValue: String): Result<String> =
        Result.success("$propName = $propValue (simulated)")

    override suspend fun getPropertyCheck(propName: String): Result<Boolean> =
        Result.success(true)

    override suspend fun getPropertyDesc(propName: String): Result<CameraPropertyDesc> =
        Result.success(CameraPropertyDesc(propName = propName, currentValue = "Auto", enumValues = listOf("Auto")))

    override suspend fun getPropertyDescList(): Result<Map<String, CameraPropertyDesc>> =
        Result.success(emptyMap())

    override suspend fun assignAfFrame(pointX: Int, pointY: Int): Result<AssignedAfFrame> =
        Result.success(
            AssignedAfFrame(
                requestedX = pointX,
                requestedY = pointY,
                confirmedX = pointX,
                confirmedY = pointY,
            ),
        )

    override suspend fun releaseAfFrame(): Result<String> =
        Result.success("AF released (simulated)")

    override suspend fun getImageList(directory: String): Result<List<CameraImage>> =
        Result.success(emptyList())

    override suspend fun getThumbnail(image: CameraImage): Result<ByteArray> =
        Result.success(ByteArray(0))

    override suspend fun getPreviewThumbnail(image: CameraImage): Result<ByteArray> =
        Result.success(ByteArray(0))

    override suspend fun getResizedImageWithErr(image: CameraImage, width: Int): Result<ByteArray> =
        Result.success(ByteArray(0))

    override suspend fun getResizedImage(image: CameraImage, width: Int): Result<ByteArray> =
        Result.success(ByteArray(0))

    override suspend fun getFullImage(image: CameraImage): Result<ByteArray> =
        Result.success(ByteArray(0))

    override suspend fun getPlayTargetSlot(): Result<Int> =
        Result.success(1)

    override suspend fun setPlayTargetSlot(slot: Int): Result<Int> =
        Result.success(slot)

    override suspend fun deleteImage(image: CameraImage): Result<String> =
        Result.success("Deleted ${image.fileName} (simulated)")

    override suspend fun powerOffCamera(useWithBleMode: Boolean): Result<String> =
        Result.success("Camera power-off sent (simulated)")

    override suspend fun syncCameraTime(): Result<String> =
        Result.success("Camera time synced (simulated)")

    override suspend fun setSessionTimeout(timeoutSeconds: Int): Result<String> =
        Result.success("Timeout set (fake)")

    override fun sampleCommandListXml(): String = SAMPLE_COMMAND_LIST

    private companion object {
        private const val SAMPLE_COMMAND_LIST = """
            <cmdlist>
                <version>2.1</version>
                <cgi name="get_connectmode"><support>true</support></cgi>
                <cgi name="switch_cammode"><support>true</support></cgi>
                <cgi name="exec_takemotion"><support>true</support></cgi>
                <cgi name="exec_takemisc"><support>true</support></cgi>
                <cgi name="get_camprop"><support>true</support></cgi>
                <cgi name="set_camprop"><support>true</support></cgi>
                <cgi name="get_imglist"><support>true</support><param1>/DCIM</param1></cgi>
                <cgi name="get_thumbnail"><support>true</support></cgi>
                <cgi name="get_screennail"><support>true</support></cgi>
                <cgi name="get_resizeimg_witherr"><support>true</support></cgi>
                <cgi name="exec_pwoff"><support>true</support><param1>withble</param1></cgi>
                <cgi name="ready_moviestream"><support>true</support></cgi>
                <cgi name="start_moviestream"><support>true</support></cgi>
                <cgi name="stop_moviestream"><support>true</support></cgi>
                <cgi name="request_getmysetdata"><support>true</support></cgi>
                <cgi name="get_partialmysetdata"><support>true</support></cgi>
                <cgi name="send_partialmysetdata"><support>true</support></cgi>
                <cgi name="get_cameraloginfo"><support>true</support></cgi>
            </cmdlist>
        """
    }
}
