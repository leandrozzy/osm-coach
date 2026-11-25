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
private val WORLD_ICONS = listOf("chart", "globe", "trophy", "shield", "users", "check", "eye", "bolt", "list")

/** Filtros compartilhados entre as seções (ex.: "ver os clubes deste país"). */
internal class WorldNav { var section by mutableIntStateOf(0); var country by mutableStateOf<String?>(null); var competition by mutableStateOf<Long?>(null); var detail by mutableStateOf<String?>(null); var all by mutableStateOf(false) }

@Composable
internal fun WorldTab() {
    val ctx = LocalContext.current
    val nav = remember { WorldNav() }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { val db = WorldDb.get(ctx); WorldSources.seed(db); WorldSync.healStale(db) }; runCatching { WorldOsm.refresh(ctx) } }; WorldSync.tick(ctx) }
    BackHandler(enabled = nav.detail != null) { nav.detail = null }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp)) {
            HeroHeader("🌐", "Base Mundial", "Central Mundial de Futebol", tint = C.INFO)
        }
        if (nav.detail == null) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WORLD_SECTIONS.forEachIndexed { i, t ->
                    val on = i == nav.section
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(if (on) C.PRIMARY else C.SURFACE).border(1.dp, if (on) C.PRIMARY else C.BORDER, RoundedCornerShape(50)).heightIn(min = 44.dp).clickable { nav.section = i }.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) { DsIcon(WORLD_ICONS[i], 18.dp, if (on) DS.ON_LIME else C.MUTED); Spacer(Modifier.width(6.dp)); Text(t, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) DS.ON_LIME else C.MUTED, maxLines = 1) }
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            val d = nav.detail
            if (d != null) WorldDetail(d, nav)
            else when (nav.section) {
                0 -> WorldOverview(nav)
                1 -> WorldCountries(nav)
                2 -> WorldCompetitions(nav)
                3 -> WorldClubs(nav)
                4 -> WorldPlayers(nav)
                5 -> WorldValidation()
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
internal fun WorldScope(nav: WorldNav) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterPill("Só o que está no OSM", !nav.all) { nav.all = false }; FilterPill("Toda a base mundial", nav.all) { nav.all = true }
    }
}
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
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WorldOverview(nav: WorldNav) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    val t = rememberWorld(null as WorldCoverage.Totals?) { WorldCoverage.totals(ctx, it) }
    val srcs = rememberWorld(emptyList<WorldQuery.Source>()) { WorldQuery.sources(AndroidSql(it.readableDatabase)) }
    val playersSeries = rememberWorld(emptyList<Pair<Long, Long>>()) { WorldQuery.series(AndroidSql(it.readableDatabase), "players") }
    val ocrSeries = rememberWorld(emptyList<Pair<Long, Long>>()) { WorldQuery.series(AndroidSql(it.readableDatabase), "ocr_confirmed") }
    val continents = rememberWorld(emptyList<Triple<String, Long, Long>>()) { WorldQuery.continentCountries(AndroidSql(it.readableDatabase)) }
    val origin = rememberWorld(emptyList<Pair<String, Long>>()) { WorldQuery.originCounts(AndroidSql(it.readableDatabase), "football_players") }
    val withDob = rememberWorld(0L) { it.count("football_players", "dob IS NOT NULL") }
    WorldPage {
        WorldSearch("Buscar país, liga, clube ou jogador") { q = it }
        if (q.isNotBlank()) { WorldGlobalResults(q, nav); return@WorldPage }
        if (t == null) { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.PRIMARY) }; return@WorldPage }
        val osm = rememberWorld(LongArray(6)) { db -> longArrayOf(db.count("football_countries", "osm_seen>0"), db.count("football_competitions", "osm_seen>0"), db.count("football_clubs", "osm_seen>0"), db.count("football_players", "osm_seen>0"), db.count("football_osm_entity_mappings", "status IN ('confirmado','muito alta','provável')"), db.count("football_ocr_observations")) }
        val img = rememberWorld(null as WorldOsm.ImgStat?) { WorldOsm.imageStat(ctx) }
        if (t.players == 0L && t.clubs == 0L) WorldEmpty("A base ainda está vazia", "Ela se preenche sozinha em Wi‑Fi (aba Sincronização). Nenhum dado é inventado.")
        DsSection("O que o OSM já mostrou ao app")
        val k1 = listOf(Triple("Países", osm[0], 1), Triple("Ligas", osm[1], 2), Triple("Clubes", osm[2], 3), Triple("Jogadores", osm[3], 4)).filter { it.second > 0 }
        if (k1.isEmpty()) WorldCard { Text("Nada ainda: a base só mostra o que o OSM exibiu nas telas lidas (elenco, calendário, adversários). Conforme você usa o app com a captura ligada, isto se preenche sozinho.", fontSize = 13.sp, color = C.MUTED) }
        else for (row in k1.chunked(2)) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((l, v, s2) in row) Kpi(l, fmtN(v), C.PRIMARY, Modifier.weight(1f)) { nav.section = s2 }
            repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
        }
        if (osm[5] > 0) Text("${fmtN(osm[5])} leituras do OCR • ${fmtN(osm[4])} identificadas na base real", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
        img?.let { im ->
            val rows = listOf(Triple("Bandeiras", im.flagsHave, im.flagsNeed), Triple("Escudos", im.crestsHave, im.crestsNeed), Triple("Fotos", im.photosHave, im.photosNeed)).filter { it.third > 0 }
            if (rows.isNotEmpty()) { DsSection("Imagens do que está no OSM"); WorldCard { for ((l, h, n) in rows) WorldBar(l, h, n) } }
        }
        DsSection("Base real disponível no aparelho")
        WorldCard {
            listOf("Países" to t.countries, "Competições" to t.competitions, "Clubes" to t.clubs, "Jogadores" to t.players).filter { it.second > 0 }.forEach { KV(it.first, fmtN(it.second)) }
            KV("Ocupação do banco", fmtBytes(t.dbBytes))
        }
        DsSection("Sincronização e fontes")
        WorldCard(onClick = { nav.section = 7 }) {
            val last = srcs.mapNotNull { it.lastSync }.maxOrNull(); val next = srcs.mapNotNull { it.nextSync }.minOrNull()
            KV("Última sincronização", last?.let { ago(it) } ?: "ainda não houve"); KV("Próxima", next?.let { fmtTime(it) } ?: "ao ligar o Wi‑Fi")
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 6.dp)) { for (s in srcs) DsChip(s.name.substringBefore(" —").substringBefore(" (").take(22) + ": " + s.status, statusColor(s.status), modifier = Modifier.padding(end = 6.dp, bottom = 6.dp)) }
            val quota = srcs.filter { it.callsToday > 0 }.joinToString(" • ") { "${it.id}: ${it.callsToday} consulta(s) hoje" }
            if (quota.isNotEmpty()) Text("Uso de cotas: $quota", fontSize = 12.sp, color = C.MUTED)
        }
        if (playersSeries.size >= 2 || ocrSeries.size >= 2) DsSection("Crescimento")
        if (playersSeries.size >= 2 || ocrSeries.size >= 2) WorldCard {
            Text("Jogadores na base (cada ponto é uma fotografia real, no máx. 1 a cada 6 h)", fontSize = 12.5.sp, color = C.MUTED)
            if (playersSeries.size < 2) Text("A série começa depois da primeira importação.", fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(vertical = 10.dp)) else WorldLine(playersSeries.map { it.second.toFloat() }, C.PRIMARY)
            Text("Confirmações por OCR", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
            if (ocrSeries.size < 2) Text("Aparece quando as telas do OSM forem lidas com a captura ligada.", fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(vertical = 6.dp)) else WorldLine(ocrSeries.map { it.second.toFloat() }, C.INFO)
        }
        DsSection("Cobertura por continente")
        WorldCard(onClick = { nav.section = 6 }) {
            val max = (continents.maxOfOrNull { it.third } ?: 1L).coerceAtLeast(1L)
            if (continents.all { it.third == 0L }) Text("Sem clubes importados ainda.", fontSize = 13.sp, color = C.MUTED)
            for ((c, n, clubs) in continents) { Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) { Text("$c ($n países)", fontSize = 12.5.sp, modifier = Modifier.width(150.dp), maxLines = 1, overflow = TextOverflow.Ellipsis); Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(DS.ELEVATED)) { Box(Modifier.fillMaxWidth(clubs.toFloat() / max).height(10.dp).clip(RoundedCornerShape(5.dp)).background(C.PRIMARY)) }; Text(" ${fmtN(clubs)}", fontSize = 12.sp, fontWeight = FontWeight.Bold) } }
            if (continents.any { it.first == "Sem confederação" }) Text("\"Sem confederação\": países ainda sem confederação importada (vem do Wikidata).", fontSize = 11.5.sp, color = C.MUTED)
        }
    }
}

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

