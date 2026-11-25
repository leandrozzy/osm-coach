# Redesign de UI/UX — inventário e matriz (25/11/2026)

**Base:** `desenvolvimento` @ `2dedff2` (SHA inicial; tag local `backup-pre-design-2dedff2`). **Branch de trabalho:** `design`. `main` (`17378fc`), backups e `desenvolvimento` não foram tocados.
O app executado é **Android nativo (Kotlin + Jetpack Compose)** em `osm-coach/`; não há CSS, PWA nem service worker no app ativo (a raiz web/PWA é legado e não foi tocada). O vídeo `2026-11-24-23-22-43.mp4` foi baixado e examinado por quadros (1 a cada 6 s): confere com o código (navegação de 6 abas, slots S1–S4 com 9 sub-abas).

## Regra do redesign
Só APARÊNCIA. Nenhuma regra, dado, OCR, captura, probabilidade, IA, notificação, widget (lógica), arte (geração) ou persistência foi alterado. Os desenhos do jogo (estilos, desarme, marcação, fora-de-jogo, árbitros), escudos, bandeiras, fotos, logo e ícones são os MESMOS arquivos e mecanismos.

## Design system (`Design.kt`, objeto `DS`; `C` aponta para ele)
| Token | Valor | Uso |
|---|---|---|
| BG / SURFACE / ELEVATED / BORDER | #0B111B / #151F2D / #1C2938 / #2C3B4D | fundo, cartões, elevado, bordas |
| TEXT / TEXT2 | #F4F7FB / #A8B7C8 | texto principal / secundário |
| LIME | #B9F363 | ação primária, aba/nav selecionada (texto escuro `ON_LIME` sobre ele) |
| CYAN / AMBER / RED / GREEN | #39BDF8 / #F5B942 / #EF5350 / #4CCB7A | análise / atenção / erro / ok |
| Raio / toque | 16 dp (cartões), 14 dp (controles), ≥ 48 dp | `osmPanel`, `CtaButton`, `GhostButton`, `ChipButton`, `Fold`, `SlotTabs` |
Cinco cores dos árbitros (azul, verde, amarelo, laranja, vermelho): inalteradas (`Referee`, `RefereeFace`, `PitchUi`).
Componentes tocados (todos herdam): `osmPanel` (sem brilho neon; borda 1 dp), `OsmAppBackground` (sólido), `Panel`, `Fold`, `Pill`, `Bar`, `PageTitle`, `StepHeader`, `CtaButton` (lima), `GhostButton`, `ChipButton`/`OsmButton`, `SlotTabs` (ativa = lima), navegação inferior (selecionada = lima), `BrandBar` (divisor sólido, logo real com descrição). Widget: mesmas cores (`res/drawable/widget_*`, `res/layout/widget_*`).

