#!/usr/bin/env python3
"""Central Mundial de Futebol — carregador do Supabase.

Baixa as fontes abertas (OpenFootballBase, Reep v0 CC0, flagcdn), limpa (só alfabeto latino, sem futebol feminino),
junta duplicados pelo QID do Wikidata e grava no Supabase por RPC idempotente: só muda a linha cujo conteúdo mudou (row_hash).
Uso:  SUPABASE_URL=https://xxxx.supabase.co SUPABASE_SERVICE_KEY=... python3 loader.py [--only clubs,players,competitions,countries]
Teste sem rede de gravação:  python3 loader.py --dry-run --from-dir DIR   (DIR com clubs_football.csv, teams.csv, competitions.csv, people.csv, codes.json)
Só biblioteca padrão do Python 3.9+. A chave service_role NUNCA vai para o app nem para o repositório.
"""
import argparse, csv, hashlib, io, json, os, re, sys, time, unicodedata, urllib.request, urllib.error

RAW = "https://raw.githubusercontent.com"
URLS = {
    "clubs_football.csv": f"{RAW}/florentfougeres/OpenFootballBase-Data/main/data/source/clubs_football.csv",
    "teams.csv": f"{RAW}/withqwerty/reep/main/data/teams.csv",
    "competitions.csv": f"{RAW}/withqwerty/reep/main/data/competitions.csv",
    "people.csv": f"{RAW}/withqwerty/reep/main/data/people.csv",
    "codes.json": "https://flagcdn.com/en/codes.json",
}
csv.field_size_limit(10_000_000)

# ------------------------------------------------------------------ normalização (espelha WorldNorm.kt)
_SPECIAL = str.maketrans({"ß": "ss", "ø": "o", "Ø": "O", "æ": "ae", "Æ": "AE", "œ": "oe", "đ": "d", "Đ": "D", "ł": "l", "Ł": "L", "ı": "i", "þ": "th"})
def fold(s):
    if not s or not s.strip(): return ""
    t = unicodedata.normalize("NFD", s.translate(_SPECIAL))
    t = "".join(c for c in t if not unicodedata.category(c).startswith("M")).lower()
    return re.sub(r"[^a-z0-9]+", " ", t).strip()

PARTICLES = {"van", "von", "de", "da", "do", "dos", "das", "del", "della", "di", "der", "den", "le", "la", "el", "al", "bin", "ben", "ibn", "mac", "mc", "st", "ter", "ten"}
ROMAN = {"ii": "II", "iii": "III", "iv": "IV", "b": "B", "c": "C", "u17": "U17", "u18": "U18", "u19": "U19", "u20": "U20", "u21": "U21", "u23": "U23", "reserves": "RES", "reserve": "RES", "youth": "YTH",
         "juniors": "JUN", "amateure": "AMA", "amateurs": "AMA", "women": "W", "femenino": "W", "feminino": "W", "ladies": "W", "frauen": "W", "fem": "W", "2": "II", "3": "III"}

def club_marker(name):
    tk = fold(name).split()
    marker = ""
    while len(tk) > 1 and tk[-1] in ROMAN:
        marker = ROMAN[tk.pop()] + marker
    return marker

def player_tails(name):
    tk = fold(name).split()
    if not tk: return "", ""
    si = len(tk) - 1
    while si > 0 and tk[si - 1] in PARTICLES: si -= 1
    return tk[-1], " ".join(tk[si:])

def name_ok(s):
    if not s: return False
    t = s.strip()
    if len(t) < 2: return False
    letters = 0
    for ch in t:
        if ch in "?�": return False
        if ch.isalpha():
            try:
                if not unicodedata.name(ch).startswith("LATIN"): return False
            except ValueError: return False
            letters += 1
    return letters >= 2

_WOMEN = re.compile(r"(^|[^a-z])(women|womens|woman|female|femin[a-z]*|femen[a-z]*|femmes?|frauen|damen|ladies|vrouwen|kobiet|kvinner|kvinnor|damallsvenskan|nwsl|wsl|w-league|liga f)([^a-z]|$)")
def is_women(s):
    if not s: return False
    f = fold(s)
    return bool(_WOMEN.search(f)) or f.endswith(" w")

