package dev.pl36.cameralink.core.deepsky

import android.graphics.BitmapFactory
import android.graphics.Color
import dev.pl36.cameralink.core.logging.D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

interface DeepSkyFrameDecoder {
    suspend fun decode(frame: CapturedFrame): DecodedFrame

    fun recycle(frameId: String, decodedFrame: DecodedFrame) = Unit
}

data class NativeRawDecodeResult(
    val width: Int,
    val height: Int,
    val fullResRgb48: ShortArray,
    val previewWidth: Int,
    val previewHeight: Int,
    val previewArgb: IntArray,
    val alignmentWidth: Int,
    val alignmentHeight: Int,
    val alignmentLuma: FloatArray,
)

object DeepSkyNative {
    private const val LIBRARY_NAME = "deepskynative"

    val isAvailable: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        runCatching {
            System.loadLibrary(LIBRARY_NAME)
            true
        }.onFailure { throwable ->
            D.err("RAW", "Failed to load $LIBRARY_NAME", throwable)
        }.getOrDefault(false)
    }

    external fun decodeOrf(
        rawPath: String,
        previewMaxEdge: Int,
        alignmentMaxEdge: Int,
    ): NativeRawDecodeResult?
}

class DeepSkyBufferPool {
    private val shortArrays = ArrayDeque<ShortArray>()
    private val floatArrays = ArrayDeque<FloatArray>()
    private val intArrays = ArrayDeque<IntArray>()

    @Synchronized
    fun acquireShortArray(size: Int): ShortArray {
        val iterator = shortArrays.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate.size == size) {
                iterator.remove()
                return candidate
            }
        }
        return ShortArray(size)
    }

    @Synchronized
    fun acquireFloatArray(size: Int): FloatArray {
        val iterator = floatArrays.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate.size == size) {
                iterator.remove()
                return candidate
            }
        }
        return FloatArray(size)
    }

    @Synchronized
    fun acquireIntArray(size: Int): IntArray {
        val iterator = intArrays.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate.size == size) {
                iterator.remove()
                return candidate
            }
        }
        return IntArray(size)
    }

    @Synchronized
    fun release(array: ShortArray) {
        shortArrays += array
    }

    @Synchronized
    fun release(array: FloatArray) {
        floatArrays += array
    }

    @Synchronized
    fun release(array: IntArray) {
        intArrays += array
    }
}

class DefaultDeepSkyFrameDecoder(
    private val bufferPool: DeepSkyBufferPool = DeepSkyBufferPool(),
    private val rawDecoder: RawDecoder = RawDecoder(bufferPool = bufferPool),
    private val jpegDecoder: JpegDecoder = JpegDecoder(bufferPool = bufferPool),
) : DeepSkyFrameDecoder {
    private val decoderByFrameId = ConcurrentHashMap<String, DeepSkyFrameDecoder>()

    override suspend fun decode(frame: CapturedFrame): DecodedFrame {
        val prefersRaw = if (
            frame.rawPath.endsWith(".orf", ignoreCase = true) ||
            frame.rawPath.endsWith(".ori", ignoreCase = true)
        ) {
            true
        } else {
            false
        }
        var usedDecoder: DeepSkyFrameDecoder = jpegDecoder
        val decoded = if (prefersRaw) {
            val rawDecoded = rawDecoder.decodeOrNull(frame)
            if (rawDecoded != null) {
                usedDecoder = rawDecoder
                rawDecoded
            } else {
                jpegDecoder.decode(frame)
            }
        } else {
            jpegDecoder.decode(frame)
        }
        decoderByFrameId[frame.id] = usedDecoder
        return decoded
    }

    override fun recycle(frameId: String, decodedFrame: DecodedFrame) {
        decoderByFrameId.remove(frameId)?.recycle(frameId, decodedFrame)
    }
}

class RawDecoder(
    private val previewMaxEdge: Int = 1440,
    private val alignmentMaxEdge: Int = 1024,
    private val bufferPool: DeepSkyBufferPool = DeepSkyBufferPool(),
) : DeepSkyFrameDecoder {
    suspend fun decodeOrNull(frame: CapturedFrame): DecodedFrame? = withContext(Dispatchers.IO) {
        if (!DeepSkyNative.isAvailable) {
            D.raw("Native RAW decoder unavailable; falling back for frame=${frame.id}")
            return@withContext null
        }
        val rawPath = frame.rawPath
        if (!File(rawPath).isFile) {
            D.raw("RAW file missing for frame=${frame.id} path=$rawPath")
            return@withContext null
        }
        runCatching {
            D.raw("Decoding RAW frame=${frame.id} path=$rawPath")
            DeepSkyNative.decodeOrf(rawPath, previewMaxEdge, alignmentMaxEdge)
        }.onFailure { throwable ->
            D.err("RAW", "RAW decode failed for frame=${frame.id}", throwable)
        }.getOrNull()?.let { native ->
            DecodedFrame(
                fullResRgb48 = native.fullResRgb48,
                fullResWidth = native.width,
                fullResHeight = native.height,
                alignmentLuma = native.alignmentLuma,
                alignmentWidth = native.alignmentWidth,
                alignmentHeight = native.alignmentHeight,
                previewArgb = native.previewArgb,
                previewWidth = native.previewWidth,
                previewHeight = native.previewHeight,
            )
        }
    }

    override suspend fun decode(frame: CapturedFrame): DecodedFrame {
        return decodeOrNull(frame) ?: error("RAW decode failed for ${frame.id}")
    }

    override fun recycle(frameId: String, decodedFrame: DecodedFrame) {
        bufferPool.release(decodedFrame.fullResRgb48)
        bufferPool.release(decodedFrame.alignmentLuma)
        bufferPool.release(decodedFrame.previewArgb)
    }
}

