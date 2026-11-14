package kr.phishguard.app.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import kr.phishguard.app.R

/** 설정 > 앱 아이콘 숨기기 */
@Composable
fun IconHideSection() {
    val context = LocalContext.current
    var hidden by remember { mutableStateOf(IconHider.isHidden(context)) }
    var error by remember { mutableStateOf(false) }

    fun change(value: Boolean) {
        error = try {
            IconHider.setHidden(context, value)
            hidden = value
            if (value) requestTile(context)
            false
        } catch (e: RuntimeException) {
            Log.e("PG-Icon", "아이콘 설정 실패", e)
            true
        }
    }

    SectionTitle("앱 아이콘 숨기기")
    Row(
        Modifier.fillMaxWidth().toggleable(value = hidden, role = Role.Switch, onValueChange = ::change),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("홈 화면·앱 목록에서 아이콘 숨기기", Modifier.weight(1f))
        Switch(checked = hidden, onCheckedChange = null)
    }
    Text(
        "사기범이 원격으로 화면을 볼 때 이 앱을 찾아 지우기 어렵게 합니다. 숨기는 동안에는 화면 위에서 아래로 쓸어내려 빠른설정의 '피싱앱 검사기' 타일을 눌러 엽니다. 타일이 안 보이면 빠른설정의 연필(편집) 버튼을 눌러 타일을 추가하세요.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "Android 10 이상에서는 시스템이 '앱 정보'로 가는 대체 아이콘을 남길 수 있어 완전히 숨겨지지는 않습니다.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (error) ErrorText("아이콘 설정을 바꾸지 못했습니다. 다시 눌러 주세요.")
}

/** Android 13 이상: 빠른설정에 타일 추가 창을 띄운다. 그 아래 버전은 사용자가 직접 추가. */
private fun requestTile(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    try {
        val sbm = context.getSystemService(StatusBarManager::class.java) ?: return
        sbm.requestAddTileService(
            ComponentName(context, OpenAppTileService::class.java),
            context.getString(R.string.app_name),
            Icon.createWithResource(context, R.drawable.ic_tile),
            context.mainExecutor,
        ) { result -> Log.i("PG-Icon", "타일 추가 결과 $result") }
    } catch (e: RuntimeException) {
        Log.w("PG-Icon", "타일 추가 요청 실패", e)
    }
}
