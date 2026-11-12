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

@Composable
internal fun SlotSquad(slot: Int, d: SlotData) {
    var trainEdit by remember { mutableStateOf<PlayerEntity?>(null) }
    var flagEdit by remember { mutableStateOf<PlayerEntity?>(null) }
    val mine = d.players.filter { it.owner == "MY" }
    val counts = HashMap<String, Int>()
    for (p in mine) p.cat?.let { counts[it] = (counts[it] ?: 0) + 1 }
    // titulares da tática gerada (só quando ela vale para o próximo jogo)
    val xiNames: Set<String> = run {
        val tp = d.tactic ?: return@run emptySet<String>()
        val o = runCatching { JSONObject(tp.json) }.getOrNull() ?: return@run emptySet<String>()
        if (!Director.tacticValid(o, tp.at, d.fields, System.currentTimeMillis())) return@run emptySet<String>()
        val rows = o.optJSONArray("lineup") ?: return@run emptySet<String>()
        buildSet { for (i in 0 until rows.length()) { val r = rows.optJSONArray(i) ?: continue; for (k in 0 until r.length()) r.optJSONObject(k)?.optString("n")?.let { add(it) } } }
    }
    var filter by remember { mutableStateOf("TODOS") }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).osmPanel().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("👥 Seu elenco", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Pill("${mine.size} jogadores", null)
        }
        Text("Força de cada setor do seu time (a de cima é o time todo)", fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 2.dp))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((lab, k, col) in listOf(Triple("Geral", K.MY_STRENGTH, C.PRIMARY), Triple("GOL", K.MY_GOL, catColor("GOL")), Triple("DEF", K.MY_DEF, catColor("DEF")), Triple("MEI", K.MY_MID, catColor("MEI")), Triple("ATA", K.MY_ATK, catColor("ATA")))) {
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(col.copy(alpha = 0.16f)).border(1.dp, col.copy(alpha = 0.55f), RoundedCornerShape(14.dp)).padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(known(d.fields, k) ?: "?", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    Text(lab, fontSize = 12.sp, color = C.MUTED)
                }
            }
        }
        Text("Quantos jogadores você tem em cada posição (tem / ideal)", fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 14.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (n in MarketPlanner.needs(counts)) {
                val ok = n.have >= n.target
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0A1830)).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${n.have}/${n.target}", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = if (ok) C.OK else C.WARN)
                    Text(n.cat, fontSize = 12.sp, color = C.MUTED)
                }
            }
        }
    }
    val seats = TrainBoard.seats(d.trainScreen, d.players, System.currentTimeMillis(), d.trainOv)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🏋 Treinamento", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Pill("${seats.count { it.state == "active" && it.fresh }} de 5 treinando", if (seats.count { it.state == "active" && it.fresh } >= 4) true else null)
        }
        Spacer(Modifier.height(8.dp))
        TrainingBoardView(seats)
        if (seats.any { it.state == "unknown" }) {
            Text("? = abra a tela de Treinamento no OSM para o app ler", fontSize = 12.5.sp, color = C.WARN, modifier = Modifier.padding(top = 6.dp))
        }
        if (seats.any { it.state == "done" }) {
            Text("✅ treino concluído: pegue a evolução no OSM e comece outro", fontSize = 12.5.sp, color = C.GOLD, modifier = Modifier.padding(top = 4.dp))
        }
        Hint(
            "O OSM tem 5 treinadores: Goleiros, Defesas, Médios e Avançados (um jogador da posição cada) e o Universal (qualquer posição). " +
                "O app lê quem está em cada um na tela de Treinamento — a camisa no plantel não conta. Lugar livre (＋) é treino desperdiçado. " +
                "Toque num jogador abaixo para marcar ⛔ fora do próximo jogo (lesão/suspensão, só nesta rodada), corrigir o treino ou remover quem não existe no elenco."
        )
    }
    if (mine.isEmpty()) {
        EmptyState("👕", "Nenhum jogador seu lido ainda", "No jogo: Plantel do SEU time, depois role a lista devagar até o fim.")
    }
    if (mine.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (f in listOf("TODOS", "ATA", "MEI", "DEF", "GOL")) FilterPill(if (f == "TODOS") "Todos" else f, filter == f) { filter = f }
        }
        Text("⚡ Cond. = condição física • 🙂 Moral • ★ titular da tática gerada", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(bottom = 4.dp))
    }
    for (cat in listOf("ATA", "MEI", "DEF", "GOL", "?")) {
        // "?": lidos sem posição — antes ficavam invisíveis (mas contavam), sem como remover.
        if (filter != "TODOS" && cat != filter) continue
        val list = mine.filter { (it.cat ?: "?") == cat }.sortedByDescending { it.strength ?: 0 }
        if (list.isEmpty()) continue
        Title(if (cat == "?") "Sem posição (confira)" else cat)
        Panel {
            for (p in list) {
                Row(Modifier.fillMaxWidth().clickable { trainEdit = p }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(catColor(cat).copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                        Text(p.strength?.toString() ?: "?", fontWeight = FontWeight.ExtraBold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.name, fontWeight = FontWeight.Bold, fontSize = 15.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (p.name.take(14) in xiNames) Text("  ★", color = C.GOLD, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                            if (p.forSale == true) Text("  🏷 À venda", color = C.WARN, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.clickable { flagEdit = p }, verticalAlignment = Alignment.CenterVertically) {
                                FlagBadge(d.isos[p.nameKey] ?: Flags.iso(d.countries[p.nameKey]), d.flagRich[p.nameKey], null, 30.dp, 20.dp, d.flagImg[p.nameKey], soft = true)
                            }
                            Text("${p.posCode ?: "?"} • ${p.age ?: "?"} anos • ${p.valueText ?: "?"}" + (Flags.countryLabel(d.countries[p.nameKey])?.let { " • $it" } ?: ""), fontSize = 13.sp, color = C.MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val ft = d.fitness[p.nameKey]
                        if (ft != null) {
                            Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                for ((ic, v) in listOf("Cond." to ft.first, "Moral" to ft.second)) {
                                    Text(ic, fontSize = 11.5.sp, color = C.MUTED)
                                    LinearProgressIndicator(
                                        progress = { v.coerceIn(0, 100) / 100f },
                                        modifier = Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(50)),
                                        color = if (v < 50) C.LOSS else if (v < 70) C.WARN else C.OK, trackColor = C.SURFACE2
                                    )
                                }
                            }
                        }
                    }
                    val seat = seats.firstOrNull { it.key == p.nameKey && it.state != "unknown" }
                    if (seat != null && seat.state == "active" && seat.fresh) Pill("🏋 " + (if (seat.role == "UNI") "UNI" else seat.role))
                    else if (seat != null && seat.state == "done") Pill("✅ pegar")
                    else if (d.trainScreen == null && p.training == true) Pill("🏋")
                    // só aparece quando marcado (marca e desmarca no toque do jogador, para não marcar sem querer)
                    if (p.nameKey in d.unavailable) {
                        Box(Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(C.LOSS).padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text("⛔ fora", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
    val te = trainEdit
    if (te != null) TrainingDialog(slot, te, te.nameKey in d.unavailable, onFlag = { flagEdit = te }) { trainEdit = null }
    flagEdit?.let { fe -> PlayerFlagDialog(fe) { flagEdit = null } }
}

@Composable
internal fun CountPill(text: String, color: Color) {
    Box(Modifier.padding(end = 6.dp).clip(RoundedCornerShape(50)).background(color).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (color == C.DRAW) Color(0xFF222222) else Color.White)
    }
}

@Composable
internal fun ResultBadge(result: String?) {
    val c = when (result) {
        "V" -> C.WIN
        "E" -> C.DRAW
        "D" -> C.LOSS
        else -> C.SURFACE2
    }
    Box(Modifier.size(24.dp).clip(RoundedCornerShape(50)).background(c), contentAlignment = Alignment.Center) {
        Text(result ?: "–", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (result == "E") Color(0xFF222222) else Color.White)
    }
}

@Composable
internal fun MatchCard(m: MatchEntity, modifier: Modifier, highlight: Boolean, void: Boolean = false, live: Boolean = false, onClick: () -> Unit) {
    val cup = Fixtures.isCup(m)
    val tint = when (m.result) {
        "V" -> Color(0xFF12351F)
        "E" -> Color(0xFF3A3210)
        "D" -> Color(0xFF3A1616)
        else -> C.SURFACE2
    }
    val score = if (m.scoreMine != null && m.scoreOpp != null) "${m.scoreMine}-${m.scoreOpp}" else "–"
    val place = when (m.home) {
        true -> "🏠"
        false -> "✈"
        null -> ""
    }
    val shape = RoundedCornerShape(12.dp)
    val frame = if (live) modifier.border(2.dp, C.OK, shape) else if (highlight) modifier.border(2.dp, C.GOLD, shape) else if (cup && !void) modifier.border(1.dp, Color(0x669B6BFF), shape) else modifier
    Column(
        frame.clickable { onClick() }.height(140.dp).clip(shape).background(tint).graphicsLayer { alpha = if (void) 0.4f else 1f }.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text((if (cup) "🏆" else place) + " " + (if (m.round != null) "J${m.round}" else m.label.take(8)), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = C.MUTED)
            // selo no topo (antes ficava embaixo e era cortado pelo card)
            val tag = when {
                m.result != null -> null
                void -> Pair("ELIMINADO", C.BAD)
                live -> Pair("AGORA", C.OK)
                highlight -> Pair("PRÓXIMO", C.GOLD)
                cup -> Pair("COPA", Color(0xFFB79CFF))
                else -> null
            }
            if (tag != null) {
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(tag.second.copy(alpha = 0.2f))
                        .border(1.dp, tag.second.copy(alpha = 0.7f), RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 2.dp)
                ) { Text(tag.first, fontSize = 11.sp, color = tag.second, fontWeight = FontWeight.ExtraBold, maxLines = 1) }
            } else ResultBadge(m.result)
        }
        val opp = Fixtures.opponent(m)
        Row(verticalAlignment = Alignment.CenterVertically) {
            // escudo do rival guardado do calendário do OSM (só aparece se já foi recortado)
            if (opp != null) TeamCrest(opp, 30.dp)
            Text(score, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        }
        val nick = m.opponentNick?.takeIf { Evidence.plausibleNick(it) }
        Text(
            opp ?: Fixtures.stage(m) ?: (if (m.opponent != null) "a definir" else NI),
            fontSize = 12.5.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold
        )
        // Humano (apelido sob o time no card) ou CPU (card lido sem apelido).
        if (opp != null) {
            Text(
                if (nick != null) "👤 $nick" else "🖥 CPU",
                fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (nick != null) Color(0xFF7FD8A0) else C.MUTED
            )
        }
        Text(m.date ?: m.time ?: "", fontSize = 11.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
internal fun UnreadCard(round: Int, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clickable { onClick() }.height(128.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, C.SURFACE2, RoundedCornerShape(12.dp)).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("J$round", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = C.MUTED)
        Text("não lido", fontSize = 12.5.sp, color = C.MUTED)
        Text("role o jogo ou toque para preencher", fontSize = 11.sp, color = C.MUTED, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(end = 6.dp).clip(RoundedCornerShape(50))
            .background(if (selected) C.PRIMARY else C.SURFACE2).clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Color.White else C.MUTED) }
}

/** Resumo dos últimos jogos do seu time: bolinhas V/E/D, aproveitamento e gols. */
@Composable
internal fun ResultsStrip(d: SlotData) {
    val done = d.matches.filter { it.result != null }.sortedByDescending { it.round ?: 0 }.take(5)
    if (done.isEmpty()) return
    val v = done.count { it.result == "V" }; val e = done.count { it.result == "E" }; val l = done.count { it.result == "D" }
    val pts = v * 3 + e
    val gf = done.sumOf { it.scoreMine ?: 0 }; val ga = done.sumOf { it.scoreOpp ?: 0 }
    val pct = pts * 100 / (done.size * 3)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).osmPanel().padding(16.dp)) {
        Text("📊 Seus últimos ${done.size} jogos", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in done.reversed()) {
                    val col = when (m.result) { "V" -> C.WIN; "D" -> C.LOSS; else -> C.DRAW }
                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(50)).background(col), contentAlignment = Alignment.Center) {
                        Text(m.result ?: "", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0B1C3E))
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("$pct%", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = if (pct >= 60) C.OK else if (pct >= 40) C.GOLD else C.BAD)
                Text("aproveitamento", fontSize = 12.sp, color = C.MUTED)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("🏆", "${v}V ${e}E ${l}D", "campanha", C.PRIMARY, Modifier.weight(1f))
            StatTile("⭐", "$pts de ${done.size * 3}", "pontos", C.GOLD, Modifier.weight(1f))
            StatTile("⚽", "$gf – $ga", "gols (pró – contra)", if (gf >= ga) C.OK else C.BAD, Modifier.weight(1f))
        }
    }
}

@Composable
internal fun SlotCalendar(slot: Int, d: SlotData) {
    var mode by remember { mutableIntStateOf(0) }
    var editRound by remember { mutableStateOf<Int?>(null) }
    if (d.matches.isEmpty() && fv(d, K.ROUND_TOTAL).toIntOrNull() == null) {
        EmptyState("📅", "Calendário ainda não lido", "No jogo: menu → Calendário do SEU time (no topo da lista) e role até o fim. Ou informe uma rodada à mão no botão abaixo.")
        OutlinedButton(onClick = { editRound = fv(d, K.ROUND).toIntOrNull() ?: 1 }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("＋ Informar uma rodada")
        }
        val e0 = editRound
        if (e0 != null) MatchDialog(slot, e0, null) { editRound = null }
        return
    }
    ResultsStrip(d)
    val sorted = d.matches.sortedBy { it.round ?: 999 }
    // o total do hub pode contar só a liga (34) enquanto o calendário traz também as copas (43): vale o maior
    val total = fv(d, K.ROUND_TOTAL).toIntOrNull()?.let { t -> maxOf(t, sorted.maxOfOrNull { it.round ?: 0 } ?: 0) }
    val read = sorted.count { it.round != null }
    val byRound = sorted.filter { it.round != null }.associateBy { it.round!! }
    val unread = if (total != null) (1..total).filter { it !in byRound } else emptyList()
    if (total != null && total > 0) {
        Panel {
            Text("Calendário lido: $read de $total rodadas", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Bar(read * 100 / total)
            if (unread.isNotEmpty()) {
                Text("faltam J${unread.first()}–J${unread.last()} • role o calendário no OSM", fontSize = 12.5.sp, color = C.WARN, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    val form = sorted.filter { it.result != null && it.round != null }.takeLast(10).mapNotNull { it.result }
    if (form.isNotEmpty()) FormGuide(form)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.padding(bottom = 8.dp)) {
        CountPill("V ${sorted.count { it.result == "V" }}", C.WIN)
        CountPill("E ${sorted.count { it.result == "E" }}", C.DRAW)
        CountPill("D ${sorted.count { it.result == "D" }}", C.LOSS)
        CountPill("${sorted.count { it.result == null && !Fixtures.void(it, sorted) }} a jogar", C.SURFACE2)
    }
    val outRound = Fixtures.cupOutRound(sorted)
    if (outRound != null) {
        Text("🏆 Eliminado da copa na J$outRound — os jogos de copa seguintes não contam.", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(bottom = 6.dp))
    }
    Row(Modifier.padding(bottom = 8.dp)) {
        FilterPill("Todos", mode == 0) { mode = 0 }
        FilterPill("Resultados", mode == 1) { mode = 1 }
        FilterPill("Próximos", mode == 2) { mode = 2 }
    }
    // Próximo de verdade: ignora copa após eliminação e cards cujo horário já passou.
    val nowC = System.currentTimeMillis()
    val curRound = fv(d, K.ROUND).toIntOrNull()
    val curAt = fv(d, K.MATCH_AT).toLongOrNull()
    val nextRound = Fixtures.next(sorted, nowC, curRound, curAt)?.round
    // jogo da rodada atual que começou há pouco e ainda não tem placar: "AGORA" (o próximo continua marcado)
    val liveRound = curRound?.takeIf { r ->
        curAt != null && curAt <= nowC && nowC - curAt < 3 * 3600000L && sorted.any { it.round == r && it.result == null && it.scoreMine == null }
    }
    // Lista final: rodadas da liga em ordem (com "não lido" nos buracos), depois jogos de copa/outros.
    val cells = ArrayList<Pair<Int?, MatchEntity?>>()
    if (total != null && total > 0) {
        for (r in 1..total) cells.add(Pair(r, byRound[r]))
    } else {
        for (m in sorted.filter { it.round != null }) cells.add(Pair(m.round, m))
    }
    for (m in sorted.filter { it.round == null }) cells.add(Pair(null, m))
    val shown = cells.filter { (_, m) ->
        when (mode) {
            1 -> m != null && m.result != null
            2 -> m == null || (m.result == null && !Fixtures.void(m, sorted))
            else -> true
        }
    }
    for (row in shown.chunked(3)) {
        Row(Modifier.fillMaxWidth()) {
            for ((r, m) in row) {
                val mod = Modifier.weight(1f).padding(3.dp)
                if (m != null) MatchCard(m, mod, m.round != null && m.round == nextRound, Fixtures.void(m, sorted), live = m.round != null && m.round == liveRound) { if (m.round != null) editRound = m.round }
                else UnreadCard(r ?: 0, mod) { if (r != null) editRound = r }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    val er = editRound
    if (er != null) MatchDialog(slot, er, byRound[er]) { editRound = null }
}

