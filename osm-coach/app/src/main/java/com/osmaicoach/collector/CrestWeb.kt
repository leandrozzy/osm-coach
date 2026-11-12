package com.osmaicoach.collector

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Escudos REAIS dos clubes, pelo NOME do time (o app já lê todos os nomes: faixa do Calendário, Pré-jogo, central, tabelas).
 * Fonte: TheSportsDB (chave pública de teste "123", ~30 consultas/min): busca por nome e baixa o PNG do escudo, guardado em
 * `crests/web/<time>.png` e reaproveitado em todos os slots. Só o NOME do clube sai do aparelho. Sem resultado (clube pequeno
 * ou nome diferente) nada é gravado e o app segue com o recorte do jogo (`CrestCut`); nova tentativa só depois de 3 dias.
 * Desligável em Ajustes (`web_crests` = 0).
 */
object CrestWeb {
    private const val SEARCH = "https://www.thesportsdb.com/api/v1/json/123/searchteams.php?t="
    private const val MISS_MS = 3L * 86400000L
    private val queue = LinkedHashSet<String>()
    @Volatile private var worker = false
    private val lock = Any()

    private const val VER = "2"   // 2: SVG com largura/altura diferentes do viewBox saía cortado (Cruzeiro, Atlético-PR): refaz os escudos já baixados
    @Volatile private var verChecked = false

    fun dir(filesDir: File): File = File(filesDir, "crests/web").apply {
        mkdirs()
        if (!verChecked) {
            verChecked = true
            val v = File(this, "_v.txt")
            if (!v.exists() || runCatching { v.readText().trim() }.getOrNull() != VER) {
                listFiles()?.filter { it.name.endsWith(".png") || it.name == "_miss.json" }?.forEach { it.delete() }
                v.writeText(VER)
            }
        }
    }

    /** Arquivo do escudo real (nome exato; senão o nome mais parecido). */
    fun find(filesDir: File, team: String?): File? {
        val k = Txt.key(team ?: "").takeIf { it.length >= 3 } ?: return null
        val d = dir(filesDir)
        val exact = File(d, "$k.png")
        if (exact.exists()) return exact
        val all = d.listFiles()?.filter { it.name.endsWith(".png") } ?: return null
        val best = all.maxByOrNull { CrestCut.score(Txt.key(it.name.removeSuffix(".png")), k) } ?: return null
        return best.takeIf { CrestCut.score(Txt.key(it.name.removeSuffix(".png")), k) >= 0.9 }
    }

