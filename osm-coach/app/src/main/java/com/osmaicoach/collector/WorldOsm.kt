package com.osmaicoach.collector

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Base Mundial — ESCOPO DO OSM. A base real é enorme, mas o app só se interessa pelo que o OSM mostra: clubes/adversários, jogadores dos elencos,
 * países (bandeiras dos jogadores) e ligas (atlas dos filtros). Este módulo lê o que o app JÁ gravou (Room, só leitura) e o que a captura gravar daqui
 * em diante, marca `osm_seen` e deixa as listas da aba mostrarem só isso. Nada é inventado: sem leitura do OSM, a lista fica vazia e diz por quê.
 * Nunca altera o banco do app nem dados manuais (escudos/bandeiras manuais continuam valendo).
 */
object WorldOsm {
    @Volatile var running = false; private set
    @Volatile var lastReport: String = ""; private set
    @Volatile var lastAt: Long = 0L; private set

    /** Lê elencos, adversários e ligas já gravados e cria observações (idempotente: mesma chave de captura).
     *  ATENÇÃO: transação do SQLite pertence a UMA thread. Por isso TODA leitura do Room (suspend) acontece ANTES de abrir a transação da Base; dentro dela só SQL síncrono. */
    suspend fun backfill(ctx: Context): Int {
        val repo = Repo(ctx); val db: WorldSql = AndroidSql(WorldDb.get(ctx).w); val now = System.currentTimeMillis()
        class Obs(val slot: Int, val screen: String, val kind: String, val raw: String?, val ctx: JSONObject)
        val list = ArrayList<Obs>()
        for (slot in 1..4) {
            val fields = repo.dao.fieldsOf(slot).associate { it.fkey to it.fvalue }
            val league = fields[K.COMPETITION]
            fields[K.TEAM]?.let { list.add(Obs(slot, "FIELDS", "club", it, JSONObject().put("role", "meu").put("league", league))) }
            fields[K.RIVAL_TEAM]?.let { list.add(Obs(slot, "FIELDS", "club", it, JSONObject().put("role", "rival").put("league", league))) }
            for (m in repo.dao.matchesOf(slot)) m.opponent?.takeIf { !Fixtures.notAClub(it) }?.let { list.add(Obs(slot, "MATCHES", "club", it, JSONObject().put("role", "adversario").put("league", league))) }
            for (p in repo.dao.playersOf(slot)) list.add(Obs(slot, "SQUAD", "player", p.name, JSONObject().put("age", p.age).put("pos", p.posCode).put("cat", p.cat).put("owner", p.owner).put("nameKey", p.nameKey)))
        }
        var n = 0
        db.begin()
        try {
            for (o in list) {
                val r = o.raw?.trim()?.takeIf { it.length >= 2 && WorldClean.nameOk(it) } ?: continue
                val norm = WorldNorm.fold(r).takeIf { it.isNotEmpty() } ?: continue
                if (WorldCatalog.isIgnored(db, o.kind, norm)) continue
                n += db.exec("INSERT OR IGNORE INTO football_ocr_observations(slot,screen,kind,raw,norm,context,capture_key,at,league) VALUES(?,?,?,?,?,?,?,?,?)", arrayOf(o.slot, o.screen, o.kind, r, norm, o.ctx.toString(), "backfill:${o.slot}", now, o.ctx.optString("league").takeIf { it.isNotBlank() && it != "null" }))
            }
            db.commit()
        } catch (e: Exception) { runCatching { db.rollback() }; throw e }
        return n
    }

    /** Observações antigas guardavam a liga só dentro do JSON do contexto (o SQLite do Android não tem JSON1): copia para a coluna `league` (uma vez; idempotente). */
    internal fun migrateLeague(db: WorldSql) {
        while (true) {
            val rows = db.rows("SELECT id,context FROM football_ocr_observations WHERE league IS NULL AND context LIKE '%\"league\":\"%' LIMIT 500")
            if (rows.isEmpty()) return
            db.begin()
            try { for (r in rows) { val lg = runCatching { JSONObject(r[1] ?: "{}").optString("league") }.getOrDefault("").takeIf { it.isNotBlank() && it != "null" } ?: "-"; db.exec("UPDATE football_ocr_observations SET league=? WHERE id=?", arrayOf(lg, r[0]!!.toLong())) }; db.commit() } catch (e: Exception) { runCatching { db.rollback() }; throw e }
        }
    }

