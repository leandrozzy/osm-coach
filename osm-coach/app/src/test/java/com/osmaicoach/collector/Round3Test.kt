package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test

/** Correções de 26/11 (prints/diagnóstico 25/11 22:xx). */
class Round3Test {
    @Test fun hoffenheimIIReadAsISnapsToTheClassificationName() {
        assertTrue(CrestAdmin.droppedStroke("TSG 1899 Hoffenheim ll", "TSG 1899 Hoffenheim I"))
        assertFalse(CrestAdmin.droppedStroke("VfB Stuttgart II", "VfB Stuttgart III"))
        assertFalse(CrestAdmin.droppedStroke("TSG 1899 Hoffenheim", "TSG 1899 Hoffenheim I"))
    }

    @Test fun fullNameFromTheBigCardIdentifiesEvenWithADifferentBirthCountry() {
        val fati = WorldIdentity.PlayerC(1, "Ansu Fati", "Anssumane Fati", emptyList(), 2002, "Guinea-Bissau", "wing half")
        val lamini = WorldIdentity.PlayerC(2, "Lamini Fati", null, emptyList(), 2006, "Spain", "centre-back")
        val obs = WorldIdentity.PlayerObs("Ansu Fati", ageNow = 24, year = 2026, nationality = "Spain", position = "EE")
        val r = WorldIdentity.matchPlayer(obs, listOf(fati, lamini))
        assertEquals(1L, r.best?.id)
        assertTrue("não pode ficar ambíguo: ${r.status} ${r.score}", r.status == WorldIdentity.Status.CONFIRMED || r.status == WorldIdentity.Status.VERY_HIGH)
    }

    @Test fun sameNameWithAnotherAgeStillHasTheNationalityPenalty() {
        val old = WorldIdentity.PlayerC(3, "Ansu Fati", null, emptyList(), 1990, "Guinea-Bissau", "forward")
        val r = WorldIdentity.matchPlayer(WorldIdentity.PlayerObs("Ansu Fati", ageNow = 24, year = 2026, nationality = "Spain", position = "EE"), listOf(old))
        assertTrue(r.status != WorldIdentity.Status.CONFIRMED)
    }
}

class Round3DedupeTest {
    @Test fun cutShortNameIsTheSamePlayer() {
        assertTrue(SquadDedupe.samePlayer("fat", 24, 82, "fati", 24, 82))
        assertFalse(SquadDedupe.samePlayer("fat", 24, 82, "fati", 24, 70))     // força bem diferente: outro jogador
        assertFalse(SquadDedupe.samePlayer("fat", 24, 82, "fatima", 24, 82))   // 3 letras só com 1 letra a mais
    }
}

class Round3IdentityTest {
    private val y = 2026
    @Test fun uniqueCandidateWithSameAgeAndCountryIsProbableEvenIfPositionDiffers() {      // Ledezma, 25/11
        val c = listOf(
            WorldIdentity.PlayerC(1, "Richard Ledezma", null, emptyList(), 2000, "Mexico", "midfielder"),
            WorldIdentity.PlayerC(2, "Luis Raquel Ledezma", null, emptyList(), 1959, null, null),
            WorldIdentity.PlayerC(3, "Juan Pablo Ledezma", null, emptyList(), 2005, "Costa Rica", null),
            WorldIdentity.PlayerC(4, "Iván Ledezma", null, emptyList(), 1995, "Chile", "midfielder"))
        val r = WorldIdentity.matchPlayer(WorldIdentity.PlayerObs("Ledezma", ageNow = 26, year = y, nationality = "Mexico", position = "DD"), c)
        assertEquals(1L, r.best?.id); assertTrue("${r.status} ${r.score}", r.score >= 90)
    }
    @Test fun pavardWithPortugueseDefenderCodeIsConfirmed() {
        val c = listOf(WorldIdentity.PlayerC(1, "Benjamin Pavard", null, emptyList(), 1996, "France", "full-back"))
        val r = WorldIdentity.matchPlayer(WorldIdentity.PlayerObs("Pavard", ageNow = 30, year = y, nationality = "France", position = "DO", independent = 2), c)
        assertTrue("${r.status} ${r.score}", r.score >= 95)
    }
    @Test fun twoCandidatesWithSameAgeAndCountryDoNotGetTheUniqueBonus() {
        val c = listOf(
            WorldIdentity.PlayerC(1, "Ana Silva", null, emptyList(), 2000, "Brazil", null), WorldIdentity.PlayerC(2, "Bia Silva", null, emptyList(), 2000, "Brazil", null))
        val r = WorldIdentity.matchPlayer(WorldIdentity.PlayerObs("Silva", ageNow = 26, year = y, nationality = "Brazil"), c)
        assertTrue(r.status != WorldIdentity.Status.CONFIRMED && r.status != WorldIdentity.Status.PROBABLE)
    }
}

class Round3CrestTest {
    @Test fun excludedSpellingOfARealClubStillShowsItsCrest() {
        val fd = java.nio.file.Files.createTempDirectory("crest").toFile()
        val dir = java.io.File(fd, "crests").apply { mkdirs() }
        java.io.File(dir, "blocked.json").writeText("""{"vfbstuttgartii":1,"25anniversary":1}""")
        assertTrue(CrestAdmin.isBlocked(fd, "VfB Stuttgart II"))                       // sem Classificação lida: continua excluído
        java.io.File(dir, "trusted.json").writeText("""{"VfB Stuttgart ll":1}""")        // a Classificação trouxe o clube (OCR leu "ll")
        assertFalse(CrestAdmin.isBlocked(fd, "VfB Stuttgart II"))
        assertTrue(CrestAdmin.isBlocked(fd, "25 anniversary"))                         // lixo de banner nunca volta
    }
}
