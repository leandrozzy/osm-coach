package com.osmaicoach.collector

import android.content.Context
import org.json.JSONObject

/**
 * Base Mundial — catálogo PARTINDO DO JOGO. As listas mostram tudo que o OCR já leu do OSM (jogadores, clubes), cada item com o cadastro REAL ligado quando identificado
 * (nome completo, nascimento, nacionalidade, posição, IDs, cidade, liga…). O que ainda não foi identificado aparece mesmo assim, marcado como pendente.
 */
object WorldCatalog {
    const val PAGE = 40
    /** Identificação do jogador SÓ do mesmo slot da observação: nome curto igual ("R. Dias") em outro slot é outra pessoa e não empresta a ficha. */
    private const val BEST_SLOT = "(SELECT m.entity_id || '|' || m.status || '|' || COALESCE(m.score,0) FROM football_osm_entity_mappings m WHERE m.osm_kind='player' AND m.osm_key=o.norm AND m.slot=o.slot AND m.entity_id IS NOT NULL AND m.status IN ('confirmado','muito alta','provável') ORDER BY CASE m.status WHEN 'confirmado' THEN 0 WHEN 'muito alta' THEN 1 ELSE 2 END, m.score DESC LIMIT 1)"
    private const val BEST = "(SELECT m.entity_id || '|' || m.status || '|' || COALESCE(m.score,0) FROM football_osm_entity_mappings m WHERE m.osm_kind='%K%' AND m.osm_key=o.norm AND m.entity_id IS NOT NULL AND m.status IN ('confirmado','muito alta','provável') ORDER BY CASE m.status WHEN 'confirmado' THEN 0 WHEN 'muito alta' THEN 1 ELSE 2 END, m.score DESC LIMIT 1)"

    class Counts(val playersSeen: Long, val playersReal: Long, val clubsSeen: Long, val clubsReal: Long, val countries: Long, val pending: Long, val leaguesWithClubs: Long)
    fun counts(db: WorldSql): Counts {
        fun one(sql: String) = db.long(sql) ?: 0L
        return Counts(
            one("SELECT COUNT(DISTINCT norm) FROM football_ocr_observations o WHERE kind='player' AND NOT EXISTS(SELECT 1 FROM football_ignored ig WHERE ig.kind=o.kind AND ig.norm=o.norm)"),
            one("SELECT COUNT(DISTINCT osm_key) FROM football_osm_entity_mappings WHERE osm_kind='player' AND status IN ('confirmado','muito alta','provável') AND entity_id IS NOT NULL"),
            one("SELECT COUNT(DISTINCT norm) FROM football_ocr_observations o WHERE kind='club' AND NOT EXISTS(SELECT 1 FROM football_ignored ig WHERE ig.kind=o.kind AND ig.norm=o.norm)"),
            one("SELECT COUNT(DISTINCT osm_key) FROM football_osm_entity_mappings WHERE osm_kind='club' AND status IN ('confirmado','muito alta','provável') AND entity_id IS NOT NULL"),
            one("SELECT COUNT(*) FROM football_countries WHERE osm_seen>0"),
            one("SELECT COUNT(DISTINCT osm_kind||'|'||osm_key) FROM football_osm_entity_mappings m WHERE status IN ('ambíguo','desconhecido') AND NOT EXISTS(SELECT 1 FROM football_ignored ig WHERE ig.kind=m.osm_kind AND ig.norm=m.osm_key)"),
            one("SELECT COUNT(DISTINCT league) FROM football_ocr_observations WHERE kind='club' AND league IS NOT NULL AND league<>'-'")
        )
    }

    class RealP(val id: Long, val name: String, val fullName: String?, val dob: String?, val nat: String?, val iso: String?, val pos: String?, val heightCm: Int?, val status: String, val score: Int,
                val wikidata: String?, val apifId: String?, val tmId: String?, val reepId: String?)
    class PlayerRow(val key: String, val raw: String, val age: Int?, val pos: String?, val slots: String, val real: RealP?, val nameKey: String? = null)

    private fun best(s: String?): Triple<Long, String, Int>? = s?.split('|')?.takeIf { it.size == 3 }?.let { Triple(it[0].toLong(), it[1], it[2].toIntOrNull() ?: 0) }

