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

/** Treinos e estádio de um slot, recolhido numa linha (abre com toque). Os relógios contam segundo a segundo. */
@Composable
internal fun WorkFold(w: SlotWork, startOpenForTest: Boolean = false, onOpen: () -> Unit) {
    val tick = rememberTick(1000L)
    val now = System.currentTimeMillis() + tick * 0L
    val need = w.needsAction(now)
    val next = w.nextEnd(now)
    val badge = when {
        need > 0 -> "⚠ $need livre" + (if (need > 1) "s" else "")
        next != null -> "⏱ " + Timers.clock(next, now)
        else -> null
    }
    Fold("S${w.slot}", w.team, badge, startOpen = startOpenForTest) {
        Text("TREINADORES", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = C.MUTED, letterSpacing = 1.sp)
        if (w.seats.isEmpty()) {
            Text("Passe pela tela de Treinamento deste slot para ver os treinadores.", fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(vertical = 4.dp))
        }
        for (s in w.seats) {
            val ended = s.state == "done" || (s.endAt != null && s.endAt <= now)
            WorkRow(
                icon = when (s.role) { "UNI" -> "⭐"; "ATA" -> "⚽"; "MEI" -> "🎯"; "DEF" -> "🛡"; else -> "🧤" },
                title = s.name ?: "Ninguém treinando",
                sub = Timers.roleLabel(s.role).replaceFirstChar { it.uppercase() },
                status = when {
                    s.state == "empty" -> Pair("Livre", C.WARN)
                    ended -> Pair("Reclamar", C.OK)
                    s.endAt != null -> Pair("⏱ " + Timers.clock(s.endAt, now), C.PRIMARY)
                    else -> Pair("Treinando", C.PRIMARY)
                }
            )
        }
        Spacer(Modifier.height(10.dp))
        Text("ESTÁDIO", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = C.MUTED, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in w.cards) StadiumCard(c, now, Modifier.weight(1f))
        }
        Text(
            "Abrir o slot ›", color = C.PRIMARY, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 10.dp).clickable { onOpen() }
        )
    }
}

/** Card do estádio como no jogo: desenho, estrelas, barra do nível atual → próximo e o relógio ou o custo. */
@Composable
internal fun StadiumCard(c: WorkCard, now: Long, modifier: Modifier = Modifier) {
    val building = c.endAt != null && c.endAt > now
    val ended = c.endAt != null && c.endAt <= now
    val accent = when {
        building -> C.PRIMARY
        ended -> C.OK
        c.next == "máximo" -> C.GOLD
        else -> Color(0xFF5B7BB5)
    }
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF0E2550))
            .border(1.dp, Brush.verticalGradient(listOf(accent.copy(alpha = 0.9f), accent.copy(alpha = 0.25f))), RoundedCornerShape(14.dp))
    ) {
        Box(Modifier.fillMaxWidth().height(78.dp)) {
            StadiumArt(c.label, building, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x99000000), Color(0x00000000), Color(0x66000000)))))
            Column(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(c.label, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1)
                val lv = c.level ?: 0
                Row {
                    for (i in 1..maxOf(3, lv)) Text("★", fontSize = 13.sp, color = if (i <= lv) Color(0xFFFFD24A) else Color(0x88FFFFFF))
                }
            }
        }
        // barra: nível atual → próximo
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(c.level?.toString() ?: "?", fontSize = 11.5.sp, color = C.MUTED, fontWeight = FontWeight.Bold)
            Box(Modifier.weight(1f).padding(horizontal = 4.dp).height(5.dp).clip(RoundedCornerShape(50)).background(Color(0xFF1B3D7A))) {
                if (building || ended) Box(
                    Modifier.fillMaxHeight().fillMaxWidth(if (ended) 1f else 0.5f)
                        .background(Brush.horizontalGradient(listOf(Color(0xFF3DB2FF), Color(0xFF7FD3FF))))
                )
            }
            Text(c.level?.let { (it + 1).toString() } ?: "?", fontSize = 11.5.sp, color = C.MUTED, fontWeight = FontWeight.Bold)
        }
        Box(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 6.dp).clip(RoundedCornerShape(9.dp))
                .background(accent.copy(alpha = 0.18f)).padding(vertical = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                when {
                    building -> Timers.clock(c.endAt!!, now)
                    ended -> "Concluir"
                    c.next == "máximo" -> "Máximo"
                    c.next != null -> c.next
                    else -> "Não lido"
                },
                fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = if (accent == Color(0xFF5B7BB5)) Color(0xFFCFE0FF) else accent, maxLines = 1
            )
        }
    }
}

@Composable
internal fun WorkRow(icon: String, title: String, sub: String, status: Pair<String, Color>?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(C.SURFACE2),
            contentAlignment = Alignment.Center
        ) { Text(icon, fontSize = 14.sp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 12.5.sp, color = C.MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (status != null) {
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(status.second.copy(alpha = 0.16f))
                    .border(1.dp, status.second.copy(alpha = 0.45f), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 3.dp)
            ) { Text(status.first, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = status.second, maxLines = 1) }
        }
    }
}

