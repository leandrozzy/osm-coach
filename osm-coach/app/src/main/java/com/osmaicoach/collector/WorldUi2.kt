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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------ 7 · Cobertura
@Composable
internal fun WorldCoverageView(nav: WorldNav) {
    var sel by rememberSaveable { mutableStateOf<String?>(null) }
    val rows = rememberWorld(emptyList<Triple<String, String, LongArray>>()) { WorldCoverage.byCountry(it, 400, false) }
    val weight = remember(rows) { rows.associate { it.first to it.third[0].coerceAtLeast(1L) } }
    fun line(n: LongArray) = listOf(n[0] to "clubes", n[1] to "ligas", n[2] to "jogadores").filter { it.first > 0 }.joinToString(" • ") { "${fmtN(it.first)} ${it.second}" }
    WorldPage {
        Text("Mapa: os países que o seu jogo já mostrou; quanto mais claro, mais clubes do jogo identificados nele. Toque num país.", fontSize = 12.5.sp, color = C.MUTED)
        WorldMapView(sel, weight) { sel = it }
        sel?.let { iso ->
            val r = rows.firstOrNull { it.first == iso }
            WorldCard(tint = C.INFO, onClick = { nav.detail = "c:$iso" }) { Row(verticalAlignment = Alignment.CenterVertically) { FlagBadge(iso.uppercase(), null, null, 34.dp, 24.dp); Spacer(Modifier.width(8.dp)); Column { Text(r?.second ?: iso.uppercase(), fontWeight = FontWeight.ExtraBold, fontSize = 17.sp); Text(r?.let { line(it.third) } ?: "nada do OSM neste país", fontSize = 12.5.sp, color = C.MUTED) } } }
        }
        DsSection("Países do seu jogo")
        if (rows.isEmpty()) WorldEmpty("Nenhum país lido ainda", OSM_HINT)
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
            DsSection("Imagens baixadas no celular")
            WorldCard {
                Text("Só dos países, clubes e jogadores que aparecem nos seus slots: tem / precisa. Elas também vão para o Supabase e voltam de lá em outras instalações.", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(bottom = 4.dp))
                if (rows.isEmpty()) Text("Ainda não há o que baixar.", fontSize = 13.sp, color = C.MUTED)
                for ((l, h, n) in rows) WorldBar(l, h, n)
                if (WorldSync.imagesNote.isNotEmpty()) Text(WorldSync.imagesNote + " (o app envia ao Supabase o que baixa e busca lá antes de ir à internet)", fontSize = 11.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
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
                Button(onClick = {
                    testing = true; msg = "Testando a conexão…"
                    scope.launch {
                        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async { runCatching { WorldRemote.stats(ctx).getOrThrow() } }
                        val r = kotlinx.coroutines.withTimeoutOrNull(25_000L) { job.await() }
                        msg = when {
                            r == null -> "⏱ Sem resposta em 25 s. Confira a internet e se a URL está certa (https://xxxx.supabase.co)."
                            r.isSuccess -> r.getOrThrow().let { "✅ Conectado: ${fmtN(it.countries)} países • ${fmtN(it.competitions)} competições • ${fmtN(it.clubs)} clubes • ${fmtN(it.players)} jogadores • ${fmtN(it.images)} imagens" + (it.lastRun?.let { x -> " • última carga: $x" } ?: "") }
                            else -> "❌ ${r.exceptionOrNull()?.message ?: r.exceptionOrNull()?.javaClass?.simpleName}"
                        }
                        testing = false
                        // o registro no banco local roda à parte: se o banco estiver ocupado, a tela não espera
                        if (r != null) AppScope.scope.launch(Dispatchers.IO) { runCatching { WorldSync.recordSupabase(ctx, r) } }
                    }
                }, enabled = !testing, modifier = Modifier.weight(1f)) { Text(if (testing) "Testando…" else "Testar conexão", fontWeight = FontWeight.ExtraBold) }
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

/** Caixa "Base Mundial" na tela do jogador do slot: identidade REAL (camada separada; não altera força, valor nem atributos do jogo) e atalho para a foto pelo cadastro real. */
@Composable
internal fun WorldRealBox(slot: Int, playerName: String) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val r = rememberLoaded(null as WorldOcr.RealPlayer?, 0, Pair(slot, playerName)) { runCatching { WorldOcr.realPlayer(ctx, slot, playerName) }.getOrNull() }
    var msg by remember { mutableStateOf("") }
    val shape = RoundedCornerShape(DS.RADIUS_CARD)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(shape).background(DS.SURFACE).border(1.dp, C.INFO.copy(alpha = 0.45f), shape).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { DsIcon("globe", 18.dp, C.INFO); Spacer(Modifier.width(6.dp)); Text("Base Mundial (futebol real)", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, modifier = Modifier.weight(1f)); r?.let { DsChip("${it.status} · ${it.score}", statusColor(it.status)) } }
        if (r == null) Text("Ainda não identificado na base real (precisa de mais leituras). Em Base → Validação OCR você pode escolher o cadastro certo. Isso não afeta nada do jogo.", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
        else {
            Text(r.fullName ?: r.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
            Text(listOfNotNull(r.dob?.let { "nasc. ${it.take(10)}" }, r.nationality, r.position, r.heightCm?.let { "$it cm" }).joinToString(" • ").ifEmpty { "sem mais dados" }, fontSize = 12.5.sp, color = C.MUTED)
            val ids = listOfNotNull(r.wikidata?.let { "Wikidata $it" }, r.tmId?.let { "Transfermarkt $it" }, r.apifId?.let { "API-Football $it" }).joinToString(" • ")
            if (ids.isNotEmpty()) Text(ids, fontSize = 11.5.sp, color = C.MUTED)
            val key = remember(playerName) { Txt.key(playerName) }
            if (r.apifId != null && PhotoWeb.file(ctx.filesDir, key) == null) OutlinedButton(onClick = { scope.launch { val ok = runCatching { PhotoWeb.saveFromBaseId(ctx, key, r.apifId) }.getOrDefault(false); msg = if (ok) "✅ Foto salva." else "Não achei foto para este jogador." } }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Buscar a foto pelo cadastro real") }
            if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp, color = C.MUTED)
        }
    }
}
