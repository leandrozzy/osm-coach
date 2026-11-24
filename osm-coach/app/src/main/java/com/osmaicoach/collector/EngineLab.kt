package com.osmaicoach.collector

import org.json.JSONArray
import org.json.JSONObject

/**
 * Motor NOVO em paralelo (24/11) — modo de teste, DESLIGADO por padrão (ajuste `lab_on`). Nunca substitui a recomendação padrão.
 *
 * Diferenças do motor padrão (`Director.generateTacticLocal`):
 *  - separa a leitura do rival em ATUAL / ANTIGA / HIPÓTESE / DESCONHECIDO e pesa os cenários por isso (leitura antiga vale menos; hipótese só entra como cenário);
 *  - rival humano também em BATALHA; treino secreto do rival não revela nada que não foi lido;
 *  - objetivo explícito: VITÓRIA (P(vitória)) ou PONTOS (3·P(V) + P(E)) quando o empate serve (ajuste `lab_obj_S<slot>` = "pontos");
 *  - jogo equilibrado: nota = 50% média + 50% pior cenário (evita recomendação que desmorona com uma mudança plausível do rival);
 *  - força confirmada ATUAL superior a 20: testa também o 4-3-3 de emergência (sem forçar a escolha);
 *  - desarme nunca acima do que o árbitro permite (laranja/vermelho = Cuidadoso); valor do elenco NUNCA substitui força (só campos de força entram);
 *  - plano B só se rende mais em algum cenário plausível, dizendo qual leitura justificaria trocar.
 */
object EngineLab {
    const val VERSION = "lab-1"
    const val STD_VERSION = "std-1"
    const val OBJ_WIN = "vitoria"
    const val OBJ_POINTS = "pontos"

    fun on(repo: Repo): Boolean = repo.setting("lab_on", "0") == "1"
    fun objective(repo: Repo, slot: Int): String = if (repo.setting("lab_obj_S$slot", OBJ_WIN) == OBJ_POINTS) OBJ_POINTS else OBJ_WIN

    enum class Status(val label: String) { ATUAL("atual"), ANTIGA("antiga"), HIPOTESE("hipótese"), DESCONHECIDO("desconhecido") }
    class Evidence(val field: String, val status: Status, val value: String?, val ageHours: Long?)

    /** Nota de um conjunto de probabilidades no objetivo escolhido (0–1 para vitória; 0–3 para pontos). */
    fun objectiveValue(objective: String, win: Int, draw: Int): Double =
        if (objective == OBJ_POINTS) (3.0 * win + draw) / 100.0 else win / 100.0

    /** Escala do objetivo (para penalidades em "pontos esperados" valerem o mesmo nos dois objetivos). */
    fun scale(objective: String): Double = if (objective == OBJ_POINTS) 1.0 else 1.0 / 3.0

    /** Mistura robusta: jogo equilibrado (ou força desconhecida) usa metade pior cenário; com folga, um quarto. */
    fun robust(mean: Double, worst: Double, balanced: Boolean, scenarios: Int): Double =
        if (scenarios <= 1) mean else if (balanced) 0.5 * mean + 0.5 * worst else 0.75 * mean + 0.25 * worst

    // ------------------------------------------------------------------ evidência do rival
    fun evidence(f: Map<String, StoredField>, now: Long): List<Evidence> {
        val secret = SecretTraining.on(f)
        fun ev(label: String, key: String): Evidence {
            val fld = f[key]
            if (secret && key != K.RIVAL_STRENGTH) return Evidence(label, Status.DESCONHECIDO, null, null)   // treino secreto: o que não foi lido continua desconhecido
            if (fld == null || !FieldMerge.known(fld.value)) return Evidence(label, Status.DESCONHECIDO, null, null)
            val age = (now - fld.updatedAt) / 3_600_000L
            return Evidence(label, if (Director.fieldFresh(f, fld, now)) Status.ATUAL else Status.ANTIGA, fld.value, age)
        }
        return listOf(ev("Força", K.RIVAL_STRENGTH), ev("Formação", K.RIVAL_FORMATION), ev("Estilo", K.RIVAL_PLAN), ev("Desarme", K.RIVAL_TACKLE), ev("Marcação", K.RIVAL_MARKING), ev("Fora-de-jogo", K.RIVAL_OFFSIDE))
    }