SYN = {"czech republic": "cz", "ivory coast": "ci", "cote d ivoire": "ci", "democratic republic of the congo": "cd", "zaire": "cd", "people s republic of china": "cn", "kingdom of the netherlands": "nl",
       "kingdom of denmark": "dk", "the gambia": "gm", "eswatini": "sz", "swaziland": "sz", "the bahamas": "bs", "korea south": "kr", "south korea": "kr", "korea north": "kp", "north korea": "kp",
       "chinese taipei": "tw", "republic of china": "tw", "slovak republic": "sk", "bosnia herzegovina": "ba", "saint kitts": "kn", "republic of egypt": "eg", "state of malta": "mt",
       "united kingdom of great britain and ireland": "gb", "russian federation": "ru", "turkiye": "tr", "turkey": "tr", "macedonia": "mk", "north macedonia": "mk", "cape verde": "cv", "east timor": "tl"}

def h(obj):
    return hashlib.sha1(json.dumps(obj, sort_keys=True, ensure_ascii=False).encode("utf-8")).hexdigest()[:16]

def qid(v):
    return v if v and re.fullmatch(r"Q\d+", v) else None

def g(row, k):
    v = row.get(k)
    v = v.strip() if isinstance(v, str) else v
    return v or None

# ------------------------------------------------------------------ leitura das fontes
def fetch(name, src_dir):
    if src_dir:
        return open(os.path.join(src_dir, name), "rb")
    req = urllib.request.Request(URLS[name], headers={"User-Agent": "OSMCoach-WorldLoader/1.0"})
    return urllib.request.urlopen(req, timeout=120)

def read_csv(name, src_dir):
    with fetch(name, src_dir) as f:
        for row in csv.DictReader(io.TextIOWrapper(f, encoding="utf-8-sig", newline="")):
            yield row

def load_countries(src_dir):
    with fetch("codes.json", src_dir) as f:
        codes = json.load(f)
    rows, idx = [], {}
    for code, name in codes.items():
        iso = code.lower()
        r = {"iso2": iso, "iso3": None, "name_en": name, "name_pt": None, "confederation": None, "flag_url": f"https://flagcdn.com/w160/{iso}.png"}
        r["row_hash"] = h([r["iso2"], r["name_en"], r["flag_url"]])
        rows.append(r)
        if len(iso) == 2: idx[fold(name)] = iso
    return rows, lambda n: SYN.get(fold(n)) or idx.get(fold(n))

