package kr.phishguard.app.remove

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kr.phishguard.app.net.BlockList
import kr.phishguard.app.net.MonitorVpnService
import kr.phishguard.app.scan.AppInfo
import kr.phishguard.app.scan.ScanRepository
import kr.phishguard.app.ui.EmergencyCallButtons
import kr.phishguard.app.ui.ErrorText
import kr.phishguard.app.ui.RiskHigh
import kr.phishguard.app.ui.RiskLow
import kr.phishguard.app.ui.ScreenTitle
import kr.phishguard.app.ui.riskOnColor

private const val TAG = "PG-Removal"

private val STEPS = listOf("증거 저장", "인터넷 차단", "권한 해제", "삭제", "재검사")

private fun isInstalled(context: Context, pkg: String): Boolean = try {
    context.packageManager.getPackageInfo(pkg, 0)
    true
} catch (_: PackageManager.NameNotFoundException) {
    false
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemovalScreen(packageName: String, onDone: () -> Unit, onBack: () -> Unit = onDone) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 삭제 후 목록에서 사라지므로 시작 시점 스냅샷을 유지
    val app: AppInfo? = remember(packageName) { ScanRepository.find(packageName) }
    val label = app?.label ?: packageName

    var step by rememberSaveable { mutableIntStateOf(0) }

    // 1단계 (결과는 문자열만 저장하고 캐시 파일은 이름으로 복원)
    var evidenceName by rememberSaveable { mutableStateOf<String?>(null) }
    var evidenceSavedTo by rememberSaveable { mutableStateOf("") }
    var evidenceEntries by rememberSaveable { mutableStateOf("") }
    val evidence: EvidenceResult? = evidenceName?.let { n ->
        EvidenceResult(
            fileName = n,
            cacheFile = File(File(context.cacheDir, "evidence"), n),
            savedTo = evidenceSavedTo,
            entries = if (evidenceEntries.isEmpty()) emptyList() else evidenceEntries.split('\n'),
        )
    }
    var evidenceError by remember { mutableStateOf<String?>(null) }
    var evidenceBusy by remember { mutableStateOf(false) }
    var skipConfirm by remember { mutableStateOf(false) }
    var evidenceSkipped by rememberSaveable { mutableStateOf(false) }

    // 2단계
    var blocked by remember { mutableStateOf(BlockList.isBlocked(packageName)) }
    var vpnDenied by remember { mutableStateOf(false) }
    val vpnRunning by MonitorVpnService.running.collectAsState()
    val startFirewall = kr.phishguard.app.net.rememberFirewallStarter { ok -> vpnDenied = !ok }

    // 3단계
    var blocking by remember { mutableStateOf(AdminAccessibilityDetector.detect(context, packageName)) }
    var settingsOpenFailed by remember { mutableStateOf(false) }

    // 4단계
    var uninstallTried by rememberSaveable { mutableStateOf(false) }
    var uninstallFailed by rememberSaveable { mutableStateOf(false) }

    // 5단계
    var scanning by remember { mutableStateOf(false) }
    var removed by remember { mutableStateOf<Boolean?>(null) }

    fun runExport() {
        val target = app ?: run {
            evidenceError = "앱 정보를 찾을 수 없어 증거를 만들 수 없습니다. 검사 탭에서 다시 검사한 뒤 시도하세요."
            return
        }
        evidenceBusy = true
        evidenceError = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { EvidenceExporter.export(context, target) }
                evidenceSavedTo = result.savedTo
                evidenceEntries = result.entries.joinToString("\n")
                evidenceName = result.fileName
            } catch (e: java.io.IOException) {
                Log.w(TAG, "evidence export failed (IO)", e)
                evidenceError = "증거 파일을 저장하지 못했습니다. 저장 공간이 충분한지 확인한 뒤 '다시 시도'를 눌러 주세요."
            } catch (e: SecurityException) {
                Log.w(TAG, "evidence export failed (permission)", e)
                evidenceError = "저장 권한이 없어 증거 파일을 저장하지 못했습니다. '다시 시도'를 누르고 권한 요청 창에서 '허용'을 눌러 주세요."
            } catch (e: RuntimeException) {
                Log.e(TAG, "evidence export failed", e)
                evidenceError = "증거 파일을 저장하는 중 문제가 생겼습니다. '다시 시도'를 누르거나, 계속 안 되면 '증거 없이 진행'을 눌러 주세요."
            } finally {
                evidenceBusy = false
            }
        }
    }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            runExport()
        } else {
            evidenceError = "저장소 권한이 거부되어 다운로드 폴더에 저장할 수 없습니다. '다시 시도'를 누르고 권한 요청 창에서 '허용'을 눌러 주세요."
        }
    }

    fun startExport() {
        if (EvidenceExporter.needsLegacyPermission() &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            runExport()
        }
    }

    fun rescan() {
        scanning = true
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    ScanRepository.scan(context)
                } catch (e: SecurityException) {
                    // 재검사 목록 갱신 실패해도 설치 여부는 PackageManager로 직접 확인
                }
            }
            removed = !isInstalled(context, packageName)
            scanning = false
        }
    }

    val uninstallLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        uninstallTried = true
        if (!isInstalled(context, packageName)) {
            uninstallFailed = false
            step = 4
            rescan()
        } else {
            uninstallFailed = true
        }
    }

    // 설정 화면에서 돌아올 때 권한 상태 갱신
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        blocking = AdminAccessibilityDetector.detect(context, packageName)
        if (step == 3 && !isInstalled(context, packageName)) {
            uninstallFailed = false
            step = 4
            rescan()
        }
    }

    // 단계가 바뀌면 새 단계 제목으로 포커스 이동 (처음 진입 때는 상단 바부터 읽도록 건너뜀)
    val stepTitleFocus = remember { FocusRequester() }
    var stepShown by remember { mutableStateOf(false) }
    LaunchedEffect(step) {
        if (stepShown) stepTitleFocus.requestFocus() else stepShown = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenTitle("의심 앱 삭제")
            Text(
                "$label ($packageName)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Stepper(step)

            when (step) {
                0 -> StepCard("1단계. 증거 저장", stepTitleFocus) {
                    Text("삭제하면 흔적이 사라집니다. 신고에 쓸 수 있도록 APK, 권한 목록, 설치 정보, 데이터 사용량(CSV)을 ZIP 하나로 다운로드 폴더에 저장합니다.")
                    val ev = evidence
                    if (ev != null) {
                        Text(
                            "저장 완료: ${ev.savedTo}",
                            color = RiskLow,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        Text("포함: ${ev.entries.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", ev.cacheFile)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "증거 공유"))
                        }) { Text("증거 파일 공유") }
                        Button(onClick = { step = 1 }, Modifier.fillMaxWidth()) { Text("다음: 인터넷 차단") }
                    } else {
                        if (app == null) ErrorText("앱 정보를 찾지 못했습니다. 검사 탭에서 다시 검사한 뒤 시도하세요.")
                        evidenceError?.let { ErrorText(it) }
                        Button(onClick = { startExport() }, enabled = !evidenceBusy && app != null, modifier = Modifier.fillMaxWidth()) {
                            Text(if (evidenceBusy) "저장 중…" else if (evidenceError != null) "다시 시도" else "증거 ZIP 저장")
                        }
                        TextButton(onClick = { skipConfirm = true }, Modifier.fillMaxWidth()) { Text("증거 없이 진행") }
                    }
                }

                1 -> StepCard("2단계. 인터넷 차단", stepTitleFocus) {
                    Text("이 앱이 개인정보를 밖으로 보내지 못하게 인터넷을 끊습니다. 처음 한 번 'VPN 연결 요청' 창에서 확인을 눌러야 합니다.")
                    if (blocked && vpnRunning) {
                        Text(
                            "차단 켜짐",
                            color = RiskLow,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    if (vpnDenied) {
                        ErrorText("VPN 연결을 허용해야 차단됩니다. 다른 VPN 앱이 켜져 있으면 끄고 '다시 시도'를 눌러 주세요.")
                    }
                    Text("다른 앱의 통신은 건드리지 않습니다. 차단 상태는 '통신 감시' 탭에서 확인·해제할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                    if (!blocked) {
                        Button(onClick = { BlockList.set(context, packageName, true); blocked = true; startFirewall() }, Modifier.fillMaxWidth()) {
                            Text("인터넷 차단")
                        }
                    } else if (!vpnRunning) {
                        Button(onClick = { startFirewall() }, Modifier.fillMaxWidth()) {
                            Text(if (vpnDenied) "다시 시도" else "차단 다시 켜기")
                        }
                    }
                    val blockActive = blocked && vpnRunning
                    Button(onClick = { step = 2 }, Modifier.fillMaxWidth(), colors = if (blockActive) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()) {
                        Text(if (blockActive) "다음: 권한 해제" else "차단 없이 다음")
                    }
                }

                2 -> StepCard("3단계. 권한 해제", stepTitleFocus) {
                    Text("기기관리자, 접근성, 알림 접근 권한이 켜져 있으면 삭제가 막힙니다. 아래 항목을 설정 화면에서 직접 끄세요.")
                    BlockRow("기기관리자", blocking.deviceAdmin, "설정 > 보안 > 기기 관리자 앱에서 '$label' 선택 후 '비활성화'", "기기관리자 설정 열기") {
                        settingsOpenFailed = !AdminAccessibilityDetector.openDeviceAdminSettings(context)
                    }
                    BlockRow("접근성 서비스", blocking.accessibility, "설정 > 접근성 > 설치된 앱에서 '$label'을 끄기", "접근성 설정 열기") {
                        settingsOpenFailed = !AdminAccessibilityDetector.openAccessibilitySettings(context)
                    }
                    BlockRow("알림 접근", blocking.notificationListener, "설정 > 알림 > 기기 및 앱 알림 > 알림 접근에서 '$label'을 끄기", "알림 접근 설정 열기") {
                        settingsOpenFailed = !AdminAccessibilityDetector.openNotificationListenerSettings(context)
                    }
                    if (settingsOpenFailed) ErrorText("설정 화면을 열 수 없습니다. 기기 설정에서 직접 찾아 해제하세요.")
                    if (!blocking.any) {
                        Text(
                            "삭제를 막는 권한이 모두 꺼져 있습니다.",
                            color = RiskLow,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    Button(onClick = { step = 3 }, Modifier.fillMaxWidth()) {
                        Text(if (blocking.any) "그래도 다음: 삭제" else "다음: 삭제")
                    }
                }

                3 -> StepCard("4단계. 삭제", stepTitleFocus) {
                    Text("시스템 삭제 확인창이 뜨면 '확인'을 누르세요.")
                    if (blocking.any) Text("아직 켜져 있는 권한이 있어 삭제가 실패할 수 있습니다. 3단계로 돌아가 해제하세요.", color = RiskHigh)
                    Button(
                        onClick = {
                            val i = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
                                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                            uninstallLauncher.launch(i)
                        },
                        Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = RiskHigh),
                    ) { Text("앱 삭제") }
                    OutlinedButton(onClick = { step = 2 }, Modifier.fillMaxWidth()) { Text("권한 해제 단계로 돌아가기") }
                    if (uninstallTried && uninstallFailed) FailureGuide(context)
                    OutlinedButton(onClick = { step = 4; rescan() }, Modifier.fillMaxWidth()) { Text("삭제했다면 재검사") }
                }

                else -> StepCard("5단계. 재검사", stepTitleFocus) {
                    val politeStatus = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    when {
                        scanning || removed == null -> Text("재검사 중…", modifier = politeStatus)
                        removed == true -> {
                            Text(
                                "앱이 삭제되었습니다. 기기에서 더 이상 발견되지 않습니다.",
                                color = RiskLow,
                                fontWeight = FontWeight.Bold,
                                modifier = politeStatus,
                            )
                            Text("인터넷 차단 설정은 필요 없어 해제했습니다.", style = MaterialTheme.typography.bodySmall)
                        }
                        else -> {
                            Text("앱이 아직 남아 있습니다.", color = RiskHigh, fontWeight = FontWeight.Bold, modifier = politeStatus)
                            FailureGuide(context)
                            OutlinedButton(onClick = { step = 3 }, Modifier.fillMaxWidth()) { Text("삭제 다시 시도") }
                            OutlinedButton(onClick = { rescan() }, Modifier.fillMaxWidth()) { Text("다시 재검사") }
                        }
                    }
                    if (removed == true) {
                        Button(onClick = {
                            BlockList.set(context, packageName, false)
                            onDone()
                        }, Modifier.fillMaxWidth()) { Text("완료") }
                    }
                    if (evidenceSkipped) Text("증거를 저장하지 않고 진행했습니다.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (skipConfirm) {
        AlertDialog(
            onDismissRequest = { skipConfirm = false },
            title = { Text("증거 없이 진행할까요?") },
            text = { Text("삭제 후에는 APK와 사용 기록을 다시 얻을 수 없어 신고에 쓸 증거가 사라집니다.") },
            confirmButton = {
                TextButton(
                    onClick = { skipConfirm = false; evidenceSkipped = true; step = 1 },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("증거 없이 진행") }
            },
            dismissButton = { TextButton(onClick = { skipConfirm = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun Stepper(current: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        STEPS.forEachIndexed { i, name ->
            val done = i < current
            val active = i == current
            val (circle, onCircle) = when {
                done -> RiskLow to riskOnColor
                active -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
            }
            val labelColor = when {
                done -> RiskLow
                active -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val state = when {
                done -> "완료"
                active -> "진행 중"
                else -> "대기"
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics { contentDescription = "${i + 1}단계 $name, $state" },
            ) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(circle), contentAlignment = Alignment.Center) {
                    if (done) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = onCircle, modifier = Modifier.size(18.dp))
                    } else {
                        Text("${i + 1}", color = onCircle, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    name,
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun StepCard(title: String, titleFocus: FocusRequester, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .focusRequester(titleFocus)
                    .focusable()
                    .semantics { heading() },
            )
            content()
        }
    }
}

@Composable
private fun BlockRow(name: String, active: Boolean, howTo: String, buttonLabel: String, onOpen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "$name: ${if (active) "켜져 있음 (해제 필요)" else "꺼져 있음"}",
            color = if (active) RiskHigh else RiskLow,
            fontWeight = FontWeight.Bold,
        )
        if (active) {
            Text(howTo, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onOpen) { Text(buttonLabel) }
        }
    }
}

@Composable
private fun FailureGuide(context: Context) {
    var appSettingsFailed by remember { mutableStateOf(false) }
    HorizontalDivider()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("삭제가 되지 않을 때", style = MaterialTheme.typography.titleSmall, color = RiskHigh, modifier = Modifier.semantics { heading() })
        Text("1. 기기관리자·접근성·알림 접근이 모두 꺼졌는지 3단계에서 다시 확인하세요.")
        Text("2. 안전모드로 재부팅 후 삭제하세요. 전원 버튼을 길게 눌러 '전원 끄기'를 길게 누르면 안전모드 안내가 뜹니다(제조사마다 방법이 다를 수 있음). 안전모드에서는 설치한 앱이 실행되지 않아 삭제가 가능합니다. 설정 > 앱에서 삭제하세요.")
        Text("3. 그래도 안 되면 KISA 118(국번 없이 118, 인터넷침해·해킹 상담)에 문의하세요. 보이스피싱 피해는 경찰 112 또는 금감원 1332에도 신고할 수 있습니다.")
        EmergencyCallButtons()
        OutlinedButton(onClick = {
            appSettingsFailed = try {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                false
            } catch (e: android.content.ActivityNotFoundException) {
                Log.w(TAG, "app settings unavailable", e)
                true
            }
        }) { Text("앱 설정 열기") }
        if (appSettingsFailed) ErrorText("앱 설정 화면을 열 수 없습니다. 기기의 설정 앱에서 '애플리케이션'을 직접 여세요.")
    }
}
