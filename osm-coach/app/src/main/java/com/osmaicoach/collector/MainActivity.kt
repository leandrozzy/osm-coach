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
import androidx.compose.material.icons.outlined.Create
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

// Cores do OSM: azul-marinho de estádio à noite, azul vivo dos botões e o amarelo dos destaques.
internal object C {
    val BG = Color(0xFF081A3C)
    val SURFACE = Color(0xFF0F2347)
    val SURFACE2 = Color(0xFF16325F)
    val PRIMARY = Color(0xFF2AB4FF)
    val GOLD = Color(0xFFFFC21A)
    val OK = Color(0xFF3DDC84)
    val BAD = Color(0xFFFF5C5C)
    val WARN = Color(0xFFFFB74D)
    val MUTED = Color(0xFF9FB4D3)
    val WIN = Color(0xFF2EA043)
    val DRAW = Color(0xFFF2C94C)
    val LOSS = Color(0xFFE5484D)
}

class MainActivity : ComponentActivity() {
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        AppScope.scope.launch(Dispatchers.IO) { Notifier.reschedule(applicationContext) }
    }

    private fun handleIntent(i: Intent?) {
        val slot = i?.getIntExtra("slot", 0) ?: 0
        if (slot in 1..4) {
            Route.tab = i?.getIntExtra("tab", 4) ?: 4
            Route.slot = slot
            Route.nonce++
        }
    }

    override fun onResume() {
        runCatching { WebKnow.refreshIfStale(applicationContext) }
        super.onResume()
        AppVisible.resumed = true
        CaptureService.instance?.refreshOverlay()
    }

    override fun onPause() {
        AppVisible.resumed = false
        CaptureService.instance?.refreshOverlay()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Health.install(applicationContext)
        Diag.init(applicationContext)
        Settings.migrate(applicationContext)
        AppScope.scope.launch { Repo(applicationContext).purgeLegacy() }
        Notifier.ensureChannels(applicationContext)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        AppScope.scope.launch(Dispatchers.IO) { Notifier.reschedule(applicationContext) }
        KeepAliveService.start(applicationContext)
        // Ao recriar a tela (girar o celular) o intent antigo continua aqui: não reabre o slot da notificação.
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val font = remember { osmFontFamily() }
            val base = androidx.compose.material3.Typography()
            fun f(t: androidx.compose.ui.text.TextStyle) = t.copy(fontFamily = font)
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = C.PRIMARY, secondary = C.GOLD, background = C.BG, surface = C.SURFACE,
                    onSurface = Color.White, onBackground = Color.White, surfaceVariant = C.SURFACE2,
                    onPrimary = Color.White, secondaryContainer = C.SURFACE2,
                    onSecondary = Color(0xFF0B1C3E), onSecondaryContainer = Color.White, onSurfaceVariant = Color(0xFFC9D8F0),
                    primaryContainer = C.SURFACE2, onPrimaryContainer = Color.White, outline = Color(0xFF3F6AA8)
                ),
                typography = base.copy(
                    displayLarge = f(base.displayLarge), displayMedium = f(base.displayMedium), displaySmall = f(base.displaySmall),
                    headlineLarge = f(base.headlineLarge), headlineMedium = f(base.headlineMedium), headlineSmall = f(base.headlineSmall),
                    titleLarge = f(base.titleLarge), titleMedium = f(base.titleMedium), titleSmall = f(base.titleSmall),
                    bodyLarge = f(base.bodyLarge), bodyMedium = f(base.bodyMedium), bodySmall = f(base.bodySmall),
                    labelLarge = f(base.labelLarge), labelMedium = f(base.labelMedium), labelSmall = f(base.labelSmall)
                )
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalTextStyle provides f(base.bodyLarge)
                ) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.material3.LocalContentColor provides Color.White
                    ) {
                        OsmAppBackground(Modifier.fillMaxSize()) { App() }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ utilidades

internal fun serviceEnabled(ctx: Context): Boolean {
    val s = android.provider.Settings.Secure.getString(
        ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val id = ComponentName(ctx, CaptureService::class.java).flattenToString()
    return s.split(':').any { it.equals(id, ignoreCase = true) }
}

internal fun batteryUnrestricted(ctx: Context): Boolean {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

internal fun openOsm(ctx: Context): Boolean {
    val i = ctx.packageManager.getLaunchIntentForPackage(OSM_PACKAGE) ?: return false
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(i)
    return true
}

/**
 * Bateria sem restrição: pede direto ao Android ("Permitir que o app rode em segundo plano?") para ESTE app.
 * A lista geral de otimização esconde o app em muitos celulares. Se o pedido não abrir, vai para Info do app
 * (lá: Bateria → Sem restrição).
 */
@android.annotation.SuppressLint("BatteryLife")
internal fun openBatterySettings(ctx: Context) {
    if (batteryUnrestricted(ctx)) {
        openSettings(ctx, android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
        return
    }
    val req = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:" + ctx.packageName))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(req)
    } catch (e: Exception) {
        openSettings(ctx, android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
    }
}

internal fun openSettings(ctx: Context, action: String, pkg: Boolean = false) {
    val i = Intent(action)
    if (pkg) i.data = Uri.parse("package:" + ctx.packageName)
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(i)
    } catch (e: Exception) {
        Diag.lastError = "Não consegui abrir: $action"
    }
}

internal fun ago(ms: Long): String {
    if (ms <= 0L) return "nunca"
    val s = (System.currentTimeMillis() - ms) / 1000
    return when {
        s < 60 -> "há ${s}s"
        s < 3600 -> "há ${s / 60} min"
        s < 86400 -> "há ${s / 3600} h"
        else -> "há ${s / 86400} d"
    }
}

internal fun fmtTime(ms: Long): String = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(ms))

internal fun typeLabel(name: String): String = when (name) {
    "HUB" -> "Central dos slots"
    "PREGAME" -> "Pré-jogo"
    "SQUAD" -> "Elenco"
    "CALENDAR" -> "Calendário"
    "MARKET" -> "Mercado"
    "TRAINING" -> "Treinamento"
    "TACTIC" -> "Tática"
    "REPORT" -> "Relatório"
    "STADIUM" -> "Meu estádio"
    "OTHER_OSM" -> "Outra tela do OSM"
    "NON_OSM" -> "Fora do OSM / anúncio"
    "NOISE" -> "Transição"
    else -> name
}

@Composable
internal fun rememberTick(ms: Long = 1500L): Int {
    var t by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(ms)
            t++
        }
    }
    return t
}

object UiBus {
    var version by mutableIntStateOf(0)
    private var lastBump = 0L
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Chamado pela captura depois de gravar algo (campos, escudos, bandeiras): a tela recarrega sozinha, sem botão de atualizar. No máx. 1× a cada 1,2 s. */
    fun bump() {
        val now = System.currentTimeMillis()
        if (now - lastBump < 1200L) return
        lastBump = now
        main.post { version++ }
    }
}

data class EditRow(val label: String, val key: String?, val display: String? = null)

@Composable
internal fun ManualDialog(slot: Int, spec: ManualFields.Spec, current: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(if (current == NI) "" else current) }
    var error by remember { mutableStateOf<String?>(null) }
    val opts = spec.options
    val buttonsOnly = opts != null && opts.size <= 6
    fun save(raw: String) {
        val n = ManualFields.normalize(spec.key, raw)
        if (n == null) {
            error = "Valor inválido. Exemplo: ${spec.example}"
            return
        }
        AppScope.scope.launch(Dispatchers.IO) {
            Repo(ctx).setManual(slot, spec.key, n)
            UiBus.version++
        }
        onDone()
    }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = C.SURFACE,
        title = { Text(spec.label, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                if (spec.hint.isNotBlank()) Text("Onde ver no jogo: ${spec.hint}", fontSize = 13.sp, color = C.MUTED)
                if (buttonsOnly && opts != null) {
                    for (o in opts) {
                        OutlinedButton(onClick = { save(o) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text(o) }
                    }
                } else {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it; error = null }, singleLine = true,
                        label = { Text("Valor (ex.: ${spec.example})") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    if (opts != null) Text("Formações: " + opts.joinToString(", "), fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
                }
                val e = error
                if (e != null) Text(e, color = C.BAD, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                Text("O que você digita nunca é sobrescrito pela leitura automática.", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { if (!buttonsOnly) TextButton(onClick = { save(text) }) { Text("Salvar") } },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    AppScope.scope.launch(Dispatchers.IO) {
                        Repo(ctx).clearField(slot, spec.key)
                        UiBus.version++
                    }
                    onDone()
                }) { Text("Limpar") }
                TextButton(onClick = onDone) { Text("Cancelar") }
            }
        }
    )
}

@Composable
internal fun EditSection(slot: Int, d: SlotData, title: String, rows: List<EditRow>, icon: String = "✏️", startOpen: Boolean = false) {
    var editing by remember { mutableStateOf<EditRow?>(null) }
    val missing = rows.count { r ->
        r.key != null && (r.display ?: fv(d, r.key)) == NI && !(r.key == K.RIVAL_NICK && fv(d, K.RIVAL_HUMAN) == "Não")
    }
    Fold(icon, title, if (missing > 0) "faltam $missing" else "✔", startOpen) {
        for (r in rows) {
            val raw = r.display ?: (if (r.key != null) fv(d, r.key) else NI)
            val v = when {
                r.key == K.RIVAL_HUMAN && raw == "Sim" -> "Humano"
                r.key == K.RIVAL_HUMAN && raw == "Não" -> "CPU"
                r.key == K.RIVAL_NICK && raw == NI && fv(d, K.RIVAL_HUMAN) == "Não" -> "— (CPU não tem apelido)"
                (d.fields[r.key ?: ""]?.conf ?: 1.0) < 0.6 && raw != NI && r.key?.endsWith(".mid") == true -> "≈$raw"
                else -> raw
            }
            val manual = r.key != null && (d.fields[r.key]?.conf ?: 0.0) >= 1.5
            Row(
                Modifier.fillMaxWidth().clickable(enabled = r.key != null) { editing = r }.padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(r.label, color = C.MUTED, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(v + (if (manual) " ✎" else ""), fontWeight = FontWeight.SemiBold, color = if (v == NI) C.WARN else Color.White)
                if (v == NI && r.key != null) Text("  ＋", color = C.PRIMARY, fontWeight = FontWeight.Bold)
            }
        }
        Text("✎ toque num valor para corrigir", fontSize = 11.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
    }
    val e = editing
    if (e != null && e.key != null) ManualDialog(slot, ManualFields.spec(e.key, e.label), fv(d, e.key)) { editing = null }
}

@Composable
internal fun MatchDialog(slot: Int, round: Int, existing: MatchEntity?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var opp by remember { mutableStateOf(existing?.opponent ?: "") }
    var a by remember { mutableStateOf(existing?.scoreMine?.toString() ?: "") }
    var b by remember { mutableStateOf(existing?.scoreOpp?.toString() ?: "") }
    var home by remember { mutableStateOf(existing?.home) }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = C.SURFACE,
        title = { Text("Rodada $round", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(value = opp, onValueChange = { opp = it }, singleLine = true, label = { Text("Adversário") }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = a, onValueChange = { a = it.filter { c -> c.isDigit() }.take(2) }, singleLine = true, label = { Text("Meus gols") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = b, onValueChange = { b = it.filter { c -> c.isDigit() }.take(2) }, singleLine = true, label = { Text("Gols rival") }, modifier = Modifier.weight(1f))
                }
                Row(Modifier.padding(top = 10.dp)) {
                    FilterPill("🏠 Casa", home == true) { home = true }
                    FilterPill("✈ Fora", home == false) { home = false }
                }
                Text("Deixe os gols vazios se o jogo ainda não foi disputado. O resultado entra no aprendizado da IA.", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ma = a.toIntOrNull()
                val mb = b.toIntOrNull()
                AppScope.scope.launch(Dispatchers.IO) {
                    Repo(ctx).setManualMatch(slot, round, opp, ma, mb, home)
                    UiBus.version++
                }
                onDone()
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancelar") } }
    )
}

@Composable
internal fun ResultDialog(slot: Int, kind: String, title: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = C.SURFACE,
        title = { Text("Resultado do jogo", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(title, fontSize = 13.sp, color = C.MUTED)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = a, onValueChange = { a = it.filter { c -> c.isDigit() }.take(2) }, singleLine = true, label = { Text("Meus gols") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = b, onValueChange = { b = it.filter { c -> c.isDigit() }.take(2) }, singleLine = true, label = { Text("Gols rival") }, modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ma = a.toIntOrNull()
                val mb = b.toIntOrNull()
                if (ma != null && mb != null) {
                    AppScope.scope.launch(Dispatchers.IO) {
                        Director.setLogResult(Repo(ctx), slot, kind, ma, mb)
                        UiBus.version++
                    }
                    onDone()
                }
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancelar") } }
    )
}

@Composable
internal fun <T> rememberLoaded(initial: T, tick: Int, key: Any?, loader: suspend () -> T): T {
    var state by remember { mutableStateOf(initial) }
    val tickNow by rememberUpdatedState(tick)
    val load by rememberUpdatedState(loader)
    // Cada leitura termina antes da próxima começar: o relógio não cancela uma leitura lenta
    // (antes, com leitura > 1 s, a aba Hoje ficava sem os slots).
    LaunchedEffect(key) {
        snapshotFlow { tickNow }.conflate().collect {
            try {
                state = withContext(Dispatchers.IO) { load() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diag.lastError = "Tela: " + (e.message ?: e.javaClass.simpleName)
            }
        }
    }
    return state
}

// ------------------------------------------------------------------ componentes visuais

/** Título pequeno de grupo (AVISOS, PREFERÊNCIAS…): separa o que é de cada assunto numa lista longa. */
@Composable
internal fun SectionLabel(text: String, hint: String? = null) {
    Column(Modifier.padding(top = 18.dp, bottom = 2.dp, start = 4.dp)) {
        Text(text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = C.PRIMARY, letterSpacing = 1.2.sp)
        if (hint != null) Text(hint, fontSize = 12.5.sp, color = C.MUTED)
    }
}

@Composable
internal fun Title(text: String) {
    Row(Modifier.padding(top = 22.dp, bottom = 8.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
internal fun KV(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = C.MUTED, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun Panel(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).osmPanel(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) { Column(Modifier.padding(16.dp)) { content() } }
}

@Composable
internal fun Pill(text: String, ok: Boolean? = null) {
    val c = when (ok) {
        true -> Color(0xFF22C55E)
        false -> Color(0xFFEF4444)
        null -> Color(0xFF5FA8FF)
    }
    Box(
        Modifier.padding(end = 6.dp, top = 2.dp, bottom = 2.dp).clip(RoundedCornerShape(50))
            .background(c.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c)
    }
}

@Composable
internal fun Crest(slot: Int, size: Dp, team: String? = null) {
    val ctx = LocalContext.current
    val ver = UiBus.version
    // o escudo guardado por NOME do time (recorte novo do Pré-jogo/Calendário) vale mais que o recorte antigo do slot
    androidx.compose.runtime.LaunchedEffect(team) { CrestWeb.request(ctx, team) }   // escudo real da web (se achar); senão vale o recorte do jogo
    val f = remember(slot, team, ver) { CrestWeb.find(ctx.filesDir, team) ?: CrestCut.find(ctx.filesDir, team) ?: File(ctx.filesDir, "crests/s$slot.png") }
    val stamp = if (f.exists()) f.lastModified() else 0L
    val raw = remember(stamp, f.path) { if (stamp > 0L) BitmapFactory.decodeFile(f.absolutePath) else null }
    // escudo já recortado na silhueta (fundo transparente): mostra inteiro, sem o retângulo; recorte antigo quadrado segue como antes
    val isCut = raw != null && (f.path.contains("/crests/web/") || (raw.hasAlpha() && (raw.getPixel(0, 0) ushr 24) == 0))
    val bmp = remember(raw) { raw?.asImageBitmap() }
    if (bmp != null && isCut) {
        Image(bmp, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
    } else {   // recorte antigo quadrado (com números/fundo) não é mais mostrado: espera o escudo certo
        Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(C.SURFACE2), contentAlignment = Alignment.Center) {
            Text("S$slot", fontWeight = FontWeight.Bold, color = C.MUTED, fontSize = 18.sp)
        }
    }
}

@Composable
internal fun Bar(pct: Int) {
    val f by androidx.compose.animation.core.animateFloatAsState(pct.coerceIn(0, 100) / 100f, androidx.compose.animation.core.tween(800), label = "bar")
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(Color(0x33000000))) {
        Box(
            Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(Color(0xFF1E6BFF), Color(0xFF3DB2FF), Color(0xFF6EE7FF))))
        )
    }
}

// ------------------------------------------------------------------ desenhos e blocos visuais (menos texto)

/** Explicação recolhida: só um "ⓘ" pequeno; o texto aparece ao tocar. */
@Composable
internal fun Hint(text: String, label: String = "como funciona") {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).clickable { open = !open }.padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(16.dp).clip(RoundedCornerShape(50)).background(C.SURFACE2), contentAlignment = Alignment.Center) {
                Text("i", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = C.PRIMARY)
            }
            Text(" " + label + (if (open) " ▲" else " ▼"), fontSize = 12.5.sp, color = C.MUTED)
        }
        if (open) Text(text, fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
    }
}

/** Painel que abre e fecha pelo título (detalhes ficam guardados até o usuário querer ver). */
@Composable
internal fun Fold(icon: String, title: String, badge: String? = null, startOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).osmPanel(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF2B5BB8), Color(0xFF14306E))))
                        .border(1.dp, Color(0x4072B8FF), RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center
                ) { Text(icon, fontSize = 17.sp) }
                Spacer(Modifier.width(12.dp))
                Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (badge != null) {
                    Box(Modifier.padding(end = 8.dp).clip(RoundedCornerShape(50)).background(C.SURFACE2).padding(horizontal = 8.dp, vertical = 2.dp)) {
                        Text(badge, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = C.GOLD, maxLines = 1)
                    }
                }
                val rot by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, label = "chev")
                Text("▼", fontSize = 13.sp, color = C.MUTED, modifier = Modifier.rotate(rot))
            }
            if (open) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) { content() }
        }
    }
}

/** Número grande com ícone e rótulo curto. */
@Composable
internal fun StatTile(icon: String, value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 18.sp)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, fontSize = 11.5.sp, color = C.MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Anel de progresso com o texto no meio. */
@Composable
internal fun Ring(pct: Int, size: Dp, color: Color, center: String, sub: String? = null) {
    val ringAnim by androidx.compose.animation.core.animateFloatAsState(pct.coerceIn(0, 100) / 100f, androidx.compose.animation.core.tween(900), label = "ring")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val sw = this.size.width * 0.11f
            val arc = Size(this.size.width - sw, this.size.height - sw)
            drawArc(C.SURFACE2, -90f, 360f, false, topLeft = Offset(sw / 2f, sw / 2f), size = arc, style = Stroke(sw))
            drawArc(color.copy(alpha = 0.22f), -90f, 360f * ringAnim, false, topLeft = Offset(sw / 2f, sw / 2f), size = arc, style = Stroke(sw * 2f, cap = StrokeCap.Round))
            drawArc(
                Brush.sweepGradient(listOf(color.copy(alpha = 0.7f), color, Color.White.copy(alpha = 0.9f), color)),
                -90f, 360f * ringAnim, false, topLeft = Offset(sw / 2f, sw / 2f), size = arc, style = Stroke(sw, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(center, fontSize = (size.value * (if (center.length >= 4) 0.2f else 0.24f)).sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1)
            if (sub != null) Text(sub, fontSize = (size.value * 0.11f).coerceAtLeast(7f).sp, color = C.MUTED, maxLines = 1)
        }
    }
}

/** Previsão compacta: vitória em destaque e a barra vitória/empate/derrota com os três números. */
@Composable
internal fun WinMini(p: WinModel.Prob) {
    Column(Modifier.width(116.dp), horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${p.win}%", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Text(" vitória", fontSize = 11.5.sp, color = C.MUTED, modifier = Modifier.padding(bottom = 2.dp))
        }
        Box(Modifier.padding(top = 2.dp)) { VerdictChip(p) }
        Box(Modifier.padding(top = 3.dp)) { WinBar(p, 7.dp) }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("V${p.win}", fontSize = 11.sp, color = C.WIN, fontWeight = FontWeight.Bold)
            Text("E${p.draw}", fontSize = 11.sp, color = C.DRAW, fontWeight = FontWeight.Bold)
            Text("D${p.loss}", fontSize = 11.sp, color = C.LOSS, fontWeight = FontWeight.Bold)
        }
    }
}

internal val SHIELD_COLORS = listOf(
    Color(0xFFE5484D), Color(0xFF9B6BFF), Color(0xFF2EA043), Color(0xFFFF8A3D), Color(0xFF00B4D8),
    Color(0xFFD6409F), Color(0xFF8D6E63), Color(0xFF607D8B)
)

internal fun initials(name: String): String {
    val w = name.split(Regex("[\\s.\\-]+")).filter { it.isNotBlank() && it.first().isLetterOrDigit() }
    return when {
        w.isEmpty() -> "?"
        w.size == 1 -> w[0].take(2).uppercase()
        else -> (w[0].take(1) + w[1].take(1)).uppercase()
    }
}

/** Escudo desenhado para o rival (cor fixa pelo nome, com as iniciais). */
@Composable
internal fun RivalShield(name: String?, size: Dp, slot: Int? = null) {
    val ctx = LocalContext.current
    val ver = UiBus.version
    // escudo REAL do rival (recorte do Pré-jogo), só enquanto for o mesmo rival do recorte
    androidx.compose.runtime.LaunchedEffect(name) { CrestWeb.request(ctx, name) }
    val real = remember(name, ver) {
        if (name == null) null else runCatching {
            // escudo real da web primeiro; senão o arquivo global por nome do time (calendário/pré-jogo/central: o escudo de um clube não muda)
            (CrestWeb.find(ctx.filesDir, name) ?: CrestCut.find(ctx.filesDir, name))?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
        }.getOrNull()
    }
    if (real != null) { Image(real, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size)); return }
    val col = if (name == null) C.SURFACE2 else SHIELD_COLORS[(name.hashCode() and 0x7fffffff) % SHIELD_COLORS.size]
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val p = androidx.compose.ui.graphics.Path()
            p.moveTo(w * 0.5f, h * 0.02f)
            p.lineTo(w * 0.95f, h * 0.16f)
            p.lineTo(w * 0.95f, h * 0.52f)
            p.quadraticBezierTo(w * 0.93f, h * 0.84f, w * 0.5f, h * 0.98f)
            p.quadraticBezierTo(w * 0.07f, h * 0.84f, w * 0.05f, h * 0.52f)
            p.lineTo(w * 0.05f, h * 0.16f)
            p.close()
            drawPath(p, Brush.verticalGradient(listOf(col, col.copy(red = col.red * 0.6f, green = col.green * 0.6f, blue = col.blue * 0.6f))))
            drawPath(p, Color(0x66FFFFFF), style = Stroke(2f * density))
            drawLine(Color(0x33FFFFFF), Offset(w * 0.5f, h * 0.08f), Offset(w * 0.5f, h * 0.9f), 3f * density)
        }
        Text(if (name == null) "?" else initials(name), fontSize = (size.value * 0.3f).sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
    }
}

internal val REF_COLORS = listOf(Color(0xFF2F8CFF), Color(0xFF3DDC84), Color(0xFFFFD43B), Color(0xFFFF9F1A), Color(0xFFE5484D))

/**
 * Termômetro do árbitro igual ao do jogo, 5 cores (azul, verde, amarelo, laranja, vermelho), com o nível lido em
 * destaque e o desarme que ele pede.
 */
