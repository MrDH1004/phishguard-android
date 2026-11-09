package kr.phishguard.app.scan

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.phishguard.app.ui.RiskHigh

private const val TAG = "AppDetail"

@Composable
fun AppDetailScreen(packageName: String, onStartRemoval: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val apps by ScanRepository.apps.collectAsStateWithLifecycle()
    val app = apps.firstOrNull { it.packageName == packageName }
    var resolving by remember { mutableStateOf(app == null) }

    LaunchedEffect(packageName) {
        if (ScanRepository.find(packageName) == null) {
            resolving = true
            withContext(Dispatchers.IO) { ScanRepository.rescanPackage(context, packageName) }
        }
        resolving = false
    }

    if (app == null) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (resolving) "앱 정보를 불러오는 중..." else "앱을 찾을 수 없음 (이미 삭제됨)")
            OutlinedButton(onClick = onBack) { Text("뒤로") }
        }
        return
    }

    val isSelf = app.packageName == context.packageName
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app.packageName, size = 56.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(app.label, style = MaterialTheme.typography.titleLarge)
                Text(app.packageName, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                Text("버전 ${app.versionName ?: "알 수 없음"}", fontSize = 12.sp)
            }
        }

        Section("위험도")
        Row(verticalAlignment = Alignment.CenterVertically) {
            RiskBadge(app.riskLevel, app.riskScore)
            Text("  ${app.riskLevel.label} · ${app.riskScore}점")
        }
        if (app.matchedRules.isEmpty()) {
            Text("해당되는 의심 규칙이 없습니다.", fontSize = 13.sp)
        } else {
            app.matchedRules.forEach { r ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(r.description, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("+${r.points}", fontSize = 13.sp, color = RiskHigh)
                }
            }
        }

        Section("설치 정보")
        InfoRow("최초 설치", formatTime(app.firstInstallTime))
        InfoRow("마지막 업데이트", formatTime(app.lastUpdateTime))
        InfoRow("설치한 앱", app.installerPackage ?: "없음")
        InfoRow("설치 출처", app.installSource.label)

        Section("상태")
        val flags = buildList {
            if (app.isActiveDeviceAdmin) add("기기관리자 활성")
            if (app.hasEnabledAccessibility) add("접근성 활성")
            if (app.hasEnabledNotificationListener) add("알림접근 활성")
            if (!app.hasLauncherIcon && !app.isSystem) add("아이콘 숨김")
        }
        if (flags.isEmpty()) Text("특이 상태 없음", fontSize = 13.sp)
        flags.forEach { Text("• $it", fontSize = 13.sp, color = RiskHigh) }

        Section("권한 목록")
        if (app.permissions.isEmpty()) Text("요청한 권한이 없습니다.", fontSize = 13.sp)
        val grouped = app.permissions.groupBy { it.category }
        PermCategory.values().forEach { cat ->
            grouped[cat]?.let { items -> PermissionGroup(cat.label, items, cat in PermissionMap.DANGEROUS) }
        }
        grouped[null]?.let { PermissionGroup("기타", it, false) }

        HorizontalDivider()
        OutlinedButton(
            onClick = { openAppSettings(context, app.packageName) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("앱 정보 열기") }

        when {
            isSelf -> Unit
            app.isSystem -> {
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("증거 저장 후 삭제하기") }
                Text("시스템 앱은 삭제할 수 없습니다. 비활성화는 앱 정보 화면에서 확인하세요.", fontSize = 12.sp)
            }
            else -> Button(
                onClick = { onStartRemoval(app.packageName) },
                colors = ButtonDefaults.buttonColors(containerColor = RiskHigh),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("증거 저장 후 삭제하기") }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("뒤로") }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(0.35f), color = MaterialTheme.colorScheme.outline)
        Text(value, fontSize = 13.sp, modifier = Modifier.weight(0.65f))
    }
}

@Composable
private fun PermissionGroup(title: String, items: List<PermissionInfoItem>, dangerousCategory: Boolean) {
    Column {
        Text(title, fontSize = 14.sp, style = MaterialTheme.typography.labelLarge)
        items.forEach { p ->
            val mark = if (p.granted) "✔ 허용" else "✘ 미허용"
            val color = when {
                p.granted && dangerousCategory -> RiskHigh
                p.granted -> MaterialTheme.colorScheme.onSurface
                else -> Color.Gray
            }
            Text("$mark  ${p.name.removePrefix("android.permission.")}", fontSize = 12.sp, color = color)
        }
    }
}

private fun openAppSettings(context: android.content.Context, pkg: String) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "app settings unavailable for $pkg", e)
    }
}
