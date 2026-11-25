package com.osmaicoach.collector

/**
 * Base Mundial — esquema SQLite (banco PRÓPRIO `world_football.db`, separado do `osm_coach.db`: migração aditiva, o banco do app não muda).
 * Camadas: REAL (football_*), OSM (observações/mapeamentos por slot) e CORRESPONDÊNCIA (football_osm_entity_mappings). Dado externo nunca grava força/valor/atributo do jogo.
 */
object WorldSchema {
    const val VERSION = 3
    /** Colunas novas (v2): ALTER aditivo, ignorado se já existir. */
    val ALTERS = listOf("ALTER TABLE football_countries ADD COLUMN osm_seen INTEGER NOT NULL DEFAULT 0")
    val DDL: List<String> = listOf(
        """CREATE TABLE IF NOT EXISTS football_confederations(id INTEGER PRIMARY KEY, code TEXT UNIQUE NOT NULL, name TEXT NOT NULL, continent TEXT, wikidata TEXT, source_id TEXT, updated_at INTEGER)""",
        """CREATE TABLE IF NOT EXISTS football_countries(id INTEGER PRIMARY KEY, iso2 TEXT UNIQUE, iso3 TEXT, fifa TEXT, name_en TEXT NOT NULL, name_pt TEXT, name_official TEXT, name_osm TEXT, name_key TEXT,
            continent TEXT, confederation_id INTEGER REFERENCES football_confederations(id), association_id INTEGER, flag_url TEXT, wikidata TEXT, source_id TEXT, validation INTEGER NOT NULL DEFAULT 0, osm_seen INTEGER NOT NULL DEFAULT 0, updated_at INTEGER)""",
        """CREATE TABLE IF NOT EXISTS football_associations(id INTEGER PRIMARY KEY, country_id INTEGER REFERENCES football_countries(id), name TEXT NOT NULL, code TEXT, confederation_id INTEGER REFERENCES football_confederations(id), wikidata TEXT UNIQUE, source_id TEXT, updated_at INTEGER)""",
        """CREATE TABLE IF NOT EXISTS football_competitions(id INTEGER PRIMARY KEY, name TEXT NOT NULL, name_osm TEXT, name_key TEXT, country_id INTEGER REFERENCES football_countries(id), iso2 TEXT, association_id INTEGER, level INTEGER,
            kind TEXT, category TEXT, gender TEXT, sport TEXT NOT NULL DEFAULT 'football', wikidata TEXT UNIQUE, source_id TEXT, validation INTEGER NOT NULL DEFAULT 0, osm_seen INTEGER NOT NULL DEFAULT 0, updated_at INTEGER)""",
        """CREATE TABLE IF NOT EXISTS football_competition_seasons(id INTEGER PRIMARY KEY, competition_id INTEGER NOT NULL REFERENCES football_competitions(id), season TEXT NOT NULL, status TEXT, source_id TEXT, UNIQUE(competition_id, season))""",
        """CREATE TABLE IF NOT EXISTS football_competition_clubs(competition_id INTEGER NOT NULL REFERENCES football_competitions(id), club_id INTEGER NOT NULL REFERENCES football_clubs(id), season TEXT NOT NULL, division TEXT, source_id TEXT, PRIMARY KEY(competition_id, club_id, season))""",
        """CREATE TABLE IF NOT EXISTS football_clubs(id INTEGER PRIMARY KEY, name TEXT NOT NULL, short_name TEXT, name_osm TEXT, name_key TEXT NOT NULL, marker TEXT NOT NULL DEFAULT '', country_id INTEGER REFERENCES football_countries(id), iso2 TEXT,
            city TEXT, founded TEXT, stadium TEXT, category TEXT, wikidata TEXT UNIQUE, logo_url TEXT, source_id TEXT, validation INTEGER NOT NULL DEFAULT 0, osm_seen INTEGER NOT NULL DEFAULT 0, updated_at INTEGER)""",
        """CREATE TABLE IF NOT EXISTS football_players(id INTEGER PRIMARY KEY, name TEXT NOT NULL, full_name TEXT, name_key TEXT NOT NULL, surname_key TEXT NOT NULL, initial TEXT, dob TEXT, birth_year INTEGER, nationality TEXT, nationality_iso TEXT,
            position TEXT, foot TEXT, height_cm INTEGER, status TEXT, wikidata TEXT UNIQUE, reep_id TEXT, tm_id TEXT, apif_id TEXT, source_id TEXT, confidence INTEGER, validation INTEGER NOT NULL DEFAULT 0, osm_seen INTEGER NOT NULL DEFAULT 0, updated_at INTEGER)""",
        """CREATE TABLE IF NOT EXISTS football_squads(id INTEGER PRIMARY KEY, club_id INTEGER NOT NULL REFERENCES football_clubs(id), player_id INTEGER NOT NULL REFERENCES football_players(id), season TEXT NOT NULL, shirt INTEGER, source_id TEXT, UNIQUE(club_id, player_id, season))""",
        """CREATE TABLE IF NOT EXISTS football_player_club_history(id INTEGER PRIMARY KEY, player_id INTEGER NOT NULL REFERENCES football_players(id), club_id INTEGER REFERENCES football_clubs(id), club_name TEXT, from_date TEXT, to_date TEXT, kind TEXT, source_id TEXT)""",
        """CREATE TABLE IF NOT EXISTS football_entity_aliases(id INTEGER PRIMARY KEY, entity_type TEXT NOT NULL, entity_id INTEGER NOT NULL, alias TEXT NOT NULL, alias_key TEXT NOT NULL, kind TEXT, source_id TEXT, UNIQUE(entity_type, entity_id, alias_key))""",
        """CREATE TABLE IF NOT EXISTS football_external_ids(entity_type TEXT NOT NULL, entity_id INTEGER NOT NULL, provider TEXT NOT NULL, ext_id TEXT NOT NULL, source_id TEXT, PRIMARY KEY(entity_type, provider, ext_id))""",
        """CREATE TABLE IF NOT EXISTS football_images(id INTEGER PRIMARY KEY, entity_type TEXT NOT NULL, entity_id INTEGER NOT NULL, kind TEXT NOT NULL, url TEXT NOT NULL, local_path TEXT, license TEXT, attribution TEXT, source_id TEXT, hash TEXT, status TEXT NOT NULL DEFAULT 'pendente', checked_at INTEGER, UNIQUE(entity_type, entity_id, kind, url))""",
        """CREATE TABLE IF NOT EXISTS football_sources(id TEXT PRIMARY KEY, name TEXT NOT NULL, site TEXT, docs TEXT, kind TEXT, license TEXT, status TEXT NOT NULL, credential TEXT, quota TEXT, last_sync INTEGER, next_sync INTEGER, imported INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL DEFAULT 0,
            errors INTEGER NOT NULL DEFAULT 0, version TEXT, categories TEXT, notes TEXT, verified_at INTEGER, enabled INTEGER NOT NULL DEFAULT 1)""",
        """CREATE TABLE IF NOT EXISTS football_field_provenance(entity_type TEXT NOT NULL, entity_id INTEGER NOT NULL, field TEXT NOT NULL, source_id TEXT NOT NULL, version TEXT, confidence INTEGER, at INTEGER NOT NULL, PRIMARY KEY(entity_type, entity_id, field))""",
        """CREATE TABLE IF NOT EXISTS football_sync_jobs(id INTEGER PRIMARY KEY, source_id TEXT NOT NULL, kind TEXT NOT NULL, status TEXT NOT NULL, started INTEGER, finished INTEGER, analyzed INTEGER NOT NULL DEFAULT 0, inserted INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL DEFAULT 0,
            deduped INTEGER NOT NULL DEFAULT 0, failed INTEGER NOT NULL DEFAULT 0, total INTEGER, message TEXT, version TEXT)""",
        """CREATE TABLE IF NOT EXISTS football_sync_checkpoints(job_key TEXT PRIMARY KEY, source_id TEXT NOT NULL, byte_offset INTEGER NOT NULL DEFAULT 0, cursor TEXT, etag TEXT, last_modified TEXT, hash TEXT, total INTEGER, header TEXT, updated_at INTEGER NOT NULL)""",
        """CREATE TABLE IF NOT EXISTS football_ocr_observations(id INTEGER PRIMARY KEY, slot INTEGER NOT NULL, screen TEXT, kind TEXT NOT NULL, raw TEXT NOT NULL, norm TEXT NOT NULL, context TEXT, capture_key TEXT, at INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'novo', entity_id INTEGER, score INTEGER, algo TEXT)""",
        """CREATE TABLE IF NOT EXISTS football_osm_entity_mappings(id INTEGER PRIMARY KEY, osm_kind TEXT NOT NULL, osm_key TEXT NOT NULL, slot INTEGER NOT NULL, entity_type TEXT, entity_id INTEGER, status TEXT NOT NULL, score INTEGER, evidence TEXT, algo TEXT, observations INTEGER NOT NULL DEFAULT 1,
            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, UNIQUE(osm_kind, osm_key, slot))""",
        """CREATE TABLE IF NOT EXISTS football_validation_history(id INTEGER PRIMARY KEY, mapping_id INTEGER, osm_kind TEXT, osm_key TEXT, slot INTEGER, action TEXT NOT NULL, from_status TEXT, to_status TEXT, score INTEGER, reason TEXT, at INTEGER NOT NULL)""",
        """CREATE TABLE IF NOT EXISTS football_unresolved_entities(id INTEGER PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL, norm TEXT NOT NULL, slot INTEGER NOT NULL, attrs TEXT, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, seen_count INTEGER NOT NULL DEFAULT 1, status TEXT NOT NULL DEFAULT 'provisorio', UNIQUE(kind, norm, slot))""",
        """CREATE TABLE IF NOT EXISTS football_api_usage(source_id TEXT NOT NULL, day TEXT NOT NULL, calls INTEGER NOT NULL DEFAULT 0, limit_hint INTEGER, last_status INTEGER, last_at INTEGER, PRIMARY KEY(source_id, day))""",
        """CREATE TABLE IF NOT EXISTS football_coverage_metrics(id INTEGER PRIMARY KEY, at INTEGER NOT NULL, metric TEXT NOT NULL, scope TEXT NOT NULL DEFAULT '', value INTEGER NOT NULL)""",
        // índices
        "CREATE INDEX IF NOT EXISTS ix_clubs_key ON football_clubs(name_key, marker)", "CREATE INDEX IF NOT EXISTS ix_clubs_iso ON football_clubs(iso2)", "CREATE INDEX IF NOT EXISTS ix_clubs_country ON football_clubs(country_id)",
        "CREATE INDEX IF NOT EXISTS ix_players_name ON football_players(name_key)", "CREATE INDEX IF NOT EXISTS ix_players_surname ON football_players(surname_key, initial)", "CREATE INDEX IF NOT EXISTS ix_players_nat ON football_players(nationality_iso)", "CREATE UNIQUE INDEX IF NOT EXISTS ux_players_reep ON football_players(reep_id)",
        "CREATE INDEX IF NOT EXISTS ix_comp_iso ON football_competitions(iso2)", "CREATE INDEX IF NOT EXISTS ix_comp_key ON football_competitions(name_key)",
        "CREATE INDEX IF NOT EXISTS ix_alias_key ON football_entity_aliases(entity_type, alias_key)", "CREATE INDEX IF NOT EXISTS ix_ext_entity ON football_external_ids(entity_type, entity_id)",
        "CREATE INDEX IF NOT EXISTS ix_obs_status ON football_ocr_observations(status, at)", "CREATE UNIQUE INDEX IF NOT EXISTS ux_obs ON football_ocr_observations(kind, norm, slot, capture_key)", "CREATE INDEX IF NOT EXISTS ix_obs_norm ON football_ocr_observations(kind, norm, slot)",
        "CREATE INDEX IF NOT EXISTS ix_map_status ON football_osm_entity_mappings(status)", "CREATE INDEX IF NOT EXISTS ix_squad_player ON football_squads(player_id)",
        "CREATE INDEX IF NOT EXISTS ix_clubs_osm ON football_clubs(osm_seen)", "CREATE INDEX IF NOT EXISTS ix_players_osm ON football_players(osm_seen)", "CREATE INDEX IF NOT EXISTS ix_comp_osm ON football_competitions(osm_seen)", "CREATE INDEX IF NOT EXISTS ix_players_nat ON football_players(nationality_iso)", "CREATE INDEX IF NOT EXISTS ix_clubs_iso ON football_clubs(iso2)",
        "CREATE INDEX IF NOT EXISTS ix_jobs_src ON football_sync_jobs(source_id, started)", "CREATE INDEX IF NOT EXISTS ix_metrics ON football_coverage_metrics(metric, scope, at)"
    )
}
