package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

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
        assertTrue(r.noTransferSecondHalf); assertEquals(1, r.relegated)
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

class SlotRulesExtraTest {
    @Test fun leRebaixamento() {
        assertEquals(1, SlotRules.parse("🚨O último treinador é rebaixado.").relegated)
        assertEquals(3, SlotRules.parse("Os 3 últimos treinadores são rebaixados").relegated)
        assertEquals(2, SlotRules.parse("Os últimos 2 times serão rebaixados").relegated)
        assertEquals(0, SlotRules.parse("Proibido treino secreto").relegated)
    }

    @Test fun zonaDeRebaixamentoEMetaDeValor() {
        val r = SlotRules.parse("Os 2 últimos são rebaixados\nEvoluir no mínimo *3* X o valor inicial")
        val z = SlotRules.tips(r, emptyList(), emptySet(), 10, 22, null, 0L, SlotRules.Extra(pos = 12, teams = 12, gapPts = -4, valueNow = 1.0, valueBase = 1.0))
        assertTrue(z.any { it.title.startsWith("ZONA DE REBAIXAMENTO") && it.icon == "🚨" })
        assertTrue(z.any { it.icon == "📈" && it.title.contains("atrasado") })
        val ok = SlotRules.tips(r, emptyList(), emptySet(), 10, 22, null, 0L, SlotRules.Extra(pos = 4, teams = 12, gapPts = 9, valueNow = 2.0, valueBase = 1.0))
        assertTrue(ok.none { it.icon == "🚨" })
    }

    @Test fun cotaDeNacionalidade() {
        val r = SlotRules.parse("Obrigatório ter *3* jogadores da nacionalidade da liga, e cada jogador entrar em campo *5* vezes no mínimo.")
        val x = SlotRules.Extra(natives = listOf("A Um" to 5, "B Dois" to 2), flagsKnown = true)
        val t = SlotRules.tips(r, emptyList(), emptySet(), 5, 22, null, 0L, x)
        assertTrue(t.any { it.icon == "🚨" && it.title.contains("Faltam 1") })
        val x2 = SlotRules.Extra(natives = listOf("A" to 5, "B" to 2, "C" to 6), flagsKnown = true)
        assertTrue(SlotRules.tips(r, emptyList(), emptySet(), 5, 22, null, 0L, x2).any { it.icon == "📝" && it.title.contains("B (2/5") })
        assertTrue(SlotRules.sameFlag("BWRBWRBWR", "BWRBWRBWW"))
        assertFalse(SlotRules.sameFlag("BWRBWRBWR", "GWGGWGGWG"))
    }
}

class CoinsTest {
    @Test fun leSaldoDoTopoDoOsm() {
        // trechos reais dos logs de 06/11: "7,7M@0.18,0.04 | 136@0.27,0.04 | FREE@0.33,0.05"
        val o = Fx.ocr(Fx.line("7,7M", 0.18f, 0.04f, 0.05f), Fx.line("136", 0.27f, 0.04f, 0.04f), Fx.line("FREE", 0.33f, 0.05f, 0.04f), Fx.line("23", 0.53f, 0.24f, 0.04f))
        assertEquals(136, Parsers.bossCoins(o))
        val o2 = Fx.ocr(Fx.line("78M", 0.13f, 0.04f, 0.05f), Fx.line("145", 0.22f, 0.04f, 0.04f))
        assertEquals(145, Parsers.bossCoins(o2))
        assertNull(Parsers.bossCoins(Fx.ocr(Fx.line("Jornada 20", 0.5f, 0.13f, 0.1f))))
    }

    @Test fun reservaDe100NuncaEGasta() {
        assertTrue(Coins.spendPlan(100, emptyList()).single().contains("não se toca"))
        assertTrue(Coins.spendPlan(80, emptyList()).single().contains("não se toca"))
        val p = Coins.spendPlan(145, listOf(Coins.SlotInfo(4, "Otelul", -10, null), Coins.SlotInfo(1, "Fortuna", 6, null),
            Coins.SlotInfo(3, "Uzm", -7, "Proibido comprar pacote de jogador.\nProibido treino secreto.")))
        assertTrue(p.first().contains("45 BC"))
        assertTrue(p.any { it.startsWith("1º lugar") && it.contains("S4") })
        assertTrue(p.any { it.contains("S3") && it.contains("pacote de jogador") && it.contains("treino secreto") })
        assertTrue(Coins.spendPlan(null, emptyList()).single().contains("saldo"))
    }

    @Test fun pesquisaIgnoraGeradoresEHacks() {
        val j = JSONObject("""{"ways":[{"title":"Vídeos FREE","how":"Assista","speed":3,"source":"osmtactic.com"},{"title":"Gerador de Boss Coins","how":"cole seu login","speed":3,"source":"x"}],"tips":["Treine o setor fraco","use o bug de moedas infinitas"],"how":"ok"}""")
        val c = Coins.clean(j)
        assertEquals(1, c.getJSONArray("ways").length())
        assertEquals(1, c.getJSONArray("tips").length())
    }
}

class ReadSummaryTest {
    private fun sc(type: String, ex: Int) = ScreenEntity(0, "s", 0L, type, 1, 1.0, "h", null, 10, ex, "done", "")
    @Test fun resumoMostraTelasLidasDeVerdade() {
        val t = Processor.readSummary(listOf(sc("PREGAME", 2), sc("PREGAME", 0), sc("RESULT", 5), sc("OTHER_OSM", 0)))!!
        assertTrue(t, t.startsWith("Telas lidas nesta sessão: 4"))
        assertTrue(t.contains("Pré-jogo 2") && t.contains("Resultado 1") && t.contains("7 campo(s)"))
        assertNull(Processor.readSummary(emptyList()))
    }
}
