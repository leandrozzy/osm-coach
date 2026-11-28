package com.osmaicoach.collector

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Pré-jogo contra humanos: humano com evidência, leitura, Plano B concreto, resultados só com tática aplicada confirmada, dicas com proveniência, homônimos por slot. */
class Round6Test {
    private val NOW = 1_000_000_000_000L
    private fun sf(v: String, conf: Double = 0.9, at: Long = NOW - 600_000L) = StoredField(v, conf, at)

    // ------------------------------------------------------------------ humano e leitura
    @Test fun humanTagOnlyWithReliableEvidence() {
        assertTrue(HumanLab.human(mapOf(K.COMP_TYPE to sf("Batalha"))).reliable)
        assertTrue(HumanLab.human(mapOf(K.RIVAL_HUMAN to sf("Sim", 0.85))).reliable)
        assertFalse(HumanLab.human(mapOf(K.RIVAL_HUMAN to sf("Sim", 0.6))).reliable)      // palpite do Calendário
        val bot = HumanLab.human(mapOf(K.RIVAL_HUMAN to sf("Não", 0.95)))
        assertFalse(bot.reliable); assertTrue(bot.label.contains("CPU"))
        val unknown = HumanLab.human(emptyMap())
        assertFalse(unknown.reliable); assertTrue(unknown.label.contains("não confirmado"))
    }

    @Test fun staleOrMissingReadingIsSignalledNotGuessed() {
        val old = NOW - 40L * 3_600_000L
        val f = mapOf(K.RIVAL_FORMATION to sf("4-3-3 A", at = old), K.RIVAL_PLAN to sf("Jogar pelas alas", at = old), K.RIVAL_STRENGTH to sf("80", at = old))
        val st = HumanLab.readState(f, NOW)
        assertEquals("antiga", st.label); assertTrue(st.needsReread.isNotEmpty())
        val none = HumanLab.readState(emptyMap(), NOW)
        assertEquals("desconhecida", none.label); assertTrue(none.detail.startsWith("Não sei"))
        val fresh = HumanLab.readState(mapOf(K.RIVAL_FORMATION to sf("4-3-3 A"), K.RIVAL_PLAN to sf("Jogar pelas alas"), K.RIVAL_STRENGTH to sf("80")), NOW)
        assertEquals("atual", fresh.label)
    }

    @Test fun secretTrainingHidesWhatWasNotReadNow() {
        val f = mapOf(K.RIVAL_SECRET to sf("Sim"), K.RIVAL_FORMATION to sf("4-3-3 A"), K.RIVAL_PLAN to sf("Jogar pelas alas"), K.RIVAL_STRENGTH to sf("80"))
        val st = HumanLab.readState(f, NOW)
        assertEquals("desconhecida", st.label)                      // estilo lido antes não é reciclado como atual
        assertTrue(st.detail.contains("treino secreto"))
    }

    // ------------------------------------------------------------------ Plano B só com gatilho concreto
    private fun inp(form: String?, style: String?) = WinModel.Input(70, 70, rivalFormation = form, rivalStyle = style)

    @Test fun planBNeedsConcreteTriggerAndNonCurrentReading() {
        val old = HumanLab.ReadState("antiga", "x", listOf("Estilo"))
        val cur = HumanLab.ReadState("atual", "x", emptyList())
        val base = inp("4-3-3 A", "Jogar pelas alas")
        assertEquals("Se a releitura confirmar que mudou o estilo do rival (de Alas para Passe)", HumanLab.planBTrigger(inp("4-3-3 A", "Jogo de passe"), base, old))
        assertTrue(HumanLab.planBTrigger(inp("4-4-2 B", "Jogar pelas alas"), base, old)!!.contains("formação do rival (de 4-3-3 A para 4-4-2 B)"))
        assertNull(HumanLab.planBTrigger(base, base, old))                                   // sem mudança concreta: sem Plano B
        assertNull(HumanLab.planBTrigger(inp("4-4-2 B", "Jogo de passe"), base, cur))        // leitura atual: nada a reler
    }

