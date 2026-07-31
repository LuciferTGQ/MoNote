package app.monote.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme

private val LightColors = lightColorScheme(
    primary = Ink900,
    onPrimary = Paper50,
    secondary = Vermilion,
    onSecondary = Paper50,
    background = Paper50,
    onBackground = Ink900,
    surface = Paper100,
    onSurface = Ink900,
    surfaceVariant = Paper200,
    onSurfaceVariant = Ink700,
    outline = Ink500,
)

private val DarkColors = darkColorScheme(
    primary = Paper200,
    onPrimary = Ink950,
    secondary = Vermilion,
    onSecondary = Paper50,
    background = Ink950,
    onBackground = Paper100,
    surface = Ink900,
    onSurface = Paper100,
    surfaceVariant = Ink700,
    onSurfaceVariant = Paper200,
    outline = Ink500,
)

@Composable
fun MoNoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
