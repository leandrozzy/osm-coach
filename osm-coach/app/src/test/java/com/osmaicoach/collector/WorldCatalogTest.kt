package com.osmaicoach.collector

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class WorldCatalogTest {
    private fun obs(sql: JdbcSql, slot: Int, kind: String, raw: String, ctx: String, cap: String) =
        sql.exec("INSERT OR IGNORE INTO football_ocr_observations(slot,screen,kind,raw,norm,context,capture_key,at) VALUES(?,?,?,?,?,?,?,?)", arrayOf(slot, "X", kind, raw, WorldNorm.fold(raw), ctx, cap, 1L))

    @Test fun catalogStartsFromTheGameAndAttachesRealDataWhenIdentified() {
        val sql = JdbcSql.fresh()
        WorldRemote.upsertPlayers(sql, JSONArray("""[{"id":1,"reep_id":"r1","wikidata":"Q1","name":"Anton Ari Einarsson","full_name":"Anton Ari Einarsson","dob":"1994-08-25","birth_year":1994,"nationality":"Iceland","nationality_iso":"is","position":"goalkeeper","height_cm":190,"apif_id":"22238","tm_id":"42"}]"""))
        obs(sql, 3, "player", "A. Einarsson", """{"age":32,"pos":"GR","owner":"MINE"}""", "SQ:1"); obs(sql, 4, "player", "A. Einarsson", """{"age":32,"pos":"GR"}""", "SQ:2")
        obs(sql, 1, "player", "Zxqvw Utbk", """{"age":20,"pos":"ATA"}""", "SQ:1")
        val pid = sql.long("SELECT id FROM football_players WHERE remote_id=1")!!
        sql.exec("INSERT INTO football_osm_entity_mappings(osm_kind,osm_key,slot,entity_type,entity_id,status,score,created_at,updated_at) VALUES('player','a einarsson',3,'player',?,'confirmado',100,1,1)", arrayOf(pid))
        val all = WorldCatalog.players(sql, "", null, 0, 0)
        assertEquals(2, all.size)                                                              // os DOIS aparecem (identificado e pendente)
        val a = all.first { it.raw == "A. Einarsson" }
        assertEquals("3,4", a.slots); assertEquals(32, a.age); assertEquals("GR", a.pos)
        assertEquals("Anton Ari Einarsson", a.real!!.fullName); assertEquals("22238", a.real!!.apifId); assertEquals("is", a.real!!.iso)   // mesmo vendo o slot 4 sem mapa próprio
        assertNull(all.first { it.raw == "Zxqvw Utbk" }.real)
        assertEquals(1, WorldCatalog.players(sql, "", null, 1, 0).size); assertEquals(1, WorldCatalog.players(sql, "", null, 2, 0).size)
        assertEquals(1, WorldCatalog.players(sql, "", 4, 0, 0).size)                           // filtro por slot
        assertEquals(1, WorldCatalog.players(sql, "einarsson", null, 0, 0).size)
        val c = WorldCatalog.counts(sql); assertEquals(2L, c.playersSeen); assertEquals(1L, c.playersReal)
    }

    @Test fun leagueRealNameComesFromTheClubsTheGameShowedInIt() {
        val sql = JdbcSql.fresh()
        WorldRemote.upsertClubs(sql, JSONArray("""[
          {"id":11,"name":"Hansa Rostock","iso2":"de","comp_key":"wd:Q1","aliases":["Hansa Rostock"]},{"id":12,"name":"SV Meppen","iso2":"de","comp_key":"wd:Q1","aliases":["SV Meppen"]},
          {"id":13,"name":"MSV Duisburg","iso2":"de","comp_key":"wd:Q1","aliases":[]}]"""), { _ -> org.json.JSONObject("""{"id":500,"name":"3. Liga","iso2":"de","level":3,"kind":"liga","wikidata":"Q1"}""") })
        for ((i, n) in listOf("Hansa Rostock", "SV Meppen", "MSV Duisburg", "Clube Fantasma").withIndex()) {
            obs(sql, 1, "club", n, """{"role":"liga","league":"Alemanha 3ª Divisão"}""", "ST:1")
            val id = sql.long("SELECT id FROM football_clubs WHERE name=?", n)
            if (id != null) sql.exec("INSERT INTO football_osm_entity_mappings(osm_kind,osm_key,slot,entity_type,entity_id,status,score,created_at,updated_at) VALUES('club',?,1,'club',?,'confirmado',100,1,1)", arrayOf(WorldNorm.fold(n), id))
        }
        val l = WorldCatalog.leagues(sql).single()
        assertEquals("Alemanha 3ª Divisão", l.name); assertEquals(4L, l.clubsSeen); assertEquals(3L, l.clubsReal)
        assertEquals("3. Liga", l.real); assertEquals(3, l.realLevel)
        assertEquals(4, WorldCatalog.clubs(sql, "", null, 0, "Alemanha 3ª Divisão", 0).size)
        assertEquals("3. Liga", WorldCatalog.clubs(sql, "hansa", null, 1, null, 0).single().real!!.league)
    }

    @Test fun manualConfirmationWinsAndIsNotOverwrittenByTheEngine() {
        val sql = JdbcSql.fresh()
        WorldRemote.upsertPlayers(sql, JSONArray("""[{"id":1,"reep_id":"r1","name":"Anton Einarsson","name_fold":"x","dob":"1994-01-01","birth_year":1994,"nationality_iso":"is","position":"goalkeeper"},{"id":2,"reep_id":"r2","name":"Arnar Einarsson","dob":"2000-01-01","birth_year":2000,"nationality_iso":"is","position":"midfielder"}]"""))
        obs(sql, 3, "player", "A. Einarsson", """{"age":32,"pos":"GR"}""", "SQ:1")
        val p2 = sql.long("SELECT id FROM football_players WHERE remote_id=2")!!
        // simula a confirmação manual diretamente no SQL (o método usa Context): mesmas linhas
        sql.exec("INSERT INTO football_osm_entity_mappings(osm_kind,osm_key,slot,entity_type,entity_id,status,score,algo,observations,created_at,updated_at) VALUES('player','a einarsson',3,'player',?,'confirmado',100,'manual',1,1,1)", arrayOf(p2))
        WorldOcr.setMapping(sql, "player", "a einarsson", 3, sql.long("SELECT id FROM football_players WHERE remote_id=1"), "provável", 90, "auto", 2)
        val m = sql.rows("SELECT entity_id,status,algo FROM football_osm_entity_mappings WHERE osm_key='a einarsson'")[0]
        assertEquals(p2.toString(), m[0]); assertEquals("manual", m[2])
    }
}
