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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/** The five vitals shown in [VitalsCard], in display order. */
private val VITALS = listOf("Sleep", "Recovery", "Execution", "Wellness", "Fitness")

/** Pull a single metric's score out of a [DailyScore] by its display name. */
private fun DailyScore.metricValue(name: String): Double? = when (name) {
    "Sleep" -> sleepScore
    "Recovery" -> recoveryScore
    "Execution" -> executionScore
    "Wellness" -> wellnessScore
    "Fitness" -> fitnessScore
    else -> null
}

/** Plain-language description of what each vital captures. */
private fun metricBlurb(name: String): String = when (name) {
    "Sleep" -> "How well you slept — duration, deep/REM balance, and how fragmented the night was."
    "Recovery" -> "Overnight physiological recovery from resting heart rate, HRV, and blood oxygen."
    "Execution" -> "What you got done — coding activity, commits, and follow-through on the day."
    "Wellness" -> "Your own check-ins — mood, energy, focus, and stress logged in the Pulse tab."
    "Fitness" -> "Movement and cardio load — steps, workouts, calories, and VO₂ max."
    else -> ""
}

/**
 * All of today's scores grouped into one organized card. Tapping a row opens a
 * detail sheet with that metric's recent trajectory and stats.
 *
 * @param history newest-first score history, used to chart each metric's trend.
 */
@Composable
fun VitalsCard(
    score: DailyScore?,
    history: List<DailyScore> = emptyList(),
    modifier: Modifier = Modifier,
) {
    var detail by remember { mutableStateOf<String?>(null) }

    LifeOsCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            VITALS.forEachIndexed { i, name ->
                if (i > 0) VitalDivider()
                VitalRow(name, score?.metricValue(name), onClick = { detail = name })
            }
        }
    }

    detail?.let { name ->
        VitalDetailSheet(
            name = name,
            history = history,
            onDismiss = { detail = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VitalDetailSheet(
    name: String,
    history: List<DailyScore>,
    onDismiss: () -> Unit,
) {
    val color = metricColor(name)
    // History is newest-first; chart oldest→newest, last 14 points.
    val series = remember(history, name) {
        history.asReversed().map { it.metricValue(name) }.takeLast(14)
    }
    val present = series.filterNotNull()
    val current = present.lastOrNull()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LifeOsColors.Surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(10.dp))
                Text(name, style = MaterialTheme.typography.headlineSmall, color = LifeOsColors.OnBackground)
                Spacer(Modifier.weight(1f))
                Text(
                    text = current?.toInt()?.toString() ?: "—",
                    style = MaterialTheme.typography.displaySmall,
                    color = if (current == null) LifeOsColors.OnSurfaceMuted else color,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                metricBlurb(name),
                style = MaterialTheme.typography.bodyMedium,
                color = LifeOsColors.OnSurfaceMuted,
            )

            // Recent trajectory as a bar strip.
            Text("LAST ${series.size.coerceAtLeast(1)} DAYS", style = MaterialTheme.typography.labelSmall, color = LifeOsColors.OnSurfaceMuted)
            Row(
                modifier = Modifier.fillMaxWidth().height(72.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                if (present.isEmpty()) {
                    Text("No data yet.", style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted)
                } else {
                    series.forEach { v ->
                        val frac = ((v ?: 0.0) / 100.0).toFloat().coerceIn(0.04f, 1f)
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(frac)
                                .clip(RoundedCornerShape(5.dp))
                                .background(if (v == null) LifeOsColors.SurfaceMuted else color.copy(alpha = 0.85f)),
                        )
                    }
                }
            }

            // Avg / Low / High over the shown window.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VitalStat("Avg", present.takeIf { it.isNotEmpty() }?.average()?.toInt()?.toString() ?: "—", Modifier.weight(1f))
                VitalStat("Low", present.minOrNull()?.toInt()?.toString() ?: "—", Modifier.weight(1f))
                VitalStat("High", present.maxOrNull()?.toInt()?.toString() ?: "—", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun VitalStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(LifeOsColors.SurfaceMuted)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = LifeOsColors.OnBackground, fontWeight = FontWeight.Bold)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = LifeOsColors.OnSurfaceMuted)
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
private fun VitalRow(name: String, value: Double?, onClick: () -> Unit) {
    val color = metricColor(name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
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
        Spacer(Modifier.width(6.dp))
        Text("›", style = MaterialTheme.typography.titleMedium, color = LifeOsColors.OnSurfaceMuted)
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

/**
 * Top-of-dashboard focal point: the composite Life Score for today, its day-over-day
 * delta, and the inferred current state — all in one gradient hero so the eye lands here first.
 */
@Composable
fun LifeScoreHero(
    score: Double?,
    delta: Double?,
    stateLabel: String,
    confidence: Int?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(6.dp, shape, ambientColor = Color(0x33059669), spotColor = Color(0x33059669))
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF059669), Color(0xFF0D9488), Color(0xFF10B981)),
                ),
            )
            .padding(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "TODAY'S LIFE SCORE",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = score?.let { kotlin.math.round(it).toInt().toString() } ?: "—",
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "/100",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Spacer(Modifier.weight(1f))
                if (delta != null) {
                    val up = delta >= 0
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color.White.copy(alpha = 0.18f),
                        modifier = Modifier.padding(bottom = 12.dp),
                    ) {
                        Text(
                            (if (up) "▲ " else "▼ ") + "${kotlin.math.abs(delta).toInt()} vs yesterday",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.16f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    buildString {
                        append("◆  ")
                        append(stateLabel)
                        if (confidence != null) append("  ·  $confidence%")
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
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
            .shadow(3.dp, shape, ambientColor = Color(0x26059669), spotColor = Color(0x26059669))
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFFD1FAE5), Color(0xFFCCFBF1)),
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
            // Lead with the LLM narrative when we have one; keep the deterministic
            // summary visible underneath as the grounding fact.
            val narrative = insight.narrative?.takeIf { it.isNotBlank() }
            Text(
                narrative ?: insight.summary,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (narrative != null) {
                Text(
                    insight.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = LifeOsColors.OnSurfaceMuted,
                )
            }
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
