package com.osmaicoach.collector

/** Base Mundial — contagens REAIS (e série temporal) para o painel e a cobertura. Nenhum percentual é inventado: só contagens e, quando o denominador é conhecido, a razão. */
object WorldCoverage {
    class Totals(val countries: Long, val confederations: Long, val associations: Long, val competitions: Long, val seasons: Long, val clubs: Long, val players: Long, val crests: Long, val photos: Long, val flags: Long,
        val ocrConfirmed: Long, val ocrProbable: Long, val ocrAmbiguous: Long, val ocrUnknown: Long, val osmClubs: Long, val osmPlayers: Long, val pending: Long, val divergences: Long, val duplicatesResolved: Long, val dbBytes: Long)

    fun totals(ctx: android.content.Context, db: WorldDb): Totals {
        fun c(t: String, w: String? = null, vararg a: String) = db.count(t, w, *a)
        val st = { s: String -> c("football_osm_entity_mappings", "status=?", s) }
        return Totals(c("football_countries"), c("football_confederations"), c("football_associations"), c("football_competitions"), c("football_competition_seasons"), c("football_clubs"), c("football_players"),
            c("football_clubs", "logo_url IS NOT NULL"), c("football_images", "kind='photo' AND status='ok'"), c("football_countries", "flag_url IS NOT NULL"),
            st("confirmado"), st("provável") + st("muito alta"), st("ambíguo"), c("football_unresolved_entities"), c("football_clubs", "osm_seen>0"), c("football_players", "osm_seen>0"),
            c("football_ocr_observations", "status='novo'"), c("football_validation_history", "action='desfeito' OR action='divergência'"), c("football_validation_history", "action='deduplicado'"), db.fileSize(ctx))
    }

    /** Guarda uma fotografia das contagens principais (no máx. 1 por 6 h) para o gráfico de evolução. */
    fun snapshot(db: WorldDb) {
        val last = db.scalar("SELECT MAX(at) FROM football_coverage_metrics WHERE metric='players'") ?: 0L
        if (System.currentTimeMillis() - last < 6 * 3_600_000L) return
        db.metric("players", "", db.count("football_players")); db.metric("clubs", "", db.count("football_clubs")); db.metric("competitions", "", db.count("football_competitions"))
        db.metric("ocr_confirmed", "", db.count("football_osm_entity_mappings", "status='confirmado'")); db.metric("images", "", db.count("football_images", "status='ok'"))
    }

    class Row(val label: String, val a: Long, val b: Long, val c: Long)
    /** Por país (clubes, competições, jogadores) — só dos países com algum dado. */
    fun byCountry(db: WorldDb, limit: Int = 300): List<Triple<String, String, LongArray>> = db.query(
        "SELECT c.iso2, c.name_pt, c.name_en, (SELECT COUNT(*) FROM football_clubs x WHERE x.iso2=c.iso2), (SELECT COUNT(*) FROM football_competitions y WHERE y.iso2=c.iso2), (SELECT COUNT(*) FROM football_players p WHERE p.nationality_iso=c.iso2) FROM football_countries c ORDER BY 4 DESC LIMIT $limit"
    ) { cu -> Triple(cu.getString(0), cu.getString(1) ?: cu.getString(2), longArrayOf(cu.getLong(3), cu.getLong(4), cu.getLong(5))) }.filter { it.third.sum() > 0 }
}
