package com.osmaicoach.collector

import android.content.Context
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Base Mundial — Wikidata (CC0): (1) confederação de cada país, (2) enriquecimento de jogadores/clubes OBSERVADOS no OCR que a base local não resolveu.
 * Educado: 1 consulta a cada 1,2 s, User-Agent próprio, consultas curtas (SPARQL com VALUES em blocos de 40; o endpoint estoura timeout em consultas pesadas), backoff em 429/503.
 */
object WorldWikidata {
    private const val SPARQL = "https://query.wikidata.org/sparql"
    private const val API = "https://www.wikidata.org/w/api.php"
    private var lastCall = 0L
    @Volatile private var blockedUntil = 0L

    /** Confederações (QIDs verificados no Wikidata em 25/11/26) e o continente de cada uma. */
    val CONFEDERATIONS = listOf(
        Triple("UEFA", "Q35572", "Europa"), Triple("CONMEBOL", "Q58733", "América do Sul"), Triple("CONCACAF", "Q160549", "América do Norte, Central e Caribe"),
        Triple("AFC", "Q83276", "Ásia"), Triple("CAF", "Q168360", "África"), Triple("OFC", "Q180344", "Oceania")
    )

    private fun throttle() { val w = 1200L - (System.currentTimeMillis() - lastCall); if (w > 0) Thread.sleep(w); lastCall = System.currentTimeMillis() }

    private fun get(db: WorldDb, url: String, source: String): String? {
        if (System.currentTimeMillis() < blockedUntil) return null
        throttle()
        val (code, body) = WorldHttp.text(url, mapOf("Accept" to "application/json"))
        db.bumpUsage(source, code)
        if (code == 429 || code == 503) { blockedUntil = System.currentTimeMillis() + 10 * 60_000L; return null }
        return if (code in 200..299) body else null
    }

    private fun sparql(db: WorldDb, q: String): List<JSONObject>? {
        val body = get(db, "$SPARQL?format=json&query=" + URLEncoder.encode(q, "UTF-8"), WorldSources.WIKIDATA) ?: return null
        val arr = JSONObject(body).getJSONObject("results").getJSONArray("bindings")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }
    private fun v(o: JSONObject, k: String) = o.optJSONObject(k)?.optString("value")?.takeIf { it.isNotEmpty() }

    /**
     * País → confederação: itens com "membro de" (P463) = a confederação (federações e seleções) → país (P17) → código ISO (P297). Quando um país aparece em mais de uma
     * confederação (ex.: Austrália), vale a que tem mais itens e a confiança fica menor. Só roda se ainda falta confederação em muitos países.
     */
    fun syncConfederations(ctx: Context, db: WorldDb) {
        if ((db.scalar("SELECT COUNT(*) FROM football_countries WHERE confederation_id IS NOT NULL") ?: 0L) > 150L) return
        val now = System.currentTimeMillis()
        for ((code, q, cont) in CONFEDERATIONS) db.w.execSQL("INSERT INTO football_confederations(code,name,continent,wikidata,source_id,updated_at) VALUES(?,?,?,?,?,?) ON CONFLICT(code) DO UPDATE SET wikidata=excluded.wikidata,continent=excluded.continent,updated_at=excluded.updated_at", arrayOf(code, code, cont, q, WorldSources.WIKIDATA, now))
        val votes = HashMap<String, HashMap<String, Int>>()          // iso2 → (confederação → itens)
        for ((code, q, _) in CONFEDERATIONS) {
            val members = sparql(db, "SELECT ?a WHERE { ?a wdt:P463 wd:$q } LIMIT 600") ?: return
            val ids = members.mapNotNull { v(it, "a")?.substringAfterLast('/') }.filter { it.startsWith("Q") }
            for (chunk in ids.chunked(40)) {
                val rows = sparql(db, "SELECT ?iso WHERE { VALUES ?a { ${chunk.joinToString(" ") { "wd:$it" }} } ?a wdt:P17 ?c . ?c wdt:P297 ?iso }") ?: return
                for (r in rows) v(r, "iso")?.lowercase()?.let { iso -> votes.getOrPut(iso) { HashMap() }.merge(code, 1, Int::plus) }
            }
        }
        for ((iso, m) in votes) {
            val best = m.maxByOrNull { it.value }!!
            val conf = db.scalar("SELECT id FROM football_confederations WHERE code=?", best.key) ?: continue
            db.w.execSQL("UPDATE football_countries SET confederation_id=?,continent=(SELECT continent FROM football_confederations WHERE id=?),updated_at=? WHERE iso2=?", arrayOf(conf, conf, now, iso))
            db.w.execSQL("INSERT OR REPLACE INTO football_field_provenance(entity_type,entity_id,field,source_id,version,confidence,at) SELECT 'country',id,'confederation',?,?,?,? FROM football_countries WHERE iso2=?", arrayOf(WorldSources.WIKIDATA, "P463+P17", if (m.size > 1) 60 else 85, now, iso))
        }
        // subdivisões do Reino Unido herdam do país-mãe (as associações da Inglaterra, Escócia, Gales e Irlanda do Norte são da UEFA)
        db.w.execSQL("UPDATE football_countries SET confederation_id=(SELECT confederation_id FROM football_countries WHERE iso2='gb'),continent=(SELECT continent FROM football_countries WHERE iso2='gb') WHERE iso2 IN ('gb-eng','gb-sct','gb-wls','gb-nir') AND confederation_id IS NULL")
    }

