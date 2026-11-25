package com.osmaicoach.collector

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Base Mundial — telas PARTINDO DO JOGO: tudo que o OCR leu do OSM (jogadores, clubes, ligas, países) com o cadastro real ligado quando identificado.
 */

@Composable
internal fun WorldBack(nav: WorldNav, label: String = "Voltar") {
    TextButton(onClick = { if (nav.detail != null) nav.detail = null else nav.back() }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) { Text("‹ $label", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
}

private val logoCache = android.util.LruCache<String, android.graphics.Bitmap>(60)

private fun loadLogo(url: String?): ImageBitmap? {
    if (url == null) return null
    logoCache.get(url)?.let { return it.asImageBitmap() }
    val u = url.replace("http://", "https://").let { it + (if ('?' in it) "&" else "?") + "width=96" }
    val b = runCatching { WorldHttp.get(u).use { r -> if (r.ok) r.stream?.readBytes()?.takeIf { it.size in 100..400_000 } else null }?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull() ?: return null
    logoCache.put(url, b); return b.asImageBitmap()
}

/** Escudo vindo do cadastro (URL do Wikimedia): PNG pequeno por `?width=`; falha em silêncio (sem escudo, só o nome). */
@Composable
internal fun RemoteLogo(url: String?, size: Dp) {
    var bmp by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) { bmp = withContext(Dispatchers.IO) { loadLogo(url) } }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, null, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize()) else DsIcon("shieldc", size * 0.6f, C.MUTED)
    }
}

/** Nome do país (como o jogo/Brasil escreve) → ISO, para as ligas do Atlas. */
private fun countryIndex(db: WorldDb): Map<String, String> {
    val m = HashMap<String, String>()
    db.query("SELECT iso2,name_pt,name_en FROM football_countries WHERE length(iso2)=2") { arrayOf(it.getString(0), it.getString(1), it.getString(2)) }.forEach { r -> for (i in 1..2) r[i]?.let { n -> m[WorldNorm.fold(n)] = r[0]!! } }
    for ((k, v) in LeagueNames.ALIAS) m[k] = v
    return m
}

private fun chipFor(status: String) = when (status) { "confirmado" -> "✓ identificado"; "muito alta" -> "✓ muito provável"; "provável" -> "≈ provável"; else -> status }
private fun nameOf(r: WorldCatalog.RealP) = r.fullName?.takeIf { it.isNotBlank() } ?: r.name

// ------------------------------------------------------------------ 1 · Visão Geral
@Composable
internal fun WorldOverview(nav: WorldNav) {
    var q by remember { mutableStateOf("") }
    val c = rememberWorld(null as WorldCatalog.Counts?) { WorldCatalog.counts(AndroidSql(it.readableDatabase)) }
    val sb = rememberWorld(emptyList<WorldQuery.Source>()) { WorldQuery.sources(AndroidSql(it.readableDatabase)) }.firstOrNull { it.id == WorldSources.SUPABASE }
    WorldPage {
        WorldSearch("Buscar jogador ou clube do seu jogo") { q = it }
        if (q.isNotBlank()) { WorldSearchResults(q, nav); return@WorldPage }
        WorldCard(tint = C.INFO) {
            Text("Para que serve", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
            Text("O app lê as telas do OSM e guarda cada jogador e clube que aparece. Aqui cada nome lido é ligado ao cadastro REAL do futebol (guardado no Supabase): assim o app corrige nomes lidos errado, acha escudo e foto certos e completa dados (nome completo, nascimento, nacionalidade, posição) para as buscas nas APIs e para os prompts.", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
        }
        if (c == null) { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.PRIMARY) }; return@WorldPage }
        DsSection("O que o seu jogo já mostrou")
        val k = listOf(Triple("Jogadores identificados", "${fmtN(c.playersReal)} de ${fmtN(c.playersSeen)}", 4), Triple("Clubes identificados", "${fmtN(c.clubsReal)} de ${fmtN(c.clubsSeen)}", 3), Triple("Ligas lidas", fmtN(c.leaguesWithClubs), 2), Triple("Países", fmtN(c.countries), 1))
        for (row in k.chunked(2)) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { for ((l, v, s) in row) Kpi(l, v, C.PRIMARY, Modifier.weight(1f)) { nav.go(s) } }
        if (c.playersSeen == 0L && c.clubsSeen == 0L) Text("Ainda não há leituras. Use o app com a captura ligada (Plantel, Calendário, Pré-jogo): tudo aparece aqui sozinho.", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 6.dp))
        if (c.pending > 0) WorldCard(tint = C.GOLD, onClick = { nav.go(5) }) {
            Text("${fmtN(c.pending)} nomes precisam de você", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
            Text("O app não teve certeza. Toque para escolher o cadastro certo (vale mais que a identificação automática).", fontSize = 12.5.sp, color = C.MUTED)
        }
        DsSection("Cadastro real (Supabase)")
        WorldCard(onClick = { nav.go(8) }) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Banco na nuvem", Modifier.weight(1f), fontWeight = FontWeight.Bold); DsChip(sb?.status ?: "aguardando configuração", statusColor(sb?.status ?: "aguardando configuração")) }
            Text(sb?.notes ?: "Configure em Fontes de Dados.", fontSize = 12.sp, color = C.MUTED)
        }
    }
}

