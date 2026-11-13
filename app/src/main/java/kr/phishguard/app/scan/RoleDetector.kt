package kr.phishguard.app.scan

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.provider.Telephony
import android.telecom.TelecomManager
import android.util.Log
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/**
 * 기본 전화/문자 앱과 통화 관련 기능을 선언한 앱 조사.
 *
 * RoleManager.getRoleHolders는 시스템 API라 일반 앱이 부를 수 없으므로 공개 API로 대신한다.
 *  - 기본 전화 앱: TelecomManager.getDefaultDialerPackage() (API 23+, 선택된 앱이 없으면 null)
 *  - 기본 문자 앱: Telephony.Sms.getDefaultSmsPackage(context) (API 19+, 없으면 null)
 *  - 통화 연결 바꾸기 / 통화 가려내기 / 통화 화면: 해당 intent action과 bind 권한을 가진 서비스를 선언한 앱.
 *    실제 역할 보유자가 아니라 "역할 가능(capable)" 앱이다.
 * Android 11+ 패키지 가시성은 매니페스트의 QUERY_ALL_PACKAGES로 확보돼 있다.
 */
object RoleDetector {
    private const val TAG = "RoleDetector"

    // 공식 문서의 SERVICE_INTERFACE 값과 서비스 선언 권한.
    // CallRedirectionService는 API 29+라 상수 대신 문자열을 쓴다 (낮은 API에서는 일치하는 앱이 없을 뿐이다).
    const val ACTION_CALL_REDIRECTION = "android.telecom.CallRedirectionService"
    const val PERM_CALL_REDIRECTION = "android.permission.BIND_CALL_REDIRECTION_SERVICE"
    const val ACTION_CALL_SCREENING = "android.telecom.CallScreeningService"
    const val PERM_CALL_SCREENING = "android.permission.BIND_SCREENING_SERVICE"
    const val ACTION_IN_CALL = "android.telecom.InCallService"
    const val PERM_IN_CALL = "android.permission.BIND_INCALL_SERVICE"

    class Snapshot(
        val defaultDialer: String?,
        val defaultSms: String?,
        val callRedirection: Set<String>,
        val callScreening: Set<String>,
        val inCall: Set<String>,
    )

    fun snapshot(context: Context): Snapshot {
        val pm = context.packageManager
        return Snapshot(
            defaultDialer = defaultDialer(context),
            defaultSms = defaultSms(context),
            callRedirection = servicePackages(pm, ACTION_CALL_REDIRECTION, PERM_CALL_REDIRECTION),
            callScreening = servicePackages(pm, ACTION_CALL_SCREENING, PERM_CALL_SCREENING),
            inCall = servicePackages(pm, ACTION_IN_CALL, PERM_IN_CALL),
        )
    }

    fun defaultDialer(context: Context): String? = try {
        (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage
    } catch (e: RuntimeException) {
        Log.w(TAG, "default dialer lookup failed", e)
        null
    }

    fun defaultSms(context: Context): String? = try {
        Telephony.Sms.getDefaultSmsPackage(context)
    } catch (e: RuntimeException) {
        Log.w(TAG, "default sms lookup failed", e)
        null
    }

    /** action을 처리하고 bind 권한으로 보호된 서비스를 선언한 패키지 */
    private fun servicePackages(pm: PackageManager, action: String, permission: String): Set<String> = try {
        val intent = Intent(action)
        val list: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, 0)
        }
        list.mapNotNull { ri -> ri.serviceInfo?.takeIf { it.permission == permission }?.packageName }.toSet()
    } catch (e: RuntimeException) {
        Log.w(TAG, "service query failed: $action", e)
        emptySet()
    }

    // ---- 서명 인증서 ----

    /** getInstalledPackages/getPackageInfo에 함께 넘길 서명 조회 플래그 */
    val signingFlag: Int
        get() = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    /** 앱 서명 인증서의 SHA-256 (소문자 hex). 여러 서명자면 모두, 아니면 교체 이력 포함. */
    class Signers(val digests: Set<String>, val multiple: Boolean, val subjects: List<String> = emptyList())

    fun signersOf(pi: PackageInfo): Signers {
        return try {
            val (certs, multiple) = if (Build.VERSION.SDK_INT >= 28) {
                val info = pi.signingInfo ?: return Signers(emptySet(), false)
                if (info.hasMultipleSigners()) {
                    info.apkContentsSigners.orEmpty().map { it.toByteArray() } to true
                } else {
                    info.signingCertificateHistory.orEmpty().map { it.toByteArray() } to false
                }
            } else {
                @Suppress("DEPRECATION")
                val sigs = pi.signatures.orEmpty()
                sigs.map { it.toByteArray() } to (sigs.size > 1)
            }
            Signers(certs.map { sha256(it) }.toSet(), multiple, certs.mapNotNull { subjectOf(it, pi.packageName) })
        } catch (e: RuntimeException) {
            Log.w(TAG, "signer read failed: ${pi.packageName}", e)
            Signers(emptySet(), false)
        }
    }

    /** X.509 인증서 주체 이름 (RFC 2253, 예: "CN=Android Debug,O=Android,C=US"). 읽지 못하면 null */
    private fun subjectOf(der: ByteArray, pkg: String): String? = try {
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as? X509Certificate
        cert?.subjectX500Principal?.getName(X500Principal.RFC2253)
    } catch (e: CertificateException) {
        Log.w(TAG, "certificate parse failed: $pkg", e)
        null
    }

    /**
     * 시스템 앱 서명 집합과 비교. 여러 서명자 앱은 서명자 전체가 하나의 신원이므로 모두 포함돼야 하고,
     * 단일 서명자 앱은 교체 이력 중 하나라도 포함되면 같은 서명으로 본다.
     */
    fun isSignedBy(signers: Signers, systemDigests: Set<String>): Boolean {
        if (signers.digests.isEmpty()) return false
        return if (signers.multiple) {
            systemDigests.containsAll(signers.digests)
        } else {
            signers.digests.any { it in systemDigests }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
