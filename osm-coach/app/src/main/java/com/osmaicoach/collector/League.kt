package com.osmaicoach.collector

import org.json.JSONObject
import kotlin.math.abs

/**
 * Tabelas da liga inteira (menu → Classificação e Valor do plantel): pontos e gols de cada clube e valor/tamanho do elenco.
 * Serve para (1) comparar o seu elenco com o da liga e (2) calibrar a tática com o que o rival realmente marca e sofre.
 * Guardadas no plano "league" do slot: { st: {clube: linha}, va: {clube: linha}, stAt, vaAt }. Cada rolagem completa a anterior.
 */
object League {
    data class Row(
        val club: String, val nick: String?, val rank: Int?,
        val j: Int? = null, val v: Int? = null, val e: Int? = null, val d: Int? = null, val pts: Int? = null, val gf: Int? = null, val ga: Int? = null,
        val value: Double? = null, val players: Int? = null, val avg: Double? = null, val at: Long? = null
    )

    fun isStandings(fullText: String): Boolean {
        val t = Txt.norm(fullText)
        return t.contains("gm-gs") && t.contains("clube") && t.contains("pts") && t.contains("jog")
    }

    fun isValues(fullText: String): Boolean {
        val t = Txt.norm(fullText)
        return t.contains("valor medio do jogador") && t.contains("clube") && t.contains("jogadores")
    }

