-- Central Mundial de Futebol — banco no Supabase (projeto gratuito). Rode UMA vez no SQL Editor; é idempotente.
-- O app só LÊ (chave "anon", RLS somente-leitura). Quem GRAVA é o carregador (tools/world_loader), com a chave "service_role" (nunca vai para o app nem para o repositório).
create extension if not exists pg_trgm;

create table if not exists wf_countries(
  iso2 text primary key, iso3 text, name_en text not null, name_pt text, confederation text, flag_url text,
  row_hash text, updated_at timestamptz not null default now());

create table if not exists wf_competitions(
  id bigint generated always as identity primary key, key text unique not null, wikidata text, reep_id text,
  name text not null, name_fold text not null, iso2 text, level int, kind text,
  row_hash text, updated_at timestamptz not null default now());

create table if not exists wf_clubs(
  id bigint generated always as identity primary key, key text unique not null, wikidata text, reep_id text,
  name text not null, short_name text, name_fold text not null, marker text not null default '', iso2 text, city text, founded text, stadium text,
  category text, logo_url text, comp_key text, division text, aliases text[] not null default '{}', alias_fold text[] not null default '{}',
  row_hash text, updated_at timestamptz not null default now());

alter table wf_clubs add column if not exists prominence int not null default 0;

create table if not exists wf_players(
  id bigint generated always as identity primary key, reep_id text unique not null, wikidata text,
  name text not null, full_name text, name_fold text not null, tail1 text not null, tail2 text not null,
  dob text, birth_year int, nationality text, nationality_iso text, position text, height_cm int, tm_id text, apif_id text,
  row_hash text, updated_at timestamptz not null default now());

create table if not exists wf_runs(
  id bigint generated always as identity primary key, source text not null, started_at timestamptz not null default now(), finished_at timestamptz,
  rows_read bigint default 0, rows_changed bigint default 0, rows_skipped bigint default 0, status text, message text);

-- ===== imagens que o APP usa (bandeiras, escudos, fotos): o app envia cada imagem nova e busca daqui antes de baixar da internet =====
-- Bucket público de leitura; o app (chave anon) só pode INSERIR (png/jpg/webp até 300 KB, caminho flags|crests|photos/nome.png, sem sobrescrever nem apagar).
create table if not exists wf_images(
  id bigint generated always as identity primary key,
  kind text not null check (kind in ('flag','crest','photo')),
  key text not null check (key ~ '^[a-z0-9._-]{1,120}$'),
  path text not null, bytes int not null check (bytes between 100 and 307200), sha text, source text default 'app',
  created_at timestamptz not null default now(), unique(kind, key));
alter table wf_images enable row level security;
drop policy if exists wf_images_read on wf_images; create policy wf_images_read on wf_images for select to anon, authenticated using (true);
drop policy if exists wf_images_ins on wf_images; create policy wf_images_ins on wf_images for insert to anon, authenticated with check (path = case kind when 'flag' then 'flags/' when 'crest' then 'crests/' else 'photos/' end || key || '.png');
grant select, insert on wf_images to anon, authenticated;


create index if not exists wf_clubs_fold_trgm on wf_clubs using gin (name_fold gin_trgm_ops);
create index if not exists wf_clubs_alias on wf_clubs using gin (alias_fold);
create index if not exists wf_clubs_upd on wf_clubs(updated_at);
create index if not exists wf_players_fold_trgm on wf_players using gin (name_fold gin_trgm_ops);
create index if not exists wf_players_tail1 on wf_players(tail1);
create index if not exists wf_players_tail2 on wf_players(tail2);
create index if not exists wf_players_upd on wf_players(updated_at);
create index if not exists wf_comp_fold_trgm on wf_competitions using gin (name_fold gin_trgm_ops);
create index if not exists wf_comp_upd on wf_competitions(updated_at);

-- RLS: leitura pública (só dados abertos), nenhuma escrita para anon/authenticated.
alter table wf_countries enable row level security; alter table wf_competitions enable row level security;
alter table wf_clubs enable row level security; alter table wf_players enable row level security; alter table wf_runs enable row level security;
do $$ declare t text; begin
  foreach t in array array['wf_countries','wf_competitions','wf_clubs','wf_players','wf_runs'] loop
    execute format('drop policy if exists %I on %I', t||'_read', t);
    execute format('create policy %I on %I for select to anon, authenticated using (true)', t||'_read', t);
  end loop; end $$;

