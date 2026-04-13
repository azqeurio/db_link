package dev.dblink.feature.settings

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dblink.R
import dev.dblink.core.config.AppConfig
import dev.dblink.core.localization.AppLanguageManager
import dev.dblink.core.logging.FileLogger
import dev.dblink.core.model.CameraWorkspace
import dev.dblink.core.model.SavedCameraProfile
import dev.dblink.core.model.SettingItem
import dev.dblink.core.model.TetherSaveTarget
import dev.dblink.core.permissions.PermissionPlan
import dev.dblink.core.ui.GlassCard
import dev.dblink.core.ui.KeyValueRow
import dev.dblink.core.ui.SectionHeader
import dev.dblink.core.ui.SettingToggleRow
import dev.dblink.ui.AutoImportConfig
import dev.dblink.ui.GeotagConfig
import dev.dblink.ui.theme.AppleBlue
import dev.dblink.ui.theme.AppleRed
import dev.dblink.ui.theme.Chalk
import dev.dblink.ui.theme.LeicaBorder

/**
 * Maps a setting ID to its localized title resource.
 */
@Composable
private fun localizedTitle(id: String): String = when (id) {
    "nearby_discovery" -> stringResource(R.string.settings_nearby_discovery_title)
    "reverse_dial_aperture" -> stringResource(R.string.settings_reverse_dial_aperture_title)
    "reverse_dial_shutter" -> stringResource(R.string.settings_reverse_dial_shutter_title)
    "reverse_dial_iso" -> stringResource(R.string.settings_reverse_dial_iso_title)
    "reverse_dial_wb" -> stringResource(R.string.settings_reverse_dial_wb_title)
    "reverse_dial_ev" -> stringResource(R.string.settings_reverse_dial_ev_title)
    "capture_review_after_shot" -> stringResource(R.string.settings_capture_review_after_shot_title)
    "capture_save_after_shot" -> stringResource(R.string.settings_capture_save_after_shot_title)
    "auto_import" -> stringResource(R.string.settings_auto_import_title)
    "import_new_only" -> stringResource(R.string.settings_import_new_only_title)
    "import_skip_duplicates" -> stringResource(R.string.settings_import_skip_duplicates_title)
    "time_match_geotags" -> stringResource(R.string.settings_auto_geotag_title)
    "geotag_sync_clock" -> stringResource(R.string.settings_geotag_sync_clock_title)
    "geotag_include_altitude" -> stringResource(R.string.settings_geotag_include_altitude_title)
    "debug_workbench" -> stringResource(R.string.settings_debug_workbench_title)
    "verbose_logs" -> stringResource(R.string.settings_verbose_logs_title)
    else -> id
}

/**
 * Maps a setting ID to its localized summary resource.
 */
@Composable
private fun localizedSummary(id: String): String = when (id) {
    "nearby_discovery" -> stringResource(R.string.settings_nearby_discovery_summary)
    "reverse_dial_aperture" -> stringResource(R.string.settings_reverse_dial_aperture_summary)
    "reverse_dial_shutter" -> stringResource(R.string.settings_reverse_dial_shutter_summary)
    "reverse_dial_iso" -> stringResource(R.string.settings_reverse_dial_iso_summary)
    "reverse_dial_wb" -> stringResource(R.string.settings_reverse_dial_wb_summary)
    "reverse_dial_ev" -> stringResource(R.string.settings_reverse_dial_ev_summary)
    "capture_review_after_shot" -> stringResource(R.string.settings_capture_review_after_shot_summary)
    "capture_save_after_shot" -> stringResource(R.string.settings_capture_save_after_shot_summary)
    "auto_import" -> stringResource(R.string.settings_auto_import_summary)
    "import_new_only" -> stringResource(R.string.settings_import_new_only_summary)
    "import_skip_duplicates" -> stringResource(R.string.settings_import_skip_duplicates_summary)
    "time_match_geotags" -> stringResource(R.string.settings_auto_geotag_summary)
    "geotag_sync_clock" -> stringResource(R.string.settings_geotag_sync_clock_summary)
    "geotag_include_altitude" -> stringResource(R.string.settings_geotag_include_altitude_summary)
    "debug_workbench" -> stringResource(R.string.settings_debug_workbench_summary)
    "verbose_logs" -> stringResource(R.string.settings_verbose_logs_summary)
    else -> ""
}

