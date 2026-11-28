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

    private fun g(r: Int) = LeagueResults.Stored(r, "Rival FC", "Outro $r", 0, 2, 1, 0L)   // rival perdeu

    @Test fun preGameDotsBeatAShorterConfirmedSeriesThatCoversTheRound() {
        // print real: o jogo mostra D D D D D, mas só 1 placar confirmado (rodada 15, a atual) escondia as bolinhas
        val f = FormRead.rivalForm(listOf(g(15)), "Rival FC", 15, "DDDDD", true, false)!!
        assertEquals("prejogo", f.source); assertEquals("DDDDD", f.dots)
        assertTrue(f.note.contains("1 resultado"))
    }

    @Test fun confirmedSeriesStillWinsWhenAsLongAsTheDots() {
        val f = FormRead.rivalForm((11..15).map { g(it) }, "Rival FC", 15, "DDDDD", true, false)!!
        assertEquals("resultados", f.source); assertEquals(5, f.dots.length)
    }
}
