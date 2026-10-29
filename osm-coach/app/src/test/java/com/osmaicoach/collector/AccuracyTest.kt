package com.osmaicoach.collector

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccuracyTest {
    private fun log(round: Int, w: Int, e: Int, d: Int, r: String?) =
        JSONObject().put("round", round).put("predW", w).put("predE", e).put("predD", d).apply { if (r != null) put("result", r) }

    @Test fun countsHitsOnlyForGamesWithResultAndPrediction() {
        val logs = listOf(log(1, 50, 25, 25, "V"), log(2, 20, 30, 50, "V"), log(3, 40, 30, 30, null), JSONObject().put("result", "V"))
        val games = Accuracy.parse(1, logs)
        assertEquals(2, games.size)
        val s = Accuracy.of(games)!!
        assertEquals(1, s.hits)
        assertEquals(50, s.pct)
    }

    @Test fun flagsOverconfidenceAndQuietDraws() {
        // 10 jogos: prevê 70% de vitória em todos, acerta 4; saem 4 empates
        val logs = (1..10).map { i -> log(i, 70, 15, 15, if (i <= 4) "V" else if (i <= 8) "E" else "D") }
        val s = Accuracy.of(Accuracy.parse(2, logs))!!
        assertEquals(40, s.pct)
        assertTrue(s.tips.any { it.contains("confiante demais") })
        assertTrue(s.tips.any { it.contains("vitória") && it.contains("4 de 10") })
        assertTrue(s.tips.any { it.contains("empate") })
        assertTrue(s.skill < 0)
    }

    @Test fun noGamesNoSummary() {
        assertNull(Accuracy.of(emptyList()))
    }

    @Test fun splitsByContextAndFlagsTheWeakest() {
        // contra humano erra tudo (6 jogos), contra CPU acerta tudo (6 jogos)
        val logs = (1..12).map { i ->
            log(i, 60, 20, 20, if (i <= 6) "D" else "V").put("human", i <= 6).put("home", i % 2 == 0)
        }
        val s = Accuracy.of(Accuracy.parse(1, logs))!!
        assertEquals(50, s.pct)
        assertTrue(s.splits.any { it.first == "contra humano" && it.second == 0 && it.third == 6 })
        assertTrue(s.tips.any { it.contains("Onde mais erra") && it.contains("contra humano") })
    }
}
