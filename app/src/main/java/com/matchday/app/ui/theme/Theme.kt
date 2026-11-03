package com.matchday.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Accent {
    val Blue = Color(0xFF4F8CFF)
    val Purple = Color(0xFF9B6BFF)
    val Green = Color(0xFF2ECC8F)
    val Orange = Color(0xFFFF9F43)
    val Red = Color(0xFFFF5C6C)
    val Live = Color(0xFFFF4D5E)
}

private val DarkColors = darkColorScheme(
    primary = Accent.Blue,
    onPrimary = Color.White,
    secondary = Accent.Purple,
    onSecondary = Color.White,
    tertiary = Accent.Green,
    background = Color(0xFF0B0F1A),
    onBackground = Color(0xFFE8ECF6),
    surface = Color(0xFF141A2A),
    onSurface = Color(0xFFE8ECF6),
    surfaceVariant = Color(0xFF1C2438),
    onSurfaceVariant = Color(0xFF9AA4BC),
    surfaceContainer = Color(0xFF161D2F),
    surfaceContainerHigh = Color(0xFF1C2438),
    outline = Color(0xFF2B3550),
    outlineVariant = Color(0xFF232C44),
    error = Accent.Red,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6BE0),
    secondary = Color(0xFF7A4FE0),
    tertiary = Color(0xFF16A06E),
    background = Color(0xFFF4F6FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFE8ECF6),
    onSurfaceVariant = Color(0xFF55607A),
    outline = Color(0xFFCBD3E4),
    error = Color(0xFFD93B4B),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

@Composable
fun MatchdayTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
