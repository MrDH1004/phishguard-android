package kr.phishguard.app.scan

/**
 * 실제 피해 사례에서 뽑은 판별 기준 (Android 의존 없음, 단위 테스트 대상).
 *
 * 근거: 2026-10-29~10-30 가족 폰(Galaxy S10 5G, Android 12) 피해 사례를 ADB 읽기 전용으로 추출·분석한 결과.
 *  - 원격조종 앱(AnySupport) 설치 후 4분 안에 가짜 "카메라"(com.system.camera), 가짜 "Play 스토어"(com.play.store240302),
 *    다음날 아이콘 없는 "Samsung 17"(org.vu35ehgiekoura.r3wwal)이 차례로 설치됨.
 *  - 세 앱 모두 설치자가 com.google.android.packageinstaller(= APK 파일 직접 설치)였음.
 *  - 가짜 Play 스토어는 개발용 "Android Debug" 인증서, 가짜 카메라는 모든 항목이 "1"인 엉터리 인증서로 서명됨.
 *  - Samsung 17은 10일간 약 10.7GB를 업로드함. 이미 지워진 1차 악성앱(com.arsp4z.nbfdsx...)과 TeamViewer 흔적도 남아 있었음.
 */
object ThreatIntel {

    /** 이 사례에서 확인된 악성앱 패키지명 */
    val KNOWN_BAD_PACKAGES: Set<String> = setOf(
        "org.vu35ehgiekoura.r3wwal",
        "com.play.store240302",
        "com.system.camera",
        "com.arsp4z.nbfdsx.x4pavwt.z70gl7o.mlk3xpeh2btx5",
    )

    /** 이 사례에서 확인된 악성앱 서명 인증서 SHA-256 (apksigner verify --print-certs로 확인, 소문자 hex) */
    val KNOWN_BAD_SIGNERS: Set<String> = setOf(
        // Samsung 17 — CN=TerraToolsX, O=Harbor Interactive, L=Taipei, C=TW
        "a7418b5b21f322092163fbcdf617319562bb86ab4cf3aed3c17a97a4c2778e6f",
        // 가짜 카메라 — C=82, ST=1, L=1, O=1, OU=1, CN=1
        "43158a7fa176067bf84eb04d1be15c2bee2b8690e814f8063aca590ce4b6f26d",
        // 가짜 Play 스토어 — CN=Android Debug. 디버그 키는 개발자 PC마다 따로 만들어지므로 같은 값이면 같은 제작자의 앱
        "899eb55d52660a86d1f90f58afd89f8026220dd67ae3be851fd33db0c8d483b1",
    )

    fun isKnownBad(packageName: String, signerDigests: Set<String>): Boolean =
        packageName in KNOWN_BAD_PACKAGES || signerDigests.any { it in KNOWN_BAD_SIGNERS }

    // ---- 공식 앱 서명 ----

    /**
     * 주요 앱의 공식 서명 인증서 SHA-256 (패키지명 → 서명 집합). 범인은 패키지명은 베껴도 회사 서명 키는 못 베낀다.
     *
     * 출처: 2026-11-13 피해 폰(SM-G977N)에 Play 스토어로 설치된 APK를 adb pull → `apksigner verify --print-certs`로 직접 확인.
     * 추측한 값은 넣지 않는다. 은행·카드·정부24·모바일 신분증 등은 실제 설치본에서 같은 방법으로 확인한 뒤에만 추가할 것.
     */
    val OFFICIAL_SIGNERS: Map<String, Set<String>> = mapOf(
        // 카카오톡 26.9.1 — OU=kakaoteam, O=kakao, C=ko
        "com.kakao.talk" to setOf("2b06cc3d47782d7c497c07f17cb5f859cd6bbcb66829f3e67b96b7a44820d2ce"),
        // LINE 26.16.0 — 키 교체(v3.1): Android 12 이하용 NaverJapan 키 + Android 13 이상용 LINE Corp 키
        "jp.naver.line.android" to setOf(
            "e682fe0bcd60907dfed515e0b8a4de03aa1c281d111a07833986602b6098afd2",
            "e6e8036e01234e919fbe9cf9ea5d210435cfc0542328641aa70a018f52f63cf8",
        ),
        // 애니서포트 모바일지원 v10.26.10.27.1 — CN=koino (이 사례에서 원격조종에 쓰인 앱)
        "net.koino.anysupportMobile" to setOf("e0d2d7af10491f70aeecbcf8909dbab3d9226ca49d566352e7682893e86a8855"),
        // KT 인증 02.01.39 — CN=KT corporation
        "com.kt.ktauth" to setOf("7117ee13c842f1009553c8b70fff7cf30ec8f04118ae87b6722e2f05193ea09a"),
        // 네이버 12.19.20 / 밴드 31.0.2 — 같은 NHN 키
        "com.nhn.android.search" to setOf("0b8b8523bb4aeffa346e4bdd4fbf7d193450569aa14aaad4adfd94a3f7b227bb"),
        "com.nhn.android.band" to setOf("0b8b8523bb4aeffa346e4bdd4fbf7d193450569aa14aaad4adfd94a3f7b227bb"),
        // 인터넷지로 4.7.6 — O=KFTC (금융결제원)
        "kr.or.giro.android" to setOf("c9b20a82085cbc106a1a3443c9ccae30757c40de7f633115d57ef2553b4e55ea"),
        // 당근 23.48.3 — CN=N42
        "com.towneers.www" to setOf("7f1261cd434e5ae1706fd3feb4762db346a20c8cdaf5c0fce49a91f61dd3e68d"),
    )

