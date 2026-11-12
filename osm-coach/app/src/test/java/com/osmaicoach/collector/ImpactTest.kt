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

class LedgerSanitizeTest {
    private fun e(t: String, k: String, at: Long) = Ledger.Entry(t, k, k, at, 1.0, false, 70, 25)
    private val h = 3600_000L

    @Test fun sellOfPlayerStillInSquadIsDropped() {
        val l = listOf(e("buy", "a", 0), e("sell", "a", 100 * h), e("sell", "b", 100 * h))
        val out = Ledger.sanitize(l, setOf("a"))
        assertTrue(out.none { it.type == "sell" && it.key == "a" })   // a ainda está no elenco
        assertTrue(out.any { it.type == "sell" && it.key == "b" })    // b saiu de verdade
    }

    @Test fun sellThenBuyBackWithin72hIsReadingNoise() {
        val l = listOf(e("sell", "z", 10 * h), e("buy", "z", 12 * h))
        assertTrue(Ledger.sanitize(l, setOf("z")).isEmpty())
    }

    @Test fun realSaleKept() {
        val l = listOf(e("buy", "q", 0), e("sell", "q", 500 * h))
        assertEquals(2, Ledger.sanitize(l, emptySet()).size)
    }
}
