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
