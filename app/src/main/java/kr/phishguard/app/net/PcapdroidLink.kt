package kr.phishguard.app.net

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kr.phishguard.app.ui.ErrorText

/**
 * PCAPdroid 공개 앱 API(CaptureCtrl) 연동.
 * 문서: https://github.com/emanuele-f/PCAPdroid/blob/master/docs/app_api.md
 * - action=start/stop, app_filter=<패키지>, pcap_dump_mode=none
 * - PCAPdroid가 매 요청마다 사용자 동의 화면을 띄운다(api_key 미사용).
 * - 감시 결과(접속 IP·도메인·국가, 악성 목록 탐지)는 PCAPdroid 화면에서 본다. 이 앱은 패킷을 받지 않는다.
 */
object PcapdroidLink {
    const val PACKAGE = "com.emanuelef.remote_capture"
    private const val CTRL = "com.emanuelef.remote_capture.activities.CaptureCtrl"

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun startIntent(targetPackage: String): Intent = Intent(Intent.ACTION_VIEW)
        .setClassName(PACKAGE, CTRL)
        .putExtra("action", "start")
        .putExtra("pcap_dump_mode", "none")
        .putExtra("app_filter", targetPackage)

    fun stopIntent(): Intent = Intent(Intent.ACTION_VIEW)
        .setClassName(PACKAGE, CTRL)
        .putExtra("action", "stop")

    fun openAppIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(PACKAGE)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

private const val TAG = "PG-Pcapdroid"

/** 앱 상세 화면용: 이 앱만 PCAPdroid로 감시 시작/중지 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PcapdroidMonitorSection(targetPackage: String, targetLabel: String) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val installed = PcapdroidLink.isInstalled(context)

    val startLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            error = null
            status = "PCAPdroid가 '$targetLabel' 감시를 시작했습니다. 'PCAPdroid 열기'에서 접속 IP·도메인·국가를 확인하세요."
        } else {
            status = null
            error = "감시를 시작하지 못했습니다. PCAPdroid 허용 창에서 '허용'을 눌렀는지, 다른 VPN 앱(이 앱의 인터넷 차단 포함)이 꺼져 있는지 확인한 뒤 다시 시도하세요."
        }
    }
    val stopLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            error = null
            status = "PCAPdroid 감시를 중지했습니다."
        } else {
            error = "감시를 중지하지 못했습니다. PCAPdroid 앱을 열어 직접 중지하세요."
        }
    }

    fun launch(block: () -> Unit) {
        try {
            block()
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "PCAPdroid launch failed", e)
            error = "PCAPdroid를 실행할 수 없습니다. 최신 버전으로 업데이트하거나 다시 설치한 뒤 시도하세요."
        } catch (e: SecurityException) {
            Log.w(TAG, "PCAPdroid launch denied", e)
            error = "PCAPdroid 실행이 거부되었습니다. PCAPdroid 설정 > Control Permissions에서 이 앱이 차단돼 있지 않은지 확인하세요."
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "PCAPdroid(무료 오픈소스)를 쓰면 이 앱이 어느 IP·도메인·국가로 통신하는지, 알려진 악성 주소인지 확인할 수 있습니다. " +
                "시작할 때 PCAPdroid가 허용 여부를 묻습니다. 안드로이드는 VPN을 하나만 켤 수 있어 감시 중에는 이 앱의 인터넷 차단이 꺼집니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!installed) {
            OutlinedButton(onClick = { launch { context.startActivity(PcapdroidLink.installIntent()) } }) {
                Text("PCAPdroid 설치")
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { launch { startLauncher.launch(PcapdroidLink.startIntent(targetPackage)) } }) {
                    Text("이 앱만 PCAPdroid로 감시")
                }
                OutlinedButton(onClick = { launch { stopLauncher.launch(PcapdroidLink.stopIntent()) } }) {
                    Text("감시 중지")
                }
                OutlinedButton(onClick = {
                    val i = PcapdroidLink.openAppIntent(context)
                    if (i == null) error = "PCAPdroid를 열 수 없습니다. 앱 목록에서 직접 여세요."
                    else launch { context.startActivity(i) }
                }) { Text("PCAPdroid 열기") }
            }
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        error?.let { ErrorText(it) }
    }
}
