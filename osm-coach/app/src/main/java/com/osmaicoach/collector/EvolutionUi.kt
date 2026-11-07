package com.osmaicoach.collector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val GOOD = Color(0xFF4ADE80)
private val BADC = Color(0xFFFF6B6B)
private val MUTE = Color(0xFF9FB6D8)

private fun signColor(v: Double?): Color = when {
    v == null -> MUTE
    v > 0.0001 -> GOOD
    v < -0.0001 -> BADC
    else -> MUTE
}

/** Cartão no estilo do jogo: legenda azul pequena em cima e o conteúdo embaixo. */
@Composable
private fun EvoCard(title: String, icon: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).osmPanel().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x332AB4FF)), contentAlignment = Alignment.Center) { Text(icon, fontSize = 17.sp) }
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, modifier = Modifier.padding(start = 10.dp))
        }
        Column(Modifier.padding(top = 12.dp)) { content() }
    }
}

/** Número grande com rótulo e variação colorida (verde sobe, vermelho cai). */
@Composable
private fun Metric(label: String, value: String, delta: String?, deltaColor: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF0A1830)).border(1.dp, Color(0x332AB4FF), RoundedCornerShape(14.dp)).padding(horizontal = 6.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 12.sp, color = MUTE, maxLines = 1)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1, modifier = Modifier.padding(vertical = 2.dp))
        if (delta != null) Text(delta, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = deltaColor, maxLines = 1)
    }
}

@Composable
private fun ResultDot(r: String) {
    val c = when (r) {
        "V" -> GOOD
        "D" -> BADC
        else -> OsmColors.YELLOW
    }
    Box(
        Modifier.padding(end = 4.dp).width(24.dp).height(24.dp).clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) { Text(r, fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0B1C3E)) }
}

/** Valor do elenco do seu time, semana a semana: cada barra tem o valor em cima e a data embaixo; a laranja é a semana atual. */
@Composable
private fun WeekBars(weeks: List<Weekly.Week>) {
    val list = weeks.takeLast(6).filter { it.value != null }
    if (list.isEmpty()) return
    val vals = list.map { it.value!! }
    val mx = vals.max()
    val mn = vals.min() * 0.85
    val fmt = java.text.SimpleDateFormat("dd/MM", java.util.Locale("pt", "BR"))
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text("Valor do elenco por semana", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Laranja = esta semana • azul = semanas anteriores", fontSize = 12.sp, color = MUTE, modifier = Modifier.padding(bottom = 8.dp))
        Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            list.forEachIndexed { i, w ->
                val v = w.value!!
                val f = if (mx <= 0.0) 1f else (v / mx).toFloat().coerceIn(0.08f, 1f)
                val last = i == list.size - 1
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Text(moneyText(v), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = if (last) OsmColors.YELLOW else Color.White, maxLines = 1)
                    Box(
                        Modifier.padding(top = 4.dp).fillMaxWidth().height((70 * f).dp).clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                            .background(Brush.verticalGradient(if (last) listOf(OsmColors.YELLOW, Color(0xFFFF9F1A)) else listOf(OsmColors.BLUE, Color(0xFF0E5FB8))))
                    )
                    Text(fmt.format(java.util.Date(w.at)), fontSize = 11.sp, color = MUTE, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
                }
            }
        }
    }
}

