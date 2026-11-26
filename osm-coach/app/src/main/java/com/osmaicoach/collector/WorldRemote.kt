package com.osmaicoach.collector

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

/**
 * Base Mundial — cliente do SUPABASE (a base completa mora lá; o aparelho NÃO importa nada em massa).
 * Fluxo: o OCR lê um nome → o app pede ao Supabase só os candidatos daquele nome (GET, chave anon, somente leitura) → guarda uma CÓPIA PEQUENA local
 * (só o que o OSM mostrou) → identifica. Uma vez por dia pergunta só "o que mudou desde a última vez" (`updated_at`) dos itens que já tem; o que não mudou não é baixado.
 * URL e chave anon ficam em `settings` (Ajustes), nunca no repositório. Somente https e só o host *.supabase.co configurado.
 */
object WorldRemote {
    const val K_URL = "world_sb_url"; const val K_KEY = "world_sb_key"; private const val K_CURSOR = "world_sb_cursor"
    private const val DAY = 86_400_000L
    class Cfg(val url: String, val key: String) { val host: String get() = URL(url).host.lowercase() }

    fun cfg(ctx: Context): Cfg? {
        val url = Settings.get(ctx, K_URL, "").trim().trimEnd('/'); val key = Settings.get(ctx, K_KEY, "").trim()
        return valid(url, key)?.also { WorldHttp.extraHosts = setOf(it.host) }
    }
    internal fun valid(url: String, key: String): Cfg? {
        if (key.length < 20 || key.any { it.isWhitespace() }) return null
        val host = runCatching { URL(url).takeIf { it.protocol == "https" && it.path.isEmpty() && it.userInfo == null }?.host?.lowercase() }.getOrNull() ?: return null
        return if (host.endsWith(".supabase.co") && host.length > ".supabase.co".length) Cfg(url, key) else null
    }

    /** Chaves novas (`sb_publishable_…`/`sb_secret_…`) NÃO são JWT: só no cabeçalho apikey. As legadas (JWT `eyJ…`) também vão em Authorization. */
    internal fun headersFor(key: String) = if (key.startsWith("sb_")) mapOf("apikey" to key, "Accept" to "application/json") else mapOf("apikey" to key, "Authorization" to "Bearer $key", "Accept" to "application/json")
    internal fun headers(c: Cfg) = headersFor(c.key)
    internal fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** GET JSON. (código HTTP, corpo). Lança só em falha de rede. */
    fun get(ctx: Context, path: String, timeoutMs: Int = 20_000): Pair<Int, String?> {
        val c = cfg(ctx) ?: return Pair(-1, null)
        val t0 = System.currentTimeMillis()
        val live = Live.begin("Supabase: " + liveName(path))
        return try { WorldHttp.text(c.url + path, headers(c), 6_000_000, timeoutMs).also { Diag.log("Base/Supabase GET ${path.substringBefore('?').take(40)} → HTTP ${it.first} em ${System.currentTimeMillis() - t0} ms") } }
        catch (e: Exception) { Diag.log("Base/Supabase GET ${path.substringBefore('?').take(40)} FALHOU em ${System.currentTimeMillis() - t0} ms: ${e.javaClass.simpleName} ${e.message?.take(80)}"); throw e }
        finally { Live.end(live) }
    }
    private fun liveName(path: String): String = when {
        "wf_match_clubs" in path -> "buscando clube"; "wf_match_players" in path -> "buscando jogador"; "wf_stats" in path -> "testando conexão"
        "wf_images" in path -> "índice de imagens"; "wf_competitions" in path -> "competição"; "wf_clubs" in path || "wf_players" in path -> "atualizando o que mudou"; else -> path.substringBefore('?').takeLast(24)
    }

    class Stats(val countries: Long, val competitions: Long, val clubs: Long, val players: Long, val lastRun: String?, val images: Long = 0)
    /** Testa a conexão e devolve as contagens REAIS do Supabase (nada de número inventado). */
    fun stats(ctx: Context): Result<Stats> = runCatching {
        val (code, body) = get(ctx, "/rest/v1/rpc/wf_stats", 15_000)
        if (code == -1) error("URL/chave não configuradas")
        if (code !in 200..299 || body == null) error(when (code) { 401, 403 -> "chave recusada (use a chave anon)"; 404 -> "banco ainda sem as tabelas: rode supabase/schema.sql"; else -> "HTTP $code" })
        val o = JSONObject(body)
        Stats(o.optLong("countries"), o.optLong("competitions"), o.optLong("clubs"), o.optLong("players"),
            o.optJSONObject("last_run")?.let { r -> "${r.optString("source")} • ${r.optString("status")} • ${r.optLong("rows_changed")} alterados" }, o.optLong("images"))
    }

