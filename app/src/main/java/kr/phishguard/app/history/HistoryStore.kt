package kr.phishguard.app.history

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kr.phishguard.app.scan.InstallSource
import kr.phishguard.app.scan.RiskLevel
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 설치·삭제 기록 저장 상태. snapshot == null이면 아직 기준점이 없다. */
data class HistoryState(
    val snapshot: Map<String, SnapshotEntry>?,
    val events: List<HistoryEvent>,
    val lastChangedSeq: Int,
    val lastBootCount: Int,
    val baselineCreatedAt: Long?,
)

/** filesDir/app_history.json에 기록을 보관한다. 모든 접근은 lock으로 직렬화. */
object HistoryStore {
    private const val TAG = "HistoryStore"
    private const val FILE_NAME = "app_history.json"
    const val MAX_EVENTS = 500
    const val UNKNOWN_BOOT_COUNT = -1

    private val lock = Any()
    private var state: HistoryState? = null

    private val _events = MutableStateFlow<List<HistoryEvent>>(emptyList())
    val events: StateFlow<List<HistoryEvent>> = _events

    private val _baselineCreatedAt = MutableStateFlow<Long?>(null)
    val baselineCreatedAt: StateFlow<Long?> = _baselineCreatedAt

    /** 파일에서 읽어 둔다 (IO 스레드에서 호출). 이미 읽었으면 아무것도 하지 않는다. */
    fun ensureLoaded(context: Context) {
        synchronized(lock) { loadLocked(context.applicationContext) }
    }

    /** 상태를 바꾸고 파일에 저장한다 (IO 스레드에서 호출). */
    fun update(context: Context, transform: (HistoryState) -> HistoryState) {
        synchronized(lock) {
            val appContext = context.applicationContext
            val next = transform(loadLocked(appContext))
            val capped = next.copy(events = next.events.take(MAX_EVENTS))
            state = capped
            publish(capped)
            save(appContext, capped)
        }
    }

    private fun loadLocked(context: Context): HistoryState {
        state?.let { return it }
        val file = File(context.filesDir, FILE_NAME)
        val loaded = if (file.exists()) {
            try {
                fromJson(JSONObject(file.readText(Charsets.UTF_8)))
            } catch (e: IOException) {
                Log.e(TAG, "history read failed", e)
                null
            } catch (e: JSONException) {
                Log.e(TAG, "history parse failed", e)
                null
            }
        } else {
            null
        }
        val result = loaded ?: HistoryState(null, emptyList(), 0, UNKNOWN_BOOT_COUNT, null)
        state = result
        publish(result)
        return result
    }

    private fun publish(s: HistoryState) {
        _events.value = s.events
        _baselineCreatedAt.value = s.baselineCreatedAt
    }

    private fun save(context: Context, s: HistoryState) {
        val file = File(context.filesDir, FILE_NAME)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        try {
            tmp.writeText(toJson(s).toString(), Charsets.UTF_8)
            Files.move(
                tmp.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: IOException) {
            Log.e(TAG, "history write failed", e)
        } catch (e: JSONException) {
            Log.e(TAG, "history serialize failed", e)
        }
    }

    // ---- JSON ----

    private fun toJson(s: HistoryState): JSONObject = JSONObject().apply {
        val snap = s.snapshot
        if (snap != null) {
            val o = JSONObject()
            snap.forEach { (pkg, e) ->
                o.put(pkg, JSONObject().apply {
                    put("label", e.label)
                    putOpt("versionName", e.versionName)
                    putOpt("installer", e.installer)
                    putOpt("initiating", e.initiating)
                    put("installSource", e.installSource.name)
                    put("firstInstallTime", e.firstInstallTime)
                    put("lastUpdateTime", e.lastUpdateTime)
                    put("riskLevel", e.riskLevel.name)
                    put("riskScore", e.riskScore)
                    put("isSystem", e.isSystem)
                })
            }
            put("snapshot", o)
        }
        put("events", JSONArray().apply {
            s.events.forEach { e ->
                put(JSONObject().apply {
                    put("type", e.type.name)
                    put("packageName", e.packageName)
                    putOpt("label", e.label)
                    putOpt("versionName", e.versionName)
                    putOpt("installer", e.installer)
                    putOpt("initiating", e.initiating)
                    putOpt("installSource", e.installSource?.name)
                    putOpt("riskLevel", e.riskLevel?.name)
                    put("eventTime", e.eventTime)
                    put("timeIsExact", e.timeIsExact)
                    put("detectedAt", e.detectedAt)
                })
            }
        })
        put("lastChangedSeq", s.lastChangedSeq)
        put("lastBootCount", s.lastBootCount)
        s.baselineCreatedAt?.let { put("baselineCreatedAt", it) }
    }

    private fun fromJson(o: JSONObject): HistoryState {
        val snapshot = o.optJSONObject("snapshot")?.let { snap ->
            val map = LinkedHashMap<String, SnapshotEntry>()
            snap.keys().forEach { pkg ->
                val e = snap.getJSONObject(pkg)
                map[pkg] = SnapshotEntry(
                    label = e.optString("label", pkg),
                    versionName = e.optStringOrNull("versionName"),
                    installer = e.optStringOrNull("installer"),
                    initiating = e.optStringOrNull("initiating"),
                    installSource = enumOrNull<InstallSource>(e.optStringOrNull("installSource")) ?: InstallSource.UNKNOWN,
                    firstInstallTime = e.optLong("firstInstallTime"),
                    lastUpdateTime = e.optLong("lastUpdateTime"),
                    riskLevel = enumOrNull<RiskLevel>(e.optStringOrNull("riskLevel")) ?: RiskLevel.LOW,
                    riskScore = e.optInt("riskScore"),
                    isSystem = e.optBoolean("isSystem"),
                )
            }
            map
        }
        val events = ArrayList<HistoryEvent>()
        val arr = o.optJSONArray("events") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val type = enumOrNull<HistoryEventType>(e.optStringOrNull("type")) ?: continue
            val pkg = e.optStringOrNull("packageName") ?: continue
            events.add(
                HistoryEvent(
                    type = type,
                    packageName = pkg,
                    label = e.optStringOrNull("label"),
                    versionName = e.optStringOrNull("versionName"),
                    installer = e.optStringOrNull("installer"),
                    initiating = e.optStringOrNull("initiating"),
                    installSource = enumOrNull<InstallSource>(e.optStringOrNull("installSource")),
                    riskLevel = enumOrNull<RiskLevel>(e.optStringOrNull("riskLevel")),
                    eventTime = e.optLong("eventTime"),
                    timeIsExact = e.optBoolean("timeIsExact"),
                    detectedAt = e.optLong("detectedAt"),
                ),
            )
        }
        return HistoryState(
            snapshot = snapshot,
            events = events,
            lastChangedSeq = o.optInt("lastChangedSeq", 0),
            lastBootCount = o.optInt("lastBootCount", UNKNOWN_BOOT_COUNT),
            baselineCreatedAt = if (o.has("baselineCreatedAt")) o.optLong("baselineCreatedAt") else null,
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } }
}
