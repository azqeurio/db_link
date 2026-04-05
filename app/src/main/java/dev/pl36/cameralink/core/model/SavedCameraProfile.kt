package dev.pl36.cameralink.core.model

data class SavedCameraProfile(
    val ssid: String,
    val password: String,
    val displayName: String = ssid,
    val bleName: String? = null,
    val blePass: String? = null,
    val bleAddress: String? = null,
    val playTargetSlot: Int? = null,
    val lastUsedAtMillis: Long = System.currentTimeMillis(),
)
