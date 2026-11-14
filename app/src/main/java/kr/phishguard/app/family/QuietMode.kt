package kr.phishguard.app.family

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 부모 폰(보호받는 쪽)은 알림을 띄우지 않는다.
 * 사기범이 원격으로 폰을 조종하며 모르는 앱을 지우는데, 눈에 띄는 알림이 있으면 이 앱을 찾아 지운다.
 * 없앨 수 없는 포그라운드 서비스 알림만 [ongoing]으로 아이콘·소리 없이, 앱 이름을 드러내지 않는 문구로 남긴다.
 */
object QuietMode {
    /** 중요도는 만든 뒤 바꿀 수 없어 기존 채널과 다른 id를 쓴다 */
    private const val CHANNEL_QUIET = "sync_quiet"

    /** FamilyNotify(family_alert·family_service), InstallWatchWorker(install_alert), MonitorVpnService(firewall) */
    private val OLD_CHANNELS = listOf("family_alert", "family_service", "install_alert", "firewall")

    fun isQuiet(context: Context): Boolean {
        FamilyStore.ensureLoaded(context.applicationContext)
        return FamilyStore.state.value.role == FamilyRole.PARENT
    }

    /** 포그라운드 서비스용: 상태 표시줄 아이콘 없음(IMPORTANCE_MIN), 알림창에서도 접힌 채로 "동기화 · 실행 중"만 */
    fun ongoing(context: Context): Notification {
        val nm = context.getSystemService(NotificationManager::class.java)
        // 앱 알림 설정 화면에 "가족 폰 위험 알림"·"인터넷 차단" 같은 이름이 남지 않게 예전 채널을 지운다
        OLD_CHANNELS.forEach {
            if (nm.getNotificationChannel(it) == null) return@forEach
            try {
                nm.deleteNotificationChannel(it)
            } catch (e: SecurityException) {
                // 연결 전에 시작된 포그라운드 서비스가 아직 그 채널을 쓰는 중 → 다음에 지움
                Log.w("PG-QuietMode", "채널 삭제 보류: $it", e)
            }
        }
        if (nm.getNotificationChannel(CHANNEL_QUIET) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_QUIET, "동기화", NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                },
            )
        }
        return NotificationCompat.Builder(context, CHANNEL_QUIET)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("동기화")
            .setContentText("실행 중")
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
