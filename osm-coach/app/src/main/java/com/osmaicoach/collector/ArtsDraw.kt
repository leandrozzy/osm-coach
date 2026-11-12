package com.osmaicoach.collector

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

/**
 * Acabamento do cartaz (1080×1350) desenhado pelo app sobre a CENA gerada pela IA (`ArtsAi`): escudo(s) em anel, faixa dourada, texto grande em ouro,
 * linha de dados, placa e rodapé. Tudo que é texto/número/escudo vem do app (nunca da IA), então sai sempre certo. Prototipado em Skia (mesma engine do
 * Canvas do Android); não visto no aparelho.
 */
internal object ArtsDraw {
    const val W = 1080f
    const val H = 1350f

    class Theme(val bg: Int, val accent: Int, val kit: Int)

    private fun argb(a: Int, r: Int, g: Int, b: Int) = Color.argb(a, r, g, b)
    private fun white(a: Int) = Color.argb(a, 255, 255, 255)
    private fun black(a: Int) = Color.argb(a, 0, 0, 0)
    private fun shade(c: Int, f: Float) = Color.argb(Color.alpha(c), min(255, (Color.red(c) * f).toInt()), min(255, (Color.green(c) * f).toInt()), min(255, (Color.blue(c) * f).toInt()))
    private fun withA(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    private val GOLD = intArrayOf(argb(255, 255, 244, 176), argb(255, 246, 201, 69), argb(255, 214, 160, 30), argb(255, 255, 224, 138), argb(255, 168, 112, 10))
    private val GPOS = floatArrayOf(0f, 0.28f, 0.52f, 0.7f, 1f)

    private fun P(color: Int? = null, shader: Shader? = null, stroke: Float = 0f, blur: Float = 0f): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (color != null) p.color = color
        if (shader != null) p.shader = shader
        if (stroke > 0f) { p.style = Paint.Style.STROKE; p.strokeWidth = stroke; p.strokeJoin = Paint.Join.ROUND; p.strokeCap = Paint.Cap.ROUND }
        if (blur > 0f) p.maskFilter = BlurMaskFilter(blur * 1.7f, BlurMaskFilter.Blur.NORMAL)   // sigma do Skia → raio do Android
        return p
    }

    private fun lin(x0: Float, y0: Float, x1: Float, y1: Float, cols: IntArray, pos: FloatArray? = null) = LinearGradient(x0, y0, x1, y1, cols, pos, Shader.TileMode.CLAMP)

