package com.osmaicoach.collector

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------ detalhes (país, competição, clube, jogador)
@Composable
internal fun WorldDetail(d: String, nav: WorldNav) {
    val id = d.substringAfter(':')
    WorldPage {
        TextButton(onClick = { nav.detail = null }) { Text("‹ Voltar", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
        when (d.substringBefore(':')) { "c" -> CountryDetail(id, nav); "t" -> CompDetail(id.toLong(), nav); "k" -> ClubDetail(id.toLong(), nav); "p" -> PlayerDetailW(id.toLong(), nav) }
    }
}

@Composable
private fun CountryDetail(iso: String, nav: WorldNav) {
    val c = rememberWorld(null as List<String?>?, iso) { db -> AndroidSql(db.readableDatabase).rows("SELECT COALESCE(c.name_pt,c.name_en),c.name_en,c.iso2,c.iso3,c.name_osm,f.code,f.name,c.continent,c.source_id,c.updated_at,c.wikidata FROM football_countries c LEFT JOIN football_confederations f ON f.id=c.confederation_id WHERE c.iso2=?", iso).firstOrNull() }
    val n = rememberWorld(longArrayOf(0, 0, 0), iso) { db -> longArrayOf(db.count("football_clubs", "iso2=?", iso), db.count("football_competitions", "iso2=?", iso), db.count("football_players", "nationality_iso=?", iso)) }
    if (c == null) { WorldEmpty("País não encontrado"); return }
    Row(verticalAlignment = Alignment.CenterVertically) { FlagBadge(iso.uppercase().takeIf { it.length == 2 }, null, null, 52.dp, 36.dp); Text(c[0] ?: iso, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold) }
    WorldCard {
        KV("Nome em inglês", c[1] ?: "—"); KV("Código ISO", (c[2] ?: "").uppercase() + (c[3]?.let { " / $it" } ?: "")); KV("Nome no OSM", c[4] ?: "não observado")
        KV("Confederação", c[5]?.let { "$it (${c[7] ?: ""})" } ?: "não importada"); KV("Fonte", WorldSources.def(c[8] ?: "")?.name ?: (c[8] ?: "—")); KV("Atualizado", c[9]?.toLongOrNull()?.let { ago(it) } ?: "—")
        Text("País ISO e associação FIFA não são a mesma coisa (ex.: Inglaterra, Escócia, País de Gales e Irlanda do Norte têm associações próprias).", fontSize = 11.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
    }
    DsSection("Cobertura")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Kpi("Clubes", fmtN(n[0]), C.INFO, Modifier.weight(1f)) { nav.country = iso; nav.detail = null; nav.section = 3 }; Kpi("Competições", fmtN(n[1]), C.GOLD, Modifier.weight(1f)) { nav.country = iso; nav.detail = null; nav.section = 2 }; Kpi("Jogadores", fmtN(n[2]), C.OK, Modifier.weight(1f)) { nav.country = iso; nav.detail = null; nav.section = 4 } }
    val comps = rememberWorld(emptyList<WorldQuery.CompRow>(), iso) { WorldQuery.competitions(AndroidSql(it.readableDatabase), "", iso, 0).take(8) }
    if (comps.isNotEmpty()) { DsSection("Competições e divisões"); comps.forEach { r -> CompRowView(r) { nav.detail = "t:${r.id}" } } }
}

@Composable
private fun CompDetail(id: Long, nav: WorldNav) {
    val c = rememberWorld(null as List<String?>?, id) { db -> AndroidSql(db.readableDatabase).rows("SELECT c.name,c.name_osm,COALESCE(k.name_pt,k.name_en),c.iso2,c.level,c.kind,c.category,c.wikidata,c.source_id,c.osm_seen FROM football_competitions c LEFT JOIN football_countries k ON k.iso2=c.iso2 WHERE c.id=?", id.toString()).firstOrNull() }
    val seasons = rememberWorld(emptyList<String>(), id) { db -> db.query("SELECT DISTINCT season FROM football_competition_clubs WHERE competition_id=? ORDER BY 1 DESC", id.toString()) { it.getString(0) } }
    val ext = rememberWorld(emptyList<Pair<String, String>>(), id) { db -> db.query("SELECT provider,ext_id FROM football_external_ids WHERE entity_type='competition' AND entity_id=?", id.toString()) { Pair(it.getString(0), it.getString(1)) } }
    if (c == null) { WorldEmpty("Competição não encontrada"); return }
    Text(c[0] ?: "", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
    WorldCard {
        KV("País", c[2] ?: "—"); KV("Nível da divisão", c[4]?.let { "$it (informado pela fonte)" } ?: "não comprovado (não inventado)"); KV("Tipo", c[5] ?: "—"); KV("Categoria", c[6] ?: "—"); KV("Esporte", "futebol de campo"); KV("Nome no OSM", c[1] ?: "não observado")
        KV("Temporadas com participantes", if (seasons.isEmpty()) "nenhuma" else seasons.joinToString()); KV("Wikidata", c[7] ?: "—"); KV("Fonte", WorldSources.def(c[8] ?: "")?.name ?: (c[8] ?: "—"))
        if (ext.isNotEmpty()) KV("IDs externos", ext.joinToString { "${it.first}: ${it.second}" })
    }
    DsSection("Clubes participantes (${seasons.firstOrNull() ?: "atual"})")
    WorldPaged(id, "Nenhum clube vinculado a esta competição", { db, off -> WorldQuery.clubs(db, "", null, null, id, off) }) { r -> ClubRowView(r) { nav.detail = "k:${r.id}" } }
}

@Composable
private fun ClubDetail(id: Long, nav: WorldNav) {
    val ctx = LocalContext.current
    val c = rememberWorld(null as List<String?>?, id) { db -> AndroidSql(db.readableDatabase).rows("SELECT name,short_name,name_osm,iso2,city,founded,stadium,category,marker,wikidata,logo_url,source_id,osm_seen FROM football_clubs WHERE id=?", id.toString()).firstOrNull() }
    val aliases = rememberWorld(emptyList<String>(), id) { db -> db.query("SELECT DISTINCT alias FROM football_entity_aliases WHERE entity_type='club' AND entity_id=? LIMIT 30", id.toString()) { it.getString(0) } }
    val comps = rememberWorld(emptyList<Pair<Long, String>>(), id) { db -> db.query("SELECT c.id,c.name FROM football_competition_clubs cc JOIN football_competitions c ON c.id=cc.competition_id WHERE cc.club_id=?", id.toString()) { Pair(it.getLong(0), it.getString(1)) } }
    val ext = rememberWorld(emptyList<Pair<String, String>>(), id) { db -> db.query("SELECT provider,ext_id FROM football_external_ids WHERE entity_type='club' AND entity_id=?", id.toString()) { Pair(it.getString(0), it.getString(1)) } }
    val squad = rememberWorld(emptyList<Pair<Long, String>>(), id) { db -> db.query("SELECT p.id,p.name FROM football_squads s JOIN football_players p ON p.id=s.player_id WHERE s.club_id=? ORDER BY s.season DESC LIMIT 40", id.toString()) { Pair(it.getLong(0), it.getString(1)) } }
    val maps = rememberWorld(emptyList<List<String?>>(), id) { db -> AndroidSql(db.readableDatabase).rows("SELECT slot,osm_key,status,score FROM football_osm_entity_mappings WHERE osm_kind='club' AND entity_id=? ORDER BY updated_at DESC LIMIT 12", id.toString()) }
    if (c == null) { WorldEmpty("Clube não encontrado"); return }
    Row(verticalAlignment = Alignment.CenterVertically) { RivalShield(c[0], 64.dp); Spacer(Modifier.width(10.dp)); Column { Text(c[0] ?: "", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold); Row(verticalAlignment = Alignment.CenterVertically) { FlagBadge(c[3]?.uppercase(), null, null, 28.dp, 20.dp); Text(markerLabel(c[8] ?: "").takeIf { !c[8].isNullOrEmpty() } ?: "equipe principal", fontSize = 13.sp, color = C.MUTED) } } }
    WorldCard {
        KV("Nome curto/apelido", c[1] ?: "—"); KV("Nome no OSM", c[2] ?: "não observado"); KV("Cidade", c[4] ?: "—"); KV("Fundação", c[5] ?: "—"); KV("Estádio", c[6] ?: "—"); KV("Categoria", c[7] ?: "principal"); KV("Wikidata", c[9] ?: "—")
        KV("Fonte", WorldSources.def(c[11] ?: "")?.name ?: (c[11] ?: "—")); KV("Escudo", if (c[10] != null) "URL do Wikimedia (só ilustração; o app usa o escudo já guardado)" else "sem URL conhecida")
        if (ext.isNotEmpty()) KV("IDs externos", ext.joinToString { "${it.first}: ${it.second}" })
    }
    if (aliases.isNotEmpty()) { DsSection("Nomes e apelidos"); WorldCard { Text(aliases.joinToString(" • "), fontSize = 13.sp, color = C.MUTED) } }
    DsSection("Competições e divisão"); if (comps.isEmpty()) Text("Nenhuma competição vinculada.", fontSize = 13.sp, color = C.MUTED) else comps.forEach { (cid, n) -> WorldCard(onClick = { nav.detail = "t:$cid" }) { Text(n, fontWeight = FontWeight.Bold) } }
    DsSection("Elenco"); if (squad.isEmpty()) Text("Elenco por temporada ainda não importado para este clube (nenhuma fonte aberta integrada traz elencos; não é inventado).", fontSize = 13.sp, color = C.MUTED) else squad.forEach { (pid, n) -> WorldCard(onClick = { nav.detail = "p:$pid" }) { Text(n, fontWeight = FontWeight.Bold) } }
    DsSection("No OSM (por slot)"); if (maps.isEmpty()) Text("Ainda não observado nos slots.", fontSize = 13.sp, color = C.MUTED) else maps.forEach { WorldCard { Text("Slot ${it[0]} • lido como \"${it[1]}\"", fontSize = 13.sp); DsChip("${it[2]} (${it[3]})", statusColor(it[2] ?: "")) } }
    WorldProvenance("club", id)
}

@Composable
private fun PlayerDetailW(id: Long, nav: WorldNav) {
    val ctx = LocalContext.current
    val p = rememberWorld(null as List<String?>?, id) { db -> AndroidSql(db.readableDatabase).rows("SELECT name,full_name,dob,nationality,nationality_iso,position,foot,height_cm,status,wikidata,reep_id,tm_id,apif_id,source_id,confidence,validation,osm_seen FROM football_players WHERE id=?", id.toString()).firstOrNull() }
    val aliases = rememberWorld(emptyList<String>(), id) { db -> db.query("SELECT DISTINCT alias FROM football_entity_aliases WHERE entity_type='player' AND entity_id=? LIMIT 20", id.toString()) { it.getString(0) } }
    val maps = rememberWorld(emptyList<List<String?>>(), id) { db -> AndroidSql(db.readableDatabase).rows("SELECT slot,osm_key,status,score,evidence FROM football_osm_entity_mappings WHERE osm_kind='player' AND entity_id=? ORDER BY updated_at DESC LIMIT 12", id.toString()) }
    var photo by remember(id) { mutableStateOf<android.graphics.Bitmap?>(null) }; var photoMsg by remember(id) { mutableStateOf("verificando a licença da foto…") }
    LaunchedEffect(id) {
        val f = withContext(Dispatchers.IO) { runCatching { WorldImages.ensure(ctx, id) }.getOrNull() }
        photo = f?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
        photoMsg = if (photo != null) "foto com licença livre verificada (Wikimedia Commons)" else "sem foto com licença livre verificada: nenhuma foto é inventada"
    }
    if (p == null) { WorldEmpty("Jogador não encontrado"); return }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(42.dp)).background(androidx.compose.ui.graphics.Color.White), contentAlignment = Alignment.Center) {
            val b = photo; if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) else Text("👤", fontSize = 38.sp)
        }
        Spacer(Modifier.width(12.dp)); Column { Text(p[0] ?: "", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold); Row(verticalAlignment = Alignment.CenterVertically) { FlagBadge(p[4]?.uppercase()?.takeIf { it.length == 2 }, null, null, 28.dp, 20.dp); Text(p[3] ?: "nacionalidade desconhecida", fontSize = 13.sp, color = C.MUTED) } }
    }
    Text(photoMsg, fontSize = 11.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
    WorldCard {
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        KV("Nome completo", p[1] ?: "—"); KV("Nascimento", p[2] ?: "—"); KV("Idade (calculada)", p[2]?.take(4)?.toIntOrNull()?.let { "~${year - it} anos" } ?: "—"); KV("Posição", p[5] ?: "—"); KV("Pé dominante", p[6] ?: "não disponível"); KV("Altura", p[7]?.let { "$it cm" } ?: "—")
        KV("Situação", p[8] ?: "desconhecido"); KV("Wikidata", p[9] ?: "—"); KV("Transfermarkt", p[11] ?: "—"); KV("API-Football", p[12] ?: "—"); KV("Reep", p[10] ?: "—"); KV("Fonte", WorldSources.def(p[13] ?: "")?.name ?: (p[13] ?: "—"))
        KV("Visto no OSM", if (p[16] != "0") "sim" else "não")
    }
    if (aliases.isNotEmpty()) { DsSection("Nomes alternativos"); WorldCard { Text(aliases.joinToString(" • "), fontSize = 13.sp, color = C.MUTED) } }
    DsSection("No OSM (camada separada)")
    Text("A força, o valor e os atributos do jogo ficam nas telas do slot; esta base só confirma a identidade real.", fontSize = 12.5.sp, color = C.MUTED)
    if (maps.isEmpty()) Text("Ainda não associado a nenhum jogador dos slots.", fontSize = 13.sp, color = C.MUTED) else maps.forEach { WorldCard { Text("Slot ${it[0]} • lido como \"${it[1]}\"", fontSize = 13.sp, fontWeight = FontWeight.Bold); Row { DsChip("${it[2]} (${it[3]})", statusColor(it[2] ?: "")) }; Text(it[4] ?: "", fontSize = 11.5.sp, color = C.MUTED) } }
    WorldProvenance("player", id)
}

