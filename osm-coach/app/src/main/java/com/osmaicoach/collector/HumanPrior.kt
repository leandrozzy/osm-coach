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
        val avoidForm: Set<String> = emptySet(), val avoidStyle: Set<String> = emptySet(),
        /** Últimos resultados, do mais recente ao mais antigo (ex.: D D D V). */
        val streak: String = "",
        /** Lição da IA depois do último jogo (vale por poucos jogos): formação/estilo a evitar e a repetir. */
        val lessonAvoid: List<Pair<String?, String?>> = emptyList(), val lessonPrefer: List<Pair<String?, String?>> = emptyList(),
        val lessonText: String = ""
    ) {
        fun withLesson(l: Lesson?): Prior = if (l == null) this else Prior(ref, n, bucket, form, style, ment, avoidForm, avoidStyle, streak, l.avoid, l.prefer, l.text)
    }

    /** Lição guardada pelo app depois de um jogo (IA ou regra): vale por [LESSON_GAMES] jogos. */
    class Lesson(val round: Int, val avoid: List<Pair<String?, String?>>, val prefer: List<Pair<String?, String?>>, val text: String)

    const val LESSON_GAMES = 3

    fun lessonFromJson(j: JSONObject?, currentRound: Int?): Lesson? {
        if (j == null) return null
        val r = j.optInt("round", -1)
        if (r < 0 || (currentRound != null && currentRound - r > LESSON_GAMES)) return null
        fun list(k: String): List<Pair<String?, String?>> {
            val a = j.optJSONArray(k) ?: return emptyList()
            return (0 until minOf(a.length(), 3)).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val f = o.optString("formation").takeIf { it.isNotBlank() }?.let { Formations.canonical(it) }?.let { Formations.base(it) }
                val st = o.optString("style").takeIf { it.isNotBlank() }?.let { Osm.style(it) }
                if (f == null && st == null) null else Pair(f, st)
            }
        }
        return Lesson(r, list("avoid"), list("prefer"), j.optString("text").take(400))
    }

    private const val SHRINK = 5.0
    private const val MIN_GAMES = 3
    private const val AVOID_MIN_GAMES = 3
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

    /** Peso pela idade: o jogo mais recente vale 1; a cada 8 jogos para trás o peso cai pela metade. */
    private fun recency(i: Int) = Math.pow(0.5, i / 8.0)

    fun build(logs: List<JSONObject>, diff: Int?): Prior? {
        // do mais recente ao mais antigo (genAt = quando a tática foi gerada; sem data, a ordem da lista)
        val all = logs.filter { it.optBoolean("human", false) && it.optString("result") in listOf("V", "E", "D") }
            .sortedByDescending { it.optLong("genAt", 0L) }
        if (all.size < 6) return null
        val (a, b) = fit(all)
        val ref = all.sumOf { pts(it.optString("result")) } / all.size
        val w = all.indices.associate { all[it] to recency(it) }
        fun groups(key: (JSONObject) -> String): Map<String, G> = all.groupBy(key).mapValues { (_, v) ->
            val raw = v.sumOf { pts(it.optString("result")) } / v.size
            val ws = v.sumOf { w[it] ?: 1.0 }
            val res = v.sumOf { j -> (w[j] ?: 1.0) * (pts(j.optString("result")) - (a + b * (diffOf(j) ?: 0))) } / (ws + SHRINK)
            G(v.size, raw, res)
        }
        val form = groups { Formations.base(it.optString("formation")) }
        val style = groups { Osm.style(it.optString("playStyle")) ?: it.optString("playStyle") }
        val ment = groups { band(it.optInt("mentality", 50)) }
        // freio de emergência: escolha usada 2+ vezes nos últimos 6 jogos que só perdeu sai já, sem esperar 4 jogos
        val last = all.take(6)
        fun hot(key: (JSONObject) -> String) = last.groupBy(key).filter { (_, v) -> v.size >= 2 && v.all { it.optString("result") == "D" } }.keys
        val hotForm = hot { Formations.base(it.optString("formation")) }
        val hotStyle = hot { Osm.style(it.optString("playStyle")) ?: it.optString("playStyle") }
        return Prior(
            ref, all.size, "todas as faixas, ajustado pela diferença de força e pelo momento", form, style, ment,
            avoidForm = form.filter { it.value.n >= AVOID_MIN_GAMES && it.value.adj <= AVOID_ADJ }.keys + hotForm,
            avoidStyle = style.filter { it.value.n >= AVOID_MIN_GAMES && it.value.adj <= AVOID_ADJ }.keys + hotStyle,
            streak = all.take(6).joinToString(" ") { it.optString("result") }
        )
    }

    private fun adj(g: G?): Double = if (g == null || g.n < MIN_GAMES) 0.0 else g.adj

    /** Pontos esperados a somar (ou tirar) da nota da tática. */
    fun bonus(p: Prior, formation: String, style: String, mentality: Int): Double {
        val d = adj(p.form[Formations.base(formation)]) + adj(p.style[Osm.style(style) ?: style]) + 0.5 * adj(p.ment[band(mentality)])
        fun m(l: List<Pair<String?, String?>>) = l.count { (f, st) ->
            (f == null || f == Formations.base(formation)) && (st == null || st == (Osm.style(style) ?: style))
        }
        val lesson = 0.4 * m(p.lessonPrefer).coerceAtMost(1) - 0.5 * m(p.lessonAvoid).coerceAtMost(1)
        return (WEIGHT * d + lesson).coerceIn(-CAP, CAP)
    }

    fun avoidsFormation(p: Prior?, formation: String) = p != null && (Formations.base(formation) in p.avoidForm ||
        p.lessonAvoid.any { (f, st) -> f == Formations.base(formation) && st == null })
    fun avoidsStyle(p: Prior?, style: String) = p != null && ((Osm.style(style) ?: style) in p.avoidStyle ||
        p.lessonAvoid.any { (f, st) -> f == null && st == (Osm.style(style) ?: style) })

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
            if (p.streak.isNotBlank()) "Últimos resultados contra humanos (do mais recente): ${p.streak}. Em sequência de derrotas NÃO repita as táticas que perderam." else null,
            if (p.lessonText.isNotBlank()) "Lição do último jogo: ${p.lessonText}" else null,
            if (p.avoidForm.isNotEmpty() || p.avoidStyle.isNotEmpty())
                "EVITE (perderam bem mais do que a força explica): " + (p.avoidForm.map { "formação $it" } + p.avoidStyle.map { "estilo $it" }).joinToString(", ") else null
        ).joinToString("\n")
    }
}
