package kr.phishguard.app.remove

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kr.phishguard.app.net.UsageStats
import kr.phishguard.app.scan.AppInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class EvidenceResult(
    val fileName: String,
    /** 앱 캐시 내 사본 (공유용, FileProvider) */
    val cacheFile: File,
    /** 다운로드 폴더 저장 위치 설명 */
    val savedTo: String,
    val entries: List<String>,
)

/** 증거 ZIP(APK, 권한목록, 설치정보, 데이터 사용량 CSV) 생성 후 다운로드 폴더에 저장 */
object EvidenceExporter {
    private fun timeFmt() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** API 28 이하는 WRITE_EXTERNAL_STORAGE 런타임 권한 필요 */
    fun needsLegacyPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    @Throws(IOException::class)
    fun export(context: Context, app: AppInfo): EvidenceResult {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "phishguard_evidence_${app.packageName}_$stamp.zip"
        val dir = File(context.cacheDir, "evidence").also { it.mkdirs() }
        val zipFile = File(dir, name)
        val entries = mutableListOf<String>()

        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            fun put(entry: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(entry))
                zip.write(bytes)
                zip.closeEntry()
                entries += entry
            }
            fun putFile(entry: String, path: String) {
                val f = File(path)
                if (!f.canRead()) return
                zip.putNextEntry(ZipEntry(entry))
                FileInputStream(f).use { it.copyTo(zip) }
                zip.closeEntry()
                entries += entry
            }
            app.sourceDir?.let { putFile("apk/base.apk", it) }
            app.splitSourceDirs.forEach { putFile("apk/${File(it).name}", it) }
            put("permissions.json", permissionsJson(app).toByteArray(Charsets.UTF_8))
            put("install-info.json", installJson(app).toByteArray(Charsets.UTF_8))
            put("data-usage.csv", usageCsv(context, app).toByteArray(Charsets.UTF_8))
        }

        val savedTo = saveToDownloads(context, zipFile, name)
        return EvidenceResult(name, zipFile, savedTo, entries)
    }

    private fun permissionsJson(app: AppInfo): String {
        val arr = JSONArray()
        app.permissions.forEach {
            arr.put(
                JSONObject().put("name", it.name).put("category", it.category?.label ?: JSONObject.NULL)
                    .put("granted", it.granted),
            )
        }
        return JSONObject().put("package", app.packageName).put("permissions", arr)
            .put("deviceAdminActive", app.isActiveDeviceAdmin)
            .put("accessibilityEnabled", app.hasEnabledAccessibility)
            .put("notificationListenerEnabled", app.hasEnabledNotificationListener)
            .toString(2)
    }

    private fun installJson(app: AppInfo): String {
        val fmt = timeFmt()
        val rules = JSONArray()
        app.matchedRules.forEach {
            rules.put(JSONObject().put("id", it.id).put("description", it.description).put("points", it.points))
        }
        return JSONObject()
            .put("package", app.packageName).put("label", app.label).put("versionName", app.versionName ?: JSONObject.NULL)
            .put("firstInstallTime", fmt.format(Date(app.firstInstallTime)))
            .put("lastUpdateTime", fmt.format(Date(app.lastUpdateTime)))
            .put("installerPackage", app.installerPackage ?: JSONObject.NULL)
            .put("installSource", app.installSource.label)
            .put("hasLauncherIcon", app.hasLauncherIcon)
            .put("riskScore", app.riskScore).put("riskLevel", app.riskLevel.label)
            .put("matchedRules", rules)
            .put("exportedAt", fmt.format(Date()))
            .toString(2)
    }

    private fun csvCell(s: String?): String {
        val v = s ?: ""
        return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    /** 이 앱의 Wi-Fi/모바일 송수신량 (최근 1/7/30일). 사용 정보 접근 권한이 없으면 안내 한 줄 */
    private fun usageCsv(context: Context, app: AppInfo): String {
        val nl = "\r\n"
        val sb = StringBuilder("﻿period,package,wifi_sent,wifi_received,mobile_sent,mobile_received").append(nl)
        if (!UsageStats.hasAccess(context)) {
            sb.append("# 사용 정보 접근 권한이 없어 데이터 사용량을 기록하지 못함").append(nl)
            return sb.toString()
        }
        val now = System.currentTimeMillis()
        listOf(1 to "24h", 7 to "7d", 30 to "30d").forEach { (days, label) ->
            val u = UsageStats.query(context, now - days * 24L * 3600_000L)[app.uid]
            val row = listOf(
                label, csvCell(app.packageName),
                (u?.wifiTx ?: 0L).toString(), (u?.wifiRx ?: 0L).toString(),
                (u?.mobileTx ?: 0L).toString(), (u?.mobileRx ?: 0L).toString(),
            )
            sb.append(row.joinToString(",")).append(nl)
        }
        return sb.toString()
    }

    @Throws(IOException::class)
    private fun saveToDownloads(context: Context, src: File, name: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("다운로드 폴더에 파일을 만들 수 없음")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("출력 스트림을 열 수 없음")
                out.use { o -> FileInputStream(src).use { it.copyTo(o) } }
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            } catch (e: IOException) {
                resolver.delete(uri, null, null)
                throw e
            }
            return "다운로드/$name"
        }
        @Suppress("DEPRECATION")
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dl.exists() && !dl.mkdirs()) throw IOException("다운로드 폴더를 만들 수 없음")
        val dst = File(dl, name)
        src.copyTo(dst, overwrite = true)
        return dst.absolutePath
    }
}
