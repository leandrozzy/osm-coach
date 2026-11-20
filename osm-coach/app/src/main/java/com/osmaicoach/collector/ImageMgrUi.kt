package com.osmaicoach.collector

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ajustes → Imagens → "Gestão de imagens": o que há de foto de cada jogador, de onde veio, e o que fazer com ela. */
@Composable
internal fun ImageManagerFold() {
    val ctx = LocalContext.current.applicationContext
    val tick = rememberTick(3000L)
    val items = rememberLoaded(emptyList<PhotoWeb.Item>(), tick, UiBus.version) { PhotoWeb.items(ctx) }
    val bytes = rememberLoaded(0L, tick, UiBus.version) { PhotoWeb.totalBytes(ctx.filesDir) }
    var filter by remember { mutableStateOf("all") }
    var q by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(25) }
    var pickFor by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Triple<String, String, suspend () -> String>?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val k = pickFor; pickFor = null
        if (uri != null && k != null) {
            android.widget.Toast.makeText(ctx, "Processando a foto…", android.widget.Toast.LENGTH_SHORT).show()
            AppScope.scope.launch(Dispatchers.IO) {
                val bmp = PhotoWeb.decodeUri(ctx, uri)
                val ok = bmp != null && PhotoWeb.saveManual(ctx, k, bmp)
                withContext(Dispatchers.Main) { android.widget.Toast.makeText(ctx, if (ok) "Foto salva." else "Não consegui ler essa imagem.", android.widget.Toast.LENGTH_SHORT).show() }
            }
        }
    }
    val count = { s: String -> items.count { it.source == s } }
    val withPhoto = items.count { it.source in listOf("manual", "wiki", "api") }
    Fold("🖼", "Gestão de imagens", "$withPhoto de ${items.size} com foto") {
        Text("Cada jogador dos seus slots, a origem da foto e o que fazer com ela. Vale para os 4 slots.", fontSize = 13.sp, color = C.MUTED)
        Panel {
            KV("✋ Inseridas manualmente", "${count("manual")}")
            KV("🌐 Da Wikipedia/Commons", "${count("wiki")}")
            KV("⚽ Da API-Football", "${count("api")}")
            KV("✂ Recorte do cartão do OSM", "${count("osm")}")
            KV("▫ Sem foto (silhueta)", "${count("none")}")
            KV("💾 Espaço usado", String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((id, label) in listOf("all" to "Todas", "manual" to "Manual", "wiki" to "Wikipedia", "api" to "API", "osm" to "Recorte OSM", "none" to "Sem foto"))
                FilterPill(label, filter == id) { filter = id; shown = 25 }
        }
        OutlinedTextField(q, { q = it; shown = 25 }, singleLine = true, label = { Text("Buscar jogador") }, modifier = Modifier.fillMaxWidth())
        val list = items.filter { (filter == "all" || it.source == filter) && (q.isBlank() || Txt.norm(it.name).contains(Txt.norm(q))) }.sortedBy { Txt.norm(it.name) }
        Text("${list.size} jogador(es)", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 6.dp))
        for (it in list.take(shown)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayerPhoto(it.key, 48.dp)
                Column(Modifier.weight(1f)) {
                    Text("${it.name} (S${it.slot})", fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(PhotoWeb.sourceLabel(it.source) + (if (it.blocked) " • busca automática desligada" else ""), fontSize = 12.sp, color = C.MUTED)
                    if (it.at > 0) Text("${ago(it.at)} • ${it.bytes / 1024} KB", fontSize = 11.5.sp, color = C.MUTED)
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { pickFor = it.key; pick.launch("image/*") }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("📷 Trocar", fontSize = 12.sp) }
                OutlinedButton(onClick = { AppScope.scope.launch(Dispatchers.IO) { PhotoWeb.retryPhoto(ctx, it.key); PhotoWeb.run(ctx, force = true) } }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("🔁 Buscar de novo", fontSize = 12.sp) }
                if (it.source in listOf("manual", "wiki", "api"))
                    OutlinedButton(onClick = { AppScope.scope.launch(Dispatchers.IO) { PhotoWeb.removePhoto(ctx, it.key) } }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = C.BAD)) { Text("🗑 Remover", fontSize = 12.sp) }
            }
        }
        if (list.size > shown) GhostButton("Mostrar mais (${list.size - shown})") { shown += 25 }
        Text("Em lote", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
        GhostButton("📦 Salvar cópia de todas as fotos (zip em Downloads)") {
            AppScope.scope.launch(Dispatchers.IO) {
                val n = PhotoWeb.exportZip(ctx)
                withContext(Dispatchers.Main) { android.widget.Toast.makeText(ctx, if (n != null) "Salvo em Downloads/OSM Coach: $n" else "Não há fotos para salvar.", android.widget.Toast.LENGTH_LONG).show() }
            }
        }
        GhostButton("🔁 Buscar de novo quem está sem foto") {
            confirm = Triple("Buscar de novo?", "Libera quem está sem foto (ou só com recorte) para a API e a Wikipedia tentarem outra vez.") {
                val n = PhotoWeb.retryMissing(ctx); PhotoWeb.run(ctx, force = true); "$n jogador(es) liberados."
            }
        }
        GhostButton("🗑 Apagar todas as da Wikipedia") {
            confirm = Triple("Apagar as da Wikipedia?", "Apaga as fotos vindas da Wikipedia/Commons e busca de novo.") { "${PhotoWeb.clearSource(ctx, "wiki")} apagada(s)." }
        }
        GhostButton("🗑 Apagar todas as da API") {
            confirm = Triple("Apagar as da API?", "Apaga as fotos vindas da API-Football e busca de novo (gasta a cota do dia).") { "${PhotoWeb.clearSource(ctx, "api")} apagada(s)." }
        }
        GhostButton("🗑 Apagar todas as inseridas manualmente") {
            confirm = Triple("Apagar as manuais?", "Apaga as fotos que você escolheu à mão. Elas NÃO voltam sozinhas: faça uma cópia antes se quiser.") { "${PhotoWeb.clearSource(ctx, "manual")} apagada(s)." }
        }
    }
    confirm?.let { (title, msg, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    AppScope.scope.launch(Dispatchers.IO) {
                        val r = runCatching { action() }.getOrDefault("Não deu certo.")
                        withContext(Dispatchers.Main) { android.widget.Toast.makeText(ctx, r, android.widget.Toast.LENGTH_LONG).show() }
                    }
                }) { Text("Confirmar") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancelar") } }
        )
    }
}
