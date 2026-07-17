package dev.dblink.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Stateless helpers for turning raw camera image bytes (JPEG, ORF/TIFF RAW, or
 * arbitrary containers with an embedded preview) into a displayable [Bitmap],
 * plus the EXIF/TIFF parsing those decoders rely on.
 *
 * Extracted verbatim from `MainViewModel` so the preview/thumbnail decoding can
 * be shared and unit-tested independently of the camera session state. These
 * functions are pure: they never touch ViewModel state.
 */
object ImagePreviewDecoder {

    const val DEFAULT_PREVIEW_MAX_DIMENSION = 2048

    fun decodeTransferPreviewBitmap(
        imageBytes: ByteArray,
        maxDimension: Int = DEFAULT_PREVIEW_MAX_DIMENSION,
    ): Bitmap? {
        decodeSampledPreviewBitmap(imageBytes, maxDimension = maxDimension)?.let { return it }
        decodePlatformPreviewBitmap(imageBytes, maxDimension = maxDimension)?.let { return it }
        extractTiffPreviewBytes(imageBytes)?.let { previewBytes ->
            decodeSampledPreviewBitmap(previewBytes, maxDimension = maxDimension)?.let { return it }
            decodePlatformPreviewBitmap(previewBytes, maxDimension = maxDimension)?.let { return it }
        }
        extractExifThumbnailBytes(imageBytes)?.let { thumbnailBytes ->
            decodeSampledPreviewBitmap(thumbnailBytes, maxDimension = maxDimension)?.let { return it }
            decodePlatformPreviewBitmap(thumbnailBytes, maxDimension = maxDimension)?.let { return it }
        }
        extractEmbeddedJpegPreview(imageBytes)?.let { embeddedPreview ->
            decodeSampledPreviewBitmap(embeddedPreview, maxDimension = maxDimension)?.let { return it }
            decodePlatformPreviewBitmap(embeddedPreview, maxDimension = maxDimension)?.let { return it }
        }
        return null
    }

