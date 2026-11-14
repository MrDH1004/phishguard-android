package kr.phishguard.app.net

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * 차단된 앱이 접속하려던 곳 기록 (증거용).
 * 차단 VPN에는 차단 대상 앱의 패킷만 들어오므로 다른 앱 정보는 섞이지 않는다.
 * 같은 목적지는 한 줄로 합치고(횟수·처음·마지막 시각), 저장은 모아서 한다 (패킷마다 쓰지 않음).
 */
object BlockedTraffic {
    private const val TAG = "PG-BlockedTraffic"
    private const val FILE = "blocked_traffic.json"
    private const val MAX_ENTRIES = 500
    private const val FLUSH_MS = 30_000L
    /** 해외 전송 차단으로 막힌 IPv4 연결: "foreign-tcp" / "foreign-udp" */
    const val FOREIGN_PREFIX = "foreign-"

    data class Entry(
        /** "dns" 또는 "tcp"/"udp", 해외 전송 차단이면 "foreign-tcp"/"foreign-udp" */
        val kind: String,
        /** 도메인 이름 또는 "주소:포트" */
        val target: String,
        val count: Long,
        val firstSeen: Long,
        val lastSeen: Long,
        /** 기록할 때 차단 중이던 앱 (여러 개면 어느 앱의 시도인지 구분할 수 없음) */
        val blockedApps: Set<String>,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private var loaded = false
    private var dirty = false
    private var lastFlush = 0L

    private val _snapshot = MutableStateFlow<List<Entry>>(emptyList())
    val snapshot: StateFlow<List<Entry>> = _snapshot

    /** 이 프로세스가 지금까지 받은 패킷 수 (배터리 영향 확인용) */
    @Volatile
    var packetsSeen = 0L
        private set

    fun ensureLoaded(context: Context) {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            val f = File(context.filesDir, FILE)
            if (!f.exists()) return
            try {
                val a = JSONArray(f.readText(Charsets.UTF_8))
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val apps = o.optJSONArray("apps")?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.toSet() } ?: emptySet()
                    val e = Entry(o.optString("kind"), o.optString("target"), o.optLong("count"), o.optLong("first"), o.optLong("last"), apps)
                    entries[key(e.kind, e.target)] = e
                }
            } catch (e: IOException) {
                Log.w(TAG, "기록 읽기 실패", e)
            } catch (e: JSONException) {
                Log.w(TAG, "기록 형식 오류", e)
            }
            publish()
        }
    }

    private fun key(kind: String, target: String) = "$kind|$target"

    /** TUN에서 읽은 패킷 하나 (차단 VPN의 읽기 스레드에서 호출) */
    fun onPacket(
        context: Context,
        buf: ByteArray,
        len: Int,
        blockedApps: Set<String>,
        foreignMode: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ) {
        packetsSeen++
        val p = PacketParser.parse(buf, len) ?: return
        val (base, target) = when {
            p.dnsQuery != null -> "dns" to p.dnsQuery
            p.protocol == PacketParser.PROTO_TCP -> {
                // 연결 시작(SYN)만 센다: 재전송·나머지 조각까지 세면 숫자가 부풀려진다
                if (!p.tcpSyn) return
                "tcp" to hostPort(p)
            }
            p.protocol == PacketParser.PROTO_UDP -> "udp" to hostPort(p)
            else -> return // ICMP 등은 기록하지 않음
        }
        // 해외 차단 모드의 IPv4는 해외 대역으로 가는 것만 들어온다. IPv6는 국가와 상관없이 전부 막으므로 해외로 표시하지 않는다
        val kind = if (foreignMode && base != "dns" && p.ipVersion == 4) "$FOREIGN_PREFIX$base" else base
        synchronized(lock) {
            val k = key(kind, target)
            val prev = entries[k]
            entries[k] = if (prev == null) {
                if (entries.size >= MAX_ENTRIES) entries.remove(entries.keys.first())
                Entry(kind, target, 1, now, now, blockedApps)
            } else {
                prev.copy(count = prev.count + 1, lastSeen = now, blockedApps = prev.blockedApps + blockedApps)
            }
            dirty = true
            if (now - lastFlush >= FLUSH_MS) flushLocked(context, now)
        }
    }

    private fun hostPort(p: PacketParser.Packet) = if (p.ipVersion == 6) "[${p.dstAddress}]:${p.dstPort}" else "${p.dstAddress}:${p.dstPort}"

    /** 차단을 끌 때·앱을 닫을 때 남은 기록 저장 */
    fun flush(context: Context) = synchronized(lock) { flushLocked(context, System.currentTimeMillis()) }

    private fun flushLocked(context: Context, now: Long) {
        lastFlush = now
        publish()
        if (!dirty) return
        val a = JSONArray()
        entries.values.forEach { e ->
            a.put(
                JSONObject().put("kind", e.kind).put("target", e.target).put("count", e.count)
                    .put("first", e.firstSeen).put("last", e.lastSeen).put("apps", JSONArray(e.blockedApps.toList())),
            )
        }
        try {
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(a.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(File(context.filesDir, FILE))) Log.w(TAG, "기록 파일 교체 실패")
            dirty = false
        } catch (e: IOException) {
            Log.w(TAG, "기록 저장 실패", e)
        }
    }

    private fun publish() {
        _snapshot.value = entries.values.sortedWith(compareByDescending<Entry> { it.kind == "dns" }.thenByDescending { it.count })
    }

    /** 증거 ZIP용 CSV (해당 앱이 차단 목록에 있었던 기록만) */
    fun csvFor(context: Context, pkg: String): String {
        ensureLoaded(context)
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val sb = StringBuilder("종류,목적지,횟수,처음,마지막,기록 당시 차단 중이던 앱\n")
        synchronized(lock) {
            entries.values.filter { pkg in it.blockedApps }.forEach { e ->
                sb.append(e.kind).append(',').append(e.target).append(',').append(e.count).append(',')
                    .append(fmt.format(java.util.Date(e.firstSeen))).append(',').append(fmt.format(java.util.Date(e.lastSeen))).append(',')
                    .append(e.blockedApps.sorted().joinToString(" ")).append('\n')
            }
        }
        return sb.toString()
    }
}
