package com.osmaicoach.collector

import org.json.JSONObject
import kotlin.math.abs

/**
 * Central de batalha (24/11): lê a tela de resultados da batalha (duas equipes, jornada, PLACAR OFICIAL ACUMULADO do cabeçalho, linhas com jogos e pontos).
 * Regras: o agregado vem SÓ do cabeçalho (nunca é somado das linhas: 78×60 já inclui tudo); linhas são "contribuição da rodada", guardadas por nome (reler substitui,
 * não soma); minha equipe = ajuste "Minha equipe de batalha" (padrão uzMitaofc), esquerda ou direita, e a escolha é gravada em cada leitura (trocar a equipe vale
 * para as próximas e não reatribui o histórico). Dados por batalha (par de equipes) e por slot. Layout NÃO validado com print real.
 */
object Battle {
    const val DEFAULT_TEAM = "uzMitaofc"
    const val SETTING = "battle_team"

    data class Row(val side: Char, val name: String, val games: Int, val pts: Int)
    data class Parsed(val left: String, val right: String, val aggL: Int, val aggR: Int, val round: Int?, val rows: List<Row>)

    private val RX_AGG = Regex("^(\\d{1,3})\\s*[-–:xX×]\\s*(\\d{1,3})$")
    private val RX_ONE = Regex("^(.+?)\\s+(\\d{1,3})\\s*[-–:xX×]\\s*(\\d{1,3})\\s+(.+)$")
    private val RX_ROUND = Regex("(?:jornada|rodada)\\s*(\\d{1,2})")

    fun parse(o: OcrResult): Parsed? {
        val t = Txt.norm(o.fullText)
        if (t.contains("gm-gs") || t.contains("primeira parte") || t.contains("homem do jogo")) return null
        val round = o.lines.filter { it.yc < 0.45f }.mapNotNull { RX_ROUND.find(Txt.norm(it.text))?.groupValues?.get(1)?.toIntOrNull() }.distinct().let { if (it.size == 1) it[0] else null }
        var left: String? = null; var right: String? = null; var aggL = 0; var aggR = 0; var aggY = 0f
        for (ln in o.lines.filter { it.yc in 0.05f..0.45f }) {
            val one = RX_ONE.find(ln.text.trim())
            if (one != null && ln.r - ln.l > 0.30f) { left = one.groupValues[1].trim(); right = one.groupValues[4].trim(); aggL = one.groupValues[2].toInt(); aggR = one.groupValues[3].toInt(); aggY = ln.yc; break }
            val sc = RX_AGG.find(ln.text.trim()) ?: continue
            if (ln.xc !in 0.30f..0.70f) continue
            val same = o.lines.filter { it !== ln && abs(it.yc - ln.yc) <= 0.05f && Txt.letters(it.text) >= 2 && RX_ROUND.find(Txt.norm(it.text)) == null }
            val lf = same.filter { it.r <= ln.l + 0.03f }.maxByOrNull { it.r } ?: continue
            val rt = same.filter { it.l >= ln.r - 0.03f }.minByOrNull { it.l } ?: continue
            left = lf.text.trim(); right = rt.text.trim(); aggL = sc.groupValues[1].toInt(); aggR = sc.groupValues[2].toInt(); aggY = ln.yc; break
        }
        if (left == null || right == null || Txt.key(left) == Txt.key(right)) return null
        // linhas: nome + (jogos, pontos) do mesmo lado
        val rows = ArrayList<Row>()
        for (ln in o.lines) {
            if (ln.yc <= aggY + 0.04f || Txt.letters(ln.text) < 2) continue
            if (RX_ROUND.find(Txt.norm(ln.text)) != null) continue
            val leftSide = ln.xc < 0.5f
            val ints = o.tokens.filter { abs(it.yc - ln.yc) <= 0.022f && it.l >= ln.r - 0.005f && (if (leftSide) it.xc < 0.52f else true) && it.text.all { c -> c.isDigit() } }
                .sortedBy { it.xc }.mapNotNull { it.text.toIntOrNull() }
            // a linha pode ser "Nome 5 12" já inteira
            val inline = ln.text.trim().split(Regex("\\s+"))
            val trailing = inline.reversed().takeWhile { it.all { c -> c.isDigit() } }.reversed().mapNotNull { it.toIntOrNull() }
            val nums = if (trailing.size >= 2) trailing.takeLast(2) else ints.take(2)
            if (nums.size < 2 || nums[0] > 40 || nums[1] > 999) continue
            val name = (if (trailing.size >= 2) inline.dropLast(trailing.size).joinToString(" ") else ln.text).trim()
            if (Txt.letters(name) < 2  ) continue
            rows.add(Row(if (leftSide) 'L' else 'R', name, nums[0], nums[1]))
        }
        return Parsed(left, right, aggL, aggR, round, rows.distinctBy { it.side.toString() + Txt.key(it.name) })
    }

