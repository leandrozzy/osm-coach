package com.osmaicoach.collector

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Pacote "fluxo pré-jogo e artes": forma do rival com fonte/cobertura, pedidos de arte por destino, Base isolada por slot, limpeza segura e paridade da tática. */
class Round5FormTest {
    private fun g(r: Int, h: String, a: String, gh: Int, ga: Int) = LeagueResults.Stored(r, h, a, gh, ga, 1, 0L)
    private fun rivalGames(vararg rounds: Int) = rounds.map { r -> g(r, "Rival FC", "Outro $r", 2, 1) }   // todas vitórias do rival

    @Test fun fiveOldScoresDoNotBeatAFresherReading() {
        val old = rivalGames(1, 2, 3, 4, 5)                       // cinco placares, mas só até a rodada 5; já foram jogadas 9
        val withDots = FormRead.rivalForm(old, "Rival FC", 9, "VVD", true, false)!!
        assertEquals("prejogo", withDots.source); assertEquals("VVD", withDots.dots); assertFalse(withDots.stale)
        assertTrue(withDots.note.contains("não são placares confirmados") && withDots.note.contains("rodada 5"))
        val noDots = FormRead.rivalForm(old, "Rival FC", 9, null, false, false)!!
        assertEquals("resultados", noDots.source); assertTrue(noDots.stale); assertTrue(noDots.note.contains("desatualizada") && noDots.note.contains("rodada 5"))
    }

    @Test fun currentConfirmedScoresShowCoverageAndNeverFillGaps() {
        val ok = FormRead.rivalForm(rivalGames(6, 7, 8), "Rival FC", 8, "DDD", true, false)!!
        assertEquals("resultados", ok.source); assertEquals("VVV", ok.dots); assertFalse(ok.stale); assertEquals(8, ok.lastRound)
        assertEquals("3 resultados confirmados, até a rodada 8", ok.note)
        // rodada faltando no meio: só a sequência seguida até a mais recente (4,5,6 e 8 → só o 8)
        val gap = FormRead.rivalForm(rivalGames(4, 5, 6, 8), "Rival FC", 8, null, false, false)!!
        assertEquals(1, gap.dots.length); assertEquals("1 resultado confirmado, até a rodada 8", gap.note)
        // falta a rodada mais recente (9 jogadas, lido até 8): desatualizada, e bolinhas só entram identificadas como tal
        assertTrue(FormRead.rivalForm(rivalGames(6, 7, 8), "Rival FC", 9, null, false, false)!!.stale)
    }

    @Test fun cupAndBattleNeverUseLeagueScoresEvenWithTheSameClubs() {
        var root: JSONObject? = null
        root = LeagueResults.store(root, "Liga|20", 3, listOf(LeagueResults.Game("Rival FC", "Meu FC", 1, 0)), emptyList(), 1L).first
        root = LeagueResults.store(root, "Liga|20", 4, listOf(LeagueResults.Game("Meu FC", "Rival FC", 0, 3)), emptyList(), 1L).first
        root = LeagueResults.store(root, "copa|Liga|20", 2, listOf(LeagueResults.Game("Rival FC", "Meu FC", 0, 5)), emptyList(), 1L).first
        val league = LeagueResults.games(root, "Liga|20"); val cup = LeagueResults.games(root, "copa|Liga|20")
        assertEquals(2, league.size); assertEquals(1, cup.size)
        assertEquals("VV", LeagueResults.formOf(league, "Rival FC"))                         // a goleada de 5×0 da Copa não entra na série da Liga
        // próximo jogo é de Copa/Batalha: ignora a tabela da liga; só bolinhas do Pré-jogo, identificadas
        val cupForm = FormRead.rivalForm(league, "Rival FC", 4, "DE", true, cupOrBattle = true)!!
        assertEquals("prejogo", cupForm.source); assertEquals("DE", cupForm.dots)
        assertNull(FormRead.rivalForm(league, "Rival FC", 4, null, false, cupOrBattle = true))
    }
}

class Round5ArtsTest {
    private fun req(id: String, at: Long, label: String = id, status: String = "pendente") = ArtsRequests.Req(id, 3, "MATCHDAY", label, null, "L14", "Brasileirão", 14, "COPA • Oitavos de final", label, at, status)

