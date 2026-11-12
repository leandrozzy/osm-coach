package com.osmaicoach.collector

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.conflate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal fun formationLines(f: String): List<Int> = Formations.lines(f)

internal val CY = Color(0xFF29B6F6)
internal val OR = Color(0xFFFFA726)
internal val RD = Color(0xFFE5484D)

internal fun DrawScope.arrow(from: Offset, to: Offset, color: Color, width: Float) {
    drawLine(color, from, to, width)
    val dx = to.x - from.x
    val dy = to.y - from.y
    val len = sqrt(dx * dx + dy * dy)
    if (len < 1f) return
    val ux = dx / len
    val uy = dy / len
    val head = width * 3.2f
    drawLine(color, to, Offset(to.x - ux * head - uy * head * 0.6f, to.y - uy * head + ux * head * 0.6f), width)
    drawLine(color, to, Offset(to.x - ux * head + uy * head * 0.6f, to.y - uy * head - ux * head * 0.6f), width)
}

/** Setas como nas telas "Avançadas" do OSM: ciano = ataque/pressão, laranja = apoio/recuo. */
internal fun arrowSet(option: String): List<Triple<Float, Float, Color>> {
    val n = Txt.norm(option)
    return when {
        n.contains("atacar apenas") -> listOf(Triple(0f, -1f, CY))
        n.contains("ajudar a defender") || n.contains("ajudar a defesa") ->
            listOf(Triple(0f, -1f, CY), Triple(-0.7f, 0.7f, OR), Triple(0.7f, 0.7f, OR))
        n.contains("manter") -> listOf(Triple(-1f, 0f, OR), Triple(1f, 0f, OR))
        n.contains("pressionar") -> listOf(Triple(0f, -1f, CY), Triple(-0.7f, -0.7f, OR), Triple(0.7f, -0.7f, OR))
        n.contains("defender atras") -> listOf(Triple(0f, 1f, OR), Triple(-0.7f, 0.7f, OR), Triple(0.7f, 0.7f, OR))
        n.contains("ajudar meio") || n.contains("apoiar meio") -> listOf(Triple(0f, 1f, OR), Triple(0f, -1f, CY))
        n.contains("atacantes") || n.contains("laterais") -> listOf(Triple(-1f, -0.6f, CY), Triple(1f, -0.6f, CY))
        else -> emptyList()
    }
}

@Composable
internal fun SectorIcon(option: String) {
    Canvas(Modifier.size(72.dp, 88.dp).clip(RoundedCornerShape(12.dp))) {
        // mini campo em perspectiva, com as setas no chão (mesma câmera do campo grande)
        val pv = PitchView(size.width, size.height)
        drawRect(Brush.verticalGradient(listOf(Color(0xFF050B18), Color(0xFF0B1A33))))
        for (i in 0 until 6) {
            val v0 = i / 6f
            val v1 = (i + 1) / 6f
            quad(pv.p(0f, v0), pv.p(1f, v0), pv.p(1f, v1), pv.p(0f, v1), if (i % 2 == 0) Color(0xFF2E8B3E) else Color(0xFF267A35))
        }
        val lw = 1.4f * density
        pitchLine(pv, listOf(0.04f to 0.02f, 0.96f to 0.02f, 0.96f to 0.98f, 0.04f to 0.98f), Color(0xCCFFFFFF), lw, closed = true)
        val ring = (0..32).map { i ->
            val a = i / 32f * 2f * Math.PI.toFloat()
            (0.5f + 0.2f * kotlin.math.cos(a)) to (0.5f + 0.14f * kotlin.math.sin(a))
        }
        pitchLine(pv, ring, Color(0xCCFFFFFF), lw)
        for ((dx, dy, col) in arrowSet(option)) {
            arrow(pv.p(0.5f + dx * 0.08f, 0.5f - dy * 0.06f), pv.p(0.5f + dx * 0.4f, 0.5f - dy * 0.36f), col, 3.2f * density)
        }
        val c = pv.p(0.5f, 0.5f)
        drawOval(Color(0x66000000), topLeft = Offset(c.x - 7f * density, c.y - 1f * density), size = Size(14f * density, 5f * density))
        drawCircle(
            Brush.radialGradient(listOf(Color.White, Color(0xFFB0BEC5)), center = Offset(c.x - 2f * density, c.y - 6f * density), radius = 7f * density),
            radius = 5.5f * density, center = Offset(c.x, c.y - 4f * density)
        )
    }
}

