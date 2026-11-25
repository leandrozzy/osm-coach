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

class WorldSlimTest {
    @Test fun slimKeepsOnlyWhatOsmShowedOrSupabaseSent() {
        val sql = JdbcSql.fresh()
        for (i in 1..3000) sql.exec("INSERT INTO football_players(name,name_key,surname_key,status,updated_at,osm_seen,remote_id) VALUES(?,?,?,?,1,?,?)", arrayOf("Jogador $i", "jogador $i", "x$i", "x", if (i == 7) 2 else 0, if (i == 9) 99 else null))
        for (i in 1..500) sql.exec("INSERT INTO football_clubs(name,name_key,updated_at,osm_seen) VALUES(?,?,1,?)", arrayOf("Clube $i", "clube $i", if (i <= 3) 1 else 0))
        sql.exec("INSERT INTO football_entity_aliases(entity_type,entity_id,alias,alias_key) VALUES('club',1,'A','a'),('club',400,'B','b'),('player',7,'C','c'),('player',8,'D','d')")
        sql.exec("INSERT INTO football_osm_entity_mappings(osm_kind,osm_key,slot,entity_type,entity_id,status,observations,created_at,updated_at) VALUES('player','jogador 8',1,'player',8,'confirmado',1,1,1)")
        val n = WorldClean.slim(sql)
        assertEquals(3L, sql.long("SELECT COUNT(*) FROM football_players"))               // 7 (osm_seen), 9 (veio do Supabase), 8 (mapeamento confirmado)
        assertEquals(3L, sql.long("SELECT COUNT(*) FROM football_clubs"))
        assertEquals(3L, sql.long("SELECT COUNT(*) FROM football_entity_aliases"))        // clube 1, jogador 7 e jogador 8 (os de quem ficou)
        assertTrue(n >= 3000 - 3 + 500 - 3)
        assertEquals(0L, WorldClean.slim(sql))                                             // idempotente
    }
}
