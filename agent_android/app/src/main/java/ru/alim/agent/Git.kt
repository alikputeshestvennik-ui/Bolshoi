package ru.alim.agent

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * GitHub только на чтение (личный токен GH_PAT): смотреть статус сборок приложения и скачивать готовый APK,
 * чтобы обновиться, не заходя на сайт. Ничего не меняет в репозитории.
 */
object Git {
    private const val OWNER = "alikputeshestvennik-ui"
    private const val REPO = "Bolshoi"
    private const val WORKFLOW = "AGENT_APK.yml"
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    val token: String get() = BuildConfig.GH_PAT.trim()
    val ready: Boolean get() = token.isNotBlank()

    private fun get(url: String): okhttp3.Response = http.newCall(Request.Builder().url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28").build()).execute()

    private fun api(path: String): JSONObject {
        get("https://api.github.com$path").use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("GitHub ${r.code}")
            return JSONObject(body)
        }
    }

    private fun ago(iso: String): String = runCatching {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val mins = (System.currentTimeMillis() - f.parse(iso)!!.time) / 60000
        when { mins < 1 -> "только что"; mins < 60 -> "$mins мин назад"; mins < 1440 -> "${mins / 60} ч назад"; else -> "${mins / 1440} дн назад" }
    }.getOrDefault(iso)

    /** Последний запуск сборки приложения (любой статус) или null. */
    fun latestRun(): JSONObject? = api("/repos/$OWNER/$REPO/actions/workflows/$WORKFLOW/runs?per_page=1").optJSONArray("workflow_runs")?.optJSONObject(0)

    /** Статус последней сборки приложения. */
    fun lastBuild(): String {
        if (!ready) return "нет токена GitHub (GH_PAT)"
        val runs = api("/repos/$OWNER/$REPO/actions/workflows/$WORKFLOW/runs?per_page=1").optJSONArray("workflow_runs")
        val r = runs?.optJSONObject(0) ?: return "сборок не нашёл"
        val status = r.optString("status"); val concl = r.optString("conclusion")
        val state = when {
            status != "completed" -> "идёт сейчас"
            concl == "success" -> "успешно ✓"
            concl == "failure" -> "упала ✗"
            else -> concl
        }
        return "Последняя сборка: $state, ${ago(r.optString("created_at"))} (#${r.optInt("run_number")})"
    }

    /** Скачать APK из последней успешной сборки и открыть установку. Возвращает текст для Алима. */
    fun installLatest(ctx: Context): String {
        if (!ready) return "нет токена GitHub (GH_PAT)"
        val runs = api("/repos/$OWNER/$REPO/actions/workflows/$WORKFLOW/runs?per_page=10&status=success").optJSONArray("workflow_runs")
            ?: return "успешных сборок не нашёл"
        val runId = (0 until runs.length()).map { runs.getJSONObject(it) }.firstOrNull()?.optLong("id") ?: return "успешных сборок не нашёл"
        val arts = api("/repos/$OWNER/$REPO/actions/runs/$runId/artifacts").optJSONArray("artifacts")
        val art = (0 until (arts?.length() ?: 0)).map { arts!!.getJSONObject(it) }.firstOrNull() ?: return "в сборке нет артефакта"
        val artId = art.optLong("id")
        // артефакт — zip, внутри .apk
        val apk = File(ctx.cacheDir, "update.apk")
        get("https://api.github.com/repos/$OWNER/$REPO/actions/artifacts/$artId/zip").use { r ->
            if (!r.isSuccessful) return "не скачалось: GitHub ${r.code}"
            ZipInputStream(r.body!!.byteStream()).use { zip ->
                var e = zip.nextEntry
                var found = false
                while (e != null) {
                    if (e.name.endsWith(".apk", true)) { apk.outputStream().use { zip.copyTo(it) }; found = true; break }
                    e = zip.nextEntry
                }
                if (!found) return "в артефакте нет APK"
            }
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure { return "скачал, но не открылась установка: ${it.message}" }
        return "скачал свежую сборку — подтверди установку (один раз разреши «Установка из этого источника»)"
    }
}

/**
 * Сторож сборок: сам замечает новую версию Пилота на GitHub и присылает уведомление «готово — скажи „обновись“».
 * Раз в 15 минут в фоне + при каждом открытии приложения; пока сборка идёт — проверяет каждые 2 минуты.
 */
object BuildWatch {
    private fun installed(ctx: Context): Int = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        (if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode) - 1
    }.getOrDefault(0)

    fun schedule(ctx: Context) {
        val wm = androidx.work.WorkManager.getInstance(ctx)
        val net = androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()
        wm.enqueueUniquePeriodicWork("buildwatch", androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            androidx.work.PeriodicWorkRequestBuilder<BuildWatchWorker>(15, TimeUnit.MINUTES).setConstraints(net).build())
    }

    private fun soon(ctx: Context) {
        val net = androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()
        androidx.work.WorkManager.getInstance(ctx).enqueueUniqueWork("buildwatch-soon", androidx.work.ExistingWorkPolicy.REPLACE,
            androidx.work.OneTimeWorkRequestBuilder<BuildWatchWorker>().setInitialDelay(2, TimeUnit.MINUTES).setConstraints(net).build())
    }

    /** Проверка последней сборки. Возвращает текст для чата, если есть что сказать (новая готова / упала). */
    fun check(ctx: Context): String? {
        if (!Git.ready) return null
        val r = Git.latestRun() ?: return null
        val num = r.optInt("run_number"); val status = r.optString("status"); val concl = r.optString("conclusion")
        if (status != "completed") { soon(ctx); return null }
        if (num <= installed(ctx) || num <= Prefs.buildSeen) return null
        Prefs.buildSeen = num
        val (title, text) = if (concl == "success") "Новая версия Пилота готова" to "Сборка #$num собрана. Скажи «обновись» — поставлю."
            else "Сборка #$num не собралась" to "Новая версия Пилота упала при сборке — на телефоне остаётся текущая."
        Monitor.notify(ctx, 7300, title, text)
        return "🛠 $title. ${text}"
    }
}

class BuildWatchWorker(ctx: Context, params: androidx.work.WorkerParameters) : androidx.work.CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        // в фоне — только уведомление (в чат запишет само приложение при открытии)
        runCatching { Prefs.init(applicationContext); BuildWatch.check(applicationContext) }
        return Result.success()
    }
}