/** Campo em perspectiva (trapézio), como a tela de análise do OSM. */
internal fun DrawScope.miniPitch(w: Float, h: Float) {
    val top = h * 0.14f
    val bottom = h * 0.92f
    val inset = w * 0.13f
    val path = androidx.compose.ui.graphics.Path()
    path.moveTo(inset, top)
    path.lineTo(w - inset, top)
    path.lineTo(w * 0.97f, bottom)
    path.lineTo(w * 0.03f, bottom)
    path.close()
    drawPath(path, Brush.verticalGradient(listOf(Color(0xFF2E8B3E), Color(0xFF1F6B30)), startY = top, endY = bottom))
    // faixas do gramado (cortadas pelo trapézio) e luz no centro
    clipPath(path) {
        for (i in 0 until 10 step 2) drawRect(Color(0x14FFFFFF), topLeft = Offset(w * i / 10f, 0f), size = Size(w / 10f, h))
        drawRect(Brush.radialGradient(listOf(Color(0x30FFFFFF), Color(0x00FFFFFF)), center = Offset(w * 0.5f, h * 0.5f), radius = w * 0.6f))
    }
    drawPath(path, Color(0xCCFFFFFF), style = Stroke(2f))
    drawLine(Color(0xCCFFFFFF), Offset(w * 0.5f, top), Offset(w * 0.5f, bottom), 2f)
    drawOval(Color(0xCCFFFFFF), topLeft = Offset(w * 0.43f, h * 0.40f), size = Size(w * 0.14f, h * 0.22f), style = Stroke(2f))
}

@Composable
internal fun StyleIcon(style: String) {
    val n = Txt.norm(style)
    Canvas(Modifier.size(132.dp, 78.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0E2A5A))) {
        val w = size.width
        val h = size.height
        miniPitch(w, h)
        when {
            n.contains("alas") -> {
                arrow(Offset(w * 0.18f, h * 0.34f), Offset(w * 0.86f, h * 0.34f), RD, 6f)
                arrow(Offset(w * 0.12f, h * 0.76f), Offset(w * 0.9f, h * 0.76f), RD, 6f)
            }
            n.contains("passe") -> {
                val pts = listOf(Offset(0.14f, 0.78f), Offset(0.32f, 0.36f), Offset(0.5f, 0.74f), Offset(0.68f, 0.34f), Offset(0.88f, 0.52f))
                for (i in 0 until pts.size - 2) drawLine(RD, Offset(pts[i].x * w, pts[i].y * h), Offset(pts[i + 1].x * w, pts[i + 1].y * h), 6f)
                arrow(Offset(pts[3].x * w, pts[3].y * h), Offset(pts[4].x * w, pts[4].y * h), RD, 6f)
            }
            n.contains("remate") -> {
                arrow(Offset(w * 0.2f, h * 0.8f), Offset(w * 0.8f, h * 0.3f), RD, 6f)
                drawCircle(Color.White, radius = 6f, center = Offset(w * 0.2f, h * 0.8f))
                drawCircle(Color.White, radius = 6f, center = Offset(w * 0.52f, h * 0.56f))
            }
            n.contains("contra") -> {
                // recua e sai em velocidade: seta curta para trás + seta longa para o gol adversário
                arrow(Offset(w * 0.78f, h * 0.30f), Offset(w * 0.46f, h * 0.30f), OR, 5f)
                arrow(Offset(w * 0.16f, h * 0.84f), Offset(w * 0.88f, h * 0.34f), RD, 7f)
                drawCircle(Color.White, radius = 6f, center = Offset(w * 0.16f, h * 0.84f))
            }
            n.contains("long") || n.contains("bola") -> {
                // lançamento: arco da defesa até o ataque
                var prev = Offset(w * 0.14f, h * 0.84f)
                for (i in 1..10) {
                    val t = i / 10f
                    val x = w * (0.14f + 0.74f * t)
                    val y = h * (0.84f - 0.50f * t) - h * 0.30f * (1f - (2f * t - 1f) * (2f * t - 1f))
                    val cur = Offset(x, y)
                    if (i < 10) drawLine(RD, prev, cur, 6f) else arrow(prev, cur, RD, 6f)
                    prev = cur
                }
            }
        }
    }
}

/**
 * Campo em perspectiva (estilo OSM): u = 0..1 de lado a lado, v = 0 (meu gol, embaixo) .. 1 (gol rival, no fundo).
 * A largura encolhe com a distância e as faixas do fundo ficam mais finas, como uma câmera atrás do gol.
 */
internal class PitchView(val w: Float, val h: Float) {
    val top = h * 0.08f
    val bottom = h * 0.97f
    private val halfNear = w * 0.47f
    private val halfFar = w * 0.30f

    fun y(v: Float): Float {
        val f = (1f - v) / (1f + 0.55f * v)
        return top + (bottom - top) * f
    }

