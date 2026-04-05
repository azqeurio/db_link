package dev.pl36.cameralink.core.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.content.Intent
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dev.pl36.cameralink.core.logging.D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Monitors WiFi connection state and detects whether the device is connected
 * to the camera's WiFi access point (typically 192.168.0.x network).
 *
 * Typical connection flow:
 * 1. Camera creates WiFi AP (e.g., "E-M10MarkIV_XXXX")
 * 2. User connects phone to camera WiFi
 * 3. App detects WiFi connection and starts HTTP communication
 */
class CameraWifiManager(private val context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var connectNetworkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var requestedCameraNetwork: Network? = null
    @Volatile private var requestedCameraSsid: String? = null
    private val requestGeneration = AtomicLong(0)
    private var requestTimeoutRunnable: Runnable? = null
    private val requestSettleIntervalMillis = 150L
    private val requestSettleMaxChecks = 20

    /**
     * Programmatically request connection to a specific WiFi network (used with QR scan).
     */
    fun connectToCameraWifi(ssid: String, password: String) {
        connectToCameraWifiInternal(
            ssid = ssid,
            password = password,
            timeoutMillis = 15000L,
            openSettingsOnFailure = true,
            onCompleted = null,
        )
    }

    suspend fun connectToCameraWifiAndAwait(
        ssid: String,
        password: String,
        timeoutMillis: Long = 12000L,
        openSettingsOnFailure: Boolean = false,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        connectToCameraWifiInternal(
            ssid = ssid,
            password = password,
            timeoutMillis = timeoutMillis,
            openSettingsOnFailure = openSettingsOnFailure,
            onCompleted = { success ->
                if (continuation.isActive) {
                    continuation.resume(success)
                }
            },
        )
        continuation.invokeOnCancellation {
            cancelPendingRequestTimeout()
        }
    }

    private fun connectToCameraWifiInternal(
        ssid: String,
        password: String,
        timeoutMillis: Long,
        openSettingsOnFailure: Boolean,
        onCompleted: ((Boolean) -> Unit)?,
    ) {
        D.marker("WiFi Connect: $ssid")
        D.wifi("connectToCameraWifi() ssid=$ssid")
        D.timeStart("wifi_connect")
        val generation = requestGeneration.incrementAndGet()
        val completed = AtomicBoolean(false)

        fun finish(success: Boolean) {
            if (requestGeneration.get() != generation) return
            if (!completed.compareAndSet(false, true)) return
            cancelPendingRequestTimeout()
            if (!success) {
                clearConnectCallback()
            }
            if (!success && openSettingsOnFailure) {
                openWifiSettings()
            }
            onCompleted?.invoke(success)
        }

        fun finishWhenCameraNetworkReady(settleCheck: Int = 0) {
            if (requestGeneration.get() != generation || completed.get()) return
            val active = isRequestedCameraNetworkActive() || isConnectedToCameraSsid(ssid)
            if (active) {
                D.wifi("requestNetwork settle complete for $ssid: active=true after ${settleCheck + 1} checks")
                finish(true)
                return
            }
            if (settleCheck >= requestSettleMaxChecks) {
                D.wifi("requestNetwork settle complete for $ssid: active=false after ${settleCheck + 1} checks")
                finish(false)
                return
            }
            mainHandler.postDelayed(
                { finishWhenCameraNetworkReady(settleCheck + 1) },
                requestSettleIntervalMillis,
            )
        }

        if (!isWifiEnabled()) {
            D.wifi("connectToCameraWifi aborted: WiFi is disabled")
            finish(false)
            return
        }
        requestedCameraSsid = ssid
        resetRequestedCameraConnection(clearBinding = false)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            D.wifi("connectToCameraWifi aborted: WifiNetworkSpecifier requires API 29+")
            finish(false)
            return
        }

        val request = buildCameraWifiNetworkRequest(ssid, password)

        connectNetworkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                if (requestGeneration.get() != generation) return
                D.timeEnd("WIFI", "wifi_connect", "requestNetwork onAvailable: network=$network")
                requestedCameraNetwork = network
                D.wifi("Binding process to network")
                connectivityManager.bindProcessToNetwork(network)
                finishWhenCameraNetworkReady()
            }
            override fun onLost(network: Network) {
                super.onLost(network)
                if (requestGeneration.get() != generation) return
                D.wifi("requestNetwork onLost: network=$network")
                if (requestedCameraNetwork == network) {
                    requestedCameraNetwork = null
                }
                if (connectivityManager.boundNetworkForProcess == network) {
                    D.wifi("Unbinding process from lost network")
                    connectivityManager.bindProcessToNetwork(null)
                }
                D.wifi("Clearing stale requestNetwork callback after onLost")
                clearConnectCallback()
            }
            override fun onUnavailable() {
                super.onUnavailable()
                if (requestGeneration.get() != generation) return
                D.wifi("requestNetwork onUnavailable: user rejected or timeout")
                if (openSettingsOnFailure) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(
                            context,
                            "Auto-connect failed or timed out. Please connect manually.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
                requestedCameraNetwork = null
                finish(false)
            }
        }
        D.wifi("Calling requestNetwork for ssid=$ssid")
        try {
            connectivityManager.requestNetwork(request, connectNetworkCallback!!)
        } catch (e: SecurityException) {
            D.err("WIFI", "requestNetwork denied (missing CHANGE_NETWORK_STATE?)", e)
            finish(false)
            return
        }

        requestTimeoutRunnable = Runnable {
            if (requestGeneration.get() != generation) return@Runnable
            if (!isConnectedToCameraSsid(ssid)) {
                D.wifi("WiFi request timed out for $ssid after ${timeoutMillis}ms")
                finish(false)
            }
        }
        mainHandler.postDelayed(requestTimeoutRunnable!!, timeoutMillis)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildCameraWifiNetworkRequest(
        ssid: String,
        password: String,
    ): NetworkRequest {
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()

        return NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
    }

    fun clearRequestedCameraConnection(clearBinding: Boolean = true) {
        requestGeneration.incrementAndGet()
        resetRequestedCameraConnection(clearBinding)
        requestedCameraSsid = null
    }

    fun isCameraRequestInFlight(targetSsid: String? = null): Boolean {
        val activeRequest = connectNetworkCallback != null && requestedCameraNetwork == null
        if (!activeRequest) {
            return false
        }
        return targetSsid.isNullOrBlank() ||
            requestedCameraSsid.isNullOrBlank() ||
            requestedCameraSsid.equals(targetSsid, ignoreCase = true)
    }

    private fun resetRequestedCameraConnection(clearBinding: Boolean) {
        clearConnectCallback()
        cancelPendingRequestTimeout()
        requestedCameraNetwork = null
        try {
            if (clearBinding && connectivityManager.boundNetworkForProcess != null) {
                D.wifi("Clearing process network binding")
                connectivityManager.bindProcessToNetwork(null)
            }
        } catch (e: Exception) {
            D.err("WIFI", "Failed to clear network binding", e)
        }
    }

    suspend fun waitForCameraSsidVisibility(
        ssid: String,
        timeoutMillis: Long = 21000L,
        scanDelayMillis: Long = 3000L,
    ): Boolean = withContext(Dispatchers.IO) {
        val normalizedTarget = normalizeSsid(ssid) ?: return@withContext false
        if (isConnectedToCameraSsid(normalizedTarget) || isCameraSsidVisible(normalizedTarget)) {
            return@withContext true
        }

        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var scanAttempt = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            scanAttempt += 1
            requestCameraWifiScan(scanAttempt, normalizedTarget)
            delay(scanDelayMillis)
            if (isConnectedToCameraSsid(normalizedTarget) || isCameraSsidVisible(normalizedTarget)) {
                D.wifi("Camera SSID $normalizedTarget became visible after scan #$scanAttempt")
                return@withContext true
            }
        }
        D.wifi("Camera SSID $normalizedTarget did not appear within ${timeoutMillis}ms")
        false
    }

    private fun clearConnectCallback() {
        connectNetworkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        connectNetworkCallback = null
    }

    private fun cancelPendingRequestTimeout() {
        requestTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        requestTimeoutRunnable = null
    }

    private fun openWifiSettings() {
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            D.wifi("Failed to launch WiFi settings: ${e.message}")
        }
    }

    /**
     * Check if currently connected to a WiFi network that looks like a camera AP.
     * Camera WiFi typically has gateway 192.168.0.1 and no internet.
     */
    fun isCameraWifiConnected(): Boolean {
        if (isRequestedCameraNetworkActive()) {
            return true
        }
        val networks = connectivityManager.allNetworks
        for (network in networks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue

            val linkProperties = connectivityManager.getLinkProperties(network) ?: continue
            val gateway = linkProperties.routes
                .firstOrNull { it.isDefaultRoute }
                ?.gateway
                ?.hostAddress

            D.wifi("Network check: gateway=$gateway, hasInternet=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}")

            if (gateway != null && gateway.startsWith("192.168.0.")) {
                D.wifi("Camera WiFi detected via gateway $gateway")
                return true
            }
        }
        return false
    }

    fun isConnectedToCameraSsid(targetSsid: String?): Boolean {
        val state = currentState()
        return when (state) {
            is WifiConnectionState.CameraWifi -> {
                targetSsid.isNullOrBlank() ||
                    state.ssid == null ||
                    state.ssid.equals(targetSsid, ignoreCase = true)
            }
            else -> false
        }
    }

    private fun updateNetworkBinding() {
        var foundCameraNetwork: Network? = requestedCameraNetwork?.takeIf { isRequestedCameraNetworkActive() }
        val networks = connectivityManager.allNetworks
        D.wifi("updateNetworkBinding: checking ${networks.size} networks")
        if (foundCameraNetwork == null) {
            for (network in networks) {
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue

                val linkProperties = connectivityManager.getLinkProperties(network) ?: continue
                val gateway = linkProperties.routes
                    .firstOrNull { it.isDefaultRoute }
                    ?.gateway
                    ?.hostAddress

                if (gateway != null && gateway.startsWith("192.168.0.")) {
                    foundCameraNetwork = network
                    D.wifi("Found camera network: $network (gateway=$gateway)")
                    break
                }
            }
        }
        try {
            val currentBound = connectivityManager.boundNetworkForProcess
            if (currentBound != foundCameraNetwork) {
                D.wifi("Binding process: $currentBound -> $foundCameraNetwork")
                connectivityManager.bindProcessToNetwork(foundCameraNetwork)
            }
        } catch (e: Exception) {
            D.err("WIFI", "bindProcessToNetwork failed", e)
        }
    }

    /**
     * Returns the current camera Network object (192.168.0.x gateway),
     * or null if not connected to camera WiFi.
     * Used to bind UDP sockets to the correct network interface.
     */
    fun getCameraNetwork(): Network? {
        val boundNetwork = connectivityManager.boundNetworkForProcess
        if (isCameraNetwork(boundNetwork)) {
            return boundNetwork
        }
        requestedCameraNetwork?.takeIf { isCameraNetwork(it) }?.let { network ->
            return network
        }
        for (network in connectivityManager.allNetworks) {
            if (isCameraNetwork(network)) return network
        }
        return null
    }

    /**
     * Check if WiFi is enabled on the device.
     */
    fun isWifiEnabled(): Boolean = wifiManager.isWifiEnabled

    @Suppress("DEPRECATION")
    private fun requestCameraWifiScan(attempt: Int, ssid: String) {
        runCatching {
            D.wifi("Starting WiFi scan for $ssid (attempt #$attempt)")
            wifiManager.startScan()
        }.onFailure { error ->
            D.err("WIFI", "WiFi scan request failed for $ssid", error)
        }
    }

    /**
     * Get the current WiFi SSID (requires location permission on Android 8.0+).
     * Returns null if not connected to WiFi or permission not granted.
     */
    @Suppress("DEPRECATION")
    fun getCurrentSsid(): String? {
        if (!isWifiEnabled()) return null
        val info = wifiManager.connectionInfo ?: return null
        val ssid = info.ssid ?: return null
        // SSID comes wrapped in quotes, strip them
        val cleaned = ssid.removePrefix("\"").removeSuffix("\"").takeIf {
            it.isNotBlank() && it != "<unknown ssid>"
        }
        D.wifi("getCurrentSsid: raw=$ssid, cleaned=$cleaned")
        return cleaned
    }

    private fun isCameraSsidVisible(targetSsid: String): Boolean {
        val normalizedTarget = normalizeSsid(targetSsid) ?: return false
        val hasLocationPermission =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasNearbyWifiPermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        if (!hasLocationPermission && !hasNearbyWifiPermission) {
            D.wifi("Skipping Wi-Fi scan visibility check for $normalizedTarget: missing scan permission")
            return false
        }
        @SuppressLint("MissingPermission")
        val results = runCatching { wifiManager.scanResults }.getOrDefault(emptyList())
        val match = results.firstOrNull { result ->
            normalizeSsid(result.SSID)?.equals(normalizedTarget, ignoreCase = true) == true
        }
        if (match != null) {
            D.wifi("Matched scan result for $normalizedTarget with RSSI=${match.level}")
            return true
        }
        return false
    }

    /**
     * Emits WiFi connectivity changes as a Flow.
     * true = connected to WiFi, false = disconnected
     */
    fun observeWifiState(): Flow<WifiConnectionState> = callbackFlow {
        // Emit initial state
        updateNetworkBinding()
        val initial = currentState()
        D.wifi("observeWifiState: initial=$initial")
        trySend(initial)

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                D.wifi("Observer onAvailable: $network")
                updateNetworkBinding()
                trySend(currentState())
            }

            override fun onLost(network: Network) {
                D.wifi("Observer onLost: $network")
                updateNetworkBinding()
                trySend(currentState())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                D.wifi("Observer onCapabilitiesChanged: $network")
                updateNetworkBinding()
                trySend(currentState())
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        connectivityManager.registerNetworkCallback(request, networkCallback)
        D.wifi("WiFi network callback registered")

        awaitClose {
            D.wifi("WiFi network callback unregistered")
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
    }.distinctUntilChanged()

    private fun currentState(): WifiConnectionState {
        if (!isWifiEnabled()) return WifiConnectionState.WifiOff

        val boundNetwork = connectivityManager.boundNetworkForProcess
        if (isCameraNetwork(boundNetwork)) {
            return WifiConnectionState.CameraWifi(
                ssid = requestedCameraSsid ?: getCurrentSsid(),
            )
        }

        if (isRequestedCameraNetworkActive()) {
            return WifiConnectionState.CameraWifi(
                ssid = requestedCameraSsid ?: getCurrentSsid(),
            )
        }

        var hasWifiTransport = false
        var hasCameraGateway = false
        for (network in connectivityManager.allNetworks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
                continue
            }
            hasWifiTransport = true
            val linkProperties = connectivityManager.getLinkProperties(network) ?: continue
            val gateway = linkProperties.routes
                .firstOrNull { it.isDefaultRoute }
                ?.gateway
                ?.hostAddress
            if (gateway?.startsWith("192.168.0.") == true) {
                hasCameraGateway = true
                break
            }
        }

        if (!hasWifiTransport) {
            return WifiConnectionState.Disconnected
        }

        val currentSsid = getCurrentSsid()
        val state = if (hasCameraGateway) {
            WifiConnectionState.CameraWifi(ssid = currentSsid)
        } else {
            WifiConnectionState.OtherWifi(ssid = currentSsid)
        }
        D.wifi("currentState: $state")
        return state
    }

    private fun isRequestedCameraNetworkActive(): Boolean {
        val network = requestedCameraNetwork ?: return false
        return isCameraNetwork(network)
    }

    private fun isCameraNetwork(network: Network?): Boolean {
        if (network == null) return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return false
        }
        if (requestedCameraNetwork == network && !requestedCameraSsid.isNullOrBlank()) {
            return true
        }
        val linkProperties = connectivityManager.getLinkProperties(network)
        val gateway = linkProperties?.routes
            ?.firstOrNull { it.isDefaultRoute }
            ?.gateway
            ?.hostAddress
        if (gateway?.startsWith("192.168.0.") == true) {
            return true
        }
        val targetSsid = requestedCameraSsid ?: return false
        val currentSsid = getCurrentSsid()
        return requestedCameraNetwork == network &&
            currentSsid != null &&
            currentSsid.equals(targetSsid, ignoreCase = true)
    }

    private fun normalizeSsid(rawSsid: String?): String? {
        return rawSsid
            ?.trim()
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }
}

sealed interface WifiConnectionState {
    data object WifiOff : WifiConnectionState
    data object Disconnected : WifiConnectionState
    data class OtherWifi(val ssid: String?) : WifiConnectionState
    data class CameraWifi(val ssid: String?) : WifiConnectionState
}