    /** Marca países (bandeiras dos jogadores + liga do slot + atlas de ligas "País, 2ª Divisão") e competições do OSM. */
    suspend fun markScope(ctx: Context) {
        val repo = Repo(ctx); val db: WorldSql = AndroidSql(WorldDb.get(ctx).w)
        val isos = HashSet<String>()
        for (slot in 1..4) repo.dao.plan(slot, "natiso4")?.json?.let { j -> runCatching { JSONObject(j) }.getOrNull()?.let { o -> for (k in o.keys()) o.optString(k).takeIf { it.length == 2 }?.let { isos.add(it.lowercase()) } } }
        runCatching { FlagManual.all(repo).values.forEach { if (it.length == 2) isos.add(it.lowercase()) } }
        val leagues = Atlas.load(repo, Atlas.Kind.LEAGUE).keys + (1..4).mapNotNull { s -> repo.dao.fieldsOf(s).firstOrNull { it.fkey == K.COMPETITION }?.fvalue }
        db.begin()
        try {
            for (iso in isos) db.exec("UPDATE football_countries SET osm_seen=1 WHERE iso2=?", arrayOf(iso))
            for (raw in leagues) {
                val parts = raw.split(',', limit = 2).map { it.trim() }
                val level = Regex("(\\d+)").find(parts.getOrNull(1) ?: "")?.value?.toIntOrNull() ?: if (parts.size == 2 && Txt.norm(parts[1]).contains("divis")) 1 else null
                val iso = db.rows("SELECT iso2 FROM football_countries WHERE name_key LIKE ? LIMIT 1", "%" + WorldNorm.fold(parts[0]) + "%").firstOrNull()?.get(0)
                if (iso != null && parts.size == 2) {
                    db.exec("UPDATE football_countries SET osm_seen=1 WHERE iso2=?", arrayOf(iso))
                    val lv = level ?: continue
                    val id = db.rows("SELECT c.id FROM football_competitions c WHERE c.iso2=? AND c.level=? AND (c.kind IS NULL OR c.kind NOT LIKE '%cup%') ORDER BY (SELECT COUNT(*) FROM football_competition_clubs x WHERE x.competition_id=c.id) DESC LIMIT 1", iso, lv.toString()).firstOrNull()?.get(0)
                    if (id != null) db.exec("UPDATE football_competitions SET osm_seen=1 WHERE id=?", arrayOf(id.toLong()))
                } else db.exec("UPDATE football_competitions SET osm_seen=1 WHERE name_key=?", arrayOf(WorldNorm.fold(raw)))
            }
            // propagação: o que contém clube do OSM entra no escopo
            db.exec("UPDATE football_countries SET osm_seen=1 WHERE iso2 IN (SELECT iso2 FROM football_clubs WHERE osm_seen>0 AND iso2 IS NOT NULL)")
            db.exec("UPDATE football_competitions SET osm_seen=1 WHERE level IS NOT NULL AND id IN (SELECT competition_id FROM football_competition_clubs WHERE club_id IN (SELECT id FROM football_clubs WHERE osm_seen>0))")
            db.exec("UPDATE football_countries SET osm_seen=1 WHERE iso2 IN (SELECT iso2 FROM football_competitions WHERE osm_seen>0 AND iso2 IS NOT NULL)")
            db.commit()
        } catch (e: Exception) { runCatching { db.rollback() }; throw e }
    }

    /** Limpeza única (nomes não latinos, feminino) + leitura do OSM + identificação. Seguro de chamar várias vezes; ignora se já estiver rodando. */
    suspend fun refresh(ctx: Context, force: Boolean = false): String = withContext(Dispatchers.IO) {
        if (running) return@withContext lastReport
        if (!force && System.currentTimeMillis() - lastAt < 20 * 60_000L) return@withContext lastReport
        running = true
        try {
            val wdb = WorldDb.get(ctx); val sql: WorldSql = AndroidSql(wdb.w)
            var purged = 0L
            // Cópia local enxuta: com o Supabase configurado, a cópia só guarda candidatos dos nomes lidos. Se ela estiver grande (restos da importação antiga), enxuga —
            // por CONDIÇÃO (não por marcador), para se corrigir sozinha se uma tentativa anterior não terminou.
            if (WorldRemote.cfg(ctx) != null && (wdb.count("football_players") > 60_000 || wdb.count("football_clubs") > 60_000)) {
                val t0 = System.currentTimeMillis(); Live.on("osm", "Base: enxugando a cópia local"); Diag.log("Base: enxugando a cópia local (${wdb.count("football_players")} jogadores, ${wdb.count("football_clubs")} clubes)…")
                purged = WorldClean.slim(sql); Diag.log("Base: cópia enxugada em ${System.currentTimeMillis() - t0} ms; compactando…")
                runCatching { wdb.w.execSQL("VACUUM") }; Diag.log("Base: compactação concluída em ${System.currentTimeMillis() - t0} ms")
            }
            Live.on("osm", "Base: lendo o que o OSM mostrou")
            migrateLeague(sql)
            val obs = backfill(ctx)
            Live.on("osm", "Base: identificando nomes lidos")
            var resolved = 0
            for (round in 1..8) { val k = WorldOcr.resolvePending(ctx, 400); resolved += k; if (k == 0) break }
            markScope(ctx)
            lastAt = System.currentTimeMillis()
            lastReport = buildString {
                if (purged > 0) append("Cópia local enxugada: $purged registros que o OSM nunca mostrou foram removidos (a base completa fica no Supabase). ")
                append("Lidos do app: $obs leituras novas; $resolved identificadas.")
            }
            UiBus.bump(); lastReport
        } finally { running = false; Live.off("osm") }
    }