## Matriz tela → arquivo → ações → assets → regressão
| Tela / subtela | Arquivo principal | Ações preservadas | Assets reais | Regressão (teste existente) |
|---|---|---|---|---|
| Cabeçalho (BrandBar) | `MainActivity.kt` | status Online/Parado, versão | `g_logo.webp` | `DesignTest` |
| Navegação inferior (Hoje, Agenda, Slots, Diretor, Artes, Ajustes) | `MainActivity.kt` | troca de aba, selo "Artes (N)" | ícones Material | `EvolucaoTest` (prioridade por slot) |
| Hoje | `MainActivity.kt` (`TodayTab`, `TodoCard`, `SlotCardV2`) | abrir slot/aba, "Abrir OSM e capturar", pendências | escudos (`Crest`), bandeiras (`FlagBadge`) | `LigaBatalhaTest`, `EvolucaoTest` |
| Hoje → Treinos e estádio / Coins / Acerto / Resultados / O que o app aprendeu | `WorkUi.kt`, `CoinsUi.kt`, `Progress.kt`, `EvolutionUi.kt` | todas | — | `ProgressTest`, `AccuracyTest` |
| Agenda | `AgendaUi.kt` | dispensar, abrir OSM | escudos | `EvolucaoTest` |
| Slots (lista S1–S4) | `MainActivity.kt` (`SlotsTab`) | abrir slot | escudos, bandeiras | — |
| Slot → Visão geral / Pré-jogo / Elenco / Calendário / Tática / Resultado / Histórico / Diretor / Aprendizado | `MainActivity.kt`, `SlotSquadUi.kt`, `SlotTacticUi.kt`, `SlotResultUi.kt`, `PostGameUi.kt`, `LeagueUi.kt`, `PlayedTacticUi.kt`, `LabUi.kt` | todas (gerar tática, confirmar jogada, Radar, projeção da liga, batalha, modo de teste) | escudos, bandeiras, fotos (`PlayerPhoto`), `g_estilo*`, `g_desarme*`, `g_marcacao*`, `g_foraj*`, `g_arbitro_0..4` | `EngineTest`, `LigaBatalhaTest`, `EvolucaoTest`, `RivalWatchTest` |
| Elenco → detalhes do jogador | `PlayerDetailUi.kt`, `PlayerTools.kt` | foto, país, comparar, preço, remover | fotos, bandeiras | `PhotoFitTest`, `PhotoWebTest` |
| Diretor | `MainActivity.kt` (`DirectorTab`), `DirectorChat.kt` | todas | escudos | `SmartTest` |
| Artes | `ArtsUi.kt`, `Arts*.kt` | criar, baixar, compartilhar, ChatGPT | escudos, fotos | `ArtsAiTest` |
| Ajustes (Avisos, Batalha e testes, Preferências, Imagens, Dados, Leitura, IA) | `SettingsUi.kt`, `ImageMgrUi.kt` | todas (chaves mascaradas) | escudos, bandeiras, fotos | `PhotoWebTest`, `CrestFlagTest` |
| Widget (2×2, 4×2, 4×4) | `SlotWidget.kt`, `res/layout/widget_*` | toque abre o slot | escudos | `LigaBatalhaTest` |
| Bolha/overlay sobre o jogo | `CaptureService.kt` | dicas por grupo | `g_icon.webp` | `ScreensTest` |

