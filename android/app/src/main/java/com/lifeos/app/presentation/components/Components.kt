package com.lifeos.app.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeos.app.domain.model.DailyScore
import com.lifeos.app.domain.model.DayPulse
import com.lifeos.app.domain.model.Insight
import com.lifeos.app.presentation.theme.LifeOsColors
import kotlin.math.abs

@Composable
fun lifeOsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = LifeOsColors.Primary,
    unfocusedBorderColor = LifeOsColors.SurfaceBorder,
    focusedTextColor = LifeOsColors.OnBackground,
    unfocusedTextColor = LifeOsColors.OnBackground,
    cursorColor = LifeOsColors.Primary,
    focusedContainerColor = LifeOsColors.Surface,
    unfocusedContainerColor = LifeOsColors.Surface,
)

fun scoreColor(score: Double?): Color = when {
    score == null -> LifeOsColors.OnSurfaceMuted
    score >= 75 -> LifeOsColors.ScoreHigh
    score >= 50 -> LifeOsColors.ScoreMid
    score >= 25 -> LifeOsColors.Warning
    else -> LifeOsColors.ScoreLow
}

private fun DayPulse.tint(): Pair<Color, Color> = when (this) {
    DayPulse.ROUGH -> Color(0xFFFFE4E6) to Color(0xFFF43F5E)
    DayPulse.MEH -> Color(0xFFF1F5F9) to Color(0xFF94A3B8)
    DayPulse.GOOD -> Color(0xFFDCFCE7) to Color(0xFF22C55E)
    DayPulse.FLOW -> Color(0xFFFEF3C7) to Color(0xFFF59E0B)
}

@Composable
fun DemoBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFFFF7ED),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFED7AA)),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("◉", color = LifeOsColors.AccentAmber, fontSize = 10.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                "Preview mode — sample data",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFB45309),
            )
        }
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LifeOsColors.OnSurfaceMuted)
        }
    }
}

@Composable
fun LifeOsCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(2.dp, shape, ambientColor = Color(0x1A64748B), spotColor = Color(0x1A64748B))
            .clip(shape)
            .background(LifeOsColors.Surface)
            .border(1.dp, LifeOsColors.SurfaceBorder, shape),
    ) {
        if (accent != null) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(48.dp)
                    .align(Alignment.TopStart)
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
        }
        Box(Modifier.padding(18.dp)) { content() }
    }
}

@Composable
fun ScoreCard(title: String, score: Double?, modifier: Modifier = Modifier) {
    val color = scoreColor(score)
    LifeOsCard(modifier = modifier, accent = color) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = LifeOsColors.OnSurfaceMuted)
            Spacer(Modifier.height(6.dp))
            Text(
                text = score?.toInt()?.toString() ?: "—",
                style = MaterialTheme.typography.displaySmall,
                color = color,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { ((score ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = color,
                trackColor = LifeOsColors.SurfaceBorder,
            )
        }
    }
}

/** One metric's identity color — kept consistent everywhere it appears. */
fun metricColor(name: String): Color = when (name.lowercase()) {
    "sleep" -> LifeOsColors.MetricSleep
    "recovery" -> LifeOsColors.MetricRecovery
    "execution" -> LifeOsColors.MetricExecution
    "wellness" -> LifeOsColors.MetricWellness
    "fitness" -> LifeOsColors.MetricFitness
    else -> LifeOsColors.Primary
}

/** All of today's scores grouped into one organized card — no scattered tiles. */
@Composable
fun VitalsCard(score: DailyScore?, modifier: Modifier = Modifier) {
    LifeOsCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            VitalRow("Sleep", score?.sleepScore)
            VitalDivider()
            VitalRow("Recovery", score?.recoveryScore)
            VitalDivider()
            VitalRow("Execution", score?.executionScore)
            VitalDivider()
            VitalRow("Wellness", score?.wellnessScore)
            VitalDivider()
            VitalRow("Fitness", score?.fitnessScore)
        }
    }
}

@Composable
private fun VitalDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .padding(vertical = 0.dp)
            .background(LifeOsColors.SurfaceBorder.copy(alpha = 0.6f)),
    )
}

