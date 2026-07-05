package com.lifeos.app.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Claude-inspired warm palette — ivory canvas, terracotta identity, earthy
// muted accents. Calm, book-like, never loud. One cohesive design language.
object LifeOsColors {
    val Background = Color(0xFFFAF9F5)     // warm ivory canvas
    val Surface = Color(0xFFFFFFFF)
    val SurfaceElevated = Color(0xFFFFFFFF)
    val SurfaceMuted = Color(0xFFF0EEE6)   // pampas / warm paper
    val SurfaceBorder = Color(0xFFE5E2D9)  // hairline on ivory

    val Primary = Color(0xFFC15F3C)        // terracotta (accessible on white)
    val PrimarySoft = Color(0xFFF6E3DA)    // terracotta wash
    val OnPrimary = Color(0xFFFFFFFF)

    val Secondary = Color(0xFF6E7B5E)      // sage
    val SecondarySoft = Color(0xFFEAEDE2)  // sage wash

    val AccentTeal = Color(0xFF5FA396)
    val AccentCoral = Color(0xFFD97757)    // Claude coral, for soft emphasis
    val AccentAmber = Color(0xFFC99A3C)

    val OnBackground = Color(0xFF29261F)   // warm ink
    val OnSurfaceMuted = Color(0xFF6E6A5E) // warm gray

    val ScoreHigh = Color(0xFF6B9B5E)      // moss
    val ScoreMid = Color(0xFFC99A3C)       // ochre
    val ScoreLow = Color(0xFFC24E4E)       // clay red
    val Warning = Color(0xFFC76E3B)        // burnt orange
    val Positive = Color(0xFF4A7C59)       // forest

    // Per-metric identity colors — distinct hues, desaturated to sit calmly on ivory.
    val MetricSleep = Color(0xFF5B7DB1)     // dusk blue (night/rest)
    val MetricRecovery = Color(0xFF5FA396)  // sea teal
    val MetricExecution = Color(0xFFC99A3C) // ochre (drive/output)
    val MetricWellness = Color(0xFFC05F86)  // rose clay
    val MetricFitness = Color(0xFF6B9B5E)   // moss

    // Charting — gridlines and faint axis text on the trend graph.
    val ChartGrid = Color(0xFFEAE7DE)
    val ChartAxisText = Color(0xFF9B968A)
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

// Serif for display + headlines (Claude's editorial voice); clean sans for the rest.
private val LifeOsTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = LifeOsColors.OnBackground),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
    displaySmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
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
