package kr.phishguard.app.family

import kr.phishguard.app.scan.AppInfo
import kr.phishguard.app.scan.InstallOrigin
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
    /** 설치자 기록 (예: com.android.vending). 옛 버전 부모 폰은 보내지 않음 */
    val installerPackage: String? = null,
    /** 실제로 설치를 요청한 앱 (Android 11+, 예: com.kakao.talk). 옛 버전 부모 폰은 보내지 않음 */
    val initiatingPackage: String? = null,
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
        .apply {
            installerPackage?.let { put("installerPackage", it) }
            initiatingPackage?.let { put("initiatingPackage", it) }
        }

    /** 사람이 읽는 설치 출처 한 줄 (예: "APK 직접 설치 (Play 스토어 아님) · 카카오톡에서 받은 파일로 설치 (com.kakao.talk)") */
    fun originText(): String = InstallOrigin.describe(sourceOf(installSource), installerPackage, initiatingPackage)

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
            installerPackage = o.optString("installerPackage").ifEmpty { null },
            initiatingPackage = o.optString("initiatingPackage").ifEmpty { null },
        )

        /**
         * installSource는 부모 폰이 보낸 이름 문자열이라 다시 enum으로 바꾼다.
         * 옛 버전은 직접 설치를 "알 수 없음(APK 직접 설치)"로 보냈다. 모르는 문자열은 직접 설치로 본다(가장 의심스러운 쪽).
         */
        fun sourceOf(label: String): InstallSource =
            InstallSource.entries.firstOrNull { it.label == label }
                ?: when {
                    label.contains("Play") -> InstallSource.PLAY_STORE
                    label.contains("시스템") -> InstallSource.SYSTEM
                    label.contains("스토어") -> InstallSource.OTHER_STORE
                    else -> InstallSource.UNKNOWN
                }
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
        /** 부모 폰 → 자녀: 기기 소유자 모드를 풀어 달라는 요청 (자녀가 승인해야 풀림) */
        const val TYPE_OWNER_RELEASE_REQUEST = "owner_release_request"
        /** 부모 폰 → 자녀: 다른 VPN 앱이 차단 VPN을 끔 (data: time, mode) */
        const val TYPE_VPN_REVOKED = "vpn_revoked"

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
        installerPackage = app.installerPackage,
        initiatingPackage = app.initiatingPackage,
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

    /**
     * 자녀 폰 화면용: 사건 목록에서 패키지별 최신 위험 앱 목록 (가장 최근 snapshot 이후 alert를 덮어씀).
     * 삭제 요청으로 지워졌다고 알려 온 앱은 뺀다 (그 뒤에 다시 설치돼 알림이 오면 다시 보임).
     */
    fun currentRiskyApps(events: List<FamilyEvent>): List<AlertApp> {
        val sorted = events.sortedBy { it.id }
        val lastSnap = sorted.lastOrNull { it.type == FamilyEvent.TYPE_SNAPSHOT }
        val map = LinkedHashMap<String, AlertApp>()
        lastSnap?.apps?.forEach { map[it.packageName] = it }
        sorted.filter { lastSnap == null || it.id > lastSnap.id }.forEach { e ->
            when {
                e.type == FamilyEvent.TYPE_ALERT || e.type == FamilyEvent.TYPE_DECISION -> e.apps.forEach { map[it.packageName] = it }
                isUninstallRemoved(e) -> map.remove(e.data.optString("packageName"))
            }
        }
        return map.values.sortedByDescending { it.riskScore }
    }

    private fun isUninstallRemoved(e: FamilyEvent) =
        e.type == FamilyEvent.TYPE_COMMAND_RESULT && e.data.optString("type") == TYPE_UNINSTALL && e.data.optString("stage") == "removed"

    /** 자녀 폰 화면용: 이 앱의 마지막 삭제 요청 상태 문구 (보냄 / 부모 폰 결과). 없으면 null */
    fun uninstallStatus(events: List<FamilyEvent>, pkg: String): String? =
        commandStatus(events, pkg, setOf(TYPE_UNINSTALL), "삭제 요청")

    /** 자녀 폰 화면용: 부모 폰의 관리 모드 해제 요청에 아직 답하지 않았는지 (마지막 요청 뒤에 해제 명령을 안 보냄) */
    fun pendingOwnerRelease(events: List<FamilyEvent>): Boolean = pendingOwnerReleaseRequest(events) != null

    /** 답하지 않은 해제 요청 사건 (data.unlink=true면 '연결 끊기' 요청). 없으면 null */
    fun pendingOwnerReleaseRequest(events: List<FamilyEvent>): FamilyEvent? =
        events.filter {
            it.type == FamilyEvent.TYPE_OWNER_RELEASE_REQUEST ||
                (it.type == FamilyEvent.TYPE_COMMAND_SENT && it.data.optString("type") == TYPE_RELEASE_OWNER)
        }.maxByOrNull { it.id }?.takeIf { it.type == FamilyEvent.TYPE_OWNER_RELEASE_REQUEST }

    /** 부모 폰의 '연결 끊기'를 어떻게 처리할지 */
    enum class ParentUnlink { NOW, ASK_CHILD }

    /**
     * 기기 소유자 모드가 켜진, 연결이 끝난 부모 폰은 혼자 끊을 수 없다 (끊으면 소유자 모드가 풀려 사기범이 이 앱을 지울 수 있음).
     * 자녀에게 요청하고, 자녀가 승인하면 자녀 쪽에서 끊는다 → 부모 폰은 unlinked를 받고 소유자 모드를 푼다.
     * 아직 승인 전(paired=false)이면 승인해 줄 자녀가 없으므로 바로 끊는다.
     */
    fun parentUnlink(owner: Boolean, paired: Boolean): ParentUnlink =
        if (owner && paired) ParentUnlink.ASK_CHILD else ParentUnlink.NOW

    /** 자녀 폰 화면용: 하루 안에 다른 VPN이 차단 VPN을 끈 마지막 시각 (없으면 null) */
    fun recentVpnRevoke(events: List<FamilyEvent>, now: Long): Long? =
        events.filter { it.type == FamilyEvent.TYPE_VPN_REVOKED }
            .map { e -> e.data.optLong("time").takeIf { it > 0 } ?: e.ts }
            .filter { now - it in 0..24L * 60 * 60 * 1000 }
            .maxOrNull()

    /** 자녀 폰 화면용: 이 앱의 마지막 정지/해제 상태 문구. 없으면 null */
    fun suspendStatus(events: List<FamilyEvent>, pkg: String): String? =
        commandStatus(events, pkg, setOf(TYPE_SUSPEND, TYPE_UNSUSPEND), "정지/해제 요청")

    private fun commandStatus(events: List<FamilyEvent>, pkg: String, types: Set<String>, name: String): String? {
        val last = events.filter {
            (it.type == FamilyEvent.TYPE_COMMAND_SENT || it.type == FamilyEvent.TYPE_COMMAND_RESULT) &&
                it.data.optString("type") in types && it.data.optString("packageName") == pkg
        }.maxByOrNull { it.id } ?: return null
        return if (last.type == FamilyEvent.TYPE_COMMAND_SENT) {
            "$name 보냄 — 부모 폰의 답을 기다리는 중"
        } else {
            last.data.optString("message").ifEmpty { if (last.data.optBoolean("ok")) "$name 처리됨" else "$name 실패" }
        }
    }

    /** 자녀가 보내는 삭제 요청 명령 */
    const val TYPE_UNINSTALL = "uninstall"

    /** 기기 소유자 모드 전용: 앱 정지 / 정지 해제 / 부모 폰 관리(소유자 모드) 해제 */
    const val TYPE_SUSPEND = "suspend"
    const val TYPE_UNSUSPEND = "unsuspend"
    const val TYPE_RELEASE_OWNER = "release_owner"
}
