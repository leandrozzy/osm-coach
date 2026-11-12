package com.osmaicoach.collector

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Base de conhecimento da web (atualizada sozinha 1×/semana): lê as páginas das fontes de táticas do OSM, transforma as TABELAS
 * em regras (formação, estilo, controles, linhas) e guarda faixas de controles por formação. Nada disso decide sozinho: entra como
 * candidato (marcado com a fonte) e o seu histórico real escolhe entre fontes que discordam (`Meta.resolve`).
 * Planos globais (slot 0): `kb_rules` (regras), `kb_bands` (faixas), `kb_status` (o que foi lido e quando).
 */
object WebKnow {
    const val RULES = "kb_rules"
    const val BANDS = "kb_bands"
    const val STATUS = "kb_status"
    private const val WEEK = 7L * 86400000L
    @Volatile var running = false

    data class Cand(val key: String, val rule: JSONObject, val prio: Int)
    data class PageRes(val url: String, val http: Int, val chars: Int, val parsed: Int, val hash: String, val changed: Boolean, val note: String = "")

    // ------------------------------------------------------------ rede e texto

    fun httpGet(url: String): Pair<Int, String?> = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) OSMCoach")
        c.setRequestProperty("Accept-Language", "en")
        val code = c.responseCode
        if (code in 200..299) Pair(code, c.inputStream.bufferedReader().use { it.readText() }) else Pair(code, null)
    } catch (e: Exception) { Pair(-1, null) }

    fun toText(html: String): String = html
        .replace(Regex("(?s)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&#x27;", "'").replace("&#39;", "'").replace("&quot;", "\"").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun sha(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)

    // ------------------------------------------------------------ vocabulário (inglês das fontes -> valores do jogo)

    private fun firstOf(s: String, vararg pairs: Pair<String, String>): String? {
        val n = s.lowercase()
        return pairs.mapNotNull { (k, v) -> n.indexOf(k).takeIf { it >= 0 }?.let { it to v } }.minByOrNull { it.first }?.second
    }
    fun enAttack(s: String) = firstOf(s, "attack only" to "Atacar apenas", "support" to "Apoiar meio-campo", "drop deep" to "Ajudar a defender")
    fun enMid(s: String) = firstOf(s, "stay in position" to "Manter posições", "protect" to "Ajudar a defesa", "push forward" to "Pressionar na frente")
    fun enDef(s: String) = firstOf(s, "defend deep" to "Defender atrás", "attacking full" to "Defesas atacantes", "support" to "Apoiar meio-campo")
    fun enStyle(s: String): String? = Osm.style(s) ?: if (s.lowercase().contains("park")) "Contra-ataque" else null
    private fun enMarking(s: String) = if (s.lowercase().startsWith("man")) "Homem-a-homem" else "À zona"

    private val FORM = "\\d-\\d-\\d(?:-\\d)?(?: [AB])?"
    private fun formationIn(s: String): String? = Regex(FORM).find(s)?.value?.let { Formations.canonical(it) }

    private fun mid(a: String, b: String) = (a.toInt() + b.toInt()) / 2

    // ------------------------------------------------------------ osmcoach.com: biblioteca de táticas

    private fun relationsOf(useWhen: String): List<String> {
        val u = useWhen.lowercase()
        if (u.contains("mandatory") || u.contains("desperat") || u.contains("chase")) return emptyList()
        val out = ArrayList<String>()
        if (Regex("favou?rite|stronger|dominant").containsMatchIn(u)) out.add("F")
        if (Regex("equal|balanced").containsMatchIn(u)) out.add("E")
        if (Regex("underdog|weaker|protecting|slightly weaker").containsMatchIn(u)) out.add("U")
        return out
    }

    /** "6.1 Dominant 4-3-3 A Wing Play Use when: ... Setting Value Formation ... Risk: ..." -> regras. */
    fun parseLibrary(text: String, source: String = "web:osmcoach.com"): List<Cand> {
        val out = ArrayList<Cand>()
        val parts = text.split(Regex("\\b0\\d 6\\.\\d+ |\\b1\\d 6\\.\\d+ "))
        for (sec in parts.drop(1)) {
            val use = Regex("Use when:\\s*(.+?)\\s+Setting Value").find(sec)?.groupValues?.get(1) ?: continue
            fun f(a: String, b: String) = Regex("$a\\s+(.+?)\\s+$b").find(sec)?.groupValues?.get(1)?.trim()
            val form = formationIn(f("Formation", "Game plan") ?: continue) ?: continue
            val plan = enStyle(f("Game plan", "Forwards") ?: continue) ?: continue
            val fw = f("Forwards", "Midfield") ?: ""
            val md = f("Midfield", "Defence") ?: ""
            val df = f("Defence", "Pressing") ?: ""
            fun rng(n: String) = Regex("$n\\s+(\\d+)\\s*[–-]\\s*(\\d+)").find(sec)?.let { Pair(it.groupValues[1], it.groupValues[2]) }
            val pr = rng("Pressing") ?: continue; val st = rng("Style") ?: continue; val tp = rng("Tempo") ?: continue
            val marking = Regex("Marking\\s+(\\S+)").find(sec)?.groupValues?.get(1) ?: "Zonal"
            val off = Regex("Offside\\s+(\\S+)").find(sec)?.groupValues?.get(1) ?: "No"
            val risk = Regex("Risk:\\s*(.+?)(?:\\.\\s|\\.$|$)").find(sec)?.groupValues?.get(1)
            val rival = Regex("against (?:[a-z]+ ){0,2}(\\d-\\d-\\d(?:-\\d)?)").find(use)?.groupValues?.get(1)
            val rule = JSONObject().put("formation", form).put("style", plan)
                .put("pressure", mid(pr.first, pr.second)).put("mentality", mid(st.first, st.second)).put("tempo", mid(tp.first, tp.second))
                .put("marking", enMarking(marking)).put("offside", if (off.lowercase() == "yes") "Sim" else "Não")
                .put("source", "$source/biblioteca").put("use", use.take(160)).put("conf", "média")
                .put("range", JSONObject().put("pressure", JSONArray(listOf(pr.first.toInt(), pr.second.toInt()))).put("mentality", JSONArray(listOf(st.first.toInt(), st.second.toInt()))).put("tempo", JSONArray(listOf(tp.first.toInt(), tp.second.toInt()))))
            enAttack(fw)?.let { rule.put("attack", it) }; enMid(md)?.let { rule.put("mid", it) }; enDef(df)?.let { rule.put("def", it) }
            risk?.let { rule.put("risk", it.take(180)) }
            for (rel in relationsOf(use)) out.add(Cand("$rel|${rival?.let { Formations.base(it) } ?: "?"}", JSONObject(rule.toString()), 2))
        }
        return out
    }

    // ------------------------------------------------------------ osmcoach.com/counter/*: mais forte / parelho / mais fraco

    fun parseCounter(text: String, lib: Map<String, JSONObject>, source: String = "web:osmcoach.com"): List<Cand> {
        val rival = Regex("Counter ($FORM)").find(text)?.groupValues?.get(1)?.let { Formations.base(it) } ?: return emptyList()
        val rx = Regex("(Stronger|Approximately equal|Equal|Weaker)\\s+($FORM)\\s+([A-Za-z][A-Za-z ]*?)\\s+(\\d+)[–-](\\d+)\\s*/\\s*(\\d+)[–-](\\d+)\\s*/\\s*(\\d+)[–-](\\d+)\\s+(\\w+)\\s*·\\s*(\\w+)")
        val out = ArrayList<Cand>()
        for (m in rx.findAll(text)) {
            val g = m.groupValues
            val form = Formations.canonical(g[2]) ?: continue
            val style = enStyle(g[3]) ?: continue
            val rel = when (g[1]) { "Stronger" -> "F"; "Weaker" -> "U"; else -> "E" }
            val rule = JSONObject().put("formation", form).put("style", style)
                .put("pressure", mid(g[4], g[5])).put("mentality", mid(g[6], g[7])).put("tempo", mid(g[8], g[9]))
                .put("source", "$source/contra-$rival").put("conf", if (g[11].lowercase() == "high") "alta" else "média")
                .put("range", JSONObject().put("pressure", JSONArray(listOf(g[4].toInt(), g[5].toInt()))).put("mentality", JSONArray(listOf(g[6].toInt(), g[7].toInt()))).put("tempo", JSONArray(listOf(g[8].toInt(), g[9].toInt()))))
            lib[form]?.let { l -> for (n in listOf("attack", "mid", "def", "marking", "offside", "risk")) if (l.has(n)) rule.put(n, l.get(n)) }
            out.add(Cand("$rel|$rival", rule, 3))
        }
        return out
    }

    // ------------------------------------------------------------ osmnextlevel.com/formations/*: casa/fora × força

    fun parseNextLevel(text: String, lib: Map<String, JSONObject>, source: String = "web:osmnextlevel.com"): List<Cand> {
        val rival = Regex("Counter ($FORM)").find(text)?.groupValues?.get(1)?.let { Formations.base(it) } ?: return emptyList()
        val rx = Regex("(Home|Away)\\s+(Stronger|Equal|Weaker)\\s+($FORM)(?:\\s*\\(([^)]+)\\))?\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)")
        val home = HashMap<String, JSONObject>(); val away = HashMap<String, JSONObject>()
        for (m in rx.findAll(text)) {
            val g = m.groupValues
            val form = Formations.canonical(g[3]) ?: continue
            val style = (if (g[4].isNotBlank()) enStyle(g[4]) else null) ?: lib[form]?.optString("style")?.ifBlank { null } ?: continue
            val rule = JSONObject().put("formation", form).put("style", style)
                .put("pressure", g[5].toInt()).put("mentality", g[6].toInt()).put("tempo", g[7].toInt())
            val rel = when (g[2]) { "Stronger" -> "F"; "Weaker" -> "U"; else -> "E" }
            (if (g[1] == "Home") home else away).putIfAbsent(rel, rule)
        }
        val out = ArrayList<Cand>()
        for ((rel, h) in home) {
            val form = h.getString("formation")
            h.put("source", "$source/contra-$rival").put("conf", "baixa")
            lib[form]?.let { l -> for (n in listOf("attack", "mid", "def", "marking", "offside", "risk")) if (l.has(n)) h.put(n, l.get(n)) }
            away[rel]?.let { a -> h.put("away", a) }
            out.add(Cand("$rel|$rival", h, 3))
        }
        return out
    }

    // ------------------------------------------------------------ faixas por formação (osmcoach /tactics/*, osmtacticsapp /learn/best-*)

    /** "Formation 4-3-3 A Game plan Wing Play Pressing 70 ( 65–75 ) Style 70 ( 65–75 ) Tempo 67 ( 60–75 )". */
    fun parseBandsCoach(text: String): Pair<String, JSONObject>? {
        val m = Regex("Formation\\s+($FORM)\\s+Game plan\\s+(.+?)\\s+Pressing\\s+(\\d+)\\s*\\(\\s*(\\d+)\\s*[–-]\\s*(\\d+)\\s*\\)\\s+Style\\s+(\\d+)\\s*\\(\\s*(\\d+)\\s*[–-]\\s*(\\d+)\\s*\\)\\s+Tempo\\s+(\\d+)\\s*\\(\\s*(\\d+)\\s*[–-]\\s*(\\d+)\\s*\\)").find(text) ?: return null
        val g = m.groupValues
        val f = Formations.canonical(g[1]) ?: return null
        val o = JSONObject().put("plan", enStyle(g[2]) ?: "").put("src", "osmcoach.com")
            .put("pressure", JSONArray(listOf(g[4].toInt(), g[5].toInt()))).put("mentality", JSONArray(listOf(g[7].toInt(), g[8].toInt()))).put("tempo", JSONArray(listOf(g[10].toInt(), g[11].toInt())))
            .put("ref", JSONArray(listOf(g[3].toInt(), g[6].toInt(), g[9].toInt())))
        return Pair(f, o)
    }

    /** osmtacticsapp: "Style: 55-70. Tempo: 50-70. Pressing: 46-60" para "Best 4-3-3 A tactic". */
    fun parseBandsApp(text: String): Pair<String, JSONObject>? {
        val f = Regex("Best ($FORM) tactic").find(text)?.groupValues?.get(1)?.let { Formations.canonical(it) } ?: return null
        fun r(n: String) = Regex("$n:\\s*(\\d+)\\s*[–-]\\s*(\\d+)").find(text)?.let { JSONArray(listOf(it.groupValues[1].toInt(), it.groupValues[2].toInt())) }
        val st = r("Style") ?: return null; val tp = r("Tempo") ?: return null; val pr = r("Pressing") ?: return null
        return Pair(f, JSONObject().put("src", "osmtacticsapp.com").put("pressure", pr).put("mentality", st).put("tempo", tp))
    }

    // ------------------------------------------------------------ junção de candidatos

    /** Por situação: a de maior prioridade vira a principal e as demais ficam em `alts` (formação+estilo diferentes). */
    fun mergeCands(cands: List<Cand>): JSONObject {
        val out = JSONObject()
        for ((key, list) in cands.groupBy { it.key }) {
            val sorted = list.sortedByDescending { it.prio }
            val main = JSONObject(sorted.first().rule.toString())
            val seen = HashSet<String>(); seen.add(main.optString("formation") + "|" + main.optString("style"))
            val alts = JSONArray()
            for (c in sorted.drop(1)) if (seen.add(c.rule.optString("formation") + "|" + c.rule.optString("style")) && alts.length() < 3) alts.put(c.rule)
            if (alts.length() > 0) main.put("alts", alts)
            out.put(key, main)
        }
        return out
    }

    // ------------------------------------------------------------ páginas

    private const val OC = "https://osmcoach.com"
    private const val NL = "https://www.osmnextlevel.com"
    private const val TA = "https://osmtacticsapp.com"
    private val OC_GUIDES = listOf("best-osm-tactics", "tactical-building-blocks", "referee-and-specialists", "post-match-analysis", "matchday-workflow",
        "lineup-and-fitness", "detailed-matchup-rules", "strength-and-risk", "evidence-confidence", "sources")

    private fun links(html: String, prefix: String, base: String): List<String> =
        Regex("href=\"($prefix[a-z0-9\\-]+/?)\"").findAll(html).map { it.groupValues[1] }.distinct().map { base + it }.toList()

    private suspend fun <T> pmap(items: List<T>, n: Int = 3, f: suspend (T) -> PageRes?): List<PageRes> = coroutineScope {
        val sem = Semaphore(n)
        items.map { async(Dispatchers.IO) { sem.withPermit { runCatching { f(it) }.getOrNull() } } }.awaitAll().filterNotNull()
    }

    suspend fun refresh(ctx: Context): String {
        if (running) return "já está atualizando"
        running = true
        val repo = Repo(ctx)
        val now = System.currentTimeMillis()
        Settings.put(ctx, "kb_try_at", now.toString())
        try {
            val prev = repo.dao.plan(0, STATUS)?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
            val prevHash = HashMap<String, String>()
            prev?.optJSONArray("pages")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { prevHash[it.optString("url")] = it.optString("hash") } }
            val cands = java.util.Collections.synchronizedList(ArrayList<Cand>())
            val bands = JSONObject()
            val pages = java.util.Collections.synchronizedList(ArrayList<PageRes>())

            suspend fun read(url: String, parse: ((String, String) -> Int)? = null): PageRes? {
                val (code, html) = httpGet(url)
                if (html == null) return PageRes(url, code, 0, 0, "", false, "sem resposta").also { pages.add(it) }
                val text = toText(html)
                val h = sha(text)
                val n = parse?.invoke(html, text) ?: 0
                return PageRes(url, code, text.length, n, h, prevHash[url]?.let { it != h } ?: false).also { pages.add(it) }
            }

            // 1) biblioteca do osmcoach primeiro (as regras de linhas alimentam as demais)
            val libMap = HashMap<String, JSONObject>()
            read("$OC/guides/core-tactic-library") { _, t -> parseLibrary(t).also { l -> cands.addAll(l); for (c in l) libMap.putIfAbsent(c.rule.optString("formation"), c.rule) }.size }

            // 2) índices -> páginas por formação
            val ocCounters = httpGet("$OC/counters").second?.let { links(it, "/counter/", OC) }.orEmpty()
            val ocTactics = httpGet("$OC/tactics").second?.let { links(it, "/tactics/", OC) }.orEmpty()
            val nlForms = httpGet("$NL/blog/osm-best-tactic/").second?.let { links(it, "/formations/", NL) }.orEmpty()
            val taLearn = httpGet("$TA/learn").second?.let { links(it, "/learn/", TA) }.orEmpty()
            val lib = libMap.toMap()

            pmap(ocCounters) { u -> read(u) { _, t -> parseCounter(t, lib).also { cands.addAll(it) }.size } }
            pmap(ocTactics) { u -> read(u) { _, t -> parseBandsCoach(t)?.let { (f, o) -> synchronized(bands) { if (!bands.has(f)) bands.put(f, o) }; 1 } ?: 0 } }
            pmap(nlForms) { u -> read(u) { _, t -> parseNextLevel(t, lib).also { cands.addAll(it) }.size } }
            pmap(taLearn) { u ->
                if (Regex("/learn/best-.*-tactic-osm$").containsMatchIn(u)) read(u) { _, t -> parseBandsApp(t)?.let { (f, o) -> synchronized(bands) { if (!bands.has(f)) bands.put(f, o) }; 1 } ?: 0 }
                else read(u)
            }
            // 3) guias de texto e demais páginas: lidas e conferidas por mudança (resumo só se o usuário tiver IA, ao gerar tática)
            val texts = OC_GUIDES.map { "$OC/guides/$it" } + listOf("$OC/tools/matchup-builder", "$NL/blog/osm-best-tactic/", "$NL/counter-tactics/",
                "$NL/blog/osm-weekly-meta-guide/", "$NL/blog/osm-beat-stronger-teams/", "https://osmhelper.com/tactics/", "$TA/learn")
            pmap(texts) { u -> read(u) }

            // formato mudou e há IA: pede a extração das regras da página da biblioteca (marcada como IA)
            if (cands.none { it.prio == 2 } && AiClient.hasAnyKey(ctx)) {
                httpGet("$OC/guides/core-tactic-library").second?.let { html ->
                    val reply = AiClient.ask(ctx, MetaRefresh.prompt() + "\n\nTEXTO DA PÁGINA (use só o que está aqui):\n" + toText(html).take(9000), null, 90000L)
                    AiClient.parseJson(reply.text)?.let { j ->
                        val c = MetaRefresh.cleanRules(j, " • extraído por IA")
                        for (k in c.keys()) cands.add(Cand(k, c.getJSONObject(k), 1))
                    }
                }
            }

            val rules = mergeCands(cands.toList())
            val st = JSONObject().put("at", now).put("rules", rules.length()).put("bands", bands.length())
                .put("pages", JSONArray().also { a -> for (p in pages.sortedBy { it.url }) a.put(JSONObject().put("url", p.url).put("http", p.http).put("chars", p.chars).put("parsed", p.parsed).put("hash", p.hash).put("changed", p.changed).put("note", p.note)) })
            if (rules.length() > 0) {
                repo.dao.putPlan(PlanEntity(0, RULES, JSONObject().put("at", now).put("rules", rules).toString(), now))
                if (bands.length() > 0) repo.dao.putPlan(PlanEntity(0, BANDS, JSONObject().put("at", now).put("bands", bands).toString(), now))
            }
            repo.dao.putPlan(PlanEntity(0, STATUS, st.toString(), now))
            val ok = pages.count { it.http in 200..299 }
            val changed = pages.count { it.changed }
            return if (rules.length() == 0) "li $ok de ${pages.size} páginas, mas nenhuma regra veio do formato atual; mantive o que já havia"
            else "base da web atualizada: $ok de ${pages.size} páginas lidas, ${rules.length()} situações, ${bands.length()} faixas" + (if (changed > 0) ", $changed página(s) mudaram" else "")
        } finally { running = false }
    }

    /** Uma vez por semana, sozinho (não precisa de chave de IA); depois de uma tentativa espera 12 h. */
    fun refreshIfStale(ctx: Context) {
        if (running) return
        val now = System.currentTimeMillis()
        val tried = Settings.get(ctx, "kb_try_at", "0").toLongOrNull() ?: 0L
        if (now - tried < 12L * 3600000L) return
        AppScope.scope.launch(Dispatchers.IO) {
            val st = rulesAt(Repo(ctx))
            if (st == null || now - st > WEEK) runCatching { Diag.log("Base da web: " + refresh(ctx)) }
        }
    }

    suspend fun rulesAt(repo: Repo): Long? = rules(repo)?.optLong("at")?.takeIf { it > 0 }
    suspend fun rules(repo: Repo): JSONObject? = repo.dao.plan(0, RULES)?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
    suspend fun bands(repo: Repo): JSONObject? = repo.dao.plan(0, BANDS)?.json?.let { runCatching { JSONObject(it) }.getOrNull() }?.optJSONObject("bands")
    suspend fun status(repo: Repo): JSONObject? = repo.dao.plan(0, STATUS)?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
}
