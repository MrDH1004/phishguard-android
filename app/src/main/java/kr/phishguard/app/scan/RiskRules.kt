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
    /** 서명 인증서 SHA-256 (소문자 hex) */
    val signerDigests: Set<String> = emptySet(),
    /** 서명 인증서 주체 이름 (예: "CN=Android Debug, O=Android, C=US") */
    val signerSubjects: List<String> = emptyList(),
    /** 이 앱 앞뒤 1시간 안에 함께 설치된 다른 외부 설치(APK 직접 설치) 앱 수 */
    val nearbyUnknownInstalls: Int = 0,
    /** 최근 30일 업로드 바이트. 사용 정보 접근 권한이 없으면 null */
    val uploadBytes30d: Long? = null,
    /** 최근 30일 다운로드 바이트. 사용 정보 접근 권한이 없으면 null */
    val downloadBytes30d: Long? = null,
    /** 활성 접근성 서비스가 packageNames로 콕 집어 지켜보는 앱들 (null=모든 앱이면 빈 집합) */
    val a11yWatchedPackages: Set<String> = emptySet(),
    /** 활성 접근성 서비스가 화면 내용 읽기 + 제스처(대신 누르기) 능력을 함께 가짐 */
    val a11yCanReadAndGesture: Boolean = false,
    /** 설치자 기록(installer of record) 패키지 */
    val installerPackage: String? = null,
    /** 실제로 설치를 요청한 패키지 (API 30+ InstallSourceInfo.initiatingPackageName) */
    val initiatingPackage: String? = null,
    /** 설치를 요청한 앱이 그 자체로 외부 설치(APK 직접 설치)된 일반 앱임 */
    val initiatorIsSideloaded: Boolean = false,
    /** 설치자가 "스토어에서 받음"으로 신고함 (API 33+ PACKAGE_SOURCE_STORE) */
    val packageSourceStore: Boolean = false,
    /** PROCESS_OUTGOING_CALLS 권한을 요청함 */
    val requestsProcessOutgoingCalls: Boolean = false,
    /** NEW_OUTGOING_CALL 방송(전화 걸기)을 받는 리시버 선언 */
    val handlesNewOutgoingCall: Boolean = false,
    /** WRITE_CALL_LOG 허용됨 (CALL_LOG 분류와 별도로 쓰기 권한만 구분) */
    val writeCallLogGranted: Boolean = false,
    /** REQUEST_DELETE_PACKAGES 요청 (일반 권한이라 선언만으로 허용됨) */
    val requestsDeletePackages: Boolean = false,
    /** READ_SMS 또는 RECEIVE_SMS 요청 (허용 여부와 무관) */
    val requestsSmsRead: Boolean = false,
    /** 알림 접근 서비스 선언 (활성 여부와 무관) */
    val declaresNotificationListener: Boolean = false,
    /** 화면 녹화(mediaProjection) 포그라운드 서비스 선언 */
    val fgsMediaProjection: Boolean = false,
    /** 통화(phoneCall) 포그라운드 서비스 선언 */
    val fgsPhoneCall: Boolean = false,
    /** APK 구조 검사 결과. 검사 안 함(신뢰 출처·읽기 실패)이면 null */
    val apkStructure: ApkStructure? = null,
    /** VpnService(BIND_VPN_SERVICE 보호 서비스) 선언 */
    val declaresVpnService: Boolean = false,
    /** 지금 이 앱의 VPN이 켜져 있음 */
    val vpnActiveNow: Boolean = false,
    /** 사용자가 'VPN 허용 앱'으로 지정 (회사 VPN 등) → VPN 관련 규칙만 제외 */
    val vpnAllowedByUser: Boolean = false,
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
    const val ID_SIDELOADED_VPN = "sideloaded_vpn"
    const val ID_SIDELOADED_VPN_ACTIVE = "sideloaded_vpn_active"
    val VPN_RULE_IDS = setOf(ID_SIDELOADED_VPN, ID_SIDELOADED_VPN_ACTIVE)

    /** 신뢰할 수 없는 출처의 VPN 기능 앱 (이 앱·사용자가 허용한 VPN 앱 제외) */
    private fun sideloadedVpn(c: RuleContext) =
        c.untrustedOrigin && !c.input.isSelf && !c.input.vpnAllowedByUser && c.input.declaresVpnService
    const val ID_KNOWN_THREAT = "known_threat"
    const val ID_FAKE_OFFICIAL = "fake_official_app"
    const val ID_OFFICIAL_LABEL_SPOOF = "official_label_spoof"

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

    private fun comboSmsControl(c: RuleContext) =
        granted(c, PermCategory.SMS) && (c.input.hasEnabledAccessibility || c.input.isActiveDeviceAdmin)

    private fun comboUnknownA11yInstall(c: RuleContext) =
        unknownSource(c) && (c.input.hasEnabledAccessibility || c.input.declaresAccessibility) &&
            granted(c, PermCategory.INSTALL_PACKAGES)

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
    // APK 구조·포그라운드 유형·삭제 요청·접근성 능력 규칙은 APK 직접 설치(UNKNOWN)에만 적용한다.
    // 갤럭시 스토어·원스토어의 금융·보안 앱은 보안 모듈(dex/jar, 패커, 화면 녹화)을 정상적으로 쓰기 때문이다 (리뷰 M8).
    val ALL: List<RiskRule> = listOf(
        RiskRule(
            ID_KNOWN_THREAT,
            "실제 보이스피싱 피해에서 확인된 악성앱과 같은 패키지/서명",
            100,
            "가족 폰 피해 사례에서 직접 분석한 악성앱과 같은 앱입니다. 통화 가로채기·알림 엿보기·대량 파일 유출 기능이 확인됐습니다. 바로 증거를 저장하고 인터넷을 끊으세요.",
        ) { !it.exemptSystem && ThreatIntel.isKnownBad(it.input.packageName, it.input.signerDigests) },
        RiskRule(
            ID_FAKE_OFFICIAL,
            "유명 앱과 패키지명은 같은데 서명이 다름 — 가짜 앱 확정",
            95,
            "카카오톡·은행 같은 진짜 앱의 이름표(패키지명)를 그대로 베낀 가짜입니다. 진짜 회사의 서명 키는 베낄 수 없어 서명이 다릅니다. 이 앱에 입력한 비밀번호·인증번호는 사기범에게 넘어갑니다.",
        ) {
            // Play 스토어 설치는 제외: 스토어가 패키지 소유자를 확인하므로, 서명이 다르면 공식 키 교체(목록 갱신 필요)일 뿐이다
            !it.exemptSystem && it.input.installSource != InstallSource.PLAY_STORE &&
                ThreatIntel.isOfficialSignerMismatch(it.input.packageName, it.input.signerDigests)
        },
        RiskRule(
            "official_app_sideloaded",
            "Play 스토어에 있는 앱인데 파일(APK)로 직접 설치됨",
            20,
            "스토어에서 받으면 되는 앱을 굳이 파일로 설치한 것은 사기범이 보낸 링크로 깔았을 때 흔히 보입니다. 같은 이름의 앱은 지우고 Play 스토어에서 다시 받으세요.",
        ) { unknownSource(it) && ThreatIntel.isOfficialPackage(it.input.packageName) },
        RiskRule(
            "remote_control_recent",
            "원격조종 앱이 최근 30일 안에 설치됨",
            30,
            "사기범이 \"보안 점검\"이라며 설치시키는 원격조종 앱입니다. 화면을 보면서 휴대폰을 대신 조작해 악성앱을 깔고 송금할 수 있습니다. 직접 설치한 기억이 없으면 지우세요.",
        ) {
            !it.exemptSystem && it.ageMillis in 0..(30 * DAY_MS) &&
                ThreatIntel.isRemoteControl(it.input.packageName, it.input.label)
        },
        RiskRule(
            "remote_control",
            "원격조종 앱 설치됨",
            10,
            "다른 사람이 화면을 보고 휴대폰을 조작할 수 있는 앱입니다. 쓰지 않으면 지우는 것이 안전합니다.",
        ) {
            !it.exemptSystem && it.ageMillis > 30 * DAY_MS &&
                ThreatIntel.isRemoteControl(it.input.packageName, it.input.label)
        },
        RiskRule(
            "system_impersonation",
            "카메라·Play 스토어·삼성·구글 같은 기본 앱을 흉내 낸 이름 (외부 설치)",
            30,
            "진짜 기본 앱은 휴대폰에 처음부터 들어 있거나 공식 스토어로만 설치됩니다. 같은 이름을 쓴 외부 앱은 지우지 못하게 속이려는 가짜입니다.",
        ) {
            // 제조사 서명 앱은 제외: 갤럭시 기본 앱 '팁'은 설치자가 PrePackageInstaller라 출처가 UNKNOWN으로 보이지만
            // 시스템 앱과 같은 플랫폼 키로 서명돼 있다 (실기기 확인). 사칭범은 제조사 키를 가질 수 없다.
            it.untrustedOrigin && !it.input.vendorSigned &&
                ThreatIntel.impersonatesSystem(it.input.packageName, it.input.label)
        },
        RiskRule(
            "obfuscated_name",
            "무작위 글자로 된 난독화 패키지명 (외부 설치)",
            15,
            "정상 앱은 회사·서비스 이름으로 패키지명을 짓습니다. 무작위 글자는 악성앱이 탐지를 피하려고 쓰는 방법입니다.",
        ) { it.untrustedOrigin && ThreatIntel.isObfuscatedPackage(it.input.packageName) },
        RiskRule(
            "debug_cert",
            "개발용 테스트 인증서(Android Debug)로 서명됨",
            20,
            "정식으로 배포되는 앱은 개발용 인증서를 쓰지 않습니다. 급하게 만든 가짜 앱에서 자주 보입니다.",
        ) { it.untrustedOrigin && it.input.signerSubjects.any { s -> ThreatIntel.isDebugCertificate(s) } },
        RiskRule(
            "placeholder_cert",
            "서명 인증서 정보가 엉터리(\"1\" 같은 값)임",
            15,
            "회사 이름 없이 아무 값이나 넣은 인증서입니다. 누가 만들었는지 숨기려는 앱일 수 있습니다.",
        ) { it.untrustedOrigin && it.input.signerSubjects.any { s -> ThreatIntel.isPlaceholderCertificate(s) } },
        RiskRule(
            "install_burst",
            "다른 외부 설치 앱과 1시간 안에 몰아서 설치됨",
            15,
            "사기범이 원격조종이나 링크로 여러 악성앱을 짧은 시간에 연달아 설치하는 수법입니다. 같은 무렵 설치된 앱을 모두 확인하세요.",
        ) { unknownSource(it) && it.input.nearbyUnknownInstalls >= 1 },
        RiskRule(
            "large_upload",
            "최근 30일 동안 500MB 넘게 업로드함 (외부 설치)",
            25,
            "사진·파일·녹음 같은 자료를 밖으로 대량으로 보냈을 수 있습니다. 데이터 사용량 화면에서 보낸 양을 확인하세요.",
        ) {
            val up = it.input.uploadBytes30d
            val down = it.input.downloadBytes30d
            it.untrustedOrigin && up != null && up >= ThreatIntel.LARGE_UPLOAD_BYTES && (down == null || up > down)
        },
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
        ) { comboSmsControl(it) },
        RiskRule(
            "combo_unknown_a11y_install",
            "외부 설치 + 접근성 + 다른 앱 설치 권한 조합",
            20,
            "휴대폰을 조작하면서 다른 악성 앱을 계속 설치할 수 있습니다. 보이스피싱 악성 앱의 전형적인 조합입니다.",
        ) { comboUnknownA11yInstall(it) },
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
        // ---- 2026-11 추가 신호 (조합 규칙 외에는 모두 신뢰할 수 없는 출처에만 적용) ----
        RiskRule(
            ID_OFFICIAL_LABEL_SPOOF,
            "유명 앱 이름을 쓰는데 패키지명·서명이 공식 앱과 다름 — 이름 도용",
            60,
            "카카오톡·네이버 같은 유명 앱과 똑같은 이름이지만 진짜 회사가 만든 앱이 아닙니다. 진짜처럼 보이게 해서 비밀번호나 인증번호를 입력하게 만드는 가짜입니다.",
        ) {
            !it.exemptSystem && it.input.installSource != InstallSource.PLAY_STORE && !it.vendorTrusted &&
                ThreatIntel.isOfficialLabelSpoof(it.input.packageName, it.input.label, it.input.signerDigests)
        },
        RiskRule(
            "official_name_other_package",
            "은행·카드·정부기관·원격지원 등 공식 앱 이름을 쓰는데 패키지명이 공식 앱과 다름",
            50,
            "진짜 은행·기관 앱과 같은 이름이지만 그 회사가 Play 스토어에 올린 앱이 아닙니다. 사기범이 보낸 링크로 깔리는 가짜 은행·검찰·택배 앱이 이렇게 생겼습니다. 지우고 필요한 앱은 Play 스토어에서 다시 받으세요.",
        ) {
            !it.exemptSystem && it.input.installSource != InstallSource.PLAY_STORE && !it.vendorTrusted &&
                !ThreatIntel.isOfficialLabelSpoof(it.input.packageName, it.input.label, it.input.signerDigests) &&
                ThreatIntel.isOfficialNameOtherPackage(it.input.packageName, it.input.label)
        },
        RiskRule(
            "a11y_watch_sensitive",
            "접근성 서비스가 전화·설정·권한 화면이나 금융 앱을 콕 집어 지켜봄",
            35,
            "전화 화면·설정·권한 허용 창이나 은행 앱이 열릴 때만 골라서 화면을 읽고 대신 누를 수 있습니다. 112 통화 화면을 가리거나 이 앱을 지우지 못하게 막는 데 쓰입니다.",
        ) { it.untrustedOrigin && it.input.a11yWatchedPackages.any { p -> ThreatIntel.isSensitiveWatchTarget(p) } },
        RiskRule(
            "a11y_read_and_gesture",
            "접근성 서비스가 화면 읽기 + 대신 누르기 능력을 함께 가짐",
            20,
            "화면에 뜬 글자를 모두 읽으면서 사람 대신 화면을 누를 수 있습니다. 은행 앱을 열어 몰래 송금 버튼을 누를 수 있습니다.",
        ) { unknownSource(it) && it.input.a11yCanReadAndGesture },
        RiskRule(
            "dropper_installed",
            "다른 외부 설치 앱이 이 앱을 설치함 (드로퍼 의심)",
            30,
            "먼저 깔린 수상한 앱이 이 앱을 몰래 내려받아 설치했습니다. 악성앱이 다른 악성앱을 계속 심는 수법입니다. 설치한 앱도 함께 확인하세요.",
        ) { it.untrustedOrigin && it.input.initiatorIsSideloaded && it.input.initiatingPackage != it.input.packageName },
        RiskRule(
            "fake_store_source",
            "스토어에서 받은 것처럼 신고됐지만 설치한 앱은 스토어가 아님",
            25,
            "앱 스토어가 아닌 일반 앱이 스스로 \"스토어에서 받았다\"고 꾸며 설치했습니다. 외부 설치 경고를 피하려는 수법입니다.",
        ) {
            val inst = it.input.installerPackage
            it.untrustedOrigin && it.input.packageSourceStore && inst != null &&
                inst !in InstallSourceClassifier.KNOWN_STORES
        },
        RiskRule(
            "outgoing_call_intercept",
            "전화를 걸 때 번호를 가로챌 수 있음 (발신 전화 감시)",
            30,
            "112나 은행에 거는 번호를 몰래 보고 다른 번호로 바꿀 수 있습니다. 진짜 기관에 전화해도 사기범에게 연결되게 만드는 수법입니다.",
        ) { it.untrustedOrigin && (it.input.requestsProcessOutgoingCalls || it.input.handlesNewOutgoingCall) },
        RiskRule(
            "write_call_log",
            "기본 전화 앱이 아닌데 통화기록 쓰기 허용됨",
            20,
            "통화 기록을 지우거나 바꿔서 사기범과 통화한 흔적을 숨길 수 있습니다.",
        ) { it.untrustedOrigin && it.input.writeCallLogGranted && !it.input.isDefaultDialer },
        RiskRule(
            "request_delete_packages",
            "다른 앱 삭제를 요청할 수 있음",
            15,
            "백신이나 보안 앱을 지우라는 창을 띄울 수 있습니다. 사기범이 탐지를 피하려고 보안 앱을 지우게 만듭니다.",
        ) { unknownSource(it) && !it.input.isSelf && it.input.requestsDeletePackages },
        RiskRule(
            "fgs_capture_call",
            "화면 녹화·통화용 백그라운드 서비스를 갖춤",
            15,
            "앱을 닫아도 뒤에서 계속 화면을 녹화하거나 통화에 끼어들 수 있습니다.",
        ) { unknownSource(it) && (it.input.fgsMediaProjection || it.input.fgsPhoneCall) },
        RiskRule(
            "apk_nested_payload",
            "앱 파일 안에 또 다른 앱/실행 코드(.apk·.dex·.jar)를 숨김",
            30,
            "설치된 뒤에 숨겨 둔 악성 코드를 꺼내 실행하거나 다른 앱을 설치할 수 있습니다. 검사를 피하려는 악성앱의 흔한 수법입니다.",
        ) { unknownSource(it) && it.input.apkStructure?.nestedPayload == true },
        RiskRule(
            "apk_tampered",
            "앱 파일 구조가 비정상 (손상·조작·난독화 흔적)",
            15,
            "분석 도구가 열지 못하게 일부러 망가뜨리거나 꾸민 앱 파일입니다. 정상 앱에서는 거의 보이지 않습니다.",
        ) {
            val a = it.input.apkStructure
            unknownSource(it) && a != null && (a.longEntryName || a.obfuscatorRootFile || a.openFailed)
        },
        RiskRule(
            "apk_packer",
            "코드를 감싸 숨기는 패커 라이브러리 사용",
            10,
            "앱의 실제 코드를 감싸 숨겨서 무엇을 하는지 알아보기 어렵게 만든 앱입니다.",
        ) { unknownSource(it) && it.input.apkStructure?.packerLibs == true },
        RiskRule(
            "combo_unknown_spy_capable",
            "외부 설치 + 문자 읽기·접근성·알림 접근 중 하나를 선언 (Play 프로텍트 기준)",
            20,
            "스토어 검사를 거치지 않은 앱이 문자나 화면·알림을 엿볼 수 있는 기능을 갖췄습니다. Play 프로텍트가 가장 엄격하게 막는 조합입니다.",
        ) {
            // 더 구체적인 조합(문자+접근성/기기관리자, 외부설치+접근성+설치권한)이 이미 걸리면 중복 가산하지 않는다
            val i = it.input
            unknownSource(it) &&
                (i.requestsSmsRead || i.declaresAccessibility || i.declaresNotificationListener) &&
                !comboSmsControl(it) && !comboUnknownA11yInstall(it)
        },
        RiskRule(
            ID_SIDELOADED_VPN,
            "VPN 기능 앱을 파일로 설치",
            20,
            "휴대폰의 모든 인터넷 통신을 자기에게 돌려 엿보거나, 범인 서버로 가는 통신을 숨기고 보안 앱의 인터넷 차단을 풀 수 있습니다.",
        ) { sideloadedVpn(it) },
        RiskRule(
            ID_SIDELOADED_VPN_ACTIVE,
            "파일로 설치한 VPN 앱의 VPN이 지금 켜져 있음",
            15,
            "지금 이 순간 휴대폰 통신이 이 앱을 거쳐 나가고 있습니다. 모르는 앱이라면 VPN을 끄고 앱을 지우세요.",
        ) { sideloadedVpn(it) && it.input.vpnActiveNow },
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
