package com.osmaicoach.collector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Fotos REAIS dos jogadores dos seus 4 slots, pela API-Football (api-sports.io) com a chave do próprio usuário (Ajustes → Fotos de jogadores).
 * Só os jogadores que o app já tem no elenco (owner MY) e só o nome sai do aparelho. Uma consulta por jogador (`/players/profiles?search=`),
 * confirmada por nome + idade (±1) + nacionalidade; ambíguo ou sem acerto = sem foto (foto de outro jogador é pior que nenhuma). A imagem vem de
 * media.api-sports.io (sem chave) e fica em `photos/<nameKey>.png`. Cota: no máx. [BUDGET] consultas por dia (plano grátis = 100/dia e 10/min);
 * ao esgotar ou ao a API avisar, para até o dia seguinte (UTC, quando a cota volta) e segue de onde parou. Quem já tem foto/resultado não é consultado de novo.
 */
object PhotoWeb {
    const val KEY = "apifootball_key"
    const val BUDGET = 90
    private const val ALGO = "3"   // 3: busca pelo NOME INTEIRO (2ª tentativa: palavra principal sem partículas) e escolha por pontos
    private const val HOST = "https://v3.football.api-sports.io"
    private const val RETRY_MS = 7L * 86400000L
    private const val GAP_MS = 6500L          // plano grátis: 10 consultas/min
    @Volatile private var running = false
    @Volatile private var lastRun = 0L

    class Cand(val id: Int, val name: String, val first: String, val last: String, val age: Int?, val nationality: String?, val photo: String?)
    class Pick(val cand: Cand?, val state: String)   // state: ok | miss | amb

    fun dir(filesDir: File): File = File(filesDir, "photos").apply { mkdirs() }
    fun file(filesDir: File, nameKey: String): File? = File(dir(filesDir), "$nameKey.png").takeIf { it.exists() && it.length() > 200 }
    fun bitmap(filesDir: File, nameKey: String): Bitmap? = file(filesDir, nameKey)?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
    /** Recortes do rosto tirados dos cartões do campo do OSM (plano B quando a API não achou o jogador). */
    private const val OSM_VER = "4"   // 4: corte mais baixo (queixo), ombros atrás, cartão grande: refaz os recortes antigos
    @Volatile private var osmChecked = false
    fun osmDir(filesDir: File): File = File(filesDir, "photos/osm").apply {
        mkdirs()
        if (!osmChecked) {
            osmChecked = true
            val v = File(this, "_v.txt")
            if (!v.exists() || runCatching { v.readText().trim() }.getOrNull() != OSM_VER) {
                listFiles()?.filter { it.name.endsWith(".png") || it.name == "_q.json" }?.forEach { it.delete() }
                v.writeText(OSM_VER)
            }
        }
    }
    fun osmBitmap(filesDir: File, nameKey: String): Bitmap? =
        File(osmDir(filesDir), "$nameKey.png").takeIf { it.exists() && it.length() > 200 }?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
    /** Foto para o Elenco: a da API; senão o recorte do cartão do OSM; senão null (a tela desenha a silhueta no mesmo estilo). */
    fun bitmapAny(filesDir: File, nameKey: String): Bitmap? = bitmap(filesDir, nameKey) ?: osmBitmap(filesDir, nameKey)
    private fun noneFile(filesDir: File) = File(osmDir(filesDir), "_none.json")
    /** Jogadores que o OSM mostra com SILHUETA (sem foto): abrir o cartão não adianta, só a API pode achar a foto. */
    fun noneSet(filesDir: File): Set<String> = runCatching { org.json.JSONArray(noneFile(filesDir).readText()).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrNull() ?: emptySet()
    fun markNone(filesDir: File, key: String, on: Boolean) {
        val cur = noneSet(filesDir).toMutableSet()
        if (!(if (on) cur.add(key) else cur.remove(key))) return
        runCatching { noneFile(filesDir).writeText(org.json.JSONArray(cur.toList()).toString()) }
    }

    /**
     * Quem o usuário precisa abrir no Plantel (toque no jogador = cartão grande): sem foto da API, sem recorte do OSM e que NÃO é silhueta no OSM.
     * Só entra depois de a API já ter tentado (sem acerto/ambíguo) ou se não há chave: quem ainda está na fila da API pode ganhar a foto sozinho.
     */
    suspend fun openList(ctx: Context, slot: Int): List<String> {
        val repo = Repo(ctx)
        val st = repo.fieldMap(slot)[K.SLOT_STATE]?.value?.lowercase().orEmpty()
        if (st in listOf("preepoca", "procurando", "vazio")) return emptyList()
        val ix = idx(ctx.filesDir); val none = noneSet(ctx.filesDir)
        val apiOn = hasKey(ctx) && Settings.get(ctx, "web_photos", "1") == "1"
        return repo.dao.playersOf(slot).filter { it.owner == "MY" }.filter { p ->
            file(ctx.filesDir, p.nameKey) == null && !File(osmDir(ctx.filesDir), "${p.nameKey}.png").exists() && p.nameKey !in none &&
                (!apiOn || (ix.optJSONObject(p.nameKey)?.optString("s") ?: "") in listOf("miss", "amb"))
        }.sortedByDescending { it.strength ?: 0 }.map { it.name }
    }

    fun hasKey(ctx: Context) = Settings.get(ctx, KEY, "").trim().length >= 16

    // ------------------------------------------------------------------ escolha do jogador (pura, testada)

    private fun toks(s: String): List<String> = Txt.norm(s).split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }

