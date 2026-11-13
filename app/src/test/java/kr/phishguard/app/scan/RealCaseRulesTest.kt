package kr.phishguard.app.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10 가족 폰 피해 사례(ADB 읽기 전용 추출)의 실제 앱 특성으로 판단 규칙을 검증한다.
 * 알려진 악성앱 목록(known_threat)에 의존하지 않고도 같은 유형을 잡는지 먼저 확인한다.
 */
class RealCaseRulesTest {
    private val now = 1_800_000_000_000L
    private val day = RiskRules.DAY_MS
    private val unrelatedDigest = setOf("00".repeat(32))

    private fun input(
        pkg: String,
        label: String,
        source: InstallSource = InstallSource.UNKNOWN,
        icon: Boolean = true,
        installedDaysAgo: Long = 40,
        perms: Set<PermCategory> = emptySet(),
        notif: Boolean = false,
        inCall: Boolean = false,
        digests: Set<String> = unrelatedDigest,
        subjects: List<String> = emptyList(),
        nearby: Int = 0,
        up: Long? = null,
        down: Long? = null,
        system: Boolean = false,
        vendorSigned: Boolean = false,
    ) = RiskInput(
        packageName = pkg, label = label, installSource = source, isSystem = system,
        hasLauncherIcon = icon, firstInstallTime = now - installedDaysAgo * day,
        grantedCategories = perms, isActiveDeviceAdmin = false,
        hasEnabledAccessibility = false, hasEnabledNotificationListener = notif,
        declaresInCallService = inCall, vendorSigned = vendorSigned,
        signerDigests = digests, signerSubjects = subjects,
        nearbyUnknownInstalls = nearby, uploadBytes30d = up, downloadBytes30d = down,
    )

    private fun ids(i: RiskInput) = RiskScorer.score(i, now).matchedRules.map { it.id }.toSet()

    // ---- 실제 악성앱: 목록 없이 휴리스틱만으로 높음 ----

    @Test
    fun samsung17IsHighWithoutIoc() {
        val r = RiskScorer.score(
            input(
                // 실제 패키지명은 known_threat 목록에 있으므로 같은 모양의 다른 이름으로 휴리스틱만 검증
                pkg = "org.qz47hdkeimova.t8xxbl", label = "Samsung 17", icon = false,
                perms = setOf(PermCategory.PHONE), notif = true, inCall = true,
                subjects = listOf("CN=TerraToolsX,O=Harbor Interactive,L=Taipei,ST=Taipei,C=TW"),
                up = 10_700L * 1024 * 1024, down = 372L * 1024 * 1024,
            ),
            now,
        )
        assertEquals(RiskLevel.HIGH, r.level)
        val m = r.matchedRules.map { it.id }
        listOf("src_unknown", "system_impersonation", "obfuscated_name", "no_icon", "large_upload").forEach {
            assertTrue("missing $it in $m", it in m)
        }
        assertFalse(RiskRules.ID_KNOWN_THREAT in m)
    }

    @Test
    fun fakePlayStoreIsHighWithoutIoc() {
        val r = RiskScorer.score(
            input(
                pkg = "com.play.store240302", label = "Play 스토어",
                perms = setOf(PermCategory.CALL_LOG, PermCategory.LOCATION),
                subjects = listOf("C=US,O=Android,CN=Android Debug"), nearby = 2,
            ),
            now,
        )
        assertEquals(RiskLevel.HIGH, r.level)
        val m = r.matchedRules.map { it.id }
        assertTrue("system_impersonation" in m)
        assertTrue("debug_cert" in m)
        assertTrue("install_burst" in m)
        assertFalse("obfuscated_name" in m) // store240302는 끝에 붙은 숫자라 무작위 이름이 아님
    }

    @Test
    fun fakeCameraIsHighWithoutIoc() {
        val r = RiskScorer.score(
            input(
                pkg = "com.system.camera", label = "카메라",
                perms = setOf(PermCategory.CAMERA_MIC, PermCategory.STORAGE),
                subjects = listOf("C=82,ST=1,L=1,O=1,OU=1,CN=1"), nearby = 2,
            ),
            now,
        )
        assertEquals(RiskLevel.HIGH, r.level)
        val m = r.matchedRules.map { it.id }
        assertTrue("system_impersonation" in m)
        assertTrue("placeholder_cert" in m)
        assertTrue("unknown_camera_mic" in m)
    }

    @Test
    fun anySupportFromPlayIsMediumWhenRecent() {
        val recent = RiskScorer.score(
            input(pkg = "net.koino.anysupportMobile", label = "AnySupport", source = InstallSource.PLAY_STORE, installedDaysAgo = 1),
            now,
        )
        assertEquals(RiskLevel.MEDIUM, recent.level)
        assertTrue(recent.matchedRules.any { it.id == "remote_control_recent" })
        val old = ids(input(pkg = "com.teamviewer.quicksupport.market", label = "QuickSupport", source = InstallSource.PLAY_STORE, installedDaysAgo = 200))
        assertTrue("remote_control" in old)
        assertFalse("remote_control_recent" in old)
    }

