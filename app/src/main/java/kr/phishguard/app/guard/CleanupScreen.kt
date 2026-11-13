package kr.phishguard.app.guard

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import kr.phishguard.app.ui.EmergencyCallButtons
import kr.phishguard.app.ui.ErrorText
import kr.phishguard.app.ui.ScreenTitle
import kr.phishguard.app.ui.SectionTitle

private fun isInstalled(context: Context, pkg: String): Boolean = try {
    context.packageManager.getPackageInfo(pkg, 0)
    true
} catch (e: PackageManager.NameNotFoundException) {
    false
}

private fun labelOf(context: Context, pkg: String): String = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (e: PackageManager.NameNotFoundException) {
    pkg
}

/**
 * 잠금이 풀린 뒤 정리 화면: 의심 앱과 사기범 연락 창구(텔레그램·LINE) 삭제.
 * 다른 앱 삭제는 안드로이드가 항상 "삭제할까요?" 확인을 받으므로 버튼을 누른 뒤 확인을 눌러야 한다.
 */
@Composable
fun CleanupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val lock = remember { LockStore.load(context) }
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current
    // 삭제 확인 창에서 돌아오면 목록을 다시 확인
    LaunchedEffect(lifecycle) { lifecycle.lifecycle.currentStateFlow.collect { refresh++ } }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("의심 앱 정리")
        if (lock == null) {
            Text("정리할 항목이 없습니다.")
            TextButton(onClick = onDone) { Text("닫기") }
            return@Column
        }
        Text("가족이 1시간 잠금을 걸었던 의심 앱입니다. 아래 앱을 지우세요. 삭제 버튼을 누르면 휴대폰이 '삭제할까요?'를 묻습니다.")
        lock.evidencePath?.let { Text("증거 파일은 먼저 저장해 두었습니다: $it", style = MaterialTheme.typography.bodySmall) }
        val pending = lock.cleanup.filter { refresh >= 0 && isInstalled(context, it) }
        if (pending.isEmpty()) {
            Text("모두 지웠습니다.", fontWeight = FontWeight.Bold)
        }
        pending.forEach { pkg ->
            val messenger = pkg in GuardPolicy.MESSENGER_PACKAGES
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(labelOf(context, pkg), style = MaterialTheme.typography.titleMedium)
                    Text(pkg, style = MaterialTheme.typography.bodySmall)
                    if (messenger) {
                        Text(
                            "사기범이 연락할 때 자주 쓰는 메신저입니다. 지우면 대화 기록도 사라집니다. 경찰에 낼 대화가 필요하면 먼저 대화 내보내기(백업)를 하거나 화면을 캡처해 두세요.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text("보이스피싱 의심 앱입니다. 지우세요.", color = MaterialTheme.colorScheme.error)
                    }
                    var failed by remember(pkg) { androidx.compose.runtime.mutableStateOf(false) }
                    Button(onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: ActivityNotFoundException) {
                            Log.w("PG-Cleanup", "삭제 화면 열기 실패: $pkg", e)
                            failed = true
                        }
                    }) { Text("삭제") }
                    if (failed) ErrorText("삭제 화면을 열 수 없습니다. 설정 → 애플리케이션에서 직접 지워 주세요.")
                }
            }
        }
        SectionTitle("도움 받기")
        Text("돈을 보냈거나 개인정보를 알려 줬다면 바로 신고하세요.")
        EmergencyCallButtons()
        TextButton(onClick = {
            LockStore.clear(context)
            onDone()
        }) { Text("정리 끝") }
    }
}
