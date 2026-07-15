package dev.dblink.core.rawai

/** Receives one normalized HWC row. Implementations must copy it before returning if it is retained. */
fun interface StreamingRowSink {
    fun write(rowIndex: Int, rowHwc: FloatArray)
}

data class StreamingInferenceResult(
    val plan: TilePlan,
    val rowsEmitted: Int,
    val peakRetainedRows: Int,
    val estimatedReconstructionBytes: Long,
    val timings: StageTimings,
)

/**
 * PR 4 bounded-memory oracle-preserving reconstruction.
 *
 * Tiles retain the PR 3 row-major order. After a complete tile row, every output row before the
 * next tile-row origin is final: no future tile can cover it. Only [TilingConfig.tileSize] rows of
 * weighted RGB and scalar weights are therefore retained, independent of image height.
 */
class StreamingFullImageInferenceEngine {
    fun estimateReconstructionBytes(width: Int, tileSize: Int = 256): Long {
        require(width > 0 && tileSize > 0)
        val accumulator = Math.multiplyExact(Math.multiplyExact(width.toLong(), tileSize.toLong()), 16L)
        val tileElements = Math.multiplyExact(Math.multiplyExact(tileSize.toLong(), tileSize.toLong()), 3L)
        return Math.addExact(accumulator, Math.multiplyExact(tileElements, 8L))
    }

