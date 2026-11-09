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
    /** 접근성 서비스를 선언함 (활성 여부와 무관) */
    val declaresAccessibility: Boolean = false,
    val isDefaultDialer: Boolean = false,
    val isDefaultSms: Boolean = false,
    val declaresCallRedirection: Boolean = false,
    val declaresCallScreening: Boolean = false,
    val declaresInCallService: Boolean = false,
    /** 기기의 시스템 앱과 같은 서명 인증서로 서명됨 */
    val vendorSigned: Boolean = false,
)

/** 규칙 평가 시점의 입력 + 현재 시각 */
class RuleContext(val input: RiskInput, val nowMillis: Long) {
    val ageMillis: Long get() = nowMillis - input.firstInstallTime

    /** 시스템 앱은 출처/최근설치/아이콘/사칭 규칙 면제 */
    val exemptSystem: Boolean get() = input.isSystem || input.installSource == InstallSource.SYSTEM

    /**
     * 제조사(기기) 서명 앱: 시스템 앱과 같은 인증서로 서명됐고 설치 출처가 확인됨(UNKNOWN 아님).
     * 예) 갤럭시 스토어에서 받은 삼성 서명 동반 앱. 서명 키는 제조사만 갖고 있으므로 사기범이 흉내 낼 수 없다.
     * 출처가 UNKNOWN이면 제외한다 — 일부 커스텀 롬은 공개된 AOSP 테스트 키로 시스템 앱을 서명하기 때문이다.
     */
    val vendorTrusted: Boolean get() = input.vendorSigned && input.installSource != InstallSource.UNKNOWN

    /** 시스템 앱도, Play 스토어 설치도, 제조사 서명 앱도 아님 */
    val untrustedOrigin: Boolean get() =
        !exemptSystem && input.installSource != InstallSource.PLAY_STORE && !vendorTrusted
}

data class RiskRule(
    val id: String,
    val description: String,
    val points: Int,
    /** 이 상태로 공격자가 무엇을 할 수 있는지 쉬운 말 한 문장 */
    val plainExplanation: String,
    val predicate: (RuleContext) -> Boolean,
)

object RiskRules {
    const val DAY_MS = 24L * 60 * 60 * 1000
    const val THRESHOLD_HIGH = 60
    const val THRESHOLD_MEDIUM = 30

    /** 기본 전화/문자 앱 가로채기 규칙 id (화면 경고 배너가 사용) */
    const val ID_DEFAULT_ROLE_HIJACK = "default_role_hijack"
    const val ID_VENDOR_SIGNED = "vendor_signed"

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

    private fun unknownSource(c: RuleContext) = !c.exemptSystem && c.input.installSource == InstallSource.UNKNOWN

