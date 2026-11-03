package ru.alim.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Большая задача из независимых вариантов (3 города, 3 магазина) — по частям, одновременно.
 * Каждая часть — полная копия Тени (та же модель, те же правила), будто Алим спросил только про этот вариант.
 * Общий для всех участок (дорога Ялта ↔ Сочи) ищется один раз до частей и даётся им готовым.
 * Главная Тень потом ничего не ищет заново — только сравнивает, собирает таблицу и пишет вывод.
 */
object Split {
    class Part(val title: String, val task: String)
    class Plan(val shared: String, val parts: List<Part>)

    private fun json(t: String): JSONObject? = runCatching { val a = t.indexOf('{'); val b = t.lastIndexOf('}'); JSONObject(t.substring(a, b + 1)) }.getOrNull()

    /** Делится ли просьба на независимые части. null — нет (делаем как обычно). */
    suspend fun plan(text: String, model: String, core: Agent.Core): Plan? {
        val sys = "Ты решаешь, можно ли большую просьбу разделить на НЕЗАВИСИМЫЕ части, чтобы их сделали одновременно разные исполнители. Ответ — только JSON:\n" +
            "{\"split\": true|false, \"shared\": \"…\", \"parts\": [{\"title\": \"Москва\", \"task\": \"…\"}]}\n" +
            "split=true — ТОЛЬКО если просьба сравнивает или подбирает 2–5 вариантов (города, направления, даты, магазины, модели), и каждый можно найти отдельно, не зная про остальные. " +
            "Одна поездка с пересадками, договориться с человеком, исправить документ, «найди лучший вариант» без перечня — split=false.\n" +
            "task каждой части — полная просьба Алима, но ТОЛЬКО про этот вариант: все условия (даты, время, сроки, бюджет, сколько человек, ночей, что найти, рейтинг) перепиши дословно; другие варианты не упоминай; таблицу и файл не проси.\n" +
            "shared — участок, ОДИНАКОВЫЙ для всех частей (например дорога от дома до общего аэропорта/вокзала и обратно), с датами и сроками: что именно найти. Нет такого — \"\". " +
            "Не выноси в shared то, что у частей разное."
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys)).put(JSONObject().put("role", "user").put("content", text))
        val r = runCatching { Llm.chat(model, msgs, 2500, json = true) }.getOrNull() ?: return null
        core.addCost(r.cost(model), r.inTok, r.outTok)
        val j = json(r.text) ?: return null
        if (!j.optBoolean("split")) return null
        val a = j.optJSONArray("parts") ?: return null
        val parts = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Part(it.optString("title").ifBlank { "Часть" }, it.optString("task")) }.filter { it.task.length > 20 }
        if (parts.size !in 2..5) return null
        return Plan(j.optString("shared").trim(), parts)
    }

    /** Одна часть — отдельная Тень; шаги её видно строкой у главной. Возвращает (ответ, отчёт JSON). */
    private suspend fun child(main: Agent.Core, fid: String, task: String, step: Agent.Step): Pair<String, String> {
        val c = Agent.of(fid)
        val t0 = System.currentTimeMillis()
        withContext(Dispatchers.Main) { c.start(task) }
        delay(300)
        while (true) {
            delay(800)
            val busy = withContext(Dispatchers.Main) {
                val last = c.steps.lastOrNull { it.text != "Думаю…" }
                if (last != null) step.detail = last.text.take(80) + (last.detail.takeIf { it.isNotBlank() }?.let { " · " + it.lineSequence().first().take(80) } ?: "")
                c.busy
            }
            if (!busy) break
            if (main.stopRequested()) { withContext(Dispatchers.Main) { c.stop() }; break }
        }
        main.addCost(c.cost, c.tokIn.toInt(), c.tokOut.toInt())
        val secs = (System.currentTimeMillis() - t0) / 1000
        withContext(Dispatchers.Main) { step.done = true; step.detail = "${c.steps.size} шагов · $secs с" + if (c.failed) " · не доделала" else "" }
        return c.result to c.lastBest
    }

    /** Сделать части. Возвращает текст для главной Тени: ответы и отчёты частей. */
    suspend fun run(main: Agent.Core, plan: Plan): String? {
        val t0 = System.currentTimeMillis()
        var shared = ""
        if (plan.shared.isNotBlank()) {
            val st = Agent.Step("Общий участок · один раз для всех")
            withContext(Dispatchers.Main) { main.steps.add(st) }
            val (txt, rep) = child(main, "part:0",
                "Это часть большой задачи Алима. Найди ТОЛЬКО это: ${plan.shared}\nВремя, цены и источник у каждой цифры; нет — «нет данных». Таблицу и файл не делай. В ответе — report с маршрутом (route).", st)
            shared = "ОТВЕТ: $txt\nОТЧЁТ: ${rep.take(4000)}"
        }
        val steps = plan.parts.mapIndexed { i, p -> Agent.Step("Часть ${i + 1} · ${p.title}") }
        withContext(Dispatchers.Main) { steps.forEach { main.steps.add(it) } }
        val res = coroutineScope {
            plan.parts.mapIndexed { i, p ->
                async {
                    val task = p.task +
                        (if (shared.isNotBlank()) "\n\nГОТОВО ЗАРАНЕЕ — общий участок, используй как есть и заново НЕ ищи:\n$shared" else "") +
                        "\n\nТаблицу и файл не делай — общую соберёт Тень. В ответе обязательно report: маршрут по участкам (route) с временем и ценой каждого, итоги (totals), источники."
                    child(main, "part:${i + 1}", task, steps[i])
                }
            }.awaitAll()
        }
        if (res.all { it.first.isBlank() }) return null
        val secs = (System.currentTimeMillis() - t0) / 1000
        return buildString {
            append("ЧАСТИ ЗАДАЧИ УЖЕ СДЕЛАНЫ отдельными полными прогонами (${plan.parts.size} одновременно, $secs с). Им доверяй и заново НЕ ищи — можно максимум 3 поиска, только чтобы закрыть «нет данных».\n")
            if (shared.isNotBlank()) append("\n### Общий участок\n$shared\n")
            plan.parts.forEachIndexed { i, p ->
                append("\n### ${p.title}\nОТВЕТ: ").append(res[i].first.take(4000)).append("\nОТЧЁТ (JSON): ").append(res[i].second.take(6000)).append('\n')
            }
            append("\nТеперь: сравни части по условиям Алима (жёсткие условия — сроки, бюджет — нарушать нельзя), выбери лучший вариант, " +
                "сделай таблицу file_create (лист на каждую часть: ВСЕ её участки с ценами и строкой ИТОГО; сводка — ссылками на ИТОГО листов) и дай финальный ответ (tool null, report с compare, best).")
        }
    }
}
