package kr.phishguard.app.net

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 사용자가 고른 'VPN 허용 앱' (회사 VPN·정상 VPN 앱). SharedPreferences 영속.
 * 이 앱들은 VPN 관련 판정 규칙에서 빠지고, VPN 자리를 가져가도 위협 대신 '허용된 VPN 앱 사용'으로 기록한다.
 * 다른 판정 규칙(원격제어·사칭 등)은 그대로 적용된다.
 */
object VpnAllowList {
    private const val TAG = "PG-VpnAllow"
    private const val PREF = "vpn_allowlist"
    private const val KEY = "allowed_packages"
    private val _allowed = MutableStateFlow<Set<String>>(emptySet())
    val allowed: StateFlow<Set<String>> = _allowed

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        _allowed.value = p.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
    }

    fun isAllowed(pkg: String?): Boolean = pkg != null && pkg in _allowed.value

    fun set(context: Context, pkg: String, allow: Boolean) {
        val next = if (allow) _allowed.value + pkg else _allowed.value - pkg
        _allowed.value = next
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putStringSet(KEY, next).apply()
    }

    /**
     * 자리를 가져간 VPN이 허용 앱인지 추정한다 (순수 함수).
     * 다른 앱의 VPN 주인은 공개 API로 알 수 없으므로, VPN을 켤 수 있는 일반 앱이 모두 허용 앱일 때만 허용으로 본다.
     * 후보가 없거나 허용 안 된 VPN 앱이 하나라도 있으면 false (위협으로 처리).
     */
    fun takenByAllowed(vpnApps: Set<String>, allowed: Set<String>): Boolean =
        vpnApps.isNotEmpty() && allowed.containsAll(vpnApps)

    /** android.net.VpnService를 BIND_VPN_SERVICE로 선언한 일반 앱 (시스템 앱·[self] 제외) */
    fun vpnApps(context: Context, self: String = context.packageName): Set<String> = try {
        val pm = context.packageManager
        val intent = Intent("android.net.VpnService")
        val list = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, 0)
        }
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        list.mapNotNull { it.serviceInfo }
            .filter { it.permission == "android.permission.BIND_VPN_SERVICE" && it.packageName != self }
            .filter { (it.applicationInfo.flags and systemFlags) == 0 }
            .map { it.packageName }.toSet()
    } catch (e: RuntimeException) {
        Log.w(TAG, "vpn service query failed", e)
        emptySet()
    }
}
