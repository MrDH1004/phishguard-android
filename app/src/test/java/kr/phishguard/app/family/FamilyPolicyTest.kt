package kr.phishguard.app.family

import kr.phishguard.app.scan.AppInfo
import kr.phishguard.app.scan.InstallSource
import kr.phishguard.app.scan.MatchedRule
import kr.phishguard.app.scan.RiskLevel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyPolicyTest {
    private val self = "kr.phishguard.app"

    private fun app(
        pkg: String = "com.system.camera",
        label: String = "카메라",
        level: RiskLevel = RiskLevel.HIGH,
        score: Int = 195,
        source: InstallSource = InstallSource.UNKNOWN,
        system: Boolean = false,
        rules: List<MatchedRule> = emptyList(),
    ) = AppInfo(
        packageName = pkg, label = label, uid = 10000, versionName = "1", firstInstallTime = 1000L, lastUpdateTime = 1000L,
        installerPackage = null, installSource = source, hasLauncherIcon = true, isSystem = system, sourceDir = null,
        splitSourceDirs = emptyList(), permissions = emptyList(), isActiveDeviceAdmin = false, hasEnabledAccessibility = false,
        hasEnabledNotificationListener = false, riskScore = score, riskLevel = level, matchedRules = rules,
    )

    @Test
    fun reportsMediumHighAndRemoteControlOnly() {
        assertTrue(FamilyPolicy.shouldReport(app(), trusted = false, selfPackage = self))
        assertTrue(FamilyPolicy.shouldReport(app(level = RiskLevel.MEDIUM, score = 30), false, self))
        assertFalse(FamilyPolicy.shouldReport(app(level = RiskLevel.LOW, score = 10), false, self))
        // 낮음이어도 원격조종 앱이면 보냄
        assertTrue(FamilyPolicy.shouldReport(app(pkg = "net.koino.anysupportMobile", label = "애니서포트", level = RiskLevel.LOW), false, self))
        // 안전앱·자기 자신·시스템 앱은 안 보냄
        assertFalse(FamilyPolicy.shouldReport(app(), trusted = true, selfPackage = self))
        assertFalse(FamilyPolicy.shouldReport(app(pkg = self), false, self))
        assertFalse(FamilyPolicy.shouldReport(app(system = true, source = InstallSource.SYSTEM), false, self))
    }

    @Test
    fun alertKindOnlyWhenNewOrRiskUp() {
        assertEquals("installed", FamilyPolicy.alertKind(null, RiskLevel.MEDIUM))
        assertEquals("risk_up", FamilyPolicy.alertKind(RiskLevel.MEDIUM, RiskLevel.HIGH))
        assertNull(FamilyPolicy.alertKind(RiskLevel.HIGH, RiskLevel.HIGH))
        assertNull(FamilyPolicy.alertKind(RiskLevel.HIGH, RiskLevel.MEDIUM))
    }

    @Test
    fun alertCarriesTopThreeReasonsWithoutZeroPointRules() {
        val rules = listOf(
            MatchedRule("a", "5점", 5), MatchedRule("b", "100점", 100), MatchedRule("c", "0점", 0),
            MatchedRule("d", "30점", 30), MatchedRule("e", "15점", 15),
        )
        val a = FamilyPolicy.toAlertApp(app(rules = rules))
        assertEquals(listOf("100점", "30점", "15점"), a.reasons)
        // JSON 왕복
        val back = AlertApp.fromJson(JSONObject(a.toJson().toString()))
        assertEquals(a, back)
    }

    private fun ev(id: Long, type: String, data: JSONObject) = FamilyEvent(id, id * 1000, type, data)

    @Test
    fun currentRiskyAppsUsesLatestSnapshotThenAlerts() {
        val cam = AlertApp("com.system.camera", "카메라", RiskLevel.HIGH, 195, "직접 설치", 1, listOf("사칭"), false)
        val any = AlertApp("net.koino.anysupportMobile", "애니서포트", RiskLevel.MEDIUM, 45, "Play", 1, emptyList(), true)
        val old = AlertApp("com.old.app", "옛날", RiskLevel.MEDIUM, 30, "", 1, emptyList(), false)
        val events = listOf(
            ev(1, FamilyEvent.TYPE_PAIRED, JSONObject().put("parentName", "엄마 폰")),
            ev(2, FamilyEvent.TYPE_ALERT, JSONObject().put("kind", "installed").put("app", old.toJson())),
            ev(3, FamilyEvent.TYPE_SNAPSHOT, JSONObject().put("apps", JSONArray().put(any.toJson()))),
            ev(4, FamilyEvent.TYPE_ALERT, JSONObject().put("kind", "installed").put("app", cam.toJson())),
        )
        val list = FamilyPolicy.currentRiskyApps(events)
        assertEquals(listOf("com.system.camera", "net.koino.anysupportMobile"), list.map { it.packageName })
    }
}
