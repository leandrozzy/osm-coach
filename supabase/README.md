# Supabase da Central Mundial — passo a passo (gratuito)
1. Crie um projeto em supabase.com (plano Free: 500 MB de banco; esta base cabe).
2. **SQL Editor** → cole o conteúdo de `schema.sql` → Run.
3. **Project Settings → API**: copie a *Project URL* e as chaves **anon** (vai para o app) e **service_role** (SÓ para o carregador).
4. GitHub → repositório → *Settings → Secrets and variables → Actions*: crie `SUPABASE_URL` e `SUPABASE_SERVICE_KEY`.
5. *Actions → world-loader → Run workflow* (escolha o branch `design`). A 1ª carga leva de dezenas de minutos a poucas horas (400 mil jogadores); as seguintes só gravam o que mudou.
6. No app: Base → Fontes de Dados → Configurar → cole a URL e a chave **anon** → Testar conexão.
Nunca coloque a chave `service_role` no app, no repositório ou em prints.

## Imagens (atualização do schema)
O `schema.sql` agora também cria a tabela `wf_images` e o bucket público `wf-images` (somente inserção pela chave do app: png ≤ 300 KB, caminho `flags|crests|photos/<chave>.png`, sem sobrescrever nem apagar).
Se você já rodou o schema antes: cole o `schema.sql` de novo no SQL Editor e rode (é idempotente). O app envia as imagens que já usa e busca lá antes de baixar da internet.


## Atualização automática dentro do Supabase (Edge Function `world-sync`)
O Supabase passa a buscar fontes sozinho, todo dia, de forma incremental (só acrescenta ou completa campos vazios; nunca apaga):
- **Wikidata** (aberto): clubes e jogadores ATIVOS (só masculino), país por país, retomando de onde parou.
- **API-Football** (chave sua, guardada como *secret* no Supabase, nunca no app): resolve a fila de nomes que o app não identificou (`wf_wanted`), até 90 consultas/dia.
- O app envia essa fila (só nome, idade e posição) e chama a função 1x/dia sozinho; o botão **Base → Sincronização → Atualizar fontes na nuvem** chama na hora.

Passo a passo (uma vez só):
1. SQL Editor → cole `schema_sync.sql` → Run.
2. Painel → *Edge Functions* → *Deploy a new function* → *Via Editor* → nome `world-sync` → cole `functions/world-sync/index.ts` → Deploy. (Desligue "Verify JWT" só se o app/ cron receberem 401; com a chave anon ele funciona ligado.)
3. *Edge Functions → Secrets* → `API_FOOTBALL_KEY` = sua chave da API-Football (https://dashboard.api-football.com). `SUPABASE_URL` e `SUPABASE_SERVICE_ROLE_KEY` já existem sozinhas.
4. SQL Editor → cole `cron.sql` trocando `<PROJECT_REF>` e `<ANON_KEY>` → Run (agenda 05:17 UTC por dia).
5. Teste: no app, Base → Sincronização → *Atualizar fontes na nuvem*; ou *Edge Functions → world-sync → Invoke*. A resposta mostra `clubs`, `players` e `apif`.
A função tem trava de 8 min entre execuções (o botão público não consegue sobrecarregar a fonte).
