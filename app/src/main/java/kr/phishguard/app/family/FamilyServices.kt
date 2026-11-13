package kr.phishguard.app.family

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kr.phishguard.app.guard.GuardController
import kr.phishguard.app.guard.LockOverlay
import kr.phishguard.app.scan.ScanRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 예상 못 한 예외로 상시 서비스 프로세스가 죽지 않게 기록만 남긴다 (리뷰 M2) */
private fun crashGuard(tag: String) = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
    Log.e(tag, "처리 중 예외", e)
}

/** 포그라운드 시작. 백그라운드라 막히면 사용자에게 앱을 열어 달라고 알리고 false */
private fun Service.startForegroundCompat(id: Int, title: String, text: String, role: FamilyRole): Boolean {
    val n = FamilyNotify.ongoing(this, title, text)
    return try {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(id, n)
        }
        true
    } catch (e: IllegalStateException) {
        if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
            Log.e("PG-Family", "포그라운드 서비스 시작 불가", e)
            FamilyController.notifyServiceBlocked(applicationContext, role)
            stopSelf()
            false
        } else {
            throw e
        }
    }
}

/**
 * 서버와의 실시간 연결 (자녀: 사건 수신, 부모: 승인·명령 수신). 두 서비스가 같이 쓴다.
 * 끊기면 2초부터 최대 60초 간격으로 다시 연결하고, 다시 연결할 때 [sinceId] 이후를 받아 온다.
 */
