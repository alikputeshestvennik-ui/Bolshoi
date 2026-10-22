package ru.alim.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import java.io.ByteArrayOutputStream

/**
 * «Руки и глаза»: служба специальных возможностей. Читает, что на экране (кнопки, тексты, поля),
 * нажимает, вводит текст, листает, жмёт «назад»/«домой», делает снимок экрана.
 * Сам ничего не решает — выполняет шаги, которые присылает HandsAgent.
 */
class Hands : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())

    /** Один видимый элемент экрана. */
    class El(val i: Int, val node: AccessibilityNodeInfo, val label: String, val kind: String, val rect: Rect, val editable: Boolean)

    private var frame: List<El> = emptyList()
    /** Пакет приложения, которое сейчас на экране. */
    @Volatile var pkg: String = ""; private set

    override fun onServiceConnected() { inst = this; HandsAgent.onServiceReady() }
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { inst = null; return super.onUnbind(intent) }
    override fun onDestroy() { inst = null; super.onDestroy() }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.packageName != null && (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
            pkg = e.packageName.toString()
        }
    }

    // ——— чтение экрана ———

    /** Снимок экрана как список пронумерованных элементов + их текст. Для модели. */
    fun observe(): String {
        val root = rootInActiveWindow ?: return "(экран пуст или недоступен)"
        val out = ArrayList<El>()
        val plain = StringBuilder()
        walk(root, out, plain, 0)
        frame = out
        val sb = StringBuilder()
        sb.append("Приложение: ").append(appLabel(pkg)).append(" (").append(pkg).append(")\n")
        sb.append("Элементы (номер — что это):\n")
        for (el in out) sb.append(el.i).append(". ").append(el.kind).append(if (el.editable) " [поле ввода]" else "")
            .append(": ").append(el.label.take(80)).append('\n')
        val text = plain.toString().trim()
        if (text.isNotEmpty()) sb.append("Текст на экране:\n").append(text.take(2500))
        return sb.toString().take(6000)
    }

    private fun walk(n: AccessibilityNodeInfo?, out: ArrayList<El>, plain: StringBuilder, depth: Int) {
        n ?: return
        if (depth > 40 || out.size > 120) return
        val r = Rect(); n.getBoundsInScreen(r)
        val visible = r.width() > 0 && r.height() > 0
        val t = (n.text ?: "").toString().trim()
        val desc = (n.contentDescription ?: "").toString().trim()
        val label = t.ifBlank { desc }
        val actionable = n.isClickable || n.isEditable || n.isCheckable || n.isScrollable || n.isLongClickable
        if (visible && (actionable || (n.isFocusable && label.isNotEmpty()))) {
            val kind = when {
                n.isEditable -> "поле"
                n.className?.contains("Button") == true -> "кнопка"
                n.isCheckable -> "переключатель" + (if (n.isChecked) " (вкл)" else " (выкл)")
                n.isScrollable -> "список"
                else -> "элемент"
            }
            out += El(out.size + 1, n, label.ifBlank { n.viewIdResourceName?.substringAfterLast('/') ?: "—" }, kind, r, n.isEditable)
        } else if (visible && t.isNotEmpty()) {
            plain.append(t).append('\n')
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), out, plain, depth + 1)
    }

    private fun appLabel(p: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString()
    }.getOrDefault(p)

    // ——— действия ———

    fun tap(i: Int): Boolean {
        val el = frame.firstOrNull { it.i == i } ?: return false
        // сначала пробуем «настоящее» нажатие по узлу, иначе жест по центру
        var node: AccessibilityNodeInfo? = el.node
        while (node != null && !node.isClickable) node = node.parent
        if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        return tapXY(el.rect.centerX().toFloat(), el.rect.centerY().toFloat())
    }

    fun tapXY(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build()
        return dispatchGesture(g, null, null)
    }

    fun setText(i: Int, text: String): Boolean {
        val el = frame.firstOrNull { it.i == i && it.editable } ?: frame.firstOrNull { it.editable } ?: return false
        el.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return el.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scroll(down: Boolean): Boolean {
        val sc = frame.map { it.node }.firstOrNull { it.isScrollable } ?: rootInActiveWindow ?: return false
        val act = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (sc.performAction(act)) return true
        // жестом: свайп по центру экрана
        val m = resources.displayMetrics
        val cx = m.widthPixels / 2f
        val y1 = if (down) m.heightPixels * 0.7f else m.heightPixels * 0.35f
        val y2 = if (down) m.heightPixels * 0.35f else m.heightPixels * 0.7f
        val p = Path().apply { moveTo(cx, y1); lineTo(cx, y2) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 300)).build(), null, null)
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)

    /** Открыть приложение по названию или пакету. */
    fun openApp(query: String): Boolean {
        val q = query.trim().lowercase()
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
        val match = apps.firstOrNull { it.packageName.equals(query, true) }
            ?: apps.map { it to pm.getApplicationLabel(it).toString() }
                .filter { it.second.lowercase().contains(q) || it.first.packageName.lowercase().contains(q) }
                .minByOrNull { it.second.length }?.first
            ?: return false
        val intent = pm.getLaunchIntentForPackage(match.packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return true
    }

    /** Снимок экрана → «сырой» base64 JPEG (без префикса). Доступно с Android 11. */
    suspend fun screenshot(): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val done = CompletableDeferred<Bitmap?>()
        runCatching {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(res: ScreenshotResult) {
                    val bmp = runCatching {
                        Bitmap.wrapHardwareBuffer(res.hardwareBuffer, res.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                    runCatching { res.hardwareBuffer.close() }
                    done.complete(bmp)
                }
                override fun onFailure(code: Int) { done.complete(null) }
            })
        }.onFailure { done.complete(null) }
        val bmp = done.await() ?: return null
        val scaled = run {
            val max = 1024
            val s = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
            if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        @Volatile var inst: Hands? = null
        val ready: Boolean get() = inst != null
        fun go(f: () -> Unit) = Handler(Looper.getMainLooper()).post(f)
    }
}
