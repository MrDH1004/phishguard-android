package kr.phishguard.app.net

import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * '다른 VPN 앱 막기': 사기범·악성앱이 다른 VPN 앱을 켜서 통신을 숨기거나 이 앱의 차단을 풀지 못하게
 * 이 앱의 VPN이 VPN 자리를 늘 차지한다 ([VpnPlan.Mode.STANDBY]). 기본 꺼짐.
 */
object VpnGuard {
    private const val TAG = "PG-VpnGuard"
    private const val PREF = "vpn_guard"
    private const val KEY_HOLD = "hold_slot"
    private const val KEY_PAY = "pause_for_payment"
    private const val FILE = "vpn_revokes.json"
    private const val MAX_EVENTS = 50

    private val _hold = MutableStateFlow(false)
    /** 다른 VPN 앱 막기 켜짐 */
    val hold: StateFlow<Boolean> = _hold

    private val _pauseForPayment = MutableStateFlow(true)
    /** 삼성페이를 쓰는 동안 대기 VPN을 잠시 내림 (옵션이 켜졌을 때 기본 켜짐) */
    val pauseForPayment: StateFlow<Boolean> = _pauseForPayment

    /** 서비스가 확인한 항상 켜진 VPN 상태. null = 모름(서비스 꺼짐 또는 Android 9 이하) */
    data class AlwaysOnStatus(val alwaysOn: Boolean?, val lockdown: Boolean?)

    private val _status = MutableStateFlow(AlwaysOnStatus(null, null))
    val status: StateFlow<AlwaysOnStatus> = _status

    private val _paused = MutableStateFlow(false)
    /** 결제 앱 때문에 대기 VPN을 잠시 내린 상태 */
    val paused: StateFlow<Boolean> = _paused

    /**
     * 다른 VPN이 VPN 자리를 가져간 기록 (onRevoke).
     * [allowed]=true면 가져간 VPN이 사용자가 고른 'VPN 허용 앱'으로 추정됨 (위협 아님, [onRevoked]도 부르지 않음).
     */
    data class RevokeEvent(val time: Long, val mode: String, val allowed: Boolean = false)

    private val _revokes = MutableStateFlow<List<RevokeEvent>>(emptyList())
    val revokes: StateFlow<List<RevokeEvent>> = _revokes

    /**
     * 자리를 빼앗길 때 호출된다 (VPN 서비스 스레드). 가족 연결(부모 폰 → 자녀 폰 알림)이 여기에 연결한다.
     * 예: `VpnGuard.onRevoked = { ctx, e -> FamilyReporter.reportVpnRevoked(ctx, e.time) }`
     */
    @Volatile
    var onRevoked: ((Context, RevokeEvent) -> Unit)? = null

    private var loaded = false

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        _hold.value = p.getBoolean(KEY_HOLD, false)
        _pauseForPayment.value = p.getBoolean(KEY_PAY, true)
        synchronized(this) {
            if (loaded) return
            loaded = true
            _revokes.value = readEvents(File(context.filesDir, FILE))
        }
    }

    fun setHold(context: Context, on: Boolean) {
        _hold.value = on
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_HOLD, on).apply()
    }

    fun setPauseForPayment(context: Context, on: Boolean) {
        _pauseForPayment.value = on
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_PAY, on).apply()
    }

    internal fun updateStatus(status: AlwaysOnStatus) {
        _status.value = status
    }

    internal fun setPaused(p: Boolean) {
        _paused.value = p
    }

    internal fun recordRevoke(context: Context, mode: VpnPlan.Mode, now: Long = System.currentTimeMillis()) {
        load(context)
        VpnAllowList.load(context)
        val allowed = VpnAllowList.takenByAllowed(VpnAllowList.vpnApps(context), VpnAllowList.allowed.value)
        val e = RevokeEvent(now, mode.name, allowed)
        val list = synchronized(this) {
            (_revokes.value + e).takeLast(MAX_EVENTS).also { _revokes.value = it }
        }
        writeEvents(File(context.filesDir, FILE), list)
        if (allowed) {
            Log.i(TAG, "허용된 VPN 앱이 VPN 자리를 가져감 (모드 ${mode.name})")
            return
        }
        Log.w(TAG, "다른 VPN이 VPN 자리를 가져감 (모드 ${mode.name})")
        try {
            onRevoked?.invoke(context, e)
        } catch (ex: RuntimeException) {
            Log.e(TAG, "onRevoked 처리 실패", ex)
        }
    }

    private fun readEvents(f: File): List<RevokeEvent> {
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText(Charsets.UTF_8))
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { RevokeEvent(it.optLong("time"), it.optString("mode"), it.optBoolean("allowed")) } }
        } catch (e: IOException) {
            Log.w(TAG, "기록 읽기 실패", e)
            emptyList()
        } catch (e: JSONException) {
            Log.w(TAG, "기록 형식 오류", e)
            emptyList()
        }
    }

    private fun writeEvents(f: File, list: List<RevokeEvent>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("time", it.time).put("mode", it.mode).put("allowed", it.allowed)) }
        try {
            f.writeText(a.toString(), Charsets.UTF_8)
        } catch (e: IOException) {
            Log.w(TAG, "기록 저장 실패", e)
        }
    }

    /** 지금 앞에 떠 있는 앱 계산용: [since] 이후 RESUMED/PAUSED 이벤트 (시간순). 사용 정보 접근 권한이 없으면 null */
    internal fun screenEvents(context: Context, since: Long, until: Long): List<Pair<String, Int>>? {
        if (!UsageStats.hasAccess(context)) return null
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val out = ArrayList<Pair<String, Int>>()
        val events = usm.queryEvents(since, until) ?: return out
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val t = e.eventType
            if (t == VpnPlan.EVENT_RESUMED || t == VpnPlan.EVENT_PAUSED) out += e.packageName to t
        }
        return out
    }

    /**
     * 기기 소유자 모드에서 이 앱을 '항상 켜진 VPN + 연결 차단'으로 고정한다 (사용자가 설정에서 바꿀 수 없음).
     * 설치된 결제 앱은 연결 차단 예외(lockdown allowlist, Android 10+)로 넣어 VPN을 잠시 내렸을 때도 결제가 되게 한다.
     * 호출자는 기기 소유자(DevicePolicyManager.isDeviceOwnerApp)여야 한다. 성공하면 true.
     */
    fun applyOwnerVpnLock(context: Context, admin: ComponentName): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val allow = VpnPlan.PAYMENT_PACKAGES.filter { isInstalled(context, it) }.toSet()
                dpm.setAlwaysOnVpnPackage(admin, context.packageName, true, allow)
            } else {
                dpm.setAlwaysOnVpnPackage(admin, context.packageName, true)
            }
            Log.i(TAG, "항상 켜진 VPN 고정: ${dpm.getAlwaysOnVpnPackage(admin)}")
            true
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "항상 켜진 VPN 고정 실패 (패키지 없음)", e)
            false
        } catch (e: UnsupportedOperationException) {
            Log.e(TAG, "항상 켜진 VPN 고정 실패 (지원 안 함)", e)
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "항상 켜진 VPN 고정 실패 (소유자 아님)", e)
            false
        }
    }

    /** 기기 소유자 모드에서 고정을 푼다 */
    fun clearOwnerVpnLock(context: Context, admin: ComponentName): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        return try {
            dpm.setAlwaysOnVpnPackage(admin, null, false)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "항상 켜진 VPN 해제 실패", e)
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "항상 켜진 VPN 해제 실패 (소유자 아님)", e)
            false
        }
    }

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
