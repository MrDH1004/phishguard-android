package kr.phishguard.app.net

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.phishguard.app.scan.RiskLevel
import kr.phishguard.app.scan.ScanRepository
import kr.phishguard.app.scan.TrustList
import kr.phishguard.app.ui.ErrorText
import kr.phishguard.app.ui.RiskHigh
import kr.phishguard.app.ui.RiskLow
import kr.phishguard.app.ui.RiskMedium
import kr.phishguard.app.ui.SectionTitle

private data class UsageRow(
    val uid: Int,
    val pkg: String?,
    val label: String,
    val risk: RiskLevel?,
    val usage: UidUsage,
    /** 검사 결과에 있는 앱인지 (열기·차단 스위치 표시 기준). */
    val scanned: Boolean,
)

private const val PCAPDROID_PKG = "com.emanuelef.remote_capture"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MonitorScreen(onOpenApp: (String) -> Unit) {
    val context = LocalContext.current
    val apps by ScanRepository.apps.collectAsState()
    val blocked by BlockList.blocked.collectAsState()
    val trusted by TrustList.trusted.collectAsState()
    val running by MonitorVpnService.running.collectAsState()
    var hasAccess by remember { mutableStateOf(UsageStats.hasAccess(context)) }
    var periodDays by remember { mutableIntStateOf(1) }
    var rows by remember { mutableStateOf<List<UsageRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var accessSettingsFailed by remember { mutableStateOf(false) }
    var pcapdroidFailed by remember { mutableStateOf(false) }
    val startFirewall = rememberFirewallStarter()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasAccess = UsageStats.hasAccess(context)
        refreshKey++
    }

    LaunchedEffect(hasAccess, periodDays, apps, trusted, refreshKey) {
        if (!hasAccess) { rows = emptyList(); loading = false; return@LaunchedEffect }
        loading = true
        val since = System.currentTimeMillis() - periodDays * 24L * 3600_000L
        rows = withContext(Dispatchers.IO) {
            val byUid = apps.associateBy { it.uid }
            UsageStats.query(context, since).values
                .filter { it.totalTx + it.totalRx > 0 }
                .map { u ->
                    val app = byUid[u.uid]
                    val pkg = app?.packageName ?: context.packageManager.getPackagesForUid(u.uid)?.firstOrNull()
                    val label = app?.label ?: pkg ?: when (u.uid) {
                        -4 -> "삭제된 앱"
                        -5 -> "테더링"
                        1000 -> "안드로이드 시스템"
                        else -> "uid ${u.uid}"
                    }
                    // 아는 앱은 위험 표시·정렬 우선순위 없이 보인다.
                    val risk = if (app != null && app.packageName in trusted) null else app?.riskLevel
                    UsageRow(u.uid, pkg, label, risk, u, scanned = app != null)
                }
                .sortedWith(compareBy<UsageRow> { it.risk?.ordinal ?: 3 }.thenByDescending { it.usage.totalTx })
        }
        loading = false
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.padding(4.dp)) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("인터넷 차단", first = true)
                    Text(
                        if (running) "켜짐 · ${blocked.size}개 앱 차단 중" else "꺼짐 · 차단 목록 ${blocked.size}개",
                        color = if (running) RiskLow else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (running) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Text(
                        "차단 목록에 있는 앱만 인터넷이 끊깁니다. 다른 앱의 통신은 건드리지 않습니다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!running) {
                        Button(onClick = startFirewall, enabled = blocked.isNotEmpty()) { Text("차단 켜기") }
                    } else {
                        OutlinedButton(onClick = { MonitorVpnService.stop(context) }) { Text("차단 끄기") }
                    }
                    blocked.sorted().forEach { pkg ->
                        val label = apps.firstOrNull { it.packageName == pkg }?.label ?: pkg
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(label)
                                Text(
                                    "인터넷 차단 중",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = { BlockList.set(context, pkg, false) },
                                modifier = Modifier.semantics { contentDescription = "$label 차단 해제" },
                            ) { Text("차단 해제") }
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionTitle("앱별 데이터 사용량")
                Text(
                    "보낸 양(↑)이 많은 의심 앱은 개인정보를 내보내고 있을 수 있습니다. 통신 상대(IP·국가)는 이 화면에서 볼 수 없습니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (!hasAccess) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("사용량을 보려면 '사용 정보 접근'을 허용해야 합니다.")
                        Button(onClick = {
                            accessSettingsFailed = try {
                                context.startActivity(UsageStats.accessSettingsIntent())
                                false
                            } catch (e: ActivityNotFoundException) {
                                Log.w("PG-Monitor", "사용 정보 접근 설정 없음", e)
                                true
                            }
                        }) { Text("설정 열기") }
                        if (accessSettingsFailed) {
                            ErrorText("설정 화면을 열 수 없습니다. 설정 > 앱 > 특별한 접근 > 사용 정보 접근에서 '피싱앱 검사기'를 직접 허용하세요.")
                        }
                    }
                }
            }
        } else {
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(1 to "24시간", 7 to "7일", 30 to "30일").forEach { (d, l) ->
                        FilterChip(selected = periodDays == d, onClick = { periodDays = d }, label = { Text(l) })
                    }
                }
            }
            if (rows.isEmpty()) {
                item {
                    Text(
                        if (loading) "사용량을 불러오는 중…" else "이 기간에 데이터를 쓴 앱이 없습니다.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            items(rows, key = { it.uid }) { r -> UsageRowItem(r, blocked, onOpenApp) }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("접속 IP·국가까지 확인하려면", first = true)
                    Text(
                        "오픈소스 앱 PCAPdroid를 함께 쓰세요. 앱별 접속 IP·도메인·국가를 보여주고 CSV로 내보낼 수 있습니다. " +
                            "안드로이드는 VPN을 하나만 켤 수 있어서, PCAPdroid를 쓰는 동안에는 이 앱의 인터넷 차단이 꺼집니다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = {
                        val launch = context.packageManager.getLaunchIntentForPackage(PCAPDROID_PKG)
                        val intent = launch ?: Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=$PCAPDROID_PKG"),
                        )
                        pcapdroidFailed = try {
                            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            false
                        } catch (e: ActivityNotFoundException) {
                            Log.w("PG-Monitor", "PCAPdroid 열기 실패", e)
                            true
                        }
                    }) { Text("PCAPdroid 열기/설치") }
                    if (pcapdroidFailed) {
                        ErrorText("PCAPdroid를 열 수 없습니다. Play 스토어나 F-Droid에서 'PCAPdroid'를 검색해 설치하세요.")
                    }
                }
            }
        }
        item { Spacer(Modifier.padding(8.dp)) }
    }
}