@Composable
private fun WorldSearchResults(q: String, nav: WorldNav) {
    val ctx = LocalContext.current
    val r = rememberLoaded(null as Pair<List<WorldCatalog.PlayerRow>, List<WorldCatalog.ClubRow>>?, 0, q) { val db = AndroidSql(WorldDb.get(ctx).readableDatabase); Pair(WorldCatalog.players(db, q, null, 0, 0).take(8), WorldCatalog.clubs(db, q, null, 0, null, 0).take(8)) }
    if (r == null) { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.PRIMARY) }; return }
    if (r.first.isEmpty() && r.second.isEmpty()) { WorldEmpty("Nada do seu jogo com \"$q\"", "A busca ignora acentos e maiúsculas."); return }
    if (r.first.isNotEmpty()) { DsSection("Jogadores"); r.first.forEach { PlayerCatalogRow(it) { nav.detail = "op:${it.key}" } } }
    if (r.second.isNotEmpty()) { DsSection("Clubes"); r.second.forEach { ClubCatalogRow(it) { nav.detail = "oc:${it.key}" } } }
}

// ------------------------------------------------------------------ linhas
@Composable
internal fun PlayerCatalogRow(r: WorldCatalog.PlayerRow, gameIso: String? = null, onClick: () -> Unit) {
    WorldCard(onClick = onClick) { Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(r.raw, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(r.age?.let { "$it anos" }, r.pos, "slot ${r.slots.replace(",", ", ")}").joinToString(" • "), fontSize = 12.sp, color = C.MUTED)
            val re = r.real
            if (re != null) Text("= ${nameOf(re)}" + listOfNotNull(re.dob?.take(10), re.nat, re.pos).joinToString(" • ").let { if (it.isEmpty()) "" else " • $it" }, fontSize = 12.5.sp, color = C.INFO, maxLines = 2, overflow = TextOverflow.Ellipsis)
            else Text("ainda não identificado na base real", fontSize = 12.sp, color = C.MUTED)
        }
        (r.real?.iso ?: gameIso)?.takeIf { it.length == 2 }?.let { FlagBadge(it.uppercase(), null, null, 28.dp, 20.dp); Spacer(Modifier.width(6.dp)) }
        if (r.real != null) DsChip(chipFor(r.real.status), statusColor(r.real.status)) else DsChip("pendente", C.MUTED)
    } }
}

@Composable
internal fun ClubCatalogRow(r: WorldCatalog.ClubRow, onClick: () -> Unit) {
    WorldCard(onClick = onClick) { Row(verticalAlignment = Alignment.CenterVertically) {
        RivalShield(r.raw, 40.dp, web = false); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(r.raw, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val re = r.real
            if (re != null) Text(listOfNotNull(re.name.takeIf { WorldNorm.fold(it) != WorldNorm.fold(r.raw) }, re.city, re.country, re.league).joinToString(" • ").ifEmpty { "identificado" }, fontSize = 12.5.sp, color = C.INFO, maxLines = 2, overflow = TextOverflow.Ellipsis)
            else Text("ainda não identificado na base real", fontSize = 12.sp, color = C.MUTED)
            Text(listOfNotNull(r.league, "slot ${r.slots.replace(",", ", ")}").joinToString(" • "), fontSize = 11.5.sp, color = C.MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        r.real?.iso?.takeIf { it.length == 2 }?.let { FlagBadge(it.uppercase(), null, null, 28.dp, 20.dp); Spacer(Modifier.width(6.dp)) }
        if (r.real != null) DsChip(chipFor(r.real.status), statusColor(r.real.status)) else DsChip("pendente", C.MUTED)
    } }
}

// ------------------------------------------------------------------ 5 · Jogadores / 4 · Clubes (listas)
@Composable
internal fun WorldPlayers(nav: WorldNav) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }; var slot by rememberSaveable { mutableStateOf<Int?>(null) }; var mode by rememberSaveable { mutableIntStateOf(0) }
    val nat = rememberLoaded(emptyMap<String, String>(), 0, UiBus.version) { WorldCatalog.gameNationalities(ctx) }
    WorldPage {
        WorldSearch("Buscar jogador (nome como o jogo mostra)") { q = it }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((i, l) in listOf("Todos", "Identificados", "Pendentes").withIndex()) FilterPill(l, mode == i) { mode = i }
            for (s in 1..4) FilterPill("Slot $s", slot == s) { slot = if (slot == s) null else s }
        }
        WorldPaged(listOf(q, slot, mode), "Nenhum jogador lido ainda", { db, off -> WorldCatalog.players(db, q, slot, mode, off) }, group = { it.raw.firstOrNull()?.uppercaseChar()?.toString() }, hint = "Os jogadores aparecem conforme o app lê Plantel, Mercado e Pré-jogo com a captura ligada.") { r -> PlayerCatalogRow(r, r.nameKey?.let { nat[it] }) { nav.detail = "op:${r.key}" } }
    }
}

