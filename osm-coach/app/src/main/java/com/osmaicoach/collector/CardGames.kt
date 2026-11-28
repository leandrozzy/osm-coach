package com.osmaicoach.collector

import org.json.JSONObject

/**
 * "JOGOS" do cartão GRANDE do jogador (bloco "Estatísticas do jogador": JOGOS / GOLOS / ASSISTÊNCIAS / CLASSIFICAÇÃO). É a fonte oficial para a regra
 * "cada jogador da nacionalidade da liga entrar em campo N vezes". Guardado por slot no plano `pgames` (nameKey → jogos + hora).
 */
object CardGames {
    /** Letras que o OCR troca por dígitos em numerais grandes e estilizados (8→B/3, 9→g/q, 0→O, 1→I/l, 6→G, 5→S, 2→Z). */
    fun toDigits(raw: String): Int? {
        val t = raw.trim().filter { !it.isWhitespace() }
        if (t.isEmpty() || t.length > 2) return null
        val m = t.map { c ->
            when (c) {
                'O', 'o', 'Q', 'D' -> '0'; 'I', 'l', '|', '!' -> '1'; 'Z' -> '2'; 'S', 's' -> '5'; 'G' -> '6'; 'B' -> '8'; 'g', 'q' -> '9'
                else -> c
            }
        }.joinToString("")
        return if (m.all { it.isDigit() }) m.toIntOrNull()?.takeIf { it in 0..99 } else null
    }

    /** Posição (xc, yc) do rótulo "JOGOS" do bloco de estatísticas, ou null se o cartão não mostra o bloco. */
    fun labelPos(ocr: OcrResult): Pair<Float, Float>? {
        val toks = if (ocr.tokens.isNotEmpty()) ocr.tokens else ocr.lines.flatMap { it.tokens }
        val l = toks.filter { t -> Txt.norm(t.text).trim(' ', ':', '.') == "jogos" && t.xc in 0.30f..0.65f && t.yc in 0.26f..0.52f }.minByOrNull { it.yc } ?: return null
        return Pair(l.xc, l.yc)
    }

    /** Caixa (fração da tela) onde fica o número de JOGOS: logo abaixo do rótulo. */
    fun valueBox(pos: Pair<Float, Float>): FloatArray = floatArrayOf(pos.first - 0.05f, pos.second + 0.018f, pos.first + 0.05f, pos.second + 0.080f)

    /**
     * Lê o número abaixo do rótulo "JOGOS". Usa PALAVRAS (tokens): o OCR costuma juntar "JOGOS GOLOS ASSISTÊNCIAS CLASSIFICAÇÃO" numa linha só e
     * "8 2 1 7,3" noutra, então a linha inteira nunca é "jogos". O numeral grande às vezes sai como letra (8→"B", 9→"g"): `toDigits` corrige.
     */
    fun read(ocr: OcrResult): Int? {
        val toks = if (ocr.tokens.isNotEmpty()) ocr.tokens else ocr.lines.flatMap { it.tokens }
        val pos = labelPos(ocr)
        if (pos != null) {
            val v = toks.filter { t -> t.yc - pos.second in 0.015f..0.09f && Math.abs(t.xc - pos.first) < 0.05f && toDigits(t.text) != null }
                .minByOrNull { Math.abs(it.xc - pos.first) + (it.yc - pos.second) }
            v?.let { toDigits(it.text) }?.let { return it }
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
