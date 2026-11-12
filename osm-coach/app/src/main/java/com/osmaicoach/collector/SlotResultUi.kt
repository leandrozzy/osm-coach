package com.osmaicoach.collector

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.conflate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Um jogo que já aconteceu (ou já começou): junta tática usada, card do calendário e análise lida do jogo. */
internal data class PlayedRow(
    val round: Int,
    val formation: String?,
    val style: String?,
    val rival: String,
    val result: String?,
    val home: Boolean?,
    val report: JSONObject?,
    val match: MatchEntity?
)

internal fun playedRows(d: SlotData): List<PlayedRow> {
    val logs = HashMap<Int, JSONObject>()
    for (p in d.logs) {
        val j = try { JSONObject(p.json) } catch (e: Exception) { continue }
        val r = j.optInt("round", -1)
        if (r > 0) logs[r] = j
    }
    val reports = HashMap<Int, JSONObject>()
    for (p in d.reports) {
        val r = p.kind.removePrefix("mr_R").toIntOrNull() ?: continue
        try { reports[r] = JSONObject(p.json) } catch (e: Exception) { }
    }
    val league = d.matches.filter { it.mkey.startsWith("L") && it.round != null }.associateBy { it.round!! }
    val pendingOk: (MatchEntity) -> Boolean = { it.result != null || it.scoreMine != null }
    val rounds = java.util.TreeSet<Int>()
    rounds.addAll(logs.keys)
    rounds.addAll(reports.keys)
    rounds.addAll(league.values.filter(pendingOk).mapNotNull { it.round })
    // Jogo cujo card (data e hora) já passou e ainda não tem placar: entra para registrar o resultado.
    Fixtures.awaitingResult(
        d.matches, reports.filter { it.value.has("sh") && it.value.has("sa") }.keys, System.currentTimeMillis(),
        known(d.fields, K.ROUND)?.toIntOrNull(), known(d.fields, K.MATCH_AT)?.toLongOrNull()
    )
        ?.round?.let { rounds.add(it) }
    // Copa da qual já fui eliminado: esses cards não são jogos meus.
    rounds.removeAll { r -> league[r]?.let { Fixtures.void(it, d.matches) } == true }
    // Só libera jogo que já aconteceu: com placar lido, com o horário do card já passado ou de data antiga.
    // Tática gerada para o próximo jogo NÃO é motivo para pedir resultado.
    val now = System.currentTimeMillis()
    val nextRound = known(d.fields, K.ROUND)?.toIntOrNull()
    rounds.removeAll { r ->
        val rep = reports[r]
        val m = league[r]
        val scored = (rep != null && rep.has("sh") && rep.has("sa")) || (m != null && (m.result != null || m.scoreMine != null))
        val happened = when {
            scored -> true
            m != null -> Fixtures.happened(m, now, nextRound, known(d.fields, K.MATCH_AT)?.toLongOrNull())
            nextRound != null && r == nextRound -> (known(d.fields, K.MATCH_AT)?.toLongOrNull() ?: Long.MAX_VALUE) <= now
            else -> nextRound != null && r < nextRound
        }
        !happened
    }
    return rounds.descendingSet().map { r ->
        val t = logs[r]
        val rep = reports[r]
        val m = league[r]
        val repHome = if (rep != null && rep.has("mineHome")) rep.optBoolean("mineHome") else null
        val home = m?.home ?: repHome ?: t?.let { if (it.isNull("home")) null else it.optBoolean("home") }
        val repRival = if (rep != null && repHome != null) (if (repHome) rep.optString("awayTeam") else rep.optString("homeTeam")).ifBlank { null } else null
        val rival = t?.optString("rival")?.takeIf { it.isNotBlank() && it != NI } ?: m?.let { Fixtures.opponent(it) } ?: repRival
            ?: m?.let { Fixtures.stage(it)?.let { st -> "Copa • $st" } } ?: "adversário"
        val tr = if (t == null || t.isNull("result")) null else t.optString("result")
        PlayedRow(
            r, t?.optString("formation")?.ifBlank { null }, t?.optString("playStyle")?.ifBlank { null },
            rival, m?.result ?: tr, home, rep, m
        )
    }
}

