package com.osmaicoach.collector

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Base Mundial — aba "Central Mundial de Futebol" (antes de Ajustes). Nove seções internas, listas paginadas no SQLite, estados reais (carregando, vazio, erro, indisponível). */
internal val WORLD_SECTIONS = listOf("Visão Geral", "Países", "Competições", "Clubes", "Jogadores", "Validação OCR", "Cobertura", "Sincronização", "Fontes de Dados")
private val WORLD_ICONS = listOf("chart", "globe", "trophy", "shieldc", "users", "check", "eye", "bolt", "list")

/** Filtros compartilhados entre as seções (ex.: "ver os clubes deste país"). */
internal class WorldNav {
    var section by mutableIntStateOf(0); var country by mutableStateOf<String?>(null); var competition by mutableStateOf<Long?>(null); var detail by mutableStateOf<String?>(null); var league by mutableStateOf<String?>(null)
    private val history = ArrayList<Int>()
    /** Troca de seção guardando de onde veio (o botão Voltar e o gesto de voltar usam isso). */
    fun go(s: Int) { if (s != section) { history.add(section); section = s }; detail = null }
    fun back(): Boolean { if (detail != null) { detail = null; return true }; if (history.isNotEmpty()) { section = history.removeAt(history.size - 1); return true }; if (section != 0) { section = 0; return true }; return false }
    val canBack get() = detail != null || history.isNotEmpty() || section != 0
}

@Composable
internal fun WorldTab() {
    val ctx = LocalContext.current
    val nav = remember { WorldNav() }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { val db = WorldDb.get(ctx); WorldSources.seed(db); WorldSync.healStale(db) }; runCatching { WorldOsm.refresh(ctx) } }; WorldSync.tick(ctx) }
    BackHandler(enabled = nav.canBack) { nav.back() }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp)) {
            HeroHeader("🌐", "Base Mundial", "Central Mundial de Futebol", tint = C.INFO)
        }
        if (nav.detail == null) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WORLD_SECTIONS.forEachIndexed { i, t ->
                    val on = i == nav.section
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(if (on) C.PRIMARY else C.SURFACE).border(1.dp, if (on) C.PRIMARY else C.BORDER, RoundedCornerShape(50)).heightIn(min = 44.dp).clickable { nav.go(i) }.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) { DsIcon(WORLD_ICONS[i], 18.dp, if (on) DS.ON_LIME else C.MUTED); Spacer(Modifier.width(6.dp)); Text(t, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) DS.ON_LIME else C.MUTED, maxLines = 1) }
                }
            }
        }
        if (nav.canBack && nav.detail == null) WorldBack(nav, if (nav.section == 0) "Voltar" else "Voltar" )
        Box(Modifier.fillMaxSize()) {
            val d = nav.detail
            if (d != null) WorldDetail(d, nav)
            else when (nav.section) {
                0 -> WorldOverview(nav)
                1 -> WorldCountries(nav)
                2 -> WorldLeagues(nav)
                3 -> WorldClubs(nav)
                4 -> WorldPlayers(nav)
                5 -> WorldValidation(nav)
                6 -> WorldCoverageView(nav)
                7 -> WorldSyncView()
                else -> WorldSourcesView()
            }
        }
    }
}

// ------------------------------------------------------------------ peças
internal const val OSM_HINT = "O que está no OSM aparece aqui conforme o app lê suas telas (elenco, calendário, adversários, filtros de liga). Ainda não há leitura suficiente: use o app com a captura ligada. A leitura é automática."
@Composable
internal fun WorldPage(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, top = 8.dp, end = 14.dp, bottom = 96.dp), content = content)
}

@Composable
internal fun WorldCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, tint: Color = C.BORDER, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(DS.RADIUS_CARD)
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape).background(DS.SURFACE).border(1.dp, tint, shape).then(if (onClick != null) Modifier.clickable { onClick() } else Modifier).padding(12.dp), content = content)
}

