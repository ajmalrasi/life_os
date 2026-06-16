package com.lifeos.app.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Fresh light palette — soft mint canvas, emerald→teal identity, vivid but
// restrained metric accents (Tailwind-derived). One cohesive design language.
object LifeOsColors {
    val Background = Color(0xFFF4F8F6)     // soft mint-tinted canvas
    val Surface = Color(0xFFFFFFFF)
    val SurfaceElevated = Color(0xFFFFFFFF)
    val SurfaceMuted = Color(0xFFE8F3EE)   // pale mint
    val SurfaceBorder = Color(0xFFDCE9E3)

    val Primary = Color(0xFF059669)        // emerald-600 (accessible on white)
    val PrimarySoft = Color(0xFFD1FAE5)    // emerald-100
    val OnPrimary = Color(0xFFFFFFFF)

    val Secondary = Color(0xFF0D9488)      // teal-600
    val SecondarySoft = Color(0xFFCCFBF1)  // teal-100

    val AccentTeal = Color(0xFF14B8A6)
    val AccentCoral = Color(0xFFFB7185)
    val AccentAmber = Color(0xFFF59E0B)

    val OnBackground = Color(0xFF0F1F1A)   // near-ink, green-tinted
    val OnSurfaceMuted = Color(0xFF5C6B64)

    val ScoreHigh = Color(0xFF10B981)      // emerald
    val ScoreMid = Color(0xFFF59E0B)       // amber
    val ScoreLow = Color(0xFFEF4444)       // red
    val Warning = Color(0xFFF97316)        // orange
    val Positive = Color(0xFF0D9488)

    // Per-metric identity colors — distinct hues, no purple, harmonized with the green identity.
    val MetricSleep = Color(0xFF3B82F6)     // blue (night/rest)
    val MetricRecovery = Color(0xFF14B8A6)  // teal
    val MetricExecution = Color(0xFFF59E0B) // amber (drive/output)
    val MetricWellness = Color(0xFFF43F5E)  // rose
    val MetricFitness = Color(0xFF10B981)   // emerald

    // Charting — gridlines and faint axis text on the trend graph.
    val ChartGrid = Color(0xFFE6EFEA)
    val ChartAxisText = Color(0xFF8A9A93)
}

val LifeOsShapes = androidx.compose.material3.Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val LightScheme = lightColorScheme(
    primary = LifeOsColors.Primary,
    onPrimary = LifeOsColors.OnPrimary,
    primaryContainer = LifeOsColors.PrimarySoft,
    onPrimaryContainer = LifeOsColors.Primary,
    secondary = LifeOsColors.Secondary,
    onSecondary = Color.White,
    secondaryContainer = LifeOsColors.SecondarySoft,
    onSecondaryContainer = LifeOsColors.Secondary,
    background = LifeOsColors.Background,
    onBackground = LifeOsColors.OnBackground,
    surface = LifeOsColors.Surface,
    onSurface = LifeOsColors.OnBackground,
    surfaceVariant = LifeOsColors.SurfaceMuted,
    onSurfaceVariant = LifeOsColors.OnSurfaceMuted,
    outline = LifeOsColors.SurfaceBorder,
    tertiary = LifeOsColors.AccentTeal,
)

private val LifeOsTypography = Typography(
    headlineLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = LifeOsColors.OnBackground),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
    displaySmall = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
)

@Composable
fun LifeOsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightScheme,
        typography = LifeOsTypography,
        shapes = LifeOsShapes,
        content = content,
    )
}
