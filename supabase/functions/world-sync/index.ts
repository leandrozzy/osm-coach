// Central Mundial — atualização automática DENTRO do Supabase (Edge Function, Deno).
// Chamada pelo agendamento diário (pg_cron) e pelo botão do app. Em cada chamada faz um pedaço limitado do trabalho (incremental):
//   1) fila de nomes que o app não identificou → API-Football (chave guardada aqui como secret, nunca no app);
//   2) varredura do Wikidata por país: clubes e jogadores ATIVOS (só homens), uma fatia por vez, retomando de onde parou (wf_sync_state);
// Só ACRESCENTA/completa campos vazios (wf_merge_clubs / wf_merge_players). Nunca apaga nada.
const URL_ = Deno.env.get("SUPABASE_URL")!;
const KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const APIF = Deno.env.get("API_FOOTBALL_KEY") ?? "";
const BUDGET_MS = 110_000;          // limite da função é ~150 s: para antes
const APIF_DAILY = 90;              // plano gratuito = 100/dia; sobra margem
const WD_PAGE = 1500;
const T0 = Date.now();
const left = () => BUDGET_MS - (Date.now() - T0);

// ------------------------------------------------------------ normalização (espelha tools/world_loader/loader.py e WorldNorm.kt)
const SPECIAL: Record<string, string> = { "ß": "ss", "ø": "o", "Ø": "O", "æ": "ae", "Æ": "AE", "œ": "oe", "đ": "d", "Đ": "D", "ł": "l", "Ł": "L", "ı": "i", "þ": "th" };
function fold(s: string | null | undefined): string {
  if (!s || !s.trim()) return "";
  let t = s.replace(/[ßøØæÆœđĐłŁıþ]/g, (c) => SPECIAL[c] ?? c).normalize("NFD").replace(/\p{M}/gu, "").toLowerCase();
  return t.replace(/[^a-z0-9]+/g, " ").trim();
}
const PARTICLES = new Set(["van", "von", "de", "da", "do", "dos", "das", "del", "della", "di", "der", "den", "le", "la", "el", "al", "bin", "ben", "ibn", "mac", "mc", "st", "ter", "ten"]);
const ROMAN: Record<string, string> = { ii: "II", iii: "III", iv: "IV", b: "B", c: "C", u17: "U17", u18: "U18", u19: "U19", u20: "U20", u21: "U21", u23: "U23", reserves: "RES", reserve: "RES", youth: "YTH", juniors: "JUN", amateure: "AMA", amateurs: "AMA", women: "W", femenino: "W", feminino: "W", ladies: "W", frauen: "W", fem: "W", "2": "II", "3": "III" };
function clubMarker(name: string): string {
  const tk = fold(name).split(" ").filter(Boolean); let m = "";
  while (tk.length > 1 && ROMAN[tk[tk.length - 1]]) m = ROMAN[tk.pop()!] + m;
  return m;
}
function playerTails(name: string): [string, string] {
  const tk = fold(name).split(" ").filter(Boolean);
  if (!tk.length) return ["", ""];
  let si = tk.length - 1;
  while (si > 0 && PARTICLES.has(tk[si - 1])) si--;
  return [tk[tk.length - 1], tk.slice(si).join(" ")];
}
function nameOk(s: string | null | undefined): boolean {
  if (!s) return false; const t = s.trim(); if (t.length < 2) return false;
  let letters = 0;
  for (const ch of t) {
    if (ch === "?" || ch === "�") return false;
    if (/\p{L}/u.test(ch)) { if (!/\p{Script=Latin}/u.test(ch)) return false; letters++; }
  }
  return letters >= 2 && !/^Q\d+$/.test(t);
}
const WOMEN = /(^|[^a-z])(women|womens|woman|female|femin[a-z]*|femen[a-z]*|femmes?|frauen|damen|ladies|vrouwen|kobiet|kvinner|kvinnor|damallsvenskan|nwsl|wsl|w-league|liga f)([^a-z]|$)/;
const isWomen = (s: string) => { const f = fold(s); return WOMEN.test(f) || f.endsWith(" w"); };

