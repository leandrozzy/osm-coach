package com.osmaicoach.collector

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Comparar dois jogadores do SEU elenco e estimar o preço de venda (usa as regras que o app já tem: teto 2–2,5× e as vendas reais do slot). */
object PlayerTools {
    data class Bar(val label: String, val a: Double?, val b: Double?, val higherBetter: Boolean, val fmt: (Double) -> String)

    fun bars(a: PlayerEntity, b: PlayerEntity): List<Bar> = listOf(
        Bar("Força", a.strength?.toDouble(), b.strength?.toDouble(), true) { it.toInt().toString() },
        Bar("Idade", a.age?.toDouble(), b.age?.toDouble(), false) { it.toInt().toString() },
        Bar("Valor", Money.parse(a.valueText), Money.parse(b.valueText), true) { fmtM(it) }
    )

    /** Preço de venda: faixa do teto do OSM (2× a 2,5× do valor) e, com 3+ vendas reais, o que a CPU costuma pagar. */
    data class Price(val value: Double, val lo: Double, val hi: Double, val learned: Pair<Double, Int>?)

    fun price(valueText: String?, learned: Pair<Double, Int>?): Price? {
        val v = Money.parse(valueText) ?: return null
        return Price(v, v * 2.0, v * 2.5, learned)
    }
}

@Composable
internal fun CompareBars(a: PlayerEntity, b: PlayerEntity) {
    Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text(a.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = C.PRIMARY, modifier = Modifier.weight(1f), maxLines = 1)
            Text(b.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFFFF8A8A), maxLines = 1)
        }
        for (bar in PlayerTools.bars(a, b)) {
            val av = bar.a; val bv = bar.b
            val aWin = av != null && bv != null && av != bv && ((av > bv) == bar.higherBetter)
            val bWin = av != null && bv != null && av != bv && ((bv > av) == bar.higherBetter)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(av?.let(bar.fmt) ?: "?", fontSize = 14.sp, fontWeight = if (aWin) FontWeight.ExtraBold else FontWeight.Normal, color = if (aWin) C.OK else Color.White, modifier = Modifier.width(52.dp))
                Column(Modifier.weight(1f)) {
                    Text(bar.label + if (!bar.higherBetter) " (menor = mais jovem)" else "", fontSize = 11.5.sp, color = C.MUTED, modifier = Modifier.align(Alignment.CenterHorizontally))
                    val mx = maxOf(av ?: 0.0, bv ?: 0.0).coerceAtLeast(0.0001)
                    Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                        Box(Modifier.weight(1f).height(8.dp), contentAlignment = Alignment.CenterEnd) {
                            Box(Modifier.fillMaxWidth(((av ?: 0.0) / mx).toFloat().coerceIn(0.02f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(if (aWin) C.OK else C.PRIMARY))
                        }
                        Spacer(Modifier.width(4.dp))
                        Box(Modifier.weight(1f).height(8.dp), contentAlignment = Alignment.CenterStart) {
                            Box(Modifier.fillMaxWidth(((bv ?: 0.0) / mx).toFloat().coerceIn(0.02f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(if (bWin) C.OK else Color(0xFFE5484D)))
                        }
                    }
                }
                Text(bv?.let(bar.fmt) ?: "?", fontSize = 14.sp, fontWeight = if (bWin) FontWeight.ExtraBold else FontWeight.Normal, color = if (bWin) C.OK else Color.White, modifier = Modifier.width(52.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayerCompareDialog(slot: Int, p: PlayerEntity, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val mates = rememberLoaded<List<PlayerEntity>>(emptyList(), 0, slot) {
        runCatching { Repo(ctx).dao.playersOf(slot).filter { it.owner == "MY" && it.nameKey != p.nameKey }.sortedWith(compareByDescending<PlayerEntity> { it.cat == p.cat }.thenByDescending { it.strength ?: 0 }) }.getOrDefault(emptyList())
    }
    var other by remember { mutableStateOf<PlayerEntity?>(null) }
    AlertDialog(
        onDismissRequest = onDone, containerColor = C.SURFACE,
        title = { Text("⚖ Comparar jogadores", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                other?.let { CompareBars(p, it) } ?: Text("Escolha com quem comparar ${p.name}:", fontSize = 13.sp, color = C.MUTED)
                FlowRow(Modifier.padding(top = 10.dp)) {
                    for (m in mates.take(24)) {
                        Box(Modifier.padding(end = 6.dp, bottom = 6.dp).clip(RoundedCornerShape(14.dp)).background(if (other?.nameKey == m.nameKey) C.PRIMARY.copy(alpha = 0.35f) else Color(0xFF0A1830))
                            .border(1.dp, Color(0x332AB4FF), RoundedCornerShape(14.dp)).clickable { other = m }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text("${m.name} ${m.strength ?: ""}", fontSize = 12.5.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Fechar") } }
    )
}

@Composable
internal fun PriceView(p: PlayerEntity, pr: PlayerTools.Price?) {
    Column {
        if (pr == null) { Text("Valor de ${p.name} não lido: leia o Plantel de novo.", fontSize = 13.sp, color = C.MUTED); return }
        Text("Valor ${fmtM(pr.value)}", fontSize = 13.sp, color = C.MUTED)
        Text("${fmtM(pr.lo)} – ${fmtM(pr.hi)}", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = C.PRIMARY, modifier = Modifier.padding(top = 4.dp))
        Text("Teto do OSM: 2×, 2,25× ou 2,5× do valor (o jogo mostra o seu teto na tela de venda).", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 2.dp))
        pr.learned?.let { (r, n) ->
            Text("Nas suas $n vendas reais deste slot a CPU pagou ~${"%.2f".format(r).replace('.', ',')}× o valor: ${fmtM(pr.value * r)}.", fontSize = 13.sp, color = C.OK, modifier = Modifier.padding(top = 8.dp))
        } ?: Text("Com 3 vendas reais lidas o app mostra aqui quanto a CPU costuma pagar.", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
        Text("Venda só o que sobra do elenco (mínimos por posição e formações montáveis).", fontSize = 12.5.sp, color = C.GOLD, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
internal fun PriceDialog(slot: Int, p: PlayerEntity, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val learned = rememberLoaded<Pair<Double, Int>?>(null, 0, slot) {
        runCatching {
            val repo = Repo(ctx)
            SellPrices.learnedRatio(Ledger.parse(repo.dao.plan(slot, "ledger")?.json), repo.dao.plan(slot, "phist")?.json?.let { JSONObject(it) } ?: JSONObject())
        }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDone, containerColor = C.SURFACE,
        title = { Text("💰 Preço de venda: ${p.name}", fontWeight = FontWeight.Bold) },
        text = { PriceView(p, PlayerTools.price(p.valueText, learned)) },
        confirmButton = { TextButton(onClick = onDone) { Text("Fechar") } }
    )
}
