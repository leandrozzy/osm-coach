package com.osmaicoach.collector

import android.content.Context
import org.json.JSONObject

/**
 * Base Mundial — catálogo PARTINDO DO JOGO. As listas mostram tudo que o OCR já leu do OSM (jogadores, clubes), cada item com o cadastro REAL ligado quando identificado
 * (nome completo, nascimento, nacionalidade, posição, IDs, cidade, liga…). O que ainda não foi identificado aparece mesmo assim, marcado como pendente.
 */
object WorldCatalog {
    const val PAGE = 40
    private const val BEST = "(SELECT m.entity_id || '|' || m.status || '|' || COALESCE(m.score,0) FROM football_osm_entity_mappings m WHERE m.osm_kind='%K%' AND m.osm_key=o.norm AND m.entity_id IS NOT NULL AND m.status IN ('confirmado','muito alta','provável') ORDER BY CASE m.status WHEN 'confirmado' THEN 0 WHEN 'muito alta' THEN 1 ELSE 2 END, m.score DESC LIMIT 1)"

    class Counts(val playersSeen: Long, val playersReal: Long, val clubsSeen: Long, val clubsReal: Long, val countries: Long, val pending: Long, val leaguesWithClubs: Long)
    fun counts(db: WorldSql): Counts {
        fun one(sql: String) = db.long(sql) ?: 0L
        return Counts(
            one("SELECT COUNT(DISTINCT norm) FROM football_ocr_observations WHERE kind='player'"),
            one("SELECT COUNT(DISTINCT osm_key) FROM football_osm_entity_mappings WHERE osm_kind='player' AND status IN ('confirmado','muito alta','provável') AND entity_id IS NOT NULL"),
            one("SELECT COUNT(DISTINCT norm) FROM football_ocr_observations WHERE kind='club'"),
            one("SELECT COUNT(DISTINCT osm_key) FROM football_osm_entity_mappings WHERE osm_kind='club' AND status IN ('confirmado','muito alta','provável') AND entity_id IS NOT NULL"),
            one("SELECT COUNT(*) FROM football_countries WHERE osm_seen>0"),
            one("SELECT COUNT(DISTINCT osm_kind||'|'||osm_key) FROM football_osm_entity_mappings WHERE status IN ('ambíguo','desconhecido')"),
            one("SELECT COUNT(DISTINCT json_extract(context,'$.league')) FROM football_ocr_observations WHERE kind='club' AND json_extract(context,'$.league') IS NOT NULL")
        )
    }

    class RealP(val id: Long, val name: String, val fullName: String?, val dob: String?, val nat: String?, val iso: String?, val pos: String?, val heightCm: Int?, val status: String, val score: Int,
                val wikidata: String?, val apifId: String?, val tmId: String?, val reepId: String?)
    class PlayerRow(val key: String, val raw: String, val age: Int?, val pos: String?, val slots: String, val real: RealP?, val nameKey: String? = null)

    private fun best(s: String?): Triple<Long, String, Int>? = s?.split('|')?.takeIf { it.size == 3 }?.let { Triple(it[0].toLong(), it[1], it[2].toIntOrNull() ?: 0) }

    fun realPlayer(db: WorldSql, id: Long, status: String, score: Int): RealP? = db.q("SELECT name,full_name,dob,nationality,nationality_iso,position,height_cm,wikidata,apif_id,tm_id,reep_id FROM football_players WHERE id=?", id.toString()) {
        RealP(id, it.getString(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), if (it.isNull(6)) null else it.getInt(6), status, score, it.getString(7), it.getString(8), it.getString(9), it.getString(10))
    }.firstOrNull()