@Composable
internal fun WorldClubs(nav: WorldNav) {
    var q by remember { mutableStateOf("") }; var slot by rememberSaveable { mutableStateOf<Int?>(null) }; var mode by rememberSaveable { mutableIntStateOf(0) }
    WorldPage {
        WorldSearch("Buscar clube (nome como o jogo mostra)") { q = it }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((i, l) in listOf("Todos", "Identificados", "Pendentes").withIndex()) FilterPill(l, mode == i) { mode = i }
            for (s in 1..4) FilterPill("Slot $s", slot == s) { slot = if (slot == s) null else s }
        }
        if (nav.league != null) Row(verticalAlignment = Alignment.CenterVertically) { DsChip(nav.league!!.take(30), C.GOLD); TextButton(onClick = { nav.league = null }) { Text("limpar") } }
        WorldPaged(listOf(q, slot, mode, nav.league), "Nenhum clube lido ainda", { db, off -> WorldCatalog.clubs(db, q, slot, mode, nav.league, off) }, group = { it.raw.firstOrNull()?.uppercaseChar()?.toString() }, hint = "Os clubes aparecem conforme o app lê Calendário, Classificação e Pré-jogo.") { r -> ClubCatalogRow(r) { nav.detail = "oc:${r.key}" } }
    }
}

// ------------------------------------------------------------------ 3 · Competições (suas ligas + Atlas)
@Composable
internal fun WorldLeagues(nav: WorldNav) {
    val ctx = LocalContext.current
    val mine = rememberWorld(emptyList<WorldCatalog.LeagueRow>()) { WorldCatalog.leagues(AndroidSql(it.readableDatabase)) }
    val idx = rememberWorld(emptyMap<String, String>()) { countryIndex(it) }
    val names = rememberWorld(emptyMap<String, String>()) { db -> db.query("SELECT iso2,COALESCE(name_pt,name_en) FROM football_countries") { Pair(it.getString(0), it.getString(1)) }.toMap() }
    val atlas = rememberLoaded(emptyList<String>(), 0, UiBus.version) { runCatching { Atlas.load(Repo(ctx), Atlas.Kind.LEAGUE).keys.toList() }.getOrDefault(emptyList()) }
    val (groups, rest) = remember(atlas, idx) { LeagueNames.group(atlas) { t -> WorldNorm.fold(t) in idx } }
    WorldPage {
        DsSection("Suas ligas (lidas dos slots)")
        if (mine.isEmpty()) WorldEmpty("Nenhuma liga lida ainda", "Abra Calendário ou Classificação de cada slot com a captura ligada.")
        for (l in mine) WorldCard(onClick = { nav.detail = "ol:${l.name}" }) {
            Row(verticalAlignment = Alignment.CenterVertically) { DsTile("🏆", C.GOLD, 40.dp); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("slot ${l.slots.replace(",", ", ")} • ${l.clubsSeen} clubes lidos • ${l.clubsReal} identificados", fontSize = 12.sp, color = C.MUTED)
                    if (l.real != null) Text("= ${l.real}" + (l.realLevel?.let { " • divisão $it" } ?: ""), fontSize = 12.5.sp, color = C.INFO) else Text("liga real ainda não reconhecida (precisa de mais clubes identificados)", fontSize = 11.5.sp, color = C.MUTED)
                }
            }
        }
        val byCountry = groups.groupBy { g -> idx[WorldNorm.fold(g.canon.countryText)]?.let { names[it] } ?: g.canon.countryText }
        DsSection("Divisões do jogo por país (Atlas)", if (byCountry.isEmpty()) null else "${byCountry.size} países")
        if (atlas.isEmpty()) Text("O Atlas é aprendido quando você abre os filtros de Liga do OSM (Lista de transferências → Liga). Ele ainda está vazio.", fontSize = 12.5.sp, color = C.MUTED)
        else {
            Text("As divisões abaixo foram lidas dos filtros do jogo; as grafias erradas do OCR (\"22 Divisão\", \"2a\") foram juntas numa só linha. Para completar um país, abra o filtro de Liga no OSM e role a lista inteira com a captura ligada.", fontSize = 11.5.sp, color = C.MUTED)
            for ((country, gs) in byCountry.toSortedMap()) Fold("🌍", country, gs.joinToString(" ") { "${it.canon.tier}ª" }) {
                for (g in gs.sortedBy { it.canon.tier }) Text("${g.canon.tierLabel}" + (if (g.variants.size > 1) "  (${g.variants.size} leituras)" else ""), fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp))
                val tiers = gs.map { it.canon.tier }.toSet(); val miss = (1..(tiers.maxOrNull() ?: 1)).filter { it !in tiers }
                if (miss.isNotEmpty()) Text("Faltam no Atlas: " + miss.joinToString(", ") { "${it}ª" }, fontSize = 11.5.sp, color = C.WARN)
            }
            if (rest.isNotEmpty()) Fold("❔", "Nomes que não reconheci", "${rest.size}") { for (n in rest) Text(n, fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}

// ------------------------------------------------------------------ 2 · Países
@Composable
internal fun WorldCountries(nav: WorldNav) {
    val ctx = LocalContext.current
    val list = rememberWorld(emptyList<Triple<String, String, String?>>()) { db -> db.query("SELECT c.iso2,COALESCE(c.name_pt,c.name_en),(SELECT f.code FROM football_confederations f WHERE f.id=c.confederation_id) FROM football_countries c WHERE c.osm_seen>0 ORDER BY 2") { Triple(it.getString(0), it.getString(1), it.getString(2)) } }
    val idx = rememberWorld(emptyMap<String, String>()) { countryIndex(it) }
    val counts = rememberLoaded(emptyMap<String, Int>(), 0, UiBus.version) { runCatching { countryPlayers(ctx) }.getOrDefault(emptyMap()) }
    val atlas = rememberLoaded(emptyList<String>(), 0, UiBus.version) { runCatching { Atlas.load(Repo(ctx), Atlas.Kind.LEAGUE).keys.toList() }.getOrDefault(emptyList()) }
    val groups = remember(atlas, idx) { LeagueNames.group(atlas) { t -> WorldNorm.fold(t) in idx }.first }
    WorldPage {
        if (list.isEmpty()) WorldEmpty("Nenhum país lido ainda", "Os países vêm das bandeiras dos jogadores e das ligas lidas.")
        for ((iso, name, confed) in list) {
            val tiers = groups.count { idx[WorldNorm.fold(it.canon.countryText)] == iso }
            WorldCard(onClick = { nav.detail = "oy:$iso" }) { Row(verticalAlignment = Alignment.CenterVertically) {
                FlagBadge(iso.uppercase(), null, null, 34.dp, 24.dp); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.Bold, fontSize = 16.sp); Text(listOfNotNull((counts[iso] ?: 0).takeIf { it > 0 }?.let { "$it jogadores no seu jogo" }, tiers.takeIf { it > 0 }?.let { "$it divisões no Atlas" }).joinToString(" • ").ifEmpty { "visto no jogo" }, fontSize = 12.5.sp, color = C.MUTED) }
                if (confed != null) DsChip(confed, C.INFO)
            } }
        }
    }
}

