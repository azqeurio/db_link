package dev.pl36.cameralink.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.pl36.cameralink.core.logging.D
import dev.pl36.cameralink.core.model.CameraNameNormalizer
import dev.pl36.cameralink.core.model.GeoTagLocationSample
import dev.pl36.cameralink.core.model.GeoTaggingSnapshot
import dev.pl36.cameralink.core.model.SavedCameraProfile
import dev.pl36.cameralink.core.model.SettingItem
import dev.pl36.cameralink.feature.qr.WifiCredentials
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.appPreferencesDataStore by preferencesDataStore(name = "app_preferences")

class AppPreferencesRepository(
    private val context: Context,
) {
    data class ReconnectBleIdentity(
        val bleName: String?,
        val blePass: String?,
        val bleAddress: String?,
    )

    suspend fun loadSettings(defaults: List<SettingItem>): List<SettingItem> {
        D.pref("loadSettings: ${defaults.size} defaults")
        val preferences = context.appPreferencesDataStore.data.first()
        val result = defaults.map { setting ->
            setting.copy(
                enabled = preferences[settingKey(setting.id)] ?: setting.enabled,
            )
        }
        D.pref("loadSettings done: ${result.joinToString { "${it.id}=${it.enabled}" }}")
        return result
    }

    suspend fun saveSetting(settingId: String, enabled: Boolean) {
        D.pref("saveSetting: $settingId=$enabled")
        context.appPreferencesDataStore.edit { preferences ->
            preferences[settingKey(settingId)] = enabled
        }
    }

    suspend fun loadStringPref(key: String, default: String): String {
        val preferences = context.appPreferencesDataStore.data.first()
        return preferences[stringPreferencesKey("pref_$key")] ?: default
    }

    suspend fun saveStringPref(key: String, value: String) {
        D.pref("saveStringPref: $key=$value")
        context.appPreferencesDataStore.edit { preferences ->
            preferences[stringPreferencesKey("pref_$key")] = value
        }
    }

    suspend fun loadGeoTagging(): GeoTaggingSnapshot {
        D.pref("loadGeoTagging")
        val preferences = context.appPreferencesDataStore.data.first()
        val samples = parseSamples(preferences[samplesKey])
        val latestSample = samples.maxByOrNull { it.capturedAtMillis }
        val snapshot = GeoTaggingSnapshot(
            statusLabel = if (latestSample != null) "Recent pin loaded" else "Ready",
            latestSample = latestSample,
            samples = samples,
            clockOffsetMinutes = preferences[clockOffsetKey] ?: 0,
            totalPinsCaptured = preferences[totalPinsKey] ?: samples.size,
        )
        D.pref("loadGeoTagging done: ${samples.size} samples, offset=${snapshot.clockOffsetMinutes}min, totalPins=${snapshot.totalPinsCaptured}")
        return snapshot
    }

    // Camera Credentials
    suspend fun saveCameraCredentials(
        ssid: String,
        password: String,
        bleName: String? = null,
        blePass: String? = null,
    ) {
        D.pref(
            "saveCameraCredentials: ssid=$ssid, bleName=$bleName, " +
                "blePassSet=${!blePass.isNullOrBlank()}, blePassLen=${blePass?.length ?: 0}",
        )
        val updatedProfiles = upsertCameraProfile(
            profiles = loadSavedCameraProfiles(),
            profile = SavedCameraProfile(
                ssid = ssid,
                password = password,
                displayName = CameraNameNormalizer.savedCameraDisplayName(ssid = ssid),
                bleName = bleName,
                blePass = blePass,
                playTargetSlot = loadSavedCameraProfile(ssid)?.playTargetSlot,
                lastUsedAtMillis = System.currentTimeMillis(),
            ),
        )
        context.appPreferencesDataStore.edit { preferences ->
            preferences[cameraProfilesKey] = serializeCameraProfiles(updatedProfiles)
            preferences[selectedCameraSsidKey] = ssid
            clearLegacyCameraKeys(preferences)
        }
    }

    suspend fun loadCameraCredentials(ssid: String? = null): WifiCredentials? {
        val preferences = context.appPreferencesDataStore.data.first()
        val profiles = loadSavedCameraProfiles(preferences)
        val requestedSsid = ssid ?: preferences[selectedCameraSsidKey]
        val selectedProfile = when {
            requestedSsid != null -> profiles.firstOrNull { it.ssid == requestedSsid }
            profiles.isNotEmpty() -> profiles.firstOrNull()
            else -> null
        } ?: run {
            val legacySsid = preferences[cameraSsidKey] ?: return null
            val legacyPassword = preferences[cameraPasswordKey] ?: return null
            D.pref("loadCameraCredentials: using legacy ssid=$legacySsid")
            return WifiCredentials(
                ssid = legacySsid,
                password = legacyPassword,
                bleName = preferences[cameraBleNameKey],
                blePass = preferences[cameraBlePassKey],
            )
        }
        D.pref("loadCameraCredentials: ssid=${selectedProfile.ssid}")
        val reconnectBleIdentity = loadReconnectBleIdentity(preferences, selectedProfile.ssid)
        return WifiCredentials(
            ssid = selectedProfile.ssid,
            password = selectedProfile.password,
            bleName = reconnectBleIdentity?.bleName ?: selectedProfile.bleName,
            blePass = reconnectBleIdentity?.blePass ?: selectedProfile.blePass,
        )
    }

    suspend fun loadReconnectBleIdentity(ssid: String? = null): ReconnectBleIdentity? {
        val preferences = context.appPreferencesDataStore.data.first()
        return loadReconnectBleIdentity(preferences, ssid)
    }

    suspend fun loadSavedCameraProfiles(): List<SavedCameraProfile> {
        val preferences = context.appPreferencesDataStore.data.first()
        return loadSavedCameraProfiles(preferences)
    }

    suspend fun loadSavedCameraProfile(ssid: String? = null): SavedCameraProfile? {
        val preferences = context.appPreferencesDataStore.data.first()
        val profiles = loadSavedCameraProfiles(preferences)
        val requestedSsid = ssid ?: preferences[selectedCameraSsidKey]
        return when {
            requestedSsid != null -> profiles.firstOrNull { it.ssid == requestedSsid }
            else -> profiles.firstOrNull()
        }
    }

    suspend fun loadSelectedCameraSsid(): String? {
        val preferences = context.appPreferencesDataStore.data.first()
        return preferences[selectedCameraSsidKey]
            ?: loadSavedCameraProfiles(preferences).firstOrNull()?.ssid
    }

    suspend fun selectSavedCamera(ssid: String) {
        D.pref("selectSavedCamera: ssid=$ssid")
        val updatedProfiles = loadSavedCameraProfiles()
        context.appPreferencesDataStore.edit { preferences ->
            if (updatedProfiles.isNotEmpty()) {
                preferences[cameraProfilesKey] = serializeCameraProfiles(updatedProfiles)
                preferences[selectedCameraSsidKey] = ssid
            } else {
                preferences.remove(selectedCameraSsidKey)
            }
            clearLegacyCameraKeys(preferences)
        }
    }

    suspend fun clearCameraCredentials() {
        forgetCamera()
    }

    suspend fun migrateLegacyCameraPrefsIfNeeded() {
        val preferences = context.appPreferencesDataStore.data.first()
        if (!hasLegacyCameraKeys(preferences)) {
            return
        }

        val parsedProfiles = parseCameraProfiles(preferences[cameraProfilesKey])
        val legacyProfile = legacyCameraProfile(preferences)
        val mergedProfiles = when {
            legacyProfile == null && parsedProfiles.isEmpty() -> emptyList()
            legacyProfile == null -> parsedProfiles
            parsedProfiles.isEmpty() -> listOf(legacyProfile)
            else -> mergeLegacyProfile(parsedProfiles, legacyProfile)
        }
        val nextSelectedSsid = preferences[selectedCameraSsidKey]
            ?: legacyProfile?.ssid
            ?: mergedProfiles.firstOrNull()?.ssid

        D.pref(
            "migrateLegacyCameraPrefsIfNeeded: parsed=${parsedProfiles.size}, " +
                "legacy=${legacyProfile?.ssid ?: "<none>"}, merged=${mergedProfiles.size}",
        )

        context.appPreferencesDataStore.edit { mutablePreferences ->
            if (mergedProfiles.isEmpty()) {
                mutablePreferences.remove(cameraProfilesKey)
                mutablePreferences.remove(selectedCameraSsidKey)
            } else {
                mutablePreferences[cameraProfilesKey] = serializeCameraProfiles(mergedProfiles)
                putOrRemove(mutablePreferences, selectedCameraSsidKey, nextSelectedSsid)
            }
            clearLegacyCameraKeys(mutablePreferences)
        }
    }

    suspend fun updateCameraDisplayName(ssid: String, displayName: String) {
        val preferredName = CameraNameNormalizer.normalizeModelName(displayName)
            ?.takeUnless { it.equals("OM SYSTEM Camera", ignoreCase = true) }
            ?: return
        val normalizedName = CameraNameNormalizer.savedCameraDisplayName(
            preferredName = preferredName,
            ssid = ssid,
        )
        val updatedProfiles = loadSavedCameraProfiles().map { profile ->
            if (profile.ssid == ssid && profile.displayName != normalizedName) {
                profile.copy(displayName = normalizedName)
            } else {
                profile
            }
        }
        if (updatedProfiles.none { it.ssid == ssid }) {
            return
        }
        context.appPreferencesDataStore.edit { preferences ->
            preferences[cameraProfilesKey] = serializeCameraProfiles(updatedProfiles)
            val currentSelectedSsid = preferences[selectedCameraSsidKey]
            if (currentSelectedSsid == ssid) {
                putOrRemove(preferences, selectedCameraSsidKey, ssid)
            }
            clearLegacyCameraKeys(preferences)
        }
    }

    suspend fun updateCameraBleIdentity(
        ssid: String,
        bleName: String?,
        blePass: String? = null,
        bleAddress: String?,
    ) {
        if (bleName.isNullOrBlank() && blePass.isNullOrBlank() && bleAddress.isNullOrBlank()) {
            return
        }
        val updatedProfiles = loadSavedCameraProfiles().map { profile ->
            if (profile.ssid == ssid) {
                profile.copy(
                    bleName = bleName ?: profile.bleName,
                    blePass = blePass ?: profile.blePass,
                    bleAddress = bleAddress ?: profile.bleAddress,
                    playTargetSlot = profile.playTargetSlot,
                )
            } else {
                profile
            }
        }
        if (updatedProfiles.none { it.ssid == ssid }) {
            return
        }
        context.appPreferencesDataStore.edit { preferences ->
            preferences[cameraProfilesKey] = serializeCameraProfiles(updatedProfiles)
            val currentSelectedSsid = preferences[selectedCameraSsidKey]
            if (currentSelectedSsid == ssid) {
                putOrRemove(preferences, selectedCameraSsidKey, ssid)
            }
            clearLegacyCameraKeys(preferences)
        }
    }

    suspend fun updateCameraPlayTargetSlot(
        ssid: String,
        playTargetSlot: Int?,
    ) {
        val updatedProfiles = loadSavedCameraProfiles().map { profile ->
            if (profile.ssid == ssid) {
                profile.copy(playTargetSlot = playTargetSlot)
            } else {
                profile
            }
        }
        if (updatedProfiles.none { it.ssid == ssid }) {
            return
        }
        context.appPreferencesDataStore.edit { preferences ->
            preferences[cameraProfilesKey] = serializeCameraProfiles(updatedProfiles)
            val currentSelectedSsid = preferences[selectedCameraSsidKey]
            if (currentSelectedSsid == ssid) {
                putOrRemove(preferences, selectedCameraSsidKey, ssid)
            }
            clearLegacyCameraKeys(preferences)
        }
    }

    suspend fun forgetCamera(ssid: String? = null) {
        D.pref("forgetCamera: ssid=${ssid ?: "<all>"}")
        val updatedProfiles = if (ssid == null) {
            emptyList()
        } else {
            loadSavedCameraProfiles().filterNot { it.ssid == ssid }
        }
        context.appPreferencesDataStore.edit { preferences ->
            if (updatedProfiles.isEmpty()) {
                preferences.remove(cameraProfilesKey)
                preferences.remove(selectedCameraSsidKey)
            } else {
                preferences[cameraProfilesKey] = serializeCameraProfiles(updatedProfiles)
                val nextSelected = preferences[selectedCameraSsidKey]
                    ?.takeIf { candidate -> updatedProfiles.any { it.ssid == candidate } }
                    ?: updatedProfiles.firstOrNull()?.ssid
                if (nextSelected != null) {
                    preferences[selectedCameraSsidKey] = nextSelected
                } else {
                    preferences.remove(selectedCameraSsidKey)
                }
            }
            clearLegacyCameraKeys(preferences)
        }
    }

    // GeoTagging
    suspend fun saveGeoTagging(snapshot: GeoTaggingSnapshot) {
        D.pref("saveGeoTagging: ${snapshot.samples.size} samples, offset=${snapshot.clockOffsetMinutes}min, totalPins=${snapshot.totalPinsCaptured}")
        context.appPreferencesDataStore.edit { preferences ->
            preferences[clockOffsetKey] = snapshot.clockOffsetMinutes
            preferences[samplesKey] = serializeSamples(snapshot.samples.takeLast(MAX_STORED_SAMPLES))
            preferences[totalPinsKey] = snapshot.totalPinsCaptured
        }
    }

    private fun settingKey(id: String): Preferences.Key<Boolean> = booleanPreferencesKey("setting_$id")

    private fun parseSamples(serialized: String?): List<GeoTagLocationSample> {
        if (serialized.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(serialized)
            D.pref("parseSamples: JSON array length=${array.length()}")
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    try {
                        val item = array.getJSONObject(index)
                        add(
                            GeoTagLocationSample(
                                capturedAtMillis = item.getLong("capturedAtMillis"),
                                latitude = item.getDouble("latitude"),
                                longitude = item.getDouble("longitude"),
                                altitude = item.optDouble("altitude").takeIf { !it.isNaN() },
                                speedMps = item.optDouble("speedMps").takeIf { !it.isNaN() }?.toFloat(),
                                bearingDegrees = item.optDouble("bearingDegrees").takeIf { !it.isNaN() }?.toFloat(),
                                accuracyMeters = item.getDouble("accuracyMeters").toFloat(),
                                placeName = if (item.has("placeName") && !item.isNull("placeName")) item.getString("placeName") else null,
                                source = item.getString("source"),
                            ),
                        )
                    } catch (e: Exception) {
                        D.err("PREF", "Skipping corrupted sample at index $index", e)
                    }
                }
            }
        } catch (e: Exception) {
            D.err("PREF", "parseSamples failed", e)
            emptyList()
        }
    }

    private fun serializeSamples(samples: List<GeoTagLocationSample>): String {
        return JSONArray().apply {
            samples.forEach { sample ->
                put(
                    JSONObject().apply {
                        put("capturedAtMillis", sample.capturedAtMillis)
                        put("latitude", sample.latitude)
                        put("longitude", sample.longitude)
                        sample.altitude?.let { put("altitude", it) }
                        sample.speedMps?.let { put("speedMps", it.toDouble()) }
                        sample.bearingDegrees?.let { put("bearingDegrees", it.toDouble()) }
                        put("accuracyMeters", sample.accuracyMeters.toDouble())
                        sample.placeName?.let { put("placeName", it) }
                        put("source", sample.source)
                    },
                )
            }
        }.toString()
    }

    private fun loadSavedCameraProfiles(preferences: Preferences): List<SavedCameraProfile> {
        val serialized = preferences[cameraProfilesKey]
        val parsedProfiles = parseCameraProfiles(serialized)
        if (parsedProfiles.isNotEmpty()) {
            return parsedProfiles
        }

        return legacyCameraProfile(preferences)?.let(::listOf).orEmpty()
    }

    private fun parseCameraProfiles(serialized: String?): List<SavedCameraProfile> {
        if (serialized.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(serialized)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        SavedCameraProfile(
                            ssid = item.getString("ssid"),
                            password = item.getString("password"),
                            displayName = CameraNameNormalizer.savedCameraDisplayName(
                                preferredName = item.optString("displayName").ifBlank { null },
                                ssid = item.getString("ssid"),
                            ),
                            bleName = item.optString("bleName").ifBlank { null },
                            blePass = item.optString("blePass").ifBlank { null },
                            bleAddress = item.optString("bleAddress").ifBlank { null },
                            playTargetSlot = item.optInt("playTargetSlot", 0).takeIf { it in 1..2 },
                            lastUsedAtMillis = item.optLong("lastUsedAtMillis", System.currentTimeMillis()),
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            D.err("PREF", "parseCameraProfiles failed", e)
            emptyList()
        }
    }

    private fun serializeCameraProfiles(profiles: List<SavedCameraProfile>): String {
        return JSONArray().apply {
            profiles.forEach { profile ->
                put(
                    JSONObject().apply {
                        put("ssid", profile.ssid)
                        put("password", profile.password)
                        put("displayName", profile.displayName)
                        profile.bleName?.let { put("bleName", it) }
                        profile.blePass?.let { put("blePass", it) }
                        profile.bleAddress?.let { put("bleAddress", it) }
                        profile.playTargetSlot?.let { put("playTargetSlot", it) }
                        put("lastUsedAtMillis", profile.lastUsedAtMillis)
                    },
                )
            }
        }.toString()
    }

    private fun upsertCameraProfile(
        profiles: List<SavedCameraProfile>,
        profile: SavedCameraProfile,
    ): List<SavedCameraProfile> {
        val existingIndex = profiles.indexOfFirst { it.ssid == profile.ssid }
        val existing = profiles.getOrNull(existingIndex)
        val merged = profile.copy(
            displayName = profile.displayName.ifBlank { existing?.displayName ?: profile.ssid },
            bleName = profile.bleName ?: existing?.bleName,
            blePass = profile.blePass ?: existing?.blePass,
            bleAddress = profile.bleAddress ?: existing?.bleAddress,
            playTargetSlot = profile.playTargetSlot ?: existing?.playTargetSlot,
        )
        return if (existingIndex >= 0) {
            profiles.toMutableList().apply {
                this[existingIndex] = merged
            }
        } else {
            profiles + merged
        }
    }

    private fun loadReconnectBleIdentity(
        preferences: Preferences,
        ssid: String?,
    ): ReconnectBleIdentity? {
        val parsedProfiles = parseCameraProfiles(preferences[cameraProfilesKey])
        if (parsedProfiles.isNotEmpty()) {
            val requestedSsid = ssid ?: preferences[selectedCameraSsidKey]
            val selectedProfile = when {
                requestedSsid != null -> parsedProfiles.firstOrNull { it.ssid == requestedSsid }
                else -> parsedProfiles.firstOrNull()
            }
            return selectedProfile?.let { profile ->
                if (profile.bleName.isNullOrBlank() && profile.blePass.isNullOrBlank() && profile.bleAddress.isNullOrBlank()) {
                    null
                } else {
                    ReconnectBleIdentity(
                        bleName = profile.bleName,
                        blePass = profile.blePass,
                        bleAddress = profile.bleAddress,
                    )
                }
            }
        }

        val requestedSsid = ssid ?: preferences[selectedCameraSsidKey] ?: preferences[cameraSsidKey]
        val currentSsid = preferences[selectedCameraSsidKey] ?: preferences[cameraSsidKey]
        val bleName = if (requestedSsid != null && requestedSsid == currentSsid) preferences[cameraBleNameKey] else null
        val blePass = if (requestedSsid != null && requestedSsid == currentSsid) preferences[cameraBlePassKey] else null
        val bleAddress = if (requestedSsid != null && requestedSsid == currentSsid) preferences[cameraBleAddressKey] else null
        return if (bleName.isNullOrBlank() && blePass.isNullOrBlank() && bleAddress.isNullOrBlank()) {
            null
        } else {
            ReconnectBleIdentity(
                bleName = bleName,
                blePass = blePass,
                bleAddress = bleAddress,
            )
        }
    }

    private fun requestedCurrentSelection(preferences: Preferences, ssid: String): Boolean {
        val currentSsid = preferences[selectedCameraSsidKey] ?: preferences[cameraSsidKey]
        return currentSsid == ssid
    }

    private fun hasLegacyCameraKeys(preferences: Preferences): Boolean {
        return preferences[cameraSsidKey] != null ||
            preferences[cameraPasswordKey] != null ||
            preferences[cameraBleNameKey] != null ||
            preferences[cameraBlePassKey] != null ||
            preferences[cameraBleAddressKey] != null ||
            preferences[cameraPlayTargetSlotKey] != null
    }

    private fun legacyCameraProfile(preferences: Preferences): SavedCameraProfile? {
        val legacySsid = preferences[cameraSsidKey] ?: return null
        val legacyPassword = preferences[cameraPasswordKey] ?: return null
        return SavedCameraProfile(
            ssid = legacySsid,
            password = legacyPassword,
            displayName = CameraNameNormalizer.savedCameraDisplayName(ssid = legacySsid),
            bleName = preferences[cameraBleNameKey],
            blePass = preferences[cameraBlePassKey],
            bleAddress = preferences[cameraBleAddressKey],
            playTargetSlot = preferences[cameraPlayTargetSlotKey],
        )
    }

    private fun mergeLegacyProfile(
        profiles: List<SavedCameraProfile>,
        legacyProfile: SavedCameraProfile,
    ): List<SavedCameraProfile> {
        var merged = false
        val updatedProfiles = profiles.map { profile ->
            if (profile.ssid != legacyProfile.ssid) {
                profile
            } else {
                merged = true
                profile.copy(
                    password = profile.password.ifBlank { legacyProfile.password },
                    displayName = profile.displayName.ifBlank { legacyProfile.displayName },
                    bleName = profile.bleName ?: legacyProfile.bleName,
                    blePass = profile.blePass ?: legacyProfile.blePass,
                    bleAddress = profile.bleAddress ?: legacyProfile.bleAddress,
                    playTargetSlot = profile.playTargetSlot ?: legacyProfile.playTargetSlot,
                    lastUsedAtMillis = maxOf(profile.lastUsedAtMillis, legacyProfile.lastUsedAtMillis),
                )
            }
        }
        return if (merged) updatedProfiles else updatedProfiles + legacyProfile
    }

    private fun clearLegacyCameraKeys(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
    ) {
        preferences.remove(cameraSsidKey)
        preferences.remove(cameraPasswordKey)
        preferences.remove(cameraBleNameKey)
        preferences.remove(cameraBlePassKey)
        preferences.remove(cameraBleAddressKey)
        preferences.remove(cameraPlayTargetSlotKey)
    }

    private fun putOrRemove(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
        key: Preferences.Key<String>,
        value: String?,
    ) {
        if (value.isNullOrBlank()) {
            preferences.remove(key)
        } else {
            preferences[key] = value
        }
    }

    private fun putOrRemove(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
        key: Preferences.Key<Int>,
        value: Int?,
    ) {
        if (value == null) {
            preferences.remove(key)
        } else {
            preferences[key] = value
        }
    }

    private companion object {
        const val MAX_STORED_SAMPLES = 64
        val clockOffsetKey = intPreferencesKey("geotag_clock_offset_minutes")
        val samplesKey = stringPreferencesKey("geotag_samples_json")
        val totalPinsKey = intPreferencesKey("geotag_total_pins")
        val cameraProfilesKey = stringPreferencesKey("camera_profiles_json")
        val selectedCameraSsidKey = stringPreferencesKey("selected_camera_ssid")
        val cameraSsidKey = stringPreferencesKey("camera_ssid")
        val cameraPasswordKey = stringPreferencesKey("camera_password")
        val cameraBleNameKey = stringPreferencesKey("camera_ble_name")
        val cameraBlePassKey = stringPreferencesKey("camera_ble_pass")
        val cameraBleAddressKey = stringPreferencesKey("camera_ble_address")
        val cameraPlayTargetSlotKey = intPreferencesKey("camera_play_target_slot")
    }
}
