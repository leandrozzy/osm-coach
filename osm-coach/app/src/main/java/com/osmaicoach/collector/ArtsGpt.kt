package com.osmaicoach.collector

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Prompt pronto para o ChatGPT (Projeto fixo com as regras de estilo): só dados já lidos, nomes reais de clube/país/liga.
 * O app NÃO envia nada ao ChatGPT: copia o texto e abre o link da conversa/projeto que o usuário colou em Ajustes.
 */
object ArtsGpt {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("arts", Context.MODE_PRIVATE)
    fun link(ctx: Context): String = prefs(ctx).getString("gpt_link", "").orEmpty()
    fun setLink(ctx: Context, v: String) { prefs(ctx).edit().putString("gpt_link", v.trim()).apply() }

    /** Artes cujo prompt já foi enviado ao ChatGPT: aparecem como feitas na lista (como as geradas pelo app). */
    fun isDone(ctx: Context, id: String): Boolean = prefs(ctx).getStringSet("gpt_done", emptySet())?.contains(id) == true
    fun markDone(ctx: Context, id: String) {
        val cur = prefs(ctx).getStringSet("gpt_done", emptySet()).orEmpty().toMutableSet(); cur.add(id)
        prefs(ctx).edit().putStringSet("gpt_done", cur).apply()
    }
    fun unmark(ctx: Context, id: String) {
        val cur = prefs(ctx).getStringSet("gpt_done", emptySet()).orEmpty().toMutableSet(); cur.remove(id)
        prefs(ctx).edit().putStringSet("gpt_done", cur).apply()
    }

    /** Depois de Limpar/Apagar: o que já foi usado no ChatGPT nunca mais volta à lista (vai para os dispensados). */
    suspend fun dismissUsed(ctx: Context, repo: Repo, slot: Int? = null) {
        val used = prefs(ctx).getStringSet("gpt_done", emptySet()).orEmpty()
        if (used.isEmpty()) return
        val ids = (if (slot != null) listOf(slot) else (1..4)).flatMap { sl -> Arts.candidates(repo, sl, includeDismissed = true).map { it.id } }.filter { it in used }
        if (ids.isNotEmpty()) Arts.dismiss(repo, ids)
    }

    /** Apagar artes (app e ChatGPT): os eventos apagados nunca mais voltam à lista. */
    suspend fun deleteAndForget(ctx: Context, slot: Int?): Int {
        val repo = Repo(ctx)
        val ids = Arts.items(repo).filter { slot == null || it.slot == slot }.map { it.id }
        if (ids.isNotEmpty()) Arts.dismiss(repo, ids)
        dismissUsed(ctx, repo, slot)
        return if (slot == null) Arts.deleteAll(repo) else Arts.deleteSlot(repo, slot)
    }

    private val rnd = java.util.Random()
    private fun <T> pick(l: List<T>): T = l[rnd.nextInt(l.size)]
    private val POST_STYLES = listOf(
        "capa de jornal impresso clássico, papel levemente envelhecido, foto granulada, título em fonte serifada grande, colunas de texto ao fundo",
        "capa de jornal esportivo popular brasileiro, manchete GIGANTE em caixa alta amarela e branca, fotos coloridas, quadro de placar",
        "capa de revista esportiva de luxo, jogador em destaque ocupando a página, manchete sobre a imagem, selos e chamadas laterais",
        "página de tabloide ousado com fundo preto, manchete amarela, recortes de foto com setas e círculos de destaque",
        "arte de transmissão de TV: foto do lance em tela cheia, placar final em barra de transmissão e chamadas em faixas coloridas"
    )
    private val NEWS_STYLES = listOf(
        "capa de jornal 'Jornal do <clube>' com os retratos em molduras, manchete de mercado da bola e subtítulo",
        "cartaz oficial de apresentação ao estilo redes sociais: os jogadores posam juntos com a camisa do clube e cachecol, texto 'BEM-VINDOS'",
        "página de mercado da bola de revista, uma ficha por jogador (retrato, nome, idade, posição, país, força) e chamada principal",
        "painel de anúncio de reforços em estúdio, fundo escuro com as cores do clube, retratos em recortes inclinados e manchete forte"
    )
    private val GENERIC_STYLES = listOf(
        "composição cinematográfica de poster de filme esportivo",
        "estilo de capa de videogame de futebol, com muita profundidade e brilho",
        "estilo de pôster de campanha da TV esportiva, com faixas diagonais e partículas de luz",
        "estilo de revista esportiva, tipografia ousada, foto em destaque"
    )

