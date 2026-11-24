package com.osmaicoach.collector

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Widget da tela inicial: uma linha por slot (clube, próximo adversário/horário confirmado, status mais útil, placar agregado da batalha). Só lê o que o app JÁ
 * gravou: não abre o OSM, não roda OCR, não envia aviso. Atualiza quando a captura salva dados novos (`UiBus.bump` → `requestRefresh`) e a cada 30 min (relógio do Android).
 */
object SlotWidget {
    const val STALE_MS = 12L * 3600_000L
    const val TACTIC_WINDOW = Director.TACTIC_WINDOW_MS

    /** Dados puros de uma linha (testável sem Android). */
    class Row(
        val slot: Int, val title: String, val line: String, val status: String, val stale: Boolean, val tab: Int, val readAt: Long?,
        val club: String = "", val rival: String? = null, val `when`: String = "", val state: Int = STATE_NONE,
        val at: Long? = null, val short: String = "", val icon: String = ""
    )
    const val STATE_OK = 0; const val STATE_WAIT = 1; const val STATE_OLD = 2; const val STATE_NONE = 3

    /** "em 35 min", "em 3 h", "em 2 d" (sem hora de jogo confirmada = vazio). */
    fun until(at: Long?, now: Long): String {
        if (at == null || at <= now) return ""
        val m = (at - now) / 60_000L
        return if (m < 60) "em ${maxOf(1, m)} min" else if (m < 48 * 60) "em ${m / 60} h" else "em ${m / (24 * 60)} d"
    }

    private val HOUR = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR"))
    private fun fmt(t: Long) = HOUR.format(Date(t))

    fun buildRow(
        slot: Int, f: Map<String, StoredField>, tacticReady: Boolean, lastResult: String?, battleAgg: String?, now: Long
    ): Row {
        val team = f[K.TEAM]?.value?.takeIf { FieldMerge.known(it) } ?: f[K.HUB_TITLE]?.value?.takeIf { FieldMerge.known(it) }
        val state = f[K.SLOT_STATE]?.value
        val readAt = f.values.maxOfOrNull { it.updatedAt }?.takeIf { it > 0L }
        if (team == null || state == "vazio" || state == "procurando") return Row(slot, "S$slot · sem clube", "", "", false, 0, readAt, club = "sem clube", state = STATE_NONE)
        val stale = readAt == null || now - readAt > STALE_MS
        val rival = f[K.RIVAL_TEAM]?.value?.takeIf { FieldMerge.known(it) }
        val at = f[K.MATCH_AT]?.value?.toLongOrNull()?.takeIf { it > now }
        val battle = f[K.COMP_TYPE]?.value == "Batalha"
        val line = if (rival != null && at != null) "× $rival • ${fmt(at)}" else "sem próximo jogo confirmado"
        val readTxt = readAt?.let { "lido ${fmt(it)}" } ?: "sem leitura"
        var status = when {
            at != null && tacticReady -> "✅ tática pronta"
            at != null && at - now <= TACTIC_WINDOW -> "⏳ aguardando leitura do rival"
            at != null -> "⏳ tática só nas 8 h antes do jogo"
            lastResult != null -> "último: $lastResult"
            else -> "aguardando leitura"
        }
        if (battleAgg != null) status += " • ⚔ $battleAgg"
        status += " • " + (if (stale) "⚠ antigo, $readTxt" else readTxt)
        val tab = when { battle && at == null -> 0; at != null && tacticReady -> 4; at != null -> 1; else -> 0 }
        val st = when { stale -> STATE_OLD; at != null && tacticReady -> STATE_OK; else -> STATE_WAIT }
        val statusShort = when {
            at != null && tacticReady -> "tática pronta"
            at != null && at - now <= TACTIC_WINDOW -> "aguardando leitura do rival"
            at != null -> "tática só nas 8 h antes"
            lastResult != null -> "último: $lastResult"
            else -> "aguardando leitura"
        } + (if (battleAgg != null) " • ⚔ $battleAgg" else "") + (if (stale) " • ⚠ antigo" else "")
        val line2 = (if (rival != null && at != null) "× $rival • ${fmt(at).substringBefore(' ')}  ${fmt(at).substringAfter(' ')} • " else "") + statusShort
        val icon = when (st) { STATE_OK -> "✅"; STATE_OLD -> "⚠"; else -> "⏳" }
        return Row(slot, "S$slot · $team", line2, status, stale, tab, readAt, club = team, rival = rival, `when` = until(at, now), state = st, at = at, short = statusShort, icon = icon)
    }