    @Test
    fun knownThreatByPackageOrSigner() {
        val byPkg = RiskScorer.score(input(pkg = "com.system.camera", label = "카메라"), now)
        assertTrue(byPkg.matchedRules.any { it.id == RiskRules.ID_KNOWN_THREAT && it.points == 100 })
        assertEquals(RiskLevel.HIGH, byPkg.level)
        // 패키지명을 바꿔도 같은 서명이면 잡힘
        val bySigner = ids(
            input(
                pkg = "com.example.renamed", label = "뉴스",
                digests = setOf("a7418b5b21f322092163fbcdf617319562bb86ab4cf3aed3c17a97a4c2778e6f"),
            ),
        )
        assertTrue(RiskRules.ID_KNOWN_THREAT in bySigner)
    }

    // ---- 공식 앱 서명 대조 ----

    private val kakaoOfficial = "2b06cc3d47782d7c497c07f17cb5f859cd6bbcb66829f3e67b96b7a44820d2ce"

    @Test
    fun fakeKakaoWithCopiedPackageIsHigh() {
        val r = RiskScorer.score(input(pkg = "com.kakao.talk", label = "카카오톡"), now) // 서명 = unrelatedDigest
        assertEquals(RiskLevel.HIGH, r.level)
        assertTrue(r.matchedRules.any { it.id == RiskRules.ID_FAKE_OFFICIAL && it.points == 95 })
        assertTrue(r.matchedRules.any { it.id == "official_app_sideloaded" })
    }

    @Test
    fun genuineOfficialAppNotFlaggedAsFake() {
        // Play 설치 진짜 카카오톡: 아무 규칙도 안 걸림
        val play = ids(input(pkg = "com.kakao.talk", label = "카카오톡", source = InstallSource.PLAY_STORE, digests = setOf(kakaoOfficial)))
        assertFalse(RiskRules.ID_FAKE_OFFICIAL in play)
        assertFalse("official_app_sideloaded" in play)
        // 진짜 서명이지만 파일로 설치: 가짜 확정은 아니고 직접 설치 가산점만
        val side = RiskScorer.score(input(pkg = "com.kakao.talk", label = "카카오톡", digests = setOf(kakaoOfficial)), now)
        assertFalse(side.matchedRules.any { it.id == RiskRules.ID_FAKE_OFFICIAL })
        assertTrue(side.matchedRules.any { it.id == "official_app_sideloaded" })
        assertEquals(RiskLevel.MEDIUM, side.level)
        // LINE은 키 교체 이력 중 하나만 맞아도 진짜
        assertFalse(
            RiskRules.ID_FAKE_OFFICIAL in ids(
                input(pkg = "jp.naver.line.android", label = "LINE", digests = setOf("e682fe0bcd60907dfed515e0b8a4de03aa1c281d111a07833986602b6098afd2")),
            ),
        )
        // Play 설치인데 서명이 다르면(공식 키 교체) 가짜로 단정하지 않음
        assertFalse(RiskRules.ID_FAKE_OFFICIAL in ids(input(pkg = "com.kakao.talk", label = "카카오톡", source = InstallSource.PLAY_STORE)))
        // 서명을 못 읽었으면 판단 안 함
        assertFalse(RiskRules.ID_FAKE_OFFICIAL in ids(input(pkg = "com.kakao.talk", label = "카카오톡", digests = emptySet())))
    }

    @Test
    fun sideloadedRemoteSupportIsHigh() {
        val r = RiskScorer.score(
            input(pkg = "net.koino.anysupportMobile", label = "애니서포트 모바일지원", installedDaysAgo = 1,
                digests = setOf("e0d2d7af10491f70aeecbcf8909dbab3d9226ca49d566352e7682893e86a8855")),
            now,
        )
        assertEquals(RiskLevel.HIGH, r.level)
    }

    // ---- 설치 출처 판정 ----

    @Test
    fun packageInstallerMeansSideload() {
        assertEquals(InstallSource.UNKNOWN, InstallSourceClassifier.classify(false, "com.google.android.packageinstaller"))
        assertEquals(InstallSource.UNKNOWN, InstallSourceClassifier.classify(false, "com.android.packageinstaller"))
        assertEquals(InstallSource.UNKNOWN, InstallSourceClassifier.classify(false, null))
        assertEquals(InstallSource.PLAY_STORE, InstallSourceClassifier.classify(false, "com.android.vending"))
        assertEquals(InstallSource.OTHER_STORE, InstallSourceClassifier.classify(false, "com.sec.android.app.samsungapps"))
        assertEquals(InstallSource.SYSTEM, InstallSourceClassifier.classify(true, null))
    }

