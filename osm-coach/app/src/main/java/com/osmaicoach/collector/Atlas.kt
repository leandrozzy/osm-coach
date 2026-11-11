package com.osmaicoach.collector

import org.json.JSONObject
import kotlin.math.abs

/**
 * Atlas de bandeiras e ligas: aprendido das telas de FILTRO do OSM (Lista de transferências → Nacionalidade / Liga),
 * onde cada célula mostra a bandeira e o nome ("Brasileiro", "Brasil, 2ª Divisão"). Guarda nome → assinatura rica
 * (`PixelProbe.flagRich`) no plano global (slot 0). Com isso a bandeira do Plantel vira o nome do país sozinha.
 * Dados globais (valem para os 4 slots).
 */
object Atlas {
    enum class Kind { NAT, LEAGUE }

    private val COLS = floatArrayOf(0.163f, 0.387f, 0.612f, 0.836f)
    private val cache = HashMap<Kind, Map<String, String>>()

    private fun key(k: Kind) = if (k == Kind.NAT) "atlas_nat" else "atlas_liga"

    /** A tela é o filtro "Nacionalidade" ou "Liga" (título no topo + grade de 4 colunas)? */
    fun detect(o: OcrResult): Kind? {
        val title = o.lines.firstOrNull { it.yc < 0.11f && it.xc in 0.42f..0.58f && Txt.norm(it.text).trim().let { s -> s == "nacionalidade" || s == "liga" } } ?: return null
        val cells = o.lines.count { it !== title && it.yc in 0.15f..0.97f && COLS.any { c -> abs(it.xc - c) < 0.06f } }
        if (cells < 4) return null
        return if (Txt.norm(title.text).trim() == "nacionalidade") Kind.NAT else Kind.LEAGUE
    }

    suspend fun load(repo: Repo, kind: Kind): Map<String, String> {
        cache[kind]?.let { return it }
        val j = repo.dao.plan(0, key(kind))?.json?.let { runCatching { JSONObject(it) }.getOrNull() }?.optJSONObject("v")
        val m = LinkedHashMap<String, String>()
        if (j != null) for (k in j.keys()) m[k] = j.optString(k)
        cache[kind] = m
        return m
    }

    /** Lê os nomes e bandeiras visíveis e soma ao Atlas. Devolve quantas entradas novas. */
    suspend fun learn(repo: Repo, o: OcrResult, img: PixelProbe.Img, kind: Kind, now: Long): Int {
        val nat = kind == Kind.NAT
        val dy = if (nat) 0.052f else 0.050f
        val w = if (nat) 0.040f else 0.018f
        val h = if (nat) 0.060f else 0.034f
        val cur = LinkedHashMap(load(repo, kind))
        var added = 0
        for (ln in o.lines) {
            if (ln.yc !in 0.22f..0.97f) continue
            val col = COLS.minByOrNull { abs(it - ln.xc) } ?: continue
            if (abs(col - ln.xc) > 0.06f) continue
            val name = ln.text.trim()
            if (name.length < 3 || Txt.letters(name) < 3) continue
            val nn = Txt.norm(name)
            if (nn.startsWith("todas") || nn.startsWith("ligas sugeridas")) continue
            val fy = ln.yc - dy
            if (fy !in 0.19f..0.92f) continue   // bandeira cortada pela borda da lista: não vale
            if (cur.containsKey(name)) continue
            val rich = PixelProbe.flagRich(img, col, fy, w, h) ?: continue
            cur[name] = rich
            added++
        }
        if (added > 0) {
            val v = JSONObject(); for ((k, r) in cur) v.put(k, r)
            repo.dao.putPlan(PlanEntity(0, key(kind), JSONObject().put("v", v).put("at", now).toString(), now))
            cache[kind] = cur
            Diag.log("Atlas ${if (nat) "nacionalidades" else "ligas"}: +$added (total ${cur.size})")
        }
        return added
    }

    suspend fun count(repo: Repo, kind: Kind): Int = load(repo, kind).size

    /** Nome do país (gentílico, ex.: "Brasileiro") da bandeira; null se o Atlas não tem ou há dúvida. Empate próximo devolve "A ou B". */
    suspend fun nameOf(repo: Repo, rich: String?): String? {
        if (rich == null) return null
        val atlas = load(repo, Kind.NAT)
        if (atlas.isEmpty()) return null
        var best = 9f; var second = 9f; var bn: String? = null; var sn: String? = null
        for ((n, r) in atlas) {
            val d = PixelProbe.richDist(rich, r)
            if (d < best) { second = best; sn = bn; best = d; bn = n } else if (d < second) { second = d; sn = n }
        }
        if (bn == null || best > 0.05f) return null
        return if (second - best >= 0.004f || sn == null) bn else "$bn ou $sn"
    }

    /** Assinatura rica do Atlas para o nome (nacionalidade ou liga). */
    suspend fun richOf(repo: Repo, kind: Kind, name: String): String? = load(repo, kind)[name]

    /** Liga do Atlas que combina com o título do slot ("Alemanha, 3ª Divisão"); null se não achar. */
    suspend fun leagueMatch(repo: Repo, title: String?): String? {
        if (title.isNullOrBlank()) return null
        val t = Txt.norm(title).replace(Regex("\\s+"), " ").trim()
        val m = load(repo, Kind.LEAGUE)
        var best: String? = null; var bs = 0.0
        for (n in m.keys) {
            val s = Txt.sim(Txt.norm(n), t)
            if (s > bs) { bs = s; best = n }
        }
        return if (bs >= 0.82) best else null
    }

    private val SPECIAL = mapOf("eua" to "americano", "estados unidos" to "americano", "holanda" to "holandes", "paises baixos" to "holandes", "reino unido" to "ingles", "inglaterra" to "ingles", "suica" to "suico", "alemanha" to "alemao")

    /** Gentílico do Atlas que corresponde ao país da liga ("Alemanha" → "Alemão"). */
    suspend fun nationalityForCountry(repo: Repo, country: String): String? {
        val c = Txt.norm(country).trim()
        val atlas = load(repo, Kind.NAT)
        val special = SPECIAL[c]
        for (n in atlas.keys) {
            val nn = Txt.norm(n)
            if (special != null && nn == special) return n
        }
        if (c.length < 4) return null
        val pre = c.take(4)
        val hits = atlas.keys.filter { Txt.norm(it).startsWith(pre) }
        return if (hits.size == 1) hits[0] else hits.firstOrNull { Txt.norm(it).startsWith(c.take(minOf(c.length, 6))) }
    }
}
