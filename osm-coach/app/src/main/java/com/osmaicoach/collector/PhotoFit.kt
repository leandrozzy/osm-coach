package com.osmaicoach.collector

import android.graphics.Bitmap

/**
 * Enquadra uma foto de estúdio (a do Wikipedia ou a que o usuário escolhe) no mesmo estilo das da API: tira o fundo liso (conexo com a borda,
 * por cor), acha o rosto pela pele, enquadra cabeça e ombros num quadrado e põe sobre o MESMO fundo claro (a tela corta em círculo).
 * Sem fundo liso ou sem rosto achado, cai no recorte simples (quadrado do alto, sem tirar o fundo). Tudo em IntArray (testável na JVM).
 */
object PhotoFit {
    const val BG = 0xFFF3F5F9.toInt()
    private const val MAXSIDE = 500

    class Fit(val px: IntArray, val removedBg: Boolean)

    private fun lum(r: Int, g: Int, b: Int) = 0.299f * r + 0.587f * g + 0.114f * b

    /** px = pixels ARGB w×h. Devolve s×s pixels (opaco). */
    fun fit(px: IntArray, w: Int, h: Int, s: Int = 192): Fit {
        val rr = IntArray(w * h) { (px[it] shr 16) and 255 }
        val gg = IntArray(w * h) { (px[it] shr 8) and 255 }
        val bb = IntArray(w * h) { px[it] and 255 }
        // fundo = mediana da moldura de 4 px
        val bs = ArrayList<Int>()
        for (y in 0 until h) for (x in 0 until w) if (x < 4 || y < 4 || x >= w - 4 || y >= h - 4) bs.add(y * w + x)
        fun med(c: IntArray): Float { val v = IntArray(bs.size) { c[bs[it]] }; v.sort(); return v[v.size / 2].toFloat() }
        val mr = med(rr); val mg = med(gg); val mb = med(bb)
        val fL = lum(mr.toInt(), mg.toInt(), mb.toInt()); val fU = mr - mg; val fV = mb - (mr + mg) / 2f
        val cand = BooleanArray(w * h) {
            val l = lum(rr[it], gg[it], bb[it]); val u = (rr[it] - gg[it]).toFloat(); val v = bb[it] - (rr[it] + gg[it]) / 2f
            0.8f * Math.abs(l - fL) + 2f * Math.abs(u - fU) + 2f * Math.abs(v - fV) < 26f
        }
        val isBg = BooleanArray(w * h)
        val stack = IntArray(w * h); var sp = 0
        fun seed(i: Int) { if (cand[i] && !isBg[i]) { isBg[i] = true; stack[sp++] = i } }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        while (sp > 0) {
            val i = stack[--sp]; val x = i % w; val y = i / w
            if (x > 0) seed(i - 1); if (x < w - 1) seed(i + 1); if (y > 0) seed(i - w); if (y < h - 1) seed(i + w)
        }
        var fgN = 0; for (i in isBg.indices) if (!isBg[i]) fgN++
        val frac = fgN.toFloat() / (w * h)
        val usable = frac in 0.06f..0.92f
        // topo do sujeito: 1ª linha com >3% de largura em primeiro plano
        var top = 0
        if (usable) { for (y in 0 until h) { var c = 0; for (x in 0 until w) if (!isBg[y * w + x]) c++; if (c > 0.03f * w) { top = y; break } } }
        // pele (YCbCr) na parte de cima do sujeito
        val limY = top + (0.55f * (h - top)).toInt()
        val sx = ArrayList<Int>(); val sy = ArrayList<Int>()
        if (usable) for (y in top until minOf(h, limY)) for (x in 0 until w) {
            val i = y * w + x; if (isBg[i]) continue
            val r = rr[i]; val g = gg[i]; val b = bb[i]
            val yy = lum(r, g, b); val cb = 128 - 0.168736f * r - 0.331264f * g + 0.5f * b; val cr = 128 + 0.5f * r - 0.418688f * g - 0.081312f * b
            if (cb > 77 && cb < 127 && cr > 133 && cr < 173 && yy > 60) { sx.add(x); sy.add(y) }
        }
        val out = IntArray(s * s)
        if (usable && sx.size >= 0.004f * w * h) {
            val xs = sx.sorted()
            val x0 = xs[(xs.size * 0.03f).toInt()]; val x1 = xs[minOf(xs.size - 1, (xs.size * 0.97f).toInt())]
            val fw = (x1 - x0).coerceAtLeast(8)
            val cx = (x0 + x1) / 2f
            val side = fw * 3.7f
            val fx = cx - side / 2f; val fy = top - 0.07f * side
            // alfa suavizado (erode 1 px + desfoque)
            val al = FloatArray(w * h) { if (isBg[it]) 0f else 1f }
            val er = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var m = 1f
                for (dy in -1..1) for (dx in -1..1) { val xx = x + dx; val yy = y + dy; if (xx in 0 until w && yy in 0 until h) m = minOf(m, al[yy * w + xx]) }
                er[y * w + x] = m
            }
            val bl = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var sum = 0f; var n = 0
                for (dy in -1..1) for (dx in -1..1) { val xx = x + dx; val yy = y + dy; if (xx in 0 until w && yy in 0 until h) { sum += er[yy * w + xx]; n++ } }
                bl[y * w + x] = sum / n
            }
            fun samp(c: FloatArray?, ci: IntArray?, fxp: Float, fyp: Float): Float {
                val x = fxp - 0.5f; val y = fyp - 0.5f
                val xi = Math.floor(x.toDouble()).toInt(); val yi = Math.floor(y.toDouble()).toInt()
                val tx = x - xi; val ty = y - yi
                fun v(xx: Int, yy: Int): Float = if (xx !in 0 until w || yy !in 0 until h) 0f else if (c != null) c[yy * w + xx] else ci!![yy * w + xx].toFloat()
                return v(xi, yi) * (1 - tx) * (1 - ty) + v(xi + 1, yi) * tx * (1 - ty) + v(xi, yi + 1) * (1 - tx) * ty + v(xi + 1, yi + 1) * tx * ty
            }
            val br = (BG shr 16) and 255; val bg = (BG shr 8) and 255; val bbl = BG and 255
            for (oy in 0 until s) for (ox in 0 until s) {
                val sxp = fx + (ox + 0.5f) / s * side; val syp = fy + (oy + 0.5f) / s * side
                val a = samp(bl, null, sxp, syp).coerceIn(0f, 1f)
                val r = samp(null, rr, sxp, syp); val g = samp(null, gg, sxp, syp); val b = samp(null, bb, sxp, syp)
                val R = (r * a + br * (1 - a)).toInt().coerceIn(0, 255); val G = (g * a + bg * (1 - a)).toInt().coerceIn(0, 255); val B = (b * a + bbl * (1 - a)).toInt().coerceIn(0, 255)
                out[oy * s + ox] = (0xFF shl 24) or (R shl 16) or (G shl 8) or B
            }
            return Fit(out, true)
        }
        // plano B: quadrado (retrato: do alto), sem tirar o fundo
        val side = minOf(w, h).toFloat()
        val fx = (w - side) / 2f; val fy = if (h > w) (h * 0.02f).coerceAtMost(h - side) else (h - side) / 2f
        for (oy in 0 until s) for (ox in 0 until s) {
            val xp = (fx + (ox + 0.5f) / s * side).toInt().coerceIn(0, w - 1); val yp = (fy + (oy + 0.5f) / s * side).toInt().coerceIn(0, h - 1)
            out[oy * s + ox] = px[yp * w + xp] or (0xFF shl 24)
        }
        return Fit(out, false)
    }

    /** Bitmap → Bitmap 192×192 enquadrado. */
    fun fitBitmap(src: Bitmap, s: Int = 192): Bitmap {
        val scale = minOf(1f, MAXSIDE.toFloat() / maxOf(src.width, src.height))
        val b = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(8), (src.height * scale).toInt().coerceAtLeast(8), true) else src
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val f = fit(px, b.width, b.height, s)
        if (b !== src) b.recycle()
        return Bitmap.createBitmap(f.px, s, s, Bitmap.Config.ARGB_8888)
    }
}
