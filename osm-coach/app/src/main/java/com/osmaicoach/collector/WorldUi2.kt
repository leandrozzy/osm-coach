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
    val size = rememberWorld(0L) { it.fileSize(ctx) }
    val net = WorldSync.unmetered(ctx)
    val running = WorldSync.running
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
                Button(onClick = { WorldSync.requestCloud(ctx) }, modifier = Modifier.weight(1f), enabled = running == null) { Text("Atualizar fontes na nuvem", fontWeight = FontWeight.ExtraBold) }
                if (WorldSync.paused) OutlinedButton(onClick = { WorldSync.resume(ctx) }, modifier = Modifier.weight(1f)) { Text("Retomar") } else OutlinedButton(onClick = { WorldSync.pause() }, modifier = Modifier.weight(1f), enabled = running != null) { Text("Pausar") }
            }
        }
        CloudBlock(ctx, running)
    }
}

/** Tudo sobre a nuvem em um lugar: conexão, quantos itens de cada fonte, fila, progresso da atualização automática e o histórico das execuções (da própria função world-sync). */
@Composable
private fun CloudBlock(ctx: android.content.Context, running: String?) {
    var st by remember { mutableStateOf<Result<org.json.JSONObject>?>(null) }
    var stats by remember { mutableStateOf<Result<WorldRemote.Stats>?>(null) }
    val note = WorldSync.cloudNote
    LaunchedEffect(note, running) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            stats = runCatching { WorldRemote.stats(ctx).getOrThrow() }
            st = WorldRemote.cloudStatus(ctx)
        }
    }
    DsSection("Nuvem (Supabase)")
    val s = stats?.getOrNull(); val j = st?.getOrNull()
    WorldCard(tint = (if (s != null) C.WIN else C.BORDER).copy(alpha = 0.5f)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Banco na nuvem", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 14.sp); DsChip(if (s != null) "conectado" else if (stats == null) "verificando…" else "sem conexão", if (s != null) C.WIN else C.MUTED) }
        if (s != null) Text("${fmtN(s.countries)} países • ${fmtN(s.competitions)} competições • ${fmtN(s.clubs)} clubes • ${fmtN(s.players)} jogadores • ${fmtN(s.images)} imagens", fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp))
        else stats?.exceptionOrNull()?.let { Text(it.message ?: "falhou", fontSize = 12.sp, color = C.MUTED) }
        (note.ifBlank { WorldRemote.cloudMessage(ctx) }).takeIf { it.isNotBlank() }?.let { Text("☁ $it", fontSize = 12.sp, color = C.INFO, modifier = Modifier.padding(top = 4.dp)) }
    }
    if (st != null && j == null) {
        WorldCard(tint = C.WARN.copy(alpha = 0.5f)) { Text("Atualização automática ainda não ativa: ${st?.exceptionOrNull()?.message}. Veja supabase/README.md (passos 1 a 4).", fontSize = 12.5.sp) }
        return
    }
    if (j == null) return
    fun line(o: org.json.JSONObject?, label: String, k: String) = o?.takeIf { it.has(k) }?.let { "$label ${fmtN(it.optLong(k))}" }
    val c = j.optJSONObject("clubs"); val p = j.optJSONObject("players")
    DsSection("De onde vêm os dados")
    WorldCard {
        Text("Clubes (${fmtN(c?.optLong("total") ?: 0)})", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(listOfNotNull(line(c, "Reep", "reep"), line(c, "com ID Wikidata", "wikidata"), line(c, "API-Football", "apif"), line(c, "novos pela atualização automática", "added_by_sync")).joinToString(" • "), fontSize = 12.sp, color = C.MUTED)
        Text("Jogadores (${fmtN(p?.optLong("total") ?: 0)})", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        Text(listOfNotNull(line(p, "Reep", "reep"), line(p, "com ID Wikidata", "wikidata"), line(p, "com ID API-Football", "apif"), line(p, "com ID Transfermarkt", "transfermarkt"), line(p, "novos pela atualização automática", "added_by_sync")).joinToString(" • "), fontSize = 12.sp, color = C.MUTED)
        Text("Fila de nomes que o app não achou: ${fmtN(j.optLong("wanted_pending"))} esperando • ${fmtN(j.optLong("wanted_done"))} resolvidos • ${fmtN(j.optLong("wanted_none"))} sem resultado", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
    }
    val tasks = j.optJSONObject("tasks")
    DsSection("Atualização automática")
    WorldCard {
        fun task(name: String, label: String): String {
            val t = tasks?.optJSONObject(name) ?: return "$label: ainda não começou"
            val cur = t.optString("cursor").takeIf { it.isNotBlank() && it != "null" }?.let { it.substringBefore(':').uppercase() + " (lote ${it.substringAfter(':').toIntOrNull()?.div(1500)?.plus(1) ?: 1})" }
            val cycles = t.optJSONObject("info")?.optInt("cycles", 0) ?: 0
            val err = t.optJSONObject("info")?.optString("error")?.takeIf { it.isNotBlank() }
            return "$label: parou em ${cur ?: "—"}${if (cycles > 0) " • $cycles volta(s) completa(s)" else ""}${err?.let { " • último erro: $it" } ?: ""}"
        }
        Text(task("wikidata_clubs", "Clubes (Wikidata)"), fontSize = 12.5.sp)
        Text(task("wikidata_players", "Jogadores (Wikidata)"), fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp))
        val q = tasks?.optJSONObject("apif_quota")?.optJSONObject("info")
        Text("API-Football hoje: ${q?.optInt("used", 0) ?: 0} de 90 consultas", fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp))
        Text("Roda sozinha de 3 em 3 horas no Supabase; cada execução avança alguns países e continua de onde parou.", fontSize = 11.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
    }
    DsSection("Histórico da nuvem")
    val runs = j.optJSONArray("runs")
    if (runs == null || runs.length() == 0) Text("Nenhuma execução ainda. Toque em “Atualizar fontes na nuvem”.", fontSize = 13.sp, color = C.MUTED)
    else for (i in 0 until runs.length()) {
        val r = runs.getJSONObject(i)
        WorldCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.optString("started_at").take(16).replace('T', ' '), Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                DsChip(r.optString("status").ifBlank { "?" }, if (r.optString("status") == "ok") C.WIN else C.WARN)
            }
            Text("${fmtN(r.optLong("rows_changed"))} itens novos ou completados", fontSize = 12.sp, color = C.MUTED)
        }
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
    val r = rememberLoaded(null as WorldOcr.RealPlayer?, 0, Triple(slot, playerName, UiBus.version)) { runCatching { WorldOcr.realPlayer(ctx, slot, playerName) }.getOrNull() }
    var msg by remember { mutableStateOf("") }
    val shape = RoundedCornerShape(DS.RADIUS_CARD)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(shape).background(DS.SURFACE).border(1.dp, C.INFO.copy(alpha = 0.45f), shape).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { DsIcon("globe", 18.dp, C.INFO); Spacer(Modifier.width(6.dp)); Text("Base Mundial (futebol real)", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, modifier = Modifier.weight(1f)); r?.let { DsChip("${it.status} · ${it.score}", statusColor(it.status)) } }
        var pick by remember(playerName) { mutableStateOf(false) }
        if (r == null) {
            Text("Ainda não identificado na base real. Abra o CARD GRANDE deste jogador no OSM (toque nele no Plantel) com a captura ligada: o app lê o nome completo e a idade e identifica sozinho. Ou escolha o cadastro certo abaixo. Isso não afeta nada do jogo.", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
            OutlinedButton(onClick = { pick = !pick }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text(if (pick) "Fechar a busca" else "Escolher o cadastro certo") }
        } else {
            Text(r.fullName ?: r.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
            Text(listOfNotNull(r.dob?.let { "nasc. ${it.take(10)}" }, r.nationality, r.position, r.heightCm?.let { "$it cm" }).joinToString(" • ").ifEmpty { "sem mais dados" }, fontSize = 12.5.sp, color = C.MUTED)
            val ids = listOfNotNull(r.wikidata?.let { "Wikidata $it" }, r.tmId?.let { "Transfermarkt $it" }, r.apifId?.let { "API-Football $it" }).joinToString(" • ")
            if (ids.isNotEmpty()) Text(ids, fontSize = 11.5.sp, color = C.MUTED)
            val key = remember(playerName) { Txt.key(playerName) }
            if (r.apifId != null && PhotoWeb.file(ctx.filesDir, key) == null) OutlinedButton(onClick = { scope.launch { val ok = runCatching { PhotoWeb.saveFromBaseId(ctx, key, r.apifId) }.getOrDefault(false); msg = if (ok) "✅ Foto salva." else "Não achei foto para este jogador." } }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Buscar a foto pelo cadastro real") }
            if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp, color = C.MUTED)
            // identificou o jogador errado (outro de mesmo nome, outra posição)? troque: a sua escolha vale mais que a automática
            OutlinedButton(onClick = { pick = !pick }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text(if (pick) "Fechar a busca" else "Não é este? Trocar / procurar de novo") }
        }
        if (pick) PickReal("player", WorldNorm.fold(playerName), playerName, slot)
    }
}