    suspend fun loadRows(ctx: Context, now: Long = System.currentTimeMillis()): List<Row> {
        val repo = Repo(ctx)
        return (1..4).map { s ->
            runCatching {
                val f = repo.fieldMap(s)
                val tj = repo.dao.plan(s, "tactic")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
                val round = f[K.ROUND]?.value?.filter { it.isDigit() }?.toIntOrNull()
                val ready = tj != null && (round == null || tj.optInt("round", -1) == round) && tj.optString("formation").isNotBlank()
                val last = repo.dao.matchesOf(s).filter { it.scoreMine != null && it.scoreOpp != null }.maxByOrNull { it.round ?: 0 }
                    ?.let { "${it.opponent ?: "?"} ${it.scoreMine}×${it.scoreOpp}" }
                val agg = if (f[K.COMP_TYPE]?.value == "Batalha") repo.dao.plan(s, "battle")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?.let { Battle.state(it, null, repo.setting(Battle.SETTING, Battle.DEFAULT_TEAM)) }
                    ?.takeIf { it.aggA != null }?.let { st -> if (st.mine == "B") "${st.aggB}×${st.aggA}" else "${st.aggA}×${st.aggB}" } else null
                buildRow(s, f, ready, last, agg, now)
            }.getOrElse { Row(s, "S$s · sem dados", "", "", false, 0, null) }
        }
    }

    private fun badgeFor(state: Int) = when (state) { STATE_OK -> R.drawable.w_badge_ok; STATE_WAIT -> R.drawable.w_badge_wait; STATE_OLD -> R.drawable.w_badge_old; else -> R.drawable.w_badge_none }

