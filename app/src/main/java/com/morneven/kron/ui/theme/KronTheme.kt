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
val KronBackground = Color(0xFF100B0D)
val KronSurface = Color(0xFF1E1519)
val KronSurfaceHigh = Color(0xFF281C22)
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
val KronGoldLight = Color(0xFFA87B05)
val KronGreenDark = Color(0xFF57B58C)
val KronGreenLight = Color(0xFF0D8A52)
val KronBlueDark = Color(0xFF65A9EF)
val KronBlueLight = Color(0xFF1565C0)
private val KronNegativeDark = Color(0xFFFF796E)
private val KronNegativeLight = Color(0xFFD32F2F)

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
// actually reads is spelled out here so none of that purple reaches a maroon and gold brand.
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
    surfaceContainerLowest = Color(0xFF0C0809),
    surfaceContainerLow = Color(0xFF160E12),
    surfaceContainer = Color(0xFF1E1519),
    surfaceContainerHigh = Color(0xFF281C22),
    surfaceContainerHighest = Color(0xFF33252B),
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

// The Swiss Executive Alabaster light palette for high-end personal wealth management.
// Clean pure surfaces, deep imperial bordeaux, authentic royal gold, vibrant emerald, and crisp contrast.
private val LightColors = lightColorScheme(
    primary = Color(0xFF9B1D31),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFCEBED),
    onPrimaryContainer = Color(0xFF4A0A17),
    inversePrimary = Color(0xFFFFB3BC),
    secondary = Color(0xFF5E4E63),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1EBF3),
    onSecondaryContainer = Color(0xFF26182B),
    tertiary = Color(0xFF9E6F05),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF3D6),
    onTertiaryContainer = Color(0xFF332000),
    background = Color(0xFFEBF0F5),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF5F6B7A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEDF2F7),
    surfaceContainerHighest = Color(0xFFE2E8F0),
    surfaceTint = Color(0xFF9B1D31),
    inverseSurface = Color(0xFF1E232B),
    inverseOnSurface = Color(0xFFF1F4F8),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFCBD5E1),
    error = Color(0xFFD32F2F),
    onError = Color.White,
    errorContainer = Color(0xFFFEECEB),
    onErrorContainer = Color(0xFF4C0805),
    scrim = Color.Black,
)

private val Orbitron = FontFamily(Font(R.font.orbitron))
private val Rajdhani = FontFamily(Font(R.font.rajdhani_medium, weight = FontWeight.Medium))
private val Inter = FontFamily(Font(R.font.inter))

private val KronTypography = androidx.compose.material3.Typography(
    displayLarge = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = 0.5.sp),
    displayMedium = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = 0.5.sp),
    displaySmall = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = 0.4.sp),
    headlineLarge = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 0.3.sp),
    headlineMedium = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 0.3.sp),
    headlineSmall = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, letterSpacing = 0.2.sp),
    titleLarge = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, letterSpacing = 0.2.sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.5.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontSize = 13.5.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.4.sp),
    labelMedium = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.5.sp),
)

val KronButtonShape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
val KronFieldShape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
val KronCardShape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
val KronSubCardShape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
val KronChipShape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
val KronPillShape = androidx.compose.foundation.shape.RoundedCornerShape(50)

val KronShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
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