    /** mode: 0 todos • 1 identificados • 2 pendentes. */
    fun players(db: WorldSql, q: String, slot: Int?, mode: Int, offset: Int): List<PlayerRow> {
        val w = arrayListOf("o.kind='player'"); val a = ArrayList<String>()
        if (q.isNotBlank()) { w.add("o.norm LIKE ?"); a.add("%" + WorldNorm.fold(q) + "%") }
        if (slot != null) { w.add("o.slot=?"); a.add(slot.toString()) }
        val having = when (mode) { 1 -> " HAVING best IS NOT NULL"; 2 -> " HAVING best IS NULL"; else -> "" }
        val rows = db.q("SELECT o.norm, MIN(o.raw) AS raw, GROUP_CONCAT(DISTINCT o.slot), MAX(o.context), ${BEST.replace("%K%", "player")} AS best FROM football_ocr_observations o WHERE ${w.joinToString(" AND ")} GROUP BY o.norm$having ORDER BY raw COLLATE NOCASE LIMIT $PAGE OFFSET $offset", *a.toTypedArray()) {
            arrayOf(it.getString(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4))
        }
        return rows.map { r ->
            val c = runCatching { JSONObject(r[3] ?: "{}") }.getOrDefault(JSONObject())
            val b = best(r[4])
            PlayerRow(r[0]!!, r[1]!!, c.optInt("age", -1).takeIf { it > 0 }, c.optString("pos").takeIf { it.isNotBlank() && it != "null" }, (r[2] ?: "").split(',').sorted().joinToString(","), b?.let { realPlayer(db, it.first, it.second, it.third) }, c.optString("nameKey").takeIf { it.isNotBlank() && it != "null" })
        }
    }

    class RealC(val id: Long, val name: String, val city: String?, val iso: String?, val country: String?, val league: String?, val stadium: String?, val founded: String?, val wikidata: String?, val logoUrl: String?, val status: String, val score: Int)
    class ClubRow(val key: String, val raw: String, val slots: String, val role: String?, val league: String?, val real: RealC?)

