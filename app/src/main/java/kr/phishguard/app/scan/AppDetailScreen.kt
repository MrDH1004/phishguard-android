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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.phishguard.app.net.PcapdroidMonitorSection
import kr.phishguard.app.remove.EvidenceSaveSection
import kr.phishguard.app.ui.ErrorText
import kr.phishguard.app.ui.RiskHigh
import kr.phishguard.app.ui.SectionTitle

private const val TAG = "AppDetail"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(packageName: String, onStartRemoval: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val apps by ScanRepository.apps.collectAsStateWithLifecycle()
    val app = apps.firstOrNull { it.packageName == packageName }
    var resolving by remember { mutableStateOf(app == null) }
    var settingsOpenFailed by remember { mutableStateOf(false) }

    LaunchedEffect(packageName) {
        if (ScanRepository.find(packageName) == null) {
            resolving = true
            withContext(Dispatchers.IO) { ScanRepository.rescanPackage(context, packageName) }
        }
        resolving = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(app?.label ?: "앱 정보", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        },
    ) { inner ->
        if (app == null) {
            Text(
                if (resolving) "앱 정보를 불러오는 중…" else "앱을 찾을 수 없습니다. 이미 삭제된 것 같습니다.",
                modifier = Modifier
                    .padding(inner)
                    .padding(16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            return@Scaffold
        }

        val isSelf = app.packageName == context.packageName
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (app.matchedRules.any { it.id == RiskRules.ID_DEFAULT_ROLE_HIJACK }) {
                DefaultRoleWarningCard(app)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app.packageName, size = 56.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(app.label, style = MaterialTheme.typography.titleLarge)
                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "버전 ${app.versionName ?: "알 수 없음"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionTitle("위험도")
            RiskBadge(app.riskLevel, app.riskScore)
            if (app.matchedRules.isEmpty()) {
                Text("해당되는 의심 규칙이 없습니다.", style = MaterialTheme.typography.bodyMedium)
            } else {
                app.matchedRules.forEach { r ->
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(r.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            if (r.points > 0) {
                                Text("+${r.points}", style = MaterialTheme.typography.bodyMedium, color = RiskHigh)
                            } else {
                                Text(
                                    "참고",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (r.plainExplanation.isNotEmpty()) {
                            Text(
                                r.plainExplanation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            when {
                isSelf -> Unit
                app.isSystem -> {
                    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("증거 저장 후 삭제하기") }
                    Text(
                        "시스템 앱은 삭제할 수 없습니다. 비활성화는 앱 정보 화면에서 확인하세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> Button(
                    onClick = { onStartRemoval(app.packageName) },
                    colors = ButtonDefaults.buttonColors(containerColor = RiskHigh),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("증거 저장 후 삭제하기") }
            }
            if (!isSelf) {
                EvidenceSaveSection(app)
                TrustSection(app)
            }
            OutlinedButton(
                onClick = { settingsOpenFailed = !openAppSettings(context, app.packageName) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("앱 정보 열기") }
            if (settingsOpenFailed) {
                ErrorText("앱 정보 화면을 열 수 없습니다. 설정 > 애플리케이션에서 '${app.label}'을 직접 찾아 여세요.")
            }

            if (!isSelf) {
                SectionTitle("통신 확인 (PCAPdroid)")
                PcapdroidMonitorSection(app.packageName, app.label)
            }

            SectionTitle("설치 정보")
            InfoRow("최초 설치", formatTime(app.firstInstallTime))
            InfoRow("마지막 업데이트", formatTime(app.lastUpdateTime))
            InfoRow("설치한 앱", app.installerPackage ?: "없음")
            InfoRow("설치 출처", app.installSource.label)

            SectionTitle("상태")
            val flags = buildList {
                if (app.isActiveDeviceAdmin) add("기기관리자 활성")
                if (app.hasEnabledAccessibility) add("접근성 활성")
                if (app.hasEnabledNotificationListener) add("알림 접근 활성")
                if (!app.hasLauncherIcon && !app.isSystem) add("아이콘 숨김")
                if (app.isDefaultDialer) add("기본 전화 앱")
                if (app.isDefaultSms) add("기본 문자 앱")
                if (app.declaresInCallService) add("전화 화면 대체 기능 있음")
                if (app.declaresCallRedirection) add("거는 번호 바꾸기 기능 있음")
                if (app.declaresCallScreening) add("걸려온 전화 가려내기 기능 있음")
            }
            if (flags.isEmpty()) StatusLine(Icons.Filled.Check, "특이 상태 없음", MaterialTheme.colorScheme.onSurface)
            flags.forEach { StatusLine(Icons.Filled.Warning, it, RiskHigh) }

            SectionTitle("권한 목록")
            if (app.permissions.isEmpty()) Text("요청한 권한이 없습니다.", style = MaterialTheme.typography.bodyMedium)
            val grouped = app.permissions.groupBy { it.category }
            PermCategory.values().forEach { cat ->
                grouped[cat]?.let { items -> PermissionGroup(cat.label, items, cat in PermissionMap.DANGEROUS) }
            }
            grouped[null]?.let { PermissionGroup("기타", it, false) }
        }
    }
}

/** 의심 앱이 기본 전화/문자 앱일 때 맨 위 경고. 112·은행 전화 가로채기를 설명하고 기본 앱 설정으로 보낸다. */
@Composable
private fun DefaultRoleWarningCard(app: AppInfo) {
    val context = LocalContext.current
    var openFailed by remember { mutableStateOf(false) }
    val role = when {
        app.isDefaultDialer && app.isDefaultSms -> "전화·문자"
        app.isDefaultSms -> "문자"
        else -> "전화"
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(24.dp))
                Text(
                    "이 앱이 기본 $role 앱입니다",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp).semantics { heading() },
                )
            }
            Text(
                "기본 전화·문자 앱은 모든 통화와 문자를 처리합니다. 사기범의 앱이 이 자리를 차지하면 " +
                    "112나 은행에 전화해도 사기범에게 연결되고, 은행 문자도 빼앗길 수 있습니다. " +
                    "기본 앱 설정에서 원래 쓰던 전화·문자 앱(예: 삼성 전화, 메시지)으로 바꾸세요.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = { openFailed = !openDefaultAppsSettings(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("기본 앱 설정 열기") }
            if (openFailed) {
                ErrorText("기본 앱 설정을 열 수 없습니다. 설정 > 애플리케이션 > 기본 앱 선택에서 직접 바꾸세요.")
            }
        }
    }
}

private fun openDefaultAppsSettings(context: android.content.Context): Boolean {
    val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "default apps settings unavailable", e)
        false
    }
}

/** 안전앱 표시/해제. 위험 높음·중간이면 한 번 더 확인한다. */
@Composable
private fun TrustSection(app: AppInfo) {
    val context = LocalContext.current
    val trusted by TrustList.trusted.collectAsStateWithLifecycle()
    val isTrusted = app.packageName in trusted
    var confirming by remember { mutableStateOf(false) }

    if (isTrusted) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Icon(
                Icons.Filled.Verified,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "안전앱으로 표시됨 · 검사 결과에서 제외됩니다",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        OutlinedButton(
            onClick = { TrustList.set(context, app.packageName, false) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("안전앱 표시 해제") }
    } else {
        OutlinedButton(
            onClick = {
                if (app.riskLevel == RiskLevel.LOW) TrustList.set(context, app.packageName, true) else confirming = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("안전앱으로 표시 (검사 결과에서 제외)") }
    }

    if (confirming) {
        TrustConfirmDialog(
            app = app,
            onConfirm = {
                TrustList.set(context, app.packageName, true)
                confirming = false
            },
            onDismiss = { confirming = false },
        )
    }
}

/** 아이콘 + 글자. 색만으로 의미를 전하지 않도록 글자가 상태를 말한다. 아이콘은 장식(contentDescription = null). */
@Composable
private fun StatusLine(icon: ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.35f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.65f))
    }
}

@Composable
private fun PermissionGroup(title: String, items: List<PermissionInfoItem>, dangerousCategory: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        items.forEach { p ->
            val name = p.name.removePrefix("android.permission.")
            when {
                p.granted && dangerousCategory ->
                    StatusLine(Icons.Filled.Warning, "위험 권한 허용 · $name", RiskHigh)
                p.granted ->
                    StatusLine(Icons.Filled.Check, "허용 · $name", MaterialTheme.colorScheme.onSurface)
                else ->
                    StatusLine(Icons.Filled.Close, "미허용 · $name", MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun openAppSettings(context: android.content.Context, pkg: String): Boolean {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "app settings unavailable for $pkg", e)
        false
    }
}
