package kr.phishguard.app.family

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Slider
import androidx.core.app.NotificationManagerCompat
import androidx.compose.runtime.mutableIntStateOf
import kr.phishguard.app.guard.GuardPolicy
import kr.phishguard.app.guard.LockOverlay
import kr.phishguard.app.guard.OwnerMode
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
        "rejected" -> "자녀 폰이 이 연결을 거절했습니다. 자녀 폰에서 새 PIN을 받아 다시 입력하세요."
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
    val joinName = FamilyPolicy.pendingJoinName(s.events, s.paired)
    if (joinName != null) {
        JoinRequestCard(joinName, onAnswer = { approve, done ->
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { FamilyController.answerJoin(context, approve) }; error = null
                    done(true)
                } catch (e: IOException) {
                    Log.w("PG-Family", "answer failed", e); error = errorMessage(e)
                    done(false)
                }
            }
        })
        (error ?: s.lastError)?.let { ErrorText(it) }
        return
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
        withContext(Dispatchers.IO) { FamilyController.pollChild(context) }
        while (true) {
            now = System.currentTimeMillis(); delay(5_000)
        }
    }
    val who = s.otherName ?: "가족 폰"
    var cmdError by remember { mutableStateOf<String?>(null) }
    // 삭제 요청·정지·관리 해제 명령 보내기 공용 (실패하면 다시 누를 수 있게 done(false))
    val runCommand = { action: (Context) -> Unit, done: (Boolean) -> Unit ->
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action(context) }; cmdError = null
                done(true)
            } catch (e: IOException) {
                Log.w("PG-Family", "command request failed", e); cmdError = errorMessage(e)
                done(false)
            }
        }
        Unit
    }
    val sendUninstall = { app: AlertApp, done: (Boolean) -> Unit -> runCommand({ FamilyController.sendUninstall(it, app) }, done) }
    val sendSuspend = { app: AlertApp, suspend: Boolean, done: (Boolean) -> Unit ->
        runCommand({ FamilyController.sendSuspend(it, app, suspend) }, done)
    }
    FamilyPolicy.pendingOwnerReleaseRequest(s.events)?.let { req ->
        val unlink = req.data.optBoolean("unlink")
        // 연결 끊기 요청을 승인하면 자녀 쪽에서 끊는다 → 부모 폰은 unlinked를 받고 소유자 모드를 푼다
        OwnerReleaseRequestCard(who, unlink) { done ->
            runCommand({ if (unlink) FamilyController.unlink(it) else FamilyController.sendReleaseOwner(it) }, done)
        }
    }
    FamilyPolicy.pendingDecisions(s.events).forEach { d ->
        DecisionCard(d, who, now, FamilyPolicy.uninstallStatus(s.events, d.app.packageName), sendUninstall, onChoose = { type, done ->
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { FamilyController.sendCommand(context, type, d) }; cmdError = null
                    done(true)
                } catch (e: IOException) {
                    Log.w("PG-Family", "command failed", e); cmdError = errorMessage(e)
                    done(false) // 다시 누를 수 있게
                }
            }
        })
    }
    cmdError?.let { ErrorText(it) }
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
            FamilyPolicy.recentVpnRevoke(s.events, now)?.let { t ->
                Text("최근 ${ago(t, now)}: 부모 폰에서 다른 VPN 앱이 차단 VPN을 껐습니다. 전화로 확인하세요.", color = MaterialTheme.colorScheme.error)
            }
            if (s.parentOwnerMode) Text("기기 소유자 모드 켜짐: 확인 창 없이 삭제·정지할 수 있고, 부모 폰에서 이 앱을 지울 수 없습니다.")
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
                Text("출처: ${a.originText()}", style = MaterialTheme.typography.bodySmall)
                a.reasons.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
                UninstallRequest(a, who, FamilyPolicy.uninstallStatus(s.events, a.packageName), sendUninstall)
                if (s.parentOwnerMode) SuspendButtons(a, FamilyPolicy.suspendStatus(s.events, a.packageName), sendSuspend)
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
            FamilyEvent.TYPE_DECISION -> e.apps.firstOrNull()?.let { "결정 요청: ${it.label} (${it.riskScore}점)" }
            FamilyEvent.TYPE_OWNER_RELEASE_REQUEST -> "부모 폰이 관리 모드 해제를 요청함"
            FamilyEvent.TYPE_VPN_REVOKED -> "다른 VPN 앱이 차단 VPN을 끔"
            FamilyEvent.TYPE_COMMAND_SENT -> when (e.data.optString("type")) {
                "lock" -> "보냄: 1시간 잠금 (${e.data.optString("label")})"
                "block" -> "보냄: 인터넷만 차단 (${e.data.optString("label")})"
                "ignore" -> "보냄: 무시 (${e.data.optString("label")})"
                FamilyPolicy.TYPE_UNINSTALL -> "보냄: 삭제 요청 (${e.data.optString("label")})"
                FamilyPolicy.TYPE_SUSPEND -> "보냄: 앱 정지 (${e.data.optString("label")})"
                FamilyPolicy.TYPE_UNSUSPEND -> "보냄: 정지 해제 (${e.data.optString("label")})"
                FamilyPolicy.TYPE_RELEASE_OWNER -> "보냄: 부모 폰 관리 모드 해제"
                else -> null
            }
            FamilyEvent.TYPE_COMMAND_RESULT -> {
                val ok = if (e.data.optBoolean("ok")) "완료" else "일부 실패"
                when (e.data.optString("type")) {
                    "lock" -> "잠금 $ok: ${e.data.optString("message")}"
                    "block" -> "차단 $ok: ${e.data.optString("message")}"
                    "auto_block" -> "답이 없어 자동 차단: ${e.data.optString("message")}"
                    FamilyPolicy.TYPE_UNINSTALL -> "삭제 요청 (${e.data.optString("label")}): ${e.data.optString("message")}"
                    FamilyPolicy.TYPE_SUSPEND, FamilyPolicy.TYPE_UNSUSPEND -> "정지/해제 (${e.data.optString("label")}): ${e.data.optString("message")}"
                    FamilyPolicy.TYPE_RELEASE_OWNER -> "관리 모드 해제: ${e.data.optString("message")}"
                    else -> null
                }
            }
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
    if (!s.paired) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${who}의 승인을 기다리는 중", style = MaterialTheme.typography.titleMedium)
                Text("자녀 폰 화면에 뜬 '연결 요청'에서 승인을 누르면 연결이 끝납니다. 30분이 지나면 다시 PIN을 받아야 합니다.")
            }
        }
        s.lastError?.let { ErrorText(it) }
        UnlinkButton(onConfirm = { scope.launch { withContext(Dispatchers.IO) { FamilyController.unlink(context) } } })
        return
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${who}에게 연결됨", style = MaterialTheme.typography.titleMedium)
            Text("이 폰에 위험한 앱이 설치되면 ${who}에게 바로 알림이 갑니다.")
            Text("마지막으로 보낸 시각: ${fmt(s.lastSentAt)}")
            Text(
                "기기 소유자 모드: " + (if (OwnerMode.isOwner(context)) "켜짐" else "꺼짐 (설정 탭에서 켤 수 있음)"),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    s.lastError?.let { ErrorText(it) }
    BatteryOptimizationRow()
    HideNotificationsRow()
    OutlinedButton(enabled = !busy, onClick = {
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) { FamilyReporter.sync(context) }
            busy = false
        }
    }) { Text(if (busy) "보내는 중…" else "지금 상태 보내기") }
    GuardSettings(who)
    Text("모르는 사람이 '연결해 주겠다'며 PIN을 불러 주면 절대 입력하지 마세요. 연결 상대는 위에 보이는 이름 한 명뿐입니다.", style = MaterialTheme.typography.bodySmall)
    if (FamilyPolicy.parentUnlink(OwnerMode.isOwner(context), s.paired) == FamilyPolicy.ParentUnlink.ASK_CHILD) {
        ParentUnlinkRequest(who)
    } else {
        UnlinkButton(onConfirm = { scope.launch { withContext(Dispatchers.IO) { FamilyController.unlink(context) } } })
    }
}

