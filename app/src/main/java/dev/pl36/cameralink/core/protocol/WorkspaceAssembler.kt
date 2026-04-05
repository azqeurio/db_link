package dev.pl36.cameralink.core.protocol

import dev.pl36.cameralink.core.logging.D
import dev.pl36.cameralink.core.model.CameraProperty
import dev.pl36.cameralink.core.model.CameraProtocolSummary
import dev.pl36.cameralink.core.model.CameraWorkspace
import dev.pl36.cameralink.core.model.ConnectMode
import dev.pl36.cameralink.core.model.ConnectionMethod
import dev.pl36.cameralink.core.model.ConnectedCamera
import dev.pl36.cameralink.core.model.FeatureStatus
import dev.pl36.cameralink.core.model.RemoteAction
import dev.pl36.cameralink.core.model.RemoteControlSnapshot
import dev.pl36.cameralink.core.model.SettingItem
import dev.pl36.cameralink.core.model.TransferSnapshot

object WorkspaceAssembler {
    private val parser = CameraCommandListParser()

    fun build(
        commandListXml: String,
        baseUrl: String,
        sourceLabel: String,
        connectMode: ConnectMode,
        connectionMethod: ConnectionMethod,
        showProtocolWorkbench: Boolean,
        debugProtocolLogs: Boolean,
        cameraName: String = "OM SYSTEM Camera",
        firmwareVersion: String = "Unknown",
        batteryPercent: Int? = null,
    ): CameraWorkspace {
        D.proto("Building workspace: baseUrl=$baseUrl, mode=$connectMode, method=$connectionMethod")
        val commandList = parser.parse(commandListXml)
        val capabilities = CapabilityResolver.resolve(commandList)
        D.proto("Workspace built: commands=${commandList.commands.size}, capabilities=${capabilities.size}")

        return CameraWorkspace(
            camera = ConnectedCamera(
                name = cameraName,
                firmwareVersion = firmwareVersion,
                batteryPercent = batteryPercent,
                connectionMethod = connectionMethod,
                connectMode = connectMode,
                storageFree = "Checking...",
                capabilityCount = capabilities.size,
            ),
            protocol = CameraProtocolSummary(
                baseUrl = baseUrl,
                commandList = commandList,
                endpoints = CameraProtocolCatalog.endpoints,
                properties = CameraProperty.entries,
            ),
            capabilities = capabilities,
            remote = RemoteControlSnapshot(
                liveViewLabel = if ("exec_takemisc" in commandList.commands.map { it.name }) "Available" else "Unavailable",
                actionGroups = listOf(
                    RemoteAction("Shutter", FeatureStatus.Ready),
                    RemoteAction("Focus", FeatureStatus.Ready),
                    RemoteAction("Exposure", FeatureStatus.Partial),
                    RemoteAction("Movie", FeatureStatus.Partial),
                ),
            ),
            transfer = TransferSnapshot(
                autoImportEnabled = "get_connectmode" in commandList.commands.map { it.name },
                mediaDestination = "Pictures / db link",
                queueDepth = 0,
                importedToday = 0,
            ),
            settings = listOf(
                SettingItem("nearby_discovery", "Nearby discovery", "Wi-Fi & Bluetooth on setup only", true),
                SettingItem("reverse_dial_aperture", "Reverse aperture dial", "Flip scroll direction for the aperture dial", false),
                SettingItem("reverse_dial_shutter", "Reverse shutter dial", "Flip scroll direction for the shutter speed dial", false),
                SettingItem("reverse_dial_iso", "Reverse ISO dial", "Flip scroll direction for the ISO dial", false),
                SettingItem("reverse_dial_wb", "Reverse WB dial", "Flip scroll direction for the white balance dial", false),
                SettingItem("reverse_dial_ev", "Reverse EV dial", "Flip scroll direction for the exposure compensation dial", false),
                SettingItem("capture_review_after_shot", "Review latest shot", "Refresh the library after capture and keep the newest shot ready to open", false),
                SettingItem("auto_import", "Auto import", "Import when camera wakes nearby", false),
                SettingItem("import_new_only", "New files only", "Only import files not previously transferred", true),
                SettingItem("import_skip_duplicates", "Skip duplicates", "Do not overwrite existing files", true),
                SettingItem("time_match_geotags", "Auto geotag", "Match places to photos by time", true),
                SettingItem("geotag_sync_clock", "Trust clock sync", "Reset clock offset to 0 on connect", true),
                SettingItem("geotag_include_altitude", "Include altitude", "Write altitude to EXIF GPS fields", true),
                SettingItem("debug_workbench", "Protocol workbench", "Debug protocol tools", showProtocolWorkbench),
                SettingItem("verbose_logs", "Verbose logs", "HTTP and parser diagnostics", debugProtocolLogs),
            ),
            generatedAtLabel = sourceLabel,
        )
    }
}
