package com.osmaicoach.collector

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Sugerida × jogada, modo de teste do motor em paralelo e prioridade por slot no Hoje (24/11). */
class EvolucaoTest {
    private fun sug() = JSONObject().put("formation", "4-3-3 A").put("playStyle", "Jogar pelas alas").put("pressure", 60).put("mentality", 70)
        .put("tempo", 55).put("marking", "Zona").put("offside", "Não").put("tackle", "Normal").put("advAttack", "Atacar apenas").put("advMid", "Equilibrado").put("advDef", "Defender apenas")

    private fun played(vararg kv: Pair<String, String>, src: String = "tela"): JSONObject {
        val seen = kv.toMap()
        return PlayedTactic.merge(null, "id", 5, "Rival", seen, src, 1000L)
    }
    private fun allSame(): Array<Pair<String, String>> = PlayedTactic.suggestedFields(sug()).map { it.key to it.value }.toTypedArray()

    @Test fun equalAndConfirmedIsFollowed() {
        val v = PlayedTactic.classify(sug(), played(*allSame()))
        assertEquals(PlayedTactic.SEGUIU, v.usage)
    }

    @Test fun onlyPressureChangedIsAltered() {
        val m = PlayedTactic.suggestedFields(sug()).toMutableMap(); m["pressure"] = "20"
        val v = PlayedTactic.classify(sug(), played(*m.map { it.key to it.value }.toTypedArray()))
        assertEquals(PlayedTactic.ALTEROU, v.usage)
        assertEquals(listOf("pressure"), v.diffs.map { it.field })
    }

    @Test fun incompleteReadingIsNotConfirmedNeverFilledFromSuggestion() {
        val m = PlayedTactic.suggestedFields(sug()).filterKeys { it != "tempo" }
        val v = PlayedTactic.classify(sug(), played(*m.map { it.key to it.value }.toTypedArray()))
        assertEquals(PlayedTactic.NAO, v.usage)
        assertTrue("tempo" in v.unknown)
    }

    @Test fun oneProvenDivergenceBeatsUnknowns() {
        val v = PlayedTactic.classify(sug(), played("formation" to "5-3-2 A"))
        assertEquals(PlayedTactic.ALTEROU, v.usage)
    }

    @Test fun oldGameWithoutEvidenceNeverCountsAsFollowed() {
        assertEquals(PlayedTactic.NAO, PlayedTactic.usageOf(JSONObject().put("result", "V")))
        assertTrue(PlayedTactic.attributable(JSONObject().put("result", "V")))       // histórico antigo continua no aprendizado
        assertFalse(PlayedTactic.confirmedOrLegacy(JSONObject().put("usage", PlayedTactic.NAO)))
        assertFalse(PlayedTactic.attributable(JSONObject().put("usage", PlayedTactic.NAO))) // sem confirmação: não atribui à tática sugerida
    }

    @Test fun sameRoundInOtherSlotOrCompetitionIsAnotherMatch() {
        val a = PlayedTactic.matchId(1, "Liga A", "34", 5, "Rival")
        assertNotEquals(a, PlayedTactic.matchId(2, "Liga A", "34", 5, "Rival"))
        assertNotEquals(a, PlayedTactic.matchId(1, "Liga B", "34", 5, "Rival"))
        assertNotEquals(a, PlayedTactic.matchId(1, "Liga A", "38", 5, "Rival"))
        assertEquals(a, PlayedTactic.matchId(1, "liga a", "34", 5, "RIVAL"))
        assertNotEquals(PlayedTactic.kind(a), PlayedTactic.kind(PlayedTactic.matchId(2, "Liga A", "34", 5, "Rival")))
    }

    @Test fun manualConfirmationSurvivesAutomaticRead() {
        val m = PlayedTactic.merge(null, "id", 5, "R", mapOf("pressure" to "60"), "manual", 1L)
        val m2 = PlayedTactic.merge(m, "id", 5, "R", mapOf("pressure" to "60"), "tela", 2L)
        assertEquals("manual", PlayedTactic.cell(m2, "pressure")!!.optString("src"))
    }

    // ---------------------------------------------------------------- motor em paralelo
    @Test fun objectiveWinVsPoints() {
        assertEquals(0.40, EngineLab.objectiveValue(EngineLab.OBJ_WIN, 40, 30), 1e-9)
        assertEquals(1.5, EngineLab.objectiveValue(EngineLab.OBJ_POINTS, 40, 30), 1e-9)   // 3×0,40 + 0,30
        // com empate valendo, o empate mais provável pode superar a vitória: 30V/60E = 1,5 > 40V/10E = 1,3
        assertTrue(EngineLab.objectiveValue(EngineLab.OBJ_POINTS, 30, 60) > EngineLab.objectiveValue(EngineLab.OBJ_POINTS, 40, 10))
        assertTrue(EngineLab.objectiveValue(EngineLab.OBJ_WIN, 30, 60) < EngineLab.objectiveValue(EngineLab.OBJ_WIN, 40, 10))
    }