    // ------------------------------------------------------------------ tabela vertical
    @Test fun tableUsesAppNamesAndRealValues() {
        val t = Tactic("4-3-3 A", "Jogar pelas alas", 62, 78, 45, "À zona", "Sim", "Cuidadoso", "Atacar apenas", "Equilibrado", "Segurar", emptyList())
        val rows = HumanLab.table(t)
        assertEquals(listOf("Formação", "Estilo de jogo", "Pressão", "Mentalidade", "Temporização", "Marcação", "Impedimento", "Desarme", "Avançados", "Médios", "Defesa"), rows.map { it.label })
        assertEquals("62 (0–100)", rows[2].value); assertEquals("4-3-3 A", rows[0].value); assertEquals("Cuidadoso", rows[7].value)
        assertTrue(HumanLab.existsInOsm(t))
        assertFalse(HumanLab.existsInOsm(t.copy(playStyle = "Tiki-taka")))
        assertFalse(HumanLab.existsInOsm(t.copy(pressure = 140)))
    }

    // ------------------------------------------------------------------ resultados contra humanos
    private fun log(res: String?, human: Boolean?, usage: String?, battle: Boolean? = false, form: String = "4-3-3 A", style: String = "Jogar pelas alas"): JSONObject {
        val j = JSONObject().put("formation", form).put("playStyle", style).put("result", res ?: JSONObject.NULL)
        if (human != null) j.put("human", human) else j.put("human", JSONObject.NULL)
        if (usage != null) j.put("usage", usage)
        if (usage == PlayedTactic.ALTEROU) j.put("appliedFull", true)
        if (battle != null) j.put("battle", battle)
        return j
    }
    private fun e(id: String, l: JSONObject) = HumanLab.Entry(id, l)

    @Test fun statsExcludeBotsUnconfirmedAndOldRecords() {
        val s = HumanLab.stats(listOf(
            e("a", log("V", true, PlayedTactic.SEGUIU)),
            e("b", log("D", true, PlayedTactic.ALTEROU)),
            e("c", log("V", false, PlayedTactic.SEGUIU)),                 // bot
            e("d", log("V", true, PlayedTactic.NAO)),                     // aplicação não confirmada
            e("e", log("V", true, null)),                                 // registro antigo: não vira exemplo verificado
            e("f", log("V", null, PlayedTactic.SEGUIU)),                  // humano desconhecido
            e("g", log(null, true, PlayedTactic.SEGUIU))                  // sem resultado
        ))
        assertEquals(2, s.eligible)
        assertEquals(1, s.excluded["rival CPU (bot)"]); assertEquals(2, s.excluded["Aplicação não confirmada"])
        val liga = s.groups.single { it.name == "Liga" }
        assertEquals(1, liga.w); assertEquals(1, liga.d); assertEquals(1.5, liga.ppg, 1e-9)
        assertTrue(HumanLab.verdictText(s).startsWith("Ainda não dá para concluir"))
    }

    @Test fun repeatedImportCountsOnceAndBattleIsSeparate() {
        val l = log("V", true, PlayedTactic.SEGUIU, battle = true)
        val s = HumanLab.stats(listOf(e("x", l), e("x", l), e("y", log("E", true, PlayedTactic.SEGUIU, battle = false)), e("z", log("V", true, PlayedTactic.SEGUIU, battle = null))))
        assertEquals(3, s.eligible)
        assertEquals(1, s.excluded["jogo repetido (contado uma vez)"])
        assertEquals(setOf("Batalha", "Liga", "Sem marca de competição (registro antigo)"), s.groups.map { it.name }.toSet())
        assertEquals(1, s.groups.single { it.name == "Batalha" }.n)
    }

    @Test fun appliedDifferentFromSuggestedIsAttributedToApplied() {
        // o tlog já guarda a JOGADA quando alterou (4-4-2 B / Passe); a sugerida fica em gen*
        val l = log("V", true, PlayedTactic.ALTEROU, form = "4-4-2 B", style = "Jogo de passe").put("genFormation", "4-3-3 A")
        val cell = HumanLab.stats(listOf(e("a", l))).groups.single().cells.single()
        assertEquals("4-4-2 B • Jogo de passe", cell.key)
    }

    @Test fun formationLetterAndStyleStaySeparate() {
        val s = HumanLab.stats(listOf(
            e("a", log("V", true, PlayedTactic.SEGUIU, form = "4-3-3 A", style = "Jogar pelas alas")),
            e("b", log("V", true, PlayedTactic.SEGUIU, form = "4-3-3 B", style = "Jogar pelas alas")),
            e("c", log("V", true, PlayedTactic.SEGUIU, form = "4-3-3 A", style = "Jogo de passe"))))
        assertEquals(3, s.groups.single().cells.size)
    }

