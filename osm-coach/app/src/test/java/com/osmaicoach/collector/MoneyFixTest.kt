package com.osmaicoach.collector

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyFixTest {
    @Test fun lostCommaMyValue() {
        assertEquals("57,3M", Money.fixLostComma("573M", 71))
        assertEquals(null, Money.fixLostComma("57,3M", 71))
    }

    @Test fun weeklyDropsOlderWhenValueCollapses() {
        val l = mutableListOf<Weekly.Week>()
        Weekly.upsert(l, Weekly.Week(202543, 1, 108.0, null, null, 0, 0, 0))
        Weekly.upsert(l, Weekly.Week(202544, 2, 47.4, null, null, 0, 0, 0))
        Weekly.upsert(l, Weekly.Week(202545, 3, 70.6, null, null, 0, 0, 0))
        assertEquals(listOf(202544, 202545), l.map { it.id })
    }
    @Test fun referenceBeatsStrengthGuess() {
        // elenco real de 106M com força 79: a regra por força dava 10,6M
        assertEquals("106M", Money.pickByReference("106M", 104.0))
        assertEquals("57,3M", Money.pickByReference("573M", 55.0))
        assertEquals("10,6M", Money.pickByReference("10,6M", 10.0))
        assertEquals(null, Money.pickByReference("106M", null))
    }
}
