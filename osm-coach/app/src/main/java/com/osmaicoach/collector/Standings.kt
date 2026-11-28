package com.osmaicoach.collector

/**
 * Posição na liga SÓ pela tela Classificação do OSM (conferida com print real em 25/11): posição, pontos e jogos vêm direto da linha do seu clube;
 * nada é calculado a partir dos resultados das jornadas (essa conta foi retirada porque inventava jornadas e tratava a copa como liga).
 * Usa só a última "passada" de leitura (rolagens seguidas, ≤3 min) para nunca misturar tabela velha com nova.
 */
object Standings {
    class View(
        val rows: List<League.Row>,          // ordenadas pela posição lida
        val me: League.Row?,
        val readAt: Long,
        val missingRanks: List<Int>,         // posições sem linha lida (rolagem incompleta)
        val complete: Boolean,               // posições 1..N seguidas E a tabela fecha (Σ vitórias = Σ derrotas, Σ gols pró = Σ gols contra)
        val stale: Boolean,                  // você já jogou mais jogos de liga do que a tabela mostra
        val ifWin: Int?, val ifDraw: Int?, val ifLoss: Int?,
        /** posições 1..N seguidas, mas Σ vitórias ≠ Σ derrotas (ou gols): uma linha lida errado ou jogos contra clubes fora da tabela — NÃO prova que falta clube. */
        val sumsMismatch: Boolean = false, val sumV: Int? = null, val sumD: Int? = null
    )

    const val PASS_MS = 180_000L

    /** `leagueDone` = jogos da LIGA (sem copa) com resultado lido no calendário do slot, ou null se não sabe. */
    fun build(all: List<League.Row>, stAt: Long, myTeam: String?, leagueDone: Int?, hasNext: Boolean, battle: Boolean = false): View? {
        val dedup = League.dedupe(all).filter { it.rank != null && it.pts != null }
        if (dedup.isEmpty()) return null
        val last = dedup.maxOf { it.at ?: 0L }
        val pass = dedup.filter { last - (it.at ?: 0L) <= PASS_MS }
        // ranks repetidos na passada = mistura: fica com a leitura mais recente de cada posição
        val byRank = pass.groupBy { it.rank!! }.mapValues { (_, l) -> l.maxByOrNull { it.at ?: 0L }!! }
        val rows = byRank.toSortedMap().values.toList()
        if (rows.size < 2) return null
        val top = rows.maxOf { it.rank!! }
        val missing = (1..top).filter { it !in byRank }
        // tabela completa fecha as contas: toda vitória é a derrota de alguém e todo gol marcado é sofrido por alguém. Faltando um clube, não fecha.
        val balanced = rows.all { it.v != null && it.d != null } && rows.sumOf { it.v!! } == rows.sumOf { it.d!! } &&
            (rows.any { it.gf == null || it.ga == null } || rows.sumOf { it.gf!! } == rows.sumOf { it.ga!! })
        // Batalha de Grupos: posições 1..N seguidas bastam (os jogos podem ser contra clubes fora da tabela lida); liga exige as contas fechando
        val contiguous = missing.isEmpty() && rows.first().rank == 1
        val complete = contiguous && (balanced || battle)
        val me = League.find(rows, myTeam)
        val stale = me?.j != null && leagueDone != null && me.j < leagueDone
        fun rankWith(add: Int): Int? { me ?: return null; val p = me.pts ?: return null; return 1 + rows.count { it !== me && (it.pts ?: 0) > p + add } }
        return View(rows, me, if (stAt > 0) stAt else last, missing, complete, stale, if (hasNext) rankWith(3) else null, if (hasNext) rankWith(1) else null, if (hasNext) rankWith(0) else null,
            sumsMismatch = contiguous && !balanced, sumV = rows.mapNotNull { it.v }.takeIf { it.size == rows.size }?.sum(), sumD = rows.mapNotNull { it.d }.takeIf { it.size == rows.size }?.sum())
    }
}
