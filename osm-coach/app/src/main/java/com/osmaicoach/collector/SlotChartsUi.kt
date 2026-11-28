package com.osmaicoach.collector

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Ordem de exibição das abas do slot: "Gráficos" (id 9) fica ao lado de Histórico e os ids antigos (7 Diretor, 8 Aprendizado) não mudam. */
object SlotNav {
    const val CHARTS = 9
    val ORDER = listOf(0, 1, 2, 3, 4, 5, 6, CHARTS, 7, 8)
}

/** Aba "Gráficos" (estilo BI, automática): o app escolhe o que mostrar; só dá para alternar "Este slot / Todos os slots" e, havendo mais de um tipo de competição, qual deles. */
@Composable
internal fun SlotChartsTab(slot: Int, onOpenResult: (Int) -> Unit) {
    val ctx = LocalContext.current
    var all by remember(slot) { mutableStateOf(false) }
    var modeSel by remember(slot, all) { mutableStateOf<String?>(null) }
    val data = rememberLoaded<List<SlotCharts.Game>?>(null, UiBus.version, "bi$slot$all") {
        runCatching {
            val repo = Repo(ctx)
            if (all) (1..4).flatMap { s -> SlotCharts.load(repo, s).second } else SlotCharts.load(repo, slot).second
        }.getOrNull()
    }
    Title(if (all) "Gráficos — todos os slots" else "Gráficos — slot $slot")
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterPill("Este slot", !all) { all = false }
        FilterPill("Todos os slots", all) { all = true }
    }
    if (data == null) { Text("Carregando…", color = C.MUTED); return }
    val current = data.filter { !it.archived }
    val modes = current.filter { it.result != null }.map { it.mode }.distinct()
    val mode = modeSel?.takeIf { it in modes } ?: SlotBi.mainMode(current)
    if (mode == null) { EmptyState("📈", "Ainda sem partidas lidas", "Os gráficos aparecem sozinhos quando o app lê o Calendário/Resultados dos jogos. Nada precisa ser configurado."); return }
    if (modes.size > 1) Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (m in modes) FilterPill("$m (${current.count { it.mode == m && it.result != null }})", m == mode) { modeSel = m }
    }
    val g = current.filter { it.mode == mode }
    val k = SlotBi.kpi(g)
    Text("Temporada atual • $mode" + (if (modes.size > 1) " (Liga, Copa e Batalha nunca são somadas)" else ""), color = C.MUTED, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))

    // KPIs
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Kpi("Aproveitamento", "${k.rate}%", "${k.pts} pts em ${k.n} jogos", Modifier.weight(1f), if (k.rate >= 60) C.OK else if (k.rate >= 40) C.GOLD else C.BAD)
        Kpi("Campanha", "${k.w}V ${k.e}E ${k.d}D", SlotBi.streak(g) ?: "", Modifier.weight(1f), C.INFO)
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Kpi("Pts por jogo", "%.2f".format(k.ppg).replace('.', ','), "máximo 3,00", Modifier.weight(1f), C.PRIMARY)
        Kpi("Gols", if (k.goalGames > 0) "${k.gf}–${k.ga}" else "sem leitura", if (k.goalGames > 0) "saldo ${if (k.gf - k.ga > 0) "+" else ""}${k.gf - k.ga} (${k.goalGames} jogos com placar)" else "", Modifier.weight(1f), C.INFO)
    }

    // achados automáticos
    val ins = SlotBi.insights(g)
    if (ins.isNotEmpty()) Panel {
        PanelTitle("O que o app percebeu")
        for (i in ins) Text((when (i.kind) { "good" -> "✅ "; "warn" -> "⚠ "; else -> "ℹ " }) + i.text, fontSize = 13.sp, color = if (i.kind == "warn") C.GOLD else C.TEXT, modifier = Modifier.padding(vertical = 2.dp))
    }

    // evolução
    val ev = SlotBi.evolution(g)
    if (ev.rounds.size >= 2) Panel {
        PanelTitle("Evolução na temporada")
        Text("Pontos acumulados (linha) e resultado de cada rodada (quadrados; toque abre o jogo)", color = C.MUTED, fontSize = 11.5.sp)
        LineChart(ev.cumulative.map { it.toFloat() }, C.PRIMARY)
        Text("Aproveitamento nos últimos 5 jogos (%)", color = C.MUTED, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp))
        LineChart(ev.rolling.map { it.toFloat() }, C.INFO, maxFixed = 100f)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in ev.rounds.indices) {
                val r = ev.results[i]
                Box(Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(when (r) { "V" -> C.OK; "E" -> C.GOLD; else -> C.BAD }).clickable { onOpenResult(ev.rounds[i]) }, contentAlignment = Alignment.Center) {
                    Text(r, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = androidx.compose.ui.graphics.Color(0xFF081A3C))
                }
            }
        }
        Text("Rodadas: " + ev.rounds.joinToString(" ") { "J$it" }, color = C.MUTED, fontSize = 10.5.sp)
    }

    // o que funciona
    val (conf, sug) = SlotBi.tactics(g)
    Panel {
        PanelTitle("Qual tática está funcionando")
        if (conf.isEmpty() && sug.isEmpty()) Text("Ainda sem táticas registradas neste período.", color = C.MUTED, fontSize = 12.sp)
        if (conf.isNotEmpty()) { Text("Jogada e confirmada (tela Tática lida)", color = C.OK, fontSize = 12.sp, fontWeight = FontWeight.SemiBold); for (r in conf) RowBar(r) }
        if (sug.isNotEmpty()) {
            Text("Sugerida pelo app — aplicação NÃO confirmada (pode ter sido outra)", color = C.GOLD, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            for (r in sug) RowBar(r)
        }
        Text("Verde = funcionando (≥2,0 pts/jogo com 3+ jogos); vermelho = atenção (≤1,0). Amostra pequena não conclui nada.", color = C.MUTED, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }

    // casa/fora e humano/cpu
    val ha = SlotBi.homeAway(g); val hb = SlotBi.humanBot(g)
    if (ha.isNotEmpty() || hb.isNotEmpty()) Panel {
        PanelTitle("Onde rende mais")
        for (r in ha + hb) RowBar(r)
    }
    val st = SlotBi.rivalStyles(g)
    if (st.isNotEmpty()) Panel {
        PanelTitle("Contra o estilo do rival")
        for (r in st) RowBar(r)
        Text("Só estilos realmente lidos; jogos sem estilo lido ficam de fora.", color = C.MUTED, fontSize = 11.sp)
    }
    if (all) {
        val bs = SlotBi.bySlot(g)
        if (bs.size > 1) Panel { PanelTitle("Comparação entre slots"); for (r in bs) RowBar(r) }
    }

    // gols e finalizações por jogo
    val withR = g.filter { it.result != null && it.round != null && !all }.sortedBy { it.round }
    if (withR.isNotEmpty()) Panel {
        PanelTitle("Gols e finalizações por jogo")
        val maxS = (withR.flatMap { listOfNotNull(it.shotsFor, it.shotsAgainst, it.gf, it.ga) }.maxOrNull() ?: 1).coerceAtLeast(1)
        for (x in withR) {
            val score = if (x.gf != null && x.ga != null) "${x.gf}–${x.ga}" else "placar sem leitura"
            val shots = if (x.hasStats) "remates ${x.shotsFor} × ${x.shotsAgainst}" else "remates sem leitura"
            Bar("J${x.round} vs ${x.rival ?: "?"}: $score • $shots", (x.shotsFor ?: x.gf ?: 0).toDouble() / maxS, when (x.result) { "V" -> C.OK; "E" -> C.GOLD; else -> C.BAD }, "resultado ${x.result}") { onOpenResult(x.round!!) }
        }
        Text("Falta de leitura aparece como \"sem leitura\", nunca como zero.", color = C.MUTED, fontSize = 11.sp)
    }

    // cobertura
    val cv = SlotCharts.coverage(g)
    Text("Base dos gráficos: ${cv.total} jogos com resultado • ${cv.withScore} com placar • ${cv.withStats} com remates • ${cv.withApplied} com tática jogada confirmada.", color = C.MUTED, fontSize = 11.5.sp, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Kpi(title: String, value: String, sub: String, modifier: Modifier, color: androidx.compose.ui.graphics.Color) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(C.SURFACE).padding(12.dp)) {
        Text(title, fontSize = 11.5.sp, color = C.MUTED)
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = color)
        if (sub.isNotBlank()) Text(sub, fontSize = 11.sp, color = C.MUTED)
    }
}