@Composable
private fun WorldProvenance(type: String, id: Long) {
    val rows = rememberWorld(emptyList<List<String?>>(), "$type$id") { db -> AndroidSql(db.readableDatabase).rows("SELECT field,source_id,version,at FROM football_field_provenance WHERE entity_type=? AND entity_id=?", type, id.toString()) }
    if (rows.isEmpty()) return
    Fold("🔎", "Proveniência dos campos", "${rows.size}") { for (r in rows) Text("${r[0]} ← ${WorldSources.def(r[1] ?: "")?.name ?: r[1]} • versão ${r[2] ?: "—"}", fontSize = 12.5.sp, color = C.MUTED) }
}

// ------------------------------------------------------------------ 6 · Validação OCR
@Composable
internal fun WorldValidation() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Confirmados" to listOf("confirmado"), "Prováveis" to listOf("provável", "muito alta"), "Ambíguos" to listOf("ambíguo"), "Desconhecidos" to emptyList(), "Divergências" to listOf("desfeito"), "Histórico" to emptyList())
    WorldPage {
        Text("O app confirma sozinho só com evidência suficiente (nome + pelo menos duas pistas independentes e margem sobre o 2º candidato). Prováveis e ambíguos esperam novas leituras. Nada aqui gera notificação.", fontSize = 12.5.sp, color = C.MUTED)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { tabs.forEachIndexed { i, (l, _) -> FilterPill(l, tab == i) { tab = i } } }
        when (tab) {
            3 -> WorldPaged("unres", "Nenhuma entidade sem correspondência", { db, off -> WorldQuery.unresolved(db, off) }) { u ->
                WorldCard { Text(u.name, fontWeight = FontWeight.Bold); Text("${if (u.kind == "club") "clube" else "jogador"} • slot ${u.slot} • visto ${u.seen}x • ${ago(u.last)}", fontSize = 12.5.sp, color = C.MUTED); Text("Registro provisório de origem OSM: só vira real depois de confirmado por fontes (não se infere existência pelo jogo).", fontSize = 11.5.sp, color = C.MUTED) }
            }
            5 -> WorldPaged("hist", "Sem histórico ainda", { db, off -> WorldQuery.history(db, off) }) { h ->
                WorldCard { Text("${h.action}: ${h.raw}", fontWeight = FontWeight.Bold, fontSize = 14.sp); Text("slot ${h.slot} • ${h.from ?: "novo"} → ${h.to ?: "—"}${h.score?.let { " • escore $it" } ?: ""} • ${ago(h.at)}", fontSize = 12.5.sp, color = C.MUTED); h.reason?.let { Text(it, fontSize = 11.5.sp, color = C.MUTED) } }
            }
            else -> WorldPaged(tab, "Nada nesta lista", { db, off -> WorldQuery.mappings(db, tabs[tab].second, null, off) }) { m ->
                WorldCard(tint = statusColor(m.status).copy(alpha = 0.5f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("\"${m.raw}\" → ${m.entity ?: "—"}", fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis); Text("${if (m.kind == "club") "clube" else "jogador"} • slot ${m.slot} • ${m.observations} observação(ões) • ${ago(m.at)}", fontSize = 12.sp, color = C.MUTED) }; DsChip("${m.status} · ${m.score}", statusColor(m.status)) }
                    m.evidence?.let { Text(it, fontSize = 11.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp)) }
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (m.status != "desfeito") OutlinedButton(onClick = { scope.launch(Dispatchers.IO) { WorldOcr.undo(ctx, m.kind, WorldNorm.fold(m.raw), m.slot) } }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("↩ Desfazer", fontSize = 12.sp) }
                        else OutlinedButton(onClick = { scope.launch(Dispatchers.IO) { WorldOcr.reopen(ctx, m.kind, WorldNorm.fold(m.raw), m.slot); WorldOcr.resolvePending(ctx) } }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("🔁 Reavaliar", fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 7 · Cobertura
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun WorldCoverageView(nav: WorldNav) {
    var sel by rememberSaveable { mutableStateOf<String?>(null) }
    val rows = rememberWorld(emptyList<Triple<String, String, LongArray>>()) { WorldCoverage.byCountry(it, 400) }
    val weight = remember(rows) { rows.associate { it.first to it.third[0] } }
    val gaps = rememberWorld(emptyList<String>()) { db -> db.query("SELECT COALESCE(name_pt,name_en) FROM football_countries WHERE length(iso2)=2 AND iso2 NOT IN (SELECT iso2 FROM football_clubs WHERE iso2 IS NOT NULL) ORDER BY 1 LIMIT 60") { it.getString(0) } }
    val obs = rememberWorld(longArrayOf(0, 0, 0, 0)) { db -> longArrayOf(db.count("football_clubs", "osm_seen>0"), db.count("football_competitions", "osm_seen>0"), db.count("football_unresolved_entities", "kind='club'"), db.count("football_ocr_observations", "kind='club'")) }
    val withConf = rememberWorld(longArrayOf(0, 0)) { db -> longArrayOf(db.count("football_countries", "confederation_id IS NOT NULL"), db.count("football_countries")) }
    WorldPage {
        Text("Mapa clicável: o tom mostra quantos clubes a base tem em cada país (cinza = nenhum). Toque num país.", fontSize = 12.5.sp, color = C.MUTED)
        WorldMapView(sel, weight) { sel = it }
        Text("Mapa: Natural Earth 1:110m (domínio público) via world-atlas (ISC). Microestados e ilhas pequenas não aparecem no desenho; estão na lista abaixo.", fontSize = 11.sp, color = C.MUTED)
        sel?.let { iso ->
            val r = rows.firstOrNull { it.first == iso }
            WorldCard(tint = C.INFO, onClick = { nav.detail = "c:$iso" }) { Row(verticalAlignment = Alignment.CenterVertically) { FlagBadge(iso.uppercase(), null, null, 34.dp, 24.dp); Column { Text(r?.second ?: iso.uppercase(), fontWeight = FontWeight.ExtraBold, fontSize = 17.sp); Text(r?.let { "${fmtN(it.third[0])} clubes • ${fmtN(it.third[1])} competições • ${fmtN(it.third[2])} jogadores" } ?: "sem dados nesta base", fontSize = 12.5.sp, color = C.MUTED) } } ; Text("Toque para abrir o país", fontSize = 11.5.sp, color = C.MUTED) }
        }
        DsSection("Três camadas, sem misturar")
        WorldCard {
            KV("Futebol real (clubes na base)", fmtN(rows.sumOf { it.third[0] })); KV("Observados no OSM (por OCR)", "${fmtN(obs[0])} clubes confirmados • ${fmtN(obs[3])} leituras"); KV("Sem correspondência real ainda", "${fmtN(obs[2])} clubes")
            Text("Catálogo do OSM: o app só conhece o que as telas já mostraram; por isso não há percentual global. Percentuais só aparecem quando o denominador é conhecido (ex.: países com confederação: ${withConf[0]} de ${withConf[1]}).", fontSize = 11.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
        }
        DsSection("Países com dados")
        if (rows.isEmpty()) WorldEmpty("Sem dados importados ainda") else rows.take(60).forEach { (iso, name, n) -> WorldCard(onClick = { nav.detail = "c:$iso" }) { Row(verticalAlignment = Alignment.CenterVertically) { FlagBadge(iso.uppercase().takeIf { it.length == 2 }, null, null, 30.dp, 20.dp); Text(name, Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${fmtN(n[0])} • ${fmtN(n[1])} • ${fmtN(n[2])}", fontSize = 12.sp, color = C.MUTED) } } }
        Text("colunas: clubes • competições • jogadores", fontSize = 11.sp, color = C.MUTED)
        DsSection("Lacunas (países sem nenhum clube na base)")
        WorldCard { if (gaps.isEmpty()) Text("Nenhuma lacuna conhecida.", fontSize = 13.sp, color = C.MUTED) else Text(gaps.joinToString(" • "), fontSize = 12.5.sp, color = C.MUTED) }
    }
}

// ------------------------------------------------------------------ 8 · Sincronização
@Composable
internal fun WorldSyncView() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val tick = rememberTick(1000L); tick.let { }
    val jobs = rememberWorld(emptyList<WorldQuery.Job>()) { WorldQuery.jobs(AndroidSql(it.readableDatabase)) }
    val srcs = rememberWorld(emptyList<WorldQuery.Source>()) { WorldQuery.sources(AndroidSql(it.readableDatabase)) }
    val size = rememberWorld(0L) { it.fileSize(ctx) }
    val net = WorldSync.unmetered(ctx)
    var verifying by remember { mutableStateOf(false) }
    WorldPage {
        WorldCard(tint = if (WorldSync.running != null) C.INFO else C.BORDER) {
            Text(WorldSync.running?.let { "Sincronizando: $it" } ?: if (WorldSync.paused) "Pausada" else "Em espera", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            val p = WorldSync.progress
            if (WorldSync.running != null) { if (p != null) { LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), color = C.PRIMARY, trackColor = DS.ELEVATED); Text("${(p * 100).toInt()}% do arquivo (bytes lidos)", fontSize = 12.sp, color = C.MUTED) } else LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp), color = C.PRIMARY, trackColor = DS.ELEVATED) }
            val c = WorldSync.counters
            if (WorldSync.running != null) Text("${fmtN(c.analyzed)} analisados • ${fmtN(c.inserted)} novos • ${fmtN(c.updated)} atualizados • ${fmtN(c.deduped)} duplicados • ${fmtN(c.failed)} falhas", fontSize = 12.5.sp)
            if (WorldSync.lastMessage.isNotEmpty()) Text(WorldSync.lastMessage, fontSize = 12.5.sp, color = C.MUTED)
            Text("Rede: ${if (net) "Wi‑Fi/sem limite de dados ✓" else "dados móveis — a importação grande espera o Wi‑Fi"} • banco: ${fmtBytes(size)}", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { WorldSync.requestRun(ctx) }, modifier = Modifier.weight(1f), enabled = WorldSync.running == null) { Text("Sincronizar agora", fontWeight = FontWeight.ExtraBold) }
                if (WorldSync.paused) OutlinedButton(onClick = { WorldSync.resume(ctx) }, modifier = Modifier.weight(1f)) { Text("Retomar") } else OutlinedButton(onClick = { WorldSync.pause() }, modifier = Modifier.weight(1f), enabled = WorldSync.running != null) { Text("Pausar") }
            }
            OutlinedButton(onClick = { verifying = true; scope.launch { WorldSync.verify(ctx); verifying = false } }, enabled = !verifying, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text(if (verifying) "Verificando as fontes…" else "Verificar as fontes agora") }
            var mobile by remember { mutableStateOf(Settings.get(ctx, "world_mobile_data", "0") == "1") }
            SwitchRow("Permitir a importação grande também em dados móveis (~80 MB)", mobile) { mobile = it; Settings.put(ctx, "world_mobile_data", if (it) "1" else "0") }
        }
        DsSection("Por fonte")
        for (s in srcs.filter { WorldSources.def(it.id)?.adapter == true && WorldSources.def(it.id)?.probe?.endsWith(".csv") == true }) WorldCard {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(s.name, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2); DsChip(s.status, statusColor(s.status)) }
            Text("importados ${fmtN(s.imported)} • atualizados/duplicados ${fmtN(s.updated)} • erros ${s.errors} • última: ${s.lastSync?.let { ago(it) } ?: "nunca"} • próxima: ${s.nextSync?.let { fmtTime(it) } ?: "—"}", fontSize = 12.sp, color = C.MUTED)
            OutlinedButton(onClick = { WorldSync.requestSource(ctx, s.id) }, enabled = WorldSync.running == null, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("Sincronizar esta fonte", fontSize = 12.sp) }
        }
        DsSection("Histórico")
        if (jobs.isEmpty()) Text("Nenhuma sincronização ainda.", fontSize = 13.sp, color = C.MUTED)
        for (j in jobs) WorldCard { Row(verticalAlignment = Alignment.CenterVertically) { Text(WorldSources.def(j.source)?.name ?: j.source, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); DsChip(j.status, statusColor(j.status)) }
            Text("${j.started?.let { fmtTime(it) } ?: ""} • ${fmtN(j.analyzed)} lidos • ${fmtN(j.inserted)} novos • ${fmtN(j.deduped)} duplicados • ${j.failed} falhas", fontSize = 12.sp, color = C.MUTED); j.message?.let { Text(it, fontSize = 11.5.sp, color = C.MUTED) } }
    }
}

// ------------------------------------------------------------------ 9 · Fontes de dados
@Composable
internal fun WorldSourcesView() {
    val ctx = LocalContext.current
    val srcs = rememberWorld(emptyList<WorldQuery.Source>()) { WorldQuery.sources(AndroidSql(it.readableDatabase)) }
    WorldPage {
        Text("Cada fonte foi pesquisada em 25/11/26. O status só vira \"operacional\" depois de uma verificação real feita por este aparelho (aba Sincronização → Verificar as fontes) e de dados realmente gravados.", fontSize = 12.5.sp, color = C.MUTED)
        if (srcs.isEmpty()) WorldEmpty("Registro de fontes ainda não criado", "Abra a aba Sincronização uma vez.")
        for (s in srcs) WorldCard(tint = statusColor(s.status).copy(alpha = 0.5f)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(s.name, Modifier.weight(1f), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp); DsChip(s.status, statusColor(s.status)) }
            KV("Tipo", s.kind ?: "—"); KV("Licença", s.license ?: "—"); KV("Credencial", s.credential ?: "—"); KV("Limites/uso", s.quota ?: "—"); KV("Categorias", s.categories ?: "—")
            KV("Importados / atualizados / erros", "${fmtN(s.imported)} / ${fmtN(s.updated)} / ${s.errors}"); KV("Versão", s.version ?: "—"); KV("Última / próxima sincronização", "${s.lastSync?.let { ago(it) } ?: "—"} / ${s.nextSync?.let { fmtTime(it) } ?: "—"}"); KV("Verificada", s.verifiedAt?.let { ago(it) } ?: "ainda não")
            s.notes?.let { Text(it, fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp)) }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.site?.let { u -> OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("Site", fontSize = 12.sp) } }
                s.docs?.let { u -> OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("Documentação", fontSize = 12.sp) } }
            }
        }
    }
}

