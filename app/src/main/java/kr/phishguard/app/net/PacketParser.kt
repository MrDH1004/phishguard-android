package kr.phishguard.app.net

/**
 * 차단 VPN(TUN)으로 들어온 패킷의 머리 부분만 읽는 순수 파서 (Android 의존 없음, 단위 테스트 대상).
 * 내용(본문)은 보지 않는다. 목적지 주소·포트와, DNS 질의라면 물어본 도메인 이름만 꺼낸다.
 *
 * 형식 근거: IPv4 RFC 791(헤더 길이 IHL, 프로토콜 9번째 바이트, 목적지 16~19), IPv6 RFC 8200(다음 헤더 6번째,
 * 목적지 24~39, 고정 헤더 40바이트), TCP RFC 9293(목적지 포트 2~3, 플래그 13번째 바이트 SYN=0x02),
 * UDP RFC 768(목적지 포트 2~3, 헤더 8바이트), DNS RFC 1035(헤더 12바이트, QDCOUNT 4~5, QNAME 길이-라벨 형식).
 */
object PacketParser {
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    data class Packet(
        val ipVersion: Int,
        val protocol: Int,
        val dstAddress: String,
        val dstPort: Int,
        /** TCP 연결 시작(SYN, ACK 없음) */
        val tcpSyn: Boolean,
        /** UDP 53번 DNS 질의의 첫 도메인 이름 */
        val dnsQuery: String?,
    )

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)

    /** [len]바이트까지만 유효. 형식이 맞지 않으면 null */
    fun parse(buf: ByteArray, len: Int): Packet? {
        if (len < 1 || len > buf.size) return null
        return when (u8(buf, 0) shr 4) {
            4 -> parseV4(buf, len)
            6 -> parseV6(buf, len)
            else -> null
        }
    }

    private fun parseV4(b: ByteArray, len: Int): Packet? {
        if (len < 20) return null
        val ihl = (u8(b, 0) and 0x0f) * 4
        if (ihl < 20 || ihl > len) return null
        val proto = u8(b, 9)
        val dst = "${u8(b, 16)}.${u8(b, 17)}.${u8(b, 18)}.${u8(b, 19)}"
        // 조각난 패킷의 뒷조각에는 TCP/UDP 헤더가 없다 (fragment offset != 0)
        val fragOffset = u16(b, 6) and 0x1fff
        return transport(b, len, ihl, proto, dst, 4, fragOffset == 0)
    }

    private fun parseV6(b: ByteArray, len: Int): Packet? {
        if (len < 40) return null
        val next = u8(b, 6)
        val sb = StringBuilder()
        for (i in 0 until 8) {
            if (i > 0) sb.append(':')
            sb.append(Integer.toHexString(u16(b, 24 + i * 2)))
        }
        // 확장 헤더는 따라가지 않는다 (TCP/UDP가 바로 오는 흔한 경우만)
        return transport(b, len, 40, next, compressV6(sb.toString()), 6, true)
    }

    private fun transport(b: ByteArray, len: Int, off: Int, proto: Int, dst: String, ver: Int, hasL4: Boolean): Packet {
        var port = 0
        var syn = false
        var dns: String? = null
        if (hasL4 && (proto == PROTO_TCP || proto == PROTO_UDP) && len >= off + 4) {
            port = u16(b, off + 2)
            if (proto == PROTO_TCP && len >= off + 14) {
                val flags = u8(b, off + 13)
                syn = (flags and 0x02) != 0 && (flags and 0x10) == 0
            }
            if (proto == PROTO_UDP && port == 53) dns = dnsQueryName(b, off + 8, len)
        }
        return Packet(ver, proto, dst, port, syn, dns)
    }

    /** DNS 질의의 첫 이름. 압축 포인터·이상한 길이면 null */
    internal fun dnsQueryName(b: ByteArray, start: Int, len: Int): String? {
        if (len < start + 12) return null
        val flags = u16(b, start + 2)
        if ((flags and 0x8000) != 0) return null // 응답이면 무시 (질의만)
        if (u16(b, start + 4) < 1) return null
        var i = start + 12
        val labels = ArrayList<String>()
        var total = 0
        while (i < len) {
            val l = u8(b, i)
            if (l == 0) break
            if (l > 63 || (l and 0xc0) != 0) return null
            if (i + 1 + l > len) return null
            total += l + 1
            if (total > 253) return null
            val sb = StringBuilder(l)
            for (k in i + 1..i + l) {
                val c = u8(b, k)
                // 도메인에 쓰는 글자만 (그 밖은 ? 로 표시해 화면·CSV를 망가뜨리지 않게)
                sb.append(if (c in 0x21..0x7e && c != ','.code) c.toChar().lowercaseChar() else '?')
            }
            labels.add(sb.toString())
            i += 1 + l
        }
        if (i >= len || labels.isEmpty()) return null
        return labels.joinToString(".")
    }

    /** 가장 긴 0 묶음을 :: 로 줄임 (RFC 5952 간이) */
    internal fun compressV6(full: String): String {
        val parts = full.split(':')
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < parts.size) {
            if (parts[i] == "0") {
                var j = i
                while (j < parts.size && parts[j] == "0") j++
                if (j - i > bestLen && j - i >= 2) {
                    bestStart = i
                    bestLen = j - i
                }
                i = j
            } else {
                i++
            }
        }
        if (bestStart < 0) return full
        val head = parts.subList(0, bestStart).joinToString(":")
        val tail = parts.subList(bestStart + bestLen, parts.size).joinToString(":")
        return "$head::$tail"
    }
}
