package com.osmaicoach.collector

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Resultados de TODOS os jogos da liga (tela de resultados da jornada), classificação cruzada e Radar do rival (24/11).
 * Guardado no plano "lres" do slot: { "<competição>|<total>": { "<jornada>": { "<casa>|<fora>": {h,a,gh,ga,n,at} }, ... } }.
 * Uma partida é salva uma vez (por slot, competição/temporada, jornada e confronto); reler só confirma. Leitura incerta nunca apaga dado melhor.
 * Layout da tela NÃO validado com print real (o parser é tolerante: linha "A 1-2 B" ou nome | placar | nome na mesma altura).
 */
object LeagueResults {
    data class Game(val home: String, val away: String, val gh: Int, val ga: Int)
    data class Parsed(val round: Int?, val games: List<Game>)
    /** Contexto da rolagem: o cabeçalho "Jornada N" some ao rolar; só herda dentro da mesma sessão de tela. */
    class Session { var slot: Int = -1; var round: Int = -1; var at: Long = 0L }
    const val INHERIT_MS = 40_000L

    private val RX_SCORE = Regex("^(\\d{1,2})\\s*[-–:xX×]\\s*(\\d{1,2})$")
    private val RX_LINE = Regex("^(.+?)\\s+(\\d{1,2})\\s*[-–:xX×]\\s*(\\d{1,2})\\s+(.+)$")
    private val RX_ROUND = Regex("(?:jornada|rodada)\\s*(\\d{1,2})")

    private fun isOtherScreen(t: String): Boolean =
        t.contains("primeira parte") || t.contains("homem do jogo") || t.contains("resumo da epoca") || t.contains("zonas de acao") ||
            t.contains("gm-gs") || t.contains("plantel") && t.contains("idade") || t.contains("preparacao para o jogo")

    fun parse(o: OcrResult): Parsed? {
        val t = Txt.norm(o.fullText)
        if (isOtherScreen(t)) return null
        val rounds = o.lines.filter { it.yc < 0.40f }.mapNotNull { RX_ROUND.find(Txt.norm(it.text))?.groupValues?.get(1)?.toIntOrNull() }.distinct()
        if (rounds.size > 1) return null                  // dois cabeçalhos diferentes: ambíguo
        val games = ArrayList<Game>()
        for (ln in o.lines) {
            val tx = ln.text.trim()
            val single = RX_LINE.find(tx)
            if (single != null && ln.r - ln.l > 0.30f) {
                val h = single.groupValues[1].trim(); val a = single.groupValues[4].trim()
                if (Txt.letters(h) >= 2 && Txt.letters(a) >= 2 && single.groupValues[2].toInt() <= 15 && single.groupValues[3].toInt() <= 15) { games.add(Game(h, a, single.groupValues[2].toInt(), single.groupValues[3].toInt())); continue }
            }
            val sc = RX_SCORE.find(tx) ?: continue
            if (ln.xc !in 0.30f..0.70f || ln.yc < 0.12f || sc.groupValues[1].toInt() > 15 || sc.groupValues[2].toInt() > 15) continue
            val same = o.lines.filter { it !== ln && abs(it.yc - ln.yc) <= 0.030f && Txt.letters(it.text) >= 2 }
            val left = same.filter { it.r <= ln.l + 0.03f }.maxByOrNull { it.r }
            val right = same.filter { it.l >= ln.r - 0.03f }.minByOrNull { it.l }
            if (left == null || right == null) continue
            games.add(Game(left.text.trim(), right.text.trim(), sc.groupValues[1].toInt(), sc.groupValues[2].toInt()))
        }
        val uniq = games.distinctBy { Txt.key(it.home) + "|" + Txt.key(it.away) }
        val minRows = if (rounds.isNotEmpty()) 2 else 3
        if (uniq.size < minRows) return null
        return Parsed(rounds.firstOrNull(), uniq)
    }

    /** Define a jornada da leitura: do cabeçalho, ou herdada da MESMA tela/sessão (mesmo slot, até 40 s, cadeia contínua). Senão null = não salva. */
    fun resolveRound(p: Parsed, slot: Int, now: Long, s: Session): Int? {
        if (p.round != null) { s.slot = slot; s.round = p.round; s.at = now; return p.round }
        if (s.slot == slot && s.round > 0 && now - s.at <= INHERIT_MS) { s.at = now; return s.round }
        return null
    }

    // ------------------------------------------------------------------ guardar
    fun compKey(f: Map<String, StoredField>): String? {
        val c = (f[K.COMPETITION]?.value ?: f[K.HUB_TITLE]?.value)?.takeIf { FieldMerge.known(it) } ?: return null
        val tot = f[K.ROUND_TOTAL]?.value?.filter { it.isDigit() }.orEmpty()
        return Txt.key(c).take(40) + "|" + tot
    }