    private fun intOf(s: String): Int? = s.trim().takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } }?.toIntOrNull()

    private val RX_GOALS = Regex("^(\\d{1,3})\\s*[-–—]\\s*(\\d{1,3})$")

    /** Linhas da tela (rolagem parcial): cada uma começa pelo número da posição à esquerda. */
    fun read(o: OcrResult, myTeam: String? = null): Pair<String, List<Row>>? {
        val standings = isStandings(o.fullText)
        val values = !standings && isValues(o.fullText)
        if (!standings && !values) return null
        if (standings) return readStandings(o, myTeam)?.let { Pair("st", it) }
        val ranks = o.tokens.filter { it.xc < 0.06f && it.yc > 0.25f && (intOf(it.text) ?: 0) in 1..40 }.sortedBy { it.yc }
        val out = ArrayList<Row>()
        for (r in ranks) {
            val rank = intOf(r.text) ?: continue
            val names = o.lines.filter { it.l in 0.04f..0.40f && abs(it.yc - r.yc) <= 0.055f && Txt.letters(it.text) >= 2 }.sortedBy { it.yc }
            val club = names.firstOrNull()?.text?.trim()?.takeIf { it.length >= 3 } ?: continue
            val nick = names.getOrNull(1)?.text?.trim()?.takeIf { it.length >= 3 }
            val band = o.tokens.filter { abs(it.yc - r.yc) <= 0.05f && it.xc > 0.40f }.sortedBy { it.xc }
            if (standings) {
                val ints = band.filter { it.xc > 0.72f }.mapNotNull { intOf(it.text) }
                val gl = band.firstNotNullOfOrNull { RX_GOALS.find(it.text.trim()) }
                if (ints.size < 5 || gl == null) continue
                val (j, v, e, d, pts) = ints
                if (j != v + e + d && j > 0) continue
                out.add(Row(club, nick, rank, j, v, e, d, pts, gl.groupValues[1].toInt(), gl.groupValues[2].toInt()))
            } else {
                val money = band.mapNotNull { t -> Money.extract(t.text)?.let { m -> Pair(t, Money.parse(m)) } }
                val val1 = money.firstOrNull { it.first.xc < 0.7f }?.second
                val avg = money.lastOrNull { it.first.xc > 0.8f }?.second
                val players = band.filter { it.xc in 0.55f..0.8f }.firstNotNullOfOrNull { intOf(it.text) }
                if (val1 == null || players == null) continue
                out.add(Row(club, nick, rank, value = val1, players = players, avg = avg))
            }
        }
        return if (out.isEmpty()) null else Pair(if (standings) "st" else "va", out)
    }


    /**
     * Classificação (layout real conferido em 25/11/26 com prints de 20 clubes): cada clube ocupa ~0,099 da altura; o NOME (branco) fica em cima e o apelido do treinador (cinza)
     * logo abaixo; J V E D Pts ficam à direita na altura do meio, e Gm-Gs no fim. A posição é um número à esquerda (nas 3 primeiras fica sobre a taça dourada e o OCR
     * costuma falhar): quando falta, vale a ordem na tela (posição anterior + linhas puladas). Números conferidos entre si (J = V+E+D, Pts = 3V+E): erro de OCR de um
     * dígito é corrigido pelo que fecha; o que não fecha é descartado (nunca gravado errado).
     */
    class RawRow(val name: OcrLine, val nick: OcrLine?, val rankTok: Int?, val ints: List<Int>, val gl: MatchResult?, val statsY: Float? = null)

    /** Numeral com 3+ traços ("Stuttgart III", "Hoffenheim lll") é o OCR somando o traço da borda da linha: nenhum clube do OSM tem III; vira II (o time reserva). */
    internal fun fixNumeral(n: String): String = Regex("^(.*\\S)\\s+([IlL1|!]{3,})$").find(n)?.let { it.groupValues[1] + " II" } ?: n

    /**
     * Linha com 3 ou 4 números (o OCR perdeu uma ou duas colunas do meio): J é o primeiro e Pts o último; V/E/D que faltam saem de J = V+E+D e Pts = 3V+E.
     * Só aceita quando há UMA única solução com números inteiros e não negativos (nunca chuta). Ex.: [21, 7, 10, 25] → J21 V7 E4 D10 Pts25.
     */
    internal fun reconstruct(ints: List<Int>): List<Int>? {
        if (ints.size !in 3..4) return null
        val j = ints.first(); val pts = ints.last(); val mid = ints.subList(1, ints.size - 1)
        val sols = LinkedHashSet<List<Int>>()
        fun add(v: Int, e: Int, d: Int) { if (v >= 0 && e >= 0 && d >= 0 && v + e + d == j && 3 * v + e == pts) sols.add(listOf(j, v, e, d, pts)) }
        if (mid.size == 2) {
            val (a, b) = mid
            add(a, b, j - a - b); add(a, j - a - b, b); add(j - a - b, a, b)       // falta D, falta E, falta V
        } else {
            val x = mid[0]
            add(x, pts - 3 * x, j - x - (pts - 3 * x))                              // x é V
            if ((pts - x) % 3 == 0) { val v = (pts - x) / 3; add(v, x, j - v - x) } // x é E
            if ((pts - (j - x)) % 2 == 0) { val v = (pts - (j - x)) / 2; add(v, j - x - v, x) } // x é D
        }
        return sols.singleOrNull()
    }

    fun repairStats(ints: List<Int>): List<Int>? {
        if (ints.size in 3..4) return reconstruct(ints)
        if (ints.size < 5) return null
        val a = if (ints.size > 5) ints.takeLast(5) else ints
        val (j, v, e, d, p) = a
        if (j == v + e + d) return listOf(j, v, e, d, 3 * v + e)          // pontos conferidos pelas vitórias/empates
        if (p == 3 * v + e && j - v - e >= 0) return listOf(j, v, e, j - v - e, p)
        if (p == 3 * v + e) return listOf(v + e + d, v, e, d, p)
        return null
    }

    fun readStandings(o: OcrResult, myTeam: String? = null): List<Row>? {
        // só linhas INTEIRAS: a primeira/última do scroll aparecem cortadas na borda (prints de 25/11: a 8ª linha de cada rolagem) e o OCR lê um pedaço das letras ("Cantoe", "Dartini Tirar")
        val cand = o.lines.filter { it.l in 0.04f..0.40f && it.yc in 0.285f..0.955f && Txt.letters(it.text) >= 2 }.sortedBy { it.yc }
        val heads = ArrayList<OcrLine>(); val nicks = HashMap<OcrLine, OcrLine>()
        for (ln in cand) {
            val h = heads.lastOrNull()
            if (h != null && ln.yc - h.yc <= 0.062f) { if (!nicks.containsKey(h)) nicks[h] = ln } else heads.add(ln)
        }
        if (heads.isEmpty()) return null
        val raws = heads.map { h ->
            val win = o.tokens.filter { abs(it.yc - h.yc) <= 0.045f }
            val rk = win.filter { it.xc < 0.07f }.mapNotNull { t -> intOf(t.text)?.takeIf { it in 1..40 }?.let { Pair(t, it) } }.minByOrNull { abs(it.first.yc - h.yc) }?.second
            val ints = win.filter { it.xc > 0.72f && it.xc < 0.94f }.sortedBy { it.xc }.mapNotNull { intOf(it.text) }
            val gl = win.filter { it.xc > 0.90f }.firstNotNullOfOrNull { RX_GOALS.find(it.text.trim()) }
            RawRow(h, nicks[h], rk, ints, gl, win.filter { it.xc > 0.78f && intOf(it.text) != null }.map { it.yc }.sorted().let { l -> if (l.isEmpty()) null else l[l.size / 2] })
        }
        // posição de cada linha: o número lido quando bate com a sequência; senão a ordem na tela (anterior + linhas puladas pela altura)
        val gaps = heads.zipWithNext { a, b -> b.yc - a.yc }.filter { it > 0.05f && it < 0.15f }.sorted()
        val spacing = gaps.getOrNull(gaps.size / 2) ?: 0.099f
        val ranks = arrayOfNulls<Int>(raws.size)
        // consenso: cada número lido vota na "posição da 1ª linha da tela" (número − linhas puladas); o OCR erra um dígito aqui e ali (prints 25/11: o "9" lido como "1" punha o Meppen em 1º e deslocava as outras linhas)
        val cum = IntArray(raws.size)
        for (i in 1 until raws.size) cum[i] = cum[i - 1] + maxOf(1, Math.round((heads[i].yc - heads[i - 1].yc) / spacing))
        val votes = HashMap<Int, Int>()
        for (i in raws.indices) raws[i].rankTok?.let { t -> votes.merge(t - cum[i], 1, Int::plus) }
        val best = votes.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key }).firstOrNull()
        val consensus = best?.takeIf { it.value >= 2 || raws.count { r -> r.rankTok != null } == 1 || votes.size == 1 }?.key
        var prev: Int? = null
        if (consensus != null) for (i in raws.indices) ranks[i] = (consensus + cum[i]).takeIf { it in 1..40 }
        else for (i in raws.indices) {
            val tok = raws[i].rankTok
            val expected = prev?.let { p -> p + maxOf(1, Math.round((heads[i].yc - heads[i - 1].yc) / spacing)) }
            ranks[i] = when {
                tok != null && (prev == null || tok > prev) && (expected == null || abs(tok - expected) <= 1) -> tok
                expected != null -> expected
                else -> null
            }
            prev = ranks[i] ?: prev
        }
        // primeiras linhas sem posição: contam para trás a partir da primeira conhecida
        val first = ranks.indexOfFirst { it != null }
        if (first > 0) for (i in first - 1 downTo 0) ranks[i] = (ranks[i + 1] ?: break) - 1
        val out = ArrayList<Row>()
        // Batalha de grupos / início de liga: ninguém jogou ainda, a tela só mostra zeros (o OCR lê [0,0], [0,0,0] ou nada). Tabela em que a maioria das linhas é só zero = tudo zero.
        val zeroish = raws.count { r -> r.ints.all { it == 0 } }
        val someZeros = raws.count { r -> r.ints.isNotEmpty() && r.ints.all { it == 0 } }
        val allZero = raws.size >= 4 && zeroish * 10 >= raws.size * 6 && someZeros >= 2
        for (i in raws.indices) {
            val r = raws[i]; val rank = ranks[i]?.takeIf { it in 1..40 } ?: continue
            // a linha do MEU clube vem destacada (amarelo) e o OCR perde o nome do clube: sobra só o apelido ("leandrozzy") como 1ª linha = é o meu clube
            val nickOnly = r.statsY != null && r.name.yc - r.statsY >= 0.004f
            val mineNick = Txt.sim(Txt.key(r.name.text), Txt.key(MY_NICK)) >= 0.75
            val club = when {
                mineNick && myTeam != null -> myTeam
                nickOnly -> { runCatching { Diag.log("Classificação: linha sem nome de clube (só o apelido '${r.name.text.trim()}') descartada, posição $rank") }; continue }   // apelido de treinador nunca vira clube
                else -> fixNumeral(r.name.text.trim())
            }.takeIf { it.length >= 3 } ?: continue
            val st = if (allZero && r.ints.all { it == 0 }) listOf(0, 0, 0, 0, 0) else repairStats(r.ints)
            if (st == null) { runCatching { Diag.log("Classificação: linha de '$club' descartada (números lidos: ${r.ints}, posição $rank)") }; continue }
            val (j, v, e, d, pts) = st
            out.add(Row(club, r.nick?.text?.trim()?.takeIf { it.length >= 3 }, rank, j, v, e, d, pts, r.gl?.groupValues?.get(1)?.toIntOrNull(), r.gl?.groupValues?.get(2)?.toIntOrNull()))
        }
        return out.ifEmpty { null }
    }

    fun key(club: String): String = Txt.key(club)

    private fun toJson(r: Row): JSONObject = JSONObject().put("club", r.club).put("nick", r.nick ?: JSONObject.NULL).put("rank", r.rank ?: JSONObject.NULL)
        .also { o ->
            r.j?.let { o.put("j", it) }; r.v?.let { o.put("v", it) }; r.e?.let { o.put("e", it) }; r.d?.let { o.put("d", it) }
            r.pts?.let { o.put("pts", it) }; r.gf?.let { o.put("gf", it) }; r.ga?.let { o.put("ga", it) }
            r.value?.let { o.put("value", it) }; r.players?.let { o.put("players", it) }; r.avg?.let { o.put("avg", it) }
        }

    fun fromJson(o: JSONObject): Row = Row(
        o.optString("club"), o.optString("nick").takeIf { it.isNotBlank() && it != "null" }, if (o.isNull("rank")) null else o.optInt("rank"),
        o.optInt("j", -1).takeIf { it >= 0 }, o.optInt("v", -1).takeIf { it >= 0 }, o.optInt("e", -1).takeIf { it >= 0 }, o.optInt("d", -1).takeIf { it >= 0 },
        o.optInt("pts", -1).takeIf { it >= 0 }, o.optInt("gf", -1).takeIf { it >= 0 }, o.optInt("ga", -1).takeIf { it >= 0 },
        o.optDouble("value", Double.NaN).takeIf { !it.isNaN() }, o.optInt("players", -1).takeIf { it >= 0 }, o.optDouble("avg", Double.NaN).takeIf { !it.isNaN() }, o.optLong("at", 0L).takeIf { it > 0L }
    )

    /** Junta as linhas lidas ao que já estava guardado (a leitura nova de um clube substitui a velha). */
    fun merge(old: JSONObject?, kind: String, rows: List<Row>, now: Long): JSONObject {
        val j = old ?: JSONObject()
        val m = j.optJSONObject(kind) ?: JSONObject()
        val prevAt = j.optLong(kind + "At", 0L)
        // posição que acabou de ser lida de novo por OUTRO nome: a linha antiga daquela posição (leitura cortada/errada como "Cantoe", ou o mesmo clube com outra grafia) sai
        if (kind == "st") for (r in rows) { val rk = r.rank ?: continue; for (k in m.keys().asSequence().toList()) { val o = m.optJSONObject(k) ?: continue; if (!o.isNull("rank") && o.optInt("rank") == rk && k != key(r.club)) m.remove(k) } }
        for (r in rows) m.put(key(r.club), toJson(r).put("at", now))
        j.put(kind, m).put(kind + "At", now)
        if (kind == "st") snapshotPass(j, m, rows, prevAt, now)
        return j
    }

    /**
     * Fotografia COMPLETA da classificação, para comparar duas leituras (quem passou, quem encostou). Uma "passada" são as rolagens seguidas (até 3 min entre elas);
     * a foto só nasce quando a passada cobriu todos os clubes conhecidos, nunca de uma tabela meio velha meio nova. No máx. 6 fotos; igual à anterior não repete.
     */
    private fun snapshotPass(j: JSONObject, m: JSONObject, rows: List<Row>, prevAt: Long, now: Long) {
        val pass = if (now - prevAt > 180_000L) JSONObject() else (j.optJSONObject("stPass") ?: JSONObject())
        for (r in rows) pass.put(key(r.club), 1)
        j.put("stPass", pass)
        val total = m.length()
        if (total < 6 || pass.length() < total) return
        val snap = JSONObject().put("at", now)
        val rr = JSONObject()
        var sumJ = 0
        for (k in m.keys()) { val o = m.optJSONObject(k) ?: continue; rr.put(k, org.json.JSONArray().put(o.optInt("rank", -1)).put(o.optInt("j", -1)).put(o.optInt("pts", -1)).put(o.optString("club"))); sumJ += o.optInt("j", 0) }
        snap.put("r", rr).put("sumJ", sumJ)
        val arr = j.optJSONArray("snaps") ?: org.json.JSONArray()
        if (arr.length() > 0 && arr.getJSONObject(arr.length() - 1).optInt("sumJ", -1) == sumJ && arr.getJSONObject(arr.length() - 1).optJSONObject("r")?.toString() == rr.toString()) return
        arr.put(snap)
        val keep = org.json.JSONArray()
        for (i in maxOf(0, arr.length() - 6) until arr.length()) keep.put(arr.get(i))
        j.put("snaps", keep)
    }

    /** Junta variações do mesmo clube (o OCR gera "Sonnenhof Grossaspach" e "Sonnenhof Großaspach"): fica a linha mais recente. */
    fun dedupe(rows: List<Row>): List<Row> {
        val out = ArrayList<Row>()
        for (r in rows.sortedByDescending { it.at ?: 0L }) {
            val k = key(r.club)
            if (out.none { o -> key(o.club) == k || Txt.sim(key(o.club), k) >= 0.88 }) out.add(r)
        }
        return out
    }

    fun table(j: JSONObject?, kind: String): List<Row> {
        val m = j?.optJSONObject(kind) ?: return emptyList()
        return m.keys().asSequence().mapNotNull { m.optJSONObject(it)?.let { o -> fromJson(o) } }.toList()
    }

    /** Linha do clube pelo nome (tolera erro de OCR). */
    fun find(rows: List<Row>, club: String?): Row? {
        val k = club?.let { key(it) }?.takeIf { it.length >= 3 } ?: return null
        return rows.firstOrNull { key(it.club) == k } ?: rows.maxByOrNull { Txt.sim(key(it.club), k) }?.takeIf { Txt.sim(key(it.club), k) >= 0.85 }
    }

    /** Gols por jogo que o clube marca e sofre, e a média da liga (por time). Null se a tabela é curta demais. */
    data class Rates(val gf: Double, val ga: Double, val games: Int)

    fun rates(r: Row?): Rates? {
        r ?: return null
        val j = r.j ?: return null
        if (j < 8 || r.gf == null || r.ga == null) return null
        return Rates(r.gf / j.toDouble(), r.ga / j.toDouble(), j)
    }

    fun leagueAvgGoals(rows: List<Row>): Double? {
        val ok = rows.filter { (it.j ?: 0) >= 8 && it.gf != null }
        if (ok.size < 6) return null
        return ok.sumOf { it.gf!! }.toDouble() / ok.sumOf { it.j!! }
    }

    private fun median(l: List<Double>): Double? = l.sorted().let { if (it.isEmpty()) null else if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2.0 }

    /** Seu elenco contra a liga: posição por valor, tamanho e valor médio comparados com a mediana e o líder. */
    data class SquadCompare(
        val rank: Int, val of: Int, val value: Double, val players: Int?, val avg: Double?,
        val medianValue: Double?, val medianPlayers: Double?, val medianAvg: Double?, val topValue: Double?, val topPlayers: Int?, val topAvg: Double?
    )

    fun squad(values: List<Row>, me: Row?): SquadCompare? {
        me ?: return null
        val v = me.value ?: return null
        if (values.size < 6) return null
        val ordered = values.filter { it.value != null }.sortedByDescending { it.value }
        val rank = ordered.indexOfFirst { key(it.club) == key(me.club) }.let { if (it < 0) return null else it + 1 }
        val top = ordered.first()
        return SquadCompare(
            rank, ordered.size, v, me.players, me.avg,
            median(ordered.mapNotNull { it.value }), median(ordered.mapNotNull { it.players?.toDouble() }), median(ordered.mapNotNull { it.avg }),
            top.value, top.players, top.avg
        )
    }
}
