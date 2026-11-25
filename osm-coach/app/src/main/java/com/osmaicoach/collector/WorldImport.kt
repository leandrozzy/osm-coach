package com.osmaicoach.collector

import android.content.Context
import org.json.JSONObject

/**
 * Base Mundial — adaptadores de importação (um por fonte). Todos: lotes (transação a cada 2.000 registros), checkpoint persistente em bytes, retomada com `Range`,
 * idempotentes (reimportar não duplica), ETag (arquivo inalterado = 304, nada baixado) e isolados (falha de uma fonte não derruba as outras).
 */
object WorldImport {
    const val BATCH = 2000

    class Counters { var analyzed = 0L; var inserted = 0L; var updated = 0L; var deduped = 0L; var failed = 0L }
    interface Sink { fun header(h: List<String>); fun row(r: List<String>, c: Counters); fun close() }

    // ------------------------------------------------------------------ países (índice nome → ISO)
    object Countries {
        /** Nomes que a Wikidata/Reep usam e a lista de bandeiras não (verificado contra os 571 valores distintos do Reep v0). Estados históricos (URSS, RDA, Iugoslávia…) ficam SEM ISO de propósito. */
        val SYN = mapOf(
            "czech republic" to "cz", "ivory coast" to "ci", "cote d ivoire" to "ci", "democratic republic of the congo" to "cd", "zaire" to "cd", "people s republic of china" to "cn", "kingdom of the netherlands" to "nl",
            "kingdom of denmark" to "dk", "the gambia" to "gm", "eswatini" to "sz", "swaziland" to "sz", "the bahamas" to "bs", "korea south" to "kr", "south korea" to "kr", "korea north" to "kp", "north korea" to "kp",
            "chinese taipei" to "tw", "republic of china" to "tw", "slovak republic" to "sk", "bosnia herzegovina" to "ba", "saint kitts" to "kn", "republic of egypt" to "eg", "state of malta" to "mt",
            "united kingdom of great britain and ireland" to "gb", "russian federation" to "ru", "turkiye" to "tr", "turkey" to "tr", "macedonia" to "mk", "north macedonia" to "mk", "cape verde" to "cv", "east timor" to "tl"
        )
        class Index(val byName: Map<String, String>) {
            fun iso(name: String?): String? { val k = WorldNorm.fold(name); return if (k.isEmpty()) null else SYN[k] ?: byName[k] }
        }
        fun index(db: WorldSql): Index {
            val m = HashMap<String, String>()
            for (r in db.rows("SELECT iso2,name_en,name_pt,name_osm,name_official FROM football_countries")) for (i in 1..4) r[i]?.let { n -> m[WorldNorm.fold(n)] = r[0]!! }
            return Index(m)
        }
    }

    // ------------------------------------------------------------------ utilitários
    private fun s(r: List<String>, i: Int): String? = if (i in r.indices) r[i].trim().takeIf { it.isNotEmpty() } else null
    private fun idx(h: List<String>) = h.withIndex().associate { it.value to it.index }
    private fun qid(v: String?): String? = v?.takeIf { Regex("Q\\d+").matches(it) }
    private fun year(d: String?): Int? = d?.take(4)?.toIntOrNull()?.takeIf { it in 1000..2100 }
    private fun WorldSql.id(sql: String, vararg a: String): Long? = long(sql, *a)

    private fun alias(db: WorldSql, type: String, id: Long, name: String?, kind: String, src: String) {
        val n = name?.trim()?.takeIf { it.length >= 2 } ?: return
        val k = WorldNorm.fold(n).takeIf { it.isNotEmpty() } ?: return
        db.exec("INSERT OR IGNORE INTO football_entity_aliases(entity_type,entity_id,alias,alias_key,kind,source_id) VALUES(?,?,?,?,?,?)", arrayOf(type, id, n, k, kind, src))
    }
    private fun extId(db: WorldSql, type: String, id: Long, provider: String, ext: String?, src: String) {
        val e = ext?.trim()?.takeIf { it.isNotEmpty() } ?: return
        db.exec("INSERT OR IGNORE INTO football_external_ids(entity_type,entity_id,provider,ext_id,source_id) VALUES(?,?,?,?,?)", arrayOf(type, id, provider, e, src))
    }
    private fun prov(db: WorldSql, type: String, id: Long, fields: List<String>, src: String, version: String, now: Long) {
        for (f in fields) db.exec("INSERT OR REPLACE INTO football_field_provenance(entity_type,entity_id,field,source_id,version,confidence,at) VALUES(?,?,?,?,?,?,?)", arrayOf(type, id, f, src, version, 90, now))
    }

