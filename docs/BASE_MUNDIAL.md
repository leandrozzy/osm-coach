# Central Mundial de Futebol (aba "Base")

Aba nova, imediatamente antes de Ajustes (índices: Hoje 0, Agenda 1, Slots 2, Diretor 3, Artes 4, **Base 5**, Ajustes 6).
Dados REAIS de países/competições/clubes/jogadores ficam num banco **separado e aditivo** (`world_football.db`); o
`osm_coach.db` (Room v1, sem migração) **não foi alterado**. Atributos do OSM (camada OSM) nunca se misturam com a camada
real; a ligação entre as duas é só a tabela de mapeamentos (camada de mapeamento).

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