    fun decodeSampledPreviewBitmap(
        imageBytes: ByteArray,
        maxDimension: Int = DEFAULT_PREVIEW_MAX_DIMENSION,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        val options = BitmapFactory.Options()
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            var sampleSize = 1
            val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
            while (longestSide / sampleSize > maxDimension) {
                sampleSize *= 2
            }
            options.inSampleSize = sampleSize.coerceAtLeast(1)
        }
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
    }

    fun readExifCapturedAtMillis(exif: ExifInterface): Long? {
        val candidateValues = listOf(
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED),
            exif.getAttribute(ExifInterface.TAG_DATETIME),
        )
        val formatter = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        formatter.timeZone = TimeZone.getDefault()
        return candidateValues.firstNotNullOfOrNull { value ->
            value?.let { formatter.parse(it)?.time }
        }
    }

    private fun decodePlatformPreviewBitmap(
        imageBytes: ByteArray,
        maxDimension: Int = DEFAULT_PREVIEW_MAX_DIMENSION,
    ): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null
        }
        return runCatching {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(imageBytes))
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val longestSide = maxOf(info.size.width, info.size.height)
                if (longestSide > maxDimension) {
                    val scale = maxDimension.toFloat() / longestSide.toFloat()
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
    }

    private fun extractTiffPreviewBytes(imageBytes: ByteArray): ByteArray? {
        if (imageBytes.size < 8) {
            return null
        }
        val byteOrder = when {
            imageBytes[0] == 'I'.code.toByte() && imageBytes[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            imageBytes[0] == 'M'.code.toByte() && imageBytes[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> return null
        }
        val firstIfdOffset = readTiffUInt(imageBytes, 4, byteOrder)?.toInt() ?: return null
        val pendingOffsets = ArrayDeque<Int>().apply {
            add(firstIfdOffset)
        }
        val visitedOffsets = linkedSetOf<Int>()
        val previewCandidates = linkedSetOf<Pair<Int, Int>>()

        while (pendingOffsets.isNotEmpty()) {
            val ifdOffset = pendingOffsets.removeFirst()
            if (!visitedOffsets.add(ifdOffset) || ifdOffset <= 0 || ifdOffset + 2 > imageBytes.size) {
                continue
            }
            val entryCount = readTiffUShort(imageBytes, ifdOffset, byteOrder) ?: continue
            var jpegOffset: Int? = null
            var jpegLength: Int? = null
            var stripOffsets: LongArray? = null
            var stripByteCounts: LongArray? = null
            var tileOffsets: LongArray? = null
            var tileByteCounts: LongArray? = null
            for (entryIndex in 0 until entryCount) {
                val entryOffset = ifdOffset + 2 + entryIndex * 12
                if (entryOffset + 12 > imageBytes.size) {
                    break
                }
                val tag = readTiffUShort(imageBytes, entryOffset, byteOrder) ?: continue
                val type = readTiffUShort(imageBytes, entryOffset + 2, byteOrder) ?: continue
                val count = readTiffUInt(imageBytes, entryOffset + 4, byteOrder)?.toInt() ?: continue
                val values = readTiffValues(imageBytes, entryOffset + 8, type, count, byteOrder)
                when (tag) {
                    0x0201 -> jpegOffset = values.firstOrNull()?.toInt()
                    0x0202 -> jpegLength = values.firstOrNull()?.toInt()
                    0x0111 -> stripOffsets = values
                    0x0117 -> stripByteCounts = values
                    0x0144 -> tileOffsets = values
                    0x0145 -> tileByteCounts = values
                    0x014A, 0x8769, 0x8825, 0xA005 -> {
                        values.mapTo(pendingOffsets) { it.toInt() }
                    }
                }
            }

            if (jpegOffset != null && jpegLength != null) {
                previewCandidates += jpegOffset to jpegLength
            }
            if (stripOffsets != null && stripByteCounts != null && stripOffsets.size == stripByteCounts.size) {
                stripOffsets.indices.forEach { index ->
                    previewCandidates += stripOffsets[index].toInt() to stripByteCounts[index].toInt()
                }
            }
            if (tileOffsets != null && tileByteCounts != null && tileOffsets.size == tileByteCounts.size) {
                tileOffsets.indices.forEach { index ->
                    previewCandidates += tileOffsets[index].toInt() to tileByteCounts[index].toInt()
                }
            }

            val nextIfdOffset = readTiffUInt(imageBytes, ifdOffset + 2 + entryCount * 12, byteOrder)?.toInt()
            if (nextIfdOffset != null && nextIfdOffset > 0) {
                pendingOffsets += nextIfdOffset
            }
        }

        return previewCandidates
            .asSequence()
            .filter { (offset, length) ->
                offset >= 0 &&
                    length > 32 &&
                    offset + length <= imageBytes.size
            }
            .sortedByDescending { (_, length) -> length }
            .mapNotNull { (offset, length) ->
                imageBytes.copyOfRange(offset, offset + length).let { candidate ->
                    if (candidate.size >= 2 &&
                        candidate[0] == 0xFF.toByte() &&
                        candidate[1] == 0xD8.toByte()
                    ) {
                        candidate
                    } else {
                        extractTiffPreviewBytes(candidate) ?: extractEmbeddedJpegPreview(candidate)
                    }
                }
            }
            .firstOrNull()
    }

    private fun extractExifThumbnailBytes(imageBytes: ByteArray): ByteArray? {
        return runCatching {
            ByteArrayInputStream(imageBytes).use { inputStream ->
                val exif = ExifInterface(inputStream)
                if (exif.hasThumbnail()) {
                    exif.thumbnailBytes
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun extractEmbeddedJpegPreview(imageBytes: ByteArray): ByteArray? {
        var bestStart = -1
        var bestEnd = -1
        var index = 0
        while (index < imageBytes.size - 1) {
            if (imageBytes[index] == 0xFF.toByte() && imageBytes[index + 1] == 0xD8.toByte()) {
                var end = index + 2
                while (end < imageBytes.size - 1) {
                    if (imageBytes[end] == 0xFF.toByte() && imageBytes[end + 1] == 0xD9.toByte()) {
                        val candidateEnd = end + 2
                        if (candidateEnd - index > bestEnd - bestStart) {
                            bestStart = index
                            bestEnd = candidateEnd
                        }
                        break
                    }
                    end++
                }
                index = end
            } else {
                index++
            }
        }
        return if (bestStart >= 0 && bestEnd > bestStart) {
            imageBytes.copyOfRange(bestStart, bestEnd)
        } else {
            null
        }
    }

    private fun readTiffUShort(
        data: ByteArray,
        offset: Int,
        byteOrder: ByteOrder,
    ): Int? {
        if (offset < 0 || offset + 2 > data.size) {
            return null
        }
        return ByteBuffer.wrap(data, offset, 2).order(byteOrder).short.toInt() and 0xFFFF
    }

    private fun readTiffUInt(
        data: ByteArray,
        offset: Int,
        byteOrder: ByteOrder,
    ): Long? {
        if (offset < 0 || offset + 4 > data.size) {
            return null
        }
        return ByteBuffer.wrap(data, offset, 4).order(byteOrder).int.toLong() and 0xFFFFFFFFL
    }

    private fun readTiffValues(
        data: ByteArray,
        valueFieldOffset: Int,
        type: Int,
        count: Int,
        byteOrder: ByteOrder,
    ): LongArray {
        val typeSize = when (type) {
            1, 2, 6, 7 -> 1
            3, 8 -> 2
            4, 9, 13 -> 4
            5, 10, 12 -> 8
            16, 17, 18 -> 8
            else -> return longArrayOf()
        }
        val totalBytes = typeSize * count
        val dataOffset = if (totalBytes <= 4) {
            valueFieldOffset
        } else {
            readTiffUInt(data, valueFieldOffset, byteOrder)?.toInt() ?: return longArrayOf()
        }
        if (dataOffset < 0 || dataOffset + totalBytes > data.size) {
            return longArrayOf()
        }
        return LongArray(count) { index ->
            val elementOffset = dataOffset + index * typeSize
            when (type) {
                1, 2, 6, 7 -> data[elementOffset].toLong() and 0xFFL
                3, 8 -> readTiffUShort(data, elementOffset, byteOrder)?.toLong() ?: 0L
                4, 9, 13 -> readTiffUInt(data, elementOffset, byteOrder) ?: 0L
                16, 17, 18 -> {
                    if (elementOffset + 8 > data.size) {
                        0L
                    } else {
                        ByteBuffer.wrap(data, elementOffset, 8).order(byteOrder).long
                    }
                }
                else -> 0L
            }
        }
    }
}
