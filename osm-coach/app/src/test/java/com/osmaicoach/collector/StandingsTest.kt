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
            row(0.325f, "10", "Corinthians", "Nathy Bianque 3", "11", "6", "1", "4", "19", "19-12") +
            row(0.424f, "11", "São Paulo", "José Reginaldo 04", "11", "4", "2", "5", "14", "12-13") +
            row(0.523f, "12", "Red Bull Bragantino", "leandrozzy", "11", "2", "5", "4", "11", "13-19")
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

class StandingsReadingTest {
    // tabela REAL do print de 25/11 (S3, 20 clubes): nome, J, V, E, D, Pts, GM, GS
    private val real = listOf(
        listOf("Botafogo", 11, 11, 0, 0, 33, 29, 5), listOf("Internacional", 11, 9, 1, 1, 28, 19, 6), listOf("Grêmio", 11, 8, 3, 0, 27, 26, 4), listOf("Atlético Mineiro", 11, 8, 3, 0, 27, 25, 5),
        listOf("Fluminense", 11, 8, 1, 2, 25, 28, 7), listOf("Vasco da Gama", 11, 7, 2, 2, 23, 19, 7), listOf("EC Cruzeiro Belo Horizonte", 11, 6, 3, 2, 21, 28, 11), listOf("Santos", 11, 6, 2, 3, 20, 17, 12),
        listOf("Esporte Clube Bahia", 11, 5, 4, 2, 19, 20, 7), listOf("Corinthians", 11, 6, 1, 4, 19, 19, 12), listOf("São Paulo", 11, 4, 2, 5, 14, 12, 13), listOf("Red Bull Bragantino", 11, 2, 5, 4, 11, 13, 19),
        listOf("Coritiba", 11, 2, 3, 6, 9, 8, 30), listOf("Mirassol FC (SP)", 11, 2, 2, 7, 8, 6, 15), listOf("Atlético Paranaense", 11, 2, 2, 7, 8, 10, 21), listOf("Palmeiras", 11, 2, 1, 8, 7, 7, 23),
        listOf("Esporte Clube Vitória", 11, 2, 0, 9, 6, 8, 30), listOf("Flamengo", 11, 1, 0, 10, 3, 3, 23), listOf("Clube do Remo (PA)", 11, 1, 0, 10, 3, 8, 29), listOf("Chapecoense", 11, 0, 1, 10, 1, 4, 30)
    )

    /** Uma tela com 7 linhas a partir da posição `from` (1..); `noRank` = posições sem número lido (taça dourada); `badPts` = pontos lidos errado por posição. */
    private fun screen(from: Int, noRank: Set<Int> = emptySet(), badPts: Map<Int, String> = emptyMap(), skipNick: Set<Int> = emptySet(), dropCol: Map<Int, Int> = emptyMap(), partialBottom: String? = null): OcrResult {
        val lines = ArrayList<OcrLine>()
        lines.add(Fx.line("Clube", 0.07f, 0.23f)); lines.add(Fx.line("Jog", 0.786f, 0.23f)); lines.add(Fx.line("Pts", 0.91f, 0.23f)); lines.add(Fx.line("Gm-Gs", 0.965f, 0.23f))
        for (k in 0 until 7) {
            val pos = from + k; val r = real[pos - 1]; val y = 0.325f + 0.099f * k
            if (pos !in noRank) lines.add(Fx.line("$pos", 0.022f, y, 0.02f))
            lines.add(Fx.line(r[0] as String, 0.15f, y - 0.018f, 0.12f))
            if (pos !in skipNick) lines.add(Fx.line("UzM Treinador$pos", 0.15f, y + 0.018f, 0.12f))
            lines.add(Fx.line("UZM", 0.74f, y, 0.04f))
            val drop = dropCol[pos]
            if (drop != 0) lines.add(Fx.line("${r[1]}", 0.786f, y, 0.02f)); if (drop != 1) lines.add(Fx.line("${r[2]}", 0.814f, y, 0.02f)); if (drop != 2) lines.add(Fx.line("${r[3]}", 0.841f, y, 0.02f)); if (drop != 3) lines.add(Fx.line("${r[4]}", 0.869f, y, 0.02f))
            lines.add(Fx.line(badPts[pos] ?: "${r[5]}", 0.910f, y, 0.02f)); lines.add(Fx.line("${r[6]}-${r[7]}", 0.965f, y, 0.04f))
        }
        // 8ª linha cortada na borda de baixo (como nos prints do slot 2): só um pedaço do nome, sem números
        partialBottom?.let { lines.add(Fx.line(it, 0.15f, 0.325f + 0.099f * 7 - 0.018f, 0.12f)); lines.add(Fx.line("Uzm", 0.15f, 0.325f + 0.099f * 7 + 0.018f, 0.12f)) }
        return Fx.ocr(*lines.toTypedArray())
    }

