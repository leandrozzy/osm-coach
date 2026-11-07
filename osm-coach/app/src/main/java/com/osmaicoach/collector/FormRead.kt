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

    /**
     * Bolinhas do jogo (lidas no Pré-jogo da rodada `dotsRound`) + os resultados dos jogos disputados DEPOIS dessa leitura,
     * últimos 5: é o que o OSM mostraria agora. O Calendário só entra se estiver em dia e tiver MAIS jogos que as bolinhas
     * (leitura parcial/ocultada).
     */
    fun mine(matches: List<MatchEntity>, roundDone: Int?, dots: String?, dotsRound: Int? = null): String? {
        val cal = fromMatches(matches)
        val calOk = calendarCurrent(cal, roundDone)
        val a = dots?.takeIf { it.isNotEmpty() }?.let { d ->
            if (dotsRound == null || dotsRound <= 0) d
            else (d + matches.filter { it.result in listOf("V", "E", "D") && (it.round ?: 0) >= dotsRound }.sortedBy { it.round }.joinToString("") { it.result!! }).takeLast(5)
        }
        return when {
            a != null && calOk && cal.form.length > a.length -> cal.form
            a != null -> a
            cal.form.isNotEmpty() -> cal.form
            else -> null
        }
    }

    /** Rodadas jogadas que o calendário ainda não mostra (2 ou mais = leitura incompleta; 0 = em dia). */
    fun lag(matches: List<MatchEntity>, roundDone: Int?): Int {
        if (roundDone == null || roundDone < 2) return 0
        val last = fromMatches(matches).lastRound
        return (roundDone - last).takeIf { it >= 2 } ?: 0
    }
}
