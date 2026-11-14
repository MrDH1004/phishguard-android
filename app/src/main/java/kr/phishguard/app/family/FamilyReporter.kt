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
import java.util.UUID
import kr.phishguard.app.guard.GuardPolicy
import kr.phishguard.app.guard.OwnerMode
import kr.phishguard.app.net.VpnGuard
import kotlin.concurrent.thread

/** 부모 폰: 위험 앱 알림과 생존 신호를 자녀 폰으로 보낸다. IO 스레드에서 호출. */
object FamilyReporter {
    private const val TAG = "PG-FamilyReporter"
    private const val SNAPSHOT_MAX = 50
    private const val BATCH_MAX = 50
    private const val HEARTBEAT_MAX_GAP_MS = 12L * 60 * 60 * 1000

    /** 연결 직후 PIN 입력 화면과 상시 서비스가 동시에 보내 snapshot이 두 번 가는 일을 막는다 (전송은 한 번에 하나) */
    private val sendLock = Any()

    private fun reportable(context: Context, app: AppInfo) =
        FamilyPolicy.shouldReport(app, TrustList.isTrusted(app.packageName), context.packageName)

    /** 앱 하나가 설치·변경됐을 때 (패키지 브로드캐스트) */
    fun onPackageChanged(context: Context, pkg: String) {
        if (parentToken(context) == null) return
        val app = ScanRepository.rescanPackage(context, pkg) ?: return
        synchronized(sendLock) {
            val token = parentToken(context) ?: return
            val gen = FamilyStore.generation()
            try {
                maybeRequestDecision(context, token, app, gen)
            } catch (e: FamilyApiException) {
                handleError(context, e)
                return
            } catch (e: IOException) {
                Log.w(TAG, "결정 요청 전송 실패: ${app.packageName}", e)
            }
            sendAlertIfNeeded(context, token, app, gen)
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
            // 결정 요청이 먼저: 기준을 넘었거나 급상승한 앱은 알림 대신 "잠금/차단/무시"를 묻는다
            apps.forEach { maybeRequestDecision(context, token, it, gen) }
            if (!FamilyStore.snapshotSent(context)) {
                val top = risky.sortedByDescending { it.riskScore }.take(SNAPSHOT_MAX)
                val arr = JSONArray()
                top.forEach { arr.put(FamilyPolicy.toAlertApp(it).toJson()) }
                FamilyApi.post(token, FamilyEvent.TYPE_SNAPSHOT, JSONObject().put("apps", arr).put("scannedAt", System.currentTimeMillis()))
                // 목록에 실제로 담은 앱만 "보냄"으로 기록 → 나머지는 아래 알림으로 이어서 보낸다
                top.forEach { FamilyStore.markReported(context, it.packageName, it.firstInstallTime, it.riskLevel, gen) }
                FamilyStore.markSnapshotSent(context, gen)
            }
            sendAlertsBatched(context, token, risky, gen)
            val hb = JSONObject()
                .put("high", risky.count { it.riskLevel == RiskLevel.HIGH })
                .put("medium", risky.count { it.riskLevel == RiskLevel.MEDIUM })
                .put("appVersion", BuildConfig.VERSION_NAME)
                // 실시간 설치 감지 서비스가 실제로 돌고 있는지 (꺼져 있으면 자녀 화면에 경고)
                .put("guardOn", ParentGuardService.running)
                // 기기 소유자 모드면 자녀 화면에 정지·해제 버튼을 보인다
                .put("ownerMode", OwnerMode.isOwner(context))
            // 실시간 연결이 살아 있으면 서버가 ping 시각으로 생존을 안다 → 내용이 바뀌었을 때만 보낸다 (최대 12시간에 한 번은 보냄)
            val now = System.currentTimeMillis()
            val signature = hb.toString()
            val needed = !ParentGuardService.socketLive ||
                signature != FamilyStore.lastHeartbeatSignature(context) ||
                now - FamilyStore.lastHeartbeatSentAt(context) > HEARTBEAT_MAX_GAP_MS
            if (needed) {
                FamilyApi.post(token, "heartbeat", hb)
                FamilyStore.markHeartbeatSent(context, signature, now)
            }
            FamilyStore.markSent(context, gen)
        } catch (e: FamilyApiException) {
            handleError(context, e)
        } catch (e: IOException) {
            Log.w(TAG, "sync 전송 실패 (다음에 다시 시도)", e)
            FamilyStore.setError("인터넷 연결이 없어 보내지 못했습니다. 연결되면 다시 보냅니다.")
        }
    }

