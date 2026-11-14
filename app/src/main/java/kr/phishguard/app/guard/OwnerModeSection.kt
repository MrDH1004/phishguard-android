package kr.phishguard.app.guard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.phishguard.app.family.FamilyController
import kr.phishguard.app.family.FamilyRole
import kr.phishguard.app.family.FamilyStore
import kr.phishguard.app.ui.SectionTitle
import java.io.IOException

/**
 * 설정 > 기기 소유자 모드 (선택). 기본은 꺼짐이며 PC에서 adb 명령으로 한 번 켠다.
 * 해제: 가족 연결 중인 부모 폰은 자녀 승인이 있어야 풀린다 (사기범이 원격으로 풀지 못하게).
 * 연결이 없거나 자녀 폰이면 바로 풀 수 있다 (잠겨서 못 빠져나오는 상황 방지).
 */
@Composable
fun OwnerModeSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var owner by remember { mutableStateOf(OwnerMode.isOwner(context)) }
    var message by remember { mutableStateOf<String?>(null) }
    var askRelease by remember { mutableStateOf(false) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        lifecycle.lifecycle.currentStateFlow.collect { owner = OwnerMode.isOwner(context) }
    }
    val s = FamilyStore.state.value
    // 연결이 끝난 부모 폰만 자녀 승인 필요 (연결 끊기와 같은 규칙: FamilyPolicy.parentUnlink). 토큰은 서버가 401 unknown_token일 때만 지워진다
    val needsChildApproval = s.role == FamilyRole.PARENT && s.paired && FamilyStore.token(context) != null

    SectionTitle("기기 소유자 모드 (선택)")
    Text("지금: " + if (owner) "켜짐" else "꺼짐", fontWeight = FontWeight.Bold)
    Text(
        "켜면: 가족이 확인 창 없이 의심 앱을 지우거나 정지(실행 불가, 증거는 남음)할 수 있고, 답이 없을 때 자동으로 인터넷 차단과 함께 정지합니다. " +
            "이 앱도 지울 수 없게 막습니다.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "단점: 휴대폰에 '관리되는 기기' 표시가 생기고, 켤 때 휴대폰의 모든 계정(Google·삼성 등)을 잠깐 로그아웃해야 합니다. 꺼도 다른 기능은 그대로입니다.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (!owner) {
        Text(
            "켜는 방법: ① 설정 → 계정에서 모든 계정 로그아웃 ② 개발자 옵션의 USB 디버깅 켜기 ③ PC에 연결하고 아래 명령 실행 ④ 계정 다시 로그인",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(OwnerMode.ADB_COMMAND, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("adb", OwnerMode.ADB_COMMAND))
            message = "명령을 복사했습니다"
        }) { Text("명령 복사") }
    } else {
        Text(
            if (needsChildApproval) "해제하려면 가족(자녀) 폰의 승인이 필요합니다. 아래 버튼을 누르면 자녀 폰에 요청이 갑니다."
            else "가족 연결이 없어 이 폰에서 바로 해제할 수 있습니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { askRelease = true }) { Text(if (needsChildApproval) "해제 승인 요청" else "기기 소유자 해제") }
        }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (askRelease) {
        AlertDialog(
            onDismissRequest = { askRelease = false },
            title = { Text("기기 소유자 모드를 해제할까요?") },
            text = { Text("해제하면 이 앱을 지울 수 있게 되고, 확인 창 없는 삭제·정지를 못 합니다. 다시 켜려면 PC에서 설정해야 합니다.") },
            confirmButton = {
                TextButton(onClick = {
                    askRelease = false
                    scope.launch { message = release(context, needsChildApproval); owner = OwnerMode.isOwner(context) }
                }) { Text(if (needsChildApproval) "요청 보내기" else "해제") }
            },
            dismissButton = { TextButton(onClick = { askRelease = false }) { Text("취소") } },
        )
    }
}

private suspend fun release(context: Context, needsChildApproval: Boolean): String = withContext(Dispatchers.IO) {
    if (!needsChildApproval) return@withContext OwnerMode.release(context).message
    try {
        FamilyController.requestOwnerRelease(context)
        "가족 폰에 해제 요청을 보냈습니다. 가족 폰에서 승인하면 풀립니다."
    } catch (e: IOException) {
        Log.w("PG-Owner", "해제 요청 실패", e)
        "요청을 보내지 못했습니다. 인터넷 연결을 확인하세요."
    }
}
