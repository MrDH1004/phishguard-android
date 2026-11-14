package kr.phishguard.app.net

/**
 * 차단 VPN을 어떻게 세울지 정한다 (순수 함수).
 *
 * 안드로이드는 VPN을 하나만 켤 수 있고 라우트는 VPN 전체에 적용된다(앱별로 다르게 줄 수 없음).
 * - 인터넷 전체 차단 앱이 하나라도 있으면 FULL: 전체 차단 ∪ 해외 차단 앱을 모두 넣고 전부 막는다
 *   (해외 차단 앱은 그동안 더 엄격하게 전체 차단된다).
 * - 해외 차단 앱만 있으면 FOREIGN: 한국·사설 대역을 뺀 IPv4 대역과 IPv6 전체만 VPN으로 보낸다.
 *   한국 주소로 가는 통신은 라우트에 걸리지 않아 원래 회선으로 나간다.
 * - 차단할 앱이 없어도 '다른 VPN 앱 막기'가 켜져 있으면 STANDBY: VPN 자리만 차지한다.
 *   이 앱만 VPN에 넣고 라우트는 주지 않아 어떤 앱의 통신도 TUN으로 들어오지 않는다.
 */
object VpnPlan {
    enum class Mode { OFF, FULL, FOREIGN, STANDBY }

    data class Plan(
        val mode: Mode,
        val apps: Set<String>,
        /** VPN으로 보낼(=막을) IPv4 대역 */
        val ipv4Routes: List<Ipv4Cidr>,
        /** TUN 안쪽 주소를 DNS로 지정해 도메인 조회를 기록할지. FOREIGN에서는 조회가 모두 죽으므로 false */
        val dnsInTunnel: Boolean,
        /** IPv6 전체(::/0)를 VPN으로 보내 막을지 */
        val blocksIpv6: Boolean = mode == Mode.FULL || mode == Mode.FOREIGN,
    )

    /** 해외가 아닌 주소: 사설망·루프백·링크로컬·CGNAT·멀티캐스트·예약 (와이파이 공유기 DNS 등이 막히지 않게) */
    val NON_PUBLIC: List<Ipv4Cidr> = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
        "172.16.0.0/12", "192.168.0.0/16", "224.0.0.0/4", "240.0.0.0/4",
    ).map { Ipv4Cidr.parse(it) }

    /**
     * VPN이 켜져 있으면 결제가 안 될 수 있는 앱 (삼성페이·삼성 월렛).
     * AdGuard 문서: 한국 기기에서 로컬 VPN이 켜져 있으면 삼성페이가 동작하지 않을 수 있어 삼성페이를 열면 잠시 멈춘다.
     */
    val PAYMENT_PACKAGES = setOf("com.samsung.android.spay", "com.samsung.android.spaylite")

    private val ALL_IPV4 = listOf(Ipv4Cidr(0, 0))

    fun plan(
        fullBlocked: Set<String>,
        foreignBlocked: Set<String>,
        krRanges: List<Ipv4Cidr>,
        holdSlot: Boolean = false,
    ): Plan = when {
        fullBlocked.isNotEmpty() -> Plan(Mode.FULL, fullBlocked + foreignBlocked, ALL_IPV4, dnsInTunnel = true)
        foreignBlocked.isNotEmpty() -> Plan(Mode.FOREIGN, foreignBlocked, foreignRoutes(krRanges), dnsInTunnel = false)
        holdSlot -> Plan(Mode.STANDBY, emptySet(), emptyList(), dnsInTunnel = false)
        else -> Plan(Mode.OFF, emptySet(), emptyList(), dnsInTunnel = false)
    }

    fun foreignRoutes(krRanges: List<Ipv4Cidr>): List<Ipv4Cidr> = Ipv4Cidr.complement(krRanges + NON_PUBLIC)

    /**
     * 결제 앱이 앞에 떠 있는 동안 VPN을 잠시 내릴지.
     * 대기 모드에서만 (실제로 막는 앱이 있으면 내리지 않음). '항상 켜진 VPN 없이 연결 차단'이 켜져 있으면
     * VPN을 내리는 순간 모든 앱의 인터넷이 끊기므로 내리지 않는다.
     */
    fun shouldPauseForPayment(mode: Mode, optionOn: Boolean, lockdown: Boolean, foregroundPkg: String?): Boolean =
        mode == Mode.STANDBY && optionOn && !lockdown && foregroundPkg != null && foregroundPkg in PAYMENT_PACKAGES

    /** UsageEvents 종류 (UsageEvents.Event.ACTIVITY_RESUMED / ACTIVITY_PAUSED 와 같은 값) */
    const val EVENT_RESUMED = 1
    const val EVENT_PAUSED = 2

    /**
     * 시간순 앱 화면 이벤트로 지금 앞에 떠 있는 앱을 구한다. [current]는 이전까지의 결과.
     * 마지막으로 RESUMED된 앱이 그 뒤에 PAUSED되지 않았으면 그 앱, PAUSED되면 null.
     */
    fun foregroundAfter(current: String?, events: List<Pair<String, Int>>): String? {
        var fg = current
        for ((pkg, type) in events) {
            when (type) {
                EVENT_RESUMED -> fg = pkg
                EVENT_PAUSED -> if (fg == pkg) fg = null
            }
        }
        return fg
    }
}
