package com.osmaicoach.collector

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Decisão tática na bolha (MANTENHA/AJUSTE/FALTA), identidade da partida e aba Gráficos. */
class Round7Test {
    private val NOW = 1_000_000_000_000L
    private val AT = NOW + 2 * 3_600_000L
    private fun sf(v: String, conf: Double = 0.9, at: Long = NOW - 300_000L) = StoredField(v, conf, at)

    private fun fields(round: String = "5", rival: String = "Rival FC", type: String = "Batalha", at: Long = AT) = mapOf(
        K.MATCH_AT to sf(at.toString()), K.ROUND to sf(round), K.RIVAL_TEAM to sf(rival), K.COMP_TYPE to sf(type), K.COMPETITION to sf("Batalha de Grupos"),
        K.RIVAL_HUMAN to sf("Sim", 0.9), K.RIVAL_FORMATION to sf("4-3-3 A"), K.RIVAL_PLAN to sf("Jogar pelas alas"), K.RIVAL_STRENGTH to sf("80"), K.MY_STRENGTH to sf("78"),
        K.HOME to sf("Casa"), K.RIVAL_NICK to sf("Mestre")
    )

    private fun plan(round: Int = 5, rival: String = "Rival FC", at: Long = AT, form: String = "4-3-3 A", pressure: Int = 64) = JSONObject()
        .put("formation", form).put("playStyle", "Jogar pelas alas").put("pressure", pressure).put("mentality", 72).put("tempo", 55).put("marking", "À zona")
        .put("offside", "Sim").put("tackle", "Normal").put("advAttack", "Atacar apenas").put("advMid", "Equilibrado").put("advDef", "Manter posições")
        .put("matchAt", at).put("forRound", round).put("rival", rival)

    private fun played(plan: JSONObject, carried: Boolean = false, override: Map<String, String> = emptyMap(), skip: Set<String> = emptySet()): JSONObject {
        val fl = JSONObject()
        for (k in PlayedTactic.FIELDS) {
            if (k in skip) continue
            val v = override[k] ?: plan.opt(k).toString()
            fl.put(k, JSONObject().put("v", v).put("src", if (carried) "última leitura" else "tela").put("at", NOW).apply { if (carried) put("carried", true) })
        }
        return JSONObject().put("id", "x").put("fields", fl)
    }

    private fun inp(slot: Int? = 4, f: Map<String, StoredField> = fields(), plan: JSONObject? = plan(), played: JSONObject? = null, gen: Boolean = false, sig: String? = null, hist: List<JSONObject> = emptyList()) =
        TacticDecision.In(slot, f, plan, NOW - 1000L, played, gen, sig, null, NOW, hist)

    // ------------------------------------------------------------------ três estados da bolha
    @Test fun keepWhenMountedEqualsRecommendedAndAllFieldsReadNow() {
        val p = plan()
        val v = TacticDecision.decide(inp(played = played(p)))
        assertEquals(TacticDecision.State.MANTENHA, v.state)
        assertTrue(v.title.startsWith("S4 • vs Rival FC")); assertTrue(v.title.contains("Batalha"))
        assertEquals(11, v.table!!.size)
    }

    @Test fun adjustShowsOnlyDifferingFieldsAsReadToRecommended() {
        val p = plan()
        val v = TacticDecision.decide(inp(played = played(p, override = mapOf("pressure" to "40", "tackle" to "Agressivo"))))
        assertEquals(TacticDecision.State.AJUSTE, v.state)
        assertEquals(setOf("Pressão", "Desarme"), v.changes.map { it.label }.toSet())
        val pr = v.changes.first { it.label == "Pressão" }; assertEquals("40", pr.read); assertEquals("64", pr.rec)
    }

    @Test fun missingConfirmationKeepsRecommendationConsultableAndSaysNotCompared() {
        val v = TacticDecision.decide(inp(played = null))
        assertEquals(TacticDecision.State.FALTA, v.state)
        assertTrue(v.consultable); assertNotNull(v.table)
        assertTrue(v.action!!.contains("Abra Tática")); assertTrue(v.lines.any { it.contains("não comparada") })
    }

