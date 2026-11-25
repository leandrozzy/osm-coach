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
        val nObs = rememberWorld(0L) { it.count("football_ocr_observations") }
        Text(if (nObs == 0L) "Ainda não há leituras: esta lista se preenche sozinha quando o app lê telas do OSM (Plantel, Calendário, Pré-jogo) com a captura ligada. Não precisa abrir nada específico." else "$nObs leituras do OCR comparadas com a base real. O app confirma sozinho só com evidência além do nome; o resto espera novas leituras. Nada aqui gera notificação.", fontSize = 12.5.sp, color = C.MUTED)
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
@Composable
internal fun WorldCoverageView(nav: WorldNav) {
    var sel by rememberSaveable { mutableStateOf<String?>(null) }
    val rows = rememberWorld(emptyList<Triple<String, String, LongArray>>(), nav.all) { WorldCoverage.byCountry(it, 400, nav.all) }
    val weight = remember(rows) { rows.associate { it.first to it.third[0].coerceAtLeast(1L) } }
    fun line(n: LongArray) = listOf(n[0] to "clubes", n[1] to "ligas", n[2] to "jogadores").filter { it.first > 0 }.joinToString(" • ") { "${fmtN(it.first)} ${it.second}" }
    WorldPage {
        WorldScope(nav)
        Text(if (nav.all) "Mapa: quanto mais claro, mais clubes a base mundial tem no país." else "Mapa: só os países que o OSM já mostrou ao app; quanto mais claro, mais clubes.", fontSize = 12.5.sp, color = C.MUTED)
        WorldMapView(sel, weight) { sel = it }
        sel?.let { iso ->
            val r = rows.firstOrNull { it.first == iso }
            WorldCard(tint = C.INFO, onClick = { nav.detail = "c:$iso" }) { Row(verticalAlignment = Alignment.CenterVertically) { FlagBadge(iso.uppercase(), null, null, 34.dp, 24.dp); Spacer(Modifier.width(8.dp)); Column { Text(r?.second ?: iso.uppercase(), fontWeight = FontWeight.ExtraBold, fontSize = 17.sp); Text(r?.let { line(it.third) } ?: "nada do OSM neste país", fontSize = 12.5.sp, color = C.MUTED) } } }
        }
        DsSection(if (nav.all) "Países com dados" else "Países do OSM")
        if (rows.isEmpty()) WorldEmpty(if (nav.all) "Sem dados importados ainda" else "Nenhum país do OSM ainda", if (nav.all) null else OSM_HINT)
        else rows.forEach { (iso, name, n) -> WorldCard(onClick = { nav.detail = "c:$iso" }) { Row(verticalAlignment = Alignment.CenterVertically) { FlagBadge(iso.uppercase().takeIf { it.length == 2 }, null, null, 30.dp, 20.dp); Spacer(Modifier.width(8.dp)); Text(name, Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(line(n), fontSize = 12.sp, color = C.MUTED, maxLines = 1) } } }
        Text("Mapa: Natural Earth 1:110m via world-atlas. Microestados e ilhas pequenas não aparecem no desenho; estão na lista.", fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
    }
}

// ------------------------------------------------------------------ 8 · Sincronização
@Composable
internal fun WorldSyncView() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val tick = rememberTick(1000L); tick.let { }
    val jobs = rememberWorld(emptyList<WorldQuery.Job>()) { WorldQuery.jobs(AndroidSql(it.readableDatabase), 15) }
    val srcs = rememberWorld(emptyList<WorldQuery.Source>()) { WorldQuery.sources(AndroidSql(it.readableDatabase)) }
    val size = rememberWorld(0L) { it.fileSize(ctx) }
    val img = rememberWorld(null as WorldOsm.ImgStat?) { WorldOsm.imageStat(ctx) }
    val net = WorldSync.unmetered(ctx)
    val running = WorldSync.running
    var busyImg by remember { mutableStateOf(false) }
    WorldPage {
        WorldCard(tint = if (running != null) C.INFO else C.BORDER) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (running != null) "Sincronizando" else if (WorldSync.paused) "Pausada" else "Em espera", Modifier.weight(1f), fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                DsChip(if (running != null) "em andamento" else if (WorldSync.paused) "pausada" else "pronta", if (running != null) C.INFO else if (WorldSync.paused) C.GOLD else C.OK)
            }
            if (running != null) {
                Text(running, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                val ov = WorldSync.overall
                if (ov != null) { LinearProgressIndicator(progress = { ov }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = C.PRIMARY, trackColor = DS.ELEVATED); Text("Geral: etapa ${WorldSync.step + 1} de ${WorldSync.steps} • ${(ov * 100).toInt()}%", fontSize = 12.sp, color = C.MUTED) }
                val p = WorldSync.progress
                if (p != null) { LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = C.INFO, trackColor = DS.ELEVATED); Text("Arquivo atual: ${(p * 100).toInt()}% (bytes lidos)", fontSize = 12.sp, color = C.MUTED) }
                val c = WorldSync.counters
                if (c.analyzed > 0) Text("${fmtN(c.analyzed)} lidos • ${fmtN(c.inserted)} novos • ${fmtN(c.updated)} atualizados • ${fmtN(c.deduped)} duplicados", fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (WorldSync.lastMessage.isNotEmpty()) Text(WorldSync.lastMessage, fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
            Text("Rede: ${if (net) "Wi‑Fi ✓" else "dados móveis (consultas pequenas, tudo bem)"} • cópia local: ${fmtBytes(size)}", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { WorldSync.requestRun(ctx) }, modifier = Modifier.weight(1f), enabled = running == null) { Text("Sincronizar agora", fontWeight = FontWeight.ExtraBold) }
                if (WorldSync.paused) OutlinedButton(onClick = { WorldSync.resume(ctx) }, modifier = Modifier.weight(1f)) { Text("Retomar") } else OutlinedButton(onClick = { WorldSync.pause() }, modifier = Modifier.weight(1f), enabled = running != null) { Text("Pausar") }
            }
        }
        img?.let { im ->
            val rows = listOf(Triple("Bandeiras", im.flagsHave, im.flagsNeed), Triple("Escudos", im.crestsHave, im.crestsNeed), Triple("Fotos de jogadores", im.photosHave, im.photosNeed)).filter { it.third > 0 }
            DsSection("Imagens do que está no OSM")
            WorldCard {
                if (rows.isEmpty()) Text("Ainda não há países, clubes ou jogadores do OSM identificados; as imagens são pedidas assim que houver.", fontSize = 13.sp, color = C.MUTED)
                for ((l, h, n) in rows) WorldBar(l, h, n)
                if (rows.isNotEmpty()) OutlinedButton(onClick = { busyImg = true; scope.launch(Dispatchers.IO) { runCatching { WorldOsm.prefetchImages(ctx, 12) }; busyImg = false } }, enabled = !busyImg, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text(if (busyImg) "Baixando…" else "Baixar imagens que faltam") }
            }
        }
        DsSection("Supabase (base completa)")
        val sb = srcs.firstOrNull { it.id == WorldSources.SUPABASE }
        val cache = rememberWorld(LongArray(3)) { db -> longArrayOf(db.count("football_clubs"), db.count("football_players"), db.count("football_ocr_observations", "status='novo'")) }
        WorldCard(tint = statusColor(sb?.status ?: "aguardando configuração").copy(alpha = 0.5f)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Banco na nuvem", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 14.sp); DsChip(sb?.status ?: "aguardando configuração", statusColor(sb?.status ?: "aguardando configuração")) }
            Text(sb?.notes ?: "Configure a URL e a chave em Fontes de Dados → Configurar.", fontSize = 12.sp, color = C.MUTED)
            Text("No aparelho (cópia do que o OSM mostrou): ${fmtN(cache[0])} clubes • ${fmtN(cache[1])} jogadores" + (if (cache[2] > 0) " • ${fmtN(cache[2])} leituras esperando rede" else ""), fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
            Text("O app não baixa a base inteira: pede só os nomes lidos e, 1x por dia, só o que mudou.", fontSize = 11.5.sp, color = C.MUTED)
        }
        DsSection("Histórico")
        val hist = jobs.filter { running != null || it.status != "sincronizando" }
        if (hist.isEmpty()) Text("Nenhuma sincronização ainda.", fontSize = 13.sp, color = C.MUTED)
        for (j in hist) WorldCard { Row(verticalAlignment = Alignment.CenterVertically) { Text(WorldSources.def(j.source)?.name?.substringBefore(" —") ?: j.source, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); DsChip(j.status, statusColor(j.status)) }
            Text("${j.started?.let { fmtTime(it) } ?: ""} • ${fmtN(j.analyzed)} lidos • ${fmtN(j.inserted)} novos${if (j.failed > 0) " • ${j.failed} descartados" else ""}", fontSize = 12.sp, color = C.MUTED); j.message?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 11.5.sp, color = C.MUTED) } }
    }
}

