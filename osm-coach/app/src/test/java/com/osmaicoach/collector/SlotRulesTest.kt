package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test

class SlotRulesTest {
    private val TXT = """
Regras UzM
Superliga Uzbeque 🇺🇿
🚫 Proibido transferências no segundo turno.
🚫Proibido comprar jogador acima over *79*
🚫 Proibido ultrapassar over de treinamento *88*
🚫 Proibido craque ultrapassar over *93*
🚫 Proibido ter mais de 30 jogadores no elenco.
🚫 Proibido ficar mais de 3 dias sem entrar na liga.
✅ Obrigatório ter *3* jogadores da nacionalidade da liga, e cada jogador entrar em campo *5* vezes no mínimo.
⚠️Evoluir no mínimo *3* X o valor inicial o time.
🚨O último treinador é rebaixado.
"""

    @Test fun leOsNumerosDasRegras() {
        val r = SlotRules.parse(TXT)
        assertEquals(79, r.maxBuy); assertEquals(88, r.maxTrain); assertEquals(93, r.maxStar)
        assertEquals(30, r.maxSquad); assertEquals(3, r.maxAwayDays)
        assertEquals(3, r.natCount); assertEquals(5, r.natGames); assertEquals(3, r.evolveX)
        assertTrue(r.noTransferSecondHalf); assertTrue(r.lastRelegated)
        assertTrue(SlotRules.summary(r).size >= 8)
    }

    private fun p(name: String, s: Int, training: Boolean = false) =
        PlayerEntity(2, "MY", Txt.key(name), name, 25, "MC", "MEI", s, "1M", training, false, 0L)

    @Test fun dicasRespeitamLimites() {
        val r = SlotRules.parse(TXT)
        val ps = listOf(p("A Um", 89, true), p("B Dois", 94), p("C Tres", 87)) + (1..28).map { p("X Jogador$it", 70) }
        val tips = SlotRules.tips(r, ps, emptySet(), 14, 22, null, 0L)
        assertTrue(tips.any { it.icon == "🏋" && it.title.contains("A Um") })
        assertTrue(tips.any { it.title.startsWith("Acima do limite de craque") && it.title.contains("B Dois") })
        assertTrue(tips.any { it.title.contains("Elenco com 31") })
        assertTrue(tips.any { it.title.contains("2º turno") })
        assertTrue(SlotRules.tips(SlotRules.parse("só jogar"), ps, emptySet(), 14, 22, null, 0L).isEmpty())
        val away = SlotRules.tips(r, listOf(p("A", 70)), emptySet(), 5, 22, 0L, 50L * 3_600_000L)
        assertTrue(away.any { it.icon == "⏰" })
    }
}
