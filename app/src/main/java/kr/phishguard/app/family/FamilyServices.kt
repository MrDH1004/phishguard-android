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
 * 부모 폰 상시 서비스: 앱 설치·업데이트를 즉시 감지해 자녀 폰에 알리고, 3시간마다 생존 신호를 보낸다.
 * 상시 알림으로 "가족에게 위험 알림을 보내는 중"임을 항상 보여준다 (몰래 감시하지 않음).
 */
class ParentGuardService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var receiver: BroadcastReceiver? = null
    private var heartbeatJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        FamilyStore.ensureLoaded(this)
        val s = FamilyStore.state.value
        if (s.role != FamilyRole.PARENT) {
            stopSelf()
            return START_NOT_STICKY
        }
        val who = s.otherName ?: "가족"
        if (!startForegroundCompat(NOTI_ID, "가족 보호 켜짐", "위험한 앱이 설치되면 ${who}에게 바로 알립니다", FamilyRole.PARENT)) {
            return START_NOT_STICKY
        }
        running = true
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
        if (heartbeatJob == null) {
            heartbeatJob = scope.launch {
                while (isActive) {
                    try {
                        if (ScanRepository.apps.value.isEmpty()) ScanRepository.scan(applicationContext)
                        FamilyReporter.sync(applicationContext)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "검사 권한 오류", e)
                    }
                    delay(HEARTBEAT_MS)
                }
            }
        }
        return START_STICKY
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
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PG-ParentGuard"
        private const val NOTI_ID = 3101
        private const val HEARTBEAT_MS = 3L * 60 * 60 * 1000

        /** 실시간 설치 감지가 돌고 있는지 (생존 신호에 담아 자녀에게 알림) */
        @Volatile
        var running = false
            private set
    }
}

/**
 * 자녀 폰 상시 서비스: 서버와 WebSocket을 유지해 부모 폰 알림을 거의 실시간으로 받는다.
 * 끊기면 2초부터 최대 60초 간격으로 다시 연결하고, 다시 연결할 때 놓친 사건을 받아 온다.
 */
class ChildWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var lastPong = 0L

    @Volatile
    private var stopped = false
    private var loopJob: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var wake: (() -> Unit)? = null

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
        if (loopJob == null) loopJob = scope.launch { connectLoop() }
        if (netCallback == null) {
            val cm = getSystemService(ConnectivityManager::class.java)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // 망이 바뀌면 이전 소켓은 죽었을 수 있다 → 바로 새로 연결
                    socket?.cancel()
                    wake?.invoke()
                }
            }
            try {
                cm.registerDefaultNetworkCallback(cb)
                netCallback = cb
            } catch (e: RuntimeException) {
                Log.w(TAG, "네트워크 콜백 등록 실패", e)
            }
        }
        return START_STICKY
    }

    private fun ongoingText(): String {
        val s = FamilyStore.state.value
        return if (s.paired) "${s.otherName ?: "가족 폰"}의 위험 알림을 받는 중" else "부모 폰에서 PIN 입력을 기다리는 중"
    }

    private suspend fun connectLoop() {
        var backoff = MIN_BACKOFF_MS
        while (scope.isActive && !stopped) {
            val token = FamilyStore.token(this)
            if (token == null || FamilyStore.state.value.role != FamilyRole.CHILD) {
                stopSelf()
                return
            }
            val gen = FamilyStore.generation()
            val closed = CompletableDeferred<Int>()
            val req = Request.Builder()
                .url(FamilyApi.wsUrl(FamilyStore.lastEventId()))
                .header("authorization", "Bearer $token")
                .build()
            lastPong = System.currentTimeMillis()
            val openedAt = System.currentTimeMillis()
            val ws = client.newWebSocket(req, Listener(closed, gen))
            socket = ws
            // 연결 유지: 45초마다 ping, 100초 넘게 pong이 없으면 끊고 다시 연결
            val pinger = scope.launch {
                while (isActive) {
                    delay(PING_MS)
                    ws.send("ping")
                    if (System.currentTimeMillis() - lastPong > PONG_TIMEOUT_MS) {
                        Log.w(TAG, "pong 없음: 다시 연결")
                        ws.cancel()
                        break
                    }
                }
            }
            val code = closed.await()
            pinger.cancel()
            socket = null
            FamilyStore.setLive(false)
            when (code) {
                CODE_GONE -> {
                    FamilyController.onRemoteUnlinked(applicationContext)
                    return
                }
                CODE_AUTH_FAILED -> {
                    // 401이 서버의 "없는 연결"인지 확인한 뒤에만 지운다 (프록시·포털의 401일 수 있음)
                    if (confirmGone(token)) {
                        FamilyController.onRemoteUnlinked(applicationContext)
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
            FamilyStore.setLive(true)
            FamilyStore.setError(null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            lastPong = System.currentTimeMillis()
            if (text == "pong") return
            handle(text, gen)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            // 4000 = 연결 끊김, 4001 = PIN 대기 중 만료로 서버에서 지워짐
            closed.complete(if (code == 4000 || code == 4001) CODE_GONE else code)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val status = response?.code
            Log.w(TAG, "WebSocket 실패 (HTTP $status)", t)
            if (status == 401) {
                closed.complete(CODE_AUTH_FAILED)
            } else {
                FamilyStore.setError("서버에 연결하지 못했습니다. 다시 연결하는 중…")
                closed.complete(-1)
            }
        }
    }

    private fun handle(text: String, gen: Int) {
        val o = try {
            JSONObject(text)
        } catch (e: JSONException) {
            Log.w(TAG, "메시지 해석 실패", e)
            return
        }
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
        stopped = true
        socket?.close(1000, null)
        netCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "네트워크 콜백 해제 실패", e)
            }
        }
        FamilyStore.setLive(false)
        scope.cancel()
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PG-ChildWatch"
        private const val NOTI_ID = 3102
        private const val PING_MS = 45_000L
        private const val PONG_TIMEOUT_MS = 100_000L
        private const val MIN_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val STABLE_MS = 30_000L
        private const val CODE_GONE = -4000
        private const val CODE_AUTH_FAILED = -401

        @Volatile
        var running = false
            private set
    }
}
