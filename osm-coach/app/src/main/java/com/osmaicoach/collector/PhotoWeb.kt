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
    fun hasKey(ctx: Context) = Settings.get(ctx, KEY, "").trim().length >= 16

    // ------------------------------------------------------------------ escolha do jogador (pura, testada)

    private fun toks(s: String): List<String> = Txt.norm(s).split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }

    /** Termo da busca: a maior palavra do nome (≥3 letras, sem acento), que o campo `search` da API exige. */
    fun searchTerm(name: String): String? =
        toks(name).filter { it.length >= 3 }.maxByOrNull { it.length }

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

    /** Escolhe entre os candidatos da busca: nome E idade (±1) obrigatórios; vários → desempata por nacionalidade/idade exata; ainda vários = ambíguo. */
    fun pick(osmName: String, age: Int?, iso: String?, cands: List<Cand>): Pick {
        val want = natEnglish(iso)
        val ok = cands.filter { it.photo != null && nameOk(osmName, it) && (age == null || it.age == null || Math.abs(it.age - age) <= 1) }
        if (ok.isEmpty()) return Pick(null, "miss")
        if (ok.size == 1) return Pick(ok[0], "ok")
        val byNat = ok.filter { natMatch(want, it) == true }
        val pool = if (byNat.isNotEmpty()) byNat else return Pick(null, "amb")
        if (pool.size == 1) return Pick(pool[0], "ok")
        val exact = pool.filter { age != null && it.age == age }
        return if (exact.size == 1) Pick(exact[0], "ok") else Pick(null, "amb")
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

    /** (com foto, jogadores dos slots, sem acerto/ambíguos) para o painel de Ajustes. */
    suspend fun stats(ctx: Context): Triple<Int, Int, Int> {
        val keys = candidatesAll(Repo(ctx)).map { it.nameKey }.toSet()
        val ix = idx(ctx.filesDir)
        val withPhoto = keys.count { file(ctx.filesDir, it) != null }
        val noMatch = keys.count { file(ctx.filesDir, it) == null && (ix.optJSONObject(it)?.optString("s") ?: "") in listOf("miss", "amb") }
        return Triple(withPhoto, keys.size, noMatch)
    }

    // ------------------------------------------------------------------ fila

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

    /** Chamado pelo monitor, ao abrir o app e ao salvar a chave. Vários chamados juntos viram um só; no máx. 1 rodada a cada 10 min. */
    suspend fun run(ctx: Context, force: Boolean = false) {
        if (running || !hasKey(ctx) || Settings.get(ctx, "web_photos", "1") != "1") return
        if (!force && System.currentTimeMillis() - lastRun < 10 * 60_000L) return
        if (blockedToday(ctx) || usedToday(ctx) >= BUDGET) return
        running = true
        try {
            lastRun = System.currentTimeMillis()
            val repo = Repo(ctx)
            val apiKey = Settings.get(ctx, KEY, "").trim()
            val ix = idx(ctx.filesDir)
            val now = System.currentTimeMillis()
            val queue = candidatesAll(repo).filter { p ->
                if (file(ctx.filesDir, p.nameKey) != null) false
                else {
                    val e = ix.optJSONObject(p.nameKey)
                    e == null || now - e.optLong("at", 0L) > RETRY_MS
                }
            }
            if (queue.isEmpty()) return
            val isoMap = FlagManual.all(repo)
            var done = 0
            for (p in queue) {
                if (usedToday(ctx) >= BUDGET) { block(ctx); Diag.log("Fotos: cota do dia usada ($BUDGET), volto amanhã"); break }
                val term = searchTerm(p.name) ?: continue
                val iso = isoMap[p.nameKey] ?: repo.dao.plan(p.slotId, "natiso2")?.json?.let { runCatching { JSONObject(it).optString(p.nameKey) }.getOrNull() }?.takeIf { it.isNotBlank() }
                val (code, body, hdr) = get("$HOST/players/profiles?search=" + java.net.URLEncoder.encode(term, "UTF-8"), apiKey)
                addUsed(ctx)
                if (code == 429) { Diag.log("Fotos: limite por minuto, espero e tento de novo"); delay(65_000L); continue }
                if (code == 401 || code == 403) { Diag.log("Fotos: chave recusada pela API ($code)"); block(ctx); break }
                if (body == null) { Diag.log("Fotos: sem resposta da API ($code)"); break }
                val (cands, daily, minute) = parseCands(String(body, Charsets.UTF_8))
                if (daily) { block(ctx); Diag.log("Fotos: a API avisou que a cota do dia acabou, volto amanhã"); break }
                if (minute) { delay(65_000L); continue }
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
                if (hdr["daily"] == "0") { block(ctx); break }
                delay(GAP_MS)
            }
            if (done > 0) UiBus.bump()
        } finally { running = false }
    }

    /** Apaga o índice de "sem acerto" (tentar de novo hoje, respeitando a cota). */
    fun retryMisses(ctx: Context) {
        val ix = idx(ctx.filesDir)
        for (k in ix.keys().asSequence().toList()) if (ix.optJSONObject(k)?.optString("s") != "ok") ix.remove(k)
        saveIdx(ctx.filesDir, ix)
        prefs(ctx).edit().remove("blocked").apply()
    }
}
