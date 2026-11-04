package com.osmaicoach.collector

import org.json.JSONObject

/**
 * O que os SEUS jogos reais contra humanos (nos 4 slots) mostram: pontos por jogo por formação, estilo e mentalidade,
 * já DESCONTADO o efeito da diferença de força (jogar bem contra um time mais fraco não prova que a tática é boa).
 * Vira um peso forte na nota da simulação e uma lista de "evitar" (formação/estilo que perdeu mais do que a força explica).
 * Diagnóstico de 04/11: 67 jogos contra humanos, 18V 15E 34D; 4-2-3-1, 4-5-1 e Contra-ataque ficaram ~0,5-0,7 pts/jogo
 * abaixo do esperado pela força; 4-3-3 (A/B) e "Jogar pelas alas" acima.
 */
object HumanPrior {
    /** n jogos, pts/jogo bruto, e o resíduo (pts/jogo acima do esperado pela diferença de força, já encolhido). */
    data class G(val n: Int, val ppg: Double, val adj: Double = 0.0)
    class Prior(
        val ref: Double, val n: Int, val bucket: String,
        val form: Map<String, G>, val style: Map<String, G>, val ment: Map<String, G>,
        val avoidForm: Set<String> = emptySet(), val avoidStyle: Set<String> = emptySet()
    )

    private const val SHRINK = 5.0
    private const val MIN_GAMES = 3
    private const val AVOID_MIN_GAMES = 4
    private const val AVOID_ADJ = -0.30
    private const val WEIGHT = 1.0
    private const val CAP = 1.2

    private fun band(m: Int) = if (m >= 60) "ofensiva" else if (m >= 45) "média" else "defensiva"

    private fun pts(r: String) = if (r == "V") 3.0 else if (r == "E") 1.0 else 0.0

    private fun diffOf(j: JSONObject): Int? {
        val m = j.optInt("myStrength", 0); val r = j.optInt("rivalStrength", 0)
        return if (m > 0 && r > 0) m - r else null
    }

    /** Reta pts = a + b * diferença de força, ajustada nos jogos reais (b limitado a valores plausíveis). */
    private fun fit(rows: List<JSONObject>): Pair<Double, Double> {
        val pts = rows.mapNotNull { j -> diffOf(j)?.let { Pair(it.toDouble(), pts(j.optString("result"))) } }
        val ref = rows.sumOf { pts(it.optString("result")) } / rows.size
        if (pts.size < 8) return Pair(ref, 0.0)
        val mx = pts.sumOf { it.first } / pts.size
        val my = pts.sumOf { it.second } / pts.size
        val vx = pts.sumOf { (it.first - mx) * (it.first - mx) }
        if (vx < 1e-6) return Pair(my, 0.0)
        val b = (pts.sumOf { (it.first - mx) * (it.second - my) } / vx).coerceIn(0.02, 0.15)
        return Pair(my - b * mx, b)
    }

    fun build(logs: List<JSONObject>, diff: Int?): Prior? {
        val all = logs.filter { it.optBoolean("human", false) && it.optString("result") in listOf("V", "E", "D") }
        if (all.size < 6) return null
        val (a, b) = fit(all)
        val ref = all.sumOf { pts(it.optString("result")) } / all.size
        fun groups(key: (JSONObject) -> String): Map<String, G> = all.groupBy(key).mapValues { (_, v) ->
            val raw = v.sumOf { pts(it.optString("result")) } / v.size
            val res = v.sumOf { j -> pts(j.optString("result")) - (a + b * (diffOf(j) ?: 0)) } / v.size
            G(v.size, raw, res * v.size / (v.size + SHRINK))
        }
        val form = groups { Formations.base(it.optString("formation")) }
        val style = groups { Osm.style(it.optString("playStyle")) ?: it.optString("playStyle") }
        val ment = groups { band(it.optInt("mentality", 50)) }
        return Prior(
            ref, all.size, "todas as faixas, ajustado pela diferença de força", form, style, ment,
            avoidForm = form.filter { it.value.n >= AVOID_MIN_GAMES && it.value.adj <= AVOID_ADJ }.keys,
            avoidStyle = style.filter { it.value.n >= AVOID_MIN_GAMES && it.value.adj <= AVOID_ADJ }.keys
        )
    }

    private fun adj(g: G?): Double = if (g == null || g.n < MIN_GAMES) 0.0 else g.adj

    /** Pontos esperados a somar (ou tirar) da nota da tática. */
    fun bonus(p: Prior, formation: String, style: String, mentality: Int): Double {
        val d = adj(p.form[Formations.base(formation)]) + adj(p.style[Osm.style(style) ?: style]) + 0.5 * adj(p.ment[band(mentality)])
        return (WEIGHT * d).coerceIn(-CAP, CAP)
    }

    fun avoidsFormation(p: Prior?, formation: String) = p != null && Formations.base(formation) in p.avoidForm
    fun avoidsStyle(p: Prior?, style: String) = p != null && (Osm.style(style) ?: style) in p.avoidStyle

    /** Frase curta com os números reais que pesaram na escolha. */
    fun note(p: Prior, formation: String, style: String, mentality: Int): String? {
        fun f(g: G?) = g?.takeIf { it.n >= MIN_GAMES }?.let { "${"%.1f".format(it.ppg).replace('.', ',')} pts/jogo em ${it.n} (${"%+.1f".format(it.adj).replace('.', ',')} vs o esperado pela força)" }
        val parts = listOfNotNull(
            f(p.form[Formations.base(formation)])?.let { "${Formations.base(formation)}: $it" },
            f(p.style[Osm.style(style) ?: style])?.let { "${Osm.style(style) ?: style}: $it" }
        )
        if (parts.isEmpty()) return null
        return "Seus jogos reais contra humanos (${p.n} jogos, média ${"%.1f".format(p.ref).replace('.', ',')} pts/jogo) — " + parts.joinToString("; ") + "."
    }

    /** Texto para a IA: o que evitar e o que repetir, com os números já descontada a força. */
    fun promptText(p: Prior): String {
        fun line(m: Map<String, G>, title: String) = m.filter { it.value.n >= MIN_GAMES }.entries.sortedByDescending { it.value.adj }
            .joinToString("; ") { "${it.key}: ${"%.1f".format(it.value.ppg)} pts/jogo em ${it.value.n} (${"%+.2f".format(it.value.adj)} vs esperado pela força)" }
            .takeIf { it.isNotBlank() }?.let { "$title — $it" }
        return listOfNotNull(
            "Seus jogos reais contra humanos (${p.n} jogos, média ${"%.1f".format(p.ref).replace('.', ',')} pts/jogo). Os números JÁ descontam a diferença de força:",
            line(p.form, "formação"), line(p.style, "estilo"),
            if (p.avoidForm.isNotEmpty() || p.avoidStyle.isNotEmpty())
                "EVITE (perderam bem mais do que a força explica): " + (p.avoidForm.map { "formação $it" } + p.avoidStyle.map { "estilo $it" }).joinToString(", ") else null
        ).joinToString("\n")
    }
}
