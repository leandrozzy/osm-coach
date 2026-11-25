# Supabase da Central Mundial — passo a passo (gratuito)
1. Crie um projeto em supabase.com (plano Free: 500 MB de banco; esta base cabe).
2. **SQL Editor** → cole o conteúdo de `schema.sql` → Run.
3. **Project Settings → API**: copie a *Project URL* e as chaves **anon** (vai para o app) e **service_role** (SÓ para o carregador).
4. GitHub → repositório → *Settings → Secrets and variables → Actions*: crie `SUPABASE_URL` e `SUPABASE_SERVICE_KEY`.
5. *Actions → world-loader → Run workflow* (escolha o branch `design`). A 1ª carga leva de dezenas de minutos a poucas horas (400 mil jogadores); as seguintes só gravam o que mudou.
6. No app: Base → Fontes de Dados → Configurar → cole a URL e a chave **anon** → Testar conexão.
Nunca coloque a chave `service_role` no app, no repositório ou em prints.
