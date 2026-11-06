package com.osmaicoach.collector

/**
 * Forma recente do MEU time (até 5 resultados, do mais antigo ao mais novo), igual à que o jogo mostra no Pré-jogo.
 * O Calendário só serve quando está em dia (já tem os jogos até a última rodada disputada); lido pela metade (rolou só as
 * primeiras rodadas), vale a leitura do jogo (bolinhas do Pré-jogo). Sem uma das duas fontes, usa a outra.
 */
object FormRead {
    /** Resultados do calendário e a última rodada com resultado conhecido (0 = nenhum). */
    data class Cal(val form: String, val lastRound: Int)

    fun fromMatches(matches: List<MatchEntity>): Cal {
        val done = matches.filter { it.result in listOf("V", "E", "D") && (it.round ?: 0) > 0 }.sortedBy { it.round }
        return Cal(done.takeLast(5).joinToString("") { it.result!! }, done.lastOrNull()?.round ?: 0)
    }

    /** O calendário está em dia? Tolera 1 rodada (o resultado do último jogo chega pela tela de Resultado ou pela próxima leitura). */
    fun calendarCurrent(cal: Cal, roundDone: Int?): Boolean = when {
        cal.form.isEmpty() -> false
        roundDone == null -> cal.form.length >= 5
        else -> cal.lastRound >= roundDone - 1
    }

    fun mine(matches: List<MatchEntity>, roundDone: Int?, dots: String?): String? {
        val cal = fromMatches(matches)
        return when {
            calendarCurrent(cal, roundDone) -> cal.form
            !dots.isNullOrEmpty() -> dots
            else -> cal.form.ifEmpty { null }
        }
    }

    /** Rodadas jogadas que o calendário ainda não mostra (2 ou mais = leitura incompleta; 0 = em dia). */
    fun lag(matches: List<MatchEntity>, roundDone: Int?): Int {
        if (roundDone == null || roundDone < 2) return 0
        val last = fromMatches(matches).lastRound
        return (roundDone - last).takeIf { it >= 2 } ?: 0
    }
}
