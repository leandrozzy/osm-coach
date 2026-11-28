package com.osmaicoach.collector

import org.json.JSONObject

/**
 * Dados da aba "Gráficos" de cada slot. Só o que foi LIDO: sem leitura = lacuna ("sem leitura"), nunca zero;
 * Batalha, Liga, Copa e edições anteriores não se misturam; comparação de táticas só com humano confirmado e configuração aplicada INTEIRA confirmada.
 */
object SlotCharts {
    const val MIN_TOTAL = 6
    const val MIN_EACH = 3

    class Game(
        val key: String, val round: Int?, val rival: String?, val mode: String, val home: Boolean?, val human: Boolean?, val result: String?,
        val gf: Int?, val ga: Int?, val shotsFor: Int?, val shotsAgainst: Int?, val config: String?, val applied: Boolean,
        val rivalStyle: String?, val rivalFormation: String?, val bucket: String, val archived: Boolean
    ) {
        val points: Int? get() = when (result) { "V" -> 3; "E" -> 1; "D" -> 0; else -> null }
        val hasStats: Boolean get() = shotsFor != null && shotsAgainst != null
    }

    class Filter(
        val archived: Boolean = false, val mode: String? = null, val home: Boolean? = null, val humanOnly: Boolean = false,
        val bucket: String? = null, val rivalFormation: String? = null, val rivalStyle: String? = null
    ) {
        fun active(): List<String> = listOfNotNull(if (archived) "edições anteriores" else null, mode, home?.let { if (it) "casa" else "fora" }, if (humanOnly) "humanos" else null, bucket, rivalFormation?.let { "rival $it" }, rivalStyle?.let { "estilo $it" })
        companion object {
            /** Filtro vindo da bolha: só o que foi confirmado no confronto (nada é preenchido no chute). */
            fun fromJson(s: String?): Filter? {
                val j = s?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
                fun str(k: String) = if (j.has(k) && !j.isNull(k)) j.optString(k).takeIf { it.isNotBlank() } else null
                return Filter(mode = str("mode"), home = if (j.has("home") && !j.isNull("home")) j.optBoolean("home") else null,
                    humanOnly = j.optString("mode") == "Batalha", rivalFormation = str("rivalFormation"), rivalStyle = str("rivalStyle"))
            }
        }
    }

    fun apply(all: List<Game>, f: Filter): List<Game> = all.filter { g ->
        g.archived == f.archived && (f.mode == null || g.mode == f.mode) && (f.home == null || g.home == f.home) && (!f.humanOnly || g.human == true) &&
            (f.bucket == null || g.bucket == f.bucket) && (f.rivalFormation == null || g.rivalFormation == f.rivalFormation) && (f.rivalStyle == null || g.rivalStyle == f.rivalStyle)
    }

    /** Sliders em faixas de 5 (o OCR varia ±1); formação com letra, estilo e TODOS os campos entram: configurações completas diferentes não se agrupam. */
    fun configKey(j: JSONObject): String {
        fun q(k: String) = (Math.round(j.optInt(k, -1) / 5.0) * 5).toInt()
        return listOf(j.optString("formation"), Osm.style(j.optString("playStyle")) ?: j.optString("playStyle"), "P${q("pressure")}", "M${q("mentality")}", "T${q("tempo")}",
            j.optString("marking"), j.optString("offside"), j.optString("tackle"), j.optString("advAttack"), j.optString("advMid"), j.optString("advDef")).joinToString(" • ")
    }

    class RawLog(val kind: String, val json: JSONObject, val archived: Boolean)

    /** Monta os jogos: partidas do Calendário/Resultados (edição atual) + tlogs; tlog arquivado só entra em "edições anteriores". */
    fun build(slotMode: String, matches: List<MatchEntity>, logs: List<RawLog>): List<Game> {
        val out = ArrayList<Game>()
        val usedLogs = HashSet<RawLog>()
        fun sameRival(a: String?, b: String?) = a != null && b != null && Txt.sim(Txt.key(a), Txt.key(b)) >= 0.8
        fun game(key: String, round: Int?, rival: String?, mode: String, home: Boolean?, res: String?, gf: Int?, ga: Int?, log: JSONObject?, archived: Boolean): Game {
            val applied = log != null && PlayedTactic.fullyConfirmed(log)
            val diff = log?.takeIf { it.has("myStrength") && !it.isNull("myStrength") && it.has("rivalStrength") && !it.isNull("rivalStrength") }?.let { it.optInt("myStrength") - it.optInt("rivalStrength") }
            return Game(key, round, rival, mode, home ?: log?.takeIf { it.has("home") && !it.isNull("home") }?.optBoolean("home"), log?.takeIf { it.has("human") && !it.isNull("human") }?.optBoolean("human"), res, gf, ga,
                log?.takeIf { it.has("myShots") }?.optInt("myShots"), log?.takeIf { it.has("oppShots") }?.optInt("oppShots"),
                if (applied) configKey(log!!) else null, applied,
                log?.optString("rivalStyleRead")?.let { Osm.style(it) }, log?.optString("rivalFormationRead")?.takeIf { it.isNotBlank() }?.let { Formations.canonical(it) ?: it },
                EngineLab.diffBucket(diff), archived)
        }
        for (m in matches) {
            val cup = Fixtures.isCup(m)
            val mode = if (cup) "Copa" else if (slotMode == "Batalha") "Batalha" else "Liga"
            val log = logs.firstOrNull { !it.archived && it !in usedLogs && it.json.optInt("round", -1) == (m.round ?: -2) && sameRival(it.json.optString("rival"), m.opponent) &&
                (it.json.optBoolean("battle", false) == (mode == "Batalha")) }
            log?.let { usedLogs.add(it) }
            out.add(game("m|${m.mkey}", m.round, m.opponent, mode, m.home, m.result?.takeIf { it in listOf("V", "E", "D") }, m.scoreMine, m.scoreOpp, log?.json, false))
        }
        // tlog sem partida no Calendário (atual) e tlogs arquivados
        for (l in logs) {
            if (l in usedLogs) continue
            val j = l.json
            val res = j.optString("result").takeIf { it in listOf("V", "E", "D") }
            val mode = if (j.has("battle") && !j.isNull("battle")) (if (j.optBoolean("battle")) "Batalha" else "Liga") else "Sem marca"
            out.add(game("t|${l.kind}", j.optInt("round", -1).takeIf { it > 0 }, j.optString("rival").takeIf { it != NI && it.isNotBlank() }, mode, null, res, null, null, j, l.archived))
        }
        return out
    }

