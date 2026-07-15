package dev.dblink.core.rawai

import kotlin.math.abs
import kotlin.math.sqrt

data class SeamChannelMetrics(val mean: Double, val maximum: Double, val rmse: Double)

data class SeamMetrics(
    val verticalSeamCount: Int,
    val horizontalSeamCount: Int,
    val meanBoundaryJump: Double,
    val maximumBoundaryJump: Double,
    val boundaryRmse: Double,
    val interiorMeanJump: Double,
    val seamToInteriorRatio: Double,
    val channels: List<SeamChannelMetrics>,
)

object SeamAnalyzer {
    /** Measures adjacent-pixel jumps at each nonzero tile origin versus all non-boundary adjacencies. */
    fun analyze(image: LinearRgbImage, plan: TilePlan): SeamMetrics {
        val vertical = plan.originsX.drop(1).filter { it in 1 until image.width }.toSet()
        val horizontal = plan.originsY.drop(1).filter { it in 1 until image.height }.toSet()
        val channelSum = DoubleArray(3)
        val channelSquares = DoubleArray(3)
        val channelMax = DoubleArray(3)
        val channelCount = LongArray(3)
        var boundarySum = 0.0
        var boundarySquares = 0.0
        var boundaryMax = 0.0
        var boundaryCount = 0L
        var interiorSum = 0.0
        var interiorCount = 0L

        fun record(a: Int, b: Int, boundary: Boolean) {
            for (channel in 0..2) {
                val jump = abs(image.data[a * 3 + channel].toDouble() - image.data[b * 3 + channel].toDouble())
                if (boundary) {
                    boundarySum += jump
                    boundarySquares += jump * jump
                    boundaryMax = maxOf(boundaryMax, jump)
                    boundaryCount++
                    channelSum[channel] += jump
                    channelSquares[channel] += jump * jump
                    channelMax[channel] = maxOf(channelMax[channel], jump)
                    channelCount[channel]++
                } else {
                    interiorSum += jump
                    interiorCount++
                }
            }
        }

        for (y in 0 until image.height) for (x in 1 until image.width) {
            record(y * image.width + x - 1, y * image.width + x, x in vertical)
        }
        for (y in 1 until image.height) for (x in 0 until image.width) {
            record((y - 1) * image.width + x, y * image.width + x, y in horizontal)
        }
        val boundaryMean = if (boundaryCount == 0L) 0.0 else boundarySum / boundaryCount
        val interiorMean = if (interiorCount == 0L) 0.0 else interiorSum / interiorCount
        return SeamMetrics(
            vertical.size, horizontal.size, boundaryMean, boundaryMax,
            if (boundaryCount == 0L) 0.0 else sqrt(boundarySquares / boundaryCount),
            interiorMean, if (interiorMean == 0.0) if (boundaryMean == 0.0) 1.0 else Double.POSITIVE_INFINITY else boundaryMean / interiorMean,
            List(3) { channel ->
                val count = channelCount[channel].coerceAtLeast(1).toDouble()
                SeamChannelMetrics(channelSum[channel] / count, channelMax[channel], sqrt(channelSquares[channel] / count))
            },
        )
    }
}
