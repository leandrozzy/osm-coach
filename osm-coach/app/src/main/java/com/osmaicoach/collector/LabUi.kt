package com.osmaicoach.collector

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** Modo de teste (desligado por padrão): nova recomendação ao lado da padrão, sem trocá-la, e comparação das previsões. */
@Composable
internal fun LabCard(slot: Int) {
    val ctx = LocalContext.current
    val on = Settings.get(ctx, "lab_on", "0") == "1"
    if (!on) return
    val rec = rememberLoaded<JSONObject?>(null, UiBus.version, "lab$slot") {
        runCatching { Repo(ctx).dao.plan(slot, "lab_rec")?.json?.let { JSONObject(it) } }.getOrNull()
    }
    val rep = rememberLoaded<LabEval.Report?>(null, UiBus.version, "labeval$slot") { runCatching { LabEval.load(Repo(ctx), slot) }.getOrNull() }
    Fold("🧪", "Modo de teste: motor novo (não substitui a recomendação)", null, startOpen = false) {
        Hint("Só registra e compara. A tática padrão continua sendo a recomendada. As previsões dos dois motores são congeladas antes do jogo.")
        if (rec == null) Text("Ainda não há recomendação de teste para este jogo (aparece ao gerar a tática dentro das 8 h antes).", color = C.MUTED)
        else {
            Text("Nova: ${rec.optString("formation")} • ${rec.optString("playStyle")} • pressão ${rec.optInt("pressure")} • mentalidade ${rec.optInt("mentality")} • ritmo ${rec.optInt("tempo")}")
            Text("Desarme ${rec.optString("tackle")} • marcação ${rec.optString("marking")} • fora-de-jogo ${rec.optString("offside")}", color = C.MUTED)
            Text("Previsão: V ${rec.optInt("win")}% • E ${rec.optInt("draw")}% • D ${rec.optInt("loss")}%  (${rec.optInt("scenarios")} cenário(s) do rival)")
            rec.optJSONArray("notes")?.let { a -> for (i in 0 until a.length()) Text("• " + a.optString(i), color = C.MUTED, modifier = Modifier.padding(top = 2.dp)) }
            rec.optJSONObject("planB")?.let { b -> Text("Plano B: ${b.optString("formation")} • ${b.optString("playStyle")} — trocar ${b.optString("trigger")}", modifier = Modifier.padding(top = 4.dp)) }
        }
        rep?.let { r ->
            Column(Modifier.padding(top = 8.dp)) {
                Text("Comparação das previsões (mesma tática aplicada e confirmada, previsões registradas antes do jogo): ${r.n} jogo(s)")
                if (r.n == 0) Text("Sem amostra ainda. ${r.excluded.entries.joinToString("; ") { "${it.value}× ${it.key}" }}", color = C.MUTED)
                else {
                    r.std?.let { Text("Padrão: erro (Brier) ${"%.3f".format(it.brier)} • acerto ${(it.hit * 100).toInt()}%", color = C.MUTED) }
                    r.lab?.let { Text("Novo: erro (Brier) ${"%.3f".format(it.brier)} • acerto ${(it.hit * 100).toInt()}%", color = C.MUTED) }
                    Text("Casa/fora: ${r.byHome}  •  humano/CPU: ${r.byHuman}", color = C.MUTED)
                    Text("Faixa de força: ${r.byBucket}", color = C.MUTED)
                    if (r.n < 10) Text("⚠ Amostra pequena (<10): sem conclusão.", color = C.MUTED)
                }
                Text("Isto mede qualidade da PREVISÃO, não qual tática ganharia mais; o placar de uma tática não diz se a outra venceria.", color = C.MUTED, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
