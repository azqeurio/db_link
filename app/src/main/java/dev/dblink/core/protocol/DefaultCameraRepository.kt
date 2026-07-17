package dev.dblink.core.protocol

import dev.dblink.core.config.AppConfig
import dev.dblink.core.config.AppEnvironment
import dev.dblink.core.logging.D
import dev.dblink.core.model.CameraNameNormalizer
import dev.dblink.core.model.CameraWorkspace
import dev.dblink.core.model.ConnectMode
import dev.dblink.core.model.ConnectionMethod
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import javax.xml.parsers.DocumentBuilderFactory
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.w3c.dom.Element
import org.w3c.dom.Node

private fun normalizeCameraBaseUrl(baseUrl: String): String {
    return baseUrl.trim().ifBlank { CameraProtocolCatalog.BaseUrl }.removeSuffix("/")
}

internal fun buildOiShareDirCandidates(directory: String): List<String> {
    val normalized = directory.trim().ifBlank { "/DCIM" }
    return buildList {
        add(normalized)
        val withoutLeadingSlash = normalized.trimStart('/')
        if (withoutLeadingSlash.isNotBlank() && withoutLeadingSlash != normalized) {
            add(withoutLeadingSlash)
        }
    }.filter { it.isNotBlank() }.distinct()
}

private data class OiShareDirQueryCandidate(
    val value: String,
    val useEncodedQuery: Boolean,
) {
    val logLabel: String
        get() = if (useEncodedQuery) "raw-slash:$value" else "url-encoded:$value"
}

private fun buildOiShareDirQueryCandidates(directory: String): List<OiShareDirQueryCandidate> {
    return buildOiShareDirCandidates(directory)
        .flatMap { value ->
            listOf(
                OiShareDirQueryCandidate(value = value, useEncodedQuery = true),
                OiShareDirQueryCandidate(value = value, useEncodedQuery = false),
            )
        }
        .distinct()
}

internal object OiShareImageListContinuationPolicy {
    const val MIN_PAGE_SIZE = 240
    const val MAX_PAGES = 32
    const val PAGE_DELAY_MS = 650L
    const val DUPLICATE_RETRY_DELAY_MS = 1_200L
    const val DUPLICATE_RETRY_LIMIT = 1
    private const val PAGED_RESPONSE_MAX_HINT = 320

    fun shouldRequestAnotherPage(receivedCount: Int, newEntryCount: Int): Boolean {
        return receivedCount in MIN_PAGE_SIZE..PAGED_RESPONSE_MAX_HINT && newEntryCount > 0
    }

    fun shouldRetryDuplicatePage(
        receivedCount: Int,
        newEntryCount: Int,
        duplicateRetriesUsed: Int,
    ): Boolean {
        return newEntryCount == 0 &&
            receivedCount in MIN_PAGE_SIZE..PAGED_RESPONSE_MAX_HINT &&
            duplicateRetriesUsed < DUPLICATE_RETRY_LIMIT
    }
}

internal object OiShareImageListReadinessPolicy {
    const val MAX_ATTEMPTS = 3

    fun settleDelayMillis(attemptIndex: Int): Long {
        return when (attemptIndex) {
            0 -> 250L
            1 -> 1_200L
            else -> 2_200L
        }
    }

    fun shouldRetryAfterFailure(error: Throwable?, attemptIndex: Int): Boolean {
        if (attemptIndex >= MAX_ATTEMPTS - 1) {
            return false
        }
        val message = error?.message.orEmpty()
        return "HTTP 404" in message ||
            "HTTP 503" in message ||
            "HTTP 520" in message ||
            "timeout" in message.lowercase(Locale.US) ||
            "timed out" in message.lowercase(Locale.US)
    }
}