@Composable
private fun tetherSaveTargetLabel(target: TetherSaveTarget): String = when (target) {
    TetherSaveTarget.SdCard -> stringResource(R.string.settings_tether_save_target_sd)
    TetherSaveTarget.SdAndPhone -> stringResource(R.string.settings_tether_save_target_sd_phone)
    TetherSaveTarget.Phone -> stringResource(R.string.settings_tether_save_target_phone)
}

@Composable
private fun tetherSaveTargetSummary(target: TetherSaveTarget): String = when (target) {
    TetherSaveTarget.SdCard -> stringResource(R.string.settings_tether_save_target_sd_summary)
    TetherSaveTarget.SdAndPhone -> stringResource(R.string.settings_tether_save_target_sd_phone_summary)
    TetherSaveTarget.Phone -> stringResource(R.string.settings_tether_save_target_phone_summary)
}

private val connectionIds = setOf("nearby_discovery")
private val dialIds = setOf(
    "reverse_dial_aperture", "reverse_dial_shutter", "reverse_dial_iso",
    "reverse_dial_wb", "reverse_dial_ev",
)
private val tetheredCaptureIds = setOf("capture_review_after_shot")
private val autoImportIds = setOf("auto_import", "import_new_only", "import_skip_duplicates")
private val autoGeotagIds = setOf("time_match_geotags", "geotag_sync_clock", "geotag_include_altitude")
private val developerIds = setOf("debug_workbench", "verbose_logs")