/** 부모 폰(기기 소유자 모드): 연결 끊기는 자녀 승인이 있어야 한다. 요청을 보내고 승인을 기다린다 */
@Composable
private fun ParentUnlinkRequest(who: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var requestedAt by remember { mutableLongStateOf(FamilyStore.unlinkRequestedAt(context)) }
    var ask by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = { ask = true }) {
        Text(if (requestedAt > 0) "연결 끊기 다시 요청" else "연결 끊기", color = MaterialTheme.colorScheme.error)
    }
    if (requestedAt > 0) Text("${fmt(requestedAt)}에 ${who}에게 연결 끊기 승인을 요청했습니다. ${who}에서 승인하면 끊깁니다. (승인 대기 중)")
    error?.let { ErrorText(it) }
    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text("연결 끊기를 요청할까요?") },
            text = { Text("기기 소유자 모드가 켜져 있어 ${who}의 승인이 있어야 끊을 수 있습니다. ${who}에 요청을 보냅니다.") },
            confirmButton = {
                TextButton(onClick = {
                    ask = false
                    scope.launch {
                        error = try {
                            withContext(Dispatchers.IO) { FamilyController.requestOwnerRelease(context, unlink = true) }
                            requestedAt = FamilyStore.unlinkRequestedAt(context)
                            null
                        } catch (e: IOException) {
                            Log.w("PG-Family", "unlink request failed", e)
                            errorMessage(e)
                        }
                    }
                }) { Text("요청 보내기") }
            },
            dismissButton = { TextButton(onClick = { ask = false }) { Text("취소") } },
        )
    }
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

