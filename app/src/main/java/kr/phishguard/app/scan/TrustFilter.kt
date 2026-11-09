package kr.phishguard.app.scan

/** 위험 수준별 개수. 안전앱은 세지 않는다. */
data class SummaryCounts(val high: Int, val medium: Int, val low: Int)

/** 검사 목록의 안전앱·시스템 앱 거르기 (Android 의존 없음). */
object TrustFilter {
    /** 시스템 앱 포함 여부만 반영한 목록. */
    fun systemFiltered(apps: List<AppInfo>, includeSystem: Boolean): List<AppInfo> =
        if (includeSystem) apps else apps.filter { !it.isSystem }

    /** 목록에 보일 앱. 안전앱은 includeTrusted일 때만 포함. */
    fun visibleApps(
        apps: List<AppInfo>,
        trusted: Set<String>,
        includeTrusted: Boolean,
        includeSystem: Boolean,
    ): List<AppInfo> = systemFiltered(apps, includeSystem)
        .filter { includeTrusted || it.packageName !in trusted }

    /** 지금 목록 범위(시스템 앱 포함 여부 반영) 안에 있는 안전앱. */
    fun presentTrusted(apps: List<AppInfo>, trusted: Set<String>, includeSystem: Boolean): List<AppInfo> =
        systemFiltered(apps, includeSystem).filter { it.packageName in trusted }

    /** 요약 개수. 안전앱은 목록에 보이더라도 항상 제외. */
    fun summaryCounts(apps: List<AppInfo>, trusted: Set<String>, includeSystem: Boolean): SummaryCounts {
        val counted = systemFiltered(apps, includeSystem).filter { it.packageName !in trusted }
        return SummaryCounts(
            high = counted.count { it.riskLevel == RiskLevel.HIGH },
            medium = counted.count { it.riskLevel == RiskLevel.MEDIUM },
            low = counted.count { it.riskLevel == RiskLevel.LOW },
        )
    }
}
