package com.osmaicoach.collector

/** Base Mundial — consultas paginadas no SQLite (a base nunca é carregada inteira; cada tela pede 40 linhas por vez). Busca sem acento e tolerante (chave normalizada + prefixo). */
fun <T> WorldSql.q(sql: String, vararg args: String, map: (Cur) -> T): List<T> = rows(sql, *args).map { map(Cur(it)) }

object WorldQuery {
    const val PAGE = 40

    class CountryRow(val iso: String, val name: String, val confed: String?, val clubs: Long, val comps: Long, val players: Long)
    class CompRow(val id: Long, val name: String, val iso: String?, val country: String?, val level: Int?, val kind: String?, val clubs: Long, val osmSeen: Boolean)
    class ClubRow(val id: Long, val name: String, val iso: String?, val city: String?, val marker: String, val category: String?, val league: String?, val osmSeen: Boolean, val wikidata: String?)
    class PlayerRow(val id: Long, val name: String, val dob: String?, val nat: String?, val iso: String?, val pos: String?, val osmSeen: Boolean, val hasPhoto: Boolean)

    private fun like(q: String) = WorldNorm.fold(q).let { if (it.isEmpty()) "%" else "$it%" }
    private fun contains(q: String) = WorldNorm.fold(q).let { if (it.isEmpty()) "%" else "%$it%" }

    fun countries(db: WorldSql, q: String, confed: String?, offset: Int): List<CountryRow> {
        val args = ArrayList<String>(); val w = ArrayList<String>()
        if (q.isNotBlank()) { w.add("(c.name_key LIKE ? OR lower(c.iso2)=?)"); args.add(contains(q)); args.add(q.trim().lowercase()) }
        if (confed == "-") w.add("c.confederation_id IS NULL") else if (confed != null) { w.add("f.code=?"); args.add(confed) }
        return db.q("SELECT c.iso2,COALESCE(c.name_pt,c.name_en),f.code,(SELECT COUNT(*) FROM football_clubs x WHERE x.iso2=c.iso2),(SELECT COUNT(*) FROM football_competitions y WHERE y.iso2=c.iso2),(SELECT COUNT(*) FROM football_players p WHERE p.nationality_iso=c.iso2) FROM football_countries c LEFT JOIN football_confederations f ON f.id=c.confederation_id" +
            (if (w.isEmpty()) "" else " WHERE " + w.joinToString(" AND ")) + " ORDER BY 2 LIMIT $PAGE OFFSET $offset", *args.toTypedArray()) { CountryRow(it.getString(0), it.getString(1), it.getString(2), it.getLong(3), it.getLong(4), it.getLong(5)) }
    }

    fun competitions(db: WorldSql, q: String, iso: String?, offset: Int): List<CompRow> {
        val args = ArrayList<String>(); val w = ArrayList<String>()
        if (q.isNotBlank()) { w.add("c.name_key LIKE ?"); args.add(contains(q)) }
        if (iso != null) { w.add("c.iso2=?"); args.add(iso) }
        return db.q("SELECT c.id,c.name,c.iso2,COALESCE(k.name_pt,k.name_en),c.level,c.kind,(SELECT COUNT(*) FROM football_competition_clubs cc WHERE cc.competition_id=c.id),c.osm_seen FROM football_competitions c LEFT JOIN football_countries k ON k.iso2=c.iso2" +
            (if (w.isEmpty()) "" else " WHERE " + w.joinToString(" AND ")) + " ORDER BY 7 DESC, c.name LIMIT $PAGE OFFSET $offset", *args.toTypedArray()) { CompRow(it.getLong(0), it.getString(1), it.getString(2), it.getString(3), if (it.isNull(4)) null else it.getInt(4), it.getString(5), it.getLong(6), it.getInt(7) > 0) }
    }

    /** `category`: null = todos; "principal" | "reserva" | "feminino". */
    fun clubs(db: WorldSql, q: String, iso: String?, category: String?, competitionId: Long?, offset: Int): List<ClubRow> {
        val args = ArrayList<String>(); val w = ArrayList<String>(); var join = ""
        val key = WorldNorm.fold(q)
        if (key.isNotEmpty()) { w.add("(c.name_key LIKE ? OR c.id IN (SELECT entity_id FROM football_entity_aliases WHERE entity_type='club' AND alias_key LIKE ?))"); args.add(contains(q)); args.add("$key%") }
        if (iso != null) { w.add("c.iso2=?"); args.add(iso) }
        when (category) { "principal" -> w.add("c.marker=''"); "reserva" -> w.add("(c.marker<>'' AND c.marker NOT LIKE '%W%')"); "feminino" -> w.add("c.marker LIKE '%W%'") }
        if (competitionId != null) { join = " JOIN football_competition_clubs cc ON cc.club_id=c.id AND cc.competition_id=?"; args.add(0, competitionId.toString()) }
        return db.q("SELECT c.id,c.name,c.iso2,c.city,c.marker,c.category,(SELECT comp.name FROM football_competition_clubs x JOIN football_competitions comp ON comp.id=x.competition_id WHERE x.club_id=c.id LIMIT 1),c.osm_seen,c.wikidata FROM football_clubs c$join" +
            (if (w.isEmpty()) "" else " WHERE " + w.joinToString(" AND ")) + " ORDER BY c.osm_seen DESC, c.name LIMIT $PAGE OFFSET $offset", *args.toTypedArray()) { ClubRow(it.getLong(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4) ?: "", it.getString(5), it.getString(6), it.getInt(7) > 0, it.getString(8)) }
    }

