package dev.pl36.cameralink.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import dev.pl36.cameralink.core.logging.D
import dev.pl36.cameralink.core.model.CameraNameNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@SuppressLint("MissingPermission")
class CameraBleManager(private val context: Context) {

    data class WakeIdentity(
        val name: String?,
        val address: String?,
    )

    private data class BleProtocolCommand(
        val highByte: Byte,
        val lowByte: Byte,
        val data: ByteArray,
        val label: String,
        val delayAfterSuccessMs: Long = 0L,
        val acceptedResultCodes: Set<Int> = setOf(0),
    )

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val OLYMPUS_SERVICE_UUID = UUID.fromString("ADC505F9-4E58-4B71-B8CA-983BB8C73E4F")
    private val COMMAND_CHAR_UUID = UUID.fromString("82F949B4-F5DC-4CF3-AB3C-FD9FD4017B68")
    private val RESPONSE_CHAR_UUID = UUID.fromString("B7A8015C-CB94-4EFA-BDA2-B7921FA9951F")
    private val DATA_CHAR_UUID = UUID.fromString("05A02050-0860-4919-8ADD-9801FBA8B6ED")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private companion object {
        const val MAX_GATT_RETRIES = 3
        const val GATT_RETRY_DELAY_MS = 2000L
        const val PHASE_CONNECTING = 0
        const val PHASE_NOTIFY_CHAR1 = 1
        const val PHASE_NOTIFY_CHAR2 = 2
        const val PHASE_NOTIFY_CHAR3 = 3
        const val PHASE_WRITING_COMMAND = 4
        const val PHASE_AWAITING_RESPONSE = 5
        const val PHASE_ACK_WRITE_PENDING = 6
        const val PHASE_DONE = 7
    }

    @Volatile
    private var activeGatt: BluetoothGatt? = null

    @Volatile
    private var isConnected: Boolean = false

    @Volatile
    private var lastWakeIdentity: WakeIdentity? = null

    @Volatile
    private var bleSequenceNumber: Byte = 0x01

    @Volatile
    private var pendingCommandHighByte: Byte = 0x00

    @Volatile
    private var pendingCommandLowByte: Byte = 0x00

    val isBleConnected: Boolean get() = isConnected
    val latestWakeIdentity: WakeIdentity? get() = lastWakeIdentity

    suspend fun wakeUpCamera(
        targetSsid: String? = null,
        targetBleName: String? = null,
        targetBlePass: String? = null,
        targetBleAddress: String? = null,
        isReconnect: Boolean = false,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        D.marker("BLE Wake-Up")
        D.ble(
            "wakeUpCamera() targetSsid=$targetSsid, targetBleName=$targetBleName, " +
                "targetBleAddress=$targetBleAddress, blePassSet=${!targetBlePass.isNullOrBlank()}, isReconnect=$isReconnect",
        )
        D.timeStart("ble_wakeup")

        val existingGatt = activeGatt
        if (existingGatt != null || isConnected) {
            D.ble("Discarding existing BLE session before wake-up to mirror stock reconnect ordering")
            disconnectInternal()
            delay(350L)
        }

        val adapter = bluetoothAdapter
        if (adapter == null) {
            return@withContext Result.failure(Exception("Bluetooth is disabled or unsupported."))
        }
        if (!adapter.isEnabled) {
            return@withContext Result.failure(Exception("Bluetooth is disabled or unsupported."))
        }

        try {
            val device = scanForOlympusCamera(
                targetSsid = targetSsid,
                targetBleName = targetBleName,
                targetBleAddress = targetBleAddress,
            ) ?: return@withContext Result.failure(Exception("Could not find camera via Bluetooth."))

            D.ble("Found device: name=${device.name}, address=${device.address}")

            connectAndSetupWithRetry(
                device = device,
                isReconnect = isReconnect,
                targetBlePass = targetBlePass,
            )

            lastWakeIdentity = WakeIdentity(
                name = device.name,
                address = device.address,
            )
            D.timeEnd("BLE", "ble_wakeup", "Wake-up sequence completed successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            D.timeEnd("BLE", "ble_wakeup", "Wake-up FAILED")
            D.err("BLE", "Wake-up process failed", e)
            disconnectInternal()
            Result.failure(e)
        }
    }

    fun disconnect() {
        D.ble("disconnect() requested, isConnected=$isConnected")
        disconnectInternal()
    }

    private fun disconnectInternal() {
        isConnected = false
        activeGatt?.let { gatt ->
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: Exception) {
                D.ble("Error closing GATT: ${e.message}")
            }
        }
        activeGatt = null
        bleSequenceNumber = 0x01
    }

