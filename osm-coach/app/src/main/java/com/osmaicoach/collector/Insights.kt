package com.osmaicoach.collector

import org.json.JSONObject

/**
 * Explicações em cima do que o app JÁ decidiu/aprendeu (nada novo é inventado): "Por quê" da tática (fatores, fontes da web, faixas,
 * alternativa, riscos, grau de evidência), painel de aprendizado e revisão do último jogo em duas linhas.
 * "Evidência" não é probabilidade de vitória: conta jogos seus com a mesma escolha e quantas fontes concordam.
 */
object Why {
    data class View(
        val head: String, val factors: List<String>, val web: List<Pair<Boolean, String>>, val bands: List<Pair<Boolean, String>>,
        val alt: String?, val risks: List<String>, val evidence: String
    )

    private fun boolHome(v: String?): Boolean? = when (v) { "Casa" -> true; "Fora" -> false; else -> null }

    /** Combinações que a fonte da web e o osmtacticsapp marcam como incoerentes (ritmo×alas, pressão×estilo etc.). */
    fun coherenceRisks(t: JSONObject): List<String> {
        val st = Osm.style(t.optString("playStyle")) ?: t.optString("playStyle")
        val pr = t.optInt("pressure"); val me = t.optInt("mentality"); val te = t.optInt("tempo")
        val out = ArrayList<String>()
        if (st == "Jogar pelas alas" && te >= 80) out.add("Ritmo muito alto com Jogar pelas alas: os cruzamentos saem antes de os pontas chegarem.")
        if (pr >= 85 && me >= 70) out.add("Pressão altíssima com estilo ofensivo: se perder a bola, ninguém fica atrás dela.")
        if (st == "Jogo de passe" && te > 65) out.add("Jogo de passe pede ritmo ~40–60; ritmo alto atrapalha a circulação.")
        if (st == "Contra-ataque" && (me > 50 || te < 60)) out.add("Contra-ataque rende com estilo baixo (30–45) e ritmo alto (65–85); fora disso a saída em velocidade perde força.")
        if (st == "Contra-ataque" && t.optString("advDef").contains("atacantes", true)) out.add("Contra-ataque com defesas atacantes abre espaço nas costas.")
        val lines = Formations.lines(Formations.canonical(t.optString("formation")) ?: "")
        if (lines.isNotEmpty() && lines.first() <= 3) out.add("Só 3 defensores: exposição nas alas contra pontas rápidos.")
        if (lines.isNotEmpty() && lines.first() >= 5 && me >= 65) out.add("Linha de 5 com estilo alto: você defende fundo mas empurra o time para frente; confira se faz sentido.")
        return out
    }

    private fun combo(logs: List<JSONObject>, formation: String, style: String): List<JSONObject> =
        logs.filter {
            it.optString("result") in listOf("V", "E", "D") && Formations.base(it.optString("formation")) == Formations.base(formation) &&
                (Osm.style(it.optString("playStyle")) ?: it.optString("playStyle")) == style
        }

