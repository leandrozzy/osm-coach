package com.osmaicoach.collector

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pedidos de arte feitos no ChatGPT, UM POR ARTE (id da arte, slot, jogo, competição, rodada/fase), guardados no aparelho (sobrevivem ao fechar o app, 48 h).
 * A imagem que volta (Compartilhar › OSM Coach) só é salva num destino que o pedido indica: com 1 pedido válido vai direto; com 2 ou mais o usuário escolhe vendo a miniatura;
 * sem nenhum, nada é salvo. NUNCA se adivinha pelo último pedido aberto. O pedido só é marcado como concluído DEPOIS de a imagem ser salva.
 */
object ArtsRequests {
    const val TTL_MS = 48 * 3_600_000L
    private const val PREF = "arts"
    private const val KEY = "gpt_requests"

    data class Req(val id: String, val slot: Int, val kind: String, val title: String, val key: String?, val matchKey: String?, val comp: String?,
                   val round: Int?, val phase: String?, val label: String, val at: Long, val status: String = "pendente") {
        fun toCand() = Arts.Cand(id, slot, kind, title, at, key)
    }

    sealed class Route {
        object None : Route()
        class Auto(val req: Req) : Route()
        class Choose(val options: List<Req>) : Route()
    }

    // ------------------------------------------------------------------ lógica pura (testada)
    fun toJson(list: List<Req>): String {
        val arr = JSONArray()
        for (r in list) arr.put(JSONObject().put("id", r.id).put("slot", r.slot).put("kind", r.kind).put("title", r.title).put("key", r.key ?: "").put("matchKey", r.matchKey ?: "")
            .put("comp", r.comp ?: "").put("round", r.round ?: JSONObject.NULL).put("phase", r.phase ?: "").put("label", r.label).put("at", r.at).put("status", r.status))
        return arr.toString()
    }

    fun fromJson(s: String?): List<Req> = runCatching {
        val arr = JSONArray(s ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Req(id, o.optInt("slot"), o.optString("kind"), o.optString("title"), o.optString("key").takeIf { it.isNotBlank() }, o.optString("matchKey").takeIf { it.isNotBlank() },
                o.optString("comp").takeIf { it.isNotBlank() }, if (o.has("round") && !o.isNull("round")) o.optInt("round") else null, o.optString("phase").takeIf { it.isNotBlank() },
                o.optString("label"), o.optLong("at"), o.optString("status").ifBlank { "pendente" })
        }
    }.getOrDefault(emptyList())

    /** Um pedido por arte: pedir de novo a MESMA arte substitui o anterior; arte diferente fica ao lado. */
    fun upsert(list: List<Req>, r: Req): List<Req> = (list.filter { it.id != r.id } + r).takeLast(40)
    fun pending(list: List<Req>, now: Long): List<Req> = list.filter { it.status == "pendente" && now - it.at <= TTL_MS }.sortedByDescending { it.at }
    fun complete(list: List<Req>, id: String): List<Req> = list.map { if (it.id == id) it.copy(status = "concluido") else it }
    fun cancel(list: List<Req>, id: String): List<Req> = list.filter { it.id != id }

    fun route(list: List<Req>, now: Long): Route {
        val p = pending(list, now)
        return when (p.size) { 0 -> Route.None; 1 -> Route.Auto(p[0]); else -> Route.Choose(p) }
    }

    // ------------------------------------------------------------------ armazenamento no aparelho
    fun load(ctx: Context): List<Req> = fromJson(ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null))
    private fun save(ctx: Context, list: List<Req>) { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, toJson(list)).apply() }
    fun add(ctx: Context, r: Req) = save(ctx, upsert(load(ctx), r))
    fun markDone(ctx: Context, id: String) = save(ctx, complete(load(ctx), id))
    fun remove(ctx: Context, id: String) = save(ctx, cancel(load(ctx), id))
}

/** Imagens recebidas por Compartilhar que esperam o destino ser escolhido (miniatura + lista de pedidos). */
object ShareInbox {
    var files by mutableStateOf<List<String>>(emptyList())
    var options by mutableStateOf<List<ArtsRequests.Req>>(emptyList())
    fun offer(file: String, opts: List<ArtsRequests.Req>) { files = files + file; options = opts }
    fun pop() { files = files.drop(1) }
}

/** Entrada da imagem compartilhada: copia para o cache do app (a permissão do link some) e salva no destino ESCOLHIDO. */
object ArtsInbox {
    /** Copia a imagem do link para um PNG no cache, reduzida a no máx. ~2200 px; null se não abrir. */
    fun copy(ctx: Context, uri: android.net.Uri): String? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2200) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val f = java.io.File(ctx.cacheDir, "shared_${System.currentTimeMillis()}.png")
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle(); f.absolutePath
    }.getOrNull()

    /** Salva [file] como NOVA versão da arte do pedido [req]; só depois de salvar marca o pedido como concluído e apaga o temporário. */
    fun save(ctx: Context, file: String, req: ArtsRequests.Req): Boolean = runCatching {
        val bmp = android.graphics.BitmapFactory.decodeFile(file) ?: return false
        kotlinx.coroutines.runBlocking { Arts.storeBitmap(ctx, Repo(ctx), req.toCand(), bmp, asVersion = true) }
        ArtsRequests.markDone(ctx, req.id)
        ArtsGpt.markDone(ctx, req.id)
        runCatching { java.io.File(file).delete() }
        true
    }.getOrDefault(false)

    fun discard(file: String) { runCatching { java.io.File(file).delete() } }
}
