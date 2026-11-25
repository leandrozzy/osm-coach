package com.osmaicoach.collector

/**
 * Base Mundial — motor de identidade (puro, testável). Os escores são HEURÍSTICOS (0..100), não probabilidades calibradas.
 * Faixas configuráveis: 98–100 muito alta, 90–97 provável, 75–89 ambígua, <75 não resolvida. Só vira "confirmado" com evidência independente do nome,
 * margem sobre o 2º candidato e nenhuma contradição relevante. Nunca une homônimos, equipe B/principal ou clubes de países diferentes.
 */
object WorldIdentity {
    const val ALGO = "id-v1"
    class Config(val veryHigh: Int = 98, val probable: Int = 90, val ambiguous: Int = 75, val margin: Int = 6, val minSignals: Int = 2)
    val DEFAULT = Config()

    enum class Status(val label: String) { CONFIRMED("confirmado"), VERY_HIGH("muito alta"), PROBABLE("provável"), AMBIGUOUS("ambíguo"), UNRESOLVED("desconhecido") }

    class Result<T>(val best: T?, val score: Int, val status: Status, val margin: Int, val reasons: List<String>, val runnerUp: T? = null)

    // ---------------------------------------------------------------- clubes
    class ClubC(val id: Long, val name: String, val aliases: List<String> = emptyList(), val iso2: String? = null, val marker: String = "", val league: String? = null, val prominence: Int = 0)
    class ClubObs(val name: String, val iso2: String? = null, val league: String? = null, val independent: Int = 1)

    fun matchClub(o: ClubObs, cands: List<ClubC>, cfg: Config = DEFAULT): Result<ClubC> {
        val ok = WorldNorm.clubKey(o.name)
        if (ok.core.isEmpty()) return Result(null, 0, Status.UNRESOLVED, 0, listOf("nome vazio"))
        class Sc(val c: ClubC, val score: Int, val why: List<String>, val signals: Int, val contradiction: Boolean)
        val tieBreak = 3     // diferença de "prominência" (nº de cadastros que conhecem o clube) para um empate de nome ter favorito
        val scored = cands.map { c ->
            val names = listOf(c.name) + c.aliases
            val keys = names.map { WorldNorm.clubKey(it) }
            // marcador de categoria diferente (II, B, U19, feminino) = outro clube
            val sameMarker = keys.any { it.marker == ok.marker }
            var best = 0
            for (k in keys) if (k.marker == ok.marker) best = maxOf(best, WorldNorm.nameSim(ok.core, k.core))
            val why = ArrayList<String>(); var sig = 0; var contra = false
            if (best >= 98) why.add("nome igual") else why.add("nome parecido $best")
            if (!sameMarker) { best = minOf(best, 55); why.add("categoria diferente (${ok.marker.ifEmpty { "principal" }} × ${c.marker.ifEmpty { "principal" }})"); contra = true }
            if (o.iso2 != null && c.iso2 != null) { if (o.iso2.equals(c.iso2, true)) { sig++; best = minOf(100, best + 2); why.add("mesmo país") } else { best = minOf(best, 55); why.add("país diferente"); contra = true } }
            if (o.league != null && c.league != null && WorldNorm.nameSim(WorldNorm.fold(o.league), WorldNorm.fold(c.league)) >= 85) { sig++; why.add("mesma liga") }
            Sc(c, best, why, sig, contra)
        }.sortedWith(compareByDescending<Sc> { it.score }.thenByDescending { it.c.prominence })
        val top = scored.firstOrNull() ?: return Result(null, 0, Status.UNRESOLVED, 0, listOf("sem candidatos"))
        val second = scored.getOrNull(1)
        val margin = top.score - (second?.score ?: 0)
        // empate (ou quase) no nome: só há vencedor se o clube é CLARAMENTE mais conhecido que o 2º (ex.: Flamengo do Rio × Flamengo de Americana); senão fica provável, nunca "confirmado"
        val tie = second != null && margin < cfg.margin
        val favorite = tie && top.c.prominence - second!!.c.prominence >= tieBreak && !top.contradiction
        val why = if (favorite) top.why + "mais conhecido que \"${second!!.c.name}\" (${top.c.prominence} × ${second.c.prominence} cadastros)" else if (tie) top.why + "empate com \"${second!!.c.name}\": sem favorito claro" else top.why
        val extra = if (o.independent >= 2) 1 else 0
        val status = when {
            top.score < cfg.ambiguous -> Status.UNRESOLVED
            top.contradiction || (tie && !favorite) -> if (top.score >= cfg.probable) Status.PROBABLE else Status.AMBIGUOUS
            top.score >= cfg.veryHigh && top.signals + extra >= 1 -> Status.CONFIRMED
            top.score >= cfg.veryHigh -> Status.VERY_HIGH
            top.score >= cfg.probable -> Status.PROBABLE
            else -> Status.AMBIGUOUS
        }
        return Result(if (status == Status.UNRESOLVED) null else top.c, top.score, status, margin, why, scored.getOrNull(1)?.c)
    }

