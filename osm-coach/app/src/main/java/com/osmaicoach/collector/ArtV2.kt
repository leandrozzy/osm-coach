package com.osmaicoach.collector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Desenhos v2 (base: os desenhos do jogo, refeitos em vetor, sem imagem de terceiros): campo em perspectiva com borda de terra,
 * setas do estilo de jogo, rostos com expressão (desarme e árbitro) e termômetro de rigor nas cores do jogo.
 */

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Campo em perspectiva: u = comprimento (0 esquerda … 1 direita), v = profundidade (0 fundo … 1 frente). */
internal class Pitch3(val w: Float, val h: Float) {
    private val farL = 0.14f; private val farR = 0.86f; private val yFar = 0.17f; private val yNear = 0.80f
    fun p(u: Float, v: Float): Offset {
        val xl = lerpF(farL, 0f, v); val xr = lerpF(farR, 1f, v)
        return Offset((xl + (xr - xl) * u) * w, lerpF(yFar, yNear, v) * h)
    }
    fun scale(v: Float) = lerpF(0.72f, 1.08f, v)
    val soil get() = h * 0.075f
}

internal fun DrawScope.pitch3(pt: Pitch3) {
    val w = size.width; val h = size.height
    // sombra no chão
    drawOval(Color(0x55000000), Offset(w * 0.04f, h * 0.80f), Size(w * 0.92f, h * 0.17f))
    // terra (frente e laterais)
    val soil = pt.soil
    val front = Path().apply { moveTo(pt.p(0f, 1f).x, pt.p(0f, 1f).y); lineTo(pt.p(1f, 1f).x, pt.p(1f, 1f).y); lineTo(pt.p(1f, 1f).x, pt.p(1f, 1f).y + soil); lineTo(pt.p(0f, 1f).x, pt.p(0f, 1f).y + soil); close() }
    drawPath(front, Brush.verticalGradient(listOf(Color(0xFF9A5A26), Color(0xFF5B2F12)), pt.p(0f, 1f).y, pt.p(0f, 1f).y + soil))
    for (u in listOf(0f, 1f)) {
        val s = Path().apply { moveTo(pt.p(u, 0f).x, pt.p(u, 0f).y); lineTo(pt.p(u, 1f).x, pt.p(u, 1f).y); lineTo(pt.p(u, 1f).x, pt.p(u, 1f).y + soil); lineTo(pt.p(u, 0f).x, pt.p(u, 0f).y + soil * 0.6f); close() }
        drawPath(s, Color(0xFF6E3A17))
    }
    // grama com listras
    val grass = Path().apply { moveTo(pt.p(0f, 0f).x, pt.p(0f, 0f).y); lineTo(pt.p(1f, 0f).x, pt.p(1f, 0f).y); lineTo(pt.p(1f, 1f).x, pt.p(1f, 1f).y); lineTo(pt.p(0f, 1f).x, pt.p(0f, 1f).y); close() }
    drawPath(grass, Brush.verticalGradient(listOf(Color(0xFF1B7F34), Color(0xFF29A346)), pt.p(0f, 0f).y, pt.p(0f, 1f).y))
    val n = 10
    for (i in 0 until n step 2) {
        val u0 = i / n.toFloat(); val u1 = (i + 1) / n.toFloat()
        val q = Path().apply { moveTo(pt.p(u0, 0f).x, pt.p(u0, 0f).y); lineTo(pt.p(u1, 0f).x, pt.p(u1, 0f).y); lineTo(pt.p(u1, 1f).x, pt.p(u1, 1f).y); lineTo(pt.p(u0, 1f).x, pt.p(u0, 1f).y); close() }
        drawPath(q, Color(0x14FFFFFF))
    }
    // brilho de luz sobre o campo
    drawPath(grass, Brush.radialGradient(listOf(Color(0x33FFFFFF), Color(0x00FFFFFF)), pt.p(0.5f, 0.45f), w * 0.42f))
    val line = Color(0xCCFFFFFF); val lw = h * 0.012f
    fun poly(pts: List<Offset>, close: Boolean = false) {
        val pa = Path().apply { moveTo(pts[0].x, pts[0].y); for (k in 1 until pts.size) lineTo(pts[k].x, pts[k].y); if (close) close() }
        drawPath(pa, line, style = Stroke(lw, join = StrokeJoin.Round, cap = StrokeCap.Round))
    }
    poly(listOf(pt.p(0f, 0f), pt.p(1f, 0f), pt.p(1f, 1f), pt.p(0f, 1f)), true)
    poly(listOf(pt.p(0.5f, 0f), pt.p(0.5f, 1f)))
    poly((0..40).map { val a = it / 40f * 2f * PI.toFloat(); pt.p(0.5f + 0.085f * cos(a), 0.5f + 0.20f * sin(a)) }, true)
    for (side in listOf(0f, 1f)) {
        val s = if (side == 0f) 1f else -1f
        fun u(x: Float) = side + s * x
        poly(listOf(pt.p(u(0f), 0.20f), pt.p(u(0.17f), 0.20f), pt.p(u(0.17f), 0.80f), pt.p(u(0f), 0.80f)))
        poly(listOf(pt.p(u(0f), 0.36f), pt.p(u(0.065f), 0.36f), pt.p(u(0.065f), 0.64f), pt.p(u(0f), 0.64f)))
        // trave com rede
        val gh = h * 0.085f
        val a = pt.p(u(0f), 0.40f); val b = pt.p(u(0f), 0.60f)
        val net = Path().apply { moveTo(a.x, a.y); lineTo(a.x, a.y - gh); lineTo(b.x, b.y - gh); lineTo(b.x, b.y); close() }
        drawPath(net, Color(0x33FFFFFF))
        drawPath(net, Color(0xEEFFFFFF), style = Stroke(lw * 1.4f, join = StrokeJoin.Round))
    }
    // bandeirinhas de escanteio
    for ((u, v) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)) {
        val c = pt.p(u, v); val fh = h * 0.07f * pt.scale(v)
        drawLine(Color(0xFFEDEDED), c, c - Offset(0f, fh), lw * 0.9f)
        val fl = Path().apply { moveTo(c.x, c.y - fh); lineTo(c.x + (if (u == 0f) 1 else -1) * fh * 0.55f, c.y - fh * 0.82f); lineTo(c.x, c.y - fh * 0.64f); close() }
        drawPath(fl, Color(0xFFE5484D))
    }
}

