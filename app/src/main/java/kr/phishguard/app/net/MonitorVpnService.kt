package kr.phishguard.app.net

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.IOException
import kr.phishguard.app.ui.MainActivity

/**
 * 앱별 인터넷 차단 전용 로컬 VPN.
 *
 * 차단 목록에 있는 앱만 VPN에 포함(addAllowedApplication)하고, 들어온 패킷은 읽어서 버린다.
 * 차단 대상이 아닌 앱의 트래픽은 VPN을 거치지 않으므로 전혀 건드리지 않는다.
 * 통신 내용 중계·저장은 하지 않는다.
 */
class MonitorVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private var drainJob: Job? = null
    private var collectJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        BlockList.load(applicationContext)
        if (!startForegroundCompat(BlockList.blocked.value.size)) return START_NOT_STICKY
        if (collectJob == null) {
            collectJob = scope.launch { BlockList.blocked.collect { set -> rebuild(set) } }
        }
        return START_STICKY
    }

    @Synchronized
    private fun rebuild(blocked: Set<String>) {
        val installed = blocked.filter { isInstalled(it) && it != packageName }
        if (installed.isEmpty()) {
            Log.i(TAG, "차단 대상 없음 → 서비스 종료")
            shutdown()
            return
        }
        val builder = Builder()
            .setSession("피싱앱 검사기 - 인터넷 차단")
            .addAddress("10.111.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .setBlocking(true)
        try {
            builder.addAddress("fd00:7068:6973::2", 128)
            builder.addRoute("::", 0)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "IPv6 라우트 추가 실패", e)
        }
        for (pkg in installed) {
            try {
                builder.addAllowedApplication(pkg)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "패키지 없음: $pkg", e)
            }
        }
        val pfd = try {
            builder.establish()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "VPN 수립 실패", e)
            null
        } catch (e: SecurityException) {
            Log.e(TAG, "VPN 권한 없음", e)
            null
        }
        if (pfd == null) {
            notifyUser("인터넷 차단을 켜지 못했어요", "앱을 열어 다시 시도하세요")
            shutdown()
            return
        }
        // 새 TUN을 먼저 수립한 뒤 기존 것을 닫아 차단 공백을 없앤다
        val oldTun = tun
        val oldDrain = drainJob
        tun = pfd
        _running.value = true
        updateNotification(installed.size)
        drainJob = scope.launch {
            val input = FileInputStream(pfd.fileDescriptor)
            val buf = ByteArray(32767)
            try {
                while (isActive) {
                    val n = input.read(buf)
                    if (n < 0) break
                    // 차단 대상 앱의 패킷: 버린다(전달하지 않음)
                }
            } catch (e: IOException) {
                Log.i(TAG, "TUN 읽기 종료: ${e.message}")
            }
        }
        oldDrain?.cancel()
        try {
            oldTun?.close()
        } catch (e: IOException) {
            Log.w(TAG, "이전 TUN 닫기 실패", e)
        }
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    @Synchronized
    private fun closeTun() {
        drainJob?.cancel()
        drainJob = null
        try {
            tun?.close()
        } catch (e: IOException) {
            Log.w(TAG, "TUN 닫기 실패", e)
        }
        tun = null
    }

    private fun shutdown() {
        closeTun()
        _running.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        notifyUser("인터넷 차단이 해제됐어요", "다른 VPN 앱이 켜져서 차단이 꺼졌어요. 앱에서 다시 켜세요.")
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeTun()
        _running.value = false
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(count: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "인터넷 차단", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("의심 앱 인터넷 차단 중")
            .setContentText("${count}개 앱의 인터넷을 막고 있어요")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun notifyUser(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        buildNotification(0) // 채널 보장
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 1, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setAutoCancel(true)
            .build()
        nm.notify(NOTI_ALERT_ID, n)
    }

    /** 포그라운드 전환 성공 여부. API 31+에서 백그라운드 시작 제한에 걸리면 false */
    private fun startForegroundCompat(count: Int): Boolean {
        val n = buildNotification(count)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTI_ID, n)
            }
        } catch (e: IllegalStateException) {
            if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                Log.e(TAG, "포그라운드 서비스 시작 불가", e)
                stopSelf()
                return false
            }
            throw e
        }
        return true
    }

    private fun updateNotification(count: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTI_ID, buildNotification(count))
    }

    companion object {
        private const val TAG = "PG-Firewall"
        private const val CHANNEL = "firewall"
        private const val NOTI_ID = 1001
        private const val NOTI_ALERT_ID = 1002
        const val ACTION_STOP = "kr.phishguard.app.STOP_FIREWALL"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        /** VPN 동의(prepare)가 끝난 상태에서 호출 */
        fun start(context: Context) {
            val i = Intent(context, MonitorVpnService::class.java)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MonitorVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