/** Placar da tática: quanto o app acertou e qual tática mais rendeu. */
@Composable
fun ScoreboardCard(s: Scoreboard.Stats) {
    EvoCard("Placar da tática", "🎯") {
        if (s.games == 0) {
            Text("Ainda sem jogos com resultado. Depois de cada jogo, abra o resultado no OSM: o placar começa a contar.", fontSize = 13.sp, color = MUTE)
            return@EvoCard
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("jogos", "${s.games}", "${s.v}V ${s.e}E ${s.d}D", MUTE, Modifier.weight(1f))
            Metric("vitórias", "${s.winRate}%", s.avgWinPred?.let { "previsto $it%" }, MUTE, Modifier.weight(1f))
            Metric(
                "acertos", if (s.predicted > 0) "${(s.hits * 100.0 / s.predicted).roundToInt()}%" else "—",
                if (s.predicted > 0) "${s.hits} de ${s.predicted}" else null, MUTE, Modifier.weight(1f)
            )
        }
        if (s.lastFive.isNotEmpty()) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Últimos: ", fontSize = 13.sp, color = MUTE)
                for (r in s.lastFive) ResultDot(r)
            }
        }
        s.best?.let {
            Text(
                "🏆 Melhor: ${it.label} — ${it.v}V ${it.e}E ${it.d}D (${"%.1f".format(it.points).replace('.', ',')} pts/jogo)",
                fontSize = 13.sp, color = GOOD, modifier = Modifier.padding(top = 8.dp)
            )
        }
        s.worst?.takeIf { it.label != s.best?.label }?.let {
            Text(
                "⚠ Pior: ${it.label} — ${it.v}V ${it.e}E ${it.d}D: o app evita repetir.",
                fontSize = 13.sp, color = BADC, modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** Evolução do time: semana, livro de caixa, ganho do treino e alertas de valor. */
@Composable
fun EvolutionPanel(p: Repo.ProgressData, team: String? = null, slot: Int = 0) {
    // de quem são estes números
    if (team != null) Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x222AB4FF)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("📌", fontSize = 15.sp)
        Text("  Seu time" + (if (slot > 0) " (S$slot)" else "") + ": ", fontSize = 13.sp, color = MUTE)
        Text(team, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    // ---- semana
    EvoCard("Resumo da semana", "📅") {
        val d = p.week
        if (d == null) {
            Text("Leia o Plantel do seu time para começar o resumo semanal.", fontSize = 13.sp, color = MUTE)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("valor do elenco", d.cur.value?.let { moneyText(it) } ?: "?", d.value?.let { moneyText(it, true) }, signColor(d.value), Modifier.weight(1f))
                Metric("caixa", d.cur.cash?.let { moneyText(it) } ?: "?", d.cash?.let { moneyText(it, true) }, signColor(d.cash), Modifier.weight(1f))
                Metric(
                    "força do XI", d.cur.xi?.let { "%.1f".format(it).replace('.', ',') } ?: "?",
                    d.xi?.let { (if (it > 0) "+" else "") + "%.1f".format(it).replace('.', ',') }, signColor(d.xi), Modifier.weight(1f)
                )
            }
            if (d.prev == null) {
                Text("Primeira semana registrada: a comparação aparece na próxima.", fontSize = 12.5.sp, color = MUTE, modifier = Modifier.padding(top = 8.dp))
            } else if (d.games > 0) {
                Text("Nesta semana: ${d.games} jogo(s), ${d.wins} vitória(s).", fontSize = 13.sp, color = Color.White, modifier = Modifier.padding(top = 8.dp))
            }
            WeekBars(p.weeks)
        }
    }
    // ---- livro de caixa
    EvoCard("Livro de caixa", "💰") {
        val l = p.ledger
        if (l.buys + l.sells == 0) {
            Text(
                "Compras e vendas aparecem aqui sozinhas: jogador novo no Plantel é contratação; quem some numa leitura completa do Plantel foi vendido.",
                fontSize = 13.sp, color = MUTE
            )
            return@EvoCard
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("gasto", moneyText(l.spent), "${l.buys} compra(s)", MUTE, Modifier.weight(1f))
            Metric("recebido", moneyText(l.received), "${l.sells} venda(s)", MUTE, Modifier.weight(1f))
            Metric("saldo", moneyText(l.balance, true), null, signColor(l.balance), Modifier.weight(1f))
        }
        for (deal in l.deals.take(6)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(deal.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                val txt = when {
                    deal.profit != null -> "${moneyText(deal.bought!!)} → ${moneyText(deal.sold!!)}"
                    deal.sold != null -> "vendido ${moneyText(deal.sold)}"
                    deal.bought != null -> "comprado ${moneyText(deal.bought)}"
                    else -> "?"
                }
                Text(txt + (if (deal.estimated) "*" else ""), fontSize = 13.sp, color = MUTE)
                deal.profit?.let {
                    Text(" ${moneyText(it, true)}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = signColor(it))
                }
            }
        }
        if (l.deals.any { it.estimated }) {
            Text("* preço estimado pelo valor do Plantel (o da lista de transferências vale quando o jogador foi visto lá).", fontSize = 11.5.sp, color = MUTE, modifier = Modifier.padding(top = 6.dp))
        }
    }
    // ---- treino
    EvoCard("Quanto o treino rendeu", "🏋") {
        val t = p.train
        if (t == null || t.trained == 0) {
            Text(
                "Leia o Plantel de vez em quando (ao menos 1x por semana) com treinos rodando: eu comparo a força de quem treinou com a de quem não treinou.",
                fontSize = 13.sp, color = MUTE
            )
            return@EvoCard
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("força • treinaram", fmt1(t.trainedGain, true), "${t.trained} jogador(es)", MUTE, Modifier.weight(1f))
            Metric("força • sem treino", fmt1(t.othersGain, true), "${t.others} jogador(es)", MUTE, Modifier.weight(1f))
            Metric("valor do elenco", moneyText(t.valueGain, true), "${t.days} dias", MUTE, Modifier.weight(1f))
        }
        val verdict = when {
            t.edge >= 1.0 -> "✔ O treino está rendendo: +${fmt1(t.edge)} de força por jogador treinado em ${t.days} dias. Vale manter os 5 treinadores ocupados."
            t.edge > 0 -> "Rende pouco por enquanto (+${fmt1(t.edge)}). Treine quem é titular e jovem: é onde a força vira vitória e valor."
            else -> "Ainda sem ganho medido. Leia o Plantel antes e depois de alguns treinos para eu medir."
        }
        Text(verdict, fontSize = 13.sp, color = if (t.edge >= 1.0) GOOD else Color.White, modifier = Modifier.padding(top = 8.dp))
        if (t.top.isNotEmpty()) {
            Text("Quem mais subiu: " + t.top.joinToString(" • ") { "${it.first} +${it.second}" }, fontSize = 12.5.sp, color = MUTE, modifier = Modifier.padding(top = 4.dp))
        }
    }
    // ---- alerta de valor caindo
    if (p.falling.isNotEmpty()) {
        EvoCard("Vender antes que caia mais", "📉") {
            for ((name, v0, v1) in p.falling.take(4)) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${moneyText(v0)} → ${moneyText(v1)}", fontSize = 13.sp, color = BADC, textAlign = TextAlign.End)
                }
            }
            Text("Valor caiu 10% ou mais em 3 semanas: se não é titular, venda agora.", fontSize = 12.5.sp, color = MUTE, modifier = Modifier.padding(top = 6.dp))
        }
    }
    ScoreboardCard(p.score)
}

private fun fmt1(v: Double, sign: Boolean = false): String =
    (if (sign && v > 0) "+" else "") + "%.1f".format(v).replace('.', ',')