    /** Qual lado sou eu. Igualdade de chave vence; senão similaridade ≥0,85; se os dois lados qualificam igual, é ambíguo (null: não adivinha). */
    fun mySide(myTeam: String, left: String, right: String): Char? {
        val m = Txt.key(myTeam)
        if (m.length < 3) return null
        fun score(n: String): Double { val k = Txt.key(n); return if (k == m) 1.0 else Txt.sim(k, m).takeIf { it >= 0.85 } ?: 0.0 }
        val l = score(left); val r = score(right)
        return when { l == 0.0 && r == 0.0 -> null; l > r -> 'L'; r > l -> 'R'; else -> null }
    }

    fun battleId(left: String, right: String): String = listOf(Txt.key(left), Txt.key(right)).sorted().joinToString("~")

    /** Guarda uma leitura. Agregado: só do cabeçalho; mesma jornada com valor diferente fica com o mais confirmado; agregado nunca diminui entre jornadas. */
    fun store(old: JSONObject?, p: Parsed, round: Int?, myTeam: String, now: Long): JSONObject {
        val root = old ?: JSONObject()
        val id = battleId(p.left, p.right)
        val b = root.optJSONObject(id) ?: JSONObject().put("first", now).also { root.put(id, it) }
        // equipes em ordem estável da PRIMEIRA leitura: se a tela inverteu os lados, inverte os números junto
        val flipped = b.has("A") && Txt.key(b.optString("A")) != Txt.key(p.left)
        if (!b.has("A")) b.put("A", p.left).put("B", p.right)
        val aggA = if (flipped) p.aggR else p.aggL; val aggB = if (flipped) p.aggL else p.aggR
        val mineSide = mySide(myTeam, p.left, p.right)
        val mineAB: String? = mineSide?.let { s -> if ((s == 'L') != flipped) "A" else "B" }
        if (mineAB != null && !b.has("mine")) b.put("mine", mineAB).put("mineName", myTeam)       // vale para o histórico desta batalha: trocar o ajuste depois não reatribui
        val aggs = b.optJSONObject("agg") ?: JSONObject().also { b.put("agg", it) }
        if (round != null) {
            val cur = aggs.optJSONObject(round.toString())
            val prevOk = aggs.keys().asSequence().mapNotNull { it.toIntOrNull()?.let { r -> Pair(r, aggs.getJSONObject(it)) } }
                .filter { it.first < round }.all { aggA >= it.second.optInt("a") && aggB >= it.second.optInt("b") }
            val laterOk = aggs.keys().asSequence().mapNotNull { it.toIntOrNull()?.let { r -> Pair(r, aggs.getJSONObject(it)) } }
                .filter { it.first > round }.all { aggA <= it.second.optInt("a") && aggB <= it.second.optInt("b") }
            if (prevOk && laterOk) {
                if (cur == null) aggs.put(round.toString(), JSONObject().put("a", aggA).put("b", aggB).put("n", 1).put("at", now))
                else if (cur.optInt("a") == aggA && cur.optInt("b") == aggB) cur.put("n", cur.optInt("n", 1) + 1).put("at", now)
                // conflito na mesma jornada: mantém o já confirmado
            }
            val rr = b.optJSONObject("rows") ?: JSONObject().also { b.put("rows", it) }
            val cr = rr.optJSONObject(round.toString()) ?: JSONObject().also { rr.put(round.toString(), it) }
            for (r in p.rows) {
                val side = if ((r.side == 'L') != flipped) "A" else "B"
                cr.put(side + "|" + Txt.key(r.name), JSONObject().put("name", r.name).put("g", r.games).put("p", r.pts))   // substitui: reler não soma
            }
            b.put("round", maxOf(b.optInt("round", 0), round))
        }
        b.put("at", now)
        return root
    }

