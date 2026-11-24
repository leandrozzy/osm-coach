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
    class Row(val slot: Int, val title: String, val line: String, val status: String, val stale: Boolean, val tab: Int, val readAt: Long?)

    private val HOUR = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR"))
    private fun fmt(t: Long) = HOUR.format(Date(t))

    fun buildRow(
        slot: Int, f: Map<String, StoredField>, tacticReady: Boolean, lastResult: String?, battleAgg: String?, now: Long
    ): Row {
        val team = f[K.TEAM]?.value?.takeIf { FieldMerge.known(it) } ?: f[K.HUB_TITLE]?.value?.takeIf { FieldMerge.known(it) }
        val state = f[K.SLOT_STATE]?.value
        val readAt = f.values.maxOfOrNull { it.updatedAt }?.takeIf { it > 0L }
        if (team == null || state == "vazio" || state == "procurando") return Row(slot, "S$slot · sem clube", "", "", false, 0, readAt)
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
        return Row(slot, "S$slot · $team", line, status, stale, tab, readAt)
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

    private val IDS = listOf(
        Triple(R.id.row1, R.id.title1, Pair(R.id.line1, R.id.status1)), Triple(R.id.row2, R.id.title2, Pair(R.id.line2, R.id.status2)),
        Triple(R.id.row3, R.id.title3, Pair(R.id.line3, R.id.status3)), Triple(R.id.row4, R.id.title4, Pair(R.id.line4, R.id.status4))
    )

    fun intentFor(ctx: Context, r: Row): Intent = Intent(ctx, MainActivity::class.java)
        .setAction("com.osmaicoach.collector.WIDGET_OPEN").setData(Uri.parse("osmcoach://slot/${r.slot}/${r.tab}"))
        .putExtra("slot", r.slot).putExtra("tab", r.tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun views(ctx: Context, rows: List<Row>, now: Long): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_slots)
        for ((i, r) in rows.withIndex()) {
            val (row, title, ls) = IDS[i]
            v.setTextViewText(title, r.title); v.setTextViewText(ls.first, r.line); v.setTextViewText(ls.second, r.status)
            v.setTextColor(ls.second, if (r.stale) 0xFFFFB454.toInt() else 0xFFFFD54A.toInt())
            v.setOnClickPendingIntent(row, PendingIntent.getActivity(ctx, 700 + r.slot, intentFor(ctx, r), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        v.setTextViewText(R.id.footer, "atualizado ${HOUR.format(Date(now))}")
        return v
    }

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
