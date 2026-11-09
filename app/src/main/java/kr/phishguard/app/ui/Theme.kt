package kr.phishguard.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// WCAG 대비 (상대휘도 공식으로 계산):
//            Card(surfaceContainerHighest E2E3E9)  surface FBFCFF  흰 글자(배지)
// RiskHigh   5.13:1                                6.40:1          6.57:1
// RiskMedium 5.28:1                                6.59:1          6.76:1
// RiskLow    6.14:1                                7.67:1          7.87:1
// onSurfaceVariant 44474F: Card 7.26:1, surface 9.06:1
val RiskHigh = Color(0xFFB71C1C)
val RiskMedium = Color(0xFF9A4000)
val RiskLow = Color(0xFF1B5E20)

private val scheme = lightColorScheme(
    primary = Color(0xFF1D3E79),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF001A41),
    inversePrimary = Color(0xFFADC6FF),
    secondary = Color(0xFF116EB5),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E4FF),
    onSecondaryContainer = Color(0xFF001C38),
    tertiary = Color(0xFF00639A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCDE5FF),
    onTertiaryContainer = Color(0xFF001D32),
    background = Color(0xFFFBFCFF),
    onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFFBFCFF),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    surfaceTint = Color(0xFF1D3E79),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF1F0F7),
    error = RiskHigh,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    scrim = Color.Black,
    surfaceBright = Color(0xFFFBFCFF),
    surfaceDim = Color(0xFFD9DAE0),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F5FB),
    surfaceContainer = Color(0xFFEEEFF5),
    surfaceContainerHigh = Color(0xFFE8E9EF),
    surfaceContainerHighest = Color(0xFFE2E3E9),
)

@Composable
fun PhishGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