    @Test fun twoRequestsReturningOutOfOrderAlwaysAskInsteadOfGuessing() {
        val now = 10_000_000L
        val a = req("match|3|14", now - 5_000, "Copa A"); val b = req("match|3|15", now - 1_000, "Liga B")   // B é o último pedido aberto
        var list = ArtsRequests.upsert(ArtsRequests.upsert(emptyList(), a), b)
        val r = ArtsRequests.route(list, now)
        assertTrue(r is ArtsRequests.Route.Choose)                                            // nunca assume "o último" (B) para a 1ª imagem
        assertEquals(listOf("match|3|15", "match|3|14"), (r as ArtsRequests.Route.Choose).options.map { it.id })
        // o usuário escolheu A primeiro (a imagem de A voltou antes): só A é concluída
        list = ArtsRequests.complete(list, "match|3|14")
        val r2 = ArtsRequests.route(list, now)
        assertTrue(r2 is ArtsRequests.Route.Auto); assertEquals("match|3|15", (r2 as ArtsRequests.Route.Auto).req.id)
        list = ArtsRequests.complete(list, "match|3|15")
        assertTrue(ArtsRequests.route(list, now) is ArtsRequests.Route.None)
    }

    @Test fun noValidDestinationSavesNothingAndExpiredRequestsDoNotCount() {
        val now = 500_000_000L
        assertTrue(ArtsRequests.route(emptyList(), now) is ArtsRequests.Route.None)
        val old = req("x", now - ArtsRequests.TTL_MS - 1)
        assertTrue(ArtsRequests.route(listOf(old), now) is ArtsRequests.Route.None)
        assertTrue(ArtsRequests.route(listOf(req("y", now, status = "concluido")), now) is ArtsRequests.Route.None)
    }

    @Test fun requestsSurviveRestartAndAskingAgainReplacesOnlyThatArt() {
        val a = req("a", 1L); val b = req("b", 2L)
        val saved = ArtsRequests.toJson(ArtsRequests.upsert(ArtsRequests.upsert(emptyList(), a), b))
        val back = ArtsRequests.fromJson(saved)                                               // "reabrir o app"
        assertEquals(listOf("a", "b"), back.map { it.id }); assertEquals("COPA • Oitavos de final", back[0].phase); assertEquals(14, back[0].round); assertEquals("L14", back[0].matchKey)
        val again = ArtsRequests.upsert(back, a.copy(at = 99L))
        assertEquals(2, again.size); assertEquals(99L, again.first { it.id == "a" }.at)
        assertEquals(listOf("b"), ArtsRequests.cancel(again, "a").map { it.id })
    }

    @Test fun twoVersionsOfTheSameArtAreBothKept() {
        fun item(id: String, ver: Int) = Arts.Item(id, 3, "MATCHDAY", "Dia de jogo", 1L, "/x/$id.png", group = "match|3|14", ver = ver, matchKey = "L14", comp = "Brasileirão", round = 14, phase = "COPA • Oitavos de final")
        assertEquals(Pair("match|3|14", 1), Arts.nextVersion(emptyList(), "match|3|14"))
        val v1 = item("match|3|14", 1)
        assertEquals(Pair("match|3|14#v2", 2), Arts.nextVersion(listOf(v1), "match|3|14"))
        val v2 = item("match|3|14#v2", 2)
        assertEquals(Pair("match|3|14#v3", 3), Arts.nextVersion(listOf(v1, v2), "match|3|14"))
        assertEquals(Pair("match|3|15", 1), Arts.nextVersion(listOf(v1, v2), "match|3|15"))     // outra arte não interfere
        assertTrue(v1.id != v2.id && v1.group == v2.group && v1.matchKey == v2.matchKey && v1.round == v2.round)   // mesmo jogo, itens separados
        // item antigo (sem os campos novos) continua válido: grupo = id, versão 1
        val legacy = Arts.Item("old|1|2", 1, "BUY", "Reforço", 1L, "/y.png")
        assertEquals("old|1|2", legacy.group); assertEquals(1, legacy.ver); assertNull(legacy.matchKey)
    }
}

class Round5BaseTest {
    private fun db(): JdbcSql {
        val d = JdbcSql.fresh()
        d.exec("INSERT INTO football_players(id,name,name_key,surname_key,status) VALUES(1,'Thiago Silva','thiago silva','silva','ativo')")
        d.exec("INSERT INTO football_players(id,name,name_key,surname_key,status) VALUES(2,'Bruno Silva','bruno silva','silva','ativo')")
        fun obs(slot: Int, age: Int, pos: String, cap: String) = d.exec("INSERT INTO football_ocr_observations(slot,kind,raw,norm,context,capture_key,at) VALUES(?,?,?,?,?,?,?)",
            arrayOf(slot, "player", "Silva", "silva", JSONObject().put("age", age).put("pos", pos).toString(), cap, 1L))
        obs(1, 25, "MC", "c1"); obs(3, 31, "DC", "c3")
        return d
    }

