package kr.phishguard.app.remove

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager

/** 삭제를 막는 활성 권한(기기관리자/접근성/알림 접근) 현재 상태 */
data class BlockingState(
    val deviceAdmin: Boolean,
    val accessibility: Boolean,
    val notificationListener: Boolean,
) {
    val any get() = deviceAdmin || accessibility || notificationListener
}

object AdminAccessibilityDetector {
    fun detect(context: Context, pkg: String): BlockingState {
        val admin = try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            dpm?.activeAdmins?.any { it.packageName == pkg } == true
        } catch (e: SecurityException) {
            Log.w(TAG, "기기관리자 조회 실패", e)
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "기기관리자 조회 실패", e)
            false
        }

        val acc = try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { it.resolveInfo?.serviceInfo?.packageName == pkg } == true
        } catch (e: SecurityException) {
            Log.w(TAG, "접근성 조회 실패", e)
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "접근성 조회 실패", e)
            false
        }

        val notif = try {
            val listeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            listeners.split(':').any { ComponentName.unflattenFromString(it)?.packageName == pkg }
        } catch (e: SecurityException) {
            Log.w(TAG, "알림 접근 조회 실패", e)
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "알림 접근 조회 실패", e)
            false
        }

        return BlockingState(admin, acc, notif)
    }

    private const val TAG = "PG-Detector"

    private fun launch(context: Context, vararg intents: Intent): Boolean {
        for (i in intents) {
            try {
                context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }

    fun openDeviceAdminSettings(context: Context): Boolean = launch(
        context,
        Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.DeviceAdminSettings")),
        Intent(Settings.ACTION_SECURITY_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )

    fun openAccessibilitySettings(context: Context): Boolean = launch(
        context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), Intent(Settings.ACTION_SETTINGS),
    )

    fun openNotificationListenerSettings(context: Context): Boolean = launch(
        context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), Intent(Settings.ACTION_SETTINGS),
    )
}