    private suspend fun scanForOlympusCamera(
        targetSsid: String?,
        targetBleName: String?,
        targetBleAddress: String?,
    ): BluetoothDevice? = withTimeout(10000L) {
        suspendCancellableCoroutine { continuation ->
            val scanner = bluetoothAdapter?.bluetoothLeScanner
            if (scanner == null) {
                continuation.resumeWithException(Exception("BLE Scanner not available"))
                return@suspendCancellableCoroutine
            }

            val filters = mutableListOf<ScanFilter>()
            targetBleName?.trim()?.takeIf { it.isNotBlank() }?.let { bleName ->
                filters += ScanFilter.Builder().setDeviceName(bleName).build()
            }
            targetBleAddress?.trim()?.takeIf { it.isNotBlank() }?.let { bleAddress ->
                filters += ScanFilter.Builder().setDeviceAddress(bleAddress).build()
            }
            filters += ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(OLYMPUS_SERVICE_UUID))
                .build()

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            var isResumed = false
            val scanStartTime = System.currentTimeMillis()
            val normalizedTargetModel = CameraNameNormalizer.normalizeSsid(targetSsid)
                ?: CameraNameNormalizer.normalizeModelName(targetSsid)
            val targetPairingToken = CameraNameNormalizer.extractPairingToken(targetSsid)

            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    super.onScanResult(callbackType, result)
                    val elapsed = System.currentTimeMillis() - scanStartTime
                    val device = result?.device ?: return
                    val candidateName = device.name ?: result.scanRecord?.deviceName
                    val matchedTarget = matchesTargetCamera(
                        candidateName = candidateName,
                        candidateAddress = device.address,
                        targetSsid = targetSsid,
                        targetBleName = targetBleName,
                        targetBleAddress = targetBleAddress,
                        normalizedTargetModel = normalizedTargetModel,
                        targetPairingToken = targetPairingToken,
                    )
                    D.ble(
                        "onScanResult: callbackType=$callbackType, device=${candidateName ?: device.address}, " +
                            "rssi=${result.rssi}, matchedTarget=$matchedTarget, elapsed=${elapsed}ms",
                    )
                    if (!isResumed && (
                            targetSsid.isNullOrBlank() &&
                                targetBleName.isNullOrBlank() &&
                                targetBleAddress.isNullOrBlank() ||
                                matchedTarget
                            )
                    ) {
                        isResumed = true
                        scanner.stopScan(this)
                        continuation.resume(device)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    super.onScanFailed(errorCode)
                    if (!isResumed) {
                        isResumed = true
                        continuation.resumeWithException(Exception("BLE Scan failed with code $errorCode"))
                    }
                }
            }