    /**
     * 위험 기준(100~150 설정값) 이상이거나 50점 넘게 급상승한 앱이면 자녀에게 결정 요청을 보낸다 (설치 한 번당 한 번).
     * 30분 안에 답이 없으면 GuardController가 인터넷만 자동 차단한다. 보낸 앱은 일반 알림을 따로 보내지 않는다.
     */
    private fun maybeRequestDecision(context: Context, token: String, app: AppInfo, gen: Int) {
        val pkg = app.packageName
        val fit = app.firstInstallTime
        val prev = FamilyStore.lastScore(context, pkg, fit)
        val reason = if (reportable(context, app) && !FamilyStore.decisionRequested(context, pkg, fit)) {
            GuardPolicy.trigger(app.riskScore, prev, FamilyStore.threshold(context))
        } else {
            null
        }
        if (reason == null) {
            FamilyStore.setLastScore(context, pkg, fit, app.riskScore)
            return
        }
        // 설치 한 번에 같은 요청 번호 → 응답을 못 받아 다시 보내도 서버·자녀 화면에서 하나로 합쳐진다
        val requestId = UUID.nameUUIDFromBytes("$pkg|$fit".toByteArray(Charsets.UTF_8)).toString()
        val deadline = System.currentTimeMillis() + GuardPolicy.AUTO_BLOCK_DELAY_MS
        // 전송에 실패하면 이전 점수를 그대로 두어 다음 동기화에서 급상승을 다시 판단한다
        FamilyApi.post(
            token, FamilyEvent.TYPE_DECISION,
            JSONObject()
                .put("requestId", requestId)
                .put("reason", reason.code)
                .put("previousScore", prev ?: 0)
                .put("autoBlockAt", deadline)
                .put("app", FamilyPolicy.toAlertApp(app).toJson()),
        )
        FamilyStore.addOpenDecision(context, FamilyStore.OpenDecision(requestId, pkg, app.label, deadline, fit), gen)
        FamilyStore.markDecisionSent(context, pkg, fit, gen)
        FamilyStore.setLastScore(context, pkg, fit, app.riskScore)
        FamilyStore.markReported(context, pkg, fit, app.riskLevel, gen)
        FamilyStore.markSent(context, gen)
    }

    /** 새로 보낼 알림을 모아 요청 한 번으로 보낸다 (한 앱이면 기존 단건 형식) */
    private fun sendAlertsBatched(context: Context, token: String, risky: List<AppInfo>, gen: Int) {
        val todo = risky.mapNotNull { app ->
            val kind = FamilyPolicy.alertKind(FamilyStore.reportedLevel(context, app.packageName, app.firstInstallTime), app.riskLevel)
            kind?.let { app to it }
        }
        if (todo.isEmpty()) return
        if (todo.size == 1) {
            sendAlertIfNeeded(context, token, todo[0].first, gen)
            return
        }
        val items = JSONArray()
        todo.take(BATCH_MAX).forEach { (app, kind) ->
            items.put(JSONObject().put("kind", kind).put("app", FamilyPolicy.toAlertApp(app).toJson()))
        }
        FamilyApi.post(token, "alerts", JSONObject().put("items", items))
        todo.take(BATCH_MAX).forEach { (app, _) -> FamilyStore.markReported(context, app.packageName, app.firstInstallTime, app.riskLevel, gen) }
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

    /** 연결이 승인된 부모 폰의 토큰 (승인 전이면 null — 서버도 보내기를 거부한다) */
    private fun parentToken(context: Context): String? {
        FamilyStore.ensureLoaded(context)
        val s = FamilyStore.state.value
        if (s.role != FamilyRole.PARENT || !s.paired) return null
        return FamilyStore.token(context)
    }

    /**
     * 부모 폰: 다른 VPN 앱이 차단 VPN 자리를 가져가면 자녀에게 알린다. VPN 스레드에서 불리므로 전송은 별도 스레드에서.
     * 부모 역할이 아니거나 연결 전이면 아무것도 안 함.
     */
    fun registerVpnRevokeListener() {
        VpnGuard.onRevoked = { ctx, e ->
            val app = ctx.applicationContext
            thread(name = "pg-vpn-revoked", isDaemon = true) {
                val token = parentToken(app) ?: return@thread
                try {
                    FamilyApi.post(token, FamilyEvent.TYPE_VPN_REVOKED, JSONObject().put("time", e.time).put("mode", e.mode))
                } catch (ex: FamilyApiException) {
                    handleError(app, ex)
                } catch (ex: IOException) {
                    // 다른 VPN이 켜진 직후라 인터넷이 막혔을 수 있다. 기록은 VpnGuard에 남아 있다
                    Log.w(TAG, "VPN 해제 알림 전송 실패", ex)
                }
            }
        }
    }

    /** 명령 실행 결과·자동 차단을 자녀에게 알림 */
    fun postResult(context: Context, data: JSONObject) {
        val token = parentToken(context) ?: return
        try {
            FamilyApi.post(token, FamilyEvent.TYPE_COMMAND_RESULT, data)
        } catch (e: FamilyApiException) {
            handleError(context, e)
        } catch (e: IOException) {
            Log.w(TAG, "결과 전송 실패", e)
        }
    }
}
