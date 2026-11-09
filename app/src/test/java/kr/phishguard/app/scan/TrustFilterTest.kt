package kr.phishguard.app.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustFilterTest {
    private fun app(pkg: String, level: RiskLevel, system: Boolean = false) = AppInfo(
        packageName = pkg, label = pkg, uid = 10000, versionName = "1.0",
        firstInstallTime = 0L, lastUpdateTime = 0L, installerPackage = null,
        installSource = InstallSource.OTHER_STORE, hasLauncherIcon = false, isSystem = system,
        sourceDir = null, splitSourceDirs = emptyList(), permissions = emptyList(),
        isActiveDeviceAdmin = false, hasEnabledAccessibility = false, hasEnabledNotificationListener = false,
        riskScore = 0, riskLevel = level, matchedRules = emptyList(),
    )

    private val watch = app("com.samsung.wearable.watch7plugin", RiskLevel.HIGH)
    private val buds = app("com.samsung.accessory.jellymgr", RiskLevel.MEDIUM)
    private val malware = app("kr.test.malware", RiskLevel.HIGH)
    private val memo = app("com.example.memo", RiskLevel.LOW)
    private val sysApp = app("android.system.thing", RiskLevel.MEDIUM, system = true)
    private val all = listOf(watch, buds, malware, memo, sysApp)
    private val trusted = setOf(watch.packageName, buds.packageName)

    @Test
    fun trustedExcludedFromCounts() {
        assertEquals(SummaryCounts(high = 1, medium = 0, low = 1), TrustFilter.summaryCounts(all, trusted, includeSystem = false))
    }

    @Test
    fun countsWithoutTrustCountEverythingNonSystem() {
        assertEquals(SummaryCounts(high = 2, medium = 1, low = 1), TrustFilter.summaryCounts(all, emptySet(), includeSystem = false))
    }

    @Test
    fun trustedHiddenByDefault() {
        val shown = TrustFilter.visibleApps(all, trusted, includeTrusted = false, includeSystem = false)
        assertEquals(listOf(malware, memo), shown)
    }

    @Test
    fun trustedShownWhenToggledButStillNotCounted() {
        val shown = TrustFilter.visibleApps(all, trusted, includeTrusted = true, includeSystem = false)
        assertEquals(listOf(watch, buds, malware, memo), shown)
        assertEquals(1, TrustFilter.summaryCounts(all, trusted, includeSystem = false).high)
    }

    @Test
    fun systemFilterInterplay() {
        val trustedSys = trusted + sysApp.packageName
        assertFalse(sysApp in TrustFilter.visibleApps(all, trustedSys, includeTrusted = true, includeSystem = false))
        assertTrue(sysApp in TrustFilter.visibleApps(all, trustedSys, includeTrusted = true, includeSystem = true))
        assertFalse(sysApp in TrustFilter.visibleApps(all, trustedSys, includeTrusted = false, includeSystem = true))
        assertTrue(sysApp in TrustFilter.visibleApps(all, emptySet(), includeTrusted = false, includeSystem = true))
        assertEquals(2, TrustFilter.presentTrusted(all, trustedSys, includeSystem = false).size)
        assertEquals(3, TrustFilter.presentTrusted(all, trustedSys, includeSystem = true).size)
        assertEquals(SummaryCounts(1, 0, 1), TrustFilter.summaryCounts(all, trustedSys, includeSystem = true))
        assertEquals(SummaryCounts(1, 1, 1), TrustFilter.summaryCounts(all, trusted, includeSystem = true))
    }

    @Test
    fun trustedPackageNotInstalledIsIgnored() {
        val stale = setOf("com.removed.app")
        assertEquals(0, TrustFilter.presentTrusted(all, stale, includeSystem = true).size)
        assertEquals(4, TrustFilter.visibleApps(all, stale, includeTrusted = false, includeSystem = false).size)
    }
}
