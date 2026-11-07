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
}