    /** Escudo pequeno já salvo pelo app (nunca baixa nada aqui); reduzido para caber no limite de memória do widget. */
    private fun crestOf(ctx: Context, team: String?, px: Int = 64): android.graphics.Bitmap? = runCatching {
        if (team.isNullOrBlank()) return null
        val f = CrestWeb.find(ctx.filesDir, team) ?: return null
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
        val ss = maxOf(1, maxOf(o.outWidth, o.outHeight) / px)
        android.graphics.BitmapFactory.decodeFile(f.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = ss })
    }.getOrNull()

    private fun setCrest(ctx: Context, v: RemoteViews, id: Int, team: String?, px: Int = 64) {
        val b = crestOf(ctx, team, px)
        if (b != null) { v.setImageViewBitmap(id, b); v.setViewVisibility(id, android.view.View.VISIBLE) } else v.setViewVisibility(id, android.view.View.INVISIBLE)
    }

    private fun click(ctx: Context, v: RemoteViews, id: Int, r: Row, code: Int) =
        v.setOnClickPendingIntent(id, PendingIntent.getActivity(ctx, code + r.slot, intentFor(ctx, r), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

    /** Jogo em destaque: o mais próximo com horário confirmado; sem nenhum, o primeiro slot com clube. */
    fun hero(rows: List<Row>): Row? = rows.filter { it.at != null }.minByOrNull { it.at!! } ?: rows.firstOrNull { it.state != STATE_NONE }

    private fun clock(at: Long?): String = at?.let { SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(it)) } ?: "--:--"
    private fun day(at: Long?): String = at?.let { SimpleDateFormat("EEE dd/MM", Locale("pt", "BR")).format(Date(it)) } ?: ""

    private val WIDE = listOf(
        intArrayOf(R.id.wc1, R.id.wc1_badge, R.id.wc1_crest, R.id.wc1_name, R.id.wc1_when), intArrayOf(R.id.wc2, R.id.wc2_badge, R.id.wc2_crest, R.id.wc2_name, R.id.wc2_when),
        intArrayOf(R.id.wc3, R.id.wc3_badge, R.id.wc3_crest, R.id.wc3_name, R.id.wc3_when), intArrayOf(R.id.wc4, R.id.wc4_badge, R.id.wc4_crest, R.id.wc4_name, R.id.wc4_when)
    )
    private val LIST = listOf(
        intArrayOf(R.id.lr1, R.id.lr1_badge, R.id.lr1_crest, R.id.lr1_title, R.id.lr1_line, R.id.lr1_when), intArrayOf(R.id.lr2, R.id.lr2_badge, R.id.lr2_crest, R.id.lr2_title, R.id.lr2_line, R.id.lr2_when),
        intArrayOf(R.id.lr3, R.id.lr3_badge, R.id.lr3_crest, R.id.lr3_title, R.id.lr3_line, R.id.lr3_when), intArrayOf(R.id.lr4, R.id.lr4_badge, R.id.lr4_crest, R.id.lr4_title, R.id.lr4_line, R.id.lr4_when)
    )

    private fun upd(now: Long) = "atualizado " + SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(now))

    /** 2×2: o próximo jogo em destaque (escudos, contagem, hora). */
    fun small(ctx: Context, rows: List<Row>, now: Long): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_small)
        val h = hero(rows)
        if (h == null) { v.setTextViewText(R.id.s_head, "OSM AI Coach"); v.setTextViewText(R.id.s_rival, "sem clube nos slots"); return v }
        v.setTextViewText(R.id.s_head, "S${h.slot}" + (if (h.`when`.isNotEmpty()) " • ${h.`when`}" else ""))
        v.setInt(R.id.s_badge, "setBackgroundResource", badgeFor(h.state)); v.setTextViewText(R.id.s_badge, h.slot.toString())
        setCrest(ctx, v, R.id.s_crest_me, h.club, 96); setCrest(ctx, v, R.id.s_crest_opp, h.rival, 96)
        v.setTextViewText(R.id.s_rival, if (h.rival != null && h.at != null) "× ${h.rival}" else h.short.ifBlank { h.club })
        v.setTextViewText(R.id.s_time, if (h.at != null) "${clock(h.at)} • ${day(h.at)}" else h.icon + " " + h.short)
        click(ctx, v, R.id.s_root, h, 800)
        return v
    }

    /** 4×2: quatro cartões lado a lado (estilo previsão do tempo): escudo, clube, contagem e cor de estado. */
    fun wide(ctx: Context, rows: List<Row>, now: Long): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_wide)
        for ((i, r) in rows.withIndex()) {
            val id = WIDE[i]
            v.setTextViewText(id[1], r.slot.toString()); v.setInt(id[1], "setBackgroundResource", badgeFor(r.state))
            v.setTextViewText(id[3], r.club.ifBlank { "sem clube" })
            v.setTextViewText(id[4], r.`when`.ifEmpty { if (r.state == STATE_NONE) "—" else r.icon })
            setCrest(ctx, v, id[2], if (r.state == STATE_NONE) null else r.club)
            click(ctx, v, id[0], r, 700)
        }
        v.setTextViewText(R.id.w_footer, upd(now))
        return v
    }

    /** 4×4: jogo em destaque no topo (dois escudos, contagem, hora) e a lista dos 4 slots com status. */
    fun large(ctx: Context, rows: List<Row>, now: Long): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_large)
        val h = hero(rows)
        if (h != null) {
            v.setTextViewText(R.id.h_slot, "S${h.slot}"); v.setInt(R.id.h_slot, "setBackgroundResource", badgeFor(h.state))
            setCrest(ctx, v, R.id.h_crest_me, h.club, 96); setCrest(ctx, v, R.id.h_crest_opp, h.rival, 96)
            v.setTextViewText(R.id.h_me, h.club); v.setTextViewText(R.id.h_opp, h.rival ?: "—")
            v.setTextViewText(R.id.h_when, h.`when`.ifEmpty { "sem jogo" })
            v.setTextViewText(R.id.h_time, if (h.at != null) "${clock(h.at)} • ${day(h.at)}" else h.short)
            v.setTextViewText(R.id.h_status, h.icon + " " + h.short)
            click(ctx, v, R.id.h_card, h, 900)
        }
        for ((i, r) in rows.withIndex()) {
            val id = LIST[i]
            v.setTextViewText(id[1], r.slot.toString()); v.setInt(id[1], "setBackgroundResource", badgeFor(r.state))
            v.setTextViewText(id[3], r.club.ifBlank { "sem clube" }); v.setTextViewText(id[4], r.line.ifBlank { "abra o OSM com a captura ligada" }); v.setTextViewText(id[5], r.`when`)
            setCrest(ctx, v, id[2], if (r.state == STATE_NONE) null else r.club, 48)
            click(ctx, v, id[0], r, 700)
        }
        v.setTextViewText(R.id.l_footer, upd(now))
        return v
    }

    /** Responsivo (Android 12+): o launcher escolhe 2×2, 4×2 ou 4×4 conforme o tamanho que você der ao widget. */
    fun views(ctx: Context, rows: List<Row>, now: Long): RemoteViews =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            RemoteViews(mapOf(
                android.util.SizeF(110f, 110f) to small(ctx, rows, now),
                android.util.SizeF(250f, 110f) to wide(ctx, rows, now),
                android.util.SizeF(250f, 230f) to large(ctx, rows, now)
            ))
        else wide(ctx, rows, now)

    fun intentFor(ctx: Context, r: Row): Intent = Intent(ctx, MainActivity::class.java)
        .setAction("com.osmaicoach.collector.WIDGET_OPEN").setData(Uri.parse("osmcoach://slot/${r.slot}/${r.tab}"))
        .putExtra("slot", r.slot).putExtra("tab", r.tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    suspend fun update(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, SlotWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        val v = views(ctx, loadRows(ctx, now), now)
        for (id in ids) mgr.updateAppWidget(id, v)
    }

    @Volatile private var appCtx: Context? = null
    @Volatile private var lastRefresh = 0L
    fun attach(ctx: Context) { appCtx = ctx.applicationContext }

    /** Chamado quando a captura grava algo (via `UiBus.bump`): no máx. 1× a cada 5 s e só se houver widget na tela inicial. */
    fun requestRefresh() {
        val c = appCtx ?: return
        val now = System.currentTimeMillis()
        if (now - lastRefresh < 5000L) return
        lastRefresh = now
        AppScope.scope.launch(Dispatchers.IO) { runCatching { update(c) } }
    }
}

class SlotWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        SlotWidget.attach(context)
        val pending = goAsync()
        AppScope.scope.launch(Dispatchers.IO) {
            try { SlotWidget.update(context) } finally { pending.finish() }
        }
    }
}
