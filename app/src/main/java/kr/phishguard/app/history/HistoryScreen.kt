package kr.phishguard.app.history

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.phishguard.app.scan.InstallSource
import kr.phishguard.app.scan.RiskBadge
import kr.phishguard.app.scan.RiskLevel
import kr.phishguard.app.scan.ScanRepository
import kr.phishguard.app.scan.formatTime
import kr.phishguard.app.ui.ErrorText
import kr.phishguard.app.ui.ScreenTitle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "HistoryScreen"
private const val PLAY_STORE_PACKAGE = "com.android.vending"

private enum class HistoryFilter(val label: String) { ALL("전체"), REMOVED("삭제"), INSTALLED("설치"), UPDATED("업데이트") }

private fun HistoryFilter.matches(type: HistoryEventType): Boolean = when (this) {
    HistoryFilter.ALL -> true
    HistoryFilter.REMOVED -> type == HistoryEventType.REMOVED || type == HistoryEventType.INSTALLED_THEN_REMOVED
    HistoryFilter.INSTALLED -> type == HistoryEventType.INSTALLED
    HistoryFilter.UPDATED -> type == HistoryEventType.UPDATED
}

private fun HistoryEventType.icon(): ImageVector = when (this) {
    HistoryEventType.INSTALLED -> Icons.Outlined.Download
    HistoryEventType.UPDATED -> Icons.Outlined.Update
    HistoryEventType.REMOVED -> Icons.Outlined.Delete
    HistoryEventType.INSTALLED_THEN_REMOVED -> Icons.Outlined.DeleteSweep
}

@Composable
fun HistoryScreen(onOpenApp: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val events by HistoryStore.events.collectAsStateWithLifecycle()
    val baseline by HistoryStore.baselineCreatedAt.collectAsStateWithLifecycle()
    val apps by ScanRepository.apps.collectAsStateWithLifecycle()
    val installed = remember(apps) { apps.mapTo(HashSet()) { it.packageName } }
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var storeOpenFailed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { HistoryStore.ensureLoaded(context) }
    }

    fun checkNow() {
        if (checking) return
        checking = true
        status = "설치·삭제 변화를 확인하고 있습니다…"
        scope.launch {
            try {
                val before = HistoryStore.events.value.size
                withContext(Dispatchers.IO) { ScanRepository.scan(context) }
                val added = (HistoryStore.events.value.size - before).coerceAtLeast(0)
                status = if (added > 0) "확인을 마쳤습니다. 새 기록 ${added}건이 추가됐습니다." else "확인을 마쳤습니다. 새로 바뀐 앱이 없습니다."
            } finally {
                checking = false
            }
        }
    }

    val shown = events.filter { filter.matches(it.type) }
    val rowPadding = Modifier.padding(horizontal = 16.dp)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") { ScreenTitle("설치·삭제 기록", rowPadding) }
        item(key = "info") {
            Card(rowPadding.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val since = baseline?.let { SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date(it)) } ?: "오늘"
                    Text(
                        "기록은 ${since}부터 쌓입니다. 그 전에 지워진 앱은 Play 스토어 → 프로필 → 앱 및 기기 관리 → 관리 → " +
                            "'설치됨'을 '설치되지 않음'으로 바꾸면 확인할 수 있습니다.",
                    )
                    OutlinedButton(onClick = { storeOpenFailed = !openPlayStore(context) }) { Text("Play 스토어 열기") }
                    if (storeOpenFailed) {
                        ErrorText("Play 스토어를 열 수 없습니다. 홈 화면에서 Play 스토어 앱을 직접 열어 주세요.")
                    }
                }
            }
        }
        item(key = "notes") {
            Column(rowPadding, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "삭제 시각은 이 앱이 변화를 확인한 시각이며, 재부팅 전후로 설치했다가 바로 지운 앱은 잡히지 않을 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "앱 안의 데이터(로그인, 설정 등)는 삭제와 함께 지워져 복원되지 않습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "check") {
            Column(rowPadding.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { checkNow() }, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
                    Text(if (checking) "확인 중…" else "지금 확인")
                }
                if (status.isNotEmpty()) {
                    Text(status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
        item(key = "filters") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(HistoryFilter.values().toList(), key = { it.name }) { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                }
            }
        }
        if (shown.isEmpty()) {
            item(key = "empty") {
                Text(
                    if (events.isEmpty()) "아직 기록된 설치·삭제가 없습니다." else "이 분류에 해당하는 기록이 없습니다.",
                    modifier = rowPadding,
                )
            }
        }
        itemsIndexed(shown, key = { i, e -> "$i:${e.type}:${e.packageName}:${e.eventTime}:${e.detectedAt}" }) { _, e ->
            val isInstalledNow = e.packageName in installed
            val openable = isInstalledNow &&
                (e.type == HistoryEventType.INSTALLED || e.type == HistoryEventType.UPDATED)
            Column {
                HistoryRow(e, isInstalledNow, onClick = if (openable) ({ onOpenApp(e.packageName) }) else null)
                HorizontalDivider(rowPadding)
            }
        }
    }
}