    private fun played(ms: List<MatchEntity>) = ms.filter { it.round != null && it.result in listOf("V", "E", "D") }.sortedBy { it.round }

    /** Texto do pedido para a arte [c] (null se faltar dado). */
    suspend fun prompt(ctx: Context, repo: Repo, c: Arts.Cand): String? {
        val slot = c.slot
        val f = repo.fieldMap(slot)
        val teamRaw = f[K.TEAM]?.value ?: return null
        val team = repo.realClub(teamRaw) ?: teamRaw
        val comp = f[K.COMPETITION]?.value
        val country = Flags.countryName(Flags.isoFromCompetition(comp))
        val club = "$team" + (if (team != teamRaw) " (no OSM aparece como \"$teamRaw\")" else "") + (country?.let { " (clube de $it)" } ?: "") + (comp?.let { ", competição: $it" } ?: "")
        val ms = played(repo.dao.matchesOf(slot))
        val v = ms.count { it.result == "V" }; val e = ms.count { it.result == "E" }; val d = ms.count { it.result == "D" }
        val aprov = if (ms.isEmpty()) 0 else Math.round(100f * (3 * v + e) / (3 * ms.size))
        val last = ms.lastOrNull()
        val rivalRaw = f[K.RIVAL_TEAM]?.value
        val rival = repo.realClub(rivalRaw)
        val round = f[K.ROUND]?.value?.let { Regex("\\d+").find(it)?.value }
        val away = f[K.HOME]?.value?.lowercase() == "fora"
        val my = f[K.MY_STRENGTH]?.value; val rv = f[K.RIVAL_STRENGTH]?.value
        val bad = ms.size >= 3 && aprov < 40
        val all = repo.dao.playersOf(slot)
        val myStar = all.filter { it.owner == "MY" && it.strength != null }.maxByOrNull { it.strength ?: 0 }
        val rivStar = all.filter { it.owner == "RIVAL" && it.strength != null }.maxByOrNull { it.strength ?: 0 }
        fun pl(p: PlayerEntity?, clubName: String?) = p?.let { "${it.name}" + (it.age?.let { a -> ", $a anos" } ?: "") + (it.strength?.let { x -> ", força $x" } ?: "") + (clubName?.let { c -> " ($c)" } ?: "") }
        val myLine = pl(myStar, team)
        val rivLine = pl(rivStar, rival)
        // nomes REAIS (cadastro da Base Mundial) dos jogadores, com posição, país e idade: o gerador de imagem precisa do nome certo para o rosto e a camisa
        suspend fun desc(p: PlayerEntity): String {
            val real = runCatching { WorldOcr.realPlayer(ctx, slot, p.name) }.getOrNull()
            val iso = FlagManual.all(repo)[p.nameKey] ?: repo.dao.plan(slot, "natiso4")?.json?.let { runCatching { org.json.JSONObject(it).optString(p.nameKey) }.getOrNull() }?.takeIf { it.isNotBlank() }
            val n = real?.let { r -> r.fullName ?: r.name } ?: p.name
            val extra = listOfNotNull(p.posCode, Flags.countryName(iso) ?: real?.nationality, p.age?.let { a -> "$a anos" }).joinToString(", ")
            return n + (if (n != p.name) " (no jogo: ${p.name})" else "") + (if (extra.isEmpty()) "" else " — $extra")
        }
        suspend fun squad(owner: String, n: Int): String {
            val parts = ArrayList<String>()
            for (p in all.filter { it.owner == owner && it.strength != null }.sortedByDescending { it.strength }.take(n)) parts.add(desc(p))
            return parts.joinToString("; ")
        }
        val squadMine = squad("MY", 5); val squadRiv = squad("RIVAL", 3)
        fun numOf(x: String?) = x?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
        val posNow = numOf(f[K.LEAGUE_POS]?.value)
        val objective = numOf(f[K.MY_OBJECTIVE]?.value)
        /** "V 2×0 Rival (casa)" dos últimos jogos lidos: fatos reais para a arte (nada de campanha zerada quando ninguém jogou). */
        fun lastGames(n: Int) = ms.takeLast(n).joinToString("; ") { m -> "${m.result} ${m.scoreMine}×${m.scoreOpp} ${repo.realClub(m.opponent) ?: m.opponent ?: "?"}" + (if (m.home == true) " (casa)" else if (m.home == false) " (fora)" else "") }
        val campanha = if (ms.isEmpty()) "a temporada ainda não teve jogos lidos (NÃO escreva campanha, vitórias, gols nem aproveitamento: ainda não existem)" else "${v}V ${e}E ${d}D, aproveitamento $aprov%, gols ${ms.sumOf { it.scoreMine ?: 0 }} × ${ms.sumOf { it.scoreOpp ?: 0 }}"
        /** Gols do jogo (os DOIS lados, com minuto e autor quando lidos), como na capa de jornal. */
        suspend fun goalsBlock(m: MatchEntity, opp: String): String {
            val j = repo.dao.plan(slot, "mr_R${m.round}")?.json?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            val mineHome = j?.takeIf { it.has("mineHome") }?.optBoolean("mineHome") ?: m.home
            val goals = ArrayList<Triple<String?, String, Boolean?>>()
            val evs = j?.optJSONArray("events")
            if (evs != null) for (i in 0 until evs.length()) {
                val ev = evs.optString(i)
                if (!ev.contains(" gol de ")) continue
                val who = ev.substringAfter(" gol de ").substringBefore(" (").trim()
                if (who.isBlank()) continue
                val min = Regex("^\\s*(\\d+(?:\\+\\d+)?)").find(ev)?.groupValues?.get(1)
                val ours = if (mineHome == null) null else (ev.contains("(casa)") == mineHome)
                if (goals.any { it.first == min && it.third == ours && Txt.sim(Txt.key(it.second), Txt.key(who)) >= 0.6f }) continue
                goals.add(Triple(min, who, ours))
            }
            suspend fun gt(g: Triple<String?, String, Boolean?>): String { val r = runCatching { WorldOcr.realPlayer(ctx, slot, g.second) }.getOrNull(); return (g.first?.let { "$it' " } ?: "") + (r?.let { x -> x.fullName ?: x.name } ?: g.second) }
            suspend fun side(list: List<Triple<String?, String, Boolean?>>, total: Int): String {
                val parts = ArrayList<String>(); for (g in list) parts.add(gt(g))
                repeat((total - list.size).coerceAtLeast(0)) { parts.add("gol sem autor identificado") }
                return if (parts.isEmpty()) "nenhum gol" else parts.joinToString("; ")
            }
            val ours = goals.filter { it.third == true }.sortedBy { it.first?.substringBefore('+')?.toIntOrNull() ?: 999 }
            val theirs = goals.filter { it.third == false }.sortedBy { it.first?.substringBefore('+')?.toIntOrNull() ?: 999 }
            return "- $team (${m.scoreMine ?: 0} gol(s)): ${side(ours, m.scoreMine ?: 0)}.\n- $opp (${m.scoreOpp ?: 0} gol(s)): ${side(theirs, m.scoreOpp ?: 0)}.\n"
        }
        /** Setores (ataque/meio/defesa/goleiro): meu × rival, para o lado a lado e para "o que precisamos fazer". */
        val sectors = listOf(Triple("Ataque", K.MY_ATK, K.RIVAL_ATK), Triple("Meio-campo", K.MY_MID, K.RIVAL_MID), Triple("Defesa", K.MY_DEF, K.RIVAL_DEF), Triple("Goleiro", K.MY_GOL, K.RIVAL_GOL))
        val mySectors = sectors.mapNotNull { (n, a, _) -> f[a]?.value?.toIntOrNull()?.let { n to it } }
        val bestSector = mySectors.maxByOrNull { it.second }; val worstSector = mySectors.minByOrNull { it.second }
        /** Tabela de classificação lida (quando existe): minha linha e as primeiras. */
        val stRows = League.table(repo.dao.plan(slot, "league")?.json?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }, "st").filter { it.rank != null }.sortedBy { it.rank }
        val myRow = League.find(stRows, teamRaw) ?: League.find(stRows, team)
        val sb = StringBuilder()
        fun head(kind: String, fmt: String = "vertical 1080x1350") =
            sb.append("ARTE NOVA E INDEPENDENTE: ignore o conteúdo, os nomes e o estilo das imagens anteriores desta conversa.\nCrie um pôster de futebol $fmt: $kind. Siga as instruções do projeto (realista, estádio à noite, futebol de campo, escudos e nomes exatos). Gere uma composição NOVA e criativa, diferente das anteriores.\n\n")
        when (c.kind) {
            "MATCHDAY" -> {
                val r = rival ?: return null
                val p = runCatching { Director.winProb(repo, slot) }.getOrNull()
                val (l, rt) = if (away) r to team else team to r
                head("DIA DE JOGO")
                sb.append("Jogo: $l × $rt (casa à esquerda, fora à direita). Rodada ${round ?: "—"}. Meu clube: $club. Rival: $r.\n")
                val myN = my?.toIntOrNull(); val rvN = rv?.toIntOrNull()
                if (myN != null && rvN != null) sb.append("Força geral: $team $myN × $r $rvN.\n")
                val cmp = sectors.mapNotNull { (n, a, b) -> val x = f[a]?.value; val y = f[b]?.value; if (x != null && y != null) "$n: $team $x × $r $y" else null }
                if (cmp.isNotEmpty()) sb.append("Lado a lado por setor: ${cmp.joinToString("; ")}.\n")
                if (ms.isNotEmpty()) sb.append("Fase do $team (últimos jogos): ${lastGames(5)}.\n")
                posNow?.let { sb.append("Posição do $team na tabela: ${it}º lugar.\n") }
                if (squadMine.isNotEmpty() || squadRiv.isNotEmpty()) sb.append("Elenco (nomes reais, para rostos e camisas corretos): $team: $squadMine" + (if (squadRiv.isNotEmpty()) ". $r: $squadRiv" else "") + ".\n")
                sb.append("Cena: tela dividida na diagonal, uma cor para cada clube (cores reais dos dois), escudos grandes no topo com um X entre eles; barras comparativas por setor no centro.\n")
                sb.append("Jogadores (use os rostos reais deles): melhor do meu time = ${myLine ?: "um jogador do $team"}, em destaque, de braços cruzados; melhor do adversário = ${rivLine ?: "um jogador do $r"}, menor, ao fundo do outro lado, com a camisa do $r.\n")
                if (p != null) {
                    val (pl, pr) = if (away) p.loss to p.win else p.win to p.loss
                    sb.append("Texto na imagem: \"DIA DE JOGO\"; \"Rodada ${round ?: ""} • $l × $rt\"; faixa esquerda: \"${l.uppercase()}\" e \"$pl%\"; centro: \"Empate ${p.draw}%\"; faixa direita: \"${rt.uppercase()}\" e \"$pr%\"; linha pequena: \"Força ${if (away) "$rv × $my" else "$my × $rv"}\".\n")
                }
                val edge = if (myN != null && rvN != null) myN - rvN else 0
                sb.append("Texto de apoio: fale MAIS do $team, com tom de torcida do meu clube. " + when {
                    edge >= 6 -> "Os números mostram o $team entrando na frente: use um tom de quem chega confiante, de time que está um passo adiante."
                    edge <= -6 -> "Os números mostram um adversário mais forte: use um tom de quem vai buscar o resultado com garra, valorizando o que o $team tem de melhor."
                    else -> "Os números mostram um duelo parelho: use um tom de confronto aberto, em que o $team tem tudo para levar a melhor."
                } + " Seja criativo e natural, com frases novas a cada arte; NÃO use as palavras \"favorito\", \"azarão\", \"equilibrado\" nem \"superar\". Use só os números acima, sem inventar estatísticas.\n")
            }
            "POST" -> {
                val m = last ?: return null
                val opp = repo.realClub(m.opponent) ?: return null
                val res = when (m.result) { "V" -> "VITÓRIA"; "D" -> "DERROTA"; else -> "EMPATE" }
                val j = repo.dao.plan(slot, "mr_R${m.round}")?.json?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
                val mineHome = j?.takeIf { it.has("mineHome") }?.optBoolean("mineHome") ?: m.home
                val goals = ArrayList<Triple<String?, String, Boolean?>>()
                val evs = j?.optJSONArray("events")
                if (evs != null) for (i in 0 until evs.length()) {
                    val ev = evs.optString(i)
                    if (!ev.contains(" gol de ")) continue
                    val who = ev.substringAfter(" gol de ").substringBefore(" (").trim()
                    if (who.isBlank()) continue
                    val min = Regex("^\\s*(\\d+(?:\\+\\d+)?)").find(ev)?.groupValues?.get(1)
                    val ours = if (mineHome == null) null else (ev.contains("(casa)") == mineHome)
                    if (goals.any { it.first == min && it.third == ours && Txt.sim(Txt.key(it.second), Txt.key(who)) >= 0.6f }) continue
                    goals.add(Triple(min, who, ours))
                }
                val cap = mapOf<Boolean?, Int>(true to (m.scoreMine ?: 0), false to (m.scoreOpp ?: 0))
                val used = HashMap<Boolean?, Int>()
                val shown = goals.sortedBy { it.first?.substringBefore('+')?.toIntOrNull() ?: 999 }.filter { g ->
                    val n = used.getOrDefault(g.third, 0)
                    if (g.third != null && n >= (cap[g.third] ?: 0)) false else { used[g.third] = n + 1; true }
                }
                head("PÓS-JOGO / CAPA DE JORNAL", "horizontal 1600x1120")
                sb.append("Estilo desta vez: ${pick(POST_STYLES)}.\n")
                sb.append("Jornal do $team. Resultado: $res de $team ${m.scoreMine} × ${m.scoreOpp} $opp, rodada ${m.round}" + (if (mineHome == true) " (em casa)" else if (mineHome == false) " (fora)" else "") + ". Clube: $club. Campanha: ${v}V ${e}E ${d}D.\n")
                val ourGoals = shown.filter { it.third == true }; val theirGoals = shown.filter { it.third == false }; val sideless = shown.filter { it.third == null }
                val mineScore = m.scoreMine ?: 0; val oppScore = m.scoreOpp ?: 0
                suspend fun goalText(g: Triple<String?, String, Boolean?>): String {
                    val r = runCatching { WorldOcr.realPlayer(ctx, slot, g.second) }.getOrNull()
                    return (g.first?.let { mm -> "$mm' " } ?: "") + (r?.let { x -> x.fullName ?: x.name } ?: g.second)
                }
                suspend fun sideLine(list: List<Triple<String?, String, Boolean?>>, total: Int): String {
                    val parts = ArrayList<String>(); for (g in list) parts.add(goalText(g))
                    repeat((total - list.size).coerceAtLeast(0)) { parts.add("gol sem autor identificado") }
                    return if (parts.isEmpty()) "nenhum gol" else parts.joinToString("; ")
                }
                sb.append("GOLS DO JOGO (mostre os DOIS lados na arte, cada gol com minuto e autor quando informados):\n")
                sb.append("- $team ($mineScore gol(s)): ${sideLine(ourGoals, mineScore)}.\n")
                sb.append("- $opp ($oppScore gol(s)): ${sideLine(theirGoals, oppScore)}.\n")
                if (sideless.isNotEmpty()) { val parts = ArrayList<String>(); for (g in sideless) parts.add(goalText(g)); sb.append("- Gols cujo time não foi identificado: ${parts.joinToString("; ")}.\n") }
                sb.append("Cite pelo NOME todos os autores informados, dos dois times. Para \"gol sem autor identificado\" escreva só \"gol do <clube>\" (com o minuto, se houver): nunca invente nome. Não deixe nenhum dos dois times sem seus gols na arte.\n")
                if (squadMine.isNotEmpty()) sb.append("Elenco do $team (nomes reais, para rostos e camisas corretos): $squadMine.\n")
                sb.append(when (m.result) {
                    "V" -> "Tom: festa e orgulho; torcida e jogadores comemorando.\n"
                    "D" -> "Tom: recuperação, \"vamos melhorar\", jogadores unidos, sem humilhação.\n"
                    else -> "Tom: equilibrado, \"ponto conquistado, dá para crescer\".\n"
                })
                // análise do jogo, do ponto de vista do MEU time: estatísticas lidas e comentário do analista do próprio jogo
                val statsObj = j?.optJSONObject("stats")
                val mi = if (mineHome == false) 1 else 0
                fun st(key: String, label: String): String? = statsObj?.optJSONArray(key)?.let { a -> if (a.length() >= 2) "$label: ${a.optString(mi)} × ${a.optString(1 - mi)}" else null }
                val statLines = listOfNotNull(st("posse de bola", "Posse"), st("remates", "Remates"), st("precisao", "Precisão"), st("cantos", "Cantos"), st("faltas", "Faltas"), st("formacao", "Formação"))
                if (statLines.isNotEmpty()) sb.append("Estatísticas do jogo ($team primeiro): ${statLines.joinToString("; ")}.\n")
                val tip = j?.optString("tip")?.takeIf { it.isNotBlank() }; val adv = j?.optString("advice")?.takeIf { it.isNotBlank() }
                if (tip != null || adv != null) sb.append("Comentário do analista do jogo (base da análise; não invente além disso): ${listOfNotNull(tip, adv).joinToString(" ")}\n")
                sb.append("Análise na arte: foque no $team — o que fez de bom no jogo, o que poderia ter feito diferente e o que melhorar para o próximo (use SÓ as estatísticas e o comentário acima; sem inventar lances).\n")
                sb.append("Escreva uma MANCHETE criativa e uma linha fina curtas, usando SÓ os fatos acima (nomes, minutos e placar exatos; não invente gols, lances, estádio ou público). Use os rostos reais dos goleadores citados. Inclua o escudo dos dois clubes e o placar bem visível.\n")
            }
            "NEWS" -> {
                val mine = all.filter { it.owner == "MY" }
                val ledger = Ledger.parse(repo.dao.plan(slot, "ledger")?.json)
                val buys = ledger.filter { it.type == "buy" }.sortedByDescending { it.at }.distinctBy { it.key }.mapNotNull { b -> mine.firstOrNull { it.nameKey == b.key } }.take(3)
                if (buys.isEmpty()) return null
                head("REFORÇOS / MERCADO DA BOLA", "horizontal 1600x1120")
                sb.append("Estilo desta vez: ${pick(NEWS_STYLES)}.\n")
                sb.append("Clube: $club. Reforços recentes: " + buys.joinToString("; ") { p -> p.name + (p.age?.let { ", $it anos" } ?: "") + (p.posCode?.let { ", $it" } ?: "") + (p.strength?.let { ", força $it" } ?: "") + (p.valueText?.takeIf { v2 -> v2.isNotBlank() }?.let { ", valor $it" } ?: "") } + ".\n")
                sb.append("Rostos reais dos jogadores, cada um com a camisa oficial do $team. Manchete criativa sobre a força do elenco" + (my?.let { " (força geral $it)" } ?: "") + (comp?.let { " na $it" } ?: "") + ". Use só estes dados, sem inventar contratos, valores ou declarações.\n")
            }
            "GOLEADA" -> {
                val m = last ?: return null
                if (m.result != "V" || (m.scoreMine ?: 0) - (m.scoreOpp ?: 0) < 3) return null        // só goleada A MEU FAVOR
                val opp = repo.realClub(m.opponent) ?: m.opponent ?: return null
                head("GOLEADA")
                sb.append("$team ${m.scoreMine} × ${m.scoreOpp} $opp, rodada ${m.round}" + (if (m.home == true) " (em casa)" else if (m.home == false) " (fora)" else "") + ". Clube: $club.\n")
                sb.append("GOLS (mostre quem marcou, com minuto quando informado):\n" + goalsBlock(m, opp))
                sb.append("Cite pelo NOME os autores informados; para \"gol sem autor identificado\" escreva só \"gol do <clube>\". Nunca invente nome nem minuto.\n")
                if (squadMine.isNotEmpty()) sb.append("Elenco do $team (nomes reais, para rostos e camisas): $squadMine.\n")
                sb.append("Tom: EXALTE o $team — domínio, força, festa da torcida. Cena: comemoração intensa dos jogadores e da torcida, sinalizadores, placar enorme e o escudo do $team. Manchete curta e vibrante sobre a goleada do $team (use só o placar e os fatos acima; o adversário aparece só como o time goleado, sem humilhá-lo).\n")
            }
            "STREAK" -> {
                val n = ms.reversed().takeWhile { it.result == "V" }.size
                if (n <= 10) return null                                                               // só com MAIS de 10 vitórias seguidas
                head("SEQUÊNCIA DE VITÓRIAS")
                sb.append("$team: $n vitórias seguidas, aproveitamento $aprov%. Clube: $club.\n")
                sb.append("Últimas vitórias da sequência: ${lastGames(6)}.\n")
                posNow?.let { sb.append("Posição atual: ${it}º lugar.\n") }
                if (squadMine.isNotEmpty()) sb.append("Craques do $team (nomes reais): $squadMine.\n")
                sb.append("Cena: time embalado, jogadores comemorando juntos, torcida em festa, energia nas cores do clube. Texto: manchete sobre a sequência histórica (\"$n seguidas\") e uma linha fina com 1 ou 2 placares acima. Não invente adversários nem placares.\n")
            }
            "LEADER" -> {
                head("LÍDER DA LIGA")
                sb.append("$club. O $team ASSUMIU A LIDERANÇA. Campanha: $campanha.\n")
                val second = stRows.firstOrNull { it.rank == 2 }
                if (myRow?.pts != null) sb.append("Tabela: $team com ${myRow.pts} pontos" + (second?.pts?.let { sp -> " (${myRow.pts - sp} à frente do 2º, ${second.club}, com $sp)" } ?: "") + ".\n")
                if (ms.isNotEmpty()) sb.append("Últimos jogos: ${lastGames(5)}.\n")
                if (squadMine.isNotEmpty()) sb.append("Craques do $team (nomes reais, para rostos e camisas): $squadMine.\n")
                sb.append("Tom: ENALTEÇA o $team — conquista da ponta da tabela, time que lidera por mérito. Cena: taça dourada ao fundo, o time na ponta da tabela, torcida em festa. Manchete sobre a liderança do $team (use só a posição, os pontos e o aproveitamento acima).\n")
            }
            "TITLE" -> {
                head("CAMPEÃO")
                sb.append("$club. CAMPEÃO. Campanha: $campanha.\n")
                if (myRow != null) sb.append("Campanha na tabela final: ${listOfNotNull(myRow.j?.let { "$it jogos" }, myRow.v?.let { "${it}V" }, myRow.e?.let { "${it}E" }, myRow.d?.let { "${it}D" }, myRow.pts?.let { "$it pontos" }, if (myRow.gf != null && myRow.ga != null) "gols ${myRow.gf}×${myRow.ga}" else null).joinToString(" ")}.\n")
                val vice = stRows.firstOrNull { it.rank == 2 }
                if (vice != null && myRow?.pts != null && vice.pts != null) sb.append("Vice-campeão: ${vice.club} com ${vice.pts} pontos (${myRow.pts - vice.pts} de diferença).\n")
                if (stRows.isNotEmpty()) sb.append("Clubes na tabela: ${stRows.size}.\n")
                if (squadMine.isNotEmpty()) sb.append("Craques do título (nomes reais, para rostos e camisas): $squadMine.\n")
                sb.append("Tom: ENALTEÇA o $team — campeão por mérito da campanha acima. Cena: elenco levantando a taça de campeão, confete dourado, estádio em festa. Traga na arte as informações do campeonato (nome da competição, campanha, pontos, vice) usando SÓ os dados acima.\n")
            }
            "OBJECTIVE" -> {
                head("OBJETIVO ALCANÇADO")
                sb.append("$club. Campanha: $campanha.\n")
                posNow?.let { sb.append("Posição final/atual: ${it}º lugar.\n") }
                if (objective != null) sb.append("Meta da diretoria: ${objective}º lugar ou melhor — ALCANÇADA" + (posNow?.let { " (${it}º)" } ?: "") + ".\n")
                if (ms.isNotEmpty()) sb.append("Últimos jogos: ${lastGames(5)}.\n")
                if (squadMine.isNotEmpty()) sb.append("Craques do $team (nomes reais, para rostos e camisas): $squadMine.\n")
                sb.append("Cena: elenco comemorando de braços erguidos, faixa de \"meta cumprida\". Manchete sobre o objetivo alcançado (cite a meta e a posição).\n")
            }
            "STARS" -> {
                val top = repo.dao.playersOf(slot).filter { it.owner == "MY" && it.strength != null }.sortedByDescending { it.strength }.take(4)
                head("CRAQUES DO TIME")
                sb.append("Clube: $club. Craques (nomes reais): " + squad("MY", 4) + ". Rostos fiéis aos jogadores reais, camisa oficial do $team.\n")
            }
            "EXPECT" -> {
                // EXPECTATIVA = o que o clube QUER ALCANÇAR (a meta) e o que precisa fazer; não é balanço de resultados
                val rows = League.table(repo.dao.plan(slot, "league")?.json?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }, "va").filter { it.value != null }
                val rank = rows.sortedByDescending { it.value }.indexOfFirst { League.key(it.club) == League.key(teamRaw) || League.key(it.club) == League.key(team) }.takeIf { it >= 0 }?.plus(1)
                val tier = if (rank == null) null else if (rank <= maxOf(1, rows.size / 4)) "FAVORITO" else if (rank <= rows.size * 6 / 10) "NA BRIGA" else "AZARÃO"
                head("EXPECTATIVA DA TEMPORADA")
                sb.append("Clube: $club.\n")
                sb.append("EXPECTATIVA = o que o $team QUER ALCANÇAR nesta temporada (a META e a ambição) e o que PRECISA FAZER para chegar lá; não é um balanço de resultados.\n")
                sb.append("Fatos da expectativa:\n")
                sb.append(if (objective != null) "- META da diretoria: terminar em ${objective}º lugar ou melhor.\n" else "- O jogo não informou uma meta numérica: expresse só a ambição de começo de temporada (sem inventar posição).\n")
                if (rank != null) sb.append("- O elenco é o ${rank}º mais valioso entre ${rows.size} clubes da competição (${when (tier) { "FAVORITO" -> "candidato ao topo"; "NA BRIGA" -> "time para brigar pela parte de cima"; else -> "time que quer surpreender" }}).\n")
                if (my != null) sb.append("- Força geral do time: $my.\n")
                bestSector?.let { sb.append("- Ponto forte: ${it.first} (${it.second}).\n") }
                if (worstSector != null && worstSector != bestSector) sb.append("- Ponto a melhorar: ${worstSector.first} (${worstSector.second}).\n")
                if (posNow != null && ms.isNotEmpty()) sb.append("- Posição atual: ${posNow}º lugar.\n")
                sb.append("- Campanha: $campanha.\n")
                if (squadMine.isNotEmpty()) sb.append("Craques que vão buscar a meta (nomes reais, para rostos e camisas corretos): $squadMine.\n")
                val caminho = listOfNotNull(
                    objective?.let { "somar pontos para chegar ao ${it}º lugar ou melhor" },
                    bestSector?.let { "manter forte o ${it.first.lowercase()}" },
                    worstSector?.takeIf { it != bestSector }?.let { "reforçar o ${it.first.lowercase()}" },
                    "aproveitar os jogos em casa e não perder pontos contra os rivais diretos")
                sb.append("O QUE PRECISAMOS FAZER (use na arte, em 2 ou 3 itens curtos): ${caminho.joinToString("; ")}.\n")
                sb.append("Cena: o elenco posado de braços cruzados e olhar determinado, estádio ao amanhecer ou à noite, escudo do $team grande e a taça como símbolo da meta. Tom: ambicioso e esperançoso" + (if (tier == "AZARÃO") ", de quem quer surpreender" else if (tier == "FAVORITO") ", de quem quer o topo" else "") + ".\n")
                sb.append("Texto na imagem: uma MANCHETE sobre a META" + (objective?.let { " (ex.: \"META: TOP $it\")" } ?: "") + ", uma linha fina com 2 ou 3 fatos acima e o quadro \"O que precisamos fazer\". NÃO escreva \"Campanha 0V 0E 0D\", \"Aproveitamento 0%\" nem \"Gols 0 × 0\": jogos que não aconteceram não viram texto.\n")
            }
            "RECOVER" -> {
                head("HORA DE REAGIR")
                sb.append("Clube: $club. Campanha: $campanha.\n")
                if (ms.isNotEmpty()) sb.append("Últimos jogos (do mais antigo ao mais recente): ${lastGames(5)}.\n")
                posNow?.let { sb.append("Posição atual: ${it}º lugar.\n") }
                worstSector?.let { sb.append("Setor para melhorar: ${it.first} (${it.second}).\n") }
                bestSector?.takeIf { it != worstSector }?.let { sb.append("Ponto forte para se apoiar: ${it.first} (${it.second}).\n") }
                if (squadMine.isNotEmpty()) sb.append("Craques (nomes reais, para rostos e camisas): $squadMine.\n")
                sb.append("Tom: o time passa por uma fase ruim; mostre união, reação e confiança, \"vamos melhorar\" — nunca humilhação. Cena: jogadores de cabeça erguida abraçados no gramado ao amanhecer, estádio atrás, luz esperançosa. Manchete sobre a reação do $team, uma linha fina com o que vai melhorar (use só os dados acima).\n")
            }
            "SEASON" -> {
                head("RESUMO DA TEMPORADA")
                sb.append("Clube: $club. Campanha: $campanha.\n")
                posNow?.let { sb.append("Posição na tabela: ${it}º lugar" + (objective?.let { o -> " (meta: ${o}º)" } ?: "") + ".\n") }
                if (ms.isNotEmpty()) sb.append("Jogos: ${lastGames(6)}.\n")
                if (squadMine.isNotEmpty()) sb.append("Destaques do elenco (nomes reais): $squadMine.\n")
                sb.append("Cena: balanço da temporada, gráfico simples de vitórias/empates/derrotas, o time reunido, escudo grande. Manchete sobre como foi a temporada (use só os números acima).\n")
                sb.append(if (bad) "Tom: honesto e de recuperação (\"vamos melhorar\"), nunca humilhação.\n" else "Tom: confiante.\n")
            }
            else -> return null
        }
        if (c.kind !in listOf("POST", "NEWS")) sb.append("\nEstilo desta vez: ${pick(GENERIC_STYLES)} (varie a cena, o enquadramento e a luz em relação às artes anteriores).")
        sb.append("\nEscreva nos textos os nomes reais dos clubes exatamente como informei acima (o nome que aparece como \"no OSM\" é só do jogo, não use na imagem), sem acrescentar nem trocar palavras. Não invente números nem nomes além dos informados. Texto em português, letras grandes e nítidas.")
        return sb.toString()
    }

    /** Copia o texto e abre o link do ChatGPT (ou o app). Devolve a mensagem para o usuário. */
    fun send(ctx: Context, text: String): String {
        runCatching { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("prompt", text)) }
        val url = link(ctx).ifBlank { "https://chatgpt.com/" }
        val ok = runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        }.getOrDefault(false)
        return if (ok) "Prompt copiado. Abra uma conversa nova no projeto, cole e envie." else "Prompt copiado, mas não consegui abrir o ChatGPT."
    }
}
