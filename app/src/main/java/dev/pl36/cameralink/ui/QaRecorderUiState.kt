package dev.pl36.cameralink.ui

enum class QaRecordSource(val label: String) {
    System("System"),
    Ui("UI"),
    Camera("Camera"),
    Manual("Manual"),
}

data class QaRecordEntry(
    val id: Long,
    val timestampMs: Long,
    val source: QaRecordSource,
    val title: String,
    val detail: String = "",
    val observedCameraValue: String = "",
    val note: String = "",
)

data class QaRecorderUiState(
    val sessionActive: Boolean = false,
    val sessionLabel: String = "",
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
    val autoStopDeadlineMs: Long? = null,
    val activeTriggerTitle: String = "",
    val lastRoute: String = "dashboard",
    val statusLabel: String = "Ready to record",
    val finishReason: String = "",
    val reportPath: String? = null,
    val entries: List<QaRecordEntry> = emptyList(),
)