@Composable
fun SettingsScreen(
    workspace: CameraWorkspace,
    settings: List<SettingItem>,
    appConfig: AppConfig,
    permissionPlans: List<PermissionPlan>,
    hasSavedCamera: Boolean = false,
    savedCameras: List<SavedCameraProfile> = emptyList(),
    selectedCameraSsid: String? = null,
    selectedCardSlotSource: Int? = null,
    selectedLanguageTag: String = AppLanguageManager.LANGUAGE_ENGLISH,
    autoImportConfig: AutoImportConfig = AutoImportConfig(),
    geotagConfig: GeotagConfig = GeotagConfig(),
    tetherSaveTarget: TetherSaveTarget = TetherSaveTarget.SdAndPhone,
    onSelectSavedCamera: (String) -> Unit = {},
    onSelectLanguage: (String) -> Unit = {},
    onSettingChanged: (String, Boolean) -> Unit,
    onAutoImportConfigChanged: (AutoImportConfig) -> Unit = {},
    onSelectCardSlotSource: (Int) -> Unit = {},
    onGeotagConfigChanged: (GeotagConfig) -> Unit = {},
    onTetherSaveTargetChanged: (TetherSaveTarget) -> Unit = {},
    onExportLogs: () -> Unit,
    onForgetCamera: () -> Unit = {},
    onForgetSavedCamera: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val connectionSettings = settings.filter { it.id in connectionIds }
    val dialSettings = settings.filter { it.id in dialIds }
    val tetheredCaptureSettings = settings.filter { it.id in tetheredCaptureIds }
    val autoImportSettings = settings.filter { it.id in autoImportIds }
    val autoGeotagSettings = settings.filter { it.id in autoGeotagIds }
    val devSettings = settings.filter { it.id in developerIds }
    val supportsPlayTargetSlotSelection = workspace.protocol.commandList.commands.any {
        it.supported && it.name.equals("get_playtargetslot", ignoreCase = true)
    } && workspace.protocol.commandList.commands.any {
        it.supported && it.name.equals("set_playtargetslot", ignoreCase = true)
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
    ) {
        // Header
        item {
            SectionHeader(stringResource(R.string.settings_title))
        }

        // 1. Connection / Camera
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SectionIcon(Icons.Rounded.CameraAlt, stringResource(R.string.settings_section_connection))

                    // Saved cameras
                    if (workspace.camera.name.isNotBlank()) {
                        KeyValueRow(
                            stringResource(R.string.settings_current_camera),
                            workspace.camera.name,
                        )
                    }

                    if (hasSavedCamera && savedCameras.isNotEmpty()) {
                        savedCameras.forEach { profile ->
                            SavedCameraRow(
                                profile = profile,
                                selected = profile.ssid == selectedCameraSsid,
                                onSelect = { onSelectSavedCamera(profile.ssid) },
                                onDelete = { onForgetSavedCamera(profile.ssid) },
                            )
                        }

                        OutlinedButton(
                            onClick = onForgetCamera,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppleRed),
                            border = BorderStroke(1.dp, LeicaBorder),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Icon(Icons.Rounded.LinkOff, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(stringResource(R.string.settings_forget_all))
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.settings_saved_camera_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Chalk.copy(alpha = 0.58f),
                        )
                    }

                    // Connection toggles
                    connectionSettings.forEach { item ->
                        SettingToggleRow(
                            title = localizedTitle(item.id),
                            summary = localizedSummary(item.id),
                            checked = item.enabled,
                            onCheckedChange = { onSettingChanged(item.id, it) },
                        )
                    }

                    // Language
                    Text(
                        text = stringResource(R.string.settings_language),
                        style = MaterialTheme.typography.labelMedium,
                        color = Chalk.copy(alpha = 0.46f),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.settings_language_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Chalk.copy(alpha = 0.58f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LanguageButton(
                            modifier = Modifier.weight(1f),
                            selected = selectedLanguageTag == AppLanguageManager.LANGUAGE_ENGLISH,
                            label = stringResource(R.string.settings_language_english),
                        ) {
                            onSelectLanguage(AppLanguageManager.LANGUAGE_ENGLISH)
                        }
                        LanguageButton(
                            modifier = Modifier.weight(1f),
                            selected = selectedLanguageTag == AppLanguageManager.LANGUAGE_KOREAN,
                            label = stringResource(R.string.settings_language_korean),
                        ) {
                            onSelectLanguage(AppLanguageManager.LANGUAGE_KOREAN)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            val donateIntent = Intent(
                                Intent.ACTION_VIEW,
                                "https://buymeacoffee.com/modang".toUri(),
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            if (donateIntent.resolveActivity(context.packageManager) != null) {
                                context.startActivity(donateIntent)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Chalk),
                        border = BorderStroke(1.dp, LeicaBorder),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text(stringResource(R.string.settings_support_donate))
                    }
                }
            }
        }

        // 2. Remote Control / Dial
        if (dialSettings.isNotEmpty()) {
            item {
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SectionIcon(Icons.Rounded.Tune, stringResource(R.string.settings_section_dial))
                        Text(
                            text = stringResource(R.string.settings_section_dial_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Chalk.copy(alpha = 0.58f),
                        )
                        dialSettings.forEach { item ->
                            SettingToggleRow(
                                title = localizedTitle(item.id),
                                summary = localizedSummary(item.id),
                                checked = item.enabled,
                                onCheckedChange = { onSettingChanged(item.id, it) },
                            )
                        }
                    }
                }
            }
        }

        // 3. Live View
        // 4. Auto Import
        if (tetheredCaptureSettings.isNotEmpty()) {
            item {
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SectionIcon(Icons.Rounded.CameraAlt, stringResource(R.string.settings_section_tethered_capture))
                        tetheredCaptureSettings.forEach { item ->
                            SettingToggleRow(
                                title = localizedTitle(item.id),
                                summary = localizedSummary(item.id),
                                checked = item.enabled,
                                onCheckedChange = { onSettingChanged(item.id, it) },
                            )
                        }
                    }
                }
            }
        }

        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SectionIcon(Icons.Rounded.PhotoLibrary, stringResource(R.string.settings_section_file_save))
                    Text(
                        text = stringResource(R.string.settings_tether_save_target_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = Chalk.copy(alpha = 0.46f),
                        fontWeight = FontWeight.Bold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OptionChip(
                            label = stringResource(R.string.settings_tether_save_target_sd),
                            selected = tetherSaveTarget == TetherSaveTarget.SdCard,
                            modifier = Modifier.weight(1f),
                        ) { onTetherSaveTargetChanged(TetherSaveTarget.SdCard) }
                        OptionChip(
                            label = stringResource(R.string.settings_tether_save_target_sd_phone),
                            selected = tetherSaveTarget == TetherSaveTarget.SdAndPhone,
                            modifier = Modifier.weight(1f),
                        ) { onTetherSaveTargetChanged(TetherSaveTarget.SdAndPhone) }
                        OptionChip(
                            label = stringResource(R.string.settings_tether_save_target_phone),
                            selected = tetherSaveTarget == TetherSaveTarget.Phone,
                            modifier = Modifier.weight(1f),
                        ) { onTetherSaveTargetChanged(TetherSaveTarget.Phone) }
                    }
                    Text(
                        text = tetherSaveTargetSummary(tetherSaveTarget),
                        style = MaterialTheme.typography.bodySmall,
                        color = Chalk.copy(alpha = 0.58f),
                        lineHeight = 18.sp,
                    )
                }
            }
        }

        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SectionIcon(Icons.Rounded.PhotoLibrary, stringResource(R.string.settings_section_auto_import))
                    autoImportSettings.forEach { item ->
                        SettingToggleRow(
                            title = localizedTitle(item.id),
                            summary = localizedSummary(item.id),
                            checked = item.enabled,
                            onCheckedChange = { onSettingChanged(item.id, it) },
                        )
                    }
                    // Save location — editable
                    EditablePathRow(
                        label = stringResource(R.string.settings_auto_import_dest_label),
                        currentValue = autoImportConfig.saveLocation,
                        onValueChanged = { newPath ->
                            onAutoImportConfigChanged(autoImportConfig.copy(saveLocation = newPath))
                        },
                    )
                    // File format selector
                    Text(
                        text = stringResource(R.string.settings_auto_import_format_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = Chalk.copy(alpha = 0.46f),
                        fontWeight = FontWeight.Bold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OptionChip(
                            label = stringResource(R.string.settings_auto_import_format_jpeg),
                            selected = autoImportConfig.fileFormat == "jpeg",
                            modifier = Modifier.weight(1f),
                        ) { onAutoImportConfigChanged(autoImportConfig.copy(fileFormat = "jpeg")) }
                        OptionChip(
                            label = stringResource(R.string.settings_auto_import_format_jpeg_raw),
                            selected = autoImportConfig.fileFormat == "jpeg_raw",
                            modifier = Modifier.weight(1f),
                        ) { onAutoImportConfigChanged(autoImportConfig.copy(fileFormat = "jpeg_raw")) }
                        OptionChip(
                            label = stringResource(R.string.settings_auto_import_format_raw),
                            selected = autoImportConfig.fileFormat == "raw",
                            modifier = Modifier.weight(1f),
                        ) { onAutoImportConfigChanged(autoImportConfig.copy(fileFormat = "raw")) }
                    }
                    // Import timing selector
                    Text(
                        text = stringResource(R.string.settings_auto_import_timing_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = Chalk.copy(alpha = 0.46f),
                        fontWeight = FontWeight.Bold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OptionChip(
                            label = stringResource(R.string.settings_auto_import_timing_on_connect),
                            selected = autoImportConfig.importTiming == "on_connect",
                            modifier = Modifier.weight(1f),
                        ) { onAutoImportConfigChanged(autoImportConfig.copy(importTiming = "on_connect")) }
                        OptionChip(
                            label = stringResource(R.string.settings_auto_import_timing_since_launch),
                            selected = autoImportConfig.importTiming == "since_launch",
                            modifier = Modifier.weight(1f),
                        ) { onAutoImportConfigChanged(autoImportConfig.copy(importTiming = "since_launch")) }
                        OptionChip(
                            label = stringResource(R.string.settings_auto_import_timing_manual),
                            selected = autoImportConfig.importTiming == "manual",
                            modifier = Modifier.weight(1f),
                        ) { onAutoImportConfigChanged(autoImportConfig.copy(importTiming = "manual")) }
                    }
                    if (selectedCameraSsid != null && supportsPlayTargetSlotSelection) {
                        Text(
                            text = stringResource(R.string.settings_photo_source_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = Chalk.copy(alpha = 0.46f),
                            fontWeight = FontWeight.Bold,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OptionChip(
                                label = stringResource(R.string.settings_photo_source_slot_1),
                                selected = selectedCardSlotSource == 1,
                                modifier = Modifier.weight(1f),
                            ) { onSelectCardSlotSource(1) }
                            OptionChip(
                                label = stringResource(R.string.settings_photo_source_slot_2),
                                selected = selectedCardSlotSource == 2,
                                modifier = Modifier.weight(1f),
                            ) { onSelectCardSlotSource(2) }
                        }
                        if (selectedCardSlotSource != null) {
                            Text(
                                text = when (selectedCardSlotSource) {
                                    2 -> stringResource(R.string.settings_photo_source_current_slot_2)
                                    else -> stringResource(R.string.settings_photo_source_current_slot_1)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Chalk.copy(alpha = 0.48f),
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.settings_auto_import_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = Chalk.copy(alpha = 0.48f),
                        lineHeight = 18.sp,
                    )
                }
            }
        }

        // 5. Auto Geotag
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SectionIcon(Icons.Rounded.LocationOn, stringResource(R.string.settings_section_auto_geotag))
                    autoGeotagSettings.forEach { item ->
                        SettingToggleRow(
                            title = localizedTitle(item.id),
                            summary = localizedSummary(item.id),
                            checked = item.enabled,
                            onCheckedChange = { onSettingChanged(item.id, it) },
                        )
                    }
                    // Match window selector
                    Text(
                        text = stringResource(R.string.settings_auto_geotag_accuracy_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = Chalk.copy(alpha = 0.46f),
                        fontWeight = FontWeight.Bold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OptionChip(
                            label = stringResource(R.string.settings_auto_geotag_accuracy_1m),
                            selected = geotagConfig.matchWindowMinutes == 1,
                            modifier = Modifier.weight(1f),
                        ) { onGeotagConfigChanged(geotagConfig.copy(matchWindowMinutes = 1)) }
                        OptionChip(
                            label = stringResource(R.string.settings_auto_geotag_accuracy_2m),
                            selected = geotagConfig.matchWindowMinutes == 2,
                            modifier = Modifier.weight(1f),
                        ) { onGeotagConfigChanged(geotagConfig.copy(matchWindowMinutes = 2)) }
                        OptionChip(
                            label = stringResource(R.string.settings_auto_geotag_accuracy_5m),
                            selected = geotagConfig.matchWindowMinutes == 5,
                            modifier = Modifier.weight(1f),
                        ) { onGeotagConfigChanged(geotagConfig.copy(matchWindowMinutes = 5)) }
                        OptionChip(
                            label = stringResource(R.string.settings_auto_geotag_accuracy_10m),
                            selected = geotagConfig.matchWindowMinutes == 10,
                            modifier = Modifier.weight(1f),
                        ) { onGeotagConfigChanged(geotagConfig.copy(matchWindowMinutes = 10)) }
                    }
                    KeyValueRow(
                        stringResource(R.string.settings_auto_geotag_method_label),
                        stringResource(R.string.settings_auto_geotag_method_value),
                    )
                    KeyValueRow(
                        stringResource(R.string.settings_auto_geotag_source_label),
                        stringResource(R.string.settings_auto_geotag_source_value),
                    )
                    KeyValueRow(
                        stringResource(R.string.settings_auto_geotag_writes_label),
                        stringResource(R.string.settings_auto_geotag_writes_value),
                    )
                    Text(
                        text = stringResource(R.string.settings_auto_geotag_requires),
                        style = MaterialTheme.typography.bodySmall,
                        color = Chalk.copy(alpha = 0.48f),
                    )
                }
            }
        }

        // 6. App / Info
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionIcon(Icons.Rounded.Info, stringResource(R.string.settings_section_app_info))
                    KeyValueRow(stringResource(R.string.settings_app_version_label), "0.1.0")
                    KeyValueRow(stringResource(R.string.settings_camera_base_url), appConfig.cameraBaseUrl)
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = stringResource(R.string.settings_section_licenses),
                        style = MaterialTheme.typography.labelMedium,
                        color = Chalk.copy(alpha = 0.46f),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.settings_license_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = Chalk.copy(alpha = 0.42f),
                        lineHeight = 18.sp,
                    )
                }
            }
        }

        // 7. Developer
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SectionIcon(Icons.Rounded.Code, stringResource(R.string.settings_section_developer))
                    devSettings.forEach { item ->
                        SettingToggleRow(
                            title = localizedTitle(item.id),
                            summary = localizedSummary(item.id),
                            checked = item.enabled,
                            onCheckedChange = { onSettingChanged(item.id, it) },
                        )
                    }

                    // Diagnostics (logs)
                    Text(
                        text = stringResource(R.string.settings_diagnostics),
                        style = MaterialTheme.typography.labelMedium,
                        color = Chalk.copy(alpha = 0.46f),
                        fontWeight = FontWeight.Bold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onExportLogs,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Chalk),
                            border = BorderStroke(1.dp, LeicaBorder),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text(stringResource(R.string.settings_export_logs))
                        }
                        OutlinedButton(
                            onClick = { FileLogger.clearLogs() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppleRed),
                            border = BorderStroke(1.dp, LeicaBorder),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(stringResource(R.string.settings_clear_logs))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionIcon(icon: ImageVector, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Chalk.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Chalk.copy(alpha = 0.46f),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SavedCameraRow(
    profile: SavedCameraProfile,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (selected) AppleBlue else Chalk.copy(alpha = 0.2f)),
            )
            Column {
                Text(
                    text = profile.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = Chalk,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                )
                Text(
                    text = if (selected) {
                        stringResource(R.string.settings_auto_connect_target)
                    } else {
                        stringResource(R.string.settings_saved_camera)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) AppleBlue else Chalk.copy(alpha = 0.48f),
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TextButton(onClick = onSelect) {
                Text(stringResource(R.string.settings_select))
            }
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = AppleRed),
            ) {
                Text(stringResource(R.string.settings_delete))
            }
        }
    }
}

