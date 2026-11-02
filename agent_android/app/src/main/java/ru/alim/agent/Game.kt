package ru.alim.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Игры с загаданным секретом: детектив, «Угадай город», «20 вопросов», квест-комната.
 * Секрет загадывает сильная модель одним запросом и хранит Тень — Алим его не видит.
 * Ходы ведёт быстрая модель, сверяясь с секретом. Ответ/обвинение проверяет код — подыграть нечему.
 */
object Game {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    class Kind(val id: String, val title: String, val limit: Int, val what: String, val brief: String)

    val kinds = listOf(
        Kind("detective", "Детектив", 12, "преступника", "Ты — следователь. Допрашивай, осматривай, сопоставляй. Обвинить можно один раз."),
        Kind("city", "Угадай город", 20, "город", "Я загадал город. Задавай вопросы, на которые можно ответить «да» или «нет»."),
        Kind("q20", "20 вопросов", 20, "загаданное", "Я загадал предмет, животное или явление. Вопросы — с ответом «да»/«нет»."),
        Kind("room", "Квест-комната", 15, "код от двери", "Ты заперт в комнате. Осматривай вещи, комбинируй находки и назови код от двери."),
    )
    private fun kind(id: String) = kinds.firstOrNull { it.id == id }

    // ———————————————— состояние ————————————————

    private fun file(fid: String) = File(Chat.app.filesDir, "game_$fid.json")
    private fun load(fid: String): JSONObject? = runCatching { JSONObject(file(fid).readText()) }.getOrNull()?.takeIf { !it.optBoolean("over") }
    private fun save(fid: String, g: JSONObject) { runCatching { file(fid).writeText(g.toString()) } }
    private fun end(fid: String, g: JSONObject) { g.put("over", true); save(fid, g) }

    fun active(fid: String) = load(fid) != null

    // ———————————————— распознавание ————————————————

