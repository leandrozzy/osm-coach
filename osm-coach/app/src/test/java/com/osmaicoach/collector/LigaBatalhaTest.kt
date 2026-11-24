package com.osmaicoach.collector

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Resultados da liga, projeção, Radar, central de batalha e widget (24/11). Layout das telas NÃO validado com prints reais (valores do pedido do usuário). */
class LigaBatalhaTest {
    private fun row(h: String, sc: String, a: String, y: Float) = listOf(Fx.line(h, 0.2f, y, 0.2f), Fx.line(sc, 0.5f, y, 0.1f), Fx.line(a, 0.8f, y, 0.2f))
    private fun part1() = Fx.ocr(Fx.line("Jornada 11", 0.5f, 0.2f, 0.2f), *(row("Flamengo", "1 - 2", "Red Bull Bragantino", 0.3f) + row("Palmeiras", "0 - 0", "Santos", 0.36f) + row("Cruzeiro", "3 - 1", "Bahia", 0.42f)).toTypedArray())
    private fun part2() = Fx.ocr(*(row("Gremio", "2 - 2", "Vasco", 0.3f) + row("Botafogo", "1 - 0", "Fortaleza", 0.36f) + row("Sport", "0 - 1", "Ceara", 0.42f)).toTypedArray())

    @Test fun parsesPartOneWithFlamengoBragantino() {
        val p = LeagueResults.parse(part1())!!
        assertEquals(11, p.round)
        assertEquals(3, p.games.size)
        val g = p.games.first { it.home == "Flamengo" }
        assertEquals("Red Bull Bragantino", g.away); assertEquals(1, g.gh); assertEquals(2, g.ga)
    }

    @Test fun scrollInTwoPartsInheritsRoundOnlyWithContinuity() {
        val s = LeagueResults.Session()
        val p1 = LeagueResults.parse(part1())!!
        assertEquals(11, LeagueResults.resolveRound(p1, 1, 1000L, s))
        val p2 = LeagueResults.parse(part2())!!
        assertNull(p2.round)                                                      // cabeçalho sumiu
        assertEquals(11, LeagueResults.resolveRound(p2, 1, 20_000L, s))           // mesma tela/sessão/slot, dentro do prazo
        assertNull(LeagueResults.resolveRound(p2, 2, 21_000L, s))                 // outro slot: sem continuidade
        assertNull(LeagueResults.resolveRound(p2, 1, 100_000L, s))                // sessão antiga: não herda
    }

    @Test fun repeatedReadingDoesNotDuplicateAndPartialDoesNotEraseBetter() {
        val g1 = LeagueResults.parse(part1())!!.games
        val (j1, a1) = LeagueResults.store(null, "liga|34", 11, g1, emptyList(), 1L)
        assertEquals(3, a1)
        val (j2, a2) = LeagueResults.store(j1, "liga|34", 11, g1, emptyList(), 2L)
        assertEquals(0, a2)
        assertEquals(3, LeagueResults.games(j2, "liga|34").size)
        assertEquals(2, LeagueResults.games(j2, "liga|34").first().n)
        // leitura incerta (placar diferente de uma só leitura) não derruba o confirmado
        val wrong = listOf(LeagueResults.Game("Flamengo", "Red Bull Bragantino", 7, 7))
        val (j3, _) = LeagueResults.store(j2, "liga|34", 11, wrong, emptyList(), 3L)
        val fla = LeagueResults.games(j3, "liga|34").first { it.home == "Flamengo" }
        assertEquals(1, fla.gh); assertEquals(2, fla.ga)
        // mesma leitura parcial (2º pedaço) soma jogos novos sem apagar o 1º
        val (j4, a4) = LeagueResults.store(j3, "liga|34", 11, LeagueResults.parse(part2())!!.games, emptyList(), 4L)
        assertEquals(3, a4); assertEquals(6, LeagueResults.games(j4, "liga|34").size)
    }

    @Test fun sameRoundInDifferentLeaguesDoesNotMix() {
        val g = LeagueResults.parse(part1())!!.games
        val (a, _) = LeagueResults.store(null, "liga a|34", 11, g, emptyList(), 1L)
        val (b, n) = LeagueResults.store(a, "liga b|38", 11, g, emptyList(), 2L)
        assertEquals(3, n)
        assertEquals(3, LeagueResults.games(b, "liga a|34").size); assertEquals(3, LeagueResults.games(b, "liga b|38").size)   // dados anteriores continuam
        assertNull(LeagueResults.compKey(emptyMap()))                                                                             // sem competição: não salva
        fun sf(v: String) = StoredField(v, 0.9, 1L)
        assertNotEquals(LeagueResults.compKey(mapOf(K.COMPETITION to sf("Liga A"), K.ROUND_TOTAL to sf("34"))), LeagueResults.compKey(mapOf(K.COMPETITION to sf("Liga A"), K.ROUND_TOTAL to sf("38"))))
    }

