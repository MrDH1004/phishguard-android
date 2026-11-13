package kr.phishguard.app.family

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kr.phishguard.app.guard.GuardController
import kr.phishguard.app.scan.ScanRepository
import java.io.IOException

/** 가족 연결 동작 모음 (화면·백그라운드 공용). 네트워크 호출은 IO 스레드에서. */
object FamilyController {
    private const val TAG = "PG-FamilyController"
    private const val NOTI_SERVICE_BLOCKED = 3998

    /** 자녀: PIN 만들기 */
    fun startAsChild(context: Context, deviceName: String) {
        val r = FamilyApi.start(deviceName)
        FamilyStore.startChild(context, r.token, r.pin, r.expiresAt)
        ensureServices(context)
    }

    /** 자녀: PIN 만료 시 새로 받기 (이전 PIN은 서버에서 바로 무효) */
    fun refreshPin(context: Context) {
        val token = FamilyStore.token(context) ?: return
        try {
            val (pin, exp) = FamilyApi.newPin(token)
            FamilyStore.updatePin(context, pin, exp)
        } catch (e: FamilyApiException) {
            if (e.unlinked) onRemoteUnlinked(context) else throw e
        }
    }

    /** 부모: PIN 입력. 자녀 폰이 승인하면 상시 서비스가 실시간으로 받아 첫 목록을 보낸다 */
    fun joinAsParent(context: Context, pin: String, deviceName: String) {
        val r = FamilyApi.join(pin, deviceName)
        FamilyStore.startParent(context, r.token, r.childName)
        ensureServices(context)
        try {
            if (ScanRepository.apps.value.isEmpty()) ScanRepository.scan(context)
        } catch (e: SecurityException) {
            Log.e(TAG, "연결 직후 검사 권한 오류", e)
        }
    }

    /** 자녀: 부모 폰의 연결 요청 승인/거절. 거절하면 새 PIN을 받아 둔다 */
    fun answerJoin(context: Context, approve: Boolean) {
        val token = FamilyStore.token(context) ?: return
        try {
            FamilyApi.approve(token, approve)
            if (!approve) refreshPin(context)
            pollChild(context)
        } catch (e: FamilyApiException) {
            if (e.unlinked) onRemoteUnlinked(context) else throw e
        }
    }

    /** 자녀: 결정 요청에 답함 (lock / block / ignore) */
    fun sendCommand(context: Context, type: String, d: PendingDecision) {
        val token = FamilyStore.token(context) ?: return
        try {
            FamilyApi.command(token, type, d.requestId, d.app.packageName, d.app.label)
            pollChild(context)
        } catch (e: FamilyApiException) {
            if (e.unlinked) onRemoteUnlinked(context) else throw e
        }
    }

    /** 부모: 실시간 연결이 끊겨 있을 때 주기 작업에서 승인·명령을 받아 처리 */
    fun pollParent(context: Context) {
        val token = FamilyStore.token(context) ?: return
        val gen = FamilyStore.generation()
        try {
            val j = FamilyApi.state(token, FamilyStore.lastCommandId(context))
            if (j.optBoolean("paired") && !FamilyStore.state.value.paired) {
                FamilyStore.setApproved(context, gen)
                FamilyReporter.sync(context)
            }
            val cmds = j.optJSONArray("commands")
            if (cmds != null) {
                (0 until cmds.length()).mapNotNull { cmds.optJSONObject(it) }.sortedBy { it.optLong("id") }
                    .forEach { GuardController.handleCommand(context, it) }
            }
        } catch (e: FamilyApiException) {
            if (e.unlinked) onRemoteUnlinked(context) else FamilyStore.setError(e.message)
        } catch (e: IOException) {
            Log.w(TAG, "부모 상태 확인 실패", e)
        }
    }

    /** 이 폰에서 연결 끊기. 서버에 못 알리면 나중에 다시 알리고, 이 폰의 연결 정보는 바로 지운다 */
    fun unlink(context: Context) {
        val token = FamilyStore.token(context)
        stopServices(context)
        FamilyStore.clear(context)
        if (token != null) {
            try {
                FamilyApi.unlink(token)
            } catch (e: FamilyApiException) {
                if (!e.unlinked) FamilyStore.addPendingUnlink(context, token)
            } catch (e: IOException) {
                Log.w(TAG, "서버 연결 끊기 실패 (나중에 다시 보냄)", e)
                FamilyStore.addPendingUnlink(context, token)
            }
        }
    }

