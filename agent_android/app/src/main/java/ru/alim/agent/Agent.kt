package ru.alim.agent

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Агент: голосовая задача → шаги. Каждый шаг модель отвечает JSON с одним инструментом;
 * результат возвращается ей следующим сообщением. Начинает быстрая модель (Haiku), сложное она передаёт Sonnet.
 */
object Agent {
    enum class Phase { IDLE, WORKING, CONFIRM, DONE }

    class Step(val text: String) { var done by mutableStateOf(false) }
    class Pending(val text: String, val answer: CompletableDeferred<Boolean>)

    var phase by mutableStateOf(Phase.IDLE); private set
    var task by mutableStateOf(""); private set
    val steps = mutableStateListOf<Step>()
    var pending by mutableStateOf<Pending?>(null); private set
    var result by mutableStateOf(""); private set
    var failed by mutableStateOf(false); private set
    var cost by mutableDoubleStateOf(0.0); private set
    var model by mutableStateOf(""); private set
    var startedAt by mutableLongStateOf(0L); private set
    var endedAt by mutableLongStateOf(0L); private set

    lateinit var app: Context
    var onSpeak: ((String) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    val busy: Boolean get() = phase == Phase.WORKING || phase == Phase.CONFIRM

    fun start(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        task = t; steps.clear(); result = ""; failed = false; cost = 0.0; pending = null
        startedAt = System.currentTimeMillis(); endedAt = 0L
        model = Prefs.fastModel; phase = Phase.WORKING
        job = scope.launch { loop(t) }
    }

    fun answer(yes: Boolean) { pending?.answer?.complete(yes) }

    fun stop() {
        if (!busy) return
        job?.cancel()
        pending?.answer?.cancel(); pending = null
        finish(false, "Остановил по твоей команде.")
    }

    fun close() { if (phase == Phase.DONE) phase = Phase.IDLE }

    private fun msg(role: String, text: String) = JSONObject().put("role", role).put("content", text)

    private fun system(): String = buildString {
        append("Ты — голосовой агент Алима в его Android-телефоне. Выполняешь просьбы: найти, позвонить, написать, ответить, показать.\n")
        append("Отвечай ТОЛЬКО одним JSON-объектом без markdown:\n")
        append("{\"step\": \"что делаешь, 2-5 слов\", \"tool\": \"имя инструмента или null\", \"args\": {}, \"say\": \"реплика Алиму\", \"done\": false}\n")
        append("Правила:\n")
        append("- Не больше одного инструмента за ответ. Результат придёт следующим сообщением.\n")
        append("- Задача выполнена или делать нечего — \"tool\": null, \"done\": true и короткий итог в \"say\" (1-2 фразы, по-русски, просто).\n")
        append("- Не хватает данных (кому, когда, какой) — спроси в \"say\" и \"done\": true.\n")
        append("- Людей сначала ищи через contacts_find. Похожих несколько — спроси, кого именно.\n")
        append("- Звонок, SMS, отправку фото и ответы приложение само подтвердит у Алима — просто вызывай инструмент.\n")
        append("- Никогда не пересылай и не зачитывай коды из SMS, пароли, данные банков.\n")
        append("- Для простого (одно-два действия) хватает тебя. Если задача сложная (несколько приложений, покупки, длинная цепочка, тонкий текст) — первым шагом вызови escalate.\n")
        append("Сейчас: ").append(SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale("ru")).format(Date()))
        append(". Город по умолчанию: ").append(Prefs.homeCity).append(".\n")
        append("Инструменты (аргументы — пример JSON):\n")
        append(Tools.describe())
    }

    private fun parse(t: String): JSONObject? {
        val a = t.indexOf('{'); val b = t.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return runCatching { JSONObject(t.substring(a, b + 1)) }.getOrNull()
    }

    private suspend fun loop(text: String) {
        val msgs = JSONArray().put(msg("system", system())).put(msg("user", text))
        var smart = false
        var final: String? = null
        try {
            var i = 0
            while (i < 14 && final == null) {
                i++
                val m = if (smart) Prefs.smartModel else Prefs.fastModel
                model = m
                val r = withContext(Dispatchers.IO) { Llm.chat(m, msgs) }
                cost += r.cost(m)
                msgs.put(msg("assistant", r.text))
                steps.lastOrNull()?.done = true
                val j = parse(r.text)
                if (j == null) { final = r.text.trim().take(500).ifBlank { "Готово." }; break }
                val tool = j.optString("tool").trim().takeIf { it.isNotEmpty() && it != "null" }
                val say = j.optString("say").trim()
                if (tool == null) { final = say.ifBlank { "Готово." }; break }
                val args = j.optJSONObject("args") ?: JSONObject()
                val stepText = j.optString("step").trim().ifBlank { tool }

                if (tool == "escalate") {
                    if (!smart) {
                        smart = true
                        steps.add(Step("Подключаю ${short(Prefs.smartModel)}").also { it.done = true })
                    }
                    msgs.put(msg("user", "Ты сильная модель, задача передана тебе. Продолжай."))
                    continue
                }
                val t = Tools.byName(tool)
                if (t == null) { msgs.put(msg("user", "Нет инструмента «$tool». Выбери из списка.")); continue }

                val step = Step(stepText); steps.add(step)
                var allowed = true
                t.confirm?.let { c ->
                    val d = CompletableDeferred<Boolean>()
                    pending = Pending(c(args), d); phase = Phase.CONFIRM
                    allowed = d.await()
                    pending = null; phase = Phase.WORKING
                }
                if (!allowed) {
                    step.done = true
                    msgs.put(msg("user", "Алим отменил это действие. Заверши задачу коротким ответом."))
                    continue
                }
                val res = try {
                    withContext(Dispatchers.IO) { t.run(app, args) }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { "ошибка: ${e.message}" }
                step.done = true
                msgs.put(msg("user", "РЕЗУЛЬТАТ $tool: ${res.take(3000)}"))
            }
            finish(true, final ?: "Слишком много шагов — остановился. Попробуй сформулировать проще.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finish(false, "Ошибка: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun finish(ok: Boolean, text: String) {
        steps.forEach { it.done = true }
        result = text; failed = !ok
        endedAt = System.currentTimeMillis()
        phase = Phase.DONE
        runCatching { Journal.add(app, TaskRec(startedAt, task, steps.size, short(model), cost, ok)) }
        if (ok && Prefs.speak) onSpeak?.invoke(text)
    }

    fun short(m: String): String = when {
        m.contains("haiku", true) -> "Haiku"
        m.contains("sonnet", true) -> "Sonnet"
        m.contains("opus", true) -> "Opus"
        else -> m.substringAfterLast('/').take(18)
    }
}