    @Test fun otherScreensAreNotResultsLists() {
        assertNull(LeagueResults.parse(Fx.ocr(Fx.line("Primeira parte", 0.5f, 0.2f), *row("A", "1 - 0", "B", 0.3f).toTypedArray(), *row("C", "1 - 0", "D", 0.4f).toTypedArray(), *row("E", "1 - 0", "F", 0.5f).toTypedArray())))
        // placar agregado de batalha (78×60) nunca vira jogo de liga
        assertNull(LeagueResults.parse(Fx.ocr(Fx.line("Jornada 10", 0.5f, 0.2f), *row("uzMitaofc", "78 - 60", "IRON WOLVES FC", 0.3f).toTypedArray(), *row("X", "70 - 50", "Y", 0.36f).toTypedArray())))
    }

    private fun st(club: String, rank: Int, j: Int, pts: Int) = League.Row(club, null, rank, j, 0, 0, 0, pts, 0, 0)
    private val table = listOf(st("Cruzeiro", 1, 10, 25), st("Flamengo", 2, 10, 22), st("Red Bull Bragantino", 3, 10, 20), st("Bahia", 4, 10, 15), st("Santos", 5, 10, 12), st("Palmeiras", 6, 10, 10))

    @Test fun tableThatAlreadyIncludesRoundIsNotSummedAgain() {
        val games = listOf(LeagueResults.Stored(10, "Flamengo", "Bahia", 3, 0, 1, 0L))   // j=10 ≥ 10: já está na tabela
        val adj = LeagueResults.adjust(table, games)
        assertEquals(22, adj.rows.first { it.club == "Flamengo" }.pts)
        assertTrue(adj.rows.all { it.applied.isEmpty() })
        // rodada 11 ainda não incluída (j=10): soma UMA vez, mesmo chamando duas vezes
        val g11 = listOf(LeagueResults.Stored(11, "Flamengo", "Red Bull Bragantino", 1, 2, 1, 0L))
        val a = LeagueResults.adjust(table, g11)
        assertEquals(22, a.rows.first { it.club == "Flamengo" }.pts)          // derrota: Flamengo +0
        assertEquals(23, a.rows.first { it.club == "Red Bull Bragantino" }.pts)   // vitória: +3 uma vez só
        val again = LeagueResults.adjust(a.rows.map { League.Row(it.club, null, it.rank, it.j, 0, 0, 0, it.pts, 0, 0) }, g11)
        assertEquals(23, again.rows.first { it.club == "Red Bull Bragantino" }.pts)   // a tabela ajustada já inclui J11: não soma de novo
    }

    @Test fun duplicateStoredGameInRoundAppliesOnce() {
        val g = LeagueResults.Stored(11, "Flamengo", "Red Bull Bragantino", 1, 2, 1, 0L)
        val a = LeagueResults.adjust(table, listOf(g))
        assertEquals(22, a.rows.first { it.club == "Flamengo" }.pts)
        assertEquals(23, a.rows.first { it.club == "Red Bull Bragantino" }.pts)
        assertEquals(11, a.rows.first { it.club == "Flamengo" }.j)
    }

    @Test fun movementShowsPassedAndClosed() {
        val snaps = org.json.JSONArray()
        fun snap(rows: List<League.Row>): JSONObject { val r = JSONObject(); for (x in rows) r.put(League.key(x.club), org.json.JSONArray().put(x.rank).put(x.j).put(x.pts).put(x.club)); return JSONObject().put("at", 1).put("r", r) }
        snaps.put(snap(table))
        val after = listOf(st("Cruzeiro", 1, 11, 28), st("Red Bull Bragantino", 2, 11, 23), st("Flamengo", 3, 11, 22), st("Bahia", 4, 11, 18), st("Santos", 5, 11, 13), st("Palmeiras", 6, 11, 10))
        snaps.put(snap(after))
        val games = listOf(LeagueResults.Stored(11, "Flamengo", "Red Bull Bragantino", 1, 2, 1, 0L))
        val m = LeagueResults.movement(snaps, "Flamengo", games)!!
        assertEquals(2, m.rankFrom); assertEquals(3, m.rankTo)
        assertTrue(m.passed.isEmpty())                                       // o Bragantino me passou: não é "ultrapassei"
        assertTrue(m.affecting.any { it.contains("Flamengo 1×2 Red Bull Bragantino") })
        val mb = LeagueResults.movement(snaps, "Red Bull Bragantino", games)!!   // do ponto de vista do Bragantino: ultrapassou o Flamengo
        assertEquals(3, mb.rankFrom); assertEquals(2, mb.rankTo)
        assertEquals(listOf("Flamengo"), mb.passed)
    }