    // ------------------------------------------------------------------ cópia local (testável: só SQL)
    private fun s(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.optString(k).trim().takeIf { it.isNotEmpty() }
    private fun i(o: JSONObject, k: String): Long? = if (o.isNull(k)) null else o.optLong(k)

    /** Grava (ou atualiza) clubes vindos do Supabase na cópia local. Devolve quantos. `comp` busca a competição (JSON) por chave; pode ser null. Nunca toca em `osm_seen`/`name_osm`. */
    fun upsertClubs(db: WorldSql, arr: JSONArray, comp: ((String) -> JSONObject?)? = null): Int {
        var n = 0; val now = System.currentTimeMillis()
        // rede ANTES da transação: nunca segurar o banco enquanto espera a internet
        val compCache = HashMap<String, JSONObject?>()
        if (comp != null) for (idx in 0 until arr.length()) arr.getJSONObject(idx).let { o -> s(o, "comp_key")?.let { k -> if (k !in compCache) compCache[k] = runCatching { comp(k) }.getOrNull() } }
        db.begin()
        try {
            for (idx in 0 until arr.length()) {
                val o = arr.getJSONObject(idx); val rid = i(o, "id") ?: continue; val name = s(o, "name") ?: continue
                if (!WorldClean.nameOk(name) || WorldClean.isWomen(name)) continue
                val k = WorldNorm.clubKey(name); val wd = s(o, "wikidata"); val iso = s(o, "iso2")
                var id = db.long("SELECT id FROM football_clubs WHERE remote_id=?", rid.toString()) ?: wd?.let { db.long("SELECT id FROM football_clubs WHERE wikidata=? AND remote_id IS NULL", it) }
                    ?: db.long("SELECT id FROM football_clubs WHERE name_key=? AND marker=? AND COALESCE(iso2,'')=? AND wikidata IS NULL AND remote_id IS NULL", k.core, k.marker, iso ?: "")
                val vals = arrayOf<Any?>(name, s(o, "short_name"), k.core, k.marker, iso, s(o, "city"), s(o, "founded"), s(o, "stadium"), s(o, "category"), s(o, "logo_url"), "supabase", now, rid, i(o, "prominence") ?: 0L)
                if (id == null) {
                    db.exec("INSERT INTO football_clubs(name,short_name,name_key,marker,iso2,city,founded,stadium,category,logo_url,source_id,updated_at,remote_id,prominence,wikidata) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(*vals, wd))
                    id = db.long("SELECT last_insert_rowid()")!!
                } else {
                    db.exec("UPDATE football_clubs SET name=?,short_name=?,name_key=?,marker=?,iso2=?,city=?,founded=?,stadium=?,category=?,logo_url=?,source_id=?,updated_at=?,remote_id=?,prominence=?,wikidata=COALESCE(wikidata,?) WHERE id=?", arrayOf<Any?>(*vals, wd, id))
                }
                val al = o.optJSONArray("aliases"); if (al != null) for (a in 0 until al.length()) {
                    val txt = al.optString(a).trim(); val ak = WorldNorm.fold(txt)
                    if (ak.isNotEmpty() && WorldClean.nameOk(txt)) db.exec("INSERT OR IGNORE INTO football_entity_aliases(entity_type,entity_id,alias,alias_key,kind,source_id) VALUES('club',?,?,?,?,?)", arrayOf(id, txt, ak, "remoto", "supabase"))
                }
                val ck = s(o, "comp_key")
                if (ck != null && comp != null) compCache[ck]?.let { co ->
                    val cid = upsertCompetition(db, co)
                    if (cid != null) db.exec("INSERT OR IGNORE INTO football_competition_clubs(competition_id,club_id,season,division,source_id) VALUES(?,?,?,?,?)", arrayOf(cid, id, "atual", s(o, "division"), "supabase"))
                }
                n++
            }
            db.commit()
        } catch (e: Exception) { runCatching { db.rollback() }; throw e }
        return n
    }

    fun upsertCompetition(db: WorldSql, o: JSONObject): Long? {
        val rid = i(o, "id") ?: return null; val name = s(o, "name") ?: return null
        if (!WorldClean.nameOk(name) || WorldClean.isWomen(name)) return null
        val wd = s(o, "wikidata"); val now = System.currentTimeMillis()
        var id = db.long("SELECT id FROM football_competitions WHERE remote_id=?", rid.toString()) ?: wd?.let { db.long("SELECT id FROM football_competitions WHERE wikidata=? AND remote_id IS NULL", it) }
        val vals = arrayOf<Any?>(name, WorldNorm.fold(name), s(o, "iso2"), i(o, "level"), s(o, "kind"), "supabase", now, rid)
        if (id == null) { db.exec("INSERT INTO football_competitions(name,name_key,iso2,level,kind,source_id,updated_at,remote_id,wikidata) VALUES(?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(*vals, wd)); id = db.long("SELECT last_insert_rowid()") }
        else db.exec("UPDATE football_competitions SET name=?,name_key=?,iso2=?,level=?,kind=?,source_id=?,updated_at=?,remote_id=?,wikidata=COALESCE(wikidata,?) WHERE id=?", arrayOf<Any?>(*vals, wd, id))
        return id
    }

    fun upsertPlayers(db: WorldSql, arr: JSONArray): Int {
        var n = 0; val now = System.currentTimeMillis()
        db.begin()
        try {
            for (idx in 0 until arr.length()) {
                val o = arr.getJSONObject(idx); val rid = i(o, "id") ?: continue; val name = s(o, "name") ?: continue
                if (!WorldClean.nameOk(name)) continue
                val pk = WorldNorm.playerKey(name); if (pk.surname.isEmpty()) continue
                val rp = s(o, "reep_id"); val wd = s(o, "wikidata")
                var id = db.long("SELECT id FROM football_players WHERE remote_id=?", rid.toString()) ?: rp?.let { db.long("SELECT id FROM football_players WHERE reep_id=? AND remote_id IS NULL", it) } ?: wd?.let { db.long("SELECT id FROM football_players WHERE wikidata=? AND remote_id IS NULL", it) }
                val vals = arrayOf<Any?>(name, s(o, "full_name"), pk.full, pk.surname, pk.initial, s(o, "dob"), i(o, "birth_year"), s(o, "nationality"), s(o, "nationality_iso"), s(o, "position"), i(o, "height_cm"), rp, s(o, "tm_id"), s(o, "apif_id"), "supabase", now, rid)
                if (id == null) {
                    val ins = "INSERT INTO football_players(name,full_name,name_key,surname_key,initial,dob,birth_year,nationality,nationality_iso,position,height_cm,reep_id,tm_id,apif_id,source_id,status,updated_at,remote_id,wikidata) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'desconhecido',?,?,?)"
                    try { db.exec(ins, arrayOf<Any?>(*vals, wd)) } catch (e: Exception) { db.exec(ins, arrayOf<Any?>(*vals, null)) }   // QID repetido em outro jogador: entra sem o QID
                } else {
                    db.exec("UPDATE football_players SET name=?,full_name=?,name_key=?,surname_key=?,initial=?,dob=?,birth_year=?,nationality=?,nationality_iso=?,position=?,height_cm=?,reep_id=?,tm_id=?,apif_id=?,source_id=?,updated_at=?,remote_id=? WHERE id=?", arrayOf<Any?>(*vals, id))
                }
                n++
            }
            db.commit()
        } catch (e: Exception) { runCatching { db.rollback() }; throw e }
        return n
    }

    // ------------------------------------------------------------------ busca por nome (só o que o OSM mostrou)
    private fun due(db: WorldSql, kind: String, key: String): Boolean {
        val r = db.rows("SELECT at,found FROM football_pull_log WHERE kind=? AND key=?", kind, key).firstOrNull() ?: return true
        val age = System.currentTimeMillis() - (r[0]?.toLongOrNull() ?: 0L)
        return age > (if (r[1] == "1") 30 * DAY else 7 * DAY)
    }
    private fun logPull(db: WorldSql, kind: String, key: String, found: Boolean) { db.exec("INSERT OR REPLACE INTO football_pull_log(kind,key,at,found) VALUES(?,?,?,?)", arrayOf(kind, key, System.currentTimeMillis(), if (found) 1 else 0)) }

    /** Lembra QUAIS linhas locais vieram da busca deste nome: a identificação compara com elas (antes só achava por prefixo e perdia "5C Fortuna Köln" → "SC Fortuna Köln"). */
    internal fun recordHits(db: WorldSql, kind: String, key: String, arr: JSONArray) {
        val table = if (kind == "club") "football_clubs" else "football_players"
        db.begin()
        try { for (i in 0 until arr.length()) arr.getJSONObject(i).let { o -> i(o, "id")?.let { rid -> db.exec("INSERT OR IGNORE INTO football_pull_hits(kind,key,entity_id) SELECT ?,?,id FROM $table WHERE remote_id=?", arrayOf(kind, key, rid)) } }; db.commit() }
        catch (e: Exception) { runCatching { db.rollback() }; throw e }
    }

    /** Pede ao Supabase os clubes parecidos com o nome lido e guarda na cópia local. `false` = sem conexão/erro (tenta de novo depois). */
    fun pullClub(ctx: Context, db: WorldSql, raw: String, force: Boolean = false): Boolean {
        val q = WorldNorm.fold(raw); if (q.length < 3 || cfg(ctx) == null || (!force && !due(db, "club", q))) return true
        val (code, body) = get(ctx, "/rest/v1/rpc/wf_match_clubs?q=${enc(q)}&lim=40")
        if (code !in 200..299 || body == null) return false
        val arr = JSONArray(body)
        upsertClubs(db, arr) { key -> competition(ctx, key) }
        recordHits(db, "club", q, arr)
        logPull(db, "club", q, arr.length() > 0); return true
    }

    fun pullPlayer(ctx: Context, db: WorldSql, raw: String, force: Boolean = false): Boolean {
        val pk = WorldNorm.playerKey(raw); if (pk.surname.length < 2 || cfg(ctx) == null || (!force && !due(db, "player", pk.full))) return true
        val last = pk.full.substringAfterLast(' ')
        val (code, body) = get(ctx, "/rest/v1/rpc/wf_match_players?p_full=${enc(pk.full)}&p_surname=${enc(pk.surname)}&p_last=${enc(last)}&lim=120")
        if (code !in 200..299 || body == null) return false
        val arr = JSONArray(body)
        upsertPlayers(db, arr)
        recordHits(db, "player", pk.full, arr)
        logPull(db, "player", pk.full, arr.length() > 0); return true
    }

    private fun competition(ctx: Context, key: String): JSONObject? {
        val (code, body) = get(ctx, "/rest/v1/wf_competitions?key=eq.${enc(key)}&select=*&limit=1")
        if (code !in 200..299 || body == null) return null
        return JSONArray(body).optJSONObject(0)
    }

    // ------------------------------------------------------------------ atualização diária: só o que MUDOU
    /** Para os clubes/jogadores que a cópia local já tem (do Supabase), baixa só as linhas com `updated_at` maior que a última verificação. Devolve quantas linhas mudaram. */
    fun refreshCache(ctx: Context, db: WorldSql): Int {
        if (cfg(ctx) == null) return 0
        val last = Settings.get(ctx, K_CURSOR, "0").toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - last < DAY) return 0
        val since = if (last == 0L) null else isoUtc(last - 3_600_000L)       // 1 h de folga
        var changed = 0; var ok = true
        for ((table, kind) in listOf("wf_clubs" to "club", "wf_players" to "player")) {
            val local = if (kind == "club") "football_clubs" else "football_players"
            val ids = db.rows("SELECT remote_id FROM $local WHERE remote_id IS NOT NULL AND (osm_seen>0 OR updated_at>?)", (System.currentTimeMillis() - 45 * DAY).toString()).mapNotNull { it[0]?.toLongOrNull() }
            for (chunk in ids.chunked(80)) {
                val q = "/rest/v1/$table?select=*&id=in.(${chunk.joinToString(",")})" + (since?.let { "&updated_at=gt.${enc(it)}" } ?: "")
                val (code, body) = runCatching { get(ctx, q) }.getOrDefault(Pair(-2, null))
                if (code !in 200..299 || body == null) { ok = false; break }
                val arr = JSONArray(body); if (arr.length() == 0) continue
                changed += if (kind == "club") upsertClubs(db, arr) { key -> competition(ctx, key) } else upsertPlayers(db, arr)
            }
        }
        if (ok) Settings.put(ctx, K_CURSOR, System.currentTimeMillis().toString())
        return changed
    }
    private fun isoUtc(ms: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))

    /** Competições que o cadastro real (Supabase) tem para um país: nome e nível. Consulta ao vivo, sem guardar. */
    fun countryCompetitions(ctx: Context, iso: String): List<Pair<String, Int?>> = runCatching {
        val (code, body) = get(ctx, "/rest/v1/wf_competitions?iso2=eq.${enc(iso.lowercase())}&select=name,level&order=level.asc.nullslast,name.asc&limit=60")
        if (code !in 200..299 || body == null) emptyList() else JSONArray(body).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> Pair(o.optString("name"), if (o.isNull("level")) null else o.optInt("level")) } } }
    }.getOrDefault(emptyList())
}