    @Test fun partialReadMatchingStillNeedsConfirmation() {
        val p = plan()
        val v = TacticDecision.decide(inp(played = played(p, skip = setOf("advDef", "marking"))))
        assertEquals(TacticDecision.State.FALTA, v.state)
        assertTrue(v.lines.first().contains("falta ler"))
    }

    @Test fun inheritedReadingNeverCountsAsMounted() {
        val p = plan()
        val v = TacticDecision.decide(inp(played = played(p, carried = true)))
        assertEquals(TacticDecision.State.FALTA, v.state)            // herdado do jogo anterior ≠ montado neste jogo
        assertTrue(v.changes.isEmpty())
    }

    @Test fun suggestedValueIsNotUsedAsMounted() {
        // sem nenhuma leitura própria, a sugerida NÃO vira "valor atual": nada de MANTENHA
        assertNotEquals(TacticDecision.State.MANTENHA, TacticDecision.decide(inp(played = JSONObject().put("fields", JSONObject()))).state)
    }

    @Test fun unknownSlotAsksToOpenPregameAndNeverPicksAnother() {
        val v = TacticDecision.decide(inp(slot = null))
        assertEquals(TacticDecision.State.SLOT_DESCONHECIDO, v.state); assertNull(v.table); assertNull(v.slot)
        assertTrue(v.lines.first().contains("Pré-jogo"))
    }

    @Test fun roundChangeDoesNotShowOldPlanAsCurrent() {
        val old = plan(round = 5, at = AT)
        val v = TacticDecision.decide(inp(f = fields(round = "6", at = AT + 30 * 3_600_000L), plan = old, played = played(old)))
        assertEquals(TacticDecision.State.FALTA, v.state); assertFalse(v.consultable); assertNull(v.table)
    }

    @Test fun differentRivalMakesPlanInvalid() {
        val v = TacticDecision.decide(inp(f = fields(rival = "Outro SC"), plan = plan(rival = "Rival FC")))
        assertFalse(v.consultable)
    }

    @Test fun newReadingShowsRecomputingStateNotRevised() {
        val oldSig = TacticDecision.sigOf(fields().plus(K.RIVAL_PLAN to sf("Jogo de passe")))      // a geração anterior viu OUTRO estilo
        val v = TacticDecision.decide(inp(sig = oldSig, played = played(plan())))
        assertTrue(v.recomputing); assertEquals(TacticDecision.State.FALTA, v.state)
        assertTrue(v.lines.first().contains("anterior")); assertTrue(v.changes.isEmpty())
    }

    @Test fun repeatedReadingAndStrengthJitterDoNotRecompute() {
        val sig = TacticDecision.sigOf(fields())
        assertTrue(TacticDecision.sigFresh(sig, fields()))
        assertTrue(TacticDecision.sigFresh(sig, fields().plus(K.RIVAL_STRENGTH to sf("81"))))       // força ±1 não conta
        assertFalse(TacticDecision.sigFresh(sig, fields().plus(K.RIVAL_FORMATION to sf("4-4-2 B"))))
    }

    @Test fun twoSlotsNeverMix() {
        val a = TacticDecision.decide(inp(slot = 1, f = fields(rival = "Alfa")))
        val b = TacticDecision.decide(inp(slot = 2, f = fields(rival = "Beta")))
        assertTrue(a.title.startsWith("S1 • vs Alfa")); assertTrue(b.title.startsWith("S2 • vs Beta"))
    }

    @Test fun rematchOnlyWithReliableHumanAndShowsWhatHeUsedVsToday() {
        val h = JSONObject().put("rivalNick", "Mestre").put("result", "D").put("round", 2).put("rivalFormationRead", "4-4-2 B").put("rivalStyleRead", "Jogo de passe").put("genAt", 5L)
        val v = TacticDecision.decide(inp(hist = listOf(h), played = played(plan())))
        assertTrue(v.lines.any { it.startsWith("Revanche") && it.contains("4-4-2 B") && it.contains("confirma") })
        val bot = fields().plus(K.RIVAL_HUMAN to sf("Não", 0.95)).minus(K.COMP_TYPE)
        assertTrue(TacticDecision.decide(inp(f = bot, hist = listOf(h), played = played(plan()))).lines.none { it.startsWith("Revanche") })
    }