@Composable
internal fun RefereeMeter(value: String?, compact: Boolean = false) {
    val lvl = Referee.level(value)
    val name = Referee.norm(value)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🌡", fontSize = if (compact) 13.sp else 16.sp)
            Text(" Árbitro ", fontSize = 13.sp, color = C.MUTED)
            Text(name ?: "não lido", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (lvl == null) C.WARN else REF_COLORS[lvl])
            Spacer(Modifier.weight(1f))
            if (lvl != null) {
                Box(Modifier.clip(RoundedCornerShape(50)).background(REF_COLORS[lvl].copy(alpha = 0.22f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("desarme " + Referee.TACKLES[lvl], fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (i in 0..4) {
                val on = lvl == i
                Box(
                    Modifier.weight(1f).height(if (compact) 10.dp else 14.dp).clip(RoundedCornerShape(50))
                        .background(REF_COLORS[i].copy(alpha = if (lvl == null || on) 1f else 0.22f))
                        .then(if (on) Modifier.border(2.dp, Color.White, RoundedCornerShape(50)) else Modifier)
                )
            }
        }
        if (!compact) Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
            for ((i, t) in Referee.LEVELS.withIndex()) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t.replace("Muito ", "Muito\n"), fontSize = 11.sp, color = if (lvl == i) Color.White else C.MUTED, textAlign = TextAlign.Center, fontWeight = if (lvl == i) FontWeight.Bold else FontWeight.Normal, lineHeight = 10.sp)
                    Text(Referee.TACKLES[i], fontSize = 8.sp, color = if (lvl == i) REF_COLORS[i] else C.MUTED.copy(alpha = 0.7f), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** Duelo de um número meu contra o do rival: barra dividida azul x vermelho. */
@Composable
internal fun Duel(label: String, mine: String?, rival: String?) {
    val a = mine?.let { digits(it).toIntOrNull() }
    val b = rival?.let { digits(it).toIntOrNull() }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(a?.toString() ?: "?", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = if (a != null && b != null && a >= b) C.PRIMARY else Color.White, modifier = Modifier.width(34.dp), textAlign = TextAlign.Start)
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 11.5.sp, color = C.MUTED, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            val fa = if (a != null && b != null && a + b > 0) a.toFloat() / (a + b) else 0.5f
            Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50))) {
                Box(Modifier.weight(fa.coerceIn(0.05f, 0.95f)).fillMaxSize().background(if (a == null) C.SURFACE2 else C.PRIMARY))
                Box(Modifier.width(2.dp).fillMaxSize().background(C.BG))
                Box(Modifier.weight((1f - fa).coerceIn(0.05f, 0.95f)).fillMaxSize().background(if (b == null) C.SURFACE2 else C.LOSS))
            }
        }
        Text(b?.toString() ?: "?", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = if (a != null && b != null && b > a) C.LOSS else Color.White, modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
    }
}

/** Cabeçalho do próximo jogo: escudos frente a frente, quando, onde, quem e as forças. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MatchupHero(slot: Int, d: SlotData) {
    val rival = known(d.fields, K.RIVAL_TEAM)
    val at = known(d.fields, K.MATCH_AT)?.toLongOrNull()
    val home = known(d.fields, K.HOME)
    val human = known(d.fields, K.RIVAL_HUMAN)
    val myStr = known(d.fields, K.MY_STRENGTH)?.toIntOrNull()
    val rivalStr = known(d.fields, K.RIVAL_STRENGTH)?.toIntOrNull()
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).osmPanel().padding(14.dp)) {
        Text("📅  Próximo jogo", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        val sub = listOfNotNull(known(d.fields, K.COMPETITION), known(d.fields, K.ROUND)?.let { "Rodada $it" }).joinToString(" • ")
        if (sub.isNotBlank()) Text(sub, fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 2.dp))
        // o SEU time (marcado "VOCÊ") fica à esquerda em casa e à direita fora, como no OSM
        val mineLeft = home != "Fora"
        val mineCol: @Composable (Modifier) -> Unit = { m ->
            Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.BottomEnd) { Crest(slot, 64.dp, known(d.fields, K.TEAM)); Box(Modifier.offset(x = 8.dp, y = 8.dp)) { StrengthBadge(myStr, 30.dp, true) } }
                Text(known(d.fields, K.TEAM) ?: "Meu time", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
                Box(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(50)).background(C.PRIMARY.copy(alpha = 0.25f)).border(1.dp, C.PRIMARY.copy(alpha = 0.7f), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 2.dp)) {
                    Text("VOCÊ" + (if (home == "Casa") " • 🏠 casa" else if (home != null) " • ✈ fora" else ""), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                }
            }
        }
        val rivalCol: @Composable (Modifier) -> Unit = { m ->
            Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.BottomEnd) { RivalShield(rival, 64.dp, slot); Box(Modifier.offset(x = 8.dp, y = 8.dp)) { StrengthBadge(rivalStr, 30.dp) } }
                Text(rival ?: "rival não lido", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp), color = if (rival == null) C.WARN else Color.White)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (mineLeft) mineCol(Modifier.weight(1f)) else rivalCol(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (at != null && at > System.currentTimeMillis()) {
                    val cc = countdownColor(at)
                    Column(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(cc.copy(alpha = 0.14f)).border(1.dp, cc.copy(alpha = 0.55f), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Faltam", fontSize = 12.sp, color = C.MUTED)
                        Text(countdownShort(at), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = cc, maxLines = 1)
                    }
                } else if (at != null) {
                    Text(countdown(at), fontSize = 13.sp, color = C.WARN, textAlign = TextAlign.Center)
                }
                Text("VS", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
            }
            if (mineLeft) rivalCol(Modifier.weight(1f)) else mineCol(Modifier.weight(1f))
        }
        FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center) {
            if (at != null) Pill("📅 " + fmtTime(at))
            if (home != null) Pill(if (home == "Casa") "🏠 em casa" else "✈ fora")
            if (human != null) Pill(if (human == "Sim") "👤 humano" else "🖥 CPU", human == "Sim")
        }
        Spacer(Modifier.height(6.dp))
        Duel("FORÇA", known(d.fields, K.MY_STRENGTH), known(d.fields, K.RIVAL_STRENGTH))
        val myB = known(d.fields, K.MY_BONUS)
        val rvB = known(d.fields, K.RIVAL_LOGIN_BONUS)
        if (myB != null || rvB != null) Duel("BÔNUS %", myB, rvB)
        Spacer(Modifier.height(6.dp))
        RefereeMeter(known(d.fields, K.REFEREE), compact = true)
    }
}

/** Dois cartões lado a lado: onde você é melhor que o rival e onde precisa de atenção (a partir dos setores lidos). */
@Composable
internal fun StrengthsAttention(d: SlotData) {
    fun n(k: String) = known(d.fields, k)?.toIntOrNull()
    val sectors = listOf(
        Triple("Goleiro", n(K.MY_GOL), n(K.RIVAL_GOL)), Triple("Defesa", n(K.MY_DEF), n(K.RIVAL_DEF)),
        Triple("Meio-campo", n(K.MY_MID), n(K.RIVAL_MID)), Triple("Ataque", n(K.MY_ATK), n(K.RIVAL_ATK))
    ).filter { it.second != null && it.third != null }
    val good = ArrayList<String>(sectors.filter { it.second!! - it.third!! >= 3 }.sortedByDescending { it.second!! - it.third!! }.map { "${it.first}: ${it.second} × ${it.third}" })
    val bad = ArrayList(sectors.filter { it.second!! - it.third!! <= -3 }.sortedBy { it.second!! - it.third!! }.map { "${it.first}: ${it.second} × ${it.third}" })
    // campanha na liga (tabela de classificação): o que cada time marca e sofre por jogo
    val st = League.table(d.league, "st")
    val mr = League.rates(League.find(st, known(d.fields, K.TEAM) ?: known(d.fields, K.HUB_TITLE)))
    val rr = League.rates(League.find(st, known(d.fields, K.RIVAL_TEAM)))
    val avgG = League.leagueAvgGoals(st)
    fun f1(x: Double) = "%.1f".format(x).replace('.', ',')
    if (mr != null && rr != null && avgG != null) {
        if (rr.gf >= avgG * 1.25) bad.add("Rival marca ${f1(rr.gf)} gols/jogo (média da liga ${f1(avgG)})")
        else if (rr.gf <= avgG * 0.75) good.add("Rival marca pouco: ${f1(rr.gf)} gols/jogo (média ${f1(avgG)})")
        if (rr.ga >= avgG * 1.25) good.add("Rival sofre ${f1(rr.ga)} gols/jogo: defesa frágil")
        else if (rr.ga <= avgG * 0.75) bad.add("Rival sofre pouco: ${f1(rr.ga)} gols/jogo")
        if (mr.ga >= avgG * 1.25) bad.add("Seu time sofre ${f1(mr.ga)} gols/jogo (média ${f1(avgG)})")
    }
    if (known(d.fields, K.RIVAL_HUMAN) == "Sim") bad.add("Rival humano: ele pode trocar a tática; releia a análise perto do jogo")
    d.win?.let { if (it.win < 40) bad.add("Chance de vitória baixa (${it.win}%)") }
    if (sectors.isEmpty() && bad.isEmpty()) return
    @Composable
    fun Box2(title: String, icon: String, lines: List<String>, col: Color, empty: String, mod: Modifier) {
        Column(mod.clip(RoundedCornerShape(16.dp)).background(col.copy(alpha = 0.10f)).border(1.3.dp, col.copy(alpha = 0.7f), RoundedCornerShape(16.dp)).padding(12.dp)) {
            Text("$icon $title", fontSize = 14.5.sp, fontWeight = FontWeight.ExtraBold, color = col)
            if (lines.isEmpty()) Text(empty, fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 6.dp))
            for (l in lines.take(4)) Text("• $l", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box2("Pontos fortes", "✅", good, C.OK, "Nenhum setor claramente melhor", Modifier.weight(1f).fillMaxHeight())
        Box2("Atenção", "⚠", bad, C.BAD, "Nada preocupante nos setores", Modifier.weight(1f).fillMaxHeight())
    }
}

/** Força por setor: meus números contra os do rival. */
@Composable
internal fun SectorDuels(d: SlotData) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚔ Setores", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            LegendDot(C.PRIMARY, "eu")
            LegendDot(C.LOSS, "rival")
        }
        Duel("GOL", known(d.fields, K.MY_GOL), known(d.fields, K.RIVAL_GOL))
        Duel("DEF", known(d.fields, K.MY_DEF), known(d.fields, K.RIVAL_DEF))
        Duel("MEI", known(d.fields, K.MY_MID), known(d.fields, K.RIVAL_MID))
        Duel("ATA", known(d.fields, K.MY_ATK), known(d.fields, K.RIVAL_ATK))
    }
}

/** Os 5 treinadores do OSM: um por posição + o universal, com quem está em cada um. */
@Composable
internal fun TrainingBoardView(seats: List<TrainBoard.Seat>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (s in seats) {
            val col = if (s.role == "UNI") Color(0xFF9B6BFF) else catColor(s.role)
            val (icon, stateText, stateCol) = when (s.state) {
                "active" -> Triple("⏱", "treinando", C.OK)
                "done" -> Triple("✅", "pegar", C.GOLD)
                "empty" -> Triple("＋", "livre", C.WARN)
                else -> Triple("?", "não lido", C.MUTED)
            }
            Column(
                Modifier.weight(1f).height(118.dp).clip(RoundedCornerShape(14.dp)).background(C.SURFACE2)
                    .border(1.dp, if (s.state == "empty") C.WARN.copy(alpha = 0.6f) else Color(0x14FFFFFF), RoundedCornerShape(14.dp))
                    .graphicsLayer { alpha = if (s.fresh || s.state == "unknown") 1f else 0.55f },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.fillMaxWidth().background(col.copy(alpha = 0.85f)).padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                    Text(if (s.role == "UNI") "★ UNI" else s.role, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0B1220))
                }
                Text(icon, fontSize = 20.sp, color = stateCol, modifier = Modifier.padding(top = 6.dp))
                Text(
                    s.name ?: "—", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 3.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(stateText, fontSize = 11.sp, color = stateCol, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 5.dp))
            }
        }
    }
}

/** Medalha desenhada com a posição final. */
@Composable
internal fun Medal(rank: Int?, size: Dp) {
    val col = when (rank) {
        1 -> Color(0xFFFFC83D)
        2 -> Color(0xFFC0C8D4)
        3 -> Color(0xFFCD7F32)
        else -> Color(0xFF3D8BFF)
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            // fitas
            quad(Offset(w * 0.30f, 0f), Offset(w * 0.45f, 0f), Offset(w * 0.58f, h * 0.40f), Offset(w * 0.43f, h * 0.40f), Color(0xFFE5484D))
            quad(Offset(w * 0.55f, 0f), Offset(w * 0.70f, 0f), Offset(w * 0.57f, h * 0.40f), Offset(w * 0.42f, h * 0.40f), Color(0xFF3D8BFF))
            val c = Offset(w / 2f, h * 0.62f)
            val r = w * 0.36f
            drawCircle(col.copy(red = col.red * 0.6f, green = col.green * 0.6f, blue = col.blue * 0.6f), radius = r, center = c)
            drawCircle(col, radius = r * 0.84f, center = c)
            drawCircle(Color(0x55FFFFFF), radius = r * 0.84f, center = c, style = Stroke(1.5f * density))
        }
        Text(
            if (rank != null) "${rank}º" else "?", fontSize = (size.value * 0.2f).sp, fontWeight = FontWeight.ExtraBold,
            color = Color(0xFF0B1220), modifier = Modifier.padding(top = size * 0.24f)
        )
    }
}

/**
 * Fim da competição: posição final, campanha (V/E/D, pontos de quantos possíveis, gols), meta da temporada, valor do
 * elenco, campeão e a fala mais importante da direção.
 */
@Composable
internal fun SeasonEndCard(d: SlotData) {
    val info = SeasonCard.from(d.season, d.board, d.fields)
    val rec = SeasonCard.record(d.matches, known(d.fields, K.ROUND_TOTAL)?.toIntOrNull())
    val goal = known(d.fields, K.MY_OBJECTIVE)?.toIntOrNull()
    val rank = info?.rank
    Box(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF3A2A06), Color(0xFF1A1F33), Color(0xFF0E2A5A))))
            .border(1.dp, Color(0x55FFC83D), RoundedCornerShape(12.dp)).padding(14.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Medal(rank, 76.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("🏁 COMPETIÇÃO ENCERRADA", fontSize = 12.5.sp, color = C.GOLD, fontWeight = FontWeight.Bold)
                    Text(if (rank != null) "Terminou em ${rank}º lugar" else "Aguardando a próxima", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    if (info?.champion != null) Text("🏆 Campeão: ${info.champion}", fontSize = 13.sp, color = Color(0xCCFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // campanha
            if (rec != null || info?.points != null) {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (rec != null) {
                        Column(
                            Modifier.weight(1.3f).clip(RoundedCornerShape(14.dp)).background(Color(0x22FFFFFF)).padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("CAMPANHA", fontSize = 11.sp, color = C.MUTED, fontWeight = FontWeight.Bold)
                            Row(Modifier.padding(top = 4.dp)) {
                                CountPill("${rec.v}V", C.WIN)
                                CountPill("${rec.e}E", C.DRAW)
                                CountPill("${rec.d}D", C.LOSS)
                            }
                            Text("${rec.games} jogos", fontSize = 11.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                    val pts = info?.points ?: rec?.let { it.v * 3 + it.e }
                    if (pts != null) {
                        StatTile("⭐", if (rec != null) "$pts/${rec.maxPoints}" else "$pts", "pontos feitos", C.GOLD, Modifier.weight(1f))
                    }
                    val gf = rec?.gf
                    val ga = rec?.ga
                    if (gf != null && ga != null) StatTile("⚽", "$gf×$ga", "gols pró×contra", if (gf >= ga) C.OK else C.LOSS, Modifier.weight(1f))
                    else if (info?.goals != null) StatTile("⚽", info.goals, "gols", C.OK, Modifier.weight(1f))
                }
            }
            // meta e valor, em frases curtas
            if (goal != null && rank != null) {
                val ok = rank <= goal
                Text(
                    if (ok) "🎯 Meta top $goal: cumprida ✔" else "🎯 Meta top $goal: ficou ${rank - goal} posição(ões) abaixo",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (ok) C.OK else C.WARN, modifier = Modifier.padding(top = 10.dp)
                )
            }
            if (info?.value != null) {
                Text(
                    "💰 Elenco vale ${info.value}" + (if (info.valueRank != null) " — ${info.valueRank}º mais valioso da liga" else ""),
                    fontSize = 13.sp, color = Color(0xCCFFFFFF), modifier = Modifier.padding(top = 4.dp)
                )
                if (info.valueRank != null && rank != null && rank > info.valueRank + 1) {
                    Text("Ficou abaixo do que o elenco vale: dá para render mais com tática e treino.", fontSize = 12.5.sp, color = C.MUTED)
                }
            }
            val said = info?.board
            if (said != null) {
                val mood = SeasonCard.mood(said)
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Top) {
                    Text(if (mood < 0) "😠" else if (mood > 0) "😀" else "😐", fontSize = 28.sp)
                    Spacer(Modifier.width(8.dp))
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(topStart = 2.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp))
                            .background(if (mood < 0) Color(0x33E5484D) else Color(0x22FFFFFF)).padding(10.dp)
                    ) {
                        Text("Direção", fontSize = 11.5.sp, color = C.MUTED, fontWeight = FontWeight.Bold)
                        Text("“$said”", fontSize = 13.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (info.fired) Box(Modifier.padding(top = 8.dp)) { Pill("⚠ risco de demissão: o slot pode mudar de clube", false) }
            }
            SeasonReview(d, rank)
            Text(
                "✅ Esta tela já encerra a competição: o app não pede mais nada neste slot até a próxima liga ou batalha começar.",
                fontSize = 13.sp, color = C.OK, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

/** O que a competição que acabou ensinou: força contra os rivais, postura da tática, tamanho do elenco e o que fazer na próxima. */
@Composable
internal fun SeasonReview(d: SlotData, rank: Int?) {
    val logs = d.logs.mapNotNull { runCatching { JSONObject(it.json) }.getOrNull() }.filter { it.optString("result") in listOf("V", "E", "D") && !it.optBoolean("archived") }
    val lines = ArrayList<Pair<String, String>>()
    val both = logs.filter { it.optInt("myStrength") > 0 && it.optInt("rivalStrength") > 0 }
    if (both.size >= 5) {
        val my = both.map { it.optInt("myStrength") }.average()
        val rv = both.map { it.optInt("rivalStrength") }.average()
        val stronger = both.count { it.optInt("rivalStrength") > it.optInt("myStrength") + 2 }
        lines.add(
            "🆚" to "Seu time jogou com força média ${"%.0f".format(my)} contra ${"%.0f".format(rv)} dos rivais" +
                (if (rv > my + 1) " (${"%.0f".format(rv - my)} a menos)" else "") + ". Em $stronger de ${both.size} jogos o rival era mais forte."
        )
        val hum = logs.count { it.optBoolean("human") }
        if (hum * 2 >= logs.size) lines.add("👤" to "$hum de ${logs.size} jogos foram contra humanos: eles trocam a tática até o último minuto.")
        fun ppg(g: List<JSONObject>): Double = g.sumOf { j -> val r = j.optString("result"); (if (r == "V") 3 else if (r == "E") 1 else 0).toInt() }.toDouble() / g.size
        val weak = both.filter { it.optInt("rivalStrength") > it.optInt("myStrength") + 2 }
        val def = weak.filter { it.optInt("mentality", 50) < 45 }
        val att = weak.filter { it.optInt("mentality", 50) >= 45 }
        if (def.size >= 2 && att.size >= 2) {
            lines.add("🛡" to "Contra rival mais forte: postura defensiva rendeu ${"%.1f".format(ppg(def)).replace('.', ',')} pts/jogo (${def.size} jogos) e a postura de ataque ${"%.1f".format(ppg(att)).replace('.', ',')} (${att.size}).")
        } else if (def.size >= 4 && ppg(def) < 0.8) {
            lines.add("🛡" to "Contra rival mais forte você jogou recuado em ${def.size} jogos e fez só ${"%.1f".format(ppg(def)).replace('.', ',')} pts/jogo: recuar não segurou o placar.")
        }
    }
    val mine = d.players.count { it.owner == "MY" }
    if (mine in 1..20) lines.add("👥" to "Elenco curto ($mine jogadores): lesão e suspensão sem reposição derrubam o time. Na próxima, tenha 21 ou mais.")
    if (lines.isEmpty() && rank == null) return
    Column(Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x22000000)).padding(12.dp)) {
        Text("📚 O que esta competição ensinou", fontSize = 14.5.sp, fontWeight = FontWeight.ExtraBold)
        for ((ic, t) in lines) Text("$ic  $t", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        Text("Para a próxima:", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = C.GOLD, modifier = Modifier.padding(top = 10.dp))
        for (t in listOf(
            "Comece pelo plano de arranque do Diretor: vender cedo o que sobra e treinar nos 5 treinadores sem parar.",
            "Contra humano mais forte, jogue no 4-3-3 com estilo ofensivo: o app já aprende isso dos seus jogos.",
            "Compare o valor do seu elenco com o da liga (aba Valor do plantel) a cada semana."
        )) Text("• $t", fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
    }
}


/** Sequência de resultados (bolinhas V/E/D). */
@Composable
internal fun FormGuide(results: List<String>) {
    Row(Modifier.padding(top = 6.dp)) {
        for (r in results) Box(Modifier.padding(end = 4.dp)) { ResultBadge(r) }
    }
}

/** Tela vazia com desenho grande em vez de parágrafo. */
@Composable
internal fun EmptyState(icon: String, title: String, hint: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(50)).background(C.SURFACE2), contentAlignment = Alignment.Center) {
            Text(icon, fontSize = 38.sp)
        }
        Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
        if (hint != null) Hint(hint, "o que fazer")
    }
}

/** Linha de status com luz verde/amarela. */
@Composable
internal fun StatusLight(ok: Boolean, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(if (ok) C.OK else C.WARN))
        Text("  $text", fontSize = 14.sp, color = if (ok) Color.White else C.WARN)
    }
}

/** Alerta colorido pelo ícone do começo da frase. */
@Composable
internal fun AlertCard(text: String, onClick: (() -> Unit)? = null) {
    val col = when {
        text.startsWith("🔴") -> C.LOSS
        text.startsWith("🟢") -> C.OK
        text.startsWith("🔵") -> C.PRIMARY
        text.startsWith("🏁") -> C.GOLD
        text.startsWith("📊") -> C.GOLD
        text.startsWith("⚠") -> C.WARN
        else -> C.MUTED
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(col.copy(alpha = 0.12f))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .drawBehind { drawRect(col, size = Size(4.dp.toPx(), size.height)) }.padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { Text(text, fontSize = 13.sp, modifier = Modifier.weight(1f)) }
}

/** Passo a passo desenhado de como o app funciona. */
@Composable
internal fun HowItWorks() {
    val steps = listOf("▶" to "Abra o OSM\npelo app", "👆" to "Navegue\nnas telas", "🧠" to "O app lê\ne calcula", "⚡" to "Use a\ntática")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        for ((i, s) in steps.withIndex()) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(50)).background(Brush.linearGradient(listOf(Color(0xFF1F6BFF), Color(0xFF00B4D8)))),
                    contentAlignment = Alignment.Center
                ) { Text(s.first, fontSize = 20.sp, color = Color.White) }
                Text(s.second, fontSize = 11.5.sp, color = C.MUTED, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
            if (i < steps.size - 1) Text("›", fontSize = 22.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

// ------------------------------------------------------------------ dados de tela

/** Cartão de alerta: o rival mudou depois da tática gerada — o que mudou, por quê e o botão para gerar de novo. */
@Composable
internal fun RivalChangedCard(changes: List<RivalWatch.Change>, onGenerate: () -> Unit) {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(
        initialValue = 0.35f, targetValue = 0.9f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "glow"
    )
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF4A2A06), Color(0xFF2A1A08))))
            .border(1.5.dp, Color(0xFFFFB020).copy(alpha = glow), RoundedCornerShape(16.dp)).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(50)).background(Color(0xFFFFB020)), contentAlignment = Alignment.Center) {
                Text("!", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF2A1A08))
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text("O rival mudou a tática dele", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFFFD27A))
                Text("Sua tática foi montada antes disso. Gere uma nova.", fontSize = 13.sp, color = Color(0xCCFFFFFF))
            }
        }
        for (c in changes) {
            Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x33000000)).padding(10.dp)) {
                Text(c.label, fontSize = 12.5.sp, color = Color(0xFFFFB020), fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Text(c.from, fontSize = 14.sp, color = Color(0x99FFFFFF), textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
                    Text("  →  ", fontSize = 14.sp, color = Color(0xFFFFB020), fontWeight = FontWeight.Bold)
                    Text(c.to, fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
                Text("Por quê: " + c.why, fontSize = 13.sp, color = Color(0xDDFFFFFF), modifier = Modifier.padding(top = 4.dp))
            }
        }
        Box(
            Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFFFFC21A), Color(0xFFFF9500)))).clickable { onGenerate() }.padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) { Text("⚡ Gerar nova tática", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF2A1A08)) }
    }
}

