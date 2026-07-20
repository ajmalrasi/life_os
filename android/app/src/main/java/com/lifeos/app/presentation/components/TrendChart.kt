package com.lifeos.app.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeos.app.domain.model.DailyScore
import com.lifeos.app.presentation.theme.LifeOsColors
import kotlin.math.roundToInt

/** Selectable series shown on the trend chart. "Overall" is the composite of all vitals. */
private enum class TrendMetric(val label: String) {
    OVERALL("Overall"),
    SLEEP("Sleep"),
    RECOVERY("Recovery"),
    EXECUTION("Execution"),
    WELLNESS("Wellness"),
    FITNESS("Fitness"),
}

/** Selectable look-back window for the trend chart. */
private enum class TrendRange(val label: String, val days: Int) {
    DAY("1D", 1),
    WEEK("7D", 7),
    MONTH("30D", 30),
    HALF_YEAR("6M", 180),
    YEAR("1Y", 365),
}

private fun TrendMetric.color(): Color = when (this) {
    TrendMetric.OVERALL -> LifeOsColors.Primary
    TrendMetric.SLEEP -> LifeOsColors.MetricSleep
    TrendMetric.RECOVERY -> LifeOsColors.MetricRecovery
    TrendMetric.EXECUTION -> LifeOsColors.MetricExecution
    TrendMetric.WELLNESS -> LifeOsColors.MetricWellness
    TrendMetric.FITNESS -> LifeOsColors.MetricFitness
}

/** Composite "Life Score": average of the available vitals for a day. */
fun compositeScore(score: DailyScore?): Double? {
    if (score == null) return null
    val parts = listOfNotNull(
        score.sleepScore, score.recoveryScore, score.executionScore,
        score.wellnessScore, score.fitnessScore,
    )
    return if (parts.isEmpty()) null else parts.average()
}

private fun TrendMetric.valueOf(score: DailyScore): Double? = when (this) {
    TrendMetric.OVERALL -> compositeScore(score)
    TrendMetric.SLEEP -> score.sleepScore
    TrendMetric.RECOVERY -> score.recoveryScore
    TrendMetric.EXECUTION -> score.executionScore
    TrendMetric.WELLNESS -> score.wellnessScore
    TrendMetric.FITNESS -> score.fitnessScore
}

/**
 * Historical trend graph: smooth line + gradient area over the full score history,
 * with metric-toggle chips. Replaces the "today only" view with real trajectory.
 *
 * @param history newest-first list of daily scores (as held in UI state).
 */
