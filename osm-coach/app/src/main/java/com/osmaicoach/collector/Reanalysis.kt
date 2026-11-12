package com.osmaicoach.collector

import org.json.JSONObject

/** Reanálise do rival nos 20 min finais (humano pode trocar a tática na última hora): veredito guardado no plano `reanalysis`. */
object Reanalysis {
    const val PLAN = "reanalysis"
    const val WINDOW_MS = 20L * 60_000L

    /** (feito?, texto) para a Véspera do jogo: só vale a leitura feita DENTRO da janela deste jogo. */
    fun eveItem(json: String?, matchAt: Long, now: Long): Pair<Boolean, String> {
        val j = json?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (j == null || j.optLong("matchAt") != matchAt || j.optLong("at") < matchAt - WINDOW_MS) {
            return Pair(false, "Reanálise dos 20 min finais: releia Pré-jogo e Análise do rival (ele pode ter trocado a tática)")
        }
        val min = ((now - j.optLong("at")) / 60000L).coerceAtLeast(0)
        return when (j.optString("verdict")) {
            "mudou" -> Pair(false, "Reanálise: o rival MUDOU (${j.optString("detail").take(80)}). Gere a tática de novo e monte no OSM")
            "manteve" -> Pair(true, "Reanálise feita há $min min: o rival MANTEVE a tática")
            else -> Pair(true, "Reanálise: rival lido há $min min (passe de novo mais perto do jogo para comparar)")
        }
    }
}