    fun realPlayer(db: WorldSql, id: Long, status: String, score: Int): RealP? = db.q("SELECT name,full_name,dob,nationality,nationality_iso,position,height_cm,wikidata,apif_id,tm_id,reep_id FROM football_players WHERE id=?", id.toString()) {
        RealP(id, it.getString(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), if (it.isNull(6)) null else it.getInt(6), status, score, it.getString(7), it.getString(8), it.getString(9), it.getString(10))
    }.firstOrNull()

    /** Ficha de UM jogador lido NO slot indicado (nome curto repetido em outros slots não entra). */
    fun playerIn(db: WorldSql, key: String, slot: Int?): PlayerRow? = players(db, "", slot, 0, 0, exactKey = key).firstOrNull()

    /** mode: 0 todos • 1 identificados • 2 pendentes. Um registro por (nome, slot): homônimos de slots diferentes aparecem separados. */
    fun players(db: WorldSql, q: String, slot: Int?, mode: Int, offset: Int, exactKey: String? = null): List<PlayerRow> {
        val w = arrayListOf("o.kind='player'", "NOT EXISTS(SELECT 1 FROM football_ignored ig WHERE ig.kind=o.kind AND ig.norm=o.norm)"); val a = ArrayList<String>()
        if (exactKey != null) { w.add("o.norm=?"); a.add(exactKey) }
        else if (q.isNotBlank()) { w.add("o.norm LIKE ?"); a.add("%" + WorldNorm.fold(q) + "%") }
        if (slot != null) { w.add("o.slot=?"); a.add(slot.toString()) }
        // um registro por (nome, slot); a junção entre slots e o filtro identificados/pendentes são feitos aqui (o SQLite do Android não tem JSON1)
        val rows = db.q("SELECT o.norm, MIN(o.raw) AS raw, o.slot, MAX(o.context), $BEST_SLOT AS best FROM football_ocr_observations o WHERE ${w.joinToString(" AND ")} GROUP BY o.norm, o.slot ORDER BY raw COLLATE NOCASE, o.slot", *a.toTypedArray()) {
            arrayOf(it.getString(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4))
        }
        class M(val key: String, val raw: String, val slots: MutableSet<String>, val ctx: JSONObject, val age: Int?, val pos: String?, var best: String?)
        val merged = ArrayList<M>()
        for (r in rows) {
            val c = runCatching { JSONObject(r[3] ?: "{}") }.getOrDefault(JSONObject())
            val age = c.optInt("age", -1).takeIf { it > 0 }
            val pos = c.optString("pos").takeIf { it.isNotBlank() && it != "null" }
            // mesmo nome em outro slot só se junta quando idade (e posição) lidas são IGUAIS nos dois; sem idade ou diferente = outra pessoa (homônimo), nunca empresta a ficha
            val m = merged.firstOrNull { o -> o.key == r[0] && o.age != null && o.age == age && (o.pos == null || pos == null || o.pos == pos) }
            if (m != null) { m.slots.add(r[2]!!); if (m.best == null) m.best = r[4] }
            else merged.add(M(r[0]!!, r[1]!!, sortedSetOf(r[2]!!), c, age, pos, r[4]))
        }
        val filtered = when (mode) { 1 -> merged.filter { it.best != null }; 2 -> merged.filter { it.best == null }; else -> merged }
        return filtered.drop(offset).take(PAGE).map { m ->
            val b = best(m.best)
            PlayerRow(m.key, m.raw, m.age, m.pos, m.slots.sorted().joinToString(","), b?.let { realPlayer(db, it.first, it.second, it.third) }, m.ctx.optString("nameKey").takeIf { it.isNotBlank() && it != "null" })
        }
    }

    class RealC(val id: Long, val name: String, val city: String?, val iso: String?, val country: String?, val league: String?, val stadium: String?, val founded: String?, val wikidata: String?, val logoUrl: String?, val status: String, val score: Int, val prominence: Int = 0)
    class ClubRow(val key: String, val raw: String, val slots: String, val role: String?, val league: String?, val real: RealC?)