-- ===== busca (o app chama por GET; STABLE) =====
create or replace function wf_match_clubs(q text, lim int default 40) returns setof wf_clubs language sql stable as $$
  select * from wf_clubs
   where name_fold = q or alias_fold @> array[q] or name_fold % q or q = any(alias_fold)
      or (length(q) >= 4 and name_fold like q || '%')
   order by (name_fold = q or alias_fold @> array[q]) desc, similarity(name_fold, q) + least(prominence, 10) * 0.02 desc
   limit least(lim, 80);
$$;

-- p_full = nome inteiro dobrado; p_surname = sobrenome (pode ter partículas); p_last = última palavra
create or replace function wf_match_players(p_full text, p_surname text, p_last text, lim int default 120) returns setof wf_players language sql stable as $$
  select * from wf_players
   where name_fold = p_full or tail2 = p_surname or tail1 = p_last or tail1 = p_surname
   order by (name_fold = p_full) desc, similarity(name_fold, p_full) desc
   limit least(lim, 250);
$$;

create or replace function wf_match_competitions(q text, lim int default 20) returns setof wf_competitions language sql stable as $$
  select * from wf_competitions where name_fold = q or name_fold % q order by (name_fold = q) desc, similarity(name_fold, q) desc limit least(lim, 40);
$$;

create or replace function wf_stats() returns jsonb language sql stable as $$
  select jsonb_build_object(
    'countries', (select count(*) from wf_countries), 'competitions', (select count(*) from wf_competitions),
    'clubs', (select count(*) from wf_clubs), 'players', (select count(*) from wf_players), 'images', (select count(*) from wf_images),
    'last_run', (select to_jsonb(r) from (select source, finished_at, rows_read, rows_changed, status, message from wf_runs where finished_at is not null order by id desc limit 1) r));
$$;

-- ===== carga idempotente (só service_role): só muda a linha cujo conteúdo mudou (row_hash) e só então atualiza updated_at =====
create or replace function wf_upsert_clubs(rows jsonb) returns jsonb language plpgsql security definer set search_path = public as $$
declare ins int := 0; upd int := 0; skip int := 0; r jsonb; ex text; found_id bigint;
begin
  for r in select * from jsonb_array_elements(rows) loop
    select row_hash, id into ex, found_id from wf_clubs where key = r->>'key';
    if found_id is null then
      insert into wf_clubs(key,wikidata,reep_id,name,short_name,name_fold,marker,iso2,city,founded,stadium,category,logo_url,comp_key,division,prominence,aliases,alias_fold,row_hash)
      values (r->>'key', r->>'wikidata', r->>'reep_id', r->>'name', r->>'short_name', r->>'name_fold', coalesce(r->>'marker',''), r->>'iso2', r->>'city', r->>'founded', r->>'stadium', r->>'category', r->>'logo_url', r->>'comp_key', r->>'division', coalesce(nullif(r->>'prominence','')::int, 0),
        coalesce(array(select jsonb_array_elements_text(r->'aliases')), '{}'), coalesce(array(select jsonb_array_elements_text(r->'alias_fold')), '{}'), r->>'row_hash');
      ins := ins + 1;
    elsif ex is distinct from r->>'row_hash' then
      update wf_clubs set wikidata=r->>'wikidata', reep_id=r->>'reep_id', name=r->>'name', short_name=r->>'short_name', name_fold=r->>'name_fold', marker=coalesce(r->>'marker',''), iso2=r->>'iso2', city=r->>'city', founded=r->>'founded', stadium=r->>'stadium', category=r->>'category', logo_url=r->>'logo_url', comp_key=r->>'comp_key', division=r->>'division', prominence=coalesce(nullif(r->>'prominence','')::int, 0),
        aliases=coalesce(array(select jsonb_array_elements_text(r->'aliases')), '{}'), alias_fold=coalesce(array(select jsonb_array_elements_text(r->'alias_fold')), '{}'), row_hash=r->>'row_hash', updated_at=now() where id = found_id;
      upd := upd + 1;
    else skip := skip + 1; end if;
  end loop;
  return jsonb_build_object('inserted', ins, 'updated', upd, 'unchanged', skip);
end $$;

