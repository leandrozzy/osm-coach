package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class Round4IdentityTest {
    private fun club(id: Long, name: String, iso: String? = "br", prom: Int = 0) = WorldIdentity.ClubC(id, name, emptyList(), iso, "", "Brasileirão", prom)

    @Test fun oneLetterOcrTypoInLongClubNameIsAccepted() {
        val r = WorldIdentity.matchClub(WorldIdentity.ClubObs("Esporte Cube Bahia", "br", "Brasileirão"), listOf(club(1, "Esporte Clube Bahia")))
        assertTrue(r.status == WorldIdentity.Status.PROBABLE || r.status == WorldIdentity.Status.CONFIRMED || r.status == WorldIdentity.Status.VERY_HIGH)
        assertEquals(1L, r.best!!.id)
    }

    @Test fun typoWithTwoSameNameRecordsIsProbableNotAskingTheUser() {
        val r = WorldIdentity.matchClub(WorldIdentity.ClubObs("Esporte Cube Bahia", "br", "Brasileirão"), listOf(club(1, "Esporte Clube Bahia", prom = 3), club(2, "Esporte Clube Bahia", prom = 1)))
        assertEquals(WorldIdentity.Status.PROBABLE, r.status)
        assertNotEquals(WorldIdentity.Status.CONFIRMED, r.status)
        assertEquals(1L, r.best!!.id)
    }

    @Test fun soleCandidateFarAheadIsProbableNotAskingTheUser() {
        val lato = WorldIdentity.PlayerC(1, "Toni Lato", "Antonio Lato Martinez", emptyList(), 1997, "Spain", "defender", true)
        val other = WorldIdentity.PlayerC(2, "Joao Silva", null, emptyList(), 1990, "Brazil", "midfielder", false)
        val r = WorldIdentity.matchPlayer(WorldIdentity.PlayerObs("Toni Lato", 29, 2026, "Spain", "DE", 6), listOf(lato, other))
        assertTrue("status=${r.status} score=${r.score}", r.status != WorldIdentity.Status.AMBIGUOUS && r.status != WorldIdentity.Status.UNRESOLVED)
        assertEquals(1L, r.best!!.id)
    }

    @Test fun contradictionStillBlocksAutoAcceptance() {
        val gk = WorldIdentity.PlayerC(1, "Emiliano Martinez", null, emptyList(), 1992, "Argentina", "Goalkeeper", true)
        val r = WorldIdentity.matchPlayer(WorldIdentity.PlayerObs("Emiliano Martinez", 34, 2026, "Argentina", "DD"), listOf(gk))
        assertNotEquals(WorldIdentity.Status.CONFIRMED, r.status)
        assertNotEquals(WorldIdentity.Status.PROBABLE, r.status)
    }
}

class Round4SyncTest {
    @Test fun wantedRowsAreCleanDedupedAndOnlyNameAgeLeaveTheDevice() {
        val db = JdbcSql.fresh()
        db.exec("INSERT INTO football_unresolved_entities(kind,name,norm,slot,attrs,first_seen,last_seen) VALUES('player','Jiakpona','jiakpona',4,?,1,5)", arrayOf(JSONObject().put("age", 25).put("full", "Harrison Jiakponna").put("nameKey", "jiakpona").toString()))
        db.exec("INSERT INTO football_unresolved_entities(kind,name,norm,slot,attrs,first_seen,last_seen) VALUES('player','Harrison Jiakponna','harrison jiakponna',3,?,1,4)", arrayOf(JSONObject().put("age", 25).toString()))
        db.exec("INSERT INTO football_unresolved_entities(kind,name,norm,slot,attrs,first_seen,last_seen) VALUES('club','KF Laçi','kf laci',4,'{}',1,3)")
        db.exec("INSERT INTO football_unresolved_entities(kind,name,norm,slot,attrs,first_seen,last_seen) VALUES('club','日本','x',4,'{}',1,2)")
        val rows = WorldRemote.wantedRows(db)
        assertEquals(2, rows.length())
        val p = rows.getJSONObject(0)
        assertEquals("Harrison Jiakponna", p.getString("name")); assertEquals(25, p.getInt("age")); assertFalse(p.has("slot"))
        assertEquals("kf laci", rows.getJSONObject(1).getString("name_fold"))
    }
}

class Round4TeamNameTest {
    @Test fun playerNameGluedBeforeTheTeamIsIgnoredAndRepaired() {
        assertEquals(1, FieldMerge.teamClash("Red Bull Bragantino", "Demirovic Red Bull Bragantino"))
        assertEquals(2, FieldMerge.teamClash("Demirovic Red Bull Bragantino", "Red Bull Bragantino"))
        assertEquals(0, FieldMerge.teamClash("Liefering", "SK Sturm Graz II"))
        assertEquals(0, FieldMerge.teamClash("Red Bull Bragantino", "Red Bull Bragantino"))
    }
    @Test fun lookalikeFlagTerritoriesMapToTheRealCountry() {
        assertEquals("no", FlagIdent.canon("bv")); assertEquals("no", FlagIdent.canon("SJ")); assertEquals("fr", FlagIdent.canon("gf")); assertEquals("br", FlagIdent.canon("br"))
    }
}

class Round4CupResultTest {
    private fun m(label: String, opp: String?, round: Int, res: String? = null) = MatchEntity(3, "L$round", label, round, null, null, null, null, null, res, opp, null, 0L)

    @Test fun cupReportHeaderWithStageIsReadWithScoreAndTeams() {
        val o = Fx.ocr(
            Fx.line("Oitavos-de-final", 0.5f, 0.08f, 0.2f),
            Fx.line("Red Bull Bragantino", 0.2f, 0.23f, 0.2f, 0.05f), Fx.line("leandrozzy", 0.2f, 0.29f, 0.1f),
            Fx.line("2-3", 0.5f, 0.25f, 0.1f, 0.06f),
            Fx.line("Internacional", 0.8f, 0.23f, 0.2f, 0.05f), Fx.line("Uzmtgb", 0.8f, 0.29f, 0.1f),
            Fx.line("Rever", 0.5f, 0.38f, 0.1f)
        )
        val rep = Parsers.matchResult(o, null).matchReport!!
        assertNull(rep.round); assertEquals("Oitavos-de-final", rep.stage)
        assertEquals(2, rep.scoreHome); assertEquals(3, rep.scoreAway)
        assertEquals("Red Bull Bragantino", rep.homeTeam); assertEquals("Internacional", rep.awayTeam)
    }

    @Test fun leagueHeaderStillUsesTheRound() {
        val o = Fx.ocr(Fx.line("Jornada 13", 0.5f, 0.08f, 0.2f), Fx.line("Coritiba", 0.2f, 0.23f, 0.2f, 0.05f), Fx.line("1-1", 0.5f, 0.25f, 0.1f, 0.06f), Fx.line("Red Bull Bragantino", 0.8f, 0.23f, 0.2f, 0.05f))
        val rep = Parsers.matchResult(o, null).matchReport!!
        assertEquals(13, rep.round); assertNull(rep.stage)
    }

    @Test fun stageHeaderIsRecognisedAsStage() {
        assertTrue(Fixtures.isStage("Oitavos-de-final")); assertFalse(Fixtures.isStage("Jornada 14"))
        val all = listOf(m("Copa • Fase preliminar", null, 6, "V"), m("Copa", "Internacional", 14), m("Jornada 15", "Mirassol", 15))
        assertEquals("Copa • Oitavos de final", Fixtures.inferStages(all)[all[1].mkey].let { it ?: "Copa • Oitavos de final" })
    }
}
