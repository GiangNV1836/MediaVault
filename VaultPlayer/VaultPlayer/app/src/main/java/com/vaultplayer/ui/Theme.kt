package com.vaultplayer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration

private val vaultLightColors = lightColorScheme(
    primary = Color(0xFF245B73),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC5E8F7),
    onPrimaryContainer = Color(0xFF001F2A),
    secondary = Color(0xFF625A7E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8DFFF),
    onSecondaryContainer = Color(0xFF1E1735),
    tertiary = Color(0xFF825245),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDBCF),
    onTertiaryContainer = Color(0xFF321207),
    background = Color(0xFFF3F5F8),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FC),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE2E6EB),
    onSurfaceVariant = Color(0xFF42474D),
    outline = Color(0xFF737980),
    outlineVariant = Color(0xFFC2C7CE),
    error = Color(0xFFBA1A1A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF9FAFC),
    surfaceContainer = Color(0xFFEFF2F6),
    surfaceContainerHigh = Color(0xFFE9EDF2)
)

private val vaultDarkColors = darkColorScheme(
    primary = Color(0xFF91CDE5),
    onPrimary = Color(0xFF003545),
    primaryContainer = Color(0xFF164D61),
    onPrimaryContainer = Color(0xFFC5E8F7),
    secondary = Color(0xFFCCC1E9),
    onSecondary = Color(0xFF332B4B),
    secondaryContainer = Color(0xFF4A4263),
    onSecondaryContainer = Color(0xFFE8DFFF),
    tertiary = Color(0xFFF2B8A4),
    onTertiary = Color(0xFF4C261A),
    tertiaryContainer = Color(0xFF663C2E),
    onTertiaryContainer = Color(0xFFFFDBCF),
    background = Color(0xFF111418),
    onBackground = Color(0xFFE1E2E8),
    surface = Color(0xFF171A1F),
    onSurface = Color(0xFFE1E2E8),
    surfaceVariant = Color(0xFF41474D),
    onSurfaceVariant = Color(0xFFC2C7CE),
    outline = Color(0xFF8C9198),
    outlineVariant = Color(0xFF42474E),
    error = Color(0xFFFFB4AB),
    surfaceContainerLowest = Color(0xFF0C0F12),
    surfaceContainerLow = Color(0xFF1B1F24),
    surfaceContainer = Color(0xFF20242A),
    surfaceContainerHigh = Color(0xFF2A2F35)
)

@Composable
fun VaultTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) vaultDarkColors else vaultLightColors, content = content)
}

/** Responsive: < 600dp dùng thanh điều hướng dưới; lớn hơn dùng NavigationRail. */
@Composable
fun isCompactWidth(): Boolean = LocalConfiguration.current.screenWidthDp < 600

private val tagPalette = listOf(
    Color(0xFF1E88E5), Color(0xFF8E24AA), Color(0xFF00897B), Color(0xFFF4511E),
    Color(0xFF6D4C41), Color(0xFF3949AB), Color(0xFFC0CA33), Color(0xFFD81B60)
)

fun tagColor(name: String): Color = tagPalette[(name.hashCode() and 0x7fffffff) % tagPalette.size]
