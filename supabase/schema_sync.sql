-- Central Mundial — ATUALIZAÇÃO AUTOMÁTICA dentro do Supabase. Rode UMA vez no SQL Editor (depois do schema.sql); é idempotente.
-- A função world-sync (supabase/functions/world-sync) busca Wikidata e API-Football sozinha e grava aqui com estas funções.
-- Só ACRESCENTA ou completa campos vazios: nunca apaga nem sobrescreve o que o carregador (Reep/OpenFootballBase) já gravou.

create table if not exists wf_sync_state(
  task text primary key, cursor text, info jsonb not null default '{}'::jsonb, updated_at timestamptz not null default now());

-- fila de nomes que o app NÃO conseguiu identificar (o app só insere; a função busca e marca como feito)
create table if not exists wf_wanted(
  id bigint generated always as identity primary key, kind text not null check (kind in ('club','player')),
  name text not null, name_fold text not null, iso2 text, age int, pos text,
  status text not null default 'novo', tries int not null default 0, created_at timestamptz not null default now(), tried_at timestamptz,
  unique (kind, name_fold));
alter table wf_wanted enable row level security;
drop policy if exists wf_wanted_ins on wf_wanted;
create policy wf_wanted_ins on wf_wanted for insert to anon, authenticated
  with check (status = 'novo' and tries = 0 and length(name) between 2 and 80 and length(name_fold) between 2 and 80);
grant insert on wf_wanted to anon, authenticated;
alter table wf_sync_state enable row level security;     -- sem política: só a função (service_role) lê/grava

create index if not exists wf_players_wd on wf_players(wikidata) where wikidata is not null;
create index if not exists wf_players_apif on wf_players(apif_id) where apif_id is not null;
create index if not exists wf_clubs_wd on wf_clubs(wikidata) where wikidata is not null;

-- ---------- clubes: acha por chave, por Wikidata ou por (nome, país, categoria); só preenche o que falta ----------
create or replace function wf_merge_clubs(rows jsonb) returns jsonb language plpgsql security definer set search_path = public as $$
declare ins int := 0; upd int := 0; skip int := 0; r jsonb; cid bigint; before text; after text;
begin
  for r in select * from jsonb_array_elements(rows) loop
    select id into cid from wf_clubs where key = r->>'key';
    if cid is null and r->>'wikidata' is not null then select id into cid from wf_clubs where wikidata = r->>'wikidata' limit 1; end if;
    if cid is null then select id into cid from wf_clubs where name_fold = r->>'name_fold' and marker = coalesce(r->>'marker','') and coalesce(iso2,'') = coalesce(r->>'iso2','') limit 1; end if;
    if cid is null then
      insert into wf_clubs(key,wikidata,name,short_name,name_fold,marker,iso2,city,founded,stadium,category,prominence,aliases,alias_fold,row_hash)
      values (r->>'key', r->>'wikidata', r->>'name', r->>'short_name', r->>'name_fold', coalesce(r->>'marker',''), r->>'iso2', r->>'city', r->>'founded', r->>'stadium', r->>'category',
              coalesce(nullif(r->>'prominence','')::int,1), coalesce(array(select jsonb_array_elements_text(r->'aliases')),'{}'), coalesce(array(select jsonb_array_elements_text(r->'alias_fold')),'{}'), 'sync')
      on conflict (key) do nothing;
      ins := ins + 1;
    else
      select md5(concat_ws('|',wikidata,city,founded,stadium,array_to_string(alias_fold,','))) into before from wf_clubs where id = cid;
      update wf_clubs set wikidata = coalesce(wikidata, r->>'wikidata'), city = coalesce(city, r->>'city'), founded = coalesce(founded, r->>'founded'), stadium = coalesce(stadium, r->>'stadium'),
        iso2 = coalesce(iso2, r->>'iso2'), prominence = greatest(prominence, coalesce(nullif(r->>'prominence','')::int,0)),
        aliases = array(select distinct x from unnest(aliases || coalesce(array(select jsonb_array_elements_text(r->'aliases')),'{}')) x),
        alias_fold = array(select distinct x from unnest(alias_fold || coalesce(array(select jsonb_array_elements_text(r->'alias_fold')),'{}')) x)
       where id = cid;
      select md5(concat_ws('|',wikidata,city,founded,stadium,array_to_string(alias_fold,','))) into after from wf_clubs where id = cid;
      if before is distinct from after then update wf_clubs set updated_at = now() where id = cid; upd := upd + 1; else skip := skip + 1; end if;
    end if;
  end loop;
  return jsonb_build_object('inserted', ins, 'updated', upd, 'unchanged', skip);
