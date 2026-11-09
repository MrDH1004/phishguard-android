package kr.phishguard.app.report

import kr.phishguard.app.history.HistoryEvent
import kr.phishguard.app.history.HistoryEventType
import kr.phishguard.app.scan.AppInfo
import kr.phishguard.app.scan.InstallSource
import kr.phishguard.app.scan.MatchedRule
import kr.phishguard.app.scan.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTest {
    private fun app(pkg: String, label: String, level: RiskLevel, score: Int) = AppInfo(
        packageName = pkg, label = label, uid = 1, versionName = "1.0",
        firstInstallTime = 1_700_000_000_000L, lastUpdateTime = 1_700_000_000_000L,
        installerPackage = null, installSource = InstallSource.UNKNOWN, hasLauncherIcon = false,
        isSystem = false, sourceDir = null, splitSourceDirs = emptyList(), permissions = emptyList(),
        isActiveDeviceAdmin = false, hasEnabledAccessibility = true, hasEnabledNotificationListener = false,
        riskScore = score, riskLevel = level, matchedRules = listOf(MatchedRule("r1", "규칙 <b>", 30)),
    )

    private fun input(apps: List<AppInfo>, trusted: Set<String> = emptySet(), events: List<HistoryEvent> = emptyList()) =
        ReportInput(1_700_000_000_000L, "Test <Phone>", "14", apps, trusted, events)

    @Test fun escapesHtml() {
        assertEquals("&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt; &amp; &#39;", ReportBuilder.escape("<script>alert(\"x\")</script> & '"))
        assertEquals("", ReportBuilder.escape(null))
    }

    @Test fun maliciousLabelNeverEmitsRawTag() {
        val html = ReportBuilder.build(input(listOf(app("a.b", "<img src=x onerror=1>", RiskLevel.HIGH, 80))))
        assertFalse(html.contains("<img src=x"))
        assertTrue(html.contains("&lt;img src=x onerror=1&gt;"))
        assertTrue(html.contains("Test &lt;Phone&gt;"))
    }

    @Test fun separatesSuspiciousAndTrustedAndSkipsLow() {
        val apps = listOf(
            app("sus.a", "의심", RiskLevel.HIGH, 90),
            app("trust.b", "아는앱", RiskLevel.MEDIUM, 40),
            app("low.c", "낮음", RiskLevel.LOW, 5),
        )
        val inp = input(apps, trusted = setOf("trust.b", "low.c"))
        assertEquals(listOf("sus.a"), ReportBuilder.suspicious(inp).map { it.packageName })
        assertEquals(listOf("trust.b"), ReportBuilder.trustedListed(inp).map { it.packageName })
    }

    @Test fun limitsEventsTo100() {
        val ev = (1..150).map {
            HistoryEvent(HistoryEventType.INSTALLED, "p$it", "L$it", null, null, null, null, it.toLong(), true, it.toLong())
        }
        val html = ReportBuilder.build(input(emptyList(), events = ev))
        assertTrue(html.contains("최근 100건"))
        assertTrue(html.contains("p100<"))
        assertFalse(html.contains("p101<"))
    }

    @Test fun sha256KnownVectorsAndManifest() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Hashing.sha256Hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hashing.sha256Hex("abc".toByteArray()))
        assertEquals("aa  x.json\nbb  y.csv\n", Hashing.manifest(listOf("x.json" to "aa", "y.csv" to "bb")))
        assertEquals("ba7816bf8f01cfea…", Hashing.short("ba7816bf8f01cfea414140de5dae2223"))
    }

    @Test fun sha256OfFileMatchesBytes() {
        val f = java.io.File.createTempFile("pgtest",".bin")
        try {
            f.writeBytes("abc".toByteArray())
            assertEquals(Hashing.sha256Hex("abc".toByteArray()), Hashing.sha256Hex(f))
        } finally {
            f.delete()
        }
    }

    @Test fun checklistItemsAreWellFormed() {
        val items = ResponseChecklist.items
        assertEquals(items.size, items.map { it.id }.toSet().size)
        items.forEach {
            when (val a = it.action) {
                is ChecklistAction.Dial -> assertTrue(a.number in setOf("112", "118", "1332"))
                is ChecklistAction.OpenUrl -> assertTrue(a.url.startsWith("https://"))
                ChecklistAction.None -> Unit
            }
        }
        assertEquals(2, ResponseChecklist.completed(setOf("police112", "msafer", "unknown")))
    }
}