            scanner.startScan(filters, settings, callback)
            continuation.invokeOnCancellation {
                scanner.stopScan(callback)
            }
        }
    }

    private fun matchesTargetCamera(
        candidateName: String?,
        candidateAddress: String?,
        targetSsid: String?,
        targetBleName: String?,
        targetBleAddress: String?,
        normalizedTargetModel: String?,
        targetPairingToken: String?,
    ): Boolean {
        if (targetSsid.isNullOrBlank() && targetBleName.isNullOrBlank() && targetBleAddress.isNullOrBlank()) {
            return true
        }
        if (!targetBleAddress.isNullOrBlank() &&
            !candidateAddress.isNullOrBlank() &&
            candidateAddress.equals(targetBleAddress, ignoreCase = true)
        ) {
            return true
        }
        val trimmedCandidate = candidateName?.trim()?.trim('"')?.takeIf { it.isNotBlank() } ?: return false
        if (!targetBleName.isNullOrBlank() && trimmedCandidate.equals(targetBleName, ignoreCase = true)) {
            return true
        }
        if (trimmedCandidate.equals(targetSsid, ignoreCase = true)) {
            return true
        }
        val normalizedCandidateModel = CameraNameNormalizer.normalizeSsid(trimmedCandidate)
            ?: CameraNameNormalizer.normalizeModelName(trimmedCandidate)
        if (!normalizedTargetModel.isNullOrBlank() &&
            normalizedCandidateModel != null &&
            normalizedCandidateModel.equals(normalizedTargetModel, ignoreCase = true)
        ) {
            return true
        }
        val candidatePairingToken = CameraNameNormalizer.extractPairingToken(trimmedCandidate)
            ?: trimmedCandidate.takeIf { it.length >= 6 && it.all(Char::isLetterOrDigit) }?.uppercase()
        if (!targetPairingToken.isNullOrBlank() &&
            !candidatePairingToken.isNullOrBlank() &&
            candidatePairingToken.equals(targetPairingToken, ignoreCase = true)
        ) {
            return true
        }
        val upperCandidate = trimmedCandidate.uppercase()
        val upperTarget = targetSsid?.uppercase().orEmpty()
        return upperTarget.startsWith(upperCandidate) || upperCandidate.startsWith(upperTarget)
    }

    private suspend fun connectAndSetupWithRetry(
        device: BluetoothDevice,
        isReconnect: Boolean,
        targetBlePass: String?,
    ) {
        var lastException: Exception? = null
        for (attempt in 1..MAX_GATT_RETRIES) {
            try {
                D.ble("GATT connect attempt $attempt/$MAX_GATT_RETRIES")
                connectAndSetup(device, isReconnect, targetBlePass)
                return
            } catch (e: Exception) {
                lastException = e
                val isGatt133 = e.message?.contains("status 133") == true
                if (isGatt133 && attempt < MAX_GATT_RETRIES) {
                    disconnectInternal()
                    delay(GATT_RETRY_DELAY_MS)
                } else {
                    throw e
                }
            }
        }
        throw lastException ?: Exception("GATT connection failed after $MAX_GATT_RETRIES attempts")
    }

    private suspend fun connectAndSetup(
        device: BluetoothDevice,
        isReconnect: Boolean,
        targetBlePass: String?,
    ) = withTimeout(30000L) {
        suspendCancellableCoroutine<Unit> { continuation ->
            var isContinuationResumed = false
            var setupPhase = PHASE_CONNECTING
            val handler = Handler(Looper.getMainLooper())
            val protocolQueue = ArrayDeque<BleProtocolCommand>()
            var currentCommand: BleProtocolCommand? = null
            var commandAccepted = false
            var pendingResponseFrame: ByteArray? = null
            var ackWriteCompleted = false

            fun fail(gatt: BluetoothGatt?, message: String) {
                if (!isContinuationResumed) {
                    isContinuationResumed = true
                    continuation.resumeWithException(Exception(message))
                }
                gatt?.disconnect()
            }

            fun completeSuccess() {
                if (!isContinuationResumed) {
                    isConnected = true
                    setupPhase = PHASE_DONE
                    isContinuationResumed = true
                    continuation.resume(Unit)
                }
            }

            fun prepareReconnectCommands() {
                if (!isReconnect) {
                    throw IllegalStateException("Non-reconnect BLE wake path is not used in this build.")
                }
                val blePass = targetBlePass?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException(
                        "Saved camera BLE password missing from persisted reconnect data.",
                    )
                protocolQueue += BleProtocolCommand(
                    highByte = 0x0c,
                    lowByte = 0x02,
                    data = blePass.toByteArray(Charsets.UTF_8),
                    label = "ble_auth",
                )
                protocolQueue += BleProtocolCommand(
                    highByte = 0x0f,
                    lowByte = 0x01,
                    data = byteArrayOf(0x02),
                    label = "camera_power_on",
                    delayAfterSuccessMs = 1000L,
                    acceptedResultCodes = setOf(0, 1),
                )
                protocolQueue += BleProtocolCommand(
                    highByte = 0x1d,
                    lowByte = 0x01,
                    data = byteArrayOf(0x02),
                    label = "wifi_start_reconnect",
                )
            }

            val gattCallback = object : BluetoothGattCallback() {
                private fun writeCommand(
                    gatt: BluetoothGatt,
                    command: BleProtocolCommand,
                ) {
                    val service = gatt.getService(OLYMPUS_SERVICE_UUID)
                    val commandCharacteristic = service?.getCharacteristic(COMMAND_CHAR_UUID)
                    if (commandCharacteristic == null) {
                        fail(gatt, "Command characteristic not found")
                        return
                    }
                    currentCommand = command
                    pendingCommandHighByte = command.highByte
                    pendingCommandLowByte = command.lowByte
                    commandAccepted = false
                    pendingResponseFrame = null
                    ackWriteCompleted = false
                    setupPhase = PHASE_WRITING_COMMAND
                    val payload = buildFramedCommandPayload(
                        commandHigh = command.highByte,
                        commandLow = command.lowByte,
                        data = command.data,
                    )
                    D.ble("Writing BLE command ${command.label}: ${payload.joinToString(",") { "0x%02x".format(it) }}")
                    @Suppress("DEPRECATION")
                    commandCharacteristic.value = payload
                    val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(
                            commandCharacteristic,
                            payload,
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                        ) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(commandCharacteristic)
                    }
                    if (!started) {
                        fail(gatt, "Failed to initiate BLE command write for ${command.label}")
                    }
                }

                private fun advanceProtocol(gatt: BluetoothGatt) {
                    val nextCommand = protocolQueue.removeFirstOrNull()
                    if (nextCommand == null) {
                        completeSuccess()
                        return
                    }
                    val previousDelay = currentCommand?.delayAfterSuccessMs ?: 0L
                    if (previousDelay > 0L) {
                        D.ble("Delaying next BLE command by ${previousDelay}ms after ${currentCommand?.label}")
                        handler.postDelayed(
                            {
                                if (!isContinuationResumed) {
                                    writeCommand(gatt, nextCommand)
                                }
                            },
                            previousDelay,
                        )
                    } else {
                        writeCommand(gatt, nextCommand)
                    }
                }

                private fun validateStoredResponseFrame(): Int? {
                    if (!ackWriteCompleted) {
                        return null
                    }
                    val frame = pendingResponseFrame ?: return null
                    if (frame.size <= 6) {
                        return null
                    }
                    if (frame[3] != pendingCommandHighByte) {
                        return null
                    }
                    if (frame[5] != pendingCommandLowByte) {
                        return null
                    }
                    return frame[6].toInt() and 0xFF
                }

                private fun completeCommandAfterAck(gatt: BluetoothGatt) {
                    val responseFrame = pendingResponseFrame
                    val resultCode = validateStoredResponseFrame()
                    val acceptedResultCodes = currentCommand?.acceptedResultCodes ?: setOf(0)
                    if (resultCode != null && acceptedResultCodes.contains(resultCode)) {
                        D.ble(
                            "BLE command ${currentCommand?.label} validated from stored response frame " +
                                "(cmd=0x%02x sub=0x%02x result=0x%02x)".format(
                                    pendingCommandHighByte.toInt() and 0xFF,
                                    pendingCommandLowByte.toInt() and 0xFF,
                                    resultCode,
                                ),
                        )
                        advanceProtocol(gatt)
                    } else {
                        val message = when {
                            responseFrame == null -> "BLE response frame missing after ACK"
                            !ackWriteCompleted -> "BLE ACK delivery not completed for ${currentCommand?.label}"
                            responseFrame.size <= 6 -> "BLE response frame too short for ${currentCommand?.label}"
                            responseFrame[3] != pendingCommandHighByte -> {
                                "BLE response frame command mismatch for ${currentCommand?.label}: " +
                                    "expected 0x%02x got 0x%02x".format(
                                        pendingCommandHighByte.toInt() and 0xFF,
                                        responseFrame[3].toInt() and 0xFF,
                                    )
                            }
                            responseFrame[5] != pendingCommandLowByte -> {
                                "BLE response frame subcommand mismatch for ${currentCommand?.label}: " +
                                    "expected 0x%02x got 0x%02x".format(
                                        pendingCommandLowByte.toInt() and 0xFF,
                                        responseFrame[5].toInt() and 0xFF,
                                    )
                            }
                            else -> "BLE command ${currentCommand?.label} failed with result 0x%02x".format(
                                responseFrame[6].toInt() and 0xFF,
                            )
                        }
                        fail(gatt, message)
                    }
                }

                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    D.ble("onConnectionStateChange: status=$status, newState=$newState")
                    if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        isConnected = false
                        handler.removeCallbacksAndMessages(null)
                        if (!isContinuationResumed) {
                            activeGatt = null
                            gatt.close()
                            fail(null, "GATT disconnected with status $status during BLE wake")
                        } else {
                            activeGatt = null
                            gatt.close()
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        fail(gatt, "Service discovery failed with status $status")
                        return
                    }
                    val service = gatt.getService(OLYMPUS_SERVICE_UUID)
                    if (service == null) {
                        fail(gatt, "Olympus BLE service not found")
                        return
                    }
                    val commandChar = service.getCharacteristic(COMMAND_CHAR_UUID)
                    if (commandChar == null) {
                        fail(gatt, "Command characteristic not found")
                        return
                    }
                    setupPhase = PHASE_NOTIFY_CHAR1
                    enableNotification(gatt, commandChar)
                }

                override fun onDescriptorWrite(
                    gatt: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        fail(gatt, "Descriptor write failed with status $status")
                        return
                    }
                    val service = gatt.getService(OLYMPUS_SERVICE_UUID)
                    if (service == null) {
                        fail(gatt, "Olympus service lost during notification setup")
                        return
                    }
                    when (setupPhase) {
                        PHASE_NOTIFY_CHAR1 -> {
                            val responseChar = service.getCharacteristic(RESPONSE_CHAR_UUID)
                            if (responseChar == null) {
                                fail(gatt, "Response characteristic not found")
                                return
                            }
                            setupPhase = PHASE_NOTIFY_CHAR2
                            enableNotification(gatt, responseChar)
                        }

                        PHASE_NOTIFY_CHAR2 -> {
                            val dataChar = service.getCharacteristic(DATA_CHAR_UUID)
                            if (dataChar == null) {
                                fail(gatt, "Data characteristic not found")
                                return
                            }
                            setupPhase = PHASE_NOTIFY_CHAR3
                            enableNotification(gatt, dataChar)
                        }

                        PHASE_NOTIFY_CHAR3 -> {
                            try {
                                prepareReconnectCommands()
                            } catch (e: Exception) {
                                fail(gatt, e.message ?: "Failed to prepare BLE reconnect commands")
                                return
                            }
                            advanceProtocol(gatt)
                        }
                    }
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    when {
                        characteristic.uuid == COMMAND_CHAR_UUID && setupPhase == PHASE_WRITING_COMMAND -> {
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                fail(gatt, "BLE command write failed with status $status")
                                return
                            }
                            setupPhase = PHASE_AWAITING_RESPONSE
                        }

                        characteristic.uuid == RESPONSE_CHAR_UUID && setupPhase == PHASE_ACK_WRITE_PENDING -> {
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                fail(gatt, "BLE ACK write failed with status $status")
                                return
                            }
                            ackWriteCompleted = true
                            D.ble("BLE ACK delivery completed for ${currentCommand?.label}; validating stored response frame")
                            completeCommandAfterAck(gatt)
                        }
                    }
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    handleNotification(gatt, characteristic, value)
                }

                @Deprecated("Deprecated in API 33+", ReplaceWith("onCharacteristicChanged(gatt, characteristic, value)"))
                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    @Suppress("DEPRECATION")
                    val data = characteristic.value
                    if (data != null && data.isNotEmpty()) {
                        handleNotification(gatt, characteristic, data)
                    }
                }

                private fun handleNotification(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    data: ByteArray,
                ) {
                    D.ble(
                        "onCharacteristicChanged: uuid=${characteristic.uuid}, phase=$setupPhase, " +
                            "data=${data.joinToString(",") { "0x%02x".format(it) }}",
                    )
                    if (setupPhase != PHASE_AWAITING_RESPONSE) {
                        return
                    }
                    when (characteristic.uuid) {
                        COMMAND_CHAR_UUID -> {
                            if (data.isEmpty() || data[0] != 0x05.toByte()) {
                                fail(gatt, "Camera rejected BLE command notification for ${currentCommand?.label}")
                                return
                            }
                            commandAccepted = true
                            D.ble("BLE command ${currentCommand?.label} accepted by camera (0x05)")
                        }

                        RESPONSE_CHAR_UUID -> {
                            val validFrameHeader = commandAccepted &&
                                data.size > 6 &&
                                data[0] == 0x04.toByte() &&
                                data[3] == pendingCommandHighByte
                            if (!validFrameHeader) {
                                fail(gatt, "Invalid BLE response frame for ${currentCommand?.label}")
                                return
                            }
                            pendingResponseFrame = data.copyOf()
                            D.ble(
                                "Stored BLE response frame for ${currentCommand?.label} " +
                                    "(cmd=0x%02x sub=0x%02x result=0x%02x)".format(
                                        data[3].toInt() and 0xFF,
                                        data[5].toInt() and 0xFF,
                                        data[6].toInt() and 0xFF,
                                    ),
                            )
                            val ackFrame = byteArrayOf(0x02, data[1], 0x00, 0x00, 0x00)
                            val service = gatt.getService(OLYMPUS_SERVICE_UUID)
                            val responseChar = service?.getCharacteristic(RESPONSE_CHAR_UUID)
                            if (responseChar == null) {
                                fail(gatt, "Response characteristic lost during ACK")
                                return
                            }
                            setupPhase = PHASE_ACK_WRITE_PENDING
                            @Suppress("DEPRECATION")
                            responseChar.value = ackFrame
                            val ackStarted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeCharacteristic(
                                    responseChar,
                                    ackFrame,
                                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                                ) == BluetoothStatusCodes.SUCCESS
                            } else {
                                @Suppress("DEPRECATION")
                                gatt.writeCharacteristic(responseChar)
                            }
                            if (!ackStarted) {
                                fail(gatt, "Failed to initiate BLE ACK write for ${currentCommand?.label}")
                            }
                        }
                    }
                }
            }

            val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            activeGatt = gatt

            continuation.invokeOnCancellation {
                handler.removeCallbacksAndMessages(null)
                gatt.disconnect()
                gatt.close()
                activeGatt = null
            }
        }
    }

    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    private fun buildFramedCommandPayload(
        commandHigh: Byte,
        commandLow: Byte,
        data: ByteArray,
    ): ByteArray {
        val dataLen = data.size
        val frame = ByteArray(dataLen + 8)
        val seq = bleSequenceNumber
        bleSequenceNumber = (bleSequenceNumber + 1).toByte()

        frame[0] = 0x01
        frame[1] = seq
        frame[2] = (dataLen + 3).toByte()
        frame[3] = commandHigh
        frame[4] = 0x01
        frame[5] = commandLow

        var checksum = (commandHigh.toInt() and 0xFF) + 0x01 + (commandLow.toInt() and 0xFF)
        for (index in data.indices) {
            frame[6 + index] = data[index]
            checksum += data[index].toInt() and 0xFF
        }
        frame[6 + dataLen] = (checksum and 0xFF).toByte()
        frame[7 + dataLen] = 0x00
        return frame
    }
}
