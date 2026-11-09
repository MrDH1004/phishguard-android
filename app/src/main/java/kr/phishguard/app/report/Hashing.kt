package kr.phishguard.app.report

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** SHA-256 계산과 증거 ZIP 무결성 목록(manifest) 생성. Android 의존 없음. */
object Hashing {
    const val MANIFEST_NAME = "manifest-sha256.txt"

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun sha256Hex(data: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(data))

    @Throws(IOException::class)
    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    /** 한 줄에 "<hex>  <항목>" 형식 (sha256sum 호환). */
    fun manifest(hashes: List<Pair<String, String>>): String =
        hashes.joinToString("") { (entry, hex) -> "$hex  $entry\n" }

    /** 화면 표시용 앞 16자리 + 말줄임 */
    fun short(hex: String): String = if (hex.length > 16) hex.take(16) + "…" else hex
}
