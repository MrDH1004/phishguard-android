package kr.phishguard.app.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class InstallOriginTest {
    @Test fun playStore() {
        assertEquals("Play 스토어에서 설치", InstallOrigin.describe(InstallSource.PLAY_STORE, "com.android.vending", "com.android.vending"))
    }

    @Test fun apkFromKakao() {
        assertEquals(
            "APK 직접 설치 (Play 스토어 아님) · 카카오톡에서 받은 파일로 설치 (com.kakao.talk)",
            InstallOrigin.describe(InstallSource.UNKNOWN, "com.google.android.packageinstaller", "com.kakao.talk"),
        )
    }

    @Test fun apkOpenedByInstaller() {
        assertEquals(
            "APK 직접 설치 (Play 스토어 아님) · 파일을 직접 열어 설치 (com.google.android.packageinstaller)",
            InstallOrigin.describe(InstallSource.UNKNOWN, "com.google.android.packageinstaller", "com.google.android.packageinstaller"),
        )
    }

    @Test fun apkNoInitiatorShowsInstaller() {
        assertEquals(
            "APK 직접 설치 (Play 스토어 아님) · 설치자 패키지 설치 프로그램 (com.google.android.packageinstaller)",
            InstallOrigin.describe(InstallSource.UNKNOWN, "com.google.android.packageinstaller", null),
        )
    }

    @Test fun unknownInitiatorUsesLabelLookup() {
        assertEquals(
            "APK 직접 설치 (Play 스토어 아님) · 가짜앱에서 받은 파일로 설치 (x.y.z)",
            InstallOrigin.describe(InstallSource.UNKNOWN, null, "x.y.z") { if (it == "x.y.z") "가짜앱" else null },
        )
    }

    @Test fun systemHasNoDetail() {
        assertEquals("기본 설치 앱", InstallOrigin.describe(InstallSource.SYSTEM, null, "android"))
    }
}
