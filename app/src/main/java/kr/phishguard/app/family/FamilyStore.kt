package kr.phishguard.app.family

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kr.phishguard.app.scan.RiskLevel
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** 가족 연결 상태 (화면·서비스 공유) */
data class FamilyState(
    val role: FamilyRole = FamilyRole.NONE,
    /** 연결이 끝났는지. 자녀가 PIN만 만든 상태면 false */
    val paired: Boolean = false,
    /** 상대 폰 이름 */
    val otherName: String? = null,
    /** 자녀: 표시 중인 PIN과 만료 시각 */
    val pin: String? = null,
    val pinExpiresAt: Long = 0,
    /** 자녀: 부모 폰 마지막 신호 시각 */
    val lastHeartbeat: Long = 0,
    val heartbeatHigh: Int = 0,
    val heartbeatMedium: Int = 0,
    /** 자녀: 부모 폰의 실시간 설치 감지가 켜져 있는지 (마지막 신호 기준) */
    val parentGuardOn: Boolean = true,
    /** 자녀: 부모 폰이 기기 소유자 모드인지 (마지막 신호 기준). 켜져 있으면 정지·해제 버튼을 보인다 */
    val parentOwnerMode: Boolean = false,
    /** 자녀: 실시간 연결 상태 */
    val live: Boolean = false,
    /** 자녀: 받은 사건 (최신 100개) */
    val events: List<FamilyEvent> = emptyList(),
    /** 부모: 마지막으로 서버에 보낸 시각 */
    val lastSentAt: Long = 0,
    /** 마지막 오류 (화면 표시용) */
    val lastError: String? = null,
)

/**
 * 가족 연결 정보 저장. 토큰은 이 기기 밖으로 나가지 않는다 (백업·기기 이전 제외: res/xml/data_extraction_rules.xml).
 * 여러 스레드(WebSocket·IO·화면)가 고치므로 모든 변경은 [lock] 안에서 하고, [clear] 뒤에 끝난 늦은 변경은 세대 번호로 버린다.
 */
object FamilyStore {
    private const val TAG = "PG-FamilyStore"
    private const val PREF = "family"
    private const val PREF_PENDING = "family_pending"
    private const val MAX_EVENTS = 100

    private val lock = Any()
    private var generation = 0

