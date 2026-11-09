package kr.phishguard.app.remove

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.phishguard.app.report.Hashing
import kr.phishguard.app.scan.AppInfo
import kr.phishguard.app.ui.ErrorText
import kr.phishguard.app.ui.RiskLow
import java.io.File

private const val TAG = "PG-Evidence"

/**
 * 앱을 지우지 않고 증거 ZIP만 저장하는 섹션 (앱 상세 화면용).
 * 삭제 흐름 1단계와 같은 EvidenceExporter를 쓴다.
 */
@Composable
fun EvidenceSaveSection(app: AppInfo) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var savedTo by rememberSaveable(app.packageName) { mutableStateOf<String?>(null) }
    var fileName by rememberSaveable(app.packageName) { mutableStateOf<String?>(null) }
    var zipSha by rememberSaveable(app.packageName) { mutableStateOf("") }
    var shareFailed by rememberSaveable { mutableStateOf(false) }

    fun runExport() {
        busy = true
        error = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { EvidenceExporter.export(context, app) }
                savedTo = result.savedTo
                fileName = result.fileName
                zipSha = result.zipSha256
            } catch (e: java.io.IOException) {
                Log.w(TAG, "evidence export failed (IO)", e)
                error = "증거 파일을 저장하지 못했습니다. 저장 공간이 충분한지 확인한 뒤 다시 눌러 주세요."
            } catch (e: SecurityException) {
                Log.w(TAG, "evidence export failed (permission)", e)
                error = "저장 권한이 없어 증거 파일을 저장하지 못했습니다. 다시 누르고 권한 요청 창에서 '허용'을 눌러 주세요."
            } catch (e: RuntimeException) {
                Log.e(TAG, "evidence export failed", e)
                error = "증거 파일을 저장하는 중 문제가 생겼습니다. 잠시 후 다시 눌러 주세요."
            } finally {
                busy = false
            }
        }
    }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runExport()
        else error = "저장소 권한이 거부되어 다운로드 폴더에 저장할 수 없습니다. 다시 누르고 권한 요청 창에서 '허용'을 눌러 주세요."
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                if (EvidenceExporter.needsLegacyPermission() &&
                    context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                ) {
                    storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    runExport()
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "저장 중…" else if (savedTo != null) "증거 ZIP 다시 저장" else "증거 ZIP만 저장 (삭제 안 함)") }

        Text(
            "앱을 지우지 않고 APK, 권한 목록, 설치 정보, 데이터 사용량을 ZIP 하나로 다운로드 폴더에 저장합니다. 신고·상담 후 삭제할 때 쓰세요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        error?.let { ErrorText(it) }

        val name = fileName
        val where = savedTo
        if (name != null && where != null) {
            Text(
                "저장 완료: $where",
                color = RiskLow,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (zipSha.isNotEmpty()) {
                SelectionContainer {
                    Column {
                        Text("ZIP SHA-256: ${Hashing.short(zipSha)}", style = MaterialTheme.typography.bodySmall)
                        Text(zipSha, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    shareFailed = false
                    try {
                        val file = File(File(context.cacheDir, "evidence"), name)
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "증거 공유"))
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "share failed (file)", e)
                        shareFailed = true
                    } catch (e: ActivityNotFoundException) {
                        Log.w(TAG, "share failed (no target)", e)
                        shareFailed = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("증거 파일 공유") }
            if (shareFailed) {
                ErrorText("공유할 수 없습니다. 파일 앱의 다운로드 폴더에서 '$name'을 직접 보내 주세요.")
            }
        }
    }
}
