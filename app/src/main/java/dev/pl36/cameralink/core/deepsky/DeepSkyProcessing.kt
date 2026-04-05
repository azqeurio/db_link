package dev.pl36.cameralink.core.deepsky

import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.createBitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

open class StarDetector(
    private val tileSize: Int = 32,
    private val sigmaThreshold: Float = 2.8f,
    private val supportSigmaThreshold: Float = 1.2f,
    private val minSupportPixels: Int = 3,
    private val minFootprintRadiusPx: Float = 0.45f,
    private val minSeparationPx: Float = 8f,
    private val maxStars: Int = 64,
    private val maxMatchingElongation: Float = 1.6f,
) {
    open fun detect(
        luma: FloatArray,
        width: Int,
        height: Int,
    ): List<DetectedStar> {
        if (width < 5 || height < 5) return emptyList()
        val candidates = ArrayList<DetectedStar>(maxStars * 3)
        val tileColumns = ((width + tileSize - 1) / tileSize).coerceAtLeast(1)
        val tileRows = ((height + tileSize - 1) / tileSize).coerceAtLeast(1)
        val tileMean = FloatArray(tileColumns * tileRows)
        val tileSigma = FloatArray(tileColumns * tileRows)

        for (tileY in 0 until tileRows) {
            val startY = tileY * tileSize
            val endY = min(height, startY + tileSize)
            for (tileX in 0 until tileColumns) {
                val startX = tileX * tileSize
                val endX = min(width, startX + tileSize)
                var sum = 0.0
                var sumSq = 0.0
                var count = 0
                for (y in startY until endY) {
                    val rowOffset = y * width
                    for (x in startX until endX) {
                        val value = luma[rowOffset + x].toDouble()
                        sum += value
                        sumSq += value * value
                        count += 1
                    }
                }
                val mean = if (count == 0) 0f else (sum / count).toFloat()
                val variance = if (count == 0) 0f else ((sumSq / count) - (mean * mean)).toFloat().coerceAtLeast(0f)
                val index = tileY * tileColumns + tileX
                tileMean[index] = mean
                tileSigma[index] = sqrt(variance).coerceAtLeast(1f)
            }
        }

        for (y in 2 until height - 2) {
            val tileY = min(tileRows - 1, y / tileSize)
            val rowOffset = y * width
            for (x in 2 until width - 2) {
                val tileX = min(tileColumns - 1, x / tileSize)
                val tileIndex = tileY * tileColumns + tileX
                val background = tileMean[tileIndex]
                val sigma = tileSigma[tileIndex]
                val center = luma[rowOffset + x]
                if (center < background + sigma * sigmaThreshold) continue
                if (!isLocalMaximum(luma, width, x, y, center)) continue
                val footprint = evaluateFootprint(
                    luma = luma,
                    width = width,
                    height = height,
                    centerX = x,
                    centerY = y,
                    supportThreshold = background + sigma * supportSigmaThreshold,
                )
                if (footprint.supportPixels < minSupportPixels) continue
                if (footprint.weightedRadiusPx < minFootprintRadiusPx) continue
                val star = centroidAndShape(
                    luma = luma,
                    width = width,
                    height = height,
                    centerX = x,
                    centerY = y,
                    background = background,
                    sigma = sigma,
                )
                if (star.flux <= 0f) continue
                candidates += star
            }
        }

        val stable = candidates
            .sortedByDescending { it.matchingRank }
            .fold(ArrayList<DetectedStar>(maxStars)) { chosen, star ->
                if (chosen.size >= maxStars) return@fold chosen
                if (chosen.none { hypot((it.x - star.x).toDouble(), (it.y - star.y).toDouble()) < minSeparationPx }) {
                    chosen += star
                }
                chosen
            }

        return enrichIsolation(stable)
    }

    private fun isLocalMaximum(
        luma: FloatArray,
        width: Int,
        x: Int,
        y: Int,
        center: Float,
    ): Boolean {
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                if (luma[(y + dy) * width + (x + dx)] >= center) return false
            }
        }
        return true
    }

    private fun evaluateFootprint(
        luma: FloatArray,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int,
        supportThreshold: Float,
    ): CandidateFootprint {
        var supportPixels = 0
        var weightedDistanceSum = 0f
        var totalWeight = 0f
        for (y in max(0, centerY - 1)..min(height - 1, centerY + 1)) {
            for (x in max(0, centerX - 1)..min(width - 1, centerX + 1)) {
                val sample = luma[y * width + x]
                if (sample < supportThreshold) continue
                supportPixels += 1
                val weight = sample - supportThreshold
                val dx = (x - centerX).toFloat()
                val dy = (y - centerY).toFloat()
                weightedDistanceSum += (dx * dx + dy * dy) * weight
                totalWeight += weight
            }
        }
        val weightedRadiusPx = if (totalWeight <= 0f) 0f else sqrt(weightedDistanceSum / totalWeight)
        return CandidateFootprint(
            supportPixels = supportPixels,
            weightedRadiusPx = weightedRadiusPx,
        )
    }

    private fun centroidAndShape(
        luma: FloatArray,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int,
        background: Float,
        sigma: Float,
    ): DetectedStar {
        var weightedX = 0f
        var weightedY = 0f
        var total = 0f
        var peak = 0f
        val samples = ArrayList<Triple<Float, Float, Float>>(25)
        for (y in max(0, centerY - 2)..min(height - 1, centerY + 2)) {
            for (x in max(0, centerX - 2)..min(width - 1, centerX + 2)) {
                val sample = (luma[y * width + x] - background).coerceAtLeast(0f)
                if (sample <= 0f) continue
                total += sample
                weightedX += x * sample
                weightedY += y * sample
                peak = max(peak, sample)
                samples += Triple(x.toFloat(), y.toFloat(), sample)
            }
        }
        if (total <= 0f) {
            return DetectedStar(centerX.toFloat(), centerY.toFloat(), 0f)
        }
        val centroidX = weightedX / total
        val centroidY = weightedY / total
        var xx = 0f
        var yy = 0f
        var xy = 0f
        for ((x, y, sample) in samples) {
            val dx = x - centroidX
            val dy = y - centroidY
            xx += dx * dx * sample
            yy += dy * dy * sample
            xy += dx * dy * sample
        }
        xx /= total
        yy /= total
        xy /= total
        val trace = xx + yy
        val determinant = (xx * yy) - (xy * xy)
        val discriminant = sqrt((trace * trace / 4f - determinant).coerceAtLeast(0f))
        val major = max(0.01f, trace / 2f + discriminant)
        val minor = max(0.01f, trace / 2f - discriminant)
        val sigmaMean = sqrt((major + minor) / 2f)
        val fwhm = 2.355f * sigmaMean
        val elongation = sqrt(major / minor).coerceAtLeast(1f)
        val localContrast = (peak / sigma.coerceAtLeast(1f)).coerceAtLeast(0f)
        return DetectedStar(
            x = centroidX,
            y = centroidY,
            flux = total,
            peak = peak,
            localContrast = localContrast,
            fwhmPx = fwhm,
            elongation = elongation,
            isolationScore = 0f,
            usableForMatching = elongation <= maxMatchingElongation,
        )
    }

    private fun enrichIsolation(stars: List<DetectedStar>): List<DetectedStar> {
        if (stars.isEmpty()) return stars
        return stars.mapIndexed { index, star ->
            val nearest = stars
                .filterIndexed { otherIndex, _ -> otherIndex != index }
                .map { hypot((it.x - star.x).toDouble(), (it.y - star.y).toDouble()).toFloat() }
                .minOrNull() ?: (minSeparationPx * 2f)
            val isolation = (nearest / (minSeparationPx * 2f)).coerceIn(0f, 1.5f)
            star.copy(
                isolationScore = isolation,
                usableForMatching = star.usableForMatching && isolation >= 0.35f && star.localContrast >= 3.0f,
            )
        }
    }

    private data class CandidateFootprint(
        val supportPixels: Int,
        val weightedRadiusPx: Float,
    )
}

