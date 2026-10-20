package ru.alim.agent

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Яндекс Поиск API v2 (Yandex Cloud): POST /v2/web/search, ответ — XML в base64 (rawData).
 * Ключу нужна роль search-api.webSearch.user (область yc.search-api.execute).
 */
/** Ключи и каталог Yandex Cloud: общие для поиска и YandexGPT. */
object YaCloud {
    val searchKey: String get() = BuildConfig.YANDEX_SEARCH_KEY.trim().ifBlank { BuildConfig.YANDEX_API_KEY.trim() }
    val llmKey: String get() = BuildConfig.YANDEX_API_KEY.trim().ifBlank { BuildConfig.YANDEX_SEARCH_KEY.trim() }
    val folder: String get() = BuildConfig.YANDEX_FOLDER_ID.trim().ifBlank {
        Regex("^[a-z]+://([^/]+)/").find(BuildConfig.QWEN_MODEL.trim())?.groupValues?.get(1).orEmpty()
    }
}

object YaSearch {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val key: String get() = YaCloud.searchKey
    private val folder: String get() = YaCloud.folder
    val ready: Boolean get() = key.isNotBlank() && folder.isNotBlank()
    fun missing(): String = listOfNotNull(
        "нет ключа".takeIf { key.isBlank() }, "нет ID каталога".takeIf { folder.isBlank() },
    ).joinToString(", ")

    /** Значения перечислений бывают в двух написаниях — пробуем полное, при 400 короткое. */
    private val variants = listOf("SEARCH_TYPE_RU" to "FORMAT_XML", "ru" to "XML")
    @Volatile private var variant = 0

    fun search(q: String): List<String> {
        if (!ready) error("Яндекс: ${missing()}")
        var lastErr = ""
        for (k in variants.indices) {
            val i = (variant + k) % variants.size
            val (type, fmt) = variants[i]
            val body = JSONObject()
                .put("query", JSONObject().put("searchType", type).put("queryText", q).put("familyMode", "FAMILY_MODE_MODERATE".takeIf { i == 0 } ?: "moderate"))
                .put("groupSpec", JSONObject().put("groupsOnPage", "8").put("docsInGroup", "1"))
                .put("maxPassages", "3").put("region", "225").put("l10N", if (i == 0) "LOCALIZATION_RU" else "ru")
                .put("folderId", folder).put("responseFormat", fmt)
            val (code, txt) = http.newCall(Request.Builder().url("https://searchapi.api.cloud.yandex.net/v2/web/search")
                .header("Authorization", "Api-Key $key").post(body.toString().toRequestBody(JSON)).build())
                .execute().use { it.code to (it.body?.string() ?: "") }
            if (code == 401 || code == 403) error("Яндекс: у ключа нет доступа к Search API (нужна роль search-api.webSearch.user)")
            if (code == 400) { lastErr = txt.take(150); continue }
            if (code !in 200..299) error("Яндекс ответил $code: ${txt.take(120)}")
            variant = i
            val raw = JSONObject(txt).optString("rawData")
            if (raw.isBlank()) error("Яндекс: пустой ответ")
            return parse(String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8))
        }
        error("Яндекс не принял запрос: $lastErr")
    }

    private fun strip(s: String) = s.replace(Regex("<[^>]+>"), "")
        .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&#39;", "'")
        .replace(Regex("\\s+"), " ").trim()

    private fun parse(xml: String): List<String> {
        Regex("<error[^>]*>(.*?)</error>", RegexOption.DOT_MATCHES_ALL).find(xml)?.let { error("Яндекс: ${strip(it.groupValues[1])}") }
        return Regex("<doc[ >](.*?)</doc>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val d = m.groupValues[1]
            val url = Regex("<url>(.*?)</url>", RegexOption.DOT_MATCHES_ALL).find(d)?.groupValues?.get(1)?.let { strip(it) } ?: return@mapNotNull null
            val title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(d)?.groupValues?.get(1)?.let { strip(it) }.orEmpty()
            val passages = Regex("<passage>(.*?)</passage>", RegexOption.DOT_MATCHES_ALL).findAll(d).map { strip(it.groupValues[1]) }.toList()
            val headline = Regex("<headline>(.*?)</headline>", RegexOption.DOT_MATCHES_ALL).find(d)?.groupValues?.get(1)?.let { strip(it) }
            "$title — $url\n${(passages.joinToString(" … ").ifBlank { headline.orEmpty() }).take(300)}"
        }.toList()
    }
}
