package kr.phishguard.app.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.phishguard.app.family.FamilyController
import kr.phishguard.app.family.QuietMode
import kr.phishguard.app.history.HistoryEvent
import kr.phishguard.app.history.HistoryEventType
import kr.phishguard.app.history.HistoryStore
import kr.phishguard.app.scan.ScanRepository
import kr.phishguard.app.ui.MainActivity

/** 주기적으로 검사해 새로 설치된 앱이 있으면 알림을 띄운다. */
class InstallWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val firstRun = !prefs.contains(KEY_LAST)
        val since = prefs.getLong(KEY_LAST, System.currentTimeMillis())
        if (firstRun) prefs.edit().putLong(KEY_LAST, since).apply()

        try {
            ScanRepository.scan(ctx)
        } catch (e: SecurityException) {
            Log.e(TAG, "검사 권한 오류", e)
            return@withContext Result.retry()
        }
        HistoryStore.ensureLoaded(ctx)
        // 가족 연결: 부모 폰은 위험 앱·생존 신호 전송, 자녀 폰은 실시간 연결이 끊겨 있으면 놓친 알림 확인
        FamilyController.periodic(ctx)

        val fresh = HistoryStore.events.value.filter {
            it.detectedAt > since &&
                (it.type == HistoryEventType.INSTALLED || it.type == HistoryEventType.INSTALLED_THEN_REMOVED)
        }
        if (fresh.isEmpty()) return@withContext Result.success()

        val newest = fresh.maxOf { it.detectedAt }
        prefs.edit().putLong(KEY_LAST, newest).apply()
        notifyInstalled(ctx, fresh.sortedBy { it.detectedAt })
        Result.success()
    }

    private fun notifyInstalled(ctx: Context, events: List<HistoryEvent>) {
        if (QuietMode.isQuiet(ctx)) {
            Log.i(TAG, "부모 폰 조용 모드: 설치 알림 생략")
            return
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "알림 권한 없음: 설치 알림 생략")
            return
        }
        val nm = NotificationManagerCompat.from(ctx)
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "새 앱 설치 알림", NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_TAB, TAB_HISTORY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val grouped = events.size > GROUP_THRESHOLD
        events.forEach { e ->
            val level = e.riskLevel ?: ScanRepository.find(e.packageName)?.riskLevel
            val label = e.label ?: ScanRepository.find(e.packageName)?.label ?: e.packageName
            val builder = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("새 앱이 설치되었습니다")
                .setContentText("$label · 위험 ${level?.label ?: "알 수 없음"}")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
            if (grouped) builder.setGroup(GROUP)
            nm.notify(e.packageName.hashCode(), builder.build())
        }
        if (grouped) {
            val summary = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("새 앱이 설치되었습니다")
                .setContentText("새 앱 ${events.size}개가 설치되었습니다")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setGroup(GROUP)
                .setGroupSummary(true)
                .build()
            nm.notify(SUMMARY_ID, summary)
        }
    }

    companion object {
        private const val TAG = "PG-InstallWatch"
        private const val PREF = "bg"
        private const val KEY_LAST = "last_notified"
        private const val CHANNEL = "install_alert"
        private const val GROUP = "install_alert_group"
        private const val SUMMARY_ID = 2001
        private const val GROUP_THRESHOLD = 3
        const val EXTRA_OPEN_TAB = "open_tab"
        const val TAB_HISTORY = "history"
    }
}