open class FrameRegistrar {
    open fun register(
        referenceStars: List<DetectedStar>,
        currentStars: List<DetectedStar>,
        registrationPolicy: DeepSkyRegistrationPolicy,
        alignmentWidth: Int,
        alignmentHeight: Int,
        rotationHintDeg: Float? = null,
        referenceFrameId: String? = null,
        usedFallbackPath: Boolean = false,
    ): FrameRegistrationResult {
        val referencePool = referenceStars.filter { it.usableForMatching }.sortedByDescending { it.matchingRank }
        val currentPool = currentStars.filter { it.usableForMatching }.sortedByDescending { it.matchingRank }
        if (referencePool.size < registrationPolicy.minStars || currentPool.size < registrationPolicy.minStars) {
            return FrameRegistrationResult(
                success = false,
                transform = RegistrationTransform.Identity,
                metrics = FrameRegistrationMetrics(
                    matchedStars = 0,
                    confidenceScore = 0f,
                    usedFallbackPath = usedFallbackPath,
                    referenceFrameId = referenceFrameId,
                    transformModel = registrationPolicy.preferredTransformModel,
                ),
                reason = DeepSkyRejectionReason.TooFewStars,
                debugMessage = "Not enough usable stars for registration",
            )
        }

        val topReference = referencePool.take(registrationPolicy.topStarsForVoting)
        val topCurrent = currentPool.take(registrationPolicy.topStarsForVoting)
        val referenceSignatures = computeStarSignatures(topReference)
        val currentSignatures = computeStarSignatures(topCurrent)
        val maxShiftPx = registrationPolicy.maxShiftPxFor(alignmentWidth)
        val rotationCandidates = linkedSetOf<Float>()
        rotationCandidates += 0f
        rotationHintDeg?.let { rotationCandidates += it }

        for (i in 0 until topReference.size) {
            for (j in i + 1 until topReference.size) {
                val refA = topReference[i]
                val refB = topReference[j]
                val refDistance = distance(refA, refB)
                if (refDistance < registrationPolicy.coarsePairDistanceMinPx) continue
                val refAngle = angleDeg(refA, refB)
                for (m in 0 until topCurrent.size) {
                    for (n in m + 1 until topCurrent.size) {
                        val curA = topCurrent[m]
                        val curB = topCurrent[n]
                        if (!areStarsLocallyCompatible(refA, curA, referenceSignatures[i], currentSignatures[m])) continue
                        if (!areStarsLocallyCompatible(refB, curB, referenceSignatures[j], currentSignatures[n])) continue
                        val curDistance = distance(curA, curB)
                        if (curDistance < registrationPolicy.coarsePairDistanceMinPx) continue
                        val ratio = refDistance / curDistance
                        if (ratio !in 0.85f..1.15f) continue
                        val delta = normalizeDegrees(refAngle - angleDeg(curA, curB))
                        if (abs(delta) <= registrationPolicy.maxRotationDeg * 1.5f) {
                            rotationCandidates += (delta * 10f).roundToInt() / 10f
                        }
                    }
                }
            }
        }

        var bestRigid: CandidateEvaluation? = null
        val centerX = (alignmentWidth - 1) / 2f
        val centerY = (alignmentHeight - 1) / 2f
        for (rotation in rotationCandidates.take(registrationPolicy.maxRotationHypotheses)) {
            val coarse = generateTranslationCandidates(
                referenceStars = topReference,
                currentStars = topCurrent,
                referenceSignatures = referenceSignatures,
                currentSignatures = currentSignatures,
                rotationDeg = rotation,
                centerX = centerX,
                centerY = centerY,
                maxShiftPx = maxShiftPx,
                binSizePx = registrationPolicy.candidateBinSizePx,
            )
            for (candidate in coarse) {
                val rigid = evaluateTransform(
                    referenceStars = referencePool,
                    currentStars = currentPool,
                    transform = candidate,
                    alignmentWidth = alignmentWidth,
                    alignmentHeight = alignmentHeight,
                    matchRadiusPx = registrationPolicy.matchRadiusPx,
                    referenceFrameId = referenceFrameId,
                    usedFallbackPath = usedFallbackPath,
                )
                val refined = refineRigidTransform(
                    evaluation = rigid,
                    alignmentWidth = alignmentWidth,
                    alignmentHeight = alignmentHeight,
                    matchRadiusPx = registrationPolicy.matchRadiusPx,
                    referenceFrameId = referenceFrameId,
                    usedFallbackPath = usedFallbackPath,
                )
                if (bestRigid == null || refined.metrics.confidenceScore > bestRigid.metrics.confidenceScore) {
                    bestRigid = refined
                }
            }
        }

        val rigidCandidate = bestRigid ?: CandidateEvaluation(
            transform = RegistrationTransform.Identity,
            metrics = FrameRegistrationMetrics(
                matchedStars = 0,
                confidenceScore = 0f,
                usedFallbackPath = usedFallbackPath,
                referenceFrameId = referenceFrameId,
            ),
            matchedPairs = emptyList(),
        )

        val best = chooseBestModel(
            rigidCandidate = rigidCandidate,
            registrationPolicy = registrationPolicy,
            alignmentWidth = alignmentWidth,
            alignmentHeight = alignmentHeight,
            matchRadiusPx = registrationPolicy.matchRadiusPx,
            referenceFrameId = referenceFrameId,
            usedFallbackPath = usedFallbackPath,
        )

        val finalMetrics = best.metrics.copy(
            usedFallbackPath = usedFallbackPath,
            referenceFrameId = referenceFrameId,
        )
        val finalResult = FrameRegistrationResult(
            success = best.metrics.matchedStars > 0,
            transform = best.transform,
            metrics = finalMetrics,
            debugMessage = "matched=${best.metrics.matchedStars} confidence=${"%.3f".format(best.metrics.confidenceScore)} residual=${"%.2f".format(best.metrics.residualPx)} " +
                "dx=${"%.2f".format(best.transform.dx)} dy=${"%.2f".format(best.transform.dy)} rot=${"%.2f".format(best.transform.rotationDeg)} model=${best.transform.model}",
        )

        return when {
            finalMetrics.matchedStars < registrationPolicy.minMatches ->
                finalResult.copy(success = false, reason = DeepSkyRejectionReason.RegistrationLowConfidence)
            finalMetrics.inlierRatio < registrationPolicy.minInlierRatio ->
                finalResult.copy(success = false, reason = DeepSkyRejectionReason.RegistrationLowConfidence)
            finalMetrics.residualPx > registrationPolicy.maxResidualPx ->
                finalResult.copy(success = false, reason = DeepSkyRejectionReason.RegistrationLowConfidence)
            finalMetrics.confidenceScore < registrationPolicy.minScore ->
                finalResult.copy(success = false, reason = DeepSkyRejectionReason.RegistrationLowConfidence)
            !registrationPolicy.isTransformPlausible(best.transform, alignmentWidth, usedFallbackPath) ->
                finalResult.copy(success = false, reason = DeepSkyRejectionReason.ImplausibleTransform)
            else -> finalResult.copy(success = true)
        }
    }

