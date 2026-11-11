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
 * Escudos do OSM: todos têm a MESMA moldura de escudo (dourada, de cobre ou prateada). O recorte acha a caixa do escudo (moldura metálica
 * ou, se for prateada, contraste com o fundo), CONFERE tamanho/proporção/cor e aplica a silhueta como máscara. Nada que não pareça
 * escudo é gravado (antes saía texto de placar, números e fundo azul). Guardado em `crests/t4/<time>.png`, global por nome do time.
 */
object CrestCut {
    /**
     * Caixa [x0, y0, x1, y1] (inclusive) do escudo achada pela MOLDURA metálica (dourada ou de cobre: matiz 8–58°, saturação ≥0,5):
     * largura e base vêm da moldura; o topo tem um realce branco (não conta), então a altura real = 1,06 × largura, para cima da base.
     * [expW] (px, 0 = não confere) rejeita caixas de tamanho errado.
     */
    fun goldBox(px: IntArray, w: Int, h: Int, expW: Int = 0): IntArray? {
        if (w < 20 || h < 20) return null
        val cols = IntArray(w); val rows = IntArray(h)
        for (y in 0 until h) for (x in 0 until w) {
            if (isRim(px[y * w + x])) { cols[x]++; rows[y]++ }
        }
        fun ext(a: IntArray, min: Int): Pair<Int, Int>? {
            val i0 = a.indexOfFirst { it >= min }; val i1 = a.indexOfLast { it >= min }
            return if (i0 < 0 || i1 <= i0) null else Pair(i0, i1)
        }
        val ex = ext(cols, 3) ?: return null
        val ey = ext(rows, 3) ?: return null
        val bw = ex.second - ex.first + 1; val bh = ey.second - ey.first + 1
        if (bw < 24 || bh < 20) return null
        val ratio = bh.toFloat() / bw
        if (ratio < 0.60f || ratio > 1.12f) return null
        if (expW > 0 && (bw < expW * 0.7f || bw > expW * 1.3f)) return null
        val top = (ey.second - Math.round(bw * 1.06f)).coerceAtLeast(0)
        return intArrayOf(ex.first, top, ex.second, ey.second)
    }

    private fun isRim(c: Int): Boolean {
        val r = (c shr 16 and 0xFF) / 255f; val g = (c shr 8 and 0xFF) / 255f; val b = (c and 0xFF) / 255f
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
        if (mx < 0.45f || d <= 0f) return false
        if (d / mx < 0.5f) return false
        val hue = (when (mx) { r -> ((g - b) / d) % 6f; g -> (b - r) / d + 2f; else -> (r - g) / d + 4f } * 60f).let { if (it < 0) it + 360f else it }
        return hue in 8f..58f
    }

    /**
     * Moldura prateada (sem cor): acha o escudo pelo CONTRASTE com o fundo (cor mediana da borda do recorte). Só vale com tamanho e
     * proporção de escudo; texto, números e brilhos do estádio não passam.
     */
    fun contrastBox(px: IntArray, w: Int, h: Int, expW: Int, expH: Int): IntArray? {
        if (w < 20 || h < 20 || expW <= 0 || expH <= 0) return null
        val ring = ArrayList<Int>()
        for (x in 0 until w) { ring.add(px[x]); ring.add(px[(h - 1) * w + x]) }
        for (y in 0 until h) { ring.add(px[y * w]); ring.add(px[y * w + w - 1]) }
        fun med(sh: Int) = ring.map { (it shr sh) and 0xFF }.sorted()[ring.size / 2]
        val br = med(16); val bg = med(8); val bb = med(0)
        val cols = IntArray(w); val rows = IntArray(h)
        for (y in 0 until h) for (x in 0 until w) {
            val c = px[y * w + x]
            if (maxOf(Math.abs(((c shr 16) and 0xFF) - br), Math.abs(((c shr 8) and 0xFF) - bg), Math.abs((c and 0xFF) - bb)) > 45) { cols[x]++; rows[y]++ }
        }
        val cMin = (h * 0.25f).toInt().coerceAtLeast(3); val rMin = (w * 0.25f).toInt().coerceAtLeast(3)
        val x0 = cols.indexOfFirst { it >= cMin }; val x1 = cols.indexOfLast { it >= cMin }
        val y0 = rows.indexOfFirst { it >= rMin }; val y1 = rows.indexOfLast { it >= rMin }
        if (x0 < 0 || y0 < 0 || x1 <= x0 || y1 <= y0) return null
        val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
        if (bw < expW * 0.75f || bw > expW * 1.3f || bh < expH * 0.75f || bh > expH * 1.3f) return null
        val r = bw.toFloat() / bh
        if (r < 0.8f || r > 1.1f) return null
        return intArrayOf(x0, y0, x1, y1)
    }

    /** O recorte é MESMO um escudo? Precisa de cor viva (moldura/corpo): o "?" cinza e texto branco de placar têm ~0%. */
    fun looksLikeCrest(px: IntArray): Boolean {
        if (px.isEmpty()) return false
        var vivid = 0
        for (c in px) {
            val r = (c shr 16 and 0xFF) / 255f; val g = (c shr 8 and 0xFF) / 255f; val b = (c and 0xFF) / 255f
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            if (mx >= 0.45f && (mx - mn) / mx >= 0.4f) vivid++
        }
        return vivid >= px.size * 0.08f
    }

    /** Silhueta do escudo (frações da caixa 0..1). */
    private fun shield(w: Float, h: Float): Path = Path().apply {
        // cantos de cima bem arredondados e ponta embaixo: o recorte antigo (retângulo com canto leve) deixava o fundo do cenário nos cantos
        moveTo(w * 0.27f, 0f); lineTo(w * 0.73f, 0f)
        quadTo(w, 0f, w, h * 0.24f); lineTo(w, h * 0.50f)
        cubicTo(w, h * 0.76f, w * 0.70f, h * 0.92f, w * 0.5f, h)
        cubicTo(w * 0.30f, h * 0.92f, 0f, h * 0.76f, 0f, h * 0.50f)
        lineTo(0f, h * 0.24f); quadTo(0f, 0f, w * 0.27f, 0f); close()
    }

