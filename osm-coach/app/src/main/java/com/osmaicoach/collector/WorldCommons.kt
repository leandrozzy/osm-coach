package com.osmaicoach.collector

import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Base Mundial — licença de imagem (Wikimedia Commons). Foto/escudo só é exibido depois de a licença do ARQUIVO ser livre e verificável (CC0, CC BY, CC BY-SA, domínio público);
 * a atribuição (autor + licença) fica guardada. Sem isso: nunca exibe (a UI mostra a silhueta/placeholder neutro).
 */
object WorldCommons {
    private val FREE = Regex("^(cc0|cc[- ]by(?:[- ]sa)?(?:[- ][0-9.]+)?|public domain|pd[- ].*|attribution(?:[- ]share ?alike)?.*|cc by.*)$", RegexOption.IGNORE_CASE)
    class Info(val ok: Boolean, val license: String?, val attribution: String?, val thumbUrl: String?)

    fun isFree(shortName: String?): Boolean = shortName != null && FREE.matches(shortName.trim()) && !shortName.contains("nc", true) && !shortName.contains("nd", true)

    /** `fileUrl` = URL do arquivo no Commons (Special:FilePath/Nome.jpg ou upload.wikimedia.org/…/Nome.jpg). */
    fun fileName(fileUrl: String): String? = Regex("(?:Special:FilePath/|/commons/(?:thumb/)?[0-9a-f]/[0-9a-f]{2}/)([^/?]+)").find(fileUrl)?.groupValues?.get(1)?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

    fun check(db: WorldDb, fileName: String): Info? {
        val url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=imageinfo&iiprop=extmetadata%7Curl&iiurlwidth=320&titles=" + URLEncoder.encode("File:$fileName", "UTF-8")
        val (code, body) = WorldHttp.text(url)
        db.bumpUsage(WorldSources.COMMONS, code)
        if (code !in 200..299 || body == null) return null
        val pages = JSONObject(body).optJSONObject("query")?.optJSONObject("pages") ?: return null
        val page = pages.keys().asSequence().firstOrNull()?.let { pages.getJSONObject(it) } ?: return null
        val ii = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: return Info(false, null, null, null)
        val meta = ii.optJSONObject("extmetadata")
        val lic = meta?.optJSONObject("LicenseShortName")?.optString("value")
        val artist = meta?.optJSONObject("Artist")?.optString("value")?.replace(Regex("<[^>]+>"), "")?.trim()
        val nonFree = meta?.optJSONObject("NonFree")?.optString("value")?.equals("true", true) == true
        val ok = !nonFree && isFree(lic)
        return Info(ok, lic, listOfNotNull(artist, lic, "Wikimedia Commons").joinToString(" • "), ii.optString("thumburl").takeIf { it.isNotBlank() })
    }
}

/** Foto de jogador: Wikidata (P18) → arquivo do Commons → licença livre verificada → miniatura guardada no aparelho (≤ ~60 KB). Sem licença livre: nada é baixado nem exibido. */
object WorldImages {
    fun dir(ctx: android.content.Context) = java.io.File(ctx.filesDir, "world/photos").apply { mkdirs() }
    fun file(ctx: android.content.Context, playerId: Long) = java.io.File(dir(ctx), "$playerId.jpg")

    /** Devolve o arquivo se a foto existe e está licenciada; faz a verificação/baixa na 1ª vez (chamar fora da thread principal, 1 jogador por vez). */
    fun ensure(ctx: android.content.Context, playerId: Long): java.io.File? {
        val f = file(ctx, playerId); if (f.exists()) return f
        val db = WorldDb.get(ctx)
        val st = db.query("SELECT status FROM football_images WHERE entity_type='player' AND entity_id=? AND kind='photo' ORDER BY (status='ok') DESC LIMIT 1", playerId.toString()) { it.getString(0) }.firstOrNull()
        if (st == "bloqueada") return null
        var url = db.query("SELECT url FROM football_images WHERE entity_type='player' AND entity_id=? AND kind='photo' AND status='pendente' LIMIT 1", playerId.toString()) { it.getString(0) }.firstOrNull()
        if (url == null) {
            val qid = db.query("SELECT wikidata FROM football_players WHERE id=?", playerId.toString()) { it.getString(0) }.firstOrNull() ?: return null
            val (code, body) = WorldHttp.text("https://www.wikidata.org/w/api.php?action=wbgetclaims&format=json&property=P18&entity=$qid")
            db.bumpUsage(WorldSources.WIKIDATA, code)
            val v = body?.let { runCatching { JSONObject(it).getJSONObject("claims").getJSONArray("P18").getJSONObject(0).getJSONObject("mainsnak").getJSONObject("datavalue").getString("value") }.getOrNull() } ?: return null
            url = "https://commons.wikimedia.org/wiki/Special:FilePath/" + URLEncoder.encode(v, "UTF-8").replace("+", "%20")
            db.w.execSQL("INSERT OR IGNORE INTO football_images(entity_type,entity_id,kind,url,source_id,status) VALUES('player',?,'photo',?,?, 'pendente')", arrayOf(playerId, url, WorldSources.WIKIDATA))
        }
        val name = WorldCommons.fileName(url) ?: return null
        val info = WorldCommons.check(db, name) ?: return null
        db.w.execSQL("UPDATE football_images SET license=?,attribution=?,checked_at=?,status=? WHERE entity_type='player' AND entity_id=? AND kind='photo' AND url=?", arrayOf(info.license, info.attribution, System.currentTimeMillis(), if (info.ok) "ok" else "bloqueada", playerId, url))
        if (!info.ok || info.thumbUrl == null) return null
        return try {
            WorldHttp.get(info.thumbUrl).use { r ->
                db.bumpUsage(WorldSources.COMMONS, r.code)
                val bytes = r.stream?.readBytes()?.takeIf { it.size in 1..400_000 } ?: return null
                f.writeBytes(bytes); db.w.execSQL("UPDATE football_images SET local_path=?,hash=? WHERE entity_type='player' AND entity_id=? AND kind='photo'", arrayOf(f.absolutePath, java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16), playerId))
                f
            }
        } catch (e: Exception) { null }
    }
}
