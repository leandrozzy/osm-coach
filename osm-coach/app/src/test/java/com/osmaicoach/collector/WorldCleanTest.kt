package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test

class WorldCleanTest {
    @Test fun latinNamesPassAndOtherAlphabetsOrCorruptedFail() {
        for (ok in listOf("Borussia Mönchengladbach", "São Paulo", "Atlético Madrid", "Łukasz Fabiański", "Mbappé", "Al-Nassr", "1. FC Köln", "Zażółć"))
            assertTrue(ok, WorldClean.nameOk(ok))
        for (bad in listOf("Спартак Москва", "?? Stolyarenko", "Ã© quebrado�", "東京ヴェルディ", "محمد صلاح", "A", "", "Δικέφαλος"))
            assertFalse(bad, WorldClean.nameOk(bad))
    }
    @Test fun womenDetection() {
        for (w in listOf("Arsenal Women", "FC Barcelona Femení", "Lyon Féminin", "Bayern München Frauen", "NWSL", "Liga F", "Real Madrid (W)")) assertTrue(w, WorldClean.isWomen(w))
        for (m in listOf("Arsenal", "Womenswear United FC".replace("Womenswear", "Wolverhampton"), "Premier League", "Femina Fútbol Club".let { "Flamengo" })) assertFalse(m, WorldClean.isWomen(m))
    }
    @Test fun purgeRemovesOnlyWhatDoesNotBelong() {
        val sql = JdbcSql.fresh()
        sql.exec("INSERT INTO football_players(name,name_key,surname_key,status,updated_at) VALUES('Erling Haaland','erling haaland','haaland','x',1),('Мохамед Салах','x','y','x',1),('? Stolyarenko','s','s','x',1)")
        sql.exec("INSERT INTO football_clubs(name,name_key,marker,updated_at) VALUES('Arsenal','arsenal','',1),('Arsenal Women','arsenal','W',1),('Спартак','sp','',1)")
        val n = WorldClean.purge(sql)
        assertTrue(n >= 4)
        assertEquals(1L, sql.long("SELECT COUNT(*) FROM football_players"))
        assertEquals(1L, sql.long("SELECT COUNT(*) FROM football_clubs"))
        assertEquals(0L, WorldClean.purge(sql))   // idempotente
    }
}
