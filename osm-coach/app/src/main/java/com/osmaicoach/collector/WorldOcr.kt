package com.osmaicoach.collector

import android.content.Context
import org.json.JSONObject

/**
 * Base Mundial — ponte bidirecional com o OCR EXISTENTE (não há segundo OCR e nada espera a rede):
 *  OCR → [observe] grava a observação original (texto bruto + normalizado + contexto do slot) → [resolvePending] compara com a base local (aliases, IDs, contexto) → confiança →
 *  confirmação segura OU pendência reavaliável → mapeamento OSM↔real POR SLOT → enriquecimento externo assíncrono (Wikidata) → nova avaliação.
 * Camadas separadas: aqui só entram identidade e fatos reais; força/valor/atributos do jogo continuam nos planos do app e nunca são sobrescritos.
 */
object WorldOcr {
    private const val KIND_CLUB = "club"; private const val KIND_PLAYER = "player"

    /** Fila própria (1 thread, prioridade baixa, no máx. 40 pendentes — descarta as mais antigas): o OCR/captura NUNCA espera o banco da Base. */
    private val exec = java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, java.util.concurrent.ArrayBlockingQueue(40), { r -> Thread(r, "world-ocr").apply { priority = Thread.MIN_PRIORITY; isDaemon = true } }, java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy())
    fun submit(ctx: Context, slot: Int, ex: Extraction, now: Long) { val app = ctx.applicationContext; exec.execute { runCatching { fromExtraction(app, slot, ex, now) } } }

    /** Roda na fila de [submit]: barato, sem rede, falha isolada. */
    fun fromExtraction(ctx: Context, slot: Int, ex: Extraction, now: Long) {
        val db: WorldSql = AndroidSql(WorldDb.get(ctx).w)
        val capture = "${ex.type}:${now / 3_600_000L}"           // mesma tela na mesma hora = a MESMA evidência
        val league = ex.fields[K.COMPETITION]?.value
        fun obs(kind: String, raw: String?, ctxJson: JSONObject) {
            val r = raw?.trim()?.takeIf { it.length >= 2 } ?: return
            val norm = WorldNorm.fold(r).takeIf { it.isNotEmpty() } ?: return
            if (WorldCatalog.isIgnored(db, kind, norm)) return
            db.exec("INSERT OR IGNORE INTO football_ocr_observations(slot,screen,kind,raw,norm,context,capture_key,at,league) VALUES(?,?,?,?,?,?,?,?,?)", arrayOf(slot, ex.type.name, kind, r, norm, ctxJson.put("league", league).toString(), capture, now, league))
        }
        ex.fields[K.TEAM]?.value?.let { obs(KIND_CLUB, it, JSONObject().put("role", "meu")) }
        ex.fields[K.RIVAL_TEAM]?.value?.let { obs(KIND_CLUB, it, JSONObject().put("role", "rival")) }
        ex.ownerTeam?.let { obs(KIND_CLUB, it, JSONObject().put("role", "meu")) }
        for (m in ex.matches) m.opponent?.takeIf { !Fixtures.notAClub(it) }?.let { obs(KIND_CLUB, it, JSONObject().put("role", "adversario")) }
        for (cr in ex.crests) cr.team.takeIf { it.isNotBlank() }?.let { obs(KIND_CLUB, it, JSONObject().put("role", "liga")) }
        for (p in ex.players) obs(KIND_PLAYER, p.name, JSONObject().put("age", p.age).put("pos", p.posCode).put("cat", p.cat).put("owner", "?").put("nameKey", Txt.key(p.name)))
    }

    // ------------------------------------------------------------------ resolução
    internal fun clubCandidates(db: WorldSql, raw: String, key: WorldNorm.ClubKey): List<WorldIdentity.ClubC> {
        val first = key.core.substringBefore(' ')
        val rows = db.q(
            """SELECT DISTINCT c.id,c.name,c.iso2,c.marker,c.prominence,c.city FROM football_clubs c LEFT JOIN football_entity_aliases a ON a.entity_type='club' AND a.entity_id=c.id
               WHERE c.name_key=? OR a.alias_key=? OR (c.name_key LIKE ? AND length(c.name_key)<=?) OR c.id IN (SELECT entity_id FROM football_pull_hits h WHERE h.kind='club' AND h.key=?) LIMIT 80""", key.core, WorldNorm.fold(raw), "$first%", (key.core.length + 8).toString(), WorldNorm.fold(raw)
        ) { arrayOf<Any?>(it.getLong(0), it.getString(1), it.getString(2), it.getString(3), it.getInt(4), it.getString(5)) }
        return rows.map { r ->
            val id = r[0] as Long; val name = r[1] as String
            val aliases = db.q("SELECT alias FROM football_entity_aliases WHERE entity_type='club' AND entity_id=? LIMIT 8", id.toString()) { it.getString(0) }
            val league = db.q("SELECT comp.name FROM football_competition_clubs cc JOIN football_competitions comp ON comp.id=cc.competition_id WHERE cc.club_id=? LIMIT 1", id.toString()) { it.getString(0) }.firstOrNull()
            WorldIdentity.ClubC(id, name, aliases, r[2] as String?, (r[3] as String?) ?: "", league, r[4] as Int, r[5] as String?)
        }
    }

    internal fun playerCandidates(db: WorldSql, pk: WorldNorm.PlayerKey): List<WorldIdentity.PlayerC> {
        if (pk.surname.isEmpty()) return emptyList()
        val rows = db.q("SELECT id,name,full_name,birth_year,COALESCE(nationality,nationality_iso),position FROM football_players WHERE surname_key=? OR name_key=? OR id IN (SELECT entity_id FROM football_pull_hits h WHERE h.kind='player' AND h.key=?) LIMIT 300", pk.surname, pk.full, pk.full) {
            WorldIdentity.PlayerC(it.getLong(0), it.getString(1), it.getString(2), emptyList(), if (it.isNull(3)) null else it.getInt(3), it.getString(4), it.getString(5))
        }
        return if (pk.initial.isNotEmpty() && pk.given.isNotEmpty()) rows.filter { WorldNorm.playerKey(it.name).initial.let { i -> i.isEmpty() || i == pk.initial } || WorldNorm.fold(it.fullName ?: "").startsWith(pk.initial) } else rows
    }

    internal fun setMapping(db: WorldSql, kind: String, key: String, slot: Int, entityId: Long?, status: String, score: Int, evidence: String, nObs: Int) {
        val now = System.currentTimeMillis()
        if ((db.long("SELECT COUNT(*) FROM football_osm_entity_mappings WHERE osm_kind=? AND osm_key=? AND slot=? AND algo='manual'", kind, key, slot.toString()) ?: 0L) > 0L) return   // confirmação manual nunca é sobrescrita pela automática
        val old = db.q("SELECT id,status,entity_id FROM football_osm_entity_mappings WHERE osm_kind=? AND osm_key=? AND slot=?", kind, key, slot.toString()) { Triple(it.getLong(0), it.getString(1), if (it.isNull(2)) null else it.getLong(2)) }.firstOrNull()
        if (old == null) {
            db.exec("INSERT INTO football_osm_entity_mappings(osm_kind,osm_key,slot,entity_type,entity_id,status,score,evidence,algo,observations,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf(kind, key, slot, kind, entityId, status, score, evidence, WorldIdentity.ALGO, nObs, now, now))
            db.exec("INSERT INTO football_validation_history(osm_kind,osm_key,slot,action,from_status,to_status,score,reason,at) VALUES(?,?,?,?,?,?,?,?,?)", arrayOf(kind, key, slot, "avaliado", null, status, score, evidence, now))
        } else {
            // confirmado manualmente/desfeito não é sobrescrito automaticamente por um escore pior
            if (old.second == "desfeito") return
            if (old.second != status || old.third != entityId) db.exec("INSERT INTO football_validation_history(mapping_id,osm_kind,osm_key,slot,action,from_status,to_status,score,reason,at) VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf(old.first, kind, key, slot, "reavaliado", old.second, status, score, evidence, now))
            db.exec("UPDATE football_osm_entity_mappings SET entity_id=?,status=?,score=?,evidence=?,algo=?,observations=?,updated_at=? WHERE id=?", arrayOf(entityId, status, score, evidence, WorldIdentity.ALGO, nObs, now, old.first))
        }
    }

    private fun statusName(s: WorldIdentity.Status) = s.label

    /** Dependências que só existem no aparelho (bandeira identificada, busca externa): injetadas para a avaliação poder ser testada no JVM com dados reais. */
    class Env(val natIso: (Int, String) -> String?, val enrich: (String) -> Boolean, val pullClub: (String) -> Boolean = { true }, val pullPlayer: (String) -> Boolean = { true })

    class Group(val kind: String, val norm: String, val slot: Int, val raw: String, val independent: Int, val context: JSONObject)

    internal fun pendingGroups(db: WorldSql, max: Int): List<Group> = db.q(
        """SELECT kind,norm,slot,MIN(raw),COUNT(DISTINCT capture_key),MAX(context) FROM football_ocr_observations
           WHERE status='novo' OR (kind||'|'||norm||'|'||slot) IN (SELECT osm_kind||'|'||osm_key||'|'||slot FROM football_osm_entity_mappings WHERE status NOT IN ('confirmado','desfeito') AND observations<>(SELECT COUNT(DISTINCT capture_key) FROM football_ocr_observations o WHERE o.kind=osm_kind AND o.norm=osm_key AND o.slot=football_osm_entity_mappings.slot))
           GROUP BY kind,norm,slot LIMIT $max""") { Group(it.getString(0)!!, it.getString(1)!!, it.getInt(2), it.getString(3)!!, it.getInt(4), runCatching { JSONObject(it.getString(5) ?: "{}") }.getOrDefault(JSONObject())) }

    /** Avalia as observações (local e rápido). Devolve quantos grupos foram avaliados. */
    fun evaluate(db: WorldSql, env: Env, groups: List<Group>): Int {
        var done = 0
        for (g in groups) {
            // candidatos vêm do Supabase (só o nome lido); sem rede/limite o grupo continua "novo" para a próxima rodada (não vira "desconhecido" por falta de candidatos)
            if (!runCatching { if (g.kind == KIND_CLUB) env.pullClub(g.raw) else env.pullPlayer(g.raw) }.getOrDefault(false)) continue
            done++
            runCatching { if (g.kind == KIND_CLUB) resolveClub(db, g.slot, g.raw, g.norm, g.independent, g.context) else resolvePlayer(db, env, g.slot, g.raw, g.norm, g.independent, g.context) }
            db.exec("UPDATE football_ocr_observations SET status='avaliado',algo=? WHERE kind=? AND norm=? AND slot=? AND status='novo'", arrayOf(WorldIdentity.ALGO, g.kind, g.norm, g.slot))
        }
        return done
    }

    fun resolvePending(ctx: Context, max: Int = 300): Int {
        val db = WorldDb.get(ctx); val sql = AndroidSql(db.w)
        val wanted = WorldRemote.cfg(ctx) != null
        val remote = wanted && WorldSync.networkOk(ctx)
        var pulls = 0
        val env = Env(
            natIso = { slot, key -> runCatching { natIso(ctx, slot, key) }.getOrNull() },
            pullClub = { name -> if (!remote) !wanted else if (pulls++ > 300) false else WorldRemote.pullClub(ctx, sql, name) },
            pullPlayer = { name -> if (!remote) !wanted else if (pulls++ > 300) false else WorldRemote.pullPlayer(ctx, sql, name) },
            enrich = { name -> !wanted && budget(ctx) && run { val hits = runCatching { WorldWikidata.searchPlayers(ctx, db, name) }.getOrNull().orEmpty(); hits.forEach { runCatching { WorldWikidata.savePlayer(db, WorldImport.Countries.index(sql), it) } }; hits.isNotEmpty() } }
        )
        val n = evaluate(sql, env, pendingGroups(sql, max))
        if (n > 0) UiBus.bump()
        return n
    }

    private fun resolveClub(db: WorldSql, slot: Int, raw: String, norm: String, independent: Int, ctx: JSONObject) {
        val league = ctx.optString("league").takeIf { it.isNotBlank() && it != "null" }
        val iso = league?.let { Flags.isoFromCompetition(it) }?.lowercase()
        val key = WorldNorm.clubKey(raw)
        val r = WorldIdentity.matchClub(WorldIdentity.ClubObs(raw, iso, league, independent), clubCandidates(db, raw, key))
        val ev = r.reasons.joinToString("; ") + " • margem ${r.margin} • $independent observação(ões) independente(s)"
        setMapping(db, KIND_CLUB, norm, slot, r.best?.id, statusName(r.status), r.score, ev, independent)
        if (r.status == WorldIdentity.Status.CONFIRMED && r.best != null) {
            db.exec("UPDATE football_clubs SET osm_seen=osm_seen+1,name_osm=COALESCE(name_osm,?) WHERE id=?", arrayOf(raw, r.best.id))
            db.exec("INSERT OR IGNORE INTO football_entity_aliases(entity_type,entity_id,alias,alias_key,kind,source_id) VALUES('club',?,?,?, 'osm','ocr')", arrayOf(r.best.id, raw, norm))
        }
        if (r.status == WorldIdentity.Status.UNRESOLVED) unresolved(db, KIND_CLUB, raw, norm, slot, ctx)
    }

    private fun resolvePlayer(db: WorldSql, env: Env, slot: Int, raw: String, norm: String, independent: Int, c: JSONObject) {
        val pk = WorldNorm.playerKey(raw)
        val age = c.optInt("age", -1).takeIf { it > 0 }
        val iso = env.natIso(slot, c.optString("nameKey"))
        val obs = WorldIdentity.PlayerObs(raw, age, nationality = iso?.let { i -> db.q("SELECT name_en FROM football_countries WHERE iso2=?", i) { it.getString(0) }.firstOrNull() }, position = c.optString("pos").takeIf { it.isNotBlank() && it != "null" }, independent = independent)
        var r = WorldIdentity.matchPlayer(obs, playerCandidates(db, pk))
        // sem candidato forte localmente: busca no Wikidata (assíncrona, limitada, sem bloquear o OCR) e reavalia
        if ((r.status == WorldIdentity.Status.UNRESOLVED || r.status == WorldIdentity.Status.AMBIGUOUS) && pk.given.isNotEmpty() && env.enrich(raw)) r = WorldIdentity.matchPlayer(obs, playerCandidates(db, pk))
        val ev = r.reasons.joinToString("; ") + " • margem ${r.margin} • $independent observação(ões) independente(s)"
        setMapping(db, KIND_PLAYER, norm, slot, r.best?.id, statusName(r.status), r.score, ev, independent)
        if (r.status == WorldIdentity.Status.CONFIRMED && r.best != null) {
            db.exec("UPDATE football_players SET osm_seen=osm_seen+1,validation=1,status=CASE WHEN status='desconhecido' THEN 'ativo' ELSE status END WHERE id=?", arrayOf(r.best.id))
            db.exec("INSERT OR IGNORE INTO football_entity_aliases(entity_type,entity_id,alias,alias_key,kind,source_id) VALUES('player',?,?,?, 'osm','ocr')", arrayOf(r.best.id, raw, norm))
        }
        if (r.status == WorldIdentity.Status.UNRESOLVED) unresolved(db, KIND_PLAYER, raw, norm, slot, c)
    }

    /** ISO da bandeira do jogador (a identificação do app, já conferida) — evidência independente do nome. */
    private fun natIso(ctx: Context, slot: Int, nameKey: String): String? {
        if (nameKey.isBlank()) return null
        val j = kotlinx.coroutines.runBlocking { Repo(ctx).dao.plan(slot, "natiso4")?.json } ?: return null
        return JSONObject(j).optString(nameKey).takeIf { it.isNotBlank() }
    }

    private fun unresolved(db: WorldSql, kind: String, raw: String, norm: String, slot: Int, attrs: JSONObject) {
        val now = System.currentTimeMillis()
        db.exec("INSERT INTO football_unresolved_entities(kind,name,norm,slot,attrs,first_seen,last_seen) VALUES(?,?,?,?,?,?,?) ON CONFLICT(kind,norm,slot) DO UPDATE SET last_seen=excluded.last_seen,seen_count=seen_count+1,attrs=excluded.attrs", arrayOf(kind, raw, norm, slot, attrs.toString(), now, now))
    }

    /** No máx. 12 buscas externas por dia para identidade (reserva pequena; não disputa cota com nada). */
    private fun budget(ctx: Context): Boolean = WorldDb.get(ctx).callsToday("wikidata_identity") < 12 && run { WorldDb.get(ctx).bumpUsage("wikidata_identity", 200); true }

    // ------------------------------------------------------------------ desfazer (sem perder observações)
    fun undo(ctx: Context, kind: String, key: String, slot: Int) {
        val db: WorldSql = AndroidSql(WorldDb.get(ctx).w); val now = System.currentTimeMillis()
        val old = db.q("SELECT id,status FROM football_osm_entity_mappings WHERE osm_kind=? AND osm_key=? AND slot=?", kind, key, slot.toString()) { Pair(it.getLong(0), it.getString(1)) }.firstOrNull() ?: return
        db.exec("UPDATE football_osm_entity_mappings SET status='desfeito',entity_id=NULL,updated_at=? WHERE id=?", arrayOf(now, old.first))
        db.exec("INSERT INTO football_validation_history(mapping_id,osm_kind,osm_key,slot,action,from_status,to_status,reason,at) VALUES(?,?,?,?,?,?,?,?,?)", arrayOf(old.first, kind, key, slot, "desfeito", old.second, "desfeito", "desfeito pelo usuário (as observações continuam guardadas)", now))
        UiBus.bump()
    }
    /** Deixa o motor reavaliar de novo (tira o "desfeito"). */
    fun reopen(ctx: Context, kind: String, key: String, slot: Int) {
        val db: WorldSql = AndroidSql(WorldDb.get(ctx).w)
        db.exec("DELETE FROM football_osm_entity_mappings WHERE osm_kind=? AND osm_key=? AND slot=? AND status='desfeito'", arrayOf(kind, key, slot))
        db.exec("UPDATE football_ocr_observations SET status='novo' WHERE kind=? AND norm=? AND slot=?", arrayOf(kind, key, slot))
        UiBus.bump()
    }

    /** Identidade real de um jogador (para a tela do jogador, busca de fotos e prompts): só se confirmada ou provável. */
    class RealPlayer(val name: String, val fullName: String?, val dob: String?, val nationality: String?, val iso: String?, val position: String?, val heightCm: Int?, val status: String, val score: Int, val wikidata: String?,
                     val apifId: String? = null, val tmId: String? = null, val reepId: String? = null)
    /** Busca a identificação do jogador no slot; se aquele slot ainda não o avaliou, vale a de outro slot (mesmo nome lido = mesmo jogador). */
    fun realPlayer(ctx: Context, slot: Int, playerName: String): RealPlayer? {
        val db: WorldSql = AndroidSql(WorldDb.get(ctx).readableDatabase); val k = WorldNorm.fold(playerName)
        return db.q(
            """SELECT p.name,p.full_name,p.dob,p.nationality,p.nationality_iso,p.position,p.height_cm,m.status,m.score,p.wikidata,p.apif_id,p.tm_id,p.reep_id
               FROM football_osm_entity_mappings m JOIN football_players p ON p.id=m.entity_id
               WHERE m.osm_kind='player' AND m.osm_key=? AND m.status IN ('confirmado','muito alta','provável')
               ORDER BY (m.slot=?) DESC, CASE m.status WHEN 'confirmado' THEN 0 WHEN 'muito alta' THEN 1 ELSE 2 END, m.score DESC LIMIT 1""", k, slot.toString()
        ) { RealPlayer(it.getString(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), if (it.isNull(6)) null else it.getInt(6), it.getString(7), it.getInt(8), it.getString(9), it.getString(10), it.getString(11), it.getString(12)) }.firstOrNull()
    }

    /** Nome REAL do clube lido pelo OCR (para achar escudo/foto com o nome certo quando o OCR errou uma letra). */
    fun realClubName(ctx: Context, osmName: String): String? = runCatching {
        val db: WorldSql = AndroidSql(WorldDb.get(ctx).readableDatabase)
        db.q("SELECT c.name FROM football_osm_entity_mappings m JOIN football_clubs c ON c.id=m.entity_id WHERE m.osm_kind='club' AND m.osm_key=? AND m.status IN ('confirmado','muito alta','provável') ORDER BY CASE m.status WHEN 'confirmado' THEN 0 ELSE 1 END, m.score DESC LIMIT 1", WorldNorm.fold(osmName)) { it.getString(0) }.firstOrNull()
    }.getOrNull()
}