    private fun chooseBestModel(
        rigidCandidate: CandidateEvaluation,
        registrationPolicy: DeepSkyRegistrationPolicy,
        alignmentWidth: Int,
        alignmentHeight: Int,
        matchRadiusPx: Float,
        referenceFrameId: String?,
        usedFallbackPath: Boolean,
    ): CandidateEvaluation {
        if (!registrationPolicy.allowAffineFallback) {
            return rigidCandidate
        }
        if (rigidCandidate.matchedPairs.size < 3) {
            return rigidCandidate
        }

        val affineTransform = solveAffineTransform(rigidCandidate.matchedPairs) ?: return rigidCandidate
        val affineCandidate = evaluateTransform(
            referenceStars = rigidCandidate.matchedPairs.map { it.referenceStar },
            currentStars = rigidCandidate.matchedPairs.map { it.currentStar },
            transform = affineTransform,
            alignmentWidth = alignmentWidth,
            alignmentHeight = alignmentHeight,
            matchRadiusPx = matchRadiusPx,
            referenceFrameId = referenceFrameId,
            usedFallbackPath = usedFallbackPath,
        )
        if (!registrationPolicy.isTransformPlausible(affineCandidate.transform, alignmentWidth, usedFallbackPath)) {
            return rigidCandidate
        }
        val rigidResidual = rigidCandidate.metrics.residualPx.coerceAtLeast(0.001f)
        val affineResidual = affineCandidate.metrics.residualPx
        val affineImprovement = (rigidResidual - affineResidual) / rigidResidual
        return when (registrationPolicy.preferredTransformModel) {
            DeepSkyTransformModel.Affine -> {
                if (affineImprovement >= 0.20f && affineCandidate.metrics.confidenceScore >= rigidCandidate.metrics.confidenceScore * 0.95f) {
                    affineCandidate
                } else {
                    rigidCandidate
                }
            }
            DeepSkyTransformModel.Rigid -> {
                if (rigidCandidate.metrics.confidenceScore < registrationPolicy.minScore &&
                    affineCandidate.metrics.confidenceScore > rigidCandidate.metrics.confidenceScore &&
                    affineImprovement >= 0.20f
                ) {
                    affineCandidate
                } else {
                    rigidCandidate
                }
            }
        }
    }

    private fun generateTranslationCandidates(
        referenceStars: List<DetectedStar>,
        currentStars: List<DetectedStar>,
        referenceSignatures: List<FloatArray>,
        currentSignatures: List<FloatArray>,
        rotationDeg: Float,
        centerX: Float,
        centerY: Float,
        maxShiftPx: Float,
        binSizePx: Float,
    ): List<RegistrationTransform> {
        val rotatedCurrent = currentStars.map { rotateStar(it, rotationDeg, centerX, centerY) }
        val translationVotes = LinkedHashMap<Pair<Int, Int>, MutableList<Pair<Float, Float>>>()
        for (refIndex in referenceStars.indices) {
            val ref = referenceStars[refIndex]
            val refSignature = referenceSignatures[refIndex]
            for (curIndex in rotatedCurrent.indices) {
                val cur = rotatedCurrent[curIndex]
                if (!areStarsLocallyCompatible(referenceStars[refIndex], currentStars[curIndex], refSignature, currentSignatures[curIndex])) {
                    continue
                }
                val dx = ref.x - cur.x
                val dy = ref.y - cur.y
                if (abs(dx) > maxShiftPx || abs(dy) > maxShiftPx) continue
                val key = Pair((dx / binSizePx).roundToInt(), (dy / binSizePx).roundToInt())
                translationVotes.getOrPut(key) { ArrayList() } += Pair(dx, dy)
            }
        }
        return translationVotes.entries
            .sortedByDescending { it.value.size }
            .take(6)
            .map { entry ->
                val dx = entry.value.map { it.first }.sorted().median()
                val dy = entry.value.map { it.second }.sorted().median()
                createRigidTransform(dx, dy, rotationDeg)
            }
    }