private class FamilySocket(
    private val context: Context,
    private val scope: CoroutineScope,
    private val tag: String,
    private val sinceId: () -> Long,
    private val onMessage: (JSONObject, Int) -> Unit,
    /** 서버에서 연결이 없어짐 (끊김·만료·거절). rejected = 자녀가 연결 요청을 거절함 */
    private val onGone: (rejected: Boolean) -> Unit,
) {
    // 공유 HTTP 클라이언트의 연결 풀·스레드를 같이 쓰되, WebSocket은 읽기 시간 제한 없음
    private val client = FamilyApi.http.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var lastPong = 0L

    @Volatile
    private var stopped = false

    @Volatile
    private var wake: (() -> Unit)? = null
    private var loopJob: Job? = null

    @Volatile
    var live = false
        private set

    fun start() {
        if (loopJob == null) loopJob = scope.launch { loop() }
    }

    /** 망이 바뀜: 이전 소켓은 죽었을 수 있으니 바로 새로 연결 */
    fun kick() {
        socket?.cancel()
        wake?.invoke()
    }

    fun stop() {
        stopped = true
        socket?.close(1000, null)
        // 공유 클라이언트의 스레드는 다른 요청도 쓰므로 종료하지 않는다
    }

    private suspend fun loop() {
        var backoff = MIN_BACKOFF_MS
        while (scope.isActive && !stopped) {
            val token = FamilyStore.token(context) ?: return
            val gen = FamilyStore.generation()
            val closed = CompletableDeferred<Int>()
            val req = Request.Builder()
                .url(FamilyApi.wsUrl(sinceId()))
                .header("authorization", "Bearer $token")
                .build()
            lastPong = System.currentTimeMillis()
            val openedAt = System.currentTimeMillis()
            if (stopped) return
            val ws = try {
                client.newWebSocket(req, Listener(closed, gen))
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                Log.w(TAG, "$tag: 종료 중이라 연결하지 않음", e) // stop() 직후 (리뷰 L5)
                return
            }
            socket = ws
            // 연결 유지: ping 간격을 스스로 조절한다 (KeepAlivePolicy). pong이 늦으면 끊고 다시 연결하고 간격을 줄인다.
            val pinger = scope.launch {
                var stable = 0
                while (isActive) {
                    val interval = KeepAlivePolicy.current(context)
                    delay(interval)
                    val sentAt = System.currentTimeMillis()
                    ws.send("ping")
                    delay(KeepAlivePolicy.PONG_WAIT_MS)
                    if (lastPong < sentAt) {
                        Log.w(TAG, "$tag: ${interval / 1000}초 간격에서 pong 없음, 간격 줄이고 다시 연결")
                        KeepAlivePolicy.onTimeout(context, interval)
                        ws.cancel()
                        break
                    }
                    stable++
                    if (KeepAlivePolicy.onStable(context, interval, stable)) stable = 0
                }
            }
            val code = closed.await()
            pinger.cancel()
            socket = null
            setLive(false)
            when (code) {
                CODE_GONE -> {
                    onGone(false)
                    return
                }
                CODE_REJECTED -> {
                    onGone(true)
                    return
                }
                CODE_AUTH_FAILED -> {
                    // 401이 서버의 "없는 연결"인지 확인한 뒤에만 지운다 (프록시·포털의 401일 수 있음)
                    if (confirmGone(token)) {
                        onGone(false)
                        return
                    }
                }
            }
            // 30초 넘게 잘 붙어 있었으면 빠르게 재연결, 아니면 간격을 늘림 (붙자마자 끊기는 상황의 무한 반복 방지)
            if (System.currentTimeMillis() - openedAt > STABLE_MS) backoff = MIN_BACKOFF_MS
            val wakeSignal = CompletableDeferred<Unit>()
            wake = { wakeSignal.complete(Unit) }
            withTimeoutOrNull(backoff) { wakeSignal.await() }
            wake = null
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private fun setLive(v: Boolean) {
        live = v
        if (tag == "child") FamilyStore.setLive(v) else ParentGuardService.socketLive = v
    }

    private fun confirmGone(token: String): Boolean = try {
        FamilyApi.state(token, Long.MAX_VALUE / 2)
        false
    } catch (e: FamilyApiException) {
        e.unlinked
    } catch (e: IOException) {
        Log.w(TAG, "연결 확인 실패", e)
        false
    }

    private inner class Listener(private val closed: CompletableDeferred<Int>, private val gen: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            setLive(true)
            FamilyStore.setError(null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            lastPong = System.currentTimeMillis()
            if (text == "pong") return
            val o = try {
                JSONObject(text)
            } catch (e: JSONException) {
                Log.w(TAG, "메시지 해석 실패", e)
                return
            }
            onMessage(o, gen)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            // 4000 = 연결 끊김, 4001 = 대기 중 만료로 서버에서 지워짐, 4002 = 자녀가 연결 요청 거절
            closed.complete(
                when (code) {
                    4000, 4001 -> CODE_GONE
                    4002 -> CODE_REJECTED
                    else -> code
                },
            )
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val status = response?.code
            Log.w(TAG, "$tag WebSocket 실패 (HTTP $status)", t)
            if (status == 401) {
                closed.complete(CODE_AUTH_FAILED)
            } else {
                FamilyStore.setError("서버에 연결하지 못했습니다. 다시 연결하는 중…")
                closed.complete(-1)
            }
        }
    }

    companion object {
        private const val TAG = "PG-FamilySocket"
        private const val MIN_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val STABLE_MS = 30_000L
        private const val CODE_GONE = -4000
        private const val CODE_REJECTED = -4002
        private const val CODE_AUTH_FAILED = -401
    }
}

private fun Service.registerNetworkKick(onAvailable: () -> Unit): ConnectivityManager.NetworkCallback? {
    val cm = getSystemService(ConnectivityManager::class.java) ?: return null
    val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onAvailable()
    }
    return try {
        cm.registerDefaultNetworkCallback(cb)
        cb
    } catch (e: RuntimeException) {
        Log.w("PG-Family", "네트워크 콜백 등록 실패", e)
        null
    }
}

private fun Service.unregisterNetworkKick(cb: ConnectivityManager.NetworkCallback?) {
    if (cb == null) return
    try {
        getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
    } catch (e: IllegalArgumentException) {
        Log.w("PG-Family", "네트워크 콜백 해제 실패", e)
    }
}

/**
 * 부모 폰 상시 서비스
 *  - 앱 설치·업데이트를 즉시 감지해 자녀 폰에 알림 / 결정 요청
 *  - 서버와 실시간 연결: 자녀 승인, 자녀 명령(잠금·차단·무시)을 바로 실행
 *  - 3시간마다 생존 신호, 1분마다 "30분 무응답 → 인터넷 자동 차단" 확인
 *  - 잠금 화면(1시간)을 이 프로세스에서 띄운다
 * 상시 알림으로 "가족에게 위험 알림을 보내는 중"임을 항상 보여준다 (몰래 감시하지 않음).
 */
class ParentGuardService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashGuard("PG-ParentGuard"))

    /** 승인·명령은 도착한 순서대로 하나씩 처리한다 (재연결 직후 state와 실시간 명령이 겹쳐도 순서 보장, 리뷰 M1) */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val commandDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var receiver: BroadcastReceiver? = null
    private var jobs: List<Job> = emptyList()
    private var socket: FamilySocket? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        FamilyStore.ensureLoaded(this)
        val s = FamilyStore.state.value
        if (s.role != FamilyRole.PARENT) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!startForegroundCompat(NOTI_ID, "가족 보호 켜짐", ongoingText(), FamilyRole.PARENT)) return START_NOT_STICKY
        running = true
        // 재부팅·앱 재시작 뒤 잠금이 남아 있으면 다시 덮는다
        LockOverlay.restoreIfActive(applicationContext)
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context, i: Intent) {
                    val pkg = i.data?.schemeSpecificPart ?: return
                    // 서비스가 살아 있으므로 goAsync 없이 서비스 범위에서 처리 (브로드캐스트 시간 제한과 무관)
                    scope.launch { FamilyReporter.onPackageChanged(applicationContext, pkg) }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            // 시스템 브로드캐스트만 받는다 (다른 앱은 이 액션을 보낼 수 없음)
            ContextCompat.registerReceiver(this, r, filter, ContextCompat.RECEIVER_EXPORTED)
            receiver = r
        }
        if (socket == null) {
            socket = FamilySocket(
                applicationContext, scope, "parent",
                sinceId = { FamilyStore.lastCommandId(applicationContext) },
                onMessage = ::handle,
                onGone = { rejected ->
                    if (rejected) {
                        FamilyNotify.post(applicationContext, 3997, "가족 연결이 거절됐습니다", "자녀 폰에서 연결을 거절했습니다. 다시 연결하려면 새 PIN을 받아 입력하세요.")
                    }
                    FamilyController.onRemoteUnlinked(applicationContext)
                },
            ).also { it.start() }
            netCallback = registerNetworkKick { socket?.kick() }
        }
        if (jobs.isEmpty()) {
            jobs = listOf(
                scope.launch {
                    while (isActive) {
                        try {
                            if (ScanRepository.apps.value.isEmpty()) ScanRepository.scan(applicationContext)
                            FamilyReporter.sync(applicationContext)
                        } catch (e: SecurityException) {
                            Log.e(TAG, "검사 권한 오류", e)
                        }
                        delay(HEARTBEAT_MS)
                    }
                },
                scope.launch {
                    while (isActive) {
                        delay(DEADLINE_CHECK_MS)
                        GuardController.checkDeadlines(applicationContext)
                    }
                },
            )
        }
        return START_STICKY
    }

    private fun ongoingText(): String {
        val s = FamilyStore.state.value
        val who = s.otherName ?: "가족"
        return if (s.paired) "위험한 앱이 설치되면 ${who}에게 바로 알립니다" else "${who}의 연결 승인을 기다리는 중"
    }

    /** 명령 실행(잠금·증거 저장)은 시간이 걸리므로 소켓 스레드가 아닌 서비스 범위에서 차례로 처리 */
    private fun handle(o: JSONObject, gen: Int) {
        scope.launch(commandDispatcher) { handleNow(o, gen) }
    }

    private fun handleNow(o: JSONObject, gen: Int) {
        val ctx = applicationContext
        when (o.optString("kind")) {
            "state" -> {
                val st = o.optJSONObject("state") ?: return
                if (st.optBoolean("paired") && !FamilyStore.state.value.paired) onApproved(gen)
                val cmds = st.optJSONArray("commands")
                if (cmds != null) {
                    (0 until cmds.length()).mapNotNull { cmds.optJSONObject(it) }.sortedBy { it.optLong("id") }
                        .forEach { GuardController.handleCommand(ctx, it) }
                }
            }
            "approved" -> onApproved(gen)
            "command" -> o.optJSONObject("command")?.let { GuardController.handleCommand(ctx, it) }
        }
    }

    private fun onApproved(gen: Int) {
        if (FamilyStore.state.value.paired) return
        FamilyStore.setApproved(applicationContext, gen)
        startForegroundCompat(NOTI_ID, "가족 보호 켜짐", ongoingText(), FamilyRole.PARENT)
        FamilyNotify.post(applicationContext, 3996, "가족 연결 완료", "이제 위험한 앱이 설치되면 ${FamilyStore.state.value.otherName ?: "가족"}에게 바로 알립니다.")
        scope.launch {
            try {
                if (ScanRepository.apps.value.isEmpty()) ScanRepository.scan(applicationContext)
                FamilyReporter.sync(applicationContext)
            } catch (e: SecurityException) {
                Log.e(TAG, "검사 권한 오류", e)
            }
        }
    }

    override fun onDestroy() {
        running = false
        receiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "receiver 해제 실패", e)
            }
        }
        receiver = null
        socket?.stop()
        unregisterNetworkKick(netCallback)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PG-ParentGuard"
        private const val NOTI_ID = 3101
        private const val HEARTBEAT_MS = 3L * 60 * 60 * 1000
        private const val DEADLINE_CHECK_MS = 60_000L

        /** 실시간 설치 감지가 돌고 있는지 (생존 신호에 담아 자녀에게 알림) */
        @Volatile
        var running = false
            private set

        /** 서버와 실시간 연결 중인지 (연결 중이면 서버가 ping으로 생존을 알므로 생존 신호 HTTP를 줄인다) */
        @Volatile
        var socketLive = false
    }
}

