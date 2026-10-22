package ru.alim.agent

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Передаю телефон другу»: цикл глаза→решение→руки. Друг смотрит на экран, делает один шаг, смотрит снова.
 * Алим видит плашку поверх всех приложений: что делает друг, кнопки «Стоп» и «👁», и может в любой момент прервать.
 * Отправку, заказ, публикацию друг делает только после «ОК» Алима. Денежные переводы внутри банков не трогает.
 */
object HandsAgent {
    enum class St { IDLE, RUNNING, CONFIRM, ENDED }

    var state by mutableStateOf(St.IDLE); private set
    var fid by mutableStateOf(Friends.MAIN); private set
    var task by mutableStateOf(""); private set
    var step by mutableStateOf(""); private set
    var eyes by mutableStateOf(false)          // 👁 — смотреть снимком на каждом шаге
    var paused by mutableStateOf(false); private set
    var confirmText by mutableStateOf(""); private set
    private var confirmAnswer: CompletableDeferred<Boolean>? = null

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var app: Context? = null
    private var overlay: HandsOverlay? = null
    private var pendingStart: Pair<String, String>? = null

    val active: Boolean get() = state == St.RUNNING || state == St.CONFIRM
    fun model(): String = Prefs.handsModel

    /** Служба включена Алимом в настройках? */
    val serviceOn: Boolean get() = Hands.ready

