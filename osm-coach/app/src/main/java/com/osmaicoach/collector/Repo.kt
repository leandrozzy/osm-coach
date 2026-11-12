package com.osmaicoach.collector

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Rodada do relatório de resultado em andamento (o cabeçalho com "Jornada N" só aparece no topo da tela). */
object ResultCtx {
    var slot: Int = 0
    var round: Int? = null
    var at: Long = 0L
}

/** Último momento em que cada aviso do registro foi gravado (compartilhado: o app cria vários Repo). */
private val LEARN_SEEN = java.util.concurrent.ConcurrentHashMap<String, Long>()

class Repo(private val ctx: Context) {
    val dao: CoachDao = CoachDb.get(ctx).dao()

    init {
        // formações já vistas nas telas do OSM (ex. 3-3-2-2, 3-4-3 B) entram no catálogo do motor
        val saved = ctx.getSharedPreferences("formations", Context.MODE_PRIVATE).getStringSet("seen", emptySet()) ?: emptySet()
        for (f in saved) Formations.learn(f)
    }

    private fun rememberFormation(v: String) {
        if (Formations.learn(v)) {
            ctx.getSharedPreferences("formations", Context.MODE_PRIVATE).edit()
                .putStringSet("seen", Formations.seenList().toSet()).apply()
        }
    }

    private suspend fun putFields(slot: Int, readings: Map<String, Reading>, source: String, now: Long): Int {
        if (readings.isEmpty()) return 0
        val old = dao.fieldsOf(slot).associateBy { it.fkey }
        var changed = 0
        for ((k, r) in readings) {
            val o = old[k]?.let { StoredField(it.fvalue, it.conf, it.updatedAt) }
            // leitura de uma tela mais antiga que o dado guardado (reprocessamento, telas aplicadas com atraso) não volta atrás
            if (o != null && now < o.updatedAt && FieldMerge.known(o.value)) continue
            if (k in CHECKED && o != null && old[k]?.source != "manual") noteConflict(slot, k, o, r.value, now)
            val n = FieldMerge.merge(k, o, r, now) ?: continue
            // mesma leitura repetida: só renova a data de leitura (mantém a origem e não conta como mudança)
            if (o != null && o.value == n.value && n.conf == o.conf) {
                dao.putField(FieldEntity(slot, k, n.value, n.conf, n.updatedAt, old[k]?.source ?: source))
                continue
            }
            dao.putField(FieldEntity(slot, k, n.value, n.conf, n.updatedAt, source))
            if (k == K.RIVAL_FORMATION || k == K.MY_FORMATION) rememberFormation(n.value)
            changed++
        }
        return changed
    }

    /** Números que decidem a tática: duas leituras recentes muito diferentes viram aviso para conferir. */
    private val CHECKED = setOf(
        K.MY_STRENGTH, K.RIVAL_STRENGTH, K.RIVAL_GOL, K.RIVAL_DEF, K.RIVAL_MID, K.RIVAL_ATK, K.MY_GOL, K.MY_DEF, K.MY_MID, K.MY_ATK
    )

    private suspend fun noteConflict(slot: Int, k: String, o: StoredField, nv: String, now: Long) {
        val a = o.value.toIntOrNull() ?: return
        val b = nv.toIntOrNull() ?: return
        val j = planJson(slot, "conflicts")
        if (Math.abs(a - b) >= 8 && now - o.updatedAt < 6L * 3600000L) {
            j.put(k, org.json.JSONObject().put("a", a).put("b", b).put("at", now))
        } else if (a == b && j.has(k)) {
            j.remove(k) // a mesma leitura de novo confirma o valor
        } else return
        dao.putPlan(PlanEntity(slot, "conflicts", j.toString(), now))
    }

    suspend fun learn(slot: Int, kind: String, text: String, now: Long = System.currentTimeMillis()) {
        // o mesmo aviso (ex.: "elenco de dono desconhecido ignorado") a cada quadro lotava o registro: 1 vez a cada 30 min
        val key = "$slot|$kind|${text.take(300)}"
        val prev = LEARN_SEEN[key]
        if (prev != null && now - prev < 30L * 60_000L) return
        LEARN_SEEN[key] = now
        dao.insertLearning(LearningEntity(at = now, slotId = slot, kind = kind, text = text.take(300)))
    }

    suspend fun fieldMap(slot: Int): Map<String, StoredField> =
        dao.fieldsOf(slot).associate { it.fkey to StoredField(it.fvalue, it.conf, it.updatedAt) }

    /** Rodadas que já têm placar lido da análise do jogo. */
    suspend fun scoredRounds(slot: Int): Set<Int> = dao.matchReports(slot).mapNotNull { p ->
        val r = p.kind.removePrefix("mr_R").toIntOrNull() ?: return@mapNotNull null
        val j = try { org.json.JSONObject(p.json) } catch (e: Exception) { return@mapNotNull null }
        if (j.has("sh") && j.has("sa")) r else null
    }.toSet()

    /**
     * Grava a rodada atual e o horário conferidos (Fixtures.clock), para todas as telas, o Diretor e as
     * notificações enxergarem o mesmo próximo jogo. A rodada digitada à mão só é corrigida para frente
     * (quando já há placar dela).
     */
    suspend fun syncRound(slot: Int, now: Long = System.currentTimeMillis()): Fixtures.Clock {
        val rows = dao.fieldsOf(slot)
        val f = rows.associate { it.fkey to StoredField(it.fvalue, it.conf, it.updatedAt) }
        val ms = dao.matchesOf(slot)
        val c0 = Fixtures.clock(ms, scoredRounds(slot), now, f)
        // Não avança sozinho por horário (12/11): o rival/rodada só mudam quando uma tela lida (Resultado, Pré-jogo) mostra o jogo seguinte.
        val c = c0
        // competição que ainda não começou: o hub não mostra "x/y" e as rodadas concluídas ficavam "NI" (S3, 11/11).
        // Concluídas = próxima rodada − 1; qualquer leitura do hub/resultado (fonte ≠ sync) passa por cima.
        val dn = rows.firstOrNull { it.fkey == K.ROUND_DONE }
        if (c.round != null && c.round >= 1 && (dn == null || dn.source == "sync") && dn?.fvalue?.toIntOrNull() != c.round - 1) {
            dao.putField(FieldEntity(slot, K.ROUND_DONE, (c.round - 1).toString(), 0.5, now, "sync"))
        }
        val cur = rows.firstOrNull { it.fkey == K.ROUND }
        val old = cur?.fvalue?.toIntOrNull()
        if (c.round != null && c.round != old && (cur?.source != "manual" || (old != null && c.round > old))) {
            dao.putField(FieldEntity(slot, K.ROUND, c.round.toString(), 0.8, now, "sync"))
            // rodada avançou: bônus lidos antes (de outro jogo) saem; o pré-jogo novo lê os de agora
            if (old != null && c.round > old) {
                for (row in rows) if (row.fkey in FieldMerge.PER_GAME && now - row.updatedAt > 2L * 3600000L) dao.deleteField(slot, row.fkey)
            }
        }
        if (c.at == null && f.containsKey(K.MATCH_AT)) dao.deleteField(slot, K.MATCH_AT)
        return c
    }

    /** Passa o slot para o jogo seguinte do calendário: rodada, horário estimado e o rival do card (até ler o pré-jogo). */
    private suspend fun advanceTo(slot: Int, adv: Fixtures.Advance, f: Map<String, StoredField>, now: Long) {
        val r = adv.clock.round ?: return
        dao.putField(FieldEntity(slot, K.ROUND, r.toString(), 0.8, now, "sync"))
        if (adv.clock.at != null) dao.putField(FieldEntity(slot, K.MATCH_AT, adv.clock.at.toString(), 0.6, now, "sync"))
        else dao.deleteField(slot, K.MATCH_AT)
        val opp = Fixtures.opponent(adv.match)
        val old = f[K.RIVAL_TEAM]?.value
        if (opp != null && (old == null || Txt.sim(Txt.key(old), Txt.key(opp)) < 0.8)) {
            // os dados lidos eram do rival do jogo que já começou
            dao.deleteRivalFields(slot)
            dao.deleteRivalPlayers(slot)
            dao.deleteField(slot, K.MY_BONUS)
            dao.deleteField(slot, K.REFEREE)
            dao.deletePlan(slot, "evidence")
            dao.putField(FieldEntity(slot, K.RIVAL_TEAM, opp, 0.6, now, "calendar"))
            adv.match.opponentNick?.takeIf { Evidence.plausibleNick(it) }?.let {
                dao.putField(FieldEntity(slot, K.RIVAL_NICK, it, 0.6, now, "calendar"))
                dao.putField(FieldEntity(slot, K.RIVAL_HUMAN, "Sim", 0.6, now, "calendar"))
            }
        }
        adv.match.home?.let { dao.putField(FieldEntity(slot, K.HOME, if (it) "Casa" else "Fora", 0.6, now, "calendar")) }
        learn(
            slot, "rodada",
            "O J${r - 1} já começou: passei para o J$r" + (opp?.let { " vs $it" } ?: "") +
                " pelo calendário. Abra o pré-jogo no OSM para ler o rival e gere a tática.", now
        )
    }

    /** Identidades conhecidas de cada slot (para reconhecer o slot sem passar pela central). */
    suspend fun knownIdentities(): List<SlotIdentity> {
        val out = ArrayList<SlotIdentity>()
        for (slot in 1..4) {
            val f = fieldMap(slot)
            val names = HashSet<String>()
            f[K.TEAM]?.let { names.add(Txt.key(it.value)) }
            f[K.HUB_TITLE]?.let { names.add(Txt.key(it.value)) }
            names.removeAll { it.length < 3 }
            val rivals = HashSet<String>()
            f[K.RIVAL_TEAM]?.value?.takeIf { FieldMerge.known(it) }?.let { rivals.add(Txt.key(it)) }
            f[K.RIVAL_NICK]?.value?.takeIf { FieldMerge.known(it) && Evidence.plausibleNick(it) }?.let { rivals.add(Txt.key(it)) }
            rivals.removeAll { it.length < 4 }
            if (names.isEmpty() && rivals.isEmpty()) continue
            out.add(SlotIdentity(slot, names, f[K.ROUND]?.value?.toIntOrNull(), rivals))
        }
        return out
    }

    /** Aplica uma extração. Só escreve no slot atribuído; sem slot confiável, nada é gravado. */
    suspend fun apply(slot: Int?, ex: Extraction, source: String, now: Long): Int {
        if (ex.type == ScreenType.HUB) return applyHub(ex, now)
        if (ex.type == ScreenType.MISSIONS) return applyExtra(0, ex, now)
        if (slot == null) return 0
        val n = applyTyped(slot, ex, source, now)
        // registra que a tela foi lida (base do "telas para atualizar"): só se trouxe conteúdo de verdade
        val meaningful = when (ex.type) {
            // tela reconhecida pelo classificador = você passou por ela (mesmo sem treinador/obra/listagem para ler)
            ScreenType.TRAINING, ScreenType.STADIUM, ScreenType.MARKET -> true
            ScreenType.PREGAME -> ex.fields.containsKey(K.RIVAL_TEAM) || ex.fields.containsKey(K.MY_STRENGTH)
            ScreenType.REPORT -> ex.fields.size >= 3
            ScreenType.CALENDAR -> ex.matches.size >= 3
            ScreenType.RESULT -> ex.matchReport != null
            else -> false
        }
        if (meaningful) runCatching { noteScreen(slot, ex.type, now) }
        if (n > 0 && ex.type in setOf(ScreenType.PREGAME, ScreenType.REPORT, ScreenType.SQUAD)) {
            runCatching { checkRivalChange(slot, now) }
        }
        return n
    }

    /**
     * Depois de gerar a tática, uma leitura nova mostrou o rival diferente (formação, estilo, marcação, árbitro…):
     * guarda o que mudou e avisa (notificação + aviso por cima do OSM) uma vez por conjunto de mudanças.
     */
    suspend fun checkRivalChange(slot: Int, now: Long = System.currentTimeMillis()) {
        val p = dao.plan(slot, "tactic") ?: return
        val j = runCatching { org.json.JSONObject(p.json) }.getOrNull() ?: return
        val f = fieldMap(slot)
        if (!Director.tacticValid(j, p.at, f, now)) return
        val changes = RivalWatch.diff(j.optJSONObject("basis"), f)
        if (changes.isEmpty()) {
            dao.deletePlan(slot, "rivalchange")
            return
        }
        val sig = changes.joinToString("|") { it.key + "=" + it.to }
        val old = dao.plan(slot, "rivalchange")?.json?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
        if (old?.optString("sig") == sig) return
        dao.putPlan(PlanEntity(slot, "rivalchange", RivalWatch.toJson(changes, now).put("sig", sig).toString(), now))
        val rival = f[K.RIVAL_TEAM]?.value ?: "rival"
        learn(slot, "rival", "Rival mudou depois da tática: " + RivalWatch.headline(changes) + ". Gere a tática de novo.", now)
        Notifier.rivalChanged(ctx, slot, rival, changes)
        CaptureService.instance?.notice("⚠ S$slot: $rival mudou — " + RivalWatch.headline(changes) + ". Gere uma nova tática.")
    }

    private suspend fun applyTyped(slot: Int, ex: Extraction, source: String, now: Long): Int {
        return when (ex.type) {
            ScreenType.PREGAME -> applyPregame(slot, ex, source, now)
            ScreenType.SQUAD -> applySquad(slot, ex, source, now)
            ScreenType.CALENDAR -> applyCalendar(slot, ex, source, now)
            ScreenType.MARKET -> applyMarket(slot, ex, source, now)
            ScreenType.REPORT -> {
                val n = putTracked(slot, ex.fields, source, "REPORT", now)
                recordRivalProfile(slot, ex.fields, now)
                n
            }
            ScreenType.STADIUM -> {
                if (ex.timers.isNotEmpty()) runCatching { updateTimers(slot, ex.timers.mapKeys { "stad_" + it.key }.mapValues { (k, v) -> v?.let { Pair(it, "obra: ${k.removePrefix("stad_")}") } }, now) }
                putFields(slot, ex.fields, source, now)
            }
            ScreenType.RESULT -> applyResult(slot, ex, now)
            ScreenType.TRAINING -> applyTraining(slot, ex.trainees, now)
            ScreenType.PREP, ScreenType.DOCTOR, ScreenType.LAWYER, ScreenType.SPECIALISTS, ScreenType.SCOUT,
            ScreenType.DEALS, ScreenType.STATS -> applyExtra(slot, ex, now)
            else -> 0
        }
    }

    private suspend fun applyHub(ex: Extraction, now: Long): Int {
        var changed = 0
        for (c in ex.hubCards) {
            val rows = dao.fieldsOf(c.slot).associateBy { it.fkey }
            val prev = rows[K.SLOT_STATE]?.fvalue
            // pré-época na central, mas o pré-jogo da competição nova já foi lido (jornada 1-2): o slot está ativo
            if (c.state == "preepoca" && prev == "ativo" && (rows[K.ROUND]?.fvalue?.toIntOrNull() ?: 99) <= 2) {
                // o slot segue "ativo", mas o card da central mostra o NOME da competição nova ("Brasileirão UZM"): sem isto o app mostrava
                // para sempre a liga anterior ("Superliga Uzbeque", lida em 01/11). Total de rodadas da liga antiga sai (o novo vem com o "x/y").
                if (c.team.isNotBlank()) changed += hubPut(c.slot, K.HUB_TITLE, Txt.titleCase(c.team), now)
                if (c.subtitle.isNotBlank()) {
                    val oldComp = rows[K.COMPETITION]?.fvalue
                    val newComp = Txt.titleCase(c.subtitle)
                    if (oldComp != null && Txt.sim(Txt.key(oldComp), Txt.key(newComp)) < 0.6) { dao.deleteField(c.slot, K.ROUND_TOTAL); dao.deleteField(c.slot, K.COMP_TYPE) }
                    changed += hubPut(c.slot, K.COMPETITION, newComp, now)
                }
                continue
            }
            if (c.state != null) {
                // Slot livre, procurando adversário ou pré-época: a competição anterior acabou. Só arquiva quando DUAS
                // leituras da central (com 15 s ou mais entre elas) mostram o mesmo estado; nunca apaga os campos
                // do clube, só o que é da competição (calendário, relatórios, tática, rival).
                if (prev != c.state) {
                    val pend = rows[K.SLOT_PENDING]?.fvalue
                    val pState = pend?.substringBefore('|')
                    val pAt = pend?.substringAfter('|')?.toLongOrNull()
                    if (pState != c.state || pAt == null) {
                        dao.putField(FieldEntity(c.slot, K.SLOT_PENDING, "${c.state}|$now", 0.9, now, "hub"))
                        continue
                    }
                    if (now - pAt < 15000L) continue
                    // só arquiva competição com jogo jogado (a nova, ainda sem placar, nunca é arquivada)
                    val hadComp = (rows[K.ROUND_DONE]?.fvalue?.toIntOrNull() ?: 0) >= 1 ||
                        dao.matchesOf(c.slot).any { it.result != null || it.scoreMine != null }
                    if (hadComp && prev != "vazio" && prev != "procurando" && prev != "preepoca") {
                        archiveSeason(c.slot, now, false)
                        dao.deleteRivalFields(c.slot)
                        learn(
                            c.slot, "temporada",
                            when (c.state) {
                                "vazio" -> "Slot livre no OSM: a competição anterior foi arquivada (o aprendizado das táticas continua)."
                                "procurando" -> "Nova batalha procurando adversário: a competição anterior foi arquivada."
                                else -> "Competição nova em pré-época${if (c.team.isNotBlank()) " (" + Txt.titleCase(c.team) + ")" else ""}: a anterior foi arquivada."
                            }, now
                        )
                    }
                    dao.putField(FieldEntity(c.slot, K.SLOT_STATE, c.state, 0.9, now, "hub"))
                    dao.deleteField(c.slot, K.SLOT_PENDING)
                    changed++
                }
                if (c.state == "preepoca" && c.team.isNotBlank()) changed += hubPut(c.slot, K.HUB_TITLE, Txt.titleCase(c.team), now)
                if (c.state != "vazio" && c.subtitle.isNotBlank()) {
                    changed += hubPut(c.slot, K.COMPETITION, Txt.titleCase(c.subtitle), now)
                    if (Txt.norm(c.subtitle).contains("batalha")) changed += hubPut(c.slot, K.COMP_TYPE, "Batalha", now)
                }
                continue
            }
            if (rows.containsKey(K.SLOT_PENDING)) dao.deleteField(c.slot, K.SLOT_PENDING)
            if (prev != null && prev != "ativo") {
                dao.putField(FieldEntity(c.slot, K.SLOT_STATE, "ativo", 0.9, now, "hub"))
                changed++
            }
            // card com "x/y JORNADA": título e competição só entram pelo merge normal (não trocam o que já foi
            // confirmado por outras telas); a troca de competição é decidida pelo pré-jogo/calendário (rodada que volta)
            val r = LinkedHashMap<String, Reading>()
            r[K.HUB_TITLE] = Reading(Txt.titleCase(c.team), 0.8)
            if (c.subtitle.isNotBlank()) r[K.COMPETITION] = Reading(Txt.titleCase(c.subtitle), 0.8)
            c.roundDone?.let { r[K.ROUND_DONE] = Reading(it.toString(), 0.8) }
            c.roundTotal?.let { r[K.ROUND_TOTAL] = Reading(it.toString(), 0.8) }
            val sub = Txt.norm(c.subtitle)
            val type = when {
                sub.contains("batalha") || Txt.norm(c.team).contains(" vs ") -> "Batalha"
                sub.contains("copa") || sub.contains("taca") -> "Copa"
                else -> "Liga"
            }
            r[K.COMP_TYPE] = Reading(type, 0.7)
            if (type == "Batalha") r[K.RIVAL_HUMAN] = Reading("Sim", 0.9)
            changed += runCatching { detectSwitchFromHub(c, type, now) }.getOrDefault(0)
            changed += putFields(c.slot, r, "hub", now)
        }
        return changed
    }

