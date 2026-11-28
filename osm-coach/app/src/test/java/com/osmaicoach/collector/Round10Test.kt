package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test

/** OCR com letra acentuada repetida ("Brasileirãão") e resumo das regras para a bolha. */
class Round10Test {
    @Test fun repeatedAccentedLetterIsCollapsed() {
        assertEquals("Brasileirão UZM", Txt.fixDupAccent("Brasileirãão UZM"))
        assertEquals("Brasileirão Uzm", Txt.titleCase("BRASILEIRÃÃO UZM"))
        assertEquals("São Paulo", Txt.fixDupAccent("São Paulo"))
    }

    @Test fun legitimateDoubleLettersStayUntouched() {
        assertEquals("Leeds United", Txt.fixDupAccent("Leeds United"))
        assertEquals("Brasileirão Uzm", Txt.fixDupAccent("Brasileirão Uzm"))
        assertEquals("Hoffenheim II", Txt.fixDupAccent("Hoffenheim II"))
        assertEquals("Atlético Mineiro", Txt.fixDupAccent("Atlético Mineiro"))
    }

    @Test fun rulesSummaryHasReadableLinesForTheBubble() {
        val r = SlotRules.parse("Proibido ultrapassar over de treinamento 88. Proibido ter mais de 30 jogadores no elenco.")
        val s = SlotRules.summary(r)
        assertTrue(s.any { it.contains("Treino") && it.contains("88") }); assertTrue(s.any { it.contains("30") })
    }
}
