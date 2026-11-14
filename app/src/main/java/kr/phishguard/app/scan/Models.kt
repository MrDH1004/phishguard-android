package kr.phishguard.app.scan

/** 위험 수준 */
enum class RiskLevel(val label: String) { HIGH("높음"), MEDIUM("중간"), LOW("낮음") }

/** 권한 분류 (위험 권한·특수 권한) */
enum class PermCategory(val label: String) {
    SMS("문자"),
    CALL_LOG("통화기록"),
    PHONE("전화"),
    CONTACTS("연락처"),
    ACCESSIBILITY("접근성 서비스"),
    DEVICE_ADMIN("기기관리자"),
    OVERLAY("다른 앱 위에 그리기"),
    NOTIFICATION_LISTENER("알림 접근"),
    INSTALL_PACKAGES("앱 설치"),
    CAMERA_MIC("카메라·마이크"),
    LOCATION("위치"),
    STORAGE("저장소"),
}

data class PermissionInfoItem(
    val name: String,
    val category: PermCategory?,
    val granted: Boolean,
)

/** 설치 출처 */
enum class InstallSource(val label: String) { PLAY_STORE("Play 스토어"), OTHER_STORE("기타 스토어/앱"), UNKNOWN("APK 직접 설치"), SYSTEM("시스템") }

/** 일치한 규칙. plainExplanation은 공격자가 이 상태로 무엇을 할 수 있는지 쉬운 말로 설명한다. */
data class MatchedRule(val id: String, val description: String, val points: Int, val plainExplanation: String = "")

/**
 * 앱 하나의 조사 결과. 모든 기능(검사/통신감시/삭제)이 공유하는 계약.
 */
data class AppInfo(
    val packageName: String,
    val label: String,
    val uid: Int,
    val versionName: String?,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val installerPackage: String?,
    val installSource: InstallSource,
    val hasLauncherIcon: Boolean,
    val isSystem: Boolean,
    val sourceDir: String?,
    val splitSourceDirs: List<String>,
    val permissions: List<PermissionInfoItem>,
    /** 활성화된 기기관리자 여부 */
    val isActiveDeviceAdmin: Boolean,
    /** 활성화된 접근성 서비스 보유 여부 */
    val hasEnabledAccessibility: Boolean,
    /** 활성화된 알림 접근 보유 여부 */
    val hasEnabledNotificationListener: Boolean,
    val riskScore: Int,
    val riskLevel: RiskLevel,
    val matchedRules: List<MatchedRule>,
    /** 현재 기본 전화 앱 (TelecomManager.getDefaultDialerPackage) */
    val isDefaultDialer: Boolean = false,
    /** 현재 기본 문자 앱 (Telephony.Sms.getDefaultSmsPackage) */
    val isDefaultSms: Boolean = false,
    /** 통화 연결 바꾸기(CallRedirectionService) 서비스를 선언함 — 역할 가능, 보유 여부 아님 */
    val declaresCallRedirection: Boolean = false,
    /** 통화 가려내기(CallScreeningService) 서비스를 선언함 — 역할 가능, 보유 여부 아님 */
    val declaresCallScreening: Boolean = false,
    /** 통화 화면(InCallService) 서비스를 선언함 — 전화 앱 대체 기능 */
    val declaresInCallService: Boolean = false,
    /** 기기에 미리 설치된 시스템 앱과 같은 서명 인증서로 서명됨 (제조사·기기 서명) */
    val vendorSigned: Boolean = false,
    /** 실제로 설치를 요청한 앱 (Android 11+). APK 직접 설치면 파일을 연 메신저·브라우저·파일 앱 */
    val initiatingPackage: String? = null,
)