    /** Força do rival confirmada por leitura ATUAL, sem treino secreto. */
    private fun confirmedDiff(f: Map<String, StoredField>, now: Long): Int? {
        if (SecretTraining.on(f)) return null
        val mine = f[K.MY_STRENGTH]?.takeIf { FieldMerge.known(it.value) }?.value?.toIntOrNull() ?: return null
        val rf = f[K.RIVAL_STRENGTH]?.takeIf { FieldMerge.known(it.value) && Director.fieldFresh(f, it, now) } ?: return null
        val rv = rf.value.toIntOrNull() ?: return null
        return mine - rv
    }

    // ------------------------------------------------------------------ recomendação
    class Alt(val tactic: Tactic, val trigger: String, val gain: Double)
    class Rec(
        val tactic: Tactic, val rows: List<List<PlayerEntity?>>, val objective: String, val win: Int, val draw: Int, val loss: Int, val value: Double,
        val worst: Double, val scenarios: Int, val evidence: List<Evidence>, val notes: List<String>, val planB: Alt?, val emergencyTested: Boolean
    )

    private fun predict(inp: WinModel.Input, t: Tactic, rows: List<List<PlayerEntity?>>, hist: List<HistRow>): WinModel.Prob? {
        val sp = Director.simPlan(t, rows)
        return WinModel.predict(inp.copy(plan = sp, tacticRecord = Director.record(hist, sp)))
    }

    /** Cenários do rival com peso pela QUALIDADE da leitura (e batalha = humano). */
    suspend fun scenarios(repo: Repo, slot: Int, base0: WinModel.Input, ev: List<Evidence>, battle: Boolean): List<Pair<WinModel.Input, Double>> {
        val base = if (battle) base0.copy(rivalHuman = true) else base0
        val std = Director.scenarios(repo, slot, base)   // lido agora + perfil do apelido + típicas de humanos
        val read = ev.filter { it.field == "Formação" || it.field == "Estilo" }
        val allCurrent = read.isNotEmpty() && read.all { it.status == Status.ATUAL }
        val anyOld = read.any { it.status == Status.ANTIGA }
        if (std.size <= 1) return if (anyOld && base.rivalHuman == true) std else std
        // reponderação: a leitura ATUAL manda; a ANTIGA vale metade; sem leitura, só hipóteses (o cenário "lido" some)
        val out = ArrayList<Pair<WinModel.Input, Double>>()
        for ((i, p) in std.withIndex()) {
            val isRead = p.first.rivalFormation == base.rivalFormation && p.first.rivalStyle == base.rivalStyle && p.first.rivalStrength == base.rivalStrength
            val w = when {
                isRead && allCurrent -> p.second * 1.25
                isRead && anyOld -> p.second * 0.5
                isRead && read.all { it.status == Status.DESCONHECIDO } -> p.second * 0.2
                else -> p.second
            }
            out.add(Pair(p.first, w))
        }
        return out
    }

