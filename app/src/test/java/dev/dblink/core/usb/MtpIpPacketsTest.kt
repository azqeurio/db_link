package dev.dblink.core.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MtpIpPacketsTest {
    @Test
    fun `encodes operation request with command params`() {
        val command = PtpContainer.command(
            PtpConstants.Op.GetObjectHandles,
            7,
            -1,
            0,
            -1,
        )

        val packet = MtpIpPackets.buildOperationRequest(command)
        val payload = MtpIpPackets.payload(packet)
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(MtpIpPackets.TYPE_OPERATION_REQUEST, MtpIpPackets.type(packet))
        assertEquals(MtpIpPackets.DATA_PHASE_IN_OR_NONE, buffer.int)
        assertEquals(PtpConstants.Op.GetObjectHandles, buffer.short.toInt() and 0xFFFF)
        assertEquals(7, buffer.int)
        assertEquals(-1, buffer.int)
        assertEquals(0, buffer.int)
        assertEquals(-1, buffer.int)
    }

    @Test
    fun `decodes operation response and event packets`() {
        val response = packet(
            type = MtpIpPackets.TYPE_OPERATION_RESPONSE,
            payload = ByteBuffer.allocate(10)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(PtpConstants.Resp.OK.toShort())
                .putInt(9)
                .putInt(123)
                .array(),
        )
        val event = packet(
            type = MtpIpPackets.TYPE_EVENT,
            payload = ByteBuffer.allocate(10)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(PtpConstants.Evt.ObjectAdded.toShort())
                .putInt(11)
                .putInt(0xABC)
                .array(),
        )

        val parsedResponse = MtpIpPackets.parseOperationResponse(response)
        val parsedEvent = MtpIpPackets.parseEvent(event)

        assertEquals(PtpConstants.CONTAINER_TYPE_RESPONSE, parsedResponse?.type)
        assertEquals(PtpConstants.Resp.OK, parsedResponse?.code)
        assertEquals(9, parsedResponse?.transactionId)
        assertEquals(123, parsedResponse?.param(0))
        assertEquals(PtpConstants.CONTAINER_TYPE_EVENT, parsedEvent?.type)
        assertEquals(PtpConstants.Evt.ObjectAdded, parsedEvent?.code)
        assertEquals(0xABC, parsedEvent?.param(0))
    }

    @Test
    fun `decodes start data and end data packets`() {
        val start = MtpIpPackets.buildStartDataPacket(transactionId = 5, totalDataLength = 12)
        val end = MtpIpPackets.buildEndDataPacket(transactionId = 5, data = byteArrayOf(1, 2, 3))

        assertEquals(MtpIpPackets.TYPE_START_DATA, MtpIpPackets.type(start))
        assertEquals(5, MtpIpPackets.parseStartDataTransactionId(start))
        assertEquals(12L, MtpIpPackets.parseStartDataLength(start))

        val parsed = MtpIpPackets.parseDataPayload(end)
        assertEquals(5, parsed?.first)
        assertArrayEquals(byteArrayOf(1, 2, 3), parsed?.second)
    }

    @Test
    fun `decodes init command ack responder name`() {
        val payload = ByteBuffer.allocate(4 + 16 + "OM-1".toByteArray(Charsets.UTF_16LE).size + 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(42)
            .put(ByteArray(16))
            .put("OM-1".toByteArray(Charsets.UTF_16LE))
            .put(byteArrayOf(0, 0))
            .array()
        val ack = packet(
            type = MtpIpPackets.TYPE_INIT_COMMAND_ACK,
            payload = payload,
        )

        assertEquals(42, MtpIpPackets.parseInitCommandAckConnectionNumber(ack))
        assertEquals("OM-1", MtpIpPackets.parseInitCommandAckResponderName(ack))
    }

    @Test
    fun `rejects malformed packets`() {
        assertNull(MtpIpPackets.type(byteArrayOf(1, 2, 3)))
        assertNull(MtpIpPackets.parseOperationResponse(byteArrayOf(1, 2, 3)))
        assertTrue(MtpIpPackets.payload(byteArrayOf(1, 2, 3)).isEmpty())
    }

    private fun packet(type: Int, payload: ByteArray): ByteArray {
        return ByteBuffer.allocate(8 + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(8 + payload.size)
            .putInt(type)
            .put(payload)
            .array()
    }
}
