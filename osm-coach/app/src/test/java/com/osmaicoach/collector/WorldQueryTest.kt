package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test

/** As consultas da interface rodando em SQLite de verdade sobre dados REAIS importados (amostras dos CSVs das fontes). */
class WorldQueryTest {
    private fun res(n: String) = javaClass.getResourceAsStream("/world/$n")!!
    private fun load(sql: JdbcSql, sink: WorldImport.Sink, file: String) {
        val csv = WorldCsv(res(file)); sink.header(WorldCsv.clean(csv.readRecord()!!)); val c = WorldImport.Counters(); sql.begin()
        while (true) { val r = csv.readRecord() ?: break; if (r.isNotEmpty()) sink.row(r, c) }; sql.commit()
    }
    private fun db(): JdbcSql {
        val sql = JdbcSql.fresh(); WorldImport.importCountries(sql, String(res("flag_codes.json").readBytes()))
        val ci = WorldImport.Countries.index(sql)
        load(sql, WorldImport.OfbClubsSink(sql, ci, "t"), "ofb_clubs_sample.csv"); load(sql, WorldImport.ReepPeopleSink(sql, ci, "t"), "reep_people_sample.csv")
        sql.exec("INSERT INTO football_confederations(code,name,continent) VALUES('UEFA','UEFA','Europa')")
        sql.exec("UPDATE football_countries SET confederation_id=(SELECT id FROM football_confederations WHERE code='UEFA') WHERE iso2 IN ('it','fr','de')")
        return sql
    }

    @Test fun countriesSearchAndConfederationFilter() {
        val sql = db()
        assertTrue(WorldQuery.countries(sql, "brasil", null, 0).any { it.iso == "br" })
        assertTrue(WorldQuery.countries(sql, "", "UEFA", 0).map { it.iso }.toSet() == setOf("it", "fr", "de"))
        assertTrue(WorldQuery.countries(sql, "", "-", 0).none { it.iso == "it" })
        assertEquals(WorldQuery.PAGE, WorldQuery.countries(sql, "", null, 0).size)
        assertNotEquals(WorldQuery.countries(sql, "", null, 0)[0].iso, WorldQuery.countries(sql, "", null, WorldQuery.PAGE)[0].iso)
    }

    @Test fun clubSearchIgnoresAccentsAndFiltersByCategory() {
        val sql = db()
        val all = WorldQuery.clubs(sql, "", null, null, null, 0); assertEquals(WorldQuery.PAGE, all.size)
        val inter = WorldQuery.clubs(sql, "INTER", null, null, null, 0); assertTrue(inter.any { it.wikidata == "Q631" })
        assertTrue(WorldQuery.clubs(sql, "", null, "feminino", null, 0).all { it.marker.contains("W") })
        assertTrue(WorldQuery.clubs(sql, "", null, "principal", null, 0).all { it.marker.isEmpty() })
        assertTrue(WorldQuery.clubs(sql, "", "it", null, null, 0).all { it.iso == "it" })
    }

    @Test fun playerSearchAndFilters() {
        val sql = db()
        val first = sql.rows("SELECT name,surname_key,nationality_iso,birth_year,position FROM football_players WHERE nationality_iso IS NOT NULL AND birth_year IS NOT NULL AND position IS NOT NULL LIMIT 1")[0]
        val byName = WorldQuery.players(sql, WorldQuery.PlayerFilter(q = first[0]!!), 0)
        assertTrue(byName.any { it.name == first[0] })
        val accentless = WorldQuery.players(sql, WorldQuery.PlayerFilter(q = WorldNorm.fold(first[0])), 0)
        assertTrue(accentless.any { it.name == first[0] })
        val nat = WorldQuery.players(sql, WorldQuery.PlayerFilter(nat = first[2]), 0); assertTrue(nat.isNotEmpty() && nat.all { it.iso == first[2] })
        val yr = first[3]!!.toInt()
        assertTrue(WorldQuery.players(sql, WorldQuery.PlayerFilter(q = first[0]!!, minYear = yr, maxYear = yr), 0).any { it.name == first[0] })
        assertTrue(WorldQuery.players(sql, WorldQuery.PlayerFilter(pos = first[4]), 0).all { it.pos?.lowercase()?.startsWith(first[4]!!.lowercase()) == true })
        assertTrue(WorldQuery.players(sql, WorldQuery.PlayerFilter(withPhoto = true), 0).isEmpty())   // nenhuma foto licenciada ainda
    }

    @Test fun globalSearchGroupsByType() {
        val sql = db(); val g = WorldQuery.global(sql, "brasil")
        assertTrue(g.countries.isNotEmpty())
    }

    @Test fun mappingsAndSourcesQueriesRun() {
        val sql = db(); WorldSources.ALL.forEach { s -> sql.exec("INSERT INTO football_sources(id,name,status) VALUES(?,?,?)", arrayOf(s.id, s.name, s.defaultStatus)) }
        sql.exec("INSERT INTO football_osm_entity_mappings(osm_kind,osm_key,slot,entity_type,status,score,created_at,updated_at) VALUES('club','x',1,'club','ambíguo',80,1,1)")
        assertEquals(1, WorldQuery.mappings(sql, listOf("ambíguo", "provável"), "club", 0).size)
        assertEquals(WorldSources.ALL.size, WorldQuery.sources(sql).size)
        assertTrue(WorldQuery.jobs(sql).isEmpty()); assertTrue(WorldQuery.history(sql, 0).isEmpty()); assertTrue(WorldQuery.continentCountries(sql).isNotEmpty())
    }
}
