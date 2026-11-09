package kr.phishguard.app.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleAndVendorRulesTest {
    private val now = 1_800_000_000_000L
    private val day = RiskRules.DAY_MS

    private fun input(
        source: InstallSource = InstallSource.PLAY_STORE,
        system: Boolean = false,
        icon: Boolean = true,
        perms: Set<PermCategory> = emptySet(),
        accessibility: Boolean = false,
        declaresAccessibility: Boolean = false,
        notif: Boolean = false,
        dialer: Boolean = false,
        sms: Boolean = false,
        redirection: Boolean = false,
        screening: Boolean = false,
        inCall: Boolean = false,
        vendorSigned: Boolean = false,
    ) = RiskInput(
        packageName = "com.example.app", label = "테스트", installSource = source, isSystem = system,
        hasLauncherIcon = icon, firstInstallTime = now - 365 * day,
        grantedCategories = perms, isActiveDeviceAdmin = false,
        hasEnabledAccessibility = accessibility, hasEnabledNotificationListener = notif,
        declaresAccessibility = declaresAccessibility,
        isDefaultDialer = dialer, isDefaultSms = sms,
        declaresCallRedirection = redirection, declaresCallScreening = screening, declaresInCallService = inCall,
        vendorSigned = vendorSigned,
    )

    private fun ids(i: RiskInput) = RiskScorer.score(i, now).matchedRules.map { it.id }

    @Test
    fun defaultDialerFromUnknownSourceIsHijack() {
        val r = RiskScorer.score(input(source = InstallSource.UNKNOWN, dialer = true), now)
        assertTrue(r.matchedRules.any { it.id == RiskRules.ID_DEFAULT_ROLE_HIJACK && it.points == 50 })
        // 30(출처) + 50(기본 앱) = 80 → HIGH
        assertEquals(80, r.score)
        assertEquals(RiskLevel.HIGH, r.level)
    }

    @Test
    fun defaultSmsFromOtherStoreIsHijack() {
        assertTrue(RiskRules.ID_DEFAULT_ROLE_HIJACK in ids(input(source = InstallSource.OTHER_STORE, sms = true)))
    }

    @Test
    fun defaultRoleHeldByPlayOrSystemAppIsNotHijack() {
        assertFalse(RiskRules.ID_DEFAULT_ROLE_HIJACK in ids(input(source = InstallSource.PLAY_STORE, dialer = true, sms = true)))
        assertFalse(
            RiskRules.ID_DEFAULT_ROLE_HIJACK in
                ids(input(source = InstallSource.SYSTEM, system = true, dialer = true, sms = true)),
        )
    }

    @Test
    fun callServiceDeclarationScoredOnlyForUntrustedOrigin() {
        listOf(
            input(source = InstallSource.UNKNOWN, inCall = true),
            input(source = InstallSource.UNKNOWN, redirection = true),
            input(source = InstallSource.OTHER_STORE, screening = true),
        ).forEach { i ->
            val r = RiskScorer.score(i, now)
            assertTrue(r.matchedRules.any { it.id == "call_service_declared" && it.points == 25 })
        }
        assertFalse("call_service_declared" in ids(input(source = InstallSource.PLAY_STORE, inCall = true)))
        assertFalse("call_service_declared" in ids(input(source = InstallSource.SYSTEM, system = true, inCall = true)))
    }

    @Test
    fun unknownAccessibilityInstallCombo() {
        val declared = input(
            source = InstallSource.UNKNOWN, declaresAccessibility = true, perms = setOf(PermCategory.INSTALL_PACKAGES),
        )
        assertTrue("combo_unknown_a11y_install" in ids(declared))
        val enabled = input(
            source = InstallSource.UNKNOWN, accessibility = true, perms = setOf(PermCategory.INSTALL_PACKAGES),
        )
        assertTrue("combo_unknown_a11y_install" in ids(enabled))
        // 설치 권한 없으면 불일치, Play 출처면 불일치
        assertFalse("combo_unknown_a11y_install" in ids(input(source = InstallSource.UNKNOWN, accessibility = true)))
        assertFalse(
            "combo_unknown_a11y_install" in ids(
                input(source = InstallSource.PLAY_STORE, accessibility = true, perms = setOf(PermCategory.INSTALL_PACKAGES)),
            ),
        )
    }

    @Test
    fun unknownPhoneInstallCombo() {
        val r = RiskScorer.score(
            input(source = InstallSource.UNKNOWN, perms = setOf(PermCategory.CALL_LOG, PermCategory.INSTALL_PACKAGES)),
            now,
        )
        assertTrue(r.matchedRules.any { it.id == "combo_unknown_phone_install" && it.points == 15 })
        assertTrue("combo_unknown_phone_install" in ids(
            input(source = InstallSource.UNKNOWN, perms = setOf(PermCategory.PHONE, PermCategory.INSTALL_PACKAGES)),
        ))
        assertFalse("combo_unknown_phone_install" in ids(
            input(source = InstallSource.OTHER_STORE, perms = setOf(PermCategory.PHONE, PermCategory.INSTALL_PACKAGES)),
        ))
    }

    private val companionPerms = setOf(
        PermCategory.CALL_LOG, PermCategory.PHONE, PermCategory.CONTACTS, PermCategory.OVERLAY,
    )

    @Test
    fun galaxyStoreVendorSignedCompanionIsLow() {
        val r = RiskScorer.score(
            input(
                source = InstallSource.OTHER_STORE, icon = false, notif = true, perms = companionPerms,
                inCall = true, vendorSigned = true,
            ),
            now,
        )
        assertEquals(RiskLevel.LOW, r.level)
        val ids = r.matchedRules.map { it.id }
        assertTrue(RiskRules.ID_VENDOR_SIGNED in ids)
        assertEquals(0, r.matchedRules.first { it.id == RiskRules.ID_VENDOR_SIGNED }.points)
        listOf("no_icon", "perm_phone", "perm_call_log", "notif_listener", "call_service_declared").forEach {
            assertFalse(it, it in ids)
        }
    }

    @Test
    fun sameProfileUnsignedFromUnknownIsHigh() {
        val r = RiskScorer.score(
            input(source = InstallSource.UNKNOWN, icon = false, notif = true, perms = companionPerms),
            now,
        )
        assertEquals(RiskLevel.HIGH, r.level)
        assertFalse(r.matchedRules.any { it.id == RiskRules.ID_VENDOR_SIGNED })
    }

    @Test
    fun vendorSignedFromUnknownSourceGetsNoReduction() {
        val r = RiskScorer.score(
            input(source = InstallSource.UNKNOWN, icon = false, notif = true, perms = companionPerms, vendorSigned = true),
            now,
        )
        assertEquals(RiskLevel.HIGH, r.level)
        assertFalse(r.matchedRules.any { it.id == RiskRules.ID_VENDOR_SIGNED })
        assertTrue(r.matchedRules.any { it.id == "no_icon" })
    }

    @Test
    fun everyRuleHasPlainExplanation() {
        RiskRules.ALL.forEach { assertTrue(it.id, it.plainExplanation.isNotBlank()) }
        val r = RiskScorer.score(input(source = InstallSource.UNKNOWN), now)
        assertTrue(r.matchedRules.all { it.plainExplanation.isNotBlank() })
    }

    @Test
    fun signerMatching() {
        val system = setOf("aa", "bb")
        assertTrue(RoleDetector.isSignedBy(RoleDetector.Signers(setOf("cc", "aa"), multiple = false), system))
        assertFalse(RoleDetector.isSignedBy(RoleDetector.Signers(setOf("cc"), multiple = false), system))
        assertFalse(RoleDetector.isSignedBy(RoleDetector.Signers(setOf("aa", "cc"), multiple = true), system))
        assertTrue(RoleDetector.isSignedBy(RoleDetector.Signers(setOf("aa", "bb"), multiple = true), system))
        assertFalse(RoleDetector.isSignedBy(RoleDetector.Signers(emptySet(), multiple = false), system))
    }
}