    /**
     * Slot que espera uma competição nova (pré-época, procurando adversário ou livre). Com mais de um, desempata
     * pelos grupos da batalha no título da central ("UZM VS UFN") que aparecem no pré-jogo.
     */
    suspend fun openSlotFor(team: String, screenText: String): Int? {
        val open = (1..4).filter { s -> fieldMap(s)[K.SLOT_STATE]?.value in setOf("preepoca", "procurando", "vazio") }
        if (open.size <= 1) return open.singleOrNull()
        val txt = Txt.norm(screenText)
        val byTag = open.filter { s ->
            val title = Txt.norm(fieldMap(s)[K.HUB_TITLE]?.value ?: "")
            title.split(" ").filter { it.length >= 3 && it != "vs" }.any { tag -> Regex("\\b$tag\\b").containsMatchIn(txt) }
        }
        return byTag.singleOrNull()
    }

    /** O slot passou a ter o meu time novo: troca o time guardado e o slot volta a "ativo". */
    suspend fun startCompetition(slot: Int, team: String, now: Long) {
        val old = dao.fieldsOf(slot).firstOrNull { it.fkey == K.TEAM }
        // competição nova no slot livre: o que era da anterior (liga, total, rodada, regras, elenco do clube antigo) é arquivado/apagado;
        // só o aprendizado (tlog/relatórios arquivados, lições) fica
        if (old != null && Txt.sim(Txt.key(old.fvalue), Txt.key(team)) < 0.85) runCatching { archiveSeason(slot, now, true) }
        else runCatching { archiveSeason(slot, now, false) }
        if (old != null && Txt.sim(Txt.key(old.fvalue), Txt.key(team)) < 0.85) dao.deleteField(slot, K.TEAM)
        dao.putField(FieldEntity(slot, K.SLOT_STATE, "ativo", 0.9, now, "pregame"))
        dao.deleteField(slot, K.SLOT_PENDING)
        dao.deletePlan(slot, "evidence")
        learn(slot, "temporada", "Competição nova: o meu time neste slot agora é $team.", now)
    }

    /** Campo vindo da central: vale o que está na tela agora (troca mesmo com a mesma confiança). */
    private suspend fun hubPut(slot: Int, key: String, value: String, now: Long): Int {
        val old = dao.fieldsOf(slot).firstOrNull { it.fkey == key }
        if (old != null && old.fvalue == value) return 0
        dao.putField(FieldEntity(slot, key, value, 0.8, now, "hub"))
        return 1
    }

    /**
     * Competição nova no slot (temporada seguinte ou batalha nova): o pré-jogo mostra rodada 1-3 enquanto o
     * histórico já tinha placar em rodadas bem maiores. Arquiva o calendário e os relatórios antigos (o aprendizado
     * das táticas fica) para a rodada não "pular" para a 35 e nada da temporada passada contar como jogado.
     */
    private suspend fun newSeasonIfNeeded(slot: Int, roundRead: Int?, now: Long): Boolean {
        val r = roundRead ?: return false
        val done = Fixtures.lastDone(dao.matchesOf(slot), scoredRounds(slot)) ?: return false
        if (r <= 3 && done >= r + 5) {
            archiveSeason(slot, now, false)
            learn(slot, "temporada", "Competição nova (rodada $r depois da $done): calendário e relatórios antigos arquivados; o aprendizado das táticas continua.", now)
            return true
        }
        // competição que entrou no meio (vídeo 07/11: nova liga já na rodada 5 e a anterior tinha 22 jogos): a rodada nunca volta
        // na mesma competição, então rodada lida MENOR que as já jogadas = outra competição (2 leituras iguais para confirmar)
        if (r < done) return confirmSwitch(slot, "R$r|$done", false, "rodada $r depois de $done jogadas", now, 2)
        return false
    }

    /**
     * Troca de competição/clube no slot, confirmada por leituras repetidas do mesmo sinal (o OCR pisca): arquiva a anterior
     * (calendário, relatórios, tática, tabela, fim de temporada) e, se o clube mudou, apaga também elenco e leituras do clube
     * e do rival antigos. O aprendizado das táticas (tlog) e as regras coladas do slot ficam.
     */
    private suspend fun confirmSwitch(slot: Int, sig: String, clearClub: Boolean, reason: String, now: Long, need: Int): Boolean {
        val p = planJson(slot, "compswitch")
        val n = if (p.optString("sig") == sig) p.optInt("n") + 1 else 1
        if (n < need) {
            dao.putPlan(PlanEntity(slot, "compswitch", org.json.JSONObject().put("sig", sig).put("n", n).toString(), now))
            return false
        }
        dao.deletePlan(slot, "compswitch")
        archiveSeason(slot, now, clearClub)
        dao.deleteRivalFields(slot)
        dao.deleteRivalPlayers(slot)
        if (clearClub) {
            for (k in listOf("injured", "suspended", "cards", "cm", "nations", "natreps", "leagueflag", "valuebase", "spec", "sponsor", "todolist", "formread",
                "rivalsnap", "autogen", "tacticdone", "tacmounted", "prep", "reds", "noappeal", "unavail", "squadseen", "squadreads", "squadblock", "watch", "lesson", "lessontry", "phist", "weekly", "ledger", "evoteam", "squadbase", "treating", "lineup", "natrich", "natiso2", "whyalt", "reanalysis")) dao.deletePlan(slot, k)
        }
        learn(slot, "temporada", "Competição nova detectada ($reason): a anterior foi arquivada" + (if (clearClub) " e o elenco/dados do clube antigo saíram" else "") + "; o aprendizado das táticas continua.", now)
        Diag.log("S$slot: competição nova detectada ($reason)")
        return true
    }

    /** A central mostra outro clube/competição/nº de rodadas neste slot: é uma competição nova (não só a rodada que avança). */
    private suspend fun detectSwitchFromHub(c: HubCard, type: String, now: Long): Int {
        val f = fieldMap(c.slot)
        val oldTitle = f[K.HUB_TITLE]?.value
        val oldComp = f[K.COMPETITION]?.value
        val oldTotal = f[K.ROUND_TOTAL]?.value?.toIntOrNull()
        if (oldTitle == null && oldComp == null) return 0
        val newTitle = Txt.titleCase(c.team)
        val titleChanged = oldTitle != null && newTitle.isNotBlank() && Txt.sim(Txt.key(oldTitle), Txt.key(newTitle)) < 0.7
        val compChanged = oldComp != null && c.subtitle.isNotBlank() && Txt.sim(Txt.key(oldComp), Txt.key(Txt.titleCase(c.subtitle))) < 0.6
        val totalChanged = oldTotal != null && c.roundTotal != null && oldTotal != c.roundTotal
        val strong = (titleChanged && (compChanged || totalChanged)) || (compChanged && totalChanged)
        if (!strong && !titleChanged) {
            dao.deletePlan(c.slot, "compswitch")
            return 0
        }
        val sig = "H|" + Txt.key(newTitle) + "|" + Txt.key(c.subtitle) + "|" + c.roundTotal
        // só apaga o clube quando o título E a competição/nº de rodadas mudaram; título sozinho (OCR repetido) só arquiva a competição
        return if (confirmSwitch(c.slot, sig, titleChanged && strong, "a central mostra $newTitle (${c.subtitle})", now, if (strong) 2 else 3)) 1 else 0
    }

    /**
     * Arquiva a competição do slot: calendário, relatórios, tática e temporada (os registros de tática com resultado
     * ficam, renomeados, para o aprendizado). Com clearClub (slot livre ou batalha nova com outro clube) apaga também
     * os campos e o elenco do clube antigo.
     */
    /** O que só vale DENTRO de uma competição (suspensões, tática montada, leituras do rival, forma, listas): apaga; o aprendizado (tlog arquivado, lições, histórico de treino) fica. */
    private suspend fun clearCompetitionState(slot: Int) {
        for (k in listOf("reds", "suspended", "noappeal", "tacticdone", "tacmounted", "autogen", "rivalsnap", "formread", "lineup", "todolist", "watch", "rivalchange", "prep", "whyalt", "reanalysis"))
            dao.deletePlan(slot, k)
        for (k in listOf(K.MY_FORM, K.RIVAL_FORM)) dao.deleteField(slot, k)
        dao.deleteRivalFields(slot)
        dao.deleteRivalPlayers(slot)
    }

    private suspend fun archiveSeason(slot: Int, now: Long, clearClub: Boolean) {
        val tag = "X" + (now / 1000)
        clearCompetitionState(slot)
        dao.deleteMatches(slot)
        // competição nova = linha de base nova: semanas (valor do elenco) e livro de caixa do que acabou não valem
        dao.deletePlan(slot, "weekly"); dao.deletePlan(slot, "ledger")
        for (p in dao.matchReports(slot)) {
            dao.putPlan(PlanEntity(slot, "old_" + tag + "_" + p.kind, p.json, p.at))
            dao.deletePlan(slot, p.kind)
        }
        for (p in dao.tacticLogs(slot)) {
            if (!p.kind.startsWith("tlog_R")) continue
            dao.putPlan(PlanEntity(slot, "tlog_" + tag + "_" + p.kind.removePrefix("tlog_"), p.json, p.at))
            dao.deletePlan(slot, p.kind)
        }
        // a tática de um jogo que ainda vai acontecer, das primeiras jornadas, já é da competição nova: fica
        val keep = dao.plan(slot, "tactic")?.let { p ->
            runCatching { org.json.JSONObject(p.json) }.getOrNull()?.let { j ->
                val at = if (j.isNull("matchAt")) 0L else j.optLong("matchAt")
                at > now && j.optInt("forRound", 99) in 1..3
            }
        } == true
        if (!keep) dao.deletePlan(slot, "tactic")
        // conferência entre telas e mensagens da direção são da competição que acabou
        dao.deletePlan(slot, "evidence")
        dao.plan(slot, "board")?.let {
            dao.putPlan(PlanEntity(slot, "old_" + tag + "_board", it.json, it.at))
            dao.deletePlan(slot, "board")
        }
        dao.plan(slot, "season")?.let {
            dao.putPlan(PlanEntity(slot, "old_" + tag + "_season", it.json, it.at))
            dao.deletePlan(slot, "season")
        }
        dao.plan(slot, "league")?.let {
            dao.putPlan(PlanEntity(slot, "old_" + tag + "_league", it.json, it.at))
            dao.deletePlan(slot, "league")
        }
        for (k in listOf(K.ROUND, K.ROUND_DONE, K.MATCH_AT, K.COMPETITION, K.COMP_TYPE, K.ROUND_TOTAL)) dao.deleteField(slot, k)
        // regras coladas, bandeira da liga e valor-base da meta eram DA liga que acabou: a nova tem as suas
        for (k in listOf("rules", "leagueflag", "valuebase")) dao.deletePlan(slot, k)
        if (clearClub) {
            dao.deleteFieldsOf(slot)
            dao.deletePlayersOf(slot)
            for (k in listOf("trainscreen", "trainov", "board", "market")) dao.deletePlan(slot, k)
        }
    }

    private suspend fun applyPregame(slot: Int, ex: Extraction, source: String, now: Long): Int {
        // competição nova só pela jornada lida com segurança (OCR); a leitura da IA (0,65) pode errar a jornada
        run {
            // o time lido no pré-jogo é outro: clube novo neste slot (a leitura é confiável: nome grande do meu time)
            val nt = ex.fields[K.TEAM]?.takeIf { it.conf >= 0.8 }?.value
            val ot = dao.fieldsOf(slot).firstOrNull { it.fkey == K.TEAM }?.fvalue
            if (nt != null && ot != null && Txt.sim(Txt.key(ot), Txt.key(nt)) < 0.6) {
                runCatching { confirmSwitch(slot, "P|" + Txt.key(nt), true, "meu time agora é $nt", now, 2) }
            }
        }
        newSeasonIfNeeded(slot, ex.roundRead ?: ex.fields[K.ROUND]?.takeIf { it.conf >= 0.85 }?.value?.toIntOrNull(), now)
        // Jogo novo (outra rodada): o cadeado do jogo anterior não vale mais.
        val oldRound = dao.fieldsOf(slot).firstOrNull { it.fkey == K.ROUND }?.fvalue?.toIntOrNull()
        val newRound = ex.roundRead ?: ex.fields[K.ROUND]?.value?.toIntOrNull()
        if (oldRound != null && newRound != null && oldRound != newRound && !ex.fields.containsKey(K.RIVAL_SECRET)) {
            dao.deleteField(slot, K.RIVAL_SECRET)
        }
        val newRival = ex.fields[K.RIVAL_TEAM]
        // 0,7 = mesma confiança com que o nome novo entra (leitura pela IA): rival trocado sempre limpa o anterior
        if (newRival != null && newRival.conf >= 0.7) {
            val old = dao.fieldsOf(slot).firstOrNull { it.fkey == K.RIVAL_TEAM }
            if (old != null && Txt.sim(Txt.key(old.fvalue), Txt.key(newRival.value)) < 0.8) {
                dao.deleteRivalFields(slot)
                dao.deleteRivalPlayers(slot)
                // jogo novo: o meu bônus também é outro (o do rival já saiu com os campos do rival)
                if (!ex.fields.containsKey(K.MY_BONUS)) dao.deleteField(slot, K.MY_BONUS)
                dao.deletePlan(slot, "evidence")
                learn(slot, "rival", "Rival mudou de ${old.fvalue} para ${newRival.value}: dados do rival anterior foram descartados.", now)
            }
        }
        // Bônus de login (+N%) no círculo do rival só aparece para humanos: nunca vira "CPU" por falta do apelido.
        // Humano sem bônus de login também deixa o círculo na força: a prova de CPU só vale se o nome de
        // usuário desse rival não apareceu em NENHUMA tela (pré-jogo, plantel, calendário, análise).
        val sameRival = run {
            val old = dao.fieldsOf(slot).firstOrNull { it.fkey == K.RIVAL_TEAM }?.fvalue
            val new = ex.fields[K.RIVAL_TEAM]?.value
            old == null || new == null || SlotMatcher.nameSim(Txt.key(old), Txt.key(new)) >= 0.8
        }
        // (o próprio pré-jogo agora sem nome não conta contra: vale nome visto em OUTRA tela ou digitado à mão)
        val nickSeen = sameRival && (
            Evidence.values(planJson(slot, "evidence"), K.RIVAL_NICK).any { b -> b.sources.any { it != "PREGAME" } } ||
                dao.fieldsOf(slot).any { it.fkey == K.RIVAL_NICK && it.source == "manual" && FieldMerge.known(it.fvalue) }
            )
        val cpuProof = !nickSeen && ex.fields[K.RIVAL_HUMAN]?.let { it.value == "Não" && it.conf >= 0.95 } == true
        if (cpuProof) {
            // prova de CPU (círculo do rival sem bônus enquanto o meu mostra): desfaz humano, apelido e bônus antigos
            val rows = dao.fieldsOf(slot).associateBy { it.fkey }
            if (rows[K.RIVAL_HUMAN]?.source != "manual" && rows[K.RIVAL_HUMAN]?.source != "hub") {
                dao.putField(FieldEntity(slot, K.RIVAL_HUMAN, "Não", 0.95, now, "cpu"))
                if (rows[K.RIVAL_NICK]?.source != "manual") dao.deleteField(slot, K.RIVAL_NICK)
                if (rows[K.RIVAL_LOGIN_BONUS]?.source != "manual") dao.deleteField(slot, K.RIVAL_LOGIN_BONUS)
                dao.deletePlan(slot, "evidence")
            }
        }
        val readings = if (cpuProof) ex.fields - K.RIVAL_HUMAN
        else if (ex.fields[K.RIVAL_HUMAN]?.value == "Não" && (nickSeen || ex.fields.containsKey(K.RIVAL_LOGIN_BONUS) || humanHints(slot))) {
            ex.fields - K.RIVAL_HUMAN
        } else ex.fields
        var n = putTracked(slot, readings - K.MY_FORM - K.RIVAL_FORM, source, "PREGAME", now)
        runCatching { keepForms(slot, ex, now) }
        // Meu apelido não foi lido: o apelido sob o time que é o rival guardado ainda conta.
        if (!ex.fields.containsKey(K.RIVAL_NICK)) {
            val rk = (ex.fields[K.RIVAL_TEAM]?.value ?: fieldMap(slot)[K.RIVAL_TEAM]?.value)?.let { Txt.key(it) }
            val nk = if (rk != null && rk.length >= 3) ex.humans.entries.firstOrNull { SlotMatcher.nameSim(it.key, rk) >= 0.85 }?.value else null
            if (nk != null && Evidence.plausibleNick(nk)) n += recordEvidence(slot, mapOf(K.RIVAL_NICK to nk.trim()), "PREGAME", now)
        }
        return n + confirmHuman(slot, planJson(slot, "evidence"), now) + estimateMid(slot, "rival.", now) + estimateMid(slot, "my.", now)
    }

