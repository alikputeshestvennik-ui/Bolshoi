package ru.alim.agent

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Run Codex through an encrypted GitHub Actions task; no terminal UI. */
object CodexGateway {
    private const val ROOT = "https://api.github.com/repos/alikputeshestvennik-ui/Bolshoi"
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()
    private fun store(ctx: Context): android.content.SharedPreferences {
        val key = MasterKey.Builder(ctx.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(ctx.applicationContext, "codex_gateway", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    fun customUrl(ctx: Context): String = store(ctx).getString("custom_url", "").orEmpty()
    fun saveCustom(ctx: Context, address: String, token: String, model: String) {
        val parsed = address.trim().toHttpUrlOrNull() ?: error("Укажите HTTPS-адрес провайдера")
        require(parsed.isHttps && parsed.query == null && parsed.fragment == null) { "Нужен HTTPS-адрес без параметров" }
        require(Regex("^[a-zA-Z0-9._/:-]{1,128}$").matches(model.trim())) { "Укажите корректный идентификатор модели" }
        val edit=store(ctx).edit().putString("custom_url", parsed.toString().trimEnd('/'))
        if (token.isNotBlank()) edit.putString("custom_token", token.trim())
        edit.apply(); Prefs.compatibleModel = model.trim()
    }
    private fun key(pat: String): SecretKeySpec = SecretKeySpec(MessageDigest.getInstance("SHA-256")
        .digest(("ten-agent-v1:" + pat.trim()).toByteArray(Charsets.UTF_8)), "AES")
    private fun encrypt(value: String, pat: String): String {
        val iv=ByteArray(12);SecureRandom().nextBytes(iv)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(pat),GCMParameterSpec(128,iv))
        return Base64.encodeToString(iv+cipher.doFinal(value.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)
    }
    private fun decrypt(value: String, pat: String): String {
        val bytes=Base64.decode(value.trim(),Base64.DEFAULT);require(bytes.size>=28)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(pat),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        return String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)
    }
    private fun call(pat: String, url: String, body: JSONObject? = null): String {
        val builder=Request.Builder().url(url).header("Authorization", "Bearer $pat")
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
        if(body!=null)builder.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        return http.newCall(builder.build()).execute().use { r ->
            if(!r.isSuccessful) error(if(r.code==403 || r.code==404) "GitHub: нужен доступ GH_PAT к запуску и чтению Actions (${r.code})" else "GitHub: HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
    }
    data class Result(val text: String, val file: Docs.FileRef?)
    fun run(ctx: Context, goal: String, selected: String): Result {
        val pat=Git.token;if(pat.isBlank())error("В сборке нет GH_PAT для GitHub Actions")
        require(goal.length<=16000) { "Задача слишком длинная" }
        val prefs=store(ctx)
        val (backend,model,providerToken)=when {
            Llm.isDeepSeek(selected)->Triple("https://api.deepseek.com",selected.removePrefix("deepseek:"),BuildConfig.DEEPSEEK_API.trim())
            Llm.isAny(selected)->Triple("https://anymodel.org/v1",selected.removePrefix("any:"),Llm.anyKey)
            selected.startsWith("compatible:")->Triple(prefs.getString("custom_url", "").orEmpty(),selected.removePrefix("compatible:"),prefs.getString("custom_token", "").orEmpty())
            Llm.isChatGpt(selected)->Triple("https://api.openai.com/v1",selected.removePrefix("chatgpt:"),ChatGptAccount.access())
            else->error("Для Codex выбери DeepSeek, AnyModel, ChatGPT или совместимый Responses API")
        }
        if(backend.isBlank()||providerToken.isBlank())error("Настрой адрес и ключ выбранного провайдера")
        val id=UUID.randomUUID().toString()
        val task=JSONObject().put("goal",goal).put("base_url",backend).put("model",model).put("provider_token",providerToken).put("previous_run_id", Prefs.codexPreviousRun.toLongOrNull() ?: 0L)
        val data=JSONObject().put("ref","main").put("inputs",JSONObject().put("request_id",id).put("payload",encrypt(task.toString(),pat)))
        call(pat,"$ROOT/actions/workflows/TEN_CODEX_TASK.yml/dispatches",data)
        val deadline=System.currentTimeMillis()+25*60_000L
        var runId=0L
        while(System.currentTimeMillis()<deadline){
            if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException("Прервано")
            if(runId==0L){
                val runs=JSONObject(call(pat,"$ROOT/actions/workflows/TEN_CODEX_TASK.yml/runs?event=workflow_dispatch&per_page=100")).optJSONArray("workflow_runs")
                if(runs!=null)for(i in 0 until runs.length()){
                    val r=runs.getJSONObject(i);if(r.optString("display_title").contains(id)){runId=r.getLong("id");break}
                }
            }else{
                val run=JSONObject(call(pat,"$ROOT/actions/runs/$runId"))
                if(run.optString("status")=="completed"){
                    if(run.optString("conclusion")!="success")error("Codex: задача GitHub Actions завершилась с ошибкой")
                    val artifacts=JSONObject(call(pat,"$ROOT/actions/runs/$runId/artifacts")).getJSONArray("artifacts")
                    val artifact=(0 until artifacts.length()).map { artifacts.getJSONObject(it) }.firstOrNull { it.optString("name")=="ten-result-$id" }
                        ?: error("Codex не вернул результат")
                    val url="$ROOT/actions/artifacts/${artifact.getLong("id")}/zip"
                    val req=Request.Builder().url(url).header("Authorization","Bearer $pat").header("Accept","application/vnd.github+json").build()
                    val encrypted=http.newCall(req).execute().use { response ->
                        if(!response.isSuccessful)error("Не удалось скачать результат Codex: HTTP ${response.code}")
                        ZipInputStream(response.body?.byteStream() ?: error("Нет результата")).use { zip ->
                            var entry=zip.nextEntry;var text=""
                            while(entry!=null){if(entry.name.endsWith("ten-result.enc")){val out=java.io.ByteArrayOutputStream();val buf=ByteArray(4096);var n=0;while(zip.read(buf).also{n=it}!=-1){if(out.size()+n>40_000_000)error("Слишком большой результат");out.write(buf,0,n)};text=out.toString("UTF-8");break};entry=zip.nextEntry}
                            text.ifBlank { error("Нет файла результата Codex") }
                        }
                    }
                    val result=JSONObject(decrypt(encrypted,pat))
                    if(!result.optBoolean("ok"))error(result.optString("error","Codex не выполнил задачу"))
                    val text=result.getString("result").takeIf{it.isNotBlank()}?:error("Codex вернул пустой результат")
                    val archive=result.optString("archive", "").takeUnless { it.isBlank() || it == "null" }
                    val file=archive?.let {
                        val bytes=Base64.decode(it,Base64.DEFAULT)
                        require(bytes.size<=25_000_000) { "Файл результата слишком большой" }
                        val dir=java.io.File(ctx.filesDir,"codex_output").apply { mkdirs() }
                        val f=java.io.File(dir,"codex_${System.currentTimeMillis()}.zip");f.writeBytes(bytes)
                        val uri=androidx.core.content.FileProvider.getUriForFile(ctx,"${ctx.packageName}.files",f)
                        Docs.FileRef(f.name,uri,"application/zip")
                    }
                    Prefs.codexPreviousRun=runId.toString()
                    return Result(text,file)
                }
            }
            Thread.sleep(5000)
        }
        error("Codex не завершился за 25 минут; проверь очередь GitHub Actions")
    }
}
