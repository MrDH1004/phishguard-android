package kr.phishguard.app.scan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ScanFilter(val label: String) {
    ALL("전체"), SUSPICIOUS("의심(중간 이상)"), RECENT("최근 설치"), HIDDEN_ICON("아이콘 숨김")
}

private val RECENT_DAY_OPTIONS = listOf(1, 3, 7, 30)

@Composable
fun ScanScreen(onOpenApp: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apps by ScanRepository.apps.collectAsStateWithLifecycle()
    var scanning by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf(ScanFilter.ALL) }
    var includeSystem by rememberSaveable { mutableStateOf(false) }
    var includeTrusted by rememberSaveable { mutableStateOf(false) }
    val trusted by TrustList.trusted.collectAsStateWithLifecycle()
    var recentDays by rememberSaveable { mutableIntStateOf(7) }
    val defaults by ScanRepository.defaults.collectAsStateWithLifecycle()

    fun startScan() {
        if (scanning) return
        scanning = true
        done = 0
        total = 0
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ScanRepository.scan(context) { d, t ->
                        done = d
                        total = t
                    }
                }
            } finally {
                scanning = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (ScanRepository.apps.value.isEmpty()) startScan()
    }

    val now = System.currentTimeMillis()
    val base = TrustFilter.visibleApps(apps, trusted, includeTrusted, includeSystem)
    val counts = TrustFilter.summaryCounts(apps, trusted, includeSystem)
    val presentTrusted = TrustFilter.presentTrusted(apps, trusted, includeSystem)
    val matchesFilter: (AppInfo) -> Boolean = {
        when (filter) {
            ScanFilter.ALL -> true
            ScanFilter.SUSPICIOUS -> it.riskLevel != RiskLevel.LOW
            ScanFilter.RECENT ->
                now - it.firstInstallTime <= recentDays * RiskRules.DAY_MS ||
                    now - it.lastUpdateTime <= recentDays * RiskRules.DAY_MS
            ScanFilter.HIDDEN_ICON -> !it.hasLauncherIcon
        }
    }
    val hiddenTrusted = if (includeTrusted) 0 else presentTrusted.count(matchesFilter)
    val trustedNote = if (hiddenTrusted > 0) " (아는 앱 ${hiddenTrusted}개 제외)" else ""
    val shown = base
        .filter(matchesFilter)
        .sortedWith(
            compareByDescending<AppInfo> { it.riskScore }
                .thenByDescending { it.firstInstallTime },
        )

    val emptyMessage = when {
        apps.isEmpty() -> "아직 검사하지 않았습니다. 위의 '검사 시작'을 눌러 설치된 앱을 확인하세요."
        filter == ScanFilter.SUSPICIOUS -> "위험도 중간 이상인 앱이 없습니다.$trustedNote"
        filter == ScanFilter.RECENT -> "최근 ${recentDays}일 안에 설치하거나 업데이트한 앱이 없습니다.$trustedNote"
        filter == ScanFilter.HIDDEN_ICON -> "아이콘을 숨긴 앱이 없습니다.$trustedNote"
        else -> "표시할 앱이 없습니다.$trustedNote '시스템 앱 포함'을 켜면 시스템 앱도 볼 수 있습니다."
    }
    val rowPadding = Modifier.padding(horizontal = 16.dp)
    // 아는 앱으로 표시하지 않은 앱 중 기본 전화/문자 앱을 차지한 의심 앱
    val roleHijackers = apps.filter {
        it.packageName !in trusted && it.matchedRules.any { r -> r.id == RiskRules.ID_DEFAULT_ROLE_HIJACK }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(roleHijackers, key = { "hijack_${it.packageName}" }) { app ->
            RoleHijackBanner(app, onClick = { onOpenApp(app.packageName) }, modifier = rowPadding.padding(bottom = 8.dp))
        }
        item(key = "scan_button") {
            Button(onClick = { startScan() }, enabled = !scanning, modifier = rowPadding.fillMaxWidth()) {
                Text(if (scanning) "검사 중…" else if (apps.isEmpty()) "검사 시작" else "다시 검사")
            }
        }
        defaults?.let { d ->
            item(key = "default_apps") {
                Text(
                    "기본 전화: ${d.dialerLabel ?: "없음"} · 기본 문자: ${d.smsLabel ?: "없음"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = rowPadding.padding(top = 8.dp),
                )
            }
        }
        if (scanning) {
            item(key = "progress") {
                Column(rowPadding.fillMaxWidth().padding(top = 8.dp)) {
                    if (total > 0) {
                        LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "$done / $total",
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        item(key = "summary") {
            Row(
                rowPadding.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                SummaryCount("위험 높음", counts.high, RiskLevel.HIGH)
                SummaryCount("위험 중간", counts.medium, RiskLevel.MEDIUM)
                SummaryCount("위험 낮음", counts.low, RiskLevel.LOW)
            }
            Text(
                "아는 앱은 앱을 눌러 '아는 앱으로 표시'하면 결과에서 빠집니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = rowPadding.padding(bottom = 8.dp),
            )
        }
        item(key = "filters") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ScanFilter.values().toList(), key = { it.name }) { f ->
                    val chipLabel = if (f == ScanFilter.RECENT && filter == f) "최근 ${recentDays}일 설치" else f.label
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(chipLabel) })
                }
            }
        }
        if (filter == ScanFilter.RECENT) {
            item(key = "recent_days") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(RECENT_DAY_OPTIONS, key = { it }) { d ->
                        FilterChip(selected = recentDays == d, onClick = { recentDays = d }, label = { Text("${d}일") })
                    }
                }
            }
        }
        item(key = "include_system") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = includeSystem, role = Role.Switch, onValueChange = { includeSystem = it })
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("시스템 앱 포함", modifier = Modifier.weight(1f))
                Switch(checked = includeSystem, onCheckedChange = null)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = includeTrusted, role = Role.Switch, onValueChange = { includeTrusted = it })
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("아는 앱 포함 (${presentTrusted.size}개)", modifier = Modifier.weight(1f))
                Switch(checked = includeTrusted, onCheckedChange = null)
            }
            HorizontalDivider()
        }
        if (shown.isEmpty() && !scanning) {
            item(key = "empty") {
                Text(emptyMessage, modifier = Modifier.padding(16.dp))
            }
        }
        items(shown, key = { it.packageName }) { app ->
            AppRow(app, isTrusted = app.packageName in trusted, onClick = { onOpenApp(app.packageName) })
            HorizontalDivider(rowPadding)
        }
    }
}

