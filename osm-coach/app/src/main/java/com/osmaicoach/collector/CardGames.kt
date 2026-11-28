package com.osmaicoach.collector

import org.json.JSONObject

/**
 * "JOGOS" do cartão GRANDE do jogador (bloco "Estatísticas do jogador": JOGOS / GOLOS / ASSISTÊNCIAS / CLASSIFICAÇÃO). É a fonte oficial para a regra
 * "cada jogador da nacionalidade da liga entrar em campo N vezes". Guardado por slot no plano `pgames` (nameKey → jogos + hora).
 */
object CardGames {
    /** Lê o número abaixo do rótulo "JOGOS" (coluna do rótulo); null se o bloco não está na tela. */
    fun read(lines: List<OcrLine>): Int? {
        val label = lines.filter { l -> Txt.norm(l.text).trim() == "jogos" && l.xc in 0.30f..0.65f && l.yc in 0.26f..0.52f }.minByOrNull { it.yc } ?: return null
        val v = lines.filter { l -> l !== label && l.yc - label.yc in 0.015f..0.09f && Math.abs(l.xc - label.xc) < 0.06f && l.text.trim().all { it.isDigit() } && l.text.isNotBlank() }
            .minByOrNull { it.yc - label.yc } ?: return null
        return v.text.trim().toIntOrNull()?.takeIf { it in 0..99 }
    }

    suspend fun put(repo: Repo, slot: Int, nameKey: String, games: Int, now: Long) {
        val j = repo.dao.plan(slot, "pgames")?.json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val old = j.optJSONObject(nameKey)
        if (old != null && old.optInt("g", -1) == games) return
        j.put(nameKey, JSONObject().put("g", games).put("at", now))
        repo.dao.putPlan(PlanEntity(slot, "pgames", j.toString(), now))
        UiBus.bump()
    }

    suspend fun load(repo: Repo, slot: Int): Map<String, Int> {
        val j = repo.dao.plan(slot, "pgames")?.json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        return j.keys().asSequence().associateWith { j.getJSONObject(it).optInt("g", -1) }.filterValues { it >= 0 }
    }
}