    fun start(ctx: Context, friendId: String, request: String) {
        if (active) return
        app = ctx.applicationContext
        fid = friendId; task = request.trim(); step = "Готовлюсь…"; paused = false; eyes = false
        if (!Hands.ready) {
            // служба ещё не включена — запомним и попросим включить
            pendingStart = friendId to request
            Chat.of(friendId).note("Чтобы управлять телефоном, включи доступ в Настройки → Спец. возможности → «Свои». Открой: ⋮ → Настройки → Руки на телефоне.")
            state = St.ENDED
            return
        }
        state = St.RUNNING
        overlay = HandsOverlay(Hands.inst!!).also { it.show() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        job = scope!!.launch { runCatching { loop() }.onFailure { finish("Что-то пошло не так: ${it.message?.take(100)}") } }
    }

    /** Служба только что включилась — если ждали, стартуем. */
    fun onServiceReady() {
        val p = pendingStart ?: return
        pendingStart = null
        app?.let { start(it, p.first, p.second) }
    }

    fun stop() { if (active) finish("Остановил по твоей команде.") }
    fun togglePause() { paused = !paused }
    fun reset() { if (state == St.ENDED) state = St.IDLE }

    fun answerConfirm(yes: Boolean) { confirmAnswer?.complete(yes) }

    private fun say(text: String) { Chat.of(fid).botSays(text.take(600)) }

    // ——— основной цикл ———

    private suspend fun loop() {
        val h = Hands.inst ?: return finish("Доступ к экрану пропал.")
        val sys = system()
        val history = JSONArray()
        var lastAsk = ""
        say("📱 Беру телефон: $task")
        delay(600)
        var i = 0
        while (i < 28 && state == St.RUNNING) {
            i++
            while (paused && state == St.RUNNING) { step = "Пауза — тронь «▶», чтобы продолжить"; delay(400) }
            if (state != St.RUNNING) break
            step = "Смотрю на экран"
            overlay?.update()
            delay(350)
            val screen = h.observe()
            val shot = if (eyes || i == 1) withContext(Dispatchers.IO) { h.screenshot() } else null

            val userContent = JSONArray()
                .put(JSONObject().put("type", "text").put("text",
                    "Задача: $task\nШаг ${i}.\n$screen\n\nЧто делаешь дальше? Ответь ТОЛЬКО JSON."))
            if (shot != null) userContent.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", shot)))

            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
            // короткая память о прошлых шагах
            val hist = (0 until history.length()).toList().takeLast(8)
            for (k in hist) msgs.put(history.getJSONObject(k))
            msgs.put(JSONObject().put("role", "user").put("content", userContent))

            val r = withContext(Dispatchers.IO) { Llm.chat(model(), msgs, 500) }
            history.put(JSONObject().put("role", "user").put("content", "[шаг $i: $screen]".take(1200)))
            history.put(JSONObject().put("role", "assistant").put("content", r.text.take(600)))

            val j = parse(r.text) ?: run { say(r.text.trim().take(300).ifBlank { "Не понял экран." }); break }
            val action = j.optString("action").trim().lowercase()
            val target = j.optInt("target", -1)
            val text = j.optString("text")
            val sayMsg = j.optString("say").trim()
            step = j.optString("step").trim().ifBlank { action }
            overlay?.update()

            if (sayMsg.isNotBlank() && sayMsg != lastAsk) { say(sayMsg); lastAsk = sayMsg }

            when (action) {
                "done" -> { finish(sayMsg.ifBlank { "Готово." }); return }
                "ask" -> { finish(sayMsg.ifBlank { "Нужна твоя подсказка." }); return }
                "wait" -> { step = "Жду загрузку"; delay(1600) }
                "back" -> { Hands.go { h.back() }; delay(700) }
                "home" -> { Hands.go { h.home() }; delay(700) }
                "open" -> {
                    val okOpen = CompletableDeferred<Boolean>()
                    Hands.go { okOpen.complete(h.openApp(j.optString("app"))) }
                    if (!okOpen.await()) say("Не нашёл приложение «${j.optString("app")}».")
                    delay(1500)
                }
                "scroll" -> { val d = j.optString("dir", "down") != "up"; Hands.go { h.scroll(d) }; delay(700) }
                "type" -> {
                    val ok = CompletableDeferred<Boolean>()
                    Hands.go { ok.complete(h.setText(target, text)) }
                    if (!ok.await()) say("Не получилось ввести текст.")
                    delay(500)
                }
                "tap" -> {
                    // отправить / заказать / оплатить / удалить — спрашиваем Алима
                    if (j.optBoolean("commit") || looksCommitting(j)) {
                        if (!confirm(j.optString("commit_text").ifBlank { "Подтвердить: ${step}?" })) {
                            say("Отменил. Что дальше?"); finish("Отменил действие, жду тебя."); return
                        }
                    }
                    val ok = CompletableDeferred<Boolean>()
                    Hands.go { ok.complete(h.tap(target)) }
                    if (!ok.await()) say("Не нашёл, куда нажать (№$target).")
                    delay(900)
                }
                else -> { say("Не понял, что делать дальше."); break }
            }
        }
        if (state == St.RUNNING) finish("Остановился после $i шагов. Если не доделал — напиши, продолжу.")
    }

    private fun looksCommitting(j: JSONObject) = false // полагаемся на флаг commit от модели

    private suspend fun confirm(text: String): Boolean {
        confirmText = text; state = St.CONFIRM
        val d = CompletableDeferred<Boolean>(); confirmAnswer = d
        overlay?.update()
        val ok = runCatching { d.await() }.getOrDefault(false)
        confirmAnswer = null; confirmText = ""
        if (state == St.CONFIRM) state = St.RUNNING
        overlay?.update()
        return ok
    }

    private fun finish(text: String) {
        state = St.ENDED
        job?.cancel(); scope?.cancel(); scope = null
        Hands.go { overlay?.hide(); overlay = null }
        step = ""
        say(text)
        Chat.of(fid).note("📱 Телефон снова у тебя")
    }

    private fun parse(t: String): JSONObject? {
        val a = t.indexOf('{'); val b = t.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return runCatching { JSONObject(t.substring(a, b + 1)) }.getOrNull()
    }

    private fun system(): String = buildString {
        val f = Friends.get(fid)
        append("Ты — ${f.name}, и сейчас ты управляешь телефоном Алима вместо него, чтобы выполнить его задачу. ")
        append("Ты видишь экран как список пронумерованных элементов и текст на нём; можешь нажимать, вводить текст, листать, открывать приложения, жать «назад»/«домой».\n")
        append("Отвечай ТОЛЬКО одним JSON без markdown:\n")
        append("{\"step\":\"что делаю, 2-4 слова (видно Алиму)\",\"action\":\"tap|type|scroll|open|back|home|wait|ask|done\",")
        append("\"target\":номер элемента,\"text\":\"что ввести\",\"app\":\"название приложения\",\"dir\":\"down|up\",")
        append("\"commit\":false,\"commit_text\":\"что подтвердить\",\"say\":\"короткое сообщение Алиму или пусто\"}\n")
        append("Действия:\n")
        append("- tap: нажать элемент target. type: ввести text в поле target. scroll: листать dir. open: открыть приложение app. back/home: назад/домой. wait: подождать загрузку.\n")
        append("- ask: не можешь продолжить без Алима (нужен пароль, выбор, данные) — опиши в say и остановись.\n")
        append("- done: задача выполнена — итог в say (1-2 фразы, что сделал или что узнал).\n")
        append("ВАЖНО про безопасность:\n")
        append("- Любой необратимый шаг — отправить сообщение, опубликовать, заказать, оформить, удалить, подтвердить оплату — помечай commit=true и напиши в commit_text, что именно подтвердить. Приложение спросит Алима, и только после «ОК» нажатие сработает.\n")
        append("- Перевод денег внутри банковских приложений ты НЕ делаешь и не доводишь до подтверждения. Посмотреть баланс и операции — можно. Дошло до перевода — action ask, попроси Алима сделать это самому.\n")
        append("- PIN, пароли, коды из SMS ты не вводишь и не зачитываешь. Попросили — action ask.\n")
        append("- Оплату картой (ввод данных карты) не делаешь. Доводи до страницы оплаты и action ask «осталось оплатить».\n")
        append("- Делай по одному шагу. После каждого увидишь новый экран. Не выдумывай, чего не видишь: если нужного элемента нет — листай или open нужное приложение.\n")
        append("- Экран не загрузился, пусто, крутится — action wait.\n")
        append("Сейчас: ").append(SimpleDateFormat("EEEE, HH:mm", Locale("ru")).format(Date())).append(".\n")
        append("Что ты знаешь об Алиме:\n").append(runCatching { Memory.forPrompt(app!!, 20) }.getOrDefault("")).append('\n')
    }
}
