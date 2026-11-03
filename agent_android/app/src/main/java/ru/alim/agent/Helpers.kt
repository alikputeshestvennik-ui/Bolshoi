package ru.alim.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Помощники Тени: отдельные «я» с чистой головой.
 *  - parallel: до 4 независимых заданий одновременно (города, магазины, даты), каждый сдаёт отчёт с цифрами и источниками;
 *  - check: независимая проверка готового ответа (видит только просьбу, ответ и отчёт — не рассуждения главной).
 * Помощникам нельзя ничего необратимого: только поиск, чтение и расчёты.
 */
object Helpers {
    /** Что разрешено помощникам: смотреть и считать, но не писать людям, не платить, не трогать телефон. */
    private val SAFE = setOf("now", "weather", "web_search", "web_read", "web_page", "calc", "time_calc", "shop_search", "flights", "hotels",
        "maps_route", "maps_search", "youtube_search", "tg_channel", "history_search", "memory_search")
    private val CHECK = setOf("now", "calc", "time_calc", "web_search", "web_read", "web_page", "flights", "hotels")

    class Result(val title: String, val report: String, val steps: Int, val ok: Boolean)

    private fun json(t: String): JSONObject? = runCatching { val a = t.indexOf('{'); val b = t.lastIndexOf('}'); JSONObject(t.substring(a, b + 1)) }.getOrNull()

    private fun toolsText(allowed: Set<String>) = Tools.list.filter { it.name in allowed }.joinToString("\n") { "- ${it.name}: ${it.help.take(260)}" }

    /** Модель для помощников: как у главной (Руки); для проверки — своя, лучше другой «породы». */
    fun model(): String = Prefs.smartModel
    fun checkModel(): String = Prefs.checkModel.ifBlank { Prefs.smartModel }

    /** Один помощник: свой короткий цикл «подумал → инструмент → результат», до maxSteps шагов. */
    private suspend fun loop(ctx: Context, core: Agent.Core, system: String, task: String, allowed: Set<String>, model: String, maxSteps: Int,
                             step: Agent.Step?, mustVerify: Boolean = false): Pair<String, Int> {
        var used = 0
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", task))
        var n = 0
        while (n < maxSteps) {
            n++
            if (n == maxSteps) msgs.put(JSONObject().put("role", "user").put("content", "Шаги кончились. Сейчас — только итог (tool null) из того, что уже узнал."))
            val r = runCatching { Llm.chat(model, msgs, 3000, json = true) }.getOrElse { e -> return ("помощник не смог: ${e.message?.take(120)}" to n) }
            core.addCost(r.cost(model), r.inTok, r.outTok)
            val j = json(r.text) ?: run {
                msgs.put(JSONObject().put("role", "assistant").put("content", r.text.take(1500)))
                msgs.put(JSONObject().put("role", "user").put("content", "Ответ должен быть одним JSON-объектом. Повтори."))
                null
            } ?: continue
            msgs.put(JSONObject().put("role", "assistant").put("content", r.text.take(3000)))
            val tool = j.optString("tool").takeIf { it.isNotBlank() && it != "null" }
            if (tool == null) {
                val rep = j.optString("report").ifBlank { j.toString() }
                // проверяющий сказал «ОК», ничего не проверив, — так не годится: один раз отправляем проверять
                if (mustVerify && used < 2 && n < maxSteps - 1 && Regex("^\\s*(ок|ok)\\W*$", RegexOption.IGNORE_CASE).containsMatchIn(rep)) {
                    msgs.put(JSONObject().put("role", "user").put("content", "Ты ещё ничего не проверил инструментами. Проверь сейчас 2 главных факта (решающий рейс/автобус/поезд и его цену), потом вердикт."))
                    continue
                }
                return (rep to n)
            }
            used++
            val t = Tools.byName(tool)
            if (t == null || tool !in allowed) { msgs.put(JSONObject().put("role", "user").put("content", "РЕЗУЛЬТАТ: инструмент «$tool» тебе недоступен. Можно: ${allowed.joinToString()}.")); continue }
            val args = j.optJSONObject("args") ?: JSONObject()
            withContext(Dispatchers.Main) { step?.detail = j.optString("step").ifBlank { tool } }
            val res = withTimeoutOrNull(90_000) { runCatching { withContext(Dispatchers.IO) { t.run(ctx, args) } }.getOrElse { e -> "ошибка: ${e.message}" } } ?: "инструмент не ответил за 90 с"
            msgs.put(JSONObject().put("role", "user").put("content", "РЕЗУЛЬТАТ $tool: ${res.take(7000)}"))
        }
        return ("не успел за $maxSteps шагов" to n)
    }

    private fun now() = java.text.SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", java.util.Locale("ru")).format(java.util.Date())