create or replace function wf_upsert_players(rows jsonb) returns jsonb language plpgsql security definer set search_path = public as $$
declare ins int := 0; upd int := 0; skip int := 0; r jsonb; ex text; found_id bigint;
begin
  for r in select * from jsonb_array_elements(rows) loop
    select row_hash, id into ex, found_id from wf_players where reep_id = r->>'reep_id';
    if found_id is null then
      insert into wf_players(reep_id,wikidata,name,full_name,name_fold,tail1,tail2,dob,birth_year,nationality,nationality_iso,position,height_cm,tm_id,apif_id,row_hash)
      values (r->>'reep_id', r->>'wikidata', r->>'name', r->>'full_name', r->>'name_fold', r->>'tail1', r->>'tail2', r->>'dob', nullif(r->>'birth_year','')::int, r->>'nationality', r->>'nationality_iso', r->>'position', nullif(r->>'height_cm','')::int, r->>'tm_id', r->>'apif_id', r->>'row_hash');
      ins := ins + 1;
    elsif ex is distinct from r->>'row_hash' then
      update wf_players set wikidata=r->>'wikidata', name=r->>'name', full_name=r->>'full_name', name_fold=r->>'name_fold', tail1=r->>'tail1', tail2=r->>'tail2', dob=r->>'dob', birth_year=nullif(r->>'birth_year','')::int, nationality=r->>'nationality', nationality_iso=r->>'nationality_iso', position=r->>'position', height_cm=nullif(r->>'height_cm','')::int, tm_id=r->>'tm_id', apif_id=r->>'apif_id', row_hash=r->>'row_hash', updated_at=now() where id = found_id;
      upd := upd + 1;
    else skip := skip + 1; end if;
  end loop;
  return jsonb_build_object('inserted', ins, 'updated', upd, 'unchanged', skip);
end $$;

create or replace function wf_upsert_competitions(rows jsonb) returns jsonb language plpgsql security definer set search_path = public as $$
declare ins int := 0; upd int := 0; skip int := 0; r jsonb; ex text; found_id bigint;
begin
  for r in select * from jsonb_array_elements(rows) loop
    select row_hash, id into ex, found_id from wf_competitions where key = r->>'key';
    if found_id is null then
      insert into wf_competitions(key,wikidata,reep_id,name,name_fold,iso2,level,kind,row_hash) values (r->>'key', r->>'wikidata', r->>'reep_id', r->>'name', r->>'name_fold', r->>'iso2', nullif(r->>'level','')::int, r->>'kind', r->>'row_hash'); ins := ins + 1;
    elsif ex is distinct from r->>'row_hash' then
      update wf_competitions set wikidata=r->>'wikidata', reep_id=r->>'reep_id', name=r->>'name', name_fold=r->>'name_fold', iso2=r->>'iso2', level=nullif(r->>'level','')::int, kind=r->>'kind', row_hash=r->>'row_hash', updated_at=now() where id = found_id; upd := upd + 1;
    else skip := skip + 1; end if;
  end loop;
  return jsonb_build_object('inserted', ins, 'updated', upd, 'unchanged', skip);
end $$;

create or replace function wf_upsert_countries(rows jsonb) returns jsonb language plpgsql security definer set search_path = public as $$
declare n int := 0; r jsonb;
begin
  for r in select * from jsonb_array_elements(rows) loop
    insert into wf_countries(iso2,iso3,name_en,name_pt,confederation,flag_url,row_hash) values (r->>'iso2', r->>'iso3', r->>'name_en', r->>'name_pt', r->>'confederation', r->>'flag_url', r->>'row_hash')
    on conflict (iso2) do update set iso3=excluded.iso3, name_en=excluded.name_en, name_pt=excluded.name_pt, confederation=coalesce(excluded.confederation, wf_countries.confederation), flag_url=excluded.flag_url, row_hash=excluded.row_hash, updated_at=now()
    where wf_countries.row_hash is distinct from excluded.row_hash;
    n := n + 1;
  end loop;
  return jsonb_build_object('processed', n);
end $$;

revoke execute on function wf_upsert_clubs(jsonb), wf_upsert_players(jsonb), wf_upsert_competitions(jsonb), wf_upsert_countries(jsonb) from public, anon, authenticated;
grant execute on function wf_upsert_clubs(jsonb), wf_upsert_players(jsonb), wf_upsert_competitions(jsonb), wf_upsert_countries(jsonb) to service_role;
grant select on wf_countries, wf_competitions, wf_clubs, wf_players, wf_runs to anon, authenticated;
grant execute on function wf_match_clubs(text,int), wf_match_players(text,text,text,int), wf_match_competitions(text,int), wf_stats() to anon, authenticated;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('wf-images', 'wf-images', true, 307200, array['image/png','image/jpeg','image/webp'])
on conflict (id) do update set public = true, file_size_limit = 307200, allowed_mime_types = array['image/png','image/jpeg','image/webp'];
drop policy if exists wf_images_obj_read on storage.objects;
create policy wf_images_obj_read on storage.objects for select to anon, authenticated using (bucket_id = 'wf-images');
drop policy if exists wf_images_obj_ins on storage.objects;
create policy wf_images_obj_ins on storage.objects for insert to anon, authenticated with check (bucket_id = 'wf-images' and name ~ '^(flags|crests|photos)/[a-z0-9._-]{1,120}\.png$');
