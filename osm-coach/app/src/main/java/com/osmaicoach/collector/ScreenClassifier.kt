package com.osmaicoach.collector

object ScreenClassifier {
    private val hubRound = Regex("\\d{1,2}\\s*/\\s*\\d{1,2}\\s*jornada")
    private val jornada = Regex("jornada\\s*\\d{1,2}")

    /**
     * Whitelist: só é tela do OSM o que tem a barra superior do OSM ou é a central dos slots.
     * Qualquer anúncio (de qualquer rede) cai em NON_OSM e é descartado.
     */
    fun classify(ocr: OcrResult, osmTopBar: Boolean): ScreenType {
        val t = Txt.norm(ocr.fullText)
        val hubHits = hubRound.findAll(t).count()
        if (hubHits >= 2 || (hubHits >= 1 && t.contains("espacos de treinador"))) return ScreenType.HUB
        if (ocr.tokens.size < 4) return ScreenType.NOISE
        // Resultados de TODOS os jogos da liga (Casa | Jornada N | Fora) e fases da copa: não são o calendário/resultado do seu clube; nunca podem trocar a competição do slot.
        if (t.contains("casa") && t.contains("fora") && LeagueResults.parse(ocr) != null) return ScreenType.OTHER_OSM
        if (!t.contains("jornada") && (LeagueResults.parseCup(ocr)?.games?.size ?: 0) >= 2) return ScreenType.OTHER_OSM
        // Análise do rival (nota do analista à esquerda): tela cheia, SEM a barra do OSM.
        if (Parsers.isAnalysis(ocr)) return ScreenType.REPORT
        // Análise do jogo (resultado): placar, estatísticas, zonas de ação e notas dos jogadores.
        if (Parsers.isMatchResult(ocr)) return ScreenType.RESULT
        // Sem a barra do OSM só passa se o texto tiver o vocabulário do jogo (análise do rival, calendário etc.).
        val osmish = listOf(
            "formacao", "marcacao", "desarme", "estilo de jogo", "jornada", "analista", "relatorio", "adversario",
            "plantel", "estadio", "campo de treinamento", "fora de jogo", "temporizacao", "capacidade"
        ).count { t.contains(it) } >= 3
        // Telas com texto bem específico (Missões nem tem a barra do OSM): antes do filtro de anúncios
        Screens.classify(t)?.let { return it }
        if (!osmTopBar && !osmish) return ScreenType.NON_OSM

        // Menu lateral aberto por cima de qualquer tela: tem "Lista de transferências" e "Olheiro" e engana o mercado.
        val menuOpen = t.contains("plantel") && (t.contains("equipa inicial") || t.contains("especialistas") || t.contains("sala de imprensa"))
        if (menuOpen) return ScreenType.OTHER_OSM

        if (t.contains("para treinar") || t.contains("treinador universal") || t.contains("treinador de ")) {
            return ScreenType.TRAINING
        }
        // Pré-jogo ANTES do mercado: a tela do próximo jogo tem o botão "Vender jogadores" (batalha e liga) e
        // era lida como mercado — o slot nunca recebia rival, contagem nem bônus.
        // OCR real (19/11, S2): "PREPARAÇÃO PARA O J0G0" (zeros no lugar de O) e o "VS" lido como "-US" derrubavam o Pré-jogo para o Mercado
        // sempre que o 4º cartão dizia "VENDER JOGADORES": o rival, as forças e o árbitro daquele jogo nunca eram lidos. Zeros viram "o" nas
        // palavras-chave e basta "Jornada N" + "Árbitro" (o mercado e as listas não têm árbitro).
        val tz = t.replace('0', 'o')
        if (tz.contains("preparacao para o jogo") || tz.contains("preparacao para o jo") || (tz.contains("jogo rapido") && tz.contains("treino")) ||
            (jornada.containsMatchIn(t) && t.contains("arbitro"))
        ) return ScreenType.PREGAME
        if (t.contains("vender jogadores") || t.contains("lista de transferencias") ||
            (t.contains("olheiro") && t.contains("negociacoes"))
        ) return ScreenType.MARKET
        // Só o EDITOR da sua tática tem estes textos. Telas com Pressão/Temporização sem eles podem ser a
        // análise do rival (mesmos rótulos) e seguem para o leitor de relatório.
        if (t.contains("define a tua tatica") || t.contains("taticas por sector") || t.contains("tatica por sector")) {
            return ScreenType.TACTIC
        }
        // o editor ROLADO (sem esses títulos): controles Pressão/Estilo/Temporização com o número na caixa à direita, ou Marcação + fora-de-jogo
        if (Parsers.isTacticPanel(ocr)) return ScreenType.TACTIC
        if ((t.contains("capacidade") && t.contains("nivel")) || t.contains("renova o teu estadio")) return ScreenType.STADIUM
        if (jornada.findAll(t).count() >= 3) return ScreenType.CALENDAR
        if (t.contains("idade") && t.contains("valor") && t.contains("jogador")) return ScreenType.SQUAD
        // Lista rolada: o cabeçalho some, mas continuam as colunas de idade e valor.
        val moneyRight = ocr.tokens.count { it.xc > 0.85f && Money.extract(it.text) != null }
        val ages = ocr.tokens.count { tk ->
            val n = tk.text.toIntOrNull()
            n != null && n in 15..45 && tk.xc in 0.50f..0.60f
        }
        if (moneyRight >= 3 && ages >= 3) return ScreenType.SQUAD
        if (t.contains("preparacao para o jogo") || (t.contains("jogo rapido") && t.contains("treino"))) {
            return ScreenType.PREGAME
        }
        val reportWords = listOf("formacao", "marcacao", "desarme", "fora de jogo", "fora-de-jogo", "impedimento", "estilo de jogo")
        if (reportWords.count { t.contains(it) } >= 3) return ScreenType.REPORT
        return ScreenType.OTHER_OSM
    }
}
