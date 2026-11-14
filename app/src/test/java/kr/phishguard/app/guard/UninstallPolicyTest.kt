package kr.phishguard.app.guard

import kr.phishguard.app.family.AlertApp
import kr.phishguard.app.family.FamilyEvent
import kr.phishguard.app.family.FamilyPolicy
import kr.phishguard.app.guard.GuardPolicy.UninstallCheck
import kr.phishguard.app.scan.InstallSource
import kr.phishguard.app.scan.RiskLevel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UninstallPolicyTest {
    private val self = "kr.phishguard.app"

    private fun check(
        pkg: String = "kr.scam.app",
        installed: Boolean = true,
        system: Boolean = false,
        reported: Boolean = true,
        ageMs: Long = 60_000,
        canDraw: Boolean = true,
    ) = GuardPolicy.checkUninstall(pkg, self, installed, system, reported, ageMs, canDraw)

    @Test
    fun runsForReportedInstalledUserApp() {
        assertEquals(UninstallCheck.RUN, check())
        assertEquals(UninstallCheck.RUN, check(ageMs = 0)) // 시각을 모르면 나이 검사 안 함
        assertEquals(UninstallCheck.RUN, check(ageMs = GuardPolicy.UNINSTALL_MAX_AGE_MS))
    }

    @Test
    fun refusesUnsafeTargets() {
        assertEquals(UninstallCheck.NO_PACKAGE, check(pkg = ""))
        assertEquals(UninstallCheck.NO_PACKAGE, check(pkg = "  "))
        assertEquals(UninstallCheck.SELF, check(pkg = self))
        assertEquals(UninstallCheck.SYSTEM, check(system = true))
        // 사기범이 자녀 역할로 연결돼도 이 폰이 알린 적 없는 앱(은행·메신저)은 지우게 할 수 없다
        assertEquals(UninstallCheck.NOT_REPORTED, check(reported = false))
        assertEquals(UninstallCheck.TOO_OLD, check(ageMs = GuardPolicy.UNINSTALL_MAX_AGE_MS + 1))
        assertEquals(UninstallCheck.NO_OVERLAY, check(canDraw = false))
        assertEquals("다른 앱 위에 그리기 권한 없음", UninstallCheck.NO_OVERLAY.message)
        listOf(UninstallCheck.NO_PACKAGE, UninstallCheck.SELF, UninstallCheck.SYSTEM, UninstallCheck.NOT_REPORTED, UninstallCheck.TOO_OLD, UninstallCheck.NO_OVERLAY)
            .forEach { assertFalse(it.name, it.ok) }
    }

    @Test
    fun alreadyRemovedIsSuccessAndChecksOrder() {
        assertEquals(UninstallCheck.ALREADY_REMOVED, check(installed = false, reported = false))
        assertTrue(UninstallCheck.ALREADY_REMOVED.ok)
        // 오래된 명령은 다른 조건보다 먼저 거절
        assertEquals(UninstallCheck.TOO_OLD, check(pkg = self, ageMs = GuardPolicy.UNINSTALL_MAX_AGE_MS + 1))
        // 자기 자신은 설치 여부와 관계없이 거절
        assertEquals(UninstallCheck.SELF, check(pkg = self, installed = false))
        // 권한이 없어도 대상 검사가 먼저 (권한 문제로 위장된 거절이 없도록)
        assertEquals(UninstallCheck.NOT_REPORTED, check(reported = false, canDraw = false))
    }

    private fun ev(id: Long, type: String, data: JSONObject) = FamilyEvent(id, id * 1000, type, data)

    private fun appJson(pkg: String, score: Int = 120) = AlertApp(
        pkg, pkg, RiskLevel.HIGH, score, InstallSource.UNKNOWN.label, 1L, emptyList(), false,
    ).toJson()

    private fun result(id: Long, pkg: String, stage: String, ok: Boolean = true) = ev(
        id, FamilyEvent.TYPE_COMMAND_RESULT,
        JSONObject().put("type", "uninstall").put("packageName", pkg).put("stage", stage).put("ok", ok).put("message", "m$stage"),
    )

    @Test
    fun removedAppDisappearsFromRiskyListUntilReinstalled() {
        val events = mutableListOf(
            ev(1, FamilyEvent.TYPE_ALERT, JSONObject().put("app", appJson("a"))),
            ev(2, FamilyEvent.TYPE_ALERT, JSONObject().put("app", appJson("b"))),
            result(3, "a", "prompt"),
        )
        assertEquals(listOf("a", "b"), FamilyPolicy.currentRiskyApps(events).map { it.packageName }.sorted())
        events += result(4, "a", "not_removed", ok = false)
        assertEquals(2, FamilyPolicy.currentRiskyApps(events).size)
        events += result(5, "a", "removed")
        assertEquals(listOf("b"), FamilyPolicy.currentRiskyApps(events).map { it.packageName })
        // 다시 설치돼 알림이 오면 다시 보임
        events += ev(6, FamilyEvent.TYPE_ALERT, JSONObject().put("app", appJson("a")))
        assertEquals(listOf("a", "b"), FamilyPolicy.currentRiskyApps(events).map { it.packageName }.sorted())
    }

    @Test
    fun uninstallStatusShowsLatestForPackage() {
        assertNull(FamilyPolicy.uninstallStatus(emptyList(), "a"))
        val sent = ev(1, FamilyEvent.TYPE_COMMAND_SENT, JSONObject().put("type", "uninstall").put("packageName", "a"))
        assertTrue(FamilyPolicy.uninstallStatus(listOf(sent), "a")!!.contains("기다리는 중"))
        assertNull(FamilyPolicy.uninstallStatus(listOf(sent), "b"))
        assertEquals("mremoved", FamilyPolicy.uninstallStatus(listOf(result(3, "a", "removed"), sent, result(2, "a", "prompt")), "a"))
    }

    @Test
    fun installSourceLabelMapsBackIncludingOldLabel() {
        assertEquals(InstallSource.UNKNOWN, AlertApp.sourceOf("APK 직접 설치"))
        assertEquals(InstallSource.UNKNOWN, AlertApp.sourceOf("알 수 없음(APK 직접 설치)")) // v1.3.1 이하
        assertEquals(InstallSource.PLAY_STORE, AlertApp.sourceOf("Play 스토어"))
        assertEquals(InstallSource.OTHER_STORE, AlertApp.sourceOf("기타 스토어/앱"))
        assertEquals(InstallSource.SYSTEM, AlertApp.sourceOf("시스템"))
        assertEquals(InstallSource.UNKNOWN, AlertApp.sourceOf(""))
    }

    @Test
    fun alertAppOriginRoundTripsAndOldJsonStillParses() {
        val a = AlertApp("x", "X", RiskLevel.HIGH, 100, "APK 직접 설치", 1L, emptyList(), false, null, "com.kakao.talk")
        val back = AlertApp.fromJson(a.toJson())
        assertEquals("com.kakao.talk", back.initiatingPackage)
        assertNull(back.installerPackage)
        assertTrue(back.originText(), back.originText().contains("카카오톡에서 받은 파일로 설치"))
        val old = AlertApp.fromJson(JSONObject().put("packageName", "y").put("installSource", "Play 스토어"))
        assertNull(old.initiatingPackage)
        assertEquals("Play 스토어에서 설치", old.originText())
    }

    private fun suspendCheck(
        pkg: String = "kr.scam.app",
        installed: Boolean = true,
        system: Boolean = false,
        reported: Boolean = true,
        owner: Boolean = true,
        suspend: Boolean = true,
    ) = GuardPolicy.checkSuspend(pkg, self, installed, system, reported, 1000, owner, suspend)

    @Test
    fun suspendNeedsOwnerAndReportedTarget() {
        assertEquals(UninstallCheck.RUN, suspendCheck())
        assertEquals(UninstallCheck.NOT_OWNER, suspendCheck(owner = false))
        assertEquals(UninstallCheck.NOT_REPORTED, suspendCheck(reported = false))
        // 해제는 푸는 것뿐이라 알린 적 없는 앱도 된다
        assertEquals(UninstallCheck.RUN, suspendCheck(reported = false, suspend = false))
        assertEquals(UninstallCheck.SELF, suspendCheck(pkg = self))
        assertEquals(UninstallCheck.SELF, suspendCheck(pkg = self, suspend = false))
        assertEquals(UninstallCheck.SYSTEM, suspendCheck(system = true))
        assertEquals(UninstallCheck.SYSTEM, suspendCheck(system = true, suspend = false))
        assertEquals(UninstallCheck.NO_PACKAGE, suspendCheck(pkg = ""))
        assertEquals(UninstallCheck.ALREADY_REMOVED, suspendCheck(installed = false))
        assertEquals(
            UninstallCheck.TOO_OLD,
            GuardPolicy.checkSuspend("a", self, true, false, true, GuardPolicy.UNINSTALL_MAX_AGE_MS + 1, true, true),
        )
    }

    @Test
    fun autoActionsAddSuspendOnlyInOwnerModeAndNeverLock() {
        assertEquals(listOf(GuardPolicy.AutoAction.BLOCK), GuardPolicy.autoActions(owner = false))
        assertEquals(listOf(GuardPolicy.AutoAction.BLOCK, GuardPolicy.AutoAction.SUSPEND), GuardPolicy.autoActions(owner = true))
    }

    @Test
    fun ownerReleaseRequestPendingUntilChildAnswers() {
        val req = ev(1, FamilyEvent.TYPE_OWNER_RELEASE_REQUEST, JSONObject())
        val sent = ev(2, FamilyEvent.TYPE_COMMAND_SENT, JSONObject().put("type", "release_owner"))
        assertFalse(FamilyPolicy.pendingOwnerRelease(emptyList()))
        assertTrue(FamilyPolicy.pendingOwnerRelease(listOf(req)))
        assertFalse(FamilyPolicy.pendingOwnerRelease(listOf(req, sent)))
        // 승인 뒤 다시 요청하면 다시 보임
        assertTrue(FamilyPolicy.pendingOwnerRelease(listOf(req, sent, ev(3, FamilyEvent.TYPE_OWNER_RELEASE_REQUEST, JSONObject()))))
        // 다른 명령은 답으로 치지 않음
        assertTrue(FamilyPolicy.pendingOwnerRelease(listOf(req, ev(4, FamilyEvent.TYPE_COMMAND_SENT, JSONObject().put("type", "lock")))))
    }

    @Test
    fun suspendStatusSeparateFromUninstall() {
        val s1 = ev(1, FamilyEvent.TYPE_COMMAND_SENT, JSONObject().put("type", "suspend").put("packageName", "a"))
        assertNull(FamilyPolicy.uninstallStatus(listOf(s1), "a"))
        assertTrue(FamilyPolicy.suspendStatus(listOf(s1), "a")!!.contains("기다리는 중"))
        val r = ev(2, FamilyEvent.TYPE_COMMAND_RESULT, JSONObject().put("type", "unsuspend").put("packageName", "a").put("ok", true).put("message", "풀림"))
        assertEquals("풀림", FamilyPolicy.suspendStatus(listOf(s1, r), "a"))
    }

    @Test
    fun recentVpnRevokeWithinOneDay() {
        val day = 24L * 60 * 60 * 1000
        val now = 10 * day
        val old = FamilyEvent(1, now - 2 * day, FamilyEvent.TYPE_VPN_REVOKED, JSONObject().put("time", now - 2 * day))
        assertNull(FamilyPolicy.recentVpnRevoke(listOf(old), now))
        val recent = FamilyEvent(2, now, FamilyEvent.TYPE_VPN_REVOKED, JSONObject().put("time", now - 1000))
        assertEquals(now - 1000, FamilyPolicy.recentVpnRevoke(listOf(old, recent), now))
        // 시각이 없으면 서버 기록 시각
        val noTime = FamilyEvent(3, now - 5000, FamilyEvent.TYPE_VPN_REVOKED, JSONObject())
        assertEquals(now - 5000, FamilyPolicy.recentVpnRevoke(listOf(noTime), now))
    }

    @Test
    fun parentUnlinkNeedsChildApprovalOnlyInOwnerModeAfterPairing() {
        assertEquals(FamilyPolicy.ParentUnlink.ASK_CHILD, FamilyPolicy.parentUnlink(owner = true, paired = true))
        assertEquals(FamilyPolicy.ParentUnlink.NOW, FamilyPolicy.parentUnlink(owner = false, paired = true))
        // 승인 전에는 승인해 줄 자녀가 없다
        assertEquals(FamilyPolicy.ParentUnlink.NOW, FamilyPolicy.parentUnlink(owner = true, paired = false))
    }

    @Test
    fun unlinkRequestCarriesFlag() {
        val req = ev(1, FamilyEvent.TYPE_OWNER_RELEASE_REQUEST, JSONObject().put("unlink", true))
        assertTrue(FamilyPolicy.pendingOwnerReleaseRequest(listOf(req))!!.data.optBoolean("unlink"))
        val plain = ev(2, FamilyEvent.TYPE_OWNER_RELEASE_REQUEST, JSONObject().put("unlink", false))
        assertFalse(FamilyPolicy.pendingOwnerReleaseRequest(listOf(req, plain))!!.data.optBoolean("unlink"))
    }

    @Test
    fun onlyServerUnknownTokenCountsAsUnlinked() {
        // 사기범이 네트워크만 끊거나 프록시가 401을 돌려줘도 연결이 끊긴 것으로 보지 않는다 (로컬 해제 우회 방지)
        assertTrue(kr.phishguard.app.family.FamilyApiException(401, "unknown_token", "").unlinked)
        assertFalse(kr.phishguard.app.family.FamilyApiException(401, "http_401", "").unlinked)
        assertFalse(kr.phishguard.app.family.FamilyApiException(403, "unknown_token", "").unlinked)
        assertFalse(kr.phishguard.app.family.FamilyApiException(0, "bad_response", "").unlinked)
    }
}
