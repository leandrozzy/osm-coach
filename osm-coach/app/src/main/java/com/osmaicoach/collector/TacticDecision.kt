package com.osmaicoach.collector

import org.json.JSONObject

/**
 * Decisão tática na bolha e na aba Tática — UMA regra só (`decide`): MANTENHA, AJUSTE ou FALTA CONFIRMAR.
 * Só o confronto atual; "valor lido" = campo lido NESTE jogo (nunca o que foi só sugerido nem herdado do jogo anterior).
 * O motor padrão continua gerando a tática (`Pipeline.autoTactic`/`startGeneration`); aqui só se compara e se explica. Nada é aplicado no OSM.
 */
object TacticDecision {
    /** Leituras que, mudando, tornam a tática gerada desatualizada (mesma lista que o Pipeline usa para regerar). */
    val SIG_KEYS = listOf(
        K.ROUND, K.RIVAL_TEAM, K.RIVAL_NICK, K.RIVAL_STRENGTH, K.MY_STRENGTH, K.RIVAL_FORMATION, K.RIVAL_PLAN, K.RIVAL_PRESSURE,
        K.RIVAL_MENTALITY, K.RIVAL_TEMPO, K.RIVAL_MARKING, K.RIVAL_OFFSIDE, K.RIVAL_TACKLE, K.RIVAL_SECRET, K.REFEREE, K.HOME
    )

    enum class State(val label: String) { SLOT_DESCONHECIDO("SLOT NÃO CONFIRMADO"), FALTA("FALTA CONFIRMAR"), MANTENHA("MANTENHA"), AJUSTE("AJUSTE") }
    class Change(val label: String, val read: String, val rec: String)
    class View(
        val state: State, val slot: Int?, val title: String, val lines: List<String>, val changes: List<Change>, val action: String?,
        /** há recomendação válida para ESTE confronto, consultável (mesmo sem comparação com o montado) */
        val consultable: Boolean, val recomputing: Boolean, val table: List<HumanLab.Row>?, val tableNote: String?, val chartFilter: String?
    )

    class In(
        val slot: Int?, val f: Map<String, StoredField>, val plan: JSONObject?, val planAt: Long, val played: JSONObject?,
        val generating: Boolean, val autogenSig: String?, val rivalChange: String?, val now: Long,
        /** tlogs do slot (para a revanche) */
        val history: List<JSONObject> = emptyList(),
        /** regras da liga (digitadas no slot) em alerta: aparecem ANTES de tudo, em vermelho */
        val alerts: List<String> = emptyList()
    )

    fun sigOf(f: Map<String, StoredField>): String = SIG_KEYS.joinToString("|") { f[it]?.value.orEmpty() }

    /** A tática guardada foi gerada com as leituras de agora? (força ±1 e leitura que piscou não contam, como no Pipeline) */
    fun sigFresh(old: String?, f: Map<String, StoredField>): Boolean {
        if (old.isNullOrBlank()) return true
        val parts = old.split("|")
        return SIG_KEYS.withIndex().none { (i, k) -> SigCompare.differs(k, parts.getOrNull(i).orEmpty(), f[k]?.value.orEmpty()) }
    }

