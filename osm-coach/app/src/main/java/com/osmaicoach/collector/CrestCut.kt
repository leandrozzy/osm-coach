package com.osmaicoach.collector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import java.io.File
import java.io.FileOutputStream

/**
 * Escudos do OSM: todos têm a MESMA moldura de escudo com borda dourada. O recorte acha a borda dourada (caixa exata do escudo) e
 * aplica a silhueta do escudo como máscara: sem o retângulo do cenário em volta. Guardado em `crests/t/<time>.png` (global:
 * o escudo de um clube não muda, só o time que aparece num slot muda).
 */
object CrestCut {
    /** Caixa [x0, y0, x1, y1] (inclusive) do escudo achada pela borda dourada; null se não parece um escudo. */
    fun goldBox(px: IntArray, w: Int, h: Int): IntArray? {
        if (w < 20 || h < 20) return null
        val cols = IntArray(w); val rows = IntArray(h)
        for (y in 0 until h) for (x in 0 until w) {
            if (isGold(px[y * w + x])) { cols[x]++; rows[y]++ }
        }
        fun ext(a: IntArray, min: Int): Pair<Int, Int>? {
            val i0 = a.indexOfFirst { it >= min }; val i1 = a.indexOfLast { it >= min }
            return if (i0 < 0 || i1 <= i0) null else Pair(i0, i1)
        }
        val ex = ext(cols, 3) ?: return null
        val ey = ext(rows, 3) ?: return null
        val bw = ex.second - ex.first + 1; val bh = ey.second - ey.first + 1
        if (bw < 24 || bh < 24) return null
        // o topo da moldura tem um realce BRANCO (não dourado): a parte dourada é mais baixa que o escudo (≈0,84 da largura em vez de 1,04).
        // Largura e base vêm do dourado; a altura real = 1,06 × largura, para cima a partir da base.
        val ratio = bh.toFloat() / bw
        if (ratio < 0.70f || ratio > 1.12f) return null
        val top = (ey.second - Math.round(bw * 1.06f)).coerceAtLeast(0)
        return intArrayOf(ex.first, top, ex.second, ey.second)
    }

    private fun isGold(c: Int): Boolean {
        val r = (c shr 16 and 0xFF) / 255f; val g = (c shr 8 and 0xFF) / 255f; val b = (c and 0xFF) / 255f
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
        if (mx < 0.55f || d <= 0f) return false
        val s = d / mx
        if (s < 0.6f) return false
        val hue = (when (mx) { r -> ((g - b) / d) % 6f; g -> (b - r) / d + 2f; else -> (r - g) / d + 4f } * 60f).let { if (it < 0) it + 360f else it }
        return hue in 28f..58f
    }

    /** Silhueta do escudo (frações da caixa 0..1). */
    private fun shield(w: Float, h: Float): Path = Path().apply {
        moveTo(w * 0.20f, 0f); lineTo(w * 0.80f, 0f)
        quadTo(w, 0f, w, h * 0.17f); lineTo(w, h * 0.50f)
        cubicTo(w, h * 0.78f, w * 0.72f, h * 0.93f, w * 0.5f, h)
        cubicTo(w * 0.28f, h * 0.93f, 0f, h * 0.78f, 0f, h * 0.50f)
        lineTo(0f, h * 0.17f); quadTo(0f, 0f, w * 0.20f, 0f); close()
    }