    // ---- 오탐 방지 ----

    @Test
    fun obfuscationHeuristic() {
        assertTrue(ThreatIntel.isObfuscatedPackage("org.vu35ehgiekoura.r3wwal"))
        assertTrue(ThreatIntel.isObfuscatedPackage("com.arsp4z.nbfdsx.x4pavwt.z70gl7o.mlk3xpeh2btx5"))
        listOf(
            "com.play.store240302", "com.kbstar.kbbank", "com.kebhana.hanapush", "com.skt.skaf.OA00412131",
            "us.zoom.videomeetings", "net.koino.anysupportMobile", "com.bizplay.bizzeropay", "kr.go.mobileid",
            "org.jw.jwlibrary.mobile", "com.kakao.talk", "kr.phishguard.app", "com.nhn.android.search",
        ).forEach { assertFalse(it, ThreatIntel.isObfuscatedPackage(it)) }
    }

    @Test
    fun trustedOriginsSkipNewHeuristics() {
        val newRules = setOf("system_impersonation", "obfuscated_name", "debug_cert", "placeholder_cert", "large_upload")
        // Play 스토어의 진짜 구글 앱
        val play = ids(
            input(
                pkg = "com.google.android.apps.photos", label = "Google 포토", source = InstallSource.PLAY_STORE,
                subjects = listOf("CN=Android Debug"), up = 5_000L * 1024 * 1024, down = 0,
            ),
        )
        assertTrue(play.intersect(newRules).isEmpty())
        // 갤럭시 스토어의 삼성 서명 앱
        val vendor = ids(
            input(pkg = "com.samsung.android.app.watchmanager", label = "Galaxy Wearable", source = InstallSource.OTHER_STORE, vendorSigned = true),
        )
        assertTrue(vendor.intersect(newRules).isEmpty())
        // 시스템 앱
        val sys = ids(input(pkg = "com.sec.android.app.camera", label = "카메라", source = InstallSource.SYSTEM, system = true))
        assertTrue(sys.intersect(newRules + "remote_control_recent").isEmpty())
    }

    @Test
    fun samsungPreloadWithUnknownInstallerIsNotImpersonation() {
        // 실기기: com.samsung.android.app.tips, installer=PrePackageInstaller(API에선 null), 플랫폼 키 서명
        val r = RiskScorer.score(
            input(pkg = "com.samsung.android.app.tips", label = "팁", vendorSigned = true, installedDaysAgo = 6000),
            now,
        )
        assertFalse(r.matchedRules.any { it.id == "system_impersonation" })
        assertTrue(r.level != RiskLevel.HIGH)
    }

    @Test
    fun ordinarySideloadWithoutSignalsStaysMedium() {
        // 평범한 이름의 직접 설치 앱은 출처 점수만 (30 = 중간). 높음으로 과장하지 않음
        val r = RiskScorer.score(input(pkg = "com.example.notes", label = "메모장"), now)
        assertEquals(30, r.score)
        assertEquals(RiskLevel.MEDIUM, r.level)
    }

    @Test
    fun largeUploadNeedsMoreUpThanDown() {
        val mb = 1024L * 1024
        assertTrue("large_upload" in ids(input(pkg = "com.example.a", label = "a", up = 600 * mb, down = 10 * mb)))
        assertFalse("large_upload" in ids(input(pkg = "com.example.a", label = "a", up = 600 * mb, down = 2_000 * mb)))
        assertFalse("large_upload" in ids(input(pkg = "com.example.a", label = "a", up = 100 * mb, down = 0)))
        assertFalse("large_upload" in ids(input(pkg = "com.example.a", label = "a", up = null)))
    }

    @Test
    fun certificateChecks() {
        assertTrue(ThreatIntel.isDebugCertificate("C=US,O=Android,CN=Android Debug"))
        assertTrue(ThreatIntel.isPlaceholderCertificate("C=82,ST=1,L=1,O=1,OU=1,CN=1"))
        assertFalse(ThreatIntel.isPlaceholderCertificate("CN=TerraToolsX,O=Harbor Interactive,L=Taipei,ST=Taipei,C=TW"))
        assertFalse(ThreatIntel.isPlaceholderCertificate(""))
    }

    @Test
    fun countNearbyExcludesSelf() {
        val t = 1_000_000L
        val hour = ThreatIntel.INSTALL_BURST_WINDOW_MS
        assertEquals(2, ThreatIntel.countNearby(t, listOf(t, t + 60_000, t - 240_000, t + 2 * hour)))
        assertEquals(0, ThreatIntel.countNearby(t, listOf(t)))
    }
}
