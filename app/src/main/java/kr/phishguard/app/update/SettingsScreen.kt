package kr.phishguard.app.update

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.FileProvider
import kr.phishguard.app.history.HistoryStore
import kr.phishguard.app.remove.EvidenceExporter
import kr.phishguard.app.report.ChecklistAction
import kr.phishguard.app.report.ChecklistStore
import kr.phishguard.app.report.ReportBuilder
import kr.phishguard.app.report.ReportExporter
import kr.phishguard.app.report.ReportInput
import kr.phishguard.app.report.ReportResult
import kr.phishguard.app.report.ResponseChecklist
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
import kr.phishguard.app.ui.AutoBlockerGuide
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
    var checkedIds by remember { mutableStateOf(ChecklistStore.load(context)) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var reportBusy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ReportResult?>(null) }
    var reportError by remember { mutableStateOf<String?>(null) }
    var shareError by remember { mutableStateOf(false) }
    var vpnError by remember { mutableStateOf(false) }

    fun runReport() {
        reportBusy = true
        reportError = null
        shareError = false
        scope.launch {
            try {
                report = withContext(Dispatchers.IO) {
                    HistoryStore.ensureLoaded(context)
                    val html = ReportBuilder.build(
                        ReportInput(
                            generatedAt = System.currentTimeMillis(),
                            deviceModel = ReportExporter.deviceModel(),
                            androidVersion = ReportExporter.androidVersion(),
                            apps = apps,
                            trusted = trusted,
                            events = HistoryStore.events.value,
                        ),
                    )
                    ReportExporter.export(context, html)
                }
            } catch (e: java.io.IOException) {
                Log.w("PG-Report", "report export failed (IO)", e)
                reportError = "리포트를 저장하지 못했습니다. 저장 공간이 충분한지 확인한 뒤 다시 눌러 주세요."
            } catch (e: SecurityException) {
                Log.w("PG-Report", "report export failed (permission)", e)
                reportError = "저장 권한이 없어 리포트를 저장하지 못했습니다. 다시 누르고 권한 요청 창에서 '허용'을 눌러 주세요."
            } catch (e: RuntimeException) {
                Log.e("PG-Report", "report export failed", e)
                reportError = "리포트를 만드는 중 문제가 생겼습니다. 잠시 후 다시 눌러 주세요."
            } finally {
                reportBusy = false
            }
        }
    }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runReport()
        else reportError = "저장소 권한이 거부되어 다운로드 폴더에 저장할 수 없습니다. 다시 누르고 권한 요청 창에서 '허용'을 눌러 주세요."
    }

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

        SectionTitle("악성 앱 설치 막기 (삼성 자동 차단)")
        AutoBlockerGuide()

        SectionTitle("안전앱")
        if (trusted.isEmpty()) {
            Text("안전앱으로 표시한 앱이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        modifier = Modifier.semantics { contentDescription = "$label 안전앱 해제" },
                    ) { Text("해제") }
                }
            }

        SectionTitle("피해 대응 체크리스트")
        Text(
            "피해를 입었다면 아래 순서대로 하나씩 해 보세요. 끝낸 항목은 체크해 두면 다음에 열어도 남아 있습니다.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "${ResponseChecklist.completed(checkedIds)} / ${ResponseChecklist.items.size} 완료",
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        ResponseChecklist.items.forEach { item ->
            val done = item.id in checkedIds
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).toggleable(
                        value = done,
                        role = Role.Checkbox,
                        onValueChange = { v ->
                            ChecklistStore.set(context, item.id, v)
                            checkedIds = if (v) checkedIds + item.id else checkedIds - item.id
                        },
                    ).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(checked = done, onCheckedChange = null)
                    Column {
                        Text(item.title, fontWeight = FontWeight.Bold)
                        Text(
                            item.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                when (val a = item.action) {
                    is ChecklistAction.Dial -> OutlinedButton(
                        onClick = {
                            actionError = try {
                                context.startActivity(
                                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:${a.number}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                                null
                            } catch (e: ActivityNotFoundException) {
                                Log.w("PG-Checklist", "전화 앱 없음", e)
                                "전화 앱을 열 수 없습니다. 휴대폰에서 직접 ${a.number}번을 눌러 전화하세요."
                            }
                        },
                        modifier = Modifier.semantics { contentDescription = "${item.title} 전화" },
                    ) { Text("전화") }
                    is ChecklistAction.OpenUrl -> OutlinedButton(
                        onClick = {
                            actionError = try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(a.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                                null
                            } catch (e: ActivityNotFoundException) {
                                Log.w("PG-Checklist", "브라우저 없음", e)
                                "사이트를 열 수 없습니다. 브라우저에서 직접 ${a.url} 주소로 들어가 주세요."
                            }
                        },
                        modifier = Modifier.semantics { contentDescription = "${item.title} 사이트 열기" },
                    ) { Text("사이트 열기") }
                    ChecklistAction.None -> Unit
                }
            }
        }
        actionError?.let { ErrorText(it) }

        SectionTitle("신고용 리포트")
        Text(
            "의심 앱, 설치·삭제 기록, 기기 정보를 한 문서(HTML)로 모아 경찰·금융감독원 신고에 첨부할 수 있습니다. 다운로드 폴더에 저장됩니다.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            enabled = !reportBusy,
            onClick = {
                if (EvidenceExporter.needsLegacyPermission() &&
                    context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                ) {
                    storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    runReport()
                }
            },
        ) { Text(if (reportBusy) "만드는 중…" else "리포트 만들기") }
        reportError?.let { ErrorText(it) }
        report?.let { r ->
            Text(
                "저장 완료: ${r.savedTo}",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            OutlinedButton(
                onClick = {
                    shareError = try {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", r.cacheFile)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = ReportExporter.MIME
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "리포트 공유"))
                        false
                    } catch (e: IllegalArgumentException) {
                        Log.w("PG-Report", "share failed (file)", e)
                        true
                    } catch (e: ActivityNotFoundException) {
                        Log.w("PG-Report", "share failed (no target)", e)
                        true
                    }
                },
            ) { Text("리포트 공유") }
            if (shareError) {
                ErrorText("공유할 수 없습니다. 파일 앱의 다운로드 폴더에서 '${r.fileName}'을 직접 보내 주세요.")
            }
        }

        SectionTitle("항상 켜진 VPN")
        Text("통신 감시와 차단을 휴대폰을 껐다 켠 뒤에도 유지하려면 '항상 켜진 VPN'을 켜 두세요.")
        Text(
            "설정 > 연결 > 기타 연결 설정 > VPN 에서 '피싱앱 검사기'의 '항상 켜진 VPN'을 켜면 재부팅 후에도 차단이 유지됩니다. 기기마다 메뉴 위치가 다를 수 있습니다.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = {
                vpnError = try {
                    context.startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    false
                } catch (e: ActivityNotFoundException) {
                    Log.w("PG-Vpn", "VPN 설정 화면 없음", e)
                    true
                }
            },
        ) { Text("VPN 설정 열기") }
        if (vpnError) {
            ErrorText("VPN 설정 화면을 열 수 없습니다. 휴대폰 설정 앱에서 'VPN'을 검색해 직접 열어 주세요.")
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
