package com.osmaicoach.collector

import org.json.JSONObject
import java.io.File

/**
 * Bandeira escolhida À MÃO para um jogador (quando a leitura não achou ou errou). Fica no plano global `natmanual` (slot 0): chave do jogador →
 * código ISO do país. Vale para os 4 slots e para ligas futuras (o mesmo jogador, a mesma bandeira) e passa por cima de qualquer identificação
 * automática.
 */
object FlagManual {
    private const val KIND = "natmanual"

    suspend fun all(repo: Repo): Map<String, String> {
        val o = repo.dao.plan(0, KIND)?.json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.optString(it) }.filterValues { it.isNotBlank() }
    }

    /** Guarda (iso) ou apaga (null) a bandeira manual do jogador. */
    suspend fun set(repo: Repo, key: String, iso: String?) {
        val o = repo.dao.plan(0, KIND)?.json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        if (iso == null) o.remove(key) else o.put(key, iso.lowercase())
        repo.dao.putPlan(PlanEntity(0, KIND, o.toString(), System.currentTimeMillis()))
        UiBus.bump()
    }

    /** Países disponíveis para escolher: (iso, nome em português), das bandeiras já baixadas do Flagpedia, em ordem alfabética. */
    fun countries(filesDir: File): List<Pair<String, String>> =
        (File(filesDir, "flags").listFiles()?.filter { it.name.endsWith(".png") }?.map { it.name.removeSuffix(".png") } ?: emptyList())
            .mapNotNull { iso -> Flags.countryName(iso)?.let { iso to it } }
            .sortedBy { Txt.norm(it.second) }
}