/** Estilo de jogo no campo em perspectiva (o desenho que o jogo mostra), com as setas animadas do app. */
@Composable
fun StyleIllustration(style: String?, modifier: Modifier = Modifier) {
    val st = Osm.style(style) ?: style ?: ""
    val prog = drawProgress(st, 1100)
    val fl = flowPhase()
    Canvas(modifier) {
        Ill.flow = fl
        val pt = Pitch3(size.width, size.height)
        pitch3(pt)
        val w = size.height * 0.052f
        fun tk(u: Float, v: Float, col: Color = Ill.MINE, a: Float = 1f) = token(pt.p(u, v), size.height * 0.062f * pt.scale(v), col, a)
        when (st) {
            "Jogar pelas alas" -> {
                arrow(listOf(pt.p(0.20f, 0.11f), pt.p(0.55f, 0.10f), pt.p(0.80f, 0.12f), pt.p(0.91f, 0.30f)), Ill.RED, Ill.RED_D, w, prog)
                arrow(listOf(pt.p(0.20f, 0.93f), pt.p(0.55f, 0.94f), pt.p(0.80f, 0.92f), pt.p(0.91f, 0.72f)), Ill.RED, Ill.RED_D, w, prog)
                tk(0.18f, 0.11f); tk(0.18f, 0.93f); tk(0.93f, 0.50f, a = ((prog - 0.6f) / 0.4f).coerceIn(0f, 1f))
            }
            "Jogo de passe" -> {
                val pts = listOf(pt.p(0.13f, 0.62f), pt.p(0.29f, 0.36f), pt.p(0.46f, 0.66f), pt.p(0.62f, 0.36f), pt.p(0.80f, 0.62f))
                for (i in 0 until pts.size - 1) {
                    val seg = (prog * (pts.size - 1) - i).coerceIn(0f, 1f)
                    arrow(listOf(pts[i] + (pts[i + 1] - pts[i]) * 0.17f, pts[i + 1] - (pts[i + 1] - pts[i]) * 0.2f), Ill.RED, Ill.RED_D, w * 0.8f, seg)
                }
                pts.forEachIndexed { i, o -> tk(0.13f + i * 0.1675f, listOf(0.62f, 0.36f, 0.66f, 0.36f, 0.62f)[i]) }
            }
            "Bola longa" -> {
                val a = pt.p(0.16f, 0.74f); val b = pt.p(0.84f, 0.64f)
                val n = 30
                for (k in 0..(n * prog).toInt()) {
                    val t = k / n.toFloat()
                    val g = a + (b - a) * t
                    drawCircle(Color(0x44000000), size.height * 0.018f, g + Offset(0f, size.height * 0.012f))
                }
                val pts = (0..10).map { k -> val t = k / 10f; val g = a + (b - a) * t; g - Offset(0f, size.height * 0.40f * sin(PI * t).toFloat()) }
                arrow(pts, Ill.GOLD_D, Ill.RED_D, w * 1.1f, prog)
                tk(0.14f, 0.76f); tk(0.86f, 0.66f)
                ball(pt.p(0.80f, 0.70f), size.height * 0.032f, ((prog - 0.85f) / 0.15f).coerceIn(0f, 1f))
            }
            "Contra-ataque" -> {
                val back = (prog / 0.45f).coerceIn(0f, 1f)
                val fwd = ((prog - 0.35f) / 0.65f).coerceIn(0f, 1f)
                arrow(listOf(pt.p(0.66f, 0.27f), pt.p(0.50f, 0.33f), pt.p(0.32f, 0.38f)), Ill.BLUE, Ill.BLUE_D, w * 0.8f, back)
                val top = (0..8).map { k -> val t = k / 8f; val a2 = pt.p(0.30f, 0.66f); val b2 = pt.p(0.90f, 0.50f); (a2 + (b2 - a2) * t) - Offset(0f, size.height * 0.16f * sin(PI * t).toFloat()) }
                arrow(top, Ill.RED, Ill.RED_D, w, fwd)
                tk(0.26f, 0.66f); tk(0.70f, 0.24f, Ill.RIVAL); tk(0.74f, 0.42f, Ill.RIVAL)
            }
            "Remate à vista" -> {
                val from = listOf(pt.p(0.52f, 0.22f), pt.p(0.58f, 0.52f), pt.p(0.52f, 0.82f))
                from.forEachIndexed { i, s ->
                    val q = ((prog - i * 0.15f) / 0.7f).coerceIn(0f, 1f)
                    arrow(listOf(s + Offset(size.height * 0.07f, 0f), pt.p(0.93f, 0.50f + (i - 1) * 0.12f)), Ill.RED, Ill.RED_D, w * 0.75f, q)
                }
                tk(0.50f, 0.22f); tk(0.56f, 0.52f); tk(0.50f, 0.82f)
                for (k in 0 until 5) ball(pt.p(0.70f + 0.04f * k, 0.28f + 0.11f * k), size.height * (0.018f + 0.003f * k), 0.45f * ((prog - 0.3f) / 0.7f).coerceIn(0f, 1f))
                ball(pt.p(0.965f, 0.46f), size.height * 0.032f, ((prog - 0.7f) / 0.3f).coerceIn(0f, 1f))
            }
            else -> tk(0.5f, 0.5f)
        }
    }
}

