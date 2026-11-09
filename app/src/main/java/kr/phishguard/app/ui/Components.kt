package kr.phishguard.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 화면 제목. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        modifier = modifier.semantics { heading() },
    )
}

/**
 * 묶음 제목. 부모 Column의 간격(12dp)에 위쪽 12dp를 더해 묶음 사이 24dp, 묶음 안 12dp가 되게 한다.
 * 화면 맨 위 첫 묶음은 first = true로 위쪽 여백을 뺀다.
 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, first: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(top = if (first) 0.dp else 12.dp)
            .semantics { heading() },
    )
}

/** 오류 문구. 화면 낭독기가 바로 읽도록 Assertive. */
@Composable
fun ErrorText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Assertive },
    )
}

private val EMERGENCY_CALLS = listOf(
    "118" to "KISA 118에 전화",
    "112" to "경찰 112에 전화",
    "1332" to "금감원 1332에 전화",
)

/** 긴급 신고·상담 전화 버튼 묶음 (설정 화면, 삭제 실패 안내 공용). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmergencyCallButtons(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var dialFailed by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EMERGENCY_CALLS.forEach { (number, label) ->
                OutlinedButton(onClick = {
                    dialFailed = try {
                        context.startActivity(
                            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        false
                    } catch (e: ActivityNotFoundException) {
                        Log.w("PG-Call", "전화 앱 없음: $number", e)
                        true
                    }
                }) { Text(label) }
            }
        }
        if (dialFailed) ErrorText("전화 앱을 열 수 없습니다. 휴대폰에서 직접 번호를 눌러 전화하세요.")
    }
}