    // ------------------------------------------------------------------ clubes (OpenFootballBase) — preenche países, clubes, ligas e vínculos
    class OfbClubsSink(private val db: WorldSql, private val ci: Countries.Index, private val version: String) : Sink {
        private var h: Map<String, Int> = emptyMap()
        private val now = System.currentTimeMillis()
        private val compCache = HashMap<String, Long>()
        override fun header(h: List<String>) { this.h = idx(h) }
        private fun g(r: List<String>, k: String) = s(r, h[k] ?: -1)
        override fun row(r: List<String>, c: Counters) {
            c.analyzed++
            val en = g(r, "nom_anglais"); val local = g(r, "nom_local"); val name = (en ?: local) ?: run { c.failed++; return }
            val wd = qid(g(r, "wikidata")); val iso = g(r, "pays_iso")?.lowercase()?.takeIf { it.length == 2 }
            val key = WorldNorm.clubKey(name)
            val cat = g(r, "categorie"); val women = cat?.contains("fémin", true) == true || cat?.contains("women", true) == true
            val marker = if (women && !key.marker.contains("W")) key.marker + "W" else key.marker
            val country = iso?.let { db.id("SELECT id FROM football_countries WHERE iso2=?", it) }
            var id = wd?.let { db.id("SELECT id FROM football_clubs WHERE wikidata=?", it) }
            val had = id != null
            if (id == null) id = db.id("SELECT id FROM football_clubs WHERE name_key=? AND marker=? AND COALESCE(iso2,'')=? AND wikidata IS NULL", key.core, marker, iso ?: "")
            if (id == null) {
                db.exec("INSERT INTO football_clubs(name,short_name,name_key,marker,country_id,iso2,city,founded,stadium,category,wikidata,logo_url,source_id,validation,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,0,?)",
                    arrayOf(name, g(r, "surnom"), key.core, marker, country, iso, g(r, "ville"), g(r, "date_creation"), g(r, "stade"), cat, wd, g(r, "url_logo"), WorldSources.OFB_CLUBS, now))
                id = db.id("SELECT last_insert_rowid()")!!; c.inserted++
            } else {
                db.exec("UPDATE football_clubs SET country_id=COALESCE(country_id,?),iso2=COALESCE(iso2,?),city=COALESCE(city,?),founded=COALESCE(founded,?),stadium=COALESCE(stadium,?),category=COALESCE(category,?),wikidata=COALESCE(wikidata,?),logo_url=COALESCE(logo_url,?),updated_at=? WHERE id=?",
                    arrayOf(country, iso, g(r, "ville"), g(r, "date_creation"), g(r, "stade"), cat, wd, g(r, "url_logo"), now, id))
                if (had) c.updated++ else c.deduped++
            }
            alias(db, "club", id, local, "local", WorldSources.OFB_CLUBS); alias(db, "club", id, en, "ingles", WorldSources.OFB_CLUBS); alias(db, "club", id, g(r, "surnom"), "apelido", WorldSources.OFB_CLUBS)
            extId(db, "club", id, "wikidata", wd, WorldSources.OFB_CLUBS); extId(db, "club", id, "transfermarkt", g(r, "transfermarkt_id"), WorldSources.OFB_CLUBS)
            extId(db, "club", id, "fbref", g(r, "fbref_id"), WorldSources.OFB_CLUBS); extId(db, "club", id, "soccerway", g(r, "soccerway_id"), WorldSources.OFB_CLUBS)
            prov(db, "club", id, listOf("name", "country", "city", "founded", "stadium", "logo_url"), WorldSources.OFB_CLUBS, version, now)
            // liga atual: competição por QID; o nível da divisão só entra se a fonte trouxer um número
            val lq = qid(g(r, "ligue_qid")); val ln = g(r, "ligue_actuelle")
            if (lq != null && ln != null) {
                val cid = compCache.getOrPut(lq) {
                    db.id("SELECT id FROM football_competitions WHERE wikidata=?", lq) ?: run {
                        val lvl = g(r, "division_actuel")?.toIntOrNull()?.takeIf { it in 1..12 }
                        db.exec("INSERT INTO football_competitions(name,name_key,country_id,iso2,level,kind,category,wikidata,source_id,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                            arrayOf(ln, WorldNorm.fold(ln), country, iso, lvl, "liga", cat, lq, WorldSources.OFB_CLUBS, now))
                        db.id("SELECT last_insert_rowid()")!!
                    }
                }
                db.exec("INSERT OR IGNORE INTO football_competition_clubs(competition_id,club_id,season,division,source_id) VALUES(?,?,?,?,?)", arrayOf(cid, id, "atual", g(r, "division_actuel"), WorldSources.OFB_CLUBS))
            }
        }
        override fun close() {}
    }