/**
 * 부모 폰: 알림을 완전히 끄는 안내. 사기범이 원격으로 폰을 보며 눈에 띄는 앱을 지우기 때문.
 * 알림을 꺼도 포그라운드 서비스(실시간 감지·연결)는 그대로 돈다 (Android 13+는 알림창에도 안 보임).
 */
@Composable
private fun HideNotificationsRow() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var error by remember { mutableStateOf(false) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        lifecycle.lifecycle.currentStateFlow.collect {
            enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }
    SectionTitle("알림 완전히 숨기기")
    Text(
        "사기범은 원격으로 폰을 조종하면서 모르는 앱을 찾아 지웁니다. 이 폰에서는 원래 알림을 띄우지 않지만, " +
            "'동기화 · 실행 중' 표시까지 없애려면 이 앱의 알림을 꺼 주세요. 알림을 꺼도 감지와 가족 연결은 계속 동작합니다.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        if (enabled) "지금: 알림 켜짐" else "지금: 알림 꺼짐 (숨김 완료)",
        fontWeight = FontWeight.Bold,
        color = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
    OutlinedButton(onClick = {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            error = false
        } catch (e: ActivityNotFoundException) {
            Log.w("PG-Family", "notification settings not found", e)
            error = true
        }
    }) { Text(if (enabled) "알림 끄러 가기" else "알림 설정 열기") }
    if (error) ErrorText("설정 화면을 열 수 없습니다. 설정 → 애플리케이션 → 이 앱 → 알림에서 끄세요.")
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

@Composable
private fun JoinRequestCard(parentName: String, onAnswer: (approve: Boolean, done: (ok: Boolean) -> Unit) -> Unit) {
    var busy by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("연결 요청", style = MaterialTheme.typography.titleMedium)
            Text("'$parentName' 폰이 PIN을 입력했습니다.", fontWeight = FontWeight.Bold)
            Text("내가 PIN을 알려 준 부모님 폰이 맞으면 승인하세요. 모르는 이름이면 거절하세요. 승인하면 이 폰에서 그 폰을 잠그거나 인터넷을 끊을 수 있습니다.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { busy = true; onAnswer(true) { ok -> if (!ok) busy = false } }) { Text("승인") }
                OutlinedButton(enabled = !busy, onClick = { busy = true; onAnswer(false) { ok -> if (!ok) busy = false } }) { Text("거절") }
            }
        }
    }
}

