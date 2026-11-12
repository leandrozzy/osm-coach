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

/** Medidor circular (0..100) para os controles deslizantes do jogo. */
@Composable
internal fun Gauge(label: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(84.dp)) {
                val sw = 9.dp.toPx()
                val pad = sw / 2f
                val arc = Size(size.width - sw, size.height - sw)
                drawArc(C.SURFACE2, 135f, 270f, false, topLeft = Offset(pad, pad), size = arc, style = Stroke(sw, cap = StrokeCap.Round))
                drawArc(
                    Brush.sweepGradient(listOf(color.copy(alpha = 0.6f), color, color.copy(alpha = 0.6f))),
                    135f, 270f * value.coerceIn(0, 100) / 100f, false, topLeft = Offset(pad, pad), size = arc,
                    style = Stroke(sw, cap = StrokeCap.Round)
                )
            }
            Text(value.toString(), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }
        Text(label, fontSize = 12.5.sp, color = C.MUTED, textAlign = TextAlign.Center, maxLines = 3)
    }
}

/** Chip de configuração (rótulo pequeno em cima, valor em destaque). */
@Composable
internal fun SettingChip(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(C.SURFACE2).padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label, fontSize = 11.5.sp, color = C.MUTED, maxLines = 1)
        Text(value.ifBlank { "—" }, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun GenPanel(kind: String, slot: Int, label: String, onStart: () -> Unit) {
    val tick = rememberTick(1000L)
    val job = GenState.get(kind, slot)
    val running = job != null && job.ok == null
    Button(
        enabled = !running,
        onClick = onStart,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(10.dp)
    ) { Text(if (running) "Trabalhando… (continua em segundo plano)" else label, fontWeight = FontWeight.Bold) }
    if (job == null || tick < 0) return
    val end = if (job.ok == null) System.currentTimeMillis() else job.finishedAt
    val secs = (end - job.startedAt) / 1000
    val time = if (secs >= 60) "${secs / 60} min ${secs % 60}s" else "${secs}s"
    Panel {
        when (job.ok) {
            null -> {
                Text("⏳ Trabalhando… $time", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)), color = C.PRIMARY, trackColor = C.SURFACE2)
                Text(
                    if (AiStatus.stage.isNotBlank()) AiStatus.stage else "Montando os dados…",
                    fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp)
                )
                Text("Pode trocar de aba ou sair do app: continua.", fontSize = 12.5.sp, color = C.MUTED)
            }
            true -> Text("✔ Pronto em $time", color = C.OK, fontWeight = FontWeight.Bold)
            false -> {
                Text("✘ Não deu certo após $time", color = C.BAD, fontWeight = FontWeight.Bold)
                Text(job.error ?: "erro desconhecido", fontSize = 13.sp, color = C.WARN, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Cor do selo da previsão: verde favorito, azul equilibrado, âmbar azarão (o número fica sempre branco). */
internal fun winColor(win: Int): Color = when {
    win >= 55 -> C.OK
    win >= 38 -> C.PRIMARY
    else -> C.GOLD
}

internal fun winIcon(win: Int): String = when {
    win >= 55 -> "💪"
    win >= 38 -> "⚖"
    else -> "🛡"
}

/** Veredito do jogo pela chance de vitória/derrota: ícone, nome e cor próprios (5 níveis, do verde forte ao vermelho). */
internal data class Verdict(val icon: String, val label: String, val color: Color)

internal fun verdictOf(win: Int, draw: Int, loss: Int): Verdict = when {
    win >= 65 -> Verdict("🏆", "Favorito claro", Color(0xFF22C55E))
    win >= 50 -> Verdict("💪", "Favorito", Color(0xFF3DDC84))
    kotlin.math.abs(win - loss) <= 10 -> Verdict("⚖", "Equilibrado", C.PRIMARY)
    loss >= 50 -> Verdict("🛡", "Azarão", Color(0xFFFF6B4A))
    else -> Verdict("⚡", "Leve desvantagem", Color(0xFFFFB020))
}

/** Selo do veredito: bolinha colorida com o ícone + nome, fundo em degradê da cor do nível. */
@Composable
internal fun VerdictPill(v: Verdict, big: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape)
            .background(Brush.horizontalGradient(listOf(v.color.copy(alpha = 0.38f), v.color.copy(alpha = 0.10f))))
            .border(1.dp, v.color.copy(alpha = 0.75f), shape)
            .padding(start = 3.dp, end = if (big) 12.dp else 9.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(if (big) 26.dp else 22.dp).clip(RoundedCornerShape(50)).background(v.color.copy(alpha = 0.30f)), contentAlignment = Alignment.Center) {
            Text(v.icon, fontSize = if (big) 15.sp else 12.sp)
        }
        Spacer(Modifier.width(6.dp))
        Text(v.label, fontSize = if (big) 14.sp else 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1)
    }
}

/** Selo com o veredito da previsão. */
@Composable
internal fun VerdictChip(p: WinModel.Prob, big: Boolean = false) = VerdictPill(verdictOf(p.win, p.draw, p.loss), big)

/** Barra V/E/D em três cores com o rótulo dentro de cada parte (quando cabe). */
@Composable
internal fun WinBar(p: WinModel.Prob, height: Dp) {
    Row(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50))) {
        for ((v, col, lab) in listOf(Triple(p.win, C.WIN, "V"), Triple(p.draw, C.DRAW, "E"), Triple(p.loss, C.LOSS, "D"))) {
            Box(Modifier.weight(v.toFloat().coerceAtLeast(1f)).fillMaxSize().background(col), contentAlignment = Alignment.Center) {
                if (height >= 16.dp && v >= 12) Text("$lab $v%", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0B1220), maxLines = 1)
            }
        }
    }
}

