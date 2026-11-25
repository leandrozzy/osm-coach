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
import java.util.Calendar
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

/** Duração curta "12 min" / "3 h 5 min" (evento atrasado). */
internal fun durShort(ms: Long): String {
    val min = ms / 60000L
    return if (min < 60) "${min} min" else "${min / 60} h ${min % 60} min"
}

/** Eventos da Agenda que o usuário dispensou ("não vale mais"): plano global `agendaskip` (chave slot|título|hora). */
internal object AgendaSkip {
    suspend fun load(ctx: Context): Set<String> = runCatching {
        val a = org.json.JSONArray(Repo(ctx).dao.plan(0, "agendaskip")?.json ?: "[]")
        (0 until a.length()).map { a.optString(it) }.toSet()
    }.getOrDefault(emptySet())

    suspend fun add(ctx: Context, keys: Collection<String>) {
        val repo = Repo(ctx)
        val cur = load(ctx).toMutableSet(); cur.addAll(keys)
        val arr = org.json.JSONArray(); cur.toList().takeLast(400).forEach { arr.put(it) }
        repo.dao.putPlan(PlanEntity(0, "agendaskip", arr.toString(), System.currentTimeMillis()))
        UiBus.bump()
    }
}

/** Linha do tempo de tudo o que tem hora: jogos, fim de treino e obra do estádio, de todos os slots, por dia. */
@Composable
internal fun AgendaTab() {
    val ctx = LocalContext.current
    val tick = rememberTick(1000L)
    val data = rememberLoaded(TodayCache.last, tick / 5, UiBus.version) { loadToday(ctx) }
    val now = System.currentTimeMillis()
    val skip = rememberLoaded(emptySet<String>(), tick / 5, UiBus.version) { AgendaSkip.load(ctx) }
    val scope = rememberCoroutineScope()
    class Ev(val at: Long, val icon: String, val title: String, val sub: String, val slot: Int, val col: Color, val todo: String, val path: String, val kind: Int = 0, val club: String? = null, val rival: String? = null, val home: Boolean? = null, val human: String? = null)
    val evs = ArrayList<Ev>()
    val H = 3600000L
    for (s in data.slots) {
        val at = s.nextAt ?: continue
        // jogo: fica pendente (sem limite de tempo) até o app LER o Resultado e a próxima rodada assumir
        if (!s.ended && s.state == null && true) evs.add(Ev(at, "⚽", "Jogo" + (s.rival?.let { " vs $it" } ?: ""), s.title + (s.human?.let { if (it == "Sim") " • rival humano" else " • rival CPU" } ?: ""), s.slot, C.PRIMARY,
            "O jogo já começou: abra o Resultado para o app aprender com ele.", "S${s.slot}: Central → card do slot → Resultado do jogo (role a tela inteira)", 0, s.title, s.rival, s.home, s.human))
    }
    for (w in data.work) {
        // treino/obra: ficam pendentes (sem limite de tempo) até você passar pela tela e o app registrar a troca (o horário muda)
        for (seat in w.seats) seat.endAt?.let {
            evs.add(Ev(it, "🏋", "Treino ${Timers.roleLabel(seat.role)} termina", seat.name ?: "treinador ocupado", w.slot, C.OK,
                "Terminou: colete o treino e ponha o próximo jogador (treinador parado é evolução perdida).", "S${w.slot}: Menu ☰ → Treino (${Timers.roleLabel(seat.role)})", 1))
        }
        for (c in w.cards) c.endAt?.let {
            evs.add(Ev(it, "🏟", "Obra do estádio termina: ${c.label}", "nível ${c.level ?: "?"}", w.slot, C.GOLD,
                "A obra terminou: colete e comece a próxima melhoria.", "S${w.slot}: Menu ☰ → Estádio (${c.label})", 2))
        }
    }
    val sorted = evs.filter { "${it.slot}|${it.title}|${it.at}" !in skip }.sortedBy { it.at }
    val overdue = sorted.filter { it.at < now }
    val upcoming = sorted.filter { it.at >= now }
    val dayFmt = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt", "BR"))
    val hourFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    fun dayKey(ms: Long) = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ms))
    fun open(e: Ev) {
        // abre o OSM e mostra o caminho por cima do jogo (o app não toca nos botões do OSM por você)
        CaptureService.instance?.notice("⏰ " + e.path)
        if (!openOsm(ctx)) android.widget.Toast.makeText(ctx, "Instale/abra o OSM. " + e.path, android.widget.Toast.LENGTH_LONG).show()
        else android.widget.Toast.makeText(ctx, e.path, android.widget.Toast.LENGTH_LONG).show()
    }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var pickedDay by rememberSaveable { mutableStateOf<String?>(null) }
    fun kindColor(k: Int) = when (k) { 0 -> C.PRIMARY; 1 -> C.INFO; else -> C.GOLD }
    fun pass(e: Ev) = filter == 0 || (filter == 1 && e.kind == 0) || (filter == 2 && e.kind == 1) || (filter == 3 && e.kind == 2)
    @Composable
    fun EventCard(e: Ev, late: Boolean) {
        val shape = RoundedCornerShape(DS.RADIUS_CARD)
        val edge = if (late) C.WARN else if (e.kind == 0) C.PRIMARY.copy(alpha = 0.45f) else C.BORDER
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(shape).background(if (late) C.WARN.copy(alpha = 0.08f) else DS.SURFACE).border(1.dp, edge, shape).clickable { open(e) }.padding(14.dp)) {
            if (e.kind == 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DsChip("S${e.slot}", C.GOLD)
                    Spacer(Modifier.width(6.dp))
                    DsChip("Jogo", C.PRIMARY)
                    Spacer(Modifier.weight(1f))
                    e.home?.let { DsChip(if (it) "casa" else "fora") }
                }
                val mineLeft = e.home != false
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val mine: @Composable (Modifier) -> Unit = { m -> Column(m, horizontalAlignment = Alignment.CenterHorizontally) { Crest(e.slot, 46.dp, e.club); Text(e.club ?: "Seu time", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) } }
                    val riv: @Composable (Modifier) -> Unit = { m -> Column(m, horizontalAlignment = Alignment.CenterHorizontally) { RivalShield(e.rival, 46.dp, e.slot); Text(e.rival ?: "rival não lido", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp), color = if (e.rival != null) C.TEXT else C.WARN) } }
                    if (mineLeft) mine(Modifier.weight(1f)) else riv(Modifier.weight(1f))
                    Text("×", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = C.MUTED, modifier = Modifier.padding(horizontal = 6.dp))
                    if (mineLeft) riv(Modifier.weight(1f)) else mine(Modifier.weight(1f))
                }
                e.human?.let { Text(if (it == "Sim") "👤 rival humano" else "🖥 rival CPU", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp)) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(e.col.copy(alpha = 0.16f)).border(1.dp, e.col.copy(alpha = 0.5f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Text(e.icon, fontSize = 20.sp) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(e.sub, fontSize = 13.sp, color = C.MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DsChip("S${e.slot}", C.GOLD)
                }
            }
            if (late) {
                Text("👉 ${e.todo}", fontSize = 13.sp, color = C.WARN, modifier = Modifier.padding(top = 10.dp))
                Text("Toque para abrir o OSM • ${e.path.substringAfter(": ")}", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 2.dp))
                TextButton(onClick = { scope.launch { withContext(kotlinx.coroutines.Dispatchers.IO) { AgendaSkip.add(ctx, listOf("${e.slot}|${e.title}|${e.at}")) } } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("✕ Não vale mais (dispensar)", fontSize = 13.sp, color = C.MUTED) }
            }
        }
    }
    @Composable
    fun TimeRow(e: Ev, late: Boolean) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(60.dp).padding(top = 18.dp)) {
                Text(hourFmt.format(Date(e.at)), fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = if (late) C.WARN else kindColor(e.kind))
                Text(if (late) "há ${durShort(now - e.at)}" else countdownShort(e.at), fontSize = 12.sp, color = if (late) C.WARN else C.MUTED, maxLines = 1)
            }
            Box(Modifier.padding(top = 22.dp, end = 8.dp).size(10.dp).clip(RoundedCornerShape(50)).background(if (late) C.WARN else kindColor(e.kind)))
            Box(Modifier.weight(1f)) { EventCard(e, late) }
        }
    }
    // faixa dos próximos 7 dias, com um ponto por evento (cor do tipo) e âmbar para o que passou do horário (hoje)
    val dayCells = (0..6).map { i ->
        val c = Calendar.getInstance().apply { timeInMillis = now + i * 86400000L }
        val k = dayKey(c.timeInMillis)
        val dots = ArrayList<Color>()
        if (i == 0 && overdue.isNotEmpty()) dots.add(C.WARN)
        for (e in upcoming) if (dayKey(e.at) == k) dots.add(kindColor(e.kind))
        DayCell(k, ptDow(c).uppercase(), c.get(Calendar.DAY_OF_MONTH), dots)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 96.dp)) {
        HeroHeader("📅", "Agenda", "Jogos, treinos e obras por dia", "${sorted.size} eventos")
        DayStrip(dayCells, pickedDay) { pickedDay = it }
        DsFilters(listOf("Tudo", "Jogos", "Treinos", "Obras"), filter) { filter = it }
        Text("Toque num evento para abrir o OSM com o caminho. O que passou do horário fica no topo, como pendente, até o app ler a tela correspondente (Resultado, Treino, Estádio).", fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(bottom = 6.dp))
        val ov = overdue.filter { pass(it) }
        val up = upcoming.filter { pass(it) && (pickedDay == null || dayKey(it.at) == pickedDay) }
        if (sorted.isEmpty()) {
            EmptyState("📅", "Nada agendado ainda", "Passe pelo Calendário, pelo Treinamento e pelo Estádio no OSM com a captura ligada: jogos, fim de treino e de obra aparecem aqui.")
        }
        if (ov.isNotEmpty() && pickedDay == null) {
            val shape = RoundedCornerShape(DS.RADIUS_CARD + 4.dp)
            Column(Modifier.fillMaxWidth().padding(top = 4.dp).clip(shape).background(C.WARN.copy(alpha = 0.08f)).border(1.dp, C.WARN.copy(alpha = 0.6f), shape).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(C.WARN.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) { Text("⏰", fontSize = 19.sp) }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("${ov.size} passaram do horário", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Faça agora: o app só registra quando você abre a tela", fontSize = 12.5.sp, color = C.MUTED)
                    }
                }
                for (e in ov) EventCard(e, true)
                val bySlot = ov.groupBy { it.slot }
                for ((sl, l) in bySlot) if (l.size >= 2) TextButton(onClick = { scope.launch { withContext(kotlinx.coroutines.Dispatchers.IO) { AgendaSkip.add(ctx, l.map { "${it.slot}|${it.title}|${it.at}" }) } } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("✕ Dispensar os ${l.size} pendentes do S$sl", fontSize = 13.sp, color = C.MUTED) }
            }
            NowLine(hourFmt.format(Date(now)))
        }
        var lastDay = ""
        for (e in up) {
            val dk = dayKey(e.at)
            if (dk != lastDay) {
                lastDay = dk
                val today = dayKey(now); val tomorrow = dayKey(now + 86400000L)
                val label = when (dk) { today -> "Hoje"; tomorrow -> "Amanhã"; else -> dayFmt.format(Date(e.at)).replaceFirstChar { it.uppercase() } }
                DsSection(label)
            }
            TimeRow(e, false)
        }
        if (pickedDay != null && up.isEmpty()) Text("Nada neste dia. Toque de novo no dia para ver tudo.", fontSize = 13.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 12.dp))
    }
}
