package dev.dblink.core.rawai

import kotlin.math.abs

data class RawModelDomainMetadata(
    val width: Int,
    val height: Int,
    val cfaPattern: String,
    val blackLevels: IntArray,
    val whiteLevel: Int,
    /** Row-major 3x3 matrix matching rawpy's first three rgb_xyz_matrix rows. */
    val rgbXyzMatrix: DoubleArray,
)

data class RawModelDomainTensor(
    val width: Int,
    val height: Int,
    /** HWC RGB float32, clipped to [0, 1] and rounded through IEEE-754 binary16. */
    val values: FloatArray,
)

/**
 * Isolated implementation of the RawForge model input contract.
 *
 * It intentionally does not alter the production LibRaw decoder. Input is a visible Bayer
 * mosaic plus metadata; output is per-CFA normalized, camera-to-linear-Rec.2020 transformed,
 * Malvar2004-demosaiced HWC RGB.
 */
object RawModelDomainPreprocessor {
    private val xyzToLinearRec2020 = doubleArrayOf(
        1.71666343, -0.35567332, -0.25336809,
        -0.66667384, 1.61645574, 0.01576830,
        0.01764248, -0.04277698, 0.94224328,
    )

    fun preprocess(visibleMosaic: IntArray, metadata: RawModelDomainMetadata): RawModelDomainTensor {
        require(metadata.width > 0 && metadata.height > 0)
        require(visibleMosaic.size == metadata.width * metadata.height)
        require(metadata.blackLevels.size == 4)
        require(metadata.rgbXyzMatrix.size == 9)
        require(metadata.whiteLevel > metadata.blackLevels.max())

        val (offsetX, offsetY) = when (metadata.cfaPattern.uppercase()) {
            "RGGB" -> 0 to 0
            "BGGR" -> 1 to 1
            "GBRG" -> 0 to 1
            "GRBG" -> 1 to 0
            else -> error("Unsupported Bayer pattern: ${metadata.cfaPattern}")
        }
        var width = metadata.width - offsetX * 2
        var height = metadata.height - offsetY * 2
        width -= width % 2
        height -= height % 2
        require(width >= 2 && height >= 2) { "Bayer input is too small after CFA alignment" }

        val cameraToXyz = invert3x3(metadata.rgbXyzMatrix)
        val cameraToRec2020 = multiply3x3(xyzToLinearRec2020, cameraToXyz)
        val rggbTransform = doubleArrayOf(
            cameraToRec2020[0], cameraToRec2020[1] / 2.0, cameraToRec2020[1] / 2.0, cameraToRec2020[2],
            cameraToRec2020[3], cameraToRec2020[4], 0.0, cameraToRec2020[5],
            cameraToRec2020[3], 0.0, cameraToRec2020[4], cameraToRec2020[5],
            cameraToRec2020[6], cameraToRec2020[7] / 2.0, cameraToRec2020[7] / 2.0, cameraToRec2020[8],
        )

        val normalized = DoubleArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val sourceX = x + offsetX
                val sourceY = y + offsetY
                val channel = when {
                    y % 2 == 0 && x % 2 == 0 -> 0
                    y % 2 == 0 -> 1
                    x % 2 == 0 -> 3
                    else -> 2
                }
                val black = metadata.blackLevels[channel]
                normalized[y * width + x] =
                    (visibleMosaic[sourceY * metadata.width + sourceX] - black).toDouble() /
                        (metadata.whiteLevel - black).toDouble()
            }
        }

        val transformed = DoubleArray(normalized.size)
        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                val input = doubleArrayOf(
                    normalized[y * width + x],
                    normalized[y * width + x + 1],
                    normalized[(y + 1) * width + x],
                    normalized[(y + 1) * width + x + 1],
                )
                for (row in 0..3) {
                    var value = 0.0
                    for (column in 0..3) value += rggbTransform[row * 4 + column] * input[column]
                    val outputX = x + row % 2
                    val outputY = y + row / 2
                    transformed[outputY * width + outputX] = value
                }
            }
        }

        return RawModelDomainTensor(width, height, malvar2004(transformed, width, height))
    }

    private fun malvar2004(cfa: DoubleArray, width: Int, height: Int): FloatArray {
        val greenKernel = doubleArrayOf(
            0.0, 0.0, -1.0, 0.0, 0.0,
            0.0, 0.0, 2.0, 0.0, 0.0,
            -1.0, 2.0, 4.0, 2.0, -1.0,
            0.0, 0.0, 2.0, 0.0, 0.0,
            0.0, 0.0, -1.0, 0.0, 0.0,
        ).scaled(1.0 / 8.0)
        val redAtGreenHorizontal = doubleArrayOf(
            0.0, 0.0, 0.5, 0.0, 0.0,
            0.0, -1.0, 0.0, -1.0, 0.0,
            -1.0, 4.0, 5.0, 4.0, -1.0,
            0.0, -1.0, 0.0, -1.0, 0.0,
            0.0, 0.0, 0.5, 0.0, 0.0,
        ).scaled(1.0 / 8.0)
        val redAtGreenVertical = transpose5x5(redAtGreenHorizontal)
        val redAtBlue = doubleArrayOf(
            0.0, 0.0, -1.5, 0.0, 0.0,
            0.0, 2.0, 0.0, 2.0, 0.0,
            -1.5, 0.0, 6.0, 0.0, -1.5,
            0.0, 2.0, 0.0, 2.0, 0.0,
            0.0, 0.0, -1.5, 0.0, 0.0,
        ).scaled(1.0 / 8.0)

        val output = FloatArray(width * height * 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val redSite = y % 2 == 0 && x % 2 == 0
                val blueSite = y % 2 == 1 && x % 2 == 1
                val greenOnRedRow = y % 2 == 0 && x % 2 == 1
                val greenOnBlueRow = y % 2 == 1 && x % 2 == 0
                val sample = cfa[y * width + x]
                val red = when {
                    redSite -> sample
                    greenOnRedRow -> convolve(cfa, width, height, x, y, redAtGreenHorizontal)
                    greenOnBlueRow -> convolve(cfa, width, height, x, y, redAtGreenVertical)
                    else -> convolve(cfa, width, height, x, y, redAtBlue)
                }
                val green = if (redSite || blueSite) {
                    convolve(cfa, width, height, x, y, greenKernel)
                } else {
                    sample
                }
                val blue = when {
                    blueSite -> sample
                    greenOnBlueRow -> convolve(cfa, width, height, x, y, redAtGreenHorizontal)
                    greenOnRedRow -> convolve(cfa, width, height, x, y, redAtGreenVertical)
                    else -> convolve(cfa, width, height, x, y, redAtBlue)
                }
                val base = (y * width + x) * 3
                output[base] = roundToModelFloat(red)
                output[base + 1] = roundToModelFloat(green)
                output[base + 2] = roundToModelFloat(blue)
            }
        }
        return output
    }

    private fun roundToModelFloat(value: Double): Float {
        val clipped = value.coerceIn(0.0, 1.0).toFloat()
        return HalfFloat.toFloat(HalfFloat.fromFloat(clipped))
    }

    private fun convolve(
        values: DoubleArray,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int,
        kernel: DoubleArray,
    ): Double {
        var result = 0.0
        for (ky in 0..4) {
            val y = reflect(centerY + ky - 2, height)
            for (kx in 0..4) {
                val x = reflect(centerX + kx - 2, width)
                result += values[y * width + x] * kernel[ky * 5 + kx]
            }
        }
        return result
    }

    private fun reflect(index: Int, length: Int): Int {
        var value = index
        while (value < 0 || value >= length) {
            value = if (value < 0) -value - 1 else 2 * length - value - 1
        }
        return value
    }

    private fun invert3x3(matrix: DoubleArray): DoubleArray {
        val a = matrix[0]; val b = matrix[1]; val c = matrix[2]
        val d = matrix[3]; val e = matrix[4]; val f = matrix[5]
        val g = matrix[6]; val h = matrix[7]; val i = matrix[8]
        val determinant = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        require(abs(determinant) > 1e-12) { "RGB/XYZ matrix is singular" }
        return doubleArrayOf(
            (e * i - f * h) / determinant,
            (c * h - b * i) / determinant,
            (b * f - c * e) / determinant,
            (f * g - d * i) / determinant,
            (a * i - c * g) / determinant,
            (c * d - a * f) / determinant,
            (d * h - e * g) / determinant,
            (b * g - a * h) / determinant,
            (a * e - b * d) / determinant,
        )
    }

    private fun multiply3x3(left: DoubleArray, right: DoubleArray): DoubleArray {
        val result = DoubleArray(9)
        for (row in 0..2) {
            for (column in 0..2) {
                for (k in 0..2) result[row * 3 + column] += left[row * 3 + k] * right[k * 3 + column]
            }
        }
        return result
    }

    private fun DoubleArray.scaled(scale: Double) = DoubleArray(size) { this[it] * scale }

    private fun transpose5x5(source: DoubleArray) = DoubleArray(25) { index ->
        source[(index % 5) * 5 + index / 5]
    }
}
