package com.osmaicoach.collector

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.tasks.await

/**
 * Enquadra uma foto (a do Wikipedia ou a que o usuário escolhe) no mesmo estilo das da API: tira o fundo, acha o rosto, enquadra cabeça e ombros
 * num quadrado e põe sobre o MESMO fundo claro (a tela corta em círculo). O fundo sai, em ordem: (1) pela transparência da própria imagem (PNG/WebP
 * recortado), (2) pela segmentação de pessoa do ML Kit (qualquer fundo: estádio, torcida), (3) por cor, se o fundo é liso. O rosto vem do detector de
 * rostos do ML Kit; sem ele, da cor da pele. Sem nada disso cai no quadrado do alto. O miolo (`fit`) é puro (IntArray) e testado na JVM.
 */
object PhotoFit {
    const val BG = 0xFFF3F5F9.toInt()
    private const val MAXSIDE = 700

    class Fit(val px: IntArray, val removedBg: Boolean)

    private fun lum(r: Int, g: Int, b: Int) = 0.299f * r + 0.587f * g + 0.114f * b

    /** Fundo liso conexo com a borda (por cor) → alfa 0/1; null se não há fundo liso. */
    fun colorMask(rr: IntArray, gg: IntArray, bb: IntArray, w: Int, h: Int): FloatArray? {
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
        var fg = 0; for (i in isBg.indices) if (!isBg[i]) fg++
        val frac = fg.toFloat() / (w * h)
        if (frac !in 0.06f..0.92f) return null
        return FloatArray(w * h) { if (isBg[it]) 0f else 1f }
    }

    /** Rosto pela cor da pele dentro do primeiro plano → [cx, cy, largura]; null se não achou. */
    fun skinFace(rr: IntArray, gg: IntArray, bb: IntArray, w: Int, h: Int, alpha: FloatArray): FloatArray? {
        var top = 0
        run { for (y in 0 until h) { var c = 0; for (x in 0 until w) if (alpha[y * w + x] > 0.5f) c++; if (c > 0.03f * w) { top = y; break } } }
        val limY = top + (0.55f * (h - top)).toInt()
        val sx = ArrayList<Int>(); val sy = ArrayList<Int>()
        for (y in top until minOf(h, limY)) for (x in 0 until w) {
            val i = y * w + x; if (alpha[i] <= 0.5f) continue
            val r = rr[i]; val g = gg[i]; val b = bb[i]
            val yy = lum(r, g, b); val cb = 128 - 0.168736f * r - 0.331264f * g + 0.5f * b; val cr = 128 + 0.5f * r - 0.418688f * g - 0.081312f * b
            if (cb > 77 && cb < 127 && cr > 133 && cr < 173 && yy > 60) { sx.add(x); sy.add(y) }
        }
        if (sx.size < 0.004f * w * h) return null
        val xs = sx.sorted(); val ys = sy.sorted()
        val x0 = xs[(xs.size * 0.03f).toInt()]; val x1 = xs[minOf(xs.size - 1, (xs.size * 0.97f).toInt())]
        val y0 = ys[(ys.size * 0.01f).toInt()]; val y1 = ys[minOf(ys.size - 1, (ys.size * 0.99f).toInt())]
        return floatArrayOf((x0 + x1) / 2f, (y0 + y1) / 2f, (x1 - x0).coerceAtLeast(8).toFloat())
    }

    /**
     * px = pixels ARGB w×h. alphaIn = máscara de pessoa 0..1 (null = tenta por cor); face = [cx, cy, largura do rosto] (null = pela pele).
     * Devolve s×s pixels (opaco). Sem máscara ou rosto utilizável cai no quadrado do alto, sem tirar o fundo.
     */
    fun fit(px: IntArray, w: Int, h: Int, s: Int = 192, alphaIn: FloatArray? = null, face: FloatArray? = null, faceFromDetector: Boolean = false): Fit {
        val rr = IntArray(w * h) { (px[it] shr 16) and 255 }
        val gg = IntArray(w * h) { (px[it] shr 8) and 255 }
        val bb = IntArray(w * h) { px[it] and 255 }
        val alpha = alphaIn ?: colorMask(rr, gg, bb, w, h)
        val f = if (alpha == null) null else (face ?: skinFace(rr, gg, bb, w, h, alpha))
        val fromDet = face != null && faceFromDetector
        val out = IntArray(s * s)
        if (alpha != null && f != null) {
            val cx = f[0]; val cy = f[1]; val fw = f[2]
            val side0 = fw * (if (fromDet) 3.5f else 3.7f)
            val side = minOf(side0, w.toFloat(), h.toFloat())
            val fx = (cx - side / 2f).coerceIn(0f, w - side); val fy = (cy - 0.42f * side).coerceIn(0f, h - side)
            // alfa suavizado (erode 1 px + desfoque)
            val er = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var m = 1f
                for (dy in -1..1) for (dx in -1..1) { val xx = x + dx; val yy = y + dy; if (xx in 0 until w && yy in 0 until h) m = minOf(m, alpha[yy * w + xx]) }
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

    /** Bitmap → Bitmap s×s enquadrado, com o melhor fundo/rosto disponíveis no aparelho. */
    suspend fun fitBitmap(src: Bitmap, s: Int = 192): Bitmap {
        val scale = minOf(1f, MAXSIDE.toFloat() / maxOf(src.width, src.height))
        val b = Bitmap.createScaledBitmap(src.copy(Bitmap.Config.ARGB_8888, false), (src.width * scale).toInt().coerceAtLeast(8), (src.height * scale).toInt().coerceAtLeast(8), true)
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        // 1) transparência da própria imagem
        var alpha: FloatArray? = null
        var transparent = 0
        for (p in px) if ((p ushr 24) < 40) transparent++
        if (transparent > 0.08f * px.size) alpha = FloatArray(px.size) { (px[it] ushr 24) / 255f }
        // 2) segmentação de pessoa do ML Kit
        if (alpha == null) {
            alpha = runCatching {
                val seg = Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).enableRawSizeMask().build())
                try {
                    val m = seg.process(InputImage.fromBitmap(b, 0)).await()
                    val mw = m.width; val mh = m.height
                    if (mw != w || mh != h) null else {
                        val buf = m.buffer; buf.rewind()
                        val a = FloatArray(w * h) { val c = buf.float; ((c - 0.35f) / 0.4f).coerceIn(0f, 1f) }
                        var fg = 0; for (v in a) if (v > 0.5f) fg++
                        if (fg.toFloat() / a.size in 0.04f..0.97f) a else null
                    }
                } finally { seg.close() }
            }.getOrNull()
        }
        // 3) (dentro de fit) por cor, se o fundo é liso
        // rosto pelo detector do ML Kit
        val face = runCatching {
            val det = FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build())
            try {
                val fs = det.process(InputImage.fromBitmap(b, 0)).await()
                fs.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }?.boundingBox?.let { floatArrayOf(it.exactCenterX(), it.exactCenterY(), it.width().toFloat()) }
            } finally { det.close() }
        }.getOrNull()
        val f = fit(px, w, h, s, alpha, face, faceFromDetector = face != null)
        b.recycle()
        return Bitmap.createBitmap(f.px, s, s, Bitmap.Config.ARGB_8888)
    }
}
