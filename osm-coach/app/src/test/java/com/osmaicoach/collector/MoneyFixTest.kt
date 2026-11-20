package com.osmaicoach.collector

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyFixTest {
    @Test fun lostCommaMyValue() {
        assertEquals("57,3M", Money.fixLostComma("573M", 71))
        assertEquals(null, Money.fixLostComma("57,3M", 71))
    }

    @Test fun weeklyKeepsOlderWeeksWhenValueDrops() {
        val l = mutableListOf<Weekly.Week>()
        Weekly.upsert(l, Weekly.Week(202543, 1, 108.0, null, null, 0, 0, 0))
        Weekly.upsert(l, Weekly.Week(202544, 2, 47.4, null, null, 0, 0, 0))
        Weekly.upsert(l, Weekly.Week(202545, 3, 70.6, null, null, 0, 0, 0))
        assertEquals(listOf(202543, 202544, 202545), l.map { it.id })
    }
    @Test fun referenceBeatsStrengthGuess() {
        // elenco real de 106M com força 79: a regra por força dava 10,6M
        assertEquals("106M", Money.pickByReference("106M", 104.0))
        assertEquals("57,3M", Money.pickByReference("573M", 55.0))
        assertEquals("10,6M", Money.pickByReference("10,6M", 10.0))
        assertEquals(null, Money.pickByReference("106M", null))
    }
    @Test fun coinIconAndOhAreReadAsMoney() {
        assertEquals("550M", Money.extract("S55OM"))
        assertEquals("550M", Money.extract("S550M"))
        assertEquals("65,2M", Money.extract("(65,2M"))
        assertEquals("80,1M", Money.extract("80,1M"))
        assertEquals(null, Money.extract("Posicao"))
    }
    @Test fun timerWithLetterInsteadOfHourDigitIsRead() {
        // OCR real do S2 (19/11): "Gh 17m 15s" = 6h 17m 15s
        assertEquals(6L * 3600 + 17 * 60 + 15, Parsers.timerSecs("Gh 17m 15s"))
        assertEquals(6L * 3600 + 17 * 60 + 13, Parsers.timerSecs("6h 17m 13s"))
        assertEquals(17L * 60 + 15, Parsers.timerSecs("17m 15s"))
        assertEquals(null, Parsers.timerSecs("18h"))
    }
}

class PlayerValueSanityTest {
    @Test fun coinIconReadAsDigitKeepsOldValue() {
        assertEquals("13,7M", Money.sanePlayerValue("13,7M", "913,7M"))
        assertEquals("13,7M", Money.sanePlayerValue(null, "913,7M"))
        assertEquals("14,1M", Money.sanePlayerValue("13,7M", "14,1M"))
        assertEquals("13,7M", Money.sanePlayerValue("913,7M", "913,7M"))
        assertEquals("13,7M", Money.sanePlayerValue("913,7M", "13,7M"))
    }
}
