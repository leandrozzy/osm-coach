package com.osmaicoach.collector

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tática SUGERIDA × tática REALMENTE JOGADA (24/11).
 *
 * A recomendação do app (plano `tactic` / `tlog_R*`) fica separada do que foi aplicado no OSM. O que foi aplicado vem de leituras da tela de Tática
 * (origem "tela"), da formação mostrada no Resultado (origem "resultado") ou da confirmação manual do usuário (origem "manual"), sempre com a hora.
 * O que o OCR não confirmou fica DESCONHECIDO (nunca é completado com o valor da recomendação).
 *
 * Classificação do jogo: "alterou" (basta UMA divergência comprovada), "seguiu" (TODOS os campos comparados confirmados e iguais) ou "nao_confirmado".
 * A partida é identificada por slot + competição + total de rodadas + rodada + rival (não só o número da rodada).
 */
object PlayedTactic {
    const val SEGUIU = "seguiu"
    const val ALTEROU = "alterou"
    const val NAO = "nao_confirmado"

    /** Campos comparados, na ordem da tabela da aba Tática (sem "lados" nem "bolas paradas"). */
    val FIELDS = listOf("formation", "playStyle", "pressure", "mentality", "tempo", "marking", "offside", "tackle", "advAttack", "advMid", "advDef")
    val LABEL = mapOf(
        "formation" to "Formação", "playStyle" to "Estilo de jogo", "pressure" to "Pressão", "mentality" to "Mentalidade", "tempo" to "Temporização",
        "marking" to "Marcação", "offside" to "Impedimento", "tackle" to "Desarme", "advAttack" to "Avançados", "advMid" to "Médios", "advDef" to "Defesas"
    )
    val SLIDERS = setOf("pressure", "mentality", "tempo")
    /** Controles 0–100: até 5 pontos de diferença contam como o mesmo valor (o usuário arrasta o controle, não digita). */
    const val SLIDER_TOL = 5

    /** Valores que o usuário pode confirmar à mão em cada campo (sliders: número 0–100). */
    fun options(field: String): List<String> = when (field) {
        "formation" -> Formations.ALL
        "playStyle" -> Osm.STYLES
        "marking" -> Osm.MARKING
        "tackle" -> Osm.TACKLES
        "offside" -> listOf("Sim", "Não")
        "advAttack" -> Osm.ATTACK
        "advMid" -> Osm.MIDFIELD
        "advDef" -> Osm.DEFENSE
        else -> emptyList()
    }

    /** Valor canônico de um campo ("4-3-3 A", "Jogo de passe", "70"…); null = ilegível/desconhecido. */
    fun norm(field: String, raw: String?): String? {
        val v = raw?.trim()?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        return when (field) {
            "formation" -> Formations.canonical(v)
            "playStyle" -> Osm.style(v)
            "marking" -> Osm.marking(v)
            "tackle" -> Osm.tackle(v)
            "offside" -> when (Txt.norm(v)) { "sim", "yes" -> "Sim"; "nao", "no" -> "Não"; else -> null }
            "advAttack" -> Osm.attack(v)
            "advMid" -> Osm.midfield(v)
            "advDef" -> Osm.defense(v)
            in SLIDERS -> v.toDoubleOrNull()?.toInt()?.takeIf { it in 0..100 }?.toString()
            else -> null
        }
    }

    fun same(field: String, a: String, b: String): Boolean =
        if (field in SLIDERS) Math.abs(a.toInt() - b.toInt()) <= SLIDER_TOL else a == b

    // ------------------------------------------------------------------ identidade da partida
    /** slot | competição | total de rodadas | rodada | rival. Competição nova (ou outro slot) = partida diferente, mesmo com a mesma rodada. */
    fun matchId(slot: Int, comp: String?, total: String?, round: Int?, rival: String?): String =
        listOf(slot.toString(), Txt.key(comp ?: "").take(40), total?.filter { it.isDigit() }.orEmpty(), (round ?: -1).toString(), Txt.key(rival ?: "").take(40)).joinToString("|")

