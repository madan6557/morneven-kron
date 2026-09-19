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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morneven.kron.R

val KronRed = Color(0xFFB94D59)
val KronRedDark = Color(0xFF7C2D37)
val KronPurple = Color(0xFF72517F)
val KronBackground = Color(0xFF120D0F)
val KronSurface = Color(0xFF1C1518)
val KronSurfaceHigh = Color(0xFF281E22)
val KronText = Color(0xFFF2E9E5)
val KronMuted = Color(0xFFB7AAA8)

/**
 * Brand accents, resolved per theme.
 *
 * The dark values are the original ones and define the look. Their light counterparts are the same
 * hues darkened until they carry at least 4.5:1 against a light surface: the dark tones sit at
 * roughly 2:1 on white, which is what made the light theme look washed out, since these carry the
 * channel badges, the signed money figures and every section heading.
 */
val KronGoldDark = Color(0xFFE2BD4D)
val KronGoldLight = Color(0xFF8A6A12)
val KronGreenDark = Color(0xFF57B58C)
val KronGreenLight = Color(0xFF1F7A55)
val KronBlueDark = Color(0xFF65A9EF)
val KronBlueLight = Color(0xFF1F6FC4)
private val KronNegativeDark = Color(0xFFFF796E)
private val KronNegativeLight = Color(0xFFB3261E)

/** True while the KRON theme is painting its dark palette. */
val LocalKronDarkTheme = androidx.compose.runtime.staticCompositionLocalOf { true }

/** Cash channel accent. */
val KronGold: Color
    @Composable get() = if (LocalKronDarkTheme.current) KronGoldDark else KronGoldLight

/** Positive money and healthy state accent. */
val KronGreen: Color
    @Composable get() = if (LocalKronDarkTheme.current) KronGreenDark else KronGreenLight

/** eBudget channel accent. */
val KronBlue: Color
    @Composable get() = if (LocalKronDarkTheme.current) KronBlueDark else KronBlueLight

/** Negative money accent, kept separate from colorScheme.error so failures still stand out. */
val KronNegative: Color
    @Composable get() = if (LocalKronDarkTheme.current) KronNegativeDark else KronNegativeLight

// Roles left unset fall back to the Material baseline, which is a purple family. Every role the app
// actually reads is spelled out here so none of that purple reaches a maroon and gold brand. The
// values that were already defined are unchanged.
private val DarkColors = darkColorScheme(
    primary = KronRed,
    onPrimary = Color.White,
    primaryContainer = KronRedDark,
    onPrimaryContainer = Color(0xFFFFDADF),
    inversePrimary = Color(0xFF8C2F3C),
    secondary = KronPurple,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF3F2B48),
    onSecondaryContainer = Color(0xFFEDDCF4),
    tertiary = KronGoldDark,
    onTertiary = Color(0xFF3A2D00),
    tertiaryContainer = Color(0xFF574200),
    onTertiaryContainer = Color(0xFFFBE6A8),
    background = KronBackground,
    onBackground = KronText,
    surface = KronSurface,
    onSurface = KronText,
    surfaceVariant = KronSurfaceHigh,
    onSurfaceVariant = KronMuted,
    surfaceTint = KronRed,
    inverseSurface = KronText,
    inverseOnSurface = Color(0xFF241B1D),
    outline = Color(0xFF9C8B8E),
    outlineVariant = Color(0xFF433539),
    error = Color(0xFFFF796E),
    onError = Color(0xFF5F1412),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black,
)

// The same maroon, purple and gold family as the dark palette, re-tuned for a light surface rather
// than half-specified. Text bearing roles clear 4.5:1 against surface.
private val LightColors = lightColorScheme(
    primary = Color(0xFF8C2F3C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9DD),
    onPrimaryContainer = Color(0xFF3B0710),
    inversePrimary = Color(0xFFFFB3BC),
    secondary = Color(0xFF6D4D78),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0DBF6),
    onSecondaryContainer = Color(0xFF2A0E33),
    tertiary = Color(0xFF7A5E0B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFBE6A8),
    onTertiaryContainer = Color(0xFF271C00),
    background = Color(0xFFF7F3F1),
    onBackground = Color(0xFF221A1C),
    surface = Color(0xFFFFFBF9),
    onSurface = Color(0xFF221A1C),
    surfaceVariant = Color(0xFFEFE2E3),
    onSurfaceVariant = Color(0xFF5A4D50),
    surfaceTint = Color(0xFF8C2F3C),
    inverseSurface = Color(0xFF37292C),
    inverseOnSurface = Color(0xFFFBEEF0),
    outline = Color(0xFF8C7C7F),
    outlineVariant = Color(0xFFDCC9CC),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    scrim = Color.Black,
)

private val Orbitron = FontFamily(Font(R.font.orbitron))
private val Rajdhani = FontFamily(Font(R.font.rajdhani_medium, weight = FontWeight.Medium))
private val Inter = FontFamily(Font(R.font.inter))

private val KronTypography = androidx.compose.material3.Typography(
    displayLarge = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = 0.5.sp),
    displayMedium = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = 0.5.sp),
    displaySmall = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, letterSpacing = 0.4.sp),
    headlineLarge = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 0.4.sp),
    headlineMedium = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = 0.3.sp),
    headlineSmall = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, letterSpacing = 0.2.sp),
    titleLarge = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, letterSpacing = 0.3.sp),
    titleMedium = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = 0.2.sp),
    titleSmall = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.2.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontSize = 13.5.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.4.sp),
    labelMedium = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.5.sp),
)

val KronButtonShape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
val KronFieldShape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
val KronCardShape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
val KronChipShape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)

val KronShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
)

@Composable
fun KronTheme(theme: String, content: @Composable () -> Unit) {
    val dark = when (theme) {
        "LIGHT" -> false
        "SYSTEM" -> isSystemInDarkTheme()
        else -> true
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalKronDarkTheme provides dark) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = KronTypography,
            shapes = KronShapes,
            content = content,
        )
    }
}
