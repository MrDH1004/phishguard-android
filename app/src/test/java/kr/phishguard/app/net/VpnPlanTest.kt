package kr.phishguard.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnPlanTest {
    private val kr = listOf("1.11.0.0/16", "223.130.0.0/15").map { Ipv4Cidr.parse(it) }
    private fun covers(l: List<Ipv4Cidr>, ip: String): Boolean {
        val a = Ipv4Cidr.parseAddress(ip)
        return l.any { a in it.start..it.end }
    }

    @Test fun offWhenNothingBlocked() {
        val p = VpnPlan.plan(emptySet(), emptySet(), kr)
        assertEquals(VpnPlan.Mode.OFF, p.mode)
        assertTrue(p.apps.isEmpty())
        assertTrue(p.ipv4Routes.isEmpty())
    }

    @Test fun fullBlockUnchanged() {
        val p = VpnPlan.plan(setOf("a"), emptySet(), kr)
        assertEquals(VpnPlan.Mode.FULL, p.mode)
        assertEquals(setOf("a"), p.apps)
        assertEquals(listOf("0.0.0.0/0"), p.ipv4Routes.map { it.toString() })
        assertTrue(p.dnsInTunnel)
    }

    @Test fun fullWinsAndAbsorbsForeign() {
        val p = VpnPlan.plan(setOf("a"), setOf("b", "a"), kr)
        assertEquals(VpnPlan.Mode.FULL, p.mode)
        assertEquals(setOf("a", "b"), p.apps)
        assertEquals(listOf("0.0.0.0/0"), p.ipv4Routes.map { it.toString() })
        assertTrue(p.dnsInTunnel)
    }

    @Test fun standbyHoldsSlotWithoutRoutes() {
        val p = VpnPlan.plan(emptySet(), emptySet(), kr, holdSlot = true)
        assertEquals(VpnPlan.Mode.STANDBY, p.mode)
        assertTrue("다른 앱을 VPN에 넣지 않는다", p.apps.isEmpty())
        assertTrue("라우트 없음: 어떤 통신도 TUN으로 오지 않는다", p.ipv4Routes.isEmpty())
        assertFalse(p.blocksIpv6)
        assertFalse(p.dnsInTunnel)
    }

    @Test fun blockingWinsOverStandby() {
        assertEquals(VpnPlan.Mode.FULL, VpnPlan.plan(setOf("a"), emptySet(), kr, holdSlot = true).mode)
        assertEquals(VpnPlan.Mode.FOREIGN, VpnPlan.plan(emptySet(), setOf("b"), kr, holdSlot = true).mode)
        assertTrue(VpnPlan.plan(setOf("a"), emptySet(), kr, holdSlot = true).blocksIpv6)
        assertEquals(VpnPlan.Mode.OFF, VpnPlan.plan(emptySet(), emptySet(), kr, holdSlot = false).mode)
    }

    @Test fun pauseForPaymentOnlyInStandby() {
        val pay = "com.samsung.android.spay"
        assertTrue(VpnPlan.shouldPauseForPayment(VpnPlan.Mode.STANDBY, true, false, pay))
        assertFalse("옵션 꺼짐", VpnPlan.shouldPauseForPayment(VpnPlan.Mode.STANDBY, false, false, pay))
        assertFalse("차단 중에는 내리지 않음", VpnPlan.shouldPauseForPayment(VpnPlan.Mode.FULL, true, false, pay))
        assertFalse("차단 중에는 내리지 않음", VpnPlan.shouldPauseForPayment(VpnPlan.Mode.FOREIGN, true, false, pay))
        assertFalse("연결 차단(lockdown)이면 내리면 전부 끊김", VpnPlan.shouldPauseForPayment(VpnPlan.Mode.STANDBY, true, true, pay))
        assertFalse(VpnPlan.shouldPauseForPayment(VpnPlan.Mode.STANDBY, true, false, "com.android.chrome"))
        assertFalse(VpnPlan.shouldPauseForPayment(VpnPlan.Mode.STANDBY, true, false, null))
    }

    @Test fun foregroundTracking() {
        val r = VpnPlan.EVENT_RESUMED
        val p = VpnPlan.EVENT_PAUSED
        assertEquals("b", VpnPlan.foregroundAfter(null, listOf("a" to r, "a" to p, "b" to r)))
        assertEquals(null, VpnPlan.foregroundAfter("b", listOf("b" to p)))
        assertEquals("b", VpnPlan.foregroundAfter("b", emptyList()))
        // 다른 앱의 늦은 PAUSED가 지금 앞에 있는 앱을 지우지 않는다
        assertEquals("b", VpnPlan.foregroundAfter(null, listOf("a" to r, "b" to r, "a" to p)))
        assertEquals("a", VpnPlan.foregroundAfter("b", listOf("b" to 23, "a" to r)))
    }

    @Test fun foreignOnly() {
        val p = VpnPlan.plan(emptySet(), setOf("b"), kr)
        assertEquals(VpnPlan.Mode.FOREIGN, p.mode)
        assertEquals(setOf("b"), p.apps)
        assertFalse("DNS를 TUN에 넣으면 조회가 전부 죽는다", p.dnsInTunnel)
        assertTrue(covers(p.ipv4Routes, "8.8.8.8"))
        assertTrue(covers(p.ipv4Routes, "93.184.215.14"))
        assertFalse(covers(p.ipv4Routes, "1.11.5.5"))
        assertFalse(covers(p.ipv4Routes, "223.131.0.1"))
        // 사설·특수 대역은 해외가 아니다 (공유기 DNS, 에뮬레이터 10.0.2.3 등)
        listOf("10.0.2.3", "192.168.0.1", "172.16.0.1", "127.0.0.1", "100.64.0.1", "169.254.1.1", "224.0.0.251", "255.255.255.255")
            .forEach { assertFalse(it, covers(p.ipv4Routes, it)) }
        val excluded = (kr + VpnPlan.NON_PUBLIC).let { Ipv4Cidr.merge(it) }.sumOf { it.size }
        assertEquals((1L shl 32) - excluded, p.ipv4Routes.sumOf { it.size })
    }
}
