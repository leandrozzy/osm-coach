package com.osmaicoach.collector

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Tudo que o app sabe de um jogador do meu elenco (lido do banco; nada é inventado). */
internal class PlayerInfo(
    val yellow: Boolean, val red: Boolean, val injured: Boolean, val noAppeal: Boolean,
    val games: Int?, val reads: Int, val rankAll: Int, val rankCat: Int, val totalAll: Int, val totalCat: Int,
    val avgStrength: Double?, val hist: List<Growth.Point>, val bought: Ledger.Entry?, val photo: PhotoWeb.Item
)

internal suspend fun loadPlayerInfo(ctx: android.content.Context, slot: Int, p: PlayerEntity): PlayerInfo {
    val repo = Repo(ctx)
    suspend fun plan(kind: String): JSONObject = runCatching { JSONObject(repo.dao.plan(slot, kind)?.json ?: "{}") }.getOrNull() ?: JSONObject()
    val mine = repo.playersOf(slot).filter { it.owner == "MY" }
    val inj = plan("injured").optJSONArray("names")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
    val noAppeal = plan("noappeal").optJSONObject("names")?.has(p.nameKey) == true
    val games = runCatching { SlotRules.appearances(repo, slot)[p.nameKey] }.getOrNull()
    val reads = plan("squadreads").optInt(p.nameKey, 0)
    val byStr = mine.sortedByDescending { it.strength ?: 0 }
    val cat = mine.filter { (it.cat ?: "?") == (p.cat ?: "?") }.sortedByDescending { it.strength ?: 0 }
    val strengths = mine.mapNotNull { it.strength }
    val hist = Growth.points(plan("phist"), p.nameKey)
    val bought = Ledger.parse(repo.dao.plan(slot, "ledger")?.json).lastOrNull { it.type == "buy" && it.key == p.nameKey }
    return PlayerInfo(
        yellow = plan("cards").optBoolean(p.nameKey), red = plan("reds").optBoolean(p.nameKey), injured = p.nameKey in inj, noAppeal = noAppeal,
        games = games, reads = reads,
        rankAll = byStr.indexOfFirst { it.nameKey == p.nameKey } + 1, rankCat = cat.indexOfFirst { it.nameKey == p.nameKey } + 1,
        totalAll = byStr.size, totalCat = cat.size, avgStrength = if (strengths.isEmpty()) null else strengths.average(),
        hist = hist, bought = bought, photo = PhotoWeb.itemOf(ctx, p)
    )
}

@Composable
private fun DSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(16.dp)).background(C.SURFACE2.copy(alpha = 0.55f))
            .border(1.dp, C.PRIMARY.copy(alpha = 0.25f), RoundedCornerShape(16.dp)).padding(12.dp)
    ) {
        Text(title.uppercase(), fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold, color = C.PRIMARY, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Box(Modifier.padding(end = 6.dp, top = 4.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.2f)).border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    }
}