/** Mudanças do rival depois da tática atual (só vale para a tática deste jogo). */
suspend fun rivalChangesOf(repo: Repo, slot: Int): List<RivalWatch.Change> {
    val t = repo.dao.plan(slot, "tactic") ?: return emptyList()
    val c = repo.dao.plan(slot, "rivalchange") ?: return emptyList()
    if (c.at < t.at) return emptyList()
    val tj = runCatching { JSONObject(t.json) }.getOrNull() ?: return emptyList()
    if (!Director.tacticValid(tj, t.at, repo.fieldMap(slot), System.currentTimeMillis())) return emptyList()
    return RivalWatch.fromJson(runCatching { JSONObject(c.json) }.getOrNull())
}

data class SlotSummary(
    val slot: Int,
    val title: String,
    val known: Boolean,
    val competition: String?,
    val compType: String?,
    val roundDone: String?,
    val roundTotal: String?,
    val rival: String?,
    val human: String?,
    val pct: Int,
    val updated: Long,
    val nextAt: Long? = null,
    val tacticReady: Boolean = false,
    /** Tática existe, mas o rival (formação/estilo/força) não foi relido nas últimas 3 h: não confiar ainda. */
    val tacticStale: Boolean = false,
    /** Faltam leituras (Pré-jogo, Análise, valor do rival) para gerar a tática. */
    val rivalGaps: Boolean = false,
    /** Leituras do rival frescas (3 h): Pré-jogo (forças), Análise (formação/estilo), Plantel (valor, 12 h). */
    val readPre: Boolean = false,
    val readAna: Boolean = false,
    val readSquad: Boolean = false,
    val readMine: Boolean = false,
    /** A tática que está no OSM foi conferida e bate com a gerada (plano `tacticdone`): nada a montar. */
    val mounted: Boolean = false,
    /** Li a Tática do OSM depois de gerada e ela difere da gerada: o que difere (a pendência "montar" mostra isso em vez de repetir "já está pronta"). */
    val mountDiff: List<String> = emptyList(),
    /** Idade da leitura mais antiga de cada passo [Pré-jogo, Análise, Rival]: a tela mostra "✓ Análise · 2 h" em vez de só ✓. */
    val readAges: List<Long?> = emptyList(),
    /** Forma recente (ex. "VVDEV") dos dois times e nível da preparação, lidos no pré-jogo (≤12 h). */
    val myForm: String? = null,
    val rivalForm: String? = null,
    val prepLevel: String? = null,
    val missing: Int = 0,
    /** O card do calendário (data e hora) já passou e o jogo ainda não tem placar: pede o resultado. */
    val needsResult: Boolean = false,
    val win: WinModel.Prob? = null,
    /** Campos do próximo jogo ainda não lidos (mesmo critério para todos os slots). */
    val missingNames: List<String> = emptyList(),
    /** Todas as rodadas da competição jogadas e nenhum jogo marcado: aguardando a próxima competição. */
    val ended: Boolean = false,
    /** Central do OSM: "vazio" (+ começar nova liga) ou "procurando" (batalha procurando adversário). */
    val state: String? = null,
    /** Próximo jogo em casa (true) ou fora (false); null = não lido. O time do usuário fica à ESQUERDA em casa e à DIREITA fora. */
    val home: Boolean? = null,
    /** O rival mudou algo (formação, estilo, árbitro…) depois que a tática foi gerada. */
    val rivalChanged: List<RivalWatch.Change> = emptyList(),
    /** Última vez que o app conferiu o rival e a tática gerada continuou valendo (geração automática). */
    val autoChk: Long? = null,
    /** Quando a tática foi gerada sozinha pela última vez. */
    val autoAt: Long? = null,
    /** Força do meu time e do rival (círculos do pré-jogo). */
    val myStr: Int? = null,
    val rivalStr: Int? = null
)

data class TodayData(val active: Boolean = false, val slots: List<SlotSummary> = emptyList(), val work: List<SlotWork> = emptyList(), val needs: List<Fresh.Need> = emptyList(), val accuracy: Accuracy.Summary? = null, val results: Results.Summary? = null)

object TodayCache {
    @Volatile var last = TodayData()
    @Volatile var maintainedAt = 0L
}

data class SlotData(
    /** false enquanto o slot ainda está carregando (evita piscar "ainda não lido"). */
    val ready: Boolean = false,
    /** Slot sem competição em andamento (livre, procurando adversário, pré-época ou competição encerrada): texto do estado; null = ativo. */
    val idle: String? = null,
    val fields: Map<String, StoredField> = emptyMap(),
    val players: List<PlayerEntity> = emptyList(),
    val matches: List<MatchEntity> = emptyList(),
    val tactic: PlanEntity? = null,
    val market: PlanEntity? = null,
    val learning: List<LearningEntity> = emptyList(),
    val sections: Map<String, Long> = emptyMap(),
    val completeness: Completeness.Result? = null,
    val baseline: List<String> = emptyList(),
    val marketPlan: MarketEngine.Plan? = null,
    val marketNote: String? = null,
    val logs: List<PlanEntity> = emptyList(),
    /** Todos os jogos do slot (inclui competições arquivadas): os cartões de resultado e o Placar contam o mesmo. */
    val logsAll: List<PlanEntity> = emptyList(),
    val reports: List<PlanEntity> = emptyList(),
    val fitness: Map<String, Pair<Int, Int>> = emptyMap(),
    /** País de cada jogador (nome do Atlas pela bandeira) e a assinatura rica para desenhar a bandeira. */
    val countries: Map<String, String?> = emptyMap(),
    val flagRich: Map<String, String> = emptyMap(),
    /** Bandeira NÍTIDA de cada jogador (recorte da captura inteira, WebP base64). */
    val flagImg: Map<String, String> = emptyMap(),
    val isos: Map<String, String> = emptyMap(),
    /** Emblema da liga do slot (Atlas), se o nome da competição casa com uma liga aprendida. */
    val leagueRich: String? = null,
    /** Véspera do jogo: (feito?, texto) na ordem em que se faz. Vazio = jogo longe. */
    val eve: List<Pair<Boolean, String>> = emptyList(),
    val rivalProfile: PlanEntity? = null,
    val win: WinModel.Prob? = null,
    val evidence: JSONObject? = null,
    /** Leituras que não batem entre telas (para conferir). */
    val conflicts: List<String> = emptyList(),
    /** Jogadores marcados fora do próximo jogo. */
    val unavailable: Set<String> = emptySet(),
    /** Fim da competição e mensagens da direção (mais nova primeiro). */
    val board: List<String> = emptyList(),
    /** Última leitura de cada treinador (tela de Treinamento). */
    val trainScreen: JSONObject? = null,
    /** Treino corrigido à mão (nameKey -> treinando?). */
    val trainOv: Map<String, Boolean> = emptyMap(),
    /** Evolução: placar da tática, livro de caixa, treino e semana. */
    val progress: Repo.ProgressData? = null,
    /** O rival mudou depois da tática gerada. */
    val rivalChanged: List<RivalWatch.Change> = emptyList(),
    /** Resumo do fim da competição (posição, pontos, campeão, valor). */
    val season: JSONObject? = null,
    /** Tabelas da liga inteira (Classificação e Valor do plantel) lidas do jogo. */
    val league: JSONObject? = null,
    /** O que os gols reais dos 4 slots ensinaram ao modelo. */
    val learned: Learned.Model? = null,
    /** Táticas mais prováveis do rival humano (pelo histórico do apelido). */
    val rivalPred: RivalPredict.View? = null
)

data class SessionRow(val s: SessionEntity, val counts: String, val unassigned: Int)

/** Véspera do jogo (≤ 8 h): o que já foi feito e o que falta, na ordem. */
internal suspend fun buildEve(repo: Repo, slot: Int, f: Map<String, StoredField>, tplan: JSONObject?): List<Pair<Boolean, String>> {
    val now = System.currentTimeMillis()
    val at = f[K.MATCH_AT]?.value?.toLongOrNull() ?: return emptyList()
    if (at <= now || at - now > Director.TACTIC_WINDOW_MS || Fixtures.seasonEnded(f, repo.dao.matchesOf(slot), now)) return emptyList()
    val out = ArrayList<Pair<Boolean, String>>()
    for (g in Director.groups(f, now)) out.add(Pair(g.ok, (if (g.ok) "Lido: " else "Ler: ") + g.name.substringBefore(" (") + (if (g.ok || g.missing.isEmpty()) "" else " (" + g.missing.joinToString(", ").take(40) + ")")))
    val tp = repo.dao.plan(slot, "tactic")
    val valid = tp != null && tplan != null && runCatching { Director.tacticValid(tplan, tp.at, f, now) }.getOrDefault(false)
    out.add(Pair(valid, if (valid) "Tática gerada: ${tplan?.optString("formation")} • ${tplan?.optString("playStyle")}" else "Gerar a tática do jogo"))
    val dj = repo.dao.plan(slot, "tacticdone")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
    val mounted = valid && dj != null && (dj.optLong("at") == tp?.at || (dj.optString("sig").isNotBlank() && dj.optString("sig") == Director.mountSig(tplan!!)))
    out.add(Pair(mounted, if (mounted) "Tática montada no OSM e conferida" else "Montar a tática no OSM (Menu → Tática)"))
    if (at - now <= Reanalysis.WINDOW_MS) out.add(Reanalysis.eveItem(repo.dao.plan(slot, Reanalysis.PLAN)?.json, at, now))
    f[K.PREP_LEVEL]?.value?.let { lv -> out.add(Pair(!PrepLevel.low(lv), "Preparação do jogo: $lv" + if (PrepLevel.low(lv)) " — conclua a lista de afazeres" else "")) }
    val (inj, susp) = repo.outByDoctorOrLawyer(slot)
    if (inj.isNotEmpty()) out.add(Pair(false, "Lesionados: ${inj.size} (Médico, 8 h grátis)"))
    if (susp.isNotEmpty()) out.add(Pair(false, "Suspensos: ${susp.size} (Advogado, 8 h grátis)"))
    return out
}

internal suspend fun loadSlot(ctx: Context, slot: Int): SlotData {
    val repo = Repo(ctx)
    val dao = repo.dao
    try {
        repo.relinkResults(slot)
        repo.validateHuman(slot)
        repo.syncRound(slot)
        repo.syncTraining(slot)
    } catch (e: kotlinx.coroutines.CancellationException) {
        // saiu da tela antes de terminar: não é erro
        throw e
    } catch (e: Exception) {
        Diag.lastError = "Resultados: " + (e.message ?: e.javaClass.simpleName)
    }
    val f = repo.fieldMap(slot)
    val players = repo.playersOf(slot)
    val matches = dao.matchesOf(slot)
    val market = dao.snapshots("TRANSFER", slot).isNotEmpty()
    val comp = Completeness.compute(
        f, players.count { it.owner == "MY" }, matches.count { it.round != null }, market,
        f[K.ROUND_TOTAL]?.value?.toIntOrNull()
    )
    Director.resolveTacticLogs(repo, slot)
    val tplan = dao.plan(slot, "tactic")?.json?.let { try { JSONObject(it) } catch (e: Exception) { null } }
    val imgMap = runCatching { repo.dao.plan(slot, "natimg3")?.json?.let { j -> JSONObject(j).let { o -> o.keys().asSequence().associateWith { k -> o.optString(k) } } } }.getOrNull().orEmpty()
    FlagWeb.ensureAll(ctx)
    val countriesMap = runCatching { SlotRules.playerCountries(repo, slot, ctx.filesDir) }.getOrDefault(emptyMap())
    val isoMap = (runCatching { repo.dao.plan(slot, "natiso2")?.json?.let { j -> JSONObject(j).let { o -> o.keys().asSequence().associateWith { k -> o.optString(k) } } } }.getOrNull().orEmpty()) +
        runCatching { FlagManual.all(repo) }.getOrDefault(emptyMap())   // bandeira escolhida à mão vale mais
    val bankMap = runCatching { repo.dao.plan(0, "natbank")?.json?.let { j -> JSONObject(j).let { o -> o.keys().asSequence().associateWith { k -> o.optString(k) } } } }.getOrNull().orEmpty()
    val richMap = runCatching { repo.dao.plan(slot, "natrich")?.json?.let { j -> JSONObject(j).let { o -> o.keys().asSequence().associateWith { k -> o.optString(k) } } } }.getOrNull().orEmpty()
    val leagueRichV = runCatching { Atlas.leagueMatch(repo, f[K.COMPETITION]?.value)?.let { n -> Atlas.richOf(repo, Atlas.Kind.LEAGUE, n) } }.getOrNull()
    val eveList = runCatching { buildEve(repo, slot, f, tplan) }.getOrDefault(emptyList())
    val idleState = known(f, K.SLOT_STATE)?.takeIf { it == "vazio" || it == "procurando" || it == "preepoca" }
    val idleOver = runCatching { Hints.competitionOver(repo, slot, f, System.currentTimeMillis()) }.getOrDefault(false)
    val idleText = when {
        idleState == "vazio" -> "Slot livre • sem liga ou batalha"
        idleState == "procurando" -> "Procurando adversário"
        idleState == "preepoca" -> "Pré-época • a competição começa em breve"
        idleOver -> "Competição encerrada • aguardando a próxima"
        else -> null
    }
    return SlotData(
        ready = true,
        idle = idleText,
        learned = runCatching { Learned.fit(Director.allLogs(repo)) }.getOrNull(),
        rivalPred = runCatching { RivalPredict.of(repo.rivalProfile(slot)?.json?.let { JSONObject(it) }) }.getOrNull(),
        fields = f, players = players, matches = dao.matchesOf(slot),
        tactic = dao.plan(slot, "tactic"), market = dao.plan(slot, "market"),
        learning = dao.learningOf(slot),
        sections = dao.lastBySection(slot).associate { it.type to it.c },
        completeness = comp,
        marketPlan = Director.marketPlan(repo, slot),
        marketNote = dao.plan(slot, "market")?.let { mp ->
            // comentário de outro elenco (jogadores já vendidos ou que nem existem) não aparece: peça de novo
            try {
                val j = JSONObject(mp.json)
                val then = j.optJSONArray("squad")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
                val now = repo.playersOf(slot).filter { it.owner == "MY" }.map { it.nameKey }.toSet()
                // nota antiga sem a lista do elenco não dá para conferir (citava jogadores que já saíram): não aparece
                val fresh = if (then == null) false
                else (then - now).size <= 2 && (now - then).size <= 2 && System.currentTimeMillis() - mp.at < 3L * 86400000L
                if (fresh) j.optString("aiNote").ifBlank { null } else null
            } catch (e: Exception) { null }
        },
        logs = dao.tacticLogs(slot).filter { it.kind.startsWith("tlog_R") },
        logsAll = dao.tacticLogs(slot),
        reports = dao.matchReports(slot),
        fitness = repo.fitness(slot),
        countries = countriesMap, flagRich = richMap, flagImg = Flags.withBank(imgMap, isoMap, bankMap), isos = isoMap, leagueRich = leagueRichV, eve = eveList,
        rivalProfile = repo.rivalProfile(slot),
        board = repo.boardMessages(slot),
        trainScreen = dao.plan(slot, "trainscreen")?.json?.let { try { JSONObject(it) } catch (e: Exception) { null } },
        trainOv = repo.trainingOverrides(slot),
        progress = runCatching { repo.progress(slot) }.getOrNull(),
        rivalChanged = runCatching { rivalChangesOf(repo, slot) }.getOrDefault(emptyList()),
        season = dao.plan(slot, "season")?.json?.let { try { JSONObject(it) } catch (e: Exception) { null } },
        league = dao.plan(slot, "league")?.json?.let { try { JSONObject(it) } catch (e: Exception) { null } },
        win = tplan?.takeIf { tacticCurrent(it, f) }?.let { Director.winProb(repo, slot, it) },
        evidence = repo.evidence(slot),
        conflicts = repo.conflicts(slot),
        unavailable = repo.unavailable(slot)
    )
}

/** Rodadas que já têm placar lido da análise do jogo. */
internal fun scoredRounds(reports: List<PlanEntity>): Set<Int> = reports.mapNotNull { p ->
    val r = p.kind.removePrefix("mr_R").toIntOrNull() ?: return@mapNotNull null
    val j = try { JSONObject(p.json) } catch (e: Exception) { return@mapNotNull null }
    if (j.has("sh") && j.has("sa")) r else null
}.toSet()

/** A tática guardada é da próxima rodada (senão a previsão usa só as forças). */
internal fun tacticCurrent(j: JSONObject, f: Map<String, StoredField>): Boolean =
    Director.tacticValid(j, System.currentTimeMillis(), f, System.currentTimeMillis())

internal fun known(f: Map<String, StoredField>, key: String): String? =
    f[key]?.value?.takeIf { FieldMerge.known(it) }

internal suspend fun tacticFor(repo: Repo, slot: Int, @Suppress("UNUSED_PARAMETER") round: Int?): Boolean = Director.tacticReady(repo, slot)

internal fun countdown(ms: Long): String {
    val d = ms - System.currentTimeMillis()
    if (d > 0L) {
        val m = d / 60000L
        return if (m >= 60) "em ${m / 60}h ${m % 60}min" else "em $m min"
    }
    if (d > -3L * 3600000L) return "já começou — registre o resultado"
    return "horário antigo — releia o calendário"
}

/** "2d 5h", "19h 53min", "45 min": tempo que falta, curto para caber no cartão. */
internal fun countdownShort(ms: Long): String {
    val m = (ms - System.currentTimeMillis()) / 60000L
    return when {
        m < 1 -> "agora"
        m < 60 -> "$m min"
        m < 48 * 60 -> "${m / 60}h ${(m % 60).toString().padStart(2, '0')}min"
        else -> "${m / 1440}d ${(m % 1440) / 60}h"
    }
}

/** Verde longe do jogo, amarelo dentro da janela de tática (8 h), vermelho na última hora. */
internal fun countdownColor(ms: Long): Color {
    val m = (ms - System.currentTimeMillis()) / 60000L
    return if (m <= 60) C.BAD else if (m <= Director.TACTIC_WINDOW_MS / 60000L) C.GOLD else C.OK
}

internal suspend fun loadToday(ctx: Context): TodayData {
    val repo = Repo(ctx)
    val list = ArrayList<SlotSummary>()
    val now = System.currentTimeMillis()
    // A arrumação dos dados é pesada; basta a cada 20 s (ou logo após dados novos).
    val maintain = now - TodayCache.maintainedAt > 20000L || Diag.lastUpdateAt > TodayCache.maintainedAt
    if (maintain) TodayCache.maintainedAt = now
    for (slot in 1..4) try {
        if (maintain) try {
            repo.relinkResults(slot)
            repo.validateHuman(slot)
            repo.purgeOverlayNames(slot)
        } catch (e: Exception) { }
        val clk = try { repo.syncRound(slot, now) } catch (e: Exception) { null }
        val f = repo.fieldMap(slot)
        val players = repo.dao.playersOf(slot).count { it.owner == "MY" }
        val all = repo.dao.matchesOf(slot)
        val matches = all.count { it.round != null }
        val awaiting = Fixtures.resultDue(
            all, scoredRounds(repo.dao.matchReports(slot)), now, clk?.round ?: f[K.ROUND]?.value?.toIntOrNull(), clk?.at
        )
        val gameMissing = Completeness.gameMissing(f)
        val fr = repo.dao.plan(slot, "formread")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
        val tplan = repo.dao.plan(slot, "tactic")?.json?.let { try { JSONObject(it) } catch (e: Exception) { null } }
        val market = repo.dao.snapshots("TRANSFER", slot).isNotEmpty()
        val c = Completeness.compute(f, players, matches, market, f[K.ROUND_TOTAL]?.value?.toIntOrNull())
        val state = known(f, K.SLOT_STATE)?.takeIf { it == "vazio" || it == "procurando" || it == "preepoca" }
        val overNow = Hints.competitionOver(repo, slot, f, now)
        val name = if (state == null && overNow) "Competição encerrada" else when (state) {
            "vazio" -> "Slot livre"
            "procurando" -> known(f, K.COMPETITION) ?: "Batalha"
            "preepoca" -> known(f, K.HUB_TITLE) ?: known(f, K.TEAM)
            else -> known(f, K.TEAM) ?: known(f, K.HUB_TITLE)
        }
        list.add(
            SlotSummary(
                slot = slot, title = name ?: "Slot $slot ainda não lido", known = name != null, state = state,
                home = known(f, K.HOME)?.let { it == "Casa" },
                competition = known(f, K.COMPETITION), compType = known(f, K.COMP_TYPE),
                roundDone = known(f, K.ROUND_DONE), roundTotal = known(f, K.ROUND_TOTAL),
                rival = known(f, K.RIVAL_TEAM), human = known(f, K.RIVAL_HUMAN),
                pct = c.percent, updated = f.values.maxOfOrNull { it.updatedAt } ?: 0L,
                ended = Hints.competitionOver(repo, slot, f, now),
                missing = gameMissing.size,
                missingNames = gameMissing.map { it.label },
                nextAt = known(f, K.MATCH_AT)?.toLongOrNull(),
                tacticReady = tacticFor(repo, slot, known(f, K.ROUND)?.toIntOrNull()).let { ok -> ok && Director.rivalConfirmed(f, now) },
                rivalGaps = Director.tacticGaps(f, now).isNotEmpty(),
                readPre = Director.groups(f, now)[0].ok,
                readAna = Director.groups(f, now)[1].ok,
                readSquad = Director.groups(f, now)[2].ok,
                readMine = Director.groups(f, now)[3].ok,
                mounted = repo.dao.plan(slot, "tactic").let { tp -> tp != null && repo.dao.plan(slot, "tacticdone")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }.let { dj ->
                    dj != null && (dj.optLong("at") == tp.at || dj.optString("sig").let { sg -> sg.isNotBlank() && runCatching { sg == Director.mountSig(JSONObject(tp.json)) }.getOrDefault(false) }) } },
                mountDiff = repo.dao.plan(slot, "tactic")?.let { tp -> runCatching { Hints.mountDiffs(repo, slot, tp, JSONObject(tp.json), f) }.getOrDefault(emptyList()) } ?: emptyList(),
                readAges = Director.groupAgesMs(f, now),
                // meu time: igual ao jogo (5 bolinhas do Pré-jogo) quando o Calendário está incompleto; Calendário em dia vale mais
                myForm = if (((f[K.ROUND_DONE]?.value?.toIntOrNull() ?: 0) == 0) && all.none { it.result != null }) null else FormRead.mine(all, f[K.ROUND_DONE]?.value?.toIntOrNull(), fr?.optString("my")?.takeIf { it.isNotEmpty() && now - fr.optLong("at") <= 7L * 86_400_000L }
                    ?: f[K.MY_FORM]?.value, fr?.optInt("round")?.takeIf { fr.has("round") }),
                rivalForm = if (((f[K.ROUND_DONE]?.value?.toIntOrNull() ?: 0) == 0) && all.none { it.result != null }) null else (if (fr != null && fr.optString("tag") == "${f[K.ROUND]?.value}|" + Txt.key(f[K.RIVAL_TEAM]?.value ?: "")) fr.optString("rival").takeIf { it.isNotEmpty() } else null)
                    ?: f[K.RIVAL_FORM]?.takeIf { now - it.updatedAt <= 12L * 3_600_000L }?.value,
                prepLevel = f[K.PREP_LEVEL]?.takeIf { now - it.updatedAt <= 12L * 3_600_000L }?.value,
                tacticStale = tacticFor(repo, slot, known(f, K.ROUND)?.toIntOrNull()).let { ok -> ok && !Director.rivalConfirmed(f, now) },
                needsResult = awaiting != null,
                win = tplan?.takeIf { tacticCurrent(it, f) && !Fixtures.seasonEnded(f, all, now) }?.let { Director.winProb(repo, slot, it) },
                rivalChanged = rivalChangesOf(repo, slot),
                myStr = known(f, K.MY_STRENGTH)?.toIntOrNull(), rivalStr = known(f, K.RIVAL_STRENGTH)?.toIntOrNull(),
                autoChk = repo.dao.plan(slot, "autogen")?.json?.let { runCatching { JSONObject(it).optLong("chk", 0L) }.getOrNull() }?.takeIf { it > 0L },
                autoAt = repo.dao.plan(slot, "autogen")?.json?.let { runCatching { JSONObject(it).optLong("at", 0L) }.getOrNull() }?.takeIf { it > 0L }
            )
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // um slot com problema não some com os outros
        list.add(TodayCache.last.slots.firstOrNull { it.slot == slot } ?: SlotSummary(slot, "Slot $slot ainda não lido", false, null, null, null, null, null, null, 0, 0L))
        Diag.lastError = "Hoje S$slot: " + (e.message ?: e.javaClass.simpleName)
    }
    val work = (1..4).mapNotNull { s ->
        runCatching {
            val f = repo.fieldMap(s)
            if (Hints.slotFree(f)) return@runCatching null
            val team = known(f, K.TEAM) ?: known(f, K.HUB_TITLE) ?: "Slot $s"
            val screen = repo.dao.plan(s, "trainscreen")?.json?.let { JSONObject(it) }
            Work.of(s, team, screen, repo.timers(s), f).takeIf { it.hasData }
        }.getOrNull()
    }
    val needs = ArrayList<Fresh.Need>()
    for (s in list) {
        if (s.ended || s.state != null && s.state != "ativo" || !s.known) continue
        runCatching { needs.addAll(Hints.needsFor(repo, s.slot, s.nextAt, true, now)) }
    }
    runCatching { needs.addAll(Fresh.global(repo.timers(0), repo.seenMap(0), now)) }
    val acc = runCatching {
        Accuracy.of((1..4).flatMap { sl -> Accuracy.parse(sl, repo.dao.tacticLogs(sl).mapNotNull { runCatching { JSONObject(it.json) }.getOrNull() }) })
    }.getOrNull()
    val res = runCatching {
        Results.of((1..4).flatMap { sl -> repo.dao.tacticLogs(sl).mapNotNull { p -> runCatching { JSONObject(p.json).also { it.put("genAt", p.at) } }.getOrNull() } })
    }.getOrNull()
    return TodayData(Control.activeSession(ctx) != null, list, work, needs.sortedWith(compareByDescending<Fresh.Need> { it.urgency }.thenBy { it.slot }), acc, res).also { TodayCache.last = it }
}

