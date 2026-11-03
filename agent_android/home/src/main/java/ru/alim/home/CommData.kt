package ru.alim.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject

/** Телефон и сообщения: читаем системные журналы, звоним и шлём СМС через систему. */
object Comm {
    val PHONE_PERMS = arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
    val SMS_PERMS = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS)

    fun has(c: Context, vararg p: String) = p.all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Последние 10 цифр — так сравниваем номера (+7 978…, 8 978…, 978…). */
    fun key(n: String) = n.filter { it.isDigit() }.takeLast(10)
    fun isPhone(a: String) = a.count { it.isDigit() } >= 10

    // ——— контакты ———

    class Contact(val name: String, val number: String)

    fun contacts(c: Context): List<Contact> {
        if (!has(c, Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = ArrayList<Contact>()
        runCatching {
            c.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { cur ->
                while (cur.moveToNext()) {
                    val name = cur.getString(0).orEmpty(); val num = cur.getString(1).orEmpty()
                    if (num.isNotBlank()) out += Contact(name.ifBlank { num }, num)
                }
            }
        }
        return out.distinctBy { it.name + "|" + key(it.number) }
    }

    fun names(list: List<Contact>): Map<String, String> = list.associate { key(it.number) to it.name }

    // ——— звонки ———

    class Call(val number: String, val name: String, val type: Int, val date: Long, val dur: Long) {
        val missed get() = type == CallLog.Calls.MISSED_TYPE || type == CallLog.Calls.REJECTED_TYPE
        val kind get() = when (type) {
            CallLog.Calls.INCOMING_TYPE -> "↘ входящий"
            CallLog.Calls.OUTGOING_TYPE -> "↗ исходящий"
            CallLog.Calls.MISSED_TYPE -> "↙ пропущенный"
            CallLog.Calls.REJECTED_TYPE -> "↙ отклонён"
            CallLog.Calls.BLOCKED_TYPE -> "× заблокирован"
            else -> "звонок"
        }
    }

    fun calls(c: Context, n: Int = 200): List<Call> {
        if (!has(c, Manifest.permission.READ_CALL_LOG)) return emptyList()
        val out = ArrayList<Call>()
        runCatching {
            c.contentResolver.query(CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                null, null, CallLog.Calls.DATE + " DESC")?.use { cur ->
                while (cur.moveToNext() && out.size < n) out += Call(cur.getString(0).orEmpty(), cur.getString(1).orEmpty(), cur.getInt(2), cur.getLong(3), cur.getLong(4))
            }
        }
        return out
    }

    fun call(c: Context, number: String): Boolean {
        val u = Uri.parse("tel:" + Uri.encode(number))
        return if (has(c, Manifest.permission.CALL_PHONE)) Apps.start(c, Intent(Intent.ACTION_CALL, u)) else Apps.start(c, Intent(Intent.ACTION_DIAL, u))
    }

    // ——— сообщения ———

    class Sms(val id: Long, val thread: Long, val addr: String, val body: String, val date: Long, val me: Boolean, val read: Boolean, val failed: Boolean)
    class Thread(val id: Long, val addr: String, val last: Sms, val unread: Int)

    /** Код подтверждения: 4–6 цифр и слова «код / пароль / code». */
    fun isCode(body: String) = Regex("(код|code|парол|подтвержд)", RegexOption.IGNORE_CASE).containsMatchIn(body) && Regex("(?<!\\d)\\d{4,6}(?!\\d)").containsMatchIn(body)
    /** Люди — обычные номера; банки и сервисы — буквенные отправители и короткие номера (900, 0611). */
    fun isService(addr: String) = !isPhone(addr)

    fun threads(c: Context): List<Thread> {
        if (!has(c, Manifest.permission.READ_SMS)) return emptyList()
        val last = LinkedHashMap<Long, Sms>(); val unread = HashMap<Long, Int>()
        runCatching {
            c.contentResolver.query(Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ),
                null, null, Telephony.Sms.DATE + " DESC")?.use { cur ->
                var n = 0
                while (cur.moveToNext() && n++ < 4000) {
                    val t = cur.getLong(1); val type = cur.getInt(5)
                    if (type == Telephony.Sms.MESSAGE_TYPE_INBOX && cur.getInt(6) == 0) unread[t] = (unread[t] ?: 0) + 1
                    if (t !in last) last[t] = Sms(cur.getLong(0), t, cur.getString(2).orEmpty(), cur.getString(3).orEmpty(), cur.getLong(4),
                        type != Telephony.Sms.MESSAGE_TYPE_INBOX, cur.getInt(6) != 0, type == Telephony.Sms.MESSAGE_TYPE_FAILED)
                }
            }
        }
        return last.values.map { Thread(it.thread, it.addr, it, unread[it.thread] ?: 0) }
    }

    fun messages(c: Context, thread: Long): List<Sms> {
        if (!has(c, Manifest.permission.READ_SMS)) return emptyList()
        val out = ArrayList<Sms>()
        runCatching {
            c.contentResolver.query(Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ),
                Telephony.Sms.THREAD_ID + "=?", arrayOf(thread.toString()), Telephony.Sms.DATE + " DESC")?.use { cur ->
                while (cur.moveToNext() && out.size < 300) {
                    val type = cur.getInt(5)
                    out += Sms(cur.getLong(0), thread, cur.getString(2).orEmpty(), cur.getString(3).orEmpty(), cur.getLong(4),
                        type != Telephony.Sms.MESSAGE_TYPE_INBOX, cur.getInt(6) != 0, type == Telephony.Sms.MESSAGE_TYPE_FAILED)
                }
            }
        }
        return out.reversed()
    }

    /** Отправить СМС. Система сама положит его в «Отправленные» — переписка обновится. */
    fun send(c: Context, addr: String, text: String): String? = runCatching {
        if (!has(c, Manifest.permission.SEND_SMS)) return "нет разрешения на отправку СМС"
        val sm: SmsManager = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        val parts = sm.divideMessage(text)
        if (parts.size > 1) sm.sendMultipartTextMessage(addr, null, parts, null, null) else sm.sendTextMessage(addr, null, text, null, null)
        null
    }.getOrElse { it.message ?: "не отправилось" }

    // ——— «Передать Тени» ———

    private val TEN = Uri.parse("content://ru.alim.agent.home")

    fun handoff(c: Context, who: String, addr: String, history: List<Sms>, goal: String, notes: String, confirm: Boolean, until: Long, manual: Boolean): String = runCatching {
        val h = JSONArray()
        history.takeLast(40).forEach { m -> h.put(JSONObject().put("me", m.me).put("t", m.body).put("ts", m.date)) }
        val b = Bundle().apply {
            putString("who", who); putString("addr", addr); putString("history", h.toString()); putString("goal", goal); putString("notes", notes)
            putBoolean("confirm", confirm); putLong("until", until); putBoolean("manual", manual)
        }
        c.contentResolver.call(TEN, "handoff", null, b)?.getString("msg") ?: "Тень не ответила"
    }.getOrElse { "Тень не ответила — обнови её до последней версии" }

    class HandoffState(val active: Boolean, val state: String)
    fun handoffState(c: Context, addr: String): HandoffState = runCatching {
        val r = c.contentResolver.call(TEN, "handoffState", addr, null)
        HandoffState(r?.getBoolean("active") == true, r?.getString("state").orEmpty())
    }.getOrDefault(HandoffState(false, ""))
    fun takeBack(c: Context, addr: String): String = runCatching { c.contentResolver.call(TEN, "takeback", addr, null)?.getString("msg") ?: "" }.getOrDefault("")
}
