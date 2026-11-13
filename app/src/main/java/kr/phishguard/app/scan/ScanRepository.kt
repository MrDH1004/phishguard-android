package kr.phishguard.app.scan

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kr.phishguard.app.history.HistoryTracker
import kr.phishguard.app.net.UidUsage
import kr.phishguard.app.net.UsageStats

/** 앱 조사 결과 공유 저장소. */
object ScanRepository {
    private const val TAG = "ScanRepository"

    // 설치 출처 판정은 InstallSourceClassifier: 설치자 없음·기본 패키지 설치 프로그램 = UNKNOWN(+30, APK 직접 설치),
    // Play 스토어 외 설치자(삼성/원스토어/KT/LG 스토어 포함) = OTHER_STORE(+5).
    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps

    /** 현재 기본 전화/문자 앱 (검사 머리말 표시용). 검사 전에는 null. */
    data class DefaultApps(
        val dialerPackage: String?,
        val dialerLabel: String?,
        val smsPackage: String?,
        val smsLabel: String?,
    )

    private val _defaults = MutableStateFlow<DefaultApps?>(null)
    val defaults: StateFlow<DefaultApps?> = _defaults

    /** 마지막 전체 검사에서 모은 시스템 앱 서명 SHA-256 집합. 앱 하나 다시 조사할 때 재사용. */
    @Volatile
    private var systemSignersCache: Set<String>? = null

    /** 마지막 전체 검사에서 모은 외부 설치(UNKNOWN) 앱의 설치 시각. 앱 하나 다시 조사할 때 재사용. */
    @Volatile
    private var unknownInstallTimesCache: Map<String, Long> = emptyMap()

    /** 마지막 전체 검사에서 모은 uid별 최근 30일 데이터 사용량 (권한 없으면 빈 맵) */
    @Volatile
    private var usageCache: Map<Int, UidUsage>? = null

    /** 기기 앱 전체를 조사해 결과를 갱신하고 반환 (IO 스레드에서 호출) */
    fun scan(context: Context, onProgress: ((done: Int, total: Int) -> Unit)? = null): List<AppInfo> {
        val appContext = context.applicationContext
        val pm = appContext.packageManager
        val packages = try {
            getInstalledPackagesCompat(pm)
        } catch (e: RuntimeException) {
            Log.e(TAG, "getInstalledPackages failed", e)
            emptyList()
        }
        val systemSigners = systemSignersOf(packages)
        systemSignersCache = systemSigners
        val now = System.currentTimeMillis()
        unknownInstallTimesCache = unknownInstallTimesOf(pm, packages, appContext.packageName)
        usageCache = queryUsage(appContext, now)
        val env = ScanEnv.create(appContext, systemSigners, unknownInstallTimesCache, usageCache)
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
        _defaults.value = defaultAppsOf(pm, env.roles)
        HistoryTracker.sync(appContext, result)
        return result
    }

    /** 패키지 하나만 다시 조사해 목록에 반영. 설치돼 있지 않으면 목록에서 제거하고 null 반환. */
    fun rescanPackage(context: Context, packageName: String): AppInfo? {
        val appContext = context.applicationContext
        val pm = appContext.packageManager
        val info: AppInfo? = try {
            val pi = getPackageInfoCompat(pm, packageName, PackageManager.GET_PERMISSIONS or RoleDetector.signingFlag)
            val systemSigners = systemSignersCache ?: systemSignersOf(getInstalledPackagesCompat(pm)).also {
                systemSignersCache = it
            }
            val usage = usageCache ?: queryUsage(appContext, System.currentTimeMillis()).also { usageCache = it }
            val env = ScanEnv.create(appContext, systemSigners, unknownInstallTimesCache, usage)
            _defaults.value = defaultAppsOf(pm, env.roles)
            buildAppInfo(appContext, pm, pi, env, System.currentTimeMillis())
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

    private const val USAGE_WINDOW_MS = 30L * RiskRules.DAY_MS

    /** 최근 30일 uid별 데이터 사용량. 사용 정보 접근 권한이 없으면 null (규칙에서 판단하지 않음) */
    private fun queryUsage(context: Context, now: Long): Map<Int, UidUsage>? {
        if (!UsageStats.hasAccess(context)) return null
        return UsageStats.query(context, now - USAGE_WINDOW_MS)
    }

    /** 시스템 앱·이 앱을 뺀, APK 직접 설치(UNKNOWN) 앱의 패키지 → 최초 설치 시각 */
    private fun unknownInstallTimesOf(pm: PackageManager, packages: List<PackageInfo>, selfPackage: String): Map<String, Long> {
        val out = HashMap<String, Long>()
        packages.forEach { pi ->
            val ai = pi.applicationInfo ?: return@forEach
            if (pi.packageName == selfPackage) return@forEach
            val isSystem = (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
            if (InstallSourceClassifier.classify(isSystem, installerOf(pm, pi.packageName)) == InstallSource.UNKNOWN) {
                out[pi.packageName] = pi.firstInstallTime
            }
        }
        return out
    }

    /** 미리 설치된 시스템 앱(FLAG_SYSTEM)의 서명 인증서 SHA-256 집합 */
    private fun systemSignersOf(packages: List<PackageInfo>): Set<String> {
        val out = HashSet<String>()
        packages.forEach { pi ->
            val ai = pi.applicationInfo ?: return@forEach
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) out.addAll(RoleDetector.signersOf(pi).digests)
        }
        return out
    }

    private fun defaultAppsOf(pm: PackageManager, roles: RoleDetector.Snapshot): DefaultApps {
        fun labelOf(pkg: String?): String? {
            if (pkg == null) return null
            return find(pkg)?.label ?: try {
                pm.getApplicationLabel(getApplicationInfoCompat(pm, pkg)).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "default app label failed: $pkg", e)
                pkg
            }
        }
        return DefaultApps(roles.defaultDialer, labelOf(roles.defaultDialer), roles.defaultSms, labelOf(roles.defaultSms))
    }

    private fun getApplicationInfoCompat(pm: PackageManager, pkg: String): ApplicationInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(pkg, 0)
        }