    /**
     * Forma (bolinhas) do Pré-jogo: a leitura mais COMPLETA do mesmo jogo vence (janela do app por cima, transição ou quadro
     * parcial mostravam 1–2 bolinhas e apagavam as 5). Guarda a rodada da leitura: o jogo seguinte completa as bolinhas
     * com os resultados novos (`FormRead.mine`). Plano `formread` {tag, round, my, rival, at}.
     */
    private suspend fun keepForms(slot: Int, ex: Extraction, now: Long) {
        val my = ex.fields[K.MY_FORM]?.value?.takeIf { it.isNotEmpty() }
        val rv = ex.fields[K.RIVAL_FORM]?.value?.takeIf { it.isNotEmpty() }
        if (my == null && rv == null) return
        val f = fieldMap(slot)
        val round = f[K.ROUND]?.value?.toIntOrNull() ?: ex.roundRead ?: -1
        val tag = "$round|" + Txt.key(f[K.RIVAL_TEAM]?.value ?: "")
        val old = planJson(slot, "formread")
        val same = old.optString("tag") == tag
        fun pick(k: String, nw: String?): String {
            val o = if (same) old.optString(k) else ""
            return if (nw != null && nw.length >= o.length) nw else o
        }
        val m = pick("my", my)
        val r = pick("rival", rv)
        dao.putPlan(PlanEntity(slot, "formread", JSONObject().put("tag", tag).put("round", round).put("my", m).put("rival", r).put("at", now).toString(), now))
        if (m.isNotEmpty()) dao.putField(FieldEntity(slot, K.MY_FORM, m, 0.85, now, "ocr"))
        if (r.isNotEmpty()) dao.putField(FieldEntity(slot, K.RIVAL_FORM, r, 0.85, now, "ocr"))
    }

    private suspend fun applySquad(slot: Int, ex: Extraction, source: String, now: Long): Int {
        val f = fieldMap(slot)
        val myTeam = f[K.TEAM]?.value
        val rival = f[K.RIVAL_TEAM]?.value
        var guess: String? = null
        val ot = ex.ownerTeam
        if (ot != null) {
            val k = Txt.key(ot)
            if (myTeam != null && SlotMatcher.nameSim(k, Txt.key(myTeam)) >= 0.8) guess = "MY"
            else if (rival != null && SlotMatcher.nameSim(k, Txt.key(rival)) >= 0.8) guess = "RIVAL"
        }
        val nick = ex.ownerNick
        if (guess == null && nick != null && Txt.sim(Txt.key(nick), MY_NICK) >= 0.75) guess = "MY"
        val rivalNick = f[K.RIVAL_NICK]?.value?.takeIf { FieldMerge.known(it) }
        if (guess == null && nick != null && rivalNick != null && Txt.sim(Txt.key(nick), Txt.key(rivalNick)) >= 0.8) guess = "RIVAL"
        val owner: String = guess ?: run {
            learn(slot, "elenco", "Elenco de dono desconhecido ou de outro time (${ot ?: "sem cabeçalho"}) ignorado.", now)
            return 0
        }
        var changed = 0
        val prefix = if (owner == "MY") "my." else "rival."
        val mapped = LinkedHashMap<String, Reading>()
        for ((k, r) in ex.fields) {
            when (k) {
                "x.pos" -> if (owner == "MY") mapped[K.LEAGUE_POS] = r
                "x.objective" -> if (owner == "MY") mapped[K.MY_OBJECTIVE] = r
                else -> mapped[prefix + k.removePrefix("x.")] = r
            }
        }
        if (owner == "MY" && ot != null) mapped[K.TEAM] = Reading(ot, 0.8)
        run {
            // OCR perde a vírgula ("57,3M" vira "573M"): vale para o MEU elenco também (S2 mostrou 573M em vez de 57,3M)
            val vk = if (owner == "MY") K.MY_VALUE else K.RIVAL_VALUE
            val st = ex.fields["x.strength"]?.value?.toIntOrNull() ?: f[if (owner == "MY") K.MY_STRENGTH else K.RIVAL_STRENGTH]?.value?.toIntOrNull()
            val v = mapped[vk]
            if (v != null) {
                var fixed = if (st != null) Money.fixLostComma(v.value, st) else null
                if (fixed == null && owner == "MY") {
                    // sem força: se o valor guardado é ~10× menor e só a vírgula explica, corrige
                    val prev = Money.parse(f[vk]?.value); val now1 = Money.parse(v.value)
                    if (prev != null && now1 != null && prev > 0 && now1 / prev in 7.0..13.0) fixed = Regex("^(\\d+)(\\d)M$").find(v.value.trim())?.let { it.groupValues[1] + "," + it.groupValues[2] + "M" }
                }
                fixed?.let { mapped[vk] = Reading(it, 0.8) }
            }
        }
        changed += putTracked(slot, mapped, source, "SQUAD", now)
        changed += estimateMid(slot, prefix, now)
        if (owner == "RIVAL" && nick != null && Evidence.plausibleNick(nick)) {
            changed += recordEvidence(slot, mapOf(K.RIVAL_NICK to nick.trim()), "SQUAD", now)
        }

        // Elenco do rival não é guardado: só o cabeçalho (forças, valor, formação) interessa.
        if (owner != "MY") return changed

        val existing = dao.playersOf(slot).filter { it.owner == owner }
        val blocked = blockedPlayers(slot)
        val seen = ArrayList<String>()
        val arrived = ArrayList<String>()
        // contratação só vale se o elenco já era conhecido de antes (>6 h): na 1ª leitura de um clube (rolando a lista) todo jogador "novo" parecia comprado
        val squadBaseAt = planJson(slot, "squadbase").optLong("at", 0L)
        if (squadBaseAt == 0L) dao.putPlan(PlanEntity(slot, "squadbase", JSONObject().put("at", now).toString(), now))
        val squadBaseOld = squadBaseAt != 0L && now - squadBaseAt > 6 * 3600_000L
        val cm = LinkedHashMap<String, Pair<Int, Int>>()
        // nomes corrigidos à mão: a grafia errada que o OCR repete vira o nome certo (plano `namefix`: chave lida → nome certo)
        val nameFix = planJson(slot, "namefix")
        for (p0 in ex.players) {
            val p = nameFix.optString(Txt.key(p0.name)).takeIf { it.isNotBlank() }?.let { p0.copy(name = it) } ?: p0
            var key = Txt.key(p.name)
            if (existing.none { it.nameKey == key }) {
                val near = existing.firstOrNull { SquadDedupe.samePlayer(it.nameKey, it.age, it.strength, key, p.age, p.strength) }
                // mesmo jogador lido com nome ilegível: mesma idade, força e posição, e algum dos nomes é lixo de OCR
                val twin = existing.firstOrNull {
                    p.age != null && it.age == p.age && p.strength != null && it.strength == p.strength && it.cat == p.cat &&
                        (it.valueText == null || p.valueText == null || it.valueText == p.valueText) &&
                        (looksGarbled(it.name) || looksGarbled(p.name))
                }
                if (near != null) key = near.nameKey else if (twin != null) key = twin.nameKey
            }
            // Removido à mão pelo usuário (jogador que não existe): nunca volta.
            if (key in blocked) continue
            val old = dao.player(slot, owner, key)
            // A IA lendo imagem pode inventar nomes: ela só completa jogadores que o OCR já encontrou.
            if (old == null && source == "ai") continue
            seen.add(key)
            if (p.cond != null && p.morale != null) cm[key] = Pair(p.cond, p.morale)
            // a IA só completa: setor e força já lidos pelo OCR (com seção e números da linha) não são trocados por ela
            val aiOnly = source == "ai" && old != null
            val n = PlayerEntity(
                slotId = slot, owner = owner, nameKey = key,
                name = chooseName(old?.name, p.name),
                age = p.age ?: old?.age,
                posCode = if (aiOnly) old?.posCode ?: p.posCode else p.posCode ?: old?.posCode,
                cat = if (aiOnly) old?.cat ?: p.cat else p.cat ?: old?.cat,
                strength = if (aiOnly) old?.strength ?: p.strength else p.strength ?: old?.strength,
                valueText = sanePlayerValue(old?.valueText, p.valueText),
                training = p.training ?: old?.training,
                forSale = p.forSale ?: old?.forSale,
                updatedAt = now
            )
            if (old == null || n.copy(updatedAt = old.updatedAt) != old) {
                dao.putPlayer(n)
                changed++
            }
            // jogador novo num elenco que já estava lido = contratação (a 1ª leitura do elenco não conta)
            if (old == null && existing.size >= 14 && squadBaseOld && source != "ai" && n.strength != null && n.valueText != null && !looksGarbled(n.name)) arrived.add(key)
        }
        // bandeiras (coluna Nac.): agrupa assinaturas parecidas numa só (representante) e guarda por jogador no plano "nations"
        runCatching {
            val withNat = ex.players.filter { it.nat != null }
            if (withNat.size >= 3 && source != "ai") {
                val reps = planJson(slot, "natreps").optJSONArray("reps")?.let { a -> (0 until a.length()).map { a.optString(it) } }?.toMutableList() ?: mutableListOf()
                val nat = planJson(slot, "nations")
                for (p in withNat) {
                    val s = p.nat!!
                    val rep = reps.firstOrNull { SlotRules.sameFlag(it, s) } ?: s.also { reps.add(it) }
                    val k = Txt.key(p.name)
                    val key = seen.firstOrNull { it == k } ?: dao.playersOf(slot).firstOrNull { it.owner == "MY" && SquadDedupe.samePlayer(it.nameKey, it.age, it.strength, k, p.age, p.strength) }?.nameKey ?: k
                    nat.put(key, rep)
                }
                dao.putPlan(PlanEntity(slot, "natreps", JSONObject().put("reps", JSONArray(reps)).toString(), now))
                dao.putPlan(PlanEntity(slot, "nations", nat.toString(), now))
                // assinatura rica por jogador (o Atlas dá o nome do país)
                val rich = planJson(slot, "natrich")
                for (p in withNat) {
                    val rs = p.natRich ?: continue
                    val k = Txt.key(p.name)
                    val key = seen.firstOrNull { it == k } ?: dao.playersOf(slot).firstOrNull { it.owner == "MY" && SquadDedupe.samePlayer(it.nameKey, it.age, it.strength, k, p.age, p.strength) }?.nameKey ?: k
                    rich.put(key, rs)
                }
                dao.putPlan(PlanEntity(slot, "natrich", rich.toString(), now))
            }
        }
        if (ex.players.size >= 3) {
            runCatching { noteScreen(slot, ScreenType.SQUAD, now) }
            // cartão amarelo (coluna Estado): guarda por jogador; quem foi lido sem amarelo sai da lista
            val cards = planJson(slot, "cards")
            var cardsChanged = false
            for (p in ex.players) {
                val y = p.yellow ?: continue
                val k = Txt.key(p.name)
                val near = existing.firstOrNull { it.nameKey == k }?.nameKey ?: existing.firstOrNull { SquadDedupe.samePlayer(it.nameKey, it.age, it.strength, k, p.age, p.strength) }?.nameKey ?: k
                if (y && !cards.optBoolean(near)) { cards.put(near, true); cardsChanged = true }
                if (!y && cards.has(near)) { cards.remove(near); cardsChanged = true }
            }
            if (cardsChanged) dao.putPlan(PlanEntity(slot, "cards", cards.toString(), now))
            // cruz vermelha no Estado = lesionado; só sai da lista quando a cruz some (linha lida sem cruz)
            run {
                val inj = planJson(slot, "injured")
                val oldNames = inj.optJSONArray("names")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().toMutableList()
                var ch = false
                for (p in ex.players) {
                    val flag = p.injured ?: continue
                    val k = Txt.key(p.name)
                    val near = existing.firstOrNull { it.nameKey == k }?.nameKey ?: existing.firstOrNull { SquadDedupe.samePlayer(it.nameKey, it.age, it.strength, k, p.age, p.strength) }?.nameKey ?: k
                    if (flag && near !in oldNames) { oldNames.add(near); ch = true }
                    if (!flag && near in oldNames) { oldNames.remove(near); ch = true }
                }
                if (ch) dao.putPlan(PlanEntity(slot, "injured", JSONObject().put("names", JSONArray(oldNames)).put("at", now).toString(), now))
            }
            // cartão vermelho = suspenso (vale mesmo sem passar pelo Advogado); quem foi lido sem vermelho sai
            val reds = planJson(slot, "reds")
            var redsChanged = false
            for (p in ex.players) {
                val r = p.red ?: continue
                val k = Txt.key(p.name)
                val near = existing.firstOrNull { it.nameKey == k }?.nameKey ?: existing.firstOrNull { SquadDedupe.samePlayer(it.nameKey, it.age, it.strength, k, p.age, p.strength) }?.nameKey ?: k
                if (r && !reds.optBoolean(near)) { reds.put(near, true); redsChanged = true }
                if (!r && reds.has(near)) { reds.remove(near); redsChanged = true }
            }
            if (redsChanged) dao.putPlan(PlanEntity(slot, "reds", reds.toString(), now))
        }
        if (seen.isNotEmpty()) markSeen(slot, seen, now)
        if (seen.isNotEmpty()) trackProgress(slot, seen, arrived, now)
        if (seen.isNotEmpty()) dedupeSlot(slot, now)
        if (cm.isNotEmpty()) {
            val cur = dao.plan(slot, "cm")?.json?.let { try { JSONObject(it) } catch (e: Exception) { null } } ?: JSONObject()
            var cmChanged = false
            for ((k, v) in cm) {
                val a = JSONArray().put(v.first).put(v.second)
                if (cur.optJSONArray(k)?.toString() != a.toString()) {
                    cur.put(k, a)
                    cmChanged = true
                }
            }
            if (cmChanged) dao.putPlan(PlanEntity(slot, "cm", cur.toString(), now))
        }
        if (ex.players.isNotEmpty()) {
            val all = dao.playersOf(slot).filter { it.owner == owner }
            val counts = JSONObject()
            for (c in listOf("ATA", "MEI", "DEF", "GOL")) counts.put(c, all.count { it.cat == c })
            counts.put("total", all.size)
            dao.deleteSnapshots("SQUAD", slot, owner)
            dao.insertSnapshot(SnapshotEntity(kind = "SQUAD", slotId = slot, owner = owner, json = counts.toString(), at = now))
        }
        return changed
    }

