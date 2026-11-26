package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test

/** Fluxo OCR → observação → avaliação → mapeamento por slot, sobre jogadores e clubes REAIS importados (amostras das fontes). */
class WorldOcrTest {
    private fun res(n: String) = javaClass.getResourceAsStream("/world/$n")!!
    private fun load(sql: JdbcSql, sink: WorldImport.Sink, file: String) {
        val csv = WorldCsv(res(file)); sink.header(WorldCsv.clean(csv.readRecord()!!)); val c = WorldImport.Counters(); sql.begin()
        while (true) { val r = csv.readRecord() ?: break; if (r.isNotEmpty()) sink.row(r, c) }; sql.commit()
    }
    private fun db(): JdbcSql {
        val sql = JdbcSql.fresh(); WorldImport.importCountries(sql, String(res("flag_codes.json").readBytes()))
        val ci = WorldImport.Countries.index(sql)
        load(sql, WorldImport.OfbClubsSink(sql, ci, "t"), "ofb_clubs_sample.csv"); load(sql, WorldImport.ReepPeopleSink(sql, ci, "t"), "reep_people_sample.csv")
        return sql
    }
    private fun obs(sql: JdbcSql, slot: Int, kind: String, raw: String, ctx: String, capture: String) =
        sql.exec("INSERT OR IGNORE INTO football_ocr_observations(slot,screen,kind,raw,norm,context,capture_key,at) VALUES(?,?,?,?,?,?,?,?)", arrayOf(slot, "SQUAD", kind, raw, WorldNorm.fold(raw), ctx, capture, 1L))
    private val noEnv = WorldOcr.Env({ _, _ -> null }, { false })

    private fun status(sql: JdbcSql, kind: String, raw: String, slot: Int) = sql.rows("SELECT status,entity_id FROM football_osm_entity_mappings WHERE osm_kind=? AND osm_key=? AND slot=?", kind, WorldNorm.fold(raw), slot.toString()).firstOrNull()

    @Test fun playerWithAbbreviationAgeNationalityAndPositionIsConfirmed() {
        val sql = db()
        val p = sql.rows("SELECT id,name,surname_key,nationality_iso,birth_year,position FROM football_players p WHERE nationality_iso IS NOT NULL AND birth_year>1990 AND position IN ('defender','forward','midfielder','goalkeeper') AND length(surname_key)>5 AND name GLOB '[A-Z][a-z][a-z]* [A-Z]*' AND name NOT LIKE '%?%' AND (SELECT COUNT(*) FROM football_players q WHERE q.surname_key=p.surname_key)=1 LIMIT 1")[0]
        val code = mapOf("defender" to "DC", "forward" to "ATA", "midfielder" to "MCO", "goalkeeper" to "GL")[p[5]]!!
        val age = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - p[4]!!.toInt()
        val raw = WorldNorm.playerKey(p[1]).given.firstOrNull()?.take(1)?.uppercase().let { "$it. ${p[1]!!.split(' ').last()}" }
        val env = WorldOcr.Env({ _, _ -> p[3] }, { false })
        obs(sql, 1, "player", raw, """{"age":$age,"pos":"$code","nameKey":"k"}""", "SQUAD:1")
        assertEquals(1, WorldOcr.evaluate(sql, env, WorldOcr.pendingGroups(sql, 50)))
        WorldOcr.evaluate(sql, env, WorldOcr.pendingGroups(sql, 50))
        val m = status(sql, "player", raw, 1)!!
        assertEquals("confirmado", m[0]); assertEquals(p[0], m[1])
        assertEquals("1", sql.rows("SELECT osm_seen FROM football_players WHERE id=?", p[0]!!)[0][0])
        assertTrue(sql.long("SELECT COUNT(*) FROM football_validation_history")!! >= 1)
    }

    @Test fun surnameOnlyNeverConfirmsAndSlotsStayIsolated() {
        val sql = db()
        val sur = sql.rows("SELECT surname_key FROM football_players GROUP BY surname_key HAVING COUNT(*)>=3 ORDER BY COUNT(*) DESC LIMIT 1")[0][0]!!
        obs(sql, 1, "player", sur, """{"age":24,"pos":"MCO","nameKey":"k"}""", "SQUAD:1")
        obs(sql, 2, "player", sur, """{"age":24,"pos":"MCO","nameKey":"k"}""", "SQUAD:1")
        WorldOcr.evaluate(sql, noEnv, WorldOcr.pendingGroups(sql, 50))
        val s1 = status(sql, "player", sur, 1)!!; val s2 = status(sql, "player", sur, 2)!!
        assertNotEquals("confirmado", s1[0]); assertNotEquals("confirmado", s2[0])
        assertEquals("um mapeamento por slot", 2L, sql.long("SELECT COUNT(*) FROM football_osm_entity_mappings WHERE osm_key=?", WorldNorm.fold(sur)))
    }

    @Test fun clubNeedsIndependentEvidenceAndNeverMergesReserve() {
        val sql = db()
        val c = sql.rows("SELECT id,name FROM football_clubs c WHERE marker='' AND wikidata IS NOT NULL AND (SELECT COUNT(*) FROM football_clubs x WHERE x.name_key=c.name_key)=1 AND length(name)>8 LIMIT 1")[0]
        obs(sql, 3, "club", c[1]!!, """{"role":"adversario"}""", "CALENDAR:1")
        WorldOcr.evaluate(sql, noEnv, WorldOcr.pendingGroups(sql, 50))
        assertNotEquals("uma leitura só (sem outra pista) não confirma", "confirmado", status(sql, "club", c[1]!!, 3)!![0])
        obs(sql, 3, "club", c[1]!!, """{"role":"meu"}""", "PREGAME:1")          // 2ª evidência independente (outra tela)
        WorldOcr.evaluate(sql, noEnv, WorldOcr.pendingGroups(sql, 50))
        val m = status(sql, "club", c[1]!!, 3)!!
        assertEquals("confirmado", m[0]); assertEquals(c[0], m[1])
        // equipe II/reserva observada com o nome da principal + II nunca vira a principal
        obs(sql, 3, "club", c[1] + " II", """{"role":"adversario"}""", "CALENDAR:1"); obs(sql, 3, "club", c[1] + " II", """{"role":"meu"}""", "PREGAME:1")
        WorldOcr.evaluate(sql, noEnv, WorldOcr.pendingGroups(sql, 50))
        val r = status(sql, "club", c[1] + " II", 3)
        assertTrue("reserva não pode ser mapeada à principal", r == null || r[1] != c[0] || r[0] != "confirmado")
    }

    @Test fun nonsenseBecomesUnresolvedProvisionalRecordAndUndoKeepsObservations() {
        val sql = db()
        obs(sql, 1, "club", "Zxqvw Utbk", """{"role":"adversario"}""", "CALENDAR:1")
        WorldOcr.evaluate(sql, noEnv, WorldOcr.pendingGroups(sql, 50))
        assertEquals("desconhecido", status(sql, "club", "Zxqvw Utbk", 1)!![0])
        assertEquals(1L, sql.long("SELECT COUNT(*) FROM football_unresolved_entities WHERE norm=?", WorldNorm.fold("Zxqvw Utbk")))
        assertEquals(0L, sql.long("SELECT COUNT(*) FROM football_clubs WHERE name_key LIKE 'zxqvw%'"))      // não inventa clube real
        assertEquals(1L, sql.long("SELECT COUNT(*) FROM football_ocr_observations WHERE raw='Zxqvw Utbk'"))
    }
}
