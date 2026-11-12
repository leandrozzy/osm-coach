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
        val value: Double? = null, val players: Int? = null, val avg: Double? = null
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

    private val RX_GOALS = Regex("^(\\d{1,3})\\s*[-–]\\s*(\\d{1,3})$")

    /** Linhas da tela (rolagem parcial): cada uma começa pelo número da posição à esquerda. */
    fun read(o: OcrResult): Pair<String, List<Row>>? {
        val standings = isStandings(o.fullText)
        val values = !standings && isValues(o.fullText)
        if (!standings && !values) return null
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
        o.optDouble("value", Double.NaN).takeIf { !it.isNaN() }, o.optInt("players", -1).takeIf { it >= 0 }, o.optDouble("avg", Double.NaN).takeIf { !it.isNaN() }
    )

    /** Junta as linhas lidas ao que já estava guardado (a leitura nova de um clube substitui a velha). */
    fun merge(old: JSONObject?, kind: String, rows: List<Row>, now: Long): JSONObject {
        val j = old ?: JSONObject()
        val m = j.optJSONObject(kind) ?: JSONObject()
        for (r in rows) m.put(key(r.club), toJson(r))
        j.put(kind, m).put(kind + "At", now)
        return j
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
