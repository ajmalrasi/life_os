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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeos.app.domain.model.DailyScore
import com.lifeos.app.domain.model.Insight
import com.lifeos.app.domain.model.SynthesizedInsight
import com.lifeos.app.presentation.theme.LifeOsColors
import kotlin.math.abs
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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
                "Preview mode: sample data",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFB45309),
            )
        }
    }
}

/**
 * Opens the navigation drawer. Provided only while a drawer area is the root
 * page; pages reached deeper (with a Back bar) get null and show no menu.
 */
val LocalOpenNavigation = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * The one header every drawer area uses, fixed above its scrolling content,
 * so the menu button, title size and spacing never move between areas.
 */
@Composable
fun AreaHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    subtitleContent: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val openNavigation = LocalOpenNavigation.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(LifeOsColors.Background)
            .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (openNavigation != null) {
            Surface(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = openNavigation),
                shape = RoundedCornerShape(14.dp),
                color = LifeOsColors.SurfaceMuted,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.Menu,
                        contentDescription = "Open navigation drawer",
                        tint = LifeOsColors.OnBackground,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineLarge,
                color = LifeOsColors.OnBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                subtitleContent != null -> subtitleContent()
                subtitle != null -> Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = LifeOsColors.OnSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        action?.invoke()
    }
}

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LifeOsColors.OnSurfaceMuted)
            }
        }
        action?.invoke()
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
                text = score?.toInt()?.toString() ?: "N/A",
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

/** One metric's identity color, kept consistent everywhere it appears. */
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
    "Sleep" -> "How well you slept: duration, deep/REM balance, and how fragmented the night was."
    "Recovery" -> "Overnight physiological recovery from resting heart rate, HRV, and blood oxygen."
    "Execution" -> "What you got done: coding activity, commits, and follow-through on the day."
    "Wellness" -> "Your own check-ins: mood, energy, focus, and stress from the Pulse card on Today."
    "Fitness" -> "Movement and cardio load: steps, workouts, calories, and VO₂ max."
    else -> ""
}

/**
 * The five scores in one card. Each row shows its most recent reading and, when
 * that reading is not from today, the day it is from, so a metric that has not
 * arrived yet never reads as a bare "N/A". Wellness only exists once you check
 * in, so without a check-in today its row offers one instead. Tapping any other
 * row opens that metric's recent trajectory.
 *
 * @param history newest-first score history.
 */
@Composable
fun VitalsCard(
    history: List<DailyScore>,
    modifier: Modifier = Modifier,
    /** Opens a check-in; null when one is already on screen (Today's Pulse card). */
    onCheckIn: (() -> Unit)? = null,
) {
    var detail by remember { mutableStateOf<String?>(null) }
    val today = LocalDate.now()

    LifeOsCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            VITALS.forEachIndexed { i, name ->
                if (i > 0) VitalDivider()
                val latest = history.firstOrNull { it.metricValue(name) != null }
                val value = latest?.metricValue(name)
                val day = latest?.day
                if (name == "Wellness" && day != today) {
                    VitalRow(
                        name,
                        value = null,
                        caption = "No check-in today",
                        action = if (onCheckIn != null) "Check in" else null,
                        onClick = onCheckIn ?: { detail = name },
                    )
                } else {
                    VitalRow(
                        name,
                        value = value,
                        caption = when {
                            day == null -> "No data yet"
                            name == "Sleep" && day == today -> "Last night"
                            day == today -> null
                            name == "Sleep" -> "Night ending ${formatVitalDay(day, today).replaceFirstChar { it.lowercase() }}"
                            else -> formatVitalDay(day, today)
                        },
                        onClick = { detail = name },
                    )
                }
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

private fun formatVitalDay(day: LocalDate, today: LocalDate): String = when (day) {
    today.minusDays(1) -> "Yesterday"
    else -> day.format(DateTimeFormatter.ofPattern("MMM d"))
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
                    text = current?.toInt()?.toString() ?: "N/A",
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
                VitalStat("Avg", present.takeIf { it.isNotEmpty() }?.average()?.toInt()?.toString() ?: "N/A", Modifier.weight(1f))
                VitalStat("Low", present.minOrNull()?.toInt()?.toString() ?: "N/A", Modifier.weight(1f))
                VitalStat("High", present.maxOrNull()?.toInt()?.toString() ?: "N/A", Modifier.weight(1f))
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
private fun VitalRow(
    name: String,
    value: Double?,
    caption: String?,
    onClick: () -> Unit,
    action: String? = null,
) {
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = LifeOsColors.OnBackground,
            )
            if (caption != null) {
                Text(caption, style = MaterialTheme.typography.labelMedium, color = LifeOsColors.OnSurfaceMuted)
            }
        }
        if (action != null) {
            Text(action, style = MaterialTheme.typography.titleSmall, color = LifeOsColors.Primary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text("›", style = MaterialTheme.typography.titleMedium, color = LifeOsColors.Primary)
        } else {
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
                text = value?.toInt()?.toString().orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (value == null) LifeOsColors.OnSurfaceMuted else color,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(40.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("›", style = MaterialTheme.typography.titleMedium, color = LifeOsColors.OnSurfaceMuted)
        }
    }
}

/** Compact 7-ish-day bar strip for a single metric that adds rhythm at a glance. */
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

private fun domainColor(domain: String?): Color = when (domain) {
    "money" -> LifeOsColors.AccentAmber
    "trip" -> LifeOsColors.AccentSky
    "health" -> LifeOsColors.MetricSleep
    "mind" -> LifeOsColors.MetricWellness
    "work" -> LifeOsColors.MetricExecution
    else -> LifeOsColors.AccentTeal
}

private fun severityColor(severity: String?): Color = when (severity) {
    "high" -> LifeOsColors.ScoreLow
    "low" -> LifeOsColors.OnSurfaceMuted
    else -> LifeOsColors.AccentAmber
}

@Composable
fun SynthesizedInsightCard(insight: SynthesizedInsight, modifier: Modifier = Modifier) {
    val accent = domainColor(insight.theme)
    LifeOsCard(modifier = modifier, accent = accent) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val domain = insight.theme?.takeIf { it.isNotBlank() }
                if (domain != null) {
                    Surface(shape = RoundedCornerShape(8.dp), color = accent.copy(alpha = 0.12f)) {
                        Text(
                            domain.replaceFirstChar { it.uppercase() },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(severityColor(insight.severity), shape = CircleShape),
                )
            }
            Text(
                insight.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = LifeOsColors.OnBackground,
            )
            Text(
                insight.body,
                style = MaterialTheme.typography.bodyMedium,
                color = LifeOsColors.OnBackground,
            )
            val action = insight.action?.takeIf { it.isNotBlank() }
            if (action != null) {
                Text(
                    "→ $action",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
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

/** Shown wherever a screen falls back to cached data after a failed refresh. */
@Composable
fun OfflineBanner(lastSyncAt: Long?, modifier: Modifier = Modifier) {
    val asOf = lastSyncAt?.let {
        val time = java.time.Instant.ofEpochMilli(it)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalTime()
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        " · as of $time"
    } ?: ""
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = LifeOsColors.Warning.copy(alpha = 0.10f),
    ) {
        Text(
            "Offline$asOf · showing cached data",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelMedium,
            color = LifeOsColors.Warning,
        )
    }
}
