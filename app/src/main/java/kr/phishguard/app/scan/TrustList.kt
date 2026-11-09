package kr.phishguard.app.scan

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 사용자가 '아는 앱'으로 표시한 패키지 목록 (SharedPreferences 영속). 검사 결과에서 제외한다. */
object TrustList {
    private const val PREF = "trustlist"
    private const val KEY = "trusted_packages"
    private val _trusted = MutableStateFlow<Set<String>>(emptySet())
    val trusted: StateFlow<Set<String>> = _trusted

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        _trusted.value = p.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
    }

    fun isTrusted(pkg: String?): Boolean = pkg != null && pkg in _trusted.value

    fun set(context: Context, pkg: String, trusted: Boolean) {
        val next = if (trusted) _trusted.value + pkg else _trusted.value - pkg
        _trusted.value = next
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putStringSet(KEY, next).apply()
    }
}
