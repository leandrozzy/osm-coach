package com.osmaicoach.collector

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** Cópia local a partir de respostas no formato do Supabase (wf_match_clubs / wf_match_players) e identificação em cima dela. */
class WorldRemoteTest {
    private val clubsJson = """[
      {"id":101,"key":"wd:Q104761","wikidata":"Q104761","name":"TSG 1899 Hoffenheim","short_name":"Hoffenheim","name_fold":"tsg 1899 hoffenheim","marker":"","iso2":"de","city":"Sinsheim","founded":"1899","stadium":"PreZero Arena","category":"men","logo_url":null,"comp_key":null,"division":"1","aliases":["TSG 1899 Hoffenheim","Hoffenheim"],"alias_fold":["hoffenheim","tsg 1899 hoffenheim"]},
      {"id":102,"key":"wd:Q1234567","wikidata":"Q1234567","name":"TSG 1899 Hoffenheim II","short_name":null,"name_fold":"tsg 1899 hoffenheim ii","marker":"II","iso2":"de","city":"Zuzenhausen","founded":null,"stadium":null,"category":null,"logo_url":null,"comp_key":null,"division":null,"aliases":[],"alias_fold":["tsg 1899 hoffenheim ii"]},
      {"id":103,"key":"rp:x1","wikidata":null,"name":"Arsenal Women","name_fold":"arsenal women","marker":"W","iso2":"gb"},
      {"id":104,"key":"rp:x2","wikidata":null,"name":"Спартак Москва","name_fold":"","marker":"","iso2":"ru"}
    ]"""
    private val playersJson = """[
      {"id":9001,"reep_id":"r1","wikidata":"Q15","name":"Arbër Kurti","full_name":"Arbër Kurti","name_fold":"arber kurti","tail1":"kurti","tail2":"kurti","dob":"2001-03-04","birth_year":2001,"nationality":"Albania","nationality_iso":"al","position":"defender","height_cm":184,"tm_id":null,"apif_id":null},
      {"id":9002,"reep_id":"r2","wikidata":"Q15","name":"Kurti Duplicate QID","name_fold":"kurti duplicate qid","tail1":"qid","tail2":"duplicate qid","dob":null,"birth_year":null,"nationality":null,"nationality_iso":null,"position":null,"height_cm":null}
    ]"""

    @Test fun clubsAreCachedCleanedAndIdempotent() {
        val sql = JdbcSql.fresh()
        assertEquals(2, WorldRemote.upsertClubs(sql, JSONArray(clubsJson)))                       // feminino e não latino ficam de fora
        assertEquals(2L, sql.long("SELECT COUNT(*) FROM football_clubs"))
        assertEquals(2, WorldRemote.upsertClubs(sql, JSONArray(clubsJson)))                       // de novo: mesma coisa, sem duplicar
        assertEquals(2L, sql.long("SELECT COUNT(*) FROM football_clubs"))
        assertEquals("II", sql.rows("SELECT marker FROM football_clubs WHERE remote_id=102")[0][0])
        assertEquals(1L, sql.long("SELECT COUNT(*) FROM football_entity_aliases WHERE alias_key='hoffenheim'"))
        // alteração no Supabase chega; o que o OSM marcou localmente não é apagado
        sql.exec("UPDATE football_clubs SET osm_seen=3, name_osm='Hoffenheim OCR' WHERE remote_id=101")
        WorldRemote.upsertClubs(sql, JSONArray(clubsJson.replace("PreZero Arena", "Rhein-Neckar-Arena")))
        assertEquals("Rhein-Neckar-Arena", sql.rows("SELECT stadium FROM football_clubs WHERE remote_id=101")[0][0])
        assertEquals("3", sql.rows("SELECT osm_seen FROM football_clubs WHERE remote_id=101")[0][0]); assertEquals("Hoffenheim OCR", sql.rows("SELECT name_osm FROM football_clubs WHERE remote_id=101")[0][0])
    }

    @Test fun playersKeepRemoteIdAndSurviveRepeatedWikidata() {
        val sql = JdbcSql.fresh()
        assertEquals(2, WorldRemote.upsertPlayers(sql, JSONArray(playersJson)))
        assertEquals(2L, sql.long("SELECT COUNT(*) FROM football_players"))
        assertEquals(2, WorldRemote.upsertPlayers(sql, JSONArray(playersJson)))
        assertEquals(2L, sql.long("SELECT COUNT(*) FROM football_players"))
        assertEquals("kurti", sql.rows("SELECT surname_key FROM football_players WHERE remote_id=9001")[0][0])
    }

    @Test fun ocrNamesAreIdentifiedFromRemoteCandidatesOnly() {
        val sql = JdbcSql.fresh()
        fun obs(kind: String, raw: String, ctx: String, cap: String) = sql.exec("INSERT OR IGNORE INTO football_ocr_observations(slot,screen,kind,raw,norm,context,capture_key,at) VALUES(?,?,?,?,?,?,?,?)", arrayOf(1, "X", kind, raw, WorldNorm.fold(raw), ctx, cap, 1L))
        obs("club", "TSG 1899 Hoffenheim", """{"role":"adversario","league":"x"}""", "CAL:1"); obs("club", "TSG 1899 Hoffenheim", """{"role":"meu"}""", "PRE:1")
        obs("club", "Zxqvw Utbk", """{"role":"adversario"}""", "CAL:1")
        var offline = true
        val pulled = ArrayList<String>()
        val env = WorldOcr.Env({ _, _ -> null }, { false }, pullClub = { n -> pulled.add(n); if (offline) false else { WorldRemote.upsertClubs(sql, JSONArray(clubsJson)); true } })
        // sem rede: nada é avaliado e nada vira "desconhecido"
        assertEquals(0, WorldOcr.evaluate(sql, env, WorldOcr.pendingGroups(sql, 50)))
        assertEquals(0L, sql.long("SELECT COUNT(*) FROM football_osm_entity_mappings"))
        offline = false
        assertEquals(2, WorldOcr.evaluate(sql, env, WorldOcr.pendingGroups(sql, 50)))
        val m = sql.rows("SELECT status,entity_id FROM football_osm_entity_mappings WHERE osm_key=?", "tsg 1899 hoffenheim")[0]
        assertEquals("confirmado", m[0])
        assertEquals("101", sql.rows("SELECT remote_id FROM football_clubs WHERE id=?", m[1]!!)[0][0])       // a principal, não a II
        assertEquals("desconhecido", sql.rows("SELECT status FROM football_osm_entity_mappings WHERE osm_key=?", "zxqvw utbk")[0][0])
    }

    @Test fun configAcceptsOnlyHttpsSupabaseHostAndRealisticKey() {
        val k = "x".repeat(40)
        assertNotNull(WorldRemote.valid("https://abcd1234.supabase.co", k))
        for (bad in listOf("http://abcd.supabase.co", "https://evil.com", "https://supabase.co", "https://abcd.supabase.co.evil.com", "https://user:pw@abcd.supabase.co", "https://abcd.supabase.co/rest", "https://127.0.0.1", ""))
            assertNull(bad, WorldRemote.valid(bad, k))
        assertNull(WorldRemote.valid("https://abcd.supabase.co", "curta"))
    }

    @Test fun newStyleKeysGoOnlyInApikeyHeader() {
        val n = WorldRemote.headersFor("sb_publishable_" + "a".repeat(30)); assertEquals(setOf("apikey", "Accept"), n.keys)
        val l = WorldRemote.headersFor("eyJ" + "a".repeat(40)); assertTrue(l.containsKey("Authorization"))
    }
}
