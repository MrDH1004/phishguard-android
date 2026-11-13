package kr.phishguard.app.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 2026-11 추가 신호 규칙: 각 규칙마다 잡아야 할 경우 + 오탐 방지 경우 */
class NewSignalRulesTest {
    private val now = 1_800_000_000_000L
    private val day = RiskRules.DAY_MS
    private val unrelatedDigest = setOf("00".repeat(32))

    /** 기본값: 평범한 이름의 오래된 외부 설치 앱 (src_unknown 30점만) */
    private fun base(
        pkg: String = "com.example.notes",
        label: String = "메모장",
        source: InstallSource = InstallSource.UNKNOWN,
        system: Boolean = false,
        vendorSigned: Boolean = false,
        self: Boolean = false,
    ) = RiskInput(
        packageName = pkg, label = label, installSource = source, isSystem = system,
        hasLauncherIcon = true, firstInstallTime = now - 400 * day,
        grantedCategories = emptySet(), isActiveDeviceAdmin = false,
        hasEnabledAccessibility = false, hasEnabledNotificationListener = false,
        isSelf = self, vendorSigned = vendorSigned, signerDigests = unrelatedDigest,
    )

    private fun ids(i: RiskInput) = RiskScorer.score(i, now).matchedRules.map { it.id }.toSet()

    private fun assertRule(id: String, i: RiskInput) = assertTrue("$id expected in ${ids(i)}", id in ids(i))
    private fun assertNoRule(id: String, i: RiskInput) = assertFalse("$id unexpected in ${ids(i)}", id in ids(i))

    /** 신뢰 출처 3종: Play 스토어 / 시스템 / 제조사 서명 + 다른 스토어 */
    private fun trustedVariants(i: RiskInput) = listOf(
        i.copy(installSource = InstallSource.PLAY_STORE),
        i.copy(installSource = InstallSource.SYSTEM, isSystem = true),
        i.copy(installSource = InstallSource.OTHER_STORE, vendorSigned = true),
    )

    private fun assertOnlyUntrusted(id: String, i: RiskInput) {
        assertRule(id, i)
        trustedVariants(i).forEach { assertNoRule(id, it) }
    }

    @Test
    fun baselineHasOnlySourcePoints() {
        val r = RiskScorer.score(base(), now)
        assertEquals(listOf("src_unknown"), r.matchedRules.map { it.id })
    }

    // 1. 접근성 감시 대상

    @Test
    fun a11yWatchingSensitivePackages() {
        listOf(
            "com.samsung.android.dialer", "com.skt.prod.dialer", "com.google.android.permissioncontroller",
            "com.android.systemui", "com.android.settings", "com.kakao.talk", "com.kbstar.kbbank",
        ).forEach { target ->
            assertOnlyUntrusted("a11y_watch_sensitive", base().copy(a11yWatchedPackages = setOf("com.example.x", target)))
        }
        assertEquals(35, RiskRules.ALL.first { it.id == "a11y_watch_sensitive" }.points)
    }

    @Test
    fun a11yWatchingOrdinaryPackagesIsNotFlagged() {
        assertNoRule("a11y_watch_sensitive", base().copy(a11yWatchedPackages = setOf("com.example.game", "org.example.reader")))
        assertNoRule("a11y_watch_sensitive", base()) // packageNames=null(모든 앱) → 빈 집합
    }

    @Test
    fun a11yReadAndGesture() {
        assertOnlyUntrusted("a11y_read_and_gesture", base().copy(a11yCanReadAndGesture = true))
        assertNoRule("a11y_read_and_gesture", base())
    }

    // 2. 설치 경로

    @Test
    fun dropperInstalledByAnotherSideloadedApp() {
        val i = base().copy(initiatingPackage = "org.qz47hdk.loader", initiatorIsSideloaded = true)
        assertOnlyUntrusted("dropper_installed", i)
        // 기본 패키지 설치 프로그램이 설치(=사람이 APK를 누름)했으면 드로퍼 아님
        assertNoRule(
            "dropper_installed",
            base().copy(initiatingPackage = "com.google.android.packageinstaller", initiatorIsSideloaded = false),
        )
        // 자기 자신 업데이트
        assertNoRule("dropper_installed", base().copy(initiatingPackage = "com.example.notes", initiatorIsSideloaded = true))
    }

