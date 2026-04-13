package dev.dblink.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = AppleBlue,
    onPrimary = Color.White,
    secondary = LeicaSteel,
    onSecondary = Color.White,
    tertiary = AppleOrange,
    onTertiary = Color.White,
    background = Chalk,
    onBackground = Obsidian,
    surface = Color.White,
    onSurface = Obsidian,
    onSurfaceVariant = LeicaSteel,
    surfaceVariant = Ghost,
    outline = LeicaBorder.copy(alpha = 0.4f),
    error = AppleRed,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = AppleBlue,
    onPrimary = Color.White,
    secondary = LeicaPaper,
    onSecondary = Obsidian,
    tertiary = AppleGreen,
    onTertiary = Color.White,
    background = Obsidian,
    onBackground = Chalk,
    surface = Onyx,
    onSurface = Chalk,
    onSurfaceVariant = LeicaStone,
    surfaceVariant = Graphite,
    outline = LeicaBorder,
    error = AppleRed,
    onError = Color.White,
)

@Composable
fun DbLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CameraTypography,
        content = content,
    )
}
