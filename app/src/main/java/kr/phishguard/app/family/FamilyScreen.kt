package kr.phishguard.app.family

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.phishguard.app.scan.RiskLevel
import kr.phishguard.app.ui.ErrorText
import kr.phishguard.app.ui.ScreenTitle
import kr.phishguard.app.ui.SectionTitle
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun fmt(ms: Long): String =
    if (ms <= 0) "-" else SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(ms))

private fun ago(ms: Long, now: Long): String {
    if (ms <= 0) return "기록 없음"
    val min = (now - ms) / 60_000
    return when {
        min < 1 -> "방금"
        min < 60 -> "${min}분 전"
        min < 24 * 60 -> "${min / 60}시간 전"
        else -> "${min / (24 * 60)}일 전"
    }
}

private fun errorMessage(e: Exception): String = when (e) {
    is FamilyApiException -> when (e.code) {
        "pin_not_found" -> "PIN이 맞지 않거나 10분이 지나 만료됐습니다. 자녀 폰의 PIN을 다시 확인하세요."
        "rate_limited" -> "PIN을 여러 번 틀렸습니다. 1시간 뒤에 다시 시도하세요."
        "gone" -> "PIN이 만료됐습니다. 자녀 폰에서 새 PIN을 만들어 다시 입력하세요."
        else -> e.message ?: "서버 오류"
    }
    else -> "인터넷에 연결할 수 없습니다. Wi-Fi나 모바일 데이터를 켜고 다시 시도하세요."
}

@Composable
fun FamilyScreen() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { FamilyStore.ensureLoaded(context) }
    val s by FamilyStore.state.collectAsStateWithLifecycle()
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("가족 연결")
        when {
            s.role == FamilyRole.NONE -> NotLinked()
            s.role == FamilyRole.CHILD && !s.paired -> ChildWaiting(s)
            s.role == FamilyRole.CHILD -> ChildLinked(s)
            else -> ParentLinked(s)
        }
        SectionTitle("보내는 정보")
        Text(
            "부모 폰에서 위험 중간 이상인 앱과 원격조종 앱의 이름·패키지명·위험도·근거, 그리고 3시간마다 '살아 있음' 신호만 보냅니다. " +
                "전화번호·연락처·통화기록·문자·위치는 보내지 않습니다. 서버(Cloudflare)에는 연결을 끊으면 모두 지워집니다.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun NotLinked() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Text("부모님 폰에 위험한 앱이 깔리면 자녀 폰으로 바로 알림이 옵니다. 두 폰 모두 이 앱을 깔고 아래 순서대로 연결하세요.")
    OutlinedTextField(
        value = name, onValueChange = { name = it.take(20) },
        label = { Text("이 폰 이름 (예: 아들 폰, 엄마 폰)") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )

    SectionTitle("1. 자녀 폰에서")
    Text("PIN 번호를 만들어 부모 폰에 알려 주세요. PIN은 10분 동안만 쓸 수 있습니다.")
    Button(
        enabled = !busy,
        onClick = {
            busy = true; error = null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { FamilyController.startAsChild(context, name.ifBlank { "자녀 폰" }) }
                } catch (e: IOException) {
                    Log.w("PG-Family", "start failed", e); error = errorMessage(e)
                } catch (e: org.json.JSONException) {
                    Log.w("PG-Family", "start bad response", e); error = "서버 응답이 올바르지 않습니다."
                } finally {
                    busy = false
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("가족 폰 지켜보기 — PIN 만들기") }

    SectionTitle("2. 부모 폰에서")
    Text("자녀 폰에 보이는 PIN 6자리를 입력하세요. 연결하면 이 폰의 위험 앱 알림이 자녀 폰으로 갑니다.")
    OutlinedTextField(
        value = pin, onValueChange = { v -> pin = v.filter { it.isDigit() }.take(6) },
        label = { Text("PIN 6자리") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 6.sp),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        enabled = !busy && pin.length == 6,
        onClick = {
            busy = true; error = null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { FamilyController.joinAsParent(context, pin, name.ifBlank { "부모 폰" }) }
                } catch (e: IOException) {
                    Log.w("PG-Family", "join failed", e); error = errorMessage(e)
                } catch (e: org.json.JSONException) {
                    Log.w("PG-Family", "join bad response", e); error = "서버 응답이 올바르지 않습니다."
                } finally {
                    busy = false
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("내 폰을 가족이 지켜보게 연결하기") }
    if (busy) Text("연결하는 중…")
    error?.let { ErrorText(it) }
}

@Composable
private fun ChildWaiting(s: FamilyState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        FamilyController.ensureServices(context)
        while (true) {
            now = System.currentTimeMillis(); delay(1000)
        }
    }
    val left = ((s.pinExpiresAt - now) / 1000).coerceAtLeast(0)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("부모 폰에 이 번호를 입력하세요")
            val pin = s.pin.orEmpty()
            Text(
                pin.chunked(3).joinToString(" "),
                fontSize = 44.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                modifier = Modifier.semantics { contentDescription = "PIN " + pin.toList().joinToString(" ") },
            )
            Text(if (left > 0) "남은 시간 ${left / 60}분 ${left % 60}초" else "만료됐습니다. 새 PIN을 만드세요.")
        }
    }
    Text("부모 폰에서 '피싱앱 검사기 → 가족 → 2. 부모 폰에서'에 PIN을 넣으면 자동으로 연결됩니다.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { FamilyController.refreshPin(context) }; error = null
                } catch (e: IOException) {
                    Log.w("PG-Family", "pin refresh failed", e); error = errorMessage(e)
                }
            }
        }) { Text("새 PIN") }
        TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { FamilyController.unlink(context) } } }) { Text("취소") }
    }
    (error ?: s.lastError)?.let { ErrorText(it) }
}