    @Test
    fun fakeStoreSourceDeclaration() {
        val i = base(source = InstallSource.OTHER_STORE).copy(installerPackage = "org.example.loader", packageSourceStore = true)
        assertRule("fake_store_source", i)
        assertNoRule("fake_store_source", i.copy(installSource = InstallSource.OTHER_STORE, vendorSigned = true))
        // 진짜 스토어가 STORE로 신고한 경우
        InstallSourceClassifier.KNOWN_STORES.forEach { store ->
            assertNoRule("fake_store_source", i.copy(installerPackage = store))
        }
        // 기본 패키지 설치 프로그램은 스토어가 아니다
        assertRule("fake_store_source", base().copy(installerPackage = "com.google.android.packageinstaller", packageSourceStore = true))
        // 스토어 신고가 없으면 판단 안 함
        assertNoRule("fake_store_source", i.copy(packageSourceStore = false))
        assertFalse("com.google.android.packageinstaller" in InstallSourceClassifier.KNOWN_STORES)
    }

    // 3. 발신 전화 가로채기

    @Test
    fun outgoingCallInterception() {
        assertOnlyUntrusted("outgoing_call_intercept", base().copy(requestsProcessOutgoingCalls = true))
        assertOnlyUntrusted("outgoing_call_intercept", base().copy(handlesNewOutgoingCall = true))
        assertNoRule("outgoing_call_intercept", base())
    }

    // 4. 유명 앱 이름 도용

    @Test
    fun officialLabelSpoofWithDifferentPackage() {
        listOf("카카오톡", "카카오 톡", "LINE", "네이버", "밴드", "인터넷지로", "당근", "애니서포트", "KT 인증").forEach { label ->
            val r = RiskScorer.score(base(pkg = "com.example.fake", label = label), now)
            assertTrue(label, r.matchedRules.any { it.id == RiskRules.ID_OFFICIAL_LABEL_SPOOF && it.points == 60 })
            assertEquals(label, RiskLevel.HIGH, r.level)
        }
        // 다른 스토어 설치도 대상 (Play·시스템만 제외)
        assertRule(RiskRules.ID_OFFICIAL_LABEL_SPOOF, base(pkg = "com.example.fake", label = "카카오톡", source = InstallSource.OTHER_STORE))
    }

    @Test
    fun officialLabelSpoofFalsePositiveGuards() {
        val fake = base(pkg = "com.example.fake", label = "카카오톡")
        assertNoRule(RiskRules.ID_OFFICIAL_LABEL_SPOOF, fake.copy(installSource = InstallSource.PLAY_STORE))
        assertNoRule(RiskRules.ID_OFFICIAL_LABEL_SPOOF, fake.copy(installSource = InstallSource.SYSTEM, isSystem = true))
        // 같은 회사 키로 서명된 다른 패키지 (예: 같은 NHN 키) → 도용 아님
        assertNoRule(
            RiskRules.ID_OFFICIAL_LABEL_SPOOF,
            base(pkg = "com.nhn.android.other", label = "네이버")
                .copy(signerDigests = setOf("0b8b8523bb4aeffa346e4bdd4fbf7d193450569aa14aaad4adfd94a3f7b227bb")),
        )
        // 이름이 일부만 같음 (정확 일치만 본다)
        assertNoRule(RiskRules.ID_OFFICIAL_LABEL_SPOOF, base(pkg = "com.example.map", label = "네이버 지도"))
        assertNoRule(RiskRules.ID_OFFICIAL_LABEL_SPOOF, base(pkg = "com.example.lite", label = "LINE Lite"))
        // 패키지명까지 같은 가짜는 기존 fake_official_app이 담당 (중복 안 함)
        val copied = ids(base(pkg = "com.kakao.talk", label = "카카오톡"))
        assertTrue(RiskRules.ID_FAKE_OFFICIAL in copied)
        assertFalse(RiskRules.ID_OFFICIAL_LABEL_SPOOF in copied)
        // 서명을 못 읽었으면 판단 안 함
        assertNoRule(RiskRules.ID_OFFICIAL_LABEL_SPOOF, fake.copy(signerDigests = emptySet()))
    }

    // 5·8. APK 구조

