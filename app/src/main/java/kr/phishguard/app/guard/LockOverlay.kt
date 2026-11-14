package kr.phishguard.app.guard

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kr.phishguard.app.background.InstallWatchWorker
import kr.phishguard.app.family.FamilyNotify
import kr.phishguard.app.ui.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 잠금 상태 저장 (재부팅·앱 재시작 후에도 이어서 잠금) */
object LockStore {
    private const val PREF = "guard_lock"

    data class Lock(
        val until: Long,
        val packageName: String,
        val label: String,
        val byName: String,
        /** 잠금이 풀린 뒤 삭제를 권할 패키지 (의심 앱 + 설치된 메신저) */
        val cleanup: List<String>,
        val evidencePath: String?,
        /** 잠금이 끝나 정리 안내까지 마쳤는지 */
        val finished: Boolean = false,
    )

    fun save(context: Context, l: Lock) {
        prefs(context).edit()
            .putLong("until", l.until).putString("pkg", l.packageName).putString("label", l.label)
            .putString("by", l.byName).putString("cleanup", l.cleanup.joinToString(","))
            .putString("evidence", l.evidencePath).putBoolean("finished", l.finished)
            .apply()
    }

    fun load(context: Context): Lock? {
        val p = prefs(context)
        val pkg = p.getString("pkg", null) ?: return null
        return Lock(
            until = p.getLong("until", 0),
            packageName = pkg,
            label = p.getString("label", pkg) ?: pkg,
            byName = p.getString("by", "가족") ?: "가족",
            cleanup = p.getString("cleanup", "").orEmpty().split(',').filter { it.isNotBlank() },
            evidencePath = p.getString("evidence", null),
            finished = p.getBoolean("finished", false),
        )
    }

    fun active(context: Context, now: Long = System.currentTimeMillis()): Lock? = load(context)?.takeIf { it.until > now }

    fun clear(context: Context) = prefs(context).edit().clear().apply()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}

/**
 * 1시간 잠금 화면. 상시 서비스(부모 폰) 프로세스에서 "다른 앱 위에 그리기" 창으로 화면 전체를 덮는다.
 * 홈·뒤로 버튼으로 닫히지 않고, 시간이 지나면 저절로 풀린 뒤 정리 화면(의심 앱·메신저 삭제)을 연다.
 *
 * 전화 (리뷰 H2·M4): 통화 중이거나 전화가 오는 동안에는 잠금 화면을 숨겨 통화 화면(끊기·키패드)을 쓸 수 있게 하고,
 * 통화가 끝나면 다시 덮는다. 112 버튼은 전화를 걸 시간(1분)만 비켜 준다 — 누를 때마다 자유 시간이 생기지 않도록
 * 실제 통화가 없으면 다시 덮는다.
 *
 * 시스템 잠금화면(비밀번호)을 1시간 강제로 잠그는 것은 일반 앱이 할 수 없어 이 방식을 쓴다.
 */
object LockOverlay {
    private const val TAG = "PG-LockOverlay"
    private const val DIAL_PAUSE_MS = 60_000L
    const val TAB_CLEANUP = "cleanup"

    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var countdown: TextView? = null
    private var shownLockKey: String? = null
    private var ticking = false

