package dev.dblink.core.usb

import dev.dblink.core.logging.D
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class PtpInitFailurePhase(
    val logLabel: String,
) {
    Send("send"),
    DataReceive("data_receive"),
    ResponseReceive("response_receive"),
    ReconnectReclaim("reconnect_reclaim"),
}

class PtpInitException(
    val phase: PtpInitFailurePhase,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

data class OlympusChangedPropertiesSnapshot(
    val rawBytes: Int,
    val changedProperties: List<Int> = emptyList(),
    val descriptorProperties: List<Int> = emptyList(),
) {
    val isDescriptorDump: Boolean
        get() = descriptorProperties.isNotEmpty() && changedProperties.isEmpty()
}

data class OlympusPcModeInitResult(
    val valueBefore: Int,
    val valueAfter: Int?,
    val writeRequested: Boolean,
    val writeSent: Boolean,
    val responseCode: Int?,
    val eventsObserved: Int,
) {
    val writeAcknowledged: Boolean
        get() = responseCode == PtpConstants.Resp.OK

    val normalizedToPcMode: Boolean
        get() = (valueAfter ?: valueBefore) == 1

    val controlModeActiveAfter: Boolean
        get() = (valueAfter ?: valueBefore) != 0
}

/**
 * PTP session manager.
 *
 * Handles session lifecycle, transaction ID tracking, and provides
 * high-level PTP operations over the low-level USB connection.
 */
class PtpSession(
    private val transport: PtpUsbConnection,
) {
    data class OlympusLiveViewModeDescriptor(
        val dataType: Int,
        val currentValue: Int,
        val supportedValues: List<Int>,
    )

    private var sessionId: Int = 0
    private var transactionId: Int = 0

    @Volatile
    var isOpen: Boolean = false
        private set

    /** Device info obtained from GetDeviceInfo. */
    var deviceInfo: PtpDeviceInfo? = null
        private set

    private fun nextTransactionId(): Int = ++transactionId

    private fun Int.toHex32(): String = "0x${(toLong() and 0xffffffffL).toString(16)}"

    private data class SetPropertyBestEffortResult(
        val commandSent: Boolean,
        val dataSent: Boolean,
        val response: PtpContainer?,
    )

    // Session Lifecycle
    /**
     * Get device information. Can be called before opening a session.
     */
    fun primeDeviceInfo(info: PtpDeviceInfo) {
        deviceInfo = info
        D.ptp("DeviceInfo primed from cached init metadata: ${info.model} (${info.manufacturer}) fw=${info.firmwareVersion}")
    }

    /**
     * Get device information. Can be called before opening a session.
     */
    fun getDeviceInfo(responseTimeoutMs: Int = 1_500): Result<PtpDeviceInfo> = runCatching {
        val cmd = PtpContainer.command(PtpConstants.Op.GetDeviceInfo, 0)
        if (!transport.sendCommand(cmd)) {
            throw PtpInitException(
                phase = PtpInitFailurePhase.Send,
                message = "GetDeviceInfo send failed",
            )
        }

        val transfer = transport.receiveDataAndResponseDetailed(timeoutMs = responseTimeoutMs)
        val (data, response) = when (transfer) {
            is PtpDataAndResponseResult.Success -> transfer.data to transfer.response
            is PtpDataAndResponseResult.Failure -> {
                throw PtpInitException(
                    phase = transfer.phase,
                    message = "GetDeviceInfo ${transfer.detail}",
                )
            }
        }
        if (response.code != PtpConstants.Resp.OK) {
            throw PtpInitException(
                phase = PtpInitFailurePhase.ResponseReceive,
                message = "GetDeviceInfo failed: ${PtpConstants.Resp.name(response.code)}",
            )
        }

        PtpDeviceInfo.parse(data).also {
            deviceInfo = it
            D.ptp("DeviceInfo: ${it.model} (${it.manufacturer}) fw=${it.firmwareVersion}")
            D.ptp("  Operations (${it.operationsSupported.size}): ${it.operationsSupported.sorted().joinToString { c -> PtpConstants.opName(c) }}")
            D.ptp("  Events (${it.eventsSupported.size}): ${it.eventsSupported.sorted().joinToString { c -> "0x${c.toString(16)}" }}")
            D.ptp("  Properties (${it.devicePropertiesSupported.size}): ${it.devicePropertiesSupported.sorted().joinToString { c -> "0x${c.toString(16)}" }}")
        }
    }

    /**
     * Open a PTP session. Must be called after getDeviceInfo.
     * For Olympus/OM cameras, also calls the vendor-specific OpenSession.
     */
    fun openSession(sessionId: Int = 1): Result<Unit> = runCatching {
        this.sessionId = sessionId
        this.transactionId = 0

        // Standard PTP OpenSession
        val cmd = PtpContainer.command(
            PtpConstants.Op.OpenSession, nextTransactionId(), sessionId,
        )
        check(transport.sendCommand(cmd)) { "Failed to send OpenSession" }

        var response = transport.receiveResponse()
            ?: throw IllegalStateException("No response to OpenSession")
        D.ptp("OpenSession response=${PtpConstants.Resp.name(response.code)} code=0x${response.code.toString(16)}")

        // If a stale session exists (e.g. from MtpDevice warmup which doesn't send
        // CloseSession), close it first and re-open a fresh one.
        if (response.code == PtpConstants.Resp.SessionAlreadyOpen) {
            D.ptp("Stale session detected, sending CloseSession before retry")
            val closeCmd = PtpContainer.command(PtpConstants.Op.CloseSession, nextTransactionId())
            transport.sendCommand(closeCmd)
            transport.receiveResponse()?.let { closeResp ->
                D.ptp("CloseSession response=${PtpConstants.Resp.name(closeResp.code)} code=0x${closeResp.code.toString(16)}")
            }

            // Re-open with a fresh session
            this.transactionId = 0
            val retryCmd = PtpContainer.command(
                PtpConstants.Op.OpenSession, nextTransactionId(), sessionId,
            )
            check(transport.sendCommand(retryCmd)) { "Failed to send OpenSession (retry)" }
            response = transport.receiveResponse()
                ?: throw IllegalStateException("No response to OpenSession (retry)")
            D.ptp("OpenSession retry response=${PtpConstants.Resp.name(response.code)} code=0x${response.code.toString(16)}")
        }

        check(response.code == PtpConstants.Resp.OK || response.code == PtpConstants.Resp.SessionAlreadyOpen) {
            "OpenSession failed: ${PtpConstants.Resp.name(response.code)}"
        }
        D.ptp("Standard session opened (id=$sessionId)")

        // Olympus vendor OpenSession (if supported)
        val info = deviceInfo
        if (info != null && info.isOlympusOrOmSystem()) {
            if (PtpConstants.OlympusOp.OpenSession in info.operationsSupported) {
                D.ptp("Sending Olympus vendor OpenSession")
                val olyCmd = PtpContainer.command(
                    PtpConstants.OlympusOp.OpenSession, nextTransactionId(), sessionId,
                )
                if (transport.sendCommand(olyCmd)) {
                    val olyResp = transport.receiveResponse()
                    D.ptp(
                        "Olympus OpenSession response=" +
                            (olyResp?.let { "${PtpConstants.Resp.name(it.code)} code=0x${it.code.toString(16)}" } ?: "no response"),
                    )
                }
            }
        }

        isOpen = true
    }

    /**
     * Close the PTP session.
     */
    fun closeSession(): Result<Unit> = runCatching {
        if (!isOpen) return@runCatching

        val cmd = PtpContainer.command(PtpConstants.Op.CloseSession, nextTransactionId())
        transport.sendCommand(cmd)
        transport.receiveResponse()?.let { response ->
            D.ptp("CloseSession response=${PtpConstants.Resp.name(response.code)} code=0x${response.code.toString(16)}")
        }
        isOpen = false
        D.ptp("Session closed")
    }

    // Device Properties
    /**
     * Get a device property value.
     * @param propCode the property code (standard or vendor)
     * @return raw byte array of the property value
     */
    fun getDevicePropValue(propCode: Int): Result<ByteArray> = runCatching {
        checkOpen()
        val cmd = PtpContainer.command(
            PtpConstants.Op.GetDevicePropValue, nextTransactionId(), propCode,
        )
        check(transport.sendCommand(cmd)) { "Failed to send GetDevicePropValue" }

        val (data, response) = transport.receiveDataAndResponse()
            ?: throw IllegalStateException("No response to GetDevicePropValue")
        check(response.code == PtpConstants.Resp.OK) {
            "GetDevicePropValue(0x${propCode.toString(16)}) failed: ${PtpConstants.Resp.name(response.code)}"
        }
        data
    }

    fun getDevicePropValueInt16(propCode: Int): Result<Int> = runCatching {
        val data = getDevicePropValue(propCode).getOrThrow()
        check(data.size >= 2) {
            "GetDevicePropValue(0x${propCode.toString(16)}) returned only ${data.size} byte(s)"
        }
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
    }

    fun getDevicePropValueInt32(propCode: Int): Result<Int> = runCatching {
        val data = getDevicePropValue(propCode).getOrThrow()
        check(data.size >= 4) {
            "GetDevicePropValue(0x${propCode.toString(16)}) returned only ${data.size} byte(s)"
        }
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).int
    }

    /**
     * Get a device property descriptor (current value + allowed values).
     */
    fun getDevicePropDesc(propCode: Int): Result<PtpPropertyDesc> = runCatching {
        checkOpen()
        val cmd = PtpContainer.command(
            PtpConstants.Op.GetDevicePropDesc, nextTransactionId(), propCode,
        )
        check(transport.sendCommand(cmd)) { "Failed to send GetDevicePropDesc" }

        val (data, response) = transport.receiveDataAndResponse()
            ?: throw IllegalStateException("No response to GetDevicePropDesc")
        check(response.code == PtpConstants.Resp.OK) {
            "GetDevicePropDesc(0x${propCode.toString(16)}) failed: ${PtpConstants.Resp.name(response.code)}"
        }
        PtpPropertyDesc.parse(data)
    }

    /**
     * Set a device property value.
     * @param propCode the property code
     * @param value the value as a byte array (format depends on property)
     */
    fun setDevicePropValue(propCode: Int, value: ByteArray): Result<Unit> = runCatching {
        checkOpen()
        // Send command first
        val cmd = PtpContainer.command(
            PtpConstants.Op.SetDevicePropValue, nextTransactionId(), propCode,
        )
        check(transport.sendCommand(cmd)) { "Failed to send SetDevicePropValue command" }

        // Send data phase
        val dataContainer = PtpContainer.data(
            PtpConstants.Op.SetDevicePropValue, transactionId, value,
        )
        check(transport.sendData(dataContainer)) { "Failed to send property value data" }

        // Read response
        val response = transport.receiveResponse()
            ?: throw IllegalStateException("No response to SetDevicePropValue")
        check(response.code == PtpConstants.Resp.OK) {
            "SetDevicePropValue(0x${propCode.toString(16)}) failed: ${PtpConstants.Resp.name(response.code)}"
        }
    }

    /**
     * Set a 16-bit device property value (convenience method).
     */
    fun setDevicePropValueInt16(propCode: Int, value: Int): Result<Unit> {
        val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(value.toShort())
        return setDevicePropValue(propCode, buf.array())
    }

    private fun setDevicePropValueInt16BestEffort(
        propCode: Int,
        value: Int,
        responseTimeoutMs: Int = 2_500,
    ): SetPropertyBestEffortResult {
        checkOpen()
        val transactionId = nextTransactionId()
        val cmd = PtpContainer.command(
            PtpConstants.Op.SetDevicePropValue,
            transactionId,
            propCode,
        )
        val commandSent = transport.sendCommand(cmd)
        if (!commandSent) {
            D.err("PTP", "Best-effort SetDevicePropValue command send failed for ${propCode.toHex32()}")
            return SetPropertyBestEffortResult(
                commandSent = false,
                dataSent = false,
                response = null,
            )
        }

        val valueBytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(value.toShort())
        }.array()
        val dataContainer = PtpContainer.data(
            PtpConstants.Op.SetDevicePropValue,
            transactionId,
            valueBytes,
        )
        val dataSent = transport.sendData(dataContainer)
        if (!dataSent) {
            D.err("PTP", "Best-effort SetDevicePropValue data send failed for ${propCode.toHex32()}")
            return SetPropertyBestEffortResult(
                commandSent = true,
                dataSent = false,
                response = null,
            )
        }

        val response = transport.receiveResponse(timeoutMs = responseTimeoutMs)
        if (response == null) {
            D.ptp(
                "Best-effort SetDevicePropValue ${propCode.toHex32()} " +
                    "received no immediate response within ${responseTimeoutMs}ms",
            )
        } else {
            D.ptp(
                "Best-effort SetDevicePropValue ${propCode.toHex32()} " +
                    "response=${PtpConstants.Resp.name(response.code)}",
            )
        }
        return SetPropertyBestEffortResult(
            commandSent = true,
            dataSent = true,
            response = response,
        )
    }

    /**
     * Set a 32-bit device property value (convenience method).
     */
    fun setDevicePropValueInt32(propCode: Int, value: Int): Result<Unit> {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(value)
        return setDevicePropValue(propCode, buf.array())
    }

    fun getChangedProperties(): Result<OlympusChangedPropertiesSnapshot> = runCatching {
        checkOpen()
        val cmd = PtpContainer.command(PtpConstants.OmdOp.GetChangedProperties, nextTransactionId())
        D.ptp(">> OMD.GetChangedProperties")
        check(transport.sendCommand(cmd)) { "Failed to send OMD.GetChangedProperties" }

        val (data, response) = transport.receiveDataAndResponse(timeoutMs = 2_000)
            ?: throw IllegalStateException("No response to OMD.GetChangedProperties")
        D.ptp("<< OMD.GetChangedProperties response=${PtpConstants.Resp.name(response.code)} bytes=${data.size}")
        check(response.code == PtpConstants.Resp.OK) {
            "OMD.GetChangedProperties failed: ${PtpConstants.Resp.name(response.code)}"
        }
        val changedProperties = parseCountPrefixedUint16ArrayOrNull(data)
        if (changedProperties != null) {
            OlympusChangedPropertiesSnapshot(
                rawBytes = data.size,
                changedProperties = changedProperties,
            )
        } else {
            OlympusChangedPropertiesSnapshot(
                rawBytes = data.size,
                descriptorProperties = parsePropertyDescCodes(data),
            )
        }
    }

    /**
     * Olympus 0x948B returns a uint16 property list related to the properties
     * monitored via 0x9489 / 0x9486.
     */
    fun getOlympusPropertyObserverHints(): Result<List<Int>> = runCatching {
        checkOpen()
        val cmd = PtpContainer.command(PtpConstants.OmdOp.GetPropertyObserverHints, nextTransactionId())
        D.ptp(">> OMD.GetPropertyObserverHints")
        check(transport.sendCommand(cmd)) { "Failed to send OMD.GetPropertyObserverHints" }

        val (data, response) = transport.receiveDataAndResponse(timeoutMs = 2_000)
            ?: throw IllegalStateException("No response to OMD.GetPropertyObserverHints")
        D.ptp(
            "<< OMD.GetPropertyObserverHints response=${PtpConstants.Resp.name(response.code)} " +
                "bytes=${data.size}",
        )
        check(response.code == PtpConstants.Resp.OK) {
            "OMD.GetPropertyObserverHints failed: ${PtpConstants.Resp.name(response.code)}"
        }
        parseCountPrefixedUint16ArrayOrNull(data) ?: parseRawUint16Array(data)
    }

    /**
     * Olympus 0x9489 accepts a uint16 property list.
     * 0x9486 may only return meaningful deltas for properties registered here.
     */
    fun setOlympusTrackedProperties(properties: List<Int>): Result<Unit> = runCatching {
        checkOpen()
        require(properties.isNotEmpty()) { "Property registration list must not be empty." }
        val transactionId = nextTransactionId()
        val cmd = PtpContainer.command(PtpConstants.OmdOp.SetProperties, transactionId)
        D.ptp(">> OMD.SetProperties count=${properties.size}")
        check(transport.sendCommand(cmd)) { "Failed to send OMD.SetProperties command" }

        val payload = ByteBuffer.allocate(4 + properties.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(properties.size)
            properties.forEach { putShort(it.toShort()) }
        }.array()
        val dataContainer = PtpContainer.data(
            PtpConstants.OmdOp.SetProperties,
            transactionId,
            payload,
        )
        check(transport.sendData(dataContainer)) { "Failed to send OMD.SetProperties data" }

        val response = transport.receiveResponse(timeoutMs = 2_000)
            ?: throw IllegalStateException("No response to OMD.SetProperties")
        D.ptp("<< OMD.SetProperties response=${PtpConstants.Resp.name(response.code)}")
        check(response.code == PtpConstants.Resp.OK) {
            "OMD.SetProperties failed: ${PtpConstants.Resp.name(response.code)}"
        }
    }

    fun getLiveViewEnabled(): Result<Boolean> = runCatching {
        getDevicePropValueInt16(PtpConstants.OlympusProp.LiveViewEnabled).getOrThrow() != 0
    }

    fun setLiveViewEnabled(enabled: Boolean): Result<Unit> {
        return setDevicePropValueInt16(
            PtpConstants.OlympusProp.LiveViewEnabled,
            if (enabled) 1 else 0,
        )
    }

    /**
     * Initialize Olympus PC mode via 0xD052.
     *
     * On OM-1, when the user accepts tethering on the camera body, 0xD052
     * transitions from 0x0 to 0x2 (Camera Control). By the time we connect,
     * the camera can already be at 0x2. Writing 0x1 when the current value is
     * 0x2 can stall the transport, so the implementation checks the existing
     * state before writing.
     *
     * So: only write 0x1 when current is 0x0 (camera hasn't entered
     * control mode yet). Any non-zero value means control mode is active.
     *
     * @return true if the property was written, false if already active
     */
    fun initializeOlympusPcMode(): Result<OlympusPcModeInitResult> = runCatching {
        val propCode = PtpConstants.OlympusProp.LiveViewEnabled
        val desc = getDevicePropDesc(propCode).getOrNull()
        if (desc != null) {
            D.ptp(
                "Olympus PC mode desc: type=0x${desc.dataType.toString(16)} " +
                    "current=${desc.currentValue.toHex32()} " +
                    "default=${desc.factoryDefault.toHex32()} " +
                    "getSet=${desc.getSet} form=${desc.formFlag}",
            )
            if (desc.isEnumeration && desc.enumValues.isNotEmpty()) {
                D.ptp("  allowed: ${desc.enumValues.joinToString { it.toHex32() }}")
            }
        }

        val currentValue = desc?.currentValue?.toInt()
            ?: getDevicePropValueInt16(propCode).getOrElse { 0 }

        if (currentValue == 1) {
            D.ptp(
                "Olympus PC mode already normalized on ${propCode.toHex32()} " +
                    "value=${currentValue.toHex32()}",
            )
            return@runCatching OlympusPcModeInitResult(
                valueBefore = currentValue,
                valueAfter = currentValue,
                writeRequested = false,
                writeSent = false,
                responseCode = null,
                eventsObserved = 0,
            )
        }

        D.ptp(
            "Olympus PC mode: compatibility write ${propCode.toHex32()} " +
                "${currentValue.toHex32()} -> 0x1",
        )
        val writeResult = setDevicePropValueInt16BestEffort(propCode, 1)
        if (!writeResult.commandSent || !writeResult.dataSent) {
            if (currentValue != 0) {
                D.ptp(
                    "Olympus PC mode: write did not get fully onto the wire, " +
                        "but existing control mode ${currentValue.toHex32()} remains usable",
                )
                return@runCatching OlympusPcModeInitResult(
                    valueBefore = currentValue,
                    valueAfter = currentValue,
                    writeRequested = true,
                    writeSent = false,
                    responseCode = null,
                    eventsObserved = 0,
                )
            }
            throw IllegalStateException("Olympus PC mode write could not be sent on this transport.")
        }

        // Blocking sleep intentional: PtpSession is a non-suspend transport layer called
        // from Dispatchers.IO. The 100ms pause allows Olympus PC mode to stabilize before
        // polling events. Brief enough not to starve the IO pool (64 default threads).
        Thread.sleep(100L)
        var eventsObserved = 0
        repeat(2) { index ->
            D.ptp("Olympus PC mode: event check ${index + 1}/2")
            val event = pollEvent(100)
            if (event != null) {
                eventsObserved += 1
                D.ptp(
                    "Olympus PC mode event ${index + 1} code=0x${event.code.toString(16)} " +
                        "param0=${event.param(0)?.toHex32() ?: "n/a"}",
                )
            }
            if (index == 0) {
                Thread.sleep(100L) // Blocking: see comment above on non-suspend transport
            }
        }

        val responseCode = writeResult.response?.code
        val currentValueAfter: Int? = null
        val activeValue = when {
            responseCode == PtpConstants.Resp.OK -> 1
            eventsObserved > 0 -> currentValue
            else -> currentValue
        }
        if (responseCode != null && responseCode != PtpConstants.Resp.OK && activeValue == 0) {
            throw IllegalStateException(
                "Olympus PC mode write failed: ${PtpConstants.Resp.name(responseCode)}",
            )
        }
        if (activeValue == 0) {
            throw IllegalStateException("Olympus PC mode remained inactive after the compatibility write.")
        }

        D.ptp(
            "Olympus PC mode result: before=${currentValue.toHex32()} " +
                "after=${currentValueAfter?.toHex32() ?: "unknown"} " +
                "ack=${responseCode?.let(PtpConstants.Resp::name) ?: "none"} " +
                "events=$eventsObserved",
        )
        OlympusPcModeInitResult(
            valueBefore = currentValue,
            valueAfter = currentValueAfter,
            writeRequested = true,
            writeSent = true,
            responseCode = responseCode,
            eventsObserved = eventsObserved,
        )
    }

    fun getLiveViewModeOm(): Result<Int> =
        getDevicePropValueInt32(PtpConstants.OlympusProp.LiveViewModeOm)

    /**
     * Read the LiveViewModeOm property descriptor.
     * Returns the descriptor so callers can inspect dataType, currentValue, allowed values.
     */
    fun getLiveViewModeOmDesc(): Result<PtpPropertyDesc> =
        getDevicePropDesc(PtpConstants.OlympusProp.LiveViewModeOm)

    fun getOlympusLiveViewModeDescriptor(): Result<OlympusLiveViewModeDescriptor> = runCatching {
        val propCode = PtpConstants.OlympusProp.LiveViewModeOm
        val desc = getDevicePropDesc(propCode).getOrNull()
        if (desc != null) {
            D.ptp(
                "LiveViewModeOm desc: type=0x${desc.dataType.toString(16)} " +
                    "current=${desc.currentValue.toHex32()} " +
                    "default=${desc.factoryDefault.toHex32()} " +
                    "getSet=${desc.getSet} form=${desc.formFlag}",
            )
            if (desc.isEnumeration && desc.enumValues.isNotEmpty()) {
                D.ptp("  allowed: ${desc.enumValues.joinToString { it.toHex32() }}")
            }
            if (desc.isRange) {
                D.ptp("  range: ${desc.rangeMin.toHex32()}..${desc.rangeMax.toHex32()} step=${desc.rangeStep}")
            }
        }

        val dataType = desc?.dataType ?: PtpPropertyDesc.TYPE_UINT32
        val currentValue = desc?.currentValue?.toInt()
            ?: getLiveViewModeOm().getOrElse { 0 }
        val supportedValues = when {
            desc?.isEnumeration == true && desc.enumValues.isNotEmpty() ->
                desc.enumValues.map(Long::toInt).distinct()
            currentValue != 0 -> listOf(currentValue)
            else -> emptyList()
        }

        OlympusLiveViewModeDescriptor(
            dataType = dataType,
            currentValue = currentValue,
            supportedValues = supportedValues,
        )
    }

    fun setOmLiveViewMode(
        target: Int,
        dataTypeHint: Int? = null,
        currentValueHint: Int? = null,
    ): Result<Unit> = runCatching {
        val propCode = PtpConstants.OlympusProp.LiveViewModeOm
        val dataType = dataTypeHint ?: PtpPropertyDesc.TYPE_UINT32
        val currentValue = currentValueHint ?: getLiveViewModeOm().getOrElse { 0 }

        if (currentValue != target) {
            D.ptp(
                "OM live view mode: ${propCode.toHex32()} " +
                    "${currentValue.toHex32()} -> ${target.toHex32()} " +
                    "(dataType=0x${dataType.toString(16)})",
            )
            when (dataType) {
                PtpPropertyDesc.TYPE_UINT16, PtpPropertyDesc.TYPE_INT16 ->
                    setDevicePropValueInt16(propCode, target).getOrThrow()
                else ->
                    setDevicePropValueInt32(propCode, target).getOrThrow()
            }
        } else {
            D.ptp(
                "OM live view mode already ${target.toHex32()} on ${propCode.toHex32()}",
            )
        }
    }

    /**
     * Configure OM live view streaming mode.
     *
     * Reads the property descriptor for LiveViewModeOm (0xD06D) to determine
     * the correct data type, then sets the streaming mode value accordingly.
     *
     * Uses the observed OM-D live-view bootstrap sequence.
     */
    fun configureOmLiveViewStreamingMode(): Result<Unit> = runCatching {
        val descriptor = getOlympusLiveViewModeDescriptor().getOrThrow()
        val dataType = descriptor.dataType

        // Choose target value based on data type
        val target = when (dataType) {
            PtpPropertyDesc.TYPE_UINT16, PtpPropertyDesc.TYPE_INT16 -> {
                // 16-bit property: use 0x0100 (basic streaming mode)
                PtpConstants.OlympusLiveViewMode.Streaming16
            }
            else -> {
                // 32-bit property (standard for OM-D): use 0x04000300
                PtpConstants.OlympusLiveViewMode.Streaming
            }
        }
        setOmLiveViewMode(
            target = target,
            dataTypeHint = descriptor.dataType,
            currentValueHint = descriptor.currentValue,
        ).getOrThrow()
    }

    private fun Long.toHex32(): String = "0x${(this and 0xffffffffL).toString(16)}"

    fun getFocusDistance(): Result<Int> = getDevicePropValueInt32(PtpConstants.OlympusProp.FocusDistance)

    fun getAfResult(): Result<Int> = getDevicePropValueInt16(PtpConstants.OlympusProp.AFResult)

    // Capture
    /**
     * Initiate a standard PTP capture.
     * @param storageId storage ID (0 = default)
     * @param formatCode object format code (0 = default)
     */
    fun initiateCapture(storageId: Int = 0, formatCode: Int = 0): Result<Unit> = runCatching {
        checkOpen()
        val cmd = PtpContainer.command(
            PtpConstants.Op.InitiateCapture, nextTransactionId(), storageId, formatCode,
        )
        check(transport.sendCommand(cmd)) { "Failed to send InitiateCapture" }

        val response = transport.receiveResponse(timeoutMs = 15_000)
            ?: throw IllegalStateException("No response to InitiateCapture")
        check(response.code == PtpConstants.Resp.OK) {
            "InitiateCapture failed: ${PtpConstants.Resp.name(response.code)}"
        }
    }

    // Storage & Objects
    /**
     * Trigger OM-D vendor capture over USB/PTP.
     *
     * The tested capture flow sends two transactions:
     *   1. 0x9481 param=0x3  (shutter press / half-press start)
     *   2. 0x9481 param=0x6  (shutter release / capture end)
     *   3. usleep(500)
     *   4. 0x9486            (read ChangedProperties)
     *
     * The old implementation sent a single 0x9481 param=0x0 which does
     * not match any known working client.
     */
    fun omdCapture(): Result<Unit> = runCatching {
        checkOpen()

        // Step 1: shutter press (param=0x3)
        val pressCmd = PtpContainer.command(
            PtpConstants.OmdOp.Capture, nextTransactionId(),
            PtpConstants.CaptureParam.BULB_START,
        )
        D.ptp(">> OMD.Capture shutter-press (param=0x3)")
        check(transport.sendCommand(pressCmd)) { "Failed to send OMD.Capture shutter-press" }
        val pressResp = transport.receiveResponse(timeoutMs = 15_000)
            ?: throw IllegalStateException("No response to OMD.Capture shutter-press")
        D.ptp("<< OMD.Capture shutter-press response=${PtpConstants.Resp.name(pressResp.code)}")
        check(pressResp.code == PtpConstants.Resp.OK) {
            "OMD.Capture shutter-press failed: ${PtpConstants.Resp.name(pressResp.code)}"
        }

        // Step 2: shutter release (param=0x6)
        val releaseCmd = PtpContainer.command(
            PtpConstants.OmdOp.Capture, nextTransactionId(),
            PtpConstants.CaptureParam.BULB_END,
        )
        D.ptp(">> OMD.Capture shutter-release (param=0x6)")
        check(transport.sendCommand(releaseCmd)) { "Failed to send OMD.Capture shutter-release" }
        val releaseResp = transport.receiveResponse(timeoutMs = 15_000)
            ?: throw IllegalStateException("No response to OMD.Capture shutter-release")
        D.ptp("<< OMD.Capture shutter-release response=${PtpConstants.Resp.name(releaseResp.code)}")
        check(releaseResp.code == PtpConstants.Resp.OK) {
            "OMD.Capture shutter-release failed: ${PtpConstants.Resp.name(releaseResp.code)}"
        }

        // Step 3: brief settle before reading ChangedProperties (blocking, non-suspend layer)
        Thread.sleep(1)

        // Step 4: read ChangedProperties (0x9486)
        getChangedProperties().onFailure { throwable ->
            D.ptp("OMD.Capture: ChangedProperties read after capture failed (non-fatal): ${throwable.message}")
        }
    }

    /**
     * OM-D bulb start — sends 0x9481 param=0x3 only (no release).
     */
    fun omdBulbStart(): Result<Unit> = runCatching {
        checkOpen()
        val cmd = PtpContainer.command(
            PtpConstants.OmdOp.Capture, nextTransactionId(),
            PtpConstants.CaptureParam.BULB_START,
        )
        D.ptp(">> OMD.BulbStart (param=0x3)")
        check(transport.sendCommand(cmd)) { "Failed to send OMD.BulbStart" }
        val resp = transport.receiveResponse(timeoutMs = 15_000)
            ?: throw IllegalStateException("No response to OMD.BulbStart")
        D.ptp("<< OMD.BulbStart response=${PtpConstants.Resp.name(resp.code)}")
        check(resp.code == PtpConstants.Resp.OK) {
            "OMD.BulbStart failed: ${PtpConstants.Resp.name(resp.code)}"
        }
    }

    /**
     * OM-D bulb end — sends 0x9481 param=0x6 only.
     */
    fun omdBulbEnd(): Result<Unit> = runCatching {
        checkOpen()
        val cmd = PtpContainer.command(
            PtpConstants.OmdOp.Capture, nextTransactionId(),
            PtpConstants.CaptureParam.BULB_END,
        )
        D.ptp(">> OMD.BulbEnd (param=0x6)")
        check(transport.sendCommand(cmd)) { "Failed to send OMD.BulbEnd" }
        val resp = transport.receiveResponse(timeoutMs = 15_000)
            ?: throw IllegalStateException("No response to OMD.BulbEnd")
        D.ptp("<< OMD.BulbEnd response=${PtpConstants.Resp.name(resp.code)}")
        check(resp.code == PtpConstants.Resp.OK) {
            "OMD.BulbEnd failed: ${PtpConstants.Resp.name(resp.code)}"
        }
    }

    /**
     * Manual focus drive (0x9487).
     * @param direction positive = near, negative = far (magnitude = steps)
     */
    fun omdMfDrive(direction: Int): Result<Unit> = runCatching {
        checkOpen()
        val cmd = PtpContainer.command(
            PtpConstants.OmdOp.MFDrive, nextTransactionId(), direction,
        )
        D.ptp(">> OMD.MFDrive direction=$direction")
        check(transport.sendCommand(cmd)) { "Failed to send OMD.MFDrive" }
        val resp = transport.receiveResponse(timeoutMs = 5_000)
            ?: throw IllegalStateException("No response to OMD.MFDrive")
        D.ptp("<< OMD.MFDrive response=${PtpConstants.Resp.name(resp.code)}")
        check(resp.code == PtpConstants.Resp.OK) {
            "OMD.MFDrive failed: ${PtpConstants.Resp.name(resp.code)}"
        }
    }

    /**
     * Trigger AF-only half-press at the current AF target area.
     * Sends ONLY 0x9481 param=0x1 (AF_START) — initiates autofocus without
     * releasing.  On OM-1, sending AF_START + AF_END (param=0x2) actually
     * triggers a full shutter release / capture, so we intentionally omit
     * the AF_END step.  The camera will run AF and hold focus.
     */
    fun omdAfStart(): Result<Unit> = runCatching {
        checkOpen()
        val startCmd = PtpContainer.command(
            PtpConstants.OmdOp.Capture, nextTransactionId(),
            PtpConstants.CaptureParam.AF_START,
        )
        D.ptp(">> OMD.AF start-only (param=0x1)")
        check(transport.sendCommand(startCmd)) { "Failed to send OMD.AF start" }
        val startResp = transport.receiveResponse(timeoutMs = 5_000)
            ?: throw IllegalStateException("No response to OMD.AF start")
        D.ptp("<< OMD.AF start response=${PtpConstants.Resp.name(startResp.code)}")
        check(startResp.code == PtpConstants.Resp.OK) {
            "OMD.AF start failed: ${PtpConstants.Resp.name(startResp.code)}"
        }
    }

    // Camera Workflow Operations
    /**
     * Change the camera's run mode (Olympus vendor operation 0x910B).
     *
     * Switch the camera into RUN_MODE_RECORDING before capture when supported.
     *
     * @param mode one of [PtpConstants.RunMode] values
     */
    fun changeRunMode(mode: Int): Result<Unit> = runCatching {
        checkOpen()
        val cmd = PtpContainer.command(
            PtpConstants.OlympusOp.SetCameraControlMode, nextTransactionId(), mode,
        )
        D.ptp(">> ChangeRunMode mode=0x${mode.toString(16)}")
        check(transport.sendCommand(cmd)) { "Failed to send ChangeRunMode" }

        val response = transport.receiveResponse(timeoutMs = 10_000)
            ?: throw IllegalStateException("No response to ChangeRunMode")
        D.ptp("<< ChangeRunMode response=${PtpConstants.Resp.name(response.code)}")
        check(response.code == PtpConstants.Resp.OK) {
            "ChangeRunMode failed: ${PtpConstants.Resp.name(response.code)}"
        }
    }

    /**
     * Get the camera's current run mode (Olympus vendor operation 0x910A).
     */
    fun getRunMode(): Result<Int> = runCatching {
        checkOpen()
        val cmd = PtpContainer.command(
            PtpConstants.OlympusOp.GetCameraControlMode, nextTransactionId(),
        )
        check(transport.sendCommand(cmd)) { "Failed to send GetRunMode" }

        val (data, response) = transport.receiveDataAndResponse()
            ?: throw IllegalStateException("No response to GetRunMode")
        check(response.code == PtpConstants.Resp.OK) {
            "GetRunMode failed: ${PtpConstants.Resp.name(response.code)}"
        }
        // Response data contains a uint32 run mode value
        if (data.size >= 4) {
            val buf = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            buf.getInt()
        } else {
            // Fallback: check response params
            response.param(0) ?: 0
        }
    }

    /**
     * Download the last captured image (Olympus OMD vendor operation 0x9485).
     *
     * Returns the JPEG data of the most recently captured image without needing
     * an object handle.
     *
     * @return raw image bytes (JPEG)
     */
    fun downloadLastCapturedImage(): Result<ByteArray> = runCatching {
        checkOpen()
        val cmd = PtpContainer.command(
            PtpConstants.OmdOp.GetImage, nextTransactionId(),
        )
        D.ptp(">> OMD.GetImage (DownloadLastCapturedImage)")
        check(transport.sendCommand(cmd)) { "Failed to send OMD.GetImage" }

        val (data, response) = transport.receiveDataAndResponse(timeoutMs = 30_000)
            ?: throw IllegalStateException("No response to OMD.GetImage")
        D.ptp("<< OMD.GetImage response=${PtpConstants.Resp.name(response.code)} size=${data.size}")
        check(response.code == PtpConstants.Resp.OK) {
            "OMD.GetImage failed: ${PtpConstants.Resp.name(response.code)}"
        }
        data
    }

    /**
     * Trigger a still capture using the best operation the connected camera supports.
     * For OM-D cameras, uses the tested two-step capture sequence (0x3→0x6→0x9486).
     */
    fun captureStill(): Result<Unit> = runCatching {
        val info = deviceInfo
        if (info?.supportsOmdCapture() == true) {
            D.ptp("captureStill: using OMD.Capture (two-step sequence)")
            omdCapture().getOrThrow()
        } else {
            D.ptp("captureStill: using standard InitiateCapture")
            initiateCapture().getOrThrow()
        }
    }

    /**
     * Get list of storage IDs (e.g., SD card slots).
     */
    fun getStorageIDs(): Result<List<Int>> = runCatching {
        checkOpen()
        val cmd = PtpContainer.command(PtpConstants.Op.GetStorageIDs, nextTransactionId())
        check(transport.sendCommand(cmd)) { "Failed to send GetStorageIDs" }

        val (data, response) = transport.receiveDataAndResponse()
            ?: throw IllegalStateException("No response to GetStorageIDs")
        check(response.code == PtpConstants.Resp.OK) {
            "GetStorageIDs failed: ${PtpConstants.Resp.name(response.code)}"
        }
        parseUint32Array(data)
    }

    fun getStorageInfoRaw(storageId: Int): Result<ByteArray> = runCatching {
        checkOpen()
        val cmd = PtpContainer.command(
            PtpConstants.Op.GetStorageInfo,
            nextTransactionId(),
            storageId,
        )
        check(transport.sendCommand(cmd)) {
            "Failed to send GetStorageInfo for storage ${storageId.toHex32()}"
        }

        val (data, response) = transport.receiveDataAndResponse()
            ?: throw IllegalStateException("No response to GetStorageInfo")
        check(response.code == PtpConstants.Resp.OK) {
            "GetStorageInfo(${storageId.toHex32()}) failed: ${PtpConstants.Resp.name(response.code)}"
        }
        data
    }

    /**
     * Get object handles from a storage.
     * @param storageId storage ID (0xFFFFFFFF for all)
     * @param formatCode filter by format (0 for all)
     * @param parent parent object handle (0 for root, 0xFFFFFFFF for all)
     */
    fun getObjectHandles(
        storageId: Int = -1, // 0xFFFFFFFF = all storages
        formatCode: Int = 0,
        parent: Int = 0, // 0x00000000 = no parent filter (all objects)
    ): Result<List<Int>> = runCatching {
        checkOpen()
        val cmd = PtpContainer.command(
            PtpConstants.Op.GetObjectHandles, nextTransactionId(),
            storageId, formatCode, parent,
        )
        check(transport.sendCommand(cmd)) { "Failed to send GetObjectHandles" }

        val (data, response) = transport.receiveDataAndResponse()
            ?: throw IllegalStateException("No response to GetObjectHandles")
        check(response.code == PtpConstants.Resp.OK) {
            "GetObjectHandles failed: ${PtpConstants.Resp.name(response.code)}"
        }
        parseUint32Array(data)
    }

    /**
     * Get object info (metadata) for a given handle.
     */
    fun getObjectInfo(handle: Int): Result<PtpObjectInfo> = runCatching {
        checkOpen()
        val cmd = PtpContainer.command(
            PtpConstants.Op.GetObjectInfo, nextTransactionId(), handle,
        )
        check(transport.sendCommand(cmd)) { "Failed to send GetObjectInfo" }

        val (data, response) = transport.receiveDataAndResponse()
            ?: throw IllegalStateException("No response to GetObjectInfo")
        check(response.code == PtpConstants.Resp.OK) {
            "GetObjectInfo failed: ${PtpConstants.Resp.name(response.code)}"
        }
        PtpObjectInfo.parse(data, handle)
    }

    /**
     * Download an object (image file) by handle.
     */
    fun getObject(handle: Int): Result<ByteArray> = runCatching {
        checkOpen()
        val cmd = PtpContainer.command(
            PtpConstants.Op.GetObject, nextTransactionId(), handle,
        )
        check(transport.sendCommand(cmd)) { "Failed to send GetObject" }

        val (data, response) = transport.receiveDataAndResponse(timeoutMs = 60_000)
            ?: throw IllegalStateException("No response to GetObject")
        check(response.code == PtpConstants.Resp.OK) {
            "GetObject failed: ${PtpConstants.Resp.name(response.code)}"
        }
        data
    }

    /**
     * Download a thumbnail by handle.
     */
    fun getThumb(handle: Int): Result<ByteArray> = runCatching {
        checkOpen()
        val cmd = PtpContainer.command(
            PtpConstants.Op.GetThumb, nextTransactionId(), handle,
        )
        check(transport.sendCommand(cmd)) { "Failed to send GetThumb" }

        val (data, response) = transport.receiveDataAndResponse()
            ?: throw IllegalStateException("No response to GetThumb")
        check(response.code == PtpConstants.Resp.OK) {
            "GetThumb failed: ${PtpConstants.Resp.name(response.code)}"
        }
        data
    }

    /**
     * Delete an object by handle.
     */
    fun deleteObject(handle: Int, formatCode: Int = 0): Result<Unit> = runCatching {
        checkOpen()
        val cmd = PtpContainer.command(
            PtpConstants.Op.DeleteObject, nextTransactionId(), handle, formatCode,
        )
        check(transport.sendCommand(cmd)) { "Failed to send DeleteObject" }

        val response = transport.receiveResponse()
            ?: throw IllegalStateException("No response to DeleteObject")
        check(response.code == PtpConstants.Resp.OK) {
            "DeleteObject failed: ${PtpConstants.Resp.name(response.code)}"
        }
    }

    // Live View
    /**
     * Get a single live view frame via OM-D vendor operation 0x9484.
     *
     * Poll this repeatedly after enabling live view.
     * Returns JPEG frame data, or null if the camera isn't ready yet.
     */
    fun getLiveViewFrame(timeoutMs: Int = 2_000): Result<ByteArray> = runCatching {
        checkOpen()
        // Keep the wired tether path on the observed preview transaction shape
        // instead of issuing a zero-parameter variant.
        val cmd = PtpContainer.command(
            PtpConstants.OmdOp.GetLiveViewImage, nextTransactionId(), 1,
        )
        check(transport.sendCommand(cmd)) { "Failed to send GetLiveViewImage" }

        val (data, response) = transport.receiveDataAndResponse(timeoutMs = timeoutMs)
            ?: throw IllegalStateException("No response to GetLiveViewImage")
        check(response.code == PtpConstants.Resp.OK) {
            "GetLiveViewImage failed: ${PtpConstants.Resp.name(response.code)}"
        }
        data
    }

    // Events
    /**
     * Poll for a PTP event from the camera.
     * Returns null if no event is pending.
     */
    fun pollEvent(timeoutMs: Int = 100): PtpContainer? = transport.pollEvent(timeoutMs)

    /**
     * Wait for a specific event (e.g., ObjectAdded after capture).
     * @param eventCode the expected event code
     * @param timeoutMs maximum time to wait
     * @return the event container, or null if timeout
     */
    fun waitForEvent(eventCode: Int, timeoutMs: Int = 10_000): PtpContainer? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val event = pollEvent(500) ?: continue
            if (event.code == eventCode) return event
            D.ptp("Unexpected event while waiting: $event")
        }
        D.ptp("Timeout waiting for event 0x${eventCode.toString(16)}")
        return null
    }

    // Helpers
    private fun checkOpen() {
        check(isOpen) { "PTP session is not open. Call openSession() first." }
    }

    /** Parse a PTP uint32 array (count + values). */
    private fun parseUint32Array(data: ByteArray): List<Int> {
        if (data.size < 4) return emptyList()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.getInt()
        return (0 until count).mapNotNull {
            if (buf.remaining() >= 4) buf.getInt() else null
        }
    }

    private fun parseCountPrefixedUint16ArrayOrNull(data: ByteArray): List<Int>? {
        if (data.size < 4) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.getInt()
        if (count < 0) return null
        val expectedBytes = 4 + count * 2
        if (expectedBytes != data.size) return null
        return (0 until count).map {
            buf.short.toInt() and 0xFFFF
        }
    }

    private fun parseRawUint16Array(data: ByteArray): List<Int> {
        if (data.isEmpty()) return emptyList()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val values = mutableListOf<Int>()
        while (buf.remaining() >= 2) {
            values += buf.short.toInt() and 0xFFFF
        }
        return values
    }

    private fun parsePropertyDescCodes(data: ByteArray): List<Int> {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val codes = mutableListOf<Int>()

        descriptorLoop@ while (buf.remaining() >= 7) {
            val propertyCode = buf.short.toInt() and 0xFFFF
            val dataType = buf.short.toInt() and 0xFFFF
            buf.get() // getSet

            if (!skipDescriptorValue(buf, dataType)) break
            if (!skipDescriptorValue(buf, dataType)) break
            if (buf.remaining() < 1) break

            val formFlag = buf.get().toInt() and 0xFF
            when (formFlag) {
                1 -> repeat(3) {
                    if (!skipDescriptorValue(buf, dataType)) {
                        break@descriptorLoop
                    }
                }

                2 -> {
                    if (buf.remaining() < 2) break
                    val count = buf.short.toInt() and 0xFFFF
                    repeat(count) {
                        if (!skipDescriptorValue(buf, dataType)) {
                            break@descriptorLoop
                        }
                    }
                }
            }

            codes += propertyCode
        }

        return codes
    }

    private fun skipDescriptorValue(buf: ByteBuffer, dataType: Int): Boolean {
        return when (dataType) {
            PtpPropertyDesc.TYPE_INT8,
            PtpPropertyDesc.TYPE_UINT8,
            -> advanceBuffer(buf, 1)

            PtpPropertyDesc.TYPE_INT16,
            PtpPropertyDesc.TYPE_UINT16,
            -> advanceBuffer(buf, 2)

            PtpPropertyDesc.TYPE_INT32,
            PtpPropertyDesc.TYPE_UINT32,
            -> advanceBuffer(buf, 4)

            PtpPropertyDesc.TYPE_INT64,
            PtpPropertyDesc.TYPE_UINT64,
            -> advanceBuffer(buf, 8)

            PtpPropertyDesc.TYPE_STR -> {
                if (buf.remaining() < 1) return false
                val charCount = buf.get().toInt() and 0xFF
                advanceBuffer(buf, charCount * 2)
            }

            else -> advanceBuffer(buf, 2)
        }
    }

    private fun advanceBuffer(buf: ByteBuffer, bytes: Int): Boolean {
        if (buf.remaining() < bytes) return false
        buf.position(buf.position() + bytes)
        return true
    }
}
