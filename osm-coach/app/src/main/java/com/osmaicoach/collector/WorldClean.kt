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

    /** Remove o que não serve ao OSM (uma passada, idempotente). Devolve quantas linhas saíram. */
    fun purge(db: WorldSql): Long {
        var n = 0L
        fun ids(sql: String, ok: (List<String?>) -> Boolean): List<Long> = ArrayList<Long>().also { out -> for (r in db.rows(sql)) if (!ok(r)) out.add(r[0]!!.toLong()) }
        fun del(table: String, ids: List<Long>) {
            for (chunk in ids.chunked(500)) { val ph = chunk.joinToString(","); n += db.exec("DELETE FROM $table WHERE id IN ($ph)", emptyArray()) }
        }
        db.begin()
        try {
            del("football_players", ids("SELECT id,name FROM football_players") { nameOk(it[1]) })
            del("football_clubs", ids("SELECT id,name,marker,category FROM football_clubs") { nameOk(it[1]) && !(it[2] ?: "").contains("W") && !isWomen(it[1]) && !isWomen(it[3]) })
            del("football_competitions", ids("SELECT id,name,gender,category FROM football_competitions") { nameOk(it[1]) && !isWomen(it[1]) && !isWomen(it[3]) && (it[2] ?: "").lowercase() !in setOf("f", "female", "women", "feminino") })
            n += db.exec("DELETE FROM football_entity_aliases WHERE entity_type='club' AND entity_id NOT IN (SELECT id FROM football_clubs)", emptyArray())
            n += db.exec("DELETE FROM football_entity_aliases WHERE entity_type='player' AND entity_id NOT IN (SELECT id FROM football_players)", emptyArray())
            n += db.exec("DELETE FROM football_competition_clubs WHERE club_id NOT IN (SELECT id FROM football_clubs) OR competition_id NOT IN (SELECT id FROM football_competitions)", emptyArray())
            db.commit()
        } catch (e: Exception) { runCatching { db.rollback() }; throw e }
        return n
    }
}
