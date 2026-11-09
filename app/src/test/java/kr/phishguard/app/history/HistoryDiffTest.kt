package kr.phishguard.app.history

import kr.phishguard.app.scan.InstallSource
import kr.phishguard.app.scan.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryDiffTest {
    private val self = "kr.phishguard.app"
    private val now = 1_000_000L

    private fun entry(
        label: String,
        first: Long = 100L,
        update: Long = first,
        source: InstallSource = InstallSource.PLAY_STORE,
        risk: RiskLevel = RiskLevel.LOW,
    ) = SnapshotEntry(label, "1.0", "com.android.vending", source, first, update, risk, 0, false)

    @Test fun removalKeepsLabelFromSnapshot() {
        val old = mapOf("a.bank" to entry("은행", risk = RiskLevel.HIGH, source = InstallSource.UNKNOWN))
        val events = HistoryDiff.compute(old, emptyMap(), listOf("a.bank"), now, self)
        assertEquals(1, events.size)
        val e = events[0]
        assertEquals(HistoryEventType.REMOVED, e.type)
        assertEquals("은행", e.label)
        assertEquals(RiskLevel.HIGH, e.riskLevel)
        assertEquals(InstallSource.UNKNOWN, e.installSource)
        assertEquals(now, e.eventTime)
        assertFalse(e.timeIsExact)
    }

    @Test fun removalDetectedBySnapshotDiffWithoutChangedNames() {
        val old = mapOf("a.app" to entry("앱"))
        val events = HistoryDiff.compute(old, emptyMap(), null, now, self)
        assertEquals(listOf(HistoryEventType.REMOVED), events.map { it.type })
        assertEquals("앱", events[0].label)
    }

    @Test fun installThenRemove() {
        val events = HistoryDiff.compute(emptyMap(), emptyMap(), listOf("x.temp"), now, self)
        assertEquals(1, events.size)
        val e = events[0]
        assertEquals(HistoryEventType.INSTALLED_THEN_REMOVED, e.type)
        assertEquals("x.temp", e.packageName)
        assertNull(e.label)
        assertNull(e.riskLevel)
        assertFalse(e.timeIsExact)
    }

    @Test fun installUsesExactFirstInstallTime() {
        val cur = mapOf("n.app" to entry("새 앱", first = 500L))
        val events = HistoryDiff.compute(emptyMap(), cur, listOf("n.app"), now, self)
        assertEquals(HistoryEventType.INSTALLED, events.single().type)
        assertEquals(500L, events.single().eventTime)
        assertTrue(events.single().timeIsExact)
    }

    @Test fun update() {
        val old = mapOf("u.app" to entry("앱", first = 100L, update = 100L))
        val cur = mapOf("u.app" to entry("앱", first = 100L, update = 700L))
        val events = HistoryDiff.compute(old, cur, listOf("u.app"), now, self)
        assertEquals(HistoryEventType.UPDATED, events.single().type)
        assertEquals(700L, events.single().eventTime)
        assertTrue(events.single().timeIsExact)
    }

    @Test fun unchangedProducesNothing() {
        val snap = mapOf("s.app" to entry("앱"))
        assertTrue(HistoryDiff.compute(snap, snap, emptyList(), now, self).isEmpty())
        assertTrue(HistoryDiff.compute(snap, snap, null, now, self).isEmpty())
    }

    @Test fun noDuplicateEvents() {
        val old = mapOf("gone.app" to entry("지운 앱"))
        val cur = mapOf("new.app" to entry("새 앱", first = 300L))
        val events = HistoryDiff.compute(
            old, cur, listOf("gone.app", "new.app", "gone.app", "new.app"), now, self,
        )
        assertEquals(2, events.size)
        assertEquals(1, events.count { it.packageName == "gone.app" && it.type == HistoryEventType.REMOVED })
        assertEquals(1, events.count { it.packageName == "new.app" && it.type == HistoryEventType.INSTALLED })
    }

    @Test fun ownPackageIgnored() {
        val old = mapOf(self to entry("피싱앱 검사기"))
        val cur = mapOf(self to entry("피싱앱 검사기", update = 999L))
        assertTrue(HistoryDiff.compute(old, cur, listOf(self), now, self).isEmpty())
        assertTrue(HistoryDiff.compute(old, emptyMap(), listOf(self), now, self).isEmpty())
        assertTrue(HistoryDiff.compute(emptyMap(), cur, null, now, self).isEmpty())
    }

    @Test fun sortedNewestFirst() {
        val old = mapOf("gone.app" to entry("지운 앱"))
        val cur = mapOf("new.app" to entry("새 앱", first = 300L))
        val events = HistoryDiff.compute(old, cur, null, now, self)
        assertEquals(listOf("gone.app", "new.app"), events.map { it.packageName })
    }
}
