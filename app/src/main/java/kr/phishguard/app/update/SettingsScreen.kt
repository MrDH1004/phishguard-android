package kr.phishguard.app.update

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.withContext
import kr.phishguard.app.BuildConfig
import kr.phishguard.app.scan.ScanRepository
import kr.phishguard.app.scan.TrustList
import kr.phishguard.app.ui.EmergencyCallButtons
import kr.phishguard.app.ui.ErrorText
import kr.phishguard.app.ui.ScreenTitle
import kr.phishguard.app.ui.SectionTitle

/** 프로세스당 한 번만 자동 확인. */
private object UpdateSession {
    @Volatile var checked = false
}

/** 다운로드/검증/설치 진행 UI 상태. */
private class UpdateFlow {
    var busy by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var status by mutableStateOf("")
    var error by mutableStateOf<String?>(null)

    suspend fun run(context: Context, info: UpdateInfo) {
        busy = true
        error = null
        progress = 0f
        val result = withContext(Dispatchers.IO) {
            UpdateManager.downloadAndVerify(
                context, info,
                onProgress = { progress = it },
                onStatus = { status = it },
            )
        }
        busy = false
        when (result) {
            is DownloadResult.Ready -> {
                val launched = UpdateManager.install(context, result.file)
                status = if (launched) {
                    "설치 확인 화면을 열었습니다."
                } else {
                    "'알 수 없는 앱 설치'를 허용한 뒤 다시 업데이트를 눌러 주세요."
                }
            }
            is DownloadResult.Error -> {
                status = ""
                error = result.message
            }
        }
    }
}

@Composable
private fun UpdateDialog(info: UpdateInfo, flow: UpdateFlow, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!flow.busy) onDismiss() },
        title = { Text("새 버전 v${info.version}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(info.notes.ifBlank { "변경 내용 안내가 없습니다." })
                if (flow.busy) LinearProgressIndicator(progress = { flow.progress }, modifier = Modifier.fillMaxWidth())
                if (flow.status.isNotEmpty()) {
                    Text(
                        flow.status,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                flow.error?.let { ErrorText(it) }
            }
        },
        confirmButton = {
            TextButton(enabled = !flow.busy, onClick = { scope.launch { flow.run(context, info) } }) { Text("업데이트") }
        },
        dismissButton = {
            TextButton(enabled = !flow.busy, onClick = onDismiss) { Text("나중에") }
        },
    )
}

/** 앱 시작 시 호출. 프로세스당 한 번 백그라운드로 확인하고 새 버전이면 다이얼로그를 띄운다. */
@Composable
fun UpdatePromptHost() {
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    val flow = remember { UpdateFlow() }
    LaunchedEffect(Unit) {
        if (UpdateSession.checked) return@LaunchedEffect
        val r = withContext(Dispatchers.IO) { UpdateManager.check() }
        UpdateSession.checked = true
        if (r is CheckResult.Available) info = r.info
    }
    info?.let { UpdateDialog(it, flow) { info = null } }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val trusted by TrustList.trusted.collectAsStateWithLifecycle()
    val apps by ScanRepository.apps.collectAsStateWithLifecycle()
    var checking by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf("") }
    var available by remember { mutableStateOf<UpdateInfo?>(null) }
    val flow = remember { UpdateFlow() }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("설정")

        SectionTitle("업데이트")
        Text("현재 버전 ${BuildConfig.VERSION_NAME}")
        Button(
            enabled = !checking,
            onClick = {
                scope.launch {
                    checking = true
                    resultText = "확인 중…"
                    when (val r = withContext(Dispatchers.IO) { UpdateManager.check() }) {
                        is CheckResult.Available -> {
                            available = r.info
                            resultText = "새 버전 v${r.info.version}이 있습니다."
                        }
                        CheckResult.UpToDate -> resultText = "최신 버전입니다."
                        CheckResult.Failed -> resultText = "업데이트를 확인하지 못했습니다. 지금 버전은 그대로 쓸 수 있습니다. 인터넷 연결을 확인한 뒤 다시 눌러 주세요."
                    }
                    checking = false
                }
            },
        ) { Text("업데이트 확인") }
        if (resultText.isNotEmpty()) {
            Text(resultText, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        available?.let { UpdateDialog(it, flow) { available = null } }

        SectionTitle("아는 앱")
        if (trusted.isEmpty()) {
            Text("아는 앱으로 표시한 앱이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trusted.map { pkg -> pkg to (apps.firstOrNull { it.packageName == pkg }?.label ?: pkg) }
            .sortedBy { it.second }
            .forEach { (pkg, label) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label)
                        Text(
                            pkg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { TrustList.set(context, pkg, false) },
                        modifier = Modifier.semantics { contentDescription = "$label 아는 앱 해제" },
                    ) { Text("해제") }
                }
            }

        SectionTitle("앱 소개")
        Text("피싱앱 검사기는 휴대폰에 설치된 앱의 권한과 설치 출처를 규칙으로 점검해 의심 앱을 찾고, 통신을 감시하며, 증거를 보존한 뒤 안전하게 삭제하도록 돕습니다.")

        SectionTitle("개인정보 및 네트워크 안내")
        Text("앱 검사는 인터넷 없이 기기 안에서만 동작합니다. 인터넷은 새 버전을 확인하고 내려받을 때만 사용합니다.")
        Text("개인정보나 검사 결과는 어디로도 전송되지 않습니다.")

        SectionTitle("긴급 신고·상담")
        EmergencyCallButtons()

        SectionTitle("오픈소스·데이터 출처")
        Text("AndroidX, Jetpack Compose, Kotlin Coroutines: Apache License 2.0")
    }
}