    fun process(
        input: LinearRgbImage,
        config: TilingConfig,
        processor: TileProcessor,
        sink: StreamingRowSink,
        cancellation: CancellationSignal = CancellationSignal { false },
        progress: (InferenceProgress) -> Unit = {},
    ): StreamingInferenceResult {
        val started = System.nanoTime()
        checkCancelled(cancellation, "before model preparation")
        val plan = TilePlanner.create(input.width, input.height, config)
        emit(progress, 0, plan, null, InferencePhase.PREPARING, started)

        val tileElements = Math.multiplyExact(Math.multiplyExact(config.tileSize, config.tileSize), 3)
        val tileInput = FloatArray(tileElements)
        val tileOutput = FloatArray(tileElements)
        val weightedRows = FloatArray(Math.multiplyExact(Math.multiplyExact(input.width, config.tileSize), 3))
        val weightRows = FloatArray(Math.multiplyExact(input.width, config.tileSize))
        val normalizedRow = FloatArray(Math.multiplyExact(input.width, 3))
        var baseY = 0
        var retainedEndY = 0
        var rowsEmitted = 0
        var peakRetainedRows = 0
        var completedTiles = 0
        var extractionNanos = 0L
        var inferenceNanos = 0L
        var blendingNanos = 0L
        var finalizationNanos = 0L
        val tileInference = ArrayList<Double>(plan.totalTiles)

        try {
            for (gridY in 0 until plan.tileCountY) {
                val originY = plan.originsY[gridY]
                check(baseY == originY) { "Invalid streaming finalization order: baseY=$baseY originY=$originY" }
                for (gridX in 0 until plan.tileCountX) {
                    val tile = plan.tiles[gridY * plan.tileCountX + gridX]
                    checkCancelled(cancellation, "before tile ${tile.index}")
                    var mark = System.nanoTime()
                    TileExtractor.extract(input, tile, config, tileInput)
                    extractionNanos += System.nanoTime() - mark
                    emit(progress, completedTiles, plan, tile, InferencePhase.INFERENCING, started)

                    mark = System.nanoTime()
                    processor.process(tile, tileInput, tileOutput)
                    val elapsed = System.nanoTime() - mark
                    inferenceNanos += elapsed
                    tileInference += elapsed / 1e6
                    checkCancelled(cancellation, "after tile ${tile.index}")

                    mark = System.nanoTime()
                    blend(tileOutput, weightedRows, weightRows, tile, plan, baseY)
                    blendingNanos += System.nanoTime() - mark
                    retainedEndY = maxOf(retainedEndY, tile.originY + tile.validHeight)
                    peakRetainedRows = maxOf(peakRetainedRows, retainedEndY - baseY)
                    check(peakRetainedRows <= config.tileSize) {
                        "Streaming row bound exceeded: peak=$peakRetainedRows tileSize=${config.tileSize}"
                    }
                    completedTiles++
                    emit(progress, completedTiles, plan, tile, InferencePhase.BLENDING, started)
                }

                checkCancelled(cancellation, "before row finalization $gridY")
                val finalizeExclusive = if (gridY + 1 < plan.tileCountY) plan.originsY[gridY + 1] else input.height
                check(finalizeExclusive in baseY..retainedEndY) {
                    "Invalid finalization range: base=$baseY end=$retainedEndY finalize=$finalizeExclusive"
                }
                val mark = System.nanoTime()
                while (baseY < finalizeExclusive) {
                    normalizeFirstRow(input.width, weightedRows, weightRows, normalizedRow, baseY)
                    sink.write(baseY, normalizedRow)
                    rowsEmitted++
                    discardFirstRow(input.width, weightedRows, weightRows, retainedEndY - baseY)
                    baseY++
                }
                finalizationNanos += System.nanoTime() - mark
            }

            checkCancelled(cancellation, "after finalization")
            check(rowsEmitted == input.height && baseY == input.height) {
                "Streaming output incomplete: rows=$rowsEmitted base=$baseY height=${input.height}"
            }
            emit(progress, plan.totalTiles, plan, plan.tiles.last(), InferencePhase.COMPLETED, started)
            return StreamingInferenceResult(
                plan,
                rowsEmitted,
                peakRetainedRows,
                estimateReconstructionBytes(input.width, config.tileSize),
                StageTimings(
                    extractionNanos / 1e6,
                    inferenceNanos / 1e6,
                    blendingNanos / 1e6,
                    finalizationNanos / 1e6,
                    (System.nanoTime() - started) / 1e6,
                    tileInference,
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
        weightedRows: FloatArray,
        weightRows: FloatArray,
        tile: TileRegion,
        plan: TilePlan,
        baseY: Int,
    ) {
        val tileSize = plan.config.tileSize
        val plane = tileSize * tileSize
        for (localY in 0 until tile.validHeight) {
            val row = tile.originY + localY - baseY
            check(row in 0 until tileSize) { "Tile row outside streaming window: row=$row tile=$tile baseY=$baseY" }
            val wy = BlendWindow.weight(localY, tile.validHeight, tile.gridY, plan.tileCountY, plan.config.overlap)
            for (localX in 0 until tile.validWidth) {
                val wx = BlendWindow.weight(localX, tile.validWidth, tile.gridX, plan.tileCountX, plan.config.overlap)
                val weight = wx * wy
                val destinationPixel = row * plan.imageWidth + tile.originX + localX
                val destinationBase = destinationPixel * 3
                val sourcePixel = localY * tileSize + localX
                weightedRows[destinationBase] += outputNchw[sourcePixel] * weight
                weightedRows[destinationBase + 1] += outputNchw[plane + sourcePixel] * weight
                weightedRows[destinationBase + 2] += outputNchw[2 * plane + sourcePixel] * weight
                weightRows[destinationPixel] += weight
            }
        }
    }

    private fun normalizeFirstRow(
        width: Int,
        weightedRows: FloatArray,
        weightRows: FloatArray,
        destination: FloatArray,
        globalY: Int,
    ) {
        for (x in 0 until width) {
            val weight = weightRows[x]
            check(weight > 0f && weight.isFinite()) { "Zero or invalid blend weight at x=$x y=$globalY value=$weight" }
            val base = x * 3
            destination[base] = weightedRows[base] / weight
            destination[base + 1] = weightedRows[base + 1] / weight
            destination[base + 2] = weightedRows[base + 2] / weight
        }
    }

    private fun discardFirstRow(width: Int, weightedRows: FloatArray, weightRows: FloatArray, retainedRows: Int) {
        val weightedRowElements = width * 3
        if (retainedRows > 1) {
            weightedRows.copyInto(weightedRows, 0, weightedRowElements, retainedRows * weightedRowElements)
            weightRows.copyInto(weightRows, 0, width, retainedRows * width)
        }
        weightedRows.fill(0f, (retainedRows - 1).coerceAtLeast(0) * weightedRowElements, retainedRows * weightedRowElements)
        weightRows.fill(0f, (retainedRows - 1).coerceAtLeast(0) * width, retainedRows * width)
    }

    private fun checkCancelled(signal: CancellationSignal, location: String) {
        if (signal.isCancelled()) throw FullImageCancellationException("Streaming inference cancelled $location")
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
            InferencePhase.CANCELLED, InferencePhase.FAILED -> completed.toDouble() / plan.totalTiles
            else -> (completed.toDouble() / plan.totalTiles).coerceIn(0.0, 0.999999)
        }
        callback(
            InferenceProgress(
                completed,
                plan.totalTiles,
                fraction,
                tile?.index ?: -1,
                tile?.originX ?: -1,
                tile?.originY ?: -1,
                phase,
                (System.nanoTime() - started) / 1_000_000L,
            ),
        )
    }
}