    @Test fun chartFilterOnlyHasConfirmedData() {
        val j = JSONObject(TacticDecision.chartFilter(4, fields(), "atual"))
        assertEquals("Batalha", j.getString("mode")); assertTrue(j.getBoolean("home")); assertEquals("Jogar pelas alas", j.getString("rivalStyle"))
        val old = JSONObject(TacticDecision.chartFilter(4, fields(), "antiga"))
        assertFalse(old.has("rivalStyle")); assertFalse(old.has("rivalFormation"))              // leitura antiga não preenche filtro
    }

    // ------------------------------------------------------------------ identidade da partida
    @Test fun cupAndLeagueAgainstSameRivalHaveDifferentIds() {
        val league = fields(type = "Liga"); val cup = fields(type = "Copa"); val battle = fields(type = "Batalha")
        assertEquals(3, setOf(PlayedTactic.matchId(1, league), PlayedTactic.matchId(1, cup), PlayedTactic.matchId(1, battle)).size)
        assertEquals(PlayedTactic.matchId(1, "batalha de grupos", null, 5, "Rival FC").let { it.replace("batalha de grupos", "batalha de grupos") }, PlayedTactic.matchId(1, league).replace("|", "|"))   // Liga mantém o id antigo
        assertNotEquals(PlayedTactic.matchId(1, fields()), PlayedTactic.matchId(2, fields()))     // outro slot
    }

    @Test fun isolatedDivergenceDoesNotConfirmWholeConfiguration() {
        val sug = plan()
        val partial = played(sug, override = mapOf("pressure" to "40"), skip = setOf("tackle", "tempo"))
        val t = JSONObject(sug.toString())
        val v = PlayedTactic.applyToLog(t, partial)
        assertEquals(PlayedTactic.ALTEROU, v.usage); assertFalse(t.optBoolean("appliedFull"))
        assertFalse(PlayedTactic.fullyConfirmed(t))                         // credita só com a configuração inteira lida
        val full = JSONObject(sug.toString()); PlayedTactic.applyToLog(full, played(sug, override = mapOf("pressure" to "40")))
        assertTrue(PlayedTactic.fullyConfirmed(full)); assertEquals(40, full.getInt("pressure"))
    }

    // ------------------------------------------------------------------ gráficos
    private fun tlog(round: Int, res: String, pressure: Int = 60, human: Boolean = true, battle: Boolean = true, shots: Boolean = false, style: String? = "Jogar pelas alas"): JSONObject {
        val j = plan(round = round).put("pressure", pressure).put("result", res).put("human", human).put("battle", battle).put("round", round).put("rival", "R$round")
            .put("usage", PlayedTactic.SEGUIU).put("myStrength", 80).put("rivalStrength", 79)
        if (style != null) j.put("rivalStyleRead", style)
        if (shots) j.put("myShots", 12).put("oppShots", 5)
        return j
    }
    private fun match(round: Int, mine: Int, opp: Int, res: String) = MatchEntity(4, "m$round", "J$round", round, null, null, true, mine, opp, res, "R$round", null, 0L)
    private fun raw(j: JSONObject, arch: Boolean = false) = SlotCharts.RawLog("tlog_R" + j.optInt("round"), j, arch)

    @Test fun sameFormationDifferentSlidersAreSeparatePlans() {
        val logs = listOf(1 to 60, 2 to 60, 3 to 60, 4 to 85, 5 to 85, 6 to 85).map { (r, p) -> raw(tlog(r, if (r % 2 == 0) "V" else "E", pressure = p)) }
        val g = SlotCharts.build("Batalha", (1..6).map { match(it, 1, 0, if (it % 2 == 0) "V" else "E") }, logs)
        val pc = SlotCharts.plans(g)
        assertEquals(2, pc.rows.size); assertTrue(pc.comparable); assertNotNull(pc.best)
    }

    @Test fun fewGamesNeverElectAWinner() {
        val g = SlotCharts.build("Batalha", (1..3).map { match(it, 2, 0, "V") }, (1..3).map { raw(tlog(it, "V")) })
        val pc = SlotCharts.plans(g)
        assertFalse(pc.comparable); assertNull(pc.best); assertTrue(pc.note.startsWith("Poucos jogos para comparar"))
    }

