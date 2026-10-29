package com.osmaicoach.collector

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import java.util.Calendar
import org.json.JSONObject

/**
 * Alarme no app Relógio do celular: o Relógio é do sistema e toca mesmo quando o fabricante fecha este app e apaga os
 * alarmes dele. Só cria o que cai nas próximas 23 h (o Relógio não aceita data) e uns 60 s depois do alarme local,
 * que quando toca tenta apagar este (o Relógio nem sempre atende o pedido). Desligado por padrão.
 */
object ClockSync {
    private const val LATE_MS = 60_000L
    private const val AHEAD_MS = 23L * 3600_000L

    fun enabled(ctx: Context): Boolean = Settings.get(ctx, "clock_on", "0") == "1"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("clocksync", Context.MODE_PRIVATE)

    private fun read(ctx: Context): MutableMap<String, Pair<Long, String>> {
        val j = runCatching { JSONObject(prefs(ctx).getString("al", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val m = LinkedHashMap<String, Pair<Long, String>>()
        for (k in j.keys()) j.optJSONObject(k)?.let { m[k] = Pair(it.optLong("at"), it.optString("label")) }
        return m
    }

    private fun write(ctx: Context, m: Map<String, Pair<Long, String>>) {
        val j = JSONObject()
        for ((k, v) in m) j.put(k, JSONObject().put("at", v.first).put("label", v.second))
        prefs(ctx).edit().putString("al", j.toString()).apply()
    }

    private fun label(title: String) = title.take(60)

    /** Pede ao Relógio um alarme (sem abrir tela) para [at]; false se o sistema não deixou. */
    private fun create(ctx: Context, at: Long, title: String): Boolean {
        val c = Calendar.getInstance().apply { timeInMillis = at + 30_000L }
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, c.get(Calendar.HOUR_OF_DAY))
            .putExtra(AlarmClock.EXTRA_MINUTES, c.get(Calendar.MINUTE))
            .putExtra(AlarmClock.EXTRA_MESSAGE, label(title))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); true } catch (e: Exception) {
            Notifier.logFire(ctx, "relógio: não criou alarme (${e.javaClass.simpleName})")
            false
        }
    }

    /** [want] = avisos do app (mesmos da nuvem/calendário). Cria os que faltam e esquece os que saíram. */
    fun sync(ctx: Context, want: List<Push.Item>) {
        if (!enabled(ctx)) return
        val now = System.currentTimeMillis()
        val have = read(ctx)
        val keep = LinkedHashMap<String, Pair<Long, String>>()
        for ((k, v) in have) if (v.first > now - 3600_000L) keep[k] = v
        var changed = keep.size != have.size
        for (it in want) {
            val at = it.at + LATE_MS
            if (at <= now + 90_000L || at > now + AHEAD_MS) continue
            val old = keep[it.seq]
            if (old != null && kotlin.math.abs(old.first - at) <= 3 * 60_000L) continue
            if (create(ctx, at, it.title)) {
                keep[it.seq] = Pair(at, label(it.title))
                changed = true
                Notifier.logFire(ctx, "relógio: alarme criado para ${java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))}")
            }
        }
        if (changed) write(ctx, keep)
    }

    /** Pedido de apagar o alarme do Relógio (o app do Relógio decide se atende). */
    fun cancel(ctx: Context, seq: String) {
        if (!enabled(ctx)) return
        val m = read(ctx)
        val e = m.remove(seq) ?: return
        write(ctx, m)
        runCatching {
            ctx.startActivity(
                Intent(AlarmClock.ACTION_DISMISS_ALARM)
                    .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, e.second)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun disable(ctx: Context) { prefs(ctx).edit().clear().apply() }

    /** Alarme de teste daqui a ~2 min. */
    fun test(ctx: Context): Boolean {
        val at = System.currentTimeMillis() + 2 * 60_000L
        val ok = create(ctx, at, "✅ Teste do OSM Coach")
        Notifier.logFire(ctx, if (ok) "relógio: teste criado para daqui a 2 min" else "relógio: teste não criado")
        return ok
    }
}