    @Test fun confirmingOneHomonymDoesNotTouchTheOtherSlot() {
        val d = db()
        WorldCatalog.confirmManualIn(d, "player", "silva", 1L, slot = 3)
        assertEquals(1L, d.long("SELECT COUNT(*) FROM football_osm_entity_mappings WHERE osm_key='silva'"))
        assertEquals(3L, d.long("SELECT slot FROM football_osm_entity_mappings WHERE osm_key='silva'"))
        assertEquals("manual", d.rows("SELECT algo FROM football_osm_entity_mappings WHERE osm_key='silva'")[0][0])
        assertEquals("Thiago Silva", WorldOcr.realPlayerIn(d, 3, "Silva")?.name)
        assertNull(WorldOcr.realPlayerIn(d, 1, "Silva"))                    // idade 25 × 31: outra pessoa, nada herdado só pelo nome curto
        assertEquals(1L, d.long("SELECT COUNT(*) FROM football_validation_history WHERE slot=3 AND action='confirmado'"))
        assertEquals(0L, d.long("SELECT COUNT(*) FROM football_validation_history WHERE slot=1"))
        // o outro slot pode ter o SEU registro, diferente
        WorldCatalog.confirmManualIn(d, "player", "silva", 2L, slot = 1)
        assertEquals("Bruno Silva", WorldOcr.realPlayerIn(d, 1, "Silva")?.name); assertEquals("Thiago Silva", WorldOcr.realPlayerIn(d, 3, "Silva")?.name)
    }

    @Test fun sameAgeAndPositionInAnotherSlotIsTheSamePlayer() {
        val d = db()
        d.exec("UPDATE football_ocr_observations SET context=? WHERE slot=1", arrayOf(JSONObject().put("age", 31).put("pos", "DC").toString()))
        WorldCatalog.confirmManualIn(d, "player", "silva", 1L, slot = 3)
        assertEquals("Thiago Silva", WorldOcr.realPlayerIn(d, 1, "Silva")?.name)
    }

    @Test fun confirmingInASlotWithoutThatTextFailsInsteadOfGuessing() {
        val d = db()
        try { WorldCatalog.confirmManualIn(d, "player", "silva", 1L, slot = 2); fail("deveria recusar") } catch (e: IllegalArgumentException) { }
        assertEquals(0L, d.long("SELECT COUNT(*) FROM football_osm_entity_mappings"))
        assertEquals(listOf(1, 3), WorldCatalog.slotsOf(d, "player", "silva"))
    }

    @Test fun cleanupMarkedDoneOnlyAfterSuccessAndResumesAfterInterruption() {
        val store = HashMap<String, String>()
        var runs = 0
        val get = { k: String -> store[k] ?: "" }; val put = { k: String, v: String -> store[k] = v }
        try { WorldMaint.onceWith(get, put, "clean_v1") { runs++; error("app fechado no meio") }; fail() } catch (e: IllegalStateException) { }
        assertNull(store["clean_v1"])                                                       // interrompida: NÃO ficou "feita"
        assertTrue(WorldMaint.onceWith(get, put, "clean_v1") { runs++ })                    // retomada
        assertEquals("1", store["clean_v1"]); assertEquals(2, runs)
        assertTrue(WorldMaint.onceWith(get, put, "clean_v1") { runs++ }); assertEquals(2, runs)   // já concluída: não repete
    }

    @Test fun atomicCleanupRollsBackWhatItDidWhenItFails() {
        val d = db()
        d.exec("INSERT INTO football_pull_log(kind,key,at,found) VALUES('player','silva',1,0)")
        try { WorldMaint.atomic(d) { d.exec("DELETE FROM football_players"); d.exec("DELETE FROM football_pull_log"); error("falhou depois de apagar") }; fail() } catch (e: IllegalStateException) { }
        assertEquals(2L, d.long("SELECT COUNT(*) FROM football_players")); assertEquals(1L, d.long("SELECT COUNT(*) FROM football_pull_log"))
        assertEquals(2L, d.long("SELECT COUNT(*) FROM football_ocr_observations"))          // observações e mapeamentos não são tocados
    }
}

