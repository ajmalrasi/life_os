package com.lifeos.app.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Cool navy/blue identity: crisp off-white canvas, deep navigation chrome,
// and calm ocean accents. Semantic score colors stay distinct.
object LifeOsColors {
    var Background = Color(0xFFF4F7FB); private set
    var Surface = Color(0xFFFFFFFF); private set
    var SurfaceElevated = Color(0xFFFFFFFF); private set
    var SurfaceMuted = Color(0xFFE8EEF5); private set
    var SurfaceBorder = Color(0xFFD7E0EA); private set

    var Primary = Color(0xFF175D9C); private set
    var PrimarySoft = Color(0xFFDCEBFA); private set
    var OnPrimary = Color(0xFFFFFFFF); private set

    var Secondary = Color(0xFF2F7EA1); private set
    var SecondarySoft = Color(0xFFDCEEF5); private set

    val AccentTeal = Color(0xFF2E8B9E)
    val AccentSky = Color(0xFF4B9BD3)
    val AccentAmber = Color(0xFFC99A3C)

    var OnBackground = Color(0xFF172B3A); private set
    var OnSurfaceMuted = Color(0xFF5D7083); private set

    var NavigationSurface = Color(0xFFD7E5F0); private set
    var NavigationIndicator = Color(0xFF2F76A5); private set
    var NavigationSelected = Color(0xFF153B5A); private set
    var NavigationUnselected = Color(0xFF62798C); private set

    val ScoreHigh = Color(0xFF6B9B5E)      // moss
    val ScoreMid = Color(0xFFC99A3C)       // ochre
    val ScoreLow = Color(0xFFC24E4E)       // clay red
    val Warning = Color(0xFFC76E3B)        // burnt orange
    val Positive = Color(0xFF4A7C59)       // forest

    // Per-metric identity colors: distinct hues, desaturated to sit calmly on ivory.
    val MetricSleep = Color(0xFF5B7DB1)     // dusk blue (night/rest)
    val MetricRecovery = Color(0xFF5FA396)  // sea teal
    val MetricExecution = Color(0xFF8B78C6) // violet (drive/output)
    val MetricWellness = Color(0xFFC05F86)  // rose clay
    val MetricFitness = Color(0xFF6B9B5E)   // moss

    // Charting: gridlines and faint axis text on the trend graph.
    var ChartGrid = Color(0xFFE1E8F0); private set
    var ChartAxisText = Color(0xFF8191A2); private set

    fun applySystemMode(dark: Boolean) {
        if (dark) {
            Background = Color(0xFF0E1620)
            Surface = Color(0xFF16212C)
            SurfaceElevated = Color(0xFF1B2835)
            SurfaceMuted = Color(0xFF202E3B)
            SurfaceBorder = Color(0xFF314252)
            Primary = Color(0xFF6EB4F0)
            PrimarySoft = Color(0xFF173754)
            OnPrimary = Color(0xFF07131D)
            Secondary = Color(0xFF74C1D8)
            SecondarySoft = Color(0xFF173B46)
            OnBackground = Color(0xFFE4EDF5)
            OnSurfaceMuted = Color(0xFFA3B3C2)
            NavigationSurface = Color(0xFF111D28)
            NavigationIndicator = Color(0xFF67B2E6)
            NavigationSelected = Color(0xFFD5E9F8)
            NavigationUnselected = Color(0xFF8398AA)
            ChartGrid = Color(0xFF2A3947)
            ChartAxisText = Color(0xFF8EA1B3)
        } else {
            Background = Color(0xFFF4F7FB)
            Surface = Color(0xFFFFFFFF)
            SurfaceElevated = Color(0xFFFFFFFF)
            SurfaceMuted = Color(0xFFE8EEF5)
            SurfaceBorder = Color(0xFFD7E0EA)
            Primary = Color(0xFF175D9C)
            PrimarySoft = Color(0xFFDCEBFA)
            OnPrimary = Color(0xFFFFFFFF)
            Secondary = Color(0xFF2F7EA1)
            SecondarySoft = Color(0xFFDCEEF5)
            OnBackground = Color(0xFF172B3A)
            OnSurfaceMuted = Color(0xFF5D7083)
            NavigationSurface = Color(0xFFD7E5F0)
            NavigationIndicator = Color(0xFF2F76A5)
            NavigationSelected = Color(0xFF153B5A)
            NavigationUnselected = Color(0xFF62798C)
            ChartGrid = Color(0xFFE1E8F0)
            ChartAxisText = Color(0xFF8191A2)
        }
    }
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

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF6EB4F0),
    onPrimary = Color(0xFF07131D),
    primaryContainer = Color(0xFF173754),
    onPrimaryContainer = Color(0xFFD5E9F8),
    secondary = Color(0xFF74C1D8),
    onSecondary = Color(0xFF07131D),
    secondaryContainer = Color(0xFF173B46),
    onSecondaryContainer = Color(0xFFD9F2F7),
    background = Color(0xFF0E1620),
    onBackground = Color(0xFFE4EDF5),
    surface = Color(0xFF16212C),
    onSurface = Color(0xFFE4EDF5),
    surfaceVariant = Color(0xFF202E3B),
    onSurfaceVariant = Color(0xFFA3B3C2),
    outline = Color(0xFF314252),
    tertiary = Color(0xFF66C3D2),
)

// Serif for display + headlines (Claude's editorial voice); clean sans for the rest.
private val LifeOsTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 22.sp, fontWeight = FontWeight.Bold),
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
    val darkTheme = isSystemInDarkTheme()
    LifeOsColors.applySystemMode(darkTheme)
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = LifeOsTypography,
        shapes = LifeOsShapes,
        content = content,
    )
}
