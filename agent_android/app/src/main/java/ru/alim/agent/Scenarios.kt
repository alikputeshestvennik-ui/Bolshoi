package ru.alim.agent

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Сохранённые шаблоны поручений; действующие задачи остаются в Agent, Deleg и Watch. */
object Scenarios {
    data class Item(val id: Long, val title: String, val instruction: String, val schedule: String = "manual", val hour: Int = 9, val minute: Int = 0, val paused: Boolean = false, val nextAt: Long = 0)

    val items = mutableStateListOf<Item>()
    private var loaded = false
    private const val PREFS = "scenarios"
    private const val KEY = "items"

    @Synchronized fun init(context: Context) {
        if (loaded) return
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val entries = JSONArray(raw)
        for (i in 0 until entries.length()) {
            val o = entries.getJSONObject(i)
            items.add(Item(o.getLong("id"), o.getString("title"), o.getString("instruction"), o.optString("schedule", "manual"), o.optInt("hour", 9), o.optInt("minute", 0), o.optBoolean("paused"), o.optLong("nextAt")))
        }
        loaded = true
    }

    @Synchronized fun save(context: Context, item: Item) {
        require(item.title.isNotBlank() && item.instruction.isNotBlank())
        init(context)
        val updated = items.filterNot { it.id == item.id } + item
        persist(context, updated)
        items.clear(); items.addAll(updated)
        Planner.plan(context)
    }

    @Synchronized fun delete(context: Context, id: Long) {
        init(context)
        val updated = items.filterNot { it.id == id }
        persist(context, updated)
        items.clear(); items.addAll(updated)
        Planner.plan(context)
    }

    private fun persist(context: Context, entries: List<Item>) {
        val a = JSONArray()
        entries.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title).put("instruction", it.instruction).put("schedule", it.schedule).put("hour", it.hour).put("minute", it.minute).put("paused", it.paused).put("nextAt", it.nextAt)) }
        check(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).commit())
    }

    fun next(schedule: String, hour: Int, minute: Int, after: Long = System.currentTimeMillis()): Long {
        if (schedule == "manual") return 0L
        val c = Calendar.getInstance().apply { timeInMillis = after; set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= after) c.add(Calendar.DAY_OF_YEAR, 1)
        if (schedule == "weekdays") while (c.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    fun nextDue(): Long = items.filter { !it.paused && it.schedule != "manual" && it.nextAt > 0 }.minOfOrNull { it.nextAt } ?: 0L

    fun run(item: Item): Boolean {
        if (Agent.main.busy || Agent.main.fastBusy || !Online.online) return false
        Chat.main.userEcho("Сценарий «${item.title}»: ${item.instruction}")
        Agent.start(item.instruction)
        return true
    }

    fun tick(context: Context) {
        val now = System.currentTimeMillis()
        val due = items.firstOrNull { !it.paused && it.schedule != "manual" && it.nextAt in 1..now } ?: return
        if (run(due)) save(context, due.copy(nextAt = next(due.schedule, due.hour, due.minute, now)))
        else save(context, due.copy(nextAt = now + 5 * 60_000L))
    }
}