    @Test fun projectionStaysWithinWhatDataSupports() {
        val adj = LeagueResults.adjust(table, emptyList())
        val noTotal = LeagueResults.project(adj, "Flamengo", null, false)!!
        assertEquals(0, noTotal.confirmedBest); assertNull(noTotal.estimateRank); assertNull(noTotal.ifWin)     // total e calendário desconhecidos: nada de faixa/efeito
        assertTrue(noTotal.limits.any { it.contains("total de jornadas") } && noTotal.limits.any { it.contains("próximo jogo") })
        val p = LeagueResults.project(adj, "Flamengo", 12, true)!!
        assertEquals(2, p.remaining)
        assertTrue(p.confirmedBest <= p.confirmedWorst)
        assertTrue(p.ifWin!! <= p.ifDraw!! && p.ifDraw!! <= p.ifLoss!!)
        assertNotNull(p.estimateRank)
    }

    @Test fun unknownTiebreakIsNotInventedInConfirmedBand() {
        val t = listOf(st("A", 1, 10, 20), st("B", 2, 10, 20), st("C", 3, 10, 5), st("D", 4, 10, 4))
        val p = LeagueResults.project(LeagueResults.adjust(t, emptyList()), "B", 10, true)!!     // fim da liga (0 jogos a disputar): A empata com B em pontos
        assertEquals(1, p.confirmedBest)      // empate não é "acima": melhor caso 1º
        assertEquals(2, p.confirmedWorst)     // mas pode estar abaixo do empatado: pior caso conta o empate contra mim
        assertEquals(1, p.ifWin!!)
        assertTrue(p.effectNote!!.contains("desempate desconhecido"))
    }

    @Test fun mixedRoundsAreFlagged() {
        val t = listOf(st("A", 1, 10, 20), st("B", 2, 12, 20), st("C", 3, 10, 5), st("D", 4, 10, 4))
        val adj = LeagueResults.adjust(t, emptyList())
        assertTrue(adj.mixedRounds)
        assertNull(LeagueResults.project(adj, "A", 20, true)!!.ifWin)
    }

    @Test fun radarWithThreeGamesShowsThree() {
        val g = listOf(
            LeagueResults.Stored(8, "Red Bull Bragantino", "Bahia", 2, 0, 1, 0L), LeagueResults.Stored(9, "Santos", "Red Bull Bragantino", 1, 1, 1, 0L),
            LeagueResults.Stored(11, "Flamengo", "Red Bull Bragantino", 1, 2, 1, 0L), LeagueResults.Stored(11, "Palmeiras", "Santos", 0, 0, 1, 0L)
        )
        val r = LeagueResults.radar(g, "Red Bull Bragantino", table, 5L)!!
        assertEquals(3, r.games.size)
        assertEquals(11, r.games[0].round); assertFalse(r.games[0].home); assertEquals(2, r.games[0].gf); assertEquals(1, r.games[0].ga)
        assertEquals(1, r.games[0].oppRank?.minus(1))             // Flamengo = 2º na referência
        assertNotNull(r.homeAvg); assertNotNull(r.awayAvg); assertNotNull(r.reference)
        assertEquals(0, LeagueResults.radar(emptyList(), "Red Bull Bragantino", table, 5L)!!.games.size)   // sem jogos individuais: só a campanha da tabela
    }

    // ---------------------------------------------------------------- batalha
    private fun battleScreen(me: String = "uzMitaofc", opp: String = "IRON WOLVES FC", meLeft: Boolean = true, aggMe: Int = 78, aggOpp: Int = 60): OcrResult {
        val l = if (meLeft) me else opp; val r = if (meLeft) opp else me
        val agg = if (meLeft) "$aggMe - $aggOpp" else "$aggOpp - $aggMe"
        return Fx.ocr(
            Fx.line("Jornada 10", 0.5f, 0.10f, 0.2f), Fx.line(l, 0.2f, 0.2f, 0.2f), Fx.line(agg, 0.5f, 0.2f, 0.1f), Fx.line(r, 0.8f, 0.2f, 0.2f),
            Fx.line(if (meLeft) "uzMitaofc 4 11" else "Lobo1 4 9", 0.25f, 0.4f, 0.3f), Fx.line(if (meLeft) "Lobo1 4 9" else "uzMitaofc 4 11", 0.75f, 0.4f, 0.3f),
            Fx.line("Colega2 3 7", 0.25f, 0.46f, 0.3f), Fx.line("Lobo2 3 5", 0.75f, 0.46f, 0.3f)
        )
    }