    @Volatile
    private var pauseUntil = 0L

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * 잠금 시작/재개. 메인 스레드가 아닌 곳에서 부르면 잠금 창이 실제로 한 번 그려질 때까지(최대 3초) 기다린다
     * (Android 15는 "보이는" 겹침 창이 있어야 백그라운드에서 차단 VPN을 시작할 수 있다). 못 띄우면 false.
     */
    fun show(context: Context): Boolean {
        val app = context.applicationContext
        if (!canDraw(app)) return false
        if (LockStore.active(app) == null) return false
        if (Looper.myLooper() == Looper.getMainLooper()) {
            startTicking(app)
            return view != null
        }
        val drawn = CountDownLatch(1)
        main.post {
            startTicking(app)
            val v = view
            if (v == null) {
                drawn.countDown()
            } else {
                // 첫 그리기가 끝난 뒤에 풀어 준다
                v.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
                    override fun onDraw() {
                        drawn.countDown()
                        v.post { v.viewTreeObserver.removeOnDrawListener(this) }
                    }
                })
                v.invalidate()
            }
        }
        drawn.await(3, TimeUnit.SECONDS)
        return view != null
    }

    /** 앱을 다시 켰거나 재부팅 뒤: 잠금이 남아 있으면 다시 덮고, 그 사이 끝났으면 정리 안내 */
    fun restoreIfActive(context: Context) {
        val app = context.applicationContext
        val l = LockStore.load(app) ?: return
        if (l.until > System.currentTimeMillis()) {
            show(app)
        } else if (!l.finished) {
            main.post { finish(app) }
        }
    }

    private fun inCall(context: Context): Boolean {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        return when (am.mode) {
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE -> true
            else -> false
        }
    }

    /** 1초마다: 끝났으면 정리, 통화 중·112 다이얼 중이면 숨김, 아니면 덮기 */
    private fun startTicking(context: Context) {
        if (!ticking) {
            ticking = true
            main.post(object : Runnable {
                override fun run() {
                    val now = System.currentTimeMillis()
                    val l = LockStore.load(context)
                    if (l == null || l.until <= now) {
                        ticking = false
                        detach(context)
                        if (l != null && !l.finished) finish(context)
                        return
                    }
                    val hide = inCall(context) || now < pauseUntil
                    if (hide) detach(context) else attach(context, l)
                    countdown?.text = "남은 시간 ${GuardPolicy.remainingText(l.until, now)}"
                    main.postDelayed(this, 1000)
                }
            })
        }
        val l = LockStore.active(context) ?: return
        if (!inCall(context) && System.currentTimeMillis() >= pauseUntil) attach(context, l)
    }

    @SuppressLint("SetTextI18n")
    private fun attach(context: Context, lock: LockStore.Lock) {
        val key = "${lock.packageName}|${lock.until}"
        if (view != null && shownLockKey == key) return
        if (view != null) detach(context) // 다른 앱으로 다시 잠김: 새 내용으로 다시 그림
        if (!canDraw(context)) return
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
        fun text(s: String, size: Float, bold: Boolean = false, color: Int = Color.WHITE) = TextView(context).apply {
            this.text = s
            setTextColor(color)
            textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(8), 0, dp(8))
        }

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(32))
        }
        col.addView(text("⚠ 보이스피싱 의심", 34f, bold = true, color = Color.rgb(255, 214, 0)))
        col.addView(text("이 휴대폰에서 보이스피싱 의심 앱이 발견되어 ${lock.byName}이(가) 1시간 동안 잠갔습니다.", 20f))
        col.addView(text("의심 앱: ${lock.label}", 22f, bold = true))
        col.addView(
            text(
                "주변의 가족이나 아는 사람에게 이 화면을 보여 주고, '${lock.label}' 앱을 직접 설치했는지 꼭 물어보세요.",
                22f, bold = true, color = Color.rgb(255, 214, 0),
            ),
        )
        col.addView(text("경찰·검찰·금융감독원·은행은 앱 설치나 송금을 시키지 않습니다. 전화로 시키는 사람이 있으면 끊으세요.", 18f))
        val cd = text("", 40f, bold = true)
        cd.gravity = Gravity.CENTER
        col.addView(cd)
        col.addView(text("시간이 지나면 저절로 풀립니다. 이 앱과 메신저의 인터넷은 끊어 두었습니다. 전화가 오면 받을 수 있습니다.", 16f, color = Color.rgb(230, 230, 230)))
        val call = Button(context).apply {
            this.text = "112 긴급 전화"
            textSize = 22f
            setOnClickListener { emergency(context) }
        }
        col.addView(call, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72)).apply { topMargin = dp(16) })

        val root = ScrollView(context).apply {
            setBackgroundColor(Color.rgb(120, 0, 0))
            isFillViewport = true
            addView(col)
            // 화면 전체를 덮는 창이라 아래 앱으로 터치가 가지 않는다
            isFocusableInTouchMode = true
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        )
        try {
            wm.addView(root, params)
        } catch (e: RuntimeException) {
            Log.e(TAG, "잠금 화면 표시 실패", e)
            return
        }
        view = root
        countdown = cd
        shownLockKey = key
        cd.text = "남은 시간 ${GuardPolicy.remainingText(lock.until, System.currentTimeMillis())}"
    }

    private fun detach(context: Context) {
        val v = view ?: return
        try {
            context.getSystemService(WindowManager::class.java)?.removeView(v)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "잠금 화면 제거 실패", e)
        }
        view = null
        countdown = null
        shownLockKey = null
    }

    /** 112: 전화를 걸 시간(1분)만 비켜 준다. 통화가 이어지면 통화가 끝날 때까지 숨긴다 */
    private fun emergency(context: Context) {
        pauseUntil = System.currentTimeMillis() + DIAL_PAUSE_MS
        detach(context)
        try {
            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "전화 앱 없음", e)
        }
    }

    /**
     * 자녀의 삭제 요청: 시스템 삭제 확인 창이 보이게 잠금 화면을 최대 [ms] 동안 비켜 준다 (112 버튼과 같은 방식).
     * 통화가 아니면 그 시간이 지나거나 [resume]을 부르면 1초 안에 다시 덮는다. 메인 스레드가 아니면 실제로 걷힐 때까지 기다린다.
     */
    fun pause(context: Context, ms: Long) {
        val app = context.applicationContext
        pauseUntil = System.currentTimeMillis() + ms
        val done = CountDownLatch(1)
        main.post {
            detach(app)
            done.countDown()
        }
        if (Looper.myLooper() != Looper.getMainLooper()) done.await(2, TimeUnit.SECONDS)
    }

    /** [pause]를 끝내고 잠금이 남아 있으면 다시 덮는다 */
    fun resume() {
        pauseUntil = 0
    }

    /** 시간이 다 됨: 풀고 정리 화면으로 (한 번만) */
    private fun finish(context: Context) {
        detach(context)
        val lock = LockStore.load(context) ?: return
        if (lock.finished) return
        LockStore.save(context, lock.copy(finished = true))
        FamilyNotify.post(
            context, 3201, "잠금이 풀렸습니다",
            "'${lock.label}' 앱과 사기범이 쓰는 메신저를 지우세요. 눌러서 정리 화면을 여세요.",
            tab = TAB_CLEANUP,
        )
        try {
            // 다른 앱 위에 그리기 권한이 있으면 백그라운드에서도 화면을 열 수 있다
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .putExtra(InstallWatchWorker.EXTRA_OPEN_TAB, TAB_CLEANUP)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "정리 화면 열기 실패 (알림으로 대신)", e)
        }
    }
}