    suspend fun recommend(repo: Repo, slot: Int, objective: String = objective(repo, slot), now: Long = System.currentTimeMillis()): Rec? {
        val f = repo.fieldMap(slot)
        val battle = Director.mustWin(f)
        var inp = Director.tacticInput(repo, slot)
        if (battle) inp = inp.copy(rivalHuman = true, mustWin = true)
        val rules = TacticEngine.recommend(inp) ?: return null
        val base0 = Director.simInput(repo, slot)
        val ev = evidence(f, now)
        val base = (if (battle) base0.copy(rivalHuman = true) else base0)
        val scen = scenarios(repo, slot, base0, ev, battle)
        val hist = Director.history(repo, slot)
        val lost = Director.lostAgainst(repo, slot)
        val prior = if (inp.rivalHuman == true) Director.priorFor(repo, slot, rules.diff) else null
        val cands = Director.candidates(rules, inp, scen, hist, lost, prior).take(60).toMutableList()
        // força confirmada acima de 20: testa também o 4-3-3 (emergência/ataque total), sem forçar a escolha
        val cdiff = confirmedDiff(f, now)
        var emergency = false
        if (cdiff != null && cdiff > 20) {
            emergency = true
            val rows = TacticEngine.lineup("4-3-3", inp.players, inp.fitness)?.first
            if (rows != null) for (st in listOf("Jogar pelas alas", "Remate à vista")) {
                val t = rules.tactic.copy(formation = Formations.variant("4-3-3", st), playStyle = st, mentality = maxOf(rules.tactic.mentality, 75), advAttack = "Atacar apenas", notes = emptyList())
                cands.add(Director.Cand(t, rows, 0.0))
            }
        }
        val referee = Director.refereeNow(f)
        val allowed = Osm.allowedTackles(referee)
        val sc = scale(objective)
        val balanced = cdiff == null || Math.abs(cdiff) <= 6
        class Row(val c: Director.Cand, val mean: Double, val worst: Double, val value: Double, val perScen: List<Double?>)
        val scored = ArrayList<Row>()
        for (c in cands) {
            // regra do árbitro: nunca acima do permitido (laranja/vermelho = Cuidadoso)
            val t = if (c.tactic.tackle in allowed) c.tactic else c.tactic.copy(tackle = allowed.last())
            var sum = 0.0; var wsum = 0.0; var worst = Double.MAX_VALUE
            val per = ArrayList<Double?>()
            for ((i, p) in scen.withIndex()) {
                val pr = predict(p.first, t, c.rows, hist)
                if (pr == null) { per.add(null); continue }
                val v = objectiveValue(objective, pr.win, pr.draw)
                per.add(v); sum += v * p.second; wsum += p.second
                if (v < worst) worst = v
            }
            if (wsum <= 0.0) continue
            val mean = sum / wsum
            val key = Formations.base(t.formation) + "|" + (Osm.style(t.playStyle) ?: "")
            val penalty = 0.4 * sc * minOf(3, lost[key] ?: 0)
            val bonus = (prior?.let { HumanPrior.bonus(it, t.formation, t.playStyle, t.mentality) } ?: 0.0) * sc
            scored.add(Row(Director.Cand(t, c.rows, c.points), mean, worst, robust(mean, worst, balanced, scen.size) - penalty + bonus, per))
        }
        if (scored.isEmpty()) return null
        scored.sortByDescending { it.value }
        val best = scored[0]
        val pr = predict(scen.first().first, best.c.tactic, best.c.rows, hist)
        // plano B: outra formação/estilo que rende mais que a principal em algum cenário plausível (ganho mínimo 0,05 no objetivo)
        var alt: Alt? = null
        val minGain = 0.05 * (if (objective == OBJ_POINTS) 3.0 else 1.0)
        for (r in scored.drop(1)) {
            if (r.c.tactic.formation == best.c.tactic.formation && r.c.tactic.playStyle == best.c.tactic.playStyle) continue
            var g = 0.0; var gi = -1
            for (i in scen.indices) { val a = r.perScen.getOrNull(i); val b = best.perScen.getOrNull(i); if (a != null && b != null && a - b > g) { g = a - b; gi = i } }
            if (gi >= 0 && g >= minGain) { alt = Alt(r.c.tactic, Director.planBTrigger(scen[gi].first, base), g); break }
        }
        val notes = ArrayList<String>()
        notes.add("Objetivo: " + (if (objective == OBJ_POINTS) "pontos esperados (3×P(vitória) + P(empate))" else "chance de vitória") + (if (battle) " • batalha: rival tratado como humano" else ""))
        notes.add("Leitura do rival: " + ev.joinToString(", ") { "${it.field} ${it.status.label}" })
        if (SecretTraining.on(f)) notes.add("Treino secreto do rival: o que não foi lido continua desconhecido (nada é inferido).")
        if (referee != null && Referee.COLORS.indices.any { Referee.tackle(referee) == "Cuidadoso" }) notes.add("Árbitro $referee: desarme limitado a ${allowed.last()}.")
        if (emergency) notes.add("Força confirmada +$cdiff: o 4-3-3 de emergência foi testado (não é forçado).")
        notes.add("Nota = " + (if (balanced) "50% média + 50% pior cenário (jogo equilibrado/incerto)" else "75% média + 25% pior cenário") + " em ${scen.size} cenário(s) do rival.")
        return Rec(best.c.tactic, best.c.rows, objective, pr?.win ?: 0, pr?.draw ?: 0, pr?.loss ?: 0, best.value, best.worst, scen.size, ev, notes, alt, emergency)
    }

