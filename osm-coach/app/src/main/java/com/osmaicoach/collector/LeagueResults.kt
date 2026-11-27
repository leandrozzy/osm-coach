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
            // cada lado tem 2 linhas: NOME (em cima) e apelido do treinador (embaixo, mais fraco); o placar fica entre as duas. Vale a de cima.
            val same = o.lines.filter { it !== ln && abs(it.yc - ln.yc) <= 0.050f && Txt.letters(it.text) >= 2 }
            val left = same.filter { it.r <= ln.l + 0.03f }.minByOrNull { it.yc }
            val right = same.filter { it.l >= ln.r - 0.03f }.minByOrNull { it.yc }
            if (left == null || right == null) continue
            games.add(Game(left.text.trim(), right.text.trim(), sc.groupValues[1].toInt(), sc.groupValues[2].toInt()))
        }
        val uniq = games.distinctBy { Txt.key(it.home) + "|" + Txt.key(it.away) }
        val minRows = if (rounds.isNotEmpty()) 2 else 3
        if (uniq.size < minRows) return null
        return Parsed(rounds.firstOrNull(), uniq)
    }

    // ------------------------------------------------------------------ copa (fases: cartões com escudos, placar no meio e nomes embaixo)
    val PHASES = listOf("fase preliminar" to 1, "oitavos" to 2, "quartos" to 3, "meias" to 4, "final" to 5)
    fun phaseOf(text: String): Int? {
        val n = Txt.norm(text).trim()
        if (n.length > 30 || n.contains("jornada")) return null
        return PHASES.firstOrNull { (k, _) -> if (k == "final") n == "final" else n.startsWith(k) }?.second
    }

    /** Fase da copa (1 preliminar … 5 final) e os jogos JÁ JOGADOS dela (placar lido); "VS" = a definir/ainda não jogado, fica de fora. */
    class CupParsed(val games: List<Pair<Int, Game>>)
    fun parseCup(o: OcrResult): CupParsed? {
        val heads = o.lines.filter { it.xc in 0.35f..0.65f && it.yc < 0.97f }.mapNotNull { l -> phaseOf(l.text)?.let { Pair(l, it) } }.sortedBy { it.first.yc }
        if (heads.isEmpty()) return null
        val out = ArrayList<Pair<Int, Game>>()
        for (ln in o.lines) {
            val sc = RX_SCORE.find(ln.text.trim()) ?: continue
            if (sc.groupValues[1].toInt() > 15 || sc.groupValues[2].toInt() > 15) continue
            val head = heads.lastOrNull { it.first.yc < ln.yc } ?: continue
            if (ln.yc - head.first.yc > 0.45f) continue
            val cardW = 0.25f; val idx = (ln.xc / cardW).toInt().coerceIn(0, 3); val l0 = idx * cardW; val r0 = l0 + cardW
            val below = o.lines.filter { it.yc in (ln.yc + 0.05f)..(ln.yc + 0.22f) && it.xc in l0..r0 && Txt.letters(it.text) >= 2 && Txt.norm(it.text) != "vs" }
            val left = below.filter { it.xc < ln.xc }.minByOrNull { it.yc }
            val right = below.filter { it.xc > ln.xc }.minByOrNull { it.yc }
            if (left == null || right == null) continue
            out.add(Pair(head.second, Game(left.text.trim(), right.text.trim(), sc.groupValues[1].toInt(), sc.groupValues[2].toInt())))
        }
        return if (out.isEmpty()) null else CupParsed(out.distinctBy { it.first.toString() + Txt.key(it.second.home) + Txt.key(it.second.away) })
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
        return canonicalize(out).first
    }

    /**
     * Junta variações do nome do mesmo clube ("Athletico-PR"/"Atletico PR": o OCR lê diferente em cada passada) e tira o jogo repetido da mesma jornada.
     * Devolve os jogos já com o nome canônico e a quantidade de clubes distintos.
     */
    fun canonicalize(all: List<Stored>): Pair<List<Stored>, Int> {
        val count = HashMap<String, Int>()
        for (g in all) { count[g.home] = (count[g.home] ?: 0) + g.n; count[g.away] = (count[g.away] ?: 0) + g.n }
        val reps = ArrayList<String>(); val map = HashMap<String, String>()
        for (name in count.keys.sortedByDescending { count[it] }) {
            val k = Txt.key(name)
            val rep = reps.firstOrNull { r -> Txt.key(r) == k || Txt.sim(Txt.key(r), k) >= 0.82 }
            if (rep == null) { reps.add(name); map[name] = name } else map[name] = rep
        }
        val seen = HashSet<String>(); val out = ArrayList<Stored>()
        for (g in all.sortedWith(compareBy({ it.round }, { -it.n }))) {
            val h = map[g.home] ?: g.home; val a = map[g.away] ?: g.away
            if (h == a || !seen.add("${g.round}|${Txt.key(h)}|${Txt.key(a)}")) continue
            out.add(Stored(g.round, h, a, g.gh, g.ga, g.n, g.at))
        }
        return Pair(out.sortedWith(compareBy({ it.round }, { it.home })), reps.size)
    }

    /**
     * Cobertura: jornadas lidas, jogos por jornada, quantos clubes a liga tem (pelos nomes lidos, ou pela classificação) e o que falta:
     * jornadas jogadas sem nenhuma leitura (missing), com menos jogos que o esperado (partial) ou com MAIS (over: nome lido de dois jeitos).
     */
    class Coverage(
        val rounds: Int, val games: Int, val perRound: Map<Int, Int>, val expectedPerRound: Int?, val lastAt: Long,
        val clubs: Int = 0, val missing: List<Int> = emptyList(), val partial: List<Int> = emptyList(), val over: List<Int> = emptyList()
    )
    fun coverage(j: JSONObject?, comp: String, clubs: Int?, played: Int? = null): Coverage? {
        val (g, nClubs) = canonicalize(games0(j, comp))
        if (g.isEmpty()) return null
        val per = g.groupBy { it.round }.mapValues { it.value.size }.toSortedMap()
        val n = (clubs?.takeIf { it >= 4 } ?: nClubs).takeIf { it in 4..40 }
        val exp = n?.div(2)
        val last = played?.takeIf { it > 0 } ?: per.keys.max()
        val missing = (1..last).filter { it !in per }
        val partial = if (exp != null) per.filter { it.value < exp }.keys.toList() else emptyList()
        val over = if (exp != null) per.filter { it.value > exp }.keys.toList() else emptyList()
        return Coverage(per.size, g.size, per, exp, g.maxOf { it.at }, nClubs, missing, partial, over)
    }

    private fun games0(j: JSONObject?, comp: String): List<Stored> {
        val c = j?.optJSONObject(comp) ?: return emptyList()
        val out = ArrayList<Stored>()
        for (rk in c.keys()) {
            val rn = rk.toIntOrNull() ?: continue
            val r = c.optJSONObject(rk) ?: continue
            for (k in r.keys()) { val e = r.optJSONObject(k) ?: continue; out.add(Stored(rn, e.optString("h"), e.optString("a"), e.optInt("gh"), e.optInt("ga"), e.optInt("n", 1), e.optLong("at"))) }
        }
        return out
    }

    // ------------------------------------------------------------------ classificação feita só com os resultados lidos
    class Derived(val rows: List<League.Row>, val rounds: Int, val tieAmbiguous: Boolean)

    /**
     * Monta a tabela SÓ dos resultados lidos, e só quando eles estão COMPLETOS: todas as jornadas de 1 até a última lida com exatamente clubes/2 jogos
     * (senão qualquer jornada faltando mudaria os pontos). Ordem: pontos, saldo, gols pró (o desempate real do OSM é desconhecido: `tieAmbiguous`).
     */
    fun tableFromResults(all: List<Stored>): Derived? {
        val (g, clubs) = canonicalize(all)
        if (g.isEmpty() || clubs < 4 || clubs % 2 != 0) return null
        val per = g.groupBy { it.round }
        val last = per.keys.max()
        if ((1..last).any { (per[it]?.size ?: 0) != clubs / 2 }) return null
        val pts = HashMap<String, IntArray>()   // j, v, e, d, pts, gf, ga
        fun row(n: String) = pts.getOrPut(n) { IntArray(7) }
        for (x in g) {
            val h = row(x.home); val a = row(x.away)
            h[0]++; a[0]++; h[5] += x.gh; h[6] += x.ga; a[5] += x.ga; a[6] += x.gh
            if (x.gh > x.ga) { h[1]++; h[4] += 3; a[3]++ } else if (x.gh < x.ga) { a[1]++; a[4] += 3; h[3]++ } else { h[2]++; a[2]++; h[4]++; a[4]++ }
        }
        val sorted = pts.entries.sortedWith(compareByDescending<Map.Entry<String, IntArray>> { it.value[4] }.thenByDescending { it.value[5] - it.value[6] }.thenByDescending { it.value[5] })
        val rows = sorted.mapIndexed { i, e -> val v = e.value; League.Row(e.key, null, i + 1, v[0], v[1], v[2], v[3], v[4], v[5], v[6]) }
        val tie = sorted.zipWithNext().any { (a, b) -> a.value[4] == b.value[4] && a.value[5] - a.value[6] == b.value[5] - b.value[6] && a.value[5] == b.value[5] }
        return Derived(rows, last, tie)
    }

    // ------------------------------------------------------------------ classificação ajustada (sem somar duas vezes)
    class Adj(val club: String, val rank: Int?, val j: Int, val pts: Int, val applied: List<String>)
    class AdjResult(val rows: List<Adj>, val skipped: Int, val mixedRounds: Boolean, val unmatched: Int = 0, val roundGap: Int = 0, val maxSkippedRound: Int = 0)

    /**
     * Parte da classificação LIDA e só soma partidas que ela AINDA NÃO inclui: o jogo da jornada R está incluído se os dois clubes já têm j ≥ R.
     * Se os dois têm j = R−1 soma 3/1/0 (estimativa); qualquer outra combinação (jogos anteriores faltando, só um incluído) não entra.
     */
    fun adjust(st: List<League.Row>, games: List<Stored>): AdjResult {
        val base = st.filter { it.j != null && it.pts != null }
        val pts = HashMap<String, Int>(); val jj = HashMap<String, Int>(); val ap = HashMap<String, MutableList<String>>()
        for (r in base) { pts[r.club] = r.pts!!; jj[r.club] = r.j!!; ap[r.club] = ArrayList() }
        var skipped = 0; var unmatched = 0; var roundGap = 0; var maxRound = 0
        for (g in games.sortedBy { it.round }) {
            val h = League.find(base, g.home)?.club; val a = League.find(base, g.away)?.club
            if (h == null || a == null || h == a) { skipped++; unmatched++; continue }
            val jh = jj[h]!!; val ja = jj[a]!!
            when {
                jh >= g.round && ja >= g.round -> {}                        // já está na tabela
                jh == g.round - 1 && ja == g.round - 1 -> {
                    val (ph, pa) = if (g.gh > g.ga) Pair(3, 0) else if (g.gh < g.ga) Pair(0, 3) else Pair(1, 1)
                    pts[h] = pts[h]!! + ph; pts[a] = pts[a]!! + pa; jj[h] = jh + 1; jj[a] = ja + 1
                    ap[h]!!.add("J${g.round} ${g.home} ${g.gh}×${g.ga} ${g.away}"); ap[a]!!.add("J${g.round} ${g.home} ${g.gh}×${g.ga} ${g.away}")
                }
                else -> { skipped++; roundGap++; maxRound = maxOf(maxRound, g.round) }
            }
        }
        val rows = base.map { Adj(it.club, it.rank, jj[it.club]!!, pts[it.club]!!, ap[it.club]!!) }
        val js = rows.map { it.j }
        return AdjResult(rows, skipped, js.isNotEmpty() && js.max() - js.min() > 1, unmatched, roundGap, maxRound)
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
        if (adj.unmatched > 0) lim.add("${adj.unmatched} resultado(s) com clube que não achei na classificação lida")
        if (adj.roundGap > 0) lim.add("${adj.roundGap} resultado(s) lido(s) são de jornada que a classificação guardada ainda não alcança (J${adj.maxSkippedRound}): leia a Classificação de novo para somar")
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
    class Radar(val rival: String, val games: List<RadarGame>, val homeAvg: Pair<Double, Double>?, val awayAvg: Pair<Double, Double>?, val reference: String?, val campaign: League.Row? = null)

    /** Até 5 partidas CONFIRMADAS (lidas) do rival na mesma competição; a amostra é a que existe (3 jogos = 3). Nada de formação/estilo deduzido. */
    /** Forma (V/E/D, mais antigo primeiro, até 5) de [team] nos resultados lidos: só rodadas SEGUIDAS até a mais recente (rodada faltando = não adivinha o que veio antes). */
    fun formOf(all: List<Stored>, team: String?): String {
        if (team.isNullOrBlank()) return ""
        val mine = all.filter { same(it.home, team) || same(it.away, team) }.distinctBy { it.round }.sortedBy { it.round }
        if (mine.isEmpty()) return ""
        val run = ArrayList<Stored>()
        for (g in mine.reversed()) { if (run.isEmpty() || run.last().round - g.round == 1) run.add(g) else break }
        return run.reversed().takeLast(5).joinToString("") { g ->
            val home = same(g.home, team); val gf = if (home) g.gh else g.ga; val ga = if (home) g.ga else g.gh
            if (gf > ga) "V" else if (gf < ga) "D" else "E"
        }
    }

    fun radar(all: List<Stored>, rival: String?, st: List<League.Row>, stAt: Long?): Radar? {
        if (rival.isNullOrBlank()) return null
        val camp = League.find(st, rival)?.takeIf { it.j != null && it.pts != null }
        val mine = all.filter { same(it.home, rival) || same(it.away, rival) }.sortedByDescending { it.round }.take(5)
        if (mine.isEmpty() && camp == null) return null
        val gs = mine.map { g ->
            val home = same(g.home, rival)
            val opp = if (home) g.away else g.home
            RadarGame(g.round, opp, home, if (home) g.gh else g.ga, if (home) g.ga else g.gh, League.find(st, opp)?.rank)
        }
        fun avg(l: List<RadarGame>) = if (l.isEmpty()) null else Pair(l.sumOf { it.gf }.toDouble() / l.size, l.sumOf { it.ga }.toDouble() / l.size)
        val ref = if (st.any { it.rank != null } && stAt != null) "posição na classificação lida em " + java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(stAt)) + " (a de hoje, não a da data do jogo)" else null
        return Radar(rival, gs, avg(gs.filter { it.home }), avg(gs.filter { !it.home }), ref, camp)
    }
}