    private fun fz(id: String, std: Pair<String, String>, lab: Pair<String, String>, frozenAt: Long = 10L, kickoff: Long = 20L) = JSONObject().put("id", id).put("frozenAt", frozenAt).put("kickoff", kickoff)
        .put("std", JSONObject().put("formation", std.first).put("playStyle", std.second)).put("lab", JSONObject().put("formation", lab.first).put("playStyle", lab.second))

    @Test fun compareOnlyCountsAppliedConfirmedAndDistinctRecommendations() {
        val a = "4-3-3 A" to "Jogar pelas alas"; val b = "4-4-2 B" to "Jogo de passe"
        val pairs = listOf(
            Pair(log("V", true, PlayedTactic.SEGUIU), fz("1", a, b)),                                          // aplicou a padrão
            Pair(log("E", true, PlayedTactic.SEGUIU, form = "4-4-2 B", style = "Jogo de passe"), fz("2", a, b)),  // aplicou a experimental
            Pair(log("V", true, PlayedTactic.SEGUIU), fz("3", a, a)),                                          // iguais: não distingue
            Pair(log("V", true, PlayedTactic.NAO), fz("4", a, b)),                                             // só simulada/sem confirmação
            Pair(log("V", true, PlayedTactic.SEGUIU), fz("5", a, b, frozenAt = 30L, kickoff = 20L))            // previsão depois do início
        )
        val c = HumanLab.compare(pairs)
        assertEquals(1, c.std.n); assertEquals(1, c.lab.n); assertFalse(c.concluded)
        assertTrue(c.text.startsWith("Ainda não dá para concluir"))
    }

    // ------------------------------------------------------------------ dicas com proveniência
    private val PAGE = """<html><head><link rel="canonical" href="https://example.org/a/best"/><meta property="og:title" content="Melhores táticas"/>
        <meta name="author" content="Equipe X"/><meta property="article:published_time" content="2025-03-04T10:00:00Z"/></head>
        <body><p>Use o 4-3-3 A com Jogar pelas alas contra defesas baixas. Treine muito.</p><p>Outra dica: 5-3-2 serve para segurar.</p></body></html>"""

    @Test fun pageTipKeepsLinkAuthorDateAndLiteralExcerptOnly() {
        val tips = Tips.parsePage("https://example.org/a/best?utm=1", PAGE, "Suporte OSM", NOW)
        assertEquals(2, tips.size)
        val t = tips[0]
        assertEquals("https://example.org/a/best", t.url); assertEquals("Equipe X", t.author); assertEquals("2025-03-04T10:00:00Z", t.publishedAt); assertEquals(NOW, t.verifiedAt)
        assertEquals("4-3-3 A", t.formation); assertEquals("Jogar pelas alas", t.playStyle)
        assertTrue(t.excerpt!!.contains("Use o 4-3-3 A com Jogar pelas alas"))
        assertNull(tips[1].playStyle)                                        // estilo só se está na MESMA frase
    }

    @Test fun tipWithoutDateStaysUndatedAndWithoutLinkIsDropped() {
        val tips = Tips.parsePage("https://example.org/p", "<html><body>Jogue 4-4-2 A no ataque.</body></html>", "Reddit", NOW)
        assertNull(tips.single().publishedAt); assertNull(tips.single().author)
        assertNull(Tips.Tip.fromJson(JSONObject().put("source", "Reddit").put("verifiedAt", NOW)))   // sem link não existe dica verificada
        assertNull(Tips.Tip.fromJson(JSONObject().put("url", "http://inseguro").put("verifiedAt", NOW)))
    }

    @Test fun videoOnlyTitleAndChannelNeverMinuteOrSliders() {
        val t = Tips.parseVideo("https://www.youtube.com/watch?v=a0BTUH2doGM&t=33s", """{"title":"Best tactic 4-3-3","author_name":"Hamza Ayaz"}""", NOW)!!
        assertEquals("https://www.youtube.com/watch?v=a0BTUH2doGM", t.url); assertEquals("Hamza Ayaz", t.author)
        assertNull(t.formation); assertNull(t.playStyle); assertNull(t.excerpt); assertTrue(t.demonstrates.contains("NÃO foi visto"))
    }

