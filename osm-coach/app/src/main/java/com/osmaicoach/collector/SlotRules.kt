package com.osmaicoach.collector

import org.json.JSONObject

/**
 * Regras especiais de uma liga (grupo de amigos/humanos), coladas pelo usuário no slot (plano `rules`, texto livre).
 * O texto é lido por padrões; o que não for reconhecido continua indo inteiro para a IA ("regras_especiais_da_liga").
 * Sem texto no slot, nada muda: vale o comportamento padrão do app.
 */
object SlotRules {
    data class Rules(
        val text: String,
        /** Over máximo para COMPRAR jogador. */
        val maxBuy: Int? = null,
        /** Over máximo de TREINO (o jogador não deve passar disso treinando). */
        val maxTrain: Int? = null,
        /** Over máximo do craque (o jogador escolhido como craque). */
        val maxStar: Int? = null,
        val noTransferSecondHalf: Boolean = false,
        val maxSquad: Int? = null,
        /** Dias seguidos sem entrar na liga que a regra permite (passou, punição). */
        val maxAwayDays: Int? = null,
        /** Nacionalidade da liga: N jogadores que entram em campo pelo menos M vezes. */
        val natCount: Int? = null,
        val natGames: Int? = null,
        val evolveX: Int? = null,
        val lastRelegated: Boolean = false
    ) {
        val any: Boolean get() = text.isNotBlank()
    }

    private fun num(t: String, rx: String): Int? = Regex(rx).find(t)?.groupValues?.get(1)?.toIntOrNull()

    fun parse(raw: String): Rules {
        val t = Txt.norm(raw).replace("*", " ").replace(Regex("\\s+"), " ")
        return Rules(
            text = raw.trim(),
            maxBuy = num(t, "comprar jogador acima (?:do |de )?over (\\d{2,3})"),
            maxTrain = num(t, "ultrapassar over (?:de )?trein\\w* (\\d{2,3})"),
            maxStar = num(t, "craque ultrapassar over (\\d{2,3})"),
            noTransferSecondHalf = Regex("proibido transferencias? no segundo turno").containsMatchIn(t),
            maxSquad = num(t, "mais de (\\d{2}) jogadores no elenco"),
            maxAwayDays = num(t, "mais de (\\d{1,2}) dias sem entrar"),
            natCount = num(t, "obrigatorio ter (\\d{1,2}) jogadores da nacionalidade"),
            natGames = num(t, "nacionalidade.{0,80}?(\\d{1,2}) vezes"),
            evolveX = num(t, "evoluir no minimo (\\d{1,2}) ?x"),
            lastRelegated = t.contains("ultimo treinador e rebaixado")
        )
    }

    suspend fun load(repo: Repo, slot: Int): Rules? {
        val j = repo.dao.plan(slot, "rules")?.json ?: return null
        val txt = runCatching { JSONObject(j).optString("text") }.getOrDefault("")
        return if (txt.isBlank()) null else parse(txt)
    }

    suspend fun save(repo: Repo, slot: Int, text: String, now: Long) {
        val body = if (text.isBlank()) "{}" else JSONObject().put("text", text.trim()).toString()
        repo.dao.putPlan(PlanEntity(slot, "rules", body, now))
    }

    /** O que o app entendeu do texto (mostrado no cartão para o usuário conferir). */
    fun summary(r: Rules): List<String> = buildList {
        r.maxBuy?.let { add("Comprar: até over $it") }
        r.maxTrain?.let { add("Treino: até over $it") }
        r.maxStar?.let { add("Craque: até over $it") }
        r.maxSquad?.let { add("Elenco: até $it jogadores") }
        if (r.noTransferSecondHalf) add("Sem transferências no 2º turno")
        r.maxAwayDays?.let { add("Entrar na liga a cada ${it} dias") }
        if (r.natCount != null) add("${r.natCount} jogadores da nacionalidade da liga" + (r.natGames?.let { ", ${it} jogos cada" } ?: ""))
        r.evolveX?.let { add("Evoluir ${it}× o valor inicial") }
        if (r.lastRelegated) add("Último treinador é rebaixado")
    }

