package dev.pl36.cameralink.core.protocol

import dev.pl36.cameralink.core.logging.D
import dev.pl36.cameralink.core.model.CameraCapability
import dev.pl36.cameralink.core.model.CameraCommandList

object CapabilityResolver {
    fun resolve(commandList: CameraCommandList): Set<CameraCapability> {
        val supportedCommands = commandList.commands
            .filter { it.supported }
            .map { it.name }
            .toSet()
        D.proto("Resolving capabilities from ${supportedCommands.size} supported commands: $supportedCommands")

        return buildSet {
            if (supportedCommands.containsAll(setOf("switch_cammode", "exec_takemotion"))) {
                add(CameraCapability.RemoteCapture)
            }
            if (supportedCommands.contains("exec_takemisc")) {
                add(CameraCapability.LiveView)
            }
            if (supportedCommands.containsAll(setOf("get_camprop", "set_camprop"))) {
                add(CameraCapability.PropertyControl)
            }
            if (supportedCommands.containsAll(setOf("get_imglist", "get_thumbnail"))) {
                add(CameraCapability.ImageBrowser)
            }
            if (supportedCommands.contains("get_resizeimg_witherr") || supportedCommands.contains("get_resizeimg")) {
                add(CameraCapability.ResizedTransfer)
            }
            if (supportedCommands.contains("ready_moviestream")) {
                add(CameraCapability.MovieStreaming)
            }
            if (supportedCommands.contains("get_partialmysetdata") || supportedCommands.contains("request_getmysetdata")) {
                add(CameraCapability.MysetBackup)
            }
            if (supportedCommands.contains("get_cameraloginfo")) {
                add(CameraCapability.CameraLogs)
            }
            if (supportedCommands.contains("get_connectmode")) {
                add(CameraCapability.AutoImport)
            }
        }.also { caps ->
            D.proto("Resolved capabilities (${caps.size}): $caps")
        }
    }
}
