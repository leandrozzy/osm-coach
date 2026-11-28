package com.osmaicoach.collector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pré-jogo contra humanos (modo de teste, desligado por padrão): recomendação experimental ao lado da padrão, sem trocá-la. */
@Composable
internal fun LabCard(slot: Int) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(Settings.get(ctx, "lab_on", "0") == "1") }
    if (!on) {
        Fold("🧪", "Modo de teste: motor novo (desligado)", null, startOpen = false) {
            Hint("A tática padrão continua sendo a recomendada. Ligue para ver, ao lado dela, uma segunda recomendação para jogos contra técnico humano — ela nunca troca a padrão.")
            SwitchRow("Ligar o modo de teste", false) { on = it; Settings.put(ctx, "lab_on", if (it) "1" else "0") }
        }
        return
    }
    val rec = rememberLoaded<JSONObject?>(null, UiBus.version, "lab$slot") {
        runCatching { Repo(ctx).dao.plan(slot, "lab_rec")?.json?.let { JSONObject(it) } }.getOrNull()
    }
    val ctxInfo = rememberLoaded<Pair<HumanLab.Human, HumanLab.ReadState>?>(null, UiBus.version, "labctx$slot") {
        runCatching { val f = Repo(ctx).fieldMap(slot); Pair(HumanLab.human(f), HumanLab.readState(f, System.currentTimeMillis())) }.getOrNull()
    }
    val stats = rememberLoaded<HumanLab.Stats?>(null, UiBus.version, "labstats") { runCatching { HumanLab.load(Repo(ctx)) }.getOrNull() }
    val cmp = rememberLoaded<HumanLab.Compare?>(null, UiBus.version, "labcmp") { runCatching { HumanLab.compareLoad(Repo(ctx)) }.getOrNull() }
    val rep = rememberLoaded<LabEval.Report?>(null, UiBus.version, "labeval$slot") { runCatching { LabEval.load(Repo(ctx), slot) }.getOrNull() }
    val tips = rememberLoaded<Pair<List<Tips.Tip>, List<Tips.SourceStatus>>?>(null, UiBus.version, "labtips") { runCatching { Tips.load(Repo(ctx)) }.getOrNull() }
    // atualização das dicas em segundo plano (cache de 12 h, falha tolerada): nunca trava o Pré-jogo
    LaunchedEffect(slot) { AppScope.scope.launch { runCatching { if (Tips.refresh(Repo(ctx))) UiBus.bump() } } }
    val now = System.currentTimeMillis()
    Fold("🧪", "Modo de teste: motor novo (não substitui a recomendação)", null, startOpen = false) {
        SwitchRow("Modo de teste ligado", true) { on = it; Settings.put(ctx, "lab_on", if (it) "1" else "0") }
        Hint("Só aconselha: nada muda no OSM sozinho. A tática padrão continua disponível e é a recomendada.")
        ctxInfo?.let { (h, rs) ->
            Text((if (h.reliable) "👤 " else "❔ ") + h.label + " — " + h.why, fontWeight = FontWeight.SemiBold, color = if (h.reliable) C.OK else C.MUTED)
            Text("Leitura do rival: ${rs.label} • ${rs.detail}", color = if (rs.label == "atual") C.MUTED else C.GOLD, fontSize = 12.5.sp)
            if (rs.needsReread.isNotEmpty()) Text("Releia no OSM: ${rs.needsReread.joinToString(", ")} do rival (Pré-jogo/Análise). Enquanto isso use a tática padrão.", color = C.GOLD, fontSize = 12.5.sp)
            if (!h.reliable) Text("Rival sem prova de ser humano: esta recomendação não conta como amostra contra humanos.", color = C.MUTED, fontSize = 12.sp)
        }
        if (rec == null) Text("Ainda não há recomendação de teste para este jogo (aparece ao gerar a tática dentro das 8 h antes).", color = C.MUTED)
        else {
            val t = tacticOf(rec)
            Column(Modifier.padding(top = 6.dp)) {
                for (r in HumanLab.table(t)) Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(r.label, color = C.MUTED, fontSize = 13.sp); Text(r.value, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
            if (!rec.optBoolean("lineupOk", true) || !rec.optBoolean("existsInOsm", true)) Text("⚠ Escalação/opção não validada para esta formação: use a tática padrão.", color = C.BAD, fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp))
            Text("Previsão: V ${rec.optInt("win")}% • E ${rec.optInt("draw")}% • D ${rec.optInt("loss")}% (modelo, não promessa)", color = C.MUTED, modifier = Modifier.padding(top = 4.dp), fontSize = 12.5.sp)
            rec.optJSONObject("planB")?.let { b -> Text("Plano B: ${b.optString("formation")} • ${b.optString("playStyle")} — ${b.optString("trigger")}", modifier = Modifier.padding(top = 4.dp), fontSize = 12.5.sp) }
            Fold("❓", "Por que esta tática?", null, startOpen = false) { Why(rec, ctxInfo, tips, t, now) }
        }
        Column(Modifier.padding(top = 8.dp)) {
            Text("Contra técnicos humanos (tática aplicada confirmada)", fontWeight = FontWeight.SemiBold)
            stats?.let { s ->
                for (g in s.groups) {
                    Text("${g.name}: ${g.n} jogo(s) • ${g.w}V ${g.e}E ${g.d}D • ${"%.2f".format(g.ppg)} pts/jogo", fontSize = 12.5.sp)
                    for (c in g.cells.take(4)) Text("   ${c.key}: ${c.n}j • ${c.w}V ${c.e}E ${c.d}D • ${"%.2f".format(c.ppg)}", fontSize = 11.5.sp, color = C.MUTED)
                }
                Text(HumanLab.verdictText(s), color = C.MUTED, fontSize = 12.sp)
                if (s.excluded.isNotEmpty()) Text("Fora da conta: " + s.excluded.entries.joinToString("; ") { "${it.value}× ${it.key}" }, color = C.MUTED, fontSize = 11.5.sp)
            }
            cmp?.let { c ->
                Text("Padrão: ${c.std.n} jogo(s), ${"%.2f".format(c.std.ppg)} pts • Experimental: ${c.lab.n} jogo(s), ${"%.2f".format(c.lab.ppg)} pts", fontSize = 12.5.sp)
                Text(c.text, color = C.MUTED, fontSize = 12.sp)
            }
            Text("Funcionar ≠ provar que ganho mais: precisa de jogos comparáveis com a tática aplicada confirmada.", color = C.MUTED, fontSize = 11.5.sp)
        }
        rep?.let { r ->
            Column(Modifier.padding(top = 6.dp)) {
                Text("Comparação das previsões (mesma tática aplicada e confirmada, previsões registradas antes do jogo): ${r.n} jogo(s)", fontSize = 12.5.sp)
                if (r.n == 0) Text("Sem amostra ainda. ${r.excluded.entries.joinToString("; ") { "${it.value}× ${it.key}" }}", color = C.MUTED, fontSize = 12.sp)
                else {
                    r.std?.let { Text("Padrão: erro (Brier) ${"%.3f".format(it.brier)} • acerto ${(it.hit * 100).toInt()}%", color = C.MUTED, fontSize = 12.sp) }
                    r.lab?.let { Text("Novo: erro (Brier) ${"%.3f".format(it.brier)} • acerto ${(it.hit * 100).toInt()}%", color = C.MUTED, fontSize = 12.sp) }
                    if (r.n < 10) Text("⚠ Amostra pequena (<10): sem conclusão.", color = C.MUTED, fontSize = 12.sp)
                }
                Text("Isto mede qualidade da PREVISÃO, não qual tática ganharia mais.", color = C.MUTED, fontSize = 11.5.sp)
            }
        }
    }
}