private suspend fun countryPlayers(ctx: android.content.Context): Map<String, Int> {
    val repo = Repo(ctx); val out = HashMap<String, Int>()
    for (slot in 1..4) {
        val iso = repo.dao.plan(slot, "natiso4")?.json?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: continue
        for (p in repo.dao.playersOf(slot)) iso.optString(p.nameKey).takeIf { it.length == 2 }?.lowercase()?.let { out[it] = (out[it] ?: 0) + 1 }
    }
    return out
}

// ------------------------------------------------------------------ detalhes
@Composable
internal fun WorldDetail(d: String, nav: WorldNav) {
    val id = d.substringAfter(':')
    WorldPage {
        WorldBack(nav)
        when (d.substringBefore(':')) { "op" -> OsmPlayerDetail(id, nav); "oc" -> OsmClubDetail(id, nav); "ol" -> OsmLeagueDetail(id, nav); "oy", "c" -> OsmCountryDetail(id, nav) }
    }
}

@Composable
private fun OsmPlayerDetail(key: String, nav: WorldNav) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val row = rememberLoaded(null as WorldCatalog.PlayerRow?, 0, Pair(key, UiBus.version)) { WorldCatalog.players(AndroidSql(WorldDb.get(ctx).readableDatabase), key, null, 0, 0).firstOrNull { it.key == key } }
    var msg by remember { mutableStateOf("") }
    if (row == null) { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.PRIMARY) }; return }
    val re = row.real
    Row(verticalAlignment = Alignment.CenterVertically) {
        re?.iso?.takeIf { it.length == 2 }?.let { FlagBadge(it.uppercase(), null, null, 40.dp, 28.dp); Spacer(Modifier.width(10.dp)) }
        Column { Text(re?.let { nameOf(it) } ?: row.raw, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp); if (re != null) Text("no jogo: ${row.raw}", fontSize = 12.5.sp, color = C.MUTED) }
    }
    DsSection("No seu jogo")
    val gameIso = rememberLoaded(null as String?, 0, Pair(row.nameKey, UiBus.version)) { row.nameKey?.let { WorldCatalog.gameNationalities(ctx)[it] } }
    WorldCard { KV("Nome lido", row.raw); KV("Idade", row.age?.let { "$it anos" } ?: "—"); KV("Posição", row.pos ?: "—"); KV("País (bandeira do jogo)", gameIso?.uppercase() ?: "não lido"); KV("Slots", row.slots.replace(",", ", ")) }
    DsSection("Cadastro real")
    if (re == null) WorldCard { Text("Ainda não identificado. Procure abaixo e confirme o cadastro certo.", fontSize = 13.sp, color = C.MUTED) }
    else WorldCard {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Identificação", Modifier.weight(1f), fontSize = 13.sp, color = C.MUTED); DsChip("${chipFor(re.status)} · ${re.score}", statusColor(re.status)) }
        KV("Nome completo", re.fullName ?: re.name); KV("Nascimento", re.dob ?: "—"); KV("Nacionalidade", re.nat ?: "—"); KV("Posição", re.pos ?: "—"); KV("Altura", re.heightCm?.let { "$it cm" } ?: "—")
        KV("Wikidata", re.wikidata ?: "—"); KV("Transfermarkt", re.tmId ?: "—"); KV("API-Football", re.apifId ?: "—"); KV("Reep", re.reepId ?: "—")
        if (re.apifId != null && row.nameKey != null) OutlinedButton(onClick = { scope.launch(Dispatchers.IO) { val ok = PhotoWeb.saveFromBaseId(ctx, row.nameKey, re.apifId); msg = if (ok) "✅ Foto salva (vale para os 4 slots)." else "Não achei foto para este jogador." } }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Buscar a foto pelo cadastro real") }
    }
    if (msg.isNotEmpty()) Text(msg, fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
    PickReal("player", row.key, row.raw)
}

@Composable
private fun OsmClubDetail(key: String, nav: WorldNav) {
    val ctx = LocalContext.current
    val row = rememberLoaded(null as WorldCatalog.ClubRow?, 0, Pair(key, UiBus.version)) { WorldCatalog.clubs(AndroidSql(WorldDb.get(ctx).readableDatabase), key, null, 0, null, 0).firstOrNull { it.key == key } }
    val aliases = rememberLoaded(emptyList<String>(), 0, Pair(row?.real?.id, UiBus.version)) { row?.real?.let { r -> WorldDb.get(ctx).query("SELECT alias FROM football_entity_aliases WHERE entity_type='club' AND entity_id=? ORDER BY alias LIMIT 12", r.id.toString()) { it.getString(0) } } ?: emptyList() }
    if (row == null) { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.PRIMARY) }; return }
    val re = row.real
    Row(verticalAlignment = Alignment.CenterVertically) {
        RivalShield(row.raw, 64.dp, web = false); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) { Text(re?.name ?: row.raw, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp); if (re != null) Text("no jogo: ${row.raw}", fontSize = 12.5.sp, color = C.MUTED) }
        re?.iso?.takeIf { it.length == 2 }?.let { FlagBadge(it.uppercase(), null, null, 40.dp, 28.dp) }
    }
    DsSection("No seu jogo")
    WorldCard { KV("Nome lido", row.raw); KV("Papel", row.role ?: "—"); KV("Liga", row.league ?: "—"); KV("Slots", row.slots.replace(",", ", ")) }
    DsSection("Cadastro real")
    if (re == null) WorldCard { Text("Ainda não identificado. Procure abaixo e confirme o cadastro certo.", fontSize = 13.sp, color = C.MUTED) }
    else WorldCard {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Identificação", Modifier.weight(1f), fontSize = 13.sp, color = C.MUTED); DsChip("${chipFor(re.status)} · ${re.score}", statusColor(re.status)) }
        KV("Nome", re.name); KV("Cidade", re.city ?: "—"); KV("País", re.country ?: "—"); KV("Liga atual", re.league ?: "—"); KV("Estádio", re.stadium ?: "—"); KV("Fundação", re.founded ?: "—"); KV("Wikidata", re.wikidata ?: "—")
        if (aliases.isNotEmpty()) Text("Outros nomes: " + aliases.joinToString(" • "), fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
        Text("Elenco por temporada não existe nas fontes abertas integradas; os jogadores do clube que o jogo mostrou estão em Jogadores.", fontSize = 11.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
    }
    PickReal("club", row.key, row.raw)
}

@Composable
private fun OsmLeagueDetail(name: String, nav: WorldNav) {
    val ctx = LocalContext.current
    val l = rememberWorld(null as WorldCatalog.LeagueRow?, name) { WorldCatalog.leagues(AndroidSql(it.readableDatabase)).firstOrNull { x -> x.name == name } }
    Text(name, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
    if (l != null) WorldCard {
        KV("Slots", l.slots.replace(",", ", ")); KV("Clubes lidos no jogo", "${l.clubsSeen}"); KV("Identificados na base real", "${l.clubsReal}")
        KV("Competição real", l.real?.let { r -> r + (l.realLevel?.let { " (divisão $it)" } ?: "") } ?: "ainda não reconhecida")
        Text("A lista de clubes abaixo é a que o JOGO mostrou (Classificação/Calendário). O cadastro aberto não traz a tabela completa de cada liga, por isso a contagem do jogo é a que vale.", fontSize = 11.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
    }
    DsSection("Clubes desta liga no jogo")
    WorldPaged(name, "Nenhum clube lido nesta liga", { db, off -> WorldCatalog.clubs(db, "", null, 0, name, off) }) { r -> ClubCatalogRow(r) { nav.detail = "oc:${r.key}" } }
}

@Composable
private fun OsmCountryDetail(iso: String, nav: WorldNav) {
    val ctx = LocalContext.current
    val country = rememberWorld(null as Triple<String, String?, String?>?, iso) { db -> db.query("SELECT COALESCE(name_pt,name_en),name_en,(SELECT f.name FROM football_confederations f WHERE f.id=confederation_id) FROM football_countries WHERE iso2=?", iso) { Triple(it.getString(0), it.getString(1), it.getString(2)) }.firstOrNull() }
    val idx = rememberWorld(emptyMap<String, String>()) { countryIndex(it) }
    val atlas = rememberLoaded(emptyList<String>(), 0, Pair(iso, UiBus.version)) { runCatching { Atlas.load(Repo(ctx), Atlas.Kind.LEAGUE).keys.toList() }.getOrDefault(emptyList()) }
    val real = rememberLoaded(null as List<Pair<String, Int?>>?, 0, iso) { withContext(Dispatchers.IO) { WorldRemote.countryCompetitions(ctx, iso) } }
    Row(verticalAlignment = Alignment.CenterVertically) { FlagBadge(iso.uppercase(), null, null, 48.dp, 34.dp); Spacer(Modifier.width(10.dp)); Text(country?.first ?: iso.uppercase(), fontWeight = FontWeight.ExtraBold, fontSize = 22.sp) }
    WorldCard { KV("Nome em inglês", country?.second ?: "—"); KV("Código", iso.uppercase()); KV("Confederação", country?.third ?: "—") }
    val gs = remember(atlas, idx) { LeagueNames.group(atlas) { t -> WorldNorm.fold(t) in idx }.first.filter { idx[WorldNorm.fold(it.canon.countryText)] == iso }.sortedBy { it.canon.tier } }
    DsSection("Divisões que o JOGO tem neste país", "${gs.size}")
    WorldCard {
        if (gs.isEmpty()) Text("Nenhuma lida no Atlas ainda. Abra o filtro de Liga no OSM (Lista de transferências → Liga) e role a lista inteira com a captura ligada.", fontSize = 13.sp, color = C.MUTED)
        else {
            for (g in gs) Text(g.canon.tierLabel, fontSize = 14.sp, modifier = Modifier.padding(vertical = 3.dp))
            val tiers = gs.map { it.canon.tier }.toSet(); val miss = (1..(tiers.maxOrNull() ?: 1)).filter { it !in tiers }
            if (miss.isNotEmpty()) Text("Faltam no Atlas: " + miss.joinToString(", ") { "${it}ª" } + ". Abra o filtro de Liga de novo para o app completar.", fontSize = 11.5.sp, color = C.WARN, modifier = Modifier.padding(top = 4.dp))
        }
    }
    DsSection("No cadastro real (Supabase)")
    WorldCard {
        val r = real
        if (r == null) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = C.PRIMARY); Spacer(Modifier.width(8.dp)); Text("consultando…", fontSize = 13.sp, color = C.MUTED) }
        else if (r.isEmpty()) Text("Sem resposta do Supabase ou sem competições deste país.", fontSize = 13.sp, color = C.MUTED)
        else { for ((n, lv) in r) Text(n + (lv?.let { "  •  divisão $it" } ?: ""), fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)); Text("Inclui campeonatos estaduais e regionais que o jogo não tem; o que vale para o jogo são as divisões acima.", fontSize = 11.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp)) }
    }
}

