package kr.phishguard.app.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 자리를 가져간 VPN이 허용 앱인지 추정: VPN 앱 후보가 모두 허용 앱일 때만 허용 */
class VpnAllowListTest {
    @Test fun singleAllowedVpnApp() {
        assertTrue(VpnAllowList.takenByAllowed(setOf("corp.vpn"), setOf("corp.vpn")))
    }

    @Test fun allCandidatesAllowed() {
        assertTrue(VpnAllowList.takenByAllowed(setOf("corp.vpn", "home.vpn"), setOf("corp.vpn", "home.vpn", "old.app")))
    }

    @Test fun unknownCandidateMeansThreat() {
        assertFalse(VpnAllowList.takenByAllowed(setOf("corp.vpn", "evil.vpn"), setOf("corp.vpn")))
    }

    @Test fun noCandidateMeansThreat() {
        // 후보가 없으면 누가 가져갔는지 추정 불가 → 위협으로 처리
        assertFalse(VpnAllowList.takenByAllowed(emptySet(), setOf("corp.vpn")))
        assertFalse(VpnAllowList.takenByAllowed(setOf("evil.vpn"), emptySet()))
    }
}