private fun tacticOf(j: JSONObject): Tactic = HumanLab.tacticOf(j)

private val DAY = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR"))

@Composable
private fun Why(rec: JSONObject, ctxInfo: Pair<HumanLab.Human, HumanLab.ReadState>?, tips: Pair<List<Tips.Tip>, List<Tips.SourceStatus>>?, t: Tactic, now: Long) {
    val uri = LocalUriHandler.current
    ctxInfo?.let { (_, rs) -> Text("Contexto do rival: leitura ${rs.label}. ${rs.detail}", fontSize = 12.5.sp) }
    rec.optJSONArray("notes")?.let { a -> for (i in 0 until a.length()) Text("• " + a.optString(i), color = C.MUTED, fontSize = 12.sp) }
    Text("Apoio: leitura do jogo (modelo) ${rec.optInt("scenarios")} cenário(s); histórico próprio contra humanos abaixo; comunidade só como candidata.", fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
    val list = tips?.first.orEmpty()
    if (list.isEmpty()) {
        Text("Comunidade: nenhuma dica verificada guardada.", color = C.MUTED, fontSize = 12.sp)
    } else for (tp in list.take(4)) {
        val age = Tips.ageDays(tp, now)
        val pub = tp.publishedAt?.let { "publicada ${it.take(10)}" } ?: "data de publicação não informada"
        Text("• ${tp.source}${tp.author?.let { " — $it" } ?: ""} • $pub • verificada " + (age?.let { "há $it dia(s)" } ?: "nunca") + (if (Tips.stale(tp, now)) " ⚠ antiga" else ""), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        Text("   ${Tips.support(tp, t)} • ${tp.demonstrates}", color = C.MUTED, fontSize = 11.5.sp)
        tp.excerpt?.let { Text("   “$it”", color = C.MUTED, fontSize = 11.5.sp) }
        Text("   ${tp.url}", color = C.PRIMARY, fontSize = 11.sp, modifier = Modifier.clickable { runCatching { uri.openUri(tp.url) } })
    }
    tips?.second?.filter { it.error != null }?.let { bad ->
        if (bad.isNotEmpty()) Text("Fontes sem acesso agora: ${bad.size} (a última base verificada continua, com a idade acima).", color = C.MUTED, fontSize = 11.5.sp)
    }
}


/** Decisão MANTENHA / AJUSTE / FALTA CONFIRMAR: a MESMA regra da bolha (`TacticDecision`), no topo da aba Tática. */
@Composable
internal fun DecisionCard(slot: Int) {
    val ctx = LocalContext.current
    val v = rememberLoaded<TacticDecision.View?>(null, UiBus.version, "decision$slot") { runCatching { TacticDecision.load(Repo(ctx), slot) }.getOrNull() }
    if (v == null) return
    var full by remember(slot) { mutableStateOf(false) }
    val col = when (v.state) { TacticDecision.State.MANTENHA -> C.OK; TacticDecision.State.AJUSTE -> C.GOLD; TacticDecision.State.FALTA -> C.INFO; TacticDecision.State.SLOT_DESCONHECIDO -> C.BAD }
    Panel {
        Text(v.title, fontWeight = FontWeight.Bold)
        Text(v.state.label, color = col, fontWeight = FontWeight.ExtraBold)
        for (l in v.lines) Text(l, color = C.MUTED, fontSize = 12.5.sp)
        for (c in v.changes) Text("• ${c.label}: ${c.read} → ${c.rec}", fontSize = 13.sp)
        v.action?.let { Text("➜ $it", color = C.PRIMARY, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
        v.tableNote?.let { Text(it, color = C.GOLD, fontSize = 11.5.sp) }
        if (v.table != null) {
            Text(if (full) "Ocultar tática completa" else "Ver tática completa", color = C.INFO, modifier = Modifier.padding(top = 6.dp).clickable { full = !full })
            if (full) for (r in v.table) Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(r.label, color = C.MUTED, fontSize = 13.sp); Text(r.value, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
    }
}