    class PlayerHit(val qid: String, val name: String, val dob: String?, val nationalityIso: String?, val position: String?, val image: String?)

    /** Busca um jogador pelo nome (só itens descritos como futebolista) e traz nascimento, nacionalidade, posição e imagem (arquivo do Commons). Devolve até 5. */
    fun searchPlayers(ctx: Context, db: WorldDb, name: String): List<PlayerHit>? {
        val body = get(db, "$API?action=wbsearchentities&format=json&language=en&type=item&limit=7&search=" + URLEncoder.encode(name, "UTF-8"), WorldSources.WIKIDATA) ?: return null
        val arr = JSONObject(body).optJSONArray("search") ?: return emptyList()
        val ids = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { o -> val d = o.optString("description").lowercase(); d.contains("footballer") || d.contains("soccer player") || d.contains("football player") }.map { it.getString("id") }
        if (ids.isEmpty()) return emptyList()
        val rows = sparql(db, "SELECT ?p ?pLabel ?dob ?iso ?posLabel ?img WHERE { VALUES ?p { ${ids.joinToString(" ") { "wd:$it" }} } OPTIONAL{?p wdt:P569 ?dob} OPTIONAL{?p wdt:P27 ?c . ?c wdt:P297 ?iso} OPTIONAL{?p wdt:P413 ?pos} OPTIONAL{?p wdt:P18 ?img} SERVICE wikibase:label { bd:serviceParam wikibase:language \"en\". } }") ?: return null
        return rows.groupBy { v(it, "p") }.mapNotNull { (p, g) -> p?.let { PlayerHit(it.substringAfterLast('/'), v(g[0], "pLabel") ?: name, v(g[0], "dob")?.take(10), g.firstNotNullOfOrNull { r -> v(r, "iso") }?.lowercase(), g.firstNotNullOfOrNull { r -> v(r, "posLabel") }, g.firstNotNullOfOrNull { r -> v(r, "img") }) } }
    }

    /** Grava o jogador do Wikidata na camada REAL (sem duplicar o Wikidata) e devolve o id. A imagem fica pendente da checagem de licença do Commons. */
    fun savePlayer(db: WorldDb, ci: WorldImport.Countries.Index, h: PlayerHit): Long {
        val k = WorldNorm.playerKey(h.name); val now = System.currentTimeMillis()
        db.scalar("SELECT id FROM football_players WHERE wikidata=?", h.qid)?.let { return it }
        db.w.execSQL("INSERT INTO football_players(name,name_key,surname_key,initial,dob,birth_year,nationality_iso,position,wikidata,source_id,status,confidence,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(h.name, k.full, k.surname, k.initial, h.dob, h.dob?.take(4)?.toIntOrNull(), h.nationalityIso, h.position, h.qid, WorldSources.WIKIDATA, "desconhecido", 85, now))
        val id = db.scalar("SELECT last_insert_rowid()")!!
        h.image?.let { url -> db.w.execSQL("INSERT OR IGNORE INTO football_images(entity_type,entity_id,kind,url,source_id,status) VALUES('player',?,?,?,?,'pendente')", arrayOf(id, "photo", url, WorldSources.WIKIDATA)) }
        return id
    }
}
