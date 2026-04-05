package dev.pl36.cameralink.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pl36.cameralink.ui.theme.AppleBlue
import dev.pl36.cameralink.ui.theme.Chalk
import dev.pl36.cameralink.ui.theme.Graphite
import dev.pl36.cameralink.ui.theme.Iron
import dev.pl36.cameralink.ui.theme.LeicaBorder

@Composable
fun ActionCard(
    title: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .widthIn(min = 150.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                if (enabled) Graphite.copy(alpha = 0.9f)
                else Graphite.copy(alpha = 0.4f)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .then(
                Modifier
                    .background(Color.Transparent)
            )
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(if (enabled) Graphite.copy(alpha = 0.96f) else Graphite.copy(alpha = 0.5f))
                .padding(1.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(23.dp))
                    .background(Graphite)
                    .background(Color.Transparent)
                    .clickable(enabled = enabled, onClick = onClick)
                    .padding(18.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(
                        color = if (enabled) accent.copy(alpha = 0.14f) else Iron.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (enabled) accent else Chalk.copy(alpha = 0.35f),
                            modifier = Modifier.padding(10.dp).size(22.dp),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = title.uppercase(),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (enabled) Chalk else Chalk.copy(alpha = 0.35f),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Box(
                            modifier = Modifier
                                .widthIn(max = 40.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (enabled) accent else LeicaBorder)
                                .padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingToggleRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Iron.copy(alpha = 0.55f))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Chalk,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = Chalk.copy(alpha = 0.55f),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = AppleBlue,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = LeicaBorder,
                uncheckedThumbColor = Chalk.copy(alpha = 0.65f),
                uncheckedBorderColor = LeicaBorder,
            ),
        )
    }
}
