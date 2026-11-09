package kr.phishguard.app.report

import kr.phishguard.app.history.HistoryEvent
import kr.phishguard.app.scan.AppInfo
import kr.phishguard.app.scan.RiskLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ReportInput(
    val generatedAt: Long,
    val deviceModel: String,
    val androidVersion: String,
    val apps: List<AppInfo>,
    val trusted: Set<String>,
    val events: List<HistoryEvent>,
)

/** 신고용 HTML 리포트 생성. Android 의존 없음. */
object ReportBuilder {
    const val MAX_EVENTS = 100

    fun escape(s: String?): String {
        val v = s ?: return ""
        val sb = StringBuilder(v.length + 16)
        for (c in v) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun fmt(ms: Long): String =
        if (ms <= 0L) "-" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date(ms))

    private fun flagsOf(app: AppInfo): String {
        val parts = mutableListOf<String>()
        parts += app.permissions.filter { it.granted }.mapNotNull { it.category?.label }
        if (app.isActiveDeviceAdmin) parts += "기기관리자 활성"
        if (app.hasEnabledAccessibility) parts += "접근성 서비스 활성"
        if (app.hasEnabledNotificationListener) parts += "알림 접근 활성"
        if (!app.hasLauncherIcon) parts += "앱 아이콘 없음"
        return parts.distinct().joinToString(", ").ifEmpty { "-" }
    }

    fun suspicious(input: ReportInput): List<AppInfo> =
        input.apps.filter { it.riskLevel != RiskLevel.LOW && it.packageName !in input.trusted }
            .sortedByDescending { it.riskScore }

    fun trustedListed(input: ReportInput): List<AppInfo> =
        input.apps.filter { it.riskLevel != RiskLevel.LOW && it.packageName in input.trusted }
            .sortedByDescending { it.riskScore }

    private fun appRow(a: AppInfo): String {
        val rules = a.matchedRules.joinToString("<br>") { "${escape(it.description)} (+${it.points})" }.ifEmpty { "-" }
        return "<tr><td>${escape(a.label)}<br><span class=\"m\">${escape(a.packageName)}</span></td>" +
            "<td>${escape(a.riskLevel.label)} (${a.riskScore}점)</td>" +
            "<td>$rules</td>" +
            "<td>${escape(fmt(a.firstInstallTime))}</td>" +
            "<td>${escape(a.installSource.label)}<br><span class=\"m\">${escape(a.installerPackage)}</span></td>" +
            "<td>${escape(flagsOf(a))}</td></tr>\n"
    }

    private fun appTable(apps: List<AppInfo>): String {
        if (apps.isEmpty()) return "<p>해당하는 앱이 없습니다.</p>\n"
        val sb = StringBuilder("<table><tr><th>앱</th><th>위험도</th><th>걸린 규칙</th><th>설치 시각</th><th>설치 출처</th><th>주요 권한·상태</th></tr>\n")
        apps.forEach { sb.append(appRow(it)) }
        return sb.append("</table>\n").toString()
    }

    fun build(input: ReportInput): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n<html lang=\"ko\"><head><meta charset=\"UTF-8\">\n")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        sb.append("<title>피싱앱 검사 리포트</title>\n<style>\n")
        sb.append("body{font-family:sans-serif;line-height:1.5;margin:16px;color:#111}\n")
        sb.append("h1{font-size:1.4em}h2{font-size:1.15em;margin-top:1.6em;border-bottom:1px solid #888}\n")
        sb.append("table{border-collapse:collapse;width:100%;font-size:.9em}\n")
        sb.append("th,td{border:1px solid #888;padding:4px 6px;text-align:left;vertical-align:top;word-break:break-all}\n")
        sb.append("th{background:#eee}.m{color:#555;font-size:.85em}\n")
        sb.append("@media print{body{margin:0}h2{page-break-after:avoid}tr{page-break-inside:avoid}}\n")
        sb.append("</style></head><body>\n")
        sb.append("<h1>피싱앱 검사 리포트</h1>\n")
        sb.append("<p>생성 시각: ${escape(fmt(input.generatedAt))}<br>")
        sb.append("기기: ${escape(input.deviceModel)} / Android ${escape(input.androidVersion)}</p>\n")
        sb.append("<p class=\"m\">이 리포트는 휴대폰 안에서 규칙으로 점검한 결과입니다. 의심 앱이라는 뜻이지 악성 앱으로 확정됐다는 뜻은 아닙니다.</p>\n")

        val sus = suspicious(input)
        sb.append("<h2>의심 앱 (${sus.size}개)</h2>\n").append(appTable(sus))

        val tr = trustedListed(input)
        sb.append("<h2>사용자가 '아는 앱'으로 표시한 앱 (${tr.size}개)</h2>\n").append(appTable(tr))

        val ev = input.events.take(MAX_EVENTS)
        sb.append("<h2>설치·삭제 기록 (최근 ${ev.size}건)</h2>\n")
        if (ev.isEmpty()) {
            sb.append("<p>기록이 없습니다.</p>\n")
        } else {
            sb.append("<table><tr><th>시각</th><th>종류</th><th>앱</th><th>설치 출처</th><th>위험도</th></tr>\n")
            ev.forEach { e ->
                val time = fmt(e.eventTime) + if (e.timeIsExact) "" else " (확인 시각)"
                sb.append("<tr><td>${escape(time)}</td><td>${escape(e.type.label)}</td>")
                sb.append("<td>${escape(e.label ?: e.packageName)}<br><span class=\"m\">${escape(e.packageName)}</span></td>")
                sb.append("<td>${escape(e.installSource?.label)}</td><td>${escape(e.riskLevel?.label)}</td></tr>\n")
            }
            sb.append("</table>\n")
        }
        sb.append("</body></html>\n")
        return sb.toString()
    }
}
