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
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun NotificationsPanel() {
    val ctx = LocalContext.current
    var pre by remember { mutableStateOf(Notifier.enabled(ctx, "pre")) }
    var tac by remember { mutableStateOf(Notifier.enabled(ctx, "tactic")) }
    var res by remember { mutableStateOf(Notifier.enabled(ctx, "result")) }
    var dir by remember { mutableStateOf(Notifier.enabled(ctx, "director")) }
    var tim by remember { mutableStateOf(Notifier.enabled(ctx, "timers")) }
    fun toggle(key: String, v: Boolean) {
        Settings.put(ctx, "notif_$key", if (v) "1" else "0")
        AppScope.scope.launch(Dispatchers.IO) { Notifier.reschedule(ctx.applicationContext) }
    }
    Fold("🔔", "Avisos no celular", if (Notifier.canPost(ctx)) "ligados" else "⚠") {
        Text(
            if (Notifier.canPost(ctx)) "Permissão concedida ✔" else "⚠ O Android ainda não liberou as notificações deste app.",
            fontSize = 13.sp, color = if (Notifier.canPost(ctx)) C.OK else C.WARN, modifier = Modifier.padding(top = 2.dp)
        )
        SwitchRow("20 min antes de cada jogo: reler o pré-jogo e a análise do rival", pre) { pre = it; toggle("pre", it) }
        SwitchRow("1 h antes, se a tática da rodada ainda não foi gerada", tac) { tac = it; toggle("tactic", it) }
        SwitchRow("30 min depois do jogo: registrar ou ler o resultado", res) { res = it; toggle("result", it) }
        var autoT by remember { mutableStateOf(Settings.get(ctx, "auto_tactic", "1") == "1") }
        SwitchRow("Gerar a tática sozinho depois de ler o Pré-jogo e a Análise do rival (nas 8 h antes do jogo)", autoT) { autoT = it; Settings.put(ctx, "auto_tactic", if (it) "1" else "0") }
        SwitchRow("Alertas do diretor (ao encerrar a leitura e a cada hora, com o app fechado)", dir) { dir = it; toggle("director", it) }
        SwitchRow("Fim de cada treino e de cada obra do estádio (lidos nas telas do OSM)", tim) { tim = it; toggle("timers", it) }
        // Com o app fechado: alarme exato liberado e bateria sem restrição (senão o Android atrasa ou corta os avisos).
        val exact = Notifier.canExact(ctx)
        val battery = batteryUnrestricted(ctx)
        Text(
            (if (exact) "✔ Alarmes no horário exato" else "⚠ Alarmes exatos bloqueados: os avisos podem atrasar") + "\n" +
                (if (battery) "✔ Bateria sem restrição (avisos com o app fechado)" else "⚠ Bateria com restrição: o Android pode bloquear avisos com o app fechado"),
            fontSize = 13.sp, color = if (exact && battery) C.OK else C.WARN, modifier = Modifier.padding(top = 6.dp)
        )
        if (!exact && Build.VERSION.SDK_INT >= 31) {
            PendingRow("Permitir \"Alarmes e lembretes\"", "Liberar") {
                openSettings(ctx, android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, true)
            }
        }
        if (!battery) {
            PendingRow("Bateria sem restrição para este app", "Bateria") {
                openBatterySettings(ctx)
            }
        }
        Hint("Em celulares Xiaomi/Samsung/Motorola, ative também \"Inicialização automática\" e não deixe o app em \"suspensão\".", "avisos atrasando?")
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Notifier.test(ctx.applicationContext) }, modifier = Modifier.weight(1f)) { Text("Testar", fontSize = 13.sp) }
            OutlinedButton(
                onClick = {
                    Notifier.testLater(ctx.applicationContext)
                    Toast.makeText(ctx, "Feche o app: o aviso chega em 1 minuto.", Toast.LENGTH_LONG).show()
                },
                modifier = Modifier.weight(1f)
            ) { Text("Testar fechado", fontSize = 13.sp) }
            if (!Notifier.canPost(ctx)) {
                OutlinedButton(
                    onClick = {
                        val i = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(i)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Liberar", fontSize = 13.sp) }
            }
        }
    }

    // Reserva na nuvem: alguns celulares apagam os alarmes ao fechar o app; o ntfy.sh entrega o aviso no horário.
    var push by remember { mutableStateOf(Push.enabled(ctx)) }
    val pushTopic = remember { Push.topic(ctx) }
    val pushScope = rememberCoroutineScope()
    var keep by remember { mutableStateOf(KeepAliveService.enabled(ctx)) }
    Fold("🛡", "Monitor ativo (notificação fixa)", if (keep) "ligado" else "desligado") {
        Text(
            "Mantém o app vivo com a tela fechada e confere os avisos a cada minuto. Se o celular apagar os alarmes ao fechar o app, deixe ligado e também coloque o OSM Coach como \"Sem restrições\" na bateria e fixe o app nos recentes (cadeado).",
            fontSize = 13.sp, color = C.MUTED
        )
        SwitchRow("Manter monitor ativo", keep) { v ->
            keep = v
            Settings.put(ctx, "keepalive", if (v) "1" else "0")
            if (v) KeepAliveService.start(ctx.applicationContext) else KeepAliveService.stop(ctx.applicationContext)
        }
        Text(
            "ASUS/ROG: o Mobile Manager bloqueia o app de acordar sozinho. Abra o ajuste abaixo e permita OSM Coach (e o ntfy) em Inicialização automática; e trave os dois nos recentes (cadeado), sem deslizar para fechar.",
            fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 6.dp)
        )
        OutlinedButton(
            onClick = {
                val tries = listOf(
                    android.content.ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
                    android.content.ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.powersaver.PowerSaverSettings"),
                    android.content.ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.MainActivity")
                )
                var ok = false
                for (c in tries) {
                    if (ok) break
                    ok = try { ctx.startActivity(Intent().setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }
                }
                if (!ok) ok = try {
                    ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage("com.asus.mobilemanager")!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
                } catch (e: Exception) { false }
                if (!ok) openBatterySettings(ctx)
            },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        ) { Text("Abrir Inicialização automática (ROG/ASUS)", fontSize = 13.sp) }
        val log = Notifier.fireLog(ctx)
        Text(
            "Últimos eventos de aviso:\n" + (if (log.isEmpty()) "nenhum ainda" else log.joinToString("\n")),
            fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 6.dp)
        )
        Text(Push.status(ctx), fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
    }
    Fold("☁", "Avisos pela nuvem (app fechado)", if (push) "ligados" else "desligados") {
        Text(
            "Se o seu celular apaga os alarmes quando você fecha o app, use esta reserva: o servidor do ntfy.sh guarda cada aviso e o entrega no horário, mesmo com o app morto. Passa só o texto do aviso (slot, time, \"treino terminou\"); o tópico é secreto e só seu.",
            fontSize = 13.sp, color = C.MUTED
        )
        Text(
            "1) Instale o app ntfy (Play Store).  2) Toque em Assinar no ntfy.  3) Ligue abaixo.  4) Toque em Testar, feche tudo e espere 1 minuto.  No ntfy, deixe a bateria sem restrição.",
            fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 6.dp)
        )
        Text("Tópico: $pushTopic", fontSize = 13.sp, color = C.OK, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val sub = Intent(Intent.ACTION_VIEW, Uri.parse("ntfy://ntfy.sh/$pushTopic")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { ctx.startActivity(sub) } catch (e: Exception) {
                        try {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=io.heckel.ntfy")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e2: Exception) {
                            Toast.makeText(ctx, "Instale o app ntfy e assine o tópico " + pushTopic, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("Assinar no ntfy", fontSize = 13.sp) }
            OutlinedButton(
                onClick = {
                    ctx.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("tópico", pushTopic))
                    Toast.makeText(ctx, "Tópico copiado.", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f)
            ) { Text("Copiar tópico", fontSize = 13.sp) }
            OutlinedButton(
                onClick = {
                    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ntfy.sh/$pushTopic")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                },
                modifier = Modifier.weight(1f)
            ) { Text("Ver no navegador", fontSize = 13.sp) }
        }
        SwitchRow("Ligar avisos pela nuvem", push) { v ->
            push = v
            Settings.put(ctx, "push_on", if (v) "1" else "0")
            pushScope.launch(Dispatchers.IO) { if (v) Notifier.reschedule(ctx.applicationContext) else Push.disable(ctx.applicationContext) }
        }
        OutlinedButton(
            onClick = {
                if (!push) {
                    Toast.makeText(ctx, "Ligue os avisos pela nuvem primeiro.", Toast.LENGTH_SHORT).show()
                } else pushScope.launch {
                    val ok = Push.test(ctx.applicationContext)
                    Toast.makeText(ctx, if (ok) "Enviado. Feche o app: o aviso chega em ~1 minuto." else "Sem internet ou o servidor recusou. Tente de novo.", Toast.LENGTH_LONG).show()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        ) { Text("Testar pela nuvem", fontSize = 13.sp) }
    }

    // Reserva pelo calendário do celular: quem dispara é o app Calendário do sistema (não é fechado junto com este app).
    var cal by remember { mutableStateOf(CalendarSync.enabled(ctx) && CalendarSync.hasPermission(ctx)) }
    val calLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val ok = r.values.isNotEmpty() && r.values.all { it }
        cal = ok
        Settings.put(ctx, "cal_on", if (ok) "1" else "0")
        if (ok) pushScope.launch(Dispatchers.IO) { Notifier.reschedule(ctx.applicationContext) }
        else Toast.makeText(ctx, "Sem a permissão do calendário não dá para ligar.", Toast.LENGTH_LONG).show()
    }
    Fold("📅", "Lembretes no calendário do celular", if (cal) "ligados" else "desligados") {
        Text(
            "Notificação normal, como a de qualquer app: o aviso de fim de treino/obra e de jogo vira um evento na sua agenda do Google (se não houver conta Google, num calendário local) e quem avisa é o app Calendário, que o celular não fecha junto com este app. Confira que o app Calendário do Google está instalado e com notificações ligadas. Precisa da permissão do calendário.",
            fontSize = 13.sp, color = C.MUTED
        )
        SwitchRow("Ligar lembretes no calendário", cal) { v ->
            if (v) {
                if (CalendarSync.hasPermission(ctx)) {
                    cal = true
                    Settings.put(ctx, "cal_on", "1")
                    pushScope.launch(Dispatchers.IO) { Notifier.reschedule(ctx.applicationContext) }
                } else calLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
            } else {
                cal = false
                Settings.put(ctx, "cal_on", "0")
                pushScope.launch(Dispatchers.IO) { CalendarSync.disable(ctx.applicationContext) }
            }
        }
        OutlinedButton(
            onClick = {
                // só os avisos do próprio app: desliga o calendário (apaga os lembretes que o app criou lá) e a nuvem (ntfy)
                cal = false; push = false
                Settings.put(ctx, "cal_on", "0"); Settings.put(ctx, "push_on", "0")
                pushScope.launch(Dispatchers.IO) { CalendarSync.disable(ctx.applicationContext); Push.disable(ctx.applicationContext) }
                Toast.makeText(ctx, "Pronto: só os avisos do app. Os lembretes do calendário foram apagados.", Toast.LENGTH_LONG).show()
            },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        ) { Text("Usar só os avisos do app (desliga calendário e nuvem)", fontSize = 13.sp) }
        OutlinedButton(
            onClick = {
                if (!cal) {
                    Toast.makeText(ctx, "Ligue os lembretes no calendário primeiro.", Toast.LENGTH_SHORT).show()
                } else pushScope.launch {
                    val ok = CalendarSync.test(ctx.applicationContext)
                    Toast.makeText(ctx, if (ok) "Criado. Feche o app: o lembrete toca em ~2 minutos." else "Não consegui criar o lembrete no calendário.", Toast.LENGTH_LONG).show()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        ) { Text("Testar no calendário (2 min)", fontSize = 13.sp) }
    }
}

/** Regras da comunidade em cartões: situação -> tática, de onde veio e como foi a última pesquisa. */
@Composable
internal fun CommunityView(v: MetaRefresh.View?) {
    if (v == null) return
    val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatTile("📚", v.rules.size.toString(), "situações", C.PRIMARY, Modifier.weight(1f))
        StatTile(if (v.active) "✅" else "⏸", if (v.active) "em uso" else "parado", "na tática", if (v.active) C.OK else C.WARN, Modifier.weight(1f))
        StatTile("🔎", if (v.searched == true) "sim" else if (v.searched == false) "não" else "?", "buscou na web", if (v.searched == true) C.OK else C.WARN, Modifier.weight(1f))
    }
    if (v.at != null) Text("Regras de ${fmt.format(Date(v.at))}" + (v.lastAt?.let { " • última pesquisa ${fmt.format(Date(it))}" } ?: ""), fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 6.dp))
    if (v.running) AlertCard("🔵 Pesquisando agora na internet… " + AiStatus.stage.ifBlank { "aguarde" })
    v.problem?.let { AlertCard("⚠ $it") }
    val missing = listOf("U" to "mais fraco", "E" to "parelho", "F" to "mais forte").filter { (k, _) -> v.rules.none { it.rel == k } }
    if (v.rules.isNotEmpty() && missing.isNotEmpty() && !v.running) {
        AlertCard("⚠ Falta a situação: " + missing.joinToString(", ") { it.second } + ". Toque em Atualizar para a IA pesquisar só ela.")
    }
    if (v.rules.isEmpty()) {
        Text("Nenhuma regra da comunidade ainda: a tática usa o modelo do app e os seus resultados.", fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 6.dp))
    }
    for (r in v.rules) {
        val (icon, title, col) = when (r.rel) {
            "U" -> Triple("🛡", "Quando você é MAIS FRACO", C.GOLD)
            "F" -> Triple("💪", "Quando você é MAIS FORTE", C.OK)
            else -> Triple("⚖", "Quando o jogo é PARELHO", C.PRIMARY)
        }
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(C.SURFACE2)
                .drawBehind { drawRect(col, size = Size(5.dp.toPx(), size.height)) }.padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 18.sp)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(title, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = col)
                    Text("contra " + r.vs, fontSize = 12.5.sp, color = C.MUTED)
                }
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.formation, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0x33FFC83D)).padding(horizontal = 10.dp, vertical = 3.dp)) {
                    Text(r.style, color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((lab, v2) in listOf("Pressão" to r.pressure, "Estilo" to r.mentality, "Ritmo" to r.tempo)) {
                    if (v2 == null) continue
                    Column(Modifier.weight(1f)) {
                        Text("$lab $v2", fontSize = 11.5.sp, color = C.MUTED)
                        LinearProgressIndicator(progress = { v2 / 100f }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)), color = col, trackColor = C.BG)
                    }
                }
            }
            if (r.roles.isNotBlank()) Text(r.roles, fontSize = 12.5.sp, color = Color(0xCCFFFFFF), modifier = Modifier.padding(top = 6.dp))
            Text("Fonte: ${r.source}" + listOfNotNull(r.marking?.let { " • marcação $it" }, r.offside?.let { " • fora-de-jogo $it" }).joinToString(""), fontSize = 11.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Para que serve a IA no app (bloquinho com desenho). */
/** Quem decidiu a tática: a IA (com o motivo) ou só o cálculo (com o porquê de a IA não ter entrado). */
@Composable
internal fun AiDecision(j: JSONObject) {
    val ai = j.optJSONObject("ai")
    val used = ai?.optBoolean("used") == true
    val col = if (used) C.OK else C.WARN
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(col.copy(alpha = 0.14f)).padding(10.dp)
    ) {
        val head = when {
            used -> "🤖 Decidida pela IA" + (ai?.optString("by")?.takeIf { it.isNotBlank() }?.let { " ($it, ${ai.optDouble("secs", 0.0)}s)" } ?: "")
            ai == null -> "📐 Só pelo cálculo: gere de novo com uma IA configurada em Ajustes"
            else -> "📐 Só pelo cálculo"
        }
        Text(head, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = col)
        val why = ai?.optString("why")?.takeIf { it.isNotBlank() }
        if (used && why != null) Text(why, fontSize = 12.5.sp, color = Color(0xDDFFFFFF), modifier = Modifier.padding(top = 3.dp))
        val problem = ai?.optString("problem")?.takeIf { it.isNotBlank() }
        if (!used && problem != null) Text("Motivo: $problem", fontSize = 12.5.sp, color = Color(0xCCFFFFFF), modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
internal fun AiUseTile(icon: String, title: String, sub: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(C.SURFACE2).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 20.sp)
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(sub, fontSize = 11.sp, color = C.MUTED, textAlign = TextAlign.Center)
    }
}

/** Um menu só para a IA principal: automático (pelo teste) ou uma escolhida. */
@Composable
internal fun AiPrimaryPicker(pref: String, tick: Int, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val opts = listOf("auto" to "🤖 Automático (pelo teste)") + AiClient.ALL.map { it to AiClient.label(it) }
    val cur = opts.firstOrNull { it.first == pref }?.second ?: opts.first().second
    Column(Modifier.fillMaxWidth()) {
        Text("IA principal", fontSize = 13.sp, color = C.MUTED)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(cur, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text("▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for ((id, name) in opts) {
                    val t = if (id == "auto") null else AiClient.lastTest(ctx, id)
                    val st = when {
                        id == "auto" -> ""
                        !AiClient.hasKey(ctx, id) -> "  • sem chave"
                        t == null -> "  • não testada"
                        t.image -> "  ✔"
                        t.text -> "  ◐ só texto"
                        else -> "  ✘"
                    }
                    DropdownMenuItem(text = { Text(name + st) }, onClick = { open = false; onPick(id) })
                }
            }
        }
        val order = if (tick >= 0) AiClient.providerOrder(ctx, true) else emptyList()
        Text(
            if (order.isEmpty()) "⚠ Nenhuma IA com chave: cole pelo menos uma chave grátis abaixo."
            else "Ordem para ler telas agora: " + order.joinToString(" → ") { AiClient.label(it) } + ". Se uma falhar, passa para a próxima.",
            fontSize = 12.5.sp, color = if (order.isEmpty()) C.WARN else C.MUTED, modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** Cartão de uma IA: luz de status do último teste; a chave e o resto aparecem ao tocar. */
@Composable
internal fun AiProviderCard(id: String, icon: String, title: String, tag: String, where: String, key: String, tick: Int, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var open by rememberSaveable("ai_$id") { mutableStateOf(false) }
    val t = if (tick >= 0) AiClient.lastTest(ctx, id) else null
    val saved = AiClient.hasKey(ctx, id)
    val (col, status) = when {
        key.isBlank() -> C.MUTED to "sem chave"
        !saved -> C.WARN to "toque em Salvar"
        t == null -> C.MUTED to "não testada"
        t.image -> C.OK to "✔ lê imagem • ${t.secs}s"
        t.text -> C.GOLD to "◐ só texto"
        else -> C.LOSS to "✘ não respondeu"
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(C.SURFACE2)
            .border(1.dp, col.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
    ) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(tag, fontSize = 11.5.sp, color = C.MUTED)
            }
            Box(Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text(status, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = col, maxLines = 1)
            }
            Text(if (open) " ▲" else " ▼", fontSize = 12.5.sp, color = C.MUTED)
        }
        if (open) Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
            Text("🔑 Chave em: $where", fontSize = 12.5.sp, color = C.PRIMARY)
            if (t != null && !t.image && t.error != null && key.isNotBlank()) {
                Text("Último teste: ${t.error}", fontSize = 12.5.sp, color = C.WARN, modifier = Modifier.padding(top = 4.dp))
            }
            if (t?.model != null) Text("Modelo usado: ${t.model}", fontSize = 11.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
internal fun SettingsTab() {
    val ctx = LocalContext.current
    val tick = rememberTick(2000L)
    var gKey by remember { mutableStateOf(Settings.get(ctx, Settings.GEMINI_KEY, "")) }
    var gModel by remember { mutableStateOf(Settings.get(ctx, Settings.GEMINI_MODEL, Settings.DEFAULT_GEMINI_MODEL)) }
    var clKey by remember { mutableStateOf(Settings.get(ctx, Settings.CLAUDE_KEY, "")) }
    var clModel by remember { mutableStateOf(Settings.get(ctx, Settings.CLAUDE_MODEL, Settings.DEFAULT_CLAUDE_MODEL)) }
    var cKey by remember { mutableStateOf(Settings.get(ctx, Settings.COMPAT_KEY, "")) }
    var cBase by remember { mutableStateOf(Settings.get(ctx, Settings.COMPAT_BASE, Settings.DEFAULT_COMPAT_BASE)) }
    var cModel by remember { mutableStateOf(Settings.get(ctx, Settings.COMPAT_MODEL, Settings.DEFAULT_COMPAT_MODEL)) }
    var mKey by remember { mutableStateOf(Settings.get(ctx, Settings.MISTRAL_KEY, "")) }
    var oKey by remember { mutableStateOf(Settings.get(ctx, Settings.OPENROUTER_KEY, "")) }
    var cap by remember { mutableStateOf(Settings.get(ctx, Settings.DAILY_CAP, "80")) }
    var pref by remember { mutableStateOf(Settings.get(ctx, Settings.PREFERRED, "auto")) }
    var saved by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 96.dp)) {
        PageTitle("⚙", "Ajustes")
        SectionLabel("Avisos", "Como o app te chama quando algo precisa de você")
        NotificationsPanel()
        SectionLabel("Preferências", "Regras do app e táticas da comunidade")
        var spendBc by remember { mutableStateOf(Settings.get(ctx, "spend_bc", "0") == "1") }
        Fold("💎", "Boss Coins", if (spendBc) "gastar" else "preservar") {
            SwitchRow("Aceitar dicas que gastam Boss Coins (amistoso, estágio, treino secreto, olheiro imediato)", spendBc) {
                spendBc = it
                Settings.put(ctx, "spend_bc", if (it) "1" else "0")
            }
            Text("Desligado (padrão): o app nunca sugere gastar Boss Coins; só dicas que usam dinheiro comum ($).", fontSize = 13.sp, color = C.MUTED)
        }
        var closeOsm by remember { mutableStateOf(Settings.get(ctx, "close_osm", "1") == "1") }
        Fold("⏹", "Ao encerrar a leitura", if (closeOsm) "abre este app" else "só encerra") {
            SwitchRow("Abrir este app ao encerrar", closeOsm) {
                closeOsm = it
                Settings.put(ctx, "close_osm", if (it) "1" else "0")
            }
            Text("O app abre sozinho e processa o que leu. O OSM fica aberto em segundo plano: feche-o à mão se quiser (o app não consegue fechar outro app neste aparelho).", fontSize = 13.sp, color = C.MUTED)
        }
        var webCrests by remember { mutableStateOf(Settings.get(ctx, "web_crests", "1") == "1") }
        Fold("🛡", "Escudos reais da web", if (webCrests) "ligado" else "desligado") {
            SwitchRow("Buscar escudos reais pelo nome do time", webCrests) {
                webCrests = it
                Settings.put(ctx, "web_crests", if (it) "1" else "0")
            }
            Text("Só o NOME do clube é enviado (repositório football-logos e TheSportsDB, gratuitos). Quem não for achado continua com o recorte do jogo. Cada clube é buscado uma vez e vale para os 4 slots.", fontSize = 13.sp, color = C.MUTED)
            // progresso em tempo real: bandeiras do Flagpedia (países) e escudos reais dos clubes que o app já conhece (calendários, rivais, os 4 times)
            val prTick = rememberTick(1000L)
            val clubs = rememberLoaded(emptyList<String>(), prTick / 3, UiBus.version) {
                val r = Repo(ctx)
                (1..4).flatMap { s ->
                    val f = r.fieldMap(s)
                    listOfNotNull(f[K.TEAM]?.value, f[K.RIVAL_TEAM]?.value) + r.dao.matchesOf(s).mapNotNull { it.opponent }
                }.map { it.trim() }.filter { Txt.key(it).length >= 3 && !Fixtures.notAClub(it) }.distinctBy { Txt.key(it) }
            }
            LaunchedEffect(webCrests, clubs.size) {
                if (webCrests) { FlagWeb.ensureAll(ctx); clubs.forEach { CrestWeb.request(ctx, it) } }
            }
            val fl = FlagWeb.progress(ctx.filesDir).also { prTick.let { _ -> } }
            val cr = CrestWeb.stats(ctx.filesDir, clubs)
            val flPct = if (fl.second > 0) fl.first * 100 / fl.second else 0
            val crDone = cr.first + cr.second
            val crPct = if (clubs.isNotEmpty()) crDone * 100 / clubs.size else 0
            Text("Bandeiras: ${fl.first} de ${fl.second}" + if (flPct >= 100) " ✅" else "", fontSize = 13.sp, color = Color.White, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            Bar(flPct)
            Text("Escudos: ${cr.first} reais de ${clubs.size} clubes" + (if (cr.third > 0) " • buscando ${cr.third}" else if (clubs.isNotEmpty()) " ✅" else "") + (if (cr.second > 0) " • ${cr.second} sem escudo real (uso o recorte do jogo)" else ""), fontSize = 13.sp, color = Color.White, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            Bar(crPct)
            if (cr.second > 0) GhostButton("Tentar de novo os que faltaram") { CrestWeb.clearMisses(ctx.filesDir); UiBus.bump() }
            // clubes que nenhuma fonte achou: o app já tentou variações do nome (sem FC/SK, ß→ss, Red Bull→RB…); aqui dá para dizer com que outro nome procurar
            var aliasFor by remember { mutableStateOf<String?>(null) }
            val missingClubs = CrestWeb.missing(ctx.filesDir, clubs)
            if (missingClubs.isNotEmpty()) {
                Text("Sem escudo real (toque para procurar por outro nome):", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
                for (c in missingClubs.take(20)) {
                    // o botão ocupa a linha toda: o nome do clube vem ACIMA dele (antes ficava espremido a largura zero e só aparecia "Outro nome")
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(c + (CrestWeb.aliasOf(ctx.filesDir, c)?.let { "  → procurando como \"$it\"" } ?: ""), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(bottom = 4.dp))
                        GhostButton("Procurar por outro nome") { aliasFor = c }
                    }
                }
            }
            aliasFor?.let { club ->
                var txt by remember(club) { mutableStateOf(CrestWeb.aliasOf(ctx.filesDir, club) ?: "") }
                AlertDialog(
                    onDismissRequest = { aliasFor = null },
                    title = { Text("Escudo de $club") },
                    text = {
                        Column {
                            Text("Com que nome procurar? Ex.: o nome oficial do clube.", fontSize = 13.sp)
                            OutlinedTextField(value = txt, onValueChange = { txt = it }, singleLine = true, modifier = Modifier.padding(top = 8.dp))
                        }
                    },
                    confirmButton = { TextButton(onClick = { if (txt.trim().length >= 3) CrestWeb.setAlias(ctx, club, txt); aliasFor = null }) { Text("Procurar") } },
                    dismissButton = { TextButton(onClick = { aliasFor = null }) { Text("Cancelar") } }
                )
            }
        }
        val atlasTick = rememberTick(4000L)
        val atlasCounts = rememberLoaded(Pair(0, 0), atlasTick, UiBus.version) { Pair(Atlas.count(Repo(ctx), Atlas.Kind.NAT), Atlas.count(Repo(ctx), Atlas.Kind.LEAGUE)) }
        Fold("🏳", "Atlas de bandeiras e ligas", "${atlasCounts.first} países • ${atlasCounts.second} ligas") {
            Text("O app aprende o nome de cada bandeira (e o emblema de cada liga) olhando os filtros do OSM. Com a captura ligada: Lista de transferências → toque no filtro Nacionalidade e role a lista até o fim; depois o filtro Liga. Vale para os 4 slots e é feito uma vez só.", fontSize = 13.sp, color = C.MUTED)
            Text(if (atlasCounts.first >= 150) "✅ Nacionalidades completas: o Elenco mostra o país de cada jogador." else if (atlasCounts.first > 0) "⚠ Faltam nacionalidades: role o filtro até o fim." else "Ainda não li o filtro de Nacionalidade.", fontSize = 13.sp, color = if (atlasCounts.first >= 150) C.OK else C.GOLD, modifier = Modifier.padding(top = 4.dp))
            Text(if (atlasCounts.second >= 80) "✅ Ligas lidas: o emblema aparece no cabeçalho do slot." else if (atlasCounts.second > 0) "⚠ Faltam ligas: role o filtro Liga até o fim." else "Ainda não li o filtro de Liga.", fontSize = 13.sp, color = if (atlasCounts.second >= 80) C.OK else C.GOLD, modifier = Modifier.padding(top = 2.dp))
        }
        val exportScope = rememberCoroutineScope()
        var exportMsg by remember { mutableStateOf("") }
        var metaMsg by remember { mutableStateOf("") }
        KnowledgeFold(exportScope)
        Fold("🌐", "Táticas da comunidade (avançado)", startOpen = false) {
            val metaTick = rememberTick(5000L)
            val mv = rememberLoaded<MetaRefresh.View?>(null, metaTick, metaMsg) { MetaRefresh.view(Repo(ctx)) }
            CommunityView(mv)
            OutlinedButton(
                onClick = {
                    metaMsg = ""
                    exportScope.launch { metaMsg = withContext(Dispatchers.IO) { MetaRefresh.refresh(ctx) } }
                },
                enabled = !MetaRefresh.running,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text(if (MetaRefresh.running) "🔎 Pesquisando…" else "🌐 Atualizar agora pela internet") }
            if (metaMsg.isNotBlank()) Text(metaMsg, fontSize = 13.sp, color = C.WARN)
            Hint(
                "A IA pesquisa (fórum do OSM, osmnextlevel, osmcoach, YouTube…) qual tática a comunidade usa em cada situação: quando você é mais fraco, " +
                    "parelho ou mais forte que o rival. O app usa isso como PONTO DE PARTIDA da tática; depois os resultados dos seus 4 slots ajustam por cima. " +
                    "Regras com mais de 30 dias deixam de valer. Atualiza sozinho uma vez por dia.",
                "como o app usa"
            )
        }
        SectionLabel("Dados", "Capturas guardadas, leitura com IA e diagnóstico")
        Fold("📼", "Capturas e leitura com IA") { SessionsBody() }
        Fold("📦", "Exportar diagnóstico") {
            Hint(
                "Gera um arquivo com TUDO o que o app tem (campos, elenco, calendário, análises do fim do jogo, táticas, telas lidas, " +
                    "registro de erros e as imagens guardadas; das chaves de IA vão só os 4 últimos caracteres). " +
                    "Fica salvo no celular (em Download/OSM Coach ou na pasta que você escolher) e você pode compartilhar depois.",
                "o que vai no arquivo"
            )
            var exported by remember { mutableStateOf<DiagExport.Result?>(null) }
            // pasta escolhida uma vez e guardada (o seletor do Android pede a permissão de gravar nela)
            var folderName by remember { mutableStateOf(DiagExport.folderLabel(ctx)) }
            val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) {
                    try {
                        DiagExport.setFolder(ctx, uri)
                        exportMsg = "Pasta escolhida: os próximos diagnósticos vão para ela."
                    } catch (e: Exception) {
                        exportMsg = "Essa pasta não deixou o app gravar: escolha outra. (" + (e.message ?: "") + ")"
                    }
                    folderName = DiagExport.folderLabel(ctx)
                }
            }
            KV("📁 Salvar em", folderName)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { pickFolder.launch(DiagExport.folder(ctx)) }, modifier = Modifier.weight(1f)) {
                    Text("📁 Escolher pasta", fontSize = 13.sp)
                }
                if (DiagExport.folder(ctx) != null) {
                    OutlinedButton(
                        onClick = {
                            DiagExport.setFolder(ctx, null)
                            folderName = DiagExport.folderLabel(ctx)
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Voltar ao padrão", fontSize = 13.sp) }
                }
            }
            OutlinedButton(
                onClick = {
                    exportMsg = "Gerando (pode levar alguns segundos com as imagens)…"
                    exportScope.launch {
                        try {
                            val r = withContext(Dispatchers.IO) { DiagExport.export(ctx) }
                            exported = r
                            exportMsg = DiagExport.summary(r)
                        } catch (e: Exception) {
                            exportMsg = "Não consegui gerar: " + (e.message ?: e.javaClass.simpleName)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) { Text("📥 Exportar diagnóstico completo") }
            if (exportMsg.isNotBlank()) Text(exportMsg, fontSize = 13.sp, color = if (exported?.zipUri != null) C.OK else C.WARN)
            // desfazer perda de dados: volta elenco, calendário, campos e planos de um diagnóstico salvo
            var restoreRoot by remember { mutableStateOf<JSONObject?>(null) }
            var restoreMsg by remember { mutableStateOf("") }
            val pickDiag = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    exportScope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { DiagRestore.read(ctx, uri) }.getOrNull() }
                        if (r == null || DiagRestore.slotsIn(r).isEmpty()) restoreMsg = "Esse arquivo não é um diagnóstico do OSM Coach."
                        else restoreRoot = r
                    }
                }
            }
            OutlinedButton(
                onClick = { pickDiag.launch(arrayOf("application/json", "application/zip", "*/*")) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) { Text("♻ Restaurar dados de um diagnóstico") }
            if (restoreMsg.isNotBlank()) Text(restoreMsg, fontSize = 13.sp, color = if (restoreMsg.startsWith("Restaurado")) C.OK else C.WARN)
            restoreRoot?.let { root ->
                val infos = remember(root) { DiagRestore.slotsIn(root) }
                val chosen = remember(root) { mutableStateMapOf<Int, Boolean>().apply { for (i in infos) put(i.slot, i.players > 0 || i.matches > 0) } }
                val fmtD = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                AlertDialog(
                    onDismissRequest = { restoreRoot = null },
                    title = { Text("Restaurar do diagnóstico de ${fmtD.format(Date(root.optLong("at")))}") },
                    text = {
                        Column {
                            Text("Escolha os slots. O que foi lido depois desse diagnóstico continua valendo.", fontSize = 13.sp, color = C.MUTED)
                            for (i in infos) {
                                Row(
                                    Modifier.fillMaxWidth().clickable { chosen[i.slot] = !(chosen[i.slot] ?: false) }.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Switch(checked = chosen[i.slot] ?: false, onCheckedChange = { chosen[i.slot] = it })
                                    Spacer(Modifier.width(8.dp))
                                    Text("S${i.slot} ${i.team} • ${i.players} jogadores • ${i.matches} jogos", fontSize = 14.sp)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val sel = chosen.filterValues { it }.keys
                            restoreRoot = null
                            restoreMsg = "Restaurando…"
                            exportScope.launch {
                                restoreMsg = withContext(Dispatchers.IO) {
                                    runCatching { DiagRestore.restore(ctx, root, sel) }.getOrElse { "Erro ao restaurar: " + (it.message ?: "?") }
                                }
                                UiBus.version++
                            }
                        }) { Text("Restaurar") }
                    },
                    dismissButton = { TextButton(onClick = { restoreRoot = null }) { Text("Cancelar") } }
                )
            }
            exported?.let { r ->
                OutlinedButton(onClick = { DiagExport.share(ctx, r) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text("📤 Compartilhar o arquivo")
                }
            }
        }
        val readOk = serviceEnabled(ctx) && Diag.serviceConnected && batteryUnrestricted(ctx)
        SectionLabel("Sistema", "Leitura das telas do OSM e inteligência artificial")
        Fold("📱", "Leitura automática", if (readOk) "ok" else "⚠", startOpen = !readOk) {
            StatusLight(serviceEnabled(ctx), "Acessibilidade ativada")
            StatusLight(Diag.serviceConnected && tick >= 0, "Serviço conectado")
            StatusLight(batteryUnrestricted(ctx), "Bateria sem restrição")
            Spacer(Modifier.height(6.dp))
            Button(onClick = { openSettings(ctx, android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS) }, modifier = Modifier.fillMaxWidth()) {
                Text("Abrir configurações de acessibilidade")
            }
            OutlinedButton(
                onClick = { openSettings(ctx, android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Info do app (permitir configurações restritas)") }
            OutlinedButton(
                onClick = { openBatterySettings(ctx) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Bateria: não otimizar este app") }
        }
        val anyKey = listOf(gKey, cKey, mKey, oKey, clKey).any { it.isNotBlank() }
        Fold("🤖", "Inteligência artificial", if (!anyKey) "sem chave" else null, startOpen = !anyKey) {
            val saveAll = {
                Settings.put(ctx, Settings.GEMINI_KEY, gKey)
                Settings.put(ctx, Settings.GEMINI_MODEL, gModel.ifBlank { Settings.DEFAULT_GEMINI_MODEL })
                Settings.put(ctx, Settings.CLAUDE_KEY, clKey)
                Settings.put(ctx, Settings.CLAUDE_MODEL, clModel.ifBlank { Settings.DEFAULT_CLAUDE_MODEL })
                Settings.put(ctx, Settings.COMPAT_KEY, cKey)
                Settings.put(ctx, Settings.COMPAT_BASE, cBase.ifBlank { Settings.DEFAULT_COMPAT_BASE })
                Settings.put(ctx, Settings.COMPAT_MODEL, cModel.ifBlank { Settings.DEFAULT_COMPAT_MODEL })
                Settings.put(ctx, Settings.MISTRAL_KEY, mKey)
                Settings.put(ctx, Settings.OPENROUTER_KEY, oKey)
                Settings.put(ctx, Settings.DAILY_CAP, cap.ifBlank { "80" })
            }
            // o que cada IA faz no app: ler as telas (imagem), pensar a tática (texto) e pesquisar na internet
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AiUseTile("📷", "Ler telas", "OCR do celular + IA com imagem", Modifier.weight(1f))
                AiUseTile("🧠", "Tática", "IA de texto", Modifier.weight(1f))
                AiUseTile("🌐", "Internet", "Gemini ou Groq", Modifier.weight(1f))
            }
            AiPrimaryPicker(pref, tick) { pref = it; Settings.put(ctx, Settings.PREFERRED, it) }
            Text("Gratuitas", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = C.OK, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            AiProviderCard("gemini", "✨", "Gemini (Google)", "grátis • lê imagem • busca no Google", "aistudio.google.com › Get API key", gKey, tick) {
                OutlinedTextField(gKey, { gKey = it }, label = { Text("Chave(s) do Google AI Studio") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                KV("Cota hoje", AiClient.geminiStatus(ctx))
                Hint(
                    "Limite DIÁRIO por modelo e por projeto do Google. Pode colar várias chaves separadas por vírgula (cada uma de um PROJETO " +
                        "diferente): quando a cota de uma acaba, o app usa a próxima. \"Sobrecarregado (503)\" é o Google cheio naquele momento: " +
                        "o app pula para a próxima IA.",
                    "cota e várias chaves"
                )
                Fold("⚙", "Avançado") {
                    OutlinedTextField(gModel, { gModel = it }, label = { Text("Modelo (auto = detecta sozinho)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(cap, { cap = it }, label = { Text("Teto diário de chamadas") }, modifier = Modifier.fillMaxWidth())
                    KV("Chamadas hoje", AiClient.usedToday(ctx).toString())
                }
            }
            AiProviderCard("compat", "⚡", "Groq", "grátis • rápido • busca na internet", "console.groq.com › API Keys", cKey, tick) {
                OutlinedTextField(cKey, { cKey = it }, label = { Text("Chave do Groq") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Fold("⚙", "Avançado (xAI ou outro)") {
                    OutlinedTextField(cBase, { cBase = it }, label = { Text("URL base") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(cModel, { cModel = it }, label = { Text("Modelo de texto (auto = detecta sozinho)") }, modifier = Modifier.fillMaxWidth())
                }
            }
            AiProviderCard("openrouter", "🔀", "OpenRouter", "grátis (modelos :free) • lê imagem", "openrouter.ai/settings/keys (limite de crédito 0) • em Settings › Privacy ligue \"Allow free endpoints that train on request data\"", oKey, tick) {
                OutlinedTextField(oKey, { oKey = it }, label = { Text("Chave do OpenRouter") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
            Text("Pagas (opcionais)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = C.GOLD, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            AiProviderCard("mistral", "🌀", "Mistral", "pago • o plano Free não libera chave de API", "console.mistral.ai › API Keys (só com plano pago)", mKey, tick) {
                OutlinedTextField(mKey, { mKey = it }, label = { Text("Chave da Mistral") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
            AiProviderCard("claude", "🅲", "Claude (Anthropic)", "pago • só com créditos no Console", "console.anthropic.com (a assinatura Pro/Max não inclui a API)", clKey, tick) {
                OutlinedTextField(clKey, { clKey = it }, label = { Text("Chave do Console da Anthropic") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(clModel, { clModel = it }, label = { Text("Modelo") }, modifier = Modifier.fillMaxWidth())
            }
            Button(
                onClick = {
                    saveAll()
                    saved = "Salvo. Testando cada IA…"
                    startAiTest(ctx)
                },
                enabled = !AiTest.running,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            ) { Text(if (AiTest.running) "Testando… (${AiStatus.stage.ifBlank { "aguarde" }})" else "💾 Salvar e testar as IAs") }
            if (saved.isNotBlank() && !AiTest.running) Text(saved.removeSuffix(" Testando cada IA…"), color = C.OK, fontSize = 13.sp)
            if (AiTest.result.isNotBlank() && !AiTest.running) Text(AiTest.result, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            else AiClient.lastWebTest(ctx)?.let { (ok, text) -> StatusLight(ok, text.removePrefix("✔ ").removePrefix("✘ ")) }
        }
    }
}

/** O que o app aprendeu com os gols reais: cada escolha de tática e quanto ela mexeu nos gols. */
@Composable
internal fun LearnedCard(m: Learned.Model?) {
    Title("🧠 O que os seus jogos ensinaram")
    Panel {
        if (m == null) {
            Text(
                "Preciso de pelo menos ${Learned.MIN_GAMES} jogos com placar (somando os 4 slots) para aprender o que funciona para você. " +
                    "Abra a análise de cada jogo depois que ele acabar.",
                color = C.MUTED, fontSize = 14.sp
            )
            return@Panel
        }
        Text(
            "Aprendido com ${m.n} jogos reais: a simulação usa isso para escolher a tática." +
                if (m.trusted) "" else " Com menos de ${Learned.TRUSTED_GAMES} jogos, as regras de segurança ainda valem.",
            color = C.MUTED, fontSize = 13.sp
        )
        Spacer(Modifier.height(6.dp))
        val rows = Learned.LABELS.indices.map { i -> Triple(Learned.LABELS[i], m.my[i], m.opp[i]) }
            .filter { kotlin.math.abs(it.second) >= 0.08 || kotlin.math.abs(it.third) >= 0.08 }
            .sortedByDescending { kotlin.math.abs(it.second) + kotlin.math.abs(it.third) }
        if (rows.isEmpty()) Text("Nenhuma escolha mudou os gols de forma clara até agora.", fontSize = 14.sp)
        for ((label, a, b) in rows) {
            val pa = ((kotlin.math.exp(a) - 1) * 100).toInt()
            val pb = ((kotlin.math.exp(b) - 1) * 100).toInt()
            // bom = mais gols meus e menos do rival
            val good = a - b
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (good >= 0.08) "▲" else if (good <= -0.08) "▼" else "•", color = if (good >= 0.08) C.OK else if (good <= -0.08) C.BAD else C.MUTED, fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        "seus gols ${if (pa >= 0) "+" else ""}$pa% • gols do rival ${if (pb >= 0) "+" else ""}$pb%",
                        color = C.MUTED, fontSize = 13.sp
                    )
                }
            }
        }
    }
}

