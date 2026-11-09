package kr.phishguard.app.scan

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 앱 조사 결과 공유 저장소. */
object ScanRepository {
    private const val TAG = "ScanRepository"

    // Play 스토어 외 설치자(삼성/원스토어/KT/LG 스토어 포함)는 모두 OTHER_STORE(+5)이며
    // 설치자 정보가 없는 UNKNOWN(+30)보다 낮은 위험으로 취급한다.
    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps

    /** 기기 앱 전체를 조사해 결과를 갱신하고 반환 (IO 스레드에서 호출) */
    fun scan(context: Context, onProgress: ((done: Int, total: Int) -> Unit)? = null): List<AppInfo> {
        val appContext = context.applicationContext
        val pm = appContext.packageManager
        val env = ScanEnv.create(appContext)
        val packages = try {
            getInstalledPackagesCompat(pm)
        } catch (e: RuntimeException) {
            Log.e(TAG, "getInstalledPackages failed", e)
            emptyList()
        }
        val now = System.currentTimeMillis()
        val result = ArrayList<AppInfo>(packages.size)
        packages.forEachIndexed { index, pi ->
            try {
                result.add(buildAppInfo(appContext, pm, pi, env, now))
            } catch (e: Exception) {
                Log.w(TAG, "scan failed for ${pi.packageName}", e)
            }
            onProgress?.invoke(index + 1, packages.size)
        }
        _apps.value = result
        return result
    }

    /** 패키지 하나만 다시 조사해 목록에 반영. 설치돼 있지 않으면 목록에서 제거하고 null 반환. */
    fun rescanPackage(context: Context, packageName: String): AppInfo? {
        val appContext = context.applicationContext
        val pm = appContext.packageManager
        val info: AppInfo? = try {
            val pi = getPackageInfoCompat(pm, packageName, PackageManager.GET_PERMISSIONS)
            buildAppInfo(appContext, pm, pi, ScanEnv.create(appContext), System.currentTimeMillis())
        } catch (e: PackageManager.NameNotFoundException) {
            Log.i(TAG, "package not installed: $packageName")
            null
        } catch (e: Exception) {
            Log.w(TAG, "rescan failed for $packageName", e)
            null
        }
        val rest = _apps.value.filter { it.packageName != packageName }
        _apps.value = if (info != null) rest + info else rest
        return info
    }

    fun find(packageName: String): AppInfo? = _apps.value.firstOrNull { it.packageName == packageName }

    // ---- 내부 구현 ----

    private class ScanEnv(
        val launcherPackages: Set<String>,
        val activeAdmins: Set<String>,
        val accessibilitySetting: String?,
        val notificationSetting: String?,
        val appOps: AppOpsManager?,
        val selfPackage: String,
    ) {
        companion object {
            fun create(context: Context): ScanEnv {
                val pm = context.packageManager
                val launcher = try {
                    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    val list = if (Build.VERSION.SDK_INT >= 33) {
                        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.queryIntentActivities(intent, 0)
                    }
                    list.map { it.activityInfo.packageName }.toSet()
                } catch (e: RuntimeException) {
                    Log.w(TAG, "launcher query failed", e)
                    emptySet()
                }
                val admins = try {
                    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                    dpm?.activeAdmins?.map { it.packageName }?.toSet() ?: emptySet()
                } catch (e: RuntimeException) {
                    Log.w(TAG, "active admins query failed", e)
                    emptySet()
                }
                return ScanEnv(
                    launcherPackages = launcher,
                    activeAdmins = admins,
                    accessibilitySetting = secureString(context, "enabled_accessibility_services"),
                    notificationSetting = secureString(context, "enabled_notification_listeners"),
                    appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager,
                    selfPackage = context.packageName,
                )
            }

            private fun secureString(context: Context, key: String): String? = try {
                Settings.Secure.getString(context.contentResolver, key)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Settings.Secure read failed: $key", e)
                null
            }
        }
    }