    // ------------------------------------------------------------------ clubes (Reep)
    class ReepTeamsSink(private val db: WorldSql, private val ci: Countries.Index, private val version: String) : Sink {
        private var h: Map<String, Int> = emptyMap(); private val now = System.currentTimeMillis()
        override fun header(h: List<String>) { this.h = idx(h) }
        private fun g(r: List<String>, k: String) = s(r, h[k] ?: -1)
        override fun row(r: List<String>, c: Counters) {
            c.analyzed++
            val name = g(r, "name") ?: run { c.failed++; return }
            val wd = qid(g(r, "key_wikidata")); val reep = g(r, "reep_id")
            val iso = ci.iso(g(r, "country")); val key = WorldNorm.clubKey(name)
            val country = iso?.let { db.id("SELECT id FROM football_countries WHERE iso2=?", it) }
            var id = wd?.let { db.id("SELECT id FROM football_clubs WHERE wikidata=?", it) } ?: reep?.let { db.id("SELECT entity_id FROM football_external_ids WHERE entity_type='club' AND provider='reep' AND ext_id=?", it) }
            val had = id != null
            if (id == null) {
                db.exec("INSERT INTO football_clubs(name,name_key,marker,country_id,iso2,founded,stadium,wikidata,source_id,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf(name, key.core, key.marker, country, iso, g(r, "founded"), g(r, "stadium"), wd, WorldSources.REEP_TEAMS, now))
                id = db.id("SELECT last_insert_rowid()")!!; c.inserted++
            } else { db.exec("UPDATE football_clubs SET country_id=COALESCE(country_id,?),iso2=COALESCE(iso2,?),founded=COALESCE(founded,?),stadium=COALESCE(stadium,?),wikidata=COALESCE(wikidata,?) WHERE id=?", arrayOf(country, iso, g(r, "founded"), g(r, "stadium"), wd, id)); if (had) c.deduped++ }
            alias(db, "club", id, name, "reep", WorldSources.REEP_TEAMS)
            extId(db, "club", id, "reep", reep, WorldSources.REEP_TEAMS); extId(db, "club", id, "wikidata", wd, WorldSources.REEP_TEAMS)
            for (p in listOf("transfermarkt", "fbref", "soccerway", "opta", "sofifa", "thesportsdb", "api_football", "flashscore", "sofascore", "uefa")) extId(db, "club", id, p, g(r, "key_$p"), WorldSources.REEP_TEAMS)
        }
        override fun close() {}
    }

    // ------------------------------------------------------------------ competições (Reep)
    class ReepCompetitionsSink(private val db: WorldSql, private val ci: Countries.Index, private val version: String) : Sink {
        private var h: Map<String, Int> = emptyMap(); private val now = System.currentTimeMillis()
        override fun header(h: List<String>) { this.h = idx(h) }
        private fun g(r: List<String>, k: String) = s(r, h[k] ?: -1)
        override fun row(r: List<String>, c: Counters) {
            c.analyzed++
            val name = g(r, "name") ?: run { c.failed++; return }
            val wd = qid(g(r, "key_wikidata")); val iso = ci.iso(g(r, "country"))
            val country = iso?.let { db.id("SELECT id FROM football_countries WHERE iso2=?", it) }
            var id = wd?.let { db.id("SELECT id FROM football_competitions WHERE wikidata=?", it) } ?: db.id("SELECT id FROM football_competitions WHERE name_key=? AND COALESCE(iso2,'')=?", WorldNorm.fold(name), iso ?: "")
            if (id == null) { db.exec("INSERT INTO football_competitions(name,name_key,country_id,iso2,wikidata,source_id,updated_at) VALUES(?,?,?,?,?,?,?)", arrayOf(name, WorldNorm.fold(name), country, iso, wd, WorldSources.REEP_COMP, now)); id = db.id("SELECT last_insert_rowid()")!!; c.inserted++ }
            else { db.exec("UPDATE football_competitions SET wikidata=COALESCE(wikidata,?),country_id=COALESCE(country_id,?),iso2=COALESCE(iso2,?) WHERE id=?", arrayOf(wd, country, iso, id)); c.deduped++ }
            extId(db, "competition", id, "reep", g(r, "reep_id"), WorldSources.REEP_COMP)
            for (p in listOf("transfermarkt", "fbref", "opta", "fotmob", "whoscored")) extId(db, "competition", id, p, g(r, "key_$p"), WorldSources.REEP_COMP)
        }
        override fun close() {}
    }

