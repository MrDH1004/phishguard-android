package kr.phishguard.app.guard

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.ContextCompat
import kr.phishguard.app.net.VpnGuard
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 기기 소유자(Device Owner) 모드 — 선택 기능. PC에서
 * `adb shell dpm set-device-owner kr.phishguard.app/.guard.OwnerAdminReceiver`를 한 번 실행한 폰에서만 켜진다.
 * 켜져 있으면: 확인 창 없이 의심 앱 삭제, 앱 정지(실행 불가, 데이터·증거는 남음), 이 앱 삭제 방지.
 * 꺼져 있으면 아무것도 하지 않고 기존 방식(삭제 확인 창)을 쓴다.
 */
object OwnerMode {
    private const val TAG = "PG-Owner"
    const val ADB_COMMAND = "adb shell dpm set-device-owner kr.phishguard.app/.guard.OwnerAdminReceiver"
    private const val ACTION_UNINSTALL_RESULT = "kr.phishguard.app.OWNER_UNINSTALL_RESULT"
    private const val UNINSTALL_WAIT_MS = 60_000L
    private val requestCode = AtomicInteger(5000)

    data class Outcome(val ok: Boolean, val message: String)

    fun admin(context: Context) = ComponentName(context, OwnerAdminReceiver::class.java)

    private fun dpm(context: Context): DevicePolicyManager? = context.getSystemService(DevicePolicyManager::class.java)

    fun isOwner(context: Context): Boolean = dpm(context)?.isDeviceOwnerApp(context.packageName) == true

    /** 소유자면 이 앱을 지울 수 없게 막고, '다른 VPN 앱 막기'가 켜져 있으면 항상 켜진 VPN으로 고정한다 */
    fun protectSelf(context: Context) {
        if (!isOwner(context)) return
        try {
            dpm(context)?.setUninstallBlocked(admin(context), context.packageName, true)
        } catch (e: SecurityException) {
            Log.e(TAG, "삭제 방지 실패", e)
        }
        // 켜질 때는 고정만 한다 (옵션이 꺼져 있을 때 사용자가 직접 설정한 항상 켜진 VPN을 지우지 않게)
        VpnGuard.load(context)
        if (VpnGuard.hold.value) VpnGuard.applyOwnerVpnLock(context, admin(context))
    }

    /**
     * 소유자 모드에서 항상 켜진 VPN 고정을 '다른 VPN 앱 막기' 옵션에 맞춘다 (옵션이 꺼져 있으면 고정하지 않음 — 삼성페이 영향).
     * 옵션 스위치를 바꿀 때 부른다. 소유자가 아니면 아무것도 안 함.
     */
    fun syncVpnLock(context: Context) {
        if (!isOwner(context)) return
        VpnGuard.load(context)
        if (VpnGuard.hold.value) VpnGuard.applyOwnerVpnLock(context, admin(context)) else VpnGuard.clearOwnerVpnLock(context, admin(context))
    }

    fun selfProtected(context: Context): Boolean = isOwner(context) && try {
        dpm(context)?.isUninstallBlocked(admin(context), context.packageName) == true
    } catch (e: SecurityException) {
        Log.w(TAG, "삭제 방지 상태 확인 실패", e)
        false
    }

    /** 앱 정지/해제. 정지된 앱은 실행되지 않지만 지워지지 않아 증거가 남는다 */
    fun setSuspended(context: Context, pkg: String, suspended: Boolean): Outcome {
        val dpm = dpm(context) ?: return Outcome(false, "기기 관리 기능을 쓸 수 없습니다")
        if (!isOwner(context)) return Outcome(false, "기기 소유자 모드가 꺼져 있습니다")
        return try {
            val failed = dpm.setPackagesSuspended(admin(context), arrayOf(pkg), suspended)
            if (failed.isEmpty()) {
                Outcome(true, if (suspended) "앱을 정지했습니다 (실행 불가, 증거는 남김)" else "앱 정지를 풀었습니다")
            } else {
                Outcome(false, if (suspended) "이 앱은 정지할 수 없습니다" else "정지를 풀지 못했습니다")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "정지 실패 $pkg", e)
            Outcome(false, "정지 권한 오류")
        }
    }

