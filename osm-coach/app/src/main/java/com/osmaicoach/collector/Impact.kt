package com.osmaicoach.collector

import org.json.JSONObject

/**
 * O que as decisões renderam (12/11): seguir a tática do app × mexer à mão, vendas (quanto a CPU pagou em múltiplos do valor), treino por faixa de idade
 * e a lista de transferências. Só números do próprio histórico; com poucos jogos/vendas diz que é pouco, sem concluir.
 */
object Impact {
    class Side(val label: String, val v: Int, val e: Int, val d: Int) {
        val n: Int get() = v + e + d
        val ppg: Double get() = if (n == 0) 0.0 else (3.0 * v + e) / n
        fun text(): String = "$n jogos • ${v}V ${e}E ${d}D • ${"%.1f".format(ppg).replace('.', ',')} pts/jogo"
    }

    /** Jogos com a tática do app montada igual × jogos em que você trocou à mão (`deviated` no registro da tática). */
    fun followed(logs: List<JSONObject>): Pair<Side, Side>? {
        val played = logs.filter { it.optString("result") in listOf("V", "E", "D") }
        if (played.isEmpty()) return null
        fun side(label: String, dev: Boolean): Side {
            val g = played.filter { it.optBoolean("deviated", false) == dev }
            return Side(label, g.count { it.optString("result") == "V" }, g.count { it.optString("result") == "E" }, g.count { it.optString("result") == "D" })
        }
        val a = side("Montou o que o app sugeriu", false)
        val b = side("Mexeu na tática à mão", true)
        return if (b.n == 0 && a.n < 4) null else Pair(a, b)
    }

    /** Frase curta comparando os dois lados; sem 4+ jogos em cada, só informa. */
    fun followedNote(p: Pair<Side, Side>): String {
        val (a, b) = p
        if (a.n < 4 || b.n < 4) return "Poucos jogos para comparar (precisa de 4+ de cada lado): o app segue guardando."
        val diff = a.ppg - b.ppg
        return when {
            diff >= 0.4 -> "Seguir o app rendeu mais pontos por jogo (+${"%.1f".format(diff).replace('.', ',')}). Correlação, não prova."
            diff <= -0.4 -> "Quando você mexeu à mão rendeu mais (+${"%.1f".format(-diff).replace('.', ',')} pts/jogo): vale mandar o motivo para o app aprender."
            else -> "Sem diferença clara entre seguir o app e mexer à mão."
        }
    }

    class SellReport(val n: Int, val median: Double, val best: Pair<String, Double>?, val worst: Pair<String, Double>?)

    /** Vendas reais (não estimadas) em múltiplos do valor do jogador na época (valor vem do histórico de leitura). */
    fun sells(ledger: List<Ledger.Entry>, hist: JSONObject): SellReport? {
        val rs = ledger.filter { it.type == "sell" && !it.estimated && it.price != null }.mapNotNull { e ->
            val v = Growth.points(hist, e.key).lastOrNull { it.at <= e.at + 3600000L && it.value != null }?.value ?: return@mapNotNull null
            if (v <= 0.0) null else Pair(e.name, e.price!! / v)
        }.filter { it.second in 0.5..3.0 }
        if (rs.isEmpty()) return null
        val sorted = rs.sortedBy { it.second }
        return SellReport(rs.size, sorted[sorted.size / 2].second, sorted.last(), sorted.first())
    }

    /** Ritmo de treino (pontos por dia) por faixa de idade, do histórico de TODOS os slots; null sem amostra. */
    fun trainRates(hists: List<JSONObject>, ages: Map<String, Int?>): List<Triple<String, Double, Int>> =
        TrainLearn.rates(hists, ages).map { Triple(it.key, it.value.first, it.value.second) }
            .sortedBy { listOf("até 21", "22–24", "25–27", "28–30", "31+", "?").indexOf(it.first) }

    /** Jogadores À VENDA agora (setas lidas no Plantel). */
    fun listed(players: List<PlayerEntity>): List<PlayerEntity> = players.filter { it.owner == "MY" && it.forSale == true }
}
