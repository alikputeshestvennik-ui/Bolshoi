package ru.alim.agent

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Плашка поверх всех приложений, пока друг управляет телефоном: что он делает, кнопки «⏸», «👁», «Стоп».
 * Когда нужно подтверждение (отправить/заказать) — показывает карточку с «Подтвердить» и «Отмена».
 */
class HandsOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var confirmRoot: FrameLayout? = null
    private var stepView: TextView? = null
    private var pauseBtn: TextView? = null
    private var eyeBtn: TextView? = null
    private var confirmBox: LinearLayout? = null
    private var confirmText: TextView? = null

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    @SuppressLint("SetTextI18n")
    fun show() {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply { setColor(Color.parseColor("#F21C2B29")); cornerRadius = dp(22).toFloat() }
        }
        val dot = TextView(ctx).apply { text = "📱"; textSize = 16f }
        val name = Friends.get(HandsAgent.fid).name
        stepView = TextView(ctx).apply {
            setTextColor(Color.WHITE); textSize = 13f; maxLines = 2
            text = "$name управляет телефоном"
        }
        fun circle(label: String, onClick: () -> Unit) = TextView(ctx).apply {
            text = label; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            val sz = dp(38)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { leftMargin = dp(6) }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#33FFFFFF")) }
            setOnClickListener { onClick() }
        }
        pauseBtn = circle("⏸") { HandsAgent.togglePause(); update() }
        eyeBtn = circle("👁") { HandsAgent.eyes = !HandsAgent.eyes; update() }
        val stop = TextView(ctx).apply {
            text = "Стоп"; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); setPadding(dp(16), dp(9), dp(16), dp(9))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) }
            background = GradientDrawable().apply { setColor(Color.parseColor("#E5484D")); cornerRadius = dp(19).toFloat() }
            setOnClickListener { HandsAgent.stop() }
        }
        bar.addView(dot, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        bar.addView(stepView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(pauseBtn); bar.addView(eyeBtn); bar.addView(stop)

        // карточка подтверждения (скрыта по умолчанию)
        confirmText = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 14f; setPadding(0, 0, 0, dp(10)) }
        val yes = Button(ctx).apply {
            text = "Подтвердить"; isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.parseColor("#2FA98B")); cornerRadius = dp(16).toFloat() }
            setOnClickListener { HandsAgent.answerConfirm(true) }
        }
        val no = Button(ctx).apply {
            text = "Отмена"; isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.parseColor("#33FFFFFF")); cornerRadius = dp(16).toFloat() }
            setOnClickListener { HandsAgent.answerConfirm(false) }
        }
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(no, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            addView(yes, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
        }
        confirmBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(Color.parseColor("#F2243139")); cornerRadius = dp(20).toFloat() }
            addView(confirmText); addView(btnRow)
        }

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val frame = FrameLayout(ctx).apply { setPadding(dp(10), 0, dp(10), 0); addView(col) }
        root = frame

        val type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP; y = dp(40) }
        runCatching { wm.addView(frame, lp) }
        // карточка подтверждения — отдельным окном внизу, чтобы было видно, ЧТО отправляется
        val cf = FrameLayout(ctx).apply { setPadding(dp(10), 0, dp(10), 0); addView(confirmBox) }
        confirmRoot = cf
        val lp2 = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM; y = dp(28) }
        runCatching { wm.addView(cf, lp2) }
        update()
    }

    @SuppressLint("SetTextI18n")
    fun update() = main.post {
        val name = Friends.get(HandsAgent.fid).name
        stepView?.text = if (HandsAgent.step.isNotBlank()) "$name: ${HandsAgent.step}" else "$name управляет телефоном"
        pauseBtn?.text = if (HandsAgent.paused) "▶" else "⏸"
        eyeBtn?.alpha = if (HandsAgent.eyes) 1f else 0.5f
        val confirming = HandsAgent.state == HandsAgent.St.CONFIRM && HandsAgent.confirmText.isNotBlank()
        confirmText?.text = HandsAgent.confirmText
        confirmBox?.visibility = if (confirming) View.VISIBLE else View.GONE
        confirmRoot?.visibility = if (confirming) View.VISIBLE else View.GONE
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }; root = null
        confirmRoot?.let { runCatching { wm.removeView(it) } }; confirmRoot = null
    }
}
