package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test

class WorldNormTest {
    @Test fun foldRemovesAccentsAndCase() {
        assertEquals("joao felix", WorldNorm.fold("João Félix"))
        assertEquals("kylian mbappe", WorldNorm.fold("Kylian Mbappé"))
        assertEquals("bayern munchen", WorldNorm.fold("Bayern München"))
        assertEquals("strasse", WorldNorm.fold("Straße"))
    }
    @Test fun clubKeysTreatKnownVariantsAsEqual() {
        assertEquals(WorldNorm.clubKey("Red Bull Bragantino").core, WorldNorm.clubKey("RB Bragantino").core)
        assertEquals(WorldNorm.clubKey("Manchester United").core, WorldNorm.clubKey("Man United").core)
        assertEquals(WorldNorm.clubKey("Bayern München").core, WorldNorm.clubKey("Bayern Munich").core)
        assertEquals(WorldNorm.clubKey("Manchester United").core, WorldNorm.clubKey("Man Utd").core)
    }
    @Test fun reserveTeamsKeepTheirMarker() {
        assertEquals("", WorldNorm.clubKey("TSG 1899 Hoffenheim").marker)
        assertEquals("II", WorldNorm.clubKey("TSG 1899 Hoffenheim II").marker)
        assertEquals("U19", WorldNorm.clubKey("Bayern München U19").marker)
        assertEquals("W", WorldNorm.clubKey("Arsenal Women").marker)
    }
    @Test fun playerKeysKeepSurnameAndInitial() {
        val k = WorldNorm.playerKey("A. Kurti"); assertEquals("kurti", k.surname); assertEquals("a", k.initial)
        val v = WorldNorm.playerKey("Virgil van Dijk"); assertEquals("van dijk", v.surname)
    }
    @Test fun similarityMetrics() {
        assertEquals(1, WorldNorm.levenshtein("kitten", "sitten"))
        assertTrue(WorldNorm.jaroWinkler("martha", "marhta") > 0.96)
        assertEquals(WorldNorm.phonetic("Mohamed"), WorldNorm.phonetic("Mohammed"))
        assertEquals(100, WorldNorm.nameSim("sao paulo", "sao paulo"))
    }
}

class WorldIdentityTest {
    private val hoff = WorldIdentity.ClubC(1, "TSG 1899 Hoffenheim", iso2 = "DE")
    private val hoff2 = WorldIdentity.ClubC(2, "TSG 1899 Hoffenheim II", marker = "II", iso2 = "DE")

    @Test fun neverMergesMainAndReserveTeam() {
        val r = WorldIdentity.matchClub(WorldIdentity.ClubObs("TSG 1899 Hoffenheim II", "DE"), listOf(hoff, hoff2))
        assertEquals(2L, r.best?.id)
        val r2 = WorldIdentity.matchClub(WorldIdentity.ClubObs("TSG 1899 Hoffenheim", "DE"), listOf(hoff2))
        assertTrue("só a reserva disponível: não confirma", r2.status != WorldIdentity.Status.CONFIRMED)
    }
    @Test fun sameNameDifferentCountryIsNotConfirmed() {
        val a = WorldIdentity.ClubC(10, "Nacional", iso2 = "PT"); val b = WorldIdentity.ClubC(11, "Nacional", iso2 = "UY")
        val r = WorldIdentity.matchClub(WorldIdentity.ClubObs("Nacional", "UY"), listOf(a, b))
        assertEquals(11L, r.best?.id)
        val none = WorldIdentity.matchClub(WorldIdentity.ClubObs("Nacional", "BR"), listOf(a, b))
        assertTrue(none.status != WorldIdentity.Status.CONFIRMED)
    }
    @Test fun clubConfirmedWithIndependentSignal() {
        val bra = WorldIdentity.ClubC(20, "Red Bull Bragantino", aliases = listOf("RB Bragantino"), iso2 = "BR", league = "Brasileirão Série A")
        val r = WorldIdentity.matchClub(WorldIdentity.ClubObs("RB Bragantino", "BR", "Brasileirao Serie A"), listOf(bra, WorldIdentity.ClubC(21, "Red Bull Salzburg", iso2 = "AT")))
        assertEquals(WorldIdentity.Status.CONFIRMED, r.status)
    }
    @Test fun surnameOnlyStaysAmbiguousWithManyCandidates() {
        val c = listOf(
            WorldIdentity.PlayerC(1, "Ismaïla Sarr", birthYear = 1998, nationality = "Senegal", position = "attacker"),
            WorldIdentity.PlayerC(2, "Abdoulaye Sarr", birthYear = 2001, nationality = "Senegal", position = "midfielder"),
            WorldIdentity.PlayerC(3, "Amath Sarr", birthYear = 1999, nationality = "France", position = "defender"))
        val r = WorldIdentity.matchPlayer(WorldIdentity.PlayerObs("Sarr"), c)
        assertTrue(r.status != WorldIdentity.Status.CONFIRMED)
        assertTrue(r.status != WorldIdentity.Status.VERY_HIGH)
    }
    @Test fun evidenceResolvesAFullNameWithAgeAndNationality() {
        val c = listOf(WorldIdentity.PlayerC(1, "Kylian Mbappé", birthYear = 1998, nationality = "France", position = "forward"), WorldIdentity.PlayerC(2, "Kylian Mbappé", birthYear = 1980, nationality = "Mali", position = "defender"))
        val r = WorldIdentity.matchPlayer(WorldIdentity.PlayerObs("Kylian Mbappe", ageNow = 27, year = 2026, nationality = "France", position = "ATA"), c)
        assertEquals(1L, r.best?.id)
        assertEquals(WorldIdentity.Status.CONFIRMED, r.status)
    }
    @Test fun abbreviatedNameMatchesWithInitial() {
        val c = listOf(WorldIdentity.PlayerC(1, "Arbër Kurti", birthYear = 2005, nationality = "Albania", position = "defender"))
        val r = WorldIdentity.matchPlayer(WorldIdentity.PlayerObs("A. Kurti", ageNow = 21, year = 2026, nationality = "Albania", position = "DC"), c)
        assertNotNull(r.best)
        assertTrue(r.score >= 90)
    }
    @Test fun contradictionsBlockConfirmation() {
        val c = listOf(WorldIdentity.PlayerC(1, "João Félix", birthYear = 1999, nationality = "Portugal", position = "forward"))
        val r = WorldIdentity.matchPlayer(WorldIdentity.PlayerObs("Joao Felix", ageNow = 35, year = 2026, nationality = "Brazil", position = "ATA"), c)
        assertTrue(r.status != WorldIdentity.Status.CONFIRMED)
    }
    @Test fun unknownNameIsUnresolved() {
        val r = WorldIdentity.matchPlayer(WorldIdentity.PlayerObs("Zzyzx Qwerty"), listOf(WorldIdentity.PlayerC(1, "Lionel Messi")))
        assertEquals(WorldIdentity.Status.UNRESOLVED, r.status)
        assertNull(r.best)
    }
}