@Composable
private fun LanguageButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (selected) AppleBlue else Chalk,
        ),
        border = BorderStroke(1.dp, if (selected) AppleBlue else LeicaBorder),
        shape = RoundedCornerShape(18.dp),
    ) {
        Text(label)
    }
}

@Composable
private fun OptionChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (selected) AppleBlue else Chalk.copy(alpha = 0.6f),
        ),
        border = BorderStroke(1.dp, if (selected) AppleBlue else LeicaBorder),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun EditablePathRow(
    label: String,
    currentValue: String,
    onValueChanged: (String) -> Unit,
) {
    val showDialogState = remember { mutableStateOf(false) }
    val showDialog = showDialogState.value
    var editText by remember(currentValue) { mutableStateOf(currentValue) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = Chalk,
            )
            Text(
                text = currentValue,
                style = MaterialTheme.typography.bodySmall,
                color = Chalk.copy(alpha = 0.5f),
            )
        }
        TextButton(onClick = { editText = currentValue; showDialogState.value = true }) {
            Text(
                text = stringResource(R.string.common_edit),
                style = MaterialTheme.typography.labelMedium,
                color = AppleBlue,
            )
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialogState.value = false },
            title = { Text(label) },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppleBlue,
                        cursorColor = AppleBlue,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = editText.trim().ifBlank { "Pictures/db link" }
                        onValueChanged(trimmed)
                        showDialogState.value = false
                    },
                ) {
                    Text(stringResource(R.string.common_save), color = AppleBlue)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialogState.value = false }) {
                    Text(stringResource(R.string.common_cancel), color = Chalk.copy(alpha = 0.6f))
                }
            },
        )
    }
}
