package com.osmaicoach.collector

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Uma linha do histórico pós-jogo: previsão × resultado e a tática usada (tudo do que já foi lido/gravado). */
internal data class PostRow(
    val round: Int, val rival: String, val score: String, val result: String?, val predW: Int?, val predE: Int?, val predD: Int?,
    val formation: String, val style: String, val deviated: Boolean, val genFormation: String?, val human: Boolean?, val home: Boolean?
) {
    val fav: String? get() = if (predW == null || predE == null || predD == null) null else if (predW >= predE && predW >= predD) "V" else if (predD >= predE) "D" else "E"
    /** null = ainda sem resultado ou sem previsão guardada. */
    val hit: Boolean? get() = if (result == null || fav == null) null else fav == result
    /** Pontos reais contra os esperados pela previsão (V-3/E-1/D-0): acima = a tática rendeu mais que o previsto. */
    val vsExpected: Double? get() {
        val w = predW ?: return null; val e = predE ?: return null
        val r = result ?: return null
        val exp = (w * 3 + e) / 100.0
        val pts = when (r) { "V" -> 3.0; "E" -> 1.0; else -> 0.0 }
        return pts - exp
    }
    val verdict: String get() = when {
        result == null -> "⏳ aguardando o resultado"
        vsExpected == null -> if (result == "V") "✅ venceu" else if (result == "E") "➖ empatou" else "❌ perdeu"
        vsExpected!! >= 0.6 -> "✅ a tática rendeu ACIMA do previsto"
        vsExpected!! <= -0.9 -> "❌ a tática rendeu ABAIXO do previsto"
        else -> "➖ como previsto"
    }
}

internal fun postRows(logs: List<JSONObject>): List<PostRow> = logs.mapNotNull { j ->
    val round = j.optInt("round", -1)
    if (round <= 0) return@mapNotNull null
    fun i(k: String): Int? = if (j.has(k) && !j.isNull(k)) j.optInt(k) else null
    val mine = i("scoreMine"); val opp = i("scoreOpp")
    PostRow(
        round, j.optString("rival").ifBlank { "?" }, if (mine != null && opp != null) "$mine-$opp" else "",
        if (j.isNull("result")) null else j.optString("result").ifBlank { null },
        i("predW"), i("predE"), i("predD"),
        j.optString("formation"), j.optString("playStyle"), j.optBoolean("deviated", false), j.optString("genFormation").ifBlank { null },
        if (j.has("human") && !j.isNull("human")) j.optBoolean("human") else null,
        if (j.has("home") && !j.isNull("home")) j.optBoolean("home") else null
    )
}.sortedByDescending { it.round }

/** Aba "Histórico" do slot: resumo no topo + um cartão por jogo (resultado lido, previsão do app, tática usada, veredito). Vale para cada slot. */
@Composable
internal fun SlotHistory(slot: Int, d: SlotData) {
    val rows = postRows(d.logsAll.mapNotNull { runCatching { JSONObject(it.json) }.getOrNull() })
    val done = rows.filter { it.result != null }
    val graded = done.filter { it.hit != null }
    if (rows.isEmpty()) {
        EmptyState("📒", "Ainda sem jogos", "Gere a tática antes do jogo e leia o Resultado depois: cada jogo aparece aqui com a previsão do app, a tática usada e o veredito.")
        return
    }
    val v = done.count { it.result == "V" }; val e = done.count { it.result == "E" }; val l = done.count { it.result == "D" }
    val pts = v * 3 + e
    val pct = if (done.isEmpty()) 0 else pts * 100 / (done.size * 3)
    val hits = graded.count { it.hit == true }
    val hitPct = if (graded.isEmpty()) null else hits * 100 / graded.size
    val above = done.count { (it.vsExpected ?: 0.0) >= 0.6 }
    val below = done.count { (it.vsExpected ?: 0.0) <= -0.9 }
    val deviated = done.count { it.deviated }
    val pending = rows.count { it.result == null }

    // ---- resumo
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).osmPanel().padding(16.dp)) {
        Text("📒 Resumo do S$slot", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("🏆", "${v}V ${e}E ${l}D", "${done.size} jogo(s)", C.PRIMARY, Modifier.weight(1f))
            StatTile("⭐", "$pct%", "aproveitamento", if (pct >= 60) C.OK else if (pct >= 40) C.GOLD else C.BAD, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("🔮", hitPct?.let { "$it%" } ?: "—", if (graded.isEmpty()) "previsão (sem dados)" else "previsão certa ($hits/${graded.size})", if ((hitPct ?: 0) >= 50) C.OK else C.GOLD, Modifier.weight(1f))
            StatTile("🎯", "$above↑ $below↓", "tática acima/abaixo do previsto", C.PRIMARY, Modifier.weight(1f))
        }
        Text(
            buildString {
                if (pending > 0) append("⏳ $pending jogo(s) esperando o Resultado. ")
                if (deviated > 0) append("🛠 Em $deviated jogo(s) você jogou diferente do app. ")
                append("Veredito = pontos reais contra os esperados pela previsão; com poucos jogos é sinal fraco (sorte conta).")
            },
            fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 10.dp)
        )
    }

    // ---- jogo a jogo
    Title("Jogo a jogo")
    for (r in rows.take(30)) {
        val col = when (r.result) { "V" -> C.WIN; "D" -> C.LOSS; "E" -> C.DRAW; else -> C.MUTED }
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).osmPanel().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(50)).background(col), contentAlignment = Alignment.Center) {
                    Text(r.result ?: "?", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0B1C3E))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("J${r.round} • ${r.rival}", fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(
                        (if (r.home == true) "casa" else if (r.home == false) "fora" else "") + (if (r.human == true) " • humano" else if (r.human == false) " • CPU" else ""),
                        fontSize = 12.sp, color = C.MUTED
                    )
                }
                Text(if (r.result != null) (r.score.ifBlank { "—" }) else "⏳", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = col)
            }
            Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                HistLine("🔮 Previsão", if (r.predW != null) "V ${r.predW}% • E ${r.predE}% • D ${r.predD}%" + (if (r.hit == true) "  ✓ acertou" else if (r.hit == false) "  ✗ errou (esperava ${r.fav})" else "") else "não guardada neste jogo")
                HistLine("🧩 Tática", "${r.formation} • ${r.style}" + (if (r.deviated) "  🛠 você jogou diferente" + (r.genFormation?.let { " (app: $it)" } ?: "") else ""))
                HistLine("📝 Veredito", r.verdict)
            }
        }
    }
    if (rows.size > 30) Text("Mostrando os 30 jogos mais recentes.", fontSize = 12.sp, color = C.MUTED)
}

@Composable
private fun HistLine(label: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.width(96.dp))
        Text(text, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}
