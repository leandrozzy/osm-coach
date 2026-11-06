package com.osmaicoach.collector

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Aba "Coins": como ganhar Boss Coins rápido, como usá-los para evoluir o time e a reserva que nunca se gasta (100 BC).
 * Duas camadas: (1) o que existe NO SEU JOGO (visto nos seus vídeos/prints: certo, sem inventar valores);
 * (2) pesquisa na web uma vez por dia (Gemini com busca), guardada com a data e marcada como "da web, não confirmado".
 */
object Coins {
    /** Saldo mínimo que nunca é gasto. */
    const val RESERVE = 100
    private const val KIND = "coinsweb"
    @Volatile var running = false

    data class Way(val icon: String, val title: String, val how: String, val source: String, val speed: Int, val verified: Boolean)

    /** Fontes que existem no seu jogo (menu ☰ e telas vistas nos vídeos). O valor de cada recompensa aparece no jogo: o app não inventa. */
    val GAME_WAYS = listOf(
        Way("🎬", "Botão FREE (vídeo) no topo do OSM", "O botão \"FREE\" ao lado dos Boss Coins mostra um anúncio e paga moedas. Repita sempre que aparecer (volta depois de um tempo).", "visto nas telas do seu jogo", 3, true),
        Way("🤝", "Menu ☰ → grátis (Centro de Negócios)", "\"Ganha Boss Coins grátis com propostas de negócio\": ofertas e vídeos de parceiros. A Lista de Afazeres também avisa \"Boss Coins grátis\".", "visto no vídeo do seu jogo", 3, true),
        Way("🎯", "Menu ☰ → Missões", "Missões diárias/semanais com marcos e recompensas (\"novas missões em…\", \"a semana reinicia em…\"). Cumpra as que você já faria (treinar, jogar, vender).", "visto no vídeo do seu jogo", 2, true),
        Way("🔮", "Menu ☰ → Acerta e Ganha", "Palpites de resultado com recompensa. Rápido e sem custo; use o app (Acerto da previsão) para palpitar com base nas suas chances calibradas.", "item do menu do seu jogo", 2, true),
        Way("⚡", "Jogo Rápido (pontos da semana)", "Os pontos do Jogo Rápido (ex.: 34/35) liberam recompensas. Jogos Rápidos são grátis; confira o que cada marco paga.", "visto na central do seu jogo", 2, true),
        Way("🏆", "Fim de temporada e conquistas", "Prêmios por posição final e conquistas. Quem termina mais acima rende mais: é onde a evolução do time paga duas vezes.", "fonte da web (osmtactic.com) + seu jogo", 1, false),
        Way("📅", "Eventos do OSM", "Eventos pagam em chance/bônus. O app já mostra o calendário da comunidade em Hoje/Diretor; ligue o alarme dos que pagam moedas.", "calendário da comunidade", 1, false)
    )

    val WARNINGS = listOf(
        "Geradores de Boss Coins, mods/APK \"hack\" e vídeos de \"bug de moedas infinitas\" são golpe ou malware e podem banir a conta. O app nunca sugere isso.",
        "Códigos promocionais só valem durante eventos e expiram; confira no próprio jogo, não em sites de lista."
    )