    fun matchId(slot: Int, f: Map<String, StoredField>): String =
        matchId(slot, f[K.COMPETITION]?.value ?: f[K.HUB_TITLE]?.value, f[K.ROUND_TOTAL]?.value, f[K.ROUND]?.value?.filter { it.isDigit() }?.toIntOrNull(), f[K.RIVAL_TEAM]?.value)

    fun kind(id: String): String = "played_" + Integer.toHexString(id.hashCode())

    // ------------------------------------------------------------------ registro do que foi jogado
    /** Junta leituras ao registro da partida (cada campo guarda valor, origem e hora). Valores ilegíveis são ignorados, nunca preenchidos. */
    fun merge(old: JSONObject?, id: String, round: Int?, rival: String?, seen: Map<String, String>, src: String, now: Long): JSONObject {
        val j = old ?: JSONObject().put("id", id)
        j.put("round", round ?: -1).put("rival", rival ?: "")
        val fields = j.optJSONObject("fields") ?: JSONObject().also { j.put("fields", it) }
        for ((k, raw) in seen) {
            if (k !in FIELDS) continue
            val v = norm(k, raw) ?: continue
            val prev = fields.optJSONObject(k)
            // manual vale mais que leitura automática: uma leitura de tela não apaga a sua confirmação (a menos que a tela traga outro valor, que é mais recente)
            if (prev != null && prev.optString("src") == "manual" && src != "manual" && prev.optString("v") == v) continue
            fields.put(k, JSONObject().put("v", v).put("src", src).put("at", now))
        }
        j.put("at", now)
        return j
    }

    suspend fun record(repo: Repo, slot: Int, seen: Map<String, String>, src: String, now: Long = System.currentTimeMillis()) {
        if (seen.isEmpty()) return
        val f = repo.fieldMap(slot)
        val id = matchId(slot, f)
        val k = kind(id)
        val old = repo.dao.plan(slot, k)?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
        val round = f[K.ROUND]?.value?.filter { it.isDigit() }?.toIntOrNull()
        repo.dao.putPlan(PlanEntity(slot, k, merge(old, id, round, f[K.RIVAL_TEAM]?.value, seen, src, now).toString(), now))
        UiBus.bump()
    }