// ------------------------------------------------------------------ escolher o cadastro certo (manual vale mais)
/** O que o JOGO mostrou do item (idade, posição, país, liga): vira evidência na lista de candidatos. */
private class GameCtx(val age: Int?, val pos: String?, val iso: String?, val league: String?)

@Composable
private fun ExcludeButton(kind: String, key: String, raw: String, onDone: () -> Unit = {}) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { ask = true }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("🗑 Excluir (não existe / foi lido errado)", color = C.BAD, fontSize = 13.sp) }
    if (ask) AlertDialog(
        onDismissRequest = { ask = false }, containerColor = C.SURFACE,
        title = { Text("Excluir \"$raw\"?", fontWeight = FontWeight.Bold) },
        text = { Text("Some das listas e o app deixa de ler esse texto como ${if (kind == "club") "clube" else "jogador"}. Dá para restaurar em Validação OCR → Excluídos.", fontSize = 13.sp) },
        confirmButton = { TextButton(onClick = { ask = false; scope.launch(Dispatchers.IO) { WorldCatalog.ignore(ctx, kind, key, raw); onDone() } }) { Text("Excluir", color = C.BAD, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = { ask = false }) { Text("Cancelar") } }
    )
}

@Composable
private fun PickReal(kind: String, key: String, raw: String) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var text by remember(key) { mutableStateOf(raw) }; var busy by remember(key) { mutableStateOf(false) }; var msg by remember(key) { mutableStateOf("") }
    var players by remember(key) { mutableStateOf<List<WorldCatalog.PlayerCand>>(emptyList()) }; var clubs by remember(key) { mutableStateOf<List<WorldCatalog.ClubCand>>(emptyList()) }
    val game = rememberLoaded(null as GameCtx?, 0, key) {
        val db = AndroidSql(WorldDb.get(ctx).readableDatabase)
        val row = db.rows("SELECT context,league FROM football_ocr_observations WHERE kind=? AND norm=? ORDER BY at DESC LIMIT 1", kind, key).firstOrNull()
        val c = runCatching { org.json.JSONObject(row?.get(0) ?: "{}") }.getOrDefault(org.json.JSONObject())
        val iso = c.optString("nameKey").takeIf { it.isNotBlank() && it != "null" }?.let { WorldCatalog.gameNationalities(ctx)[it] }
        GameCtx(c.optInt("age", -1).takeIf { it > 0 }, c.optString("pos").takeIf { it.isNotBlank() && it != "null" }, iso, row?.get(1)?.takeIf { it.isNotBlank() && it != "-" && it != "null" })
    }
    fun render(g: GameCtx?) {
        val rd = AndroidSql(WorldDb.get(ctx).readableDatabase)
        if (kind == "club") clubs = WorldCatalog.clubCandidates(rd, text, g?.league) else players = WorldCatalog.playerCandidates(rd, text, g?.age, g?.iso, g?.pos)
    }
    fun search(g: GameCtx?) { busy = true; msg = ""; scope.launch(Dispatchers.IO) {
        render(g)                                                                  // 1º: o que já está no aparelho (instantâneo)
        val db = AndroidSql(WorldDb.get(ctx).w)
        val net = runCatching { if (kind == "club") WorldRemote.pullClub(ctx, db, text, force = true) else WorldRemote.pullPlayer(ctx, db, text, force = true) }.getOrDefault(false)
        render(g)                                                                  // 2º: com o que veio do cadastro real
        val n = if (kind == "club") clubs.size else players.size
        msg = if (!net) "Sem conexão com o Supabase: mostrando só o que já está no aparelho." else if (n == 0) "Nada parecido com esse nome. Tente outra grafia." else ""
        busy = false } }
    LaunchedEffect(key, game != null) { if (game != null) search(game) }          // ao abrir, já procura com o nome lido
    DsSection("Escolher o cadastro certo")
    WorldCard {
        game?.let { g ->
            val bits = if (kind == "club") listOfNotNull(g.league?.let { "liga: $it" }) else listOfNotNull(g.age?.let { "$it anos" }, g.pos, g.iso?.let { "país do jogo:" })
            if (bits.isNotEmpty() || g.iso != null) Row(verticalAlignment = Alignment.CenterVertically) { Text("Do jogo: " + bits.joinToString(" • "), fontSize = 12.5.sp, color = C.INFO); g.iso?.takeIf { it.length == 2 }?.let { Spacer(Modifier.width(6.dp)); FlagBadge(it.uppercase(), null, null, 26.dp, 18.dp) } }
        }
        OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Nome para procurar") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        Button(onClick = { search(game) }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text(if (busy) "Procurando…" else "Procurar de novo com este nome", fontWeight = FontWeight.ExtraBold) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = C.PRIMARY, trackColor = DS.ELEVATED)
        if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
        fun scoreColor(sc: Int) = if (sc >= 90) C.OK else if (sc >= 75) C.GOLD else C.MUTED
        for (c in players) {
            val p = c.real
            Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(nameOf(p), fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
                        Text(listOfNotNull(p.dob?.take(10)?.let { d -> d + (c.age?.let { a -> " ($a anos)" } ?: "") }, p.nat, p.pos, p.heightCm?.let { "$it cm" }).joinToString(" • ").ifEmpty { "sem mais dados" }, fontSize = 12.sp, color = C.MUTED)
                    }
                    p.iso?.takeIf { it.length == 2 }?.let { FlagBadge(it.uppercase(), null, null, 26.dp, 18.dp); Spacer(Modifier.width(6.dp)) }
                    DsChip("${c.score}", scoreColor(c.score)); Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = { scope.launch(Dispatchers.IO) { WorldCatalog.confirmManual(ctx, "player", key, p.id); msg = "✅ Confirmado: ${nameOf(p)}" } }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("É este", fontSize = 12.sp) }
                }
                if (c.reasons.isNotEmpty()) Text(c.reasons.joinToString("; "), fontSize = 11.sp, color = C.MUTED)
            }
        }
        for (c in clubs) {
            val cl = c.real
            Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RemoteLogo(cl.logoUrl, 44.dp); Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cl.name, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
                        Text(listOfNotNull(cl.city, cl.country, cl.league, cl.founded?.let { "fundado $it" }).joinToString(" • ").ifEmpty { "sem mais dados" }, fontSize = 12.sp, color = C.MUTED)
                    }
                    cl.iso?.takeIf { it.length == 2 }?.let { FlagBadge(it.uppercase(), null, null, 26.dp, 18.dp); Spacer(Modifier.width(6.dp)) }
                    DsChip("${c.score}", scoreColor(c.score)); Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = { scope.launch(Dispatchers.IO) { WorldCatalog.confirmManual(ctx, "club", key, cl.id); msg = "✅ Confirmado: ${cl.name}" } }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("É este", fontSize = 12.sp) }
                }
                Text((c.reasons + (if (cl.prominence > 0) listOf("conhecido em ${cl.prominence} cadastros") else emptyList())).joinToString("; "), fontSize = 11.sp, color = C.MUTED)
            }
        }
        Text("Sua escolha vale mais que a identificação automática e nunca é sobrescrita por ela. A nota (0–100) mostra o quanto cada candidato combina com o nome, a idade, o país e a posição do jogo.", fontSize = 11.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
    }
    ExcludeButton(kind, key, raw)
}