    // ------------------------------------------------------------------ imagens (reaproveita os baixadores do app: bandeira Flagpedia, escudo web, foto Commons)
    class ImgStat(val flagsHave: Long, val flagsNeed: Long, val crestsHave: Long, val crestsNeed: Long, val photosHave: Long, val photosNeed: Long)
    fun imageStat(ctx: Context): ImgStat {
        val db = WorldDb.get(ctx)
        val isos = db.query("SELECT iso2 FROM football_countries WHERE osm_seen>0") { it.getString(0) }
        val clubs = db.query("SELECT name FROM football_clubs WHERE osm_seen>0") { it.getString(0) }
        val players = db.query("SELECT id,wikidata FROM football_players WHERE osm_seen>0") { Pair(it.getLong(0), it.getString(1)) }
        return ImgStat(isos.count { FlagWeb.file(ctx.filesDir, it) != null }.toLong(), isos.size.toLong(), clubs.count { CrestWeb.find(ctx.filesDir, it) != null }.toLong(), clubs.size.toLong(),
            players.count { WorldImages.file(ctx, it.first).exists() }.toLong(), players.count { it.second != null }.toLong())
    }

    /** Jogadores dos slots já identificados que têm ID da API-Football no cadastro real e ainda estão sem foto: baixa a imagem (não gasta a cota da API). Devolve quantas salvou. */
    suspend fun photosFromBase(ctx: Context, budget: Int = 12): Int {
        val repo = Repo(ctx); var n = 0
        for (slot in 1..4) for (p in repo.dao.playersOf(slot)) {
            if (n >= budget) return n
            if (PhotoWeb.file(ctx.filesDir, p.nameKey) != null) continue
            val re = WorldOcr.realPlayer(ctx, slot, p.name)?.takeIf { it.apifId != null && (it.status == "confirmado" || it.status == "muito alta") } ?: continue
            if (PhotoWeb.saveFromBaseId(ctx, p.nameKey, re.apifId!!)) n++
            kotlinx.coroutines.delay(400)
        }
        return n
    }

    /** Pede as imagens que faltam (só do escopo do OSM). Fotos: no máx. [photoBudget] por chamada (cota do Wikidata/Commons). Devolve quantas fotos baixou. */
    fun prefetchImages(ctx: Context, photoBudget: Int = 8): Int {
        val db = WorldDb.get(ctx)
        db.query("SELECT iso2 FROM football_countries WHERE osm_seen>0") { it.getString(0) }.forEach { FlagWeb.request(ctx, it) }
        var got = 0
        val ids = db.query("SELECT id FROM football_players WHERE osm_seen>0 AND wikidata IS NOT NULL ORDER BY osm_seen DESC LIMIT 200") { it.getLong(0) }
        for (id in ids) {
            if (got >= photoBudget) break
            if (WorldImages.file(ctx, id).exists()) continue
            val tried = db.scalar("SELECT COUNT(*) FROM football_images WHERE entity_type='player' AND entity_id=? AND kind='photo'", id.toString()) ?: 0L
            if (tried > 0 && db.scalar("SELECT COUNT(*) FROM football_images WHERE entity_type='player' AND entity_id=? AND kind='photo' AND status='pendente'", id.toString()) == 0L) continue
            if (runCatching { WorldImages.ensure(ctx, id) }.getOrNull() != null) got++
            Thread.sleep(1200)
        }
        if (got > 0) UiBus.bump()
        return got
    }
}
