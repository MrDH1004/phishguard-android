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

    /** 자녀의 삭제 요청: 이보다 오래된 명령은 실행하지 않는다 */
    const val UNINSTALL_MAX_AGE_MS = 2L * 60 * 60 * 1000

    /** 삭제 확인 창을 띄운 뒤 실제로 지워졌는지 기다리는 시간 */
    const val UNINSTALL_WATCH_MS = 2L * 60 * 1000

    /** 삭제 요청 검사 결과. [ok]=true면 자녀에게 성공으로 알린다 (이미 지워진 경우 포함) */
    enum class UninstallCheck(val ok: Boolean, val message: String) {
        RUN(true, ""),
        ALREADY_REMOVED(true, "이미 지워져 있습니다"),
        NO_PACKAGE(false, "지울 앱이 지정되지 않았습니다"),
        TOO_OLD(false, "너무 오래된 명령이라 실행하지 않았습니다"),
        SELF(false, "이 앱 자신은 대상이 될 수 없습니다"),
        SYSTEM(false, "시스템 앱은 대상이 될 수 없습니다"),
        NOT_REPORTED(false, "이 폰이 위험하다고 알린 앱이 아니어서 실행하지 않았습니다"),
        NO_OVERLAY(false, "다른 앱 위에 그리기 권한 없음"),
        NOT_OWNER(false, "부모 폰의 기기 소유자 모드가 꺼져 있습니다"),
    }

    /**
     * 자녀의 "삭제 요청"을 실행해도 되는지. 결정 요청이 없어도 되지만, 이 폰이 위험하다고 알린 앱(또는 열린 결정 요청)이어야 한다.
     * 사기범이 자녀 역할로 연결돼도 은행 앱·메신저·이 앱의 삭제 창을 띄우게 만들 수 없다.
     * [ageMs]는 명령을 만든 뒤 지난 시간 (모르면 0). [reported]는 지금 설치본 기준으로 이 폰이 보낸 적 있는지.
     */
    fun checkUninstall(
        pkg: String,
        selfPackage: String,
        installed: Boolean,
        system: Boolean,
        reported: Boolean,
        ageMs: Long,
        canDraw: Boolean,
    ): UninstallCheck = when {
        pkg.isBlank() -> UninstallCheck.NO_PACKAGE
        ageMs > UNINSTALL_MAX_AGE_MS -> UninstallCheck.TOO_OLD
        pkg == selfPackage -> UninstallCheck.SELF
        !installed -> UninstallCheck.ALREADY_REMOVED
        system -> UninstallCheck.SYSTEM
        !reported -> UninstallCheck.NOT_REPORTED
        !canDraw -> UninstallCheck.NO_OVERLAY
        else -> UninstallCheck.RUN
    }

    /**
     * 기기 소유자 모드의 정지(suspend)/해제(unsuspend) 명령 검사.
     * 정지는 삭제 요청처럼 이 폰이 알린 앱만, 해제는 푸는 것뿐이라 일반 앱이면 된다.
     */
    fun checkSuspend(
        pkg: String,
        selfPackage: String,
        installed: Boolean,
        system: Boolean,
        reported: Boolean,
        ageMs: Long,
        owner: Boolean,
        suspend: Boolean,
    ): UninstallCheck = when {
        pkg.isBlank() -> UninstallCheck.NO_PACKAGE
        ageMs > UNINSTALL_MAX_AGE_MS -> UninstallCheck.TOO_OLD
        pkg == selfPackage -> UninstallCheck.SELF
        !installed -> UninstallCheck.ALREADY_REMOVED
        system -> UninstallCheck.SYSTEM
        suspend && !reported -> UninstallCheck.NOT_REPORTED
        !owner -> UninstallCheck.NOT_OWNER
        else -> UninstallCheck.RUN
    }

    /** 30분 무응답 자동 조치. 잠금은 언제나 자녀만 결정한다 */
    enum class AutoAction { BLOCK, SUSPEND }

    /** 기본은 인터넷 차단만, 기기 소유자 모드면 앱 정지까지 (실행 불가, 증거는 남음) */
    fun autoActions(owner: Boolean): List<AutoAction> =
        if (owner) listOf(AutoAction.BLOCK, AutoAction.SUSPEND) else listOf(AutoAction.BLOCK)

    /** 잠금 남은 시간 "59:05" */
    fun remainingText(untilMs: Long, nowMs: Long): String {
        val s = ((untilMs - nowMs) / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}