// ------------------------------------------------------------------ 6 · Validação OCR (precisa de você)
@Composable
internal fun WorldValidation(nav: WorldNav) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val nPend = rememberWorld(0L) { it.scalar("SELECT COUNT(*) FROM football_osm_entity_mappings WHERE status IN ('ambíguo','desconhecido')") ?: 0L }
    WorldPage {
        Text("Aqui ficam os nomes que o app NÃO conseguiu ligar ao cadastro real com certeza. Escolha o certo uma vez e o app passa a usar; o que o app identifica sozinho fica na aba \"Identificados\" (com opção de desfazer).", fontSize = 12.5.sp, color = C.MUTED)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { for ((i, l) in listOf("Precisa de você" + if (nPend > 0) " ($nPend)" else "", "Identificados", "Excluídos", "Histórico").withIndex()) FilterPill(l, tab == i) { tab = i } }
        when (tab) {
            0 -> WorldPaged("pend", "Nada pendente: o app identificou tudo que leu.", { db, off -> WorldCatalog.pending(db, off) }) { p ->
                var open by remember(p.key) { mutableStateOf(false) }
                WorldCard(tint = C.GOLD.copy(alpha = 0.5f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(p.raw, fontWeight = FontWeight.Bold, fontSize = 15.sp); Text("${if (p.kind == "club") "clube" else "jogador"} • slot ${p.slots.replace(",", ", ")} • ${p.status}", fontSize = 12.sp, color = C.MUTED) }
                        OutlinedButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text(if (open) "Fechar" else "Escolher", fontSize = 12.sp) } }
                    if (!p.evidence.isNullOrBlank()) Text(p.evidence, fontSize = 11.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
                    if (open) PickReal(p.kind, p.key, p.raw) else ExcludeButton(p.kind, p.key, p.raw)
                }
            }
            1 -> WorldPaged("ident", "Nada identificado ainda", { db, off -> WorldQuery.mappings(db, listOf("confirmado", "muito alta", "provável"), null, off) }) { m ->
                WorldCard(tint = statusColor(m.status).copy(alpha = 0.5f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("\"${m.raw}\" → ${m.entity ?: "—"}", fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis); Text("${if (m.kind == "club") "clube" else "jogador"} • slot ${m.slot} • ${m.observations} leitura(s)", fontSize = 12.sp, color = C.MUTED) }; DsChip("${chipFor(m.status)} · ${m.score}", statusColor(m.status)) }
                    OutlinedButton(onClick = { scope.launch(Dispatchers.IO) { WorldOcr.undo(ctx, m.kind, WorldNorm.fold(m.raw), m.slot) } }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.padding(top = 4.dp)) { Text("↩ Desfazer", fontSize = 12.sp) }
                }
            }
            2 -> WorldPaged("excl", "Nada excluído.", { db, off -> WorldCatalog.ignored(db, off) }) { x ->
                WorldCard { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(x.raw, fontWeight = FontWeight.Bold); Text(if (x.kind == "club") "clube excluído" else "jogador excluído", fontSize = 12.sp, color = C.MUTED) }
                    OutlinedButton(onClick = { scope.launch(Dispatchers.IO) { WorldCatalog.restore(ctx, x.kind, x.key) } }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("Restaurar", fontSize = 12.sp) } } }
            }
            else -> WorldPaged("hist", "Sem histórico ainda", { db, off -> WorldQuery.history(db, off) }) { h ->
                WorldCard { Text("${h.action}: ${h.raw}", fontWeight = FontWeight.Bold, fontSize = 14.sp); Text("slot ${h.slot} • ${h.from ?: "novo"} → ${h.to ?: "—"}${h.score?.let { " • escore $it" } ?: ""} • ${ago(h.at)}", fontSize = 12.sp, color = C.MUTED); h.reason?.let { Text(it, fontSize = 11.5.sp, color = C.MUTED) } }
            }
        }
    }
}
