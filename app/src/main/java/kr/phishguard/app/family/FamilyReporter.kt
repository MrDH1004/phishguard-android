package kr.phishguard.app.family

import android.content.Context
import android.util.Log
import kr.phishguard.app.BuildConfig
import kr.phishguard.app.scan.AppInfo
import kr.phishguard.app.scan.RiskLevel
import kr.phishguard.app.scan.ScanRepository
import kr.phishguard.app.scan.TrustList
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** 부모 폰: 위험 앱 알림과 생존 신호를 자녀 폰으로 보낸다. IO 스레드에서 호출. */
object FamilyReporter {
    private const val TAG = "PG-FamilyReporter"
    private const val SNAPSHOT_MAX = 50

    /** 연결 직후 PIN 입력 화면과 상시 서비스가 동시에 보내 snapshot이 두 번 가는 일을 막는다 (전송은 한 번에 하나) */
    private val sendLock = Any()

    private fun reportable(context: Context, app: AppInfo) =
        FamilyPolicy.shouldReport(app, TrustList.isTrusted(app.packageName), context.packageName)

    /** 앱 하나가 설치·변경됐을 때 (패키지 브로드캐스트) */
    fun onPackageChanged(context: Context, pkg: String) {
        if (parentToken(context) == null) return
        val app = ScanRepository.rescanPackage(context, pkg) ?: return
        if (!reportable(context, app)) return
        synchronized(sendLock) {
            val token = parentToken(context) ?: return
            sendAlertIfNeeded(context, token, app, FamilyStore.generation())
        }
    }

    /**
     * 전체 동기화: 연결 직후 한 번은 지금 위험 앱 목록(snapshot), 이후에는 새로 위험해진 앱 알림 + 생존 신호.
     * [apps]가 없으면 마지막 검사 결과를 쓴다.
     */
    fun sync(context: Context, apps: List<AppInfo> = ScanRepository.apps.value) {
        synchronized(sendLock) { syncLocked(context, apps) }
    }

    private fun syncLocked(context: Context, apps: List<AppInfo>) {
        val token = parentToken(context) ?: return
        val gen = FamilyStore.generation()
        TrustList.load(context)
        val risky = apps.filter { reportable(context, it) }
        try {
            if (!FamilyStore.snapshotSent(context)) {
                val top = risky.sortedByDescending { it.riskScore }.take(SNAPSHOT_MAX)
                val arr = JSONArray()
                top.forEach { arr.put(FamilyPolicy.toAlertApp(it).toJson()) }
                FamilyApi.post(token, FamilyEvent.TYPE_SNAPSHOT, JSONObject().put("apps", arr).put("scannedAt", System.currentTimeMillis()))
                // 목록에 실제로 담은 앱만 "보냄"으로 기록 → 나머지는 아래 알림으로 이어서 보낸다
                top.forEach { FamilyStore.markReported(context, it.packageName, it.firstInstallTime, it.riskLevel, gen) }
                FamilyStore.markSnapshotSent(context, gen)
            }
            risky.forEach { sendAlertIfNeeded(context, token, it, gen) }
            FamilyApi.post(
                token, "heartbeat",
                JSONObject()
                    .put("high", risky.count { it.riskLevel == RiskLevel.HIGH })
                    .put("medium", risky.count { it.riskLevel == RiskLevel.MEDIUM })
                    .put("appVersion", BuildConfig.VERSION_NAME)
                    // 실시간 설치 감지 서비스가 실제로 돌고 있는지 (꺼져 있으면 자녀 화면에 경고)
                    .put("guardOn", ParentGuardService.running),
            )
            FamilyStore.markSent(context, gen)
        } catch (e: FamilyApiException) {
            handleError(context, e)
        } catch (e: IOException) {
            Log.w(TAG, "sync 전송 실패 (다음에 다시 시도)", e)
            FamilyStore.setError("인터넷 연결이 없어 보내지 못했습니다. 연결되면 다시 보냅니다.")
        }
    }

    private fun sendAlertIfNeeded(context: Context, token: String, app: AppInfo, gen: Int) {
        val reported = FamilyStore.reportedLevel(context, app.packageName, app.firstInstallTime)
        val kind = FamilyPolicy.alertKind(reported, app.riskLevel) ?: return
        try {
            FamilyApi.post(
                token, FamilyEvent.TYPE_ALERT,
                JSONObject().put("kind", kind).put("app", FamilyPolicy.toAlertApp(app).toJson()),
            )
            FamilyStore.markReported(context, app.packageName, app.firstInstallTime, app.riskLevel, gen)
            FamilyStore.markSent(context, gen)
        } catch (e: FamilyApiException) {
            handleError(context, e)
        } catch (e: IOException) {
            // 보고 기록을 남기지 않았으므로 다음 sync에서 다시 보낸다
            Log.w(TAG, "알림 전송 실패: ${app.packageName}", e)
            FamilyStore.setError("인터넷 연결이 없어 보내지 못했습니다. 연결되면 다시 보냅니다.")
        }
    }

    private fun handleError(context: Context, e: FamilyApiException) {
        if (e.unlinked) {
            Log.i(TAG, "서버에서 연결이 끊김")
            FamilyController.onRemoteUnlinked(context)
        } else {
            Log.w(TAG, "전송 오류 ${e.status} ${e.code}", e)
            FamilyStore.setError(e.message)
        }
    }

    private fun parentToken(context: Context): String? {
        FamilyStore.ensureLoaded(context)
        if (FamilyStore.state.value.role != FamilyRole.PARENT) return null
        return FamilyStore.token(context)
    }
}
