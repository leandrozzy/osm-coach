package com.osmaicoach.collector

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Ilustrações vetoriais da tela de tática (estilo app de esporte moderno): campo visto de cima, setas com gradiente,
 * brilho e sombra suave, jogadores como fichas. Tudo desenhado na hora, nítido em qualquer tela, e animado: as setas
 * se desenham do começo ao fim sempre que a opção muda.
 */
object Ill {
    val GRASS_A = Color(0xFF1F8F3A)
    val GRASS_B = Color(0xFF1A8034)
    val LINE = Color(0x8CFFFFFF)
    val RED = Color(0xFFFF4D4D)
    val RED_D = Color(0xFFD7192A)
    val BLUE = Color(0xFF3DB2FF)
    val BLUE_D = Color(0xFF1666E0)
    val GOLD = Color(0xFFFFD24A)
    val GOLD_D = Color(0xFFFF9F1A)
    val ORANGE = Color(0xFFFF9A3D)
    val ORANGE_D = Color(0xFFE85A0C)
    val MINE = Color(0xFF2F7BFF)
    /** Fase da luz que corre nas setas do desenho atual (-1 = sem luz). */
    var flow = -1f
    val RIVAL = Color(0xFFE5484D)
}

/** Luz que corre pelas setas em loop (0..1), deixa o desenho "vivo" depois de desenhado. */
@Composable
fun flowPhase(millis: Int = 1800): Float {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "flow")
    val v = t.animateFloat(
        initialValue = 0f, targetValue = 1f, animationSpec =
        androidx.compose.animation.core.infiniteRepeatable(tween(millis, easing = androidx.compose.animation.core.LinearEasing)),
        label = "flowv"
    )
    return v.value
}

/** Progresso 0..1 que recomeça (com aceleração suave) sempre que [key] muda. */
@Composable
fun drawProgress(key: Any?, millis: Int = 900): Float {
    val a = remember { Animatable(0f) }
    LaunchedEffect(key) {
        a.snapTo(0f)
        a.animateTo(1f, tween(millis, easing = FastOutSlowInEasing))
    }
    return a.value
}

// ------------------------------------------------------------------ campo