// ---------------------------------------------------------------- rostos (desarme e árbitro)

internal enum class Expr { JOY, SMILE, SMIRK, SKEPT, WORRY, GRIN_ANGRY, GRIN_FURIOUS, ANGRY, FURIOUS }

internal fun DrawScope.coachFace(e: Expr, hair: Color, shirt: Color?, browTint: Color?) {
    val s = size.minDimension
    val ox = (size.width - s) / 2f; val oy = (size.height - s) / 2f
    fun P(x: Float, y: Float) = Offset(ox + s * x, oy + s * y)
    fun Sz(w: Float, h: Float) = Size(s * w, s * h)
    val skin = Brush.radialGradient(listOf(Color(0xFFFFDDBF), Color(0xFFF3B58A), Color(0xFFD98B5F)), P(0.42f, 0.36f), s * 0.52f)
    val skinDark = Color(0xFFC77D52)
    shirt?.let {
        drawOval(Color(0x55000000), P(0.10f, 0.86f), Sz(0.80f, 0.26f))
        drawOval(it, P(0.12f, 0.82f), Sz(0.76f, 0.30f))
        drawArc(Color(0xFF1B1B1B), 200f, 140f, false, P(0.33f, 0.80f), Sz(0.34f, 0.12f), style = Stroke(s * 0.025f, cap = StrokeCap.Round))
    }
    // orelhas e cabeça
    drawOval(Brush.radialGradient(listOf(Color(0xFFF3B58A), skinDark), P(0.16f, 0.52f), s * 0.1f), P(0.14f, 0.43f), Sz(0.10f, 0.17f))
    drawOval(Brush.radialGradient(listOf(Color(0xFFF3B58A), skinDark), P(0.84f, 0.52f), s * 0.1f), P(0.76f, 0.43f), Sz(0.10f, 0.17f))
    drawOval(skin, P(0.19f, 0.12f), Sz(0.62f, 0.72f))
    // cabelo
    val hp = Path().apply {
        val pts = listOf(0.20f to 0.46f, 0.18f to 0.30f, 0.25f to 0.21f, 0.24f to 0.09f, 0.35f to 0.16f, 0.40f to 0.03f, 0.48f to 0.14f, 0.55f to 0.02f, 0.60f to 0.15f,
            0.71f to 0.08f, 0.70f to 0.21f, 0.80f to 0.28f, 0.81f to 0.46f, 0.75f to 0.33f, 0.62f to 0.27f, 0.50f to 0.25f, 0.38f to 0.27f, 0.26f to 0.33f)
        moveTo(P(pts[0].first, pts[0].second).x, P(pts[0].first, pts[0].second).y)
        for (k in 1 until pts.size) lineTo(P(pts[k].first, pts[k].second).x, P(pts[k].first, pts[k].second).y)
        close()
    }
    drawPath(hp, Brush.verticalGradient(listOf(lighten(hair, 0.25f), hair, darken(hair, 0.25f)), P(0f, 0.02f).y, P(0f, 0.46f).y))
    drawPath(hp, Color(0x22FFFFFF), style = Stroke(s * 0.012f, join = StrokeJoin.Round))
    // bochechas
    if (e == Expr.JOY || e == Expr.SMILE || e == Expr.WORRY) {
        drawCircle(Color(0x33FF6F61), s * 0.055f, P(0.30f, 0.64f)); drawCircle(Color(0x33FF6F61), s * 0.055f, P(0.70f, 0.64f))
    }
    // sobrancelhas: (y interno, y externo) por lado
    val brow = browTint ?: Color(0xFF241610)
    data class Br(val inL: Float, val outL: Float, val inR: Float, val outR: Float, val w: Float)
    val b = when (e) {
        Expr.JOY -> Br(0.355f, 0.335f, 0.355f, 0.335f, 0.040f)
        Expr.SMILE -> Br(0.375f, 0.365f, 0.375f, 0.365f, 0.040f)
        Expr.SMIRK -> Br(0.380f, 0.370f, 0.345f, 0.335f, 0.042f)
        Expr.SKEPT -> Br(0.405f, 0.395f, 0.345f, 0.360f, 0.042f)
        Expr.WORRY -> Br(0.340f, 0.405f, 0.340f, 0.405f, 0.040f)
        Expr.ANGRY -> Br(0.425f, 0.355f, 0.425f, 0.355f, 0.050f)
        Expr.FURIOUS -> Br(0.440f, 0.340f, 0.440f, 0.340f, 0.058f)
        Expr.GRIN_ANGRY -> Br(0.420f, 0.355f, 0.420f, 0.355f, 0.052f)
        Expr.GRIN_FURIOUS -> Br(0.440f, 0.335f, 0.440f, 0.335f, 0.060f)
    }
    val hot = e == Expr.ANGRY || e == Expr.FURIOUS || e == Expr.GRIN_ANGRY || e == Expr.GRIN_FURIOUS
    drawLine(brow, P(0.45f, b.inL), P(0.29f, b.outL), s * b.w, StrokeCap.Round)
    drawLine(brow, P(0.55f, b.inR), P(0.71f, b.outR), s * b.w, StrokeCap.Round)
    // olhos
    val open = when (e) { Expr.FURIOUS, Expr.GRIN_FURIOUS -> 0.050f; Expr.ANGRY, Expr.GRIN_ANGRY -> 0.062f; Expr.SKEPT -> 0.060f; else -> 0.080f }
    val dx = if (e == Expr.SKEPT) s * 0.016f else 0f
    for (cx in listOf(0.375f, 0.625f)) {
        val c = P(cx, 0.50f)
        drawOval(Color.White, c - Offset(s * 0.062f, s * open), Size(s * 0.124f, s * open * 2f))
        drawCircle(Color(0xFF2A1B14), s * 0.033f, c + Offset(dx, 0f))
        drawCircle(Color.White, s * 0.011f, c + Offset(dx - s * 0.011f, -s * 0.011f))
        if (hot || e == Expr.SKEPT) drawLine(Color(0xFF3B2418), c - Offset(s * 0.066f, s * open), c + Offset(s * 0.066f, -s * open), s * 0.014f, StrokeCap.Round)
    }
    // nariz
    drawArc(Color(0x66A9643E), 20f, 140f, false, P(0.455f, 0.55f), Sz(0.09f, 0.075f), style = Stroke(s * 0.016f, cap = StrokeCap.Round))
    // boca
    val mc = Color(0xFF8A3B2B)
    val sw = s * 0.026f
    fun curve(x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float) = Path().apply { moveTo(P(x0, y0).x, P(x0, y0).y); quadraticTo(P(cx, cy).x, P(cx, cy).y, P(x1, y1).x, P(x1, y1).y) }
    fun grin(topY: Float, drop: Float, wHalf: Float) {
        val mp = Path().apply {
            moveTo(P(0.5f - wHalf, topY).x, P(0.5f - wHalf, topY).y)
            quadraticTo(P(0.5f, topY + 0.01f).x, P(0.5f, topY + 0.01f).y, P(0.5f + wHalf, topY).x, P(0.5f + wHalf, topY).y)
            quadraticTo(P(0.5f, topY + drop).x, P(0.5f, topY + drop).y, P(0.5f - wHalf, topY).x, P(0.5f - wHalf, topY).y)
            close()
        }
        drawPath(mp, Color(0xFF6B1A1A))
        drawPath(Path().apply {
            moveTo(P(0.5f - wHalf * 0.92f, topY + 0.004f).x, P(0.5f - wHalf * 0.92f, topY + 0.004f).y)
            quadraticTo(P(0.5f, topY + 0.016f).x, P(0.5f, topY + 0.016f).y, P(0.5f + wHalf * 0.92f, topY + 0.004f).x, P(0.5f + wHalf * 0.92f, topY + 0.004f).y)
            lineTo(P(0.5f + wHalf * 0.78f, topY + drop * 0.42f).x, P(0.5f + wHalf * 0.78f, topY + drop * 0.42f).y)
            quadraticTo(P(0.5f, topY + drop * 0.5f).x, P(0.5f, topY + drop * 0.5f).y, P(0.5f - wHalf * 0.78f, topY + drop * 0.42f).x, P(0.5f - wHalf * 0.78f, topY + drop * 0.42f).y)
            close()
        }, Color(0xFFFFFFFF))
        drawPath(mp, Color(0xFF4A1010), style = Stroke(sw * 0.7f, join = StrokeJoin.Round))
    }
    when (e) {
        Expr.JOY -> grin(0.665f, 0.13f, 0.115f)
        Expr.SMILE -> drawPath(curve(0.40f, 0.675f, 0.50f, 0.735f, 0.60f, 0.675f), mc, style = Stroke(sw, cap = StrokeCap.Round))
        Expr.SMIRK -> drawPath(curve(0.41f, 0.700f, 0.53f, 0.735f, 0.63f, 0.655f), mc, style = Stroke(sw, cap = StrokeCap.Round))
        Expr.SKEPT -> drawPath(curve(0.42f, 0.715f, 0.50f, 0.705f, 0.59f, 0.700f), mc, style = Stroke(sw, cap = StrokeCap.Round))
        Expr.WORRY -> drawPath(curve(0.43f, 0.710f, 0.52f, 0.740f, 0.60f, 0.690f), mc, style = Stroke(sw, cap = StrokeCap.Round))
        Expr.ANGRY -> drawPath(curve(0.41f, 0.745f, 0.50f, 0.690f, 0.59f, 0.745f), mc, style = Stroke(sw * 1.1f, cap = StrokeCap.Round))
        Expr.FURIOUS -> {
            drawRoundRect(Color(0xFF4A1010), P(0.39f, 0.700f), Sz(0.22f, 0.075f), CornerRadius(s * 0.025f))
            drawRoundRect(Color.White, P(0.405f, 0.707f), Sz(0.19f, 0.032f), CornerRadius(s * 0.012f))
            drawLine(Color(0xFFC9C9C9), P(0.5f, 0.707f), P(0.5f, 0.740f), s * 0.006f)
        }
        Expr.GRIN_ANGRY -> grin(0.675f, 0.095f, 0.105f)
        Expr.GRIN_FURIOUS -> grin(0.665f, 0.125f, 0.125f)
    }
}

