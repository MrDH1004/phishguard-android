package kr.phishguard.app.guard

import kr.phishguard.app.family.AlertApp
import kr.phishguard.app.family.FamilyEvent
import kr.phishguard.app.family.FamilyPolicy
import kr.phishguard.app.scan.RiskLevel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardPolicyTest {
    @Test
    fun thresholdIsClampedTo100to150InStepsOf10() {
        assertEquals(100, GuardPolicy.clampThreshold(40))
        assertEquals(150, GuardPolicy.clampThreshold(999))
        assertEquals(120, GuardPolicy.clampThreshold(127))
        assertEquals(150, GuardPolicy.clampThreshold(GuardPolicy.THRESHOLD_DEFAULT))
    }

    @Test
    fun triggerOnThresholdOrSurge() {
        // 기준 이상
        assertEquals(GuardPolicy.Reason.SCORE, GuardPolicy.trigger(150, null, 150))
        assertEquals(GuardPolicy.Reason.SCORE, GuardPolicy.trigger(110, 100, 100))
        // 기준 미만이지만 50점 넘게 뛰어 100점 이상
        assertEquals(GuardPolicy.Reason.SURGE, GuardPolicy.trigger(120, 60, 150))
        // 급상승이지만 100점 미만
        assertNull(GuardPolicy.trigger(95, 30, 150))
        // 처음 보는 앱은 급상승 판단 안 함
        assertNull(GuardPolicy.trigger(140, null, 150))
        // 천천히 오름
        assertNull(GuardPolicy.trigger(140, 100, 150))
    }

    @Test
    fun messengersIncludeTelegramAndLine() {
        assertTrue("org.telegram.messenger" in GuardPolicy.MESSENGER_PACKAGES)
        assertTrue("jp.naver.line.android" in GuardPolicy.MESSENGER_PACKAGES)
    }

    @Test
    fun remainingText() {
        assertEquals("60:00", GuardPolicy.remainingText(3_600_000, 0))
        assertEquals("0:05", GuardPolicy.remainingText(5_000, 0))
        assertEquals("0:00", GuardPolicy.remainingText(0, 9_000))
    }

    private val app = AlertApp("com.evil", "검찰청", RiskLevel.HIGH, 160, "직접 설치", 1, listOf("사칭"), false)
    private fun ev(id: Long, type: String, data: JSONObject) = FamilyEvent(id, id * 1000, type, data)

    @Test
    fun pendingDecisionsDropAnsweredOrAutoBlocked() {
        val events = listOf(
            ev(1, FamilyEvent.TYPE_DECISION, JSONObject().put("requestId", "a").put("reason", "surge").put("previousScore", 40).put("autoBlockAt", 99).put("app", app.toJson())),
            ev(2, FamilyEvent.TYPE_DECISION, JSONObject().put("requestId", "b").put("reason", "score").put("app", app.toJson())),
            ev(3, FamilyEvent.TYPE_DECISION, JSONObject().put("requestId", "c").put("reason", "score").put("app", app.toJson())),
            ev(4, FamilyEvent.TYPE_COMMAND_SENT, JSONObject().put("requestId", "b").put("type", "lock")),
            ev(5, FamilyEvent.TYPE_COMMAND_RESULT, JSONObject().put("requestId", "c").put("type", "auto_block")),
        )
        val p = FamilyPolicy.pendingDecisions(events)
        assertEquals(listOf("a"), p.map { it.requestId })
        assertEquals("surge", p[0].reason)
        assertEquals(40, p[0].previousScore)
        assertEquals(99L, p[0].autoBlockAt)
        // 결정 요청 앱도 의심 앱 목록에 들어감
        assertEquals(listOf("com.evil"), FamilyPolicy.currentRiskyApps(events).map { it.packageName })
    }

    @Test
    fun pendingJoinNameFollowsLastJoinEvent() {
        val req = ev(1, FamilyEvent.TYPE_JOIN_REQUEST, JSONObject().put("parentName", "엄마 폰"))
        assertEquals("엄마 폰", FamilyPolicy.pendingJoinName(listOf(req), paired = false))
        assertNull(FamilyPolicy.pendingJoinName(listOf(req, ev(2, FamilyEvent.TYPE_JOIN_REJECTED, JSONObject())), paired = false))
        assertNull(FamilyPolicy.pendingJoinName(listOf(req), paired = true))
    }
}
