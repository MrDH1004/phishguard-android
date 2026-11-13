package kr.phishguard.app.guard

import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.util.Log
import kr.phishguard.app.family.FamilyStore
import kr.phishguard.app.net.BlockList
import kr.phishguard.app.net.MonitorVpnService
import kr.phishguard.app.remove.EvidenceExporter
import kr.phishguard.app.scan.ScanRepository
import java.io.IOException

/** 부모 폰: 자녀가 고른 조치를 실행한다. IO 스레드에서 호출. */
object CommandExecutor {
    private const val TAG = "PG-CommandExec"

    data class Result(val ok: Boolean, val message: String, val lockUntil: Long = 0)

    private fun installed(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** 의심 앱 + 설치된 메신저의 인터넷 차단. VPN 동의가 없으면 차단 목록만 저장하고 알려 준다 */
    fun block(context: Context, pkg: String): Result {
        val targets = listOf(pkg) + GuardPolicy.MESSENGER_PACKAGES.filter { installed(context, it) }
        BlockList.load(context)
        targets.forEach { BlockList.set(context, it, true) }
        val names = targets.joinToString(", ") { ScanRepository.find(it)?.label ?: it }
        if (VpnService.prepare(context) != null) {
            return Result(false, "차단 목록에 넣었지만 VPN 동의가 없어 인터넷을 끊지 못했습니다. 부모 폰에서 '인터넷 차단 준비'를 한 번 눌러야 합니다. ($names)")
        }
        // 차단 VPN이 이미 돌고 있으면 차단 목록 변경을 바로 반영하므로 다시 시작할 필요 없다
        if (MonitorVpnService.running.value) return Result(true, "인터넷 차단: $names")
        repeat(2) { attempt ->
            try {
                MonitorVpnService.start(context)
                return Result(true, "인터넷 차단: $names")
            } catch (e: IllegalStateException) {
                // 백그라운드 시작 제한 (잠금 화면이 보이거나 배터리 최적화가 꺼져 있으면 허용됨). 창이 막 그려진 직후일 수 있어 한 번 더
                Log.e(TAG, "차단 VPN 시작 실패 (시도 ${attempt + 1})", e)
                if (attempt == 0) Thread.sleep(700)
            }
        }
        return Result(false, "차단 목록에 넣었지만 휴대폰이 차단 시작을 막았습니다. 부모 폰에서 앱을 한 번 열면 차단됩니다. ($names)")
    }

    /**
     * 1시간 잠금: 잠금 화면을 먼저 띄운 뒤(이래야 백그라운드에서 차단 VPN을 시작할 수 있다) 차단 → 증거 저장.
     */
    fun lock(context: Context, pkg: String, label: String): Result {
        val now = System.currentTimeMillis()
        val until = now + GuardPolicy.LOCK_DURATION_MS
        val prev = LockStore.active(context, now)
        // 잠금 중에 또 잠그면 정리 목록을 합치고 화면 이름을 새 앱으로 바꾼다 (리뷰 L1)
        val cleanup = ((prev?.cleanup ?: emptyList()) + listOf(pkg) + GuardPolicy.MESSENGER_PACKAGES)
            .distinct().filter { installed(context, it) }
        val byName = FamilyStore.state.value.otherName ?: "가족"
        LockStore.save(context, LockStore.Lock(maxOf(until, prev?.until ?: 0), pkg, label, byName, cleanup, prev?.evidencePath))
        // 잠금 창이 실제로 붙은 뒤에 VPN을 시작한다 (Android 15: 보이는 겹침 창이 있어야 백그라운드 시작 허용)
        val shown = LockOverlay.show(context)
        val blocked = block(context, pkg)
        val evidence = saveEvidence(context, pkg)
        LockStore.load(context)?.let { LockStore.save(context, it.copy(evidencePath = evidence)) }
        val parts = mutableListOf<String>()
        parts += if (shown) "1시간 잠금" else "잠금 실패: 부모 폰에 '다른 앱 위에 그리기' 권한이 없습니다"
        parts += blocked.message
        parts += if (evidence != null) "증거 저장: $evidence" else "증거 저장 실패"
        return Result(shown && blocked.ok, parts.joinToString(" · "), if (shown) until else 0)
    }

    private fun saveEvidence(context: Context, pkg: String): String? {
        val app = ScanRepository.find(pkg) ?: ScanRepository.rescanPackage(context, pkg) ?: return null
        return try {
            EvidenceExporter.export(context, app).savedTo
        } catch (e: IOException) {
            Log.e(TAG, "증거 저장 실패: $pkg", e)
            null
        } catch (e: SecurityException) {
            Log.e(TAG, "증거 저장 권한 없음: $pkg", e)
            null
        }
    }
}