    // ------------------------------------------------------------------ jogadores (Reep) — o grande: 401 mil
    class ReepPeopleSink(private val db: WorldSql, private val ci: Countries.Index, private val version: String) : Sink {
        private var h: Map<String, Int> = emptyMap(); private val now = System.currentTimeMillis()
        override fun header(h: List<String>) { this.h = idx(h) }
        private fun g(r: List<String>, k: String) = s(r, h[k] ?: -1)
        override fun row(r: List<String>, c: Counters) {
            if (g(r, "type") != "player") return
            c.analyzed++
            val name = g(r, "name") ?: run { c.failed++; return }
            val reep = g(r, "reep_id") ?: run { c.failed++; return }
            val dob = g(r, "date_of_birth"); val nat = g(r, "nationality"); val k = WorldNorm.playerKey(name)
            val vals = arrayOf<Any?>(name, g(r, "full_name"), k.full, k.surname, k.initial, dob, year(dob), nat, ci.iso(nat), g(r, "position"), g(r, "height_cm")?.toDoubleOrNull()?.toInt(), qid(g(r, "key_wikidata")), reep, g(r, "key_transfermarkt"), g(r, "key_api_football"), WorldSources.REEP_PEOPLE, "desconhecido", now)
            val n = try { db.exec("INSERT OR IGNORE INTO football_players(name,full_name,name_key,surname_key,initial,dob,birth_year,nationality,nationality_iso,position,height_cm,wikidata,reep_id,tm_id,apif_id,source_id,status,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", vals) } catch (e: Exception) { 0 }
            if (n > 0) { c.inserted++; return }
            // já existe (reimportação ou mesmo Wikidata em outro Reep ID): completa campos vazios; conta como duplicado, nunca cria outro
            db.exec("UPDATE football_players SET full_name=COALESCE(?,full_name),dob=COALESCE(?,dob),birth_year=COALESCE(?,birth_year),nationality=COALESCE(?,nationality),nationality_iso=COALESCE(?,nationality_iso),position=COALESCE(?,position),height_cm=COALESCE(?,height_cm),tm_id=COALESCE(?,tm_id),apif_id=COALESCE(?,apif_id),updated_at=? WHERE reep_id=?",
                arrayOf(g(r, "full_name"), dob, year(dob), nat, ci.iso(nat), g(r, "position"), vals[10], g(r, "key_transfermarkt"), g(r, "key_api_football"), now, reep))
            c.deduped++
        }
        override fun close() {}
    }

    // ------------------------------------------------------------------ países (lista de bandeiras)
    fun importCountries(db: WorldSql, json: String): Counters {
        val c = Counters(); val o = JSONObject(json); val now = System.currentTimeMillis()
        db.begin()
        try {
            for (code in o.keys()) {
                c.analyzed++
                val name = o.optString(code).takeIf { it.isNotBlank() } ?: continue
                val iso = code.lowercase()
                val pt = if (iso.length == 2) runCatching { java.util.Locale("", iso.uppercase()).getDisplayCountry(java.util.Locale("pt", "BR")).takeIf { it.isNotBlank() && it != iso.uppercase() } }.getOrNull() else null
                val iso3 = if (iso.length == 2) runCatching { java.util.Locale("", iso.uppercase()).isO3Country }.getOrNull() else null
                val exists = db.long("SELECT id FROM football_countries WHERE iso2=?", iso) != null
                db.exec("INSERT INTO football_countries(iso2,iso3,name_en,name_pt,name_key,flag_url,source_id,validation,updated_at) VALUES(?,?,?,?,?,?,?,1,?) ON CONFLICT(iso2) DO UPDATE SET name_en=excluded.name_en,name_pt=COALESCE(excluded.name_pt,name_pt),name_key=excluded.name_key,flag_url=excluded.flag_url,updated_at=excluded.updated_at",
                    arrayOf(iso, iso3, name, pt, (WorldNorm.fold(name) + " " + WorldNorm.fold(pt)).trim(), "https://flagcdn.com/w160/$iso.png", WorldSources.FLAGS, now))
                if (exists) c.updated++ else c.inserted++
            }
            db.commit()
        } catch (e: Exception) { db.rollback(); throw e }
        return c
    }
}
