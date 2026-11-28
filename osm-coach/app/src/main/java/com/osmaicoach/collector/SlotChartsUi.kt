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
import androidx.compose.foundation.layout.fillMaxWidth as fillW

/** Ordem de exibição das abas do slot: "Gráficos" (id 9) fica ao lado de Histórico e os ids antigos (7 Diretor, 8 Aprendizado) não mudam. */
object SlotNav {
    const val CHARTS = 9
    val ORDER = listOf(0, 1, 2, 3, 4, 5, 6, CHARTS, 7, 8)
}

/** Aba "Gráficos" do slot (índice 9, exibida ao lado de Histórico). Cada barra traz o valor e a amostra em texto (não depende só de cor). */
@Composable
internal fun SlotChartsTab(slot: Int, onOpenResult: (Int) -> Unit) {
    val ctx = LocalContext.current
    val loaded = rememberLoaded<Pair<String, List<SlotCharts.Game>>?>(null, UiBus.version, "charts$slot") { runCatching { SlotCharts.load(Repo(ctx), slot) }.getOrNull() }
    // filtro vindo da bolha (confirmado no confronto): vale uma vez para este slot
    val initial = remember(slot) { Route.chartFilter?.takeIf { Route.chartSlot == slot }?.let { SlotCharts.Filter.fromJson(it) }.also { if (Route.chartSlot == slot) { Route.chartFilter = null; Route.chartSlot = 0 } } }
    var f by remember(slot) { mutableStateOf(initial ?: SlotCharts.Filter()) }
    if (loaded == null) { Text("Carregando…", color = C.MUTED); return }
    val all = loaded.second
    val g = SlotCharts.apply(all, f)
    Title("Gráficos do slot $slot")
    if (all.none { it.result != null }) { EmptyState("📈", "Sem partidas lidas neste slot", "Os gráficos usam só placares e leituras reais deste slot e desta competição. Leia o Calendário/Resultados no OSM."); return }
    // filtros
    fun row(content: @Composable () -> Unit) = content
    Row(Modifier.fillW().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterPill("Edição atual", !f.archived) { f = copyF(f, archived = false) }
        if (all.any { it.archived }) FilterPill("Anteriores", f.archived) { f = copyF(f, archived = true) }
        for (m in listOf("Batalha", "Liga", "Copa")) if (all.any { it.mode == m && it.archived == f.archived }) FilterPill(m, f.mode == m) { f = copyF(f, mode = if (f.mode == m) "" else m) }
        FilterPill("Casa", f.home == true) { f = copyF(f, home = if (f.home == true) null else true, homeSet = true) }
        FilterPill("Fora", f.home == false) { f = copyF(f, home = if (f.home == false) null else false, homeSet = true) }
        FilterPill("Só humanos", f.humanOnly) { f = copyF(f, humanOnly = !f.humanOnly) }
    }
    val buckets = all.map { it.bucket }.distinct().filter { it != "força desconhecida" }
    if (buckets.isNotEmpty()) Row(Modifier.fillW().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in buckets) FilterPill(b, f.bucket == b) { f = copyF(f, bucket = if (f.bucket == b) "" else b) }
    }
    val forms = all.mapNotNull { it.rivalFormation }.distinct(); val sts = all.mapNotNull { it.rivalStyle }.distinct()
    if (forms.isNotEmpty() || sts.isNotEmpty()) Row(Modifier.fillW().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (x in forms) FilterPill("Rival $x", f.rivalFormation == x) { f = copyF(f, rivalFormation = if (f.rivalFormation == x) "" else x) }
        for (x in sts) FilterPill("Rival: $x", f.rivalStyle == x) { f = copyF(f, rivalStyle = if (f.rivalStyle == x) "" else x) }
    }
    Text(if (f.active().isEmpty()) "Filtro: edição atual, todos os jogos (${g.size})" else "Filtro: " + f.active().joinToString(" • ") + " (${g.size} jogo(s))", color = C.MUTED, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
    if (g.isEmpty()) { Panel { Text("Nenhum jogo com este filtro. Tire um filtro para ver mais.", color = C.MUTED) }; return }

    // 1) planos em jogos parecidos
    val pc = SlotCharts.plans(g)
    Panel {
        PanelTitle("Planos em jogos parecidos")
        Text("Pontos por jogo (0–3) por configuração REALMENTE aplicada, contra humanos confirmados.", color = C.MUTED, fontSize = 12.sp)
        if (pc.rows.isEmpty()) Text(pc.note, color = C.MUTED, modifier = Modifier.padding(top = 6.dp))
        else {
            for (r in pc.rows) {
                Bar(r.label, r.ppg / 3.0, if (r === pc.best) C.OK else C.INFO, "%.2f pts/jogo • ${r.w}V ${r.e}E ${r.d}D • ${r.n} jogo(s)".format(r.ppg)) { }
                Text("   jogos: " + r.games.joinToString(", ") { "J${it.round ?: "?"} ${it.result}" }, color = C.MUTED, fontSize = 11.sp)
            }
            Text(pc.note, color = if (pc.comparable) C.MUTED else C.GOLD, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
    // 2) finalizações
    Panel {
        PanelTitle("Finalizações a favor e contra")
        val withR = g.filter { it.result != null && it.round != null }.sortedBy { it.round }
        val maxS = (withR.flatMap { listOfNotNull(it.shotsFor, it.shotsAgainst) }.maxOrNull() ?: 1).coerceAtLeast(1)
        if (withR.none { it.hasStats }) Text("Nenhum jogo com remates lidos. Abra a Análise/Resultado do jogo no OSM para ler as estatísticas (não é zero: é falta de leitura).", color = C.MUTED, fontSize = 12.sp)
        for (x in withR) {
            val score = if (x.gf != null && x.ga != null) "${x.gf}–${x.ga}" else "placar sem leitura"
            if (x.hasStats) Bar("J${x.round} vs ${x.rival ?: "?"} ($score)", x.shotsFor!!.toDouble() / maxS, C.PRIMARY, "a favor ${x.shotsFor} • contra ${x.shotsAgainst}") { onOpenResult(x.round!!) }
            else Text("J${x.round} vs ${x.rival ?: "?"} ($score): remates sem leitura", color = C.MUTED, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp).clickable { onOpenResult(x.round!!) })
        }
        Text("Chutes no alvo não são lidos separadamente: só \"remates\" totais. Expulsão/acampamento não são mostrados sem confirmação.", color = C.MUTED, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
    // 3) pontos e saldo por rodada
    val rp = SlotCharts.rounds(g)
    Panel {
        PanelTitle("Pontos por rodada")
        Text("Pontos de partida (V=3, E=1, D=0); não é o total oficial da Batalha.", color = C.MUTED, fontSize = 11.5.sp)
        for (r in rp) Bar("J${r.round}", (r.points ?: 0) / 3.0, when (r.points) { 3 -> C.OK; 1 -> C.GOLD; else -> C.BAD }, "${r.points} pt (" + (r.game.result ?: "?") + ")") { onOpenResult(r.round) }
        PanelTitle("Saldo de gols por rodada")
        val maxD = (rp.mapNotNull { it.goalDiff }.maxOfOrNull { Math.abs(it) } ?: 1).coerceAtLeast(1)
        for (r in rp) if (r.goalDiff != null) Bar("J${r.round}", Math.abs(r.goalDiff) / maxD.toDouble(), if (r.goalDiff >= 0) C.OK else C.BAD, (if (r.goalDiff > 0) "+" else "") + "${r.goalDiff} gols") { onOpenResult(r.round) }
        else Text("J${r.round}: placar sem leitura", color = C.MUTED, fontSize = 12.sp)
    }
    // 4) estilo do rival
    val sc = SlotCharts.styles(g)
    Panel {
        PanelTitle("Desempenho por estilo do rival")
        if (sc.rows.isEmpty()) Text("Nenhum estilo do rival lido nos jogos deste filtro.", color = C.MUTED, fontSize = 12.sp)
        for (r in sc.rows) Bar(r.style, r.ppg / 3.0, C.INFO, "%.2f pts/jogo • ${r.w}V ${r.e}E ${r.d}D • ${r.n} jogo(s)".format(r.ppg)) { }
        if (sc.unknown > 0) Text("${sc.unknown} jogo(s) com estilo do rival não lido: ficam fora (estilo desconhecido não vira estilo confirmado).", color = C.MUTED, fontSize = 11.5.sp)
    }
    // 5) cobertura
    val cv = SlotCharts.coverage(g)
    Panel {
        PanelTitle("Cobertura das leituras")
        Text("${cv.total} partida(s) com resultado • ${cv.withScore} com placar • ${cv.withStats} com estatísticas • ${cv.withApplied} com tática aplicada confirmada • ${cv.inComparison} entram no comparativo de táticas.", fontSize = 13.sp)
        for (r in cv.reasons) Text("• $r", color = C.MUTED, fontSize = 12.sp)
        Text("Dado que falta não é desempenho ruim: o jogo continua no placar e no histórico, só não entra onde falta confirmação.", color = C.MUTED, fontSize = 11.5.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun copyF(f: SlotCharts.Filter, archived: Boolean? = null, mode: String? = null, home: Boolean? = null, homeSet: Boolean = false, humanOnly: Boolean? = null,
                  bucket: String? = null, rivalFormation: String? = null, rivalStyle: String? = null) = SlotCharts.Filter(
    archived ?: f.archived, if (mode == null) f.mode else mode.ifEmpty { null }, if (homeSet) home else f.home, humanOnly ?: f.humanOnly,
    if (bucket == null) f.bucket else bucket.ifEmpty { null }, if (rivalFormation == null) f.rivalFormation else rivalFormation.ifEmpty { null }, if (rivalStyle == null) f.rivalStyle else rivalStyle.ifEmpty { null })

@Composable
private fun Bar(label: String, fraction: Double, color: androidx.compose.ui.graphics.Color, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { onClick() }) {
        Text(label, fontSize = 12.sp, maxLines = 2)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(C.BORDER)) {
                Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02, 1.0).toFloat()).height(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
            }
        }
        Text(value, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = C.MUTED)
    }
}