    class State(
        val teamA: String, val teamB: String, val mine: String?, val round: Int?, val aggA: Int?, val aggB: Int?, val aggRound: Int?, val aggAt: Long,
        val contribA: Int?, val contribB: Int?, val contribSource: String?, val myRow: Row?, val pendingKnown: Boolean
    )

    fun state(j: JSONObject?, battleKey: String?, myNick: String): State? {
        if (j == null) return null
        val id = battleKey?.takeIf { j.has(it) } ?: j.keys().asSequence().maxByOrNull { j.getJSONObject(it).optLong("at") } ?: return null
        val b = j.optJSONObject(id) ?: return null
        val aggs = b.optJSONObject("agg")
        val last = aggs?.keys()?.asSequence()?.mapNotNull { it.toIntOrNull() }?.maxOrNull()
        val la = last?.let { aggs.getJSONObject(it.toString()) }
        val prev = last?.let { aggs.optJSONObject((it - 1).toString()) }
        var ca: Int? = null; var cb: Int? = null; var src: String? = null
        if (la != null && prev != null) { ca = la.optInt("a") - prev.optInt("a"); cb = la.optInt("b") - prev.optInt("b"); src = "diferença do placar oficial entre as jornadas" }
        else if (last != null) {
            val cr = b.optJSONObject("rows")?.optJSONObject(last.toString())
            if (cr != null && cr.length() > 0) {
                var sa = 0; var sb = 0
                for (k in cr.keys()) { val o = cr.getJSONObject(k); if (k.startsWith("A|")) sa += o.optInt("p") else sb += o.optInt("p") }
                ca = sa; cb = sb; src = "soma das linhas lidas (podem estar incompletas)"
            }
        }
        val mine = b.optString("mine").takeIf { it == "A" || it == "B" }
        var myRow: Row? = null
        if (last != null && mine != null) {
            val cr = b.optJSONObject("rows")?.optJSONObject(last.toString())
            if (cr != null) for (k in cr.keys()) if (k.startsWith(mine + "|") && (Txt.key(cr.getJSONObject(k).optString("name")) == Txt.key(myNick) || Txt.sim(Txt.key(cr.getJSONObject(k).optString("name")), Txt.key(myNick)) >= 0.85)) {
                val o = cr.getJSONObject(k); myRow = Row(mine[0], o.optString("name"), o.optInt("g"), o.optInt("p"))
            }
        }
        return State(b.optString("A"), b.optString("B"), mine, b.optInt("round", 0).takeIf { it > 0 }, la?.optInt("a"), la?.optInt("b"), last, la?.optLong("at") ?: 0L, ca, cb, src, myRow, false)
    }

    /**
     * "Empate basta" / "precisa vencer" / "depende": SÓ com os pontos que ainda podem ser feitos por cada lado (conhecidos) e o agregado oficial.
     * Sem esses dados devolve null (o app mostra só o placar real; não inventa regra de pontuação).
     */
    fun outlook(myAgg: Int, oppAgg: Int, myRemainingMax: Int?, oppRemainingMax: Int?, myDrawPts: Int?): String? {
        if (myRemainingMax == null || oppRemainingMax == null) return null
        if (myAgg > oppAgg + oppRemainingMax) return "Garantido: o adversário não alcança mais"
        if (myAgg + myRemainingMax < oppAgg) return "Perdido: nem somando tudo o que falta"
        if (myDrawPts != null && myAgg + myDrawPts > oppAgg + oppRemainingMax) return "empate basta"
        if (myAgg + myRemainingMax > oppAgg) return if (myAgg <= oppAgg) "precisa vencer" else "depende dos outros jogos"
        return "depende dos outros jogos"
    }
}
