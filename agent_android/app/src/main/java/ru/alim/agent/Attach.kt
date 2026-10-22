package ru.alim.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Вложения к задаче: текстовые файлы читаются как текст, фото и PDF «смотрит» модель с распознаванием картинок
 * (Claude через gptunnel) и пересказывает — так с ними работает любая основная модель, в том числе яндексовская.
 */
object Attach {
    class Att(val uri: Uri, val name: String, val mime: String, val size: Long)

    /** Выбрано, но ещё не отправлено агенту. */
    val pending = mutableStateListOf<Att>()
    /** Отправлено с последней задачей — их можно переслать. */
    val sent = ArrayList<Att>()

    fun add(ctx: Context, uris: List<Uri>) {
        uris.forEach { u ->
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            var name = u.lastPathSegment ?: "файл"; var size = 0L
            runCatching {
                ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) { c.getString(0)?.let { name = it }; size = c.getLong(1) }
                }
            }
            val mime = ctx.contentResolver.getType(u) ?: guess(name)
            if (pending.none { it.uri == u }) pending += Att(u, name, mime, size)
        }
    }

    private fun guess(n: String) = when (n.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "pdf" -> "application/pdf"
        "txt", "md", "log" -> "text/plain"; "csv" -> "text/csv"; "json" -> "application/json"; else -> "application/octet-stream"
    }

    private fun isText(a: Att) = a.mime.startsWith("text/") || a.mime in setOf("application/json", "application/xml", "application/csv") ||
        a.name.substringAfterLast('.', "").lowercase() in setOf("txt", "md", "csv", "json", "xml", "log", "kt", "py", "js", "html", "yml", "yaml")

    /** Забрать вложения для задачи: текст для модели (что за файлы и что в них). Медленно — звать в фоне. */
    fun consume(ctx: Context, list: List<Att>, onStep: (String) -> Unit): String {
        if (list.isEmpty()) return ""
        sent.clear(); sent.addAll(list)
        val sb = StringBuilder("ВЛОЖЕНИЯ (${list.size}):\n")
        list.forEachIndexed { i, a ->
            sb.append("${i + 1}. ${a.name} (${a.mime}${if (a.size > 0) ", ${a.size / 1024} КБ" else ""})\n")
            val body = runCatching {
                when {
                    isText(a) -> {
                        onStep("Читаю ${a.name}")
                        val txt = ctx.contentResolver.openInputStream(a.uri)?.use { s -> s.readBytes().let { b -> String(b, 0, minOf(b.size, 400_000), Charsets.UTF_8) } }.orEmpty()
                        "Текст файла:\n" + txt.take(15000) + if (txt.length > 15000) "\n…(обрезано)" else ""
                    }
                    a.name.endsWith(".docx", true) -> { onStep("Читаю ${a.name}"); "Документ Word (разметка: # заголовки, **жирный**, | таблицы |):\n" + Docs.read(ctx, a.uri, a.name).take(15000) }
                    a.name.endsWith(".xlsx", true) -> { onStep("Читаю ${a.name}"); "Таблица Excel (JSON листов и строк, формулы через =):\n" + Docs.read(ctx, a.uri, a.name).take(15000) }
                    a.mime.startsWith("image/") -> { onStep("Смотрю ${a.name}"); "Что на изображении:\n" + look(listOfNotNull(image(ctx, a.uri))) }
                    a.mime == "application/pdf" -> { onStep("Читаю PDF ${a.name}"); "Содержимое PDF (первые страницы):\n" + look(pdfPages(ctx, a.uri, 3)) }
                    else -> "(файл такого типа прочитать нельзя, но его можно переслать)"
                }
            }.getOrElse { "(не удалось прочитать: ${it.message?.take(100)})" }
            sb.append(body).append("\n\n")
        }
        sb.append("Вложения — это контекст к просьбе Алима. Делай с ними то, что он просит (ответить, найти, поправить, посчитать). ")
        sb.append("Пересылать (attachment_send) — ТОЛЬКО если он прямо сказал отправить или переслать.\n")
        sb.append("Просит найти по фото — возьми из описания бренд, модель, цвет, надписи и ищи: товар — shop_search, место — maps_search или web_search, остальное — web_search.\n")
        return sb.toString()
    }

    private fun jpegDataUrl(bmp: Bitmap): String {
        val max = 1280
        val k = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
        val b = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp
        val out = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun image(ctx: Context, uri: Uri): String? {
        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
        var s = 1; while (maxOf(opt.outWidth, opt.outHeight) / s > 2560) s *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) } ?: return null
        return jpegDataUrl(bmp)
    }

    private fun pdfPages(ctx: Context, uri: Uri, n: Int): List<String> {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
        return pfd.use { fd ->
            PdfRenderer(fd).use { r ->
                (0 until minOf(n, r.pageCount)).map { i ->
                    r.openPage(i).use { p ->
                        val k = 1600f / maxOf(p.width, p.height)
                        val bmp = Bitmap.createBitmap((p.width * k).toInt(), (p.height * k).toInt(), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(AColor.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        jpegDataUrl(bmp)
                    }
                }
            }
        }
    }

    /** Модель с распознаванием изображений: Claude через gptunnel (яндексовские модели картинки не видят). */
    private fun look(urls: List<String>): String {
        if (urls.isEmpty()) return "(не удалось открыть)"
        val model = Prefs.bg.takeUnless { Llm.isYandex(it) } ?: Prefs.fastModel.takeUnless { Llm.isYandex(it) } ?: Prefs.smartModel.takeUnless { Llm.isYandex(it) } ?: "claude-5.5-haiku"
        val content = JSONArray().put(JSONObject().put("type", "text").put("text",
            "Опиши по-русски, что на изображении, и перепиши весь видимый текст как есть. Если это вещь или товар — назови тип, бренд и модель как можно точнее " +
                "(по логотипу, надписям, дизайну), цвет, материал; если место или здание — что это и где, если узнаёшь. В конце строка «Для поиска: …» — 3-6 слов, по которым это искать. Коротко, по делу."))
        urls.forEach { content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", it))) }
        val r = Llm.chat(model, JSONArray().put(JSONObject().put("role", "user").put("content", content)), 1500, json = false)
        return r.text.trim().take(6000)
    }
}

/** Кнопка выбора файлов: возвращает функцию, открывающую системный выбор. */
@Composable
fun rememberAttachPicker(): () -> Unit {
    val ctx = LocalContext.current
    val l = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) Attach.add(ctx, uris) }
    return { l.launch(arrayOf("*/*")) }
}

/** Плашки выбранных файлов с крестиком. */
@Composable
fun AttachChips() {
    if (Attach.pending.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Attach.pending.toList().forEach { a ->
            Row(Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(Card).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(a.name, style = body(13, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
                Box(Modifier.size(36.dp).clickable(onClickLabel = "Убрать") { Attach.pending.remove(a) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, "Убрать", tint = Muted, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
