package dev.pl36.cameralink.core.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.Build
import android.os.SystemClock
import dev.pl36.cameralink.core.logging.D
import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed interface PtpDataAndResponseResult {
    data class Success(
        val data: ByteArray,
        val response: PtpContainer,
    ) : PtpDataAndResponseResult

    data class Failure(
        val phase: PtpInitFailurePhase,
        val detail: String,
    ) : PtpDataAndResponseResult
}

/**
 * Low-level PTP USB transport layer.
 *
 * Handles bulk transfer of PTP containers over USB endpoints.
 * Manages the three PTP endpoints: Bulk-Out (commands to camera),
 * Bulk-In (data from camera), and Interrupt-In (events from camera).
 */
class PtpUsbConnection(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val bulkOut: UsbEndpoint,
    private val bulkIn: UsbEndpoint,
    private val interruptIn: UsbEndpoint,
) {
    enum class ClaimMode(
        val logLabel: String,
    ) {
        PortableDeviceCompatible("portable_device"),
        AggressiveDetach("aggressive_detach"),
    }

    companion object {
        /** Default timeout for bulk transfers in milliseconds. */
        private const val DEFAULT_TIMEOUT_MS = 5_000

        /** Large transfer timeout (image downloads). */
        private const val LARGE_TRANSFER_TIMEOUT_MS = 30_000

        /** Maximum USB packet size for bulk transfers. */
        private const val MAX_PACKET_SIZE = 512

        /** Read buffer size (2MB for large image data). */
        private const val READ_BUFFER_SIZE = 2 * 1024 * 1024

        /** Conservative bulk-in chunk size for Samsung/OM interoperability. */
        private const val BULK_IN_TRANSFER_CHUNK_SIZE = 16 * 1024

        /** Retry a failed bulk IN read before treating it as fatal. */
        private const val BULK_IN_TIMEOUT_RETRY_COUNT = 1

        /** Short pause before retrying a timed-out bulk IN read. */
        private const val BULK_IN_TIMEOUT_RETRY_DELAY_MS = 180L

        // PTP Class-Specific USB Control Transfer constants
        // These are defined in the PTP USB Still Image Class specification.
        // bmRequestType for class-specific requests:
        //   Host-to-Device: USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_INTERFACE = 0x21
        //   Device-to-Host: USB_DIR_IN  | USB_TYPE_CLASS | USB_RECIP_INTERFACE = 0xA1
        private const val REQUEST_TYPE_CLASS_OUT =
            UsbConstants.USB_DIR_OUT or (0x01 shl 5) or 0x01  // 0x21
        private const val REQUEST_TYPE_CLASS_IN =
            UsbConstants.USB_DIR_IN or (0x01 shl 5) or 0x01   // 0xA1

        /** PTP Cancel Request (class-specific bRequest). */
        private const val PTP_REQUEST_CANCEL = 0x64

        /** PTP Get Extended Event Data (class-specific bRequest). */
        private const val PTP_REQUEST_GET_EXTENDED_EVENT = 0x65

        /** PTP Device Reset Request (class-specific bRequest). */
        private const val PTP_REQUEST_DEVICE_RESET = 0x66

        /** PTP Get Device Status Request (class-specific bRequest). */
        private const val PTP_REQUEST_GET_DEVICE_STATUS = 0x67

        /** USB standard request: CLEAR_FEATURE. */
        private const val USB_REQUEST_CLEAR_FEATURE = 0x01

        /** USB feature selector: ENDPOINT_HALT. */
        private const val USB_FEATURE_ENDPOINT_HALT = 0x00

        /** Maximum attempts to poll device status after reset. */
        private const val DEVICE_STATUS_POLL_MAX = 10

        /** Delay between device status polls after reset. */
        private const val DEVICE_STATUS_POLL_DELAY_MS = 100L

        /** PTP device status: OK / Ready. */
        private const val PTP_STATUS_OK = 0x2001

        /** PTP device status: Device Busy. */
        private const val PTP_STATUS_BUSY = 0x2019

        /** Maximum allowed PTP data payload (256 MB). Rejects corrupt headers before allocation. */
        private const val MAX_PTP_DATA_LENGTH = 256 * 1024 * 1024
    }

    @Volatile
    private var claimed = false

    @Volatile
    private var kernelDriverDetached = false

    @Volatile
    var isClosed: Boolean = false
        private set

    private val useNativeBulkTransfers =
        UsbNative.isAvailable &&
            Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
            Build.VERSION.SDK_INT >= 36
    @Volatile
    private var bulkTransferBackendLogged = false
    @Volatile
    private var interruptTransferBackendLogged = false
    @Volatile
    private var extendedEventBackendLogged = false

    /**
     * Claim the USB interface for PTP communication.
     *
     * Some camera/OS combinations expose PTP through an MTP-style handoff, so
     * the default claim path stays close to an OS-managed portable-device session.
     */
    fun claim(mode: ClaimMode = ClaimMode.PortableDeviceCompatible): Boolean {
        if (isClosed) return false
        if (claimed) return true
        D.usb("Interface claim strategy=${mode.logLabel}")
        kernelDriverDetached = false

        // Explicitly detach the kernel MTP driver from this interface BEFORE claiming.
        // On Samsung Android 16+, the kernel MTP host driver holds the bulk-IN endpoint
        // even after claimInterface(force=true). USBDEVFS_DISCONNECT is the only reliable
        // way to fully release it — this is equivalent to libusb_detach_kernel_driver().
        if (mode == ClaimMode.AggressiveDetach && UsbNative.isAvailable) {
            val fd = connection.fileDescriptor
            val detachResult = UsbNative.disconnectKernelDriver(fd, usbInterface.id)
            D.usb("disconnectKernelDriver(fd=$fd, iface=${usbInterface.id}): result=$detachResult")
            kernelDriverDetached = detachResult >= 0
        }

        val result = connection.claimInterface(usbInterface, true)
        claimed = result
        D.usb("Interface claim: $result")
        if (result && mode == ClaimMode.AggressiveDetach) {
            val setIfResult = connection.setInterface(usbInterface)
            D.usb("setInterface(alt=${usbInterface.alternateSetting}): $setIfResult")
        }
        return result
    }

    /**
     * Release the USB interface.
     */
    fun release() {
        if (!claimed) return
        connection.releaseInterface(usbInterface)
        claimed = false
        D.usb("Interface released")
    }

    /**
     * Send a PTP command container to the camera.
     * @return true if all bytes were sent successfully.
     */
    fun sendCommand(container: PtpContainer): Boolean {
        if (isClosed) return false
        val bytes = container.toByteArray()
        D.ptp(">> ${container}")
        val sent = bulkTransfer(
            endpoint = bulkOut,
            buffer = bytes,
            length = bytes.size,
            timeoutMs = DEFAULT_TIMEOUT_MS,
            label = "send-command:${container.code.opName()}",
        )
        if (sent != bytes.size) {
            D.err("PTP", "Send failed: sent $sent of ${bytes.size} bytes")
            return false
        }
        return true
    }

    /**
     * Send a PTP data container to the camera.
     * For large payloads, splits into multiple USB packets.
     */
    fun sendData(container: PtpContainer): Boolean {
        if (isClosed) return false
        val bytes = container.toByteArray()
        D.ptp(">> DATA ${container.code.opName()} ${bytes.size} bytes")

        var offset = 0
        while (offset < bytes.size) {
            val chunkSize = minOf(MAX_PACKET_SIZE, bytes.size - offset)
            val sent = bulkTransfer(
                endpoint = bulkOut,
                buffer = bytes,
                offset = offset,
                length = chunkSize,
                timeoutMs = DEFAULT_TIMEOUT_MS,
                label = "send-data:${container.code.opName()}@$offset",
            )
            if (sent < 0) {
                D.err("PTP", "Data send failed at offset $offset")
                return false
            }
            offset += sent
        }
        return true
    }

    /**
     * Receive a PTP response container from the camera.
     * Reads the header first to determine total length, then reads remaining data.
     *
     * @param timeoutMs timeout for the bulk transfer
     * @return parsed PTP container, or null on error/timeout
     */
    fun receiveResponse(timeoutMs: Int = DEFAULT_TIMEOUT_MS): PtpContainer? {
        if (isClosed) return null
        val buffer = ByteArray(MAX_PACKET_SIZE)
        val received = readBulkIn(
            buffer = buffer,
            length = buffer.size,
            timeoutMs = timeoutMs,
            label = "response",
        )
        if (received < PtpConstants.CONTAINER_HEADER_SIZE) {
            D.err("PTP", "Receive failed: got $received bytes (need >= ${PtpConstants.CONTAINER_HEADER_SIZE})")
            return null
        }

        val container = PtpContainer.parse(buffer, 0, received)
        if (container != null) {
            D.ptp("<< ${container}")
            D.ptp("<< RESP code=${PtpConstants.Resp.name(container.code)} raw=${buffer.copyOf(received).toHexString()}")
        }
        return container
    }

    /**
     * Receive a large data transfer from the camera.
     * Used for image downloads and live view frames.
     *
     * The camera sends:
     *   1. Data container header (with total length)
     *   2. Data payload (possibly split across multiple USB packets)
     *   3. Response container
     *
     * @return Pair of (data payload, response container) or null on error
     */
    fun receiveDataAndResponse(timeoutMs: Int = LARGE_TRANSFER_TIMEOUT_MS): Pair<ByteArray, PtpContainer>? {
        return when (val result = receiveDataAndResponseDetailed(timeoutMs)) {
            is PtpDataAndResponseResult.Success -> result.data to result.response
            is PtpDataAndResponseResult.Failure -> null
        }
    }

    fun receiveDataAndResponseDetailed(timeoutMs: Int = LARGE_TRANSFER_TIMEOUT_MS): PtpDataAndResponseResult {
        if (isClosed) {
            return PtpDataAndResponseResult.Failure(
                phase = PtpInitFailurePhase.DataReceive,
                detail = "transport is closed",
            )
        }
        // Read first packet (contains data container header)
        val firstPacketSize = maxOf(MAX_PACKET_SIZE, minOf(BULK_IN_TRANSFER_CHUNK_SIZE, bulkIn.maxPacketSize * 4))
        val firstPacket = ByteArray(firstPacketSize)
        val firstReceived = readBulkIn(
            buffer = firstPacket,
            length = firstPacket.size,
            timeoutMs = timeoutMs,
            label = "data-header",
        )
        if (firstReceived < PtpConstants.CONTAINER_HEADER_SIZE) {
            D.err("PTP", "Data receive failed: got $firstReceived bytes")
            return PtpDataAndResponseResult.Failure(
                phase = PtpInitFailurePhase.DataReceive,
                detail = "data receive failed: got $firstReceived bytes",
            )
        }

        // Parse container header to get total length
        val headerBuf = ByteBuffer.wrap(firstPacket, 0, PtpConstants.CONTAINER_HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        val totalLength = headerBuf.getInt()
        val containerType = headerBuf.getShort().toInt() and 0xFFFF

        // Check if this is actually a data container
        if (containerType != PtpConstants.CONTAINER_TYPE_DATA) {
            // It's a response directly (no data phase)
            val resp = PtpContainer.parse(firstPacket, 0, firstReceived)
            if (resp != null) {
                D.ptp("<< RESP-DIRECT code=${PtpConstants.Resp.name(resp.code)} raw=${firstPacket.copyOf(firstReceived).toHexString()}")
                return PtpDataAndResponseResult.Success(ByteArray(0), resp)
            }
            return PtpDataAndResponseResult.Failure(
                phase = PtpInitFailurePhase.ResponseReceive,
                detail = "response receive failed: unable to parse direct response container",
            )
        }

        val dataLength = totalLength - PtpConstants.CONTAINER_HEADER_SIZE
        if (dataLength < 0 || dataLength > MAX_PTP_DATA_LENGTH) {
            D.err("PTP", "Rejecting PTP data transfer with invalid length: $dataLength (totalLength=$totalLength)")
            return PtpDataAndResponseResult.Failure(
                phase = PtpInitFailurePhase.DataReceive,
                detail = "invalid data length $dataLength (totalLength=$totalLength, max=$MAX_PTP_DATA_LENGTH)",
            )
        }
        val dataBuffer = ByteArray(dataLength)

        // Copy data from first packet (after header)
        val firstDataSize = minOf(firstReceived - PtpConstants.CONTAINER_HEADER_SIZE, dataLength)
        System.arraycopy(firstPacket, PtpConstants.CONTAINER_HEADER_SIZE, dataBuffer, 0, firstDataSize)

        // Read remaining data if needed
        var totalRead = firstDataSize
        while (totalRead < dataLength) {
            val remaining = dataLength - totalRead
            val chunkSize = minOf(BULK_IN_TRANSFER_CHUNK_SIZE, remaining)
            val chunk = ByteArray(chunkSize)
            val received = readBulkIn(
                buffer = chunk,
                length = chunkSize,
                timeoutMs = timeoutMs,
                label = "data-chunk@$totalRead",
            )
            if (received < 0) {
                D.err("PTP", "Data read failed at offset $totalRead of $dataLength")
                return PtpDataAndResponseResult.Failure(
                    phase = PtpInitFailurePhase.DataReceive,
                    detail = "data receive failed at offset $totalRead of $dataLength",
                )
            }
            System.arraycopy(chunk, 0, dataBuffer, totalRead, received)
            totalRead += received
        }

        D.ptp("<< DATA ${dataLength} bytes received")

        // Now read the response container
        val response = receiveResponse(timeoutMs)
            ?: return PtpDataAndResponseResult.Failure(
                phase = PtpInitFailurePhase.ResponseReceive,
                detail = "response receive failed: no response container",
            )

        return PtpDataAndResponseResult.Success(dataBuffer, response)
    }

    /**
     * Read an event from the interrupt endpoint using [UsbRequest].
     * @return event container, or null if no event pending
     */
    /**
     * Read an event from the interrupt endpoint.
     *
     * Some devices signal preview readiness through these callback-style events.
     * The Samsung API 36 workaround uses USBDEVFS_BULK for bulk endpoints only;
     * the interrupt endpoint must stay on the framework transfer path because
     * USBDEVFS_BULK is a bulk-only ioctl.
     *
     * @return event container, or null if no event pending
     */
    fun pollEvent(timeoutMs: Int = 100): PtpContainer? {
        if (isClosed) return null
        val buffer = ByteArray(interruptIn.maxPacketSize)
        val received = bulkTransfer(
            endpoint = interruptIn,
            buffer = buffer,
            length = buffer.size,
            timeoutMs = timeoutMs,
            label = "interrupt",
        )
        if (received < 0) {
            return null
        }
        if (received in 1 until PtpConstants.CONTAINER_HEADER_SIZE) {
            D.ptp("interrupt poll short packet bytes=$received raw=${buffer.copyOf(received).toHexString()}")
            return null
        }
        if (received < PtpConstants.CONTAINER_HEADER_SIZE) return null
        D.ptp("<< INT raw=${buffer.copyOf(received).toHexString()}")
        return PtpContainer.parse(buffer, 0, received)
    }

    /**
     * Poll the standard PTP USB class-specific "Get Extended Event Data" path.
     *
     * This standards-based event retrieval path is kept as a secondary option for
     * bodies or Android stacks where the interrupt endpoint stays silent.
     */
    fun pollExtendedEvent(timeoutMs: Int = DEFAULT_TIMEOUT_MS): PtpContainer? {
        if (isClosed) return null
        if (!extendedEventBackendLogged) {
            extendedEventBackendLogged = true
            D.usb(
                "PTP extended-event backend=usb_class_control_request " +
                    "request=0x65 interface=${usbInterface.id}",
            )
        }

        val buffer = ByteArray(512)
        val received = connection.controlTransfer(
            REQUEST_TYPE_CLASS_IN,
            PTP_REQUEST_GET_EXTENDED_EVENT,
            0,
            usbInterface.id,
            buffer,
            buffer.size,
            timeoutMs,
        )
        if (received <= 0) {
            return null
        }
        if (received < PtpConstants.CONTAINER_HEADER_SIZE) {
            D.ptp("extended event short packet bytes=$received raw=${buffer.copyOf(received).toHexString()}")
            return null
        }
        D.ptp("<< EXT raw=${buffer.copyOf(received).toHexString()}")
        val event = PtpContainer.parse(buffer, 0, received)
        if (event?.type != PtpConstants.CONTAINER_TYPE_EVENT) {
            D.ptp(
                "extended event payload was not a PTP event container " +
                    "type=${event?.type?.toString(16) ?: "n/a"} bytes=$received",
            )
            return null
        }
        return event
    }

    // PTP Class-Specific Control Transfers
    /**
     * Send a PTP Device Reset via USB class-specific control transfer.
     *
     * This resets the PTP session on the camera side, clearing any stale
     * transaction state. Must be called AFTER claimInterface() and BEFORE
     * the first PTP bulk transfer (GetDeviceInfo).
     *
     * On Android, the system MTP service may have already opened a session
     * with the camera. This reset forces the camera back to a clean state
     * so our app can establish a fresh PTP session.
     *
     * @return true if the reset was acknowledged by the device
     */
    fun resetDevice(): Boolean {
        if (isClosed) return false
        D.ptp(">> PTP Device Reset (controlTransfer bRequest=0x66)")
        val result = connection.controlTransfer(
            REQUEST_TYPE_CLASS_OUT,     // bmRequestType = 0x21
            PTP_REQUEST_DEVICE_RESET,   // bRequest = 0x66
            0,                          // wValue = 0
            0,                          // wIndex = 0 (interface number)
            null,                       // no data
            0,                          // length = 0
            DEFAULT_TIMEOUT_MS,
        )
        D.ptp("<< PTP Device Reset result=$result")
        return result >= 0
    }

    /**
     * Get PTP device status via USB class-specific control transfer.
     *
     * Returns the 2-byte status code, or -1 on failure.
     * Status 0x2001 = OK/Ready, 0x2019 = Busy.
     */
    fun getDeviceStatus(): Int {
        if (isClosed) return -1
        // Device status response: at least 4 bytes (2 length + 2 status code)
        // but can be longer if there are pending events
        val buffer = ByteArray(32)
        val received = connection.controlTransfer(
            REQUEST_TYPE_CLASS_IN,          // bmRequestType = 0xA1
            PTP_REQUEST_GET_DEVICE_STATUS,  // bRequest = 0x67
            0,                              // wValue = 0
            0,                              // wIndex = 0
            buffer,
            buffer.size,
            DEFAULT_TIMEOUT_MS,
        )
        if (received < 4) {
            D.ptp("<< PTP Get Device Status failed: received=$received")
            return -1
        }
        val statusBuf = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        val length = statusBuf.getShort().toInt() and 0xFFFF
        val statusCode = statusBuf.getShort().toInt() and 0xFFFF
        D.ptp("<< PTP Device Status: code=0x${statusCode.toString(16)} length=$length received=$received raw=${buffer.copyOf(received).toHexString()}")
        return statusCode
    }

    /**
     * Send a PTP Cancel Request via USB class-specific control transfer.
     *
     * Cancels any pending PTP transaction. The cancellation code
     * should be the transaction ID of the operation to cancel.
     *
     * @param cancellationCode the transaction ID to cancel (or 0 for any)
     * @return true if the cancel was acknowledged
     */
    fun cancelRequest(cancellationCode: Int = 0): Boolean {
        if (isClosed) return false
        // Cancel request data: 6 bytes (2 cancellation code + 4 transaction ID)
        val data = ByteArray(6)
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0x4001.toShort()) // Cancellation code
        buf.putInt(cancellationCode)
        D.ptp(">> PTP Cancel Request (controlTransfer bRequest=0x64) txn=$cancellationCode")
        val result = connection.controlTransfer(
            REQUEST_TYPE_CLASS_OUT,
            PTP_REQUEST_CANCEL,
            0, 0,
            data, data.size,
            DEFAULT_TIMEOUT_MS,
        )
        D.ptp("<< PTP Cancel Request result=$result")
        return result >= 0
    }

    /**
     * Clear HALT condition on a USB endpoint.
     *
     * When an endpoint is stalled (e.g., from a failed previous transfer),
     * this clears the halt so transfers can resume.
     */
    fun clearEndpointHalt(endpoint: UsbEndpoint): Boolean {
        val result = connection.controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD or 0x02, // RECIP_ENDPOINT
            USB_REQUEST_CLEAR_FEATURE,
            USB_FEATURE_ENDPOINT_HALT,
            endpoint.address,
            null, 0,
            DEFAULT_TIMEOUT_MS,
        )
        D.ptp("Clear HALT endpoint=0x${endpoint.address.toString(16)} result=$result")
        return result >= 0
    }

    /**
     * Full PTP transport initialization sequence.
     *
     * This performs the USB-level initialization that Windows WPD does
     * automatically but Android requires us to do manually:
     *
     * 1. Clear endpoint halts (in case of stale state)
     * 2. Send PTP Device Reset to cancel any previous session
     * 3. Drain stale data from bulk-in endpoint
     * 4. Poll device status until ready
     *
     * Call this AFTER claim() and BEFORE any PTP commands.
     *
     * @return true if device is ready for PTP communication
     */
    fun initializePtpTransport(): Boolean {
        D.ptp("=== PTP Transport Init Begin ===")

        // Step 1: Clear endpoint halts
        D.ptp("Step 1: Clearing endpoint halts")
        clearEndpointHalt(bulkIn)
        clearEndpointHalt(bulkOut)
        clearEndpointHalt(interruptIn)

        // Step 2: Send PTP Device Reset
        D.ptp("Step 2: PTP Device Reset")
        val resetResult = resetDevice()
        D.ptp("Device Reset acknowledged=$resetResult")

        // Step 3: Post-reset stabilization delay
        D.ptp("Step 3: Post-reset stabilization delay 500ms")
        SystemClock.sleep(500)

        // Step 4: Clear endpoint halts again after reset
        D.ptp("Step 4: Clearing endpoint halts post-reset")
        clearEndpointHalt(bulkIn)
        clearEndpointHalt(bulkOut)

        // Step 5: Poll device status until ready
        D.ptp("Step 5: Polling device status")
        var ready = false
        for (i in 1..DEVICE_STATUS_POLL_MAX) {
            val status = getDeviceStatus()
            when (status) {
                PTP_STATUS_OK -> {
                    D.ptp("Device status OK (ready) after $i polls")
                    ready = true
                    break
                }
                PTP_STATUS_BUSY -> {
                    D.ptp("Device busy, poll $i/$DEVICE_STATUS_POLL_MAX, waiting ${DEVICE_STATUS_POLL_DELAY_MS}ms")
                    SystemClock.sleep(DEVICE_STATUS_POLL_DELAY_MS)
                }
                -1 -> {
                    D.ptp("Device status not supported (returned -1), proceeding optimistically after poll $i")
                    ready = true
                    break
                }
                else -> {
                    D.ptp("Device status=0x${status.toString(16)}, poll $i/$DEVICE_STATUS_POLL_MAX")
                    SystemClock.sleep(DEVICE_STATUS_POLL_DELAY_MS)
                }
            }
        }

        // Step 6: Quick diagnostic — test bulkTransfer on bulk-in with tiny timeout
        // to check if the endpoint is responsive before proceeding.
        if (ready) {
            D.ptp("Step 6: Diagnostic bulk-in test (bulkTransfer 100ms)")
            val diagBuf = ByteArray(MAX_PACKET_SIZE)
            val diagStart = SystemClock.elapsedRealtime()
            val diagResult = bulkTransfer(
                endpoint = bulkIn,
                buffer = diagBuf,
                length = diagBuf.size,
                timeoutMs = 100,
                label = "diag-bulk-in",
            )
            val diagElapsed = SystemClock.elapsedRealtime() - diagStart
            D.ptp("Step 6: Diagnostic bulk-in result=$diagResult elapsed=${diagElapsed}ms")
        }

        D.ptp("=== PTP Transport Init End (ready=$ready) ===")
        return ready
    }

    /**
     * Drain any stale data from the bulk-in endpoint.
     * Uses bulkTransfer with short timeouts (NOT UsbRequest, which hangs on Samsung API 36).
     */
    private fun drainBulkIn() {
        var drained = 0
        val buf = ByteArray(MAX_PACKET_SIZE)
        while (true) {
            val received = bulkTransfer(
                endpoint = bulkIn,
                buffer = buf,
                length = buf.size,
                timeoutMs = 50,
                label = "drain-bulk-in",
            )
            if (received <= 0) break
            drained += received
            D.ptp("Drained $received bytes from bulk-in (total $drained)")
            if (drained > MAX_PACKET_SIZE * 20) {
                D.ptp("Drain limit reached, stopping")
                break
            }
        }
        if (drained > 0) {
            D.ptp("Total drained from bulk-in: $drained bytes")
        }
    }

    /**
     * Close the USB connection.
     */
    fun close() {
        if (isClosed) return
        isClosed = true
        runCatching { release() }
        // Re-attach the kernel driver so the system MTP service can resume
        if (UsbNative.isAvailable && kernelDriverDetached) {
            runCatching {
                UsbNative.connectKernelDriver(connection.fileDescriptor, usbInterface.id)
            }
        }
        kernelDriverDetached = false
        runCatching { connection.close() }
        D.usb("Connection closed")
    }

    private fun Int.opName(): String = PtpConstants.opName(this)

    /**
     * Read from bulk-in endpoint using native USBDEVFS_BULK when available,
     * otherwise Android's Java bulkTransfer wrapper.
     *
     * UsbRequest.requestWait() hangs indefinitely on Samsung Android 16 (API 36)
     * regardless of the timeout parameter. Real-device logs also suggest the
     * Java bulkTransfer wrapper can fail even when the underlying USB device
     * file descriptor is healthy, so Samsung API 36 prefers the native ioctl.
     */
    private fun readBulkIn(
        buffer: ByteArray,
        length: Int,
        timeoutMs: Int,
        label: String,
        retryCount: Int = BULK_IN_TIMEOUT_RETRY_COUNT,
    ): Int {
        var attempt = 0
        val maxAttempts = retryCount + 1
        while (attempt < maxAttempts && !isClosed) {
            attempt += 1
            val startedAt = SystemClock.elapsedRealtime()

            val received = bulkTransfer(
                endpoint = bulkIn,
                buffer = buffer,
                length = length,
                timeoutMs = timeoutMs,
                label = label,
            )
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt

            if (received >= 0) {
                D.ptp(
                    "bulkIn $label OK attempt=$attempt/$maxAttempts " +
                        "elapsed=${elapsedMs}ms bytes=$received",
                )
                return received
            }

            val instantFail = elapsedMs < 50
            D.ptp(
                "bulkIn $label fail attempt $attempt/$maxAttempts " +
                    "after ${elapsedMs}ms result=$received timeout=${timeoutMs}ms " +
                    "instantFail=$instantFail",
            )
            if (attempt >= maxAttempts) break

            val retryDelayMs = if (instantFail) {
                BULK_IN_TIMEOUT_RETRY_DELAY_MS * 2
            } else {
                BULK_IN_TIMEOUT_RETRY_DELAY_MS
            }
            SystemClock.sleep(retryDelayMs)
        }
        return -1
    }

    private fun bulkTransfer(
        endpoint: UsbEndpoint,
        buffer: ByteArray,
        length: Int,
        timeoutMs: Int,
        label: String,
        offset: Int = 0,
    ): Int {
        val useNativeBackendForEndpoint =
            useNativeBulkTransfers && endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        val backendLabel = if (useNativeBackendForEndpoint) {
            "native_usbdevfs_bulk"
        } else {
            "java_bulk_transfer"
        }

        if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && !bulkTransferBackendLogged) {
            bulkTransferBackendLogged = true
            D.usb(
                "PTP bulk transfer backend=$backendLabel " +
                    "manufacturer=${Build.MANUFACTURER} sdk=${Build.VERSION.SDK_INT}",
            )
        }
        if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT && !interruptTransferBackendLogged) {
            interruptTransferBackendLogged = true
            D.usb(
                "PTP interrupt transfer backend=$backendLabel " +
                    "endpoint=${endpoint.address.toString(16)} " +
                    "reason=USBDEVFS_BULK is bulk-only; keep RecView event path on framework USB",
            )
        }

        if (useNativeBackendForEndpoint) {
            return UsbNative.bulkTransfer(
                fd = connection.fileDescriptor,
                endpointAddress = endpoint.address,
                buffer = buffer,
                offset = offset,
                length = length,
                timeoutMs = timeoutMs,
            )
        }

        return if (offset == 0) {
            connection.bulkTransfer(endpoint, buffer, length, timeoutMs)
        } else {
            connection.bulkTransfer(endpoint, buffer, offset, length, timeoutMs)
        }
    }

    private fun ByteArray.toHexString(limit: Int = 96): String {
        val preview = take(limit).joinToString(" ") { byte -> "%02X".format(byte.toInt() and 0xFF) }
        return if (size > limit) "$preview ..." else preview
    }
}