    /** Texto para o prompt da IA: obrigatório respeitar (compra, treino, craque, elenco, escalação). */
    fun promptText(r: Rules): String =
        "REGRAS ESPECIAIS DESTA LIGA (obrigatórias; valem mais que as dicas gerais; não sugira nada que as quebre):\n" + r.text.take(1800)

    /** Dicas por slot. `late` = 2º turno (rodada > metade). Pendências usam ícones de ação; informação usa 📜. */
    fun tips(r: Rules, players: List<PlayerEntity>, xi: Set<String>, round: Int?, roundTotal: Int?, lastReadAt: Long?, now: Long): List<Playbook.Tip> {
        val out = ArrayList<Playbook.Tip>()
        val mine = players.filter { it.owner == "MY" }
        fun nm(p: PlayerEntity) = "${p.name} (${p.strength})"
        val tr = r.maxTrain
        if (tr != null) {
            val training = mine.filter { it.training == true && (it.strength ?: 0) >= tr }
            if (training.isNotEmpty()) out.add(Tip("🏋", "Tire do treino (limite over $tr): " + training.take(4).joinToString { nm(it) }, "A liga proíbe ultrapassar over $tr treinando" + (r.maxStar?.let { " (só o craque vai até $it)" } ?: "") + ". Pare o treino desses jogadores e use o treinador em outro.", 97, 2))
            val near = mine.filter { it.training != true && (it.strength ?: 0) >= tr - 1 && (it.strength ?: 0) < tr }
            if (near.isNotEmpty()) out.add(Tip("📜", "Perto do limite de treino ($tr): " + near.take(4).joinToString { nm(it) }, "Mais um ponto e passam do que a liga permite. Treine outros jogadores.", 40, 2))
        }
        val star = r.maxStar
        if (star != null) {
            val over = mine.filter { (it.strength ?: 0) > star }
            if (over.isNotEmpty()) out.add(Tip("🚨", "Acima do limite de craque ($star): " + over.take(4).joinToString { nm(it) }, "Pela regra da liga, jogador acima do over permitido é venda instantânea (permitida para este caso).", 96, 6))
        }
        val maxSq = r.maxSquad
        if (maxSq != null && mine.size > maxSq) out.add(Tip("🚨", "Elenco com ${mine.size} jogadores: limite da liga é $maxSq", "Venda ${mine.size - maxSq} (os que sobram nas posições) antes de passar da regra.", 95, 6))
        else if (maxSq != null && mine.size == maxSq) out.add(Tip("📜", "Elenco no limite da liga ($maxSq)", "Para comprar alguém, venda outro antes.", 30, 6))
        val late = round != null && roundTotal != null && round > roundTotal / 2
        if (r.noTransferSecondHalf) {
            if (late) out.add(Tip("📜", "2º turno: transferências proibidas nesta liga", "Rodada $round/$roundTotal: não compre nem liste jogadores (vale a regra da liga).", 60, 6))
            else if (round != null && roundTotal != null) out.add(Tip("📜", "Transferências só até a rodada ${roundTotal / 2}", "Depois da metade do campeonato a liga proíbe comprar e vender: monte o elenco agora.", 35, 6))
        }
        val away = r.maxAwayDays
        if (away != null && lastReadAt != null) {
            val h = (now - lastReadAt) / 3_600_000L
            if (h >= (away - 1) * 24L) out.add(Tip("⏰", "Entre na liga hoje: ${h / 24} dia(s) sem entrar (limite $away)", "A liga pune ficar mais de $away dias sem entrar. Abra o jogo neste slot.", 98, 0))
        }
        if (r.natCount != null) out.add(Tip("📜", "Regra: ${r.natCount} jogadores da nacionalidade da liga" + (r.natGames?.let { ", cada um com $it jogos" } ?: ""), "Escale esses jogadores o bastante para cumprir a cota (o app ainda não lê nacionalidade nem jogos por jogador: confira na tela do elenco).", 45, 4))
        r.evolveX?.let { out.add(Tip("📜", "Meta da liga: valor do time ${it}× o inicial", "Treine jovens e valorize o elenco; confira o valor do plantel na tabela da liga.", 30, 0)) }
        return out.map { it }
    }

    private fun Tip(icon: String, title: String, why: String, prio: Int, tab: Int) = Playbook.Tip(icon, title, why, prio, tab)
}
