package com.osmaicoach.collector

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImpactTest {
    private fun g(result: String, dev: Boolean) = JSONObject().put("result", result).put("deviated", dev)

    @Test fun followedSplitsByDeviation() {
        val logs = listOf(g("V", false), g("V", false), g("E", false), g("D", false), g("D", true), g("D", true), g("E", true), g("D", true))
        val (a, b) = Impact.followed(logs)!!
        assertEquals(4, a.n); assertEquals(2, a.v)
        assertEquals(4, b.n); assertEquals(0, b.v)
        assertTrue(Impact.followedNote(Pair(a, b)).contains("Seguir o app"))
    }

    @Test fun fewGamesDoNotConclude() {
        val (a, b) = Impact.followed(listOf(g("V", false), g("D", true)))!!.let { it } .let { Pair(it.first, it.second) }
        assertTrue(Impact.followedNote(Pair(a, b)).contains("Poucos jogos"))
    }

    @Test fun listedPlayersOnlyMine() {
        fun p(owner: String, sale: Boolean?) = PlayerEntity(1, owner, "k$owner$sale", "N", 20, "DC", "DEF", 70, "1M", null, sale, 0L)
        assertEquals(1, Impact.listed(listOf(p("MY", true), p("MY", false), p("MY", null), p("RIVAL", true))).size)
    }
}