    fun toJson(r: Rec): JSONObject = TacticValidator.toJson(r.tactic).put("objective", r.objective).put("win", r.win).put("draw", r.draw).put("loss", r.loss)
        .put("value", r.value).put("worst", r.worst).put("scenarios", r.scenarios).put("emergency", r.emergencyTested)
        .put("notes", JSONArray(r.notes)).put("version", VERSION)
        .put("evidence", JSONArray(r.evidence.map { JSONObject().put("f", it.field).put("s", it.status.name).put("v", it.value ?: JSONObject.NULL).put("h", it.ageHours ?: JSONObject.NULL) }))
        .also { j -> r.planB?.let { a -> j.put("planB", TacticValidator.toJson(a.tactic).put("trigger", a.trigger).put("gain", a.gain)) } }

    // ------------------------------------------------------------------ congelar previsões (antes do jogo)
    fun freezeKind(id: String) = "labfreeze_" + Integer.toHexString(id.hashCode())

    /** Dados que as duas previsões usaram (assinatura): se não mudou, não congela de novo (sem duplicar). */
    fun snapshot(f: Map<String, StoredField>): String {
        val keys = listOf(K.MY_STRENGTH, K.RIVAL_STRENGTH, K.MY_ATK, K.MY_MID, K.MY_DEF, K.MY_GOL, K.RIVAL_ATK, K.RIVAL_MID, K.RIVAL_DEF, K.RIVAL_GOL,
            K.RIVAL_FORMATION, K.RIVAL_PLAN, K.RIVAL_TACKLE, K.RIVAL_MARKING, K.RIVAL_OFFSIDE, K.REFEREE, K.HOME, K.RIVAL_HUMAN, K.MY_BONUS, K.RIVAL_LOGIN_BONUS)
        return keys.joinToString("|") { k -> k + "=" + (f[k]?.value ?: "") + "@" + (f[k]?.updatedAt ?: 0L) }.hashCode().toString()
    }

    fun diffBucket(diff: Int?): String = when {
        diff == null -> "força desconhecida"
        diff <= -10 -> "azarão forte (≤ −10)"
        diff <= -3 -> "azarão (−9 a −3)"
        diff <= 2 -> "parelho (−2 a +2)"
        diff <= 9 -> "favorito (+3 a +9)"
        else -> "favorito forte (≥ +10)"
    }