    @Test fun battleAggregate78x60IsNotSummedWithRows() {
        val p = Battle.parse(battleScreen())!!
        assertEquals(78, p.aggL); assertEquals(60, p.aggR); assertEquals(10, p.round)
        assertTrue(p.rows.size >= 3)
        val j = Battle.store(null, p, 10, "uzMitaofc", 1L)
        val j2 = Battle.store(j, p, 10, "uzMitaofc", 2L)           // reler a tela
        val s = Battle.state(j2, null, "uzMitaofc")!!
        assertEquals(78, s.aggA); assertEquals(60, s.aggB); assertEquals("A", s.mine)
        assertEquals(18, s.contribA)                                // 11 + 7 das linhas, só como contribuição da rodada
        assertEquals(14, s.contribB)
        assertEquals(11, s.myRow!!.pts)
        // lista parcial (sem cabeçalho, sem linhas): agregado confirmado fica
        val partial = Battle.parse(Fx.ocr(Fx.line("Jornada 10", 0.5f, 0.10f, 0.2f), Fx.line("uzMitaofc", 0.2f, 0.2f, 0.2f), Fx.line("78 - 60", 0.5f, 0.2f, 0.1f), Fx.line("IRON WOLVES FC", 0.8f, 0.2f, 0.2f)))!!
        val s3 = Battle.state(Battle.store(j2, partial, 10, "uzMitaofc", 3L), null, "uzMitaofc")!!
        assertEquals(78, s3.aggA); assertEquals(18, s3.contribA)
    }

    @Test fun myTeamOnTheRightAndTeamChange() {
        val p = Battle.parse(battleScreen(meLeft = false))!!
        assertEquals('R', Battle.mySide("uzMitaofc", p.left, p.right))
        assertEquals('R', Battle.mySide("  UZMITAOFC ", p.left, p.right))        // caixa e espaços
        val j = Battle.store(null, p, 10, "uzMitaofc", 1L)
        val s = Battle.state(j, null, "uzMitaofc")!!
        assertEquals(60, s.aggA); assertEquals(78, s.aggB)                       // A = esquerda da 1ª leitura (IRON WOLVES)
        assertEquals("B", s.mine)
        // troca de equipe nas próximas leituras: o histórico desta batalha não é reatribuído
        val j2 = Battle.store(j, p, 10, "IRON WOLVES FC", 2L)
        assertEquals("B", Battle.state(j2, null, "IRON WOLVES FC")!!.mine)
    }

    @Test fun ambiguousOrUnknownNameDoesNotGuess() {
        assertNull(Battle.mySide("uzMitaofc", "uzMitaofc A", "uzMitaofc B"))
        assertNull(Battle.mySide("outro", "uzMitaofc", "IRON WOLVES FC"))
        assertNull(Battle.mySide("", "uzMitaofc", "IRON WOLVES FC"))
    }

    @Test fun aggregateNeverDecreasesAcrossRoundsAndBattlesAreIsolated() {
        val p10 = Battle.parse(battleScreen())!!
        val j = Battle.store(null, p10, 10, "uzMitaofc", 1L)
        val low = Battle.parse(battleScreen(aggMe = 50, aggOpp = 40))!!
        val j2 = Battle.store(j, low, 11, "uzMitaofc", 2L)                        // J11 com agregado menor que o da J10: leitura ruim, descartada
        assertEquals(10, Battle.state(j2, null, "uzMitaofc")!!.aggRound)
        val other = Battle.parse(battleScreen(opp = "OUTROS FC"))!!
        val j3 = Battle.store(j2, other, 10, "uzMitaofc", 3L)
        assertEquals(2, j3.length())                                              // batalhas separadas por par de equipes
        assertEquals("empate basta", Battle.outlook(70, 68, 10, 5, 4))
        assertNull(Battle.outlook(78, 60, null, null, null))                      // sem pontos restantes conhecidos: não calcula
    }