    fun half(yy: Float): Float = halfFar + (halfNear - halfFar) * ((yy - top) / (bottom - top))

    fun p(u: Float, v: Float): Offset {
        val yy = y(v)
        return Offset(w / 2f + (u - 0.5f) * 2f * half(yy), yy)
    }

    /** Escala do objeto na profundidade v (1 = perto). */
    fun scale(v: Float): Float = half(y(v)) / halfNear
}

internal fun DrawScope.quad(a: Offset, b: Offset, c: Offset, d: Offset, color: Color) {
    val path = androidx.compose.ui.graphics.Path()
    path.moveTo(a.x, a.y)
    path.lineTo(b.x, b.y)
    path.lineTo(c.x, c.y)
    path.lineTo(d.x, d.y)
    path.close()
    drawPath(path, color)
}

internal fun DrawScope.pitchLine(pv: PitchView, pts: List<Pair<Float, Float>>, color: Color, width: Float, closed: Boolean = false) {
    val all = if (closed) pts + pts.first() else pts
    for (i in 0 until all.size - 1) {
        drawLine(color, pv.p(all[i].first, all[i].second), pv.p(all[i + 1].first, all[i + 1].second), width, cap = StrokeCap.Round)
    }
}

internal fun DrawScope.pitch3d(pv: PitchView) {
    val w = size.width
    val h = size.height
    // estádio: céu noturno e arquibancada escura
    drawRect(Brush.verticalGradient(listOf(Color(0xFF050B18), Color(0xFF0B1A33), Color(0xFF07101F))))
    drawRect(Color(0x33FFFFFF), topLeft = Offset(0f, pv.top - h * 0.035f), size = Size(w, h * 0.012f))
    // gramado em faixas
    val bands = 12
    for (i in 0 until bands) {
        val v0 = i / bands.toFloat()
        val v1 = (i + 1) / bands.toFloat()
        val c = if (i % 2 == 0) Color(0xFF2E8B3E) else Color(0xFF267A35)
        quad(pv.p(0f, v0), pv.p(1f, v0), pv.p(1f, v1), pv.p(0f, v1), c)
    }
    // luz dos refletores (centro claro, bordas escuras)
    drawRect(
        Brush.radialGradient(listOf(Color(0x33FFFFFF), Color(0x00FFFFFF)), center = Offset(w / 2f, h * 0.45f), radius = w * 0.75f)
    )
    drawRect(Brush.verticalGradient(listOf(Color(0x55000000), Color(0x00000000)), startY = pv.top, endY = pv.top + h * 0.25f))
    val line = Color(0xE6FFFFFF)
    val lw = 2.2f * density
    val m = 0.03f
    pitchLine(pv, listOf(m to 0.015f, 1f - m to 0.015f, 1f - m to 0.985f, m to 0.985f), line, lw, closed = true)
    pitchLine(pv, listOf(m to 0.5f, 1f - m to 0.5f), line, lw)
    // círculo central (projetado ponto a ponto, vira elipse)
    val circle = (0..48).map { i ->
        val a = i / 48f * 2f * Math.PI.toFloat()
        (0.5f + 0.13f * kotlin.math.cos(a)) to (0.5f + 0.085f * kotlin.math.sin(a))
    }
    pitchLine(pv, circle, line, lw)
    drawCircle(line, radius = 3f * density * pv.scale(0.5f), center = pv.p(0.5f, 0.5f))
    // grandes áreas e pequenas áreas dos dois lados
    for ((base, dir) in listOf(0.015f to 1f, 0.985f to -1f)) {
        pitchLine(pv, listOf(0.22f to base, 0.22f to base + dir * 0.15f, 0.78f to base + dir * 0.15f, 0.78f to base), line, lw)
        pitchLine(pv, listOf(0.38f to base, 0.38f to base + dir * 0.055f, 0.62f to base + dir * 0.055f, 0.62f to base), line, lw)
        drawCircle(line, radius = 2.5f * density * pv.scale(base), center = pv.p(0.5f, base + dir * 0.105f))
    }
    // gols com traves em pé (altura proporcional à profundidade)
    for (v in listOf(0.015f, 0.985f)) {
        val l = pv.p(0.44f, v)
        val r = pv.p(0.56f, v)
        val gh = 26f * density * pv.scale(v)
        drawRect(Color(0x33FFFFFF), topLeft = Offset(l.x, l.y - gh), size = Size(r.x - l.x, gh))
        drawLine(Color.White, l, Offset(l.x, l.y - gh), 3f * density * pv.scale(v))
        drawLine(Color.White, r, Offset(r.x, r.y - gh), 3f * density * pv.scale(v))
        drawLine(Color.White, Offset(l.x, l.y - gh), Offset(r.x, r.y - gh), 3f * density * pv.scale(v))
    }
}