    /** Termos da busca em ordem: o NOME INTEIRO como aparece ("van de Ven"; sem acento/hífen) e, só se vier vazio, a palavra principal. */
    fun searchTerms(name: String): List<String> {
        val full = toks(name).joinToString(" ")
        val out = ArrayList<String>()
        if (full.replace(" ", "").length >= 3) out.add(full)
        searchTerm(name)?.let { if (it != full) out.add(it) }
        return out
    }

    private val PARTICLES = setOf("van", "von", "der", "den", "de", "da", "do", "dos", "das", "del", "di", "el", "al", "bin", "ben", "le", "la", "ter", "ten")

    /** Termo da busca: a maior palavra do nome (≥3 letras, sem acento) que não seja partícula ("van de Ven" → "ven"); empate = a última. */
    fun searchTerm(name: String): String? {
        val t = toks(name).filter { it.length >= 3 }
        val real = t.filter { it !in PARTICLES }.ifEmpty { t }
        val max = real.maxOfOrNull { it.length } ?: return null
        return real.last { it.length == max }
    }

    private fun nameOk(osm: String, c: Cand): Boolean {
        val mine = toks(osm).filter { it.length >= 3 }
        if (mine.isEmpty()) return false
        val theirs = toks(c.name + " " + c.first + " " + c.last).toSet()
        if (mine.all { it in theirs || theirs.any { t -> t.length >= 4 && Txt.sim(t, it) >= 0.88 } }) return true
        return Txt.sim(Txt.key(osm), Txt.key(c.name)) >= 0.85 || Txt.sim(Txt.key(osm), Txt.key(c.first + c.last)) >= 0.85
    }

    private fun natNorm(s: String?) = Txt.norm(s ?: "").replace("-", " ").replace(Regex("[^a-z ]"), "").trim()

    /** Nacionalidade em inglês a partir do ISO do app; null quando não dá para comparar (Inglaterra/Escócia… ou país desconhecido). */
    fun natEnglish(iso: String?): String? {
        val i = iso?.trim()?.lowercase()?.takeIf { it.length == 2 && it != "gb" } ?: return null
        return Locale("", i.uppercase()).getDisplayCountry(Locale.ENGLISH).takeIf { it.isNotBlank() && it.uppercase() != i.uppercase() }
    }

    private fun natMatch(want: String?, c: Cand): Boolean? {
        val w = natNorm(want).takeIf { it.isNotBlank() } ?: return null
        val n = natNorm(c.nationality).takeIf { it.isNotBlank() } ?: return null
        return n == w || n.contains(w) || w.contains(n)
    }

    private fun score(osmName: String, age: Int?, want: String?, c: Cand): Int {
        var sc = 0
        val k = Txt.key(osmName)
        if (k == Txt.key(c.first + c.last) || k == Txt.key(c.last) || k == Txt.key(c.name)) sc += 4   // nome igual (inteiro, só o sobrenome ou como a API abrevia)
        else if (Txt.sim(k, Txt.key(c.first + c.last)) >= 0.85) sc += 2
        if (age != null && c.age != null) sc += if (c.age == age) 3 else 1
        when (natMatch(want, c)) { true -> sc += 3; false -> sc -= 2; null -> {} }
        return sc
    }

    /**
     * Escolhe entre os candidatos da busca: nome E idade (±1) são obrigatórios; entre os que sobram vence quem tem MAIS pontos (nome igual, idade exata,
     * nacionalidade) com 3 pontos de vantagem sobre o 2º; sem essa vantagem é ambíguo e fica sem foto (foto de outro jogador é pior que nenhuma).
     */
    fun pick(osmName: String, age: Int?, iso: String?, cands: List<Cand>): Pick {
        val want = natEnglish(iso)
        val ok = cands.filter { it.photo != null && nameOk(osmName, it) && (age == null || it.age == null || Math.abs(it.age - age) <= 1) }
        if (ok.isEmpty()) return Pick(null, "miss")
        if (ok.size == 1) return Pick(ok[0], "ok")
        val ranked = ok.map { it to score(osmName, age, want, it) }.sortedByDescending { it.second }
        return if (ranked[0].second - ranked[1].second >= 3) Pick(ranked[0].first, "ok") else Pick(null, "amb")
    }

