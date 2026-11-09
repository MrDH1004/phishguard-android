package kr.phishguard.app.net

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.phishguard.app.scan.RiskLevel
import kr.phishguard.app.scan.ScanRepository
import kr.phishguard.app.ui.RiskHigh
import kr.phishguard.app.ui.RiskMedium

private data class UsageRow(
    val uid: Int,
    val pkg: String?,
    val label: String,
    val risk: RiskLevel?,
    val usage: UidUsage,
)

private const val PCAPDROID_PKG = "com.emanuelef.remote_capture"

@Composable
fun MonitorScreen(onOpenApp: (String) -> Unit) {
    val context = LocalContext.current
    val apps by ScanRepository.apps.collectAsState()
    val blocked by BlockList.blocked.collectAsState()
    val running by MonitorVpnService.running.collectAsState()
    var hasAccess by remember { mutableStateOf(UsageStats.hasAccess(context)) }
    var periodDays by remember { mutableIntStateOf(1) }
    var rows by remember { mutableStateOf<List<UsageRow>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val startFirewall = rememberFirewallStarter()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasAccess = UsageStats.hasAccess(context)
        refreshKey++
    }

    LaunchedEffect(hasAccess, periodDays, apps, refreshKey) {
        if (!hasAccess) { rows = emptyList(); return@LaunchedEffect }
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
                    UsageRow(u.uid, pkg, label, app?.riskLevel, u)
                }
                .sortedWith(compareBy<UsageRow> { it.risk?.ordinal ?: 3 }.thenByDescending { it.usage.totalTx })
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.padding(4.dp)) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("인터넷 차단", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (running) "켜짐 · ${blocked.size}개 앱 차단 중" else "꺼짐 · 차단 목록 ${blocked.size}개",
                        color = if (running) RiskHigh else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "차단 목록에 있는 앱만 인터넷이 끊깁니다. 다른 앱의 통신은 건드리지 않습니다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!running) {
                            Button(onClick = startFirewall, enabled = blocked.isNotEmpty()) { Text("차단 켜기") }
                        } else {
                            OutlinedButton(onClick = { MonitorVpnService.stop(context) }) { Text("차단 끄기") }
                        }
                    }
                    if (blocked.isNotEmpty()) {
                        blocked.sorted().forEach { pkg ->
                            val label = apps.firstOrNull { it.packageName == pkg }?.label ?: pkg
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, Modifier.weight(1f))
                                Switch(checked = true, onCheckedChange = { BlockList.set(context, pkg, false) })
                            }
                        }
                    }
                }
            }
        }
        item {
            Text("앱별 데이터 사용량", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "보낸 양(↑)이 많은 의심 앱은 개인정보를 내보내고 있을 수 있어요. 통신 상대(IP·국가)는 이 화면에서 볼 수 없습니다.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!hasAccess) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("사용량을 보려면 '사용 정보 접근' 허용이 필요해요.")
                        Button(onClick = {
                            try {
                                context.startActivity(UsageStats.accessSettingsIntent())
                            } catch (e: ActivityNotFoundException) {
                                Log.w("PG-Monitor", "사용 정보 접근 설정 없음", e)
                            }
                        }) { Text("설정 열기") }
                    }
                }
            }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1 to "24시간", 7 to "7일", 30 to "30일").forEach { (d, l) ->
                        FilterChip(selected = periodDays == d, onClick = { periodDays = d }, label = { Text(l) })
                    }
                }
            }
            if (rows.isEmpty()) {
                item { Text("표시할 사용량이 없어요.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(rows, key = { it.uid }) { r -> UsageRowItem(r, blocked, onOpenApp) }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("접속 IP·국가까지 확인하려면", fontWeight = FontWeight.Bold)
                    Text(
                        "오픈소스 앱 PCAPdroid를 함께 쓰세요. 앱별 접속 IP·도메인·국가를 보여주고 CSV로 내보낼 수 있어요. " +
                            "안드로이드는 VPN을 하나만 켤 수 있어서, PCAPdroid를 쓰는 동안에는 이 앱의 인터넷 차단이 꺼집니다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = {
                        val launch = context.packageManager.getLaunchIntentForPackage(PCAPDROID_PKG)
                        val intent = launch ?: Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=$PCAPDROID_PKG"),
                        )
                        try {
                            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: ActivityNotFoundException) {
                            Log.w("PG-Monitor", "PCAPdroid 열기 실패", e)
                        }
                    }) { Text("PCAPdroid 열기/설치") }
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
    Card(Modifier.fillMaxWidth().clickable(enabled = r.pkg != null && r.risk != null) { r.pkg?.let(onOpenApp) }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.label, color = color, fontWeight = if (r.risk == RiskLevel.HIGH) FontWeight.Bold else FontWeight.Normal)
                r.risk?.let { Text("위험도 ${it.label}", color = color, style = MaterialTheme.typography.labelSmall) }
                Text(
                    "↑ ${UsageStats.format(r.usage.totalTx)}  ↓ ${UsageStats.format(r.usage.totalRx)}" +
                        "  (Wi-Fi ↑${UsageStats.format(r.usage.wifiTx)} · 모바일 ↑${UsageStats.format(r.usage.mobileTx)})",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val pkg = r.pkg
            if (pkg != null && pkg != context.packageName && r.risk != null) {
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("차단", style = MaterialTheme.typography.labelSmall)
                    Switch(checked = pkg in blocked, onCheckedChange = { BlockList.set(context, pkg, it) })
                }
            }
        }
    }
}
