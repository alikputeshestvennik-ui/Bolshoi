package ru.alim.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import android.view.WindowManager
import android.widget.LinearLayout
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
    private var params: WindowManager.LayoutParams? = null
    private var titleView: TextView? = null
    private var reasonView: TextView? = null
    private var answerView: TextView? = null
    private var taskId = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Th.load()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        startForegroundNow()
        taskId = Prefs.codexActiveTask
        showWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getStringExtra("task_id")?.isNotBlank() == true) {
            taskId = intent.getStringExtra("task_id").orEmpty()
            Prefs.codexActiveTask = taskId
        } else if (taskId.isBlank()) {
            taskId = Prefs.codexActiveTask
        }
        pollJob?.cancel()
        pollJob = scope.launch { poll() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        root?.let { view -> runCatching { window?.removeView(view) } }
        root = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Codex · активность", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 11, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_mono)
            .setContentTitle("Codex работает")
            .setContentText("Reasoning и ответ — в плавающем окне")
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
        val wm = getSystemService(WindowManager::class.java) ?: return
        window = wm
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Th.c.card.toArgb())
                setStroke(dp(1), Th.c.accent.copy(alpha = 0.55f).toArgb())
            }
            elevation = dp(10).toFloat()
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleView = textView(12f, Th.c.accent.toArgb(), true).apply {
            text = "Codex · ждёт задачу"
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        head.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val close = textView(20f, Th.c.muted.toArgb(), false).apply {
            text = "×"
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, 0, 0)
            setOnClickListener { stopSelf() }
        }
        head.addView(close, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(head)
        reasonView = textView(11f, Th.c.muted.toArgb(), false).apply {
            text = "…"
            maxLines = 5
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        }
        card.addView(reasonView)
        answerView = textView(12f, Th.c.ink.toArgb(), false).apply {
            text = ""
            maxLines = 5
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        }
        card.addView(answerView)

        val layout = WindowManager.LayoutParams(
            dp(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(20)
            y = dp(120)
        }
        params = layout
        installDrag(card, wm, layout)
        runCatching { wm.addView(card, layout) }
        root = card
    }

    private fun installDrag(view: View, wm: WindowManager, layout: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
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
                    if (kotlin.math.abs(dx) > dp(3) || kotlin.math.abs(dy) > dp(3)) moved = true
                    if (moved) {
                        layout.x = startX + dx
                        layout.y = startY + dy
                        runCatching { wm.updateViewLayout(view, layout) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
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
            reasoning.isNotBlank() -> reasoning.takeLast(1400)
            status == "running" -> "…"
            else -> "Плавающее окно Codex"
        }
        answerView?.text = when {
            answer.isNotBlank() -> answer.takeLast(1800)
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
