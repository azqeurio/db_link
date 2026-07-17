package dev.dblink.core.usb

import dev.dblink.core.logging.D
import dev.dblink.core.protocol.CameraImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.OutputStream
import java.util.Locale

data class MtpIpCameraEndpoint(
    val host: String,
    val port: Int = MtpIpPackets.DEFAULT_PORT,
    val label: String = "MTP/IP",
)

data class MtpIpCameraImage(
    val image: CameraImage,
    val handle: Int,
)

class MtpIpCameraClient {
    suspend fun discover(
        hosts: List<String>,
        port: Int = MtpIpPackets.DEFAULT_PORT,
        timeoutMs: Int = 850,
        batchSize: Int = 24,
        totalTimeoutMs: Long = 20_000L,
    ): MtpIpCameraEndpoint? = withContext(Dispatchers.IO) {
        if (hosts.isEmpty()) return@withContext null
        val uniqueHosts = hosts.distinct()
        D.wifi(
            "MTP/IP discovery start hosts=${uniqueHosts.size} port=$port " +
                "timeout=${timeoutMs}ms batch=$batchSize total=${totalTimeoutMs}ms",
        )
        withTimeoutOrNull(totalTimeoutMs) {
            uniqueHosts.chunked(batchSize).forEach { batch ->
                val found = coroutineScope {
                    batch.map { host ->
                        async(Dispatchers.IO) {
                            MtpIpTransport.probeInfo(host = host, port = port, timeoutMs = timeoutMs)
                                ?.let { probe ->
                                    MtpIpCameraEndpoint(
                                        host = host,
                                        port = port,
                                        label = probe.responderName?.takeIf { it.isNotBlank() } ?: "MTP/IP",
                                    )
                                }
                        }
                    }.awaitAll().firstOrNull()
                }
                if (found != null) {
                    D.wifi("MTP/IP discovery found ${found.host}:${found.port} label=${found.label}")
                    return@withTimeoutOrNull found
                }
            }
            D.wifi("MTP/IP discovery completed without match")
            null
        }
    }

    suspend fun loadImages(endpoint: MtpIpCameraEndpoint): Result<List<MtpIpCameraImage>> = withContext(Dispatchers.IO) {
        withSession(endpoint) { session ->
            val handles = loadObjectHandles(session)
            val ratingByHandle = readNumericObjectPropertyMap(session, PtpConstants.ObjectProp.Rating)
            val keywordsByHandle = readTextObjectPropertyMap(session, PtpConstants.ObjectProp.Keywords)
            val images = handles.mapNotNull { handle ->
                val info = session.getObjectInfo(handle).getOrElse { throwable ->
                    D.err("TRANSFER", "MTP/IP GetObjectInfo failed for handle=0x${handle.toString(16)}", throwable)
                    return@mapNotNull null
                }
                if (!isImportableTransferObject(info)) {
                    return@mapNotNull null
                }
                val keywords = keywordsByHandle[handle]?.takeIf { it.isNotBlank() } ?: info.keywords
                val rating = ratingByHandle[handle]?.toInt()?.coerceIn(0, 5)
                    ?: PtpObjectInfo.parseRatingMetadata(keywords)
                    ?: info.rating
                val selected = info.isCameraSelected || PtpObjectInfo.parseCameraSelectionMetadata(keywords)
                val image = CameraImage(
                    directory = mtpDirectory(endpoint, info),
                    fileName = info.filename.ifBlank { "HANDLE_${handle.toUIntLabel()}" },
                    fileSize = info.compressedSize.toLong() and 0xffffffffL,
                    attribute = 0,
                    width = info.imagePixWidth,
                    height = info.imagePixHeight,
                    rating = rating,
                    isCameraSelected = selected,
                    mtpIpHost = endpoint.host,
                    mtpIpPort = endpoint.port,
                    mtpIpHandle = handle,
                )
                MtpIpCameraImage(image = image, handle = handle)
            }
            images.sortedWith(
                compareByDescending<MtpIpCameraImage> { it.image.captureDateKey }
                    .thenByDescending { it.image.fileName },
            )
        }
    }

    suspend fun loadThumbnail(endpoint: MtpIpCameraEndpoint, handle: Int): Result<ByteArray> = withContext(Dispatchers.IO) {
        withSession(endpoint) { session ->
            session.getThumb(handle, timeoutMs = 8_000).getOrThrow()
        }
    }

    suspend fun downloadObjectTo(
        endpoint: MtpIpCameraEndpoint,
        handle: Int,
        output: OutputStream,
    ): Result<Long> = withContext(Dispatchers.IO) {
        withSession(endpoint) { session ->
            val data = session.getObject(handle).getOrThrow()
            output.write(data)
            data.size.toLong()
        }
    }

