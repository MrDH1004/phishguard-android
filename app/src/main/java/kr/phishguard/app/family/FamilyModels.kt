package kr.phishguard.app.family

import kr.phishguard.app.scan.AppInfo
import kr.phishguard.app.scan.InstallSource
import kr.phishguard.app.scan.RiskLevel
import kr.phishguard.app.scan.ThreatIntel
import org.json.JSONArray
import org.json.JSONObject

/** 이 폰의 가족 연결 역할 */
enum class FamilyRole {
    /** 연결 안 함 */
    NONE,

    /** 자녀(보호자) 폰: PIN을 만들고 부모 폰의 위험 알림을 받는다 */
    CHILD,

    /** 부모(보호받는) 폰: PIN을 입력하고 위험 알림을 보낸다 */
    PARENT,
}

/** 알림에 담는 앱 요약. 전화번호·연락처·위치는 담지 않는다. */
data class AlertApp(
    val packageName: String,
    val label: String,
    val riskLevel: RiskLevel,
    val riskScore: Int,
    val installSource: String,
    val firstInstallTime: Long,
    val reasons: List<String>,
    val remoteControl: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("packageName", packageName)
        .put("label", label)
        .put("riskLevel", riskLevel.name)
        .put("riskScore", riskScore)
        .put("installSource", installSource)
        .put("firstInstallTime", firstInstallTime)
        .put("reasons", JSONArray(reasons))
        .put("remoteControl", remoteControl)

    companion object {
        fun fromJson(o: JSONObject): AlertApp = AlertApp(
            packageName = o.optString("packageName"),
            label = o.optString("label").ifEmpty { o.optString("packageName") },
            riskLevel = runCatching { RiskLevel.valueOf(o.optString("riskLevel")) }.getOrDefault(RiskLevel.LOW),
            riskScore = o.optInt("riskScore"),
            installSource = o.optString("installSource"),
            firstInstallTime = o.optLong("firstInstallTime"),
            reasons = o.optJSONArray("reasons")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            remoteControl = o.optBoolean("remoteControl"),
        )
    }
}

/** 서버에서 받은 사건 하나 */
data class FamilyEvent(
    val id: Long,
    val ts: Long,
    val type: String,
    val data: JSONObject,
) {
    /** alert면 앱 하나, snapshot이면 여러 개 */
    val apps: List<AlertApp>
        get() = when (type) {
            TYPE_ALERT, TYPE_DECISION -> data.optJSONObject("app")?.let { listOf(AlertApp.fromJson(it)) } ?: emptyList()
            TYPE_SNAPSHOT -> data.optJSONArray("apps")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(AlertApp::fromJson) }
            } ?: emptyList()
            else -> emptyList()
        }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("ts", ts).put("type", type).put("data", data)

    companion object {
        const val TYPE_ALERT = "alert"
        const val TYPE_SNAPSHOT = "snapshot"
        const val TYPE_PAIRED = "paired"
        const val TYPE_UNLINKED = "unlinked"
        const val TYPE_PARENT_SILENT = "parent_silent"
        const val TYPE_PARENT_BACK = "parent_back"
        const val TYPE_JOIN_REQUEST = "join_request"
        const val TYPE_JOIN_REJECTED = "join_rejected"
        /** 부모 폰 → 자녀: 위험 기준을 넘은 앱, 잠금/차단/무시를 골라 달라는 요청 */
        const val TYPE_DECISION = "decision"
        /** 자녀가 보낸 명령 기록 */
        const val TYPE_COMMAND_SENT = "command_sent"
        /** 부모 폰이 명령을 실행한 결과 (자동 차단 포함) */
        const val TYPE_COMMAND_RESULT = "command_result"

        fun fromJson(o: JSONObject): FamilyEvent = FamilyEvent(
            id = o.optLong("id"),
            ts = o.optLong("ts"),
            type = o.optString("type"),
            data = o.optJSONObject("data") ?: JSONObject(),
        )
    }
}

/** 자녀 폰 화면용: 아직 답하지 않은 결정 요청 */
data class PendingDecision(
    val requestId: String,
    val app: AlertApp,
    val reason: String,
    val previousScore: Int,
    val autoBlockAt: Long,
    val requestedAt: Long,
)

/** 부모 폰이 무엇을 보낼지 정하는 순수 로직 (Android 의존 없음) */
object FamilyPolicy {
    /** 근거는 점수가 큰 것부터 이만큼만 보낸다 */
    const val MAX_REASONS = 3

