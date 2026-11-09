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

/**
 * 삼성 '자동 차단(Auto Blocker)' 켜기 안내 + 설정 바로가기.
 * 자동 차단 화면을 직접 여는 공개 인텐트는 확인되지 않아 보안 설정 화면을 연다.
 * 출처: https://www.samsung.com/uk/support/mobile-devices/protect-your-galaxy-device-with-the-new-auto-blocker-feature/
 */
@Composable
fun AutoBlockerGuide(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var openFailed by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "갤럭시 폰은 '자동 차단'을 켜면 Play 스토어·갤럭시 스토어가 아닌 곳에서 받은 앱(문자 링크로 받은 APK 등) 설치가 막힙니다. 보이스피싱 악성 앱을 막는 가장 확실한 방법입니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "켜는 법: 설정 → 보안 및 개인정보 보호 → 자동 차단 → 켜기",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "기종·버전에 따라 메뉴 이름이 다를 수 있습니다. 누군가 전화로 '자동 차단을 끄라'고 하면 사기입니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = {
            openFailed = false
            val intents = listOf(
                Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS),
                Intent(android.provider.Settings.ACTION_SETTINGS),
            )
            openFailed = intents.none { i ->
                try {
                    context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
                } catch (e: ActivityNotFoundException) {
                    Log.w("PG-AutoBlocker", "settings open failed: ${i.action}", e); false
                }
            }
        }) { Text("보안 설정 열기") }
        if (openFailed) {
            ErrorText("설정 화면을 열 수 없습니다. 휴대폰의 '설정' 앱을 직접 열어 '보안 및 개인정보 보호'를 찾아 주세요.")
        }
    }
}
