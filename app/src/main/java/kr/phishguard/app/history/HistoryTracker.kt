package kr.phishguard.app.history

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import kr.phishguard.app.scan.AppInfo

/** 검사 결과로 설치·삭제 기록을 갱신한다. */
object HistoryTracker {
    private const val TAG = "HistoryTracker"

    /** 검사 직후 호출 (IO 스레드). apps는 방금 조사한 전체 앱 목록. */
    fun sync(context: Context, apps: List<AppInfo>) {
        // 앱 목록 조회 자체가 실패하면 모든 앱이 지워진 것으로 오인하므로 건너뛴다
        if (apps.isEmpty()) {
            Log.w(TAG, "empty app list, skip sync")
            return
        }
        val appContext = context.applicationContext
        val pm = appContext.packageManager
        val self = appContext.packageName
        val bootCount = readBootCount(appContext)
        val now = System.currentTimeMillis()
        val scanned = apps.filter { it.packageName != self }.associate { it.packageName to it.toEntry() }

        HistoryStore.update(appContext) { st ->
            val old = st.snapshot
            if (old == null) {
                return@update st.copy(
                    snapshot = scanned,
                    lastChangedSeq = changedSeqFromStart(pm),
                    lastBootCount = bootCount,
                    baselineCreatedAt = now,
                )
            }

            var changedNames: List<String>? = null
            val newSeq: Int
            if (bootCount != HistoryStore.UNKNOWN_BOOT_COUNT && bootCount == st.lastBootCount) {
                val changed = changedPackages(pm, st.lastChangedSeq)
                if (changed != null) {
                    changedNames = changed.second
                    newSeq = changed.first
                } else {
                    newSeq = st.lastChangedSeq
                }
            } else {
                // 재부팅으로 순번이 초기화됨 → 스냅샷 비교로만 판단
                newSeq = changedSeqFromStart(pm)
            }

            // 조사 중 개별 실패로 목록에서 빠진 앱은 실제로 설치돼 있는지 다시 확인
            val current = LinkedHashMap(scanned)
            old.forEach { (pkg, prev) ->
                if (pkg !in current && isInstalled(pm, pkg)) current[pkg] = prev
            }
            val names = changedNames?.filter { it in current || it in old || !isInstalled(pm, it) }

            val newEvents = HistoryDiff.compute(old, current, names, now, self)
            st.copy(
                snapshot = current,
                events = (newEvents + st.events).sortedByDescending { it.eventTime },
                lastChangedSeq = newSeq,
                lastBootCount = bootCount,
            )
        }
    }

    private fun AppInfo.toEntry() = SnapshotEntry(
        label = label,
        versionName = versionName,
        installer = installerPackage,
        installSource = installSource,
        initiating = initiatingPackage,
        firstInstallTime = firstInstallTime,
        lastUpdateTime = lastUpdateTime,
        riskLevel = riskLevel,
        riskScore = riskScore,
        isSystem = isSystem,
    )

    private fun readBootCount(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (e: Settings.SettingNotFoundException) {
        Log.w(TAG, "BOOT_COUNT not found", e)
        HistoryStore.UNKNOWN_BOOT_COUNT
    } catch (e: RuntimeException) {
        Log.w(TAG, "BOOT_COUNT read failed", e)
        HistoryStore.UNKNOWN_BOOT_COUNT
    }

    /** (새 순번, 바뀐 패키지) 또는 바뀐 것이 없으면 null */
    private fun changedPackages(pm: PackageManager, seq: Int): Pair<Int, List<String>>? = try {
        pm.getChangedPackages(seq)?.let { it.sequenceNumber to it.packageNames }
    } catch (e: RuntimeException) {
        Log.w(TAG, "getChangedPackages($seq) failed", e)
        null
    }

    private fun changedSeqFromStart(pm: PackageManager): Int = changedPackages(pm, 0)?.first ?: 0

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