    /** 보고 대상: 위험 중간 이상이거나 원격조종 앱. 시스템 앱·이 앱·안전앱으로 표시한 앱은 제외 */
    fun shouldReport(app: AppInfo, trusted: Boolean, selfPackage: String): Boolean {
        if (trusted || app.packageName == selfPackage) return false
        if (app.isSystem || app.installSource == InstallSource.SYSTEM) return false
        return app.riskLevel != RiskLevel.LOW || isRemoteControl(app)
    }

    fun isRemoteControl(app: AppInfo): Boolean = ThreatIntel.isRemoteControl(app.packageName, app.label)

    fun toAlertApp(app: AppInfo): AlertApp = AlertApp(
        packageName = app.packageName,
        label = app.label,
        riskLevel = app.riskLevel,
        riskScore = app.riskScore,
        installSource = app.installSource.label,
        firstInstallTime = app.firstInstallTime,
        reasons = app.matchedRules.filter { it.points > 0 }.sortedByDescending { it.points }
            .take(MAX_REASONS).map { it.description },
        remoteControl = isRemoteControl(app),
    )

    /**
     * 이미 보낸 수준([reported], 없으면 null)과 지금 수준을 비교해 보낼 종류를 정한다.
     * 처음이면 "installed", 위험도가 올라갔으면 "risk_up", 그 외(같거나 내려감)는 null(안 보냄).
     */
    fun alertKind(reported: RiskLevel?, current: RiskLevel): String? = when {
        reported == null -> "installed"
        rank(current) > rank(reported) -> "risk_up"
        else -> null
    }

    private fun rank(l: RiskLevel) = when (l) {
        RiskLevel.LOW -> 0
        RiskLevel.MEDIUM -> 1
        RiskLevel.HIGH -> 2
    }

    /**
     * 자녀 폰 화면용: 결정 요청 중 아직 처리되지 않은 것. 자녀가 명령을 보냈거나(command_sent)
     * 부모 폰이 결과를 알렸으면(command_result, 자동 차단 포함) 끝난 것으로 본다.
     */
    fun pendingDecisions(events: List<FamilyEvent>): List<PendingDecision> {
        val done = events.filter { it.type == FamilyEvent.TYPE_COMMAND_SENT || it.type == FamilyEvent.TYPE_COMMAND_RESULT }
            .mapNotNull { it.data.optString("requestId").ifEmpty { null } }.toSet()
        return events.filter { it.type == FamilyEvent.TYPE_DECISION }
            .mapNotNull { e ->
                val id = e.data.optString("requestId")
                val app = e.apps.firstOrNull()
                if (id.isEmpty() || id in done || app == null) null
                else PendingDecision(id, app, e.data.optString("reason"), e.data.optInt("previousScore"), e.data.optLong("autoBlockAt"), e.ts)
            }
            .distinctBy { it.requestId } // 부모 폰이 같은 요청을 다시 보낸 경우
            .sortedByDescending { it.requestedAt }
    }

    /** 자녀 폰 화면용: 아직 답하지 않은 연결 요청의 부모 폰 이름 (없으면 null) */
    fun pendingJoinName(events: List<FamilyEvent>, paired: Boolean): String? {
        if (paired) return null
        val last = events.lastOrNull {
            it.type == FamilyEvent.TYPE_JOIN_REQUEST || it.type == FamilyEvent.TYPE_JOIN_REJECTED || it.type == FamilyEvent.TYPE_PAIRED
        } ?: return null
        return if (last.type == FamilyEvent.TYPE_JOIN_REQUEST) last.data.optString("parentName").ifEmpty { "부모 폰" } else null
    }

    /** 자녀 폰 화면용: 사건 목록에서 패키지별 최신 위험 앱 목록 (가장 최근 snapshot 이후 alert를 덮어씀) */
    fun currentRiskyApps(events: List<FamilyEvent>): List<AlertApp> {
        val sorted = events.sortedBy { it.id }
        val lastSnap = sorted.lastOrNull { it.type == FamilyEvent.TYPE_SNAPSHOT }
        val map = LinkedHashMap<String, AlertApp>()
        lastSnap?.apps?.forEach { map[it.packageName] = it }
        sorted.filter { (it.type == FamilyEvent.TYPE_ALERT || it.type == FamilyEvent.TYPE_DECISION) && (lastSnap == null || it.id > lastSnap.id) }
            .forEach { e -> e.apps.forEach { map[it.packageName] = it } }
        return map.values.sortedByDescending { it.riskScore }
    }
}
