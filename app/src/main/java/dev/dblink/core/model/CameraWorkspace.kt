package dev.dblink.core.model

enum class CameraCapability(
    val title: String,
) {
    RemoteCapture("Remote Capture"),
    LiveView("Live View"),
    PropertyControl("Properties"),
    ImageBrowser("Image Browser"),
    ResizedTransfer("Resized Transfer"),
    MovieStreaming("Movie Streaming"),
    MysetBackup("Myset Backup"),
    CameraLogs("Camera Logs"),
    AutoImport("Auto Import"),
}

enum class FeatureStatus {
    Ready,
    Partial,
    Planned,
}

enum class ConnectionMethod {
    Wifi,
    WifiBleAssist,
    BleOnly,
    Unknown,
}

enum class ConnectMode {
    Play,
    Record,
    Shutter,
    Maintenance,
    Unknown,
}

data class CameraCommandDefinition(
    val name: String,
    val supported: Boolean,
    val param1: String? = null,
    val param2: String? = null,
)

data class CameraCommandList(
    val version: String,
    val commands: List<CameraCommandDefinition>,
)

data class CameraEndpointDefinition(
    val category: String,
    val path: String,
    val title: String,
    val modernHandling: String,
)

enum class CameraProperty(
    val key: String,
    val label: String,
) {
    TakeMode("takemode", "Take Mode"),
    WhiteBalance("wbvalue", "White Balance"),
    Iso("isospeedvalue", "ISO"),
    ShutterSpeed("shutspeedvalue", "Shutter Speed"),
    ExposureCompensation("expcomp", "Exposure Compensation"),
    DriveMode("drivemode", "Drive Mode"),
    ArtFilter("artfilter", "Art Filter"),
    MovieQuality("qualitymovie", "Movie Quality"),
    FocalValue("focalvalue", "Focal Value"),
    SceneSub("SceneSub", "Scene Variant"),
    SuperMacroSub("supermacrosub", "Super Macro"),
}

data class CameraProtocolSummary(
    val baseUrl: String,
    val commandList: CameraCommandList,
    val endpoints: List<CameraEndpointDefinition>,
    val properties: List<CameraProperty>,
)

data class ConnectedCamera(
    val name: String,
    val firmwareVersion: String,
    val batteryPercent: Int?,
    val connectionMethod: ConnectionMethod,
    val connectMode: ConnectMode,
    val storageFree: String,
    val capabilityCount: Int,
)

data class RemoteAction(
    val title: String,
    val status: FeatureStatus,
)

data class RemoteControlSnapshot(
    val liveViewLabel: String,
    val actionGroups: List<RemoteAction>,
)

data class TransferSnapshot(
    val autoImportEnabled: Boolean,
    val mediaDestination: String,
    val queueDepth: Int,
    val importedToday: Int,
)

data class SettingItem(
    val id: String,
    val title: String,
    val summary: String,
    val enabled: Boolean,
)

data class CameraWorkspace(
    val camera: ConnectedCamera,
    val protocol: CameraProtocolSummary,
    val capabilities: Set<CameraCapability>,
    val remote: RemoteControlSnapshot,
    val transfer: TransferSnapshot,
    val settings: List<SettingItem>,
    val generatedAtLabel: String,
)
