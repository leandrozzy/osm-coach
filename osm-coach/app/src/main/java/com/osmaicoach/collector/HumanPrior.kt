package com.osmaicoach.collector

import org.json.JSONObject

/**
 * O que os SEUS jogos reais contra humanos (nos 4 slots) mostram: pontos por jogo por formação, estilo e mentalidade,
 * na mesma faixa de força (mais fraco / parelho / mais forte). Vira um bônus pequeno na nota da simulação, para a
 * tática não repetir o que perdeu sempre (ex.: recuar demais contra humano mais forte) nem deixar de repetir o que ganha.
 */
object HumanPrior {
    data class G(val n: Int, val ppg: Double)
    class Prior(val ref: Double, val n: Int, val bucket: String, val form: Map<String, G>, val style: Map<String, G>, val ment: Map<String, G>)

    private const val SHRINK = 8.0
    private const val MIN_GAMES = 3
    private const val WEIGHT = 0.35
    private const val CAP = 0.5

    private fun band(m: Int) = if (m >= 60) "ofensiva" else if (m >= 45) "média" else "defensiva"

    private fun bucketOf(diff: Int?): String? = diff?.let { if (it <= -3) "mais fraco" else if (it < 3) "parelho" else "mais forte" }

    private fun pts(r: String) = if (r == "V") 3.0 else if (r == "E") 1.0 else 0.0

    private fun groups(rows: List<JSONObject>, key: (JSONObject) -> String): Map<String, G> =
        rows.groupBy(key).mapValues { (_, v) -> G(v.size, v.sumOf { pts(it.optString("result")) } / v.size) }

    fun build(logs: List<JSONObject>, diff: Int?): Prior? {
        val all = logs.filter { it.optBoolean("human", false) && it.optString("result") in listOf("V", "E", "D") }
        if (all.size < 6) return null
        val b = bucketOf(diff)
        fun same(j: JSONObject): Boolean {
            val m = j.optInt("myStrength", 0); val r = j.optInt("rivalStrength", 0)
            return m > 0 && r > 0 && bucketOf(m - r) == b
        }
        val inBucket = if (b != null) all.filter { same(it) } else emptyList()
        val rows = if (inBucket.size >= 8) inBucket else all
        val label = if (rows === inBucket) b!! else "todas as faixas"
        val ref = rows.sumOf { pts(it.optString("result")) } / rows.size
        return Prior(
            ref, rows.size, label,
            groups(rows) { Formations.base(it.optString("formation")) },
            groups(rows) { Osm.style(it.optString("playStyle")) ?: it.optString("playStyle") },
            groups(rows) { band(it.optInt("mentality", 50)) }
        )
    }

    private fun delta(g: G?, ref: Double): Double =
        if (g == null || g.n < MIN_GAMES) 0.0 else (g.ppg - ref) * g.n / (g.n + SHRINK)

    /** Pontos esperados a somar (ou tirar) da nota da tática. */
    fun bonus(p: Prior, formation: String, style: String, mentality: Int): Double {
        val d = delta(p.form[Formations.base(formation)], p.ref) + delta(p.style[Osm.style(style) ?: style], p.ref) + delta(p.ment[band(mentality)], p.ref)
        return (WEIGHT * d).coerceIn(-CAP, CAP)
    }

    /** Frase curta com os números reais que pesaram na escolha. */
    fun note(p: Prior, formation: String, style: String, mentality: Int): String? {
        fun f(g: G?) = g?.takeIf { it.n >= MIN_GAMES }?.let { "${"%.1f".format(it.ppg).replace('.', ',')} pts/jogo em ${it.n}" }
        val parts = listOfNotNull(
            f(p.form[Formations.base(formation)])?.let { "${Formations.base(formation)}: $it" },
            f(p.style[Osm.style(style) ?: style])?.let { "${Osm.style(style) ?: style}: $it" },
            f(p.ment[band(mentality)])?.let { "mentalidade ${band(mentality)}: $it" }
        )
        if (parts.isEmpty()) return null
        return "Seus jogos reais contra humanos (${p.bucket}, ${p.n} jogos, média ${"%.1f".format(p.ref).replace('.', ',')} pts/jogo) — " + parts.joinToString("; ") + "."
    }
}