    private suspend fun applyCalendar(slot: Int, ex: Extraction, source: String, now: Long): Int {
        val f = fieldMap(slot)
        val myTeam = f[K.TEAM]?.value
        var changed = 0

        // Humanos: apelido embaixo do time na linha superior.
        val rival = f[K.RIVAL_TEAM]?.value
        if (rival != null && ex.humans.isNotEmpty()) {
            val rk = Txt.key(rival)
            for ((k, nick) in ex.humans) {
                if (Txt.sim(k, rk) >= 0.85) {
                    if (nick != null && Evidence.plausibleNick(nick)) {
                        // apelido sob o time: é uma evidência; vira "humano" só se outra tela confirmar
                        changed += recordEvidence(slot, mapOf(K.RIVAL_NICK to nick.trim()), "CALENDAR", now)
                    } else {
                        if (!humanHints(slot)) changed += putFields(slot, mapOf(K.RIVAL_HUMAN to Reading("Não", 0.7)), source, now)
                    }
                }
            }
        }

        val ot = ex.ownerTeam
        var accept = false
        if (myTeam != null) {
            if (ot != null) {
                accept = Txt.sim(Txt.key(ot), Txt.key(myTeam)) >= 0.8
            } else {
                // Sem o título visível (lista rolada): é o MEU calendário se nenhum card mostra o meu time como adversário.
                val mk = Txt.key(myTeam)
                val seesMe = ex.matches.any { it.opponent != null && Txt.sim(Txt.key(it.opponent), mk) >= 0.85 }
                accept = ex.matches.isNotEmpty() && !seesMe
            }
        }
        if (!accept) {
            if (ex.matches.isNotEmpty()) learn(slot, "calendário", "Calendário de outro time ignorado (${ot ?: "sem título"}).", now)
            return changed
        }
        for (m in ex.matches) {
            val old = dao.match(slot, m.key)
            val n = MatchEntity(
                slotId = slot, mkey = m.key, label = m.label,
                round = m.round ?: old?.round,
                date = m.date ?: old?.date,
                time = m.time ?: old?.time,
                home = m.home ?: old?.home,
                scoreMine = m.scoreMine ?: old?.scoreMine,
                scoreOpp = m.scoreOpp ?: old?.scoreOpp,
                result = m.result ?: old?.result,
                opponent = m.opponent ?: old?.opponent,
                opponentNick = m.opponentNick ?: old?.opponentNick,
                updatedAt = now
            )
            if (old == null || n.copy(updatedAt = old.updatedAt) != old) {
                dao.putMatch(n)
                changed++
            }
        }
        // Horário real do próximo jogo (o card mostra "HH:mm"): ignora copa da qual já fui eliminado e jogos
        // cujo horário já passou. O mesmo card confirma rival e mando de campo (evidência cruzada).
        val all = dao.matchesOf(slot)
        // Apelido sob o nome do rival nos cards do calendário (ida ou volta): mais uma fonte para confirmar humano.
        val rivalNow = fieldMap(slot)[K.RIVAL_TEAM]?.value?.let { Txt.key(it) }
        if (rivalNow != null && rivalNow.length >= 3) {
            val nk = ex.matches.firstOrNull { m ->
                m.opponentNick != null && Evidence.plausibleNick(m.opponentNick) &&
                    m.opponent != null && Txt.sim(Txt.key(m.opponent), rivalNow) >= 0.85
            }?.opponentNick
            if (nk != null) changed += recordEvidence(slot, mapOf(K.RIVAL_NICK to nk.trim()), "CALENDAR", now)
        }
        val clk = syncRound(slot, now)
        val curRound = clk.round
        val curAt = clk.at
        val next = Fixtures.next(all, now, curRound, curAt)
        // A rodada guardada fica para trás quando o pré-jogo não é relido depois do jogo: o calendário avança.
        val nr = next?.round
        if (nr != null && (curRound == null || nr > curRound)) {
            changed += putFields(slot, mapOf(K.ROUND to Reading(nr.toString(), 0.85)), source, now)
        }
        if (next != null) {
            val t = next.time
            if (t != null) {
                // Card só com a hora não diz o dia: se o horário guardado (contagem do pré-jogo) já bate com essa hora
                // e está no futuro, ele é o certo; senão vale a próxima ocorrência dessa hora a partir de agora.
                val hm = Regex("^(\\d{1,2}):(\\d{2})$").find(t.trim())
                val keep = next.date == null && curAt != null && curAt > now && hm != null && run {
                    val c = java.util.Calendar.getInstance()
                    c.timeInMillis = curAt
                    val a = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
                    val b = hm.groupValues[1].toInt() * 60 + hm.groupValues[2].toInt()
                    kotlin.math.abs(a - b) <= 2
                }
                val at = if (next.date != null) Fixtures.kickoff(next) else MatchClock.toMillis(null, t, now)
                if (!keep && at != null) changed += putFields(slot, mapOf(K.MATCH_AT to Reading(at.toString(), 0.95)), source, now)
            }
            val ev = LinkedHashMap<String, String>()
            Fixtures.opponent(next)?.let { ev[K.RIVAL_TEAM] = it }
            next.home?.let { ev[K.HOME] = if (it) "Casa" else "Fora" }
            if (ev.isNotEmpty()) changed += recordEvidence(slot, ev, "CALENDAR", now)
            // Card do próximo rival lido com o nome e SEM apelido embaixo: rival é CPU (se nada confirmou humano).
            val opp = Fixtures.opponent(next)
            val rk = fieldMap(slot)[K.RIVAL_TEAM]?.value?.let { Txt.key(it) }
            if (opp != null && next.opponentNick == null && rk != null && Txt.sim(Txt.key(opp), rk) >= 0.85) {
                if (!humanHints(slot)) changed += putFields(slot, mapOf(K.RIVAL_HUMAN to Reading("Não", 0.7)), source, now)
            }
        }
        return changed
    }

    private suspend fun applyMarket(slot: Int, ex: Extraction, source: String, now: Long): Int {
        var changed = putFields(slot, ex.fields, source, now)
        for (l in ex.listings) {
            val key = Txt.key(l.name)
            val old = dao.listing(slot, key)
            val n = ListingEntity(
                slotId = slot, nameKey = key, name = l.name,
                age = l.age ?: old?.age, posCode = l.posCode ?: old?.posCode, cat = l.cat ?: old?.cat,
                strength = l.strength ?: old?.strength, priceText = l.priceText ?: old?.priceText,
                club = l.club ?: old?.club, sellerNick = l.sellerNick ?: old?.sellerNick, seenAt = now
            )
            if (old == null || n.copy(seenAt = old.seenAt) != old) changed++
            dao.putListing(n)
        }
        dao.deleteOldListings(slot, now - 3L * 24L * 3600L * 1000L)
        if (ex.listings.isNotEmpty() || ex.fields.containsKey(K.SELLING)) {
            val j = JSONObject()
            j.put("selling", ex.fields[K.SELLING]?.value ?: NI)
            j.put("rows", ex.listings.size)
            dao.deleteSnapshots("TRANSFER", slot, null)
            dao.insertSnapshot(SnapshotEntity(kind = "TRANSFER", slotId = slot, owner = null, json = j.toString(), at = now))
        }
        return changed
    }

    // ------------------------------------------------------------------ telas novas (Preparação, Médico, Advogado…)

    /** Quando cada tela foi lida pela última vez (por slot; 0 = global): base do "telas para atualizar". */
    suspend fun noteScreen(slot: Int, type: ScreenType, now: Long) {
        val j = planJson(slot, "seen")
        j.put(type.name, now)
        dao.putPlan(PlanEntity(slot, "seen", j.toString(), now))
    }

    suspend fun seenMap(slot: Int): Map<String, Long> {
        val j = planJson(slot, "seen")
        return j.keys().asSequence().associateWith { j.optLong(it) }
    }

    private fun nameMatches(line: String, p: PlayerEntity): Boolean {
        val k = Txt.key(line)
        if (k.length < 3) return false
        if (Txt.sim(k, p.nameKey) >= 0.85) return true
        // "Rúben Dias" na tela × "r dias" no elenco: o sobrenome (4+ letras) contido basta se for único
        val last = Txt.key(p.name.trim().split(" ").last())
        // palavra inteira igual ao sobrenome (não basta o sobrenome "estar contido" em outro texto)
        return last.length >= 4 && line.split(" ", ",", ".").map { Txt.key(it) }.any { it == last }
    }

