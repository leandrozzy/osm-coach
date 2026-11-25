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

    private fun sum(ready: Boolean = false, stale: Boolean = false, gaps: Boolean = false, mounted: Boolean = false) =
        SlotSummary(slot = 3, title = "S3", known = true, competition = null, compType = null, roundDone = null, roundTotal = null, rival = null, human = null, pct = 100, updated = 0L,
            tacticReady = ready, tacticStale = stale, rivalGaps = gaps, mounted = mounted)

    @Test fun widgetStatusFollowsSameRuleAsTodayCard() {
        val now = 100L * 3600_000L; val kick = now + 3_600_000L
        val f = mapOf(K.TEAM to sf("Red Bull Bragantino", now), K.RIVAL_TEAM to sf("Atlético Paranaense", now), K.MATCH_AT to sf(kick.toString(), now))
        // rival lido e tática ainda não gerada: NÃO diz "aguardando leitura do rival"
        assertTrue(SlotWidget.buildRow(3, f, false, null, null, now, sum()).short.contains("gerando a tática"))
        assertTrue(SlotWidget.buildRow(3, f, false, null, null, now, sum(gaps = true)).short.contains("reler Pré-jogo"))
        val ok = SlotWidget.buildRow(3, f, true, null, null, now, sum(ready = true, mounted = true))
        assertTrue(ok.short.contains("pronta e montada")); assertEquals(SlotWidget.STATE_OK, ok.state); assertEquals(4, ok.tab)
        assertEquals(SlotWidget.STATE_WAIT, SlotWidget.buildRow(3, f, true, null, null, now, sum(ready = true, stale = true)).state)   // tática velha não é "pronta"
    }

    @Test fun mountedBeatsStaleMountDiffAndChoosesLayoutBySize() {
        val now = 100L * 3600_000L; val kick = now + 3_600_000L
        val f = mapOf(K.TEAM to sf("Red Bull Bragantino", now), K.RIVAL_TEAM to sf("Atlético Paranaense", now), K.MATCH_AT to sf(kick.toString(), now))
        val s = SlotSummary(slot = 3, title = "S3", known = true, competition = null, compType = null, roundDone = "37", roundTotal = "43", rival = null, human = null, pct = 100, updated = 0L,
            tacticReady = true, mounted = true, mountDiff = listOf("Desarme Cuidadoso"), myStr = 106, rivalStr = 108, myForm = "VVVEV", home = true)
        val r = SlotWidget.buildRow(3, f, true, null, null, now, s)
        assertTrue(r.short.contains("pronta e montada"))                    // conferida no OSM: a diferença antiga não vale mais
        assertEquals(106, r.strMe); assertEquals("J38/43", r.round); assertEquals(true, r.home); assertEquals("VVVEV", r.formMe)
    }

    @Test fun nameVariantsAreMergedAndCoverageSaysWhatIsMissing() {
        // 4 clubes; J1 lida duas vezes com grafias diferentes (23 jogos no print do usuário vinha disso); J3 sem leitura; J2 incompleta
        val g = listOf(
            LeagueResults.Stored(1, "Atlético Paranaense", "Bahia", 1, 0, 2, 1L), LeagueResults.Stored(1, "Atletico Paranaense", "Bahia", 1, 0, 1, 1L),
            LeagueResults.Stored(1, "Santos", "Palmeiras", 2, 2, 1, 1L), LeagueResults.Stored(1, "Santos", "Palmeiras ", 2, 2, 1, 1L),
            LeagueResults.Stored(2, "Bahia", "Santos", 0, 1, 1, 1L), LeagueResults.Stored(4, "Palmeiras", "Atlético Paranaense", 3, 1, 1, 1L), LeagueResults.Stored(4, "Bahia", "Santos", 1, 1, 1, 1L)
        )
        val (c, clubs) = LeagueResults.canonicalize(g)
        assertEquals(4, clubs); assertEquals(5, c.size); assertEquals(2, c.count { it.round == 1 })
        val comp = "liga|6"
        var root: JSONObject? = null
        for (x in g) root = LeagueResults.store(root, comp, x.round, listOf(LeagueResults.Game(x.home, x.away, x.gh, x.ga)), emptyList(), 1L).first
        val cov = LeagueResults.coverage(root, comp, null, played = 4)!!
        assertEquals(2, cov.expectedPerRound); assertEquals(listOf(3), cov.missing); assertEquals(listOf(2), cov.partial); assertTrue(cov.over.isEmpty())
    }

    @Test fun recruitingBattleCardIsASlotWithoutCompetition() {
        // S4 = "BATALHA DE GRUPOS / A RECRUTAR MEMBROS…" (print de 24/11): sem jornada; antes virava competição ativa e o S4 voltava a aparecer na Agenda/Hoje
        val lines = Fx.hubLines().filter { !it.text.contains("3/10") && !it.text.contains("UZM VS BAY") && !it.text.contains("BATALHA DE GRUPOS") } +
            listOf(Fx.line("BATALHA DE GRUPOS", 0.795f, 0.845f, 0.13f), Fx.line("A RECRUTAR MEMBROS...", 0.795f, 0.871f, 0.15f))
        val cards = Parsers.hub(Fx.ocr(*lines.toTypedArray())).hubCards
        val s4 = cards.firstOrNull { it.slot == 4 }
        assertNotNull(s4); assertEquals("procurando", s4!!.state)
    }

    // ---- layout real do jogo (prints de 24/11): nome em cima, apelido do treinador embaixo, placar no meio; copa em cartões
    @Test fun leagueRowWithLongNickPicksClubNameNotNick() {
        val o = Fx.ocr(
            Fx.line("Casa", 0.03f, 0.13f), Fx.line("Jornada 12", 0.5f, 0.13f, 0.1f), Fx.line("Fora", 0.97f, 0.13f),
            Fx.line("Red Bull Bragantino", 0.15f, 0.248f, 0.15f), Fx.line("leandrozzy", 0.12f, 0.288f, 0.08f), Fx.line("0 - 1", 0.5f, 0.266f, 0.06f), Fx.line("Atlético Paranaense", 0.88f, 0.248f, 0.15f), Fx.line("Treinador pebas", 0.9f, 0.288f, 0.12f),
            Fx.line("São Paulo", 0.12f, 0.58f, 0.09f), Fx.line("José Reginaldo 04 da Silva", 0.18f, 0.62f, 0.22f), Fx.line("2 - 1", 0.5f, 0.60f, 0.06f), Fx.line("Esporte Clube Vitória", 0.86f, 0.58f, 0.15f), Fx.line("Professor Jean Souza dos Santos", 0.83f, 0.62f, 0.2f)
        )
        val p = LeagueResults.parse(o)!!
        assertEquals(12, p.round)
        assertTrue(p.games.any { it.home == "Red Bull Bragantino" && it.away == "Atlético Paranaense" && it.gh == 0 && it.ga == 1 })
        assertTrue(p.games.any { it.home == "São Paulo" && it.away == "Esporte Clube Vitória" })       // nunca o apelido do treinador
    }

    @Test fun cupScreenIsParsedByPhaseAndNeverAsLeagueRound() {
        val o = Fx.ocr(
            Fx.line("Fase preliminar", 0.5f, 0.16f, 0.12f), Fx.line("18-11-26", 0.5f, 0.205f, 0.06f),
            Fx.line("1 - 0", 0.128f, 0.355f, 0.04f), Fx.line("Mirassol FC (SP)", 0.058f, 0.475f, 0.09f), Fx.line("Chapecoense", 0.196f, 0.475f, 0.07f), Fx.line("UzM Raro", 0.058f, 0.51f, 0.05f),
            Fx.line("3 - 1", 0.62f, 0.355f, 0.04f), Fx.line("Grêmio", 0.555f, 0.475f, 0.06f), Fx.line("Atlético Paranaense", 0.69f, 0.475f, 0.09f), Fx.line("renato gaúcho_718", 0.555f, 0.51f, 0.08f),
            Fx.line("Oitavos-de-final", 0.5f, 0.70f, 0.12f), Fx.line("VS", 0.128f, 0.9f, 0.03f), Fx.line("VS", 0.38f, 0.9f, 0.03f)
        )
        assertNull(LeagueResults.parse(o))                                                    // não é rodada da liga
        val c = LeagueResults.parseCup(o)!!
        assertEquals(2, c.games.size)
        assertTrue(c.games.all { it.first == 1 })                                             // fase preliminar
        assertTrue(c.games.any { it.second.home == "Mirassol FC (SP)" && it.second.away == "Chapecoense" && it.second.gh == 1 })
        assertTrue(c.games.any { it.second.home == "Grêmio" && it.second.away == "Atlético Paranaense" && it.second.gh == 3 })   // "VS" (a definir) fica de fora
        assertEquals(ScreenType.OTHER_OSM, ScreenClassifier.classify(o, true))
    }

    @Test fun tableFromCompleteResultsAndNothingWhenARoundIsMissing() {
        fun g(r: Int, h: String, a: String, x: Int, y: Int) = LeagueResults.Stored(r, h, a, x, y, 1, 1L)
        val full = listOf(g(1, "A", "B", 2, 0), g(1, "C", "D", 1, 1), g(2, "A", "C", 0, 0), g(2, "B", "D", 3, 1))
        val d = LeagueResults.tableFromResults(full)!!
        assertEquals(2, d.rounds)
        val a = d.rows.first { it.club == "A" }; assertEquals(4, a.pts); assertEquals(1, a.rank)
        assertEquals(2, d.rows.first { it.club == "B" }.rank.let { if (it == 2 || it == 3) 2 else it })
        assertNull(LeagueResults.tableFromResults(full.filter { it.round == 1 || it.home != "B" }))      // J2 incompleta: não calcula
        assertNull(LeagueResults.tableFromResults(listOf(g(1, "A", "B", 1, 0), g(3, "A", "C", 1, 0))))   // J2 sem leitura
    }

    @Test fun onlyOneStadiumWorkAtATime() {
        val now = 1_000_000L; val h = 3_600_000L
        val r = Work.onlyRunning(listOf(Pair("Treino", now + 2 * h + 58 * 60_000L), Pair("Capacidade", now + 3 * h + 10 * 60_000L), Pair("Relvado", now + 3 * h + 10 * 60_000L)))
        assertNotNull(r["Treino"]); assertNull(r["Capacidade"]); assertNull(r["Relvado"])            // duas obras com a mesma duração fixa não são obras em andamento
        assertNull(Work.onlyRunning(listOf(Pair("A", now + h), Pair("B", now + h)))["A"])           // todos iguais: nenhum roda
        assertNotNull(Work.onlyRunning(listOf(Pair("A", now + h), Pair("B", null)))["A"])           // um só: vale
    }

    @Test fun splitSeasonArchivesJoinTheCurrentOneOnlyWhenRoundsContinue() {
        fun gs(vararg r: Int) = r.map { Pair("tlog_R$it", JSONObject()) }
        fun ar(tag: String, vararg r: Int) = r.map { Pair("tlog_${tag}_R$it", JSONObject()) }
        val g = mapOf("ATUAL" to gs(12), "X1763000000" to ar("X1763000000", 10, 11), "X1762900000" to ar("X1762900000", 1, 2, 3, 4, 5, 6, 7, 8, 9), "X1762000000" to ar("X1762000000", 1, 2, 3))
        val m = mergeSplitSeasons(g)
        assertEquals(1 + 2 + 9, m["ATUAL"]!!.size)                                                   // J1–J12 de uma temporada só
        assertTrue("X1762000000" in m.keys && m.size == 2)                                            // a temporada que recomeça na J1 continua separada
        val real = mergeSplitSeasons(mapOf("ATUAL" to gs(3), "X1763000000" to ar("X1763000000", 1, 2, 3, 4, 5)))
        assertEquals(1, real["ATUAL"]!!.size)                                                         // rodadas sobrepostas = outra temporada
    }

    @Test fun recruitingAndWaitingBattleHidesTipsLikeFreeSlot() {
        fun sf(v: String) = StoredField(v, 0.9, 1L)
        assertTrue(Hints.slotFree(mapOf(K.SLOT_STATE to sf("procurando"))))
        assertTrue(Hints.slotFree(mapOf(K.SLOT_STATE to sf("vazio"))))
        assertFalse(Hints.slotFree(mapOf(K.SLOT_STATE to sf("ativo"))))
    }
}
