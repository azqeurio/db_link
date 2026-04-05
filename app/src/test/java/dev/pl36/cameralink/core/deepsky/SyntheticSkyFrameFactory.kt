package dev.pl36.cameralink.core.deepsky

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

data class SyntheticStarSpec(
    val x: Float,
    val y: Float,
    val peak: Float,
    val sigmaX: Float = 1.2f,
    val sigmaY: Float = 1.2f,
    val angleDeg: Float = 0f,
)

object SyntheticSkyFrameFactory {
    fun starFieldLuma(
        width: Int,
        height: Int,
        background: Float = 48f,
        noiseAmplitude: Float = 0f,
        stars: List<SyntheticStarSpec>,
        hotPixels: List<Pair<Int, Int>> = emptyList(),
    ): FloatArray {
        val luma = FloatArray(width * height) { index ->
            background + deterministicNoise(index, noiseAmplitude)
        }
        for (star in stars) {
            paintStar(luma, width, height, star)
        }
        for ((x, y) in hotPixels) {
            if (x in 0 until width && y in 0 until height) {
                luma[y * width + x] = background + 2200f
            }
        }
        return luma
    }

    fun detectedStars(
        count: Int = 10,
        startX: Float = 120f,
        startY: Float = 120f,
        spacingX: Float = 70f,
        spacingY: Float = 52f,
    ): List<DetectedStar> {
        return List(count) { index ->
            val row = index / 5
            val column = index % 5
            DetectedStar(
                x = startX + column * spacingX + row * 11f,
                y = startY + row * spacingY + column * 7f,
                flux = (180f - index * 8f).coerceAtLeast(60f),
                peak = (240f - index * 10f).coerceAtLeast(90f),
                localContrast = 7f - row * 0.2f,
                fwhmPx = 2.4f + row * 0.08f,
                elongation = 1.05f + column * 0.03f,
                isolationScore = 0.92f - column * 0.05f,
                usableForMatching = true,
            )
        }
    }

    fun inverseRigidTransform(
        referenceStars: List<DetectedStar>,
        transform: RegistrationTransform,
        alignmentWidth: Int,
        alignmentHeight: Int,
    ): List<DetectedStar> {
        val centerX = (alignmentWidth - 1) / 2f
        val centerY = (alignmentHeight - 1) / 2f
        val radians = -transform.rotationDeg / 180f * Math.PI.toFloat()
        val cosTheta = cos(radians)
        val sinTheta = sin(radians)
        return referenceStars.map { reference ->
            val translatedX = reference.x - transform.dx
            val translatedY = reference.y - transform.dy
            val relX = translatedX - centerX
            val relY = translatedY - centerY
            val currentX = cosTheta * relX - sinTheta * relY + centerX
            val currentY = sinTheta * relX + cosTheta * relY + centerY
            reference.copy(x = currentX, y = currentY)
        }
    }

    fun inverseAffineTransform(
        referenceStars: List<DetectedStar>,
        transform: RegistrationTransform,
    ): List<DetectedStar> {
        val determinant = (transform.a * transform.d) - (transform.b * transform.c)
        val invA = transform.d / determinant
        val invB = -transform.b / determinant
        val invC = -transform.c / determinant
        val invD = transform.a / determinant
        return referenceStars.map { reference ->
            val translatedX = reference.x - transform.dx
            val translatedY = reference.y - transform.dy
            val currentX = invA * translatedX + invB * translatedY
            val currentY = invC * translatedX + invD * translatedY
            reference.copy(x = currentX, y = currentY)
        }
    }

    fun decodedFrame(
        width: Int = 64,
        height: Int = 64,
        baseValue: Int = 1024,
        brightPixel: Pair<Int, Int>? = null,
        previewOutlierBoost: Int = 0,
    ): DecodedFrame {
        val full = ShortArray(width * height * 3)
        val preview = IntArray(width * height)
        val luma = FloatArray(width * height)
        for (index in 0 until width * height) {
            val value = baseValue + if (brightPixel != null && index == brightPixel.second * width + brightPixel.first) previewOutlierBoost else 0
            full[index * 3] = value.toShort()
            full[index * 3 + 1] = value.toShort()
            full[index * 3 + 2] = value.toShort()
            luma[index] = value.toFloat()
            val channel = (value / 257).coerceIn(0, 255)
            preview[index] = packOpaqueArgb(channel, channel, channel)
        }
        return DecodedFrame(
            fullResRgb48 = full,
            fullResWidth = width,
            fullResHeight = height,
            alignmentLuma = luma,
            alignmentWidth = width,
            alignmentHeight = height,
            previewArgb = preview,
            previewWidth = width,
            previewHeight = height,
        )
    }

    private fun paintStar(
        luma: FloatArray,
        width: Int,
        height: Int,
        star: SyntheticStarSpec,
    ) {
        val radians = star.angleDeg / 180f * Math.PI.toFloat()
        val cosTheta = cos(radians)
        val sinTheta = sin(radians)
        val radiusX = (star.sigmaX * 3f).toInt().coerceAtLeast(1)
        val radiusY = (star.sigmaY * 3f).toInt().coerceAtLeast(1)
        val startX = (star.x.toInt() - radiusX).coerceAtLeast(0)
        val endX = (star.x.toInt() + radiusX).coerceAtMost(width - 1)
        val startY = (star.y.toInt() - radiusY).coerceAtLeast(0)
        val endY = (star.y.toInt() + radiusY).coerceAtMost(height - 1)
        for (y in startY..endY) {
            for (x in startX..endX) {
                val dx = x - star.x
                val dy = y - star.y
                val rotX = cosTheta * dx + sinTheta * dy
                val rotY = -sinTheta * dx + cosTheta * dy
                val exponent =
                    -0.5f * (
                        (rotX * rotX) / (star.sigmaX * star.sigmaX) +
                            (rotY * rotY) / (star.sigmaY * star.sigmaY)
                        )
                val value = star.peak * exp(exponent.toDouble()).toFloat()
                luma[y * width + x] += value
            }
        }
    }

    private fun deterministicNoise(index: Int, amplitude: Float): Float {
        if (amplitude <= 0f) return 0f
        val value = ((index * 1103515245L + 12345L) and 0x7fffffffL).toFloat() / 0x7fffffffL.toFloat()
        return (value - 0.5f) * amplitude
    }
}
