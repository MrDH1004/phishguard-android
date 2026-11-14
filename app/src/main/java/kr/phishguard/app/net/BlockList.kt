package kr.phishguard.app.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 앱별 인터넷 차단 목록 (SharedPreferences 영속) */
object BlockList {
    private const val PREF = "blocklist"
    private const val KEY = "blocked_packages"
    private const val KEY_FOREIGN = "foreign_blocked_packages"
    private val _blocked = MutableStateFlow<Set<String>>(emptySet())
    val blocked: StateFlow<Set<String>> = _blocked

    /** 해외 전송만 막는 앱 (한국 IP로 가는 통신은 허용) */
    private val _foreign = MutableStateFlow<Set<String>>(emptySet())
    val foreign: StateFlow<Set<String>> = _foreign

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        _blocked.value = p.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
        _foreign.value = p.getStringSet(KEY_FOREIGN, emptySet())?.toSet() ?: emptySet()
    }

    fun isBlocked(pkg: String?): Boolean = pkg != null && pkg in _blocked.value

    /** 전체 차단·해외 차단 중 하나라도 있으면 true (VPN을 켤 이유가 있음) */
    fun hasAny(): Boolean = _blocked.value.isNotEmpty() || _foreign.value.isNotEmpty()

    fun set(context: Context, pkg: String, block: Boolean) {
        val next = if (block) _blocked.value + pkg else _blocked.value - pkg
        _blocked.value = next
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putStringSet(KEY, next).apply()
    }

    fun setForeign(context: Context, pkg: String, block: Boolean) {
        val next = if (block) _foreign.value + pkg else _foreign.value - pkg
        _foreign.value = next
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putStringSet(KEY_FOREIGN, next).apply()
    }
}
