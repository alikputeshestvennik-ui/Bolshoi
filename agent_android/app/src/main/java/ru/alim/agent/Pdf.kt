package ru.alim.agent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * PDF: создать (из той же разметки, что Word), прочитать (текст, поля форм; скан — глазами модели),
 * поправить (заменить текст на месте, вписать, заполнить форму, удалить/повернуть/переставить страницы, склеить, водяной знак).
 * Оригинал не трогаем — результат всегда новым файлом.
 */
object Pdf {
    private var inited = false
    private fun init(ctx: Context) { if (!inited) { PDFBoxResourceLoader.init(ctx.applicationContext); inited = true } }

    private fun open(ctx: Context, uri: Uri): PDDocument {
        init(ctx)
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("файл не открылся")
        return PDDocument.load(bytes).also { if (it.isEncrypted) it.isAllSecurityToBeRemoved = true }
    }

    // ———————————————— чтение ————————————————

    /** Текст по страницам с метками «— стр. N —», поля формы — в конце. */
    fun text(ctx: Context, uri: Uri, maxPages: Int = 40): String = open(ctx, uri).use { doc -> textOf(doc, maxPages) }

    private fun textOf(doc: PDDocument, maxPages: Int): String = buildString {
        val st = PDFTextStripper().apply { sortByPosition = true }
        val n = doc.numberOfPages
        for (p in 1..minOf(n, maxPages)) {
            st.startPage = p; st.endPage = p
            append("— стр. $p —\n").append(st.getText(doc).trim()).append("\n\n")
        }
        if (n > maxPages) append("…(ещё ${n - maxPages} стр. не показаны)\n")
        val form = fields(doc)
        if (form.isNotBlank()) append("Поля формы (можно заполнить pdf_edit fill):\n").append(form)
    }.trim()

    private fun fields(doc: PDDocument): String = runCatching {
        val af = doc.documentCatalog.acroForm ?: return ""
        af.fieldTree.mapNotNull { f -> f.fullyQualifiedName?.let { "- «$it»" + (f.valueAsString?.takeIf { v -> v.isNotBlank() }?.let { v -> " = $v" } ?: "") } }.take(60).joinToString("\n")
    }.getOrDefault("")

    /** Для вложения: текстовый PDF — текстом, скан (текста почти нет) — описанием страниц от модели со зрением. */
    fun readForModel(ctx: Context, uri: Uri, scan: () -> String): String {
        val (pages, txt) = runCatching { open(ctx, uri).use { d -> d.numberOfPages to textOf(d, 40) } }.getOrElse { 0 to "" }
        val letters = txt.replace(Regex("— стр\\. \\d+ —|\\s"), "").length
        return if (pages == 0 || letters < 60 * minOf(pages, 3))
            "PDF${if (pages > 0) ", $pages стр." else ""}, текстового слоя нет (скан) — что на первых страницах:\n" + scan()
        else "PDF, $pages стр. Текст:\n" + txt.take(15000) + if (txt.length > 15000) "\n…(обрезано)" else ""
    }

    // ———————————————— создание ————————————————