    class PlayerFilter(val q: String = "", val nat: String? = null, val confed: String? = null, val pos: String? = null, val minYear: Int? = null, val maxYear: Int? = null, val withPhoto: Boolean = false, val validated: Boolean = false)
    fun players(db: WorldSql, f: PlayerFilter, offset: Int): List<PlayerRow> {
        val args = ArrayList<String>(); val w = ArrayList<String>(); val key = WorldNorm.playerKey(f.q)
        if (f.q.isNotBlank()) { w.add("(p.name_key LIKE ? OR p.surname_key=?)"); args.add(WorldNorm.fold(f.q) + "%"); args.add(key.surname.ifEmpty { WorldNorm.fold(f.q) }) }
        if (f.nat != null) { w.add("p.nationality_iso=?"); args.add(f.nat) }
        if (f.confed != null) { w.add("p.nationality_iso IN (SELECT c.iso2 FROM football_countries c JOIN football_confederations k ON k.id=c.confederation_id WHERE k.code=?)"); args.add(f.confed) }
        if (f.pos != null) { w.add("lower(p.position) LIKE ?"); args.add(f.pos.lowercase() + "%") }
        if (f.minYear != null) { w.add("p.birth_year>=?"); args.add(f.minYear.toString()) }
        if (f.maxYear != null) { w.add("p.birth_year<=?"); args.add(f.maxYear.toString()) }
        if (f.withPhoto) w.add("p.id IN (SELECT entity_id FROM football_images WHERE entity_type='player' AND kind='photo' AND status='ok')")
        if (f.validated) w.add("p.osm_seen>0")
        return db.q("SELECT p.id,p.name,p.dob,p.nationality,p.nationality_iso,p.position,p.osm_seen,(SELECT 1 FROM football_images i WHERE i.entity_type='player' AND i.entity_id=p.id AND i.status='ok' LIMIT 1) FROM football_players p" +
            (if (w.isEmpty()) "" else " WHERE " + w.joinToString(" AND ")) + " ORDER BY p.osm_seen DESC, p.name_key LIMIT $PAGE OFFSET $offset", *args.toTypedArray()) { PlayerRow(it.getLong(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getInt(6) > 0, !it.isNull(7)) }
    }

    /** Busca global: até 4 de cada tipo. */
    class Global(val countries: List<CountryRow>, val competitions: List<CompRow>, val clubs: List<ClubRow>, val players: List<PlayerRow>)
    fun global(db: WorldSql, q: String): Global = Global(countries(db, q, null, 0).take(4), competitions(db, q, null, 0).take(4), clubs(db, q, null, null, null, 0).take(4), players(db, PlayerFilter(q = q), 0).take(4))

    class Mapping(val kind: String, val key: String, val slot: Int, val raw: String, val entity: String?, val status: String, val score: Int, val evidence: String?, val at: Long, val observations: Int)
    /** Mapeamentos OSM↔real por status (lista de "Validação OCR"). */
    fun mappings(db: WorldSql, statuses: List<String>, kind: String?, offset: Int): List<Mapping> {
        val ph = statuses.joinToString(",") { "?" }
        val args = ArrayList<String>(statuses); if (kind != null) args.add(kind)
        return db.q(
            """SELECT m.osm_kind,m.osm_key,m.slot,COALESCE((SELECT raw FROM football_ocr_observations o WHERE o.kind=m.osm_kind AND o.norm=m.osm_key AND o.slot=m.slot LIMIT 1),m.osm_key),
               CASE m.osm_kind WHEN 'club' THEN (SELECT name FROM football_clubs c WHERE c.id=m.entity_id) ELSE (SELECT name FROM football_players p WHERE p.id=m.entity_id) END,
               m.status,COALESCE(m.score,0),m.evidence,m.updated_at,m.observations FROM football_osm_entity_mappings m WHERE m.status IN ($ph)""" + (if (kind != null) " AND m.osm_kind=?" else "") + " ORDER BY m.updated_at DESC LIMIT $PAGE OFFSET $offset", *args.toTypedArray()
        ) { Mapping(it.getString(0), it.getString(1), it.getInt(2), it.getString(3), it.getString(4), it.getString(5), it.getInt(6), it.getString(7), it.getLong(8), it.getInt(9)) }
    }
    class Unresolved(val kind: String, val name: String, val slot: Int, val seen: Int, val last: Long)
    fun unresolved(db: WorldSql, offset: Int): List<Unresolved> = db.q("SELECT kind,name,slot,seen_count,last_seen FROM football_unresolved_entities ORDER BY last_seen DESC LIMIT $PAGE OFFSET $offset") { Unresolved(it.getString(0), it.getString(1), it.getInt(2), it.getInt(3), it.getLong(4)) }
    class Hist(val action: String, val raw: String, val slot: Int, val from: String?, val to: String?, val score: Int?, val reason: String?, val at: Long)
    fun history(db: WorldSql, offset: Int): List<Hist> = db.q("SELECT action,osm_key,slot,from_status,to_status,score,reason,at FROM football_validation_history ORDER BY at DESC LIMIT $PAGE OFFSET $offset") { Hist(it.getString(0), it.getString(1), it.getInt(2), it.getString(3), it.getString(4), if (it.isNull(5)) null else it.getInt(5), it.getString(6), it.getLong(7)) }

    class Source(val id: String, val name: String, val site: String?, val docs: String?, val kind: String?, val license: String?, val status: String, val credential: String?, val quota: String?, val lastSync: Long?, val nextSync: Long?, val imported: Long, val updated: Long, val errors: Long, val version: String?, val categories: String?, val notes: String?, val verifiedAt: Long?, val callsToday: Long)
    fun sources(db: WorldSql): List<Source> = db.q("SELECT id,name,site,docs,kind,license,status,credential,quota,last_sync,next_sync,imported,updated,errors,version,categories,notes,verified_at,(SELECT COALESCE(SUM(calls),0) FROM football_api_usage u WHERE u.source_id=football_sources.id AND u.day=date('now','localtime')) FROM football_sources ORDER BY CASE status WHEN 'operacional' THEN 0 WHEN 'atualizada' THEN 0 WHEN 'sincronizando' THEN 1 WHEN 'aguardando configuração' THEN 2 ELSE 3 END, name") {
        Source(it.getString(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getString(7), it.getString(8), if (it.isNull(9)) null else it.getLong(9), if (it.isNull(10)) null else it.getLong(10), it.getLong(11), it.getLong(12), it.getLong(13), it.getString(14), it.getString(15), it.getString(16), if (it.isNull(17)) null else it.getLong(17), it.getLong(18))
    }
    class Job(val source: String, val kind: String, val status: String, val started: Long?, val finished: Long?, val analyzed: Long, val inserted: Long, val updated: Long, val deduped: Long, val failed: Long, val total: Long?, val message: String?)
    fun jobs(db: WorldSql, n: Int = 30): List<Job> = db.q("SELECT source_id,kind,status,started,finished,analyzed,inserted,updated,deduped,failed,total,message FROM football_sync_jobs ORDER BY id DESC LIMIT $n") {
        Job(it.getString(0), it.getString(1), it.getString(2), if (it.isNull(3)) null else it.getLong(3), if (it.isNull(4)) null else it.getLong(4), it.getLong(5), it.getLong(6), it.getLong(7), it.getLong(8), it.getLong(9), if (it.isNull(10)) null else it.getLong(10), it.getString(11)) }

    fun series(db: WorldSql, metric: String): List<Pair<Long, Long>> = db.q("SELECT at,value FROM football_coverage_metrics WHERE metric=? ORDER BY at LIMIT 60", metric) { Pair(it.getLong(0), it.getLong(1)) }
    fun originCounts(db: WorldSql, table: String): List<Pair<String, Long>> = db.q("SELECT COALESCE(source_id,'?'),COUNT(*) FROM $table GROUP BY 1 ORDER BY 2 DESC") { Pair(it.getString(0), it.getLong(1)) }
    fun continentCountries(db: WorldSql): List<Triple<String, Long, Long>> = db.q("SELECT COALESCE(k.continent,'Sem confederação'),COUNT(DISTINCT c.iso2),(SELECT COUNT(*) FROM football_clubs x WHERE x.iso2 IN (SELECT iso2 FROM football_countries z WHERE COALESCE((SELECT continent FROM football_confederations q WHERE q.id=z.confederation_id),'Sem confederação')=COALESCE(k.continent,'Sem confederação'))) FROM football_countries c LEFT JOIN football_confederations k ON k.id=c.confederation_id GROUP BY 1 ORDER BY 3 DESC") { Triple(it.getString(0), it.getLong(1), it.getLong(2)) }
}