class JpegDecoder(
    private val previewMaxEdge: Int = 1440,
    private val alignmentMaxEdge: Int = 1024,
    private val bufferPool: DeepSkyBufferPool = DeepSkyBufferPool(),
) : DeepSkyFrameDecoder {
    override suspend fun decode(frame: CapturedFrame): DecodedFrame = withContext(Dispatchers.IO) {
        val preferredPath = sequenceOf(frame.previewPath, frame.rawPath)
            .firstOrNull { it.isNotBlank() && File(it).isFile }
            ?: error("No decodable frame path exists for ${frame.id}")
        val bitmap = BitmapFactory.decodeFile(preferredPath)
            ?: error("Bitmap decode returned null for $preferredPath")
        try {
            val fullWidth = bitmap.width
            val fullHeight = bitmap.height
            val fullRgb = bufferPool.acquireShortArray(fullWidth * fullHeight * 3)
            val pixelBuffer = IntArray(fullWidth * fullHeight)
            bitmap.getPixels(pixelBuffer, 0, fullWidth, 0, 0, fullWidth, fullHeight)
            var pixelIndex = 0
            for (pixel in pixelBuffer) {
                fullRgb[pixelIndex++] = (Color.red(pixel) * 257).toShort()
                fullRgb[pixelIndex++] = (Color.green(pixel) * 257).toShort()
                fullRgb[pixelIndex++] = (Color.blue(pixel) * 257).toShort()
            }

            val preview = buildPreviewArgb(
                fullResRgb48 = fullRgb,
                fullWidth = fullWidth,
                fullHeight = fullHeight,
                maxEdge = previewMaxEdge,
            )
            val alignment = buildAlignmentLuma(
                fullResRgb48 = fullRgb,
                fullWidth = fullWidth,
                fullHeight = fullHeight,
                maxEdge = alignmentMaxEdge,
            )
            DecodedFrame(
                fullResRgb48 = fullRgb,
                fullResWidth = fullWidth,
                fullResHeight = fullHeight,
                alignmentLuma = alignment.first,
                alignmentWidth = alignment.second,
                alignmentHeight = alignment.third,
                previewArgb = preview.first,
                previewWidth = preview.second,
                previewHeight = preview.third,
            )
        } finally {
            bitmap.recycle()
        }
    }

    override fun recycle(frameId: String, decodedFrame: DecodedFrame) {
        bufferPool.release(decodedFrame.fullResRgb48)
        bufferPool.release(decodedFrame.alignmentLuma)
        bufferPool.release(decodedFrame.previewArgb)
    }

    private fun buildPreviewArgb(
        fullResRgb48: ShortArray,
        fullWidth: Int,
        fullHeight: Int,
        maxEdge: Int,
    ): Triple<IntArray, Int, Int> {
        val scale = max(fullWidth, fullHeight).toFloat() / maxEdge.toFloat()
        val previewWidth = max(1, (fullWidth / max(1f, scale)).roundToInt())
        val previewHeight = max(1, (fullHeight / max(1f, scale)).roundToInt())
        val preview = bufferPool.acquireIntArray(previewWidth * previewHeight)
        for (y in 0 until previewHeight) {
            val srcY = ((y / previewHeight.toFloat()) * fullHeight).toInt().coerceIn(0, fullHeight - 1)
            for (x in 0 until previewWidth) {
                val srcX = ((x / previewWidth.toFloat()) * fullWidth).toInt().coerceIn(0, fullWidth - 1)
                val srcIndex = (srcY * fullWidth + srcX) * 3
                val r = ((fullResRgb48[srcIndex].toInt() and 0xffff) / 257).coerceIn(0, 255)
                val g = ((fullResRgb48[srcIndex + 1].toInt() and 0xffff) / 257).coerceIn(0, 255)
                val b = ((fullResRgb48[srcIndex + 2].toInt() and 0xffff) / 257).coerceIn(0, 255)
                preview[y * previewWidth + x] = Color.argb(255, r, g, b)
            }
        }
        return Triple(preview, previewWidth, previewHeight)
    }

    private fun buildAlignmentLuma(
        fullResRgb48: ShortArray,
        fullWidth: Int,
        fullHeight: Int,
        maxEdge: Int,
    ): Triple<FloatArray, Int, Int> {
        val scale = max(fullWidth, fullHeight).toFloat() / maxEdge.toFloat()
        val alignmentWidth = max(1, (fullWidth / max(1f, scale)).roundToInt())
        val alignmentHeight = max(1, (fullHeight / max(1f, scale)).roundToInt())
        val luma = bufferPool.acquireFloatArray(alignmentWidth * alignmentHeight)
        for (y in 0 until alignmentHeight) {
            val srcY = ((y / alignmentHeight.toFloat()) * fullHeight).toInt().coerceIn(0, fullHeight - 1)
            for (x in 0 until alignmentWidth) {
                val srcX = ((x / alignmentWidth.toFloat()) * fullWidth).toInt().coerceIn(0, fullWidth - 1)
                val srcIndex = (srcY * fullWidth + srcX) * 3
                val r = fullResRgb48[srcIndex].toInt() and 0xffff
                val g = fullResRgb48[srcIndex + 1].toInt() and 0xffff
                val b = fullResRgb48[srcIndex + 2].toInt() and 0xffff
                luma[y * alignmentWidth + x] = (0.2126f * r) + (0.7152f * g) + (0.0722f * b)
            }
        }
        return Triple(luma, alignmentWidth, alignmentHeight)
    }
}