    private fun evaluateTransform(
        referenceStars: List<DetectedStar>,
        currentStars: List<DetectedStar>,
        transform: RegistrationTransform,
        alignmentWidth: Int,
        alignmentHeight: Int,
        matchRadiusPx: Float,
        referenceFrameId: String?,
        usedFallbackPath: Boolean,
    ): CandidateEvaluation {
        val centerX = (alignmentWidth - 1) / 2f
        val centerY = (alignmentHeight - 1) / 2f
        val transformedCurrent = currentStars.map { star ->
            applyTransform(star, transform, centerX, centerY)
        }
        val referenceSignatures = computeStarSignatures(referenceStars)
        val currentSignatures = computeStarSignatures(currentStars)
        val usedReference = BooleanArray(referenceStars.size)
        val matches = ArrayList<MatchedPair>(min(referenceStars.size, currentStars.size))
        var residualSum = 0.0
        for (currentIndex in transformedCurrent.indices) {
            val current = transformedCurrent[currentIndex]
            var bestIndex = -1
            var bestDistance = Double.MAX_VALUE
            for (referenceIndex in referenceStars.indices) {
                if (usedReference[referenceIndex]) continue
                if (!areStarsLocallyCompatible(
                        referenceStars[referenceIndex],
                        currentStars[currentIndex],
                        referenceSignatures[referenceIndex],
                        currentSignatures[currentIndex],
                    )
                ) {
                    continue
                }
                val reference = referenceStars[referenceIndex]
                val distance = hypot(
                    (reference.x - current.x).toDouble(),
                    (reference.y - current.y).toDouble(),
                )
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestIndex = referenceIndex
                }
            }
            if (bestIndex >= 0 && bestDistance <= matchRadiusPx) {
                usedReference[bestIndex] = true
                residualSum += bestDistance
                matches += MatchedPair(
                    referenceStar = referenceStars[bestIndex],
                    currentStar = currentStars[currentIndex],
                    transformedCurrentStar = current,
                    residualPx = bestDistance.toFloat(),
                )
            }
        }
        val matched = matches.size
        val inlierRatio = matched.toFloat() / max(referenceStars.size, currentStars.size).toFloat()
        val residual = if (matched == 0) Float.MAX_VALUE else (residualSum / matched).toFloat()
        val matchedRatio = matched.toFloat() / max(1, min(referenceStars.size, currentStars.size)).toFloat()
        val residualScore = if (residual == Float.MAX_VALUE) 0f else (1f - (residual / matchRadiusPx).coerceAtMost(1f))
        val confidence = (
            (matchedRatio * 0.35f) +
                (inlierRatio * 0.35f) +
                (residualScore * 0.25f) +
                (if (transform.model == DeepSkyTransformModel.Affine) 0.05f else 0.08f)
            ).coerceIn(0f, 1f)
        val metrics = FrameRegistrationMetrics(
            matchedStars = matched,
            inlierRatio = inlierRatio,
            residualPx = if (residual == Float.MAX_VALUE) 0f else residual,
            rotationDeg = transform.rotationDeg,
            translationMagnitudePx = transform.translationMagnitude(),
            scaleX = transform.scaleX,
            scaleY = transform.scaleY,
            shear = transform.shear,
            confidenceScore = confidence,
            usedFallbackPath = usedFallbackPath,
            referenceFrameId = referenceFrameId,
            transformModel = transform.model,
        )
        return CandidateEvaluation(
            transform = transform,
            metrics = metrics,
            matchedPairs = matches,
        )
    }

    private fun refineRigidTransform(
        evaluation: CandidateEvaluation,
        alignmentWidth: Int,
        alignmentHeight: Int,
        matchRadiusPx: Float,
        referenceFrameId: String?,
        usedFallbackPath: Boolean,
    ): CandidateEvaluation {
        if (evaluation.matchedPairs.size < 3 || evaluation.transform.model != DeepSkyTransformModel.Rigid) {
            return evaluation
        }
        val centerX = (alignmentWidth - 1) / 2f
        val centerY = (alignmentHeight - 1) / 2f
        val angleDeltas = evaluation.matchedPairs.map { pair ->
            val currentAngle = atan2(
                (pair.currentStar.y - centerY).toDouble(),
                (pair.currentStar.x - centerX).toDouble(),
            ).toFloat()
            val referenceAngle = atan2(
                (pair.referenceStar.y - centerY).toDouble(),
                (pair.referenceStar.x - centerX).toDouble(),
            ).toFloat()
            normalizeDegrees(Math.toDegrees((referenceAngle - currentAngle).toDouble()).toFloat())
        }
        val refinedRotation = median(angleDeltas)
        val rotatedCurrent = evaluation.matchedPairs.map {
            rotateStar(it.currentStar, refinedRotation, centerX, centerY)
        }
        val dx = evaluation.matchedPairs.indices.map { index ->
            evaluation.matchedPairs[index].referenceStar.x - rotatedCurrent[index].x
        }.sorted().median()
        val dy = evaluation.matchedPairs.indices.map { index ->
            evaluation.matchedPairs[index].referenceStar.y - rotatedCurrent[index].y
        }.sorted().median()
        val refinedTransform = createRigidTransform(dx, dy, refinedRotation)
        return evaluateTransform(
            referenceStars = evaluation.matchedPairs.map { it.referenceStar },
            currentStars = evaluation.matchedPairs.map { it.currentStar },
            transform = refinedTransform,
            alignmentWidth = alignmentWidth,
            alignmentHeight = alignmentHeight,
            matchRadiusPx = matchRadiusPx,
            referenceFrameId = referenceFrameId,
            usedFallbackPath = usedFallbackPath,
        )
    }

    private fun solveAffineTransform(
        matchedPairs: List<MatchedPair>,
    ): RegistrationTransform? {
        if (matchedPairs.size < 3) return null
        val normal = Array(6) { DoubleArray(6) }
        val rhs = DoubleArray(6)

        fun accumulateRow(row: DoubleArray, value: Double) {
            for (i in row.indices) {
                rhs[i] += row[i] * value
                for (j in row.indices) {
                    normal[i][j] += row[i] * row[j]
                }
            }
        }

        for (pair in matchedPairs) {
            val x = pair.currentStar.x.toDouble()
            val y = pair.currentStar.y.toDouble()
            val targetX = pair.referenceStar.x.toDouble()
            val targetY = pair.referenceStar.y.toDouble()
            accumulateRow(doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0), targetX)
            accumulateRow(doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0), targetY)
        }

        val solution = solveLinearSystem(normal, rhs) ?: return null
        val a = solution[0].toFloat()
        val b = solution[1].toFloat()
        val dx = solution[2].toFloat()
        val c = solution[3].toFloat()
        val d = solution[4].toFloat()
        val dy = solution[5].toFloat()
        val scaleX = sqrt((a * a) + (c * c)).coerceAtLeast(0.001f)
        val rotationDeg = Math.toDegrees(atan2(c.toDouble(), a.toDouble())).toFloat()
        val determinant = (a * d) - (b * c)
        val scaleY = (determinant / scaleX).coerceAtLeast(0.001f)
        val shear = ((a * b) + (c * d)) / (scaleX * scaleY).coerceAtMost(1f)
        return RegistrationTransform(
            dx = dx,
            dy = dy,
            a = a,
            b = b,
            c = c,
            d = d,
            rotationDeg = rotationDeg,
            scaleX = scaleX,
            scaleY = scaleY,
            shear = shear,
            model = DeepSkyTransformModel.Affine,
        )
    }

    private fun solveLinearSystem(
        normal: Array<DoubleArray>,
        rhs: DoubleArray,
    ): DoubleArray? {
        val size = rhs.size
        val augmented = Array(size) { row ->
            DoubleArray(size + 1).apply {
                for (column in 0 until size) {
                    this[column] = normal[row][column]
                }
                this[size] = rhs[row]
            }
        }
        for (pivotIndex in 0 until size) {
            var pivotRow = pivotIndex
            for (row in pivotIndex + 1 until size) {
                if (abs(augmented[row][pivotIndex]) > abs(augmented[pivotRow][pivotIndex])) {
                    pivotRow = row
                }
            }
            if (abs(augmented[pivotRow][pivotIndex]) < 1e-8) return null
            if (pivotRow != pivotIndex) {
                val temp = augmented[pivotIndex]
                augmented[pivotIndex] = augmented[pivotRow]
                augmented[pivotRow] = temp
            }
            val pivot = augmented[pivotIndex][pivotIndex]
            for (column in pivotIndex until size + 1) {
                augmented[pivotIndex][column] /= pivot
            }
            for (row in 0 until size) {
                if (row == pivotIndex) continue
                val factor = augmented[row][pivotIndex]
                if (factor == 0.0) continue
                for (column in pivotIndex until size + 1) {
                    augmented[row][column] -= factor * augmented[pivotIndex][column]
                }
            }
        }
        return DoubleArray(size) { index -> augmented[index][size] }
    }

    private fun computeStarSignatures(stars: List<DetectedStar>): List<FloatArray> {
        if (stars.isEmpty()) return emptyList()
        return stars.mapIndexed { index, star ->
            val distances = stars.mapIndexedNotNull { otherIndex, other ->
                if (otherIndex == index) null else distance(star, other)
            }.sorted()
            if (distances.isEmpty()) {
                floatArrayOf(1f, 1f, 1f)
            } else {
                val base = distances.first().coerceAtLeast(1f)
                floatArrayOf(
                    1f,
                    (distances.getOrElse(1) { base } / base).coerceAtMost(6f),
                    (distances.getOrElse(2) { base } / base).coerceAtMost(6f),
                )
            }
        }
    }

    private fun areStarsLocallyCompatible(
        referenceStar: DetectedStar,
        currentStar: DetectedStar,
        referenceSignature: FloatArray,
        currentSignature: FloatArray,
    ): Boolean {
        val signatureError = referenceSignature.indices.sumOf { index ->
            abs(referenceSignature[index] - currentSignature[index]).toDouble()
        }.toFloat() / referenceSignature.size.toFloat()
        val contrastRatio = if (currentStar.localContrast <= 0f || referenceStar.localContrast <= 0f) {
            1f
        } else {
            max(referenceStar.localContrast, currentStar.localContrast) /
                min(referenceStar.localContrast, currentStar.localContrast)
        }
        return signatureError <= 0.55f && contrastRatio <= 2.2f
    }

    private fun applyTransform(
        star: DetectedStar,
        transform: RegistrationTransform,
        centerX: Float,
        centerY: Float,
    ): DetectedStar {
        val relX = star.x - centerX
        val relY = star.y - centerY
        val mapped = transform.applyRelative(relX, relY)
        return star.copy(
            x = mapped.first + centerX + transform.dx,
            y = mapped.second + centerY + transform.dy,
        )
    }

    private fun createRigidTransform(
        dx: Float,
        dy: Float,
        rotationDeg: Float,
    ): RegistrationTransform {
        val radians = rotationDeg / 180f * PI.toFloat()
        val cosTheta = cos(radians)
        val sinTheta = sin(radians)
        return RegistrationTransform(
            dx = dx,
            dy = dy,
            a = cosTheta,
            b = -sinTheta,
            c = sinTheta,
            d = cosTheta,
            rotationDeg = rotationDeg,
            scaleX = 1f,
            scaleY = 1f,
            shear = 0f,
            model = DeepSkyTransformModel.Rigid,
        )
    }

    private fun rotateStar(star: DetectedStar, rotationDeg: Float, centerX: Float, centerY: Float): DetectedStar {
        val radians = (rotationDeg / 180f) * PI.toFloat()
        val relX = star.x - centerX
        val relY = star.y - centerY
        val rotatedX = relX * cos(radians) - relY * sin(radians)
        val rotatedY = relX * sin(radians) + relY * cos(radians)
        return star.copy(
            x = rotatedX + centerX,
            y = rotatedY + centerY,
        )
    }

    private fun distance(a: DetectedStar, b: DetectedStar): Float {
        return hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
    }

    private fun angleDeg(a: DetectedStar, b: DetectedStar): Float {
        return Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
    }

    private fun normalizeDegrees(value: Float): Float {
        var current = value
        while (current > 180f) current -= 360f
        while (current < -180f) current += 360f
        return current
    }

    private data class MatchedPair(
        val referenceStar: DetectedStar,
        val currentStar: DetectedStar,
        val transformedCurrentStar: DetectedStar,
        val residualPx: Float,
    )

    private data class CandidateEvaluation(
        val transform: RegistrationTransform,
        val metrics: FrameRegistrationMetrics,
        val matchedPairs: List<MatchedPair>,
    )

    private fun List<Float>.median(): Float {
        if (isEmpty()) return 0f
        val middle = size / 2
        return if (size % 2 == 0) {
            (this[middle - 1] + this[middle]) / 2f
        } else {
            this[middle]
        }
    }
}

