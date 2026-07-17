package dev.dblink.core.usb

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object MtpIpPackets {
    const val DEFAULT_PORT = 15740

    const val TYPE_INIT_COMMAND_REQUEST = 1
    const val TYPE_INIT_COMMAND_ACK = 2
    const val TYPE_INIT_EVENT_REQUEST = 3
    const val TYPE_INIT_EVENT_ACK = 4
    const val TYPE_INIT_FAIL = 5
    const val TYPE_OPERATION_REQUEST = 6
    const val TYPE_OPERATION_RESPONSE = 7
    const val TYPE_EVENT = 8
    const val TYPE_START_DATA = 9
    const val TYPE_DATA = 10
    const val TYPE_CANCEL = 11
    const val TYPE_END_DATA = 12
    const val TYPE_PING = 13
    const val TYPE_PONG = 14

    const val DATA_PHASE_IN_OR_NONE = 1
    const val DATA_PHASE_OUT = 2
    const val PROTOCOL_VERSION = 0x00010000

    private const val HEADER_SIZE = 8

    fun type(rawPacket: ByteArray): Int? {
        if (rawPacket.size < HEADER_SIZE) return null
        return ByteBuffer.wrap(rawPacket, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    fun payload(rawPacket: ByteArray): ByteArray {
        if (rawPacket.size <= HEADER_SIZE) return ByteArray(0)
        return rawPacket.copyOfRange(HEADER_SIZE, rawPacket.size)
    }

    fun buildInitCommandRequest(
        initiatorGuid: UUID,
        hostName: String,
        protocolVersion: Int = PROTOCOL_VERSION,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(uuidBytes(initiatorGuid))
        output.write(hostName.toUtf16LeNullTerminated())
        output.write(uint32(protocolVersion))
        return build(TYPE_INIT_COMMAND_REQUEST, output.toByteArray())
    }

    fun parseInitCommandAckConnectionNumber(rawPacket: ByteArray): Int? {
        if (type(rawPacket) != TYPE_INIT_COMMAND_ACK) return null
        val data = payload(rawPacket)
        if (data.size < 4) return null
        return ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    fun parseInitCommandAckResponderName(rawPacket: ByteArray): String? {
        if (type(rawPacket) != TYPE_INIT_COMMAND_ACK) return null
        val data = payload(rawPacket)
        if (data.size <= 20) return null
        return data.decodeUtf16LeNullTerminated(offset = 20)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    fun buildInitEventRequest(connectionNumber: Int): ByteArray {
        return build(TYPE_INIT_EVENT_REQUEST, uint32(connectionNumber))
    }

    fun buildOperationRequest(
        container: PtpContainer,
        dataPhaseInfo: Int = DATA_PHASE_IN_OR_NONE,
    ): ByteArray {
        val params = container.params()
        val payload = ByteBuffer.allocate(4 + 2 + 4 + params.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(dataPhaseInfo)
            .putShort(container.code.toShort())
            .putInt(container.transactionId)
            .apply { params.forEach { putInt(it) } }
            .array()
        return build(TYPE_OPERATION_REQUEST, payload)
    }

    fun buildStartDataPacket(transactionId: Int, totalDataLength: Long): ByteArray {
        val payload = ByteBuffer.allocate(12)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(transactionId)
            .putLong(totalDataLength)
            .array()
        return build(TYPE_START_DATA, payload)
    }

    fun buildDataPacket(transactionId: Int, data: ByteArray): ByteArray {
        return build(TYPE_DATA, uint32(transactionId) + data)
    }

    fun buildEndDataPacket(transactionId: Int, data: ByteArray): ByteArray {
        return build(TYPE_END_DATA, uint32(transactionId) + data)
    }

    fun buildCancelPacket(transactionId: Int): ByteArray {
        return build(TYPE_CANCEL, uint32(transactionId))
    }

    fun buildPongPacket(): ByteArray = build(TYPE_PONG, ByteArray(0))

    fun parseOperationResponse(rawPacket: ByteArray): PtpContainer? {
        if (type(rawPacket) != TYPE_OPERATION_RESPONSE) return null
        return parseCodeTransactionParams(
            rawPacket = rawPacket,
            containerType = PtpConstants.CONTAINER_TYPE_RESPONSE,
        )
    }

    fun parseEvent(rawPacket: ByteArray): PtpContainer? {
        if (type(rawPacket) != TYPE_EVENT) return null
        return parseCodeTransactionParams(
            rawPacket = rawPacket,
            containerType = PtpConstants.CONTAINER_TYPE_EVENT,
        )
    }

    fun parseStartDataTransactionId(rawPacket: ByteArray): Int? {
        if (type(rawPacket) != TYPE_START_DATA) return null
        val data = payload(rawPacket)
        if (data.size < 4) return null
        return ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    fun parseStartDataLength(rawPacket: ByteArray): Long? {
        if (type(rawPacket) != TYPE_START_DATA) return null
        val data = payload(rawPacket)
        if (data.size < 12) return null
        return ByteBuffer.wrap(data, 4, 8).order(ByteOrder.LITTLE_ENDIAN).long
    }

    fun parseDataPayload(rawPacket: ByteArray): Pair<Int, ByteArray>? {
        val packetType = type(rawPacket)
        if (packetType != TYPE_DATA && packetType != TYPE_END_DATA) return null
        val data = payload(rawPacket)
        if (data.size < 4) return null
        val transactionId = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return transactionId to data.copyOfRange(4, data.size)
    }

    private fun parseCodeTransactionParams(
        rawPacket: ByteArray,
        containerType: Int,
    ): PtpContainer? {
        val data = payload(rawPacket)
        if (data.size < 6) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val code = buffer.short.toInt() and 0xFFFF
        val transactionId = buffer.int
        val params = ByteArray(buffer.remaining())
        if (params.isNotEmpty()) {
            buffer.get(params)
        }
        return PtpContainer(
            type = containerType,
            code = code,
            transactionId = transactionId,
            payload = params,
        )
    }

    private fun build(type: Int, payload: ByteArray): ByteArray {
        val packetLength = HEADER_SIZE + payload.size
        return ByteBuffer.allocate(packetLength)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(packetLength)
            .putInt(type)
            .put(payload)
            .array()
    }

    private fun uint32(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    private fun uuidBytes(uuid: UUID): ByteArray {
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }

    private fun String.toUtf16LeNullTerminated(): ByteArray {
        return toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)
    }

    private fun ByteArray.decodeUtf16LeNullTerminated(offset: Int): String? {
        if (offset !in indices || size - offset < 2) return null
        var end = offset
        while (end + 1 < size) {
            if (this[end] == 0.toByte() && this[end + 1] == 0.toByte()) {
                break
            }
            end += 2
        }
        if (end <= offset) return null
        return copyOfRange(offset, end).toString(Charsets.UTF_16LE)
    }
}