    /** Параллельные задания. Возвращает текст для главной Тени: отчёты по каждому. */
    suspend fun parallel(ctx: Context, tasks: List<Pair<String, String>>): String {
        val core = Agent.cur()
        val list = tasks.take(4)
        val t0 = System.currentTimeMillis()
        val system = "Ты — помощник Тени, личного агента Алима. Тебе дали ОДНО задание — выполни только его и сдай отчёт. Сейчас: ${now()}.\n" +
            "Отвечай ТОЛЬКО JSON: {\"step\": \"что делаешь, 2-5 слов\", \"tool\": \"имя\", \"args\": {}} — вызвать инструмент (один за раз), " +
            "или {\"tool\": null, \"report\": \"отчёт\"} — итог.\n" +
            "Отчёт: только факты — цифры, даты, время, цены, названия, с источником у каждой цифры (сайт или ссылка). Чего не нашёл — так и пиши «нет данных: …». Ничего не выдумывай и не бери цифры из памяти.\n" +
            "Инструменты:\n" + toolsText(SAFE)
        val steps = list.mapIndexed { i, (title, _) -> Agent.Step("Помощник ${i + 1} · $title") }
        withContext(Dispatchers.Main) { steps.forEach { core.steps.add(it) } }
        val res = coroutineScope {
            list.mapIndexed { i, (title, task) ->
                async(Dispatchers.IO) {
                    val (rep, n) = loop(ctx, core, system, "ЗАДАНИЕ: $task", SAFE, model(), 12, steps[i])
                    withContext(Dispatchers.Main) { steps[i].done = true; steps[i].detail = "$n шагов · " + rep.take(300) }
                    Result(title, rep, n, !rep.startsWith("помощник не смог"))
                }
            }.awaitAll()
        }
        val secs = (System.currentTimeMillis() - t0) / 1000
        return "ОТЧЁТЫ ПОМОЩНИКОВ (${res.size} одновременно, $secs с; сложи их сам, недостающее доищи сам):\n" +
            res.joinToString("\n\n") { r -> "### ${r.title}${if (!r.ok) " — не справился" else ""}\n${r.report.take(5000)}" }
    }

    /**
     * Независимая проверка готового ответа. null — замечаний нет (или проверить не вышло);
     * иначе — список ошибок, что исправить.
     */
    suspend fun check(ctx: Context, ask: String, answer: String, report: String, evidence: String = ""): String? {
        val core = Agent.cur()
        val step = Agent.Step("Независимая проверка")
        withContext(Dispatchers.Main) { core.steps.add(step) }
        val system = "Ты — независимый проверяющий. Другой агент выполнил просьбу Алима; ты видишь только просьбу, его ответ и отчёт. Сейчас: ${now()}.\n" +
            "Найди ОШИБКИ, которые подведут Алима: неверная арифметика (пересчитай calc), не выполненные условия просьбы (бюджет, сроки, «успеть к…», без пересадок, количество людей), " +
            "даты и время (часовые пояса, ночь, прибытие после дедлайна), забытые части (обратная дорога, ночёвка, трансфер), цифры без источника или противоречащие друг другу. " +
            "ОБЯЗАТЕЛЬНО до вердикта проверь сам инструментами 2-3 главных факта, от которых зависит решение (самый дорогой или решающий отрезок, расписание, цена) — без проверки «ОК» не принимается. " +
            "Ищи противоречия между найденным в ходе работы (список ниже) и ответом: если раньше находилось другое расписание или цена — это ошибка, пока не доказано обратное.\n" +
            "Не придирайся к стилю и оформлению — только то, что меняет решение или деньги.\n" +
            "Отвечай ТОЛЬКО JSON: {\"step\": \"…\", \"tool\": \"имя\", \"args\": {}} — проверить инструментом; " +
            "или итог: {\"tool\": null, \"report\": \"ОК\"} если ошибок нет, иначе {\"tool\": null, \"report\": \"1) что не так — как должно быть; 2) …\"}.\n" +
            "Инструменты:\n" + toolsText(CHECK)
        val task = "ПРОСЬБА АЛИМА:\n$ask\n\nОТВЕТ АГЕНТА:\n${answer.take(5000)}\n\nОТЧЁТ/ТАБЛИЦА АГЕНТА (JSON):\n${report.take(8000)}" +
            (if (evidence.isNotBlank()) "\n\nЧТО НАХОДИЛОСЬ ПО ХОДУ РАБОТЫ (результаты инструментов, кратко):\n${evidence.take(6000)}" else "")
        val got = runCatching { loop(ctx, core, system, task, CHECK, checkModel(), 8, step, mustVerify = true) }.getOrNull()
        if (got == null) { withContext(Dispatchers.Main) { step.done = true; step.detail = "проверить не вышло" }; return null }
        val (rep, n) = got
        val clean = rep.trim()
        val ok = clean.isBlank() || Regex("^(ок|ok|ошибок нет|замечаний нет|всё верно|все верно)\\W*$", RegexOption.IGNORE_CASE).containsMatchIn(clean) || clean.startsWith("не успел") || clean.startsWith("помощник не смог")
        withContext(Dispatchers.Main) { step.done = true; step.detail = if (ok) "ошибок не нашёл ($n шагов)" else clean.take(400) }
        return if (ok) null else clean
    }

    /** Важная задача — ошибка дорого стоит: покупки, оплата, билеты, договоры, крупные суммы, или Алим сам попросил проверить. */
    fun important(text: String): Boolean = Prefs.checkAll || Regex(
        "важн|купи|покупк|оплат|заплат|забронир|бронь|билет|договор|контракт|перевед|перевод|кредит|ипотек|страхов|проверь (всё|все|независимо|дважды)|перепроверь",
        RegexOption.IGNORE_CASE).containsMatchIn(text)
}