// ------------------------------------------------------------------ app

@Composable
internal fun ChipButton(text: String, colors: List<Color>, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(colors))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1) }
}

/**
 * Botões de captura em qualquer aba (mesma função da tela Hoje): sem captura, abre o OSM e já inicia a captura;
 * capturando, volta ao OSM ou encerra e processa.
 */
@Composable
internal fun OsmButton(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tick = rememberTick(1000L)
    val active = tick >= 0 && Control.activeSession(ctx) != null
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (active) {
            ChipButton("■ Encerrar", listOf(Color(0xFFD32F2F), Color(0xFFFF5C5C))) {
                AppScope.scope.launch { Control.end(ctx.applicationContext) }
                Toast.makeText(ctx, "Captura encerrada. Processando em segundo plano…", Toast.LENGTH_SHORT).show()
            }
            Spacer(Modifier.width(6.dp))
            ChipButton("⚽ OSM ↗", listOf(Color(0xFF1F6BFF), Color(0xFF00B4D8))) { openOsm(ctx) }
        } else {
            ChipButton("▶ OSM + captura", listOf(Color(0xFF1F6BFF), Color(0xFF00B4D8))) {
                if (serviceEnabled(ctx) && Diag.serviceConnected) {
                    scope.launch {
                        Control.start(ctx)
                        if (!openOsm(ctx)) Toast.makeText(ctx, "OSM não encontrado neste aparelho.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    openOsm(ctx)
                    Toast.makeText(ctx, "Leitura automática desligada: ative em Ajustes para capturar.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

@Composable
internal fun App() {
    val ctx = LocalContext.current
    // Volta exatamente para a tela em que estava (ao alternar com o OSM ou se o Android recriar o app).
    val ui = remember { ctx.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var tab by rememberSaveable { mutableIntStateOf(ui.getInt("tab", 0)) }
    var openSlot by rememberSaveable { mutableIntStateOf(ui.getInt("slot", 0)) }
    var openTab by rememberSaveable { mutableIntStateOf(ui.getInt("slotTab", 0)) }
    LaunchedEffect(tab, openSlot, openTab) {
        ui.edit().putInt("tab", tab).putInt("slot", openSlot).putInt("slotTab", openTab).apply()
    }
    val items = listOf("🏠" to "Hoje", "📅" to "Agenda", "⚽" to "Slots", "🧠" to "Diretor", "🎨" to "Artes", "⚙️" to "Ajustes")
    // Botão Voltar do Android: fecha o slot aberto, depois volta para Hoje (em vez de fechar o app).
    BackHandler(enabled = openSlot > 0) { openSlot = 0 }
    BackHandler(enabled = openSlot == 0 && tab != 0) { tab = 0 }
    // Toque numa notificação: abre o slot na aba certa.
    LaunchedEffect(Route.nonce) {
        if (Route.slot > 0) {
            tab = 2
            openSlot = Route.slot
            openTab = Route.tab
            Route.slot = 0
        }
    }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = { BrandBar() },
        floatingActionButton = { if (openSlot == 0) OsmButton() },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF071530), tonalElevation = 0.dp) {
                items.forEachIndexed { i, (icon, name) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i; openSlot = 0 },
                        icon = {
                            val vec = when (i) { 0 -> Icons.Outlined.Home; 1 -> Icons.Outlined.DateRange; 2 -> Icons.Outlined.List; 3 -> Icons.Outlined.Person; 4 -> Icons.Outlined.Create; else -> Icons.Outlined.Settings }
                            Icon(vec, contentDescription = name, modifier = Modifier.size(26.dp))
                        },
                        label = {
                            val newArts = if (i == 4) Arts.unseenCount(ctx).also { UiBus.version } else 0
                            Text(if (newArts > 0) "$name ($newArts)" else name, fontSize = 13.sp, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                        },
                        colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                            indicatorColor = C.PRIMARY.copy(alpha = 0.20f), selectedTextColor = C.PRIMARY, unselectedTextColor = C.MUTED,
                            selectedIconColor = C.PRIMARY, unselectedIconColor = C.MUTED
                        )
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> TodayTab(onGoSettings = { tab = 5 }, onOpenSlot = { slot, t -> tab = 2; openSlot = slot; openTab = t })
                1 -> AgendaTab()
                2 -> if (openSlot > 0) {
                    key(openSlot) { SlotScreen(openSlot, openTab, { openTab = it }) { openSlot = 0 } }
                } else SlotsTab { slot, t -> openSlot = slot; openTab = t }
                3 -> if (openSlot > 0) {
                    key(openSlot) { SlotScreen(openSlot, openTab, { openTab = it }) { openSlot = 0 } }
                } else DirectorTab { slot, t -> openSlot = slot; openTab = t }
                4 -> ArtsTab()
                else -> SettingsTab()
            }
        }
    }
}

@Composable
internal fun PendingRow(text: String, button: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 13.sp, color = C.MUTED, modifier = Modifier.weight(1f))
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.height(32.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
        ) { Text(button, fontSize = 12.5.sp) }
    }
}

/**
 * O Android fechou o app à força (a leitura estava ativa e agora está desligada, ou o app travou): explica
 * o motivo e dá os atalhos para não acontecer de novo. Some quando tudo volta ao normal.
 */
@Composable
internal fun KilledBanner(ctx: Context, enabled: Boolean, connected: Boolean, batteryOk: Boolean) {
    val alive = Health.lastAlive(ctx)
    val crash = Health.lastCrash(ctx)
    // o serviço bate a cada minuto: sem batida há 3+ min e sem conexão = foi fechado (não é só o início do app)
    val killed = alive > 0L && System.currentTimeMillis() - alive > 180000L && !(enabled && connected)
    if (!killed && crash == null) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).border(1.dp, C.BAD, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1218))
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (killed) "⚠ O Android fechou o app e desligou a leitura" else "⚠ O app travou",
                fontWeight = FontWeight.Bold, color = C.BAD, fontSize = 14.sp
            )
            Text(
                (if (killed) "A leitura funcionou até ${ago(alive)}. Quando o sistema fecha o app à força (economia de bateria " +
                    "do fabricante), ele desliga a leitura e cancela os lembretes — por isso não chegou aviso. "
                else "") +
                    (if (crash != null) "Último travamento: ${fmtTime(crash.first)} (${crash.second.take(90)}). " else "") +
                    Health.brandSteps(),
                fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)
            )
            if (killed && !enabled) PendingRow("1. Reativar a leitura (Acessibilidade → OSM AI Coach)", "Abrir") {
                openSettings(ctx, android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
            if (!batteryOk) PendingRow("2. Bateria sem restrição", "Bateria") { openBatterySettings(ctx) }
            PendingRow("3. Inicialização automática / não suspender", "Abrir") { Health.openAutostart(ctx) }
            if (crash != null) {
                TextButton(onClick = { Health.clearCrash(ctx) }) { Text("Já vi, ocultar aviso do travamento", fontSize = 12.5.sp) }
            }
        }
    }
}

/** Faixa compacta: só mostra o que ainda falta configurar e fica recolhida quando a leitura já está ativa. */
@Composable
internal fun SetupBanner(ctx: Context, enabled: Boolean, connected: Boolean, batteryOk: Boolean, hasKey: Boolean, onGoSettings: () -> Unit) {
    val ready = enabled && connected
    var open by remember { mutableStateOf(!ready) }
    val pending = (if (ready) 0 else 1) + (if (batteryOk) 0 else 1) + (if (hasKey) 0 else 1)
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).osmPanel().clickable { open = !open },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("⚙ Configuração: $pending pendente(s)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = C.WARN)
                Text(if (open) "▲" else "▼", fontSize = 12.5.sp, color = C.MUTED)
            }
            if (open) {
                if (!ready) {
                    PendingRow("1. Ativar a leitura automática (Acessibilidade → OSM AI Coach)", "Abrir") {
                        openSettings(ctx, android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    }
                    if (!enabled) {
                        PendingRow("Chave cinza? Info do app → ⋮ → Permitir configurações restritas", "Info do app") {
                            openSettings(ctx, android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
                        }
                    } else {
                        Text("Ativado; aguardando o Android conectar o serviço…", fontSize = 12.5.sp, color = C.WARN, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                if (!batteryOk) {
                    PendingRow("Bateria sem restrição (evita o Android desligar a leitura)", "Bateria") {
                        openBatterySettings(ctx)
                    }
                }
                if (!hasKey) PendingRow("Chave de IA (Google AI Studio) para tática e mercado", "Ajustes", onGoSettings)
            }
        }
    }
}

@Composable
internal fun ProcessCard() {
    val tick = rememberTick(500L)
    val now = System.currentTimeMillis()
    val show = ProcessState.running || (ProcessState.finishedAt > 0L && now - ProcessState.finishedAt < 180000L)
    if (!show || tick < 0) return
    Panel {
        if (ProcessState.running) {
            val secs = (now - ProcessState.startedAt) / 1000
            Text("⏳ " + ProcessState.title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            if (ProcessState.total > 0) {
                LinearProgressIndicator(
                    progress = { (ProcessState.done.toFloat() / ProcessState.total.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
                    color = C.PRIMARY, trackColor = C.SURFACE2
                )
                Text("${ProcessState.done} de ${ProcessState.total} • ${secs}s", fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)), color = C.PRIMARY, trackColor = C.SURFACE2)
                Text("${secs}s", fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
            }
            if (AiStatus.stage.isNotBlank()) Text("IA: " + AiStatus.stage, fontSize = 13.sp, color = C.WARN)
        } else {
            Text("✔ Processamento concluído", fontWeight = FontWeight.Bold, color = C.OK)
            Text(ProcessState.summary, fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Pílula de ação (toque): tática pendente em vermelho, resultado pendente em dourado. */
@Composable
internal fun ActionPill(s: SlotSummary, onTactic: () -> Unit, onResult: () -> Unit) {
    when {
        s.needsResult -> Box(Modifier.padding(end = 6.dp, top = 2.dp, bottom = 2.dp).clip(RoundedCornerShape(50)).background(Color(0xFF7A5A00)).clickable { onResult() }.padding(horizontal = 10.dp, vertical = 3.dp)) {
            Text("📊 Registrar resultado →", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        s.tacticReady -> Box(Modifier.clickable { onTactic() }) { Pill("Tática ✔", true) }
        // botão de ação amarelo, como os do OSM
        else -> Box(
            Modifier.padding(end = 6.dp, top = 2.dp, bottom = 2.dp).clip(RoundedCornerShape(8.dp)).background(OsmColors.YELLOW)
                .clickable { onTactic() }.padding(horizontal = 14.dp, vertical = 6.dp)
        ) { Text("⚡ Gerar tática", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0B1C3E)) }
    }
}

/** Tudo o que tem hora, de todos os slots, em ordem: jogos, treinos e obras. */
@Composable
internal fun AgendaCard(data: TodayData) {
    val now = System.currentTimeMillis()
    class Ev(val at: Long, val text: String)
    val evs = ArrayList<Ev>()
    for (s in data.slots) {
        val at = s.nextAt ?: continue
        if (!s.ended && at > now - 3600000L) evs.add(Ev(at, "⚽ S${s.slot} • jogo" + (s.rival?.let { " vs $it" } ?: "")))
    }
    for (w in data.work) {
        for (seat in w.seats) seat.endAt?.takeIf { it > now - 3600000L }?.let {
            evs.add(Ev(it, "🏋 S${w.slot} • treino ${Timers.roleLabel(seat.role)}" + (seat.name?.let { n -> " ($n)" } ?: "")))
        }
        for (c in w.cards) c.endAt?.takeIf { it > now - 3600000L }?.let { evs.add(Ev(it, "🏟 S${w.slot} • obra ${c.label}")) }
    }
    if (evs.isEmpty()) return
    val list = evs.sortedBy { it.at }.take(14)
    val fmt = SimpleDateFormat("EEE HH:mm", Locale.getDefault())
    Fold("📅", "Agenda: o que acontece e quando", Timers.left(list.first().at, now)) {
        for (e in list) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(fmt.format(Date(e.at)), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = C.GOLD, modifier = Modifier.width(84.dp))
                Text(e.text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(Timers.left(e.at, now), fontSize = 12.5.sp, color = C.MUTED)
            }
        }
        Text(
            "🎁 Todo dia: vídeos de Boss Coins grátis no Centro de Negócios e as missões (não gastam moedas).",
            fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/** O que as táticas renderam DE VERDADE: vitórias, empates, derrotas, aproveitamento, contra humano e CPU, e as melhores/piores combinações. */
@Composable
internal fun ResultsCard(s: Results.Summary, scope: String = "") {
    val t = s.total
    val c = if (t.aprov >= 55) C.OK else if (t.aprov >= 35) C.GOLD else C.WARN
    Fold("📊", "Resultados das táticas" + (if (scope.isNotBlank()) " ($scope)" else "") + ": ${t.winPct}% vitórias", "${t.v}V ${t.e}E ${t.d}D") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatTile("🏆", "${t.winPct}%", "vitórias (${t.v} de ${t.n})", c, Modifier.weight(1f))
            StatTile("📈", "${t.aprov}%", "aproveitamento dos pontos", c, Modifier.weight(1f))
            StatTile("⚽", "${t.gf}×${t.ga}", "gols feitos × sofridos", C.PRIMARY, Modifier.weight(1f))
        }
        for (l in listOfNotNull(s.human, s.cpu, s.home, s.away)) {
            val col = if (l.aprov >= 55) C.OK else if (l.aprov >= 35) C.GOLD else C.WARN
            Text("${l.label}: ${l.text()}", fontSize = 13.sp, color = col, modifier = Modifier.padding(top = 6.dp))
        }
        if (s.streak.isNotBlank()) Text("Sequência recente (do mais novo): ${s.streak}", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        if (s.best.isNotEmpty()) {
            Text("Táticas que mais pontuaram", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = C.OK, modifier = Modifier.padding(top = 8.dp))
            for (l in s.best) Text("✔ ${l.label}: ${l.text()}", fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (s.worst.isNotEmpty()) {
            Text("Táticas que menos pontuaram", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = C.WARN, modifier = Modifier.padding(top = 8.dp))
            for (l in s.worst) Text("✘ ${l.label}: ${l.text()}", fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Text("O app usa esses resultados para decidir as próximas táticas (o que perde repetidamente sai das opções).", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Quanto a PREVISÃO do app (V/E/D) acertou o resultado dos jogos já disputados, com dicas do que isso mostra. */
@Composable
internal fun AccuracyCard(s: Accuracy.Summary, scope: String = "") {
    val c = if (s.pct >= 60) C.OK else if (s.pct >= 45) C.GOLD else C.WARN
    Fold("🎯", "Acerto da previsão" + (if (scope.isNotBlank()) " ($scope)" else "") + ": ${s.pct}%", "${s.hits}/${s.games} jogos") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatTile("🎯", "${s.pct}%", "acertou o resultado", c, Modifier.weight(1f))
            StatTile("📈", (if (s.skill >= 0) "+" else "") + s.skill + "%", "melhor que o acaso", if (s.skill > 0) C.OK else C.WARN, Modifier.weight(1f))
            StatTile("⚖", "${s.avgReal}%", "chance dada ao que ocorreu", C.PRIMARY, Modifier.weight(1f))
        }
        Text(
            "Quando previu vitória: ${s.favV.first} de ${s.favV.second} • empate: ${s.favE.first} de ${s.favE.second} • derrota: ${s.favD.first} de ${s.favD.second}",
            fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp)
        )
        if (s.splits.isNotEmpty()) Text(
            "Acerto " + s.splits.joinToString(" • ") { "${it.first}: ${it.second}/${it.third}" },
            fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp)
        )
        Text("Últimos jogos", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        for (g in s.recent) {
            val res = when (g.result) { "V" -> "vitória"; "E" -> "empate"; else -> "derrota" }
            Text(
                (if (g.hit) "✔ " else "✘ ") + "S${g.slot} J${g.round}" + (g.rival?.let { " vs $it" } ?: "") +
                    ": previu V${g.pW}% E${g.pE}% D${g.pD}% → $res" + (if (g.mine != null && g.opp != null) " (${g.mine}-${g.opp})" else ""),
                fontSize = 12.5.sp, color = if (g.hit) C.OK else C.WARN, modifier = Modifier.padding(top = 2.dp)
            )
        }
        for (t in s.tips) Text("💡 $t", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        Text(
            "A cada resultado que você passa, o app corrige os gols esperados e as probabilidades das próximas táticas.",
            fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** Barra de marca no topo de todas as telas: nome do app e se a leitura do OSM está ativa. */
@Composable
internal fun BrandBar() {
    val ctx = LocalContext.current
    val tick = rememberTick(3000L)
    val ready = tick >= 0 && serviceEnabled(ctx) && Diag.serviceConnected
    val col = if (ready) C.OK else C.BAD
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_launcher_fg), contentDescription = null, modifier = Modifier.size(42.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row {
                    Text("OSM AI ", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Coach", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = C.PRIMARY)
                }
                Text("Assistente tático e analítico para o OSM", fontSize = 12.sp, color = C.MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.14f)).border(1.dp, col.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(col))
                Spacer(Modifier.width(6.dp))
                Text(if (ready) "Online" else "Parado", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = col)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x662AB4FF), Color.Transparent))))
    }
}

/** Título de página como nas referências: ícone, título grande com traço ciano e um chip à direita. */
@Composable
internal fun PageTitle(icon: String, title: String, chip: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 26.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Box(Modifier.padding(top = 2.dp).width(44.dp).height(3.dp).clip(RoundedCornerShape(50)).background(C.PRIMARY))
        }
        if (chip != null) Box(
            Modifier.clip(RoundedCornerShape(14.dp)).background(C.SURFACE).border(1.dp, Color(0x552AB4FF), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp)
        ) { Text(chip, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
    }
}


/** "Para fazer agora": só o que precisa de você, em ordem de urgência, de todos os slots. */
@Composable
internal fun TodoCard(data: TodayData, onOpenSlot: (Int, Int) -> Unit) {
    val now = System.currentTimeMillis()
    class Todo(val slot: Int, val icon: String, val title: String, val sub: String, val urgency: Int, val tab: Int)
    val todo = ArrayList<Todo>()
    for (s in data.slots) {
        if (s.ended || s.state != null) continue
        val at = s.nextAt
        if (s.needsResult) todo.add(Todo(s.slot, "📊", "Registrar o resultado do jogo", "O jogo já passou: o app aprende com o placar", 5, 5))
        if (at != null && at > now && (s.tacticStale || s.rivalGaps) && !s.tacticReady && at - now <= Director.TACTIC_WINDOW_MS)
            todo.add(Todo(s.slot, "🔎", "Reler Pré-jogo e Análise do rival", "Confira se ele mudou antes de usar a tática (jogo em ${countdownShort(at)})", if (at - now <= 3600000L) 6 else 4, 4))
        else if (at != null && at > now && !s.tacticReady && at - now <= Director.TACTIC_WINDOW_MS)
            todo.add(Todo(s.slot, "⚡", "Gerar a tática", "Jogo em ${countdownShort(at)}", if (at - now <= 3600000L) 6 else 4, 4))
        if (s.rivalChanged.isNotEmpty()) todo.add(Todo(s.slot, "⚠", "O rival mudou a tática", "Gere a tática de novo antes do jogo", 6, 4))
        if (s.tacticReady && !s.tacticStale && !s.mounted && at != null && at > now && at - now <= Director.TACTIC_WINDOW_MS) todo.add(if (s.mountDiff.isNotEmpty()) Todo(s.slot, "📋", "Ajustar a tática montada no OSM", "Li a Tática do jogo e está diferente da gerada: " + s.mountDiff.joinToString(" • "), 3, 4)
            else Todo(s.slot, "📋", "Montar a tática no OSM", "Já está pronta: abra a tela de Tática do jogo", 3, 4))
    }
    for (w in data.work) {
        val idle = w.seats.filter { it.role != "UNI" && (it.state == "empty" || it.state == "done" || (it.endAt != null && it.endAt <= now)) }
        if (idle.isNotEmpty()) todo.add(Todo(w.slot, "🏋", "Treinador parado: " + idle.joinToString { Timers.roleLabel(it.role) }, "Treino parado é evolução perdida", 3, 6))
    }
    // "Reler Pré-jogo e Análise do rival" já cobre essas duas telas: não repete como "Atualizar: Pré-jogo/Análise" no mesmo slot
    val reread = todo.filter { it.title.startsWith("Reler Pré-jogo") }.map { it.slot }.toSet()
    for (n in data.needs.filter { it.urgency >= 1 && !(it.slot in reread && (it.type == ScreenType.PREGAME || it.type == ScreenType.REPORT)) }) {
        todo.add(Todo(n.slot, "🔄", "Atualizar: " + (Fresh.NAME[n.type] ?: n.type.name), n.reason.substringBefore(":").replaceFirstChar { it.uppercase() }, 2, 0))
    }
    val sorted = todo.sortedWith(compareByDescending<Todo> { it.urgency }.thenBy { it.slot })
    Column(Modifier.fillMaxWidth().padding(top = 14.dp).osmPanel().padding(vertical = 8.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Para fazer agora", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            if (sorted.isNotEmpty()) Pill("${sorted.size}", false)
        }
        if (sorted.isEmpty()) {
            Text("✅ Tudo em dia. Nada precisa de você agora.", fontSize = 14.sp, color = C.OK, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        for (t in sorted.take(6)) {
            Row(
                Modifier.fillMaxWidth().clickable { onOpenSlot(t.slot, t.tab) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(C.SURFACE2), contentAlignment = Alignment.Center) { Text(t.icon, fontSize = 18.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(t.sub, fontSize = 13.sp, color = C.MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Text("S${t.slot}", fontSize = 13.sp, color = C.GOLD, fontWeight = FontWeight.ExtraBold)
                Text("  ›", fontSize = 18.sp, color = C.MUTED)
            }
        }
        if (sorted.size > 6) Text("+ ${sorted.size - 6} outras pendências", fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    }
}

/** Círculo com a força do time (como no pré-jogo do OSM). */
@Composable
internal fun StrengthBadge(value: Int?, size: Dp = 34.dp, highlight: Boolean = false) {
    val col = if (highlight) C.PRIMARY else Color(0x992AB4FF)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(50)).background(Color(0xFF0A1830)).border(2.dp, col, RoundedCornerShape(50)),
        contentAlignment = Alignment.Center
    ) { Text(value?.toString() ?: "?", fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.ExtraBold, color = if (value == null) C.MUTED else Color.White) }
}

/** Barra fina com rótulo e valor (confiança, dados lidos, tática). */
@Composable
internal fun MetricBar(label: String, pct: Int, valueText: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(valueText, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = color)
        }
        Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Color(0xFF14264A))) {
            Box(Modifier.fillMaxWidth(pct.coerceIn(0, 100) / 100f).height(6.dp).clip(RoundedCornerShape(50)).background(color))
        }
    }
}

/** Botão grande ciano → azul, como nas referências. */
@Composable
internal fun CtaButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Brush.horizontalGradient(if (enabled) listOf(Color(0xFF19C3FF), Color(0xFF1B6BFF)) else listOf(Color(0xFF28406B), Color(0xFF28406B))))
            .clickable(enabled = enabled) { onClick() }.padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Text("  ›", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

/** Botão de contorno ciano (ação secundária). */
@Composable
internal fun GhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF0A1830)).border(1.3.dp, Color(0x802AB4FF), RoundedCornerShape(14.dp))
            .clickable { onClick() }.padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.PRIMARY) }
}

/** Cartão do slot: quem joga contra quem (com a força de cada um), quanto falta, três barras e o botão da ação certa. */
@Composable
internal fun SlotCardV2(s: SlotSummary, onOpen: () -> Unit, onTab: (Int) -> Unit) {
    val now = System.currentTimeMillis()
    val at = s.nextAt?.takeIf { it > now && !s.ended && s.state == null }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).osmPanel().clickable { onOpen() }.padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Brush.horizontalGradient(listOf(Color(0xFF1E88E5), Color(0xFF1565C0)))).padding(horizontal = 10.dp, vertical = 5.dp)) {
                Text("S${s.slot}", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (s.known) Color.White else C.MUTED)
                val kind = when (s.compType) { "Batalha" -> "⚔ Batalha"; "Copa" -> "🏆 Copa"; else -> null }
                val flagE = Flags.emojiOfCompetition(s.competition)
                val sub = listOfNotNull(kind, s.competition?.let { c -> if (flagE != null) "$flagE $c" else c }, if (s.roundDone != null && s.roundTotal != null) "Rodada ${s.roundDone}/${s.roundTotal}" else null).joinToString(" • ")
                if (sub.isNotBlank() && !(s.ended && s.state == null)) Text(sub, fontSize = 13.sp, color = C.MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            s.win?.takeIf { at != null }?.let { w -> VerdictPill(verdictOf(w.win, w.draw, w.loss)) }
        }
        if (s.state != null || s.ended || at == null) {
            val msg = when {
                s.state == "vazio" -> "➕ Sem liga ou batalha. Comece uma no OSM; o app acompanha sozinho."
                s.state == "preepoca" -> "🗓 Pré-época: a competição nova começa em breve."
                s.state != null -> "🔎 Procurando adversário. Quando aparecer a jornada, o slot volta ao normal."
                s.ended -> "🏁 Competição encerrada. Aguardando a próxima."
                s.needsResult -> "📊 O jogo já passou: registre o resultado para o app aprender."
                else -> "📖 Leia o Pré-jogo e o Calendário no OSM para o app saber o próximo jogo."
            }
            Text(msg, fontSize = 13.5.sp, color = C.MUTED, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0A1830)).padding(12.dp))
            if (s.needsResult) CtaButton("Registrar resultado", Modifier.padding(top = 12.dp)) { onTab(5) } else CtaButton("Abrir o slot", Modifier.padding(top = 12.dp)) { onTab(0) }
            return@Column
        }
        // quem joga contra quem: o SEU time (marcado "VOCÊ") fica à esquerda em casa e à direita fora, como no OSM
        val mineLeft = s.home != false
        val mineCol: @Composable (Modifier) -> Unit = { m ->
            Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.BottomEnd) { Crest(s.slot, 48.dp, s.title); Box(Modifier.offset(x = 6.dp, y = 6.dp)) { StrengthBadge(s.myStr, 26.dp, true) } }
                Text(s.title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(RoundedCornerShape(50)).background(C.PRIMARY.copy(alpha = 0.25f)).border(1.dp, C.PRIMARY.copy(alpha = 0.7f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 1.dp)) {
                        Text("VOCÊ", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    }
                    s.home?.let { Text(if (it) "  🏠 casa" else "  ✈ fora", fontSize = 11.sp, color = C.MUTED) }
                }
            }
        }
        val rivalCol: @Composable (Modifier) -> Unit = { m ->
            Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.BottomEnd) { RivalShield(s.rival, 48.dp, s.slot); Box(Modifier.offset(x = 6.dp, y = 6.dp)) { StrengthBadge(s.rivalStr, 26.dp) } }
                Text(s.rival ?: "rival não lido", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp), color = if (s.rival != null) Color.White else C.WARN)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (mineLeft) mineCol(Modifier.weight(1f)) else rivalCol(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("VS", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = C.MUTED)
                Column(
                    Modifier.padding(top = 4.dp).clip(RoundedCornerShape(14.dp)).background(countdownColor(at!!).copy(alpha = 0.14f)).border(1.dp, countdownColor(at).copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Faltam", fontSize = 11.5.sp, color = C.MUTED)
                    Text(countdownShort(at), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = countdownColor(at), maxLines = 1)
                }
            }
            if (mineLeft) rivalCol(Modifier.weight(1f)) else mineCol(Modifier.weight(1f))
        }
        s.human?.let { Text(if (it == "Sim") "👤 rival humano" else "🖥 rival CPU", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = TextAlign.Center) }
        // forma recente dos dois e preparação, como no pré-jogo do OSM
        if (s.myForm != null || s.rivalForm != null || s.prepLevel != null) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { FormDots(if (mineLeft) s.myForm else s.rivalForm) }
                PrepChip(s.prepLevel)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { FormDots(if (mineLeft) s.rivalForm else s.myForm) }
            }
        }
        // vitória / empate / derrota (previsão calibrada com os seus jogos) + dados lidos e tática
        WdlBar(s.win, Modifier.padding(top = 12.dp))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricBar("Dados lidos", s.pct, "${s.pct}%", if (s.pct >= 80) C.OK else if (s.pct >= 50) C.GOLD else C.WARN, Modifier.weight(1f))
            MetricBar("Tática", if (s.tacticReady) 100 else if (s.tacticStale) 50 else 0, if (s.tacticReady) "pronta" else if (at!! - now > Director.TACTIC_WINDOW_MS) "só nas 8 h antes" else if (s.tacticStale) "reler rival" else "falta", if (s.tacticReady) C.OK else C.GOLD, Modifier.weight(1f))
        }
        TacticStatusLine(s)
        val inWindow = at!! - now <= Director.TACTIC_WINDOW_MS
        when {
            inWindow && (s.tacticStale || (!s.tacticReady && s.rivalGaps)) -> CtaButton("🔎 Ler o rival antes da tática",  Modifier.padding(top = 12.dp)) { onTab(4) }
            !s.tacticReady && inWindow -> CtaButton("⚡ Gerar tática", Modifier.padding(top = 12.dp)) { onTab(4) }
            s.tacticReady -> CtaButton("Ver a tática", Modifier.padding(top = 12.dp)) { onTab(4) }
            else -> CtaButton("Abrir o slot", Modifier.padding(top = 12.dp)) { onTab(0) }
        }
    }
}