    fun title(slot: Int, f: Map<String, StoredField>): String {
        val rival = known(f, K.RIVAL_TEAM)
        val round = known(f, K.ROUND)?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }
        val where = when (f[K.COMP_TYPE]?.value) {
            "Batalha" -> "Batalha"
            "Copa" -> "Copa" + (round?.let { " • jogo $it" } ?: "")
            else -> round?.let { "Rodada $it" }
        }
        return "S$slot" + (rival?.let { " • vs $it" } ?: " • adversário não lido") + (where?.let { " • $it" } ?: "")
    }

    /** Filtro do atalho "Ver jogos parecidos": só o que foi confirmado neste confronto. */
    fun chartFilter(slot: Int, f: Map<String, StoredField>, readStateLabel: String): String {
        val j = JSONObject().put("slot", slot)
        known(f, K.COMPETITION)?.let { j.put("comp", it) }
        when (f[K.COMP_TYPE]?.value) { "Batalha" -> j.put("mode", "Batalha"); "Copa" -> j.put("mode", "Copa"); "Liga" -> j.put("mode", "Liga") }
        known(f, K.HOME)?.let { j.put("home", it == "Casa") }
        if (readStateLabel == "atual") {
            known(f, K.RIVAL_FORMATION)?.let { j.put("rivalFormation", Formations.canonical(it) ?: it) }
            known(f, K.RIVAL_PLAN)?.let { Osm.style(it) }?.let { j.put("rivalStyle", it) }
        }
        return j.toString()
    }

    /** Revanche: só com técnico humano confiável e jogo anterior contra o mesmo apelido com formação/estilo lidos. Diferencia o que ele usou antes do que a Análise de hoje confirmou. */
    fun rematch(f: Map<String, StoredField>, history: List<JSONObject>, rs: HumanLab.ReadState): String? {
        if (!HumanLab.human(f).reliable) return null
        val nick = known(f, K.RIVAL_NICK)?.let { Txt.key(it) } ?: return null
        val curRound = known(f, K.ROUND)?.filter { it.isDigit() }?.toIntOrNull()
        val past = history.filter { Txt.key(it.optString("rivalNick")) == nick && it.optString("result") in listOf("V", "E", "D") && it.optInt("round", -1) != (curRound ?: -2) &&
            (it.optString("rivalFormationRead").isNotBlank() || it.optString("rivalStyleRead").isNotBlank()) }
        val last = past.maxByOrNull { it.optLong("genAt", 0L) } ?: return null
        val used = listOf(last.optString("rivalFormationRead"), last.optString("rivalStyleRead")).filter { it.isNotBlank() }.joinToString(" • ")
        val today = if (rs.label == "atual") "a Análise de hoje confirma: " + listOfNotNull(known(f, K.RIVAL_FORMATION), known(f, K.RIVAL_PLAN)).joinToString(" • ") else "hoje a leitura dele está ${rs.label}: não confirmei se repete"
        return "Revanche (${past.size} jogo(s) contra este técnico): na última vez ele usou $used e o resultado foi ${last.optString("result")}; $today."
    }

    fun decide(i: In): View {
        val v = decideCore(i)
        if (i.alerts.isEmpty()) return v
        return View(v.state, v.slot, v.title, i.alerts + v.lines, v.changes, v.action, v.consultable, v.recomputing, v.table, v.tableNote, v.chartFilter)
    }

    private fun decideCore(i: In): View {
        val slot = i.slot
        if (slot == null || slot !in 1..4) return View(State.SLOT_DESCONHECIDO, null, "Slot não confirmado",
            listOf("Não sei qual slot está aberto. Abra o Pré-jogo do jogo no OSM ou escolha o slot abaixo; não escolho o de outro time pelo horário."), emptyList(),
            "Abra o Pré-jogo ou escolha o slot", false, false, null, null, null)
        val f = i.f; val now = i.now
        val title = title(slot, f)
        val rs = HumanLab.readState(f, now)
        val filter = chartFilter(slot, f, rs.label)
        val lines = ArrayList<String>()
        rematch(f, i.history, rs)?.let { lines.add(it) }
        val plan = i.plan
        val valid = plan != null && Director.tacticValid(plan, i.planAt, f, now) && tacticIsForThisGame(plan, f)
        val matchAt = f[K.MATCH_AT]?.value?.toLongOrNull()
        if (!valid) {
            val g = Director.groups(f, now).firstOrNull { !it.ok }
            val act: String?
            when {
                i.generating -> { lines.add("Calculando a tática com as leituras atuais…"); act = null }
                g != null -> {
                    lines.add("Ainda não há tática para ESTE jogo" + (plan?.let { " (a guardada era contra ${it.optString("rival")})" } ?: "") + ".")
                    act = "Falta ler: ${g.name.substringBefore(" (")} (${g.missing.joinToString(", ")}). ${g.where}"
                }
                matchAt != null && matchAt - now > Director.TACTIC_WINDOW_MS -> { lines.add("A tática é gerada nas últimas 8 h antes do jogo (o rival humano ainda pode mudar tudo)."); act = null }
                else -> { lines.add("Leituras completas, mas ainda não há tática para este jogo."); act = "Abra o Pré-jogo no OSM para eu gerar" }
            }
            if (rs.label != "atual") lines.add("Leitura do rival: ${rs.label} — ${rs.detail}")
            return View(State.FALTA, slot, title, lines, emptyList(), act, false, i.generating, null, null, filter)
        }
        plan!!
        val t = HumanLab.tacticOf(plan)
        val fresh = sigFresh(i.autogenSig, f)
        val recomputing = i.generating || !fresh
        val rec = PlayedTactic.suggestedFields(plan)
        val read = PlayedTactic.readNow(i.played)
        val diffs = rec.mapNotNull { (k, v) -> read[k]?.let { rv -> if (PlayedTactic.same(k, v, rv)) null else Change(PlayedTactic.LABEL[k] ?: k, rv, v) } }
        val unknown = rec.keys.filter { it !in read }
        val ref = Director.refereeNow(f)
        val allowed = Osm.allowedTackles(ref)
        if (ref != null && allowed.last() != "Extremo") lines.add("Árbitro $ref: desarme até ${allowed.last()}.")
        if (rs.label != "atual") lines.add("Leitura do rival: ${rs.label} — ${rs.detail}")
        if (SecretTraining.on(f)) lines.add("Treino secreto do rival: o que não foi lido continua desconhecido.")
        val note = if (recomputing) "Recomendação anterior: as leituras mudaram e a revisão ainda está sendo feita." else null
        if (recomputing) {
            lines.add(0, if (i.generating) "Recalculando com a leitura nova… (a tática abaixo ainda é a anterior)" else "Há leitura nova desde a última geração; a tática abaixo ainda é a anterior e ainda não foi revisada.")
            return View(State.FALTA, slot, title, lines, emptyList(), null, true, true, HumanLab.table(t), note, filter)
        }
        val rival = listOfNotNull(known(f, K.RIVAL_FORMATION), known(f, K.RIVAL_PLAN)).joinToString(" • ")
        return when {
            read.isEmpty() -> {
                lines.add(0, "Recomendação pronta, mas ainda não comparada com o que está montado.")
                View(State.FALTA, slot, title, lines, emptyList(), "Abra Tática no OSM para eu ler a configuração montada", true, false, HumanLab.table(t), "Ainda não comparada com a configuração montada.", filter)
            }
            diffs.isNotEmpty() -> {
                lines.add(0, "Ajuste ${diffs.size} campo(s): a configuração montada difere da recomendada.")
                if (unknown.isNotEmpty()) lines.add("Ainda não li: ${unknown.joinToString(", ") { PlayedTactic.LABEL[it] ?: it }}.")
                View(State.AJUSTE, slot, title, lines, diffs, if (unknown.isNotEmpty()) "Abra Tática para eu ler o resto" else null, true, false, HumanLab.table(t), null, filter)
            }
            unknown.isNotEmpty() -> {
                lines.add(0, "O que li bate com a recomendação, mas falta ler: ${unknown.joinToString(", ") { PlayedTactic.LABEL[it] ?: it }}.")
                View(State.FALTA, slot, title, lines, emptyList(), "Abra Tática para eu ler a configuração inteira", true, false, HumanLab.table(t), "Comparação parcial.", filter)
            }
            else -> {
                lines.add(0, "A configuração montada já é a recomendada" + " (${rec.size} campos lidos neste jogo).")
                i.rivalChange?.let { lines.add("O rival mudou ($it) e a recomendação continua adequada.") }
                if (rival.isNotEmpty()) lines.add("Rival: $rival.")
                View(State.MANTENHA, slot, title, lines, emptyList(), null, true, false, HumanLab.table(t), null, filter)
            }
        }
    }

    /** Alertas das regras da liga (🚫) deste slot: limite de treino/craque no limite ou a 1 ponto. */
    suspend fun ruleAlerts(repo: Repo, slot: Int, f: Map<String, StoredField>, now: Long): List<String> {
        val r = SlotRules.load(repo, slot) ?: return emptyList()
        return SlotRules.tips(r, repo.playersOf(slot), emptySet(), known(f, K.ROUND)?.toIntOrNull(), known(f, K.ROUND_TOTAL)?.toIntOrNull(), null, now)
            .filter { it.title.startsWith("🚫") }.map { it.title }
    }

    suspend fun slotNow(): Int? = Diag.currentSlot.takeIf { it in 1..4 }

    suspend fun load(repo: Repo, slot: Int?, now: Long = System.currentTimeMillis()): View {
        if (slot == null || slot !in 1..4) return decide(In(null, emptyMap(), null, 0L, null, false, null, null, now))
        val f = repo.fieldMap(slot)
        val p = repo.dao.plan(slot, "tactic")
        val plan = p?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
        val sig = repo.dao.plan(slot, "autogen")?.json?.let { runCatching { JSONObject(it).optString("sig") }.getOrNull() }
        val ch = runCatching { rivalChangesOf(repo, slot) }.getOrDefault(emptyList())
        return decide(In(slot, f, plan, p?.at ?: 0L, PlayedTactic.current(repo, slot),
            GenState.running("tactic", slot) || GenState.running("tactic_ai", slot), sig, if (ch.isEmpty()) null else RivalWatch.headline(ch), now,
            repo.dao.tacticLogs(slot).mapNotNull { p -> runCatching { JSONObject(p.json).also { it.put("genAt", p.at) } }.getOrNull() },
            runCatching { ruleAlerts(repo, slot, f, now) }.getOrDefault(emptyList())))
    }

    /** Texto simples (uma tela) para a bolha. */
    fun plainText(v: View): String = buildString {
        append(v.title).append('\n').append(v.state.label)
        for (l in v.lines) append('\n').append(l)
        for (c in v.changes) append("\n• ${c.label}: ${c.read} → ${c.rec}")
        v.action?.let { append("\n➜ ").append(it) }
    }
}