@Composable
private fun ChildLinked(s: FamilyState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        FamilyController.ensureServices(context)
        withContext(Dispatchers.IO) { if (!FamilyStore.state.value.live) FamilyController.pollChild(context) }
        while (true) {
            now = System.currentTimeMillis(); delay(30_000)
        }
    }
    val who = s.otherName ?: "가족 폰"
    val silent = s.lastHeartbeat > 0 && now - s.lastHeartbeat > 24L * 60 * 60 * 1000
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (silent) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$who 지켜보는 중", style = MaterialTheme.typography.titleMedium)
            Text("마지막 신호: ${ago(s.lastHeartbeat, now)} (${fmt(s.lastHeartbeat)})")
            Text("위험 높음 ${s.heartbeatHigh}개 · 중간 ${s.heartbeatMedium}개")
            Text(if (s.live) "실시간 연결됨" else "실시간 연결 끊김 — 다시 연결하는 중")
            if (silent) Text("하루 넘게 신호가 없습니다. 폰이 꺼졌거나 앱이 지워졌을 수 있으니 전화로 확인하세요.", color = MaterialTheme.colorScheme.error)
            if (!s.parentGuardOn) {
                Text(
                    "부모 폰의 실시간 설치 감지가 꺼져 있습니다. 알림이 최대 15분 늦을 수 있으니 부모 폰에서 피싱앱 검사기를 한 번 열어 달라고 하세요.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    s.lastError?.let { ErrorText(it) }
    BatteryOptimizationRow()

    SectionTitle("$who 의심 앱")
    val risky = FamilyPolicy.currentRiskyApps(s.events)
    if (risky.isEmpty()) Text("받은 의심 앱이 없습니다.")
    risky.forEach { a ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(a.label, fontWeight = FontWeight.Bold)
                Text(a.packageName, style = MaterialTheme.typography.bodySmall)
                Text(
                    "위험 ${a.riskLevel.label} · ${a.riskScore}점" + (if (a.remoteControl) " · 원격조종 앱" else ""),
                    color = if (a.riskLevel == RiskLevel.HIGH) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                a.reasons.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    Text("위험한 앱이 보이면 부모님께 전화해 비행기 모드를 켜게 하고, 112 또는 금융감독원 1332에 신고하세요.", style = MaterialTheme.typography.bodySmall)

    SectionTitle("받은 기록")
    s.events.sortedByDescending { it.id }.take(30).forEach { e ->
        val line = when (e.type) {
            FamilyEvent.TYPE_ALERT -> e.apps.firstOrNull()?.let { "새 의심 앱: ${it.label} (위험 ${it.riskLevel.label})" }
            FamilyEvent.TYPE_SNAPSHOT -> "연결 시 의심 앱 ${e.apps.size}개"
            FamilyEvent.TYPE_PAIRED -> "연결됨"
            FamilyEvent.TYPE_PARENT_SILENT -> "하루 넘게 신호 없음"
            FamilyEvent.TYPE_PARENT_BACK -> "신호 다시 들어옴"
            else -> null
        } ?: return@forEach
        Text("${fmt(e.ts)}  $line", style = MaterialTheme.typography.bodySmall)
    }
    UnlinkButton(onConfirm = { scope.launch { withContext(Dispatchers.IO) { FamilyController.unlink(context) } } })
}

@Composable
private fun ParentLinked(s: FamilyState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { FamilyController.ensureServices(context) }
    val who = s.otherName ?: "가족"
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${who}에게 연결됨", style = MaterialTheme.typography.titleMedium)
            Text("이 폰에 위험한 앱이 설치되면 ${who}에게 바로 알림이 갑니다.")
            Text("마지막으로 보낸 시각: ${fmt(s.lastSentAt)}")
        }
    }
    s.lastError?.let { ErrorText(it) }
    BatteryOptimizationRow()
    OutlinedButton(enabled = !busy, onClick = {
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) { FamilyReporter.sync(context) }
            busy = false
        }
    }) { Text(if (busy) "보내는 중…" else "지금 상태 보내기") }
    Text("모르는 사람이 '연결해 주겠다'며 PIN을 불러 주면 절대 입력하지 마세요. 연결 상대는 위에 보이는 이름 한 명뿐입니다.", style = MaterialTheme.typography.bodySmall)
    UnlinkButton(onConfirm = { scope.launch { withContext(Dispatchers.IO) { FamilyController.unlink(context) } } })
}

