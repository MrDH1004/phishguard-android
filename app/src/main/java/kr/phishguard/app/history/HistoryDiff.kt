package kr.phishguard.app.history

import kr.phishguard.app.scan.InstallSource
import kr.phishguard.app.scan.RiskLevel

/** 설치·삭제 기록 종류 */
enum class HistoryEventType(val label: String) {
    INSTALLED("설치됨"),
    UPDATED("업데이트됨"),
    REMOVED("삭제됨"),
    INSTALLED_THEN_REMOVED("설치 후 삭제됨"),
}

/** 마지막으로 확인한 앱 하나의 상태 */
data class SnapshotEntry(
    val label: String,
    val versionName: String?,
    val installer: String?,
    val installSource: InstallSource,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val riskLevel: RiskLevel,
    val riskScore: Int,
    val isSystem: Boolean,
    /** 설치를 요청한 앱 (APK를 연 메신저·브라우저 등) */
    val initiating: String? = null,
)

data class HistoryEvent(
    val type: HistoryEventType,
    val packageName: String,
    val label: String?,
    val versionName: String?,
    val installer: String?,
    val installSource: InstallSource?,
    val riskLevel: RiskLevel?,
    /** 일이 일어난 시각. timeIsExact = false면 이 앱이 변화를 확인한 시각 */
    val eventTime: Long,
    val timeIsExact: Boolean,
    val detectedAt: Long,
    val initiating: String? = null,
)

/** 스냅샷 비교로 기록을 만드는 순수 로직 (Android 의존 없음). */
object HistoryDiff {

    /**
     * @param old 이전 스냅샷
     * @param current 지금 설치된 앱
     * @param changedNames getChangedPackages가 알려준 바뀐 패키지 (모르면 null)
     * @param now 지금 시각
     * @param selfPackage 이 앱 패키지 (기록에서 제외)
     * @return 새 기록 (eventTime 최신순). 한 패키지당 최대 1건.
     */
    fun compute(
        old: Map<String, SnapshotEntry>,
        current: Map<String, SnapshotEntry>,
        changedNames: Collection<String>?,
        now: Long,
        selfPackage: String,
    ): List<HistoryEvent> {
        val events = LinkedHashMap<String, HistoryEvent>()

        changedNames.orEmpty().forEach { pkg ->
            if (pkg == selfPackage || pkg in events) return@forEach
            val cur = current[pkg]
            val prev = old[pkg]
            val event = when {
                cur != null && prev == null -> installed(pkg, cur, now)
                cur != null && prev != null && cur.firstInstallTime != prev.firstInstallTime -> installed(pkg, cur, now)
                // 시스템 앱(Play 서비스 등) 자동 업데이트는 기록하지 않음 — 목록이 시끄러워짐
                cur != null && prev != null && !cur.isSystem && cur.lastUpdateTime != prev.lastUpdateTime -> updated(pkg, cur, now)
                cur == null && prev != null -> removed(pkg, prev, now)
                cur == null && prev == null -> HistoryEvent(
                    type = HistoryEventType.INSTALLED_THEN_REMOVED,
                    packageName = pkg,
                    label = null,
                    versionName = null,
                    installer = null,
                    installSource = null,
                    riskLevel = null,
                    eventTime = now,
                    timeIsExact = false,
                    detectedAt = now,
                )
                else -> null
            }
            if (event != null) events[pkg] = event
        }

        // 스냅샷 비교: 재부팅 등으로 바뀐 목록을 못 받은 경우까지 메운다
        old.forEach { (pkg, prev) ->
            if (pkg != selfPackage && pkg !in current && pkg !in events) events[pkg] = removed(pkg, prev, now)
        }
        current.forEach { (pkg, cur) ->
            if (pkg == selfPackage || pkg in events) return@forEach
            val prev = old[pkg]
            if (prev == null || prev.firstInstallTime != cur.firstInstallTime) events[pkg] = installed(pkg, cur, now)
        }

        return events.values.sortedByDescending { it.eventTime }
    }

    private fun installed(pkg: String, e: SnapshotEntry, now: Long) =
        fromEntry(HistoryEventType.INSTALLED, pkg, e, e.firstInstallTime, true, now)

    private fun updated(pkg: String, e: SnapshotEntry, now: Long) =
        fromEntry(HistoryEventType.UPDATED, pkg, e, e.lastUpdateTime, true, now)

    private fun removed(pkg: String, e: SnapshotEntry, now: Long) =
        fromEntry(HistoryEventType.REMOVED, pkg, e, now, false, now)

    private fun fromEntry(
        type: HistoryEventType,
        pkg: String,
        e: SnapshotEntry,
        time: Long,
        exact: Boolean,
        now: Long,
    ) = HistoryEvent(
        type = type,
        packageName = pkg,
        label = e.label,
        versionName = e.versionName,
        installer = e.installer,
        installSource = e.installSource,
        riskLevel = e.riskLevel,
        eventTime = time,
        timeIsExact = exact,
        detectedAt = now,
        initiating = e.initiating,
    )
}
