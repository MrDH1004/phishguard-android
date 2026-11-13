package kr.phishguard.app.guard

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import kr.phishguard.app.family.FamilyReporter
import kr.phishguard.app.family.FamilyStore
import org.json.JSONObject

/**
 * 부모 폰: 자녀 명령 처리와 "30분 무응답 → 인터넷 자동 차단". IO 스레드에서 호출.
 *
 * 안전장치 (리뷰 H1): 명령은 이 폰이 스스로 위험하다고 올린 "열린 결정 요청"과 requestId·패키지가 모두 맞을 때만 실행한다.
 * 사기범이 자녀 역할로 연결돼도 카카오톡·은행 앱·이 앱을 잠그거나 지우게 만들 수 없다.
 * 화면에 보이는 앱 이름은 명령에 담긴 글자가 아니라 이 폰에서 직접 읽는다.
 */
object GuardController {
    private const val TAG = "PG-GuardController"

    /** 이보다 오래된 명령은 실행하지 않는다 (오랫동안 꺼져 있던 폰이 뒤늦게 잠기지 않게) */
    private const val MAX_COMMAND_AGE_MS = 2L * 60 * 60 * 1000

    fun handleCommand(context: Context, cmd: JSONObject) {
        val id = cmd.optLong("id")
        val gen = FamilyStore.generation()
        if (id <= 0 || !FamilyStore.claimCommand(context, id, gen)) return
        val type = cmd.optString("type")
        val d = cmd.optJSONObject("data") ?: JSONObject()
        val requestId = d.optString("requestId")
        val pkg = d.optString("packageName")
        try {
            val age = System.currentTimeMillis() - cmd.optLong("ts")
            if (cmd.optLong("ts") > 0 && age > MAX_COMMAND_AGE_MS) {
                report(context, type, id, requestId, pkg, pkg, CommandExecutor.Result(false, "너무 오래된 명령이라 실행하지 않았습니다"))
                return
            }
            val open = if (requestId.isEmpty()) null else FamilyStore.claimOpenDecision(context, requestId)
            if (open == null || open.pkg != pkg) {
                Log.w(TAG, "열린 결정 요청과 맞지 않는 명령 무시: $id $type $pkg")
                report(context, type, id, requestId, pkg, pkg, CommandExecutor.Result(false, "이 폰이 요청한 결정이 아니어서 실행하지 않았습니다"))
                return
            }
            val label = labelOf(context, pkg) ?: open.label
            Log.i(TAG, "명령 $id $type $pkg")
            val r = when (type) {
                "lock" -> if (protectedPackage(context, pkg)) refused() else CommandExecutor.lock(context, pkg, label)
                "block" -> if (protectedPackage(context, pkg)) refused() else CommandExecutor.block(context, pkg)
                "ignore" -> CommandExecutor.Result(true, "무시: 아무것도 하지 않음")
                else -> CommandExecutor.Result(false, "알 수 없는 명령")
            }
            report(context, type, id, requestId, pkg, label, r)
        } catch (e: RuntimeException) {
            // 예상 못 한 실패도 자녀에게 알린다 (명령은 이미 처리됨으로 기록됐으므로 다시 오지 않는다)
            Log.e(TAG, "명령 실행 실패 $id $type $pkg", e)
            report(context, type, id, requestId, pkg, pkg, CommandExecutor.Result(false, "실행 중 오류: ${e.javaClass.simpleName}"))
        }
    }

    /** 자녀가 30분 동안 답하지 않은 결정 요청은 그 앱(+메신저)의 인터넷만 끊는다. 잠금은 하지 않는다 */
    fun checkDeadlines(context: Context, now: Long = System.currentTimeMillis()) {
        FamilyStore.openDecisions(context).filter { it.deadline in 1..now }.forEach { candidate ->
            // 서비스와 주기 작업이 동시에 돌아도 한쪽만 처리 (리뷰 M9)
            val d = FamilyStore.claimOpenDecision(context, candidate.requestId) ?: return@forEach
            try {
                val installedTime = installTimeOf(context, d.pkg)
                if (installedTime == null || installedTime != d.firstInstallTime) {
                    Log.i(TAG, "이미 지워졌거나 다시 설치된 앱이라 자동 차단 생략: ${d.pkg}")
                    return@forEach
                }
                Log.i(TAG, "무응답 자동 차단 ${d.pkg}")
                val r = if (protectedPackage(context, d.pkg)) refused() else CommandExecutor.block(context, d.pkg)
                report(context, "auto_block", 0, d.requestId, d.pkg, labelOf(context, d.pkg) ?: d.label, r)
            } catch (e: RuntimeException) {
                Log.e(TAG, "자동 차단 실패 ${d.pkg}", e)
                report(context, "auto_block", 0, d.requestId, d.pkg, d.label, CommandExecutor.Result(false, "자동 차단 중 오류"))
            }
        }
    }

    private fun refused() = CommandExecutor.Result(false, "이 앱이나 시스템 앱은 잠그거나 차단할 수 없습니다")

    /** 이 앱 자신과 시스템 앱은 대상에서 막는다 */
    private fun protectedPackage(context: Context, pkg: String): Boolean {
        if (pkg == context.packageName) return true
        return try {
            val ai = context.packageManager.getApplicationInfo(pkg, 0)
            (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun labelOf(context: Context, pkg: String): String? = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun installTimeOf(context: Context, pkg: String): Long? = try {
        context.packageManager.getPackageInfo(pkg, 0).firstInstallTime
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun report(context: Context, type: String, id: Long, requestId: String, pkg: String, label: String, r: CommandExecutor.Result) {
        FamilyReporter.postResult(
            context,
            JSONObject()
                .put("commandId", id)
                .put("requestId", requestId)
                .put("type", type)
                .put("ok", r.ok)
                .put("message", r.message)
                .put("packageName", pkg)
                .put("label", label)
                .put("lockUntil", r.lockUntil),
        )
    }
}
