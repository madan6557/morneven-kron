package com.morneven.kron.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.morneven.kron.R

val KronRed = Color(0xFFB94D59)
val KronRedDark = Color(0xFF7C2D37)
val KronGold = Color(0xFFE2BD4D)
val KronPurple = Color(0xFF72517F)
val KronGreen = Color(0xFF57B58C)
val KronBlue = Color(0xFF65A9EF)
val KronBackground = Color(0xFF120D0F)
val KronSurface = Color(0xFF1C1518)
val KronSurfaceHigh = Color(0xFF281E22)
val KronText = Color(0xFFF2E9E5)
val KronMuted = Color(0xFFB7AAA8)

private val DarkColors = darkColorScheme(
    primary = KronRed,
    onPrimary = Color.White,
    primaryContainer = KronRedDark,
    onPrimaryContainer = Color(0xFFFFDADF),
    secondary = KronPurple,
    tertiary = KronGold,
    background = KronBackground,
    onBackground = KronText,
    surface = KronSurface,
    onSurface = KronText,
    surfaceVariant = KronSurfaceHigh,
    onSurfaceVariant = KronMuted,
    error = Color(0xFFFF796E),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF983744),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9DD),
    secondary = Color(0xFF6D4D78),
    tertiary = Color(0xFF826C10),
    background = Color(0xFFF8F4F2),
    onBackground = Color(0xFF241B1D),
    surface = Color(0xFFFFFBFA),
    onSurface = Color(0xFF241B1D),
    surfaceVariant = Color(0xFFEDE3E3),
    onSurfaceVariant = Color(0xFF62575A),
    error = Color(0xFFBA1A1A),
)

private val Orbitron = FontFamily(Font(R.font.orbitron))
private val Rajdhani = FontFamily(Font(R.font.rajdhani_medium, weight = FontWeight.Medium))
private val Inter = FontFamily(Font(R.font.inter))

private val KronTypography = androidx.compose.material3.Typography(
    displaySmall = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = 1.sp),
    headlineMedium = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = 0.5.sp),
    titleLarge = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, letterSpacing = 0.4.sp),
    titleMedium = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, letterSpacing = 0.2.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.8.sp),
)

@Composable
fun KronTheme(theme: String, content: @Composable () -> Unit) {
    val dark = when (theme) {
        "LIGHT" -> false
        "SYSTEM" -> isSystemInDarkTheme()
        else -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = KronTypography,
        content = content,
    )
}
