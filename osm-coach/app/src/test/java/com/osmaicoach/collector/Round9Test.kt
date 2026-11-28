package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test

/** Gráficos estilo BI: automáticos, sem filtros, sem misturar confirmado com sugerido. */
class Round9Test {
    private fun g(round: Int, res: String, tactic: String = "4-3-3 A • Jogar pelas alas", applied: Boolean = true, home: Boolean? = true, human: Boolean? = true,
                  mode: String = "Liga", slot: Int = 1, style: String? = null, gf: Int? = 1, ga: Int? = 0) =
        SlotCharts.Game("k$slot$round", round, "R$round", mode, home, human, res, gf, ga, null, null, if (applied) tactic else null, applied, style, null, "parelho (−2 a +2)", false, tactic, slot)

    @Test fun kpiStreakAndEvolution() {
        val l = listOf(g(1, "V"), g(2, "V"), g(3, "E"), g(4, "D"), g(5, "D"))
        val k = SlotBi.kpi(l)
        assertEquals(5, k.n); assertEquals(7, k.pts); assertEquals(47, k.rate); assertEquals(2, k.w)
        assertEquals("2 derrota(s) seguida(s)", SlotBi.streak(l))
        val ev = SlotBi.evolution(l)
        assertEquals(listOf(3, 6, 7, 7, 7), ev.cumulative); assertEquals(5, ev.results.size)
    }

    @Test fun confirmedAndSuggestedTacticsNeverMix() {
        val l = listOf(g(1, "V"), g(2, "V"), g(3, "V"), g(4, "D", tactic = "5-3-2 A • Contra-ataque", applied = false), g(5, "D", tactic = "5-3-2 A • Contra-ataque", applied = false))
        val (conf, sug) = SlotBi.tactics(l)
        assertEquals(1, conf.size); assertEquals("funcionando", conf[0].verdict); assertTrue(conf[0].confirmed)
        assertEquals(1, sug.size); assertFalse(sug[0].confirmed); assertEquals("5-3-2 A • Contra-ataque", sug[0].label)
    }

    @Test fun fewGamesAreNeverFunctioningOrBad() {
        val (conf, _) = SlotBi.tactics(listOf(g(1, "V"), g(2, "V")))
        assertEquals("poucos jogos", conf[0].verdict)
    }

    @Test fun insightsFlagHomeAwayGapAndAreSilentWithoutData() {
        val l = (1..3).map { g(it, "V", home = true) } + (4..6).map { g(it, "D", home = false) }
        assertTrue(SlotBi.insights(l).any { it.kind == "warn" && it.text.startsWith("Fora de casa") })
        assertTrue(SlotBi.insights(emptyList()).isEmpty())
    }

    @Test fun noConfirmedTacticSaysSoInsteadOfInventing() {
        val l = (1..3).map { g(it, "V", applied = false) }
        val ins = SlotBi.insights(l)
        assertTrue(ins.any { it.kind == "info" && it.text.contains("SUGERIDA") })
        assertTrue(ins.none { it.text.startsWith("Funcionando") })
    }

    @Test fun modesAreNotSummed() {
        val l = listOf(g(1, "V", mode = "Liga"), g(2, "V", mode = "Liga"), g(3, "D", mode = "Batalha"))
        assertEquals("Liga", SlotBi.mainMode(l))
        assertEquals(1, SlotBi.kpi(l.filter { it.mode == "Batalha" }).n)
    }

    @Test fun globalViewComparesSlots() {
        val l = listOf(g(1, "V", slot = 1), g(2, "V", slot = 1), g(1, "D", slot = 2))
        val bs = SlotBi.bySlot(l)
        assertEquals(listOf("Slot 1", "Slot 2"), bs.map { it.label })
        assertTrue(bs[0].ppg > bs[1].ppg)
    }

    @Test fun missingStatsAreNotZeroInKpi() {
        val k = SlotBi.kpi(listOf(g(1, "V", gf = null, ga = null)))
        assertEquals(0, k.goalGames); assertEquals(1, k.n)
    }
}
