package ru.alim.agent

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * YandexART — генерация картинок в Yandex AI Studio. Запрос асинхронный: получаем операцию и ждём, пока будет готово.
 * Картинка сохраняется в Галерею, в альбом «Агент».
 */
object YaArt {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val keys: List<String> get() = YaCloud.all

    /** Соотношения сторон, которые понимает агент. */
    private fun ratio(r: String): Pair<String, String> = when (r.trim()) {
        "16:9", "широкая", "wide" -> "16" to "9"
        "9:16", "вертикальная", "tall", "обои" -> "9" to "16"
        "4:3" -> "4" to "3"
        "3:4" -> "3" to "4"
        else -> "1" to "1"
    }

    private fun size(r: String): String = when (ratio(r)) {
        "16" to "9" -> "1792x1024"
        "9" to "16" -> "1024x1792"
        "4" to "3" -> "1280x960"
        "3" to "4" -> "960x1280"
        else -> "1024x1024"
    }

    /** Alice AI ART через Images API (синхронно, ответ — base64 в data[0].b64_json). Старый YandexART — запасной путь. */
    fun generate(ctx: Context, prompt: String, aspect: String): Long {
        if (YaCloud.folder.isBlank() || keys.isEmpty()) error("нет ключа или каталога Yandex Cloud")
        val errs = ArrayList<String>()
        for (sz in listOf(size(aspect), "1024x1024").distinct()) for (k in keys) {
            val body = JSONObject().put("model", "art://${YaCloud.folder}/aliceai-image-art-3.0").put("prompt", prompt.take(500)).put("size", sz)
            val (code, txt) = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url("https://ai.api.cloud.yandex.net/v1/images/generations")
                    .header("Authorization", "Api-Key $k").header("OpenAI-Project", YaCloud.folder)
                    .post(body.toString().toRequestBody(JSON)).build()).execute().use { it.code to (it.body?.string() ?: "") }
            if (code in 200..299) {
                val b64 = JSONObject(txt).optJSONArray("data")?.optJSONObject(0)?.optString("b64_json").orEmpty()
                if (b64.isBlank()) error("Alice AI ART вернула пустую картинку")
                return save(ctx, Base64.decode(b64, Base64.DEFAULT))
            }
            errs += "…${k.takeLast(4)} $sz: $code ${txt.replace(Regex("\\s+"), " ").take(140)}"
            if (code == 400) break // размер не подошёл — пробуем 1024x1024
        }
        return runCatching { generateOld(ctx, prompt, aspect) }.getOrElse { error("Alice AI ART: ${errs.joinToString("; ").take(400)}") }
    }

    private fun generateOld(ctx: Context, prompt: String, aspect: String): Long {
        if (YaCloud.folder.isBlank() || keys.isEmpty()) error("нет ключа или каталога Yandex Cloud")
        val (wr, hr) = ratio(aspect)
        val body = JSONObject()
            .put("modelUri", "art://${YaCloud.folder}/yandex-art/latest")
            .put("generationOptions", JSONObject().put("seed", (System.currentTimeMillis() % 1_000_000).toString())
                .put("aspectRatio", JSONObject().put("widthRatio", wr).put("heightRatio", hr)))
            .put("messages", JSONArray().put(JSONObject().put("weight", "1").put("text", prompt.take(500))))
        var opId: String? = null
        var key = ""
        var lastErr = ""
        for (k in keys) {
            val (code, txt) = http.newCall(Request.Builder().url("https://llm.api.cloud.yandex.net/foundationModels/v1/imageGenerationAsync")
                .header("Authorization", "Api-Key $k").post(body.toString().toRequestBody(JSON)).build()).execute()
                .use { it.code to (it.body?.string() ?: "") }
            if (code == 401 || code == 403) { lastErr += "ключ …${k.takeLast(4)}: $code ${txt.replace(Regex("\\s+"), " ").take(180)}; "; continue }
            if (code !in 200..299) error("YandexART ответил $code: ${txt.take(160)}")
            opId = JSONObject(txt).optString("id"); key = k; break
        }
        if (opId.isNullOrBlank()) error("YandexART (каталог ${YaCloud.folder.take(6)}…, ключей ${keys.size}): $lastErr")
        // ждём до ~90 секунд
        repeat(45) {
            Thread.sleep(2000)
            val txt = http.newCall(Request.Builder().url("https://llm.api.cloud.yandex.net/operations/$opId")
                .header("Authorization", "Api-Key $key").get().build()).execute().use { it.body?.string().orEmpty() }
            val j = JSONObject(txt)
            if (j.optBoolean("done")) {
                j.optJSONObject("error")?.let { e -> error("YandexART: ${e.optString("message").ifBlank { "отказ" }}") }
                val img = j.optJSONObject("response")?.optString("image").orEmpty()
                if (img.isBlank()) error("YandexART вернул пустую картинку")
                return save(ctx, Base64.decode(img, Base64.DEFAULT))
            }
        }
        error("YandexART не успел за 90 секунд")
    }

    private fun save(ctx: Context, jpeg: ByteArray): Long {
        val cv = ContentValues().apply {
            val png = jpeg.size > 4 && jpeg[0] == 0x89.toByte() && jpeg[1] == 'P'.code.toByte()
            put(MediaStore.Images.Media.DISPLAY_NAME, "agent_${System.currentTimeMillis()}.${if (png) "png" else "jpg"}")
            put(MediaStore.Images.Media.MIME_TYPE, if (png) "image/png" else "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Пилот")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: error("не удалось сохранить картинку")
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(jpeg) } ?: error("не удалось записать картинку")
        if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri.lastPathSegment?.toLongOrNull() ?: error("не удалось сохранить картинку")
    }
}