data class StackSnapshot(
    val previewRedSum: FloatArray,
    val previewGreenSum: FloatArray,
    val previewBlueSum: FloatArray,
    val previewWeightSum: FloatArray,
    val previewWidth: Int,
    val previewHeight: Int,
    val frameCount: Int,
    val totalWeight: Float,
)

data class StackEngineConfig(
    val previewMode: StackAccumulationMode,
    val fullResMode: StackAccumulationMode,
    val previewWinsorStartFrame: Int,
    val winsorSigmaMultiplier: Float,
    val enableWinsorizedPreview: Boolean,
    val enableWinsorizedFullRes: Boolean,
)

open class StackEngine {
    private var config: StackEngineConfig? = null
    private var fullWidth: Int = 0
    private var fullHeight: Int = 0
    private var previewWidth: Int = 0
    private var previewHeight: Int = 0
    private var fullRedSum: FloatArray? = null
    private var fullGreenSum: FloatArray? = null
    private var fullBlueSum: FloatArray? = null
    private var fullWeightSum: FloatArray? = null
    private var fullLumaSum: FloatArray? = null
    private var fullLumaSqSum: FloatArray? = null
    private var previewRedSum: FloatArray? = null
    private var previewGreenSum: FloatArray? = null
    private var previewBlueSum: FloatArray? = null
    private var previewWeightSum: FloatArray? = null
    private var previewLumaSum: FloatArray? = null
    private var previewLumaSqSum: FloatArray? = null
    private var frameCount: Int = 0
    private var totalWeight: Float = 0f

    open fun initialize(
        decodedFrame: DecodedFrame,
        config: StackEngineConfig,
    ) {
        this.config = config
        fullWidth = decodedFrame.fullResWidth
        fullHeight = decodedFrame.fullResHeight
        previewWidth = decodedFrame.previewWidth
        previewHeight = decodedFrame.previewHeight
        fullRedSum = FloatArray(fullWidth * fullHeight)
        fullGreenSum = FloatArray(fullWidth * fullHeight)
        fullBlueSum = FloatArray(fullWidth * fullHeight)
        fullWeightSum = FloatArray(fullWidth * fullHeight)
        fullLumaSum = if (config.enableWinsorizedFullRes) FloatArray(fullWidth * fullHeight) else null
        fullLumaSqSum = if (config.enableWinsorizedFullRes) FloatArray(fullWidth * fullHeight) else null
        previewRedSum = FloatArray(previewWidth * previewHeight)
        previewGreenSum = FloatArray(previewWidth * previewHeight)
        previewBlueSum = FloatArray(previewWidth * previewHeight)
        previewWeightSum = FloatArray(previewWidth * previewHeight)
        previewLumaSum = FloatArray(previewWidth * previewHeight)
        previewLumaSqSum = FloatArray(previewWidth * previewHeight)
        frameCount = 0
        totalWeight = 0f
    }

    fun isInitialized(): Boolean = fullRedSum != null && config != null

    open fun reset() {
        config = null
        fullWidth = 0
        fullHeight = 0
        previewWidth = 0
        previewHeight = 0
        fullRedSum = null
        fullGreenSum = null
        fullBlueSum = null
        fullWeightSum = null
        fullLumaSum = null
        fullLumaSqSum = null
        previewRedSum = null
        previewGreenSum = null
        previewBlueSum = null
        previewWeightSum = null
        previewLumaSum = null
        previewLumaSqSum = null
        frameCount = 0
        totalWeight = 0f
    }

    open fun accumulate(
        decodedFrame: DecodedFrame,
        alignmentTransform: RegistrationTransform,
        weight: Float = 1f,
    ) {
        val config = requireNotNull(config) { "StackEngine must be initialized before accumulate()" }
        val clampedWeight = weight.coerceAtLeast(0.05f)
        accumulateRgb48(
            decodedFrame = decodedFrame,
            targetWidth = fullWidth,
            targetHeight = fullHeight,
            transform = alignmentTransform.scaled(
                scaleX = fullWidth.toFloat() / decodedFrame.alignmentWidth.toFloat(),
                scaleY = fullHeight.toFloat() / decodedFrame.alignmentHeight.toFloat(),
            ),
            weight = clampedWeight,
            redSum = requireNotNull(fullRedSum),
            greenSum = requireNotNull(fullGreenSum),
            blueSum = requireNotNull(fullBlueSum),
            weightSum = requireNotNull(fullWeightSum),
            lumaSum = fullLumaSum,
            lumaSqSum = fullLumaSqSum,
            mode = if (config.enableWinsorizedFullRes) config.fullResMode else StackAccumulationMode.WeightedAverage,
            winsorStartFrame = config.previewWinsorStartFrame,
            winsorSigmaMultiplier = config.winsorSigmaMultiplier,
        )
        accumulatePreview(
            previewArgb = decodedFrame.previewArgb,
            width = decodedFrame.previewWidth,
            height = decodedFrame.previewHeight,
            transform = alignmentTransform.scaled(
                scaleX = decodedFrame.previewWidth.toFloat() / decodedFrame.alignmentWidth.toFloat(),
                scaleY = decodedFrame.previewHeight.toFloat() / decodedFrame.alignmentHeight.toFloat(),
            ),
            weight = clampedWeight,
            redSum = requireNotNull(previewRedSum),
            greenSum = requireNotNull(previewGreenSum),
            blueSum = requireNotNull(previewBlueSum),
            weightSum = requireNotNull(previewWeightSum),
            lumaSum = requireNotNull(previewLumaSum),
            lumaSqSum = requireNotNull(previewLumaSqSum),
            mode = if (config.enableWinsorizedPreview) config.previewMode else StackAccumulationMode.WeightedAverage,
            winsorStartFrame = config.previewWinsorStartFrame,
            winsorSigmaMultiplier = config.winsorSigmaMultiplier,
        )
        frameCount += 1
        totalWeight += clampedWeight
    }

    open fun snapshot(): StackSnapshot {
        return StackSnapshot(
            previewRedSum = requireNotNull(previewRedSum),
            previewGreenSum = requireNotNull(previewGreenSum),
            previewBlueSum = requireNotNull(previewBlueSum),
            previewWeightSum = requireNotNull(previewWeightSum),
            previewWidth = previewWidth,
            previewHeight = previewHeight,
            frameCount = frameCount,
            totalWeight = totalWeight,
        )
    }

