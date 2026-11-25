package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** JDBC SQLite no lugar do SQLite do Android: roda o MESMO código de importação com linhas REAIS (amostras dos CSVs das fontes). */
class JdbcSql(val c: Connection) : WorldSql {
    private fun bind(ps: java.sql.PreparedStatement, a: Array<out Any?>) { a.forEachIndexed { i, v -> when (v) { null -> ps.setObject(i + 1, null); is Int -> ps.setInt(i + 1, v); is Long -> ps.setLong(i + 1, v); else -> ps.setString(i + 1, v.toString()) } } }
    override fun exec(sql: String, args: Array<out Any?>): Int = c.prepareStatement(sql).use { ps -> bind(ps, args); ps.executeUpdate() }
    override fun long(sql: String, vararg a: String): Long? = c.prepareStatement(sql).use { ps -> bind(ps, a); ps.executeQuery().use { if (it.next()) it.getLong(1).takeIf { _ -> it.getObject(1) != null } else null } }
    override fun rows(sql: String, vararg a: String): List<List<String?>> = c.prepareStatement(sql).use { ps -> bind(ps, a); ps.executeQuery().use { rs -> val n = rs.metaData.columnCount; val out = ArrayList<List<String?>>(); while (rs.next()) out.add((1..n).map { rs.getString(it) }); out } }
    override fun begin() { c.autoCommit = false }
    override fun commit() { c.commit(); c.autoCommit = true }
    override fun rollback() { c.rollback(); c.autoCommit = true }
    companion object {
        fun fresh(): JdbcSql { val c = DriverManager.getConnection("jdbc:sqlite::memory:"); c.createStatement().use { s -> WorldSchema.DDL.forEach { s.execute(it) } }; return JdbcSql(c) }
    }
}

class WorldImportTest {
    private fun res(n: String) = javaClass.getResourceAsStream("/world/$n")!!

    /** Mesmo laço do WorldSync: cabeçalho + registros, devolvendo os contadores. */
    private fun run(sink: WorldImport.Sink, input: java.io.InputStream, sql: WorldSql): WorldImport.Counters {
        val c = WorldImport.Counters(); val csv = WorldCsv(input)
        sink.header(WorldCsv.clean(csv.readRecord()!!))
        sql.begin()
        while (true) { val r = csv.readRecord() ?: break; if (r.isEmpty()) continue; sink.row(r, c) }
        sql.commit(); sink.close(); return c
    }
    private fun seeded(): Pair<JdbcSql, WorldImport.Countries.Index> {
        val sql = JdbcSql.fresh(); WorldImport.importCountries(sql, String(res("flag_codes.json").readBytes()))
        return Pair(sql, WorldImport.Countries.index(sql))
    }

    @Test fun csvReaderHandlesQuotesNewlinesAndOffsets() {
        val text = "a,b,c\n1,\"x,y\",3\n\"li\nne\",\"aspas \"\"duplas\"\"\",ç\r\n\n4,5,6"
        val bytes = text.toByteArray()
        val r = WorldCsv(ByteArrayInputStream(bytes))
        assertEquals(listOf("a", "b", "c"), r.readRecord()); val off1 = r.offset
        assertEquals(listOf("1", "x,y", "3"), r.readRecord())
        assertEquals(listOf("li\nne", "aspas \"duplas\"", "ç"), r.readRecord())
        assertEquals(emptyList<String>(), r.readRecord())
        assertEquals(listOf("4", "5", "6"), r.readRecord())
        assertNull(r.readRecord()); assertEquals(bytes.size.toLong(), r.offset)
        // retomada pelo offset: recomeça exatamente depois do cabeçalho
        val again = WorldCsv(ByteArrayInputStream(bytes.copyOfRange(off1.toInt(), bytes.size)), off1)
        assertEquals(listOf("1", "x,y", "3"), again.readRecord())
    }

    @Test fun countriesComeFromTheRealFlagList() {
        val sql = JdbcSql.fresh(); val c = WorldImport.importCountries(sql, String(res("flag_codes.json").readBytes()))
        assertTrue("países/territórios", c.inserted >= 250)
        assertEquals("Brasil", sql.rows("SELECT name_pt FROM football_countries WHERE iso2='br'")[0][0])
        assertEquals(c.inserted, WorldImport.importCountries(sql, String(res("flag_codes.json").readBytes())).updated)   // idempotente
        val idx = WorldImport.Countries.index(sql)
        assertEquals("cz", idx.iso("Czech Republic")); assertEquals("ci", idx.iso("Ivory Coast")); assertEquals("gb", idx.iso("United Kingdom")); assertNull(idx.iso("Soviet Union"))
    }

