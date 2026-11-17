package com.osmaicoach.collector

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewFeaturesTest {
    private fun tac(formation: String = "4-4-2 B", style: String = "Jogo de passe", pr: Int = 61, me: Int = 67, te: Int = 54, tackle: String = "Equilibrado") =
        JSONObject().put("formation", formation).put("playStyle", style).put("pressure", pr).put("mentality", me).put("tempo", te).put("tackle", tackle).put("advDef", "Defender atrás")

    private fun bands() = JSONObject().put("pressure", JSONArray(listOf(58, 64))).put("mentality", JSONArray(listOf(65, 68))).put("tempo", JSONArray(listOf(51, 57)))

    @Test fun auditAllGoodIsHighAndNeverTouchesValues() {
        val t = tac(te = 50)
        val before = t.toString()
        val r = TacticAudit.evaluate(t, bands(), "Agressivo", true, emptyList(), emptyList())
        assertEquals(before, t.toString())
        assertTrue(r.score >= 90)
        assertTrue(r.problems.isEmpty())
    }

    @Test fun auditPenalizesBandsRefereeAndAvoid() {
        val t = tac(pr = 90, me = 50, te = 40, tackle = "Extremo")
        val r = TacticAudit.evaluate(t, bands(), "Normal", false, listOf("Formação 4-4-2"), listOf("estilo"))
        assertTrue(r.score < 75)
        assertTrue(r.problems.any { it.contains("árbitro") })
        assertTrue(r.problems.any { it.contains("EVITE") })
        assertTrue(r.problems.any { it.contains("fora da faixa") })
        assertTrue(r.tips.any { it.contains("No OSM") })
    }

    @Test fun vsLibraryGroupsByRivalFormationAndCountsResults() {
        fun l(res: String, f: String, st: String, rf: String) = JSONObject().put("result", res).put("formation", f).put("playStyle", st).put("rivalFormationRead", rf)
        val logs = listOf(
            l("V", "4-3-3 A", "Jogar pelas alas", "5-2-3 A"), l("E", "4-3-3 A", "Jogar pelas alas", "5-2-3 B"), l("D", "4-4-2 B", "Jogo de passe", "5-2-3 A"),
            l("D", "4-4-2 B", "Jogo de passe", "5-2-3 A"), l("V", "4-3-3 B", "Contra-ataque", "4-4-2 A"), JSONObject().put("result", "V").put("formation", "4-3-3 A")
        )
        val g = VsLibrary.build(logs)
        assertEquals("5-2-3", g.first().rival)
        assertEquals(4, g.first().games)
        val best = g.first().rows.first()
        assertEquals("4-3-3 • Jogar pelas alas", best.mine.replace("4-3-3", "4-3-3"))
        assertEquals(2, g.size)
    }

    @Test fun playerPriceUsesOsmCeilingRange() {
        val p = PlayerTools.price("10M", null)!!
        assertEquals(20.0, p.lo, 0.001); assertEquals(25.0, p.hi, 0.001)
        assertEquals(null, PlayerTools.price("?", null))
    }
}
