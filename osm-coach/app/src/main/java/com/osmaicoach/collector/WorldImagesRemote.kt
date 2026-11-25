package com.osmaicoach.collector

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Base Mundial — imagens no Supabase Storage (bucket público `wf-images`). O app ENVIA as imagens que já usa (bandeiras, escudos da web, fotos) e, antes de baixar da internet,
 * BUSCA aqui o que o OSM mostrou e ainda não tem no aparelho: o banco vai se completando com o uso e serve a qualquer instalação.
 * Nada local é sobrescrito (escudos/bandeiras manuais e recortes do jogo ficam como estão e não são enviados). Só png ≤ 300 KB, caminho fixo `flags|crests|photos/<chave>.png`.
 */
object WorldImagesRemote {
    private const val BUCKET = "wf-images"; private const val MAX = 300_000; private const val MIN = 100
    private val KEY_OK = Regex("^[a-z0-9._-]{1,120}$")
    class Item(val kind: String, val key: String, val file: File) {
        val path get() = when (kind) { "flag" -> "flags/$key.png"; "crest" -> "crests/$key.png"; else -> "photos/$key.png" }
        val id get() = "$kind/$key"
    }
    @Volatile private var indexCache: Pair<Long, MutableSet<String>>? = null

    /** Imagens que o aparelho tem e podem ir para o Supabase (testável). */
    internal fun localItems(filesDir: File): List<Item> {
        fun scan(dir: File, kind: String): List<Item> = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".png") && !it.name.startsWith("_") && it.length() in MIN..MAX }
            ?.mapNotNull { f -> f.name.removeSuffix(".png").takeIf { KEY_OK.matches(it) }?.let { Item(kind, it, f) } } ?: emptyList()
        return scan(File(filesDir, "flags"), "flag") + scan(File(filesDir, "crests/web"), "crest") + scan(File(filesDir, "photos"), "photo")
    }

    private fun publicUrl(c: WorldRemote.Cfg, path: String) = "${c.url}/storage/v1/object/public/$BUCKET/$path"

    /** Conjunto "tipo/chave" do que já está no Supabase (1 h em memória). */
    fun index(ctx: Context, force: Boolean = false): MutableSet<String>? {
        indexCache?.let { if (!force && System.currentTimeMillis() - it.first < 3_600_000L) return it.second }
        val out = HashSet<String>(); var off = 0
        while (off < 200_000) {
            val (code, body) = WorldRemote.get(ctx, "/rest/v1/wf_images?select=kind,key&order=id&limit=1000&offset=$off")
            if (code !in 200..299 || body == null) return null
            val a = JSONArray(body); for (i in 0 until a.length()) a.getJSONObject(i).let { out.add("${it.optString("kind")}/${it.optString("key")}") }
            if (a.length() < 1000) break; off += 1000
        }
        indexCache = Pair(System.currentTimeMillis(), out); return out
    }

    /** O que o OSM mostrou e falta no aparelho: baixa do Supabase se existir lá. Devolve quantas imagens baixou (máx. [budget]). */
    fun pull(ctx: Context, budget: Int = 60): Int {
        val c = WorldRemote.cfg(ctx) ?: return 0
        val idx = index(ctx) ?: return 0
        if (idx.isEmpty()) return 0
        val fd = ctx.filesDir; val db = WorldDb.get(ctx); var n = 0
        fun fetch(kind: String, key: String, dest: File): Boolean {
            if (n >= budget || dest.exists() || !KEY_OK.matches(key) || "$kind/$key" !in idx) return false
            val it = Item(kind, key, dest)
            val bytes = runCatching { WorldHttp.get(publicUrl(c, it.path)).use { r -> if (r.ok) r.stream?.readBytes() else null } }.getOrNull() ?: return false
            if (bytes.size !in MIN..MAX) return false
            dest.parentFile?.mkdirs(); dest.writeBytes(bytes); n++; return true
        }
        db.query("SELECT iso2 FROM football_countries WHERE osm_seen>0") { it.getString(0) }.forEach { iso -> fetch("flag", iso.lowercase(), File(fd, "flags/${iso.lowercase()}.png")) }
        val crestDir = CrestWeb.dir(fd)
        db.query("SELECT name FROM football_clubs WHERE osm_seen>0 ORDER BY osm_seen DESC LIMIT 400") { it.getString(0) }.forEach { name ->
            val t = CrestAdmin.snap(fd, name) ?: name
            if (!CrestAdmin.isBlocked(fd, t) && CrestWeb.find(fd, t) == null) { val k = Txt.key(t); fetch("crest", k, File(crestDir, "$k.png")) }
        }
        runCatching {
            val repo = Repo(ctx)
            for (slot in 1..4) kotlinx.coroutines.runBlocking { repo.dao.playersOf(slot) }.forEach { p -> if (PhotoWeb.file(fd, p.nameKey) == null) fetch("photo", p.nameKey, File(PhotoWeb.dir(fd), "${p.nameKey}.png")) }
        }
        if (n > 0) UiBus.bump()
        return n
    }

    /** O que o aparelho tem e o Supabase ainda não: envia (máx. [budget] por rodada). Devolve quantas enviou. */
    fun push(ctx: Context, budget: Int = 40): Int {
        val c = WorldRemote.cfg(ctx) ?: return 0
        val idx = index(ctx) ?: return 0
        var n = 0
        for (it in localItems(ctx.filesDir)) {
            if (n >= budget) break
            if (it.id in idx) continue
            if (upload(c, it)) { idx.add(it.id); n++ }
        }
        return n
    }

    private fun upload(c: WorldRemote.Cfg, it: Item): Boolean = runCatching {
        val bytes = it.file.readBytes(); if (bytes.size !in MIN..MAX) return false
        val h = WorldRemote.headers(c)
        val (code, _) = WorldHttp.post("${c.url}/storage/v1/object/$BUCKET/${it.path}", bytes, "image/png", h + mapOf("x-upsert" to "false", "cache-control" to "max-age=31536000"))
        if (code !in 200..299 && code != 409 && code != 400) return false       // 409/400 "já existe": só garante a linha do índice
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { b -> "%02x".format(b) }.take(16)
        val row = JSONObject().put("kind", it.kind).put("key", it.key).put("path", it.path).put("bytes", bytes.size).put("sha", sha)
        val (rc, _) = WorldHttp.post("${c.url}/rest/v1/wf_images?on_conflict=kind,key", row.toString().toByteArray(), "application/json", h + mapOf("Prefer" to "resolution=ignore-duplicates,return=minimal"))
        rc in 200..299
    }.getOrDefault(false)
}