private fun lightenC(c: Color, f: Float) = lighten(c, f)

@Composable
fun TackleIllustration(tackle: String?, modifier: Modifier = Modifier) {
    val lvl = when (Osm.tackle(tackle) ?: tackle) { "Cuidadoso" -> 0; "Agressivo" -> 2; "Extremo" -> 3; else -> 1 }
    Canvas(modifier) {
        val e = listOf(Expr.WORRY, Expr.SMIRK, Expr.GRIN_ANGRY, Expr.GRIN_FURIOUS)[lvl]
        val ring = listOf(Color(0xFF34D399), Color(0xFFF2C94C), Color(0xFFFF9A3D), Color(0xFFE5484D))[lvl]
        val s = size.minDimension
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Brush.radialGradient(listOf(ring.copy(alpha = 0.35f), ring.copy(alpha = 0f)), c, s * 0.62f), s * 0.62f, c)
        coachFace(e, Color(0xFF7A4A2B), null, if (lvl >= 2) Color(0xFFD7342A) else null)
    }
}

/** Árbitro (0 muito brando/permissivo … 4 muito rigoroso): rosto + termômetro nas cores do jogo (verde, azul, amarelo, laranja, vermelho). */
@Composable
fun RefereeFace(level: Int?, modifier: Modifier = Modifier) {
    val lv = (level ?: 2).coerceIn(0, 4)
    val colors = listOf(Color(0xFF22D13A), Color(0xFF1F9BFF), Color(0xFFFFC21A), Color(0xFFFF6A00), Color(0xFFE5171B))
    val fills = listOf(0.30f, 0.45f, 0.55f, 0.72f, 0.95f)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.fillMaxHeight().aspectRatio(1f)) {
            coachFace(listOf(Expr.JOY, Expr.SMILE, Expr.SKEPT, Expr.ANGRY, Expr.FURIOUS)[lv], Color(0xFF14141C), Color(0xFFFFD21F), null)
        }
        Canvas(Modifier.fillMaxHeight().aspectRatio(0.42f)) {
            val w = size.width; val h = size.height
            val col = colors[lv]
            val tubeL = w * 0.30f; val tubeW = w * 0.40f
            val bulbR = w * 0.40f; val bulbC = Offset(w / 2, h - bulbR - h * 0.02f)
            // vidro
            drawRoundRect(Color(0xFF14447A), Offset(tubeL - w * 0.04f, h * 0.02f), Size(tubeW + w * 0.08f, bulbC.y - h * 0.02f), CornerRadius(tubeW / 2 + w * 0.04f))
            drawCircle(Color(0xFF14447A), bulbR + w * 0.05f, bulbC)
            drawRoundRect(Color(0x33FFFFFF), Offset(tubeL, h * 0.04f), Size(tubeW, bulbC.y - h * 0.04f), CornerRadius(tubeW / 2))
            // líquido
            val top = lerpF(bulbC.y, h * 0.07f, fills[lv])
            drawRoundRect(Brush.horizontalGradient(listOf(lighten(col, 0.35f), col, darken(col, 0.2f)), tubeL, tubeL + tubeW), Offset(tubeL + w * 0.03f, top), Size(tubeW - w * 0.06f, bulbC.y - top), CornerRadius(tubeW / 2))
            drawCircle(Brush.radialGradient(listOf(lighten(col, 0.5f), col, darken(col, 0.25f)), bulbC - Offset(bulbR * 0.3f, bulbR * 0.3f), bulbR * 1.3f), bulbR, bulbC)
            drawCircle(Color(0x66FFFFFF), bulbR * 0.22f, bulbC - Offset(bulbR * 0.38f, bulbR * 0.38f))
            drawLine(Color(0x66FFFFFF), Offset(tubeL + tubeW * 0.22f, h * 0.10f), Offset(tubeL + tubeW * 0.22f, bulbC.y - bulbR * 0.8f), w * 0.05f, StrokeCap.Round)
        }
    }
}