/** Previsão do jogo: chance de vencer em destaque, veredito, placar provável e o que pesa a favor/contra. */
@Composable
internal fun WinCard(p: WinModel.Prob?, compact: Boolean = false) {
    if (p == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📈", fontSize = 22.sp)
            Text("  Previsão aparece ao gerar a tática", fontSize = 14.sp, color = C.MUTED, modifier = Modifier.weight(1f))
        }
        return
    }
    if (compact) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${p.win}%", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Text(" de vencer ", fontSize = 13.sp, color = Color(0xCCFFFFFF), maxLines = 1)
            Box(Modifier.weight(1f)) { VerdictChip(p) }
            Text("${p.likely.first}x${p.likely.second}", fontSize = 13.sp, color = C.MUTED, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        WinBar(p, 8.dp)
        return
    }
    Text("📈 PREVISÃO DO JOGO", fontSize = 12.5.sp, color = Color(0xB3FFFFFF), fontWeight = FontWeight.Bold)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${p.win}%", fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("chance de vencer", fontSize = 13.sp, color = Color(0xCCFFFFFF))
            Box(Modifier.padding(top = 3.dp)) { VerdictChip(p, big = true) }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("placar provável", fontSize = 11.5.sp, color = Color(0xB3FFFFFF))
            Text("${p.likely.first} x ${p.likely.second}", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
    Spacer(Modifier.height(8.dp))
    WinBar(p, 18.dp)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Vitória ${p.win}%", fontSize = 12.5.sp, color = C.WIN, fontWeight = FontWeight.SemiBold)
        Text("Empate ${p.draw}%", fontSize = 12.5.sp, color = C.DRAW, fontWeight = FontWeight.SemiBold)
        Text("Derrota ${p.loss}%", fontSize = 12.5.sp, color = C.LOSS, fontWeight = FontWeight.SemiBold)
    }
    val pros = p.factors.filter { it.pts >= 1.0 }.sortedByDescending { it.pts }.take(3)
    val cons = p.factors.filter { it.pts <= -1.0 }.sortedBy { it.pts }.take(3)
    if (pros.isNotEmpty() || cons.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("A favor", fontSize = 12.5.sp, color = C.OK, fontWeight = FontWeight.Bold)
                if (pros.isEmpty()) Text("—", fontSize = 13.sp, color = C.MUTED)
                for (f in pros) Text("✓ ${f.label}", fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("Contra", fontSize = 12.5.sp, color = C.BAD, fontWeight = FontWeight.Bold)
                if (cons.isEmpty()) Text("—", fontSize = 13.sp, color = C.MUTED)
                for (f in cons) Text("! ${f.label}", fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
    Text("Confiança ${p.confidence}: quanto mais telas do rival lidas, mais precisa.", fontSize = 11.5.sp, color = Color(0x99FFFFFF), modifier = Modifier.padding(top = 8.dp))
}

/** Conferência entre telas: o mesmo dado lido no pré-jogo, calendário, análise e plantel do rival. */
@Composable
internal fun EvidencePanel(ev: JSONObject?, cpu: Boolean = false) {
    if (ev == null || ev.length() == 0) return
    // rival CPU não tem apelido: a linha do apelido não se aplica
    val items = listOfNotNull(
        K.RIVAL_TEAM to "Próximo rival", if (cpu) null else K.RIVAL_NICK to "Apelido (rival humano)", K.HOME to "Casa/fora",
        K.RIVAL_STRENGTH to "Força do rival", K.RIVAL_FORMATION to "Formação do rival"
    )
    Panel {
        Text("🔎 Conferência entre telas", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text("Um dado só é dado como certo quando aparece igual em telas diferentes.", fontSize = 12.5.sp, color = C.MUTED)
        for ((k, label) in items) {
            val vals = Evidence.values(ev, k)
            if (vals.isEmpty()) continue
            val top = vals.first()
            val srcs = top.sources.joinToString(" + ") { Evidence.sourceLabel(it) }
            val (icon, color, text) = when {
                vals.size > 1 -> Triple("⚠", C.WARN, vals.joinToString("  ×  ") { b -> b.value + " (" + b.sources.joinToString("+") { Evidence.sourceLabel(it) } + ")" })
                top.weight >= 2 -> Triple("✔", C.OK, "${top.value} — confirmado: $srcs")
                else -> Triple("•", C.MUTED, "${top.value} — só em $srcs (aguardando outra tela)")
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                Text(icon, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, fontSize = 12.5.sp, color = C.MUTED)
                    Text(text, fontSize = 13.sp, color = if (icon == "•") Color.White else color)
                }
            }
        }
    }
}

@Composable
internal fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 10.dp)) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(color))
        Text(" $label", fontSize = 12.5.sp, color = C.MUTED)
    }
}