    /** Разметка как у docx: «# », «## », «### », «- пункт», «1. », «**жирный**», таблицы «| a | b |», «---» — линия. */
    fun create(text: String): ByteArray {
        val doc = PdfDocument()
        val W = 595; val H = 842; val M = 48f
        var pageNo = 0; var page: PdfDocument.Page? = null; var y = M
        val foot = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = Color.GRAY }
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            page!!.canvas.drawText("$pageNo", W - M, H - 24f, foot)
            y = M
        }
        newPage()
        fun paint(size: Float, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; color = Color.BLACK; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
        fun spans(s: String): CharSequence { val sb = SpannableStringBuilder()
            s.split("**").forEachIndexed { i, p -> val st = sb.length; sb.append(p)
                if (i % 2 == 1 && p.isNotEmpty()) sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, 0) }
            return sb }
        fun layout(cs: CharSequence, tp: TextPaint, w: Int): StaticLayout =
            StaticLayout.Builder.obtain(cs, 0, cs.length, tp, maxOf(w, 20)).setLineSpacing(0f, 1.15f).build()
        // абзац с переносом на следующую страницу по строкам
        fun draw(l: StaticLayout, x: Float) {
            var line = 0
            while (line < l.lineCount) {
                val top = l.getLineTop(line)
                if (y + (l.getLineBottom(line) - top) > H - M && y > M) { newPage(); continue }
                var last = line
                while (last + 1 < l.lineCount && y + (l.getLineBottom(last + 1) - top) <= H - M) last++
                val c: Canvas = page!!.canvas
                c.save(); c.translate(x, y - top)
                c.clipRect(0f, top.toFloat(), l.width.toFloat(), l.getLineBottom(last).toFloat())
                l.draw(c); c.restore()
                y += l.getLineBottom(last) - top; line = last + 1
            }
        }
        val full = (W - 2 * M).toInt()
        val lines = text.replace("\r", "").split('\n')
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]; val t = raw.trim()
            when {
                t.isEmpty() -> y += 6f
                t.startsWith("|") -> {
                    val rows = ArrayList<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        val r = lines[i].trim().trim('|')
                        if (!Regex("^[\\s|:-]+$").matches(r)) rows += r.split('|').map { it.trim() }
                        i++
                    }
                    i--
                    if (rows.isEmpty()) { i++; continue }
                    val n = rows.maxOf { it.size }.coerceAtLeast(1)
                    val cw = full / n
                    val line = Paint().apply { color = Color.rgb(150, 150, 150); strokeWidth = 0.6f; style = Paint.Style.STROKE }
                    val head = Paint().apply { color = Color.rgb(236, 236, 234) }
                    rows.forEachIndexed { ri, r ->
                        val ls = (0 until n).map { k -> layout(spans(r.getOrElse(k) { "" }), paint(9.5f, ri == 0), cw - 8) }
                        val h = ls.maxOf { it.height } + 8f
                        if (y + h > H - M) newPage()
                        val c = page!!.canvas
                        if (ri == 0) c.drawRect(M, y, M + cw * n, y + h, head)
                        ls.forEachIndexed { k, l ->
                            val x = M + k * cw
                            c.drawRect(x, y, x + cw, y + h, line)
                            c.save(); c.translate(x + 4f, y + 4f); l.draw(c); c.restore()
                        }
                        y += h
                    }
                    y += 8f
                }
                t == "---" || t == "***" -> { if (y + 10 > H - M) newPage()
                    page!!.canvas.drawLine(M, y + 4f, W - M, y + 4f, Paint().apply { color = Color.rgb(150, 150, 150); strokeWidth = 0.6f }); y += 12f }
                t.startsWith("### ") -> { y += 4f; draw(layout(spans(t.drop(4)), paint(12.5f, true), full), M); y += 2f }
                t.startsWith("## ") -> { y += 8f; draw(layout(spans(t.drop(3)), paint(15f, true), full), M); y += 3f }
                t.startsWith("# ") -> { if (y > M) y += 10f; draw(layout(spans(t.drop(2)), paint(20f, true), full), M); y += 6f }
                Regex("^[-*•]\\s+").containsMatchIn(t) -> {
                    val body = t.replace(Regex("^[-*•]\\s+"), "")
                    val l = layout(spans(body), paint(11f), full - 16)
                    if (y + l.getLineBottom(0) > H - M) newPage()
                    page!!.canvas.drawText("•", M + 3f, y + l.getLineBaseline(0), paint(11f))
                    draw(l, M + 16f); y += 2f
                }
                Regex("^\\d+[.)]\\s+").containsMatchIn(t) -> {
                    val num = Regex("^\\d+[.)]").find(t)!!.value
                    val l = layout(spans(t.replace(Regex("^\\d+[.)]\\s+"), "")), paint(11f), full - 22)
                    if (y + l.getLineBottom(0) > H - M) newPage()
                    page!!.canvas.drawText(num, M, y + l.getLineBaseline(0), paint(11f))
                    draw(l, M + 22f); y += 2f
                }
                else -> { draw(layout(spans(t), paint(11f), full), M); y += 4f }
            }
            i++
        }
        page?.let { doc.finishPage(it) }
        val out = ByteArrayOutputStream(); doc.writeTo(out); doc.close()
        return out.toByteArray()
    }

    // ———————————————— правка ————————————————

    /** Шрифт с кириллицей: встроенный в PDFBox Liberation Sans, иначе системные. */
    private fun font(doc: PDDocument): PDFont {
        runCatching { PDFBoxResourceLoader::class.java.getResourceAsStream("/com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf")
            ?.use { return PDType0Font.load(doc, it) } }
        for (p in listOf("/system/fonts/NotoSans-Regular.ttf", "/system/fonts/Roboto-Regular.ttf", "/system/fonts/RobotoStatic-Regular.ttf", "/system/fonts/DroidSans.ttf"))
            runCatching { val f = File(p); if (f.exists()) return PDType0Font.load(doc, f) }
        error("нет шрифта с кириллицей")
    }

    private class Finder : PDFTextStripper() {
        val chars = ArrayList<TextPosition>()
        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
            textPositions?.let { chars.addAll(it) }
            super.writeString(text, textPositions)
        }
    }

    private fun pagesArg(o: JSONObject, total: Int): List<Int> {
        val out = ArrayList<Int>()
        o.optJSONArray("pages")?.let { a -> for (k in 0 until a.length()) a.optInt(k).takeIf { it in 1..total }?.let(out::add) }
        if (out.isEmpty() && o.has("page")) o.optInt("page").takeIf { it in 1..total }?.let(out::add)
        return out.distinct()
    }

    /** Замена текста на месте: ищем символы на странице, закрашиваем белым, пишем новое тем же кеглем. */
    private fun replace(doc: PDDocument, from: String, to: String, onlyPages: List<Int>, f: () -> PDFont): Int {
        val key = from.replace(Regex("\\s"), "")
        if (key.isEmpty()) return 0
        var count = 0
        for (p in 1..doc.numberOfPages) {
            if (onlyPages.isNotEmpty() && p !in onlyPages) continue
            val fd = Finder().apply { sortByPosition = true; startPage = p; endPage = p }
            fd.getText(doc)
            val ch = fd.chars.filter { it.unicode.isNotBlank() }
            val s = ch.joinToString("") { it.unicode }
            // индекс в склейке → индекс символа
            val owner = ArrayList<Int>(); ch.forEachIndexed { k, tp -> repeat(tp.unicode.length) { owner += k } }
            val page: PDPage = doc.getPage(p - 1)
            val box = page.cropBox
            var at = s.indexOf(key)
            val hits = ArrayList<Pair<Int, Int>>()
            while (at >= 0) { hits += owner[at] to owner[at + key.length - 1]; at = s.indexOf(key, at + key.length) }
            if (hits.isEmpty()) continue
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                for ((a, b) in hits) {
                    val seg = ch.subList(a, b + 1)
                    val first = seg.first()
                    var fs = first.fontSizeInPt; if (fs < 4f) fs = first.heightDir * 1.45f; if (fs < 4f) fs = 10f
                    // по строкам: каждую закрашиваем
                    val linesSeg = seg.groupBy { Math.round(it.yDirAdj / (fs / 2)) }
                    for ((_, ls) in linesSeg) {
                        val x0 = ls.minOf { it.xDirAdj }; val x1 = ls.maxOf { it.xDirAdj + it.widthDirAdj }
                        val base = box.upperRightY - ls.first().yDirAdj
                        cs.setNonStrokingColor(1f, 1f, 1f)
                        cs.addRect(box.lowerLeftX + x0 - 0.5f, base - fs * 0.28f, x1 - x0 + 1f, fs * 1.22f); cs.fill()
                    }
                    val fnt = f()
                    val covered = seg.filter { kotlin.math.abs(it.yDirAdj - first.yDirAdj) < fs / 2 }.let { l -> l.maxOf { it.xDirAdj + it.widthDirAdj } - first.xDirAdj }
                    var size = fs
                    val w = runCatching { fnt.getStringWidth(to) / 1000f * size }.getOrDefault(0f)
                    if (w > covered * 1.15f && covered > 0) size = maxOf(fs * 0.7f, size * covered * 1.15f / w)
                    if (to.isNotEmpty()) {
                        cs.setNonStrokingColor(0f, 0f, 0f)
                        cs.beginText(); cs.setFont(fnt, size)
                        cs.newLineAtOffset(box.lowerLeftX + first.xDirAdj, box.upperRightY - first.yDirAdj)
                        cs.showText(to); cs.endText()
                    }
                    count++
                }
            }
        }
        return count
    }

    /**
     * ops — массив действий по порядку. Возвращает новые байты и отчёт, что сделано.
     * others — вложенные PDF для «merge».
     */
    fun edit(ctx: Context, src: Uri, ops: JSONArray, others: List<Uri>): Pair<ByteArray, String> {
        val doc = open(ctx, src)
        val log = ArrayList<String>()
        var fnt: PDFont? = null
        val f = { fnt ?: font(doc).also { fnt = it } }
        doc.use {
            for (k in 0 until ops.length()) {
                val o = ops.optJSONObject(k) ?: continue
                val op = o.optString("op").lowercase()
                val total = doc.numberOfPages
                runCatching {
                    when (op) {
                        "replace" -> {
                            val from = o.optString("from"); val to = o.optString("to")
                            val n = replace(doc, from, to, pagesArg(o, total), f)
                            log += if (n > 0) "заменил «${from.take(40)}» → «${to.take(40)}» ($n раз; старый текст закрыт белым, в текстовом слое ниже он может остаться рядом с новым — это нормально, на странице видно только новое)" else "не нашёл «${from.take(40)}» в тексте PDF (возможно, это скан или текст разбит иначе)"
                        }
                        "add_text", "text" -> {
                            val pg = pagesArg(o, total).ifEmpty { listOf(1) }
                            val size = o.optDouble("size", 11.0).toFloat()
                            val txt = o.optString("text")
                            for (p in pg) { val page = doc.getPage(p - 1); val box = page.cropBox
                                val mm = 72f / 25.4f
                                val x = box.lowerLeftX + if (o.has("x")) o.optDouble("x").toFloat() * mm else 20 * mm
                                val yTop = when { o.has("y") -> o.optDouble("y").toFloat() * mm
                                    o.optString("where") == "top" -> 15 * mm
                                    else -> box.height - 15 * mm - (txt.lines().size - 1) * size * 1.25f }
                                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                                    cs.setNonStrokingColor(0f, 0f, 0f)
                                    cs.beginText(); cs.setFont(f(), size)
                                    cs.newLineAtOffset(x, box.upperRightY - yTop)
                                    txt.lines().forEachIndexed { i, l -> if (i > 0) cs.newLineAtOffset(0f, -size * 1.25f); cs.showText(l) }
                                    cs.endText()
                                }
                            }
                            log += "вписал текст на стр. ${pg.joinToString()}"
                        }
                        "cover", "erase" -> {
                            val p = pagesArg(o, total).firstOrNull() ?: 1; val page = doc.getPage(p - 1); val box = page.cropBox; val mm = 72f / 25.4f
                            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                                cs.setNonStrokingColor(1f, 1f, 1f)
                                val x = o.optDouble("x").toFloat() * mm; val y = o.optDouble("y").toFloat() * mm
                                val w = o.optDouble("w", 50.0).toFloat() * mm; val h = o.optDouble("h", 10.0).toFloat() * mm
                                cs.addRect(box.lowerLeftX + x, box.upperRightY - y - h, w, h); cs.fill()
                            }
                            log += "закрыл область на стр. $p"
                        }
                        "fill" -> {
                            val af = doc.documentCatalog.acroForm ?: error("в PDF нет полей формы")
                            val res = af.defaultResources ?: PDResources().also { af.defaultResources = it }
                            val fname = res.add(f()).name
                            val da = "/$fname 0 Tf 0 g"
                            af.defaultAppearance = da
                            val vals = o.optJSONObject("fields") ?: JSONObject()
                            val done = ArrayList<String>(); val miss = ArrayList<String>()
                            vals.keys().forEach { key ->
                                val fld = af.getField(key) ?: af.fieldTree.firstOrNull { it.fullyQualifiedName.equals(key, true) || it.partialName.equals(key, true) }
                                if (fld == null) miss += key else {
                                    if (fld is PDTextField) fld.defaultAppearance = da
                                    runCatching { fld.setValue(vals.optString(key)); done += key }.onFailure { miss += "$key (${it.message?.take(40)})" }
                                }
                            }
                            log += "заполнил поля: ${done.joinToString().ifBlank { "—" }}" + if (miss.isNotEmpty()) "; не вышло: ${miss.joinToString()}" else ""
                        }
                        "delete_pages", "delete" -> {
                            val pg = pagesArg(o, total)
                            if (pg.size >= total) error("нельзя удалить все страницы")
                            pg.sortedDescending().forEach { doc.removePage(it - 1) }
                            log += "удалил стр. ${pg.sorted().joinToString()}"
                        }
                        "extract", "keep" -> {
                            val keep = pagesArg(o, total).toSet()
                            if (keep.isEmpty()) error("какие страницы оставить?")
                            (total downTo 1).filter { it !in keep }.forEach { doc.removePage(it - 1) }
                            log += "оставил стр. ${keep.sorted().joinToString()}"
                        }
                        "rotate" -> {
                            val pg = pagesArg(o, total).ifEmpty { (1..total).toList() }
                            val deg = ((o.optInt("deg", 90) % 360) + 360) % 360
                            pg.forEach { p -> val page = doc.getPage(p - 1); page.rotation = (page.rotation + deg) % 360 }
                            log += "повернул на $deg° стр. ${pg.joinToString()}"
                        }
                        "reorder", "order" -> {
                            val ord = o.optJSONArray("order") ?: error("нужен order")
                            val list = (0 until ord.length()).map { ord.optInt(it) }.filter { it in 1..total }
                            if (list.toSet().size != total) error("в order должны быть все страницы 1..$total по разу")
                            val pages = (0 until total).map { doc.getPage(it) }
                            pages.forEach { doc.pages.remove(it) }
                            list.forEach { doc.addPage(pages[it - 1]) }
                            log += "переставил страницы: ${list.joinToString()}"
                        }
                        "merge" -> {
                            val idx = o.optJSONArray("with")
                            val us = if (idx != null) (0 until idx.length()).mapNotNull { others.getOrNull(idx.optInt(it) - 1) } else others.filter { it != src }
                            if (us.isEmpty()) error("нет других PDF для склейки")
                            val m = PDFMergerUtility()
                            us.forEach { u -> open(ctx, u).use { d -> m.appendDocument(doc, d) } }
                            log += "приклеил ${us.size} PDF в конец"
                        }
                        "watermark" -> {
                            val txt = o.optString("text").ifBlank { "КОПИЯ" }
                            for (p in 0 until doc.numberOfPages) { val page = doc.getPage(p); val box = page.cropBox
                                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                                    cs.setNonStrokingColor(0.78f, 0.78f, 0.78f)
                                    cs.beginText(); cs.setFont(f(), 54f)
                                    cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(45.0), box.lowerLeftX + box.width * 0.18f, box.lowerLeftY + box.height * 0.3f))
                                    cs.showText(txt); cs.endText()
                                }
                            }
                            log += "поставил водяной знак «$txt»"
                        }
                        else -> log += "неизвестное действие «$op»"
                    }
                }.onFailure { log += "$op: ошибка — ${it.message?.take(100)}" }
            }
            val out = ByteArrayOutputStream(); doc.save(out)
            return out.toByteArray() to log.joinToString("\n")
        }
    }
}
