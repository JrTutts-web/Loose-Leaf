package io.github.jrtutts_web.looseleaf.core.ui

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

private val Light = lightColorScheme(
    primary = Color(0xFF2E5E4E), onPrimary = Color.White,
    primaryContainer = Color(0xFFB9E4D2), onPrimaryContainer = Color(0xFF002117),
    secondary = Color(0xFF4C6359), secondaryContainer = Color(0xFFCEE9DB),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF9DD0B8), onPrimary = Color(0xFF003828),
    primaryContainer = Color(0xFF14503D), onPrimaryContainer = Color(0xFFB9E4D2),
    secondary = Color(0xFFB3CCBF), secondaryContainer = Color(0xFF354B42),
)

@Composable
fun LooseLeafTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val ctx = LocalContext.current
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else if (dark) Dark else Light
    MaterialTheme(colorScheme = colors, content = content)
}