    private fun same(a: String, b: String): Boolean = Txt.key(a) == Txt.key(b) || Txt.sim(Txt.key(a), Txt.key(b)) >= 0.85

    /** Grava as partidas; devolve quantas são NOVAS. Dado existente só é confirmado (n+1); placar conflitante fica com o mais confirmado. */
    fun store(old: JSONObject?, comp: String, round: Int, games: List<Game>, canon: List<String>, now: Long): Pair<JSONObject, Int> {
        val root = old ?: JSONObject()
        val c = root.optJSONObject(comp) ?: JSONObject().also { root.put(comp, it) }
        val r = c.optJSONObject(round.toString()) ?: JSONObject().also { c.put(round.toString(), it) }
        var added = 0
        fun canonical(n: String): String = canon.firstOrNull { same(it, n) } ?: n.trim()
        for (g in games) {
            val h = canonical(g.home); val a = canonical(g.away)
            if (same(h, a)) continue
            var hit: String? = null
            for (k in r.keys()) { val e = r.optJSONObject(k) ?: continue; if (same(e.optString("h"), h) && same(e.optString("a"), a)) { hit = k; break } }
            if (hit == null) {
                r.put(Txt.key(h) + "|" + Txt.key(a), JSONObject().put("h", h).put("a", a).put("gh", g.gh).put("ga", g.ga).put("n", 1).put("at", now)); added++
            } else {
                val e = r.getJSONObject(hit)
                if (e.optInt("gh") == g.gh && e.optInt("ga") == g.ga) e.put("n", e.optInt("n", 1) + 1).put("at", now)
                // conflito: uma leitura isolada não derruba o placar já confirmado
            }
        }
        root.put("lastAt", now).put("lastComp", comp).put("lastRound", round)
        return Pair(root, added)
    }

    class Stored(val round: Int, val home: String, val away: String, val gh: Int, val ga: Int, val n: Int, val at: Long)

    fun games(j: JSONObject?, comp: String): List<Stored> {
        val c = j?.optJSONObject(comp) ?: return emptyList()
        val out = ArrayList<Stored>()
        for (rk in c.keys()) {
            val rn = rk.toIntOrNull() ?: continue
            val r = c.optJSONObject(rk) ?: continue
            for (k in r.keys()) { val e = r.optJSONObject(k) ?: continue; out.add(Stored(rn, e.optString("h"), e.optString("a"), e.optInt("gh"), e.optInt("ga"), e.optInt("n", 1), e.optLong("at"))) }
        }
        return out.sortedWith(compareBy({ it.round }, { it.home }))
    }

    /** Cobertura: jornadas lidas, jogos por jornada e o total esperado (clubes/2) quando se sabe quantos clubes a liga tem. */
    class Coverage(val rounds: Int, val games: Int, val perRound: Map<Int, Int>, val expectedPerRound: Int?, val lastAt: Long)
    fun coverage(j: JSONObject?, comp: String, clubs: Int?): Coverage? {
        val g = games(j, comp)
        if (g.isEmpty()) return null
        val per = g.groupBy { it.round }.mapValues { it.value.size }.toSortedMap()
        return Coverage(per.size, g.size, per, clubs?.takeIf { it >= 4 }?.div(2), g.maxOf { it.at })
    }

    // ------------------------------------------------------------------ classificação ajustada (sem somar duas vezes)
    class Adj(val club: String, val rank: Int?, val j: Int, val pts: Int, val applied: List<String>)
    class AdjResult(val rows: List<Adj>, val skipped: Int, val mixedRounds: Boolean)

