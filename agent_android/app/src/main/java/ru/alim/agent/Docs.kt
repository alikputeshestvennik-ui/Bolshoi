package ru.alim.agent

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Файлы Word (.docx) и Excel (.xlsx) без тяжёлых библиотек: это zip с XML внутри.
 * Создаём из простого описания, читаем обратно в то же описание — так модель может их править.
 */
object Docs {
    const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    class FileRef(val name: String, val uri: Uri, val mime: String)

    /** Файлы, созданные агентом в этой сессии — «открой», «отправь», «поправь». */
    val made = ArrayList<FileRef>()

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // ———————————————— запись ————————————————

    private fun zip(parts: Map<String, String>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z -> parts.forEach { (n, x) -> z.putNextEntry(ZipEntry(n)); z.write(x.toByteArray(Charsets.UTF_8)); z.closeEntry() } }
        return bo.toByteArray()
    }

    /** Текст с разметкой: «# заголовок», «## подзаголовок», «- пункт», «**жирный**», таблицы «| a | b |». */
    fun docx(text: String): ByteArray {
        val body = StringBuilder()
        fun runs(line: String, size: Int = 0, boldAll: Boolean = false): String = line.split("**").mapIndexed { i, p ->
            if (p.isEmpty()) "" else {
                val b = boldAll || i % 2 == 1
                val pr = (if (b) "<w:b/>" else "") + (if (size > 0) "<w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/>" else "")
                "<w:r>${if (pr.isNotEmpty()) "<w:rPr>$pr</w:rPr>" else ""}<w:t xml:space=\"preserve\">${esc(p)}</w:t></w:r>"
            }
        }.joinToString("")
        fun para(inner: String, after: Int = 120) = "<w:p><w:pPr><w:spacing w:after=\"$after\"/></w:pPr>$inner</w:p>"
        val lines = text.replace("\r", "").lines()
        var i = 0
        while (i < lines.size) {
            val l = lines[i].trimEnd()
            when {
                l.trimStart().startsWith("|") -> {
                    val rows = ArrayList<List<String>>()
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                        val cells = lines[i].trim().trim('|').split("|").map { it.trim() }
                        if (!cells.all { it.matches(Regex(":?-{2,}:?")) }) rows += cells
                        i++
                    }
                    val cols = rows.maxOf { it.size }
                    body.append("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblBorders>")
                    listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach { body.append("<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"999999\"/>") }
                    body.append("</w:tblBorders></w:tblPr>")
                    rows.forEachIndexed { ri, r ->
                        body.append("<w:tr>")
                        (0 until cols).forEach { c -> body.append("<w:tc><w:p>${runs(r.getOrElse(c) { "" }, boldAll = ri == 0)}</w:p></w:tc>") }
                        body.append("</w:tr>")
                    }
                    body.append("</w:tbl>").append(para(""))
                    continue
                }
                l.startsWith("### ") -> body.append(para(runs(l.drop(4), 26, true)))
                l.startsWith("## ") -> body.append(para(runs(l.drop(3), 30, true), 160))
                l.startsWith("# ") -> body.append(para(runs(l.drop(2), 38, true), 200))
                Regex("^\\s*[-*•]\\s+").containsMatchIn(l) -> body.append(para(runs("•  " + l.replace(Regex("^\\s*[-*•]\\s+"), "")), 60))
                else -> body.append(para(runs(l)))
            }
            i++
        }
        val doc = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>$body" +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"850\" w:bottom=\"1134\" w:left=\"1418\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>"
        val styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\" w:eastAsia=\"Calibri\"/><w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/><w:lang w:val=\"ru-RU\"/></w:rPr></w:rPrDefault></w:docDefaults></w:styles>"
        return zip(linkedMapOf(
            "[Content_Types].xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>",
            "_rels/.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
            "word/_rels/document.xml.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>",
            "word/document.xml" to doc,
            "word/styles.xml" to styles,
        ))
    }

    private fun col(n: Int): String { var x = n + 1; val sb = StringBuilder(); while (x > 0) { val m = (x - 1) % 26; sb.insert(0, ('A' + m)); x = (x - 1) / 26 }; return sb.toString() }

    /** {"sheets":[{"name":"Лист1","rows":[["Товар","Цена"],["Хлеб",50],["Итого","=SUM(B2:B2)"]]}]} — первая строка жирная, «=…» — формула. */
    fun xlsx(spec: JSONObject): ByteArray {
        val sheets = spec.optJSONArray("sheets") ?: JSONArray().put(JSONObject().put("name", "Лист1").put("rows", spec.optJSONArray("rows") ?: JSONArray()))
        val parts = LinkedHashMap<String, String>()
        val wbSheets = StringBuilder(); val wbRels = StringBuilder(); val ct = StringBuilder()
        for (si in 0 until sheets.length()) {
            val sh = sheets.getJSONObject(si)
            val rows = sh.optJSONArray("rows") ?: JSONArray()
            val name = esc(sh.optString("name", "Лист${si + 1}").take(31).replace(Regex("[\\[\\]:*?/\\\\]"), " "))
            val data = StringBuilder()
            var maxCols = 0
            for (r in 0 until rows.length()) {
                val row = rows.optJSONArray(r) ?: continue
                maxCols = maxOf(maxCols, row.length())
                data.append("<row r=\"${r + 1}\">")
                for (c in 0 until row.length()) {
                    val ref = "${col(c)}${r + 1}"
                    val st = if (r == 0) " s=\"1\"" else ""
                    val v = row.opt(c)
                    when {
                        v == null || v == JSONObject.NULL -> {}
                        v is Number -> data.append("<c r=\"$ref\"$st><v>$v</v></c>")
                        v is Boolean -> data.append("<c r=\"$ref\" t=\"b\"$st><v>${if (v) 1 else 0}</v></c>")
                        v.toString().startsWith("=") -> data.append("<c r=\"$ref\"$st><f>${esc(v.toString().drop(1))}</f></c>")
                        v.toString().trim().matches(Regex("-?(0|[1-9]\\d{0,10})([.,]\\d+)?")) ->
                            data.append("<c r=\"$ref\"$st><v>${v.toString().replace(',', '.').trim()}</v></c>")
                        else -> data.append("<c r=\"$ref\" t=\"inlineStr\"$st><is><t xml:space=\"preserve\">${esc(v.toString())}</t></is></c>")
                    }
                }
                data.append("</row>")
            }
            val cols = if (maxCols > 0) "<cols><col min=\"1\" max=\"$maxCols\" width=\"18\" customWidth=\"1\"/></cols>" else ""
            parts["xl/worksheets/sheet${si + 1}.xml"] = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                "$cols<sheetData>$data</sheetData></worksheet>"
            wbSheets.append("<sheet name=\"$name\" sheetId=\"${si + 1}\" r:id=\"rId${si + 1}\"/>")
            wbRels.append("<Relationship Id=\"rId${si + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${si + 1}.xml\"/>")
            ct.append("<Override PartName=\"/xl/worksheets/sheet${si + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        val n = sheets.length()
        wbRels.append("<Relationship Id=\"rId${n + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
        return zip(linkedMapOf(
            "[Content_Types].xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>$ct</Types>",
            "_rels/.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>",
            "xl/workbook.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>$wbSheets</sheets><calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/></workbook>",
            "xl/_rels/workbook.xml.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">$wbRels</Relationships>",
            "xl/styles.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
                "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
                "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
                "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
                "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>" +
                "</styleSheet>",
        ).apply { putAll(parts) })
    }

    // ———————————————— сохранение и открытие ————————————————

    /** Сохраняет в «Загрузки/Агент». Возвращает ссылку для открытия. */
    fun save(ctx: Context, name0: String, mime: String, bytes: ByteArray): FileRef {
        val ext = if (mime == XLSX) ".xlsx" else ".docx"
        val name = name0.trim().ifBlank { "Документ" }.replace(Regex("[\\\\/:*?\"<>|]"), " ").removeSuffix(ext).take(80) + ext
        val uri: Uri = if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Свои")
            }
            val u = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: error("не удалось создать файл")
            ctx.contentResolver.openOutputStream(u)?.use { it.write(bytes) } ?: error("не удалось записать файл")
            u
        } else {
            val dir = File(ctx.getExternalFilesDir(null), "Свои").apply { mkdirs() }
            val f = File(dir, name); f.writeBytes(bytes)
            Uri.fromFile(f)
        }
        val ref = FileRef(name, uri, mime)
        made.add(0, ref)
        return ref
    }

    // ———————————————— чтение ————————————————

    private fun unzip(input: InputStream): Map<String, String> {
        val out = HashMap<String, String>()
        ZipInputStream(input).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name.endsWith(".xml")) out[e.name] = String(z.readBytes(), Charsets.UTF_8)
                e = z.nextEntry
            }
        }
        return out
    }

    private fun unesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** .docx → текст в той же разметке, что принимает docx(). */
    fun readDocx(input: InputStream): String {
        val xml = unzip(input)["word/document.xml"] ?: error("это не документ Word")
        val body = xml.substringAfter("<w:body>", xml)
        val sb = StringBuilder()
        fun paraText(p: String): String {
            val bold = Regex("<w:r>(.*?)</w:r>|<w:r [^>]*>(.*?)</w:r>", RegexOption.DOT_MATCHES_ALL).findAll(p).joinToString("") { m ->
                val r = m.value
                val t = Regex("<w:t[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL).findAll(r).joinToString("") { unesc(it.groupValues[1]) }
                if (t.isNotEmpty() && Regex("<w:b/>|<w:b w:val=\"(1|true)\"/>").containsMatchIn(r)) "**$t**" else t
            }
            return bold.replace("****", "")
        }
        Regex("<w:tbl>.*?</w:tbl>|<w:p[ >].*?</w:p>|<w:p/>", RegexOption.DOT_MATCHES_ALL).findAll(body).forEach { m ->
            val v = m.value
            if (v.startsWith("<w:tbl>")) {
                Regex("<w:tr[ >].*?</w:tr>", RegexOption.DOT_MATCHES_ALL).findAll(v).forEachIndexed { i, tr ->
                    val cells = Regex("<w:tc>.*?</w:tc>", RegexOption.DOT_MATCHES_ALL).findAll(tr.value).map { tc ->
                        Regex("<w:p[ >].*?</w:p>", RegexOption.DOT_MATCHES_ALL).findAll(tc.value).joinToString(" ") { paraText(it.value).replace("**", "") }
                    }.toList()
                    sb.append("| ").append(cells.joinToString(" | ")).append(" |\n")
                    if (i == 0) sb.append("|").append(cells.joinToString("|") { "---" }).append("|\n")
                }
            } else {
                val t = paraText(v)
                val h = Regex("<w:pStyle w:val=\"(Heading|Заголовок)?(\\d)\"").find(v)?.groupValues?.get(2)
                sb.append(if (h != null && t.isNotBlank()) "#".repeat(h.toInt().coerceIn(1, 3)) + " " + t.replace("**", "") else t).append("\n")
            }
        }
        return sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** .xlsx → {"sheets":[{"name","rows":[[…]]}]} — тот же вид, что принимает xlsx(). Формулы — «=…». */
    fun readXlsx(input: InputStream): JSONObject {
        val f = unzip(input)
        val shared = f["xl/sharedStrings.xml"]?.let { x ->
            Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(x).map { si ->
                Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(si.groupValues[1]).joinToString("") { unesc(it.groupValues[1]) }
            }.toList()
        }.orEmpty()
        val wb = f["xl/workbook.xml"].orEmpty()
        val names = Regex("<sheet [^>]*name=\"([^\"]*)\"").findAll(wb).map { unesc(it.groupValues[1]) }.toList()
        val sheetFiles = f.keys.filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }.sortedBy { it.filter(Char::isDigit).toInt() }
        val sheets = JSONArray()
        sheetFiles.forEachIndexed { si, path ->
            val rows = JSONArray()
            Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(f[path].orEmpty()).take(500).forEach { rm ->
                val row = JSONArray()
                Regex("<c ([^>]*?)(/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL).findAll(rm.groupValues[1]).forEach { cm ->
                    val attrs = cm.groupValues[1]; val inner = cm.groupValues[3]
                    val ref = Regex("r=\"([A-Z]+)\\d+\"").find(attrs)?.groupValues?.get(1).orEmpty()
                    val idx = ref.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1
                    while (idx >= 0 && row.length() < idx) row.put("")
                    val t = Regex("t=\"(\\w+)\"").find(attrs)?.groupValues?.get(1)
                    val fml = Regex("<f[^>]*>(.*?)</f>", RegexOption.DOT_MATCHES_ALL).find(inner)?.groupValues?.get(1)
                    val v = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(inner)?.groupValues?.get(1)
                    val value: Any = when {
                        fml != null -> "=" + unesc(fml)
                        t == "s" -> shared.getOrElse(v?.toIntOrNull() ?: -1) { "" }
                        t == "inlineStr" -> Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(inner).joinToString("") { unesc(it.groupValues[1]) }
                        t == "str" -> unesc(v.orEmpty())
                        t == "b" -> v == "1"
                        v != null -> v.toLongOrNull() ?: v.toDoubleOrNull() ?: v
                        else -> ""
                    }
                    row.put(value)
                }
                rows.put(row)
            }
            sheets.put(JSONObject().put("name", names.getOrElse(si) { "Лист${si + 1}" }).put("rows", rows))
        }
        return JSONObject().put("sheets", sheets)
    }

    /** Прочитать вложение или созданный файл в текст для модели. */
    fun read(ctx: Context, uri: Uri, name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ctx.contentResolver.openInputStream(uri)?.use { s ->
            when (ext) {
                "docx" -> readDocx(s)
                "xlsx" -> readXlsx(s).toString()
                else -> error("читаю только .docx и .xlsx")
            }
        } ?: error("файл не открылся")
    }
}