# ------------------------------------------------------------------ montagem
def build_clubs_and_comps(src_dir, iso_of, limit=None):
    clubs, comps = {}, {}
    def put_club(key, d):
        cur = clubs.get(key)
        if cur is None: clubs[key] = d; return
        for k, v in d.items():   # completa só o que está vazio; aliases se somam
            if k == "aliases": cur["aliases"] = list(dict.fromkeys(cur["aliases"] + v))
            elif cur.get(k) in (None, "") and v not in (None, ""): cur[k] = v
    n = 0
    for r in read_csv("clubs_football.csv", src_dir):
        n += 1
        if limit and n > limit: break
        en, local = g(r, "nom_anglais"), g(r, "nom_local")
        name = next((x for x in (en, local) if name_ok(x)), None)
        cat = g(r, "categorie")
        if not name or is_women(name) or (cat and ("fémin" in cat.lower() or "women" in cat.lower())): continue
        wd = qid(g(r, "wikidata")); iso = (g(r, "pays_iso") or "").lower() or None
        if iso and len(iso) != 2: iso = None
        marker = club_marker(name)
        key = f"wd:{wd}" if wd else f"nm:{fold(name)}|{iso or ''}|{marker}"
        lq, ln = qid(g(r, "ligue_qid")), g(r, "ligue_actuelle")
        lvl = g(r, "division_actuel")
        lvl = int(lvl) if lvl and lvl.isdigit() and 1 <= int(lvl) <= 12 else None
        if lq and ln and name_ok(ln) and not is_women(ln):
            comps.setdefault(f"wd:{lq}", {"key": f"wd:{lq}", "wikidata": lq, "reep_id": None, "name": ln, "name_fold": fold(ln), "iso2": iso, "level": lvl, "kind": "liga"})
        al = [a for a in dict.fromkeys(x for x in (local, en, g(r, "surnom")) if x and name_ok(x))]
        put_club(key, {"key": key, "wikidata": wd, "reep_id": None, "name": name, "short_name": g(r, "surnom"), "name_fold": fold(name), "marker": marker, "iso2": iso, "city": g(r, "ville"), "founded": g(r, "date_creation"),
                       "stadium": g(r, "stade"), "category": cat, "logo_url": g(r, "url_logo"), "comp_key": f"wd:{lq}" if lq and f"wd:{lq}" in comps else None, "division": g(r, "division_actuel"), "aliases": al})
    n = 0
    for r in read_csv("teams.csv", src_dir):
        n += 1
        if limit and n > limit: break
        name = g(r, "name")
        if not name_ok(name) or is_women(name): continue
        wd, rp = qid(g(r, "key_wikidata")), g(r, "reep_id")
        iso = iso_of(g(r, "country")); marker = club_marker(name)
        key = f"wd:{wd}" if wd else f"rp:{rp}"
        put_club(key, {"key": key, "wikidata": wd, "reep_id": rp, "name": name, "short_name": None, "name_fold": fold(name), "marker": marker, "iso2": iso, "city": None, "founded": g(r, "founded"), "stadium": g(r, "stadium"),
                       "category": None, "logo_url": None, "comp_key": None, "division": None, "aliases": [name]})
    for r in read_csv("competitions.csv", src_dir):
        name = g(r, "name")
        if not name_ok(name) or is_women(name): continue
        wd, rp = qid(g(r, "key_wikidata")), g(r, "reep_id")
        key = f"wd:{wd}" if wd else f"rp:{rp}"
        cur = comps.get(key)
        d = {"key": key, "wikidata": wd, "reep_id": rp, "name": name, "name_fold": fold(name), "iso2": iso_of(g(r, "country")), "level": None, "kind": None}
        if cur is None: comps[key] = d
        else:
            for k, v in d.items():
                if cur.get(k) in (None, "") and v not in (None, ""): cur[k] = v
    out_clubs = []
    for c in clubs.values():
        c["aliases"] = [a for a in dict.fromkeys(c["aliases"]) if a]
        c["alias_fold"] = sorted({fold(a) for a in c["aliases"] if fold(a)} | {c["name_fold"]})
        c["row_hash"] = h([c[k] for k in ("key", "wikidata", "reep_id", "name", "short_name", "marker", "iso2", "city", "founded", "stadium", "category", "logo_url", "comp_key", "division")] + [c["aliases"]])
        out_clubs.append(c)
    out_comps = []
    for c in comps.values():
        c["row_hash"] = h([c[k] for k in ("key", "wikidata", "reep_id", "name", "iso2", "level", "kind")]); out_comps.append(c)
    return out_clubs, out_comps

def players(src_dir, iso_of, limit=None):
    n = 0
    for r in read_csv("people.csv", src_dir):
        if g(r, "type") != "player": continue
        n += 1
        if limit and n > limit: return
        name, rp = g(r, "name"), g(r, "reep_id")
        if not rp or not name_ok(name): continue
        dob = g(r, "date_of_birth"); by = int(dob[:4]) if dob and dob[:4].isdigit() and 1000 <= int(dob[:4]) <= 2100 else None
        hc = g(r, "height_cm")
        try: hc = int(float(hc)) if hc else None
        except ValueError: hc = None
        t1, t2 = player_tails(name)
        if not t1: continue
        nat = g(r, "nationality")
        d = {"reep_id": rp, "wikidata": qid(g(r, "key_wikidata")), "name": name, "full_name": g(r, "full_name"), "name_fold": fold(name), "tail1": t1, "tail2": t2, "dob": dob, "birth_year": by,
             "nationality": nat, "nationality_iso": iso_of(nat), "position": g(r, "position"), "height_cm": hc, "tm_id": g(r, "key_transfermarkt"), "apif_id": g(r, "key_api_football")}
        d["row_hash"] = h([d[k] for k in ("reep_id", "wikidata", "name", "full_name", "dob", "nationality", "nationality_iso", "position", "height_cm", "tm_id", "apif_id")])
        yield d