    /** 상대가 끊었거나 서버에서 사라짐 */
    fun onRemoteUnlinked(context: Context) {
        val wasParent = FamilyStore.state.value.role == FamilyRole.PARENT
        stopServices(context)
        FamilyStore.clear(context)
        if (wasParent) FamilyNotify.post(context, 3999, "가족 연결이 끊겼습니다", "이제 위험 알림을 가족에게 보내지 않습니다")
    }

    /**
     * 역할에 맞는 상시 서비스 시작. 앱 화면·부팅·업데이트 직후처럼 시작이 허용될 때 성공한다.
     * 백그라운드라 막히면(Android 12+) 사용자에게 앱을 한 번 열어 달라고 알린다.
     */
    fun ensureServices(context: Context) {
        val app = context.applicationContext
        FamilyStore.ensureLoaded(app)
        val role = FamilyStore.state.value.role
        val cls = when (role) {
            FamilyRole.PARENT -> ParentGuardService::class.java
            FamilyRole.CHILD -> ChildWatchService::class.java
            FamilyRole.NONE -> return
        }
        if (role == FamilyRole.PARENT && ParentGuardService.running) return
        if (role == FamilyRole.CHILD && ChildWatchService.running) return
        try {
            app.startForegroundService(Intent(app, cls))
        } catch (e: IllegalStateException) {
            if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "백그라운드라 서비스 시작 불가", e)
                notifyServiceBlocked(app, role)
            } else {
                throw e
            }
        }
    }

    fun notifyServiceBlocked(context: Context, role: FamilyRole) {
        val text = if (role == FamilyRole.PARENT) {
            "휴대폰이 이 앱을 멈췄습니다. 앱을 한 번 열어야 위험 앱을 가족에게 바로 알릴 수 있습니다."
        } else {
            "휴대폰이 이 앱을 멈췄습니다. 앱을 한 번 열어야 가족 폰 알림을 바로 받을 수 있습니다."
        }
        FamilyNotify.post(context, NOTI_SERVICE_BLOCKED, "피싱앱 검사기를 열어 주세요", text)
    }

    fun stopServices(context: Context) {
        val app = context.applicationContext
        app.stopService(Intent(app, ParentGuardService::class.java))
        app.stopService(Intent(app, ChildWatchService::class.java))
    }

    /**
     * 주기 작업(15분)에서 부름. 부모: 동기화. 자녀: 실시간 연결이 끊겨 있으면 놓친 사건을 받아 알림.
     * 서버에 못 알린 연결 끊기도 다시 보낸다.
     */
    fun periodic(context: Context) {
        FamilyStore.ensureLoaded(context)
        flushPendingUnlinks(context)
        val s = FamilyStore.state.value
        when (s.role) {
            FamilyRole.PARENT -> if (!ParentGuardService.running) {
                // 상시 서비스가 막혀 있을 때만 대신 처리 (돌고 있으면 실시간 감지·3시간 동기화·1분 마감 확인을 서비스가 한다)
                pollParent(context)
                FamilyReporter.sync(context)
                GuardController.checkDeadlines(context)
            }
            FamilyRole.CHILD -> if (!s.live) pollChild(context)
            FamilyRole.NONE -> Unit
        }
        ensureServices(context)
    }

    private fun flushPendingUnlinks(context: Context) {
        FamilyStore.pendingUnlinks(context).forEach { token ->
            try {
                FamilyApi.unlink(token)
                FamilyStore.removePendingUnlink(context, token)
            } catch (e: FamilyApiException) {
                // 이미 사라진 연결이면 할 일 끝
                if (e.unlinked) FamilyStore.removePendingUnlink(context, token)
            } catch (e: IOException) {
                Log.w(TAG, "연결 끊기 재전송 실패", e)
            }
        }
    }

    fun pollChild(context: Context) {
        val token = FamilyStore.token(context) ?: return
        val gen = FamilyStore.generation()
        try {
            val j = FamilyApi.state(token, FamilyStore.lastEventId())
            FamilyStore.applyState(context, j, gen)
            FamilyStore.takeUnnotified(context, gen).forEach {
                FamilyNotify.showEvent(context, it, FamilyStore.state.value.otherName)
            }
        } catch (e: FamilyApiException) {
            if (e.unlinked) onRemoteUnlinked(context) else FamilyStore.setError(e.message)
        } catch (e: IOException) {
            Log.w(TAG, "상태 확인 실패", e)
        }
    }
}
