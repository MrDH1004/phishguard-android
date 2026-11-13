package kr.phishguard.app.family

import android.Manifest
import android.app.Notification
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
import kr.phishguard.app.background.InstallWatchWorker
import kr.phishguard.app.scan.RiskLevel
import kr.phishguard.app.ui.MainActivity

/** 가족 연결 알림 */
object FamilyNotify {
    private const val TAG = "PG-FamilyNotify"
    const val CHANNEL_ALERT = "family_alert"
    const val CHANNEL_SERVICE = "family_service"
    const val TAB_FAMILY = "family"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ALERT) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "가족 폰 위험 알림", NotificationManager.IMPORTANCE_HIGH))
        }
        if (nm.getNotificationChannel(CHANNEL_SERVICE) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, "가족 연결 상태", NotificationManager.IMPORTANCE_LOW))
        }
    }

    fun openFamilyIntent(context: Context, tab: String = TAB_FAMILY): PendingIntent = PendingIntent.getActivity(
        context, if (tab == TAB_FAMILY) 3001 else 3002,
        Intent(context, MainActivity::class.java)
            .putExtra(InstallWatchWorker.EXTRA_OPEN_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 상시 알림 (포그라운드 서비스용) */
    fun ongoing(context: Context, title: String, text: String): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openFamilyIntent(context))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** 자녀 폰: 받은 사건을 알림으로 */
    fun showEvent(context: Context, e: FamilyEvent, parentName: String?) {
        val who = parentName ?: "가족 폰"
        val (title, text) = when (e.type) {
            FamilyEvent.TYPE_ALERT -> {
                val app = e.apps.firstOrNull() ?: return
                val head = when {
                    app.riskLevel == RiskLevel.HIGH -> "⚠ $who: 위험한 앱이 설치됐습니다"
                    app.remoteControl -> "⚠ $who: 원격조종 앱이 설치됐습니다"
                    e.data.optString("kind") == "risk_up" -> "$who: 앱 위험도가 올라갔습니다"
                    else -> "$who: 의심 앱이 설치됐습니다"
                }
                head to "${app.label} · 위험 ${app.riskLevel.label} ${app.riskScore}점 · ${app.reasons.firstOrNull().orEmpty()}"
            }
            FamilyEvent.TYPE_SNAPSHOT -> {
                val apps = e.apps
                val high = apps.count { it.riskLevel == RiskLevel.HIGH }
                "$who 연결됨" to (if (apps.isEmpty()) "지금은 의심 앱이 없습니다" else "의심 앱 ${apps.size}개 (높음 ${high}개)")
            }
            FamilyEvent.TYPE_PAIRED -> "연결됐습니다: $who" to "이제 이 폰으로 위험 알림이 옵니다"
            FamilyEvent.TYPE_PARENT_SILENT ->
                "⚠ $who: 하루 넘게 신호가 없습니다" to "폰이 꺼졌거나, 피싱앱 검사기가 지워졌거나 꺼졌을 수 있습니다. 전화로 확인하세요."
            FamilyEvent.TYPE_PARENT_BACK -> "$who 신호가 다시 들어옵니다" to "피싱앱 검사기가 다시 동작합니다"
            FamilyEvent.TYPE_JOIN_REQUEST ->
                "연결 요청: ${e.data.optString("parentName").ifEmpty { "부모 폰" }}" to "내가 PIN을 알려 준 폰이 맞으면 승인을 누르세요. 모르는 이름이면 거절하세요."
            FamilyEvent.TYPE_DECISION -> {
                val app = e.apps.firstOrNull() ?: return
                val why = if (e.data.optString("reason") == "surge") {
                    "점수가 ${e.data.optInt("previousScore")}점에서 ${app.riskScore}점으로 급하게 올랐습니다"
                } else {
                    "위험 ${app.riskScore}점"
                }
                "⚠ 결정 필요: $who · ${app.label}" to "$why. 잠금 / 인터넷만 차단 / 무시 중에 골라 주세요. 30분 안에 답이 없으면 인터넷만 자동으로 끊습니다."
            }
            FamilyEvent.TYPE_COMMAND_RESULT -> {
                val label = e.data.optString("label").ifEmpty { e.data.optString("packageName") }
                val head = when (e.data.optString("type")) {
                    "lock" -> if (e.data.optBoolean("ok")) "$who 1시간 잠금 완료" else "$who 잠금 일부 실패"
                    "block" -> if (e.data.optBoolean("ok")) "$who 인터넷 차단 완료" else "$who 차단 일부 실패"
                    "auto_block" -> "$who: 답이 없어 인터넷을 자동으로 끊었습니다"
                    else -> return
                }
                head to "$label · ${e.data.optString("message")}"
            }
            FamilyEvent.TYPE_UNLINKED -> {
                if (e.data.optString("by") == "child") return // 이 폰에서 직접 끊음
                "연결이 끊겼습니다: $who" to "부모 폰에서 연결을 끊었습니다. 다시 지켜보려면 새로 연결하세요."
            }
            else -> return
        }
        post(context, (e.id % Int.MAX_VALUE).toInt() + 4000, title, text)
    }

    fun post(context: Context, id: Int, title: String, text: String, tab: String = TAB_FAMILY) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "알림 권한 없음: $title")
            return
        }
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openFamilyIntent(context, tab))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }
}
