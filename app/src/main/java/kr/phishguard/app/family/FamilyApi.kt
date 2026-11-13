package kr.phishguard.app.family

import android.util.Log
import kr.phishguard.app.BuildConfig
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/** 서버 응답 오류. code는 서버가 준 오류 코드 (예: pin_not_found, unknown_token) */
class FamilyApiException(val status: Int, val code: String, message: String) : IOException(message) {
    /** 연결이 서버에서 사라짐 (상대가 끊었거나 오래돼 지워짐). 프록시·캡티브 포털의 401은 해당 안 됨 */
    val unlinked: Boolean get() = status == 401 && code == "unknown_token"
}

/** 가족 연결 서버(Cloudflare Worker) 호출. 모두 블로킹이므로 IO 스레드에서 부른다. */
object FamilyApi {
    private const val TAG = "PG-FamilyApi"
    private const val TIMEOUT_MS = 15_000

    val baseUrl: String get() = BuildConfig.FAMILY_SERVER_URL.trimEnd('/')

    data class StartResult(val token: String, val pin: String, val expiresAt: Long)
    data class JoinResult(val token: String, val childName: String)

    /** 자녀: 새 가족 + PIN */
    fun start(deviceName: String): StartResult {
        val j = request("POST", "/v1/pair/start", JSONObject().put("name", deviceName), null)
        return StartResult(j.getString("token"), j.getString("pin"), j.getLong("expiresAt"))
    }

    /** 자녀: PIN 다시 만들기 */
    fun newPin(token: String): Pair<String, Long> {
        val j = request("POST", "/v1/pair/pin", JSONObject(), token)
        return j.getString("pin") to j.getLong("expiresAt")
    }

    /**
     * 부모: PIN 입력. 토큰의 비밀값은 이 폰이 만들어 보낸다 → 응답이 유실돼도 같은 값으로 다시 보내면 같은 결과.
     * 네트워크 오류면 한 번 더 시도한다.
     */
    fun join(pin: String, deviceName: String): JoinResult {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val body = JSONObject().put("pin", pin).put("name", deviceName).put("secret", secret)
        val j = try {
            request("POST", "/v1/pair/join", body, null)
        } catch (e: FamilyApiException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "join 응답 없음, 같은 값으로 다시 시도", e)
            request("POST", "/v1/pair/join", body, null)
        }
        return JoinResult(j.getString("token"), j.optString("childName", "자녀 폰"))
    }

    /** 자녀: 연결 요청 승인/거절 */
    fun approve(token: String, approve: Boolean) {
        request("POST", "/v1/pair/approve", JSONObject().put("approve", approve), token)
    }

    /** 자녀: 부모 폰에 조치 명령 (lock / block / ignore) */
    fun command(token: String, type: String, requestId: String, packageName: String, label: String) {
        val data = JSONObject().put("requestId", requestId).put("packageName", packageName).put("label", label)
        request("POST", "/v1/commands", JSONObject().put("type", type).put("data", data), token)
    }

    fun state(token: String, sinceId: Long): JSONObject = request("GET", "/v1/state?since=$sinceId", null, token)

    /** 부모: 사건 보내기 (alert / snapshot / heartbeat) */
    fun post(token: String, type: String, data: JSONObject) {
        request("POST", "/v1/events", JSONObject().put("type", type).put("data", data), token)
    }

    fun unlink(token: String) {
        request("DELETE", "/v1/family", null, token)
    }

    fun wsUrl(sinceId: Long): String = baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") +
        "/v1/ws?since=$sinceId"

    private fun request(method: String, path: String, body: JSONObject?, token: String?): JSONObject {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.useCaches = false
            conn.setRequestProperty("accept", "application/json")
            if (token != null) conn.setRequestProperty("authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("content-type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            val json = try {
                if (text.isEmpty()) JSONObject() else JSONObject(text)
            } catch (e: JSONException) {
                Log.w(TAG, "$method $path: JSON 아님 (HTTP $status)", e)
                throw FamilyApiException(status, "bad_response", "서버 응답을 읽을 수 없습니다 (HTTP $status)")
            }
            if (status !in 200..299) {
                val code = json.optString("error", "http_$status")
                val msg = json.optString("message", "서버 오류 (HTTP $status)")
                Log.w(TAG, "$method $path → $status $code")
                throw FamilyApiException(status, code, msg)
            }
            return json
        } finally {
            conn.disconnect()
        }
    }
}
