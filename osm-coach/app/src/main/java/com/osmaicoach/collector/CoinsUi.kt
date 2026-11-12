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

internal data class CoinsData(
    val balance: Pair<Int, Long>? = null, val web: Coins.Web? = null, val slots: List<Coins.SlotInfo> = emptyList(), val ready: Boolean = false
)

/** Coins (dentro da aba Hoje, lá embaixo): saldo e reserva de 100, como ganhar rápido (seu jogo + web do dia) e onde usar para evoluir o time. */
@Composable
internal fun CoinsContent() {
    val ctx = LocalContext.current
    val tick = rememberTick(30000L)
    val d = rememberLoaded(CoinsData(), tick, UiBus.version) {
        val repo = Repo(ctx)
        val infos = (1..4).mapNotNull { s ->
            val f = repo.fieldMap(s)
            if (f[K.TEAM]?.value == null) return@mapNotNull null
            val my = f[K.MY_STRENGTH]?.value?.toIntOrNull(); val rv = f[K.RIVAL_STRENGTH]?.value?.toIntOrNull()
            Coins.SlotInfo(s, f[K.TEAM]?.value.orEmpty(), if (my != null && rv != null) my - rv else null, SlotRules.load(repo, s)?.text)
        }
        CoinsData(Coins.balance(repo), Coins.web(repo), infos, true)
    }
    LaunchedEffect(Unit) { Coins.refreshIfStale(ctx) }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val fmt = remember { java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()) }
    Column(Modifier.fillMaxWidth()) {
        val bal = d.balance
        Panel {
            Text("Saldo de Boss Coins", fontSize = 13.sp, color = C.MUTED)
            Text(bal?.first?.toString() ?: "—", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = C.GOLD)
            Text(
                if (bal == null) "Ainda não li o saldo: ele aparece no topo de qualquer tela do OSM com o app lendo."
                else "lido ${ago(bal.second)} • reserva intocável: ${Coins.RESERVE} • pode gastar: ${maxOf(0, bal.first - Coins.RESERVE)}",
                fontSize = 13.sp, color = C.MUTED
            )
        }
        Title("⚡ Ganhar rápido (existe no seu jogo)")
        for (w in Coins.GAME_WAYS) CoinsWay(w)
        Title("🌐 Da web (atualizado todo dia)")
        val web = d.web
        Text(
            if (web == null) "Ainda sem pesquisa. Precisa de uma chave de IA em Ajustes; a pesquisa roda 1× por dia sozinha."
            else "Pesquisado em ${fmt.format(java.util.Date(web.at))}. Vem de sites e vídeos da comunidade: NÃO confirmado pelo app" + (if (web.how.isNotBlank()) " • ${web.how}" else ""),
            fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(bottom = 6.dp)
        )
        web?.ways?.forEach { CoinsWay(it) }
        web?.tips?.takeIf { it.isNotEmpty() }?.let { tips ->
            Panel {
                Text("Como a comunidade usa os coins", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                for (t in tips) Text("• $t", fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(top = 4.dp))
            }
        }
        OutlinedButton(onClick = { scope.launch { msg = withContext(Dispatchers.IO) { Coins.refresh(ctx) }; UiBus.version++ } }, enabled = !Coins.running, modifier = Modifier.padding(top = 8.dp)) {
            Text(if (Coins.running) "🔎 Pesquisando…" else "🌐 Atualizar agora")
        }
        msg?.let { Text(it, fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp)) }
        Title("🛠 Como usar para melhorar o time")
        Panel {
            for (t in Coins.spendPlan(bal?.first, d.slots)) Text("• $t", fontSize = 13.5.sp, color = Color.White, modifier = Modifier.padding(bottom = 6.dp))
        }
        Title("⚠ Cuidado")
        Panel { for (w in Coins.WARNINGS) Text("• $w", fontSize = 13.sp, color = C.WARN, modifier = Modifier.padding(bottom = 4.dp)) }
        Text("Estas dicas de gasto só aparecem aqui: na bolha e nas outras abas o app continua sem sugerir nada que gaste Boss Coins (a não ser que você ligue em Ajustes).", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
internal fun CoinsWay(w: Coins.Way) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).osmPanel().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(w.icon, fontSize = 20.sp)
            Text(w.title, fontWeight = FontWeight.Bold, fontSize = 14.5.sp, color = Color.White, modifier = Modifier.padding(start = 8.dp).weight(1f))
            Text("⚡".repeat(w.speed), fontSize = 12.sp)
        }
        Text(w.how, fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(top = 4.dp))
        Text((if (w.verified) "✔ " else "? ") + w.source, fontSize = 11.5.sp, color = if (w.verified) C.OK else C.MUTED, modifier = Modifier.padding(top = 4.dp))
    }
}

