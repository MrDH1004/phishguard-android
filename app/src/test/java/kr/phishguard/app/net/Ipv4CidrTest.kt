package kr.phishguard.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Ipv4CidrTest {
    private fun c(s: String) = Ipv4Cidr.parse(s)
    private fun strs(l: List<Ipv4Cidr>) = l.map { it.toString() }
    private fun total(l: List<Ipv4Cidr>) = l.sumOf { it.size }
    private fun covers(l: List<Ipv4Cidr>, ip: String): Boolean {
        val a = Ipv4Cidr.parseAddress(ip)
        return l.any { a in it.start..it.end }
    }

    @Test fun parse() {
        assertEquals("1.11.0.0/16", c("1.11.0.0/16").toString())
        assertEquals(32, c("8.8.8.8").prefix)
        assertEquals(0L, c("0.0.0.0/0").start)
        assertEquals(1L shl 32, c("0.0.0.0/0").size)
        assertEquals(Ipv4Cidr.MAX, c("255.255.255.255/32").end)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseRejectsMisaligned() { c("10.0.0.1/8") }

    @Test(expected = IllegalArgumentException::class)
    fun parseRejectsBadOctet() { c("1.2.3.256/32") }

    @Test fun parseListSkipsComments() {
        val l = Ipv4Cidr.parseList(listOf("# header", "", "1.0.0.0/24  # 주석", "  2.0.0.0/8 "))
        assertEquals(listOf("1.0.0.0/24", "2.0.0.0/8"), strs(l))
    }

    @Test fun fromRangeMinimal() {
        assertEquals(listOf("0.0.0.0/0"), strs(Ipv4Cidr.fromRange(0, Ipv4Cidr.MAX)))
        assertEquals(listOf("10.0.0.0/8"), strs(Ipv4Cidr.fromRange(c("10.0.0.0/8").start, c("10.0.0.0/8").end)))
        // 1.0.0.1 ~ 1.0.0.6 → .1/32 .2/31 .4/31 .6/32
        val r = Ipv4Cidr.fromRange(Ipv4Cidr.parseAddress("1.0.0.1"), Ipv4Cidr.parseAddress("1.0.0.6"))
        assertEquals(listOf("1.0.0.1/32", "1.0.0.2/31", "1.0.0.4/31", "1.0.0.6/32"), strs(r))
        assertEquals(listOf("255.255.255.255/32"), strs(Ipv4Cidr.fromRange(Ipv4Cidr.MAX, Ipv4Cidr.MAX)))
    }

    @Test fun mergeAdjacentAndOverlapping() {
        assertEquals(listOf("10.0.0.0/23"), strs(Ipv4Cidr.merge(listOf(c("10.0.1.0/24"), c("10.0.0.0/24")))))
        assertEquals(listOf("10.0.0.0/8"), strs(Ipv4Cidr.merge(listOf(c("10.0.0.0/8"), c("10.1.0.0/16"), c("10.0.0.0/8")))))
        // 맞닿았지만 한 블록으로 못 합치는 경우 → 구간은 합치고 최소 CIDR로
        assertEquals(listOf("10.0.1.0/24", "10.0.2.0/24"), strs(Ipv4Cidr.merge(listOf(c("10.0.2.0/24"), c("10.0.1.0/24")))))
        assertEquals(emptyList<String>(), strs(Ipv4Cidr.merge(emptyList())))
    }

    @Test fun complementEdges() {
        assertEquals(listOf("0.0.0.0/0"), strs(Ipv4Cidr.complement(emptyList())))
        assertEquals(emptyList<String>(), strs(Ipv4Cidr.complement(listOf(c("0.0.0.0/0")))))
        assertEquals(listOf("128.0.0.0/1"), strs(Ipv4Cidr.complement(listOf(c("0.0.0.0/1")))))
        assertEquals(listOf("0.0.0.0/1"), strs(Ipv4Cidr.complement(listOf(c("128.0.0.0/1")))))
        // 처음·끝 주소만 빼기
        val noEnds = Ipv4Cidr.complement(listOf(c("0.0.0.0/32"), c("255.255.255.255/32")))
        assertEquals((1L shl 32) - 2, total(noEnds))
        assertEquals(62, noEnds.size) // 아래쪽 1.. 2^31-1 은 /32,/31,…,/2 = 31개, 위쪽도 대칭 31개
        assertFalse(covers(noEnds, "0.0.0.0"))
        assertFalse(covers(noEnds, "255.255.255.255"))
        assertTrue(covers(noEnds, "0.0.0.1"))
        assertTrue(covers(noEnds, "255.255.255.254"))
    }

    @Test fun complementOfSingleBlock() {
        val r = Ipv4Cidr.complement(listOf(c("10.0.0.0/8")))
        assertEquals((1L shl 32) - (1L shl 24), total(r))
        assertFalse(covers(r, "10.1.2.3"))
        assertTrue(covers(r, "9.255.255.255"))
        assertTrue(covers(r, "11.0.0.0"))
        // 결과 블록끼리 겹치지 않는다
        assertEquals(total(r), total(Ipv4Cidr.merge(r)))
    }

    /** 실제 assets 목록: 한국 대역 + 여집합 = 전체, 서로 겹치지 않음 */
    @Test fun krAssetComplement() {
        val f = File("src/main/assets/kr_ipv4.txt")
        assertTrue("assets 파일 없음: ${f.absolutePath}", f.exists())
        val kr = Ipv4Cidr.parseList(f.readLines(Charsets.UTF_8))
        assertTrue(kr.size > 500)
        val krMerged = Ipv4Cidr.merge(kr)
        assertEquals("생성 스크립트가 이미 합쳐서 저장", kr.size, krMerged.size)
        val comp = Ipv4Cidr.complement(kr)
        assertEquals((1L shl 32) - total(kr), total(comp))
        assertEquals(1L shl 32, total(Ipv4Cidr.merge(kr + comp)))
        // 대표 주소: 네이버(223.130.x, KT), 구글 DNS(미국)
        assertTrue(covers(kr, "223.130.200.107"))
        assertFalse(covers(kr, "8.8.8.8"))
        assertTrue(covers(comp, "8.8.8.8"))
        assertFalse(covers(comp, "223.130.200.107"))
    }
}