/** 기본 전화/문자 앱이 의심 앱으로 바뀌었을 때 맨 위 경고. 색 외에 아이콘과 글자로 알린다. */
@Composable
private fun RoleHijackBanner(app: AppInfo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(24.dp))
                Text(
                    "기본 전화/문자 앱이 의심 앱으로 바뀌어 있습니다: ${app.label}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("확인하기") }
        }
    }
}

@Composable
private fun SummaryCount(label: String, count: Int, level: RiskLevel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$count",
            style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
            color = riskColor(level),
        )
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppRow(app: AppInfo, isTrusted: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                app.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${formatTime(app.firstInstallTime)} · ${app.installSource.label}",
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val flags = if (isTrusted) listOf("아는 앱") + flagLabels(app) else flagLabels(app)
            if (flags.isNotEmpty()) {
                FlowRow(
                    Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    flags.forEach { flag ->
                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(
                                flag,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
        RiskBadge(app.riskLevel, app.riskScore)
    }
}

internal fun flagLabels(app: AppInfo): List<String> = buildList {
    if (!app.hasLauncherIcon && !app.isSystem) add("아이콘 숨김")
    if (app.isActiveDeviceAdmin) add("기기관리자")
    if (app.hasEnabledAccessibility) add("접근성")
    if (app.hasEnabledNotificationListener) add("알림 접근")
    if (app.isDefaultDialer && !app.isSystem) add("기본 전화 앱")
    if (app.isDefaultSms && !app.isSystem) add("기본 문자 앱")
    val granted = app.permissions.filter { it.granted }.mapNotNull { it.category }.toSet()
    if (PermCategory.SMS in granted) add("문자")
    if (PermCategory.CALL_LOG in granted) add("통화기록")
    if (PermCategory.PHONE in granted) add("전화")
    if (PermCategory.CONTACTS in granted) add("연락처")
    if (PermCategory.OVERLAY in granted) add("다른 앱 위에 그리기")
    if (PermCategory.INSTALL_PACKAGES in granted) add("앱 설치")
}
