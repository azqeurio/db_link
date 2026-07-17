package dev.dblink.core.usb

import dev.dblink.core.logging.D
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID

data class MtpIpProbeResult(
    val connectionNumber: Int,
    val responderName: String?,
)

class MtpIpTransport private constructor(
    private val commandSocket: Socket,
    private val eventSocket: Socket?,
    val host: String,
    val port: Int,
    val connectionNumber: Int,
) : PtpTransport {
    companion object {
        private const val MAX_PACKET_BYTES = 512 * 1024 * 1024
        private const val DATA_OUT_CHUNK_BYTES = 256 * 1024

        fun connect(
            host: String,
            port: Int = MtpIpPackets.DEFAULT_PORT,
            timeoutMs: Int = 1_500,
            friendlyName: String = "DbLink Android",
        ): Result<MtpIpTransport> = runCatching {
            val commandSocket = openSocket(host, port, timeoutMs)
            try {
                commandSocket.writePacket(
                    MtpIpPackets.buildInitCommandRequest(
                        initiatorGuid = UUID.randomUUID(),
                        hostName = friendlyName,
                    ),
                )
                val ack = commandSocket.readPacket(timeoutMs)
                    ?: throw SocketTimeoutException("No MTP/IP InitCommandAck from $host:$port")
                if (MtpIpPackets.type(ack) == MtpIpPackets.TYPE_INIT_FAIL) {
                    throw IllegalStateException("MTP/IP InitCommandRequest was rejected by $host:$port")
                }
                val connectionNumber = MtpIpPackets.parseInitCommandAckConnectionNumber(ack)
                    ?: throw IllegalStateException("Malformed MTP/IP InitCommandAck from $host:$port")

                val eventSocket = runCatching {
                    val socket = openSocket(host, port, timeoutMs)
                    socket.writePacket(MtpIpPackets.buildInitEventRequest(connectionNumber))
                    val eventAck = socket.readPacket(timeoutMs)
                    if (MtpIpPackets.type(eventAck ?: ByteArray(0)) != MtpIpPackets.TYPE_INIT_EVENT_ACK) {
                        throw IllegalStateException("No MTP/IP InitEventAck from $host:$port")
                    }
                    socket
                }.onFailure { throwable ->
                    D.wifi("MTP/IP event socket unavailable at $host:$port (${throwable.message}); polling fallback remains enabled")
                }.getOrNull()

                D.wifi("MTP/IP connected host=$host port=$port connection=$connectionNumber event=${eventSocket != null}")
                MtpIpTransport(
                    commandSocket = commandSocket,
                    eventSocket = eventSocket,
                    host = host,
                    port = port,
                    connectionNumber = connectionNumber,
                )
            } catch (throwable: Throwable) {
                runCatching { commandSocket.close() }
                throw throwable
            }
        }

        fun probe(
            host: String,
            port: Int = MtpIpPackets.DEFAULT_PORT,
            timeoutMs: Int = 800,
        ): Boolean = probeInfo(host = host, port = port, timeoutMs = timeoutMs) != null

        fun probeInfo(
            host: String,
            port: Int = MtpIpPackets.DEFAULT_PORT,
            timeoutMs: Int = 800,
        ): MtpIpProbeResult? {
            val commandSocket = runCatching { openSocket(host, port, timeoutMs) }.getOrNull() ?: return null
            return runCatching {
                commandSocket.writePacket(
                    MtpIpPackets.buildInitCommandRequest(
                        initiatorGuid = UUID.randomUUID(),
                        hostName = "DbLink Android",
                    ),
                )
                val ack = commandSocket.readPacket(timeoutMs) ?: return@runCatching null
                if (MtpIpPackets.type(ack) == MtpIpPackets.TYPE_INIT_FAIL) {
                    return@runCatching null
                }
                val connectionNumber = MtpIpPackets.parseInitCommandAckConnectionNumber(ack)
                    ?: return@runCatching null
                MtpIpProbeResult(
                    connectionNumber = connectionNumber,
                    responderName = MtpIpPackets.parseInitCommandAckResponderName(ack),
                )
            }.getOrNull().also {
                runCatching { commandSocket.close() }
            }
        }

        private fun openSocket(host: String, port: Int, timeoutMs: Int): Socket {
            return Socket().apply {
                tcpNoDelay = true
                keepAlive = true
                soTimeout = timeoutMs
                connect(InetSocketAddress(host, port), timeoutMs)
            }
        }

        private fun Socket.writePacket(packet: ByteArray) {
            getOutputStream().apply {
                write(packet)
                flush()
            }
        }

        private fun Socket.readPacket(timeoutMs: Int): ByteArray? {
            soTimeout = timeoutMs
            val input = getInputStream()
            val header = ByteArray(8)
            try {
                input.readFullyOrNull(header) ?: return null
            } catch (_: SocketTimeoutException) {
                return null
            }
            val packetLength = java.nio.ByteBuffer.wrap(header, 0, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .int
            if (packetLength < 8 || packetLength > MAX_PACKET_BYTES) {
                throw IllegalStateException("Invalid MTP/IP packet length $packetLength")
            }
            val packet = ByteArray(packetLength)
            System.arraycopy(header, 0, packet, 0, header.size)
            if (packetLength > header.size) {
                input.readFullyOrNull(packet, header.size, packetLength - header.size)
                    ?: throw EOFException("Socket closed during MTP/IP packet read")
            }
            return packet
        }

        private fun java.io.InputStream.readFullyOrNull(
            target: ByteArray,
            offset: Int = 0,
            length: Int = target.size,
        ): ByteArray? {
            var total = 0
            while (total < length) {
                val read = read(target, offset + total, length - total)
                if (read < 0) {
                    return null
                }
                total += read
            }
            return target
        }
    }

    @Volatile
    override var isClosed: Boolean = false
        private set

    private val commandWriteLock = Any()
    private val eventWriteLock = Any()

    override fun sendCommand(container: PtpContainer): Boolean {
        if (isClosed) return false
        val dataPhaseInfo = dataPhaseInfoFor(container.code)
        return runCatching {
            synchronized(commandWriteLock) {
                commandSocket.writePacket(
                    MtpIpPackets.buildOperationRequest(
                        container = container,
                        dataPhaseInfo = dataPhaseInfo,
                    ),
                )
            }
            D.ptp(">> MTP/IP ${container} dataPhase=$dataPhaseInfo host=$host")
            true
        }.onFailure { throwable ->
            D.err("PTP", "MTP/IP command send failed for ${PtpConstants.opName(container.code)}", throwable)
        }.getOrDefault(false)
    }

    override fun sendData(container: PtpContainer): Boolean {
        if (isClosed) return false
        return runCatching {
            synchronized(commandWriteLock) {
                commandSocket.writePacket(
                    MtpIpPackets.buildStartDataPacket(
                        transactionId = container.transactionId,
                        totalDataLength = container.payload.size.toLong(),
                    ),
                )
                var offset = 0
                while (offset + DATA_OUT_CHUNK_BYTES < container.payload.size) {
                    val chunk = container.payload.copyOfRange(offset, offset + DATA_OUT_CHUNK_BYTES)
                    commandSocket.writePacket(MtpIpPackets.buildDataPacket(container.transactionId, chunk))
                    offset += chunk.size
                }
                val finalChunk = container.payload.copyOfRange(offset, container.payload.size)
                commandSocket.writePacket(MtpIpPackets.buildEndDataPacket(container.transactionId, finalChunk))
            }
            true
        }.onFailure { throwable ->
            D.err("PTP", "MTP/IP data send failed for ${PtpConstants.opName(container.code)}", throwable)
        }.getOrDefault(false)
    }

    override fun receiveResponse(timeoutMs: Int): PtpContainer? {
        if (isClosed) return null
        while (!isClosed) {
            val packet = commandSocket.readPacket(timeoutMs) ?: return null
            when (MtpIpPackets.type(packet)) {
                MtpIpPackets.TYPE_OPERATION_RESPONSE -> {
                    val response = MtpIpPackets.parseOperationResponse(packet)
                    if (response != null) {
                        D.ptp("<< MTP/IP $response")
                    }
                    return response
                }
                MtpIpPackets.TYPE_EVENT -> D.ptp("<< MTP/IP command-channel event ${MtpIpPackets.parseEvent(packet)}")
                MtpIpPackets.TYPE_PING -> sendPong(commandSocket, commandWriteLock)
                else -> D.ptp("<< MTP/IP unexpected packet type=${MtpIpPackets.type(packet)} while waiting for response")
            }
        }
        return null
    }

    override fun receiveDataAndResponse(timeoutMs: Int): Pair<ByteArray, PtpContainer>? {
        return when (val result = receiveDataAndResponseDetailed(timeoutMs)) {
            is PtpDataAndResponseResult.Success -> result.data to result.response
            is PtpDataAndResponseResult.Failure -> null
        }
    }

    override fun receiveDataAndResponseDetailed(timeoutMs: Int): PtpDataAndResponseResult {
        if (isClosed) {
            return PtpDataAndResponseResult.Failure(
                phase = PtpInitFailurePhase.DataReceive,
                detail = "transport is closed",
            )
        }
        val data = ByteArrayOutputStream()
        while (!isClosed) {
            val packet = commandSocket.readPacket(timeoutMs)
                ?: return PtpDataAndResponseResult.Failure(
                    phase = PtpInitFailurePhase.DataReceive,
                    detail = "MTP/IP data receive timed out",
                )
            when (MtpIpPackets.type(packet)) {
                MtpIpPackets.TYPE_OPERATION_RESPONSE -> {
                    val response = MtpIpPackets.parseOperationResponse(packet)
                        ?: return malformedResponseFailure()
                    return PtpDataAndResponseResult.Success(data.toByteArray(), response)
                }
                MtpIpPackets.TYPE_START_DATA -> {
                    MtpIpPackets.parseStartDataLength(packet)?.let { length ->
                        D.ptp("<< MTP/IP START_DATA length=$length txn=${MtpIpPackets.parseStartDataTransactionId(packet)}")
                    }
                    return receiveRemainingDataAndResponse(data, timeoutMs)
                }
                MtpIpPackets.TYPE_DATA,
                MtpIpPackets.TYPE_END_DATA,
                -> {
                    MtpIpPackets.parseDataPayload(packet)?.second?.let(data::write)
                    if (MtpIpPackets.type(packet) == MtpIpPackets.TYPE_END_DATA) {
                        val response = receiveResponse(timeoutMs)
                            ?: return PtpDataAndResponseResult.Failure(
                                phase = PtpInitFailurePhase.ResponseReceive,
                                detail = "MTP/IP response receive failed after END_DATA",
                            )
                        return PtpDataAndResponseResult.Success(data.toByteArray(), response)
                    }
                }
                MtpIpPackets.TYPE_EVENT -> D.ptp("<< MTP/IP command-channel event ${MtpIpPackets.parseEvent(packet)}")
                MtpIpPackets.TYPE_PING -> sendPong(commandSocket, commandWriteLock)
                else -> D.ptp("<< MTP/IP unexpected packet type=${MtpIpPackets.type(packet)} while waiting for data")
            }
        }
        return PtpDataAndResponseResult.Failure(
            phase = PtpInitFailurePhase.DataReceive,
            detail = "MTP/IP transport closed during data receive",
        )
    }

    private fun receiveRemainingDataAndResponse(
        data: ByteArrayOutputStream,
        timeoutMs: Int,
    ): PtpDataAndResponseResult {
        while (!isClosed) {
            val packet = commandSocket.readPacket(timeoutMs)
                ?: return PtpDataAndResponseResult.Failure(
                    phase = PtpInitFailurePhase.DataReceive,
                    detail = "MTP/IP data receive timed out after START_DATA",
                )
            when (MtpIpPackets.type(packet)) {
                MtpIpPackets.TYPE_DATA,
                MtpIpPackets.TYPE_END_DATA,
                -> {
                    MtpIpPackets.parseDataPayload(packet)?.second?.let(data::write)
                    if (MtpIpPackets.type(packet) == MtpIpPackets.TYPE_END_DATA) {
                        D.ptp("<< MTP/IP DATA ${data.size()} bytes received")
                        val response = receiveResponse(timeoutMs)
                            ?: return PtpDataAndResponseResult.Failure(
                                phase = PtpInitFailurePhase.ResponseReceive,
                                detail = "MTP/IP response receive failed after END_DATA",
                            )
                        return PtpDataAndResponseResult.Success(data.toByteArray(), response)
                    }
                }
                MtpIpPackets.TYPE_OPERATION_RESPONSE -> {
                    val response = MtpIpPackets.parseOperationResponse(packet)
                        ?: return malformedResponseFailure()
                    return PtpDataAndResponseResult.Success(data.toByteArray(), response)
                }
                MtpIpPackets.TYPE_PING -> sendPong(commandSocket, commandWriteLock)
                else -> D.ptp("<< MTP/IP unexpected data packet type=${MtpIpPackets.type(packet)}")
            }
        }
        return PtpDataAndResponseResult.Failure(
            phase = PtpInitFailurePhase.DataReceive,
            detail = "MTP/IP transport closed during data receive",
        )
    }

    override fun pollEvent(timeoutMs: Int): PtpContainer? {
        if (isClosed) return null
        val socket = eventSocket ?: return null
        val packet = socket.readPacket(timeoutMs) ?: return null
        return when (MtpIpPackets.type(packet)) {
            MtpIpPackets.TYPE_EVENT -> MtpIpPackets.parseEvent(packet)
            MtpIpPackets.TYPE_PING -> {
                sendPong(socket, eventWriteLock)
                null
            }
            else -> null
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        runCatching { eventSocket?.close() }
        runCatching { commandSocket.close() }
        D.wifi("MTP/IP transport closed host=$host port=$port")
    }

    private fun sendPong(socket: Socket, lock: Any) {
        runCatching {
            synchronized(lock) {
                socket.writePacket(MtpIpPackets.buildPongPacket())
            }
        }
    }

    private fun malformedResponseFailure(): PtpDataAndResponseResult.Failure {
        return PtpDataAndResponseResult.Failure(
            phase = PtpInitFailurePhase.ResponseReceive,
            detail = "MTP/IP response packet was malformed",
        )
    }

    private fun dataPhaseInfoFor(operationCode: Int): Int {
        return when (operationCode) {
            PtpConstants.Op.SetDevicePropValue,
            PtpConstants.Op.SetObjectPropValue,
            PtpConstants.Op.SetObjectPropList,
            PtpConstants.OmdOp.SetProperties,
            -> MtpIpPackets.DATA_PHASE_OUT
            else -> MtpIpPackets.DATA_PHASE_IN_OR_NONE
        }
    }
}
