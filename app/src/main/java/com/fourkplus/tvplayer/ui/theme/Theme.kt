package com.fourkplus.tvplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Navy = Color(0xFF071226)
val DeepBlue = Color(0xFF0A2A57)
val BrandBlue = Color(0xFF1268F3)
val Cyan = Color(0xFF16C4F4)
val Orange = Color(0xFFFFA000)
val Ice = Color(0xFFF3F8FF)

private val DarkColors = darkColorScheme(
    primary = BrandBlue,
    secondary = Cyan,
    tertiary = Orange,
    background = Navy,
    surface = Color(0xFF0D1C34),
    surfaceVariant = Color(0xFF142844),
    onPrimary = Color.White,
    onBackground = Color(0xFFF5F8FF),
    onSurface = Color(0xFFF5F8FF)
)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    secondary = Cyan,
    tertiary = Orange,
    background = Ice,
    surface = Color.White,
    surfaceVariant = Color(0xFFE7F0FF),
    onPrimary = Color.White,
    onBackground = Navy,
    onSurface = Navy
)

@Composable
fun FourKPlusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
