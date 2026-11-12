package com.grabbly.downloader.ui.theme

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
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.grabbly.downloader.R

/** Brand colours, from the logo: teal G on dark navy. */
object Brand {
    val Navy900 = Color(0xFF0B1A33)
    val Navy850 = Color(0xFF0D1F3C)
    val Navy800 = Color(0xFF112645)
    val Navy700 = Color(0xFF18325A)
    val Teal50 = Color(0xFFE7F8F6)
    val Teal100 = Color(0xFFC9EFEA)
    val Teal300 = Color(0xFF5FD3C6)
    val Teal400 = Color(0xFF2CC3B3)
    val Teal500 = Color(0xFF10B3A3)
    val Teal600 = Color(0xFF0E9A8D)
    val Teal700 = Color(0xFF0B7C72)
}

/** Colours Material doesn't have a slot for. */
@Immutable
data class GrabblyColors(
    val muted: Color,
    val success: Color,
    val accentSoft: Color,
    val onAccentSoft: Color,
    val track: Color,
)

private val LightExtras = GrabblyColors(
    muted = Color(0xFF5D6A7E),
    success = Color(0xFF0E7C55),
    accentSoft = Brand.Teal50,
    onAccentSoft = Brand.Teal700,
    track = Color(0xFFE3E8EE),
)

private val DarkExtras = GrabblyColors(
    muted = Color(0xFF8B9CB4),
    success = Brand.Teal300,
    accentSoft = Color(0x1A10B3A3),
    onAccentSoft = Brand.Teal300,
    track = Color(0x1AFFFFFF),
)

private val LightScheme = lightColorScheme(
    primary = Brand.Teal500,
    onPrimary = Brand.Navy900,
    primaryContainer = Brand.Teal50,
    onPrimaryContainer = Brand.Teal700,
    secondary = Brand.Navy800,
    onSecondary = Color.White,
    background = Color(0xFFF4F6F8),
    onBackground = Color(0xFF0F1B2D),
    surface = Color.White,
    onSurface = Color(0xFF0F1B2D),
    surfaceVariant = Color(0xFFE9EEF3),
    onSurfaceVariant = Color(0xFF5D6A7E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFF0F3F6),
    outline = Color(0xFFD8DEE6),
    outlineVariant = Color(0xFFE6EAF0),
    error = Color(0xFFC62F36),
    onError = Color.White,
)

private val DarkScheme = darkColorScheme(
    primary = Brand.Teal500,
    onPrimary = Brand.Navy900,
    primaryContainer = Color(0xFF10333F),
    onPrimaryContainer = Brand.Teal300,
    secondary = Brand.Teal300,
    onSecondary = Brand.Navy900,
    background = Brand.Navy900,
    onBackground = Color(0xFFF1F6FB),
    surface = Brand.Navy800,
    onSurface = Color(0xFFF1F6FB),
    surfaceVariant = Brand.Navy700,
    onSurfaceVariant = Color(0xFFB9C6D6),
    surfaceContainerLowest = Brand.Navy900,
    surfaceContainerLow = Brand.Navy850,
    surfaceContainer = Brand.Navy800,
    surfaceContainerHigh = Brand.Navy800,
    surfaceContainerHighest = Brand.Navy700,
    outline = Color(0xFF2A4166),
    outlineVariant = Color(0xFF1E3556),
    error = Color(0xFFFFA3A3),
    onError = Brand.Navy900,
)

val Outfit = FontFamily(
    Font(R.font.outfit_regular, FontWeight.Normal),
    Font(R.font.outfit_medium, FontWeight.Medium),
    Font(R.font.outfit_semibold, FontWeight.SemiBold),
    Font(R.font.outfit_bold, FontWeight.Bold),
)

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

private val Base = Typography()

private val GrabblyTypography = Typography(
    displaySmall = Base.displaySmall.copy(fontFamily = Outfit, fontWeight = FontWeight.Bold),
    headlineLarge = Base.headlineLarge.copy(fontFamily = Outfit, fontWeight = FontWeight.Bold),
    headlineMedium = Base.headlineMedium.copy(fontFamily = Outfit, fontWeight = FontWeight.Bold),
    headlineSmall = Base.headlineSmall.copy(fontFamily = Outfit, fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontFamily = Outfit, fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontFamily = Outfit, fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontFamily = Outfit, fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.copy(fontFamily = Inter),
    bodyMedium = Base.bodyMedium.copy(fontFamily = Inter),
    bodySmall = Base.bodySmall.copy(fontFamily = Inter),
    labelLarge = Base.labelLarge.copy(fontFamily = Outfit, fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
    labelSmall = Base.labelSmall.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
)

private val GrabblyShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val LocalGrabblyColors = staticCompositionLocalOf { LightExtras }

/** Grabbly's extra colours: `MaterialTheme.grabbly.muted` and so on. */
val MaterialTheme.grabbly: GrabblyColors
    @Composable
    @ReadOnlyComposable
    get() = LocalGrabblyColors.current

@Composable
fun GrabblyTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGrabblyColors provides if (darkTheme) DarkExtras else LightExtras) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = GrabblyTypography,
            shapes = GrabblyShapes,
            content = content,
        )
    }
}