    /**
     * 소유자 권한으로 확인 창 없이 삭제. 최대 1분 기다린다 (IO 스레드에서 호출).
     * 소유자가 아니면 시스템이 사용자 확인을 요구하므로(STATUS_PENDING_USER_ACTION) 실패로 돌려준다.
     */
    fun silentUninstall(context: Context, pkg: String): Outcome {
        val app = context.applicationContext
        if (!isOwner(app)) return Outcome(false, "기기 소유자 모드가 꺼져 있습니다")
        val code = requestCode.incrementAndGet()
        val status = AtomicReference<Pair<Int, String?>>()
        val done = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getIntExtra(EXTRA_CODE, -1) != code) return
                status.set(i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE) to i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                done.countDown()
            }
        }
        // 우리 앱이 만든 PendingIntent로만 오므로 외부 공개 안 함
        ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_UNINSTALL_RESULT), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            // 설치 프로그램이 결과를 채워 넣어야 하므로 MUTABLE, 대상은 이 앱으로 고정
            val pi = PendingIntent.getBroadcast(
                app, code,
                Intent(ACTION_UNINSTALL_RESULT).setPackage(app.packageName).putExtra(EXTRA_CODE, code),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            app.packageManager.packageInstaller.uninstall(pkg, pi.intentSender)
            if (!done.await(UNINSTALL_WAIT_MS, TimeUnit.MILLISECONDS)) return Outcome(false, "1분 안에 삭제 결과를 받지 못했습니다")
            val (st, msg) = status.get()
            Log.i(TAG, "조용한 삭제 $pkg → $st $msg")
            return when (st) {
                PackageInstaller.STATUS_SUCCESS -> Outcome(true, "부모 폰에서 앱을 삭제했습니다 (기기 소유자 모드, 확인 창 없음)")
                PackageInstaller.STATUS_PENDING_USER_ACTION -> Outcome(false, "휴대폰이 사용자 확인을 요구해 삭제하지 않았습니다")
                else -> Outcome(false, "삭제 실패 (${msg ?: st})")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "조용한 삭제 권한 오류 $pkg", e)
            return Outcome(false, "삭제 권한 오류")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "조용한 삭제 실패 $pkg", e)
            return Outcome(false, "삭제 실패 (${e.message})")
        } finally {
            try {
                app.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "삭제 결과 수신 해제 실패", e)
            }
        }
    }

    /**
     * 소유자 모드 해제: 이 앱 삭제 방지를 풀고 소유자 권한을 내려놓는다. 해제 후에는 다시 PC에서 설정해야 켜진다.
     * 부모 폰에서는 자녀 승인(release_owner 명령) 또는 가족 연결이 끊긴 상태에서만 부른다.
     */
    @Suppress("DEPRECATION") // clearDeviceOwnerApp: API 26부터 권장하지 않지만 소유자가 스스로 내려놓는 유일한 방법
    fun release(context: Context): Outcome {
        val dpm = dpm(context) ?: return Outcome(false, "기기 관리 기능을 쓸 수 없습니다")
        if (!isOwner(context)) return Outcome(true, "이미 꺼져 있습니다")
        return try {
            dpm.setUninstallBlocked(admin(context), context.packageName, false)
            VpnGuard.clearOwnerVpnLock(context, admin(context))
            dpm.clearDeviceOwnerApp(context.packageName)
            Log.i(TAG, "기기 소유자 해제")
            Outcome(true, "기기 소유자 모드를 해제했습니다")
        } catch (e: SecurityException) {
            Log.e(TAG, "해제 실패", e)
            Outcome(false, "해제 실패 (권한 오류)")
        }
    }

    private const val EXTRA_CODE = "pg_code"
}
