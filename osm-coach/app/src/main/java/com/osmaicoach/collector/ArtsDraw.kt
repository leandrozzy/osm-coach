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

    // ------------------------------------------------------------------ cartazes (estilo de revista esportiva: faixas inclinadas na cor do time, escudo SEM aro)

    /** Extras do cartaz: foto real do jogador, tema do rival (dois times), nomes dos dois times e o tipo da arte. */
    class Look(val photo: Bitmap? = null, val oppTh: Theme? = null, val names: Pair<String, String>? = null, val kind: String = "")

    private fun lum(c: Int) = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
    /** Cor de texto legível sobre [bg]. */
    private fun onColor(bg: Int) = if (lum(bg) > 0.62f) argb(255, 12, 16, 28) else Color.WHITE

    /** Escudo SOLTO (sem círculo/aro), centralizado em [cx],[cy] dentro de um quadrado [size], com sombra suave. */
    private fun crestPlain(c: Canvas, b: Bitmap?, cx: Float, cy: Float, size: Float) {
        if (b == null) return
        val s = min(size / b.width, size / b.height)
        val w = b.width * s; val h = b.height * s
        val r = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        runCatching {
            val a = b.extractAlpha()
            c.drawBitmap(a, null, RectF(r.left, r.top + 10f, r.right, r.bottom + 10f), P(color = black(170), blur = 12f))
            a.recycle()
        }
        c.drawBitmap(b, null, r, P())
    }

    /** Faixa inclinada (paralelogramo) com sombra. */
    private fun band2(c: Canvas, x0: Float, y: Float, x1: Float, h: Float, color: Int, skew: Float = 22f) {
        val p = Path(); p.moveTo(x0 + skew, y); p.lineTo(x1, y); p.lineTo(x1 - skew, y + h); p.lineTo(x0, y + h); p.close()
        c.save(); c.translate(0f, 6f); c.drawPath(p, P(color = black(120), blur = 8f)); c.restore()
        c.drawPath(p, P(color = color))
    }

    /** Texto branco com contorno escuro por cópias deslocadas (legível sobre qualquer cena). */
    private fun title(c: Canvas, s: String, x: Float, y: Float, size: Float, maxw: Float = 0f, align: Char = 'c', color: Int = Color.WHITE) {
        for ((dx, dy) in listOf(-3f to 0f, 3f to 0f, 0f to -3f, 0f to 4f, 4f to 5f)) text(c, s, x + dx, y + dy, size, black(190), maxw, align, skew = -0.1f, scale = 0.88f)
        text(c, s, x, y, size, color, maxw, align, skew = -0.1f, scale = 0.88f)
    }

    /** Painel de vidro com rótulo à esquerda e valor à direita. */
    private fun glass(c: Canvas, x0: Float, y: Float, x1: Float, h: Float, edge: Int, label: String, value: String) {
        val box = RectF(x0, y, x1, y + h)
        c.drawRoundRect(box, 20f, 20f, P(color = argb(205, 8, 10, 16)))
        c.drawRoundRect(box, 20f, 20f, P(color = edge, stroke = 3f))
        text(c, label, x0 + 36f, y + h * 0.64f, 28f, argb(255, 225, 228, 236), maxw = (x1 - x0) * 0.46f, skew = -0.05f, scale = 0.95f)
        text(c, value, x1 - 36f, y + h * 0.68f, 40f, Color.WHITE, maxw = (x1 - x0) * 0.5f, align = 'r', skew = -0.1f)
    }

    private fun footBand(c: Canvas, th: Theme, y: Float, txt: String) {
        band2(c, 40f, y, W - 40f, 90f, th.kit)
        text(c, txt, W / 2f, y + 60f, 38f, onColor(th.kit), maxw = W - 160f, align = 'c', skew = -0.15f, scale = 0.92f)
    }

    /**
     * Cartaz final. [scene] = cena da IA (pano de fundo); [big] = texto grande (nome, placar, número…); [plate] = (rótulo, valor) ou null.
     * O layout depende do tipo: dia de jogo (dois times, tela dividida), goleada (placar com os dois escudos), troféu (escudo no topo),
     * herói (escudo grande no centro) e jogador (painel na cor do time; com [Look.photo] a foto real entra num cartão).
     */
    fun poster(
        th: Theme, scene: Bitmap, crest: Bitmap?, oppCrest: Bitmap?, banner: String, big: String, info: String,
        plate: Pair<String, String>?, footer: String, versus: Boolean = false, heroCrest: Boolean = false, look: Look = Look()
    ): Bitmap {
        val out = Bitmap.createBitmap(W.toInt(), H.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val opp = look.oppTh ?: Theme(th.bg, th.accent, th.accent)
        val isTitle = look.kind == "TITLE" || look.kind == "OBJECTIVE"
        when {
            versus -> {
                sceneBase(c, th, scene, 880f)
                val left = Path().apply { moveTo(0f, 0f); lineTo(W / 2f + 90f, 0f); lineTo(W / 2f - 90f, H); lineTo(0f, H); close() }
                val right = Path().apply { moveTo(W / 2f + 90f, 0f); lineTo(W, 0f); lineTo(W, H); lineTo(W / 2f - 90f, H); close() }
                c.drawPath(left, P(color = withA(th.kit, 85))); c.drawPath(right, P(color = withA(opp.kit, 85)))
                c.drawLine(W / 2f + 90f, 0f, W / 2f - 90f, H, P(color = white(230), blur = 10f, stroke = 12f))
                c.drawLine(W / 2f + 90f, 0f, W / 2f - 90f, H, P(color = Color.WHITE, stroke = 6f))
                title(c, banner, W / 2f, 96f, 70f, maxw = W - 120f)
                text(c, info, W / 2f, 150f, 32f, argb(255, 255, 214, 120), maxw = W - 120f, align = 'c', skew = -0.1f)
                crestPlain(c, crest, 235f, 640f, 320f); crestPlain(c, oppCrest, W - 235f, 640f, 320f)
                title(c, "X", W / 2f, 720f, 130f)
                val n = look.names
                band2(c, 30f, 850f, W / 2f + 36f, 100f, th.kit); band2(c, W / 2f - 36f, 850f, W - 30f, 100f, opp.kit)
                if (n != null) {
                    text(c, n.first.uppercase(), (30f + W / 2f + 36f) / 2f, 915f, 38f, onColor(th.kit), maxw = W / 2f - 110f, align = 'c', skew = -0.12f)
                    text(c, n.second.uppercase(), (W / 2f - 36f + W - 30f) / 2f, 915f, 38f, onColor(opp.kit), maxw = W / 2f - 110f, align = 'c', skew = -0.12f)
                }
                title(c, big, W / 2f, 1060f, 80f, maxw = W - 140f)
                if (plate != null) text(c, plate.first + "  " + plate.second, W / 2f, 1130f, 40f, Color.WHITE, maxw = W - 100f, align = 'c', skew = -0.08f)
                footBand(c, th, 1200f, footer)
            }
            oppCrest != null -> {   // goleada: placar com os dois escudos
                sceneBase(c, th, scene, 700f)
                title(c, banner, W / 2f, 130f, 120f, maxw = W - 100f)
                band2(c, 160f, 165f, W - 160f, 62f, th.kit)
                text(c, info, W / 2f, 208f, 30f, onColor(th.kit), maxw = W - 380f, align = 'c', skew = -0.12f)
                crestPlain(c, crest, 190f, 400f, 240f); crestPlain(c, oppCrest, W - 190f, 400f, 240f)
                title(c, big, W / 2f, 470f, 180f, maxw = 560f)
                if (plate != null) glass(c, 90f, 1020f, W - 90f, 90f, th.kit, plate.first, plate.second)
                footBand(c, th, 1150f, footer)
            }
            isTitle -> {
                sceneBase(c, th, scene, 700f)
                crestPlain(c, crest, W / 2f, 190f, 250f)
                band2(c, 90f, 380f, W - 90f, 84f, th.kit)
                text(c, banner, W / 2f, 440f, 44f, onColor(th.kit), maxw = W - 240f, align = 'c', skew = -0.12f)
                gold(c, big.uppercase(), W / 2f, 930f, 170f, maxw = W - 100f, align = 'c', skew = -0.08f, scale = 0.86f)
                text(c, info, W / 2f, 1000f, 38f, Color.WHITE, maxw = W - 120f, align = 'c', skew = 0f, scale = 1f, bold = false)
                if (plate != null) glass(c, 90f, 1040f, W - 90f, 90f, th.kit, plate.first, plate.second)
                footBand(c, th, 1160f, footer)
            }
            heroCrest -> {
                sceneBase(c, th, scene, 560f)
                c.drawCircle(W / 2f, 360f, 280f, P(color = withA(th.kit, 80), blur = 60f))
                crestPlain(c, crest, W / 2f, 360f, 420f)
                title(c, big.uppercase(), W / 2f, 800f, 220f, maxw = W - 80f)
                band2(c, 60f, 850f, W - 60f, 88f, th.kit)
                text(c, banner, W / 2f, 912f, 44f, onColor(th.kit), maxw = W - 200f, align = 'c', skew = -0.12f)
                text(c, info, W / 2f, 1000f, 38f, Color.WHITE, maxw = W - 120f, align = 'c', skew = 0f, scale = 1f, bold = false)
                if (plate != null) glass(c, 90f, 1040f, W - 90f, 90f, th.kit, plate.first, plate.second)
                footBand(c, th, 1160f, footer)
            }
            else -> {   // jogador (contratação, destaque)
                sceneBase(c, th, scene, 820f)
                val panel = Path().apply { moveTo(0f, 0f); lineTo(W * 0.58f, 0f); lineTo(W * 0.36f, H); lineTo(0f, H); close() }
                c.drawPath(panel, P(color = withA(th.kit, if (look.photo != null) 215 else 160)))
                crestPlain(c, crest, 105f, 105f, 130f)
                text(c, banner, 190f, 112f, 34f, onColor(th.kit), maxw = 440f, skew = -0.12f)
                title(c, big.uppercase(), 50f, 500f, 150f, maxw = 540f, align = 'l')
                band2(c, 40f, 560f, 600f, 76f, Color.WHITE)
                text(c, info, 320f, 612f, 28f, argb(255, 12, 16, 28), maxw = 520f, align = 'c', skew = -0.1f)
                if (plate != null) {
                    text(c, plate.first, 50f, 760f, 38f, onColor(th.kit), skew = -0.1f)
                    title(c, plate.second, 50f, 900f, 170f, maxw = 420f, align = 'l', color = onColor(th.kit))
                }
                val ph = look.photo
                if (ph != null) {
                    val box = RectF(W - 450f, 220f, W - 70f, 600f)
                    c.drawRoundRect(RectF(box.left + 6f, box.top + 12f, box.right + 6f, box.bottom + 12f), 36f, 36f, P(color = black(150), blur = 14f))
                    val clip = Path().apply { addRoundRect(box, 36f, 36f, Path.Direction.CW) }
                    c.save(); c.clipPath(clip)
                    val sc = max(box.width() / ph.width, box.height() / ph.height)
                    val dw = ph.width * sc; val dh = ph.height * sc
                    c.drawRect(box, P(color = argb(255, 230, 234, 242)))
                    c.drawBitmap(ph, null, RectF(box.centerX() - dw / 2f, box.top, box.centerX() + dw / 2f, box.top + dh), P())
                    c.restore()
                    c.drawRoundRect(box, 36f, 36f, P(color = th.kit, stroke = 10f))
                }
                footBand(c, th, 1160f, footer)
            }
        }
        return out
    }

    class Row(val label: String, val value: String, val frac: Float? = null, val photo: Bitmap? = null)

    /**
     * Cartaz em lista (craques, lado a lado, momento): título em faixa, linhas em vidro com valor e barra (dois times = barra dividida nas cores dos dois);
     * [dots] = últimos resultados (V/E/D) em bolinhas; linhas com [Row.photo] mostram a foto real do jogador num círculo.
     */
    fun list(th: Theme, scene: Bitmap, crest: Bitmap?, oppCrest: Bitmap?, banner: String, rows: List<Row>, footer: String, dots: String? = null, oppTh: Theme? = null, mineLeft: Boolean = true): Bitmap {
        val out = Bitmap.createBitmap(W.toInt(), H.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        sceneBase(c, th, scene, 420f)
        val opp = oppTh ?: Theme(th.bg, th.accent, th.accent)
        if (oppCrest != null) {
            crestPlain(c, crest, 230f, 200f, 290f); crestPlain(c, oppCrest, W - 230f, 200f, 290f)
            title(c, "VS", W / 2f, 240f, 110f)
        } else crestPlain(c, crest, W / 2f, 200f, 290f)
        band2(c, 40f, 370f, W - 40f, 92f, th.kit)
        text(c, banner, W / 2f, 432f, 46f, onColor(th.kit), maxw = W - 180f, align = 'c', skew = -0.12f)
        var y = 500f
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
        val step = minOf(124f, (1130f - y) / maxOf(1, shown.size))
        val hh = step - 16f
        for (r in shown) {
            val box = RectF(60f, y, W - 60f, y + hh)
            c.drawRoundRect(box, 20f, 20f, P(color = argb(205, 8, 10, 16)))
            c.drawRoundRect(box, 20f, 20f, P(color = withA(th.kit, 200), stroke = 3f))
            var lx = 92f
            if (r.photo != null) {
                val rr = hh * 0.34f
                val cx = 92f + rr; val cy = y + hh * 0.46f
                c.save(); c.clipPath(Path().apply { addCircle(cx, cy, rr, Path.Direction.CW) })
                val sc = max(2 * rr / r.photo.width, 2 * rr / r.photo.height)
                val dw = r.photo.width * sc; val dh = r.photo.height * sc
                c.drawBitmap(r.photo, null, RectF(cx - dw / 2f, cy - rr, cx + dw / 2f, cy - rr + dh), P())
                c.restore()
                c.drawCircle(cx, cy, rr, P(color = th.kit, stroke = 4f))
                lx = 92f + 2 * rr + 20f
            }
            text(c, r.label, lx, y + hh * 0.60f, 38f * hh / 96f, Color.WHITE, maxw = 560f - (lx - 92f), skew = -0.1f, scale = 0.95f)
            text(c, r.value, W - 92f, y + hh * 0.62f, 50f * hh / 96f, Color.WHITE, maxw = 340f, align = 'r', skew = -0.1f)
            if (r.frac != null) {
                val f = r.frac.coerceIn(0f, 1f)
                val bar = RectF(92f, y + hh - 22f, W - 92f, y + hh - 12f)
                val leftCol = if (mineLeft) th.kit else opp.kit; val rightCol = if (mineLeft) opp.kit else th.kit
                c.drawRoundRect(bar, 5f, 5f, P(color = if (oppCrest != null) withA(rightCol, 230) else white(70)))
                c.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * f, bar.bottom), 5f, 5f, P(color = leftCol))
            }
            y += step
        }
        footBand(c, th, 1160f, footer)
        return out
    }

    /** Faixa de baixo com a logo e o nome do app, FORA da arte (a imagem fica acima dela e nada é coberto). */
    fun withStrip(art: Bitmap, logo: Bitmap?): Bitmap {
        val strip = 96
        val out = Bitmap.createBitmap(art.width, art.height + strip, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(art, 0f, 0f, null)
        val y0 = art.height.toFloat()
        c.drawRect(0f, y0, art.width.toFloat(), y0 + strip, P(color = argb(255, 8, 12, 24)))
        c.drawLine(0f, y0, art.width.toFloat(), y0, P(color = argb(255, 60, 70, 95), stroke = 2f))
        var x = 30f
        if (logo != null) {
            val s = 72f / max(logo.width, logo.height)
            c.drawBitmap(logo, null, RectF(x, y0 + 12f, x + logo.width * s, y0 + 12f + logo.height * s), P())
            x += logo.width * s + 18f
        }
        text(c, "OSM AI Coach", x, y0 + 61f, 38f, Color.WHITE, skew = -0.08f, scale = 0.95f)
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
