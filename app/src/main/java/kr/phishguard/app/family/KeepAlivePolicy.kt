package kr.phishguard.app.family

import android.content.Context

/**
 * 실시간 연결 ping 간격 자동 조절. ping은 휴대폰 데이터 라디오를 깨우므로 배터리에 가장 큰 영향을 준다.
 *
 * - 45초에서 시작해, 같은 간격으로 3번 연속 pong을 받으면 두 배로 늘린다 (최대 4분)
 * - pong이 오지 않으면(통신사·공유기가 놀고 있는 연결을 끊은 것) 그 간격의 절반으로 줄여 기억한다
 * 놓친 알림·명령은 다시 연결할 때 since로 받아 오므로 사라지지 않고 늦어질 뿐이다.
 *
 * 측정 근거 (2026-11-14, PC 회선): Cloudflare Worker WebSocket은 ping 없이 10분 넘게 열려 있었다.
 * 휴대폰 통신사 NAT의 유휴 시간은 기기에서만 알 수 있어 이렇게 스스로 찾게 했다.
 */
object KeepAlivePolicy {
    const val MIN_MS = 45_000L
    const val MAX_MS = 240_000L
    const val PONG_WAIT_MS = 20_000L
    const val STABLE_ROUNDS = 3
    private const val PREF = "family_net"
    private const val KEY = "ping_ms"

    /** 순수 계산: 안정적이면 늘린 값, 아니면 그대로 */
    fun grow(interval: Long): Long = (interval * 2).coerceAtMost(MAX_MS)

    /** 순수 계산: 끊긴 간격의 절반 (최소값 이상) */
    fun shrink(failedInterval: Long): Long = (failedInterval / 2).coerceAtLeast(MIN_MS)

    fun current(context: Context): Long =
        prefs(context).getLong(KEY, MIN_MS).coerceIn(MIN_MS, MAX_MS)

    /** @return 간격을 바꿨으면 true (연속 횟수를 다시 센다) */
    fun onStable(context: Context, interval: Long, stableRounds: Int): Boolean {
        if (stableRounds < STABLE_ROUNDS || interval >= MAX_MS) return false
        prefs(context).edit().putLong(KEY, grow(interval)).apply()
        return true
    }

    fun onTimeout(context: Context, interval: Long) {
        prefs(context).edit().putLong(KEY, shrink(interval)).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
