package kr.phishguard.app.net

import android.content.ActivityNotFoundException
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.phishguard.app.scan.ScanRepository
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kr.phishguard.app.background.AlwaysOnVpn
import kr.phishguard.app.guard.OwnerMode
import kr.phishguard.app.ui.ErrorText
import kr.phishguard.app.ui.RiskMedium
import kr.phishguard.app.ui.SectionTitle

private const val PAY_WARNING = "켜 두는 동안 삼성페이 결제가 안 될 수 있어요. 결제할 때 잠시 끄세요."

/** 설정 > 다른 VPN 앱 막기 (기본 꺼짐) */
@Composable
fun VpnGuardSection() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { VpnGuard.load(context.applicationContext) }
    val hold by VpnGuard.hold.collectAsState()
    val pauseForPay by VpnGuard.pauseForPayment.collectAsState()
    val status by VpnGuard.status.collectAsState()
    val paused by VpnGuard.paused.collectAsState()
    val revokes by VpnGuard.revokes.collectAsState()
    val running by MonitorVpnService.running.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    var vpnDenied by remember { mutableStateOf(false) }
    var settingsError by remember { mutableStateOf(false) }
    var usageAccess by remember { mutableStateOf(UsageStats.hasAccess(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { usageAccess = UsageStats.hasAccess(context) }
    val startVpn = rememberFirewallStarter { ok -> vpnDenied = !ok }

    SectionTitle("다른 VPN 앱 막기")
    Text(
        "사기범이 다른 VPN 앱을 깔아 통신을 숨기거나 이 앱의 인터넷 차단을 풀지 못하게, 이 앱이 VPN 자리를 늘 차지합니다. " +
            "차단할 앱이 없을 때는 대기만 하고 다른 앱의 통신은 건드리지 않습니다.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(PAY_WARNING, style = MaterialTheme.typography.bodySmall, color = RiskMedium)
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(
                value = hold,
                role = Role.Switch,
                onValueChange = { on ->
                    if (on) {
                        confirm = true
                    } else {
                        VpnGuard.setHold(context, false)
                        OwnerMode.syncVpnLock(context) // 기기 소유자 모드면 항상 켜진 VPN 고정도 푼다
                    }
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("다른 VPN 앱 막기", Modifier.weight(1f))
        Switch(checked = hold, onCheckedChange = null)
    }
    if (hold) {
        Text(
            "지금: " + when {
                !running -> "꺼짐 (아래 '다시 켜기'를 누르세요)"
                paused -> "삼성페이 사용 중이라 잠시 멈춤"
                else -> "VPN 자리 지키는 중"
            },
            fontWeight = FontWeight.Bold,
        )
        if (!running) OutlinedButton(onClick = startVpn) { Text("다시 켜기") }
        if (vpnDenied) ErrorText("VPN 연결 요청을 허용해야 켤 수 있습니다.")
    }

    Text(
        "더 확실히 하려면 휴대폰 VPN 설정에서 '피싱앱 검사기'의 '항상 켜진 VPN'과 '항상 켜진 VPN 없이 연결 차단'을 켜세요. " +
            "그러면 다른 VPN 앱이 자리를 가져갈 수 없습니다. 연결 차단을 켜면 아래 '삼성페이 쓸 때 잠시 끄기'는 동작하지 않습니다.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (running) {
        val on = when (status.alwaysOn) { true -> "켜짐"; false -> "꺼짐"; null -> "확인 불가" }
        val lock = when (status.lockdown) { true -> "켜짐"; false -> "꺼짐"; null -> "확인 불가" }
        Text("항상 켜진 VPN: $on · 연결 차단: $lock", style = MaterialTheme.typography.bodySmall)
    }
    OutlinedButton(onClick = {
        settingsError = try {
            context.startActivity(AlwaysOnVpn.settingsIntent())
            false
        } catch (e: ActivityNotFoundException) {
            Log.w("PG-VpnGuard", "VPN 설정 화면 없음", e)
            true
        }
    }) { Text("VPN 설정 열기") }
    if (settingsError) ErrorText("VPN 설정 화면을 열 수 없습니다. 휴대폰 설정 앱에서 'VPN'을 검색해 직접 열어 주세요.")

    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = pauseForPay, role = Role.Switch, onValueChange = { VpnGuard.setPauseForPayment(context, it) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("삼성페이 쓸 때 잠시 끄기")
            Text(
                "삼성페이를 여는 동안 대기 중인 VPN을 내렸다가 닫으면 다시 켭니다. 실제로 차단 중인 앱이 있을 때는 끄지 않습니다.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = pauseForPay, onCheckedChange = null)
    }
    if (pauseForPay && !usageAccess) {
        Text("삼성페이가 열렸는지 알려면 '사용 정보 접근' 허용이 필요합니다.", style = MaterialTheme.typography.bodySmall, color = RiskMedium)
        OutlinedButton(onClick = {
            try {
                context.startActivity(UsageStats.accessSettingsIntent().addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                Log.w("PG-VpnGuard", "사용 정보 접근 설정 없음", e)
            }
        }) { Text("사용 정보 접근 열기") }
    }

    VpnAllowListBlock()

    if (revokes.isNotEmpty()) {
        val fmt = remember { java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.KOREA) }
        Text("다른 VPN이 자리를 가져간 기록", fontWeight = FontWeight.Bold)
        revokes.takeLast(5).reversed().forEach { e ->
            val what = if (e.allowed) "허용된 VPN 앱 사용" else modeLabel(e.mode)
            Text("${fmt.format(java.util.Date(e.time))} · $what", style = MaterialTheme.typography.bodySmall)
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("다른 VPN 앱 막기를 켤까요?") },
            text = {
                Text(
                    "이 앱이 VPN 자리를 늘 차지해 다른 VPN 앱이 켜지지 않게 합니다. 회사·학교 VPN도 함께 막힙니다.\n\n$PAY_WARNING",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    VpnGuard.setHold(context, true)
                    OwnerMode.syncVpnLock(context) // 기기 소유자 모드면 항상 켜진 VPN으로 고정
                    startVpn()
                }) { Text("켜기") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("취소") } },
        )
    }
}

/** VPN 허용 앱: VPN 기능을 선언한 설치 앱만 후보로 보여 주고 체크 */
@Composable
private fun VpnAllowListBlock() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val allowed by VpnAllowList.allowed.collectAsState()
    var candidates by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(Unit) {
        candidates = withContext(Dispatchers.IO) {
            VpnAllowList.load(context.applicationContext)
            val pm = context.packageManager
            VpnAllowList.vpnApps(context).map { pkg ->
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                    pkg
                }
                pkg to label
            }.sortedBy { it.second }
        }
    }
    Text("VPN 허용 앱", fontWeight = FontWeight.Bold)
    Text(
        "회사 VPN처럼 직접 쓰는 VPN 앱을 고르면 VPN 관련 위험 점수에서 빠지고, 그 앱이 VPN 자리를 가져가도 위협으로 기록하지 않습니다. " +
            "원격 조종·사칭 같은 다른 위험 판정은 그대로입니다.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (candidates.isEmpty()) {
        Text("VPN 기능이 있는 앱이 없습니다.", style = MaterialTheme.typography.bodySmall)
    }
    candidates.forEach { (pkg, label) ->
        val checked = pkg in allowed
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = { on ->
                    VpnAllowList.set(context, pkg, on)
                    scope.launch(Dispatchers.IO) { ScanRepository.rescanPackage(context, pkg) }
                }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Column(Modifier.weight(1f)) {
                Text(label)
                Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun modeLabel(mode: String) = when (mode) {
    VpnPlan.Mode.STANDBY.name -> "대기 중 빼앗김"
    VpnPlan.Mode.FOREIGN.name -> "해외 전송 차단 중 빼앗김"
    VpnPlan.Mode.FULL.name -> "인터넷 차단 중 빼앗김"
    else -> mode
}
