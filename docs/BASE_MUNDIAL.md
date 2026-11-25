# Central Mundial de Futebol (aba "Base")

Aba nova, imediatamente antes de Ajustes (índices: Hoje 0, Agenda 1, Slots 2, Diretor 3, Artes 4, **Base 5**, Ajustes 6).
Dados REAIS de países/competições/clubes/jogadores ficam num banco **separado e aditivo** (`world_football.db`); o
`osm_coach.db` (Room v1, sem migração) **não foi alterado**. Atributos do OSM (camada OSM) nunca se misturam com a camada
real; a ligação entre as duas é só a tabela de mapeamentos (camada de mapeamento).

## Modelo atual: base no SUPABASE (revisão 25/11 tarde)
A importação em massa no aparelho (148 MB, travava o app) foi REMOVIDA. A base completa (países, competições, clubes, jogadores) mora num projeto Supabase gratuito:
- `supabase/schema.sql` (tabelas `wf_*`, RLS somente-leitura, busca por similaridade `wf_match_*`, cargas idempotentes `wf_upsert_*` só para `service_role`) e `supabase/README.md` (passo a passo).
- `tools/world_loader/loader.py` + `.github/workflows/world-loader.yml`: baixam as fontes abertas, limpam (alfabeto latino, sem feminino), juntam por QID e gravam por RPC; só linhas novas/alteradas mudam (`row_hash`, `updated_at`). Secrets do GitHub: `SUPABASE_URL`, `SUPABASE_SERVICE_KEY`.
- App (`WorldRemote`): URL + chave **anon** em Ajustes (nunca no repositório; diagnóstico mascara `*_key`). Lê o OCR → pede ao Supabase só os candidatos daquele nome → guarda cópia local pequena (só o que o OSM mostrou) → identifica (`WorldIdentity`, inalterado) → 1x/dia pergunta só o que mudou (`updated_at`). Sem rede, a leitura fica "nova" e é avaliada depois (nunca vira "desconhecido" por falta de candidatos).
- Migração única (`WorldClean.slim`): apaga da cópia local o que veio da importação antiga e o OSM nunca mostrou, e compacta o arquivo.
- O OCR grava as observações numa fila própria de baixa prioridade (`WorldOcr.submit`): a captura nunca espera o banco da Base.
Não validado: o Supabase real (não há projeto/credenciais neste ambiente), a 1ª carga completa e o tempo de resposta no aparelho.

## Foco no OSM (revisão 25/11)
A aba mostra **só o que o OSM já exibiu ao app** (padrão "Só o que está no OSM"; "Toda a base mundial" é opcional). `WorldOsm.refresh` lê os elencos,
adversários, competição do slot, bandeiras de jogadores (`natiso4`) e o atlas de ligas do app (somente leitura do Room), cria observações, identifica e marca `osm_seen`
em países/competições/clubes/jogadores. Países/ligas/clubes sem relação com o OSM não aparecem. `WorldClean` descarta nomes fora do alfabeto latino, com "?"/"�" e
todo futebol feminino (na importação e numa limpeza única do que já estava no banco, `world_clean_v1`). Imagens: bandeira (`FlagWeb`), escudo (`CrestWeb`) e foto (Commons) são pedidas só para o escopo do OSM, com barra de progresso.
Dados manuais do usuário (escudos/bandeiras em `crests/manual.json`, `FlagManual`) não são tocados; a Base nunca sobrescreve nada do Room. Banco `world_football.db` versão 2 (coluna `countries.osm_seen`, ALTER aditivo).

## Arquitetura
| Arquivo (`com.osmaicoach.collector`) | Papel |
|---|---|
| `WorldSchema/WorldDb/WorldSql/Cur.java` | 22+ tabelas `football_*`; `WorldSql` abstrai o SQLite (Android `AndroidSql`, testes `JdbcSql`) |
| `WorldNorm` / `WorldIdentity` | normalização (`clubKey` com marcador II/B/U19/W, `playerKey`), Jaro-Winkler/Levenshtein, motor de identidade com sinais, margem e contradições |
| `WorldCsv/WorldHttp/WorldImport/WorldSources/WorldSync` | importação em fluxo com checkpoint por byte (`Range`), ETag, lotes de 2000, upserts idempotentes, fila, backoff, circuit breaker (3 falhas → 6 h), só Wi‑Fi para arquivos grandes (`world_mobile_data`) |
| `WorldWikidata/WorldCommons` | confederações (P463+P17), enriquecimento limitado (12/dia), fotos só com licença livre conferida por arquivo |
| `WorldOcr` | ponte do OCR existente: `Repo.apply` → observações → avaliação → mapeamento por (tipo, chave, slot); desfazer/reabrir sem perder observações |
| `WorldQuery/WorldCoverage/WorldMap` | consultas paginadas no servidor (40 linhas), cobertura, mapa (Natural Earth 1:110m, asset `world_map.json`) |
| `WorldUi/WorldUi2` | 9 seções: Visão Geral, Países, Competições, Clubes, Jogadores, Validação OCR, Cobertura, Sincronização, Fontes |

