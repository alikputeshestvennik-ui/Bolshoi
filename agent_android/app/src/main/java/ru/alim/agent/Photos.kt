package ru.alim.agent

import android.content.ContentUris
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun photoUri(id: Long) = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

/** Сетка найденных фото: по три в ряд, нажатие открывает фото в Галерее. */
@Composable
fun PhotoGrid(ids: List<Long>) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ids.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { id ->
                    Thumb(id, Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(Card)
                        .clickable(onClickLabel = "Открыть фото") {
                            runCatching {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(photoUri(id), "image/*")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        })
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Thumb(id: Long, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val b: Bitmap? = if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(photoUri(id), Size(320, 320), null)
                else @Suppress("DEPRECATION") MediaStore.Images.Thumbnails.getThumbnail(ctx.contentResolver, id,
                    MediaStore.Images.Thumbnails.MINI_KIND, BitmapFactory.Options())
                b?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier) {
        bmp?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/**
 * Поиск фото по содержимому: «где я в чёрной футболке», «фото кота», «скрин чека».
 * Берём последние снимки галереи, уменьшаем до превью и пачками показываем модели с распознаванием картинок.
 */
object PhotoSearch {
    private fun thumb(ctx: android.content.Context, id: Long): String? = runCatching {
        val uri = photoUri(id)
        val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(uri, Size(320, 320), null)
        else {
            val o = BitmapFactory.Options().apply { inSampleSize = 8 }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return null
        }
        val out = java.io.ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
        "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }.getOrNull()

    /** Возвращает id подходящих фото (от новых к старым) и сколько просмотрено. */
    suspend fun find(ctx: android.content.Context, query: String, days: Int, max: Int, screenshots: Boolean): Pair<List<Long>, Int> {
        val since = System.currentTimeMillis() - days * 86_400_000L
        val ids = ArrayList<Long>()
        ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN),
            "${MediaStore.Images.Media.DATE_TAKEN} >= ?", arrayOf(since.toString()), "${MediaStore.Images.Media.DATE_TAKEN} DESC")?.use { c ->
            while (c.moveToNext() && ids.size < max) {
                val bucket = c.getString(1).orEmpty()
                if (!screenshots && bucket.contains("screenshot", true)) continue
                ids += c.getLong(0)
            }
        }
        if (ids.isEmpty()) return emptyList<Long>() to 0
        val model = Prefs.bg.takeUnless { Llm.isYandex(it) } ?: Prefs.fastModel.takeUnless { Llm.isYandex(it) } ?: Prefs.smartModel.takeUnless { Llm.isYandex(it) } ?: "claude-5.5-haiku"
        val batches = ids.chunked(16)
        val found = java.util.Collections.synchronizedList(ArrayList<Long>())
        coroutineScope {
            batches.chunked(3).forEach { group ->
                group.map { batch ->
                    async(Dispatchers.IO) {
                        runCatching {
                            val pics = batch.mapNotNull { id -> thumb(ctx, id)?.let { id to it } }
                            if (pics.isEmpty()) return@runCatching
                            val content = org.json.JSONArray().put(org.json.JSONObject().put("type", "text").put("text",
                                "Перед тобой ${pics.size} фото, по порядку с номерами 1..${pics.size}. Ищем: «$query». " +
                                    "Верни ТОЛЬКО JSON {\"match\": [номера]} — фото, на которых это действительно есть. Нет таких — {\"match\": []}."))
                            pics.forEachIndexed { i, (_, url) ->
                                content.put(org.json.JSONObject().put("type", "text").put("text", "Фото ${i + 1}:"))
                                content.put(org.json.JSONObject().put("type", "image_url").put("image_url", org.json.JSONObject().put("url", url)))
                            }
                            val r = Llm.chat(model, org.json.JSONArray().put(org.json.JSONObject().put("role", "user").put("content", content)), 200)
                            val t = r.text; val a = t.indexOf('{'); val b = t.lastIndexOf('}')
                            val arr = org.json.JSONObject(t.substring(a, b + 1)).optJSONArray("match") ?: return@runCatching
                            for (k in 0 until arr.length()) pics.getOrNull(arr.optInt(k) - 1)?.let { found += it.first }
                        }
                    }
                }.forEach { it.await() }
            }
        }
        return ids.filter { it in found } to ids.size
    }
}

/** Медиа из Telegram: миниатюры по локальным путям. Фото — просмотр в приложении, видео (с пометкой \tvid=ID) — ▶ по тапу качает и проигрывает. */
@Composable
fun TgPhotoGrid(entries: List<String>) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var openImg by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var openVid by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var loading by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { entry ->
                    val path = entry.substringBefore("\t")
                    val vid = Regex("vid=(\\d+)").find(entry)?.groupValues?.get(1)?.toIntOrNull()
                    val bmp = androidx.compose.runtime.remember(path) { loadBitmap(path, 480) }
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(Card)
                        .clickable {
                            if (vid == null) openImg = path
                            else { loading = entry; scope.launch { val f = Tg.fetchFile(vid); loading = null; if (f != null) openVid = f } }
                        }, contentAlignment = androidx.compose.ui.Alignment.Center) {
                        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        if (vid != null) Box(Modifier.size(42.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            androidx.compose.material3.Text(if (loading == entry) "…" else "\u25B6", style = body(20, color = androidx.compose.ui.graphics.Color.White))
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    openImg?.let { p ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { openImg = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            val big = androidx.compose.runtime.remember(p) { loadBitmap(p, 1600) }
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.95f))
                .clickable { openImg = null }, contentAlignment = androidx.compose.ui.Alignment.Center) {
                if (big != null) Image(big, null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
            }
        }
    }
    openVid?.let { path ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { openVid = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)
                .clickable { openVid = null }, contentAlignment = androidx.compose.ui.Alignment.Center) {
                androidx.compose.ui.viewinterop.AndroidView(factory = { c ->
                    android.widget.VideoView(c).apply {
                        setVideoPath(path)
                        setOnPreparedListener { it.isLooping = true; start() }
                        val mc = android.widget.MediaController(c); mc.setAnchorView(this); setMediaController(mc)
                    }
                }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