@Composable
private fun DecisionCard(
    d: PendingDecision,
    who: String,
    now: Long,
    uninstallStatus: String?,
    onUninstall: (AlertApp, (Boolean) -> Unit) -> Unit,
    onChoose: (type: String, done: (ok: Boolean) -> Unit) -> Unit,
) {
    var busy by remember(d.requestId) { mutableStateOf(false) }
    val left = d.autoBlockAt - now
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("⚠ 결정 필요: $who", style = MaterialTheme.typography.titleMedium)
            Text("${d.app.label} · 위험 ${d.app.riskScore}점", fontWeight = FontWeight.Bold)
            Text("출처: ${d.app.originText()}", style = MaterialTheme.typography.bodySmall)
            if (d.reason == "surge") Text("점수가 ${d.previousScore}점에서 ${d.app.riskScore}점으로 급하게 올랐습니다.")
            d.app.reasons.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
            Text(
                if (left > 0) {
                    "${left / 60_000 + 1}분 안에 고르지 않으면 이 앱의 인터넷만 자동으로 끊습니다."
                } else {
                    "답이 없어 곧 인터넷만 자동으로 끊습니다."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            val choose = { type: String -> busy = true; onChoose(type) { ok -> if (!ok) busy = false } }
            Button(enabled = !busy, onClick = { choose("lock") }, modifier = Modifier.fillMaxWidth()) {
                Text("1시간 잠금 (차단 + 증거 저장 + 잠금)")
            }
            OutlinedButton(enabled = !busy, onClick = { choose("block") }, modifier = Modifier.fillMaxWidth()) {
                Text("인터넷만 차단 (이 앱 + 텔레그램·LINE)")
            }
            TextButton(enabled = !busy, onClick = { choose("ignore") }) { Text("괜찮음 (무시)") }
            UninstallRequest(d.app, who, uninstallStatus, onUninstall)
        }
    }
}

/** 자녀: 기기 소유자 모드인 부모 폰의 앱 정지/해제. 정지된 앱은 실행되지 않지만 지워지지 않아 증거가 남는다 */
@Composable
private fun SuspendButtons(app: AlertApp, status: String?, onSend: (AlertApp, Boolean, (Boolean) -> Unit) -> Unit) {
    var busy by remember(app.packageName) { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy, onClick = { busy = true; onSend(app, true) { busy = false } }) { Text("정지") }
        TextButton(enabled = !busy, onClick = { busy = true; onSend(app, false) { busy = false } }) { Text("정지 해제") }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

/** 자녀: 부모 폰이 기기 소유자 모드 해제를 요청함. 사기범이 시켰을 수 있으니 전화로 확인한 뒤 승인 */
@Composable
private fun OwnerReleaseRequestCard(who: String, unlink: Boolean, onApprove: (done: (Boolean) -> Unit) -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (unlink) "연결 끊기 요청: $who" else "관리 모드 해제 요청: $who", style = MaterialTheme.typography.titleMedium)
            if (unlink) Text("부모 폰에서 가족 연결을 끊어 달라고 합니다. 승인하면 연결이 끊기고 부모 폰의 기기 소유자 모드도 풀립니다.")
            Text("부모 폰에서 기기 소유자 모드를 풀어 달라고 합니다. 풀면 부모 폰에서 이 앱을 지울 수 있게 되고, 확인 창 없는 삭제·정지를 못 합니다. 부모님이 직접 요청했는지 전화로 확인하세요.")
            Button(enabled = !busy, onClick = { ask = true }) { Text(if (unlink) "연결 끊기 승인" else "해제 승인") }
        }
    }
    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text("관리 모드를 해제할까요?") },
            text = { Text("사기범이 부모님께 시켜서 요청했을 수 있습니다. 부모님과 통화로 확인했을 때만 승인하세요.") },
            confirmButton = { TextButton(onClick = { ask = false; busy = true; onApprove { busy = false } }) { Text("해제") } },
            dismissButton = { TextButton(onClick = { ask = false }) { Text("취소") } },
        )
    }
}

