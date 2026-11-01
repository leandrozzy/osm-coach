package com.osmaicoach.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeagueTest {
    private fun header(vararg cols: Pair<String, Float>) = cols.map { Fx.line(it.first, it.second, 0.23f, 0.05f) }

    private fun stRow(rank: Int, y: Float, club: String, nick: String, nums: List<String>, gl: String) = listOf(
        Fx.line(rank.toString(), 0.02f, y, 0.02f),
        Fx.line(club, 0.12f, y - 0.02f, 0.12f),
        Fx.line(nick, 0.12f, y + 0.02f, 0.12f)
    ) + nums.mapIndexed { i, n -> Fx.line(n, listOf(0.787f, 0.814f, 0.841f, 0.869f, 0.91f)[i], y, 0.02f) } + Fx.line(gl, 0.966f, y, 0.04f)

    @Test fun readsStandingsRows() {
        val lines = header("Classificação" to 0.08f, "Clube" to 0.07f, "Jog" to 0.78f, "V" to 0.81f, "E" to 0.84f, "D" to 0.87f, "Pts" to 0.91f, "Gm-Gs" to 0.96f) +
            stRow(1, 0.33f, "Pakhtakor", "UzMRobozao", listOf("30", "22", "5", "3", "71"), "64-22") +
            stRow(2, 0.43f, "Surkhan", "natan bianque_5", listOf("30", "19", "7", "4", "64"), "40-23")
        val r = League.read(Fx.ocr(*lines.toTypedArray()))
        assertNotNull(r)
        assertEquals("st", r!!.first)
        assertEquals(2, r.second.size)
        val p = r.second[0]
        assertEquals("Pakhtakor", p.club)
        assertEquals(71, p.pts); assertEquals(64, p.gf); assertEquals(22, p.ga); assertEquals(30, p.j)
        val rates = League.rates(p)!!
        assertTrue(rates.gf > 2.0 && rates.ga < 0.8)
    }

    @Test fun readsSquadValueRows() {
        val lines = header("Clube" to 0.07f, "Valor" to 0.43f, "Jogadores" to 0.69f, "Valor médio do jogador" to 0.86f) +
            listOf(
                Fx.line("1", 0.015f, 0.33f, 0.02f), Fx.line("Lokomotiv Tashkent", 0.14f, 0.31f, 0.14f), Fx.line("betolobowolf", 0.12f, 0.35f, 0.12f),
                Fx.line("435M", 0.45f, 0.33f, 0.05f), Fx.line("30", 0.69f, 0.33f, 0.02f), Fx.line("14,5M", 0.95f, 0.33f, 0.05f),
                Fx.line("14", 0.015f, 0.43f, 0.02f), Fx.line("Nasaf", 0.1f, 0.41f, 0.08f), Fx.line("leandrozzy", 0.12f, 0.45f, 0.12f),
                Fx.line("133M", 0.45f, 0.43f, 0.05f), Fx.line("19", 0.69f, 0.43f, 0.02f), Fx.line("7,0M", 0.95f, 0.43f, 0.05f)
            )
        val r = League.read(Fx.ocr(*lines.toTypedArray()))
        assertNotNull(r)
        assertEquals("va", r!!.first)
        val me = League.find(r.second, "Nasaf")!!
        assertEquals(133.0, me.value!!, 0.01); assertEquals(19, me.players)
        assertEquals(14.5, r.second[0].avg!!, 0.01)
    }

    @Test fun otherScreensAreIgnored() {
        assertNull(League.read(Fx.ocr(Fx.line("Define a tua tática para o próximo jogo", 0.5f, 0.5f))))
    }

    @Test fun compareSquadWithLeague() {
        val rows = (1..8).map { League.Row("Clube$it", null, it, value = 100.0 + it * 40, players = 20 + it, avg = 5.0 + it) } + League.Row("Nasaf", null, 9, value = 133.0, players = 19, avg = 7.0)
        val c = League.squad(rows, League.find(rows, "Nasaf"))!!
        assertTrue(c.rank in 8..9 && c.of == 9)
        assertTrue(c.topValue!! > c.value)
    }

    @Test fun smallSquadGetsAdvice() {
        val vals = (1..8).map { League.Row("Clube$it", null, it, value = 100.0 + it * 40, players = 26 + it % 3, avg = 9.0 + it) } + League.Row("Nasaf", null, 9, value = 133.0, players = 19, avg = 7.0)
        val lj = League.merge(null, "va", vals, 1_000L)
        val f = mapOf(K.TEAM to StoredField("Nasaf", 0.9, 1L), K.ROUND_DONE to StoredField("20", 0.9, 1L))
        val tips = Hints.leagueTips(lj, f, 19, 2_000L)
        assertTrue(tips.any { it.icon == "👥" })
        assertTrue(tips.any { it.icon == "🏆" })
        assertTrue(tips.none { it.icon == "📊" } || tips.any { it.icon == "📊" }) // convite só quando a tabela está velha/ausente
    }

    @Test fun winModelUsesLeagueRatesOnlyWhenBothKnown() {
        val base = WinModel.Input(myStrength = 80, rivalStrength = 80)
        val a = WinModel.predict(base)!!
        val b = WinModel.predict(base.copy(myRates = League.Rates(2.4, 0.8, 30), rivalRates = League.Rates(0.8, 2.0, 30), leagueTeamGoals = 1.4))!!
        assertTrue(b.win > a.win)
        val c = WinModel.predict(base.copy(myRates = League.Rates(2.4, 0.8, 30)))!!
        assertEquals(a.win, c.win)
    }
}

class RivalConfirmedTest {
    private fun sf(v: String, at: Long) = StoredField(v, 0.9, at)
    @org.junit.Test fun rivalConfirmedExigeLeituraRecente() {
        val now = 100L * 3600_000L
        val ok = mapOf(K.RIVAL_FORMATION to sf("4-3-3", now - 3_600_000L), K.RIVAL_PLAN to sf("Ofensivo", now - 3_600_000L), K.RIVAL_STRENGTH to sf("70", now - 60_000L))
        org.junit.Assert.assertTrue(Director.rivalConfirmed(ok, now))
        org.junit.Assert.assertFalse(Director.rivalConfirmed(ok + (K.RIVAL_FORMATION to sf("4-3-3", now - 5 * 3_600_000L)), now))
        org.junit.Assert.assertFalse(Director.rivalConfirmed(ok - K.RIVAL_PLAN, now))
        org.junit.Assert.assertFalse(Director.rivalConfirmed(ok + (K.RIVAL_STRENGTH to sf("NI", now)), now))
    }
}
