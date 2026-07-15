package dev.dblink.core.rawai

import kotlin.math.min

/** Canonical PR 3 image representation: contiguous linear RGB in HWC order. */
data class LinearRgbImage(
    val width: Int,
    val height: Int,
    val data: FloatArray,
) {
    init {
        require(width > 0 && height > 0) { "Invalid image dimensions: ${width}x$height" }
        val expected = checkedElements(width, height)
        require(data.size == expected) { "HWC buffer mismatch: expected=$expected actual=${data.size}" }
    }

    operator fun get(x: Int, y: Int, channel: Int): Float = data[(y * width + x) * CHANNELS + channel]

    companion object {
        const val CHANNELS = 3

        fun checkedElements(width: Int, height: Int): Int {
            require(width > 0 && height > 0) { "Invalid image dimensions: ${width}x$height" }
            val elements = Math.multiplyExact(Math.multiplyExact(width.toLong(), height.toLong()), CHANNELS.toLong())
            require(elements <= Int.MAX_VALUE) { "Image element count exceeds JVM array limit: $elements" }
            return elements.toInt()
        }
    }
}

enum class TilePadding { REFLECT, REPLICATE, ZERO }

enum class InferencePhase { PREPARING, INFERENCING, BLENDING, FINALIZING, COMPLETED, CANCELLED, FAILED }

data class TilingConfig(
    val tileSize: Int = 256,
    val overlap: Int = 32,
    val padding: TilePadding = TilePadding.REFLECT,
) {
    val stride: Int = tileSize - overlap

    init {
        require(tileSize > 0) { "Tile size must be positive: $tileSize" }
        require(overlap >= 0 && overlap < tileSize) { "Invalid overlap=$overlap for tileSize=$tileSize" }
    }
}

data class TileRegion(
    val index: Int,
    val gridX: Int,
    val gridY: Int,
    val originX: Int,
    val originY: Int,
    val validWidth: Int,
    val validHeight: Int,
    val padRight: Int,
    val padBottom: Int,
)

data class TilePlan(
    val imageWidth: Int,
    val imageHeight: Int,
    val config: TilingConfig,
    val originsX: IntArray,
    val originsY: IntArray,
    val tiles: List<TileRegion>,
) {
    val tileCountX: Int get() = originsX.size
    val tileCountY: Int get() = originsY.size
    val totalTiles: Int get() = tiles.size

    fun validate() {
        require(totalTiles == Math.multiplyExact(tileCountX, tileCountY)) { "Tile count mismatch" }
        require(tiles.map { it.originX to it.originY }.toSet().size == totalTiles) { "Duplicate tile origins" }
        validateAxis(originsX, imageWidth)
        validateAxis(originsY, imageHeight)
        tiles.forEachIndexed { expectedIndex, tile ->
            require(tile.index == expectedIndex) { "Non-deterministic tile index at $expectedIndex" }
            require(tile.originX >= 0 && tile.originY >= 0) { "Negative tile origin: $tile" }
            require(tile.originX < imageWidth && tile.originY < imageHeight) { "Out-of-range tile origin: $tile" }
            require(tile.validWidth in 1..config.tileSize && tile.validHeight in 1..config.tileSize) { "Invalid valid region: $tile" }
            require(tile.originX + tile.validWidth <= imageWidth && tile.originY + tile.validHeight <= imageHeight) {
                "Out-of-range source read: $tile"
            }
            require(tile.validWidth + tile.padRight == config.tileSize) { "Invalid horizontal padding: $tile" }
            require(tile.validHeight + tile.padBottom == config.tileSize) { "Invalid vertical padding: $tile" }
        }
    }

    private fun validateAxis(origins: IntArray, size: Int) {
        require(origins.isNotEmpty() && origins[0] == 0) { "Axis must start at zero" }
        var coveredUntil = 0
        origins.forEachIndexed { index, origin ->
            require(origin >= 0 && origin < size) { "Invalid axis origin=$origin size=$size" }
            if (index > 0) require(origin > origins[index - 1]) { "Axis origins must increase" }
            require(origin <= coveredUntil) { "Uncovered source interval before $origin (covered=$coveredUntil)" }
            coveredUntil = maxOf(coveredUntil, min(size, Math.addExact(origin, config.tileSize)))
        }
        require(coveredUntil == size) { "Axis coverage incomplete: covered=$coveredUntil size=$size" }
    }
}

