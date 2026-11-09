package kr.phishguard.app.scan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var recentDays by rememberSaveable { mutableIntStateOf(7) }

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
    val base = if (includeSystem) apps else apps.filter { !it.isSystem }
    val high = base.count { it.riskLevel == RiskLevel.HIGH }
    val medium = base.count { it.riskLevel == RiskLevel.MEDIUM }
    val low = base.count { it.riskLevel == RiskLevel.LOW }
    val shown = base
        .filter {
            when (filter) {
                ScanFilter.ALL -> true
                ScanFilter.SUSPICIOUS -> it.riskLevel != RiskLevel.LOW
                ScanFilter.RECENT ->
                    now - it.firstInstallTime <= recentDays * RiskRules.DAY_MS ||
                        now - it.lastUpdateTime <= recentDays * RiskRules.DAY_MS
                ScanFilter.HIDDEN_ICON -> !it.hasLauncherIcon
            }
        }
        .sortedWith(
            compareByDescending<AppInfo> { it.riskScore }
                .thenByDescending { it.firstInstallTime },
        )

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Button(onClick = { startScan() }, enabled = !scanning, modifier = Modifier.fillMaxWidth()) {
            Text(if (scanning) "검사 중..." else if (apps.isEmpty()) "검사 시작" else "다시 검사")
        }
        if (scanning) {
            if (total > 0) {
                LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Text("$done / $total", fontSize = 12.sp)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            SummaryCount("높음", high, RiskLevel.HIGH)
            SummaryCount("중간", medium, RiskLevel.MEDIUM)
            SummaryCount("낮음", low, RiskLevel.LOW)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ScanFilter.values().forEach { f ->
                val chipLabel = if (f == ScanFilter.RECENT && filter == f) "최근 ${recentDays}일 설치" else f.label
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(chipLabel) })
            }
        }
        if (filter == ScanFilter.RECENT) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RECENT_DAY_OPTIONS.forEach { d ->
                    FilterChip(selected = recentDays == d, onClick = { recentDays = d }, label = { Text("${d}일") })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("시스템 앱 포함", modifier = Modifier.weight(1f))
            Switch(checked = includeSystem, onCheckedChange = { includeSystem = it })
        }
        HorizontalDivider()
        if (shown.isEmpty() && !scanning) {
            Text(
                if (apps.isEmpty()) "아직 검사 결과가 없습니다." else "조건에 맞는 앱이 없습니다.",
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.packageName }) { app ->
                AppRow(app, onClick = { onOpenApp(app.packageName) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SummaryCount(label: String, count: Int, level: RiskLevel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$count", fontSize = 24.sp, color = riskColor(level))
        Text(label, fontSize = 12.sp)
    }
}

@Composable
private fun AppRow(app: AppInfo, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                app.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
            )
            Text(
                "${formatTime(app.firstInstallTime)} · ${app.installSource.label}",
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val flags = flagLabels(app)
            if (flags.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    flags.forEach { SuggestionChip(onClick = onClick, label = { Text(it, fontSize = 11.sp) }) }
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
    if (app.hasEnabledNotificationListener) add("알림접근")
    val granted = app.permissions.filter { it.granted }.mapNotNull { it.category }.toSet()
    if (PermCategory.SMS in granted) add("문자")
    if (PermCategory.CALL_LOG in granted) add("통화기록")
    if (PermCategory.PHONE in granted) add("전화")
    if (PermCategory.CONTACTS in granted) add("연락처")
    if (PermCategory.OVERLAY in granted) add("화면 위 그리기")
    if (PermCategory.INSTALL_PACKAGES in granted) add("앱 설치")
}