@Composable
internal fun LineupPitch(plan: JSONObject) {
    val rows = ArrayList<List<Pair<String, Int>>>()
    val arr = plan.optJSONArray("lineup")
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val r = arr.optJSONArray(i) ?: continue
            val row = ArrayList<Pair<String, Int>>()
            for (j in 0 until r.length()) {
                val o = r.optJSONObject(j)
                row.add(Pair(o?.optString("n") ?: "?", o?.optInt("s") ?: 0))
            }
            rows.add(row)
        }
    }
    if (rows.isEmpty()) {
        val ls = formationLines(plan.optString("formation"))
        if (ls.isNotEmpty()) {
            rows.add(listOf(Pair("GOL", 0)))
            for (n in ls) rows.add(List(n) { Pair("", 0) })
        }
    }
    val advA = arrowSet(plan.optString("advAttack"))
    val advM = arrowSet(plan.optString("advMid"))
    val advD = arrowSet(plan.optString("advDef"))
    Canvas(Modifier.fillMaxWidth().height(440.dp).clip(RoundedCornerShape(20.dp))) {
        val dp = density
        val pv = PitchView(size.width, size.height)
        pitch3d(pv)
        if (rows.isEmpty()) return@Canvas
        val namePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
        val numPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
            setShadowLayer(3f, 0f, 1.5f, android.graphics.Color.BLACK)
        }
        val k = rows.size - 1
        // Desenha do fundo para a frente: quem está perto da câmera fica por cima.
        val order = rows.indices.sortedByDescending { ri -> if (ri == 0) 0.06f else if (k <= 1) 0.5f else 0.24f + (ri - 1) * (0.52f / (k - 1)) }
        for (ri in order) {
            val row = rows[ri]
            val v = if (ri == 0) 0.06f else if (k <= 1) 0.5f else 0.24f + (ri - 1) * (0.52f / (k - 1))
            val arrows: List<Triple<Float, Float, Color>> = when {
                ri == 0 -> emptyList()
                ri == 1 -> advD
                ri == rows.size - 1 -> advA
                else -> advM
            }
            val markerColor = when {
                ri == 0 -> Color(0xFFFFC83D)
                ri == 1 -> Color(0xFF3D8BFF)
                ri == rows.size - 1 -> Color(0xFFFF5C5C)
                else -> Color(0xFF9B6BFF)
            }
            val sc = pv.scale(v)
            for ((j, pl) in row.withIndex()) {
                val u = 0.06f + 0.88f * (j + 1f) / (row.size + 1f)
                val foot = pv.p(u, v)
                // setas no chão, em perspectiva (dy negativo no ícone = para o ataque = v maior)
                for ((dx, dy, col) in arrows) {
                    val a = pv.p(u + dx * 0.045f, v - dy * 0.03f)
                    val b = pv.p(u + dx * 0.11f, v - dy * 0.085f)
                    arrow(a, b, col.copy(alpha = 0.95f), 3f * dp * sc)
                }
                val r = 17f * dp * sc
                // ficha do jogador (mesmo visual das ilustrações da tática), número da força no centro
                val tok = r * 1.05f
                val cy = foot.y - tok
                token(Offset(foot.x, cy), tok, markerColor, jersey = false)
                drawIntoCanvas { cv ->
                    if (pl.second > 0) {
                        numPaint.textSize = 12.5f * dp * sc
                        cv.nativeCanvas.drawText(pl.second.toString(), foot.x, cy + numPaint.textSize * 0.36f, numPaint)
                    }
                }
                if (pl.first.isNotBlank()) {
                    namePaint.textSize = 10f * dp * (0.75f + 0.25f * sc)
                    val label = if (pl.first.length > 12) pl.first.take(11) + "…" else pl.first
                    val tw = namePaint.measureText(label)
                    val top = foot.y + r * 0.1f
                    drawRoundRect(
                        Color(0xD90B1220), topLeft = Offset(foot.x - tw / 2f - 6f * dp, top),
                        size = Size(tw + 12f * dp, namePaint.textSize + 6f * dp), cornerRadius = CornerRadius(7f * dp, 7f * dp)
                    )
                    drawRoundRect(
                        markerColor, topLeft = Offset(foot.x - tw / 2f - 6f * dp, top),
                        size = Size(3f * dp, namePaint.textSize + 6f * dp), cornerRadius = CornerRadius(2f * dp, 2f * dp)
                    )
                    drawIntoCanvas { cv -> cv.nativeCanvas.drawText(label, foot.x, top + namePaint.textSize + 1.5f * dp, namePaint) }
                }
            }
        }
    }
}