@Composable
private fun WorldGlobalResults(q: String, nav: WorldNav) {
    val ctx = LocalContext.current
    val g = rememberLoaded(null as WorldQuery.Global?, 0, q) { WorldQuery.global(AndroidSql(WorldDb.get(ctx).readableDatabase), q) }
    if (g == null) { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.PRIMARY) }; return }
    if (g.countries.isEmpty() && g.competitions.isEmpty() && g.clubs.isEmpty() && g.players.isEmpty()) { WorldEmpty("Nada encontrado para \"$q\"", "A busca ignora acentos e maiúsculas."); return }
    if (g.countries.isNotEmpty()) { DsSection("Países"); g.countries.forEach { CountryRowView(it) { nav.detail = "c:${it.iso}" } } }
    if (g.competitions.isNotEmpty()) { DsSection("Competições"); g.competitions.forEach { CompRowView(it) { nav.detail = "t:${it.id}" } } }
    if (g.clubs.isNotEmpty()) { DsSection("Clubes"); g.clubs.forEach { ClubRowView(it) { nav.detail = "k:${it.id}" } } }
    if (g.players.isNotEmpty()) { DsSection("Jogadores"); g.players.forEach { PlayerRowView(it) { nav.detail = "p:${it.id}" } } }
}

// ------------------------------------------------------------------ linhas
@Composable
internal fun CountryRowView(r: WorldQuery.CountryRow, onClick: () -> Unit) {
    WorldCard(onClick = onClick) { Row(verticalAlignment = Alignment.CenterVertically) {
        FlagBadge(r.iso.uppercase().takeIf { it.length == 2 }, null, null, 34.dp, 24.dp)
        Column(Modifier.weight(1f)) { Text(r.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${fmtN(r.clubs)} clubes • ${fmtN(r.comps)} competições • ${fmtN(r.players)} jogadores", fontSize = 12.5.sp, color = C.MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        DsChip(r.confed ?: "—", if (r.confed == null) C.MUTED else C.INFO)
    } }
}
@Composable
internal fun CompRowView(r: WorldQuery.CompRow, onClick: () -> Unit) {
    WorldCard(onClick = onClick) { Row(verticalAlignment = Alignment.CenterVertically) {
        DsTile("🏆", C.GOLD, 40.dp); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) { Text(r.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(listOfNotNull(r.country, r.level?.let { "divisão $it" }, r.kind, "${r.clubs} clubes").joinToString(" • "), fontSize = 12.5.sp, color = C.MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (r.osmSeen) DsChip("OSM", C.OK)
    } }
}
@Composable
internal fun ClubRowView(r: WorldQuery.ClubRow, onClick: () -> Unit) {
    WorldCard(onClick = onClick) { Row(verticalAlignment = Alignment.CenterVertically) {
        RivalShield(r.name, 40.dp, web = false); Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) { Text(r.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(listOfNotNull(r.city, r.league, if (r.marker.isNotEmpty()) markerLabel(r.marker) else null).joinToString(" • ").ifEmpty { "sem detalhes" }, fontSize = 12.5.sp, color = C.MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        FlagBadge(r.iso?.uppercase(), null, null, 28.dp, 20.dp)
        if (r.osmSeen) DsChip("OSM", C.OK)
    } }
}
internal fun markerLabel(m: String) = when { m.contains("W") -> "feminino"; m == "II" || m == "B" || m == "III" || m == "C" -> "equipe B/reserva"; m.startsWith("U") -> "base ($m)"; else -> m }
@Composable
internal fun PlayerRowView(r: WorldQuery.PlayerRow, onClick: () -> Unit) {
    WorldCard(onClick = onClick) { Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(r.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(listOfNotNull(r.dob?.take(10), r.nat, r.pos).joinToString(" • ").ifEmpty { "sem detalhes" }, fontSize = 12.5.sp, color = C.MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        FlagBadge(r.iso?.uppercase()?.takeIf { it.length == 2 }, null, null, 28.dp, 20.dp)
        if (r.osmSeen) DsChip("OSM", C.OK)
    } }
}

// ------------------------------------------------------------------ 2–5 · listas
@Composable
private fun WorldCountries(nav: WorldNav) {
    var q by remember { mutableStateOf("") }; var confed by rememberSaveable { mutableStateOf<String?>(null) }
    WorldPage {
        WorldScope(nav)
        WorldSearch("Buscar país (nome em português/inglês ou código)") { q = it }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((id, l) in listOf<Pair<String?, String>>(null to "Todos", "UEFA" to "UEFA", "CONMEBOL" to "CONMEBOL", "CONCACAF" to "CONCACAF", "AFC" to "AFC", "CAF" to "CAF", "OFC" to "OFC", "-" to "Independentes/sem confederação")) FilterPill(l, confed == id) { confed = id }
        }
        WorldPaged(listOf(q, confed, nav.all), "Nenhum país do OSM ainda", { db, off -> WorldQuery.countries(db, q, confed, off, nav.all) }, group = { it.confed ?: "Sem confederação" }, hint = if (nav.all) null else OSM_HINT) { r -> CountryRowView(r) { nav.detail = "c:${r.iso}" } }
    }
}

@Composable
private fun WorldCompetitions(nav: WorldNav) {
    var q by remember { mutableStateOf("") }; val iso = nav.country
    WorldPage {
        WorldScope(nav)
        WorldSearch("Buscar competição") { q = it }
        if (iso != null) Row(verticalAlignment = Alignment.CenterVertically) { DsChip("país: ${iso.uppercase()}", C.INFO); TextButton(onClick = { nav.country = null }) { Text("limpar filtro") } }
        WorldPaged(listOf(q, iso, nav.all), "Nenhuma competição do OSM ainda", { db, off -> WorldQuery.competitions(db, q, iso, off, nav.all) }, group = { it.country ?: "Sem país" }, hint = if (nav.all) null else OSM_HINT) { r -> CompRowView(r) { nav.detail = "t:${r.id}" } }
    }
}

@Composable
private fun WorldClubs(nav: WorldNav) {
    var q by remember { mutableStateOf("") }; var cat by rememberSaveable { mutableStateOf<String?>(null) }
    WorldPage {
        WorldScope(nav)
        WorldSearch("Buscar clube (apelidos e grafias alternativas também)") { q = it }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((id, l) in listOf<Pair<String?, String>>(null to "Todos", "principal" to "Principal", "reserva" to "Equipe B/reserva")) FilterPill(l, cat == id) { cat = id }
        }
        if (nav.country != null) Row(verticalAlignment = Alignment.CenterVertically) { DsChip("país: ${nav.country!!.uppercase()}", C.INFO); TextButton(onClick = { nav.country = null }) { Text("limpar") } }
        if (nav.competition != null) Row(verticalAlignment = Alignment.CenterVertically) { DsChip("competição #${nav.competition}", C.GOLD); TextButton(onClick = { nav.competition = null }) { Text("limpar") } }
        WorldPaged(listOf(q, cat, nav.country, nav.competition, nav.all), "Nenhum clube do OSM ainda", { db, off -> WorldQuery.clubs(db, q, nav.country, cat, nav.competition, off, nav.all) }, group = { it.country ?: "Sem país" }, hint = if (nav.all) null else OSM_HINT) { r -> ClubRowView(r) { nav.detail = "k:${r.id}" } }
    }
}

@Composable
private fun WorldPlayers(nav: WorldNav) {
    var q by remember { mutableStateOf("") }; var confed by rememberSaveable { mutableStateOf<String?>(null) }; var pos by rememberSaveable { mutableStateOf<String?>(null) }
    var age by rememberSaveable { mutableStateOf(0) }; var photo by rememberSaveable { mutableStateOf(false) }; var valid by rememberSaveable { mutableStateOf(false) }
    val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    val (minY, maxY) = when (age) { 1 -> Pair(year - 21, null); 2 -> Pair(year - 29, year - 22); 3 -> Pair(null, year - 30); else -> Pair(null, null) }
    WorldPage {
        WorldScope(nav)
        WorldSearch("Buscar jogador (ignora acentos e abreviações: \"A. Kurti\")") { q = it }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((id, l) in listOf<Pair<String?, String>>(null to "Todas as confederações", "UEFA" to "UEFA", "CONMEBOL" to "CONMEBOL", "CONCACAF" to "CONCACAF", "AFC" to "AFC", "CAF" to "CAF", "OFC" to "OFC")) FilterPill(l, confed == id) { confed = id }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((id, l) in listOf<Pair<String?, String>>(null to "Todas as posições", "goalkeeper" to "Goleiro", "defender" to "Defensor", "midfielder" to "Meio-campo", "forward" to "Atacante")) FilterPill(l, pos == id) { pos = id }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((i, l) in listOf("Qualquer idade", "até 21 anos", "22 a 29", "30+").withIndex()) FilterPill(l, age == i) { age = i }
            FilterPill("Com foto", photo) { photo = !photo }
        }
        if (nav.country != null) Row(verticalAlignment = Alignment.CenterVertically) { DsChip("nacionalidade: ${nav.country!!.uppercase()}", C.INFO); TextButton(onClick = { nav.country = null }) { Text("limpar") } }
        val f = WorldQuery.PlayerFilter(q, nav.country, confed, pos, minY, maxY, photo, valid, osmOnly = !nav.all)
        WorldPaged(listOf(q, nav.country, confed, pos, age, photo, valid, nav.all), "Nenhum jogador do OSM ainda", { db, off -> WorldQuery.players(db, f, off) }, group = { it.name.firstOrNull()?.uppercaseChar()?.toString() }, hint = if (nav.all) null else OSM_HINT) { r -> PlayerRowView(r) { nav.detail = "p:${r.id}" } }
    }
}
