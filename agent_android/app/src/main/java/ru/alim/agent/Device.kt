package ru.alim.agent

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.LocationManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Бытовые функции телефона: SIM, фонарик, громкость, режим звонка, «Не беспокоить», батарея, геолокация, календарь, контакты. */
object Device {
    private val ru = Locale("ru")
    private fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    // ——— SIM-карты ———
    data class Sim(val subId: Int, val slot: Int, val carrier: String, val number: String)

    fun sims(ctx: Context): List<Sim> {
        if (Build.VERSION.SDK_INT < 22 || !has(ctx, Manifest.permission.READ_PHONE_STATE)) return emptyList()
        val sm = ctx.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return runCatching {
            @Suppress("MissingPermission")
            sm.activeSubscriptionInfoList?.map {
                Sim(it.subscriptionId, it.simSlotIndex, it.carrierName?.toString().orEmpty().ifBlank { "SIM${it.simSlotIndex + 1}" }, it.number?.toString().orEmpty())
            }.orEmpty().sortedBy { it.slot }
        }.getOrDefault(emptyList())
    }

    /** Подобрать subId по «1»/«2», номеру слота, названию оператора или номеру. null — не выбрать однозначно. */
    fun pickSim(ctx: Context, spec: String): Int? {
        val list = sims(ctx)
        if (list.isEmpty()) return null
        if (list.size == 1) return list[0].subId
        val s = spec.trim().lowercase()
        if (s.isBlank()) return null
        when (s) { "1", "первой", "первая", "первую", "sim1", "сим1" -> return list.firstOrNull { it.slot == 0 }?.subId
                   "2", "второй", "вторая", "вторую", "sim2", "сим2" -> return list.firstOrNull { it.slot == 1 }?.subId }
        list.firstOrNull { it.carrier.lowercase().contains(s) || (it.number.isNotBlank() && it.number.contains(s)) }?.let { return it.subId }
        return null
    }

    /** Нужен ли выбор SIM (две карты и spec не задал). Возвращает вопрос или null. */
    fun simChoice(ctx: Context, spec: String): String? {
        val list = sims(ctx)
        if (list.size < 2 || pickSim(ctx, spec) != null) return null
        return "у тебя две SIM: " + list.joinToString(", ") { "${it.slot + 1} — ${it.carrier}" } + ". На какой?"
    }

    // ——— фонарик ———
    private var torchId: String? = null
    fun flashlight(ctx: Context, on: Boolean): String {
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return "нет камеры"
        return runCatching {
            val id = torchId ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: return "на телефоне нет вспышки"
            torchId = id
            cm.setTorchMode(id, on)
            if (on) "фонарик включён" else "фонарик выключен"
        }.getOrElse { "не вышло с фонариком: ${it.message}" }
    }

