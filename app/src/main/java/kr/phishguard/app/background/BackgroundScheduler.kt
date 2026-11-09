package kr.phishguard.app.background

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** 새 앱 설치 감시 작업 예약 */
object BackgroundScheduler {
    private const val UNIQUE_NAME = "install_watch"

    /** 이미 예약돼 있으면 그대로 둔다 (MainActivity 시작, 부팅 시 호출) */
    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<InstallWatchWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
