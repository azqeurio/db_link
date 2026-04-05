package dev.pl36.cameralink.core.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Parsed PTP ObjectInfo dataset.
 *
 * Contains metadata about an image/file stored on the camera.
 */
data class PtpObjectInfo(
    val handle: Int,
    val storageId: Int,
    val objectFormat: Int,
    val protectionStatus: Int,
    val compressedSize: Int,
    val thumbFormat: Int,
    val thumbCompressedSize: Int,
    val thumbPixWidth: Int,
    val thumbPixHeight: Int,
    val imagePixWidth: Int,
    val imagePixHeight: Int,
    val imageBitDepth: Int,
    val parentObject: Int,
    val associationType: Int,
    val associationDesc: Int,
    val sequenceNumber: Int,
    val filename: String,
    val captureDate: String,
    val modificationDate: String,
    val keywords: String,
) {
    /** True if this is a JPEG file. */
    val isJpeg: Boolean get() = objectFormat == PtpConstants.Format.JPEG

    /** True if this is a RAW (ORF) file. OM-1 reports ORF as format 0x3800 (Undefined). */
    val isRaw: Boolean
        get() = objectFormat == PtpConstants.Format.ORF ||
            objectFormat == PtpConstants.Format.RAW ||
            (objectFormat == PtpConstants.Format.Undefined && isRawByExtension)

    /** True if filename has a known RAW extension (fallback for format=Undefined). */
    private val isRawByExtension: Boolean
        get() {
            val ext = filename.substringAfterLast('.', "").uppercase(Locale.US)
            return ext in RAW_EXTENSIONS
        }

    /** True if this is a directory/association. */
    val isDirectory: Boolean get() = objectFormat == PtpConstants.Format.Association

    /** True if the camera reported a download-ready thumbnail. */
    val hasThumbnail: Boolean
        get() = thumbCompressedSize > 0

    /** True if this object is a downloadable still image for USB import. */
    val isImportableImage: Boolean
        get() = !isDirectory && (isJpeg || isRaw)

    /** Uppercased file stem for JPEG/RAW pairing. */
    val normalizedBaseName: String by lazy(LazyThreadSafetyMode.NONE) {
        filename
            .substringBeforeLast('.', filename)
            .trim()
            .uppercase(Locale.US)
    }

    /** Best-effort parsed capture timestamp, if present. */
    val captureTimestampMillis: Long? by lazy(LazyThreadSafetyMode.NONE) {
        parsePtpTimestamp(captureDate)
    }

    /** Best-effort parsed modification timestamp, if present. */
    val modificationTimestampMillis: Long? by lazy(LazyThreadSafetyMode.NONE) {
        parsePtpTimestamp(modificationDate)
    }

    /** Preferred timestamp for ordering images by recency. */
    val preferredTimestampMillis: Long? by lazy(LazyThreadSafetyMode.NONE) {
        captureTimestampMillis ?: modificationTimestampMillis
    }

    companion object {
        /** Known RAW file extensions for Olympus/OM System cameras. */
        private val RAW_EXTENSIONS = setOf("ORF", "ORI")

        private val timestampPatterns = listOf(
            "yyyyMMdd'T'HHmmss",
            "yyyyMMdd'T'HHmmss.S",
            "yyyyMMdd'T'HHmmssZ",
            "yyyyMMdd'T'HHmmss.SZ",
        )

        fun parse(data: ByteArray, handle: Int): PtpObjectInfo {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            val storageId = buf.getInt()
            val objectFormat = buf.getShort().toInt() and 0xFFFF
            val protectionStatus = buf.getShort().toInt() and 0xFFFF
            val compressedSize = buf.getInt()
            val thumbFormat = buf.getShort().toInt() and 0xFFFF
            val thumbCompressedSize = buf.getInt()
            val thumbPixWidth = buf.getInt()
            val thumbPixHeight = buf.getInt()
            val imagePixWidth = buf.getInt()
            val imagePixHeight = buf.getInt()
            val imageBitDepth = buf.getInt()
            val parentObject = buf.getInt()
            val associationType = buf.getShort().toInt() and 0xFFFF
            val associationDesc = buf.getInt()
            val sequenceNumber = buf.getInt()
            val filename = readPtpString(buf)
            val captureDate = readPtpString(buf)
            val modificationDate = readPtpString(buf)
            val keywords = readPtpString(buf)

            return PtpObjectInfo(
                handle = handle,
                storageId = storageId,
                objectFormat = objectFormat,
                protectionStatus = protectionStatus,
                compressedSize = compressedSize,
                thumbFormat = thumbFormat,
                thumbCompressedSize = thumbCompressedSize,
                thumbPixWidth = thumbPixWidth,
                thumbPixHeight = thumbPixHeight,
                imagePixWidth = imagePixWidth,
                imagePixHeight = imagePixHeight,
                imageBitDepth = imageBitDepth,
                parentObject = parentObject,
                associationType = associationType,
                associationDesc = associationDesc,
                sequenceNumber = sequenceNumber,
                filename = filename,
                captureDate = captureDate,
                modificationDate = modificationDate,
                keywords = keywords,
            )
        }

        private fun parsePtpTimestamp(rawValue: String): Long? {
            val value = rawValue.trim()
            if (value.isEmpty()) {
                return null
            }
            return timestampPatterns.firstNotNullOfOrNull { pattern ->
                runCatching {
                    SimpleDateFormat(pattern, Locale.US).apply {
                        isLenient = false
                    }.parse(value)?.time
                }.getOrNull()
            }
        }

        private fun readPtpString(buf: ByteBuffer): String {
            if (buf.remaining() < 1) return ""
            val numChars = buf.get().toInt() and 0xFF
            if (numChars == 0) return ""
            val bytes = ByteArray(numChars * 2)
            if (buf.remaining() >= bytes.size) {
                buf.get(bytes)
            }
            return String(bytes, Charset.forName("UTF-16LE")).trimEnd('\u0000')
        }
    }
}
