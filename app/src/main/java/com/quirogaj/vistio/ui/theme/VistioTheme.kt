package com.quirogaj.vistio.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun VistioTheme(tv: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        tv -> darkColorScheme(
            primary = Color(0xFFD6BEFF),
            onPrimary = Color(0xFF2B1554),
            background = Color(0xFF14121B),
            surface = Color(0xFF181520),
            surfaceContainer = Color(0xFF231D30),
            surfaceContainerLow = Color(0xFF1D1828),
        )
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme(
            primary = Color(0xFF86D7C0),
            onPrimary = Color(0xFF00382C),
            primaryContainer = Color(0xFF005142),
            onPrimaryContainer = Color(0xFFA2F4DB),
        )
        else -> lightColorScheme(
            primary = Color(0xFF006B57),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFA2F4DB),
            onPrimaryContainer = Color(0xFF002019),
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
internal fun VistioTvTheme(enabled: Boolean, content: @Composable () -> Unit) {
    if (enabled) {
        androidx.tv.material3.MaterialTheme(
            colorScheme = androidx.tv.material3.darkColorScheme(
                primary = Color(0xFFD6BEFF),
                onPrimary = Color(0xFF2B1554),
                background = Color(0xFF14121B),
                surface = Color(0xFF231D30),
                onSurface = Color(0xFFF6EEFF),
                onSurfaceVariant = Color(0xFFCBC1D5),
            ),
            content = content,
        )
    } else {
        content()
    }
}
