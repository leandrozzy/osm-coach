package com.osmaicoach.collector

import org.json.JSONObject

/**
 * "JOGOS" do cartão GRANDE do jogador (bloco "Estatísticas do jogador": JOGOS / GOLOS / ASSISTÊNCIAS / CLASSIFICAÇÃO). É a fonte oficial para a regra
 * "cada jogador da nacionalidade da liga entrar em campo N vezes". Guardado por slot no plano `pgames` (nameKey → jogos + hora).
 */
object CardGames {
    /**
     * Lê o número abaixo do rótulo "JOGOS". Usa PALAVRAS (tokens): o OCR costuma juntar "JOGOS GOLOS ASSISTÊNCIAS CLASSIFICAÇÃO" numa linha só e
     * "8 2 1 7,3" noutra, então a linha inteira nunca é "jogos". Sem tokens, cai para as linhas.
     */
    fun read(ocr: OcrResult): Int? {
        val toks = if (ocr.tokens.isNotEmpty()) ocr.tokens else ocr.lines.flatMap { it.tokens }
        val label = toks.filter { t -> Txt.norm(t.text).trim(' ', ':', '.') == "jogos" && t.xc in 0.30f..0.65f && t.yc in 0.26f..0.52f }.minByOrNull { it.yc }
        if (label != null) {
            val v = toks.filter { t -> t !== label && t.yc - label.yc in 0.015f..0.09f && Math.abs(t.xc - label.xc) < 0.05f && t.text.trim().isNotEmpty() && t.text.trim().all { it.isDigit() } }
                .minByOrNull { Math.abs(it.xc - label.xc) + (it.yc - label.yc) }
            v?.text?.trim()?.toIntOrNull()?.takeIf { it in 0..99 }?.let { return it }
        }
        return read(ocr.lines)
    }

    /** Casa o nome do cartão ("GUSTAVO MARQUES ALVES DOS SANTOS", idade 25) com UM jogador do elenco: todas as palavras do nome curto aparecem no cartão e a idade bate. */
    fun match(shown: String, age: Int?, mine: List<PlayerEntity>): PlayerEntity? {
        val card = Txt.norm(shown).split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }
        if (card.isEmpty()) return null
        val c = mine.filter { pl -> age == null || pl.age == null || pl.age == age }.filter { pl ->
            val w = Txt.norm(pl.name).split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
            w.isNotEmpty() && w.all { m -> card.any { x -> x == m || (m.length >= 4 && Txt.sim(x, m) >= 0.85) } }
        }
        if (c.size <= 1) return c.firstOrNull()
        val best = c.maxOf { pl -> Txt.norm(pl.name).length }
        return c.filter { Txt.norm(it.name).length == best }.singleOrNull()   // empate = ambíguo: não adivinha
    }

    /** Versão por linhas (quando o OCR não traz palavras). */
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
