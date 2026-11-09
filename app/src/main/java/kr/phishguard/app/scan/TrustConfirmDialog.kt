package kr.phishguard.app.scan

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** 위험 높음·중간 앱을 안전앱으로 표시하기 전에 한 번 더 묻는 확인창. */
@Composable
fun TrustConfirmDialog(app: AppInfo, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("정말 안전한 앱입니까?") },
        text = {
            Text(
                "이 앱은 위험 ${app.riskLevel.label} 판정입니다. 사기범은 '검찰청', '은행', 유명 앱 이름을 흉내 냅니다. " +
                    "직접 설치했고 출처를 아는 앱일 때만 안전앱으로 표시하세요.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("안전앱으로 표시") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