    private fun txtPaint(size: Float, bold: Boolean, skew: Float, scale: Float): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        p.textSkewX = skew; p.textScaleX = scale; p.textSize = size
        return p
    }

    private fun fitSize(s: String, size0: Float, maxw: Float, skew: Float, scale: Float, bold: Boolean): Float {
        var size = size0
        val p = txtPaint(size, bold, skew, scale)
        while (maxw > 0f && p.measureText(s) > maxw && size > 14f) { size -= 2f; p.textSize = size }
        return size
    }

    /** Texto liso (branco etc.). Devolve a largura. */
    private fun text(c: Canvas, s: String, x0: Float, y: Float, size0: Float, color: Int, maxw: Float = 0f, align: Char = 'l', skew: Float = -0.2f, scale: Float = 0.9f, bold: Boolean = true): Float {
        val size = fitSize(s, size0, maxw, skew, scale, bold)
        val p = txtPaint(size, bold, skew, scale); p.color = color
        val w = p.measureText(s)
        c.drawText(s, when (align) { 'c' -> x0 - w / 2f; 'r' -> x0 - w; else -> x0 }, y, p)
        return w
    }

    /** Texto em ouro (degradê metálico + contorno fino + sombra). Devolve a largura. */
    private fun gold(c: Canvas, s: String, x0: Float, y: Float, size0: Float, maxw: Float = 0f, align: Char = 'l', skew: Float = -0.12f, scale: Float = 0.9f): Float {
        val size = fitSize(s, size0, maxw, skew, scale, true)
        val w = txtPaint(size, true, skew, scale).measureText(s)
        val x = when (align) { 'c' -> x0 - w / 2f; 'r' -> x0 - w; else -> x0 }
        val sh = txtPaint(size, true, skew, scale); sh.color = black(150); sh.maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)
        c.drawText(s, x + 4f, y + 6f, sh)
        // borda escura por cópias deslocadas (contorno de verdade mostraria as sobreposições internas da fonte)
        val ed = txtPaint(size, true, skew, scale); ed.color = argb(255, 90, 45, 0)
        for ((dx, dy) in listOf(-2f to 0f, 2f to 0f, 0f to -2f, 0f to 2f)) c.drawText(s, x + dx, y + dy, ed)
        val f = txtPaint(size, true, skew, scale); f.shader = lin(0f, y - size * 0.78f, 0f, y + size * 0.05f, GOLD, GPOS)
        c.drawText(s, x, y, f)
        return w
    }

    private fun image(c: Canvas, b: Bitmap?, box: RectF) {
        if (b == null) return
        val s = min(box.width() / b.width, box.height() / b.height)
        val w = b.width * s; val h = b.height * s
        val l = box.left + (box.width() - w) / 2f; val t = box.top + (box.height() - h) / 2f
        c.drawBitmap(b, null, RectF(l, t, l + w, t + h), P())
    }

    private fun ring(c: Canvas, cx: Float, cy: Float, crest: Bitmap?, th: Theme, r: Float = 104f) {
        // fundo branco (qualquer escudo aparece com as próprias cores), aro dourado fino e escudo GRANDE ocupando o anel
        c.drawCircle(cx, cy, r + 6f, P(color = black(120), blur = 6f))
        c.drawCircle(cx, cy, r, P(color = Color.WHITE))
        c.drawCircle(cx, cy, r - 5f, P(shader = lin(0f, cy - r, 0f, cy + r, GOLD, GPOS), stroke = 5f))
        image(c, crest, RectF(cx - 0.80f * r, cy - 0.84f * r, cx + 0.80f * r, cy + 0.80f * r))
    }

    /** Cena preenchendo o cartaz (corte central) + vinheta + véu escuro (na cor do time) a partir de [fade]. */
    private fun sceneBase(c: Canvas, th: Theme, scene: Bitmap, fade: Float) {
        val s = max(W / scene.width, H / scene.height)
        val sw = W / s; val sh = H / s
        val sx = (scene.width - sw) / 2f; val sy = (scene.height - sh) / 2f
        c.drawBitmap(scene, android.graphics.Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt()), RectF(0f, 0f, W, H), P())
        c.drawRect(0f, 0f, W, H, P(shader = RadialGradient(W / 2f, H * 0.42f, H * 0.78f, intArrayOf(black(0), black(150)), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)))
        val veil = shade(th.bg, 0.30f)
        c.drawRect(0f, fade, W, H, P(shader = lin(0f, fade, 0f, H, intArrayOf(withA(veil, 0), withA(veil, 205), withA(veil, 245)), floatArrayOf(0f, 0.35f, 1f))))
    }

    class Row(val label: String, val value: String, val frac: Float? = null)

    private fun banner(c: Canvas, txt: String, y: Float, h: Float, dark: Int) {
        val rnd = java.util.Random(4)
        val x0 = 40f; val x1 = W - 40f
        val p = Path(); p.moveTo(x0, y + 10f)
        for (i in 0..13) p.lineTo(x0 + (x1 - x0) * i / 13f, y + (rnd.nextFloat() * 18f - 9f))
        for (i in 0..7) p.lineTo(x1 + (rnd.nextFloat() * 20f - 6f), y + h * i / 7f + (rnd.nextFloat() * 8f - 4f))
        for (i in 13 downTo 0) p.lineTo(x0 + (x1 - x0) * i / 13f, y + h + (rnd.nextFloat() * 18f - 9f))
        for (i in 7 downTo 0) p.lineTo(x0 + (rnd.nextFloat() * 20f - 14f), y + h * i / 7f + (rnd.nextFloat() * 8f - 4f))
        p.close()
        c.drawPath(p, P(color = black(160), blur = 12f))
        c.drawPath(p, P(shader = lin(0f, y, 0f, y + h, GOLD, GPOS)))
        for (i in 0 until 7) {
            val yy = y + 14f + i * (h - 28f) / 6f
            c.drawLine(60f, yy, W - 60f, yy + (rnd.nextFloat() * 6f - 3f), P(color = argb(14 + rnd.nextInt(20), 120, 70, 0), stroke = 2f))
        }
        text(c, txt, W / 2f, y + h * 0.70f, 70f, dark, maxw = W - 140f, align = 'c')
    }

    private fun laurel(c: Canvas, cx: Float, cy: Float, flip: Float) {
        for (i in 0 until 7) {
            val x = cx + flip * (i * 3f) + flip * 6f; val y = cy - 40f + i * 14f
            c.save(); c.translate(x, y); c.rotate(flip * (30f - i * 8f))
            c.drawOval(RectF(-4f, -14f, 4f, 14f), P(shader = lin(0f, -14f, 0f, 14f, GOLD, GPOS)))
            c.restore()
        }
    }

    /**
     * Cartaz final. [scene] = cena da IA; [big] = texto grande em ouro (nome do jogador, placar, número…); [plate] = (rótulo, valor) da placa, ou null.
     * [oppCrest] só na goleada (escudo do rival no canto direito).
     */
    fun poster(
        th: Theme, scene: Bitmap, crest: Bitmap?, oppCrest: Bitmap?, banner: String, big: String, info: String,
        plate: Pair<String, String>?, footer: String, versus: Boolean = false, heroCrest: Boolean = false
    ): Bitmap {
        val out = Bitmap.createBitmap(W.toInt(), H.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        sceneBase(c, th, scene, 690f)
        if (versus) {
            // dois escudos grandes com "VS" no meio (dia de jogo)
            ring(c, 250f, 500f, crest, th, 150f); ring(c, W - 250f, 500f, oppCrest, th, 150f)
            gold(c, "VS", W / 2f, 560f, 130f, align = 'c', skew = -0.1f)
        } else if (heroCrest) {
            // sem pessoa na cena: o escudo do time é o foco, grande no centro, com brilho
            c.drawCircle(540f, 400f, 250f, P(color = withA(th.accent, 90), blur = 50f))
            ring(c, 540f, 400f, crest, th, 190f)
        } else {
            ring(c, 140f, 140f, crest, th, 120f)
            if (oppCrest != null) ring(c, W - 140f, 140f, oppCrest, th, 120f)
        }
        banner(c, banner, 704f, 104f, shade(th.bg, 0.45f))
        gold(c, big.uppercase(), W / 2f, 990f, 200f, maxw = W - 100f, align = 'c', skew = -0.08f, scale = 0.86f)
        text(c, info, W / 2f, 1056f, 46f, Color.WHITE, maxw = W - 120f, align = 'c', skew = 0f, scale = 1f, bold = false)
        c.drawLine(120f, 1082f, W - 120f, 1082f, P(shader = lin(120f, 0f, W - 120f, 0f, intArrayOf(argb(0, 255, 214, 100), argb(255, 255, 214, 100), argb(0, 255, 214, 100)), floatArrayOf(0f, 0.5f, 1f)), stroke = 3f))
        if (plate != null) {
            val (lab, v) = plate
            val lp = txtPaint(56f, true, -0.12f, 0.9f); val vp = txtPaint(76f, true, -0.1f, 0.9f)
            val total = lp.measureText(lab) + 28f + vp.measureText(v)
            val half = max(200f, total / 2f + 56f)
            val l = W / 2f - half; val r = W / 2f + half
            val plateP = Path()
            plateP.moveTo(l + 36f, 1108f); plateP.lineTo(r - 36f, 1108f); plateP.lineTo(r, 1156f); plateP.lineTo(r - 36f, 1204f); plateP.lineTo(l + 36f, 1204f); plateP.lineTo(l, 1156f); plateP.close()
            c.drawPath(plateP, P(color = black(150), blur = 10f))
            c.drawPath(plateP, P(shader = lin(0f, 1108f, 0f, 1204f, intArrayOf(shade(th.bg, 0.55f), shade(th.bg, 0.25f)))))
            c.drawPath(plateP, P(shader = lin(0f, 1108f, 0f, 1204f, GOLD, GPOS), stroke = 5f))
            val x0 = W / 2f - total / 2f
            gold(c, lab, x0, 1176f, 56f)
            gold(c, v, x0 + lp.measureText(lab) + 28f, 1180f, 76f, skew = -0.1f)
            laurel(c, l - 8f, 1160f, -1f); laurel(c, r + 8f, 1160f, 1f)
        }
        gold(c, footer, W / 2f, 1290f, 60f, maxw = W - 90f, align = 'c', skew = -0.16f, scale = 0.92f)
        return out
    }

    /**
     * Cartaz em lista (craques, lado a lado, momento): linhas em vidro com valor em ouro e barra opcional; [dots] = últimos resultados (V/E/D) em bolinhas.
     */
    fun list(th: Theme, scene: Bitmap, crest: Bitmap?, oppCrest: Bitmap?, banner: String, rows: List<Row>, footer: String, dots: String? = null): Bitmap {
        val out = Bitmap.createBitmap(W.toInt(), H.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        sceneBase(c, th, scene, 560f)
        ring(c, 140f, 140f, crest, th, 120f)
        if (oppCrest != null) ring(c, W - 140f, 140f, oppCrest, th, 120f)
        banner(c, banner, 600f, 104f, shade(th.bg, 0.45f))
        var y = 740f
        if (!dots.isNullOrEmpty()) {
            val n = dots.length; val gap = 130f; val x0 = W / 2f - (n - 1) * gap / 2f
            for (i in 0 until n) {
                val col = when (dots[i]) { 'V' -> argb(255, 46, 196, 92); 'E' -> argb(255, 246, 190, 40); else -> argb(255, 226, 60, 60) }
                val cx = x0 + i * gap
                c.drawCircle(cx, y + 44f, 50f, P(color = black(150), blur = 8f))
                c.drawCircle(cx, y + 44f, 46f, P(color = col))
                c.drawCircle(cx, y + 44f, 46f, P(color = Color.WHITE, stroke = 5f))
                text(c, dots[i].toString(), cx, y + 62f, 52f, Color.WHITE, align = 'c', skew = 0f, scale = 1f)
            }
            y += 130f
        }
        val shown = rows.take(5)
        val step = minOf(112f, (1232f - y) / maxOf(1, shown.size))
        val hh = step - 16f
        for (r in shown) {
            val box = RectF(60f, y, W - 60f, y + hh)
            c.drawRoundRect(box, 18f, 18f, P(color = argb(150, 8, 12, 20)))
            c.drawRoundRect(box, 18f, 18f, P(color = white(70), stroke = 2f))
            text(c, r.label, 92f, y + hh * 0.64f, 42f * hh / 96f, Color.WHITE, maxw = 560f, skew = -0.1f, scale = 0.95f)
            gold(c, r.value, W - 92f, y + hh * 0.68f, 50f * hh / 96f, maxw = 380f, align = 'r', skew = -0.1f)
            if (r.frac != null) {
                c.drawRoundRect(RectF(92f, y + hh - 16f, W - 92f, y + hh - 8f), 4f, 4f, P(color = white(70)))
                c.drawRoundRect(RectF(92f, y + hh - 16f, 92f + (W - 184f) * r.frac.coerceIn(0f, 1f), y + hh - 8f), 4f, 4f, P(shader = lin(92f, 0f, W - 92f, 0f, GOLD, GPOS)))
            }
            y += step
        }
        gold(c, footer, W / 2f, 1290f, 60f, maxw = W - 90f, align = 'c', skew = -0.16f, scale = 0.92f)
        return out
    }
}
