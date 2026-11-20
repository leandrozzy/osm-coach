package com.osmaicoach.collector

import org.json.JSONObject
import java.io.File

/**
 * O OSM mostra nomes próprios dos clubes ("EC Cruzeiro Belo Horizonte"). Quando a busca na web acha o clube real
 * (TheSportsDB/football-logos), guarda aqui o nome com que a web o conhece ("Cruzeiro"). Artes e prompts usam esse
 * nome real; o nome do jogo continua sendo a chave de tudo no app. Sem acerto na web, nada é inventado: vale o nome do jogo.
 */
object ClubNames {
    private fun file(fd: File) = File(File(fd, "crests/web").apply { mkdirs() }, "_names.json")

    fun put(fd: File, game: String, real: String) {
        val r = real.trim()
        if (r.length < 3) return
        runCatching {
            val f = file(fd)
            val j = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
            j.put(Txt.key(game), r); f.writeText(j.toString())
        }
    }

    /** Nome real do clube na web, ou o próprio nome do jogo se ainda não foi identificado. */
    fun real(fd: File, game: String?): String? {
        if (game.isNullOrBlank()) return game
        val v = runCatching { JSONObject(file(fd).readText()).optString(Txt.key(game)) }.getOrNull()
        return v?.takeIf { it.isNotBlank() } ?: game
    }
}
