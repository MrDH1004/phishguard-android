package kr.phishguard.app.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 앱별 인터넷 차단 목록 (SharedPreferences 영속) */
object BlockList {
    private const val PREF = "blocklist"
    private const val KEY = "blocked_packages"
    private val _blocked = MutableStateFlow<Set<String>>(emptySet())
    val blocked: StateFlow<Set<String>> = _blocked

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        _blocked.value = p.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
    }

    fun isBlocked(pkg: String?): Boolean = pkg != null && pkg in _blocked.value

    fun set(context: Context, pkg: String, block: Boolean) {
        val next = if (block) _blocked.value + pkg else _blocked.value - pkg
        _blocked.value = next
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putStringSet(KEY, next).apply()
    }
}