@Composable
private fun UnlinkButton(onConfirm: () -> Unit) {
    var ask by remember { mutableStateOf(false) }
    TextButton(onClick = { ask = true }) { Text("연결 끊기", color = MaterialTheme.colorScheme.error) }
    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text("연결을 끊을까요?") },
            text = { Text("상대 폰에도 연결이 끊겼다고 알립니다. 서버에 있던 기록도 모두 지워집니다.") },
            confirmButton = { TextButton(onClick = { ask = false; onConfirm() }) { Text("끊기") } },
            dismissButton = { TextButton(onClick = { ask = false }) { Text("취소") } },
        )
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun BatteryOptimizationRow() {
    val context = LocalContext.current
    var ignoring by remember { mutableStateOf(isIgnoringBattery(context)) }
    var error by remember { mutableStateOf(false) }
    if (ignoring) return
    Text("배터리 절약 때문에 알림이 늦어질 수 있습니다. 이 앱을 배터리 최적화에서 빼 주세요.", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = {
        try {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            Log.w("PG-Family", "battery settings not found", e)
            error = true
        }
        ignoring = isIgnoringBattery(context)
    }) { Text("배터리 최적화에서 빼기") }
    if (error) ErrorText("설정 화면을 열 수 없습니다. 설정 → 애플리케이션 → 피싱앱 검사기 → 배터리 → '제한 없음'을 고르세요.")
}

private fun isIgnoringBattery(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 23) return true
    val pm = context.getSystemService(PowerManager::class.java) ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}
