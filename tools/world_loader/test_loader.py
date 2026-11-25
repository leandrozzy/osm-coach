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

if __name__ == "__main__": unittest.main()
