package ru.alim.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Small always-on-top Codex card: current reasoning and the last answer.
 *
 * The service polls the authenticated loopback bridge, so it works while the
 * user is in another app.  It is only started when the user enables the
 * overlay in Settings and grants "display over other apps".
 */
class CodexOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null
    private var window: WindowManager? = null
    private var root: View? = null
    private var content: View? = null
    private var overlayHost: FrameLayout? = null
    private var openOverlay: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var titleView: TextView? = null
    private var reasonView: TextView? = null
    private var answerView: TextView? = null
    private var taskId = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        Th.load()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        startForegroundNow()
        taskId = Prefs.codexActiveTask
        if (!AppState.foreground) showWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getStringExtra("task_id")?.isNotBlank() == true) {
            taskId = intent.getStringExtra("task_id").orEmpty()
            Prefs.codexActiveTask = taskId
        } else if (taskId.isBlank()) {
            taskId = Prefs.codexActiveTask
        }
        setWindowVisible(!AppState.foreground)
        pollJob?.cancel()
        pollJob = scope.launch { poll() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        hideWindow()
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Codex · активность", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 11, Intent(this, MainActivity::class.java)
                .putExtra("open", "chat:" + taskId.ifBlank { Friends.CODEX })
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_mono)
            .setContentTitle("Codex работает")
            .setContentText("Окно появится при сворачивании приложения")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
        runCatching {
            ServiceCompat.startForeground(
                this, 4243, notification,
                if (Build.VERSION.SDK_INT >= 34) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        }
    }

    private fun showWindow() {
        if (root != null || AppState.foreground || !Settings.canDrawOverlays(this)) return
        val wm = getSystemService(WindowManager::class.java) ?: return
        window = wm
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(10), dp(8), dp(10), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Th.c.card.toArgb())
                setStroke(dp(1), Th.c.accent.copy(alpha = 0.6f).toArgb())
            }
            elevation = dp(6).toFloat()
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleView = textView(10f, Th.c.accent.toArgb(), true).apply {
            text = "Codex · ждёт задачу"
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        head.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val close = textView(16f, Th.c.muted.toArgb(), false).apply {
            text = "×"
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, 0, 0)
            setOnClickListener { stopSelf() }
        }
        head.addView(close, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(head)
        reasonView = textView(9f, Th.c.muted.toArgb(), false).apply {
            text = "…"
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(reasonView)
        answerView = textView(10f, Th.c.ink.toArgb(), false).apply {
            text = ""
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(answerView)

        val layout = WindowManager.LayoutParams(
            dp(232),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(14)
            y = dp(96)
        }
        params = layout
        installDrag(card, wm, layout) { showOpenAction() }
        val frame = FrameLayout(this).apply {
            addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        // WindowManager ожидает обычный корень окна; вложенный FrameLayout держит слой затемнения и кнопки.
        val windowRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        if (runCatching { wm.addView(windowRoot, layout) }.isSuccess) {
            root = windowRoot
            content = card
            overlayHost = frame
        }
    }

    private fun hideWindow() {
        hideOpenAction()
        root?.let { view -> runCatching { window?.removeView(view) } }
        root = null
        content = null
        overlayHost = null
        titleView = null
        reasonView = null
        answerView = null
        params = null
    }

    private fun setWindowVisible(visible: Boolean) {
        if (visible) showWindow() else hideWindow()
    }

    private fun installDrag(view: View, wm: WindowManager, layout: WindowManager.LayoutParams, onTap: () -> Unit) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        view.setOnClickListener { onTap() }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = layout.x; startY = layout.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) moved = true
                    if (moved) {
                        layout.x = startX + dx
                        layout.y = startY + dy
                        runCatching { wm.updateViewLayout(root ?: view, layout) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun showOpenAction() {
        if (openOverlay != null) return
        val frame = overlayHost ?: return
        val card = content ?: return
        val overlay = FrameLayout(this).apply {
            // Sibling Z order must put both the scrim and its button above the card.
            elevation = card.elevation + dp(1)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(150, 0, 0, 0))
            }
            isClickable = true
            setOnClickListener { hideOpenAction() }
        }
        val go = TextView(this).apply {
            text = "Перейти"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Th.c.onAccent.toArgb())
            gravity = Gravity.CENTER
            minWidth = dp(96)
            minHeight = dp(44)
            setPadding(dp(22), dp(9), dp(22), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Th.c.accent.toArgb())
            }
            elevation = dp(6).toFloat()
            setOnClickListener { openCodex() }
        }
        overlay.addView(go, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
        frame.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        openOverlay = overlay
    }

    private fun hideOpenAction() {
        openOverlay?.let { overlay -> overlayHost?.removeView(overlay) }
        openOverlay = null
    }

    private fun openCodex() {
        hideOpenAction()
        val id = taskId.ifBlank { Prefs.codexActiveTask }.ifBlank { Friends.CODEX }
        val intent = Intent(this, MainActivity::class.java)
            .putExtra("open", "chat:$id")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { startActivity(intent) }
    }

    private suspend fun poll() {
        while (scope.isActive) {
            val snapshot = withContext(Dispatchers.IO) {
                if (taskId.isBlank()) {
                    JSONObject().put("status", "waiting")
                } else {
                    runCatching { CodexGateway.status(this@CodexOverlayService, taskId) }
                        .getOrElse { JSONObject().put("status", "waiting").put("error", it.message.orEmpty()) }
                }
            }
            render(snapshot)
            val running = snapshot.optString("status") in setOf("queued", "running")
            delay(if (running) 800 else 1800)
        }
    }

    private fun render(snapshot: JSONObject) {
        val status = snapshot.optString("status")
        val reasoning = snapshot.optString("reasoning").trim()
        val answer = snapshot.optString("answer").trim()
        val error = snapshot.optString("error").trim()
        titleView?.text = when (status) {
            "queued" -> "Codex · в очереди"
            "running" -> "Codex · думает"
            "done" -> "Codex · готово"
            "cancelled" -> "Codex · остановлено"
            "failed", "timeout" -> "Codex · ошибка"
            "preparing" -> "Codex · готовлю окружение"
            else -> "Codex · ждёт задачу"
        }
        reasonView?.text = when {
            error.isNotBlank() -> error.takeLast(1000)
            reasoning.isNotBlank() -> reasoning.takeLast(700)
            status == "running" -> "…"
            else -> "Плавающее окно Codex"
        }
        answerView?.text = when {
            answer.isNotBlank() -> answer.takeLast(1100)
            status == "running" -> "Ответ появится здесь"
            status == "preparing" -> "Первый запуск: Linux, Python и Codex"
            else -> ""
        }
        answerView?.visibility = if (answerView?.text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun textView(size: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL = "codex_overlay"
        @Volatile private var instance: CodexOverlayService? = null

        /** Hide the overlay while Ten is open; show it again when the app goes to the background. */
        fun appVisible(visible: Boolean) {
            instance?.setWindowVisible(!visible)
        }

        fun start(ctx: Context, taskId: String = Prefs.codexActiveTask): Boolean {
            if (!Settings.canDrawOverlays(ctx)) return false
            Prefs.init(ctx)
            if (taskId.isNotBlank()) Prefs.codexActiveTask = taskId
            val intent = Intent(ctx, CodexOverlayService::class.java).putExtra("task_id", taskId)
            return runCatching {
                ContextCompat.startForegroundService(ctx, intent)
                true
            }.getOrDefault(false)
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, CodexOverlayService::class.java)) }
        }
    }
}
