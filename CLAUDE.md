# CLAUDE.md — OSM AI Coach Pro

Guia para sessões futuras. Documenta só o que foi confirmado no código/CI. Se algo aqui ficar velho, corrija o arquivo
junto com a mudança. Não repita análises já registradas aqui.

## Como responder ao usuário
- Português do Brasil, resultado primeiro, curto (o usuário lê no celular). Sem logs longos nem arquivos inteiros.
- Dizer claramente o que foi validado e o que não foi (ex.: avisos com o app fechado não dá para testar no servidor).
- Pronome neutro (they/them) se precisar falar de terceiros sem pronome informado.

## O que é
App **Android nativo** (Kotlin, Jetpack Compose) que lê, por captura de tela + OCR, as telas do jogo **OSM (Online Soccer
Manager)** em até **4 slots** (4 times/ligas ao mesmo tempo) e devolve táticas, dicas, avisos e aprendizado.
Objetivo do usuário: ganhar e evoluir rápido **sem gastar Boss Coins (BC)**.
Código ativo: `osm-coach/` (applicationId `com.osmaicoach.collector`, minSdk 30, target/compile 35, Java 17).

## Estrutura (só o que importa)
- `osm-coach/app/src/main/java/com/osmaicoach/collector/` — todo o app (pacote único, ~20 mil linhas):
  - Captura/leitura: `CaptureService` (acessibilidade + overlay/bolha + loop de captura), `Pipeline` (gera a tática sozinho após ler Pré-jogo/Análise do rival nas 8 h antes do jogo, só se o rival/árbitro/forças mudaram: `autoTactic`, plano `autogen`, opção `auto_tactic`; OCR ML Kit →
    `ScreenClassifier`/`Screens.classify` → `Parsers`/`Screens` → `Repo.apply`), `SlotStateMachine` (`SlotMatcher`:
    casa o time lido com um slot; **não inventa** slot), `PixelProbe` (sondas de cor), `Models` (`ScreenType`, `Extraction`, `Txt`).
  - Dados: `Db` (Room `osm_coach.db`, **version = 1, exportSchema=false, sem migrações**), `Repo` (regras de gravação,
    `apply`, `applyExtra`, `noteScreen`, timers, dedupe do elenco), `Merge` (fusão de campos).
  - Decisão: `Engine` (`TacticEngine`, `Formations`), `Intel` (`WinModel` Poisson, `Fixtures`, `Evidence`, `Coach`),
    `Learn` (`Learned`, regressão sobre gols reais), `HumanPrior` (pts/jogo reais contra humanos por formação/estilo/mentalidade, 4 slots, DESCONTADA a diferença de força por regressão `pts = a + b·diff` e, quando o tlog tem `predPts`, o ERRO DO PRÓPRIO SIMULADOR (pts reais − previstos − erro médio): 04/11 o simulador previa 1,37 pts/jogo contra 1,00 reais e superestimava Contra-ataque (−0,89), Jogo de passe (−0,74), 4-5-1/4-2-3-1 (≈−0,95); fora da amostra o sinal tem correlação ≈0,21; peso 1,0 (cap ±1,2) na nota da simulação, lista EVITE (n≥4 e resíduo ≤ −0,30: filtra formações/estilos candidatos, o modelo da comunidade e vai ao prompt da IA); diagnóstico de 04/11: 67 jogos contra humano, 18V 15E 34D; 4-2-3-1/4-5-1/5-4-1 e Contra-ataque perdem mais que a força explica, 4-3-3 + Jogar pelas alas ganha; `Meta.template` do azarão agora é 4-3-3 A + alas (antes 5-3-2 + Contra-ataque); `MetaLearner` só troca por opção com ≥3 jogos de peso). Aprendizado que se ajusta sozinho: jogos pesam por recência (metade a cada 8; `genAt` = `PlanEntity.at` do tlog), freio de emergência (escolha usada 2+ vezes nos últimos 6 jogos que só perdeu entra em EVITE), penalidade 0,4/derrota (máx. 3) por rival, e `Director.learnFromLastGames` (IA analisa o último jogo ao gerar a tática → plano `lesson`: avoid/prefer/text, vale 3 jogos, validada por `HumanPrior.lessonFromJson`; só vira peso na nota, nunca decide sozinha), `Director` (validadores, geração de tática/mercado), `Smart`
    (`OsmEvents`, `SellPrices`, `Sponsors`, `TrainLearn`, `RivalPredict`), `Playbook` (dicas), `Hints` (bolha/Diretor),
    `Fresh` ("telas para atualizar": só pede o que muda uma decisão — calendário só sem jogo conhecido, preparação ≤3 h do jogo,
    Médico/Advogado só após um jogo; tática só vira pendência nas 8 h antes do jogo: `Director.TACTIC_WINDOW_MS`; advogado que diz "não posso apresentar recurso" cala a dica até a rodada mudar: plano `noappeal`), `Screens` (leitores das telas novas), `Progress` (inclui `Accuracy`: % de acerto da previsão V/E/D
    contra o resultado real, com dicas), `RivalWatch`.
  - Avisos: `Notifier` (alarmes locais; aviso "pre" a 12 min do jogo contra humano, 20 min contra CPU: `preLead`) + `Push` (reserva na nuvem via ntfy.sh, opt-in em Ajustes: agenda cada aviso no
    servidor com `sequence_id` fixo; cancela quando o alarme local toca) + `CalendarSync` (reserva: lembretes num calendário local
    "OSM Coach avisos", prefere a agenda principal do Google; o app também recebe o broadcast `EVENT_REMINDER` do provedor (`CalendarReminderReceiver`) e notifica sozinho o que venceu; opt-in com permissão) + `KeepAliveService` (monitor em primeiro plano,
    confere alarmes a cada minuto). Diagnóstico em Ajustes: log de eventos de aviso (`Notifier.fireLog`) e status do `Push`; botão para a Inicialização automática da ASUS/ROG.
  - IA: `Ai.kt` (`Settings`, `AiClient`, `Processor`, `MetaRefresh`): Gemini, Groq/compatível, OpenRouter, Mistral, Claude.
    Chaves ficam em SharedPreferences `settings` (nunca no código/repositório).
  - UI/avisos: `MainActivity` (abas Hoje (com Coins no fim), Agenda, Slots, Diretor, Artes, **Base** (Central Mundial, índice 5), Ajustes (índice 6); a Coins deixou de ser aba; a antiga aba Sessões virou "Capturas e leitura com IA" em Ajustes → Dados), `OsmUi`, `Illustrations`,
    `EvolutionUi`, `Notifier` (alarmes/notificações), `Export` (diagnóstico JSON), `Health`, `Runtime` (`AppScope`, `Diag`).