/** Campo visto de cima em [r] (horizontal = ataque para a direita; vertical = ataque para cima). */
fun DrawScope.pitch(r: Rect, vertical: Boolean) {
    val rad = CornerRadius(min(r.width, r.height) * 0.06f)
    // sombra do cartão do campo
    drawRoundRect(Color(0x66000000), r.topLeft + Offset(0f, r.height * 0.025f), r.size, rad)
    val clip = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(r, rad)) }
    clipPath(clip) {
        drawRect(Ill.GRASS_A, r.topLeft, r.size)
        val n = 10
        for (i in 0 until n) {
            if (i % 2 == 0) continue
            if (vertical) drawRect(Ill.GRASS_B, Offset(r.left, r.top + r.height * i / n), Size(r.width, r.height / n))
            else drawRect(Ill.GRASS_B, Offset(r.left + r.width * i / n, r.top), Size(r.width / n, r.height))
        }
        // corte cruzado bem sutil (xadrez de gramado de estádio)
        val k = 6
        for (i in 0 until k) {
            if (i % 2 == 1) continue
            if (vertical) drawRect(Color(0x0FFFFFFF), Offset(r.left + r.width * i / k, r.top), Size(r.width / k, r.height))
            else drawRect(Color(0x0FFFFFFF), Offset(r.left, r.top + r.height * i / k), Size(r.width, r.height / k))
        }
        // luz de refletor: claro no centro, escurece nas bordas
        drawRect(
            Brush.radialGradient(listOf(Color(0x26FFFFFF), Color(0x00FFFFFF), Color(0x40000000)), r.center, maxOf(r.width, r.height) * 0.75f),
            r.topLeft, r.size
        )
    }
    // filete claro no topo do cartão (dá acabamento)
    drawRoundRect(Color(0x33FFFFFF), r.topLeft, r.size, rad, style = Stroke(1.5f))
    val m = min(r.width, r.height) * 0.06f
    val f = Rect(r.left + m, r.top + m, r.right - m, r.bottom - m)
    val lw = (min(r.width, r.height) * 0.012f).coerceAtLeast(1.2f)
    val st = Stroke(lw)
    drawRect(Ill.LINE, f.topLeft, f.size, style = st)
    val cr = min(f.width, f.height) * 0.16f
    drawCircle(Ill.LINE, cr, f.center, style = st)
    drawCircle(Ill.LINE, lw * 1.4f, f.center)
    // cantos
    val ca = min(f.width, f.height) * 0.05f
    drawArc(Ill.LINE, 0f, 90f, false, Offset(f.left - ca, f.top - ca), Size(ca * 2, ca * 2), style = st)
    drawArc(Ill.LINE, 90f, 90f, false, Offset(f.right - ca, f.top - ca), Size(ca * 2, ca * 2), style = st)
    drawArc(Ill.LINE, 270f, 90f, false, Offset(f.left - ca, f.bottom - ca), Size(ca * 2, ca * 2), style = st)
    drawArc(Ill.LINE, 180f, 90f, false, Offset(f.right - ca, f.bottom - ca), Size(ca * 2, ca * 2), style = st)
    if (vertical) {
        drawLine(Ill.LINE, Offset(f.left, f.center.y), Offset(f.right, f.center.y), lw)
        val dr = f.width * 0.13f
        drawArc(Ill.LINE, 20f, 140f, false, Offset(f.center.x - dr, f.top + f.height * 0.16f - dr * 1.25f), Size(dr * 2, dr * 2), style = st)
        drawArc(Ill.LINE, 200f, 140f, false, Offset(f.center.x - dr, f.bottom - f.height * 0.16f - dr * 0.75f), Size(dr * 2, dr * 2), style = st)
        for (top in listOf(true, false)) {
            val bw = f.width * 0.56f
            val bh = f.height * 0.16f
            val gw = f.width * 0.26f
            val gh = f.height * 0.06f
            val y = if (top) f.top else f.bottom
            val s = if (top) 1f else -1f
            drawRect(Ill.LINE, Offset(f.center.x - bw / 2, if (top) y else y - bh), Size(bw, bh), style = st)
            drawRect(Ill.LINE, Offset(f.center.x - gw / 2, if (top) y else y - gh), Size(gw, gh), style = st)
            drawLine(Color.White, Offset(f.center.x - gw * 0.35f, y - s * lw), Offset(f.center.x + gw * 0.35f, y - s * lw), lw * 2.4f, StrokeCap.Round)
        }
    } else {
        drawLine(Ill.LINE, Offset(f.center.x, f.top), Offset(f.center.x, f.bottom), lw)
        val dr = f.height * 0.13f
        drawArc(Ill.LINE, -70f, 140f, false, Offset(f.left + f.width * 0.15f - dr * 1.25f, f.center.y - dr), Size(dr * 2, dr * 2), style = st)
        drawArc(Ill.LINE, 110f, 140f, false, Offset(f.right - f.width * 0.15f - dr * 0.75f, f.center.y - dr), Size(dr * 2, dr * 2), style = st)
        for (left in listOf(true, false)) {
            val bw = f.width * 0.15f
            val bh = f.height * 0.56f
            val gw = f.width * 0.055f
            val gh = f.height * 0.26f
            val x = if (left) f.left else f.right
            val s = if (left) 1f else -1f
            drawRect(Ill.LINE, Offset(if (left) x else x - bw, f.center.y - bh / 2), Size(bw, bh), style = st)
            drawRect(Ill.LINE, Offset(if (left) x else x - gw, f.center.y - gh / 2), Size(gw, gh), style = st)
            drawLine(Color.White, Offset(x - s * lw, f.center.y - gh * 0.35f), Offset(x - s * lw, f.center.y + gh * 0.35f), lw * 2.4f, StrokeCap.Round)
        }
    }
}

// ------------------------------------------------------------------ setas e fichas

/** Caminho suave passando pelos pontos (curvas pelos pontos médios). */
fun smoothPath(pts: List<Offset>): Path {
    val p = Path()
    p.moveTo(pts[0].x, pts[0].y)
    if (pts.size == 2) {
        p.lineTo(pts[1].x, pts[1].y)
        return p
    }
    for (i in 1 until pts.size - 1) {
        val mid = (pts[i] + pts[i + 1]) / 2f
        p.quadraticTo(pts[i].x, pts[i].y, mid.x, mid.y)
    }
    p.lineTo(pts.last().x, pts.last().y)
    return p
}