    // ---------------------------------------------------------------- widget
    private fun sf(v: String, at: Long) = StoredField(v, 0.9, at)
    @Test fun widgetFourSlotsStaleEmptyAndBattle() {
        val now = 100L * 3600_000L; val kick = now + 3 * 3600_000L
        val s1 = SlotWidget.buildRow(1, mapOf(K.TEAM to sf("Cruzeiro", now - 1000), K.RIVAL_TEAM to sf("Bahia", now - 1000), K.MATCH_AT to sf(kick.toString(), now - 1000)), true, null, null, now)
        assertTrue(s1.title.contains("Cruzeiro")); assertTrue(s1.line.contains("Bahia")); assertTrue(s1.status.contains("tática pronta")); assertFalse(s1.stale); assertEquals(4, s1.tab); assertEquals(1, s1.slot)
        val s2 = SlotWidget.buildRow(2, mapOf(K.TEAM to sf("Santos", now - 20 * 3600_000L), K.RIVAL_TEAM to sf("Bahia", now - 20 * 3600_000L), K.MATCH_AT to sf(kick.toString(), now - 20 * 3600_000L)), false, null, null, now)
        assertTrue(s2.stale); assertTrue(s2.status.contains("antigo")); assertTrue(s2.status.contains("aguardando leitura do rival")); assertEquals(1, s2.tab)
        val s3 = SlotWidget.buildRow(3, emptyMap(), false, null, null, now)
        assertEquals("S3 · sem clube", s3.title)
        val s4 = SlotWidget.buildRow(4, mapOf(K.TEAM to sf("uzMitaofc", now), K.COMP_TYPE to sf("Batalha", now)), false, "IRON 2×1", "78×60", now)
        assertTrue(s4.status.contains("78×60")); assertTrue(s4.status.contains("último")); assertEquals(0, s4.tab); assertEquals(4, s4.slot)
        val vazio = SlotWidget.buildRow(3, mapOf(K.TEAM to sf("X", now), K.SLOT_STATE to sf("vazio", now)), false, null, null, now)
        assertTrue(vazio.title.contains("sem clube"))
    }

    @Test fun dedupeMergesOcrVariantsOfSameClub() {
        val rows = listOf(League.Row("SG Sonnenhof Großaspach", null, 5, at = 1L), League.Row("SG Sonnenhof Grossaspach", null, 5, at = 9L), League.Row("Liefering", null, 1, at = 2L))
        val d = League.dedupe(rows)
        assertEquals(2, d.size)
        assertEquals("SG Sonnenhof Grossaspach", d.first { it.club.startsWith("SG") }.club)    // fica a leitura mais recente
    }

    @Test fun skippedReasonsAreSeparated() {
        val g = listOf(LeagueResults.Stored(38, "Flamengo", "Red Bull Bragantino", 1, 2, 1, 0L), LeagueResults.Stored(11, "Clube Fantasma", "Bahia", 1, 0, 1, 0L))
        val a = LeagueResults.adjust(table, g)
        assertEquals(1, a.roundGap); assertEquals(38, a.maxSkippedRound); assertEquals(1, a.unmatched)
        val p = LeagueResults.project(a, "Flamengo", 38, true)!!
        assertTrue(p.limits.any { it.contains("não achei na classificação") } && p.limits.any { it.contains("J38") })
    }

    @Test fun radarWithoutGamesStillShowsLeagueCampaign() {
        val r = LeagueResults.radar(emptyList(), "Bahia", table, 5L)!!
        assertEquals(0, r.games.size); assertEquals(10, r.campaign!!.j)
        assertNull(LeagueResults.radar(emptyList(), "Clube Fantasma", table, 5L))
    }

    @Test fun widgetCountdownText() {
        val now = 1_000_000_000L
        assertEquals("em 35 min", SlotWidget.until(now + 35 * 60_000L, now)); assertEquals("em 3 h", SlotWidget.until(now + 3 * 3600_000L + 5000, now))
        assertEquals("em 2 d", SlotWidget.until(now + 50 * 3600_000L, now)); assertEquals("", SlotWidget.until(null, now))
    }

    @Test fun widgetHeroIsSoonestConfirmedGame() {
        val now = 100L * 3600_000L
        fun row(slot: Int, at: Long?) = SlotWidget.buildRow(slot, mapOf(K.TEAM to sf("T$slot", now), K.RIVAL_TEAM to sf("R$slot", now), K.MATCH_AT to sf((at ?: 0L).toString(), now)), false, null, null, now)
        val rows = listOf(row(1, now + 9 * 3600_000L), row(2, now + 2 * 3600_000L), row(3, null), SlotWidget.buildRow(4, emptyMap(), false, null, null, now))
        assertEquals(2, SlotWidget.hero(rows)!!.slot)
        assertEquals("em 2 h", rows[1].`when`)
        assertEquals(3, SlotWidget.hero(listOf(rows[3], rows[2]))!!.slot)       // sem horário confirmado: primeiro slot com clube
        assertNull(SlotWidget.hero(listOf(rows[3])))
    }
}
