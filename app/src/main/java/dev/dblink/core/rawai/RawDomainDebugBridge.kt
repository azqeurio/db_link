package dev.dblink.core.rawai

import dev.dblink.BuildConfig

/** Exact, explicitly invoked debug observation of the existing LibRaw dcraw_process path. */
data class RawDomainDebugTensor(
    val width: Int,
    val height: Int,
    val cropX: Int,
    val cropY: Int,
    val tensorHwc: FloatArray,
    val rawWidth: Int,
    val rawHeight: Int,
    val processedWidth: Int,
    val processedHeight: Int,
    val topMargin: Int,
    val leftMargin: Int,
    val orientationFlip: Int,
    val cfaPattern: String,
    val blackLevels: FloatArray,
    val whiteLevel: Int,
    val cameraWhiteBalance: FloatArray,
    val stage: String,
    val libRawVersion: String,
)

data class RawBayerDebugFrame(
    val width: Int,
    val height: Int,
    val cropX: Int,
    val cropY: Int,
    val mosaic: IntArray,
    val rawWidth: Int,
    val rawHeight: Int,
    val visibleWidth: Int,
    val visibleHeight: Int,
    val topMargin: Int,
    val leftMargin: Int,
    val orientationFlip: Int,
    val cfaPattern: String,
    val blackLevels: IntArray,
    val whiteLevel: Int,
    val rgbXyzMatrix: DoubleArray,
    val cameraModel: String,
    val libRawVersion: String,
)

object RawDomainDebugNative {
    private const val LIBRARY_NAME = "deepskynative"

    private val loaded: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        check(BuildConfig.DEBUG) { "RAW-domain extraction is debug-only" }
        System.loadLibrary(LIBRARY_NAME)
        true
    }

    fun extract(rawPath: String, cropX: Int, cropY: Int, width: Int, height: Int): RawDomainDebugTensor {
        check(loaded)
        require(cropX >= 0 && cropY >= 0 && width > 0 && height > 0)
        return extractProcessedCrop(rawPath, cropX, cropY, width, height)
            ?: error("Debug LibRaw extraction failed for crop=($cropX,$cropY ${width}x$height)")
    }

    fun extractBayer(rawPath: String, cropX: Int, cropY: Int, width: Int, height: Int): RawBayerDebugFrame {
        check(loaded)
        require(cropX >= 0 && cropY >= 0 && width > 0 && height > 0)
        return extractVisibleBayerCrop(rawPath, cropX, cropY, width, height)
            ?: error("Debug Bayer extraction failed for crop=($cropX,$cropY ${width}x$height)")
    }

    private external fun extractProcessedCrop(
        rawPath: String,
        cropX: Int,
        cropY: Int,
        width: Int,
        height: Int,
    ): RawDomainDebugTensor?

    private external fun extractVisibleBayerCrop(
        rawPath: String,
        cropX: Int,
        cropY: Int,
        width: Int,
        height: Int,
    ): RawBayerDebugFrame?
}
