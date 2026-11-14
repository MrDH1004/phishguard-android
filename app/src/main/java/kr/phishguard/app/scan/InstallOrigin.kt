package kr.phishguard.app.scan

/**
 * 설치 출처를 사람이 읽는 한 줄로 만든다 (증거·리포트·상세 화면 공용).
 * 예: "APK 직접 설치 · 카카오톡에서 받은 파일로 설치 (com.kakao.talk)"
 *
 * installer = 설치자 기록(installer of record), initiating = 실제로 설치를 요청한 앱 (Android 11+).
 * 직접 설치한 APK는 initiating이 파일을 연 앱(메신저·브라우저·파일 앱)이라 어디서 받았는지 알 수 있다.
 */
object InstallOrigin {
    private val KNOWN = mapOf(
        "com.android.vending" to "Play 스토어",
        "com.sec.android.app.samsungapps" to "갤럭시 스토어",
        "com.skt.skaf.A000Z00040" to "원스토어",
        "com.kt.olleh.storefront" to "원스토어(KT)",
        "com.lguplus.appstore" to "원스토어(LG U+)",
        "com.google.android.packageinstaller" to "패키지 설치 프로그램",
        "com.android.packageinstaller" to "패키지 설치 프로그램",
        "com.kakao.talk" to "카카오톡",
        "org.telegram.messenger" to "텔레그램",
        "org.thunderdog.challegram" to "텔레그램 X",
        "jp.naver.line.android" to "LINE",
        "com.android.chrome" to "크롬",
        "com.sec.android.app.sbrowser" to "삼성 인터넷",
        "com.nhn.android.search" to "네이버",
        "com.sec.android.app.myfiles" to "내 파일",
        "com.google.android.apps.nbu.files" to "Files",
        "com.google.android.gm" to "Gmail",
        "com.samsung.android.messaging" to "삼성 메시지",
        "com.google.android.apps.messaging" to "Google 메시지",
        "com.android.shell" to "ADB(컴퓨터 연결)",
    )

    fun nameOf(pkg: String, labelOf: (String) -> String? = { null }): String =
        KNOWN[pkg] ?: labelOf(pkg) ?: pkg

    /** @param labelOf 설치된 앱이면 이름을 돌려주는 함수 (없으면 null) */
    fun describe(
        source: InstallSource,
        installer: String?,
        initiating: String?,
        labelOf: (String) -> String? = { null },
    ): String {
        val head = when (source) {
            InstallSource.PLAY_STORE -> "Play 스토어에서 설치"
            InstallSource.OTHER_STORE -> "다른 스토어·앱에서 설치"
            InstallSource.UNKNOWN -> "APK 직접 설치 (Play 스토어 아님)"
            InstallSource.SYSTEM -> "기본 설치 앱"
        }
        val by = initiating?.takeIf { it != installer || source == InstallSource.UNKNOWN }
        val parts = mutableListOf(head)
        if (by != null && source != InstallSource.SYSTEM) {
            val isInstallerApp = by == "com.google.android.packageinstaller" || by == "com.android.packageinstaller"
            parts += if (isInstallerApp) "파일을 직접 열어 설치 ($by)" else "${nameOf(by, labelOf)}에서 받은 파일로 설치 ($by)"
        } else if (installer != null && source != InstallSource.SYSTEM && source != InstallSource.PLAY_STORE) {
            parts += "설치자 ${nameOf(installer, labelOf)} ($installer)"
        }
        return parts.joinToString(" · ")
    }
}
