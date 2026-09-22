package dev.holo795.crashsleuth.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours with a meaning, beyond Material's roles: how serious a finding is, how a launch ended. */
@Immutable
data class Tones(
    val critical: Color,
    val criticalSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val success: Color,
    val successSoft: Color,
    val info: Color,
    val infoSoft: Color,
    val muted: Color,
    val line: Color,
    val raised: Color,
    val code: Color,
)

private val DarkTones = Tones(
    critical = Color(0xFFEF6461), criticalSoft = Color(0x1FEF6461),
    warning = Color(0xFFE8B84A), warningSoft = Color(0x1FE8B84A),
    success = Color(0xFF52B788), successSoft = Color(0x1F52B788),
    info = Color(0xFF6E9EFF), infoSoft = Color(0x1F6E9EFF),
    muted = Color(0xFF8B8F97), line = Color(0xFF1F2024), raised = Color(0xFF17181B), code = Color(0xFF111214),
)

private val LightTones = Tones(
    critical = Color(0xFFD9443F), criticalSoft = Color(0x14D9443F),
    warning = Color(0xFFB7831A), warningSoft = Color(0x14B7831A),
    success = Color(0xFF2E9463), successSoft = Color(0x142E9463),
    info = Color(0xFF3A74E0), infoSoft = Color(0x143A74E0),
    muted = Color(0xFF6D7079), line = Color(0xFFE9EAED), raised = Color(0xFFF3F3F5), code = Color(0xFFF5F5F7),
)

val LocalTones = staticCompositionLocalOf { DarkTones }

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8F8CF2), onPrimary = Color(0xFF0E0D1F),
    primaryContainer = Color(0xFF1E1D33), onPrimaryContainer = Color(0xFFDAD9FF),
    secondary = Color(0xFF6E9EFF), onSecondary = Color(0xFF0B1A2E),
    background = Color(0xFF0C0D0F), onBackground = Color(0xFFEDEDEF),
    surface = Color(0xFF0C0D0F), onSurface = Color(0xFFEDEDEF),
    surfaceVariant = Color(0xFF17181B), onSurfaceVariant = Color(0xFFB9BCC3),
    outline = Color(0xFF26272C), outlineVariant = Color(0xFF1F2024),
    error = Color(0xFFEF6461),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B57E3), onPrimary = Color.White,
    primaryContainer = Color(0xFFEEEDFD), onPrimaryContainer = Color(0xFF211E66),
    secondary = Color(0xFF3A74E0), onSecondary = Color.White,
    background = Color(0xFFFCFCFD), onBackground = Color(0xFF1B1C20),
    surface = Color(0xFFFCFCFD), onSurface = Color(0xFF1B1C20),
    surfaceVariant = Color(0xFFF3F3F5), onSurfaceVariant = Color(0xFF45474E),
    outline = Color(0xFFDEDFE3), outlineVariant = Color(0xFFE9EAED),
    error = Color(0xFFD9443F),
)

private val Mono = FontFamily.Monospace

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

val CodeStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 18.sp)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(22.dp),
)

@Composable
fun CrashSleuthTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTones provides if (dark) DarkTones else LightTones) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

object Theme {
    val tones: Tones @Composable get() = LocalTones.current
}