    private fun accumulateRgb48(
        decodedFrame: DecodedFrame,
        targetWidth: Int,
        targetHeight: Int,
        transform: RegistrationTransform,
        weight: Float,
        redSum: FloatArray,
        greenSum: FloatArray,
        blueSum: FloatArray,
        weightSum: FloatArray,
        lumaSum: FloatArray?,
        lumaSqSum: FloatArray?,
        mode: StackAccumulationMode,
        winsorStartFrame: Int,
        winsorSigmaMultiplier: Float,
    ) {
        val source = decodedFrame.fullResRgb48
        val centerX = (targetWidth - 1) / 2f
        val centerY = (targetHeight - 1) / 2f
        val inverse = invertLinear(transform)

        for (y in 0 until targetHeight) {
            val targetRelY = y - centerY - transform.dy
            for (x in 0 until targetWidth) {
                val targetRelX = x - centerX - transform.dx
                val srcRelX = inverse.a * targetRelX + inverse.b * targetRelY
                val srcRelY = inverse.c * targetRelX + inverse.d * targetRelY
                val srcX = (srcRelX + centerX).roundToInt()
                val srcY = (srcRelY + centerY).roundToInt()
                if (srcX !in 0 until decodedFrame.fullResWidth || srcY !in 0 until decodedFrame.fullResHeight) {
                    continue
                }
                val srcIndex = (srcY * decodedFrame.fullResWidth + srcX) * 3
                val dstIndex = y * targetWidth + x
                var red = (source[srcIndex].toInt() and 0xffff).toFloat()
                var green = (source[srcIndex + 1].toInt() and 0xffff).toFloat()
                var blue = (source[srcIndex + 2].toInt() and 0xffff).toFloat()
                val luma = previewLuma(red, green, blue)
                val clampFactor = computeWinsorFactor(
                    luma = luma,
                    frameCount = frameCount,
                    weightSum = weightSum[dstIndex],
                    lumaSum = lumaSum?.get(dstIndex),
                    lumaSqSum = lumaSqSum?.get(dstIndex),
                    mode = mode,
                    winsorStartFrame = winsorStartFrame,
                    winsorSigmaMultiplier = winsorSigmaMultiplier,
                )
                red *= clampFactor
                green *= clampFactor
                blue *= clampFactor
                val clampedLuma = luma * clampFactor
                redSum[dstIndex] += red * weight
                greenSum[dstIndex] += green * weight
                blueSum[dstIndex] += blue * weight
                weightSum[dstIndex] += weight
                if (lumaSum != null && lumaSqSum != null) {
                    lumaSum[dstIndex] += clampedLuma * weight
                    lumaSqSum[dstIndex] += clampedLuma * clampedLuma * weight
                }
            }
        }
    }

    private fun accumulatePreview(
        previewArgb: IntArray,
        width: Int,
        height: Int,
        transform: RegistrationTransform,
        weight: Float,
        redSum: FloatArray,
        greenSum: FloatArray,
        blueSum: FloatArray,
        weightSum: FloatArray,
        lumaSum: FloatArray,
        lumaSqSum: FloatArray,
        mode: StackAccumulationMode,
        winsorStartFrame: Int,
        winsorSigmaMultiplier: Float,
    ) {
        val centerX = (width - 1) / 2f
        val centerY = (height - 1) / 2f
        val inverse = invertLinear(transform)
        for (y in 0 until height) {
            val targetRelY = y - centerY - transform.dy
            for (x in 0 until width) {
                val targetRelX = x - centerX - transform.dx
                val srcRelX = inverse.a * targetRelX + inverse.b * targetRelY
                val srcRelY = inverse.c * targetRelX + inverse.d * targetRelY
                val srcX = (srcRelX + centerX).roundToInt()
                val srcY = (srcRelY + centerY).roundToInt()
                if (srcX !in 0 until width || srcY !in 0 until height) {
                    continue
                }
                val color = previewArgb[srcY * width + srcX]
                val dstIndex = y * width + x
                var red = argbRed(color).toFloat()
                var green = argbGreen(color).toFloat()
                var blue = argbBlue(color).toFloat()
                val luma = previewLuma(red, green, blue)
                val clampFactor = computeWinsorFactor(
                    luma = luma,
                    frameCount = frameCount,
                    weightSum = weightSum[dstIndex],
                    lumaSum = lumaSum[dstIndex],
                    lumaSqSum = lumaSqSum[dstIndex],
                    mode = mode,
                    winsorStartFrame = winsorStartFrame,
                    winsorSigmaMultiplier = winsorSigmaMultiplier,
                )
                red *= clampFactor
                green *= clampFactor
                blue *= clampFactor
                val clampedLuma = luma * clampFactor
                redSum[dstIndex] += red * weight
                greenSum[dstIndex] += green * weight
                blueSum[dstIndex] += blue * weight
                weightSum[dstIndex] += weight
                lumaSum[dstIndex] += clampedLuma * weight
                lumaSqSum[dstIndex] += clampedLuma * clampedLuma * weight
            }
        }
    }

    private fun computeWinsorFactor(
        luma: Float,
        frameCount: Int,
        weightSum: Float,
        lumaSum: Float?,
        lumaSqSum: Float?,
        mode: StackAccumulationMode,
        winsorStartFrame: Int,
        winsorSigmaMultiplier: Float,
    ): Float {
        if (mode != StackAccumulationMode.WinsorizedMean) return 1f
        if (frameCount + 1 < winsorStartFrame) return 1f
        if (weightSum <= 0f || lumaSum == null || lumaSqSum == null) return 1f
        val mean = lumaSum / weightSum
        val variance = ((lumaSqSum / weightSum) - (mean * mean)).coerceAtLeast(64f)
        val sigma = sqrt(variance)
        val upper = mean + sigma * winsorSigmaMultiplier
        val lower = max(0f, mean - sigma * winsorSigmaMultiplier)
        val clamped = luma.coerceIn(lower, upper)
        return if (luma <= 0f) 1f else (clamped / luma).coerceIn(0.5f, 1.5f)
    }
}

open class StackRenderer {
    private var bitmap: Bitmap? = null
    private var argbBuffer: IntArray? = null
    private var toneState: PreviewToneState? = null

    open fun render(
        snapshot: StackSnapshot,
        preset: DeepSkyPresetProfile,
        performanceMode: DeepSkyPerformanceMode,
    ): Bitmap {
        val targetSize = resolveTargetSize(snapshot, preset, performanceMode)
        if (bitmap?.width != targetSize.first || bitmap?.height != targetSize.second) {
            bitmap?.recycle()
            bitmap = createBitmap(targetSize.first, targetSize.second, Bitmap.Config.ARGB_8888)
            argbBuffer = IntArray(targetSize.first * targetSize.second)
        }

        val current = computePreviewToneParameters(snapshot, preset)
        toneState = smoothToneState(previous = toneState, current = current, preset = preset)
        val output = renderPreviewArgb(
            snapshot = snapshot,
            preset = preset,
            toneState = requireNotNull(toneState),
            outputWidth = targetSize.first,
            outputHeight = targetSize.second,
            output = requireNotNull(argbBuffer),
        )
        requireNotNull(bitmap).setPixels(output, 0, targetSize.first, 0, 0, targetSize.first, targetSize.second)
        return requireNotNull(bitmap)
    }

    open fun reset() {
        bitmap?.recycle()
        bitmap = null
        argbBuffer = null
        toneState = null
    }

    private fun resolveTargetSize(
        snapshot: StackSnapshot,
        preset: DeepSkyPresetProfile,
        performanceMode: DeepSkyPerformanceMode,
    ): Pair<Int, Int> {
        val targetMaxEdge = when (performanceMode) {
            DeepSkyPerformanceMode.Normal -> preset.performance.previewMaxEdgeNormal
            DeepSkyPerformanceMode.DegradedPreview,
            DeepSkyPerformanceMode.Throttled,
            DeepSkyPerformanceMode.ProtectionPaused,
            -> preset.performance.previewMaxEdgeDegraded
        }
        val sourceMaxEdge = max(snapshot.previewWidth, snapshot.previewHeight)
        if (sourceMaxEdge <= targetMaxEdge) {
            return snapshot.previewWidth to snapshot.previewHeight
        }
        val scale = targetMaxEdge.toFloat() / sourceMaxEdge.toFloat()
        return max(1, (snapshot.previewWidth * scale).roundToInt()) to
            max(1, (snapshot.previewHeight * scale).roundToInt())
    }
}

