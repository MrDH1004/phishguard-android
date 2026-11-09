package kr.phishguard.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

// WCAG 대비 (상대휘도 공식으로 계산)
// [라이트] Card(surfaceContainerHighest E2E3E9)  surface FBFCFF  흰 글자(배지)
// RiskHigh   5.13:1                                6.40:1          6.57:1
// RiskMedium 5.28:1                                6.59:1          6.76:1
// RiskLow    6.14:1                                7.67:1          7.87:1
// onSurfaceVariant 44474F: Card 7.26:1, surface 9.06:1
// [다크] Card(surfaceContainerHighest 33353A)  surface 111318  어두운 글자(riskOnColor)
// RiskHigh   FFB4AB 7.23:1                     10.94:1         690005 위 7.72:1
// RiskMedium FFB77C 7.20:1                     10.90:1         4A2800 위 7.72:1
// RiskLow    8BD88E 7.19:1                     10.89:1         00390A 위 7.74:1
// 다크의 위험색 위 흰 글자는 1.7:1이라 읽을 수 없다 → 배지·원 안의 글자는 riskOnColor() 사용.
// onSurfaceVariant C4C6D0: Card 7.21:1, onSurface E2E2E9: Card 9.52:1
// primary ADC6FF 위 onPrimary 002E69: 7.71:1 (버튼 containerColor를 위험색으로 줘도 글자 대비 유지)

/** PhishGuardTheme이 구성 시작 때 갱신. 스냅샷 상태라 읽는 쪽이 자동 재구성된다. */
private var darkActive by mutableStateOf(false)

// 일반(비-Composable) 함수에서도 쓰이므로 getter 방식으로 현재 테마 값을 돌려준다.
val RiskHigh: Color get() = if (darkActive) Color(0xFFFFB4AB) else Color(0xFFB71C1C)
val RiskMedium: Color get() = if (darkActive) Color(0xFFFFB77C) else Color(0xFF9A4000)
val RiskLow: Color get() = if (darkActive) Color(0xFF8BD88E) else Color(0xFF1B5E20)

/** 위험색(RiskHigh/Medium/Low) 배경 위에 올리는 글자색. 라이트는 흰색, 다크는 어두운 색. */
val riskOnColor: Color get() = if (darkActive) Color(0xFF1A1B20) else Color.White

private val lightScheme = lightColorScheme(
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
    error = Color(0xFFB71C1C),
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

private val darkScheme = darkColorScheme(
    primary = Color(0xFFADC6FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF1D3E79),
    onPrimaryContainer = Color(0xFFD8E2FF),
    inversePrimary = Color(0xFF1D3E79),
    secondary = Color(0xFFA5C8FF),
    onSecondary = Color(0xFF00315C),
    secondaryContainer = Color(0xFF004880),
    onSecondaryContainer = Color(0xFFD3E4FF),
    tertiary = Color(0xFF8DCDFF),
    onTertiary = Color(0xFF00344F),
    tertiaryContainer = Color(0xFF004B71),
    onTertiaryContainer = Color(0xFFCDE5FF),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceTint = Color(0xFFADC6FF),
    inverseSurface = Color(0xFFE2E2E9),
    inverseOnSurface = Color(0xFF2F3036),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8E909A),
    outlineVariant = Color(0xFF44474F),
    scrim = Color.Black,
    surfaceBright = Color(0xFF37393E),
    surfaceDim = Color(0xFF111318),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF1A1B20),
    surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
)

@Composable
fun PhishGuardTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    darkActive = dark
    MaterialTheme(colorScheme = if (dark) darkScheme else lightScheme, content = content)
}