@Composable
private fun Bar(label: String, v: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = C.MUTED, modifier = Modifier.width(64.dp))
        LinearProgressIndicator(
            progress = { v.coerceIn(0, 100) / 100f }, modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(50)),
            color = if (v < 50) C.LOSS else if (v < 70) C.WARN else C.OK, trackColor = C.BG
        )
        Text("$v%", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(46.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun Row2(a: @Composable RowScope.() -> Unit) = Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = a)

@Composable
private fun RowScope.Act(text: String, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (danger) C.BAD else C.PRIMARY)
    ) { Text(text, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
/** Detalhes do jogador (Elenco → toque no jogador): foto grande, tudo que foi lido, evolução, estado e as opções do jogo/app. */
@Composable
internal fun PlayerDetailDialog(slot: Int, p: PlayerEntity, d: SlotData, trainText: String?, onFlag: () -> Unit = {}, onDone: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val out = p.nameKey in d.unavailable
    val info = rememberLoaded<PlayerInfo?>(null, rememberTick(4000L), UiBus.version to p.nameKey) { loadPlayerInfo(ctx, slot, p) }
    var tool by remember { mutableStateOf(0) }
    var confirmRemove by remember { mutableStateOf(false) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            android.widget.Toast.makeText(ctx, "Processando a foto de ${p.name}…", android.widget.Toast.LENGTH_SHORT).show()
            AppScope.scope.launch(Dispatchers.IO) {
                val bmp = PhotoWeb.decodeUri(ctx, uri)
                val ok = bmp != null && PhotoWeb.saveManual(ctx, p.nameKey, bmp)
                withContext(Dispatchers.Main) { android.widget.Toast.makeText(ctx, if (ok) "Foto de ${p.name} salva." else "Não consegui ler essa imagem.", android.widget.Toast.LENGTH_LONG).show() }
            }
        }
    }
    fun train(v: Boolean?) { AppScope.scope.launch(Dispatchers.IO) { Repo(ctx).setTrainingOverride(slot, p.nameKey, v); UiBus.version++ } }
    val cat = p.cat ?: "?"
    val ft = d.fitness[p.nameKey]
    val iso = d.isos[p.nameKey] ?: Flags.iso(d.countries[p.nameKey])
    val country = Flags.countryLabel(d.countries[p.nameKey])
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(24.dp), color = C.SURFACE, modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.94f)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Jogador • Slot $slot", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDone) { Text("Fechar") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = 16.dp)) {
                    // cabeçalho: foto grande + nome + força
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val img = remember(p.nameKey, UiBus.version) { PhotoWeb.bitmapAny(ctx.filesDir, p.nameKey)?.asImageBitmap() }
                        val m = Modifier.size(104.dp).clip(CircleShape).border(2.dp, catColor(cat), CircleShape).clickable { pickPhoto.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/*")) }
                        if (img != null) Image(img, null, contentScale = ContentScale.Crop, modifier = m.background(Color.White))
                        else Canvas(m) {
                            val w = size.width; val h = size.height
                            drawRect(Color(0xFFF3F5F9)); val ink = Color(0xFFB4BDD0)
                            drawCircle(ink, w * 0.20f, Offset(w * 0.5f, h * 0.40f))
                            drawOval(ink, Offset(w * 0.16f, h * 0.66f), androidx.compose.ui.geometry.Size(w * 0.68f, h * 0.70f))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                FlagBadge(iso, d.flagRich[p.nameKey], null, 30.dp, 20.dp, d.flagImg[p.nameKey], soft = true)
                                Text(" " + (country ?: "país não identificado"), fontSize = 13.sp, color = C.MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("${p.posCode ?: "?"} • ${p.age ?: "?"} anos", fontSize = 14.sp, color = Color.White, modifier = Modifier.padding(top = 2.dp))
                        }
                        Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(catColor(cat).copy(alpha = 0.35f)).border(1.5.dp, catColor(cat), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(p.strength?.toString() ?: "?", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                                Text("FORÇA", fontSize = 9.sp, color = C.MUTED)
                            }
                        }
                    }
                    // situação
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Start) {
                        FlowRow {
                            if (p.forSale == true) Chip("🏷 À venda", C.WARN)
                            if (out) Chip("⛔ Fora do próximo jogo", C.LOSS)
                            if (info?.red == true) Chip("🟥 Suspenso", C.LOSS)
                            if (info?.yellow == true) Chip("🟨 Cartão amarelo", C.GOLD)
                            if (info?.injured == true) Chip("🚑 Lesionado", C.LOSS)
                            if (info?.noAppeal == true) Chip("⚖ Sem recurso", C.WARN)
                            if (trainText != null) Chip("🏋 $trainText", C.PRIMARY)
                            if (p.forSale != true && !out && info?.red != true && info?.injured != true && info?.yellow != true && trainText == null) Chip("✅ Disponível", C.OK)
                        }
                    }
                    WorldRealBox(slot, p.name)   // identidade real (Base Mundial), camada separada do jogo
                    DSection("Informações") {
                        KV("Setor / posição", "${cat} • ${p.posCode ?: "?"}")
                        KV("Idade", p.age?.let { "$it anos" } ?: "?")
                        KV("Valor de mercado", p.valueText ?: "?")
                        val v = Money.parse(p.valueText)
                        if (v != null && (p.strength ?: 0) > 0) KV("Valor por ponto de força", fmtM(v / p.strength!!))
                        if (info != null) {
                            if (info.rankAll > 0) KV("Posição no elenco (força)", "${info.rankAll}º de ${info.totalAll}")
                            if (info.rankCat > 0) KV("Posição no setor $cat", "${info.rankCat}º de ${info.totalCat}")
                            val avg = info.avgStrength; val s = p.strength
                            if (avg != null && s != null) KV("Versus a média do elenco", String.format(java.util.Locale.US, "%+.1f (média %.1f)", s - avg, avg))
                            info.games?.let { KV("Jogos (nos relatórios lidos)", "pelo menos $it") }
                            if (info.reads > 0) KV("Vezes que li este jogador", "${info.reads}")
                        }
                        KV("Última leitura", ago(p.updatedAt))
                        if (ft != null) { Bar("Condição", ft.first); Bar("Moral", ft.second) }
                        if (trainText != null) KV("Treino", trainText)
                        info?.bought?.let { b -> KV("Comprado", (b.price?.let { fmtM(it) } ?: "?") + (if (b.estimated) " (estimado)" else "") + " • " + fmtTime(b.at)) }
                    }
                    val hist = info?.hist.orEmpty().filter { it.strength != null }
                    if (hist.size >= 2) DSection("Evolução") {
                        val first = hist.first(); val last = hist.last()
                        val dd = (last.strength ?: 0) - (first.strength ?: 0)
                        val days = ((last.at - first.at) / 86400000L).coerceAtLeast(0)
                        Text("Força ${first.strength} → ${last.strength} (${if (dd >= 0) "+" else ""}$dd) em $days dia(s)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        val vs = hist.mapNotNull { it.value }
                        if (vs.size >= 2) Text("Valor ${fmtM(vs.first())} → ${fmtM(vs.last())}", fontSize = 13.sp, color = C.MUTED)
                        val pts = hist.map { it.strength!!.toFloat() }
                        Canvas(Modifier.fillMaxWidth().height(60.dp).padding(top = 8.dp)) {
                            val mn = pts.min(); val mx = pts.max(); val rng = (mx - mn).coerceAtLeast(1f)
                            val path = Path()
                            pts.forEachIndexed { i, v ->
                                val x = size.width * i / (pts.size - 1).coerceAtLeast(1); val y = size.height * (1f - (v - mn) / rng)
                                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(path, C.PRIMARY, style = Stroke(width = 4f, cap = StrokeCap.Round))
                        }
                    }
                    DSection("Foto") {
                        val ph = info?.photo
                        if (ph != null) {
                            KV("Origem", PhotoWeb.sourceLabel(ph.source) + (if (ph.blocked) " (busca automática desligada)" else ""))
                            if (ph.at > 0) KV("Salva", "${ago(ph.at)} • ${ph.bytes / 1024} KB")
                        }
                        Row2 {
                            Act("📷 Alterar foto") { pickPhoto.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/*")) }
                            Act("🔁 Buscar de novo") { AppScope.scope.launch(Dispatchers.IO) { PhotoWeb.retryPhoto(ctx, p.nameKey); PhotoWeb.run(ctx, force = true) }; android.widget.Toast.makeText(ctx, "Buscando outra foto…", android.widget.Toast.LENGTH_SHORT).show() }
                        }
                        Row2 {
                            Act("🗑 Remover foto", danger = true, enabled = ph != null && ph.source in listOf("manual", "wiki", "api")) { AppScope.scope.launch(Dispatchers.IO) { PhotoWeb.removePhoto(ctx, p.nameKey) } }
                            Act("🏳 Bandeira") { onDone(); onFlag() }
                        }
                        Text("A foto é salva como as outras e vale para os 4 slots. Fundo liso é removido; fundo com estádio/torcida fica como está (só enquadra no rosto).", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 6.dp))
                    }
                    DSection("No jogo") {
                        Row2 {
                            Act(if (out) "✓ Volta a jogar" else "⛔ Fora do próximo jogo") { AppScope.scope.launch(Dispatchers.IO) { Repo(ctx).setUnavailable(slot, p.nameKey, !out); UiBus.version++ } }
                        }
                        Text("Treino (o app lê a tela de Treinamento; corrija só se errou)", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
                        Row2 {
                            Act("🏋 Treinando") { train(true) }
                            Act("Não treina") { train(false) }
                            Act("Automático") { train(null) }
                        }
                    }
                    if (p.owner == "MY") DSection("Ferramentas") {
                        Row2 {
                            Act("⚖ Comparar") { tool = 1 }
                            Act("💰 Preço de venda") { tool = 2 }
                        }
                        Row2 {
                            Act("🎨 Criar arte") {
                                AppScope.scope.launch(Dispatchers.IO) {
                                    val now = System.currentTimeMillis()
                                    val it = runCatching { Arts.make(ctx, Arts.Cand("player|$slot|${p.nameKey}|$now", slot, "PLAYER", "Destaque: ${p.name}", now, p.nameKey)) }.getOrNull()
                                    withContext(Dispatchers.Main) { android.widget.Toast.makeText(ctx, if (it != null) "Arte criada: veja a aba Artes." else (ArtsAi.lastError ?: "Não consegui criar a arte."), android.widget.Toast.LENGTH_LONG).show() }
                                }
                                onDone()
                            }
                        }
                    }
                    DSection("Correções") {
                        var newName by remember { mutableStateOf(p.name) }
                        Text("Leitura errada? Corrija o nome (vale nas próximas leituras):", fontSize = 12.5.sp, color = C.MUTED)
                        OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row2 {
                            Act("✏ Salvar nome", enabled = newName.trim() != p.name && newName.trim().length >= 2) {
                                AppScope.scope.launch(Dispatchers.IO) { Repo(ctx).renamePlayer(slot, p.nameKey, newName); UiBus.version++ }
                                onDone()
                            }
                            Act("🗑 Remover do elenco", danger = true) { confirmRemove = true }
                        }
                    }
                }
            }
        }
    }
    if (tool == 1) PlayerCompareDialog(slot, p) { tool = 0 }
    if (tool == 2) PriceDialog(slot, p) { tool = 0 }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remover ${p.name}?") },
        text = { Text("Use só se ele não existe no seu elenco (erro de leitura) ou já saiu. Ele não volta mais.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; AppScope.scope.launch(Dispatchers.IO) { Repo(ctx).removePlayer(slot, p.nameKey); UiBus.version++ }; onDone() }) { Text("Remover", color = C.BAD) } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancelar") } }
    )
}
