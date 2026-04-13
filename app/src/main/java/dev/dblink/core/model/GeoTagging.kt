package dev.dblink.core.model

data class GeoTagLocationSample(
    val capturedAtMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val speedMps: Float? = null,
    val bearingDegrees: Float? = null,
    val accuracyMeters: Float,
    val placeName: String? = null,
    val source: String,
)

data class GeoTaggingSnapshot(
    val sessionActive: Boolean = false,
    val statusLabel: String = "Ready",
    val latestSample: GeoTagLocationSample? = null,
    val samples: List<GeoTagLocationSample> = emptyList(),
    val clockOffsetMinutes: Int = 0,
    val permissionGranted: Boolean = false,
    val precisePermissionGranted: Boolean = false,
    val totalPinsCaptured: Int = 0,
)
