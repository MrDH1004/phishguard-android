package kr.phishguard.app.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskScorerTest {
    private val now = 1_800_000_000_000L
    private val day = RiskRules.DAY_MS

    private fun input(
        label: String = "테스트",
        pkg: String = "com.example.app",
        source: InstallSource = InstallSource.PLAY_STORE,
        system: Boolean = false,
        icon: Boolean = true,
        installedDaysAgo: Long = 365,
        perms: Set<PermCategory> = emptySet(),
        admin: Boolean = false,
        accessibility: Boolean = false,
        notif: Boolean = false,
        self: Boolean = false,
    ) = RiskInput(
        packageName = pkg, label = label, installSource = source, isSystem = system,
        hasLauncherIcon = icon, firstInstallTime = now - installedDaysAgo * day,
        grantedCategories = perms, isActiveDeviceAdmin = admin,
        hasEnabledAccessibility = accessibility, hasEnabledNotificationListener = notif,
        isSelf = self,
    )

    @Test
    fun fakeMalwareProfileIsHigh() {
        val r = RiskScorer.score(
            input(
                label = "검찰청 사건조회", pkg = "kr.test.malware", source = InstallSource.UNKNOWN,
                icon = false, installedDaysAgo = 1,
                perms = setOf(PermCategory.SMS, PermCategory.CALL_LOG),
                admin = true,
            ),
            now,
        )
        assertEquals(RiskLevel.HIGH, r.level)
        assertTrue(r.score >= 60)
        assertTrue(r.matchedRules.any { it.id == "impersonation" })
        assertTrue(r.matchedRules.any { it.id == "combo_sms_control" })
    }

    @Test
    fun normalPlayStoreAppIsLow() {
        val r = RiskScorer.score(
            input(label = "메모장", perms = setOf(PermCategory.CAMERA_MIC, PermCategory.STORAGE)),
            now,
        )
        assertEquals(0, r.score)
        assertEquals(RiskLevel.LOW, r.level)
        assertTrue(r.matchedRules.isEmpty())
    }

    @Test
    fun impersonationKeywordWithPlayStoreGivesNoKeywordPoints() {
        val play = RiskScorer.score(input(label = "KB 은행", source = InstallSource.PLAY_STORE), now)
        assertFalse(play.matchedRules.any { it.id == "impersonation" })
        val unknown = RiskScorer.score(input(label = "KB 은행", source = InstallSource.UNKNOWN), now)
        assertTrue(unknown.matchedRules.any { it.id == "impersonation" })
    }

    @Test
    fun impersonationLabelIsCaseInsensitive() {
        val r = RiskScorer.score(input(label = "Safe BANK", source = InstallSource.OTHER_STORE), now)
        assertTrue(r.matchedRules.any { it.id == "impersonation" })
    }

    @Test
    fun systemAppIsNotPenalized() {
        val r = RiskScorer.score(
            input(
                label = "통화 보안", source = InstallSource.SYSTEM, system = true, icon = false,
                installedDaysAgo = 0,
            ),
            now,
        )
        assertEquals(0, r.score)
        assertEquals(RiskLevel.LOW, r.level)
    }

    @Test
    fun recencyBoundaries() {
        val d7 = RiskScorer.score(input(installedDaysAgo = 7), now)
        assertEquals(listOf("recent_7d"), d7.matchedRules.map { it.id })
        val d8 = RiskScorer.score(input(installedDaysAgo = 8), now)
        assertEquals(listOf("recent_30d"), d8.matchedRules.map { it.id })
        val d30 = RiskScorer.score(input(installedDaysAgo = 30), now)
        assertEquals(listOf("recent_30d"), d30.matchedRules.map { it.id })
        val d31 = RiskScorer.score(input(installedDaysAgo = 31), now)
        assertTrue(d31.matchedRules.isEmpty())
    }

    @Test
    fun levelThresholds() {
        assertEquals(RiskLevel.LOW, RiskScorer.levelFor(29))
        assertEquals(RiskLevel.MEDIUM, RiskScorer.levelFor(30))
        assertEquals(RiskLevel.MEDIUM, RiskScorer.levelFor(59))
        assertEquals(RiskLevel.HIGH, RiskScorer.levelFor(60))
    }

    @Test
    fun scoreAtMediumBoundary() {
        // 알 수 없는 출처 30점 → 정확히 MEDIUM
        val r = RiskScorer.score(input(source = InstallSource.UNKNOWN), now)
        assertEquals(30, r.score)
        assertEquals(RiskLevel.MEDIUM, r.level)
        // 기타 스토어 5점 → LOW
        val other = RiskScorer.score(input(source = InstallSource.OTHER_STORE), now)
        assertEquals(5, other.score)
        assertEquals(RiskLevel.LOW, other.level)
    }

    @Test
    fun scoreAtHighBoundary() {
        // 알 수 없는 출처 30 + 접근성 30 = 60 → HIGH
        val r = RiskScorer.score(input(source = InstallSource.UNKNOWN, accessibility = true), now)
        assertEquals(60, r.score)
        assertEquals(RiskLevel.HIGH, r.level)
    }

    @Test
    fun selfAppNeverAboveLow() {
        val r = RiskScorer.score(
            input(
                source = InstallSource.UNKNOWN, icon = false, installedDaysAgo = 0,
                perms = setOf(PermCategory.SMS), accessibility = true, admin = true, self = true,
            ),
            now,
        )
        assertEquals(RiskLevel.LOW, r.level)
        assertTrue(r.score < RiskRules.THRESHOLD_MEDIUM)
    }

    @Test
    fun ruleIdsAreUnique() {
        assertEquals(RiskRules.ALL.size, RiskRules.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun settingListMatchesPackageExactly() {
        val s = "com.a.b/com.a.b.Svc:com.c.d/.X"
        assertTrue(PermissionMap.settingListContains(s, "com.c.d"))
        assertFalse(PermissionMap.settingListContains(s, "com.a"))
        assertFalse(PermissionMap.settingListContains(null, "com.a"))
    }
}
