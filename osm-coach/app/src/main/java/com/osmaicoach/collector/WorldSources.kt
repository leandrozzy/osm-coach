package com.osmaicoach.collector

import android.content.Context

/**
 * Base Mundial — registro das FONTES (matriz de cobertura/licença/limite). Cada linha foi pesquisada em 25/11/26; o `status` só vira "operacional" depois de uma verificação REAL
 * (`WorldSync.verify`: requisição de teste feita pelo aparelho) e, para fontes de importação, depois de registros realmente gravados.
 */
object WorldSources {
    class Def(
        val id: String, val name: String, val site: String, val docs: String, val kind: String, val license: String, val credential: String, val quota: String, val categories: String, val notes: String,
        val probe: String? = null, val adapter: Boolean = false, val defaultStatus: String = "aguardando configuração"
    )

    const val OFB_CLUBS = "ofb_clubs"; const val REEP_TEAMS = "reep_teams"; const val REEP_COMP = "reep_competitions"; const val REEP_PEOPLE = "reep_people"
    const val FLAGS = "flagcdn"; const val WIKIDATA = "wikidata"; const val COMMONS = "commons"; const val APIF = "api_football"

    private const val RAW = "https://raw.githubusercontent.com"
    const val URL_OFB_CLUBS = "$RAW/florentfougeres/OpenFootballBase-Data/main/data/source/clubs_football.csv"
    const val URL_REEP_TEAMS = "$RAW/withqwerty/reep/main/data/teams.csv"
    const val URL_REEP_COMP = "$RAW/withqwerty/reep/main/data/competitions.csv"
    const val URL_REEP_PEOPLE = "$RAW/withqwerty/reep/main/data/people.csv"
    const val URL_FLAG_CODES = "https://flagcdn.com/en/codes.json"