@Composable
private fun VitalRow(name: String, value: Double?) {
    val color = metricColor(name)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (value == null) LifeOsColors.SurfaceBorder else color),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = LifeOsColors.OnBackground,
            modifier = Modifier.weight(1f),
        )
        // Thin progress track on the right.
        Box(
            Modifier
                .width(84.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(LifeOsColors.SurfaceMuted),
        ) {
            val frac = ((value ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = value?.toInt()?.toString() ?: "—",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (value == null) LifeOsColors.OnSurfaceMuted else color,
            modifier = Modifier.width(28.dp),
        )
    }
}

/** Compact 7-ish-day bar strip for a single metric — adds rhythm at a glance. */
@Composable
fun WeeklyTrendCard(
    label: String,
    values: List<Double?>,
    modifier: Modifier = Modifier,
) {
    val color = metricColor(label)
    val recent = values.takeLast(7)
    LifeOsCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(8.dp))
                Text("$label · last 7 days", style = MaterialTheme.typography.titleSmall, color = LifeOsColors.OnBackground)
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                if (recent.isEmpty()) {
                    Text("Not enough data yet.", style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted)
                } else {
                    recent.forEach { v ->
                        val frac = ((v ?: 0.0) / 100.0).toFloat().coerceIn(0.04f, 1f)
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(frac)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (v == null) LifeOsColors.SurfaceMuted else color.copy(alpha = 0.85f)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StateHeroCard(
    stateLabel: String,
    confidence: Int?,
    rationale: String?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(3.dp, shape, ambientColor = Color(0x264F6BF6), spotColor = Color(0x264F6BF6))
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFFE0E7FF), Color(0xFFCCFBF1)),
                ),
            )
            .border(1.dp, LifeOsColors.Primary.copy(alpha = 0.2f), shape)
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("CURRENT STATE", style = MaterialTheme.typography.labelSmall, color = LifeOsColors.Primary)
            Text(stateLabel, style = MaterialTheme.typography.headlineMedium, color = LifeOsColors.OnBackground)
            if (confidence != null) {
                Text("$confidence% confidence", style = MaterialTheme.typography.labelMedium, color = LifeOsColors.OnSurfaceMuted)
            }
            if (!rationale.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(rationale, style = MaterialTheme.typography.bodyMedium, color = LifeOsColors.OnBackground.copy(alpha = 0.85f))
            }
        }
    }
}

@Composable
fun QuickCheckInCard(
    selected: DayPulse?,
    savedLabel: String?,
    enabled: Boolean,
    onSelect: (DayPulse) -> Unit,
    modifier: Modifier = Modifier,
) {
    LifeOsCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("How was today?", style = MaterialTheme.typography.titleMedium)
            Text(
                "One tap. Skip anytime — sleep & execution still teach LifeOS.",
                style = MaterialTheme.typography.bodySmall,
                color = LifeOsColors.OnSurfaceMuted,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DayPulse.entries.forEach { pulse ->
                    PulseButton(
                        pulse = pulse,
                        selected = selected == pulse,
                        enabled = enabled,
                        onClick = { onSelect(pulse) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (savedLabel != null) {
                Text(savedLabel, style = MaterialTheme.typography.labelMedium, color = LifeOsColors.Positive)
            }
        }
    }
}

@Composable
private fun PulseButton(
    pulse: DayPulse,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (soft, accent) = pulse.tint()
    val shape = RoundedCornerShape(16.dp)
    val bg = if (selected) soft else LifeOsColors.Background
    val border = if (selected) accent else LifeOsColors.SurfaceBorder
    Column(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(if (selected) 2.dp else 1.dp, border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(pulse.emoji, fontSize = 28.sp)
        Text(
            pulse.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) accent else LifeOsColors.OnSurfaceMuted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
fun InsightCard(insight: Insight, modifier: Modifier = Modifier) {
    val strength = abs(insight.correlation)
    val badge = when {
        strength >= 0.7 -> "Strong" to LifeOsColors.ScoreHigh
        strength >= 0.5 -> "Moderate" to LifeOsColors.ScoreMid
        else -> "Emerging" to LifeOsColors.OnSurfaceMuted
    }
    LifeOsCard(modifier = modifier, accent = badge.second) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(8.dp), color = badge.second.copy(alpha = 0.12f)) {
                    Text(
                        badge.first,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = badge.second,
                    )
                }
                Text(
                    "r=${"%+.2f".format(insight.correlation)} · n=${insight.sampleSize}",
                    style = MaterialTheme.typography.labelSmall,
                    color = LifeOsColors.OnSurfaceMuted,
                )
            }
            Text(insight.summary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun NotificationCard(
    title: String,
    detail: String,
    severity: String,
    modifier: Modifier = Modifier,
) {
    val accent = when (severity) {
        "warning" -> LifeOsColors.Warning
        "positive" -> LifeOsColors.Positive
        else -> LifeOsColors.OnSurfaceMuted
    }
    LifeOsCard(modifier = modifier, accent = accent) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = accent, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = LifeOsColors.OnBackground)
        }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = LifeOsColors.OnSurfaceMuted,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}
