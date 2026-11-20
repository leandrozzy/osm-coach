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
                sb.append("Cena: tela dividida na diagonal, uma cor para cada clube (cores reais dos dois), escudos grandes no topo com um X entre eles, e um jogador do $team em destaque, de braços cruzados.\n")
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
                sb.append("Cena: dois jogadores frente a frente, uma cor por clube, barras comparativas por setor.\n")
            }
            "POST" -> {
                val m = last ?: return null
                val opp = m.opponent ?: return null
                val res = when (m.result) { "V" -> "VITÓRIA"; "D" -> "DERROTA"; else -> "EMPATE" }
                head("PÓS-JOGO / CAPA DE JORNAL", "horizontal 1600x1120")
                sb.append("Jornal do $team. Resultado: $res de $team ${m.scoreMine} × ${m.scoreOpp} $opp, rodada ${m.round}. Clube: $club. Campanha: ${v}V ${e}E ${d}D.\n")
                sb.append(when (m.result) {
                    "V" -> "Tom: festa e orgulho.\n"
                    "D" -> "Tom: recuperação, \"vamos melhorar\", sem humilhação. Cena de jogadores unidos no gramado.\n"
                    else -> "Tom: equilibrado, \"ponto conquistado, dá para crescer\".\n"
                })
                sb.append("Inclua manchete criativa e linha fina SÓ com esses fatos (não invente gols, nomes ou números).\n")
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
        sb.append("\nNão invente números nem nomes além dos informados. Texto em português, letras grandes e nítidas.")
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
