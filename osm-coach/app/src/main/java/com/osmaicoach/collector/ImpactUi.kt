package com.osmaicoach.collector

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** "O que as decisões renderam": seguiu o app × mexeu à mão, vendas, lista de transferências e treino (aba Aprendizado do slot). */
@Composable
internal fun ImpactCard(slot: Int, d: SlotData) {
    val logs = d.logsAll.mapNotNull { p -> runCatching { JSONObject(p.json) }.getOrNull() }
    val fol = Impact.followed(logs)
    val ledger = d.progress?.ledger
    val listed = Impact.listed(d.players)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val sell = rememberLoaded<Impact.SellReport?>(null, 0, UiBus.version) {
        val repo = Repo(ctx)
        Impact.sells(Ledger.parse(repo.dao.plan(slot, "ledger")?.json), repo.dao.plan(slot, "phist")?.json?.let { JSONObject(it) } ?: JSONObject())
    }
    Fold("📈", "O que as decisões renderam", startOpen = false) {
        Text("Só do seu histórico neste slot. Com poucos jogos ou vendas o app diz que é pouco, sem concluir.", fontSize = 12.5.sp, color = C.MUTED)
        if (fol != null) {
            Title("Tática: seguiu o app × mexeu à mão")
            for (s in listOf(fol.first, fol.second)) if (s.n > 0) Text("• ${s.label}: ${s.text()}", fontSize = 13.5.sp, color = Color.White, modifier = Modifier.padding(top = 3.dp))
            Text(Impact.followedNote(fol), fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
        } else Text("Tática: ainda sem jogos para comparar.", fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 6.dp))
        Title("Compras e vendas")
        if (ledger == null || (ledger.buys == 0 && ledger.sells == 0)) {
            Text("Nenhuma compra ou venda confirmada ainda.", fontSize = 13.sp, color = C.MUTED)
        } else {
            Text("• ${ledger.buys} compras (${fmtM(ledger.spent)}) • ${ledger.sells} vendas (${fmtM(ledger.received)}) • saldo ${fmtM(ledger.balance)}", fontSize = 13.5.sp, color = Color.White)
            Text("O livro de caixa só conta a venda de quem estava À VENDA (setas lidas): sumir do plantel por erro de leitura não vira venda.", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 3.dp))
        }
        Text(
            if (listed.isEmpty()) "• Lista de transferências: ninguém à venda (leia o Plantel até o fim para atualizar)."
            else "• À venda agora (${listed.size}): " + listed.joinToString { it.name },
            fontSize = 13.5.sp, color = Color.White, modifier = Modifier.padding(top = 4.dp)
        )
        sell?.let { Text("• CPU pagou em média ${"%.2f".format(it.median).replace('.', ',')}× o valor (${it.n} vendas).", fontSize = 13.5.sp, color = Color.White, modifier = Modifier.padding(top = 4.dp)) }
        d.progress?.train?.let { t ->
            Title("Treino")
            Text("• Quem treinou ganhou ${"%.1f".format(t.trainedGain).replace('.', ',')} pts em média (${t.trained} jogadores) contra ${"%.1f".format(t.othersGain).replace('.', ',')} de quem não treinou (${t.others}) em ${t.days} dias.", fontSize = 13.5.sp, color = Color.White)
        }
    }
}
