package app.hybridreading

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val scheme =
    darkColorScheme(
        primary = Color(0xFF8B8BFF),
        onPrimary = Color(0xFF15121F),
        primaryContainer = Color(0xFF2E2A54),
        onPrimaryContainer = Color(0xFFE6E2FF),
        secondary = Color(0xFF6FD3C2),
        background = Color(0xFF101014),
        onBackground = Color(0xFFECEBF4),
        surface = Color(0xFF17171E),
        onSurface = Color(0xFFECEBF4),
        surfaceVariant = Color(0xFF23232E),
        onSurfaceVariant = Color(0xFFB9B8C9),
    )

@Composable
fun InkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