    /**
     * Congela, ANTES do jogo, as duas recomendações e as previsões dos dois motores para AS DUAS táticas (padrão e nova). Só grava uma vez por jogo
     * (mesma assinatura de dados = nada a fazer); com dados novos ainda antes do início, refaz como revisão (sem duplicar). Depois do início do jogo ou com
     * resultado lido, NUNCA grava nem altera (sem informação pós-jogo).
     */
    suspend fun freeze(repo: Repo, slot: Int, stdTactic: Tactic, stdRows: List<List<PlayerEntity?>>, now: Long = System.currentTimeMillis()): Boolean {
        if (!on(repo)) return false
        val f = repo.fieldMap(slot)
        val kickoff = f[K.MATCH_AT]?.value?.toLongOrNull()
        if (kickoff != null && kickoff <= now) return false
        val round = f[K.ROUND]?.value?.filter { it.isDigit() }?.toIntOrNull()
        val id = PlayedTactic.matchId(slot, f)
        // já tem resultado para esta rodada/rival? então o jogo acabou: nada a congelar
        if (round != null && repo.dao.plan(slot, "tlog_R$round")?.json?.let { runCatching { JSONObject(it).optString("result") in listOf("V", "E", "D") }.getOrNull() } == true) return false
        val kind = freezeKind(id)
        val snap = snapshot(f)
        val old = repo.dao.plan(slot, kind)?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (old != null && old.optString("snap") == snap) return false
        val obj = objective(repo, slot)
        val lab = recommend(repo, slot, obj, now) ?: return false
        val base0 = Director.simInput(repo, slot)
        val ev = lab.evidence
        val scen = scenarios(repo, slot, base0, ev, Director.mustWin(f))
        val hist = Director.history(repo, slot)
        // motor padrão: previsão com a leitura atual (como o app mostra); motor novo: média ponderada dos cenários
        fun stdPred(t: Tactic, rows: List<List<PlayerEntity?>>): WinModel.Prob? = predict(base0, t, rows, hist)
        fun labPred(t: Tactic, rows: List<List<PlayerEntity?>>): Triple<Int, Int, Int>? {
            var w = 0.0; var d = 0.0; var l = 0.0; var ws = 0.0
            for ((inp, p) in scen) { val pr = predict(inp, t, rows, hist) ?: continue; w += pr.win * p; d += pr.draw * p; l += pr.loss * p; ws += p }
            if (ws <= 0.0) return null
            return Triple(Math.round(w / ws).toInt(), Math.round(d / ws).toInt(), Math.round(l / ws).toInt())
        }
        fun trip(a: Triple<Int, Int, Int>?) = if (a == null) JSONObject.NULL else JSONObject().put("W", a.first).put("D", a.second).put("L", a.third)
        fun trip(p: WinModel.Prob?) = trip(p?.let { Triple(it.win, it.draw, it.loss) })
        val preds = JSONObject()
            .put("std", JSONObject().put("stdTactic", trip(stdPred(stdTactic, stdRows))).put("labTactic", trip(stdPred(lab.tactic, lab.rows))))
            .put("lab", JSONObject().put("stdTactic", trip(labPred(stdTactic, stdRows))).put("labTactic", trip(labPred(lab.tactic, lab.rows))))
        val diff = base0.myStrength?.let { m -> base0.rivalStrength?.let { r -> m - r } }
        val rec = JSONObject().put("id", id).put("round", round ?: -1).put("rival", f[K.RIVAL_TEAM]?.value ?: "")
            .put("frozenAt", now).put("kickoff", kickoff ?: JSONObject.NULL).put("snap", snap)
            .put("revisions", (old?.optInt("revisions", 0) ?: -1) + 1)
            .put("versions", JSONObject().put("std", STD_VERSION).put("lab", VERSION))
            .put("ctx", JSONObject().put("home", base0.home ?: JSONObject.NULL).put("human", base0.rivalHuman ?: JSONObject.NULL).put("diff", diff ?: JSONObject.NULL).put("bucket", diffBucket(diff)))
            .put("objective", obj)
            .put("evidence", JSONArray(ev.map { JSONObject().put("f", it.field).put("s", it.status.name) }))
            .put("std", TacticValidator.toJson(stdTactic)).put("lab", toJson(lab)).put("preds", preds)
        repo.dao.putPlan(PlanEntity(slot, kind, rec.toString(), now))
        repo.dao.putPlan(PlanEntity(slot, "lab_rec", toJson(lab).put("round", round ?: -1).put("at", now).toString(), now))
        UiBus.bump()
        return true
    }
}

/** Comparação das PREVISÕES dos dois motores, só onde a tática aplicada é conhecida e confirmada e as duas previsões foram registradas antes do jogo. */
object LabEval {
    class Entry(val round: Int, val result: String, val home: Boolean?, val human: Boolean?, val bucket: String, val std: Triple<Int, Int, Int>, val lab: Triple<Int, Int, Int>)
    class Agg(val n: Int, val brier: Double, val hit: Double)
    class Report(val n: Int, val std: Agg?, val lab: Agg?, val byHome: Map<String, Int>, val byHuman: Map<String, Int>, val byBucket: Map<String, Int>, val excluded: Map<String, Int>)

