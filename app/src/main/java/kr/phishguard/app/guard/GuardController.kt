package kr.phishguard.app.guard

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import kr.phishguard.app.family.FamilyPolicy
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

    /** 자녀의 삭제 요청. 결정 요청 번호 없이 온다 (v1.3.x 부모 폰은 "요청한 결정이 아님"으로 무시) */
    private const val TYPE_UNINSTALL = FamilyPolicy.TYPE_UNINSTALL
    private const val TYPE_SUSPEND = FamilyPolicy.TYPE_SUSPEND
    private const val TYPE_UNSUSPEND = FamilyPolicy.TYPE_UNSUSPEND
    private const val TYPE_RELEASE_OWNER = FamilyPolicy.TYPE_RELEASE_OWNER

    fun handleCommand(context: Context, cmd: JSONObject) {
        val id = cmd.optLong("id")
        val gen = FamilyStore.generation()
        if (id <= 0 || !FamilyStore.claimCommand(context, id, gen)) return
        val type = cmd.optString("type")
        val d = cmd.optJSONObject("data") ?: JSONObject()
        val requestId = d.optString("requestId")
        val pkg = d.optString("packageName")
        try {
            when (type) {
                TYPE_UNINSTALL -> {
                    handleUninstall(context, id, pkg, cmd.optLong("ts"))
                    return
                }
                TYPE_SUSPEND, TYPE_UNSUSPEND -> {
                    handleSuspend(context, id, type, pkg, cmd.optLong("ts"))
                    return
                }
                TYPE_RELEASE_OWNER -> {
                    handleReleaseOwner(context, id, cmd.optLong("ts"))
                    return
                }
            }
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
                Log.i(TAG, "무응답 자동 조치 ${d.pkg}")
                val r = if (protectedPackage(context, d.pkg)) {
                    refused()
                } else {
                    // 기본은 인터넷 차단, 기기 소유자 모드면 앱 정지까지 (GuardPolicy.autoActions)
                    val results = GuardPolicy.autoActions(OwnerMode.isOwner(context)).map { a ->
                        when (a) {
                            GuardPolicy.AutoAction.BLOCK -> CommandExecutor.block(context, d.pkg)
                            GuardPolicy.AutoAction.SUSPEND -> OwnerMode.setSuspended(context, d.pkg, true)
                                .let { CommandExecutor.Result(it.ok, it.message) }
                        }
                    }
                    CommandExecutor.Result(results.all { it.ok }, results.joinToString(" · ") { it.message })
                }
                report(context, "auto_block", 0, d.requestId, d.pkg, labelOf(context, d.pkg) ?: d.label, r)
            } catch (e: RuntimeException) {
                Log.e(TAG, "자동 차단 실패 ${d.pkg}", e)
                report(context, "auto_block", 0, d.requestId, d.pkg, d.label, CommandExecutor.Result(false, "자동 차단 중 오류"))
            }
        }
    }

    /**
     * 자녀의 삭제 요청. 열린 결정 요청이 없어도 되지만 이 폰이 위험하다고 알린 앱이어야 한다 ([GuardPolicy.checkUninstall]).
     * 결과는 단계별로 알린다: 창을 띄움(prompt) → 2분 안에 삭제됨(removed) / 안 됨(not_removed).
     * 삭제되면 그 앱의 열린 결정 요청도 닫아 자녀 화면의 결정 카드가 사라지게 한다.
     */
    private fun handleUninstall(context: Context, id: Long, pkg: String, ts: Long) {
        val fit = installTimeOf(context, pkg)
        val reported = reportedNow(context, pkg, fit)
        val owner = OwnerMode.isOwner(context)
        val check = GuardPolicy.checkUninstall(
            pkg = pkg,
            selfPackage = context.packageName,
            installed = fit != null,
            system = fit != null && isSystemApp(context, pkg),
            reported = reported,
            ageMs = if (ts > 0) System.currentTimeMillis() - ts else 0,
            // 기기 소유자 모드는 확인 창을 띄우지 않으므로 겹침 권한이 필요 없다
            canDraw = owner || LockOverlay.canDraw(context),
        )
        val label = labelOf(context, pkg) ?: pkg
        Log.i(TAG, "삭제 요청 $id $pkg → $check (소유자=$owner)")
        when (check) {
            GuardPolicy.UninstallCheck.RUN -> Unit
            GuardPolicy.UninstallCheck.ALREADY_REMOVED -> {
                reportUninstall(context, id, pkg, label, CommandExecutor.Result(true, check.message, stage = "removed"))
                return
            }
            else -> {
                reportUninstall(context, id, pkg, label, CommandExecutor.Result(false, check.message, stage = "refused"))
                return
            }
        }
        if (owner) {
            // 지우기 전에 증거부터 저장 (지우면 APK·권한 정보가 사라진다)
            val evidence = CommandExecutor.saveEvidence(context, pkg)
            val o = OwnerMode.silentUninstall(context, pkg)
            val gone = o.ok || installTimeOf(context, pkg) == null
            val msg = o.message + (if (evidence != null) " · 증거 저장: $evidence" else " · 증거 저장 실패")
            reportUninstall(context, id, pkg, label, CommandExecutor.Result(gone, msg, stage = if (gone) "removed" else "not_removed"))
            return
        }
        val prompt = CommandExecutor.uninstall(context, pkg) { final -> reportUninstall(context, id, pkg, label, final) }
        report(context, TYPE_UNINSTALL, id, "", pkg, label, prompt)
    }

    /** 이 폰이 지금 설치본 기준으로 자녀에게 위험하다고 알린 앱인지 (알림·결정 요청·열린 결정) */
    private fun reportedNow(context: Context, pkg: String, fit: Long?): Boolean = fit != null && (
        FamilyStore.reportedLevel(context, pkg, fit) != null ||
            FamilyStore.decisionRequested(context, pkg, fit) ||
            FamilyStore.openDecisions(context).any { it.pkg == pkg }
        )

    /** 기기 소유자 모드: 앱 정지/해제 ([GuardPolicy.checkSuspend]) */
    private fun handleSuspend(context: Context, id: Long, type: String, pkg: String, ts: Long) {
        val fit = installTimeOf(context, pkg)
        val suspend = type == TYPE_SUSPEND
        val check = GuardPolicy.checkSuspend(
            pkg = pkg,
            selfPackage = context.packageName,
            installed = fit != null,
            system = fit != null && isSystemApp(context, pkg),
            reported = reportedNow(context, pkg, fit),
            ageMs = if (ts > 0) System.currentTimeMillis() - ts else 0,
            owner = OwnerMode.isOwner(context),
            suspend = suspend,
        )
        val label = labelOf(context, pkg) ?: pkg
        Log.i(TAG, "정지 명령 $id $type $pkg → $check")
        val r = if (check == GuardPolicy.UninstallCheck.RUN) {
            OwnerMode.setSuspended(context, pkg, suspend).let { CommandExecutor.Result(it.ok, it.message) }
        } else {
            CommandExecutor.Result(check.ok, check.message)
        }
        report(context, type, id, "", pkg, label, r)
    }

    /** 자녀가 승인한 기기 소유자 모드 해제 (부모 폰에서 PIN 없이 스스로 풀 수 없게 자녀 명령으로만) */
    private fun handleReleaseOwner(context: Context, id: Long, ts: Long) {
        val r = if (ts > 0 && System.currentTimeMillis() - ts > GuardPolicy.UNINSTALL_MAX_AGE_MS) {
            CommandExecutor.Result(false, "너무 오래된 명령이라 실행하지 않았습니다")
        } else {
            OwnerMode.release(context).let { CommandExecutor.Result(it.ok, it.message) }
        }
        report(context, TYPE_RELEASE_OWNER, id, "", "", "", r)
    }

    /** 삭제됐으면 그 앱의 열린 결정 요청을 닫고 같은 requestId로 알린다 (자녀 화면의 결정 카드가 끝난 것으로 보임) */
    private fun reportUninstall(context: Context, id: Long, pkg: String, label: String, r: CommandExecutor.Result) {
        val requestId = if (r.stage == "removed") {
            FamilyStore.openDecisions(context).filter { it.pkg == pkg }
                .mapNotNull { FamilyStore.claimOpenDecision(context, it.requestId) }.firstOrNull()?.requestId.orEmpty()
        } else {
            ""
        }
        report(context, TYPE_UNINSTALL, id, requestId, pkg, label, r)
    }

    private fun isSystemApp(context: Context, pkg: String): Boolean = try {
        val ai = context.packageManager.getApplicationInfo(pkg, 0)
        (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    } catch (e: PackageManager.NameNotFoundException) {
        false
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
                .put("lockUntil", r.lockUntil)
                .apply { if (r.stage.isNotEmpty()) put("stage", r.stage) },
        )
    }
}
