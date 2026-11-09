package kr.phishguard.app.net

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log

/** uid별 데이터 사용량 (안드로이드 기본 통계, 사용 정보 접근 권한 필요) */
data class UidUsage(val uid: Int, val wifiTx: Long, val wifiRx: Long, val mobileTx: Long, val mobileRx: Long) {
    val totalTx get() = wifiTx + mobileTx
    val totalRx get() = wifiRx + mobileRx
}

object UsageStats {
    private const val TAG = "PG-Usage"

    fun hasAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun accessSettingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /** [sinceMillis, now] 구간의 uid별 사용량. 권한이 없거나 실패하면 빈 맵 */
    fun query(context: Context, sinceMillis: Long): Map<Int, UidUsage> {
        if (!hasAccess(context)) return emptyMap()
        val nsm = context.getSystemService(NetworkStatsManager::class.java)
        val now = System.currentTimeMillis()
        val acc = HashMap<Int, LongArray>() // wifiTx, wifiRx, mobTx, mobRx
        fun collect(type: Int, offset: Int) {
            val stats: NetworkStats = try {
                nsm.querySummary(type, null, sinceMillis, now)
            } catch (e: SecurityException) {
                Log.w(TAG, "querySummary 권한 오류 type=$type", e); return
            } catch (e: android.os.RemoteException) {
                Log.w(TAG, "querySummary 실패 type=$type", e); return
            } catch (e: RuntimeException) {
                Log.w(TAG, "querySummary 런타임 오류 type=$type", e); return
            }
            stats.use { s ->
                val b = NetworkStats.Bucket()
                while (s.hasNextBucket()) {
                    s.getNextBucket(b)
                    val a = acc.getOrPut(b.uid) { LongArray(4) }
                    a[offset] += b.txBytes
                    a[offset + 1] += b.rxBytes
                }
            }
        }
        collect(ConnectivityManager.TYPE_WIFI, 0)
        collect(ConnectivityManager.TYPE_MOBILE, 2)
        return acc.mapValues { (uid, a) -> UidUsage(uid, a[0], a[1], a[2], a[3]) }
    }

    fun format(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }
}
