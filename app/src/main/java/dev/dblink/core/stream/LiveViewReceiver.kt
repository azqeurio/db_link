package dev.dblink.core.stream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import dev.dblink.core.logging.D
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Receives MJPEG live view frames from the Olympus camera via UDP.
 *
 * The receiver keeps the socket loop lightweight and offloads JPEG decoding to a
 * separate coroutine. This avoids packet loss while BitmapFactory is busy.
 */
enum class StreamHealth { Active, Stale, Lost }

data class LiveViewMetadata(
    val apertureCurrentTenths: Int? = null,
    val apertureMaxTenths: Int? = null,
    val apertureMinTenths: Int? = null,
)

class LiveViewReceiver(private val port: Int = 65000) {

    @Volatile
    private var running = false
    @Volatile
    private var socketReady = false
    private var socket: DatagramSocket? = null
    private var lastFrameBytes: ByteArray? = null

    private val _frames = MutableSharedFlow<Bitmap>(replay = 1, extraBufferCapacity = 2)
    val frames: Flow<Bitmap> = _frames.asSharedFlow()

    private val _health = MutableStateFlow(StreamHealth.Active)
    val health: StateFlow<StreamHealth> = _health.asStateFlow()

    private val _metadata = MutableStateFlow<LiveViewMetadata?>(null)
    val metadata: StateFlow<LiveViewMetadata?> = _metadata.asStateFlow()

    val isRunning: Boolean get() = running
    val isSocketReady: Boolean get() = socketReady