    private class A11yFacts(val watched: Set<String>, val canReadAndGesture: Boolean)

    /** API 30+ 설치 요청자, API 33+ 스토어 신고 여부. 낮은 API나 실패 시 (null, false) */
    private fun installChainOf(pm: PackageManager, pkg: String): Pair<String?, Boolean> {
        if (Build.VERSION.SDK_INT < 30) return null to false
        return try {
            val info = pm.getInstallSourceInfo(pkg)
            val store = Build.VERSION.SDK_INT >= 33 && info.packageSource == PackageInstaller.PACKAGE_SOURCE_STORE
            info.initiatingPackageName to store
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "install source lookup failed: $pkg", e)
            null to false
        } catch (e: RuntimeException) {
            Log.w(TAG, "install source lookup failed: $pkg", e)
            null to false
        }
    }

    private class ScanEnv(
        val launcherPackages: Set<String>,
        val activeAdmins: Set<String>,
        val accessibilitySetting: String?,
        val notificationSetting: String?,
        val appOps: AppOpsManager?,
        val selfPackage: String,
        val roles: RoleDetector.Snapshot,
        val systemSigners: Set<String>,
        val unknownInstallTimes: Map<String, Long>,
        /** 사용 정보 접근 권한이 없으면 null */
        val usage: Map<Int, UidUsage>?,
        /** 활성 접근성 서비스의 패키지 → 감시 대상·능력 */
        val a11yServices: Map<String, A11yFacts>,
        /** NEW_OUTGOING_CALL 방송을 받는 리시버를 선언한 패키지 */
        val outgoingCallReceivers: Set<String>,
    ) {
        companion object {
            fun create(
                context: Context,
                systemSigners: Set<String>,
                unknownInstallTimes: Map<String, Long>,
                usage: Map<Int, UidUsage>?,
            ): ScanEnv {
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
                    roles = RoleDetector.snapshot(context),
                    systemSigners = systemSigners,
                    unknownInstallTimes = unknownInstallTimes,
                    usage = usage,
                    a11yServices = a11yServicesOf(context),
                    outgoingCallReceivers = outgoingCallReceiversOf(pm),
                )
            }

            /**
             * 활성 접근성 서비스 목록. getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK)는 권한 없이 부를 수 있다.
             * packageNames가 null이면 "모든 앱"이라 감시 대상 집합에 넣지 않는다.
             */
            private fun a11yServicesOf(context: Context): Map<String, A11yFacts> = try {
                val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                val list = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).orEmpty()
                val readAndGesture = AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT or
                    AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES
                val out = HashMap<String, A11yFacts>()
                list.forEach { info ->
                    val pkg = info.resolveInfo?.serviceInfo?.packageName ?: return@forEach
                    val prev = out[pkg] ?: A11yFacts(emptySet(), false)
                    out[pkg] = A11yFacts(
                        watched = prev.watched + info.packageNames.orEmpty().toSet(),
                        canReadAndGesture = prev.canReadAndGesture || (info.capabilities and readAndGesture) == readAndGesture,
                    )
                }
                out
            } catch (e: RuntimeException) {
                Log.w(TAG, "accessibility service list failed", e)
                emptyMap()
            }

            @Suppress("DEPRECATION") // ACTION_NEW_OUTGOING_CALL: API 29에서 폐기됐지만 악성앱이 여전히 선언한다
            private fun outgoingCallReceiversOf(pm: PackageManager): Set<String> = try {
                val intent = Intent(Intent.ACTION_NEW_OUTGOING_CALL)
                val list = if (Build.VERSION.SDK_INT >= 33) {
                    pm.queryBroadcastReceivers(intent, PackageManager.ResolveInfoFlags.of(0L))
                } else {
                    pm.queryBroadcastReceivers(intent, 0)
                }
                list.mapNotNull { it.activityInfo?.packageName }.toSet()
            } catch (e: RuntimeException) {
                Log.w(TAG, "outgoing call receiver query failed", e)
                emptySet()
            }

            private fun secureString(context: Context, key: String): String? = try {
                Settings.Secure.getString(context.contentResolver, key)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Settings.Secure read failed: $key", e)
                null
            }
        }
    }

    private fun getInstalledPackagesCompat(pm: PackageManager): List<PackageInfo> {
        val flags = PackageManager.GET_PERMISSIONS or RoleDetector.signingFlag
        return if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags)
        }
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
        val source = InstallSourceClassifier.classify(isSystem, installer)
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

        var fgsMediaProjection = false
        var fgsPhoneCall = false
        // 서비스/리시버 선언으로 접근성·기기관리자·알림접근 분류 추가
        try {
            val full = getPackageInfoCompat(pm, pkg, PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS)
            val declared = LinkedHashMap<String, Boolean>()
            full.services?.forEach { s ->
                if (Build.VERSION.SDK_INT >= 29) {
                    val type = s.foregroundServiceType
                    if ((type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) != 0) fgsMediaProjection = true
                    if ((type and ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL) != 0) fgsPhoneCall = true
                }
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
        val declaresAccessibility = perms.any { it.category == PermCategory.ACCESSIBILITY }
        val isDefaultDialer = pkg == env.roles.defaultDialer
        val isDefaultSms = pkg == env.roles.defaultSms
        val declaresCallRedirection = pkg in env.roles.callRedirection
        val declaresCallScreening = pkg in env.roles.callScreening
        val declaresInCall = pkg in env.roles.inCall
        val signers = RoleDetector.signersOf(pi)
        val vendorSigned = RoleDetector.isSignedBy(signers, env.systemSigners)
        val nearbyUnknown = if (source == InstallSource.UNKNOWN && !isSelf) {
            ThreatIntel.countNearby(
                pi.firstInstallTime,
                env.unknownInstallTimes.filterKeys { it != pkg }.values.toList() + pi.firstInstallTime,
            )
        } else {
            0
        }
        val uidUsage = env.usage?.let { u -> u[uid] ?: UidUsage(uid, 0, 0, 0, 0) }
        val (initiating, sourceStore) = installChainOf(pm, pkg)
        val a11y = env.a11yServices[pkg]
        val requestedSet = requested.toSet()
        val baseInput = RiskInput(
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
            declaresAccessibility = declaresAccessibility,
            isDefaultDialer = isDefaultDialer,
            isDefaultSms = isDefaultSms,
            declaresCallRedirection = declaresCallRedirection,
            declaresCallScreening = declaresCallScreening,
            declaresInCallService = declaresInCall,
            vendorSigned = vendorSigned,
            signerDigests = signers.digests,
            signerSubjects = signers.subjects,
            nearbyUnknownInstalls = nearbyUnknown,
            uploadBytes30d = uidUsage?.totalTx,
            downloadBytes30d = uidUsage?.totalRx,
            a11yWatchedPackages = a11y?.watched ?: emptySet(),
            a11yCanReadAndGesture = a11y?.canReadAndGesture ?: false,
            installerPackage = installer,
            initiatingPackage = initiating,
            // env.unknownInstallTimes = 시스템 앱·이 앱을 뺀 외부 설치 앱 (앱 하나 다시 조사할 때는 마지막 전체 검사 값)
            initiatorIsSideloaded = initiating != null && initiating != pkg && initiating in env.unknownInstallTimes,
            packageSourceStore = sourceStore,
            requestsProcessOutgoingCalls = "android.permission.PROCESS_OUTGOING_CALLS" in requestedSet,
            handlesNewOutgoingCall = pkg in env.outgoingCallReceivers,
            writeCallLogGranted = perms.any { it.name == "android.permission.WRITE_CALL_LOG" && it.granted },
            requestsDeletePackages = "android.permission.REQUEST_DELETE_PACKAGES" in requestedSet,
            requestsSmsRead = "android.permission.READ_SMS" in requestedSet ||
                "android.permission.RECEIVE_SMS" in requestedSet,
            declaresNotificationListener = perms.any { it.category == PermCategory.NOTIFICATION_LISTENER },
            fgsMediaProjection = fgsMediaProjection,
            fgsPhoneCall = fgsPhoneCall,
        )
        // APK 파일 구조 검사는 IO 비용이 있으므로 신뢰할 수 없는 출처에만 (시간·항목 수 상한은 ApkInspector)
        val input = if (RuleContext(baseInput, now).untrustedOrigin) {
            baseInput.copy(apkStructure = ApkInspector.inspect(ai?.sourceDir) { msg, e -> Log.w(TAG, msg, e) })
        } else {
            baseInput
        }
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
            isDefaultDialer = isDefaultDialer,
            isDefaultSms = isDefaultSms,
            declaresCallRedirection = declaresCallRedirection,
            declaresCallScreening = declaresCallScreening,
            declaresInCallService = declaresInCall,
            vendorSigned = vendorSigned,
        )
    }
}
