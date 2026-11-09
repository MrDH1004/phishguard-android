package kr.phishguard.app.net

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kr.phishguard.app.scan.PermCategory
import kr.phishguard.app.scan.RiskLevel
import kr.phishguard.app.scan.ScanRepository
import kr.phishguard.app.scan.TrustList
import kr.phishguard.app.ui.ErrorText
import kr.phishguard.app.ui.RiskHigh
import kr.phishguard.app.ui.RiskLow

private const val TAG = "PG-SensorUse"

/**
 * 지금 마이크·카메라가 사용 중인지 보여 주는 카드.
 *
 * 안드로이드는 일반 앱에게 "어느 앱이" 쓰는지 알려 주지 않으므로 사용 여부만 표시한다.
 * - 카메라: CameraManager.AvailabilityCallback.onCameraUnavailable 은 어떤 앱이든 카메라를 열면 호출된다.
 *   https://developer.android.com/reference/android/hardware/camera2/CameraManager.AvailabilityCallback
 * - 마이크: AudioManager.getActiveRecordingConfigurations / AudioRecordingCallback (API 24+).
 *   https://developer.android.com/media/platform/sharing-audio-input
 *   기기·버전에 따라 다른 앱의 녹음이 보이지 않을 수 있어 "감지 안 됨"을 "안전"으로 단정하지 않는다.
 */
@Composable
fun SensorInUseCard() {
    val context = LocalContext.current
    var camerasInUse by remember { mutableStateOf(emptySet<String>()) }
    var micInUse by remember { mutableStateOf(false) }
    var openFailed by remember { mutableStateOf(false) }
    val apps by ScanRepository.apps.collectAsState()
    val trusted by TrustList.trusted.collectAsState()

    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        val cameraCallback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                camerasInUse = camerasInUse - cameraId
            }

            override fun onCameraUnavailable(cameraId: String) {
                camerasInUse = camerasInUse + cameraId
            }
        }
        val audioCallback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                micInUse = !configs.isNullOrEmpty()
            }
        }
        try {
            cameraManager?.registerAvailabilityCallback(cameraCallback, handler)
        } catch (e: SecurityException) {
            Log.w(TAG, "camera callback register failed", e)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "camera callback register failed", e)
        }
        try {
            audioManager?.let {
                micInUse = it.activeRecordingConfigurations.isNotEmpty()
                it.registerAudioRecordingCallback(audioCallback, handler)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "audio callback register failed", e)
        }
        onDispose {
            try {
                cameraManager?.unregisterAvailabilityCallback(cameraCallback)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "camera callback unregister failed", e)
            }
            try {
                audioManager?.unregisterAudioRecordingCallback(audioCallback)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "audio callback unregister failed", e)
            }
        }
    }

    val cameraInUse = camerasInUse.isNotEmpty()
    val anyInUse = cameraInUse || micInUse
    // 마이크·카메라 권한이 허용된 의심 앱 (안전앱 제외) — 사용 중일 때 확인할 후보
    val candidates = apps.filter { a ->
        a.packageName !in trusted &&
            a.packageName != context.packageName &&
            (a.riskLevel == RiskLevel.HIGH || a.riskLevel == RiskLevel.MEDIUM) &&
            a.permissions.any { it.granted && it.category == PermCategory.CAMERA_MIC }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("마이크·카메라 사용 감시", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            ) {
                Icon(
                    if (anyInUse) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = if (anyInUse) RiskHigh else RiskLow,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    when {
                        cameraInUse && micInUse -> "지금 어떤 앱이 카메라와 마이크를 사용 중입니다"
                        cameraInUse -> "지금 어떤 앱이 카메라를 사용 중입니다"
                        micInUse -> "지금 어떤 앱이 마이크를 사용 중입니다"
                        else -> "지금 감지된 카메라·마이크 사용이 없습니다"
                    },
                    color = if (anyInUse) RiskHigh else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (anyInUse) FontWeight.Bold else FontWeight.Normal,
                )
            }
            Text(
                "안드로이드는 어느 앱이 쓰는지 다른 앱에 알려 주지 않습니다. 화면 오른쪽 위 초록 점을 내려서 누르면 사용 중인 앱이 보입니다. 통화·영상통화 중이 아닌데 사용 중으로 뜨면 의심하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "기기에 따라 다른 앱의 마이크 사용이 감지되지 않을 수 있습니다. '감지 없음'이 곧 안전하다는 뜻은 아닙니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (candidates.isNotEmpty()) {
                Text(
                    "마이크·카메라 권한이 있는 의심 앱: " + candidates.joinToString(", ") { "${it.label}(위험 ${it.riskLevel.label})" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            OutlinedButton(onClick = {
                openFailed = false
                val intents = listOf(
                    Intent("android.settings.PRIVACY_SETTINGS"),
                    Intent(android.provider.Settings.ACTION_SETTINGS),
                )
                openFailed = intents.none { i ->
                    try {
                        context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
                    } catch (e: ActivityNotFoundException) {
                        Log.w(TAG, "privacy settings open failed: ${i.action}", e); false
                    }
                }
            }) { Text("개인정보 보호 설정 열기") }
            Text(
                "개인정보 보호 설정의 '권한 사용 기록(개인정보 대시보드)'에서 최근 24시간 동안 마이크·카메라를 쓴 앱을 확인할 수 있습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (openFailed) {
                ErrorText("설정 화면을 열 수 없습니다. '설정' 앱에서 '보안 및 개인정보 보호'를 직접 찾아 주세요.")
            }
        }
    }
}