    suspend fun start(
        cameraNetwork: Network? = null,
        connectivityManager: ConnectivityManager? = null,
        onSocketReady: (() -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        if (running) {
            D.stream("LiveViewReceiver already running on port $port")
            return@withContext
        }

        coroutineScope {
            D.stream("LiveViewReceiver starting on port $port")
            running = true
            socketReady = false

            val buffer = ByteArray(65536)
            val frameBuffer = ByteArrayOutputStream(256 * 1024)
            val assembledFrames = MutableSharedFlow<ByteArray>(
                replay = 0,
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

            var inFrame = false
            var assembledFrameCount = 0
            var decodedFrameCount = 0
            var errorCount = 0
            var packetCount = 0
            var lastSequenceNumber: Int? = null
            var packetGapCount = 0
            var partialFrameDropCount = 0
            var decodeDropCount = 0
            var lastStatsLogAt = System.currentTimeMillis()

            val decoderJob = launch(Dispatchers.Default) {
                assembledFrames.collect { jpegBytes ->
                    val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                    if (bitmap != null) {
                        lastFrameBytes = jpegBytes
                        decodedFrameCount++
                        _frames.emit(bitmap)
                        if (decodedFrameCount % 30 == 1) {
                            D.stream(
                                "Decoded frame #$decodedFrameCount: ${bitmap.width}x${bitmap.height}, ${jpegBytes.size} bytes",
                            )
                        }
                    } else if (decodedFrameCount < 3) {
                        D.stream("LiveViewReceiver: decode failed (${jpegBytes.size} bytes)")
                    }
                }
            }

            fun logStats(force: Boolean = false) {
                val now = System.currentTimeMillis()
                if (!force && now - lastStatsLogAt < 4000L) {
                    return
                }
                lastStatsLogAt = now
                D.stream(
                    "LiveView stats: packets=$packetCount, assembled=$assembledFrameCount, decoded=$decodedFrameCount, gaps=$packetGapCount, partialDrops=$partialFrameDropCount, decoderDrops=$decodeDropCount",
                )
            }

            try {
                var localAddr: Inet4Address? = null
                if (cameraNetwork != null && connectivityManager != null) {
                    try {
                        val linkProps = connectivityManager.getLinkProperties(cameraNetwork)
                        localAddr = linkProps?.linkAddresses
                            ?.map { it.address }
                            ?.filterIsInstance<Inet4Address>()
                            ?.firstOrNull()
                        D.stream("Camera network local address: $localAddr")
                    } catch (e: Exception) {
                        D.stream("Could not determine camera network local address: ${e.message}")
                    }
                }

                val udpSocket = run {
                    val raw = if (localAddr != null) {
                        DatagramSocket(null)
                    } else {
                        DatagramSocket(null)
                    }
                    try {
                        raw.reuseAddress = true
                        if (localAddr != null) {
                            raw.bind(InetSocketAddress(localAddr, port))
                            D.stream("UDP socket bound to ${localAddr.hostAddress}:$port")
                        } else {
                            raw.bind(InetSocketAddress(port))
                            D.stream("UDP socket bound to 0.0.0.0:$port (no specific interface)")
                        }
                        raw.soTimeout = 3000
                        raw.receiveBufferSize = 8 * 1024 * 1024
                        socket = raw
                        raw
                    } catch (e: Exception) {
                        raw.close()
                        throw e
                    }
                }

                if (cameraNetwork != null && localAddr == null) {
                    try {
                        cameraNetwork.bindSocket(udpSocket)
                        D.stream("UDP socket bound to camera network $cameraNetwork via bindSocket()")
                    } catch (e: Exception) {
                        D.err("STREAM", "Failed to bind socket to camera network", e)
                    }
                }

                D.stream("UDP socket ready on port $port, bufferSize=${udpSocket.receiveBufferSize}, localAddr=${udpSocket.localAddress}")
                socketReady = true
                onSocketReady?.invoke()

                while (running && isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        udpSocket.receive(packet)

                        val data = packet.data
                        val length = packet.length
                        packetCount++

                        if (packetCount == 1) {
                            D.stream("First UDP packet received! length=$length, from=${packet.address}:${packet.port}")
                            val headerBytes = data.slice(packet.offset until minOf(packet.offset + 16, packet.offset + length))
                                .joinToString(",") { "0x${(it.toInt() and 0xFF).toString(16).padStart(2, '0')}" }
                            D.stream("First packet header bytes: $headerBytes")
                        }

                        if (length < 8) {
                            continue
                        }

                        val packetInfo = parsePacketInfo(data, packet.offset, length)
                        packetInfo.metadata?.let { metadata ->
                            if (_metadata.value != metadata) {
                                _metadata.value = metadata
                            }
                        }
                        if (packetInfo.sequenceNumber != null && lastSequenceNumber != null) {
                            val expected = (lastSequenceNumber + 1) and 0xFFFF
                            if (packetInfo.sequenceNumber != expected) {
                                packetGapCount++
                                frameBuffer.reset()
                                inFrame = false
                            }
                        }
                        packetInfo.sequenceNumber?.let { lastSequenceNumber = it }

                        if (packetCount <= 5) {
                            D.stream(
                                "Packet #$packetCount: length=$length, payloadOffset=${packetInfo.payloadOffset - packet.offset}, marker=${packetInfo.marker}",
                            )
                        }

                        var soiOffset = -1
                        val payloadStart = packetInfo.payloadOffset.coerceIn(packet.offset, packet.offset + length)
                        for (i in payloadStart until packet.offset + length - 1) {
                            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte()) {
                                soiOffset = i
                                break
                            }
                        }

                        if (soiOffset >= 0) {
                            if (inFrame && frameBuffer.size() > 0) {
                                partialFrameDropCount++
                            }
                            if (packetCount <= 5) {
                                D.stream("JPEG SOI found at offset ${soiOffset - packet.offset} in packet #$packetCount")
                            }
                            frameBuffer.reset()
                            inFrame = true
                            val payloadLength = length - (soiOffset - packet.offset)
                            frameBuffer.write(data, soiOffset, payloadLength)
                        } else if (inFrame) {
                            val payloadOffset = packetInfo.payloadOffset.coerceAtLeast(packet.offset)
                            val payloadLength = packet.offset + length - payloadOffset
                            if (payloadLength > 0) {
                                frameBuffer.write(data, payloadOffset, payloadLength)
                            }
                            if (frameBuffer.size() > 512 * 1024) {
                                partialFrameDropCount++
                                frameBuffer.reset()
                                inFrame = false
                            }
                        }

                        if (inFrame) {
                            val pktStart = payloadStart
                            val pktEnd = packet.offset + length
                            var eoiFoundInPacket = false
                            for (i in maxOf(pktStart, pktEnd - 16) until pktEnd - 1) {
                                if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD9.toByte()) {
                                    eoiFoundInPacket = true
                                    break
                                }
                            }

                            if (eoiFoundInPacket) {
                                val bytes = frameBuffer.toByteArray()
                                val totalBytes = bytes.size
                                for (index in totalBytes - 2 downTo maxOf(totalBytes - 16, 0)) {
                                    if (bytes[index] == 0xFF.toByte() && bytes[index + 1] == 0xD9.toByte()) {
                                        val frameSize = index + 2
                                        inFrame = false
                                        assembledFrameCount++

                                        val validJpeg = frameSize >= 1000 &&
                                            bytes[0] == 0xFF.toByte() &&
                                            bytes[1] == 0xD8.toByte()
                                        if (validJpeg) {
                                            if (!assembledFrames.tryEmit(bytes.copyOf(frameSize))) {
                                                decodeDropCount++
                                            }
                                        }
                                        frameBuffer.reset()
                                        logStats()
                                        break
                                    }
                                }
                            }
                        }

                        errorCount = 0
                        _health.value = StreamHealth.Active
                    } catch (_: SocketTimeoutException) {
                        errorCount++
                        if (errorCount >= 3) {
                            _health.value = StreamHealth.Stale
                            D.stream(
                                "LiveViewReceiver: $errorCount consecutive timeouts, packets=$packetCount, decoded=$decodedFrameCount",
                            )
                        }
                        if (errorCount >= 6) {
                            _health.value = StreamHealth.Lost
                            D.stream("LiveViewReceiver: stream lost after $errorCount timeouts, breaking receive loop")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    D.err("STREAM", "LiveViewReceiver error", e)
                }
            } finally {
                logStats(force = true)
                decoderJob.cancelAndJoin()
                D.stream(
                    "LiveViewReceiver stopped after $decodedFrameCount decoded frames, $packetCount packets total",
                )
                cleanup()
            }
        }
    }

    fun stop() {
        D.stream("LiveViewReceiver stop() called")
        running = false
        socketReady = false
        cleanup()
    }

    private fun cleanup() {
        running = false
        socketReady = false
        _health.value = StreamHealth.Active
        _metadata.value = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    private fun parsePacketInfo(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): PacketInfo {
        if (length < 12) {
            return PacketInfo(payloadOffset = offset, sequenceNumber = null, marker = false, metadata = null)
        }

        val firstByte = data[offset].toInt() and 0xFF
        if (firstByte and 0xC0 != 0x80) {
            return PacketInfo(payloadOffset = offset, sequenceNumber = null, marker = false, metadata = null)
        }

        val secondByte = data[offset + 1].toInt() and 0xFF
        val csrcCount = firstByte and 0x0F
        val hasExtension = firstByte and 0x10 != 0
        var headerLength = 12 + (csrcCount * 4)
        val extensionHeaderStart = offset + headerLength
        var metadata: LiveViewMetadata? = null
        if (headerLength > length) {
            return PacketInfo(
                payloadOffset = offset,
                sequenceNumber = null,
                marker = secondByte and 0x80 != 0,
                metadata = null,
            )
        }

        if (hasExtension) {
            if (headerLength + 4 > length) {
                return PacketInfo(
                    payloadOffset = offset + headerLength,
                    sequenceNumber = sequenceNumber(data, offset),
                    marker = secondByte and 0x80 != 0,
                    metadata = null,
                )
            }
            val extensionLengthWords =
                ((data[offset + headerLength + 2].toInt() and 0xFF) shl 8) or
                    (data[offset + headerLength + 3].toInt() and 0xFF)
            metadata = parseExtensionMetadata(
                data = data,
                offset = extensionHeaderStart + 4,
                byteLength = extensionLengthWords * 4,
                packetEnd = offset + length,
            )
            headerLength += 4 + (extensionLengthWords * 4)
        }

        val payloadOffset = (offset + headerLength).coerceAtMost(offset + length)
        return PacketInfo(
            payloadOffset = payloadOffset,
            sequenceNumber = sequenceNumber(data, offset),
            marker = secondByte and 0x80 != 0,
            metadata = metadata,
        )
    }

    private fun parseExtensionMetadata(
        data: ByteArray,
        offset: Int,
        byteLength: Int,
        packetEnd: Int,
    ): LiveViewMetadata? {
        var cursor = offset
        val end = minOf(offset + byteLength, packetEnd)
        while (cursor + 4 <= end) {
            val type = unsignedShort(data, cursor)
            val lenWords = unsignedShort(data, cursor + 2)
            cursor += 4
            val valueLength = lenWords * 4
            if (cursor + valueLength > end) {
                break
            }
            if (type == 9 && lenWords >= 3) {
                return LiveViewMetadata(
                    apertureCurrentTenths = signedInt(data, cursor + 8),
                    apertureMaxTenths = signedInt(data, cursor),
                    apertureMinTenths = signedInt(data, cursor + 4),
                )
            }
            cursor += valueLength
        }
        return null
    }

    private fun unsignedShort(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private fun signedInt(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun sequenceNumber(data: ByteArray, offset: Int): Int {
        return ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
    }

    private data class PacketInfo(
        val payloadOffset: Int,
        val sequenceNumber: Int?,
        val marker: Boolean,
        val metadata: LiveViewMetadata?,
    )
}