    suspend fun build(repo: Repo, slot: Int): View? {
        val tp = repo.dao.plan(slot, "tactic") ?: return null
        val j = runCatching { JSONObject(tp.json) }.getOrNull() ?: return null
        val f = repo.fieldMap(slot)
        val now = System.currentTimeMillis()
        if (!Director.tacticValid(j, tp.at, f, now)) return null
        val formation = j.optString("formation")
        val style = Osm.style(j.optString("playStyle")) ?: j.optString("playStyle")
        fun v(k: String) = f[k]?.value?.takeIf { FieldMerge.known(it) }
        val my = v(K.MY_STRENGTH)?.toIntOrNull(); val rv = v(K.RIVAL_STRENGTH)?.toIntOrNull()
        val diff = if (j.isNull("diff")) (if (my != null && rv != null) my - rv else null) else j.optInt("diff")
        val home = boolHome(v(K.HOME))
        val factors = ArrayList<String>()
        if (my != null && rv != null) {
            val d = my - rv
            factors.add("Força $my × $rv (${if (d >= 0) "+" else ""}$d): " + if (d >= 5) "você é favorito" else if (d <= -5) "você é azarão" else "jogo parelho")
        }
        home?.let { factors.add(if (it) "Jogo em casa" else "Jogo fora de casa (controles mais contidos)") }
        v(K.REFEREE)?.let { r -> factors.add("Árbitro $r: desarme permitido até ${Referee.tackle(r) ?: "Normal"}; tática usa ${j.optString("tackle")}") }
        val rf = v(K.RIVAL_FORMATION); val rp = v(K.RIVAL_PLAN)
        if (rf != null) factors.add("Rival ${if (v(K.RIVAL_HUMAN) == "Sim") "humano" else "CPU"}: $rf" + (rp?.let { " • $it" } ?: ""))
        if (v(K.MY_FORM) != null || v(K.RIVAL_FORM) != null) factors.add("Forma recente: você ${v(K.MY_FORM) ?: "?"} • rival ${v(K.RIVAL_FORM) ?: "?"}")

        val logs = Director.allLogs(repo)
        val mine = combo(logs, formation, style)
        val vs = mine.count { it.optString("result") == "V" }; val es = mine.count { it.optString("result") == "E" }; val ds = mine.count { it.optString("result") == "D" }
        if (mine.isNotEmpty()) factors.add("Seus jogos com ${Formations.base(formation)} • $style: ${vs}V ${es}E ${ds}D (${mine.size})")
        else factors.add("Você ainda não jogou ${Formations.base(formation)} • $style: sem histórico seu para essa escolha")

        // fontes da web para a situação
        val cands = Meta.remoteCandidates(diff ?: 0, home, rf, MetaRefresh.remote(repo))
        val web = cands.map { c ->
            val same = Formations.base(c.formation) == Formations.base(formation) && c.style == style
            Pair(same, (if (same) "Concorda: " else "Sugere outra: ") + "${c.formation} • ${c.style} (pressão ${c.pressure} / estilo ${c.mentality} / ritmo ${c.tempo}) — ${c.label.removePrefix("comunidade (internet, ").removeSuffix(")")}")
        }
        // faixas por formação (osmcoach / osmtacticsapp)
        val bandsJ = WebKnow.bands(repo)?.optJSONObject(Formations.canonical(formation) ?: formation)
        val bands = ArrayList<Pair<Boolean, String>>()
        if (bandsJ != null) {
            for ((name, key, value) in listOf(Triple("Pressão", "pressure", j.optInt("pressure")), Triple("Estilo", "mentality", j.optInt("mentality")), Triple("Ritmo", "tempo", j.optInt("tempo")))) {
                val a = bandsJ.optJSONArray(key) ?: continue
                val lo = a.optInt(0); val hi = a.optInt(1)
                bands.add(Pair(value in lo..hi, "$name $value " + (if (value in lo..hi) "dentro da faixa $lo–$hi" else "FORA da faixa $lo–$hi") + " (${bandsJ.optString("src")})"))
            }
        }

        // alternativa: plano B da simulação (se for da mesma tática) ou a melhor sugestão da web diferente
        val altJ = repo.dao.plan(slot, "whyalt")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
        val alt = altJ?.takeIf { it.optString("main") == "$formation|${j.optString("playStyle")}" || it.optString("main").startsWith(Formations.base(formation)) && it.optString("main").endsWith("|$style") }?.optJSONObject("alt")?.let { a ->
            "Plano B (simulação): ${a.optString("formation")} • ${a.optString("style")} — pressão ${a.optInt("pressure")} / estilo ${a.optInt("mentality")} / ritmo ${a.optInt("tempo")}"
        } ?: cands.firstOrNull { !(Formations.base(it.formation) == Formations.base(formation) && it.style == style) }?.let { c ->
            "Plano B (web): ${c.formation} • ${c.style} — pressão ${c.pressure} / estilo ${c.mentality} / ritmo ${c.tempo}"
        }

        val risks = ArrayList<String>()
        risks.addAll(coherenceRisks(j))
        WebKnow.rules(repo)?.optJSONObject("rules")?.let { rs ->
            for (k in rs.keys()) {
                val r = rs.optJSONObject(k) ?: continue
                if (Formations.base(r.optString("formation")) == Formations.base(formation) && r.optString("risk").isNotBlank()) { risks.add("Fonte: " + r.optString("risk")); break }
            }
        }
        val agree = web.count { it.first }
        val level = when {
            mine.size >= 6 -> "alta"; mine.size >= 3 -> "média"; else -> "baixa"
        }
        val evidence = "Evidência $level: ${mine.size} jogo(s) seus com essa escolha" + (if (web.isNotEmpty()) " • ${agree} de ${web.size} fonte(s) da web concordam" else " • sem fonte da web para esta situação") +
            (if (mine.size < 4) ". Amostra pequena: pode ser sorte; isto não é chance de vitória." else ". Isto não é chance de vitória.")
        return View("${j.optString("formation")} • ${j.optString("playStyle")}", factors, web, bands, alt, risks.distinct(), evidence)
    }
}