    fun isOfficialPackage(packageName: String): Boolean = packageName in OFFICIAL_SIGNERS

    /** 공식 앱 패키지명인데 서명이 공식 서명과 하나도 안 맞음. 서명을 못 읽었으면(빈 집합) 판단하지 않음 */
    fun isOfficialSignerMismatch(packageName: String, signerDigests: Set<String>): Boolean {
        val official = OFFICIAL_SIGNERS[packageName] ?: return false
        return signerDigests.isNotEmpty() && signerDigests.none { it in official }
    }

    // ---- 원격조종 앱 ----

    /** 원격조종 앱 패키지 접두어. 정상 앱이지만 사기범이 "보안 점검" 명목으로 설치시켜 폰을 조종하는 데 쓴다. */
    private val REMOTE_CONTROL_PREFIXES = listOf(
        "net.koino.", // AnySupport (이 사례에서 첫 번째로 설치됨)
        "com.teamviewer.", // TeamViewer QuickSupport (이 사례에서 설치 후 삭제된 흔적)
        "com.anydesk.",
        "com.carriez.flutter_hbb", // RustDesk
    )

    private val REMOTE_CONTROL_LABEL_KEYWORDS = listOf(
        "원격", "remote", "anysupport", "teamviewer", "quicksupport", "anydesk", "rustdesk",
    )

    fun isRemoteControl(packageName: String, label: String): Boolean {
        val pkg = packageName.lowercase()
        val lbl = label.lowercase()
        return REMOTE_CONTROL_PREFIXES.any { pkg.startsWith(it) } ||
            REMOTE_CONTROL_LABEL_KEYWORDS.any { lbl.contains(it) }
    }

    // ---- 시스템 앱·스토어 사칭 ----

    /** 이름 전체가 이것과 같으면 기본 앱 사칭 (공백·대소문자 무시) */
    private val SYSTEM_LABEL_EXACT = setOf(
        "카메라", "camera", "설정", "settings", "전화", "phone", "메시지", "messages", "연락처", "contacts",
        "갤러리", "gallery", "내파일", "myfiles", "시계", "clock", "인터넷", "internet",
    )

    /** 이름에 이것이 들어가면 제조사·구글·스토어 사칭 (공백·대소문자 무시) */
    private val SYSTEM_LABEL_CONTAINS = listOf(
        "play스토어", "playstore", "googleplay", "구글", "google", "삼성", "samsung", "galaxy", "갤럭시",
        "시스템", "system", "android", "안드로이드", "chrome", "크롬", "youtube", "유튜브",
    )

    /** 제조사·구글·안드로이드 전용 패키지 이름 공간과 스토어를 흉내 낸 패키지명 */
    private val SYSTEM_PACKAGE_PREFIXES = listOf(
        "android.", "com.android.", "com.google.", "com.samsung.", "com.sec.", "com.system.",
    )
    private val SYSTEM_PACKAGE_CONTAINS = listOf("play.store", "playstore", "googleplay")

    fun impersonatesSystem(packageName: String, label: String): Boolean {
        val lbl = label.lowercase().filterNot { it.isWhitespace() }
        val pkg = packageName.lowercase()
        return lbl in SYSTEM_LABEL_EXACT ||
            SYSTEM_LABEL_CONTAINS.any { lbl.contains(it) } ||
            SYSTEM_PACKAGE_PREFIXES.any { pkg.startsWith(it) } ||
            SYSTEM_PACKAGE_CONTAINS.any { pkg.contains(it) }
    }

