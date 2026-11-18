package com.vaultplayer.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

@Composable
fun VaultTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Responsive: < 600dp dùng thanh điều hướng dưới; lớn hơn dùng NavigationRail. */
@Composable
fun isCompactWidth(): Boolean = LocalConfiguration.current.screenWidthDp < 600

private val tagPalette = listOf(
    Color(0xFF1E88E5), Color(0xFF8E24AA), Color(0xFF00897B), Color(0xFFF4511E),
    Color(0xFF6D4C41), Color(0xFF3949AB), Color(0xFFC0CA33), Color(0xFFD81B60)
)

fun tagColor(name: String): Color = tagPalette[(name.hashCode() and 0x7fffffff) % tagPalette.size]
