package com.osmaicoach.collector

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PostGameRowsTest {
    private fun log(round: Int, res: String?, w: Int, e: Int, d: Int) = JSONObject()
        .put("round", round).put("rival", "Rival$round").put("formation", "4-3-3 A").put("playStyle", "Jogar pelas alas")
        .put("predW", w).put("predE", e).put("predD", d)
        .put("result", res ?: JSONObject.NULL).apply { if (res != null) put("scoreMine", 2).put("scoreOpp", 0) }

    @Test fun hitAndVerdict() {
        val rows = postRows(listOf(log(1, "V", 60, 25, 15), log(2, "D", 60, 25, 15), log(3, null, 50, 30, 20)))
        assertEquals(listOf(3, 2, 1), rows.map { it.round })
        assertEquals(true, rows[2].hit)
        assertEquals(false, rows[1].hit)
        assertNull(rows[0].hit)
        assertTrue(rows[1].verdict.contains("PIOR"))
        assertTrue(rows[2].hitText.contains("ACERTOU"))
        assertTrue(rows[1].hitText.contains("ERROU"))
        assertTrue(rows[0].verdict.contains("Aguardando"))
    }
}