    /** O time devolvido pela busca é o MESMO clube? (nome parecido, um é início do outro ou todas as palavras de um estão no outro) */
    fun sameClub(osm: String, cand: String): Boolean {
        val a = Txt.key(osm); val b = Txt.key(cand)
        if (a.length < 3 || b.length < 3) return false
        if (a == b || Txt.sim(a, b) >= 0.85 || CrestCut.score(a, b) >= 0.9) return true
        fun toks(s: String) = Txt.norm(s).split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in STOP }.toSet()
        val ta = toks(osm); val tb = toks(cand)
        if (ta.isEmpty() || tb.isEmpty()) return false
        val (sh, lg) = if (ta.size <= tb.size) ta to tb else tb to ta
        return sh.all { it in lg } && sh.joinToString("").length >= 6
    }
    private val STOP = setOf("club", "clube", "fc", "sc", "sk", "sv", "ac", "afc", "cf", "the", "de", "del", "der", "von")

    /** Pede o escudo de um clube (fila única, ritmo de 1 consulta a cada ~2,5 s; o que já existe ou falhou há pouco não repete). */
    fun request(ctx: Context, team: String?) {
        val t = team?.trim() ?: return
        if (Txt.key(t).length < 3 || Fixtures.notAClub(t)) return   // fase da copa / "a definir" não é clube: o escudo vem quando a vaga for preenchida
        if (Settings.get(ctx, "web_crests", "1") != "1") return
        if (find(ctx.filesDir, t) != null) return
        if (missedRecently(ctx.filesDir, t)) return
        synchronized(lock) {
            if (!queue.add(t)) return
            if (worker) return
            worker = true
        }
        AppScope.scope.launch {
            try {
                while (true) {
                    val next = synchronized(lock) { queue.firstOrNull()?.also { queue.remove(it) } } ?: break
                    var slow = false
                    try { slow = fetch(ctx, next) } catch (e: Exception) { Diag.lastError = "Escudo da web: " + (e.message ?: "erro"); slow = true }
                    if (slow) delay(2500)   // só o TheSportsDB tem limite (~30/min); o repositório não
                }
            } finally { synchronized(lock) { worker = false } }
        }
    }

    private fun missFile(filesDir: File) = File(dir(filesDir), "_miss.json")
    private fun missedRecently(filesDir: File, team: String): Boolean = runCatching {
        val f = missFile(filesDir); if (!f.exists()) return false
        val ts = JSONObject(f.readText()).optLong(Txt.key(team), 0L)
        ts > 0 && System.currentTimeMillis() - ts < MISS_MS
    }.getOrDefault(false)
    private fun markMiss(filesDir: File, team: String) = runCatching {
        val f = missFile(filesDir)
        val j = if (f.exists()) JSONObject(f.readText()) else JSONObject()
        j.put(Txt.key(team), System.currentTimeMillis()); f.writeText(j.toString())
    }

    private fun get(url: String, bytes: Boolean): Pair<Int, ByteArray?> = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "OSMCoach/5")
        val code = c.responseCode
        if (code in 200..299) Pair(code, c.inputStream.use { it.readBytes() }) else Pair(code, null)
    } catch (e: Exception) { Pair(-1, null) }

    // ------------------------------------------------------------ nomes alternativos

    private val PREFIX = setOf("fc", "sc", "sk", "sv", "sku", "ask", "tsv", "vfb", "vfl", "vfr", "fk", "ac", "as", "cf", "cd", "ec", "esporte", "clube", "club", "sport", "sociedade", "associacao", "atletico clube")

    /** Variações do nome para procurar: apelido dado pelo usuário, nome como está, "ß→ss", sem parênteses, sem prefixos (FC/SK/SKU…), "Red Bull"→"RB". */
    internal fun variants(team: String, alias: String? = null): List<String> {
        val out = LinkedHashSet<String>()
        alias?.trim()?.takeIf { it.length >= 3 }?.let { out.add(it) }
        out.add(team.trim())
        val folded = team.replace("ß", "ss").replace("ẞ", "SS")
        out.add(folded)
        val noParen = folded.replace(Regex("\\s*\\([^)]*\\)"), "").trim()
        out.add(noParen)
        val toks = noParen.split(Regex("\\s+")).filter { it.isNotBlank() }
        val noPrefix = toks.dropWhile { Txt.norm(it).trim('.') in PREFIX }
        if (noPrefix.isNotEmpty()) out.add(noPrefix.joinToString(" "))
        if (noPrefix.size > 1 && noPrefix.last().matches(Regex("(?i)ii|iii|b|2")) ) out.add(noPrefix.dropLast(1).joinToString(" "))
        out.add(noParen.replace(Regex("(?i)red bull"), "RB"))
        return out.filter { Txt.key(it).length >= 3 }
    }

    private fun aliasFile(fd: File) = File(dir(fd), "_alias.json")
    fun aliasOf(fd: File, team: String): String? = runCatching { JSONObject(aliasFile(fd).readText()).optString(Txt.key(team)).takeIf { it.isNotBlank() } }.getOrNull()

    /** O usuário diz com que outro nome procurar o escudo deste clube; limpa o "não achei" e pede de novo. */
    fun setAlias(ctx: Context, team: String, alias: String) {
        val fd = ctx.filesDir
        val f = aliasFile(fd)
        val j = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
        j.put(Txt.key(team), alias.trim()); f.writeText(j.toString())
        runCatching { val m = missFile(fd); if (m.exists()) { val mj = JSONObject(m.readText()); mj.remove(Txt.key(team)); m.writeText(mj.toString()) } }
        request(ctx, team)
    }

    /** Clubes entre [names] que já foram procurados e não achados. */
    fun missing(filesDir: File, names: Collection<String>): List<String> = names.filter { find(filesDir, it) == null && missedRecently(filesDir, it) }

    /** true = consultou o TheSportsDB (precisa do intervalo entre pedidos). O repositório football-logos vem 1º: sem limite e em vetor. */
    private fun fetch(ctx: Context, team: String): Boolean {
        val fd = ctx.filesDir
        if (fetchRepo(ctx, team)) return false
        val queries = variants(team, aliasOf(fd, team)).take(4)
        for (q in queries) {
            val (code, body) = get(SEARCH + java.net.URLEncoder.encode(q, "UTF-8"), true)
            if (code == 429) { Diag.log("Escudo da web: limite de consultas, tento depois"); return true }
            if (body == null) continue
            val arr = runCatching { JSONObject(String(body)).optJSONArray("teams") }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("strTeam"); val badge = o.optString("strBadge")
                if (badge.isBlank() || !(sameClub(q, name) || sameClub(team, name))) continue
                val (c2, png) = get("$badge/small", true)
                val bytes = png?.takeIf { c2 in 200..299 } ?: get(badge, true).second ?: continue
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                if (bmp.width < 40 || bmp.height < 40) { bmp.recycle(); continue }
                bmp.recycle()
                val f = File(dir(fd), Txt.key(team) + ".png")
                f.writeBytes(bytes)
                Diag.log("Escudo real de $team ← $name")
                UiBus.bump()
                return true
            }
        }
        markMiss(fd, team)
        Diag.log("Escudo real de $team: não encontrado na web (uso o recorte do jogo)")
        return true
    }

    // ------------------------------------------------------------ 2ª fonte: repositório football-logos (SVG, licença MIT)

    private const val TREE = "https://api.github.com/repos/JoseArroyave/football-logos/git/trees/main?recursive=1"
    private const val RAW = "https://raw.githubusercontent.com/JoseArroyave/football-logos/main/"
    private const val IDX_MS = 7L * 86400000L
    private const val MAX_SVG = 600_000
    @Volatile private var index: List<String>? = null

    /** Lista dos SVGs do repositório (um pedido por semana, guardado em `_idx.json`). */
    private fun loadIndex(fd: File): List<String> {
        index?.let { return it }
        val f = File(dir(fd), "_idx.json")
        runCatching {
            if (f.exists() && System.currentTimeMillis() - f.lastModified() < IDX_MS) {
                val a = org.json.JSONArray(f.readText()); val l = List(a.length()) { a.getString(it) }
                if (l.isNotEmpty()) { index = l; return l }
            }
        }
        val (code, body) = get(TREE, true)
        val list = if (code in 200..299 && body != null) runCatching {
            val arr = JSONObject(String(body)).getJSONArray("tree")
            (0 until arr.length()).map { arr.getJSONObject(it).optString("path") }.filter { it.startsWith("logos/") && it.endsWith(".svg") }
        }.getOrDefault(emptyList()) else emptyList()
        if (list.isNotEmpty()) { f.writeText(org.json.JSONArray(list).toString()); index = list; return list }
        // sem rede/limite do GitHub: usa o índice velho se existir
        return runCatching { val a = org.json.JSONArray(f.readText()); List(a.length()) { a.getString(it) }.also { index = it } }.getOrDefault(emptyList())
    }

    /** "logos/germany/SV_Waldhof_Mannheim.svg" → "SV Waldhof Mannheim" */
    internal fun nameOfPath(path: String): String = path.substringAfterLast('/').removeSuffix(".svg").replace('_', ' ')

    /** Melhor arquivo do repositório para o nome do clube: nome parecido; empate entre países diferentes = ambíguo (null). */
    internal fun pickPath(team: String, paths: List<String>): String? {
        val k = Txt.key(team)
        val c = paths.filter { sameClub(team, nameOfPath(it)) && !nameOfPath(it).contains("National Team", true) }
        if (c.isEmpty()) return null
        val scored = c.map { it to Txt.sim(k, Txt.key(nameOfPath(it))) }.sortedByDescending { it.second }
        val top = scored[0]
        val rivals = scored.filter { it.second >= top.second - 0.02 && it.first.substringAfter("logos/").substringBefore('/') != top.first.substringAfter("logos/").substringBefore('/') }
        return if (rivals.isEmpty()) top.first else null
    }

    private fun fetchRepo(ctx: Context, team: String): Boolean {
        val fd = ctx.filesDir
        val idx = loadIndex(fd)
        val path = variants(team, aliasOf(fd, team)).firstNotNullOfOrNull { pickPath(it, idx) } ?: return false
        val url = RAW + path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val (code, body) = get(url, true)
        if (code !in 200..299 || body == null || body.size > MAX_SVG) return false
        val bmp = runCatching { renderSvg(body) }.getOrNull() ?: return false
        val f = File(dir(fd), Txt.key(team) + ".png")
        java.io.FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        Diag.log("Escudo real de $team ← ${nameOfPath(path)} (football-logos)")
        UiBus.bump()
        return true
    }

    /**
     * SVG → PNG 256×256 do escudo inteiro. O SVG pode ter largura/altura diferentes do viewBox (Cruzeiro: 316 × viewBox 2092) ou nem ter viewBox
     * (Atlético-PR): sem acertar isso o desenho saía cortado. Define o viewBox quando falta, escala o documento para 512×512, corta nas bordas do
     * desenho (pixels visíveis) e centraliza em 256×256. Null se o desenho ficou vazio.
     */
    internal fun renderSvg(bytes: ByteArray): android.graphics.Bitmap? {
        val svg = com.caverock.androidsvg.SVG.getFromInputStream(bytes.inputStream())
        val dw = svg.documentWidth; val dh = svg.documentHeight
        if (svg.documentViewBox == null && dw > 0f && dh > 0f) svg.setDocumentViewBox(0f, 0f, dw, dh)
        val big = 512
        val pic = if (svg.documentViewBox != null) { svg.setDocumentWidth(big.toFloat()); svg.setDocumentHeight(big.toFloat()); svg.renderToPicture() } else svg.renderToPicture(big, big)
        val full = android.graphics.Bitmap.createBitmap(big, big, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(full).drawPicture(pic, android.graphics.RectF(0f, 0f, big.toFloat(), big.toFloat()))
        val px = IntArray(big * big); full.getPixels(px, 0, big, 0, 0, big, big)
        var x0 = big; var x1 = -1; var y0 = big; var y1 = -1; var n = 0
        for (y in 0 until big) for (x in 0 until big) if ((px[y * big + x] ushr 24) > 24) { n++; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
        if (n < big * big / 40 || x1 <= x0 || y1 <= y0) { full.recycle(); return null }
        val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
        val crop = android.graphics.Bitmap.createBitmap(full, x0, y0, bw, bh); full.recycle()
        val out = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888)
        val sc = 248f / maxOf(bw, bh)
        val dwid = (bw * sc).toInt().coerceAtLeast(1); val dhei = (bh * sc).toInt().coerceAtLeast(1)
        val scaled = android.graphics.Bitmap.createScaledBitmap(crop, dwid, dhei, true); crop.recycle()
        android.graphics.Canvas(out).drawBitmap(scaled, (256 - dwid) / 2f, (256 - dhei) / 2f, null)
        scaled.recycle()
        return out
    }

    /** Situação dos escudos dos [names]: (com escudo real, sem escudo real na web, ainda na fila). */
    fun stats(filesDir: File, names: Collection<String>): Triple<Int, Int, Int> {
        var found = 0; var missed = 0; var pending = 0
        for (n in names) when {
            find(filesDir, n) != null -> found++
            missedRecently(filesDir, n) -> missed++
            else -> pending++
        }
        return Triple(found, missed, pending)
    }

    /** Esquece os "não achei" para tentar de novo (ex.: depois de uma queda de rede). */
    fun clearMisses(filesDir: File) { runCatching { missFile(filesDir).delete() } }
}
