package com.osmaicoach.collector

import org.json.JSONObject

/** Liga os leitores de resultados da liga e da batalha à captura (chamado por `Repo.smartReads` em toda tela de um slot). */
object ResultsIngest {
    private val session = LeagueResults.Session()
    private val lock = kotlinx.coroutines.sync.Mutex()

    private suspend fun plan(repo: Repo, slot: Int, kind: String): JSONObject? = repo.dao.plan(slot, kind)?.json?.let { runCatching { JSONObject(it) }.getOrNull() }

    suspend fun onScreen(repo: Repo, slot: Int, type: ScreenType, ocr: OcrResult, now: Long) {
        if (type != ScreenType.OTHER_OSM) return
        lock.lock()
        try {
            val f = repo.fieldMap(slot)
            val isBattleSlot = f[K.COMP_TYPE]?.value == "Batalha"
            val bp = Battle.parse(ocr)
            if (bp != null) {
                val my = repo.setting(Battle.SETTING, Battle.DEFAULT_TEAM).ifBlank { Battle.DEFAULT_TEAM }
                val mine = Battle.mySide(my, bp.left, bp.right) != null
                // tela de batalha: slot atribuído se for de batalha; senão, o ÚNICO slot de batalha (ambíguo = não grava, nunca mistura slots)
                val target = if (isBattleSlot) slot else if (mine) (1..4).filter { repo.fieldMap(it)[K.COMP_TYPE]?.value == "Batalha" }.singleOrNull() else null
                if (target != null) { battle(repo, target, bp, now); return }
                if (isBattleSlot || mine) { Diag.log("Batalha: tela lida mas o slot não é certo, nada salvo"); return }
            }
            if (!isBattleSlot) {
                val cup = LeagueResults.parseCup(ocr)
                if (cup != null) cupStore(repo, slot, f, cup, now) else league(repo, slot, f, ocr, now)
            }
        } finally { lock.unlock() }
    }

    /** Copa: guardada à parte (chave "copa|…", "jornada" = fase 1..5), nunca misturada com as rodadas da liga. */
    private suspend fun cupStore(repo: Repo, slot: Int, f: Map<String, StoredField>, cup: LeagueResults.CupParsed, now: Long) {
        val comp = LeagueResults.compKey(f) ?: return
        var root = plan(repo, slot, "lres2"); var added = 0
        for ((phase, games) in cup.games.groupBy({ it.first }, { it.second })) {
            val (j, n) = LeagueResults.store(root, "copa|$comp", phase, games, emptyList(), now); root = j; added += n
        }
        repo.dao.putPlan(PlanEntity(slot, "lres2", root!!.toString(), now))
        Diag.log("Copa S$slot: ${cup.games.size} jogos jogados lidos, $added novos")
        if (added > 0) UiBus.bump()
    }

    private suspend fun league(repo: Repo, slot: Int, f: Map<String, StoredField>, ocr: OcrResult, now: Long) {
        val p = LeagueResults.parse(ocr) ?: return
        val comp = LeagueResults.compKey(f)
        if (comp == null) { Diag.log("Resultados da liga S$slot: competição desconhecida, nada salvo"); return }
        val round = LeagueResults.resolveRound(p, slot, now, session)
        if (round == null) { Diag.log("Resultados da liga S$slot: ${p.games.size} jogos sem cabeçalho de jornada (sem continuidade), nada salvo"); return }
        val st = League.table(plan(repo, slot, "league"), "st").map { it.club }
        val (j, added) = LeagueResults.store(plan(repo, slot, "lres2"), comp, round, p.games, st, now)
        repo.dao.putPlan(PlanEntity(slot, "lres2", j.toString(), now))
        Diag.log("Resultados da liga S$slot J$round: ${p.games.size} jogos lidos, $added novos")
        if (added > 0) UiBus.bump()
    }

    private suspend fun battle(repo: Repo, slot: Int, p: Battle.Parsed, now: Long) {
        val my = repo.setting(Battle.SETTING, Battle.DEFAULT_TEAM).ifBlank { Battle.DEFAULT_TEAM }
        val round = p.round ?: session.takeIf { it.slot == slot && it.round > 0 && now - it.at <= LeagueResults.INHERIT_MS }?.round
        if (p.round != null) { session.slot = slot; session.round = p.round; session.at = now }
        val j = Battle.store(plan(repo, slot, "battle"), p, round, my, now)
        repo.dao.putPlan(PlanEntity(slot, "battle", j.toString(), now))
        Diag.log("Batalha S$slot${round?.let { " J$it" } ?: ""}: ${p.left} ${p.aggL}×${p.aggR} ${p.right}, ${p.rows.size} linhas")
        UiBus.bump()
    }
}