    @Test fun updateDoesNotRenewUnverifiedRulesAndFailureKeepsOldBase() {
        val old = Tips.Tip("https://example.org/old", "Reddit", null, null, null, NOW - 90L * 86_400_000L, "4-3-3 A", null, null, "x", "d")
        val fresh = Tips.Tip("https://example.org/new", "Suporte OSM", null, null, null, NOW, "4-4-2 A", null, null, "y", "d")
        val merged = Tips.merge(listOf(old), mapOf("https://example.org/new" to listOf(fresh)))
        assertEquals(2, merged.size)
        assertEquals(NOW - 90L * 86_400_000L, merged.first { it.url.endsWith("/old") }.verifiedAt)      // não foi verificada: data antiga mantida
        assertTrue(Tips.stale(merged.first { it.url.endsWith("/old") }, NOW)); assertFalse(Tips.stale(fresh, NOW))
        assertEquals(2, Tips.merge(merged, emptyMap()).size)                                              // sem rede: nada some nem é renovado
    }

    @Test fun supportDoesNotCollapseABOrWingsVsPassing() {
        val rec = Tactic("4-3-3 A", "Jogar pelas alas", 60, 70, 50, "À zona", "Sim", "Normal", "Atacar apenas", "Equilibrado", "Segurar", emptyList())
        fun tip(f: String?, s: String?) = Tips.Tip("https://x.org/a", "Reddit", null, null, null, NOW, f, s, null, null, "d")
        assertEquals("mesma formação e estilo", Tips.support(tip("4-3-3 A", "Jogar pelas alas"), rec))
        assertTrue(Tips.support(tip("4-3-3 B", "Jogar pelas alas"), rec).startsWith("formação base igual"))
        assertTrue(Tips.support(tip("4-3-3 A", "Jogo de passe"), rec).contains("estilo diferente"))
        assertTrue(Tips.support(tip("4-4-2 A", null), rec).startsWith("outra formação"))
    }

    // ------------------------------------------------------------------ Tática pronta só para ESTE jogo
    @Test fun readyTacticMustBeForTheCurrentRival() {
        val f = mapOf(K.RIVAL_TEAM to sf("Rival FC"))
        assertTrue(tacticIsForThisGame(JSONObject().put("rival", "Rival FC"), f))
        assertFalse(tacticIsForThisGame(JSONObject().put("rival", "Outro SC"), f))
        assertFalse(tacticIsForThisGame(null, f))
    }

    // ------------------------------------------------------------------ homônimos por slot
    private fun baseDb(): JdbcSql {
        val d = JdbcSql.fresh()
        d.exec("INSERT INTO football_players(id,name,name_key,surname_key,status) VALUES(1,'Rui Dias','rui dias','dias','ativo')")
        d.exec("INSERT INTO football_players(id,name,name_key,surname_key,status) VALUES(2,'Rafa Dias','rafa dias','dias','ativo')")
        fun obs(slot: Int, age: Int) = d.exec("INSERT INTO football_ocr_observations(slot,kind,raw,norm,context,capture_key,at) VALUES(?,?,?,?,?,?,?)",
            arrayOf(slot, "player", "R. Dias", "r dias", JSONObject().put("age", age).put("pos", "MC").toString(), "c$slot", 1L))
        obs(1, 22); obs(2, 30)
        return d
    }

    @Test fun twoRDiasInDifferentSlotsStayApartInListAndFicha() {
        val d = baseDb()
        WorldCatalog.confirmManualIn(d, "player", "r dias", 1L, slot = 1)
        val all = WorldCatalog.players(d, "", null, 0, 0).filter { it.key == "r dias" }
        assertEquals(2, all.size)                                                              // separados, cada um com seu slot
        assertEquals("Rui Dias", all.single { it.slots == "1" }.real?.name)
        assertNull(all.single { it.slots == "2" }.real)                                        // o outro NÃO empresta a ficha
        assertNull(WorldCatalog.playerIn(d, "r dias", 2)?.real)
        assertEquals("Rui Dias", WorldCatalog.playerIn(d, "r dias", 1)?.real?.name)
        // escolher o do slot 2 não muda o do slot 1
        WorldCatalog.confirmManualIn(d, "player", "r dias", 2L, slot = 2)
        assertEquals("Rui Dias", WorldCatalog.playerIn(d, "r dias", 1)?.real?.name)
        assertEquals("Rafa Dias", WorldCatalog.playerIn(d, "r dias", 2)?.real?.name)
        assertEquals(2L, d.long("SELECT COUNT(*) FROM football_osm_entity_mappings WHERE osm_key='r dias' AND status='confirmado'"))   // confirmações preservadas
    }
}
