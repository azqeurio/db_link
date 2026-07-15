package dev.dblink.core.rawai

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

data class DifferenceSummary(
    val maximum: Double,
    val mean: Double,
    val rmse: Double,
    val largestIndex: Int,
    val changedPercent: Double,
    val psnrPeakOne: Double,
    val channels: List<Triple<Double, Double, Double>>,
)

data class ImageSummary(
    val elements: Int,
    val finite: Int,
    val nan: Int,
    val positiveInfinity: Int,
    val negativeInfinity: Int,
    val minimum: Double,
    val maximum: Double,
    val mean: Double,
    val standardDeviation: Double,
    val belowZeroPercent: Double,
    val aboveOnePercent: Double,
    val equalZeroPercent: Double,
    val equalOnePercent: Double,
    val channels: List<Triple<Double, Double, Double>>,
)

object Pr3Metrics {
    fun difference(reference: FloatArray, actual: FloatArray): DifferenceSummary {
        require(reference.size == actual.size)
        var max = -1.0
        var maxIndex = 0
        var sum = 0.0
        var squares = 0.0
        var changed = 0
        val channelMax = DoubleArray(3)
        val channelSum = DoubleArray(3)
        val channelSquares = DoubleArray(3)
        for (index in reference.indices) {
            val error = abs(reference[index].toDouble() - actual[index].toDouble())
            if (error > max) { max = error; maxIndex = index }
            if (error != 0.0) changed++
            sum += error
            squares += error * error
            val channel = (index / (reference.size / 3)).coerceAtMost(2)
            channelMax[channel] = maxOf(channelMax[channel], error)
            channelSum[channel] += error
            channelSquares[channel] += error * error
        }
        val count = reference.size.toDouble()
        val rmse = sqrt(squares / count)
        val perChannelCount = reference.size / 3.0
        return DifferenceSummary(
            max, sum / count, rmse, maxIndex, changed * 100.0 / count,
            if (rmse == 0.0) Double.POSITIVE_INFINITY else 20.0 * log10(1.0 / rmse),
            List(3) { Triple(channelMax[it], channelSum[it] / perChannelCount, sqrt(channelSquares[it] / perChannelCount)) },
        )
    }

    fun summarize(values: FloatArray): ImageSummary {
        var finite = 0
        var nan = 0
        var positiveInfinity = 0
        var negativeInfinity = 0
        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        var sum = 0.0
        var squares = 0.0
        var below = 0
        var above = 0
        var zero = 0
        var one = 0
        val channelMin = DoubleArray(3) { Double.POSITIVE_INFINITY }
        val channelMax = DoubleArray(3) { Double.NEGATIVE_INFINITY }
        val channelSum = DoubleArray(3)
        val channelCount = IntArray(3)
        values.forEachIndexed { index, raw ->
            val value = raw.toDouble()
            when {
                value.isNaN() -> nan++
                value == Double.POSITIVE_INFINITY -> positiveInfinity++
                value == Double.NEGATIVE_INFINITY -> negativeInfinity++
                else -> {
                    finite++
                    min = minOf(min, value); max = maxOf(max, value); sum += value; squares += value * value
                    if (value < 0.0) below++; if (value > 1.0) above++; if (value == 0.0) zero++; if (value == 1.0) one++
                    val channel = index % 3
                    channelMin[channel] = minOf(channelMin[channel], value)
                    channelMax[channel] = maxOf(channelMax[channel], value)
                    channelSum[channel] += value; channelCount[channel]++
                }
            }
        }
        val mean = sum / finite.coerceAtLeast(1)
        val variance = (squares / finite.coerceAtLeast(1) - mean * mean).coerceAtLeast(0.0)
        val count = values.size.toDouble()
        return ImageSummary(
            values.size, finite, nan, positiveInfinity, negativeInfinity, min, max, mean, sqrt(variance),
            below * 100.0 / count, above * 100.0 / count, zero * 100.0 / count, one * 100.0 / count,
            List(3) { Triple(channelMin[it], channelMax[it], channelSum[it] / channelCount[it].coerceAtLeast(1)) },
        )
    }
}
