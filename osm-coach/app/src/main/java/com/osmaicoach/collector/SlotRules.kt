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
        /** Quantos últimos colocados são rebaixados (0 = a regra não existe). */
        val relegated: Int = 0
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
            relegated = num(t, "ultimos? (\\d{1,2}) (?:treinadores|times|clubes|equipas?)? ?(?:sao |serao |e )?rebaixad")
                ?: num(t, "(\\d{1,2}) ultimos (?:\\w+ )?(?:sao |serao )?rebaixad")
                ?: if (Regex("ultimo (?:treinador|colocado|time|clube)? ?(?:e|sera) rebaixad").containsMatchIn(t)) 1 else 0
        )
    }

    /** Mesma bandeira (assinaturas 3×3 com até 2 células diferentes). */
    fun sameFlag(a: String, b: String): Boolean = a.length == 9 && b.length == 9 && a.indices.count { a[it] != b[it] } <= 2

    /** Dados lidos do slot que as regras usam. */
    data class Extra(
        /** Jogadores da nacionalidade da liga (nome, jogos); null = bandeira da liga ainda não escolhida. */
        val natives: List<Pair<String, Int>>? = null,
        val flagsKnown: Boolean = false,
        val pos: Int? = null,
        val teams: Int? = null,
        /** Pontos de vantagem sobre o primeiro time rebaixado (negativo = dentro da zona). */
        val gapPts: Int? = null,
        val valueNow: Double? = null,
        val valueBase: Double? = null,
        /** Jogadores da nacionalidade da liga com os jogos: do cartão grande (`fromCard`), mínimo contado nos relatórios, ou null = ainda não lido. */
        val nativeInfo: List<NativeP>? = null
    )
    data class NativeP(val name: String, val games: Int?, val fromCard: Boolean)

    /** Grupos de bandeira do elenco: (assinatura, nomes) do maior para o menor. */
    suspend fun flagGroups(repo: Repo, slot: Int): List<Pair<String, List<String>>> {
        val nat = repo.dao.plan(slot, "nations")?.json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyList()
        val names = repo.playersOf(slot).filter { it.owner == "MY" }.associateBy { it.nameKey }
        val by = LinkedHashMap<String, MutableList<String>>()
        for (k in nat.keys()) { val n = names[k]?.name ?: continue; by.getOrPut(nat.optString(k)) { ArrayList() }.add(n) }
        return by.entries.sortedByDescending { it.value.size }.map { it.key to it.value }
    }

    /** Escolhida pelo usuário; senão a sugerida pelo nome da liga (Atlas) — ex.: "Alemanha, 3ª Divisão" → jogadores "Alemão". */
    suspend fun leagueFlag(repo: Repo, slot: Int): String? =
        repo.dao.plan(slot, "leagueflag")?.json?.let { runCatching { JSONObject(it).optString("sig") }.getOrNull() }?.takeIf { it.length == 9 }
            ?: runCatching { suggestLeagueFlag(repo, slot) }.getOrNull()

    /** País de cada jogador do meu elenco (nome do Atlas pela bandeira); null = Atlas ainda não conhece. */
    suspend fun playerCountries(repo: Repo, slot: Int, filesDir: java.io.File? = null): Map<String, String?> {
        val rich = repo.dao.plan(slot, "natrich")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
        val imgs = repo.dao.plan(slot, "natimg3")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
        val isos = repo.dao.plan(slot, "natiso4")?.json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val atlas = Atlas.load(repo, Atlas.Kind.NAT)
        val manual = FlagManual.all(repo)
        val out = HashMap<String, String?>()
        for ((k, iso) in manual) out[k] = atlasNameOf(atlas, iso) ?: Flags.countryName(iso)   // escolhida à mão: manda sobre tudo
        var changed = false
        if (imgs != null) for (k in imgs.keys()) {
            if (k in manual) continue
            // 1º o país já identificado (fica guardado por jogador); 2º identifica agora (bandeiras do Flagpedia + Atlas); 3º só o Atlas pela miniatura
            var iso = isos.optString(k).takeIf { it.isNotEmpty() }?.let { v -> FlagIdent.canon(v).also { c -> if (c != v) { isos.put(k, c); changed = true } } }   // Ilha Bouvet → Noruega
            if (iso == null && filesDir != null) {
                iso = FlagIdent.identify(filesDir, imgs.optString(k), atlas)
                if (iso != null) { isos.put(k, iso); changed = true }
            }
            // sem as bandeiras de referência ainda: Atlas por cor nomeada (não guarda), depois o Atlas por miniatura
            val shown = iso ?: FlagIdent.identifyAtlasOnly(imgs.optString(k), atlas)
            out[k] = shown?.let { atlasNameOf(atlas, it) ?: Flags.countryName(it) } ?: Atlas.nameOfThumb(repo, imgs.optString(k))
        }
        if (changed) repo.dao.putPlan(PlanEntity(slot, "natiso4", isos.toString(), System.currentTimeMillis()))
        if (rich != null) for (k in rich.keys()) if (out[k] == null && k !in manual) out[k] = Atlas.nameOf(repo, rich.optString(k))
        return out
    }

    /** Nome do Atlas (gentílico) de um ISO, para o resto do app que compara pelo nome do Atlas; null se o Atlas não tem esse país. */
    private fun atlasNameOf(atlas: Map<String, String>, iso: String): String? =
        atlas.keys.filter { Flags.iso(it) == iso }.minByOrNull { it.length }

    data class FlagInfo(val name: String?, val rich: String?, val img: String? = null)

    /** Por grupo de bandeira (assinatura 3×3): nome do país (maioria dos jogadores) e uma assinatura rica para desenhar. */
    suspend fun flagInfo(repo: Repo, slot: Int): Map<String, FlagInfo> {
        val nat = repo.dao.plan(slot, "nations")?.json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        val rich = repo.dao.plan(slot, "natrich")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
        val imgs = repo.dao.plan(slot, "natimg3")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
        val sampleImg = HashMap<String, String>()
        val countries = playerCountries(repo, slot)
        val votes = HashMap<String, HashMap<String, Int>>()
        val sample = HashMap<String, String>()
        for (k in nat.keys()) {
            val sig = nat.optString(k)
            countries[k]?.let { n -> votes.getOrPut(sig) { HashMap() }.merge(n, 1, Int::plus) }
            rich?.optString(k)?.takeIf { it.isNotEmpty() }?.let { sample.putIfAbsent(sig, it) }
            imgs?.optString(k)?.takeIf { it.isNotEmpty() }?.let { sampleImg.putIfAbsent(sig, it) }
        }
        val out = HashMap<String, FlagInfo>()
        for (k in nat.keys()) {
            val sig = nat.optString(k)
            if (out.containsKey(sig)) continue
            out[sig] = FlagInfo(votes[sig]?.maxByOrNull { it.value }?.key, sample[sig], sampleImg[sig])
        }
        return out
    }

    /** Bandeira da liga deduzida do nome da liga (Atlas) e do elenco; null se não deu para casar. */
    suspend fun suggestLeagueFlag(repo: Repo, slot: Int): String? {
        val f = repo.fieldMap(slot)
        val comp = f[K.COMPETITION]?.value ?: f[K.HUB_TITLE]?.value ?: return null
        val nat = Atlas.nationalityForCountry(repo, comp.substringBefore(',').trim()) ?: return null
        val infos = flagInfo(repo, slot)
        return infos.entries.filter { e -> e.value.name?.let { it == nat || it.startsWith(nat) } == true }
            .let { l -> l.firstOrNull() }?.key?.takeIf { it.length == 9 }
    }

    suspend fun setLeagueFlag(repo: Repo, slot: Int, sig: String?, now: Long) =
        repo.dao.putPlan(PlanEntity(slot, "leagueflag", if (sig == null) "{}" else JSONObject().put("sig", sig).toString(), now))

    /** Jogos de cada jogador do MEU time, contados nos relatórios de resultado lidos (mínimo: o relatório pode não listar todos). */
    suspend fun appearances(repo: Repo, slot: Int): Map<String, Int> {
        val mine = repo.playersOf(slot).filter { it.owner == "MY" }
        val out = HashMap<String, Int>()
        for (pl in repo.dao.plansOf(slot)) {
            if (!pl.kind.startsWith("mr_R")) continue
            val j = runCatching { JSONObject(pl.json) }.getOrNull() ?: continue
            if (!j.has("mineHome")) continue
            val arr = j.optJSONArray(if (j.optBoolean("mineHome")) "rh" else "ra") ?: continue
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val nm = arr.optJSONArray(i)?.optString(0) ?: continue
                val k = Txt.key(nm)
                mine.firstOrNull { it.nameKey == k || Txt.sim(it.nameKey, k) >= 0.85 }?.let { seen.add(it.nameKey) }
            }
            for (k in seen) out[k] = (out[k] ?: 0) + 1
        }
        return out
    }

    /** Monta o que as regras precisam (nacionalidade, posição na tabela, valor do time). */
    suspend fun gather(repo: Repo, slot: Int, r: Rules, f: Map<String, StoredField>, now: Long): Extra {
        val mine = repo.playersOf(slot).filter { it.owner == "MY" }
        var natives: List<Pair<String, Int>>? = null
        var nativeInfo: List<NativeP>? = null
        val groups = flagGroups(repo, slot)
        if (r.natCount != null) {
            val lf = leagueFlag(repo, slot)
            if (lf != null) {
                val apps = appearances(repo, slot)
                val nat = repo.dao.plan(slot, "nations")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
                natives = mine.filter { nat?.optString(it.nameKey)?.let { s -> s.isNotEmpty() && sameFlag(s, lf) } == true }
                    .map { it.name to (apps[it.nameKey] ?: 0) }.sortedByDescending { it.second }
                val card = CardGames.load(repo, slot)
                nativeInfo = mine.filter { nat?.optString(it.nameKey)?.let { s -> s.isNotEmpty() && sameFlag(s, lf) } == true }.map { pl ->
                    val c = card[pl.nameKey]; val a = apps[pl.nameKey] ?: 0
                    NativeP(pl.name, if (c != null) maxOf(c, a) else a.takeIf { it > 0 }, c != null)
                }
                natives = nativeInfo.map { it.name to (it.games ?: 0) }
            }
        }
        val pos = f[K.LEAGUE_POS]?.value?.toIntOrNull()
        val total = f[K.ROUND_TOTAL]?.value?.toIntOrNull()
        val st = League.table(repo.dao.plan(slot, "league")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }, "st")
        val teams = st.size.takeIf { it >= 6 } ?: total?.let { it / 2 + 1 }
        var gap: Int? = null
        if (r.relegated > 0 && pos != null && teams != null && st.isNotEmpty()) {
            val me = st.firstOrNull { it.rank == pos }
            val safe = st.firstOrNull { it.rank == teams - r.relegated }
            val firstOut = st.firstOrNull { it.rank == teams - r.relegated + 1 }
            if (me?.pts != null && safe?.pts != null && firstOut?.pts != null) gap = if (pos > teams - r.relegated) me.pts - safe.pts else me.pts - firstOut.pts
        }
        // valor do time: 1ª leitura vira a base da meta (guardada no slot)
        val now1 = Money.parse(f[K.MY_VALUE]?.value)
        var base = repo.dao.plan(slot, "valuebase")?.json?.let { runCatching { JSONObject(it).optDouble("v", 0.0) }.getOrNull() }?.takeIf { it > 0 }
        if (base == null && now1 != null && r.evolveX != null) {
            repo.dao.putPlan(PlanEntity(slot, "valuebase", JSONObject().put("v", now1).put("at", now).toString(), now))
            base = now1
        }
        return Extra(natives, groups.isNotEmpty(), pos, teams, gap, now1, base, nativeInfo)
    }

    /**
     * Regras valem SÓ para a liga em que foram digitadas (mudam a cada liga nova): gravadas com o nome da competição; se a competição do slot mudou,
     * as regras da liga anterior são apagadas (e `Repo.archiveSeason` também as apaga ao arquivar a liga).
     */
    suspend fun load(repo: Repo, slot: Int): Rules? {
        val j = repo.dao.plan(slot, "rules")?.json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val txt = j.optString("text")
        if (txt.isBlank()) return null
        val savedFor = j.optString("comp").takeIf { it.isNotBlank() }
        val cur = repo.fieldMap(slot)[K.COMPETITION]?.value?.takeIf { FieldMerge.known(it) }
        if (savedFor != null && cur != null && Txt.sim(Txt.key(savedFor), Txt.key(cur)) < 0.8) {
            repo.dao.deletePlan(slot, "rules")
            return null
        }
        return parse(txt)
    }

    suspend fun save(repo: Repo, slot: Int, text: String, now: Long) {
        val comp = repo.fieldMap(slot)[K.COMPETITION]?.value?.takeIf { FieldMerge.known(it) }
        val body = if (text.isBlank()) "{}" else JSONObject().put("text", text.trim()).apply { comp?.let { put("comp", it) } }.toString()
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
        if (r.relegated > 0) add("Rebaixa${if (r.relegated == 1) " o último" else "m os últimos ${r.relegated}"}")
    }

    enum class St { OK, BAD, UNKNOWN }
    /** Uma regra principal com a situação AGORA: ✅ cumprida, ❌ precisa cumprir, ❔ sem dado para saber. */
    data class Check(val text: String, val st: St, val detail: String? = null)

    /** Situação de cada regra reconhecida (só as regras em negrito/numéricas; o texto colado não é repetido). */
    fun checks(r: Rules, players: List<PlayerEntity>, round: Int?, roundTotal: Int?, lastReadAt: Long?, now: Long, x: Extra = Extra()): List<Check> {
        val out = ArrayList<Check>()
        val mine = players.filter { it.owner == "MY" }
        fun nm(p: PlayerEntity) = "${p.name} (${p.strength})"
        r.maxBuy?.let { out.add(Check("Comprar: até over $it", St.OK, "o app só sugere compras até esse over")) }
        r.maxTrain?.let { tr ->
            val bad = mine.filter { (it.training == true && (it.strength ?: 0) >= tr - 1) || (it.training != true && (it.strength ?: 0) >= tr - 1) }
            out.add(Check("Treino: até over $tr", if (bad.isEmpty()) St.OK else St.BAD,
                if (bad.isEmpty()) null else bad.take(3).joinToString { nm(it) } + (if (bad.any { it.training == true }) " — tire do treino" else " — não coloque no treino")))
        }
        r.maxStar?.let { star ->
            val bad = mine.filter { (it.strength ?: 0) >= star }
            out.add(Check("Craque: até over $star", if (bad.isEmpty()) St.OK else St.BAD, if (bad.isEmpty()) null else bad.take(3).joinToString { nm(it) } + " no limite/acima"))
        }
        r.maxSquad?.let { m ->
            out.add(Check("Elenco: até $m jogadores", if (mine.size <= m) St.OK else St.BAD, "${mine.size}/$m"))
        }
        if (r.noTransferSecondHalf) {
            val late = round != null && roundTotal != null && round > roundTotal / 2
            out.add(Check("Sem transferências no 2º turno", if (late) St.BAD else St.OK, if (late) "estamos no 2º turno: não compre nem liste" else "1º turno: transferências liberadas"))
        }
        r.maxAwayDays?.let { d ->
            if (lastReadAt == null) out.add(Check("Entrar na liga a cada $d dias", St.UNKNOWN, "sem leitura recente"))
            else {
                val h = (now - lastReadAt) / 3_600_000L
                val bad = h >= (d - 1) * 24L
                out.add(Check("Entrar na liga a cada $d dias", if (bad) St.BAD else St.OK, "última leitura há ${h / 24}d ${h % 24}h" + (if (bad) " — entre hoje" else "")))
            }
        }
        if (r.natCount != null) {
            val label = "${r.natCount} jogadores da nacionalidade da liga" + (r.natGames?.let { ", $it jogos cada" } ?: "")
            val info = x.nativeInfo
            val nat = x.natives
            if (nat == null) out.add(Check(label, St.UNKNOWN, "escolha a bandeira da liga no cartão de regras do app"))
            else {
                val need = r.natGames
                val rows = info ?: nat.map { NativeP(it.first, it.second.takeIf { g -> g > 0 }, false) }
                val ready = rows.count { need == null || (it.games ?: 0) >= need }
                val lines = ArrayList<String>()
                lines.add("$ready/${r.natCount} cumprindo")
                if (rows.size < r.natCount) lines.add("só ${rows.size} jogador(es) da nacionalidade no elenco: faltam ${r.natCount - rows.size}")
                // quem ainda não teve os jogos lidos no cartão grande vem primeiro: é o que o usuário precisa abrir
                for (p in rows.sortedBy { if (it.fromCard) 1 else 0 }.take(10)) {
                    val g = p.games
                    lines.add(when {
                        need == null -> "• ${p.name}"
                        g != null && g >= need -> "✅ ${p.name} — $g jogos"
                        g != null && p.fromCard -> "❌ ${p.name} — $g/$need jogos"
                        g != null -> "👉 ${p.name} — pelo menos $g jogos; abra o cartão grande para confirmar"
                        else -> "👉 ${p.name} — abra o cartão grande (jogos não lidos)"
                    })
                }
                out.add(Check(label, if (ready >= r.natCount) St.OK else St.BAD, lines.joinToString("\n")))
            }
        }
        r.evolveX?.let { e ->
            val now2 = x.valueNow; val base = x.valueBase
            if (now2 == null || base == null || base <= 0.0) out.add(Check("Evoluir ${e}× o valor inicial", St.UNKNOWN, "valor do elenco ainda não lido"))
            else { val k = now2 / base; out.add(Check("Evoluir ${e}× o valor inicial", if (k >= e) St.OK else St.BAD, "agora ${"%.1f".format(k).replace('.', ',')}×")) }
        }
        if (r.relegated > 0) {
            val pos = x.pos; val teams = x.teams
            if (pos == null || teams == null) out.add(Check("Rebaixa${if (r.relegated == 1) " o último" else "m os últimos ${r.relegated}"}", St.UNKNOWN, "posição não lida"))
            else out.add(Check("Rebaixa${if (r.relegated == 1) " o último" else "m os últimos ${r.relegated}"}", if (pos <= teams - r.relegated) St.OK else St.BAD, "${pos}º de $teams" + (if (pos > teams - r.relegated) " — zona de rebaixamento" else "")))
        }
        return out
    }

    /** Texto para o prompt da IA: obrigatório respeitar (compra, treino, craque, elenco, escalação). */
    fun promptText(r: Rules): String =
        "REGRAS ESPECIAIS DESTA LIGA (obrigatórias; valem mais que as dicas gerais; não sugira nada que as quebre):\n" + r.text.take(1800)

    /** Linha extra para a IA: cota de nacionalidade a cumprir, risco de rebaixamento e meta de valor. */
    fun promptExtra(r: Rules, x: Extra): String {
        val sb = StringBuilder()
        if (r.natCount != null && x.natives != null) {
            val low = x.natives.filter { r.natGames != null && it.second < r.natGames }
            sb.append("Cota de nacionalidade: ${x.natives.size}/${r.natCount} jogadores da liga. ")
            if (low.isNotEmpty()) sb.append("Precisam de jogos (escale no 11 se possível): " + low.joinToString { "${it.first} (${it.second}/${r.natGames})" } + ". ")
        }
        if (r.relegated > 0 && x.pos != null && x.teams != null && x.pos >= x.teams - r.relegated - 1) sb.append("RISCO DE REBAIXAMENTO (${x.pos}º de ${x.teams}, caem ${r.relegated}): busque vitória. ")
        return sb.toString().trim()
    }

    /** Dicas por slot. `late` = 2º turno (rodada > metade). Pendências usam ícones de ação; informação usa 📜. */
    fun tips(r: Rules, players: List<PlayerEntity>, xi: Set<String>, round: Int?, roundTotal: Int?, lastReadAt: Long?, now: Long, x: Extra = Extra()): List<Playbook.Tip> {
        val out = ArrayList<Playbook.Tip>()
        val mine = players.filter { it.owner == "MY" }
        fun nm(p: PlayerEntity) = "${p.name} (${p.strength})"
        val tr = r.maxTrain
        if (tr != null) {
            fun st(p: PlayerEntity) = p.strength ?: 0
            // VEEMENTE (🚫): treinando no limite ou a 1 ponto dele → tirar AGORA; no limite e parado → nunca colocar no treino; a 1 ponto → não pode passar
            val trainingNow = mine.filter { it.training == true && st(it) >= tr - 1 }
            if (trainingNow.isNotEmpty()) out.add(Tip("🚨", "🚫 REGRA DA LIGA — TIRE DO TREINO AGORA (limite over $tr): " + trainingNow.take(4).joinToString { nm(it) },
                "A liga PROÍBE passar de over $tr" + (r.maxStar?.let { " (só o craque vai até $it)" } ?: "") + ". Quem está com ${tr - 1} ou $tr e treinando vai estourar o limite: pare o treino e use o treinador em outro jogador.", 100, 2))
            val atCap = mine.filter { it.training != true && st(it) >= tr }
            if (atCap.isNotEmpty()) out.add(Tip("🚨", "🚫 REGRA DA LIGA — NÃO COLOQUE NO TREINO DE MANEIRA NENHUMA (limite over $tr): " + atCap.take(4).joinToString { nm(it) },
                "Já estão no limite ($tr): qualquer treino passa do que a liga permite. Deixe esses jogadores fora do treino.", 100, 2))
            val oneAway = mine.filter { it.training != true && st(it) == tr - 1 }
            if (oneAway.isNotEmpty()) out.add(Tip("🚨", "🚫 REGRA DA LIGA — NÃO PODE PASSAR DE $tr: " + oneAway.take(4).joinToString { nm(it) },
                "Falta 1 ponto para o limite ($tr). Se treinar, passa. Treine outros jogadores.", 99, 2))
            val near = mine.filter { it.training != true && st(it) in (tr - 3)..(tr - 2) }
            if (near.isNotEmpty()) out.add(Tip("📜", "Perto do limite de treino ($tr): " + near.take(4).joinToString { nm(it) }, "Mais alguns pontos e chegam ao que a liga permite. Treine outros jogadores quando chegarem perto.", 40, 2))
        }
        val star = r.maxStar
        if (star != null) {
            val over = mine.filter { (it.strength ?: 0) > star }
            if (over.isNotEmpty()) out.add(Tip("🚨", "🚫 REGRA DA LIGA — ACIMA DO LIMITE DE CRAQUE ($star): " + over.take(4).joinToString { nm(it) }, "Pela regra da liga, jogador acima do over permitido é venda instantânea (permitida para este caso).", 100, 6))
            val nearStar = mine.filter { (it.strength ?: 0) in (star - 1)..star }
            if (nearStar.isNotEmpty()) out.add(Tip("🚨", "🚫 REGRA DA LIGA — CRAQUE NO LIMITE ($star), NÃO PODE PASSAR: " + nearStar.take(4).joinToString { nm(it) }, "Não treine nem evolua esse jogador: a liga proíbe over acima de $star para o craque.", 99, 2))
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
        if (r.natCount != null) {
            val need = r.natCount; val games = r.natGames
            val nat = x.natives
            if (nat == null) {
                out.add(Tip("📜", "Regra: $need jogador(es) da nacionalidade da liga" + (games?.let { ", $it jogos cada" } ?: ""), if (x.flagsKnown) "Escolha no cartão \"Regras especiais\" qual bandeira é a da liga para eu contar." else "Abra o Plantel (rolando a lista) para eu ler a bandeira de cada jogador; depois escolha a da liga no cartão de regras.", 70, 0))
            } else {
                val ready = nat.count { games == null || it.second >= games }
                val low = nat.filter { games != null && it.second < games }
                if (nat.size < need) out.add(Tip("🚨", "Faltam ${need - nat.size} jogador(es) da nacionalidade da liga (tem ${nat.size}/$need)", "A regra exige $need" + (games?.let { ", cada um com $it jogos" } ?: "") + ". Compre (até over ${r.maxBuy ?: "permitido"}) ou use o Olheiro/mercado filtrando pela nacionalidade.", 94, 6))
                else if (low.isNotEmpty()) out.add(Tip("📝", "Escale: " + low.take(4).joinToString { "${it.first} (${it.second}/$games jogos)" }, "A regra exige $need jogadores da nacionalidade com $games jogos cada ($ready de $need prontos). Ponha-os no 11 sempre que a tática permitir (os jogos contados vêm dos relatórios lidos: podem ser mais).", 88, 4))
                else out.add(Tip("📜", "Cota da nacionalidade cumprida ($ready/$need com ${games ?: 0}+ jogos)", "Mantenha esses jogadores no elenco até o fim.", 25, 4))
            }
        }
        val vb = x.valueBase; val vn = x.valueNow
        if (r.evolveX != null && vb != null && vn != null) {
            val goal = vb * r.evolveX
            val frac = if (round != null && roundTotal != null && roundTotal > 0) round.toDouble() / roundTotal else null
            val expected = frac?.let { vb + (goal - vb) * it }
            val behind = expected != null && vn < expected * 0.92
            out.add(Tip(if (behind) "📈" else "📜", "Meta de valor: ${Playbook.money(vn)} de ${Playbook.money(goal)} (${r.evolveX}× a base ${Playbook.money(vb)})" + (if (behind) " — atrasado" else ""),
                if (behind) "Pelo ritmo da temporada já deveria estar perto de ${Playbook.money(expected!!)}. Treine jovens com mais potencial, não deixe treinador parado e valorize o elenco." else "No ritmo da meta. A base é o primeiro valor que o app leu neste slot.", if (behind) 80 else 30, 0))
        } else if (r.evolveX != null) out.add(Tip("📜", "Meta da liga: valor do time ${r.evolveX}× o inicial", "Abra o Plantel (topo) para eu registrar o valor do elenco como base da meta.", 30, 0))
        if (r.relegated > 0 && x.pos != null && x.teams != null) {
            val zoneStart = x.teams - r.relegated + 1
            val inZone = x.pos >= zoneStart
            val close = !inZone && (x.pos >= zoneStart - 2 || (x.gapPts != null && x.gapPts <= 3))
            val gapTxt = x.gapPts?.let { g -> if (inZone) " Você está ${-g} pt(s) atrás do último lugar seguro." else " Vantagem de $g pt(s) sobre a zona." } ?: ""
            if (inZone) out.add(Tip("🚨", "ZONA DE REBAIXAMENTO: ${x.pos}º de ${x.teams} (rebaixam os últimos ${r.relegated})", "Cada ponto conta: priorize vitória, escale o melhor 11 e não deixe sessão de treino ou lesão te enfraquecer.$gapTxt", 99, 4))
            else if (close) out.add(Tip("🚨", "Perto do rebaixamento: ${x.pos}º de ${x.teams} (caem os últimos ${r.relegated})", "Não relaxe: um tropeço te coloca na zona.$gapTxt", 90, 4))
            else out.add(Tip("📜", "Fora da zona de rebaixamento (${x.pos}º de ${x.teams})", "A liga rebaixa os últimos ${r.relegated}.$gapTxt", 20, 4))
        }
        return out.map { it }
    }

    private fun Tip(icon: String, title: String, why: String, prio: Int, tab: Int) = Playbook.Tip(icon, title, why, prio, tab)
}
