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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.IOException
import kr.phishguard.app.family.QuietMode
import kr.phishguard.app.ui.MainActivity

/**
 * 앱별 인터넷 차단 전용 로컬 VPN.
 *
 * 차단 목록에 있는 앱만 VPN에 포함(addAllowedApplication)하고, 들어온 패킷은 읽어서 버린다.
 * 차단 대상이 아닌 앱의 트래픽은 VPN을 거치지 않으므로 전혀 건드리지 않는다.
 * 해외 전송 차단만 있을 때는 한국 밖 IPv4 대역과 IPv6만 VPN으로 받아 버린다 ([VpnPlan]).
 * 통신 내용 중계·저장은 하지 않는다.
 */
class MonitorVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private var drainJob: Job? = null
    private var collectJob: Job? = null
    @Volatile private var notifMode = VpnPlan.Mode.FULL
    @Volatile private var currentMode = VpnPlan.Mode.OFF

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        BlockList.load(applicationContext)
        VpnGuard.load(applicationContext)
        if (!startForegroundCompat((BlockList.blocked.value + BlockList.foreign.value).size)) return START_NOT_STICKY
        if (collectJob == null) {
            collectJob = scope.launch {
                combine(
                    BlockList.blocked, BlockList.foreign, VpnGuard.hold, VpnGuard.pauseForPayment, payForeground,
                ) { full, foreign, hold, _, pay -> Inputs(full, foreign, hold, pay) }
                    .collect { rebuild(it) }
            }
        }
        return START_STICKY
    }

    private data class Inputs(val full: Set<String>, val foreign: Set<String>, val hold: Boolean, val payForeground: String?)

    /** 결제 앱(삼성페이)이 앞에 떠 있음. 대기 모드 + 옵션이 켜졌을 때만 감시한다 */
    private val payForeground = MutableStateFlow<String?>(null)
    private var payWatchJob: Job? = null

    private fun watchPayment(standby: Boolean) {
        val want = standby && VpnGuard.pauseForPayment.value && UsageStats.hasAccess(applicationContext)
        if (!want) {
            payWatchJob?.cancel()
            payWatchJob = null
            payForeground.value = null
            return
        }
        if (payWatchJob != null) return
        payWatchJob = scope.launch {
            var fg: String? = null
            var since = System.currentTimeMillis() - PAY_LOOKBACK_MS
            while (isActive) {
                val now = System.currentTimeMillis()
                val events = VpnGuard.screenEvents(applicationContext, since, now)
                if (events != null) {
                    fg = VpnPlan.foregroundAfter(fg, events)
                    since = now
                }
                payForeground.value = fg?.takeIf { it in VpnPlan.PAYMENT_PACKAGES }
                delay(PAY_POLL_MS)
            }
        }
    }

    @Synchronized
    private fun rebuild(inputs: Inputs) {
        val usable = { pkg: String -> isInstalled(pkg) && pkg != packageName }
        val plan = VpnPlan.plan(
            inputs.full.filter(usable).toSet(), inputs.foreign.filter(usable).toSet(), krRanges(applicationContext), inputs.hold,
        )
        currentMode = plan.mode
        val installed = plan.apps
        if (plan.mode == VpnPlan.Mode.OFF) {
            Log.i(TAG, "차단 대상 없음 → 서비스 종료")
            shutdown()
            return
        }
        val standby = plan.mode == VpnPlan.Mode.STANDBY
        watchPayment(standby)
        val lockdown = VpnGuard.status.value.lockdown == true
        if (VpnPlan.shouldPauseForPayment(plan.mode, VpnGuard.pauseForPayment.value, lockdown, inputs.payForeground)) {
            // 삼성페이 결제 중: TUN만 닫고 서비스는 유지 → 결제 앱이 내려가면 다시 세운다
            Log.i(TAG, "결제 앱 사용 중 → 대기 VPN 잠시 내림")
            closeTun()
            VpnGuard.setPaused(true)
            notifMode = plan.mode
            updateNotification(0)
            return
        }
        VpnGuard.setPaused(false)
        val foreignMode = plan.mode == VpnPlan.Mode.FOREIGN
        val builder = Builder()
            .setSession(
                when (plan.mode) {
                    VpnPlan.Mode.FOREIGN -> "피싱앱 검사기 - 해외 전송 차단"
                    VpnPlan.Mode.STANDBY -> "피싱앱 검사기 - 다른 VPN 막기"
                    else -> "피싱앱 검사기 - 인터넷 차단"
                },
            )
            .addAddress("10.111.0.2", 32)
            .setBlocking(true)
        for (r in plan.ipv4Routes) builder.addRoute(r.address, r.prefix)
        // 차단된 앱의 도메인 조회가 TUN으로 들어오게 TUN 안쪽 주소를 DNS로 지정한다 (응답은 하지 않음).
        // 지정하지 않으면 시스템이 원래 회선 DNS로 조회해 어떤 도메인에 접속하려 했는지 기록할 수 없다.
        // 해외 차단 모드에서는 지정하지 않는다: 조회가 전부 TUN에서 죽어 한국 사이트도 못 쓰게 된다.
        if (plan.dnsInTunnel) builder.addDnsServer("10.111.0.1")
        if (plan.blocksIpv6) {
            try {
                builder.addAddress("fd00:7068:6973::2", 128)
                // 해외 차단 모드에서도 IPv6는 전부 막는다 (막히면 앱이 IPv4로 다시 시도하고, IPv4는 한국 여부로 거른다)
                builder.addRoute("::", 0)
                if (plan.dnsInTunnel) builder.addDnsServer("fd00:7068:6973::1")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "IPv6 라우트 추가 실패", e)
            }
        }
        if (standby) {
            // 대기: 이 앱만 VPN에 넣고 라우트를 주지 않는다 → 다른 앱은 VPN 밖, 이 앱의 통신도 TUN으로 오지 않는다
            try {
                builder.addAllowedApplication(packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(TAG, "자기 패키지 없음", e)
            }
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
        Log.i(TAG, "VPN 수립 ${if (pfd != null) "성공" else "실패"}: ${plan.mode}, 앱 ${installed.size}개, IPv4 라우트 ${plan.ipv4Routes.size}개")
        if (pfd == null) {
            notifyUser("인터넷 차단을 켜지 못했습니다", "앱을 열어 '차단 켜기'를 다시 눌러 주세요.")
            shutdown()
            return
        }
        // 새 TUN을 먼저 수립한 뒤 기존 것을 닫아 차단 공백을 없앤다
        val oldTun = tun
        val oldDrain = drainJob
        tun = pfd
        _running.value = true
        notifMode = plan.mode
        updateNotification(installed.size)
        if (Build.VERSION.SDK_INT >= 29) VpnGuard.updateStatus(VpnGuard.AlwaysOnStatus(isAlwaysOn, isLockdownEnabled))
        val blockedNow = installed
        BlockedTraffic.ensureLoaded(applicationContext)
        drainJob = scope.launch {
            val input = FileInputStream(pfd.fileDescriptor)
            val buf = ByteArray(32767)
            try {
                while (isActive) {
                    val n = input.read(buf)
                    if (n < 0) break
                    // 차단 대상 앱의 패킷: 머리 부분만 읽어 목적지·DNS 도메인을 기록하고 버린다(전달하지 않음)
                    BlockedTraffic.onPacket(applicationContext, buf, n, blockedNow, foreignMode)
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
        BlockedTraffic.flush(applicationContext)
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
        payWatchJob?.cancel()
        payWatchJob = null
        VpnGuard.setPaused(false)
        VpnGuard.updateStatus(VpnGuard.AlwaysOnStatus(null, null))
        closeTun()
        _running.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        VpnGuard.recordRevoke(applicationContext, currentMode)
        notifyUser("인터넷 차단이 해제되었습니다", "다른 VPN 앱이 켜져서 차단이 꺼졌습니다. 앱을 열어 '차단 켜기'를 다시 눌러 주세요.")
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
        if (QuietMode.isQuiet(this)) return QuietMode.ongoing(this) // 부모 폰: 눈에 띄지 않게
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
            .setContentTitle(
                when {
                    notifMode == VpnPlan.Mode.STANDBY && VpnGuard.paused.value -> "다른 VPN 막기 잠시 멈춤"
                    notifMode == VpnPlan.Mode.STANDBY -> "다른 VPN 앱 막는 중"
                    notifMode == VpnPlan.Mode.FOREIGN -> "의심 앱 해외 전송 차단 중"
                    else -> "의심 앱 인터넷 차단 중"
                },
            )
            .setContentText(
                when {
                    notifMode == VpnPlan.Mode.STANDBY && VpnGuard.paused.value -> "삼성페이를 쓰는 동안 잠시 꺼 두었습니다"
                    notifMode == VpnPlan.Mode.STANDBY -> "VPN 자리를 지키고 있습니다. 다른 앱의 통신은 건드리지 않습니다"
                    notifMode == VpnPlan.Mode.FOREIGN -> "${count}개 앱이 해외로 보내는 통신을 막고 있습니다"
                    else -> "${count}개 앱의 인터넷을 막고 있습니다"
                },
            )
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun notifyUser(title: String, text: String) {
        if (QuietMode.isQuiet(this)) return // 부모 폰: 알림 없음
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
        private const val PAY_POLL_MS = 2_000L
        private const val PAY_LOOKBACK_MS = 10 * 60_000L

        @Volatile private var krCache: List<Ipv4Cidr>? = null

        /** assets/kr_ipv4.txt (tools/gen_kr_ranges.py로 생성) */
        fun krRanges(context: Context): List<Ipv4Cidr> {
            krCache?.let { return it }
            return try {
                context.assets.open("kr_ipv4.txt").bufferedReader(Charsets.UTF_8)
                    .use { Ipv4Cidr.parseList(it.readLines()) }
                    .also { krCache = it }
            } catch (e: IOException) {
                // 목록을 못 읽으면 한국 대역도 해외로 취급돼 더 많이 막힌다 (덜 막히는 쪽보다 안전). 다음 수립 때 다시 읽는다
                Log.e(TAG, "한국 IP 목록 읽기 실패", e)
                emptyList()
            }
        }

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
