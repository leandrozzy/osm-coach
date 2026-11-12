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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Desenho das artes (cartaz 1080×1350) feito só com Canvas: fundo de estádio (refletores, bokeh, vinheta), silhueta do jogador com a camisa do
 * time (sem rosto), taça, placar com os dois escudos e cartaz de sequência. Cores vêm do escudo do time (`Arts.palette`).
 * Prototipado em Skia (a mesma engine do Canvas do Android) antes de portar; o desenho não foi visto no aparelho.
 */
internal object ArtsDraw {
    const val W = 1080f
    const val H = 1350f

    class Theme(val bg: Int, val accent: Int, val kit: Int)

    // ------------------------------------------------------------------ utilidades
    private fun argb(a: Int, r: Int, g: Int, b: Int) = Color.argb(a, r, g, b)
    private fun white(a: Int) = Color.argb(a, 255, 255, 255)
    private fun black(a: Int) = Color.argb(a, 0, 0, 0)
    private fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
    private fun shade(c: Int, f: Float) = Color.argb(Color.alpha(c), min(255, (Color.red(c) * f).toInt()), min(255, (Color.green(c) * f).toInt()), min(255, (Color.blue(c) * f).toInt()))
    private fun mix(a: Int, b: Int, t: Float) = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(), (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )

