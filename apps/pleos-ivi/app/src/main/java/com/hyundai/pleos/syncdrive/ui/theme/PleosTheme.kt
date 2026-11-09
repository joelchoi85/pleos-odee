package com.hyundai.pleos.syncdrive.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** 표면·텍스트 토큰 (라이트/다크 별도). 강조색은 모드 공통. */
@Immutable
data class PleosSurfacePalette(
    val background: Color,
    val panel: Color,
    val card: Color,
    val cardElevated: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val mapBase: Color,
    val routeLine: Color
)

private val DarkPalette = PleosSurfacePalette(
    background = Color(0xFF070B12),
    panel = Color(0xFF0E1624),
    card = Color(0xFF152033),
    cardElevated = Color(0xFF1C2A42),
    border = Color(0xFF263652),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFF94A3B8),
    textMuted = Color(0xFF64748B),
    mapBase = Color(0xFF0B1220),
    routeLine = Color(0xFF334155)
)

private val LightPalette = PleosSurfacePalette(
    background = Color(0xFFF8FAFC),
    panel = Color(0xFFFFFFFF),
    card = Color(0xFFF1F5F9),
    cardElevated = Color(0xFFE2E8F0),
    border = Color(0xFFCBD5E1),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF475569),
    textMuted = Color(0xFF64748B),
    mapBase = Color(0xFFEEF2F6),
    routeLine = Color(0xFF94A3B8)
)

private val LocalPleosPalette = staticCompositionLocalOf { DarkPalette }

object PleosColors {
    // 강조색: 라이트/다크 공통 (앰비언트 라이트와 같은 의미 색)
    val PleosCyan = Color(0xFF00B8E6)
    val AmberAlert = Color(0xFFF59E0B)
    val EmeraldVoice = Color(0xFF10B981)
    val CoralDanger = Color(0xFFF43F5E)

    val ObsidianBg: Color @Composable @ReadOnlyComposable get() = LocalPleosPalette.current.background
    val PanelSurface: Color @Composable @ReadOnlyComposable get() = LocalPleosPalette.current.panel
    val CardSurface: Color @Composable @ReadOnlyComposable get() = LocalPleosPalette.current.card
    val CardSurfaceElevated: Color @Composable @ReadOnlyComposable get() = LocalPleosPalette.current.cardElevated
    val BorderSubtle: Color @Composable @ReadOnlyComposable get() = LocalPleosPalette.current.border
    val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalPleosPalette.current.textPrimary
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalPleosPalette.current.textSecondary
    val TextMuted: Color @Composable @ReadOnlyComposable get() = LocalPleosPalette.current.textMuted
    val MapBase: Color @Composable @ReadOnlyComposable get() = LocalPleosPalette.current.mapBase
    val RouteLine: Color @Composable @ReadOnlyComposable get() = LocalPleosPalette.current.routeLine
}

/** 다크/라이트는 시스템 설정(차량 주야간 테마)을 따른다. */
@Composable
fun PleosSyncDriveTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = PleosColors.PleosCyan, onPrimary = Color(0xFF02131A),
            secondary = PleosColors.EmeraldVoice, tertiary = PleosColors.AmberAlert,
            background = palette.background, onBackground = palette.textPrimary,
            surface = palette.panel, onSurface = palette.textPrimary,
            surfaceVariant = palette.card, onSurfaceVariant = palette.textSecondary,
            error = PleosColors.CoralDanger
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF0284C7), onPrimary = Color.White,
            secondary = PleosColors.EmeraldVoice, tertiary = PleosColors.AmberAlert,
            background = palette.background, onBackground = palette.textPrimary,
            surface = palette.panel, onSurface = palette.textPrimary,
            surfaceVariant = palette.card, onSurfaceVariant = palette.textSecondary,
            error = PleosColors.CoralDanger
        )
    }
    CompositionLocalProvider(LocalPleosPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
