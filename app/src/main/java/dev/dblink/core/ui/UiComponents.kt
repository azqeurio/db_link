package dev.dblink.core.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import dev.dblink.ui.theme.AppleBlue
import dev.dblink.ui.theme.Chalk
import dev.dblink.ui.theme.Graphite
import dev.dblink.ui.theme.Iron
import dev.dblink.ui.theme.LeicaBorder
import dev.dblink.ui.theme.LeicaPaper
import dev.dblink.ui.theme.Obsidian

@Composable
fun LensBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Graphite.copy(alpha = 0.78f),
                    Obsidian,
                ),
                center = Offset(size.width * 0.5f, size.height * 0.25f),
                radius = size.maxDimension,
            ),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(AppleBlue.copy(alpha = 0.08f), Color.Transparent),
                center = Offset(size.width * 0.82f, size.height * 0.16f),
                radius = size.minDimension * 0.18f,
            ),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(LeicaPaper.copy(alpha = 0.03f), Color.Transparent),
                center = Offset(size.width * 0.22f, size.height * 0.24f),
                radius = size.minDimension * 0.24f,
            ),
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.titleLarge,
        color = Chalk,
        fontWeight = FontWeight.Medium,
        modifier = modifier.padding(bottom = 8.dp),
        letterSpacing = 1.6.sp,
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Card(
        modifier = modifier.animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = Graphite.copy(alpha = 0.98f),
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = LeicaBorder,
                    shape = RoundedCornerShape(24.dp),
                )
                .padding(22.dp),
            content = content,
        )
    }
}

@Composable
fun MetricPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = Iron,
        contentColor = Chalk,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = Chalk.copy(alpha = 0.5f),
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun StatusBadge(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = Chalk.copy(alpha = 0.62f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = Chalk,
            fontWeight = FontWeight.Medium,
        )
    }
}
