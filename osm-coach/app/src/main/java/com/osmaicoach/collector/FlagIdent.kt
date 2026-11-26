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

    internal class Ref(val iso: String, val c16: Cells, val c85: Cells, val lab: FloatArray = FloatArray(0))
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
            out.add(Ref(f.name.removeSuffix(".png"), cells(px, w, mx, my, w - mx, h - my, 16, 10), cells(px, w, (w * 0.08f).toInt(), (h * 0.10f).toInt(), w - (w * 0.08f).toInt(), h - (h * 0.10f).toInt(), 8, 5), labGrid(px, w, mx, my, w - mx, h - my, 24, 16)))
        }
        refs = out; refCount = files.size
        return out
    }

    /** Quantas bandeiras de referência já foram baixadas (a identificação só roda com o conjunto quase completo). */
    fun refsReady(filesDir: File): Boolean = (File(filesDir, "flags").listFiles()?.count { it.name.endsWith(".png") } ?: 0) >= 200

    /** Média por célula (gw×gh) em CIE Lab (L, a, b por célula): separa bandeiras de mesmas cores com emblema/estrela e tons diferentes (Mali×Senegal, Turquia×Marrocos). */
    internal fun labGrid(px: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, gw: Int, gh: Int): FloatArray {
        val out = FloatArray(gw * gh * 3)
        fun lin(v: Float) = if (v > 0.04045f) Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat() else v / 12.92f
        fun f(v: Float) = if (v > 0.008856f) Math.cbrt(v.toDouble()).toFloat() else 7.787f * v + 16f / 116f
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val cx0 = x0 + (x1 - x0) * gx / gw; val cx1 = maxOf(cx0 + 1, x0 + (x1 - x0) * (gx + 1) / gw)
            val cy0 = y0 + (y1 - y0) * gy / gh; val cy1 = maxOf(cy0 + 1, y0 + (y1 - y0) * (gy + 1) / gh)
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            for (y in cy0 until cy1) for (x in cx0 until cx1) { val c = px[y * w + x]; r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF; n++ }
            n = maxOf(n, 1)
            val rl = lin(r / n / 255f); val gl = lin(g / n / 255f); val bl = lin(b / n / 255f)
            val X = (0.4124f * rl + 0.3576f * gl + 0.1805f * bl) / 0.95047f; val Y = 0.2126f * rl + 0.7152f * gl + 0.0722f * bl; val Z = (0.0193f * rl + 0.1192f * gl + 0.9505f * bl) / 1.08883f
            val fx = f(X); val fy = f(Y); val fz = f(Z); val i = (gy * gw + gx) * 3
            out[i] = 116f * fy - 16f; out[i + 1] = 500f * (fx - fy); out[i + 2] = 200f * (fy - fz)
        }
        return out
    }

    internal fun labDist(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size) / 3
        if (n == 0) return 0f
        var s = 0f
        for (i in 0 until n) { val dl = a[i * 3] - b[i * 3]; val da = a[i * 3 + 1] - b[i * 3 + 1]; val db = a[i * 3 + 2] - b[i * 3 + 2]; s += Math.sqrt((0.3f * dl * dl + da * da + db * db).toDouble()).toFloat() }
        return s / n
    }

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
        return pick(t16, labGrid(px, w, x0, y0, w - x0, h - y0, 24, 16), rs)
    }

    /**
     * Decisão (pura): o país é o da bandeira de referência (Flagpedia) mais parecida com a miniatura, comparando cor nomeada em grade 16×10.
     * Conferido com 12 bandeiras REAIS do OSM (prints de 25/11/26: Albânia, Mali, Costa do Marfim, Nigéria, Líbano, Turquia, Argentina, Kosovo): 12/12.
     * O Atlas (nome→bandeira lido dos filtros do OSM) NÃO entra mais na decisão: no aparelho do usuário ele tinha guardado a Albânia como "Turquia", o Mali como
     * "Senegal" e a Argentina como "El Salvador" (bandeiras de cores iguais, diferença só no emblema/estrela) e, com prioridade, trocava o país certo pelo errado.
     */
    internal fun pick(t16: Cells, lab: FloatArray?, rs: List<Ref>): String? {
        val ranked = rs.sortedBy { dist(t16, it.c16) }
        if (ranked.isEmpty()) return null
        if (lab == null || ranked.size < 2) return ranked[0].iso
        // 2º estágio: entre os 6 mais parecidos por cor nomeada, soma o Lab fino (peso 0,5): separa estrela/emblema e tons (medido: 12/12 e folgas maiores)
        return ranked.take(6).minByOrNull { dist(t16, it.c16) + 0.5f * labDist(lab, it.lab) / 100f }?.iso
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
            var best = Float.MAX_VALUE; var second = Float.MAX_VALUE; var bi: String? = null
            for ((name, hex) in atlas) {
                val iso = Flags.iso(name) ?: continue
                val d = dist(t85, cellsOfHex(hex) ?: continue)
                if (d < best) { if (iso != bi) second = best; best = d; bi = iso } else if (d < second && iso != bi) second = d
            }
            // sem o Flagpedia para conferir, só vale parecido de verdade e sem empate (coarse 8×5 confunde bandeiras de fundo vermelho)
            if (best <= 0.12f && second - best >= 0.03f) bi else null
        }.getOrNull()
    }
}