@Composable
private fun HistoryRow(e: HistoryEvent, isInstalledNow: Boolean, onClick: (() -> Unit)?) {
    val base = Modifier.fillMaxWidth()
    val clickable = if (onClick != null) base.clickable(onClickLabel = "앱 상세 보기", onClick = onClick) else base
    Column(
        clickable.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(e.type.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(e.type.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text(e.label ?: e.packageName, style = MaterialTheme.typography.bodyLarge)
        Text(
            e.packageName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val time = formatTime(e.eventTime) + if (e.timeIsExact) "" else " (확인 시각)"
        val source = e.installSource?.label
        Text(
            if (source != null) "$time · $source" else time,
            style = MaterialTheme.typography.bodySmall,
        )
        val risk = e.riskLevel
        if (risk != null) {
            RiskBadge(risk)
        }
        if (e.type == HistoryEventType.REMOVED || e.type == HistoryEventType.INSTALLED_THEN_REMOVED) {
            RestoreArea(e, isInstalledNow)
        }
    }
}

@Composable
private fun RestoreArea(e: HistoryEvent, isInstalledNow: Boolean) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    val risk = e.riskLevel
    when {
        isInstalledNow -> Text("다시 설치됨", style = MaterialTheme.typography.labelLarge)
        risk == RiskLevel.HIGH || risk == RiskLevel.MEDIUM -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text("악성 의심 앱이라 다시 설치하지 않는 것을 권장합니다.")
        }
        e.installSource == InstallSource.PLAY_STORE -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { failed = !openStoreDetails(context, e.packageName) }) {
                Text("Play 스토어에서 다시 설치")
            }
            if (failed) {
                ErrorText("Play 스토어를 열 수 없습니다. Play 스토어나 인터넷 브라우저에서 앱 이름으로 직접 검색해 설치해 주세요.")
            }
        }
        else -> Text(
            "Play 스토어 설치 앱이 아니라 자동 복원할 수 없습니다. 원래 받은 곳에서 다시 설치해야 합니다.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Play 스토어 앱 상세 → 실패 시 웹 페이지. 둘 다 못 열면 false. */
private fun openStoreDetails(context: Context, pkg: String): Boolean {
    val uris = listOf("market://details?id=$pkg", "https://play.google.com/store/apps/details?id=$pkg")
    for (uri in uris) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (ex: ActivityNotFoundException) {
            Log.w(TAG, "no activity for $uri", ex)
        }
    }
    return false
}

private fun openPlayStore(context: Context): Boolean {
    val intent = context.packageManager.getLaunchIntentForPackage(PLAY_STORE_PACKAGE) ?: return false
    return try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (ex: ActivityNotFoundException) {
        Log.w(TAG, "Play Store launch failed", ex)
        false
    }
}
