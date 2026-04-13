package dev.dblink.core.config

import dev.dblink.BuildConfig

data class AppConfig(
    val cameraBaseUrl: String,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
    val liveViewPort: Int,
    val liveViewUdpEnabled: Boolean,
    val autoSyncOnLaunch: Boolean,
    val connectionRetryCount: Int,
    val debugProtocolLogs: Boolean,
    val showProtocolWorkbench: Boolean,
    val useHighQualityScreennails: Boolean,
)

object AppEnvironment {
    /**
     * Returns the default camera connection settings for the current build.
     */
    fun current(): AppConfig {
        return AppConfig(
            cameraBaseUrl = BuildConfig.CAMERA_BASE_URL,
            connectTimeoutMs = 2500,
            readTimeoutMs = 5000,
            liveViewPort = 65000,
            liveViewUdpEnabled = true,
            autoSyncOnLaunch = true,
            connectionRetryCount = 3,
            debugProtocolLogs = BuildConfig.DEBUG_PROTOCOL_LOGS,
            showProtocolWorkbench = BuildConfig.DEBUG_PROTOCOL_WORKBENCH,
            useHighQualityScreennails = true
        )
    }

    /**
     * Returns a lower-activity configuration for battery-sensitive sessions.
     */
    fun powerSaver(): AppConfig = current().copy(
        connectTimeoutMs = 5000,
        readTimeoutMs = 10000,
        autoSyncOnLaunch = false,
        useHighQualityScreennails = false
    )
}
