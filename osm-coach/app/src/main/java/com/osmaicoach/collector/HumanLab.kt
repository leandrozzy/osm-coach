package com.osmaicoach.collector

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pré-jogo contra técnico HUMANO (modo de teste, junto do `EngineLab`; nada aqui troca a recomendação padrão):
 * etiqueta "Técnico humano" só com evidência confiável, estado da leitura do rival, tabela vertical da recomendação com os nomes do app,
 * Plano B só com gatilho concreto e o placar de vitórias/pontos por jogo contra humanos (só tática APLICADA confirmada).
 */
object HumanLab {
    // ------------------------------------------------------------------ humano com evidência
    class Human(val reliable: Boolean, val label: String, val why: String)

    /** "Técnico humano" só com prova: Batalha (regra do app) ou leitura de apelido/bandeira com confiança ≥ 0,85. Palpite do Calendário (0,6), CPU e desconhecido NÃO viram humano. */
    fun human(f: Map<String, StoredField>): Human {
        if (Director.mustWin(f)) return Human(true, "Técnico humano", "Batalha: o rival é humano")
        val h = f[K.RIVAL_HUMAN]
        return when {
            h == null || !FieldMerge.known(h.value) -> Human(false, "Rival: humano ou CPU não confirmado", "nenhuma leitura de apelido/bandeira")
            h.value == "Não" -> Human(false, "Rival CPU (bot)", "lido como CPU")
            h.conf >= 0.85 -> Human(true, "Técnico humano", "apelido/bandeira lidos")
            else -> Human(false, "Rival provavelmente humano (não confirmado)", "só indício do Calendário")
        }
    }

    // ------------------------------------------------------------------ estado da leitura
    class ReadState(val label: String, val detail: String, val needsReread: List<String>)

    /** Estado da leitura do rival: atual (tudo lido e fresco), antiga (algo lido há muito) ou desconhecida (falta dado decisivo). */
    fun readState(f: Map<String, StoredField>, now: Long): ReadState {
        val ev = EngineLab.evidence(f, now)
        val core = ev.filter { it.field == "Formação" || it.field == "Estilo" || it.field == "Força" }
        val unknown = core.filter { it.status == EngineLab.Status.DESCONHECIDO }.map { it.field }
        val old = core.filter { it.status == EngineLab.Status.ANTIGA }.map { it.field }
        val secret = SecretTraining.on(f)
        val parts = ArrayList<String>()
        if (secret) parts.add("treino secreto do rival: Estilo/Força escondidos (nada é inferido do que foi lido antes)")
        return when {
            unknown.isNotEmpty() -> ReadState("desconhecida", "Não sei: " + unknown.joinToString(", ") + " do rival" + (if (parts.isEmpty()) "" else " • " + parts.joinToString()), unknown)
            old.isNotEmpty() -> ReadState("antiga", "Leitura antiga: " + old.joinToString(", ") + (if (parts.isEmpty()) "" else " • " + parts.joinToString()), old)
            else -> ReadState("atual", "Formação, estilo e força lidos há pouco" + (if (parts.isEmpty()) "" else " • " + parts.joinToString()), emptyList())
        }
    }

    // ------------------------------------------------------------------ tabela vertical (nomes do app, valores reais 0–100)
    class Row(val label: String, val value: String)

    fun table(t: Tactic): List<Row> = listOf(
        Row("Formação", t.formation), Row("Estilo de jogo", t.playStyle),
        Row("Pressão", "${t.pressure} (0–100)"), Row("Mentalidade", "${t.mentality} (0–100)"), Row("Temporização", "${t.tempo} (0–100)"),
        Row("Marcação", t.marking), Row("Impedimento", t.offside), Row("Desarme", t.tackle),
        Row("Avançados", t.advAttack), Row("Médios", t.advMid), Row("Defesa", t.advDef)
    )

    /** Escalação coerente com a formação: 11 titulares e linhas com o tamanho da formação. */
    fun lineupOk(t: Tactic, rows: List<List<PlayerEntity?>>): Boolean {
        val shape = Formations.base(t.formation).split("-").mapNotNull { it.toIntOrNull() }
        if (shape.size < 3) return false
        val want = listOf(1) + shape
        if (rows.map { it.size } != want && rows.sumOf { it.size } != 11) return false
        return rows.sumOf { r -> r.count { it != null } } == 11
    }

    /** Opções que existem no OSM (nenhum valor inventado): formação do catálogo, estilo/desarme/marcação conhecidos, sliders 0–100. */
    fun existsInOsm(t: Tactic): Boolean =
        t.formation in Formations.ALL && Osm.style(t.playStyle) == t.playStyle &&
            t.pressure in 0..100 && t.mentality in 0..100 && t.tempo in 0..100