    /**
     * 규칙 표: 한 곳에서 id / 설명 / 점수 / 쉬운 설명 / 조건 정의. 일치한 규칙의 점수는 모두 합산된다.
     *
     * 제조사 서명 앱(RuleContext.vendorTrusted)은 다음 규칙에서 빠진다. 제조사 동반 앱(워치·이어폰 관리 등)은
     * 원래 아이콘이 없고 알림·전화 권한을 쓰기 때문에 잘못 의심받기 쉽다.
     *  - no_icon (숨김 아이콘)
     *  - perm_call_log, perm_phone (전화 관련 권한)
     *  - notif_listener (알림 접근)
     *  - 통화 기능 선언(call_service_declared), 기본 앱 차지(default_role_hijack)
     * 대신 0점짜리 참고 규칙 vendor_signed가 표시된다.
     */
    val ALL: List<RiskRule> = listOf(
        RiskRule(
            ID_DEFAULT_ROLE_HIJACK,
            "기본 전화/문자 앱을 차지함 — 112·은행 전화를 가로챌 수 있음",
            50,
            "112나 은행에 전화를 걸어도 사기범에게 연결되게 만들 수 있고, 은행·카드사 문자를 몰래 받아 볼 수 있습니다.",
        ) { (it.input.isDefaultDialer || it.input.isDefaultSms) && it.untrustedOrigin },
        RiskRule(
            "call_service_declared",
            "통화를 가로채거나 바꿀 수 있는 기능을 갖춤 (Play 스토어 설치 아님)",
            25,
            "전화 화면을 대신 띄우거나 거는 번호를 다른 번호로 바꿀 수 있는 기능입니다. 설정에서 허용하면 통화를 조작할 수 있습니다.",
        ) {
            val i = it.input
            (i.declaresInCallService || i.declaresCallRedirection || i.declaresCallScreening) && it.untrustedOrigin
        },
        RiskRule(
            "src_unknown",
            "알 수 없는 출처(APK 직접 설치)로 설치됨",
            30,
            "앱 스토어 검사를 거치지 않은 앱입니다. 사기범은 문자나 메신저로 보낸 링크로 이런 앱을 설치하게 만듭니다.",
        ) { unknownSource(it) },
        RiskRule(
            "src_other_store",
            "Play 스토어 외 다른 스토어/앱으로 설치됨",
            5,
            "Play 스토어가 아닌 곳에서 설치됐습니다. 믿을 만한 스토어인지 확인이 필요합니다.",
        ) { !it.exemptSystem && it.input.installSource == InstallSource.OTHER_STORE },
        RiskRule(
            "recent_7d",
            "최근 7일 이내 설치됨",
            15,
            "최근에 설치된 앱입니다. 사기 전화를 받은 무렵에 설치했다면 특히 의심해야 합니다.",
        ) { !it.exemptSystem && it.ageMillis in 0..(7 * DAY_MS) },
        RiskRule(
            "recent_30d",
            "최근 30일 이내 설치됨",
            5,
            "한 달 안에 설치된 앱입니다. 설치한 기억이 없다면 의심해야 합니다.",
        ) { !it.exemptSystem && it.ageMillis > 7 * DAY_MS && it.ageMillis <= 30 * DAY_MS },
        RiskRule(
            "no_icon",
            "실행 아이콘이 없는 숨김 앱",
            20,
            "화면에 아이콘이 없어 지우지 못하게 숨어 있는 앱입니다. 악성 앱이 자주 쓰는 방법입니다.",
        ) { !it.exemptSystem && !it.input.hasLauncherIcon && !it.vendorTrusted },
        RiskRule(
            "perm_sms",
            "문자(SMS/MMS) 권한 허용됨",
            20,
            "문자를 몰래 읽거나 보낼 수 있습니다. 은행 인증번호를 빼 갈 수 있습니다.",
        ) { granted(it, PermCategory.SMS) },
        RiskRule(
            "perm_call_log",
            "통화기록 권한 허용됨",
            15,
            "누구와 언제 통화했는지 볼 수 있고, 통화 기록을 바꿔 사기 전화를 숨길 수 있습니다.",
        ) { granted(it, PermCategory.CALL_LOG) && !it.vendorTrusted },
        RiskRule(
            "perm_phone",
            "전화 권한 허용됨",
            10,
            "전화를 걸거나 받을 수 있고, 지금 통화 중인지 알 수 있습니다.",
        ) { granted(it, PermCategory.PHONE) && !it.vendorTrusted },
        RiskRule(
            "perm_contacts",
            "연락처 권한 허용됨",
            5,
            "연락처를 빼내 가족·지인에게 사기 문자를 보낼 수 있습니다.",
        ) { granted(it, PermCategory.CONTACTS) },
        RiskRule(
            "device_admin",
            "기기관리자 활성화됨",
            30,
            "앱을 지우지 못하게 막거나 화면을 잠글 수 있습니다.",
        ) { it.input.isActiveDeviceAdmin },
        RiskRule(
            "accessibility",
            "접근성 서비스 활성화됨",
            30,
            "화면 내용을 읽고 대신 누를 수 있습니다. 은행 앱을 몰래 조작하거나 삭제를 방해할 수 있습니다.",
        ) { it.input.hasEnabledAccessibility },
        RiskRule(
            "notif_listener",
            "알림 접근 활성화됨",
            15,
            "모든 알림을 읽을 수 있습니다. 인증번호·입금 알림을 엿보거나 숨길 수 있습니다.",
        ) { it.input.hasEnabledNotificationListener && !it.vendorTrusted },
        RiskRule(
            "overlay",
            "다른 앱 위에 그리기 허용됨",
            10,
            "진짜 은행 앱 위에 가짜 화면을 덮어 비밀번호를 입력하게 만들 수 있습니다.",
        ) { granted(it, PermCategory.OVERLAY) },
        RiskRule(
            "install_pkgs",
            "다른 앱 설치 권한 요청",
            10,
            "다른 악성 앱을 몰래 더 설치할 수 있습니다.",
        ) { granted(it, PermCategory.INSTALL_PACKAGES) },
        RiskRule(
            "impersonation",
            "공공기관·금융사 사칭 의심 이름 (Play 스토어 설치 아님)",
            25,
            "검찰·경찰·은행 같은 이름으로 믿게 만든 뒤 개인정보나 돈을 요구하는 수법입니다.",
        ) { it.input.installSource != InstallSource.PLAY_STORE && !it.exemptSystem && matchesImpersonation(it.input) },
        RiskRule(
            "combo_sms_control",
            "위험 조합: 문자 + 접근성/기기관리자",
            20,
            "문자를 가로채면서 휴대폰까지 조작할 수 있어, 인증번호를 받아 바로 송금할 수 있습니다.",
        ) {
            val i = it.input
            granted(it, PermCategory.SMS) && (i.hasEnabledAccessibility || i.isActiveDeviceAdmin)
        },
        RiskRule(
            "combo_unknown_a11y_install",
            "외부 설치 + 접근성 + 다른 앱 설치 권한 조합",
            20,
            "휴대폰을 조작하면서 다른 악성 앱을 계속 설치할 수 있습니다. 보이스피싱 악성 앱의 전형적인 조합입니다.",
        ) {
            val i = it.input
            unknownSource(it) && (i.hasEnabledAccessibility || i.declaresAccessibility) &&
                granted(it, PermCategory.INSTALL_PACKAGES)
        },
        RiskRule(
            "combo_unknown_phone_install",
            "외부 설치 + 전화/통화기록 + 다른 앱 설치 권한 조합",
            15,
            "통화를 엿보거나 조작하면서 다른 악성 앱을 더 설치할 수 있습니다.",
        ) {
            unknownSource(it) &&
                (granted(it, PermCategory.CALL_LOG) || granted(it, PermCategory.PHONE)) &&
                granted(it, PermCategory.INSTALL_PACKAGES)
        },
        RiskRule(
            "unknown_camera_mic",
            "외부 설치 + 마이크·카메라 권한 허용",
            15,
            "몰래 주변 소리를 녹음하거나 카메라로 촬영해 밖으로 보낼 수 있습니다.",
        ) {
            unknownSource(it) && granted(it, PermCategory.CAMERA_MIC)
        },
        RiskRule(
            ID_VENDOR_SIGNED,
            "제조사(기기) 서명 앱",
            0,
            "휴대폰 제조사가 만든 앱과 같은 서명입니다. 숨김 아이콘·알림·전화 권한 점수를 빼고 계산했습니다.",
        ) { !it.exemptSystem && it.vendorTrusted },
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
            .map { MatchedRule(it.id, it.description, it.points, it.plainExplanation) }
        var total = matched.sumOf { it.points }
        if (input.isSelf) total = minOf(total, RiskRules.THRESHOLD_MEDIUM - 1)
        return RiskResult(total, levelFor(total), matched)
    }
}
