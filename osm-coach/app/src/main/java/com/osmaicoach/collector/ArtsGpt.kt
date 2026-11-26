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
                if (squadMine.isNotEmpty() || squadRiv.isNotEmpty()) sb.append("Elenco (nomes reais, para rostos e camisas corretos): $team: $squadMine" + (if (squadRiv.isNotEmpty()) ". $r: $squadRiv" else "") + ".\n")
                sb.append("Cena: tela dividida na diagonal, uma cor para cada clube (cores reais dos dois), escudos grandes no topo com um X entre eles.\n")
                sb.append("Jogadores (use os rostos reais deles): melhor do meu time = ${myLine ?: "um jogador do $team"}, em destaque, de braços cruzados; melhor do adversário = ${rivLine ?: "um jogador do $r"}, menor, ao fundo do outro lado, com a camisa do $r.\n")
                if (p != null) {
                    val (pl, pr) = if (away) p.loss to p.win else p.win to p.loss
                    sb.append("Texto na imagem: \"DIA DE JOGO\"; \"Rodada ${round ?: ""} • $l × $rt\"; faixa esquerda: \"${l.uppercase()}\" e \"$pl%\"; centro: \"Empate ${p.draw}%\"; faixa direita: \"${rt.uppercase()}\" e \"$pr%\"; linha pequena: \"Força ${if (away) "$rv × $my" else "$my × $rv"}\".\n")
                }
            }
            "COMPARE" -> {
                val r = rival ?: return null
                head("LADO A LADO")
                sb.append("Comparação entre $team (meu clube; $club) e $r. Força geral: $my × $rv.\n")
                for ((n, a, b) in listOf(Triple("Ataque", K.MY_ATK, K.RIVAL_ATK), Triple("Meio-campo", K.MY_MID, K.RIVAL_MID), Triple("Defesa", K.MY_DEF, K.RIVAL_DEF), Triple("Goleiro", K.MY_GOL, K.RIVAL_GOL))) {
                    val x = f[a]?.value; val y = f[b]?.value
                    if (x != null && y != null) sb.append("$n: $x × $y\n")
                }
                if (squadMine.isNotEmpty() || squadRiv.isNotEmpty()) sb.append("Elenco (nomes reais): $team: $squadMine" + (if (squadRiv.isNotEmpty()) ". $r: $squadRiv" else "") + ".\n")
                sb.append("Cena: dois jogadores de perfil, frente a frente, uma cor por clube, barras comparativas por setor entre eles.\n")
                sb.append("Jogadores (rostos reais): ${myLine ?: "um jogador do $team"} contra ${rivLine ?: "um jogador do $r"}, cada um com a camisa oficial do seu clube.\n")
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
                head("GOLEADA")
                sb.append("$team ${m.scoreMine} × ${m.scoreOpp} ${repo.realClub(m.opponent)}, rodada ${m.round}. Clube: $club. Cena de comemoração com torcida e sinalizadores.\n")
            }
            "STREAK" -> {
                val n = ms.reversed().takeWhile { it.result == "V" }.size
                head("SEQUÊNCIA DE VITÓRIAS")
                sb.append("$team: $n vitórias seguidas, aproveitamento $aprov%. Clube: $club. Cena de festa da torcida.\n")
            }
            "LEADER", "TITLE", "OBJECTIVE" -> {
                head(if (c.kind == "LEADER") "LÍDER DA LIGA" else if (c.kind == "TITLE") "CAMPEÃO" else "OBJETIVO ALCANÇADO")
                sb.append("$club. Campanha: ${v}V ${e}E ${d}D. Cena: taça dourada e estádio em festa.\n")
            }
            "BUY", "PLAYER" -> {
                val key = c.key ?: return null
                val p = repo.dao.playersOf(slot).firstOrNull { it.owner == "MY" && it.nameKey == key } ?: return null
                head(if (c.kind == "BUY") "CONTRATAÇÃO CONFIRMADA" else "DESTAQUE DO ELENCO")
                sb.append("Jogador: ${desc(p)}" + (p.strength?.let { ", força $it" } ?: "") + ". Clube: $club.\n")
                sb.append("Use o rosto, cabelo, barba e tom de pele fiéis ao jogador real, com a camisa oficial do $team. Retrato de cintura para cima, braços cruzados.\n")
            }
            "STARS" -> {
                val top = repo.dao.playersOf(slot).filter { it.owner == "MY" && it.strength != null }.sortedByDescending { it.strength }.take(4)
                head("CRAQUES DO TIME")
                sb.append("Clube: $club. Craques (nomes reais): " + squad("MY", 4) + ". Rostos fiéis aos jogadores reais, camisa oficial do $team.\n")
            }
            "MOMENT", "CAMP", "SEASON", "EXPECT" -> {
                val l5 = ms.takeLast(5)
                head(when (c.kind) { "MOMENT" -> "MOMENTO DO TIME"; "EXPECT" -> "EXPECTATIVA DA TEMPORADA"; "SEASON" -> "RESUMO DA TEMPORADA"; else -> "CAMPANHA" })
                sb.append("Clube: $club. Campanha: ${v}V ${e}E ${d}D, aproveitamento $aprov%, gols ${ms.sumOf { it.scoreMine ?: 0 }} × ${ms.sumOf { it.scoreOpp ?: 0 }}. Últimos jogos: ${l5.joinToString(" ") { it.result.orEmpty() }}.\n")
                sb.append(if (bad) "Tom: o time não está bem; mostre recuperação e \"vamos melhorar\", nunca humilhação.\n" else "Tom: confiante.\n")
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