    suspend fun balance(repo: Repo): Pair<Int, Long>? =
        repo.dao.plan(0, "bc")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }?.takeIf { it.has("v") }?.let { it.optInt("v") to it.optLong("at") }

    suspend fun saveBalance(repo: Repo, v: Int, now: Long) {
        val old = balance(repo)
        if (old != null && old.first == v && now - old.second < 60_000L) return
        repo.dao.putPlan(PlanEntity(0, "bc", JSONObject().put("v", v).put("at", now).toString(), now))
    }

    // ------------------------------------------------------------ pesquisa diária na web

    data class Web(val at: Long, val ways: List<Way>, val tips: List<String>, val how: String)

    suspend fun web(repo: Repo): Web? {
        val j = repo.dao.plan(0, KIND)?.json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val ws = j.optJSONArray("ways") ?: JSONArray()
        val ways = (0 until ws.length()).mapNotNull { ws.optJSONObject(it) }.map {
            Way("🌐", it.optString("title"), it.optString("how"), it.optString("source"), it.optInt("speed", 1).coerceIn(1, 3), false)
        }
        val ts = j.optJSONArray("tips") ?: JSONArray()
        return Web(j.optLong("at"), ways, (0 until ts.length()).map { ts.optString(it) }, j.optString("how"))
    }

    private val BAD = listOf("gerador", "generator", "hack", "cheat", "mod apk", "apk", "ilimitad", "unlimited", "bug", "glitch", "comprar moedas baratas", "revend")

    fun prompt(): String =
        "Pesquise na internet (fórum oficial do Online Soccer Manager, osmtactic.com, osmguide.com, Reddit r/OnlineSoccerManager, " +
            "YouTube) as formas REAIS e rápidas de ganhar Boss Coins de graça no OSM (Online Soccer Manager) HOJE, e como os jogadores " +
            "experientes gastam Boss Coins para evoluir o time (treino, progressão, estádio) sem desperdiçar. Ignore geradores, hacks, mods, " +
            "bugs e sites de revenda: isso é golpe. Responda SOMENTE com JSON neste formato (português, sem texto fora do JSON):\n" +
            "{\"ways\":[{\"title\":\"...\",\"how\":\"passo a passo curto\",\"speed\":1-3,\"source\":\"site.com\"}],\"tips\":[\"como usar para melhorar o time...\"],\"how\":\"1 frase sobre o que mudou recentemente\"}\n" +
            "speed: 3 = muito rápido (vários por dia), 2 = diário, 1 = lento. Máximo 8 formas e 6 dicas. Só inclua o que as fontes realmente dizem; se não souber, deixe a lista vazia."

    fun clean(json: JSONObject): JSONObject {
        fun ok(s: String) = s.isNotBlank() && BAD.none { Txt.norm(s).contains(it) }
        val ways = JSONArray()
        json.optJSONArray("ways")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val t = o.optString("title").trim().take(80); val h = o.optString("how").trim().take(320)
                if (!ok(t) || !ok(h)) continue
                ways.put(JSONObject().put("title", t).put("how", h).put("speed", o.optInt("speed", 1).coerceIn(1, 3)).put("source", o.optString("source").trim().take(60)))
                if (ways.length() >= 8) break
            }
        }
        val tips = JSONArray()
        json.optJSONArray("tips")?.let { a -> for (i in 0 until a.length()) { val s = a.optString(i).trim().take(320); if (ok(s)) tips.put(s); if (tips.length() >= 6) break } }
        return JSONObject().put("ways", ways).put("tips", tips).put("how", json.optString("how").trim().take(200))
    }

    suspend fun refresh(ctx: Context): String {
        if (running) return "já está atualizando"
        running = true
        val repo = Repo(ctx)
        val now = System.currentTimeMillis()
        Settings.put(ctx, "coins_try_at", now.toString())
        try {
            var reply = AiClient.askGrounded(ctx, prompt())
            var viaSearch = true
            if (!reply.ok) {
                reply = AiClient.ask(ctx, prompt() + " Se não puder pesquisar agora, use o que você sabe dessas fontes.", null, 90000L)
                viaSearch = false
                if (!reply.ok) return "não consegui pesquisar agora: " + (reply.error ?: "")
            }
            val json = AiClient.parseJson(reply.text) ?: return "a IA respondeu sem o JSON; tente mais tarde"
            val c = clean(json)
            if (c.getJSONArray("ways").length() == 0 && c.getJSONArray("tips").length() == 0) return "a pesquisa não trouxe nada aproveitável; mantive o anterior"
            c.put("at", now).put("search", viaSearch)
            repo.dao.putPlan(PlanEntity(0, KIND, c.toString(), now))
            return "atualizado (${c.getJSONArray("ways").length()} formas, ${c.getJSONArray("tips").length()} dicas)" + if (viaSearch) "" else " • sem busca (conhecimento da IA)"
        } finally { running = false }
    }

    /** Uma vez por dia, em segundo plano (chamado pelo monitor e ao abrir a aba). */
    fun refreshIfStale(ctx: Context) {
        if (running || !AiClient.hasAnyKey(ctx)) return
        val last = Settings.get(ctx, "coins_try_at", "0").toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - last < 24L * 3_600_000L) return
        AppScope.scope.launch(Dispatchers.IO) { runCatching { Diag.log("Coins (web): " + refresh(ctx)) } }
    }

    // ------------------------------------------------------------ como gastar

    data class SlotInfo(val slot: Int, val team: String, val diff: Int?, val rules: String?)

    /**
     * Onde os Boss Coins rendem mais, em ordem, respeitando SEMPRE a reserva de 100. Regra do app (não é preço do jogo):
     * o que mais ganha jogo é fechar a diferença de força nos slots em que você é azarão.
     */
    fun spendPlan(balance: Int?, slots: List<SlotInfo>): List<String> {
        val out = ArrayList<String>()
        if (balance == null) return listOf("Abra qualquer tela do OSM com o app lendo para eu ver o seu saldo de Boss Coins (aparece no topo do jogo).")
        val free = balance - RESERVE
        if (free <= 0) return listOf("Saldo $balance: reserva de $RESERVE não se toca. Hoje é só ganhar: use as fontes acima antes de pensar em gastar.")
        out.add("Você pode gastar até $free BC agora (saldo $balance − reserva $RESERVE). Antes de confirmar qualquer compra no jogo, confira: saldo − preço ≥ $RESERVE.")
        val worst = slots.filter { it.diff != null }.minByOrNull { it.diff!! }
        if (worst != null && worst.diff!! < 0) out.add("1º lugar do investimento: S${worst.slot} (${worst.team}), ${-worst.diff!!} pontos abaixo dos rivais. É onde a força decide o jogo (nos seus 70 jogos contra humanos, azarão ganhou 5 de 54). Use progressão/treinador universal no setor mais fraco dele.")
        val strong = slots.filter { it.diff != null && it.diff >= 3 }
        if (strong.isNotEmpty()) out.add("Não gaste moeda em S${strong.joinToString(", S") { it.slot.toString() }}: você já é favorito por 3+ pontos (nos seus jogos, favorito ganhou 14 de 16).")
        for (s in slots) {
            val r = s.rules ?: continue
            val n = Txt.norm(r)
            val bans = listOfNotNull(
                "pacote de jogador".takeIf { n.contains("proibido comprar pacote") || n.contains("proibido usar pacote") },
                "campo de treino".takeIf { n.contains("proibido campo de treino") },
                "treino secreto".takeIf { n.contains("proibido treino secreto") },
                "venda instantânea".takeIf { n.contains("proibido venda instantanea") },
                "moedas de prata".takeIf { n.contains("proibido comprar moedas de prata") }
            )
            if (bans.isNotEmpty()) out.add("S${s.slot}: a liga proíbe ${bans.joinToString(", ")}. Não gaste BC nisso neste slot.")
        }
        out.add("Boss Coin em jogador de pacote é sorteio: não é prioridade. Valor seguro = acelerar o que o time já precisa (treino do setor fraco, obra do estádio de treino).")
        return out
    }
}