    private fun getInstalledPackagesCompat(pm: PackageManager): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }

    private fun getPackageInfoCompat(pm: PackageManager, pkg: String, flags: Int): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, flags)
        }

    private fun installerOf(pm: PackageManager, pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(pkg)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        Log.w(TAG, "installer lookup failed: $pkg", e)
        null
    }

    private fun checkOverlayOp(env: ScanEnv, uid: Int, pkg: String): Int {
        val ops = env.appOps ?: return AppOpsManager.MODE_DEFAULT
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, uid, pkg)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, uid, pkg)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "overlay appop failed: $pkg", e)
            AppOpsManager.MODE_DEFAULT
        }
    }

    /** 알 수 없는 앱 설치 허용 여부. 조회 실패 시 기존 동작(요청만으로 true)으로 대체 */
    private fun isInstallOpGranted(env: ScanEnv, uid: Int, pkg: String): Boolean {
        val ops = env.appOps ?: return true
        return try {
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                ops.unsafeCheckOpNoThrow("android:request_install_packages", uid, pkg)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow("android:request_install_packages", uid, pkg)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: RuntimeException) {
            Log.w(TAG, "install appop failed: $pkg", e)
            true
        }
    }

    private fun buildAppInfo(
        context: Context,
        pm: PackageManager,
        pi: PackageInfo,
        env: ScanEnv,
        now: Long,
    ): AppInfo {
        val pkg = pi.packageName
        val ai = pi.applicationInfo
        val isSystem = ai != null &&
            (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        val label = try {
            ai?.let { pm.getApplicationLabel(it).toString() } ?: pkg
        } catch (e: RuntimeException) {
            Log.w(TAG, "label failed: $pkg", e)
            pkg
        }
        val installer = installerOf(pm, pkg)
        val source = when {
            isSystem -> InstallSource.SYSTEM
            installer == null -> InstallSource.UNKNOWN
            installer == "com.android.vending" -> InstallSource.PLAY_STORE
            else -> InstallSource.OTHER_STORE
        }
        val uid = ai?.uid ?: -1
        val hasLauncher = pkg in env.launcherPackages

        val requested = pi.requestedPermissions ?: emptyArray()
        val reqFlags = pi.requestedPermissionsFlags
        val perms = ArrayList<PermissionInfoItem>()
        requested.forEachIndexed { i, name ->
            val flagGranted = reqFlags != null && i < reqFlags.size &&
                (reqFlags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            val category = PermissionMap.categoryOf(name)
            val granted = when (category) {
                PermCategory.OVERLAY -> {
                    val mode = checkOverlayOp(env, uid, pkg)
                    mode == AppOpsManager.MODE_ALLOWED || (mode == AppOpsManager.MODE_DEFAULT && flagGranted)
                }
                PermCategory.INSTALL_PACKAGES -> isInstallOpGranted(env, uid, pkg)
                else -> flagGranted
            }
            perms.add(PermissionInfoItem(name, category, granted))
        }

        val accessibilityEnabled = PermissionMap.settingListContains(env.accessibilitySetting, pkg)
        val notificationEnabled = PermissionMap.settingListContains(env.notificationSetting, pkg)
        val adminActive = pkg in env.activeAdmins

        // 서비스/리시버 선언으로 접근성·기기관리자·알림접근 분류 추가
        try {
            val full = getPackageInfoCompat(pm, pkg, PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS)
            val declared = LinkedHashMap<String, Boolean>()
            full.services?.forEach { s ->
                when (s.permission) {
                    "android.permission.BIND_ACCESSIBILITY_SERVICE" ->
                        declared["android.permission.BIND_ACCESSIBILITY_SERVICE"] = accessibilityEnabled
                    "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" ->
                        declared["android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"] = notificationEnabled
                }
            }
            full.receivers?.forEach { r ->
                if (r.permission == "android.permission.BIND_DEVICE_ADMIN") {
                    declared["android.permission.BIND_DEVICE_ADMIN"] = adminActive
                }
            }
            declared.forEach { (name, granted) ->
                if (perms.none { it.name == name }) {
                    perms.add(PermissionInfoItem(name, PermissionMap.categoryOf(name), granted))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "component scan failed: $pkg", e)
        }

        val grantedCategories = perms.filter { it.granted }.mapNotNull { it.category }.toSet()
        val isSelf = pkg == env.selfPackage
        val input = RiskInput(
            packageName = pkg,
            label = label,
            installSource = source,
            isSystem = isSystem,
            hasLauncherIcon = hasLauncher,
            firstInstallTime = pi.firstInstallTime,
            grantedCategories = grantedCategories,
            isActiveDeviceAdmin = adminActive,
            hasEnabledAccessibility = accessibilityEnabled,
            hasEnabledNotificationListener = notificationEnabled,
            isSelf = isSelf,
        )
        val risk = RiskScorer.score(input, now)

        return AppInfo(
            packageName = pkg,
            label = label,
            uid = uid,
            versionName = pi.versionName,
            firstInstallTime = pi.firstInstallTime,
            lastUpdateTime = pi.lastUpdateTime,
            installerPackage = installer,
            installSource = source,
            hasLauncherIcon = hasLauncher,
            isSystem = isSystem,
            sourceDir = ai?.sourceDir,
            splitSourceDirs = ai?.splitSourceDirs?.toList() ?: emptyList(),
            permissions = perms,
            isActiveDeviceAdmin = adminActive,
            hasEnabledAccessibility = accessibilityEnabled,
            hasEnabledNotificationListener = notificationEnabled,
            riskScore = risk.score,
            riskLevel = risk.level,
            matchedRules = risk.matchedRules,
        )
    }
}