    // ------------------------------------------------------------------ Plano B concreto
    /**
     * Gatilho do Plano B SÓ se há uma mudança concreta do rival a confirmar na releitura (formação ou estilo diferente do lido, com leitura NÃO atual,
     * ou do que o histórico dele mostra). Sem mudança concreta, sem Plano B. Texto: "Se a releitura confirmar que o rival mudou de Alas para Passe".
     */
    fun planBTrigger(scenario: WinModel.Input, base: WinModel.Input, state: ReadState): String? {
        if (state.label == "atual") return null   // leitura atual e igual: não há o que reler; trocar por palpite não vale
        val parts = ArrayList<String>()
        val sf = scenario.rivalFormation; val bf = base.rivalFormation
        if (sf != null && bf != null && sf != bf) parts.add("a formação do rival (de $bf para $sf)")
        val ss = scenario.rivalStyle; val bs = base.rivalStyle
        if (ss != null && bs != null && ss != bs) parts.add("o estilo do rival (de ${short(bs)} para ${short(ss)})")
        if (parts.isEmpty()) return null
        return "Se a releitura confirmar que mudou " + parts.joinToString(" e ")
    }

    private fun short(style: String) = when (style) { "Jogar pelas alas" -> "Alas"; "Jogo de passe" -> "Passe"; else -> style }

    // ------------------------------------------------------------------ resultados contra humanos
    const val MIN_GROUP = 5

    class Entry(val id: String, val log: JSONObject)
    class Cell(val key: String, var n: Int = 0, var w: Int = 0, var e: Int = 0, var d: Int = 0) {
        val points get() = 3 * w + e
        val ppg get() = if (n == 0) 0.0 else points.toDouble() / n
    }
    class Group(val name: String, val cells: List<Cell>, val n: Int, val w: Int, val e: Int, val d: Int) {
        val ppg get() = if (n == 0) 0.0 else (3 * w + e).toDouble() / n
    }
    class Stats(val groups: List<Group>, val eligible: Int, val excluded: Map<String, Int>)

    /**
     * Jogos contra HUMANOS elegíveis: resultado conhecido, rival humano (bots e desconhecido fora), tática aplicada CONFIRMADA (`seguiu`/`alterou`);
     * registro antigo sem confirmação e `nao_confirmado` ficam no histórico mas não entram ("Aplicação não confirmada"). Quando alterou, vale a JOGADA.
     * Mesma partida (id) só conta uma vez; Batalha e Liga ficam em grupos separados; sem marca de batalha = grupo próprio (não promove registro antigo).
     */
    fun stats(entries: List<Entry>): Stats {
        val ex = HashMap<String, Int>()
        fun skip(r: String) { ex[r] = (ex[r] ?: 0) + 1 }
        val seen = HashSet<String>()
        val buckets = LinkedHashMap<String, LinkedHashMap<String, Cell>>()
        var ok = 0
        for (en in entries) {
            if (!seen.add(en.id)) { skip("jogo repetido (contado uma vez)"); continue }
            val l = en.log
            val res = l.optString("result").takeIf { it in listOf("V", "E", "D") }
            if (res == null) { skip("sem resultado lido"); continue }
            if (l.isNull("human") || !l.has("human")) { skip("humano/CPU desconhecido"); continue }
            if (!l.optBoolean("human")) { skip("rival CPU (bot)"); continue }
            if (!PlayedTactic.fullyConfirmed(l)) { skip("Aplicação não confirmada"); continue }
            val group = if (!l.has("battle") || l.isNull("battle")) "Sem marca de competição (registro antigo)" else if (l.optBoolean("battle")) "Batalha" else "Liga"
            val key = l.optString("formation") + " • " + l.optString("playStyle")
            val c = buckets.getOrPut(group) { LinkedHashMap() }.getOrPut(key) { Cell(key) }
            c.n++; when (res) { "V" -> c.w++; "E" -> c.e++; else -> c.d++ }
            ok++
        }
        val groups = buckets.map { (g, m) -> Group(g, m.values.sortedByDescending { it.n }, m.values.sumOf { it.n }, m.values.sumOf { it.w }, m.values.sumOf { it.e }, m.values.sumOf { it.d }) }
        return Stats(groups, ok, ex)
    }

