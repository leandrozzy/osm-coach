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

    /**
     * Forma do RIVAL para o Pré-jogo, com FONTE e COBERTURA.
     * [games] = só os resultados da MESMA competição/temporada do slot (plano `lres2`, chave da competição; Copa e Batalha ficam de fora);
     * [roundDone] = rodadas já disputadas; [dots] = bolinhas do Pré-jogo (só entram se [dotsFresh]); [cupOrBattle] = o próximo jogo é de Copa/Batalha.
     * A série de placares só vale como ATUAL se chega até a rodada já disputada; cinco placares antigos nunca vencem uma leitura mais recente só por serem cinco.
     */
    data class RivalForm(val dots: String, val source: String, val lastRound: Int?, val stale: Boolean, val note: String)

    fun rivalForm(games: List<LeagueResults.Stored>, rival: String?, roundDone: Int?, dots: String?, dotsFresh: Boolean, cupOrBattle: Boolean): RivalForm? {
        val d = dots?.takeIf { it.isNotEmpty() && dotsFresh }
        if (cupOrBattle) return d?.let { RivalForm(it, "prejogo", null, false, "Bolinhas do Pré-jogo (não são placares confirmados; Copa/Batalha não usam a tabela da liga)") }
        val (res, last) = LeagueResults.formRun(games, rival)
        val current = res.isNotEmpty() && (roundDone == null || last >= roundDone)
        return when {
            current -> RivalForm(res, "resultados", last, false, "${res.length} resultado${if (res.length > 1) "s" else ""} confirmado${if (res.length > 1) "s" else ""}, até a rodada $last")
            d != null -> RivalForm(d, "prejogo", null, false, "Bolinhas do Pré-jogo (não são placares confirmados)" + (if (res.isNotEmpty()) "; resultados lidos só até a rodada $last" else ""))
            res.isNotEmpty() -> RivalForm(res, "resultados", last, true, "Série desatualizada: resultados só até a rodada $last (faltam as mais recentes). Abra Resultados no OSM.")
            else -> null
        }
    }

    /** Corta as bolinhas ao nº de jogos já disputados na competição (rodadas jogadas ou resultados lidos, o maior). */
    fun cap(form: String, matches: List<MatchEntity>, roundDone: Int?, complete: Boolean = false): String {
        val played = maxOf(roundDone ?: 0, matches.count { it.result in listOf("V", "E", "D") })
        if (played <= 0) return ""
        val f = form.takeLast(played)
        // leitura parcial (quadro coberto pela janela do app: 1 bolinha com 13 jogos): não mostra, em vez de mostrar errado
        return if (complete && f.length < minOf(5, played)) "" else f
    }

    /** Rodadas jogadas que o calendário ainda não mostra (2 ou mais = leitura incompleta; 0 = em dia). */
    fun lag(matches: List<MatchEntity>, roundDone: Int?): Int {
        if (roundDone == null || roundDone < 2) return 0
        val last = fromMatches(matches).lastRound
        return (roundDone - last).takeIf { it >= 2 } ?: 0
    }

    /** "Reler formação e estilo de jogo do rival": nome EXATO do que falta, tirado da primeira leitura do rival que não vale (Pré-jogo, Análise ou Plantel do rival). */
    fun gapTitle(groups: List<Director.Group>): String? {
        val g = groups.take(3).firstOrNull { !it.ok } ?: return null
        val what = g.missing.joinToString(" e ")
        return when {
            g.name.startsWith("Pré-jogo") -> "Reler o Pré-jogo ($what)"
            g.name.startsWith("Análise") -> "Reler $what do rival"
            else -> "Reler o plantel do rival ($what)"
        }
    }
}