@Composable
internal fun TipCard(t: Playbook.Tip, onClick: () -> Unit) {
    val c = when {
        t.priority >= 90 -> C.BAD
        t.priority >= 75 -> C.WARN
        else -> C.PRIMARY
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
            .background(Brush.horizontalGradient(listOf(c.copy(alpha = 0.16f), C.SURFACE2.copy(alpha = 0.5f))))
            .border(1.dp, c.copy(alpha = 0.45f), RoundedCornerShape(14.dp)).clickable { onClick() }.padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            Text(t.icon, fontSize = 17.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(t.why, fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 2.dp))
        }
        Text("›", fontSize = 18.sp, color = C.MUTED)
    }
}

/** Táticas mais prováveis do rival humano, pelo que ele já usou contra você e contra os outros slots. */
@Composable
internal fun RivalPredictCard(v: RivalPredict.View, nick: String?) {
    Panel {
        Text("🔮 O que ${nick ?: "o rival"} deve usar", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
            "Pelas ${v.guesses.sumOf { it.seen }} tática(s) dele já lidas (as recentes pesam mais). A simulação testa a sua tática contra estas.",
            fontSize = 12.5.sp, color = C.MUTED
        )
        for (g in v.guesses.take(3)) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(g.formation + (g.style?.let { " • $it" } ?: ""), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${g.share}%", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = C.GOLD)
            }
            Box(Modifier.fillMaxWidth().padding(top = 3.dp).height(5.dp).clip(RoundedCornerShape(50)).background(C.SURFACE2)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(g.share / 100f).background(Brush.horizontalGradient(listOf(Color(0xFFFFB74D), Color(0xFFFFD24A)))))
            }
        }
        v.switchRate?.let {
            Text(
                "Trocou a tática na última hora em ${v.switches} de ${v.checked} jogos ($it%)" + if (it >= 40) ": releia a análise dele 20 min antes." else ".",
                fontSize = 13.sp, color = if (it >= 40) C.WARN else C.MUTED, modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}


/** Ajustes: base de conhecimento da web (o que foi lido, quando, o que mudou) e botão de atualizar agora. */
@Composable
internal fun KnowledgeFold(scope: kotlinx.coroutines.CoroutineScope) {
    val ctx = LocalContext.current
    val tick = rememberTick(4000L)
    var msg by remember { mutableStateOf("") }
    val st = rememberLoaded<org.json.JSONObject?>(null, tick, msg + UiBus.version) { WebKnow.status(Repo(ctx)) }
    val at = st?.optLong("at") ?: 0L
    val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    Fold("📚", "Base de conhecimento da web", if (at > 0) "lida em ${fmt.format(java.util.Date(at))}" else "ainda não lida") {
        Text("O app lê sozinho, 1× por semana, as páginas de táticas do OSM (osmcoach.com, osmnextlevel.com, osmtacticsapp.com, osmhelper.com), transforma as tabelas em regras e guarda as faixas de pressão/estilo/ritmo por formação. Onde as fontes discordam, vale a que o SEU histórico real sustenta. Nada disso é regra oficial do jogo.", fontSize = 13.sp, color = C.MUTED)
        if (st != null) {
            val pages = st.optJSONArray("pages")
            val n = pages?.length() ?: 0
            var ok = 0; var changed = 0; var withData = 0
            for (i in 0 until n) pages?.optJSONObject(i)?.let { p -> if (p.optInt("http") in 200..299) ok++; if (p.optBoolean("changed")) changed++; if (p.optInt("parsed") > 0) withData++ }
            Text("📄 $ok de $n páginas lidas • $withData com tabela aproveitada • ${st.optInt("rules")} situações • ${st.optInt("bands")} faixas por formação" + if (changed > 0) " • $changed mudaram desde a última leitura" else "", fontSize = 13.5.sp, modifier = Modifier.padding(top = 6.dp))
            val failed = (0 until n).mapNotNull { pages?.optJSONObject(it) }.filter { it.optInt("http") !in 200..299 }
            if (failed.isNotEmpty()) Text("⚠ Sem resposta: " + failed.take(3).joinToString { it.optString("url").substringAfter("//").take(40) } + if (failed.size > 3) " e mais ${failed.size - 3}" else "", fontSize = 12.5.sp, color = C.WARN)
        }
        if (msg.isNotBlank()) Text(msg, fontSize = 13.sp, color = C.PRIMARY, modifier = Modifier.padding(top = 4.dp))
        OutlinedButton(onClick = { msg = ""; scope.launch { msg = withContext(Dispatchers.IO) { WebKnow.refresh(ctx) } } }, enabled = !WebKnow.running, modifier = Modifier.padding(top = 8.dp)) {
            Text(if (WebKnow.running) "📚 Lendo as páginas…" else "📚 Ler as páginas agora")
        }
    }
}


// ---------------------------------------------------------------- Por quê / Lado a lado / Último jogo / Aprendizado