    // ---------------------------------------------------------------- jogadores
    class PlayerC(val id: Long, val name: String, val fullName: String? = null, val aliases: List<String> = emptyList(), val birthYear: Int? = null, val nationality: String? = null, val position: String? = null)
    /** `ageNow` = idade mostrada no jogo (camada OSM: só evidência suave); `year` = ano de referência da leitura. */
    class PlayerObs(val name: String, val ageNow: Int? = null, val year: Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR), val nationality: String? = null, val position: String? = null, val independent: Int = 1)

    private val POS = mapOf("gk" to "G", "gol" to "G", "goalkeeper" to "G", "goleiro" to "G", "gl" to "G", "def" to "D", "defender" to "D", "df" to "D", "de" to "D", "dc" to "D", "dd" to "D", "dl" to "D", "mid" to "M", "midfielder" to "M", "mei" to "M", "mc" to "M", "mco" to "M", "mcd" to "M", "mf" to "M", "mo" to "M",
        "att" to "A", "attacker" to "A", "forward" to "A", "ata" to "A", "pl" to "A", "ee" to "A", "ed" to "A", "fw" to "A", "striker" to "A")
    fun posGroup(p: String?): String? = p?.let { WorldNorm.fold(it).split(' ').firstNotNullOfOrNull { w -> POS[w] } ?: when { it.contains("keep", true) -> "G"; it.contains("back", true) || it.contains("defen", true) -> "D"; it.contains("mid", true) -> "M"; it.contains("wing", true) || it.contains("forw", true) || it.contains("strik", true) || it.contains("attack", true) -> "A"; else -> null } }

    fun matchPlayer(o: PlayerObs, cands: List<PlayerC>, cfg: Config = DEFAULT): Result<PlayerC> {
        val ok = WorldNorm.playerKey(o.name)
        if (ok.full.isEmpty()) return Result(null, 0, Status.UNRESOLVED, 0, listOf("nome vazio"))
        val oNat = o.nationality?.let { WorldNorm.fold(it) }
        class Sc(val c: PlayerC, val score: Int, val why: List<String>, val signals: Int, val contra: Boolean)
        val scored = cands.map { c ->
            val keys = (listOf(c.name, c.fullName) + c.aliases).filterNotNull().map { WorldNorm.playerKey(it) }
            var name = 0
            for (k in keys) {
                val s = when {
                    k.full == ok.full -> 100
                    k.surname == ok.surname && ok.given.isEmpty() -> 62                                   // só o sobrenome no jogo ("Sarr", "Bibo"): nome fraco
                    k.surname == ok.surname && ok.initial.isNotEmpty() && (k.initial == ok.initial) && ok.given.all { it.length <= 2 } -> 90   // "A. Kurti" × "Arbër Kurti"
                    k.surname == ok.surname && ok.given.isNotEmpty() && k.given.isNotEmpty() && WorldNorm.nameSim(ok.given.joinToString(" "), k.given.joinToString(" ")) >= 80 -> 94
                    else -> (WorldNorm.nameSim(ok.full, k.full) * 0.9).toInt()
                }
                name = maxOf(name, s)
            }
            val why = ArrayList<String>(); var sig = 0; var contra = false
            var score = name
            why.add(when { name >= 100 -> "nome igual"; name >= 90 -> "nome compatível (inicial/abreviação)"; name >= 60 -> "só sobrenome/nome parcial" else -> "nome diferente" })
            val c0 = c
            if (oNat != null && c0.nationality != null) {
                val cn = WorldNorm.fold(c0.nationality)
                val natOk = cn == oNat || cn.contains(oNat) || oNat.contains(cn) || (cn == "united kingdom" && oNat in setOf("england", "scotland", "wales", "northern ireland", "inglaterra", "escocia", "gales"))
                if (natOk) { sig++; score += 10; why.add("mesma nacionalidade") } else { score -= 25; contra = true; why.add("nacionalidade diferente") }
            }
            if (o.ageNow != null && c0.birthYear != null) {
                val age = o.year - c0.birthYear
                if (kotlin.math.abs(age - o.ageNow) <= 1) { sig++; score += 8; why.add("idade compatível") } else if (kotlin.math.abs(age - o.ageNow) >= 4) { score -= 20; contra = true; why.add("idade incompatível") }
            }
            val gp = posGroup(o.position); val cp = posGroup(c0.position)
            if (gp != null && cp != null) { if (gp == cp) { sig++; score += 5; why.add("mesma posição") } else { score -= 8; why.add("posição diferente") } }
            Sc(c, score.coerceIn(0, 100), why, sig, contra)
        }.sortedByDescending { it.score }
        val top = scored.firstOrNull() ?: return Result(null, 0, Status.UNRESOLVED, 0, listOf("sem candidatos"))
        val second = scored.getOrNull(1)?.score ?: 0
        val margin = top.score - second
        val independent = top.signals + (if (o.independent >= 2) 1 else 0)
        val status = when {
            top.score < cfg.ambiguous -> Status.UNRESOLVED
            top.contra -> Status.AMBIGUOUS
            margin < cfg.margin -> if (top.score >= cfg.probable) Status.PROBABLE else Status.AMBIGUOUS
            top.score >= cfg.veryHigh && independent >= cfg.minSignals -> Status.CONFIRMED
            top.score >= cfg.veryHigh -> Status.VERY_HIGH
            top.score >= cfg.probable -> Status.PROBABLE
            else -> Status.AMBIGUOUS
        }
        return Result(if (status == Status.UNRESOLVED) null else top.c, top.score, status, margin, top.why, scored.getOrNull(1)?.c)
    }
}