class WorldClubTieBreakTest {
    private fun c(id: Long, name: String, prom: Int, iso: String = "br", aliases: List<String> = listOf("Flamengo")) = WorldIdentity.ClubC(id, name, aliases, iso, "", null, prom)
    @Test fun sameNameClubsDoNotGetConfirmedByLuck() {
        val americana = c(1, "Flamengo Futebol Clube (Americana)", 1); val rio = c(2, "Clube de Regatas do Flamengo", 12)
        val r = WorldIdentity.matchClub(WorldIdentity.ClubObs("Flamengo", "br", null, 2), listOf(americana, rio))
        assertEquals(2L, r.best!!.id)                                                                      // o mais conhecido vence o empate de nome
        assertTrue(r.reasons.any { it.contains("mais conhecido") })
        // dois homônimos igualmente obscuros: NÃO confirma (antes confirmava o primeiro da lista)
        val a = c(3, "Atlético Rio", 1); val b = c(4, "Atlético Rio", 2)
        val t = WorldIdentity.matchClub(WorldIdentity.ClubObs("Atlético Rio", "br", null, 2), listOf(a, b))
        assertNotEquals(WorldIdentity.Status.CONFIRMED, t.status)
        assertTrue(t.reasons.any { it.contains("sem favorito") })
    }
}

class WorldSurnameOnlyTest {
    private val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    @Test fun surnameWithAgeNationalityAndPositionIsEnoughWhenNoOneElseFits() {
        val dumfries = WorldIdentity.PlayerC(1, "Denzel Dumfries", "Denzel Dumfries", emptyList(), year - 30, "Netherlands", "defender")
        val other = WorldIdentity.PlayerC(2, "Rodney Dumfries", null, emptyList(), year - 41, "Suriname", "forward")
        val r = WorldIdentity.matchPlayer(WorldIdentity.PlayerObs("Dumfries", 30, year, "Netherlands", "DD"), listOf(other, dumfries))
        assertEquals(1L, r.best!!.id); assertEquals(WorldIdentity.Status.CONFIRMED, r.status)
        // só o sobrenome SEM as três evidências continua não confirmando
        val weak = WorldIdentity.matchPlayer(WorldIdentity.PlayerObs("Dumfries", null, year, null, null), listOf(dumfries))
        assertNotEquals(WorldIdentity.Status.CONFIRMED, weak.status)
        // dois jogadores que batem em tudo: continua ambíguo (nunca escolhe por sorte)
        val twin = WorldIdentity.PlayerC(3, "Dennis Dumfries", null, emptyList(), year - 30, "Netherlands", "defender")
        assertNotEquals(WorldIdentity.Status.CONFIRMED, WorldIdentity.matchPlayer(WorldIdentity.PlayerObs("Dumfries", 30, year, "Netherlands", "DD"), listOf(dumfries, twin)).status)
    }
}
