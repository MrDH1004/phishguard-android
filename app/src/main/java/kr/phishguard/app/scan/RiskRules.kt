package kr.phishguard.app.scan

/** 점수 계산 입력. Android 의존성이 없는 순수 데이터 (단위 테스트 가능). */
data class RiskInput(
    val packageName: String,
    val label: String,
    val installSource: InstallSource,
    val isSystem: Boolean,
    val hasLauncherIcon: Boolean,
    val firstInstallTime: Long,
    /** 실제로 허용(granted)된 권한 분류 */
    val grantedCategories: Set<PermCategory>,
    val isActiveDeviceAdmin: Boolean,
    val hasEnabledAccessibility: Boolean,
    val hasEnabledNotificationListener: Boolean,
    /** 이 앱 자신 — 점수가 LOW를 넘지 않도록 제한 */
    val isSelf: Boolean = false,
)

/** 규칙 평가 시점의 입력 + 현재 시각 */
class RuleContext(val input: RiskInput, val nowMillis: Long) {
    val ageMillis: Long get() = nowMillis - input.firstInstallTime

    /** 시스템 앱은 출처/최근설치/아이콘/사칭 규칙 면제 */
    val exemptSystem: Boolean get() = input.isSystem || input.installSource == InstallSource.SYSTEM
}

data class RiskRule(
    val id: String,
    val description: String,
    val points: Int,
    val predicate: (RuleContext) -> Boolean,
)

object RiskRules {
    const val DAY_MS = 24L * 60 * 60 * 1000
    const val THRESHOLD_HIGH = 60
    const val THRESHOLD_MEDIUM = 30

    /** 사칭에 자주 쓰이는 이름 키워드 (라벨은 대소문자 무시, 패키지명은 영문 키워드만 비교) */
    val IMPERSONATION_KEYWORDS = listOf(
        "검찰", "경찰", "금감원", "금융감독", "법원", "수사", "사건조회", "은행", "뱅크", "카드",
        "국세청", "건강보험", "택배", "우체국", "대출", "보안", "백신", "정부", "민원",
        "police", "bank", "card",
    )

    fun matchesImpersonation(input: RiskInput): Boolean {
        val label = input.label.lowercase()
        val pkg = input.packageName.lowercase()
        return IMPERSONATION_KEYWORDS.any { kw ->
            val k = kw.lowercase()
            label.contains(k) || (k.all { ch -> ch in 'a'..'z' } && pkg.contains(k))
        }
    }

    private fun granted(c: RuleContext, cat: PermCategory) = cat in c.input.grantedCategories

    /** 규칙 표: 한 곳에서 id / 설명 / 점수 / 조건 정의. 일치한 규칙의 점수는 모두 합산된다. */
    val ALL: List<RiskRule> = listOf(
        RiskRule("src_unknown", "알 수 없는 출처(APK 직접 설치)로 설치됨", 30) {
            !it.exemptSystem && it.input.installSource == InstallSource.UNKNOWN
        },
        RiskRule("src_other_store", "Play 스토어 외 다른 스토어/앱으로 설치됨", 5) {
            !it.exemptSystem && it.input.installSource == InstallSource.OTHER_STORE
        },
        RiskRule("recent_7d", "최근 7일 이내 설치됨", 15) {
            !it.exemptSystem && it.ageMillis in 0..(7 * DAY_MS)
        },
        RiskRule("recent_30d", "최근 30일 이내 설치됨", 5) {
            !it.exemptSystem && it.ageMillis > 7 * DAY_MS && it.ageMillis <= 30 * DAY_MS
        },
        RiskRule("no_icon", "실행 아이콘이 없는 숨김 앱", 20) {
            !it.exemptSystem && !it.input.hasLauncherIcon
        },
        RiskRule("perm_sms", "문자(SMS/MMS) 권한 허용됨", 20) { granted(it, PermCategory.SMS) },
        RiskRule("perm_call_log", "통화기록 권한 허용됨", 15) { granted(it, PermCategory.CALL_LOG) },
        RiskRule("perm_phone", "전화 권한 허용됨", 10) { granted(it, PermCategory.PHONE) },
        RiskRule("perm_contacts", "연락처 권한 허용됨", 5) { granted(it, PermCategory.CONTACTS) },
        RiskRule("device_admin", "기기관리자 활성화됨", 30) { it.input.isActiveDeviceAdmin },
        RiskRule("accessibility", "접근성 서비스 활성화됨", 30) { it.input.hasEnabledAccessibility },
        RiskRule("notif_listener", "알림 접근 활성화됨", 15) { it.input.hasEnabledNotificationListener },
        RiskRule("overlay", "다른 앱 위에 그리기 허용됨", 10) { granted(it, PermCategory.OVERLAY) },
        RiskRule("install_pkgs", "다른 앱 설치 권한 요청", 10) { granted(it, PermCategory.INSTALL_PACKAGES) },
        RiskRule("impersonation", "공공기관·금융사 사칭 의심 이름 (Play 스토어 설치 아님)", 25) {
            it.input.installSource != InstallSource.PLAY_STORE && !it.exemptSystem && matchesImpersonation(it.input)
        },
        RiskRule("combo_sms_control", "위험 조합: 문자 + 접근성/기기관리자", 20) {
            val i = it.input
            granted(it, PermCategory.SMS) && (i.hasEnabledAccessibility || i.isActiveDeviceAdmin)
        },
    )
}

data class RiskResult(val score: Int, val level: RiskLevel, val matchedRules: List<MatchedRule>)

object RiskScorer {
    fun levelFor(score: Int): RiskLevel = when {
        score >= RiskRules.THRESHOLD_HIGH -> RiskLevel.HIGH
        score >= RiskRules.THRESHOLD_MEDIUM -> RiskLevel.MEDIUM
        else -> RiskLevel.LOW
    }

    fun score(input: RiskInput, nowMillis: Long): RiskResult {
        val ctx = RuleContext(input, nowMillis)
        val matched = RiskRules.ALL
            .filter { it.predicate(ctx) }
            .map { MatchedRule(it.id, it.description, it.points) }
        var total = matched.sumOf { it.points }
        if (input.isSelf) total = minOf(total, RiskRules.THRESHOLD_MEDIUM - 1)
        return RiskResult(total, levelFor(total), matched)
    }
}