    private suspend fun applyExtra(slot: Int, ex: Extraction, now: Long): Int {
        // quem abriu Médico/Advogado/Especialistas já "passou" pela tela, mesmo que a leitura não tenha achado nada
        if (ex.type == ScreenType.DOCTOR || ex.type == ScreenType.LAWYER || ex.type == ScreenType.SPECIALISTS) noteScreen(slot, ex.type, now)
        val x = ex.extra ?: return 0
        when (ex.type) {
            ScreenType.PREP -> {
                // aba "Lista de Afazeres": o que o jogo pede agora (guardado inteiro; item que sumiu da lista foi feito)
                if (x.optBoolean("todoSeen")) {
                    dao.putPlan(PlanEntity(slot, "todolist", JSONObject().put("keys", x.optJSONArray("todo") ?: JSONArray()).put("at", now).toString(), now))
                    noteScreen(slot, ScreenType.PREP, now)
                }
                val items = x.optJSONArray("items") ?: return if (x.optBoolean("todoSeen")) 1 else 0
                if (items.length() == 0) return if (x.optBoolean("todoSeen")) 1 else 0
                val cur = planJson(slot, "prep")
                for (i in 0 until items.length()) {
                    val o = items.optJSONObject(i) ?: continue
                    cur.put(o.optString("k"), JSONObject().put("done", o.optBoolean("done")).put("at", now))
                }
                dao.putPlan(PlanEntity(slot, "prep", cur.toString(), now))
                noteScreen(slot, ScreenType.PREP, now)
                return 1
            }
            ScreenType.DOCTOR, ScreenType.LAWYER -> {
                run {
                    val tk = if (ex.type == ScreenType.LAWYER) "lawyer" else "doctor"
                    val secs = if (x.isNull("endsIn")) null else x.optLong("endsIn")
                    if (secs != null) updateTimers(slot, mapOf(tk to Pair(now + secs * 1000L, if (tk == "lawyer") "advogado: recurso" else "médico: tratamento")), now)
                    else if (!x.optBoolean("busy")) updateTimers(slot, mapOf(tk to null), now)
                }
                if (ex.type == ScreenType.LAWYER) {
                    // sem recurso possível: vale até passar a rodada (a suspensão cumpre sozinha)
                    val round = fieldMap(slot)[K.ROUND]?.value?.toIntOrNull() ?: -1
                    if (x.optBoolean("noAppeal")) dao.putPlan(PlanEntity(slot, "noappeal", JSONObject().put("round", round).put("at", now).toString(), now))
                    else if (!x.optBoolean("empty") && planJson(slot, "noappeal").length() > 0) dao.putPlan(PlanEntity(slot, "noappeal", "{}", now))
                }
                val kind = if (ex.type == ScreenType.DOCTOR) "injured" else "suspended"
                // Advogado diz que não há ninguém: vermelhos antigos (nome que sumiu/OCR) não valem mais
                if (ex.type == ScreenType.LAWYER && x.optBoolean("empty") && planJson(slot, "reds").length() > 0) dao.putPlan(PlanEntity(slot, "reds", "{}", now))
                val mine = dao.playersOf(slot).filter { it.owner == "MY" }
                val lines = x.optJSONArray("lines")
                val names = ArrayList<String>()
                if (!x.optBoolean("empty") && lines != null) {
                    for (i in 0 until lines.length()) {
                        val l = lines.optString(i)
                        mine.firstOrNull { nameMatches(l, it) }?.let { if (it.nameKey !in names) names.add(it.nameKey) }
                    }
                }
                // tela vazia só vale com o aviso do próprio jogo; lista cheia sem nenhum nome reconhecido não apaga nada
                if (names.isEmpty() && !x.optBoolean("empty")) return 0
                val old = planJson(slot, kind)
                val oldNames = old.optJSONArray("names")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                // cartão de um jogador em tratamento: só soma (continua lesionado até acabar), sem apagar os outros da lista
                if (x.optBoolean("card")) for (n in oldNames) if (n !in names) names.add(n)
                // quem está em tratamento sai da lista de lesionados quando o cronômetro acaba (curado)
                if (ex.type == ScreenType.DOCTOR && x.optBoolean("card") && !x.isNull("endsIn")) {
                    val cardNames = lines?.let { l -> (0 until l.length()).mapNotNull { i -> mine.firstOrNull { nameMatches(l.optString(i), it) }?.nameKey } }.orEmpty().distinct()
                    if (cardNames.isNotEmpty()) {
                        val left = if (x.isNull("cross")) null else x.optInt("cross")
                        dao.putPlan(PlanEntity(slot, "treating", JSONObject().put("names", JSONArray(cardNames)).put("until", now + x.optLong("endsIn") * 1000L).apply { left?.let { put("left", it) } }.toString(), now))
                        if (left != null) learn(slot, "elenco", "Tratamentos que faltam (cruz): ${cardNames.joinToString { n -> mine.firstOrNull { it.nameKey == n }?.name ?: n }} = $left.", now)
                    }
                }
                dao.putPlan(PlanEntity(slot, kind, JSONObject().put("names", JSONArray(names)).put("at", now).toString(), now))
                noteScreen(slot, ex.type, now)
                if (names.sorted() != oldNames.sorted()) {
                    val txt = if (names.isEmpty()) "nenhum" else names.joinToString { n -> mine.firstOrNull { it.nameKey == n }?.name ?: n }
                    learn(slot, "elenco", (if (kind == "injured") "Lesionados (Médico): " else "Suspensos (Advogado): ") + txt + ".", now)
                }
                return 1
            }
            ScreenType.SPECIALISTS -> {
                if (x.length() < 4) return 0
                dao.putPlan(PlanEntity(slot, "spec", x.put("at", now).toString(), now))
                noteScreen(slot, ScreenType.SPECIALISTS, now)
                return 1
            }
            ScreenType.SCOUT -> {
                noteScreen(slot, ScreenType.SCOUT, now)
                val secs = if (x.isNull("endsIn")) null else x.optLong("endsIn")
                if (secs != null) updateTimers(slot, mapOf("scout" to Pair(now + secs * 1000L, "olheiro: relatório")), now)
                else updateTimers(slot, mapOf("scout" to null), now)
                return 1
            }
            ScreenType.DEALS -> {
                val rows = x.optJSONArray("rows") ?: return 0
                val team = fieldMap(slot)[K.TEAM]?.value ?: return 0
                val tk = Txt.key(team)
                val ledger = Ledger.parse(dao.plan(slot, "ledger")?.json)
                var added = 0
                val cal = java.util.Calendar.getInstance()
                for (i in 0 until rows.length()) {
                    val r = rows.optJSONObject(i) ?: continue
                    val price = r.optDouble("price", 0.0).takeIf { it > 0 } ?: continue
                    val from = Txt.key(r.optString("from"))
                    val to = Txt.key(r.optString("to"))
                    val sold = from.length >= 3 && Txt.sim(from, tk) >= 0.85
                    val bought = to.length >= 3 && Txt.sim(to, tk) >= 0.85
                    if (!sold && !bought) continue
                    val h = r.optInt("h", -1)
                    val m = r.optInt("m", -1)
                    val at = if (h in 0..23 && m in 0..59) cal.apply { timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, m); set(java.util.Calendar.SECOND, 0) }.timeInMillis else now
                    val name = r.optString("name")
                    if (Ledger.add(ledger, Ledger.Entry(if (sold) "sell" else "buy", Txt.key(name), name, minOf(at, now), price, false, null, null))) added++
                }
                noteScreen(slot, ScreenType.DEALS, now)
                if (added > 0) dao.putPlan(PlanEntity(slot, "ledger", Ledger.toJson(ledger), now))
                return added
            }
            ScreenType.STATS -> {
                if (x.length() < 2) return 0
                dao.putPlan(PlanEntity(slot, "lstats", x.put("at", now).toString(), now))
                noteScreen(slot, ScreenType.STATS, now)
                return 1
            }
            ScreenType.MISSIONS -> {
                val up = LinkedHashMap<String, Pair<Long, String>?>()
                if (x.has("newIn")) up["mission_new"] = Pair(now + x.optLong("newIn") * 1000L, "missões: novas")
                if (x.has("weekIn")) up["mission_week"] = Pair(now + x.optLong("weekIn") * 1000L, "missões: semana")
                if (up.isEmpty()) return 0
                updateTimers(0, up, now)
                noteScreen(0, ScreenType.MISSIONS, now)
                return 1
            }
            else -> return 0
        }
    }

    /** Jogadores com cartão vermelho no plantel (nameKeys). */
    suspend fun redsOf(slot: Int): Set<String> = planJson(slot, "reds").let { o -> o.keys().asSequence().filter { o.optBoolean(it) }.toSet() }

    /** Lesionados e suspensos lidos do Médico/Advogado (nameKeys). */
    suspend fun outByDoctorOrLawyer(slot: Int): Pair<Set<String>, Set<String>> {
        fun names(j: JSONObject): Set<String> {
            val a = j.optJSONArray("names") ?: return emptySet()
            return (0 until a.length()).map { a.optString(it) }.toSet()
        }
        val reds = planJson(slot, "reds").let { o -> o.keys().asSequence().filter { o.optBoolean(it) }.toSet() }
        // lesionado só sai da lista quando a cruz some (Médico sem ele): o fim de UM cronômetro não cura, a cruz pode pedir vários tratamentos
        return Pair(names(planJson(slot, "injured")), names(planJson(slot, "suspended")) + reds)
    }

    // ------------------------------------------------------------------ leituras extras (eventos, teto de venda, patrocínio)

    /** Coisas que valem em qualquer tela: teto de venda da liga (tela de preço) e patrocínio (tela de patrocinadores). */
    suspend fun smartReads(slot: Int?, type: ScreenType, ocr: OcrResult, now: Long) {
        if (slot == null) return
        SellPrices.fromScreen(ocr)?.let { r ->
            // vale o maior teto já visto (o controle de preço começa no máximo)
            val old = dao.plan(slot, "ceiling")?.json?.let { runCatching { org.json.JSONObject(it).optDouble("r", 0.0) }.getOrNull() } ?: 0.0
            if (r > old) dao.putPlan(PlanEntity(slot, "ceiling", org.json.JSONObject().put("r", r).put("at", now).toString(), now))
        }
        // tabelas da liga inteira (Classificação / Valor do plantel): guarda para comparar o elenco e calibrar a tática
        League.read(ocr)?.let { (kind, rows) ->
            val old = dao.plan(slot, "league")?.json?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            dao.putPlan(PlanEntity(slot, "league", League.merge(old, kind, rows, now).toString(), now))
        }
        val deals = Sponsors.parse(ocr)
        if (deals.size >= 2) {
            dao.putPlan(PlanEntity(slot, "sponsor", Sponsors.toJson(deals, now), now))
            noteScreen(slot, ScreenType.SPONSORS, now)
        }
    }

    // ------------------------------------------------------------------ cronômetros (treino e estádio)

    /** Atualiza os cronômetros do slot (null = acabou/sem obra) e reagenda os avisos. */
    suspend fun updateTimers(slot: Int, up: Map<String, Pair<Long, String>?>, now: Long) {
        val j = planJson(slot, "timers")
        var changed = false
        for ((k, v) in up) {
            if (v == null) {
                if (j.has(k)) { j.remove(k); changed = true }
            } else {
                val old = j.optJSONObject(k)
                // a mesma obra/treino relida: só muda se o fim andou mais de 1 minuto (acelerou, trocou de jogador)
                if (old == null || kotlin.math.abs(old.optLong("at") - v.first) > 60000L || old.optString("label") != v.second) {
                    j.put(k, JSONObject().put("at", v.first).put("label", v.second))
                    changed = true
                }
            }
        }
        if (!changed) return
        dao.putPlan(PlanEntity(slot, "timers", j.toString(), now))
        Notifier.scheduleTimers(ctx)
    }

    suspend fun timers(slot: Int): List<Timers.Entry> = Timers.parse(slot, dao.plan(slot, "timers")?.json)

    suspend fun removeTimer(slot: Int, key: String) {
        val j = planJson(slot, "timers")
        if (!j.has(key)) return
        j.remove(key)
        dao.putPlan(PlanEntity(slot, "timers", j.toString(), System.currentTimeMillis()))
    }

    // ------------------------------------------------------------------ treinos (tela de Treinamento)

    /**
     * Treinos lidos da tela de Treinamento (a última leitura de cada treinador vale). Com os 4 treinadores de posição
     * lidos, quem não está em nenhum deixa de "treinar" no app; a camisa do plantel não conta mais.
     */
    suspend fun applyTraining(slot: Int, list: List<TraineeRead>, now: Long): Int {
        if (list.isEmpty()) return 0
        val j = planJson(slot, "trainscreen")
        for (t in list) {
            j.put(t.role, JSONObject().put("name", t.name ?: JSONObject.NULL).put("state", t.state).put("at", now))
        }
        dao.putPlan(PlanEntity(slot, "trainscreen", j.toString(), now))
        runCatching {
            val up = LinkedHashMap<String, Pair<Long, String>?>()
            for (t in list) {
                when {
                    t.state == "active" && t.endAt != null -> up["train_" + t.role] = Pair(t.endAt, "treino: " + (t.name?.let { TrainBoard.pretty(it) } ?: "jogador") + " (" + Timers.roleLabel(t.role) + ")")
                    t.state == "done" || t.state == "empty" -> up["train_" + t.role] = null
                }
            }
            if (up.isNotEmpty()) updateTimers(slot, up, now)
        }
        // o que o usuário marcou à mão antes desta leitura deixa de valer (a tela do jogo é a fonte)
        dao.plan(slot, "trainov")?.let { if (it.at < now) dao.deletePlan(slot, "trainov") }
        return syncTraining(slot, now, log = true)
    }

    /**
     * Marca no elenco quem está treinando segundo a última leitura de cada treinador (mesma regra do quadro da tela):
     * nome que não é da tela de Treinamento não vale e o mesmo jogador em dois treinadores fica só na leitura mais nova.
     */
    suspend fun syncTraining(slot: Int, now: Long = System.currentTimeMillis(), log: Boolean = false): Int {
        val j = dao.plan(slot, "trainscreen")?.json?.let { try { JSONObject(it) } catch (e: Exception) { null } } ?: return 0
        val mine = dao.playersOf(slot).filter { it.owner == "MY" }
        if (mine.isEmpty()) return 0
        val seats = TrainBoard.seats(j, mine, now)
        val active = TrainBoard.activeKeys(seats)
        val full = seats.count { it.role != "UNI" && it.state != "unknown" && it.fresh } >= 4
        var changed = 0
        for (p in mine) {
            val want = when {
                p.nameKey in active -> true
                full -> false
                // treinando pela leitura antiga e já fora de qualquer treinador lido
                p.training == true && seats.none { it.key == p.nameKey } && seats.any { it.role == p.cat && it.fresh && it.state != "unknown" } -> false
                else -> continue
            }
            if (p.training != want) {
                dao.putPlayer(p.copy(training = want))
                changed++
            }
        }
        if (log && changed > 0) {
            val labels = seats.filter { it.state == "active" || it.state == "done" }
                .map { it.role + " " + (it.name ?: "?") + (if (it.state == "done") " (concluído, falta reclamar)" else "") }
            learn(slot, "treino", "Treinos (tela de Treinamento): " + labels.joinToString(", ").ifBlank { "nenhum treinando" } + ".", now)
        }
        return changed
    }

    // ------------------------------------------------------------------ fim de temporada e diretoria

    /**
     * Telas do fim da competição (classificação final, "a época acabou", mensagens da diretoria, objetivos, prêmios,
     * demissão): não são de adversário. O texto vira aprendizado do slot (vai para o prompt da IA) e "a época acabou"
     * encerra a competição do slot (nada de tática para um jogo que não existe).
     */
    suspend fun recordSeasonScreen(slot: Int, r: Parsers.SeasonRead, now: Long): Int {
        val f = fieldMap(slot)
        val team = f[K.TEAM]?.value ?: f[K.HUB_TITLE]?.value ?: "Meu time"
        val items = ArrayList<String>()
        if (r.rank != null) {
            // classificação vista no meio da competição não é "fim": só com a tela de fim de época ou todas as rodadas jogadas
            val ended = r.over || Fixtures.seasonEnded(f, dao.matchesOf(slot), now)
            items.add(
                (if (ended) "Fim da competição: $team terminou em ${r.rank}º" else "Classificação: $team estava em ${r.rank}º") +
                    (if (r.points != null) " com ${r.points} pts" else "") + (if (r.goals != null) " (gols ${r.goals})" else "") + "."
            )
        }
        if (r.champion != null) items.add("Campeão da liga: ${r.champion}.")
        if (r.squadValue != null) items.add("Valor do plantel no fim: ${r.squadValue}" + (if (r.valueRank != null) " (${r.valueRank}º da liga)" else "") + ".")
        if (r.board != null) items.add("Direção: \"${r.board.take(220)}\"")
        var n = 0
        if (items.isNotEmpty()) {
            val j = planJson(slot, "board")
            val arr = j.optJSONArray("items") ?: JSONArray()
            val list = ArrayList<JSONObject>()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
            for (t in items) {
                val k = Txt.key(t)
                // mesma frase lida de novo (OCR com uma letra diferente, ou cortada no fim): fica a versão mais completa
                val same = list.indexOfFirst { o ->
                    val ok = Txt.key(o.optString("text"))
                    ok == k || ok.startsWith(k) || k.startsWith(ok) || (k.length <= 60 && Txt.sim(ok, k) >= 0.85)
                }
                if (same >= 0) {
                    val ok = Txt.key(list[same].optString("text"))
                    // só troca por uma versão bem mais longa (frase que veio cortada); 1-2 letras a mais é erro de OCR
                    if (k.length > ok.length + 4) {
                        list[same] = JSONObject().put("at", now).put("text", t)
                        n++
                    }
                    continue
                }
                list.add(JSONObject().put("at", now).put("text", t))
                learn(slot, "diretoria", t, now)
                n++
            }
            if (n > 0) {
                val keep = JSONArray()
                for (o in list.takeLast(20)) keep.put(o)
                dao.putPlan(PlanEntity(slot, "board", j.put("items", keep).toString(), now))
            }
        }
        // resumo estruturado (para o cartão de fim de competição): cada leitura completa o que faltava
        if (r.rank != null || r.points != null || r.champion != null || r.squadValue != null) {
            val s = planJson(slot, "season")
            r.rank?.let { s.put("rank", it) }
            r.points?.let { s.put("points", it) }
            r.goals?.let { s.put("goals", it) }
            r.champion?.let { s.put("champion", it) }
            r.squadValue?.let { s.put("value", it) }
            r.valueRank?.let { s.put("valueRank", it) }
            if (r.over) s.put("over", true)
            dao.putPlan(PlanEntity(slot, "season", s.put("at", now).toString(), now))
        }
        val pos = LinkedHashMap<String, Reading>()
        r.rank?.let { pos[K.LEAGUE_POS] = Reading(it.toString(), 0.9) }
        r.points?.let { pos[K.POINTS] = Reading(it.toString(), 0.9) }
        n += putFields(slot, pos, "fim", now)
        if (r.over) {
            val total = f[K.ROUND_TOTAL]?.value?.toIntOrNull()
            if (total != null && (f[K.ROUND_DONE]?.value?.toIntOrNull() ?: 0) < total) {
                dao.putField(FieldEntity(slot, K.ROUND_DONE, total.toString(), 0.9, now, "fim"))
                n++
            }
            if (f[K.MATCH_AT] != null) dao.deleteField(slot, K.MATCH_AT)
            // competição acabou: some o que era só dela (rival, tática, suspensões, forma); resultados e aprendizado ficam para a revisão e o aprendizado
            clearCompetitionState(slot)
            dao.deletePlan(slot, "tactic")
        }
        return n
    }

    /** Mensagens da diretoria / fim de temporada guardadas (mais nova primeiro). */
    suspend fun boardMessages(slot: Int): List<String> {
        val arr = planJson(slot, "board").optJSONArray("items") ?: return emptyList()
        // "Fim da competição" gravado de uma classificação vista no meio da liga: mostra como classificação
        val ended = Fixtures.seasonEnded(fieldMap(slot), dao.matchesOf(slot), System.currentTimeMillis())
        val all = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("text") }
            .filter { !Txt.norm(it).contains("atribuid") }
            .map { it.replace(Regex("(?<=[A-Za-z])0|0(?=[A-Za-z])"), "O") }
            .map { t ->
                if (!ended && t.startsWith("Fim da competição:")) t.replace("Fim da competição:", "Classificação:").replace(" terminou em ", " estava em ")
                else t
            }
        // leituras repetidas da mesma frase: fica a mais completa
        val out = ArrayList<String>()
        for (t in all.reversed()) {
            val k = Txt.key(t)
            val i = out.indexOfFirst { o -> val ok = Txt.key(o); ok == k || ok.startsWith(k) || k.startsWith(ok) || (k.length <= 60 && Txt.sim(ok, k) >= 0.85) }
            if (i < 0) out.add(t) else if (k.length > Txt.key(out[i]).length + 4) out[i] = t
        }
        return out
    }

    /** Valor digitado pelo usuário: vale até a próxima leitura da tela, que sempre atualiza (o jogo pode ter mudado). */
    suspend fun setManual(slot: Int, key: String, value: String) {
        dao.putField(FieldEntity(slot, key, value.trim(), 2.0, System.currentTimeMillis(), "manual"))
    }

    suspend fun clearField(slot: Int, key: String) {
        dao.deleteField(slot, key)
    }

    suspend fun setManualMatch(slot: Int, round: Int, opponent: String?, mine: Int?, opp: Int?, home: Boolean?) {
        val key = "L$round"
        val old = dao.match(slot, key)
        val result = if (mine != null && opp != null) (if (mine > opp) "V" else if (mine == opp) "E" else "D") else null
        val name = opponent?.trim()?.ifBlank { null } ?: old?.opponent
        dao.putMatch(
            MatchEntity(
                slot, key, old?.label ?: "Jornada $round", round, old?.date, old?.time, home ?: old?.home,
                mine, opp, result, name, old?.opponentNick, System.currentTimeMillis()
            )
        )
    }

    /** Condição e moral (0 a 100) de cada jogador, lidas das barras da tela do Plantel. */
    suspend fun fitness(slot: Int): Map<String, Pair<Int, Int>> {
        val p = dao.plan(slot, "cm") ?: return emptyMap()
        val out = HashMap<String, Pair<Int, Int>>()
        try {
            val j = org.json.JSONObject(p.json)
            for (k in j.keys()) {
                val a = j.optJSONArray(k) ?: continue
                out[k] = Pair(a.optInt(0), a.optInt(1))
            }
        } catch (e: Exception) {
            return emptyMap()
        }
        return out
    }

    // ------------------------------------------------------------------ perfil do rival humano

    private fun profileKind(nick: String): String = "rp_" + Txt.key(nick).take(24)

    private suspend fun recordRivalProfile(slot: Int, fields: Map<String, Reading>, now: Long) {
        val f = fieldMap(slot)
        val nick = fields[K.RIVAL_NICK]?.value ?: f[K.RIVAL_NICK]?.value ?: return
        if (!FieldMerge.known(nick) || !Evidence.plausibleNick(nick)) return
        val attrs = listOf(
            K.RIVAL_FORMATION to "formation", K.RIVAL_PLAN to "plan", K.RIVAL_MARKING to "marking",
            K.RIVAL_OFFSIDE to "offside", K.RIVAL_TACKLE to "tackle"
        )
        val e = org.json.JSONObject()
        var any = false
        for ((k, n) in attrs) {
            val v = fields[k]?.value
            if (v != null && FieldMerge.known(v)) {
                e.put(n, v)
                any = true
            }
        }
        if (!any) return
        val round = f[K.ROUND]?.value?.toIntOrNull() ?: -1
        e.put("round", round)
        e.put("at", now)
        val kind = profileKind(nick)
        val old = dao.plan(slot, kind)
        val j = try { if (old != null) org.json.JSONObject(old.json) else org.json.JSONObject() } catch (ex: Exception) { org.json.JSONObject() }
        j.put("nick", nick)
        val arr = j.optJSONArray("entries") ?: org.json.JSONArray()
        val list = ArrayList<org.json.JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
        val last = list.lastOrNull()
        if (last != null && last.optInt("round", -2) == round) {
            for (k in e.keys()) last.put(k, e.get(k))
        } else {
            list.add(e)
        }
        val out = org.json.JSONArray()
        for (x in list.takeLast(10)) out.put(x)
        j.put("entries", out)
        dao.putPlan(PlanEntity(slot, kind, j.toString(), now))
    }

    /** Perfil do usuário rival pelo apelido, juntando os 4 slots (o mesmo humano pode estar em mais de uma liga). */
    suspend fun rivalProfile(slot: Int): PlanEntity? {
        val fm = fieldMap(slot)
        if (fm[K.RIVAL_HUMAN]?.value == "Não") return null
        val nick = fm[K.RIVAL_NICK]?.value ?: return null
        if (!FieldMerge.known(nick) || !Evidence.plausibleNick(nick)) return null
        return profileOf(nick, slot)
    }

    suspend fun profileOf(nick: String, slot: Int): PlanEntity? {
        val kind = profileKind(nick)
        val parts = (1..4).mapNotNull { dao.plan(it, kind) }
        if (parts.isEmpty()) return null
        if (parts.size == 1) return parts[0]
        val entries = ArrayList<org.json.JSONObject>()
        var switches = 0
        var checked = 0
        for (p in parts) {
            val j = try { org.json.JSONObject(p.json) } catch (e: Exception) { continue }
            val a = j.optJSONArray("entries") ?: org.json.JSONArray()
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { entries.add(it) }
            switches += j.optInt("switches")
            checked += j.optInt("checked")
        }
        val arr = org.json.JSONArray()
        for (e in entries.sortedBy { it.optLong("at") }.takeLast(10)) arr.put(e)
        val out = org.json.JSONObject().put("nick", nick).put("entries", arr).put("switches", switches).put("checked", checked)
        return PlanEntity(slot, kind, out.toString(), parts.maxOf { it.at })
    }

    /** O jogo mostrou a tática real do humano: se ela difere da lida antes, ele troca na última hora. */
    private suspend fun recordSwitch(slot: Int, nick: String, readBefore: String?, played: String?, now: Long) {
        if (readBefore == null || played == null) return
        val a = Formations.canonical(readBefore) ?: return
        val b = Formations.canonical(played) ?: return
        val kind = profileKind(nick)
        val old = dao.plan(slot, kind)
        val j = try { if (old != null) org.json.JSONObject(old.json) else org.json.JSONObject().put("nick", nick) } catch (e: Exception) { org.json.JSONObject().put("nick", nick) }
        j.put("checked", j.optInt("checked") + 1)
        if (a != b) {
            j.put("switches", j.optInt("switches") + 1)
            learn(slot, "rival", "$nick trocou a tática na última hora: lida $a, jogou $b.", now)
        }
        dao.putPlan(PlanEntity(slot, kind, j.toString(), now))
    }

    /** Humano que já trocou a tática na última hora (pelo menos 1 vez em 3 conferidas). */
    suspend fun rivalSwitches(slot: Int): Pair<Int, Int> {
        val p = rivalProfile(slot) ?: return Pair(0, 0)
        val j = try { org.json.JSONObject(p.json) } catch (e: Exception) { return Pair(0, 0) }
        return Pair(j.optInt("switches"), j.optInt("checked"))
    }

    // ------------------------------------------------------------ jogadores indisponíveis

    /** Jogadores marcados como fora do próximo jogo (suspenso/lesionado), valendo só para a rodada marcada. */
    suspend fun unavailable(slot: Int): Set<String> {
        val round = fieldMap(slot)[K.ROUND]?.value?.toIntOrNull() ?: -1
        val j = planJson(slot, "unavail")
        val manual = j.keys().asSequence().filter { j.optInt(it, -9) == round }.toSet()
        val (inj, susp) = outByDoctorOrLawyer(slot)
        return manual + inj + susp
    }

    suspend fun setUnavailable(slot: Int, nameKey: String, out: Boolean) {
        val round = fieldMap(slot)[K.ROUND]?.value?.toIntOrNull() ?: -1
        val j = planJson(slot, "unavail")
        if (out) j.put(nameKey, round) else j.remove(nameKey)
        dao.putPlan(PlanEntity(slot, "unavail", j.toString(), System.currentTimeMillis()))
    }

    // ------------------------------------------------------------ leituras divergentes

    /** Leituras da mesma informação que não batem (ex.: força 72 numa tela e 81 em outra), para o usuário conferir. */
    suspend fun conflicts(slot: Int): List<String> {
        val j = planJson(slot, "conflicts")
        val now = System.currentTimeMillis()
        return j.keys().asSequence().mapNotNull { k ->
            val o = j.optJSONObject(k) ?: return@mapNotNull null
            if (now - o.optLong("at") > 24L * 3600000L) return@mapNotNull null
            "${Completeness.ITEMS.firstOrNull { it.key == k }?.label ?: k}: ${o.optString("a")} x ${o.optString("b")}"
        }.toList()
    }

    /** Formação que esse rival humano mais usou (para quando a atual ainda não foi lida). */
    suspend fun rivalProfileFormation(slot: Int): String? {
        val p = rivalProfile(slot) ?: return null
        val counts = HashMap<String, Int>()
        try {
            val arr = org.json.JSONObject(p.json).optJSONArray("entries") ?: return null
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i)?.optString("formation") ?: continue
                if (v.isNotBlank()) counts[v] = (counts[v] ?: 0) + 1
            }
        } catch (e: Exception) {
            return null
        }
        return counts.maxByOrNull { it.value }?.key
    }

    // ------------------------------------------------------------------ resultado registrado à mão

    /** Registra o resultado de um jogo que teve tática gerada; entra no mesmo formato da leitura automática. */
    suspend fun registerManualResult(
        slot: Int, round: Int, mine: Int, opp: Int, mineHome: Boolean,
        possession: Int?, myShots: Int?, oppShots: Int?, myFouls: Int?, oppFouls: Int?,
        oppFormation: String?, mom: String?
    ) {
        val now = System.currentTimeMillis()
        val f = fieldMap(slot)
        val key = "mr_R$round"
        val old = dao.plan(slot, key)
        val j = try { if (old != null) org.json.JSONObject(old.json) else org.json.JSONObject() } catch (e: Exception) { org.json.JSONObject() }
        val tl = dao.plan(slot, "tlog_R$round")?.json?.let { try { org.json.JSONObject(it) } catch (e: Exception) { null } }
        val myTeam = f[K.TEAM]?.value ?: "Meu time"
        val rival = tl?.optString("rival")?.takeIf { it.isNotBlank() && it != NI } ?: f[K.RIVAL_TEAM]?.value ?: "Adversário"
        j.put("round", round)
        j.put("mineHome", mineHome)
        j.put("homeTeam", if (mineHome) myTeam else rival)
        j.put("awayTeam", if (mineHome) rival else myTeam)
        j.put("sh", if (mineHome) mine else opp)
        j.put("sa", if (mineHome) opp else mine)
        j.put("manual", true)
        if (!mom.isNullOrBlank()) j.put("mom", mom.trim())
        val stats = j.optJSONObject("stats") ?: org.json.JSONObject()
        fun pair(label: String, a: String?, b: String?) {
            if (a == null || b == null) return
            val arr = org.json.JSONArray()
            arr.put(if (mineHome) a else b)
            arr.put(if (mineHome) b else a)
            stats.put(label, arr)
        }
        pair("golos", mine.toString(), opp.toString())
        if (possession != null) pair("posse de bola", "$possession%", "${100 - possession}%")
        pair("remates", myShots?.toString(), oppShots?.toString())
        pair("faltas", myFouls?.toString(), oppFouls?.toString())
        if (!oppFormation.isNullOrBlank()) pair("formacao", tl?.optString("formation")?.ifBlank { null } ?: NI, oppFormation.trim())
        j.put("stats", stats)
        dao.putPlan(PlanEntity(slot, key, j.toString(), now))
        // permite corrigir um resultado já registrado: limpa o resultado da tática para o vínculo gravar o novo
        val tp = dao.plan(slot, "tlog_R$round")
        if (tp != null && tl != null) {
            tl.put("result", org.json.JSONObject.NULL)
            dao.putPlan(PlanEntity(slot, "tlog_R$round", tl.toString(), tp.at))
        }
        linkResult(slot, round, j, now)
    }

    // ------------------------------------------------------------------ treinos informados à mão

    suspend fun trainingOverrides(slot: Int): Map<String, Boolean> {
        val p = dao.plan(slot, "trainov") ?: return emptyMap()
        val out = HashMap<String, Boolean>()
        try {
            val j = org.json.JSONObject(p.json)
            for (k in j.keys()) out[k] = j.optBoolean(k)
        } catch (e: Exception) {
            return emptyMap()
        }
        return out
    }

    /** Jogadores do slot com o que o usuário marcou à mão sobre treino (vale mais que a leitura da camisa laranja). */
    /** Jogadores "inventados" pela leitura do botão flutuante colado no nome (ex. "Encerraroden"): saem. */
    suspend fun purgeOverlayNames(slot: Int) {
        val all = dao.playersOf(slot)
        val removed = ArrayList<String>()
        for (p in all) {
            val n = Txt.norm(p.name).replace(Regex("[^a-z ]"), "")
            if (Overlay.glued(p.name) || n.split(" ").any { it.startsWith("encerrar") }) {
                dao.deletePlayer(slot, p.owner, p.nameKey)
                continue
            }
            // Nome com texto de botão do OSM ("Atribuir número", "MAIS EM BREVE", "Ata") ou lixo de OCR ("MdLK"):
            // se o nome limpo já existe, era fantasma desse jogador; se não sobra nome, não era jogador.
            val clean = PlayerName.clean(p.name)
            if (clean == p.name) continue
            val key = clean?.let { Txt.key(it) }
            val twin = key?.let { k -> all.firstOrNull { it !== p && it.owner == p.owner && (it.nameKey == k || Txt.sim(it.nameKey, k) >= 0.9) } }
            dao.deletePlayer(slot, p.owner, p.nameKey)
            if (clean != null && key != null && twin == null && key.length >= 3) {
                dao.putPlayer(p.copy(nameKey = key, name = clean))
            } else removed.add(p.name)
        }
        if (removed.isNotEmpty()) {
            learn(slot, "elenco", "Leituras falsas removidas do elenco (texto de botão ou lixo do OCR): ${removed.joinToString(", ")}.")
        }
    }

    suspend fun playersOf(slot: Int): List<PlayerEntity> {
        purgeOverlayNames(slot)
        val ov = trainingOverrides(slot)
        return dao.playersOf(slot).map { p ->
            val o = if (p.owner == "MY") ov[p.nameKey] else null
            if (o != null) p.copy(training = o) else p
        }
    }

    suspend fun setTrainingOverride(slot: Int, nameKey: String, value: Boolean?) {
        val j = org.json.JSONObject()
        for ((k, v) in trainingOverrides(slot)) j.put(k, v)
        if (value == null) j.remove(nameKey) else j.put(nameKey, value)
        dao.putPlan(PlanEntity(slot, "trainov", j.toString(), System.currentTimeMillis()))
    }

    // ------------------------------------------------------------------ resultado do jogo

    private fun statPair(j: org.json.JSONObject, label: String): Pair<String, String>? {
        val a = j.optJSONObject("stats")?.optJSONArray(label) ?: return null
        return Pair(a.optString(0), a.optString(1))
    }

    private fun pct(s: String): Int? = Regex("(\\d{1,3})").find(s)?.groupValues?.get(1)?.toIntOrNull()

    private suspend fun applyResult(slot: Int, ex: Extraction, now: Long): Int {
        val rep = ex.matchReport ?: return 0
        val f = fieldMap(slot)
        var round = rep.round
        if (round != null) {
            ResultCtx.slot = slot
            ResultCtx.round = round
            ResultCtx.at = now
        } else if (ResultCtx.slot == slot && ResultCtx.round != null && now - ResultCtx.at < 600000L) {
            round = ResultCtx.round
            ResultCtx.at = now
        } else {
            val done = f[K.ROUND_DONE]?.value?.toIntOrNull()
            val next = f[K.ROUND]?.value?.toIntOrNull()
            round = done ?: next?.let { it - 1 }
        }
        if (round == null || round <= 0) return 0
        val key = "mr_R$round"
        val old = dao.plan(slot, key)
        val j = try { if (old != null) org.json.JSONObject(old.json) else org.json.JSONObject() } catch (e: Exception) { org.json.JSONObject() }
        var changed = 0
        fun put(k: String, v: Any?) {
            if (v == null) return
            if (j.opt(k)?.toString() != v.toString()) {
                j.put(k, v)
                changed++
            }
        }
        put("round", round)
        put("homeTeam", rep.homeTeam)
        put("awayTeam", rep.awayTeam)
        put("homeNick", rep.homeNick)
        put("awayNick", rep.awayNick)
        put("sh", rep.scoreHome)
        put("sa", rep.scoreAway)
        put("referee", rep.referee)
        put("tip", rep.tip)
        put("advice", rep.advice)
        if (Overlay.isChip(j.optString("mom"))) j.remove("mom")
        put("mom", rep.mom?.takeIf { !Overlay.isChip(it) })
        if (rep.homeNick != null || rep.awayNick != null) {
            val mineHome = rep.homeNick != null && Txt.sim(Txt.key(rep.homeNick), MY_NICK) >= 0.75
            val mineAway = rep.awayNick != null && Txt.sim(Txt.key(rep.awayNick), MY_NICK) >= 0.75
            if (mineHome != mineAway) put("mineHome", mineHome)
        }
        val stats = j.optJSONObject("stats") ?: org.json.JSONObject()
        for ((lab, pr) in rep.stats) {
            val arr = org.json.JSONArray().put(pr.first).put(pr.second)
            if (stats.optJSONArray(lab)?.toString() != arr.toString()) {
                stats.put(lab, arr)
                changed++
            }
        }
        j.put("stats", stats)
        if (rep.zones.size == 3) {
            val z = org.json.JSONArray()
            for (v in rep.zones) z.put(v)
            put("zones", z)
        }
        fun mergeRatings(k: String, list: List<Pair<String, Int>>) {
            if (list.isEmpty()) return
            val cur = j.optJSONArray(k) ?: org.json.JSONArray()
            val names = HashSet<String>()
            for (i in 0 until cur.length()) names.add(cur.optJSONArray(i)?.optString(0) ?: "")
            for ((n, r) in list) {
                if (n !in names) {
                    cur.put(org.json.JSONArray().put(n).put(r))
                    names.add(n)
                    changed++
                }
            }
            j.put(k, cur)
        }
        mergeRatings("rh", rep.ratingsHome)
        mergeRatings("ra", rep.ratingsAway)
        val evs = j.optJSONArray("events") ?: org.json.JSONArray()
        val seen = HashSet<String>()
        for (i in 0 until evs.length()) seen.add(evs.optString(i))
        for (e in rep.events) if (seen.add(e)) {
            evs.put(e)
            changed++
        }
        j.put("events", evs)
        // lesão (cruz vermelha) no MEU lado do jogo: soma o jogador à lista de lesionados (o Médico confirma/limpa depois)
        if (j.has("mineHome")) {
            val side = if (j.optBoolean("mineHome")) "(casa)" else "(visitante)"
            val mineP = dao.playersOf(slot).filter { it.owner == "MY" }
            val hurt = ArrayList<String>()
            for (e in rep.events) if (e.contains(side) && e.contains(" lesão de ")) {
                val nm = e.substringAfter(" lesão de ").substringBefore(" (").trim()
                mineP.firstOrNull { nameMatches(nm, it) }?.let { if (it.nameKey !in hurt) hurt.add(it.nameKey) }
            }
            if (hurt.isNotEmpty()) {
                val oldI = planJson(slot, "injured")
                val cur = oldI.optJSONArray("names")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                val merged = (cur + hurt.filter { it !in cur })
                if (merged.size != cur.size) {
                    dao.putPlan(PlanEntity(slot, "injured", JSONObject().put("names", JSONArray(merged)).put("at", now).toString(), now))
                    learn(slot, "elenco", "Lesão no jogo: " + hurt.joinToString { n -> mineP.firstOrNull { it.nameKey == n }?.name ?: n } + ".", now)
                }
            }
        }
        if (changed > 0 || old == null) {
            dao.putPlan(PlanEntity(slot, key, j.toString(), now))
            linkResult(slot, round, j, now)
        }
        return changed
    }

    /** Liga o relatório ao calendário e à tática usada naquela rodada (é daqui que a IA aprende). */
    private suspend fun linkResult(slot: Int, round: Int, j: org.json.JSONObject, now: Long) {
        if (!j.has("mineHome")) {
            // Sem apelidos legíveis no relatório: descobre o lado pelo nome do meu time ou pelo card do calendário.
            val side = mineSide(slot, round, j.optString("homeTeam"), j.optString("awayTeam")) ?: return
            j.put("mineHome", side)
            dao.putPlan(PlanEntity(slot, "mr_R$round", j.toString(), now))
        }
        val mineHome = j.optBoolean("mineHome")
        val hasScore = j.has("sh") && j.has("sa")
        val mine = if (hasScore) (if (mineHome) j.optInt("sh") else j.optInt("sa")) else null
        val opp = if (hasScore) (if (mineHome) j.optInt("sa") else j.optInt("sh")) else null
        val oppName = (if (mineHome) j.optString("awayTeam") else j.optString("homeTeam")).ifBlank { null }
        if (mine != null && opp != null) {
            setManualMatch(slot, round, oppName, mine, opp, mineHome)
            // jogo desta rodada acabou: a "próxima rodada" passa a ser a seguinte e o horário antigo sai
            // (o pré-jogo ou o calendário confirmam depois)
            syncRound(slot, now)
        }
        // Tática usada neste jogo: a da rodada, ou (rodada lida diferente) a última sem resultado contra o mesmo rival.
        fun sameRival(js: String): Boolean = try {
            val rv = Txt.key(org.json.JSONObject(js).optString("rival"))
            oppName == null || rv.length < 3 || SlotMatcher.nameSim(rv, Txt.key(oppName)) >= 0.8
        } catch (e: Exception) { false }
        val p = dao.plan(slot, "tlog_R$round")?.takeIf { sameRival(it.json) }
            ?: dao.tacticLogs(slot).filter { lp ->
                val lj = try { org.json.JSONObject(lp.json) } catch (e: Exception) { null }
                lj != null && lj.isNull("result") && lj.optInt("round", -99) in (round - 2)..round && oppName != null &&
                    SlotMatcher.nameSim(Txt.key(lj.optString("rival")), Txt.key(oppName)) >= 0.8
            }.maxByOrNull { it.at }
            ?: return
        val t = try { org.json.JSONObject(p.json) } catch (e: Exception) { return }
        t.put("round", round)
        if (mine != null && opp != null && t.isNull("result")) {
            t.put("result", if (mine > opp) "V" else if (mine == opp) "E" else "D")
            t.put("scoreMine", mine)
            t.put("scoreOpp", opp)
            learn(slot, "resultado", "Tática ${t.optString("formation")} (${t.optString("playStyle")}) na J$round → ${t.optString("result")} $mine-$opp (análise do jogo)", now)
        }
        fun side(label: String, mineSide: Boolean): String? {
            val pr = statPair(j, label) ?: return null
            val home = mineHome == mineSide
            return if (home) pr.first else pr.second
        }
        // o que foi JOGADO de verdade: a tática montada no OSM (tela de Tática, mesma rodada) e a formação que o Resultado mostra.
        // Sem isso, derrota com tática trocada à mão era atribuída à tática do app (e o aprendizado ficava errado).
        runCatching {
            if (!t.has("genFormation")) {
                val gen = t.optString("formation")
                val m = dao.plan(slot, "tacmounted")?.json?.let { org.json.JSONObject(it) }?.takeIf { it.optInt("round", -2) == round }
                val resultForm = side("formacao", true)?.let { Formations.canonical(it) }
                val usedForm = resultForm ?: m?.optString("formation")?.takeIf { it.isNotBlank() }?.let { Formations.canonical(it) }
                var deviated = usedForm != null && Formations.canonical(gen) != usedForm
                if (m != null) {
                    val ps = m.optString("playStyle").takeIf { it.isNotBlank() }
                    if (ps != null && Osm.style(ps) != Osm.style(t.optString("playStyle"))) { t.put("genPlayStyle", t.optString("playStyle")); t.put("playStyle", ps); deviated = true }
                    val tk = m.optString("tackle").takeIf { it.isNotBlank() }
                    if (tk != null && Osm.tackle(tk) != Osm.tackle(t.optString("tackle"))) { t.put("genTackle", t.optString("tackle")); t.put("tackle", tk); deviated = true }
                    val mk = m.optString("marking").takeIf { it.isNotBlank() }
                    if (mk != null && Osm.marking(mk) != Osm.marking(t.optString("marking"))) { t.put("genMarking", t.optString("marking")); t.put("marking", mk); deviated = true }
                }
                if (usedForm != null && Formations.canonical(gen) != usedForm) { t.put("genFormation", gen); t.put("formation", usedForm) }
                if (deviated) {
                    t.put("deviated", true)
                    learn(slot, "resultado", "Na J$round você jogou ${t.optString("formation")} • ${t.optString("playStyle")} (a do app era $gen): o aprendizado usa o que foi jogado.", now)
                }
            }
        }
        side("posse de bola", true)?.let { v -> pct(v)?.let { t.put("myPossession", it) } }
        side("faltas", true)?.let { v -> pct(v)?.let { t.put("myFouls", it) } }
        side("remates", true)?.let { v -> pct(v)?.let { t.put("myShots", it) } }
        side("remates", false)?.let { v -> pct(v)?.let { t.put("oppShots", it) } }
        side("formacao", false)?.let { t.put("oppFormation", it) }
        val tn = t.optString("rivalNick").takeIf { it.isNotBlank() && FieldMerge.known(it) }
        if (tn != null && !t.has("switchChecked")) {
            recordSwitch(slot, tn, t.optString("rivalFormationRead").ifBlank { null }, side("formacao", false), now)
            t.put("switchChecked", true)
        }
        j.optString("mom").takeIf { it.isNotBlank() && !Overlay.isChip(it) }?.let { t.put("mom", it) }
        // nota média dos meus jogadores por setor (a tela de Resultado mostra a nota de cada um)
        runCatching { sectorRatings(slot, j, mineHome) }.getOrNull()?.let { t.put("secRate", it) }
        dao.putPlan(PlanEntity(slot, p.kind, t.toString(), p.at))
    }

    /** {GOL, DEF, MEI, ATA: nota média, n: jogadores casados} dos meus jogadores no relatório; null sem notas ou sem elenco. */
    private suspend fun sectorRatings(slot: Int, j: org.json.JSONObject, mineHome: Boolean?): org.json.JSONObject? {
        val arr = j.optJSONArray(if (mineHome == true) "rh" else "ra") ?: return null
        val mine = playersOf(slot).filter { it.owner == "MY" }
        if (mine.isEmpty()) return null
        val by = HashMap<String, MutableList<Int>>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONArray(i) ?: continue
            val nm = Txt.key(a.optString(0))
            val r = a.optInt(1)
            if (nm.length < 3 || r !in 1..10) continue
            val p = mine.firstOrNull { it.nameKey == nm } ?: mine.firstOrNull { (it.nameKey.length >= 4 && nm.length >= 4) && (it.nameKey.endsWith(nm) || nm.endsWith(it.nameKey)) } ?: continue
            by.getOrPut(p.cat ?: continue) { ArrayList() }.add(r)
        }
        if (by.isEmpty()) return null
        val o = org.json.JSONObject()
        for ((k, v) in by) o.put(k, Math.round(v.average() * 10) / 10.0)
        o.put("n", by.values.sumOf { it.size })
        return o
    }

    /**
     * Análises do jogo já lidas que nunca chegaram ao calendário/tática (sem lado definido ou com placar
     * diferente do card): liga de novo. Idempotente; roda ao abrir o slot.
     */
    /**
     * Limpa o que já foi gravado com texto dos botões do app ("App", "Encerrar", "Encerar-White"...) e apelidos
     * falsos do calendário (o próprio nome do time, "ED:"). Idempotente; roda ao abrir o slot.
     */
    suspend fun cleanPollution(slot: Int) {
        val now = System.currentTimeMillis()
        // Rival CPU não tem apelido (nem "perfil de humano"); apelido que é lixo de OCR também sai. O digitado fica.
        run {
            val ev = planJson(slot, "evidence")
            if (Evidence.purgeNicks(ev)) dao.putPlan(PlanEntity(slot, "evidence", ev.toString(), now))
            val rows = dao.fieldsOf(slot).associateBy { it.fkey }
            val nick = rows[K.RIVAL_NICK]
            if (nick != null && nick.source != "manual" && (rows[K.RIVAL_HUMAN]?.fvalue == "Não" || !Evidence.plausibleNick(nick.fvalue))) {
                dao.deleteField(slot, K.RIVAL_NICK)
            }
        }
        // Bônus de outro jogo (lido ou digitado há mais de 30 h): sai, para a tela pedir o de hoje em vez de mostrar o velho.
        for (row in dao.fieldsOf(slot)) {
            if (row.fkey in FieldMerge.PER_GAME && !FieldMerge.fresh(row.fkey, StoredField(row.fvalue, row.conf, row.updatedAt), now)) {
                dao.deleteField(slot, row.fkey)
            }
        }
        for (p in dao.matchReports(slot)) {
            val j = try { org.json.JSONObject(p.json) } catch (e: Exception) { continue }
            var dirty = false
            j.optJSONObject("stats")?.let { st ->
                for (k in st.keys().asSequence().toList()) {
                    val a = st.optJSONArray(k) ?: continue
                    if ((0 until a.length()).any { Overlay.polluted(a.optString(it)) }) {
                        st.remove(k)
                        dirty = true
                    }
                }
            }
            if (j.has("mom") && Overlay.polluted(j.optString("mom"))) {
                j.remove("mom")
                dirty = true
            }
            for (side in listOf("rh", "ra", "events")) {
                val a = j.optJSONArray(side) ?: continue
                val keep = org.json.JSONArray()
                for (i in 0 until a.length()) {
                    val txt = a.optJSONArray(i)?.optString(0) ?: a.optString(i)
                    if (Overlay.polluted(txt) || Txt.norm(txt).contains(" app ") || Regex("encerr?ar", RegexOption.IGNORE_CASE).containsMatchIn(txt)) {
                        dirty = true
                    } else keep.put(a.get(i))
                }
                j.put(side, keep)
            }
            if (dirty) dao.putPlan(PlanEntity(slot, p.kind, j.toString(), p.at))
        }
        for (m in dao.matchesOf(slot)) {
            val nk = m.opponentNick ?: continue
            val opp = m.opponent
            if (nk.contains(':') || Overlay.polluted(nk) || (opp != null && Txt.sim(Txt.key(nk), Txt.key(opp)) >= 0.8)) {
                dao.putMatch(m.copy(opponentNick = null))
            }
        }
        dao.plan(slot, "cm")?.let { p ->
            val j = try { org.json.JSONObject(p.json) } catch (e: Exception) { return@let }
            val bad = j.keys().asSequence().filter { Overlay.glued(it) }.toList()
            if (bad.isNotEmpty()) {
                bad.forEach { j.remove(it) }
                dao.putPlan(PlanEntity(slot, "cm", j.toString(), now))
            }
        }
    }

    suspend fun relinkResults(slot: Int) {
        cleanPollution(slot)
        val now = System.currentTimeMillis()
        for (p in dao.matchReports(slot)) {
            val round = p.kind.removePrefix("mr_R").toIntOrNull() ?: continue
            val j = try { org.json.JSONObject(p.json) } catch (e: Exception) { continue }
            if (!j.has("sh") || !j.has("sa")) continue
            if (j.has("mom") && Overlay.isChip(j.optString("mom"))) {
                j.remove("mom")
                dao.putPlan(PlanEntity(slot, p.kind, j.toString(), p.at))
            }
            val m = dao.match(slot, "L$round")
            val tl = dao.plan(slot, "tlog_R$round")?.json?.let { try { org.json.JSONObject(it) } catch (e: Exception) { null } }
            // tática ainda sem resultado contra um dos times do relatório, numa rodada próxima (rodada lida diferente)
            val teams = listOf(j.optString("homeTeam"), j.optString("awayTeam")).map { Txt.key(it) }.filter { it.length >= 3 }
            val orphan = tl == null && dao.tacticLogs(slot).any { lp ->
                val lj = try { org.json.JSONObject(lp.json) } catch (e: Exception) { null }
                lj != null && lj.isNull("result") && lj.optInt("round", -99) in (round - 2)..round &&
                    teams.any { SlotMatcher.nameSim(Txt.key(lj.optString("rival")), it) >= 0.8 }
            }
            val linked = j.has("mineHome") && m?.scoreMine != null && (tl == null || !tl.isNull("result")) && !orphan
            if (!linked) linkResult(slot, round, j, now)
        }
    }

    /**
     * Bolha do MEI (MED) não lida: a força do time é a média dos 11, então MEI = (11 x força - GOL - d x DEF - a x ATA) / m,
     * com d/m/a da formação (sem formação: 4-3-3). Gravada com confiança baixa: a leitura real substitui.
     */
    private suspend fun estimateMid(slot: Int, prefix: String, now: Long): Int {
        val f = fieldMap(slot)
        if (FieldMerge.known(f[prefix + "mid"]?.value)) return 0
        val st = (if (prefix == "my.") f[K.MY_STRENGTH] else f[K.RIVAL_STRENGTH])?.value?.toIntOrNull() ?: return 0
        val gol = f[prefix + "gol"]?.value?.toIntOrNull() ?: return 0
        val def = f[prefix + "def"]?.value?.toIntOrNull() ?: return 0
        val atk = f[prefix + "atk"]?.value?.toIntOrNull() ?: return 0
        val lines = Formations.lines(f[prefix + "formation"]?.value ?: "").ifEmpty { listOf(4, 3, 3) }
        val d = lines.first()
        val a = lines.last()
        val m = 10 - d - a
        if (m <= 0) return 0
        val mid = Math.round((11.0 * st - gol - d * def - a * atk) / m).toInt()
        if (mid !in 30..130 || kotlin.math.abs(mid - st) > 15) return 0
        dao.putField(FieldEntity(slot, prefix + "mid", mid.toString(), 0.5, now, "estimativa"))
        return 1
    }

    // ------------------------------------------------------------------ evidências cruzadas

    /** Campos conferidos entre telas diferentes. */
    private val TRACKED = setOf(K.RIVAL_NICK, K.RIVAL_TEAM, K.RIVAL_STRENGTH, K.RIVAL_FORMATION, K.HOME)

    suspend fun evidence(slot: Int): org.json.JSONObject = planJson(slot, "evidence")

    /**
     * Grava as leituras normais e registra as conferidas como evidência. Apelido e "humano = Sim" nunca entram
     * direto: só quando o apelido bate em fontes independentes (ex.: pré-jogo + calendário, ou a análise).
     */
    private suspend fun putTracked(slot: Int, readings: Map<String, Reading>, source: String, evSource: String, now: Long): Int {
        val plain = LinkedHashMap<String, Reading>()
        val ev = LinkedHashMap<String, String>()
        for ((k, r) in readings) {
            if (k in TRACKED) ev[k] = r.value
            if (k == K.RIVAL_NICK) continue
            if (k == K.RIVAL_HUMAN && r.value == "Sim") continue
            plain[k] = r
        }
        if (ev[K.RIVAL_NICK]?.let { Evidence.plausibleNick(it) } == false) ev.remove(K.RIVAL_NICK)
        var changed = putFields(slot, plain, source, now)
        if (ev.isNotEmpty()) changed += recordEvidence(slot, ev, evSource, now)
        return changed
    }

    private suspend fun recordEvidence(slot: Int, values: Map<String, String>, evSource: String, now: Long): Int {
        val j = planJson(slot, "evidence")
        for ((k, v) in values) {
            if (k == K.RIVAL_NICK && !Evidence.plausibleNick(v)) continue
            Evidence.add(j, k, v, evSource, now)
        }
        dao.putPlan(PlanEntity(slot, "evidence", j.toString(), now))
        return confirmHuman(slot, j, now)
    }

    /** Apelido confirmado (peso >= 2) => rival humano; sem confirmação, um "Sim" automático antigo é desfeito. */
    private suspend fun confirmHuman(slot: Int, j: org.json.JSONObject, now: Long): Int {
        val ok = Evidence.confirmed(j, K.RIVAL_NICK)
        // Humano já certo (marcado à mão ou Batalha): basta uma tela para gravar o apelido.
        val human = dao.fieldsOf(slot).firstOrNull { it.fkey == K.RIVAL_HUMAN }
        if (ok == null && human != null && human.fvalue == "Sim" && (human.source == "manual" || human.source == "hub")) {
            val one = Evidence.best(j, K.RIVAL_NICK) ?: return 0
            return putFields(slot, mapOf(K.RIVAL_NICK to Reading(one.value, 0.85)), "evidencia", now)
        }
        if (ok != null) {
            return putFields(slot, mapOf(K.RIVAL_NICK to Reading(ok.value, 0.9), K.RIVAL_HUMAN to Reading("Sim", 0.9)), "evidencia", now)
        }
        // Bônus de login do rival lido no pré-jogo: prova de humano (time de CPU mostra a força, não bônus).
        val bonus = dao.fieldsOf(slot).firstOrNull { it.fkey == K.RIVAL_LOGIN_BONUS }?.fvalue?.takeIf { FieldMerge.known(it) }
        if (bonus != null && (human == null || human.fvalue != "Sim")) {
            val r = LinkedHashMap<String, Reading>()
            r[K.RIVAL_HUMAN] = Reading("Sim", 0.9)
            Evidence.best(j, K.RIVAL_NICK)?.let { r[K.RIVAL_NICK] = Reading(it.value, 0.85) }
            val n = putFields(slot, r, "bonus", now)
            if (n > 0) learn(slot, "rival", "Rival com bônus de login ($bonus): é humano.", now)
            return n
        }
        return 0
    }

    /** Algum sinal de que o rival é humano (bônus de login, apelido lido em alguma tela ou humano já marcado). */
    private suspend fun humanHints(slot: Int): Boolean {
        val rows = dao.fieldsOf(slot).associateBy { it.fkey }
        if (rows[K.RIVAL_HUMAN]?.fvalue == "Sim") return true
        if (rows[K.RIVAL_LOGIN_BONUS]?.fvalue?.let { FieldMerge.known(it) } == true) return true
        return Evidence.best(planJson(slot, "evidence"), K.RIVAL_NICK) != null
    }

    /**
     * Corrige leituras antigas: "humano" marcado por um texto qualquer (ex. "25 Anniversary" no fundo) sem
     * confirmação em outra tela é apagado. Não mexe no que o usuário digitou nem em Batalha (sempre humano).
     */
    suspend fun validateHuman(slot: Int) {
        val rows = dao.fieldsOf(slot).associateBy { it.fkey }
        val human = rows[K.RIVAL_HUMAN] ?: return
        if (human.fvalue != "Sim" || human.source == "manual" || human.source == "hub" || human.source == "bonus") return
        if (rows[K.RIVAL_LOGIN_BONUS]?.fvalue?.let { FieldMerge.known(it) } == true) return
        val nick = rows[K.RIVAL_NICK]
        val ev = planJson(slot, "evidence")
        val conf = Evidence.confirmed(ev, K.RIVAL_NICK)
        val nickOk = nick != null && Evidence.plausibleNick(nick.fvalue) && conf != null &&
            Txt.sim(Txt.key(conf.value), Txt.key(nick.fvalue)) >= 0.85
        if (!nickOk) {
            dao.deleteField(slot, K.RIVAL_HUMAN)
            if (nick != null && nick.source != "manual") dao.deleteField(slot, K.RIVAL_NICK)
            learn(slot, "rival", "Apelido \"${nick?.fvalue ?: "?"}\" não foi confirmado em outra tela (calendário, análise ou plantel do rival): rival humano desmarcado até confirmar.")
        }
    }

    // ------------------------------------------------------------------ elenco: fantasmas e saídas

    private suspend fun planJson(slot: Int, kind: String): org.json.JSONObject =
        dao.plan(slot, kind)?.json?.let { try { org.json.JSONObject(it) } catch (e: Exception) { null } } ?: org.json.JSONObject()

    private suspend fun blockedPlayers(slot: Int): Set<String> {
        val j = planJson(slot, "squadblock")
        val out = HashSet<String>()
        for (k in j.keys()) out.add(k)
        return out
    }

    /** Última vez que cada jogador do meu elenco apareceu numa leitura (para achar quem saiu ou nunca existiu). */
    /**
     * Evolução do time: histórico de força/valor de quem foi lido (com a marca de treino), contratações no livro de
     * caixa (preço da lista de transferências quando o jogador foi visto lá) e o resumo da semana.
     */
    /** Evolução (semanas, livro de caixa, histórico de treino) é do CLUBE: se o time do slot mudou, começa do zero (valores do clube antigo não se comparam). */
    private suspend fun guardEvoTeam(slot: Int, now: Long) {
        val team = fieldMap(slot)[K.TEAM]?.value?.takeIf { FieldMerge.known(it) } ?: return
        val tag = dao.plan(slot, "evoteam")?.json
        val weeks = Weekly.parse(dao.plan(slot, "weekly")?.json)
        val cur = Money.parse(fieldMap(slot)[K.MY_VALUE]?.value)
        // sem marca (versões antigas): só zera se o valor atual é incompatível com o histórico (outro clube)
        val mixed = tag == null && cur != null && weeks.any { w -> w.value != null && w.value > 0 && (cur / w.value > 3.0 || w.value / cur > 3.0) }
        val other = tag != null && Txt.sim(Txt.key(tag), Txt.key(team)) < 0.6
        if (other || mixed) {
            for (k in listOf("weekly", "ledger", "phist")) dao.deletePlan(slot, k)
            learn(slot, "elenco", "Evolução reiniciada: o time do slot mudou para $team.", now)
        }
        if (tag == null || other) dao.putPlan(PlanEntity(slot, "evoteam", team, now))
    }

    /** Valor de jogador que muda ~10× de uma leitura para outra é vírgula perdida/ganha pelo OCR ("7,6M" ↔ "76M"): mantém o anterior. */
    private fun sanePlayerValue(old: String?, new: String?): String? {
        if (new == null) return old
        val o = Money.parse(old); val n = Money.parse(new)
        if (o != null && n != null && o > 0 && n > 0) {
            val r = n / o
            if (r in 8.0..12.5 || r in 0.08..0.125) return old
        }
        return new
    }

    private suspend fun trackProgress(slot: Int, seen: List<String>, arrived: List<String>, now: Long) {
        guardEvoTeam(slot, now)
        try {
            val mine = playersOf(slot).filter { it.owner == "MY" }
            val byKey = mine.associateBy { it.nameKey }
            val hist = planJson(slot, "phist")
            var histChanged = false
            for (k in seen) {
                val p = byKey[k] ?: continue
                if (Growth.record(hist, k, now, p.strength, Money.parse(p.valueText), p.training == true)) histChanged = true
            }
            if (histChanged) dao.putPlan(PlanEntity(slot, "phist", hist.toString(), now))
            if (arrived.isNotEmpty()) {
                val ledger = Ledger.parse(dao.plan(slot, "ledger")?.json)
                var add = false
                for (k in arrived) {
                    val p = byKey[k] ?: continue
                    val listed = dao.listing(slot, k) ?: dao.listingsOf(slot).firstOrNull { l ->
                        l.age == p.age && Txt.sim(Txt.key(l.name.split(" ").last()), Txt.key(p.name.split(" ").last())) >= 0.85
                    }
                    val price = Money.parse(listed?.priceText)
                    if (Ledger.add(ledger, Ledger.Entry("buy", k, p.name, now, price ?: Money.parse(p.valueText), price == null, p.strength, p.age))) {
                        add = true
                        learn(slot, "elenco", "Contratação: ${p.name}" + (price?.let { " por ${moneyText(it)}" } ?: "") + ".", now)
                    }
                }
                if (add) dao.putPlan(PlanEntity(slot, "ledger", Ledger.toJson(ledger), now))
            }
            snapshotWeek(slot, now)
        } catch (e: Exception) {
            Diag.lastError = "Evolução: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    /** Semana atual do slot: valor do elenco, caixa, força do XI e resultados (das táticas registradas). */
    suspend fun snapshotWeek(slot: Int, now: Long = System.currentTimeMillis()) {
        val f = fieldMap(slot)
        val mine = dao.playersOf(slot).filter { it.owner == "MY" }
        val res = dao.tacticLogs(slot).filter { it.kind.startsWith("tlog_R") }.mapNotNull { runCatching { org.json.JSONObject(it.json).optString("result") }.getOrNull() }
        val list = Weekly.parse(dao.plan(slot, "weekly")?.json)
        Weekly.upsert(
            list, Weekly.Week(
                Weekly.weekId(now), now, Money.parse(f[K.MY_VALUE]?.value), Money.parse(f[K.CASH]?.value),
                Weekly.xi(mine.mapNotNull { it.strength }), res.count { it == "V" }, res.count { it == "E" }, res.count { it == "D" }
            )
        )
        dao.putPlan(PlanEntity(slot, "weekly", Weekly.toJson(list), now))
    }

    data class ProgressData(
        val score: Scoreboard.Stats, val ledger: Ledger.Summary, val train: Growth.TrainReport?, val week: Weekly.Delta?,
        val weeks: List<Weekly.Week>, val falling: List<Triple<String, Double, Double>> = emptyList()
    )

    /** Tudo o que a tela de Evolução mostra. */
    suspend fun progress(slot: Int, now: Long = System.currentTimeMillis()): ProgressData {
        // só a competição atual (tlog_R*); as arquivadas ficam para o aprendizado
        val logs = dao.tacticLogs(slot).filter { it.kind.startsWith("tlog_R") }.mapNotNull { p -> runCatching { org.json.JSONObject(p.json).put("at", p.at) }.getOrNull() }
        val mine = dao.playersOf(slot).filter { it.owner == "MY" }
        val weeks = Weekly.parse(dao.plan(slot, "weekly")?.json)
        return ProgressData(
            Scoreboard.of(logs), Ledger.summary(Ledger.parse(dao.plan(slot, "ledger")?.json)),
            Growth.report(planJson(slot, "phist"), mine.associate { it.nameKey to it.name }, now),
            Weekly.delta(weeks), weeks, Growth.falling(planJson(slot, "phist"), mine, now)
        )
    }

    private suspend fun markSeen(slot: Int, keys: List<String>, now: Long) {
        val j = planJson(slot, "squadseen")
        for (k in keys) j.put(k, now)
        dao.putPlan(PlanEntity(slot, "squadseen", j.toString(), now))
        // quantas passadas leram cada nome: o nome mal lido (fantasma) aparece 1× e o verdadeiro dezenas (ver dedupeSlot)
        val r = planJson(slot, "squadreads")
        for (k in keys) r.put(k, r.optInt(k, 0) + 1)
        dao.putPlan(PlanEntity(slot, "squadreads", r.toString(), now))
    }

    /** Remove um jogador que não existe (ou já saiu) e impede que a leitura o recrie. */
    /** Corrige o nome de um jogador à mão (erro de leitura). A grafia antiga fica em `namefix` e passa a virar o nome certo nas próximas leituras. */
    suspend fun renamePlayer(slot: Int, oldKey: String, newName: String): Boolean {
        val nm = newName.trim()
        val nk = Txt.key(nm)
        if (nk.length < 2 || nm.none { it.isUpperCase() }) return false
        val old = dao.player(slot, "MY", oldKey) ?: return false
        val now = System.currentTimeMillis()
        if (nk != oldKey) {
            dao.deletePlayer(slot, "MY", oldKey)
            dao.putPlayer(old.copy(nameKey = nk, name = nm, updatedAt = now))
            val fix = planJson(slot, "namefix")
            fix.put(oldKey, nm)
            dao.putPlan(PlanEntity(slot, "namefix", fix.toString(), now))
        } else dao.putPlayer(old.copy(name = nm, updatedAt = now))
        learn(slot, "elenco", "Nome corrigido à mão: ${old.name} → $nm.", now)
        return true
    }

    suspend fun removePlayer(slot: Int, nameKey: String) {
        val now = System.currentTimeMillis()
        dao.deletePlayer(slot, "MY", nameKey)
        val j = planJson(slot, "squadblock")
        j.put(nameKey, now)
        dao.putPlan(PlanEntity(slot, "squadblock", j.toString(), now))
        learn(slot, "elenco", "Jogador removido à mão (não existe no elenco): $nameKey.", now)
    }

    /**
     * Depois de uma passada pelo elenco inteiro na sessão (>= 80% dos jogadores guardados e pelo menos 14 vistos),
     * quem não apareceu é fantasma de OCR ou já foi vendido: sai do elenco. Vale para todos os slots.
     */
    suspend fun reconcileSquads(since: Long): Int {
        var removed = 0
        val now = System.currentTimeMillis()
        for (slot in 1..4) {
            dedupeSlot(slot, now)
            val mine = dao.playersOf(slot).filter { it.owner == "MY" }
            if (mine.isEmpty()) continue
            val seenAt = planJson(slot, "squadseen")
            val seenNow = mine.filter { seenAt.optLong(it.nameKey, 0L) >= since }
            // Passada completa: 14+ jogadores vistos E pelo menos um de cada setor (o fim da lista, com os goleiros,
            // também foi visto). Fantasmas de OCR inflam o total guardado, então os 80% de antes nunca eram atingidos.
            val cats = seenNow.mapNotNull { it.cat }.toSet()
            val fullPass = seenNow.size >= 14 && cats.containsAll(listOf("ATA", "MEI", "DEF", "GOL"))
            if (!fullPass || seenNow.size < mine.size * 0.5) continue
            val notSeen = mine.filter { seenAt.optLong(it.nameKey, 0L) < since }
            // Sumir de UMA leitura completa não prova venda (o OCR perde linhas: Ba/N'Guessan no S2, 12/11). Só sai do elenco depois de
            // 2 leituras completas seguidas sem ele (plano `squadmiss`: nameKey → passadas completas sem o jogador).
            val miss = planJson(slot, "squadmiss")
            val missNew = org.json.JSONObject()
            for (p in notSeen) missNew.put(p.nameKey, miss.optInt(p.nameKey, 0) + 1)
            dao.putPlan(PlanEntity(slot, "squadmiss", missNew.toString(), now))
            val gone = notSeen.filter { missNew.optInt(it.nameKey, 0) >= 2 }
            if (gone.isEmpty() || gone.size > 20) continue
            val ledger = Ledger.parse(dao.plan(slot, "ledger")?.json)
            for (p in gone) {
                dao.deletePlayer(slot, "MY", p.nameKey)
                removed++
                // o livro de caixa só ganha a venda quando o jogador ESTAVA À VENDA (setas lidas); sem isso não dá para saber se foi venda ou erro de leitura
                if (p.forSale == true && !looksGarbled(p.name) && p.valueText != null) {
                    Ledger.add(ledger, Ledger.Entry("sell", p.nameKey, p.name, now, Money.parse(p.valueText), true, p.strength, p.age))
                }
            }
            dao.putPlan(PlanEntity(slot, "ledger", Ledger.toJson(ledger), now))
            val sold = gone.filter { it.forSale == true && !looksGarbled(it.name) }
            val rest = gone - sold.toSet()
            if (sold.isNotEmpty()) learn(slot, "elenco", "Vendido (estava à venda): ${sold.joinToString { it.name }}.", now)
            if (rest.isNotEmpty()) learn(slot, "elenco", "Elenco conferido: ${rest.joinToString { it.name }} sumiu de 2 leituras completas seguidas e saiu da lista (sem venda confirmada: não entra no caixa).", now)
        }
        return removed
    }

    /** true = joguei em casa; false = fora; null = não dá para saber. */
    private suspend fun mineSide(slot: Int, round: Int, homeTeam: String, awayTeam: String): Boolean? {
        val my = fieldMap(slot)[K.TEAM]?.value?.let { Txt.key(it) }
        if (my != null && my.length >= 3) {
            val h = homeTeam.isNotBlank() && Txt.sim(Txt.key(homeTeam), my) >= 0.8
            val a = awayTeam.isNotBlank() && Txt.sim(Txt.key(awayTeam), my) >= 0.8
            if (h != a) return h
        }
        return dao.match(slot, "L$round")?.home
    }

    private fun looksGarbled(name: String): Boolean =
        Regex("^[a-z][A-Z]").containsMatchIn(name) || Txt.letters(name) < 3

    private fun chooseName(old: String?, incoming: String): String {
        if (old == null) return incoming
        return if (looksGarbled(old) && !looksGarbled(incoming)) incoming else old
    }

    /**
     * Junta jogadores repetidos do meu elenco (o mesmo jogador lido com o nome diferente: "Frimpong"/"Erimpong",
     * "J. Timber"/"Timber"): mesma idade, mesma força e nome parecido. Fica o visto por último, com o melhor nome.
     */
    suspend fun dedupeSlot(slot: Int, now: Long = System.currentTimeMillis()): Int {
        val mine = dao.playersOf(slot).filter { it.owner == "MY" }
        if (mine.size < 2) return 0
        val seenAt = planJson(slot, "squadseen")
        val drop = SquadDedupe.duplicates(mine.map { SquadDedupe.P(it.nameKey, it.name, it.age, it.strength, it.cat, it.valueText, seenAt.optLong(it.nameKey, it.updatedAt)) })
        for ((bad, keep) in drop) {
            dao.deletePlayer(slot, "MY", bad)
            learn(slot, "elenco", "Jogador repetido removido: \"${mine.first { it.nameKey == bad }.name}\" era o mesmo que \"${mine.first { it.nameKey == keep }.name}\".", now)
        }
        val left = mine.filter { m -> drop.none { it.first == m.nameKey } }
        val ghosts = SquadDedupe.ghosts(left.map { SquadDedupe.P(it.nameKey, it.name, it.age, it.strength, it.cat, it.valueText, seenAt.optLong(it.nameKey, it.updatedAt)) })
        for ((bad, keep) in ghosts) {
            dao.deletePlayer(slot, "MY", bad)
            learn(slot, "elenco", "Nome mal lido removido: \"${left.first { it.nameKey == bad }.name}\" (agora lido como \"${left.first { it.nameKey == keep }.name}\").", now)
        }
        // fantasma por contagem de leituras: mesma idade, posição, força e valor + nome ainda parecido; o nome lido só 1× (ou nenhuma) contra o
        // outro lido 3+ vezes mais é erro de OCR ("Lapixdud" no lugar de "Capixaba"). Não depende do intervalo de 10 min das regras acima.
        val reads = planJson(slot, "squadreads")
        val gone = HashSet<String>(); for ((b, _) in ghosts) gone.add(b)
        val rest = left.filter { it.nameKey !in gone }
        var byReads = 0
        for (a in rest) {
            if (a.nameKey in gone) continue
            val ra = reads.optInt(a.nameKey, 0)
            if (ra > 1) continue
            val real = rest.firstOrNull { b ->
                b.nameKey != a.nameKey && b.nameKey !in gone && a.age != null && a.age == b.age && a.cat != null && a.cat == b.cat &&
                    a.strength != null && a.strength == b.strength && a.valueText != null && a.valueText == b.valueText &&
                    Txt.sim(a.nameKey, b.nameKey) >= 0.4 && reads.optInt(b.nameKey, 0) >= ra + 3
            } ?: continue
            gone.add(a.nameKey); byReads++
            dao.deletePlayer(slot, "MY", a.nameKey)
            learn(slot, "elenco", "Nome mal lido removido: \"${a.name}\" (lido poucas vezes; o jogador é \"${real.name}\").", now)
        }
        return drop.size + ghosts.size + byReads
    }

    /** Apaga jogadores "fantasma" (nome ilegível) que repetem outro jogador com mesma idade, força e posição. */
    private suspend fun dedupeSquads() {
        for (slot in 1..4) dedupeSlot(slot)
        for (slot in 1..4) {
            val mine = dao.playersOf(slot).filter { it.owner == "MY" }
            for (bad in mine) {
                if (!looksGarbled(bad.name)) continue
                val real = mine.firstOrNull {
                    it.nameKey != bad.nameKey && !looksGarbled(it.name) && it.age != null && it.age == bad.age &&
                        it.strength != null && it.strength == bad.strength && it.cat == bad.cat &&
                        (it.valueText == null || bad.valueText == null || it.valueText == bad.valueText)
                }
                if (real != null) dao.deletePlayer(slot, "MY", bad.nameKey)
            }
        }
    }

    /** Valores gravados por versões antigas ("Homem a homem", "Jogo de passe"...) viram o texto exato do OSM. */
    private suspend fun canonicalizeOptions() {
        for (slot in 1..4) {
            for (row in dao.fieldsOf(slot)) {
                val fixed = when (row.fkey) {
                    K.RIVAL_PLAN -> Osm.style(row.fvalue)
                    K.RIVAL_MARKING -> Osm.marking(row.fvalue)
                    K.RIVAL_TACKLE -> Osm.tackle(row.fvalue)
                    K.RIVAL_FORMATION, K.MY_FORMATION -> Formations.parse(row.fvalue)?.also { rememberFormation(it) }
                    else -> null
                } ?: continue
                if (fixed != row.fvalue) dao.putField(row.copy(fvalue = fixed))
            }
        }
    }

    /** Táticas guardadas por versões antigas (sem rodada) não valem mais e não podem aparecer como "prontas". */
    private suspend fun dropStaleTactics() {
        for (slot in 1..4) {
            val p = dao.plan(slot, "tactic") ?: continue
            val has = try {
                val j = org.json.JSONObject(p.json)
                j.optInt("forRound", -1) > 0 || (!j.isNull("matchAt") && j.optLong("matchAt") > 0L)
            } catch (e: Exception) { false }
            if (!has) dao.deletePlan(slot, "tactic")
        }
    }

    /** Remove dados importados do app antigo (eram inválidos). Os dados novos só vêm de leitura real. */
    suspend fun purgeLegacy(): Int {
        val prefs = ctx.getSharedPreferences("collector_runtime", Context.MODE_PRIVATE)
        val removed = dao.deleteLegacyFields()
        prefs.edit().putBoolean("legacy_imported", true).apply()
        try {
            // Versões antigas marcavam "treinando" pela camisa laranja do plantel (errado). Uma vez: zera os slots que
            // ainda não leram a tela de Treinamento (a leitura dela refaz tudo certo).
            if (!prefs.getBoolean("train_reset_v1", false)) {
                for (slot in 1..4) {
                    if (dao.plan(slot, "trainscreen") != null) continue
                    for (p in dao.playersOf(slot)) if (p.training != null) dao.putPlayer(p.copy(training = null))
                    dao.deletePlan(slot, "trainov")
                }
                prefs.edit().putBoolean("train_reset_v1", true).apply()
            }
            // Árbitro em 5 níveis: o "Médio" antigo juntava amarelo e laranja (a leitura errada que dava Cuidadoso com
            // árbitro amarelo). Uma vez: apaga o árbitro lido no formato antigo e a tática feita com ele.
            if (!prefs.getBoolean("referee5_v1", false)) {
                for (slot in 1..4) {
                    val ref = dao.fieldsOf(slot).firstOrNull { it.fkey == K.REFEREE } ?: continue
                    if (Referee.norm(ref.fvalue) == null || ref.fvalue == "Médio") {
                        dao.deleteField(slot, K.REFEREE)
                        dao.deletePlan(slot, "tactic")
                    } else if (ref.fvalue == "Rigoroso" && ref.source != "manual") {
                        // o vermelho antigo chamava "Rigoroso"; agora vermelho é "Muito rigoroso" (mesmo desarme)
                        dao.putField(ref.copy(fvalue = "Muito rigoroso"))
                    }
                }
                prefs.edit().putBoolean("referee5_v1", true).apply()
            }
            dedupeSquads()
            dropStaleTactics()
            canonicalizeOptions()
            for (slot in 1..4) {
                dao.deleteCupCards(slot)
                val st = dao.fieldsOf(slot).firstOrNull { it.fkey == K.MY_STADIUM }
                if (st != null && !st.fvalue.contains("Capacidade ")) dao.deleteField(slot, K.MY_STADIUM)
            }
        } catch (e: Exception) {
            Diag.lastError = "Limpeza: " + (e.message ?: e.javaClass.simpleName)
        }
        return removed
    }
}
