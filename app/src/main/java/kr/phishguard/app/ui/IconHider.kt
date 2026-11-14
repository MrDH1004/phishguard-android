package kr.phishguard.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 앱 아이콘 숨기기. 런처 진입점(activity-alias)만 끄고 켠다. MainActivity 자체는 그대로라 타일·알림으로 열 수 있다.
 *
 * Android 10 이상에서는 권한을 쓰는 앱이 런처 진입점을 끄면 시스템이 '앱 정보'로 가는 대체 아이콘을 보여 준다.
 * 그래서 완전히 숨겨지지는 않고, 숨긴 동안 앱은 빠른설정 타일([OpenAppTileService])로 연다.
 */
object IconHider {
    private const val ALIAS = "kr.phishguard.app.ui.LauncherAlias"

    private fun alias(context: Context) = ComponentName(context.packageName, ALIAS)

    fun isHidden(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(alias(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    fun setHidden(context: Context, hidden: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            alias(context),
            if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP,
        )
    }
}
