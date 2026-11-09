package kr.phishguard.app.report

import android.content.Context
import android.os.Build
import kr.phishguard.app.remove.EvidenceExporter
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ReportResult(val fileName: String, val cacheFile: File, val savedTo: String)

/** HTML 리포트를 앱 캐시(공유용)와 다운로드 폴더에 저장. */
object ReportExporter {
    const val MIME = "text/html"

    fun deviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    fun androidVersion(): String = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    @Throws(IOException::class)
    fun export(context: Context, html: String): ReportResult {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "phishguard_report_$stamp.html"
        val dir = File(context.cacheDir, "evidence").also { it.mkdirs() }
        val file = File(dir, name)
        file.writeText(html, Charsets.UTF_8)
        val savedTo = EvidenceExporter.saveToDownloads(context, file, name, MIME)
        return ReportResult(name, file, savedTo)
    }
}