/**
 * Seta: corpo com gradiente (claro -> cor), sombra suave embaixo, brilho fino em cima e ponta arredondada.
 * [progress] desenha só o começo do caminho (animação). [dashed] para trajetória de bola.
 */
fun DrawScope.arrow(pts: List<Offset>, light: Color, dark: Color, width: Float, progress: Float = 1f, dashed: Boolean = false, flow: Float = Ill.flow) {
    if (progress <= 0.01f) return
    val full = smoothPath(pts)
    val pm = PathMeasure()
    pm.setPath(full, false)
    val len = pm.length
    if (len <= 1f) return
    val head = width * (if (len < width * 8f) 1.7f else 2.1f)
    val end = len * progress.coerceIn(0f, 1f)
    val bodyEnd = (end - head * 0.55f).coerceAtLeast(0.01f)
    val body = Path()
    pm.getSegment(0f, bodyEnd, body, true)
    val tip = pm.getPosition(end)
    val tan = pm.getTangent(end)
    val ang = atan2(tan.y, tan.x)
    val start = pts.first()
    val brush = Brush.linearGradient(listOf(light, dark), start, tip)
    val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(width * 1.6f, width * 1.1f)) else null
    // sombra (deslocada para baixo, mais larga e transparente)
    val sh = Offset(width * 0.25f, width * 0.55f)
    val shadow = Path().apply { addPath(body, sh) }
    drawPath(shadow, Color(0x55000000), style = Stroke(width * 1.25f, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
    // halo da cor da seta (brilho de neon suave)
    drawPath(body, light.copy(alpha = 0.16f), style = Stroke(width * 3.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(body, light.copy(alpha = 0.22f), style = Stroke(width * 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    // contorno escuro fino (dá corpo) + corpo com gradiente
    drawPath(body, dark.copy(alpha = 0.9f), style = Stroke(width * 1.22f, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
    drawPath(body, brush, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
    // brilho
    val hl = Path().apply { addPath(body, Offset(-width * 0.12f, -width * 0.18f)) }
    drawPath(hl, Color(0x66FFFFFF), style = Stroke(width * 0.28f, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
    // ponta
    val hp = Path().apply {
        moveTo(head, 0f)
        quadraticTo(head * 0.1f, -head * 0.08f, -head * 0.35f, -head * 0.62f)
        quadraticTo(-head * 0.2f, 0f, -head * 0.35f, head * 0.62f)
        quadraticTo(head * 0.1f, head * 0.08f, head, 0f)
        close()
    }
    val base = tip - Offset(cos(ang) * head * 0.55f, sin(ang) * head * 0.55f)
    translateRotate(base + sh, ang) { drawPath(hp, Color(0x55000000)) }
    translateRotate(base, ang) {
        drawPath(hp, Brush.linearGradient(listOf(light, dark), Offset(-head, -head), Offset(head, head)))
        drawPath(hp, dark.copy(alpha = 0.9f), style = Stroke(width * 0.18f, join = StrokeJoin.Round))
        drawPath(hp, Color(0x40FFFFFF), style = Stroke(width * 0.12f, join = StrokeJoin.Round))
    }
    // luz que corre pela seta (só depois de desenhada)
    if (flow >= 0f && progress >= 0.999f) {
        val fp = pm.getPosition(bodyEnd * flow)
        val fade = (sin(Math.PI * flow).toFloat()).coerceIn(0f, 1f)
        drawCircle(Color.White.copy(alpha = 0.18f * fade), width * 1.3f, fp)
        drawCircle(Color.White.copy(alpha = 0.85f * fade), width * 0.38f, fp)
    }
}

private fun DrawScope.translateRotate(at: Offset, rad: Float, block: DrawScope.() -> Unit) {
    drawContext.transform.translate(at.x, at.y)
    rotate(Math.toDegrees(rad.toDouble()).toFloat(), Offset.Zero) { block() }
    drawContext.transform.translate(-at.x, -at.y)
}

/** Ficha de jogador: disco com gradiente da cor do time, borda branca e sombra. */
fun DrawScope.token(c: Offset, r: Float, color: Color, alpha: Float = 1f, jersey: Boolean = true) {
    if (alpha <= 0f) return
    drawCircle(Color.Black.copy(alpha = 0.35f * alpha), r * 1.05f, c + Offset(r * 0.12f, r * 0.3f))
    drawCircle(Color.White.copy(alpha = alpha), r, c)
    drawCircle(
        Brush.radialGradient(listOf(lighten(color, 0.35f), color, darken(color, 0.35f)), c - Offset(r * 0.35f, r * 0.4f), r * 1.4f),
        r * 0.78f, c, alpha = alpha
    )
    // camisa dentro da ficha
    val j = Path().apply {
        val u = r * 0.42f
        moveTo(c.x - u * 0.35f, c.y - u * 0.75f)
        lineTo(c.x - u * 0.95f, c.y - u * 0.45f)
        lineTo(c.x - u * 0.7f, c.y - u * 0.05f)
        lineTo(c.x - u * 0.48f, c.y - u * 0.18f)
        lineTo(c.x - u * 0.48f, c.y + u * 0.78f)
        lineTo(c.x + u * 0.48f, c.y + u * 0.78f)
        lineTo(c.x + u * 0.48f, c.y - u * 0.18f)
        lineTo(c.x + u * 0.7f, c.y - u * 0.05f)
        lineTo(c.x + u * 0.95f, c.y - u * 0.45f)
        lineTo(c.x + u * 0.35f, c.y - u * 0.75f)
        quadraticTo(c.x, c.y - u * 0.35f, c.x - u * 0.35f, c.y - u * 0.75f)
        close()
    }
    if (jersey) {
        drawPath(j, Color.White.copy(alpha = 0.92f * alpha))
        drawPath(j, darken(color, 0.4f).copy(alpha = 0.5f * alpha), style = Stroke(r * 0.05f, join = StrokeJoin.Round))
    }
    drawCircle(Color.White.copy(alpha = 0.3f * alpha), r * 0.22f, c - Offset(r * 0.35f, r * 0.38f))
}

fun lighten(c: Color, f: Float) = Color(c.red + (1 - c.red) * f, c.green + (1 - c.green) * f, c.blue + (1 - c.blue) * f, c.alpha)
fun darken(c: Color, f: Float) = Color(c.red * (1 - f), c.green * (1 - f), c.blue * (1 - f), c.alpha)

/** Bola (branca com gomos) com sombra. */
fun DrawScope.ball(c: Offset, r: Float, alpha: Float = 1f) {
    if (alpha <= 0f) return
    drawCircle(Color.Black.copy(alpha = 0.35f * alpha), r, c + Offset(r * 0.25f, r * 0.45f))
    drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFCFD6DE)), c - Offset(r * 0.3f, r * 0.3f), r * 1.4f), r, c, alpha = alpha)
    drawCircle(Color(0xFF1B1F27).copy(alpha = alpha), r * 0.36f, c)
    for (k in 0 until 5) {
        val a = Math.toRadians((k * 72 - 90).toDouble())
        drawCircle(Color(0xFF1B1F27).copy(alpha = alpha), r * 0.16f, c + Offset((cos(a) * r * 0.78f).toFloat(), (sin(a) * r * 0.78f).toFloat()))
    }
}

// ------------------------------------------------------------------ estilo de jogo

// ------------------------------------------------------------------ desarme: medidor de intensidade

// ------------------------------------------------------------------ táticas por sector

@Composable
fun SectorIllustration(attack: String?, mid: String?, def: String?, modifier: Modifier = Modifier) {
    val a = Osm.ATTACK.indexOf(Osm.attack(attack) ?: attack).coerceAtLeast(0)
    val m = Osm.MIDFIELD.indexOf(Osm.midfield(mid) ?: mid).coerceAtLeast(0)
    val d = Osm.DEFENSE.indexOf(Osm.defense(def) ?: def).coerceAtLeast(0)
    val pa = drawProgress(a, 800)
    val pm = drawProgress(m, 800)
    val pd = drawProgress(d, 800)
    val fl = flowPhase()
    Canvas(modifier) {
        Ill.flow = fl
        val r = Rect(Offset(size.width * 0.04f, size.height * 0.03f), Size(size.width * 0.92f, size.height * 0.93f))
        pitch(r, vertical = true)
        fun at(x: Float, y: Float) = Offset(r.left + r.width * x, r.top + r.height * y)
        val w = r.width * 0.07f
        val tr = r.width * 0.068f
        // defesas (laranja, embaixo)
        when (d) {
            0 -> holdCross(at(0.5f, 0.84f), r.width * 0.12f, Ill.ORANGE, Ill.ORANGE_D, w * 0.6f, pd)
            1 -> for (x in listOf(0.22f, 0.78f)) arrow(listOf(at(x, 0.86f), at(x, 0.6f)), Ill.ORANGE, Ill.ORANGE_D, w, pd)
            else -> for (x in listOf(0.22f, 0.5f, 0.78f)) arrow(listOf(at(x, 0.86f), at(x, 0.6f)), Ill.ORANGE, Ill.ORANGE_D, w, pd)
        }
        // médios (dourado, no meio)
        when (m) {
            0 -> for (s in listOf(-1f, 1f)) arrow(listOf(at(0.5f + s * 0.12f, 0.46f), at(0.5f + s * 0.24f, 0.36f), at(0.5f + s * 0.2f, 0.26f)), Ill.GOLD, Ill.GOLD_D, w * 0.85f, pm)
            1 -> for (x in listOf(0.2f, 0.8f)) holdCross(at(x, 0.5f), r.width * 0.11f, Ill.GOLD, Ill.GOLD_D, w * 0.55f, pm)
            else -> for (s in listOf(-1f, 1f)) arrow(listOf(at(0.5f + s * 0.12f, 0.54f), at(0.5f + s * 0.24f, 0.64f), at(0.5f + s * 0.2f, 0.74f)), Ill.GOLD, Ill.GOLD_D, w * 0.85f, pm)
        }
        // avançados (azul, em cima)
        when (a) {
            0 -> arrow(listOf(at(0.5f, 0.3f), at(0.5f, 0.1f)), Ill.BLUE, Ill.BLUE_D, w * 1.15f, pa)
            1 -> arrow(listOf(at(0.5f, 0.1f), at(0.5f, 0.3f)), Ill.BLUE, Ill.BLUE_D, w * 1.15f, pa)
            else -> arrow(listOf(at(0.5f, 0.1f), at(0.5f, 0.46f)), Ill.BLUE, Ill.BLUE_D, w * 1.15f, pa)
        }
        // fichas de cada linha por cima das setas
        token(at(0.5f, if (a == 0) 0.33f else 0.1f), tr, Ill.BLUE_D)
        for (x in listOf(0.2f, 0.5f, 0.8f)) if (!(m == 1 && x == 0.5f)) token(at(x, 0.5f), tr, Ill.GOLD_D)
        for (x in listOf(0.22f, 0.5f, 0.78f)) token(at(x, 0.88f), tr, Ill.ORANGE_D)
    }
}

/** "Manter posição": quatro setinhas saindo de um ponto. */
fun DrawScope.holdCross(c: Offset, len: Float, light: Color, dark: Color, w: Float, p: Float) {
    // anel no ponto + setinhas finas para os 4 lados (fica na posição)
    drawCircle(dark.copy(alpha = 0.35f), len * 0.42f * p, c)
    drawCircle(light.copy(alpha = 0.9f), len * 0.42f * p, c, style = Stroke(w * 0.35f))
    for ((dx, dy) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)) {
        arrow(listOf(c + Offset(dx * len * 0.5f, dy * len * 0.5f), c + Offset(dx * len * 1.05f, dy * len * 1.05f)), light, dark, w * 0.55f, p)
    }
}

// ------------------------------------------------------------------ marcação e fora-de-jogo

/**
 * Arte dos 3 cards do estádio (como no OSM): Capacidade = arquibancada iluminada, Relvado = gramado visto de
 * cima, Treino = gol com cone e bola. [building] acende uma luz que corre (obra em andamento).
 */
@Composable
fun StadiumArt(kind: String, building: Boolean, modifier: Modifier = Modifier) {
    val ph = if (building) flowPhase(2400) else -1f
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        when (kind) {
            "Capacidade" -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF0B1E4A), Color(0xFF1B4FA8), Color(0xFF0E2C66))))
                // refletores
                for (x in listOf(0.18f, 0.82f)) {
                    val c = Offset(w * x, h * 0.16f)
                    drawCircle(Brush.radialGradient(listOf(Color(0x99FFFFFF), Color(0x00FFFFFF)), c, w * 0.32f), w * 0.32f, c)
                    drawRect(Color(0xFF9FB8E8), Offset(c.x - w * 0.012f, c.y), Size(w * 0.024f, h * 0.30f))
                    drawRoundRect(Color.White, Offset(c.x - w * 0.06f, c.y - h * 0.04f), Size(w * 0.12f, h * 0.07f), CornerRadius(4f))
                }
                // arquibancadas em degraus com torcida
                val tiers = 5
                for (i in 0 until tiers) {
                    val top = h * (0.42f + i * 0.11f)
                    drawRect(if (i % 2 == 0) Color(0xFF1A3C7E) else Color(0xFF15346E), Offset(0f, top), Size(w, h * 0.11f))
                    val n = 14
                    for (k in 0 until n) {
                        val cx = w * (k + 0.5f + (i % 2) * 0.5f) / n
                        val col = listOf(Color(0xFFE8EEF8), Color(0xFF6FA8FF), Color(0xFFFFD24A), Color(0xFFFF6B6B))[(k * 7 + i * 3) % 4]
                        drawCircle(col.copy(alpha = 0.85f), w * 0.016f, Offset(cx, top + h * 0.045f))
                    }
                }
            }
            "Relvado" -> {
                drawRect(Color(0xFF16325F))
                val r = Rect(w * 0.10f, h * 0.08f, w * 0.90f, h * 0.92f)
                pitch(r, vertical = true)
                // arquibancadas nas laterais
                drawRect(Brush.horizontalGradient(listOf(Color(0xFF243F73), Color(0xFF16325F))), Offset(0f, 0f), Size(w * 0.10f, h))
                drawRect(Brush.horizontalGradient(listOf(Color(0xFF16325F), Color(0xFF243F73))), Offset(w * 0.90f, 0f), Size(w * 0.10f, h))
            }
            else -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF123A2A), Color(0xFF1F6B3A))))
                // gramado
                for (i in 0 until 6) drawRect(if (i % 2 == 0) Ill.GRASS_A else Ill.GRASS_B, Offset(0f, h * (0.55f + i * 0.075f)), Size(w, h * 0.075f))
                // gol com rede
                val gl = w * 0.22f; val gr = w * 0.78f; val gt = h * 0.22f; val gb = h * 0.60f
                val net = Color(0x55FFFFFF)
                for (k in 0..8) { val x = gl + (gr - gl) * k / 8f; drawLine(net, Offset(x, gt), Offset(x, gb), 1.5f) }
                for (k in 0..5) { val y = gt + (gb - gt) * k / 5f; drawLine(net, Offset(gl, y), Offset(gr, y), 1.5f) }
                drawLine(Color.White, Offset(gl, gb), Offset(gl, gt), w * 0.025f, StrokeCap.Round)
                drawLine(Color.White, Offset(gl, gt), Offset(gr, gt), w * 0.025f, StrokeCap.Round)
                drawLine(Color.White, Offset(gr, gt), Offset(gr, gb), w * 0.025f, StrokeCap.Round)
                // cone
                val cx = w * 0.20f; val cb = h * 0.90f
                val cone = Path().apply { moveTo(cx, cb - h * 0.22f); lineTo(cx + w * 0.09f, cb); lineTo(cx - w * 0.09f, cb); close() }
                drawPath(cone, Brush.verticalGradient(listOf(Color(0xFFFFB347), Color(0xFFE8650C))))
                drawLine(Color.White, Offset(cx - w * 0.045f, cb - h * 0.10f), Offset(cx + w * 0.045f, cb - h * 0.10f), w * 0.02f)
                ball(Offset(w * 0.62f, h * 0.82f), w * 0.07f)
            }
        }
        if (ph >= 0f) {
            // obra: faixa de luz passando
            val x = w * (ph * 1.6f - 0.3f)
            drawRect(Brush.horizontalGradient(listOf(Color(0x00FFFFFF), Color(0x33FFFFFF), Color(0x00FFFFFF)), x - w * 0.25f, x + w * 0.25f))
        }
    }
}
