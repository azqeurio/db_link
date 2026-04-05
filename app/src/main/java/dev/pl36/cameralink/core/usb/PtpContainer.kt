package dev.pl36.cameralink.core.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Represents a PTP USB container (command, data, response, or event).
 *
 * PTP container format (all little-endian):
 *   [0..3]   Container Length (4 bytes, uint32)
 *   [4..5]   Container Type  (2 bytes, uint16)
 *   [6..7]   Code            (2 bytes, uint16)
 *   [8..11]  Transaction ID  (4 bytes, uint32)
 *   [12..]   Payload / Parameters (variable length)
 */
data class PtpContainer(
    val type: Int,
    val code: Int,
    val transactionId: Int,
    val payload: ByteArray = ByteArray(0),
) {
    /** Total container length in bytes. */
    val length: Int get() = PtpConstants.CONTAINER_HEADER_SIZE + payload.size

    /** Serialize this container to a byte array for USB bulk transfer. */
    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(length)
        buffer.putShort(type.toShort())
        buffer.putShort(code.toShort())
        buffer.putInt(transactionId)
        if (payload.isNotEmpty()) {
            buffer.put(payload)
        }
        return buffer.array()
    }

    /** Extract up to 5 parameters (each 4 bytes) from the payload. */
    fun params(): List<Int> {
        if (payload.isEmpty()) return emptyList()
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val count = payload.size / 4
        return (0 until minOf(count, 5)).map { buf.getInt() }
    }

    /** Get parameter at index (0-based). Returns null if not present. */
    fun param(index: Int): Int? {
        val offset = index * 4
        if (offset + 4 > payload.size) return null
        return ByteBuffer.wrap(payload, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PtpContainer) return false
        return type == other.type && code == other.code &&
            transactionId == other.transactionId && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + code
        result = 31 * result + transactionId
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String {
        val typeName = when (type) {
            PtpConstants.CONTAINER_TYPE_COMMAND -> "CMD"
            PtpConstants.CONTAINER_TYPE_DATA -> "DATA"
            PtpConstants.CONTAINER_TYPE_RESPONSE -> "RESP"
            PtpConstants.CONTAINER_TYPE_EVENT -> "EVT"
            else -> "T$type"
        }
        val codeName = when (type) {
            PtpConstants.CONTAINER_TYPE_COMMAND, PtpConstants.CONTAINER_TYPE_DATA -> PtpConstants.opName(code)
            PtpConstants.CONTAINER_TYPE_RESPONSE -> PtpConstants.Resp.name(code)
            else -> "0x${code.toString(16)}"
        }
        val params = params()
        val paramStr = if (params.isNotEmpty()) " params=${params.map { "0x${it.toString(16)}" }}" else ""
        return "PTP[$typeName $codeName txn=$transactionId$paramStr len=$length]"
    }

    companion object {
        /**
         * Parse a PTP container from raw bytes received via USB bulk transfer.
         * Returns null if the data is too short or malformed.
         */
        fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size): PtpContainer? {
            val available = length - offset
            if (available < PtpConstants.CONTAINER_HEADER_SIZE) return null

            val buf = ByteBuffer.wrap(data, offset, available).order(ByteOrder.LITTLE_ENDIAN)
            val containerLength = buf.getInt()
            if (containerLength < PtpConstants.CONTAINER_HEADER_SIZE) return null

            val containerType = buf.getShort().toInt() and 0xFFFF
            val code = buf.getShort().toInt() and 0xFFFF
            val transactionId = buf.getInt()

            val payloadSize = minOf(containerLength - PtpConstants.CONTAINER_HEADER_SIZE, available - PtpConstants.CONTAINER_HEADER_SIZE)
            val payload = if (payloadSize > 0) {
                ByteArray(payloadSize).also { buf.get(it) }
            } else {
                ByteArray(0)
            }

            return PtpContainer(
                type = containerType,
                code = code,
                transactionId = transactionId,
                payload = payload,
            )
        }

        /**
         * Build a command container with up to 5 integer parameters.
         */
        fun command(
            code: Int,
            transactionId: Int,
            vararg params: Int,
        ): PtpContainer {
            val payload = if (params.isNotEmpty()) {
                val buf = ByteBuffer.allocate(params.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                params.forEach { buf.putInt(it) }
                buf.array()
            } else {
                ByteArray(0)
            }
            return PtpContainer(
                type = PtpConstants.CONTAINER_TYPE_COMMAND,
                code = code,
                transactionId = transactionId,
                payload = payload,
            )
        }

        /**
         * Build a data container for sending data to the camera.
         */
        fun data(
            code: Int,
            transactionId: Int,
            payload: ByteArray,
        ): PtpContainer = PtpContainer(
            type = PtpConstants.CONTAINER_TYPE_DATA,
            code = code,
            transactionId = transactionId,
            payload = payload,
        )
    }
}