object TilePlanner {
    fun create(width: Int, height: Int, config: TilingConfig): TilePlan {
        LinearRgbImage.checkedElements(width, height)
        val xOrigins = origins(width, config.stride, config.tileSize)
        val yOrigins = origins(height, config.stride, config.tileSize)
        val tiles = ArrayList<TileRegion>(Math.multiplyExact(xOrigins.size, yOrigins.size))
        var index = 0
        yOrigins.forEachIndexed { gridY, y ->
            xOrigins.forEachIndexed { gridX, x ->
                val validWidth = min(config.tileSize, width - x)
                val validHeight = min(config.tileSize, height - y)
                tiles += TileRegion(
                    index++, gridX, gridY, x, y, validWidth, validHeight,
                    config.tileSize - validWidth, config.tileSize - validHeight,
                )
            }
        }
        return TilePlan(width, height, config, xOrigins, yOrigins, tiles).also(TilePlan::validate)
    }

    private fun origins(size: Int, stride: Int, tileSize: Int): IntArray {
        val uncoveredAfterFirst = (size.toLong() - tileSize.toLong()).coerceAtLeast(0L)
        val count = if (uncoveredAfterFirst == 0L) 1L else ((uncoveredAfterFirst + stride - 1L) / stride + 1L)
        require(count <= Int.MAX_VALUE) { "Tile count overflow for size=$size stride=$stride" }
        return IntArray(count.toInt()) { Math.multiplyExact(it, stride) }
    }
}

object TileExtractor {
    fun extract(image: LinearRgbImage, tile: TileRegion, config: TilingConfig, destination: FloatArray) {
        val expected = Math.multiplyExact(Math.multiplyExact(config.tileSize, config.tileSize), LinearRgbImage.CHANNELS)
        require(destination.size == expected) { "Tile input buffer mismatch: expected=$expected actual=${destination.size}" }
        for (localY in 0 until config.tileSize) {
            val sourceY = paddedIndex(tile.originY + localY, image.height, config.padding)
            for (localX in 0 until config.tileSize) {
                val sourceX = paddedIndex(tile.originX + localX, image.width, config.padding)
                val destinationIndex = (localY * config.tileSize + localX) * LinearRgbImage.CHANNELS
                if (sourceX < 0 || sourceY < 0) {
                    destination[destinationIndex] = 0f
                    destination[destinationIndex + 1] = 0f
                    destination[destinationIndex + 2] = 0f
                } else {
                    val sourceIndex = (sourceY * image.width + sourceX) * LinearRgbImage.CHANNELS
                    destination[destinationIndex] = image.data[sourceIndex]
                    destination[destinationIndex + 1] = image.data[sourceIndex + 1]
                    destination[destinationIndex + 2] = image.data[sourceIndex + 2]
                }
            }
        }
    }

    internal fun paddedIndex(index: Int, size: Int, padding: TilePadding): Int = when (padding) {
        TilePadding.ZERO -> if (index in 0 until size) index else -1
        TilePadding.REPLICATE -> index.coerceIn(0, size - 1)
        TilePadding.REFLECT -> reflect(index, size)
    }

    private fun reflect(index: Int, size: Int): Int {
        if (size == 1) return 0
        val period = 2L * size - 2L
        var value = index.toLong() % period
        if (value < 0) value += period
        return if (value < size) value.toInt() else (period - value).toInt()
    }
}

object BlendWindow {
    /** Positive smoothstep ramps; paired overlap weights sum to one. */
    fun weight(local: Int, valid: Int, gridIndex: Int, gridCount: Int, overlap: Int): Float {
        if (overlap == 0) return 1f
        var result = 1.0
        if (gridIndex > 0 && local < overlap) result *= smoothstep((local + 1.0) / (overlap + 1.0))
        if (gridIndex + 1 < gridCount && local >= valid - overlap) {
            result *= smoothstep((valid - local).toDouble() / (overlap + 1.0))
        }
        return result.toFloat().coerceAtLeast(java.lang.Float.MIN_NORMAL)
    }

