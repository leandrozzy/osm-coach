package com.osmaicoach.collector

import androidx.compose.foundation.layout.*
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

/** "Histórico pós-jogo": um cartão por jogo com resultado lido, previsão do app, tática usada e se deu certo. Vale para cada slot. */
@Composable
internal fun PostGameHistory(slot: Int, d: SlotData) {
    val rows = postRows(d.logsAll.mapNotNull { runCatching { JSONObject(it.json) }.getOrNull() })
    val done = rows.filter { it.result != null }
    val graded = done.filter { it.hit != null }
    val hits = graded.count { it.hit == true }
    val badge = if (graded.isEmpty()) "${done.size} jogo(s)" else "previsão certa em $hits de ${graded.size}"
    Fold("📒", "Histórico pós-jogo (S$slot)", badge, startOpen = true) {
        if (rows.isEmpty()) {
            Text("Ainda sem jogos. Gere a tática antes do jogo e leia o Resultado depois: cada jogo aparece aqui com a previsão, a tática usada e o veredito.", fontSize = 13.sp, color = C.MUTED)
            return@Fold
        }
        if (graded.isNotEmpty()) {
            val above = done.count { (it.vsExpected ?: 0.0) >= 0.6 }; val below = done.count { (it.vsExpected ?: 0.0) <= -0.9 }
            Text("🔮 Previsão certa em $hits de ${graded.size} jogo(s). Tática acima do previsto: $above • abaixo: $below.", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        for (r in rows.take(15)) {
            val col = when (r.result) { "V" -> C.WIN; "D" -> C.LOSS; "E" -> C.DRAW; else -> C.MUTED }
            Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(
                    "J${r.round} • ${r.rival}" + (if (r.home == true) " (casa)" else if (r.home == false) " (fora)" else "") + (if (r.human == true) " 👤" else if (r.human == false) " 🤖" else ""),
                    fontSize = 14.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    (r.result?.let { "Resultado: $it ${r.score}".trim() } ?: "Resultado: ainda não lido"),
                    fontSize = 13.sp, color = col, fontWeight = FontWeight.SemiBold
                )
                if (r.predW != null) {
                    val f = r.fav
                    Text(
                        "Previsão do app: V ${r.predW}% • E ${r.predE}% • D ${r.predD}%" + (if (r.hit == true) " → ✓ acertou ($f)" else if (r.hit == false) " → ✗ errou (esperava $f)" else ""),
                        fontSize = 12.5.sp, color = C.MUTED
                    )
                } else Text("Previsão: não guardada neste jogo", fontSize = 12.5.sp, color = C.MUTED)
                Text(
                    "Tática: ${r.formation} • ${r.style}" + (if (r.deviated) " (você jogou diferente do app" + (r.genFormation?.let { ": o app indicou $it" } ?: "") + ")" else ""),
                    fontSize = 12.5.sp, color = C.MUTED
                )
                Text(r.verdict, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Text("“Acima/abaixo do previsto” compara os pontos reais com os pontos esperados pela chance de V/E/D guardada na hora de gerar a tática. Poucos jogos = sinal fraco (sorte conta).", fontSize = 11.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 10.dp))
    }
}