## Validação
- Executados: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` (resultado no relatório final) e `DesignTest` (contraste WCAG ≥ 7:1 no texto principal, ≥ 4,5:1 em secundário e acentos; alvo de toque ≥ 48 dp; raios 12–18 dp).
- **Não foi possível:** capturas de tela reais do app Android (sem emulador/aparelho neste ambiente) e os viewports 360/390/430/1440 do Playwright (o app não é web). O "antes" está no vídeo anexado; o "depois" precisa ser visto no aparelho com o APK do CI da branch `design`.

## Segunda etapa (protótipo aprovado v5) — `DesignKit.kt`
- Linguagem única de forma: cantos arredondados (sem cortes retos). Peças novas em `DesignKit.kt`: `HeroHeader`, `DsChip`, `DsSection`, `DayStrip`, `DsFilters`, `NowLine`, `MonthGrid`, `MatchLine`/`MatchLineNames`, `DsLegend`. `PageTitle` e `Title` passaram a usá-las.
- **Agenda** (`AgendaUi.kt`): faixa dos próximos 7 dias com pontos por tipo, filtros Tudo/Jogos/Treinos/Obras, card âmbar "passaram do horário" com Dispensar, linha AGORA, linha do tempo; jogo com escudo do seu time (`Crest`) e do rival (`RivalShield`). Mesmos dados e mesmas regras (`AgendaSkip`, `open`).
- **Calendário do slot** (`SlotSquadUi.kt` → `CalendarOverview`): próximo jogo com os dois escudos, grade do mês com pontos V/E/D/a jogar/copa, próximos jogos e últimos resultados com escudos; classificação (`PointsChart`) e jogos da copa (`LeagueUi.kt`) com escudo de cada clube. A lista de rodadas e a posição na liga seguem como antes.
- **Cabeçalho do slot** (`SlotScreen`): faixa arredondada com escudo, bandeira e % lido.
- **Árbitro** (`RefereeMeter`): rosto real `g_arbitro_0..4` com contorno na cor do nível (antes `RefereeFace` não era usado); desarme/marcação/fora-de-jogo/estilo seguem na Tática.
- **Bolha** (`CaptureService.updateRing`): anel lima (parada), ciano pulsando + selo "● Capturando" (captura nos últimos 5 s), âmbar (pendências); cartão e botões nas cores do sistema.
- Testes novos: `DesignKitTest` (`parseDm`, `monthName`, `ptDow`).
- Não validado no aparelho: layout Compose real, escudos reais (vêm do OSM/web em tempo de execução), o selo "Capturando" e a animação da bolha.

## Terceira etapa — Hoje, Slots, Pré-jogo, Diretor
- **Hoje** (`TodayTab`): `HeroHeader` com a data, `NextGameHero` (próximo jogo entre os slots, escudos e contagem), botão lima "Abrir OSM e capturar" (vermelho "Encerrar" com captura ativa), grade "Meus slots" (`SlotTile`), "Prioridades do dia" (`TodoCard` em cartões por tom, mesma lista `todoItems/priorityPerSlot`) e o detalhe dos slots logo abaixo.
- **Slots / SlotCardV2**: título maior e anel `Ring` com o progresso da rodada; veredito V/E/D abaixo.
- **Visão geral** (`MatchupHero`): fundo de destaque `dsHero`. **Pré-jogo**: `DuelBanner` no topo (escudos, FALTAM, forma) e botão ciano da análise com IA.
- **Tema**: `Shapes` do Material em 12–28 dp (nenhum canto reto); `OsmTitle` com barra lima.
- Diretor, Artes, Ajustes, Elenco, Resultado, Histórico, Aprendizado herdam cabeçalho, títulos, cartões, chips e botões novos; o conteúdo e as regras não mudaram.

## Quarta etapa — todas as telas restantes
- **Botões** (`DesignKit.kt`): `Button` e `OutlinedButton` do pacote substituem os do Material em todo o app (lima/contorno lima, 14–16 dp, mínimo 48 dp). `PanelTitle` nos títulos de painel; `SectionLabel` (Ajustes) com barra lima; `SwitchRow` com 48 dp e cores do sistema.
- **Resultado**: placar com escudo dos dois times e fundo na cor do resultado (`dsHero`). **Batalha**: placar oficial em destaque. **Elenco**: filtros 44 dp.
- Tática, Histórico, Aprendizado, Diretor, Artes, Ajustes: mesma estrutura de dados, agora com os componentes novos (cantos, botões, títulos, chips).

## Quinta etapa — alinhamento com o protótipo v5 (comparado com o vídeo do app)
- **Tipografia**: Barlow (texto) e Barlow Semi Condensed ExtraBold (títulos e números), embutidas em `res/font` (licença OFL; convertidas dos pacotes `@fontsource`). `AndroidFont.kt`.
- **Ícones**: os 39 ícones de traço do protótipo (`DsIcons.kt`, `DsIcon`/`DsTile`); `iconFor` troca emojis de dicas/pendências por eles (sem equivalente, mantém o emoji). Usados na barra inferior, abas do slot, cabeçalhos, Prioridades, Agenda, Fold e dicas.
- **Cabeçalho do app**: "OSM AI COACH / TÁTICO E ANALÍTICO". **Cabeçalho de página**: marca-d'água do título. **Barra dos 4 slots** no topo do slot (`SlotBar`, troca sem voltar à lista).
- **Hoje**: próximo jogo com escudos de 84 dp, contagem de 46 sp e fundo em dois tons. **Agenda**: pendência atrasada compacta (tempo + caminho + Dispensar).

## Sexta etapa — interação (pedido do usuário)
- **Hoje**: tocar num slot da grade o ESCOLHE (o card do jogo, as prioridades e o detalhe passam a mostrar só aquele slot; tocar de novo volta ao geral). Abrir o slot: tocar no card do jogo ou no "Detalhe dos slots". Slot sem jogo (batalha, livre, encerrado) mostra o estado e o botão "Abrir o slot".
- **Calendário do slot**: tocar num dia mostra os jogos daquela data (com escudos e placar/hora); setas ‹ › trocam o mês (limitadas ao primeiro e ao último mês com jogo lido) e "hoje" volta ao mês atual.

## Sétima etapa — correções de uso (vídeo/prints do aparelho)
- **Bolha**: lista do cartão limitada a 38 % da altura, aviso (`notice`) com no máximo 4 linhas, e o painel sobe sozinho se passar da tela (botões Tática/App/Encerrar sempre visíveis).
- **Fotos sem fundo** (PNG transparente): aparecem sobre branco no Elenco e no detalhe do jogador (antes, sobre o fundo escuro do cartão).
- **Slots**: trocar de slot na barra do topo mantém a aba (Elenco continua Elenco).
- **Abertura**: app aberto do zero começa sempre em Hoje (`onCreate` com `savedInstanceState == null`); girar a tela/memória e voltar do OSM mantêm o lugar.
- **Botão "App" da bolha**: abre o slot em que o OSM está (`Diag.currentSlot`) na aba atual (`Route.tab = -1` = manter).
