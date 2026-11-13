package kr.phishguard.app.scan

import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** APK(zip) 내부 구조에서 찾은 이상 징후. 순수 데이터 (단위 테스트 가능). */
data class ApkStructure(
    /** assets/ 또는 res/raw/ 안에 .apk/.dex/.jar가 숨어 있음 (실행 중에 꺼내 설치·실행하는 드로퍼 수법) */
    val nestedPayload: Boolean = false,
    /** 파일 이름 길이가 300자 넘는 항목 (분석 도구를 망가뜨리려는 조작) */
    val longEntryName: Boolean = false,
    /** 최상위에 o…bf 형태 파일 (난독화 도구 흔적) */
    val obfuscatorRootFile: Boolean = false,
    /** 설치된 APK인데 zip으로 열리지 않음 (손상·조작) */
    val openFailed: Boolean = false,
    /** 상용 패커(코드 감싸기) 라이브러리 포함 */
    val packerLibs: Boolean = false,
)

/** 설치된 APK의 zip 항목 이름만 훑어 구조 이상을 찾는다 (내용은 읽지 않음). */
object ApkInspector {
    const val MAX_ENTRIES = 5000
    const val MAX_NAME_LENGTH = 300
    const val TIME_BUDGET_MS = 1500L

    private val NESTED_DIRS = listOf("assets/", "res/raw/")
    private val NESTED_EXTS = listOf(".apk", ".dex", ".jar")
    private val OBFUSCATOR_ROOT = Regex("^o.*bf$")

    /** lib/<abi>/ 아래 파일 이름. 접두어로 끝나는 항목은 뒤에 무엇이 붙어도 일치 */
    private val PACKER_PREFIXES = listOf("libjiagu", "libshella", "libDexHelper")
    private val PACKER_EXACT = setOf("libtxoprot.so", "libsecexe.so", "libsecmain.so")

    private fun isPackerLib(name: String): Boolean {
        if (!name.startsWith("lib/")) return false
        val file = name.substringAfterLast('/')
        if (!file.endsWith(".so")) return false
        return file in PACKER_EXACT || PACKER_PREFIXES.any { file.startsWith(it) }
    }

    /** 항목 이름 목록에서 이상 징후 판정 ([MAX_ENTRIES]개까지만 본다) */
    fun analyzeEntryNames(names: Sequence<String>): ApkStructure {
        var nested = false
        var long = false
        var obf = false
        var packer = false
        names.take(MAX_ENTRIES).forEach { name ->
            val lower = name.lowercase()
            if (!nested && NESTED_DIRS.any { lower.startsWith(it) } && NESTED_EXTS.any { lower.endsWith(it) }) nested = true
            if (name.length > MAX_NAME_LENGTH) long = true
            if (!obf && '/' !in name && OBFUSCATOR_ROOT.matches(name)) obf = true
            if (!packer && isPackerLib(name)) packer = true
        }
        return ApkStructure(nestedPayload = nested, longEntryName = long, obfuscatorRootFile = obf, packerLibs = packer)
    }

    /**
     * APK 파일을 열어 검사. IO 스레드에서 호출. [MAX_ENTRIES]개 또는 [TIME_BUDGET_MS]가 넘으면 거기까지만 본다.
     * 경로가 없으면 null (판단 안 함). 파일은 있는데 zip으로 열리지 않으면 openFailed.
     */
    fun inspect(path: String?, onError: (String, Exception) -> Unit = { _, _ -> }): ApkStructure? {
        if (path.isNullOrEmpty()) return null
        val file = File(path)
        if (!file.canRead()) return null
        val deadline = System.currentTimeMillis() + TIME_BUDGET_MS
        return try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                val names = generateSequence {
                    if (entries.hasMoreElements() && System.currentTimeMillis() < deadline) entries.nextElement().name else null
                }
                analyzeEntryNames(names)
            }
        } catch (e: ZipException) {
            onError("zip open failed: $path", e)
            ApkStructure(openFailed = true)
        } catch (e: IOException) {
            // 읽기 권한·일시적 IO 오류는 조작 증거가 아니므로 판단하지 않는다
            onError("apk read failed: $path", e)
            null
        } catch (e: SecurityException) {
            onError("apk access denied: $path", e)
            null
        } catch (e: IllegalArgumentException) {
            // 항목 이름 인코딩이 깨진 경우 (JDK ZipFile이 순회 중 던짐). 판단하지 않는다
            onError("apk entry name malformed: $path", e)
            null
        }
    }
}