object PostGame {
    data class Two(val title: String, val what: String, val next: String)

    fun twoLines(g: Coach.Game, sameCombo: Int): Two {
        val rv = Coach.review(g)
        val res = when (g.result) { "V" -> "Vitória"; "E" -> "Empate"; "D" -> "Derrota"; else -> "Jogo" }
        val score = if (g.gf != null && g.ga != null) " ${g.gf}×${g.ga}" else ""
        val facts = (rv.wrong.take(1) + rv.right.take(1)).joinToString(" ").ifBlank { "sem estatísticas lidas desse jogo" }
        val what = "$res$score vs ${g.rival} com ${g.formation ?: "?"} • ${g.style ?: "?"}. $facts"
        val gap = if (g.myStr != null && g.rivalStr != null) g.myStr - g.rivalStr else null
        val first = rv.wrong.firstOrNull().orEmpty()
        val next = when {
            g.result == "D" && gap != null && (gap <= -8 || (gap <= -5 && rv.wrong.size <= 1)) -> "Derrota esperada pela diferença de força (${g.myStr}×${g.rivalStr}): não culpe só a tática; mantenha e observe."
            sameCombo < 4 -> "Só $sameCombo jogo(s) com essa tática: um resultado não prova nada. Mude UMA coisa só se o mesmo problema voltar."
            first.startsWith("Perdeu o meio") -> "Teste uma coisa: mais um meia (4-5-1/4-2-3-1) ou ritmo −5."
            first.startsWith("Criou pouco") || first.startsWith("Teve a bola") || first.startsWith("Não marcou") -> "Teste uma coisa: estilo +5 e avançados em Atacar apenas."
            first.startsWith("Defesa exposta") -> "Teste uma coisa: pressão −5 e defesas em Defender atrás."
            first.startsWith("Muitas faltas") -> "Teste uma coisa: desarme um nível abaixo."
            g.result == "V" -> "Repita: funcionou. Só mude se o rival mudar de formação."
            else -> "Sem um problema claro nos números: mantenha e confirme no próximo jogo."
        }
        return Two("Rodada ${g.round}", what, next)
    }
}

object LearnPanel {
    data class View(
        val humanGames: Int, val best: List<String>, val worst: List<String>, val avoid: List<String>, val streak: String,
        val lessons: List<String>, val note: String
    )

    suspend fun build(repo: Repo): View {
        val logs = Director.allLogs(repo)
        val human = logs.count { it.optBoolean("human", false) && it.optString("result") in listOf("V", "E", "D") }
        val lessons = ArrayList<String>()
        for (s in 1..4) repo.dao.plan(s, "lesson")?.json?.let { js ->
            HumanPrior.lessonFromJson(runCatching { JSONObject(js) }.getOrNull(), null)?.text?.takeIf { it.isNotBlank() }?.let { lessons.add("S$s: $it") }
        }
        val p = HumanPrior.build(logs, null)
            ?: return View(human, emptyList(), emptyList(), emptyList(), "", lessons, "Preciso de 6 jogos contra humanos para começar a comparar táticas (tenho $human). Até lá valem a simulação e as fontes da web.")
        fun fmt(k: String, g: HumanPrior.G) = "$k: ${"%.1f".format(g.ppg).replace('.', ',')} pts/jogo em ${g.n} (${"%+.1f".format(g.adj).replace('.', ',')} vs o esperado pela força)"
        val all = (p.form.map { Triple("Formação ${it.key}", it.value, 0) } + p.style.map { Triple("Estilo ${it.key}", it.value, 0) }).filter { it.second.n >= 3 }
        val best = all.sortedByDescending { it.second.adj }.take(3).filter { it.second.adj > 0.05 }.map { fmt(it.first, it.second) }
        val worst = all.sortedBy { it.second.adj }.take(3).filter { it.second.adj < -0.05 }.map { fmt(it.first, it.second) }
        val avoid = (p.avoidForm.map { "Formação $it" } + p.avoidStyle.map { "Estilo $it" })
        return View(p.n, best, worst, avoid, p.streak, lessons,
            "Correlação não é causa: poucos jogos e rivais diferentes pesam muito. Por isso grupos com menos de 3 jogos não entram e o histórico só escolhe entre opções que as fontes já sugerem.")
    }
}