    fun realClub(db: WorldSql, id: Long, status: String, score: Int): RealC? = db.q(
        """SELECT c.name,c.city,c.iso2,(SELECT COALESCE(k.name_pt,k.name_en) FROM football_countries k WHERE k.iso2=c.iso2),
                  (SELECT comp.name FROM football_competition_clubs x JOIN football_competitions comp ON comp.id=x.competition_id WHERE x.club_id=c.id ORDER BY comp.level LIMIT 1),c.stadium,c.founded,c.wikidata,c.logo_url,c.prominence FROM football_clubs c WHERE c.id=?""", id.toString()
    ) { RealC(id, it.getString(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getString(7), it.getString(8), status, score, it.getInt(9)) }.firstOrNull()

    fun clubs(db: WorldSql, q: String, slot: Int?, mode: Int, league: String?, offset: Int): List<ClubRow> {
        val w = arrayListOf("o.kind='club'", "NOT EXISTS(SELECT 1 FROM football_ignored ig WHERE ig.kind=o.kind AND ig.norm=o.norm)"); val a = ArrayList<String>()
        if (q.isNotBlank()) { w.add("o.norm LIKE ?"); a.add("%" + WorldNorm.fold(q) + "%") }
        if (slot != null) { w.add("o.slot=?"); a.add(slot.toString()) }
        if (league != null) { w.add("o.league=?"); a.add(league) }
        val having = when (mode) { 1 -> " HAVING best IS NOT NULL"; 2 -> " HAVING best IS NULL"; else -> "" }
        val rows = db.q("SELECT o.norm, MIN(o.raw) AS raw, GROUP_CONCAT(DISTINCT o.slot), MAX(o.context), ${BEST.replace("%K%", "club")} AS best FROM football_ocr_observations o WHERE ${w.joinToString(" AND ")} GROUP BY o.norm$having ORDER BY raw COLLATE NOCASE LIMIT $PAGE OFFSET $offset", *a.toTypedArray()) {
            arrayOf(it.getString(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4))
        }
        return rows.map { r ->
            val c = runCatching { JSONObject(r[3] ?: "{}") }.getOrDefault(JSONObject())
            val b = best(r[4])
            ClubRow(r[0]!!, r[1]!!, (r[2] ?: "").split(',').sorted().joinToString(","), c.optString("role").takeIf { it.isNotBlank() && it != "null" }, c.optString("league").takeIf { it.isNotBlank() && it != "null" }, b?.let { realClub(db, it.first, it.second, it.third) })
        }
    }

    /** Ligas lidas dos slots (nome como o jogo mostra) com quantos clubes já foram vistos em cada uma e a competição REAL mais votada pelos clubes identificados. */
    class LeagueRow(val name: String, val slots: String, val clubsSeen: Long, val clubsReal: Long, val real: String?, val realIso: String?, val realLevel: Int?)
    fun leagues(db: WorldSql): List<LeagueRow> {
        val rows = db.q("SELECT league, GROUP_CONCAT(DISTINCT slot), COUNT(DISTINCT norm) FROM football_ocr_observations WHERE kind='club' AND league IS NOT NULL AND league<>'null' AND league<>'' AND league<>'-' GROUP BY league ORDER BY league") { arrayOf(it.getString(0), it.getString(1), it.getString(2)) }
        return rows.map { r ->
            val lg = r[0]!!
            val real = db.q(
                """SELECT comp.name,comp.iso2,comp.level,COUNT(*) AS n FROM football_ocr_observations o
                   JOIN football_osm_entity_mappings m ON m.osm_kind='club' AND m.osm_key=o.norm AND m.status IN ('confirmado','muito alta','provável') AND m.entity_id IS NOT NULL
                   JOIN football_competition_clubs x ON x.club_id=m.entity_id JOIN football_competitions comp ON comp.id=x.competition_id
                   WHERE o.kind='club' AND o.league=? GROUP BY comp.id HAVING n>=2 ORDER BY n DESC LIMIT 1""", lg) { arrayOf(it.getString(0), it.getString(1), it.getString(2)) }.firstOrNull()
            val identified = db.long("SELECT COUNT(DISTINCT o.norm) FROM football_ocr_observations o WHERE o.kind='club' AND o.league=? AND EXISTS(SELECT 1 FROM football_osm_entity_mappings m WHERE m.osm_kind='club' AND m.osm_key=o.norm AND m.status IN ('confirmado','muito alta','provável') AND m.entity_id IS NOT NULL)", lg) ?: 0L
            LeagueRow(lg, (r[1] ?: "").split(',').sorted().joinToString(","), r[2]?.toLongOrNull() ?: 0L, identified, real?.get(0), real?.get(1), real?.get(2)?.toIntOrNull())
        }
    }

    class PlayerCand(val real: RealP, val score: Int, val reasons: List<String>, val age: Int?)
    class ClubCand(val real: RealC, val score: Int, val reasons: List<String>)

    /** Candidatos para o usuário escolher à mão, do mais parecido ao menos, com a idade, o país e a posição do JOGO como evidência (mostra o porquê de cada nota). */
    fun playerCandidates(db: WorldSql, text: String, gameAge: Int? = null, gameIso: String? = null, gamePos: String? = null): List<PlayerCand> {
        val pk = WorldNorm.playerKey(text); if (pk.surname.isEmpty()) return emptyList()
        val cands = WorldOcr.playerCandidates(db, pk)
        val nat = gameIso?.let { i -> db.q("SELECT name_en FROM football_countries WHERE iso2=?", i.lowercase()) { it.getString(0) }.firstOrNull() }
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        return WorldIdentity.rankPlayers(WorldIdentity.PlayerObs(text, gameAge, year, nat, gamePos), cands).take(15).mapNotNull { r -> realPlayer(db, r.c.id, "manual", r.score)?.let { PlayerCand(it, r.score, r.reasons, r.c.birthYear?.let { y -> year - y }) } }
    }
    fun clubCandidates(db: WorldSql, text: String, league: String? = null): List<ClubCand> {
        val key = WorldNorm.clubKey(text); if (key.core.length < 2) return emptyList()
        val cands = WorldOcr.clubCandidates(db, text, key)
        return WorldIdentity.rankClubs(WorldIdentity.ClubObs(text, null, league, 1), cands).take(15).mapNotNull { r -> realClub(db, r.c.id, "manual", r.score)?.let { ClubCand(it, r.score, r.reasons) } }
    }

    /** nameKey → ISO do país (bandeira lida pelo app) de todos os slots: o país que o JOGO mostra para cada jogador. */
    suspend fun gameNationalities(ctx: Context): Map<String, String> {
        val repo = Repo(ctx); val out = HashMap<String, String>()
        for (slot in 1..4) repo.dao.plan(slot, "natiso4")?.json?.let { j -> runCatching { JSONObject(j) }.getOrNull()?.let { o -> for (k in o.keys()) o.optString(k).takeIf { it.length == 2 }?.let { v -> out.putIfAbsent(k, v.lowercase()) } } }
        return out
    }

    /** Slots em que o texto lido [key] aparece (para a Validação perguntar qual quando há mais de um: homônimos não se confundem). */
    fun slotsOf(db: WorldSql, kind: String, key: String): List<Int> = db.q("SELECT DISTINCT slot FROM football_ocr_observations WHERE kind=? AND norm=? ORDER BY slot", kind, key) { it.getInt(0) }

    /**
     * Confirma à mão (vale mais que a automática e nunca é sobrescrita por ela) SÓ no [slot] escolhido: o mesmo nome curto em outro slot é outro registro e não muda.
     * Falha se o texto não existe nesse slot (contexto errado).
     */
    fun confirmManual(ctx: Context, kind: String, key: String, entityId: Long, slot: Int) =
        confirmManualIn(AndroidSql(WorldDb.get(ctx).w), kind, key, entityId, slot).also { UiBus.bump() }

    internal fun confirmManualIn(db: WorldSql, kind: String, key: String, entityId: Long, slot: Int) {
        val now = System.currentTimeMillis()
        require(slot in 0..4 && (slot == 0 || slot in slotsOf(db, kind, key))) { "o texto lido não existe neste slot" }
        db.begin()
        try {
            db.exec("DELETE FROM football_osm_entity_mappings WHERE osm_kind=? AND osm_key=? AND slot=?", arrayOf(kind, key, slot))
            db.exec("INSERT INTO football_osm_entity_mappings(osm_kind,osm_key,slot,entity_type,entity_id,status,score,evidence,algo,observations,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf(kind, key, slot, kind, entityId, "confirmado", 100, "escolha manual (slot $slot)", "manual", 1, now, now))
            db.exec("INSERT INTO football_validation_history(osm_kind,osm_key,slot,action,from_status,to_status,score,reason,at) VALUES(?,?,?,?,?,?,?,?,?)", arrayOf(kind, key, slot, "confirmado", null, "confirmado", 100, "escolha manual (slot $slot)", now))
            db.exec(if (kind == "club") "UPDATE football_clubs SET osm_seen=osm_seen+1 WHERE id=?" else "UPDATE football_players SET osm_seen=osm_seen+1,validation=1 WHERE id=?", arrayOf(entityId))
            db.commit()
        } catch (e: Exception) { runCatching { db.rollback() }; throw e }
    }

    /** Nomes que o app não conseguiu identificar sozinho (ambíguos/desconhecidos), um por nome, para a tela "Validação OCR". */
    class Pending(val kind: String, val key: String, val raw: String, val status: String, val evidence: String?, val slots: String)
    fun pending(db: WorldSql, offset: Int): List<Pending> = db.q(
        """SELECT m.osm_kind, m.osm_key, COALESCE((SELECT raw FROM football_ocr_observations o WHERE o.kind=m.osm_kind AND o.norm=m.osm_key LIMIT 1), m.osm_key) AS raw,
                  MAX(m.status), MAX(COALESCE(m.evidence,'')), GROUP_CONCAT(DISTINCT m.slot)
           FROM football_osm_entity_mappings m WHERE m.status IN ('ambíguo','desconhecido')
             AND NOT EXISTS(SELECT 1 FROM football_ignored ig WHERE ig.kind=m.osm_kind AND ig.norm=m.osm_key)
             AND NOT EXISTS(SELECT 1 FROM football_osm_entity_mappings z WHERE z.osm_kind=m.osm_kind AND z.osm_key=m.osm_key AND z.status IN ('confirmado','muito alta','provável'))
           GROUP BY m.osm_kind, m.osm_key ORDER BY raw COLLATE NOCASE LIMIT $PAGE OFFSET $offset""") { Pending(it.getString(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4), (it.getString(5) ?: "").split(',').sorted().joinToString(",")) }

    /** "Isto não existe" (ex.: texto de banner lido como clube): some das listas, para de ser avaliado e as próximas leituras do mesmo texto são descartadas. Reversível. */
    fun ignore(ctx: Context, kind: String, key: String, raw: String) {
        val db: WorldSql = AndroidSql(WorldDb.get(ctx).w)
        db.exec("INSERT OR REPLACE INTO football_ignored(kind,norm,raw,at) VALUES(?,?,?,?)", arrayOf(kind, key, raw, System.currentTimeMillis()))
        db.exec("UPDATE football_ocr_observations SET status='avaliado' WHERE kind=? AND norm=?", arrayOf(kind, key))
        UiBus.bump()
    }
    fun restore(ctx: Context, kind: String, key: String) {
        val db: WorldSql = AndroidSql(WorldDb.get(ctx).w)
        db.exec("DELETE FROM football_ignored WHERE kind=? AND norm=?", arrayOf(kind, key))
        db.exec("UPDATE football_ocr_observations SET status='novo' WHERE kind=? AND norm=?", arrayOf(kind, key))
        UiBus.bump()
    }
    class Ignored(val kind: String, val key: String, val raw: String)
    fun ignored(db: WorldSql, offset: Int): List<Ignored> = db.q("SELECT kind,norm,COALESCE(raw,norm) FROM football_ignored ORDER BY at DESC LIMIT $PAGE OFFSET $offset") { Ignored(it.getString(0), it.getString(1), it.getString(2)) }
    fun isIgnored(db: WorldSql, kind: String, norm: String): Boolean = (db.long("SELECT COUNT(*) FROM football_ignored WHERE kind=? AND norm=?", kind, norm) ?: 0L) > 0L
}
