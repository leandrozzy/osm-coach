package com.osmaicoach.collector

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class WorldRound2Test {
    private fun obs(sql: JdbcSql, slot: Int, kind: String, raw: String, ctx: String, cap: String, league: String? = null) =
        sql.exec("INSERT OR IGNORE INTO football_ocr_observations(slot,screen,kind,raw,norm,context,capture_key,at,league) VALUES(?,?,?,?,?,?,?,?,?)", arrayOf(slot, "X", kind, raw, WorldNorm.fold(raw), ctx, cap, 1L, league))

    @Test fun atlasLeagueNamesWithOcrErrorsCollapseIntoOneLinePerDivision() {
        val names = listOf("22 Divisão A dos EUA", "29 Divisão B dos EUA", "2a Divisão A dos EUA", "2a Divisão B dos EUA", "2a DivisãoB dos EUA", "2ª Divisão A dos EUA", "2a Divisão da Noruega", "2a Divisão da índia", "2ª Divisão da índia",
            "Brasil", "Brasil, 2 Divisão", "Brasil, 2a Divisão", "Brasil, 2ª Divisão", "Ligas sugeridas xyz")
        val (g, rest) = LeagueNames.group(names) { WorldNorm.fold(it) == "brasil" }
        val by = g.associate { WorldNorm.fold(it.canon.countryText) + "|" + it.canon.tier + (it.canon.sub ?: "") to it.variants.size }
        assertEquals(mapOf("brasil|1" to 1, "brasil|2" to 3, "eua|2A" to 3, "eua|2B" to 3, "india|2" to 2, "noruega|2" to 1), by)
        assertEquals(listOf("Ligas sugeridas xyz"), rest)
        assertEquals("2ª divisão A", g.first { it.canon.sub == "A" }.canon.tierLabel)
    }

    @Test fun clubNameWithCityMatchesTheClubNotItsHomonyms() {
        val bh = WorldIdentity.ClubC(1, "Cruzeiro Esporte Clube", listOf("Cruzeiro"), "br", "", null, 9, "Belo Horizonte")
        val ar = WorldIdentity.ClubC(2, "Esporte Clube Cruzeiro (Arapiraca)", emptyList(), "br", "", null, 1, "Arapiraca")
        val r = WorldIdentity.matchClub(WorldIdentity.ClubObs("EC Cruzeiro Belo Horizonte", "br", null, 2), listOf(ar, bh))
        assertEquals(1L, r.best!!.id); assertTrue(r.reasons.any { it.contains("nome + cidade") }); assertNotEquals(WorldIdentity.Status.UNRESOLVED, r.status)
    }

    @Test fun candidatesComeFromWhatTheSearchReturnedEvenWithOcrNoiseInTheFirstWord() {
        val sql = JdbcSql.fresh()
        val arr = JSONArray("""[{"id":7,"name":"SC Fortuna Köln","iso2":"de","city":"Köln","aliases":["SC Fortuna Köln","Fortuna Köln"],"prominence":3}]""")
        WorldRemote.upsertClubs(sql, arr); WorldRemote.recordHits(sql, "club", WorldNorm.fold("5C Fortuna Köln"), arr)
        val c = WorldOcr.clubCandidates(sql, "5C Fortuna Köln", WorldNorm.clubKey("5C Fortuna Köln"))
        assertEquals(listOf("SC Fortuna Köln"), c.map { it.name })                          // antes: "sem candidatos"
        assertNotNull(WorldIdentity.matchClub(WorldIdentity.ClubObs("5C Fortuna Köln"), c).best)
    }

    @Test fun excludedNamesDisappearFromListsCountsAndPending() {
        val sql = JdbcSql.fresh()
        obs(sql, 3, "club", "25ANNYESAR", """{"role":"liga"}""", "A:1"); obs(sql, 3, "club", "SV Meppen", """{"role":"liga"}""", "A:1")
        sql.exec("INSERT INTO football_osm_entity_mappings(osm_kind,osm_key,slot,status,observations,created_at,updated_at) VALUES('club','25annyesar',3,'desconhecido',1,1,1)")
        assertEquals(1L, WorldCatalog.pending(sql, 0).size.toLong()); assertEquals(2, WorldCatalog.clubs(sql, "", null, 0, null, 0).size)
        sql.exec("INSERT INTO football_ignored(kind,norm,raw,at) VALUES('club','25annyesar','25ANNYESAR',1)")
        assertEquals(0, WorldCatalog.pending(sql, 0).size); assertEquals(listOf("SV Meppen"), WorldCatalog.clubs(sql, "", null, 0, null, 0).map { it.raw })
        assertEquals(1L, WorldCatalog.counts(sql).clubsSeen); assertTrue(WorldCatalog.isIgnored(sql, "club", "25annyesar"))
        assertEquals(1, WorldCatalog.ignored(sql, 0).size)
    }

    @Test fun playerCandidatesAreRankedByAgeCountryAndPositionOfTheGame() {
        val sql = JdbcSql.fresh()
        WorldRemote.upsertPlayers(sql, JSONArray("""[
          {"id":1,"reep_id":"a","name":"Altin Aliu","dob":"1999-11-11","birth_year":1999,"nationality":"Kosovo","nationality_iso":"xk","position":"midfielder"},
          {"id":2,"reep_id":"b","name":"Arber Aliu","dob":"1988-01-13","birth_year":1988,"nationality":"Albania","nationality_iso":"al","position":"defender"},
          {"id":3,"reep_id":"c","name":"Besart Aliu","dob":"1999-05-05","birth_year":1999,"nationality":"Albania","nationality_iso":"al","position":"forward"}]"""))
        sql.exec("INSERT INTO football_countries(iso2,name_en,name_key,source_id,validation,updated_at) VALUES('al','Albania','albania','t',1,1)")
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val r = WorldCatalog.playerCandidates(sql, "Aliu", year - 1999, "al", "EE")           // jogo: 27 anos, bandeira da Albânia
        assertEquals("Besart Aliu", r.first().real.name)                                      // idade E país batem; os outros ficam abaixo
        assertTrue(r.first().score > r.last().score); assertEquals(year - 1999, r.first().age)
        assertTrue(r.first().reasons.any { it.contains("idade compatível") } && r.first().reasons.any { it.contains("mesma nacionalidade") })
    }
}
