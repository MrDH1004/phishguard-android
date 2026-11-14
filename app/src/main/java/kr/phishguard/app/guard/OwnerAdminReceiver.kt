package kr.phishguard.app.guard

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 기기 소유자(Device Owner) 모드용 관리자 수신기. 선택 기능이며 PC에서 한 번 설정해야 켜진다:
 * `adb shell dpm set-device-owner kr.phishguard.app/.guard.OwnerAdminReceiver`
 * 켜지면 이 앱이 지워지지 않게 막는다 (OwnerMode.protectSelf).
 */
class OwnerAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.i("PG-Owner", "관리자 켜짐 (소유자=${OwnerMode.isOwner(context)})")
        OwnerMode.protectSelf(context)
    }
}
