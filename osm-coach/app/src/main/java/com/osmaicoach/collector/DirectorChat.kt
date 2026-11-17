package com.osmaicoach.collector

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Pergunte ao Diretor": perguntas prontas (sugeridas pelo que existe no slot) respondidas na hora com os dados que o app já leu,
 * sem IA e sem custo. Pergunta livre usa a IA configurada, com os mesmos dados e a regra de não inventar.
 */
object DirectorChatText {
    fun why(v: Why.View): String = buildString {
        append("Tática: ${v.head}\n")
        for (f in v.factors.take(5)) append("• $f\n")
        v.alt?.let { append("🔁 $it\n") }
        append(v.evidence)
    }

    fun risk(r: TacticAudit.Result): String = buildString {
        append("Coerência ${r.score}/100 (${r.label}). Isto não é chance de vitória.\n")
        if (r.problems.isEmpty()) append("Nenhum problema encontrado.\n") else for (p in r.problems) append("⚠ $p\n")
        for (t in r.tips) append("💡 $t\n")
    }.trim()

    fun planB(a: AltPlan?): String = a?.let {
        "Plano B: ${it.formation} • ${it.style} (pressão ${it.pressure}, estilo ${it.mentality}, ritmo ${it.tempo}). Use se o rival mudar a tática ou a leitura dele estiver incerta; não substitui a principal."
    } ?: "Não há plano B guardado para esta tática."

    fun learned(v: LearnPanel.View): String = buildString {
        append("Jogos contra humanos lidos: ${v.humanGames}.\n")
        if (v.best.isNotEmpty()) append("Melhor: " + v.best.first() + "\n")
        if (v.worst.isNotEmpty()) append("Pior: " + v.worst.first() + "\n")
        if (v.avoid.isNotEmpty()) append("Evitar: " + v.avoid.joinToString(", ") + "\n")
        append(v.note)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DirectorChatView(
    chips: List<String>, msgs: List<Pair<Boolean, String>>, busy: Boolean, text: String,
    onText: (String) -> Unit, onChip: (String) -> Unit, onSend: () -> Unit
) {
    Column {
        Text("Toque numa pergunta (responde na hora, sem IA) ou escreva a sua.", fontSize = 12.5.sp, color = C.MUTED)
        FlowRow(Modifier.padding(top = 8.dp)) {
            for (c in chips) {
                Box(
                    Modifier.padding(end = 6.dp, bottom = 6.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF0A1830))
                        .border(1.dp, Color(0x552AB4FF), RoundedCornerShape(16.dp)).clickable(enabled = !busy) { onChip(c) }.padding(horizontal = 12.dp, vertical = 7.dp)
                ) { Text(c, fontSize = 13.sp, color = C.PRIMARY, fontWeight = FontWeight.SemiBold) }
            }
        }
        for ((mine, t) in msgs.takeLast(8)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                Box(
                    Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(14.dp)).background(if (mine) Color(0xFF1B4FA8) else Color(0xFF16325F)).padding(horizontal = 12.dp, vertical = 8.dp)
                ) { Text(t, fontSize = 13.5.sp) }
            }
        }
        if (busy) Text("Pensando…", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = text, onValueChange = onText, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("Pergunte ao Diretor…", fontSize = 13.sp) })
            TextButton(onClick = onSend, enabled = !busy && text.isNotBlank()) { Text("Enviar") }
        }
    }
}

@Composable
internal fun DirectorChat(slot: Int, d: SlotData) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msgs by remember(slot) { mutableStateOf(listOf<Pair<Boolean, String>>()) }
    var busy by remember(slot) { mutableStateOf(false) }
    var text by remember(slot) { mutableStateOf("") }
    val hasTactic = d.tactic != null
    val chips = buildList {
        if (hasTactic) { add("Por que esta tática?"); add("Qual o risco deste jogo?"); add("Tem plano B?") }
        add("Quem é o rival?"); add("O que o app aprendeu?")
    }
    fun run(label: String, block: suspend () -> String) {
        msgs = msgs + (true to label); busy = true
        scope.launch {
            val a = withContext(Dispatchers.IO) { runCatching { block() }.getOrElse { "Não consegui responder agora." } }
            msgs = msgs + (false to a); busy = false
        }
    }
    fun answer(q: String): suspend () -> String = {
        val repo = Repo(ctx)
        when (q) {
            "Por que esta tática?" -> Why.build(repo, slot)?.let { DirectorChatText.why(it) } ?: "Ainda não há tática válida para este jogo."
            "Qual o risco deste jogo?" -> d.tactic?.let { tp -> TacticAudit.load(repo, slot, tp)?.let { DirectorChatText.risk(it) } } ?: "Sem tática válida para avaliar."
            "Tem plano B?" -> DirectorChatText.planB(d.tactic?.let { loadAltPlan(repo, slot, it) })
            "Quem é o rival?" -> {
                fun v(k: String) = known(d.fields, k)
                listOfNotNull(
                    v(K.RIVAL_TEAM)?.let { "Rival: $it" + (if (v(K.RIVAL_HUMAN) == "Sim") " (humano)" else " (CPU)") },
                    (v(K.MY_STRENGTH) to v(K.RIVAL_STRENGTH)).let { (a, b) -> if (a != null && b != null) "Força $a × $b" else null },
                    v(K.RIVAL_FORMATION)?.let { "Formação $it" + (v(K.RIVAL_PLAN)?.let { p -> " • $p" } ?: "") },
                    v(K.REFEREE)?.let { "Árbitro $it" }
                ).joinToString("\n").ifBlank { "Ainda não li o rival: abra o Pré-jogo e a Análise no OSM." }
            }
            "O que o app aprendeu?" -> DirectorChatText.learned(LearnPanel.build(repo))
            else -> "Não entendi."
        }
    }
    fun free(q: String) = run(q) {
        val repo = Repo(ctx)
        val ctxJson = Director.context(repo, slot).toString().take(6000)
        val prompt = "Você é o Diretor técnico do app OSM Coach. Responda em português do Brasil, curto (máx. 6 linhas), usando SÓ os dados abaixo. " +
            "Se o dado não está lá, diga que não foi lido. Não invente números, nomes nem eventos. Não sugira gastar Boss Coins.\nDADOS: $ctxJson\nPERGUNTA: $q"
        val r = AiClient.ask(ctx, prompt, null, 60000L)
        if (r.ok && !r.text.isNullOrBlank()) r.text.trim() else "Para perguntas livres configure uma chave de IA em Ajustes. As perguntas prontas funcionam sem IA."
    }
    Fold("💬", "Pergunte ao Diretor", null, startOpen = false) {
        DirectorChatView(chips, msgs, busy, text, { text = it }, { q -> run(q, answer(q)) }, { val q = text.trim(); text = ""; if (q.isNotEmpty()) free(q) })
    }
}