# ------------------------------------------------------------------ Supabase
class Supa:
    def __init__(self, url, key):
        self.url, self.key = url.rstrip("/"), key
    def call(self, method, path, body=None, retries=5):
        data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
        for i in range(retries):
            req = urllib.request.Request(self.url + path, data=data, method=method, headers={
                "apikey": self.key, "Authorization": f"Bearer {self.key}", "Content-Type": "application/json", "Prefer": "return=representation"})
            try:
                with urllib.request.urlopen(req, timeout=180) as r:
                    t = r.read().decode("utf-8"); return json.loads(t) if t else None
            except urllib.error.HTTPError as e:
                msg = e.read().decode("utf-8", "replace")[:300]
                if e.code in (429, 500, 502, 503, 504) and i < retries - 1: time.sleep(2 ** (i + 1)); continue
                raise RuntimeError(f"HTTP {e.code} em {path}: {msg}")
            except (urllib.error.URLError, TimeoutError):
                if i < retries - 1: time.sleep(2 ** (i + 1)); continue
                raise
    def rpc(self, fn, rows): return self.call("POST", f"/rest/v1/rpc/{fn}", {"rows": rows})

def push(supa, fn, rows, size, label, stats):
    tot = {"inserted": 0, "updated": 0, "unchanged": 0}
    batch = []
    def flush():
        nonlocal batch
        if not batch: return
        if supa:
            r = supa.rpc(fn, batch) or {}
            for k in tot: tot[k] += int(r.get(k, 0))
        else: tot["inserted"] += len(batch)
        batch = []
    for i, row in enumerate(rows, 1):
        batch.append(row)
        if len(batch) >= size:
            flush()
            if i % (size * 20) == 0: print(f"  {label}: {i} linhas…", flush=True)
    flush()
    print(f"{label}: novos {tot['inserted']} • alterados {tot['updated']} • iguais {tot['unchanged']}", flush=True)
    stats["read"] += sum(tot.values()); stats["changed"] += tot["inserted"] + tot["updated"]; stats["skipped"] += tot["unchanged"]
    return tot

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="countries,competitions,clubs,players")
    ap.add_argument("--from-dir"); ap.add_argument("--dry-run", action="store_true"); ap.add_argument("--limit", type=int)
    a = ap.parse_args()
    only = set(a.only.split(","))
    url, key = os.environ.get("SUPABASE_URL"), os.environ.get("SUPABASE_SERVICE_KEY")
    supa = None
    if not a.dry_run:
        if not url or not key: sys.exit("Defina SUPABASE_URL e SUPABASE_SERVICE_KEY (ou use --dry-run).")
        supa = Supa(url, key)
    stats = {"read": 0, "changed": 0, "skipped": 0}
    run = supa.call("POST", "/rest/v1/wf_runs", {"source": a.only, "status": "rodando"})[0]["id"] if supa else None
    status, msg = "ok", ""
    try:
        crow, iso_of = load_countries(a.from_dir)
        if "countries" in only: push(supa, "wf_upsert_countries", crow, 300, "países", stats)
        if only & {"clubs", "competitions"}:
            clubs, comps = build_clubs_and_comps(a.from_dir, iso_of, a.limit)
            if "competitions" in only: push(supa, "wf_upsert_competitions", comps, 300, "competições", stats)
            if "clubs" in only: push(supa, "wf_upsert_clubs", clubs, 200, "clubes", stats)
        if "players" in only: push(supa, "wf_upsert_players", players(a.from_dir, iso_of, a.limit), 300, "jogadores", stats)
    except Exception as e:
        status, msg = "erro", str(e)[:400]; print("ERRO:", msg, file=sys.stderr)
    finally:
        if supa and run:
            supa.call("PATCH", f"/rest/v1/wf_runs?id=eq.{run}", {"finished_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "rows_read": stats["read"], "rows_changed": stats["changed"], "rows_skipped": stats["skipped"], "status": status, "message": msg})
    sys.exit(0 if status == "ok" else 1)

if __name__ == "__main__":
    main()