/** Valor do meu lado / do rival numa estatística do relatório ("posse de bola" -> ["55%", "45%"]). */
internal fun repStat(rep: JSONObject?, label: String, mineHome: Boolean?, mine: Boolean): String? {
    val a = rep?.optJSONObject("stats")?.optJSONArray(label) ?: return null
    if (mineHome == null) return null
    val home = mineHome == mine
    return (if (home) a.optString(0) else a.optString(1)).ifBlank { null }
}

internal fun digits(s: String?): String = s?.let { Regex("(\\d{1,3})").find(it)?.groupValues?.get(1) } ?: ""

@Composable
internal fun SlotResult(slot: Int, d: SlotData, goTactic: () -> Unit) {
    val ctx = LocalContext.current
    val rows = playedRows(d)
    if (rows.isEmpty()) {
        Panel {
            EmptyState("📊", "Nenhum jogo disputado ainda", "Depois da partida, abra no OSM o calendário ou a análise do jogo: o app lê o placar sozinho e ele aparece aqui. Também dá para registrar à mão.")
            OutlinedButton(onClick = goTactic, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("Ver a tática do próximo jogo") }
        }
        return
    }
    var sel by remember { mutableIntStateOf(rows.firstOrNull { it.result == null }?.round ?: rows.first().round) }
    val cur = rows.firstOrNull { it.round == sel } ?: rows.first()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        for (r in rows.take(16)) {
            FilterPill("J${r.round}" + (if (r.result == null) " • pendente" else " " + r.result), r.round == cur.round) { sel = r.round }
        }
    }
    val rep = cur.report
    val repHome = if (rep != null && rep.has("mineHome")) rep.optBoolean("mineHome") else null
    val repScore = rep != null && rep.has("sh") && rep.has("sa") && repHome != null
    val calMine = cur.match?.scoreMine
    val calOpp = cur.match?.scoreOpp
    val initMine = when {
        rep != null && repScore -> (if (repHome == true) rep.optInt("sh") else rep.optInt("sa")).toString()
        calMine != null && calOpp != null -> calMine.toString()
        else -> ""
    }
    val initOpp = when {
        rep != null && repScore -> (if (repHome == true) rep.optInt("sa") else rep.optInt("sh")).toString()
        calMine != null && calOpp != null -> calOpp.toString()
        else -> ""
    }
    val stamp = listOf(cur.round, initMine, initOpp, cur.home, rep?.toString()?.hashCode()).joinToString("|")
    // Recria o formulário quando chega leitura nova (análise ou calendário), em vez de ficar com o valor vazio.
    key(stamp) {
        val home0 = repHome ?: cur.home
        var a by remember { mutableStateOf(initMine) }
        var b by remember { mutableStateOf(initOpp) }
        var home by remember { mutableStateOf(home0) }
        var poss by remember { mutableStateOf(digits(repStat(rep, "posse de bola", home0, true))) }
        var mShots by remember { mutableStateOf(digits(repStat(rep, "remates", home0, true))) }
        var oShots by remember { mutableStateOf(digits(repStat(rep, "remates", home0, false))) }
        var mFouls by remember { mutableStateOf(digits(repStat(rep, "faltas", home0, true))) }
        var oFouls by remember { mutableStateOf(digits(repStat(rep, "faltas", home0, false))) }
        var oForm by remember { mutableStateOf(repStat(rep, "formacao", home0, false)?.takeIf { it != NI } ?: "") }
        var mom by remember { mutableStateOf(rep?.optString("mom")?.ifBlank { null }?.takeIf { !Overlay.isChip(it) } ?: "") }
        var msg by remember { mutableStateOf<String?>(null) }

        // Placar grande, como no fim de jogo do OSM.
        val sa = a.toIntOrNull()
        val sb = b.toIntOrNull()
        val resColor = when {
            sa == null || sb == null -> C.SURFACE2
            sa > sb -> C.WIN
            sa == sb -> C.DRAW
            else -> C.LOSS
        }
        Box(
            Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(20.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF13284F), C.SURFACE)))
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Jornada ${cur.round}" + (if (home == true) " • em casa" else if (home == false) " • fora" else ""), color = C.MUTED, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(known(d.fields, K.TEAM) ?: "Meu time", fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 2)
                    Box(Modifier.clip(RoundedCornerShape(14.dp)).background(resColor).padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text((sa?.toString() ?: "–") + "  :  " + (sb?.toString() ?: "–"), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    }
                    Text(cur.rival, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 2)
                }
                val origin = when {
                    repScore -> "✔ Lido da análise do jogo"
                    calMine != null -> "✔ Lido do calendário"
                    else -> "Ainda sem placar lido — preencha abaixo"
                }
                Text(origin, fontSize = 12.5.sp, color = if (initMine.isNotBlank()) C.OK else C.WARN, modifier = Modifier.padding(top = 8.dp))
                if (cur.formation != null) {
                    Text("Tática usada: ${cur.formation} • ${cur.style ?: "?"}", fontSize = 13.sp, color = C.GOLD, modifier = Modifier.padding(top = 2.dp))
                } else {
                    Text("Sem tática gerada neste jogo (o resultado ainda ensina a IA sobre o rival).", fontSize = 12.5.sp, color = C.MUTED, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }

        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = a, onValueChange = { a = it.filter { c -> c.isDigit() }.take(2) }, singleLine = true, label = { Text("Meus gols") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = b, onValueChange = { b = it.filter { c -> c.isDigit() }.take(2) }, singleLine = true, label = { Text("Gols do rival") }, modifier = Modifier.weight(1f))
            }
            Row(Modifier.padding(top = 10.dp)) {
                FilterPill("🏠 Casa", home == true) { home = true }
                FilterPill("✈ Fora", home == false) { home = false }
            }
            Text("Estatísticas (ajudam a IA a aprender)", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = poss, onValueChange = { poss = it.filter { c -> c.isDigit() }.take(3) }, singleLine = true, label = { Text("Minha posse (%)") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = oForm, onValueChange = { oForm = it.take(12) }, singleLine = true, label = { Text("Formação do rival") }, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = mShots, onValueChange = { mShots = it.filter { c -> c.isDigit() }.take(2) }, singleLine = true, label = { Text("Meus remates") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = oShots, onValueChange = { oShots = it.filter { c -> c.isDigit() }.take(2) }, singleLine = true, label = { Text("Remates do rival") }, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = mFouls, onValueChange = { mFouls = it.filter { c -> c.isDigit() }.take(2) }, singleLine = true, label = { Text("Minhas faltas") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = oFouls, onValueChange = { oFouls = it.filter { c -> c.isDigit() }.take(2) }, singleLine = true, label = { Text("Faltas do rival") }, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(value = mom, onValueChange = { mom = it.take(30) }, singleLine = true, label = { Text("Homem do jogo") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            val canSave = a.isNotBlank() && b.isNotBlank() && home != null
            Button(
                enabled = canSave,
                onClick = {
                    val ma = a.toIntOrNull()
                    val mb = b.toIntOrNull()
                    val hh = home
                    if (ma != null && mb != null && hh != null) {
                        val round = cur.round
                        val p = poss.toIntOrNull()?.coerceIn(0, 100)
                        val ms = mShots.toIntOrNull()
                        val os = oShots.toIntOrNull()
                        val mf = mFouls.toIntOrNull()
                        val of = oFouls.toIntOrNull()
                        val form = oForm.ifBlank { null }
                        val star = mom.ifBlank { null }
                        AppScope.scope.launch(Dispatchers.IO) {
                            Repo(ctx).registerManualResult(slot, round, ma, mb, hh, p, ms, os, mf, of, form, star)
                            UiBus.version++
                        }
                        msg = "✔ Resultado salvo. A IA já usa este jogo para aprender."
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(50.dp),
                shape = RoundedCornerShape(10.dp)
            ) { Text(if (cur.result == null) "Salvar resultado" else "Corrigir resultado", fontWeight = FontWeight.Bold) }
            if (!canSave) Text("Informe os gols dos dois times e se foi em casa ou fora.", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
            val m = msg
            if (m != null) Text(m, color = C.OK, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Fim da competição (posição, pontos, campeão, valor) e o que a direção disse — entra no aprendizado e na IA. */
@Composable
internal fun BoardPanel(items: List<String>) {
    if (items.isEmpty()) return
    Fold("🏁", "Fim da competição e direção", items.size.toString()) { BoardLines(items) }
}

@Composable
internal fun BoardLines(items: List<String>) {
    for (t in items.take(8)) {
        val fired = Txt.norm(t).contains("demiss")
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Top) {
            Text(
                when {
                    t.startsWith("Direção") -> if (SeasonCard.mood(t) < 0) "😠" else if (SeasonCard.mood(t) > 0) "😀" else "💬"
                    t.startsWith("Campeão") -> "🏆"
                    t.startsWith("Valor") -> "💰"
                    t.startsWith("Fim") -> "🏁"
                    else -> "•"
                },
                fontSize = 14.sp, modifier = Modifier.width(24.dp)
            )
            Text(
                t, fontSize = 13.sp, color = if (fired) C.WARN else Color.White,
                fontWeight = if (fired) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f)
            )
        }
    }
    if (items.any { Txt.norm(it).contains("demiss") }) {
        Hint("A direção falou em demissão: na próxima época este slot pode ser de outro clube. O app recomeça o slot quando ler o novo time.", "demissão?")
    }
}

/** Um jogo analisado: placar, tática usada, o que deu errado/certo e os números (abre ao tocar). */
@Composable
internal fun GameCard(g: Coach.Game, rv: Coach.Review?) {
    var open by remember { mutableStateOf(false) }
    val col = when (g.result) { "V" -> C.WIN; "E" -> C.DRAW; "D" -> C.LOSS; else -> C.SURFACE2 }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(C.SURFACE)
            .drawBehind { drawRect(col, size = Size(5.dp.toPx(), size.height)) }.clickable { open = !open }
            .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ResultBadge(g.result)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "J${g.round} • " + (if (g.gf != null && g.ga != null) "${g.gf}-${g.ga} " else "") + "vs ${g.rival}",
                    fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (g.formation != null) "Sua tática: ${g.formation}" + (g.style?.let { " • $it" } ?: "") else "Tática não registrada no app",
                    fontSize = 12.5.sp, color = if (g.formation != null) C.GOLD else C.MUTED
                )
                if (g.myStr != null || g.rivalStr != null) Text("Força: ${g.myStr ?: "?"} × ${g.rivalStr ?: "?"}", fontSize = 12.5.sp, color = C.MUTED)
            }
            Text(if (open) "▲" else "▼", color = C.MUTED, fontSize = 13.sp)
        }
        if (rv != null) {
            for (w in rv.wrong.take(if (open) 9 else 2)) Text("✘ $w", fontSize = 13.sp, color = C.LOSS, modifier = Modifier.padding(top = 3.dp))
            for (r in rv.right.take(if (open) 9 else 1)) Text("✔ $r", fontSize = 13.sp, color = C.OK, modifier = Modifier.padding(top = 3.dp))
        }
        if (open) {
            if (g.poss != null) Duel("POSSE %", g.poss.toString(), (100 - g.poss).toString())
            if (g.shots != null || g.oppShots != null) Duel("REMATES", g.shots?.toString(), g.oppShots?.toString())
            val extra = listOfNotNull(g.fouls?.let { "suas faltas $it" }, g.oppFormation?.let { "formação do rival $it" }).joinToString(" • ")
            if (extra.isNotBlank()) Text(extra, fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
            g.advice?.takeIf { it.isNotBlank() }?.let { Text("Analista: “$it”", fontSize = 12.5.sp, color = C.GOLD, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

@Composable
internal fun SlotLearning(slot: Int, d: SlotData) {
    d.progress?.let { ScoreboardCard(it.score) }
    Results.of(d.logsAll.mapNotNull { p -> runCatching { JSONObject(p.json).also { it.put("genAt", p.at) } }.getOrNull() })?.let { ResultsCard(it, "S$slot, todas as competições") }
    Accuracy.of(Accuracy.parse(slot, d.logsAll.mapNotNull { runCatching { JSONObject(it.json) }.getOrNull() }))?.let { AccuracyCard(it, "S$slot, todas as competições") }
    LearnedCard(d.learned)
    ImpactCard(slot, d)
    data class Row2(
        val kind: String, val round: Int, val formation: String, val style: String, val rival: String,
        val result: String?, val score: String, val human: Boolean?, val home: Boolean?
    )
    var resultFor by remember { mutableStateOf<Row2?>(null) }
    val rows = d.logs.mapNotNull { p ->
        try {
            val j = JSONObject(p.json)
            Row2(
                p.kind, j.optInt("round", -1), j.optString("formation"), j.optString("playStyle"), j.optString("rival"),
                if (j.isNull("result")) null else j.optString("result"),
                if (j.isNull("scoreMine")) "" else "${j.optInt("scoreMine")}-${j.optInt("scoreOpp")}",
                if (j.isNull("human")) null else j.optBoolean("human"),
                if (j.isNull("home")) null else j.optBoolean("home")
            )
        } catch (e: Exception) {
            null
        }
    }.sortedByDescending { it.round }
    val hist = rows.map { HistRow(it.formation, it.style, it.result, it.human, it.home) }
    val games = Coach.games(
        d.logs.mapNotNull { runCatching { JSONObject(it.json) }.getOrNull() },
        d.reports.mapNotNull { p ->
            runCatching { JSONObject(p.json).also { j -> if (!j.has("round")) p.kind.removePrefix("mr_R").toIntOrNull()?.let { j.put("round", it) } } }.getOrNull()
        }
    )
    val ended = Fixtures.seasonEnded(d.fields, d.matches, System.currentTimeMillis())
    val nextRival = if (ended) null else known(d.fields, K.RIVAL_TEAM)
    val lessons = Coach.lessons(games, nextRival)
    val logsJ = d.logs.mapNotNull { runCatching { JSONObject(it.json) }.getOrNull() }
    val (hit, graded) = Calib.accuracy(logsJ)
    val calib = Calib.offset(logsJ)

    if (ended) SeasonEndCard(d)
    BoardPanel(d.board)

    // ---- 1. o que muda no próximo jogo
    Panel {
        Text(if (ended) "🧠 Lições para a próxima competição" else "🧠 O que muda no próximo jogo", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        if (games.isEmpty()) {
            Text("Ainda sem jogos analisados", fontSize = 14.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
            Hint("Depois de cada jogo, abra a análise do jogo no OSM: eu leio posse, remates e faltas e digo onde errou.")
        } else {
            if (nextRival != null) Text("vs $nextRival", fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 2.dp))
            if (lessons.plan.isEmpty()) Text("Nada a mudar: manter o que vem funcionando.", fontSize = 14.sp, color = C.OK, modifier = Modifier.padding(top = 6.dp))
            for (line in lessons.plan) AlertCard("🔵 $line")
            Text("Isso já entra quando você toca em “Gerar tática”.", fontSize = 11.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 6.dp))
        }
    }

    // ---- 2. como o time vem jogando
    if (games.isNotEmpty()) {
        Panel {
            Text("📈 Como o time vem jogando", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            val res = games.mapNotNull { it.result }
            FormGuide(res.take(10).reversed())
            fun avg(f: (Coach.Game) -> Int?): Int? = games.mapNotNull(f).takeIf { it.isNotEmpty() }?.average()?.toInt()
            val gf = games.mapNotNull { it.gf }.sum()
            val ga = games.mapNotNull { it.ga }.sum()
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatTile("⚽", "$gf×$ga", "gols pró×contra", if (gf >= ga) C.OK else C.LOSS, Modifier.weight(1f))
                avg { it.poss }?.let { StatTile("🔄", "$it%", "posse média", if (it >= 50) C.OK else C.WARN, Modifier.weight(1f)) }
                val sh = avg { it.shots }
                val osh = avg { it.oppShots }
                if (sh != null && osh != null) StatTile("🎯", "$sh×$osh", "remates/jogo", if (sh >= osh) C.OK else C.WARN, Modifier.weight(1f))
            }
            if (graded > 0) {
                Text(
                    "🔮 Acertei o resultado mais provável em $hit de $graded jogo(s)." +
                        (if (calib > 0.01) " O time rende acima do previsto: já ajustei para cima." else if (calib < -0.01) " O time rende abaixo do previsto: já ajustei para baixo." else ""),
                    fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        // ---- 3. jogo a jogo (um cartão por jogo; toque para ver os números)
        Title("Jogo a jogo")
        for (g in games.take(8)) GameCard(g, Coach.review(g))
    }

    // ---- 4. o que funciona por formação, estilo e contexto
    val groups = listOf(
        "Por formação" to Learning.stats(hist),
        "Por estilo de jogo" to Learning.byStyle(hist),
        "Por contexto" to Learning.byContext(hist)
    ).filter { it.second.isNotEmpty() }
    if (groups.isNotEmpty()) {
        Fold("📊", "O que funciona com as suas táticas", rows.count { it.result != null }.toString() + " jogo(s)") {
            for ((title, list) in groups) {
                Text(title, fontSize = 13.sp, color = C.MUTED, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                for (st in list) {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(st.formation, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.width(120.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        CountPill("${st.v}V", C.WIN)
                        CountPill("${st.e}E", C.DRAW)
                        CountPill("${st.d}D", C.LOSS)
                    }
                }
            }
        }
    }
    // ---- táticas geradas que ainda não têm resultado
    // o jogo que ainda vai acontecer (horário no futuro) não "falta resultado"
    val upcomingRound = if ((known(d.fields, K.MATCH_AT)?.toLongOrNull() ?: 0L) > System.currentTimeMillis()) known(d.fields, K.ROUND)?.toIntOrNull() else null
    val noResult = rows.filter { it.result == null && it.score.isBlank() && !(upcomingRound != null && it.round == upcomingRound) }
    if (noResult.isNotEmpty()) {
        Fold("📝", "Táticas sem resultado", noResult.size.toString()) {
            Text("Toque para informar o placar: assim o app aprende com ele.", fontSize = 12.5.sp, color = C.MUTED)
            for (r in noResult.take(8)) {
                Row(Modifier.fillMaxWidth().clickable { resultFor = r }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ResultBadge(null)
                    Text("  J${r.round} • ${r.formation} vs ${r.rival}", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("＋", color = C.PRIMARY, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    val rf = resultFor
    if (rf != null) ResultDialog(slot, rf.kind, "J${rf.round} • ${rf.formation} vs ${rf.rival}") { resultFor = null }
    if (d.learning.isNotEmpty()) {
        Fold("🗂", "Registro do que o app leu", d.learning.size.toString()) {
            for (l in d.learning) {
                Text("${fmtTime(l.at)} • ${l.kind}: ${l.text}", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

