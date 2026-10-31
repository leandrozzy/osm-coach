package com.osmaicoach.collector

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Lembretes no calendário do celular: o aviso é disparado pelo app Calendário (do sistema), que o fabricante não
 * fecha junto com este app. É a reserva quando o celular apaga os alarmes e o push não acorda. Fica num calendário
 * local próprio ("OSM Coach avisos"). Sai um pouco depois do alarme local e é apagado se o local tocar.
 */
object CalendarSync {
    private const val ACCOUNT = "OSM Coach"
    private const val EXTRA_MS = 30_000L
    private val lock = Mutex()

    fun enabled(ctx: Context): Boolean = Settings.get(ctx, "cal_on", "0") == "1"

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("calsync", Context.MODE_PRIVATE)

    /** seq -> (início do evento, id do evento) */
    private fun read(ctx: Context): MutableMap<String, Pair<Long, Long>> {
        val j = runCatching { JSONObject(prefs(ctx).getString("ev", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val m = LinkedHashMap<String, Pair<Long, Long>>()
        for (k in j.keys()) j.optJSONObject(k)?.let { m[k] = Pair(it.optLong("at"), it.optLong("id")) }
        return m
    }

    private fun write(ctx: Context, m: Map<String, Pair<Long, Long>>) {
        val j = JSONObject()
        for ((k, v) in m) j.put(k, JSONObject().put("at", v.first).put("id", v.second))
        prefs(ctx).edit().putString("ev", j.toString()).apply()
    }

    /** Agenda principal da conta Google (a que o app Calendário do Google já avisa): é a que o celular acorda de verdade. */
    private fun googleCalendarId(ctx: Context): Long? {
        val cr = ctx.contentResolver
        cr.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.OWNER_ACCOUNT, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.VISIBLE),
            "${CalendarContract.Calendars.ACCOUNT_TYPE}=?", arrayOf("com.google"), null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(3) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR && c.getInt(4) == 1 && c.getString(1) == c.getString(2)) return c.getLong(0)
            }
        }
        return null
    }

    /** seq -> [título, texto] do evento, para o app notificar sozinho quando o calendário avisar. */
    private fun readTx(ctx: Context): JSONObject = runCatching { JSONObject(prefs(ctx).getString("tx", "{}") ?: "{}") }.getOrDefault(JSONObject())

    private fun writeTx(ctx: Context, j: JSONObject) { prefs(ctx).edit().putString("tx", j.toString()).apply() }

    private fun calendarId(ctx: Context): Long? {
        googleCalendarId(ctx)?.let { return it }
        val cr = ctx.contentResolver
        cr.query(
            CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.ACCOUNT_NAME}=? AND ${CalendarContract.Calendars.ACCOUNT_TYPE}=?",
            arrayOf(ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL), null
        )?.use { if (it.moveToFirst()) return it.getLong(0) }
        val v = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, "osmcoach")
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "OSM Coach avisos")
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF1F6BFF.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        return cr.insert(uri, v)?.lastPathSegment?.toLongOrNull()
    }

    private fun insertEvent(ctx: Context, calId: Long, title: String, text: String, start: Long): Long? {
        val cr = ctx.contentResolver
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, text)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, start + 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.HAS_ALARM, 1)
        }
        val id = cr.insert(CalendarContract.Events.CONTENT_URI, v)?.lastPathSegment?.toLongOrNull() ?: return null
        val r = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, id)
            put(CalendarContract.Reminders.MINUTES, 0)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        cr.insert(CalendarContract.Reminders.CONTENT_URI, r)
        return id
    }

    private fun eventText(ctx: Context, id: Long): Pair<String, String>? = runCatching {
        ctx.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
            arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DESCRIPTION), null, null, null
        )?.use { c -> if (c.moveToFirst()) Pair(c.getString(0).orEmpty(), c.getString(1).orEmpty()) else null }
    }.getOrNull()

    private fun deleteEvent(ctx: Context, id: Long): Boolean =
        runCatching { ctx.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) >= 0 }.getOrDefault(false)

    /** Deixa os eventos do calendário iguais ao que vale agora (mesmo plano do Push, com início um pouco depois). */
    suspend fun sync(ctx: Context, want: List<Push.Item>) {
        if (!enabled(ctx) || !hasPermission(ctx)) return
        withContext(Dispatchers.IO) { lock.withLock {
            val now = System.currentTimeMillis()
            val have = read(ctx)
            val p = Push.plan(want.map { it.copy(at = it.at + EXTRA_MS) }, have.mapValues { it.value.first }, now)
            val out = LinkedHashMap<String, Pair<Long, Long>>()
            for ((k, v) in have) if (v.first > now && k !in p.delete) out[k] = v
            for (k in p.delete) have[k]?.let { if (!deleteEvent(ctx, it.second)) out[k] = it }
            val cal = if (p.publish.isNotEmpty()) calendarId(ctx) else null
            val tx = readTx(ctx)
            if (cal != null) for (it in p.publish) {
                have[it.seq]?.let { old -> deleteEvent(ctx, old.second) }
                val start = it.at + Push.LATE_MS
                insertEvent(ctx, cal, it.title, it.text, start)?.let { id -> out[it.seq] = Pair(start, id); tx.put(it.seq, org.json.JSONArray().put(it.title).put(it.text)) }
            }
            for (k in tx.keys().asSequence().toList()) if (k !in out) tx.remove(k)
            writeTx(ctx, tx)
            write(ctx, out)
        } }
    }

    /** O alarme local tocou: tira o lembrete do calendário do mesmo evento. */
    suspend fun cancel(ctx: Context, seq: String) {
        if (!enabled(ctx) || !hasPermission(ctx)) return
        withContext(Dispatchers.IO) { lock.withLock {
            val m = read(ctx)
            m[seq]?.let { if (deleteEvent(ctx, it.second)) { m.remove(seq); write(ctx, m) } }
        } }
    }

    /**
     * O sistema de agenda avisou (broadcast EVENT_REMINDER, que chega mesmo com este app fechado): notifica por conta
     * própria os lembretes nossos que já venceram, sem depender de o app Calendário mostrar a notificação.
     */
    suspend fun onReminder(ctx: Context) {
        if (!enabled(ctx) || !hasPermission(ctx)) return
        withContext(Dispatchers.IO) { lock.withLock {
            val now = System.currentTimeMillis()
            val m = read(ctx)
            val tx = readTx(ctx)
            var changed = false
            for ((seq, v) in m.entries.toList()) {
                if (v.first > now + 60_000L || v.first < now - 6 * 3600_000L) continue
                val t = tx.optJSONArray(seq)
                // título e texto vêm do próprio evento (vale também para eventos criados antes de guardarmos o texto)
                val ev = eventText(ctx, v.second)
                val title = ev?.first?.takeIf { it.isNotBlank() } ?: t?.optString(0).orEmpty().ifBlank { "Aviso do OSM Coach" }
                val body = ev?.second?.takeIf { it.isNotBlank() } ?: t?.optString(1).orEmpty()
                Notifier.postCalendarReminder(ctx, seq, title, body)
                deleteEvent(ctx, v.second)
                m.remove(seq); tx.remove(seq); changed = true
            }
            if (changed) { write(ctx, m); writeTx(ctx, tx) }
        } }
    }

    suspend fun disable(ctx: Context) {
        if (!hasPermission(ctx)) return
        withContext(Dispatchers.IO) { lock.withLock {
            val m = read(ctx)
            val left = LinkedHashMap<String, Pair<Long, Long>>()
            for ((k, v) in m) if (!deleteEvent(ctx, v.second)) left[k] = v
            write(ctx, left)
        } }
    }

    /** Teste: cria um lembrete para daqui a 2 minutos; feche o app e espere. */
    suspend fun test(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        if (!hasPermission(ctx)) return@withContext false
        lock.withLock {
            runCatching {
                val cal = calendarId(ctx) ?: return@runCatching false
                Notifier.logFire(ctx, if (cal == googleCalendarId(ctx)) "calendário: usando a agenda do Google" else "calendário: usando agenda local (sem conta Google)")
                val m = read(ctx)
                m["test"]?.let { deleteEvent(ctx, it.second) }
                val start = System.currentTimeMillis() + 2 * 60_000L
                val id = insertEvent(ctx, cal, "✅ Teste do OSM Coach", "O lembrete pelo calendário do celular está funcionando.", start) ?: return@runCatching false
                m["test"] = Pair(start, id)
                write(ctx, m)
                writeTx(ctx, readTx(ctx).put("test", org.json.JSONArray().put("✅ Teste do OSM Coach").put("O lembrete pelo calendário do celular está funcionando.")))
                true
            }.onFailure { Notifier.logFire(ctx, "calendário: teste falhou (${it.javaClass.simpleName})") }
                .getOrDefault(false).also { ok -> if (ok) Notifier.logFire(ctx, "calendário: teste criado para daqui a 2 min") else Notifier.logFire(ctx, "calendário: teste não criado") }
        }
    }
}