class DefaultCameraRepository(
    private val config: AppConfig = AppEnvironment.current(),
    private val fallback: FakeCameraRepository = FakeCameraRepository(config),
) : CameraRepository {
    @Volatile private var activeBaseUrl: String = normalizeCameraBaseUrl(config.cameraBaseUrl)
    @Volatile private var gateway = CameraHttpGateway(baseUrl = activeBaseUrl)

    val currentCameraBaseUrl: String
        get() = activeBaseUrl

    fun useCameraBaseUrl(baseUrl: String) {
        val normalized = normalizeCameraBaseUrl(baseUrl)
        if (normalized == activeBaseUrl) {
            return
        }
        D.proto("Switching camera HTTP base URL: $activeBaseUrl -> $normalized")
        gateway.cancelPendingPropertyCalls()
        gateway.cancelPendingTransferCalls()
        activeBaseUrl = normalized
        gateway = CameraHttpGateway(baseUrl = normalized)
    }

    fun resetCameraBaseUrl() {
        useCameraBaseUrl(config.cameraBaseUrl)
    }

    suspend fun probeCameraBaseUrl(
        baseUrl: String,
        timeoutMillis: Int = 1_000,
    ): Result<String> = withContext(Dispatchers.IO) {
        val normalized = normalizeCameraBaseUrl(baseUrl)
        val probeGateway = CameraHttpGateway(baseUrl = normalized)
        val connectMode = probeGateway.getText(
            path = "/get_connectmode.cgi",
            timeoutMillis = timeoutMillis,
        )
        if (connectMode.getOrNull()?.let(CameraHttpProbeValidator::isCameraConnectModeResponse) == true) {
            return@withContext Result.success(normalized)
        }
        val camInfo = probeGateway.getText(
            path = "/get_caminfo.cgi",
            timeoutMillis = timeoutMillis,
        )
        if (camInfo.getOrNull()?.let(CameraHttpProbeValidator::isCameraInfoResponse) == true) {
            Result.success(normalized)
        } else {
            D.proto(
                "Rejected HTTP probe candidate $normalized " +
                    "connectmode=${connectMode.getOrNull()?.take(80) ?: connectMode.exceptionOrNull()?.message} " +
                    "caminfo=${camInfo.getOrNull()?.take(80) ?: camInfo.exceptionOrNull()?.message}",
            )
            Result.failure(
                connectMode.exceptionOrNull()
                    ?: camInfo.exceptionOrNull()
                    ?: IllegalStateException("No OM camera HTTP endpoint at $normalized"),
            )
        }
    }

    fun cancelPendingTransfers() {
        gateway.cancelPendingTransferCalls()
    }

    override fun initialWorkspace(): CameraWorkspace = fallback.initialWorkspace()

    override suspend fun probeConnectionReady(): Result<String> = withContext(Dispatchers.IO) {
        val probeTimeoutMs = maxOf(config.connectTimeoutMs, 3_500)
        fun attempt(attempt: Int): Result<String> {
            D.proto("Connection readiness probe attempt $attempt via get_connectmode")
            return gateway.getText(
                path = "/get_connectmode.cgi",
                timeoutMillis = probeTimeoutMs,
            ).map { "Camera ready" }
        }

        var lastResult: Result<String> = attempt(1)
        if (lastResult.isSuccess) {
            return@withContext lastResult
        }
        repeat(2) { retryIndex ->
            D.proto(
                "Connection readiness probe retry ${retryIndex + 2} " +
                    "after failure: ${lastResult.exceptionOrNull()?.message}",
            )
            delay(if (retryIndex == 0) 250L else 500L)
            lastResult = attempt(retryIndex + 2)
            if (lastResult.isSuccess) {
                return@withContext lastResult
            }
        }
        lastResult
    }

    override suspend fun loadWorkspace(): CameraWorkspace = withContext(Dispatchers.IO) {
        val workspaceBaseUrl = activeBaseUrl
        D.proto("Loading workspace from $workspaceBaseUrl")
        D.marker("Connection Handshake")

        // Stable connection sequence observed during device testing:
        // get_connectmode.cgi → get_caminfo.cgi → get_commandlist.cgi → set_utctimediff.cgi

        // Step 1: get_connectmode.cgi
        val connectModeResponse = gateway.getText(
            path = "/get_connectmode.cgi",
            timeoutMillis = 3000,
        ).getOrElse { error ->
            D.err("PROTO", "Step 1: get_connectmode failed", error)
            throw IllegalStateException("Camera unreachable at $workspaceBaseUrl: ${error.message}")
        }
        val connectMode = ConnectModeParser.parse(connectModeResponse)
        D.proto("Step 1: connectmode=$connectMode")

        // Step 2: get_caminfo.cgi
        val camInfoResult = gateway.getText(
            path = "/get_caminfo.cgi",
            timeoutMillis = 10000,
        )
        if (camInfoResult.isSuccess) {
            D.proto("Step 2: get_caminfo -> OK")
        } else {
            D.proto("Step 2: get_caminfo -> failed (non-fatal): ${camInfoResult.exceptionOrNull()?.message}")
        }

        // Step 3: get_commandlist.cgi
        val commandListXml = gateway.getText(
            path = "/get_commandlist.cgi",
            timeoutMillis = 10000,
        ).getOrElse { error ->
            D.err("PROTO", "Step 3: get_commandlist failed", error)
            throw IllegalStateException("Failed to load command list: ${error.message}")
        }
        D.proto("Step 3: commandlist received (${commandListXml.length} bytes)")

        // Step 4: set_utctimediff.cgi
        // Observed devices expect this call even when geotag_sync_clock is off.
        // It clears the camera's connection prompt, so always send it.
        val utcTimeFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val diffFormat = SimpleDateFormat("Z", Locale.getDefault())
        val now = Date()
        val utcTime = utcTimeFormat.format(now)
        val tzDiff = diffFormat.format(now)
        D.proto("Step 4: utctime=$utcTime, diff=$tzDiff")

        val activateResult = gateway.getText(
            path = "/set_utctimediff.cgi",
            query = mapOf(
                "utctime" to utcTime,
                "diff" to tzDiff,
            ),
            timeoutMillis = 3000,
        )
        if (activateResult.isSuccess) {
            D.proto("Step 4: set_utctimediff -> OK")
        } else {
            D.err("PROTO", "Step 4: set_utctimediff failed (non-fatal)", activateResult.exceptionOrNull())
        }

        D.marker("Connection Handshake Complete")
        val parsedCamInfo = parseCamInfo(camInfoResult.getOrNull())
        val cameraName = parsedCamInfo.cameraName ?: "OM SYSTEM Camera"
        val firmwareVersion = parsedCamInfo.firmwareVersion ?: "Unknown"
        val batteryPercent = parsedCamInfo.batteryPercent

        // Assemble workspace from parsed data
        WorkspaceAssembler.build(
            commandListXml = commandListXml,
            baseUrl = workspaceBaseUrl,
            sourceLabel = "Synced from $workspaceBaseUrl",
            connectMode = connectMode,
            connectionMethod = ConnectionMethod.Wifi,
            showProtocolWorkbench = config.showProtocolWorkbench,
            debugProtocolLogs = config.debugProtocolLogs,
            cameraName = cameraName,
            firmwareVersion = firmwareVersion,
            batteryPercent = batteryPercent,
        ).also {
            D.proto("Workspace assembly complete. Camera: ${it.camera.name}")
        }
    }

    override suspend fun refreshFromCommandList(rawCommandList: String): Result<CameraWorkspace> = withContext(Dispatchers.Default) {
        D.proto("Refreshing from custom command list...")
        runCatching {
            WorkspaceAssembler.build(
                commandListXml = rawCommandList,
                baseUrl = activeBaseUrl,
                sourceLabel = "Custom preview",
                connectMode = ConnectMode.Record,
                connectionMethod = ConnectionMethod.WifiBleAssist,
                showProtocolWorkbench = config.showProtocolWorkbench,
                debugProtocolLogs = config.debugProtocolLogs,
            )
        }.onSuccess {
            D.proto("Custom workspace assembly complete.")
        }.onFailure {
            D.err("PROTO", "Custom workspace assembly failed", it)
        }
    }

    override suspend fun switchCameraMode(mode: ConnectMode): Result<String> = withContext(Dispatchers.IO) {
        D.proto("Switching camera mode to: $mode")
        val modeQuery = when (mode) {
            ConnectMode.Record -> "rec"
            ConnectMode.Play -> "play"
            ConnectMode.Shutter -> "shutter"
            else -> return@withContext Result.failure(IllegalArgumentException("Cannot switch to $mode"))
        }
        gateway.getText(
            path = "/switch_cammode.cgi",
            query = mapOf("mode" to modeQuery),
            timeoutMillis = config.connectTimeoutMs,
        ).onSuccess {
            D.proto("Successfully switched to $mode")
        }.onFailure {
            D.err("PROTO", "Failed to switch mode to $mode", it)
        }.map { "Switched to ${mode.name}" }
    }

    override suspend fun activateRecMode(): Result<String> = withContext(Dispatchers.IO) {
        D.proto("Activating rec mode with imaging pipeline (lvqty)")
        gateway.getText(
            path = "/switch_cammode.cgi",
            query = mapOf("mode" to "rec", "lvqty" to liveViewQualityParam()),
            timeoutMillis = 10000,
        ).onSuccess {
            D.proto("Rec mode activated (imaging pipeline ready)")
        }.onFailure {
            D.err("PROTO", "Failed to activate rec mode", it)
        }.map { "Rec mode active" }
    }

    override suspend fun syncCameraTime(): Result<String> = withContext(Dispatchers.IO) {
        val utcTimeFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val diffFormat = SimpleDateFormat("Z", Locale.getDefault())
        val now = Date()
        val utcTime = utcTimeFormat.format(now)
        val tzDiff = diffFormat.format(now)
        D.proto("syncCameraTime: utctime=$utcTime, diff=$tzDiff")

        gateway.getText(
            path = "/set_utctimediff.cgi",
            query = mapOf(
                "utctime" to utcTime,
                "diff" to tzDiff,
            ),
            timeoutMillis = 3000,
        ).onSuccess {
            D.proto("syncCameraTime: set_utctimediff -> OK — camera clock synced")
        }.onFailure {
            D.err("PROTO", "syncCameraTime: set_utctimediff failed (non-fatal)", it)
        }.map { "Camera time synced" }
    }

    override suspend fun setSessionTimeout(timeoutSeconds: Int): Result<String> = withContext(Dispatchers.IO) {
        D.proto("Setting session timeout to ${timeoutSeconds}s")
        gateway.getText(
            path = "/set_timeout.cgi",
            query = mapOf("timeoutsec" to timeoutSeconds.toString()),
            timeoutMillis = 3000,
        ).onSuccess {
            D.proto("Session timeout set to ${timeoutSeconds}s")
        }.onFailure {
            D.proto("set_timeout failed (non-fatal): ${it.message}")
        }.map { "Timeout set" }
    }

    override suspend fun startLiveView(port: Int): Result<String> = withContext(Dispatchers.IO) {
        D.proto("Starting live view on port $port")
        val modeResult = gateway.getText(
            path = "/switch_cammode.cgi",
            query = mapOf("mode" to "rec", "lvqty" to liveViewQualityParam()),
            timeoutMillis = 10000,
        )
        if (modeResult.isFailure) {
            D.err("PROTO", "Failed to switch to REC mode for live view", modeResult.exceptionOrNull())
            return@withContext modeResult.map { "Failed to switch mode" }
        }

        gateway.getText(
            path = "/exec_takemisc.cgi",
            query = mapOf("com" to "startliveview", "port" to port.toString()),
            timeoutMillis = 10000,
        ).onSuccess {
            D.proto("Live view command accepted.")
        }.onFailure {
            D.err("PROTO", "Failed to start live view", it)
        }.map { "Live View on port $port" }
    }

    override suspend fun stopLiveView(): Result<String> = withContext(Dispatchers.IO) {
        D.proto("Stopping live view")
        val stopResult = gateway.getText(
            path = "/exec_takemisc.cgi",
            query = mapOf("com" to "stopliveview"),
            timeoutMillis = 10000,
        )
        // Return to playback after stopliveview so the camera stays awake on Wi-Fi.
        if (stopResult.isSuccess) {
            D.proto("Switching to playback mode to keep camera session alive")
            gateway.getText(
                path = "/switch_cammode.cgi",
                query = mapOf("mode" to "play"),
                timeoutMillis = 10000,
            ).onFailure {
                D.err("PROTO", "switch_cammode(play) after stopliveview failed", it)
            }
        }
        stopResult.map { "Live View stopped" }
    }

    override suspend fun triggerCapture(): Result<String> = withContext(Dispatchers.IO) {
        D.proto("Triggering capture sequence...")

        // Switch to rec mode WITH viewfinder activation (lvqty parameter).
        // Without lvqty, the camera's imaging pipeline stays inactive and takeready returns 520.
        val modeResult = gateway.getText(
            path = "/switch_cammode.cgi",
            query = mapOf("mode" to "rec", "lvqty" to liveViewQualityParam()),
            timeoutMillis = 10000,
        )
        if (modeResult.isFailure) {
            D.err("PROTO", "Failed to switch to rec mode for capture", modeResult.exceptionOrNull())
            return@withContext modeResult.map { "Failed to switch mode" }
        }
        D.proto("Switched to rec mode (with viewfinder) for capture")

        // Camera needs time to fully activate the imaging pipeline after mode switch.
        // Retry takeready up to 8 times with 1s delays (camera returns 520 until ready).
        var focusResult: Result<String>? = null
        for (attempt in 1..8) {
            kotlinx.coroutines.delay(1000)
            focusResult = gateway.getText(
                path = "/exec_takemotion.cgi",
                query = mapOf("com" to "takeready"),
                timeoutMillis = 15000,
            )
            if (focusResult.isSuccess) {
                D.proto("takeready OK on attempt $attempt")
                break
            }
            val errorMsg = focusResult.exceptionOrNull()?.message ?: ""
            if ("520" in errorMsg && attempt < 8) {
                D.proto("takeready got 520 on attempt $attempt, retrying...")
                continue
            }
            break
        }

        if (focusResult?.isFailure == true) {
            D.err("PROTO", "Capture failed at focus stage after retries", focusResult.exceptionOrNull())
            return@withContext Result.failure(focusResult.exceptionOrNull()!!)
        }

        // Give autofocus time to lock after takeready succeeds.
        // 300ms has been stable across tested bodies.
        kotlinx.coroutines.delay(300)

        val captureResult = gateway.getText(
            path = "/exec_takemotion.cgi",
            query = mapOf("com" to "starttake"),
            timeoutMillis = 15000,
        )

        // stopaim can fail with 520 — that's ok, ignore errors
        runCatching {
            gateway.getText(
                path = "/exec_takemotion.cgi",
                query = mapOf("com" to "stopaim"),
                timeoutMillis = 10000,
            )
        }

        captureResult.onFailure {
            D.err("PROTO", "Capture sequence failed at starttake stage", it)
        }

        // Check response body for <take>ng</take> which means capture failed at camera level
        captureResult.mapCatching { responseBody ->
            if (responseBody.contains("<take>ng</take>")) {
                D.err("PROTO", "Camera reported capture failure: <take>ng</take>")
                error("Camera autofocus/capture failed. Try again.")
            }
            D.proto("Capture sequence successful.")
            "Captured"
        }
    }

    /**
     * Capture while live view is active — camera is already in rec mode,
     * so we skip mode switch and retry logic. Just takeready → starttake → stopaim.
     */
    override suspend fun captureWhileLiveView(): Result<String> = withContext(Dispatchers.IO) {
        D.proto("captureWhileLiveView: takeready...")

        // takeready (half-press) — camera should be ready quickly since LV is active
        val focusResult = gateway.getText(
            path = "/exec_takemotion.cgi",
            query = mapOf("com" to "takeready"),
            timeoutMillis = 15000,
        )
        if (focusResult.isFailure) {
            D.err("PROTO", "captureWhileLiveView: takeready failed", focusResult.exceptionOrNull())
            return@withContext Result.failure(focusResult.exceptionOrNull()!!)
        }
        D.proto("captureWhileLiveView: takeready OK, waiting for AF lock...")

        // Brief delay for AF lock
        kotlinx.coroutines.delay(300)

        // starttake
        val captureResult = gateway.getText(
            path = "/exec_takemotion.cgi",
            query = mapOf("com" to "starttake"),
            timeoutMillis = 15000,
        )

        // stopaim (release)
        runCatching {
            gateway.getText(
                path = "/exec_takemotion.cgi",
                query = mapOf("com" to "stopaim"),
                timeoutMillis = 10000,
            )
        }

        captureResult.mapCatching { responseBody ->
            if (responseBody.contains("<take>ng</take>")) {
                D.err("PROTO", "Camera reported capture failure: <take>ng</take>")
                error("AF/Capture failed. Please try again.")
            }
            D.proto("captureWhileLiveView: success")
            "Captured"
        }
    }

    override suspend fun getPropertyDesc(propName: String): Result<CameraPropertyDesc> = withContext(Dispatchers.IO) {
        D.proto("Getting property desc for $propName")
        getCameraPropertyDescText(propName).map { responseXml ->
            val parsed = parsePropertyDescXml(responseXml)
            val currentValue = parsed.currentValue
            val attribute = parsed.attribute
            val enumValues = parsed.enumValues
            D.proto("Property $propName: value=$currentValue, ${enumValues.size} options")
            CameraPropertyDesc(
                propName = propName,
                attribute = attribute,
                currentValue = currentValue,
                enumValues = enumValues,
                minValue = parsed.minValue,
                maxValue = parsed.maxValue,
            )
        }.onFailure {
            D.err("PROTO", "Failed to get property desc for $propName", it)
        }
    }

    override suspend fun getPropertyDescList(): Result<Map<String, CameraPropertyDesc>> = withContext(Dispatchers.IO) {
        D.proto("Getting property desc list")
        getCameraPropertyDescText("desclist").map { responseXml ->
            parsePropertyDescListXml(responseXml)
        }.onFailure {
            D.err("PROTO", "Failed to get property desc list", it)
        }
    }

    private suspend fun getCameraPropertyDescText(propName: String): Result<String> {
        var lastResult: Result<String>? = null
        repeat(PROPERTY_DESC_MAX_ATTEMPTS) { attempt ->
            val result = gateway.getText(
                path = "/get_camprop.cgi",
                query = mapOf("com" to "desc", "propname" to propName),
                timeoutMillis = config.readTimeoutMs,
            )
            if (result.isSuccess) {
                return result
            }
            lastResult = result
            val shouldRetry = attempt < PROPERTY_DESC_MAX_ATTEMPTS - 1 &&
                isTransientPropertyDescFailure(result.exceptionOrNull())
            if (!shouldRetry) {
                return result
            }
            D.proto(
                "Retrying property desc for $propName after transient failure: " +
                    result.exceptionOrNull()?.message,
            )
            delay(PROPERTY_DESC_RETRY_DELAY_MS)
        }
        return lastResult ?: Result.failure(IllegalStateException("Failed to get property desc for $propName"))
    }

    private fun isTransientPropertyDescFailure(error: Throwable?): Boolean {
        val message = error?.message.orEmpty()
        return "HTTP 520" in message || "HTTP 503" in message
    }

    override suspend fun setTakeMode(modeValue: String): Result<String> = withContext(Dispatchers.IO) {
        D.proto("Setting take mode to $modeValue")
        setProperty("takemode", modeValue)
            .onSuccess {
            D.proto("Take mode set successfully.")
        }
            .onFailure {
            D.err("PROTO", "Failed to set take mode $modeValue", it)
        }
            .map { "takemode set to $modeValue" }
    }

    override suspend fun setProperty(propName: String, propValue: String): Result<String> = withContext(Dispatchers.IO) {
        D.proto("Setting property $propName to $propValue")
        val xmlBody = "<?xml version=\"1.0\"?><set><value>$propValue</value></set>"
        // Olympus cameras process HTTP requests serially. When the user scrolls
        // a dial rapidly, earlier set_camprop calls may still be waiting for a
        // response — making the camera appear unresponsive and causing the new
        // call to time out. Use a longer timeout (10s) and rely on the gateway's
        // automatic stale-call cancellation to unblock the socket.
        gateway.postText(
            path = "/set_camprop.cgi",
            query = mapOf("com" to "set", "propname" to propName),
            body = xmlBody,
            timeoutMillis = config.readTimeoutMs,
        ).onSuccess {
            D.proto("Property $propName set successfully.")
        }.onFailure {
            D.err("PROTO", "Failed to set property $propName", it)
        }.map { "$propName set to $propValue" }
    }

    override suspend fun getPropertyCheck(propName: String): Result<Boolean> = withContext(Dispatchers.IO) {
        D.proto("Checking property change state for $propName")
        gateway.getText(
            path = "/get_camprop.cgi",
            query = mapOf("com" to "check", "propname" to propName),
            timeoutMillis = config.readTimeoutMs,
        ).mapCatching { responseBody ->
            val normalized = responseBody.trim().lowercase(Locale.US)
            when {
                "notchanged" in normalized -> false
                "changed" in normalized -> true
                else -> error("Unexpected property check response for $propName: $responseBody")
            }
        }.onFailure {
            D.err("PROTO", "Failed to check property change state for $propName", it)
        }
    }

    override fun sampleCommandListXml(): String = fallback.sampleCommandListXml()

    // Image Transfer
    override suspend fun getImageList(directory: String): Result<List<CameraImage>> = withContext(Dispatchers.IO) {
        D.transfer("Getting image list for $directory")

        val topResult = fetchImageListEntriesAfterPlayReady(directory)
        if (topResult.isFailure) {
            D.err("TRANSFER", "Failed to get image list for $directory", topResult.exceptionOrNull())
            return@withContext topResult
        }

        val topEntries = topResult.getOrThrow()
        val directories = topEntries.filter { it.isDirectory }
        val files = topEntries.filter { !it.isDirectory }.toMutableList()

        D.transfer("Top-level: ${directories.size} directories, ${files.size} files in $directory")

        // Recursively fetch files from each subdirectory
        for (dir in directories) {
            val subDir = "${dir.directory}/${dir.fileName}"
            D.transfer("Fetching subdirectory: $subDir")
            fetchImageListEntries(subDir).onSuccess { subFiles ->
                val actualFiles = subFiles.filter { !it.isDirectory }
                D.transfer("Got ${actualFiles.size} files from $subDir")
                files.addAll(actualFiles)
            }.onFailure {
                D.err("TRANSFER", "Failed to get image list for subdirectory $subDir", it)
            }
        }

        D.transfer("Total: ${files.size} images from $directory")
        Result.success(files)
    }

    private suspend fun fetchImageListEntriesAfterPlayReady(directory: String): Result<List<CameraImage>> {
        var lastResult: Result<List<CameraImage>> =
            Result.failure(IllegalStateException("Image list was not requested"))
        repeat(OiShareImageListReadinessPolicy.MAX_ATTEMPTS) { attemptIndex ->
            val attempt = attemptIndex + 1
            val modeResult = gateway.getText(
                path = "/switch_cammode.cgi",
                query = mapOf("mode" to "play"),
                timeoutMillis = 10000,
            )
            if (modeResult.isFailure) {
                D.err(
                    "TRANSFER",
                    "Failed to switch to play mode for image list (attempt $attempt)",
                    modeResult.exceptionOrNull(),
                )
            } else {
                D.transfer("Image list play-mode preparation complete (attempt $attempt)")
            }

            delay(OiShareImageListReadinessPolicy.settleDelayMillis(attemptIndex))
            val listResult = fetchImageListEntries(directory)
            if (listResult.isSuccess) {
                return listResult
            }
            lastResult = listResult
            val throwable = listResult.exceptionOrNull()
            if (!OiShareImageListReadinessPolicy.shouldRetryAfterFailure(throwable, attemptIndex)) {
                return listResult
            }
            D.transfer(
                "Image list for $directory failed after play preparation attempt $attempt; " +
                    "retrying after camera settle (${throwable?.message})",
            )
        }
        return lastResult
    }

    private suspend fun fetchImageListEntries(directory: String): Result<List<CameraImage>> {
        val mergedEntries = linkedMapOf<String, CameraImage>()
        var pageIndex = 0
        var nextDelayMs = 0L
        var duplicateRetriesUsed = 0
        while (pageIndex < OiShareImageListContinuationPolicy.MAX_PAGES) {
            if (nextDelayMs > 0L) {
                delay(nextDelayMs)
                D.transfer("Continuing image list for $directory (page ${pageIndex + 1})")
            }
            val pageResult = getTextWithOiShareDirFallback(
                path = "/get_imglist.cgi",
                directory = directory,
                timeoutMillis = 10000,
            ).map { response ->
                ImageListParser.parse(response)
            }
            if (pageResult.isFailure) {
                return if (mergedEntries.isNotEmpty()) {
                    D.err(
                        "TRANSFER",
                        "Continuing image list for $directory failed after ${mergedEntries.size} merged entries",
                        pageResult.exceptionOrNull(),
                    )
                    Result.success(mergedEntries.values.toList())
                } else {
                    pageResult
                }
            }

            val pageEntries = pageResult.getOrThrow()
            var newEntries = 0
            pageEntries.forEach { entry ->
                if (mergedEntries.putIfAbsent(entry.fullPath, entry) == null) {
                    newEntries += 1
                }
            }
            D.transfer(
                "Image list page ${pageIndex + 1} for $directory: " +
                    "received=${pageEntries.size}, new=$newEntries, merged=${mergedEntries.size}",
            )
            if (!OiShareImageListContinuationPolicy.shouldRequestAnotherPage(pageEntries.size, newEntries)) {
                if (
                    OiShareImageListContinuationPolicy.shouldRetryDuplicatePage(
                        receivedCount = pageEntries.size,
                        newEntryCount = newEntries,
                        duplicateRetriesUsed = duplicateRetriesUsed,
                    )
                ) {
                    duplicateRetriesUsed += 1
                    pageIndex += 1
                    nextDelayMs = OiShareImageListContinuationPolicy.DUPLICATE_RETRY_DELAY_MS
                    D.transfer(
                        "Image list for $directory returned a duplicate ${pageEntries.size}-entry " +
                            "page; retrying after settle delay",
                    )
                    continue
                }
                return Result.success(mergedEntries.values.toList())
            }
            duplicateRetriesUsed = 0
            pageIndex += 1
            nextDelayMs = OiShareImageListContinuationPolicy.PAGE_DELAY_MS
        }
        D.transfer(
            "Image list continuation limit reached for $directory " +
                "(${mergedEntries.size} merged entries)",
        )
        return Result.success(mergedEntries.values.toList())
    }

    private fun isHttpNotFound(error: Throwable?): Boolean {
        return "HTTP 404" in error?.message.orEmpty()
    }

    private fun isHttpBadRequest(error: Throwable?): Boolean {
        return "HTTP 400" in error?.message.orEmpty()
    }

    private fun shouldTryAlternateOiShareDir(error: Throwable?): Boolean {
        return isHttpNotFound(error) || isHttpBadRequest(error)
    }

    private fun getBytesWithOiShareDirFallback(
        path: String,
        directory: String,
        timeoutMillis: Int,
        extraQuery: Map<String, String> = emptyMap(),
    ): Result<ByteArray> {
        var lastResult: Result<ByteArray> =
            Result.failure(IllegalStateException("Transfer bytes were not requested"))
        buildOiShareDirQueryCandidates(directory).forEachIndexed { index, candidate ->
            if (index > 0) {
                D.transfer("Trying alternate OI.Share DIR format for $path: ${candidate.logLabel}")
            }
            val query = buildMap {
                put("DIR", candidate.value)
                putAll(extraQuery)
            }
            val result = if (candidate.useEncodedQuery) {
                gateway.getBytesWithEncodedQuery(
                    path = path,
                    query = query,
                    timeoutMillis = timeoutMillis,
                )
            } else {
                gateway.getBytes(
                    path = path,
                    query = query,
                    timeoutMillis = timeoutMillis,
                )
            }
            if (result.isSuccess) {
                return result
            }
            lastResult = result
            if (!shouldTryAlternateOiShareDir(result.exceptionOrNull())) {
                return result
            }
        }
        return lastResult
    }

    private fun getTextWithOiShareDirFallback(
        path: String,
        directory: String,
        timeoutMillis: Int,
        extraQuery: Map<String, String> = emptyMap(),
    ): Result<String> {
        var lastResult: Result<String> =
            Result.failure(IllegalStateException("Transfer text was not requested"))
        buildOiShareDirQueryCandidates(directory).forEachIndexed { index, candidate ->
            if (index > 0) {
                D.transfer("Trying alternate OI.Share DIR format for $path: ${candidate.logLabel}")
            }
            val query = buildMap {
                put("DIR", candidate.value)
                putAll(extraQuery)
            }
            val result = if (candidate.useEncodedQuery) {
                gateway.getTextWithEncodedQuery(
                    path = path,
                    query = query,
                    timeoutMillis = timeoutMillis,
                )
            } else {
                gateway.getText(
                    path = path,
                    query = query,
                    timeoutMillis = timeoutMillis,
                )
            }
            if (result.isSuccess) {
                return result
            }
            lastResult = result
            if (!shouldTryAlternateOiShareDir(result.exceptionOrNull())) {
                return result
            }
        }
        return lastResult
    }

    override suspend fun getThumbnail(image: CameraImage): Result<ByteArray> = withContext(Dispatchers.IO) {
        D.transfer("Getting thumbnail for ${image.fileName}")
        getBytesWithOiShareDirFallback(
            path = "/get_thumbnail.cgi",
            directory = image.fullPath,
            timeoutMillis = 8000,
        ).onFailure {
            D.err("TRANSFER", "Failed to get thumbnail for ${image.fileName}", it)
        }
    }

    override suspend fun getPreviewThumbnail(image: CameraImage): Result<ByteArray> = withContext(Dispatchers.IO) {
        D.transfer("Getting preview thumbnail for ${image.fileName}")
        getBytesWithOiShareDirFallback(
            path = "/get_screennail.cgi",
            directory = image.fullPath,
            timeoutMillis = 12000,
        ).onFailure {
            D.err("TRANSFER", "Failed to get preview thumbnail for ${image.fileName}", it)
        }
    }

    override suspend fun getResizedImageWithErr(image: CameraImage, width: Int): Result<ByteArray> = withContext(Dispatchers.IO) {
        D.transfer("Getting resized image witherr for ${image.fileName} (width=$width)")
        getBytesWithOiShareDirFallback(
            path = "/get_resizeimg_witherr.cgi",
            directory = image.fullPath,
            extraQuery = mapOf("size" to width.toString()),
            timeoutMillis = 15000,
        ).onFailure {
            D.err("TRANSFER", "Failed to get resized image witherr for ${image.fileName}", it)
        }
    }

    override suspend fun getResizedImage(image: CameraImage, width: Int): Result<ByteArray> = withContext(Dispatchers.IO) {
        D.transfer("Getting resized image for ${image.fileName} (width=$width)")
        getBytesWithOiShareDirFallback(
            path = "/get_resizeimg.cgi",
            directory = image.fullPath,
            extraQuery = mapOf("size" to width.toString()),
            timeoutMillis = 15000,
        ).onFailure {
            D.err("TRANSFER", "Failed to get resized image for ${image.fileName}", it)
        }
    }

    override suspend fun getFullImage(image: CameraImage): Result<ByteArray> = withContext(Dispatchers.IO) {
        D.transfer("Downloading full image ${image.fileName} (${image.fileSize} bytes)")
        gateway.getBytes(
            path = image.fullPath,
            timeoutMillis = fullImageTimeoutMillis(image.fileSize),
        ).onSuccess {
            D.transfer("Downloaded ${image.fileName}: ${it.size} bytes")
        }.onFailure {
            D.err("TRANSFER", "Failed to download ${image.fileName}", it)
        }
    }

    override suspend fun downloadFullImageTo(
        image: CameraImage,
        output: OutputStream,
        onProgress: ((bytesCopied: Long, totalBytes: Long) -> Unit)?,
    ): Result<Long> = withContext(Dispatchers.IO) {
        D.transfer("Streaming full image ${image.fileName} (${image.fileSize} bytes)")
        gateway.copyBytesTo(
            path = image.fullPath,
            output = output,
            timeoutMillis = fullImageTimeoutMillis(image.fileSize),
            expectedBytes = image.fileSize,
            onProgress = onProgress,
        ).onSuccess { bytesCopied ->
            val sizeLabel = if (bytesCopied >= 0L) bytesCopied.toString() else "unknown"
            D.transfer("Streamed ${image.fileName}: $sizeLabel bytes")
        }.onFailure {
            D.err("TRANSFER", "Failed to stream ${image.fileName}", it)
        }
    }

    override suspend fun getPlayTargetSlot(): Result<Int> = withContext(Dispatchers.IO) {
        gateway.getText(
            path = "/switch_cammode.cgi",
            query = mapOf("mode" to "play"),
            timeoutMillis = 10000,
        ).onFailure {
            D.err("TRANSFER", "Failed to switch to play mode before getting play target slot", it)
        }
        gateway.getText(
            path = "/get_playtargetslot.cgi",
            timeoutMillis = 10000,
        ).mapCatching { response ->
            parsePlayTargetSlot(response)
        }.onFailure {
            D.err("TRANSFER", "Failed to get play target slot", it)
        }
    }

    override suspend fun setPlayTargetSlot(slot: Int): Result<Int> = withContext(Dispatchers.IO) {
        require(slot in 1..2) { "Unsupported play target slot: $slot" }
        gateway.getText(
            path = "/switch_cammode.cgi",
            query = mapOf("mode" to "play"),
            timeoutMillis = 10000,
        ).onFailure {
            D.err("TRANSFER", "Failed to switch to play mode before setting play target slot", it)
        }
        gateway.getText(
            path = "/set_playtargetslot.cgi",
            query = mapOf("targetslot" to slot.toString()),
            timeoutMillis = 10000,
        ).map { slot }.onFailure {
            D.err("TRANSFER", "Failed to set play target slot to $slot", it)
        }
    }

    override suspend fun deleteImage(image: CameraImage): Result<String> = withContext(Dispatchers.IO) {
        D.transfer("Deleting image on camera: ${image.fullPath}")
        gateway.getText(
            path = "/switch_cammode.cgi",
            query = mapOf("mode" to "play"),
            timeoutMillis = 10000,
        ).onFailure {
            D.err("TRANSFER", "Failed to switch to play mode before delete", it)
        }
        getTextWithOiShareDirFallback(
            path = "/exec_erase.cgi",
            directory = image.fullPath,
            timeoutMillis = 15000,
        ).onSuccess {
            D.transfer("Deleted image on camera: ${image.fullPath}")
        }.onFailure {
            D.err("TRANSFER", "Failed to delete ${image.fullPath}", it)
        }.map { "Deleted ${image.fileName}" }
    }

    override suspend fun powerOffCamera(useWithBleMode: Boolean): Result<String> = withContext(Dispatchers.IO) {
        D.proto("Powering off camera via exec_pwoff (withBle=$useWithBleMode)")
        gateway.getText(
            path = "/exec_pwoff.cgi",
            query = if (useWithBleMode) {
                mapOf("mode" to "withble")
            } else {
                emptyMap()
            },
            timeoutMillis = 5000,
        ).onSuccess {
            D.proto("Camera power-off command accepted")
        }.onFailure {
            D.err("PROTO", "Failed to power off camera", it)
        }.map { "Camera power off requested" }
    }

    override suspend fun assignAfFrame(pointX: Int, pointY: Int): Result<AssignedAfFrame> = withContext(Dispatchers.IO) {
        val point = String.format(Locale.US, "%04dx%04d", pointX, pointY)
        D.proto("Assigning AF frame at $point")
        gateway.getText(
            path = "/exec_takemotion.cgi",
            query = mapOf("com" to "assignafframe", "point" to point),
            timeoutMillis = config.readTimeoutMs,
        ).mapCatching { responseBody ->
            if (responseBody.contains("<affocus>ng</affocus>")) {
                error("Camera could not focus at the specified position.")
            }
            val confirmedPoint = parseAfFramePoint(responseBody)
            val assignment = AssignedAfFrame(
                requestedX = pointX,
                requestedY = pointY,
                confirmedX = confirmedPoint?.first ?: pointX,
                confirmedY = confirmedPoint?.second ?: pointY,
            )
            D.proto(
                "AF frame assigned at $point " +
                    "(confirmed=${assignment.confirmedX}x${assignment.confirmedY})",
            )
            assignment
        }.onFailure {
            D.err("PROTO", "Failed to assign AF frame at $point", it)
        }
    }

    override suspend fun releaseAfFrame(): Result<String> = withContext(Dispatchers.IO) {
        D.proto("Releasing AF frame")
        gateway.getText(
            path = "/exec_takemotion.cgi",
            query = mapOf("com" to "releaseafframe"),
            timeoutMillis = config.readTimeoutMs,
        ).map { "AF frame released" }
    }

    override suspend fun halfPressShutter(): Result<String> = withContext(Dispatchers.IO) {
        D.proto("Half-pressing shutter...")
        gateway.getText(
            path = "/exec_takemotion.cgi",
            query = mapOf("com" to "takeready"),
            timeoutMillis = config.readTimeoutMs,
        ).map { "Focus locked" }
    }

    override suspend fun releaseShutterFocus(): Result<String> = withContext(Dispatchers.IO) {
        D.proto("Releasing shutter focus...")
        gateway.getText(
            path = "/exec_takemotion.cgi",
            query = mapOf("com" to "stopaim"),
            timeoutMillis = config.readTimeoutMs,
        ).map { "Focus released" }
    }

    private data class ParsedPropertyDesc(
        val attribute: String,
        val currentValue: String,
        val enumValues: List<String>,
        val minValue: String?,
        val maxValue: String?,
    )

    private data class ParsedCamInfo(
        val cameraName: String?,
        val firmwareVersion: String?,
        val batteryPercent: Int?,
    )

    private fun parsePropertyDescXml(xml: String): ParsedPropertyDesc {
        return runCatching {
            val factory = newCameraXmlFactory()
            val builder = factory.newDocumentBuilder()
            val document = ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)).use(builder::parse)
            parsePropertyDescElement(document.documentElement)
        }.getOrElse {
            D.err("PROTO", "Failed to parse property desc XML, falling back to regex", it)
            val currentValue = Regex("<value>(.*?)</value>", setOf(RegexOption.DOT_MATCHES_ALL))
                .find(xml)
                ?.groupValues
                ?.get(1)
                ?.trim()
                .orEmpty()
            val attribute = Regex("<attribute>(.*?)</attribute>", setOf(RegexOption.DOT_MATCHES_ALL))
                .find(xml)
                ?.groupValues
                ?.get(1)
                ?.trim()
                .orEmpty()
            val enumValues = Regex("<enum>(.*?)</enum>", setOf(RegexOption.DOT_MATCHES_ALL))
                .find(xml)
                ?.groupValues
                ?.get(1)
                ?.split(Regex("\\s+"))
                ?.map { value -> value.trim() }
                ?.filter { value -> value.isNotEmpty() }
                .orEmpty()
            val minValue = Regex("<min>(.*?)</min>", setOf(RegexOption.DOT_MATCHES_ALL))
                .find(xml)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val maxValue = Regex("<max>(.*?)</max>", setOf(RegexOption.DOT_MATCHES_ALL))
                .find(xml)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ParsedPropertyDesc(
                attribute = attribute,
                currentValue = currentValue,
                enumValues = enumValues,
                minValue = minValue,
                maxValue = maxValue,
            )
        }
    }

    private fun parsePropertyDescListXml(xml: String): Map<String, CameraPropertyDesc> {
        return runCatching {
            val factory = newCameraXmlFactory()
            val builder = factory.newDocumentBuilder()
            val document = ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)).use(builder::parse)
            val root = document.documentElement
            val descNodes = mutableListOf<Element>()
            if (root.tagName.equals("desc", ignoreCase = true)) {
                descNodes += root
            } else {
                val nodes = root.getElementsByTagName("desc")
                for (index in 0 until nodes.length) {
                    val node = nodes.item(index)
                    if (node is Element) {
                        descNodes += node
                    }
                }
            }
            descNodes.mapNotNull { descElement ->
                val propName = firstText(descElement, "propname")
                if (propName.isBlank()) {
                    null
                } else {
                    val parsed = parsePropertyDescElement(descElement)
                    propName to CameraPropertyDesc(
                        propName = propName,
                        attribute = parsed.attribute,
                        currentValue = parsed.currentValue,
                        enumValues = parsed.enumValues,
                        minValue = parsed.minValue,
                        maxValue = parsed.maxValue,
                    )
                }
            }.toMap()
        }.getOrElse {
            D.err("PROTO", "Failed to parse property desc list XML", it)
            emptyMap()
        }
    }

    private fun parsePropertyDescElement(root: Element): ParsedPropertyDesc {
        val valueElement = root.getElementsByTagName("value").item(0) as? Element
        val enumElement = root.getElementsByTagName("enum").item(0) as? Element
        val enumValues = firstText(root, "enum")
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val minValue = firstNonBlank(
            root.getAttribute("min"),
            valueElement?.getAttribute("min").orEmpty(),
            enumElement?.getAttribute("min").orEmpty(),
            firstText(root, "min"),
            firstText(root, "minimum"),
            firstText(root, "minvalue"),
            firstText(root, "selectedmin"),
            findNamedRangeCandidate(root, token = "min"),
        )
        val maxValue = firstNonBlank(
            root.getAttribute("max"),
            valueElement?.getAttribute("max").orEmpty(),
            enumElement?.getAttribute("max").orEmpty(),
            firstText(root, "max"),
            firstText(root, "maximum"),
            firstText(root, "maxvalue"),
            firstText(root, "selectedmax"),
            findNamedRangeCandidate(root, token = "max"),
        )
        return ParsedPropertyDesc(
            attribute = firstText(root, "attribute"),
            currentValue = firstText(root, "value"),
            enumValues = enumValues,
            minValue = minValue,
            maxValue = maxValue,
        )
    }

    private fun firstText(root: Element, tag: String): String {
        val nodes = root.getElementsByTagName(tag)
        if (nodes.length == 0) return ""
        return nodes.item(0)?.textContent?.trim().orEmpty()
    }

    private fun parseCamInfo(xml: String?): ParsedCamInfo {
        if (xml.isNullOrBlank()) return ParsedCamInfo(cameraName = null, firmwareVersion = null, batteryPercent = null)
        return runCatching {
            val factory = newCameraXmlFactory()
            val builder = factory.newDocumentBuilder()
            val document = ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)).use(builder::parse)
            val root = document.documentElement
            ParsedCamInfo(
                cameraName = CameraNameNormalizer.normalizeModelName(
                    firstNonBlank(
                        firstText(root, "model"),
                        firstText(root, "modelname"),
                        findNamedTextCandidate(root, "model", "modelname", "bodyname", "cameraname"),
                    ),
                ),
                firmwareVersion = normalizeFirmwareVersion(
                    firstNonBlank(
                        firstText(root, "firmwareversion"),
                        firstText(root, "firmware"),
                        firstText(root, "fwversion"),
                        findNamedTextCandidate(root, "firmware", "fwversion", "swversion"),
                    ),
                ),
                batteryPercent = extractBatteryPercent(
                    firstNonBlank(
                        firstText(root, "batterypercent"),
                        firstText(root, "batterylevel"),
                        firstText(root, "battery"),
                        firstText(root, "batt"),
                        findNamedTextCandidate(root, "batterypercent", "batterylevel", "battery"),
                    ),
                ),
            )
        }.getOrElse { throwable ->
            D.err("PROTO", "Failed to parse get_caminfo response", throwable)
            ParsedCamInfo(cameraName = null, firmwareVersion = null, batteryPercent = null)
        }
    }

    private fun newCameraXmlFactory(): DocumentBuilderFactory {
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isIgnoringComments = true
            isCoalescing = true
            isExpandEntityReferences = false
            setFeatureIfSupported("http://xml.org/sax/features/external-general-entities", false)
            setFeatureIfSupported("http://xml.org/sax/features/external-parameter-entities", false)
        }
    }

    private fun DocumentBuilderFactory.setFeatureIfSupported(feature: String, value: Boolean) {
        runCatching { setFeature(feature, value) }
            .onFailure { D.proto("XML parser feature unsupported: $feature") }
    }

    private fun parsePlayTargetSlot(response: String): Int {
        val normalized = response.trim()
        val slot = Regex("<targetslot>\\s*(\\d+)\\s*</targetslot>", RegexOption.IGNORE_CASE)
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: normalized.toIntOrNull()
        return slot?.takeIf { it in 1..2 }
            ?: error("Unexpected play target slot response: $response")
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private fun normalizeFirmwareVersion(value: String?): String? {
        val normalized = value
            ?.trim()
            ?.removePrefix("Firmware")
            ?.removePrefix("firmware")
            ?.removePrefix("Version")
            ?.removePrefix("version")
            ?.trim(' ', ':')
            ?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
        return normalized
    }

    private fun extractBatteryPercent(value: String?): Int? {
        val normalized = value?.trim().orEmpty()
        if (normalized.isBlank()) {
            return null
        }
        val percentMatch = Regex("(\\d{1,3})\\s*%").find(normalized)
        val explicitPercent = percentMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (explicitPercent != null && explicitPercent in 0..100) {
            return explicitPercent
        }
        val rawValue = normalized.toIntOrNull()
        return rawValue?.takeIf { it in 5..100 }
    }

    private fun findNamedTextCandidate(root: Element, vararg tokens: String): String? {
        val normalizedTokens = tokens.map { it.lowercase(Locale.US) }
        val matches = mutableListOf<String>()

        fun visit(node: Node) {
            val element = node as? Element ?: return
            val tagName = element.tagName.lowercase(Locale.US)
            if (normalizedTokens.any { token -> tagName.contains(token) }) {
                element.textContent
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(matches::add)
            }

            val attributes = element.attributes
            for (index in 0 until attributes.length) {
                val attribute = attributes.item(index) ?: continue
                val attributeName = attribute.nodeName.lowercase(Locale.US)
                if (normalizedTokens.any { token -> attributeName.contains(token) }) {
                    attribute.nodeValue
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let(matches::add)
                }
            }

            val children = element.childNodes
            for (index in 0 until children.length) {
                visit(children.item(index))
            }
        }

        visit(root)
        return matches.firstOrNull { it.isNotBlank() }
    }

    private fun findNamedRangeCandidate(root: Element, token: String): String {
        val normalizedToken = token.lowercase(Locale.US)
        val matches = mutableListOf<String>()

        fun visit(node: Node) {
            val element = node as? Element ?: return
            val tagName = element.tagName.lowercase(Locale.US)
            if (tagName.contains(normalizedToken)) {
                element.textContent
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(matches::add)
            }

            val attributes = element.attributes
            for (index in 0 until attributes.length) {
                val attribute = attributes.item(index) ?: continue
                if (attribute.nodeName.lowercase(Locale.US).contains(normalizedToken)) {
                    attribute.nodeValue
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let(matches::add)
                }
            }

            val children = element.childNodes
            for (index in 0 until children.length) {
                visit(children.item(index))
            }
        }

        visit(root)
        return matches.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun liveViewQualityParam(): String {
        return if (config.useHighQualityScreennails) "0800x0600" else "0640x0480"
    }

    private fun parseAfFramePoint(responseBody: String): Pair<Int, Int>? {
        val match = Regex("<afframepoint>(\\d+)x(\\d+)</afframepoint>")
            .find(responseBody)
            ?: return null
        val pointX = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val pointY = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        return pointX to pointY
    }

    private fun fullImageTimeoutMillis(fileSize: Long): Int {
        if (fileSize <= 0L) {
            return 60_000
        }
        val extraTimeout = ((fileSize + FULL_IMAGE_TIMEOUT_STEP_BYTES - 1L) / FULL_IMAGE_TIMEOUT_STEP_BYTES) * 4_000L
        return (60_000L + extraTimeout)
            .coerceAtMost(180_000L)
            .toInt()
    }

    private companion object {
        private const val PROPERTY_DESC_RETRY_DELAY_MS = 250L
        private const val PROPERTY_DESC_MAX_ATTEMPTS = 2
        private const val FULL_IMAGE_TIMEOUT_STEP_BYTES = 4L * 1024L * 1024L
    }
}
