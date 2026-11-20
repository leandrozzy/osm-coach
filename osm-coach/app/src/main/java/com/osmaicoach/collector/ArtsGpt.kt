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
        val team = f[K.TEAM]?.value ?: return null
        val comp = f[K.COMPETITION]?.value
        val country = Flags.countryName(Flags.isoFromCompetition(comp))
        val club = "$team" + (country?.let { " (clube de $it)" } ?: "") + (comp?.let { ", competição: $it" } ?: "")
        val ms = played(repo.dao.matchesOf(slot))
        val v = ms.count { it.result == "V" }; val e = ms.count { it.result == "E" }; val d = ms.count { it.result == "D" }
        val aprov = if (ms.isEmpty()) 0 else Math.round(100f * (3 * v + e) / (3 * ms.size))
        val last = ms.lastOrNull()
        val rival = f[K.RIVAL_TEAM]?.value
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
        val sb = StringBuilder()
        fun head(kind: String, fmt: String = "vertical 1080x1350") =
            sb.append("Crie um pôster de futebol $fmt: $kind. Siga as instruções do projeto (realista, estádio à noite, futebol de campo, escudos e nomes exatos). Gere uma composição NOVA e criativa, diferente das anteriores.\n\n")
        when (c.kind) {
            "MATCHDAY" -> {
                val r = rival ?: return null
                val p = runCatching { Director.winProb(repo, slot) }.getOrNull()
                val (l, rt) = if (away) r to team else team to r
                head("DIA DE JOGO")
                sb.append("Jogo: $l × $rt (casa à esquerda, fora à direita). Rodada ${round ?: "—"}. Meu clube: $club. Rival: $r.\n")
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
                sb.append("Cena: dois jogadores de perfil, frente a frente, uma cor por clube, barras comparativas por setor entre eles.\n")
                sb.append("Jogadores (rostos reais): ${myLine ?: "um jogador do $team"} contra ${rivLine ?: "um jogador do $r"}, cada um com a camisa oficial do seu clube.\n")
            }
            "POST" -> {
                val m = last ?: return null
                val opp = m.opponent ?: return null
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
                if (shown.isNotEmpty()) sb.append("Gols (só estes, com minuto): " + shown.joinToString("; ") { (it.first?.let { mm -> "$mm' " } ?: "") + it.second + " (" + (if (it.third == true) team else if (it.third == false) opp else "—") + ")" } + ".\n")
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
                sb.append("$team ${m.scoreMine} × ${m.scoreOpp} ${m.opponent}, rodada ${m.round}. Clube: $club. Cena de comemoração com torcida e sinalizadores.\n")
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
                sb.append("Jogador: ${p.name}" + (p.age?.let { ", $it anos" } ?: "") + (p.strength?.let { ", força $it" } ?: "") + ". Clube: $club.\n")
                sb.append("Use o rosto, cabelo, barba e tom de pele fiéis ao jogador real, com a camisa oficial do $team. Retrato de cintura para cima, braços cruzados.\n")
            }
            "STARS" -> {
                val top = repo.dao.playersOf(slot).filter { it.owner == "MY" && it.strength != null }.sortedByDescending { it.strength }.take(4)
                head("CRAQUES DO TIME")
                sb.append("Clube: $club. Craques: " + top.joinToString("; ") { "${it.name} (força ${it.strength})" } + ". Rostos fiéis aos jogadores reais, camisa oficial do $team.\n")
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
        sb.append("\nUse os nomes dos clubes na forma curta e oficial, sem acrescentar cidade, \"Belo Horizonte\", \"FC\" ou qualquer palavra que eu não escrevi. Não invente números nem nomes além dos informados. Texto em português, letras grandes e nítidas.")
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