@Composable
private fun RowBar(r: SlotBi.Row) {
    val col = when (r.verdict) { "funcionando" -> C.OK; "atenção" -> C.BAD; "poucos jogos" -> C.MUTED; else -> C.INFO }
    Bar(r.label, r.ppg / 3.0, if (r.confirmed) col else col.copy(alpha = 0.55f), "%.2f pts/jogo • ${r.w}V ${r.e}E ${r.d}D • ${r.n} jogo(s) • ${r.verdict}".format(r.ppg).replace('.', ',')) { }
}

@Composable
private fun LineChart(values: List<Float>, color: androidx.compose.ui.graphics.Color, maxFixed: Float? = null) {
    if (values.size < 2) return
    val maxV = (maxFixed ?: values.max()).coerceAtLeast(1f)
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(90.dp).padding(vertical = 4.dp)) {
        val w = size.width; val h = size.height
        val step = w / (values.size - 1)
        val path = androidx.compose.ui.graphics.Path()
        values.forEachIndexed { i, v ->
            val x = i * step; val y = h - (v / maxV) * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawLine(color.copy(alpha = 0.25f), androidx.compose.ui.geometry.Offset(0f, h), androidx.compose.ui.geometry.Offset(w, h), strokeWidth = 2f)
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f))
        values.forEachIndexed { i, v -> drawCircle(color, 6f, androidx.compose.ui.geometry.Offset(i * step, h - (v / maxV) * h)) }
    }
    Text("de ${values.first().toInt()} a ${values.last().toInt()} (pico ${values.max().toInt()})", color = C.MUTED, fontSize = 10.5.sp)
}

@Composable
private fun Bar(label: String, fraction: Double, color: androidx.compose.ui.graphics.Color, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { onClick() }) {
        Text(label, fontSize = 12.sp, maxLines = 3)
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(C.BORDER)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02, 1.0).toFloat()).height(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        }
        Text(value, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = C.MUTED)
    }
}
