package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test

/** Tabela real da Classificação (print do usuário, 25/11/26, 20 clubes, 11 jogos cada). */
class StandingsTest {
    private val raw = listOf(
        Triple("Botafogo", 11, 33), Triple("Internacional", 11, 28), Triple("Grêmio", 11, 27), Triple("Atlético Mineiro", 11, 27), Triple("Fluminense", 11, 25),
        Triple("Vasco da Gama", 11, 23), Triple("EC Cruzeiro Belo Horizonte", 11, 21), Triple("Santos", 11, 20), Triple("Esporte Clube Bahia", 11, 19), Triple("Corinthians", 11, 19),
        Triple("São Paulo", 11, 14), Triple("Red Bull Bragantino", 11, 11), Triple("Coritiba", 11, 9), Triple("Mirassol FC (SP)", 11, 8), Triple("Atlético Paranaense", 11, 8),
        Triple("Palmeiras", 11, 7), Triple("Esporte Clube Vitória", 11, 6), Triple("Flamengo", 11, 3), Triple("Clube do Remo (PA)", 11, 3), Triple("Chapecoense", 11, 1)
    )
    private fun rows(at: Long = 1_000_000L) = raw.mapIndexed { i, (n, j, p) -> League.Row(n, null, i + 1, j, 0, 0, 0, p, 0, 0, at = at) }

    @Test fun positionComesFromTheStandingsRow() {
        val v = Standings.build(rows(), 1_000_000L, "Red Bull Bragantino", 11, true)!!
        assertEquals(20, v.rows.size)
        assertEquals(12, v.me!!.rank)
        assertEquals(11, v.me!!.pts)
        assertTrue(v.missingRanks.isEmpty())
        assertFalse(v.stale)
        // 11 + 3 = 14 empata com o São Paulo (14, não passa): fica em 11º; 11 + 1 = 12: 12º; 11: 12º
        assertEquals(11, v.ifWin)
        assertEquals(12, v.ifDraw)
        assertEquals(12, v.ifLoss)
    }

    @Test fun missingRowsAreReportedNotInvented() {
        val partial = rows().filter { it.rank != 15 && it.rank != 16 }
        val v = Standings.build(partial, 1_000_000L, "Red Bull Bragantino", 11, false)!!
        assertEquals(listOf(15, 16), v.missingRanks)
        assertNull(v.ifWin)
    }

    @Test fun staleWhenYouPlayedMoreLeagueGamesThanTheTableShows() {
        assertTrue(Standings.build(rows(), 1_000_000L, "Red Bull Bragantino", 12, false)!!.stale)
    }

    @Test fun oldReadingsDoNotMixWithTheLastPass() {
        val old = rows(at = 1_000L).map { League.Row(it.club, null, 21 - it.rank!!, it.j, 0, 0, 0, it.pts, 0, 0, at = 1_000L) }.filter { it.club == "Botafogo" || it.club == "Chapecoense" }
        val fresh = rows(at = 5_000_000L).filter { it.club != "Botafogo" && it.club != "Chapecoense" }
        val v = Standings.build(old + fresh, 5_000_000L, "Red Bull Bragantino", 11, false)!!
        assertEquals(18, v.rows.size)          // só a última passada; as duas linhas velhas ficam de fora
        assertEquals(listOf(1), v.missingRanks)   // o fim da tabela (20º) não dá para saber que falta
    }
}

/** Leitores contra o layout REAL dos prints de 25/11 (coordenadas medidas nos prints, 2000×882). */
class RealLayoutTest {
    @Test fun standingsRowsOfTheRealScreen() {
        fun row(y: Float, rank: String, club: String, nick: String, j: String, v: String, e: String, d: String, pts: String, gl: String) = listOf(
            Fx.line(rank, 0.022f, y, 0.02f), Fx.line(club, 0.12f, y - 0.018f, 0.12f), Fx.line(nick, 0.12f, y + 0.018f, 0.12f),
            Fx.line(j, 0.786f, y, 0.02f), Fx.line(v, 0.814f, y, 0.02f), Fx.line(e, 0.841f, y, 0.02f), Fx.line(d, 0.869f, y, 0.02f), Fx.line(pts, 0.910f, y, 0.02f), Fx.line(gl, 0.965f, y, 0.04f)
        )
        val lines = listOf(Fx.line("Clube", 0.07f, 0.23f), Fx.line("Jog", 0.786f, 0.23f), Fx.line("Pts", 0.91f, 0.23f), Fx.line("Gm-Gs", 0.965f, 0.23f)) +
            row(0.325f, "1", "Botafogo", "UzM wLn7", "11", "11", "0", "0", "33", "29-5") +
            row(0.424f, "2", "Internacional", "Uzmtgb", "11", "9", "1", "1", "28", "19-6") +
            row(0.743f, "12", "Red Bull Bragantino", "leandrozzy", "11", "2", "5", "4", "11", "13-19")
        val r = League.read(Fx.ocr(*lines.toTypedArray()))
        assertNotNull(r)
        assertEquals("st", r!!.first)
        val bragantino = r.second.first { it.club == "Red Bull Bragantino" }
        assertEquals(12, bragantino.rank); assertEquals(11, bragantino.pts); assertEquals(11, bragantino.j); assertEquals(13, bragantino.gf); assertEquals(19, bragantino.ga)
        assertEquals(3, r.second.size)
    }

    @Test fun leagueResultsOfTheRealScreen() {
        fun game(y: Float, home: String, hn: String, sc: String, away: String, an: String) = listOf(
            Fx.line(home, 0.12f, y - 0.02f, 0.10f), Fx.line(hn, 0.12f, y + 0.02f, 0.10f), Fx.line(sc, 0.5f, y, 0.04f),
            Fx.line(away, 0.88f, y - 0.02f, 0.12f), Fx.line(an, 0.88f, y + 0.02f, 0.12f)
        )
        val lines = listOf(Fx.line("Casa", 0.02f, 0.13f), Fx.line("Jornada 1", 0.5f, 0.13f, 0.07f), Fx.line("Fora", 0.97f, 0.13f)) +
            game(0.26f, "Fluminense", "UzM NeyDA CLT", "1-1", "Red Bull Bragantino", "leandrozzy") +
            game(0.44f, "Botafogo", "UzM wLn7", "2-0", "Flamengo", "") +
            game(0.61f, "Internacional", "Uzmtgb", "2-0", "Atlético Paranaense", "Treinador pebas")
        val p = LeagueResults.parse(Fx.ocr(*lines.toTypedArray()))
        assertNotNull(p)
        assertEquals(1, p!!.round)
        assertEquals(3, p.games.size)
        assertEquals("Fluminense", p.games[0].home); assertEquals("Red Bull Bragantino", p.games[0].away)
    }
}
