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
    /** Таблицу собрал код — главной остаётся только вывод. */
    @Volatile var built = false
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

    private fun sheetName(t: String, used: MutableSet<String>): String {
        var n = t.replace(Regex("[\\[\\]:*?/\\\\']"), " ").trim().take(28).ifBlank { "Вариант" }
        var k = 2; val b = n
        while (!used.add(n)) n = "$b ${k++}"
        return n
    }

    /** Таблица кодом: лист на вариант (все отрезки, доплата за ночь, ИТОГО формулой) и сводка ссылками. (подпись шага, имя файла, сводка для модели) */
    private suspend fun buildTable(plan: Plan, plans: List<Trip.Plan>, ask: String): Triple<String, String, String>? {
        val budget = Regex("бюджет[^\\d]{0,20}(\\d{1,3}(?:[ \\u00A0]\\d{3})+|\\d{4,7})", RegexOption.IGNORE_CASE).find(ask)?.groupValues?.get(1)?.replace(Regex("\\D"), "")?.toIntOrNull()
        val deadline = Tools.deadline
        val used = HashSet<String>()
        val sheets = JSONArray()
        val sum = JSONArray().put(JSONArray(listOf("Вариант", "Итого, ₽", "Бюджет, ₽", "Запас, ₽", "Дома", "Срок", "Ночи", "Без цены")))
        val text = StringBuilder()
        plan.parts.forEachIndexed { i, p ->
            val pl = plans[i]
            val name = sheetName(p.title, used)
            val rows = JSONArray().put(JSONArray(listOf("Участок", "Когда", "Что", "Цена, ₽", "Источник", "Примечание")))
            val kindRu = mapOf("bus" to "Автобус", "train" to "Поезд", "flight" to "Перелёт", "stay" to "Жильё", "taxi" to "Такси", "car" to "Машина", "ship" to "Паром", "walk" to "Пешком")
            pl.rows.forEach { r -> rows.put(JSONArray(listOf(kindRu[r.kind] ?: r.kind, r.whenText, r.what, r.price ?: "нет данных", r.src, r.note))) }
            val last = pl.rows.size + 1
            rows.put(JSONArray(listOf("ИТОГО", "", "", "=SUM(D2:D$last)", "", if (pl.missing > 0) "без ${pl.missing} цен" else "")))
            val late = deadline != null && pl.end != null && pl.end.isAfter(deadline)
            pl.end?.let { rows.put(JSONArray(listOf("Дома", Trip.fmtDt(it), "", "", "", if (deadline == null) "" else if (late) "НЕ УСПЕВАЕТ к ${Trip.fmtDt(deadline)}" else "успевает к ${Trip.fmtDt(deadline)}"))) }
            sheets.put(JSONObject().put("name", name).put("rows", rows))
            val q = "'$name'"
            val r = sum.length() + 1
            sum.put(JSONArray(listOf(p.title, "=$q!D${last + 1}", budget ?: "", if (budget != null) "=C$r-B$r" else "", pl.end?.let { Trip.fmtDt(it) } ?: "нет данных",
                if (deadline == null || pl.end == null) "" else if (late) "НЕ УСПЕВАЕТ" else "успевает",
                if (pl.gaps.isEmpty()) "ок" else "доплата: " + pl.gaps.joinToString("; ") { it.substringBefore(":") }, pl.missing)))
            text.append("- ${p.title}: ИТОГО ${"%,d".format(pl.total).replace(',', ' ')} ₽")
            if (budget != null) text.append(if (pl.total <= budget) " (в бюджете, запас ${"%,d".format(budget - pl.total).replace(',', ' ')} ₽)" else " (СВЕРХ БЮДЖЕТА на ${"%,d".format(pl.total - budget).replace(',', ' ')} ₽)")
            pl.end?.let { text.append(", дома ${Trip.fmtDt(it)}") }
            if (deadline != null && pl.end != null) text.append(if (late) " — НЕ УСПЕВАЕТ к ${Trip.fmtDt(deadline)}" else " — успевает")
            if (pl.gaps.isNotEmpty()) text.append("; добавлена доплата: ").append(pl.gaps.joinToString("; "))
            if (pl.missing > 0) text.append("; без цены отрезков: ${pl.missing}")
            text.append('\n')
        }
        sheets.put(JSONObject().put("name", sheetName("Сводка", used)).put("rows", sum))
        val file = "Сравнение — " + plan.parts.joinToString(", ") { it.title }.take(60)
        val t = Tools.byName("file_create") ?: return null
        val args = JSONObject().put("type", "xlsx").put("name", file).put("sheets", sheets)
        var r = withContext(Dispatchers.IO) { t.run(Agent.app, args) }
        if (r.startsWith("ошибка")) r = withContext(Dispatchers.IO) { t.run(Agent.app, args.put("force", true)) }
        if (r.startsWith("ошибка")) return null
        return Triple("${plan.parts.size} листа + сводка · " + text.lines().filter { it.isNotBlank() }.joinToString(" · ") { it.removePrefix("- ").substringBefore(" ₽") + " ₽" }.take(140), file, text.toString())
    }

    /** Отчёт части без ссылок на фото и картинок — погода и места не должны обрезаться из-за длинных url. */
    private fun slim(rep: String): String = rep.replace(Regex("\"(photo|img|image|thumb|pic)\"\\s*:\\s*\"[^\"]*\",?"), "")
        .replace(Regex("https?://[^\"\\s]{60,}")) { it.value.take(60) }

    /** Сделать части. Возвращает текст для главной Тени: ответы и отчёты частей. */
    suspend fun run(main: Agent.Core, plan: Plan, ask: String = ""): String? {
        val t0 = System.currentTimeMillis()
        overLimit = false; doneCost = 0.0; failedParts = 0; ev.setLength(0); evidence = ""; built = false
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
        // все части сдали маршрут — таблицу, итоги, ночи и срок считает код; модели остаётся только вывод
        val reps = res.map { Rich.parse(it.second)?.optJSONObject("report") }
        val plans = reps.map { Trip.plan(it) }
        if (res.all { it.third } && plans.all { it.rows.any { r -> r.kind != "stay" } }) {
            val st = Agent.Step("Собираю таблицу · кодом"); withContext(Dispatchers.Main) { main.steps.add(st) }
            val made = runCatching { buildTable(plan, plans, ask) }.getOrNull()
            withContext(Dispatchers.Main) { st.done = true; st.detail = made?.first ?: "не вышло — соберёт модель" }
            if (made != null) {
                built = true
                return buildString {
                    append("ЧАСТИ ЗАДАЧИ СДЕЛАНЫ (${plan.parts.size} одновременно, $secs с), ТАБЛИЦУ «${made.second}» УЖЕ СОБРАЛ КОД — не пересоздавай её и НИЧЕГО не ищи.$note\n")
                    append("Итоги, ночи, срок и бюджет посчитаны кодом по маршрутам частей:\n").append(made.third).append('\n')
                    plan.parts.forEachIndexed { i, p ->
                        val r = reps[i]
                        append("\n### ${p.title}\n")
                        r?.optJSONArray("route")?.let { append("route: ").append(slim(it.toString()).take(3000)).append('\n') }
                        listOf("weather", "places", "stay", "sources").forEach { k -> r?.opt(k)?.let { append("$k: ").append(slim(it.toString()).take(2000)).append('\n') } }
                        append("кратко от части: ").append(res[i].first.replace('\n', ' ').take(800)).append('\n')
                    }
                    append("\nТеперь только финальный ответ (tool null): say — вывод словами с цифрами (что выбрать и почему, чего нет); " +
                        "report — route ЛУЧШЕГО (его route выше + строка доплаты за ночь, если код её добавил), compare всех вариантов с ИТОГО ровно как в таблице, totals, places и weather каждого (если есть), sources; best. " +
                        "Вариант с «НЕ УСПЕВАЕТ» или сверх бюджета не может быть лучшим. Числа — только из блоков выше.")
                }
            }
        }
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
