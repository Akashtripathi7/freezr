package com.freezr.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ice = Color(0xFF7DD3FC)
private val IceDeep = Color(0xFF0369A1)
private val Glacier = Color(0xFFA5B4FC)
private val Indigo = Color(0xFF4F46E5)
private val Mint = Color(0xFF5EEAD4)
private val Teal = Color(0xFF0D9488)

private val DarkColors = darkColorScheme(
    primary = Ice,
    onPrimary = Color(0xFF00293D),
    primaryContainer = Color(0xFF0C4A6E),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = Glacier,
    onSecondary = Color(0xFF1E1B4B),
    secondaryContainer = Color(0xFF312E81),
    onSecondaryContainer = Color(0xFFE0E7FF),
    tertiary = Mint,
    onTertiary = Color(0xFF042F2E),
    tertiaryContainer = Color(0xFF134E4A),
    onTertiaryContainer = Color(0xFFCCFBF1),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF0B1220),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1A2438),
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceContainerLowest = Color(0xFF070C16),
    surfaceContainerLow = Color(0xFF101828),
    surfaceContainer = Color(0xFF131C2E),
    surfaceContainerHigh = Color(0xFF1A2438),
    surfaceContainerHighest = Color(0xFF223049),
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF1E293B),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
)

private val LightColors = lightColorScheme(
    primary = IceDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0C4A6E),
    secondary = Indigo,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E7FF),
    onSecondaryContainer = Color(0xFF312E81),
    tertiary = Teal,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCCFBF1),
    onTertiaryContainer = Color(0xFF134E4A),
    background = Color(0xFFF4F8FC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF4F8FC),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEFF4F9),
    surfaceContainerHighest = Color(0xFFE2E8F0),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

/** Extra brand tokens not covered by Material's ColorScheme. */
@Immutable
data class FreezrExtras(
    val frozenGradient: Brush,
    val idleGradient: Brush,
    val pausedGradient: Brush,
    val warning: Color,
    val success: Color,
    val cardBorder: Color,
    val chart: List<Color>,
)

private val DarkExtras = FreezrExtras(
    frozenGradient = Brush.linearGradient(listOf(Color(0xFF0EA5E9), Color(0xFF4338CA), Color(0xFF1E1B4B))),
    idleGradient = Brush.linearGradient(listOf(Color(0xFF134E4A), Color(0xFF0F2A3D), Color(0xFF0B1220))),
    pausedGradient = Brush.linearGradient(listOf(Color(0xFF3F3F46), Color(0xFF1F2937))),
    warning = Color(0xFFFBBF24),
    success = Color(0xFF34D399),
    cardBorder = Color(0x1FFFFFFF),
    chart = listOf(Color(0xFF7DD3FC), Color(0xFFA5B4FC), Color(0xFF5EEAD4), Color(0xFFFDA4AF), Color(0xFFFCD34D)),
)

private val LightExtras = FreezrExtras(
    frozenGradient = Brush.linearGradient(listOf(Color(0xFF38BDF8), Color(0xFF6366F1), Color(0xFF4338CA))),
    idleGradient = Brush.linearGradient(listOf(Color(0xFF0F766E), Color(0xFF0369A1))),
    pausedGradient = Brush.linearGradient(listOf(Color(0xFF94A3B8), Color(0xFF64748B))),
    warning = Color(0xFFD97706),
    success = Color(0xFF059669),
    cardBorder = Color(0x14000000),
    chart = listOf(Color(0xFF0284C7), Color(0xFF6366F1), Color(0xFF0D9488), Color(0xFFE11D48), Color(0xFFD97706)),
)

val LocalFreezrExtras = staticCompositionLocalOf { DarkExtras }

private val AppTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun FreezrTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors: ColorScheme = if (darkTheme) DarkColors else LightColors
    androidx.compose.runtime.CompositionLocalProvider(LocalFreezrExtras provides if (darkTheme) DarkExtras else LightExtras) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

object FreezrThemeExtras {
    val current: FreezrExtras
        @Composable get() = LocalFreezrExtras.current
}