    /**
     * Escudo recortado (fundo transparente), ou null se o recorte não é um escudo. [expW]/[expH] (px) = tamanho esperado do escudo na tela:
     * sem eles a caixa só vem da moldura de cor.
     */
    fun cut(crop: Bitmap, expW: Int = 0, expH: Int = 0): Bitmap? {
        val w = crop.width; val h = crop.height
        val px = IntArray(w * h); crop.getPixels(px, 0, w, 0, 0, w, h)
        // moldura de cor com o tamanho esperado; senão contraste (prateada); senão moldura de cor com qualquer tamanho plausível (escudo ampliado/reduzido)
        var tight = false
        val box = goldBox(px, w, h, expW) ?: contrastBox(px, w, h, expW, expH)?.also { tight = true } ?: goldBox(px, w, h, 0) ?: run { lastFail = "sem moldura"; return null }
        val x0 = (box[0] - 1).coerceAtLeast(0); val y0 = (box[1] - 1).coerceAtLeast(0)
        val x1 = (box[2] + 2).coerceAtMost(w); val y1 = (box[3] + 2).coerceAtMost(h)
        val bw = x1 - x0; val bh = y1 - y0
        if (bw < 16 || bh < 16) return null
        val src = Bitmap.createBitmap(crop, x0, y0, bw, bh)
        val spx = IntArray(bw * bh); src.getPixels(spx, 0, bw, 0, 0, bw, bh)
        // escudo preto e branco de moldura prateada quase não tem cor viva: aceita se o contraste achou uma caixa de tamanho e proporção de escudo
        val sizeOk = expW > 0 && expH > 0 && bw >= expW * 0.85f && bw <= expW * 1.2f && bh >= expH * 0.85f && bh <= expH * 1.2f
        if (!looksLikeCrest(spx) && !(tight && sizeOk)) { src.recycle(); lastFail = "sem cor de escudo"; return null }
        val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        cv.drawPath(shield(bw.toFloat(), bh.toFloat()), p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        cv.drawBitmap(src, 0f, 0f, p)
        src.recycle()
        return out
    }

    /** Motivo da última recusa (vai ao log uma vez por time). */
    private val failed = HashSet<String>()
    @Volatile var lastFail: String = ""

    fun dir(filesDir: File): File = File(filesDir, "crests/t4").apply { mkdirs() }

    fun file(filesDir: File, team: String?): File? {
        val k = Txt.key(team ?: "").takeIf { it.length >= 3 } ?: return null
        return File(File(filesDir, "crests/t4"), "$k.png")
    }

    /** Arquivo do escudo do clube: nome exato ou, se faltar, o mais parecido (OCR: "SC"/"5C", acento perdido) com similaridade ≥0,85. */
    fun find(filesDir: File, team: String?): File? {
        val exact = file(filesDir, team) ?: return null
        if (exact.exists()) return exact
        val k = Txt.key(team ?: "")
        val dir = File(filesDir, "crests/t4")
        val all = dir.listFiles()?.filter { it.name.endsWith(".png") } ?: return null
        return all.maxByOrNull { score(Txt.key(it.name.removeSuffix(".png")), k) }
            ?.takeIf { score(Txt.key(it.name.removeSuffix(".png")), k) >= 0.85 }
    }

    /** Parecença de dois nomes de clube: similaridade, ou 0,9 se um é início do outro (nome cortado/quebrado na faixa: "Young Violets" × "Young Violets Austria Wien"). */
    internal fun score(a: String, b: String): Double {
        val s = Txt.sim(a, b)
        val (sh, lg) = if (a.length <= b.length) a to b else b to a
        return if (sh.length >= 7 && lg.startsWith(sh) && sh.length * 2 >= lg.length) maxOf(s, 0.9) else s
    }

    /**
     * Recorta a região da captura (centro [cx],[cy] e tamanho [w]×[h], frações 0..1), acha e corta o escudo (tamanho esperado
     * [expW]×[expH], frações da largura/altura) e grava. false se não achou escudo ou já existe.
     */
    fun save(filesDir: File, bmp: Bitmap, team: String?, cx: Float, cy: Float, w: Float, h: Float, overwrite: Boolean = false, expW: Float = 0f, expH: Float = 0f): Boolean {
        val f = file(filesDir, team) ?: return false
        if (f.exists() && !overwrite) return false
        return try {
            val x = ((cx - w / 2) * bmp.width).toInt().coerceIn(0, bmp.width - 2)
            val y = ((cy - h / 2) * bmp.height).toInt().coerceIn(0, bmp.height - 2)
            val ww = (w * bmp.width).toInt().coerceAtMost(bmp.width - x)
            val hh = (h * bmp.height).toInt().coerceAtMost(bmp.height - y)
            if (ww < 20 || hh < 20) return false
            val crop = Bitmap.createBitmap(bmp, x, y, ww, hh)
            val cut = cut(crop, (expW * bmp.width).toInt(), (expH * bmp.height).toInt())
            crop.recycle()
            if (cut == null) {
                if (failed.add(Txt.key(team ?: ""))) Diag.log("Escudo de ${team}: não gravado (${lastFail})")
                return false
            }
            f.parentFile?.mkdirs()
            FileOutputStream(f).use { cut.compress(Bitmap.CompressFormat.PNG, 100, it) }
            cut.recycle()
            true
        } catch (e: Exception) { false }
    }
}
