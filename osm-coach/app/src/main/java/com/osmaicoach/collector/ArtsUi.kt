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


internal data class ArtsData(val items: List<Arts.Item> = emptyList(), val cands: Map<Int, List<Arts.Cand>> = emptyMap(), val teams: Map<Int, String> = emptyMap())

/** Aba Artes: galeria das imagens dos 4 slots (baixar, compartilhar, apagar) e criação à mão de cada evento (contratação, despedida, goleada, campanha). */
@Composable
internal fun ArtsTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tick = rememberTick(5000L)
    val d = rememberLoaded(ArtsData(), tick, UiBus.version) {
        val repo = Repo(ctx)
        val teams = HashMap<Int, String>()
        val cands = HashMap<Int, List<Arts.Cand>>()
        for (s in 1..4) {
            val t = repo.fieldMap(s)[K.TEAM]?.value ?: continue
            teams[s] = t
            cands[s] = Arts.candidates(repo, s)
        }
        ArtsData(Arts.items(repo), cands, teams)
    }
    LaunchedEffect(Unit) { Arts.clearUnseen(ctx) }
    var preview by remember { mutableStateOf<Arts.Item?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 96.dp)) {
        PageTitle("🎨", "Artes", "${d.items.size} imagens")
        Text(
            "Imagens de divulgação dos seus times, desenhadas pelo app com o que ele já leu do jogo (sem foto de jogador, sem IA, sem custo). " +
                "Depois de Encerrar a leitura, o app cria sozinho as de contratação, despedida (venda confirmada), goleada/sequência e fim de temporada.",
            fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(bottom = 8.dp)
        )
        CtaButton(if (busy) "Criando…" else "🎨 Criar artes novas agora", Modifier.fillMaxWidth(), !busy) {
            busy = true
            scope.launch {
                val n = withContext(Dispatchers.IO) { runCatching { Arts.auto(ctx) }.getOrDefault(0) }
                msg = if (n > 0) "Criei $n arte(s) nova(s)." else "Nada novo para criar. Use \"Criar uma arte\" abaixo para escolher um evento."
                busy = false
            }
        }
        msg?.let { Text(it, fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 6.dp)) }
        Title("Suas artes")
        if (d.items.isEmpty()) EmptyState("🎨", "Nenhuma arte ainda", "Quando o app confirmar uma contratação, venda, goleada ou o fim da temporada, a arte aparece aqui. Ou crie uma abaixo.")
        for (row in d.items.chunked(2)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (it in row) ArtTile(it, Modifier.weight(1f)) { preview = it }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Title("Criar uma arte")
        if (d.teams.isEmpty()) Text("Nenhum slot com time lido ainda.", fontSize = 13.sp, color = C.MUTED)
        for ((slot, team) in d.teams.toSortedMap()) {
            Panel {
                Text("S$slot • $team", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                val cs = d.cands[slot].orEmpty()
                if (cs.isEmpty()) Text("Sem eventos ainda.", fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
                for (c in cs) {
                    val done = d.items.any { it.id == c.id }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.title, fontSize = 13.5.sp, modifier = Modifier.weight(1f), maxLines = 2)
                        OutlinedButton(
                            onClick = { scope.launch { withContext(Dispatchers.IO) { runCatching { Arts.make(ctx, c) } } } },
                            enabled = !done, modifier = Modifier.height(36.dp)
                        ) { Text(if (done) "✓ feita" else "Criar", fontSize = 12.5.sp) }
                    }
                }
            }
        }
        Text("As artes ficam só no aparelho. \"Baixar\" salva na galeria (Pictures/OSM Coach); \"Compartilhar\" abre o menu do Android.", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 10.dp))
    }
    preview?.let { ArtPreview(it) { preview = null } }
}

@Composable
private fun artBitmap(path: String, sample: Int): androidx.compose.ui.graphics.ImageBitmap? =
    remember(path, sample) { runCatching { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap() }.getOrNull() }

@Composable
private fun ArtTile(it: Arts.Item, modifier: Modifier, onClick: () -> Unit) {
    val bmp = artBitmap(it.file, 4)
    Column(modifier.osmPanel().clickable(onClick = onClick).padding(6.dp)) {
        if (bmp != null) Image(bmp, contentDescription = it.title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)))
        Text(it.title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
        Text("S${it.slot} • ${fmtTime(it.at)}", fontSize = 11.sp, color = C.MUTED)
    }
}

@Composable
private fun ArtPreview(item: Arts.Item, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val bmp = artBitmap(item.file, 2)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDone,
        containerColor = C.SURFACE,
        title = { Text(item.title, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (bmp != null) Image(bmp, contentDescription = item.title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val u = withContext(Dispatchers.IO) { runCatching { Arts.saveToGallery(ctx, item) }.getOrNull() }
                            android.widget.Toast.makeText(ctx, if (u != null) "Salva na galeria (Pictures/OSM Coach)." else "Não consegui salvar.", android.widget.Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                ) { Text("💾 Baixar na galeria") }
                OutlinedButton(
                    onClick = { scope.launch { withContext(Dispatchers.IO) { runCatching { Arts.share(ctx, item) } } } },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) { Text("📤 Compartilhar") }
                OutlinedButton(
                    onClick = { scope.launch { withContext(Dispatchers.IO) { Arts.delete(Repo(ctx), item); UiBus.version++ }; onDone() } },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = C.BAD)
                ) { Text("🗑 Apagar") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDone) { Text("Fechar") } }
    )
}