// ------------------------------------------------------------------ 9 · Fontes de dados
@Composable
internal fun WorldSourcesView() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val srcs = rememberWorld(emptyList<WorldQuery.Source>()) { WorldQuery.sources(AndroidSql(it.readableDatabase)) }
    val enabled = rememberWorld(emptySet<String>()) { db -> db.query("SELECT id FROM football_sources WHERE enabled=1") { it.getString(0) }.toSet() }
    var testing by remember { mutableStateOf(false) }; var reading by remember { mutableStateOf(false) }; var msg by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(Settings.get(ctx, WorldRemote.K_URL, "")) }
    var sbKey by remember { mutableStateOf(Settings.get(ctx, WorldRemote.K_KEY, "")) }
    val inUse = srcs.filter { WorldSources.def(it.id)?.adapter == true }
    val others = srcs.filter { WorldSources.def(it.id)?.adapter != true }
    WorldPage {
        DsSection("Configurar o Supabase")
        WorldCard {
            Text("1) Crie um projeto grátis em supabase.com  2) rode supabase/schema.sql  3) rode o workflow world-loader no GitHub (veja supabase/README.md)  4) cole abaixo a URL e a chave ANON (nunca a service_role).", fontSize = 12.sp, color = C.MUTED)
            OutlinedTextField(url, { url = it; Settings.put(ctx, WorldRemote.K_URL, it.trim()) }, singleLine = true, label = { Text("URL do projeto (https://xxxx.supabase.co)") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            OutlinedTextField(sbKey, { sbKey = it; Settings.put(ctx, WorldRemote.K_KEY, it.trim()) }, singleLine = true, label = { Text("Chave anon (somente leitura)") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { testing = true; scope.launch(Dispatchers.IO) { msg = WorldSync.checkSupabase(ctx, WorldDb.get(ctx)).fold({ "✅ Conectado: ${fmtN(it.countries)} países • ${fmtN(it.competitions)} competições • ${fmtN(it.clubs)} clubes • ${fmtN(it.players)} jogadores" + (it.lastRun?.let { r -> " • última carga: $r" } ?: "") }, { "❌ ${it.message}" }); testing = false } }, enabled = !testing, modifier = Modifier.weight(1f)) { Text(if (testing) "Testando…" else "Testar conexão", fontWeight = FontWeight.ExtraBold) }
                OutlinedButton(onClick = { reading = true; scope.launch { msg = runCatching { WorldOsm.refresh(ctx, force = true) }.getOrElse { "Falhou: ${it.message}" }; reading = false } }, enabled = !reading, modifier = Modifier.weight(1f)) { Text(if (reading) "Lendo…" else "Ler o que o app já viu", fontSize = 13.sp) }
            }
            if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
        }
        DsSection("Fontes em uso")
        if (inUse.isEmpty()) WorldEmpty("Registro de fontes ainda não criado", "Abra a aba Sincronização uma vez.")
        for (s in inUse) {
            val on = s.id in enabled
            WorldCard(tint = statusColor(s.status).copy(alpha = 0.5f)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(s.name.substringBefore(" —"), Modifier.weight(1f), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp); DsChip(s.status, statusColor(s.status)) }
                Text(listOfNotNull(s.categories, s.license?.let { "licença: $it" }).joinToString(" • "), fontSize = 12.sp, color = C.MUTED)
                Text("${fmtN(s.imported)} importados • última: ${s.lastSync?.let { ago(it) } ?: "nunca"} • erros: ${s.errors}", fontSize = 12.sp, color = C.MUTED)
                SwitchRow("Ativa", on) { v -> scope.launch(Dispatchers.IO) { WorldDb.get(ctx).w.execSQL("UPDATE football_sources SET enabled=? WHERE id=?", arrayOf(if (v) 1 else 0, s.id)); UiBus.bump() } }
                s.site?.let { u -> OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("Abrir site", fontSize = 12.sp) } }
            }
        }
        Fold("list", "Outras fontes avaliadas", badge = others.size.toString()) {
            for (s in others) WorldCard { Row(verticalAlignment = Alignment.CenterVertically) { Text(s.name.substringBefore(" —"), Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 14.sp); DsChip(s.status, statusColor(s.status)) }; s.notes?.let { Text(it, fontSize = 12.sp, color = C.MUTED) } }
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