    private val _state = MutableStateFlow(FamilyState())
    val state: StateFlow<FamilyState> = _state

    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val p = prefs(context)
            _state.value = FamilyState(
                role = runCatching { FamilyRole.valueOf(p.getString("role", null) ?: "NONE") }.getOrDefault(FamilyRole.NONE),
                paired = p.getBoolean("paired", false),
                otherName = p.getString("other_name", null),
                pin = p.getString("pin", null),
                pinExpiresAt = p.getLong("pin_expires", 0),
                lastHeartbeat = p.getLong("last_hb", 0),
                heartbeatHigh = p.getInt("hb_high", 0),
                heartbeatMedium = p.getInt("hb_medium", 0),
                parentGuardOn = p.getBoolean("hb_guard", true),
                parentOwnerMode = p.getBoolean("hb_owner", false),
                events = parseEvents(p.getString("events", null)),
                lastSentAt = p.getLong("last_sent", 0),
            )
            loaded = true
        }
    }

    fun token(context: Context): String? = prefs(context).getString("token", null)

    fun lastEventId(): Long = _state.value.events.maxOfOrNull { it.id } ?: 0

    /** 세대 번호. 비동기 작업 시작 때 잡아 두고 끝날 때 [isCurrent]로 확인 */
    fun generation(): Int = synchronized(lock) { generation }

    fun isCurrent(gen: Int): Boolean = synchronized(lock) { gen == generation }

    // ---- 역할 시작/끝 ----

    fun startChild(context: Context, token: String, pin: String, pinExpiresAt: Long) = synchronized(lock) {
        clearLocked(context)
        prefs(context).edit()
            .putString("role", FamilyRole.CHILD.name).putString("token", token)
            .putString("pin", pin).putLong("pin_expires", pinExpiresAt).putBoolean("paired", false)
            .apply()
        _state.value = FamilyState(role = FamilyRole.CHILD, pin = pin, pinExpiresAt = pinExpiresAt)
    }

    fun updatePin(context: Context, pin: String, expiresAt: Long) = synchronized(lock) {
        prefs(context).edit().putString("pin", pin).putLong("pin_expires", expiresAt).apply()
        _state.value = _state.value.copy(pin = pin, pinExpiresAt = expiresAt, lastError = null)
    }

    /** 부모: PIN 입력 직후. 자녀 폰이 승인하기 전까지 paired = false */
    fun startParent(context: Context, token: String, childName: String) = synchronized(lock) {
        clearLocked(context)
        prefs(context).edit()
            .putString("role", FamilyRole.PARENT.name).putString("token", token)
            .putString("other_name", childName).putBoolean("paired", false)
            .apply()
        _state.value = FamilyState(role = FamilyRole.PARENT, paired = false, otherName = childName)
    }

    /** 연결 정보·사건 모두 지움 */
    fun clear(context: Context) = synchronized(lock) { clearLocked(context) }

    private fun clearLocked(context: Context) {
        generation++
        prefs(context).edit().clear().apply()
        _state.value = FamilyState()
    }

    // ---- 자녀: 받은 내용 반영 (세대가 바뀌었으면 버림) ----

    fun applyState(context: Context, o: JSONObject, gen: Int) = mutate(context, gen) { cur ->
        val paired = o.optBoolean("paired")
        val parentName = o.optString("parentName").takeIf { o.has("parentName") && !o.isNull("parentName") }
        val hb = o.optJSONObject("heartbeat")
        val incoming = o.optJSONArray("events")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(FamilyEvent::fromJson) }
        } ?: emptyList()
        cur.copy(
            paired = paired || cur.paired,
            otherName = parentName ?: cur.otherName,
            pin = if (paired) null else cur.pin,
            lastHeartbeat = maxOf(o.optLong("lastHeartbeat"), cur.lastHeartbeat),
            heartbeatHigh = hb?.optInt("high") ?: cur.heartbeatHigh,
            heartbeatMedium = hb?.optInt("medium") ?: cur.heartbeatMedium,
            parentGuardOn = hb?.optBoolean("guardOn", true) ?: cur.parentGuardOn,
            parentOwnerMode = hb?.optBoolean("ownerMode", false) ?: cur.parentOwnerMode,
            events = merge(cur.events, incoming),
            lastError = null,
        )
    }

    fun addEvents(context: Context, incoming: List<FamilyEvent>, gen: Int) = mutate(context, gen) { cur ->
        var s = cur.copy(events = merge(cur.events, incoming.filter { it.id > 0 }))
        incoming.firstOrNull { it.type == FamilyEvent.TYPE_PAIRED }?.let { e ->
            s = s.copy(paired = true, pin = null, otherName = e.data.optString("parentName").ifEmpty { s.otherName })
        }
        s
    }

    fun applyHeartbeat(context: Context, ts: Long, hb: JSONObject?, gen: Int) = mutate(context, gen) { cur ->
        cur.copy(
            lastHeartbeat = ts,
            heartbeatHigh = hb?.optInt("high") ?: cur.heartbeatHigh,
            heartbeatMedium = hb?.optInt("medium") ?: cur.heartbeatMedium,
            parentGuardOn = hb?.optBoolean("guardOn", true) ?: cur.parentGuardOn,
            parentOwnerMode = hb?.optBoolean("ownerMode", false) ?: cur.parentOwnerMode,
        )
    }

    /**
     * 아직 알림을 띄우지 않은 사건을 꺼내고 "띄움"으로 기록한다. WebSocket과 주기 확인이 동시에 불러도 한 번만 나온다.
     */
    fun takeUnnotified(context: Context, gen: Int): List<FamilyEvent> {
        synchronized(lock) {
            if (gen != generation) return emptyList()
            val p = prefs(context)
            val last = p.getLong("notified_id", 0)
            val fresh = _state.value.events.filter { it.id > last }
            if (fresh.isNotEmpty()) p.edit().putLong("notified_id", fresh.maxOf { it.id }).apply()
            return fresh
        }
    }

    fun setLive(live: Boolean) = synchronized(lock) { _state.value = _state.value.copy(live = live) }

    fun setError(message: String?) = synchronized(lock) { _state.value = _state.value.copy(lastError = message) }

    // ---- 부모: 보낸 기록 ----

    fun reportedLevel(context: Context, pkg: String, firstInstallTime: Long): RiskLevel? {
        val v = prefs(context).getString("rep:$pkg", null) ?: return null
        val parts = v.split('|')
        if (parts.size != 2 || parts[1].toLongOrNull() != firstInstallTime) return null // 다시 설치됨 → 새로 보고
        return runCatching { RiskLevel.valueOf(parts[0]) }.getOrNull()
    }

    fun markReported(context: Context, pkg: String, firstInstallTime: Long, level: RiskLevel, gen: Int) = synchronized(lock) {
        if (gen == generation) prefs(context).edit().putString("rep:$pkg", "${level.name}|$firstInstallTime").apply()
    }

    fun markSent(context: Context, gen: Int) {
        synchronized(lock) {
            if (gen != generation) return
            val now = System.currentTimeMillis()
            prefs(context).edit().putLong("last_sent", now).apply()
            _state.value = _state.value.copy(lastSentAt = now, lastError = null)
        }
    }

    fun snapshotSent(context: Context): Boolean = prefs(context).getBoolean("snapshot_sent", false)

    fun markSnapshotSent(context: Context, gen: Int) = synchronized(lock) {
        if (gen == generation) prefs(context).edit().putBoolean("snapshot_sent", true).apply()
    }

    // ---- 부모: 승인·명령·결정 요청 ----

    /** 자녀 폰이 연결을 승인함 */
    fun setApproved(context: Context, gen: Int) = mutate(context, gen) { it.copy(paired = true, lastError = null) }

    /** 부모: 기기 소유자 모드에서 자녀에게 '연결 끊기' 승인을 요청한 시각 (0 = 없음). 연결이 끊기면 같이 지워짐 */
    fun unlinkRequestedAt(context: Context): Long = prefs(context).getLong("unlink_req", 0)

    fun setUnlinkRequested(context: Context, at: Long) {
        prefs(context).edit().putLong("unlink_req", at).apply()
    }

    fun lastCommandId(context: Context): Long = prefs(context).getLong("last_cmd", 0)

    /** 명령을 한 번만 실행하도록: 처음 보는 id면 true를 돌려주고 기록 */
    fun claimCommand(context: Context, id: Long, gen: Int): Boolean {
        synchronized(lock) {
            if (gen != generation) return false
            val p = prefs(context)
            if (id <= p.getLong("last_cmd", 0)) return false
            p.edit().putLong("last_cmd", id).apply()
            return true
        }
    }

    fun threshold(context: Context): Int =
        kr.phishguard.app.guard.GuardPolicy.clampThreshold(
            prefs(context).getInt("threshold", kr.phishguard.app.guard.GuardPolicy.THRESHOLD_DEFAULT),
        )

    fun setThreshold(context: Context, v: Int) {
        prefs(context).edit().putInt("threshold", kr.phishguard.app.guard.GuardPolicy.clampThreshold(v)).apply()
    }

    /** 마지막으로 본 점수 (설치가 바뀌면 새 앱으로 봄) */
    fun lastScore(context: Context, pkg: String, firstInstallTime: Long): Int? {
        val v = prefs(context).getString("score:$pkg", null) ?: return null
        val parts = v.split('|')
        if (parts.size != 2 || parts[1].toLongOrNull() != firstInstallTime) return null
        return parts[0].toIntOrNull()
    }

    fun setLastScore(context: Context, pkg: String, firstInstallTime: Long, score: Int) {
        prefs(context).edit().putString("score:$pkg", "$score|$firstInstallTime").apply()
    }

    /** 부모 폰: 자녀 답을 기다리는 결정 요청 (requestId → pkg|label|deadline|firstInstallTime) */
    data class OpenDecision(val requestId: String, val pkg: String, val label: String, val deadline: Long, val firstInstallTime: Long)

    fun decisionRequested(context: Context, pkg: String, firstInstallTime: Long): Boolean =
        prefs(context).getBoolean("dreq:$pkg|$firstInstallTime", false)

    /** 열린 결정 요청 저장. 같은 requestId가 이미 있으면 처음 마감 시각을 유지 */
    fun addOpenDecision(context: Context, d: OpenDecision, gen: Int) {
        synchronized(lock) {
            if (gen != generation) return
            if (prefs(context).contains("open:${d.requestId}")) return
            prefs(context).edit()
                .putString("open:${d.requestId}", listOf(d.pkg, d.label.replace("|", " "), d.deadline, d.firstInstallTime).joinToString("|"))
                .apply()
        }
    }

    fun openDecisions(context: Context): List<OpenDecision> =
        prefs(context).all.filterKeys { it.startsWith("open:") }.mapNotNull { (k, v) ->
            val parts = (v as? String)?.split('|') ?: return@mapNotNull null
            if (parts.size != 4) return@mapNotNull null
            OpenDecision(k.removePrefix("open:"), parts[0], parts[1], parts[2].toLongOrNull() ?: 0, parts[3].toLongOrNull() ?: 0)
        }

    /** 열린 결정 요청을 꺼내며 닫는다. 이미 닫혔으면(다른 경로가 먼저 처리) null — 같은 결정은 한 번만 처리된다 */
    fun claimOpenDecision(context: Context, requestId: String): OpenDecision? {
        synchronized(lock) {
            val d = openDecisions(context).firstOrNull { it.requestId == requestId } ?: return null
            prefs(context).edit().remove("open:$requestId").apply()
            return d
        }
    }

    /** 결정 요청을 자녀에게 보냈음 (설치 한 번당 한 번) */
    fun markDecisionSent(context: Context, pkg: String, firstInstallTime: Long, gen: Int) {
        synchronized(lock) {
            if (gen == generation) prefs(context).edit().putBoolean("dreq:$pkg|$firstInstallTime", true).apply()
        }
    }

    // ---- 부모: 생존 신호를 꼭 필요할 때만 보내기 ----

    fun lastHeartbeatSignature(context: Context): String? = prefs(context).getString("hb_sig", null)

    fun lastHeartbeatSentAt(context: Context): Long = prefs(context).getLong("hb_sent", 0)

    fun markHeartbeatSent(context: Context, signature: String, at: Long) {
        prefs(context).edit().putString("hb_sig", signature).putLong("hb_sent", at).apply()
    }

    // ---- 서버에 못 알린 연결 끊기 (다음에 다시 보냄) ----

    fun addPendingUnlink(context: Context, token: String) {
        val p = pendingPrefs(context)
        p.edit().putStringSet("tokens", p.getStringSet("tokens", emptySet()).orEmpty() + token).apply()
    }

    fun pendingUnlinks(context: Context): Set<String> = pendingPrefs(context).getStringSet("tokens", emptySet()).orEmpty()

    fun removePendingUnlink(context: Context, token: String) {
        val p = pendingPrefs(context)
        p.edit().putStringSet("tokens", p.getStringSet("tokens", emptySet()).orEmpty() - token).apply()
    }

    // ---- 내부 ----

    private inline fun mutate(context: Context, gen: Int, f: (FamilyState) -> FamilyState) = synchronized(lock) {
        if (gen != generation) return@synchronized
        save(context, f(_state.value))
    }

    private fun merge(old: List<FamilyEvent>, incoming: List<FamilyEvent>): List<FamilyEvent> {
        val byId = LinkedHashMap<Long, FamilyEvent>()
        (old + incoming).filter { it.id > 0 }.forEach { byId[it.id] = it }
        return byId.values.sortedBy { it.id }.takeLast(MAX_EVENTS)
    }

    private fun save(context: Context, s: FamilyState) {
        _state.value = s
        val arr = JSONArray()
        s.events.forEach { arr.put(it.toJson()) }
        prefs(context).edit()
            .putBoolean("paired", s.paired)
            .putString("other_name", s.otherName)
            .putString("pin", s.pin)
            .putLong("pin_expires", s.pinExpiresAt)
            .putLong("last_hb", s.lastHeartbeat)
            .putInt("hb_high", s.heartbeatHigh)
            .putInt("hb_medium", s.heartbeatMedium)
            .putBoolean("hb_guard", s.parentGuardOn)
            .putBoolean("hb_owner", s.parentOwnerMode)
            .putString("events", arr.toString())
            .apply()
    }

    private fun parseEvents(text: String?): List<FamilyEvent> {
        if (text.isNullOrEmpty()) return emptyList()
        return try {
            val a = JSONArray(text)
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(FamilyEvent::fromJson) }
        } catch (e: JSONException) {
            Log.w(TAG, "events parse failed", e)
            emptyList()
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun pendingPrefs(context: Context) = context.applicationContext.getSharedPreferences(PREF_PENDING, Context.MODE_PRIVATE)
}
