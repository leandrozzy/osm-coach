package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test

class CrestAdminTest {
    private val dir = java.nio.file.Files.createTempDirectory("crest").toFile()

    @Test fun bannerTextIsNeverAClub() {
        assertTrue(CrestAdmin.isBlocked(dir, "25 ANNIVERSARY"))
        assertTrue(CrestAdmin.isBlocked(dir, "25th Anniversary"))
        assertTrue(CrestAdmin.isBlocked(dir, "OSM"))
        assertTrue(CrestAdmin.isBlocked(dir, "123"))
        assertTrue(CrestAdmin.isBlocked(dir, null))
    }

    @Test fun realClubsAreNotBlocked() {
        for (n in listOf("Botafogo", "Red Bull Bragantino", "Fortuna Düsseldorf", "FC Liefering", "EC Cruzeiro Belo Horizonte")) assertFalse(n, CrestAdmin.isBlocked(dir, n))
    }

    @Test fun entriesMarkBlockedNamesAndListKnownClubsWithoutCrest() {
        val e = CrestAdmin.entries(dir, listOf("Botafogo", "25 anniversary", "Santos"))
        val by = e.associateBy { it.name }
        assertEquals("nenhum", by["Botafogo"]!!.source)
        assertEquals("nenhum", by["Santos"]!!.source)
        assertEquals("bloqueado", by["25 anniversary"]?.source ?: "bloqueado")
    }
}
