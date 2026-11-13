package kr.phishguard.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketParserTest {
    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** IPv4 헤더(20) + 목적지 [a.b.c.d], 프로토콜 */
    private fun ipv4(proto: Int, dst: IntArray, l4: ByteArray): ByteArray {
        val h = ByteArray(20)
        h[0] = 0x45
        val total = 20 + l4.size
        h[2] = (total shr 8).toByte(); h[3] = total.toByte()
        h[8] = 64
        h[9] = proto.toByte()
        h[12] = 10; h[13] = 111; h[14] = 0; h[15] = 2
        for (i in 0..3) h[16 + i] = dst[i].toByte()
        return h + l4
    }

    private fun tcp(dstPort: Int, flags: Int): ByteArray {
        val t = ByteArray(20)
        t[0] = 0xc3.toByte(); t[1] = 0x50
        t[2] = (dstPort shr 8).toByte(); t[3] = dstPort.toByte()
        t[12] = 0x50
        t[13] = flags.toByte()
        return t
    }

    private fun dnsQuery(name: String): ByteArray {
        val q = java.io.ByteArrayOutputStream()
        q.write(hex("abcd 0100 0001 0000 0000 0000"))
        name.split('.').forEach { l -> q.write(l.length); q.write(l.toByteArray()) }
        q.write(0); q.write(hex("0001 0001"))
        val dns = q.toByteArray()
        val udp = ByteArray(8)
        udp[0] = 0xd4.toByte(); udp[1] = 0x31
        udp[2] = 0; udp[3] = 53
        val len = 8 + dns.size
        udp[4] = (len shr 8).toByte(); udp[5] = len.toByte()
        return udp + dns
    }

    @Test
    fun tcpSynToC2() {
        val p = ipv4(6, intArrayOf(202, 79, 165, 171), tcp(2096, 0x02))
        val r = PacketParser.parse(p, p.size)!!
        assertEquals(4, r.ipVersion)
        assertEquals("202.79.165.171", r.dstAddress)
        assertEquals(2096, r.dstPort)
        assertTrue(r.tcpSyn)
        // ACK·SYN+ACK는 연결 시작이 아님
        val ack = ipv4(6, intArrayOf(1, 2, 3, 4), tcp(443, 0x10))
        assertFalse(PacketParser.parse(ack, ack.size)!!.tcpSyn)
        val synAck = ipv4(6, intArrayOf(1, 2, 3, 4), tcp(443, 0x12))
        assertFalse(PacketParser.parse(synAck, synAck.size)!!.tcpSyn)
    }

    @Test
    fun dnsQueryName() {
        val p = ipv4(17, intArrayOf(8, 8, 8, 8), dnsQuery("RkOwmGhx.com"))
        val r = PacketParser.parse(p, p.size)!!
        assertEquals(53, r.dstPort)
        assertEquals("rkowmghx.com", r.dnsQuery)
    }

    @Test
    fun ipv6Address() {
        val h = ByteArray(40)
        h[0] = 0x60
        h[6] = 6
        // 2001:db8::1
        h[24] = 0x20; h[25] = 0x01; h[26] = 0x0d; h[27] = 0xb8.toByte(); h[39] = 1
        val p = h + tcp(443, 0x02)
        val r = PacketParser.parse(p, p.size)!!
        assertEquals("2001:db8::1", r.dstAddress)
        assertEquals(443, r.dstPort)
        assertTrue(r.tcpSyn)
    }

    @Test
    fun compressV6() {
        assertEquals("2001:db8::1", PacketParser.compressV6("2001:db8:0:0:0:0:0:1"))
        assertEquals("::1", PacketParser.compressV6("0:0:0:0:0:0:0:1"))
        assertEquals("2001:db8:0:1:2:3:4:5", PacketParser.compressV6("2001:db8:0:1:2:3:4:5")) // 0 하나는 줄이지 않음
    }

    @Test
    fun garbageIsRejectedWithoutCrash() {
        assertNull(PacketParser.parse(ByteArray(0), 0))
        assertNull(PacketParser.parse(ByteArray(10), 10))
        assertNull(PacketParser.parse(byteArrayOf(0x45), 1))
        val bad = ipv4(17, intArrayOf(8, 8, 8, 8), dnsQuery("a.b")).copyOf(30) // 잘린 DNS
        assertNull(PacketParser.parse(bad, bad.size)!!.dnsQuery)
        val r = java.util.Random(1)
        repeat(5000) {
            val b = ByteArray(r.nextInt(80)).also { r.nextBytes(it) }
            if (b.isNotEmpty()) b[0] = (if (r.nextBoolean()) 0x45 else 0x60).toByte()
            PacketParser.parse(b, b.size)
        }
    }
}
