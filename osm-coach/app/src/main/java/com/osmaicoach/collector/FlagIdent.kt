package com.osmaicoach.collector

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Base64
import java.io.File

/**
 * Descobre o PAÍS de uma bandeira do Plantel combinando duas provas (medido no diagnóstico de 11/11 com 36 jogadores de país conhecido):
 *  1) as bandeiras reais do Flagpedia (`flags/<iso>.png`) comparadas por COR NOMEADA (preto/branco/cinza/matiz): as bandeiras do OSM têm brilho
 *     e tom diferentes das reais, então comparar RGB puro confundia França com Irlanda; por matiz acerta 32/36;
 *  2) o Atlas (nome do país lido dos filtros do OSM, mapeado para ISO): ajuda onde a 1ª prova empata (EUA × Samoa, Inglaterra × Irlanda do Norte).
 * Junto acerta 33/36; sobram só bandeiras quase iguais (Eslovênia × Rússia, Paraguai × Holanda). Nada aqui depende de nitidez alta: a grade é grossa.
 */
object FlagIdent {
    private const val NO_ATLAS = 0.45f

    /** Células já classificadas: tipo (0 preto, 1 branco, 2 cinza, 3 cor) e matiz em graus. */
    class Cells(val type: IntArray, val hue: FloatArray)

    internal fun classify(r: Float, g: Float, b: Float): Pair<Int, Float> {
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val v = mx; val d = mx - mn
        if (v < 0.28f) return Pair(0, 0f)
        val s = if (mx <= 0f) 0f else d / mx
        if (s < 0.22f) return Pair(if (v > 0.62f) 1 else 2, 0f)
        val h = when (mx) { r -> ((g - b) / d) % 6f; g -> (b - r) / d + 2f; else -> (r - g) / d + 4f } * 60f
        return Pair(3, if (h < 0) h + 360f else h)
    }