    private fun startKind(t: String): String? {
        val l = t.lowercase().replace('ё', 'е')
        val game = Regex("игр|поигра|сыгра|загадай|давай|сделай|можешь|хочу|запусти|начн").containsMatchIn(l)
        return when {
            Regex("детектив|расследован|следовател|сыщик|убийств[оа]? .*(игр|раскр)").containsMatchIn(l) && game -> "detective"
            Regex("угада\\w* город|загадай город|город.{0,12}угада").containsMatchIn(l) -> "city"
            Regex("20 вопрос|двадцать вопрос|загадай (что|кого|предмет|животн|что-нибудь)|угадай,? что я|угадаю,? что").containsMatchIn(l) -> "q20"
            Regex("квест|комнат\\w* (выхода|с загадк)|escape|выбраться из комнат").containsMatchIn(l) && game -> "room"
            else -> null
        }
    }
    private fun wantsMenu(t: String) = Regex("^\\s*(давай|хочу|го|может)?,?\\s*(по)?игра(ем|ть)|^\\s*давай сыграем|во что (по)?игра|какие (есть )?игры", RegexOption.IGNORE_CASE).containsMatchIn(t.trim()) && t.length < 60
    private fun quits(t: String) = Regex("^\\s*(стоп|хватит|закончим|выйти|выход|не хочу)( играть| игру| из игры)?\\s*[.!]?\\s*$|сдаюсь|скажи ответ|какой ответ|кто убийца\\?*\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(t)
    private fun surrender(t: String) = Regex("сдаюсь|скажи ответ|какой ответ|кто убийца", RegexOption.IGNORE_CASE).containsMatchIn(t)

    private fun norm(s: String) = s.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun aliases(o: JSONObject?): List<String> {
        if (o == null) return emptyList()
        val a = ArrayList<String>()
        o.optString("answer").takeIf { it.isNotBlank() }?.let(a::add)
        o.optString("name").takeIf { it.isNotBlank() }?.let(a::add)
        o.optJSONArray("aliases")?.let { j -> for (i in 0 until j.length()) j.optString(i).takeIf { it.isNotBlank() }?.let(a::add) }
        return a.map(::norm).filter { it.length >= 2 }.distinct()
    }

    /** Совпадает ли названное с загаданным. null — названо несколько вариантов сразу. */
    private fun judge(g: JSONObject, guess: String): Boolean? {
        val s = g.optJSONObject("secret") ?: return false
        val n = " " + norm(guess) + " "
        fun hit(al: List<String>) = al.any { a -> n.contains(" $a ") || (a.length >= 5 && n.contains(a)) || (a.length >= 5 && norm(guess).length >= 4 && a.contains(norm(guess))) }
        val right = hit(aliases(s).ifEmpty { listOf(norm(s.optString("culprit"))) } + aliases(s.optJSONObject("culprit_obj")))
        val others = s.optJSONArray("suspects")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
            ?.filter { !it.optBoolean("culprit") }?.any { hit(aliases(it)) } ?: false
        if (right && others) return null
        return right
    }

    // ———————————————— загадывание ————————————————

    private fun genPrompt(k: Kind): String = when (k.id) {
        "detective" -> "Придумай короткое, но ЧЕСТНОЕ детективное дело для текстовой игры, где игрок — следователь. По-русски. Дело должно решаться логикой по уликам, без угадывания. " +
            "Ровно 4 подозреваемых, один виновен. У каждого — мотив и алиби; у виновного в алиби дыра, которую доказывают 2-3 улики вместе; у невиновных — по одной подозрительной детали, которая объясняется невинно. " +
            "8 улик: 4-5 настоящих, 3 ложных следа. Хронология по времени. Ничего сверхъестественного, без жестокости в подробностях. Верни ТОЛЬКО JSON: " +
            "{\"title\":\"…\",\"intro\":\"что видит игрок в начале: место, жертва, обстоятельства, список подозреваемых с одной строкой о каждом (3-6 абзацев, без разгадки)\"," +
            "\"suspects\":[{\"name\":\"Имя Фамилия\",\"role\":\"племянник\",\"aliases\":[\"имя\",\"фамилия\",\"роль\"],\"culprit\":false,\"motive\":\"…\",\"alibi\":\"…\",\"truth\":\"что делал на самом деле\",\"knows\":\"что расскажет на допросе, если спросить правильно\"}]," +
            "\"clues\":[{\"where\":\"где найти\",\"text\":\"что это\",\"real\":true,\"means\":\"что доказывает\"}],\"timeline\":[\"21:00 …\"],\"method\":\"как совершено\"," +
            "\"answer\":\"имя виновного\",\"aliases\":[\"имя\",\"фамилия\",\"роль виновного\"],\"solution\":\"разгадка 4-6 фраз: кто, как, почему, какие улики это доказывают\"}"
        "city" -> "Загадай для игры «угадай город» один город (чаще России, иногда СНГ или мира; не самый очевидный, но известный). Верни ТОЛЬКО JSON: " +
            "{\"answer\":\"Город\",\"aliases\":[\"варианты написания\"],\"country\":\"…\",\"region\":\"…\",\"facts\":[\"12 точных фактов о городе: население, река/море, климат, чем известен, когда основан, достопримечательности\"]," +
            "\"intro\":\"одна фраза-приглашение без подсказок\",\"solution\":\"2-3 фразы про город после отгадки\"}"
        "q20" -> "Загадай для игры «20 вопросов» что-то одно, понятное взрослому: предмет быта, животное, еду, транспорт или явление природы (не абстракцию). Верни ТОЛЬКО JSON: " +
            "{\"answer\":\"…\",\"aliases\":[\"синонимы\"],\"category\":\"предмет|животное|еда|транспорт|явление\",\"facts\":[\"10 фактов: размер, материал, где встречается, для чего, живое ли, съедобное ли и т.п.\"]," +
            "\"intro\":\"одна фраза без подсказок\",\"solution\":\"1-2 фразы после отгадки\"}"
        else -> "Придумай квест-комнату для текстовой игры, по-русски: игрок заперт, дверь открывается кодом из 4 цифр. Цепочка из 4-5 шагов: находки комбинируются, каждый шаг логичен и выводим из описаний. Верни ТОЛЬКО JSON: " +
            "{\"title\":\"…\",\"intro\":\"описание комнаты: что видно сразу (5-8 предметов), без решений\",\"objects\":[{\"name\":\"…\",\"look\":\"что видно при осмотре\",\"hidden\":\"что найдётся, если сделать правильное действие\",\"needs\":\"что нужно сделать/чем\"}]," +
            "\"chain\":[\"шаг 1 …\"],\"answer\":\"1234\",\"aliases\":[\"1234\"],\"solution\":\"как получается код, 3-4 фразы\"}"
    }

    private fun genModel(): String = Prefs.smartModel.takeIf { Llm.anyKey.isNotBlank() || Llm.isYandex(it) } ?: Prefs.laneModel

    private fun json(t: String): JSONObject? = runCatching {
        val a = t.indexOf('{'); val b = t.lastIndexOf('}')
        JSONObject(t.substring(a, b + 1))
    }.getOrNull()

    private fun start(store: ChatStore, k: Kind) {
        store.note("Загадываю: ${k.title.lowercase()}…")
        io.launch {
            val model = genModel()
            val req = JSONArray().put(JSONObject().put("role", "system").put("content", "Ты — автор логических игр. Отвечай только JSON без пояснений."))
                .put(JSONObject().put("role", "user").put("content", genPrompt(k) + "\nСлучайное зерно: ${System.nanoTime() % 100000} — не повторяй типовые сюжеты."))
            var secret: JSONObject? = null
            for (attempt in 0..1) {
                val r = runCatching { Llm.chat(model, req, 4000, json = true, patient = true) }.getOrNull() ?: continue
                runCatching { Journal.addLane(Chat.app, model, (r.inTok + r.outTok).toLong(), AnyPrice.rub(model, (r.inTok + r.outTok).toLong()) ?: 0.0) }
                secret = json(r.text)?.takeIf { it.optString("answer").isNotBlank() }
                if (secret != null) break
            }
            if (secret == null) { store.add(Msg(store.newId(), false, "Не получилось загадать — модель вернула ерунду. Попробуй ещё раз.", System.currentTimeMillis(), status = "read")); return@launch }
            // у детектива помечаем виновного среди подозреваемых
            secret.optJSONArray("suspects")?.let { a -> val ans = norm(secret.optString("answer"))
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { s -> if (aliases(s).any { it == ans || ans.contains(it) && it.length >= 4 }) s.put("culprit", true) } }
            val g = JSONObject().put("kind", k.id).put("secret", secret).put("turns", 0).put("limit", k.limit).put("started", System.currentTimeMillis())
            save(store.fid, g)
            val intro = secret.optString("intro").ifBlank { k.brief }
            store.add(Msg(store.newId(), false, buildString {
                secret.optString("title").takeIf { it.isNotBlank() }?.let { append("**").append(it).append("**\n\n") }
                append(intro.trim()).append("\n\n")
                append("Правила: ").append(k.brief).append(" Ходов: ${k.limit}. «Сдаюсь» — покажу ответ, «стоп игра» — выйти.")
            }, System.currentTimeMillis(), status = "read", meta = "${k.title} · загадано"))
        }
    }

    // ———————————————— ход ————————————————

    private fun masterSystem(k: Kind, g: JSONObject): String =
        "Ты — ведущий игры «${k.title}» в чате с Алимом. По-русски, на «ты», живо и грамотно, без грубого сленга. Ниже ТАЙНА — Алим её не видит. " +
        "Отвечай строго по тайне: ничего не выдумывай, что ей противоречит; чего в тайне нет — отвечай нейтрально и непротиворечиво. НИКОГДА не называй и не намекай прямо на ответ, не подтверждай догадки — только факты по запросу. " +
        when (k.id) {
            "detective" -> "Игрок допрашивает и осматривает. Подозреваемые отвечают в своём характере: невиновные говорят правду (могут скрывать своё неловкое), виновный лжёт по алиби, но не противоречит уликам, которые игрок уже нашёл. Улику выдавай, только если игрок ищет в нужном месте или задаёт нужный вопрос. Ответ — 2-6 фраз."
            "room" -> "Игрок действует в комнате. Описывай результат действия по тайне; скрытое находится только при правильном действии. Ответ — 1-4 фразы."
            else -> "Отвечай одним словом «Да», «Нет», «Частично» или «Не знаю» и, если нужно, коротким пояснением в 3-8 слов, не раскрывающим ответ. Вопрос не на да/нет — попроси переформулировать."
        } +
        "\nЕсли игрок называет окончательный ответ (${if (k.id == "detective") "обвиняет кого-то, «преступник — …», «арестовать …»" else if (k.id == "room") "вводит или называет код" else "«это …?», «ответ — …», конкретное название"}) — ответь РОВНО строкой «#guess: <что он назвал>» и больше ничего. Проверю я сам." +
        "\n\nТАЙНА:\n" + g.optJSONObject("secret")?.toString()

    private fun history(store: ChatStore, since: Long): String = store.msgs.filter { it.ts >= since && !it.note }.takeLast(24)
        .joinToString("\n") { m -> (if (m.me) "Алим: " else "Ведущий: ") + m.text.replace('\n', ' ').take(900) }

    private fun reveal(k: Kind, s: JSONObject, won: Boolean, guess: String): String = buildString {
        val ans = s.optString("answer")
        if (won) append("**Верно!** ${if (k.id == "detective") "Преступник — $ans." else "Это $ans."}\n\n")
        else if (guess.isNotBlank()) append("**Нет.** ${if (k.id == "detective") "Преступник — $ans." else "Правильный ответ — $ans."}\n\n")
        else append("Ответ: **$ans**.\n\n")
        s.optString("solution").takeIf { it.isNotBlank() }?.let { append(it.trim()) }
        if (k.id == "detective" && !won) s.optJSONArray("clues")?.let { a ->
            val real = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optBoolean("real") }
            if (real.isNotEmpty()) append("\n\nКлючевые улики:\n").append(real.take(4).joinToString("\n") { "- ${it.optString("text")} (${it.optString("where")})" })
        }
    }

