package dev.dblink.core.usb

/**
 * Common PTP transport used by both USB bulk endpoints and MTP/IP sockets.
 *
 * The session layer only deals in PTP containers. Transport-specific setup
 * stays below this boundary so object listing, thumbnails, imports, and events
 * share one implementation.
 */
interface PtpTransport : AutoCloseable {
    val isClosed: Boolean

    fun sendCommand(container: PtpContainer): Boolean

    fun sendData(container: PtpContainer): Boolean

    fun receiveResponse(timeoutMs: Int = 5_000): PtpContainer?

    fun receiveDataAndResponse(timeoutMs: Int = 30_000): Pair<ByteArray, PtpContainer>?

    fun receiveDataAndResponseDetailed(timeoutMs: Int = 30_000): PtpDataAndResponseResult

    fun pollEvent(timeoutMs: Int = 100): PtpContainer?

    fun pollExtendedEvent(timeoutMs: Int = 100): PtpContainer? = pollEvent(timeoutMs)

    override fun close()
}