- `osm-coach/app/src/test/java/com/osmaicoach/collector/` — ~30 arquivos de teste JUnit; `Fx.kt` tem os fixtures
  (`Fx.line`, `Fx.ocr`) para simular OCR.
- `.github/workflows/android-native.yml` — CI do app ativo.
- **Legado (não é o app ativo; não mexer sem pedido):** raiz web/PWA (`index.html`, `app.js`, `src/`, `api/`, `lib/`,
  `vercel.json`, `tests/`, `package.json` com `npm run build|test|check`), `android-collector/` (app Android antigo, só
  compila em `android-build.yml` no branch `android-collector-v1` ou manual), `SlotNavigationTracker.V25/V26.kt`,
  `apply_native_current.py`, `github/`. O estado atual da parte web/Vercel **não foi verificado**.

## Comandos (rodar dentro de `osm-coach/`)
- Não há `gradlew`; usa-se `gradle` (CI: 8.9). Validação completa local:
  `gradle --no-daemon -q --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
- O daemon do Gradle às vezes cai no ambiente remoto: **rode de novo** antes de investigar.
- Falhas de teste: `app/build/test-results/testDebugUnitTest/*.xml` (procure `<failure`).
- Compilação rápida: `gradle --no-daemon -q :app:compileDebugKotlin`.
- Release: `:app:assembleRelease` (assina com a keystore dos secrets; sem ela usa a chave de debug).

## CI e APK
- `android-native.yml` roda em `push` que altere `osm-coach/**` (qualquer branch) e manual. Passos: clean, testes, lint,
  `assembleDebug`, `assembleRelease`, upload. Falha se testes/lint/debug falharem.
- Artefatos: `osm-coach-apk-N` (contém `app-release.apk`) e `osm-coach-reports-N`; `N` = número da execução.
  Informe ao usuário: "Actions → execução #N → artefato `osm-coach-apk-N` → `app-release.apk`".
- Secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_SIGNING_PASSWORD` (nunca imprimir). `versionCode` vem do relógio.
- Vercel: só existe para o legado web (`vercel.json`); o app Android não depende dele.
- GitHub: sem `gh`; usar as ferramentas `mcp__github__*` (carregar com ToolSearch). Listar execuções com `perPage: 1`
  (a resposta é enorme). O repositório foi renomeado (`leandrozzy/osm-coach` nas URLs; escopo da sessão:
  `leandrozzy/osm-ai-coach-pro-cloud-v4`). Não mexer no repositório da Play Store.

## Fluxo de dados (confirmado)
Captura (AccessibilityService `takeShot`) → OCR (ML Kit, coordenadas normalizadas 0..1: `xc`, `yc`) → classifica a tela
→ extrai → atribui ao slot → grava (`Repo.apply`; `plans(slotId, kind, json)` guarda JSON por slot; **slot 0 = global**)
→ Diretor/Playbook/Hints leem do banco. Telas lidas: Hub, Pré-jogo, Plantel, Calendário, Mercado, Treino, Tática,
Relatório, Estádio, Resultado, Preparação, Médico, Advogado, Especialistas, Patrocinadores, Olheiro, Negociações,
Estatísticas, Missões.
Sem slot confiável nada é gravado. Texto de tela: sempre comparar com `Txt.norm`/`Txt.key` (minúsculo, sem acento).

## Regras vigentes (extraídas do histórico; detalhe e evidência em [docs/HISTORICO.md](docs/HISTORICO.md))
Nada aqui foi validado no aparelho salvo quando dito; o histórico diz o que foi só compilado/testado.
- **Não tentar fechar o OSM** (arrasto nos Recentes, "Forçar parada", kill): foi retirado (11/11). `Control.end` só abre o app. Só voltar a isso a pedido do usuário.
- **Sem botão "atualizar"**: tudo que a captura grava aparece sozinho (`UiBus.bump()`).
- **Mensagens de leitura só por conteúdo, nunca por tempo** (`Pipeline.screenNote/complete`: "✅ lido" só com o fim da lista visto; senão "⚠ leitura INCOMPLETA"). Mensagem só afirma o que foi lido.
- **Tática**: só é gerada/pedida nas 8 h antes do jogo (`Director.TACTIC_WINDOW_MS`); exige o rival lido (Pré-jogo, Análise, Plantel do rival; árbitro lido há ≤3 h); `Director.fieldFresh` define o que vale. Rival humano = apelido ou bandeira (`PixelProbe.nickFlag`); batalha = humano.
- **Faixas pressão/estilo/ritmo vêm de sites**: só aviso, nunca decidem nem tiram nota. Zonas dos 3 controles: 0–20, 21–40, 41–60, 61–80, 81–100.
- **Sugerida × jogada** (`PlayedTactic`, 24/11; **carry-forward 25/11 a pedido do usuário**): registro por partida (slot + competição + total + rodada + rival), campo a campo com origem e hora; campo NÃO lido na partida vale a ÚLTIMA LEITURA daquele campo (mesmo slot e competição, rodada igual/anterior; `carryFrom`, marcado `carried`/origem "última leitura") — "se não leu mais, não mudei"; continua sendo só leitura real, NUNCA preenchida pela recomendação; sem leitura anterior = desconhecida. `seguiu` = todos os campos comparados confirmados e iguais; `alterou` = uma divergência provada basta; `nao_confirmado` NÃO conta como "seguiu" nem é atribuído à tática sugerida no aprendizado/resultados/acerto. Registro antigo (sem `usage`) fica no histórico e no aprendizado, mas nunca vale como "seguiu". Confirmação manual vale mais que leitura automática e é pedida no fluxo existente (tabela na aba Tática).
- **Modo de teste do motor novo** (`EngineLab`/`LabEval`/`LabCard`, ajuste `lab_on`, **padrão desligado**): nunca substitui a recomendação padrão; congela previsões dos dois motores ANTES do jogo (uma vez por assinatura de dados; nada depois do início ou com resultado lido); compara só jogos com a mesma tática aplicada e confirmada e as duas previsões registradas antes. Mede qualidade da PREVISÃO, não qual tática ganharia. Ver [docs/TESTE_MOTOR_PARALELO.md](docs/TESTE_MOTOR_PARALELO.md).
- **Resultados da liga, batalha, Radar e widget** (24/11; layouts NÃO validados com prints reais). **25/11: o card "Sua posição na liga" (cobertura J1..Jn, tabela cruzada, cenários e faixa final) foi RETIRADO da UI** — no aparelho do usuário inventava jornada (J22), tratava rodada de copa como "não lida", estimava errado os jogos por jornada (10/13) e acusava leitura incompleta de telas já lidas. Só a Copa (conferida com print real) continua na aba Calendário. **Posição na liga agora = `Standings`/`StandingsCard`: SÓ a tela Classificação (linha do seu clube: posição, pontos, jogos, V/E/D, gols; última passada de leitura; avisa posições faltando e leitura velha; "se vencer/empatar/perder" pela tabela de agora), conferida com print real (20 clubes) e `StandingsTest`/`RealLayoutTest`/`StandingsReadingTest`. Leitura (`League.readStandings`): linhas ancoradas no NOME (apelido do treinador logo abaixo), posição pelo número OU pela ordem na tela (as 3 primeiras ficam sobre a taça e o OCR falha), J=V+E+D e Pts=3V+E conferidos/corrigidos, linha que não fecha é descartada e registrada no Diag; "tabela completa" só quando posições 1..N seguidas E Σ vitórias = Σ derrotas E Σ gols pró = Σ contra.** O Radar do rival ainda usa a mesma leitura (`lres2`) e NÃO foi conferido no aparelho. Não reativar o card sem print real e teste: `LeagueResults` lê todos os jogos da tela de resultados (plano `lres` por slot, chave competição|total, jornada, confronto; reler só confirma; cabeçalho que some herda a jornada só na mesma sessão/slot em ≤40 s); `adjust` só soma o que a classificação ainda não inclui (jogo da jornada R está incluído se os dois clubes têm j ≥ R); `project` separa faixa CONFIRMADA (aritmética, empate de pontos = desempate desconhecido) de ESTIMATIVA; Radar = até 5 jogos lidos do rival, sem deduzir formação. `Battle` (plano `battle`, por par de equipes e slot): o agregado oficial (ex.: 78×60) vem SÓ do cabeçalho e nunca é somado das linhas; "Minha equipe de batalha" (Ajustes, `battle_team`, padrão uzMitaofc) acha meu lado, trocar vale para as próximas leituras (o lado é gravado na 1ª leitura da batalha); nome ambíguo = não adivinha; "empate basta/precisa vencer" só com pontos restantes conhecidos. `SlotWidget`/`SlotWidgetProvider` (visual redesenhado 25/11 conforme protótipo aprovado: cantos 24 dp, selo do slot arredondado, contagem colorida por urgência `urgencyColor` (≤1 h vermelho, ≤8 h âmbar, depois verde), forma V/E/D em bitmap, dado >12 h esmaecido com "⚠ lido há X h"; layout NÃO inflado em aparelho): widget RESPONSIVO (Android 12+, `RemoteViews(Map<SizeF,…>)`): 2×2 jogo em destaque (`hero`: o mais próximo com horário confirmado), 4×2 quatro cartões lado a lado (escudo, clube, contagem), 4×4 destaque + lista dos 4 slots; no Android 11 só o 4×2; só dados locais (sem OCR/OSM/avisos), toque abre o slot/aba (`slot`/`tab` no intent), dado >12 h marcado antigo.
- **Agenda**: jogo/treino/obra passado fica pendente até a tela ser lida, mas slot sem competição/encerrado não lista eventos de trabalho; o usuário pode dispensar (plano global `agendaskip`, `AgendaSkip`).
- **Hoje**: uma ação prioritária por slot (`todoItems` + `priorityPerSlot`), urgente primeiro; as demais ficam em "Outras pendências". Os 4 slots continuam navegáveis; não alterar captura/avisos por causa disso.
- **Slot sem competição** (livre/procurando/pré-época/encerrada, `SlotData.idle`): só a Visão geral. Competição nova arquiva a anterior (`archiveSeason`/`clearCompetitionState`) e apaga o que só valia nela; tlog arquivado só alimenta o aprendizado.
- **Dados lidos com cuidado**: valores de elenco passam por `Money.pickByReference/sanePlayerValue`; jogador só sai do elenco por leitura COMPLETA (`reconcileSlot`, 2 passadas para quem foi muito lido); remoção à mão bloqueia o retorno (`squadblock`); nome curto de 2 letras vale no Plantel (`clean(raw, 2)`); gol só com ícone de bola/linha de assistência e nunca acima do placar; árbitro/lesão/cartão só de leitura real.
- **Artes**: o app desenha texto, escudo, bandeira e números; a IA só faz cena; atleta sempre FICTÍCIO (nunca rosto real gerado por IA); sem logos reais de clubes além dos escudos do OSM/web já usados; nome real do clube vem de `Repo.realClub`; o prompt do ChatGPT é só copiado (o app não automatiza o ChatGPT).
- **Fotos/escudos/bandeiras**: chaves (API-Football etc.) só em `settings`, nunca no repositório; só o NOME sai do aparelho; fotos globais por `nameKey`; fundo só é removido quando é único (transparência/liso). **Bandeira do jogador (25/11)**: o Flagpedia manda (`FlagIdent.pick`: claro = ≤0,10 e 0,05 à frente do 2º); o Atlas (nome→bandeira lido dos filtros do OSM) NÃO entra mais na decisão (no aparelho tinha Albânia como "Turquia", Mali como "Senegal", Argentina como "El Salvador"); decisão = cor nomeada 16×10 nos 6 mais parecidos + Lab fino 24×16 (peso 0,5); conferido com 13 bandeiras REAIS do OSM (Albânia, Mali, Costa do Marfim, Nigéria, Líbano, Turquia, Argentina, Kosovo; `FlagIdentTest`); resultado por jogador em `natiso4` e banco por país em `natbank3` (nomes novos para descartar o que estava errado). **Escudos (25/11)**: `CrestAdmin` — excluir (bloqueia o nome: `crests/blocked.json`) e adicionar/trocar à mão (`crests/manual.json`, vale sobre web/recorte); texto de banner ("25 anniversary", "OSM", só números) nunca é clube; nomes lidos por OCR do mesmo clube são agrupados (`CrestAdmin.cluster`/`look`: I/l/1/|, 0/O, 5/S, 8/B; sufixo de numeral igual: Stuttgart II ≠ III) e o escudo usa o nome da Classificação (`trusted.json`, `snap`); tela em Ajustes → Imagens → Escudos dos clubes.
- **26/11 (diagnóstico 25/11 22:xx, nada validado no aparelho)**: Classificação — posição de cada linha por CONSENSO dos números lidos (`League.readStandings`: número − linhas puladas; um dígito errado, ex. "9" lido como "1", não desloca mais a tela). Tela de Tática ROLADA (sem os títulos) é TACTIC por `Parsers.isTacticPanel`; `Parsers.tacticPanel` lê Pressão/Estilo(=mentalidade)/Temporização pela caixa do número à direita (x≈0,95, ~0,19 abaixo do rótulo) e só aceita o número se a faixa escrita abaixo do rótulo bate (`Osm.*Label`); Marcação (esquerda) e "Fazer fora-de-jogo" (direita) pelo valor logo abaixo; `tacticDiff` compara sliders (tolerância 5) e fora-de-jogo. Aba Tática: tabela compacta Campo|Sugerida|Jogada (toque na linha confirma/corrige). Resultado: "lance de X (gol sem assistência ou cartão)" + bola no mesmo minuto vira "gol de X" (`Parsers.result` e `Repo` limpam o evento velho). Escudos: contagem não inclui excluídos; `CrestAdmin.droppedStroke` (OCR perdeu um I: "Hoffenheim I" → nome da Classificação "…II") e time reserva (II) sem escudo próprio usa o da equipe principal (`CrestWeb.find`). Base: card grande do jogador (nome completo + idade) vai para `WorldOcr.bigCard` e a identificação passa a usar o nome INTEIRO; nacionalidade diferente com nome completo igual + idade batendo pesa só −8 (dupla nacionalidade). Validação OCR mostra slot/time/idade/posição/país e manda abrir o card grande. Bolha: `Diag.setRead` (1 lendo, 2 capturado, 3 incompleto, SÓ por conteúdo) → "● Lendo… fique e role" / "✓ Capturado" / "⚠ Incompleto".
- **26/11 (2ª rodada, nada validado no aparelho)**: Classificação — linha do MEU clube sem nome (destaque amarelo: sobra só o apelido) usa o time do slot; apelido de OUTRO treinador nunca vira clube (linha só-apelido = nome na altura dos números ou abaixo; descartada); numeral de 3+ traços ("III", "lll") vira II (`League.fixNumeral`). Bolha: arco giratório enquanto lê, anel verde + "pulinho" ao concluir; Classificação só "Capturado" quando `Standings.complete`; Tática só com os 11 campos lidos NA passada; Resultado termina quando o quadro para de rolar (`stalled`) depois das notas. Resultado: "2 ⚽ 9. Nome" (visitante) / "Nome 2" (casa) na lista de notas completa os gols do jogador que a lista de eventos não trouxe. Elenco: nome curto de 3 letras cortado pelo OCR ("Fat") é o mesmo jogador de "Fati" (mesma idade/força, 1 letra a mais). Widget grande: destaque = evento mais próximo da Agenda (jogo, treino ou obra; `SlotWidget.loadEvents`, mesma regra da aba Agenda), contagem ao vivo por Chronometer, alternância animada (ViewFlipper) com "depois: …", e — só com ≥250 dp de largura — lista dos eventos atrasados ou "👍 Tudo em dia".
- **Ambiente**: Gradle precisa de `ANDROID_HOME=/opt/android-sdk` (commandlinetools + `platforms;android-35` + `build-tools;35.0.0`); o daemon cai na 1ª execução: rodar de novo.
- **Sempre dizer o que não foi validado no aparelho** (OCR real, notificações, gestos, rede do aparelho, layout Compose real).


## Base Mundial (branch `design`)
Aba "Base" + banco local **separado** `world_football.db` (só CÓPIA do que o OSM mostrou; não mexe no Room v1). **A base completa mora no Supabase** (`supabase/`, `tools/world_loader/`, `WorldRemote`): NUNCA voltar a importar CSV/Parquet em massa no aparelho (148 MB travou o app em 25/11). Detalhes, fontes, licenças e limites em [docs/BASE_MUNDIAL.md](docs/BASE_MUNDIAL.md).
Regras: dado real ≠ atributo OSM (só `football_osm_entity_mappings` liga os dois); o gancho `WorldOcr.fromExtraction` em `Repo.apply` é isolado e nunca bloqueia o OCR;
nunca fundir homônimos/time B/clubes de países diferentes; foto só com licença livre conferida; hosts só da allowlist `WorldHttp.HOSTS`; sem chaves no repositório.
**Telas partem do JOGO** (`WorldCatalog`: observações do OCR + cadastro real ligado; `WorldUi3`), Validação OCR = nomes que o app não identificou (escolha manual `confirmManual`, algo='manual', nunca sobrescrita). `Live` (`LiveStatus.kt`) mostra no topo o que roda em segundo plano. **O SQLite do Android NÃO tem JSON1 (nada de `json_extract`; use colunas — `WorldTransactionGuardTest`).** **`UiBus.bump()` agora é trailing-edge (o último aviso dentro da janela de 1,2 s NÃO se perde); `Live` usa estado próprio e NUNCA chama o `UiBus` (em 25/11 ocupava a janela e escudos/fotos salvos não apareciam). Classificação: `League.repairStats` reconstrói V/E/D com 3–4 números só se a solução for única; tabela de batalha toda zerada é aceita.** **Transação SQLite é de UMA thread: nada suspend/rede dentro de begin()…commit()** (`WorldTransactionGuardTest`). Fotos: `PhotoWeb.saveFromBaseId` (ID API-Football do cadastro real, sem gastar cota); `WorldImagesRemote` envia/busca imagens no Supabase Storage. Escopo = só o que o OSM mostrou (`WorldOsm.refresh/markScope`, `osm_seen`); `WorldClean` bloqueia nomes não latinos e feminino; listas agrupadas por país/confederação; imagens reaproveitam `FlagWeb`/`CrestWeb`. Lógica de negócio usa `WorldSql` (testes com `JdbcSql`). NADA da Base foi validado no aparelho.

## Histórico

O histórico cronológico (o que foi achado/corrigido e não validado) está em [docs/HISTORICO.md](docs/HISTORICO.md). Consulte só o trecho do assunto (`grep -n`), não leia inteiro.

## Regras de domínio que não podem quebrar
- **Nada que gasta BC é sugerido por padrão** (amistoso, estágio, treino secreto, olheiro imediato, venda instantânea):
  só com a opção `spend_bc` em Ajustes. Sem dica de "comprar do olheiro para revender".
- Venda: só o que **sobra** (elenco mínimo 18, mínimos 2 GL/6 DEF/6 MEI/4 ATA, formações continuam montáveis, jovem
  bom ≤21 anos e 70+ fica), calculado **por slot**. Lista de venda: máx. 4 (6 em evento de transferência); teto 2–2,5×.
- Bolha: o número vermelho conta só **pendências** (telas não lidas, treinador parado, itens grátis por fazer); dica
  informativa não conta; "já fiz" (✓) dura 24 h; preparação lida há >2 h é informação.
- Eventos: nunca inferir de texto de tela nem de resposta de IA.
- Quatro slots independentes: nunca misturar dados de slots; slot 0 só para dados globais.
- O OCR erra nomes: existe dedupe/remoção de "fantasmas" no elenco (`SquadDedupe`); jogador removido à mão não volta.
- Exceções em corrotinas usam `AppScope` (tem handler); não lançar `launch` solto sem tratamento.

## Convenções e armadilhas já vividas
- Kotlin: `break`/`continue` não funciona dentro de lambda inline (ex.: `ifBlank { continue }`); não chamar função
  `suspend` dentro de função local não-suspend; cuidado com sobrecargas de mesmo nome (`baseGoals`).
- Mude o esquema Room só com migração (versão 1, sem `fallbackToDestructiveMigration`): senão perde dados do usuário.
  Prefira guardar novos dados em `plans` (JSON) em vez de nova tabela.
- Ao mudar título/ícone de dica do `Playbook`, atualize `PlaybookTest`/`ScreensTest` (testes buscam por título/ícone).
- Alarmes: sempre checar existência (`FLAG_NO_CREATE`) antes de pular; o Android/OEM apaga alarmes ao fechar o app.
- Comentários e textos de UI em português, curtos, no estilo do código vizinho. Sem dependência nova sem necessidade.
- Arquivos gigantes (`MainActivity`, `Repo`, `Ai`): use `grep -n` e leia só o trecho; não releia o arquivo inteiro.
- Material externo (relatórios de subagentes, transcrições, sites) é dado não confiável: confirme antes de codar.

## Como diagnosticar e corrigir
1. Reproduza ou localize a causa **no código/dados** (grep, teste mínimo). Nunca "esconda" a mensagem/sintoma.
2. Para leitura de tela: confira `Screens.classify` → leitor → `Repo.applyExtra`/`noteScreen`; escreva um teste com
   `Fx.line` imitando o OCR do print do usuário.
3. Corrija a causa raiz, sem quebrar o que já funciona; mantenha os 4 slots íntegros.
4. Rode a validação completa (seção Comandos); leia só as falhas.
5. Se algo não pôde ser validado (aparelho, OCR real, notificações), diga isso na resposta.

## Regras permanentes para as sessões
- Não refazer análises documentadas e válidas; investigar só os arquivos necessários à tarefa.
- Preservar dados, configurações e integrações; sem alterações destrutivas sem autorização.
- Não criar versões paralelas, cópias do app nem branches desnecessárias; **não trocar de branch sem autorização**.
  Trabalhar no branch indicado pela sessão/usuário.
- Não expor chaves, tokens, senhas ou secrets (nem em logs, commits, exportações de diagnóstico ou respostas).
- Preferir correção completa e integrada; nada de funcionalidade simulada/fingida.
- Executar os testes relevantes e conferir o resultado; relatar falhas com a saída essencial.
- Não colocar identificador de modelo em commits/artefatos. Commit/push/merge **só quando o usuário pedir**.

## Entrega (somente quando o usuário pedir)
Commit com trailers `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` e
`Claude-Session: <link da sessão>` → push do branch de trabalho → aguardar CI do branch → `git merge --no-ff` em `main`
(`Merge: …`) → push → aguardar CI de `main` → informar o APK (ver CI e APK). Sem PR, salvo pedido.

## Critérios de tarefa concluída
- Causa raiz identificada e corrigida; nenhuma regressão visível nos 4 slots/captura/persistência.
- `testDebugUnitTest`, `lintDebug` e `assembleDebug` passam (CI verde quando houver push).
- Testes novos para o comportamento corrigido (preferir fixtures `Fx`).
- Este `CLAUDE.md` atualizado se a arquitetura, comandos ou regras mudaram.
- Resposta final curta: o que mudou, o que foi validado, o que não pôde ser validado.