    @Test fun botsAndUnconfirmedAreOutOfPlanComparisonButStayInScoreAndCoverage() {
        val bot = tlog(1, "V", human = false); val unc = tlog(2, "D").put("usage", PlayedTactic.NAO)
        val g = SlotCharts.build("Batalha", listOf(match(1, 2, 0, "V"), match(2, 0, 1, "D")), listOf(raw(bot), raw(unc)))
        assertTrue(SlotCharts.plans(g).rows.isEmpty())
        val cv = SlotCharts.coverage(g)
        assertEquals(2, cv.total); assertEquals(2, cv.withScore); assertEquals(0, cv.inComparison); assertEquals(2, cv.reasons.size)
        assertEquals(2, SlotCharts.rounds(g).size)                              // placar oficial/pontos continuam
    }

    @Test fun missingShotsAreGapsNeverZero() {
        val g = SlotCharts.build("Liga", listOf(match(1, 1, 0, "V")), listOf(raw(tlog(1, "V", battle = false))))
        assertNull(g[0].shotsFor); assertFalse(g[0].hasStats); assertEquals(0, SlotCharts.coverage(g).withStats)
        val g2 = SlotCharts.build("Liga", listOf(match(1, 1, 0, "V")), listOf(raw(tlog(1, "V", battle = false, shots = true))))
        assertEquals(12, g2[0].shotsFor); assertEquals(1, SlotCharts.coverage(g2).withStats)
    }

    @Test fun styleChartKeepsUnknownStyleOut() {
        val g = SlotCharts.build("Batalha", (1..3).map { match(it, 1, 0, "V") }, listOf(raw(tlog(1, "V", style = "Jogar pelas alas")), raw(tlog(2, "V", style = "Jogo de passe")), raw(tlog(3, "V", style = null))))
        val sc = SlotCharts.styles(g)
        assertEquals(setOf("Jogar pelas alas", "Jogo de passe"), sc.rows.map { it.style }.toSet()); assertEquals(1, sc.unknown)
    }

    @Test fun filtersNeverMixBattleLeagueCupOrEditions() {
        val cupMatch = match(7, 1, 0, "V").copy(label = "Copa • Oitavos")
        val g = SlotCharts.build("Liga", listOf(match(1, 1, 0, "V"), cupMatch), emptyList()) +
            SlotCharts.build("Liga", emptyList(), listOf(raw(tlog(9, "D", battle = false), arch = true)))
        assertEquals(setOf("Liga", "Copa"), SlotCharts.apply(g, SlotCharts.Filter()).map { it.mode }.toSet())
        assertEquals(1, SlotCharts.apply(g, SlotCharts.Filter(mode = "Copa")).size)
        assertEquals(1, SlotCharts.apply(g, SlotCharts.Filter(archived = true)).size)          // edição anterior separada
        assertEquals(0, SlotCharts.apply(g, SlotCharts.Filter(home = false)).count { it.home == true })
    }

    @Test fun bubbleFilterOnlyFillsConfirmedFields() {
        val f = SlotCharts.Filter.fromJson("""{"slot":4,"mode":"Batalha","home":true}""")!!
        assertEquals("Batalha", f.mode); assertEquals(true, f.home); assertNull(f.rivalStyle); assertNull(f.bucket)
        assertNull(SlotCharts.Filter.fromJson(null))
    }

    @Test fun oneMatchWithOneLogIsOneGame() {
        val g = SlotCharts.build("Batalha", listOf(match(3, 2, 1, "V")), listOf(raw(tlog(3, "V"))))
        assertEquals(1, g.size)
    }

    @Test fun emptySlotHasNoGames() {
        assertTrue(SlotCharts.build("Liga", emptyList(), emptyList()).isEmpty())
    }

    // ------------------------------------------------------------------ navegação
    @Test fun chartsTabSitsNextToHistoryWithoutChangingOldIds() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 9, 7, 8), SlotNav.ORDER)
        assertEquals(6, SlotNav.ORDER[SlotNav.ORDER.indexOf(SlotNav.CHARTS) - 1])
        assertEquals(setOf(0, 1, 2, 3, 4, 5, 6, 7, 8), SlotNav.ORDER.filter { it != SlotNav.CHARTS }.toSet())
    }
}
