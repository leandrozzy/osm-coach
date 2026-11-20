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
    val resultWord: String get() = when (result) { "V" -> "vitória"; "E" -> "empate"; "D" -> "derrota"; else -> "" }
    private fun word(r: String?) = when (r) { "V" -> "vitória"; "E" -> "empate"; else -> "derrota" }
    private fun pOf(r: String?): Int? = when (r) { "V" -> predW; "E" -> predE; "D" -> predD; else -> null }
    /** Frase simples: o app acertou o resultado mais provável? */
    val hitText: String get() {
        val f = fav; val r = result
        if (f == null) return "O app não guardou a previsão deste jogo."
        if (r == null) return "O app previa ${word(f)} como mais provável (${pOf(f)}%). Falta ler o Resultado."
        return if (f == r) "✅ ACERTOU: previu ${word(f)} (${pOf(f)}%) e foi ${word(r)}."
        else "❌ ERROU: previu ${word(f)} (${pOf(f)}%), mas foi ${word(r)} (o app dava só ${pOf(r)}% para isso)."
    }
    /** Frase simples: a tática funcionou? Compara com o que era esperado. */
    val verdict: String get() {
        val r = result ?: return "⏳ Aguardando você ler o Resultado do jogo."
        val v = vsExpected ?: return if (r == "V") "👍 Ganhou." else if (r == "E") "➖ Empatou." else "👎 Perdeu."
        return when {
            v >= 0.6 -> "👍 Foi MELHOR que o esperado: ${word(r)} num jogo em que o app dava só ${pOf(r)}% para isso."
            v <= -0.9 -> "👎 Foi PIOR que o esperado: o app dava ${predW}% de vitória e deu ${word(r)}. Vale rever essa tática."
            r == "D" -> "➖ Perdeu, mas já era esperado (o app dava ${predD}% de derrota): não dá para culpar a tática."
            else -> "➖ Dentro do esperado."
        }
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

/** Aba "Histórico" do slot: resumo em frases simples no topo + um cartão por jogo. Vale para cada slot. */
@Composable
internal fun SlotHistory(slot: Int, d: SlotData) {
    val rows = postRows(d.logsAll.mapNotNull { runCatching { JSONObject(it.json) }.getOrNull() })
    val done = rows.filter { it.result != null }
    val graded = done.filter { it.hit != null }
    if (rows.isEmpty()) {
        EmptyState("📒", "Ainda sem jogos", "Gere a tática antes do jogo e leia o Resultado depois: cada jogo aparece aqui com o que o app previa, o que aconteceu e se a tática funcionou.")
        return
    }
    val v = done.count { it.result == "V" }; val e = done.count { it.result == "E" }; val l = done.count { it.result == "D" }
    val pts = v * 3 + e
    val pct = if (done.isEmpty()) 0 else pts * 100 / (done.size * 3)
    val hits = graded.count { it.hit == true }
    val better = done.count { (it.vsExpected ?: 0.0) >= 0.6 }
    val worse = done.count { (it.vsExpected ?: 0.0) <= -0.9 }
    val same = graded.size - better - worse
    val deviated = done.count { it.deviated }
    val pending = rows.count { it.result == null }

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).osmPanel().padding(16.dp)) {
        Text("📒 Resumo do S$slot", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        Text("O que aconteceu", fontSize = 13.sp, color = C.GOLD, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        Text("Em ${done.size} jogo(s): $v vitória(s), $e empate(s), $l derrota(s). Você fez $pts de ${done.size * 3} pontos possíveis ($pct%).", fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
        Text("O app acertou?", fontSize = 13.sp, color = C.GOLD, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        Text(
            if (graded.isEmpty()) "Ainda não dá para dizer: nenhum jogo tem previsão guardada."
            else "Acertou o resultado em $hits de ${graded.size} jogo(s) (${hits * 100 / graded.size}%). Chute puro acertaria ~33%.",
            fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp)
        )
        if (graded.isNotEmpty()) {
            Text("A tática funcionou?", fontSize = 13.sp, color = C.GOLD, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            Text("Melhor que o esperado em $better jogo(s), pior em $worse, dentro do esperado em $same. Poucos jogos = sinal fraco (sorte conta).", fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (pending > 0) Text("⏳ $pending jogo(s) esperando você ler o Resultado.", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
        if (deviated > 0) Text("🛠 Em $deviated jogo(s) você jogou diferente do que o app indicou.", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
    }

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
                    Text("Rodada ${r.round} • ${r.rival}", fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(
                        (if (r.result != null) r.resultWord.replaceFirstChar { it.uppercase() } + (if (r.score.isNotBlank()) " ${r.score}" else "") else "Ainda não jogado/lido") +
                            (if (r.home == true) " • em casa" else if (r.home == false) " • fora" else "") + (if (r.human == true) " • humano" else if (r.human == false) " • CPU" else ""),
                        fontSize = 12.5.sp, color = col, fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (r.predW != null) Text("Previsão do app: vitória ${r.predW}% • empate ${r.predE}% • derrota ${r.predD}%", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
            Text(r.hitText, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            Text(r.verdict, fontSize = 13.5.sp, modifier = Modifier.padding(top = 4.dp))
            Text(
                "Tática usada: ${r.formation} • ${r.style}" + (if (r.deviated) " (você trocou" + (r.genFormation?.let { "; o app indicou $it" } ?: "") + ")" else ""),
                fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
    if (rows.size > 30) Text("Mostrando os 30 jogos mais recentes.", fontSize = 12.sp, color = C.MUTED)
}
