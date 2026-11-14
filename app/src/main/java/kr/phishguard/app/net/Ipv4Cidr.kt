package kr.phishguard.app.net

/** IPv4 대역 (시작 주소는 접두사 경계에 맞춰져 있다). 주소는 0..2^32-1 범위의 Long */
data class Ipv4Cidr(val start: Long, val prefix: Int) {
    init {
        require(prefix in 0..32) { "prefix 범위 밖: $prefix" }
        require(start in 0..MAX && start and (size - 1) == 0L) { "경계가 맞지 않는 대역: ${addr(start)}/$prefix" }
    }

    val size: Long get() = 1L shl (32 - prefix)
    val end: Long get() = start + size - 1
    val address: String get() = addr(start)

    override fun toString() = "$address/$prefix"

    companion object {
        const val MAX = 0xFFFF_FFFFL

        fun addr(v: Long) = "${v shr 24 and 255}.${v shr 16 and 255}.${v shr 8 and 255}.${v and 255}"

        fun parseAddress(s: String): Long {
            val p = s.split('.')
            require(p.size == 4) { "IPv4 주소 아님: $s" }
            return p.fold(0L) { acc, part ->
                val n = part.toIntOrNull()
                require(n != null && n in 0..255) { "IPv4 주소 아님: $s" }
                (acc shl 8) or n.toLong()
            }
        }

        /** "a.b.c.d/n" (접두사 없으면 /32) */
        fun parse(s: String): Ipv4Cidr {
            val t = s.trim()
            val slash = t.indexOf('/')
            if (slash < 0) return Ipv4Cidr(parseAddress(t), 32)
            val prefix = t.substring(slash + 1).toIntOrNull()
            require(prefix != null) { "접두사 아님: $s" }
            return Ipv4Cidr(parseAddress(t.substring(0, slash)), prefix)
        }

        /** 파일 줄들 → 대역 목록 ('#' 주석·빈 줄 무시) */
        fun parseList(lines: List<String>): List<Ipv4Cidr> =
            lines.map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.map { parse(it) }

        /** [start, end] 구간을 덮는 최소 CIDR 목록 */
        fun fromRange(start: Long, end: Long): List<Ipv4Cidr> {
            val out = ArrayList<Ipv4Cidr>()
            var cur = start
            while (cur <= end) {
                // cur의 정렬이 허락하는 가장 큰 블록에서, 끝을 넘지 않을 때까지 줄인다
                var prefix = if (cur == 0L) 0 else 32 - java.lang.Long.numberOfTrailingZeros(cur).coerceAtMost(32)
                while (cur + (1L shl (32 - prefix)) - 1 > end) prefix++
                out += Ipv4Cidr(cur, prefix)
                cur += 1L shl (32 - prefix)
            }
            return out
        }

        /** 겹치거나 맞닿은 대역을 합친 구간 목록 (정렬됨) */
        fun mergeRanges(cidrs: List<Ipv4Cidr>): List<LongRange> {
            val sorted = cidrs.sortedBy { it.start }
            val out = ArrayList<LongRange>()
            var s = -1L
            var e = -1L
            for (c in sorted) {
                if (s >= 0 && c.start <= e + 1) {
                    if (c.end > e) e = c.end
                } else {
                    if (s >= 0) out += s..e
                    s = c.start
                    e = c.end
                }
            }
            if (s >= 0) out += s..e
            return out
        }

        /** 겹치거나 맞닿은 대역을 합쳐 최소 CIDR 목록으로 */
        fun merge(cidrs: List<Ipv4Cidr>): List<Ipv4Cidr> = mergeRanges(cidrs).flatMap { fromRange(it.first, it.last) }

        /** 전체 IPv4 공간에서 [excluded]를 뺀 나머지를 최소 CIDR 목록으로 */
        fun complement(excluded: List<Ipv4Cidr>): List<Ipv4Cidr> {
            val out = ArrayList<Ipv4Cidr>()
            var cur = 0L
            for (r in mergeRanges(excluded)) {
                if (r.first > cur) out += fromRange(cur, r.first - 1)
                cur = r.last + 1
            }
            if (cur <= MAX) out += fromRange(cur, MAX)
            return out
        }
    }
}