    suspend fun waitForNewObjectEvent(
        endpoint: MtpIpCameraEndpoint,
        timeoutMs: Int,
    ): Result<Int?> = withContext(Dispatchers.IO) {
        withSession(endpoint) { session ->
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(100L).toInt()
                val event = session.pollEvent(timeoutMs = minOf(1_000, remaining)) ?: continue
                val handle = objectHandleFromEvent(event)
                if (handle != null) {
                    D.transfer("MTP/IP ObjectAdded event code=0x${event.code.toString(16)} handle=0x${handle.toString(16)}")
                    return@withSession handle
                }
                D.ptp("MTP/IP ignored event code=0x${event.code.toString(16)} params=${event.params()}")
            }
            null
        }
    }

    private fun <T> withSession(
        endpoint: MtpIpCameraEndpoint,
        block: (PtpSession) -> T,
    ): Result<T> = runCatching {
        val transport = MtpIpTransport.connect(
            host = endpoint.host,
            port = endpoint.port,
            timeoutMs = 2_000,
        ).getOrThrow()
        var session: PtpSession? = null
        try {
            val activeSession = PtpSession(transport)
            session = activeSession
            activeSession.getDeviceInfo(responseTimeoutMs = 3_500).getOrThrow()
            activeSession.openSession().getOrThrow()
            block(activeSession)
        } finally {
            runCatching { session?.closeSession() }
            transport.close()
        }
    }

    private fun loadObjectHandles(session: PtpSession): List<Int> {
        val allHandles = session.getObjectHandles(storageId = -1, formatCode = 0, parent = -1).getOrElse { throwable ->
            D.err("TRANSFER", "MTP/IP GetObjectHandles(all) failed; trying per-storage fallback", throwable)
            emptyList()
        }
        if (allHandles.isNotEmpty()) {
            return allHandles.distinct()
        }
        val storageIds = session.getStorageIDs().getOrElse { throwable ->
            D.err("TRANSFER", "MTP/IP GetStorageIDs failed", throwable)
            emptyList()
        }
        return storageIds.flatMap { storageId ->
            session.getObjectHandles(storageId = storageId, formatCode = 0, parent = -1).getOrElse { throwable ->
                D.err("TRANSFER", "MTP/IP GetObjectHandles(storage=0x${storageId.toString(16)}) failed", throwable)
                emptyList()
            }
        }.distinct()
    }

    private fun readNumericObjectPropertyMap(
        session: PtpSession,
        propertyCode: Int,
    ): Map<Int, Long> {
        return session.getObjectPropertyList(propertyCode).getOrElse { throwable ->
            D.transfer("MTP/IP object property 0x${propertyCode.toString(16)} unavailable (${throwable.message})")
            emptyList()
        }.mapNotNull { value ->
            value.numericValue?.let { value.handle to it }
        }.toMap()
    }

    private fun readTextObjectPropertyMap(
        session: PtpSession,
        propertyCode: Int,
    ): Map<Int, String> {
        return session.getObjectPropertyList(propertyCode).getOrElse { throwable ->
            D.transfer("MTP/IP object property 0x${propertyCode.toString(16)} unavailable (${throwable.message})")
            emptyList()
        }.mapNotNull { value ->
            value.textValue?.let { value.handle to it }
        }.toMap()
    }

    private fun isImportableTransferObject(info: PtpObjectInfo): Boolean {
        if (info.isDirectory) return false
        val name = info.filename.uppercase(Locale.US)
        return info.isJpeg ||
            info.isRaw ||
            name.endsWith(".MOV") ||
            name.endsWith(".MP4") ||
            name.endsWith(".AVI")
    }

    private fun mtpDirectory(endpoint: MtpIpCameraEndpoint, info: PtpObjectInfo): String {
        return "/MTPIP/${endpoint.host}/${info.storageId.toUIntLabel()}"
    }

    private fun objectHandleFromEvent(event: PtpContainer): Int? {
        val isObjectEvent = event.code == PtpConstants.Evt.ObjectAdded ||
            event.code == PtpConstants.Evt.RequestObjectTransfer ||
            event.code == PtpConstants.OlympusEvt.ObjectAdded ||
            event.code == PtpConstants.OlympusEvt.DirectStoreImage ||
            event.code == PtpConstants.OlympusEvt.ImageRecordFinish
        if (!isObjectEvent) {
            return null
        }
        return event.params().firstOrNull { it != 0 }
    }

    private fun Int.toUIntLabel(): String {
        return (toLong() and 0xffffffffL).toString(16)
    }
}