/**
 * 자녀 폰 상시 서비스: 서버와 WebSocket을 유지해 부모 폰 알림·결정 요청을 거의 실시간으로 받는다.
 */
class ChildWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashGuard("PG-ChildWatch"))
    private var socket: FamilySocket? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        FamilyStore.ensureLoaded(this)
        val s = FamilyStore.state.value
        if (s.role != FamilyRole.CHILD) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!startForegroundCompat(NOTI_ID, "가족 폰 지켜보기", ongoingText(), FamilyRole.CHILD)) return START_NOT_STICKY
        running = true
        if (socket == null) {
            socket = FamilySocket(
                applicationContext, scope, "child",
                sinceId = { FamilyStore.lastEventId() },
                onMessage = ::handle,
                onGone = { FamilyController.onRemoteUnlinked(applicationContext) },
            ).also { it.start() }
            netCallback = registerNetworkKick { socket?.kick() }
        }
        return START_STICKY
    }

    private fun ongoingText(): String {
        val s = FamilyStore.state.value
        return if (s.paired) "${s.otherName ?: "가족 폰"}의 위험 알림을 받는 중" else "부모 폰의 연결을 기다리는 중"
    }

    private fun handle(o: JSONObject, gen: Int) {
        val ctx = applicationContext
        val wasPaired = FamilyStore.state.value.paired
        when (o.optString("kind")) {
            "state" -> o.optJSONObject("state")?.let { FamilyStore.applyState(ctx, it, gen) }
            "event" -> {
                val e = o.optJSONObject("event")?.let(FamilyEvent::fromJson) ?: return
                if (e.type == FamilyEvent.TYPE_UNLINKED) {
                    FamilyNotify.showEvent(ctx, e, FamilyStore.state.value.otherName)
                    return // 곧 4000으로 닫힘
                }
                FamilyStore.addEvents(ctx, listOf(e), gen)
            }
            "heartbeat" -> FamilyStore.applyHeartbeat(ctx, o.optLong("lastHeartbeat"), o.optJSONObject("heartbeat"), gen)
        }
        // 실시간이든, 끊겨 있던 동안 쌓인 것이든 아직 안 띄운 사건만 알림 (주기 확인과 겹쳐도 한 번만)
        FamilyStore.takeUnnotified(ctx, gen).forEach { FamilyNotify.showEvent(ctx, it, FamilyStore.state.value.otherName) }
        if (!wasPaired && FamilyStore.state.value.paired) {
            startForegroundCompat(NOTI_ID, "가족 폰 지켜보기", ongoingText(), FamilyRole.CHILD)
        }
    }

    override fun onDestroy() {
        running = false
        socket?.stop()
        unregisterNetworkKick(netCallback)
        FamilyStore.setLive(false)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTI_ID = 3102

        @Volatile
        var running = false
            private set
    }
}