    /**
     * Parte da classificação LIDA e só soma partidas que ela AINDA NÃO inclui: o jogo da jornada R está incluído se os dois clubes já têm j ≥ R.
     * Se os dois têm j = R−1 soma 3/1/0 (estimativa); qualquer outra combinação (jogos anteriores faltando, só um incluído) não entra.
     */
    fun adjust(st: List<League.Row>, games: List<Stored>): AdjResult {
        val base = st.filter { it.j != null && it.pts != null }
        val pts = HashMap<String, Int>(); val jj = HashMap<String, Int>(); val ap = HashMap<String, MutableList<String>>()
        for (r in base) { pts[r.club] = r.pts!!; jj[r.club] = r.j!!; ap[r.club] = ArrayList() }
        var skipped = 0
        for (g in games.sortedBy { it.round }) {
            val h = League.find(base, g.home)?.club; val a = League.find(base, g.away)?.club
            if (h == null || a == null || h == a) { skipped++; continue }
            val jh = jj[h]!!; val ja = jj[a]!!
            when {
                jh >= g.round && ja >= g.round -> {}                        // já está na tabela
                jh == g.round - 1 && ja == g.round - 1 -> {
                    val (ph, pa) = if (g.gh > g.ga) Pair(3, 0) else if (g.gh < g.ga) Pair(0, 3) else Pair(1, 1)
                    pts[h] = pts[h]!! + ph; pts[a] = pts[a]!! + pa; jj[h] = jh + 1; jj[a] = ja + 1
                    ap[h]!!.add("J${g.round} ${g.home} ${g.gh}×${g.ga} ${g.away}"); ap[a]!!.add("J${g.round} ${g.home} ${g.gh}×${g.ga} ${g.away}")
                }
                else -> skipped++
            }
        }
        val rows = base.map { Adj(it.club, it.rank, jj[it.club]!!, pts[it.club]!!, ap[it.club]!!) }
        val js = rows.map { it.j }
        return AdjResult(rows, skipped, js.isNotEmpty() && js.max() - js.min() > 1)
    }

    fun rankOf(rows: List<Adj>, club: String?): Int? {
        val me = rows.firstOrNull { League.key(it.club) == League.key(club ?: "") } ?: return null
        return 1 + rows.count { it.pts > me.pts }
    }

    // ------------------------------------------------------------------ comparar duas classificações
    class Movement(val rankFrom: Int, val rankTo: Int, val passed: List<String>, val closed: List<String>, val affecting: List<String>, val ptsGain: Int)

    /** Duas fotos comparáveis (≥80% dos mesmos clubes, com posição nas duas): quem ultrapassei, quem encostou e os resultados que mexeram. */
    fun movement(snaps: JSONArray?, myClub: String?, games: List<Stored>): Movement? {
        if (snaps == null || snaps.length() < 2 || myClub == null) return null
        val a = snaps.getJSONObject(snaps.length() - 2).optJSONObject("r") ?: return null
        val b = snaps.getJSONObject(snaps.length() - 1).optJSONObject("r") ?: return null
        val common = a.keys().asSequence().filter { b.has(it) }.toList()
        if (common.size < 0.8 * maxOf(a.length(), b.length())) return null
        val mk = common.firstOrNull { Txt.sim(it, League.key(myClub)) >= 0.85 } ?: return null
        fun rk(o: JSONObject, k: String) = o.getJSONArray(k).getInt(0)
        fun pt(o: JSONObject, k: String) = o.getJSONArray(k).getInt(2)
        fun jn(o: JSONObject, k: String) = o.getJSONArray(k).getInt(1)
        if (rk(a, mk) < 1 || rk(b, mk) < 1 || jn(b, mk) <= jn(a, mk)) return null
        val passed = ArrayList<String>(); val closed = ArrayList<String>()
        for (k in common) {
            if (k == mk) continue
            val name = b.getJSONArray(k).getString(3)
            if (rk(a, k) in 1 until rk(a, mk) && rk(b, k) > rk(b, mk)) passed.add(name)
            else if (rk(a, k) > rk(a, mk) && rk(b, k) > rk(b, mk)) {
                val gapA = pt(a, mk) - pt(a, k); val gapB = pt(b, mk) - pt(b, k)
                if (gapB < gapA && gapB in 0..3) closed.add(name)
            }
        }
        val rounds = (jn(a, mk) + 1)..jn(b, mk)
        val names = (passed + closed + b.getJSONArray(mk).getString(3)).map { League.key(it) }
        val aff = games.filter { it.round in rounds && names.any { n -> Txt.sim(n, League.key(it.home)) >= 0.85 || Txt.sim(n, League.key(it.away)) >= 0.85 } }
            .map { "J${it.round} ${it.home} ${it.gh}×${it.ga} ${it.away}" }
        return Movement(rk(a, mk), rk(b, mk), passed, closed, aff, pt(b, mk) - pt(a, mk))
    }

    // ------------------------------------------------------------------ projeção
    class Projection(
        val confirmedBest: Int, val confirmedWorst: Int, val remaining: Int,
        val estimateRank: Int?, val estimatePts: Int?, val tieNote: Boolean,
        val ifWin: Int?, val ifDraw: Int?, val ifLoss: Int?, val effectNote: String?, val limits: List<String>
    )

