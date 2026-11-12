package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test

class PlayerNameTest {
    @Test fun apostropheNamesAreKept() {
        assertEquals("N'Guessan", PlayerName.clean("N'Guessan"))
        assertEquals("O'Neill", PlayerName.clean("O'Neill"))
        assertEquals("N'Golo Kanté", PlayerName.clean("N'Golo Kanté"))
    }

    @Test fun shortNamesNeedTheLooserLimit() {
        assertNull(PlayerName.clean("Ba"))                 // padrão: 3 letras (pedaço de leitura)
        assertEquals("Ba", PlayerName.clean("Ba", 2))      // o plantel aceita se a linha tem força e valor
        assertNull(PlayerName.clean("ba", 2))              // sem maiúscula continua sendo lixo
    }

    @Test fun ocrGarbageStillRejected() {
        assertNull(PlayerName.clean("MdLK"))
        assertNull(PlayerName.clean("RAbdikhoikov"))
    }
}