    /** Escudo recortado e com fundo transparente; null se não achou a moldura dourada. */
    fun cut(crop: Bitmap): Bitmap? {
        val w = crop.width; val h = crop.height
        val px = IntArray(w * h); crop.getPixels(px, 0, w, 0, 0, w, h)
        val box = goldBox(px, w, h) ?: return null
        val x0 = (box[0] - 2).coerceAtLeast(0); val y0 = (box[1] - 2).coerceAtLeast(0)
        val x1 = (box[2] + 3).coerceAtMost(w); val y1 = (box[3] + 3).coerceAtMost(h)
        val bw = x1 - x0; val bh = y1 - y0
        val src = Bitmap.createBitmap(crop, x0, y0, bw, bh)
        val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        cv.drawPath(shield(bw.toFloat(), bh.toFloat()), p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        cv.drawBitmap(src, 0f, 0f, p)
        src.recycle()
        return out
    }

    /** O recorte é MESMO um escudo? O "?" cinza (clube sem escudo) é só um contorno azul apagado: quase nenhum pixel vivo/claro. */
    fun looksLikeCrest(px: IntArray): Boolean {
        if (px.isEmpty()) return false
        var vivid = 0
        for (c in px) {
            val r = (c shr 16 and 0xFF) / 255f; val g = (c shr 8 and 0xFF) / 255f; val b = (c and 0xFF) / 255f
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            val s = if (mx == 0f) 0f else (mx - mn) / mx
            if ((mx >= 0.6f && s >= 0.4f) || (mx >= 0.85f && s <= 0.2f)) vivid++
        }
        return vivid >= px.size * 0.07f   // escudo escuro (verde/preto) tem só ~14% vivo; o "?" tem 0%
    }

    /** Escudo de tamanho conhecido (a moldura pode ser dourada, de cobre ou prata): máscara da silhueta sobre a caixa exata. */
    fun cutFixed(bmp: Bitmap, x: Int, y: Int, w: Int, h: Int): Bitmap? {
        if (w < 20 || h < 20 || x < 0 || y < 0 || x + w > bmp.width || y + h > bmp.height) return null
        val src = Bitmap.createBitmap(bmp, x, y, w, h)
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        if (!looksLikeCrest(px)) { src.recycle(); return null }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        cv.drawPath(shield(w.toFloat(), h.toFloat()), p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        cv.drawBitmap(src, 0f, 0f, p)
        src.recycle()
        return out
    }

    fun dir(filesDir: File): File = File(filesDir, "crests/t2").apply { mkdirs() }

    fun file(filesDir: File, team: String?): File? {
        val k = Txt.key(team ?: "").takeIf { it.length >= 3 } ?: return null
        return File(File(filesDir, "crests/t2"), "$k.png")
    }

    /** Arquivo do escudo do clube: nome exato ou, se faltar, o mais parecido (OCR: "SC"/"5C", acento perdido) com similaridade ≥0,85. */
    fun find(filesDir: File, team: String?): File? {
        val exact = file(filesDir, team) ?: return null
        if (exact.exists()) return exact
        val k = Txt.key(team ?: "")
        val dir = File(filesDir, "crests/t2")
        return dir.listFiles()?.filter { it.name.endsWith(".png") }
            ?.maxByOrNull { Txt.sim(Txt.key(it.name.removeSuffix(".png")), k) }
            ?.takeIf { Txt.sim(Txt.key(it.name.removeSuffix(".png")), k) >= 0.85 }
    }

    /** Recorta a região da captura (frações 0..1), corta o escudo e grava. false se não achou escudo ou já existe. */
    fun save(filesDir: File, bmp: Bitmap, team: String?, cx: Float, cy: Float, w: Float, h: Float, overwrite: Boolean = false, fixedW: Float? = null, fixedH: Float? = null): Boolean {
        val f = file(filesDir, team) ?: return false
        if (f.exists() && !overwrite) return false
        return try {
            val x = ((cx - w / 2) * bmp.width).toInt().coerceIn(0, bmp.width - 2)
            val y = ((cy - h / 2) * bmp.height).toInt().coerceIn(0, bmp.height - 2)
            val ww = (w * bmp.width).toInt().coerceAtMost(bmp.width - x)
            val hh = (h * bmp.height).toInt().coerceAtMost(bmp.height - y)
            if (ww < 20 || hh < 20) return false
            val crop = Bitmap.createBitmap(bmp, x, y, ww, hh)
            // moldura dourada achada = caixa exata; senão (moldura de cobre/prata) usa a caixa fixa da tela, se informada
            val cut = cut(crop) ?: if (fixedW != null && fixedH != null) {
                val fw = (fixedW * bmp.width).toInt(); val fh = (fixedH * bmp.height).toInt()
                cutFixed(bmp, (cx * bmp.width - fw / 2f).toInt(), (cy * bmp.height - fh / 2f).toInt(), fw, fh)
            } else null
            crop.recycle()
            if (cut == null) return false
            f.parentFile?.mkdirs()
            FileOutputStream(f).use { cut.compress(Bitmap.CompressFormat.PNG, 100, it) }
            cut.recycle()
            true
        } catch (e: Exception) { false }
    }
}