fun composeTransforms(
    currentToPrevious: RegistrationTransform,
    previousToReference: RegistrationTransform,
): RegistrationTransform {
    val a = previousToReference.a * currentToPrevious.a + previousToReference.b * currentToPrevious.c
    val b = previousToReference.a * currentToPrevious.b + previousToReference.b * currentToPrevious.d
    val c = previousToReference.c * currentToPrevious.a + previousToReference.d * currentToPrevious.c
    val d = previousToReference.c * currentToPrevious.b + previousToReference.d * currentToPrevious.d
    val rotatedDx = previousToReference.a * currentToPrevious.dx + previousToReference.b * currentToPrevious.dy
    val rotatedDy = previousToReference.c * currentToPrevious.dx + previousToReference.d * currentToPrevious.dy
    val dx = rotatedDx + previousToReference.dx
    val dy = rotatedDy + previousToReference.dy
    val scaleX = sqrt((a * a) + (c * c)).coerceAtLeast(0.001f)
    val determinant = (a * d) - (b * c)
    val scaleY = (determinant / scaleX).coerceAtLeast(0.001f)
    val shear = ((a * b) + (c * d)) / (scaleX * scaleY)
    val rotationDeg = Math.toDegrees(atan2(c.toDouble(), a.toDouble())).toFloat()
    return RegistrationTransform(
        dx = dx,
        dy = dy,
        a = a,
        b = b,
        c = c,
        d = d,
        rotationDeg = rotationDeg,
        scaleX = scaleX,
        scaleY = scaleY,
        shear = shear,
        model = if (currentToPrevious.model == DeepSkyTransformModel.Affine || previousToReference.model == DeepSkyTransformModel.Affine) {
            DeepSkyTransformModel.Affine
        } else {
            DeepSkyTransformModel.Rigid
        },
    )
}

internal fun renderPreviewArgb(
    snapshot: StackSnapshot,
    preset: DeepSkyPresetProfile,
    toneState: PreviewToneState,
    outputWidth: Int = snapshot.previewWidth,
    outputHeight: Int = snapshot.previewHeight,
    output: IntArray = IntArray(outputWidth * outputHeight),
): IntArray {
    require(output.size == outputWidth * outputHeight) {
        "Output buffer size ${output.size} does not match ${outputWidth}x${outputHeight}"
    }

    val stretchStrength = preset.preview.stretch.aggressiveness.coerceAtLeast(1.1f)
    val xScale = snapshot.previewWidth.toFloat() / outputWidth.toFloat()
    val yScale = snapshot.previewHeight.toFloat() / outputHeight.toFloat()
    for (y in 0 until outputHeight) {
        val srcY = min(snapshot.previewHeight - 1, (y * yScale).toInt())
        for (x in 0 until outputWidth) {
            val srcX = min(snapshot.previewWidth - 1, (x * xScale).toInt())
            val srcIndex = srcY * snapshot.previewWidth + srcX
            val dstIndex = y * outputWidth + x
            val weight = snapshot.previewWeightSum[srcIndex]
            if (weight <= 0f) {
                output[dstIndex] = packOpaqueArgb(0, 0, 0)
                continue
            }
            val rawRed = previewAverage(snapshot.previewRedSum[srcIndex], weight)
            val rawGreen = previewAverage(snapshot.previewGreenSum[srcIndex], weight)
            val rawBlue = previewAverage(snapshot.previewBlueSum[srcIndex], weight)
            val red = ((rawRed - toneState.backgroundRed).coerceAtLeast(0f) * toneState.redGain)
            val green = ((rawGreen - toneState.backgroundGreen).coerceAtLeast(0f) * toneState.greenGain)
            val blue = ((rawBlue - toneState.backgroundBlue).coerceAtLeast(0f) * toneState.blueGain)
            val stretchedRed = stretchPreviewChannel(red, toneState.blackPoint, toneState.normDivisor, stretchStrength, preset.preview.stretch)
            val stretchedGreen = stretchPreviewChannel(green, toneState.blackPoint, toneState.normDivisor, stretchStrength, preset.preview.stretch)
            val stretchedBlue = stretchPreviewChannel(blue, toneState.blackPoint, toneState.normDivisor, stretchStrength, preset.preview.stretch)
            output[dstIndex] = packOpaqueArgb(stretchedRed, stretchedGreen, stretchedBlue)
        }
    }
    return output
}

internal fun computePreviewToneParameters(
    snapshot: StackSnapshot,
    preset: DeepSkyPresetProfile,
): PreviewToneState {
    val sampledIndices = buildSampleIndices(snapshot.previewWidth * snapshot.previewHeight)
    val redSamples = FloatArray(sampledIndices.size)
    val greenSamples = FloatArray(sampledIndices.size)
    val blueSamples = FloatArray(sampledIndices.size)
    var validSamples = 0
    for (sampleIndex in sampledIndices.indices) {
        val pixelIndex = sampledIndices[sampleIndex]
        val weight = snapshot.previewWeightSum[pixelIndex]
        if (weight <= 0f) continue
        redSamples[validSamples] = previewAverage(snapshot.previewRedSum[pixelIndex], weight)
        greenSamples[validSamples] = previewAverage(snapshot.previewGreenSum[pixelIndex], weight)
        blueSamples[validSamples] = previewAverage(snapshot.previewBlueSum[pixelIndex], weight)
        validSamples += 1
    }
    if (validSamples == 0) {
        return PreviewToneState(
            blackPoint = 0f,
            whitePoint = 1f,
            normDivisor = 1f,
            backgroundRed = 0f,
            backgroundGreen = 0f,
            backgroundBlue = 0f,
            redGain = 1f,
            greenGain = 1f,
            blueGain = 1f,
        )
    }
    val reds = redSamples.copyOf(validSamples)
    val greens = greenSamples.copyOf(validSamples)
    val blues = blueSamples.copyOf(validSamples)
    val backgroundRed = percentile(reds, PREVIEW_CHANNEL_BACKGROUND_PERCENTILE)
    val backgroundGreen = percentile(greens, PREVIEW_CHANNEL_BACKGROUND_PERCENTILE)
    val backgroundBlue = percentile(blues, PREVIEW_CHANNEL_BACKGROUND_PERCENTILE)
    val averageBackground = ((backgroundRed + backgroundGreen + backgroundBlue) / 3f).coerceAtLeast(1f)
    val redGain = (averageBackground / backgroundRed.coerceAtLeast(1f))
        .coerceIn(1f - preset.preview.maxColorBalanceShift, 1f + preset.preview.maxColorBalanceShift)
    val greenGain = (averageBackground / backgroundGreen.coerceAtLeast(1f))
        .coerceIn(1f - preset.preview.maxColorBalanceShift, 1f + preset.preview.maxColorBalanceShift)
    val blueGain = (averageBackground / backgroundBlue.coerceAtLeast(1f))
        .coerceIn(1f - preset.preview.maxColorBalanceShift, 1f + preset.preview.maxColorBalanceShift)
    val lumaSamples = FloatArray(validSamples)
    for (index in 0 until validSamples) {
        lumaSamples[index] = previewLuma(
            red = (reds[index] - backgroundRed).coerceAtLeast(0f) * redGain,
            green = (greens[index] - backgroundGreen).coerceAtLeast(0f) * greenGain,
            blue = (blues[index] - backgroundBlue).coerceAtLeast(0f) * blueGain,
        )
    }
    val shadow = percentile(lumaSamples, PREVIEW_SHADOW_PERCENTILE)
    val background = percentile(lumaSamples, PREVIEW_BACKGROUND_PERCENTILE)
    val aggressivenessBias =
        ((preset.preview.stretch.aggressiveness - PREVIEW_STRETCH_AGGRESSIVENESS_BASE) / PREVIEW_STRETCH_AGGRESSIVENESS_SPAN)
            .coerceIn(0f, 1f)
    val highlightPercentile = 1f - (
        PREVIEW_WHITE_CLIP_FRACTION_MIN +
            (PREVIEW_WHITE_CLIP_FRACTION_MAX - PREVIEW_WHITE_CLIP_FRACTION_MIN) * aggressivenessBias
        )
    val highlight = percentile(lumaSamples, highlightPercentile)
    val blackBlend =
        (PREVIEW_BLACK_BLEND_BASE + preset.preview.stretch.blackPointLift * PREVIEW_BLACK_BLEND_LIFT_SCALE)
            .coerceIn(PREVIEW_BLACK_BLEND_MIN, PREVIEW_BLACK_BLEND_MAX)
    val blackPoint = shadow + (background - shadow).coerceAtLeast(0f) * blackBlend
    val whitePoint = max(highlight, blackPoint + 1f)
    return PreviewToneState(
        blackPoint = blackPoint,
        whitePoint = whitePoint,
        normDivisor = (whitePoint - blackPoint).coerceAtLeast(1f),
        backgroundRed = backgroundRed,
        backgroundGreen = backgroundGreen,
        backgroundBlue = backgroundBlue,
        redGain = redGain,
        greenGain = greenGain,
        blueGain = blueGain,
    )
}

