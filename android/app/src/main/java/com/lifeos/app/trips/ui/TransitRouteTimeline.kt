package com.lifeos.app.trips.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.DirectionsSubway
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.SwapCalls
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeos.app.presentation.theme.LifeOsColors
import com.lifeos.app.trips.importer.ImportTransitDetails
import com.lifeos.app.trips.importer.ImportTransitEndpoint
import com.lifeos.app.trips.importer.ImportTransitStep

@Composable
fun TransitRouteTimeline(steps: List<ImportTransitStep>, modifier: Modifier = Modifier) {
    if (steps.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        steps.forEach { step ->
            TransitStepCard(step)
        }
    }
}

@Composable
private fun TransitStepCard(step: ImportTransitStep) {
    val modeColor = parseLineColor(step.transitDetails?.lineColor, step.mode)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header: Step number, Mode badge, Title & Duration
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    ModeBadge(step.mode, modeColor)
                    Column {
                        Text(
                            text = "Step ${step.stepOrder}: ${step.title}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    step.transitDetails?.fare?.let { fare ->
                        BadgeChip(
                            text = "${fare.currency} ${fare.amount}",
                            backgroundColor = LifeOsColors.Positive.copy(alpha = 0.15f),
                            textColor = LifeOsColors.Positive,
                        )
                    }
                    step.durationMinutes?.let { mins ->
                        BadgeChip(
                            text = "$mins min",
                            backgroundColor = MaterialTheme.colorScheme.surface,
                            textColor = LifeOsColors.OnSurfaceMuted,
                        )
                    }
                }
            }

            // Route Timeline with Departure, Route line, and Arrival
            RouteTimelineView(step, modeColor)

            // Step Navigation Instructions / Transfer guidance
            step.instructions?.let { instructions ->
                InstructionCallout(instructions)
            }

            // Additional Notes
            step.notes?.let { notes ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Rounded.Info,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp).padding(top = 2.dp),
                        tint = LifeOsColors.OnSurfaceMuted,
                    )
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeBadge(mode: String, modeColor: Color) {
    val icon = modeIcon(mode)
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(modeColor.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = mode,
            tint = modeColor,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun RouteTimelineView(step: ImportTransitStep, modeColor: Color) {
    val dep = step.departure
    val arr = step.arrival
    val details = step.transitDetails

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        // Departure Stop Row
        if (dep != null) {
            StopNodeRow(
                isDeparture = true,
                endpoint = dep,
                nodeColor = modeColor,
            )
        }

        // Connecting in-transit transit route segment
        RouteSegmentRow(
            modeColor = modeColor,
            step = step,
            details = details,
            showVerticalLine = dep != null && arr != null,
        )

        // Arrival Stop Row
        if (arr != null) {
            StopNodeRow(
                isDeparture = false,
                endpoint = arr,
                nodeColor = modeColor,
            )
        }
    }
}

@Composable
private fun StopNodeRow(
    isDeparture: Boolean,
    endpoint: ImportTransitEndpoint,
    nodeColor: Color,
) {
    val stopTitle = endpoint.stationName ?: endpoint.name ?: if (isDeparture) "Departure" else "Arrival"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Timeline indicator icon/circle
        Box(
            modifier = Modifier.width(20.dp).padding(top = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isDeparture) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(nodeColor)
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(LifeOsColors.Positive)
                        .border(2.dp, MaterialTheme.colorScheme.surface, RoundedCornerShape(3.dp)),
                )
            }
        }

        // Endpoint details
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stopTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    endpoint.stationCode?.let { code ->
                        BadgeChip(text = code, backgroundColor = MaterialTheme.colorScheme.surfaceVariant, textColor = LifeOsColors.OnSurfaceMuted)
                    }
                }

                endpoint.scheduledTime?.let { time ->
                    Text(
                        text = time,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = LifeOsColors.Primary,
                    )
                }
            }

            // High-detail badges (Terminal, Platform, Exit Gate, Pickup Zone)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                endpoint.terminal?.let { term ->
                    BadgeChip(
                        text = if (term.startsWith("T", ignoreCase = true)) term else "Terminal $term",
                        backgroundColor = LifeOsColors.PrimarySoft.copy(alpha = 0.7f),
                        textColor = LifeOsColors.Primary,
                    )
                }

                endpoint.platform?.let { plat ->
                    BadgeChip(
                        text = if (plat.startsWith("Platform", ignoreCase = true)) plat else "Platform $plat",
                        backgroundColor = LifeOsColors.AccentAmber.copy(alpha = 0.18f),
                        textColor = LifeOsColors.AccentAmber,
                        bold = true,
                    )
                }

                endpoint.gate?.let { gate ->
                    BadgeChip(
                        text = if (gate.startsWith("Gate", ignoreCase = true)) gate else "Gate $gate",
                        backgroundColor = MaterialTheme.colorScheme.surface,
                        textColor = LifeOsColors.OnSurfaceMuted,
                    )
                }

                endpoint.exitGate?.let { exit ->
                    BadgeChip(
                        text = "Exit: $exit",
                        backgroundColor = LifeOsColors.Positive.copy(alpha = 0.18f),
                        textColor = LifeOsColors.Positive,
                        bold = true,
                    )
                }
            }

            endpoint.pickupLocation?.let { pickup ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.LocationOn, contentDescription = null, modifier = Modifier.size(13.dp), tint = LifeOsColors.Warning)
                    Text(text = "Pickup: $pickup", style = MaterialTheme.typography.bodySmall, color = LifeOsColors.Warning, fontWeight = FontWeight.Medium)
                }
            }

            endpoint.dropoffLocation?.let { dropoff ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.LocationOn, contentDescription = null, modifier = Modifier.size(13.dp), tint = LifeOsColors.OnSurfaceMuted)
                    Text(text = "Drop-off: $dropoff", style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted)
                }
            }

            endpoint.notes?.let { notes ->
                Text(text = notes, style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted)
            }
        }
    }
}