    @Test
    fun apkStructureRules() {
        assertOnlyUntrusted("apk_nested_payload", base().copy(apkStructure = ApkStructure(nestedPayload = true)))
        listOf(
            ApkStructure(longEntryName = true), ApkStructure(obfuscatorRootFile = true), ApkStructure(openFailed = true),
        ).forEach { assertOnlyUntrusted("apk_tampered", base().copy(apkStructure = it)) }
        assertOnlyUntrusted("apk_packer", base().copy(apkStructure = ApkStructure(packerLibs = true)))
        // 검사 안 함(null) 또는 이상 없음
        val clean = ids(base().copy(apkStructure = ApkStructure()))
        assertTrue(clean.intersect(setOf("apk_nested_payload", "apk_tampered", "apk_packer")).isEmpty())
        // 패커만으로는 중간을 넘지 않게 작게
        assertEquals(10, RiskRules.ALL.first { it.id == "apk_packer" }.points)
    }

    @Test
    fun analyzeEntryNames() {
        val bad = ApkInspector.analyzeEntryNames(
            sequenceOf(
                "AndroidManifest.xml", "classes.dex", "assets/payload/core.APK", "res/raw/x.dex",
                "lib/arm64-v8a/libjiagu_a64.so", "a".repeat(301), "oxxxxbf",
            ),
        )
        assertEquals(ApkStructure(nestedPayload = true, longEntryName = true, obfuscatorRootFile = true, packerLibs = true), bad)
        listOf("libshella-2.10.so", "libtxoprot.so", "libsecexe.so", "libsecmain.so", "libDexHelper-x86.so").forEach {
            assertTrue(it, ApkInspector.analyzeEntryNames(sequenceOf("lib/armeabi-v7a/$it")).packerLibs)
        }
        // 정상 앱 구조: classes2.dex는 최상위, assets의 다른 파일, 하위 폴더의 o..bf 파일, 일반 .so
        val ok = ApkInspector.analyzeEntryNames(
            sequenceOf(
                "classes2.dex", "assets/fonts/a.ttf", "assets/dexopt/baseline.prof", "res/raw/sound.ogg",
                "res/obf", "lib/arm64-v8a/libsqlite.so", "assets/libjiagu.so", "META-INF/CERT.RSA", "okhttp3.bf.txt",
            ),
        )
        assertEquals(ApkStructure(), ok)
    }

    @Test
    fun analyzeEntryNamesIsBounded() {
        val names = generateSequence(0) { it + 1 }.map { if (it == ApkInspector.MAX_ENTRIES) "assets/late.apk" else "f$it" }
        assertFalse(ApkInspector.analyzeEntryNames(names).nestedPayload)
    }

    @Test
    fun inspectRealZipAndCorruptFile() {
        val dir = File(System.getProperty("java.io.tmpdir"), "pg-apk-${System.nanoTime()}").apply { mkdirs() }
        try {
            val apk = File(dir, "a.apk")
            ZipOutputStream(apk.outputStream()).use { z ->
                listOf("AndroidManifest.xml", "assets/inner.jar").forEach { n ->
                    z.putNextEntry(ZipEntry(n))
                    z.write(1)
                    z.closeEntry()
                }
            }
            val r = ApkInspector.inspect(apk.path)
            assertNotNull(r)
            assertTrue(r!!.nestedPayload)
            assertFalse(r.openFailed)

            val broken = File(dir, "b.apk").apply { writeBytes(ByteArray(64) { 7 }) }
            assertEquals(ApkStructure(openFailed = true), ApkInspector.inspect(broken.path))

            assertNull(ApkInspector.inspect(File(dir, "missing.apk").path))
            assertNull(ApkInspector.inspect(null))
        } finally {
            dir.deleteRecursively()
        }
    }

    // 6. 통화기록 쓰기

    @Test
    fun writeCallLog() {
        assertOnlyUntrusted("write_call_log", base().copy(writeCallLogGranted = true))
        assertNoRule("write_call_log", base().copy(writeCallLogGranted = true, isDefaultDialer = true))
        assertNoRule("write_call_log", base())
    }

    // 7. 앱 삭제 요청

    @Test
    fun requestDeletePackages() {
        assertOnlyUntrusted("request_delete_packages", base().copy(requestsDeletePackages = true))
        // 이 앱(PhishGuard)은 위험 앱 삭제에 이 권한을 쓴다
        assertNoRule("request_delete_packages", base(pkg = "kr.phishguard.app", self = true).copy(requestsDeletePackages = true))
    }

    // 9. 포그라운드 서비스 유형

    @Test
    fun foregroundServiceCaptureOrCall() {
        assertOnlyUntrusted("fgs_capture_call", base().copy(fgsMediaProjection = true))
        assertOnlyUntrusted("fgs_capture_call", base().copy(fgsPhoneCall = true))
        assertNoRule("fgs_capture_call", base())
    }