    @Test fun balancedGameWeighsWorstScenarioMore() {
        // A: média 0,5 mas desmorona (0,1); B: média 0,45 e estável (0,4)
        val a = EngineLab.robust(0.5, 0.1, true, 3); val b = EngineLab.robust(0.45, 0.4, true, 3)
        assertTrue(b > a)
        assertEquals(0.5, EngineLab.robust(0.5, 0.1, true, 1), 1e-9)   // um só cenário: sem penalidade
    }

    @Test fun orangeRedRefereeNeverAllowsAggressiveTackle() {
        for (r in listOf("Rigoroso", "Muito rigoroso")) assertEquals(listOf("Cuidadoso"), Osm.allowedTackles(r).takeLast(1))
        for (r in listOf("Rigoroso", "Muito rigoroso")) assertFalse(Osm.allowedTackles(r).any { it == "Agressivo" || it == "Extremo" })
    }

    private fun sf(v: String, at: Long) = StoredField(v, 0.9, at)
    @Test fun secretTrainingKeepsUnreadInfoUnknown() {
        val now = 100L * 3600_000L
        val f = mapOf(K.RIVAL_SECRET to sf("Sim", now), K.RIVAL_FORMATION to sf("4-4-2", now), K.RIVAL_STRENGTH to sf("80", now))
        val ev = EngineLab.evidence(f, now).associateBy { it.field }
        assertEquals(EngineLab.Status.DESCONHECIDO, ev["Formação"]!!.status)   // nada é inferido sob treino secreto
        assertEquals(EngineLab.Status.ATUAL, ev["Força"]!!.status)
    }

    @Test fun rivalReadBeforeKickoffOfHumanBecomesOldInLastHours() {
        val now = 100L * 3600_000L; val kick = now + 2 * 3600_000L
        val f = mapOf(K.MATCH_AT to sf(kick.toString(), now), K.RIVAL_HUMAN to sf("Sim", now), K.RIVAL_FORMATION to sf("4-4-2", now - 5 * 3600_000L))
        val ev = EngineLab.evidence(f, now).associateBy { it.field }
        assertEquals(EngineLab.Status.ANTIGA, ev["Formação"]!!.status)         // humano a <3 h do jogo: leitura de 5 h é "antiga"
        assertEquals(EngineLab.Status.DESCONHECIDO, ev["Estilo"]!!.status)
    }

    @Test fun diffBucketsAndSnapshotAreStable() {
        assertEquals("força desconhecida", EngineLab.diffBucket(null))
        assertTrue(EngineLab.diffBucket(25).startsWith("favorito forte"))
        val f = mapOf(K.MY_STRENGTH to sf("80", 5L))
        assertEquals(EngineLab.snapshot(f), EngineLab.snapshot(f))                   // mesma assinatura = não congela de novo
        assertNotEquals(EngineLab.snapshot(f), EngineLab.snapshot(mapOf(K.MY_STRENGTH to sf("81", 5L))))
    }

    private fun e(res: String, s: Triple<Int, Int, Int>, l: Triple<Int, Int, Int>, home: Boolean? = true, human: Boolean? = false, b: String = "parelho (−2 a +2)") =
        LabEval.Entry(1, res, home, human, b, s, l)

    @Test fun evalComparesPredictionQualityAndCountsSamples() {
        val es = listOf(
            e("V", Triple(50, 30, 20), Triple(70, 20, 10)), e("D", Triple(50, 30, 20), Triple(20, 30, 50), home = false, human = true),
            e("E", Triple(40, 30, 30), Triple(30, 40, 30), home = true, human = true)
        )
        val r = LabEval.report(es, mapOf("jogo sem resultado" to 2))
        assertEquals(3, r.n)
        assertTrue(r.lab!!.brier < r.std!!.brier)
        assertEquals(2, r.byHome["casa"]); assertEquals(1, r.byHome["fora"])
        assertEquals(2, r.byHuman["humano"]); assertEquals(1, r.byHuman["CPU"])
        assertNull(LabEval.report(emptyList(), emptyMap()).std)                     // sem amostra: sem número
    }

    // ---------------------------------------------------------------- Hoje: uma ação por slot
    private fun it(slot: Int, title: String, urg: Int) = TodoItem(slot, "x", title, "sub", urg, 0)
    @Test fun priorityPerSlotKeepsOneActionMostUrgentFirst() {
        val items = listOf(it(1, "Atualizar: Plantel", 2), it(4, "Revisar tática", 6), it(2, "Pré-jogo", 4), it(4, "Treinador parado", 3), it(1, "Montar tática", 3))
            .sortedWith(compareByDescending<TodoItem> { it.urgency }.thenBy { it.slot })
        val p = priorityPerSlot(items)
        assertEquals(listOf(4, 2, 1), p.map { it.slot })                             // quatro slots navegáveis, um item cada, urgente primeiro
        assertEquals("Revisar tática", p[0].title)
        assertEquals("Montar tática", p.first { it.slot == 1 }.title)
    }
}
