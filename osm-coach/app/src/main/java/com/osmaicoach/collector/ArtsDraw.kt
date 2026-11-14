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

    class NewsPlayer(val name: String, val line: String, val stats: String, val portrait: Bitmap?)

    private fun wrap(s: String, p: Paint, maxw: Float): List<String> {
        val out = ArrayList<String>(); var cur = ""
        for (w in s.split(" ")) {
            val t = if (cur.isEmpty()) w else "$cur $w"
            if (p.measureText(t) <= maxw || cur.isEmpty()) cur = t else { out.add(cur); cur = w }
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    /**
     * Capa de jornal (1600×1120, horizontal): título do jornal com o nome do time, manchete, subtítulo, até 3 contratados com retrato fictício (IA) e dados lidos,
     * fala do treinador (a única parte inventada, marcada) e rodapé do próximo jogo. Tudo, exceto a fala e os retratos, vem de dados já lidos do jogo.
     */
    fun news(
        th: Theme, crest: Bitmap?, paper: String, headA: String, headB: String, sub: String, subLine: String, side: List<String>, dateLine: String,
        players: List<NewsPlayer>, quoteHead: String, quote: String, footer: String
    ): Bitmap {
        val w = 1600f; val h = 1120f
        val out = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val ink = shade(th.bg, 0.95f).let { Color.rgb(min(255, Color.red(it) + 0), min(255, Color.green(it) + 12), min(255, Color.blue(it) + 30)) }
        val red = shade(th.accent, 0.72f)
        c.drawColor(argb(255, 243, 240, 232))
        c.drawRect(0f, 0f, w, h, P(shader = RadialGradient(w / 2f, h / 2f, w * 0.75f, intArrayOf(white(0), argb(70, 150, 130, 100)), floatArrayOf(0.6f, 1f), Shader.TileMode.CLAMP)))
        val rnd = java.util.Random(7)
        for (i in 0 until 900) c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, 1f + rnd.nextFloat() * 1.5f, P(color = argb(10 + rnd.nextInt(14), 90, 70, 40)))
        // título do jornal
        val tp = txtPaint(1f, true, 0f, 0.72f)
        var ts = 150f; tp.textSize = ts
        while (tp.measureText(paper) > 800f && ts > 40f) { ts -= 4f; tp.textSize = ts }
        tp.color = ink; c.drawText(paper, 56f, 140f, tp)
        c.drawLine(870f, 40f, 870f, 150f, P(color = argb(120, 60, 60, 60), stroke = 2f))
        ring(c, 960f, 92f, crest, th, 66f)
        c.drawLine(1050f, 40f, 1050f, 150f, P(color = argb(120, 60, 60, 60), stroke = 2f))
        text(c, dateLine, 1070f, 62f, 21f, argb(255, 30, 30, 30), maxw = 500f, skew = 0f, scale = 0.9f)
        side.take(3).forEachIndexed { i, t ->
            val sp = txtPaint(17f, false, 0f, 0.95f); sp.color = argb(255, 40, 40, 40)
            wrap(t, sp, 160f).take(4).forEachIndexed { j, ln -> c.drawText(ln, 1070f + i * 175f, 92f + j * 21f, sp) }
        }
        c.drawLine(40f, 168f, w - 40f, 168f, P(color = ink, stroke = 3f))
        // manchete
        val hp = txtPaint(1f, true, 0f, 0.74f)
        var hs = 118f; hp.textSize = hs
        while (hp.measureText("$headA $headB") > w - 100f && hs > 40f) { hs -= 4f; hp.textSize = hs }
        val wa = hp.measureText("$headA ")
        val total = wa + hp.measureText(headB)
        val hx = (w - total) / 2f
        hp.color = ink; c.drawText("$headA ", hx, 275f, hp)
        hp.color = red; c.drawText(headB, hx + wa, 275f, hp)
        // subtítulo
        val sp2 = txtPaint(46f, true, 0f, 0.86f); sp2.color = argb(255, 20, 20, 20)
        var y = 330f
        for (ln in wrap(sub, sp2, w - 200f).take(2)) { c.drawText(ln, (w - sp2.measureText(ln)) / 2f, y, sp2); y += 50f }
        val lp = txtPaint(27f, true, 0f, 0.92f); lp.color = ink
        for (ln in wrap(subLine, lp, w - 300f).take(2)) { c.drawText(ln, (w - lp.measureText(ln)) / 2f, y + 6f, lp); y += 32f }
        // colunas dos contratados
        val top = y + 30f
        val n = max(1, players.size); val colW = (w - 120f) / n
        players.forEachIndexed { i, p ->
            val cx = 60f + colW * i + colW / 2f
            val r = 95f; val cy = top + r + 6f
            if (i > 0) c.drawLine(60f + colW * i, top, 60f + colW * i, top + 400f, P(color = argb(110, 60, 60, 60), stroke = 2f))
            c.drawCircle(cx, cy, r, P(shader = lin(0f, cy - r, 0f, cy + r, intArrayOf(withA(th.kit, 255), shade(th.bg, 0.8f)))))
            c.save(); val clip = Path(); clip.addCircle(cx, cy, r, Path.Direction.CW); c.clipPath(clip)
            if (p.portrait != null) c.drawBitmap(p.portrait, null, RectF(cx - r, cy - r, cx + r, cy + r), P())
            else text(c, p.name.trim().first().uppercaseChar().toString(), cx, cy + 36f, 110f, Color.WHITE, align = 'c', skew = 0f, scale = 1f)
            c.restore()
            c.drawCircle(cx, cy, r, P(color = argb(160, 255, 255, 255), stroke = 4f))
            val np = txtPaint(1f, true, 0f, 0.8f); var ns = 52f; np.textSize = ns
            val nm = p.name.uppercase()
            while (np.measureText(nm) > colW - 40f && ns > 24f) { ns -= 2f; np.textSize = ns }
            np.color = argb(255, 10, 10, 10); c.drawText(nm, cx - np.measureText(nm) / 2f, cy + r + 62f, np)
            val ip = txtPaint(26f, true, 0f, 0.88f); ip.color = argb(255, 30, 30, 30)
            var ln = p.line
            while (ip.measureText(ln) > colW - 30f && ip.textSize > 15f) ip.textSize -= 1f
            c.drawText(ln, cx - ip.measureText(ln) / 2f, cy + r + 98f, ip)
            val pp = txtPaint(25f, true, 0f, 0.9f); pp.color = Color.WHITE
            while (pp.measureText(p.stats) > colW - 70f && pp.textSize > 14f) pp.textSize -= 1f
            val pw = pp.measureText(p.stats) + 36f
            val pr = RectF(cx - pw / 2f, cy + r + 118f, cx + pw / 2f, cy + r + 156f)
            c.drawRoundRect(pr, 6f, 6f, P(color = ink)); c.drawText(p.stats, pr.left + 18f, pr.bottom - 11f, pp)
        }
        val qy = top + 400f
        c.drawLine(40f, qy - 20f, w - 40f, qy - 20f, P(color = argb(160, 60, 60, 60), stroke = 2f))
        val qh = txtPaint(1f, true, 0f, 0.76f); var qs = 56f; qh.textSize = qs
        while (qh.measureText(quoteHead) > w - 400f && qs > 30f) { qs -= 2f; qh.textSize = qs }
        qh.color = ink; c.drawText(quoteHead, 220f, qy + 42f, qh)
        val qp = txtPaint(26f, false, 0f, 0.95f); qp.color = argb(255, 25, 25, 25)
        var qyy = qy + 82f
        val lines = wrap("“$quote”", qp, w - 440f)
        for ((i, ln) in lines.take(3).withIndex()) { c.drawText(if (i == 2 && lines.size > 3) "$ln…" else ln, 220f, qyy, qp); qyy += 32f }
        val fp = txtPaint(17f, false, 0.0f, 1f); fp.color = argb(255, 110, 100, 90)
        c.drawText("declaração fictícia", 60f, qy + 42f, fp)
        // rodapé
        val bar = RectF(40f, h - 66f, w - 40f, h - 18f)
        c.drawRoundRect(bar, 8f, 8f, P(color = ink))
        text(c, footer, w / 2f, bar.bottom - 14f, 31f, Color.WHITE, maxw = w - 100f, align = 'c', skew = 0f, scale = 0.92f)
        return out
    }

    /**
     * Capa de jornal pós-jogo (1600×1120): manchete e texto escritos pela IA com os fatos do jogo, foto da cena (IA, fictícia), placar com os dois escudos
     * (quem joga em casa à esquerda), gols lidos e rodapé. Placar, escudos, nomes dos gols e rodapé vêm dos dados lidos.
     */
    fun matchNews(
        th: Theme, crest: Bitmap?, paper: String, dateLine: String, headline: String, sub: String, body: List<String>, photo: Bitmap?,
        leftCrest: Bitmap?, rightCrest: Bitmap?, leftName: String, rightName: String, score: String, goals: List<String>, footer: String
    ): Bitmap {
        val w = 1600f; val h = 1120f
        val out = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val ink = shade(th.bg, 0.95f).let { Color.rgb(min(255, Color.red(it)), min(255, Color.green(it) + 12), min(255, Color.blue(it) + 30)) }
        c.drawColor(argb(255, 243, 240, 232))
        c.drawRect(0f, 0f, w, h, P(shader = RadialGradient(w / 2f, h / 2f, w * 0.75f, intArrayOf(white(0), argb(70, 150, 130, 100)), floatArrayOf(0.6f, 1f), Shader.TileMode.CLAMP)))
        val rnd = java.util.Random(11)
        for (i in 0 until 900) c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, 1f + rnd.nextFloat() * 1.5f, P(color = argb(10 + rnd.nextInt(14), 90, 70, 40)))
        // título do jornal
        val tp = txtPaint(1f, true, 0f, 0.72f)
        var ts = 150f; tp.textSize = ts
        while (tp.measureText(paper) > 800f && ts > 40f) { ts -= 4f; tp.textSize = ts }
        tp.color = ink; c.drawText(paper, 56f, 140f, tp)
        c.drawLine(870f, 40f, 870f, 150f, P(color = argb(120, 60, 60, 60), stroke = 2f))
        ring(c, 960f, 92f, crest, th, 66f)
        c.drawLine(1050f, 40f, 1050f, 150f, P(color = argb(120, 60, 60, 60), stroke = 2f))
        text(c, dateLine, 1070f, 80f, 22f, argb(255, 30, 30, 30), maxw = 500f, skew = 0f, scale = 0.9f)
        text(c, "EDIÇÃO PÓS-JOGO", 1070f, 112f, 22f, ink, maxw = 500f, skew = 0f, scale = 0.9f)
        c.drawLine(40f, 168f, w - 40f, 168f, P(color = ink, stroke = 3f))
        // manchete (até 2 linhas) e linha fina
        val hp = txtPaint(88f, true, 0f, 0.74f); hp.color = ink
        var y = 252f
        val hl = wrap(headline.uppercase(), hp, w - 120f).take(2)
        for (ln in hl) { c.drawText(ln, (w - hp.measureText(ln)) / 2f, y, hp); y += 90f }
        val sp = txtPaint(34f, true, 0f, 0.88f); sp.color = argb(255, 25, 25, 25)
        y += -50f
        for (ln in wrap(sub, sp, w - 240f).take(2)) { c.drawText(ln, (w - sp.measureText(ln)) / 2f, y, sp); y += 40f }
        val top = max(y + 10f, 400f)
        // foto (cena) à esquerda
        val pr = RectF(50f, top, 620f, top + 300f)
        c.drawRect(RectF(pr.left - 3f, pr.top - 3f, pr.right + 3f, pr.bottom + 3f), P(color = ink))
        if (photo != null) {
            val sw = photo.width.toFloat(); val sh = sw * pr.height() / pr.width()
            val sy = ((photo.height - sh) * 0.3f).coerceAtLeast(0f)
            c.drawBitmap(photo, android.graphics.Rect(0, sy.toInt(), photo.width, min(photo.height, (sy + sh).toInt())), pr, P())
        } else c.drawRect(pr, P(color = argb(255, 40, 50, 70)))
        val cp = txtPaint(17f, false, 0.1f, 1f); cp.color = argb(255, 90, 80, 70)
        c.drawText("Imagem ilustrativa gerada por IA", pr.left, pr.bottom + 22f, cp)
        // placar com os dois escudos
        val by = pr.bottom + 40f
        val box = RectF(50f, by, 620f, h - 80f)
        c.drawRoundRect(box, 10f, 10f, P(color = ink))
        val mid = (box.top + box.bottom) / 2f
        ring(c, 140f, mid - 14f, leftCrest, th, 56f); ring(c, 530f, mid - 14f, rightCrest, th, 56f)
        text(c, score, 335f, mid + 22f, 92f, Color.WHITE, maxw = 190f, align = 'c', skew = 0f, scale = 0.9f)
        text(c, leftName.uppercase(), 140f, box.bottom - 12f, 20f, Color.WHITE, maxw = 170f, align = 'c', skew = 0f, scale = 0.85f)
        text(c, rightName.uppercase(), 530f, box.bottom - 12f, 20f, Color.WHITE, maxw = 170f, align = 'c', skew = 0f, scale = 0.85f)
        // texto da notícia em 2 colunas
        val bp = txtPaint(27f, false, 0f, 0.95f); bp.color = argb(255, 22, 22, 22)
        val colW = 430f; val x1 = 670f; val x2 = 1120f
        val lines = ArrayList<String>()
        for ((i, para) in body.withIndex()) { lines.addAll(wrap(para, bp, colW)); if (i < body.lastIndex) lines.add("") }
        val maxLines = 14
        val perCol = minOf(maxLines, (lines.size + 1) / 2)
        var ly = top + 28f
        for ((i, ln) in lines.take(perCol * 2).withIndex()) {
            val col = i / perCol; val row = i % perCol
            c.drawText(ln, if (col == 0) x1 else x2, ly + row * 36f, bp)
        }
        c.drawLine(1105f, top, 1105f, top + perCol * 36f + 10f, P(color = argb(100, 60, 60, 60), stroke = 2f))
        // gols (lidos do relatório do jogo)
        if (goals.isNotEmpty()) {
            val gy = h - 190f
            c.drawLine(660f, gy - 26f, w - 50f, gy - 26f, P(color = ink, stroke = 2f))
            val gh = txtPaint(26f, true, 0f, 0.85f); gh.color = ink
            c.drawText("GOLS DO JOGO", 670f, gy, gh)
            val gp = txtPaint(23f, false, 0f, 0.95f); gp.color = argb(255, 25, 25, 25)
            goals.take(8).forEachIndexed { i, g ->
                val col = i / 4; val row = i % 4
                c.drawText(g, if (col == 0) 670f else 1120f, gy + 32f + row * 30f, gp)
            }
        }
        val bar = RectF(40f, h - 66f, w - 40f, h - 18f)
        c.drawRoundRect(bar, 8f, 8f, P(color = ink))
        text(c, footer, w / 2f, bar.bottom - 14f, 31f, Color.WHITE, maxw = w - 100f, align = 'c', skew = 0f, scale = 0.92f)
        return out
    }
}