    /** Média por célula (gw×gh) do retângulo [x0,x1)×[y0,y1) de [px] (largura [w]), já classificada. */
    internal fun cells(px: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, gw: Int, gh: Int): Cells {
        val t = IntArray(gw * gh); val hu = FloatArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val cx0 = x0 + (x1 - x0) * gx / gw; val cx1 = maxOf(cx0 + 1, x0 + (x1 - x0) * (gx + 1) / gw)
            val cy0 = y0 + (y1 - y0) * gy / gh; val cy1 = maxOf(cy0 + 1, y0 + (y1 - y0) * (gy + 1) / gh)
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            for (y in cy0 until cy1) for (x in cx0 until cx1) { val c = px[y * w + x]; r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF; n++ }
            n = maxOf(n, 1)
            val (ty, h) = classify(r / n / 255f, g / n / 255f, b / n / 255f)
            t[gy * gw + gx] = ty; hu[gy * gw + gx] = h
        }
        return Cells(t, hu)
    }

    internal fun cellDist(at: Int, ah: Float, bt: Int, bh: Float): Float {
        if (at != bt) return if ((at == 1 && bt == 2) || (at == 2 && bt == 1)) 0.4f else 1f
        if (at != 3) return 0f
        var d = Math.abs(ah - bh); if (d > 180f) d = 360f - d
        return minOf(d / 60f, 1f)
    }

    internal fun dist(a: Cells, b: Cells): Float {
        val n = minOf(a.type.size, b.type.size)
        if (n == 0) return 1f
        var s = 0f
        for (i in 0 until n) s += cellDist(a.type[i], a.hue[i], b.type[i], b.hue[i])
        return s / n
    }

    /** Células 8×5 a partir da assinatura rica (hex) do Atlas. */
    internal fun cellsOfHex(hex: String): Cells? {
        if (hex.length != 240) return null
        val px = IntArray(40) { i -> 0xFF000000.toInt() or hex.substring(i * 6, i * 6 + 6).toInt(16) }
        return cells(px, 8, 0, 0, 8, 5, 8, 5)
    }

    private class Ref(val iso: String, val c16: Cells)
    @Volatile private var refs: List<Ref> = emptyList()
    @Volatile private var refCount = -1

    private fun loadRefs(filesDir: File): List<Ref> {
        val dir = File(filesDir, "flags")
        val files = dir.listFiles()?.filter { it.name.endsWith(".png") } ?: return emptyList()
        if (files.size == refCount) return refs
        val out = ArrayList<Ref>()
        for (f in files) {
            val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() ?: continue
            val w = bmp.width; val h = bmp.height
            val flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply { drawColor(0xFFFFFFFF.toInt()); drawBitmap(bmp, 0f, 0f, null) }
            val px = IntArray(w * h); flat.getPixels(px, 0, w, 0, 0, w, h)
            bmp.recycle(); flat.recycle()
            val mx = (w * 0.03f).toInt(); val my = (h * 0.04f).toInt()
            out.add(Ref(f.name.removeSuffix(".png"), cells(px, w, mx, my, w - mx, h - my, 16, 10)))
        }
        refs = out; refCount = files.size
        return out
    }

    /** Quantas bandeiras de referência já foram baixadas (a identificação só roda com o conjunto quase completo). */
    fun refsReady(filesDir: File): Boolean = (File(filesDir, "flags").listFiles()?.count { it.name.endsWith(".png") } ?: 0) >= 200

    private val cache = HashMap<String, String?>()

    /**
     * ISO do país da miniatura (WebP 48×32 base64 do `natimg3`); null se as bandeiras de referência ainda não estão no aparelho.
     * [atlas] = nome → assinatura rica do Atlas de nacionalidades.
     */
    fun identify(filesDir: File, thumbB64: String?, atlas: Map<String, String>): String? {
        if (thumbB64.isNullOrEmpty() || !refsReady(filesDir)) return null
        val ck = refCount.toString() + ":" + atlas.size + ":" + thumbB64.hashCode()
        synchronized(cache) { if (cache.containsKey(ck)) return cache[ck] }
        val res = runCatching { identifyNow(filesDir, thumbB64, atlas) }.getOrNull()
        synchronized(cache) { if (cache.size > 3000) cache.clear(); cache[ck] = res }
        return res
    }

    private fun identifyNow(filesDir: File, thumbB64: String, atlas: Map<String, String>): String? {
        val rs = loadRefs(filesDir)
        if (rs.size < 200) return null
        val bytes = Base64.decode(Flags.imgPayload(thumbB64), Base64.NO_WRAP)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h); bmp.recycle()
        val x0 = (w * 0.08f).toInt(); val y0 = (h * 0.10f).toInt()
        val t16 = cells(px, w, x0, y0, w - x0, h - y0, 16, 10)
        val t85 = cells(px, w, x0, y0, w - x0, h - y0, 8, 5)
        // Atlas por ISO (um país pode ter mais de um nome lido, com erros do OCR: vale o mais parecido)
        val atlasBy = HashMap<String, MutableList<Cells>>()
        for ((name, hex) in atlas) { val iso = Flags.iso(name) ?: continue; cellsOfHex(hex)?.let { atlasBy.getOrPut(iso) { ArrayList() }.add(it) } }
        // 1º o Atlas sozinho, quando é CLARO (parecido ≤0,16 e 0,01 à frente do 2º país): é o nome que o próprio OSM deu à bandeira. Medido: nos 36
        // conhecidos acertou todos os que passaram por esse critério (EUA incluído, que a soma com a web trocava por Taiwan).
        run {
            var a1 = Float.MAX_VALUE; var a2 = Float.MAX_VALUE; var ai: String? = null
            for ((iso, cs) in atlasBy) {
                val d = cs.minOf { dist(t85, it) }
                if (d < a1) { a2 = a1; a1 = d; ai = iso } else if (d < a2) a2 = d
            }
            if (ai != null && a1 <= 0.16f && a2 - a1 >= 0.01f) return ai
        }
        var best = Float.MAX_VALUE; var bi: String? = null
        for (r in rs) {
            val dw = dist(t16, r.c16)
            val da = atlasBy[r.iso]?.minOf { dist(t85, it) } ?: NO_ATLAS
            val sc = dw + da
            if (sc < best) { best = sc; bi = r.iso }
        }
        return bi
    }

    /**
     * Plano B enquanto as bandeiras do Flagpedia ainda não foram baixadas: só o Atlas (cor nomeada 8×5, tolera borrão). Não é guardado no
     * jogador (só o resultado com as duas provas vale); null se nada no Atlas parece com a bandeira.
     */
    fun identifyAtlasOnly(thumbB64: String?, atlas: Map<String, String>): String? {
        if (thumbB64.isNullOrEmpty() || atlas.isEmpty()) return null
        return runCatching {
            val bytes = Base64.decode(Flags.imgPayload(thumbB64), Base64.NO_WRAP)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val w = bmp.width; val h = bmp.height
            val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h); bmp.recycle()
            val x0 = (w * 0.08f).toInt(); val y0 = (h * 0.10f).toInt()
            val t85 = cells(px, w, x0, y0, w - x0, h - y0, 8, 5)
            var best = Float.MAX_VALUE; var bi: String? = null
            for ((name, hex) in atlas) {
                val iso = Flags.iso(name) ?: continue
                val d = dist(t85, cellsOfHex(hex) ?: continue)
                if (d < best) { best = d; bi = iso }
            }
            if (best <= 0.22f) bi else null
        }.getOrNull()
    }
}