    private fun smoothstep(value: Double): Double {
        val t = value.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}

fun interface TileProcessor {
    /** Input is HWC float32; output must be native model NCHW float32. */
    fun process(tile: TileRegion, inputHwc: FloatArray, outputNchw: FloatArray)
}

fun interface CancellationSignal { fun isCancelled(): Boolean }

data class InferenceProgress(
    val completedTiles: Int,
    val totalTiles: Int,
    val fraction: Double,
    val currentTileIndex: Int,
    val tileX: Int,
    val tileY: Int,
    val phase: InferencePhase,
    val elapsedMillis: Long,
)

class FullImageCancellationException(message: String) : RuntimeException(message)

data class StageTimings(
    val extractionMillis: Double,
    val inferenceMillis: Double,
    val blendingMillis: Double,
    val finalizationMillis: Double,
    val totalMillis: Double,
    val tileInferenceMillis: List<Double>,
)

data class FullImageResult(val image: LinearRgbImage, val plan: TilePlan, val timings: StageTimings)

class FullImageInferenceEngine(private val maximumWorkingBytes: Long = 512L * 1024L * 1024L) {
    fun estimateWorkingBytes(width: Int, height: Int, tileSize: Int = 256): Long {
        val pixels = Math.multiplyExact(width.toLong(), height.toLong())
        val fullImageBuffers = Math.multiplyExact(pixels, 28L) // input + weighted output + scalar weights
        val tileElements = Math.multiplyExact(Math.multiplyExact(tileSize.toLong(), tileSize.toLong()), 3L)
        return Math.addExact(fullImageBuffers, Math.multiplyExact(tileElements, 8L)) // input and output tiles
    }

    fun process(
        input: LinearRgbImage,
        config: TilingConfig,
        processor: TileProcessor,
        cancellation: CancellationSignal = CancellationSignal { false },
        progress: (InferenceProgress) -> Unit = {},
    ): FullImageResult {
        val started = System.nanoTime()
        checkCancelled(cancellation, "before model preparation")
        val requiredBytes = estimateWorkingBytes(input.width, input.height, config.tileSize)
        require(requiredBytes <= maximumWorkingBytes) {
            "Estimated working set $requiredBytes exceeds configured limit $maximumWorkingBytes for ${input.width}x${input.height}"
        }
        val plan = TilePlanner.create(input.width, input.height, config)
        emit(progress, 0, plan, null, InferencePhase.PREPARING, started)
        val tileElements = config.tileSize * config.tileSize * LinearRgbImage.CHANNELS
        val tileInput = FloatArray(tileElements)
        val tileOutput = FloatArray(tileElements)
        val weightedOutput = FloatArray(input.data.size)
        val weights = FloatArray(input.width * input.height)
        var extractionNanos = 0L
        var inferenceNanos = 0L
        var blendingNanos = 0L
        var completedTiles = 0
        val tileInference = ArrayList<Double>(plan.totalTiles)

        try {
            plan.tiles.forEach { tile ->
                checkCancelled(cancellation, "before tile ${tile.index}")
                var mark = System.nanoTime()
                TileExtractor.extract(input, tile, config, tileInput)
                extractionNanos += System.nanoTime() - mark
                emit(progress, tile.index, plan, tile, InferencePhase.INFERENCING, started)

                mark = System.nanoTime()
                processor.process(tile, tileInput, tileOutput)
                val tileNanos = System.nanoTime() - mark
                inferenceNanos += tileNanos
                tileInference += tileNanos / 1_000_000.0
                checkCancelled(cancellation, "after tile ${tile.index}")

                mark = System.nanoTime()
                blend(tileOutput, weightedOutput, weights, tile, plan)
                blendingNanos += System.nanoTime() - mark
                completedTiles = tile.index + 1
                emit(progress, completedTiles, plan, tile, InferencePhase.BLENDING, started)
            }
            checkCancelled(cancellation, "before finalization")
            emit(progress, plan.totalTiles, plan, plan.tiles.last(), InferencePhase.FINALIZING, started)
            val finalizationStart = System.nanoTime()
            for (pixel in weights.indices) {
                val weight = weights[pixel]
                check(weight > 0f && weight.isFinite()) { "Zero or invalid blend weight at pixel=$pixel value=$weight" }
                val base = pixel * 3
                weightedOutput[base] /= weight
                weightedOutput[base + 1] /= weight
                weightedOutput[base + 2] /= weight
            }
            val finalizationNanos = System.nanoTime() - finalizationStart
            emit(progress, plan.totalTiles, plan, plan.tiles.last(), InferencePhase.COMPLETED, started)
            return FullImageResult(
                LinearRgbImage(input.width, input.height, weightedOutput),
                plan,
                StageTimings(
                    extractionNanos / 1e6, inferenceNanos / 1e6, blendingNanos / 1e6,
                    finalizationNanos / 1e6, (System.nanoTime() - started) / 1e6, tileInference,
                ),
            )
        } catch (cancelled: FullImageCancellationException) {
            emit(progress, completedTiles, plan, null, InferencePhase.CANCELLED, started)
            throw cancelled
        } catch (failure: Throwable) {
            emit(progress, completedTiles, plan, null, InferencePhase.FAILED, started)
            throw failure
        }
    }

