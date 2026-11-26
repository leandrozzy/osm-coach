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