    @Test fun topRowsWithoutRankNumberKeepTheirPosition() {
        val rows = League.readStandings(screen(1, noRank = setOf(1, 2, 3)))!!
        assertEquals(7, rows.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), rows.map { it.rank })
        assertEquals("Botafogo", rows[0].club)
        assertEquals(27, rows[2].pts)
    }

    @Test fun misreadPointsAreRepairedFromWinsAndDraws() {
        val rows = League.readStandings(screen(1, badPts = mapOf(3 to "2", 4 to "7")))!!      // 27 lido como "2" e "7"
        assertEquals(27, rows.first { it.rank == 3 }.pts)
        assertEquals(27, rows.first { it.rank == 4 }.pts)
    }

    @Test fun rowWithoutCoachNicknameStillReads() {
        val rows = League.readStandings(screen(8, skipNick = setOf(11)))!!
        assertEquals(listOf(8, 9, 9 + 1, 11, 12, 13, 14), rows.map { it.rank })
        assertEquals("São Paulo", rows.first { it.rank == 11 }.club)
    }

    @Test fun fullRealTableBalancesAndAMissingClubDoesNot() {
        fun rowsOf(skip: Int?) = real.mapIndexedNotNull { i, r -> if (i + 1 == skip) null else League.Row(r[0] as String, null, i + 1, r[1] as Int, r[2] as Int, r[3] as Int, r[4] as Int, r[5] as Int, r[6] as Int, r[7] as Int, at = 1L) }
        val ok = Standings.build(rowsOf(null), 1L, "Red Bull Bragantino", 11, true)!!
        assertTrue(ok.complete); assertEquals(20, ok.rows.size)
        val missingLast = Standings.build(rowsOf(20), 1L, "Red Bull Bragantino", 11, true)!!
        assertTrue("sem o 20º a tabela não pode fechar", !missingLast.complete)
        val missingMid = Standings.build(rowsOf(9), 1L, "Red Bull Bragantino", 11, true)!!
        assertEquals(listOf(9), missingMid.missingRanks); assertTrue(!missingMid.complete)
    }

    @Test fun rowCutAtTheBottomEdgeIsIgnoredInsteadOfBecomingAGarbageClub() {
        val rows = League.readStandings(screen(1, partialBottom = "Cantoe"))!!
        assertEquals(7, rows.size); assertTrue(rows.none { it.club == "Cantoe" })
    }

    @Test fun oneMisreadRankNumberDoesNotShiftTheWholeScreen() {
        // diagnóstico 25/11 (S1): a posição 9 foi lida como "1": o Meppen virava 1º e as outras linhas ficavam deslocadas
        val base = screen(9)
        val lines = base.lines.map { l -> if (l.text == "9" && l.xc < 0.07f) Fx.line("1", l.xc, l.yc, 0.02f) else l }
        val rows = League.readStandings(Fx.ocr(*lines.toTypedArray()))!!
        assertEquals(listOf(9, 10, 11, 12, 13, 14, 15), rows.map { it.rank })
        assertEquals("Esporte Clube Bahia", rows[0].club)
    }

    @Test fun rowWhoseEColumnWasNotReadIsRebuilt() {
        val rows = League.readStandings(screen(1, dropCol = mapOf(3 to 2)))!!          // Grêmio: E (3) não lido
        val g = rows.first { it.club == "Grêmio" }
        assertEquals(listOf(11, 8, 3, 0, 27), listOf(g.j, g.v, g.e, g.d, g.pts))
    }

    @Test fun aNewReadingOfAPositionReplacesTheStaleGarbageRowOfThatPosition() {
        val old = League.merge(null, "st", listOf(League.Row("Cantoe", null, 8, 21, 10, 2, 9, 32, 40, 20), League.Row("Admira Wacker", null, 9, 21, 9, 5, 7, 32, 29, 27)), 1000L)
        val now = League.merge(old, "st", listOf(League.Row("Liefering", "leandrozzy", 8, 21, 10, 2, 9, 32, 40, 20)), 2000L)
        val t = League.table(now, "st").associate { it.rank to it.club }
        assertEquals("Liefering", t[8]); assertEquals("Admira Wacker", t[9]); assertEquals(2, t.size)
    }
}