/** Caixa "Base Mundial" na tela do jogador do slot: identidade REAL confirmada/provável (camada separada; não altera força, valor nem atributos do jogo). */
@Composable
internal fun WorldRealBox(slot: Int, playerName: String) {
    val ctx = LocalContext.current
    val r = rememberLoaded(null as WorldOcr.RealPlayer?, 0, Pair(slot, playerName)) { runCatching { WorldOcr.realPlayer(ctx, slot, playerName) }.getOrNull() }
    val shape = RoundedCornerShape(DS.RADIUS_CARD)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(shape).background(DS.SURFACE).border(1.dp, C.INFO.copy(alpha = 0.45f), shape).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { DsIcon("globe", 18.dp, C.INFO); Spacer(Modifier.width(6.dp)); Text("Base Mundial (futebol real)", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, modifier = Modifier.weight(1f)); r?.let { DsChip("${it.status} · ${it.score}", statusColor(it.status)) } }
        if (r == null) Text("Ainda não identificado na base real (precisa de mais leituras ou de uma fonte). Isso não afeta nada do jogo.", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
        else {
            Text(r.fullName ?: r.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
            Text(listOfNotNull(r.dob?.let { "nasc. $it" }, r.nationality, r.position, r.heightCm?.let { "$it cm" }).joinToString(" • ").ifEmpty { "sem mais dados" }, fontSize = 12.5.sp, color = C.MUTED)
        }
    }
}