    /** Pincel: cor/shader, contorno (stroke > 0) e desfoque (blur = sigma do Skia; o Android pede raio ≈ 1,7×). */
    private fun P(color: Int? = null, shader: Shader? = null, stroke: Float = 0f, blur: Float = 0f): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (color != null) p.color = color
        if (shader != null) p.shader = shader
        if (stroke > 0f) { p.style = Paint.Style.STROKE; p.strokeWidth = stroke; p.strokeJoin = Paint.Join.ROUND; p.strokeCap = Paint.Cap.ROUND }
        if (blur > 0f) p.maskFilter = BlurMaskFilter(blur * 1.7f, BlurMaskFilter.Blur.NORMAL)
        return p
    }

    private fun lin(x0: Float, y0: Float, x1: Float, y1: Float, cols: IntArray, pos: FloatArray? = null) = LinearGradient(x0, y0, x1, y1, cols, pos, Shader.TileMode.CLAMP)
    private fun rad(cx: Float, cy: Float, r: Float, cols: IntArray, pos: FloatArray? = null) = RadialGradient(cx, cy, r, cols, pos, Shader.TileMode.CLAMP)

    private fun poly(vararg pts: Float): Path {
        val p = Path(); p.moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) { p.lineTo(pts[i], pts[i + 1]); i += 2 }
        p.close(); return p
    }

    /** Texto condensado e inclinado (estilo esportivo). Encolhe até caber em [maxw]. Devolve a largura desenhada. */
    private fun text(
        c: Canvas, s: String, x0: Float, y: Float, size0: Float, color: Int, maxw: Float = 0f, align: Char = 'l',
        skew: Float = -0.2f, scale: Float = 0.9f, bold: Boolean = true, blur: Float = 0f
    ): Float {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        p.textSkewX = skew; p.textScaleX = scale; p.color = color
        if (blur > 0f) p.maskFilter = BlurMaskFilter(blur * 1.7f, BlurMaskFilter.Blur.NORMAL)
        var size = size0
        p.textSize = size
        while (maxw > 0f && p.measureText(s) > maxw && size > 14f) { size -= 2f; p.textSize = size }
        val w = p.measureText(s)
        val x = when (align) { 'c' -> x0 - w / 2f; 'r' -> x0 - w; else -> x0 }
        c.drawText(s, x, y, p)
        return w
    }

    private fun measure(s: String, size: Float, skew: Float = -0.12f, scale: Float = 0.92f): Float {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = Typeface.create("sans-serif", Typeface.BOLD); p.textSkewX = skew; p.textScaleX = scale; p.textSize = size
        return p.measureText(s)
    }

    private fun image(c: Canvas, b: Bitmap?, box: RectF) {
        if (b == null) return
        // mantém a proporção dentro da caixa
        val s = min(box.width() / b.width, box.height() / b.height)
        val w = b.width * s; val h = b.height * s
        val l = box.left + (box.width() - w) / 2f; val t = box.top + (box.height() - h) / 2f
        c.drawBitmap(b, null, RectF(l, t, l + w, t + h), P())
    }

    // ------------------------------------------------------------------ fundo e peças comuns
    private fun background(c: Canvas, th: Theme) {
        c.drawRect(0f, 0f, W, H, P(shader = lin(0f, 0f, 0f, H, intArrayOf(shade(th.bg, 1.15f), shade(th.bg, 0.55f), shade(th.bg, 0.20f)))))
        // fachos dos refletores
        val beams = listOf(floatArrayOf(150f, 0.34f, 140f, 70f), floatArrayOf(400f, 0.13f, 170f, 52f), floatArrayOf(680f, -0.13f, 170f, 52f), floatArrayOf(930f, -0.34f, 140f, 70f))
        for (b in beams) {
            c.save()
            c.translate(b[0], -60f)
            c.rotate(Math.toDegrees(b[1].toDouble()).toFloat())
            c.drawPath(poly(-16f, 0f, 16f, 0f, b[2], H * 1.15f, -b[2], H * 1.15f), P(shader = lin(0f, 0f, 0f, H * 0.95f, intArrayOf(white(b[3].toInt()), white(0)))))
            c.restore()
        }
        val r = Color.red(th.accent); val g = Color.green(th.accent); val bl = Color.blue(th.accent)
        c.drawRect(0f, 0f, W, H, P(shader = rad(W * 0.5f, H * 0.56f, W * 0.66f, intArrayOf(argb(135, r, g, bl), argb(0, r, g, bl)))))
        val rnd = java.util.Random(11)
        for (i in 0 until 54) {
            val x = rnd.nextFloat() * W; val y = rnd.nextFloat() * H * 0.60f
            val rr = floatArrayOf(5f, 8f, 12f, 18f, 26f)[rnd.nextInt(5)]
            c.drawCircle(x, y, rr, P(color = white(22 + rnd.nextInt(58)), blur = rr * 0.4f))
        }
        // linhas do gramado no chão (perspectiva)
        c.save()
        c.clipRect(0f, H * 0.66f, W, H)
        for (k in 0 until 7) {
            val yy = H * 0.66f + k * k * 9f + 10f
            c.drawLine(0f, yy, W, yy, P(color = white(24), stroke = 2f))
        }
        c.restore()
        c.drawRect(0f, 0f, W, H, P(shader = rad(W / 2f, H * 0.5f, H * 0.80f, intArrayOf(black(0), black(190)), floatArrayOf(0.52f, 1f))))
    }

    private fun topBar(c: Canvas, team: String, comp: String?, crest: Bitmap?, th: Theme) {
        if (crest != null) {
            c.drawCircle(150f, 150f, 118f, P(color = black(90), blur = 22f))
            image(c, crest, RectF(60f, 60f, 240f, 240f))
        }
        text(c, team.uppercase(), 268f, 128f, 70f, Color.WHITE, maxw = W - 268f - 50f)
        text(c, comp ?: "", 268f, 182f, 38f, th.accent, maxw = W - 268f - 50f, bold = false)
        c.drawLine(268f, 208f, 568f, 208f, P(color = th.accent, stroke = 5f))
    }

    private fun banner(c: Canvas, txt: String, th: Theme, y: Float = 262f, h: Float = 112f) {
        val base = th.accent
        val p = poly(24f, y + 16f, W - 150f, y, W - 96f, y + h, 46f, y + h - 14f)
        c.drawPath(p, P(color = black(120), blur = 14f))
        c.drawPath(p, P(shader = lin(0f, y, 0f, y + h, intArrayOf(mix(base, Color.WHITE, 0.35f), base, shade(base, 0.82f)))))
        c.drawPath(poly(40f, y + 20f, W - 160f, y + 4f, W - 150f, y + 14f, 44f, y + 32f), P(color = white(110)))
        val ys = floatArrayOf(y + h * 0.55f, y + h * 0.7f, y + h * 0.82f)
        for (i in ys.indices) c.drawLine(60f + i * 40f, ys[i], W - 190f - i * 60f, ys[i] - 6f, P(color = black(26), stroke = 3f))
        text(c, txt, 76f, y + h * 0.72f, 80f, argb(255, 18, 18, 24), maxw = W - 270f)
    }

    private fun glass(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val r = RectF(x, y, x + w, y + h)
        c.drawRoundRect(r, 18f, 18f, P(color = argb(150, 8, 12, 20)))
        c.drawRoundRect(r, 18f, 18f, P(color = white(70), stroke = 2f))
    }

    private fun footer(c: Canvas, left: String, slot: Int, th: Theme) {
        c.drawRect(0f, H - 96f, W, H, P(color = black(190)))
        c.drawRect(0f, H - 100f, W, H - 96f, P(color = th.accent))
        text(c, left, 40f, H - 32f, 44f, Color.WHITE, maxw = W - 360f)
        text(c, "OSM Coach  •  S$slot", W - 36f, H - 36f, 28f, white(190), align = 'r', skew = 0f, scale = 1f, bold = false)
    }

    private fun shield(c: Canvas, cx: Float, cy: Float, s: Float, f1: Int, f2: Int, ring: Int) {
        val p = Path()
        p.moveTo(cx, cy - s)
        p.cubicTo(cx + s * 0.9f, cy - s * 0.95f, cx + s * 0.95f, cy - s * 0.35f, cx + s * 0.9f, cy + s * 0.1f)
        p.cubicTo(cx + s * 0.8f, cy + s * 0.65f, cx + s * 0.3f, cy + s * 0.9f, cx, cy + s * 1.1f)
        p.cubicTo(cx - s * 0.3f, cy + s * 0.9f, cx - s * 0.8f, cy + s * 0.65f, cx - s * 0.9f, cy + s * 0.1f)
        p.cubicTo(cx - s * 0.95f, cy - s * 0.35f, cx - s * 0.9f, cy - s * 0.95f, cx, cy - s)
        p.close()
        c.drawPath(p, P(color = black(150), blur = 16f))
        c.drawPath(p, P(shader = lin(0f, cy - s, 0f, cy + s, intArrayOf(f1, f2))))
        c.drawPath(p, P(color = ring, stroke = 7f))
    }

    private fun powerBadge(c: Canvas, num: String, label: String, th: Theme) {
        val cx = 900f; val cy = 690f
        c.drawCircle(cx, cy, 120f, P(color = alpha(th.accent, 90), blur = 40f))
        shield(c, cx, cy, 84f, shade(th.bg, 0.55f), shade(th.bg, 0.22f), th.accent)
        text(c, num, cx, cy + 22f, 90f, Color.WHITE, align = 'c', skew = -0.1f)
        text(c, label, cx, cy + 64f, 24f, th.accent, align = 'c', skew = 0f, scale = 1f)
    }

    // ------------------------------------------------------------------ jogador (silhueta com o uniforme do time)
    private fun giantName(c: Canvas, name: String, th: Theme) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = Typeface.create("sans-serif", Typeface.BOLD); p.textSkewX = -0.18f; p.textScaleX = 0.80f
        var size = 300f; p.textSize = size
        while (p.measureText(name) > W * 1.10f && size > 80f) { size -= 6f; p.textSize = size }
        val x = (W - p.measureText(name)) / 2f
        p.color = white(26); c.drawText(name, x, 880f, p)
        val q = Paint(p); q.style = Paint.Style.STROKE; q.strokeWidth = 3f; q.color = alpha(th.accent, 120); c.drawText(name, x, 880f, q)
    }

    private fun figure(c: Canvas, th: Theme, crest: Bitmap?, team: String) {
        val kit = th.kit; val trim = th.accent
        val skin = argb(255, 62, 44, 38)
        val skHi = mix(skin, Color.WHITE, 0.22f); val skLo = shade(skin, 0.55f)
        c.save()
        c.translate(540f, 1350f); c.scale(0.92f, 0.92f); c.translate(-540f, -1350f)
        c.drawOval(RectF(150f, 470f, 930f, 1100f), P(color = alpha(trim, 55), blur = 80f))
        // pescoço
        val neck = Path()
        neck.moveTo(490f, 520f); neck.cubicTo(486f, 580f, 480f, 620f, 450f, 668f); neck.lineTo(630f, 668f); neck.cubicTo(600f, 620f, 594f, 580f, 590f, 520f); neck.close()
        c.drawPath(neck, P(shader = lin(450f, 0f, 630f, 0f, intArrayOf(skHi, skin, skLo))))
        c.drawPath(neck, P(shader = lin(0f, 520f, 0f, 640f, intArrayOf(black(0), black(120)))))
        // cabeça (mandíbula) + orelhas, um pouco mais baixa para o pescoço ficar curto
        c.save(); c.translate(0f, 34f)
        val head = Path()
        head.moveTo(468f, 450f)
        head.cubicTo(466f, 392f, 504f, 366f, 540f, 366f)
        head.cubicTo(578f, 366f, 614f, 392f, 612f, 450f)
        head.cubicTo(612f, 500f, 606f, 536f, 586f, 556f)
        head.cubicTo(572f, 572f, 556f, 578f, 540f, 578f)
        head.cubicTo(524f, 578f, 508f, 572f, 494f, 556f)
        head.cubicTo(474f, 536f, 468f, 500f, 468f, 450f)
        head.close()
        c.drawOval(RectF(458f, 452f, 480f, 500f), P(color = shade(skin, 0.9f)))
        c.drawOval(RectF(600f, 452f, 622f, 500f), P(color = shade(skin, 0.75f)))
        c.drawPath(head, P(shader = lin(468f, 0f, 612f, 0f, intArrayOf(skHi, skin, skLo))))
        c.save(); c.clipPath(head)
        c.drawRect(460f, 520f, 620f, 590f, P(shader = lin(0f, 520f, 0f, 590f, intArrayOf(black(0), black(150)))))
        c.drawOval(RectF(486f, 400f, 560f, 470f), P(color = white(60), blur = 22f))
        c.drawOval(RectF(458f, 380f, 500f, 560f), P(color = white(110), blur = 12f))
        c.restore()
        val hair = Path()
        hair.moveTo(466f, 452f); hair.cubicTo(456f, 386f, 494f, 352f, 544f, 352f)
        hair.cubicTo(594f, 352f, 628f, 388f, 614f, 452f)
        hair.cubicTo(606f, 424f, 586f, 408f, 560f, 404f)
        hair.cubicTo(520f, 400f, 484f, 416f, 466f, 452f); hair.close()
        c.drawPath(hair, P(shader = lin(0f, 352f, 0f, 452f, intArrayOf(argb(255, 70, 60, 64), argb(255, 18, 14, 16)))))
        c.restore()
        // camisa: ombros arredondados, mangas e tronco
        val body = Path()
        body.moveTo(452f, 664f)
        body.cubicTo(380f, 676f, 300f, 684f, 252f, 716f)
        body.cubicTo(214f, 742f, 196f, 800f, 176f, 880f)
        body.lineTo(172f, 930f); body.lineTo(292f, 952f)
        body.cubicTo(298f, 880f, 308f, 840f, 320f, 820f)
        body.lineTo(304f, 1350f); body.lineTo(776f, 1350f); body.lineTo(760f, 820f)
        body.cubicTo(772f, 840f, 782f, 880f, 788f, 952f)
        body.lineTo(908f, 930f); body.lineTo(904f, 880f)
        body.cubicTo(884f, 800f, 866f, 742f, 828f, 716f)
        body.cubicTo(780f, 684f, 700f, 676f, 628f, 664f)
        body.cubicTo(606f, 720f, 474f, 720f, 452f, 664f)
        body.close()
        c.save(); c.clipPath(body)
        c.drawRect(150f, 640f, 930f, 1350f, P(shader = lin(170f, 0f, 910f, 0f, intArrayOf(shade(kit, 0.40f), shade(kit, 0.95f), shade(kit, 1.22f), shade(kit, 0.95f), shade(kit, 0.36f)), floatArrayOf(0f, 0.22f, 0.5f, 0.8f, 1f))))
        c.drawRect(150f, 640f, 930f, 900f, P(shader = lin(0f, 660f, 0f, 900f, intArrayOf(white(70), white(0)))))
        c.drawOval(RectF(420f, 620f, 660f, 780f), P(color = black(120), blur = 24f))
        for (x0 in floatArrayOf(330f, 750f)) c.drawLine(x0, 830f, x0 + (if (x0 < 540f) 10f else -10f), 1350f, P(color = black(46), stroke = 5f))
        val folds = arrayOf(floatArrayOf(390f, 800f, 420f, 900f, 400f, 1040f), floatArrayOf(700f, 800f, 676f, 920f, 700f, 1060f), floatArrayOf(500f, 930f, 486f, 1050f, 500f, 1220f), floatArrayOf(590f, 930f, 606f, 1050f, 592f, 1230f))
        for (f in folds) {
            val fp = Path(); fp.moveTo(f[0], f[1]); fp.quadTo(f[2], f[3], f[4], f[5])
            c.drawPath(fp, P(color = black(64), stroke = 16f, blur = 10f))
            c.drawPath(fp, P(color = white(30), stroke = 6f, blur = 6f))
        }
        if (team.isNotBlank()) text(c, team.uppercase(), 470f, 884f, 44f, white(215), maxw = 300f, align = 'c', skew = -0.12f)
        c.restore()
        // gola
        val vneck = Path()
        vneck.moveTo(458f, 666f); vneck.cubicTo(480f, 722f, 600f, 722f, 622f, 666f); vneck.lineTo(582f, 738f); vneck.lineTo(498f, 738f); vneck.close()
        c.drawPath(vneck, P(shader = lin(0f, 666f, 0f, 738f, intArrayOf(shade(skin, 0.9f), shade(skin, 0.5f)))))
        val collar = Path(); collar.moveTo(450f, 660f); collar.cubicTo(480f, 736f, 600f, 736f, 630f, 660f)
        c.drawPath(collar, P(color = trim, stroke = 20f))
        c.drawPath(collar, P(color = white(110), stroke = 5f))
        c.drawPath(poly(172f, 930f, 176f, 880f, 292f, 900f, 292f, 952f), P(color = trim))
        c.drawPath(poly(908f, 930f, 904f, 880f, 788f, 900f, 788f, 952f), P(color = trim))
        val e1 = Path(); e1.moveTo(452f, 664f); e1.cubicTo(380f, 676f, 300f, 684f, 252f, 716f); e1.cubicTo(214f, 742f, 196f, 800f, 176f, 880f)
        c.drawPath(e1, P(color = alpha(trim, 235), stroke = 7f, blur = 2f))
        val e2 = Path(); e2.moveTo(628f, 664f); e2.cubicTo(700f, 676f, 780f, 684f, 828f, 716f); e2.cubicTo(866f, 742f, 884f, 800f, 904f, 880f)
        c.drawPath(e2, P(color = alpha(trim, 110), stroke = 6f, blur = 2f))
        image(c, crest, RectF(650f, 742f, 744f, 836f))
        // braços cruzados
        fun limb(x0: Float, y0: Float, x1: Float, y1: Float, w: Float) {
            val a = Path(); a.moveTo(x0, y0); a.lineTo(x1, y1)
            c.drawPath(a, P(color = black(130), stroke = w + 22f, blur = 16f))
            val top = min(y0, y1) - w / 2f; val bot = maxOf(y0, y1) + w / 2f
            c.drawPath(a, P(shader = lin(0f, top, 0f, bot, intArrayOf(skHi, skin, skLo)), stroke = w))
            c.drawPath(a, P(color = alpha(trim, 200), stroke = 4f, blur = 2f))
        }
        limb(236f, 940f, 250f, 1010f, 112f)
        limb(844f, 940f, 830f, 1010f, 112f)
        limb(250f, 1018f, 700f, 944f, 100f)
        limb(830f, 1022f, 380f, 960f, 100f)
        for (h in arrayOf(floatArrayOf(662f, 894f), floatArrayOf(362f, 910f))) {
            c.drawOval(RectF(h[0], h[1], h[0] + 96f, h[1] + 96f), P(shader = lin(0f, h[1], 0f, h[1] + 96f, intArrayOf(skHi, skin, skLo))))
            c.drawOval(RectF(h[0] + 10f, h[1] + 6f, h[0] + 60f, h[1] + 40f), P(color = white(60), blur = 8f))
        }
        c.restore()
    }

    // ------------------------------------------------------------------ taça, confete, placar, chama
    private fun trophy(c: Canvas, cx: Float, cy: Float, s: Float, gold1: Int, gold2: Int) {
        c.save(); c.translate(cx, cy); c.scale(s, s)
        c.drawCircle(0f, -40f, 230f, P(color = argb(110, 255, 220, 120), blur = 70f))
        fun g(y0: Float, y1: Float) = P(shader = lin(-120f, y0, 120f, y1, intArrayOf(argb(255, 255, 238, 150), gold1, gold2, shade(gold2, 0.6f)), floatArrayOf(0f, 0.35f, 0.7f, 1f)))
        for (sx in floatArrayOf(-1f, 1f)) {
            val h = Path(); h.moveTo(sx * 118f, -150f); h.cubicTo(sx * 215f, -140f, sx * 215f, -30f, sx * 105f, 20f)
            c.drawPath(h, P(color = gold2, stroke = 26f)); c.drawPath(h, P(color = white(90), stroke = 7f))
        }
        val cup = Path(); cup.moveTo(-130f, -200f); cup.lineTo(130f, -200f); cup.cubicTo(135f, -40f, 80f, 40f, 0f, 60f); cup.cubicTo(-80f, 40f, -135f, -40f, -130f, -200f); cup.close()
        c.drawPath(cup, g(-200f, 60f))
        c.drawOval(RectF(-130f, -218f, 130f, -182f), P(color = shade(gold2, 0.7f)))
        c.drawOval(RectF(-118f, -213f, 118f, -187f), P(color = argb(170, 255, 255, 240)))
        c.drawOval(RectF(-92f, -170f, -58f, 10f), P(color = white(110), blur = 8f))
        c.drawRect(-26f, 55f, 26f, 150f, g(55f, 150f))
        c.drawOval(RectF(-62f, 128f, 62f, 160f), P(color = gold2))
        c.drawPath(poly(-110f, 150f, 110f, 150f, 130f, 215f, -130f, 215f), g(150f, 215f))
        c.drawRect(-130f, 215f, 130f, 236f, P(color = shade(gold2, 0.55f)))
        val st = Path()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) 44f else 18f
            val a = -PI / 2 + i * PI / 5
            val px = (r * cos(a)).toFloat(); val py = (-95 + r * sin(a)).toFloat()
            if (i == 0) st.moveTo(px, py) else st.lineTo(px, py)
        }
        st.close(); c.drawPath(st, P(color = argb(210, 120, 70, 10)))
        c.restore()
    }

    private fun confetti(c: Canvas, th: Theme) {
        val rnd = java.util.Random(3)
        val cols = intArrayOf(th.accent, Color.WHITE, argb(255, 255, 255, 210), mix(th.accent, Color.WHITE, 0.5f))
        for (i in 0 until 90) {
            val x = rnd.nextFloat() * W; val y = rnd.nextFloat() * H * 0.7f
            c.save(); c.translate(x, y); c.rotate(rnd.nextFloat() * 360f)
            c.drawRect(-7f, -3f, 7f, 3f, P(color = alpha(cols[rnd.nextInt(cols.size)], 120 + rnd.nextInt(110))))
            c.restore()
        }
    }

    // ------------------------------------------------------------------ cartazes
    fun player(th: Theme, team: String, comp: String?, bannerTxt: String, first: String, last: String, rows: List<Triple<String, String, Bitmap?>>, strength: String?, footerTxt: String, slot: Int, crest: Bitmap?): Bitmap {
        val out = Bitmap.createBitmap(W.toInt(), H.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        background(c, th)
        giantName(c, last.uppercase(), th)
        figure(c, th, crest, team)
        c.drawRect(0f, 900f, W, H - 100f, P(shader = lin(0f, 900f, 0f, H - 100f, intArrayOf(black(0), black(235)))))
        topBar(c, team, comp, crest, th)
        banner(c, bannerTxt, th)
        if (strength != null) powerBadge(c, strength, "FORÇA", th)
        if (first.isNotBlank()) text(c, first.uppercase(), 48f, 1000f, 60f, Color.WHITE, maxw = W - 100f)
        text(c, last.uppercase(), 44f, 1128f, 168f, th.accent, maxw = W - 90f)
        var x = 40f
        for ((lab, v, flag) in rows) {
            val vw = measure(v, 44f) + (if (flag != null) 64f else 0f)
            val w = maxOf(vw, 120f) + 44f
            if (x + w > W - 30f) break
            glass(c, x, 1160f, w, 96f)
            text(c, lab.uppercase(), x + 22f, 1190f, 22f, white(200), bold = false, skew = 0f)
            var xx = x + 22f
            if (flag != null) { c.drawBitmap(flag, null, RectF(xx, 1204f, xx + 52f, 1240f), P()); xx += 64f }
            text(c, v, xx, 1238f, 44f, Color.WHITE, skew = -0.12f, scale = 0.92f)
            x += w + 16f
        }
        footer(c, footerTxt, slot, th)
        return out
    }

    fun trophyCard(th: Theme, team: String, comp: String?, bannerTxt: String, big: String, sub: String, footerTxt: String, slot: Int, crest: Bitmap?): Bitmap {
        val out = Bitmap.createBitmap(W.toInt(), H.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        background(c, th); confetti(c, th)
        trophy(c, 540f, 700f, 1.55f, th.accent, shade(th.accent, 0.62f))
        c.drawRect(0f, 900f, W, H - 100f, P(shader = lin(0f, 900f, 0f, H - 100f, intArrayOf(black(0), black(235)))))
        topBar(c, team, comp, crest, th); banner(c, bannerTxt, th)
        text(c, big, 540f, 1112f, 150f, th.accent, maxw = W - 80f, align = 'c')
        text(c, sub, 540f, 1172f, 44f, Color.WHITE, maxw = W - 100f, align = 'c', bold = false)
        footer(c, footerTxt, slot, th)
        return out
    }

    fun match(th: Theme, team: String, comp: String?, bannerTxt: String, own: Bitmap?, opp: Bitmap?, a: Int, b: Int, sub: String, rows: List<Pair<String, String>>, footerTxt: String, slot: Int): Bitmap {
        val out = Bitmap.createBitmap(W.toInt(), H.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        background(c, th)
        topBar(c, team, comp, own, th); banner(c, bannerTxt, th)
        val cy = 640f
        for ((cx, img) in listOf(200f to own, 880f to opp)) {
            c.drawCircle(cx, cy, 160f, P(color = black(90), blur = 36f))
            image(c, img, RectF(cx - 125f, cy - 135f, cx + 125f, cy + 125f))
        }
        text(c, a.toString(), 410f, 700f, 260f, Color.WHITE, align = 'c', skew = -0.1f)
        text(c, b.toString(), 670f, 700f, 260f, Color.WHITE, align = 'c', skew = -0.1f)
        text(c, "×", 540f, 660f, 120f, th.accent, align = 'c', skew = 0f)
        text(c, sub, 540f, 960f, 54f, Color.WHITE, maxw = W - 100f, align = 'c')
        var x = 40f
        for ((lab, v) in rows) {
            val w = maxOf(measure(v, 44f), 120f) + 44f
            if (x + w > W - 30f) break
            glass(c, x, 1030f, w, 96f)
            text(c, lab.uppercase(), x + 22f, 1060f, 22f, white(200), bold = false, skew = 0f)
            text(c, v, x + 22f, 1108f, 44f, Color.WHITE, skew = -0.12f, scale = 0.92f)
            x += w + 16f
        }
        footer(c, footerTxt, slot, th)
        return out
    }

    fun stats(th: Theme, team: String, comp: String?, bannerTxt: String, big: String, sub: String, rows: List<Pair<String, String>>, footerTxt: String, slot: Int, crest: Bitmap?): Bitmap {
        val out = Bitmap.createBitmap(W.toInt(), H.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        background(c, th)
        topBar(c, team, comp, crest, th); banner(c, bannerTxt, th)
        val fl = Path()
        fl.moveTo(540f, 450f); fl.cubicTo(640f, 560f, 700f, 640f, 650f, 760f); fl.cubicTo(630f, 810f, 560f, 830f, 540f, 830f)
        fl.cubicTo(500f, 830f, 420f, 800f, 410f, 730f); fl.cubicTo(400f, 660f, 470f, 620f, 480f, 560f); fl.cubicTo(500f, 600f, 520f, 610f, 540f, 450f); fl.close()
        c.drawPath(fl, P(color = argb(160, 255, 120, 20), blur = 40f))
        c.drawPath(fl, P(shader = lin(0f, 450f, 0f, 830f, intArrayOf(argb(255, 255, 220, 80), argb(255, 255, 140, 20), argb(255, 230, 60, 10)))))
        text(c, big, 540f, 700f, 280f, Color.WHITE, align = 'c', skew = -0.1f, maxw = W - 160f)
        text(c, sub, 540f, 900f, 58f, th.accent, maxw = W - 100f, align = 'c')
        var y = 960f
        for ((lab, v) in rows.take(3)) {
            glass(c, 110f, y, W - 220f, 84f)
            text(c, lab.uppercase(), 140f, y + 54f, 30f, white(210), bold = false, skew = 0f)
            text(c, v, W - 140f, y + 60f, 46f, Color.WHITE, align = 'r', maxw = 520f)
            y += 98f
        }
        footer(c, footerTxt, slot, th)
        return out
    }
}