O OCR **não é substituído nem bloqueado**: o gancho em `Repo.apply` roda isolado (`runCatching`); falha da Base nunca afeta a leitura.
Lógica tática não foi tocada. Agendamento: `KeepAliveService` chama `WorldSync.tick` a cada 10 min.

## Identidade (faixas heurísticas, não probabilidades)
98–100 muito alta · 90–97 provável · 75–89 ambígua · <75 não resolvida. "Confirmado" exige score ≥ muito alta **e** evidência
além do nome (nacionalidade, idade, posição, país/liga do clube ou 2+ capturas independentes — `capture_key` = tela + hora)
**e** margem sobre o 2º candidato. Nunca une homônimos, time B/II/sub‑19 com a principal, nem clubes de países diferentes.
Nome sem correspondência vira registro provisório "desconhecido" (`football_unresolved_entities`); nada é inventado.
Nacionalidade do jogador vem da bandeira já identificada pelo app (plano `natiso4`).

## Fontes
| Fonte | Uso | Licença | Estado |
|---|---|---|---|
| OpenFootballBase `clubs_football.csv` (raw.githubusercontent.com/florentfougeres/OpenFootballBase-Data) | clubes | CC BY-SA 4.0 | integrada |
| Reep v0 `people/teams/competitions.csv` (raw.githubusercontent.com/withqwerty/reep) | jogadores, clubes, competições | CC0 | integrada (congelada em 2026.25) |
| flagcdn `codes.json` / imagens | países, bandeiras | uso livre | integrada |
| Wikidata API/SPARQL | ids, confederações, enriquecimento | CC0 | integrada; SPARQL instável, consultas curtas |
| Wikimedia Commons | fotos | por arquivo (só licença livre é exibida) | integrada |
| Natural Earth via world-atlas | mapa | domínio público/ISC | embutida |
| API-Football | extras | gratuito com chave | opcional: "aguardando configuração" sem chave (`apifootball_key` em Ajustes) |
| OpenFootballBase Parquet | — | — | avaliada; sem leitor Parquet, não integrada |
| football-data.org | — | token, poucas ligas | avaliada, não integrada |
| Transfermarkt datasets | — | raspagem | **desativada** (licença) |
| Sportmonks | — | paga | **descartada** |
| FIFA/confederações | referência | — | só referência |

Allowlist de hosts em `WorldHttp.HOSTS` (bloqueio de SSRF: só https, só hosts listados). Nenhuma chave no repositório.

## Limites conhecidos
- Reep v0 é congelada; elencos e clubes por temporada **não** foram importados (sem fonte CSV aberta integrada).
- Confederações via Wikidata não foram verificadas ponta a ponta (WDQS instável no ambiente de teste).
- O mapa não desenha micro‑estados (a lista cobre).
- Foto de atleta só aparece com licença livre conferida; nunca gerada nem inventada.
- **Nada foi validado no aparelho**: tempo/tamanho da importação, agendamento, UI, gancho com capturas reais e fluxo de fotos. Uma fonte só é "operacional" depois de `WorldSync.verify` no aparelho.

## Testes
`WorldIdentityTest`, `WorldSchemaTest`, `WorldImportTest`, `WorldQueryTest`, `WorldOcrTest` (JVM, SQLite real via `sqlite-jdbc` só de teste,
amostras reais em `app/src/test/resources/world/`).

## Recuperação
Tag `recovery/design-pre-base-mundial-ba4c60f`. Reverter = voltar ao commit `ba4c60f`; o `world_football.db` pode ser apagado sem afetar o resto do app.

## Atribuições
Wikidata e Reep (CC0); OpenFootballBase (CC BY-SA 4.0, florentfougeres); Natural Earth/world-atlas (ISC); bandeiras flagcdn.
