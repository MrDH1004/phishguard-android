package kr.phishguard.app.scan

import android.content.Context
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.phishguard.app.ui.RiskHigh
import kr.phishguard.app.ui.RiskLow
import kr.phishguard.app.ui.RiskMedium
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "ScanUi"

fun riskColor(level: RiskLevel): Color = when (level) {
    RiskLevel.HIGH -> RiskHigh
    RiskLevel.MEDIUM -> RiskMedium
    RiskLevel.LOW -> RiskLow
}

fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(millis))

/** 앱 아이콘을 IO 스레드에서 비트맵으로 로드. 실패 시 기본 아이콘. */
@Composable
fun AppIcon(packageName: String, size: Dp = 40.dp) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) { loadIcon(context, packageName) }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Icon(
            Icons.Filled.Android,
            contentDescription = null,
            modifier = Modifier.size(size),
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}

private fun loadIcon(context: Context, packageName: String): ImageBitmap? = try {
    context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
} catch (e: Exception) {
    Log.w(TAG, "icon load failed: $packageName", e)
    null
}

@Composable
fun RiskBadge(level: RiskLevel, score: Int? = null) {
    val text = if (score != null) "${level.label} $score" else level.label
    Box(
        modifier = Modifier
            .background(riskColor(level), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 12.sp)
    }
}