    suspend fun load(repo: Repo): Stats {
        val list = ArrayList<Entry>()
        for (s in 1..4) for (p in repo.dao.tacticLogs(s)) {
            val j = runCatching { JSONObject(p.json) }.getOrNull() ?: continue
            list.add(Entry("$s|${p.kind}|${j.optString("rival")}", j))
        }
        return stats(list)
    }

    fun verdictText(s: Stats): String = when {
        s.eligible < MIN_GROUP -> "Ainda não dá para concluir: ${s.eligible} jogo(s) comparável(is) contra humanos com tática aplicada confirmada (mínimo $MIN_GROUP)."
        else -> "Amostra de ${s.eligible} jogo(s): isto mostra o que aconteceu, não prova que uma tática ganha mais (rivais e forças diferentes)."
    }

    // ------------------------------------------------------------------ padrão × experimental (mesmo jogo, previsão congelada antes)
    class Origin(val name: String, val n: Int, val w: Int, val e: Int, val d: Int) { val ppg get() = if (n == 0) 0.0 else (3 * w + e).toDouble() / n }
    class Compare(val std: Origin, val lab: Origin, val other: Int, val concluded: Boolean, val text: String)

    /**
     * Compara jogos contra humanos cuja tática APLICADA coincide com a padrão OU com a experimental congeladas ANTES do jogo (frozenAt ≤ início).
     * Jogo em que as duas recomendações são iguais não distingue nada e fica fora; jogo só simulado (sem aplicação confirmada) nunca conta.
     */
    fun compare(pairs: List<Pair<JSONObject, JSONObject?>>): Compare {
        var sw = 0; var se = 0; var sd = 0; var lw = 0; var le = 0; var ld = 0; var other = 0
        val seen = HashSet<String>()
        for ((log, fz) in pairs) {
            if (fz == null) { other++; continue }
            if (!seen.add(fz.optString("id"))) continue
            val res = log.optString("result"); if (res !in listOf("V", "E", "D")) continue
            if (log.isNull("human") || !log.optBoolean("human") || !PlayedTactic.fullyConfirmed(log)) { other++; continue }
            val kick = if (fz.isNull("kickoff")) null else fz.optLong("kickoff")
            if (kick != null && fz.optLong("frozenAt") > kick) { other++; continue }
            val std = fz.optJSONObject("std"); val lab = fz.optJSONObject("lab")
            if (std == null || lab == null) { other++; continue }
            fun eq(a: JSONObject) = a.optString("formation") == log.optString("formation") && a.optString("playStyle") == log.optString("playStyle")
            val isStd = eq(std); val isLab = eq(lab)
            if (isStd && isLab) { other++; continue }
            when {
                isLab -> when (res) { "V" -> lw++; "E" -> le++; else -> ld++ }
                isStd -> when (res) { "V" -> sw++; "E" -> se++; else -> sd++ }
                else -> other++
            }
        }
        val s = Origin("Padrão", sw + se + sd, sw, se, sd); val l = Origin("Experimental", lw + le + ld, lw, le, ld)
        val ok = s.n >= MIN_GROUP && l.n >= MIN_GROUP
        return Compare(s, l, other, ok, if (ok) "Amostra mínima atingida; compare com cautela (rivais diferentes). Nada é promovido sozinho." else "Ainda não dá para concluir (precisa de $MIN_GROUP+ jogos de cada lado com tática aplicada confirmada).")
    }

    suspend fun compareLoad(repo: Repo): Compare {
        val pairs = ArrayList<Pair<JSONObject, JSONObject?>>()
        for (s in 1..4) {
            val fz = repo.dao.plansOf(s).filter { it.kind.startsWith("labfreeze_") }.mapNotNull { runCatching { JSONObject(it.json) }.getOrNull() }
            for (p in repo.dao.tacticLogs(s)) {
                val log = runCatching { JSONObject(p.json) }.getOrNull() ?: continue
                val round = log.optInt("round", -1)
                val z = fz.firstOrNull { it.optInt("round", -2) == round && it.optString("rival") == log.optString("rival") }
                pairs.add(Pair(log, z))
            }
        }
        return compare(pairs)
    }

    fun tacticOf(j: JSONObject): Tactic = Tactic(
        j.optString("formation"), j.optString("playStyle"), j.optInt("pressure"), j.optInt("mentality"), j.optInt("tempo"), j.optString("marking"),
        j.optString("offside"), j.optString("tackle"), j.optString("advAttack"), j.optString("advMid"), j.optString("advDef"), emptyList()
    )

    fun toJsonRows(rows: List<Row>): JSONArray = JSONArray(rows.map { JSONObject().put("k", it.label).put("v", it.value) })
}