    private fun blend(
        outputNchw: FloatArray,
        weightedOutput: FloatArray,
        weights: FloatArray,
        tile: TileRegion,
        plan: TilePlan,
    ) {
        val tileSize = plan.config.tileSize
        val plane = tileSize * tileSize
        for (localY in 0 until tile.validHeight) {
            val wy = BlendWindow.weight(localY, tile.validHeight, tile.gridY, plan.tileCountY, plan.config.overlap)
            for (localX in 0 until tile.validWidth) {
                val wx = BlendWindow.weight(localX, tile.validWidth, tile.gridX, plan.tileCountX, plan.config.overlap)
                val weight = wx * wy
                val destinationPixel = (tile.originY + localY) * plan.imageWidth + tile.originX + localX
                val destinationBase = destinationPixel * 3
                val sourcePixel = localY * tileSize + localX
                weightedOutput[destinationBase] += outputNchw[sourcePixel] * weight
                weightedOutput[destinationBase + 1] += outputNchw[plane + sourcePixel] * weight
                weightedOutput[destinationBase + 2] += outputNchw[2 * plane + sourcePixel] * weight
                weights[destinationPixel] += weight
            }
        }
    }

    private fun checkCancelled(signal: CancellationSignal, location: String) {
        if (signal.isCancelled()) throw FullImageCancellationException("Full-image inference cancelled $location")
    }

    private fun emit(
        callback: (InferenceProgress) -> Unit,
        completed: Int,
        plan: TilePlan,
        tile: TileRegion?,
        phase: InferencePhase,
        started: Long,
    ) {
        val fraction = when (phase) {
            InferencePhase.COMPLETED -> 1.0
            InferencePhase.FINALIZING -> 0.999999
            InferencePhase.CANCELLED, InferencePhase.FAILED -> completed.toDouble() / plan.totalTiles
            else -> (completed.toDouble() / plan.totalTiles).coerceIn(0.0, 0.999998)
        }
        callback(
            InferenceProgress(
                completed, plan.totalTiles, fraction, tile?.index ?: -1, tile?.originX ?: -1,
                tile?.originY ?: -1, phase, (System.nanoTime() - started) / 1_000_000L,
            ),
        )
    }
}

object SyntheticTileProcessors {
    val identity = TileProcessor { _, input, output ->
        val pixels = input.size / 3
        for (pixel in 0 until pixels) {
            output[pixel] = input[pixel * 3]
            output[pixels + pixel] = input[pixel * 3 + 1]
            output[2 * pixels + pixel] = input[pixel * 3 + 2]
        }
    }

    fun constant(value: Float) = TileProcessor { _, _, output -> output.fill(value) }

    val edgeSensitive = TileProcessor { _, input, output ->
        val size = 256
        val plane = size * size
        for (y in 0 until size) for (x in 0 until size) {
            val pixel = y * size + x
            val edge = minOf(x, y, size - 1 - x, size - 1 - y) / 255f
            for (channel in 0..2) output[channel * plane + pixel] = input[pixel * 3 + channel] + edge * 0.01f
        }
    }

    val localCoordinates = TileProcessor { _, _, output ->
        val size = 256
        val plane = size * size
        for (y in 0 until size) for (x in 0 until size) {
            val pixel = y * size + x
            output[pixel] = x.toFloat()
            output[plane + pixel] = y.toFloat()
            output[2 * plane + pixel] = (x + y).toFloat()
        }
    }
}
