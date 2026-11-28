package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test

/** Regras da liga na bolha: veemente no limite de treino/craque. */
class Round8Test {
    private fun p(name: String, s: Int, training: Boolean = false) =
        PlayerEntity(2, "MY", Txt.key(name), name, 25, "MC", "MEI", s, "1M", training, false, 0L)
    private val r = SlotRules.parse("Proibido ultrapassar over de treino 95. O craque não pode ultrapassar over 97.")

    private fun alerts(vararg ps: PlayerEntity) = SlotRules.tips(r, ps.toList(), emptySet(), 5, 22, null, 0L).filter { it.title.startsWith("🚫") }

    @Test fun at94WarnsItCannotPass95() {
        val a = alerts(p("Alfa", 94))
        assertTrue(a.any { it.title.contains("NÃO PODE PASSAR DE 95") && it.title.contains("Alfa") })
        assertTrue(a.all { it.icon == "🚨" })                        // 🚨 conta como pendência (selo vermelho na bolha)
    }

    @Test fun at95NeverPutInTraining() {
        assertTrue(alerts(p("Beta", 95)).any { it.title.contains("NÃO COLOQUE NO TREINO DE MANEIRA NENHUMA") && it.title.contains("Beta") })
    }

    @Test fun trainingAt94Or95MustBeRemovedNow() {
        assertTrue(alerts(p("Gama", 94, training = true)).any { it.title.contains("TIRE DO TREINO AGORA") })
        assertTrue(alerts(p("Delta", 95, training = true)).any { it.title.contains("TIRE DO TREINO AGORA") })
    }

    @Test fun farFromLimitHasNoVeementeAlertAndNearIsOnlyInfo() {
        assertTrue(alerts(p("Eps", 80)).isEmpty())
        val tips = SlotRules.tips(r, listOf(p("Zeta", 92)), emptySet(), 5, 22, null, 0L)
        assertTrue(tips.none { it.title.startsWith("🚫") }); assertTrue(tips.any { it.title.startsWith("Perto do limite de treino") })
    }

    @Test fun starAtLimitAndAbove() {
        assertTrue(alerts(p("Eta", 97)).any { it.title.contains("CRAQUE NO LIMITE") })
        assertTrue(alerts(p("Teta", 98)).any { it.title.contains("ACIMA DO LIMITE DE CRAQUE") })
    }

    @Test fun noRulesNoAlerts() {
        val none = SlotRules.parse("")
        assertTrue(SlotRules.tips(none, listOf(p("X", 95)), emptySet(), 5, 22, null, 0L).isEmpty())
    }

    @Test fun alertsComeFirstInDecisionCard() {
        val f = mapOf(K.ROUND to StoredField("5", 0.9, 0L), K.RIVAL_TEAM to StoredField("Rival", 0.9, 0L))
        val v = TacticDecision.decide(TacticDecision.In(4, f, null, 0L, null, false, null, null, 1L, emptyList(), listOf("🚫 REGRA DA LIGA — NÃO PODE PASSAR DE 95: Alfa (94)")))
        assertTrue(v.lines.first().startsWith("🚫"))
    }
}
