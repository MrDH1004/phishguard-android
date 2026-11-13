package kr.phishguard.app.guard

/**
 * 자녀 폰에 "결정 요청"을 보낼지 정하는 순수 로직 (Android 의존 없음).
 *
 * - 점수가 기준(100~150, 기본 150) 이상이면 요청
 * - 또는 이전 점수보다 50점 넘게 뛰어 100점 이상이 되면 요청 (권한을 몰래 더 받거나 기능을 켠 경우)
 * 자녀가 30분 안에 답하지 않으면 그 앱의 인터넷만 자동으로 끊는다. 잠금은 자녀만 결정한다.
 */
object GuardPolicy {
    const val THRESHOLD_MIN = 100
    const val THRESHOLD_MAX = 150
    const val THRESHOLD_DEFAULT = 150
    const val THRESHOLD_STEP = 10

    const val SURGE_DELTA = 50
    const val SURGE_MIN_SCORE = 100

    const val AUTO_BLOCK_DELAY_MS = 30L * 60 * 1000
    const val LOCK_DURATION_MS = 60L * 60 * 1000

    /**
     * 사기범이 연락 창구로 쓰는 메신저. 잠금·차단 때 인터넷을 같이 끊고, 잠금이 풀린 뒤 삭제를 권한다.
     * 패키지명 확인: Telegram(Play 스토어 org.telegram.messenger), Telegram X(org.thunderdog.challegram),
     * LINE(피해 폰에서 직접 확인한 jp.naver.line.android).
     */
    val MESSENGER_PACKAGES: List<String> = listOf(
        "org.telegram.messenger",
        "org.thunderdog.challegram",
        "jp.naver.line.android",
    )

    enum class Reason(val code: String) { SCORE("score"), SURGE("surge") }

    fun clampThreshold(v: Int): Int {
        val c = v.coerceIn(THRESHOLD_MIN, THRESHOLD_MAX)
        return THRESHOLD_MIN + ((c - THRESHOLD_MIN) / THRESHOLD_STEP) * THRESHOLD_STEP
    }

    /** [previous]는 마지막으로 본 점수(처음이면 null) */
    fun trigger(score: Int, previous: Int?, threshold: Int): Reason? = when {
        score >= clampThreshold(threshold) -> Reason.SCORE
        previous != null && score - previous >= SURGE_DELTA && score >= SURGE_MIN_SCORE -> Reason.SURGE
        else -> null
    }

    /** 잠금 남은 시간 "59:05" */
    fun remainingText(untilMs: Long, nowMs: Long): String {
        val s = ((untilMs - nowMs) / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}