@Composable
fun TrendChart(history: List<DailyScore>, modifier: Modifier = Modifier) {
    var metric by remember { mutableStateOf(TrendMetric.OVERALL) }
    var range by remember { mutableStateOf(TrendRange.MONTH) }
    val color = metric.color()

    // Chronological order for plotting (state holds newest-first), then sliced
    // to the selected look-back window.
    val chrono = remember(history, range) {
        history.reversed().takeLast(range.days)
    }
    val values = chrono.map { metric.valueOf(it) }
    val present = values.filterNotNull()

    val current = present.lastOrNull()
    val avg = if (present.isNotEmpty()) present.average() else null
    val delta = if (present.size >= 2) present.last() - present[present.size - 2] else null

    // The headline value is the most recent reading in the window. When that
    // reading is NOT the window's latest day (e.g. today has no sleep yet, but an
    // earlier night does), the number is stale. Flag it so it doesn't read as
    // "today's score". `lastDataIdx` is the slot of that reading in `chrono`.
    val lastDataIdx = values.indexOfLast { it != null }
    val latestDay = chrono.getOrNull(lastDataIdx)?.day
    val isStale = lastDataIdx in 0 until values.lastIndex

    LifeOsCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Header: title + current value + delta vs previous reading.
            Row(verticalAlignment = Alignment.Bottom) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "TREND · ${range.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${metric.label} score",
                        style = MaterialTheme.typography.titleMedium,
                        color = LifeOsColors.OnBackground,
                    )
                }
                if (current != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            current.roundToInt().toString(),
                            style = MaterialTheme.typography.displaySmall,
                            // Mute the number when it's an older reading so it no
                            // longer reads as the current/today score.
                            color = if (isStale) LifeOsColors.OnSurfaceMuted else color,
                        )
                        when {
                            delta != null -> DeltaPill(delta)
                            isStale && latestDay != null -> Text(
                                "latest · ${formatShort(latestDay)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = LifeOsColors.OnSurfaceMuted,
                            )
                        }
                    }
                }
            }

            // Range selector: segmented control for the look-back window.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(LifeOsColors.SurfaceMuted)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                TrendRange.entries.forEach { r ->
                    RangeSegment(
                        label = r.label,
                        color = color,
                        selected = r == range,
                        onClick = { range = r },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Metric toggle chips.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TrendMetric.entries.forEach { m ->
                    MetricChip(
                        label = m.label,
                        color = m.color(),
                        selected = m == metric,
                        onClick = { metric = m },
                    )
                }
            }

            if (present.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().height(176.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No ${metric.label.lowercase()} data in this range yet. Keep syncing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                }
            } else if (present.size == 1) {
                // Exactly one day has data in this window. The score is per-day; a
                // trend *line* needs 2+ days. Render an intentional single-point
                // card: the value + which day, plus a message that fits the
                // selected range (1D vs a sparse multi-day window).
                val onlyValue = present.first().roundToInt()
                val dayLabel = latestDay?.let { formatShort(it) }
                // The single point may be an earlier day, not literally today, so
                // keep the 1D copy range-neutral (the date is shown by the dot).
                val message = if (range.days == 1) {
                    "Just one day in this range. A trend line appears once there are 2+ days."
                } else {
                    "Only one day with ${metric.label.lowercase()} data in the last " +
                        "${range.days} days. A trend line needs 2+."
                }
                Box(
                    Modifier.fillMaxWidth().height(176.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                buildString {
                                    append(onlyValue)
                                    if (dayLabel != null) append(" · ").append(dayLabel)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = LifeOsColors.OnBackground,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = LifeOsColors.OnSurfaceMuted,
                        )
                    }
                }
            } else {
                // Y-axis labels + plot area.
                Row(modifier = Modifier.fillMaxWidth().height(176.dp)) {
                    Column(
                        modifier = Modifier.fillMaxHeight().padding(end = 8.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.End,
                    ) {
                        listOf(100, 75, 50, 25, 0).forEach {
                            Text(
                                it.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = LifeOsColors.ChartAxisText,
                            )
                        }
                    }
                    LineGraph(
                        values = values,
                        color = color,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }

                // X-axis range labels.
                Row(modifier = Modifier.fillMaxWidth().padding(start = 28.dp)) {
                    Text(
                        chrono.firstOrNull()?.day?.let { formatShort(it) } ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = LifeOsColors.ChartAxisText,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Today",
                        style = MaterialTheme.typography.labelSmall,
                        color = LifeOsColors.ChartAxisText,
                    )
                }

                // Footer stat chips.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatChip("Avg", avg?.roundToInt()?.toString() ?: "N/A", Modifier.weight(1f))
                    StatChip("Low", present.minOrNull()?.roundToInt()?.toString() ?: "N/A", Modifier.weight(1f))
                    StatChip("High", present.maxOrNull()?.roundToInt()?.toString() ?: "N/A", Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LineGraph(values: List<Double?>, color: Color, modifier: Modifier = Modifier) {
    // Animate the reveal so switching metrics feels alive.
    val anim by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(600),
        label = "trend-reveal",
    )
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val n = values.size
        if (n < 2) return@Canvas

        // Horizontal gridlines at 0/25/50/75/100.
        for (g in 0..4) {
            val y = h * g / 4f
            drawLine(
                color = LifeOsColors.ChartGrid,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1f,
            )
        }

        fun pointAt(i: Int, v: Double): Offset {
            val x = if (n == 1) 0f else w * i / (n - 1)
            val y = h - (v / 100.0).toFloat().coerceIn(0f, 1f) * h
            return Offset(x, y)
        }

        // Split into contiguous non-null segments so gaps don't draw phantom lines.
        val segments = mutableListOf<List<Offset>>()
        var cur = mutableListOf<Offset>()
        values.forEachIndexed { i, v ->
            if (v != null) cur.add(pointAt(i, v)) else {
                if (cur.size > 0) { segments.add(cur); cur = mutableListOf() }
            }
        }
        if (cur.isNotEmpty()) segments.add(cur)

        segments.forEach { pts ->
            if (pts.size == 1) {
                drawCircle(color, radius = 4f, center = pts[0])
                return@forEach
            }
            val line = smoothPath(pts)

            // Gradient area fill under the curve.
            val area = Path().apply {
                addPath(line)
                lineTo(pts.last().x, h)
                lineTo(pts.first().x, h)
                close()
            }
            drawPath(
                path = area,
                brush = Brush.verticalGradient(
                    listOf(color.copy(alpha = 0.28f * anim), color.copy(alpha = 0f)),
                ),
            )
            drawPath(
                path = line,
                color = color,
                style = Stroke(width = 3.5f),
            )
        }

        // Emphasize the latest reading.
        segments.lastOrNull()?.lastOrNull()?.let { last ->
            drawCircle(Color.White, radius = 6.5f, center = last)
            drawCircle(color, radius = 4.5f, center = last)
        }
    }
}

/** Catmull-Rom → cubic Bézier smoothing for a pleasant, non-jagged curve. */
private fun smoothPath(pts: List<Offset>): Path {
    val path = Path()
    path.moveTo(pts[0].x, pts[0].y)
    for (i in 0 until pts.size - 1) {
        val p0 = pts[if (i - 1 < 0) 0 else i - 1]
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = pts[if (i + 2 > pts.size - 1) pts.size - 1 else i + 2]
        val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
        val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
        path.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
    }
    return path
}

@Composable
private fun RangeSegment(
    label: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) LifeOsColors.Surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) color else LifeOsColors.OnSurfaceMuted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun MetricChip(label: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) color.copy(alpha = 0.12f) else LifeOsColors.SurfaceMuted)
            .border(1.dp, if (selected) color else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) color else LifeOsColors.OnSurfaceMuted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun DeltaPill(delta: Double) {
    val up = delta >= 0
    val c = if (up) LifeOsColors.ScoreHigh else LifeOsColors.ScoreLow
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            (if (up) "▲ " else "▼ ") + "%.0f".format(kotlin.math.abs(delta)),
            style = MaterialTheme.typography.labelMedium,
            color = c,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StatChip(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(LifeOsColors.SurfaceMuted)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = LifeOsColors.OnBackground, fontWeight = FontWeight.Bold)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = LifeOsColors.OnSurfaceMuted)
    }
}

private fun formatShort(date: java.time.LocalDate): String =
    date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