// ------------------------------------------------------------ acesso ao banco (PostgREST com a chave de serviço)
const H = { apikey: KEY, Authorization: `Bearer ${KEY}`, "Content-Type": "application/json" };
async function db(path: string, init: RequestInit = {}) {
  const r = await fetch(`${URL_}/rest/v1/${path}`, { ...init, headers: { ...H, ...(init.headers as Record<string, string> | undefined) } });
  const t = await r.text();
  if (!r.ok) throw new Error(`HTTP ${r.status} ${path}: ${t.slice(0, 200)}`);
  return t ? JSON.parse(t) : null;
}
const rpc = (fn: string, rows: unknown[]) => db(`rpc/${fn}`, { method: "POST", body: JSON.stringify({ rows }) });
async function getState(task: string): Promise<{ cursor: string | null; info: Record<string, any> }> {
  const r = await db(`wf_sync_state?task=eq.${encodeURIComponent(task)}&select=cursor,info`);
  return r?.[0] ?? { cursor: null, info: {} };
}
async function setState(task: string, cursor: string | null, info: Record<string, unknown>) {
  await db("wf_sync_state?on_conflict=task", { method: "POST", headers: { Prefer: "resolution=merge-duplicates,return=minimal" }, body: JSON.stringify({ task, cursor, info, updated_at: new Date().toISOString() }) });
}
async function push(fn: string, rows: unknown[], size = 200) {
  const tot = { inserted: 0, updated: 0, unchanged: 0 };
  for (let i = 0; i < rows.length; i += size) {
    const r = await rpc(fn, rows.slice(i, i + size)) ?? {};
    for (const k of Object.keys(tot) as (keyof typeof tot)[]) tot[k] += Number(r[k] ?? 0);
  }
  return tot;
}

