package com.osmaicoach.collector

import android.graphics.BitmapFactory
import android.util.Base64
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

    /**
     * Assinatura rica (grade 8×5, mesma do Atlas) do MIOLO da miniatura da bandeira do jogador (48×32, recortada na caixa real): tira 8% dos
     * lados e 10% de cima/baixo (a moldura branca/cinza da bandeira do OSM) para ficar comparável com o que o Atlas guardou. Medido no
     * diagnóstico de 11/11: com a miniatura inteira acertava 6/9 e a distância ao país certo ficava em ~0,13; com o miolo 9/9.
     */
    fun gridOfThumb(b64: String): String? = runCatching {
        val bytes = Base64.decode(Flags.imgPayload(b64), Base64.NO_WRAP)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h); bmp.recycle()
        val x0 = (w * 0.08f).toInt(); val x1 = w - x0; val y0 = (h * 0.10f).toInt(); val y1 = h - y0
        gridOf(px, w, x0, y0, x1, y1)
    }.getOrNull()

    /** Média de cor em 8×5 células do retângulo [x0,x1)×[y0,y1) de [px] (largura [w]), em hexa RRGGBB por célula. */
    internal fun gridOf(px: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): String {
        val sb = StringBuilder()
        for (gy in 0 until 5) for (gx in 0 until 8) {
            val cx0 = x0 + (x1 - x0) * gx / 8; val cx1 = maxOf(cx0 + 1, x0 + (x1 - x0) * (gx + 1) / 8)
            val cy0 = y0 + (y1 - y0) * gy / 5; val cy1 = maxOf(cy0 + 1, y0 + (y1 - y0) * (gy + 1) / 5)
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            for (y in cy0 until cy1) for (x in cx0 until cx1) { val c = px[y * w + x]; r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF; n++ }
            n = maxOf(n, 1)
            sb.append("%02x%02x%02x".format((r / n).toInt(), (g / n).toInt(), (b / n).toInt()))
        }
        return sb.toString()
    }

    private val thumbCache = HashMap<String, String?>()

    /**
     * País (gentílico do Atlas) de uma miniatura de bandeira: o mais parecido no Atlas, se a distância é ≤0,14 e a vantagem sobre o 2º ≥0,012;
     * duvidoso = null (o Elenco usa o emoji/imagem própria).
     */
    suspend fun nameOfThumb(repo: Repo, b64: String?): String? {
        if (b64.isNullOrEmpty()) return null
        val atlas = load(repo, Kind.NAT)
        if (atlas.isEmpty()) return null
        val ck = atlas.size.toString() + ":" + b64.hashCode()
        synchronized(thumbCache) { if (thumbCache.containsKey(ck)) return thumbCache[ck] }
        val g = gridOfThumb(b64)
        var res: String? = null
        if (g != null) {
            var best = 9f; var second = 9f; var bn: String? = null
            for ((n, r) in atlas) {
                val d = PixelProbe.richDist(g, r)
                if (d < best) { second = best; best = d; bn = n } else if (d < second) second = d
            }
            if (bn != null && best <= 0.14f && second - best >= 0.012f) res = bn
        }
        synchronized(thumbCache) { if (thumbCache.size > 2000) thumbCache.clear(); thumbCache[ck] = res }
        return res
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
