package com.osmaicoach.collector

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Segunda fonte de jogadores da Base: API-Football (`/players/profiles?search=`), com a chave do PRÓPRIO usuário (Ajustes → Fotos de jogadores).
 * Entra quando o Supabase (Reep) não tem o jogador ou só tem candidatos fracos: o jogador vira um cadastro local (com o ID da API-Football, então a foto sai
 * sem gastar cota) e passa pelo MESMO motor de identidade. Só o NOME sai do aparelho. Reserva própria de 30 consultas/dia para identidade.
 */
object WorldApif {
    private const val HOST = "https://v3.football.api-sports.io"
    private const val SRC = "apif_identity"
    private const val DAILY = 30

    fun enabled(ctx: Context) = Settings.get(ctx, PhotoWeb.KEY, "").isNotBlank()

    class Hit(val id: String, val name: String, val full: String?, val dob: String?, val nationality: String?, val position: String?, val heightCm: Int?)

    internal fun parse(body: String): List<Hit> {
        val arr = runCatching { JSONObject(body).optJSONArray("response") }.getOrNull() ?: return emptyList()
        val out = ArrayList<Hit>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i)?.optJSONObject("player") ?: continue
            val id = p.optInt("id", 0).takeIf { it > 0 } ?: continue
            val first = p.optString("firstname").takeIf { it.isNotBlank() && it != "null" }; val last = p.optString("lastname").takeIf { it.isNotBlank() && it != "null" }
            val full = listOfNotNull(first, last).joinToString(" ").takeIf { it.isNotBlank() }
            val name = full ?: p.optString("name").takeIf { it.isNotBlank() } ?: continue
            val dob = p.optJSONObject("birth")?.optString("date")?.takeIf { it.length >= 10 && it != "null" }?.take(10)
            val h = Regex("\\d+").find(p.optString("height"))?.value?.toIntOrNull()
            out.add(Hit(id.toString(), name, full, dob, p.optString("nationality").takeIf { it.isNotBlank() && it != "null" }, p.optString("position").takeIf { it.isNotBlank() && it != "null" }, h))
        }
        return out
    }

    /** Busca pelo sobrenome (a API exige ≥3 letras) e guarda o que veio como candidato local. Devolve quantos candidatos entraram; -1 = sem chave/limite/erro. */
    fun search(ctx: Context, db: WorldSql, raw: String, force: Boolean = false): Int {
        if (!enabled(ctx)) return -1
        val pk = WorldNorm.playerKey(raw); val term = pk.surname.split(' ').maxByOrNull { it.length } ?: return -1
        if (term.length < 3) return -1
        val wdb = WorldDb.get(ctx)
        if (!force && wdb.callsToday(SRC) >= DAILY) return -1
        wdb.bumpUsage(SRC, 200, DAILY)
        val body = Live.run("Base: procurando $raw (API-Football)") {
            try {
                val c = URL("$HOST/players/profiles?search=" + java.net.URLEncoder.encode(term, "UTF-8")).openConnection() as HttpURLConnection
                c.connectTimeout = 12000; c.readTimeout = 20000
                c.setRequestProperty("User-Agent", "OSMCoach/5"); c.setRequestProperty("x-apisports-key", Settings.get(ctx, PhotoWeb.KEY, ""))
                if (c.responseCode in 200..299) c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) } else null
            } catch (e: Exception) { null }
        } ?: return -1
        val hits = parse(body)
        var n = 0
        for (h in hits.take(40)) {
            val k = WorldNorm.playerKey(h.name); if (k.surname.isEmpty() || !WorldClean.nameOk(h.name)) continue
            val iso = h.nationality?.let { nat -> db.q("SELECT iso2 FROM football_countries WHERE lower(name_en)=?", nat.lowercase()) { it.getString(0) }.firstOrNull() }
            var id = db.long("SELECT id FROM football_players WHERE apif_id=?", h.id)
            if (id == null) {
                db.exec("INSERT INTO football_players(name,full_name,name_key,surname_key,initial,dob,birth_year,nationality,nationality_iso,position,height_cm,apif_id,source_id,status,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'desconhecido',?)",
                    arrayOf<Any?>(h.name, h.full, k.full, k.surname, k.initial, h.dob, h.dob?.take(4)?.toIntOrNull(), h.nationality, iso, h.position, h.heightCm, h.id, "api-football", System.currentTimeMillis()))
                id = db.long("SELECT last_insert_rowid()")
            }
            if (id != null) { db.exec("INSERT OR IGNORE INTO football_pull_hits(kind,key,entity_id) VALUES('player',?,?)", arrayOf<Any?>(pk.full, id)); n++ }
        }
        return n
    }
}
