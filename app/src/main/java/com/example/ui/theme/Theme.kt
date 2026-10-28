package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CyberRadarColorScheme =
  darkColorScheme(
    primary = RadarCyan,
    onPrimary = Color(0xFF00262B),
    primaryContainer = Color(0xFF00424C),
    onPrimaryContainer = Color(0xFF9CF2FF),
    secondary = RadarOrange,
    onSecondary = Color(0xFF2D1000),
    secondaryContainer = Color(0xFF4D1F00),
    onSecondaryContainer = Color(0xFFFFDBC8),
    tertiary = RadarEmerald,
    onTertiary = Color(0xFF002911),
    background = RadarBgDeep,
    onBackground = RadarTextPrimary,
    surface = RadarBgSurface,
    onSurface = RadarTextPrimary,
    surfaceVariant = RadarBgCard,
    onSurfaceVariant = RadarTextSecondary,
    outline = RadarBorder,
    error = RadarCrimson,
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  MaterialTheme(colorScheme = CyberRadarColorScheme, typography = Typography, content = content)
}
