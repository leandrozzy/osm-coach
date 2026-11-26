import os, shutil, sys, tempfile, unittest
sys.path.insert(0, os.path.dirname(__file__))
import loader as L

SAMPLES = os.path.join(os.path.dirname(__file__), "..", "..", "osm-coach", "app", "src", "test", "resources", "world")

class T(unittest.TestCase):
    def test_norm(self):
        self.assertEqual(L.fold("Borussia Mönchengladbach"), "borussia monchengladbach")
        self.assertEqual(L.fold("Łukasz Fabiański"), "lukasz fabianski")
        self.assertTrue(L.name_ok("São Paulo")); self.assertFalse(L.name_ok("Спартак")); self.assertFalse(L.name_ok("? Stolyarenko"))
        self.assertTrue(L.is_women("Arsenal Women")); self.assertTrue(L.is_women("Real Madrid (W)")); self.assertFalse(L.is_women("Wolverhampton Wanderers"))
        self.assertEqual(L.club_marker("TSG 1899 Hoffenheim II"), "II"); self.assertEqual(L.club_marker("TSG 1899 Hoffenheim"), "")
        self.assertEqual(L.player_tails("Virgil van Dijk"), ("dijk", "van dijk"))

    def test_samples_build_clean_idempotent_rows(self):
        d = tempfile.mkdtemp()
        try:
            for src, dst in [("flag_codes.json", "codes.json"), ("ofb_clubs_sample.csv", "clubs_football.csv"), ("reep_teams_sample.csv", "teams.csv"), ("reep_competitions_sample.csv", "competitions.csv"), ("reep_people_sample.csv", "people.csv")]:
                shutil.copy(os.path.join(SAMPLES, src), os.path.join(d, dst))
            countries, iso_of = L.load_countries(d)
            self.assertGreater(len(countries), 250); self.assertEqual(iso_of("Germany"), "de"); self.assertEqual(iso_of("Czech Republic"), "cz")
            clubs, comps = L.build_clubs_and_comps(d, iso_of)
            self.assertGreater(len(clubs), 500); self.assertGreater(len(comps), 50)
            self.assertEqual(len({c["key"] for c in clubs}), len(clubs))                 # chaves únicas
            self.assertTrue(all(L.name_ok(c["name"]) and not L.is_women(c["name"]) for c in clubs))
            clubs2, _ = L.build_clubs_and_comps(d, iso_of)
            self.assertEqual({c["key"]: c["row_hash"] for c in clubs}, {c["key"]: c["row_hash"] for c in clubs2})   # mesmo dado = mesmo hash (nada regravado)
            pl = list(L.players(d, iso_of))
            self.assertGreater(len(pl), 1000); self.assertEqual(len({p["reep_id"] for p in pl}), len(pl))
            self.assertTrue(all(L.name_ok(p["name"]) and p["tail1"] for p in pl))
        finally: shutil.rmtree(d)


class FakeSupa:
    """Imita o Supabase: devolve o que as funções wf_upsert_* devolvem (países só informam 'processed')."""
    def __init__(self): self.calls = []
    def rpc(self, fn, rows):
        self.calls.append((fn, len(rows)))
        return {"processed": len(rows)} if fn == "wf_upsert_countries" else {"inserted": len(rows) - 1, "updated": 0, "unchanged": 1}

class TPush(unittest.TestCase):
    def test_push_with_real_client_path(self):          # o caminho com Supabase (antes só era testado em --dry-run e estourava)
        st = {"read": 0, "changed": 0, "skipped": 0}; fk = FakeSupa()
        r = L.push(fk, "wf_upsert_countries", [{"a": i} for i in range(5)], 2, "países", st)
        self.assertEqual(st["read"], 5)
        r = L.push(fk, "wf_upsert_clubs", ({"a": i} for i in range(7)), 3, "clubes", st)
        self.assertEqual(r["unchanged"], 3)
        self.assertEqual(st["changed"], 7 - 3); self.assertEqual([c[0] for c in fk.calls].count("wf_upsert_clubs"), 3)

if __name__ == "__main__": unittest.main()
