package kr.phishguard.app.background

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import kr.phishguard.app.family.FamilyController
import kr.phishguard.app.net.BlockList
import kr.phishguard.app.net.MonitorVpnService

/** 부팅/앱 업데이트 후 감시 작업 재예약, 차단 목록이 있으면 차단 VPN 복구 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        BackgroundScheduler.ensureScheduled(app)
        FamilyController.ensureServices(app)

        BlockList.load(app)
        if (BlockList.blocked.value.isEmpty()) return
        if (VpnService.prepare(app) != null) {
            Log.i(TAG, "VPN 동의 없음: 차단 복구 생략")
            return
        }
        try {
            MonitorVpnService.start(app)
        } catch (e: IllegalStateException) {
            if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                Log.e(TAG, "포그라운드 서비스 시작 불가", e)
            } else {
                throw e
            }
        }
    }

    private companion object {
        const val TAG = "PG-BootReceiver"
    }
}
