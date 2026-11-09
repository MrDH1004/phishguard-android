package kr.phishguard.app.update

/** Android 의존성이 없는 순수 로직 (단위 테스트 대상). */
object UpdateLogic {

    /** "v1.2.3" / "1.2" 형태를 숫자 목록으로 변환. 숫자가 아닌 접미사(-beta 등)는 무시. */
    fun parseVersion(raw: String): List<Int> {
        val core = raw.trim().removePrefix("v").removePrefix("V").takeWhile { it.isDigit() || it == '.' }
        if (core.isEmpty()) return emptyList()
        return core.split('.').map { it.toIntOrNull() ?: 0 }
    }

    /** a > b 면 양수, 같으면 0, a < b 면 음수. */
    fun compareVersions(a: String, b: String): Int {
        val pa = parseVersion(a)
        val pb = parseVersion(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    fun isNewer(remote: String, current: String): Boolean =
        parseVersion(remote).isNotEmpty() && compareVersions(remote, current) > 0

    /** ".sha256" 파일 내용에서 64자리 hex 추출. "<hex>  filename" 또는 hex 단독. 실패 시 null. */
    fun parseSha256(text: String): String? {
        val first = text.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        val token = first.split(Regex("\\s+")).firstOrNull()?.removePrefix("*").orEmpty()
        return if (Regex("^[0-9a-fA-F]{64}$").matches(token)) token.lowercase() else null
    }

    fun hashMatches(expectedHex: String, actualHex: String): Boolean =
        expectedHex.trim().equals(actualHex.trim(), ignoreCase = true)
}
