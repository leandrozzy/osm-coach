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

    private fun pl(name: String, s: Int, training: Boolean = false) = PlayerEntity(3, "MY", Txt.key(name), name, 25, "MC", "MEI", s, "1M", training, false, 0L)

    @Test fun rulesShowCheckForMetAndCrossForUnmet() {
        val r = SlotRules.parse("Proibido comprar jogador acima over 80. Proibido ultrapassar over de treinamento 95. Proibido ter mais de 30 jogadores no elenco. Proibido ficar mais de 3 dias sem entrar na liga.")
        val ps = listOf(pl("Alfa", 95), pl("Beta", 70)) + (1..20).map { pl("X$it", 60) }
        val c = SlotRules.checks(r, ps, 5, 22, 0L, 1L * 3_600_000L).associateBy { it.text }
        assertEquals(SlotRules.St.OK, c.getValue("Comprar: até over 80").st)
        assertEquals(SlotRules.St.BAD, c.getValue("Treino: até over 95").st)           // Alfa no limite
        assertTrue(c.getValue("Treino: até over 95").detail!!.contains("Alfa"))
        assertEquals(SlotRules.St.OK, c.getValue("Elenco: até 30 jogadores").st)
        assertEquals(SlotRules.St.OK, c.getValue("Entrar na liga a cada 3 dias").st)
    }

    @Test fun rulesCrossWhenSquadTooBigOrAwayTooLong() {
        val r = SlotRules.parse("Proibido ter mais de 20 jogadores no elenco. Proibido ficar mais de 3 dias sem entrar na liga.")
        val ps = (1..22).map { pl("X$it", 60) }
        val c = SlotRules.checks(r, ps, 5, 22, 0L, 3L * 86_400_000L).associateBy { it.text }
        assertEquals(SlotRules.St.BAD, c.getValue("Elenco: até 20 jogadores").st)
        assertEquals(SlotRules.St.BAD, c.getValue("Entrar na liga a cada 3 dias").st)
    }

    @Test fun nationalityWithoutFlagIsUnknownNotFailed() {
        val r = SlotRules.parse("Obrigatório ter 5 jogadores da nacionalidade da liga, e cada jogador entrar em campo 5 vezes no mínimo.")
        val c = SlotRules.checks(r, emptyList(), 5, 22, null, 0L)
        assertEquals(SlotRules.St.UNKNOWN, c.single().st)
    }
}