    val ALL: List<Def> = listOf(
        Def(OFB_CLUBS, "OpenFootballBase — clubes do mundo (CSV)", "https://github.com/florentfougeres/OpenFootballBase-Data", "https://github.com/florentfougeres/OpenFootballBase-Data/blob/main/docs/en/data-model.md", "importação em massa (CSV, 9,8 MB)",
            "Dados: Wikidata (CC0) / repositório de dados CC BY-SA 4.0 (atribuição aos colaboradores da Wikipedia); código MIT. Logos Wikimedia: só ilustração.", "nenhuma", "sem cota (arquivo estático, ETag)", "clubes, ligas, estádios, fundação, IDs cruzados (Transfermarkt, FBref, Soccerway)",
            "42.888 clubes (36.162 com país ISO, 16.382 com liga). Arquivo dentro do repositório Git, baixado por raw.githubusercontent.com.", URL_OFB_CLUBS, true),
        Def(REEP_TEAMS, "Reep v0 — clubes (CSV)", "https://reep.football/", "https://github.com/withqwerty/reep", "importação em massa (CSV, 4,1 MB)", "CC0 1.0 (derivado do Wikidata)", "nenhuma", "sem cota", "clubes, país, fundação, IDs cruzados (30+ provedores)",
            "45.337 clubes. Arquivos v0 CONGELADOS (última versão 2026.25, 21/06/2026): servem como cadastro histórico; o v1 atual (DuckDB 321 MB) é grande demais para o aparelho.", URL_REEP_TEAMS, true),
        Def(REEP_COMP, "Reep v0 — competições (CSV)", "https://reep.football/", "https://github.com/withqwerty/reep", "importação (CSV)", "CC0 1.0", "nenhuma", "sem cota", "ligas, copas, torneios e IDs cruzados", "212 competições.", URL_REEP_COMP, true),
        Def(REEP_PEOPLE, "Reep v0 — jogadores (CSV)", "https://reep.football/", "https://github.com/withqwerty/reep", "importação em massa (CSV, 65,8 MB)", "CC0 1.0", "nenhuma", "sem cota (importa só em Wi‑Fi, retomável)",
            "jogadores: nome, nascimento, nacionalidade, posição, altura, IDs cruzados", "401.061 jogadores (392.156 com nascimento ou nacionalidade) + 43.646 treinadores (ignorados). Congelado em 2026.25.", URL_REEP_PEOPLE, true),
        Def(FLAGS, "Flagpedia / flagcdn — bandeiras", "https://flagpedia.net/", "https://flagpedia.net/download/api", "lista de países + imagens", "serviço gratuito; vetores da Wikimedia Commons (bandeiras de domínio público); atribuição recomendada", "nenhuma", "CDN gratuito (Cloudflare)", "países, territórios e bandeiras (PNG/SVG)",
            "A lista (codes.json) traz países e subdivisões (ex.: gb-eng). O app já guarda as bandeiras corretas em Ajustes → Imagens; nada é substituído.", URL_FLAG_CODES, true),
        Def(WIKIDATA, "Wikidata — enriquecimento (API/SPARQL)", "https://www.wikidata.org/", "https://www.wikidata.org/wiki/Wikidata:Data_access", "API + SPARQL (consultas pequenas, paginadas)", "CC0 1.0 (dados)", "nenhuma", "uso educado: 1 consulta/1,2 s, backoff em 429/503", "confederações, nomes alternativos, jogadores observados (nascimento, nacionalidade, posição), imagens",
            "O endpoint SPARQL aplica timeout em consultas pesadas: as consultas do app são curtas (VALUES em blocos).", "https://www.wikidata.org/w/api.php?action=query&meta=siteinfo&format=json", true),
        Def(COMMONS, "Wikimedia Commons — imagens e licenças", "https://commons.wikimedia.org/", "https://www.mediawiki.org/wiki/API:Main_page", "API (licença por arquivo)", "cada imagem tem a sua licença; só entram CC0/CC BY/CC BY-SA/domínio público", "nenhuma", "uso educado", "fotos e escudos com licença verificável e atribuição",
            "Foto de jogador só aparece depois de a licença do arquivo ser confirmada; sem licença livre, o espaço fica com a silhueta.", "https://commons.wikimedia.org/w/api.php?action=query&meta=siteinfo&format=json", true),
        Def(APIF, "API-Football (API-Sports) — complemento opcional", "https://www.api-football.com/", "https://www.api-football.com/documentation-v3", "API REST", "termos do provedor (plano gratuito: ~100 consultas/dia, confirmar no painel)", "chave opcional (a mesma de Ajustes → Imagens)", "reserva diária pequena para a Base (não disputa a cota das fotos)",
            "jogadores (perfil, elenco), clubes, ligas", "Sem chave a Base funciona só com fontes abertas. Nunca usa várias contas.", "https://v3.football.api-sports.io/status", false),
        Def("thesportsdb", "TheSportsDB (escudos)", "https://www.thesportsdb.com/", "https://www.thesportsdb.com/api.php", "API REST (chave pública de teste)", "termos próprios; API gratuita limitada; imagens de terceiros", "nenhuma (chave pública)", "~30 consultas/min", "escudos de clubes",
            "Já usada pelos escudos do app (CrestWeb). Não é fonte de identidade.", "https://www.thesportsdb.com/api.php", false, "atualizada"),
        Def("ofb_parquet", "OpenFootballBase — releases (Parquet)", "https://github.com/florentfougeres/OpenFootballBase-Data/releases", "https://github.com/florentfougeres/OpenFootballBase-Data/blob/main/docs/en/data-model.md", "arquivo tar.gz com Parquet", "CC BY-SA 4.0", "nenhuma", "sem cota",
            "elencos por temporada, 85 mil jogadores, carreiras", "AVALIADA, NÃO INTEGRADA: formato Parquet exige leitor pesado (parquet-mr/Hadoop) no Android e o download de releases do github.com não pôde ser verificado neste ambiente. Caminho futuro: converter no servidor para CSV.", null, false, "indisponível"),
        Def("transfermarkt", "Transfermarkt datasets (dcaribou)", "https://github.com/dcaribou/transfermarkt-datasets", "https://github.com/dcaribou/transfermarkt-datasets", "dataset derivado de scraping", "sem licença de dados compatível verificada (origem: scraping do Transfermarkt)", "—", "—", "—",
            "DESATIVADA POR LICENÇA: origem em scraping não autorizado; não importada.", null, false, "desativada por licença"),
        Def("football_data_org", "football-data.org", "https://www.football-data.org/", "https://www.football-data.org/documentation/quickstart", "API REST", "plano gratuito limitado (poucas competições, 10 consultas/min); termos próprios", "token gratuito", "10/min", "competições principais",
            "AVALIADA, NÃO INTEGRADA: precisa de token e cobre poucas ligas; não ajuda divisões inferiores.", null, false, "aguardando configuração"),
        Def("sportmonks", "Sportmonks", "https://www.sportmonks.com/football-api/", "https://docs.sportmonks.com/football", "API REST paga", "plano pago", "—", "—", "—", "DESATIVADA: serviço pago (a Base nunca ativa cobrança).", null, false, "desativada por licença"),
        Def("openfootball", "OpenFootball (football.db)", "https://github.com/openfootball", "https://openfootball.github.io/", "arquivos Football.TXT (domínio público)", "domínio público", "nenhuma", "sem cota", "jogos e resultados por país; lista de jogadores por país (pequena)",
            "AVALIADA, NÃO IMPORTADA: dados de partidas, não de identidades; os clubes já vêm dos CSVs acima.", null, false, "atualizada"),
        Def("fifa_confed", "FIFA e confederações (UEFA, CONMEBOL, Concacaf, AFC, CAF, OFC)", "https://inside.fifa.com/about-fifa/associations", "https://inside.fifa.com/", "referência institucional (HTML)", "conteúdo protegido; sem API ou licença de importação", "—", "—", "associações e hierarquia",
            "Só referência. A confederação de cada país é derivada do Wikidata (P463 + P17), com a proveniência registrada; não há scraping.", "https://inside.fifa.com/about-fifa/associations", false, "atualizada")
    )

    fun def(id: String) = ALL.firstOrNull { it.id == id }

    /** Garante uma linha por fonte (sem apagar contadores já gravados). */
    fun seed(db: WorldDb) {
        for (s in ALL) db.w.execSQL(
            "INSERT INTO football_sources(id,name,site,docs,kind,license,status,credential,quota,categories,notes) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,site=excluded.site,docs=excluded.docs,kind=excluded.kind,license=excluded.license,credential=excluded.credential,quota=excluded.quota,categories=excluded.categories,notes=excluded.notes",
            arrayOf(s.id, s.name, s.site, s.docs, s.kind, s.license, s.defaultStatus, s.credential, s.quota, s.categories, s.notes)
        )
        // fontes que nunca poderão rodar mantêm o status definido na pesquisa
        for (s in ALL) if (s.defaultStatus in setOf("desativada por licença", "indisponível")) db.w.execSQL("UPDATE football_sources SET status=?, enabled=0 WHERE id=?", arrayOf(s.defaultStatus, s.id))
    }

    fun status(db: WorldDb, id: String, status: String, message: String? = null) {
        db.w.execSQL("UPDATE football_sources SET status=?, notes=COALESCE(?, notes) WHERE id=?", arrayOf(status, message, id))
    }
}
