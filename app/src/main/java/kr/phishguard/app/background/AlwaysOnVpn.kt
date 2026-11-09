package kr.phishguard.app.background

import android.content.Intent
import android.provider.Settings

/** "항상 켜진 VPN" 설정 화면으로 안내하는 인텐트 */
object AlwaysOnVpn {
    fun settingsIntent(): Intent = Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
