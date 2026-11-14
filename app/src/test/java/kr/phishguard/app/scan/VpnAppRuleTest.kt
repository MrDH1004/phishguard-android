package kr.phishguard.app.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** VPN 기능 앱 규칙: 파일 설치 VPN 앱 +20, 지금 VPN이 켜져 있으면 +15 */
class VpnAppRuleTest {
    private val now = 1_800_000_000_000L

    private fun base(source: InstallSource = InstallSource.UNKNOWN, system: Boolean = false, self: Boolean = false) = RiskInput(
        packageName = "com.example.fastvpn", label = "빠른VPN", installSource = source, isSystem = system,
        hasLauncherIcon = true, firstInstallTime = now - 400 * RiskRules.DAY_MS,
        grantedCategories = emptySet(), isActiveDeviceAdmin = false,
        hasEnabledAccessibility = false, hasEnabledNotificationListener = false,
        isSelf = self, signerDigests = setOf("00".repeat(32)),
    )

    private fun ids(i: RiskInput) = RiskScorer.score(i, now).matchedRules.map { it.id }.toSet()
    private fun score(i: RiskInput) = RiskScorer.score(i, now).score

    @Test fun sideloadedVpnApp() {
        val plain = base()
        val vpn = plain.copy(declaresVpnService = true)
        assertTrue(RiskRules.ID_SIDELOADED_VPN in ids(vpn))
        assertFalse("sideloaded_vpn_active" in ids(vpn))
        assertEquals(20, score(vpn) - score(plain))
    }

    @Test fun activeVpnAddsMore() {
        val vpn = base().copy(declaresVpnService = true)
        val active = vpn.copy(vpnActiveNow = true)
        assertTrue("sideloaded_vpn_active" in ids(active))
        assertEquals(15, score(active) - score(vpn))
    }

    @Test fun otherStoreCounts() {
        assertTrue(RiskRules.ID_SIDELOADED_VPN in ids(base(InstallSource.OTHER_STORE).copy(declaresVpnService = true)))
    }

    @Test fun trustedAndSelfExempt() {
        listOf(
            base(InstallSource.PLAY_STORE),
            base(InstallSource.SYSTEM, system = true),
            base(InstallSource.OTHER_STORE).copy(vendorSigned = true),
            base(self = true),
        ).forEach { i ->
            val x = i.copy(declaresVpnService = true, vpnActiveNow = true)
            assertFalse("$x", RiskRules.ID_SIDELOADED_VPN in ids(x))
            assertFalse("$x", "sideloaded_vpn_active" in ids(x))
        }
    }

    @Test fun userAllowedVpnAppSkipsOnlyVpnRules() {
        val vpn = base().copy(declaresVpnService = true, vpnActiveNow = true)
        val allowed = vpn.copy(vpnAllowedByUser = true)
        RiskRules.VPN_RULE_IDS.forEach { assertFalse(it, it in ids(allowed)) }
        assertEquals(35, score(vpn) - score(allowed))
        assertTrue("외부 설치 규칙은 그대로", "src_unknown" in ids(allowed))
    }

    @Test fun userAllowedKeepsOtherRules() {
        // 원격조종 앱 + 사칭 이름 + 접근성: VPN을 허용해도 이 규칙들은 그대로 걸린다
        val remote = base().copy(
            packageName = "com.anydesk.vpnhelper", label = "검찰청 원격 VPN",
            firstInstallTime = now - 3 * RiskRules.DAY_MS,
            hasEnabledAccessibility = true, grantedCategories = setOf(PermCategory.ACCESSIBILITY),
            declaresVpnService = true, vpnActiveNow = true,
        )
        val allowed = remote.copy(vpnAllowedByUser = true)
        val kept = ids(remote) - RiskRules.VPN_RULE_IDS
        assertTrue("remote_control_recent" in kept)
        assertTrue("impersonation" in kept)
        assertTrue("accessibility" in kept)
        assertEquals(kept, ids(allowed))
        assertEquals(RiskScorer.score(remote, now).score - 35, RiskScorer.score(allowed, now).score)
    }

    @Test fun activeWithoutDeclarationIgnored() {
        assertFalse("sideloaded_vpn_active" in ids(base().copy(vpnActiveNow = true)))
    }
}