    // ---- 난독화된(무작위) 패키지명 ----

    private const val VOWELS = "aeiouy"

    /**
     * 사람이 지은 이름이 아닌 무작위 조각인지. 예) vu35ehgiekoura, r3wwal, arsp4z, nbfdsx.
     *  - 글자 사이에 숫자가 끼어 있음 (끝에 붙은 버전 숫자 store240302는 제외)
     *  - 또는 6자 이상이면서 자음이 5개 이상 연속
     */
    internal fun isRandomSegment(segment: String): Boolean {
        val s = segment.lowercase()
        if (s.length < 5) return false
        val digitBetweenLetters = (1 until s.length - 1).any { i ->
            s[i].isDigit() && s.substring(0, i).any { it in 'a'..'z' } && s.substring(i + 1).any { it in 'a'..'z' }
        }
        if (digitBetweenLetters) return true
        if (s.length < 6) return false
        var run = 0
        var maxRun = 0
        s.forEach { ch ->
            if (ch in 'a'..'z' && ch !in VOWELS) {
                run++
                maxRun = maxOf(maxRun, run)
            } else {
                run = 0
            }
        }
        return maxRun >= 5
    }

    /** 첫 조각(com/org/kr 등)을 뺀 나머지 중 무작위 조각이 2개 이상이면 난독화된 이름 */
    fun isObfuscatedPackage(packageName: String): Boolean =
        packageName.split('.').drop(1).count { isRandomSegment(it) } >= 2

    // ---- 서명 인증서 ----

    /** 개발용 테스트 인증서 (Android Studio가 자동으로 만드는 키). 정식 배포 앱은 이 키를 쓰지 않는다. */
    fun isDebugCertificate(subject: String): Boolean =
        subject.contains("CN=Android Debug", ignoreCase = true)

    /**
     * 엉터리 인증서: 이름 항목(CN·O·OU·L·ST·C) 값이 모두 2자 이하. 예) "C=82, ST=1, L=1, O=1, OU=1, CN=1".
     * 값을 하나도 읽지 못하면 판단하지 않는다(false).
     */
    fun isPlaceholderCertificate(subject: String): Boolean {
        val values = subject.split(',')
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null else part.substring(0, idx).trim().uppercase() to part.substring(idx + 1).trim()
            }
            .filter { (key, _) -> key in setOf("CN", "O", "OU", "L", "ST", "C") }
            .map { it.second }
        return values.isNotEmpty() && values.all { it.length <= 2 }
    }

    // ---- 같은 무렵 설치 / 대량 업로드 ----

    /** 이 시간 안에 다른 외부 설치 앱이 함께 깔렸으면 "몰아서 설치" (사례: 3개 앱이 5분 안에 설치됨) */
    const val INSTALL_BURST_WINDOW_MS = 60L * 60 * 1000

    /** 최근 30일 업로드가 이 이상이면 대량 유출 의심 (사례: Samsung 17이 10일간 약 10.7GB) */
    const val LARGE_UPLOAD_BYTES = 500L * 1024 * 1024

    /** [times] 중 [target]과 [windowMs] 안에 있는 다른 항목 수. target 자신(같은 값 1개)은 뺀다. */
    fun countNearby(target: Long, times: List<Long>, windowMs: Long = INSTALL_BURST_WINDOW_MS): Int {
        val n = times.count { kotlin.math.abs(it - target) <= windowMs }
        return if (target in times) n - 1 else n
    }
}

/** 설치자 패키지로 설치 출처 판정 (Android 의존 없음). */
object InstallSourceClassifier {
    const val PLAY_STORE = "com.android.vending"

    /**
     * 안드로이드 기본 "패키지 설치 프로그램". 파일 관리자·브라우저·메신저에서 APK 파일을 눌러 설치하면
     * Android 11+는 설치자를 null이 아니라 이 패키지로 기록한다.
     * 피해 사례(Android 12)의 악성앱 4개 모두 installerPackageName=com.google.android.packageinstaller였다.
     * 이전 판정은 이를 "기타 스토어"(+5)로 봐서 APK 직접 설치(+30)를 놓쳤다.
     */
    val PACKAGE_INSTALLERS = setOf(
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
    )

    fun classify(isSystem: Boolean, installer: String?): InstallSource = when {
        isSystem -> InstallSource.SYSTEM
        installer == null || installer in PACKAGE_INSTALLERS -> InstallSource.UNKNOWN
        installer == PLAY_STORE -> InstallSource.PLAY_STORE
        else -> InstallSource.OTHER_STORE
    }
}
