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

// Cool navy/blue identity — crisp off-white canvas, deep navigation chrome,
// and calm ocean accents. Semantic score colors stay distinct.
object LifeOsColors {
    val Background = Color(0xFFF4F7FB)     // cool off-white canvas
    val Surface = Color(0xFFFFFFFF)
    val SurfaceElevated = Color(0xFFFFFFFF)
    val SurfaceMuted = Color(0xFFE8EEF5)
    val SurfaceBorder = Color(0xFFD7E0EA)

    val Primary = Color(0xFF175D9C)        // confident ocean blue
    val PrimarySoft = Color(0xFFDCEBFA)
    val OnPrimary = Color(0xFFFFFFFF)

    val Secondary = Color(0xFF2F7EA1)      // blue-teal
    val SecondarySoft = Color(0xFFDCEEF5)

    val AccentTeal = Color(0xFF2E8B9E)
    val AccentSky = Color(0xFF4B9BD3)
    val AccentAmber = Color(0xFFC99A3C)

    val OnBackground = Color(0xFF172B3A)   // navy ink
    val OnSurfaceMuted = Color(0xFF5D7083)

    val NavigationSurface = Color(0xFF102A43)
    val NavigationIndicator = Color(0xFF214D75)
    val NavigationSelected = Color(0xFFF7FBFF)
    val NavigationUnselected = Color(0xFFA9BDCF)

    val ScoreHigh = Color(0xFF6B9B5E)      // moss
    val ScoreMid = Color(0xFFC99A3C)       // ochre
    val ScoreLow = Color(0xFFC24E4E)       // clay red
    val Warning = Color(0xFFC76E3B)        // burnt orange
    val Positive = Color(0xFF4A7C59)       // forest

    // Per-metric identity colors — distinct hues, desaturated to sit calmly on ivory.
    val MetricSleep = Color(0xFF5B7DB1)     // dusk blue (night/rest)
    val MetricRecovery = Color(0xFF5FA396)  // sea teal
    val MetricExecution = Color(0xFF8B78C6) // violet (drive/output)
    val MetricWellness = Color(0xFFC05F86)  // rose clay
    val MetricFitness = Color(0xFF6B9B5E)   // moss

    // Charting — gridlines and faint axis text on the trend graph.
    val ChartGrid = Color(0xFFE1E8F0)
    val ChartAxisText = Color(0xFF8191A2)
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