    fun parseCands(json: String): Triple<List<Cand>, Boolean, Boolean> {   // (candidatos, cota do dia esgotada, limite por minuto)
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return Triple(emptyList(), false, false)
        val err = o.opt("errors")
        var daily = false; var minute = false
        if (err is JSONObject) {
            for (k in err.keys()) {
                val v = err.optString(k).lowercase()
                if (k == "requests" || "limit for the day" in v || "request limit" in v) daily = true
                else if (k == "rateLimit" || "too many requests" in v) minute = true
            }
        }
        val arr = o.optJSONArray("response") ?: return Triple(emptyList(), daily, minute)
        val out = ArrayList<Cand>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i)?.optJSONObject("player") ?: continue
            out.add(Cand(p.optInt("id", -1), p.optString("name"), p.optString("firstname"), p.optString("lastname"),
                if (p.isNull("age")) null else p.optInt("age"), p.optString("nationality").takeIf { it.isNotBlank() }, p.optString("photo").takeIf { it.startsWith("http") }))
        }
        return Triple(out.filter { it.id > 0 }, daily, minute)
    }

    // ------------------------------------------------------------------ estado (índice e cota)

    private fun day(): String = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("photoweb", Context.MODE_PRIVATE)
    fun usedToday(ctx: Context): Int = prefs(ctx).let { if (it.getString("day", "") == day()) it.getInt("used", 0) else 0 }
    fun blockedToday(ctx: Context): Boolean = prefs(ctx).getString("blocked", "") == day()
    private fun addUsed(ctx: Context, n: Int = 1) { prefs(ctx).edit().putString("day", day()).putInt("used", usedToday(ctx) + n).apply() }
    private fun block(ctx: Context) { prefs(ctx).edit().putString("blocked", day()).apply() }

    private fun idxFile(filesDir: File) = File(dir(filesDir), "_idx.json")
    private fun idx(filesDir: File): JSONObject = runCatching { JSONObject(idxFile(filesDir).readText()) }.getOrNull() ?: JSONObject()
    private fun saveIdx(filesDir: File, o: JSONObject) { runCatching { idxFile(filesDir).writeText(o.toString()) } }

    class Status(val api: Int, val osm: Int, val total: Int, val untried: Int, val miss: Int, val key: Boolean, val used: Int, val blocked: Boolean)

    private fun needsTry(filesDir: File, ix: JSONObject, k: String): Boolean {
        if (file(filesDir, k) != null) return false
        val st = ix.optJSONObject(k)?.optString("s")
        return st == null || st == "ok"   // "ok" sem arquivo = foi apagada: busca de novo
    }

    suspend fun status(ctx: Context): Status {
        val keys = candidatesAll(Repo(ctx)).map { it.nameKey }.toSet()
        val ix = idx(ctx.filesDir)
        val api = keys.count { file(ctx.filesDir, it) != null }
        val osm = keys.count { file(ctx.filesDir, it) == null && File(osmDir(ctx.filesDir), "$it.png").exists() }
        val blockedKeys = noAuto(ctx.filesDir)
        val untried = keys.count { it !in blockedKeys && needsTry(ctx.filesDir, ix, it) }
        val miss = keys.count { file(ctx.filesDir, it) == null && !needsTry(ctx.filesDir, ix, it) }
        return Status(api, osm, keys.size, untried, miss, hasKey(ctx), usedToday(ctx), blockedToday(ctx))
    }

    /** Duas linhas para o Elenco e Ajustes: o que já tem, de onde vem e o que a busca automática está fazendo. */
    fun statusText(s: Status): String {
        val sil = s.total - s.api - s.osm
        val l1 = "${s.api} de ${s.total} com foto da API • ${s.osm} com rosto recortado do OSM • $sil com silhueta"
        val l2 = when {
            !s.key -> "Sem chave da API-Football (Ajustes → Fotos de jogadores): uso o recorte do OSM e a silhueta."
            s.untried > 0 && s.blocked -> "Cota de hoje usada (${s.used}/$BUDGET): continuo amanhã, faltam ${s.untried} jogadores."
            s.untried > 0 -> "Buscando sozinho: faltam ${s.untried} jogadores (hoje ${s.used}/$BUDGET consultas). Jogador novo entra na fila na hora."
            s.miss > 0 -> "Todos já foram tentados. ${s.miss} sem acerto seguro ficam com recorte/silhueta; só tento de novo esses depois de uns dias."
            else -> "Todos os jogadores já têm foto."
        }
        return "$l1\n$l2"
    }

    // ------------------------------------------------------------------ fila

    private fun noAutoFile(filesDir: File) = File(dir(filesDir), "_noauto.json")
    fun noAuto(filesDir: File): Set<String> = runCatching { org.json.JSONArray(noAutoFile(filesDir).readText()).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrNull() ?: emptySet()
    private fun setNoAuto(filesDir: File, key: String, on: Boolean) {
        val cur = noAuto(filesDir).toMutableSet()
        if (on) cur.add(key) else cur.remove(key)
        runCatching { noAutoFile(filesDir).writeText(org.json.JSONArray(cur.toList()).toString()) }
    }

    private suspend fun candidatesAll(repo: Repo): List<PlayerEntity> {
        val out = ArrayList<PlayerEntity>()
        for (slot in 1..4) {
            val st = repo.fieldMap(slot)[K.SLOT_STATE]?.value?.lowercase().orEmpty()
            if (st in listOf("preepoca", "procurando", "vazio")) continue
            out += repo.dao.playersOf(slot).filter { it.owner == "MY" && it.nameKey.length >= 2 && (searchTerm(it.name) != null) }.sortedByDescending { it.strength ?: 0 }
        }
        return out.distinctBy { it.nameKey }
    }

    private fun get(url: String, key: String?): Triple<Int, ByteArray?, Map<String, String>> = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "OSMCoach/5")
        if (key != null) c.setRequestProperty("x-apisports-key", key)
        val code = c.responseCode
        val hdr = mapOf("daily" to (c.getHeaderField("x-ratelimit-requests-remaining") ?: ""), "min" to (c.getHeaderField("X-RateLimit-Remaining") ?: ""))
        if (code in 200..299) Triple(code, c.inputStream.use { it.readBytes() }, hdr) else Triple(code, null, hdr)
    } catch (e: Exception) { Triple(-1, null, emptyMap()) }

    /** Chamado pelo monitor, ao abrir o app e ao salvar a chave. Vários chamados juntos viram um só; no máx. 1 rodada por minuto. */
    suspend fun run(ctx: Context, force: Boolean = false) {
        runApi(ctx, force)
        runWiki(ctx, force)
    }

    private suspend fun runApi(ctx: Context, force: Boolean) {
        if (running || !hasKey(ctx) || Settings.get(ctx, "web_photos", "1") != "1") return
        if (!force && System.currentTimeMillis() - lastRun < 60_000L) return
        if (blockedToday(ctx) || usedToday(ctx) >= BUDGET) return
        running = true
        try {
            lastRun = System.currentTimeMillis()
            val repo = Repo(ctx)
            val apiKey = Settings.get(ctx, KEY, "").trim()
            val ix = idx(ctx.filesDir)
            if (ix.optString("_algo") != ALGO) {   // regra de escolha melhorada: reavalia UMA vez os sem acerto/ambíguos (os que já têm foto ficam)
                for (k in ix.keys().asSequence().toList()) if (k != "_algo" && ix.optJSONObject(k)?.optString("s") != "ok") ix.remove(k)
                ix.put("_algo", ALGO); saveIdx(ctx.filesDir, ix)
            }
            val now = System.currentTimeMillis()
            val blocked = noAuto(ctx.filesDir)
            val all = candidatesAll(repo).filter { file(ctx.filesDir, it.nameKey) == null && it.nameKey !in blocked }
            // 1º quem NUNCA foi tentado (jogador comprado agora vem primeiro); só quando todos foram tentados volta aos sem acerto (um por vez, 7 dias de intervalo)
            val bought = HashSet<String>()
            for (sl in 1..4) Ledger.parse(repo.dao.plan(sl, "ledger")?.json).filter { it.type == "buy" && now - it.at < 3L * 86400000L }.forEach { bought.add(it.key) }
            val untried = all.filter { needsTry(ctx.filesDir, ix, it.nameKey) }.sortedByDescending { it.nameKey in bought }
            val queue = if (untried.isNotEmpty()) untried
            else all.filter { now - (ix.optJSONObject(it.nameKey)?.optLong("at", 0L) ?: 0L) > RETRY_MS }.sortedBy { ix.optJSONObject(it.nameKey)?.optLong("at", 0L) ?: 0L }
            if (queue.isEmpty()) return
            val isoMap = FlagManual.all(repo)
            var done = 0
            for (p in queue) {
                if (usedToday(ctx) >= BUDGET) { block(ctx); Diag.log("Fotos: cota do dia usada ($BUDGET), volto amanhã"); break }
                val terms = searchTerms(p.name)
                if (terms.isEmpty()) continue
                val iso = isoMap[p.nameKey] ?: repo.dao.plan(p.slotId, "natiso3")?.json?.let { runCatching { JSONObject(it).optString(p.nameKey) }.getOrNull() }?.takeIf { it.isNotBlank() }
                var cands: List<Cand> = emptyList()
                var stop = false; var again = false; var zero = false
                for ((ti, term) in terms.withIndex()) {
                    val (code, body, hdr) = get("$HOST/players/profiles?search=" + java.net.URLEncoder.encode(term, "UTF-8"), apiKey)
                    addUsed(ctx)
                    if (code == 429) { Diag.log("Fotos: limite por minuto, espero e tento de novo"); delay(65_000L); again = true; break }
                    if (code == 401 || code == 403) { Diag.log("Fotos: chave recusada pela API ($code)"); block(ctx); stop = true; break }
                    if (body == null) { Diag.log("Fotos: sem resposta da API ($code)"); stop = true; break }
                    val parsed = parseCands(String(body, Charsets.UTF_8))
                    if (parsed.second) { block(ctx); Diag.log("Fotos: a API avisou que a cota do dia acabou, volto amanhã"); stop = true; break }
                    if (parsed.third) { delay(65_000L); again = true; break }
                    cands = parsed.first
                    if (hdr["daily"] == "0") zero = true
                    if (cands.isNotEmpty() || ti == terms.lastIndex || usedToday(ctx) >= BUDGET || zero) break
                    delay(GAP_MS)   // nome inteiro sem resultado: tenta a palavra principal
                }
                if (stop) break
                if (again) continue
                val pk = pick(p.name, p.age, iso, cands)
                val e = JSONObject().put("s", pk.state).put("at", System.currentTimeMillis())
                if (pk.cand != null) {
                    val img = pk.cand.photo?.let { get(it, null).second }
                    val ok = img != null && runCatching { BitmapFactory.decodeByteArray(img, 0, img.size) }.getOrNull()?.also { it.recycle() } != null
                    if (ok) {
                        File(dir(ctx.filesDir), "${p.nameKey}.png").writeBytes(img!!)
                        e.put("id", pk.cand.id); done++
                        Diag.log("Foto de ${p.name} ← ${pk.cand.name} (${pk.cand.nationality ?: "?"}, ${pk.cand.age ?: "?"})")
                    } else e.put("s", "miss")
                } else Diag.log("Foto de ${p.name}: ${if (pk.state == "amb") "mais de um candidato, não escolhi" else "sem acerto seguro"} (${cands.size} resultados)")
                ix.put(p.nameKey, e); saveIdx(ctx.filesDir, ix)
                if (done > 0 && done % 3 == 0) UiBus.bump()
                if (zero) { block(ctx); break }
                delay(GAP_MS)
            }
            if (done > 0) UiBus.bump()
        } finally { running = false }
    }

    /** Chave nova: libera o bloqueio do dia (401/403 da chave antiga). Os "sem acerto" NÃO são limpos: só voltam depois de tentar todos. */
    fun keyChanged(ctx: Context) { prefs(ctx).edit().remove("blocked").apply() }

    // ------------------------------------------------------------------ Wikidata + Wikimedia Commons (sem chave e sem cota)

    private const val WIKI_UA = "OSMCoach/5 (app pessoal de futebol)"
    private const val WIKI_RETRY_MS = 14L * 86400000L
    @Volatile private var wikiRunning = false
    @Volatile private var lastWiki = 0L

    class WikiHit(val qid: String, val desc: String)

    /** A descrição do Wikidata ("Senegalese association football player (born 1998)") tem o ano de nascimento: confere com a idade do jogo (±1 por causa do aniversário). */
    fun wikiYearOk(desc: String, age: Int?, nowYear: Int): Boolean {
        val a = age ?: return false
        val y = Regex("born (\\d{4})").find(desc)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return y == nowYear - a || y == nowYear - a - 1
    }

    /** Candidatos da busca que são jogador de futebol nascido no ano certo; com nacionalidade conhecida e algum candidato dela, só esses. */
    fun wikiFilter(hits: List<WikiHit>, age: Int?, natEn: String?, nowYear: Int): List<WikiHit> {
        val ok = hits.filter { val d = it.desc.lowercase(); (d.contains("football") || d.contains("soccer")) && wikiYearOk(it.desc, age, nowYear) }
        val n4 = natEn?.lowercase()?.take(4)
        if (n4 != null && n4.length >= 3) ok.filter { it.desc.lowercase().contains(n4) }.takeIf { it.isNotEmpty() }?.let { return it }
        return ok
    }

    /** O nome do rótulo da entidade tem que conter as palavras do nome do jogo (sem partículas). */
    fun wikiLabelOk(osm: String, label: String): Boolean {
        val mine = toks(osm).filter { it.length >= 3 && it !in PARTICLES }
        if (mine.isEmpty()) return false
        val theirs = toks(label).toSet()
        return mine.all { it in theirs || theirs.any { t -> t.length >= 4 && Txt.sim(t, it) >= 0.88 } }
    }

    private fun wget(url: String): ByteArray? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000; c.readTimeout = 25000
        c.setRequestProperty("User-Agent", WIKI_UA)
        if (c.responseCode in 200..299) c.inputStream.use { it.readBytes() } else null
    } catch (e: Exception) { null }

    class Missing(val key: String, val name: String, val slot: Int, val reason: String)

    /** Quantos vieram do Wikidata/Commons e quem ainda está sem foto (com o motivo), para o usuário escolher à mão em Ajustes. */
    suspend fun wikiReport(ctx: Context): Pair<Int, List<Missing>> {
        val wx = wikiIdx(ctx.filesDir)
        val ix = idx(ctx.filesDir)
        val all = candidatesAll(Repo(ctx))
        val ok = all.count { file(ctx.filesDir, it.nameKey) != null && wx.optJSONObject(it.nameKey)?.optString("s") == "ok" }
        val miss = all.filter { file(ctx.filesDir, it.nameKey) == null }.map { p ->
            val w = wx.optJSONObject(p.nameKey)?.optString("s")
            val a = ix.optJSONObject(p.nameKey)?.optString("s")
            val why = when {
                w == "miss" -> "não achei no Wikidata (nenhum jogador nascido em ${p.age?.let { java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - it } ?: "?"} com esse nome e foto)"
                w == "amb" -> "mais de um jogador possível, não escolhi"
                w == "err" -> "erro ao salvar a foto"
                a == "miss" || a == "amb" -> "a API não achou; Wikidata ainda não tentou"
                else -> "ainda não tentei (a fila anda sozinha)"
            }
            Missing(p.nameKey, p.name, p.slotId, why)
        }
        return ok to miss
    }

    /** Lê a imagem escolhida na galeria já reduzida (foto de câmera tem 12+ MP). */
    fun decodeUri(ctx: Context, uri: android.net.Uri): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > 1600) sample *= 2
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }.getOrNull()

    /** Foto escolhida à mão: recorta o quadrado (retrato: do alto), grava como as outras (`photos/<nameKey>.png`) e marca como manual. */
    suspend fun saveManual(ctx: Context, key: String, bmp: Bitmap): Boolean {
        val out = PhotoFit.fitBitmap(bmp)
        val ok = runCatching { File(dir(ctx.filesDir), "$key.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) } }.isSuccess
        out.recycle()
        if (ok) {
            val wx = wikiIdx(ctx.filesDir)
            wx.put(key, JSONObject().put("s", "manual").put("at", System.currentTimeMillis()))
            runCatching { wikiFile(ctx.filesDir).writeText(wx.toString()) }
            UiBus.bump()
        }
        return ok
    }

    // ------------------------------------------------------------------ gestão de imagens (Ajustes → Imagens e diálogo do jogador)

    /** source: manual | wiki | api | osm (recorte do cartão do OSM) | none. */
    class Item(val key: String, val name: String, val slot: Int, val age: Int?, val source: String, val at: Long, val bytes: Long, val blocked: Boolean)

    fun sourceOf(filesDir: File, key: String): String {
        val f = file(filesDir, key)
        if (f != null) {
            return when (wikiIdx(filesDir).optJSONObject(key)?.optString("s")) { "manual" -> "manual"; "ok" -> "wiki"; else -> "api" }
        }
        return if (File(osmDir(filesDir), "$key.png").exists()) "osm" else "none"
    }

    fun sourceLabel(source: String) = when (source) { "manual" -> "✋ Manual"; "wiki" -> "🌐 Wikipedia"; "api" -> "⚽ API-Football"; "osm" -> "✂ Recorte do OSM"; else -> "▫ Sem foto" }

    fun itemOf(ctx: Context, p: PlayerEntity): Item {
        val src = sourceOf(ctx.filesDir, p.nameKey)
        val f = file(ctx.filesDir, p.nameKey) ?: File(osmDir(ctx.filesDir), "${p.nameKey}.png").takeIf { it.exists() }
        return Item(p.nameKey, p.name, p.slotId, p.age, src, f?.lastModified() ?: 0L, f?.length() ?: 0L, p.nameKey in noAuto(ctx.filesDir))
    }

    /** Todos os jogadores meus dos slots ativos, com a origem da foto de cada um. */
    suspend fun items(ctx: Context): List<Item> = candidatesAll(Repo(ctx)).map { itemOf(ctx, it) }

    /** Apaga a foto e NÃO busca de novo sozinho (o jogador fica com recorte/silhueta até você escolher outra). */
    fun removePhoto(ctx: Context, key: String) {
        File(dir(ctx.filesDir), "$key.png").delete()
        setNoAuto(ctx.filesDir, key, true)
        val wx = wikiIdx(ctx.filesDir); wx.remove(key); runCatching { wikiFile(ctx.filesDir).writeText(wx.toString()) }
        UiBus.bump()
    }

    /** Apaga a foto e libera o jogador para a busca automática (API e Wikipedia) tentar de novo. */
    fun retryPhoto(ctx: Context, key: String) {
        File(dir(ctx.filesDir), "$key.png").delete()
        setNoAuto(ctx.filesDir, key, false)
        val wx = wikiIdx(ctx.filesDir); wx.remove(key); runCatching { wikiFile(ctx.filesDir).writeText(wx.toString()) }
        val ix = idx(ctx.filesDir); ix.remove(key); saveIdx(ctx.filesDir, ix)
        UiBus.bump()
    }

    /** Apaga as fotos de uma origem (wiki/api/manual) e libera a busca (exceto manual, que só apaga). Devolve quantas. */
    suspend fun clearSource(ctx: Context, source: String): Int {
        var n = 0
        for (it in items(ctx)) if (it.source == source) { if (source == "manual") removePhoto(ctx, it.key) else retryPhoto(ctx, it.key); n++ }
        return n
    }

    /** Libera para nova busca todos que ficaram sem foto (sem acerto/ambíguos) e os bloqueados. */
    suspend fun retryMissing(ctx: Context): Int {
        var n = 0
        for (it in items(ctx)) if (it.source == "none" || it.source == "osm") { retryPhoto(ctx, it.key); n++ }
        return n
    }

    fun totalBytes(filesDir: File): Long = dir(filesDir).listFiles()?.filter { it.isFile && it.name.endsWith(".png") }?.sumOf { it.length() } ?: 0L

    /** Salva uma cópia de todas as fotos em Downloads/OSM Coach (um .zip). Devolve o nome do arquivo ou null. */
    suspend fun exportZip(ctx: Context): String? {
        val its = items(ctx).filter { it.source in listOf("manual", "wiki", "api") }
        if (its.isEmpty()) return null
        val name = "osm-coach-fotos-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".zip"
        return runCatching {
            val cv = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/OSM Coach")
            }
            val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return@runCatching null
            ctx.contentResolver.openOutputStream(uri)?.use { os ->
                java.util.zip.ZipOutputStream(os).use { z ->
                    for (it in its) {
                        val f = file(ctx.filesDir, it.key) ?: continue
                        z.putNextEntry(java.util.zip.ZipEntry(Txt.key(it.name) + "_" + it.source + ".png"))
                        z.write(f.readBytes()); z.closeEntry()
                    }
                }
            }
            name
        }.getOrNull()
    }

    private fun wikiFile(filesDir: File) = File(dir(filesDir), "_wiki.json")
    private fun wikiIdx(filesDir: File): JSONObject = runCatching { JSONObject(wikiFile(filesDir).readText()) }.getOrNull() ?: JSONObject()

    /** Foto real pela Wikidata/Commons para quem ficou sem foto da API (sem acerto/ambíguo, sem chave ou cota do dia usada). Roda sozinho, sem chave. */
    private suspend fun runWiki(ctx: Context, force: Boolean) {
        if (wikiRunning || Settings.get(ctx, "web_photos", "1") != "1") return
        if (!force && System.currentTimeMillis() - lastWiki < 60_000L) return
        wikiRunning = true
        try {
            lastWiki = System.currentTimeMillis()
            val repo = Repo(ctx)
            val ix = idx(ctx.filesDir)
            val wx = wikiIdx(ctx.filesDir)
            val now = System.currentTimeMillis()
            val apiOn = hasKey(ctx) && !blockedToday(ctx) && usedToday(ctx) < BUDGET
            val blocked = noAuto(ctx.filesDir)
            val queue = candidatesAll(repo).filter { p ->
                p.nameKey !in blocked && file(ctx.filesDir, p.nameKey) == null && now - (wx.optJSONObject(p.nameKey)?.optLong("at", 0L) ?: 0L) > WIKI_RETRY_MS &&
                    !(apiOn && needsTry(ctx.filesDir, ix, p.nameKey))   // a API vai primeiro enquanto ainda não tentou este jogador
            }.take(40)
            if (queue.isEmpty()) return
            val isoMap = FlagManual.all(repo)
            val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            var done = 0
            for (p in queue) {
                val q = toks(p.name).joinToString(" ")
                if (q.length < 3) continue
                val iso = isoMap[p.nameKey] ?: repo.dao.plan(p.slotId, "natiso3")?.json?.let { runCatching { JSONObject(it).optString(p.nameKey) }.getOrNull() }?.takeIf { it.isNotBlank() }
                fun mark(state: String) { wx.put(p.nameKey, JSONObject().put("s", state).put("at", System.currentTimeMillis())); runCatching { wikiFile(ctx.filesDir).writeText(wx.toString()) } }
                val sUrl = "https://www.wikidata.org/w/api.php?action=query&list=search&format=json&srlimit=50&srsearch=" +
                    java.net.URLEncoder.encode("$q haswbstatement:P106=Q937857", "UTF-8")
                val sBody = wget(sUrl) ?: run { Diag.log("Foto web: sem resposta do Wikidata, tento depois"); return }
                val hits = runCatching {
                    val arr = JSONObject(String(sBody, Charsets.UTF_8)).getJSONObject("query").getJSONArray("search")
                    (0 until arr.length()).map { WikiHit(arr.getJSONObject(it).getString("title"), arr.getJSONObject(it).optString("snippet")) }
                }.getOrNull()
                if (hits == null) { delay(2500L); continue }
                val cand = wikiFilter(hits, p.age, natEnglish(iso), year).take(6)
                if (cand.isEmpty()) { mark("miss"); Diag.log("Foto web de ${p.name}: nenhum jogador nascido em ${p.age?.let { year - it } ?: "?"} no Wikidata"); delay(1500L); continue }
                delay(1000L)
                val eBody = wget("https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=claims|labels&languages=en&ids=" + cand.joinToString("|") { it.qid })
                if (eBody == null) { delay(2500L); continue }
                val good = ArrayList<Pair<String, String>>()   // (rótulo, arquivo da imagem)
                runCatching {
                    val ents = JSONObject(String(eBody, Charsets.UTF_8)).getJSONObject("entities")
                    for (c in cand) {
                        val e = ents.optJSONObject(c.qid) ?: continue
                        val label = e.optJSONObject("labels")?.optJSONObject("en")?.optString("value") ?: continue
                        if (!wikiLabelOk(p.name, label)) continue
                        val file = e.optJSONObject("claims")?.optJSONArray("P18")?.optJSONObject(0)?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optString("value") ?: continue
                        good.add(label to file)
                    }
                }
                if (good.size != 1) {
                    mark(if (good.isEmpty()) "miss" else "amb")
                    Diag.log("Foto web de ${p.name}: ${if (good.isEmpty()) "sem foto no Wikidata" else "mais de um jogador possível, não escolhi"}")
                    delay(1500L); continue
                }
                delay(1000L)
                val img = wget("https://commons.wikimedia.org/wiki/Special:FilePath/" + java.net.URLEncoder.encode(good[0].second, "UTF-8").replace("+", "%20") + "?width=360")
                val bmp = img?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
                if (bmp == null) { Diag.log("Foto web de ${p.name}: não consegui baixar a imagem, tento depois"); delay(2500L); continue }
                val out = PhotoFit.fitBitmap(bmp)
                val ok = runCatching { File(dir(ctx.filesDir), "${p.nameKey}.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) } }.isSuccess
                bmp.recycle(); out.recycle()
                if (ok) { mark("ok"); done++; Diag.log("Foto web de ${p.name} ← ${good[0].first} (Wikipedia/Commons)"); if (done % 3 == 0) UiBus.bump() } else mark("err")
                delay(1500L)
            }
            if (done > 0) UiBus.bump()
        } finally { wikiRunning = false }
    }
}

/**
 * Recorte do rosto do cartão do OSM: tira o fundo azul (e a moldura/barras), mantém só a maior região do rosto e põe sobre o MESMO fundo claro das fotos
 * da API-Football, (o cartão termina no queixo/pescoço: o rosto fica alinhado embaixo e a borda do círculo faz o corte).
 * A tela corta em círculo. Prototipado nos rostos de prints reais (Sarr, Sano, Musiala, Van de Ven…).
 */
object FaceCut {
    const val BG = 0xFFF3F5F9.toInt()

    /** [px] = recorte [s]×[s]; [big] = cartão grande (fundo azul claro com brilho: tira qualquer pixel azulado). Devolve os pixels finais (opacos). */
    fun cutout(px: IntArray, s: Int, big: Boolean = false): IntArray {
        val bg = BooleanArray(s * s)
        for (y in 0 until s) for (x in 0 until s) {
            val c = px[y * s + x]
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            val navy = (b - r) > 20 && (big || lum < 150f)            // fundo do cartão
            val line = (g > r + 25 && g > b + 25) || (r > 180 && g > 150 && b < 110)   // moldura verde/amarela e barras
            val dx = (x - s * 0.5f) / (s * 0.47f); val dy = (y - s * 0.5f) / (s * 0.54f)
            val outside = if (y < s * 0.55f) dx * dx + dy * dy > 1f else Math.abs(x - s * 0.5f) > s * 0.47f   // elipse só no alto; embaixo o pescoço segue até a borda
            bg[y * s + x] = navy || line || outside
        }
        val keep = BooleanArray(s * s) { !bg[it] }
        // maior região conectada
        val used = BooleanArray(s * s)
        var best: IntArray = IntArray(0)
        val stack = IntArray(s * s)
        for (start in 0 until s * s) {
            if (!keep[start] || used[start]) continue
            var sp = 0; val comp = ArrayList<Int>()
            stack[sp++] = start; used[start] = true
            while (sp > 0) {
                val p = stack[--sp]; comp.add(p)
                val y = p / s; val x = p % s
                if (y > 0 && keep[p - s] && !used[p - s]) { used[p - s] = true; stack[sp++] = p - s }
                if (y < s - 1 && keep[p + s] && !used[p + s]) { used[p + s] = true; stack[sp++] = p + s }
                if (x > 0 && keep[p - 1] && !used[p - 1]) { used[p - 1] = true; stack[sp++] = p - 1 }
                if (x < s - 1 && keep[p + 1] && !used[p + 1]) { used[p + 1] = true; stack[sp++] = p + 1 }
            }
            if (comp.size > best.size) best = comp.toIntArray()
        }
        val face = BooleanArray(s * s); for (p in best) face[p] = true
        // buracos: tudo que não é alcançado pela borda sem cruzar o rosto vira rosto
        val out = BooleanArray(s * s); var sp = 0
        fun seed(p: Int) { if (!face[p] && !out[p]) { out[p] = true; stack[sp++] = p } }
        for (i in 0 until s) { seed(i); seed((s - 1) * s + i); seed(i * s); seed(i * s + s - 1) }
        while (sp > 0) {
            val p = stack[--sp]; val y = p / s; val x = p % s
            if (y > 0) seed(p - s); if (y < s - 1) seed(p + s); if (x > 0) seed(p - 1); if (x < s - 1) seed(p + 1)
        }
        var a = FloatArray(s * s) { if (!out[it]) 1f else 0f }
        for (y in 0 until s) { val f = (1f - (y.toFloat() / s - 0.95f) / 0.05f).coerceIn(0f, 1f); if (f < 1f) for (x in 0 until s) a[y * s + x] *= f }   // pescoço desbota no fim do recorte
        repeat(2) {   // borda suave (caixa 3×3)
            val n = FloatArray(s * s)
            for (y in 0 until s) for (x in 0 until s) {
                var sum = 0f
                for (dy in -1..1) for (dx in -1..1) sum += a[(y + dy).coerceIn(0, s - 1) * s + (x + dx).coerceIn(0, s - 1)]
                n[y * s + x] = sum / 9f
            }
            a = n
        }
        return place(px, a, s)
    }

    /** Rosto a 84% do quadro, centrado e ALINHADO EMBAIXO (o pescoço sai pela borda do círculo, como numa foto recortada de verdade), sobre o fundo claro. */
    private fun place(src: IntArray, alpha: FloatArray, s: Int): IntArray {
        val out = IntArray(s * s) { BG }
        val n = (s * 0.84f).toInt(); val x0 = (s - n) / 2; val y0 = s - n - (s * 0.015f).toInt()
        for (y in 0 until n) for (x in 0 until n) {
            val fx = ((x + 0.5f) * s / n - 0.5f).coerceIn(0f, s - 1.001f); val fy = ((y + 0.5f) * s / n - 0.5f).coerceIn(0f, s - 1.001f)
            val ix = fx.toInt(); val iy = fy.toInt(); val tx = fx - ix; val ty = fy - iy
            fun bil(f: (Int) -> Float): Float {
                val p00 = iy * s + ix
                return (f(p00) * (1 - tx) + f(p00 + 1) * tx) * (1 - ty) + (f(p00 + s) * (1 - tx) + f(p00 + s + 1) * tx) * ty
            }
            val al = bil { alpha[it] }
            if (al <= 0f) continue
            val dst = out[(y0 + y) * s + x0 + x]
            fun ch(sh: Int): Int {
                val c = bil { ((src[it] shr sh) and 255).toFloat() }
                val d = ((dst shr sh) and 255).toFloat()
                return (c * al + d * (1f - al)).toInt().coerceIn(0, 255)
            }
            out[(y0 + y) * s + x0 + x] = (255 shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        return out
    }
}