@Composable
internal fun WorldEmpty(text: String, hint: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        DsIcon("globe", 36.dp, C.MUTED); Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)); if (hint != null) Text(hint, fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Campo de busca com atraso (300 ms): só consulta depois que a digitação para. */
@Composable
internal fun WorldSearch(label: String, onApplied: (String) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(q) { delay(300); onApplied(q) }
    OutlinedTextField(q, { q = it }, singleLine = true, label = { Text(label) }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
}

/** Lista paginada: pede 40 linhas por vez ao SQLite; "Carregar mais" continua de onde parou. Cancela a leitura antiga quando o filtro muda. */
@Composable
internal fun <T> WorldPaged(key: Any?, empty: String, load: (WorldSql, Int) -> List<T>, group: ((T) -> String?)? = null, hint: String? = null, row: @Composable (T) -> Unit) {
    val ctx = LocalContext.current
    val items = remember(key) { mutableStateListOf<T>() }
    var end by remember(key) { mutableStateOf(false) }
    var busy by remember(key) { mutableStateOf(true) }
    var err by remember(key) { mutableStateOf<String?>(null) }
    var more by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key, more) {
        busy = true
        try {
            val page = withContext(Dispatchers.IO) { load(AndroidSql(WorldDb.get(ctx).readableDatabase), items.size) }
            items.addAll(page); if (page.size < WorldQuery.PAGE) end = true
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { err = e.message ?: "erro ao consultar a base" }
        busy = false
    }
    if (err != null) WorldEmpty("Não consegui consultar a base", err)
    else if (items.isEmpty() && busy) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.PRIMARY) }
    else if (items.isEmpty()) WorldEmpty(empty, hint ?: "A base é preenchida sozinha: veja a aba Sincronização.")
    else {
        var prev: String? = null
        for (it in items) { val g = group?.invoke(it); if (g != null && g != prev) { DsSection(g); prev = g }; row(it) }
        if (!end) OutlinedButton(onClick = { more++ }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(if (busy) "Carregando…" else "Carregar mais") }
    }
}

@Composable
internal fun <T> rememberWorld(initial: T, key: Any? = null, loader: (WorldDb) -> T): T {
    val ctx = LocalContext.current
    val tick = rememberTick(20000L)
    return rememberLoaded(initial, tick, Pair(key, UiBus.version)) { loader(WorldDb.get(ctx)) }
}

@Composable
internal fun Kpi(label: String, value: String, tone: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).background(tone.copy(alpha = 0.10f)).border(1.dp, tone.copy(alpha = 0.45f), shape).then(if (onClick != null) Modifier.clickable { onClick() } else Modifier).padding(vertical = 10.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1); Text(label, fontSize = 11.5.sp, color = C.MUTED, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

internal fun fmtN(n: Long): String = java.text.NumberFormat.getIntegerInstance(java.util.Locale("pt", "BR")).format(n)
internal fun fmtBytes(b: Long): String = if (b >= 1048576) String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0) else "${b / 1024} KB"
internal fun statusColor(s: String): Color = when (s) { "operacional", "atualizada", "confirmado" -> C.OK; "sincronizando", "em andamento", "muito alta", "provável" -> C.INFO; "aguardando configuração", "aguardando sincronização", "ambíguo", "pausada", "interrompida" -> C.GOLD; "limite atingido" -> C.GOLD; "desativada por licença", "indisponível", "erro", "desconhecido", "desfeito" -> C.BAD; else -> C.MUTED }

// ------------------------------------------------------------------ 1 · Visão Geral
@Composable
internal fun WorldBar(label: String, have: Long, need: Long) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row { Text(label, Modifier.weight(1f), fontSize = 13.sp); Text("${fmtN(have)} de ${fmtN(need)}", fontSize = 12.5.sp, color = C.MUTED) }
        LinearProgressIndicator(progress = { if (need <= 0) 0f else (have.toFloat() / need).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 3.dp), color = C.PRIMARY, trackColor = DS.ELEVATED)
    }
}

@Composable
internal fun WorldLine(values: List<Float>, color: Color) {
    val mx = (values.maxOrNull() ?: 1f).coerceAtLeast(1f); val mn = values.minOrNull() ?: 0f
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(72.dp).padding(vertical = 6.dp)) {
        val n = values.size; if (n < 2) return@Canvas
        var prev: androidx.compose.ui.geometry.Offset? = null
        for ((i, v) in values.withIndex()) {
            val p = androidx.compose.ui.geometry.Offset(size.width * i / (n - 1), size.height * (1f - (v - mn) / (mx - mn).coerceAtLeast(1f)))
            prev?.let { drawLine(color, it, p, 4f, cap = androidx.compose.ui.graphics.StrokeCap.Round) }; prev = p
        }
        prev?.let { drawCircle(color, 7f, it) }
    }
}
