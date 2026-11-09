package kr.phishguard.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private const val PREFS = "phishguard_ui"
private const val KEY_DONE = "onboarding_done"
private const val PAGE_COUNT = 3

fun isOnboardingDone(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

private fun setOnboardingDone(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
}

/** 처음 실행할 때만 보이는 3쪽 안내. 끝내거나 건너뛰면 onFinish 호출. */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf(0) }
    val finish = {
        setOnboardingDone(context)
        onFinish()
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (page) {
                    0 -> PageEmergency()
                    1 -> PageWhatItDoes()
                    else -> PagePermissions()
                }
            }
            PageIndicator(page)
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (page < PAGE_COUNT - 1) {
                    TextButton(onClick = finish) { Text("건너뛰기") }
                    Button(onClick = { page += 1 }) { Text("다음") }
                } else {
                    OutlinedButton(onClick = { page -= 1 }) { Text("이전") }
                    Button(onClick = finish) { Text("시작하기") }
                }
            }
        }
    }
}

@Composable
private fun PageIndicator(page: Int) {
    val desc = "${page + 1} / $PAGE_COUNT 페이지"
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clearAndSetSemantics { contentDescription = desc },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(PAGE_COUNT) { i ->
            val color = if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            Box(Modifier.padding(horizontal = 4.dp).size(10.dp).background(color, CircleShape))
        }
    }
}

@Composable
private fun PageTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun PageEmergency() {
    PageTitle("급하면 먼저")
    Body("보이스피싱이나 악성 앱이 의심되면 검사보다 아래 순서가 먼저입니다.")
    Body("1. 비행기 모드를 켜서 인터넷과 통화를 끊습니다.")
    Body("2. 112(경찰)나 금융감독원 1332에 신고하고 지급정지를 요청합니다.")
    Body("3. 악성 앱 상담은 KISA 118에서 받을 수 있습니다.")
    Body("비행기 모드를 끈 뒤 이 앱으로 검사하세요. 아래 버튼을 누르면 전화 앱이 열립니다.")
    EmergencyCallButtons()
}

@Composable
private fun PageWhatItDoes() {
    PageTitle("이 앱이 하는 일")
    Body("검사: 휴대폰에 설치된 앱을 살펴 의심스러운 앱을 찾아 위험도를 알려 줍니다.")
    Body("증거 저장: 삭제하기 전에 의심 앱의 설치 파일을 저장해 신고할 때 쓸 수 있게 합니다.")
    Body("인터넷 차단: 의심 앱이 인터넷을 쓰지 못하게 막습니다.")
    Body("삭제: 의심 앱을 지우는 순서를 단계별로 안내합니다.")
    Body("기록: 앱을 설치하거나 지운 내역을 모아 보여 줍니다.")
    Body("검사는 인터넷 없이 휴대폰 안에서만 이루어지며, 개인정보를 밖으로 보내지 않습니다.")
}

@Composable
private fun PagePermissions() {
    val context = LocalContext.current
    var notifGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifGranted = it }
    PageTitle("필요한 권한")
    Body("꼭 필요한 기능에만 권한을 요청합니다. 지금 허용하지 않아도 나중에 설정에서 켤 수 있습니다.")
    Body("알림: 새 앱이 설치되면 바로 알려 드립니다.")
    Body("사용 정보 접근: 앱별 데이터 사용량을 보여 주는 데 씁니다.")
    Body("VPN: 의심 앱의 인터넷을 막는 데 씁니다. 외부로 통신 내용을 보내지 않습니다.")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (notifGranted) {
            Body("알림 권한이 허용되었습니다.")
        } else {
            OutlinedButton(
                onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("알림 권한 허용하기") }
        }
    }
}