    // ——— громкость / режим звонка ———
    fun volume(ctx: Context, percent: Int): String {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return "нет звука"
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val v = (percent.coerceIn(0, 100) * max / 100.0).toInt()
        return runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, v, AudioManager.FLAG_SHOW_UI); "громкость $percent%" }
            .getOrElse { "не вышло: ${it.message}" }
    }

    /** mode: normal / vibrate / silent */
    fun ringer(ctx: Context, mode: String): String {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return "нет звука"
        val m = when (mode.lowercase()) {
            "silent", "тихий", "беззвучный", "без звука" -> AudioManager.RINGER_MODE_SILENT
            "vibrate", "вибро", "вибрация" -> AudioManager.RINGER_MODE_VIBRATE
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        // беззвучный/вибро на новых Android требуют доступа к «Не беспокоить»
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        if (m != AudioManager.RINGER_MODE_NORMAL && Build.VERSION.SDK_INT >= 23 && !nm.isNotificationPolicyAccessGranted) {
            openDndAccess(ctx); return "дай разрешение «Доступ к режиму Не беспокоить» для «Пилота», и повтори"
        }
        return runCatching { am.ringerMode = m; when (m) {
            AudioManager.RINGER_MODE_SILENT -> "беззвучный режим"; AudioManager.RINGER_MODE_VIBRATE -> "вибрация"; else -> "звук включён" } }
            .getOrElse { "не вышло: ${it.message}" }
    }

    // ——— «Не беспокоить» ———
    fun dnd(ctx: Context, on: Boolean): String {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java) ?: return "нет доступа"
        if (Build.VERSION.SDK_INT < 23) return "не поддерживается на этой версии"
        if (!nm.isNotificationPolicyAccessGranted) { openDndAccess(ctx); return "дай разрешение «Доступ к режиму Не беспокоить» для «Пилота», и повтори" }
        return runCatching {
            nm.setInterruptionFilter(if (on) android.app.NotificationManager.INTERRUPTION_FILTER_NONE else android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
            if (on) "«Не беспокоить» включён" else "«Не беспокоить» выключен"
        }.getOrElse { "не вышло: ${it.message}" }
    }

    private fun openDndAccess(ctx: Context) {
        runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // ——— батарея ———
    fun battery(ctx: Context): String {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging == true
        val pm = ctx.getSystemService(PowerManager::class.java)
        val saver = if (pm?.isPowerSaveMode == true) ", энергосбережение включено" else ""
        return if (level < 0) "не вижу заряд" else "заряд $level%${if (charging) ", заряжается" else ""}$saver"
    }

    fun openBatterySettings(ctx: Context): String {
        return runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "открыл настройки энергосбережения — включи там" }
            .getOrElse { "не открылись настройки батареи" }
    }

    // ——— геолокация ———
    /** Последнее известное местоположение + город. null — нет разрешения или координат. */
    fun location(ctx: Context): Pair<Pair<Double, Double>, String>? {
        if (!has(ctx, Manifest.permission.ACCESS_FINE_LOCATION) && !has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val loc = runCatching {
            @Suppress("MissingPermission")
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { if (lm.isProviderEnabled(it)) lm.getLastKnownLocation(it) else null }
                .maxByOrNull { it.time }
        }.getOrNull() ?: return null
        val city = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(ctx, ru).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }.orEmpty()
        }.getOrDefault("")
        return (loc.latitude to loc.longitude) to city
    }

    // ——— календарь ———
    private fun primaryCalendarId(ctx: Context): Long? {
        if (!has(ctx, Manifest.permission.WRITE_CALENDAR)) return null
        return ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
            null, null, null)?.use { c ->
            var best: Long? = null
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (c.getInt(2) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    if (c.getInt(1) == 1) return id
                    if (best == null) best = id
                }
            }
            best
        }
    }

    /** Создать событие. start/end — миллисекунды. */
    fun createEvent(ctx: Context, title: String, startMs: Long, endMs: Long, where: String): String {
        if (!has(ctx, Manifest.permission.WRITE_CALENDAR)) return "нет доступа к календарю — выдай его в «Подключениях»"
        val calId = primaryCalendarId(ctx) ?: return "не нашёл календарь для записи"
        val tz = java.util.TimeZone.getDefault().id
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, if (endMs > startMs) endMs else startMs + 3600_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, tz)
            if (where.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, where)
        }
        val uri = runCatching { ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) }.getOrNull()
            ?: return "не удалось записать событие"
        val f = SimpleDateFormat("d MMM, HH:mm", ru)
        return "записал в календарь: «$title» на ${f.format(Date(startMs))}"
    }

    fun readEvents(ctx: Context, startMs: Long, endMs: Long): String {
        if (!has(ctx, Manifest.permission.READ_CALENDAR)) return "нет доступа к календарю"
        val f = SimpleDateFormat("d MMM HH:mm", ru)
        val out = ArrayList<String>()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, startMs); ContentUris.appendId(this, endMs)
        }.build()
        ctx.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.EVENT_LOCATION),
            null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext() && out.size < 30) {
                val title = c.getString(0).orEmpty().ifBlank { "(без названия)" }
                out += "${f.format(Date(c.getLong(1)))} — $title" + (c.getString(2)?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: "")
            }
        }
        return if (out.isEmpty()) "событий нет" else out.joinToString("\n")
    }

    // ——— сохранить контакт ———
    fun addContact(ctx: Context, name: String, number: String): String {
        if (!has(ctx, Manifest.permission.WRITE_CONTACTS)) return "нет доступа к контактам — выдай его в «Подключениях»"
        val ops = ArrayList<android.content.ContentProviderOperation>()
        ops.add(android.content.ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build())
        ops.add(android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name).build())
        ops.add(android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE).build())
        return runCatching { ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops); "сохранил контакт «$name» — $number" }
            .getOrElse { "не вышло сохранить: ${it.message}" }
    }

    // ——— заметки (простой список в памяти приложения) ———
    fun noteAdd(text: String): String { val l = Prefs.notes.toMutableList(); l.add(text.trim()); Prefs.notes = l; return "записал: «${text.trim()}»" }
    fun noteList(): String { val l = Prefs.notes; return if (l.isEmpty()) "заметок нет" else l.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n") }
    fun noteDone(which: String): String {
        val l = Prefs.notes.toMutableList()
        val idx = which.trim().toIntOrNull()?.minus(1)
        if (idx != null && idx in l.indices) { val t = l.removeAt(idx); Prefs.notes = l; return "убрал: «$t»" }
        val m = l.indexOfFirst { it.contains(which.trim(), true) }
        if (m >= 0) { val t = l.removeAt(m); Prefs.notes = l; return "убрал: «$t»" }
        return "не нашёл такую заметку"
    }

    /** Номер месяца по слову («декабря», «мая», «март») или null. */
    private fun month(w: String): Int? = when {
        w.startsWith("январ") -> 1; w.startsWith("феврал") -> 2; w.startsWith("март") -> 3; w.startsWith("апрел") -> 4
        w == "май" || w == "мая" -> 5; w.startsWith("июн") -> 6; w.startsWith("июл") -> 7; w.startsWith("август") -> 8
        w.startsWith("сентябр") -> 9; w.startsWith("октябр") -> 10; w.startsWith("ноябр") -> 11; w.startsWith("декабр") -> 12
        else -> null
    }

    /**
     * Дата и время из слов модели → миллисекунды (местное). Понимает «2026-12-01 10:00», «2026-12-01T10:00», «01.12.2026 10:00»,
     * «01.12 10:00», «1 декабря 10:00», «1 декабря» (тогда 10:00), «завтра 15:00», «в 18:30», «18:30». Дата без года в прошлом — следующий год.
     * Дата названа, но не разобрана — null (раньше «1 декабря» превращалось в 01:00 сегодня).
     */
    fun parseWhen(spec: String): Long? = parseWhenAt(spec, java.time.LocalDateTime.now())?.atZone(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli()

    fun parseWhenAt(spec: String, now: java.time.LocalDateTime): java.time.LocalDateTime? {
        var rest = " " + spec.lowercase().replace('ё', 'е').replace('t', ' ').trim() + " "
        if (rest.isBlank()) return null
        var date: java.time.LocalDate? = null
        var yearGiven = false
        fun cut(v: String) { rest = rest.replaceFirst(v, " ") }
        Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(rest)?.let { m ->
            date = runCatching { java.time.LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull() ?: return null
            yearGiven = true; cut(m.value)
        }
        if (date == null) Regex("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{2,4})").find(rest)?.let { m ->
            var y = m.groupValues[3].toInt(); if (y < 100) y += 2000
            date = runCatching { java.time.LocalDate.of(y, m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull() ?: return null
            yearGiven = true; cut(m.value)
        }
        if (date == null) Regex("(\\d{1,2})\\s+([а-я]+)(?:\\s+(\\d{4}))?").findAll(rest).firstOrNull { month(it.groupValues[2]) != null }?.let { m ->
            val y = m.groupValues[3].toIntOrNull()?.also { yearGiven = true } ?: now.year
            date = runCatching { java.time.LocalDate.of(y, month(m.groupValues[2])!!, m.groupValues[1].toInt()) }.getOrNull() ?: return null
            cut(m.value)
        }
        // «01.12 10:00» — дата, только если рядом есть время через двоеточие (иначе «18.30» — это время)
        if (date == null && Regex("\\d{1,2}:\\d{2}").containsMatchIn(rest)) Regex("(?<![\\d:])(\\d{1,2})\\.(\\d{1,2})(?![\\d:])").find(rest)?.let { m ->
            date = runCatching { java.time.LocalDate.of(now.year, m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull()
            if (date != null) cut(m.value)
        }
        if (date == null) date = when {
            rest.contains("послезавтра") -> now.toLocalDate().plusDays(2)
            rest.contains("завтра") -> now.toLocalDate().plusDays(1)
            rest.contains("сегодня") -> now.toLocalDate()
            else -> null
        }
        // месяц словом есть, а дата не собралась — лучше переспросить, чем поставить не туда
        if (date == null && Regex("[а-я]+").findAll(rest).any { month(it.value) != null }) return null
        val tm = Regex("(?<!\\d)(\\d{1,2})[:.](\\d{2})(?!\\d)").find(rest) ?: Regex("(?:^|\\s)(?:в|к)\\s+(\\d{1,2})(?!\\d)").find(rest)
        val h = tm?.groupValues?.get(1)?.toIntOrNull()
        val mi = tm?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        if (h != null && (h > 23 || mi > 59)) return null
        if (date == null && h == null) return null
        var dt = (date ?: now.toLocalDate()).atTime(h ?: 10, mi)
        if (date == null && dt.isBefore(now.minusMinutes(1))) dt = dt.plusDays(1)
        if (date != null && !yearGiven && dt.toLocalDate().isBefore(now.toLocalDate())) dt = dt.plusYears(1)
        return dt
    }

    /** Как дату увидит Алим: «1 декабря 2026, вт, 10:00». */
    fun whenLabel(ms: Long): String = java.text.SimpleDateFormat("d MMMM yyyy, EEE, HH:mm", java.util.Locale("ru")).format(java.util.Date(ms))
}