end $$;

-- ---------- jogadores: por Wikidata, por apif_id, por reep_id ou por (nome, ano de nascimento, país); só preenche o que falta ----------
create or replace function wf_merge_players(rows jsonb) returns jsonb language plpgsql security definer set search_path = public as $$
declare ins int := 0; upd int := 0; skip int := 0; r jsonb; pid bigint; before text; after text;
begin
  for r in select * from jsonb_array_elements(rows) loop
    pid := null;
    if r->>'wikidata' is not null then select id into pid from wf_players where wikidata = r->>'wikidata' limit 1; end if;
    if pid is null and r->>'apif_id' is not null then select id into pid from wf_players where apif_id = r->>'apif_id' limit 1; end if;
    if pid is null then select id into pid from wf_players where reep_id = r->>'reep_id'; end if;
    if pid is null and nullif(r->>'birth_year','') is not null then
      select id into pid from wf_players where name_fold = r->>'name_fold' and birth_year = (r->>'birth_year')::int and coalesce(nationality_iso,'') = coalesce(r->>'nationality_iso','') limit 1;
    end if;
    if pid is null then
      insert into wf_players(reep_id,wikidata,name,full_name,name_fold,tail1,tail2,dob,birth_year,nationality,nationality_iso,position,height_cm,tm_id,apif_id,row_hash)
      values (r->>'reep_id', r->>'wikidata', r->>'name', r->>'full_name', r->>'name_fold', r->>'tail1', r->>'tail2', r->>'dob', nullif(r->>'birth_year','')::int, r->>'nationality', r->>'nationality_iso',
              r->>'position', nullif(r->>'height_cm','')::int, r->>'tm_id', r->>'apif_id', 'sync')
      on conflict (reep_id) do nothing;
      ins := ins + 1;
    else
      select md5(concat_ws('|',wikidata,full_name,dob,nationality_iso,position,height_cm,tm_id,apif_id)) into before from wf_players where id = pid;
      update wf_players set wikidata = coalesce(wikidata, r->>'wikidata'), full_name = coalesce(full_name, r->>'full_name'), dob = coalesce(dob, r->>'dob'), birth_year = coalesce(birth_year, nullif(r->>'birth_year','')::int),
        nationality = coalesce(nationality, r->>'nationality'), nationality_iso = coalesce(nationality_iso, r->>'nationality_iso'), position = coalesce(position, r->>'position'),
        height_cm = coalesce(height_cm, nullif(r->>'height_cm','')::int), tm_id = coalesce(tm_id, r->>'tm_id'), apif_id = coalesce(apif_id, r->>'apif_id') where id = pid;
      select md5(concat_ws('|',wikidata,full_name,dob,nationality_iso,position,height_cm,tm_id,apif_id)) into after from wf_players where id = pid;
      if before is distinct from after then update wf_players set updated_at = now() where id = pid; upd := upd + 1; else skip := skip + 1; end if;
    end if;
  end loop;
  return jsonb_build_object('inserted', ins, 'updated', upd, 'unchanged', skip);
end $$;

-- ---------- status legível pelo app (chave anon): o que já rodou, fila, última atualização ----------
create or replace function wf_sync_status() returns jsonb language sql stable security definer set search_path = public as $$
  select jsonb_build_object(
    'wanted_pending', (select count(*) from wf_wanted where status = 'novo'),
    'wanted_done', (select count(*) from wf_wanted where status = 'feito'),
    'tasks', coalesce((select jsonb_object_agg(task, jsonb_build_object('cursor', cursor, 'at', updated_at, 'info', info)) from wf_sync_state), '{}'::jsonb));
$$;

grant execute on function wf_merge_clubs(jsonb), wf_merge_players(jsonb) to service_role;
grant execute on function wf_sync_status() to anon, authenticated;