class Round5TacticParityTest {
    /** Valores gerados com o motor da `main` antes deste pacote (o motor não foi alterado): mesmas entradas, mesmos parâmetros. */
    private fun show(my: Int, rv: Int, rf: String?): String {
        val inp = TacticEngine.Input(EngineTest().squad(), my, rv, null, null, null, null, null, null, emptyList()).copy(rivalFormation = rf)
        val r = TacticEngine.recommend(inp)!!; val t = r.tactic
        return listOf(t.formation, t.playStyle, t.pressure, t.mentality, t.tempo, t.marking, t.offside, t.tackle, t.advAttack, t.advMid, t.advDef, r.diff).joinToString("|")
    }
    @Test fun identicalInputsGiveTheSameRecommendationAsBefore() {
        assertEquals("4-3-3 A|Jogar pelas alas|64|86|68|À zona|Não|Normal|Atacar apenas|Manter posições|Defender atrás|30", show(91, 61, null))
        assertEquals("4-3-3 A|Jogar pelas alas|50|47|64|À zona|Não|Normal|Atacar apenas|Manter posições|Defender atrás|-20", show(70, 90, null))
        assertEquals("4-3-3 A|Jogar pelas alas|50|47|64|À zona|Não|Normal|Atacar apenas|Manter posições|Defender atrás|-20", show(70, 90, "4-3-3 A"))
        assertEquals("4-3-3 B|Jogo de passe|50|55|55|À zona|Não|Normal|Atacar apenas|Manter posições|Defender atrás|0", show(80, 80, null))
        assertEquals("4-3-3 A|Jogar pelas alas|50|47|64|À zona|Não|Normal|Atacar apenas|Manter posições|Defender atrás|-25", show(60, 85, "5-3-2"))
        assertEquals("4-3-3 A|Jogar pelas alas|70|86|65|À zona|Não|Normal|Atacar apenas|Manter posições|Defender atrás|45", show(95, 50, "4-4-2"))
    }
}

class Round5MigrationTest {
    @Test fun oldArtsListWithoutNewFieldsMigratesAndRoundTrips() {
        // formato anterior ao pacote (sem group/ver/matchKey/comp/round/phase)
        val oldJson = """[{"id":"match|3|14","slot":3,"kind":"MATCHDAY","title":"Dia de jogo","at":5,"file":"/a.png"},{"id":"buy|1|silva","slot":1,"kind":"BUY","title":"Reforço","at":4,"file":"/b.png"}]"""
        val items = Arts.parseItems(oldJson)
        assertEquals(2, items.size); assertEquals("match|3|14", items[0].group); assertEquals(1, items[0].ver); assertNull(items[0].round); assertNull(items[0].phase)
        val back = Arts.parseItems(Arts.itemsToJson(items + Arts.Item("match|3|14#v2", 3, "MATCHDAY", "Dia de jogo (v2)", 9, "/c.png", group = "match|3|14", ver = 2, matchKey = "L14", comp = "Liga", round = 14, phase = "COPA • Oitavos de final")))
        assertEquals(3, back.size); assertEquals(2, back[2].ver); assertEquals("L14", back[2].matchKey); assertEquals(14, back[2].round)
        assertEquals(emptyList<Arts.Item>(), Arts.parseItems("lixo{{"))                         // dado corrompido não derruba
    }
}

class Round5TodayTest {
    @Test fun battleSlotComesFirstAmongEqualUrgencyAndOnlyOneActionPerSlot() {
        val items = listOf(TodoItem(1, "🔎", "Reler formação do rival", "x", 4, 1), TodoItem(4, "🔎", "Reler formação do rival", "x", 4, 1), TodoItem(4, "⚡", "Gerar a tática", "x", 2, 4), TodoItem(2, "✅", "Tática pronta", "x", 1, 4))
        val r = priorityPerSlot(items, battleSlots = setOf(4))
        assertEquals(listOf(4, 1, 2), r.map { it.slot })                                         // S4 (Batalha) antes do S1; uma ação por slot
        assertEquals("Reler formação do rival", r.first().title)
        assertEquals(listOf(1, 4, 2), priorityPerSlot(items).map { it.slot })                     // sem Batalha, a ordem de antes
    }
    @Test fun gapTitleNamesTheExactOsmScreen() {
        fun grp(name: String, ok: Boolean, vararg miss: String) = Director.Group(name, "onde", ok, miss.toList())
        assertEquals("Reler formação e estilo de jogo do rival", FormRead.gapTitle(listOf(grp("Pré-jogo (forças e bônus)", true), grp("Análise do rival", false, "formação", "estilo de jogo"), grp("Plantel do rival (setores e valor)", true), grp("Seu plantel (setores)", true))))
        assertEquals("Reler o Pré-jogo (força do rival e árbitro (termômetro))", FormRead.gapTitle(listOf(grp("Pré-jogo (forças e bônus)", false, "força do rival", "árbitro (termômetro)"), grp("Análise do rival", true), grp("Plantel do rival (setores e valor)", true), grp("Seu plantel (setores)", true))))
        assertNull(FormRead.gapTitle(listOf(grp("Pré-jogo (forças e bônus)", true), grp("Análise do rival", true), grp("Plantel do rival (setores e valor)", true), grp("Seu plantel (setores)", false, "setor GOL"))))   // seu plantel não bloqueia a tática do rival
    }
}