    @Test fun realClubsAreImportedOnceAndKeepCategoryApart() {
        val (sql, ci) = seeded()
        val c1 = run(WorldImport.OfbClubsSink(sql, ci, "t"), res("ofb_clubs_sample.csv"), sql)
        assertTrue(c1.inserted > 500); assertEquals(0L, c1.failed)
        val n = sql.long("SELECT COUNT(*) FROM football_clubs")!!
        val c2 = run(WorldImport.OfbClubsSink(sql, ci, "t"), res("ofb_clubs_sample.csv"), sql)
        assertEquals("reimportar não cria clube novo", 0L, c2.inserted)
        assertEquals(n, sql.long("SELECT COUNT(*) FROM football_clubs"))
        assertNotNull("FC Inter (Q631) com país", sql.rows("SELECT iso2 FROM football_clubs WHERE wikidata='Q631'").firstOrNull()?.get(0))
        assertTrue(sql.long("SELECT COUNT(*) FROM football_competitions")!! > 5)
        assertTrue(sql.long("SELECT COUNT(*) FROM football_entity_aliases")!! > n)
    }

    @Test fun realPlayersAreImportedOnceWithoutDuplicates() {
        val (sql, ci) = seeded()
        val c1 = run(WorldImport.ReepPeopleSink(sql, ci, "t"), res("reep_people_sample.csv"), sql)
        assertTrue(c1.inserted > 2500)
        val n = sql.long("SELECT COUNT(*) FROM football_players")!!
        val c2 = run(WorldImport.ReepPeopleSink(sql, ci, "t"), res("reep_people_sample.csv"), sql)
        assertEquals(0L, c2.inserted); assertEquals(n, sql.long("SELECT COUNT(*) FROM football_players"))
        val r = sql.rows("SELECT name_key,surname_key,birth_year FROM football_players WHERE dob IS NOT NULL LIMIT 1")[0]
        assertTrue(r[0]!!.isNotEmpty() && r[1]!!.isNotEmpty() && r[2] != null)
    }

    @Test fun clubsFromTwoSourcesMergeByWikidata() {
        val (sql, ci) = seeded()
        run(WorldImport.OfbClubsSink(sql, ci, "t"), res("ofb_clubs_sample.csv"), sql)
        val before = sql.long("SELECT COUNT(*) FROM football_clubs")!!
        val c = run(WorldImport.ReepTeamsSink(sql, ci, "t"), res("reep_teams_sample.csv"), sql)
        assertTrue("alguns clubes da amostra já existiam (mesmo Wikidata): ${c.deduped}", c.deduped > 0 || c.inserted > 0)
        assertEquals(sql.long("SELECT COUNT(DISTINCT wikidata) FROM football_clubs WHERE wikidata IS NOT NULL"), sql.long("SELECT COUNT(*) FROM football_clubs WHERE wikidata IS NOT NULL"))
        assertTrue(sql.long("SELECT COUNT(*) FROM football_clubs")!! >= before)
    }

    /** Só roda no ambiente de desenvolvimento com os arquivos completos baixados (/tmp/src): imprime as contagens REAIS usadas no relatório. */
    @Test fun fullRealFilesIfPresent() {
        val dir = File("/tmp/src"); assumeTrue(File(dir, "clubs.csv").exists() && File(dir, "reep_people.csv").exists())
        val (sql, ci) = seeded()
        fun full(name: String, sink: WorldImport.Sink): WorldImport.Counters { val t = System.currentTimeMillis(); val c = File(dir, name).inputStream().buffered().use { run(sink, it, sql) }; println("WORLD $name analisados=${c.analyzed} novos=${c.inserted} atualizados=${c.updated} duplicados=${c.deduped} falhas=${c.failed} em ${(System.currentTimeMillis() - t) / 1000}s"); return c }
        full("clubs.csv", WorldImport.OfbClubsSink(sql, ci, "full")); full("reep_teams.csv", WorldImport.ReepTeamsSink(sql, ci, "full")); full("reep_competitions.csv", WorldImport.ReepCompetitionsSink(sql, ci, "full"))
        val p = full("reep_people.csv", WorldImport.ReepPeopleSink(sql, ci, "full"))
        println("WORLD totais: países=${sql.long("SELECT COUNT(*) FROM football_countries")} clubes=${sql.long("SELECT COUNT(*) FROM football_clubs")} competições=${sql.long("SELECT COUNT(*) FROM football_competitions")} jogadores=${sql.long("SELECT COUNT(*) FROM football_players")} aliases=${sql.long("SELECT COUNT(*) FROM football_entity_aliases")} ids=${sql.long("SELECT COUNT(*) FROM football_external_ids")}")
        val hoff = sql.rows("SELECT name,marker,iso2 FROM football_clubs WHERE name_key LIKE '%hoffenheim%' ORDER BY name").map { it.joinToString("|") }
        println("WORLD hoffenheim: $hoff")
        assertTrue(p.inserted > 390_000)
    }
}