internal fun smoothToneState(
    previous: PreviewToneState?,
    current: PreviewToneState,
    preset: DeepSkyPresetProfile,
): PreviewToneState {
    if (previous == null) return current
    return PreviewToneState(
        blackPoint = smoothValue(previous.blackPoint, current.blackPoint, preset.preview.blackRiseAlpha, preset.preview.blackFallAlpha),
        whitePoint = smoothValue(previous.whitePoint, current.whitePoint, preset.preview.whiteRiseAlpha, preset.preview.whiteFallAlpha),
        normDivisor = max(1f, smoothValue(previous.normDivisor, current.normDivisor, preset.preview.whiteRiseAlpha, preset.preview.whiteFallAlpha)),
        backgroundRed = smoothValue(previous.backgroundRed, current.backgroundRed, preset.preview.blackRiseAlpha, preset.preview.blackFallAlpha),
        backgroundGreen = smoothValue(previous.backgroundGreen, current.backgroundGreen, preset.preview.blackRiseAlpha, preset.preview.blackFallAlpha),
        backgroundBlue = smoothValue(previous.backgroundBlue, current.backgroundBlue, preset.preview.blackRiseAlpha, preset.preview.blackFallAlpha),
        redGain = smoothValue(previous.redGain, current.redGain, preset.preview.colorRiseAlpha, preset.preview.colorFallAlpha),
        greenGain = smoothValue(previous.greenGain, current.greenGain, preset.preview.colorRiseAlpha, preset.preview.colorFallAlpha),
        blueGain = smoothValue(previous.blueGain, current.blueGain, preset.preview.colorRiseAlpha, preset.preview.colorFallAlpha),
    )
}

internal fun previewAverage(sum: Float, weight: Float): Float {
    return if (weight <= 0f) 0f else sum / weight
}

internal fun previewLuma(red: Float, green: Float, blue: Float): Float {
    return 0.2126f * red + 0.7152f * green + 0.0722f * blue
}

internal fun stretchPreviewChannel(
    value: Float,
    blackPoint: Float,
    normDivisor: Float,
    stretchStrength: Float,
    stretchProfile: DeepSkyPreviewStretchProfile,
): Int {
    val normalized = ((value - blackPoint) / normDivisor).coerceIn(0f, 1f)
    val stretched = when (stretchProfile.mode) {
        DeepSkyStretchMode.Arcsinh ->
            (asinhValue(normalized * stretchStrength) / asinhValue(stretchStrength)).coerceIn(0f, 1f)
        DeepSkyStretchMode.Log ->
            (ln(1.0f + normalized * stretchStrength) / ln(1.0f + stretchStrength)).coerceIn(0f, 1f)
    }
    val midtoneAdjusted = stretched.toDouble().pow((1f / stretchProfile.midtone.coerceAtLeast(0.1f)).toDouble()).toFloat()
    return (midtoneAdjusted * 255f).roundToInt().coerceIn(0, 255)
}

internal fun argbRed(color: Int): Int = (color ushr 16) and 0xff

internal fun argbGreen(color: Int): Int = (color ushr 8) and 0xff

internal fun argbBlue(color: Int): Int = color and 0xff

internal fun packOpaqueArgb(red: Int, green: Int, blue: Int): Int {
    return (255 shl 24) or
        ((red.coerceIn(0, 255) and 0xff) shl 16) or
        ((green.coerceIn(0, 255) and 0xff) shl 8) or
        (blue.coerceIn(0, 255) and 0xff)
}

internal data class PreviewToneState(
    val blackPoint: Float,
    val whitePoint: Float,
    val normDivisor: Float,
    val backgroundRed: Float,
    val backgroundGreen: Float,
    val backgroundBlue: Float,
    val redGain: Float,
    val greenGain: Float,
    val blueGain: Float,
)

private data class LinearInverse(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
)

private fun invertLinear(transform: RegistrationTransform): LinearInverse {
    val determinant = (transform.a * transform.d) - (transform.b * transform.c)
    if (abs(determinant) < 1e-6f) {
        Log.w("DeepSky", "invertLinear: near-singular determinant ($determinant), falling back to identity")
        return LinearInverse(1f, 0f, 0f, 1f)
    }
    val invDet = 1f / determinant
    return LinearInverse(
        a = transform.d * invDet,
        b = -transform.b * invDet,
        c = -transform.c * invDet,
        d = transform.a * invDet,
    )
}

private fun buildSampleIndices(totalPixels: Int, maxSamples: Int = 65536): IntArray {
    if (totalPixels <= maxSamples) {
        return IntArray(totalPixels) { it }
    }
    val step = ceil(totalPixels.toDouble() / maxSamples.toDouble()).toInt().coerceAtLeast(1)
    val sampled = IntArray((totalPixels + step - 1) / step)
    var dst = 0
    var src = 0
    while (src < totalPixels && dst < sampled.size) {
        sampled[dst++] = src
        src += step
    }
    return if (dst == sampled.size) sampled else sampled.copyOf(dst)
}

private fun smoothValue(previous: Float, current: Float, riseAlpha: Float, fallAlpha: Float): Float {
    val alpha = if (current > previous) riseAlpha else fallAlpha
    return previous + (current - previous) * alpha.coerceIn(0f, 1f)
}

private fun asinhValue(value: Float): Float {
    return ln(value + sqrt(value * value + 1f))
}

private const val PREVIEW_CHANNEL_BACKGROUND_PERCENTILE = 0.08f
private const val PREVIEW_SHADOW_PERCENTILE = 0.08f
private const val PREVIEW_BACKGROUND_PERCENTILE = 0.55f
private const val PREVIEW_WHITE_CLIP_FRACTION_MIN = 0.0015f
private const val PREVIEW_WHITE_CLIP_FRACTION_MAX = 0.0035f
private const val PREVIEW_STRETCH_AGGRESSIVENESS_BASE = 2.0f
private const val PREVIEW_STRETCH_AGGRESSIVENESS_SPAN = 2.5f
private const val PREVIEW_BLACK_BLEND_BASE = 0.62f
private const val PREVIEW_BLACK_BLEND_LIFT_SCALE = 4.0f
private const val PREVIEW_BLACK_BLEND_MIN = 0.45f
private const val PREVIEW_BLACK_BLEND_MAX = 0.82f