    fun realClub(db: WorldSql, id: Long, status: String, score: Int): RealC? = db.q(
        """SELECT c.name,c.city,c.iso2,(SELECT COALESCE(k.name_pt,k.name_en) FROM football_countries k WHERE k.iso2=c.iso2),
                  (SELECT comp.name FROM football_competition_clubs x JOIN football_competitions comp ON comp.id=x.competition_id WHERE x.club_id=c.id ORDER BY comp.level LIMIT 1),c.stadium,c.founded,c.wikidata,c.logo_url FROM football_clubs c WHERE c.id=?""", id.toString()
    ) { RealC(id, it.getString(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getString(7), it.getString(8), status, score) }.firstOrNull()

    fun clubs(db: WorldSql, q: String, slot: Int?, mode: Int, league: String?, offset: Int): List<ClubRow> {
        val w = arrayListOf("o.kind='club'"); val a = ArrayList<String>()
        if (q.isNotBlank()) { w.add("o.norm LIKE ?"); a.add("%" + WorldNorm.fold(q) + "%") }
        if (slot != null) { w.add("o.slot=?"); a.add(slot.toString()) }
        if (league != null) { w.add("json_extract(o.context,'$.league')=?"); a.add(league) }
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
        val rows = db.q("SELECT json_extract(context,'$.league') AS lg, GROUP_CONCAT(DISTINCT slot), COUNT(DISTINCT norm) FROM football_ocr_observations WHERE kind='club' AND lg IS NOT NULL AND lg<>'null' AND lg<>'' GROUP BY lg ORDER BY lg") { arrayOf(it.getString(0), it.getString(1), it.getString(2)) }
        return rows.map { r ->
            val lg = r[0]!!
            val real = db.q(
                """SELECT comp.name,comp.iso2,comp.level,COUNT(*) AS n FROM football_ocr_observations o
                   JOIN football_osm_entity_mappings m ON m.osm_kind='club' AND m.osm_key=o.norm AND m.status IN ('confirmado','muito alta','provável') AND m.entity_id IS NOT NULL
                   JOIN football_competition_clubs x ON x.club_id=m.entity_id JOIN football_competitions comp ON comp.id=x.competition_id
                   WHERE o.kind='club' AND json_extract(o.context,'$.league')=? GROUP BY comp.id HAVING n>=2 ORDER BY n DESC LIMIT 1""", lg) { arrayOf(it.getString(0), it.getString(1), it.getString(2)) }.firstOrNull()
            val identified = db.long("SELECT COUNT(DISTINCT o.norm) FROM football_ocr_observations o WHERE o.kind='club' AND json_extract(o.context,'$.league')=? AND EXISTS(SELECT 1 FROM football_osm_entity_mappings m WHERE m.osm_kind='club' AND m.osm_key=o.norm AND m.status IN ('confirmado','muito alta','provável') AND m.entity_id IS NOT NULL)", lg) ?: 0L
            LeagueRow(lg, (r[1] ?: "").split(',').sorted().joinToString(","), r[2]?.toLongOrNull() ?: 0L, identified, real?.get(0), real?.get(1), real?.get(2)?.toIntOrNull())
        }
    }

    /** Candidatos para o usuário escolher à mão (Validação OCR): depois de pedir ao Supabase, procura na cópia local. */
    fun playerCandidates(db: WorldSql, q: String): List<RealP> {
        val k = WorldNorm.playerKey(q); if (k.surname.isEmpty()) return emptyList()
        return db.q("SELECT id FROM football_players WHERE surname_key=? OR name_key=? OR name_key LIKE ? ORDER BY (name_key=?) DESC, name_key LIMIT 12", k.surname, k.full, k.full + "%", k.full) { it.getLong(0) }.mapNotNull { realPlayer(db, it, "manual", 0) }
    }
    fun clubCandidates(db: WorldSql, q: String): List<RealC> {
        val key = WorldNorm.clubKey(q); val f = WorldNorm.fold(q); if (f.length < 3) return emptyList()
        return db.q("SELECT DISTINCT c.id FROM football_clubs c LEFT JOIN football_entity_aliases a ON a.entity_type='club' AND a.entity_id=c.id WHERE c.name_key=? OR a.alias_key=? OR c.name_key LIKE ? ORDER BY (c.name_key=?) DESC, c.name LIMIT 12", key.core, f, key.core + "%", key.core) { it.getLong(0) }.mapNotNull { realClub(db, it, "manual", 0) }
    }

    /** Confirma à mão (vale mais que a automática e nunca é sobrescrita por ela). */
    fun confirmManual(ctx: Context, kind: String, key: String, entityId: Long) {
        val db: WorldSql = AndroidSql(WorldDb.get(ctx).w); val now = System.currentTimeMillis()
        val slots = db.q("SELECT DISTINCT slot FROM football_ocr_observations WHERE kind=? AND norm=?", kind, key) { it.getInt(0) }.ifEmpty { listOf(0) }
        db.begin()
        try {
            for (s in slots) {
                db.exec("DELETE FROM football_osm_entity_mappings WHERE osm_kind=? AND osm_key=? AND slot=?", arrayOf(kind, key, s))
                db.exec("INSERT INTO football_osm_entity_mappings(osm_kind,osm_key,slot,entity_type,entity_id,status,score,evidence,algo,observations,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf(kind, key, s, kind, entityId, "confirmado", 100, "confirmado por você", "manual", 1, now, now))
                db.exec("INSERT INTO football_validation_history(osm_kind,osm_key,slot,action,from_status,to_status,score,reason,at) VALUES(?,?,?,?,?,?,?,?,?)", arrayOf(kind, key, s, "confirmado", null, "confirmado", 100, "escolha manual", now))
            }
            db.exec(if (kind == "club") "UPDATE football_clubs SET osm_seen=osm_seen+1 WHERE id=?" else "UPDATE football_players SET osm_seen=osm_seen+1,validation=1 WHERE id=?", arrayOf(entityId))
            db.commit()
        } catch (e: Exception) { runCatching { db.rollback() }; throw e }
        UiBus.bump()
    }

    /** Nomes que o app não conseguiu identificar sozinho (ambíguos/desconhecidos), um por nome, para a tela "Validação OCR". */
    class Pending(val kind: String, val key: String, val raw: String, val status: String, val evidence: String?, val slots: String)
    fun pending(db: WorldSql, offset: Int): List<Pending> = db.q(
        """SELECT m.osm_kind, m.osm_key, COALESCE((SELECT raw FROM football_ocr_observations o WHERE o.kind=m.osm_kind AND o.norm=m.osm_key LIMIT 1), m.osm_key) AS raw,
                  MAX(m.status), MAX(COALESCE(m.evidence,'')), GROUP_CONCAT(DISTINCT m.slot)
           FROM football_osm_entity_mappings m WHERE m.status IN ('ambíguo','desconhecido')
             AND NOT EXISTS(SELECT 1 FROM football_osm_entity_mappings z WHERE z.osm_kind=m.osm_kind AND z.osm_key=m.osm_key AND z.status IN ('confirmado','muito alta','provável'))
           GROUP BY m.osm_kind, m.osm_key ORDER BY raw COLLATE NOCASE LIMIT $PAGE OFFSET $offset""") { Pending(it.getString(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4), (it.getString(5) ?: "").split(',').sorted().joinToString(",")) }
}