/**
 * 자녀: 부모 폰에 앱 삭제 요청. 일반 앱은 다른 앱을 몰래 지울 수 없어서, 부모 폰에 시스템 삭제 확인 창이 뜨고
 * 옆에 있는 사람이 '확인'을 눌러야 지워진다. 결과는 아래 상태 줄과 알림으로 온다.
 */
@Composable
private fun UninstallRequest(app: AlertApp, who: String, status: String?, onSend: (AlertApp, (Boolean) -> Unit) -> Unit) {
    var ask by remember(app.packageName) { mutableStateOf(false) }
    var busy by remember(app.packageName) { mutableStateOf(false) }
    OutlinedButton(enabled = !busy, onClick = { ask = true }) { Text(if (busy) "보내는 중…" else "삭제 요청") }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text("'${app.label}' 삭제를 요청할까요?") },
            text = {
                Text(
                    "${who}에 앱 삭제 확인 창이 뜹니다. 휴대폰이 앱을 몰래 지우는 것은 막혀 있어서, 부모님이나 옆에 있는 사람이 '확인'을 눌러야 지워집니다. " +
                        "전화로 '지금 뜨는 창에서 확인을 누르세요'라고 알려 주세요. 2분 안에 지워졌는지 알려 드립니다.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ask = false
                    busy = true
                    onSend(app) { busy = false }
                }) { Text("삭제 요청") }
            },
            dismissButton = { TextButton(onClick = { ask = false }) { Text("취소") } },
        )
    }
}

/** 부모 폰: 결정 요청 기준과 잠금·차단에 필요한 권한 */
@Composable
private fun GuardSettings(who: String) {
    val context = LocalContext.current
    var threshold by remember { mutableIntStateOf(FamilyStore.threshold(context)) }
    var canDraw by remember { mutableStateOf(LockOverlay.canDraw(context)) }
    var vpnReady by remember { mutableStateOf(VpnService.prepare(context) == null) }
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vpnReady = VpnService.prepare(context) == null
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        lifecycle.lifecycle.currentStateFlow.collect {
            canDraw = LockOverlay.canDraw(context)
            vpnReady = VpnService.prepare(context) == null
        }
    }
    SectionTitle("위험할 때 ${who}에게 묻기")
    Text("위험 점수가 아래 기준 이상이거나, 한 번에 ${GuardPolicy.SURGE_DELTA}점 넘게 오르면 ${who}에게 '잠금 / 인터넷만 차단 / 무시'를 묻습니다. 30분 안에 답이 없으면 인터넷만 자동으로 끊습니다.")
    Text("기준: ${threshold}점", fontWeight = FontWeight.Bold)
    Slider(
        value = threshold.toFloat(),
        onValueChange = { threshold = GuardPolicy.clampThreshold(it.toInt()) },
        onValueChangeFinished = { FamilyStore.setThreshold(context, threshold) },
        valueRange = GuardPolicy.THRESHOLD_MIN.toFloat()..GuardPolicy.THRESHOLD_MAX.toFloat(),
        steps = (GuardPolicy.THRESHOLD_MAX - GuardPolicy.THRESHOLD_MIN) / GuardPolicy.THRESHOLD_STEP - 1,
    )
    if (!canDraw) {
        Text("잠금 화면을 띄우려면 '다른 앱 위에 그리기'를 허용해야 합니다.", color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: ActivityNotFoundException) {
                Log.w("PG-Family", "overlay settings not found", e)
            }
        }) { Text("다른 앱 위에 그리기 허용") }
    } else {
        Text("잠금 화면: 준비됨", style = MaterialTheme.typography.bodySmall)
    }
    if (!vpnReady) {
        Text("인터넷을 끊으려면 처음 한 번 'VPN 연결 요청'에 확인을 눌러야 합니다.", color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = {
            val i = VpnService.prepare(context)
            if (i == null) vpnReady = true else vpnLauncher.launch(i)
        }) { Text("인터넷 차단 준비 (VPN 허용)") }
    } else {
        Text("인터넷 차단: 준비됨", style = MaterialTheme.typography.bodySmall)
    }
}
