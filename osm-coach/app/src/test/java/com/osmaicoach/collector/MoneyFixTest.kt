package com.osmaicoach.collector

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyFixTest {
    @Test fun lostCommaMyValue() {
        assertEquals("57,3M", Money.fixLostComma("573M", 71))
        assertEquals(null, Money.fixLostComma("57,3M", 71))
    }
}
