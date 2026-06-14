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

// Soft light palette — airy backgrounds, friendly blue/violet accents.
object LifeOsColors {
    val Background = Color(0xFFF5F7FC)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceElevated = Color(0xFFFFFFFF)
    val SurfaceMuted = Color(0xFFEEF2FF)
    val SurfaceBorder = Color(0xFFE2E8F0)

    val Primary = Color(0xFF4F6BF6)
    val PrimarySoft = Color(0xFFDBE4FF)
    val OnPrimary = Color(0xFFFFFFFF)

    val Secondary = Color(0xFF8B5CF6)
    val SecondarySoft = Color(0xFFEDE9FE)

    val AccentTeal = Color(0xFF14B8A6)
    val AccentCoral = Color(0xFFFB7185)
    val AccentAmber = Color(0xFFF59E0B)

    val OnBackground = Color(0xFF1E293B)
    val OnSurfaceMuted = Color(0xFF64748B)

    val ScoreHigh = Color(0xFF059669)
    val ScoreMid = Color(0xFFD97706)
    val ScoreLow = Color(0xFFDC2626)
    val Warning = Color(0xFFEA580C)
    val Positive = Color(0xFF0D9488)
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
