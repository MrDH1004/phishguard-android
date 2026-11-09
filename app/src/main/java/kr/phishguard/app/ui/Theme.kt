package kr.phishguard.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val RiskHigh = Color(0xFFC62828)
val RiskMedium = Color(0xFFEF6C00)
val RiskLow = Color(0xFF2E7D32)

private val scheme = lightColorScheme(
    primary = Color(0xFF1D3E79),
    onPrimary = Color.White,
    secondary = Color(0xFF116EB5),
    error = RiskHigh,
)

@Composable
fun PhishGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
