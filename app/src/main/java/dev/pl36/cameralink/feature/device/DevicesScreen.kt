package dev.pl36.cameralink.feature.device

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pl36.cameralink.core.model.CameraWorkspace
import dev.pl36.cameralink.core.permissions.PermissionPlan
import dev.pl36.cameralink.core.presentation.asUserFacingFallback
import dev.pl36.cameralink.core.presentation.displayLabel
import dev.pl36.cameralink.core.ui.GlassCard
import dev.pl36.cameralink.core.ui.KeyValueRow
import dev.pl36.cameralink.core.ui.SectionHeader
import dev.pl36.cameralink.core.ui.StatusBadge
import dev.pl36.cameralink.ui.theme.AppleBlue
import dev.pl36.cameralink.ui.theme.AppleGreen
import dev.pl36.cameralink.ui.theme.Graphite

@Composable
fun DevicesScreen(
    workspace: CameraWorkspace,
    permissionPlans: List<PermissionPlan>,
) {
    val firmwareLabel = workspace.camera.firmwareVersion.asUserFacingFallback("Unknown")
    val batteryLabel = workspace.camera.batteryPercent?.let { "$it%" } ?: "Unknown"

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
    ) {
        item {
            SectionHeader("System")
        }

        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AppleBlue.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.CameraAlt, null, tint = AppleBlue, modifier = Modifier.size(32.dp))
                        }
                        Column {
                            Text(
                                text = workspace.camera.name.ifEmpty { "No Camera" },
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = if (firmwareLabel == "Unknown") "Firmware unknown" else "Firmware v$firmwareLabel",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.4f),
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StatusBadge(workspace.camera.connectionMethod.displayLabel(), AppleBlue)
                        StatusBadge(workspace.camera.connectMode.displayLabel(), AppleGreen)
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "HARDWARE STATUS",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.5f),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        KeyValueRow("Internal Storage", workspace.camera.storageFree.asUserFacingFallback("Unknown"))
                        KeyValueRow("Battery Level", batteryLabel)
                        KeyValueRow("Access Mode", workspace.camera.connectMode.name)
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Security, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                    Text(
                        text = "APP PERMISSIONS",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.5f),
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    permissionPlans.forEach { plan ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Graphite)
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(plan.title, style = MaterialTheme.typography.bodyLarge, color = Color.White, fontWeight = FontWeight.Bold)
                                Text("${plan.permissions.size} tokens required", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.4f))
                            }
                            StatusBadge("AUTHORIZED", AppleGreen)
                        }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.AccountTree, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                    Text(
                        text = "ACTIVE PROTOCOLS",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.5f),
                        fontWeight = FontWeight.Bold,
                    )
                }
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        workspace.protocol.endpoints
                            .groupBy { it.category }
                            .forEach { (category, endpoints) ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(category.uppercase(), style = MaterialTheme.typography.labelSmall, color = AppleBlue, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = endpoints.joinToString(" • ") { it.title },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.7f),
                                    )
                                }
                            }
                    }
                }
            }
        }
    }
}