@Composable
private fun UsageRowItem(r: UsageRow, blocked: Set<String>, onOpenApp: (String) -> Unit) {
    val context = LocalContext.current
    val color = when (r.risk) {
        RiskLevel.HIGH -> RiskHigh
        RiskLevel.MEDIUM -> RiskMedium
        else -> MaterialTheme.colorScheme.onSurface
    }
    val pkg = r.pkg
    val openable = pkg != null && r.scanned
    val content: @Composable () -> Unit = {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.label, color = color, fontWeight = if (r.risk == RiskLevel.HIGH) FontWeight.Bold else FontWeight.Normal)
                r.risk?.let { Text("위험 ${it.label}", color = color, style = MaterialTheme.typography.labelMedium) }
                Text(
                    "↑ ${UsageStats.format(r.usage.totalTx)}  ↓ ${UsageStats.format(r.usage.totalRx)}" +
                        "  (Wi-Fi ↑${UsageStats.format(r.usage.wifiTx)} · 모바일 ↑${UsageStats.format(r.usage.mobileTx)})",
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                )
            }
            if (pkg != null && pkg != context.packageName && r.scanned) {
                Spacer(Modifier.width(8.dp))
                val isBlocked = pkg in blocked
                Column(
                    Modifier
                        .toggleable(value = isBlocked, role = Role.Switch, onValueChange = { BlockList.set(context, pkg, it) })
                        .semantics { contentDescription = "${r.label} 인터넷 차단" },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("차단", style = MaterialTheme.typography.labelMedium)
                    Switch(checked = isBlocked, onCheckedChange = null)
                }
            }
            if (openable) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
    }
    if (openable) {
        Card(onClick = { onOpenApp(pkg) }, enabled = openable, modifier = Modifier.fillMaxWidth()) { content() }
    } else {
        Card(Modifier.fillMaxWidth()) { content() }
    }
}