    private fun brier(p: Triple<Int, Int, Int>, res: String): Double {
        val o = doubleArrayOf(if (res == "V") 1.0 else 0.0, if (res == "E") 1.0 else 0.0, if (res == "D") 1.0 else 0.0)
        val q = doubleArrayOf(p.first / 100.0, p.second / 100.0, p.third / 100.0)
        return (0..2).sumOf { (q[it] - o[it]) * (q[it] - o[it]) }
    }
    private fun hit(p: Triple<Int, Int, Int>, res: String): Boolean {
        val pick = if (p.first >= p.second && p.first >= p.third) "V" else if (p.third >= p.second) "D" else "E"
        return pick == res
    }
    fun agg(es: List<Entry>, pick: (Entry) -> Triple<Int, Int, Int>): Agg? =
        if (es.isEmpty()) null else Agg(es.size, es.sumOf { brier(pick(it), it.result) } / es.size, es.count { hit(pick(it), it.result) }.toDouble() / es.size)

    fun report(es: List<Entry>, excluded: Map<String, Int>): Report = Report(
        es.size, agg(es) { it.std }, agg(es) { it.lab },
        es.groupingBy { when (it.home) { true -> "casa"; false -> "fora"; null -> "mando desconhecido" } }.eachCount(),
        es.groupingBy { when (it.human) { true -> "humano"; false -> "CPU"; null -> "humano/CPU desconhecido" } }.eachCount(),
        es.groupingBy { it.bucket }.eachCount(), excluded
    )

    /**
     * Lê os jogos congelados do slot e as táticas com resultado. Regras de elegibilidade (cada motivo de exclusão é contado):
     * resultado conhecido; previsão dos dois motores registrada ANTES do início (frozenAt ≤ kickoff); tática APLICADA confirmada igual a uma das duas
     * recomendações (padrão ou nova) — a previsão usada é a dos dois motores para essa tática.
     */
    suspend fun load(repo: Repo, slot: Int): Report {
        val entries = ArrayList<Entry>(); val ex = HashMap<String, Int>()
        fun skip(r: String) { ex[r] = (ex[r] ?: 0) + 1 }
        val logs = repo.dao.tacticLogs(slot).filter { it.kind.startsWith("tlog_R") }
        val all = repo.dao.plansOf(slot).filter { it.kind.startsWith("labfreeze_") }
        for (fz in all) {
            val j = runCatching { JSONObject(fz.json) }.getOrNull() ?: continue
            val round = j.optInt("round", -1)
            val tl = logs.firstOrNull { it.kind == "tlog_R$round" }?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
            val res = tl?.optString("result")
            if (tl == null || res !in listOf("V", "E", "D")) { skip("jogo sem resultado"); continue }
            val kick = if (j.isNull("kickoff")) null else j.optLong("kickoff")
            if (kick != null && j.optLong("frozenAt") > kick) { skip("previsão registrada depois do início"); continue }
            val preds = j.optJSONObject("preds")
            if (preds == null) { skip("sem previsões"); continue }
            val played = PlayedTactic.forLog(repo, slot, round, j.optString("rival"))
            val which = when {
                PlayedTactic.classify(j.optJSONObject("std") ?: JSONObject(), played).usage == PlayedTactic.SEGUIU -> "stdTactic"
                PlayedTactic.classify(j.optJSONObject("lab") ?: JSONObject(), played).usage == PlayedTactic.SEGUIU -> "labTactic"
                else -> null
            }
            if (which == null) { skip("tática aplicada não confirmada ou diferente das duas"); continue }
            fun get(engine: String): Triple<Int, Int, Int>? = preds.optJSONObject(engine)?.optJSONObject(which)?.let { Triple(it.optInt("W"), it.optInt("D"), it.optInt("L")) }
            val s = get("std"); val l = get("lab")
            if (s == null || l == null) { skip("falta a previsão de um dos motores"); continue }
            val c = j.optJSONObject("ctx")
            entries.add(Entry(round, res!!, c?.takeIf { !it.isNull("home") }?.optBoolean("home"), c?.takeIf { !it.isNull("human") }?.optBoolean("human"), c?.optString("bucket") ?: "?", s, l))
        }
        return report(entries, ex)
    }
}
