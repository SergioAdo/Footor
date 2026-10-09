package app.footor.ui.theme

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

private val PitchGreen = Color(0xFF1B7F4B)

private val LightColors = lightColorScheme(
    primary = PitchGreen,
    secondary = Color(0xFF4E6354),
    tertiary = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7AD9A1),
    secondary = Color(0xFFB5CCBA),
    tertiary = Color(0xFFFFB4AB),
)

/** Colour used for anything live: the clock, live badges and fresh goals. */
val LiveRed = Color(0xFFE53935)

@Composable
fun FootorTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
