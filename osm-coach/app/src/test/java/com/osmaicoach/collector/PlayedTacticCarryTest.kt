package com.osmaicoach.collector

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** "Se não leu mais, não mudei": campo não lido na partida vale a última leitura (só leitura real), sem nunca vir da sugestão. */
class PlayedTacticCarryTest {
    private val comp = "3|brasileirao|38"
    private fun rec(round: Int, seen: Map<String, String>, src: String = "tela", at: Long = round * 1000L) =
        PlayedTactic.merge(null, "$comp|$round|rival$round", round, "Rival $round", seen, src, at)

    @Test fun unreadFieldsCarryTheLastRead() {
        val r10 = rec(10, mapOf("formation" to "4-3-3 A", "playStyle" to "Jogo de passe", "pressure" to "64"))
        val r11 = rec(11, mapOf("playStyle" to "Jogar pelas alas"))                       // só o estilo foi relido
        val cur = PlayedTactic.carryFrom(r11, "$comp|11|rival11", 11, listOf(r10))!!
        assertEquals("Jogar pelas alas", PlayedTactic.cell(cur, "playStyle")!!.optString("v"))          // leitura nova vence
        val f = PlayedTactic.cell(cur, "formation")!!
        assertEquals("4-3-3 A", f.optString("v")); assertTrue(f.optBoolean("carried")); assertEquals("última leitura", f.optString("src")); assertEquals(10000L, f.optLong("at"))
        assertEquals("64", PlayedTactic.cell(cur, "pressure")!!.optString("v"))
    }

    @Test fun neverFromFutureOtherSlotOrOtherCompetitionAndNeverFromSuggestion() {
        val future = rec(20, mapOf("formation" to "5-3-2"))
        val otherComp = PlayedTactic.merge(null, "3|copa|6|10|x", 10, "X", mapOf("formation" to "4-4-2 A"), "tela", 5L)
        val cur = PlayedTactic.carryFrom(null, "$comp|11|rival11", 11, listOf(future, otherComp))
        assertNull(cur)                                                                    // nada anterior na MESMA competição: segue desconhecido
        val none = PlayedTactic.carryFrom(rec(11, mapOf("tempo" to "50")), "$comp|11|r", 11, emptyList())!!
        assertNull(PlayedTactic.cell(none, "formation"))
    }

    @Test fun carriedValuesClassifyAsFollowedOrChanged() {
        val r10 = rec(10, mapOf("formation" to "4-3-3 A", "playStyle" to "Jogar pelas alas", "pressure" to "64", "mentality" to "72", "tempo" to "77", "marking" to "Homem-a-homem",
            "offside" to "Sim", "tackle" to "Normal", "advAttack" to "Atacar apenas", "advMid" to "Ajudar a defesa", "advDef" to "Manter posições"))
        val cur = PlayedTactic.carryFrom(null, "$comp|11|r", 11, listOf(r10))!!
        val sug = JSONObject().put("formation", "4-3-3 A").put("playStyle", "Jogar pelas alas").put("pressure", 64).put("mentality", 72).put("tempo", 77).put("marking", "Homem-a-homem")
        assertEquals(PlayedTactic.SEGUIU, PlayedTactic.classify(sug, cur).usage)
        val sug2 = JSONObject(sug.toString()).put("formation", "4-2-3-1")
        assertEquals(PlayedTactic.ALTEROU, PlayedTactic.classify(sug2, cur).usage)
    }
}
