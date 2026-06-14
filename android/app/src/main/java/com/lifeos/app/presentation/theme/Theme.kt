package com.lifeos.app.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF5BC0BE)
private val DeepNavy = Color(0xFF0B132B)
private val Indigo = Color(0xFF3A506B)
private val Amber = Color(0xFFFFB703)

private val DarkColors = darkColorScheme(
    primary = Teal,
    onPrimary = DeepNavy,
    secondary = Amber,
    background = DeepNavy,
    surface = Color(0xFF1C2541),
    onSurface = Color(0xFFE6E8EC),
    tertiary = Indigo,
)

private val LightColors = lightColorScheme(
    primary = Indigo,
    secondary = Amber,
    tertiary = Teal,
)

private val LifeOsType = Typography()

@Composable
fun LifeOsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = LifeOsType,
        content = content,
    )
}
