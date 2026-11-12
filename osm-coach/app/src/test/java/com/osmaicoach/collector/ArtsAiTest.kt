package com.osmaicoach.collector

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtsAiTest {
    @Test fun hueNames() {
        assertEquals("red", ArtsAi.hueName(2f)); assertEquals("red", ArtsAi.hueName(350f))
        assertEquals("yellow", ArtsAi.hueName(50f)); assertEquals("green", ArtsAi.hueName(120f)); assertEquals("blue", ArtsAi.hueName(220f))
    }

    @Test fun lookWordsKnownAndDefault() {
        assertTrue(ArtsAi.lookWords(JSONObject("""{"hair":"bald","beard":"full"}""")).contains("bald"))
        assertTrue(ArtsAi.lookWords(JSONObject("""{"hair":"bald","beard":"full"}""")).contains("beard"))
        // desconhecido ou fora do esperado vira o padrão (cabelo curto, sem barba)
        val d = ArtsAi.lookWords(JSONObject("""{"hair":"unknown","beard":"xyz"}"""))
        assertTrue(d.contains("short") && d.contains("clean shaven"))
        assertTrue(ArtsAi.lookWords(null).contains("clean shaven"))
    }

    @Test fun promptNeverAsksForRealPersonOrText() {
        for (k in listOf("BUY", "GOLEADA", "STREAK", "LEADER", "TITLE", "CAMP", "EXPECT", "MATCHDAY", "COMPARE", "STARS", "MOMENT")) {
            val p = ArtsAi.prompt(k, "red and white", "a red jersey with a white chest band, white collar football jersey", "short neat dark hair, clean shaven")
            assertTrue(k, p.contains("no text") && p.contains("red and white"))
        }
        val buy = ArtsAi.prompt("BUY", "red and white", "", "completely bald, shaved head, full thick beard")
        assertTrue(buy.contains("fictional") && buy.contains("not a real person") && buy.contains("bald"))
        assertTrue(buy.contains("red and white football jersey"))   // sem descrição da camisa: usa as cores
    }
}

class ArtsAiLimitTest {
    @Test fun retryAfterParsedFromFreeLimitBody() {
        val body = """{"status":402,"success":false,"error":{"code":"FREE_LIMIT","message":"Free limit reached: 1 image every 45 seconds per IP. Retry after 27s, or get unlimited"}}"""
        assertEquals(27, ArtsAi.retryAfter(body))
        assertNull(ArtsAi.retryAfter("""{"status":500}"""))
    }
}