@Composable
private fun RouteSegmentRow(
    modeColor: Color,
    step: ImportTransitStep,
    details: ImportTransitDetails?,
    showVerticalLine: Boolean,
) {
    var expandedStops by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Vertical timeline bar
        Box(
            modifier = Modifier.width(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (showVerticalLine) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(if (expandedStops) 120.dp else 56.dp)
                        .background(modeColor.copy(alpha = 0.7f), RoundedCornerShape(2.dp)),
                )
            }
        }

        // Segment Content Box
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (details != null) {
                // Line name / Train name
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    details.lineName?.let { line ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(modeColor),
                            )
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = modeColor,
                            )
                        }
                    }

                    details.trainNumber?.let { trainNo ->
                        BadgeChip(
                            text = "#$trainNo ${details.trainName.orEmpty()}",
                            backgroundColor = LifeOsColors.PrimarySoft,
                            textColor = LifeOsColors.Primary,
                            bold = true,
                        )
                    }

                    details.roadType?.let { road ->
                        val label = listOfNotNull(details.serviceProvider, road.replace('_', ' ')).joinToString(" • ")
                        BadgeChip(
                            text = label,
                            backgroundColor = MaterialTheme.colorScheme.surface,
                            textColor = LifeOsColors.OnSurfaceMuted,
                        )
                    }
                }

                // Direction / Headsign
                details.direction?.let { dir ->
                    Text(
                        text = if (dir.startsWith("Towards", ignoreCase = true)) dir else "Towards $dir",
                        style = MaterialTheme.typography.bodySmall,
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                }

                // Train Coach & Seat / Berth / Class + PNR
                val seatParts = listOfNotNull(
                    details.coach?.let { if (it.startsWith("Coach", ignoreCase = true)) it else "Coach $it" },
                    details.seatOrBerth?.let { if (it.startsWith("Seat", ignoreCase = true) || it.startsWith("Berth", ignoreCase = true)) it else "Seat $it" },
                    details.bookingClass,
                )
                if (seatParts.isNotEmpty() || details.pnr != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = LifeOsColors.PrimarySoft.copy(alpha = 0.6f),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            if (seatParts.isNotEmpty()) {
                                Text(
                                    text = seatParts.joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = LifeOsColors.Primary,
                                )
                            }
                            details.pnr?.let { pnr ->
                                Text(
                                    text = "PNR: $pnr",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    color = LifeOsColors.OnSurfaceMuted,
                                )
                            }
                        }
                    }
                }

                // Additional Transit Notes (e.g. onboard breakfast)
                details.notes?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                }

                // Flight specifics
                val flightParts = listOfNotNull(
                    details.airline,
                    details.flightNumber,
                    details.seatOrBerth?.let { "Seat $it" },
                    details.boardingGroup?.let { "Group $it" },
                    details.baggageBelt?.let { "Belt $it" },
                )
                if (flightParts.isNotEmpty() && step.mode.lowercase() == "flight") {
                    Text(
                        text = flightParts.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = LifeOsColors.Primary,
                        fontWeight = FontWeight.Medium,
                    )
                }

                // Intermediate Stops Accordion
                if (details.intermediateStops.isNotEmpty()) {
                    val count = details.stopsCount ?: details.intermediateStops.size
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { expandedStops = !expandedStops }
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "$count stops",
                            style = MaterialTheme.typography.labelSmall,
                            color = LifeOsColors.Secondary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Icon(
                            if (expandedStops) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = LifeOsColors.Secondary,
                        )
                        details.frequencyMinutes?.let { freq ->
                            Text(
                                text = "(every $freq mins)",
                                style = MaterialTheme.typography.labelSmall,
                                color = LifeOsColors.OnSurfaceMuted,
                            )
                        }
                    }

                    AnimatedVisibility(visible = expandedStops) {
                        Column(
                            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            details.intermediateStops.forEachIndexed { i, stopName ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(LifeOsColors.OnSurfaceMuted))
                                    Text(
                                        text = stopName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = LifeOsColors.OnSurfaceMuted,
                                    )
                                }
                            }
                        }
                    }
                }

                // Interchange notes (e.g. Line transfer at Rajiv Chowk)
                details.interchangeNotes?.let { interchange ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(LifeOsColors.AccentSky.copy(alpha = 0.15f))
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.SwapCalls, contentDescription = null, modifier = Modifier.size(16.dp), tint = LifeOsColors.AccentSky)
                        Text(
                            text = interchange,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = LifeOsColors.Primary,
                        )
                    }
                }

                // Road route name
                details.routeName?.let { route ->
                    Text(
                        text = if (route.startsWith("Via", ignoreCase = true)) route else "Via $route",
                        style = MaterialTheme.typography.bodySmall,
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                }
            } else if (step.mode.lowercase() == "walk") {
                val distanceText = listOfNotNull(
                    step.distanceMeters?.let { "$it m" },
                    step.distanceKm?.let { "$it km" },
                ).firstOrNull()

                if (distanceText != null) {
                    Text(
                        text = "Walk distance: $distanceText",
                        style = MaterialTheme.typography.bodySmall,
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun InstructionCallout(instruction: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = LifeOsColors.AccentAmber.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Rounded.NearMe,
                contentDescription = null,
                tint = LifeOsColors.AccentAmber,
                modifier = Modifier.size(16.dp).padding(top = 1.dp),
            )
            Text(
                text = instruction,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun BadgeChip(
    text: String,
    backgroundColor: Color,
    textColor: Color,
    bold: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = backgroundColor,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            color = textColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun modeIcon(mode: String): ImageVector {
    return when (mode.trim().lowercase()) {
        "metro", "subway" -> Icons.Rounded.DirectionsSubway
        "train", "rail" -> Icons.Rounded.Train
        "car", "taxi", "rideshare", "auto_rickshaw" -> Icons.Rounded.DirectionsCar
        "bus" -> Icons.Rounded.DirectionsBus
        "flight" -> Icons.Rounded.Flight
        "walk" -> Icons.AutoMirrored.Rounded.DirectionsWalk
        else -> Icons.Rounded.Directions
    }
}

private fun parseLineColor(lineColor: String?, mode: String? = null): Color {
    if (!lineColor.isNullOrBlank()) {
        val clean = lineColor.trim().lowercase()
        return when {
            clean.startsWith("#") -> runCatching { Color(android.graphics.Color.parseColor(clean)) }.getOrDefault(LifeOsColors.Primary)
            "orange" in clean -> Color(0xFFF06A1A)
            "yellow" in clean -> Color(0xFFD49B00)
            "blue" in clean -> Color(0xFF1E75C4)
            "red" in clean -> Color(0xFFD9383A)
            "green" in clean -> Color(0xFF2E9447)
            "violet" in clean || "purple" in clean -> Color(0xFF7E3FB0)
            "magenta" in clean || "pink" in clean -> Color(0xFFD03588)
            "grey" in clean || "gray" in clean -> Color(0xFF7A8B99)
            "cyan" in clean || "aqua" in clean -> Color(0xFF00A8B5)
            else -> LifeOsColors.Primary
        }
    }
    return when (mode?.lowercase()?.trim()) {
        "metro", "subway" -> Color(0xFFF06A1A)
        "train", "rail" -> LifeOsColors.Primary
        "car", "taxi", "rideshare" -> Color(0xFF2E8B9E)
        "bus" -> Color(0xFF6B9B5E)
        "flight" -> Color(0xFF4B9BD3)
        "walk" -> LifeOsColors.OnSurfaceMuted
        else -> LifeOsColors.Primary
    }
}