    // 10. Play 프로텍트식 조합

    @Test
    fun comboUnknownSpyCapable() {
        listOf(
            base().copy(requestsSmsRead = true),
            base().copy(declaresAccessibility = true),
            base().copy(declaresNotificationListener = true),
        ).forEach { i ->
            assertRule("combo_unknown_spy_capable", i)
            assertNoRule("combo_unknown_spy_capable", i.copy(installSource = InstallSource.OTHER_STORE))
            assertNoRule("combo_unknown_spy_capable", i.copy(installSource = InstallSource.PLAY_STORE))
            assertNoRule("combo_unknown_spy_capable", i.copy(installSource = InstallSource.SYSTEM, isSystem = true))
        }
        assertNoRule("combo_unknown_spy_capable", base())
    }

    @Test
    fun comboUnknownSpyCapableDoesNotStackOnSpecificCombos() {
        // 외부설치 + 접근성 + 설치 권한 → combo_unknown_a11y_install만
        val a11yInstall = ids(
            base().copy(declaresAccessibility = true, grantedCategories = setOf(PermCategory.INSTALL_PACKAGES)),
        )
        assertTrue("combo_unknown_a11y_install" in a11yInstall)
        assertFalse("combo_unknown_spy_capable" in a11yInstall)
        // 문자 허용 + 접근성 활성 → combo_sms_control만
        val smsControl = ids(
            base().copy(requestsSmsRead = true, grantedCategories = setOf(PermCategory.SMS), hasEnabledAccessibility = true),
        )
        assertTrue("combo_sms_control" in smsControl)
        assertFalse("combo_unknown_spy_capable" in smsControl)
    }

    // 전체: 기존 실제 사례 프로필에 새 신호가 없으면 점수가 바뀌지 않음

    @Test
    fun newFieldsDefaultToNoEffect() {
        val newIds = setOf(
            RiskRules.ID_OFFICIAL_LABEL_SPOOF, "a11y_watch_sensitive", "a11y_read_and_gesture", "dropper_installed",
            "fake_store_source", "outgoing_call_intercept", "write_call_log", "request_delete_packages",
            "fgs_capture_call", "apk_nested_payload", "apk_tampered", "apk_packer", "combo_unknown_spy_capable",
        )
        assertEquals(newIds.size, RiskRules.ALL.count { it.id in newIds })
        assertEquals(RiskRules.ALL.size, RiskRules.ALL.map { it.id }.toSet().size) // id 중복 없음
        assertTrue(ids(base()).intersect(newIds).isEmpty())
        RiskRules.ALL.filter { it.id in newIds }.forEach {
            assertTrue(it.id, it.description.isNotBlank() && it.plainExplanation.isNotBlank())
        }
    }
}

/** 리뷰 M8: 갤럭시 스토어·원스토어 설치본(OTHER_STORE)은 APK 구조·포그라운드·삭제 요청·접근성 능력 규칙에서 빠진다 */
class StoreInstalledExclusionTest {
    private val now = 1_800_000_000_000L

    private fun store(i: RiskInput) = i.copy(installSource = InstallSource.OTHER_STORE)

    private fun input() = RiskInput(
        packageName = "com.example.bank", label = "예시은행", installSource = InstallSource.UNKNOWN, isSystem = false,
        hasLauncherIcon = true, firstInstallTime = now - 400 * RiskRules.DAY_MS, grantedCategories = emptySet(),
        isActiveDeviceAdmin = false, hasEnabledAccessibility = false, hasEnabledNotificationListener = false,
        signerDigests = setOf("00".repeat(32)),
    )

    @Test
    fun structuralRulesOnlyForDirectApk() {
        val loaded = input().copy(
            apkStructure = ApkStructure(nestedPayload = true, packerLibs = true),
            requestsDeletePackages = true,
            fgsMediaProjection = true,
            a11yCanReadAndGesture = true,
        )
        val ids = setOf("apk_nested_payload", "apk_packer", "request_delete_packages", "fgs_capture_call", "a11y_read_and_gesture")
        val direct = RiskScorer.score(loaded, now).matchedRules.map { it.id }.toSet()
        assertTrue("direct APK should match all: $direct", direct.containsAll(ids))
        val fromStore = RiskScorer.score(store(loaded), now).matchedRules.map { it.id }.toSet()
        assertTrue("store install should match none: $fromStore", fromStore.intersect(ids).isEmpty())
    }
}
