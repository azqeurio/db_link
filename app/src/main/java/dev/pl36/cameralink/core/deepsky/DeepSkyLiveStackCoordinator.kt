package dev.pl36.cameralink.core.deepsky

import android.content.Context
import dev.pl36.cameralink.core.logging.D
import dev.pl36.cameralink.core.model.GeoTagLocationSample
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

class DeepSkyLiveStackCoordinator(
    private val appContext: Context,
    private val presetEngine: DeepSkyPresetEngine = DeepSkyPresetEngine(),
    private val skyHintProvider: SkyHintProvider = SkyHintProvider(),
    private val frameDecoder: DeepSkyFrameDecoder = DefaultDeepSkyFrameDecoder(),
    private val starDetector: StarDetector = StarDetector(),
    private val qualityAnalyzer: DeepSkyQualityAnalyzer = DeepSkyQualityAnalyzer(),
    private val memoryPolicy: DeepSkyMemoryPolicy = DeepSkyMemoryPolicy(appContext),
    private val performanceProbe: DeepSkyPerformanceProbe = DefaultDeepSkyPerformanceProbe(appContext),
    private val frameRegistrar: FrameRegistrar = FrameRegistrar(),
    private val stackEngine: StackEngine = StackEngine(),
    private val stackRenderer: StackRenderer = StackRenderer(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val processingDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "DeepSkyLiveStack").apply { isDaemon = true }
        }.asCoroutineDispatcher(),
    private val nanoTimeSource: () -> Long = System::nanoTime,
) {
    private data class QueuedFrame(
        val generation: Long,
        val frame: CapturedFrame,
    )

    private data class AcceptedFrameAnchor(
        val frameId: String,
        val stars: List<DetectedStar>,
        val qualityMetrics: FrameQualityMetrics,
        val transformToStackSpace: RegistrationTransform,
        val acceptedIndex: Int,
        val referenceCandidateScore: Float,
    )

    private val scope = CoroutineScope(SupervisorJob() + processingDispatcher)
    private val generation = AtomicLong(0L)
    private val queue = Channel<QueuedFrame>(capacity = Channel.UNLIMITED)
    private val _state = MutableStateFlow(
        DeepSkyLiveStackUiState(
            sessionStatus = DeepSkySessionStatus.Idle,
            availablePresets = presetEngine.profiles,
        ),
    )
    val state: StateFlow<DeepSkyLiveStackUiState> = _state.asStateFlow()

    @Volatile
    private var sessionActive = false
    private var manualPresetOverride: DeepSkyPresetId? = null
    private var manualFocalLengthOverrideMm: Float? = null
    private var liveViewFocalLengthMm: Float? = null
    private var liveViewAperture: Float? = null
    private var liveViewExposureSec: Double? = null
    private var stackAnchor: AcceptedFrameAnchor? = null
    private var registrationReference: AcceptedFrameAnchor? = null
    private var lastAcceptedAnchor: AcceptedFrameAnchor? = null
    private var lastDirectReferenceTransform: RegistrationTransform? = null
    private var memoryPlan: DeepSkyMemoryBudgetPlan? = null
    private var stats = DeepSkySessionStats()
    private var fallbackChainLength = 0
    private var earlyRebasePerformed = false
    private val referenceCandidates = ArrayDeque<AcceptedFrameAnchor>()
    private val recentAcceptedQualityMetrics = ArrayDeque<FrameQualityMetrics>()
    private val recentAcceptedRegistrationMetrics = ArrayDeque<FrameRegistrationMetrics>()
    private val recentDirectReferenceOutcomes = ArrayDeque<Boolean>()
    private val recentDecisionKinds = ArrayDeque<DeepSkyFrameDecisionKind>()
    private val recentProcessingDurationsMs = ArrayDeque<Long>()

    init {
        scope.launch {
            for (queuedFrame in queue) {
                processQueuedFrame(queuedFrame)
            }
        }
    }

    fun startSession(selectedPresetOverride: DeepSkyPresetId? = null) {
        manualPresetOverride = selectedPresetOverride
        sessionActive = true
        generation.incrementAndGet()
        resetSessionState()
        val recommendation = selectedPresetOverride?.let {
            val preset = presetEngine.profile(it)
            DeepSkyPresetRecommendation(
                profile = preset,
                reason = "Manual preset ${preset.displayName} selected. Changing presets starts a fresh stack so thresholds stay consistent.",
            )
        } ?: presetEngine.recommend(currentCaptureContext())
        D.astro("Deep Sky session started presetOverride=$selectedPresetOverride")
        _state.value = enrichStateWithCaptureContext(
            DeepSkyLiveStackUiState(
                sessionStatus = DeepSkySessionStatus.Waiting,
                workflowState = DeepSkyWorkflowState.WaitingForFrames,
                performanceMode = DeepSkyPerformanceMode.Normal,
                statusLabel = "Waiting for tethered captures...",
                selectedPreset = recommendation.profile,
                recommendedPreset = recommendation.profile,
                recommendationReason = recommendation.reason,
                manualPresetOverride = selectedPresetOverride,
                isSessionActive = true,
                availablePresets = presetEngine.profiles,
            ),
        )
    }

    fun stopSession() {
        generation.incrementAndGet()
        sessionActive = false
        resetSessionState()
        D.astro("Deep Sky session stopped")
        _state.value = enrichStateWithCaptureContext(
            _state.value.copy(
                sessionStatus = DeepSkySessionStatus.Stopped,
                workflowState = DeepSkyWorkflowState.Stopped,
                statusLabel = "Deep Sky session stopped",
                previewBitmap = null,
                isSessionActive = false,
                queueDepth = 0,
                lastAcceptedFrameId = null,
                lastRejectionKind = null,
                consecutiveRejectedFrames = 0,
                activeDiagnosticMessage = null,
                referenceStatus = DeepSkyReferenceStatusSummary(status = DeepSkyReferenceStatus.None),
            ),
        )
    }

    fun resetSession() {
        startSession(manualPresetOverride)
    }

    fun submitCapturedFrame(frame: CapturedFrame) {
        if (!sessionActive) {
            D.astro("Ignoring captured frame=${frame.id} because the Deep Sky session is inactive")
            return
        }
        val sessionGeneration = generation.get()
        queue.trySend(QueuedFrame(sessionGeneration, frame))
        _state.value = _state.value.copy(
            queueDepth = _state.value.queueDepth + 1,
            statusLabel = "Queued ${frame.cameraMetadata["displayName"] ?: frame.id}",
        )
        D.astro("Queued frame=${frame.id} generation=$sessionGeneration")
    }

    fun updateSkyHintContext(locationSample: GeoTagLocationSample?, rotationHintDeg: Float?) {
        skyHintProvider.updateContext(locationSample, rotationHintDeg)
    }

    fun updateLiveViewCaptureContext(
        focalLengthMm: Float?,
        aperture: Float?,
        exposureSec: Double?,
    ) {
        liveViewFocalLengthMm = focalLengthMm?.takeIf { it > 0f }
        liveViewAperture = aperture?.takeIf { it > 0f }
        liveViewExposureSec = exposureSec?.takeIf { it > 0.0 }
        _state.value = enrichStateWithCaptureContext(_state.value)
    }

    fun setManualFocalLengthOverride(focalLengthMm: Float?) {
        manualFocalLengthOverrideMm = focalLengthMm
            ?.takeIf { it > 0f }
            ?.coerceIn(7f, 1200f)
        _state.value = enrichStateWithCaptureContext(_state.value)
    }

    private fun currentCaptureContext(
        frameFocalLengthMm: Float? = null,
        frameAperture: Float? = null,
        frameExposureSec: Double? = null,
    ): DeepSkyCaptureContext {
        return DeepSkyCaptureContext(
            focalLengthMm = manualFocalLengthOverrideMm ?: frameFocalLengthMm ?: liveViewFocalLengthMm,
            aperture = frameAperture ?: liveViewAperture,
            exposureSec = frameExposureSec ?: liveViewExposureSec,
            tripodFixed = true,
        )
    }

    private fun enrichStateWithCaptureContext(
        base: DeepSkyLiveStackUiState,
        frameFocalLengthMm: Float? = null,
        frameAperture: Float? = null,
        frameExposureSec: Double? = null,
    ): DeepSkyLiveStackUiState {
        val cameraFocalLengthMm = frameFocalLengthMm ?: liveViewFocalLengthMm
        val manualFocalLengthMm = manualFocalLengthOverrideMm
        val activeFocalLengthMm = manualFocalLengthMm ?: cameraFocalLengthMm
        val captureContext = currentCaptureContext(
            frameFocalLengthMm = frameFocalLengthMm,
            frameAperture = frameAperture,
            frameExposureSec = frameExposureSec,
        )
        val recommendation = presetEngine.recommend(captureContext)
        val selectedPreset = manualPresetOverride?.let { presetEngine.profile(it) } ?: recommendation.profile
        val recommendationReason = manualPresetOverride?.let {
            "Manual preset ${selectedPreset.displayName} selected. ${recommendation.reason}"
        } ?: recommendation.reason
        val focalLengthSourceLabel = when {
            manualFocalLengthMm != null -> "Manual"
            cameraFocalLengthMm != null -> "Camera"
            else -> "Manual input"
        }
        val fieldOfViewLabel = activeFocalLengthMm?.let { focalLengthMm ->
            val horizontalDeg = estimateFieldOfViewDeg(sensorSizeMm = 17.3f, focalLengthMm = focalLengthMm)
            val verticalDeg = estimateFieldOfViewDeg(sensorSizeMm = 13.0f, focalLengthMm = focalLengthMm)
            String.format(Locale.ENGLISH, "%.1f° × %.1f° field", horizontalDeg, verticalDeg)
        } ?: "Set focal length to size the center stack guide."
        val stackGuideFraction = activeFocalLengthMm?.let { focalLengthMm ->
            estimateStackGuideFraction(
                focalLengthMm = focalLengthMm,
                totalExposureSec = base.totalExposureSec,
                frameCount = base.frameCount,
            )
        } ?: 0.42f
        return base.copy(
            selectedPreset = selectedPreset,
            recommendedPreset = recommendation.profile,
            recommendationReason = recommendationReason,
            manualPresetOverride = manualPresetOverride,
            autoFocalLengthMm = cameraFocalLengthMm,
            manualFocalLengthMm = manualFocalLengthMm,
            activeFocalLengthMm = activeFocalLengthMm,
            usingManualFocalLength = manualFocalLengthMm != null || cameraFocalLengthMm == null,
            focalLengthSourceLabel = focalLengthSourceLabel,
            fieldOfViewLabel = fieldOfViewLabel,
            stackFrameLabel = activeFocalLengthMm?.let { focalLengthMm ->
                val baseLabel = when {
                    focalLengthMm <= 35f -> "Wide field guide"
                    focalLengthMm <= 150f -> "Mid-tele guide"
                    else -> "Tight center guide"
                }
                val progressLabel = buildStackGuideProgressLabel(
                    frameCount = base.frameCount,
                    totalExposureSec = base.totalExposureSec,
                )
                if (progressLabel == null) baseLabel else "$baseLabel · $progressLabel"
            } ?: "Center stack guide",
            stackFrameWidthFraction = stackGuideFraction,
            stackFrameHeightFraction = stackGuideFraction,
            availablePresets = if (base.availablePresets.isEmpty()) presetEngine.profiles else base.availablePresets,
        )
    }

    private fun estimateStackGuideFraction(
        focalLengthMm: Float,
        totalExposureSec: Double,
        frameCount: Int,
    ): Float {
        val horizontalDeg = estimateFieldOfViewDeg(sensorSizeMm = 17.3f, focalLengthMm = focalLengthMm)
        val baseFraction = ((horizontalDeg / 150f) + 0.18f).coerceIn(0.22f, 0.62f)
        val exposurePenalty = (totalExposureSec / 2400.0).toFloat().coerceIn(0f, 0.08f)
        val framePenalty = ((frameCount - 1).coerceAtLeast(0) * 0.0022f).coerceIn(0f, 0.07f)
        return (baseFraction - exposurePenalty - framePenalty).coerceIn(0.18f, 0.62f)
    }

    private fun buildStackGuideProgressLabel(
        frameCount: Int,
        totalExposureSec: Double,
    ): String? {
        if (frameCount <= 0 && totalExposureSec <= 0.0) {
            return null
        }
        val totalSeconds = totalExposureSec.roundToInt().coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val timeLabel = when {
            hours > 0 -> String.format(Locale.ENGLISH, "%dh %02dm", hours, minutes)
            minutes > 0 -> String.format(Locale.ENGLISH, "%dm %02ds", minutes, seconds)
            else -> String.format(Locale.ENGLISH, "%ds", seconds)
        }
        return "$frameCount frames · $timeLabel"
    }

    private fun estimateFieldOfViewDeg(sensorSizeMm: Float, focalLengthMm: Float): Float {
        if (focalLengthMm <= 0f) return 0f
        return Math.toDegrees(
            2.0 * atan((sensorSizeMm / (2.0f * focalLengthMm)).toDouble()),
        ).toFloat()
    }

    private suspend fun processQueuedFrame(queuedFrame: QueuedFrame) {
        _state.value = _state.value.copy(
            queueDepth = (_state.value.queueDepth - 1).coerceAtLeast(0),
        )
        if (!sessionActive || queuedFrame.generation != generation.get()) {
            D.astro("Dropping stale frame=${queuedFrame.frame.id}")
            return
        }

        val frame = queuedFrame.frame
        val recommendation = presetEngine.recommend(
            currentCaptureContext(
                frameFocalLengthMm = frame.focalLengthMm,
                frameAperture = frame.aperture,
                frameExposureSec = frame.exposureSec,
            ),
        )
        val selectedPreset = manualPresetOverride?.let { presetEngine.profile(it) } ?: recommendation.profile
        val recommendationReason = manualPresetOverride?.let {
            "Manual preset ${selectedPreset.displayName} selected. Recommendation would be ${recommendation.profile.displayName} based on the latest frame metadata."
        } ?: recommendation.reason
        _state.value = enrichStateWithCaptureContext(
            _state.value.copy(
                sessionStatus = DeepSkySessionStatus.Processing,
                workflowState = deriveWorkflowState(selectedPreset, processing = true),
                statusLabel = "Processing ${frame.cameraMetadata["displayName"] ?: frame.id}",
                selectedPreset = selectedPreset,
                recommendedPreset = recommendation.profile,
                recommendationReason = recommendationReason,
                lastRejectionReason = null,
            ),
            frameFocalLengthMm = frame.focalLengthMm,
            frameAperture = frame.aperture,
            frameExposureSec = frame.exposureSec,
        )

        val cadenceMs = (((frame.exposureSec ?: selectedPreset.stackCadenceExpectationSec) * 1000.0).toLong()).coerceAtLeast(1000L)
        val performanceBefore = selectPerformanceMode(
            currentMode = _state.value.performanceMode,
            snapshot = performanceProbe.snapshot(),
            rollingProcessingMs = rollingAverageProcessingMs(),
            cadenceMs = cadenceMs,
            policy = selectedPreset.performance,
        )
        if (performanceBefore == DeepSkyPerformanceMode.ProtectionPaused) {
            val decision = DeepSkyFrameDecision(
                kind = DeepSkyFrameDecisionKind.HardReject,
                reason = DeepSkyRejectionReason.ThermalOrPerformanceBackoff,
                explanation = "Preview processing is paused to protect the device.",
                weightMultiplier = 0f,
            )
            rejectFrame(
                frame = frame,
                preset = selectedPreset,
                decision = decision,
                qualityMetrics = null,
                registrationMetrics = null,
                timings = DeepSkyStageTimings(),
            )
            return
        }

        var decodeMs = 0L
        val decodeStart = nanoTimeSource()
        val decoded = runCatching {
            withContext(ioDispatcher) { frameDecoder.decode(frame) }
        }.onFailure { throwable ->
            D.err("ASTRO", "Frame decode failed for ${frame.id}", throwable)
        }.getOrNull()
        decodeMs = elapsedMs(decodeStart)

        if (decoded == null) {
            rejectFrame(
                frame = frame,
                preset = selectedPreset,
                decision = DeepSkyFrameDecision(
                    kind = DeepSkyFrameDecisionKind.HardReject,
                    reason = DeepSkyRejectionReason.DecodeFailure,
                    explanation = "The imported frame could not be decoded.",
                    weightMultiplier = 0f,
                ),
                qualityMetrics = null,
                registrationMetrics = null,
                timings = DeepSkyStageTimings(decodeMs = decodeMs, totalMs = decodeMs),
            )
            return
        }

        try {
            if (queuedFrame.generation != generation.get() || !sessionActive) {
                return
            }

            if (stackAnchor == null) {
                val plan = memoryPolicy.evaluate(decoded, selectedPreset)
                memoryPlan = plan
                if (!plan.success) {
                    D.stack("Deep Sky memory plan rejected frame=${frame.id}: ${plan.explanation}")
                    sessionActive = false
                    stackEngine.reset()
                    stackRenderer.reset()
                    _state.value = _state.value.copy(
                        sessionStatus = DeepSkySessionStatus.Error,
                        workflowState = DeepSkyWorkflowState.MemoryLimited,
                        performanceMode = plan.initialPerformanceMode,
                        statusLabel = "Deep Sky stack needs more memory than this device can safely spare.",
                        lastRejectionReason = plan.explanation,
                        lastRejectionKind = DeepSkyRejectionReason.MemoryBudgetExceeded,
                        lastDecision = DeepSkyFrameDecision(
                            kind = DeepSkyFrameDecisionKind.HardReject,
                            reason = DeepSkyRejectionReason.MemoryBudgetExceeded,
                            explanation = plan.explanation,
                            weightMultiplier = 0f,
                        ),
                        activeDiagnosticMessage = plan.explanation,
                        isSessionActive = false,
                    )
                    return
                }
                stackEngine.initialize(
                    decodedFrame = decoded,
                    config = StackEngineConfig(
                        previewMode = selectedPreset.accumulation.previewMode,
                        fullResMode = selectedPreset.accumulation.fullResPreferredMode,
                        previewWinsorStartFrame = selectedPreset.accumulation.previewWinsorStartFrame,
                        winsorSigmaMultiplier = selectedPreset.accumulation.winsorSigmaMultiplier,
                        enableWinsorizedPreview = plan.enableWinsorizedPreview,
                        enableWinsorizedFullRes = plan.enableWinsorizedFullRes,
                    ),
                )
            }

            val detectStart = nanoTimeSource()
            val stars = starDetector.detect(decoded.alignmentLuma, decoded.alignmentWidth, decoded.alignmentHeight)
            val detectMs = elapsedMs(detectStart)

            val qualityStart = nanoTimeSource()
            val provisionalQuality = qualityAnalyzer.analyzeFrame(
                decoded = decoded,
                stars = stars,
                preset = selectedPreset,
                recentAcceptedMetrics = recentAcceptedQualityMetrics.toList(),
                registrationConfidenceHint = if (stackAnchor == null) 1f else null,
            )
            val qualityMs = elapsedMs(qualityStart)

            if (stackAnchor == null) {
                val firstDecision = qualityAnalyzer.decideFrame(
                    qualityMetrics = provisionalQuality,
                    registrationMetrics = null,
                    preset = selectedPreset,
                    recentAcceptedMetrics = recentAcceptedQualityMetrics.toList(),
                    performanceMode = performanceBefore,
                    transformPlausible = true,
                )
                val firstFrameDecision = if (firstDecision.kind == DeepSkyFrameDecisionKind.SoftAccept) {
                    firstDecision.copy(kind = DeepSkyFrameDecisionKind.HardReject, weightMultiplier = 0f)
                } else {
                    firstDecision
                }
                if (firstFrameDecision.kind == DeepSkyFrameDecisionKind.HardReject) {
                    rejectFrame(
                        frame = frame,
                        preset = selectedPreset,
                        decision = firstFrameDecision,
                        qualityMetrics = provisionalQuality,
                        registrationMetrics = null,
                        timings = DeepSkyStageTimings(
                            decodeMs = decodeMs,
                            detectMs = detectMs,
                            qualityMs = qualityMs,
                            totalMs = decodeMs + detectMs + qualityMs,
                        ),
                    )
                    return
                }
                acceptFirstFrame(
                    frame = frame,
                    preset = selectedPreset,
                    decoded = decoded,
                    stars = stars,
                    qualityMetrics = provisionalQuality,
                    decision = firstFrameDecision,
                    performanceMode = maxOf(performanceBefore, memoryPlan?.initialPerformanceMode ?: DeepSkyPerformanceMode.Normal, compareBy { it.ordinal }),
                    timings = DeepSkyStageTimings(
                        decodeMs = decodeMs,
                        detectMs = detectMs,
                        qualityMs = qualityMs,
                        accumulateMs = 0L,
                        renderMs = 0L,
                        totalMs = decodeMs + detectMs + qualityMs,
                    ),
                )
                return
            }

            val registerStart = nanoTimeSource()
            val registration = registerFrame(
                frame = frame,
                preset = selectedPreset,
                decoded = decoded,
                stars = stars,
            )
            val registerMs = elapsedMs(registerStart)
            val registrationMetrics = registration?.metrics
            val qualityMetrics = qualityAnalyzer.analyzeFrame(
                decoded = decoded,
                stars = stars,
                preset = selectedPreset,
                recentAcceptedMetrics = recentAcceptedQualityMetrics.toList(),
                registrationConfidenceHint = registrationMetrics?.confidenceScore,
            )
            val transformPlausible = registration?.let {
                selectedPreset.registration.isTransformPlausible(
                    transform = it.transform,
                    alignmentWidth = decoded.alignmentWidth,
                    fallbackPath = it.metrics.usedFallbackPath,
                )
            } ?: false
            val decision = registration?.let {
                qualityAnalyzer.decideFrame(
                    qualityMetrics = qualityMetrics,
                    registrationMetrics = it.metrics,
                    preset = selectedPreset,
                    recentAcceptedMetrics = recentAcceptedQualityMetrics.toList(),
                    performanceMode = performanceBefore,
                    transformPlausible = transformPlausible,
                )
            } ?: DeepSkyFrameDecision(
                kind = DeepSkyFrameDecisionKind.HardReject,
                reason = DeepSkyRejectionReason.RegistrationLowConfidence,
                explanation = "No usable registration candidate could be established.",
                weightMultiplier = 0f,
            )

            if (registration == null || decision.kind == DeepSkyFrameDecisionKind.HardReject) {
                rejectFrame(
                    frame = frame,
                    preset = selectedPreset,
                    decision = decision,
                    qualityMetrics = qualityMetrics,
                    registrationMetrics = registrationMetrics,
                    timings = DeepSkyStageTimings(
                        decodeMs = decodeMs,
                        detectMs = detectMs,
                        qualityMs = qualityMs,
                        registerMs = registerMs,
                        totalMs = decodeMs + detectMs + qualityMs + registerMs,
                    ),
                )
                return
            }

            val accumulateStart = nanoTimeSource()
            stackEngine.accumulate(
                decodedFrame = decoded,
                alignmentTransform = registration.transform,
                weight = decision.weightMultiplier,
            )
            val accumulateMs = elapsedMs(accumulateStart)
            val newAcceptedIndex = stats.acceptedFrameCount + 1
            val acceptedAnchor = AcceptedFrameAnchor(
                frameId = frame.id,
                stars = stars,
                qualityMetrics = qualityMetrics,
                transformToStackSpace = registration.transform,
                acceptedIndex = newAcceptedIndex,
                referenceCandidateScore = qualityMetrics.referenceCandidateScore,
            )
            lastAcceptedAnchor = acceptedAnchor
            if (!registration.metrics.usedFallbackPath) {
                lastDirectReferenceTransform = registration.transform
                fallbackChainLength = 0
            } else {
                fallbackChainLength += 1
            }
            updateReferenceElection(
                acceptedAnchor = acceptedAnchor,
                preset = selectedPreset,
            )
            recordAcceptedMetrics(
                qualityMetrics = qualityMetrics,
                registrationMetrics = registration.metrics,
                decision = decision,
                frameExposureSec = frame.exposureSec ?: 0.0,
                weight = decision.weightMultiplier,
            )
            val renderStart = nanoTimeSource()
            val performanceAfter = selectPerformanceMode(
                currentMode = performanceBefore,
                snapshot = performanceProbe.snapshot(),
                rollingProcessingMs = rollingAverageProcessingMs(),
                cadenceMs = cadenceMs,
                policy = selectedPreset.performance,
            )
            val previewBitmap = if (shouldRenderFrame(selectedPreset, performanceAfter)) {
                stackRenderer.render(
                    snapshot = stackEngine.snapshot(),
                    preset = selectedPreset,
                    performanceMode = performanceAfter,
                )
            } else {
                _state.value.previewBitmap
            }
            val renderMs = elapsedMs(renderStart)
            val totalMs = decodeMs + detectMs + qualityMs + registerMs + accumulateMs + renderMs
            recordProcessingDuration(totalMs)
            val workflowState = deriveWorkflowState(selectedPreset, processing = false, performanceMode = performanceAfter, lastDecision = decision)
            val timings = DeepSkyStageTimings(
                decodeMs = decodeMs,
                detectMs = detectMs,
                qualityMs = qualityMs,
                registerMs = registerMs,
                accumulateMs = accumulateMs,
                renderMs = renderMs,
                totalMs = totalMs,
            )
            val diagnosticMessage = DeepSkyDiagnosticCatalog.describeDecision(decision)
                ?: DeepSkyDiagnosticCatalog.describePerformanceMode(performanceAfter)
                ?: "Stack is running."
            _state.value = _state.value.copy(
                sessionStatus = DeepSkySessionStatus.Running,
                workflowState = workflowState,
                performanceMode = performanceAfter,
                statusLabel = if (workflowState == DeepSkyWorkflowState.ElectingReference) {
                    "Evaluating reference candidates..."
                } else {
                    "Stack updated with ${frame.cameraMetadata["displayName"] ?: frame.id}"
                },
                previewBitmap = previewBitmap,
                frameCount = stats.acceptedFrameCount,
                softAcceptedCount = stats.softAcceptedCount,
                rejectedCount = stats.hardRejectedCount,
                hardRejectedCount = stats.hardRejectedCount,
                totalExposureSec = stats.totalExposureSec,
                effectiveIntegrationSec = stats.effectiveIntegrationSec,
                rollingAcceptanceRate = stats.rollingAcceptanceRate,
                lastRegistrationScore = registration.metrics.confidenceScore,
                detectedStarCount = qualityMetrics.detectedStarCount,
                matchedStarCount = registration.metrics.matchedStars,
                lastRegistrationMetrics = registration.metrics,
                lastFrameQualityMetrics = qualityMetrics,
                lastDecision = decision,
                lastStageTimings = timings,
                sessionQualitySummary = qualityAnalyzer.summarizeSession(
                    qualityMetrics = recentAcceptedQualityMetrics.toList(),
                    registrationMetrics = recentAcceptedRegistrationMetrics.toList(),
                ),
                lastAcceptedFrameId = frame.id,
                lastRejectionReason = null,
                lastRejectionKind = null,
                consecutiveRejectedFrames = 0,
                referenceStatus = currentReferenceStatus(selectedPreset),
                activeDiagnosticMessage = diagnosticMessage,
            )
            D.stack(
                "Accepted frame=${frame.id} decision=${decision.kind} weight=${"%.2f".format(decision.weightMultiplier)} " +
                    "conf=${"%.3f".format(registration.metrics.confidenceScore)} inliers=${registration.metrics.matchedStars} " +
                    "residual=${"%.2f".format(registration.metrics.residualPx)} mode=${registration.metrics.transformModel} " +
                    "fallback=${registration.metrics.usedFallbackPath} perf=$performanceAfter totalMs=$totalMs",
            )
        } finally {
            frameDecoder.recycle(frame.id, decoded)
        }
    }

    private fun acceptFirstFrame(
        frame: CapturedFrame,
        preset: DeepSkyPresetProfile,
        decoded: DecodedFrame,
        stars: List<DetectedStar>,
        qualityMetrics: FrameQualityMetrics,
        decision: DeepSkyFrameDecision,
        performanceMode: DeepSkyPerformanceMode,
        timings: DeepSkyStageTimings,
    ) {
        stackEngine.accumulate(
            decodedFrame = decoded,
            alignmentTransform = RegistrationTransform.Identity,
            weight = 1f,
        )
        val anchor = AcceptedFrameAnchor(
            frameId = frame.id,
            stars = stars,
            qualityMetrics = qualityMetrics,
            transformToStackSpace = RegistrationTransform.Identity,
            acceptedIndex = 1,
            referenceCandidateScore = qualityMetrics.referenceCandidateScore,
        )
        stackAnchor = anchor
        registrationReference = anchor
        lastAcceptedAnchor = anchor
        lastDirectReferenceTransform = RegistrationTransform.Identity
        referenceCandidates += anchor
        recordAcceptedMetrics(
            qualityMetrics = qualityMetrics,
            registrationMetrics = FrameRegistrationMetrics(
                matchedStars = qualityMetrics.usableStarCount,
                inlierRatio = 1f,
                residualPx = 0f,
                rotationDeg = 0f,
                translationMagnitudePx = 0f,
                scaleX = 1f,
                scaleY = 1f,
                shear = 0f,
                confidenceScore = 1f,
                usedFallbackPath = false,
                referenceFrameId = frame.id,
                transformModel = DeepSkyTransformModel.Rigid,
            ),
            decision = decision,
            frameExposureSec = frame.exposureSec ?: 0.0,
            weight = 1f,
        )
        val preview = stackRenderer.render(
            snapshot = stackEngine.snapshot(),
            preset = preset,
            performanceMode = performanceMode,
        )
        val workflowState = deriveWorkflowState(preset, processing = false, performanceMode = performanceMode, lastDecision = decision)
        val diagnosticMessage = memoryPlan?.explanation ?: "Reference frame locked."
        _state.value = _state.value.copy(
            sessionStatus = DeepSkySessionStatus.Running,
            workflowState = workflowState,
            performanceMode = performanceMode,
            statusLabel = if (workflowState == DeepSkyWorkflowState.ElectingReference) {
                "Reference election started"
            } else {
                "Reference frame locked"
            },
            previewBitmap = preview,
            frameCount = stats.acceptedFrameCount,
            softAcceptedCount = stats.softAcceptedCount,
            rejectedCount = stats.hardRejectedCount,
            hardRejectedCount = stats.hardRejectedCount,
            totalExposureSec = stats.totalExposureSec,
            effectiveIntegrationSec = stats.effectiveIntegrationSec,
            rollingAcceptanceRate = stats.rollingAcceptanceRate,
            lastRegistrationScore = 1f,
            detectedStarCount = qualityMetrics.detectedStarCount,
            matchedStarCount = qualityMetrics.usableStarCount,
            lastRegistrationMetrics = recentAcceptedRegistrationMetrics.lastOrNull(),
            lastFrameQualityMetrics = qualityMetrics,
            lastDecision = decision,
            lastStageTimings = timings,
            sessionQualitySummary = qualityAnalyzer.summarizeSession(
                qualityMetrics = recentAcceptedQualityMetrics.toList(),
                registrationMetrics = recentAcceptedRegistrationMetrics.toList(),
            ),
            lastAcceptedFrameId = frame.id,
            lastRejectionKind = null,
            consecutiveRejectedFrames = 0,
            referenceStatus = currentReferenceStatus(preset),
            activeDiagnosticMessage = diagnosticMessage,
        )
        D.stack("Accepted first Deep Sky frame=${frame.id} stars=${qualityMetrics.usableStarCount}")
    }

    private fun registerFrame(
        frame: CapturedFrame,
        preset: DeepSkyPresetProfile,
        decoded: DecodedFrame,
        stars: List<DetectedStar>,
    ): FrameRegistrationResult? {
        val reference = registrationReference ?: return null
        val hint = skyHintProvider.buildHint(frame)
        val direct = frameRegistrar.register(
            referenceStars = reference.stars,
            currentStars = stars,
            registrationPolicy = preset.registration,
            alignmentWidth = decoded.alignmentWidth,
            alignmentHeight = decoded.alignmentHeight,
            rotationHintDeg = hint.rotationHintDeg,
            referenceFrameId = reference.frameId,
            usedFallbackPath = false,
        )
        if (direct.success) {
            recentDirectReferenceOutcomes += true
            trimDeque(recentDirectReferenceOutcomes, preset.reference.driftWindowFrames)
            val directToStack = if (reference.transformToStackSpace == RegistrationTransform.Identity) {
                direct.transform
            } else {
                composeTransforms(direct.transform, reference.transformToStackSpace)
            }
            val metrics = direct.metrics.copy(
                translationMagnitudePx = directToStack.translationMagnitude(),
                rotationDeg = directToStack.rotationDeg,
                scaleX = directToStack.scaleX,
                scaleY = directToStack.scaleY,
                shear = directToStack.shear,
                usedFallbackPath = false,
                referenceFrameId = reference.frameId,
                transformModel = directToStack.model,
            )
            return direct.copy(transform = directToStack, metrics = metrics)
        }

        val previous = lastAcceptedAnchor
        if (previous == null || previous.frameId == reference.frameId || fallbackChainLength >= preset.registration.maxFallbackChainFrames) {
            recentDirectReferenceOutcomes += false
            trimDeque(recentDirectReferenceOutcomes, preset.reference.driftWindowFrames)
            return null
        }

        val previousResult = frameRegistrar.register(
            referenceStars = previous.stars,
            currentStars = stars,
            registrationPolicy = preset.registration,
            alignmentWidth = decoded.alignmentWidth,
            alignmentHeight = decoded.alignmentHeight,
            rotationHintDeg = hint.rotationHintDeg,
            referenceFrameId = previous.frameId,
            usedFallbackPath = true,
        )
        if (!previousResult.success) {
            recentDirectReferenceOutcomes += false
            trimDeque(recentDirectReferenceOutcomes, preset.reference.driftWindowFrames)
            return null
        }

        val composedTransform = composeTransforms(
            currentToPrevious = previousResult.transform,
            previousToReference = previous.transformToStackSpace,
        )
        val deviationFromDirect = lastDirectReferenceTransform?.let { directTransform ->
            hypot(
                (directTransform.dx - composedTransform.dx).toDouble(),
                (directTransform.dy - composedTransform.dy).toDouble(),
            ).toFloat()
        } ?: 0f
        if (!preset.registration.isTransformPlausible(composedTransform, decoded.alignmentWidth, fallbackPath = true) ||
            deviationFromDirect > preset.registration.maxReferenceDeviationPxFor(decoded.alignmentWidth)
        ) {
            recentDirectReferenceOutcomes += false
            trimDeque(recentDirectReferenceOutcomes, preset.reference.driftWindowFrames)
            return null
        }

        recentDirectReferenceOutcomes += false
        trimDeque(recentDirectReferenceOutcomes, preset.reference.driftWindowFrames)
        val metrics = previousResult.metrics.copy(
            translationMagnitudePx = composedTransform.translationMagnitude(),
            rotationDeg = composedTransform.rotationDeg,
            scaleX = composedTransform.scaleX,
            scaleY = composedTransform.scaleY,
            shear = composedTransform.shear,
            usedFallbackPath = true,
            referenceFrameId = previous.frameId,
            transformModel = composedTransform.model,
        )
        return previousResult.copy(transform = composedTransform, metrics = metrics)
    }

    private fun rejectFrame(
        frame: CapturedFrame,
        preset: DeepSkyPresetProfile,
        decision: DeepSkyFrameDecision,
        qualityMetrics: FrameQualityMetrics?,
        registrationMetrics: FrameRegistrationMetrics?,
        timings: DeepSkyStageTimings,
    ) {
        recordRejectedDecision(decision)
        val workflowState = when (decision.reason) {
            DeepSkyRejectionReason.MemoryBudgetExceeded -> DeepSkyWorkflowState.MemoryLimited
            DeepSkyRejectionReason.ThermalOrPerformanceBackoff -> DeepSkyWorkflowState.Degraded
            else -> {
                if (stats.acceptedFrameCount == 0) DeepSkyWorkflowState.ActionRequired else DeepSkyWorkflowState.StackingUnstable
            }
        }
        val performanceMode = if (decision.reason == DeepSkyRejectionReason.ThermalOrPerformanceBackoff) {
            DeepSkyPerformanceMode.ProtectionPaused
        } else {
            _state.value.performanceMode
        }
        val explanation = DeepSkyDiagnosticCatalog.describeDecision(decision) ?: decision.explanation
        _state.value = _state.value.copy(
            sessionStatus = if (stats.acceptedFrameCount > 0) DeepSkySessionStatus.Running else DeepSkySessionStatus.Waiting,
            workflowState = workflowState,
            performanceMode = performanceMode,
            statusLabel = "Waiting for next capture...",
            rejectedCount = stats.hardRejectedCount,
            hardRejectedCount = stats.hardRejectedCount,
            rollingAcceptanceRate = stats.rollingAcceptanceRate,
            detectedStarCount = qualityMetrics?.detectedStarCount ?: _state.value.detectedStarCount,
            matchedStarCount = registrationMetrics?.matchedStars ?: _state.value.matchedStarCount,
            lastRegistrationScore = registrationMetrics?.confidenceScore ?: _state.value.lastRegistrationScore,
            lastRegistrationMetrics = registrationMetrics ?: _state.value.lastRegistrationMetrics,
            lastFrameQualityMetrics = qualityMetrics ?: _state.value.lastFrameQualityMetrics,
            lastDecision = decision,
            lastStageTimings = timings,
            lastRejectionReason = explanation,
            lastRejectionKind = decision.reason,
            consecutiveRejectedFrames = _state.value.consecutiveRejectedFrames + 1,
            activeDiagnosticMessage = explanation,
            referenceStatus = currentReferenceStatus(preset),
        )
        D.stack("Rejected frame=${frame.id} reason=${decision.reason} explanation=$explanation")
    }

    private fun updateReferenceElection(
        acceptedAnchor: AcceptedFrameAnchor,
        preset: DeepSkyPresetProfile,
    ) {
        referenceCandidates += acceptedAnchor
        trimDeque(referenceCandidates, preset.reference.earlyRebaseAcceptedFrames)
        val currentReference = registrationReference
        val bestCandidate = referenceCandidates.maxByOrNull { it.referenceCandidateScore } ?: acceptedAnchor
        if (stats.acceptedFrameCount < preset.reference.warmupAcceptedFrames) {
            registrationReference = bestCandidate
            return
        }
        if (currentReference == null) {
            registrationReference = bestCandidate
            return
        }
        if (!earlyRebasePerformed &&
            acceptedAnchor.acceptedIndex <= preset.reference.earlyRebaseAcceptedFrames &&
            bestCandidate.referenceCandidateScore >= currentReference.referenceCandidateScore * (1f + preset.reference.earlyRebaseImprovementFraction)
        ) {
            registrationReference = bestCandidate
            earlyRebasePerformed = true
            D.stack("Rebased Deep Sky registration reference to ${bestCandidate.frameId} during early reference election")
            return
        }
        if (shouldRebaseForDrift(preset, currentReference, bestCandidate)) {
            registrationReference = bestCandidate
            earlyRebasePerformed = true
            D.stack("Rebased Deep Sky registration reference to ${bestCandidate.frameId} due to drift pressure")
        }
    }

    private fun shouldRebaseForDrift(
        preset: DeepSkyPresetProfile,
        currentReference: AcceptedFrameAnchor,
        bestCandidate: AcceptedFrameAnchor,
    ): Boolean {
        if (recentDirectReferenceOutcomes.size < preset.reference.driftWindowFrames) return false
        val successRate = recentDirectReferenceOutcomes.count { it }.toFloat() / recentDirectReferenceOutcomes.size.toFloat()
        return successRate < preset.reference.driftSuccessFloor &&
            bestCandidate.frameId != currentReference.frameId &&
            bestCandidate.referenceCandidateScore >= currentReference.referenceCandidateScore * (1f + preset.reference.driftRebaseImprovementFraction)
    }

    private fun recordAcceptedMetrics(
        qualityMetrics: FrameQualityMetrics,
        registrationMetrics: FrameRegistrationMetrics,
        decision: DeepSkyFrameDecision,
        frameExposureSec: Double,
        weight: Float,
    ) {
        recentAcceptedQualityMetrics += qualityMetrics
        trimDeque(recentAcceptedQualityMetrics, 8)
        recentAcceptedRegistrationMetrics += registrationMetrics
        trimDeque(recentAcceptedRegistrationMetrics, 8)
        recentDecisionKinds += decision.kind
        trimDeque(recentDecisionKinds, 12)
        stats = stats.copy(
            acceptedFrameCount = stats.acceptedFrameCount + 1,
            softAcceptedCount = stats.softAcceptedCount + if (decision.kind == DeepSkyFrameDecisionKind.SoftAccept) 1 else 0,
            totalExposureSec = stats.totalExposureSec + frameExposureSec,
            effectiveIntegrationSec = stats.effectiveIntegrationSec + (frameExposureSec * weight),
            rollingAcceptanceRate = acceptedRatio(),
        )
    }

    private fun recordRejectedDecision(decision: DeepSkyFrameDecision) {
        recentDecisionKinds += decision.kind
        trimDeque(recentDecisionKinds, 12)
        stats = stats.copy(
            hardRejectedCount = stats.hardRejectedCount + 1,
            rollingAcceptanceRate = acceptedRatio(),
        )
    }

    private fun acceptedRatio(): Float {
        if (recentDecisionKinds.isEmpty()) return 0f
        return recentDecisionKinds.count { it != DeepSkyFrameDecisionKind.HardReject }.toFloat() / recentDecisionKinds.size.toFloat()
    }

    private fun shouldRenderFrame(
        preset: DeepSkyPresetProfile,
        performanceMode: DeepSkyPerformanceMode,
    ): Boolean {
        val acceptedCount = max(1, stats.acceptedFrameCount)
        val every = when (performanceMode) {
            DeepSkyPerformanceMode.Normal -> preset.performance.renderEveryAcceptedFramesNormal
            DeepSkyPerformanceMode.DegradedPreview,
            DeepSkyPerformanceMode.Throttled,
            -> preset.performance.renderEveryAcceptedFramesThrottled
            DeepSkyPerformanceMode.ProtectionPaused -> Int.MAX_VALUE
        }
        return acceptedCount % max(1, every) == 0
    }

    private fun deriveWorkflowState(
        preset: DeepSkyPresetProfile,
        processing: Boolean,
        performanceMode: DeepSkyPerformanceMode = _state.value.performanceMode,
        lastDecision: DeepSkyFrameDecision? = _state.value.lastDecision,
    ): DeepSkyWorkflowState {
        if (!sessionActive) return if (_state.value.sessionStatus == DeepSkySessionStatus.Stopped) DeepSkyWorkflowState.Stopped else DeepSkyWorkflowState.Idle
        if (processing) return DeepSkyWorkflowState.WaitingForFrames
        if (memoryPlan?.success == false) return DeepSkyWorkflowState.MemoryLimited
        if (stackAnchor == null) return DeepSkyWorkflowState.WaitingForFrames
        if (stats.acceptedFrameCount < preset.reference.warmupAcceptedFrames) return DeepSkyWorkflowState.ElectingReference
        if (performanceMode != DeepSkyPerformanceMode.Normal) return DeepSkyWorkflowState.Degraded
        if (lastDecision?.kind == DeepSkyFrameDecisionKind.SoftAccept || _state.value.consecutiveRejectedFrames > 0) {
            return DeepSkyWorkflowState.StackingUnstable
        }
        return DeepSkyWorkflowState.StackingHealthy
    }

    private fun currentReferenceStatus(preset: DeepSkyPresetProfile): DeepSkyReferenceStatusSummary {
        val activeReference = registrationReference
        val bestCandidate = referenceCandidates.maxByOrNull { it.referenceCandidateScore }
        val status = when {
            activeReference == null -> DeepSkyReferenceStatus.None
            stats.acceptedFrameCount < preset.reference.warmupAcceptedFrames -> DeepSkyReferenceStatus.WarmingUp
            activeReference.frameId != stackAnchor?.frameId -> {
                if (earlyRebasePerformed) DeepSkyReferenceStatus.Rebased else DeepSkyReferenceStatus.Elected
            }
            recentDirectReferenceOutcomes.isNotEmpty() &&
                recentDirectReferenceOutcomes.count { it }.toFloat() / recentDirectReferenceOutcomes.size.toFloat() < preset.reference.driftSuccessFloor ->
                DeepSkyReferenceStatus.Drifting
            else -> DeepSkyReferenceStatus.Elected
        }
        return DeepSkyReferenceStatusSummary(
            status = status,
            activeReferenceFrameId = activeReference?.frameId,
            candidateFrameId = bestCandidate?.frameId,
            warmupAcceptedFrames = minOf(stats.acceptedFrameCount, preset.reference.warmupAcceptedFrames),
            targetWarmupFrames = preset.reference.warmupAcceptedFrames,
        )
    }

    private fun recordProcessingDuration(totalMs: Long) {
        recentProcessingDurationsMs += totalMs
        trimDeque(recentProcessingDurationsMs, 6)
    }

    private fun rollingAverageProcessingMs(): Long {
        if (recentProcessingDurationsMs.isEmpty()) return 0L
        return (recentProcessingDurationsMs.sum().toDouble() / recentProcessingDurationsMs.size.toDouble()).toLong()
    }

    private fun elapsedMs(startNanos: Long): Long {
        return ((nanoTimeSource() - startNanos) / 1_000_000L).coerceAtLeast(0L)
    }

    private fun resetSessionState() {
        stackAnchor = null
        registrationReference = null
        lastAcceptedAnchor = null
        lastDirectReferenceTransform = null
        memoryPlan = null
        stats = DeepSkySessionStats()
        fallbackChainLength = 0
        earlyRebasePerformed = false
        referenceCandidates.clear()
        recentAcceptedQualityMetrics.clear()
        recentAcceptedRegistrationMetrics.clear()
        recentDirectReferenceOutcomes.clear()
        recentDecisionKinds.clear()
        recentProcessingDurationsMs.clear()
        stackEngine.reset()
        stackRenderer.reset()
    }

    private fun <T> trimDeque(
        deque: ArrayDeque<T>,
        limit: Int,
    ) {
        while (deque.size > limit) {
            if (deque.isEmpty()) {
                return
            }
            deque.removeFirst()
        }
    }
}
