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

    /** Почему не разделили — видно под шагом (чтобы понять, что пошло не так). */
    var why = ""

    /** Делится ли просьба на независимые части. null — нет (делаем как обычно). */
    suspend fun plan(text: String, model: String, core: Agent.Core): Plan? {
        why = ""
        val sys = "Ты решаешь, можно ли большую просьбу разделить на НЕЗАВИСИМЫЕ части, чтобы их сделали одновременно разные исполнители. Ответ — только JSON:\n" +
            "{\"split\": true|false, \"why\": \"коротко почему\", \"shared\": \"…\", \"parts\": [{\"title\": \"Москва\", \"task\": \"…\"}]}\n" +
            "split=true, если просьба сравнивает или подбирает 2–5 перечисленных вариантов (города, направления, страны, даты, магазины, модели товара) и по каждому нужно найти данные. " +
            "Общая дорога в начале (от дома до аэропорта) делению НЕ мешает — её выносят в shared.\n" +
            "Пример: «Сравни поездку из Ялты в Москву, Минск и Самару 2–8 января, отель 3 ночи, бюджет 55 000, вернуться до 09:00 8-го» → split=true, " +
            "parts: Москва, Минск, Самара (в task каждой — вся просьба, но только про свой город), shared: «автобус/дорога Ялта → аэропорт Сочи 2 января после 14:00 и обратно с прибытием в Ялту до 09:00 8 января».\n" +
            "split=false: один маршрут (даже с пересадками), договориться с человеком, исправить документ, «найди лучший» без перечня вариантов.\n" +
            "task каждой части — полная просьба Алима, но ТОЛЬКО про этот вариант: все условия (даты, время, сроки, бюджет, сколько человек, ночей, что найти, рейтинг) перепиши дословно; другие варианты не упоминай; таблицу и файл не проси.\n" +
            "shared — участок, ОДИНАКОВЫЙ для всех частей, с датами и сроками; нет такого — \"\". Не выноси в shared то, что у частей разное."
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys)).put(JSONObject().put("role", "user").put("content", "ПРОСЬБА АЛИМА:\n$text"))
        var j: JSONObject? = null
        for (attempt in 0..1) {
            // сеть — только в фоне: в главном потоке Android запрос обрывает
            val r = runCatching { withContext(Dispatchers.IO) { Llm.chat(model, msgs, 2500, json = true) } }.getOrElse { e -> why = "планировщик не ответил: ${e.javaClass.simpleName} ${e.message?.take(60).orEmpty()}"; null } ?: continue
            core.addCost(r.cost(model), r.inTok, r.outTok)
            j = json(r.text)
            if (j != null) break
            why = "ответ не JSON"
            msgs.put(JSONObject().put("role", "assistant").put("content", r.text.take(1500))).put(JSONObject().put("role", "user").put("content", "Повтори строго одним JSON-объектом по схеме."))
        }
        val jj = j ?: return null
        if (!jj.optBoolean("split")) { why = jj.optString("why").ifBlank { "модель решила не делить" }; return null }
        val a = jj.optJSONArray("parts") ?: run { why = "нет списка частей"; return null }
        val parts = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Part(it.optString("title").ifBlank { "Часть" }, it.optString("task")) }.filter { it.task.length > 20 }
        if (parts.size !in 2..5) { why = "частей ${parts.size}"; return null }
        return Plan(jj.optString("shared").trim(), parts)
    }

    /** Потолок на все части одной задачи, ₽: дошли — части останавливаются, главная собирает из найденного. */
    const val LIMIT_RUB = 20.0
    private val running = java.util.Collections.synchronizedList(ArrayList<Agent.Core>())
    @Volatile private var overLimit = false
    private fun spent(): Double = synchronized(running) { running.sumOf { it.cost } } + doneCost
    @Volatile private var doneCost = 0.0
    /** Сколько частей не дали ничего — главной на них даём больше поисков. */
    @Volatile var failedParts = 0
    /** Сырые результаты инструментов всех частей. */
    @Volatile var evidence = ""
    private val ev = StringBuffer()

    /** Одна часть — отдельная Тень; шаги её видно строкой у главной. Возвращает (ответ, отчёт JSON, справилась ли). */
    private suspend fun child(main: Agent.Core, fid: String, task: String, step: Agent.Step): Triple<String, String, Boolean> {
        // отказ модели случаен (одна и та же просьба: две части работают, третья «нет инструментов») — один раз запускаем заново
        val first = once(main, fid, task, step)
        if (first.third || main.stopRequested() || overLimit || !Agent.of(fid).refused) return first
        withContext(Dispatchers.Main) { step.done = false; step.detail = "отказалась — запускаю заново" }
        return once(main, fid, task, step)
    }

    private suspend fun once(main: Agent.Core, fid: String, task: String, step: Agent.Step): Triple<String, String, Boolean> {
        val c = Agent.of(fid)
        val t0 = System.currentTimeMillis()
        withContext(Dispatchers.Main) { if (c.busy) c.stop(); c.start(task) }
        running.add(c)
        try {
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
                if (spent() > LIMIT_RUB) { overLimit = true; withContext(Dispatchers.Main) { c.stop() }; break }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // главную остановили (отмена корутины) — части не должны жить дальше и тратить деньги
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { c.stop() }
            throw e
        } finally {
            running.remove(c); doneCost += c.cost
            c.seen?.let { sb -> if (ev.length < 500_000) ev.append('\n').append(sb.toString().take(200_000)) }
            main.addCost(c.cost, c.tokIn.toInt(), c.tokOut.toInt())
        }
        val secs = (System.currentTimeMillis() - t0) / 1000
        val ok = !c.failed && !c.refused && c.result.isNotBlank()
        withContext(Dispatchers.Main) { step.done = true; step.detail = "${c.steps.size} шагов · $secs с · ${"%.1f".format(c.cost)} ₽" +
            (if (c.refused) " · отказалась — без неё: «${c.result.replace('\n', ' ').take(100)}»" else if (!ok) " · не доделала" else "") }
        return Triple(if (c.refused) "" else c.result, if (c.refused) "" else c.lastBest, ok)
    }

    /** Отчёт части без ссылок на фото и картинок — погода и места не должны обрезаться из-за длинных url. */
    private fun slim(rep: String): String = rep.replace(Regex("\"(photo|img|image|thumb|pic)\"\\s*:\\s*\"[^\"]*\",?"), "")
        .replace(Regex("https?://[^\"\\s]{60,}")) { it.value.take(60) }

    /** Сделать части. Возвращает текст для главной Тени: ответы и отчёты частей. */
    suspend fun run(main: Agent.Core, plan: Plan): String? {
        val t0 = System.currentTimeMillis()
        overLimit = false; doneCost = 0.0; failedParts = 0; ev.setLength(0); evidence = ""
        // старые чаты частей (из прошлой версии) — удалить: их переписка попадала частям в голову
        runCatching { Agent.app.filesDir.listFiles()?.filter { it.name.startsWith("chat_part:") }?.forEach { it.delete() } }
        var shared = ""
        if (plan.shared.isNotBlank()) {
            val st = Agent.Step("Общий участок · один раз для всех")
            withContext(Dispatchers.Main) { main.steps.add(st) }
            val (txt, rep, _) = child(main, "part:0",
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
        evidence = ev.toString()
        failedParts = res.count { !it.third && it.first.isBlank() }
        if (res.all { it.first.isBlank() }) return null
        val note = if (overLimit) " Части остановлены на лимите ${LIMIT_RUB.toInt()} ₽ — что не успели, по тем «нет данных» (или до 3 поисков)." else ""
        val secs = (System.currentTimeMillis() - t0) / 1000
        return buildString {
            append("ЧАСТИ ЗАДАЧИ УЖЕ СДЕЛАНЫ отдельными полными прогонами (${plan.parts.size} одновременно, $secs с). Им доверяй и заново НЕ ищи — можно максимум 3 поиска, только чтобы закрыть «нет данных».$note\n")
            if (shared.isNotBlank()) append("\n### Общий участок\n$shared\n")
            plan.parts.forEachIndexed { i, p ->
                if (!res[i].third && res[i].first.isBlank()) { append("\n### ${p.title}\nЧАСТЬ НЕ СПРАВИЛАСЬ — данных нет. Эту часть дособери сама (на неё можно до 6 поисков) или честно «нет данных».\n"); return@forEachIndexed }
                append("\n### ${p.title}\nОТВЕТ: ").append(res[i].first.take(3000)).append("\nОТЧЁТ (JSON): ").append(slim(res[i].second).take(12000)).append('\n')
            }
            append("\nНОЧИ: отель должен покрывать каждую ночь между приездом и отъездом. Если обратный рейс на день позже выезда из отеля — это лишняя платная ночь (добавь её в цену) или нужен рейс в день выезда; " +
                "«3 ночи» в просьбе — не повод оставить ночь без жилья. Не урезай ночи, которые часть посчитала правильно.\n")
            append("\nТеперь: сравни части по условиям Алима (жёсткие условия — сроки, бюджет — нарушать нельзя), выбери лучший вариант, " +
                "сделай таблицу file_create (лист на каждую часть: ВСЕ её участки с ценами и строкой ИТОГО; сводка — ссылками на ИТОГО листов) и дай финальный ответ (tool null, report с compare, best).")
        }
    }
}