    suspend fun current(repo: Repo, slot: Int): JSONObject? {
        val f = repo.fieldMap(slot)
        return repo.dao.plan(slot, kind(matchId(slot, f)))?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    /** Registro da partida de um jogo já jogado (usa a identidade guardada no tlog: rodada, rival e a competição atual do slot). */
    suspend fun forLog(repo: Repo, slot: Int, round: Int, rival: String?): JSONObject? {
        val f = repo.fieldMap(slot)
        val id = matchId(slot, f[K.COMPETITION]?.value ?: f[K.HUB_TITLE]?.value, f[K.ROUND_TOTAL]?.value, round, rival)
        return repo.dao.plan(slot, kind(id))?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    fun cell(played: JSONObject?, field: String): JSONObject? = played?.optJSONObject("fields")?.optJSONObject(field)

    // ------------------------------------------------------------------ classificação
    class Diff(val field: String, val suggested: String, val played: String)
    class Verdict(val usage: String, val diffs: List<Diff>, val unknown: List<String>, val compared: Int) {
        fun text(): String = when (usage) {
            SEGUIU -> "Seguiu a sugestão (todos os $compared campos confirmados)"
            ALTEROU -> "Alterou: " + diffs.joinToString("; ") { "${LABEL[it.field]} ${it.played} (sugerido ${it.suggested})" }
            else -> "Não confirmado: " + (if (unknown.isEmpty()) "sem campos para comparar" else "falta confirmar " + unknown.joinToString(", ") { LABEL[it] ?: it })
        }
    }

    /** Campos da sugestão que existem (têm valor legível). */
    fun suggestedFields(sug: JSONObject): Map<String, String> = FIELDS.mapNotNull { f -> norm(f, sug.opt(f)?.toString())?.let { f to it } }.toMap()

    /**
     * Compara a SUGESTÃO com o que foi jogado. [extra] = confirmações de outras fontes (ex.: formação lida no Resultado).
     * "alterou": uma divergência comprovada basta. "seguiu": todos os campos comparados confirmados e iguais. Senão "nao_confirmado".
     */
    fun classify(sug: JSONObject, played: JSONObject?, extra: Map<String, String> = emptyMap()): Verdict {
        val want = suggestedFields(sug)
        val diffs = ArrayList<Diff>(); val unknown = ArrayList<String>()
        for ((f, sv) in want) {
            val pv = cell(played, f)?.optString("v")?.takeIf { it.isNotBlank() } ?: extra[f]?.let { norm(f, it) }
            if (pv == null) { unknown.add(f); continue }
            if (!same(f, sv, pv)) diffs.add(Diff(f, sv, pv))
        }
        val usage = when {
            diffs.isNotEmpty() -> ALTEROU
            want.isNotEmpty() && unknown.isEmpty() -> SEGUIU
            else -> NAO
        }
        return Verdict(usage, diffs, unknown, want.size)
    }

    // ------------------------------------------------------------------ uso no tlog (aprendizado)
    /**
     * Uso registrado no jogo: o campo `usage` quando existe; registro ANTIGO (sem o campo) só conta como "alterou" se já tinha evidência de troca
     * (`deviated`); sem evidência nunca conta como "seguiu".
     */
    fun usageOf(log: JSONObject): String = when {
        log.has("usage") -> log.optString("usage")
        log.optBoolean("deviated", false) -> ALTEROU
        else -> NAO
    }

    /**
     * O resultado deste jogo pode ser atribuído à tática registrada? Registro novo "nao_confirmado" NÃO pode (a configuração jogada é desconhecida).
     * Registro antigo (sem `usage`) continua valendo como antes, para não apagar o histórico de aprendizado; ele só não conta como "seguiu".
     */
    fun attributable(log: JSONObject): Boolean = !(log.has("usage") && log.optString("usage") == NAO)

    /** Previsão registrada na geração só mede a SUGERIDA se o jogo foi "seguiu" (novo) ou é registro antigo sem usage (não conta como nada além do que já era). */
    fun confirmedOrLegacy(log: JSONObject): Boolean = !log.has("usage") || log.optString("usage") == SEGUIU

    /** Atribuição estrita (comparar sugerida × jogada): só "seguiu" ou "alterou" comprovados. */
    fun confirmed(log: JSONObject): Boolean = usageOf(log).let { it == SEGUIU || it == ALTEROU }

    /**
     * Aplica a classificação ao tlog: guarda a sugestão original (`sug`) uma vez, o veredito (`usage`, `usageDiffs`, `usageUnknown`) e, quando alterou,
     * troca no registro a configuração pela JOGADA (mantendo `gen*` com a sugerida), como já fazia o aprendizado.
     */
    fun applyToLog(t: JSONObject, played: JSONObject?, extra: Map<String, String> = emptyMap()): Verdict {
        val sug = t.optJSONObject("sug") ?: JSONObject().also { s ->
            // a sugestão original é o que o app gerou: se o registro já foi trocado por uma versão anterior do app, usa os gen*
            for (f in FIELDS) {
                val gen = t.optString("gen" + f.replaceFirstChar { it.uppercase() }).takeIf { it.isNotBlank() }
                val cur = if (gen != null) gen else t.opt(f)?.toString()
                if (cur != null && cur != "null") s.put(f, cur)
            }
            t.put("sug", s)
        }
        val v = classify(sug, played, extra)
        t.put("usage", v.usage)
        t.put("usageDiffs", JSONArray(v.diffs.map { "${it.field}:${it.suggested}>${it.played}" }))
        t.put("usageUnknown", JSONArray(v.unknown))
        t.put("deviated", v.usage == ALTEROU)
        if (v.usage == ALTEROU) {
            for (d in v.diffs) {
                t.put("gen" + d.field.replaceFirstChar { it.uppercase() }, d.suggested)
                if (d.field in SLIDERS) t.put(d.field, d.played.toInt()) else t.put(d.field, d.played)
            }
        }
        return v
    }
}