/** Bolinhas V/E/D como no pré-jogo do OSM: verde vitória, laranja empate, vermelho derrota. */
/** Chance de vitória, empate e derrota: barra única proporcional e três colunas com o número grande. */
@Composable
internal fun WdlBar(p: WinModel.Prob?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        if (p == null) {
            Text("Chance de vitória • aparece quando a tática é gerada", fontSize = 12.5.sp, color = C.MUTED)
            return@Column
        }
        // três cartões, cada um com a SUA cor, o seu número e a sua barrinha (antes uma barra única empilhada e os números em 3 colunas iguais
        // pareciam ligados a trechos errados: o "Empate" caía embaixo do verde da vitória)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, v, col) in listOf(Triple("Vitória", p.win, C.WIN), Triple("Empate", p.draw, C.DRAW), Triple("Derrota", p.loss, C.LOSS))) {
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(col.copy(alpha = 0.14f))
                        .border(1.dp, col.copy(alpha = 0.55f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = col)
                    Text("$v%", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF))) {
                        Box(Modifier.fillMaxWidth((v / 100f).coerceIn(0.03f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(col))
                    }
                }
            }
        }
    }
}

@Composable
internal fun FormDots(form: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        if (form.isNullOrBlank()) Text("—", fontSize = 12.sp, color = C.MUTED)
        else for (c in form.take(5)) {
            val col = when (c) { 'V' -> C.OK; 'E' -> C.GOLD; else -> C.BAD }
            Box(Modifier.size(17.dp).clip(RoundedCornerShape(50)).background(col), contentAlignment = Alignment.Center) {
                Text(c.toString(), fontSize = 9.5.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF081A3C))
            }
        }
    }
}

/** Preparação para o jogo (barra do pré-jogo do OSM): vermelho crítico, laranja fraco, amarelo razoável, verde bom. */
@Composable
internal fun PrepChip(level: String?) {
    val lv = PrepLevel.canon(level) ?: return
    val col = when (PrepLevel.rank(lv)) { 0 -> C.BAD; 1 -> Color(0xFFFF8F1F); 2 -> C.GOLD; else -> C.OK }
    Text(
        "Preparação: $lv", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = col,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.14f)).border(1.dp, col.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

/** Três "passos" de leitura do rival, em pílulas: o que já foi lido (verde) e o que falta (amarelo). */
@Composable
internal fun ReadChips(s: SlotSummary) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((i, pair) in listOf(s.readPre to "Pré-jogo", s.readAna to "Análise", s.readSquad to "Rival", s.readMine to "Meu time").withIndex()) {
            val (ok, label) = pair
            val col = if (ok) C.OK else C.WARN
            val ageMs = s.readAges.getOrNull(i)
            val age = if (ok && ageMs != null) " · " + (if (ageMs < 3_600_000L) "${maxOf(1L, ageMs / 60_000L)} min" else "${ageMs / 3_600_000L} h") else ""
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.14f)).border(1.dp, col.copy(alpha = 0.55f), RoundedCornerShape(50)).padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
            ) { Text((if (ok) "✓ " else "○ ") + label + age, fontSize = 11.5.sp, color = col, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
    }
}

/** Uma linha que diz o que está acontecendo com a tática deste jogo (gerando, pronta, rival mudou, longe do jogo). */
@Composable
internal fun TacticStatusLine(s: SlotSummary) {
    val now = System.currentTimeMillis()
    val at = s.nextAt ?: return
    if (at <= now || s.ended) return
    val far = at - now > Director.TACTIC_WINDOW_MS
    val (icon, text, col) = when {
        GenState.running("tactic", s.slot) || GenState.running("tactic_ai", s.slot) -> Triple("⚙", "Gerando a tática…", C.PRIMARY)
        s.rivalChanged.isNotEmpty() -> Triple("⚠", "O rival mudou: gere a tática de novo", C.WARN)
        s.mounted && s.tacticReady -> Triple("✅", "Tática montada no OSM: confere com a gerada", C.OK)
        s.tacticStale -> Triple("🔎", "Releia o rival (passos abaixo) antes de confiar na tática", C.WARN)
        s.tacticReady && s.autoChk != null && s.autoChk > (s.autoAt ?: 0L) -> Triple("✅", "Análise do rival conferida ${ago(s.autoChk)}: não mudou, a tática continua valendo", C.OK)
        s.tacticReady && s.autoAt != null -> Triple("✅", "Tática gerada ${ago(s.autoAt)}: monte no OSM antes do jogo", C.OK)
        s.tacticReady -> Triple("✅", "Tática pronta: monte no OSM antes do jogo", C.OK)
        far -> Triple("⏳", "Jogo longe: a tática é gerada nas últimas 8 h antes dele", C.MUTED)
        s.rivalGaps -> Triple("🔎", "Antes de gerar a tática, leia o rival (passos abaixo)", C.WARN)
        else -> Triple("⚡", "Falta gerar a tática deste jogo", C.GOLD)
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 14.sp)
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 13.sp, color = col, fontWeight = FontWeight.Medium)
    }
    ReadChips(s)
}


