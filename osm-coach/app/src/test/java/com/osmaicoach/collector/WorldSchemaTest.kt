package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test
import java.sql.DriverManager

/** O esquema roda em SQLite de verdade (JDBC só nos testes): todas as tabelas do documento existem, são idempotentes e respeitam unicidade. */
class WorldSchemaTest {
    private fun db() = DriverManager.getConnection("jdbc:sqlite::memory:").also { c -> c.createStatement().use { s -> s.execute("PRAGMA foreign_keys=ON"); WorldSchema.DDL.forEach { s.execute(it) } } }

    @Test fun allSpecTablesExist() {
        val c = db()
        val names = HashSet<String>()
        c.createStatement().executeQuery("select name from sqlite_master where type='table'").use { while (it.next()) names.add(it.getString(1)) }
        for (t in listOf("football_countries", "football_associations", "football_confederations", "football_competitions", "football_competition_seasons", "football_clubs", "football_players", "football_squads",
            "football_player_club_history", "football_entity_aliases", "football_external_ids", "football_images", "football_sources", "football_field_provenance", "football_sync_jobs", "football_sync_checkpoints",
            "football_ocr_observations", "football_osm_entity_mappings", "football_validation_history", "football_unresolved_entities", "football_api_usage", "football_coverage_metrics")) assertTrue(t, t in names)
    }

    @Test fun schemaIsIdempotent() { val c = db(); c.createStatement().use { s -> WorldSchema.DDL.forEach { s.execute(it) } } }

    @Test fun uniquenessAndForeignKeys() {
        val c = db(); val s = c.createStatement()
        s.execute("insert into football_clubs(name,name_key,wikidata) values('A','a','Q1')")
        try { s.execute("insert into football_clubs(name,name_key,wikidata) values('B','b','Q1')"); fail("wikidata duplicado") } catch (_: java.sql.SQLException) {}
        try { s.execute("insert into football_squads(club_id,player_id,season) values(99,99,'2026')"); fail("FK") } catch (_: java.sql.SQLException) {}
        s.execute("insert into football_osm_entity_mappings(osm_kind,osm_key,slot,status,created_at,updated_at) values('club','x',1,'novo',1,1)")
        s.execute("insert into football_osm_entity_mappings(osm_kind,osm_key,slot,status,created_at,updated_at) values('club','x',2,'novo',1,1)")   // outro slot: permitido
        try { s.execute("insert into football_osm_entity_mappings(osm_kind,osm_key,slot,status,created_at,updated_at) values('club','x',1,'novo',1,1)"); fail("mesmo slot") } catch (_: java.sql.SQLException) {}
    }
}
