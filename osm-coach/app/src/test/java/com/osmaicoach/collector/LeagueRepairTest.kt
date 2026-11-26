package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test

/** Linhas da Classificação que o diagnóstico de 25/11 mostrou descartadas por faltar uma coluna do OCR. */
class LeagueRepairTest {
    @Test fun missingMiddleColumnIsRebuiltOnlyWhenTheSolutionIsUnique() {
        assertEquals(listOf(21, 7, 4, 10, 25), League.repairStats(listOf(21, 7, 10, 25)))      // SV Austria Salzburg (E não lido)
        assertEquals(listOf(35, 16, 9, 10, 57), League.repairStats(listOf(35, 16, 10, 57)))    // Rot-Weiss Essen
        assertEquals(listOf(35, 9, 5, 21, 32), League.repairStats(listOf(35, 21, 32)))          // Saarbrücken: só J, uma coluna e Pts
        assertEquals(listOf(35, 4, 11, 20, 23), League.repairStats(listOf(35, 11, 20, 23)))      // Fortuna Köln: o V (4) não foi lido
        assertEquals(listOf(35, 0, 1, 34, 1), League.repairStats(listOf(35, 1, 34, 1)))          // Preußen Münster (lanterna): 0V 1E 34D, 1 ponto
        assertNull(League.repairStats(listOf(35, 11, 20, 99)))                                  // nenhuma combinação fecha: continua descartada (nunca grava errado)
        assertNull(League.repairStats(listOf(21, 5)))
    }
    @Test fun fullRowsKeepWorking() {
        assertEquals(listOf(21, 12, 1, 8, 37), League.repairStats(listOf(21, 12, 1, 8, 37)))
        assertEquals(listOf(21, 12, 1, 8, 37), League.repairStats(listOf(21, 12, 1, 8, 99)))   // Pts conferido pelas vitórias/empates
    }
}