    /**
     * Faixa de posição final. "Confirmada" = só aritmética (todos ganham/perdem tudo o que falta; empate de pontos = desempate DESCONHECIDO, conta como possível contra mim
     * e nunca a favor). "Estimativa" = ritmo de pontos por jogo até agora (j ≥ 5), rotulada. Efeito do próximo jogo: minha posição na tabela atual se eu somar 3/1/0 (os outros não jogam).
     */
    fun project(adj: AdjResult, myClub: String?, totalRounds: Int?, hasNextGame: Boolean): Projection? {
        val rows = adj.rows
        if (rows.size < 4 || myClub == null) return null
        val me = rows.firstOrNull { League.key(it.club) == League.key(myClub) } ?: League.find(rows.map { League.Row(it.club, null, it.rank) }, myClub)?.let { m -> rows.firstOrNull { it.club == m.club } } ?: return null
        val lim = ArrayList<String>()
        if (adj.mixedRounds) lim.add("a tabela mistura jornadas diferentes: o efeito do próximo jogo não é comparável")
        if (adj.skipped > 0) lim.add("${adj.skipped} resultado(s) lido(s) não puderam ser casados com a tabela")
        val tot = totalRounds?.takeIf { it >= me.j }
        if (tot == null) lim.add("total de jornadas desconhecido: sem faixa final")
        val rem = tot?.minus(me.j)
        var best = 0; var worst = 0
        var estRank: Int? = null; var estPts: Int? = null; var ties = false
        if (tot != null) {
            val myMax = me.pts + 3 * (tot - me.j)
            best = 1 + rows.count { it.club != me.club && it.pts > myMax }
            worst = 1 + rows.count { it.club != me.club && it.pts + 3 * maxOf(0, tot - it.j) >= me.pts }
            if (rows.all { it.j >= 5 }) {
                fun proj(r: Adj) = r.pts + Math.round(r.pts.toDouble() / r.j * (tot - r.j)).toInt()
                estPts = proj(me); estRank = 1 + rows.count { it.club != me.club && proj(it) > estPts }
                ties = rows.any { it.club != me.club && proj(it) == estPts }
            } else lim.add("menos de 5 jogos em algum clube: sem estimativa por ritmo")
        }
        var w: Int? = null; var d: Int? = null; var l: Int? = null; var note: String? = null
        if (hasNextGame && !adj.mixedRounds) {
            fun rankWith(add: Int) = 1 + rows.count { it.club != me.club && it.pts > me.pts + add }
            fun tied(add: Int) = rows.any { it.club != me.club && it.pts == me.pts + add }
            w = rankWith(3); d = rankWith(1); l = rankWith(0)
            note = "posição na tabela atual somando o resultado do próximo jogo; os outros jogos da rodada não entram" +
                (if (tied(3) || tied(1) || tied(0)) "; empate de pontos com outro clube: desempate desconhecido (posição é a melhor possível)" else "")
        } else if (!hasNextGame) lim.add("próximo jogo não confirmado no calendário: sem efeito de vitória/empate/derrota")
        return Projection(best, worst, rem ?: -1, estRank, estPts, ties, w, d, l, note, lim)
    }

    // ------------------------------------------------------------------ Radar do próximo rival
    class RadarGame(val round: Int, val opponent: String, val home: Boolean, val gf: Int, val ga: Int, val oppRank: Int?)
    class Radar(val rival: String, val games: List<RadarGame>, val homeAvg: Pair<Double, Double>?, val awayAvg: Pair<Double, Double>?, val reference: String?)

    /** Até 5 partidas CONFIRMADAS (lidas) do rival na mesma competição; a amostra é a que existe (3 jogos = 3). Nada de formação/estilo deduzido. */
    fun radar(all: List<Stored>, rival: String?, st: List<League.Row>, stAt: Long?): Radar? {
        if (rival.isNullOrBlank()) return null
        val mine = all.filter { same(it.home, rival) || same(it.away, rival) }.sortedByDescending { it.round }.take(5)
        if (mine.isEmpty()) return null
        val gs = mine.map { g ->
            val home = same(g.home, rival)
            val opp = if (home) g.away else g.home
            RadarGame(g.round, opp, home, if (home) g.gh else g.ga, if (home) g.ga else g.gh, League.find(st, opp)?.rank)
        }
        fun avg(l: List<RadarGame>) = if (l.isEmpty()) null else Pair(l.sumOf { it.gf }.toDouble() / l.size, l.sumOf { it.ga }.toDouble() / l.size)
        val ref = if (st.any { it.rank != null } && stAt != null) "posição na classificação lida em " + java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(stAt)) + " (a de hoje, não a da data do jogo)" else null
        return Radar(rival, gs, avg(gs.filter { it.home }), avg(gs.filter { !it.home }), ref)
    }
}