    private fun finish(store: ChatStore, g: JSONObject, k: Kind, won: Boolean, guess: String) {
        end(store.fid, g)
        store.add(Msg(store.newId(), false, reveal(k, g.optJSONObject("secret") ?: JSONObject(), won, guess), System.currentTimeMillis(), status = "read",
            meta = "${k.title} · ${if (won) "победа" else "конец"} · ходов ${g.optInt("turns")}", buttons = listOf("Ещё ${k.title.lowercase()}", "Другая игра")))
    }

    /** Сообщение Алима: начать игру, сыграть ход или ничего (false — пусть отвечает обычный чат). */
    fun handle(store: ChatStore, text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val g = load(store.fid)
        val k0 = startKind(t) ?: Regex("^ещё (.+)$", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.let { n -> kinds.firstOrNull { it.title.lowercase() == n.lowercase().trim() }?.id }
            ?: kinds.firstOrNull { it.title.equals(t.trimEnd('.', '!'), true) }?.id
        if (g == null) {
            if (k0 != null) { start(store, kind(k0)!!); return true }
            if (wantsMenu(t) || t.equals("Другая игра", true)) {
                store.add(Msg(store.newId(), false, "Давай. Выбирай:\n- **Детектив** — расследуешь дело и называешь преступника\n- **Угадай город** — вопросы «да/нет»\n- **20 вопросов** — угадываешь загаданную вещь\n- **Квест-комната** — выбираешься из запертой комнаты",
                    System.currentTimeMillis(), status = "read", buttons = kinds.map { it.title }))
                return true
            }
            return false
        }
        val k = kind(g.optString("kind")) ?: return false
        // во время игры: новая игра поверх — закрываем старую
        if (k0 != null && k0 != k.id) { end(store.fid, g); start(store, kind(k0)!!); return true }
        if (quits(t)) {
            if (surrender(t)) finish(store, g, k, false, "")
            else { end(store.fid, g); store.add(Msg(store.newId(), false, "Игру закрыл. Ответ был: **${g.optJSONObject("secret")?.optString("answer")}**.", System.currentTimeMillis(), status = "read")) }
            return true
        }
        // явно не про игру (просьба-задача) — отдаём обычному чату, игра ждёт
        if (Agent.of(store.fid).hardText(t) && !Regex("допрос|осмотр|обыск|улик|алиби|это |код|открыва", RegexOption.IGNORE_CASE).containsMatchIn(t)) return false
        val turns = g.optInt("turns") + 1
        g.put("turns", turns); save(store.fid, g)
        val over = turns > g.optInt("limit", k.limit)
        val bubble = store.newId()
        store.add(Msg(bubble, false, "…", System.currentTimeMillis(), status = "read", meta = "${k.title} · ход ${minOf(turns, k.limit)} из ${k.limit}"))
        io.launch {
            val model = Prefs.laneModel
            val user = history(store, g.optLong("started")) + "\n\nСейчас Алим пишет: " + t +
                (if (over) "\n(Ходы кончились. Если это не окончательный ответ — скажи, что ходы кончились и пусть назовёт ответ.)" else "") +
                (if (turns == k.limit) "\n(Это последний ход — после ответа напомни, что пора назвать ответ.)" else "")
            val req = JSONArray().put(JSONObject().put("role", "system").put("content", masterSystem(k, g))).put(JSONObject().put("role", "user").put("content", user))
            val r = runCatching { Llm.chat(model, req, 700, json = false) }.getOrNull()
            r?.let { runCatching { Journal.addLane(Chat.app, model, (it.inTok + it.outTok).toLong(), AnyPrice.rub(model, (it.inTok + it.outTok).toLong()) ?: 0.0) } }
            val out = r?.text?.trim().orEmpty()
            val gm = Regex("#guess\\s*:?\\s*(.+)", RegexOption.IGNORE_CASE).find(out)
            // модель не распознала, а ходы кончились — считаем сообщение ответом
            val guess = gm?.groupValues?.get(1)?.trim() ?: if (over && out.isBlank()) t else null
            if (guess != null || (over && judge(g, t) == true)) {
                store.delete(listOf(bubble))
                val gs = guess ?: t
                when (judge(g, gs)) {
                    null -> store.add(Msg(store.newId(), false, "Назови одного — кого именно обвиняешь?", System.currentTimeMillis(), status = "read"))
                    true -> finish(store, g, k, true, gs)
                    false -> if (k.id == "detective" || over) finish(store, g, k, false, gs)
                        else store.add(Msg(store.newId(), false, "Нет, это не «$gs». Ходов осталось: ${(k.limit - turns).coerceAtLeast(0)}.", System.currentTimeMillis(), status = "read",
                            meta = "${k.title} · ход $turns из ${k.limit}"))
                }
                if (judge(g, gs) == false && k.id != "detective" && !over) { g.put("turns", turns); save(store.fid, g) }
                return@launch
            }
            store.update(bubble) { it.copy(text = out.ifBlank { "Связь подвела — повтори ход." }) }
            if (out.isBlank()) { g.put("turns", turns - 1); save(store.fid, g) }
        }
        return true
    }
}
