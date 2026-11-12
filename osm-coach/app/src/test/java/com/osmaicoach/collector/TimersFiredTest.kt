package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test

class TimersFiredTest {
    @Test fun firedFlagIsParsedAndDefaultsToFalse() {
        val json = """{"train_ATA":{"at":1000,"label":"treino","fired":true},"stad_Relvado":{"at":2000,"label":"obra"}}"""
        val l = Timers.parse(1, json).associateBy { it.key }
        assertTrue(l.getValue("train_ATA").fired)
        assertFalse(l.getValue("stad_Relvado").fired)
        assertEquals(2, l.size)   // o cronômetro vencido continua na lista (a Agenda mostra "passou do horário")
    }
}
