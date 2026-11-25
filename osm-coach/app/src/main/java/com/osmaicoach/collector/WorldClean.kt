package com.osmaicoach.collector

/**
 * Base Mundial — higiene dos dados. O OSM só tem nomes em alfabeto latino (inglês/espanhol/português…) e não tem futebol feminino:
 * nomes em outros alfabetos, com "?" / "�" (texto corrompido) e competições/clubes femininos nunca entram — e o que já entrou é removido ([purge]).
 */
object WorldClean {
    private val WOMEN = Regex("(^|[^a-z])(women|womens|woman|female|femin[a-z]*|femen[a-z]*|femmes?|frauen|damen|ladies|vrouwen|kobiet|kvinner|kvinnor|damallsvenskan|nwsl|wsl|\\(w\\)|w-league|liga f)([^a-z]|$)", RegexOption.IGNORE_CASE)

    /** Só letras latinas (com acento), números e pontuação comum; sem "?" nem "�". */
    fun nameOk(s: String?): Boolean {
        if (s == null) return false
        val t = s.trim(); if (t.length < 2) return false
        var letters = 0
        for (ch in t) {
            if (ch == '?' || ch == '�') return false
            if (Character.isLetter(ch)) { if (Character.UnicodeScript.of(ch.code) != Character.UnicodeScript.LATIN) return false; letters++ }
        }
        return letters >= 2
    }
    fun isWomen(s: String?): Boolean = s != null && WorldNorm.fold(s).let { WOMEN.containsMatchIn(it) || it.endsWith(" w") }

    /** Remove o que não serve ao OSM (idempotente). Em BLOCOS de 5 000 linhas com transação curta: nunca carrega a tabela inteira na memória nem segura o banco por muito tempo. */
    fun purge(db: WorldSql): Long {
        var n = 0L
        fun sweep(table: String, cols: String, ok: (List<String?>) -> Boolean) {
            var last = 0L
            while (true) {
                val page = db.rows("SELECT id,$cols FROM $table WHERE id>? ORDER BY id LIMIT 5000", last.toString())
                if (page.isEmpty()) break
                last = page.last()[0]!!.toLong()
                val bad = page.filter { !ok(it) }.map { it[0]!!.toLong() }
                if (bad.isNotEmpty()) { db.begin(); try { for (c in bad.chunked(400)) n += db.exec("DELETE FROM $table WHERE id IN (${c.joinToString(",")})", emptyArray()); db.commit() } catch (e: Exception) { runCatching { db.rollback() }; throw e } }
                Thread.sleep(5)    // deixa o OCR/captura usar o banco entre os blocos
            }
        }
        sweep("football_players", "name") { nameOk(it[1]) }
        sweep("football_clubs", "name,marker,category") { nameOk(it[1]) && !(it[2] ?: "").contains("W") && !isWomen(it[1]) && !isWomen(it[3]) }
        sweep("football_competitions", "name,gender,category") { nameOk(it[1]) && !isWomen(it[1]) && !isWomen(it[3]) && (it[2] ?: "").lowercase() !in setOf("f", "female", "women", "feminino") }
        n += db.exec("DELETE FROM football_entity_aliases WHERE entity_type='club' AND entity_id NOT IN (SELECT id FROM football_clubs)", emptyArray())
        n += db.exec("DELETE FROM football_entity_aliases WHERE entity_type='player' AND entity_id NOT IN (SELECT id FROM football_players)", emptyArray())
        n += db.exec("DELETE FROM football_competition_clubs WHERE club_id NOT IN (SELECT id FROM football_clubs) OR competition_id NOT IN (SELECT id FROM football_competitions)", emptyArray())
        return n
    }

    /**
     * Migração única para o modelo "base no Supabase": mantém na cópia local SÓ o que o OSM mostrou (ou veio do Supabase) e descarta o resto da importação em massa antiga.
     * Rápido e atômico: copia o que fica para uma tabela temporária, esvazia a tabela com `DELETE` sem filtro (o SQLite "trunca" sem mexer índice por índice) e devolve o que ficou.
     * Antes (linha a linha, 388 mil jogadores) levava muitos minutos no celular. Em caso de falha tudo volta atrás e tenta de novo depois. Observações e mapeamentos do OCR ficam.
     */
    fun slim(db: WorldSql): Long {
        var removed = 0L
        db.begin()
        try {
            fun keepOnly(table: String, keep: String) {
                val before = db.long("SELECT COUNT(*) FROM $table") ?: 0L
                db.exec("DROP TABLE IF EXISTS temp._keep", emptyArray())
                db.exec("CREATE TEMP TABLE _keep AS SELECT * FROM $table WHERE $keep", emptyArray())
                db.exec("DELETE FROM $table", emptyArray())
                db.exec("INSERT INTO $table SELECT * FROM temp._keep", emptyArray())
                db.exec("DROP TABLE temp._keep", emptyArray())
                removed += before - (db.long("SELECT COUNT(*) FROM $table") ?: 0L)
            }
            keepOnly("football_players", "osm_seen>0 OR remote_id IS NOT NULL OR id IN (SELECT entity_id FROM football_osm_entity_mappings WHERE entity_type='player' AND entity_id IS NOT NULL)")
            keepOnly("football_clubs", "osm_seen>0 OR remote_id IS NOT NULL OR id IN (SELECT entity_id FROM football_osm_entity_mappings WHERE entity_type='club' AND entity_id IS NOT NULL)")
            db.exec("DELETE FROM football_competition_clubs WHERE club_id NOT IN (SELECT id FROM football_clubs)", emptyArray())
            keepOnly("football_competitions", "osm_seen>0 OR remote_id IS NOT NULL OR id IN (SELECT competition_id FROM football_competition_clubs)")
            keepOnly("football_entity_aliases", "(entity_type='club' AND entity_id IN (SELECT id FROM football_clubs)) OR (entity_type='player' AND entity_id IN (SELECT id FROM football_players))")
            db.exec("DELETE FROM football_sync_checkpoints", emptyArray())
            db.commit()
        } catch (e: Exception) { runCatching { db.rollback() }; throw e }
        return removed
    }
}