// ------------------------------------------------------------ Wikidata (SPARQL, uma fatia por vez)
async function sparql(q: string): Promise<any[]> {
  const ctl = new AbortController(); const to = setTimeout(() => ctl.abort(), Math.min(60_000, Math.max(5_000, left())));
  try {
    const r = await fetch("https://query.wikidata.org/sparql", { method: "POST", signal: ctl.signal,
      headers: { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/sparql-results+json", "User-Agent": "OSMCoachWorldSync/1.0 (open data sync)" }, body: "query=" + encodeURIComponent(q) });
    if (!r.ok) throw new Error(`Wikidata HTTP ${r.status}`);
    return (await r.json()).results.bindings;
  } finally { clearTimeout(to); }
}
const qidOf = (u?: string) => u?.split("/").pop();
const val = (b: any, k: string) => b[k]?.value as string | undefined;

async function wikidataClubs(iso: string, offset: number) {
  const q = `SELECT ?c ?cLabel ?cityLabel ?stadLabel ?inc WHERE {
    ?c wdt:P31 wd:Q476028; wdt:P17 ?k. ?k wdt:P297 "${iso.toUpperCase()}".
    FILTER NOT EXISTS { ?c wdt:P576 ?dis }
    OPTIONAL { ?c wdt:P159 ?city } OPTIONAL { ?c wdt:P115 ?stad } OPTIONAL { ?c wdt:P571 ?inc }
    SERVICE wikibase:label { bd:serviceParam wikibase:language "en,pt,es,de,fr,it,nl,pl,tr,[AUTO_LANGUAGE]". }
  } ORDER BY ?c LIMIT ${WD_PAGE} OFFSET ${offset}`;
  const b = await sparql(q); const rows = new Map<string, any>();
  for (const x of b) {
    const wd = qidOf(val(x, "c")); const name = val(x, "cLabel");
    if (!wd || !name || !nameOk(name) || isWomen(name) || rows.has(wd)) continue;
    const nf = fold(name); if (!nf) continue;
    const city = val(x, "cityLabel"); const stad = val(x, "stadLabel"); const inc = val(x, "inc");
    rows.set(wd, { key: `wd:${wd}`, wikidata: wd, name, name_fold: nf, marker: clubMarker(name), iso2: iso.toLowerCase(),
      city: city && nameOk(city) ? city : null, stadium: stad && nameOk(stad) ? stad : null, founded: inc ? inc.slice(0, 4) : null, prominence: 1, aliases: [], alias_fold: [nf] });
  }
  return { n: b.length, res: await push("wf_merge_clubs", [...rows.values()]) };
}

async function wikidataPlayers(iso: string, offset: number, natName: string | null) {
  const q = `SELECT ?p ?pLabel ?dob ?posLabel WHERE {
    ?p wdt:P106 wd:Q937857; wdt:P21 wd:Q6581097; wdt:P27 ?k. ?k wdt:P297 "${iso.toUpperCase()}".
    ?p wdt:P569 ?dob. FILTER(?dob >= "1986-01-01T00:00:00Z"^^xsd:dateTime)
    ?p p:P54 ?st. ?st ps:P54 ?team. FILTER NOT EXISTS { ?st pq:P582 ?end }
    OPTIONAL { ?p wdt:P413 ?pos }
    SERVICE wikibase:label { bd:serviceParam wikibase:language "en,pt,es,de,fr,it,nl,pl,tr,[AUTO_LANGUAGE]". }
  } ORDER BY ?p LIMIT ${WD_PAGE} OFFSET ${offset}`;
  const b = await sparql(q); const rows = new Map<string, any>();
  for (const x of b) {
    const wd = qidOf(val(x, "p")); const name = val(x, "pLabel");
    if (!wd || !name || !nameOk(name) || rows.has(wd)) continue;
    const [t1, t2] = playerTails(name); if (!t1) continue;
    const dob = val(x, "dob")?.slice(0, 10) ?? null; const by = dob && /^\d{4}/.test(dob) ? Number(dob.slice(0, 4)) : null;
    rows.set(wd, { reep_id: `wd:${wd}`, wikidata: wd, name, full_name: null, name_fold: fold(name), tail1: t1, tail2: t2, dob, birth_year: by,
      nationality: natName, nationality_iso: iso.toLowerCase(), position: val(x, "posLabel") ?? null, height_cm: null, tm_id: null, apif_id: null });
  }
  return { n: b.length, res: await push("wf_merge_players", [...rows.values()]) };
}

// ------------------------------------------------------------ API-Football (só para a fila de nomes que o app não achou)
async function apif(path: string): Promise<any[]> {
  const r = await fetch(`https://v3.football.api-sports.io${path}`, { headers: { "x-apisports-key": APIF } });
  if (!r.ok) throw new Error(`API-Football HTTP ${r.status}`);
  const j = await r.json();
  if (j.errors && Object.keys(j.errors).length && !Array.isArray(j.errors)) throw new Error("API-Football: " + JSON.stringify(j.errors).slice(0, 160));
  return j.response ?? [];
}
const today = () => new Date().toISOString().slice(0, 10);
async function apifQueue(countries: Map<string, string>) {
  const out = { used: 0, players: 0, clubs: 0, done: 0, skipped: "" };
  if (!APIF) { out.skipped = "API_FOOTBALL_KEY não configurada"; return out; }
  const st = await getState("apif_quota"); let used = st.info.day === today() ? Number(st.info.used ?? 0) : 0;
  const wanted: any[] = await db("wf_wanted?status=eq.novo&tries=lt.3&order=created_at.asc&limit=60&select=id,kind,name,name_fold,iso2,age,tries");
  const year = new Date().getFullYear();
  for (const w of wanted) {
    if (left() < 25_000 || used >= APIF_DAILY) break;
    let found = false;
    try {
      const term = w.name.replace(/[^A-Za-z0-9 ]/g, " ").replace(/\s+/g, " ").trim();
      if (term.length < 3) throw new Error("curto");
      if (w.kind === "player") {
        const sur = term.split(" ").pop()!; used++;
        const rs = await apif(`/players/profiles?search=${encodeURIComponent(sur.length >= 3 ? sur : term)}`);
        const rows: any[] = [];
        for (const e of rs.slice(0, 40)) {
          const p = e.player; if (!p?.name || !nameOk(p.name)) continue;
          if (w.age && p.age && Math.abs(p.age - w.age) > 1) continue;      // a idade do jogo (±1) filtra homônimos
          const [t1, t2] = playerTails(p.name); if (!t1) continue;
          const dob = p.birth?.date ?? null; const by = dob ? Number(String(dob).slice(0, 4)) : (p.age ? year - p.age : null);
          rows.push({ reep_id: `apif:${p.id}`, wikidata: null, name: p.name, full_name: [p.firstname, p.lastname].filter(Boolean).join(" ") || null, name_fold: fold(p.name), tail1: t1, tail2: t2,
            dob, birth_year: by, nationality: p.nationality ?? null, nationality_iso: countries.get(fold(p.nationality ?? "")) ?? null, position: p.position ?? null, height_cm: null, tm_id: null, apif_id: String(p.id) });
        }
        if (rows.length) { await push("wf_merge_players", rows); out.players += rows.length; found = true; }
      } else {
        used++; const ts = await apif(`/teams?search=${encodeURIComponent(term)}`);
        const tm = ts.find((e: any) => e.team && !e.team.national && fold(e.team.name) === fold(term)) ?? ts.find((e: any) => e.team && !e.team.national);
        if (tm && used < APIF_DAILY) {
          const t = tm.team; const nf = fold(t.name);
          if (nf && nameOk(t.name) && !isWomen(t.name)) {
            await push("wf_merge_clubs", [{ key: `apif:${t.id}`, wikidata: null, name: t.name, name_fold: nf, marker: clubMarker(t.name), iso2: countries.get(fold(tm.team.country ?? "")) ?? null,
              city: tm.venue?.city ?? null, stadium: tm.venue?.name ?? null, founded: t.founded ? String(t.founded) : null, prominence: 1, aliases: [], alias_fold: [nf] }]);
            out.clubs++; found = true;
            used++; const sq = await apif(`/players/squads?team=${t.id}`);
            const rows: any[] = [];
            for (const p of sq[0]?.players ?? []) {
              if (!p?.name || !nameOk(p.name)) continue; const [t1, t2] = playerTails(p.name); if (!t1) continue;
              rows.push({ reep_id: `apif:${p.id}`, wikidata: null, name: p.name, full_name: null, name_fold: fold(p.name), tail1: t1, tail2: t2, dob: null, birth_year: p.age ? year - p.age : null,
                nationality: null, nationality_iso: null, position: p.position ?? null, height_cm: null, tm_id: null, apif_id: String(p.id) });
            }
            if (rows.length) { await push("wf_merge_players", rows); out.players += rows.length; }
          }
        }
      }
    } catch (e) { out.skipped = String((e as Error).message ?? e).slice(0, 120); if (/HTTP 429|requests|limit/i.test(out.skipped)) break; }
    await db(`wf_wanted?id=eq.${w.id}`, { method: "PATCH", headers: { Prefer: "return=minimal" }, body: JSON.stringify(found ? { status: "feito", tried_at: new Date().toISOString() } : { tries: w.tries + 1, status: w.tries + 1 >= 3 ? "sem" : "novo", tried_at: new Date().toISOString() }) });
    if (found) out.done++;
  }
  out.used = used; await setState("apif_quota", null, { day: today(), used });
  return out;
}

// ------------------------------------------------------------ varredura do Wikidata por país (retoma de onde parou)
async function wikidataSweep(kind: "clubs" | "players", isos: string[], names: Map<string, string>, slices: number) {
  const task = `wikidata_${kind}`; const st = await getState(task);
  let [iso, off] = (st.cursor ?? `${isos[0]}:0`).split(":"); let offset = Number(off) || 0;
  let idx = Math.max(0, isos.indexOf(iso)); const info: any = { ...st.info, last: [] };
  let did = 0, added = 0;
  while (did < slices && left() > 70_000) {
    iso = isos[idx];
    try {
      const r = kind === "clubs" ? await wikidataClubs(iso, offset) : await wikidataPlayers(iso, offset, names.get(iso) ?? null);
      added += r.res.inserted + r.res.updated; info.last.push(`${iso}:${offset}+${r.n}`);
      if (r.n >= WD_PAGE) offset += WD_PAGE; else { offset = 0; idx = (idx + 1) % isos.length; if (idx === 0) info.cycles = (info.cycles ?? 0) + 1; }
    } catch (e) { info.error = `${iso}: ${String((e as Error).message ?? e).slice(0, 100)}`; offset = 0; idx = (idx + 1) % isos.length; }   // país que falha é pulado; volta na próxima volta
    did++;
  }
  info.last = info.last.slice(-8);
  await setState(task, `${isos[idx]}:${offset}`, info);
  return { slices: did, changed: added, next: `${isos[idx]}:${offset}` };
}

Deno.serve(async (req) => {
  const cors = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info", "Content-Type": "application/json" };
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  try {
    // trava de 8 min: o botão do app (chave anon pública) não consegue disparar trabalho em excesso
    const lock = await getState("lock"); const until = Number(lock.info.until ?? 0);
    if (Date.now() < until) return new Response(JSON.stringify({ ok: true, busy: true, retry_in_s: Math.ceil((until - Date.now()) / 1000) }), { headers: cors });
    await setState("lock", null, { until: Date.now() + 8 * 60_000 });
    const run = await db("wf_runs", { method: "POST", headers: { Prefer: "return=representation" }, body: JSON.stringify({ source: "world-sync", status: "rodando" }) });
    const ctr: any[] = await db("wf_countries?select=iso2,name_en&order=iso2.asc");
    const names = new Map<string, string>(ctr.map((c) => [c.iso2.toLowerCase(), c.name_en])); const byName = new Map<string, string>(ctr.map((c) => [fold(c.name_en), c.iso2.toLowerCase()]));
    // países com nomes na fila vêm primeiro (o que o app mais precisa)
    const pr: any[] = await db("wf_wanted?status=eq.novo&iso2=not.is.null&select=iso2&limit=200"); const prio = [...new Set(pr.map((x) => String(x.iso2).toLowerCase()).filter((x) => names.has(x)))];
    const isos = [...prio, ...[...names.keys()].filter((x) => !prio.includes(x))];
    const out: Record<string, unknown> = {};
    out.apif = await apifQueue(byName);
    if (left() > 75_000) out.clubs = await wikidataSweep("clubs", isos, names, 2);
    if (left() > 75_000) out.players = await wikidataSweep("players", isos, names, 2);
    const changed = Number((out.clubs as any)?.changed ?? 0) + Number((out.players as any)?.changed ?? 0) + Number((out.apif as any)?.players ?? 0) + Number((out.apif as any)?.clubs ?? 0);
    await db(`wf_runs?id=eq.${run[0].id}`, { method: "PATCH", headers: { Prefer: "return=minimal" }, body: JSON.stringify({ finished_at: new Date().toISOString(), rows_changed: changed, status: "ok", message: JSON.stringify(out).slice(0, 400) }) });
    await setState("lock", null, { until: 0 });
    return new Response(JSON.stringify({ ok: true, ms: Date.now() - T0, ...out }), { headers: cors });
  } catch (e) {
    await setState("lock", null, { until: 0 }).catch(() => {});
    return new Response(JSON.stringify({ ok: false, error: String((e as Error).message ?? e).slice(0, 300) }), { status: 500, headers: cors });
  }
});
