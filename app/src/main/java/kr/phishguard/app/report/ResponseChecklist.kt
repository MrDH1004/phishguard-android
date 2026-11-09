package kr.phishguard.app.report

import android.content.Context

sealed interface ChecklistAction {
    data class Dial(val number: String) : ChecklistAction
    data class OpenUrl(val url: String) : ChecklistAction
    data object None : ChecklistAction
}

data class ChecklistItem(
    val id: String,
    val title: String,
    val description: String,
    val action: ChecklistAction,
)

/**
 * 피해 대응 체크리스트. 번호·주소는 공식 사이트에서 확인한 것만 넣었다.
 * - 1332(금융감독원 콜센터), pd.fss.or.kr 링크: https://fine.fss.or.kr (금융소비자 정보포털 파인 메인 화면)
 * - 118(KISA 불법스팸대응센터): https://news.seoul.go.kr/economy/archives/547027
 * - 112 신고 / 지급정지는 금융회사 요청: https://www.fsc.go.kr/no010106/24081 (금융위 보이스피싱 안내 보도자료)
 * - 명의도용방지서비스(가입사실현황조회·가입제한): https://www.msafer.or.kr
 * - 계좌정보통합관리서비스(어카운트인포, 본인계좌 일괄지급정지): https://www.payinfo.or.kr
 */
object ResponseChecklist {
    val items: List<ChecklistItem> = listOf(
        ChecklistItem(
            "police112", "경찰에 신고 (112)",
            "피해 사실을 신고하고 '사건사고사실확인원'을 받아 두세요. 은행에 피해구제를 신청할 때 필요합니다.",
            ChecklistAction.Dial("112"),
        ),
        ChecklistItem(
            "bank_freeze", "송금한 은행에 지급정지 요청",
            "돈을 보낸 은행 고객센터에 바로 전화해 지급정지를 요청하세요. 번호는 통장·카드 뒷면이나 은행 앱에 있습니다.",
            ChecklistAction.None,
        ),
        ChecklistItem(
            "fss1332", "금융감독원 상담 (1332)",
            "피해구제 절차와 다음에 할 일을 상담받을 수 있습니다.",
            ChecklistAction.Dial("1332"),
        ),
        ChecklistItem(
            "kisa118", "한국인터넷진흥원 118 상담",
            "악성 앱과 문자 등 피해 상담과 신고를 받습니다.",
            ChecklistAction.Dial("118"),
        ),
        ChecklistItem(
            "payinfo", "내 계좌 일괄 지급정지 (어카운트인포)",
            "내 이름으로 열린 계좌를 한 번에 조회하고, 피해가 우려되는 계좌를 직접 지급정지할 수 있습니다.",
            ChecklistAction.OpenUrl("https://www.payinfo.or.kr"),
        ),
        ChecklistItem(
            "msafer", "명의도용 방지 (엠세이퍼)",
            "내 명의로 개통된 휴대폰을 조회하고, 새 휴대폰 개통을 미리 막아 둘 수 있습니다.",
            ChecklistAction.OpenUrl("https://www.msafer.or.kr"),
        ),
        ChecklistItem(
            "pd_fss", "개인정보 노출자 등록 (금융감독원)",
            "신분증·개인정보가 노출됐다면 등록해 두세요. 내 명의로 대출이나 계좌를 만들 때 금융회사가 본인 확인을 더 엄격히 합니다.",
            ChecklistAction.OpenUrl("https://pd.fss.or.kr"),
        ),
    )

    fun completed(checked: Set<String>): Int = items.count { it.id in checked }
}

/** 체크 상태를 SharedPreferences "checklist"에 저장. */
object ChecklistStore {
    private const val PREF = "checklist"

    fun load(context: Context): Set<String> {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return ResponseChecklist.items.map { it.id }.filter { p.getBoolean(it, false) }.toSet()
    }

    fun set(context: Context, id: String, checked: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(id, checked).apply()
    }
}