    // ------------------------------------------------------------------ gráfico 1
    class PlanRow(val label: String, val n: Int, val w: Int, val e: Int, val d: Int, val games: List<Game>) { val ppg get() = if (n == 0) 0.0 else (3.0 * w + e) / n }
    class PlanChart(val rows: List<PlanRow>, val comparable: Boolean, val best: PlanRow?, val note: String)

    fun plans(g: List<Game>): PlanChart {
        val elig = g.filter { it.human == true && it.applied && it.config != null && it.result != null }
        val rows = elig.groupBy { it.config!! }.map { (k, l) -> PlanRow(k, l.size, l.count { it.result == "V" }, l.count { it.result == "E" }, l.count { it.result == "D" }, l) }.sortedByDescending { it.n }
        val top = rows.take(2)
        val ok = rows.size >= 2 && elig.size >= MIN_TOTAL && top.all { it.n >= MIN_EACH }
        return PlanChart(rows, ok, if (ok) rows.filter { it.n >= MIN_EACH }.maxByOrNull { it.ppg } else null,
            if (rows.isEmpty()) "Sem jogos comparáveis: precisa de rival humano confirmado e da configuração aplicada inteira confirmada." else if (ok) "Compare com cautela: rivais e forças diferentes." else "Poucos jogos para comparar (mínimo $MIN_TOTAL no total e $MIN_EACH por configuração): sem vencedor.")
    }

    // ------------------------------------------------------------------ gráfico 3
    class RoundPoint(val round: Int, val points: Int?, val goalDiff: Int?, val game: Game)
    fun rounds(g: List<Game>): List<RoundPoint> = g.filter { it.round != null && it.result != null }.sortedBy { it.round }.map { RoundPoint(it.round!!, it.points, if (it.gf != null && it.ga != null) it.gf - it.ga else null, it) }

    // ------------------------------------------------------------------ gráfico 4
    class StyleRow(val style: String, val n: Int, val w: Int, val e: Int, val d: Int, val games: List<Game>) { val ppg get() = if (n == 0) 0.0 else (3.0 * w + e) / n }
    class StyleChart(val rows: List<StyleRow>, val unknown: Int)
    fun styles(g: List<Game>): StyleChart {
        val withRes = g.filter { it.result != null }
        val rows = withRes.filter { it.rivalStyle != null }.groupBy { it.rivalStyle!! }.map { (k, l) -> StyleRow(k, l.size, l.count { it.result == "V" }, l.count { it.result == "E" }, l.count { it.result == "D" }, l) }.sortedByDescending { it.n }
        return StyleChart(rows, withRes.count { it.rivalStyle == null })
    }

    // ------------------------------------------------------------------ gráfico 5
    class Coverage(val total: Int, val withScore: Int, val withStats: Int, val withApplied: Int, val humanConfirmed: Int, val inComparison: Int, val reasons: List<String>)
    fun coverage(g: List<Game>): Coverage {
        val played = g.filter { it.result != null }
        val comp = played.count { it.human == true && it.applied }
        val reasons = ArrayList<String>()
        val noHuman = played.count { it.human != true }; if (noHuman > 0) reasons.add("$noHuman sem rival humano confirmado (bot ou não identificado)")
        val noApplied = played.count { it.human == true && !it.applied }; if (noApplied > 0) reasons.add("$noApplied sem a configuração aplicada inteira confirmada (a tática montada não foi lida neste jogo)")
        return Coverage(played.size, played.count { it.gf != null && it.ga != null }, played.count { it.hasStats }, played.count { it.applied }, played.count { it.human == true }, comp, reasons)
    }

    suspend fun load(repo: Repo, slot: Int): Pair<String, List<Game>> {
        val f = repo.fieldMap(slot)
        val logs = repo.dao.tacticLogs(slot).mapNotNull { p -> runCatching { RawLog(p.kind, JSONObject(p.json), p.kind.startsWith("tlog_X")) }.getOrNull() }
        val slotMode = if (f[K.COMP_TYPE]?.value == "Batalha") "Batalha" else "Liga"
        return Pair(slotMode, build(slotMode, repo.dao.matchesOf(slot), logs))
    }
}