@OptIn(ExperimentalLayoutApi::class)
/** Resumo da tática do próximo jogo no topo da aba: formação, estilo, números-chave e a chance de vitória. */
@Composable
internal fun TacticHero(d: SlotData) {
    val plan = d.tactic ?: return
    val j = runCatching { JSONObject(plan.json) }.getOrNull() ?: return
    if (!Director.tacticValid(j, plan.at, d.fields, System.currentTimeMillis())) return
    val pr = j.optInt("pressure"); val me = j.optInt("mentality"); val te = j.optInt("tempo")
    val byAi = j.optJSONObject("ai") != null
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).osmPanel().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚡ Tática do próximo jogo", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Pill(if (byAi) "IA + simulação" else "simulação", null)
        }
        Text("Gerada ${ago(plan.at)}" + (known(d.fields, K.RIVAL_TEAM)?.let { " • contra $it" } ?: ""), fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 2.dp))
        Text(j.optString("formation"), fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = C.PRIMARY, modifier = Modifier.padding(top = 10.dp))
        Text(j.optString("playStyle"), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((lab, v, sub) in listOf(Triple("Pressão", pr, Osm.pressureLabel(pr)), Triple("Estilo", me, Osm.mentalityLabel(me)), Triple("Ritmo", te, Osm.tempoLabel(te)))) {
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color(0xFF0A1830)).border(1.dp, Color(0x332AB4FF), RoundedCornerShape(14.dp)).padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(lab, fontSize = 12.sp, color = C.MUTED)
                    Text(v.toString(), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Text(sub, fontSize = 11.5.sp, color = C.PRIMARY, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        FlowRow(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Pill("Marcação: " + j.optString("marking"))
            Pill("Fora-de-jogo: " + j.optString("offside"))
            Pill("Desarme: " + j.optString("tackle"))
        }
        d.win?.let { w ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Chance de vitória", fontSize = 13.sp, color = C.MUTED, modifier = Modifier.weight(1f))
                Text("${w.win}%", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = if (w.win >= 50) C.OK else C.GOLD)
            }
            Box(Modifier.padding(top = 6.dp)) { WinBar(w, 8.dp) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SlotTactic(slot: Int, d: SlotData) {
    val ctx = LocalContext.current
    if (Fixtures.seasonEnded(d.fields, d.matches, System.currentTimeMillis())) {
        SeasonEndCard(d)
        Hint(Director.ENDED_MSG)
        return
    }
    TacticHero(d)
    WhyCard(slot, d)
    if (d.rivalChanged.isNotEmpty()) RivalChangedCard(d.rivalChanged) { startGeneration(ctx, "tactic", slot) }
    d.rivalPred?.takeIf { it.guesses.isNotEmpty() }?.let { RivalPredictCard(it, known(d.fields, K.RIVAL_NICK)) }
    // Checklist: o que ainda falta ler e em qual tela do OSM (a tática sai melhor com tudo lido).
    val secret = SecretTraining.on(d.fields)
    val human = secret || fv(d, K.RIVAL_HUMAN) == "Sim"
    // Treino secreto (cadeado): força e relatório do analista não existem neste jogo, não "faltam".
    val checks = listOfNotNull(
        Pair("Força do rival", "Pré-jogo (círculo do rival)").takeIf { !secret && known(d.fields, K.RIVAL_STRENGTH) == null },
        Pair("Setores do rival (GOL/DEF/MEI/ATA)", "Plantel do rival").takeIf { known(d.fields, K.RIVAL_DEF) == null || known(d.fields, K.RIVAL_ATK) == null },
        Pair("Formação do rival", "Análise do rival").takeIf { known(d.fields, K.RIVAL_FORMATION) == null },
        Pair("Estilo do rival", "Análise do rival").takeIf { !secret && known(d.fields, K.RIVAL_PLAN) == null },
        Pair("Árbitro", "Pré-jogo (termômetro)").takeIf { known(d.fields, K.REFEREE) == null },
        Pair("Nome do usuário rival", "Pré-jogo ou plantel do rival").takeIf { human && known(d.fields, K.RIVAL_NICK) == null },
        Pair("Seu elenco", "Plantel do seu time (role até o fim)").takeIf { d.players.count { it.owner == "MY" && it.strength != null } < 14 }
    )
    if (d.conflicts.isNotEmpty()) {
        Panel {
            Text("⚠ Leituras que não batem — confira no jogo", color = C.WARN, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            for (c in d.conflicts) Text("• $c", fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
            Text("Abra a tela de novo (a leitura repetida confirma) ou corrija o valor no Pré-jogo.", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
        }
    }
    if (d.unavailable.isNotEmpty()) {
        Panel {
            Text("⛔ ${d.unavailable.size} fora deste jogo (não entram na escalação)", fontSize = 14.sp, color = C.WARN, fontWeight = FontWeight.Bold)
            FlowRow(Modifier.padding(top = 6.dp)) {
                for (k in d.unavailable) Pill(d.players.firstOrNull { it.nameKey == k }?.name ?: k, false)
            }
            OutlinedButton(
                onClick = {
                    AppScope.scope.launch(Dispatchers.IO) {
                        val r = Repo(ctx)
                        for (k in d.unavailable) r.setUnavailable(slot, k, false)
                        UiBus.version++
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) { Text("✓ Liberar todos") }
            Hint("Marque quem está lesionado ou suspenso tocando no jogador na aba Elenco. Vale só para esta rodada.", "como marcar")
        }
    }
    if (secret) {
        Panel {
            Text("🔒 Rival fez TREINO SECRETO", color = OsmColors.YELLOW, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                "Rival humano. O jogo esconde a força, o bônus e o relatório do analista dele, então isso não falta ler. " +
                    (SecretTraining.estimate(d.fields)?.let { "Força estimada pelos setores do plantel: $it (com margem). " } ?: "Leia o Plantel do rival para eu estimar a força. ") +
                    "A tática vem mais segura: sem linha de impedimento e meio-campo protegido.",
                fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
    if (checks.isNotEmpty()) {
        Panel {
            Text("📋 Falta ler ${checks.size} — a tática fica menos precisa", color = C.WARN, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            FlowRow(Modifier.padding(top = 6.dp)) {
                for ((what, _) in checks) Pill("✗ $what", false)
            }
            Hint(checks.joinToString("\n") { (what, where) -> "• $what → abra: $where" }, "onde ler")
        }
    } else {
        Text("✔ Tudo lido para este jogo", color = C.OK, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
    }
    val gaps = Director.tacticGaps(d.fields, System.currentTimeMillis())
    if (gaps.isEmpty()) {
        Text("✓ " + Director.checklist(d.fields, System.currentTimeMillis()), fontSize = 12.5.sp, color = C.OK, modifier = Modifier.padding(bottom = 6.dp))
        GenPanel("tactic", slot, "⚡ Gerar tática para o próximo jogo") { startGeneration(ctx, "tactic", slot) }
    } else {
        Panel {
            Text("🔎 Leia antes de gerar (a tática precisa do rival)", color = C.WARN, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            for ((what, where) in gaps) Text("• $what → $where", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Text("Tudo é obrigatório (a tática precisa do rival inteiro). Leituras valem por 3 h (o rival humano pode mudar a tática). Com o app ligado, passe por essas telas no OSM; a leitura repetida renova o prazo.", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 6.dp))
        }
        GenPanel("tactic", slot, "Gerar mesmo assim (menos precisa)") { startGeneration(ctx, "tactic", slot, force = true) }
    }
    val plan = d.tactic
    if (plan == null) {
        EmptyState("⚡", "Nenhuma tática gerada ainda", "A tática é calculada na hora com o seu elenco, a força do rival, o árbitro e o que já deu certo ou errado antes. Toque no botão acima.")
        return
    }
    val j = try {
        JSONObject(plan.json)
    } catch (e: Exception) {
        null
    }
    if (j == null) return
    val forRound = j.optInt("forRound", -1)
    val curRound = fv(d, K.ROUND).toIntOrNull()
    if (curRound != null && forRound != curRound) {
        Text(
            "⚠ A tática guardada é de outra rodada (${if (forRound > 0) forRound else "?"}); o próximo jogo é a rodada $curRound. Toque em “Gerar tática” para criar a desta rodada.",
            color = C.WARN, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)
        )
        return
    }
    if (!Director.tacticValid(j, plan.at, d.fields, System.currentTimeMillis())) {
        Text(
            "⚠ A tática guardada foi gerada para o jogo anterior (ou o jogo já começou). Toque em “Gerar tática” para criar a do próximo jogo.",
            color = C.WARN, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)
        )
        return
    }

    // A tela segue a do próprio OSM, na mesma ordem: Próximo jogo → Equipa inicial (formação) → Estilo de jogo e
    // Desarme → Táticas por sector → Pressão / Estilo / Temporização → Marcação e Fora-de-jogo.
    val style = Osm.style(j.optString("playStyle")) ?: j.optString("playStyle")
    val tackle = Osm.tackle(j.optString("tackle")) ?: j.optString("tackle")
    val att = Osm.attack(j.optString("advAttack")) ?: j.optString("advAttack")
    val mid = Osm.midfield(j.optString("advMid")) ?: j.optString("advMid")
    val def = Osm.defense(j.optString("advDef")) ?: j.optString("advDef")
    val marking = Osm.marking(j.optString("marking")) ?: j.optString("marking")
    val offside = j.optString("offside")
    val pressure = j.optInt("pressure")
    val mentality = j.optInt("mentality")
    val tempo = j.optInt("tempo")

    // ---- Próximo jogo
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).osmPanel().padding(12.dp)) {
        Text("Próximo jogo", fontSize = 12.5.sp, color = OsmColors.CAPTION, modifier = Modifier.align(Alignment.CenterHorizontally))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Crest(slot, 46.dp, known(d.fields, K.TEAM))
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                Text(known(d.fields, K.TEAM) ?: "Meu time", fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(known(d.fields, K.HOME)?.let { if (it == "Casa") "em casa" else "fora" } ?: "", fontSize = 12.5.sp, color = OsmColors.CAPTION)
            }
            StrengthBubble(known(d.fields, K.MY_STRENGTH))
            Text(
                " VS ", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = OsmColors.YELLOW,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
            )
            StrengthBubble(known(d.fields, K.RIVAL_STRENGTH) ?: if (secret) "🔒" else null)
            Column(Modifier.weight(1f).padding(end = 6.dp), horizontalAlignment = Alignment.End) {
                Text(j.optString("rival"), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                Text(known(d.fields, K.RIVAL_NICK) ?: if (fv(d, K.RIVAL_HUMAN) == "Não") "CPU" else "", fontSize = 12.5.sp, color = OsmColors.CAPTION, maxLines = 1)
            }
            RivalShield(j.optString("rival"), 46.dp)
        }
        Text(
            (if (forRound > 0) "Jornada $forRound • " else "") + "tática gerada ${ago(plan.at)}",
            fontSize = 12.5.sp, color = C.MUTED, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        )
        AiDecision(j)
        Spacer(Modifier.height(8.dp))
        WinCard(d.win)
    }

    // ---- Equipa inicial
    OsmTitle("Escalação: quem joga")
    Column(Modifier.fillMaxWidth().osmPanel().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        OsmCaption("Formação", j.optString("formation"), valueSize = 22)
        Spacer(Modifier.height(6.dp))
        LineupPitch(j)
        FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
            LegendDot(Color(0xFFFFC83D), "GOL")
            LegendDot(Color(0xFF3D8BFF), "DEF")
            LegendDot(Color(0xFF9B6BFF), "MEI")
            LegendDot(Color(0xFFFF5C5C), "ATA")
        }
        Text("O número na camisa é a força do titular.", fontSize = 12.5.sp, color = C.MUTED)
    }

    // ---- Tática
    OsmTitle("Tática: o que montar no OSM")
    OsmSplitCard(
        left = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                OsmCaption("Estilo de jogo", style)
                StyleIllustration(style, Modifier.fillMaxWidth().height(118.dp).padding(top = 6.dp))
                OsmDots(Osm.STYLES.size, Osm.STYLES.indexOf(style), Modifier.padding(top = 4.dp))
            }
        },
        right = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                OsmCaption("Desarme", tackle)
                TackleIllustration(tackle, Modifier.size(width = 130.dp, height = 112.dp).padding(top = 6.dp))
                OsmDots(Osm.TACKLES.size, Osm.TACKLES.indexOf(tackle), Modifier.padding(top = 4.dp))
            }
        }
    )
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).osmPanel().padding(10.dp)) {
        Text("Táticas por sector", fontSize = 12.5.sp, color = OsmColors.CAPTION, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterHorizontally))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            SectorIllustration(att, mid, def, Modifier.size(width = 128.dp, height = 172.dp))
            Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectorChoice("Avançados", att, Osm.ATTACK)
                SectorChoice("Médios", mid, Osm.MIDFIELD)
                SectorChoice("Defesas", def, Osm.DEFENSE)
            }
        }
    }
    OsmSlider("Pressão", Osm.pressureLabel(pressure), pressure, Modifier.padding(vertical = 4.dp))
    OsmSlider("Estilo", Osm.mentalityLabel(mentality), mentality, Modifier.padding(vertical = 4.dp))
    OsmSlider("Temporização", Osm.tempoLabel(tempo), tempo, Modifier.padding(vertical = 4.dp))
    OsmSplitCard(
        left = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                OsmCaption("Marcação", marking)
                MarkingIllustration(marking, Modifier.size(width = 130.dp, height = 150.dp).padding(top = 6.dp))
                OsmDots(2, Osm.MARKING.indexOf(marking), Modifier.padding(top = 4.dp))
            }
        },
        right = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                OsmCaption("Fazer fora-de-jogo", offside)
                OffsideIllustration(offside, Modifier.size(width = 130.dp, height = 150.dp).padding(top = 6.dp))
                OsmDots(2, if (offside == "Sim") 1 else 0, Modifier.padding(top = 4.dp))
            }
        }
    )
    Spacer(Modifier.height(8.dp))
    GenPanel("tactic_ai", slot, "🤖 Consultar a IA de novo") { startGeneration(ctx, "tactic_ai", slot) }

    val lineupArr = j.optJSONArray("lineup")
    if (lineupArr != null && lineupArr.length() > 1) {
        Fold("📋", "Escalação por setor", "${lineupArr.length()} linhas") {
            for (i in 0 until lineupArr.length()) {
                val row = lineupArr.optJSONArray(i) ?: continue
                val label = if (i == 0) "GOL" else if (i == 1) "DEF" else if (i == lineupArr.length() - 1) "ATA" else "MEI"
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.width(44.dp).clip(RoundedCornerShape(8.dp)).background(catColor(label).copy(alpha = 0.25f)).padding(vertical = 3.dp), contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(8.dp))
                    FlowRow(Modifier.weight(1f)) {
                        for (k in 0 until row.length()) {
                            val o = row.optJSONObject(k) ?: continue
                            Pill(o.optString("n") + " " + o.optInt("s"))
                        }
                    }
                }
            }
        }
    }
    val ra = j.optJSONArray("rationale")
    if (ra != null && ra.length() > 0) {
        Fold("💡", "Por que esta tática", ra.length().toString()) {
            for (i in 0 until ra.length()) Text("• " + ra.optString(i), fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp))
        }
    }
    val rk = j.optJSONArray("ranking")
    if (rk != null && rk.length() > 1) {
        Fold("📊", "Outras formações calculadas", (rk.length() - 1).toString()) {
            val best = (0 until rk.length()).mapNotNull { rk.optJSONArray(it)?.optLong(1) }.maxOrNull()?.coerceAtLeast(1L) ?: 1L
            for (i in 0 until rk.length()) {
                val r = rk.optJSONArray(i) ?: continue
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. ${r.optString(0)}", fontSize = 13.sp, modifier = Modifier.width(96.dp))
                    Box(Modifier.weight(1f)) { Bar(((r.optLong(1) * 100) / best).toInt()) }
                    Text("  ${r.optLong(1)}", fontSize = 12.5.sp, color = C.MUTED)
                }
            }
        }
    }
}

/** Círculo da força do time, como ao lado do "VS" no jogo. */
@Composable
internal fun StrengthBubble(v: String?) {
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(50)).background(Color(0xFF0B1C3E)).border(2.dp, OsmColors.BLUE, RoundedCornerShape(50)),
        contentAlignment = Alignment.Center
    ) { Text(v ?: "?", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White) }
}

/** Uma linha das táticas por sector: rótulo laranja, opção em branco e os pontinhos da posição na lista. */
@Composable
internal fun SectorChoice(label: String, value: String, options: List<String>) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.5.sp, color = OsmColors.ORANGE, fontWeight = FontWeight.SemiBold)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
        OsmDots(options.size, options.indexOf(value), Modifier.padding(top = 2.dp))
    }
}

