package com.osmaicoach.collector

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Miniatura da última tela do OSM e busca dela nos Recentes: o cartão do OSM muda de lugar (e aparece girado) conforme o
 * launcher, então o arrasto para fechar mira onde a miniatura realmente está (07/11: o arrasto no centro não pegava).
 */
object OsmThumb {
    const val TW = 64
    const val TH = 28
    @Volatile private var luma: IntArray? = null

    fun has(): Boolean = luma != null

    private fun lum(c: Int): Int = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000

    /** Guarda a miniatura (paisagem 64×28) de um quadro aceito como tela do OSM. */
    fun set(b: Bitmap) {
        try {
            val land = if (b.width >= b.height) b else return
            val s = Bitmap.createScaledBitmap(land, TW, TH, true)
            val px = IntArray(TW * TH)
            s.getPixels(px, 0, TW, 0, 0, TW, TH)
            luma = IntArray(px.size) { lum(px[it]) }
            if (s !== land) s.recycle()
        } catch (e: Exception) { }
    }

    /** Resultado: centro do cartão (em pixels da tela) e altura estimada. */
    class Hit(val cx: Float, val cy: Float, val cardH: Float, val score: Double)

    fun find(screen: Bitmap): Hit? {
        val t = luma ?: return null
        val sw = 108
        val sh = (sw * screen.height / screen.width.toFloat()).roundToInt().coerceAtLeast(40)
        val small = Bitmap.createScaledBitmap(screen, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== screen) small.recycle()
        val s = IntArray(px.size) { lum(px[it]) }
        val hit = match(s, sw, sh, t, TW, TH) ?: return null
        val k = screen.width / sw.toFloat()
        return Hit(hit.cx * k, hit.cy * k, hit.cardH * k, hit.score)
    }

    /**
     * Procura a miniatura (paisagem `tw`×`th`) na imagem `s` (`sw`×`sh`), de pé ou girada 90°, em vários tamanhos.
     * Compara com a média removida (a tela de Recentes escurece os cartões). Score menor = melhor; ≥ 0,6 = nada convincente.
     */
    fun match(s: IntArray, sw: Int, sh: Int, t: IntArray, tw: Int, th: Int): Hit? {
        var best: Hit? = null
        // variantes: 0 = de pé, 1 = girada 90° anti-horária, 2 = girada 90° horária
        for (rot in 0..2) {
            val bw = if (rot == 0) tw else th
            val bh = if (rot == 0) th else tw
            for (frac in doubleArrayOf(0.30, 0.38, 0.46, 0.55, 0.65, 0.78)) {
                val w = (sw * frac).roundToInt().coerceAtLeast(8)
                val h = (w * bh / bw.toFloat()).roundToInt().coerceAtLeast(8)
                if (w >= sw || h >= sh) continue
                // modelo reamostrado w×h (vizinho mais próximo), já girado
                val m = IntArray(w * h)
                for (y in 0 until h) for (x in 0 until w) {
                    val u = x * bw / w
                    val v = y * bh / h
                    val tx: Int; val ty: Int
                    when (rot) {
                        0 -> { tx = u; ty = v }
                        1 -> { tx = tw - 1 - v; ty = u }      // girada anti-horária: coluna do topo vira linha
                        else -> { tx = v; ty = th - 1 - u }   // girada horária
                    }
                    m[y * w + x] = t[ty.coerceIn(0, th - 1) * tw + tx.coerceIn(0, tw - 1)]
                }
                var mm = 0L
                for (v in m) mm += v
                val mMean = mm.toDouble() / m.size
                var dev = 0.0
                for (v in m) dev += abs(v - mMean)
                dev /= m.size
                if (dev < 6.0) continue // modelo sem contraste: não dá para achar
                var y0 = 0
                while (y0 + h <= sh) {
                    var x0 = 0
                    while (x0 + w <= sw) {
                        var sm = 0L
                        var n = 0
                        var yy = 0
                        while (yy < h) {
                            var xx = 0
                            while (xx < w) { sm += s[(y0 + yy) * sw + x0 + xx]; n++; xx += 2 }
                            yy += 2
                        }
                        val sMean = sm.toDouble() / n
                        var d = 0.0
                        yy = 0
                        while (yy < h) {
                            var xx = 0
                            while (xx < w) {
                                d += abs((s[(y0 + yy) * sw + x0 + xx] - sMean) - (m[yy * w + xx] - mMean))
                                xx += 2
                            }
                            yy += 2
                        }
                        val score = d / n / dev
                        if (best == null || score < best.score) best = Hit(x0 + w / 2f, y0 + h / 2f, h.toFloat(), score)
                        x0 += 2
                    }
                    y0 += 2
                }
            }
        }
        return best?.takeIf { it.score < 0.6 }
    }
}
