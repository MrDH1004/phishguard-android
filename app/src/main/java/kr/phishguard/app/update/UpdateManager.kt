package kr.phishguard.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kr.phishguard.app.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val TAG = "PhishGuardUpdate"

data class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val sha256Url: String,
)

sealed class CheckResult {
    data class Available(val info: UpdateInfo) : CheckResult()
    object UpToDate : CheckResult()
    object Failed : CheckResult()
}

sealed class DownloadResult {
    data class Ready(val file: File) : DownloadResult()
    data class Error(val message: String) : DownloadResult()
}

object UpdateManager {

    private fun open(url: String, accept: String = "application/vnd.github+json"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PhishGuard-Updater")
            setRequestProperty("Accept", accept)
        }

    /** 일시적 네트워크 오류(시간 초과 등)는 한 번 더 시도한다. */
    private fun <T> withRetry(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        Log.w(TAG, "retrying after ${e.javaClass.simpleName}")
        block()
    }

    /** 이 앱의 네트워크 사용은 업데이트 확인/다운로드뿐. 실패는 조용히 Failed. */
    fun check(): CheckResult {
        var conn: HttpURLConnection? = null
        try {
            conn = open("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            if (conn.responseCode != 200) {
                Log.w(TAG, "releases/latest HTTP ${conn.responseCode}")
                return CheckResult.Failed
            }
            val json = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val tag = json.optString("tag_name")
            if (!UpdateLogic.isNewer(tag, BuildConfig.VERSION_NAME)) return CheckResult.UpToDate
            val assets = json.optJSONArray("assets") ?: return CheckResult.Failed
            var apk: String? = null
            var sha: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                // github.com/.../releases/download 경로는 지역 엣지에서 504·지연이 관찰됨(2026-11-09).
                // API 자산 경로 + Accept: application/octet-stream 으로 받는다.
                val url = a.optString("url").ifEmpty { a.optString("browser_download_url") }
                if (name.endsWith(".apk.sha256")) sha = url
                else if (name.endsWith(".apk")) apk = url
            }
            if (apk == null || sha == null) {
                Log.w(TAG, "release assets missing apk or sha256")
                return CheckResult.Failed
            }
            return CheckResult.Available(UpdateInfo(tag.removePrefix("v"), json.optString("body"), apk, sha))
        } catch (e: Exception) {
            Log.w(TAG, "update check failed", e)
            return CheckResult.Failed
        } finally {
            conn?.disconnect()
        }
    }

    /** APK 다운로드 + SHA-256 + 패키지명/버전/서명 검증. 실패 시 파일 삭제. */
    fun downloadAndVerify(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float) -> Unit,
        onStatus: (String) -> Unit,
    ): DownloadResult {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val apk = File(dir, "phishguard-${info.version}.apk")
        var stage = "다운로드"
        try {
            onStatus("다운로드 중…")
            withRetry { download(info.apkUrl, apk, onProgress) }
            stage = "검증 정보 받기"
            onStatus("무결성 확인 중…")
            val expected = UpdateLogic.parseSha256(withRetry { fetchText(info.sha256Url) })
                ?: return fail(apk, "검증 정보를 읽지 못해 설치하지 않았습니다. 지금 버전은 그대로 안전하게 쓸 수 있습니다. 잠시 후 '업데이트'를 다시 눌러 주세요.")
            if (!UpdateLogic.hashMatches(expected, sha256Hex(apk))) {
                return fail(apk, "받은 파일이 손상되었거나 변조되어 설치하지 않았습니다. 지금 버전은 그대로 안전하게 쓸 수 있습니다. 잠시 후 '업데이트'를 다시 눌러 주세요.")
            }
            stage = "서명 확인"
            onStatus("서명 확인 중…")
            val err = verifyPackage(context, apk)
            if (err != null) return fail(apk, err)
            onStatus("검증 완료")
            return DownloadResult.Ready(apk)
        } catch (e: Exception) {
            Log.w(TAG, "download failed at $stage", e)
            val cause = "${e.javaClass.simpleName}: ${e.message?.take(120) ?: "-"}"
            return fail(
                apk,
                "새 버전을 내려받지 못했습니다($stage 단계). 지금 버전은 그대로 안전하게 쓸 수 있습니다. " +
                    "인터넷 연결을 확인한 뒤 다시 누르거나, '브라우저로 받기'로 직접 받아 설치하세요. (원인: $cause)",
            )
        }
    }

    private fun fail(f: File, msg: String): DownloadResult {
        f.delete()
        return DownloadResult.Error(msg)
    }

    private fun download(url: String, dest: File, onProgress: (Float) -> Unit) {
        val conn = open(url, "application/octet-stream")
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct / 100f)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchText(url: String): String {
        val conn = open(url, "application/octet-stream")
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256Hex(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun signerHashes(info: PackageInfo): Set<String>? {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo ?: return null
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            info.signatures
        }
        if (sigs == null || sigs.isEmpty()) return null
        return sigs.map { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    /** 문제 없으면 null, 있으면 한국어 오류 메시지. */
    @Suppress("DEPRECATION")
    private fun verifyPackage(context: Context, apk: File): String? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: return "받은 파일이 올바른 앱 설치 파일이 아니어서 설치하지 않았습니다. 지금 버전은 그대로 안전하게 쓸 수 있습니다. 잠시 후 다시 시도해 주세요."
        archive.applicationInfo?.let {
            it.sourceDir = apk.absolutePath
            it.publicSourceDir = apk.absolutePath
        }
        if (archive.packageName != context.packageName) return "받은 파일이 이 앱의 파일이 아니어서 설치하지 않았습니다. 지금 버전은 그대로 안전하게 쓸 수 있습니다. 잠시 후 다시 시도해 주세요."
        val own = pm.getPackageInfo(context.packageName, flags)
        val newCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val ownCode = if (Build.VERSION.SDK_INT >= 28) own.longVersionCode else own.versionCode.toLong()
        if (newCode <= ownCode) return "받은 파일이 지금 버전보다 새 버전이 아니어서 설치하지 않았습니다. 지금 버전은 그대로 안전하게 쓸 수 있습니다. 따로 하실 일은 없습니다."
        val a = signerHashes(archive)
        val b = signerHashes(own)
        if (a == null || b == null || a != b) return "받은 파일의 서명이 이 앱과 달라 설치하지 않았습니다. 지금 버전은 그대로 안전하게 쓸 수 있습니다. 다른 곳에서 받은 설치 파일은 설치하지 마세요."
        return null
    }

    /** 설치 화면 열기. 알 수 없는 앱 설치 권한이 없으면 설정 화면으로 보내고 false 반환. */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        systemInstallerPackage(context, view)?.let { view.setPackage(it) }
        context.startActivity(view)
        return true
    }

    /** 시스템 앱인 패키지 설치 화면 패키지명. 없으면 null(고정하지 않음). */
    private fun systemInstallerPackage(context: Context, view: Intent): String? {
        return try {
            val pm = context.packageManager
            val list = if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(view, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(view, 0)
            }
            list.firstOrNull {
                ((it.activityInfo?.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0
            }?.activityInfo?.packageName
        } catch (e: RuntimeException) {
            Log.w(TAG, "installer resolve failed", e)
            null
        }
    }
}
