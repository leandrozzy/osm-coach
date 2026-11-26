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
        val at: Long? = null, val short: String = "", val icon: String = "",
        val strMe: Int? = null, val strOpp: Int? = null, val win: Int? = null, val draw: Int? = null, val loss: Int? = null,
        val formMe: String? = null, val formOpp: String? = null, val round: String? = null, val home: Boolean? = null, val pos: String? = null
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
        slot: Int, f: Map<String, StoredField>, tacticReady: Boolean, lastResult: String?, battleAgg: String?, now: Long, sum: SlotSummary? = null
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
        val tab = when { battle && at == null -> 0; at != null && (sum?.let { it.tacticReady && !it.tacticStale } ?: tacticReady) -> 4; at != null -> 1; else -> 0 }
        val ready = if (sum != null) sum.tacticReady && !sum.tacticStale else tacticReady
        val st = when { stale -> STATE_OLD; at != null && ready -> STATE_OK; else -> STATE_WAIT }
        val inWindow = at != null && at - now <= TACTIC_WINDOW
        val statusShort = if (sum != null) when {
            sum.needsResult -> "registrar o resultado do jogo"
            sum.rivalChanged.isNotEmpty() -> "rival mudou: revisar a tática"
            at != null && sum.tacticReady && !sum.tacticStale && sum.mounted -> "tática pronta e montada"
            at != null && sum.tacticReady && !sum.tacticStale && sum.mountDiff.isNotEmpty() -> "tática pronta • ajustar no OSM"
            at != null && sum.tacticReady && !sum.tacticStale -> "tática pronta • falta montar no OSM"
            inWindow && (sum.tacticStale || sum.rivalGaps) -> "reler Pré-jogo e Análise do rival"
            inWindow -> "rival lido • gerando a tática"
            at != null -> "tática só nas 8 h antes"
            lastResult != null -> "último: $lastResult"
            else -> "aguardando leitura"
        } else when {
            at != null && tacticReady -> "tática pronta"
            at != null && at - now <= TACTIC_WINDOW -> "aguardando leitura do rival"
            at != null -> "tática só nas 8 h antes"
            lastResult != null -> "último: $lastResult"
            else -> "aguardando leitura"
        } + (if (battleAgg != null) " • ⚔ $battleAgg" else "") + (if (stale) " • ⚠ antigo" else "")
        val line2 = (if (rival != null && at != null) "× $rival • ${fmt(at).substringBefore(' ')}  ${fmt(at).substringAfter(' ')} • " else "") + statusShort
        val icon = when (st) { STATE_OK -> "✅"; STATE_OLD -> "⚠"; else -> "⏳" }
        return Row(slot, "S$slot · $team", line2, status, stale, tab, readAt, club = team, rival = rival, `when` = until(at, now), state = st, at = at, short = statusShort, icon = icon,
            strMe = sum?.myStr, strOpp = sum?.rivalStr, win = sum?.win?.win, draw = sum?.win?.draw, loss = sum?.win?.loss,
            formMe = sum?.myForm?.takeIf { it.isNotBlank() }, formOpp = sum?.rivalForm?.takeIf { it.isNotBlank() },
            round = if (sum?.roundDone != null && sum.roundTotal != null) "J${(sum.roundDone.toIntOrNull() ?: 0) + 1}/${sum.roundTotal}" else f[K.ROUND]?.value?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }?.let { "J$it" },
            home = sum?.home, pos = f[K.LEAGUE_POS]?.value?.takeIf { FieldMerge.known(it) })
    }

    suspend fun loadRows(ctx: Context, now: Long = System.currentTimeMillis()): List<Row> {
        val repo = Repo(ctx)
        val today = runCatching { loadToday(ctx) }.getOrNull()
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
                buildRow(s, f, ready, last, agg, now, today?.slots?.firstOrNull { it.slot == s })
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

    /** Barra de chances desenhada em bitmap (vitória verde, empate âmbar, derrota vermelha): funciona em qualquer Android, sem depender de métodos remotos. */
    fun probBar(win: Int, draw: Int, loss: Int, w: Int = 300, h: Int = 14): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val tot = maxOf(1, win + draw + loss).toFloat()
        val clip = android.graphics.Path().apply { addRoundRect(android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, android.graphics.Path.Direction.CW) }
        c.clipPath(clip)
        var x = 0f
        for ((v, col) in listOf(win to 0xFF4CCB7A.toInt(), draw to 0xFFF5B942.toInt(), loss to 0xFFEF5350.toInt())) {
            val ww = w * v / tot; p.color = col; c.drawRect(x, 0f, x + ww, h.toFloat(), p); x += ww
        }
        return bmp
    }

    /** A barra é desenhada JÁ no tamanho real (px) e mostrada sem esticar: largura = parte da largura do widget (máx. 260 dp), altura 8 dp. */
    private fun setProb(ctx: Context, v: RemoteViews, id: Int, r: Row, widthDp: Float) {
        if (r.win != null && r.draw != null && r.loss != null) {
            val d = ctx.resources.displayMetrics.density
            v.setImageViewBitmap(id, probBar(r.win, r.draw, r.loss, (minOf(widthDp, 260f) * d).toInt().coerceAtLeast(60), (8 * d).toInt().coerceAtLeast(6)))
            v.setViewVisibility(id, android.view.View.VISIBLE)
        } else v.setViewVisibility(id, android.view.View.GONE)
    }

    private fun probText(r: Row) = if (r.win != null) "V ${r.win}%  E ${r.draw}%  D ${r.loss}%" else ""
    private fun formEmoji(f: String?) = f?.map { when (it) { 'V' -> "🟢"; 'E' -> "🟡"; 'D' -> "🔴"; else -> "" } }?.joinToString("").orEmpty()
    private fun strengths(r: Row) = if (r.strMe != null && r.strOpp != null) "${r.strMe} × ${r.strOpp}" else "×"
    private fun where(r: Row) = when (r.home) { true -> "🏠 casa"; false -> "✈ fora"; null -> "" }

    private val WIDE = listOf(
        intArrayOf(R.id.wc1, R.id.wc1_badge, R.id.wc1_crest, R.id.wc1_name, R.id.wc1_when, R.id.wc1_info), intArrayOf(R.id.wc2, R.id.wc2_badge, R.id.wc2_crest, R.id.wc2_name, R.id.wc2_when, R.id.wc2_info),
        intArrayOf(R.id.wc3, R.id.wc3_badge, R.id.wc3_crest, R.id.wc3_name, R.id.wc3_when, R.id.wc3_info), intArrayOf(R.id.wc4, R.id.wc4_badge, R.id.wc4_crest, R.id.wc4_name, R.id.wc4_when, R.id.wc4_info)
    )
    private val LIST = listOf(
        intArrayOf(R.id.lr1, R.id.lr1_badge, R.id.lr1_crest, R.id.lr1_title, R.id.lr1_line, R.id.lr1_when, R.id.lr1_sub), intArrayOf(R.id.lr2, R.id.lr2_badge, R.id.lr2_crest, R.id.lr2_title, R.id.lr2_line, R.id.lr2_when, R.id.lr2_sub),
        intArrayOf(R.id.lr3, R.id.lr3_badge, R.id.lr3_crest, R.id.lr3_title, R.id.lr3_line, R.id.lr3_when, R.id.lr3_sub), intArrayOf(R.id.lr4, R.id.lr4_badge, R.id.lr4_crest, R.id.lr4_title, R.id.lr4_line, R.id.lr4_when, R.id.lr4_sub)
    )

    private fun upd(now: Long) = "atualizado " + SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(now))

    // ------------------------------------------------------------------ visual do redesign (cores do app; tudo por RemoteViews, sem blur/sombra)
    private val C_LIME = 0xFFB9F363.toInt(); private const val C_AMBER = 0xFFF5B942.toInt(); private const val C_RED = 0xFFEF5350.toInt()
    private val C_GREEN = 0xFF4CCB7A.toInt(); private const val C_TEXT = 0xFFF4F7FB.toInt(); private const val C_MUTED = 0xFFA8B7C8.toInt()

    /** Cor da contagem (mesma regra do app): ≤ 1 h vermelho, dentro das 8 h da tática âmbar, depois verde; sem hora, cinza. */
    fun urgencyColor(at: Long?, now: Long): Int {
        if (at == null || at <= now) return C_MUTED
        val m = (at - now) / 60_000L
        return if (m <= 60) C_RED else if (m <= TACTIC_WINDOW / 60_000L) C_AMBER else C_GREEN
    }
    private fun stateColor(state: Int) = when (state) { STATE_OK -> C_GREEN; STATE_WAIT -> C_AMBER; STATE_OLD -> C_RED; else -> C_MUTED }
    private fun short(r: Row) = r.`when`.removePrefix("em ").ifEmpty { "—" }
    /** "⚠ lido há 14 h" para dado antigo (> 12 h); vazio se o dado é recente. */
    fun staleText(r: Row, now: Long): String {
        if (!r.stale) return ""
        val h = r.readAt?.let { (now - it) / 3_600_000L } ?: return "⚠ sem leitura"
        return if (h >= 48) "⚠ lido há ${h / 24} d" else "⚠ lido há $h h"
    }

    /** Forma recente como quadradinhos V/E/D coloridos (bitmap, para funcionar em qualquer Android). */
    fun formBitmap(form: String?, ctx: Context, alignEnd: Boolean): android.graphics.Bitmap? {
        val f = form?.filter { it == 'V' || it == 'E' || it == 'D' }?.takeLast(5)?.takeIf { it.isNotEmpty() } ?: return null
        val d = ctx.resources.displayMetrics.density
        val s = (13 * d).toInt(); val gap = (3 * d).toInt()
        val w = f.length * s + (f.length - 1) * gap
        val bmp = android.graphics.Bitmap.createBitmap(w, s, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B111B.toInt(); textSize = s * 0.68f; typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = android.graphics.Paint.Align.CENTER }
        for ((i, ch) in f.withIndex()) {
            val x = i * (s + gap).toFloat()
            p.color = when (ch) { 'V' -> C_GREEN; 'E' -> C_AMBER; else -> C_RED }
            c.drawRoundRect(android.graphics.RectF(x, 0f, x + s, s.toFloat()), s * 0.3f, s * 0.3f, p)
            c.drawText(ch.toString(), x + s / 2f, s * 0.74f, tp)
        }
        return bmp
    }
    private fun setForm(ctx: Context, v: RemoteViews, id: Int, form: String?, alignEnd: Boolean) {
        val b = formBitmap(form, ctx, alignEnd)
        if (b != null) { v.setImageViewBitmap(id, b); v.setViewVisibility(id, android.view.View.VISIBLE) } else v.setViewVisibility(id, android.view.View.INVISIBLE)
    }

    /** 2×2: o próximo jogo (escudos, forças, chances, hora). */
    fun small(ctx: Context, rows: List<Row>, now: Long, wDp: Int = 110): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_small)
        val h = hero(rows)
        if (h == null) { v.setTextViewText(R.id.s_head, "OSM AI Coach"); v.setTextColor(R.id.s_head, C_TEXT); v.setTextViewText(R.id.s_probtxt, "sem clube nos slots"); return v }
        val old = h.stale
        v.setTextViewText(R.id.s_head, h.`when`.ifEmpty { "sem jogo" }); v.setTextColor(R.id.s_head, if (old) C_MUTED else urgencyColor(h.at, now))
        v.setInt(R.id.s_badge, "setBackgroundResource", badgeFor(h.state)); v.setTextViewText(R.id.s_badge, h.slot.toString())
        v.setTextColor(R.id.s_badge, if (h.state == STATE_OLD) 0xFFFFFFFF.toInt() else 0xFF0B111B.toInt())
        setCrest(ctx, v, R.id.s_crest_me, h.club, 96); setCrest(ctx, v, R.id.s_crest_opp, h.rival, 96)
        v.setTextViewText(R.id.s_vs, if (h.strMe != null && h.strOpp != null) strengths(h) else h.rival?.let { "× $it" } ?: h.short)
        setProb(ctx, v, R.id.s_prob, h, wDp - 28f)
        val st = staleText(h, now)
        v.setTextViewText(R.id.s_probtxt, if (st.isNotEmpty()) st else probText(h))
        v.setTextColor(R.id.s_probtxt, if (st.isNotEmpty()) C_RED else C_MUTED)
        v.setTextViewText(R.id.s_time, if (h.at != null) "${clock(h.at)} • ${day(h.at)} ${where(h)}".trim() else h.icon + " " + h.short)
        click(ctx, v, R.id.s_root, h, 800)
        return v
    }

    /** 4×2: quatro cartões lado a lado: escudo, clube, contagem (pílula colorida pela urgência) e chances/rodada; o jogo mais próximo ganha contorno lima. */
    fun wide(ctx: Context, rows: List<Row>, now: Long): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_wide)
        val hr = hero(rows)
        for ((i, r) in rows.withIndex()) {
            val id = WIDE[i]
            v.setTextViewText(id[1], r.slot.toString()); v.setInt(id[1], "setBackgroundResource", badgeFor(r.state))
            v.setTextColor(id[1], if (r.state == STATE_OLD) 0xFFFFFFFF.toInt() else 0xFF0B111B.toInt())
            v.setInt(id[0], "setBackgroundResource", if (r === hr && r.at != null) R.drawable.w_tile_hero else R.drawable.w_tile)
            v.setTextViewText(id[3], r.club.ifBlank { "sem clube" })
            v.setTextViewText(id[4], if (r.state == STATE_NONE) "livre" else short(r))
            v.setTextColor(id[4], if (r.state == STATE_NONE || r.stale) C_MUTED else urgencyColor(r.at, now))
            v.setTextViewText(id[5], if (r.stale) staleText(r, now) else if (r.win != null && r.at != null) "V${r.win} E${r.draw} D${r.loss}" else r.round ?: "")
            v.setTextColor(id[5], if (r.stale) C_RED else C_MUTED)
            setCrest(ctx, v, id[2], if (r.state == STATE_NONE) null else r.club)
            click(ctx, v, id[0], r, 700)
        }
        v.setTextViewText(R.id.w_footer, upd(now))
        return v
    }

    /** 4×4: jogo em destaque (escudos, contagem grande, chances, forma, estado) e a lista dos 4 slots. */
    /** Evento da Agenda (mesma regra da aba Agenda): jogo (kind 0), fim de treino (1) ou fim de obra do estádio (2). */
    class Ev(val at: Long, val kind: Int, val icon: String, val title: String, val sub: String, val slot: Int, val club: String? = null, val rival: String? = null)

    suspend fun loadEvents(ctx: Context): List<Ev> = runCatching {
        val data = loadToday(ctx)
        val skip = runCatching { AgendaSkip.load(ctx) }.getOrDefault(emptySet())
        val out = ArrayList<Ev>()
        for (s in data.slots) {
            val at = s.nextAt ?: continue
            if (!s.ended && s.state == null) out.add(Ev(at, 0, "⚽", "Jogo" + (s.rival?.let { " vs $it" } ?: ""), s.title, s.slot, s.title, s.rival))
        }
        for (w in data.work) {
            for (seat in w.seats) seat.endAt?.let { out.add(Ev(it, 1, "🏋", "Treino ${Timers.roleLabel(seat.role)}", seat.name ?: "treinador ocupado", w.slot, w.team)) }
            for (c in w.cards) c.endAt?.let { out.add(Ev(it, 2, "🏟", "Obra: ${c.label}", "nível ${c.level ?: "?"}", w.slot, w.team)) }
        }
        out.filter { e -> "${e.slot}|" + (when (e.kind) { 0 -> "Jogo" + (e.rival?.let { " vs $it" } ?: ""); 1 -> "Treino ${e.title.removePrefix("Treino ")} termina"; else -> "Obra do estádio termina: ${e.title.removePrefix("Obra: ")}" }) + "|${e.at}" !in skip }.sortedBy { it.at }
    }.getOrDefault(emptyList())

    private fun ago(ms: Long): String { val m = ms / 60_000L; return if (m < 60) "há ${maxOf(1, m)} min" else if (m < 48 * 60) "há ${m / 60} h" else "há ${m / (24 * 60)} d" }

    fun large(ctx: Context, rows: List<Row>, now: Long, wDp: Int = 250, evs: List<Ev> = emptyList()): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_large)
        // destaque = o evento MAIS PRÓXIMO da agenda (jogo, treino ou obra); sem nenhum futuro, o atrasado mais recente
        val upcoming = evs.filter { it.at >= now }
        val overdue = evs.filter { it.at < now }
        val heroEv = upcoming.minByOrNull { it.at } ?: overdue.maxByOrNull { it.at }
        val h = if (heroEv != null && heroEv.kind == 0) rows.firstOrNull { it.slot == heroEv.slot && it.at != null } ?: hero(rows) else if (heroEv != null) null else hero(rows)
        val next = (upcoming.sortedBy { it.at }.drop(if (heroEv != null && heroEv.at >= now) 1 else 0)).firstOrNull()
        v.setTextViewText(R.id.h_alt, next?.let { "depois: ${it.icon} ${it.title.take(18)} ${until(it.at, now)}" } ?: "")
        if (next == null) v.setViewVisibility(R.id.h_alt, android.view.View.GONE) else v.setViewVisibility(R.id.h_alt, android.view.View.VISIBLE)
        if (heroEv != null && heroEv.kind != 0) {
            // treino / obra: ícone grande + o que é + quanto falta (contagem ao vivo)
            val late = heroEv.at < now
            v.setTextViewText(R.id.h_slot, heroEv.slot.toString()); v.setInt(R.id.h_slot, "setBackgroundResource", if (late) R.drawable.w_badge_old else R.drawable.w_badge_ok)
            v.setTextColor(R.id.h_slot, if (late) 0xFFFFFFFF.toInt() else 0xFF0B111B.toInt())
            v.setTextViewText(R.id.h_kind, if (late) "⏰ atrasado" else if (heroEv.kind == 1) "próximo treino" else "próxima obra")
            v.setViewVisibility(R.id.h_crest_me, android.view.View.GONE); v.setViewVisibility(R.id.h_emoji, android.view.View.VISIBLE)
            v.setTextViewText(R.id.h_emoji, heroEv.icon)
            v.setTextViewText(R.id.h_me, heroEv.title)
            setCrest(ctx, v, R.id.h_crest_opp, heroEv.club, 120); v.setTextViewText(R.id.h_opp, heroEv.club ?: "Slot ${heroEv.slot}")
            v.setTextViewText(R.id.h_when, if (late) ago(now - heroEv.at).removePrefix("há ") else until(heroEv.at, now).removePrefix("em ").ifEmpty { "agora" })
            v.setTextColor(R.id.h_when, if (late) C_RED else urgencyColor(heroEv.at, now))
            v.setTextViewText(R.id.h_time, "${day(heroEv.at)} • ${clock(heroEv.at)}")
            v.setTextViewText(R.id.h_str, heroEv.sub)
            v.setImageViewBitmap(R.id.h_prob, null); v.setTextViewText(R.id.h_probtxt, ""); v.setImageViewBitmap(R.id.h_form_me, null); v.setImageViewBitmap(R.id.h_form_opp, null)
            v.setTextViewText(R.id.h_status, if (late) "⏰ colete e comece o próximo" else "✅ em andamento"); v.setTextColor(R.id.h_status, if (late) C_RED else C_GREEN)
            click(ctx, v, R.id.h_card, rows.firstOrNull { it.slot == heroEv.slot } ?: rows.first(), 900)
        } else {
            v.setViewVisibility(R.id.h_crest_me, android.view.View.VISIBLE); v.setViewVisibility(R.id.h_emoji, android.view.View.GONE)
            v.setTextViewText(R.id.h_kind, "próximo jogo")
        }
        // contagem ao vivo (o Android conta sozinho, sem atualizar o widget): só para o que acontece nas próximas 24 h
        val target = if (heroEv != null && heroEv.kind != 0) heroEv.at else h?.at
        if (target != null && target > now && target - now <= 24 * 3_600_000L) {
            v.setViewVisibility(R.id.h_chrono, android.view.View.VISIBLE)
            v.setChronometerCountDown(R.id.h_chrono, true)
            v.setChronometer(R.id.h_chrono, android.os.SystemClock.elapsedRealtime() + (target - now), null, true)
        } else v.setViewVisibility(R.id.h_chrono, android.view.View.GONE)
        // lista da Agenda (só com 4 colunas): o que já passou e ainda espera você; sem nada atrasado, "tudo em dia"
        if (wDp >= 250) {
            v.setViewVisibility(R.id.e_box, android.view.View.VISIBLE)
            val late = overdue.filter { it !== heroEv }.sortedBy { it.at }.take(3)
            val ids = listOf(intArrayOf(R.id.e1, R.id.e1_icon, R.id.e1_title, R.id.e1_when), intArrayOf(R.id.e2, R.id.e2_icon, R.id.e2_title, R.id.e2_when), intArrayOf(R.id.e3, R.id.e3_icon, R.id.e3_title, R.id.e3_when))
            if (late.isEmpty()) {
                v.setViewVisibility(ids[0][0], android.view.View.VISIBLE); v.setViewVisibility(ids[1][0], android.view.View.GONE); v.setViewVisibility(ids[2][0], android.view.View.GONE)
                v.setTextViewText(ids[0][1], "👍"); v.setTextViewText(ids[0][2], "Tudo em dia: nada atrasado na agenda"); v.setTextViewText(ids[0][3], "✓"); v.setTextColor(ids[0][3], C_GREEN)
            } else for ((i, id) in ids.withIndex()) {
                val e = late.getOrNull(i)
                if (e == null) { v.setViewVisibility(id[0], android.view.View.GONE); continue }
                v.setViewVisibility(id[0], android.view.View.VISIBLE)
                v.setTextViewText(id[1], e.icon); v.setTextViewText(id[2], "S${e.slot} • ${e.title}"); v.setTextViewText(id[3], ago(now - e.at)); v.setTextColor(id[3], C_RED)
            }
        } else v.setViewVisibility(R.id.e_box, android.view.View.GONE)
        if (h != null) {
            v.setTextViewText(R.id.h_slot, h.slot.toString()); v.setInt(R.id.h_slot, "setBackgroundResource", badgeFor(h.state))
            v.setTextColor(R.id.h_slot, if (h.state == STATE_OLD) 0xFFFFFFFF.toInt() else 0xFF0B111B.toInt())
            setCrest(ctx, v, R.id.h_crest_me, h.club, 120); setCrest(ctx, v, R.id.h_crest_opp, h.rival, 120)
            v.setTextViewText(R.id.h_me, h.club); v.setTextViewText(R.id.h_opp, h.rival ?: "—")
            v.setTextViewText(R.id.h_when, h.`when`.removePrefix("em ").ifEmpty { "sem jogo" })
            v.setTextColor(R.id.h_when, if (h.stale) C_MUTED else urgencyColor(h.at, now))
            v.setTextViewText(R.id.h_time, if (h.at != null) "${day(h.at)} • ${clock(h.at)} ${where(h)}".trim() else h.short)
            v.setTextViewText(R.id.h_str, listOfNotNull(if (h.strMe != null && h.strOpp != null) strengths(h) else null, h.round, h.pos).joinToString(" • "))
            setProb(ctx, v, R.id.h_prob, h, wDp * 0.8f); v.setTextViewText(R.id.h_probtxt, probText(h))
            setForm(ctx, v, R.id.h_form_me, h.formMe, false); setForm(ctx, v, R.id.h_form_opp, h.formOpp, true)
            val st = staleText(h, now)
            v.setTextViewText(R.id.h_status, if (st.isNotEmpty()) st else h.icon + " " + h.short)
            v.setTextColor(R.id.h_status, stateColor(h.state))
            click(ctx, v, R.id.h_card, h, 900)
        }
        for ((i, r) in rows.withIndex()) {
            val id = LIST[i]
            v.setTextViewText(id[1], r.slot.toString()); v.setInt(id[1], "setBackgroundResource", badgeFor(r.state))
            v.setTextColor(id[1], if (r.state == STATE_OLD) 0xFFFFFFFF.toInt() else 0xFF0B111B.toInt())
            v.setTextViewText(id[3], r.club.ifBlank { "sem clube" })
            v.setTextViewText(id[4], if (r.rival != null && r.at != null) "× ${r.rival} • ${clock(r.at)}" else r.short.ifBlank { "abra o OSM com a captura ligada" })
            v.setTextViewText(id[5], if (r.state == STATE_NONE) "livre" else short(r))
            v.setTextColor(id[5], if (r.state == STATE_NONE || r.stale) C_MUTED else urgencyColor(r.at, now))
            v.setTextViewText(id[6], if (r.stale) staleText(r, now) else listOfNotNull(r.round, r.pos).joinToString(" • "))
            v.setTextColor(id[6], if (r.stale) C_RED else C_MUTED)
            setCrest(ctx, v, id[2], if (r.state == STATE_NONE) null else r.club, 64)
            click(ctx, v, id[0], r, 700)
        }
        v.setTextViewText(R.id.l_footer, upd(now))
        return v
    }

    /** Escolhe o layout pelo tamanho atual do widget (dp) — funciona do Android 11 em diante (opções do widget), sem depender do modo responsivo do Android 12. */
    fun views(ctx: Context, rows: List<Row>, now: Long, wDp: Int, hDp: Int, evs: List<Ev> = emptyList()): RemoteViews = when {
        wDp >= 200 && hDp >= 210 -> large(ctx, rows, now, wDp, evs)
        wDp >= 200 -> wide(ctx, rows, now)
        else -> small(ctx, rows, now, wDp)
    }

    fun intentFor(ctx: Context, r: Row): Intent = Intent(ctx, MainActivity::class.java)
        .setAction("com.osmaicoach.collector.WIDGET_OPEN").setData(Uri.parse("osmcoach://slot/${r.slot}/${r.tab}"))
        .putExtra("slot", r.slot).putExtra("tab", r.tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    val PROVIDERS = listOf(SlotWidgetProvider::class.java, SlotWidgetSmallProvider::class.java, SlotWidgetLargeProvider::class.java)

    suspend fun update(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val all = PROVIDERS.flatMap { c -> mgr.getAppWidgetIds(ComponentName(ctx, c)).toList() }
        if (all.isEmpty()) return
        val now = System.currentTimeMillis()
        val rows = loadRows(ctx, now)
        val evs = loadEvents(ctx)
        for (id in all) {
            val o = mgr.getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250); val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 120))
            runCatching { mgr.updateAppWidget(id, views(ctx, rows, now, w, h, evs)) }
        }
    }

    @Volatile private var appCtx: Context? = null
    @Volatile private var pending = false
    fun attach(ctx: Context) { appCtx = ctx.applicationContext }

    /**
     * Chamado quando a captura grava algo (via `UiBus.bump`) e a cada minuto pelo monitor: agrupa as mudanças e atualiza 3 s DEPOIS da última
     * (antes um limite fixo descartava a última leitura e o widget ficava com o status velho).
     */
    fun requestRefresh() {
        val c = appCtx ?: return
        if (pending) return
        pending = true
        AppScope.scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(3000L)
            pending = false
            runCatching { update(c) }
        }
    }
}

open class SlotWidgetProvider : AppWidgetProvider() {
    private fun go(context: Context) {
        SlotWidget.attach(context)
        val pending = goAsync()
        AppScope.scope.launch(Dispatchers.IO) { try { SlotWidget.update(context) } finally { pending.finish() } }
    }
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = go(context)
    /** Redimensionar o widget troca o layout (2×2 / 4×2 / 4×4) na hora. */
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle) = go(context)
}

/** Mesmo widget, oferecido no seletor com tamanho inicial 2×2 (próximo jogo). */
class SlotWidgetSmallProvider : SlotWidgetProvider()

/** Mesmo widget, oferecido no seletor com tamanho inicial 4×4 (painel completo). */
class SlotWidgetLargeProvider : SlotWidgetProvider()