@Composable
internal fun TodayTab(onGoSettings: () -> Unit, onOpenSlot: (Int, Int) -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberTick(1000L)
    val scope = rememberCoroutineScope()
    // dados a cada 5 s (ou na hora, com dado novo); o relógio da tela continua a cada 1 s
    val data = rememberLoaded(TodayCache.last, tick / 5, UiBus.version) { loadToday(ctx) }
    var msg by remember { mutableStateOf("") }
    val enabled = serviceEnabled(ctx)
    val connected = Diag.serviceConnected
    val ready = enabled && connected
    val batteryOk = batteryUnrestricted(ctx)
    val hasKey = Settings.get(ctx, Settings.GEMINI_KEY, "").isNotBlank()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 96.dp)) {
        PageTitle("🏠", "Hoje", SimpleDateFormat("EEE, d 'de' MMM", Locale("pt", "BR")).format(Date()).replaceFirstChar { it.uppercase() })

        KilledBanner(ctx, enabled, connected, batteryOk)
        if (!ready || !batteryOk || !hasKey) SetupBanner(ctx, enabled, connected, batteryOk, hasKey, onGoSettings)

        Spacer(Modifier.height(8.dp))
        if (data.active) {
            Button(
                onClick = {
                    AppScope.scope.launch { Control.end(ctx.applicationContext) }
                    msg = "Captura encerrada. Processando em segundo plano…"
                },
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = C.BAD)
            ) { Text("ENCERRAR CAPTURA E PROCESSAR", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { if (!openOsm(ctx)) msg = "OSM não encontrado." }, modifier = Modifier.fillMaxWidth()) {
                Text("Voltar ao OSM (a mesma sessão continua)")
            }
            Panel {
                Text("🔴 Capturando • " + typeLabel(Diag.currentType), color = C.OK, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatTile("🎯", if (Diag.currentSlot > 0) "S${Diag.currentSlot}" else "?", "slot", C.GOLD, Modifier.weight(1f))
                    StatTile("📸", Diag.valid.get().toString(), "telas lidas", C.PRIMARY, Modifier.weight(1f))
                    StatTile("✨", Diag.extracted.get().toString(), "dados novos", C.OK, Modifier.weight(1f))
                }
            }
        } else {
            Button(
                onClick = {
                    scope.launch {
                        Control.start(ctx)
                        if (!openOsm(ctx)) msg = "OSM não encontrado neste aparelho."
                    }
                },
                enabled = ready,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(20.dp)
            ) { Text("▶  Abrir OSM e capturar", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            if (!ready) Text("⚙ Ative a leitura (acima) para liberar o botão.", color = C.WARN, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            if (data.slots.none { it.known }) Panel { HowItWorks() }
        }
        if (msg.isNotBlank()) Text(msg, color = C.WARN, modifier = Modifier.padding(top = 8.dp))
        ProcessCard()

        TodoCard(data, onOpenSlot)
        NewsCard()
        Title("Seus slots")
        for (s in data.slots.sortedBy { sl -> sl.slot }) SlotCardV2(s, { onOpenSlot(s.slot, 0) }, { t -> onOpenSlot(s.slot, t) })
        AgendaCard(data)
        data.results?.let { ResultsCard(it) }
        data.accuracy?.let { AccuracyCard(it) }
        NoFlagFold()
        LearningCard()
        if (data.work.isNotEmpty()) {
            Fold("⏱", "Treinos e estádio", data.work.size.toString() + " slots") {
                for (w in data.work) WorkFold(w) { onOpenSlot(w.slot, 5) }
            }
        }
        Fold("💰", "Coins: ganhar e usar Boss Coins") { CoinsContent() }
        Fold("⋯", "Mais: guia das telas, eventos e diagnóstico") {
            Fold("🗺", "Guia: o que cada tela do OSM alimenta") {
                Text("Passe por estas telas com a captura ligada. Vale para todos os slots (repita em cada um).", fontSize = 13.sp, color = C.MUTED)
                for ((t, gives, every) in Fresh.GUIDE) {
                    Column(Modifier.padding(top = 8.dp)) {
                        Text(Fresh.NAME[t] ?: t.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(gives, fontSize = 12.5.sp, color = C.MUTED)
                        Text("Quando: $every", fontSize = 12.5.sp, color = C.GOLD)
                        Text(Fresh.PATH[t] ?: "", fontSize = 12.5.sp, color = C.PRIMARY)
                    }
                }
            }
            val ev = Diag.recentEvents()
            Fold("🕘", "Últimos eventos", if (ev.isEmpty()) null else ev.size.toString()) {
                if (ev.isEmpty()) Text("Nenhum evento ainda. Inicie a captura e navegue no OSM.", color = C.MUTED, fontSize = 13.sp)
                for (e in ev) Text(e, fontSize = 13.sp, modifier = Modifier.padding(vertical = 1.dp))
            }
            Fold("🩺", "Diagnóstico técnico", if (ready) "ok" else "⚠") {
                Column {
                    KV("Acessibilidade ativada", if (enabled) "sim" else "NÃO")
                    KV("Serviço realmente conectado", if (connected) "sim" else "NÃO")
                    KV("Pacote do jogo", if (Diag.lastOsmEventAt > 0) "$OSM_PACKAGE (evento ${ago(Diag.lastOsmEventAt)})" else "sem eventos do OSM")
                    KV("Sessão atual", Control.activeSession(ctx) ?: "nenhuma")
                    KV("Slot atual", if (Diag.currentSlot > 0) "S${Diag.currentSlot}" else "não identificado")
                    KV("Tipo da tela atual", typeLabel(Diag.currentType))
                    KV("Último screenshot", ago(Diag.lastShotAt))
                    KV("Frames válidos", Diag.valid.get().toString())
                    KV("Frames descartados", Diag.discarded.get().toString())
                    KV("Deduplicados", Diag.dedup.get().toString())
                    KV("OCR realizado", Diag.ocr.get().toString())
                    KV("Parser aplicado", Diag.parsed.get().toString())
                    KV("Campos extraídos/alterados", Diag.extracted.get().toString())
                    KV("Sem slot (preservados)", Diag.unassigned.get().toString())
                    KV("Chamadas de IA (sessão)", Diag.aiCalls.get().toString())
                    KV("Última atualização de dados", ago(Diag.lastUpdateAt))
                    KV("Último erro", Diag.lastError ?: "nenhum")
                    KV("Último travamento", Health.lastCrash(ctx)?.let { fmtTime(it.first) + " — " + it.second } ?: "nenhum")
                    KV("Leitura viva pela última vez", ago(Health.lastAlive(ctx)))
                    KV("Aparelho", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " • Android " + android.os.Build.VERSION.RELEASE)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
internal fun Thumb(path: String?) {
    val bmp = remember(path) {
        if (path != null && File(path).exists()) {
            val o = BitmapFactory.Options()
            o.inSampleSize = 4
            BitmapFactory.decodeFile(path, o)?.asImageBitmap()
        } else null
    }
    if (bmp != null) {
        Image(bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.width(110.dp).height(62.dp).clip(RoundedCornerShape(8.dp)))
    } else {
        Box(Modifier.width(110.dp).height(62.dp).clip(RoundedCornerShape(8.dp)).background(C.SURFACE2))
    }
}

@Composable
internal fun SavedScreens(tick: Int) {
    val ctx = LocalContext.current
    val ai = rememberLoaded(emptyList<ScreenEntity>(), tick, UiBus.version) { Repo(ctx).dao.aiScreens(30) }
    val archive = rememberLoaded(emptyList<ScreenEntity>(), tick, UiBus.version) { Repo(ctx).dao.savedScreens(12) }
    val hasKey = AiClient.hasAnyKey(ctx)
    val waiting = ai.count { it.aiState == "pending" }
    val done = ai.count { it.aiState == "done" }
    val failed = ai.count { it.aiState == "failed" }
    Title("🤖 Leitura com IA")
    if (!hasKey) AlertCard("⚠ Sem chave de IA: coloque a chave do Gemini (grátis) em Ajustes para a IA ler essas telas.")
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatTile("⏳", waiting.toString(), "na fila", C.GOLD, Modifier.weight(1f))
        StatTile("✅", done.toString(), "lidas", C.OK, Modifier.weight(1f))
        StatTile("⚠", failed.toString(), "com erro", C.LOSS, Modifier.weight(1f))
    }
    Button(
        onClick = {
            if (!ProcessState.running) {
                val app = ctx.applicationContext
                AppScope.scope.launch(Dispatchers.IO) {
                    try {
                        // as que deram erro voltam para a fila
                        val dao = Repo(app).dao
                        for (s in dao.aiScreens(30)) if (s.aiState == "failed" || (s.aiState == "skipped" && s.note.contains("chave"))) dao.updateScreenAi(s.id, "pending", s.extracted, s.note)
                        Processor.run(app, "manual")
                    } catch (e: Exception) {
                        ProcessState.finish("Erro: " + (e.message ?: "?"))
                    }
                    withContext(Dispatchers.Main) { UiBus.version++ }
                }
            }
        },
        enabled = !ProcessState.running && hasKey && (waiting + failed) > 0,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) { Text("🤖 Ler com IA agora (${waiting + failed})") }
    Hint(
        "O OCR do celular lê quase tudo sozinho. A IA só entra no que ele não conseguiu: pré-jogo sem força/bônus/rival, " +
            "relatório do rival sem formação ou estilo e plantel com nomes ilegíveis. As mais novas são lidas primeiro; telas que " +
            "já não precisam (o OCR completou depois, jogo que já passou) são dispensadas sem gastar a cota.",
        "para que serve"
    )
    if (ai.isEmpty()) Text("Nenhuma tela na fila da IA.", color = C.MUTED, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
    for (s in ai.take(12)) {
        val (icon, label, col) = when (s.aiState) {
            "done" -> Triple("✅", if (s.extracted > 0) "lida pela IA" else "lida (sem dado novo)", C.OK)
            "pending" -> Triple("⏳", if (s.slotId == 0) "espera o slot" else "na fila", C.GOLD)
            "skipped" -> Triple("⏭", if (s.note.contains("chave")) "não lida (sem chave)" else "dispensada", C.MUTED)
            else -> Triple("⚠", "erro na IA", C.LOSS)
        }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumb(s.imagePath)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(typeLabel(s.type) + " • " + (if (s.slotId > 0) "S${s.slotId}" else "sem slot"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        Box(Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.2f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                            Text("$icon $label", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = col)
                        }
                        Text("  " + fmtTime(s.at), fontSize = 12.5.sp, color = C.MUTED)
                    }
                    if (s.note.isNotBlank() && s.aiState != "pending") Text(s.note, fontSize = 12.5.sp, color = C.MUTED, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (s.slotId > 0 && s.aiState != "pending") {
                TextButton(onClick = { startReadOne(ctx, s.id) }, enabled = !ProcessState.running && hasKey) { Text("🔁 Ler de novo com IA", fontSize = 13.sp) }
            }
        }
    }
    Fold("🗂", "Telas guardadas (diagnóstico)", archive.size.toString()) {
        Text("Imagens guardadas para conferir a leitura e ir no diagnóstico. A IA lê sozinha as que precisam (acima).", fontSize = 12.5.sp, color = C.MUTED)
        for (s in archive) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumb(s.imagePath)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(typeLabel(s.type) + " • " + (if (s.slotId > 0) "S${s.slotId}" else "sem slot"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(fmtTime(s.at), fontSize = 12.5.sp, color = C.MUTED)
                }
                if (s.slotId > 0) TextButton(onClick = { startReadOne(ctx, s.id) }, enabled = !ProcessState.running && hasKey) { Text("IA", fontSize = 13.sp) }
            }
        }
    }
}

@Composable
internal fun SessionsBody() {
    val ctx = LocalContext.current
    val tick = rememberTick(2000L)
    val rows = rememberLoaded(emptyList<SessionRow>(), tick, null) {
        val dao = Repo(ctx).dao
        dao.sessions().map { sess ->
            val counts = dao.typeCounts(sess.id).joinToString(" • ") { "${typeLabel(it.type)} ${it.c}" }
            SessionRow(sess, counts, dao.unassignedCount(sess.id))
        }
    }
    Column(Modifier.fillMaxWidth()) {
        // resumo: o que o app já guardou
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("📼", rows.size.toString(), "capturas", C.PRIMARY, Modifier.weight(1f))
            StatTile("🕒", rows.firstOrNull()?.s?.startedAt?.let { fmtTime(it).substringAfter(" ") } ?: "—", "última", C.GOLD, Modifier.weight(1f))
            StatTile("❓", rows.sumOf { it.unassigned }.toString(), "sem slot", if (rows.sumOf { it.unassigned } > 0) C.WARN else C.OK, Modifier.weight(1f))
        }
        ProcessCard()
        Title("Ferramentas")
        GhostButton("Reprocessar telas sem slot") {
            if (!ProcessState.running) {
                val app = ctx.applicationContext
                AppScope.scope.launch(Dispatchers.IO) {
                    try {
                        Processor.run(app, "manual")
                    } catch (e: Exception) {
                        ProcessState.finish("Erro: " + (e.message ?: "?"))
                    }
                }
            }
        }
        GhostButton("🔁 Reprocessar a última captura inteira", Modifier.padding(top = 8.dp)) {
            if (!ProcessState.running) {
                val app = ctx.applicationContext
                AppScope.scope.launch(Dispatchers.IO) {
                    try {
                        val repo = Repo(app)
                        val last = repo.dao.sessions().firstOrNull { it.endedAt != null } ?: repo.dao.sessions().firstOrNull()
                        if (last == null) {
                            ProcessState.begin("Reprocessando a última captura…", 0)
                            ProcessState.finish("Nenhuma captura guardada ainda.")
                            return@launch
                        }
                        ProcessState.begin("Reprocessando a última captura (${fmtTime(last.startedAt)})…", 1)
                        val r = FramePipeline.get(app).replaySession(last.id) { i, n ->
                            ProcessState.total = n
                            ProcessState.done = i
                            ProcessState.label("Relendo tela ${minOf(i + 1, n)} de $n da captura de ${fmtTime(last.startedAt)}…")
                        }
                        withContext(Dispatchers.Main) { UiBus.version++ }
                        ProcessState.finish(
                            "Captura de ${fmtTime(last.startedAt)} relida: ${r.read} de ${r.screens} telas guardadas, ${r.fields} campo(s) atualizados" +
                                (if (r.bySlot.isNotEmpty()) " (" + r.bySlot.toSortedMap().entries.joinToString { "S${it.key}: ${it.value}" } + ")" else "") +
                                ". Só as telas com imagem guardada podem ser relidas."
                        )
                    } catch (e: Exception) {
                        ProcessState.finish("Erro ao reprocessar: " + (e.message ?: e.javaClass.simpleName))
                    }
                }
            }
        }
        Hint(
            "Relê com a versão atual do app todas as telas guardadas da última captura (as mesmas que vão no diagnóstico) e " +
                "aplica nos slots. Dado mais novo nunca é trocado por leitura antiga."
        )
        SavedScreens(tick)
        Title("Histórico")
        if (rows.isEmpty()) Text("Nenhuma sessão ainda.", color = C.MUTED)
        for (r in rows) {
            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).osmPanel().padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(fmtTime(r.s.startedAt), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                        Text(r.s.endedAt?.let { "até " + fmtTime(it) } ?: "em andamento", fontSize = 13.sp, color = C.MUTED)
                    }
                    Pill(if (r.s.endedAt == null) "● gravando" else "concluída", r.s.endedAt == null)
                }
                Text(r.counts.ifBlank { "sem quadros válidos" }, fontSize = 13.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
                if (r.unassigned > 0) Text("⚠ ${r.unassigned} tela(s) sem slot", fontSize = 13.sp, color = C.WARN, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
internal fun SlotsTab(onOpen: (Int, Int) -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberTick(5000L)
    val data = rememberLoaded(TodayCache.last, tick, UiBus.version) { loadToday(ctx) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 96.dp)) {
        PageTitle("📚", "Slots", "${data.slots.count { it.known }} ativos")
        for (s in data.slots.sortedBy { sl -> sl.slot }) SlotCardV2(s, { onOpen(s.slot, 0) }, { t -> onOpen(s.slot, t) })
    }
}

data class DirSlot(
    val s: SlotSummary,
    val forecast: String,
    val sells: List<String>,
    val buys: List<String>,
    val trains: List<String>,
    val radar: String?,
    val cash: String,
    val sellSlots: Int,
    val alerts: List<String> = emptyList(),
    val stadium: String? = null,
    val goal: String? = null,
    /** A primeira coisa a fazer neste slot para o time evoluir. */
    val next: String? = null,
    /** Semana em uma linha: valor, caixa e treino. */
    val week: String? = null,
    /** Dicas primeiro: o que fazer agora para evoluir rápido (dados do slot + o que a comunidade confirma). */
    val tips: List<Playbook.Tip> = emptyList()
)

internal suspend fun loadDirector(ctx: Context): List<DirSlot> {
    val repo = Repo(ctx)
    val today = loadToday(ctx)
    val out = ArrayList<DirSlot>()
    // ganho de força por dia de treino, por idade, medido nos 4 slots
    val trainRates = Hints.trainRates(repo)
    for (s in today.slots) {
        val f = repo.fieldMap(s.slot)
        val inp = Director.tacticInput(repo, s.slot)
        val ready = inp.players.count { it.strength != null } >= 8
        val res = if (ready) TacticEngine.recommend(inp) else null
        val w = s.win
        val forecast = if (s.ended) "competição encerrada — sem próximo jogo" else if (res == null) "faltam dados do elenco (leia o Plantel do seu time)" else {
            res.tactic.formation + " • " + res.tactic.playStyle +
                (if (w != null) " • V ${w.win}% / E ${w.draw}% / D ${w.loss}%" else "")
        }
        val plan = Director.marketPlan(repo, s.slot)
        val alerts = ArrayList<String>()
        if (s.ended) alerts.add("🏁 S${s.slot}: competição encerrada. Quando a nova começar, abra o pré-jogo: o app arquiva a antiga e recomeça o calendário.")
        val nextIn = s.nextAt?.let { it - System.currentTimeMillis() }
        if (s.tacticStale && nextIn != null && nextIn in 0L..10800000L) alerts.add("🔎 S${s.slot}: jogo em ${countdown(s.nextAt ?: 0L)}: a tática já foi gerada, mas falta reler o rival (Pré-jogo e Análise) para confirmar que ele não mudou.")
        else if (!s.tacticReady && nextIn != null && nextIn in 0L..10800000L) alerts.add("⚠ S${s.slot}: jogo em ${countdown(s.nextAt ?: 0L)} e a tática ainda não foi gerada.")
        if (plan != null) {
            val free = 5 - plan.trainingActive
            if (free > 0 && plan.train.isNotEmpty()) alerts.add("🔵 S${s.slot}: $free treino(s) livre(s) — ${plan.train.joinToString(", ") { it.name }}.")
            if (plan.sell.isNotEmpty()) alerts.add("🔴 S${s.slot}: ${plan.sell.size} venda(s) sugerida(s) para liberar caixa.")
        }
        if (s.missing > 0 && !s.ended) alerts.add("📋 S${s.slot}: faltam ${s.missing} campos de leitura (toque no slot para preencher).")
        if (s.needsResult) alerts.add("📊 S${s.slot}: o jogo do calendário já aconteceu — registre o resultado (ou abra a análise do jogo no OSM).")
        if (w != null && w.win < 35 && !s.needsResult) alerts.add("⚠ S${s.slot}: jogo difícil (vitória ${w.win}%) — confira a tática e os treinos antes.")
        alerts.addAll(Director.priceDrops(repo, s.slot))
        val stadiumLine = Director.stadiumPlan(f)
        if (stadiumLine != null && stadiumLine.contains("cabe no caixa")) alerts.add("🏟 S${s.slot}: um melhoramento do estádio cabe no caixa agora.")
        val gp = f[K.MY_OBJECTIVE]?.value?.toIntOrNull()
        val np = f[K.LEAGUE_POS]?.value?.toIntOrNull()
        val goalLine = if (gp != null && np != null) {
            if (np <= gp) "🎯 Meta: top $gp • hoje ${np}º ✔" else "🎯 Meta: top $gp • hoje ${np}º ⚠ (${np - gp} posição(ões) fora)"
        } else null
        if (gp != null && np != null && np > gp) alerts.add("🎯 S${s.slot}: fora da meta da temporada (top $gp, hoje ${np}º).")
        val top = plan?.radar?.maxByOrNull { it.gain }
        val seats = TrainBoard.seats(
            repo.dao.plan(s.slot, "trainscreen")?.json?.let { runCatching { JSONObject(it) }.getOrNull() },
            repo.playersOf(s.slot), System.currentTimeMillis(), repo.trainingOverrides(s.slot)
        )
        val freeSeat = seats.firstOrNull { it.state == "empty" || it.state == "done" }
        val next = when {
            s.needsResult -> "registrar o resultado do último jogo"
            !s.ended && s.tacticStale && s.nextAt != null && s.nextAt - System.currentTimeMillis() <= Director.TACTIC_WINDOW_MS -> "reler o rival (Pré-jogo e Análise) para confirmar a tática"
            !s.ended && !s.tacticReady && s.nextAt != null && s.nextAt - System.currentTimeMillis() <= Director.TACTIC_WINDOW_MS -> "gerar a tática do próximo jogo"
            freeSeat != null -> (if (freeSeat.state == "done") "pegar o treino concluído e " else "") +
                "ocupar o treinador " + (if (freeSeat.role == "UNI") "universal" else freeSeat.role) +
                (plan?.train?.firstOrNull { (TRAINER_ROLE[it.trainer] ?: "UNI") == freeSeat.role }?.let { " com ${it.name}" } ?: "")
            plan != null && plan.sell.isNotEmpty() -> "vender ${plan.sell.first().name} (${plan.sell.size} venda(s) sugerida(s))"
            plan != null && plan.buy.isNotEmpty() -> "comprar ${plan.buy.first().name}"
            seats.any { it.state == "unknown" } -> "abrir a tela de Treinamento no jogo"
            else -> null
        }
        val tips = Hints.slotTips(repo, s.slot, res?.rows?.flatten()?.mapNotNull { it?.nameKey }?.toSet() ?: emptySet(), s.nextAt, s.title, trainRates, Settings.get(ctx, "spend_bc", "0") == "1")
        out.add(
            DirSlot(
                s = s,
                tips = tips,
                forecast = forecast,
                sells = plan?.sell?.map { it.name + " (" + it.cat + " " + (it.strength ?: "?") + ")" } ?: emptyList(),
                buys = plan?.buy?.map { it.name + " (+" + it.gain + ")" } ?: emptyList(),
                trains = plan?.train?.map { it.name + " → " + (if (it.trainer == "universal") "universal" else it.trainer) } ?: emptyList(),
                radar = top?.let { it.cat + ": " + it.name + " (" + it.strength + ")" + (if (it.affordable) " ✔ cabe no caixa" else " — falta caixa") },
                cash = f[K.CASH]?.value ?: NI,
                sellSlots = MarketPlanner.sellSlotsLeft(f[K.SELLING]?.value),
                alerts = alerts,
                stadium = stadiumLine,
                goal = goalLine,
                next = next,
                week = runCatching { repo.progress(s.slot) }.getOrNull()?.let { pg ->
                    listOfNotNull(
                        pg.week?.value?.let { "valor ${moneyText(it, true)}" },
                        pg.week?.cash?.let { "caixa ${moneyText(it, true)}" },
                        pg.train?.takeIf { it.trained > 0 }?.let { "treino ${if (it.edge >= 0) "+" else ""}${"%.1f".format(it.edge).replace('.', ',')}/jogador" },
                        pg.ledger.takeIf { it.buys + it.sells > 0 }?.let { "saldo ${moneyText(it.balance, true)}" }
                    ).takeIf { it.isNotEmpty() }?.joinToString(" • ")?.let { "📈 Semana: $it" }
                }
            )
        )
    }
    return out
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DirectorTab(onOpen: (Int, Int) -> Unit) {
    val ctx = LocalContext.current
    // recarga pesada (4 slots): a cada 20 s ou na hora em que chega dado novo (UiBus)
    val tick = rememberTick(20000L)
    val list = rememberLoaded(emptyList<DirSlot>(), tick, UiBus.version) { loadDirector(ctx) }
    val now = System.currentTimeMillis()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 96.dp)) {
        PageTitle("🧭", "Diretor", "gestão do clube")
        // competição recém-começada (batalha/liga nova): o plano de arranque vem primeiro
        val fresh = list.filter { d -> !d.s.ended && d.s.known && (d.s.state == null || d.s.state == "preepoca") && (d.s.roundDone?.toIntOrNull() ?: 0) <= 2 && d.tips.any { it.icon != "🎉" } }
        for (d in fresh) {
            Title("🚀 Arranque do S${d.s.slot} • ${d.s.title}")
            Text(
                (if (d.s.compType == "Batalha") "Batalha nova" else "Competição nova") + ": faça nesta ordem para evoluir o mais rápido (grátis, sem Boss Coins).",
                fontSize = 13.sp, color = C.MUTED
            )
            d.tips.filter { it.icon != "🎉" }.take(7).forEachIndexed { i, t ->
                Text("${i + 1}.", fontWeight = FontWeight.ExtraBold, color = C.GOLD, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                TipCard(t) { onOpen(d.s.slot, t.tab) }
            }
        }
        val eventTips = list.flatMap { it.tips }.filter { it.icon == "🎉" }.distinctBy { it.title }
        if (eventTips.isNotEmpty()) {
            Title("🎉 Eventos do OSM agora")
            for (t in eventTips) TipCard(t) {}
        }
        Title("📋 O que fazer agora")
        for (d in list.filter { it.tips.isNotEmpty() && it !in fresh }) {
            Row(Modifier.padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Crest(d.s.slot, 26.dp, d.s.title)
                Spacer(Modifier.width(8.dp))
                Text("S${d.s.slot} • ${d.s.title}", fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val mineTips = d.tips.filter { it.icon != "🎉" }
            for (t in mineTips.take(3)) TipCard(t) { onOpen(d.s.slot, t.tab) }
            if (mineTips.size > 3) Fold("➕", "Mais ${mineTips.size - 3} dica(s) do S${d.s.slot}") { for (t in mineTips.drop(3)) TipCard(t) { onOpen(d.s.slot, t.tab) } }
        }
        if (Diag.lastError != null) Hint(Diag.lastError ?: "", "último erro")

        val alertLines = list.flatMap { it.alerts }
        if (alertLines.isNotEmpty()) {
            Fold("🔔", "Alertas", alertLines.size.toString()) { for (a in alertLines) AlertCard(a) }
        }
        val upcoming = list.filter { !it.s.ended && it.s.nextAt != null && it.s.nextAt > now - 7200000L }.sortedBy { it.s.nextAt }
        if (upcoming.isNotEmpty()) {
            Title("Agenda")
            Panel {
                for (u in upcoming) {
                    Row(Modifier.fillMaxWidth().clickable { onOpen(u.s.slot, 4) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("S${u.s.slot} • ${u.s.title}" + (if (u.s.rival != null) " vs ${u.s.rival}" else ""), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(countdown(u.s.nextAt!!) + " • previsão: " + u.forecast, fontSize = 12.5.sp, color = C.MUTED)
                        }
                        val far = u.s.nextAt != null && u.s.nextAt - System.currentTimeMillis() > Director.TACTIC_WINDOW_MS
                        Pill(if (u.s.tacticReady) "Tática ✔" else if (u.s.tacticStale || u.s.rivalGaps) "Reler rival" else if (far) "Nas últimas 8 h" else "Gerar →", u.s.tacticReady)
                    }
                }
            }
        }

        if (list.isNotEmpty()) Title("Plano de cada slot")
        for (d in list) {
            Fold("S${d.s.slot}", d.s.title, if (d.alerts.isNotEmpty()) "${d.alerts.size} alerta(s)" else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Crest(d.s.slot, 44.dp, d.s.title)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("S${d.s.slot} • ${d.s.title}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Row {
                            if (d.s.rival != null) Pill("vs ${d.s.rival}")
                            if (d.s.human != null) Pill(if (d.s.human == "Sim") "Humano" else "CPU", d.s.human == "Sim")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                WinCard(d.s.win, compact = true)
                if (d.next != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(C.PRIMARY.copy(alpha = 0.18f))
                            .clickable { onOpen(d.s.slot, 6) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("👉", fontSize = 18.sp)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text("PRÓXIMO PASSO", fontSize = 11.sp, color = C.MUTED, fontWeight = FontWeight.Bold)
                            Text(d.next.replaceFirstChar { it.uppercase() }, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("›", fontSize = 20.sp, color = C.MUTED)
                    }
                }
                Text("⚽ ${d.forecast}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatTile("💰", d.cash, "caixa", C.OK, Modifier.weight(1f))
                    StatTile("🔴", d.sells.size.toString(), "vender", C.LOSS, Modifier.weight(1f))
                    StatTile("🟢", d.buys.size.toString(), "comprar", C.WIN, Modifier.weight(1f))
                    StatTile("🏋", d.trains.size.toString(), "treinar", C.PRIMARY, Modifier.weight(1f))
                }
                FlowRow(Modifier.padding(top = 6.dp)) {
                    for (x in d.sells.take(3)) Pill("🔴 $x", false)
                    for (x in d.buys.take(3)) Pill("🟢 $x", true)
                    for (x in d.trains.take(3)) Pill("🏋 $x")
                }
                if (d.radar != null) Text("📡 ${d.radar}", fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
                if (d.goal != null) Text(d.goal, fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 2.dp))
                if (d.week != null) Text(d.week, fontSize = 13.sp, color = OsmColors.YELLOW, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onOpen(d.s.slot, 4) }, modifier = Modifier.weight(1f)) { Text("Tática", fontSize = 13.sp) }
                    OutlinedButton(onClick = { onOpen(d.s.slot, 6) }, modifier = Modifier.weight(1f)) { Text("Plano completo", fontSize = 13.sp) }
                }
            }
        }
        Fold("📚", "O que a comunidade diz que funciona") {
            for ((rule, src) in Playbook.GUIDE) {
                Text(rule, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                Text("Fonte: $src", fontSize = 11.5.sp, color = C.MUTED)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Abas do slot como pílulas com ícone: a ativa fica azul; a lista rola e leva a ativa para a vista. */
@Composable
internal fun SlotTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val icons = listOf("📋", "⚔", "👥", "📅", "⚡", "📊", "🧭", "🧠")
    val scroll = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(selected) { scroll.animateScrollTo(((selected - 1).coerceAtLeast(0) * 96 * density.density).toInt().coerceAtMost(scroll.maxValue)) }
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tabs.forEachIndexed { i, t ->
            val on = i == selected
            Row(
                Modifier.clip(RoundedCornerShape(50))
                    .background(if (on) Brush.horizontalGradient(listOf(Color(0xFF1E9BFF), Color(0xFF1665C8))) else Brush.horizontalGradient(listOf(C.SURFACE, C.SURFACE)))
                    .border(1.dp, if (on) Color(0x6672B8FF) else Color(0x2272B8FF), RoundedCornerShape(50))
                    .clickable { onSelect(i) }.padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(icons.getOrElse(i) { "•" }, fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                Text(t, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) Color.White else C.MUTED, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun SlotScreen(slot: Int, tab: Int, onTab: (Int) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberTick(6000L)
    val data = rememberLoaded(SlotData(), tick, Pair(slot, UiBus.version)) { loadSlot(ctx, slot) }
    val idle = data.idle
    // slot sem competição em andamento: só a Visão geral (resumo do que foi) e o estado no topo; nada de Elenco/Calendário/Tática da competição antiga
    val tabs = if (idle != null) listOf("Visão geral") else listOf("Visão geral", "Pré-jogo", "Elenco", "Calendário", "Tática", "Resultado", "Diretor", "Aprendizado")
    val tab = if (idle != null) 0 else tab
    val title = if (idle != null) "Slot $slot" else known(data.fields, K.TEAM) ?: known(data.fields, K.HUB_TITLE) ?: if (data.ready) "Slot $slot ainda não lido" else "carregando…"
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(50)).clickable { onBack() }, contentAlignment = Alignment.Center) { Text("‹", fontSize = 30.sp, color = Color.White) }
            Crest(slot, 44.dp, if (idle != null) null else known(data.fields, K.TEAM))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (idle != null) Text(idle, color = C.GOLD, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    FlagBadge(Flags.isoFromCompetition(known(data.fields, K.COMPETITION)), data.leagueRich, null, 22.dp, 16.dp)
                    Text("Slot $slot • " + (known(data.fields, K.COMPETITION) ?: if (data.ready) "competição não lida" else ""), color = C.MUTED, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (idle == null) data.completeness?.let { c ->
                Column(Modifier.padding(end = 8.dp), horizontalAlignment = Alignment.End) {
                    Text("Slot ativo", fontSize = 11.5.sp, color = C.MUTED)
                    Text("${c.percent}%", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = if (c.percent >= 80) C.OK else if (c.percent >= 50) C.GOLD else C.WARN)
                }
            }
            OsmButton()
        }
        if (idle == null) SlotTabs(tabs, tab, onTab)
        if (!data.ready) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator(color = C.PRIMARY) }
            return@Column
        }
        // troca de aba fluida: a nova desliza um pouco e aparece enquanto a anterior some
        androidx.compose.animation.AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260)) +
                    androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(300)) { it / 10 * dir }) togetherWith
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160))
            },
            label = "tabs"
        ) { t ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 96.dp)) {
                when (t) {
                    0 -> SlotSummaryTab(slot, data)
                    1 -> SlotPregame(slot, data)
                    2 -> SlotSquad(slot, data)
                    3 -> SlotCalendar(slot, data)
                    4 -> SlotTactic(slot, data)
                    5 -> SlotResult(slot, data) { onTab(4) }
                    6 -> SlotDirector(slot, data)
                    else -> SlotLearning(slot, data)
                }
            }
        }
    }
}

internal fun fv(d: SlotData, key: String): String = known(d.fields, key) ?: NI

@Composable
internal fun Section(title: String, rows: List<Pair<String, String>>) {
    Panel {
        Text(title, color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
        for ((k, v) in rows) KV(k, v)
    }
}

/** Bandeira legível: emoji vetorial quando o país é conhecido (nome/gentílico/competição); senão a miniatura desenhada. */
@Composable
internal fun FlagBadge(iso: String?, rich: String?, sig: String?, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp, img: String? = null, soft: Boolean = false) {
    val e = Flags.emoji(iso)
    val fctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(iso) { FlagWeb.request(fctx, iso) }   // bandeira nítida do Flagpedia (domínio público) quando o país é conhecido
    val webFlag = remember(iso, UiBus.version) { FlagWeb.file(fctx.filesDir, iso)?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }.getOrNull() } }
    if (webFlag != null) {
        Image(webFlag, contentDescription = null, contentScale = ContentScale.FillBounds, filterQuality = androidx.compose.ui.graphics.FilterQuality.High, modifier = Modifier.size(w, h).clip(RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(6.dp))
        return
    }
    val bmp = remember(img) { img?.let { runCatching { val b = android.util.Base64.decode(Flags.imgPayload(it), android.util.Base64.NO_WRAP); BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull() } }
    if (bmp != null) {
        Image(bmp, contentDescription = null, contentScale = ContentScale.FillBounds, filterQuality = androidx.compose.ui.graphics.FilterQuality.High, modifier = Modifier.size(w, h).clip(RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(6.dp))
    } else if (e != null) {
        Box(Modifier.size(w, h), contentAlignment = Alignment.Center) { Text(e, fontSize = (h.value * 0.95f).sp, maxLines = 1, softWrap = false) }
        Spacer(Modifier.width(6.dp))
    } else if (soft) {
        // país ainda não identificado e sem recorte nítido: caixa neutra em vez da miniatura de 8×5 células (que aparece borrada)
        Box(Modifier.size(w, h).clip(RoundedCornerShape(3.dp)).background(C.SURFACE2), contentAlignment = Alignment.Center) { Text("🏳", fontSize = (h.value * 0.7f).sp) }
        Spacer(Modifier.width(6.dp))
    } else if (rich != null || sig != null) {
        FlagSwatch(rich, sig, w, h); Spacer(Modifier.width(6.dp))
    }
}

/** Desenha a bandeira/emblema a partir da assinatura rica (grade 8×5 suavizada); sem ela, usa os 9 quadrados da assinatura antiga. */
@Composable
internal fun FlagSwatch(rich: String?, sig: String? = null, w: androidx.compose.ui.unit.Dp = 30.dp, h: androidx.compose.ui.unit.Dp = 20.dp, round: Boolean = false) {
    val bmp = remember(rich) {
        rich?.takeIf { it.length >= 6 * 8 && it.length % 6 == 0 }?.let { r ->
            runCatching {
                val cells = r.length / 6
                val px = IntArray(cells) { i -> 0xFF000000.toInt() or r.substring(i * 6, i * 6 + 6).toInt(16) }
                android.graphics.Bitmap.createBitmap(px, 8, cells / 8, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
            }.getOrNull()
        }
    }
    val shape = if (round) RoundedCornerShape(50) else RoundedCornerShape(3.dp)
    if (bmp != null) {
        Image(bitmap = bmp, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.FillBounds, filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium, modifier = Modifier.size(w, h).clip(shape))
    } else if (sig != null && sig.length == 9) {
        Column(Modifier.size(w, h).clip(shape)) { for (r in 0 until 3) Row(Modifier.weight(1f)) { for (c in 0 until 3) Box(Modifier.weight(1f).fillMaxHeight().background(flagColor(sig[r * 3 + c]))) } }
    } else Box(Modifier.size(w, h).clip(shape).background(C.SURFACE2))
}

internal fun flagColor(c: Char): Color = when (c) {
    'K' -> Color(0xFF111111); 'W' -> Color(0xFFF5F5F5); 'X' -> Color(0xFF9A9A9A); 'R' -> Color(0xFFD62828); 'O' -> Color(0xFFF77F00)
    'Y' -> Color(0xFFFFD60A); 'G' -> Color(0xFF2D9D4B); 'L' -> Color(0xFF4CC9F0); 'B' -> Color(0xFF1D4ED8); else -> Color(0xFF7B2CBF)
}

/** Escolha da bandeira da liga entre as bandeiras lidas no Plantel; o nome do país vem do Atlas (filtros Nacionalidade/Liga do OSM). */
@Composable
internal fun FlagPicker(slot: Int) {
    val ctx = LocalContext.current
    var groups by remember(slot) { mutableStateOf<List<Pair<String, List<String>>>>(emptyList()) }
    var infos by remember(slot) { mutableStateOf<Map<String, SlotRules.FlagInfo>>(emptyMap()) }
    var chosen by remember(slot) { mutableStateOf<String?>(null) }
    var suggested by remember(slot) { mutableStateOf<String?>(null) }
    var atlasN by remember(slot) { mutableStateOf(0) }
    LaunchedEffect(slot, UiBus.version) {
        withContext(Dispatchers.IO) {
            val repo = Repo(ctx)
            groups = SlotRules.flagGroups(repo, slot)
            infos = SlotRules.flagInfo(repo, slot)
            atlasN = Atlas.count(repo, Atlas.Kind.NAT)
            val saved = repo.dao.plan(slot, "leagueflag")?.json?.let { runCatching { JSONObject(it).optString("sig") }.getOrNull() }?.takeIf { it.length == 9 }
            chosen = saved
            suggested = if (saved == null) SlotRules.suggestLeagueFlag(repo, slot) else null
            // sem escolha e sem sugestão pelo nome da liga: o grupo de longe maior costuma ser a nacionalidade da liga
            val g = groups
            if (saved == null && suggested == null && g.size >= 2 && g[0].second.size >= 8 && g[0].second.size >= g[1].second.size * 2) {
                SlotRules.setLeagueFlag(repo, slot, g[0].first, System.currentTimeMillis())
                chosen = g[0].first
            }
        }
    }
    Text("Nacionalidades do elenco", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    Text(
        if (atlasN > 0) "O nome do país vem do Atlas ($atlasN nacionalidades aprendidas). Toque numa linha para dizer qual é a nacionalidade da liga (✓)."
        else "Para ver o NOME dos países: no OSM, Lista de transferências → filtro Nacionalidade (toque nele) e role a lista inteira com a captura ligada. Sem isso mostro só a bandeira.",
        fontSize = 12.5.sp, color = C.MUTED
    )
    if (groups.isEmpty()) {
        Text("Abra o Plantel e role a lista para eu ler as bandeiras.", fontSize = 12.5.sp, color = C.MUTED)
        return
    }
    for ((sig, names) in groups.take(10)) {
        val sel = (chosen != null && SlotRules.sameFlag(sig, chosen!!)) || (chosen == null && suggested != null && SlotRules.sameFlag(sig, suggested!!))
        val inf = infos[sig]
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clickable {
                val v = if (chosen != null && SlotRules.sameFlag(sig, chosen!!)) null else sig
                chosen = v; suggested = null
                AppScope.scope.launch(Dispatchers.IO) { SlotRules.setLeagueFlag(Repo(ctx), slot, v, System.currentTimeMillis()); UiBus.version++ }
            },
            verticalAlignment = Alignment.CenterVertically
        ) {
            FlagBadge(Flags.iso(inf?.name), inf?.rich, sig, 40.dp, 27.dp, inf?.img)
            Column(Modifier.padding(start = 10.dp)) {
                Text((if (sel) "✓ " else "") + (inf?.name ?: "País não identificado") + (if (sel && chosen == null) " (pela liga)" else ""), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (sel) C.OK else Color.White)
                Text("${names.size}: " + names.take(4).joinToString(", ") + (if (names.size > 4) "…" else ""), fontSize = 12.5.sp, color = C.MUTED, maxLines = 2)
            }
        }
    }
}

/** Regras especiais da liga deste slot (texto colado pelo usuário): o app respeita compra, treino, craque, elenco e prazos. */
@Composable
internal fun RulesCard(slot: Int) {
    val ctx = LocalContext.current
    var saved by remember(slot, UiBus.version) { mutableStateOf<String?>(null) }
    var loaded by remember(slot) { mutableStateOf(false) }
    var editing by remember(slot) { mutableStateOf(false) }
    var text by remember(slot) { mutableStateOf("") }
    LaunchedEffect(slot, UiBus.version) {
        val t = withContext(Dispatchers.IO) { SlotRules.load(Repo(ctx), slot)?.text }
        saved = t
        if (!editing) text = t.orEmpty()
        loaded = true
    }
    if (!loaded) return
    Panel {
        Text("📜 Regras especiais da liga", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
        val r = saved?.let { SlotRules.parse(it) }
        if (!editing) {
            if (r == null) {
                Text("Sem regras especiais: vale o padrão do app. Se esta liga tem regras (over máximo, treino, craque, elenco…), cole aqui.", fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
            } else {
                val s = SlotRules.summary(r)
                Text(if (s.isEmpty()) "Texto salvo; a IA recebe tudo como regra obrigatória." else "Entendi: " + s.joinToString(" • "), fontSize = 13.sp, color = Color.White, modifier = Modifier.padding(top = 4.dp))
                Text("O restante do texto também vai para a IA da tática e do mercado.", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 2.dp))
            }
            if (r?.natCount != null) FlagPicker(slot)
            GhostButton(if (r == null) "Colar regras" else "Editar regras", modifier = Modifier.padding(top = 8.dp)) { editing = true }
        } else {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, minLines = 5, maxLines = 12,
                label = { Text("Cole as regras desta liga") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CtaButton("Salvar", modifier = Modifier.weight(1f)) {
                    val t = text
                    AppScope.scope.launch(Dispatchers.IO) {
                        SlotRules.save(Repo(ctx), slot, t, System.currentTimeMillis())
                        UiBus.version++
                    }
                    editing = false
                }
                GhostButton("Apagar", modifier = Modifier.weight(1f)) {
                    text = ""
                    AppScope.scope.launch(Dispatchers.IO) {
                        SlotRules.save(Repo(ctx), slot, "", System.currentTimeMillis())
                        UiBus.version++
                    }
                    editing = false
                }
            }
        }
    }
}

/** Véspera do jogo: checklist do que fazer antes da partida (só nas 8 h antes). */
@Composable
internal fun EveCard(items: List<Pair<Boolean, String>>) {
    val done = items.count { it.first }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🌙 Véspera do jogo", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text("$done/${items.size}", color = if (done == items.size) C.OK else C.GOLD, fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(progress = { done / items.size.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp).height(5.dp).clip(RoundedCornerShape(50)), color = if (done == items.size) C.OK else C.GOLD, trackColor = C.SURFACE2)
        for ((ok, t) in items) {
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (ok) "✅" else "⬜", fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Text(t, fontSize = 13.5.sp, color = if (ok) C.MUTED else Color.White)
            }
        }
    }
}

@Composable
internal fun SlotSummaryTab(slot: Int, d: SlotData) {
    var missingEdit by remember { mutableStateOf<Completeness.Item?>(null) }
    val c = d.completeness
    val ended = Fixtures.seasonEnded(d.fields, d.matches, System.currentTimeMillis())
    d.idle?.let { msg ->
        Panel {
            Text(if (ended) "🏁 $msg" else "🔎 $msg", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.GOLD)
            Text(
                if (ended) "O resumo abaixo mostra como foi. Os dados desta competição ficam guardados só para o aprendizado do app; as outras abas voltam quando a próxima começar."
                else "Quando uma competição ou batalha começar, o app reconhece sozinho e as abas voltam.",
                fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (!ended) return
    }
    if (ended) {
        SeasonEndCard(d)
        if (d.board.size > 1) Fold("💬", "Mensagens da direção", d.board.size.toString()) { BoardLines(d.board) }
    } else {
        MatchupHero(slot, d)
        if (d.eve.isNotEmpty()) EveCard(d.eve)
        StrengthsAttention(d)
        CompareCard(d)
        Panel { WinCard(d.win) }
    }
    LastGameCard(d)
    RulesCard(slot)
    // números da temporada em blocos
    val goalPos = fv(d, K.MY_OBJECTIVE).toIntOrNull()
    val nowPos = fv(d, K.LEAGUE_POS).toIntOrNull()
    val pts = known(d.fields, K.POINTS)
    val cash = known(d.fields, K.CASH)
    // no fim da competição a posição e os pontos já estão no cartão de cima
    val showPos = !ended && (nowPos != null || pts != null)
    if (showPos || (goalPos != null && !ended) || cash != null) Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showPos) StatTile("📈", nowPos?.let { "${it}º" } ?: "?", "na liga", C.PRIMARY, Modifier.weight(1f))
        if (showPos) StatTile("⭐", pts ?: "?", "pontos", C.GOLD, Modifier.weight(1f))
        if (goalPos != null && !ended) StatTile(
            "🎯", "top $goalPos",
            if (nowPos != null) (if (nowPos <= goalPos) "meta ✔" else "fora da meta") else "meta",
            if (nowPos != null && nowPos > goalPos) C.LOSS else C.OK, Modifier.weight(1f)
        )
        if (cash != null) StatTile("💰", cash, "caixa", C.OK, Modifier.weight(1f))
    }
    if (c != null && !ended) {
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ring(c.percent, 64.dp, if (c.percent >= 80) C.OK else if (c.percent >= 50) C.GOLD else C.WARN, "${c.percent}%")
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (c.missing.isEmpty()) "Tudo lido ✔" else "Faltam ${c.missing.size} dado(s)", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (c.missing.isEmpty()) C.OK else Color.White)
                    Text("${c.known.size} de ${c.known.size + c.missing.size} lidos", fontSize = 12.5.sp, color = C.MUTED)
                }
            }
            var all by remember { mutableStateOf(false) }
            for (mi in if (all) c.missingItems else c.missingItems.take(4)) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(C.SURFACE2)
                        .clickable { missingEdit = mi }.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("📋", fontSize = 14.sp)
                    Text("  " + mi.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("＋", color = C.PRIMARY, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (c.missingItems.size > 4) {
                Text(
                    if (all) "▲ mostrar menos" else "▼ ver mais ${c.missingItems.size - 4}", fontSize = 13.sp, color = C.PRIMARY, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { all = !all }.padding(top = 8.dp)
                )
            }
            if (all) for (m in c.missing) {
                if (c.missingItems.none { x -> x.label == m }) {
                    Text("• $m", fontSize = 13.sp, color = C.WARN, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (c.missing.isNotEmpty()) Hint("Toque em ＋ para preencher à mão, ou abra a tela certa no OSM e role até o fim: o app lê sozinho.")
        }
    }
    val me = missingEdit
    if (me != null) ManualDialog(slot, ManualFields.spec(me.key, me.label), fv(d, me.key)) { missingEdit = null }
    if (!ended) EvidencePanel(d.evidence, fv(d, K.RIVAL_HUMAN) == "Não")
    val stadiumPlan = Director.stadiumPlan(d.fields)
    if (stadiumPlan != null) {
        Fold("🏟", "Plano do estádio") { Text(stadiumPlan, fontSize = 13.sp) }
    }
    EditSection(
        slot, d, "Dados da competição",
        listOf(
            EditRow("Time", K.TEAM, fv(d, K.TEAM).let { if (it == NI) fv(d, K.HUB_TITLE) else it }),
            EditRow("Competição", K.COMPETITION),
            EditRow("Tipo", K.COMP_TYPE),
            EditRow("Rodadas concluídas", K.ROUND_DONE),
            EditRow("Total de rodadas", K.ROUND_TOTAL),
            EditRow("Próxima rodada", K.ROUND),
            EditRow("Posição na liga", K.LEAGUE_POS),
            EditRow("Pontos", K.POINTS),
            EditRow("Caixa", K.CASH),
            EditRow("Objetivo da temporada (posição)", K.MY_OBJECTIVE),
            EditRow("Nível do estádio (relvado) — bônus em casa", K.MY_STAD_PITCH),
            EditRow("Capacidade — receita por jogo", K.MY_STAD_CAP),
            EditRow("Treino do estádio — evolução nos treinos", K.MY_STAD_TRAIN)
        ),
        icon = "🏆"
    )
    Fold("🕘", "Última leitura de cada tela") {
        for (t in listOf("PREGAME", "SQUAD", "CALENDAR", "MARKET", "REPORT")) {
            KV(typeLabel(t), ago(d.sections[t] ?: 0L))
        }
    }
}

@Composable
internal fun ForceBox(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }
        Text(label, fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
internal fun SlotPregame(slot: Int, d: SlotData) {
    val ctx = LocalContext.current
    if (Fixtures.seasonEnded(d.fields, d.matches, System.currentTimeMillis())) {
        // sem próximo jogo: nada de rival, árbitro ou relatório (eram do último jogo)
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🏁", fontSize = 30.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Sem próximo jogo", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    Text("A competição acabou. O pré-jogo volta quando a próxima começar.", fontSize = 13.sp, color = C.MUTED)
                }
            }
        }
        Panel {
            Text("💪 Força do seu time hoje", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((lab, k) in listOf("Geral" to K.MY_STRENGTH, "GOL" to K.MY_GOL, "DEF" to K.MY_DEF, "MEI" to K.MY_MID, "ATA" to K.MY_ATK)) {
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(C.SURFACE2).padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(known(d.fields, k) ?: "?", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                        Text(lab, fontSize = 11.5.sp, color = C.MUTED)
                    }
                }
            }
            val weakest = listOf("GOL" to K.MY_GOL, "DEF" to K.MY_DEF, "MEI" to K.MY_MID, "ATA" to K.MY_ATK)
                .mapNotNull { (l, k) -> known(d.fields, k)?.toIntOrNull()?.let { l to it } }.minByOrNull { it.second }
            if (weakest != null) {
                Text("Setor mais fraco: ${weakest.first} (${weakest.second}). Reforce antes da próxima competição: veja o Diretor.", fontSize = 13.sp, color = C.WARN, modifier = Modifier.padding(top = 8.dp))
            }
        }
        return
    }
    val at = fv(d, K.MATCH_AT).toLongOrNull()
    // IA sempre no topo
    OutlinedButton(
        onClick = { startReadLatest(ctx, slot) },
        enabled = !ProcessState.running,
        modifier = Modifier.fillMaxWidth()
    ) { Text("🤖 Ler a análise do rival com IA agora") }
    ProcessCard()
    Panel {
        RefereeMeter(known(d.fields, K.REFEREE))
        Spacer(Modifier.height(10.dp))
        Duel("FORÇA GERAL", known(d.fields, K.MY_STRENGTH), known(d.fields, K.RIVAL_STRENGTH))
        Duel("MEU BÔNUS x BÔNUS DO RIVAL (%)", known(d.fields, K.MY_BONUS), known(d.fields, K.RIVAL_LOGIN_BONUS))
    }
    SectorDuels(d)
    Panel {
        Text("🕵 Como o rival joga", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingChip("Formação", known(d.fields, K.RIVAL_FORMATION) ?: "?", Modifier.weight(1f))
            SettingChip("Estilo", known(d.fields, K.RIVAL_PLAN) ?: "?", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingChip("Marcação", known(d.fields, K.RIVAL_MARKING) ?: "?", Modifier.weight(1f))
            SettingChip("Fora-de-jogo", known(d.fields, K.RIVAL_OFFSIDE) ?: "?", Modifier.weight(1f))
            SettingChip("Entradas", known(d.fields, K.RIVAL_TACKLE) ?: "?", Modifier.weight(1f))
        }
    }
    EditSection(
        slot, d, "Jogo",
        listOf(
            EditRow("Rodada", K.ROUND),
            EditRow("Data/hora do jogo", K.MATCH_AT, if (at != null) fmtTime(at) else NI),
            EditRow("Casa/fora", K.HOME),
            EditRow("Adversário", K.RIVAL_TEAM),
            EditRow("Humano/CPU", K.RIVAL_HUMAN),
            EditRow("Apelido do rival", K.RIVAL_NICK),
            EditRow("Árbitro (termômetro)", K.REFEREE)
        ),
        icon = "📅"
    )
    EditSection(
        slot, d, "Forças",
        listOf(
            EditRow("Minha força", K.MY_STRENGTH),
            EditRow("Meu bônus", K.MY_BONUS),
            EditRow("Força do rival", K.RIVAL_STRENGTH),
            EditRow("Valor do meu elenco", K.MY_VALUE),
            EditRow("Valor do elenco rival", K.RIVAL_VALUE),
            EditRow("Meu GOL", K.MY_GOL), EditRow("Meu DEF", K.MY_DEF), EditRow("Meu MEI", K.MY_MID), EditRow("Meu ATA", K.MY_ATK),
            EditRow("GOL do rival", K.RIVAL_GOL), EditRow("DEF do rival", K.RIVAL_DEF),
            EditRow("MEI do rival", K.RIVAL_MID), EditRow("ATA do rival", K.RIVAL_ATK),
            EditRow("Formação do rival", K.RIVAL_FORMATION)
        ),
        icon = "💪"
    )
    val rivalIsHuman = fv(d, K.RIVAL_HUMAN) == "Sim"
    val reportRows = ArrayList<EditRow>()
    reportRows.add(EditRow("Plano de jogo (estilo)", K.RIVAL_PLAN))
    reportRows.add(EditRow("Marcação", K.RIVAL_MARKING))
    reportRows.add(EditRow("Fora-de-jogo", K.RIVAL_OFFSIDE))
    reportRows.add(EditRow("Entradas (desarme)", K.RIVAL_TACKLE))
    reportRows.add(EditRow("Treino secreto", K.RIVAL_SECRET))
    reportRows.add(EditRow("Estágio (campo de treinamento)", K.RIVAL_CAMP))
    reportRows.add(EditRow("Nível do estádio", K.STADIUM))
    if (rivalIsHuman) {
        reportRows.add(EditRow("Apelido do usuário", K.RIVAL_NICK))
        reportRows.add(EditRow("Bônus do rival (círculo do pré-jogo)", K.RIVAL_LOGIN_BONUS))
    }
    EditSection(slot, d, "Relatório do rival", reportRows, icon = "🕵")
    val prof = d.rivalProfile
    if (prof != null) {
        val pj = try { JSONObject(prof.json) } catch (e: Exception) { null }
        val arr = pj?.optJSONArray("entries")
        if (pj != null && arr != null && arr.length() > 0) {
            fun mode(key: String): String {
                val counts = HashMap<String, Int>()
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i)?.optString(key) ?: ""
                    if (v.isNotBlank()) counts[v] = (counts[v] ?: 0) + 1
                }
                val best = counts.maxByOrNull { it.value } ?: return NI
                return "${best.key} (${best.value}x)"
            }
            Fold("👤", "Perfil de ${pj.optString("nick")}", "${arr.length()} jogo(s)") {
                KV("Formação mais usada", mode("formation"))
                KV("Estilo mais usado", mode("plan"))
                KV("Marcação", mode("marking"))
                KV("Fora-de-jogo", mode("offside"))
                KV("Entradas", mode("tackle"))
            }
        }
    }
    Hint(
        "? = ainda não lido. Formação, força, valor e setores do rival vêm do Plantel dele. Estilo, marcação, fora-de-jogo, entradas, estágio e nível do estádio vêm do Relatório do analista (a tela com a nota à esquerda): alterne os botões Tática e Equipa inicial. Apelido e bônus só existem se o rival for humano. O árbitro vem da cor do termômetro no pré-jogo.",
        "de onde vem cada dado"
    )
}

internal fun catColor(cat: String): Color = when (cat) {
    "ATA" -> Color(0xFFE5484D)
    "MEI" -> Color(0xFF3D8BFF)
    "DEF" -> Color(0xFF2EA043)
    else -> Color(0xFFF2C94C)
}

@Composable
internal fun TrainingDialog(slot: Int, p: PlayerEntity, out: Boolean, onFlag: () -> Unit = {}, onDone: () -> Unit) {
    val ctx = LocalContext.current
    fun set(v: Boolean?) {
        AppScope.scope.launch(Dispatchers.IO) {
            Repo(ctx).setTrainingOverride(slot, p.nameKey, v)
            UiBus.version++
        }
        onDone()
    }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = C.SURFACE,
        title = { Text(p.name, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("⛔ Próximo jogo", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                OutlinedButton(
                    onClick = {
                        AppScope.scope.launch(Dispatchers.IO) {
                            Repo(ctx).setUnavailable(slot, p.nameKey, !out)
                            UiBus.version++
                        }
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (out) C.OK else C.WARN)
                ) { Text(if (out) "✓ Volta a jogar (tirar o ⛔)" else "⛔ Fora do próximo jogo (lesão/suspensão)") }
                Text("🏳 Bandeira", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                OutlinedButton(onClick = { onDone(); onFlag() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("Escolher o país deste jogador") }
                Text("🏋 Treino", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                Hint("O app lê os treinos da tela de Treinamento do OSM. Cada slot tem até 5: 1 por treinador de posição (GOL, DEF, MEI, ATA) + 1 universal. Corrija aqui só se a leitura errou.")
                OutlinedButton(onClick = { set(true) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("🏋 Está treinando") }
                OutlinedButton(onClick = { set(false) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Não está treinando") }
                OutlinedButton(onClick = { set(null) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Voltar ao automático") }
                OutlinedButton(
                    onClick = {
                        AppScope.scope.launch(Dispatchers.IO) {
                            val now = System.currentTimeMillis()
                            val it = runCatching { Arts.make(ctx, Arts.Cand("player|$slot|${p.nameKey}|$now", slot, "PLAYER", "Destaque: ${p.name}", now, p.nameKey)) }.getOrNull()
                            withContext(Dispatchers.Main) { android.widget.Toast.makeText(ctx, if (it != null) "Arte criada: veja a aba Artes." else (ArtsAi.lastError ?: "Não consegui criar a arte."), android.widget.Toast.LENGTH_LONG).show() }
                        }
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) { Text("🎨 Criar arte deste jogador") }
                var newName by remember { mutableStateOf(p.name) }
                Text("Leitura errada? Corrija o nome (vale nas próximas leituras):", fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 12.dp))
                OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(
                    onClick = {
                        AppScope.scope.launch(Dispatchers.IO) { Repo(ctx).renamePlayer(slot, p.nameKey, newName); UiBus.version++ }
                        onDone()
                    },
                    enabled = newName.trim() != p.name && newName.trim().length >= 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) { Text("✏ Salvar nome corrigido") }
                Text("Não existe no seu elenco (erro de leitura) ou já saiu?", fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 12.dp))
                OutlinedButton(
                    onClick = {
                        AppScope.scope.launch(Dispatchers.IO) {
                            Repo(ctx).removePlayer(slot, p.nameKey)
                            UiBus.version++
                        }
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = C.BAD)
                ) { Text("🗑 Remover do elenco (não volta mais)") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDone) { Text("Cancelar") } }
    )
}

/** Escolha manual do país de um jogador: busca por nome, toque no país. Fica guardada (vale para todos os slots e ligas futuras). */
@Composable
internal fun PlayerFlagDialog(p: PlayerEntity, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    val all = remember { FlagManual.countries(ctx.filesDir) }
    val shown = remember(q) { val k = Txt.norm(q).trim(); all.filter { k.isEmpty() || Txt.norm(it.second).contains(k) }.take(60) }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = C.SURFACE,
        title = { Text("Bandeira de ${p.name}", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                if (all.isEmpty()) Text("As bandeiras ainda estão sendo baixadas (Ajustes → Escudos reais da web). Tente de novo em instantes.", fontSize = 13.sp, color = C.MUTED)
                else {
                    OutlinedTextField(value = q, onValueChange = { q = it }, singleLine = true, label = { Text("Buscar país") }, modifier = Modifier.fillMaxWidth())
                    Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
                        for ((iso, name) in shown) {
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    AppScope.scope.launch(Dispatchers.IO) { FlagManual.set(Repo(ctx), p.nameKey, iso) }
                                    onDone()
                                }.padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FlagBadge(iso, null, null, 30.dp, 20.dp)
                                Text(name, fontSize = 15.sp, color = Color.White)
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { AppScope.scope.launch(Dispatchers.IO) { FlagManual.set(Repo(ctx), p.nameKey, null) }; onDone() },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) { Text("Voltar ao automático") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDone) { Text("Cancelar") } }
    )
}

/** Titulares por setor (para achar o titular mais fraco de cada um). */
internal val CORE_SLOTS = mapOf("GOL" to 1, "DEF" to 4, "MEI" to 4, "ATA" to 3)
internal val TRAINER_ROLE = mapOf("avançados" to "ATA", "médios" to "MEI", "defesas" to "DEF", "guarda-redes" to "GOL", "universal" to "UNI")

internal fun fmtM(m: Double?): String = if (m == null) "?" else if (m < 0) "-" + fmtM(-m) else if (m >= 1.0) "%.1fM".format(m).replace('.', ',') else "%.0fK".format(m * 1000)

/** Número grande de passo (1, 2, 3…) com título. */
@Composable
internal fun StepHeader(n: Int, icon: String, title: String, badge: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(50)).background(C.PRIMARY), contentAlignment = Alignment.Center) {
            Text("$n", fontWeight = FontWeight.ExtraBold, color = Color.White, fontSize = 14.sp)
        }
        Text("  $icon $title", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (badge != null) Pill(badge)
    }
}

/**
 * Diretor do slot: um plano para o time evoluir rápido, em passos (treinadores, vendas, compra), começando pelo setor
 * mais fraco. Em batalha (poucas rodadas) a prioridade é força já.
 */
@Composable
internal fun SlotDirector(slot: Int, d: SlotData) {
    val ctx = LocalContext.current
    d.progress?.let { EvolutionPanel(it, known(d.fields, K.TEAM) ?: known(d.fields, K.HUB_TITLE), slot) }
    val plan = d.marketPlan
    if (plan == null) {
        EmptyState("👕", "Leia o seu elenco primeiro", "No jogo: Plantel do SEU time, role a lista até o fim. Aí o diretor monta treinos, vendas e compras.")
        return
    }
    val mine = d.players.filter { it.owner == "MY" && it.strength != null }
    val battle = (known(d.fields, K.COMP_TYPE) ?: "").contains("Batalha", true) || (known(d.fields, K.COMPETITION) ?: "").contains("Batalha", true)
    // força dos setores: a lida no pré-jogo/plantel ou a média dos titulares
    val sectors = listOf("GOL" to K.MY_GOL, "DEF" to K.MY_DEF, "MEI" to K.MY_MID, "ATA" to K.MY_ATK).map { (c, k) ->
        val starters = mine.filter { it.cat == c }.sortedByDescending { it.strength }.take(CORE_SLOTS[c] ?: 3)
        val v = known(d.fields, k)?.toIntOrNull() ?: starters.mapNotNull { it.strength }.takeIf { it.isNotEmpty() }?.average()?.toInt()
        Triple(c, v, starters.lastOrNull())
    }
    val weakest = sectors.filter { it.second != null }.minByOrNull { it.second!! }

    // ---- onde o time está
    Box(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF0E2A5A), Color(0xFF16213D))))
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(12.dp)).padding(14.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🚀 Plano para evoluir o time", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                if (battle) Pill("⚔ Batalha", true)
            }
            if (battle) {
                Text(
                    "Batalha tem poucas rodadas: vale força JÁ. Treine quem joga, venda quem não entra e compre jogador pronto no setor mais fraco.",
                    fontSize = 13.sp, color = Color(0xCCFFFFFF), modifier = Modifier.padding(top = 4.dp)
                )
            }
            val lo = (sectors.mapNotNull { it.second }.minOrNull() ?: 50) - 8
            val hi = (sectors.mapNotNull { it.second }.maxOrNull() ?: 90) + 4
            for ((c, v, _) in sectors) {
                val isWeak = weakest?.first == c
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(44.dp).clip(RoundedCornerShape(8.dp)).background(catColor(c).copy(alpha = 0.3f)).padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
                        Text(c, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(50)).background(C.SURFACE2)) {
                        if (v != null) Box(
                            Modifier.fillMaxWidth(((v - lo).toFloat() / (hi - lo).coerceAtLeast(1)).coerceIn(0.05f, 1f)).height(12.dp)
                                .clip(RoundedCornerShape(50)).background(if (isWeak) C.WARN else catColor(c))
                        )
                    }
                    Text("  " + (v?.toString() ?: "?"), fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, modifier = Modifier.width(40.dp))
                }
            }
            if (weakest != null) {
                Text("Foco: ${weakest.first} é o setor mais fraco (${weakest.second}).", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = C.WARN, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }

    // ---- 1. treinadores
    val seats = TrainBoard.seats(d.trainScreen, d.players, System.currentTimeMillis(), d.trainOv)
    val busy = seats.count { it.state == "active" && it.fresh }
    StepHeader(1, "🏋", "Treinadores", "$busy de 5")
    TrainingBoardView(seats)
    val freeRoles = seats.filter { it.state == "empty" || it.state == "done" || it.state == "unknown" }.map { it.role }
    val sugg = plan.train.filter { (TRAINER_ROLE[it.trainer] ?: "UNI") in freeRoles }
    if (sugg.isNotEmpty()) {
        for (t in sugg) {
            val role = TRAINER_ROLE[t.trainer] ?: "UNI"
            AlertCard("🔵 Treinador ${if (role == "UNI") "universal" else role}: coloque ${t.name} — ${t.reason}")
        }
    } else if (busy >= 5) {
        Text("✔ Os 5 treinadores estão trabalhando.", fontSize = 13.sp, color = C.OK, modifier = Modifier.padding(top = 4.dp))
    }
    if (seats.any { it.state == "unknown" }) Text("? = abra a tela de Treinamento no jogo para eu ver quem está em cada treinador.", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))

    // ---- 2. vendas
    val proceeds = plan.sell.sumOf { it.valueM ?: 0.0 }
    StepHeader(2, "💸", "Vender", if (plan.sell.isEmpty()) "nada" else "≈ ${fmtM(proceeds)}")
    if (plan.sell.isEmpty()) Text("Ninguém sobrando no elenco agora.", fontSize = 13.sp, color = C.MUTED)
    for (sItem in plan.sell) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(C.SURFACE).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(catColor(sItem.cat).copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                Text(sItem.strength?.toString() ?: "?", fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(sItem.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(sItem.reason, fontSize = 12.5.sp, color = C.MUTED, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("≈ " + fmtM(sItem.valueM), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = C.OK)
        }
    }

    // ---- 3. compra
    StepHeader(3, "🛒", "Reforçar", "verba ${fmtM((plan.cashM ?: 0.0) + proceeds)}")
    if (plan.buy.isNotEmpty()) {
        for (b in plan.buy) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(C.SURFACE).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(catColor(b.cat).copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                    Text(b.strength.toString(), fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(b.name + "  +" + b.gain, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(b.reason, fontSize = 12.5.sp, color = C.MUTED, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(fmtM(b.priceM), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = C.GOLD)
            }
        }
    } else if (weakest != null) {
        val ref = weakest.third?.strength
        Panel {
            Text(
                "Procure na Lista de transferências: ${weakest.first} com força ${if (ref != null) "${ref + 3} ou mais" else "acima dos seus"}" +
                    " até ${fmtM((plan.cashM ?: 0.0) + proceeds)}" + (if (battle) ", pronto para jogar (25–30 anos)." else ", de preferência até 26 anos (ainda cresce)."),
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold
            )
            if (ref != null && weakest.third != null) Text("Hoje o titular mais fraco de ${weakest.first} é ${weakest.third!!.name} (${ref}).", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
            Text("Role a lista no jogo com a captura ligada: eu leio os jogadores e digo qual comprar.", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
        }
    }
    if (plan.radar.isNotEmpty()) {
        Fold("📡", "Melhores alvos já lidos no mercado", plan.radar.size.toString()) {
            for (r in plan.radar) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${r.cat}: ${r.name} (${r.strength})", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(fmtM(r.priceM) + " • +${r.gain} no setor", fontSize = 12.5.sp, color = C.MUTED)
                    }
                    Pill(if (r.affordable) "cabe na verba" else "falta verba", r.affordable)
                }
            }
        }
    }
    val stadium = Director.stadiumPlan(d.fields)
    if (stadium != null) {
        StepHeader(4, "🏟", "Estádio")
        Text(stadium, fontSize = 13.sp)
    }
    Spacer(Modifier.height(10.dp))
    GenPanel("market_ai", slot, "🤖 Pedir a opinião da IA sobre este plano") { startGeneration(ctx, "market_ai", slot) }
    if (d.marketNote != null) {
        Panel {
            Text("🤖 Opinião da IA", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(d.marketNote, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
    Hint("Preços de venda são estimados pelo valor do jogador; confirme no jogo. Metas do elenco: 2 GOL, 6 DEF, 6 MEI e 4 ATA.", "como calculo")
}

internal fun firstNum(s: String?): Double? = s?.let { Regex("\\d+(?:[.,]\\d+)?").find(it)?.value?.replace(',', '.')?.toDoubleOrNull() }

/** "Por quê" da tática do slot: fatores, o que as fontes da web dizem, faixas, plano B, riscos e grau de evidência. */
@Composable
internal fun WhyCard(slot: Int, d: SlotData) {
    val ctx = LocalContext.current
    val v = rememberLoaded<Why.View?>(null, 0, d.tactic?.at ?: 0L) { runCatching { Why.build(Repo(ctx), slot) }.getOrNull() } ?: return
    Fold("🧭", "Raciocínio e fontes (novo)", v.head, startOpen = true) {
        for (x in v.factors) Text("• $x", fontSize = 13.5.sp, modifier = Modifier.padding(vertical = 1.dp))
        if (v.web.isNotEmpty()) {
            Text("O que as fontes da web dizem", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = C.GOLD, modifier = Modifier.padding(top = 8.dp))
            for ((ok, t) in v.web) Text((if (ok) "✅ " else "↔ ") + t, fontSize = 13.sp, color = if (ok) C.OK else C.MUTED)
        }
        if (v.bands.isNotEmpty()) {
            Text("Faixas das fontes para esta formação", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = C.GOLD, modifier = Modifier.padding(top = 8.dp))
            for ((ok, t) in v.bands) Text((if (ok) "✅ " else "⚠ ") + t, fontSize = 13.sp, color = if (ok) C.OK else C.WARN)
        }
        v.alt?.let { Text("🔁 $it", fontSize = 13.5.sp, modifier = Modifier.padding(top = 8.dp)) }
        if (v.risks.isNotEmpty()) {
            Text("Riscos", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = C.WARN, modifier = Modifier.padding(top = 8.dp))
            for (r in v.risks) Text("⚠ $r", fontSize = 13.sp, color = C.MUTED)
        }
        Text(v.evidence, fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Os dois times lado a lado (quem é melhor em cada item sai em destaque). */
@Composable
internal fun CompareCard(d: SlotData) {
    fun n(k: String) = known(d.fields, k)
    val nums = listOf(
        Triple("Força", K.MY_STRENGTH, K.RIVAL_STRENGTH), Triple("Goleiro", K.MY_GOL, K.RIVAL_GOL), Triple("Defesa", K.MY_DEF, K.RIVAL_DEF),
        Triple("Meio-campo", K.MY_MID, K.RIVAL_MID), Triple("Ataque", K.MY_ATK, K.RIVAL_ATK), Triple("Valor do elenco", K.MY_VALUE, K.RIVAL_VALUE),
        Triple("Bônus %", K.MY_BONUS, K.RIVAL_LOGIN_BONUS)
    ).filter { n(it.second) != null || n(it.third) != null }
    val txt = listOf(
        Triple("Formação", K.MY_FORMATION, K.RIVAL_FORMATION), Triple("Estilo", "", K.RIVAL_PLAN), Triple("Forma", K.MY_FORM, K.RIVAL_FORM)
    ).filter { (if (it.second.isEmpty()) null else n(it.second)) != null || n(it.third) != null }
    if (nums.isEmpty() && txt.isEmpty()) return
    Panel {
        Text("⚖ Lado a lado", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(n(K.TEAM) ?: "Meu time", Modifier.weight(1f), fontSize = 12.5.sp, color = C.MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("", Modifier.weight(1.1f))
            Text(n(K.RIVAL_TEAM) ?: "Rival", Modifier.weight(1f), fontSize = 12.5.sp, color = C.MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
        }
        for ((label, mk, rk) in nums) {
            val a = n(mk); val b = n(rk)
            val fa = firstNum(a); val fb = firstNum(b)
            val aWin = fa != null && fb != null && fa > fb; val bWin = fa != null && fb != null && fb > fa
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(a ?: "—", Modifier.weight(1f), fontSize = 15.sp, fontWeight = if (aWin) FontWeight.ExtraBold else FontWeight.Normal, color = if (aWin) C.OK else Color.White)
                Text(label, Modifier.weight(1.1f), fontSize = 12.5.sp, color = C.MUTED, textAlign = TextAlign.Center)
                Text(b ?: "—", Modifier.weight(1f), fontSize = 15.sp, fontWeight = if (bWin) FontWeight.ExtraBold else FontWeight.Normal, color = if (bWin) C.WARN else Color.White, textAlign = TextAlign.End)
            }
        }
        for ((label, mk, rk) in txt) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text((if (mk.isEmpty()) null else n(mk)) ?: "—", Modifier.weight(1f), fontSize = 13.5.sp)
                Text(label, Modifier.weight(1.1f), fontSize = 12.5.sp, color = C.MUTED, textAlign = TextAlign.Center)
                Text(n(rk) ?: "—", Modifier.weight(1f), fontSize = 13.5.sp, textAlign = TextAlign.End)
            }
        }
        Text("Verde = você à frente • laranja = rival à frente. Só entra o que foi lido.", fontSize = 11.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Revisão do último jogo em duas linhas: o que aconteceu e UMA mudança a testar (sem concluir com 1 jogo só). */
@Composable
internal fun LastGameCard(d: SlotData) {
    val games = remember(d.logs, d.reports) {
        Coach.games(
            d.logs.mapNotNull { runCatching { JSONObject(it.json) }.getOrNull() },
            d.reports.mapNotNull { p -> runCatching { JSONObject(p.json).also { j -> if (!j.has("round")) p.kind.removePrefix("mr_R").toIntOrNull()?.let { j.put("round", it) } } }.getOrNull() }
        )
    }
    val last = games.filter { it.result != null }.maxByOrNull { it.round } ?: return
    fun key(g: Coach.Game) = Formations.base(g.formation ?: "") + "|" + (Osm.style(g.style ?: "") ?: g.style)
    val same = games.count { it.result != null && key(it) == key(last) }
    val t = PostGame.twoLines(last, same)
    Panel {
        Text("📝 Último jogo — ${t.title}", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        Text(t.what, fontSize = 13.5.sp, modifier = Modifier.padding(top = 6.dp))
        Text("➡ " + t.next, fontSize = 13.5.sp, color = C.GOLD, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Painel de aprendizado (Hoje): o que seus jogos contra humanos ensinaram, o que está em EVITE e as lições da IA. */
@Composable
internal fun LearningCard() {
    val ctx = LocalContext.current
    val v = rememberLoaded<LearnPanel.View?>(null, 0, UiBus.version) { runCatching { LearnPanel.build(Repo(ctx)) }.getOrNull() } ?: return
    Fold("🎓", "O que o app aprendeu", "${v.humanGames} jogos contra humanos") {
        if (v.streak.isNotBlank()) Text("Últimos resultados (mais recente primeiro): ${v.streak}", fontSize = 13.5.sp)
        if (v.best.isNotEmpty()) { Text("Rende acima do esperado", fontWeight = FontWeight.Bold, color = C.OK, fontSize = 13.5.sp, modifier = Modifier.padding(top = 6.dp)); for (x in v.best) Text("• $x", fontSize = 13.sp) }
        if (v.worst.isNotEmpty()) { Text("Rende abaixo do esperado", fontWeight = FontWeight.Bold, color = C.WARN, fontSize = 13.5.sp, modifier = Modifier.padding(top = 6.dp)); for (x in v.worst) Text("• $x", fontSize = 13.sp) }
        if (v.avoid.isNotEmpty()) Text("🚫 EVITE: " + v.avoid.joinToString(", "), fontSize = 13.5.sp, color = C.WARN, modifier = Modifier.padding(top = 6.dp))
        if (v.lessons.isNotEmpty()) { Text("Lições recentes da IA", fontWeight = FontWeight.Bold, color = C.GOLD, fontSize = 13.5.sp, modifier = Modifier.padding(top = 6.dp)); for (x in v.lessons) Text("• $x", fontSize = 13.sp) }
        Text(v.note, fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
    }
}


/** Onde achar cada novidade (aparece até o usuário tocar em "Entendi"). */
/**
 * Bandeiras dos jogadores sem país confirmado (só slots ATIVOS): escolher à mão; fica guardado (vale para os 4 slots e ligas futuras). Fica na aba
 * Hoje, separado das bandeiras/escudos da web. Antes de listar, roda a identificação de cada slot (`playerCountries`): sem isso todos apareciam
 * "sem bandeira" até você abrir o slot (o país só era calculado ao abrir).
 */
@Composable
internal fun NoFlagFold() {
    val ctx = LocalContext.current
    val tick = rememberTick(3000L)
    var flagFor by remember { mutableStateOf<PlayerEntity?>(null) }
    val noFlag = rememberLoaded(emptyList<Pair<Int, PlayerEntity>>(), tick, UiBus.version) {
        val r = Repo(ctx)
        val manual = FlagManual.all(r)
        val now = System.currentTimeMillis()
        (1..4).flatMap { s ->
            val f = r.fieldMap(s)
            val st = known(f, K.SLOT_STATE)
            if (st == "vazio" || st == "procurando" || st == "preepoca" || Hints.competitionOver(r, s, f, now)) return@flatMap emptyList<Pair<Int, PlayerEntity>>()
            runCatching { SlotRules.playerCountries(r, s, ctx.filesDir) }   // preenche os países já identificáveis (antes só ao abrir o slot)
            val iso = r.dao.plan(s, "natiso2")?.json?.let { j -> runCatching { JSONObject(j) }.getOrNull() }
            r.playersOf(s).filter { it.owner == "MY" && it.nameKey !in manual && (iso == null || iso.optString(it.nameKey).isBlank()) }.map { s to it }
        }
    }
    if (noFlag.isEmpty()) return
    Fold("🏳", "Jogadores sem bandeira", "${noFlag.size}") {
        Text("O app não achou o país destes jogadores. Toque para escolher: fica guardado e vale para todos os slots e ligas futuras.", fontSize = 13.sp, color = C.MUTED)
        for ((s, pl) in noFlag.take(30)) {
            Row(Modifier.fillMaxWidth().clickable { flagFor = pl }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("S$s", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.width(30.dp))
                Text(pl.name, fontSize = 14.sp, color = Color.White, modifier = Modifier.weight(1f))
                Text("🏳 escolher", fontSize = 13.sp, color = C.PRIMARY)
            }
        }
    }
    flagFor?.let { fp -> PlayerFlagDialog(fp) { flagFor = null } }
}

@Composable
internal fun NewsCard() {
    val ctx = LocalContext.current
    var seen by remember { mutableStateOf(Settings.get(ctx, "news_evo1", "0") == "1") }
    if (seen) return
    Panel {
        Text("🆕 Novidades: onde encontrar", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = C.GOLD)
        for (t in listOf(
            "🏳 Bandeiras: Elenco (por jogador) e cartões dos slots (liga)",
            "🧭 Slot → Tática → \"Raciocínio e fontes\": por que, plano B, riscos",
            "📚 Ajustes → \"Base de conhecimento da web\" (lê as fontes sozinho toda semana)",
            "⚖ Slot → Visão geral → \"Lado a lado\" (você × rival)",
            "📝 Slot → Visão geral → \"Último jogo\" (2 linhas, após um resultado)",
            "🎓 Hoje → \"O que o app aprendeu\"",
            "🔁 Véspera do jogo: \"Reanálise dos 20 min finais\""
        )) Text(t, fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 3.dp))
        OutlinedButton(onClick = { Settings.put(ctx, "news_evo1", "1"); seen = true }, modifier = Modifier.padding(top = 8.dp)) { Text("Entendi") }
    }
}


/** Escudo guardado de um clube (arquivo global por nome); não desenha nada se ainda não foi recortado. */
@Composable
internal fun TeamCrest(name: String?, size: Dp) {
    val ctx = LocalContext.current
    val ver = UiBus.version
    androidx.compose.runtime.LaunchedEffect(name) { CrestWeb.request(ctx, name) }
    val bmp = remember(name, ver) {
        runCatching { (CrestWeb.find(ctx.filesDir, name) ?: CrestCut.find(ctx.filesDir, name))?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() } }.getOrNull()
    }
    if (bmp != null) { Image(bmp, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size)); Spacer(Modifier.width(4.dp)) }
}
