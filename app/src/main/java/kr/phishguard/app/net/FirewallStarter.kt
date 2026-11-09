package kr.phishguard.app.net

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * VPN 동의 화면을 거쳐 인터넷 차단 서비스를 켜는 함수를 돌려준다.
 * 반환된 함수를 호출하면: 동의가 필요하면 시스템 동의창 → 승인 시 서비스 시작.
 */
@Composable
fun rememberFirewallStarter(onResult: (Boolean) -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val ok = r.resultCode == Activity.RESULT_OK
        if (ok) MonitorVpnService.start(context)
        onResult(ok)
    }
    // 결과와 무관하게 방화벽 시작은 진행한다(알림이 안 보일 뿐 포그라운드 서비스는 동작)
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val prepare = VpnService.prepare(context)
        if (prepare != null) {
            launcher.launch(prepare)
        } else {
            MonitorVpnService.start(context)
            onResult(true)
        }
    }
}
