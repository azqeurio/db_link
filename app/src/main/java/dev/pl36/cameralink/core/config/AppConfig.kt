package dev.pl36.cameralink.core.config

import dev.pl36.cameralink.BuildConfig

/**
 * Modern Application Configuration
 * Refined for Apple Pro UI/UX and actual Olympus Camera Protocol.
 */
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
     * Returns the optimized configuration for the current build environment.
     * Defaulting to "Pro" performance settings for real-time camera interaction.
     */
    fun current(): AppConfig {
        return AppConfig(
            cameraBaseUrl = BuildConfig.CAMERA_BASE_URL,
            connectTimeoutMs = 2500, // Optimized for responsive UI
            readTimeoutMs = 5000,
            liveViewPort = 65000, // Standard Olympus LiveView Port
            liveViewUdpEnabled = true,
            autoSyncOnLaunch = true,
            connectionRetryCount = 3,
            debugProtocolLogs = BuildConfig.DEBUG_PROTOCOL_LOGS,
            showProtocolWorkbench = BuildConfig.DEBUG_PROTOCOL_WORKBENCH,
            useHighQualityScreennails = true
        )
    }

    /**
     * Returns a "Power Saver" configuration for battery efficiency.
     */
    fun powerSaver(): AppConfig = current().copy(
        connectTimeoutMs = 5000,
        readTimeoutMs = 10000,
        autoSyncOnLaunch = false,
        useHighQualityScreennails = false
    )
}
